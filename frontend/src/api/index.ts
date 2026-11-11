import { httpApi } from './http';
import { mockApi } from './mock';
import type { Api } from './types';

/** Моки включены, пока явно не задано VITE_USE_MOCKS=false. */
export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS !== 'false';

export const api: Api = USE_MOCKS ? mockApi : httpApi;

export * from './types';
export { ApiError, errorMessage } from './errors';
