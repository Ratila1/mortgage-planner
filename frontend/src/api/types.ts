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

/** Пользователь в админке (GET /api/admin/users). Способ входа и роли — из БД. */
export type LoginProvider = 'LOCAL' | 'GOOGLE' | 'GITHUB';

export interface AdminUser {
  id: Id;
  email: string;
  /** ФИО из профиля клиента; null, если профиль ещё не заполнен. */
  fullName: string | null;
  salary: Money | null;
  provider: LoginProvider;
  createdAt: string;
  phone: string | null;
  roles: Role[];
  emailVerified: boolean;
  blocked: boolean;
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

/** Payload уведомлений по заявке на микрозайм (LoanNotificationConsumer). */
export interface LoanNotificationPayload {
  applicationId: Id;
  /** Ссылка на карточку заявки (/loans/{id}) — ведёт в личный кабинет. */
  cardUrl: string;
  /** Договор: GET по этому адресу отдаёт PDF (нужен Bearer). Есть только у LOAN_APPROVED. */
  contractUrl?: string;
  /** Ссылка на подпись — пока совпадает с cardUrl. */
  signUrl?: string;
  /** Причина отказа — только у LOAN_REJECTED. */
  reason?: string;
}

export interface AppNotification {
  id: Id;
  type: NotificationType;
  payload: ScenarioRecalculatedPayload | PdfReadyPayload | LoanNotificationPayload | { message?: string } | null;
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

/** Статусы заявки на микрозайм — как на бэке (MICROLOANS.md §3.8). */
export type MicroloanApplicationStatus =
  | 'SUBMITTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'ACTIVE'
  | 'OVERDUE'
  | 'EXPIRED'
  | 'REPAID';

/** Тип микрозайма (GET /api/loan-types, /api/admin/loan-types). */
export interface MicroloanType {
  id: Id;
  name: string;
  description: string;
  minAmount: Money;
  maxAmount: Money;
  minTermDays: number;
  maxTermDays: number;
  dailyRatePct: string;
  /** Фиксированная надбавка типа в BYN, прибавляется к сумме к возврату. */
  modifier: Money;
  /**
   * Балл цели займа для скоринга, 0–1 (CREDIT_POLICY.md §3.2): тип займа и есть цель.
   * Чем надёжнее цель, тем выше балл: техника 0.6, «до зарплаты» 0.3.
   */
  purposeScore?: string;
  active: boolean;
  version: number;
}

export type MicroloanTypeInput = Omit<MicroloanType, 'id' | 'version'>;

export type Sex = 'MALE' | 'FEMALE';

/**
 * Профиль клиента (GET/PUT /api/me/profile). Email не входит: он берётся из учётной записи.
 * Дата рождения, пол, страна и город нужны скорингу (CREDIT_POLICY.md §4); у старых профилей они пустые.
 */
export interface CustomerProfile {
  lastName: string;
  firstName: string;
  middleName: string;
  phone: string;
  monthlyIncome: Money;
  /** yyyy-MM-dd */
  birthDate: string;
  sex: Sex | '';
  /** ISO-код страны: BY, PL… */
  country: string;
  city: string;
  phoneVerified: boolean;
}

export type CustomerProfileInput = Omit<CustomerProfile, 'phoneVerified'>;

/** Фото паспорта: загружено или удалено по сроку хранения. */
export interface PassportStatus {
  uploadedAt: string;
  deleted: boolean;
}

/** Заявка клиента на микрозайм (GET /api/loans/applications и /{id}). */
export interface MicroloanApplication {
  id: Id;
  typeId: Id;
  typeName: string;
  amount: Money;
  termDays: number;
  interest: Money;
  modifier: Money;
  totalAmount: Money;
  monthlyIncome: Money;
  debtLoadPercent: string;
  status: MicroloanApplicationStatus;
  rejectionReason: string | null;
  offerExpiresAt: string | null;
  createdAt: string;
  /** Дата возврата (yyyy-MM-dd): появляется после подписи договора. */
  dueDate?: string | null;
  /** Дни просрочки: у OVERDUE — на сегодня, у погашенного — итог. */
  overdueDays?: number | null;
  /** Документы есть только в GET /{id} (в списке бэк отдаёт пустой список). */
  documents: AdminLoanDocument[];
}

/**
 * Итог скоринга заявки (CREDIT_POLICY.md §6.4). status PENDING — ждём ответ credit-policy;
 * без COMPLETED или NOT_CALCULATED одобрить нельзя (бэк ответит 409 SCORING_REQUIRED).
 */
export interface MicroloanScoring {
  status: 'PENDING' | 'COMPLETED' | 'NOT_CALCULATED';
  score: string | null;
  points: number | null;
  /** A1…E3; F — стоп-фактор (чёрный список, просрочка, возраст). */
  ratingClass: string | null;
  /** PD — вероятность невозврата, %. */
  pdPercent: string | null;
  decision: 'RECOMMEND' | 'REVIEW' | 'AUTO_REJECT' | null;
}

/** Строка расшифровки скоринга: вклад фактора = балл × вес. */
export interface ScoringFactor {
  factor: 'HISTORY' | 'PURPOSE' | 'AGE' | 'CITY' | 'SEX';
  value: string | null;
  score: number;
  weight: number;
  contribution: number;
}

/** Расчёт из credit-policy (GET /credit-policy/api/admin/scorings/{applicationId}). */
export interface ScoringDetail {
  status: 'COMPLETED' | 'NOT_CALCULATED';
  ratingClass: string | null;
  points: number | null;
  pdPercent: string | null;
  decision: 'RECOMMEND' | 'REVIEW' | 'AUTO_REJECT';
  /** Стоп-факторы и причины, по которым балл не считается. */
  reasons: string[];
  breakdown: ScoringFactor[];
  rulesVersion: number;
  createdAt: string;
}

/** Строка списка заявок в админке. */
export interface AdminMicroloanApplication {
  id: Id;
  fullName: string;
  typeName: string;
  amount: Money;
  termDays: number;
  monthlyIncome: Money;
  debtLoadPercent: string;
  debtLoadWarning: boolean;
  status: MicroloanApplicationStatus;
  createdAt: string;
  /** null — заявка подана до появления скоринга. */
  scoring?: MicroloanScoring | null;
  overdueDays?: number | null;
}

/** Документ заявки (договор и т.п.): скачивается по downloadUrl с Bearer-токеном. */
export interface AdminLoanDocument {
  id: Id;
  type: string;
  downloadUrl: string;
  createdAt: string;
}

/** Сводка кредитной истории клиента (Mongo, §3.12). totalLoans === 0 — истории нет. */
export interface AdminCreditHistorySummary {
  totalLoans: number;
  byStatus: Record<string, number>;
  hasActiveLoan: boolean;
  lastMonthlyIncome: string | null;
  lastDebtLoadPercent: string | null;
  minDebtLoadPercent: string | null;
  maxDebtLoadPercent: string | null;
  incidentsByType: Record<string, number>;
  firstSubmittedAt: string | null;
}

/** Займ из кредитной истории клиента. */
export interface AdminCreditHistoryLoan {
  applicationId: Id;
  status: MicroloanApplicationStatus;
  loanTypeName: string;
  amount: Money;
  termDays: number;
  dailyRate: string;
  modifier: string;
  interest: Money;
  total: Money;
  monthlyIncome: string | null;
  debtLoadPercent: string | null;
  submittedAt: string | null;
  decidedAt: string | null;
  decisionComment: string | null;
  closedAt: string | null;
  syncedAt: string;
}

/** Кредитная история клиента: сводка + все займы. */
export interface AdminCreditHistory {
  summary: AdminCreditHistorySummary;
  loans: AdminCreditHistoryLoan[];
}

/** Карточка заявки для админа (GET /api/admin/loan-applications/{id}): заявка + документы + кредитная история. */
export interface AdminMicroloanApplicationCard {
  id: Id;
  userId: Id;
  status: MicroloanApplicationStatus;
  version: number;
  createdAt: string;
  loanTypeId: Id;
  loanTypeName: string;
  amount: Money;
  termDays: number;
  dailyRate: string;
  interest: Money;
  modifier: Money;
  total: Money;
  lastName: string;
  firstName: string;
  middleName: string;
  phone: string;
  monthlyIncome: Money;
  debtLoadPercent: string;
  debtLoadWarning: boolean;
  decidedBy: Id | null;
  decidedAt: string | null;
  decisionComment: string | null;
  offerExpiresAt: string | null;
  closedAt: string | null;
  dueDate?: string | null;
  paidAt?: string | null;
  overdueDays?: number | null;
  scoring?: MicroloanScoring | null;
  /** GET по этому адресу отдаёт фото паспорта (no-store); 404 — фото удалено по сроку. */
  passportUrl: string;
  documents: AdminLoanDocument[];
  creditHistory: AdminCreditHistory;
}

/** Расчёт микрозайма (POST /api/loans/quote): проценты от суммы + модификатор типа. */
export interface MicroloanCalculationResult {
  amount: Money;
  termDays: number;
  dailyRatePct: string;
  interestAmount: Money;
  modifier: Money;
  totalAmount: Money;
}

export interface Api {
  calculate(params: CalcParams, signal?: AbortSignal): Promise<CalcResult>;
  getCalculation(calcId: string): Promise<CalcResult>;
  stress(params: CalcParams, deltas: number[]): Promise<StressRow[]>;

