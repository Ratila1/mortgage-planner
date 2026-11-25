import { useEffect, useRef, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import {
  api,
  errorMessage,
  type CustomerProfileInput,
  type MicroloanCalculationResult,
  type MicroloanType,
  type PassportStatus,
  type Sex,
} from '../api';
import { PhoneVerifyPanel } from '../components/PhoneVerifyPanel';
import { Alert, Card, Field, FileInput, PageTitle, Select, Skeleton, TextInput } from '../components/ui';
import { formatDateTime, formatMoney, formatMoneyShort, parseMoneyInput, toNumber } from '../lib/format';

/** Параметры займа приходят из калькулятора через state навигации. */
interface PrefillState {
  typeId: string;
  amount: string;
  termDays: number;
}

const EMPTY_PROFILE: CustomerProfileInput = {
  lastName: '',
  firstName: '',
  middleName: '',
  phone: '',
  monthlyIncome: '',
  birthDate: '',
  sex: '',
  country: 'BY',
  city: '',
};
const COUNTRY_RE = /^[A-Za-z]{2}$/;
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
  const [verifiedPhone, setVerifiedPhone] = useState<string | null>(null);
  const [verificationKey, setVerificationKey] = useState(0);
  const phoneRef = useRef(profile.phone);
  phoneRef.current = profile.phone;
  const savedPhoneRef = useRef<string | null>(null);
  const submittedRef = useRef(false);

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
            birthDate: savedProfile.birthDate,
            sex: savedProfile.sex,
            // У старых профилей страны нет — по умолчанию Беларусь.
            country: savedProfile.country || 'BY',
            city: savedProfile.city,
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

  // The SMS code belongs to the number that was saved before the request.
  useEffect(() => {
    if (savedPhoneRef.current || verifiedPhone) {
      setSubmitError('Телефон изменился. Запросите новый код.');
    }
    setVerifiedPhone(null);
    savedPhoneRef.current = null;
    setVerificationKey((key) => key + 1);
  }, [profile.phone]);

  // Фото, удалённое после подписи или по сроку хранения, нужно загрузить заново.
  const hasPassport = !!passport && !passport.deleted;

  const validate = () => {
    if (!profile.lastName.trim()) return 'Укажите фамилию';
    if (!profile.firstName.trim()) return 'Укажите имя';
    if (!PHONE_RE.test(profile.phone.trim())) return 'Телефон в формате +375291234567';
    if (!(toNumber(profile.monthlyIncome) > 0)) return 'Укажите зарплату в месяц';
    // Нужны скорингу (CREDIT_POLICY.md §4). Возраст не проверяем здесь: вне 18–90 скоринг откажет сам.
    if (!profile.birthDate) return 'Укажите дату рождения';
    if (!profile.sex) return 'Укажите пол';
    if (!COUNTRY_RE.test(profile.country.trim())) return 'Страна — двухбуквенный код, например BY';
    if (!profile.city.trim()) return 'Укажите город';
    if (!hasPassport && !file) return 'Загрузите фото паспорта';
    if (file && !['image/jpeg', 'image/png'].includes(file.type)) return 'Фото паспорта — только JPEG или PNG';
    if (file && file.size > MAX_PASSPORT_BYTES) return 'Фото паспорта — не больше 5 МБ';
    return null;
  };

  // Validate and persist the form before the SMS is sent to the saved number.
  const beforePhoneRequest = async (): Promise<boolean> => {
    setSubmitError(null);
    const err = validate();
    if (err) {
      setSubmitError(err);
      return false;
    }
    try {
      if (file) {
        setPassport(await api.uploadPassport(file));
        setFile(null);
      }
      const saved = await api.saveProfile(profile);
      if (saved.phone !== phoneRef.current) {
        setSubmitError('Телефон изменился во время сохранения. Повторите подтверждение.');
        return false;
      }
      savedPhoneRef.current = saved.phone;
      return true;
    } catch (error) {
      setSubmitError(errorMessage(error));
      return false;
    }
  };

  // A successful code submits the application immediately; no second button is needed.
  const afterPhoneVerified = async () => {
    const currentPhone = phoneRef.current;
    if (!savedPhoneRef.current || savedPhoneRef.current !== currentPhone) {
      setSubmitError('Телефон изменился. Запросите новый код.');
      setVerifiedPhone(null);
      setVerificationKey((key) => key + 1);
      return;
    }
    try {
      const saved = await api.getProfile();
      if (!saved || !saved.phoneVerified || saved.phone !== currentPhone || phoneRef.current !== currentPhone) {
        setSubmitError('Телефон изменился. Запросите новый код.');
        setVerifiedPhone(null);
        setVerificationKey((key) => key + 1);
        return;
      }
      // A passport chosen after the code was requested must still be uploaded before submit.
      const err = validate();
      if (err) {
        setSubmitError(err);
        setVerifiedPhone(null);
        setVerificationKey((key) => key + 1);
        return;
      }
      setVerifiedPhone(saved.phone);
      if (submittedRef.current) return;
      submittedRef.current = true;
      setSubmitting(true);
      setSubmitError(null);
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
        submittedRef.current = false;
        setVerifiedPhone(null);
        setVerificationKey((key) => key + 1);
      } finally {
        setSubmitting(false);
      }
    } catch (error) {
      setSubmitError(errorMessage(error));
      setVerifiedPhone(null);
      setVerificationKey((key) => key + 1);
    }
  };

  if (loading) {
    return (
      <div className="space-y-4" aria-hidden="true">
        <Skeleton className="h-10 w-64 max-w-full" />
        <Skeleton className="h-64" />
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
        <form onSubmit={(e) => e.preventDefault()} className="space-y-5">
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

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Дата рождения" htmlFor="ml-birth">
              <TextInput
                id="ml-birth"
                type="date"
                value={profile.birthDate}
                max={new Date().toISOString().slice(0, 10)}
                onChange={(e) => setProfile({ ...profile, birthDate: e.target.value })}
                autoComplete="bday"
              />
            </Field>
            <Field label="Пол" htmlFor="ml-sex">
              <Select
                value={profile.sex || ''}
                options={[
                  { value: '', label: 'Не выбран' },
                  { value: 'FEMALE', label: 'Женский' },
                  { value: 'MALE', label: 'Мужской' },
                ]}
                onChange={(sex) => setProfile({ ...profile, sex: sex as Sex | '' })}
              />
            </Field>
          </div>

          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Страна" htmlFor="ml-country" hint="Код: BY, PL…">
              <TextInput
                id="ml-country"
                value={profile.country}
                maxLength={2}
                onChange={(e) => setProfile({ ...profile, country: e.target.value.toUpperCase() })}
                autoComplete="country"
              />
            </Field>
            <div className="sm:col-span-2">
              <Field label="Город" htmlFor="ml-city">
                <TextInput
                  id="ml-city"
                  value={profile.city}
                  maxLength={100}
                  onChange={(e) => setProfile({ ...profile, city: e.target.value })}
                  autoComplete="address-level2"
                />
              </Field>
            </div>
          </div>

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

          <div className="pt-2">
            <PhoneVerifyPanel
              key={verificationKey}
              phone={profile.phone}
              onBeforeRequest={beforePhoneRequest}
              onVerified={afterPhoneVerified}
              description={
                <>
                  Сохраним указанные данные и отправим код на номер{' '}
                  <span className="num font-semibold">{profile.phone || '—'}</span>. После верного кода заявка уйдёт
                  автоматически.
                </>
              }
              requestLabel="Подтвердить телефон"
              confirmLabel="Подтвердить и отправить"
            />
            {verifiedPhone && submitting && (
              <p className="mt-2 text-sm text-money-2">Телефон подтверждён. Отправляем заявку…</p>
            )}
          </div>
        </form>
      </Card>
    </div>
  );
}
