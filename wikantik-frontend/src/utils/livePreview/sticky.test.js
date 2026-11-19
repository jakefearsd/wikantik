/**
 * Sticky ranges on a real EditorView: a sticky decoration stays hidden while the caret is on its line, the caret
 * cannot rest before or inside it, and Backspace right after a line marker removes exactly its text. Block markers
 * (heading, bullet, number, task box, quote mark) are the sticky ranges here; a line can carry several.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { ChangeSet, EditorState, EditorSelection, StateEffect, Transaction } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap, indentWithTab, history, undo, redo } from '@codemirror/commands';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { livePreviewPlugin } from './plugin';
import { livePreviewSpecs, activeLinesOf } from './ranges';
import { stickyRangesOf, removeLineMarker, leaveLineMarker } from './sticky';
import { collab, receiveUpdates, sendableUpdates } from '@codemirror/collab';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from '../../test/linearCost';

const context = { pageName: 'Sticky', attachments: [] };
const DOC = 'Para one\n## Title\nAfter';
const H = DOC.indexOf('## '); // heading line start (9); its marker `## ` is [H, H + 3)

const views = [];
afterEach(() => { while (views.length) { const v = views.pop(); v.destroy(); v.dom.parentNode?.remove(); } });

function mount(doc = DOC, { live = true, extra = [] } = {}) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc,
      extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: () => context }), keymap.of(defaultKeymap), extra],
    }),
  });
  views.push(view);
  ensureSyntaxTree(view.state, view.state.doc.length, 60000);
  if (live) setLivePreview(view, true);
  return view;
}

function press(view, key, init = {}) {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
  view.contentDOM.dispatchEvent(event);
  return event.defaultPrevented;
}
const caretAt = (view, pos) => view.dispatch({ selection: { anchor: pos } });
const head = (view) => view.state.selection.main.head;
const text = (view) => view.state.doc.toString();
const type = (view, ch) => {
  const pos = head(view);
  view.dispatch({ changes: { from: pos, insert: ch }, selection: { anchor: pos + ch.length }, userEvent: 'input.type' });
};
const rangesOf = (set) => {
  const out = [];
  for (const it = set.iter(); it.value; it.next()) out.push([it.from, it.to]);
  return out;
};
const hiddenRanges = (view) => {
  const out = [];
  for (const it = view.plugin(livePreviewPlugin).decorations.iter(); it.value; it.next()) {
    if (it.to > it.from && !it.value.spec.class) out.push([it.from, it.to]);
  }
  return out;
};

describe('sticky specs', () => {
  it('a sticky spec is emitted on an active line and carries sticky: true; a non-sticky one is not emitted there', () => {
    const view = mount('Para one\n## *Ti*tle\nAfter');
    const hides = () => livePreviewSpecs(view.state, activeLinesOf(view.state), { context }).filter((s) => s.kind === 'hide');

    caretAt(view, 0);
    expect(hides().map((s) => [s.from, s.to, s.sticky === true])).toEqual([[H, H + 3, true], [H + 3, H + 4, false], [H + 6, H + 7, false]]);
    caretAt(view, H + 9);
    expect(hides()).toEqual([expect.objectContaining({ from: H, to: H + 3, sticky: true })]);
  });

  it('a page with no block marker has no sticky spec and no sticky range', () => {
    const view = mount('Para *one*\n\nAfter `code`');
    caretAt(view, 0);
    const specs = livePreviewSpecs(view.state, activeLinesOf(view.state), { context });
    expect(specs.some((s) => s.kind === 'hide')).toBe(true);
    expect(specs.filter((s) => 'sticky' in s)).toEqual([]);
    expect(rangesOf(stickyRangesOf(view))).toEqual([]);
  });

  it('an ordered number stays visible and is still a sticky (atomic) range', () => {
    const view = mount('1. first\n\nend');
    caretAt(view, 5);
    expect(rangesOf(stickyRangesOf(view))).toEqual([[0, 3]]);
    expect(hiddenRanges(view)).toEqual([]);
    expect(view.contentDOM.querySelector('.cm-line').textContent).toBe('1. first');
  });

  it('the marker stays hidden with the caret on its line, and stickyRangesOf reports it', () => {
    const view = mount();
    caretAt(view, H + 5);
    expect(hiddenRanges(view)).toContainEqual([H, H + 3]);
    expect(rangesOf(stickyRangesOf(view))).toEqual([[H, H + 3]]);
  });

  it('stickyRangesOf is empty outside live mode', () => {
    const view = mount(DOC, { live: false });
    expect(rangesOf(stickyRangesOf(view))).toEqual([]);
  });
});

describe('sticky caret', () => {
  it('Home on a heading line puts the caret after the marker, and again leaves it there', () => {
    const view = mount();
    caretAt(view, H + 6);
    press(view, 'Home');
    expect(head(view)).toBe(H + 3);
    press(view, 'Home');
    expect(head(view)).toBe(H + 3);
  });

  it('Home on a first-line heading puts the caret at offset 3, not 0', () => {
    const view = mount('## Title');
    caretAt(view, 8);
    press(view, 'Home');
    expect(head(view)).toBe(3);
  });

  it('ArrowLeft from just after the marker goes to the end of the previous line', () => {
    const view = mount();
    caretAt(view, H + 3);
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(H - 1);
    expect(view.state.selection.main.empty).toBe(true);
  });

  it('word-left from just after the marker goes to the end of the previous line too', () => {
    const view = mount();
    caretAt(view, H + 3);
    press(view, 'ArrowLeft', { ctrlKey: true });
    expect(head(view)).toBe(H - 1);
  });

  it('Shift+ArrowLeft from just after the marker selects the whole marker (it is atomic)', () => {
    const view = mount();
    caretAt(view, H + 3);
    press(view, 'ArrowLeft', { shiftKey: true });
    expect(view.state.selection.main.anchor).toBe(H + 3);
    expect(head(view)).toBe(H);
  });

  it('ArrowLeft just after the marker of a first-line heading stays put', () => {
    const view = mount('## Title');
    caretAt(view, 3);
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(3);
  });

  it('ArrowRight from the end of the previous line lands after the marker', () => {
    const view = mount();
    caretAt(view, H - 1);
    press(view, 'ArrowRight');
    expect(head(view)).toBe(H + 3);
  });

  it.each([[0, 3], [1, 3], [2, 3], [3, 3], [4, 4]])('a caret placed at offset %i of the heading line rests at %i', (at, rests) => {
    const view = mount();
    caretAt(view, H + at);
    expect(head(view)).toBe(H + rests);
  });

  it('typing at the caret never inserts before the marker', () => {
    const view = mount();
    for (const at of [H, H + 1, H + 2]) {
      caretAt(view, at);
      type(view, 'x');
    }
    expect(text(view)).toBe('Para one\n## xxxTitle\nAfter');
  });

  it('a non-empty selection that ends inside the marker is left alone', () => {
    const view = mount();
    view.dispatch({ selection: { anchor: 2, head: H + 1 } });
    expect(view.state.selection.main.anchor).toBe(2);
    expect(head(view)).toBe(H + 1);
  });

  it('during composition the caret is not moved: a compose transaction', () => {
    const view = mount();
    caretAt(view, H + 5);
    view.dispatch({ selection: { anchor: H }, userEvent: 'input.type.compose' });
    expect(head(view)).toBe(H);
  });

  it('while the view is composing nothing moves; afterwards the next selection or change corrects the caret', () => {
    for (const next of [(view) => caretAt(view, H), (view) => view.dispatch({ changes: { from: H + 3, insert: 'k' } })]) {
      const view = mount();
      caretAt(view, H + 5);
      Object.defineProperty(view, 'composing', { get: () => true, configurable: true });
      view.dispatch({ selection: { anchor: H } });
      expect(head(view)).toBe(H);
      view.dispatch({ changes: { from: H + 3, insert: 'k' } }); // a change mid-composition does not move it either
      expect(head(view)).toBe(H);
      delete view.composing; // the composition ended with the caret before the marker: nothing is dispatched for that
      expect(head(view)).toBe(H);
      next(view);
      expect(head(view)).toBe(H + 3);
    }
  });

  it('every range of a multi-range selection obeys the rule', () => {
    const view = mount('## One\n## Two', { extra: EditorState.allowMultipleSelections.of(true) });
    view.dispatch({ selection: EditorSelection.create([EditorSelection.cursor(0), EditorSelection.cursor(8)], 1) });
    expect(view.state.selection.ranges.map((r) => r.head)).toEqual([3, 10]);
  });
});

describe('a change that puts a marker in front of the caret', () => {
  const PLAIN = 'Para one\nTitle\nAfter'; // line 2 starts at H

  // The second models no real peer: @codemirror/collab applies a peer's change with the filters off (see the next describe).
  it.each([['a local change with no selection', []], ['a change annotated remote that goes through the filters', [Transaction.remote.of(true)]]])(
    '%s turns the caret line into a heading: the caret ends after the marker and the change arrives intact',
    (_name, annotations) => {
      const seen = [];
      const view = mount(PLAIN, { extra: EditorView.updateListener.of((u) => seen.push(...u.transactions)) });
      caretAt(view, H);
      seen.length = 0;
      view.dispatch({ changes: { from: H, insert: '## ' }, annotations });

      expect(text(view)).toBe(DOC);
      expect(head(view)).toBe(H + 3);
      expect(seen).toHaveLength(1);
      expect(!!seen[0].annotation(Transaction.remote)).toBe(annotations.length === 1);
      const changes = [];
      seen[0].changes.iterChanges((fromA, toA, _fromB, _toB, inserted) => changes.push([fromA, toA, inserted.toString()]));
      expect(changes).toEqual([[H, H, '## ']]);

      type(view, 'x');
      expect(text(view)).toBe('Para one\n## xTitle\nAfter');
    },
  );

  it('a change elsewhere leaves a caret that is already fine where it maps', () => {
    const view = mount();
    caretAt(view, H + 5);
    view.dispatch({ changes: { from: 0, insert: 'Zz ' } });
    expect(head(view)).toBe(H + 8);
  });

  it('outside live mode a change-only transaction leaves the caret before the marker', () => {
    const view = mount(PLAIN, { live: false });
    caretAt(view, H);
    view.dispatch({ changes: { from: H, insert: '## ' } });
    expect(head(view)).toBe(H);
  });
});

describe('a peer\'s change as @codemirror/collab applies it: no transaction filter runs', () => {
  const PLAIN = 'Para one\nTitle\nAfter'; // line 2 starts at H
  function mountShared(doc, live = true) {
    const seen = [];
    const view = mount(doc, { live, extra: [history(), collab({ startVersion: 0, clientID: 'me' }), EditorView.updateListener.of((u) => seen.push(...u.transactions))] });
    const receive = (change) => view.dispatch(receiveUpdates(view.state, [{ clientID: 'peer', changes: view.state.changes(change) }]));
    return { view, seen, receive };
  }

  it('a marker put in front of the caret: the caret is set after it once the update is over, by a selection alone', async () => {
    const { view, seen, receive } = mountShared(PLAIN);
    caretAt(view, H);
    seen.length = 0;
    receive({ from: H, insert: '## ' });
    expect(head(view)).toBe(H); // as the change mapped it: the caret rule did not see the change
    await Promise.resolve();
    expect(head(view)).toBe(H + 3);
    expect(seen).toHaveLength(2);
    expect(seen[1].docChanged).toBe(false);
    expect(seen[1].annotation(Transaction.addToHistory)).toBe(false);
    expect(sendableUpdates(view.state)).toEqual([]); // nothing of it reaches the peers
    type(view, 'x');
    expect(text(view)).toBe('Para one\n## xTitle\nAfter');
  });

  it('a change that leaves the caret in a resting place, on its line or another, dispatches nothing more', async () => {
    const { view, seen, receive } = mountShared(DOC);
    caretAt(view, H + 5);
    seen.length = 0;
    receive({ from: H + 8, insert: '!' }); // on the caret's line, the caret already fine
    receive({ from: 0, insert: '## ' }); // a marker, on another line
    await Promise.resolve();
    expect(seen).toHaveLength(2);
    expect(head(view)).toBe(H + 8);
  });

  it('a change on another line that makes the caret\'s table a grid: the caret, after a closing pipe, is put in the cell', async () => {
    const ragged = 'top\n\n| h1 | h2 |\n| --- | --- |\n| a1 | a2 | extra |\n| b1 | b2 |\n\nend';
    const { view, receive } = mountShared(ragged);
    const afterPipe = ragged.indexOf('| b1 | b2 |') + '| b1 | b2 |'.length;
    caretAt(view, afterPipe);
    expect(head(view)).toBe(afterPipe); // a resting place while the table is no grid
    const extra = ragged.indexOf(' extra |');
    receive({ from: extra, to: extra + ' extra |'.length });
    await Promise.resolve();
    expect(head(view)).toBe(text(view).indexOf('b2') + 2);
    type(view, 'z');
    expect(text(view)).toBe('top\n\n| h1 | h2 |\n| --- | --- |\n| a1 | a2 |\n| b1 | b2z |\n\nend');
  });

  it('a change on another line that makes the caret\'s line a heading (the fence above it is taken away): the caret goes after the marker', async () => {
    const { view, receive } = mountShared('```\n## Title\nmore');
    caretAt(view, 4);
    expect(head(view)).toBe(4); // inside the fence the line is code
    receive({ from: 0, to: 3 });
    await Promise.resolve();
    expect(text(view)).toBe('\n## Title\nmore');
    expect(head(view)).toBe(4);
    type(view, 'y');
    expect(text(view)).toBe('\n## yTitle\nmore');
  });

  it('the caret left on the fence line its own typing just hid stays there through a change on another line', async () => {
    const { view, seen } = mountShared('```\ncode\n``\nafter');
    caretAt(view, 11);
    type(view, '`');
    expect(head(view)).toBe(12); // stickyCaret's exception: Enter continues below the block
    seen.length = 0;
    // The peer has not seen the typed backtick: its change is against the 17 characters before it.
    view.dispatch(receiveUpdates(view.state, [{ clientID: 'peer', changes: ChangeSet.of({ from: 17, insert: '!' }, 17) }]));
    expect(text(view)).toBe('```\ncode\n```\nafter!');
    await Promise.resolve();
    expect(seen).toHaveLength(1);
    expect(head(view)).toBe(12);
  });

  it('a change on another line that makes the caret\'s line hidden (a table\'s delimiter row): the caret leaves it', async () => {
    const ragged = 'top\n\n| h1 | h2 | h3 |\n| --- | --- |\n| a1 | a2 |\n\nend';
    const { view, receive } = mountShared(ragged);
    const inDelimiter = ragged.indexOf('| --- |') + 4;
    caretAt(view, inDelimiter);
    expect(head(view)).toBe(inDelimiter); // a resting place while the table is no grid
    const extra = ragged.indexOf(' h3 |');
    receive({ from: extra, to: extra + ' h3 |'.length });
    await Promise.resolve();
    type(view, 'z');
    expect(text(view)).toBe('top\n\n| h1 | h2z |\n| --- | --- |\n| a1 | a2 |\n\nend'); // in a cell (the header's last), the delimiter row whole
  });

  it('a change on other lines that makes the caret\'s line a fence\'s hidden opening line: the caret leaves it', async () => {
    const nested = '````\n```\ncode\n```\n````\nafter';
    const { view } = mountShared(nested);
    caretAt(view, 8);
    expect(head(view)).toBe(8); // the inner fence is code text inside the outer one
    view.dispatch(receiveUpdates(view.state, [{ clientID: 'peer', changes: view.state.changes([{ from: 0, to: 4 }, { from: 18, to: 22 }]) }]));
    await Promise.resolve();
    expect(text(view)).toBe('\n```\ncode\n```\n\nafter');
    type(view, 'z');
    expect(text(view)).toBe('\n```\nzcode\n```\n\nafter');
  });

  it('several carets: the one on the fence line its typing hid stays, another the change left before a marker is moved', async () => {
    const doc = '```\ncode\n``\nafter\nTitle';
    const title = doc.indexOf('Title');
    const { view, seen } = mountShared(doc);
    view.dispatch({ effects: StateEffect.appendConfig.of(EditorState.allowMultipleSelections.of(true)) });
    view.dispatch({ selection: EditorSelection.create([EditorSelection.cursor(11), EditorSelection.cursor(title)], 0) });
    view.dispatch({
      changes: { from: 11, insert: '`' },
      selection: EditorSelection.create([EditorSelection.cursor(12), EditorSelection.cursor(title + 1)], 0),
      userEvent: 'input.type',
    });
    expect(view.state.selection.ranges.map((r) => r.head)).toEqual([12, title + 1]);
    seen.length = 0;
    // The peer has not seen the backtick: its change is against the document before it.
    view.dispatch(receiveUpdates(view.state, [{ clientID: 'peer', changes: ChangeSet.of({ from: title, insert: '## ' }, doc.length) }]));
    expect(text(view)).toBe('```\ncode\n```\nafter\n## Title');
    expect(view.state.selection.ranges.map((r) => r.head)).toEqual([12, title + 1]);
    await Promise.resolve();
    expect(view.state.selection.ranges.map((r) => r.head)).toEqual([12, title + 4]);
    expect(seen).toHaveLength(2);
    expect(seen[1].docChanged).toBe(false);
  });

  it('a peer\'s change on the caret\'s own line that hides it (the peer closes the fence): the caret leaves the line', async () => {
    const { view, receive } = mountShared('```\ncode\n``\nafter');
    caretAt(view, 11);
    receive({ from: 11, insert: '`' });
    await Promise.resolve();
    expect(text(view)).toBe('```\ncode\n```\nafter');
    const at = head(view);
    expect(at < 9 || at > 12, `the caret, at ${at}, is off the hidden closing line (9 to 12)`).toBe(true);
  });

  it('redo of the typing that hid a closing fence line leaves the caret on it, as the typing did', async () => {
    const { view, seen } = mountShared('```\ncode\n``\nafter');
    caretAt(view, 11);
    type(view, '`');
    expect(head(view)).toBe(12);
    undo(view);
    await Promise.resolve();
    expect(text(view)).toBe('```\ncode\n``\nafter');
    seen.length = 0;
    redo(view);
    await Promise.resolve();
    expect(text(view)).toBe('```\ncode\n```\nafter');
    expect(head(view)).toBe(12);
    expect(seen).toHaveLength(1);
  });

  it('undo, which the history also applies without the filters, restoring a caret a peer has since put a marker after', async () => {
    const { view, seen } = mountShared(PLAIN);
    caretAt(view, H);
    type(view, 'x');
    // A peer that has not seen the "x" makes the line a heading.
    view.dispatch(receiveUpdates(view.state, [{ clientID: 'peer', changes: ChangeSet.of({ from: H, insert: '## ' }, PLAIN.length) }]));
    await Promise.resolve();
    expect(text(view)).toBe('Para one\n## xTitle\nAfter');
    seen.length = 0;
    undo(view);
    expect(text(view)).toBe(DOC);
    await Promise.resolve();
    expect(head(view)).toBe(H + 3);
    expect(seen).toHaveLength(2);
    expect(seen[1].docChanged).toBe(false);
    redo(view);
    await Promise.resolve();
    expect(text(view)).toBe('Para one\n## xTitle\nAfter');
    expect(head(view)).toBe(H + 3);
    undo(view);
    await Promise.resolve();
    type(view, 'y');
    expect(text(view)).toBe('Para one\n## yTitle\nAfter');
  });

  // Unasked for by the user, this dispatch stands down more broadly than the caret filter does (there the user's own
  // action is the trigger): also between compositionstart and the first composed change, when `composing` is still false.
  it.each(['composing', 'compositionStarted'])('nothing is dispatched while view.%s', async (flag) => {
    const { view, seen, receive } = mountShared(PLAIN);
    caretAt(view, H);
    seen.length = 0;
    receive({ from: H, insert: '## ' });
    Object.defineProperty(view, flag, { configurable: true, get: () => true });
    await Promise.resolve();
    expect(seen).toHaveLength(1);
    expect(head(view)).toBe(H);
    delete view[flag];
  });

  it('in Source the caret stays where the change mapped it', async () => {
    const { view, seen, receive } = mountShared(PLAIN, false);
    caretAt(view, H);
    seen.length = 0;
    receive({ from: H, insert: '## ' });
    await Promise.resolve();
    expect(seen).toHaveLength(1);
    expect(head(view)).toBe(H);
  });

  it('a view destroyed before the update is over is left alone', async () => {
    const { view, receive } = mountShared(PLAIN);
    caretAt(view, H);
    receive({ from: H, insert: '## ' });
    view.destroy();
    await Promise.resolve();
    expect(head(view)).toBe(H);
  });
});

describe('Backspace wins over the Markdown keymap, in CodeEditor\'s extension order', () => {
  // CodeEditor.jsx lists markdown() — whose keymap binds Backspace to deleteMarkupBackward at Prec.high — before the
  // live-preview extension. deleteMarkupBackward dispatches userEvent "delete"; removeLineMarker "delete.backward".
  function mountLikeCodeEditor(doc, live) {
    const seen = [];
    const view = mount(doc, { live, extra: EditorView.updateListener.of((u) => seen.push(...u.transactions)) });
    return { view, seen };
  }

  it('control: outside live mode, Backspace after `> ` is the Markdown keymap\'s', () => {
    const { view, seen } = mountLikeCodeEditor('Para\n\n> Quote', false);
    caretAt(view, 8);
    seen.length = 0;
    expect(press(view, 'Backspace')).toBe(true);
    expect(seen.map((tr) => tr.annotation(Transaction.userEvent))).toEqual(['delete']);
  });

  it('in live mode removeLineMarker runs instead and removes exactly `> `', () => {
    const { view, seen } = mountLikeCodeEditor('Para\n\n> Quote', true);
    caretAt(view, 8);
    seen.length = 0;
    expect(press(view, 'Backspace')).toBe(true);
    expect(seen.map((tr) => tr.annotation(Transaction.userEvent))).toEqual(['delete.backward']);
    expect(text(view)).toBe('Para\n\nQuote');
    expect(head(view)).toBe(6);
  });
});

describe('Backspace after a line marker', () => {
  it.each([['Backspace', {}], ['Shift-Backspace', { shiftKey: true }]])('%s removes exactly the marker and its one trailing space', (_name, mods) => {
    const seen = [];
    const view = mount(DOC, { extra: EditorView.updateListener.of((u) => { seen.push(...u.transactions.filter((tr) => tr.docChanged)); }) });
    caretAt(view, H + 3);
    expect(press(view, 'Backspace', mods)).toBe(true);
    expect(text(view)).toBe('Para one\nTitle\nAfter');
    expect(head(view)).toBe(H);
    // A pin of the outcome only: these cannot tell removeLineMarker from the default delete, which takes the marker
    // whole too (it is atomic) and with the same user event. Without the Shift binding this test passes all the same.
    expect(seen.map((tr) => tr.annotation(Transaction.userEvent))).toEqual(['delete.backward']);
    expect(seen[0].scrollIntoView).toBe(true);
  });
  it.each([['Backspace', {}], ['Shift-Backspace', { shiftKey: true }]])('%s after the innermost of two markers removes that one alone', (_name, mods) => {
    const view = mount('> - item');
    caretAt(view, 4);
    press(view, 'Backspace', mods);
    expect(text(view)).toBe('> item');
  });

  it('Backspace at offset 3 of `## Title` gives `Title`', () => {
    const view = mount('## Title');
    caretAt(view, 3);
    press(view, 'Backspace');
    expect(text(view)).toBe('Title');
  });

  it('declines anywhere else and with a non-empty selection', () => {
    const view = mount();
    caretAt(view, H + 5);
    expect(removeLineMarker(view)).toBe(false);
    view.dispatch({ selection: { anchor: H + 3, head: H + 5 } });
    expect(removeLineMarker(view)).toBe(false);
    expect(text(view)).toBe(DOC);
  });

  it('elsewhere on the line Backspace deletes one character as usual', () => {
    const view = mount();
    caretAt(view, H + 5);
    press(view, 'Backspace');
    expect(text(view)).toBe('Para one\n## Ttle\nAfter');
  });
});

describe('a read-only document', () => {
  it('Backspace after a line marker removes nothing', () => {
    const view = mount(DOC, { extra: EditorState.readOnly.of(true) });
    caretAt(view, H + 3);
    expect(removeLineMarker(view)).toBe(false);
    press(view, 'Backspace');
    expect(text(view)).toBe(DOC);
  });
});

describe('every block marker: the caret rests where the text starts, and Backspace there removes the marker', () => {
  // [line, where its text starts, the line after one Backspace there]
  const CASES = [
    ['## Title', 3, 'Title'],
    ['- item', 2, 'item'],
    ['* item', 2, 'item'],
    ['1. item', 3, 'item'],
    ['10) item', 4, 'item'],
    ['- [ ] task', 6, 'task'],
    ['- [x] task', 6, 'task'],
    ['> quote', 2, 'quote'],
    ['- ', 2, ''],
    ['> > deep', 4, '> deep'],
    ['> - item', 4, '> item'],
    ['> - [ ] x', 8, '> x'],
    ['> ## t', 5, '> t'],
    ['- > x', 4, '- x'],
    ['- ## t', 5, '- t'],
    ['> 1. x', 5, '> x'],
  ];

  it.each(CASES)('%j: a caret anywhere up to offset %i rests there', (line, start) => {
    const view = mount(`Para\n\n${line}\n\nAfter`);
    for (let at = 0; at <= start; at += 1) {
      caretAt(view, 0);
      caretAt(view, 6 + at);
      expect(head(view), `placed at offset ${at}`).toBe(6 + start);
    }
    caretAt(view, 5);
    press(view, 'ArrowRight');
    expect(head(view)).toBe(6 + start);
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(5);
  });

  it.each(CASES)('%j: Backspace at offset %i leaves %j and changes nothing else', (line, start, after) => {
    const view = mount(`Para\n\n${line}\n\nAfter`);
    caretAt(view, 6 + start);
    expect(press(view, 'Backspace')).toBe(true);
    expect(text(view)).toBe(`Para\n\n${after}\n\nAfter`);
    expect(head(view)).toBe(6 + after.length - (line.length - start));
  });

  it('typing at the resting place goes into the text, behind every marker', () => {
    const view = mount('> - [ ] x');
    caretAt(view, 0);
    type(view, 'y');
    expect(text(view)).toBe('> - [ ] yx');
  });

  it('Backspace peels one marker at a time, innermost first', () => {
    const view = mount('> - [ ] x');
    caretAt(view, 8);
    press(view, 'Backspace');
    expect(text(view)).toBe('> x');
    expect(head(view)).toBe(2);
    press(view, 'Backspace');
    expect(text(view)).toBe('x');
    expect(head(view)).toBe(0);
  });

  it('with several spaces after the marker only the first belongs to it', () => {
    const view = mount('-   wide');
    caretAt(view, 0);
    expect(head(view)).toBe(2);
    press(view, 'Backspace');
    expect(text(view)).toBe('  wide');
  });

  it('a continuation line of a quote has its own mark; a lazy line and a list continuation have none', () => {
    const view = mount('> a\n> b\nlazy\n\n- item\n  more');
    caretAt(view, 4);
    expect(head(view)).toBe(6);
    caretAt(view, 8);
    expect(head(view)).toBe(8);
    caretAt(view, 21);
    expect(head(view)).toBe(21);
  });

  it('a clicked checkbox still toggles with the caret on its line', () => {
    const view = mount('- [ ] task');
    caretAt(view, 8);
    const box = view.contentDOM.querySelector('input.cm-lp-task');
    expect(box).not.toBeNull();
    box.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    expect(text(view)).toBe('- [x] task');
    expect(view.contentDOM.querySelector('input.cm-lp-task').checked).toBe(true);
  });
});

describe('nested list items: the marker is sticky, the indentation is plain text', () => {
  const NESTED = '- one\n  - nested\nAfter';
  const L = NESTED.indexOf('  - '); // the nested line starts at 6; its text at L + 4

  it('only the marker and its space are the sticky range', () => {
    const view = mount(NESTED);
    caretAt(view, L + 6);
    expect(rangesOf(stickyRangesOf(view))).toEqual([[0, 2], [L + 2, L + 4]]);
  });

  it.each([0, 1, 2, 3, 4])('a caret placed at offset %i rests where the text starts', (at) => {
    const view = mount(NESTED);
    caretAt(view, L + at);
    expect(head(view)).toBe(L + 4);
  });

  it('ArrowLeft from the start of the text goes to the end of the previous line, and ArrowRight comes back', () => {
    const view = mount(NESTED);
    caretAt(view, L + 4);
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(L - 1);
    press(view, 'ArrowRight');
    expect(head(view)).toBe(L + 4);
  });

  it('Backspace at the start of the text removes the marker and keeps the indentation', () => {
    const view = mount(NESTED);
    caretAt(view, L + 4);
    press(view, 'Backspace');
    expect(text(view)).toBe('- one\n  nested\nAfter');
    expect(head(view)).toBe(L + 2);
  });

  it.each([['Tab', {}], ['Tab', { shiftKey: true }]])('%s %j changes the text exactly as it does in Source mode, the caret staying with the text', (key, init) => {
    const run = (live) => {
      const view = mount(NESTED, { live, extra: keymap.of([indentWithTab]) });
      view.dispatch({ selection: { anchor: L + 4 } });
      press(view, key, init);
      return { doc: text(view), textStart: text(view).indexOf('nested'), caret: head(view) };
    };
    const rich = run(true);
    expect(rich.doc).toBe(run(false).doc);
    expect(rich.doc).not.toBe(NESTED);
    expect(rich.caret).toBe(rich.textStart);
  });
});

describe('Enter keeps continuing and ending lists (the Markdown keymap\'s, untouched)', () => {
  it.each([['- a', '- a\n- '], ['1. a', '1. a\n2. '], ['- [ ] a', '- [ ] a\n- [ ] '], ['> a', '> a\n> ']])('Enter at the end of %j gives %j', (line, after) => {
    const view = mount(line);
    caretAt(view, line.length);
    press(view, 'Enter');
    expect(text(view)).toBe(after);
    expect(head(view)).toBe(after.length);
  });

  // The Markdown keymap first loosens a tight list (a blank line before the empty item), then removes the empty marker.
  it('Enter on the empty item ends the list, step for step as in Source mode', () => {
    const run = (live) => {
      const view = mount('- a\n- ', { live });
      view.dispatch({ selection: { anchor: 6 } });
      const steps = [];
      for (let i = 0; i < 2; i += 1) { press(view, 'Enter'); steps.push(text(view)); }
      return steps;
    };
    expect(run(true)).toEqual(run(false));
    expect(run(true)).toEqual(['- a\n\n- ', '- a\n\n']);
  });
});

describe('a selection that runs into a marker line (Review Focus 3)', () => {
  const TEXT = 'Para one\n## Title\n- item\n> - [ ] x';
  const LINES = { heading: TEXT.indexOf('## '), item: TEXT.indexOf('- item'), stacked: TEXT.indexOf('> - [ ]') };
  const cases = [];
  for (const [name, start] of Object.entries(LINES)) {
    const lineEnd = TEXT.indexOf('\n', start) < 0 ? TEXT.length : TEXT.indexOf('\n', start);
    for (let to = start; to <= lineEnd; to += 1) cases.push([name, to]);
  }

  it.each(cases)('from mid-paragraph into the %s line, up to offset %i: Delete and Backspace remove exactly the selected text', (_name, to) => {
    for (const key of ['Delete', 'Backspace']) {
      for (const [anchor, hd] of [[5, to], [to, 5]]) {
        const view = mount(TEXT);
        view.dispatch({ selection: { anchor, head: hd } });
        expect([view.state.selection.main.from, view.state.selection.main.to]).toEqual([5, to]);
        press(view, key);
        expect(text(view), `${key}, anchor ${anchor}`).toBe(TEXT.slice(0, 5) + TEXT.slice(to));
      }
    }
  });
});

describe('ArrowLeft with a range selection or several cursors is CodeMirror\'s own', () => {
  const selections = {
    'a range selection': () => EditorSelection.single(H + 4, H + 7),
    'a range selection ending right after the marker': () => EditorSelection.single(H + 6, H + 3),
    'two cursors': () => EditorSelection.create([EditorSelection.cursor(3), EditorSelection.cursor(H + 5)], 1),
    'two cursors, one right after the marker': () => EditorSelection.create([EditorSelection.cursor(3), EditorSelection.cursor(H + 3)], 1),
  };
  const cases = Object.keys(selections).flatMap((name) => [[name, 'ArrowLeft', {}], [name, 'ArrowLeft', { ctrlKey: true }]]);

  it.each(cases)('%s, %s %j: no throw', (name, key, init) => {
    const view = mount(DOC, { extra: EditorState.allowMultipleSelections.of(true) });
    view.dispatch({ selection: selections[name]() });
    expect(leaveLineMarker(view)).toBe(false); // the command declines; the key is then CodeMirror's
    expect(() => press(view, key, init)).not.toThrow();
    expect(text(view)).toBe(DOC);
  });

  it.each(cases.filter(([name]) => !name.includes('right after the marker')))('%s, %s %j: the same selection as in Source mode', (name, key, init) => {
    const run = (live) => {
      const view = mount(DOC, { live, extra: EditorState.allowMultipleSelections.of(true) });
      view.dispatch({ selection: selections[name]() });
      press(view, key, init);
      return view.state.selection.ranges.map((r) => [r.anchor, r.head]);
    };
    expect(run(true)).toEqual(run(false));
  });

  it('Backspace with a range selection or two cursors declines the marker command too', () => {
    for (const make of Object.values(selections)) {
      const view = mount(DOC, { extra: EditorState.allowMultipleSelections.of(true) });
      view.dispatch({ selection: make() });
      expect(removeLineMarker(view)).toBe(false);
    }
  });
});

describe('fenced code inside a quote', () => {
  const QUOTED = 'Para\n\n> ```\n> code\n> ```\n\nend';
  const C = QUOTED.indexOf('> code');

  it('pinned as it is: the opening line is its quote mark and the fence header, the closing line is hidden whole; code lines keep their `> ` as source', () => {
    const view = mount(QUOTED);
    caretAt(view, C + 4);
    expect(rangesOf(stickyRangesOf(view))).toEqual([[6, 8], [8, 11], [19, 24]]);
    expect(view.contentDOM.querySelectorAll('.cm-line')[3].textContent).toBe('> code');
    caretAt(view, C);
    expect(head(view)).toBe(C); // nothing sticky on a code line: the caret may rest before its `> `
  });

  it('the opening line cannot hold the caret, so its `> ` is not removable here (Source mode is)', () => {
    const view = mount(QUOTED);
    caretAt(view, 0);
    caretAt(view, 6);
    expect(head(view)).toBe(C); // on to the first code line
    press(view, 'Backspace');
    expect(text(view)).toBe(QUOTED); // Backspace there would join the code line onto the hidden fence
  });
});

describe('live mode off: none of it applies', () => {
  it('Home goes to offset 0, the caret can rest inside the marker, Backspace deletes one character', () => {
    const view = mount('## Title', { live: false });
    caretAt(view, 8);
    press(view, 'Home');
    expect(head(view)).toBe(0);
    caretAt(view, 1);
    expect(head(view)).toBe(1);
    expect(removeLineMarker(view)).toBe(false);
    caretAt(view, 3);
    expect(leaveLineMarker(view)).toBe(false);
    press(view, 'Backspace');
    expect(text(view)).toBe('##Title');
  });

  it('turning live mode off after it was on restores plain behaviour', () => {
    const view = mount('## Title');
    setLivePreview(view, false);
    caretAt(view, 0);
    expect(head(view)).toBe(0);
    expect(rangesOf(stickyRangesOf(view))).toEqual([]);
  });
});

describe('a selection that cuts into a sticky range', () => {
  it.each(['Delete', 'Backspace'])('%s over previous paragraph .. offset 1 of the marker yields the same text as in Source mode', (key) => {
    const run = (live) => {
      const view = mount(DOC, { live });
      view.dispatch({ selection: { anchor: 5, head: H + 1 } });
      press(view, key);
      return text(view);
    };
    expect(run(true)).toBe(run(false));
    expect(run(true)).toBe('Para # Title\nAfter');
  });
});

describe('cost', () => {
  it('caret moves on a long page stay linear in the page at worst (the caret rule reads one line)', () => {
    const make = (n) => {
      const doc = Array.from({ length: n }, (_, i) => (i % 7 === 0 ? `## Heading ${i}` : `Some *text* with [[Link${i}]] and \`code\` ${i}`)).join('\n');
      const view = mount(doc);
      return { view, mid: view.state.doc.line(Math.floor(n / 2)).from };
    };
    const moves = ({ view, mid }) => { for (let i = 0; i < 200; i += 1) caretAt(view, mid + (i % 40)); };
    const { ratio, bound } = linearCost(make, moves, { small: 2000 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
});

describe('composition input is never lost', () => {
  // Any view.dispatch makes CodeMirror drop DOM mutations it has not read yet. At compositionend it has just scheduled
  // the read of the IME's final text, so an extension that dispatches from a composition event loses that text.
  async function compose(view) {
    const seen = [];
    const original = view.dispatch.bind(view);
    view.dispatch = (...specs) => { seen.push(specs); return original(...specs); };
    view.focus();
    view.contentDOM.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
    const duringStart = seen.length;
    // The IME writes its final text into the DOM; compositionend fires before the mutation records are delivered.
    const node = document.createTreeWalker(view.contentDOM.querySelector('.cm-line'), NodeFilter.SHOW_TEXT).nextNode();
    expect(node.nodeValue).toBe('Para one');
    node.nodeValue = 'ParaXY one';
    view.contentDOM.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
    const duringEvents = seen.length;
    await new Promise((r) => { setTimeout(r, 120); });
    return { duringStart, duringEvents };
  }

  it.each([['in live mode', true], ['with live mode off', false]])('text composed %s reaches the document', async (_name, live) => {
    const view = mount('Para one\nTitle', { live });
    caretAt(view, 4);
    const { duringStart, duringEvents } = await compose(view);
    expect(text(view)).toBe('ParaXY one\nTitle');
    expect(duringStart).toBe(0); // nothing dispatches from compositionstart …
    expect(duringEvents).toBe(0); // … nor from compositionend
  });
});
