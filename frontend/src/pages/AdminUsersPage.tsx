import { useEffect, useState } from 'react';
import { api, errorMessage, type AdminUser } from '../api';
import { Alert, Badge, Button, Card, EmptyState, PageTitle, Spinner, cx } from '../components/ui';
import { formatMoneyShort, formatDateTime } from '../lib/format';

const PROVIDER_LABELS: Record<string, string> = {
  email: 'Email',
  google: 'Google',
  github: 'GitHub',
};

const ROLE_TONES: Record<string, 'money' | 'stamp' | 'gold' | 'neutral'> = {
  ADMIN: 'stamp',
  USER: 'neutral',
};

function RoleBadges({ roles }: { roles: string[] }) {
  return (
    <div className="flex flex-wrap gap-1">
      {roles.map((role) => (
        <Badge key={role} tone={(ROLE_TONES[role] as any) || 'neutral'}>{role}</Badge>
      ))}
    </div>
  );
}

export function AdminUsersPage() {
  const [users, setUsers] = useState<import('../api').AdminUser[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const reload = () => {
    setLoading(true);
    api.adminListUsers()
      .then(setUsers)
      .catch((err) => setError(errorMessage(err)))
      .finally(() => setLoading(false));
  };

  useEffect(() => { reload(); }, []);

  return (
    <div>
      <PageTitle eyebrow="Панель админа" title="Пользователи" />
      {error && <Alert className="mb-4">{error}</Alert>}

      {loading ? (
        <div className="grid place-items-center py-24 text-money"><Spinner className="h-8 w-8" /></div>
      ) : users && users.length === 0 ? (
        <Card>
          <EmptyState
            title="Пользователей пока нет"
            text="В системе пока нет зарегистрированных пользователей."
            emoji="👥"
          />
        </Card>
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[960px] text-sm">
              <thead className="bg-paper-2/70 text-left text-xs uppercase tracking-wider text-ink-3">
                <tr>
                  <th className="p-4 font-semibold">ID</th>
                  <th className="p-4 font-semibold">Email</th>
                  <th className="p-4 font-semibold">ФИО</th>
                  <th className="p-4 text-right font-semibold">Зарплата</th>
                  <th className="p-4 font-semibold">Провайдер</th>
                  <th className="p-4 font-semibold">Создан</th>
                  <th className="p-4 font-semibold">Телефон</th>
                  <th className="p-4 font-semibold">Роли</th>
                </tr>
              </thead>
              <tbody>
                {users?.map((user) => (
                  <tr key={user.id} className="border-t border-line/60 last:border-0">
                    <td className="p-4 font-mono text-xs text-ink-3">{user.id}</td>
                    <td className="p-4">
                      <span className="font-semibold">{user.email}</span>
                    </td>
                    <td className="p-4">{user.fullName || '—'}</td>
                    <td className="num p-4 text-right">{user.salary ? formatMoneyShort(user.salary) : '—'}</td>
                    <td className="p-4">
                      <Badge tone="gold">{user.provider ? PROVIDER_LABELS[user.provider] : '—'}</Badge>
                    </td>
                    <td className="p-4 text-ink-3 whitespace-nowrap">{formatDateTime(user.createdAt)}</td>
                    <td className="p-4">{user.phone || '—'}</td>
                    <td className="p-4"><RoleBadges roles={user.roles} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  );
}