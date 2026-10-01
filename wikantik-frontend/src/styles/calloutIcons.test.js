/** Callout icons are CSS-only SVG masks (the callout HTML stays an empty span.callout-icon). */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Read from disk: vitest turns CSS imports into empty strings. Tests run from the package root.
const css = readFileSync(resolve(process.cwd(), 'src/styles/article.css'), 'utf8');
const STYLES = ['abstract', 'info', 'todo', 'tip', 'success', 'question', 'warning', 'failure', 'danger', 'bug', 'example', 'quote'];

const iconOf = (selector) => {
  const m = css.match(new RegExp(`${selector.replace(/\./g, '\\.')} \\{ --callout-icon: url\\("data:image/svg\\+xml,([^"]+)"\\); \\}`));
  return m && decodeURIComponent(m[1]);
};

describe('callout icons', () => {
  it('no longer draws text glyphs', () => {
    expect(css).not.toMatch(/callout-icon::before/);
  });

  it('masks the icon over the callout colour at 1em square', () => {
    const start = css.indexOf('.article-prose .callout-icon {');
    const rule = css.slice(start, css.indexOf('}', start));
    expect(rule).toContain('background-color: var(--c)');
    expect(rule).toContain('mask: var(--callout-icon)');
    expect(rule).toContain('-webkit-mask: var(--callout-icon)');
    expect(rule).toMatch(/width: 1em; height: 1em/);
  });

  it('gives the base callout (note and unknown types) and every style a well-formed SVG', () => {
    const svgs = [iconOf('.article-prose .callout'), ...STYLES.map((s) => iconOf(`.article-prose .callout-${s}`))];
    svgs.forEach((svg, i) => {
      expect(svg, `icon #${i}`).toBeTruthy();
      const doc = new DOMParser().parseFromString(svg, 'image/svg+xml');
      expect(doc.getElementsByTagName('parsererror')).toHaveLength(0);
      expect(doc.documentElement.getAttribute('viewBox')).toBe('0 0 24 24');
    });
    expect(new Set(svgs).size).toBe(svgs.length);
  });
});
