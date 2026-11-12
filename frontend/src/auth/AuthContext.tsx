import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, type Me } from '../api';

interface AuthState {
  user: Me | null;
  /** true, пока пытаемся восстановить сессию по refresh-cookie */
  loading: boolean;
  isAdmin: boolean;
  login(email: string, password: string): Promise<void>;
  verifyOtp(email: string, code: string): Promise<void>;
  logout(): Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        if (await api.restoreSession()) {
          const me = await api.me();
          if (!cancelled) setUser(me);
        }
      } catch {
        // нет сессии — работаем анонимно
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    await api.login(email, password);
    setUser(await api.me());
  }, []);

  const verifyOtp = useCallback(async (email: string, code: string) => {
    await api.verifyOtp(email, code);
    setUser(await api.me());
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      setUser(null);
    }
  }, []);

  const value = useMemo<AuthState>(
    () => ({ user, loading, isAdmin: !!user?.roles.includes('ADMIN'), login, verifyOtp, logout }),
    [user, loading, login, verifyOtp, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth вне AuthProvider');
  return ctx;
}
