import { useEffect, useState } from 'react';
import { api, type CalcParams, type StressRow } from '../../api';
import { formatMoney, formatMoneyShort, formatRate, toNumber } from '../../lib/format';
import { Spinner } from '../ui';

const DELTAS = [1, 2, 3, 5];

/** «Что если ставка вырастет» — для плавающей ставки или рефинансирования не в вашу пользу. */
export function StressTest({ params, basePayment, baseOverpayment }: { params: CalcParams; basePayment: string; baseOverpayment: string }) {
  const [rows, setRows] = useState<StressRow[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setFailed(false);
    api
      .stress(params, DELTAS)
      .then((r) => !cancelled && setRows(r))
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, [params]);

  if (failed) return <p className="text-sm text-ink-3">Стресс-тест пока недоступен. Банку это на руку.</p>;
  if (!rows) {
    return (
      <div className="grid place-items-center py-8 text-money">
        <Spinner />
      </div>
    );
  }

  const base = toNumber(basePayment);
  const baseOver = toNumber(baseOverpayment);
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[480px] text-sm">
        <thead className="text-left text-xs uppercase tracking-wider text-ink-3">
          <tr>
            <th className="py-2 font-semibold">Ставка</th>
            <th className="py-2 text-right font-semibold">Платёж</th>
            <th className="py-2 text-right font-semibold">Разница в месяц</th>
            <th className="py-2 text-right font-semibold">Переплата</th>
          </tr>
        </thead>
        <tbody>
          <tr className="border-t border-line">
            <td className="py-2.5 font-medium">{formatRate(params.annualRate)} <span className="text-xs text-ink-3">сейчас</span></td>
            <td className="num py-2.5 text-right">{formatMoney(base)}</td>
            <td className="py-2.5 text-right text-ink-3">—</td>
            <td className="num py-2.5 text-right">{formatMoneyShort(baseOver)}</td>
          </tr>
          {rows.map((row) => {
            const diff = toNumber(row.monthlyPayment) - base;
            return (
              <tr key={row.delta} className="border-t border-line">
                <td className="py-2.5 font-medium">
                  {formatRate(toNumber(params.annualRate) + row.delta)} <span className="text-xs text-stamp">+{row.delta} п.п.</span>
                </td>
                <td className="num py-2.5 text-right">{formatMoney(row.monthlyPayment)}</td>
                <td className="num py-2.5 text-right text-stamp">+{formatMoney(diff)}</td>
                <td className="num py-2.5 text-right text-stamp">{formatMoneyShort(row.overpayment)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
