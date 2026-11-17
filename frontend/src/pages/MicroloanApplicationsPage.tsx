import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  api,
  errorMessage,
  type CustomerProfile,
  type MicroloanApplication,
  type MicroloanApplicationStatus,
} from '../api';
import { Alert, Badge, Button, Card, EmptyState, PageTitle, Spinner, TextInput } from '../components/ui';
import { PhoneVerifyPanel } from '../components/PhoneVerifyPanel';
import { formatDateTime, formatMoney } from '../lib/format';

export const MICROLOAN_STATUS_LABELS: Record<MicroloanApplicationStatus, string> = {
  SUBMITTED: 'На рассмотрении',
  APPROVED: 'Одобрена, ждёт подписи',
  REJECTED: 'Отклонена',
  CANCELLED: 'Отменена',
  ACTIVE: 'Заём выдан',
  EXPIRED: 'Предложение истекло',
  REPAID: 'Погашен',
};

export const MICROLOAN_STATUS_TONES: Record<MicroloanApplicationStatus, 'money' | 'stamp' | 'gold' | 'neutral'> = {
  SUBMITTED: 'gold',
  APPROVED: 'money',
  REJECTED: 'stamp',
  CANCELLED: 'neutral',
  ACTIVE: 'money',
  EXPIRED: 'neutral',
  REPAID: 'neutral',
};

/** Пока есть заявка или заём в этих статусах, новую подать нельзя (один активный займ). */
const BLOCKING: MicroloanApplicationStatus[] = ['SUBMITTED', 'APPROVED', 'ACTIVE'];
/** Клиент может отменить заявку до подписи. */
const CANCELLABLE: MicroloanApplicationStatus[] = ['SUBMITTED', 'APPROVED'];

function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(url);
}

