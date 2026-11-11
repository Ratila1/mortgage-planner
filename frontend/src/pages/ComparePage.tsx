import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, errorMessage, type Scenario } from '../api';
import { Alert, Badge, EmptyState, PageTitle, Spinner, Stamp } from '../components/ui';
import { formatCompact, formatMoney, formatMoneyShort, formatRate, formatTerm, toNumber } from '../lib/format';

type Row = {
  label: string;
  value: (s: Scenario) => string;
  /** чем меньше, тем лучше — подсвечиваем победителя */
  score?: (s: Scenario) => number;
};

const ROWS: Row[] = [
  { label: 'Стоимость жилья', value: (s) => formatMoneyShort(s.params.price) },
  { label: 'Взнос', value: (s) => formatMoneyShort(s.params.downPayment) },
  { label: 'Ставка', value: (s) => formatRate(s.params.annualRate), score: (s) => toNumber(s.params.annualRate) },
  { label: 'Тип', value: (s) => (s.params.type === 'ANNUITY' ? 'Аннуитетный' : 'Дифференцированный') },
  { label: 'Срок', value: (s) => formatTerm(s.summary.termMonths), score: (s) => s.summary.termMonths },
  { label: 'Ежемесячный платёж', value: (s) => formatMoney(s.summary.monthlyPayment), score: (s) => toNumber(s.summary.monthlyPayment) },
  { label: 'Переплата', value: (s) => formatMoneyShort(s.summary.overpayment), score: (s) => toNumber(s.summary.overpayment) },
  { label: 'Всего заплатите', value: (s) => formatMoneyShort(s.summary.totalPaid), score: (s) => toNumber(s.summary.totalPaid) },
  { label: 'Досрочные погашения', value: (s) => (s.params.earlyRepayments.length ? `${s.params.earlyRepayments.length} шт.` : '—') },
];

export function ComparePage() {
  const [params] = useSearchParams();
  const ids = useMemo(
    () => (params.get('ids') ?? '').split(',').map(Number).filter((n) => Number.isInteger(n) && n > 0).slice(0, 3),
    [params],
  );
  const [scenarios, setScenarios] = useState<Scenario[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (ids.length < 2) {
      setScenarios([]);
      return;
    }
    api.compareScenarios(ids).then(setScenarios).catch((err) => setError(errorMessage(err)));
  }, [ids]);

  if (error) return <Alert>{error}</Alert>;
  if (scenarios === null) return <div className="grid place-items-center py-24 text-money"><Spinner className="h-8 w-8" /></div>;
  if (scenarios.length < 2) {
    return (
      <>
        <PageTitle eyebrow="Сравнение" title="Сравнить сценарии" />
        <EmptyState
          emoji="⚖️"
          title="Нужно хотя бы два сценария"
          text="Отметьте 2–3 сохранённых сценария в кабинете — и мы честно покажем, какой из них меньше радует банк."
          action={<Link to="/scenarios" className="rounded-xl bg-money px-4 py-2.5 text-sm font-semibold text-white">К сценариям</Link>}
        />
      </>
    );
  }

  const cheapest = scenarios.reduce((best, s) => (toNumber(s.summary.overpayment) < toNumber(best.summary.overpayment) ? s : best));
  const chartData = scenarios.map((s) => ({
    name: s.name,
    principal: toNumber(s.params.price) - toNumber(s.params.downPayment),
    interest: toNumber(s.summary.overpayment),
  }));

  return (
    <div className="space-y-6">
      <PageTitle eyebrow="Сравнение" title="Кто переплатит меньше" />

      <div className="card overflow-x-auto p-0">
        <table className="w-full min-w-[640px] text-sm">
          <thead>
            <tr className="border-b border-line">
              <th className="w-48 p-4" />
              {scenarios.map((s) => (
                <th key={s.id} className="relative p-4 text-left align-top">
                  <span className="block font-display text-base font-bold">{s.name}</span>
                  {s.id === cheapest.id && <Stamp tone="money" className="mt-2">Меньше всех банку</Stamp>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {ROWS.map((row) => {
              const scores = row.score ? scenarios.map(row.score) : null;
              const best = scores ? Math.min(...scores) : null;
              const allEqual = scores ? scores.every((x) => x === scores[0]) : true;
              return (
                <tr key={row.label} className="border-b border-line/60 last:border-0">
                  <th scope="row" className="p-4 text-left font-medium text-ink-2">{row.label}</th>
                  {scenarios.map((s, i) => (
                    <td key={s.id} className="num p-4">
                      {row.value(s)}
                      {scores && !allEqual && scores[i] === best && <span className="ml-2"><Badge tone="money">лучше</Badge></span>}
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="card p-5 sm:p-6">
        <h2 className="mb-4 font-display text-lg font-bold">Тело кредита и проценты</h2>
        <div className="h-72" role="img" aria-label="Сравнение суммы кредита и переплаты по сценариям">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={chartData} layout="vertical" margin={{ left: 8, right: 16 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--color-line)" horizontal={false} />
              <XAxis type="number" tickFormatter={formatCompact} tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'var(--color-ink-3)' }} />
              <YAxis type="category" dataKey="name" width={130} tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'var(--color-ink-2)' }} />
              <Tooltip
                cursor={{ fill: 'rgb(22 33 27 / 0.05)' }}
                contentStyle={{ borderRadius: 12, border: '1px solid var(--color-line)', background: 'var(--color-card)', fontSize: 13 }}
                formatter={(value, name) => [formatMoneyShort(Number(value)), name === 'principal' ? 'Кредит' : 'Переплата']}
              />
              <Bar dataKey="principal" stackId="a" fill="var(--color-money)" />
              <Bar dataKey="interest" stackId="a" fill="var(--color-stamp)" radius={[0, 6, 6, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  );
}
