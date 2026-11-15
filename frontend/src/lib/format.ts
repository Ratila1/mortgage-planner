/** Валюта отображения. Бэкенд её не задаёт — поменять здесь, если нужно. */
export const CURRENCY = 'BYN';

const moneyFmt = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const moneyShortFmt = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });
const compactFmt = new Intl.NumberFormat('ru-RU', { notation: 'compact', maximumFractionDigits: 1 });

export const toNumber = (value: string | number | undefined | null): number => {
  const n = typeof value === 'number' ? value : Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
};

/** 1 234 567,89 BYN */
export const formatMoney = (value: string | number, withCurrency = true) =>
  `${moneyFmt.format(toNumber(value))}${withCurrency ? ` ${CURRENCY}` : ''}`;

/** 1 234 568 BYN — без копеек, для крупных сумм */
export const formatMoneyShort = (value: string | number, withCurrency = true) =>
  `${moneyShortFmt.format(Math.round(toNumber(value)))}${withCurrency ? ` ${CURRENCY}` : ''}`;

/** 1,2 млн — для осей графиков */
export const formatCompact = (value: number) => compactFmt.format(value);

export const formatRate = (value: string | number) =>
  `${new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(toNumber(value))} %`;

function plural(n: number, one: string, few: string, many: string) {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return few;
  return many;
}

/** 25 лет 3 мес. */
export function formatTerm(months: number): string {
  const years = Math.floor(months / 12);
  const rest = months % 12;
  const parts: string[] = [];
  if (years) parts.push(`${years} ${plural(years, 'год', 'года', 'лет')}`);
  if (rest || !years) parts.push(`${rest} мес.`);
  return parts.join(' ');
}

export const pluralMonths = (n: number) => `${n} ${plural(n, 'месяц', 'месяца', 'месяцев')}`;

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

/** Ввод денег: убираем пробелы, запятую превращаем в точку. */
export function parseMoneyInput(raw: string): string {
  const cleaned = raw.replace(/\s/g, '').replace(',', '.').replace(/[^\d.]/g, '');
  const [int, ...rest] = cleaned.split('.');
  return rest.length ? `${int}.${rest.join('').slice(0, 2)}` : int;
}
