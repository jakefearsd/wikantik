/**
 * updateBlockCandidates (the incremental block-widget scan) must equal a full blockCandidates rescan after
 * every kind of edit: typing, deletions that merge blocks, opening/closing fences and $$ blocks, multi-range
 * (multi-cursor) changes, and undo/redo.
 */
import { describe, it, expect } from 'vitest';
import { EditorState, EditorSelection } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { syntaxTree, ensureSyntaxTree } from '@codemirror/language';
import { history, undo, redo } from '@codemirror/commands';
import { editorMarkdownConfig } from '../editorMarkdown';
import { blockCandidates, updateBlockCandidates, referenceDefs, updateReferenceDefs, referenceDefsField, referenceLabels } from './ranges';
import { livePreview } from './index';

const context = { attachments: ['pic.png'] };
const extensions = [markdown(editorMarkdownConfig), history(), EditorState.allowMultipleSelections.of(true)];

/** Deterministic PRNG (mulberry32), so a failure reproduces from its seed. */
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

const BLOCKS = [
  '$$\nx^2\n$$', '![[Other]]', '![[Other#Intro]]', '![[pic.png]]', 'plain paragraph text', '# Heading',
  '- item\n- [ ] task', '```\ncode $$\n```', '> quote\n> more', '   $$\n   y\n   $$', 'Setext\n---', '$$\na\n\nb\n$$',
  '```mermaid\nflowchart TD\n  A-->B\n```', '```mermaid example\nx\n```', '~~~mermaid\ngraph LR\n~~~',
  '| a | b |\n|:--|--:|\n| 1 | 2 |', 'h1 | h2\n---|---\nc | d', '| a |\n|---|\n| 1 |\nlazy',
];
const TOKENS = ['mermaid', '```mermaid\n', '~~~', '\n```\n', '\n', '\n\n', '$$', '$$\n', '\n$$', '```', '```\n', '![[X]]', '![[Y#H]]', 'word', '---', '===', '> ', '- ', '   ', '![',
  '|', ' | ', '|---|', '\n| x | y |', ':-:', '\\|'];

function initialDoc(rand) {
  const parts = [];
  for (let i = 0; i < 40; i += 1) parts.push(BLOCKS[Math.floor(rand() * BLOCKS.length)]);
  return parts.join('\n\n');
}

function randomChange(rand, len) {
  const pos = Math.floor(rand() * (len + 1));
  if (rand() < 0.35 && len > 0) return { from: pos, to: Math.min(len, pos + 1 + Math.floor(rand() * 12)) };
  return { from: pos, insert: TOKENS[Math.floor(rand() * TOKENS.length)] };
}

function nextTransaction(rand, state) {
  const roll = rand();
  if (roll < 0.08) {
    let tr = null;
    (roll < 0.04 ? undo : redo)({ state, dispatch: (t) => { tr = t; } });
    if (tr) return tr;
  }
  const len = state.doc.length;
  if (roll < 0.25) { // a multi-cursor edit: the same text inserted at two places in one transaction
    const a = Math.floor(rand() * (len + 1));
    const b = Math.floor(rand() * (len + 1));
    const insert = TOKENS[Math.floor(rand() * TOKENS.length)];
    return state.update({ changes: [{ from: Math.min(a, b), insert }, ...(a === b ? [] : [{ from: Math.max(a, b), insert }])], userEvent: 'input.type' });
  }
  return state.update({ changes: randomChange(rand, len), userEvent: 'input.type' });
}