  register(email: string, password: string): Promise<void>;
  resendVerification(email: string): Promise<void>;
  /** Подтверждение почты одноразовым токеном из письма + мгновенный вход. */
  verifyEmail(token: string): Promise<void>;
  login(email: string, password: string): Promise<void>;
  /** Прислать на почту одноразовую ссылку для входа (magic link). */
  requestLoginLink(email: string): Promise<void>;
  /** Обменять одноразовый токен из письма на сессию. */
  consumeLoginLink(token: string): Promise<void>;
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

  // Микрозаймы (MICROLOANS.md §4.6)
  /** Активные типы микрозаймов для калькулятора и заявки. */
  listMicroloanTypes(): Promise<MicroloanType[]>;
  /** Админ: все типы, включая выключенные. */
  adminListMicroloanTypes(): Promise<MicroloanType[]>;
  adminCreateMicroloanType(input: MicroloanTypeInput): Promise<MicroloanType>;
  adminUpdateMicroloanType(id: Id, input: MicroloanTypeInput, version: number): Promise<MicroloanType>;
  /** Выключение типа (удаления нет — на тип ссылаются старые заявки). */
  adminDeactivateMicroloanType(id: Id): Promise<MicroloanType>;
  /** Расчёт на бэке (публично, ничего не сохраняет). */
  calculateMicroloan(typeId: Id, amount: Money, termDays: number): Promise<MicroloanCalculationResult>;

