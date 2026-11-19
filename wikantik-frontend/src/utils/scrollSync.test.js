import { describe, it, expect } from 'vitest';
import { scrollAnchors, mapPosition, previewScrollFor, editorScrollFor } from './scrollSync';

describe('scrollAnchors', () => {
  it('starts at the two tops, ends at the two document ends, with each block between', () => {
    expect(scrollAnchors([[100, 300], [200, 350]], 1000, 2000)).toEqual([[0, 0], [100, 300], [200, 350], [1000, 2000]]);
  });

  it('drops a pair that does not move down in both panes (a hidden line, a block out of order)', () => {
    // 0 in the editor ties the start; 150 is above 300 in the preview; 200 ties 200 in the editor
    expect(scrollAnchors([[0, 24], [100, 300], [150, 250], [200, 400], [200, 500]], 1000, 2000))
      .toEqual([[0, 0], [100, 300], [200, 400], [1000, 2000]]);
  });

  it('with no blocks is the plain proportion of the two documents', () => {
    expect(scrollAnchors([], 1000, 2000)).toEqual([[0, 0], [1000, 2000]]);
  });
});

describe('mapPosition', () => {
  const anchors = [[0, 0], [100, 300], [200, 350], [1000, 2000]];

  it('lands each block top on the same block top in the other pane', () => {
    expect(mapPosition(100, anchors)).toBe(300);
    expect(mapPosition(200, anchors)).toBe(350);
  });

  it('moves linearly between two blocks', () => {
    expect(mapPosition(50, anchors)).toBe(150);
    expect(mapPosition(150, anchors)).toBe(325);
  });

  it('maps the preview back to the editor with reverse', () => {
    expect(mapPosition(300, anchors, true)).toBe(100);
    expect(mapPosition(325, anchors, true)).toBe(150);
  });

  it('holds at the ends outside the anchors', () => {
    expect(mapPosition(-10, anchors)).toBe(0);
    expect(mapPosition(5000, anchors)).toBe(2000);
  });

  it('is 0 with no anchors', () => {
    expect(mapPosition(10, [])).toBe(0);
  });
});

describe('previewScrollFor / editorScrollFor', () => {
  // The editor: 1000 px of content in a 200 px window. The preview: 1100 px of content in a 200 px window.
  const anchors = [[0, 0], [100, 300], [200, 350], [1000, 1100]];
  const pane = (readingLine) => ({ anchors, readingLine, editorMax: 800, editorWindow: 200, previewMax: 900 });

  it('in the middle, holds the two panes level at the reading line', () => {
    expect(previewScrollFor(150, pane(0))).toBe(mapPosition(150, anchors));
    expect(previewScrollFor(150, pane(30))).toBe(mapPosition(180, anchors) - 30);
  });

  it('starts at the top and rises without a step into the reading line', () => {
    const p = pane(100);
    expect(previewScrollFor(0, p)).toBe(0);
    expect(previewScrollFor(100, p)).toBeCloseTo(mapPosition(200, anchors) - 100, 6);
    expect(previewScrollFor(99.999, p)).toBeCloseTo(previewScrollFor(100, p), 2);
  });

  it('never runs backwards near the top, where the preview is far shorter than the editor', () => {
    // 400 px of editor (link definitions, comments) render as 20 px
    const squeezed = { anchors: [[0, 0], [400, 20], [1000, 1100]], readingLine: 300, editorMax: 800, editorWindow: 200, previewMax: 900 };
    let last = -1;
    for (let s = 0; s <= 800; s += 5) {
      const y = previewScrollFor(s, squeezed);
      expect(y).toBeGreaterThanOrEqual(last);
      last = y;
    }
  });

  it('meets the end at the end, without a jump on the last pixel', () => {
    const p = pane(30);
    expect(previewScrollFor(800, p)).toBe(900);
    expect(900 - previewScrollFor(799, p)).toBeLessThan(5);
    let last = -1;
    for (let s = 590; s <= 800; s += 1) {
      const y = previewScrollFor(s, p);
      expect(y).toBeGreaterThanOrEqual(last);
      last = y;
    }
  });

  it('the editor scroll for a preview scroll is the exact inverse', () => {
    for (const k of [0, 30, 150]) {
      for (const s of [0, 10, 75, 150, 400, 700, 790, 800]) {
        const p = previewScrollFor(s, pane(k));
        expect(previewScrollFor(editorScrollFor(p, pane(k)), pane(k))).toBeCloseTo(p, 1);
      }
    }
    expect(editorScrollFor(0, pane(30))).toBe(0);
    expect(editorScrollFor(900, pane(30))).toBe(800);
  });

  it('holds still when a pane cannot scroll', () => {
    expect(previewScrollFor(0, { ...pane(0), editorMax: 0 })).toBe(0);
    expect(editorScrollFor(100, { ...pane(0), previewMax: 0 })).toBe(0);
  });
});

describe('mapPosition over a long page', () => {
  // The scan the search replaced: the reference the faster lookup must agree with everywhere.
  function linear(y, anchors, reverse = false) {
    const from = reverse ? 1 : 0;
    const to = reverse ? 0 : 1;
    if (y <= anchors[0][from]) return anchors[0][to];
    for (let i = 1; i < anchors.length; i++) {
      const a = anchors[i - 1];
      const b = anchors[i];
      if (y < b[from]) return a[to] + ((y - a[from]) / (b[from] - a[from])) * (b[to] - a[to]);
    }
    return anchors[anchors.length - 1][to];
  }

  it('agrees with a scan of every anchor, both ways, at the anchors, between them and past the ends', () => {
    const pairs = [];
    let e = 0;
    let p = 0;
    for (let i = 0; i < 3000; i++) {
      e += 10 + (i % 7) * 13;
      p += 5 + (i % 11) * 29;
      pairs.push([e, p]);
    }
    const anchors = scrollAnchors(pairs, e + 100, p + 100);
    for (let y = -50; y < e + 300; y += 37.5) expect(mapPosition(y, anchors)).toBeCloseTo(linear(y, anchors), 9);
    for (let y = -50; y < p + 300; y += 41.25) expect(mapPosition(y, anchors, true)).toBeCloseTo(linear(y, anchors, true), 9);
    for (const [ae, ap] of anchors) {
      expect(mapPosition(ae, anchors)).toBeCloseTo(ap, 9);
      expect(mapPosition(ap, anchors, true)).toBeCloseTo(ae, 9);
    }
  });
});

