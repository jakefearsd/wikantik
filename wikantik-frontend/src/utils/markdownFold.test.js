import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { codeFolding, foldEffect, foldable, foldedRanges, syntaxTree, unfoldEffect } from '@codemirror/language';
import { editorFoldConfig, frontmatterFold, frontmatterFoldRange, revealEffects } from './markdownFold';

const DIALECTS = [['CommonMark', {}], ['GFM', { base: markdownLanguage }]];

describe.each(DIALECTS)('markdownFold (%s)', (_label, base) => {
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
    let state = EditorState.create({ doc: '# A\none\ntwo\n# B\nthree', extensions: [markdown(base), codeFolding()] });
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

describe('editorFoldConfig — fold markers only for headings, frontmatter and fences', () => {
  const DOC = [
    '---',                    // 1 frontmatter open
    'title: x',               // 2
    'tags: [a]',              // 3
    '---',                    // 4 frontmatter close (would be a setext underline)
    '# One',                  // 5
    'para line one',          // 6 multi-line paragraph
    'para line two',          // 7
    '',                       // 8
    '> quote one',            // 9 multi-line blockquote
    '> quote two',            // 10
    '',                       // 11
    '- item one',             // 12 list with a multi-line item
    '  continued',            // 13
    '- item two',             // 14
    '',                       // 15
    '## Sub',                 // 16
    'sub body',               // 17
    '',                       // 18
    '```js',                  // 19 fence
    'const a = 1;',           // 20
    '```',                    // 21
    '',                       // 22
    '# Two',                  // 23
    'tail',                   // 24
  ].join('\n');

  const state = EditorState.create({
    doc: DOC,
    extensions: [markdown({ ...base, extensions: editorFoldConfig }), frontmatterFold],
  });
  const at = (n) => foldable(state, state.doc.line(n).from, state.doc.line(n).to);

  it('offers no fold on paragraph, blockquote or list lines', () => {
    expect(at(6)).toBeNull();
    expect(at(9)).toBeNull();
    expect(at(12)).toBeNull();
    expect(at(13)).toBeNull();
  });

  it('offers no fold inside the frontmatter body (no setext heading there)', () => {
    expect(at(2)).toBeNull();
    expect(at(3)).toBeNull();
  });

  it('folds the leading frontmatter block', () => {
    expect(at(1)).toEqual({ from: state.doc.line(1).to, to: state.doc.line(4).to });
  });

  it('folds a heading up to (not into) the next heading of the same or higher level', () => {
    const h1 = at(5);
    expect(h1.from).toBe(state.doc.line(5).to);
    expect(h1.to).toBeLessThan(state.doc.line(23).from);
    expect(h1.to).toBeGreaterThanOrEqual(state.doc.line(21).to);
    const h2 = at(16);
    expect(h2.from).toBe(state.doc.line(16).to);
    expect(h2.to).toBeLessThan(state.doc.line(23).from);
  });

  it('folds a fenced code block from the end of its opening fence', () => {
    expect(at(19)).toEqual({ from: state.doc.line(19).to, to: state.doc.line(21).to });
  });

  it('offers no fold for an unclosed leading --- block', () => {
    const s = EditorState.create({ doc: '---\npara one\npara two\n', extensions: [markdown({ ...base, extensions: editorFoldConfig }), frontmatterFold] });
    expect(foldable(s, 0, 3)).toBeNull();
    expect(foldable(s, s.doc.line(2).from, s.doc.line(2).to)).toBeNull();
  });
});

describe('editorFoldConfig — only YAML-looking, closed leading blocks are frontmatter', () => {
  const mk = (doc) => EditorState.create({ doc, extensions: [markdown({ ...base, extensions: editorFoldConfig }), frontmatterFold] });
  const foldAtLine = (state, n) => foldable(state, state.doc.line(n).from, state.doc.line(n).to);
  const topNodes = (state) => {
    const names = [];
    for (let c = syntaxTree(state).topNode.firstChild; c; c = c.nextSibling) names.push(c.name);
    return names;
  };

  it('an unclosed leading --- is a horizontal rule and the rest of the document still parses', () => {
    const state = mk('---\n\n# Title\nbody one\n\n```js\nx\n```\n\n# Next\n');
    expect(topNodes(state)).not.toContain('Frontmatter');
    expect(topNodes(state)[0]).toBe('HorizontalRule');
    expect(foldAtLine(state, 1)).toBeNull();
    expect(foldAtLine(state, 3)).not.toBeNull();   // # Title
    expect(foldAtLine(state, 6)).not.toBeNull();   // ```js
  });

  it('two rules around prose are not frontmatter', () => {
    const state = mk('---\n\nSome text\n\n---\n\n# After\nx\n');
    expect(topNodes(state)).not.toContain('Frontmatter');
    expect(foldAtLine(state, 1)).toBeNull();
    expect(foldAtLine(state, 7)).not.toBeNull();
  });

  it('a closed block whose first line is not a key: line is not frontmatter', () => {
    expect(topNodes(mk('---\nplain words\n---\n'))).not.toContain('Frontmatter');
    expect(topNodes(mk('---\n\ntitle: x\n---\n'))).not.toContain('Frontmatter');
  });

  it('real frontmatter still parses as one block and folds', () => {
    const state = mk('---\ntitle: x\ntags: [a]\n---\n# H\nbody\n');
    expect(topNodes(state)[0]).toBe('Frontmatter');
    expect(foldAtLine(state, 1)).toEqual({ from: 3, to: state.doc.line(4).to });
    expect(foldAtLine(state, 2)).toBeNull();
  });
});
});
