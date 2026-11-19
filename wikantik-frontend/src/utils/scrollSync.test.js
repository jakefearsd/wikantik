import { describe, it, expect } from 'vitest';
import { frontmatterLineCount, scrollAnchors, mapPosition, previewScrollFor, editorScrollFor } from './scrollSync';

describe('frontmatterLineCount', () => {
  it('counts both fences of a leading frontmatter block', () => {
    const text = '---\ntype: article\ncluster: x\n---\n\n# Body\n';
    expect(frontmatterLineCount(text)).toBe(4); // lines 0..3 (--- type cluster ---)
  });

  it('returns 0 when there is no frontmatter', () => {
    expect(frontmatterLineCount('# Just a body\n\ntext')).toBe(0);
    expect(frontmatterLineCount('')).toBe(0);
  });

  it('returns 0 for an unterminated (still being typed) block', () => {
    expect(frontmatterLineCount('---\ntype: article\n')).toBe(0);
  });

  it('does not treat a mid-document --- (hr) as frontmatter', () => {
    expect(frontmatterLineCount('# Title\n\n---\n\nmore')).toBe(0);
  });
});

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
