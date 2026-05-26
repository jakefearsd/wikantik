import { describe, it, expect } from 'vitest';
import { selectorFromRange, selectionTouchesMath } from './commentAnchor';

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
