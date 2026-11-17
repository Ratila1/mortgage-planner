import { ApiError } from './errors';
import type {
  Api, AppNotification, BankOffer, CalcParams, CalcResult, EarlyRepayment, Me, OAuthProvider, OfferInput, Problem, Scenario,
  ScenarioSummary, StressRow,
  MicroloanType, MicroloanTypeInput, ApplicantProfile, MicroloanApplication,
  MicroloanApplicationStatus, MicroloanCalculationResult, AdminUser, Id, Money,
} from './types';

/**
 * Клиент настоящего API (backend из ветки dev).
 * Access-токен живёт только в памяти, refresh — в httpOnly-cookie (Path=/api/auth),
 * поэтому все запросы идут на тот же хост с credentials: 'include'.
 */

let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;

interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

async function parseProblem(res: Response): Promise<ApiError> {
  let problem: Problem = { status: res.status };
  try {
    problem = { ...problem, ...(await res.json()) };
  } catch {
    // тело не JSON — оставляем только статус
  }
  const retryAfter = Number(res.headers.get('Retry-After')) || undefined;
  return new ApiError(problem, retryAfter);
}

async function refreshAccess(): Promise<boolean> {
  refreshing ??= (async () => {
    try {
      const res = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' });
      if (!res.ok) {
        accessToken = null;
        return false;
      }
      accessToken = ((await res.json()) as TokenResponse).accessToken;
      return true;
    } catch {
      accessToken = null;
      return false;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

async function send(method: string, path: string, body?: unknown, retry = true, auth = true): Promise<Response> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  // Публичные /api/auth/*-запросы не отправляем с Bearer: протухший токен дал бы 401
  // от ресурсного сервера ещё до permitAll (например, /api/auth/oauth/providers).
  if (accessToken && auth && !path.startsWith('/api/auth/')) headers.Authorization = `Bearer ${accessToken}`;

  const res = await fetch(path, {
    method,
    headers,
    credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  // Access истёк (TTL 15 мин): один раз обновляем по refresh-cookie и повторяем запрос.
  if (res.status === 401 && retry && accessToken && !path.startsWith('/api/auth/')) {
    if (await refreshAccess()) return send(method, path, body, false);
  }
  if (!res.ok) throw await parseProblem(res);
  return res;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await send(method, path, body);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

// ---------- нормализация ответов ----------
// После пересчёта сценария бэкенд сериализует params из BigDecimal, и деньги приходят числами,
// а досрочные погашения — с null-полями. Приводим к типам фронта.

type Raw = Record<string, unknown>;
const str = (v: unknown, fallback = '0') => (v === null || v === undefined ? fallback : String(v));
const int = (v: unknown) => (v === null || v === undefined ? undefined : Number(v));

function normalizeRepayment(raw: Raw): EarlyRepayment {
  const base = { amount: str(raw.amount), mode: raw.mode as EarlyRepayment['mode'] };
  if (raw.type === 'REGULAR') {
    return {
      type: 'REGULAR',
      startMonth: int(raw.startMonth) ?? 1,
      everyMonths: int(raw.everyMonths) ?? 1,
      untilMonth: int(raw.untilMonth),
      ...base,
    };
  }
  return { type: 'ONE_TIME', month: int(raw.month) ?? 1, ...base };
}

function normalizeParams(raw: Raw): CalcParams {
  return {
    price: str(raw.price),
    downPayment: str(raw.downPayment),
    termMonths: Number(raw.termMonths),
    annualRate: str(raw.annualRate),
    type: raw.type as CalcParams['type'],
    earlyRepayments: Array.isArray(raw.earlyRepayments) ? (raw.earlyRepayments as Raw[]).map(normalizeRepayment) : [],
  };
}

function normalizeSummary(raw: Raw): ScenarioSummary {
  return {
    monthlyPayment: str(raw.monthlyPayment),
    overpayment: str(raw.overpayment),
    totalPaid: str(raw.totalPaid),
    termMonths: Number(raw.termMonths),
  };
}

function normalizeScenario(raw: Raw): Scenario {
  return {
    id: String(raw.id),
    name: String(raw.name),
    params: normalizeParams((raw.params ?? {}) as Raw),
    summary: normalizeSummary((raw.summary ?? {}) as Raw),
    calcId: String(raw.calcId),
    offerId: raw.offerId ? String(raw.offerId) : null,
    createdAt: String(raw.createdAt),
    updatedAt: String(raw.updatedAt),
  };
}

/** Тело запроса расчёта: только поля CalcRequest, без undefined. */
function calcBody(params: CalcParams) {
  return {
    price: params.price,
    downPayment: params.downPayment,
    termMonths: params.termMonths,
    annualRate: params.annualRate,
    type: params.type,
    earlyRepayments: params.earlyRepayments,
  };
}

function offerBody(input: OfferInput) {
  return {
    bankName: input.bankName,
    rate: input.rate,
    minDownPaymentPercent: input.minDownPaymentPercent,
    maxTermMonths: input.maxTermMonths,
    active: input.active,
  };
}

async function login(path: string, body: unknown) {
  const res = await request<TokenResponse>('POST', path, body);
  accessToken = res.accessToken;
}

function mapBackendStatus(s: unknown): MicroloanApplicationStatus {
  const v = String(s ?? '');
  if (v === 'REJECTED' || v === 'CANCELLED' || v === 'EXPIRED') return 'REJECTED';
  if (v === 'APPROVED' || v === 'ACTIVE' || v === 'REPAID') return 'APPROVED';
  return 'NEW';
}

function mapApplication(raw: Raw): MicroloanApplication {
  return {
    id: String(raw.id),
    userId: raw.userId ? String(raw.userId) : '',
    typeId: String(raw.loanTypeId ?? raw.typeId ?? ''),
    typeName: String(raw.loanTypeName ?? raw.typeName ?? ''),
    amount: str(raw.amount),
    termDays: Number(raw.termDays ?? 0),
    totalAmount: str(raw.total ?? raw.totalAmount ?? raw.amount),
    status: mapBackendStatus(raw.status),
    profile: { fullName: '', phone: '', email: '' },
    createdAt: String(raw.createdAt ?? new Date().toISOString()),
    updatedAt: String(raw.updatedAt ?? raw.createdAt ?? new Date().toISOString()),
  };
}

function mapAdminApplication(raw: Raw): MicroloanApplication {
  const fullName = [raw.lastName, raw.firstName, raw.middleName].filter(Boolean).join(' ');
  return {
    id: String(raw.id),
    userId: raw.userId ? String(raw.userId) : '',
    typeId: String(raw.loanTypeId ?? ''),
    typeName: String(raw.loanTypeName ?? ''),
    amount: str(raw.amount),
    termDays: Number(raw.termDays ?? 0),
    totalAmount: str(raw.total ?? raw.amount),
    status: mapBackendStatus(raw.status),
    profile: { fullName, phone: String(raw.phone ?? ''), email: '' },
    createdAt: String(raw.createdAt ?? new Date().toISOString()),
    updatedAt: String(raw.updatedAt ?? raw.createdAt ?? new Date().toISOString()),
  };
}

export const httpApi: Api = {
  calculate: (params) => request<CalcResult>('POST', '/api/calc', calcBody(params)),
  getCalculation: (calcId) => request<CalcResult>('GET', `/api/calc/${encodeURIComponent(calcId)}`),
  stress: (params, deltas) => request<StressRow[]>('POST', '/api/calc/stress', { ...calcBody(params), deltas }),

  register: (email, password) => request<void>('POST', '/api/auth/register', { email, password }),
  resendVerification: (email) => request<void>('POST', `/api/auth/verify-email/resend?email=${encodeURIComponent(email)}`),
  // Токен передаём в теле POST (не в query GET): он не попадёт в access-логи сервера.
  verifyEmail: (token) => login('/api/auth/verify-email', { token }),
  login: (email, password) => login('/api/auth/login', { email, password }),
  requestLoginLink: (email) => request<void>('POST', '/api/auth/login-link', { email }),
  consumeLoginLink: (token) => login('/api/auth/login-link/consume', { token }),
  restoreSession: () => refreshAccess(),
  async logout() {
    try {
      await request<void>('POST', '/api/auth/logout');
    } finally {
      accessToken = null;
    }
  },
  me: () => request<Me>('GET', '/api/me'),
  oauthUrl: (provider) => `/oauth2/authorization/${provider}`,
  // Ошибка запроса не должна ломать страницу входа — просто не показываем кнопки.
  oauthProviders: () =>
    request<string[]>('GET', '/api/auth/oauth/providers')
      .then((ids) => ids.filter((id): id is OAuthProvider => id === 'google' || id === 'github'))
      .catch(() => []),

  listScenarios: async () => (await request<Raw[]>('GET', '/api/scenarios')).map(normalizeScenario),
  createScenario: async (name, params, offerId) =>
    normalizeScenario(await request<Raw>('POST', '/api/scenarios', { name, params: calcBody(params), offerId: offerId ?? null })),
  deleteScenario: (id) => request<void>('DELETE', `/api/scenarios/${id}`),
  compareScenarios: async (ids) =>
    (await request<Raw[]>('GET', `/api/scenarios/compare?ids=${ids.map(encodeURIComponent).join(',')}`)).map(normalizeScenario),
  requestPdfExport: (id) => request<void>('POST', `/api/scenarios/${id}/export/pdf`),
  downloadPdf: async (id) => (await send('GET', `/api/scenarios/${id}/pdf`)).blob(),

  listNotifications: () => request<AppNotification[]>('GET', '/api/notifications'),
  markNotificationRead: (id) => request<void>('POST', `/api/notifications/${id}/read`),

  listOffers: () => request<BankOffer[]>('GET', '/api/offers'),
  adminListOffers: () => request<BankOffer[]>('GET', '/api/admin/offers'),
  adminCreateOffer: (input) => request<BankOffer>('POST', '/api/admin/offers', offerBody(input)),
  adminUpdateOffer: (id, input) => request<BankOffer>('PUT', `/api/admin/offers/${id}`, offerBody(input)),
  adminDeleteOffer: (id) => request<void>('DELETE', `/api/admin/offers/${id}`),

  // Microloan API -> backend loan-types / loans / admin endpoints
  async listMicroloanTypes() {
    const raw = await request<Raw[]>('GET', '/api/loan-types');
    return raw.map((t) => ({
      id: String(t.id),
      name: String(t.name),
      minAmount: str(t.minAmount),
      maxAmount: str(t.maxAmount),
      gracePeriodDays: 0,
      dailyRatePct: str(t.dailyRate),
      active: Boolean(t.active),
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    })) as MicroloanType[];
  },
  async adminListMicroloanTypes() {
    const raw = await request<Raw[]>('GET', '/api/admin/loan-types');
    return raw.map((t) => ({
      id: String(t.id),
      name: String(t.name),
      minAmount: str(t.minAmount),
      maxAmount: str(t.maxAmount),
      gracePeriodDays: 0,
      dailyRatePct: str(t.dailyRate),
      active: Boolean(t.active),
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    })) as MicroloanType[];
  },
  async adminCreateMicroloanType(input: MicroloanTypeInput) {
    const body = {
      name: input.name,
      description: input.name,
      minAmount: input.minAmount,
      maxAmount: input.maxAmount,
      minTermDays: 7,
      maxTermDays: 30,
      dailyRate: input.dailyRatePct,
      modifier: '0',
      active: input.active,
    };
    const raw = await request<Raw>('POST', '/api/admin/loan-types', body);
    return {
      id: String(raw.id),
      name: String(raw.name),
      minAmount: str(raw.minAmount),
      maxAmount: str(raw.maxAmount),
      gracePeriodDays: 0,
      dailyRatePct: str(raw.dailyRate),
      active: Boolean(raw.active),
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    } as MicroloanType;
  },
  async adminUpdateMicroloanType(id: Id, input: MicroloanTypeInput) {
    // fetch current version
    const list = await request<Raw[]>('GET', '/api/admin/loan-types');
    const current = list.find((t) => String(t.id) === id);
    const version = current ? current.version : null;
    const body = {
      name: input.name,
      description: input.name,
      minAmount: input.minAmount,
      maxAmount: input.maxAmount,
      minTermDays: 7,
      maxTermDays: 30,
      dailyRate: input.dailyRatePct,
      modifier: '0',
      active: input.active,
      version,
    };
    const raw = await request<Raw>('PUT', `/api/admin/loan-types/${id}`, body);
    return {
      id: String(raw.id),
      name: String(raw.name),
      minAmount: str(raw.minAmount),
      maxAmount: str(raw.maxAmount),
      gracePeriodDays: 0,
      dailyRatePct: str(raw.dailyRate),
      active: Boolean(raw.active),
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    } as MicroloanType;
  },
  async adminToggleMicroloanType(id: Id, active: boolean) {
    const list = await request<Raw[]>('GET', '/api/admin/loan-types');
    const current = list.find((t) => String(t.id) === id);
    if (!current) throw new ApiError({ status: 404, code: 'NOT_FOUND', detail: 'type not found' });
    const body = {
      name: String(current.name),
      description: String(current.name),
      minAmount: str(current.minAmount),
      maxAmount: str(current.maxAmount),
      minTermDays: Number(current.minTermDays ?? 7),
      maxTermDays: Number(current.maxTermDays ?? 30),
      dailyRate: str(current.dailyRate),
      modifier: str(current.modifier ?? '0'),
      active,
      version: current.version,
    };
    const raw = await request<Raw>('PUT', `/api/admin/loan-types/${id}`, body);
    return {
      id: String(raw.id),
      name: String(raw.name),
      minAmount: str(raw.minAmount),
      maxAmount: str(raw.maxAmount),
      gracePeriodDays: 0,
      dailyRatePct: str(raw.dailyRate),
      active: Boolean(raw.active),
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    } as MicroloanType;
  },
  async calculateMicroloan(typeId: Id, amount: Money, termDays: number) {
    const types = await request<Raw[]>('GET', '/api/loan-types').catch(() => [] as Raw[]);
    const t = types.find((x) => String(x.id) === typeId);
    const typeName = t ? String(t.name) : '';
    const raw = await request<Raw>('POST', '/api/loans/quote', { loanTypeId: typeId, amount, termDays });
    const principal = str(raw.amount);
    const interest = str(raw.interest);
    const total = str(raw.total);
    return {
      typeId,
      typeName,
      amount: principal,
      termDays: Number(raw.termDays ?? termDays),
      dailyRatePct: str(raw.dailyRate),
      gracePeriodDays: 0,
      interestAmount: interest,
      totalAmount: total,
      paymentSchedule: [{ day: Number(raw.termDays ?? termDays), principal, interest, total }],
    } as MicroloanCalculationResult;
  },
  async createMicroloanApplication(input: { typeId: Id; amount: Money; termDays: number; profile: ApplicantProfile }) {
    // fetch profile for monthlyIncome
    let monthlyIncome = '1000';
    try {
      const p = await request<Raw>('GET', '/api/me/profile');
      if (p && p.monthlyIncome) monthlyIncome = str(p.monthlyIncome);
    } catch {
      // ignore
    }
    const parts = input.profile.fullName.trim().split(/\s+/);
    const lastName = parts[0] || 'Клиент';
    const firstName = parts[1] || 'Клиент';
    const middleName = parts.slice(2).join(' ') || undefined;
    const phone = input.profile.phone.replace(/[^\d+]/g, '');
    const normalizedPhone = phone.startsWith('+') ? phone : `+${phone}`;
    const body = {
      loanTypeId: input.typeId,
      amount: input.amount,
      termDays: input.termDays,
      profile: {
        lastName,
        firstName,
        middleName,
        phone: normalizedPhone,
        monthlyIncome,
      },
    };
    const raw = await request<Raw>('POST', '/api/loans/applications', body);
    return mapApplication(raw);
  },
  async listMyMicroloanApplications() {
    const page = await request<Raw>('GET', '/api/loans/applications?page=0&size=100');
    const items = Array.isArray(page.items) ? page.items : [];
    return items.map(mapApplication);
  },
  async adminListMicroloanApplications() {
    // fetch several statuses and merge
    const statuses = ['SUBMITTED', 'APPROVED', 'ACTIVE', 'REJECTED', 'REPAID', 'CANCELLED', 'EXPIRED'];
    const results = await Promise.allSettled(statuses.map((s) => request<Raw>('GET', `/api/admin/loan-applications?status=${s}&page=0&size=100`)));
    const merged: Raw[] = [];
    const seen = new Set<string>();
    for (const r of results) {
      if (r.status === 'fulfilled') {
        const page = r.value as Raw;
        const items = Array.isArray(page.items) ? page.items : [];
        for (const it of items) {
          const id = String(it.id);
          if (!seen.has(id)) { seen.add(id); merged.push(it); }
        }
      }
    }
    return merged.map(mapAdminApplication);
  },
  async adminUpdateMicroloanApplicationStatus(id: Id, status: MicroloanApplicationStatus) {
    const card = await request<Raw>('GET', `/api/admin/loan-applications/${id}`);
    const version = card.version;
    const backendStatus = status === 'NEW' || status === 'UNDER_REVIEW' ? 'SUBMITTED' : status;
    if (backendStatus === 'APPROVED') {
      await request<Raw>('POST', `/api/admin/loan-applications/${id}/approve`, { comment: '', version });
    } else if (backendStatus === 'REJECTED') {
      await request<Raw>('POST', `/api/admin/loan-applications/${id}/reject`, { reason: 'Отклонено', version });
    }
    const updated = await request<Raw>('GET', `/api/admin/loan-applications/${id}`);
    return mapAdminApplication(updated);
  },
  async adminListUsers() {
    const page = await request<Raw>('GET', '/api/admin/users?page=0&size=100');
    const items = Array.isArray(page.items) ? page.items : [];
    return items.map((u: Raw) => ({
      id: String(u.id),
      email: String(u.email),
      fullName: [u.lastName, u.firstName, u.middleName].filter(Boolean).join(' ') || String(u.email),
      salary: null,
      provider: 'email' as const,
      createdAt: String(u.createdAt),
      phone: u.phone ? String(u.phone) : null,
      roles: ['USER'],
    })) as AdminUser[];
  },
};
