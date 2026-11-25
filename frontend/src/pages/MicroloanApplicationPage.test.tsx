import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api } from '../api';
import { resetMockState } from '../api/mock';
import type { PhoneVerifyPanelProps } from '../components/PhoneVerifyPanel';
import { MicroloanApplicationPage } from './MicroloanApplicationPage';

/** Capture the page callback through the component's public props, not React internals. */
const captured = vi.hoisted(() => ({
  onVerified: undefined as (() => void | Promise<void>) | undefined,
}));

vi.mock('../components/PhoneVerifyPanel', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../components/PhoneVerifyPanel')>();
  return {
    ...actual,
    PhoneVerifyPanel: (props: PhoneVerifyPanelProps) => {
      captured.onVerified = props.onVerified;
      return <actual.PhoneVerifyPanel {...props} />;
    },
  };
});

const PREFILL = { typeId: 'ml-type-1', amount: '500.00', termDays: 14 };
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
const SERVER_ERROR = /На сервере что-то сломалось/;

function deferred() {
  let release!: () => void;
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

function spyCallThrough<K extends 'createMicroloanApplication' | 'uploadPassport'>(method: K) {
  const real = api[method].bind(api);
  // Both methods accept a single argument of different types; the cast keeps the spy generic.
  return vi.spyOn(api, method).mockImplementation(((arg: never) => real(arg)) as never);
}

async function renderForm() {
  resetMockState();
  await api.login('client@example.com', 'password123');
  await api.uploadPassport(new File(['passport'], 'passport.png', { type: 'image/png' }));
  render(
    <MemoryRouter initialEntries={[{ pathname: '/microloan/application', state: PREFILL }]}>
      <Routes>
        <Route path="/microloan/application" element={<MicroloanApplicationPage />} />
        <Route path="/cabinet/applications" element={<p>Список заявок открыт</p>} />
      </Routes>
    </MemoryRouter>,
  );
  await screen.findByLabelText('Фамилия', {}, { timeout: 5000 });
}

function fillForm(phone = PROFILE.phone) {
  fireEvent.change(screen.getByLabelText('Фамилия'), { target: { value: PROFILE.lastName } });
  fireEvent.change(screen.getByLabelText('Имя'), { target: { value: PROFILE.firstName } });
  fireEvent.change(screen.getByLabelText('Телефон'), { target: { value: phone } });
  fireEvent.change(screen.getByLabelText('Зарплата в месяц'), { target: { value: PROFILE.monthlyIncome } });
  fireEvent.change(screen.getByLabelText('Дата рождения'), { target: { value: PROFILE.birthDate } });
  fireEvent.change(screen.getByRole('combobox'), { target: { value: PROFILE.sex } });
  fireEvent.change(screen.getByLabelText('Город'), { target: { value: PROFILE.city } });
}

async function requestCode() {
  fireEvent.click(screen.getByRole('button', { name: 'Подтвердить телефон' }));
  await screen.findByRole('button', { name: /Подтвердить телефон \(\d+с\)/ }, { timeout: 5000 });
  return screen.getByPlaceholderText('Код из SMS');
}

function confirmWith(code: string) {
  fireEvent.change(screen.getByPlaceholderText('Код из SMS'), { target: { value: code } });
  fireEvent.click(screen.getByRole('button', { name: 'Подтвердить и отправить' }));
}

describe('MicroloanApplicationPage phone gate', () => {
  afterEach(() => {
    cleanup();
    captured.onVerified = undefined;
    vi.restoreAllMocks();
  });

  it('blocks submit until a valid code, then creates one SUBMITTED application', async () => {
    const createSpy = spyCallThrough('createMicroloanApplication');
    await renderForm();
    fillForm();

    expect(screen.getByRole('button', { name: 'Подтвердить и отправить' })).toBeDisabled();
    expect(createSpy).not.toHaveBeenCalled();

    const codeInput = await requestCode();
    fireEvent.change(codeInput, { target: { value: '123' } });
    expect(screen.getByRole('button', { name: 'Подтвердить и отправить' })).toBeDisabled();
    expect(createSpy).not.toHaveBeenCalled();

    fireEvent.change(codeInput, { target: { value: '1234' } });
    // Editing any field except the phone must not discard the code or the SMS cooldown.
    fireEvent.change(screen.getByLabelText('Город'), { target: { value: 'Гродно' } });
    expect(codeInput).toHaveValue('1234');
    expect(screen.getByRole('button', { name: /Подтвердить телефон \(\d+с\)/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Подтвердить и отправить' })).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить и отправить' }));

    expect(await screen.findByText('Список заявок открыт', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(createSpy).toHaveBeenCalledTimes(1);
    const applications = await api.listMyMicroloanApplications();
    expect(applications).toHaveLength(1);
    expect(applications[0].status).toBe('SUBMITTED');
    const profile = await api.getProfile();
    expect(profile?.phone).toBe(PROFILE.phone);
    expect(profile?.city).toBe('Гродно');
  }, 20000);

  it('keeps the filled form and creates nothing when the code is wrong', async () => {
    const createSpy = spyCallThrough('createMicroloanApplication');
    await renderForm();
    fillForm();
    await requestCode();
    confirmWith('0000');

    expect(await screen.findByText(/Код неверный/, {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByLabelText('Фамилия')).toHaveValue(PROFILE.lastName);
    expect(createSpy).not.toHaveBeenCalled();
    expect(await api.listMyMicroloanApplications()).toHaveLength(0);
  }, 20000);

  it('does not submit when SMS delivery fails', async () => {
    const createSpy = spyCallThrough('createMicroloanApplication');
    vi.spyOn(api, 'requestPhoneVerification').mockRejectedValueOnce(
      new ApiError({ status: 503, code: 'SMS_DELIVERY_FAILED' }),
    );
    await renderForm();
    fillForm();
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить телефон' }));

    expect(await screen.findByText(/Не удалось отправить SMS/, {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByLabelText('Фамилия')).toHaveValue(PROFILE.lastName);
    expect(createSpy).not.toHaveBeenCalled();
    expect(await api.listMyMicroloanApplications()).toHaveLength(0);
  }, 20000);

  it('clears a pending code when the phone changes before confirmation', async () => {
    await renderForm();
    fillForm();
    const codeInput = await requestCode();
    fireEvent.change(codeInput, { target: { value: '1234' } });
    fireEvent.change(screen.getByLabelText('Телефон'), { target: { value: '+375291111111' } });

    await waitFor(() => expect(screen.getByPlaceholderText('Код из SMS')).toHaveValue(''));
    expect(screen.getByRole('button', { name: 'Подтвердить телефон' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить и отправить' }));
    expect(await api.listMyMicroloanApplications()).toHaveLength(0);

    const newCodeInput = await requestCode();
    fireEvent.change(newCodeInput, { target: { value: '1234' } });
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить и отправить' }));

    expect(await screen.findByText('Список заявок открыт', {}, { timeout: 5000 })).toBeInTheDocument();
    expect((await api.getProfile())?.phone).toBe('+375291111111');
    expect(await api.listMyMicroloanApplications()).toHaveLength(1);
  }, 30000);

  it('does not submit when the phone changes after confirmation but before submit', async () => {
    const realGetProfile = api.getProfile.bind(api);
    const profileRefreshed = deferred();
    const profileGate = deferred();
    let profileCalls = 0;
    vi.spyOn(api, 'getProfile').mockImplementation(async () => {
      profileCalls += 1;
      if (profileCalls === 2) {
        profileRefreshed.release();
        await profileGate.promise;
      }
      return realGetProfile();
    });
    const createSpy = spyCallThrough('createMicroloanApplication');

    await renderForm();
    fillForm();
    await requestCode();
    confirmWith('1234');
    // The code is already confirmed and afterPhoneVerified is waiting for the profile refresh.
    await act(async () => {
      await profileRefreshed.promise;
    });
    fireEvent.change(screen.getByLabelText('Телефон'), { target: { value: '+375292222222' } });
    await act(async () => {
      profileGate.release();
    });

    expect(
      await screen.findByText(/Телефон изменился\. Запросите новый код/, {}, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(screen.queryByText('Список заявок открыт')).not.toBeInTheDocument();
    expect(createSpy).not.toHaveBeenCalled();
    expect(await api.listMyMicroloanApplications()).toHaveLength(0);
  }, 20000);

  it('does not create a duplicate application when confirmation fires twice', async () => {
    const realCreate = api.createMicroloanApplication.bind(api);
    const firstCreateStarted = deferred();
    const createGate = deferred();
    let createCalls = 0;
    const createSpy = vi.spyOn(api, 'createMicroloanApplication').mockImplementation(async (input) => {
      createCalls += 1;
      if (createCalls === 1) {
        firstCreateStarted.release();
        await createGate.promise;
      }
      return realCreate(input);
    });

    const realGetProfile = api.getProfile.bind(api);
    const secondConfirmation = deferred();
    const secondProfileGate = deferred();
    let profileCalls = 0;
    vi.spyOn(api, 'getProfile').mockImplementation(async () => {
      profileCalls += 1;
      if (profileCalls === 3) {
        const saved = await realGetProfile();
        secondConfirmation.release();
        await secondProfileGate.promise;
        return saved;
      }
      return realGetProfile();
    });

    await renderForm();
    fillForm();
    await requestCode();
    confirmWith('1234');
    await act(async () => {
      await firstCreateStarted.promise;
    });

    // A repeated confirmation while the first submit is in flight must be ignored.
    // The UI disables the button, so invoke the same onVerified callback the panel uses.
    let secondConfirm: void | Promise<void>;
    await act(async () => {
      secondConfirm = captured.onVerified!();
    });
    await act(async () => {
      await secondConfirmation.promise;
    });
    await act(async () => {
      secondProfileGate.release();
      await secondConfirm!;
    });

    expect(createSpy).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Список заявок открыт')).not.toBeInTheDocument();

    await act(async () => {
      createGate.release();
    });
    expect(await screen.findByText('Список заявок открыт', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(await api.listMyMicroloanApplications()).toHaveLength(1);
  }, 20000);

  it('shows an error and keeps the form when saving the profile fails', async () => {
    const createSpy = spyCallThrough('createMicroloanApplication');
    vi.spyOn(api, 'saveProfile').mockRejectedValueOnce(new ApiError({ status: 500, code: 'INTERNAL_ERROR' }));
    await renderForm();
    fillForm();
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить телефон' }));

    expect(await screen.findByText(SERVER_ERROR, {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByLabelText('Фамилия')).toHaveValue(PROFILE.lastName);
    expect(screen.getByLabelText('Телефон')).toHaveValue(PROFILE.phone);
    expect(screen.getByRole('button', { name: 'Подтвердить телефон' })).toBeEnabled();
    expect(createSpy).not.toHaveBeenCalled();
    expect(await api.listMyMicroloanApplications()).toHaveLength(0);
  }, 20000);

  it('allows a retry and creates one application when submit fails after the code', async () => {
    const realCreate = api.createMicroloanApplication.bind(api);
    const createSpy = vi
      .spyOn(api, 'createMicroloanApplication')
      .mockRejectedValueOnce(new ApiError({ status: 500, code: 'INTERNAL_ERROR' }))
      .mockImplementation((input) => realCreate(input));

    await renderForm();
    fillForm();
    await requestCode();
    confirmWith('1234');

    expect(await screen.findByText(SERVER_ERROR, {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByLabelText('Фамилия')).toHaveValue(PROFILE.lastName);
    expect(createSpy).toHaveBeenCalledTimes(1);
    expect(await api.listMyMicroloanApplications()).toHaveLength(0);

    const retryCodeInput = await requestCode();
    fireEvent.change(retryCodeInput, { target: { value: '1234' } });
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить и отправить' }));

    expect(await screen.findByText('Список заявок открыт', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(createSpy).toHaveBeenCalledTimes(2);
    expect(await api.listMyMicroloanApplications()).toHaveLength(1);
  }, 25000);

  it('uploads a passport chosen after the code was requested', async () => {
    await renderForm();
    const realUpload = api.uploadPassport.bind(api);
    const uploadSpy = vi.spyOn(api, 'uploadPassport').mockImplementation((file) => realUpload(file));
    fillForm();
    await requestCode();

    const newFile = new File(['new passport'], 'new-passport.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText('Выбрать файл'), { target: { files: [newFile] } });
    expect(screen.getByLabelText('Выбран файл: new-passport.png')).toBeInTheDocument();
    expect(uploadSpy).not.toHaveBeenCalled();

    confirmWith('1234');
    expect(await screen.findByText('Список заявок открыт', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(uploadSpy).toHaveBeenCalledTimes(1);
    expect(uploadSpy).toHaveBeenCalledWith(newFile);
    expect(await api.listMyMicroloanApplications()).toHaveLength(1);
  }, 20000);
});