export function MicroloanApplicationsPage() {
  const navigate = useNavigate();
  const [applications, setApplications] = useState<MicroloanApplication[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);

  const load = useCallback(
    () =>
      api
        .listMyMicroloanApplications()
        .then((list) => {
          setApplications(list);
          setError(null);
        })
        .catch((err) => setError(errorMessage(err))),
    [],
  );

  useEffect(() => {
    load();
  }, [load]);

  const cancel = async (id: string) => {
    setBusyId(id);
    try {
      await api.cancelMicroloanApplication(id);
      await load();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  };

  const canApply = applications !== null && !applications.some((a) => BLOCKING.includes(a.status));

  return (
    <div className="space-y-8">
      <PageTitle eyebrow="Личный кабинет" title="Мои заявки на микрозайм">
        {canApply && <Button onClick={() => navigate('/microloan')}>+ Новая заявка</Button>}
      </PageTitle>

      {notice && <Alert tone="money">{notice}</Alert>}
      {error && <Alert>{error}</Alert>}

      {applications === null ? (
        !error && (
          <div className="grid place-items-center py-24 text-money">
            <Spinner className="h-8 w-8" />
          </div>
        )
      ) : applications.length === 0 ? (
        <EmptyState
          title="Заявок пока нет"
          text="Выберите сумму и срок в калькуляторе и оформите заявку."
          action={<Button onClick={() => navigate('/microloan')}>Перейти к калькулятору</Button>}
          emoji="📋"
        />
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-sm">
              <thead className="bg-paper-2/70 text-left text-xs uppercase tracking-wider text-ink-3">
                <tr>
                  <th className="p-4 font-semibold">Тип займа</th>
                  <th className="p-4 text-right font-semibold">Сумма</th>
                  <th className="p-4 text-right font-semibold">К возврату</th>
                  <th className="p-4 text-right font-semibold">Срок</th>
                  <th className="p-4 font-semibold">Статус</th>
                  <th className="p-4 font-semibold">Создана</th>
                  <th className="p-4" />
                </tr>
              </thead>
              <tbody>
                {applications.map((app) => (
                  <ApplicationRow
                    key={app.id}
                    app={app}
                    open={openId === app.id}
                    busyId={busyId}
                    onCancel={() => cancel(app.id)}
                    onToggle={() => setOpenId(openId === app.id ? null : app.id)}
                    onSigned={(message) => {
                      setNotice(message);
                      setOpenId(null);
                      load();
                    }}
                  />
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  );
}

function ApplicationRow({
  app,
  open,
  busyId,
  onCancel,
  onToggle,
  onSigned,
}: {
  app: MicroloanApplication;
  open: boolean;
  busyId: string | null;
  onCancel: () => void;
  onToggle: () => void;
  onSigned: (message: string) => void;
}) {
  return (
    <>
      <tr className="border-t border-line/60 align-top last:border-0">
        <td className="p-4 font-semibold">{app.typeName}</td>
        <td className="num p-4 text-right">{formatMoney(app.amount)}</td>
        <td className="num p-4 text-right text-stamp">{formatMoney(app.totalAmount)}</td>
        <td className="p-4 text-right">{app.termDays} дн.</td>
        <td className="p-4">
          <Badge tone={MICROLOAN_STATUS_TONES[app.status]}>{MICROLOAN_STATUS_LABELS[app.status]}</Badge>
          {app.status === 'REJECTED' && app.rejectionReason && (
            <p className="mt-1 max-w-xs text-xs text-ink-3">Причина: {app.rejectionReason}</p>
          )}
          {app.status === 'APPROVED' && app.offerExpiresAt && (
            <p className="mt-1 text-xs text-ink-3">Подписать до {formatDateTime(app.offerExpiresAt)}</p>
          )}
        </td>
        <td className="whitespace-nowrap p-4 text-ink-3">{formatDateTime(app.createdAt)}</td>
        <td className="p-4 text-right">
          <div className="flex justify-end gap-2">
            {app.status === 'APPROVED' && (
              <Button size="sm" variant={open ? 'secondary' : 'primary'} onClick={onToggle}>
                {open ? 'Скрыть' : 'Подписать'}
              </Button>
            )}
            {CANCELLABLE.includes(app.status) && (
              <Button size="sm" variant="ghost" loading={busyId === app.id} onClick={onCancel}>
                Отменить
              </Button>
            )}
          </div>
        </td>
      </tr>
      {open && app.status === 'APPROVED' && (
        <tr className="border-t border-line/40 bg-paper-2/40">
          <td colSpan={7} className="p-4">
            <SigningPanel id={app.id} expiresAt={app.offerExpiresAt} onSigned={onSigned} />
          </td>
        </tr>
      )}
    </>
  );
}

/**
 * Подпись договора (MICROLOANS.md §3.10): скачать договор → подтвердить телефон → код из SMS → подписать.
 * Договор доступен сразу после одобрения; подписать нужно до offerExpiresAt (5 рабочих дней).
 */
function SigningPanel({
  id,
  expiresAt,
  onSigned,
}: {
  id: string;
  expiresAt: string | null;
  onSigned: (message: string) => void;
}) {
  const [card, setCard] = useState<MicroloanApplication | null>(null);
  const [profile, setProfile] = useState<CustomerProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [codeSent, setCodeSent] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [busy, setBusy] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [signError, setSignError] = useState<string | null>(null);

  const load = useCallback(
    () =>
      Promise.all([api.getMicroloanApplication(id), api.getProfile()])
        .then(([c, p]) => {
          setCard(c);
          setProfile(p);
          setError(null);
        })
        .catch((err) => setError(errorMessage(err))),
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

  if (error) return <Alert>{error}</Alert>;
  if (!card || (profile === null && !error)) {
    return (
      <div className="grid place-items-center py-6 text-money">
        <Spinner className="h-6 w-6" />
      </div>
    );
  }

  const contract = card.documents.find((d) => d.type === 'CONTRACT') ?? card.documents[0] ?? null;
  const phoneVerified = profile?.phoneVerified ?? false;

  const downloadContract = async () => {
    if (!contract) return;
    setDownloading(true);
    setSignError(null);
    try {
      saveBlob(await api.downloadContract(contract.downloadUrl), `contract-${id}.pdf`);
    } catch (err) {
      setSignError(errorMessage(err));
    } finally {
      setDownloading(false);
    }
  };

  const requestCode = async () => {
    setBusy(true);
    setSignError(null);
    try {
      await api.requestSignatureCode(id);
      setCodeSent(true);
      setCooldown(60);
    } catch (err) {
      setSignError(errorMessage(err));
      if (err instanceof Error && 'retryAfter' in err) {
        const retryAfter = (err as { retryAfter?: number }).retryAfter;
        if (retryAfter) setCooldown(retryAfter);
      }
    } finally {
      setBusy(false);
    }
  };

  const sign = async () => {
    setBusy(true);
    setSignError(null);
    try {
      await api.confirmSignature(id, code.trim());
      onSigned('Договор подписан — заём выдан');
    } catch (err) {
      setSignError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={MICROLOAN_STATUS_TONES[card.status]}>{MICROLOAN_STATUS_LABELS[card.status]}</Badge>
        {expiresAt && <span className="text-xs text-ink-3">Подписать до {formatDateTime(expiresAt)}</span>}
      </div>

      <div className="rounded-xl border border-line bg-white/60 p-4">
        <p className="mb-3 text-xs font-semibold uppercase tracking-wider text-ink-3">Договор</p>
        {contract ? (
          <div className="flex flex-wrap items-center gap-3">
            <Button size="sm" variant="secondary" loading={downloading} onClick={downloadContract}>
              Скачать договор (PDF)
            </Button>
            <span className="text-xs text-ink-3">подписан SHA-256 при отправке кода — документ не может незаметно поменяться</span>
          </div>
        ) : (
          <p className="text-sm text-ink-3">Документ ещё готовится — попробуйте через минуту-другую.</p>
        )}
      </div>

      {!phoneVerified ? (
        <PhoneVerifyPanel phone={profile?.phone ?? ''} onVerified={load} />
      ) : (
        <div className="rounded-xl border border-line bg-white/60 p-4">
          <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-ink-3">Подпись кодом из SMS</p>
          <p className="text-sm text-ink-2">
            Нажмите кнопку — код придёт SMS на номер <span className="num font-semibold">{profile?.phone}</span>.
            Введите его здесь, чтобы подписать договор. Код действует 5 минут, на ввод 3 попытки.
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Button size="sm" variant="secondary" loading={busy} disabled={cooldown > 0} onClick={requestCode}>
              {cooldown > 0 ? `Отправить код (${cooldown}с)` : codeSent ? 'Отправить код заново' : 'Отправить код по SMS'}
            </Button>
            <TextInput
              className="w-40"
              inputMode="numeric"
              maxLength={6}
              placeholder="Код из SMS"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
            />
            <Button size="sm" loading={busy} disabled={!codeSent || code.trim().length < 4} onClick={sign}>
              Подписать
            </Button>
          </div>
        </div>
      )}

      {signError && <Alert>{signError}</Alert>}
    </div>
  );
}
