import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router';
import { api, errorMessage, type BankOffer, type CalcParams, type CalcResult, type EarlyRepayment, type PaymentType } from '../api';
import { EarlyRepaymentsEditor } from '../components/calculator/EarlyRepaymentsEditor';
import { PaymentChart } from '../components/calculator/PaymentChart';
import { ResultSummary } from '../components/calculator/ResultSummary';
import { SaveScenario } from '../components/calculator/SaveScenario';
import { ScheduleTable } from '../components/calculator/ScheduleTable';
import { StressTest } from '../components/calculator/StressTest';
import { Alert, Card, Field, Segmented, Spinner, TextInput, cx } from '../components/ui';
import { validateParams } from '../lib/calc';
import { formatMoneyShort, formatRate, formatTerm, parseMoneyInput, toNumber } from '../lib/format';

const DEFAULT_PARAMS: CalcParams = {
  price: '250000',
  downPayment: '50000',
  termMonths: 300,
  annualRate: '11.5',
  type: 'ANNUITY',
  earlyRepayments: [],
};

const typeOptions: Array<{ value: PaymentType; label: string; hint: string }> = [
  { value: 'ANNUITY', label: 'Аннуитетный', hint: 'Одинаковый платёж каждый месяц. Банки любят: проценты платятся в начале.' },
  { value: 'DIFFERENTIATED', label: 'Дифференцированный', hint: 'Сначала платёж больше, потом меньше. Переплата ниже.' },
];

function SectionTitle({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
      <h2 className="font-display text-lg font-bold">{children}</h2>
      {aside && <span className="text-sm text-ink-3">{aside}</span>}
    </div>
  );
}

/**
 * Задержка пересчёта, чтобы не дёргать API на каждую цифру и движение ползунка:
 * у анонима лимит 30 расчётов в минуту (app.ratelimit.calc).
 */
function useDebounced<T>(value: T, ms = 600): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

