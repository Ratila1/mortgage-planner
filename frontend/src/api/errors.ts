import type { Problem } from './types';

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
  RATE_LIMITED: 'Слишком много расчётов подряд. Даже банк столько не считает — подождите минуту.',
  VERIFY_TOKEN_INVALID: 'Ссылка подтверждения устарела или уже использована.',
  INVALID_CREDENTIALS: 'Неверная почта или пароль.',
  EMAIL_NOT_VERIFIED: 'Почта ещё не подтверждена. Проверьте письмо — мы не шутим.',
  INVALID_REFRESH_TOKEN: 'Сессия истекла. Войдите снова.',
  OTP_INVALID: 'Код не подошёл или устарел.',
  UNAUTHORIZED: 'Нужно войти.',
  FORBIDDEN: 'Недостаточно прав.',
  INTERNAL_ERROR: 'На сервере что-то сломалось. Это не мы, это проценты.',
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

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof TypeError) return 'Сервер не отвечает. Проверьте соединение.';
  return 'Что-то пошло не так. Сюрприз, которого мы обещали избежать.';
}
