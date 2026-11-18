import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { api } from '../api';
import { cabinetTabFromPath } from '../pages/UserCabinetPage';
import { useAuth } from '../auth/AuthContext';
import { toggleScheme, useColorScheme } from '../theme';
import { Logo } from './Logo';
import { Button, cx } from './ui';

const navClass = ({ isActive }: { isActive: boolean }) =>
  cx(
    'rounded-lg px-3 py-2 text-sm font-medium transition',
    isActive ? 'bg-ink text-paper' : 'text-ink-2 hover:bg-paper-2 hover:text-ink',
  );

function ThemeToggle() {
  const scheme = useColorScheme();
  const dark = scheme === 'dark';
  return (
    <button
      type="button"
      onClick={toggleScheme}
      className="grid h-10 w-10 place-items-center rounded-xl text-ink-2 hover:bg-paper-2 hover:text-ink transition-colors"
      aria-label={dark ? 'Переключить на светлую тему' : 'Переключить на тёмную тему'}
      title={dark ? 'Светлая тема' : 'Тёмная тема'}
    >
      {dark ? (
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <circle cx="12" cy="12" r="4" />
          <path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4m11.4-11.4 1.4-1.4" />
        </svg>
      ) : (
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8" />
        </svg>
      )}
    </button>
  );
}

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
      {/* У админа только его панель: клиентские разделы ему закрыты (см. NotForAdmin). */}
      {!isAdmin && (
        <>
          <NavLink to="/" end viewTransition className={navClass}>Калькулятор</NavLink>
          <NavLink to="/microloan" viewTransition className={navClass}>Микрозайм</NavLink>
          {user && (
            <NavLink
              to="/scenarios"
              viewTransition
              className={() => navClass({ isActive: cabinetTabFromPath(location.pathname) !== null })}
            >
              Личный кабинет
            </NavLink>
          )}
        </>
      )}
      {isAdmin && <NavLink to="/admin" viewTransition className={navClass}>Панель админа</NavLink>}
    </>
  );

  /** В мобильном меню для гостей дублируем вход/регистрацию: из шапки <640px они недоступны. */
  const guestActions = !user && !isAdmin && (
    <>
      <Button variant="secondary" size="md" onClick={() => navigate('/login')}>Войти</Button>
      <Button size="md" onClick={() => navigate('/register')}>Регистрация</Button>
    </>
  );

  return (
    <div className="flex min-h-screen flex-col">
      <header className="site-header sticky top-0 z-30 border-b border-line/80 bg-paper/85 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
          <Logo />
          <nav className="hidden items-center gap-1 md:flex" aria-label="Основная навигация">{links}</nav>
          <div className="flex items-center gap-2">
            <ThemeToggle />
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
                    <span className="badge-live absolute -right-0.5 -top-0.5 grid h-5 min-w-5 place-items-center rounded-full bg-stamp-solid px-1 text-[10px] font-bold text-white">
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
                {/* обёртка-спан: класс hidden на самой кнопке перебивается её базовым inline-flex */}
                <span className="hidden sm:block">
                  <Button size="sm" onClick={() => navigate('/register')}>Регистрация</Button>
                </span>
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
        {menuOpen && (
          <nav className="menu-panel flex flex-col gap-1 border-t border-line px-4 py-3 md:hidden" aria-label="Мобильная навигация">
            {links}
            {guestActions && (
              <div className="mt-2 grid grid-cols-2 gap-2 border-t border-line pt-3">
                {guestActions}
              </div>
            )}
          </nav>
        )}
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
