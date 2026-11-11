import type { Problem } from './types';

/** Понятные тексты для кодов ошибок бэкенда. Неизвестный код — показываем detail/title. */
const MESSAGES: Record<string, string> = {
  EMAIL_NOT_VERIFIED: 'Почта ещё не подтверждена. Проверьте письмо — мы не шутим.',
  BAD_CREDENTIALS: 'Неверная почта или пароль.',
  EMAIL_TAKEN: 'Эта почта уже зарегистрирована.',
  VALIDATION_FAILED: 'Проверьте поля формы.',
  RATE_LIMITED: 'Слишком много расчётов подряд. Даже банк столько не считает — подождите минуту.',
  NOT_FOUND: 'Не нашли. Возможно, оно погашено досрочно.',
};

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly retryAfter?: number;

  constructor(problem: Problem, retryAfter?: number) {
    const code = problem.code ?? (problem.status === 429 ? 'RATE_LIMITED' : undefined);
    super((code && MESSAGES[code]) || problem.detail || problem.title || `Ошибка ${problem.status}`);
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
