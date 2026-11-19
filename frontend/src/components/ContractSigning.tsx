import { useCallback, useEffect, useState } from 'react';
import { ApiError, api, errorMessage, type CustomerProfile, type MicroloanApplication } from '../api';
import { formatDateTime } from '../lib/format';
import { PhoneVerifyPanel } from './PhoneVerifyPanel';
import { Alert, Button, Spinner, TextInput } from './ui';

/** Кулдаун SMS на бэке — 60 с на пользователя (OneTimeCodeStore). */
const SMS_COOLDOWN_SECONDS = 60;

function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(url);
}

/** Что показать вместо подписи, если заявка уже не ждёт подписи (уведомление могли открыть позже). */
const AFTER_SIGNING: Partial<Record<MicroloanApplication['status'], string>> = {
  ACTIVE: 'Договор подписан, заём выдан.',
  REPAID: 'Заём погашен.',
  EXPIRED: 'Срок подписания истёк — договор больше нельзя подписать.',
  CANCELLED: 'Заявка отменена.',
};

/**
 * Подпись договора микрозайма (MICROLOANS.md §3.10): скачать PDF → (если нужно) подтвердить телефон →
 * получить код по SMS → ввести код. Используется и в уведомлении об одобрении, и в «Моих заявках»,
 * поэтому сам загружает актуальную заявку и профиль: уведомление могли открыть через несколько дней.
 * Без подтверждённого телефона бэк не отправит код подписи (403 PHONE_NOT_VERIFIED).
 */
export function ContractSigning({ id, onSigned }: { id: string; onSigned?: (app: MicroloanApplication) => void }) {
  const [card, setCard] = useState<MicroloanApplication | null>(null);
  const [profile, setProfile] = useState<CustomerProfile | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [codeSent, setCodeSent] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [busy, setBusy] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    () =>
      Promise.all([api.getMicroloanApplication(id), api.getProfile()])
        .then(([c, p]) => {
          setCard(c);
          setProfile(p);
          setLoadError(null);
        })
        .catch((err) => setLoadError(errorMessage(err))),
    [id],
  );

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

  if (loadError) return <Alert>{loadError}</Alert>;
  if (!card) {
    return (
      <div className="grid place-items-center py-4 text-money">
        <Spinner className="h-5 w-5" />
      </div>
    );
  }

  const contract = card.documents.find((d) => d.type === 'CONTRACT') ?? null;

  const downloadContract = async () => {
    if (!contract) return;
    setDownloading(true);
    setError(null);
    try {
      saveBlob(await api.downloadContract(contract.downloadUrl), `contract-${id}.pdf`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setDownloading(false);
    }
  };

  const requestCode = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.requestSignatureCode(id);
      setCodeSent(true);
      setCooldown(SMS_COOLDOWN_SECONDS);
    } catch (err) {
      setError(errorMessage(err, 'otp'));
      // 429 с Retry-After: SMS недавно уже отправляли (общий кулдаун с подтверждением телефона).
      if (err instanceof ApiError && err.retryAfter) setCooldown(err.retryAfter);
    } finally {
      setBusy(false);
    }
  };

  const sign = async () => {
    setBusy(true);
    setError(null);
    try {
      const signed = await api.confirmSignature(id, code.trim());
      setCard({ ...card, status: signed.status });
      onSigned?.(signed);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const downloadButton = contract && (
    <Button size="sm" variant="secondary" loading={downloading} onClick={downloadContract}>
      Скачать договор (PDF)
    </Button>
  );

  if (card.status !== 'APPROVED') {
    return (
      <div className="space-y-3">
        <p className="text-sm text-ink-2">{AFTER_SIGNING[card.status] ?? 'Заявка не ждёт подписи.'}</p>
        {downloadButton}
        {error && <Alert>{error}</Alert>}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {card.offerExpiresAt && (
        <p className="text-xs text-ink-3">Подписать нужно до {formatDateTime(card.offerExpiresAt)}.</p>
      )}

      {contract ? (
        <div className="flex flex-wrap items-center gap-3">{downloadButton}</div>
      ) : (
        <p className="text-sm text-ink-3">Договор ещё готовится — обновите страницу через минуту.</p>
      )}

      {contract && !profile?.phoneVerified && <PhoneVerifyPanel phone={profile?.phone ?? ''} onVerified={load} />}

      {contract && profile?.phoneVerified && (
        <div className="rounded-xl border border-line bg-field p-4">
          <p className="text-sm text-ink-2">
            Чтобы подписать договор, нажмите кнопку ниже — на номер{' '}
            <span className="num font-semibold">{profile.phone}</span> придёт код. Введите его и нажмите «Подписать».
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Button size="sm" variant={codeSent ? 'secondary' : 'primary'} loading={busy && !codeSent} disabled={cooldown > 0} onClick={requestCode}>
              {cooldown > 0 ? `Отправить код заново (${cooldown}с)` : codeSent ? 'Отправить код заново' : 'Получить код по SMS'}
            </Button>
            {codeSent && (
              <>
                <TextInput
                  className="w-40"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  placeholder="Код из SMS"
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                />
                <Button size="sm" loading={busy} disabled={code.trim().length < 4} onClick={sign}>
                  Подписать
                </Button>
              </>
            )}
          </div>
          {codeSent && <p className="mt-2 text-xs text-ink-3">Код действует 5 минут, на ввод — 3 попытки.</p>}
        </div>
      )}

      {error && <Alert>{error}</Alert>}
    </div>
  );
}
