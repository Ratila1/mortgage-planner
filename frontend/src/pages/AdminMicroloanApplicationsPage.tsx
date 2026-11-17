import { useCallback, useEffect, useState, type ReactNode } from 'react';
import {
  api,
  errorMessage,
  type AdminCreditHistory,
  type AdminMicroloanApplication,
  type AdminMicroloanApplicationCard,
  type MicroloanApplicationStatus,
} from '../api';
import { Alert, Badge, Button, Card, EmptyState, PageTitle, Select, Spinner, TextInput } from '../components/ui';
import { formatDateTime, formatMoney } from '../lib/format';
import { MICROLOAN_STATUS_LABELS, MICROLOAN_STATUS_TONES } from './MicroloanApplicationsPage';

const STATUSES: MicroloanApplicationStatus[] = ['SUBMITTED', 'APPROVED', 'ACTIVE', 'REJECTED', 'CANCELLED', 'EXPIRED', 'REPAID'];

/** Названия документов (LoanDocument.type). */
const DOCUMENT_LABELS: Record<string, string> = {
  CONTRACT: 'Договор',
  PROFILE: 'Анкета',
};

/**
 * Заявки на микрозаймы (MICROLOANS.md §4.6). Бэк отдаёт список по одному статусу, поэтому здесь фильтр,
 * а не общий список. Действия — только разрешённые переходы: SUBMITTED → одобрить/отклонить с причиной,
 * ACTIVE → «Погашен». Остальные переходы (подпись, истечение) делает клиент или планировщик.
 * Клик «Подробнее» раскрывает карточку: ФИО/телефон, фото паспорта, документы и кредитная история (§3.4).
 */
export function AdminMicroloanApplicationsPage() {
  const [status, setStatus] = useState<MicroloanApplicationStatus>('SUBMITTED');
  const [applications, setApplications] = useState<AdminMicroloanApplication[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<{ id: string; reason: string } | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);

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
    setOpenId(null);
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
                  <FragmentRow
                    key={app.id}
                    app={app}
                    open={openId === app.id}
                    busyId={busyId}
                    rejecting={rejecting}
                    setRejecting={setRejecting}
                    onToggle={() => setOpenId(openId === app.id ? null : app.id)}
                    onAction={(action, message) => act(app, action, message)}
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

/** Строка списка + раскрывающаяся карточка заявки (фото, документы, кредитная история). */
function FragmentRow({
  app,
  open,
  busyId,
  rejecting,
  setRejecting,
  onToggle,
  onAction,
}: {
  app: AdminMicroloanApplication;
  open: boolean;
  busyId: string | null;
  rejecting: { id: string; reason: string } | null;
  setRejecting: (value: { id: string; reason: string } | null) => void;
  onToggle: () => void;
  onAction: (action: () => Promise<void>, message: string) => void;
}) {
  return (
    <>
      <tr className="border-t border-line/60 align-top last:border-0">
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
          <div className="flex justify-end gap-2">
            <Button size="sm" variant={open ? 'secondary' : 'ghost'} onClick={onToggle}>
              {open ? 'Скрыть' : 'Подробнее'}
            </Button>
            {app.status === 'SUBMITTED' && rejecting?.id !== app.id && (
              <>
                <Button
                  size="sm"
                  loading={busyId === app.id}
                  onClick={() => onAction(() => api.adminApproveMicroloanApplication(app.id), 'заявка одобрена')}
                >
                  Одобрить
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setRejecting({ id: app.id, reason: '' })}>
                  Отклонить
                </Button>
              </>
            )}
            {app.status === 'ACTIVE' && (
              <Button
                size="sm"
                variant="secondary"
                loading={busyId === app.id}
                onClick={() => onAction(() => api.adminMarkMicroloanRepaid(app.id), 'заём отмечен погашенным')}
              >
                Погашен
              </Button>
            )}
          </div>
          {app.status === 'SUBMITTED' && rejecting?.id === app.id && (
            <div className="mt-2 flex min-w-72 flex-col gap-2">
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
                    onAction(() => api.adminRejectMicroloanApplication(app.id, rejecting.reason.trim()), 'заявка отклонена')
                  }
                >
                  Отклонить
                </Button>
              </div>
            </div>
          )}
        </td>
      </tr>
      {open && (
        <tr className="border-t border-line/40 bg-paper-2/40">
          <td colSpan={9} className="p-4">
            <ApplicationDetail id={app.id} />
          </td>
        </tr>
      )}
    </>
  );
}

function DetailSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-line bg-white/60 p-4">
      <h4 className="mb-3 text-xs font-semibold uppercase tracking-wider text-ink-3">{title}</h4>
      {children}
    </section>
  );
}

function DetailRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-line/40 py-1.5 last:border-0">
      <span className="shrink-0 text-xs text-ink-3">{label}</span>
      <span className="min-w-0 break-words text-right text-sm">{value ?? '—'}</span>
    </div>
  );
}

/** Карточка заявки: клиент, фото паспорта, документы и кредитная история (GET /api/admin/loan-applications/{id}). */
function ApplicationDetail({ id }: { id: string }) {
  const [card, setCard] = useState<AdminMicroloanApplicationCard | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [photo, setPhoto] = useState<string | null>(null);
  const [photoError, setPhotoError] = useState<string | null>(null);
  const [photoLoading, setPhotoLoading] = useState(false);

  useEffect(() => {
    let alive = true;
    setCard(null);
    setError(null);
    setPhoto(null);
    setPhotoError(null);
    api
      .adminGetMicroloanApplication(id)
      .then((c) => alive && setCard(c))
      .catch((err) => alive && setError(errorMessage(err)));
    return () => {
      alive = false;
    };
  }, [id]);

  // Освобождаем objectURL фото при перерисовке и размонтировании.
  useEffect(() => () => {
    if (photo) URL.revokeObjectURL(photo);
  }, [photo]);

  const showPhoto = async () => {
    setPhotoLoading(true);
    setPhotoError(null);
    try {
      if (photo) URL.revokeObjectURL(photo);
      setPhoto(null);
      const blob = await api.adminViewPassport(id);
      setPhoto(URL.createObjectURL(blob));
    } catch (err) {
      setPhotoError(
        errorMessage(err) || 'Фотография недоступна: возможно, она уже удалена по сроку хранения (7 дней после решения).',
      );
    } finally {
      setPhotoLoading(false);
    }
  };

  if (error) return <Alert>{error}</Alert>;
  if (!card) {
    return (
      <div className="grid place-items-center py-8 text-money">
        <Spinner className="h-6 w-6" />
      </div>
    );
  }

  const fullName = [card.lastName, card.firstName, card.middleName].filter(Boolean).join(' ');

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={MICROLOAN_STATUS_TONES[card.status]}>{MICROLOAN_STATUS_LABELS[card.status]}</Badge>
        {card.debtLoadWarning && <Badge tone="stamp">ПДН выше порога</Badge>}
        <span className="text-xs text-ink-3">Заявка {card.id}</span>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <DetailSection title="Клиент">
          <DetailRow label="ФИО" value={fullName || '—'} />
          <DetailRow label="Телефон" value={card.phone || '—'} />
          <DetailRow label="Доход в месяц" value={formatMoney(card.monthlyIncome)} />
          <DetailRow label="ПДН по займу" value={`${card.debtLoadPercent}%`} />
          <DetailRow label="Подана" value={formatDateTime(card.createdAt)} />
        </DetailSection>

        <DetailSection title="Условия займа">
          <DetailRow label="Тип" value={card.loanTypeName} />
          <DetailRow label="Сумма" value={formatMoney(card.amount)} />
          <DetailRow label="Срок" value={`${card.termDays} дн.`} />
          <DetailRow label="Ставка" value={`${card.dailyRate}%/день`} />
          <DetailRow label="Проценты" value={formatMoney(card.interest)} />
          <DetailRow label="Модификатор" value={formatMoney(card.modifier)} />
          <DetailRow label="К возврату" value={formatMoney(card.total)} />
        </DetailSection>

        <DetailSection title="Решение">
          <DetailRow
            label="Статус"
            value={<Badge tone={MICROLOAN_STATUS_TONES[card.status]}>{MICROLOAN_STATUS_LABELS[card.status]}</Badge>}
          />
          <DetailRow label="Решение принято" value={card.decidedAt ? formatDateTime(card.decidedAt) : '—'} />
          <DetailRow label="Кто решил" value={card.decidedBy ?? '—'} />
          <DetailRow label="Комментарий" value={card.decisionComment || '—'} />
          <DetailRow
            label="Подписать до"
            value={card.offerExpiresAt ? formatDateTime(card.offerExpiresAt) : '—'}
          />
        </DetailSection>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <DetailSection title="Фото паспорта (KYC)">
          {!photo && !photoError && (
            <div>
              <p className="mb-3 text-sm text-ink-2">
                Фото загружается по кнопке и только для админа; каждый просмотр пишется в журнал (§5).
              </p>
              <Button size="sm" variant="secondary" loading={photoLoading} onClick={showPhoto}>
                Показать фото
              </Button>
            </div>
          )}
          {photo && (
            <div className="space-y-2">
              <img src={photo} alt="Фото паспорта клиента" className="max-h-96 w-auto rounded-lg border border-line" />
              <Button size="sm" variant="ghost" onClick={() => { URL.revokeObjectURL(photo); setPhoto(null); }}>
                Скрыть фото
              </Button>
            </div>
          )}
          {photoError && (
            <div>
              <Alert>{photoError}</Alert>
              <Button size="sm" variant="ghost" className="mt-2" onClick={() => { setPhotoError(null); showPhoto(); }}>
                Попробовать снова
              </Button>
            </div>
          )}
        </DetailSection>

        <DetailSection title="Документы заявки">
          {card.documents.length === 0 ? (
            <p className="text-sm text-ink-3">Документов пока нет — договор появится после одобрения и подписи.</p>
          ) : (
            <ul className="space-y-1.5 text-sm">
              {card.documents.map((doc) => (
                <li key={doc.id} className="flex items-baseline justify-between gap-4 border-b border-line/40 py-1.5 last:border-0">
                  <span>{DOCUMENT_LABELS[doc.type] ?? doc.type}</span>
                  <span className="text-xs text-ink-3">{formatDateTime(doc.createdAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </DetailSection>
      </div>

      <DetailSection title="Кредитная история клиента">
        <CreditHistoryBlock history={card.creditHistory} />
      </DetailSection>
    </div>
  );
}

/** Кредитная история: «прочерк», если займов нет; иначе сводка + все займы (§3.12). */
function CreditHistoryBlock({ history }: { history: AdminCreditHistory }) {
  const { summary, loans } = history;
  if (summary.totalLoans === 0) {
    return (
      <p className="text-sm text-ink-3">
        Кредитная история отсутствует — это первая заявка клиента в сервисе.
      </p>
    );
  }
  const incidentCount = Object.values(summary.incidentsByType ?? {}).reduce((sum, n) => sum + n, 0);
  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <DetailSection title="Сводка">
          <DetailRow label="Всего займов" value={summary.totalLoans} />
          <DetailRow
            label="По статусам"
            value={
              Object.entries(summary.byStatus ?? {})
                .map(([s, n]) => `${MICROLOAN_STATUS_LABELS[s as MicroloanApplicationStatus] ?? s}: ${n}`)
                .join(', ') || '—'
            }
          />
          <DetailRow label="Активный займ" value={summary.hasActiveLoan ? 'есть' : 'нет'} />
          <DetailRow label="Инциденты" value={incidentCount === 0 ? 'нет' : String(incidentCount)} />
        </DetailSection>
        <DetailSection title="ПДН клиента">
          <DetailRow label="Последний доход" value={summary.lastMonthlyIncome ? formatMoney(summary.lastMonthlyIncome) : '—'} />
          <DetailRow label="Последний ПДН" value={summary.lastDebtLoadPercent ? `${summary.lastDebtLoadPercent}%` : '—'} />
          <DetailRow label="Минимальный ПДН" value={summary.minDebtLoadPercent ? `${summary.minDebtLoadPercent}%` : '—'} />
          <DetailRow label="Максимальный ПДН" value={summary.maxDebtLoadPercent ? `${summary.maxDebtLoadPercent}%` : '—'} />
        </DetailSection>
        <DetailSection title="Первая заявка">
          <p className="text-sm">
            {summary.firstSubmittedAt ? formatDateTime(summary.firstSubmittedAt) : '—'}
          </p>
        </DetailSection>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] text-sm">
          <thead className="bg-paper-2/70 text-left text-xs uppercase tracking-wider text-ink-3">
            <tr>
              <th className="p-3 font-semibold">Тип займа</th>
              <th className="p-3 text-right font-semibold">Сумма</th>
              <th className="p-3 text-right font-semibold">Срок</th>
              <th className="p-3 font-semibold">Статус</th>
              <th className="p-3 text-right font-semibold">ПДН</th>
              <th className="p-3 font-semibold">Подана</th>
              <th className="p-3 font-semibold">Решение</th>
            </tr>
          </thead>
          <tbody>
            {loans.map((loan) => (
              <tr key={loan.applicationId} className="border-t border-line/60 align-top last:border-0">
                <td className="p-3 font-semibold">{loan.loanTypeName}</td>
                <td className="num p-3 text-right">{formatMoney(loan.amount)}</td>
                <td className="p-3 text-right">{loan.termDays} дн.</td>
                <td className="p-3">
                  <Badge tone={MICROLOAN_STATUS_TONES[loan.status]}>{MICROLOAN_STATUS_LABELS[loan.status]}</Badge>
                  {loan.decisionComment && <p className="mt-1 max-w-xs text-xs text-ink-3">{loan.decisionComment}</p>}
                </td>
                <td className="num p-3 text-right">{loan.debtLoadPercent ? `${loan.debtLoadPercent}%` : '—'}</td>
                <td className="whitespace-nowrap p-3 text-ink-3">{loan.submittedAt ? formatDateTime(loan.submittedAt) : '—'}</td>
                <td className="whitespace-nowrap p-3 text-ink-3">{loan.decidedAt ? formatDateTime(loan.decidedAt) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
