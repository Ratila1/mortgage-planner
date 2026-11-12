import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type BankOffer, type OfferInput } from '../api';
import { Alert, Badge, Button, Card, Field, PageTitle, Spinner, TextInput, cx } from '../components/ui';
import { formatDateTime, formatRate, formatTerm, parseMoneyInput } from '../lib/format';

const EMPTY: OfferInput = { bankName: '', rate: '', minDownPaymentPercent: '20', maxTermMonths: 300, active: true };

function OfferForm({
  initial,
  onSubmit,
  onCancel,
}: {
  initial: BankOffer | null;
  onSubmit: (input: OfferInput) => Promise<void>;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<OfferInput>(() => {
    if (!initial) return EMPTY;
    const { id: _id, updatedAt: _updatedAt, ...input } = initial;
    return input;
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const rateChanged = initial !== null && form.rate !== initial.rate;

  const set = <K extends keyof OfferInput>(key: K, value: OfferInput[K]) => setForm((f) => ({ ...f, [key]: value }));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!form.bankName.trim()) return setError('Укажите название банка');
    if (!(Number(form.rate) >= 0 && Number(form.rate) <= 100)) return setError('Ставка — от 0 до 100 %');
    setBusy(true);
    setError(null);
    try {
      await onSubmit({ ...form, bankName: form.bankName.trim() });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <h2 className="font-display text-lg font-bold">{initial ? `Редактирование: ${initial.bankName}` : 'Новое предложение'}</h2>
      <Field label="Банк" htmlFor="bank">
        <TextInput id="bank" value={form.bankName} maxLength={120} onChange={(e) => set('bankName', e.target.value)} />
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Ставка" htmlFor="offer-rate">
          <TextInput id="offer-rate" inputMode="decimal" value={form.rate} suffix="%" onChange={(e) => set('rate', parseMoneyInput(e.target.value))} />
        </Field>
        <Field label="Мин. взнос" htmlFor="offer-down">
          <TextInput id="offer-down" inputMode="decimal" value={form.minDownPaymentPercent} suffix="%" onChange={(e) => set('minDownPaymentPercent', parseMoneyInput(e.target.value))} />
        </Field>
      </div>
      <Field label="Максимальный срок" htmlFor="offer-term" hint={formatTerm(form.maxTermMonths)}>
        <TextInput
          id="offer-term"
          type="number"
          min={1}
          max={360}
          value={form.maxTermMonths}
          suffix="мес."
          onChange={(e) => set('maxTermMonths', Math.min(360, Math.max(1, Number(e.target.value) || 1)))}
        />
      </Field>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" className="h-4 w-4 accent-money" checked={form.active} onChange={(e) => set('active', e.target.checked)} />
        Показывать пользователям
      </label>
      {rateChanged && (
        <Alert tone="gold">
          Ставка меняется {formatRate(initial?.rate ?? 0)} → {formatRate(form.rate || 0)}. Все сценарии с этим банком будут пересчитаны,
          а их владельцы получат уведомление и письмо.
        </Alert>
      )}
      {error && <Alert>{error}</Alert>}
      <div className="flex gap-2">
        <Button type="submit" loading={busy}>{initial ? 'Сохранить' : 'Добавить'}</Button>
        <Button type="button" variant="ghost" onClick={onCancel}>Отмена</Button>
      </div>
    </form>
  );
}

export function AdminOffersPage() {
  const [offers, setOffers] = useState<BankOffer[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<BankOffer | 'new' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const reload = () => api.adminListOffers().then(setOffers).catch((err) => setError(errorMessage(err)));
  useEffect(() => {
    reload();
  }, []);

  const save = async (input: OfferInput) => {
    if (editing === 'new') {
      await api.adminCreateOffer(input);
      setNotice(`Добавили «${input.bankName}».`);
    } else if (editing) {
      const rateChanged = editing.rate !== input.rate;
      await api.adminUpdateOffer(editing.id, input);
      setNotice(rateChanged ? 'Ставка обновлена. Пересчёт сценариев и уведомления уже в очереди.' : 'Сохранено.');
    }
    setEditing(null);
    await reload();
  };

  const remove = async (offer: BankOffer) => {
    if (!confirm(`Удалить «${offer.bankName}»? Сценарии с ним останутся, но без привязки к банку.`)) return;
    try {
      await api.adminDeleteOffer(offer.id);
      setNotice(`Удалили «${offer.bankName}».`);
      await reload();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  return (
    <div>
      <PageTitle eyebrow="Админка" title="Пульт управления чужими ставками">
        <Button onClick={() => setEditing('new')}>+ Предложение</Button>
      </PageTitle>
      {notice && <div className="mb-4"><Alert tone="money">{notice}</Alert></div>}
      {error && <div className="mb-4"><Alert>{error}</Alert></div>}

      <div className="grid gap-6 lg:grid-cols-12">
        <div className={cx(editing ? 'lg:col-span-8' : 'lg:col-span-12')}>
          {offers === null && !error ? (
            <div className="grid place-items-center py-24 text-money"><Spinner className="h-8 w-8" /></div>
          ) : (
            <div className="card overflow-x-auto p-0">
              <table className="w-full min-w-[640px] text-sm">
                <thead className="border-b border-line text-left text-xs uppercase tracking-wider text-ink-3">
                  <tr>
                    <th className="p-4 font-semibold">Банк</th>
                    <th className="p-4 text-right font-semibold">Ставка</th>
                    <th className="p-4 text-right font-semibold">Взнос от</th>
                    <th className="p-4 text-right font-semibold">Срок до</th>
                    <th className="p-4 font-semibold">Статус</th>
                    <th className="p-4" />
                  </tr>
                </thead>
                <tbody>
                  {offers?.map((o) => (
                    <tr key={o.id} className={cx('border-b border-line/60 last:border-0', !o.active && 'text-ink-3')}>
                      <td className="p-4">
                        <span className="font-semibold">{o.bankName}</span>
                        <span className="block text-xs text-ink-3">обновлено {formatDateTime(o.updatedAt)}</span>
                      </td>
                      <td className="num p-4 text-right font-bold">{formatRate(o.rate)}</td>
                      <td className="num p-4 text-right">{formatRate(o.minDownPaymentPercent)}</td>
                      <td className="p-4 text-right">{formatTerm(o.maxTermMonths)}</td>
                      <td className="p-4">{o.active ? <Badge tone="money">активно</Badge> : <Badge>скрыто</Badge>}</td>
                      <td className="p-4 text-right whitespace-nowrap">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(o)}>Изменить</Button>
                        <Button size="sm" variant="ghost" onClick={() => remove(o)}>Удалить</Button>
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
            <OfferForm
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
