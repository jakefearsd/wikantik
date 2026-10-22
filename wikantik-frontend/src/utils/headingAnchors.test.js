import { describe, it, expect, vi, afterEach } from 'vitest';
import { addHeadingAnchors } from './headingAnchors';

describe('addHeadingAnchors', () => {
  function makeContainer(html) {
    const div = document.createElement('div');
    div.innerHTML = html;
    return div;
  }

  it('injects an anchor with correct href into a heading that has an id', () => {
    const container = makeContainer('<h2 id="my-section">My Section</h2>');
    addHeadingAnchors(container);
    const anchor = container.querySelector('a.heading-anchor');
    expect(anchor).not.toBeNull();
    expect(anchor.getAttribute('href')).toBe('#my-section');
  });

  it('sets an accessible aria-label on the anchor', () => {
    const container = makeContainer('<h2 id="intro">Introduction</h2>');
    addHeadingAnchors(container);
    const anchor = container.querySelector('a.heading-anchor');
    expect(anchor.getAttribute('aria-label')).toMatch(/Introduction/);
  });

  it('is idempotent — running twice does not duplicate anchors', () => {
    const container = makeContainer('<h2 id="foo">Foo</h2>');
    addHeadingAnchors(container);
    addHeadingAnchors(container);
    const anchors = container.querySelectorAll('a.heading-anchor');
    expect(anchors.length).toBe(1);
  });

  it('skips headings without an id', () => {
    const container = makeContainer('<h2>No Id Here</h2>');
    addHeadingAnchors(container);
    expect(container.querySelectorAll('a.heading-anchor').length).toBe(0);
  });

  it('handles multiple headings', () => {
    const container = makeContainer(
      '<h2 id="a">A</h2><h3 id="b">B</h3><h2>No id</h2>'
    );
    addHeadingAnchors(container);
    expect(container.querySelectorAll('a.heading-anchor').length).toBe(2);
  });

  describe('copy link on click', () => {
    afterEach(() => vi.unstubAllGlobals());

    const sectionUrl = (id) => `${window.location.origin}${window.location.pathname}#${id}`;

    it('copies the absolute section URL and reports success', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined);
      vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
      const onCopy = vi.fn();
      const container = makeContainer('<h2 id="my-section">My Section</h2>');
      addHeadingAnchors(container, { onCopy });

      container.querySelector('a.heading-anchor').click();

      await vi.waitFor(() => expect(onCopy).toHaveBeenCalledTimes(1));
      expect(writeText).toHaveBeenCalledWith(sectionUrl('my-section'));
    });

    it('still follows the fragment link, so the address bar shows the section', () => {
      vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
      const container = makeContainer('<h2 id="my-section">My Section</h2>');
      addHeadingAnchors(container);

      const click = new MouseEvent('click', { bubbles: true, cancelable: true });
      container.querySelector('a.heading-anchor').dispatchEvent(click);

      expect(click.defaultPrevented).toBe(false);
    });

    it('reports a rejected clipboard write through onError', async () => {
      const failure = new Error('denied');
      vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: vi.fn().mockRejectedValue(failure) } });
      const onCopy = vi.fn();
      const onError = vi.fn();
      const container = makeContainer('<h2 id="my-section">My Section</h2>');
      addHeadingAnchors(container, { onCopy, onError });

      container.querySelector('a.heading-anchor').click();

      await vi.waitFor(() => expect(onError).toHaveBeenCalledWith(failure));
      expect(onCopy).not.toHaveBeenCalled();
    });

    it('reports a missing clipboard API (insecure context) through onError instead of throwing', async () => {
      vi.stubGlobal('navigator', { ...navigator, clipboard: undefined });
      const onError = vi.fn();
      const container = makeContainer('<h2 id="my-section">My Section</h2>');
      addHeadingAnchors(container, { onError });

      expect(() => container.querySelector('a.heading-anchor').click()).not.toThrow();

      await vi.waitFor(() => expect(onError).toHaveBeenCalledTimes(1));
    });
  });
});
