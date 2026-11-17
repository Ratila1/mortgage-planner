import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage, type AdminMicroloanApplication, type MicroloanApplicationStatus } from '../api';
import { Alert, Badge, Button, Card, EmptyState, PageTitle, Select, Spinner, TextInput } from '../components/ui';
import { formatDateTime, formatMoney } from '../lib/format';
import { MICROLOAN_STATUS_LABELS, MICROLOAN_STATUS_TONES } from './MicroloanApplicationsPage';

const STATUSES: MicroloanApplicationStatus[] = ['SUBMITTED', 'APPROVED', 'ACTIVE', 'REJECTED', 'CANCELLED', 'EXPIRED', 'REPAID'];

/**
 * Заявки на микрозаймы (MICROLOANS.md §4.6). Бэк отдаёт список по одному статусу, поэтому здесь фильтр,
 * а не общий список. Действия — только разрешённые переходы: SUBMITTED → одобрить/отклонить с причиной,
 * ACTIVE → «Погашен». Остальные переходы (подпись, истечение) делает клиент или планировщик.
 */
export function AdminMicroloanApplicationsPage() {
  const [status, setStatus] = useState<MicroloanApplicationStatus>('SUBMITTED');
  const [applications, setApplications] = useState<AdminMicroloanApplication[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<{ id: string; reason: string } | null>(null);

  // «Обновить» перечитывает только список заявок — страница и вкладка админки остаются на месте.
  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setApplications(await api.adminListMicroloanApplications(status));
      setError(null);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => {
    setApplications(null);
    reload();
  }, [reload]);

  const act = async (app: AdminMicroloanApplication, action: () => Promise<void>, message: string) => {
    setBusyId(app.id);
    setNotice(null);
    try {
      await action();
      setNotice(`${app.fullName}: ${message}.`);
      setRejecting(null);
      await reload();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <PageTitle eyebrow="Админка" title="Заявки на микрозаймы">
        <div className="flex items-end gap-2">
          <Select
            value={status}
            options={STATUSES.map((s) => ({ value: s, label: MICROLOAN_STATUS_LABELS[s] }))}
            onChange={setStatus}
            className="w-56"
          />
          <Button variant="secondary" size="sm" onClick={reload} loading={loading} disabled={loading}>
            Обновить
          </Button>
        </div>
      </PageTitle>

      {notice && <div className="mb-4"><Alert tone="money">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      {applications === null ? (
        <div className="grid place-items-center py-24 text-money"><Spinner className="h-8 w-8" /></div>
      ) : applications.length === 0 ? (
        <Card>
          <EmptyState title="Заявок нет" text={`Нет заявок в статусе «${MICROLOAN_STATUS_LABELS[status]}».`} emoji="📋" />
        </Card>
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[960px] text-sm">
              <thead className="bg-paper-2/70 text-left text-xs uppercase tracking-wider text-ink-3">
                <tr>
                  <th className="p-4 font-semibold">Клиент</th>
                  <th className="p-4 font-semibold">Тип займа</th>
                  <th className="p-4 text-right font-semibold">Сумма</th>
                  <th className="p-4 text-right font-semibold">Срок</th>
                  <th className="p-4 text-right font-semibold">Зарплата</th>
                  <th className="p-4 text-right font-semibold">ПДН</th>
                  <th className="p-4 font-semibold">Статус</th>
                  <th className="p-4 font-semibold">Создана</th>
                  <th className="p-4" />
                </tr>
              </thead>
              <tbody>
                {applications.map((app) => (
                  <tr key={app.id} className="border-t border-line/60 align-top last:border-0">
                    <td className="p-4 font-semibold">{app.fullName}</td>
                    <td className="p-4">{app.typeName}</td>
                    <td className="num p-4 text-right">{formatMoney(app.amount)}</td>
                    <td className="p-4 text-right">{app.termDays} дн.</td>
                    <td className="num p-4 text-right">{formatMoney(app.monthlyIncome)}</td>
                    <td className={`num p-4 text-right ${app.debtLoadWarning ? 'font-bold text-stamp' : ''}`}>
                      {app.debtLoadPercent}%
                    </td>
                    <td className="p-4">
                      <Badge tone={MICROLOAN_STATUS_TONES[app.status]}>{MICROLOAN_STATUS_LABELS[app.status]}</Badge>
                    </td>
                    <td className="whitespace-nowrap p-4 text-ink-3">{formatDateTime(app.createdAt)}</td>
                    <td className="p-4 text-right">
                      {app.status === 'SUBMITTED' && rejecting?.id !== app.id && (
                        <div className="flex justify-end gap-2">
                          <Button
                            size="sm"
                            loading={busyId === app.id}
                            onClick={() => act(app, () => api.adminApproveMicroloanApplication(app.id), 'заявка одобрена')}
                          >
                            Одобрить
                          </Button>
                          <Button size="sm" variant="ghost" onClick={() => setRejecting({ id: app.id, reason: '' })}>
                            Отклонить
                          </Button>
                        </div>
                      )}
                      {app.status === 'SUBMITTED' && rejecting?.id === app.id && (
                        <div className="flex min-w-72 flex-col gap-2">
                          <TextInput
                            autoFocus
                            placeholder="Причина отказа (увидит клиент)"
                            value={rejecting.reason}
                            maxLength={1000}
                            onChange={(e) => setRejecting({ id: app.id, reason: e.target.value })}
                          />
                          <div className="flex justify-end gap-2">
                            <Button size="sm" variant="ghost" onClick={() => setRejecting(null)}>Отмена</Button>
                            <Button
                              size="sm"
                              loading={busyId === app.id}
                              disabled={!rejecting.reason.trim()}
                              onClick={() =>
                                act(app, () => api.adminRejectMicroloanApplication(app.id, rejecting.reason.trim()), 'заявка отклонена')
                              }
                            >
                              Отклонить
                            </Button>
                          </div>
                        </div>
                      )}
                      {app.status === 'ACTIVE' && (
                        <Button
                          size="sm"
                          variant="secondary"
                          loading={busyId === app.id}
                          onClick={() => act(app, () => api.adminMarkMicroloanRepaid(app.id), 'заём отмечен погашенным')}
                        >
                          Погашен
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  );
}
