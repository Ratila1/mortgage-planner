import { ApiError } from './errors';
import type {
  Api, AppNotification, BankOffer, CalcParams, CalcResult, EarlyRepayment, Me, OfferInput, Problem, Scenario,
  ScenarioSummary, StressRow,
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

async function send(method: string, path: string, body?: unknown, retry = true): Promise<Response> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;

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

export const httpApi: Api = {
  calculate: (params) => request<CalcResult>('POST', '/api/calc', calcBody(params)),
  getCalculation: (calcId) => request<CalcResult>('GET', `/api/calc/${encodeURIComponent(calcId)}`),
  stress: (params, deltas) => request<StressRow[]>('POST', '/api/calc/stress', { ...calcBody(params), deltas }),

  register: (email, password) => request<void>('POST', '/api/auth/register', { email, password }),
  verifyEmail: (token) => request<void>('GET', `/api/auth/verify-email?token=${encodeURIComponent(token)}`),
  login: (email, password) => login('/api/auth/login', { email, password }),
  requestOtp: (email) => request<void>('POST', '/api/auth/otp/request', { email }),
  verifyOtp: (email, code) => login('/api/auth/otp/verify', { email, code }),
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
};
