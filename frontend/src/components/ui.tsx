import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react';

const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');
export { cx };

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

const buttonStyles: Record<ButtonVariant, string> = {
  primary: 'bg-money text-white hover:bg-money-2 shadow-sm',
  secondary: 'bg-card text-ink border border-line hover:border-ink-3',
  ghost: 'text-ink-2 hover:bg-paper-2 hover:text-ink',
  danger: 'bg-stamp text-white hover:brightness-95',
};

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  className,
  children,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
}) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={cx(
        'inline-flex items-center justify-center gap-2 rounded-xl font-semibold transition',
        'disabled:cursor-not-allowed disabled:opacity-50 active:translate-y-px',
        size === 'sm' && 'px-3 py-1.5 text-sm',
        size === 'md' && 'px-4 py-2.5 text-sm',
        size === 'lg' && 'px-6 py-3.5 text-base',
        buttonStyles[variant],
        className,
      )}
    >
      {loading && <Spinner className="h-4 w-4" />}
      {children}
    </button>
  );
}

export function Spinner({ className = 'h-5 w-5' }: { className?: string }) {
  return (
    <span
      role="status"
      aria-label="Загрузка"
      className={cx('inline-block animate-spin rounded-full border-2 border-current border-t-transparent', className)}
    />
  );
}

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return <section className={cx('card p-5 sm:p-6', className)}>{children}</section>;
}

export function Field({
  label,
  hint,
  error,
  children,
  htmlFor,
}: {
  label: ReactNode;
  hint?: ReactNode;
  error?: string | null;
  children: ReactNode;
  htmlFor?: string;
}) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={htmlFor} className="flex items-baseline justify-between gap-3 text-sm font-medium text-ink-2">
        <span>{label}</span>
        {hint && <span className="text-xs font-normal text-ink-3">{hint}</span>}
      </label>
      {children}
      {error && <p className="text-xs text-stamp">{error}</p>}
    </div>
  );
}

export function TextInput({
  suffix,
  className,
  ...rest
}: InputHTMLAttributes<HTMLInputElement> & { suffix?: ReactNode }) {
  return (
    <div
      className={cx(
        'flex items-center rounded-xl border border-line bg-white/70 transition',
        'focus-within:border-money focus-within:ring-2 focus-within:ring-money/15',
        className,
      )}
    >
      <input
        {...rest}
        className="min-w-0 flex-1 bg-transparent px-3.5 py-2.5 text-ink outline-none placeholder:text-ink-3"
      />
      {suffix && <span className="pr-3.5 text-sm text-ink-3">{suffix}</span>}
    </div>
  );
}

export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T;
  options: Array<{ value: T; label: ReactNode; hint?: string }>;
  onChange: (value: T) => void;
  label: string;
}) {
  return (
    <div role="radiogroup" aria-label={label} className="grid auto-cols-fr grid-flow-col gap-1 rounded-xl bg-paper-2 p-1">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={value === option.value}
          title={option.hint}
          onClick={() => onChange(option.value)}
          className={cx(
            'rounded-lg px-3 py-2 text-sm font-medium transition',
            value === option.value ? 'bg-card text-ink shadow-sm' : 'text-ink-2 hover:text-ink',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function Badge({
  tone = 'neutral',
  children,
}: {
  tone?: 'neutral' | 'money' | 'stamp' | 'gold';
  children: ReactNode;
}) {
  const tones = {
    neutral: 'bg-paper-2 text-ink-2',
    money: 'bg-money-soft text-money-2',
    stamp: 'bg-stamp-soft text-stamp',
    gold: 'bg-gold-soft text-[#8a6100]',
  };
  return (
    <span className={cx('inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold', tones[tone])}>
      {children}
    </span>
  );
}

/** Печать — фирменный элемент. */
export function Stamp({ children, tone = 'stamp', className }: { children: ReactNode; tone?: 'stamp' | 'money'; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cx(
        'stamp-in pointer-events-none inline-block select-none rounded-md border-[3px] px-2.5 py-1',
        'font-display text-[11px] font-extrabold uppercase leading-tight tracking-widest opacity-80',
        tone === 'stamp' ? 'border-stamp text-stamp' : 'border-money text-money',
        className,
      )}
    >
      {children}
    </span>
  );
}

export function Alert({ tone = 'stamp', children }: { tone?: 'stamp' | 'money' | 'gold'; children: ReactNode }) {
  const tones = {
    stamp: 'border-stamp/30 bg-stamp-soft text-stamp',
    money: 'border-money/30 bg-money-soft text-money-2',
    gold: 'border-gold/40 bg-gold-soft text-[#7a5600]',
  };
  return <div role="alert" className={cx('rounded-xl border px-4 py-3 text-sm', tones[tone])}>{children}</div>;
}

export function EmptyState({ title, text, action, emoji = '🧾' }: { title: string; text: ReactNode; action?: ReactNode; emoji?: string }) {
  return (
    <div className="flex flex-col items-center rounded-[var(--radius-card)] border-2 border-dashed border-line px-6 py-14 text-center">
      <div className="mb-4 text-4xl" aria-hidden="true">{emoji}</div>
      <h3 className="font-display text-lg font-bold">{title}</h3>
      <p className="mt-2 max-w-md text-sm text-ink-2">{text}</p>
      {action && <div className="mt-6">{action}</div>}
    </div>
  );
}

export function PageTitle({ eyebrow, title, children }: { eyebrow?: string; title: ReactNode; children?: ReactNode }) {
  return (
    <header className="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div>
        {eyebrow && <p className="eyebrow mb-2">{eyebrow}</p>}
        <h1 className="font-display text-3xl font-bold tracking-tight sm:text-4xl">{title}</h1>
      </div>
      {children}
    </header>
  );
}
