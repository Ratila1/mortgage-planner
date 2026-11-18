import { useEffect, useState } from 'react';

/**
 * Управление цветовой темой. Источник истины — <meta name="color-scheme"> в head
 * (см. dark-mode-toggle в modern-web-guidance): CSS привязан к мете через :root:has(),
 * поэтому переключение не требует JS-классов и работает даже до гидрации.
 *
 * Три состояния данных: система (в localStorage пусто) / light / dark.
 * Явный выбор сохраняется только когда он расходится с системной темой:
 * если пользователь вернул то, что и так включает система, запись стирается —
 * иначе система не сможет снова менять тему по времени суток.
 */
const META_NAME = 'color-scheme';
const STORAGE_KEY = 'color-scheme';

export type Scheme = 'light' | 'dark';

const systemScheme = (): Scheme =>
  window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';

function storedScheme(): Scheme | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === 'light' || value === 'dark' ? value : null;
  } catch {
    return null;
  }
}

export function currentScheme(): Scheme {
  return storedScheme() ?? systemScheme();
}

function writeMeta() {
  document.querySelector(`meta[name="${META_NAME}"]`)?.setAttribute('content', storedScheme() ?? 'light dark');
  // Событие 'storage' не срабатывает в той же вкладке, что сделала запись,
  // а matchMedia молчит, если сменили не систему, а выбор пользователя.
  // Поэтому свой синхронный сигнал для useColorScheme в этой вкладке.
  window.dispatchEvent(new Event('color-scheme-change'));
}

/** Переключить тему на противоположную показанной. */
export function toggleScheme(): void {
  const target: Scheme = currentScheme() === 'dark' ? 'light' : 'dark';
  try {
    if (target === systemScheme()) localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, target);
  } catch {
    /* localStorage недоступен — тема применится, но не запомнится */
  }
  writeMeta();
}

/** Тема, показанная сейчас; реагирует на систему и на смену темы в другой вкладке. */
export function useColorScheme(): Scheme {
  const [scheme, setScheme] = useState<Scheme>(currentScheme);
  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const sync = () => setScheme(currentScheme());
    media.addEventListener('change', sync);
    window.addEventListener('storage', sync);
    window.addEventListener('color-scheme-change', sync);
    return () => {
      media.removeEventListener('change', sync);
      window.removeEventListener('storage', sync);
      window.removeEventListener('color-scheme-change', sync);
    };
  }, []);
  return scheme;
}
