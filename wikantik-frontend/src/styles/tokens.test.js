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

  it('small secondary text never uses the low-contrast --text-muted (fails AA)', () => {
    for (const sel of ['.link-preview-cluster', '.link-preview-missing', '.link-preview-loading',
      '.quick-overlay .quick-row-error', '.mention-context']) {
      const start = css.indexOf(`${sel} {`) >= 0 ? css.indexOf(`${sel} {`) : css.indexOf(`${sel},`);
      expect(start, sel).toBeGreaterThanOrEqual(0);
      const rule = css.slice(start, css.indexOf('}', start));
      expect(rule, sel).not.toContain('--text-muted');
    }
  });

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

  it('the quick overlay highlights its single focused row with a token both themes define', () => {
    const rule = css.slice(css.indexOf('.quick-overlay .quick-row.focused,'), css.indexOf('}', css.indexOf('.quick-overlay .quick-row.focused,')));
    expect(rule).toContain('var(--row-selected-bg)');
    expect(block(':root')).toContain('--row-selected-bg:');
    expect(block('[data-theme="dark"]')).toContain('--row-selected-bg:');
  });

  it('frontmatter controls, bordered buttons and the mention rail read only tokens both themes define', () => {
    const light = block(':root');
    const dark = block('[data-theme="dark"]');
    const selectors = ['.ui-select,\n.ui-combobox-field,\n.ui-taginput-field {', '.ui-taginput {', '.ui-combobox-list {',
      '.fm-textarea {', '.fm-cluster {', '.fm-cluster-field {', '.btn-secondary {', '.mention-btn-ignore,\n.draft-btn-discard {', '.mention-btn-link,\n.draft-btn-restore {', '.draft-btn-dismiss {',
      '.mention-line {'];
    for (const sel of selectors) {
      const start = css.indexOf(sel);
      expect(start, `${sel} rule exists`).toBeGreaterThanOrEqual(0);
      const rule = css.slice(start, css.indexOf('}', start));
      expect(rule, `${sel} has no hard-coded white fallback`).not.toMatch(/var\(--[\w-]+,\s*#/);
      for (const [, token] of rule.matchAll(/var\((--[\w-]+)/g)) {
        expect(light, `${token} (${sel}) in :root`).toContain(`${token}:`);
        if (/^--(space|radius|font|duration|ease)/.test(token)) continue; // theme-independent metrics
        expect(dark, `${token} (${sel}) in dark theme`).toContain(`${token}:`);
      }
    }
  });

  it('never falls back to the undefined --surface token (it rendered white fields in dark mode)', () => {
    expect(css).not.toMatch(/var\(--surface\b/);
  });

  // article.css and admin.css read globals' tokens; a fallback on an undeclared one (e.g. var(--surface, #fff))
  // is what actually renders, in BOTH themes — that is how white panels ended up in dark mode.
  for (const file of ['article.css', 'admin.css']) {
    it(`${file} reads only tokens globals.css (or the file itself) declares`, () => {
      const other = readFileSync(resolve(process.cwd(), `src/styles/${file}`), 'utf8');
      const own = new Set([...other.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]));
      const undeclared = [...other.matchAll(/var\((--[\w-]+)/g)].map((m) => m[1])
        .filter((t) => !declared.has(t) && !own.has(t));
      expect([...new Set(undeclared)].sort()).toEqual([]);
      expect(other).not.toMatch(/var\(--surface\b/);
    });
  }
});
