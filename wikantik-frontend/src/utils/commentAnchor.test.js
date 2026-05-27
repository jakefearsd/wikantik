import { describe, it, expect, vi, afterEach } from 'vitest';
import { selectorFromRange, selectionTouchesMath, captureSelection } from './commentAnchor';

function makeRoot(html) {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.appendChild(root);
  return root;
}

function rangeOver(textNode, start, end) {
  const r = document.createRange();
  r.setStart(textNode, start);
  r.setEnd(textNode, end);
  return r;
}

describe('selectorFromRange', () => {
  it('captures exact text plus surrounding context', () => {
    const root = makeRoot('<p>say hello world to everyone</p>');
    const tn = root.querySelector('p').firstChild;
    // "hello" is chars 4..9
    const sel = selectorFromRange(root, rangeOver(tn, 4, 9));
    expect(sel.exact).toBe('hello');
    expect(sel.prefix.endsWith('say ')).toBe(true);
    expect(sel.suffix.startsWith(' world')).toBe(true);
  });

  it('returns null for a collapsed/empty range', () => {
    const root = makeRoot('<p>abc</p>');
    const tn = root.querySelector('p').firstChild;
    expect(selectorFromRange(root, rangeOver(tn, 1, 1))).toBeNull();
  });
});

describe('selectionTouchesMath', () => {
  it('detects a selection inside KaTeX output', () => {
    const root = makeRoot('<p>see <span class="katex"><span>x</span></span> here</p>');
    const inner = root.querySelector('.katex span').firstChild;
    expect(selectionTouchesMath(rangeOver(inner, 0, 1))).toBe(true);
  });

  it('is false for plain text', () => {
    const root = makeRoot('<p>plain text</p>');
    const tn = root.querySelector('p').firstChild;
    expect(selectionTouchesMath(rangeOver(tn, 0, 5))).toBe(false);
  });
});

describe('captureSelection', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function stubSelection({ rangeCount = 1, isCollapsed = false, range = null }) {
    vi.spyOn(window, 'getSelection').mockReturnValue({
      rangeCount,
      isCollapsed,
      getRangeAt: () => range,
    });
  }

  it('returns null when there is no live selection', () => {
    stubSelection({ rangeCount: 0 });
    const root = makeRoot('<p>hello world</p>');
    expect(captureSelection(root)).toBeNull();
  });

  it('returns null for a collapsed selection', () => {
    const root = makeRoot('<p>hello world</p>');
    stubSelection({ isCollapsed: true });
    expect(captureSelection(root)).toBeNull();
  });

  it('returns null when the selection is outside root', () => {
    const root = makeRoot('<p>inside text</p>');
    const outside = makeRoot('<p>outside text</p>');
    const tn = outside.querySelector('p').firstChild;
    stubSelection({ range: rangeOver(tn, 0, 7) });
    expect(captureSelection(root)).toBeNull();
  });

  it('returns a math error when the selection touches KaTeX output', () => {
    const root = makeRoot('<p>see <span class="katex"><span>x</span></span></p>');
    const inner = root.querySelector('.katex span').firstChild;
    stubSelection({ range: rangeOver(inner, 0, 1) });
    expect(captureSelection(root)).toEqual({ error: 'math' });
  });

  it('returns selector + rect for a valid selection inside root', () => {
    const root = makeRoot('<p>say hello world to everyone</p>');
    const tn = root.querySelector('p').firstChild;
    stubSelection({ range: rangeOver(tn, 4, 9) });
    const result = captureSelection(root);
    expect(result.selector.exact).toBe('hello');
    expect(result).toHaveProperty('rect');
  });
});
