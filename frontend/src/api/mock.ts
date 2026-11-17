import { calculateLocally, validateParams } from '../lib/calc';
import { ApiError } from './errors';
import type {
  Api, AppNotification, BankOffer, CalcParams, CalcResult, Me, OfferInput, Scenario,
  MicroloanType, MicroloanTypeInput, ApplicantProfile, MicroloanApplication,
  MicroloanApplicationStatus, MicroloanCalculationResult,
} from './types';

/**
 * Моки API для работы без бэкенда (VITE_USE_MOCKS != 'false').
 * Состояние хранится в localStorage, чтобы переживать перезагрузку страницы.
 * Демо-входы: любая почта и пароль от 8 символов; почта с «admin» получает роль ADMIN.
 * Ссылки для входа/подтверждения в моках одноразовые («expired» имитирует истёкшую).
 * Почта с «unverified» имитирует неподтверждённую почту.
 */

const STORE_KEY = 'nosurprise.mock.v2';
const delay = (ms = 250 + Math.random() * 250) => new Promise((r) => setTimeout(r, ms));
const now = () => new Date().toISOString();

interface MockState {
  session: Me | null;
  scenarios: Scenario[];
  notifications: AppNotification[];
  offers: BankOffer[];
  calculations: Record<string, CalcResult>;
  microloanTypes: MicroloanType[];
  microloanApplications: MicroloanApplication[];
  nextId: number;
}

const newId = () => `mock-${(state.nextId++).toString(16).padStart(8, '0')}`;

const seedOffers: BankOffer[] = [
  { id: 'offer-1', bankName: 'Банк «Тихая Гавань»', rate: '10.90', minDownPaymentPercent: '20.00', maxTermMonths: 300, active: true, updatedAt: now() },
  { id: 'offer-2', bankName: 'Народный Процент', rate: '11.50', minDownPaymentPercent: '15.00', maxTermMonths: 360, active: true, updatedAt: now() },
  { id: 'offer-3', bankName: 'Мегакредит Ультра', rate: '9.75', minDownPaymentPercent: '30.00', maxTermMonths: 240, active: true, updatedAt: now() },
  { id: 'offer-4', bankName: 'Банк Вечной Ставки', rate: '13.20', minDownPaymentPercent: '10.00', maxTermMonths: 360, active: false, updatedAt: now() },
];

const seedMicroloanTypes: MicroloanType[] = [
  {
    id: 'ml-type-1',
    name: 'На жизнь',
    minAmount: '3000',
    maxAmount: '100000',
    gracePeriodDays: 7,
    dailyRatePct: '0.8',
    active: true,
    createdAt: now(),
    updatedAt: now(),
  },
  {
    id: 'ml-type-2',
    name: 'На авто',
    minAmount: '50000',
    maxAmount: '200000',
    gracePeriodDays: 14,
    dailyRatePct: '0.6',
    active: true,
    createdAt: now(),
    updatedAt: now(),
  },
  {
    id: 'ml-type-3',
    name: 'На образование',
    minAmount: '10000',
    maxAmount: '150000',
    gracePeriodDays: 30,
    dailyRatePct: '0.5',
    active: true,
    createdAt: now(),
    updatedAt: now(),
  },
];

const MICROLOAN_STORE_KEY = 'nosurprise.microloan.v3';

function load(): MockState {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (raw) return JSON.parse(raw) as MockState;
  } catch {
    // localStorage недоступен — работаем в памяти
  }
  return { session: null, scenarios: [], notifications: [], offers: seedOffers, calculations: {}, microloanTypes: seedMicroloanTypes, microloanApplications: [], nextId: 100 };
}

function loadMicroloan(): { types: MicroloanType[]; applications: MicroloanApplication[] } {
  try {
    const raw = localStorage.getItem(MICROLOAN_STORE_KEY);
    if (raw) return JSON.parse(raw) as { types: MicroloanType[]; applications: MicroloanApplication[] };
  } catch {
    // localStorage недоступен — работаем в памяти
  }
  return { types: seedMicroloanTypes, applications: [] };
}

