import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { CALLOUT_STYLES, editorChromeSpec } from './editorTheme';
import { styleOf } from './remarkCallouts';

const css = readFileSync(resolve(process.cwd(), 'src/styles/globals.css'), 'utf8');
function block(selector) {
  const start = css.indexOf(`${selector} {`);
  return start < 0 ? '' : css.slice(start, css.indexOf('\n}', start));
}
// Typography / geometry tokens are theme-independent and only live in :root.
const THEME_INDEPENDENT = new Set(['--font-ui', '--font-mono', '--radius-sm', '--radius-md']);

const flat = JSON.stringify(editorChromeSpec);
const used = new Set([...flat.matchAll(/var\((--[\w-]+)/g)].map((m) => m[1]));
const local = new Set([...flat.matchAll(/"(--[\w-]+)":/g)].map((m) => m[1]));

describe('editor chrome theme', () => {
  it('reads only tokens that globals.css declares, colour tokens in both themes', () => {
    const light = block(':root');
    const dark = block('[data-theme="dark"]');
    const missing = [];
    for (const token of used) {
      if (local.has(token)) continue;
      if (!light.includes(`${token}:`)) missing.push(`${token} (:root)`);
      if (!THEME_INDEPENDENT.has(token) && !dark.includes(`${token}:`)) missing.push(`${token} (dark)`);
    }
    expect(missing).toEqual([]);
    expect(used.has('--row-selected-bg')).toBe(true);
  });

  it('roots every selector at the editor so it out-ranks the default themes', () => {
    expect(Object.keys(editorChromeSpec).filter((s) => !s.startsWith('&.cm-editor '))).toEqual([]);
  });

  it('styles the selected completion row and themes the fold placeholder and callout markers', () => {
    const keys = Object.keys(editorChromeSpec);
    expect(keys.some((k) => k.endsWith('> ul > li[aria-selected]'))).toBe(true);
    expect(keys).toContain('&.cm-editor .cm-foldPlaceholder');
    expect(keys).toContain('&.cm-editor .cm-callout-marker');
  });

  it('has a tint rule for every callout style the alias map can produce', () => {
    const aliases = ['note', 'summary', 'tldr', 'info', 'todo', 'tip', 'hint', 'important', 'success', 'check', 'done',
      'question', 'help', 'faq', 'warning', 'caution', 'attention', 'failure', 'fail', 'missing', 'danger', 'error',
      'bug', 'example', 'quote', 'cite', 'abstract', 'unknown'];
    for (const style of new Set(aliases.map(styleOf))) {
      expect(CALLOUT_STYLES).toContain(style);
      expect(editorChromeSpec[`&.cm-editor .cm-callout-marker-${style}`]).toEqual({ '--cm-callout-tint': `var(--callout-${style})` });
    }
  });
});
