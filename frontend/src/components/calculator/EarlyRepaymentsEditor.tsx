import type { EarlyRepayment, RepaymentMode } from '../../api';
import { formatMoneyShort, parseMoneyInput } from '../../lib/format';
import { Button, Segmented, TextInput } from '../ui';

const modeOptions: Array<{ value: RepaymentMode; label: string; hint: string }> = [
  { value: 'REDUCE_TERM', label: 'Сократить срок', hint: 'Платёж прежний, кредит заканчивается раньше. Обычно выгоднее.' },
  { value: 'REDUCE_PAYMENT', label: 'Уменьшить платёж', hint: 'Срок прежний, ежемесячный платёж меньше.' },
];

function NumberBox({
  label,
  value,
  onChange,
  min = 1,
  max,
  suffix,
}: {
  label: string;
  value: number | undefined;
  onChange: (v: number | undefined) => void;
  min?: number;
  max?: number;
  suffix?: string;
}) {
  return (
    <label className="block space-y-1">
      <span className="text-xs text-ink-3">{label}</span>
      <TextInput
        type="number"
        inputMode="numeric"
        min={min}
        max={max}
        value={value ?? ''}
        suffix={suffix}
        onChange={(e) => onChange(e.target.value === '' ? undefined : Math.max(min, Math.floor(Number(e.target.value))))}
      />
    </label>
  );
}

export function EarlyRepaymentsEditor({
  value,
  onChange,
  termMonths,
}: {
  value: EarlyRepayment[];
  onChange: (next: EarlyRepayment[]) => void;
  termMonths: number;
}) {
  const update = (index: number, patch: Partial<EarlyRepayment>) =>
    onChange(value.map((item, i) => (i === index ? ({ ...item, ...patch } as EarlyRepayment) : item)));
  const remove = (index: number) => onChange(value.filter((_, i) => i !== index));

  return (
    <div className="space-y-3">
      {value.length === 0 && (
        <p className="text-sm text-ink-3">
          Добавьте досрочное погашение и посмотрите, сколько денег останется у вас, а не у банка.
        </p>
      )}

      {value.map((item, index) => (
        <div key={index} className="space-y-3 rounded-xl border border-line bg-paper/60 p-3.5">
          <div className="flex items-center justify-between gap-2">
            <span className="text-sm font-semibold">
              {item.type === 'ONE_TIME' ? 'Разовое погашение' : 'Регулярное погашение'}
              {Number(item.amount) > 0 && <span className="num ml-2 font-normal text-ink-3">{formatMoneyShort(item.amount)}</span>}
            </span>
            <button
              type="button"
              onClick={() => remove(index)}
              className="rounded-lg px-2 py-1 text-xs text-ink-3 hover:bg-stamp-soft hover:text-stamp"
              aria-label="Удалить погашение"
            >
              Удалить
            </button>
          </div>

          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <label className="col-span-2 block space-y-1">
              <span className="text-xs text-ink-3">Сумма</span>
              <TextInput
                inputMode="decimal"
                value={item.amount}
                suffix="BYN"
                onChange={(e) => update(index, { amount: parseMoneyInput(e.target.value) })}
              />
            </label>
            {item.type === 'ONE_TIME' ? (
              <NumberBox
                label="В месяце №"
                value={item.month}
                max={termMonths}
                onChange={(v) => update(index, { month: v ?? 1 })}
              />
            ) : (
              <>
                <NumberBox label="С месяца №" value={item.startMonth} max={termMonths} onChange={(v) => update(index, { startMonth: v ?? 1 })} />
                <NumberBox label="Каждые, мес." value={item.everyMonths} max={120} onChange={(v) => update(index, { everyMonths: v ?? 1 })} />
                <NumberBox label="До месяца №" value={item.untilMonth} max={termMonths} onChange={(v) => update(index, { untilMonth: v })} />
              </>
            )}
          </div>

          <Segmented label="Что делать с погашением" value={item.mode} options={modeOptions} onChange={(mode) => update(index, { mode })} />
        </div>
      ))}

      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={() => onChange([...value, { type: 'ONE_TIME', month: 12, amount: '10000', mode: 'REDUCE_TERM' }])}
        >
          + Разовое
        </Button>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={() =>
            onChange([...value, { type: 'REGULAR', startMonth: 1, everyMonths: 1, amount: '200', mode: 'REDUCE_TERM' }])
          }
        >
          + Регулярное
        </Button>
      </div>
    </div>
  );
}
