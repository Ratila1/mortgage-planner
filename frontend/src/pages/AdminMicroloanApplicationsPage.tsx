import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  api,
  errorMessage,
  type AdminCreditHistory,
  type AdminMicroloanApplication,
  type AdminMicroloanApplicationCard,
  type MicroloanApplicationStatus,
  type MicroloanScoring,
  type ScoringDetail,
} from '../api';
import { Alert, Badge, Button, Card, EmptyState, Field, FilterPanel, PageTitle, Pagination, Select, Skeleton, Spinner, TextInput } from '../components/ui';
import { formatDate, formatDateTime, formatMoney } from '../lib/format';
import { MICROLOAN_STATUS_LABELS, MICROLOAN_STATUS_TONES } from './MicroloanApplicationsPage';

const STATUSES: MicroloanApplicationStatus[] = [
  'SUBMITTED', 'APPROVED', 'ACTIVE', 'OVERDUE', 'REJECTED', 'CANCELLED', 'EXPIRED', 'REPAID',
];

/** С этого дня просрочки админ видит подсказку «кандидат в ЧС» (CREDIT_POLICY.md §5.3). */
const BLACKLIST_HINT_DAYS = 30;

const DECISION_LABELS: Record<NonNullable<MicroloanScoring['decision']>, string> = {
  RECOMMEND: 'рекомендовано',
  REVIEW: 'высокий риск',
  AUTO_REJECT: 'автоотказ',
};

const FACTOR_LABELS: Record<string, string> = {
  HISTORY: 'История',
  PURPOSE: 'Цель (тип займа)',
  AGE: 'Возраст',
  CITY: 'Город',
  SEX: 'Пол',
};

const REASON_LABELS: Record<string, string> = {
  AGE_OUT_OF_RANGE: 'возраст вне 18–90',
  BLACKLISTED: 'чёрный список',
  CURRENT_OVERDUE: 'текущая просрочка',
  MISSING_PROFILE_DATA: 'в профиле не хватает данных',
  PURPOSE_SCORE_MISSING: 'у типа займа нет балла цели',
};

/** Одобрять можно только посчитанную заявку: иначе бэк ответит 409 SCORING_REQUIRED. */
const isScored = (scoring?: MicroloanScoring | null) =>
  scoring?.status === 'COMPLETED' || scoring?.status === 'NOT_CALCULATED';

function ScoringBadge({ scoring }: { scoring?: MicroloanScoring | null }) {
  if (!scoring) return <span className="text-xs text-ink-3">нет</span>;
  if (scoring.status === 'PENDING') return <Badge tone="neutral">считается…</Badge>;
  if (scoring.status === 'NOT_CALCULATED') return <Badge tone="gold">не рассчитан</Badge>;
  const tone = scoring.decision === 'RECOMMEND' ? 'money' : scoring.decision === 'REVIEW' ? 'gold' : 'stamp';
  return (
    <div className="flex flex-col items-start gap-1">
      <Badge tone={tone}>{scoring.ratingClass}</Badge>
      {scoring.pdPercent && <span className="text-xs text-ink-3">PD {scoring.pdPercent}%</span>}
    </div>
  );
}

/** Названия документов (LoanDocument.type). */
const DOCUMENT_LABELS: Record<string, string> = {
  CONTRACT: 'Договор',
  PROFILE: 'Анкета',
};

/**
 * Заявки на микрозаймы (MICROLOANS.md §4.6). Бэк отдаёт список по одному статусу, поэтому здесь фильтр,
 * а не общий список. Действия — только разрешённые переходы: SUBMITTED → одобрить (после скоринга)/отклонить
 * с причиной, ACTIVE и OVERDUE → «Погашен». Остальные переходы (подпись, истечение, просрочка) делает клиент
 * или планировщик. Скоринг — CREDIT_POLICY.md: класс, PD и решение credit-policy.
 * Клик «Подробнее» раскрывает карточку: ФИО/телефон, фото паспорта, документы и кредитная история (§3.4).
 */
