/**
 * Типы HTTP-контракта. Сверены с DTO бэкенда из ветки dev (пакеты web/dto в backend/src/main/java/dev/nosurprise).
 * Деньги — строки ("88848.79"), идентификаторы — UUID-строки, время — ISO-8601 (UTC).
 */

export type Id = string;

export type Money = string;

export type PaymentType = 'ANNUITY' | 'DIFFERENTIATED';
export type RepaymentMode = 'REDUCE_TERM' | 'REDUCE_PAYMENT';

export interface OneTimeRepayment {
  type: 'ONE_TIME';
  month: number;
  amount: Money;
  mode: RepaymentMode;
}

export interface RegularRepayment {
  type: 'REGULAR';
  startMonth: number;
  everyMonths: number;
  amount: Money;
  untilMonth?: number;
  mode: RepaymentMode;
}

export type EarlyRepayment = OneTimeRepayment | RegularRepayment;

export interface CalcParams {
  price: Money;
  downPayment: Money;
  termMonths: number;
  /** проценты годовых, строкой: "12.5" */
  annualRate: string;
  type: PaymentType;
  earlyRepayments: EarlyRepayment[];
}

export interface ScheduleRow {
  month: number;
  /** бэкенд дату не отдаёт; заполняется только моками */
  date?: string;
  payment: Money;
  principal: Money;
  interest: Money;
  earlyRepayment: Money;
  balance: Money;
}

/** SavingsResponse: эффект досрочных погашений */
export interface Savings {
  /** на сколько уменьшилась переплата */
  overpaymentSaved: Money;
  /** на сколько месяцев сократился срок */
  monthsSaved: number;
}

export interface CalcResult {
  calcId: string;
  loanAmount: Money;
  monthlyPayment: Money;
  overpayment: Money;
  totalPaid: Money;
  termMonths: number;
  /** null, если досрочных погашений не было */
  savings?: Savings | null;
  schedule: ScheduleRow[];
}

/** POST /api/calc/stress (★) — «что если ставка вырастет на N п.п.» */
export interface StressRow {
  /** BigDecimal на бэкенде — в JSON приходит числом */
  delta: number;
  monthlyPayment: Money;
  overpayment: Money;
}

export type Role = 'USER' | 'ADMIN';

export interface Me {
  id: Id;
  email: string;
  emailVerified: boolean;
  roles: Role[];
}

export interface ScenarioSummary {
  monthlyPayment: Money;
  overpayment: Money;
  totalPaid: Money;
  termMonths: number;
}

export interface Scenario {
  id: Id;
  name: string;
  params: CalcParams;
  /** на бэкенде это весь CalcResponse; фронту нужны только итоги */
  summary: ScenarioSummary;
  calcId: string;
  offerId: Id | null;
  createdAt: string;
  updatedAt: string;
}

export interface BankOffer {
  id: Id;
  bankName: string;
  /** проценты годовых, строкой */
  rate: string;
  minDownPaymentPercent: string;
  maxTermMonths: number;
  active: boolean;
  updatedAt: string;
}

export type OfferInput = Omit<BankOffer, 'id' | 'updatedAt'>;

export type NotificationType = 'SCENARIO_RECALCULATED' | 'PDF_READY' | (string & {});

export interface ScenarioRecalculatedPayload {
  scenarioId: Id;
  calcId: string;
  oldRate: string;
  newRate: string;
  oldMonthlyPayment: Money;
  newMonthlyPayment: Money;
  oldOverpayment: Money;
  newOverpayment: Money;
}

export interface PdfReadyPayload {
  scenarioId: Id;
  calcId: string;
  fileId: string;
  fileName: string;
  downloadUrl: string;
}

export interface AppNotification {
  id: Id;
  type: NotificationType;
  payload: ScenarioRecalculatedPayload | PdfReadyPayload | { message?: string } | null;
  read: boolean;
  createdAt: string;
}

/** RFC 7807 ProblemDetail с полем code */
export interface Problem {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  code?: string;
}

/** Всё, что умеет фронт. Реализации: api/http.ts (настоящий бэк) и api/mock.ts. */
export type OAuthProvider = 'google' | 'github';

export interface Api {
  calculate(params: CalcParams): Promise<CalcResult>;
  getCalculation(calcId: string): Promise<CalcResult>;
  stress(params: CalcParams, deltas: number[]): Promise<StressRow[]>;

  register(email: string, password: string): Promise<void>;
  verifyEmail(token: string): Promise<void>;
  login(email: string, password: string): Promise<void>;
  requestOtp(email: string): Promise<void>;
  verifyOtp(email: string, code: string): Promise<void>;
  /** Пробует восстановить сессию по refresh-cookie. true — сессия есть. */
  restoreSession(): Promise<boolean>;
  logout(): Promise<void>;
  me(): Promise<Me>;
  oauthUrl(provider: OAuthProvider): string;
  /** Какие провайдеры включены на бэкенде (профиль oauth). Пусто — кнопки не показываем. */
  oauthProviders(): Promise<OAuthProvider[]>;

  listScenarios(): Promise<Scenario[]>;
  createScenario(name: string, params: CalcParams, offerId?: Id | null): Promise<Scenario>;
  deleteScenario(id: Id): Promise<void>;
  compareScenarios(ids: Id[]): Promise<Scenario[]>;
  /** POST /api/scenarios/{id}/export/pdf → 202; готовность придёт уведомлением PDF_READY */
  requestPdfExport(id: Id): Promise<void>;
  /** GET /api/scenarios/{id}/pdf (нужен Bearer, поэтому через fetch, а не ссылкой) */
  downloadPdf(id: Id): Promise<Blob>;

  listNotifications(): Promise<AppNotification[]>;
  markNotificationRead(id: Id): Promise<void>;

  listOffers(): Promise<BankOffer[]>;
  /** GET /api/admin/offers — все предложения, включая скрытые (active=false). */
  adminListOffers(): Promise<BankOffer[]>;
  adminCreateOffer(input: OfferInput): Promise<BankOffer>;
  adminUpdateOffer(id: Id, input: OfferInput): Promise<BankOffer>;
  adminDeleteOffer(id: Id): Promise<void>;
}
