import { useMemo, useState } from 'react';
import type { ScheduleRow } from '../../api';
import { formatMoney, toNumber } from '../../lib/format';
import { Button, cx } from '../ui';

function toCsv(schedule: ScheduleRow[]): string {
  const header = ['Месяц', 'Дата', 'Платёж', 'Основной долг', 'Проценты', 'Досрочно', 'Остаток'];
  const lines = schedule.map((r) =>
    [r.month, r.date ?? '', r.payment, r.principal, r.interest, r.earlyRepayment, r.balance].join(';'),
  );
  return '﻿' + [header.join(';'), ...lines].join('\n');
}

function downloadCsv(schedule: ScheduleRow[]) {
  const blob = new Blob([toCsv(schedule)], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'grafik-platezhey.csv';
  a.click();
  URL.revokeObjectURL(url);
}

const monthName = (iso?: string) =>
  iso ? new Date(iso).toLocaleDateString('ru-RU', { month: 'short', year: 'numeric' }) : '';

export function ScheduleTable({ schedule }: { schedule: ScheduleRow[] }) {
  const years = useMemo(() => {
    const groups: ScheduleRow[][] = [];
    schedule.forEach((row, i) => (groups[Math.floor(i / 12)] ??= []).push(row));
    return groups;
  }, [schedule]);
  const [open, setOpen] = useState<Set<number>>(() => new Set([0]));

  const toggle = (index: number) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-ink-3">{schedule.length} платежей. Каждый — с копейками, без «примерно».</p>
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => setOpen(new Set(years.map((_, i) => i)))}>Развернуть всё</Button>
          <Button variant="secondary" size="sm" onClick={() => downloadCsv(schedule)}>Скачать CSV</Button>
        </div>
      </div>
      <div className="overflow-x-auto rounded-xl border border-line">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-paper-2/70 text-left text-xs uppercase tracking-wider text-ink-3">
            <tr>
              <th className="px-3 py-2.5 font-semibold">Месяц</th>
              <th className="px-3 py-2.5 text-right font-semibold">Платёж</th>
              <th className="px-3 py-2.5 text-right font-semibold">Долг</th>
              <th className="px-3 py-2.5 text-right font-semibold">Проценты</th>
              <th className="px-3 py-2.5 text-right font-semibold">Досрочно</th>
              <th className="px-3 py-2.5 text-right font-semibold">Остаток</th>
            </tr>
          </thead>
          {years.map((rows, index) => {
            const interest = rows.reduce((s, r) => s + toNumber(r.interest), 0);
            const paid = rows.reduce((s, r) => s + toNumber(r.payment) + toNumber(r.earlyRepayment), 0);
            const isOpen = open.has(index);
            return (
              <tbody key={index} className="border-t border-line">
                <tr className="cursor-pointer bg-card hover:bg-paper/70" onClick={() => toggle(index)}>
                  <td className="px-3 py-2.5 font-semibold">
                    <button type="button" aria-expanded={isOpen} className="flex items-center gap-2">
                      <span className={cx('inline-block transition', isOpen && 'rotate-90')} aria-hidden="true">▸</span>
                      {index + 1}-й год
                    </button>
                  </td>
                  <td className="num px-3 py-2.5 text-right" colSpan={2}>{formatMoney(paid)}</td>
                  <td className="num px-3 py-2.5 text-right text-stamp">{formatMoney(interest)}</td>
                  <td />
                  <td className="num px-3 py-2.5 text-right text-ink-2">{formatMoney(rows[rows.length - 1].balance)}</td>
                </tr>
                {isOpen &&
                  rows.map((r) => (
                    <tr key={r.month} className="border-t border-line/60 bg-white/40">
                      <td className="px-3 py-2 text-ink-2">
                        <span className="num mr-2 text-ink">{r.month}</span>
                        <span className="text-xs text-ink-3">{monthName(r.date)}</span>
                      </td>
                      <td className="num px-3 py-2 text-right">{formatMoney(r.payment, false)}</td>
                      <td className="num px-3 py-2 text-right text-money">{formatMoney(r.principal, false)}</td>
                      <td className="num px-3 py-2 text-right text-stamp">{formatMoney(r.interest, false)}</td>
                      <td className="num px-3 py-2 text-right text-[#8a6100]">
                        {toNumber(r.earlyRepayment) > 0 ? formatMoney(r.earlyRepayment, false) : '—'}
                      </td>
                      <td className="num px-3 py-2 text-right text-ink-2">{formatMoney(r.balance, false)}</td>
                    </tr>
                  ))}
              </tbody>
            );
          })}
        </table>
      </div>
    </div>
  );
}
