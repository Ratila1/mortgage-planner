import { httpApi } from './http';
import { mockApi } from './mock';
import type { Api } from './types';

/** Моки включаются только явно: VITE_USE_MOCKS=true (работа без бэкенда, тесты). */
export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS === 'true';

export const api: Api = USE_MOCKS ? mockApi : httpApi;

export * from './types';
export { ApiError, errorMessage } from './errors';
