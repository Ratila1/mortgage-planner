import { ApiError } from './errors';
import type {
  Api, AppNotification, BankOffer, CalcParams, CalcResult, EarlyRepayment, Me, OAuthProvider, OfferInput, Problem, Scenario,
  ScenarioSummary, StressRow,
  MicroloanType, MicroloanTypeInput, MicroloanApplication, AdminMicroloanApplication,
  MicroloanApplicationStatus, MicroloanCalculationResult, AdminUser, Id, Money,
  CustomerProfile, CustomerProfileInput, PassportStatus, LoginProvider, Role,
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

// ---------- микрозаймы: ответы бэкенда → типы фронта ----------

function mapLoanType(raw: Raw): MicroloanType {
  return {
    id: String(raw.id),
    name: String(raw.name),
    description: str(raw.description, ''),
    minAmount: str(raw.minAmount),
    maxAmount: str(raw.maxAmount),
    minTermDays: Number(raw.minTermDays),
    maxTermDays: Number(raw.maxTermDays),
    dailyRatePct: str(raw.dailyRate),
    modifier: str(raw.modifier),
    active: Boolean(raw.active),
    version: Number(raw.version ?? 0),
  };
}

function loanTypeBody(input: MicroloanTypeInput, version?: number) {
  return {
    name: input.name,
    description: input.description,
    minAmount: input.minAmount,
    maxAmount: input.maxAmount,
    minTermDays: input.minTermDays,
    maxTermDays: input.maxTermDays,
    dailyRate: input.dailyRatePct,
    modifier: input.modifier,
    active: input.active,
    version,
  };
}

function mapApplication(raw: Raw): MicroloanApplication {
  return {
    id: String(raw.id),
    typeId: String(raw.loanTypeId),
    typeName: String(raw.loanTypeName ?? ''),
    amount: str(raw.amount),
    termDays: Number(raw.termDays),
    interest: str(raw.interest),
    modifier: str(raw.modifier),
    totalAmount: str(raw.total),
    monthlyIncome: str(raw.monthlyIncome),
    debtLoadPercent: str(raw.debtLoadPercent),
    status: raw.status as MicroloanApplicationStatus,
    rejectionReason: raw.rejectionReason ? String(raw.rejectionReason) : null,
    offerExpiresAt: raw.offerExpiresAt ? String(raw.offerExpiresAt) : null,
    createdAt: String(raw.createdAt),
  };
}

function mapAdminApplication(raw: Raw): AdminMicroloanApplication {
  return {
    id: String(raw.id),
    fullName: [raw.lastName, raw.firstName, raw.middleName].filter(Boolean).join(' '),
    typeName: String(raw.loanTypeName ?? ''),
    amount: str(raw.amount),
    termDays: Number(raw.termDays),
    monthlyIncome: str(raw.monthlyIncome),
    debtLoadPercent: str(raw.debtLoadPercent),
    debtLoadWarning: Boolean(raw.debtLoadWarning),
    status: raw.status as MicroloanApplicationStatus,
    createdAt: String(raw.createdAt),
  };
}

function mapProfile(raw: Raw): CustomerProfile {
  return {
    lastName: str(raw.lastName, ''),
    firstName: str(raw.firstName, ''),
    middleName: str(raw.middleName, ''),
    phone: str(raw.phone, ''),
    monthlyIncome: str(raw.monthlyIncome),
    phoneVerified: Boolean(raw.phoneVerified),
  };
}

/** Пустое отчество на бэке хранится как null, а не ''. */
function profileBody(p: CustomerProfileInput) {
  return {
    lastName: p.lastName.trim(),
    firstName: p.firstName.trim(),
    middleName: p.middleName.trim() || null,
    phone: p.phone.trim(),
    monthlyIncome: p.monthlyIncome,
  };
}

function mapPassport(raw: Raw | undefined): PassportStatus | null {
  return raw ? { uploadedAt: String(raw.uploadedAt), deleted: Boolean(raw.deleted) } : null;
}

/**
 * Решение по заявке требует version (оптимистичная блокировка), а в строке списка его нет —
 * берём свежую карточку прямо перед действием.
 */
async function adminDecision(id: Id, action: 'approve' | 'reject' | 'repaid', extra: Raw = {}) {
  const card = await request<Raw>('GET', `/api/admin/loan-applications/${id}`);
  await request<Raw>('POST', `/api/admin/loan-applications/${id}/${action}`, { ...extra, version: card.version });
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

  // ---------- микрозаймы ----------
  listMicroloanTypes: async () => (await request<Raw[]>('GET', '/api/loan-types')).map(mapLoanType),
  adminListMicroloanTypes: async () => (await request<Raw[]>('GET', '/api/admin/loan-types')).map(mapLoanType),
  adminCreateMicroloanType: async (input) =>
    mapLoanType(await request<Raw>('POST', '/api/admin/loan-types', loanTypeBody(input))),
  adminUpdateMicroloanType: async (id, input, version) =>
    mapLoanType(await request<Raw>('PUT', `/api/admin/loan-types/${id}`, loanTypeBody(input, version))),
  adminDeactivateMicroloanType: async (id) =>
    mapLoanType(await request<Raw>('POST', `/api/admin/loan-types/${id}/deactivate`)),
  async calculateMicroloan(typeId: Id, amount: Money, termDays: number) {
    const raw = await request<Raw>('POST', '/api/loans/quote', { loanTypeId: typeId, amount, termDays });
    return {
      amount: str(raw.amount),
      termDays: Number(raw.termDays),
      dailyRatePct: str(raw.dailyRate),
      interestAmount: str(raw.interest),
      modifier: str(raw.modifier),
      totalAmount: str(raw.total),
    } satisfies MicroloanCalculationResult;
  },

  // 204 (профиль ещё не заполнен) приходит как undefined.
  getProfile: async () => {
    const raw = await request<Raw | undefined>('GET', '/api/me/profile');
    return raw ? mapProfile(raw) : null;
  },
  saveProfile: async (input) => mapProfile(await request<Raw>('PUT', '/api/me/profile', profileBody(input))),
  getPassport: async () => mapPassport(await request<Raw | undefined>('GET', '/api/me/kyc/passport')),
  async uploadPassport(file: File) {
    // multipart: Content-Type с boundary проставляет браузер, поэтому не через send().
    const form = new FormData();
    form.append('file', file);
    const post = () =>
      fetch('/api/me/kyc/passport', {
        method: 'POST',
        credentials: 'include',
        headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
        body: form,
      });
    let res = await post();
    if (res.status === 401 && (await refreshAccess())) res = await post();
    if (!res.ok) throw await parseProblem(res);
    return mapPassport((await res.json()) as Raw)!;
  },

  async createMicroloanApplication(input) {
    const raw = await request<Raw>('POST', '/api/loans/applications', {
      loanTypeId: input.typeId,
      amount: input.amount,
      termDays: input.termDays,
      profile: profileBody(input.profile),
    });
    return mapApplication(raw);
  },
  async listMyMicroloanApplications() {
    const page = await request<Raw>('GET', '/api/loans/applications?page=0&size=100');
    return ((page.items as Raw[] | undefined) ?? []).map(mapApplication);
  },
  cancelMicroloanApplication: (id) => request<void>('POST', `/api/loans/applications/${id}/cancel`),

  async adminListMicroloanApplications(status: MicroloanApplicationStatus) {
    const page = await request<Raw>('GET', `/api/admin/loan-applications?status=${status}&page=0&size=100`);
    return ((page.items as Raw[] | undefined) ?? []).map(mapAdminApplication);
  },
  adminApproveMicroloanApplication: (id) => adminDecision(id, 'approve'),
  adminRejectMicroloanApplication: (id, reason) => adminDecision(id, 'reject', { reason }),
  adminMarkMicroloanRepaid: (id) => adminDecision(id, 'repaid'),

  async adminListUsers() {
    const page = await request<Raw>('GET', '/api/admin/users?page=0&size=100');
    return ((page.items as Raw[] | undefined) ?? []).map(
      (u): AdminUser => ({
        id: String(u.id),
        email: String(u.email),
        fullName: [u.lastName, u.firstName, u.middleName].filter(Boolean).join(' ') || null,
        salary: u.monthlyIncome ? String(u.monthlyIncome) : null,
        provider: String(u.provider) as LoginProvider,
        createdAt: String(u.createdAt),
        phone: u.phone ? String(u.phone) : null,
        roles: (Array.isArray(u.roles) ? u.roles : []) as Role[],
        emailVerified: Boolean(u.emailVerified),
        blocked: Boolean(u.blocked),
      }),
    );
  },
};
