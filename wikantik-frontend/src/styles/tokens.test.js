/** Every colour/spacing token a stylesheet reads without a fallback must be declared, or the rule silently drops. */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Read from disk: vitest turns CSS imports (even ?raw) into empty strings. Tests run from the package root.
const css = readFileSync(resolve(process.cwd(), 'src/styles/globals.css'), 'utf8');

function block(selector) {
  const start = css.indexOf(`${selector} {`);
  return start < 0 ? '' : css.slice(start, css.indexOf('\n}', start));
}

describe('globals.css custom properties', () => {
  const declared = new Set([...css.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]));
  const usedWithoutFallback = new Set([...css.matchAll(/var\((--[\w-]+)\s*\)/g)].map((m) => m[1]));

  it('reads the real stylesheet', () => {
    expect(css.length).toBeGreaterThan(10000);
    expect(declared.has('--text')).toBe(true);
  });

  it('declares every token read without a fallback', () => {
    expect([...usedWithoutFallback].filter((t) => !declared.has(t)).sort()).toEqual([]);
  });

  it('the mention rail reads text colours both themes define', () => {
    const light = block(':root');
    const dark = block('[data-theme="dark"]');
    const rule = css.slice(css.indexOf('.mention-head {'), css.indexOf('}', css.indexOf('.mention-head {')));
    for (const [, token] of rule.matchAll(/var\((--[\w-]+)/g)) {
      expect(light, `${token} in :root`).toContain(`${token}:`);
      expect(dark, `${token} in dark theme`).toContain(`${token}:`);
    }
  });
});
