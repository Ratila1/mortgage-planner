/**
 * Типы HTTP-контракта из INSTRUCTION.md (§5, §9).
 * Деньги — всегда строки ("88848.79"), время — ISO-8601 (UTC).
 * Если бэкенд отклонится от документа, правится только этот файл и api/http.ts.
 */

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
  date?: string;
  payment: Money;
  principal: Money;
  interest: Money;
  earlyRepayment: Money;
  balance: Money;
}

export interface Savings {
  /** на сколько уменьшилась переплата */
  overpayment: Money;
  /** на сколько месяцев сократился срок */
  months: number;
}

export interface CalcResult {
  calcId: string;
  loanAmount: Money;
  monthlyPayment: Money;
  overpayment: Money;
  totalPaid: Money;
  termMonths: number;
  savings?: Savings;
  schedule: ScheduleRow[];
}

/** POST /api/calc/stress (★) — «что если ставка вырастет на N п.п.» */
export interface StressRow {
  delta: number;
  monthlyPayment: Money;
  overpayment: Money;
}

export type Role = 'USER' | 'ADMIN';

export interface Me {
  id: number;
  email: string;
  emailVerified: boolean;
  provider: 'LOCAL' | 'GOOGLE' | 'GITHUB';
  roles: Role[];
}

export interface ScenarioSummary {
  monthlyPayment: Money;
  overpayment: Money;
  totalPaid: Money;
  termMonths: number;
}

export interface Scenario {
  id: number;
  name: string;
  params: CalcParams;
  summary: ScenarioSummary;
  calcId: string;
  offerId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface BankOffer {
  id: number;
  bankName: string;
  /** проценты годовых, строкой */
  rate: string;
  minDownPaymentPercent: string;
  maxTermMonths: number;
  active: boolean;
  updatedAt: string;
}

export type OfferInput = Omit<BankOffer, 'id' | 'updatedAt'>;

export type NotificationType = 'SCENARIO_RECALCULATED' | 'SYSTEM';

export interface ScenarioRecalculatedPayload {
  scenarioId: number;
  scenarioName: string;
  bankName: string;
  oldRate: string;
  newRate: string;
  oldMonthlyPayment: Money;
  newMonthlyPayment: Money;
  oldOverpayment: Money;
  newOverpayment: Money;
}

export interface AppNotification {
  id: number;
  type: NotificationType;
  payload: ScenarioRecalculatedPayload | { message: string };
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
  oauthUrl(provider: 'google' | 'github'): string;

  listScenarios(): Promise<Scenario[]>;
  createScenario(name: string, params: CalcParams, offerId?: number | null): Promise<Scenario>;
  deleteScenario(id: number): Promise<void>;
  compareScenarios(ids: number[]): Promise<Scenario[]>;

  listNotifications(): Promise<AppNotification[]>;
  markNotificationRead(id: number): Promise<void>;

  listOffers(): Promise<BankOffer[]>;
  adminListOffers(): Promise<BankOffer[]>;
  adminCreateOffer(input: OfferInput): Promise<BankOffer>;
  adminUpdateOffer(id: number, input: OfferInput): Promise<BankOffer>;
  adminDeleteOffer(id: number): Promise<void>;
}
