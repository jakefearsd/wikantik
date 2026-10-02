import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { CALLOUT_STYLES, editorChromeSpec } from './editorTheme';
import { styleOf } from './remarkCallouts';

const css = readFileSync(resolve(process.cwd(), 'src/styles/globals.css'), 'utf8');
function block(selector) {
  const start = css.indexOf(`${selector} {`);
  return start < 0 ? '' : css.slice(start, css.indexOf('\n}', start));
}
// Typography / geometry tokens are theme-independent and only live in :root.
const THEME_INDEPENDENT = new Set(['--font-ui', '--font-mono', '--font-display', '--radius-sm', '--radius-md']);

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

  it('styles every live-preview class', () => {
    const classes = ['cm-lp-h1', 'cm-lp-h2', 'cm-lp-h3', 'cm-lp-h4', 'cm-lp-h5', 'cm-lp-h6', 'cm-lp-em', 'cm-lp-strong',
      'cm-lp-del', 'cm-lp-code', 'cm-lp-link', 'cm-lp-quote', 'cm-lp-callout', 'cm-lp-callout-title',
      'cm-lp-callout-title-widget', 'cm-lp-callout-icon', 'cm-lp-bullet', 'cm-lp-task', 'cm-lp-rule', 'cm-lp-image',
      'cm-lp-image-wrap', 'cm-lp-image-missing', 'cm-lp-math', 'cm-lp-math-block', 'cm-lp-math-error', 'cm-lp-fence',
      'cm-lp-codeblock', 'cm-lp-plugin', 'cm-lp-embed', 'cm-lp-embed-title', 'cm-lp-embed-body', 'cm-lp-embed-loading',
      'cm-lp-embed-missing', 'cm-lp-embed-restricted', 'cm-lp-embed-error'];
    const keys = Object.keys(editorChromeSpec);
    expect(classes.filter((c) => !keys.some((k) => k.split(/[\s,:]/).includes(`.${c}`)))).toEqual([]);
    for (const style of CALLOUT_STYLES) {
      expect(editorChromeSpec[`&.cm-editor .cm-lp-callout-${style}`]).toEqual({ '--cm-callout-tint': `var(--callout-${style})` });
    }
    expect(editorChromeSpec['&.cm-editor .cm-lp-h1'].fontFamily).toBe('var(--font-display)');
  });

  it('has a rule for every cm-lp class the extension source emits', () => {
    const dir = resolve(process.cwd(), 'src/utils/livePreview');
    const emitted = new Set();
    for (const f of readdirSync(dir).filter((n) => n.endsWith('.js') && !n.endsWith('.test.js'))) {
      for (const m of readFileSync(resolve(dir, f), 'utf8').matchAll(/cm-lp-[a-z0-9]+(?:-[a-z0-9]+)*(?![\w-])/g)) emitted.add(m[0]);
    }
    const prefixes = new Set(['cm-lp-h', 'cm-lp-callout-']); // completed at runtime: cm-lp-h<level>, cm-lp-callout-<style>
    const keys = Object.keys(editorChromeSpec);
    const unstyled = [...emitted].filter((c) => !prefixes.has(c) && !keys.some((k) => k.split(/[\s,:]/).includes(`.${c}`)));
    expect(unstyled).toEqual([]);
  });
});
