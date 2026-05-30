import { describe, it, expect } from 'vitest';
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
});
