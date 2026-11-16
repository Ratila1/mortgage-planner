import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type MicroloanType, type MicroloanTypeInput } from '../api';
import { Alert, Badge, Button, Card, Field, PageTitle, Spinner, TextInput, cx } from '../components/ui';
import { formatMoneyShort, formatRate, parseMoneyInput } from '../lib/format';

const EMPTY: MicroloanTypeInput = {
  name: '',
  minAmount: '3000',
  maxAmount: '100000',
  gracePeriodDays: 7,
  dailyRatePct: '0.8',
  active: true,
};

function MicroloanTypeForm({
  initial,
  onSubmit,
  onCancel,
}: {
  initial: MicroloanType | null;
  onSubmit: (input: MicroloanTypeInput) => Promise<void>;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<MicroloanTypeInput>(() => {
    if (!initial) return EMPTY;
    const { id: _id, createdAt: _createdAt, updatedAt: _updatedAt, ...input } = initial;
    return input;
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const set = <K extends keyof MicroloanTypeInput>(key: K, value: MicroloanTypeInput[K]) => setForm((f) => ({ ...f, [key]: value }));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!form.name.trim()) return setError('Укажите название типа займа');
    const minAmount = Number(form.minAmount);
    const maxAmount = Number(form.maxAmount);
    if (!(minAmount > 0)) return setError('Минимальная сумма должна быть положительной');
    if (!(maxAmount > 0)) return setError('Максимальная сумма должна быть положительной');
    if (minAmount > maxAmount) return setError('Минимальная сумма не может превышать максимальную');
    if (!(Number.isInteger(form.gracePeriodDays) && form.gracePeriodDays >= 0 && form.gracePeriodDays <= 30)) {
      return setError('Льготный период — целое число от 0 до 30 дней');
    }
    const dailyRate = Number(form.dailyRatePct);
    if (!(Number.isFinite(dailyRate) && dailyRate >= 0)) return setError('Дневная ставка — число не меньше 0');
    setBusy(true);
    setError(null);
    try {
      await onSubmit({ ...form, name: form.name.trim() });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <h2 className="font-display text-lg font-bold">{initial ? `Редактирование: ${initial.name}` : 'Новый тип микрозайма'}</h2>

      <Field label="Название" htmlFor="mlt-name">
        <TextInput id="mlt-name" value={form.name} maxLength={120} onChange={(e) => set('name', e.target.value)} placeholder="Например: Первый займ" />
      </Field>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Мин. сумма" htmlFor="mlt-min">
          <TextInput
            id="mlt-min"
            inputMode="decimal"
            value={form.minAmount}
            suffix="BYN"
            onChange={(e) => set('minAmount', parseMoneyInput(e.target.value))}
          />
        </Field>
        <Field label="Макс. сумма" htmlFor="mlt-max">
          <TextInput
            id="mlt-max"
            inputMode="decimal"
            value={form.maxAmount}
            suffix="BYN"
            onChange={(e) => set('maxAmount', parseMoneyInput(e.target.value))}
          />
        </Field>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Льготный период (дней)" htmlFor="mlt-grace">
          <TextInput
            id="mlt-grace"
            type="number"
            min="0"
            max="30"
            value={form.gracePeriodDays}
            onChange={(e) => set('gracePeriodDays', Math.min(30, Math.max(0, Number(e.target.value) || 0)))}
          />
        </Field>
        <Field label="Дневная ставка, %" htmlFor="mlt-rate">
          <TextInput
            id="mlt-rate"
            inputMode="decimal"
            value={form.dailyRatePct}
            suffix="%"
            onChange={(e) => set('dailyRatePct', parseMoneyInput(e.target.value))}
          />
        </Field>
      </div>

      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" className="h-4 w-4 accent-money" checked={form.active} onChange={(e) => set('active', e.target.checked)} />
        Активен (доступен для расчёта и заявок)
      </label>

      {error && <Alert>{error}</Alert>}
      <div className="flex gap-2">
        <Button type="submit" loading={busy}>{initial ? 'Сохранить' : 'Добавить'}</Button>
        <Button type="button" variant="ghost" onClick={onCancel}>Отмена</Button>
      </div>
    </form>
  );
}

export function AdminMicroloanTypesPage() {
  const [types, setTypes] = useState<MicroloanType[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<MicroloanType | 'new' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const reload = () => api.adminListMicroloanTypes().then(setTypes).catch((err) => setError(errorMessage(err)));
  useEffect(() => { reload(); }, []);

  const save = async (input: MicroloanTypeInput) => {
    if (editing === 'new') {
      await api.adminCreateMicroloanType(input);
      setNotice(`Добавили тип «${input.name}».`);
    } else if (editing) {
      await api.adminUpdateMicroloanType(editing.id, input);
      setNotice('Сохранено.');
    }
    setEditing(null);
    await reload();
  };

  const toggle = async (type: MicroloanType) => {
    try {
      await api.adminToggleMicroloanType(type.id, !type.active);
      setNotice(`${type.active ? 'Деактивировали' : 'Активировали'} «${type.name}».`);
      await reload();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  return (
    <div>
      <PageTitle eyebrow="Админка" title="Типы микрозаймов">
        <Button onClick={() => setEditing('new')}>+ Тип займа</Button>
      </PageTitle>

      {notice && <div className="mb-4"><Alert tone="money">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      <div className="grid gap-6 lg:grid-cols-12">
        <div className={cx(editing ? 'lg:col-span-8' : 'lg:col-span-12')}>
          {types === null && !error ? (
            <div className="grid place-items-center py-24 text-money"><Spinner className="h-8 w-8" /></div>
          ) : (
            <div className="card overflow-x-auto p-0">
              <table className="w-full min-w-[720px] text-sm">
                <thead className="border-b border-line text-left text-xs uppercase tracking-wider text-ink-3">
                  <tr>
                    <th className="p-4 font-semibold">Название</th>
                    <th className="p-4 text-right font-semibold">Сумма, BYN</th>
                    <th className="p-4 text-right font-semibold">Льготный пер., дн.</th>
                    <th className="p-4 text-right font-semibold">Ставка, %/день</th>
                    <th className="p-4 font-semibold">Статус</th>
                    <th className="p-4" />
                  </tr>
                </thead>
                <tbody>
                  {types?.map((t) => (
                    <tr key={t.id} className={cx('border-b border-line/60 last:border-0', !t.active && 'text-ink-3')}>
                      <td className="p-4">
                        <span className="font-semibold">{t.name}</span>
                        <span className="block text-xs text-ink-3">ID: {t.id.slice(0, 12)}…</span>
                      </td>
                      <td className="num p-4 text-right">{formatMoneyShort(t.minAmount)} – {formatMoneyShort(t.maxAmount)}</td>
                      <td className="num p-4 text-right">{t.gracePeriodDays}</td>
                      <td className="num p-4 text-right">{formatRate(t.dailyRatePct)}</td>
                      <td className="p-4">{t.active ? <Badge tone="money">активен</Badge> : <Badge>скрыт</Badge>}</td>
                      <td className="p-4 text-right whitespace-nowrap">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(t)}>Изменить</Button>
                        <Button size="sm" variant="ghost" onClick={() => toggle(t)}>{t.active ? 'Деактивировать' : 'Активировать'}</Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
        {editing && (
          <Card className="lg:col-span-4">
            <MicroloanTypeForm
              key={editing === 'new' ? 'new' : editing.id}
              initial={editing === 'new' ? null : editing}
              onSubmit={save}
              onCancel={() => setEditing(null)}
            />
          </Card>
        )}
      </div>
    </div>
  );
}