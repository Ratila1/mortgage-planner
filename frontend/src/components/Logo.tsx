import { Link } from 'react-router';

export function Logo() {
  return (
    <Link to="/" className="group flex items-center gap-2.5" aria-label="ООО «Тмыв денег» — на главную">
      <span className="grid h-9 w-9 place-items-center rounded-xl bg-money font-display text-sm font-extrabold text-white shadow-sm transition group-hover:rotate-[-6deg]">
        ТД
      </span>
      <span className="leading-tight">
        <span className="block font-display text-sm font-bold">ООО «Тмыв денег»</span>
        <span className="block text-[11px] text-ink-3">отмываем цифры от мелкого шрифта</span>
      </span>
    </Link>
  );
}
