import { useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError, api, errorMessage } from '../api';
import { Alert, Button, TextInput } from './ui';

export interface PhoneVerifyPanelProps {
  phone: string;
  onVerified: () => void | Promise<void>;
  /** Runs before the SMS request; return false to cancel it (the caller shows its own error). */
  onBeforeRequest?: () => boolean | void | Promise<boolean | void>;
  title?: string;
  description?: ReactNode;
  requestLabel?: string;
  confirmLabel?: string;
}

const SMS_COOLDOWN_SECONDS = 60;

/**
 * Phone confirmation by SMS. Used both by the application form and by the post-approval
 * signing fallback, so the texts and the pre-request hook are supplied by the caller.
 */
export function PhoneVerifyPanel({
  phone,
  onVerified,
  onBeforeRequest,
  title = 'Подтвердите телефон',
  description,
  requestLabel = 'Получить код',
  confirmLabel = 'Подтвердить',
}: PhoneVerifyPanelProps) {
  const [cooldown, setCooldown] = useState(0);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const phoneRef = useRef(phone);
  phoneRef.current = phone;

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

  // A code requested for one number must not be used for another one.
  useEffect(() => {
    setCode('');
    setSent(false);
    setCooldown(0);
    setError(null);
  }, [phone]);

  const request = async () => {
    const targetPhone = phoneRef.current;
    setBusy(true);
    setError(null);
    try {
      const ready = await onBeforeRequest?.();
      if (ready === false) return;
      await api.requestPhoneVerification();
      if (phoneRef.current !== targetPhone) return;
      setSent(true);
      setCooldown(SMS_COOLDOWN_SECONDS);
    } catch (err) {
      if (phoneRef.current !== targetPhone) return;
      setError(errorMessage(err, 'otp'));
      // 429 with Retry-After: the shared SMS cooldown is still active.
      if (err instanceof ApiError && err.retryAfter) setCooldown(err.retryAfter);
    } finally {
      setBusy(false);
    }
  };

  const confirm = async () => {
    const targetPhone = phoneRef.current;
    setBusy(true);
    setError(null);
    try {
      await api.confirmPhoneVerification(code.trim());
      if (phoneRef.current !== targetPhone) return;
      await onVerified();
    } catch (err) {
      if (phoneRef.current !== targetPhone) return;
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="rounded-xl border border-gold/40 bg-gold-soft/60 p-4">
      <p className="text-sm font-semibold">{title}</p>
      <p className="mt-1 text-sm text-ink-2">
        {description ?? (
          <>
            Код из SMS придёт на номер <span className="num font-semibold">{phone || '—'}</span>. Код действует 5 минут,
            попыток три.
          </>
        )}
      </p>
      {sent && !error && (
        <p className="mt-2 text-sm text-money-2">Код отправлен. Введите цифры из SMS.</p>
      )}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button size="sm" variant="secondary" loading={busy} disabled={cooldown > 0} onClick={request}>
          {cooldown > 0 ? `${requestLabel} (${cooldown}с)` : sent ? 'Получить код заново' : requestLabel}
        </Button>
        <TextInput
          className="w-40"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          placeholder="Код из SMS"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
        />
        <Button size="sm" loading={busy} disabled={code.trim().length < 4} onClick={confirm}>
          {confirmLabel}
        </Button>
      </div>
      {error && (
        <div className="mt-3">
          <Alert>{error}</Alert>
        </div>
      )}
    </div>
  );
}
