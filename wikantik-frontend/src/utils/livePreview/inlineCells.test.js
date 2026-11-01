import { describe, it, expect } from 'vitest';
import { renderInlineMarkdown } from './inlineCells';

const html = (md) => {
  const host = document.createElement('div');
  host.appendChild(renderInlineMarkdown(md));
  return host;
};

describe('renderInlineMarkdown', () => {
  it('renders emphasis, strong, delete and code', () => {
    const h = html('**b** *i* ~~d~~ `c`');
    expect(h.querySelector('strong').textContent).toBe('b');
    expect(h.querySelector('em').textContent).toBe('i');
    expect(h.querySelector('del').textContent).toBe('d');
    expect(h.querySelector('code').textContent).toBe('c');
  });
  it('renders links as spans, never anchors', () => {
    const h = html('[t](https://x)');
    expect(h.querySelector('a')).toBeNull();
    expect(h.querySelector('span.cm-lp-link').textContent).toBe('t');
  });
  it('renders wikilinks with their alias', () => {
    const h = html('[[Page|Alias]]');
    expect(h.querySelector('span.cm-lp-link').textContent).toBe('Alias');
    expect(h.querySelector('a')).toBeNull();
  });
  it('renders inline math with KaTeX', () => {
    expect(html('$x^2$').querySelector('.katex')).not.toBeNull();
  });
  it('renders images as alt-text spans', () => {
    const h = html('![alt](i.png)');
    expect(h.querySelector('img')).toBeNull();
    expect(h.querySelector('span.cm-lp-cell-img').textContent).toBe('alt');
  });
  it('shows hostile markup as inert text', () => {
    for (const src of ['<img src=x onerror=alert(1)>', '<script>x</script>', '[x](javascript:alert(1))']) {
      const h = html(src);
      expect(h.querySelector('img, script, a')).toBeNull();
    }
    expect(html('<img src=x onerror=alert(1)>').textContent).toContain('<img src=x onerror=alert(1)>');
    expect(html('<script>x</script>').textContent).toContain('<script>x</script>');
    expect(html('[x](javascript:alert(1))').textContent).toBe('x');
  });
  it('returns an empty fragment for empty input', () => {
    expect(html('').childNodes.length).toBe(0);
  });
});
