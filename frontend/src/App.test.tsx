import { render, screen } from '@testing-library/react';
import { calculateLocally } from './lib/calc';
import App from './App';

describe('App', () => {
  it('renders the title', () => {
    render(<App />);
    expect(screen.getByRole('heading', { level: 1, name: /ипотека без сюрпризов/i })).toBeInTheDocument();
  });
});

// Мок-калькулятор должен совпадать с эталонами из INSTRUCTION.md §5.
describe('calculateLocally', () => {
  const base = { price: '1000000', downPayment: '0', termMonths: 12, annualRate: '12', earlyRepayments: [] };

  it('annuity reference payment', () => {
    const r = calculateLocally({ ...base, type: 'ANNUITY' });
    expect(r.monthlyPayment).toBe('88848.79');
    expect(r.schedule.at(-1)?.balance).toBe('0.00');
  });

  it('differentiated reference first payment', () => {
    const r = calculateLocally({ ...base, type: 'DIFFERENTIATED' });
    expect(r.schedule[0]).toMatchObject({ payment: '93333.33', principal: '83333.33', interest: '10000.00' });
  });

  it('zero rate has no overpayment', () => {
    const r = calculateLocally({ ...base, annualRate: '0', type: 'ANNUITY' });
    expect(r.overpayment).toBe('0.00');
  });

  it('early repayment larger than balance closes the loan', () => {
    const r = calculateLocally({
      ...base,
      type: 'ANNUITY',
      earlyRepayments: [{ type: 'ONE_TIME', month: 3, amount: '99999999', mode: 'REDUCE_TERM' }],
    });
    expect(r.termMonths).toBe(3);
    expect(r.schedule.at(-1)?.balance).toBe('0.00');
    expect(Number(r.savings?.overpayment)).toBeGreaterThan(0);
  });
});
