import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { api, ApiError, errorMessage, USE_MOCKS } from '../api';
import type { OAuthProvider } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Alert, Button, Field, Segmented, Spinner, Stamp, TextInput } from '../components/ui';

function AuthShell({ title, subtitle, children, footer }: { title: string; subtitle: ReactNode; children: ReactNode; footer?: ReactNode }) {
  return (
    <div className="mx-auto grid max-w-5xl items-center gap-10 md:grid-cols-2">
      <div className="hidden md:block">
        <Stamp tone="money" className="mb-6">Без мелкого шрифта</Stamp>
        <h1 className="font-display text-4xl font-extrabold leading-tight tracking-tight">{title}</h1>
        <p className="mt-4 text-lg text-ink-2">{subtitle}</p>
        <ul className="mt-8 space-y-3 text-sm text-ink-2">
          <li>✓ Сохраняйте сценарии и сравнивайте их бок о бок</li>
          <li>✓ Узнавайте первыми, когда банк меняет ставку</li>
          <li>✓ Пароль храним хэшем, токен — только в памяти браузера</li>
        </ul>
      </div>
      <div className="card p-6 sm:p-8">
        <h2 className="font-display text-2xl font-bold md:hidden">{title}</h2>
        <div className="mt-4 md:mt-0">{children}</div>
        {footer && <div className="mt-6 border-t border-line pt-5 text-center text-sm text-ink-2">{footer}</div>}
      </div>
    </div>
  );
}

const OAUTH_LABELS: Record<OAuthProvider, string> = { google: 'Войти через Google', github: 'Войти через GitHub' };

/** Коды из редиректа бэкенда /login?oauthError=… (OAuth2AuthenticationSuccessHandler, SecurityConfig). */
const OAUTH_ERRORS: Record<string, string> = {
  no_email: 'Провайдер не отдал адрес почты. Откройте доступ к email в настройках аккаунта или войдите по почте.',
  failed: 'Не получилось войти через соцсеть: вход отменён или провайдер ответил ошибкой. Попробуйте ещё раз.',
};

/** Кнопки показываются, только если бэкенд запущен с профилем oauth и ключами провайдеров. */
function OAuthButtons() {
  const [providers, setProviders] = useState<OAuthProvider[]>([]);
  useEffect(() => {
    let alive = true;
    api.oauthProviders().then((list) => alive && setProviders(list));
    return () => {
      alive = false;
    };
  }, []);

  if (providers.length === 0) return null;
  return (
    <>
      <div className={`grid gap-2 ${providers.length > 1 ? 'sm:grid-cols-2' : ''}`}>
      {providers.map((provider) => (
        <a
          key={provider}
          href={api.oauthUrl(provider)}
          onClick={(e) => {
            if (USE_MOCKS) {
              e.preventDefault();
              alert('В демо-режиме вход через соцсети не работает. Он заработает вместе с бэкендом.');
            }
          }}
          className="flex items-center justify-center gap-2 rounded-xl border border-line bg-white/70 px-3 py-2.5 text-sm font-semibold transition hover:border-ink-3"
        >
          {OAUTH_LABELS[provider]}
        </a>
      ))}
    </div>
    <Divider>или по почте</Divider>
    </>
  );
}

function Divider({ children }: { children: ReactNode }) {
  return (
    <div className="my-5 flex items-center gap-3 text-xs text-ink-3">
      <span className="h-px flex-1 bg-line" />
      {children}
      <span className="h-px flex-1 bg-line" />
    </div>
  );
}

export function LoginPage() {
  const { login, verifyOtp } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/scenarios';
  const [searchParams] = useSearchParams();
  const oauthErrorCode = searchParams.get('oauthError');
  const oauthError = oauthErrorCode ? (OAUTH_ERRORS[oauthErrorCode] ?? OAUTH_ERRORS.failed) : null;

  const [mode, setMode] = useState<'password' | 'otp'>('password');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [otpSent, setOtpSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notVerified, setNotVerified] = useState(false);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    setNotVerified(false);
    try {
      await action();
    } catch (err) {
      setError(errorMessage(err));
      setNotVerified(err instanceof ApiError && err.code === 'EMAIL_NOT_VERIFIED');
    } finally {
      setBusy(false);
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (mode === 'password') {
      run(async () => {
        await login(email, password);
        navigate(from, { replace: true });
      });
    } else if (!otpSent) {
      run(async () => {
        await api.requestOtp(email);
        setOtpSent(true);
      });
    } else {
      run(async () => {
        await verifyOtp(email, code);
        navigate(from, { replace: true });
      });
    }
  };

  return (
    <AuthShell
      title="С возвращением"
      subtitle="Ваши сценарии на месте. Ставки банков, увы, тоже."
      footer={<>Ещё нет аккаунта? <Link to="/register" className="font-semibold text-money">Зарегистрируйтесь</Link></>}
    >
      {oauthError && <div className="mb-4"><Alert>{oauthError}</Alert></div>}
      <OAuthButtons />
      <Segmented
        label="Способ входа"
        value={mode}
        onChange={(m) => {
          setMode(m);
          setError(null);
        }}
        options={[
          { value: 'password', label: 'Пароль' },
          { value: 'otp', label: 'Код на почту' },
        ]}
      />
      <form onSubmit={submit} className="mt-5 space-y-4">
        <Field label="Почта" htmlFor="email">
          <TextInput id="email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        {mode === 'password' ? (
          <Field label="Пароль" htmlFor="password">
            <TextInput id="password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
          </Field>
        ) : (
          otpSent && (
            <Field label="Код из письма" htmlFor="code" hint={USE_MOCKS ? 'в демо: 123456' : 'действует 10 минут'}>
              <TextInput id="code" inputMode="numeric" autoComplete="one-time-code" required maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
            </Field>
          )
        )}
        {error && (
          <Alert>
            {error}
            {notVerified && <span className="block pt-1 text-xs">Письмо не пришло? Проверьте «Спам» — оно там в хорошей компании.</span>}
          </Alert>
        )}
        <Button type="submit" size="lg" className="w-full" loading={busy}>
          {mode === 'password' ? 'Войти' : otpSent ? 'Подтвердить код' : 'Прислать код'}
        </Button>
      </form>
    </AuthShell>
  );
}

