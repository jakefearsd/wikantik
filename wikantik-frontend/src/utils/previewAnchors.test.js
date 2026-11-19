import { describe, it, expect, beforeEach } from 'vitest';
import { collectPreviewAnchors, caretBlock } from './previewAnchors';

// happy-dom lays nothing out, so each element's top is stubbed: `top` is its viewport top with the scroller's
// own top at 50 and the scroller scrolled by 100.
function build() {
  document.body.innerHTML = `
    <div id="scroller"><div class="fm">frontmatter card</div>
      <article id="article">
        <h1 data-line="1" data-top="60">Title</h1>
        <p data-line="3" data-top="110">Some <em data-line="3" data-top="112">text</em></p>
        <ul data-line="5" data-top="160"><li data-line="5" data-top="160"><p data-line="5" data-top="160">one</p></li><li data-line="6" data-top="190">two</li></ul>
        <table data-line="8" data-top="240"><tbody><tr data-line="8" data-top="240"><td data-line="8">a</td></tr><tr data-line="10" data-top="270"><td data-line="10">b</td></tr></tbody></table>
        <p data-line="12" data-top="320">end</p>
      </article></div>`;
  const scroller = document.getElementById('scroller');
  scroller.getBoundingClientRect = () => ({ top: 50 });
  Object.defineProperty(scroller, 'scrollTop', { value: 100, configurable: true });
  for (const el of document.querySelectorAll('[data-top]')) {
    const top = Number(el.getAttribute('data-top'));
    el.getBoundingClientRect = () => ({ top });
  }
  return { scroller, article: document.getElementById('article') };
}

describe('collectPreviewAnchors', () => {
  let dom;
  beforeEach(() => { dom = build(); });

  it('takes the top-level blocks, list items and table rows, in document order, in scroll coordinates', () => {
    const anchors = collectPreviewAnchors(dom.article, dom.scroller);
    expect(anchors.map((a) => [a.el.tagName, a.line, a.top])).toEqual([
      ['H1', 1, 110], ['P', 3, 160], ['UL', 5, 210], ['LI', 5, 210], ['LI', 6, 240],
      ['TABLE', 8, 290], ['TR', 8, 290], ['TR', 10, 320], ['P', 12, 370],
    ]);
  });

  it('is empty without an article', () => {
    expect(collectPreviewAnchors(null, dom.scroller)).toEqual([]);
  });
});

describe('caretBlock', () => {
  let anchors;
  beforeEach(() => {
    const dom = build();
    anchors = collectPreviewAnchors(dom.article, dom.scroller);
  });

  it('is the innermost block starting at or above the caret line', () => {
    expect(caretBlock(anchors, 3).tagName).toBe('P');
    expect(caretBlock(anchors, 4).getAttribute('data-line')).toBe('3'); // the blank line after a paragraph
    expect(caretBlock(anchors, 5).tagName).toBe('LI'); // the first item, not the whole list
    expect(caretBlock(anchors, 6).getAttribute('data-line')).toBe('6');
    expect(caretBlock(anchors, 9).tagName).toBe('TR'); // the delimiter row belongs to the header row
    expect(caretBlock(anchors, 99).getAttribute('data-line')).toBe('12');
  });

  it('is null above the first block', () => {
    expect(caretBlock(anchors.slice(1), 1)).toBeNull();
  });
});
