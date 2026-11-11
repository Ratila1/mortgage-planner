import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { api, errorMessage, type CalcParams } from '../../api';
import { useAuth } from '../../auth/AuthContext';
import { Alert, Button, TextInput } from '../ui';

export function SaveScenario({ params, offerId }: { params: CalcParams; offerId: number | null }) {
  const { user } = useAuth();
  const [name, setName] = useState('');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!user) {
    return (
      <p className="text-sm text-ink-2">
        <Link to="/login" state={{ from: '/' }} className="font-semibold text-money underline underline-offset-4">Войдите</Link>, чтобы
        сохранить сценарий, сравнить его с другими и узнать первыми, когда банк поменяет ставку.
      </p>
    );
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const scenario = await api.createScenario(name.trim() || 'Без названия', params, offerId);
      setSaved(scenario.name);
      setName('');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-3">
      <div className="flex flex-col gap-2 sm:flex-row">
        <TextInput
          className="flex-1"
          value={name}
          maxLength={120}
          placeholder="Например: «Двушка, если повезёт»"
          aria-label="Название сценария"
          onChange={(e) => setName(e.target.value)}
        />
        <Button type="submit" loading={saving}>Сохранить сценарий</Button>
      </div>
      {saved && (
        <Alert tone="money">
          Сохранили «{saved}». <Link to="/scenarios" className="font-semibold underline underline-offset-4">Все сценарии →</Link>
        </Alert>
      )}
      {error && <Alert>{error}</Alert>}
    </form>
  );
}
