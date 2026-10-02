/**
 * End-to-end property of the block-widget field: after ANY transaction its decorations are exactly what a
 * from-scratch blockSpecs(state, activeLines) would produce. Random steps mix caret moves, multi-range
 * selections, typing at the caret, external single-range edits with the caret elsewhere, deletions, and
 * undo/redo, on small documents (fully parsed) and on a large one (> 3,000 chars: the parse stays partial).
 */
import { describe, it, expect } from 'vitest';
import { EditorState, EditorSelection } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { syntaxTree } from '@codemirror/language';
import { history, undo, redo } from '@codemirror/commands';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview } from './index';
import { blockField, setLiveMode } from './plugin';
import { blockSpecs, activeLinesOf } from './ranges';

const context = { attachments: ['pic.png'] };

function rng(seed) { // mulberry32
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const BLOCKS = [
  '$$\nx^2\n$$', '![[Other]]', '![[Other#Intro]]', '![[pic.png]]', 'plain paragraph text', '# Heading',
  '- item\n- [ ] task', '```\ncode $$\n```', '> quote\n> more', '   $$\n   y\n   $$', 'Setext\n---',
];
const TOKENS = ['\n', '\n\n', '$$', '$$\n', '\n$$', '```\n', '![[X]]', 'word', '---', '> ', '- ', '   '];

const pick = (rand, list) => list[Math.floor(rand() * list.length)];
const pos = (rand, state) => Math.floor(rand() * (state.doc.length + 1));

function step(rand, state) {
  const roll = rand();
  if (roll < 0.25) return state.update({ selection: { anchor: pos(rand, state) }, userEvent: 'select' });
  if (roll < 0.35) {
    const ranges = [EditorSelection.cursor(pos(rand, state)), EditorSelection.range(pos(rand, state), pos(rand, state))];
    return state.update({ selection: EditorSelection.create(ranges), userEvent: 'select' });
  }
  if (roll < 0.55) { // typing at the caret
    const at = state.selection.main.head;
    const insert = pick(rand, TOKENS);
    return state.update({ changes: { from: at, insert }, selection: { anchor: at + insert.length }, userEvent: 'input.type' });
  }
  if (roll < 0.75) { // an external edit somewhere else; the caret only maps
    return state.update({ changes: { from: pos(rand, state), insert: pick(rand, TOKENS) } });
  }
  if (roll < 0.88 && state.doc.length > 0) {
    const from = pos(rand, state);
    return state.update({ changes: { from, to: Math.min(state.doc.length, from + 1 + Math.floor(rand() * 10)) }, userEvent: 'delete' });
  }
  let tr = null;
  (roll < 0.94 ? undo : redo)({ state, dispatch: (t) => { tr = t; } });
  return tr || state.update({ selection: { anchor: pos(rand, state) } });
}

const shownOf = (state) => {
  const out = [];
  for (const it = state.field(blockField).decos.iter(); it.value; it.next()) {
    out.push({ from: it.from, to: it.to, widget: it.value.spec.lpWidget });
  }
  return out;
};
const expectedOf = (state) => blockSpecs(state, activeLinesOf(state), context).map(({ from, to, widget }) => ({ from, to, widget }));

function liveState(doc) {
  const state = EditorState.create({
    doc,
    extensions: [markdown(editorMarkdownConfig), history(), EditorState.allowMultipleSelections.of(true), livePreview({ getContext: () => context })],
  });
  return state.update({ effects: setLiveMode.of(true) }).state;
}

function run(seeds, steps, makeDoc) {
  for (let seed = 1; seed <= seeds; seed += 1) {
    const rand = rng(seed);
    let state = liveState(makeDoc(rand));
    for (let i = 0; i < steps; i += 1) {
      state = step(rand, state).state;
      expect(shownOf(state), `seed ${seed} step ${i}`).toEqual(expectedOf(state));
    }
  }
}

describe('block-widget field (end to end)', () => {
  it('always shows exactly the from-scratch block specs (200 seeds x 60 random steps)', () => {
    run(200, 60, (rand) => Array.from({ length: 30 }, () => pick(rand, BLOCKS)).join('\n\n'));
  });

  it('holds on a large page whose parse stays partial', () => {
    const large = () => Array.from({ length: 200 }, (_, i) => `para ${i}\n\n$$\nx_{${i}}\n$$\n\n![[P${i}]]`).join('\n\n');
    expect(syntaxTree(liveState(large())).length).toBeLessThan(large().length);
    run(10, 40, large);
  });
});