export function CalculatorPage() {
  const location = useLocation();
  const prefill = (location.state as { params?: CalcParams; offerId?: string | null } | null) ?? null;

  const [price, setPrice] = useState(prefill?.params?.price ?? DEFAULT_PARAMS.price);
  const [downPayment, setDownPayment] = useState(prefill?.params?.downPayment ?? DEFAULT_PARAMS.downPayment);
  const [termMonths, setTermMonths] = useState(prefill?.params?.termMonths ?? DEFAULT_PARAMS.termMonths);
  const [annualRate, setAnnualRate] = useState(prefill?.params?.annualRate ?? DEFAULT_PARAMS.annualRate);
  const [type, setType] = useState<PaymentType>(prefill?.params?.type ?? DEFAULT_PARAMS.type);
  const [earlyRepayments, setEarlyRepayments] = useState<EarlyRepayment[]>(prefill?.params?.earlyRepayments ?? []);
  const [offerId, setOfferId] = useState<string | null>(prefill?.offerId ?? null);

  const [offers, setOffers] = useState<BankOffer[]>([]);
  const [result, setResult] = useState<CalcResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listOffers().then(setOffers).catch(() => setOffers([]));
  }, []);

  const params = useMemo<CalcParams>(
    () => ({ price, downPayment, termMonths, annualRate, type, earlyRepayments }),
    [price, downPayment, termMonths, annualRate, type, earlyRepayments],
  );
  const debouncedParams = useDebounced(params);
  const validation = validateParams(params);

  useEffect(() => {
    if (validateParams(debouncedParams)) return;
    let cancelled = false;
    setLoading(true);
    api
      .calculate(debouncedParams)
      .then((r) => {
        if (cancelled) return;
        setResult(r);
        setError(null);
      })
      .catch((err) => !cancelled && setError(errorMessage(err)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [debouncedParams]);

  const priceNum = toNumber(price);
  const downPercent = priceNum > 0 ? (toNumber(downPayment) / priceNum) * 100 : 0;
  const selectedOffer = offers.find((o) => o.id === offerId) ?? null;
  const offerWarnings: string[] = [];
  if (selectedOffer) {
    if (downPercent < toNumber(selectedOffer.minDownPaymentPercent)) {
      offerWarnings.push(`${selectedOffer.bankName} хочет взнос от ${formatRate(selectedOffer.minDownPaymentPercent)}`);
    }
    if (termMonths > selectedOffer.maxTermMonths) {
      offerWarnings.push(`максимальный срок у банка — ${formatTerm(selectedOffer.maxTermMonths)}`);
    }
    if (annualRate !== selectedOffer.rate) {
      offerWarnings.push('ставка отличается от предложения банка');
    }
  }

  const pickOffer = (offer: BankOffer) => {
    if (offerId === offer.id) {
      setOfferId(null);
      return;
    }
    setOfferId(offer.id);
    setAnnualRate(offer.rate);
    if (termMonths > offer.maxTermMonths) setTermMonths(offer.maxTermMonths);
  };

  const years = Math.round(termMonths / 12);

  return (
    <div className="space-y-8">
      <section className="max-w-3xl">
        <p className="eyebrow mb-3">Калькулятор без регистрации и мелкого шрифта</p>
        <h1 className="font-display text-4xl font-extrabold leading-[1.05] tracking-tight sm:text-6xl">
          Ипотека без <span className="relative whitespace-nowrap text-money">сюрпризов<svg aria-hidden="true" viewBox="0 0 300 12" className="absolute -bottom-1 left-0 h-3 w-full text-gold" preserveAspectRatio="none"><path d="M2 9c60-6 140-8 296-3" fill="none" stroke="currentColor" strokeWidth="5" strokeLinecap="round" /></svg></span>
        </h1>
        <p className="mt-5 max-w-2xl text-lg text-ink-2">
          Банк уже посчитал, сколько на вас заработает. Теперь ваша очередь: платёж, настоящая переплата, полный график и что
          будет, если ставка вырастет.
        </p>
      </section>

      <div className="grid gap-6 lg:grid-cols-12">
        <Card className="lg:col-span-5">
          <SectionTitle>Параметры</SectionTitle>
          <div className="space-y-5">
            <Field label="Стоимость жилья" htmlFor="price" hint={formatMoneyShort(price)}>
              <TextInput id="price" inputMode="decimal" value={price} suffix="BYN" onChange={(e) => setPrice(parseMoneyInput(e.target.value))} />
              <input
                type="range"
                className="range mt-3"
                aria-label="Стоимость жилья, ползунок"
                min={20000}
                max={1500000}
                step={5000}
                value={Math.min(Math.max(priceNum, 20000), 1500000)}
                onChange={(e) => setPrice(e.target.value)}
              />
            </Field>

            <Field label="Первоначальный взнос" htmlFor="down" hint={`${downPercent.toFixed(1)} % от цены`}>
              <TextInput id="down" inputMode="decimal" value={downPayment} suffix="BYN" onChange={(e) => setDownPayment(parseMoneyInput(e.target.value))} />
              <div className="mt-2 flex flex-wrap gap-1.5">
                {[10, 20, 30, 50].map((p) => (
                  <button
                    key={p}
                    type="button"
                    onClick={() => setDownPayment(String(Math.round((priceNum * p) / 100)))}
                    className={cx(
                      'rounded-lg border px-2.5 py-1 text-xs font-medium transition',
                      Math.abs(downPercent - p) < 0.05 ? 'border-money bg-money-soft text-money-2' : 'border-line text-ink-2 hover:border-ink-3',
                    )}
                  >
                    {p} %
                  </button>
                ))}
              </div>
            </Field>

            <Field label="Срок" htmlFor="term" hint={formatTerm(termMonths)}>
              <input
                id="term"
                type="range"
                className="range"
                min={1}
                max={30}
                step={1}
                value={Math.max(1, years)}
                onChange={(e) => setTermMonths(Number(e.target.value) * 12)}
              />
              <div className="mt-1 flex justify-between text-xs text-ink-3">
                <span>1 год</span>
                <span>15</span>
                <span>30 лет</span>
              </div>
            </Field>

            <Field label="Ставка, % годовых" htmlFor="rate">
              <TextInput
                id="rate"
                inputMode="decimal"
                value={annualRate}
                suffix="%"
                onChange={(e) => setAnnualRate(parseMoneyInput(e.target.value))}
              />
              {offers.length > 0 && (
                <div className="mt-2">
                  <p className="mb-1.5 text-xs text-ink-3">или возьмите ставку из предложений банков:</p>
                  <div className="flex flex-wrap gap-1.5">
                    {offers.map((offer) => (
                      <button
                        key={offer.id}
                        type="button"
                        aria-pressed={offerId === offer.id}
                        onClick={() => pickOffer(offer)}
                        className={cx(
                          'rounded-lg border px-2.5 py-1.5 text-left text-xs transition',
                          offerId === offer.id ? 'border-money bg-money-soft text-money-2' : 'border-line bg-white/60 text-ink-2 hover:border-ink-3',
                        )}
                      >
                        <span className="block font-semibold">{offer.bankName}</span>
                        <span className="num">{formatRate(offer.rate)}</span>
                        <span className="text-ink-3"> · взнос от {formatRate(offer.minDownPaymentPercent)}</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}
              {offerWarnings.length > 0 && <p className="mt-2 text-xs text-[#8a6100]">Внимание: {offerWarnings.join('; ')}.</p>}
            </Field>

            <Field label="Тип платежа">
              <Segmented label="Тип платежа" value={type} options={typeOptions} onChange={setType} />
              <p className="mt-1.5 text-xs text-ink-3">{typeOptions.find((o) => o.value === type)?.hint}</p>
            </Field>

            <details className="group rounded-xl border border-line bg-white/40 p-3.5" open={earlyRepayments.length > 0}>
              <summary className="flex cursor-pointer list-none items-center justify-between text-sm font-semibold">
                <span>Досрочные погашения {earlyRepayments.length > 0 && <span className="text-ink-3">({earlyRepayments.length})</span>}</span>
                <span className="text-ink-3 transition group-open:rotate-180" aria-hidden="true">▾</span>
              </summary>
              <div className="mt-3">
                <EarlyRepaymentsEditor value={earlyRepayments} onChange={setEarlyRepayments} termMonths={termMonths} />
              </div>
            </details>
          </div>
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
                {error && <div className="mb-4"><Alert>{error} Показан предыдущий расчёт.</Alert></div>}
                <ResultSummary result={result} type={debouncedParams.type} />
                <div className="mt-6 border-t border-dashed border-line pt-5">
                  <SaveScenario params={debouncedParams} offerId={offerId} />
                </div>
              </div>
            ) : (
              <div className="grid place-items-center py-16 text-money">
                <Spinner className="h-8 w-8" />
              </div>
            )}
          </Card>
        </div>
      </div>

      {result && !validation && (
        <>
          <Card>
            <SectionTitle aside="зелёное — ваше, красное — банку">Куда уходят деньги</SectionTitle>
            <PaymentChart schedule={result.schedule} />
          </Card>

          <Card>
            <SectionTitle aside="на случай плавающей ставки">Что если ставка вырастет</SectionTitle>
            <StressTest params={debouncedParams} basePayment={result.monthlyPayment} baseOverpayment={result.overpayment} />
          </Card>

          <Card>
            <SectionTitle>График платежей</SectionTitle>
            <ScheduleTable schedule={result.schedule} />
          </Card>
        </>
      )}
    </div>
  );
}
