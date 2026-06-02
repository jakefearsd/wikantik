import { describe, it, expect } from 'vitest';
import { frontmatterLineCount, caretToPreviewFraction, previewScrollTopFor } from './scrollSync';

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

describe('caretToPreviewFraction', () => {
  const fm = 4; // frontmatter occupies lines 1..4
  it('pins to the top while the caret is inside the frontmatter', () => {
    expect(caretToPreviewFraction(1, 24, fm)).toBe(0);
    expect(caretToPreviewFraction(4, 24, fm)).toBe(0);
  });

  it('maps body position proportionally (first body line → 0)', () => {
    expect(caretToPreviewFraction(5, 24, fm)).toBe(0); // first body line
  });

  it('maps the last body line near the end', () => {
    // 24 total, fm=4 → 20 body lines; caret on the last line.
    const f = caretToPreviewFraction(24, 24, fm);
    expect(f).toBeGreaterThan(0.9);
    expect(f).toBeLessThanOrEqual(1);
  });

  it('handles no-frontmatter documents', () => {
    expect(caretToPreviewFraction(1, 10, 0)).toBe(0);
    expect(caretToPreviewFraction(10, 10, 0)).toBeGreaterThan(0.8);
  });
});

describe('previewScrollTopFor', () => {
  it('scales the fraction across the scrollable range', () => {
    expect(previewScrollTopFor(0, 1000, 400)).toBe(0);
    expect(previewScrollTopFor(1, 1000, 400)).toBe(600);
    expect(previewScrollTopFor(0.5, 1000, 400)).toBe(300);
  });

  it('clamps and never returns negative when content fits', () => {
    expect(previewScrollTopFor(0.5, 300, 400)).toBe(0);
  });
});