describe('updateBlockCandidates', () => {
  it('equals a full rescan after every random edit (typing, merges, fences, multi-cursor, undo/redo)', () => {
    for (let seed = 1; seed <= 40; seed += 1) {
      const rand = rng(seed);
      let state = EditorState.create({ doc: initialDoc(rand), extensions });
      let candidates = blockCandidates(state, context);
      for (let step = 0; step < 60; step += 1) {
        const tr = nextTransaction(rand, state);
        const incremental = tr.docChanged ? updateBlockCandidates(candidates, tr, context) : candidates;
        state = tr.state;
        const full = blockCandidates(state, context);
        expect(incremental, `seed ${seed} step ${step}`).toEqual(full);
        candidates = incremental;
      }
    }
  });

  it('falls back to a full scan when the old tree did not cover the document (a partial parse)', () => {
    // > 3,000 chars: the initial parse stops early, so the old state's tree (and candidates) are partial,
    // while the parse context, advanced here as the background parser would, yields a full tree next.
    const doc = Array.from({ length: 300 }, (_, i) => `para ${i}\n\n$$\nx_{${i}}\n$$`).join('\n\n');
    const state = EditorState.create({ doc, extensions });
    expect(syntaxTree(state).length).toBeLessThan(doc.length);
    const prev = blockCandidates(state, context);
    ensureSyntaxTree(state, doc.length, 5000);
    const tr = state.update({ changes: { from: 0, insert: 'x' } });
    expect(syntaxTree(tr.state).length).toBe(tr.state.doc.length);
    const full = blockCandidates(tr.state, context);
    expect(full).toHaveLength(300);
    expect(updateBlockCandidates(prev, tr, context)).toEqual(full);
  });

  it('matches a full scan when the NEW parse is partial (the parser has not reached the end yet)', () => {
    const doc = Array.from({ length: 300 }, (_, i) => `para ${i}\n\n$$\nx_{${i}}\n$$`).join('\n\n');
    const created = EditorState.create({ doc, extensions });
    ensureSyntaxTree(created, doc.length, 5000);
    const old = created.update({ changes: { from: doc.length, insert: '\n' } }).state; // its field now holds the full tree
    expect(syntaxTree(old).length).toBe(old.doc.length);
    const real = old.update({ changes: { from: 0, insert: 'x' } });
    // The same edit landing on a state whose parse has only covered the start of the page (> 3,000 chars).
    const partial = EditorState.create({ doc: real.state.doc, extensions });
    expect(syntaxTree(partial).length).toBeLessThan(partial.doc.length);
    const tr = { changes: real.changes, startState: old, state: partial };
    const full = blockCandidates(partial, context);
    expect(full.length).toBeLessThan(300);
    expect(updateBlockCandidates(blockCandidates(old, context), tr, context)).toEqual(full);
  });

  it('opening a fence above math hides every later block; closing it brings them back', () => {
    let state = EditorState.create({ doc: 'intro\n\n$$\na\n$$\n\n![[P]]\n', extensions });
    let candidates = blockCandidates(state, context);
    expect(candidates).toHaveLength(2);
    let tr = state.update({ changes: { from: 0, insert: '```\n' } });
    candidates = updateBlockCandidates(candidates, tr, context);
    state = tr.state;
    expect(candidates).toEqual([]);
    tr = state.update({ changes: { from: 0, to: 4 } });
    candidates = updateBlockCandidates(candidates, tr, context);
    expect(candidates).toEqual(blockCandidates(tr.state, context));
    expect(candidates).toHaveLength(2);
  });

  it('incrementalUpdateMatchesFullScan for a fence edit (typing inside, closing, unclosing)', () => {
    let state = EditorState.create({ doc: 'intro\n\n```mermaid\nA-->B\n```\n\n$$\nx\n$$\n', extensions });
    let candidates = blockCandidates(state, context);
    expect(candidates.map((c) => c.widget.type)).toEqual(['fence', 'math']);
    const edits = [{ from: 22, insert: 'C' }, { from: 26, to: 29 }, { from: 26, insert: '```' }, { from: 8, insert: 'x' }];
    for (const changes of edits) {
      const tr = state.update({ changes });
      candidates = updateBlockCandidates(candidates, tr, context);
      state = tr.state;
      expect(candidates).toEqual(blockCandidates(state, context));
    }
  });

  it('incrementalUpdateMatchesFullScan for edits inside, before, after and adjacent to a table', () => {
    const doc = 'intro\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n$$\nx\n$$\n';
    const at = (s) => doc.indexOf(s);
    const edits = [
      { from: at('| 1') + 2, insert: 'Z' }, // inside a body cell
      { from: at('|---'), to: at('|---') + 4, insert: '|x' }, // breaks the delimiter row: no longer a table
      { from: 0, insert: 'pre ' }, // before
      { from: at('$$'), insert: 'after\n\n' }, // after
      { from: at('| 2 |') + 5, insert: '\nlazy' }, // adjacent: a lazy-continuation row
      { from: at('| 2 |') + 5, insert: '\n' }, // adjacent: a blank line ends the table
      { from: at('intro') + 5, insert: '\n' }, // adjacent above: the blank line before it removed/added
      { from: at('intro') + 5, to: at('| a') }, // the paragraph above runs into the header row
    ];
    for (const changes of edits) {
      let state = EditorState.create({ doc, extensions });
      let candidates = blockCandidates(state, context);
      expect(candidates.map((c) => c.widget.type)).toEqual(['table', 'math']);
      const tr = state.update({ changes });
      candidates = updateBlockCandidates(candidates, tr, context);
      state = tr.state;
      expect(candidates, JSON.stringify(changes)).toEqual(blockCandidates(state, context));
    }
  });

  it('maps the blocks after an edit without re-reading them', () => {
    const state = EditorState.create({ doc: 'a\n\n$$\nx\n$$\n\n![[P]]', extensions, selection: EditorSelection.cursor(1) });
    const before = blockCandidates(state, context);
    const tr = state.update({ changes: { from: 1, insert: 'bc' } });
    const after = updateBlockCandidates(before, tr, context);
    expect(after.map((c) => [c.from, c.to])).toEqual(before.map((c) => [c.from + 2, c.to + 2]));
    expect(after.map((c) => c.widget)).toEqual(before.map((c) => c.widget));
  });

  it('blank lines pasted above a leading block push it down instead of keeping a stale copy at the top', () => {
    for (const doc of ['![[P]]\n', '$$\nx\n$$\n\ntext', '| a |\n|---|\n| 1 |\n']) {
      const state = EditorState.create({ doc, extensions });
      const tr = state.update({ changes: { from: 0, insert: '\n'.repeat(30) } });
      expect(updateBlockCandidates(blockCandidates(state, context), tr, context), doc).toEqual(blockCandidates(tr.state, context));
    }
  });

  it('returns an empty list for an emptied document', () => {
    const state = EditorState.create({ doc: '$$\nx\n$$', extensions });
    const tr = state.update({ changes: { from: 0, to: state.doc.length } });
    expect(updateBlockCandidates(blockCandidates(state, context), tr, context)).toEqual([]);
  });
});

