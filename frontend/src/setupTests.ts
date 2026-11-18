import '@testing-library/jest-dom/vitest';

// jsdom не умеет ResizeObserver, а он нужен ResponsiveContainer из Recharts
// и скользящему индикатору Segmented.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;

// jsdom не умеет matchMedia, а он нужен определению цветовой темы и AnimatedNumber.
if (!globalThis.matchMedia) {
  globalThis.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent: () => false,
  })) as unknown as typeof matchMedia;
}
