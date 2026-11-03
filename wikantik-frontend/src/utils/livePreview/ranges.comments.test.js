/**
 * The live preview's `%%comment%%` ranges are read off the editor's syntax tree (commentsOf): whatever the edit, they
 * must equal the ranges of a fresh parse of the same text, a tree that does not cover the document yet must not throw,
 * and typing in a large page must not cost a rescan of the whole document's code and math.
 */
import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { history } from '@codemirror/commands';
import { EditorView } from '@codemirror/view';
import { ensureSyntaxTree, forceParsing, syntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { commentRanges } from '../obsidianComments';
import { commentsOf } from './ranges';
import { livePreview } from './index';

const extensions = [markdown(editorMarkdownConfig), history(), livePreview()];

function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const PARTS = ['plain words here', 'a %%note%% b', '%%\nmulti line\n%%', '```mermaid\ngraph TD\n%% c\nA-->B\n```',
  '> ```\n> %% q %%\n> ```', '- item %%x%%\n    indented', '$a %% b$ and %%c%%', '$$\nx %% y\n$$', 'Use `%%` here',
  '50%% off', '    code %%k%%', '> [!note]\n> body %%n%%', 'a $$x %% y$$ z', '$a\nb %%x%% c$ d', 'Para `x` and $y$ math',
  'a \\$ b\nc %%d%%', '1. item\n2. two %%t%%', '```js\ncode\n```', '$$\nx\n$$\n%%after%%'];
const TYPED = ['a', 'b', ' ', 'z', 'q', 'r', '%', '`', '$', '>', '-', '\n', '~', '\\', '1', '.', ' x '];

const pairs = (list) => list.map((r) => [r.from, r.to]);
const ranges = (state) => pairs(commentsOf(state));
/** A fresh, independent parse of the same text. */
const fresh = (state) => pairs(commentRanges(state.doc.toString()));

describe('commentsOf: ranges read off the tree equal those of a fresh parse', () => {
  it.each([1, 2, 3, 4, 5, 6, 7, 8])('random edits, seed %i', (seed) => {
    const rand = rng(seed);
    const pick = (list) => list[Math.floor(rand() * list.length)];
    const doc = Array.from({ length: 8 }, () => pick(PARTS)).join('\n\n');
    let state = EditorState.create({ doc, extensions });
    ensureSyntaxTree(state, state.doc.length, 5000);
    expect(ranges(state)).toEqual(fresh(state));
    for (let step = 0; step < 150; step += 1) {
      const len = state.doc.length;
      const from = Math.floor(rand() * (len + 1));
      const del = rand() < 0.3 ? Math.min(len - from, Math.floor(rand() * 4)) : 0;
      state = state.update({ changes: { from, to: from + del, insert: rand() < 0.2 ? '' : pick(TYPED) } }).state;
      ensureSyntaxTree(state, state.doc.length, 5000);
      expect(ranges(state), `seed ${seed} step ${step}: ${JSON.stringify(state.doc.toString())}`).toEqual(fresh(state));
    }
  });

  it('is memoised per document and tree: the same array comes back until either changes', () => {
    let state = EditorState.create({ doc: 'a %%x%% b\n', extensions });
    ensureSyntaxTree(state, state.doc.length, 5000);
    const first = commentsOf(state);
    expect(commentsOf(state)).toBe(first);
    state = state.update({ changes: { from: 0, insert: 'z' } }).state;
    ensureSyntaxTree(state, state.doc.length, 5000);
    expect(commentsOf(state)).not.toBe(first);
    expect(ranges(state)).toEqual([[3, 8]]);
  });

  it('a state whose tree is partial does not throw, and the ranges are right once the parse completes', () => {
    // A long page: a fence with a `%%` line sits far past what the first synchronous parse covers.
    const body = `%% head %%\n\n${'filler paragraph\n\n'.repeat(40000)}\`\`\`mermaid\n%% in fence\n\`\`\`\n\ntail %%x%% end\n`;
    const view = new EditorView({ state: EditorState.create({ doc: body, extensions }), parent: document.body });
    try {
      expect(syntaxTree(view.state).length).toBeLessThan(body.length); // the premise: the tree does not reach the end yet
      expect(() => commentsOf(view.state)).not.toThrow();
      expect(forceParsing(view, body.length, 20000)).toBe(true);
      expect(syntaxTree(view.state).length).toBe(body.length);
      const tailAt = body.indexOf('%%x%%');
      expect(ranges(view.state)).toEqual([[0, 10], [tailAt, tailAt + 5]]);
    } finally {
      view.destroy();
    }
  });

  it('typing in a 160K page does not rescan the document: 200 keystrokes stay well inside a budget', () => {
    const page = `Intro %%a%% text\n\n${'plain words with `code` and $x$ here\n\n'.repeat(3500)}\`\`\`mermaid\n%% c\n\`\`\`\n`;
    let state = EditorState.create({ doc: page, extensions });
    ensureSyntaxTree(state, state.doc.length, 20000);
    commentsOf(state);
    const at = page.indexOf('words') + 2;
    const start = performance.now();
    for (let i = 0; i < 200; i += 1) {
      state = state.update({ changes: { from: at + i, insert: 'x' } }).state;
      ensureSyntaxTree(state, state.doc.length, 20000);
      commentsOf(state);
    }
    expect(performance.now() - start).toBeLessThan(4000);
    expect(ranges(state)).toEqual(fresh(state));
  });
});
