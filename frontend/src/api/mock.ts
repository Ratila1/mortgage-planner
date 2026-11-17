import { calculateLocally, validateParams } from '../lib/calc';
import { ApiError } from './errors';
import type {
  Api, AppNotification, BankOffer, CalcParams, CalcResult, Me, OfferInput, Scenario,
  MicroloanType, MicroloanTypeInput, MicroloanApplication, MicroloanApplicationStatus,
  MicroloanCalculationResult, AdminUser, CustomerProfile, CustomerProfileInput, PassportStatus,
} from './types';

/**
 * Моки API для работы без бэкенда (VITE_USE_MOCKS != 'false').
 * Состояние хранится в localStorage, чтобы переживать перезагрузку страницы.
 * Демо-входы: любая почта и пароль от 8 символов; почта с «admin» получает роль ADMIN.
 * Ссылки для входа/подтверждения в моках одноразовые («expired» имитирует истёкшую).
 * Почта с «unverified» имитирует неподтверждённую почту.
 */

const STORE_KEY = 'nosurprise.mock.v3';
const delay = (ms = 250 + Math.random() * 250) => new Promise((r) => setTimeout(r, ms));
const now = () => new Date().toISOString();

interface MockState {
  session: Me | null;
  scenarios: Scenario[];
  notifications: AppNotification[];
  offers: BankOffer[];
  calculations: Record<string, CalcResult>;
  microloanTypes: MicroloanType[];
  microloanApplications: MockApplication[];
  users: AdminUser[];
  profile: CustomerProfile | null;
  passport: PassportStatus | null;
  nextId: number;
}

/** Заявка в моках хранит владельца и ФИО — их нет в клиентском ответе, но нужны админке. */
type MockApplication = MicroloanApplication & { userId: string; fullName: string };

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
    name: 'До зарплаты',
    description: 'Небольшая сумма до ближайшей зарплаты',
    minAmount: '50',
    maxAmount: '1000',
    minTermDays: 7,
    maxTermDays: 30,
    dailyRatePct: '0.800',
    modifier: '0.00',
    active: true,
    version: 0,
  },
  {
    id: 'ml-type-2',
    name: 'На iPhone',
    description: 'Покупка техники с фиксированной надбавкой',
    minAmount: '1000',
    maxAmount: '5000',
    minTermDays: 14,
    maxTermDays: 30,
    dailyRatePct: '0.500',
    modifier: '25.00',
    active: true,
    version: 0,
  },
];

const seedUsers: AdminUser[] = [
  {
    id: 'user-1',
    email: 'admin@test.com',
    fullName: null,
    salary: null,
    provider: 'LOCAL',
    createdAt: '2025-01-15T10:00:00.000Z',
    phone: null,
    roles: ['ADMIN', 'USER'],
    emailVerified: true,
    blocked: false,
  },
  {
    id: 'user-2',
    email: 'ivan@gmail.com',
    fullName: 'Петров Петр Петрович',
    salary: '2500.00',
    provider: 'GOOGLE',
    createdAt: '2025-02-20T14:30:00.000Z',
    phone: '+375339876543',
    roles: ['USER'],
    emailVerified: true,
    blocked: false,
  },
  {
    id: 'user-3',
    email: 'maria@example.com',
    fullName: 'Сидорова Мария Сергеевна',
    salary: '3100.00',
    provider: 'GITHUB',
    createdAt: '2025-03-10T09:15:00.000Z',
    phone: '+375445551234',
    roles: ['USER'],
    emailVerified: true,
    blocked: false,
  },
];

const MICROLOAN_STORE_KEY = 'nosurprise.microloan.v4';

function load(): MockState {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (raw) return JSON.parse(raw) as MockState;
  } catch {
    // localStorage недоступен — работаем в памяти
  }
  return { session: null, scenarios: [], notifications: [], offers: seedOffers, calculations: {}, microloanTypes: seedMicroloanTypes, microloanApplications: [], users: seedUsers, profile: null, passport: null, nextId: 100 };
}

