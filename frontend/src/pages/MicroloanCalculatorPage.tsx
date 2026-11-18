import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { api, errorMessage, type MicroloanCalculationResult, type MicroloanType } from '../api';
import { Alert, AnimatedNumber, Button, Card, EmptyState, Field, Select, Skeleton, Spinner, TextInput } from '../components/ui';
import { formatMoney, formatMoneyShort, formatRate, parseMoneyInput, toNumber } from '../lib/format';

/** Пауза перед запросом к /api/loans/quote: пока ползунок тянут, на бэк не ходим. */
const QUOTE_DEBOUNCE_MS = 300;

function SectionTitle({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
      <h2 className="font-display text-lg font-bold">{children}</h2>
      {aside && <span className="text-sm text-ink-3">{aside}</span>}
    </div>
  );
}

/**
 * Мгновенный предпросмотр по той же формуле, что LoanQuoteCalculator на бэке:
 * interest = round(amount × daily_rate / 100 × term_days, 2), total = amount + interest + modifier.
 * Нужен, чтобы итог менялся вместе с ползунком без ожидания сети. Источник истины — ответ /quote,
 * он заменяет предпросмотр, как только приходит.
 */
function previewQuote(type: MicroloanType, amount: number, termDays: number): MicroloanCalculationResult {
  const interest = Math.round(amount * (toNumber(type.dailyRatePct) / 100) * termDays * 100) / 100;
  const modifier = toNumber(type.modifier);
  return {
    amount: amount.toFixed(2),
    termDays,
    dailyRatePct: type.dailyRatePct,
    interestAmount: interest.toFixed(2),
    modifier: modifier.toFixed(2),
    totalAmount: (amount + interest + modifier).toFixed(2),
  };
}

const clamp = (v: number, min: number, max: number) => Math.min(Math.max(v, min), max);

