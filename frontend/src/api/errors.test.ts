import { ApiError, errorMessage } from './errors';

describe('errorMessage', () => {
  it('keeps the calculator joke for the calculator context', () => {
    const error = new ApiError({ status: 429, code: 'RATE_LIMITED' });
    expect(errorMessage(error, 'calc')).toContain('расчётов');
  });

  it('explains the OTP cooldown in the OTP context', () => {
    const error = new ApiError({ status: 429, code: 'RATE_LIMITED' });
    expect(errorMessage(error, 'otp')).toContain('Код уже отправляли недавно');
  });

  it('falls back to a neutral text without context', () => {
    const error = new ApiError({ status: 429, code: 'RATE_LIMITED' });
    expect(errorMessage(error)).toContain('Слишком много запросов');
  });

  it('appends Retry-After seconds when the backend sent them', () => {
    const error = new ApiError({ status: 429, code: 'RATE_LIMITED' }, 42);
    expect(errorMessage(error, 'calc')).toContain('42 с');
  });

  it('has a human message for SMS delivery failures instead of the english detail', () => {
    const error = new ApiError({
      status: 502,
      code: 'SMS_DELIVERY_FAILED',
      detail: 'SMS could not be sent, try again later',
    });
    expect(errorMessage(error)).toContain('Не удалось отправить SMS');
  });

  it('keeps an unknown code detail as-is', () => {
    const error = new ApiError({ status: 418, detail: 'teapot' });
    expect(errorMessage(error)).toBe('teapot');
  });

  it('explains a network failure', () => {
    expect(errorMessage(new TypeError('fetch failed'))).toContain('Сервер не отвечает');
  });
});
