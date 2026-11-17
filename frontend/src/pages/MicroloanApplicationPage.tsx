import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import {
  api,
  errorMessage,
  type CustomerProfileInput,
  type MicroloanCalculationResult,
  type MicroloanType,
  type PassportStatus,
} from '../api';
import { Alert, Button, Card, Field, FileInput, PageTitle, Spinner, TextInput } from '../components/ui';
import { formatDateTime, formatMoney, formatMoneyShort, parseMoneyInput, toNumber } from '../lib/format';

/** Параметры займа приходят из калькулятора через state навигации. */
interface PrefillState {
  typeId: string;
  amount: string;
  termDays: number;
}

const EMPTY_PROFILE: CustomerProfileInput = { lastName: '', firstName: '', middleName: '', phone: '', monthlyIncome: '' };
const PHONE_RE = /^\+\d{10,15}$/;
const MAX_PASSPORT_BYTES = 5 * 1024 * 1024;

/**
 * Подача заявки (MICROLOANS.md §4.1). Email не спрашиваем: он уже есть в учётной записи.
 * Данные профиля (ФИО, телефон, зарплата) подставляются из /api/me/profile и сохраняются бэком
 * вместе с заявкой. Фото паспорта обязательно: без него бэк отвечает 409 KYC_REQUIRED,
 * поэтому выбранный файл сначала загружается на /api/me/kyc/passport, затем подаётся заявка.
 */
export function MicroloanApplicationPage() {
  const location = useLocation();
  const prefill = location.state as PrefillState | null;
  if (!prefill?.typeId) return <Navigate to="/microloan" replace />;
  return <ApplicationForm prefill={prefill} />;
}

