import { calculateLocally, validateParams } from '../lib/calc';
import { ApiError } from './errors';
import type {
  Api, AppNotification, BankOffer, CalcParams, CalcResult, Me, OfferInput, Scenario,
} from './types';

/**
 * Моки API для работы без бэкенда (VITE_USE_MOCKS != 'false').
 * Состояние хранится в localStorage, чтобы переживать перезагрузку страницы.
 * Демо-входы: любая почта и пароль от 8 символов; почта с «admin» получает роль ADMIN.
 * OTP-код в моках всегда 123456. Почта с «unverified» имитирует неподтверждённую почту.
 */

const STORE_KEY = 'nosurprise.mock.v1';
const delay = (ms = 250 + Math.random() * 250) => new Promise((r) => setTimeout(r, ms));
const now = () => new Date().toISOString();

interface MockState {
  session: Me | null;
  scenarios: Scenario[];
  notifications: AppNotification[];
  offers: BankOffer[];
  calculations: Record<string, CalcResult>;
  nextId: number;
}

const seedOffers: BankOffer[] = [
  { id: 1, bankName: 'Банк «Тихая Гавань»', rate: '10.90', minDownPaymentPercent: '20.00', maxTermMonths: 300, active: true, updatedAt: now() },
  { id: 2, bankName: 'Народный Процент', rate: '11.50', minDownPaymentPercent: '15.00', maxTermMonths: 360, active: true, updatedAt: now() },
  { id: 3, bankName: 'Мегакредит Ультра', rate: '9.75', minDownPaymentPercent: '30.00', maxTermMonths: 240, active: true, updatedAt: now() },
  { id: 4, bankName: 'Банк Вечной Ставки', rate: '13.20', minDownPaymentPercent: '10.00', maxTermMonths: 360, active: false, updatedAt: now() },
];

function load(): MockState {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (raw) return JSON.parse(raw) as MockState;
  } catch {
    // localStorage недоступен — работаем в памяти
  }
  return { session: null, scenarios: [], notifications: [], offers: seedOffers, calculations: {}, nextId: 100 };
}

const state: MockState = load();

function save() {
  try {
    // графики не храним — тяжёлые, пересчитываются заново
    localStorage.setItem(STORE_KEY, JSON.stringify({ ...state, calculations: {} }));
  } catch {
    // не страшно
  }
}

function requireUser(): Me {
  if (!state.session) throw new ApiError({ status: 401, code: 'UNAUTHORIZED', detail: 'Нужно войти' });
  return state.session;
}

function requireAdmin(): Me {
  const me = requireUser();
  if (!me.roles.includes('ADMIN')) throw new ApiError({ status: 403, code: 'FORBIDDEN', detail: 'Только для админов' });
  return me;
}

function calc(params: CalcParams): CalcResult {
  const error = validateParams(params);
  if (error) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: error });
  const result = calculateLocally(params);
  state.calculations[result.calcId] = result;
  return result;
}

function signIn(email: string) {
  state.session = {
    id: 1,
    email,
    emailVerified: true,
    provider: 'LOCAL',
    roles: email.toLowerCase().includes('admin') ? ['USER', 'ADMIN'] : ['USER'],
  };
  if (state.notifications.length === 0) {
    state.notifications.push({
      id: state.nextId++,
      type: 'SYSTEM',
      payload: { message: 'Добро пожаловать! Здесь будут новости о ставках. Хорошие — редко.' },
      read: false,
      createdAt: now(),
    });
  }
  save();
}

