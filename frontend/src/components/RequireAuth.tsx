import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { Spinner } from './ui';

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
  if (loading) {
    return (
      <div className="grid place-items-center py-24 text-money">
        <Spinner className="h-8 w-8" />
      </div>
    );
  }
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
  if (loading) {
    return (
      <div className="grid place-items-center py-24 text-money">
        <Spinner className="h-8 w-8" />
      </div>
    );
  }
  if (isAdmin) return <Navigate to="/admin" replace />;
  return <>{children}</>;
}