function saveMicroloan() {
  try {
    localStorage.setItem(MICROLOAN_STORE_KEY, JSON.stringify({ types: state.microloanTypes, applications: state.microloanApplications }));
    return true;
  } catch {
    return false;
  }
}

const microloanData = loadMicroloan();

const state: MockState = {
  ...load(),
  microloanTypes: microloanData.types,
  microloanApplications: microloanData.applications,
};

// Адрес, на который была «отправлена» демо-ссылка (подтверждение / вход по ссылке).
let pendingVerificationEmail: string | null = null;
let lastLoginLinkEmail: string | null = null;

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
    id: 'user-1',
    email,
    emailVerified: true,
    roles: email.toLowerCase().includes('admin') ? ['USER', 'ADMIN'] : ['USER'],
  };
  if (state.notifications.length === 0) {
    state.notifications.push({
      id: newId(),
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
    pendingVerificationEmail = email;
  },
  async resendVerification(email) {
    await delay();
    return;
  },
  async verifyEmail(token) {
    await delay();
    if (token === 'expired') throw new ApiError({ status: 400, code: 'TOKEN_EXPIRED', detail: 'Ссылка устарела. Запросите новую.' });
    signIn(pendingVerificationEmail ?? 'demo@example.com');
  },
  async login(email, password) {
    await delay();
    if (password.length < 8) throw new ApiError({ status: 401, code: 'BAD_CREDENTIALS' });
    if (email.toLowerCase().includes('unverified')) throw new ApiError({ status: 403, code: 'EMAIL_NOT_VERIFIED' });
    signIn(email);
  },
  async requestLoginLink(email) {
    await delay();
    if (email.toLowerCase().includes('unverified')) return; // письмо шлём и так, но вход по ссылке не пройдёт
    lastLoginLinkEmail = email;
  },
  async consumeLoginLink(token) {
    await delay();
    if (token === 'expired') throw new ApiError({ status: 400, code: 'OTP_INVALID', detail: 'Ссылка устарела' });
    const email = lastLoginLinkEmail ?? 'demo@example.com';
    if (email.toLowerCase().includes('unverified')) throw new ApiError({ status: 403, code: 'EMAIL_NOT_VERIFIED' });
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
  async oauthProviders() {
    return ['google', 'github'];
  },

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
      id: newId(),
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

  async requestPdfExport(id) {
    await delay();
    requireUser();
    const scenario = state.scenarios.find((s) => s.id === id);
    if (!scenario) throw new ApiError({ status: 404, code: 'SCENARIO_NOT_FOUND' });
    // Имитация очереди export.pdf.requested → уведомление PDF_READY через пару секунд.
    setTimeout(() => {
      state.notifications.push({
        id: newId(),
        type: 'PDF_READY',
        payload: {
          scenarioId: id,
          calcId: scenario.calcId,
          fileId: newId(),
          fileName: `scenario-${id}.pdf`,
          downloadUrl: `/api/scenarios/${id}/pdf`,
        },
        read: false,
        createdAt: now(),
      });
      save();
    }, 2000);
  },
  async downloadPdf(id) {
    await delay();
    requireUser();
    const scenario = state.scenarios.find((s) => s.id === id);
    if (!scenario) throw new ApiError({ status: 404, code: 'PDF_NOT_FOUND' });
    // В демо вместо PDF — текстовая заглушка.
    return new Blob([`Демо-выгрузка сценария «${scenario.name}». Настоящий PDF сделает бэкенд.`], { type: 'text/plain' });
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
    const offer: BankOffer = { ...input, id: newId(), updatedAt: now() };
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
          id: newId(),
          type: 'SCENARIO_RECALCULATED',
          payload: {
            scenarioId: scenario.id,
            calcId: result.calcId,
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

  // Microloan API
  async listMicroloanTypes() {
    await delay();
    return state.microloanTypes.filter((t) => t.active);
  },
  async adminListMicroloanTypes() {
    await delay();
    requireAdmin();
    return [...state.microloanTypes].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  },
  async adminCreateMicroloanType(input: MicroloanTypeInput) {
    await delay();
    requireAdmin();
    // Validation
    if (!input.name.trim()) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Укажите название типа займа' });
    const minAmount = Number(input.minAmount);
    const maxAmount = Number(input.maxAmount);
    if (!(minAmount > 0)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Минимальная сумма должна быть положительной' });
    if (!(maxAmount > 0)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Максимальная сумма должна быть положительной' });
    if (minAmount > maxAmount) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Минимальная сумма не может превышать максимальную' });
    if (!(Number.isInteger(input.gracePeriodDays) && input.gracePeriodDays >= 0 && input.gracePeriodDays <= 30)) {
      throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Льготный период — целое число от 0 до 30 дней' });
    }
    const dailyRate = Number(input.dailyRatePct);
    if (!(Number.isFinite(dailyRate) && dailyRate >= 0)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Дневная ставка — число не меньше 0' });
    const type: MicroloanType = { ...input, id: newId(), createdAt: now(), updatedAt: now() };
    state.microloanTypes.push(type);
    const ok = saveMicroloan();
    if (!ok) throw new ApiError({ status: 500, code: 'STORAGE_FAILED', detail: 'Не удалось сохранить тип займа' });
    return type;
  },
  async adminUpdateMicroloanType(id, input) {
    await delay();
    requireAdmin();
    // Validation
    if (!input.name.trim()) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Укажите название типа займа' });
    const minAmount = Number(input.minAmount);
    const maxAmount = Number(input.maxAmount);
    if (!(minAmount > 0)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Минимальная сумма должна быть положительной' });
    if (!(maxAmount > 0)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Максимальная сумма должна быть положительной' });
    if (minAmount > maxAmount) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Минимальная сумма не может превышать максимальную' });
    if (!(Number.isInteger(input.gracePeriodDays) && input.gracePeriodDays >= 0 && input.gracePeriodDays <= 30)) {
      throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Льготный период — целое число от 0 до 30 дней' });
    }
    const dailyRate = Number(input.dailyRatePct);
    if (!(Number.isFinite(dailyRate) && dailyRate >= 0)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Дневная ставка — число не меньше 0' });
    const index = state.microloanTypes.findIndex((t) => t.id === id);
    if (index < 0) throw new ApiError({ status: 404, code: 'NOT_FOUND' });
    const type: MicroloanType = { ...input, id, createdAt: state.microloanTypes[index].createdAt, updatedAt: now() };
    state.microloanTypes[index] = type;
    const ok = saveMicroloan();
    if (!ok) throw new ApiError({ status: 500, code: 'STORAGE_FAILED', detail: 'Не удалось сохранить тип займа' });
    return type;
  },
  async adminToggleMicroloanType(id, active) {
    await delay();
    requireAdmin();
    const index = state.microloanTypes.findIndex((t) => t.id === id);
    if (index < 0) throw new ApiError({ status: 404, code: 'NOT_FOUND' });
    state.microloanTypes[index] = { ...state.microloanTypes[index], active, updatedAt: now() };
    const ok = saveMicroloan();
    if (!ok) throw new ApiError({ status: 500, code: 'STORAGE_FAILED', detail: 'Не удалось сохранить тип займа' });
    return state.microloanTypes[index];
  },
  async calculateMicroloan(typeId, amount, termDays) {
    await delay();
    const type = state.microloanTypes.find((t) => t.id === typeId && t.active);
    if (!type) throw new ApiError({ status: 404, code: 'NOT_FOUND', detail: 'Тип займа не найден или неактивен' });
    const amt = Number(amount);
    const days = Number(termDays);
    if (!(amt >= Number(type.minAmount) && amt <= Number(type.maxAmount))) {
      throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: `Сумма должна быть от ${type.minAmount} до ${type.maxAmount} BYN` });
    }
    if (!(days >= 1 && days <= 30)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Срок — от 1 до 30 дней' });
    const dailyRate = Number(type.dailyRatePct) / 100;
    const graceDays = type.gracePeriodDays;
    const chargeableDays = Math.max(0, days - graceDays);
    const interest = Math.round(amt * dailyRate * chargeableDays);
    const total = amt + interest;
    // Single payment at the end
    const paymentSchedule = [
      { day: days, principal: String(amt), interest: String(interest), total: String(total) },
    ];
    return {
      typeId: type.id,
      typeName: type.name,
      amount: String(amt),
      termDays: days,
      dailyRatePct: type.dailyRatePct,
      gracePeriodDays: graceDays,
      interestAmount: String(interest),
      totalAmount: String(total),
      paymentSchedule,
    };
  },
  async createMicroloanApplication(input) {
    await delay();
    const me = requireUser();
    // Check if user already has an application that blocks new one
    const existing = state.microloanApplications.find(
      (a) => a.userId === me.id && (a.status === 'NEW' || a.status === 'UNDER_REVIEW' || a.status === 'APPROVED')
    );
    if (existing) {
      throw new ApiError({ status: 409, code: 'APPLICATION_EXISTS', detail: 'У вас уже есть заявка на рассмотрении или одобренная. Новая заявка возможна только после отклонения.' });
    }
    // Validate type
    const type = state.microloanTypes.find((t) => t.id === input.typeId && t.active);
    if (!type) throw new ApiError({ status: 404, code: 'NOT_FOUND', detail: 'Тип займа не найден или неактивен' });
    const amt = Number(input.amount);
    const days = Number(input.termDays);
    if (!(amt >= Number(type.minAmount) && amt <= Number(type.maxAmount))) {
      throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: `Сумма должна быть от ${type.minAmount} до ${type.maxAmount} BYN` });
    }
    if (!(days >= 1 && days <= 30)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Срок — от 1 до 30 дней' });
    // Calculate total
    const dailyRate = Number(type.dailyRatePct) / 100;
    const graceDays = type.gracePeriodDays;
    const chargeableDays = Math.max(0, days - graceDays);
    const interest = Math.round(amt * dailyRate * chargeableDays);
    const total = amt + interest;
    const application: MicroloanApplication = {
      id: newId(),
      userId: me.id,
      typeId: type.id,
      typeName: type.name,
      amount: String(amt),
      termDays: days,
      totalAmount: String(total),
      status: 'NEW',
      profile: input.profile,
      createdAt: now(),
      updatedAt: now(),
    };
    state.microloanApplications.push(application);
    const ok = saveMicroloan();
    if (!ok) throw new ApiError({ status: 500, code: 'STORAGE_FAILED', detail: 'Не удалось сохранить заявку' });
    return application;
  },
  async listMyMicroloanApplications() {
    await delay();
    const me = requireUser();
    return [...state.microloanApplications]
      .filter((a) => a.userId === me.id)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  },
  async adminListMicroloanApplications() {
    await delay();
    requireAdmin();
    return [...state.microloanApplications].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  },
  async adminUpdateMicroloanApplicationStatus(id, status) {
    await delay();
    requireAdmin();
    const index = state.microloanApplications.findIndex((a) => a.id === id);
    if (index < 0) throw new ApiError({ status: 404, code: 'NOT_FOUND' });
    const allowedStatuses: MicroloanApplicationStatus[] = ['NEW', 'UNDER_REVIEW', 'APPROVED', 'REJECTED'];
    if (!allowedStatuses.includes(status)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'Недопустимый статус' });
    state.microloanApplications[index] = { ...state.microloanApplications[index], status, updatedAt: now() };
    const ok = saveMicroloan();
    if (!ok) throw new ApiError({ status: 500, code: 'STORAGE_FAILED', detail: 'Не удалось сохранить статус' });
    return state.microloanApplications[index];
  },
};
