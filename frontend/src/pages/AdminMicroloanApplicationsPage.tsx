import { useEffect, useState } from 'react';
import { api, errorMessage, type MicroloanApplication, type MicroloanApplicationStatus } from '../api';
import { Alert, Badge, Button, Card, EmptyState, PageTitle, Select, Spinner, cx } from '../components/ui';
import { formatMoney, formatMoneyShort, formatDateTime } from '../lib/format';

const STATUS_LABELS: Record<string, string> = {
  NEW: 'Новая',
  UNDER_REVIEW: 'На рассмотрении',
  APPROVED: 'Одобрена',
  REJECTED: 'Отклонена',
};

const STATUSES: MicroloanApplicationStatus[] = ['NEW', 'UNDER_REVIEW', 'APPROVED', 'REJECTED'];

const STATUS_TONES: Record<string, 'money' | 'stamp' | 'gold' | 'neutral'> = {
  NEW: 'gold',
  UNDER_REVIEW: 'stamp',
  APPROVED: 'money',
  REJECTED: 'neutral',
};

function StatusBadge({ status }: { status: MicroloanApplicationStatus }) {
  return <Badge tone={STATUS_TONES[status]}>{STATUS_LABELS[status]}</Badge>;
}

export function AdminMicroloanApplicationsPage() {
  const [applications, setApplications] = useState<MicroloanApplication[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<string | null>(null);

  const reload = () => {
    setLoading(true);
    api.adminListMicroloanApplications()
      .then(setApplications)
      .catch((err) => setError(errorMessage(err)))
      .finally(() => setLoading(false));
  };

  useEffect(() => { reload(); }, []);

  const forceReload = () => {
    // Перечитываем из localStorage через реинициализацию мока (простая перезагрузка страницы)
    window.location.reload();
  };

  const changeStatus = async (app: MicroloanApplication, newStatus: MicroloanApplicationStatus) => {
    try {
      await api.adminUpdateMicroloanApplicationStatus(app.id, newStatus);
      setNotice(`Статус заявки «${app.typeName}» изменён на «${STATUS_LABELS[newStatus]}».`);
      await reload();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  return (
    <div>
      <PageTitle eyebrow="Админка" title="Заявки на микрозаймы">
        <Button variant="secondary" size="sm" onClick={forceReload} disabled={loading}>
          Обновить
        </Button>
      </PageTitle>

      {notice && <div className="mb-4"><Alert tone="money">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      {loading ? (
        <div className="grid place-items-center py-24 text-money"><Spinner className="h-8 w-8" /></div>
      ) : applications && applications.length === 0 ? (
        <Card>
          <EmptyState
            title="Заявок пока нет"
            text="Пользователи ещё не подавали заявок на микрозайм."
            emoji="📋"
          />
        </Card>
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[880px] text-sm">
              <thead className="bg-paper-2/70 text-left text-xs uppercase tracking-wider text-ink-3">
                <tr>
                  <th className="p-4 font-semibold">Пользователь</th>
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
                {applications?.map((app) => (
                  <tr key={app.id} className="border-t border-line/60 last:border-0">
                    <td className="p-4">
                      <span className="font-semibold">{app.profile.fullName}</span>
                      <span className="block text-xs text-ink-3">{app.profile.phone}</span>
                      <span className="block text-xs text-ink-3">{app.profile.email}</span>
                    </td>
                    <td className="p-4">
                      <span className="font-semibold">{app.typeName}</span>
                      <span className="block text-xs text-ink-3">ID: {app.id.slice(0, 12)}…</span>
                    </td>
                    <td className="num p-4 text-right">{formatMoney(app.amount)}</td>
                    <td className="num p-4 text-right text-stamp">{formatMoney(app.totalAmount)}</td>
                    <td className="p-4 text-right">{app.termDays} дн.</td>
                    <td className="p-4"><StatusBadge status={app.status} /></td>
                    <td className="p-4 text-ink-3 whitespace-nowrap">{formatDateTime(app.createdAt)}</td>
                    <td className="p-4 text-right whitespace-nowrap">
                      <Select
                        value={app.status}
                        options={STATUSES.map((s) => ({ value: s, label: STATUS_LABELS[s] }))}
                        onChange={(newStatus) => changeStatus(app, newStatus)}
                        label="Изменить статус"
                      />
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