export const mockApi: Api = {
  async calculate(params) {
    await delay(120);
    return calc(params);
  },
  async getCalculation(calcId) {
    await delay();
    const found = state.calculations[calcId];
    if (!found) throw new ApiError({ status: 404, code: 'NOT_FOUND' });
    return found;
  },
  async stress(params, deltas) {
    await delay(150);
    return deltas.map((delta) => {
      const r = calc({ ...params, annualRate: String(Number(params.annualRate) + delta) });
      return { delta, monthlyPayment: r.monthlyPayment, overpayment: r.overpayment };
    });
  },

  async register(email, password) {
    await delay();
    if (password.length < 8) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Пароль — минимум 8 символов' });
    if (state.session?.email === email) throw new ApiError({ status: 409, code: 'EMAIL_TAKEN' });
  },
  async verifyEmail(token) {
    await delay();
    if (token === 'expired') throw new ApiError({ status: 400, code: 'TOKEN_EXPIRED', detail: 'Ссылка устарела. Запросите новую.' });
  },
  async login(email, password) {
    await delay();
    if (password.length < 8) throw new ApiError({ status: 401, code: 'BAD_CREDENTIALS' });
    if (email.toLowerCase().includes('unverified')) throw new ApiError({ status: 403, code: 'EMAIL_NOT_VERIFIED' });
    signIn(email);
  },
  async requestOtp() {
    await delay();
  },
  async verifyOtp(email, code) {
    await delay();
    if (code !== '123456') throw new ApiError({ status: 401, code: 'BAD_CREDENTIALS', detail: 'Код не подошёл (в демо это 123456)' });
    signIn(email);
  },
  async restoreSession() {
    await delay(80);
    return state.session !== null;
  },
  async logout() {
    await delay(80);
    state.session = null;
    save();
  },
  async me() {
    await delay(80);
    return requireUser();
  },
  oauthUrl: () => '#oauth-mock',

  async listScenarios() {
    await delay();
    requireUser();
    return [...state.scenarios].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  },
  async createScenario(name, params, offerId) {
    await delay();
    requireUser();
    const result = calc(params);
    const scenario: Scenario = {
      id: state.nextId++,
      name,
      params,
      summary: {
        monthlyPayment: result.monthlyPayment,
        overpayment: result.overpayment,
        totalPaid: result.totalPaid,
        termMonths: result.termMonths,
      },
      calcId: result.calcId,
      offerId: offerId ?? null,
      createdAt: now(),
      updatedAt: now(),
    };
    state.scenarios.push(scenario);
    save();
    return scenario;
  },
  async deleteScenario(id) {
    await delay();
    requireUser();
    state.scenarios = state.scenarios.filter((s) => s.id !== id);
    save();
  },
  async compareScenarios(ids) {
    await delay();
    requireUser();
    return ids
      .map((id) => state.scenarios.find((s) => s.id === id))
      .filter((s): s is Scenario => s !== undefined);
  },

  async listNotifications() {
    await delay();
    requireUser();
    return [...state.notifications].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  },
  async markNotificationRead(id) {
    await delay(80);
    const n = state.notifications.find((x) => x.id === id);
    if (n) n.read = true;
    save();
  },

  async listOffers() {
    await delay();
    return state.offers.filter((o) => o.active);
  },
  async adminListOffers() {
    await delay();
    requireAdmin();
    return state.offers;
  },
  async adminCreateOffer(input: OfferInput) {
    await delay();
    requireAdmin();
    const offer: BankOffer = { ...input, id: state.nextId++, updatedAt: now() };
    state.offers.push(offer);
    save();
    return offer;
  },
  async adminUpdateOffer(id, input) {
    await delay();
    requireAdmin();
    const index = state.offers.findIndex((o) => o.id === id);
    if (index < 0) throw new ApiError({ status: 404, code: 'NOT_FOUND' });
    const before = state.offers[index];
    const offer: BankOffer = { ...input, id, updatedAt: now() };
    state.offers[index] = offer;
    // Имитация цепочки offer.rate.changed → пересчёт сценариев → уведомление.
    if (before.rate !== offer.rate) {
      for (const scenario of state.scenarios.filter((s) => s.offerId === id)) {
        const params = { ...scenario.params, annualRate: offer.rate };
        const result = calc(params);
        state.notifications.push({
          id: state.nextId++,
          type: 'SCENARIO_RECALCULATED',
          payload: {
            scenarioId: scenario.id,
            scenarioName: scenario.name,
            bankName: offer.bankName,
            oldRate: before.rate,
            newRate: offer.rate,
            oldMonthlyPayment: scenario.summary.monthlyPayment,
            newMonthlyPayment: result.monthlyPayment,
            oldOverpayment: scenario.summary.overpayment,
            newOverpayment: result.overpayment,
          },
          read: false,
          createdAt: now(),
        });
        scenario.params = params;
        scenario.summary = {
          monthlyPayment: result.monthlyPayment,
          overpayment: result.overpayment,
          totalPaid: result.totalPaid,
          termMonths: result.termMonths,
        };
        scenario.updatedAt = now();
      }
    }
    save();
    return offer;
  },
  async adminDeleteOffer(id) {
    await delay();
    requireAdmin();
    state.offers = state.offers.filter((o) => o.id !== id);
    save();
  },
};
