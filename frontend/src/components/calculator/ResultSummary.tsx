import type { CalcResult, PaymentType } from '../../api';
import { formatMoney, formatMoneyShort, formatTerm, pluralMonths, toNumber } from '../../lib/format';
import { Badge, Stamp } from '../ui';

/** Ироничный, но честный комментарий к переплате. */
function verdict(overpayment: number, loan: number): { stamp: string; text: string } {
  const ratio = loan > 0 ? overpayment / loan : 0;
  if (ratio === 0) return { stamp: 'Без процентов', text: 'Ставка ноль. Проверьте, нет ли подвоха в мелком шрифте.' };
  if (ratio < 0.3) return { stamp: 'Терпимо', text: 'Переплата скромная. Банк расстроится, но переживёт.' };
  if (ratio < 1) return { stamp: 'Банк доволен', text: 'Заметная часть денег уйдёт на проценты. Досрочные погашения помогут.' };
  if (ratio < 2) return { stamp: 'Вторая квартира', text: 'Переплата больше самого кредита: вы покупаете квартиру и ещё одну — банку.' };
  return { stamp: 'Это рекорд', text: 'Банк уже присматривает яхту на ваши проценты. Попробуйте срок покороче.' };
}

export function ResultSummary({ result, type }: { result: CalcResult; type: PaymentType }) {
  const loan = toNumber(result.loanAmount);
  const over = toNumber(result.overpayment);
  const total = toNumber(result.totalPaid);
  const interestShare = total > 0 ? Math.round((over / total) * 100) : 0;
  const { stamp, text } = verdict(over, loan);
  const first = result.schedule[0];
  const last = result.schedule[result.schedule.length - 1];

  return (
    <div className="relative">
      <Stamp className="absolute right-0 top-0 sm:right-2">{stamp}</Stamp>

      <p className="eyebrow">{type === 'ANNUITY' ? 'Ежемесячный платёж' : 'Первый платёж'}</p>
      <p className="num mt-1 text-4xl font-bold tracking-tight sm:text-5xl">{formatMoney(result.monthlyPayment)}</p>
      {type === 'DIFFERENTIATED' && last && first && (
        <p className="mt-1 text-sm text-ink-3">
          и дальше снижается до <span className="num text-ink-2">{formatMoney(last.payment)}</span> в последнем месяце
        </p>
      )}

      <dl className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-3">
        <div className="rounded-xl bg-stamp-soft/70 p-3.5">
          <dt className="text-xs font-medium text-stamp">Переплата банку</dt>
          <dd className="num mt-1 text-lg font-bold text-stamp">{formatMoneyShort(over)}</dd>
        </div>
        <div className="rounded-xl bg-paper-2/70 p-3.5">
          <dt className="text-xs font-medium text-ink-3">Всего заплатите</dt>
          <dd className="num mt-1 text-lg font-bold">{formatMoneyShort(total)}</dd>
        </div>
        <div className="col-span-2 rounded-xl bg-paper-2/70 p-3.5 sm:col-span-1">
          <dt className="text-xs font-medium text-ink-3">Срок</dt>
          <dd className="mt-1 text-lg font-bold">{formatTerm(result.termMonths)}</dd>
        </div>
      </dl>

      <div className="mt-6">
        <div className="mb-2 flex justify-between text-xs text-ink-3">
          <span>Долг: <span className="num text-money">{formatMoneyShort(loan)}</span></span>
          <span>Проценты: <span className="num text-stamp">{interestShare}%</span> всех платежей</span>
        </div>
        <div className="flex h-3 overflow-hidden rounded-full bg-paper-2" aria-hidden="true">
          <div className="bg-money transition-all" style={{ width: `${100 - interestShare}%` }} />
          <div className="bg-stamp transition-all" style={{ width: `${interestShare}%` }} />
        </div>
        <p className="mt-3 text-sm text-ink-2">
          Из каждых 100 BYN, что вы отдадите, <b className="num text-stamp">{interestShare}</b> — плата банку за ожидание. {text}
        </p>
      </div>

      {result.savings && toNumber(result.savings.overpaymentSaved) > 0 && (
        <div className="mt-5 flex flex-wrap items-center gap-2 rounded-xl border border-money/25 bg-money-soft/60 p-3.5 text-sm text-money-2">
          <span aria-hidden="true">💸</span>
          <span>
            Досрочные погашения экономят <b className="num">{formatMoneyShort(result.savings.overpaymentSaved)}</b>
          </span>
          {result.savings.monthsSaved > 0 && <Badge tone="money">−{pluralMonths(result.savings.monthsSaved)}</Badge>}
        </div>
      )}
    </div>
  );
}
