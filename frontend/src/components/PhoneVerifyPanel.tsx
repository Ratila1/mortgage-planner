import { useEffect, useState } from 'react';
import { ApiError, api, errorMessage } from '../api';
import { Alert, Button, TextInput } from './ui';

/**
 * Подтверждение телефона кодом из SMS (§3.3, шаг 3): без него бэк не даст запросить код подписи договора.
 * Кулдаун общий на все SMS пользователя — 60 с (OneTimeCodeStore.COOLDOWN), 3 попытки ввода, код живёт 5 минут.
 */
export function PhoneVerifyPanel({ phone, onVerified }: { phone: string; onVerified: () => void }) {
  const [cooldown, setCooldown] = useState(0);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

  const request = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.requestPhoneVerification();
      setSent(true);
      setCooldown(60);
    } catch (err) {
      setError(errorMessage(err));
      // Кулдаун кода приходит 429 c Retry-After — показываем кнопку с обратным отсчётом.
      if (err instanceof ApiError && err.retryAfter) setCooldown(err.retryAfter);
    } finally {
      setBusy(false);
    }
  };

  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.confirmPhoneVerification(code.trim());
      onVerified();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="rounded-xl border border-gold/40 bg-gold-soft/60 p-4">
      <p className="text-sm font-semibold">Подтвердите телефон</p>
      <p className="mt-1 text-sm text-ink-2">
        Договор подписывается кодом из SMS на номер <span className="num font-semibold">{phone || '—'}</span>.
        Без подтверждённого номера подпись недоступна.
      </p>
      {sent && !error && (
        <p className="mt-2 text-sm text-money-2">Код отправлен. Введите цифры из SMS — код действует 5 минут, попыток три.</p>
      )}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button size="sm" variant="secondary" loading={busy} disabled={cooldown > 0} onClick={request}>
          {cooldown > 0 ? `Получить код (${cooldown}с)` : sent ? 'Получить код заново' : 'Получить код'}
        </Button>
        <TextInput
          className="w-40"
          inputMode="numeric"
          maxLength={6}
          placeholder="Код из SMS"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
        />
        <Button size="sm" loading={busy} disabled={code.trim().length < 4} onClick={confirm}>
          Подтвердить
        </Button>
      </div>
      {error && <div className="mt-3"><Alert>{error}</Alert></div>}
    </div>
  );
}
