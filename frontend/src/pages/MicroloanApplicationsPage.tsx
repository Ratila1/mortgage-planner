import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { api, errorMessage, type MicroloanApplication } from '../api';
import { Alert, Badge, Button, Card, EmptyState, PageTitle, Spinner, cx } from '../components/ui';
import { formatMoney, formatMoneyShort, formatDateTime } from '../lib/format';

const STATUS_LABELS: Record<string, string> = {
  NEW: 'Новая',
  UNDER_REVIEW: 'На рассмотрении',
  APPROVED: 'Одобрена',
  REJECTED: 'Отклонена',
};

const STATUS_TONES: Record<string, 'money' | 'stamp' | 'gold' | 'neutral'> = {
  NEW: 'gold',
  UNDER_REVIEW: 'stamp',
  APPROVED: 'money',
  REJECTED: 'neutral',
};

export function MicroloanApplicationsPage() {
  const navigate = useNavigate();
  const [applications, setApplications] = useState<MicroloanApplication[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = () => {
    setLoading(true);
    api.listMyMicroloanApplications()
      .then(setApplications)
      .catch((err) => setError(errorMessage(err)))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const canApply = applications?.some((a) => a.status === 'NEW' || a.status === 'UNDER_REVIEW' || a.status === 'APPROVED') === false;

  return (
    <div className="space-y-8">
      <PageTitle eyebrow="Личный кабинет" title="Мои заявки на микрозайм">
        {canApply && <Button onClick={() => navigate('/microloan/application')}>+ Новая заявка</Button>}
      </PageTitle>

      {error && <Alert>{error}</Alert>}

      {loading ? (
        <div className="grid place-items-center py-24 text-money"><Spinner className="h-8 w-8" /></div>
      ) : applications && applications.length === 0 ? (
        <Card>
          <EmptyState
            title="Заявок пока нет"
            text="Вы ещё не подавали заявок на микрозайм. Начните с калькулятора — выберите сумму и срок, а потом оформите заявку."
            action={<Button onClick={() => navigate('/microloan')}>Перейти к калькулятору</Button>}
            emoji="📋"
          />
        </Card>
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-sm">
              <thead className="bg-paper-2/70 text-left text-xs uppercase tracking-wider text-ink-3">
                <tr>
                  <th className="p-4 font-semibold">Тип займа</th>
                  <th className="p-4 text-right font-semibold">Сумма</th>
                  <th className="p-4 text-right font-semibold">К возврату</th>
                  <th className="p-4 text-right font-semibold">Срок</th>
                  <th className="p-4 font-semibold">Статус</th>
                  <th className="p-4 font-semibold">Создана</th>
                </tr>
              </thead>
              <tbody>
                {applications?.map((app) => (
                  <tr key={app.id} className="border-t border-line/60 last:border-0">
                    <td className="p-4">
                      <span className="font-semibold">{app.typeName}</span>
                      <span className="block text-xs text-ink-3">ID: {app.id.slice(0, 12)}…</span>
                    </td>
                    <td className="num p-4 text-right">{formatMoney(app.amount)}</td>
                    <td className="num p-4 text-right text-stamp">{formatMoney(app.totalAmount)}</td>
                    <td className="p-4 text-right">{app.termDays} дн.</td>
                    <td className="p-4">
                      <Badge tone={STATUS_TONES[app.status]}>{STATUS_LABELS[app.status]}</Badge>
                    </td>
                    <td className="p-4 text-ink-3 whitespace-nowrap">{formatDateTime(app.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {canApply && (
            <div className="mt-4 flex justify-end">
              <Button onClick={() => navigate('/microloan/application')}>+ Новая заявка</Button>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}