function ApplicationForm({ prefill }: { prefill: PrefillState }) {
  const navigate = useNavigate();
  const [type, setType] = useState<MicroloanType | null>(null);
  const [quote, setQuote] = useState<MicroloanCalculationResult | null>(null);
  const [profile, setProfile] = useState<CustomerProfileInput>(EMPTY_PROFILE);
  const [passport, setPassport] = useState<PassportStatus | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api.listMicroloanTypes(),
      api.calculateMicroloan(prefill.typeId, prefill.amount, prefill.termDays),
      api.getProfile(),
      api.getPassport(),
    ])
      .then(([types, q, savedProfile, savedPassport]) => {
        if (cancelled) return;
        setType(types.find((t) => t.id === prefill.typeId) ?? null);
        setQuote(q);
        if (savedProfile) {
          setProfile({
            lastName: savedProfile.lastName,
            firstName: savedProfile.firstName,
            middleName: savedProfile.middleName,
            phone: savedProfile.phone,
            monthlyIncome: savedProfile.monthlyIncome,
          });
        }
        setPassport(savedPassport);
      })
      .catch((err) => !cancelled && setLoadError(errorMessage(err)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [prefill.typeId, prefill.amount, prefill.termDays]);

  // Фото, удалённое после подписи или по сроку хранения, нужно загрузить заново.
  const hasPassport = !!passport && !passport.deleted;

  const validate = () => {
    if (!profile.lastName.trim()) return 'Укажите фамилию';
    if (!profile.firstName.trim()) return 'Укажите имя';
    if (!PHONE_RE.test(profile.phone.trim())) return 'Телефон в формате +375291234567';
    if (!(toNumber(profile.monthlyIncome) > 0)) return 'Укажите зарплату в месяц';
    if (!hasPassport && !file) return 'Загрузите фото паспорта';
    if (file && !['image/jpeg', 'image/png'].includes(file.type)) return 'Фото паспорта — только JPEG или PNG';
    if (file && file.size > MAX_PASSPORT_BYTES) return 'Фото паспорта — не больше 5 МБ';
    return null;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitError(null);
    const err = validate();
    if (err) return setSubmitError(err);
    setSubmitting(true);
    try {
      if (file) {
        setPassport(await api.uploadPassport(file));
        setFile(null);
      }
      await api.createMicroloanApplication({
        typeId: prefill.typeId,
        amount: prefill.amount,
        termDays: prefill.termDays,
        profile,
      });
      navigate('/cabinet/applications', { replace: true });
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div className="grid place-items-center py-24 text-money">
        <Spinner className="h-8 w-8" />
      </div>
    );
  }
  if (loadError) return <Alert>{loadError}</Alert>;

  return (
    <div className="max-w-2xl space-y-8">
      <section>
        <p className="eyebrow mb-3">Подача заявки</p>
        <h1 className="font-display text-4xl font-extrabold leading-[1.05] tracking-tight sm:text-5xl">
          Оформить заявку на микрозайм
        </h1>
      </section>

      {quote && (
        <Card>
          <PageTitle title="Расчёт" eyebrow={type?.name} />
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="rounded-xl bg-paper-2/70 p-4">
                <dt className="text-xs font-medium text-ink-3">Сумма займа</dt>
                <dd className="num mt-1 text-xl font-bold">{formatMoney(quote.amount)}</dd>
              </div>
              <div className="rounded-xl bg-paper-2/70 p-4">
                <dt className="text-xs font-medium text-ink-3">Срок</dt>
                <dd className="num mt-1 text-xl font-bold">{quote.termDays} дн.</dd>
              </div>
            </div>
            <div className="rounded-xl bg-stamp-soft/70 p-4">
              <dt className="text-xs font-medium text-stamp">К возврату (в конце срока)</dt>
              <dd className="num mt-1 text-2xl font-bold text-stamp">{formatMoney(quote.totalAmount)}</dd>
            </div>
            <div className="text-xs text-ink-3">
              Проценты: {formatMoneyShort(quote.interestAmount)} · Надбавка: {formatMoneyShort(quote.modifier)} · Ставка:{' '}
              {quote.dailyRatePct}% в день
            </div>
          </div>
        </Card>
      )}

      <Card>
        <PageTitle title="Ваши данные" />
        <form onSubmit={handleSubmit} className="space-y-5">
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Фамилия" htmlFor="ml-last">
              <TextInput
                id="ml-last"
                value={profile.lastName}
                onChange={(e) => setProfile({ ...profile, lastName: e.target.value })}
                autoComplete="family-name"
              />
            </Field>
            <Field label="Имя" htmlFor="ml-first">
              <TextInput
                id="ml-first"
                value={profile.firstName}
                onChange={(e) => setProfile({ ...profile, firstName: e.target.value })}
                autoComplete="given-name"
              />
            </Field>
            <Field label="Отчество" htmlFor="ml-middle">
              <TextInput
                id="ml-middle"
                value={profile.middleName}
                onChange={(e) => setProfile({ ...profile, middleName: e.target.value })}
                autoComplete="additional-name"
              />
            </Field>
          </div>

          <Field label="Телефон" htmlFor="ml-phone">
            <TextInput
              id="ml-phone"
              type="tel"
              value={profile.phone}
              onChange={(e) => setProfile({ ...profile, phone: e.target.value.replace(/[^\d+]/g, '') })}
              placeholder="+375291234567"
              autoComplete="tel"
            />
          </Field>

          <Field label="Зарплата в месяц" htmlFor="ml-income">
            <TextInput
              id="ml-income"
              inputMode="decimal"
              suffix="BYN"
              value={profile.monthlyIncome}
              onChange={(e) => setProfile({ ...profile, monthlyIncome: parseMoneyInput(e.target.value) })}
            />
          </Field>

          <Field
            label="Фото паспорта"
            htmlFor="ml-passport"
            hint={
              hasPassport
                ? `Загружено ${formatDateTime(passport!.uploadedAt)}. Можно заменить.`
                : 'JPEG или PNG, до 5 МБ'
            }
          >
            <FileInput
              id="ml-passport"
              value={file?.name ?? ''}
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              onClear={() => setFile(null)}
              accept="image/jpeg,image/png,.jpg,.jpeg,.png"
            />
          </Field>

          {submitError && <Alert>{submitError}</Alert>}

          <div className="flex gap-2 pt-2">
            <Button type="submit" size="lg" loading={submitting} disabled={submitting}>
              Отправить заявку
            </Button>
          </div>
        </form>
      </Card>
    </div>
  );
}
