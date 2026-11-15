import '@testing-library/jest-dom/vitest';

// jsdom не умеет ResizeObserver, а он нужен ResponsiveContainer из Recharts.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;