export function MicroloanCalculatorPage() {
  const navigate = useNavigate();
  const [types, setTypes] = useState<MicroloanType[] | null>(null);
  const [typesError, setTypesError] = useState<string | null>(null);
  const [selectedTypeId, setSelectedTypeId] = useState('');
  const [amount, setAmount] = useState('');
  const [termDays, setTermDays] = useState(0);
  const [serverQuote, setServerQuote] = useState<MicroloanCalculationResult | null>(null);
  const [quoting, setQuoting] = useState(false);
  const [quoteError, setQuoteError] = useState<string | null>(null);

  useEffect(() => {
    api
      .listMicroloanTypes()
      .then(setTypes)
      .catch((err) => {
        setTypes([]);
        setTypesError(errorMessage(err));
      });
  }, []);

  const selectedType = types?.find((t) => t.id === selectedTypeId) ?? null;

  // При выборе типа ставим сумму и срок в его лимиты (середина диапазона срока).
  const selectType = (type: MicroloanType) => {
    setSelectedTypeId(type.id);
    setAmount(String(Math.round(toNumber(type.minAmount))));
    setTermDays(Math.round((type.minTermDays + type.maxTermDays) / 2));
    setServerQuote(null);
  };

  useEffect(() => {
    if (types && types.length > 0 && !selectedTypeId) selectType(types[0]);
  }, [types, selectedTypeId]);

  const minAmount = selectedType ? toNumber(selectedType.minAmount) : 0;
  const maxAmount = selectedType ? toNumber(selectedType.maxAmount) : 0;
  const amountNum = toNumber(amount);

  const validation = useMemo(() => {
    if (!selectedType) return null;
    if (!(amountNum >= minAmount && amountNum <= maxAmount)) {
      return `Сумма — от ${formatMoneyShort(minAmount)} до ${formatMoneyShort(maxAmount)}`;
    }
    return null;
  }, [selectedType, amountNum, minAmount, maxAmount]);

  const preview = useMemo(
    () => (selectedType && !validation ? previewQuote(selectedType, amountNum, termDays) : null),
    [selectedType, validation, amountNum, termDays],
  );

  // Запрос к бэку с паузой; устаревшие ответы отбрасываем по номеру запроса.
  const requestSeq = useRef(0);
  useEffect(() => {
    if (!selectedType || validation) return;
    const seq = ++requestSeq.current;
    const timer = setTimeout(() => {
      setQuoting(true);
      api
        .calculateMicroloan(selectedType.id, amountNum.toFixed(2), termDays)
        .then((r) => {
          if (seq !== requestSeq.current) return;
          setServerQuote(r);
          setQuoteError(null);
        })
        .catch((err) => seq === requestSeq.current && setQuoteError(errorMessage(err)))
        .finally(() => seq === requestSeq.current && setQuoting(false));
    }, QUOTE_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [selectedType, validation, amountNum, termDays]);

  // Ответ бэка показываем, только если он про текущие параметры; иначе — предпросмотр.
  const shown =
    serverQuote && preview && serverQuote.termDays === preview.termDays && toNumber(serverQuote.amount) === amountNum
      ? serverQuote
      : preview;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedType || validation) return;
    navigate('/microloan/application', {
      state: { typeId: selectedType.id, amount: amountNum.toFixed(2), termDays },
    });
  };

  return (
    <div className="space-y-8">
      <section className="reveal max-w-3xl">
        <p className="eyebrow mb-3">Калькулятор микрозайма</p>
        <h1 className="font-display text-4xl font-extrabold leading-[1.05] tracking-tight sm:text-6xl">
          Микрозайм без <span className="relative whitespace-nowrap text-money">сюрпризов<svg aria-hidden="true" viewBox="0 0 300 12" className="absolute -bottom-1 left-0 h-3 w-full text-gold" preserveAspectRatio="none"><path d="M2 9c60-6 140-8 296-3" fill="none" stroke="currentColor" strokeWidth="5" strokeLinecap="round" /></svg></span>
        </h1>
        <p className="mt-5 max-w-2xl text-lg text-ink-2">
          Выберите тип, сумму и срок — увидите точную сумму к возврату одним платежом в конце срока.
        </p>
      </section>

      {types === null && (
        <div className="grid gap-6 lg:grid-cols-12" aria-hidden="true">
          <Skeleton className="h-96 lg:col-span-5" />
          <Skeleton className="h-96 lg:col-span-7" />
        </div>
      )}

      {types !== null && types.length === 0 && (
        <Card>
          {typesError ? (
            <Alert>{typesError}</Alert>
          ) : (
            <EmptyState title="Сейчас нет доступных займов" text="Активных типов микрозайма пока нет." emoji="📭" />
          )}
        </Card>
      )}

      {selectedType && types && (
        <div className="grid gap-6 lg:grid-cols-12">
          <Card className="reveal lg:col-span-5" style={{ '--reveal-index': 1 } as CSSProperties}>
            <SectionTitle>Параметры</SectionTitle>
            <form onSubmit={handleSubmit} className="space-y-5">
              <Field label="Тип займа" htmlFor="ml-type">
                <Select
                  value={selectedTypeId}
                  options={types.map((t) => ({ value: t.id, label: t.name }))}
                  onChange={(id) => {
                    const type = types.find((t) => t.id === id);
                    if (type) selectType(type);
                  }}
                />
                {selectedType.description && <p className="mt-1.5 text-sm text-ink-2">{selectedType.description}</p>}
                <p className="mt-1.5 text-xs text-ink-3">
                  Лимит: {formatMoneyShort(selectedType.minAmount)}–{formatMoneyShort(selectedType.maxAmount)} · Срок:{' '}
                  {selectedType.minTermDays}–{selectedType.maxTermDays} дн. · Ставка: {formatRate(selectedType.dailyRatePct)} в день
                  {toNumber(selectedType.modifier) > 0 && <> · Надбавка: {formatMoneyShort(selectedType.modifier)}</>}
                </p>
              </Field>

              <Field label="Сумма займа" htmlFor="ml-amount">
                <TextInput
                  id="ml-amount"
                  inputMode="decimal"
                  value={amount}
                  suffix="BYN"
                  onChange={(e) => setAmount(parseMoneyInput(e.target.value))}
                />
                {/* step=1: ползунок двигается плавно по всей длине, а не скачками по 1000. */}
                <input
                  type="range"
                  className="range mt-3"
                  aria-label="Сумма займа, ползунок"
                  min={minAmount}
                  max={maxAmount}
                  step={1}
                  value={clamp(Math.round(amountNum), minAmount, maxAmount)}
                  onChange={(e) => setAmount(e.target.value)}
                />
              </Field>

              <Field label="Срок" htmlFor="ml-term" hint={`${termDays} дн.`}>
                <input
                  id="ml-term"
                  type="range"
                  className="range"
                  min={selectedType.minTermDays}
                  max={selectedType.maxTermDays}
                  step={1}
                  value={termDays}
                  onChange={(e) => setTermDays(Number(e.target.value))}
                />
                <div className="mt-1 flex justify-between text-xs text-ink-3">
                  <span>{selectedType.minTermDays} дн.</span>
                  <span>{selectedType.maxTermDays} дн.</span>
                </div>
              </Field>

              {validation && <Alert tone="gold">{validation}</Alert>}

              <div className="flex gap-2 pt-2">
                <Button type="submit" size="lg" disabled={!!validation}>
                  Оформить заявку
                </Button>
              </div>
            </form>
          </Card>

          <div className="space-y-6 lg:col-span-7">
            <Card className="reveal lg:sticky lg:top-24" style={{ '--reveal-index': 2 } as CSSProperties}>
              <SectionTitle aside={quoting ? <Spinner className="h-4 w-4 text-money" /> : undefined}>Итог</SectionTitle>
              {shown ? <ResultSummary result={shown} /> : <Alert tone="gold">{validation}</Alert>}
              {quoteError && <Alert className="mt-4">{quoteError}</Alert>}
            </Card>
          </div>
        </div>
      )}
    </div>
  );
}