  /** Профиль клиента; null — ещё не заполнен. */
  getProfile(): Promise<CustomerProfile | null>;
  saveProfile(input: CustomerProfileInput): Promise<CustomerProfile>;
  /** Фото паспорта; null — ещё не загружено. */
  getPassport(): Promise<PassportStatus | null>;
  uploadPassport(file: File): Promise<PassportStatus>;

  /** Подать заявку: данные формы сохраняются в профиль в той же транзакции. */
  createMicroloanApplication(input: {
    typeId: Id;
    amount: Money;
    termDays: number;
    profile: CustomerProfileInput;
  }): Promise<MicroloanApplication>;
  listMyMicroloanApplications(): Promise<MicroloanApplication[]>;
  /** Карточка своей заявки: условия, статус и документы (договор). */
  getMicroloanApplication(id: Id): Promise<MicroloanApplication>;
  /** Скачивание документа (договора) по downloadUrl из карточки: нужен Bearer, поэтому fetch. */
  downloadContract(downloadUrl: string): Promise<Blob>;
  /** Подпись договора (§3.10): код приходит SMS на подтверждённый телефон. */
  requestSignatureCode(id: Id): Promise<void>;
  confirmSignature(id: Id, code: string): Promise<MicroloanApplication>;
  /** Подтверждение телефона (условие подписи): код приходит SMS на телефон профиля. */
  requestPhoneVerification(): Promise<void>;
  confirmPhoneVerification(code: string): Promise<void>;
  cancelMicroloanApplication(id: Id): Promise<void>;

  adminListMicroloanApplications(status?: MicroloanApplicationStatus | 'ALL' | null, query?: string, page?: number, size?: number): Promise<{ items: AdminMicroloanApplication[]; total: number; page: number; size: number }>;  /** Карточка заявки: полные данные клиента, документы, фото паспорта (passportUrl) и кредитная история. */
  adminGetMicroloanApplication(id: Id): Promise<AdminMicroloanApplicationCard>;
  /** Фото паспорта (blob); 404 — фото удалено по сроку хранения. */
  adminViewPassport(id: Id): Promise<Blob>;
  adminApproveMicroloanApplication(id: Id): Promise<void>;
  adminRejectMicroloanApplication(id: Id, reason: string): Promise<void>;
  /** Отметка «Погашен» для займа в ACTIVE. */
  adminMarkMicroloanRepaid(id: Id): Promise<void>;
  /** Запросить скоринг заново: ответ не пришёл или заявка подана до скоринга (только SUBMITTED). */
  adminRequestScoring(id: Id): Promise<void>;
  /** Расшифровка скоринга из credit-policy; null — расчёта ещё нет. */
  adminGetScoringDetail(applicationId: Id): Promise<ScoringDetail | null>;

  adminListUsers(query?: string, page?: number, size?: number): Promise<{ items: AdminUser[]; total: number; page: number; size: number }>;  /** Блокировка (бэкен заодно отзывает все refresh-токены) и разблокировка. Блок себя — 400 SELF_BLOCK_FORBIDDEN. */
  adminBlockUser(id: Id): Promise<void>;
  adminUnblockUser(id: Id): Promise<void>;
}
