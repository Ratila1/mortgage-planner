import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import {
  api,
  errorMessage,
  type MicroloanApplication,
  type MicroloanApplicationStatus,
} from '../api';
import { Alert, Badge, Button, Card, EmptyState, PageTitle, Spinner } from '../components/ui';
import { ContractSigning } from '../components/ContractSigning';
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

export function MicroloanApplicationsPage() {
  const navigate = useNavigate();
  const [applications, setApplications] = useState<MicroloanApplication[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  // Ссылка из письма (/loans/{id}) ведёт сюда с ?open={id} — сразу раскрываем подпись этой заявки.
  const [params] = useSearchParams();
  const [openId, setOpenId] = useState<string | null>(params.get('open'));

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
            <ContractSigning id={app.id} onSigned={() => onSigned('Договор подписан — заём выдан')} />
          </td>
        </tr>
      )}
    </>
  );
}
