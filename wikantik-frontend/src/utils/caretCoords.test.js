import { describe, it, expect, afterEach } from 'vitest';
import { getCaretCoordinates } from './caretCoords';

function makeTextarea({ value, selectionStart, top = 100, left = 50, width = 300 }) {
  const ta = document.createElement('textarea');
  ta.value = value;
  ta.selectionStart = selectionStart;
  ta.selectionEnd = selectionStart;
  // Position the textarea so taRect.top is non-zero — we want viewport coords.
  ta.style.position = 'absolute';
  ta.style.top = `${top}px`;
  ta.style.left = `${left}px`;
  ta.style.width = `${width}px`;
  ta.style.height = '120px';
  ta.style.fontSize = '14px';
  ta.style.lineHeight = '20px';
  ta.style.padding = '8px';
  ta.style.fontFamily = 'monospace';
  document.body.appendChild(ta);
  // happy-dom's getBoundingClientRect doesn't honor layout; stub it.
  ta.getBoundingClientRect = () => ({
    top, left, right: left + width, bottom: top + 120, width, height: 120, x: left, y: top,
  });
  return ta;
}

describe('getCaretCoordinates', () => {
  let ta;
  afterEach(() => { if (ta && ta.parentNode) ta.parentNode.removeChild(ta); });

  it('returns finite top + left numbers for a non-empty textarea', () => {
    ta = makeTextarea({ value: 'hello @al', selectionStart: 9, top: 100, left: 50 });
    const coords = getCaretCoordinates(ta);
    expect(Number.isFinite(coords.top)).toBe(true);
    expect(Number.isFinite(coords.left)).toBe(true);
  });

  it('places the popup BELOW the textarea-top (caret line + line-height offset)', () => {
    ta = makeTextarea({ value: 'hello @al', selectionStart: 9, top: 100 });
    const coords = getCaretCoordinates(ta);
    // Caret is somewhere inside the textarea (top=100, height=120). The result
    // includes the line-height offset (+2), so it should be strictly > top.
    expect(coords.top).toBeGreaterThan(100);
  });

  it('returns the empty-string baseline (caret at 0) above later positions', () => {
    ta = makeTextarea({ value: '', selectionStart: 0, top: 100 });
    const a = getCaretCoordinates(ta);
    ta.value = 'lots of text\nover multiple\nlines';
    ta.selectionStart = ta.value.length;
    const b = getCaretCoordinates(ta);
    // Caret at end of multi-line content should be >= caret at start.
    // happy-dom may collapse to identical values; assert non-strict ordering.
    expect(b.top).toBeGreaterThanOrEqual(a.top);
  });

  it('handles a null/missing textarea by returning the zero origin', () => {
    expect(getCaretCoordinates(null)).toEqual({ top: 0, left: 0 });
    expect(getCaretCoordinates(undefined)).toEqual({ top: 0, left: 0 });
  });

  it('cleans up its mirror element after computing (no leftover hidden divs)', () => {
    ta = makeTextarea({ value: 'hi @al', selectionStart: 6 });
    const before = document.body.children.length;
    getCaretCoordinates(ta);
    expect(document.body.children.length).toBe(before);
  });
});
