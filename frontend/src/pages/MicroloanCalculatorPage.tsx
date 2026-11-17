import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { api, errorMessage, type MicroloanType, type MicroloanCalculationResult } from '../api';
import { ScheduleTable } from '../components/calculator/ScheduleTable';
import { Alert, Badge, Button, Card, EmptyState, Field, PageTitle, Segmented, Spinner, TextInput, cx } from '../components/ui';
import { formatRate, parseMoneyInput, toNumber, formatMoney, formatMoneyShort } from '../lib/format';

const STATUS_TONES: Record<string, 'money' | 'stamp' | 'gold' | 'neutral'> = {
  NEW: 'gold',
  UNDER_REVIEW: 'stamp',
  APPROVED: 'money',
  REJECTED: 'neutral',
};

function SectionTitle({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
      <h2 className="font-display text-lg font-bold">{children}</h2>
      {aside && <span className="text-sm text-ink-3">{aside}</span>}
    </div>
  );
}

export function MicroloanCalculatorPage() {
  const navigate = useNavigate();
  const [types, setTypes] = useState<MicroloanType[]>([]);
  const [selectedTypeId, setSelectedTypeId] = useState<string>('');
  const [amount, setAmount] = useState('10000');
  const [termDays, setTermDays] = useState(10);
  const [result, setResult] = useState<MicroloanCalculationResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    api.listMicroloanTypes().then(setTypes).catch(() => setTypes([]));
  }, []);

  // Auto-select first active type
  useEffect(() => {
    if (types.length > 0 && !selectedTypeId) {
      setSelectedTypeId(types[0].id);
    }
  }, [types]);

  const selectedType = types.find((t) => t.id === selectedTypeId) ?? null;

  const validateForm = () => {
    if (!selectedType) return 'Нет доступных типов займа';
    const amt = toNumber(amount);
    const days = termDays;
    if (!(amt >= toNumber(selectedType.minAmount) && amt <= toNumber(selectedType.maxAmount))) {
      return `Сумма должна быть от ${formatMoneyShort(selectedType.minAmount)} до ${formatMoneyShort(selectedType.maxAmount)}`;
    }
    if (!(days >= 1 && days <= 30)) return 'Срок — от 1 до 30 дней';
    return null;
  };

  const validation = validateForm();

  useEffect(() => {
    if (validation || !selectedType) {
      setResult(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    api
      .calculateMicroloan(selectedType.id, amount, termDays)
      .then((r) => {
        if (cancelled) return;
        setResult(r);
        setError(null);
      })
      .catch((err) => !cancelled && setError(errorMessage(err)))
      .finally(() => !cancelled && setLoading(false));
    return () => { cancelled = true; };
  }, [selectedType, amount, termDays, validation]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitError(null);
    const err = validateForm();
    if (err) return setSubmitError(err);
    if (!selectedType) return setSubmitError('Тип займа не выбран');
    try {
      // Navigate to application page with prefilled data
      navigate('/microloan/application', {
        state: { typeId: selectedType.id, amount, termDays },
      });
    } catch (err) {
      setSubmitError(errorMessage(err));
    }
  };

  const minAmount = selectedType ? toNumber(selectedType.minAmount) : 3000;
  const maxAmount = selectedType ? toNumber(selectedType.maxAmount) : 100000;

  return (
    <div className="space-y-8">
      <section className="max-w-3xl">
        <p className="eyebrow mb-3">Калькулятор микрозайма · Демо для разработчиков</p>
        <h1 className="font-display text-4xl font-extrabold leading-[1.05] tracking-tight sm:text-6xl">
          Микрозайм без <span className="relative whitespace-nowrap text-money">сюрпризов<svg aria-hidden="true" viewBox="0 0 300 12" className="absolute -bottom-1 left-0 h-3 w-full text-gold" preserveAspectRatio="none"><path d="M2 9c60-6 140-8 296-3" fill="none" stroke="currentColor" strokeWidth="5" strokeLinecap="round" /></svg></span>
        </h1>
        <p className="mt-5 max-w-2xl text-lg text-ink-2">
          Выберите тип, сумму и срок — увидите точную сумму возврата, график и платеж. Расчёт локальный, без бэкенда.
        </p>
      </section>

      {types.length === 0 && (
        <Card>
          <EmptyState
            title="Сейчас нет доступных займов"
            text="Администратор не активировал ни одного типа микрозайма. Как только появится активный тип — калькулятор заработает."
            emoji="📭"
          />
        </Card>
      )}

      {types.length > 0 && (
        <div className="grid gap-6 lg:grid-cols-12">
          <Card className="lg:col-span-5">
            <SectionTitle>Параметры</SectionTitle>
            <form onSubmit={handleSubmit} className="space-y-5">
              <Field label="Тип займа" htmlFor="ml-type">
                <Segmented
                  label="Тип займа"
                  value={selectedTypeId}
                  options={types.map((t) => ({ value: t.id, label: t.name, hint: `${formatMoneyShort(t.minAmount)}–${formatMoneyShort(t.maxAmount)} BYN, ${t.gracePeriodDays} дн. 0%, потом ${formatRate(t.dailyRatePct)}/день` }))}
                  onChange={setSelectedTypeId}
                />
                {selectedType && (
                  <p className="mt-1.5 text-xs text-ink-3">
                    Лимит: {formatMoneyShort(selectedType.minAmount)}–{formatMoneyShort(selectedType.maxAmount)} BYN · Срок: 1–30 дней · Льготный период: {selectedType.gracePeriodDays} дней · Ставка: {formatRate(selectedType.dailyRatePct)}/день
                  </p>
                )}
              </Field>

              <Field label="Сумма займа" htmlFor="ml-amount" hint={formatMoneyShort(amount)}>
                <TextInput
                  id="ml-amount"
                  inputMode="decimal"
                  value={amount}
                  suffix="BYN"
                  onChange={(e) => setAmount(parseMoneyInput(e.target.value))}
                  disabled={!selectedType}
                />
                <input
                  type="range"
                  className="range mt-3"
                  aria-label="Сумма займа, ползунок"
                  min={String(minAmount)}
                  max={String(maxAmount)}
                  step="1000"
                  value={Math.min(Math.max(toNumber(amount), minAmount), maxAmount)}
                  onChange={(e) => setAmount(e.target.value)}
                  disabled={!selectedType}
                />
              </Field>

              <Field label="Срок" htmlFor="ml-term" hint={`${termDays} дн.`}>
                <input
                  id="ml-term"
                  type="range"
                  className="range"
                  min="1"
                  max="30"
                  step="1"
                  value={termDays}
                  onChange={(e) => setTermDays(Number(e.target.value))}
                  disabled={!selectedType}
                />
                <div className="mt-1 flex justify-between text-xs text-ink-3">
                  <span>1 день</span>
                  <span>15</span>
                  <span>30 дней</span>
                </div>
              </Field>

              {validation && <Alert tone="gold">{validation}</Alert>}
              {submitError && <Alert>{submitError}</Alert>}

              <div className="flex gap-2 pt-2">
                <Button type="submit" size="lg" loading={loading} disabled={!!validation || !selectedType || loading}>
                  Оформить заявку
                </Button>
              </div>
            </form>
          </Card>

          <div className="space-y-6 lg:col-span-7">
            <Card className="lg:sticky lg:top-24">
              <SectionTitle aside={loading ? <Spinner className="h-4 w-4 text-money" /> : undefined}>Итог</SectionTitle>
              {validation ? (
                <Alert tone="gold">{validation}</Alert>
              ) : error && !result ? (
                <Alert>{error}</Alert>
              ) : result ? (
                <div className={cx('transition-opacity', loading && 'opacity-60')}>
                  <ResultSummary result={result} type={selectedType} />
                </div>
              ) : (
                <div className="grid place-items-center py-16 text-money">
                  <Spinner className="h-8 w-8" />
                </div>
              )}
            </Card>
          </div>
        </div>
      )}

      {result && !validation && types.length > 0 && (
        <Card>
          <SectionTitle>График платежей</SectionTitle>
          <MicroloanScheduleTable schedule={result.paymentSchedule} />
        </Card>
      )}
    </div>
  );
}

function ResultSummary({ result, type }: { result: MicroloanCalculationResult; type: MicroloanType | null }) {
  const loan = toNumber(result.amount);
  const over = toNumber(result.interestAmount);
  const total = toNumber(result.totalAmount);
  const interestShare = total > 0 ? Math.round((over / total) * 100) : 0;

  return (
    <div className="relative">
      <p className="eyebrow">К возврату (один платёж в конце срока)</p>
      <p className="num mt-1 text-4xl font-bold tracking-tight sm:text-5xl">{formatMoney(result.totalAmount)}</p>

      <dl className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-3">
        <div className="rounded-xl bg-stamp-soft/70 p-3.5">
          <dt className="text-xs font-medium text-stamp">Проценты</dt>
          <dd className="num mt-1 text-lg font-bold text-stamp">{formatMoneyShort(over)}</dd>
        </div>
        <div className="rounded-xl bg-paper-2/70 p-3.5">
          <dt className="text-xs font-medium text-ink-3">Сумма займа</dt>
          <dd className="num mt-1 text-lg font-bold">{formatMoneyShort(loan)}</dd>
        </div>
        <div className="col-span-2 rounded-xl bg-paper-2/70 p-3.5 sm:col-span-1">
          <dt className="text-xs font-medium text-ink-3">Срок</dt>
          <dd className="mt-1 text-lg font-bold">{result.termDays} дн.</dd>
        </div>
      </dl>

      <div className="mt-6">
        <div className="mb-2 flex justify-between text-xs text-ink-3">
          <span>Долг: <span className="num text-money">{formatMoneyShort(loan)}</span></span>
          <span>Проценты: <span className="num text-stamp">{interestShare}%</span> от возврата</span>
        </div>
        <div className="flex h-3 overflow-hidden rounded-full bg-paper-2" aria-hidden="true">
          <div className="bg-money transition-all" style={{ width: `${100 - interestShare}%` }} />
          <div className="bg-stamp transition-all" style={{ width: `${interestShare}%` }} />
        </div>
        {interestShare === 0 && (
          <p className="mt-3 text-sm text-money-2">В льготный период — процентов нет. Платите ровно ту сумму, что взяли.</p>
        )}
      </div>
    </div>
  );
}

function MicroloanScheduleTable({ schedule }: { schedule: MicroloanCalculationResult['paymentSchedule'] }) {
  const termDays = schedule[schedule.length - 1]?.day ?? 0;
  return (
    <div className="overflow-x-auto rounded-xl border border-line">
      <table className="w-full min-w-[520px] text-sm">
        <thead className="bg-paper-2/70 text-left text-xs uppercase tracking-wider text-ink-3">
          <tr>
            <th className="px-3 py-2.5 font-semibold">Срок</th>
            <th className="px-3 py-2.5 text-right font-semibold">Основной долг</th>
            <th className="px-3 py-2.5 text-right font-semibold">Проценты</th>
            <th className="px-3 py-2.5 text-right font-semibold">Итого к оплате</th>
          </tr>
        </thead>
        <tbody>
          {schedule.map((row) => (
            <tr key={row.day} className="border-t border-line/60 bg-white/40">
              <td className="px-3 py-2 font-semibold">
                {row.day === termDays
                  ? `Конец срока (${row.day}-й день)`
                  : `${row.day}-й день`}
              </td>
              <td className="num px-3 py-2 text-right text-money">{formatMoney(row.principal, false)}</td>
              <td className="num px-3 py-2 text-right text-stamp">{formatMoney(row.interest, false)}</td>
              <td className="num px-3 py-2 text-right text-ink">{formatMoney(row.total, false)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-xs text-ink-3">Платёж один — в последний день срока. Проценты начисляются только за дни после льготного периода.</p>
    </div>
  );
}