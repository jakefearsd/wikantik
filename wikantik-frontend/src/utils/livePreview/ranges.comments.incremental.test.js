/**
 * The live preview's `%%comment%%` ranges are carried through edits (commentRangesField): an edit that cannot change
 * what is code, math or a comment marker maps the previous ranges instead of rescanning the document. Whatever the
 * edit, the carried ranges must equal a full commentRanges scan of the new text.
 */
import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { history } from '@codemirror/commands';
import { editorMarkdownConfig } from '../editorMarkdown';
import { commentRanges } from '../obsidianComments';
import { commentsOf, commentScans } from './ranges';
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

const ranges = (doc) => commentsOf(doc).map((r) => [r.from, r.to]);
const full = (doc) => commentRanges(doc.toString()).map((r) => [r.from, r.to]);

describe('commentRangesField: carried comment ranges equal a full scan', () => {
  it.each([1, 2, 3, 4, 5, 6, 7, 8])('random edits, seed %i', (seed) => {
    const rand = rng(seed);
    const pick = (list) => list[Math.floor(rand() * list.length)];
    const doc = Array.from({ length: 8 }, () => pick(PARTS)).join('\n\n');
    let state = EditorState.create({ doc, extensions });
    expect(ranges(state.doc)).toEqual(full(state.doc));
    const scansBefore = commentScans();
    for (let step = 0; step < 150; step += 1) {
      const len = state.doc.length;
      const from = Math.floor(rand() * (len + 1));
      const del = rand() < 0.3 ? Math.min(len - from, Math.floor(rand() * 4)) : 0;
      state = state.update({ changes: { from, to: from + del, insert: rand() < 0.2 ? '' : pick(TYPED) } }).state;
      expect(ranges(state.doc), `seed ${seed} step ${step}: ${JSON.stringify(state.doc.toString())}`).toEqual(full(state.doc));
    }
    // full() scans too, without the cache: only ranges() counts. Some edits took the carried path, or this proves nothing.
    expect(commentScans() - scansBefore).toBeLessThan(150);
  });

  it('typing ordinary text on a line with no marker character maps the ranges without rescanning', () => {
    let state = EditorState.create({ doc: 'Intro %%a%% text\n\nplain words with `code` and $x$ here\n\n```mermaid\n%% c\n```\n', extensions });
    ranges(state.doc);
    const before = commentScans();
    const at = state.doc.toString().indexOf('words') + 2;
    for (let i = 0; i < 20; i += 1) state = state.update({ changes: { from: at + i, insert: 'x' } }).state;
    expect(commentScans()).toBe(before);
    expect(ranges(state.doc)).toEqual(full(state.doc));
  });

  it('typing in a math paragraph that holds a %% rescans', () => {
    let state = EditorState.create({ doc: 'Intro $a\nwords here %%x%% b$\n', extensions });
    ranges(state.doc);
    const before = commentScans();
    state = state.update({ changes: { from: 8, insert: 'x' } }).state;
    expect(ranges(state.doc)).toEqual(full(state.doc));
    expect(commentScans()).toBe(before + 1);
  });

  it('typing on a line that holds a marker character rescans', () => {
    let state = EditorState.create({ doc: 'Intro %%a%% text\n\nplain\n', extensions });
    ranges(state.doc);
    const before = commentScans();
    state = state.update({ changes: { from: 8, insert: 'x' } }).state;
    expect(ranges(state.doc)).toEqual(full(state.doc));
    expect(commentScans()).toBe(before + 1);
  });
});
