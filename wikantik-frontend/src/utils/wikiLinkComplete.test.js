import { describe, it, expect } from 'vitest';
import { createWikiLinkSource } from './wikiLinkComplete';

// Minimal stand-in for a CodeMirror CompletionContext: matchBefore(re) runs the
// regex against the text immediately before the cursor.
function fakeContext(textBefore, { explicit = false } = {}) {
  return {
    explicit,
    matchBefore(re) {
      const m = textBefore.match(re);
      if (!m) return null;
      const from = textBefore.length - m[0].length;
      return { from, to: textBefore.length, text: m[0] };
    },
  };
}

const PAGES = ['MathematicsHub', 'MachineLearning', 'RiskManagement'];

describe('createWikiLinkSource', () => {
  const source = createWikiLinkSource(() => PAGES);

  it('returns null when there is no [[ trigger before the cursor', () => {
    expect(source(fakeContext('some plain text'))).toBeNull();
  });

  it('offers all pages right after [[', () => {
    const res = source(fakeContext('intro [['));
    expect(res).not.toBeNull();
    expect(res.options.map(o => o.label)).toEqual(PAGES);
    // Replacement spans from the [[ so the brackets are consumed.
    expect(res.from).toBe('intro '.length);
  });

  it('filters case-insensitively by the typed fragment', () => {
    const res = source(fakeContext('see [[mach'));
    expect(res.options.map(o => o.label)).toEqual(['MachineLearning']);
  });

  it('applies as a wikilink [Name](Name)', () => {
    const res = source(fakeContext('[[Risk'));
    expect(res.options[0].apply).toBe('[RiskManagement](RiskManagement)');
  });

  it('returns null when nothing matches the fragment', () => {
    expect(source(fakeContext('[[zzzz'))).toBeNull();
  });

  it('does not trigger across a closing bracket or newline', () => {
    expect(source(fakeContext('[[Math] '))).toBeNull();
    expect(source(fakeContext('[[Math\n'))).toBeNull();
  });

  it('caps the number of options', () => {
    const many = Array.from({ length: 50 }, (_, i) => `Page${i}`);
    const s = createWikiLinkSource(() => many);
    expect(s(fakeContext('[[Page')).options.length).toBeLessThanOrEqual(20);
  });
});
