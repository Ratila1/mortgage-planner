import type { Problem } from './types';

/**
 * Место запроса, если один код ошибки означает разное.
 * RATE_LIMITED приходит из калькулятора, входа и OTP-кулдауна — тексты там разные.
 */
export type ErrorContext = 'calc' | 'otp';

/**
 * Тексты для кодов ошибок бэкенда (common/problem/ErrorCode.java).
 * detail у бэкенда на английском и технический, поэтому для известных кодов показываем свой текст.
 */
const MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: 'Проверьте поля формы.',
  INVALID_REQUEST_BODY: 'Некорректный запрос.',
  INVALID_CALC_PARAMS: 'С такими параметрами ипотеку не посчитать. Проверьте цену, взнос, срок и досрочные погашения.',
  CALC_NOT_FOUND: 'Расчёт не найден или уже удалён.',
  OFFER_NOT_FOUND: 'Предложение банка не найдено.',
  INVALID_OFFER: 'Предложение банка недоступно: возможно, его уже скрыли.',
  SCENARIO_NOT_FOUND: 'Сценарий не найден. Возможно, его погасили досрочно.',
  NOTIFICATION_NOT_FOUND: 'Уведомление не найдено.',
  PDF_NOT_READY: 'PDF ещё готовится. Пришлём уведомление, когда будет готов.',
  PDF_NOT_FOUND: 'PDF не найден. Запросите выгрузку ещё раз.',
  INVALID_REQUEST: 'Некорректный запрос.',
  RATE_LIMITED: 'Слишком много запросов подряд. Подождите минуту и попробуйте снова.',
  SMS_DELIVERY_FAILED: 'Не удалось отправить SMS: сервис отправки недоступен. Попробуйте ещё раз — ждать минуту не нужно.',
  VERIFY_TOKEN_INVALID: 'Ссылка подтверждения устарела или уже использована.',
  INVALID_CREDENTIALS: 'Неверная почта или пароль.',
  EMAIL_NOT_VERIFIED: 'Почта ещё не подтверждена. Проверьте письмо — мы не шутим.',
  INVALID_REFRESH_TOKEN: 'Сессия истекла. Войдите снова.',
  OTP_INVALID: 'Код неверный, устарел или уже использован. Запросите новый.',
  PHONE_NOT_VERIFIED: 'Телефон не подтверждён. Подтвердите его кодом из SMS, затем подпишите договор.',
  PROFILE_INCOMPLETE: 'Сначала заполните профиль: ФИО, телефон и зарплату.',
  OFFER_EXPIRED: 'Срок подписания договора истёк. Подайте заявку заново.',
  DOCUMENT_NOT_FOUND: 'Документ не найден: договор ещё готовится или уже удалён.',
  KYC_NOT_FOUND: 'Фотография паспорта не найдена: возможно, она удалена по сроку хранения.',
  INVALID_STATUS_TRANSITION: 'Действие недоступно для текущего статуса заявки.',
  UNAUTHORIZED: 'Нужно войти.',
  FORBIDDEN: 'Недостаточно прав.',
  INTERNAL_ERROR: 'На сервере что-то сломалось. Это не мы, это проценты.',
};

/**
 * RATE_LIMITED в калькуляторе и в OTP — разные истории: в одном месте это «слишком много
 * расчётов», в другом — «код уже отправляли недавно». Без контекста остаётся общий текст.
 */
const RATE_LIMITED_BY_CONTEXT: Partial<Record<ErrorContext, string>> = {
  calc: 'Слишком много расчётов подряд. Даже банк столько не считает — подождите минуту.',
  otp: 'Код уже отправляли недавно — раньше новое SMS не придёт.',
};

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly retryAfter?: number;

  constructor(problem: Problem, retryAfter?: number) {
    const code = problem.code ?? (problem.status === 429 ? 'RATE_LIMITED' : undefined);
    let message = (code && MESSAGES[code]) || problem.detail || problem.title || `Ошибка ${problem.status}`;
    if (code === 'RATE_LIMITED' && retryAfter) message += ` Можно снова через ${retryAfter} с.`;
    super(message);
    this.name = 'ApiError';
    this.status = problem.status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

export function errorMessage(error: unknown, context?: ErrorContext): string {
  if (error instanceof ApiError) {
    // RATE_LIMITED — единственный код, который в разных местах значит разное.
    const contextual = error.code === 'RATE_LIMITED' && context ? RATE_LIMITED_BY_CONTEXT[context] : undefined;
    if (contextual) {
      return error.retryAfter ? `${contextual} Можно снова через ${error.retryAfter} с.` : contextual;
    }
    return error.message;
  }
  if (error instanceof TypeError) return 'Сервер не отвечает. Проверьте соединение.';
  return 'Что-то пошло не так. Сюрприз, которого мы обещали избежать.';
}
