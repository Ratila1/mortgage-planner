import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { api, USE_MOCKS } from '../api';
import { useAuth } from '../auth/AuthContext';
import { Logo } from './Logo';
import { Button, cx } from './ui';

const navClass = ({ isActive }: { isActive: boolean }) =>
  cx(
    'rounded-lg px-3 py-2 text-sm font-medium transition',
    isActive ? 'bg-ink text-paper' : 'text-ink-2 hover:bg-paper-2 hover:text-ink',
  );

function useUnreadCount(enabled: boolean) {
  const [count, setCount] = useState(0);
  const location = useLocation();
  useEffect(() => {
    if (!enabled) {
      setCount(0);
      return;
    }
    let cancelled = false;
    api
      .listNotifications()
      .then((list) => !cancelled && setCount(list.filter((n) => !n.read).length))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [enabled, location.pathname]);
  return count;
}

export function Layout() {
  const { user, isAdmin, logout } = useAuth();
  const navigate = useNavigate();
  const unread = useUnreadCount(!!user);
  const [menuOpen, setMenuOpen] = useState(false);
  const location = useLocation();
  useEffect(() => setMenuOpen(false), [location.pathname]);

  const links = (
    <>
      <NavLink to="/" end className={navClass}>Калькулятор</NavLink>
      {user && <NavLink to="/scenarios" className={navClass}>Мои сценарии</NavLink>}
      {user && <NavLink to="/compare" className={navClass}>Сравнение</NavLink>}
      {isAdmin && <NavLink to="/admin/offers" className={navClass}>Админка</NavLink>}
    </>
  );

  return (
    <div className="flex min-h-screen flex-col">
      {USE_MOCKS && (
        <div className="bg-ink px-4 py-1.5 text-center text-xs text-paper/80">
          Демо-режим: данные ненастоящие, как обещания в рекламе ипотеки. Вход — любая почта и пароль от 8 символов
          (с «admin» в почте — админка).
        </div>
      )}
      <header className="sticky top-0 z-30 border-b border-line/80 bg-paper/85 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
          <Logo />
          <nav className="hidden items-center gap-1 md:flex" aria-label="Основная навигация">{links}</nav>
          <div className="flex items-center gap-2">
            {user ? (
              <>
                <NavLink
                  to="/notifications"
                  className="relative grid h-10 w-10 place-items-center rounded-xl text-ink-2 hover:bg-paper-2"
                  aria-label={unread ? `Уведомления: ${unread} новых` : 'Уведомления'}
                >
                  <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                    <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
                    <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
                  </svg>
                  {unread > 0 && (
                    <span className="absolute -right-0.5 -top-0.5 grid h-5 min-w-5 place-items-center rounded-full bg-stamp px-1 text-[10px] font-bold text-white">
                      {unread}
                    </span>
                  )}
                </NavLink>
                <span className="hidden max-w-40 truncate text-sm text-ink-2 lg:inline" title={user.email}>{user.email}</span>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={async () => {
                    await logout();
                    navigate('/');
                  }}
                >
                  Выйти
                </Button>
              </>
            ) : (
              <>
                <Button variant="ghost" size="sm" onClick={() => navigate('/login')}>Войти</Button>
                <Button size="sm" className="hidden sm:inline-flex" onClick={() => navigate('/register')}>Регистрация</Button>
              </>
            )}
            <button
              type="button"
              className="grid h-10 w-10 place-items-center rounded-xl text-ink-2 hover:bg-paper-2 md:hidden"
              aria-label="Меню"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen((v) => !v)}
            >
              <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d={menuOpen ? 'M6 6l12 12M18 6L6 18' : 'M4 7h16M4 12h16M4 17h16'} />
              </svg>
            </button>
          </div>
        </div>
        {menuOpen && <nav className="flex flex-col gap-1 border-t border-line px-4 py-3 md:hidden" aria-label="Мобильная навигация">{links}</nav>}
      </header>

      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:py-12">
        <Outlet />
      </main>

      <footer className="border-t border-line/80">
        <div className="mx-auto flex max-w-6xl flex-col gap-2 px-4 py-6 text-xs text-ink-3 sm:flex-row sm:items-center sm:justify-between">
          <p>© {new Date().getFullYear()} ООО «Тмыв денег». Не является публичной офертой, финансовой консультацией и, к счастью, банком.</p>
          <p>Расчёты приблизительные. Точные цифры — в договоре мелким шрифтом.</p>
        </div>
      </footer>
    </div>
  );
}
