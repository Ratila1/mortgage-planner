import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api } from '../api';
import { markLegacyApplication, resetMockState } from '../api/mock';
import { ContractSigning } from './ContractSigning';

const PROFILE = {
  lastName: 'Иванов',
  firstName: 'Иван',
  middleName: '',
  phone: '+375291234567',
  monthlyIncome: '2500',
  birthDate: '1990-05-10',
  sex: 'MALE' as const,
  country: 'BY',
  city: 'Минск',
};
const CHANGED_PHONE = '+375291111111';

/**
 * Apps submitted through the form are signed and activate on approval. To exercise the old
 * signing flow we flip the marker and, when needed, clear verification by changing the phone.
 */
async function seedApproved({ legacy = false, unverified = false } = {}) {
  resetMockState();
  await api.login('client@example.com', 'password123');
  await api.saveProfile(PROFILE);
  await api.requestPhoneVerification();
  await api.confirmPhoneVerification('1234');
  await api.uploadPassport(new File(['passport'], 'passport.png', { type: 'image/png' }));
  const app = await api.createMicroloanApplication({
    typeId: 'ml-type-1',
    amount: '500.00',
    termDays: 14,
    profile: PROFILE,
  });
  if (legacy) markLegacyApplication(app.id);
  if (unverified) await api.saveProfile({ ...PROFILE, phone: CHANGED_PHONE });
  await api.login('admin@test.com', 'password123');
  await api.adminApproveMicroloanApplication(app.id);
  await api.login('client@example.com', 'password123');
  return app.id;
}

describe('ContractSigning', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('activates a legacy loan with the signing code and keeps the contract downloadable', async () => {
    const id = await seedApproved({ legacy: true });
    render(<ContractSigning id={id} />);

    expect(await screen.findByRole('button', { name: 'Скачать договор (PDF)' })).toBeInTheDocument();
    expect(screen.queryByText('Подтвердите телефон')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Получить код по SMS' }));
    const codeInput = await screen.findByPlaceholderText('Код из SMS');
    fireEvent.change(codeInput, { target: { value: '1234' } });
    fireEvent.click(screen.getByRole('button', { name: 'Подписать' }));

    await waitFor(async () => expect((await api.getMicroloanApplication(id)).status).toBe('ACTIVE'));
    expect(await screen.findByText('Договор подписан, заём выдан.')).toBeInTheDocument();
  }, 30000);

  it('offers phone verification as a fallback for an unverified legacy application', async () => {
    const id = await seedApproved({ legacy: true, unverified: true });
    render(<ContractSigning id={id} />);

    expect(await screen.findByText('Подтвердите телефон')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Получить код по SMS' })).not.toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText('Код из SMS'), { target: { value: '1234' } });
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));

    expect(await screen.findByRole('button', { name: 'Получить код по SMS' })).toBeInTheDocument();
    expect(screen.queryByText('Подтвердите телефон')).not.toBeInTheDocument();
  }, 30000);

  it('falls back to phone verification when signature request reports PHONE_NOT_VERIFIED', async () => {
    const id = await seedApproved({ legacy: true });
    vi.spyOn(api, 'requestSignatureCode').mockRejectedValueOnce(
      new ApiError({ status: 403, code: 'PHONE_NOT_VERIFIED' }),
    );
    render(<ContractSigning id={id} />);

    expect(await screen.findByRole('button', { name: 'Скачать договор (PDF)' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Получить код по SMS' }));

    expect(await screen.findByText('Подтвердите телефон')).toBeInTheDocument();
    expect(await screen.findByText(/Телефон не подтверждён/)).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText('Код из SMS'), { target: { value: '1234' } });
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));

    expect(
      await screen.findByRole('button', { name: 'Получить код по SMS' }, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(screen.queryByText('Подтвердите телефон')).not.toBeInTheDocument();
  }, 30000);

  it('activates a form-signed loan without asking for a second code', async () => {
    const id = await seedApproved();
    expect((await api.getMicroloanApplication(id)).status).toBe('ACTIVE');

    render(<ContractSigning id={id} />);

    expect(await screen.findByText('Договор подписан, заём выдан.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Получить код по SMS' })).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Код из SMS')).not.toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Скачать договор (PDF)' })).toBeInTheDocument();
  }, 30000);
});