function loadMicroloan(): { types: MicroloanType[]; applications: MockApplication[] } {
  try {
    const raw = localStorage.getItem(MICROLOAN_STORE_KEY);
    if (raw) return JSON.parse(raw) as { types: MicroloanType[]; applications: MockApplication[] };
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

const fail = (status: number, code: string, detail?: string) => new ApiError({ status, code, detail });

function persistMicroloan() {
  if (!saveMicroloan()) throw fail(500, 'STORAGE_FAILED', 'Не удалось сохранить данные');
  save();
}

function validateType(input: MicroloanTypeInput) {
  if (!input.name.trim()) throw fail(400, 'VALIDATION_FAILED', 'Укажите название');
  if (!input.description.trim()) throw fail(400, 'VALIDATION_FAILED', 'Укажите описание');
  const min = Number(input.minAmount);
  const max = Number(input.maxAmount);
  if (!(min > 0 && max > 0 && min <= max)) throw fail(400, 'LOAN_TYPE_INVALID', 'Проверьте суммы: 0 < мин ≤ макс');
  const ok = (d: number) => Number.isInteger(d) && d >= 7 && d <= 30;
  if (!(ok(input.minTermDays) && ok(input.maxTermDays) && input.minTermDays <= input.maxTermDays)) {
    throw fail(400, 'LOAN_TYPE_INVALID', 'Сроки — целые дни от 7 до 30, мин ≤ макс');
  }
  if (!(Number(input.dailyRatePct) >= 0)) throw fail(400, 'VALIDATION_FAILED', 'Ставка — число не меньше 0');
  if (!(Number(input.modifier) >= 0)) throw fail(400, 'VALIDATION_FAILED', 'Модификатор — число не меньше 0');
}

/** Та же формула, что на бэке: простые проценты за каждый день + модификатор типа. */
function quote(type: MicroloanType, amount: number, termDays: number): MicroloanCalculationResult {
  if (!(amount >= Number(type.minAmount) && amount <= Number(type.maxAmount))) {
    throw fail(400, 'LOAN_AMOUNT_OUT_OF_RANGE', `Сумма должна быть от ${type.minAmount} до ${type.maxAmount} BYN`);
  }
  if (!(termDays >= type.minTermDays && termDays <= type.maxTermDays)) {
    throw fail(400, 'LOAN_TERM_OUT_OF_RANGE', `Срок — от ${type.minTermDays} до ${type.maxTermDays} дней`);
  }
  const interest = Math.round(amount * (Number(type.dailyRatePct) / 100) * termDays * 100) / 100;
  const total = amount + interest + Number(type.modifier);
  return {
    amount: amount.toFixed(2),
    termDays,
    dailyRatePct: type.dailyRatePct,
    interestAmount: interest.toFixed(2),
    modifier: Number(type.modifier).toFixed(2),
    totalAmount: total.toFixed(2),
  };
}

const ACTIVE_STATUSES: MicroloanApplicationStatus[] = ['SUBMITTED', 'APPROVED', 'ACTIVE'];

function strip(a: MockApplication): MicroloanApplication {
  const { userId: _userId, fullName: _fullName, ...rest } = a;
  return rest;
}

function findApplication(id: string) {
  const app = state.microloanApplications.find((a) => a.id === id);
  if (!app) throw fail(404, 'LOAN_APPLICATION_NOT_FOUND');
  return app;
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

  // ---------- микрозаймы ----------
  async listMicroloanTypes() {
    await delay();
    return state.microloanTypes.filter((t) => t.active);
  },
  async adminListMicroloanTypes() {
    await delay();
    requireAdmin();
    return [...state.microloanTypes];
  },
  async adminCreateMicroloanType(input) {
    await delay();
    requireAdmin();
    validateType(input);
    const type: MicroloanType = { ...input, id: newId(), version: 0 };
    state.microloanTypes.push(type);
    persistMicroloan();
    return type;
  },
  async adminUpdateMicroloanType(id, input, version) {
    await delay();
    requireAdmin();
    validateType(input);
    const index = state.microloanTypes.findIndex((t) => t.id === id);
    if (index < 0) throw fail(404, 'LOAN_TYPE_NOT_FOUND');
    if (state.microloanTypes[index].version !== version) throw fail(409, 'CONCURRENT_MODIFICATION', 'Тип изменили, обновите страницу');
    state.microloanTypes[index] = { ...input, id, version: version + 1 };
    persistMicroloan();
    return state.microloanTypes[index];
  },
  async adminDeactivateMicroloanType(id) {
    await delay();
    requireAdmin();
    const index = state.microloanTypes.findIndex((t) => t.id === id);
    if (index < 0) throw fail(404, 'LOAN_TYPE_NOT_FOUND');
    const t = state.microloanTypes[index];
    state.microloanTypes[index] = { ...t, active: false, version: t.version + 1 };
    persistMicroloan();
    return state.microloanTypes[index];
  },
  async calculateMicroloan(typeId, amount, termDays) {
    await delay(80);
    const type = state.microloanTypes.find((t) => t.id === typeId && t.active);
    if (!type) throw fail(404, 'LOAN_TYPE_NOT_FOUND', 'Тип займа не найден или выключен');
    return quote(type, Number(amount), Number(termDays));
  },

  async getProfile() {
    await delay();
    requireUser();
    return state.profile;
  },
  async saveProfile(input: CustomerProfileInput) {
    await delay();
    requireUser();
    const phoneChanged = state.profile?.phone !== input.phone;
    state.profile = { ...input, phoneVerified: phoneChanged ? false : (state.profile?.phoneVerified ?? false) };
    save();
    return state.profile;
  },
  async getPassport() {
    await delay();
    requireUser();
    return state.passport;
  },
  async uploadPassport(file: File) {
    await delay();
    requireUser();
    if (!['image/jpeg', 'image/png'].includes(file.type)) throw fail(415, 'UNSUPPORTED_FILE_TYPE', 'Только JPEG или PNG');
    if (file.size > 5 * 1024 * 1024) throw fail(413, 'FILE_TOO_LARGE', 'Файл больше 5 МБ');
    state.passport = { uploadedAt: now(), deleted: false };
    save();
    return state.passport;
  },

  async createMicroloanApplication(input) {
    await delay();
    const me = requireUser();
    if (!state.passport || state.passport.deleted) throw fail(409, 'KYC_REQUIRED', 'Загрузите фото паспорта');
    if (state.microloanApplications.some((a) => a.userId === me.id && ACTIVE_STATUSES.includes(a.status))) {
      throw fail(409, 'ACTIVE_LOAN_EXISTS', 'У вас уже есть активная заявка или заём');
    }
    const type = state.microloanTypes.find((t) => t.id === input.typeId && t.active);
    if (!type) throw fail(404, 'LOAN_TYPE_NOT_FOUND', 'Тип займа не найден или выключен');
    const q = quote(type, Number(input.amount), input.termDays);
    await mockApi.saveProfile(input.profile);
    const p = input.profile;
    const app: MockApplication = {
      id: newId(),
      userId: me.id,
      fullName: [p.lastName, p.firstName, p.middleName].filter(Boolean).join(' '),
      typeId: type.id,
      typeName: type.name,
      amount: q.amount,
      termDays: q.termDays,
      interest: q.interestAmount,
      modifier: q.modifier,
      totalAmount: q.totalAmount,
      monthlyIncome: p.monthlyIncome,
      debtLoadPercent: ((Number(q.totalAmount) / Number(p.monthlyIncome)) * 100).toFixed(2),
      status: 'SUBMITTED',
      rejectionReason: null,
      offerExpiresAt: null,
      createdAt: now(),
    };
    state.microloanApplications.push(app);
    persistMicroloan();
    return strip(app);
  },
  async listMyMicroloanApplications() {
    await delay();
    const me = requireUser();
    return state.microloanApplications
      .filter((a) => a.userId === me.id)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map(strip);
  },
  async cancelMicroloanApplication(id) {
    await delay();
    requireUser();
    const app = findApplication(id);
    if (app.status !== 'SUBMITTED' && app.status !== 'APPROVED') throw fail(409, 'INVALID_STATUS_TRANSITION');
    app.status = 'CANCELLED';
    persistMicroloan();
  },

  async adminListMicroloanApplications(status) {
    await delay();
    requireAdmin();
    return state.microloanApplications
      .filter((a) => a.status === status)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map((a) => ({
        id: a.id,
        fullName: a.fullName,
        typeName: a.typeName,
        amount: a.amount,
        termDays: a.termDays,
        monthlyIncome: a.monthlyIncome,
        debtLoadPercent: a.debtLoadPercent,
        debtLoadWarning: Number(a.debtLoadPercent) > 50,
        status: a.status,
        createdAt: a.createdAt,
      }));
  },
  async adminApproveMicroloanApplication(id) {
    await delay();
    requireAdmin();
    const app = findApplication(id);
    if (app.status !== 'SUBMITTED') throw fail(409, 'INVALID_STATUS_TRANSITION');
    app.status = 'APPROVED';
    app.offerExpiresAt = new Date(Date.now() + 5 * 864e5).toISOString();
    persistMicroloan();
  },
  async adminRejectMicroloanApplication(id, reason) {
    await delay();
    requireAdmin();
    const app = findApplication(id);
    if (app.status !== 'SUBMITTED') throw fail(409, 'INVALID_STATUS_TRANSITION');
    app.status = 'REJECTED';
    app.rejectionReason = reason;
    persistMicroloan();
  },
  async adminMarkMicroloanRepaid(id) {
    await delay();
    requireAdmin();
    const app = findApplication(id);
    if (app.status !== 'ACTIVE') throw fail(409, 'INVALID_STATUS_TRANSITION');
    app.status = 'REPAID';
    persistMicroloan();
  },
  async adminListUsers() {
    await delay();
    requireAdmin();
    return [...state.users].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  },
};
