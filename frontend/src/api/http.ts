import { ApiError } from './errors';
import type {
  Api, AppNotification, BankOffer, CalcParams, CalcResult, Me, OfferInput, Problem, Scenario, StressRow,
} from './types';

/**
 * Настоящий клиент по INSTRUCTION.md §9.
 * Access-токен живёт только в памяти, refresh — в httpOnly-cookie (Path=/api/auth),
 * поэтому все запросы идут с credentials: 'include' на тот же хост.
 */

let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;

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
      accessToken = ((await res.json()) as { accessToken: string }).accessToken;
      return true;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

async function request<T>(method: string, path: string, body?: unknown, retry = true): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;

  const res = await fetch(path, {
    method,
    headers,
    credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  // Access истёк (TTL 15 мин): один раз пробуем обновить и повторить запрос.
  if (res.status === 401 && retry && !path.startsWith('/api/auth/')) {
    if (await refreshAccess()) return request<T>(method, path, body, false);
  }
  if (!res.ok) throw await parseProblem(res);
  if (res.status === 204 || res.status === 202 || res.headers.get('Content-Length') === '0') {
    return undefined as T;
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const httpApi: Api = {
  calculate: (params) => request<CalcResult>('POST', '/api/calc', params),
  getCalculation: (calcId) => request<CalcResult>('GET', `/api/calc/${encodeURIComponent(calcId)}`),
  // Формат тела для stress в документе не зафиксирован: шлём параметры + список дельт.
  stress: (params, deltas) => request<StressRow[]>('POST', '/api/calc/stress', { ...params, deltas }),

  register: (email, password) => request<void>('POST', '/api/auth/register', { email, password }),
  verifyEmail: (token) => request<void>('GET', `/api/auth/verify-email?token=${encodeURIComponent(token)}`),
  async login(email, password) {
    const res = await request<{ accessToken: string }>('POST', '/api/auth/login', { email, password });
    accessToken = res.accessToken;
  },
  requestOtp: (email) => request<void>('POST', '/api/auth/otp/request', { email }),
  async verifyOtp(email, code) {
    const res = await request<{ accessToken: string }>('POST', '/api/auth/otp/verify', { email, code });
    accessToken = res.accessToken;
  },
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

  listScenarios: () => request<Scenario[]>('GET', '/api/scenarios'),
  createScenario: (name, params, offerId) =>
    request<Scenario>('POST', '/api/scenarios', { name, params, offerId: offerId ?? null }),
  deleteScenario: (id) => request<void>('DELETE', `/api/scenarios/${id}`),
  compareScenarios: (ids) => request<Scenario[]>('GET', `/api/scenarios/compare?ids=${ids.join(',')}`),

  listNotifications: () => request<AppNotification[]>('GET', '/api/notifications'),
  markNotificationRead: (id) => request<void>('POST', `/api/notifications/${id}/read`),

  listOffers: () => request<BankOffer[]>('GET', '/api/offers'),
  adminListOffers: () => request<BankOffer[]>('GET', '/api/admin/offers'),
  adminCreateOffer: (input: OfferInput) => request<BankOffer>('POST', '/api/admin/offers', input),
  adminUpdateOffer: (id, input) => request<BankOffer>('PUT', `/api/admin/offers/${id}`, input),
  adminDeleteOffer: (id) => request<void>('DELETE', `/api/admin/offers/${id}`),
};
