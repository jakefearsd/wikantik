import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { codeFolding, foldEffect, foldedRanges, unfoldEffect } from '@codemirror/language';
import { frontmatterFoldRange, revealEffects } from './markdownFold';

describe('frontmatterFoldRange', () => {
  it('folds a leading frontmatter block from the end of the opening fence to the closing fence', () => {
    const state = EditorState.create({ doc: '---\ntitle: x\ntags: [a]\n---\nbody' });
    expect(frontmatterFoldRange(state, 0)).toEqual({ from: 3, to: state.doc.line(4).to });
  });
  it('offers nothing for other lines or unclosed blocks', () => {
    expect(frontmatterFoldRange(EditorState.create({ doc: '---\ntitle: x\n---' }), 4)).toBeNull();
    expect(frontmatterFoldRange(EditorState.create({ doc: '---\nnever closed' }), 0)).toBeNull();
    expect(frontmatterFoldRange(EditorState.create({ doc: 'text\n---\n' }), 0)).toBeNull();
  });
});

describe('revealEffects', () => {
  it('returns an unfold effect for each fold containing the position', () => {
    let state = EditorState.create({ doc: '# A\none\ntwo\n# B\nthree', extensions: [markdown(), codeFolding()] });
    state = state.update({ effects: foldEffect.of({ from: 3, to: 11 }) }).state;
    const effects = revealEffects(state, 6);
    expect(effects).toHaveLength(1);
    expect(effects[0].is(unfoldEffect)).toBe(true);
    const after = state.update({ effects }).state;
    expect(foldedRanges(after).size).toBe(0);
  });
  it('returns nothing outside folds or without the fold state', () => {
    expect(revealEffects(EditorState.create({ doc: 'x' }), 0)).toEqual([]);
  });
});
