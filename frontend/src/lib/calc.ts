/**
 * Расчёт ипотеки на клиенте — ТОЛЬКО для моков, пока нет бэкенда.
 * Правила повторяют INSTRUCTION.md §5 (проценты месяца округляются до копеек,
 * последний платёж закрывает остаток в ноль, досрочка — после планового платежа).
 * Числа — double с округлением до копеек: для превью достаточно, честные цифры считает бэкенд на BigDecimal.
 */
import type { CalcParams, CalcResult, EarlyRepayment, RepaymentMode, ScheduleRow } from '../api/types';

const round2 = (x: number) => Math.round((x + Number.EPSILON) * 100) / 100;
const money = (x: number) => round2(x).toFixed(2);

function annuityPayment(principal: number, r: number, months: number): number {
  if (months <= 0) return principal;
  if (r === 0) return round2(principal / months);
  return round2((principal * r) / (1 - Math.pow(1 + r, -months)));
}

interface Planned {
  amount: number;
  mode: RepaymentMode;
}

function expandRepayments(list: EarlyRepayment[], termMonths: number): Map<number, Planned[]> {
  const byMonth = new Map<number, Planned[]>();
  const add = (month: number, amount: number, mode: RepaymentMode) => {
    if (month < 1 || month > termMonths || !(amount > 0)) return;
    const items = byMonth.get(month) ?? [];
    items.push({ amount, mode });
    byMonth.set(month, items);
  };
  for (const er of list) {
    const amount = Number(er.amount);
    if (er.type === 'ONE_TIME') {
      add(er.month, amount, er.mode);
    } else {
      const until = Math.min(er.untilMonth ?? termMonths, termMonths);
      const step = Math.max(1, er.everyMonths);
      for (let m = er.startMonth; m <= until; m += step) add(m, amount, er.mode);
    }
  }
  return byMonth;
}

function firstPaymentDate(month: number): string {
  const now = new Date();
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + month, 1));
  return d.toISOString().slice(0, 10);
}

interface Core {
  rows: ScheduleRow[];
  overpayment: number;
  totalPaid: number;
}

function runSchedule(params: CalcParams, withEarly: boolean): Core {
  const loan = Number(params.price) - Number(params.downPayment);
  const n = params.termMonths;
  const r = Number(params.annualRate) / 100 / 12;
  const early = withEarly ? expandRepayments(params.earlyRepayments, n) : new Map<number, Planned[]>();

  let balance = loan;
  let payment = annuityPayment(loan, r, n); // для аннуитета
  let principalPart = loan / n; // для дифференцированного
  const rows: ScheduleRow[] = [];
  let overpayment = 0;
  let totalPaid = 0;

  for (let m = 1; m <= n && balance > 0.004; m++) {
    const interest = round2(balance * r);
    let principal: number;
    if (params.type === 'ANNUITY') {
      principal = round2(payment - interest);
    } else {
      principal = round2(principalPart);
    }
    // Последний месяц срока или переплата остатка: закрываем долг ровно в ноль.
    if (m === n || principal >= balance) principal = round2(balance);
    const pay = round2(principal + interest);
    balance = round2(balance - principal);

    let earlyAmount = 0;
    let reducePayment = false;
    for (const item of early.get(m) ?? []) {
      const applied = Math.min(item.amount, balance);
      if (applied <= 0) break;
      earlyAmount = round2(earlyAmount + applied);
      balance = round2(balance - applied);
      if (item.mode === 'REDUCE_PAYMENT') reducePayment = true;
    }
    if (reducePayment && balance > 0) {
      const remaining = n - m;
      if (params.type === 'ANNUITY') payment = annuityPayment(balance, r, remaining);
      else principalPart = balance / remaining;
    }

    overpayment += interest;
    totalPaid += pay + earlyAmount;
    rows.push({
      month: m,
      date: firstPaymentDate(m),
      payment: money(pay),
      principal: money(principal),
      interest: money(interest),
      earlyRepayment: money(earlyAmount),
      balance: money(balance),
    });
  }
  return { rows, overpayment: round2(overpayment), totalPaid: round2(totalPaid) };
}

export function calculateLocally(params: CalcParams): CalcResult {
  const core = runSchedule(params, true);
  const result: CalcResult = {
    calcId: `mock-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
    loanAmount: money(Number(params.price) - Number(params.downPayment)),
    monthlyPayment: core.rows[0]?.payment ?? '0.00',
    overpayment: money(core.overpayment),
    totalPaid: money(core.totalPaid),
    termMonths: core.rows.length,
    schedule: core.rows,
  };
  if (params.earlyRepayments.length > 0) {
    const base = runSchedule(params, false);
    result.savings = {
      overpayment: money(base.overpayment - core.overpayment),
      months: base.rows.length - core.rows.length,
    };
  }
  return result;
}

export function validateParams(params: CalcParams): string | null {
  const price = Number(params.price);
  const down = Number(params.downPayment);
  const rate = Number(params.annualRate);
  if (!(price > 0)) return 'Укажите стоимость жилья';
  if (!(down >= 0)) return 'Первоначальный взнос не может быть отрицательным';
  if (down >= price) return 'Взнос больше цены жилья? Тогда ипотека вам не нужна 🎉';
  if (!(params.termMonths >= 1 && params.termMonths <= 360)) return 'Срок — от 1 месяца до 30 лет';
  if (!(rate >= 0 && rate <= 100)) return 'Ставка — от 0 до 100 % годовых';
  return null;
}
