import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import {
  api, errorMessage, type AppNotification, type LoanNotificationPayload, type PdfReadyPayload,
  type Scenario, type ScenarioRecalculatedPayload,
} from '../api';
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

function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(url);
}

function PdfReady({ p, scenarioName }: { p: PdfReadyPayload; scenarioName?: string }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const download = async () => {
    setBusy(true);
    setError(null);
    try {
      saveBlob(await api.downloadPdf(p.scenarioId), p.fileName || `scenario-${p.scenarioId}.pdf`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div>
      <p className="font-semibold">PDF готов{scenarioName ? `: «${scenarioName}»` : ''}</p>
      <p className="mt-0.5 text-sm text-ink-2">График платежей на бумаге. Удобно показать банку — или себе в минуту слабости.</p>
      <Button size="sm" variant="secondary" className="mt-3" loading={busy} onClick={download}>Скачать PDF</Button>
      {error && <p className="mt-2 text-sm text-stamp">{error}</p>}
    </div>
  );
}

/** Заголовки и тексты loan-уведомлений (LoanNotificationConsumer). */
const LOAN_NOTIFICATIONS: Record<string, { title: string; text: string }> = {
  LOAN_SIGNED: { title: 'Договор подписан', text: 'Деньги уже в пути. График погашения — в разделе «Мои заявки».' },
  LOAN_EXPIRED: { title: 'Срок подписания истёк', text: 'Заявку пришлось закрыть: договор не был подписан за 5 рабочих дней.' },
  LOAN_REPAID: { title: 'Заём закрыт', text: 'Долг погашен полностью. Спасибо — приходите ещё!' },
};

function LoanNotification({ n }: { n: AppNotification }) {
  const p = (n.payload ?? {}) as LoanNotificationPayload;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const downloadContract = async () => {
    if (!p.contractUrl) return;
    setBusy(true);
    setError(null);
    try {
      saveBlob(await api.downloadContract(p.contractUrl), `contract-${p.applicationId}.pdf`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  if (n.type === 'LOAN_APPROVED') {
    return (
      <div>
        <p className="font-semibold">Вашу заявку одобрили 🎉</p>
        <p className="mt-0.5 text-sm text-ink-2">
          Договор уже готов — скачайте его, ознакомьтесь с условиями и подпишите кодом из SMS.
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          {p.contractUrl && (
            <Button size="sm" variant="secondary" loading={busy} onClick={downloadContract}>
              Скачать договор (PDF)
            </Button>
          )}
          <Link to="/cabinet/applications" className="text-sm font-semibold text-money">
            Подписать в личном кабинете →
          </Link>
        </div>
        {error && <p className="mt-2 text-sm text-stamp">{error}</p>}
      </div>
    );
  }
  if (n.type === 'LOAN_REJECTED') {
    return (
      <div>
        <p className="font-semibold">Заявку отклонили</p>
        {p.reason && <p className="mt-0.5 text-sm text-ink-2">Причина: {p.reason}</p>}
        <p className="mt-1 text-sm text-ink-2">
          Это не приговор: проверьте данные и подайте заявку снова, когда обстоятельства изменятся.
        </p>
        <Link to="/cabinet/applications" className="mt-2 inline-block text-sm font-semibold text-money">
          К моим заявкам →
        </Link>
      </div>
    );
  }
  const info = LOAN_NOTIFICATIONS[n.type];
  if (info) {
    return (
      <div>
        <p className="font-semibold">{info.title}</p>
        <p className="mt-0.5 text-sm text-ink-2">{info.text}</p>
        <Link to="/cabinet/applications" className="mt-2 inline-block text-sm font-semibold text-money">
          К моим заявкам →
        </Link>
      </div>
    );
  }
  const message = (n.payload as { message?: string } | null)?.message;
  return <p>{message ?? 'Новое уведомление.'}</p>;
}

function Body({ n, scenarios }: { n: AppNotification; scenarios: Map<string, Scenario> }) {
  if (n.type === 'SCENARIO_RECALCULATED' && n.payload) {
    const p = n.payload as ScenarioRecalculatedPayload;
    const up = toNumber(p.newRate) > toNumber(p.oldRate);
    const name = scenarios.get(p.scenarioId)?.name;
    return (
      <div>
        <p className="font-semibold">
          Банк {up ? 'поднял' : 'снизил'} ставку: {formatRate(p.oldRate)} → {formatRate(p.newRate)}
        </p>
        <p className="mt-0.5 text-sm text-ink-2">
          {up ? 'Мы заметили раньше вас. ' : 'Редкий случай, порадуемся вместе. '}
          {name ? <>Сценарий «{name}» пересчитан:</> : 'Ваш сценарий пересчитан:'}
        </p>
        <ul className="mt-2 space-y-1 text-sm">
          <li>Платёж: <Delta before={p.oldMonthlyPayment} after={p.newMonthlyPayment} /></li>
          <li>Переплата: <Delta before={p.oldOverpayment} after={p.newOverpayment} short /></li>
        </ul>
        {name && <Link to="/scenarios" className="mt-2 inline-block text-sm font-semibold text-money">К сценариям →</Link>}
      </div>
    );
  }
  if (n.type === 'PDF_READY' && n.payload) {
    const p = n.payload as PdfReadyPayload;
    return <PdfReady p={p} scenarioName={scenarios.get(p.scenarioId)?.name} />;
  }
  if (n.type.startsWith('LOAN_')) {
    return <LoanNotification n={n} />;
  }
  const message = (n.payload as { message?: string } | null)?.message;
  return <p>{message ?? 'Новое уведомление.'}</p>;
}

export function NotificationsPage() {
  const [items, setItems] = useState<AppNotification[] | null>(null);
  const [scenarios, setScenarios] = useState<Map<string, Scenario>>(new Map());
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listNotifications().then(setItems).catch((err) => setError(errorMessage(err)));
    // В payload только id сценария — подтягиваем названия для понятного текста.
    api
      .listScenarios()
      .then((list) => setScenarios(new Map(list.map((s) => [s.id, s]))))
      .catch(() => undefined);
  }, []);

  const markRead = async (id: string) => {
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
        <EmptyState emoji="🔕" title="Тишина" text="Когда банк поменяет ставку в одном из ваших сценариев или будет готов PDF, мы сразу напишем сюда." />
      ) : (
        <ul className="space-y-3">
          {items?.map((n) => (
            <li key={n.id} className={cx('card flex gap-4 p-5', !n.read && 'border-l-4 border-l-stamp')}>
              <div className="flex-1">
                <div className="mb-2 flex items-center gap-2 text-xs text-ink-3">
                  {!n.read && <Badge tone="stamp">новое</Badge>}
                  <time dateTime={n.createdAt}>{formatDateTime(n.createdAt)}</time>
                </div>
                <Body n={n} scenarios={scenarios} />
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
