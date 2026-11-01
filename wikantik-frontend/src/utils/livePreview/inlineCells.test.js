import { describe, it, expect, vi } from 'vitest';
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
  it('shows the source of invalid inline math', () => {
    expect(html('$\\frac$').textContent).toBe('$\\frac$');
  });
  it('shows the file name for an image without alt text', () => {
    expect(html('![](dir/x.png)').querySelector('span.cm-lp-cell-img').textContent).toBe('x.png');
  });
  it('renders non-paragraph and embed-only cells as raw text', () => {
    for (const src of ['# h', '- x', '![[X]]']) expect(html(src).textContent).toBe(src);
  });
  it('logs and falls back to raw text when rendering throws', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.resetModules();
    vi.doMock('./mathRender', () => ({ renderedMath: () => { throw new Error('boom'); } }));
    const mod = await import('./inlineCells');
    const host = document.createElement('div');
    host.appendChild(mod.renderInlineMarkdown('a $x$ b'));
    expect(host.textContent).toBe('a $x$ b');
    expect(warn).toHaveBeenCalledWith('[live-preview] inline cell render failed', 'boom');
    vi.doUnmock('./mathRender');
    warn.mockRestore();
  });
});
