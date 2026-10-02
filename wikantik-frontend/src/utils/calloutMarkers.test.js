import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { calloutMarkerRanges } from './calloutMarkers';

const DIALECTS = [['CommonMark', {}], ['GFM', { base: markdownLanguage }]];

describe.each(DIALECTS)('calloutMarkers (%s)', (_label, base) => {
function stateOf(doc) {
  const state = EditorState.create({ doc, extensions: [markdown(base)] });
  ensureSyntaxTree(state, state.doc.length, 5000);
  return state;
}

const text = (state, r) => state.sliceDoc(r.from, r.to);

describe('calloutMarkerRanges', () => {
  it('marks [!type] with its fold marker at the start of a blockquote, tagged with the callout style', () => {
    const state = stateOf('> [!warning] Careful\n> body\n\n> [!TIP]- Folded\n> x\n');
    const ranges = calloutMarkerRanges(state);
    expect(ranges.map((r) => [text(state, r), r.style])).toEqual([['[!warning]', 'warning'], ['[!TIP]-', 'tip']]);
  });

  it('maps aliases and unknown types through the preview style map', () => {
    const state = stateOf('> [!caution] a\n\n> [!faq] b\n\n> [!custom] c\n');
    expect(calloutMarkerRanges(state).map((r) => r.style)).toEqual(['warning', 'question', 'note']);
  });

  it('finds markers in nested blockquotes', () => {
    const state = stateOf('> > [!info] nested\n');
    const ranges = calloutMarkerRanges(state);
    expect(ranges.map((r) => [text(state, r), r.style])).toEqual([['[!info]', 'info']]);
  });

  it('ignores markers that are not the start of a blockquote', () => {
    const state = stateOf('> plain [!note] later\n\n> text\n> [!note] second line\n\n[!note] outside a quote\n\n> [! note] spaced\n');
    expect(calloutMarkerRanges(state)).toEqual([]);
  });

  it('limits the scan to the requested range', () => {
    const state = stateOf('> [!note] a\n\n> [!tip] b\n');
    const second = state.doc.line(3);
    expect(calloutMarkerRanges(state, second.from, second.to).map((r) => r.style)).toEqual(['tip']);
  });
});
});
