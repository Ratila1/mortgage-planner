import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, errorMessage, type AdminUser, type LoginProvider } from '../api';
import { useAuth } from '../auth/AuthContext';
import { Alert, Badge, Button, Card, ConfirmDialog, EmptyState, PageTitle, Select, Skeleton, TextInput } from '../components/ui';
import { formatMoneyShort, formatDateTime } from '../lib/format';

/** Способ входа хранится в users.provider и приходит с бэка как есть. */
const PROVIDER_LABELS: Record<LoginProvider, string> = {
  LOCAL: 'Email',
  GOOGLE: 'Google',
  GITHUB: 'GitHub',
};

const ROLE_TONES: Record<string, 'money' | 'stamp' | 'gold' | 'neutral'> = {
  ADMIN: 'stamp',
  USER: 'neutral',
};

function RoleBadges({ roles }: { roles: string[] }) {
  return (
    <div className="flex flex-wrap gap-1">
      {roles.map((role) => (
        <Badge key={role} tone={ROLE_TONES[role] ?? 'neutral'}>{role}</Badge>
      ))}
    </div>
  );
}

export function AdminUsersPage() {
  const { user: me } = useAuth();
  const [users, setUsers] = useState<AdminUser[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<AdminUser | null>(null);
  const [loading, setLoading] = useState(true);

  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState<'ALL' | 'ADMIN' | 'USER'>('ALL');
  const [providerFilter, setProviderFilter] = useState<'ALL' | LoginProvider>('ALL');
  const [blockedFilter, setBlockedFilter] = useState<'ALL' | 'BLOCKED' | 'ACTIVE'>('ALL');
  const [sort, setSort] = useState<'date_desc' | 'date_asc' | 'email' | 'name'>('date_desc');

  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(t);
  }, [search]);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setUsers(await api.adminListUsers(debouncedSearch));
      setError(null);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [debouncedSearch]);

  useEffect(() => { reload(); }, [reload]);

  const filteredUsers = useMemo(() => {
    if (!users) return null;
    let list = [...users];
    if (roleFilter !== 'ALL') list = list.filter((u) => u.roles.includes(roleFilter as 'ADMIN' | 'USER'));
    if (providerFilter !== 'ALL') list = list.filter((u) => u.provider === (providerFilter as LoginProvider));
    if (blockedFilter === 'BLOCKED') list = list.filter((u) => u.blocked);
    if (blockedFilter === 'ACTIVE') list = list.filter((u) => !u.blocked);
    switch (sort) {
      case 'date_asc': list.sort((a, b) => a.createdAt.localeCompare(b.createdAt)); break;
      case 'email': list.sort((a, b) => a.email.localeCompare(b.email)); break;
      case 'name': list.sort((a, b) => (a.fullName ?? '').localeCompare(b.fullName ?? '')); break;
      default: list.sort((a, b) => b.createdAt.localeCompare(a.createdAt)); break;
    }
    return list;
  }, [users, roleFilter, providerFilter, blockedFilter, sort]);

  const toggleBlock = async (user: AdminUser) => {
    setBusyId(user.id);
    setError(null);
    setNotice(null);
    try {
      if (user.blocked) {
        await api.adminUnblockUser(user.id);
        setNotice(`Разблокировали «${user.email}».`);
      } else {
        await api.adminBlockUser(user.id);
        setNotice(`Заблокировали «${user.email}». Его сессии отозваны.`);
      }
      setUsers((prev) => prev?.map((u) => (u.id === user.id ? { ...u, blocked: !u.blocked } : u)) ?? prev);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
      setConfirmTarget(null);
    }
  };

  return (
    <div>
      <PageTitle eyebrow="Панель админа" title="Пользователи" />
      {notice && <div className="mb-4"><Alert tone="money">{notice}</Alert></div>}
      {error && <Alert className="mb-4">{error}</Alert>}
      <div className="mb-4 flex flex-wrap items-end gap-2">
        <TextInput placeholder="Поиск по email, имени, телефону..." value={search} onChange={(e) => setSearch(e.target.value)} className="w-64" />
        <Select value={roleFilter} options={[{ value: 'ALL', label: 'Все роли' }, { value: 'ADMIN', label: 'ADMIN' }, { value: 'USER', label: 'USER' }]} onChange={(v) => setRoleFilter(v as typeof roleFilter)} className="w-32" />
        <Select value={providerFilter} options={[{ value: 'ALL', label: 'Все провайдеры' }, { value: 'LOCAL', label: 'Email' }, { value: 'GOOGLE', label: 'Google' }, { value: 'GITHUB', label: 'GitHub' }]} onChange={(v) => setProviderFilter(v as typeof providerFilter)} className="w-40" />
        <Select value={blockedFilter} options={[{ value: 'ALL', label: 'Все' }, { value: 'ACTIVE', label: 'Активные' }, { value: 'BLOCKED', label: 'Заблокированные' }]} onChange={(v) => setBlockedFilter(v as typeof blockedFilter)} className="w-40" />
        <Select value={sort} options={[{ value: 'date_desc', label: 'Дата (новые)' }, { value: 'date_asc', label: 'Дата (старые)' }, { value: 'email', label: 'Email' }, { value: 'name', label: 'Имя' }]} onChange={(v) => setSort(v as typeof sort)} className="w-40" />
      </div>

      {loading ? (
        <div className="space-y-3" aria-hidden="true">
          <Skeleton className="h-12" />
          <Skeleton className="h-64" />
        </div>
      ) : !filteredUsers || filteredUsers.length === 0 ? (
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
                  <th className="p-4 font-semibold">Действия</th>
                </tr>
              </thead>
              <tbody>
                {filteredUsers?.map((user) => (
                  <tr key={user.id} className="border-t border-line/60 last:border-0">
                    <td className="p-4 font-mono text-xs text-ink-3">{user.id}</td>
                    <td className="p-4">
                      <span className="font-semibold">{user.email}</span>
                      {user.blocked && <span className="mt-1 block"><Badge tone="stamp">заблокирован</Badge></span>}
                    </td>
                    <td className="p-4">{user.fullName || '—'}</td>
                    <td className="num p-4 text-right">{user.salary ? formatMoneyShort(user.salary) : '—'}</td>
                    <td className="p-4">
                      <Badge tone="gold">{PROVIDER_LABELS[user.provider] ?? user.provider}</Badge>
                    </td>
                    <td className="p-4 text-ink-3 whitespace-nowrap">{formatDateTime(user.createdAt)}</td>
                    <td className="p-4">{user.phone || '—'}</td>
                    <td className="p-4"><RoleBadges roles={user.roles} /></td>
                    <td className="p-4">
                      {/* Себя не блокируем — бэкен это запрещает (SELF_BLOCK_FORBIDDEN), кнопку прячем. */}
                      {user.id === me?.id ? (
                        <span className="text-xs text-ink-3">это вы</span>
                      ) : (
                        <Button
                          size="sm"
                          variant={user.blocked ? 'secondary' : 'ghost'}
                          loading={busyId === user.id}
                          onClick={() => setConfirmTarget(user)}
                        >
                          {user.blocked ? 'Разблокировать' : 'Заблокировать'}
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <ConfirmDialog
        open={confirmTarget !== null}
        title={confirmTarget?.blocked ? 'Разблокировать пользователя?' : 'Заблокировать пользователя?'}
        danger={!confirmTarget?.blocked}
        busy={busyId === confirmTarget?.id}
        confirmLabel={confirmTarget?.blocked ? 'Разблокировать' : 'Заблокировать'}
        onConfirm={() => confirmTarget && toggleBlock(confirmTarget)}
        onCancel={() => setConfirmTarget(null)}
      >
        {confirmTarget && (
          <p>
            {confirmTarget.blocked
              ? <>Пользователь <span className="font-semibold">{confirmTarget.email}</span> снова сможет войти.</>
              : <>Пользователь <span className="font-semibold">{confirmTarget.email}</span> потеряет доступ, его сессии будут отозваны.</>}
          </p>
        )}
      </ConfirmDialog>
    </div>
  );
}
