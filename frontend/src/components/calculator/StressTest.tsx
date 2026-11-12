import { useEffect, useState } from 'react';
import { api, errorMessage, type CalcParams, type StressRow } from '../../api';
import { formatMoney, formatMoneyShort, formatRate, toNumber } from '../../lib/format';
import { Button } from '../ui';

const DELTAS = [1, 2, 3, 5];

/**
 * «Что если ставка вырастет» — для плавающей ставки или рефинансирования не в вашу пользу.
 * Считается по кнопке, а не на каждое изменение: /api/calc/stress входит в тот же лимит,
 * что и /api/calc (аноним — 30 запросов в минуту).
 */
export function StressTest({ params, basePayment, baseOverpayment }: { params: CalcParams; basePayment: string; baseOverpayment: string }) {
  const [rows, setRows] = useState<StressRow[] | null>(null);
  const [rowsFor, setRowsFor] = useState<CalcParams | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Параметры поменялись — старая таблица уже про другую ипотеку.
  useEffect(() => {
    if (rowsFor && rowsFor !== params) setRows(null);
  }, [params, rowsFor]);

  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      setRows(await api.stress(params, DELTAS));
      setRowsFor(params);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  if (!rows) {
    return (
      <div className="flex flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-sm text-ink-2">
          Посмотрим, во что превратится платёж, если ставка вырастет на {DELTAS.join(', ')} п.п. Банки такое умеют.
        </p>
        <Button variant="secondary" onClick={run} loading={busy}>Проверить</Button>
        {error && <p className="text-sm text-stamp">{error}</p>}
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
