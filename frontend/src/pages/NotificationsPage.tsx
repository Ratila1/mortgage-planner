import { useEffect, useState } from 'react';
import { api, errorMessage, type AppNotification, type ScenarioRecalculatedPayload } from '../api';
import { Alert, Badge, Button, EmptyState, PageTitle, Spinner, cx } from '../components/ui';
import { formatDateTime, formatMoney, formatMoneyShort, formatRate, toNumber } from '../lib/format';

function Delta({ before, after, short = false }: { before: string; after: string; short?: boolean }) {
  const diff = toNumber(after) - toNumber(before);
  const fmt = short ? formatMoneyShort : formatMoney;
  return (
    <span className="num">
      {fmt(before)} → <b>{fmt(after)}</b>{' '}
      <span className={diff > 0 ? 'text-stamp' : 'text-money'}>
        ({diff > 0 ? '+' : '−'}{fmt(Math.abs(diff))})
      </span>
    </span>
  );
}

function Body({ n }: { n: AppNotification }) {
  if (n.type === 'SCENARIO_RECALCULATED') {
    const p = n.payload as ScenarioRecalculatedPayload;
    const up = toNumber(p.newRate) > toNumber(p.oldRate);
    return (
      <div>
        <p className="font-semibold">
          {p.bankName} {up ? 'поднял' : 'снизил'} ставку: {formatRate(p.oldRate)} → {formatRate(p.newRate)}
        </p>
        <p className="mt-0.5 text-sm text-ink-2">
          {up ? 'Мы заметили раньше вас. ' : 'Редкий случай, порадуемся вместе. '}Сценарий «{p.scenarioName}» пересчитан:
        </p>
        <ul className="mt-2 space-y-1 text-sm">
          <li>Платёж: <Delta before={p.oldMonthlyPayment} after={p.newMonthlyPayment} /></li>
          <li>Переплата: <Delta before={p.oldOverpayment} after={p.newOverpayment} short /></li>
        </ul>
      </div>
    );
  }
  return <p>{(n.payload as { message: string }).message}</p>;
}

export function NotificationsPage() {
  const [items, setItems] = useState<AppNotification[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listNotifications().then(setItems).catch((err) => setError(errorMessage(err)));
  }, []);

  const markRead = async (id: number) => {
    setItems((list) => list?.map((n) => (n.id === id ? { ...n, read: true } : n)) ?? null);
    try {
      await api.markNotificationRead(id);
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const unread = items?.filter((n) => !n.read) ?? [];

  return (
    <div className="mx-auto max-w-3xl">
      <PageTitle eyebrow="Новости о ваших деньгах" title="Уведомления">
        {unread.length > 0 && (
          <Button variant="ghost" size="sm" onClick={() => unread.forEach((n) => markRead(n.id))}>Прочитать все</Button>
        )}
      </PageTitle>
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {items === null && !error ? (
        <div className="grid place-items-center py-24 text-money"><Spinner className="h-8 w-8" /></div>
      ) : items && items.length === 0 ? (
        <EmptyState emoji="🔕" title="Тишина" text="Когда банк поменяет ставку в одном из ваших сценариев, мы сразу напишем сюда и на почту." />
      ) : (
        <ul className="space-y-3">
          {items?.map((n) => (
            <li key={n.id} className={cx('card flex gap-4 p-5', !n.read && 'border-l-4 border-l-stamp')}>
              <div className="flex-1">
                <div className="mb-2 flex items-center gap-2 text-xs text-ink-3">
                  {!n.read && <Badge tone="stamp">новое</Badge>}
                  <time dateTime={n.createdAt}>{formatDateTime(n.createdAt)}</time>
                </div>
                <Body n={n} />
              </div>
              {!n.read && (
                <Button variant="ghost" size="sm" className="self-start" onClick={() => markRead(n.id)}>Прочитано</Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
