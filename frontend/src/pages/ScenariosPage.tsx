import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, errorMessage, type BankOffer, type Scenario } from '../api';
import { Alert, Badge, Button, EmptyState, PageTitle, Spinner, cx } from '../components/ui';
import { formatDate, formatMoney, formatMoneyShort, formatRate, formatTerm } from '../lib/format';

const MAX_COMPARE = 3;

export function ScenariosPage() {
  const navigate = useNavigate();
  const [scenarios, setScenarios] = useState<Scenario[] | null>(null);
  const [offers, setOffers] = useState<BankOffer[]>([]);
  const [selected, setSelected] = useState<number[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);

  useEffect(() => {
    api.listScenarios().then(setScenarios).catch((err) => setError(errorMessage(err)));
    api.listOffers().then(setOffers).catch(() => undefined);
  }, []);

  const toggle = (id: number) =>
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : prev.length < MAX_COMPARE ? [...prev, id] : prev));

  const remove = async (scenario: Scenario) => {
    if (!confirm(`Удалить «${scenario.name}»? Банк об этом не узнает.`)) return;
    setDeleting(scenario.id);
    try {
      await api.deleteScenario(scenario.id);
      setScenarios((list) => list?.filter((s) => s.id !== scenario.id) ?? null);
      setSelected((prev) => prev.filter((x) => x !== scenario.id));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setDeleting(null);
    }
  };

  return (
    <div>
      <PageTitle eyebrow="Личный кабинет" title="Мои сценарии">
        <Button variant="secondary" onClick={() => navigate('/')}>+ Новый расчёт</Button>
      </PageTitle>

      {error && <div className="mb-6"><Alert>{error}</Alert></div>}

      {scenarios === null && !error ? (
        <div className="grid place-items-center py-24 text-money"><Spinner className="h-8 w-8" /></div>
      ) : scenarios && scenarios.length === 0 ? (
        <EmptyState
          emoji="🏚️"
          title="Пока ни одного сценария"
          text="Посчитайте ипотеку и сохраните результат. Можно несколько — чтобы выбрать наименее грустный."
          action={<Button onClick={() => navigate('/')}>Открыть калькулятор</Button>}
        />
      ) : (
        <>
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {scenarios?.map((s) => {
              const offer = offers.find((o) => o.id === s.offerId);
              const isSelected = selected.includes(s.id);
              return (
                <article key={s.id} className={cx('card flex flex-col p-5 transition', isSelected && 'ring-2 ring-money')}>
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <h2 className="truncate font-display text-base font-bold" title={s.name}>{s.name}</h2>
                      <p className="mt-0.5 text-xs text-ink-3">от {formatDate(s.createdAt)}</p>
                    </div>
                    <label className="flex shrink-0 cursor-pointer items-center gap-1.5 text-xs text-ink-2">
                      <input
                        type="checkbox"
                        className="h-4 w-4 accent-money"
                        checked={isSelected}
                        disabled={!isSelected && selected.length >= MAX_COMPARE}
                        onChange={() => toggle(s.id)}
                      />
                      сравнить
                    </label>
                  </div>

                  <div className="mt-3 flex flex-wrap gap-1.5">
                    <Badge>{s.params.type === 'ANNUITY' ? 'Аннуитет' : 'Дифференц.'}</Badge>
                    <Badge>{formatRate(s.params.annualRate)}</Badge>
                    <Badge>{formatTerm(s.summary.termMonths)}</Badge>
                    {offer && <Badge tone="money">{offer.bankName}</Badge>}
                    {s.params.earlyRepayments.length > 0 && <Badge tone="gold">досрочки: {s.params.earlyRepayments.length}</Badge>}
                  </div>

                  <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
                    <div>
                      <dt className="text-xs text-ink-3">Платёж</dt>
                      <dd className="num font-bold">{formatMoney(s.summary.monthlyPayment)}</dd>
                    </div>
                    <div>
                      <dt className="text-xs text-ink-3">Переплата</dt>
                      <dd className="num font-bold text-stamp">{formatMoneyShort(s.summary.overpayment)}</dd>
                    </div>
                    <div>
                      <dt className="text-xs text-ink-3">Цена жилья</dt>
                      <dd className="num">{formatMoneyShort(s.params.price)}</dd>
                    </div>
                    <div>
                      <dt className="text-xs text-ink-3">Всего</dt>
                      <dd className="num">{formatMoneyShort(s.summary.totalPaid)}</dd>
                    </div>
                  </dl>

                  {s.updatedAt !== s.createdAt && (
                    <p className="mt-3 text-xs text-[#8a6100]">Пересчитан {formatDate(s.updatedAt)} — банк поменял ставку.</p>
                  )}

                  <div className="mt-auto flex gap-2 pt-5">
                    <Button size="sm" variant="secondary" className="flex-1" onClick={() => navigate('/', { state: { params: s.params, offerId: s.offerId } })}>
                      Открыть
                    </Button>
                    <Button size="sm" variant="ghost" loading={deleting === s.id} onClick={() => remove(s)}>Удалить</Button>
                  </div>
                </article>
              );
            })}
          </div>

          {scenarios && scenarios.length > 1 && (
            <div className="sticky bottom-4 z-20 mt-8 flex justify-center">
              <div className="flex items-center gap-3 rounded-2xl border border-line bg-card/95 px-4 py-3 shadow-[var(--shadow-card)] backdrop-blur">
                <span className="text-sm text-ink-2">Выбрано {selected.length} из {MAX_COMPARE}</span>
                {selected.length >= 2 ? (
                  <Link to={`/compare?ids=${selected.join(',')}`} className="rounded-xl bg-money px-4 py-2 text-sm font-semibold text-white hover:bg-money-2">
                    Сравнить
                  </Link>
                ) : (
                  <span className="text-xs text-ink-3">отметьте 2–3 сценария</span>
                )}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
