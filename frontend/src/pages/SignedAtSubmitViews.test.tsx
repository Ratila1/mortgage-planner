import type { ReactNode } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';
import { api } from '../api';
import { markLegacyApplication, resetMockState, setApplicationStatus } from '../api/mock';
import { MicroloanApplicationsPage } from './MicroloanApplicationsPage';
import { NotificationsPage } from './NotificationsPage';

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

async function seedSignedApplication() {
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
  await api.login('admin@test.com', 'password123');
  await api.adminApproveMicroloanApplication(app.id);
  await api.login('client@example.com', 'password123');
  return app.id;
}

function renderPage(page: ReactNode) {
  render(<MemoryRouter>{page}</MemoryRouter>);
}

describe('signed-at-submit views', () => {
  afterEach(() => cleanup());

  it('shows a stalled signed application without the signing code or deadline hint', async () => {
    const id = await seedSignedApplication();
    setApplicationStatus(id, 'APPROVED');

    renderPage(<MicroloanApplicationsPage />);

    expect(await screen.findByText('Ожидаем активацию займа', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Подписать' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Подписать до/)).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Код из SMS')).not.toBeInTheDocument();
  }, 30000);

  it('keeps the signing code and deadline for a legacy APPROVED application', async () => {
    const id = await seedSignedApplication();
    markLegacyApplication(id);
    setApplicationStatus(id, 'APPROVED');

    renderPage(<MicroloanApplicationsPage />);

    expect(await screen.findByText(/Подписать до/, {}, { timeout: 5000 })).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Подписать' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Получить код по SMS' }, { timeout: 5000 }));
    expect(await screen.findByPlaceholderText('Код из SMS')).toBeInTheDocument();
    expect(screen.queryByText('Ожидаем активацию займа')).not.toBeInTheDocument();
  }, 30000);

  it('offers the contract download for an ACTIVE signed application', async () => {
    await seedSignedApplication();

    renderPage(<MicroloanApplicationsPage />);

    expect(await screen.findByRole('button', { name: 'Скачать договор' }, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Подписать' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Подписать до/)).not.toBeInTheDocument();
  }, 30000);

  it('renders the LOAN_ISSUED notification with the issued-loan title and contract link', async () => {
    await seedSignedApplication();

    renderPage(<NotificationsPage />);

    expect(await screen.findByText('Заём выдан', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Скачать договор (PDF)' })).toBeInTheDocument();
    expect(screen.queryByText(/Вашу заявку на микрозайм одобрили/)).not.toBeInTheDocument();
  }, 30000);
});