export function RegisterPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const weak = password.length > 0 && password.length < 8;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.register(email, password);
      setDone(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthShell
      title="Аккаунт за минуту"
      subtitle="Без звонков менеджера, без «предварительного одобрения» и без подписки на рассылку о вкладах."
      footer={<>Уже есть аккаунт? <Link to="/login" className="font-semibold text-money">Войти</Link></>}
    >
      {done ? (
        <div className="py-6 text-center">
          <div className="mb-4 text-5xl" aria-hidden="true">📬</div>
          <h3 className="font-display text-xl font-bold">Проверьте почту</h3>
          <p className="mt-2 text-sm text-ink-2">
            Мы отправили ссылку на <b>{email}</b>. Она действует 24 часа — дольше, чем многие акционные ставки.
          </p>
          {USE_MOCKS && (
            <Link to="/verify-email?token=demo" className="mt-5 inline-block text-sm font-semibold text-money underline underline-offset-4">
              Демо: «нажать» ссылку из письма
            </Link>
          )}
        </div>
      ) : (
        <>
          <OAuthButtons />
          <form onSubmit={submit} className="space-y-4">
            <Field label="Почта" htmlFor="reg-email">
              <TextInput id="reg-email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
            </Field>
            <Field label="Пароль" htmlFor="reg-password" hint="минимум 8 символов" error={weak ? 'Слишком короткий. Даже банк придумывает пароли длиннее.' : null}>
              <TextInput id="reg-password" type="password" autoComplete="new-password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} />
            </Field>
            {error && <Alert>{error}</Alert>}
            <Button type="submit" size="lg" className="w-full" loading={busy} disabled={weak}>Создать аккаунт</Button>
            <p className="text-center text-xs text-ink-3">Нажимая кнопку, вы соглашаетесь с тем, что ипотека — это надолго.</p>
          </form>
        </>
      )}
    </AuthShell>
  );
}

export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [state, setState] = useState<'loading' | 'ok' | 'error'>(token ? 'loading' : 'error');
  const [error, setError] = useState<string | null>(token ? null : 'В ссылке нет токена.');

  useEffect(() => {
    if (!token) return;
    api
      .verifyEmail(token)
      .then(() => setState('ok'))
      .catch((err) => {
        setError(errorMessage(err));
        setState('error');
      });
  }, [token]);

  return (
    <div className="mx-auto max-w-md">
      <div className="card p-8 text-center">
        {state === 'loading' && (
          <div className="py-8 text-money">
            <Spinner className="h-8 w-8" />
          </div>
        )}
        {state === 'ok' && (
          <>
            <Stamp tone="money" className="mb-5">Подтверждено</Stamp>
            <h1 className="font-display text-2xl font-bold">Почта подтверждена</h1>
            <p className="mt-2 text-sm text-ink-2">Быстрее, чем банк одобряет заявку. Теперь можно войти.</p>
            <Link to="/login" className="mt-6 inline-block rounded-xl bg-money px-5 py-3 text-sm font-semibold text-white hover:bg-money-2">Войти</Link>
          </>
        )}
        {state === 'error' && (
          <>
            <Stamp className="mb-5">Не вышло</Stamp>
            <h1 className="font-display text-2xl font-bold">Ссылка не сработала</h1>
            <p className="mt-2 text-sm text-ink-2">{error}</p>
            <Link to="/register" className="mt-6 inline-block text-sm font-semibold text-money underline underline-offset-4">Зарегистрироваться заново</Link>
          </>
        )}
      </div>
    </div>
  );
}
