import { useMemo } from 'react';
import {
  Bar, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import type { ScheduleRow } from '../../api';
import { formatCompact, formatMoneyShort, toNumber } from '../../lib/format';

interface YearPoint {
  year: string;
  principal: number;
  interest: number;
  early: number;
  balance: number;
}

/** Агрегация графика по годам: сколько ушло в долг, сколько банку, сколько досрочно. */
export function groupByYear(schedule: ScheduleRow[]): YearPoint[] {
  const years: YearPoint[] = [];
  schedule.forEach((row, i) => {
    const index = Math.floor(i / 12);
    years[index] ??= { year: `${index + 1}`, principal: 0, interest: 0, early: 0, balance: 0 };
    const point = years[index];
    point.principal += toNumber(row.principal);
    point.interest += toNumber(row.interest);
    point.early += toNumber(row.earlyRepayment);
    point.balance = toNumber(row.balance);
  });
  return years;
}

const COLORS = {
  principal: 'var(--color-money)',
  interest: 'var(--color-stamp)',
  early: 'var(--color-gold)',
  balance: 'var(--color-ink)',
};

type Radius = [number, number, number, number];
const FLAT: Radius = [0, 0, 0, 0];
const TOP: Radius = [4, 4, 0, 0];

const LABELS: Record<string, string> = {
  principal: 'В счёт долга',
  interest: 'Проценты банку',
  early: 'Досрочно',
  balance: 'Остаток долга',
};

export function PaymentChart({ schedule }: { schedule: ScheduleRow[] }) {
  const data = useMemo(() => groupByYear(schedule), [schedule]);
  const hasEarly = data.some((d) => d.early > 0);

  return (
    <div className="h-80 w-full" role="img" aria-label="График платежей по годам: долг, проценты и остаток">
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--color-line)" vertical={false} />
          <XAxis dataKey="year" tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'var(--color-ink-3)' }} label={{ value: 'год', position: 'insideBottomRight', offset: -2, fontSize: 11, fill: 'var(--color-ink-3)' }} />
          <YAxis yAxisId="pay" tickFormatter={formatCompact} tickLine={false} axisLine={false} width={48} tick={{ fontSize: 12, fill: 'var(--color-ink-3)' }} />
          <YAxis yAxisId="bal" orientation="right" tickFormatter={formatCompact} tickLine={false} axisLine={false} width={48} tick={{ fontSize: 12, fill: 'var(--color-ink-3)' }} />
          <Tooltip
            cursor={{ fill: 'rgb(22 33 27 / 0.05)' }}
            contentStyle={{ borderRadius: 12, border: '1px solid var(--color-line)', background: 'var(--color-card)', fontSize: 13 }}
            labelFormatter={(year) => `${year}-й год`}
            formatter={(value, name) => [formatMoneyShort(Number(value)), LABELS[String(name)] ?? String(name)]}
          />
          <Legend formatter={(name) => LABELS[String(name)] ?? String(name)} iconType="circle" wrapperStyle={{ fontSize: 12 }} />
          <Bar yAxisId="pay" dataKey="principal" stackId="pay" fill={COLORS.principal} radius={FLAT} />
          <Bar yAxisId="pay" dataKey="interest" stackId="pay" fill={COLORS.interest} radius={hasEarly ? FLAT : TOP} />
          {hasEarly && <Bar yAxisId="pay" dataKey="early" stackId="pay" fill={COLORS.early} radius={TOP} />}
          <Line yAxisId="bal" type="monotone" dataKey="balance" stroke={COLORS.balance} strokeWidth={2} dot={false} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}
