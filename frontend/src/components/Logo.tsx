import { Link } from 'react-router';

export function Logo() {
  return (
    <Link to="/" className="group flex items-center gap-2.5" aria-label="ООО «Тмыв денег» — на главную">
      <span className="grid h-9 w-9 place-items-center rounded-xl bg-money shadow-sm transition group-hover:rotate-[-6deg]">
        <svg viewBox="0 0 64 64" className="h-7 w-7" aria-hidden="true">
          <path d="M32 11 5.5 34.5h8.5V53h36V34.5h8.5L32 11z" fill="#f5f1e6" />
          <rect x="27" y="39" width="10" height="14" rx="2" fill="#e2a40c" />
        </svg>
      </span>
      <span className="leading-tight">
        <span className="block font-display text-sm font-bold">ООО «Тмыв денег»</span>
        <span className="block text-[11px] text-ink-3">отмываем цифры от мелкого шрифта</span>
      </span>
    </Link>
  );
}