function ResultSummary({ result }: { result: MicroloanCalculationResult }) {
  const loan = toNumber(result.amount);
  const interest = toNumber(result.interestAmount);
  const modifier = toNumber(result.modifier);
  const total = toNumber(result.totalAmount);
  const costShare = total > 0 ? Math.round(((interest + modifier) / total) * 100) : 0;

  return (
    <div>
      <p className="eyebrow">К возврату (один платёж в конце срока)</p>
      <p className="mt-1 text-4xl font-bold tracking-tight sm:text-5xl">
        <AnimatedNumber value={total} format={(n) => formatMoney(n)} />
      </p>

      <dl className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
        <div className="rounded-xl bg-paper-2/70 p-3.5">
          <dt className="text-xs font-medium text-ink-3">Сумма займа</dt>
          <dd className="mt-1 text-lg font-bold">
            <AnimatedNumber value={loan} format={(n) => formatMoneyShort(n)} />
          </dd>
        </div>
        <div className="rounded-xl bg-stamp-soft/70 p-3.5">
          <dt className="text-xs font-medium text-stamp">Проценты</dt>
          <dd className="mt-1 text-lg font-bold text-stamp">
            <AnimatedNumber value={interest} format={(n) => formatMoneyShort(n)} />
          </dd>
        </div>
        <div className="rounded-xl bg-paper-2/70 p-3.5">
          <dt className="text-xs font-medium text-ink-3">Надбавка типа</dt>
          <dd className="mt-1 text-lg font-bold">
            <AnimatedNumber value={modifier} format={(n) => formatMoneyShort(n)} />
          </dd>
        </div>
        <div className="rounded-xl bg-paper-2/70 p-3.5">
          <dt className="text-xs font-medium text-ink-3">Срок</dt>
          <dd className="mt-1 text-lg font-bold">{result.termDays} дн.</dd>
        </div>
      </dl>

      <div className="mt-6">
        <div className="mb-2 flex justify-between text-xs text-ink-3">
          <span>Долг: <span className="num text-money">{formatMoneyShort(loan)}</span></span>
          <span>Переплата: <span className="num text-stamp">{costShare}%</span> от возврата</span>
        </div>
        <div className="flex h-3 overflow-hidden rounded-full bg-paper-2" aria-hidden="true">
          <div className="bg-money transition-all" style={{ width: `${100 - costShare}%` }} />
          <div className="bg-stamp transition-all" style={{ width: `${costShare}%` }} />
        </div>
      </div>
    </div>
  );
}
