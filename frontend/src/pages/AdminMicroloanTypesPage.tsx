import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type MicroloanType, type MicroloanTypeInput } from '../api';
import { Alert, Badge, Button, Card, Field, PageTitle, Skeleton, TextInput, cx } from '../components/ui';
import { formatMoneyShort, formatRate, parseMoneyInput } from '../lib/format';

const EMPTY: MicroloanTypeInput = {
  name: '',
  description: '',
  minAmount: '50',
  maxAmount: '1000',
  minTermDays: 7,
  maxTermDays: 30,
  dailyRatePct: '0.8',
  modifier: '0',
  purposeScore: '0.50',
  active: true,
};

/** Срок микрозайма по требованиям — от 7 до 30 дней (MICROLOANS.md §3.1), бэк проверяет то же. */
const TERM_MIN = 7;
const TERM_MAX = 30;
const clampTerm = (v: number) => Math.min(TERM_MAX, Math.max(TERM_MIN, Math.round(v) || TERM_MIN));

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
    const { id: _id, version: _version, ...input } = initial;
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
    if (!form.description.trim()) return setError('Укажите описание');
    if (form.minTermDays > form.maxTermDays) return setError('Мин. срок не может превышать максимальный');
    if (!(Number(form.modifier) >= 0)) return setError('Модификатор — число не меньше 0');
    const purposeScore = Number(form.purposeScore ?? '0.50');
    if (!(purposeScore >= 0 && purposeScore <= 1)) return setError('Балл цели — число от 0 до 1');
    const dailyRate = Number(form.dailyRatePct);
    if (!(Number.isFinite(dailyRate) && dailyRate >= 0)) return setError('Дневная ставка — число не меньше 0');
    setBusy(true);
    setError(null);
    try {
      await onSubmit({ ...form, name: form.name.trim(), description: form.description.trim() });
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
        <TextInput id="mlt-name" value={form.name} maxLength={120} onChange={(e) => set('name', e.target.value)} placeholder="Например: До зарплаты" />
      </Field>

      <Field label="Описание" htmlFor="mlt-desc">
        <TextInput id="mlt-desc" value={form.description} maxLength={1000} onChange={(e) => set('description', e.target.value)} />
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
        <Field label="Мин. срок, дней" htmlFor="mlt-term-min">
          <TextInput
            id="mlt-term-min"
            type="number"
            min={TERM_MIN}
            max={TERM_MAX}
            value={form.minTermDays}
            onChange={(e) => set('minTermDays', clampTerm(Number(e.target.value)))}
          />
        </Field>
        <Field label="Макс. срок, дней" htmlFor="mlt-term-max">
          <TextInput
            id="mlt-term-max"
            type="number"
            min={TERM_MIN}
            max={TERM_MAX}
            value={form.maxTermDays}
            onChange={(e) => set('maxTermDays', clampTerm(Number(e.target.value)))}
          />
        </Field>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Дневная ставка, %" htmlFor="mlt-rate">
          <TextInput
            id="mlt-rate"
            inputMode="decimal"
            value={form.dailyRatePct}
            suffix="%"
            onChange={(e) => set('dailyRatePct', parseMoneyInput(e.target.value))}
          />
        </Field>
        <Field label="Модификатор (надбавка)" htmlFor="mlt-mod">
          <TextInput
            id="mlt-mod"
            inputMode="decimal"
            value={form.modifier}
            suffix="BYN"
            onChange={(e) => set('modifier', parseMoneyInput(e.target.value))}
          />
        </Field>
        <Field label="Балл цели для скоринга" htmlFor="mlt-purpose" hint="0–1: чем надёжнее цель, тем выше">
          <TextInput
            id="mlt-purpose"
            inputMode="decimal"
            value={form.purposeScore ?? '0.50'}
            onChange={(e) => set('purposeScore', parseMoneyInput(e.target.value))}
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
      await api.adminUpdateMicroloanType(editing.id, input, editing.version);
      setNotice('Сохранено.');
    }
    setEditing(null);
    await reload();
  };

  const toggle = async (type: MicroloanType) => {
    try {
      // Выключение — отдельный эндпоинт; включение — обычное обновление с active=true.
      if (type.active) {
        await api.adminDeactivateMicroloanType(type.id);
      } else {
        const { id, version, ...input } = type;
        await api.adminUpdateMicroloanType(id, { ...input, active: true }, version);
      }
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
        {/* min-w-0: иначе grid-ячейка наследует минимальную ширину таблицы
            и вылезает за карточку при узком окне. Скролл остаётся внутри. */}
        <div className={cx('min-w-0', editing ? 'lg:col-span-8' : 'lg:col-span-12')}>
          {types === null && !error ? (
            <div className="space-y-3" aria-hidden="true">
              <Skeleton className="h-12" />
              <Skeleton className="h-64" />
            </div>
          ) : (
            <div className="card overflow-x-auto p-0">
              <table className="w-full text-sm">
                <thead className="border-b border-line text-left text-xs uppercase tracking-wider text-ink-3">
                  <tr>
                    <th className="p-3 font-semibold">Название</th>
                    <th className="p-3 text-right font-semibold">Сумма, BYN</th>
                    <th className="p-3 text-right font-semibold">Срок, дн.</th>
                    <th className="p-3 text-right font-semibold">Ставка, %/день</th>
                    <th className="p-3 text-right font-semibold">Модификатор</th>
                    <th className="p-3 text-right font-semibold">Балл цели</th>
                    <th className="p-3 font-semibold">Статус</th>
                    <th className="p-3" />
                  </tr>
                </thead>
                <tbody>
                  {types?.map((t) => (
                    <tr key={t.id} className={cx('border-b border-line/60 last:border-0', !t.active && 'text-ink-3')}>
                      <td className="p-3">
                        <span className="font-semibold">{t.name}</span>
                        <span className="block max-w-xs text-xs text-ink-3">{t.description}</span>
                      </td>
                      <td className="num p-3 text-right">{formatMoneyShort(t.minAmount)} – {formatMoneyShort(t.maxAmount)}</td>
                      <td className="num p-3 text-right">{t.minTermDays} – {t.maxTermDays}</td>
                      <td className="num p-3 text-right">{formatRate(t.dailyRatePct)}</td>
                      <td className="num p-3 text-right">+{formatMoneyShort(t.modifier)}</td>
                      <td className="num p-3 text-right">{t.purposeScore ?? '—'}</td>
                      <td className="p-3">{t.active ? <Badge tone="money">активен</Badge> : <Badge>скрыт</Badge>}</td>
                      <td className="p-3 text-right whitespace-nowrap">
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