export function AdminMicroloanApplicationsPage() {
  const PAGE_SIZE = 20;
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<'ALL' | MicroloanApplicationStatus>('SUBMITTED');
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [sort, setSort] = useState<'date_desc' | 'date_asc' | 'amount_desc' | 'amount_asc' | 'name'>('date_desc');
  const [result, setResult] = useState<{ items: AdminMicroloanApplication[]; total: number; page: number; size: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  // Черновики отказа хранятся по id заявки: набранная причина не зависит от того,
  // какую строку раскрыли сейчас, и не теряется при переключении между строками.
  const [rejectDrafts, setRejectDrafts] = useState<Record<string, string>>({});
  const [openId, setOpenId] = useState<string | null>(null);

  // «Обновить» перечитывает только список заявок — страница и вкладка админки остаются на месте.
  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(t);
  }, [search]);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.adminListMicroloanApplications(status === 'ALL' ? null : status, debouncedSearch, page, PAGE_SIZE);
      setResult(res);
      setError(null);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [status, debouncedSearch, page]);

  // Смена поиска — начинаем с первой страницы.
  useEffect(() => { setPage(0); }, [debouncedSearch]);

  useEffect(() => {
    setResult(null);
    // Смена фильтра/страницы обновляет список: старые раскрытия и черновики уже не нужны.
    setOpenId(null);
    setRejectDrafts({});
    reload();
  }, [reload]);

  const clearDraft = (id: string) => setRejectDrafts((prev) => {
    const next = { ...prev };
    delete next[id];
    return next;
  });

  const act = async (app: AdminMicroloanApplication, action: () => Promise<void>, message: string) => {
    setBusyId(app.id);
    setNotice(null);
    try {
      await action();
      setNotice(`${app.fullName}: ${message}.`);
      clearDraft(app.id);
      await reload();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  };

  const sortedApplications = useMemo(() => {
    if (!result) return null;
    const list = [...result.items];
    switch (sort) {
      case 'date_asc': list.sort((a, b) => a.createdAt.localeCompare(b.createdAt)); break;
      case 'amount_desc': list.sort((a, b) => Number(b.amount) - Number(a.amount)); break;
      case 'amount_asc': list.sort((a, b) => Number(a.amount) - Number(b.amount)); break;
      case 'name': list.sort((a, b) => a.fullName.localeCompare(b.fullName)); break;
      default: list.sort((a, b) => b.createdAt.localeCompare(a.createdAt)); break;
    }
    return list;
  }, [result, sort]);

  return (
    <div>
      <PageTitle eyebrow="Админка" title="Заявки на микрозаймы" />
      <FilterPanel columns={4}>
        <Field label="Поиск" htmlFor="admin-ml-search">
          <TextInput
            id="admin-ml-search"
            placeholder="Поиск по ФИО, телефону, типу..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </Field>
        <Select
          label="Статус"
          value={status}
          options={[{ value: 'ALL', label: 'Все статусы' }, ...STATUSES.map((s) => ({ value: s, label: MICROLOAN_STATUS_LABELS[s] }))]}
          onChange={(v) => {
            setStatus(v as 'ALL' | MicroloanApplicationStatus);
            setPage(0);
          }}
        />
        <Select
          label="Сортировка"
          value={sort}
          options={[
            { value: 'date_desc', label: 'Дата (новые)' },
            { value: 'date_asc', label: 'Дата (старые)' },
            { value: 'amount_desc', label: 'Сумма (убыв.)' },
            { value: 'amount_asc', label: 'Сумма (возр.)' },
            { value: 'name', label: 'Имя' },
          ]}
          onChange={(v) => setSort(v as typeof sort)}
        />
        <div className="flex items-end">
          <Button variant="secondary" className="h-11 w-full" onClick={reload} loading={loading} disabled={loading}>
            Обновить
          </Button>
        </div>
      </FilterPanel>

      {notice && <div className="mb-4"><Alert tone="money">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      {result === null ? (
        <div className="space-y-3" aria-hidden="true">
          <Skeleton className="h-12" />
          <Skeleton className="h-64" />
        </div>
      ) : !sortedApplications || sortedApplications.length === 0 ? (
        <Card>
          <EmptyState title="Нет заявок" text={status === 'ALL' ? 'Заявок пока нет.' : `Нет заявок в статусе <${MICROLOAN_STATUS_LABELS[status]}>.`} emoji="??" />
        </Card>
      ) : (
        <Card>
          {/* Страховка от вылезания колонок: широкий экран — влезаем, узкий — горизонтальный скролл. */}
          <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-paper-2/70 text-left text-xs uppercase tracking-wider text-ink-3">
              <tr>
                <th className="p-3 font-semibold">Клиент</th>
                <th className="p-3 font-semibold">Займ</th>
                <th className="p-3 text-right font-semibold">Доход / ПДН</th>
                <th className="p-3 font-semibold">Скоринг</th>
                <th className="p-3 font-semibold">Статус</th>
                <th className="p-3" />
              </tr>
            </thead>
            <tbody>
              {sortedApplications?.map((app) => (
                <FragmentRow
                  key={app.id}
                  app={app}
                  open={openId === app.id}
                  busyId={busyId}
                  draft={rejectDrafts[app.id] ?? null}
                  onToggle={() => setOpenId(openId === app.id ? null : app.id)}
                  onStartReject={() => {
                    setRejectDrafts((prev) => ({ ...prev, [app.id]: '' }));
                    setOpenId(app.id);
                  }}
                  onDraftChange={(reason) => setRejectDrafts((prev) => ({ ...prev, [app.id]: reason }))}
                  onCancelReject={() => clearDraft(app.id)}
                  onReject={(reason) => act(app, () => api.adminRejectMicroloanApplication(app.id, reason.trim()), 'заявка отклонена')}
                  onAction={(action, message) => act(app, action, message)}
                />
              ))}
            </tbody>
          </table>
          </div>
        </Card>
      )}

      <Pagination page={page} total={result?.total ?? 0} pageSize={PAGE_SIZE} onChange={setPage} />
    </div>
  );
}

/** Строка списка + раскрывающаяся карточка заявки (фото, документы, кредитная история). */
function FragmentRow({
  app,
  open,
  busyId,
  draft,
  onToggle,
  onStartReject,
  onDraftChange,
  onCancelReject,
  onReject,
  onAction,
}: {
  app: AdminMicroloanApplication;
  open: boolean;
  busyId: string | null;
  draft: string | null;
  onToggle: () => void;
  onStartReject: () => void;
  onDraftChange: (reason: string) => void;
  onCancelReject: () => void;
  onReject: (reason: string) => void;
  onAction: (action: () => Promise<void>, message: string) => void;
}) {
  return (
    <>
      <tr className="border-t border-line/60 align-top last:border-0">
        {/* Узкие отступы и truncate: минимальная ширина таблицы меньше карточки,
            поэтому десктопный скролл не появляется. */}
        <td className="p-3">
          <span className="block max-w-36 truncate font-semibold" title={app.fullName}>{app.fullName}</span>
          <span className="mt-1 block text-xs text-ink-3">{formatDateTime(app.createdAt)}</span>
        </td>
        <td className="p-3">
          <span className="block max-w-32 truncate" title={app.typeName}>{app.typeName}</span>
          <span className="num mt-1 block text-xs text-ink-3">
            {formatMoney(app.amount)} · {app.termDays} дн.
          </span>
        </td>
        <td className="p-3 text-right">
          <span className="num block whitespace-nowrap">{formatMoney(app.monthlyIncome)}</span>
          <span className={`num mt-1 block whitespace-nowrap text-xs ${app.debtLoadWarning ? 'font-bold text-stamp' : 'text-ink-3'}`}>
            ПДН {app.debtLoadPercent}%
          </span>
        </td>
        <td className="p-3">
          <ScoringBadge scoring={app.scoring} />
        </td>
        <td className="p-3">
          <Badge tone={MICROLOAN_STATUS_TONES[app.status]}>{MICROLOAN_STATUS_LABELS[app.status]}</Badge>
          {app.status === 'OVERDUE' && (
            <p className="mt-1 text-xs font-semibold text-stamp">
              {app.overdueDays ?? 0} дн.
              {(app.overdueDays ?? 0) >= BLACKLIST_HINT_DAYS && ' · кандидат в ЧС'}
            </p>
          )}
        </td>
        <td className="p-3 text-right">
          {/* Кнопки переносятся, а не расширяют ячейку; форма отказа живёт в раскрытии ниже — cell не раздувается.
              Блок кнопок прятан, пока набирается причина отказа: одобрение и отказ взаимоисключающие. */}
          <div className="flex flex-wrap justify-end gap-2">
            <Button size="sm" variant={open ? 'secondary' : 'ghost'} onClick={onToggle}>
              {open ? 'Скрыть' : 'Подробнее'}
            </Button>
            {app.status === 'SUBMITTED' && draft === null && (
              <>
                <Button
                  size="sm"
                  loading={busyId === app.id}
                  disabled={!isScored(app.scoring)}
                  title={isScored(app.scoring) ? undefined : 'Одобрить можно после результата скоринга'}
                  onClick={() => onAction(() => api.adminApproveMicroloanApplication(app.id), 'заявка одобрена')}
                >
                  Одобрить
                </Button>
                {!isScored(app.scoring) && (
                  <Button
                    size="sm"
                    variant="secondary"
                    loading={busyId === app.id}
                    onClick={() => onAction(() => api.adminRequestScoring(app.id), 'скоринг запрошен')}
                  >
                    Запросить скоринг
                  </Button>
                )}
                <Button size="sm" variant="ghost" onClick={onStartReject}>
                  Отклонить
                </Button>
              </>
            )}
            {(app.status === 'ACTIVE' || app.status === 'OVERDUE') && (
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
        </td>
      </tr>
      {/* Форма отказа видна, пока есть черновик, даже если карточка заявки закрыта:
          иначе набранная причина пропадала, а кнопки строки оставались скрытыми. */}
      {(open || draft !== null) && (
        <tr className="border-t border-line/40 bg-paper-2/40">
          {/* Ширина раскрытия = числу колонок основной таблицы (6), иначе браузер
              резервирует стихийные колонки и таблица вылезает вправо. */}
          <td colSpan={6} className="p-4">
            {draft !== null && (
              <div className="mb-4 max-w-xl space-y-2 rounded-xl border border-line bg-field p-4">
                <label className="block text-sm font-medium text-ink-2" htmlFor={`reject-reason-${app.id}`}>
                  Причина отказа <span className="font-normal text-ink-3">(увидит клиент)</span>
                </label>
                <TextInput
                  id={`reject-reason-${app.id}`}
                  autoFocus
                  placeholder="Например: неполный пакет документов"
                  value={draft}
                  maxLength={1000}
                  onChange={(e) => onDraftChange(e.target.value)}
                />
                <div className="flex justify-end gap-2">
                  <Button size="sm" variant="ghost" onClick={onCancelReject}>Отмена</Button>
                  <Button
                    size="sm"
                    loading={busyId === app.id}
                    disabled={!draft.trim()}
                    onClick={() => onReject(draft)}
                  >
                    Отклонить
                  </Button>
                </div>
              </div>
            )}
            {open && <ApplicationDetail id={app.id} />}
          </td>
        </tr>
      )}
    </>
  );
}

function DetailSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-line bg-field p-4">
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
          <DetailRow label="Вернуть до" value={card.dueDate ? formatDate(card.dueDate) : '—'} />
          {card.paidAt && <DetailRow label="Оплачен" value={formatDate(card.paidAt)} />}
          {(card.overdueDays ?? 0) > 0 && <DetailRow label="Просрочка" value={`${card.overdueDays} дн.`} />}
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

      <ScoringSection applicationId={card.id} scoring={card.scoring} />

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
              <img src={photo} alt="Фото паспорта клиента" className="max-h-96 w-auto max-w-full rounded-lg border border-line" />
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

      {/* Внутри раскрытия таблица тоже скроллится, а не вылезает за карточку. */}
      <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="bg-paper-2/70 text-left text-xs uppercase tracking-wider text-ink-3">
          <tr>
            <th className="p-3 font-semibold">Тип займа</th>
            <th className="p-3 text-right font-semibold">Сумма · Срок</th>
            <th className="p-3 font-semibold">Статус</th>
            <th className="p-3 text-right font-semibold">ПДН</th>
            <th className="p-3 font-semibold">Подана · Решена</th>
          </tr>
        </thead>
        <tbody>
          {loans.map((loan) => (
            <tr key={loan.applicationId} className="border-t border-line/60 align-top last:border-0">
              <td className="p-3 font-semibold">{loan.loanTypeName}</td>
              <td className="num p-3 text-right">{formatMoney(loan.amount)} · {loan.termDays} дн.</td>
              <td className="p-3">
                <Badge tone={MICROLOAN_STATUS_TONES[loan.status]}>{MICROLOAN_STATUS_LABELS[loan.status]}</Badge>
                {loan.decisionComment && <p className="mt-1 max-w-xs text-xs text-ink-3">{loan.decisionComment}</p>}
              </td>
              <td className="num p-3 text-right">{loan.debtLoadPercent ? `${loan.debtLoadPercent}%` : '—'}</td>
              <td className="p-3 text-ink-3">
                <span className="block">{loan.submittedAt ? formatDateTime(loan.submittedAt) : '—'}</span>
                <span className="block text-xs">{loan.decidedAt ? formatDateTime(loan.decidedAt) : '—'}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
    </div>
  );
}

/** Скоринг заявки: итог из монолита + расшифровка по факторам из credit-policy (CREDIT_POLICY.md §6.4). */
function ScoringSection({ applicationId, scoring }: { applicationId: string; scoring?: MicroloanScoring | null }) {
  const [detail, setDetail] = useState<ScoringDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setDetail(null);
    setError(null);
    if (!isScored(scoring)) return;
    api
      .adminGetScoringDetail(applicationId)
      .then((d) => alive && setDetail(d))
      .catch((err) => alive && setError(errorMessage(err)));
    return () => {
      alive = false;
    };
  }, [applicationId, scoring?.status]);

  if (!scoring) {
    return (
      <DetailSection title="Скоринг">
        <p className="text-sm text-ink-2">Заявка подана до появления скоринга — нажмите «Запросить скоринг».</p>
      </DetailSection>
    );
  }
  if (scoring.status === 'PENDING') {
    return (
      <DetailSection title="Скоринг">
        <p className="text-sm text-ink-2">Считается… Если результат не пришёл за минуту — «Запросить скоринг».</p>
      </DetailSection>
    );
  }
  return (
    <DetailSection title="Скоринг">
      <div className="grid gap-4 lg:grid-cols-3">
        <div>
          <DetailRow label="Класс" value={<ScoringBadge scoring={scoring} />} />
          <DetailRow label="Скорбалл" value={scoring.points == null ? '—' : `${scoring.points} из 400`} />
          <DetailRow label="PD (вероятность невозврата)" value={scoring.pdPercent ? `${scoring.pdPercent}%` : '—'} />
          <DetailRow label="Решение" value={scoring.decision ? DECISION_LABELS[scoring.decision] : '—'} />
          {detail && detail.reasons.length > 0 && (
            <DetailRow label="Причины" value={detail.reasons.map((r) => REASON_LABELS[r] ?? r).join(', ')} />
          )}
        </div>
        <div className="min-w-0 lg:col-span-2">
          {error && <Alert>{error}</Alert>}
          {detail && detail.breakdown.length > 0 && (
            <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs uppercase tracking-wider text-ink-3">
                <tr>
                  <th className="py-1 font-semibold">Фактор</th>
                  <th className="py-1 font-semibold">Значение</th>
                  <th className="py-1 text-right font-semibold">Балл</th>
                  <th className="py-1 text-right font-semibold">Вес</th>
                  <th className="py-1 text-right font-semibold">Вклад</th>
                </tr>
              </thead>
              <tbody>
                {detail.breakdown.map((f) => (
                  <tr key={f.factor} className="border-t border-line/40">
                    <td className="py-1">{FACTOR_LABELS[f.factor] ?? f.factor}</td>
                    <td className="py-1 text-ink-2">{f.value ?? '—'}</td>
                    <td className="num py-1 text-right">{Number(f.score).toFixed(2)}</td>
                    <td className="num py-1 text-right">{Math.round(Number(f.weight) * 100)}%</td>
                    <td className="num py-1 text-right">{Number(f.contribution).toFixed(3)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          )}
          {detail && <p className="mt-2 text-xs text-ink-3">Правила версии {detail.rulesVersion}</p>}
        </div>
      </div>
    </DetailSection>
  );
}
