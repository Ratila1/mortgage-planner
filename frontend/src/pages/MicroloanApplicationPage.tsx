import { useEffect, useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { api, errorMessage, type MicroloanType, type MicroloanCalculationResult, type ApplicantProfile } from '../api';
import { Alert, Badge, Button, Card, Field, PageTitle, Spinner, TextInput, cx, FileInput } from '../components/ui';
import { parseMoneyInput, toNumber, formatMoney, formatMoneyShort } from '../lib/format';

interface PrefillState {
  typeId?: string;
  amount?: string;
  termDays?: number;
}

export function MicroloanApplicationPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const prefill = (location.state as PrefillState | null) ?? null;

  const [types, setTypes] = useState<MicroloanType[]>([]);
  const [selectedType, setSelectedType] = useState<MicroloanType | null>(null);
  const [calculation, setCalculation] = useState<MicroloanCalculationResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const [profile, setProfile] = useState<ApplicantProfile>({ fullName: '', phone: '', email: '' });
  const [file, setFile] = useState<File | null>(null);
  const [fileName, setFileName] = useState<string>('');

  useEffect(() => {
    api.listMicroloanTypes().then(setTypes).catch(() => setTypes([]));
  }, []);

  // Initialize from prefill or first active type
  useEffect(() => {
    if (prefill?.typeId) {
      const t = types.find((x) => x.id === prefill.typeId);
      if (t) {
        setSelectedType(t);
        setAmount(prefill.amount ?? '10000');
        setTermDays(prefill.termDays ?? 10);
      }
    } else if (types.length > 0 && !selectedType) {
      setSelectedType(types[0]);
      setAmount('10000');
      setTermDays(10);
    }
  }, [types, prefill]);

  const [amount, setAmount] = useState(prefill?.amount ?? '10000');
  const [termDays, setTermDays] = useState(prefill?.termDays ?? 10);

  // Calculate when params change
  useEffect(() => {
    if (!selectedType) return;
    let cancelled = false;
    setLoading(true);
    api
      .calculateMicroloan(selectedType.id, amount, termDays)
      .then((r) => {
        if (cancelled) return;
        setCalculation(r);
        setError(null);
      })
      .catch((err) => !cancelled && setError(errorMessage(err)))
      .finally(() => !cancelled && setLoading(false));
    return () => { cancelled = true; };
  }, [selectedType, amount, termDays]);

  const validateForm = () => {
    if (!selectedType) return 'Тип займа не выбран';
    const amt = toNumber(amount);
    const days = termDays;
    if (!(amt >= toNumber(selectedType.minAmount) && amt <= toNumber(selectedType.maxAmount))) {
      return `Сумма должна быть от ${selectedType.minAmount} до ${selectedType.maxAmount} BYN`;
    }
    if (!(days >= 1 && days <= 30)) return 'Срок — от 1 до 30 дней';
    if (!profile.fullName.trim()) return 'Укажите ФИО';
    if (!profile.phone.trim()) return 'Укажите телефон';
    if (!profile.email.trim()) return 'Укажите email';
    if (!file) return 'Выберите файл (обязательно)';
    return null;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitError(null);
    const err = validateForm();
    if (err) return setSubmitError(err);
    if (!selectedType) return setSubmitError('Тип займа не выбран');

    setSubmitting(true);
    try {
      await api.createMicroloanApplication({
        typeId: selectedType.id,
        amount,
        termDays,
        profile,
      });
      navigate('/microloan/applications', { replace: true });
    } catch (err) {
      setSubmitError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="max-w-2xl space-y-8">
      <section>
        <p className="eyebrow mb-3">Подача заявки · Демо для разработчиков</p>
        <h1 className="font-display text-4xl font-extrabold leading-[1.05] tracking-tight sm:text-5xl">
          Оформить заявку на микрозайм
        </h1>
        <p className="mt-3 text-lg text-ink-2">
          Заполните данные, выберите файл (демо-шаг) и отправьте заявку. Расчёт и сохранение — локально в этом браузере.
        </p>
      </section>

      <Card>
        <PageTitle title="Расчёт" eyebrow={selectedType ? selectedType.name : 'Выберите тип'} />
        {error && !calculation ? (
          <Alert>{error}</Alert>
        ) : calculation ? (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="rounded-xl bg-paper-2/70 p-4">
                <dt className="text-xs font-medium text-ink-3">Сумма займа</dt>
                <dd className="num mt-1 text-xl font-bold">{formatMoney(calculation.amount)}</dd>
              </div>
              <div className="rounded-xl bg-paper-2/70 p-4">
                <dt className="text-xs font-medium text-ink-3">Срок</dt>
                <dd className="num mt-1 text-xl font-bold">{calculation.termDays} дн.</dd>
              </div>
            </div>
            <div className="rounded-xl bg-stamp-soft/70 p-4">
              <dt className="text-xs font-medium text-stamp">К возврату (в конце срока)</dt>
              <dd className="num mt-1 text-2xl font-bold text-stamp">{formatMoney(calculation.totalAmount)}</dd>
            </div>
            <div className="text-xs text-ink-3">
              Проценты: {formatMoneyShort(calculation.interestAmount)} · Льготный период: {calculation.gracePeriodDays} дн. · Ставка: {calculation.dailyRatePct}%/день
            </div>
          </div>
        ) : (
          <div className="grid place-items-center py-8 text-money"><Spinner className="h-6 w-6" /></div>
        )}
      </Card>

      <Card>
        <PageTitle title="Ваши данные" />
        <form onSubmit={handleSubmit} className="space-y-5">
          <Field label="ФИО" htmlFor="ml-fullname">
            <TextInput
              id="ml-fullname"
              value={profile.fullName}
              onChange={(e) => setProfile({ ...profile, fullName: e.target.value })}
              placeholder="Иванов Иван Иванович"
              autoComplete="name"
            />
          </Field>

          <Field label="Телефон" htmlFor="ml-phone">
            <TextInput
              id="ml-phone"
              type="tel"
              value={profile.phone}
              onChange={(e) => setProfile({ ...profile, phone: e.target.value })}
              placeholder="+375 (XX) XXX-XX-XX"
              autoComplete="tel"
            />
          </Field>

          <Field label="Email" htmlFor="ml-email">
            <TextInput
              id="ml-email"
              type="email"
              value={profile.email}
              onChange={(e) => setProfile({ ...profile, email: e.target.value })}
              placeholder="ivan@example.com"
              autoComplete="email"
            />
          </Field>

          <Field label="Файл (обязательно)" htmlFor="ml-file" hint="Демо-шаг: файл не загружается и не сохраняется, показывается только имя">
            <FileInput
              id="ml-file"
              value={fileName}
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) {
                  setFile(f);
                  setFileName(f.name);
                }
              }}
              onClear={() => { setFile(null); setFileName(''); }}
              accept=".pdf,.jpg,.jpeg,.png"
            />
          </Field>

          <div className="pt-2 flex gap-2">
            <Button type="submit" size="lg" loading={submitting} disabled={submitting}>
              {submitting ? 'Отправка...' : 'Отправить заявку'}
            </Button>
          </div>

          {submitError && <div className="mt-4"><Alert>{submitError}</Alert></div>}
        </form>
      </Card>

      <p className="text-xs text-ink-3 text-center">
        Данные сохраняются локально в этом браузере (localStorage). Файл и его имя не передаются и не сохраняются.
      </p>
    </div>
  );
}