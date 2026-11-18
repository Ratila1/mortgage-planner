import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { Skeleton } from './ui';

/** Заглушка на время восстановления сессии — той же формы, что типовая страница. */
const LoadingSkeleton = () => (
  <div className="space-y-4" aria-hidden="true">
    <Skeleton className="h-10 w-64 max-w-full" />
    <Skeleton className="h-64" />
  </div>
);

/** admin — только для админа; userOnly — только для клиента (админа уводим в его панель). */
export function RequireAuth({
  children,
  admin = false,
  userOnly = false,
}: {
  children: ReactNode;
  admin?: boolean;
  userOnly?: boolean;
}) {
  const { user, loading, isAdmin } = useAuth();
  const location = useLocation();
  if (loading) return <LoadingSkeleton />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (admin && !isAdmin) return <Navigate to="/" replace />;
  if (userOnly && isAdmin) return <Navigate to="/admin" replace />;
  return <>{children}</>;
}

/**
 * Разделы клиента (ипотечный калькулятор, микрозаймы, личный кабинет) админу не нужны:
 * у него своя панель. Гостей пропускаем — калькуляторы публичные.
 */
export function NotForAdmin({ children }: { children: ReactNode }) {
  const { loading, isAdmin } = useAuth();
  if (loading) return <LoadingSkeleton />;
  if (isAdmin) return <Navigate to="/admin" replace />;
  return <>{children}</>;
}
