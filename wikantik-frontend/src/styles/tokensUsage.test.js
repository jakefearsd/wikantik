/** Every var(--token) read anywhere under src/ must be declared in globals.css (or be a known dynamic family). */
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const SRC = resolve(process.cwd(), 'src');
const css = readFileSync(join(SRC, 'styles/globals.css'), 'utf8');
const declared = new Set([...css.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]));
// Built at runtime from a callout kind, e.g. `--callout-${kind}`; declared per kind in globals.css.
// Declared locally rather than in globals.css: --c / --callout-icon are set per .callout-* rule in article.css;
// --selection-bar-* are set on the selection bar in admin.css.
const DYNAMIC = [/^--callout-$/, /^--cm-callout-tint$/, /^--c$/, /^--callout-icon$/, /^--selection-bar-(bg|border|accent)$/];

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return walk(p);
    return /\.(css|jsx?|tsx?)$/.test(name) && !/\.test\./.test(name) ? [p] : [];
  });
}

describe('CSS custom property usage', () => {
  const used = new Map();
  for (const file of walk(SRC)) {
    for (const m of readFileSync(file, 'utf8').matchAll(/var\(\s*(--[\w-]+)/g)) {
      if (!used.has(m[1])) used.set(m[1], file.slice(SRC.length + 1));
    }
  }

  it('finds usages', () => {
    expect(used.size).toBeGreaterThan(50);
  });

  it('reads only tokens globals.css declares', () => {
    const undefinedTokens = [...used.entries()]
      .filter(([t]) => !declared.has(t) && !DYNAMIC.some((re) => re.test(t)))
      .map(([t, f]) => `${t} (${f})`)
      .sort();
    expect(undefinedTokens).toEqual([]);
  });
});
