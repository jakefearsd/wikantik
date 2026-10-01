import { describe, it, expect } from 'vitest';
import { locatePhrase, linkMarkup } from './mentionLink';

const m = (phrase, from) => ({ phrase, from, to: from + phrase.length, target: 'T' });

describe('locatePhrase', () => {
  it('uses the recorded span when it still matches', () => {
    expect(locatePhrase('an index fund here', m('index fund', 3))).toEqual({ from: 3, to: 13 });
  });
  it('relocates to the nearest occurrence after the text shifted', () => {
    const text = 'NEW TEXT. an index fund here, another index fund';
    expect(locatePhrase(text, m('index fund', 3))).toEqual({ from: 13, to: 23 });
  });
  it('requires whole words and ignores case', () => {
    expect(locatePhrase('reindex fundamentals; Index Fund', m('index fund', 0))).toEqual({ from: 22, to: 32 });
  });
  it('returns null when the phrase is gone', () => {
    expect(locatePhrase('nothing left', m('index fund', 0))).toBeNull();
  });
  it('handles astral characters before the phrase (UTF-16 offsets)', () => {
    const text = '😀 an expense ratio';
    expect(locatePhrase(text, m('expense ratio', 6))).toEqual({ from: 6, to: 19 });
  });
  it('relocates correctly after astral characters and CRLF line breaks', () => {
    const text = '😀\r\n😀 new\r\nan Expense Ratio here';
    const loc = locatePhrase(text, m('expense ratio', 3));
    expect(text.slice(loc.from, loc.to)).toBe('Expense Ratio');
  });
  it('skips an occurrence inside inline code and relocates to the prose one', () => {
    const text = 'Use `index fund` here; an index fund later';
    expect(locatePhrase(text, m('index fund', 0))).toEqual({ from: 26, to: 36 });
  });
  it('skips occurrences inside existing link text and link URLs', () => {
    const text = 'See [an index fund](IndexFundsHub) or [x](index fund) — then index fund.';
    const loc = locatePhrase(text, m('index fund', 0));
    expect(loc).toEqual({ from: text.lastIndexOf('index fund'), to: text.lastIndexOf('index fund') + 10 });
  });
  it('returns null (so the caller rescans) when only code or link occurrences remain', () => {
    expect(locatePhrase('only `index fund` and [index fund](X) left', m('index fund', 0))).toBeNull();
  });
  it('does not accept the recorded span once the text there became inline code', () => {
    expect(locatePhrase('`index fund`', m('index fund', 1))).toBeNull();
  });
});

describe('linkMarkup', () => {
  it('keeps the author casing and escapes brackets', () => {
    expect(linkMarkup('Index [x] Fund', 'IndexFundsHub')).toBe('[Index \\[x\\] Fund](IndexFundsHub)');
  });
});