const REF_BLOCKS = [
  '[foo]: /u', '[Foo  Bar]: <x> "t"', '> [q]: /u', '- [li]: /u\n- item', '[^fn]: note', 'see [foo] and [bar][foo]',
  '```\n[code]: /u\n```', '| a |\n|---|\n| [t]: u |', 'para\n[lazy]: /u', '[multi\nline]: /u', '# [h]: /u', 'plain text',
  '> quote\n> [deep]: /x', '    [indented]: /u',
];
const REF_TOKENS = ['[', ']', ']: /u', '[x]: y\n', '\n', '\n\n', '> ', '- ', '^', '```\n', '\n```\n', 'word', '    ', '|---|', '[^'];

describe('updateReferenceDefs', () => {
  it('equals a full walk after every random edit (typing, merges, quotes, lists, fences, multi-cursor)', () => {
    for (let seed = 1; seed <= 40; seed += 1) {
      const rand = rng(seed);
      const parts = [];
      for (let i = 0; i < 30; i += 1) parts.push(REF_BLOCKS[Math.floor(rand() * REF_BLOCKS.length)]);
      let state = EditorState.create({ doc: parts.join('\n\n'), extensions });
      let defs = referenceDefs(state);
      for (let step = 0; step < 60; step += 1) {
        const len = state.doc.length;
        const insert = REF_TOKENS[Math.floor(rand() * REF_TOKENS.length)];
        const a = Math.floor(rand() * (len + 1));
        const b = Math.floor(rand() * (len + 1));
        const roll = rand();
        let changes;
        if (roll < 0.3 && len > 0) changes = { from: a, to: Math.min(len, a + 1 + Math.floor(rand() * 12)) };
        else if (roll < 0.5) changes = [{ from: Math.min(a, b), insert }, ...(a === b ? [] : [{ from: Math.max(a, b), insert }])];
        else changes = { from: a, insert };
        const tr = state.update({ changes });
        defs = updateReferenceDefs(defs, tr);
        state = tr.state;
        expect(defs, `seed ${seed} step ${step}`).toEqual(referenceDefs(state));
      }
    }
  });

  it('an edit in the indentation before the first block re-reads it (no stale definition kept at the top)', () => {
    for (const insert of ['    ', '\n'.repeat(30)]) {
      const state = EditorState.create({ doc: '[foo]: /u\n\n[bar]: /b\n', extensions });
      const tr = state.update({ changes: { from: 0, insert } });
      expect(updateReferenceDefs(referenceDefs(state), tr), JSON.stringify(insert)).toEqual(referenceDefs(tr.state));
    }
  });

  it('falls back to a full walk when the old tree did not cover the document (a partial parse)', () => {
    const doc = Array.from({ length: 300 }, (_, i) => `para ${i}\n\n[ref${i}]: /u${i}`).join('\n\n');
    const state = EditorState.create({ doc, extensions });
    expect(syntaxTree(state).length).toBeLessThan(doc.length);
    const prev = referenceDefs(state);
    ensureSyntaxTree(state, doc.length, 5000);
    const tr = state.update({ changes: { from: 0, insert: 'x' } });
    const full = referenceDefs(tr.state);
    expect(full).toHaveLength(300);
    expect(updateReferenceDefs(prev, tr)).toEqual(full);
  });

  it('the live-preview extension carries the definitions through an edit once asked, instead of re-walking the page', () => {
    const doc = 'see [foo]\n\n[foo]: /u\n\n> [Bar]: /b\n';
    let state = EditorState.create({ doc, extensions: [...extensions, livePreview()] });
    expect(state.field(referenceDefsField)).toBeNull(); // nothing asked yet: no walk at all
    expect([...referenceLabels(state)]).toEqual(['foo', 'bar']);
    state = state.update({ changes: { from: 0, insert: 'x' } }).state;
    const kept = state.field(referenceDefsField);
    expect(kept.tree).toBe(syntaxTree(state));
    expect(kept.defs).toEqual(referenceDefs(state));
    expect(referenceLabels(state)).toBe(kept.labels);
    state = state.update({ changes: { from: state.doc.length, insert: '\n[baz]: /z\n' } }).state;
    expect([...referenceLabels(state)]).toEqual(['foo', 'bar', 'baz']);
    expect(state.field(referenceDefsField).labels).toBe(referenceLabels(state));
  });
});
