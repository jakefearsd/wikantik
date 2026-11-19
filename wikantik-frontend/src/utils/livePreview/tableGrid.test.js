/**
 * The table grid on a real EditorView: an editable top-level table's rows stay editor lines, drawn as cells. Typing in
 * a cell is ordinary typing in the one editor; these pin the cell input rules, the caret's confinement to cells, the
 * cell keys, and which tables the grid owns at all.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { Annotation, EditorState, EditorSelection, Transaction } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap, indentWithTab, history, undo, redo, deleteLine, insertBlankLine } from '@codemirror/commands';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview, asDispatched } from './index';
import { livePreviewPlugin } from './plugin';
import { currentTable, tableGridPlugin } from './tableGrid';
import { onOpenLinkCard, cardModelOf } from './linkCard';
import { readLinkAt, linkChange } from '../linkEdit';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from '../../test/linearCost';

const context = { pageName: 'Grid', attachments: [] };
const TABLE = ['| A | B |', '| --- | --- |', '| one | two |', '| three |  |'].join('\n');
const DOC = `Intro\n\n${TABLE}\n\nAfter`;
const at = (needle, doc = DOC) => { const i = doc.indexOf(needle); if (i < 0) throw new Error(`no ${needle}`); return i; };
const EMPTY = at('|  |') + 2; // the typing position of the empty cell: between its two spaces

const views = [];
afterEach(() => { while (views.length) { const v = views.pop(); v.destroy(); v.dom.parentNode?.remove(); } });

function mount(doc = DOC, { live = true, extra = [] } = {}) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const seen = [];
  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc,
      extensions: [markdown(editorMarkdownConfig), history(), livePreview({ getContext: () => context }),
        keymap.of([indentWithTab, ...defaultKeymap]), EditorView.updateListener.of((u) => { seen.push(...u.transactions); }), extra],
    }),
  });
  views.push(view);
  ensureSyntaxTree(view.state, view.state.doc.length, 60000);
  if (live) setLivePreview(view, true);
  view.seen = seen;
  return view;
}

function press(view, key, init = {}) {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
  view.contentDOM.dispatchEvent(event);
  return event.defaultPrevented;
}
const caretAt = (view, pos) => view.dispatch({ selection: { anchor: pos }, userEvent: 'select' });
const head = (view) => view.state.selection.main.head;
const text = (view) => view.state.doc.toString();
const type = (view, ch) => {
  const { from, to } = view.state.selection.main;
  view.dispatch({ changes: { from, to, insert: ch }, selection: { anchor: from + ch.length }, userEvent: 'input.type' });
};
const paste = (view, pasted) => view.dispatch(view.state.update(view.state.replaceSelection(pasted), { userEvent: 'input.paste' }));
/** Every change of the transactions seen since `mark`, as `[from, to, inserted]` against each one's start document. */
const changesSince = (view, mark) => {
  const out = [];
  for (const tr of view.seen.slice(mark)) tr.changes.iterChanges((from, to, _fb, _tb, ins) => out.push([from, to, ins.toString()]));
  return out;
};
/** The cell (`{ row, col }`) the caret is in, by the grid's own account. */
const cell = (view) => { const c = currentTable(view.state); return c ? { row: c.row, col: c.col } : null; };

describe('typing in a cell is typing in the document', () => {
  it('a typed character grows only that cell, as one change of length 1', () => {
    const view = mount();
    caretAt(view, at('one') + 3);
    const mark = view.seen.length;
    type(view, 'x');
    expect(text(view)).toBe(DOC.replace('| one |', '| onex |'));
    expect(changesSince(view, mark)).toEqual([[at('one') + 3, at('one') + 3, 'x']]);
    expect(head(view)).toBe(at('one') + 4);
  });

  it('a typed pipe is stored escaped, and the caret ends after it', () => {
    const view = mount();
    caretAt(view, at('one') + 3);
    type(view, '|');
    expect(text(view)).toBe(DOC.replace('| one |', '| one\\| |'));
    expect(head(view)).toBe(at('one') + 5);
    type(view, 'z');
    expect(text(view)).toBe(DOC.replace('| one |', '| one\\|z |'));
  });

  it('a pipe typed after a backslash is the pipe that backslash already escapes', () => {
    const view = mount();
    caretAt(view, at('one') + 3);
    type(view, '\\');
    type(view, '|');
    expect(text(view)).toBe(DOC.replace('| one |', '| one\\| |'));
  });

  it('a backslash typed against a closing pipe is stored as an escaped backslash, so the pipe still closes the cell', () => {
    const doc = DOC.replace('| one |', '| one|');
    const view = mount(doc);
    caretAt(view, at('one', doc) + 3);
    type(view, '\\');
    expect(text(view)).toBe(doc.replace('| one|', '| one\\\\|'));
    expect(currentTable(view.state)).not.toBeNull();
  });

  it('the backslash stored that way goes as one character: Backspace after it, Delete before it or inside it', () => {
    const doc = DOC.replace('| one |', '| one\\\\|');
    const end = at('one', doc) + 5;
    for (const [key, caret] of [['Backspace', end], ['Delete', end - 2], ['Delete', end - 1], ['Backspace', end - 1]]) {
      const view = mount(doc);
      caretAt(view, caret);
      press(view, key);
      expect(text(view), `${key} at ${caret - end}`).toBe(doc.replace('| one\\\\|', '| one|'));
      expect(currentTable(view.state)?.table.columns).toBe(2);
    }
  });

  it('a backslash typed just before an escaped pipe keeps that pipe escaped (stored as an escaped backslash)', () => {
    const doc = DOC.replace('one', 'a\\|b');
    const view = mount(doc);
    caretAt(view, at('a\\|b', doc) + 1);
    type(view, '\\');
    expect(text(view)).toBe(doc.replace('a\\|b', 'a\\\\\\|b')); // a, an escaped backslash, the escaped pipe, b
    expect(currentTable(view.state)?.table.columns).toBe(2);
  });

  it('Delete before an escaped pipe removes the pipe with its backslash; so does one Backspace after it', () => {
    const doc = DOC.replace('one', 'a\\|b');
    const start = at('a\\|b', doc);
    const del = mount(doc);
    caretAt(del, start + 1);
    press(del, 'Delete');
    expect(text(del)).toBe(doc.replace('a\\|b', 'ab'));
    const back = mount(doc);
    caretAt(back, start + 3);
    press(back, 'Backspace');
    expect(text(back)).toBe(doc.replace('a\\|b', 'ab'));
    expect(head(back)).toBe(start + 1);
  });

  it('pasted multi-line text becomes one line with its pipes escaped (Review Focus 1)', () => {
    const view = mount();
    caretAt(view, EMPTY);
    paste(view, 'a\nb|c');
    expect(text(view)).toBe(DOC.replace('| three |  |', '| three | a b\\|c |'));
    expect(head(view)).toBe(EMPTY + 'a b\\|c'.length);
    expect(currentTable(view.state)?.table.editable).toBe(true);
  });

  it('typing in an empty cell lands between its spaces; a space typed at a cell end stays in the cell', () => {
    const view = mount();
    caretAt(view, EMPTY);
    type(view, 'q');
    type(view, ' ');
    type(view, 'r');
    expect(text(view)).toBe(DOC.replace('| three |  |', '| three | q r |'));
    expect(cell(view)).toEqual({ row: 2, col: 1 });
  });

  it('one undo takes back a rewritten change, and redo brings it back', () => {
    const view = mount();
    caretAt(view, at('one') + 3);
    type(view, '|');
    undo(view);
    expect(text(view)).toBe(DOC);
    redo(view);
    expect(text(view)).toBe(DOC.replace('| one |', '| one\\| |'));
  });

  it('with live mode off nothing is rewritten', () => {
    const view = mount(DOC, { live: false });
    caretAt(view, at('one') + 3);
    type(view, '|');
    expect(text(view)).toBe(DOC.replace('| one |', '| one| |'));
  });
});

// A cell's text starts after the padding that follows its pipe, and that padding is hidden. A space typed at the
// start of a cell that has text would be stored in front of it and shown nowhere, the caret not moving: each press
// one more invisible character, each sent to every collaborator.
describe('a space typed at the start of a cell that has text', () => {
  it.each([['a space', ' '], ['a tab', '\t'], ['pasted blanks', '   ']])('%s changes nothing: no document change, and the caret stays', (_name, blank) => {
    const view = mount();
    caretAt(view, at('one'));
    const mark = view.seen.length;
    for (let i = 0; i < 3; i += 1) {
      const h = head(view);
      view.dispatch({ changes: { from: h, insert: blank }, selection: { anchor: h + blank.length }, userEvent: blank.length > 1 ? 'input.paste' : 'input.type' });
    }
    expect(text(view)).toBe(DOC);
    expect(head(view)).toBe(at('one'));
    expect(view.seen.slice(mark).filter((tr) => tr.docChanged)).toEqual([]);
  });
  it('a space inside the text, at its end, and before text typed with it are kept', () => {
    let view = mount();
    caretAt(view, at('one') + 1);
    type(view, ' ');
    expect(text(view)).toBe(DOC.replace('| one |', '| o ne |'));
    view = mount();
    caretAt(view, at('two') + 3);
    type(view, ' ');
    expect(text(view)).toBe(DOC.replace('| two |', '| two  |'));
    view = mount();
    caretAt(view, at('three'));
    paste(view, ' x');
    expect(text(view)).toBe(DOC.replace('| three |', '|  xthree |'));
  });
  it('in an empty cell a space is typed as anywhere else', () => {
    const view = mount();
    caretAt(view, EMPTY);
    type(view, ' ');
    expect(text(view)).toBe(DOC.replace('|  |', '|   |'));
  });
  it('a selection at the cell\'s start replaced by a space is replaced', () => {
    const view = mount();
    view.dispatch({ selection: EditorSelection.range(at('one'), at('one') + 2), userEvent: 'select' });
    type(view, ' ');
    expect(text(view)).toBe(DOC.replace('| one |', '|  e |'));
  });
});

describe('what the input rules leave alone', () => {
  // Not how a real peer's change arrives: @codemirror/collab applies those with the filters off altogether.
  it('a change annotated remote that goes through the filters, inserting a raw pipe, is not escaped', () => {
    const view = mount();
    caretAt(view, at('two'));
    const mark = view.seen.length;
    view.dispatch({ changes: { from: at('one') + 3, insert: '|' }, annotations: Transaction.remote.of(true), userEvent: 'input.type' });
    expect(text(view)).toBe(DOC.replace('| one |', '| one| |'));
    expect(changesSince(view, mark)).toEqual([[at('one') + 3, at('one') + 3, '|']]);
  });

  it('undo and redo of a raw pipe pass through', () => {
    const view = mount();
    caretAt(view, at('two'));
    view.dispatch({ changes: { from: at('one') + 3, insert: '|' } });
    const mark = view.seen.length;
    undo(view);
    expect(text(view)).toBe(DOC);
    redo(view);
    expect(text(view)).toBe(DOC.replace('| one |', '| one| |'));
    expect(changesSince(view, mark)).toEqual([[at('one') + 3, at('one') + 4, ''], [at('one') + 3, at('one') + 3, '|']]);
  });

  it('a replace across a pipe passes through exactly as dispatched', () => {
    const view = mount();
    const match = at(' | two');
    view.dispatch({ selection: EditorSelection.range(match, match + 3), userEvent: 'select.search' });
    const mark = view.seen.length;
    view.dispatch({ changes: { from: match, to: match + 3, insert: ', ' }, userEvent: 'input.replace' });
    expect(text(view)).toBe(DOC.replace('one | two', 'one, two'));
    expect(changesSince(view, mark)).toEqual([[match, match + 3, ', ']]);
  });

  it('a replace-all with several changes passes through exactly as dispatched', () => {
    const view = mount();
    caretAt(view, at('one') + 1);
    const a = at(' | two');
    const b = at(' |  |');
    const mark = view.seen.length;
    view.dispatch({ changes: [{ from: a, to: a + 3, insert: ', ' }, { from: b, to: b + 3, insert: ', ' }], userEvent: 'input.replace.all' });
    expect(changesSince(view, mark)).toEqual([[a, a + 3, ', '], [b, b + 3, ', ']]);
  });

  it('a replace wholly inside one cell passes through exactly as dispatched', () => {
    const view = mount();
    view.dispatch({ selection: EditorSelection.range(at('one'), at('one') + 3), userEvent: 'select.search' });
    view.dispatch({ changes: { from: at('one'), to: at('one') + 3, insert: 'a|b' }, userEvent: 'input.replace' });
    expect(text(view)).toBe(DOC.replace('| one |', '| a|b |'));
  });

  it('an input change that did not come from a selection range passes through exactly as dispatched', () => {
    const view = mount();
    caretAt(view, at('A'));
    const mark = view.seen.length;
    view.dispatch({ changes: { from: at('one') + 3, to: at('two'), insert: 'x|y' }, userEvent: 'input.link' });
    expect(changesSince(view, mark)).toEqual([[at('one') + 3, at('two'), 'x|y']]);
  });

  it.each([['with the caret elsewhere', false], ['with the caret on the link', true]])('a link-card commit of a text holding a pipe keeps the row\'s cells, %s, and is not escaped twice', (_name, onLink) => {
    const doc = DOC.replace('one', '[t](u)');
    const view = mount(doc);
    const link = at('[t](u)', doc);
    caretAt(view, onLink ? link + 6 : 0);
    const change = linkChange(readLinkAt(view.state, link + 1), { text: 'a|b' });
    view.dispatch({ changes: change, userEvent: 'input.link', annotations: asDispatched.of(true) }); // as CodeEditor.applyChanges dispatches it
    expect(text(view)).toBe(doc.replace('[t](u)', '[a\\|b](u)'));
    caretAt(view, at('two', doc) + 4);
    expect(currentTable(view.state)?.table.columns).toBe(2);
    expect(readLinkAt(view.state, link + 1).text).toBe('a|b');
  });

  it.each([['with the caret elsewhere', false], ['with the caret on the text', true]])('a wrap-card commit (a link made of a selection) with a pipe in its address keeps the row\'s cells, %s', (_name, onText) => {
    const view = mount();
    const from = at('one');
    caretAt(view, onText ? from + 3 : 0);
    const model = cardModelOf(view.state, { wrap: true, from, to: from + 3, source: 'one' });
    view.dispatch({ changes: linkChange(model, { url: 'a|b' }), userEvent: 'input.link-card', annotations: asDispatched.of(true) });
    expect(text(view)).toBe(DOC.replace('| one |', '| [one](a\\|b) |'));
    caretAt(view, at('A'));
    expect(currentTable(view.state)?.table.columns).toBe(2);
    expect(readLinkAt(view.state, from + 1).url).toBe('a|b');
  });

  // A programmatic write says so (asDispatched): the selection touching it does not make it typing. Here the
  // selection is anchored two cells away and ends at the changed text, which the cell rules would clip to the anchor's cell.
  describe('an exact write marked asDispatched, with a selection that touches it', () => {
    const doc = DOC.replace('|  |', '| [x](http://u) |');
    const link = at('[x](http://u)', doc);
    const linkEnd = link + '[x](http://u)'.length;
    it.each([
      ['input.link-card', '[x](http://other)'],
      ['delete.link-card', 'x'],
      ['input', 'a|b\nc'],
    ])('passes through exactly as dispatched (%s)', (userEvent, insert) => {
      const view = mount(doc);
      view.dispatch({ selection: EditorSelection.range(at('one', doc), link), userEvent: 'select' });
      const mark = view.seen.length;
      view.dispatch({ changes: { from: link, to: linkEnd, insert }, userEvent, annotations: asDispatched.of(true) });
      expect(changesSince(view, mark)).toEqual([[link, linkEnd, insert]]);
      expect(text(view)).toBe(doc.slice(0, link) + insert + doc.slice(linkEnd));
    });
    it('the same change without the mark is input at the selection, and is clipped to the anchor\'s cell', () => {
      const view = mount(doc);
      view.dispatch({ selection: EditorSelection.range(at('one', doc), link), userEvent: 'select' });
      view.dispatch({ changes: { from: link, to: linkEnd, insert: '[x](http://other)' }, userEvent: 'input.type' });
      expect(text(view)).not.toBe(doc.replace('http://u', 'http://other'));
      expect(text(view)).toContain('[x](http://u)');
    });
    it('typing never carries the mark: a typed pipe is still escaped, and the rewritten transaction is not marked', () => {
      const view = mount();
      caretAt(view, at('one') + 3);
      const mark = view.seen.length;
      type(view, '|');
      expect(text(view)).toBe(DOC.replace('| one |', '| one\\| |'));
      expect(view.seen.slice(mark).some((tr) => tr.annotation(asDispatched))).toBe(false);
    });
  });

  it('every annotation of a rewritten transaction survives the rewrite', () => {
    const tagged = Annotation.define();
    const view = mount();
    caretAt(view, at('one') + 3);
    const mark = view.seen.length;
    view.dispatch({ changes: { from: at('one') + 3, insert: '|' }, selection: { anchor: at('one') + 4 }, userEvent: 'input.type', annotations: [tagged.of('kept'), Transaction.addToHistory.of(false)] });
    expect(text(view)).toBe(DOC.replace('| one |', '| one\\| |'));
    const tr = view.seen.slice(mark).find((t) => t.docChanged);
    expect(tr.annotation(tagged)).toBe('kept');
    expect(tr.annotation(Transaction.addToHistory)).toBe(false);
    expect(tr.annotation(Transaction.userEvent)).toBe('input.type');
  });

  it('a change with no user event (a command, a table operation) is not rewritten', () => {
    const view = mount();
    view.dispatch({ changes: { from: at('one'), to: at('two') + 3, insert: 'x\ny' } });
    expect(text(view)).toBe(DOC.replace('one | two', 'x\ny'));
  });

  it('a pipe typed through a composition is stored as typed, not escaped (the table then goes back to the widget); the caret is not moved under it (Review Focus 4)', () => {
    const view = mount();
    caretAt(view, at('one') + 3);
    view.dispatch({ changes: { from: at('one') + 3, insert: '|' }, selection: { anchor: at('one') + 4 }, userEvent: 'input.type.compose' });
    expect(text(view)).toBe(DOC.replace('| one |', '| one| |'));
    expect(head(view)).toBe(at('one') + 4);
    expect(currentTable(view.state)).toBeNull(); // ragged now: no longer the grid's
    caretAt(view, 0);
    expect(view.contentDOM.querySelectorAll('.cm-lp-table-block').length).toBe(1);
  });

  it('text composed in a cell lands intact, and nothing dispatches from the composition events', async () => {
    const view = mount();
    caretAt(view, at('one') + 2);
    const dispatched = [];
    const original = view.dispatch.bind(view);
    view.dispatch = (...specs) => { dispatched.push(specs); return original(...specs); };
    view.focus();
    view.contentDOM.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
    const row = [...view.contentDOM.querySelectorAll('.cm-lp-table-row')].find((el) => el.textContent.includes('one'));
    const walker = document.createTreeWalker(row, NodeFilter.SHOW_TEXT);
    let node = walker.nextNode();
    while (node && node.nodeValue !== 'one') node = walker.nextNode();
    expect(node?.nodeValue).toBe('one');
    node.nodeValue = 'onXYe';
    view.contentDOM.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
    const during = dispatched.length;
    await new Promise((r) => { setTimeout(r, 120); });
    expect(text(view)).toBe(DOC.replace('| one |', '| onXYe |'));
    expect(during).toBe(0);
  });

  it('a selection from the paragraph above into a cell, then Delete, is the Source result (Review Focus 3)', () => {
    const from = 2; // inside `Intro`
    const to = at('one') + 2;
    const source = DOC.slice(0, from) + DOC.slice(to);
    for (const [name, act] of [['Delete', (v) => press(v, 'Delete')], ['typing', (v) => type(v, 'k')]]) {
      const view = mount();
      view.dispatch({ selection: EditorSelection.range(from, to), userEvent: 'select' });
      act(view);
      expect(text(view), name).toBe(name === 'Delete' ? source : `${DOC.slice(0, from)}k${DOC.slice(to)}`);
    }
  });

  it('a selection from a cell out past the table end is not clipped either', () => {
    const view = mount();
    view.dispatch({ selection: EditorSelection.range(at('one') + 1, at('After') + 2), userEvent: 'select' });
    press(view, 'Backspace');
    expect(text(view)).toBe(DOC.slice(0, at('one') + 1) + DOC.slice(at('After') + 2));
  });
});

describe('a deletion never eats a pipe', () => {
  it('Backspace at the start of a cell does nothing', () => {
    const view = mount();
    caretAt(view, at('two'));
    const mark = view.seen.length;
    press(view, 'Backspace');
    expect(text(view)).toBe(DOC);
    expect(changesSince(view, mark)).toEqual([]);
    expect(head(view)).toBe(at('two'));
  });

  it('Delete at the end of a cell does nothing', () => {
    const view = mount();
    caretAt(view, at('one') + 3);
    press(view, 'Delete');
    expect(text(view)).toBe(DOC);
    expect(head(view)).toBe(at('one') + 3);
  });

  it('Backspace and Delete in an empty cell do nothing', () => {
    const view = mount();
    caretAt(view, EMPTY);
    press(view, 'Backspace');
    press(view, 'Delete');
    expect(text(view)).toBe(DOC);
    expect(head(view)).toBe(EMPTY);
    expect(cell(view)).toEqual({ row: 2, col: 1 });
  });

  it('deleting by word or to the line start stops at the cell', () => {
    const view = mount();
    caretAt(view, at('two') + 3);
    press(view, 'Backspace', { ctrlKey: true });
    press(view, 'Backspace', { ctrlKey: true });
    expect(text(view)).toBe(DOC.replace('| two |', '|  |'));
    expect(currentTable(view.state)?.table.columns).toBe(2);
  });

  it('a selection across two cells of a row is clipped to the cell its anchor is in', () => {
    const view = mount();
    view.dispatch({ selection: EditorSelection.range(at('one') + 1, at('two') + 2), userEvent: 'select' });
    type(view, 'X');
    expect(text(view)).toBe(DOC.replace('| one | two |', '| oX | two |'));
    expect(head(view)).toBe(at('one') + 2);
    const back = mount();
    back.dispatch({ selection: EditorSelection.range(at('two') + 2, at('one') + 1), userEvent: 'select' });
    press(back, 'Delete');
    expect(text(back)).toBe(DOC.replace('| one | two |', '| one | o |'));
  });

  describe('a selection across cells inside one table', () => {
    const cases = [
      ['two cells of one row', () => [at('one') + 1, at('two') + 2], '| o | two |', '| oX | two |', '| one | two |'],
      ['two rows', () => [at('one') + 1, at('three') + 2], '| o | two |', '| oX | two |', '| one | two |'],
    ];
    const select = (view, [anchor, head]) => view.dispatch({ selection: EditorSelection.range(anchor, head), userEvent: 'select' });
    it.each(cases)('%s: Delete, Backspace and typing change only the anchor\'s cell', (_name, ends, deleted, typed) => {
      for (const key of ['Delete', 'Backspace']) {
        const view = mount();
        select(view, ends());
        press(view, key);
        expect(text(view), key).toBe(DOC.replace('| one | two |', deleted));
      }
      const view = mount();
      select(view, ends());
      type(view, 'X');
      expect(text(view)).toBe(DOC.replace('| one | two |', typed));
    });
    it.each(cases)('%s: a cut removes nothing — the clipboard has the whole selection, and only part of it could go', (_name, ends) => {
      const view = mount();
      const [anchor, head] = ends();
      select(view, [anchor, head]);
      const mark = view.seen.length;
      view.dispatch({ changes: { from: anchor, to: head }, userEvent: 'delete.cut' });
      expect(text(view)).toBe(DOC);
      expect(changesSince(view, mark)).toEqual([]);
      expect([view.state.selection.main.from, view.state.selection.main.to]).toEqual([anchor, head]);
    });
    it('a cut whose selection only reaches the next cell\'s start cuts the cell\'s text: what is clipped away is a hidden pipe and its padding', () => {
      for (const [anchor, head] of [[at('one'), at('two')], [at('two'), at('one')]]) {
        const view = mount();
        select(view, [anchor, head]);
        view.dispatch({ changes: { from: at('one'), to: at('two') }, userEvent: 'delete.cut' });
        expect(text(view), `${anchor}>${head}`).toBe(DOC.replace('| one |', '|  |'));
        const del = mount();
        select(del, [anchor, head]);
        press(del, 'Delete');
        expect(text(del), `Delete ${anchor}>${head}`).toBe(DOC.replace('| one |', '|  |'));
      }
      const view = mount();
      select(view, [at('one') + 1, at('two')]);
      view.dispatch({ changes: { from: at('one') + 1, to: at('two') }, userEvent: 'delete.cut' });
      expect(text(view)).toBe(DOC.replace('| one |', '| o |'));
    });
    it('an anchor at its cell\'s edge with only the next cell\'s text selected acts on that cell, forwards as backwards', () => {
      for (const [anchor, head, left] of [[at('one') + 3, at('two') + 2, '| one | o |'], [at('two'), at('one') + 1, '| o | two |']]) {
        const view = mount();
        select(view, [anchor, head]);
        press(view, 'Delete');
        expect(text(view), `${anchor}>${head}`).toBe(DOC.replace('| one | two |', left));
      }
    });
    it('an anchor at its cell\'s edge with a selection running into another row removes nothing', () => {
      for (const key of ['Delete', 'Backspace']) {
        const view = mount();
        select(view, [at('one') + 3, at('three') + 2]); // all of `two` and the start of `three` are selected
        press(view, key);
        expect(text(view), key).toBe(DOC);
      }
      const far = mount(DOC.replace('| A | B |', '| A | B | C |').replace('| --- | --- |', '| --- | --- | --- |').replace('| one | two |', '| one | two | x |').replace('| three |  |', '| three |  | y |'));
      const doc = text(far);
      select(far, [doc.indexOf('one') + 3, doc.indexOf('| x') + 3]); // over the whole of `two` into the cell after it
      press(far, 'Delete');
      expect(text(far)).toBe(doc);
    });
    it('a cut inside one cell cuts', () => {
      const view = mount();
      select(view, [at('one'), at('one') + 2]);
      view.dispatch({ changes: { from: at('one'), to: at('one') + 2 }, userEvent: 'delete.cut' });
      expect(text(view)).toBe(DOC.replace('| one |', '| e |'));
    });
    it('the selection itself is not clipped', () => {
      const view = mount();
      select(view, [at('one') + 1, at('three') + 2]);
      expect([view.state.selection.main.from, view.state.selection.main.to]).toEqual([at('one') + 1, at('three') + 2]);
    });
  });

  it('deleteLine in the header row deletes the row, as in Source: its range starts on the line above the table', () => {
    const view = mount();
    caretAt(view, at('B'));
    deleteLine(view);
    expect(text(view)).toBe(DOC.replace('| A | B |\n', ''));
  });

  it('deleteLine in a body cell clears the cell, not the row', () => {
    const view = mount();
    caretAt(view, at('two') + 1);
    deleteLine(view); // its range is the caret's line: a deletion at the selection, clipped to the caret's cell like any other
    expect(text(view)).toBe(DOC.replace('| two |', '|  |'));
    caretAt(view, at('one'));
    expect(currentTable(view.state)?.table.body).toHaveLength(2);
  });

  it('Mod-Enter (a blank line after the row) splits the table, as it does in Source', () => {
    const view = mount();
    caretAt(view, at('one') + 1);
    insertBlankLine(view); // its change is at the line's end, not at the selection: it goes as dispatched
    expect(text(view)).toBe(DOC.replace('| one | two |\n', '| one | two |\n\n'));
  });

  it('Backspace right after a chip in a cell selects the chip first, then deletes it', () => {
    const doc = DOC.replace('two', '[[Page]]');
    const view = mount(doc);
    const end = at('[[Page]]', doc) + 8;
    caretAt(view, end);
    press(view, 'Backspace');
    expect(text(view)).toBe(doc);
    expect([view.state.selection.main.from, view.state.selection.main.to]).toEqual([end - 8, end]);
    press(view, 'Backspace');
    expect(text(view)).toBe(doc.replace('[[Page]]', ''));
  });
});

describe('the caret stays in cells', () => {
  it('a caret placed on a row outside its cells goes to the nearest cell', () => {
    const view = mount();
    const row = view.state.doc.lineAt(at('one'));
    caretAt(view, row.from);
    expect(head(view)).toBe(at('one'));
    caretAt(view, row.to);
    expect(head(view)).toBe(at('two') + 3);
    caretAt(view, at('two') - 1); // between the pipe and the padding before `two`
    expect(head(view)).toBe(at('two'));
  });

  it('a caret in a cell\'s trailing padding stays there, drawn as cell text', () => {
    const view = mount();
    caretAt(view, at('one') + 4); // after the space that follows `one`, as if that space had just been typed
    expect(head(view)).toBe(at('one') + 4);
    expect(cell(view)).toEqual({ row: 1, col: 0 });
    const row = [...view.contentDOM.querySelectorAll('.cm-lp-table-row')][1];
    expect([...row.querySelectorAll('.cm-lp-cell')].map((c) => c.textContent)).toEqual(['one ', 'two']);
    caretAt(view, at('two') + 1);
    expect([...view.contentDOM.querySelectorAll('.cm-lp-table-row')][1].textContent).toBe('onetwo');
  });

  it('a cursor at a cell start never leans back nor at a cell end forward, so it is drawn in its own cell', () => {
    const view = mount();
    const cursor = (pos, assoc) => { view.dispatch({ selection: EditorSelection.create([EditorSelection.cursor(pos, assoc)]), userEvent: 'select' }); return view.state.selection.main.assoc; };
    expect(cursor(at('two'), -1)).toBe(1); // as ArrowRight across the pipe leaves it
    expect(cursor(at('one') + 3, 1)).toBe(-1); // as ArrowLeft back across it does
    expect(cursor(EMPTY, -1)).toBe(1);
    expect(cursor(at('one') + 1, -1)).toBe(-1); // inside a cell's text it is nobody's business
    const row = view.state.doc.lineAt(at('one'));
    expect(cursor(row.from, 0)).toBe(1); // moved into the first cell
    expect(cursor(row.to, 0)).toBe(-1); // moved back into the last
  });

  it('pressing ArrowRight at a cell end lands in the next cell leaning into it', () => {
    const view = mount();
    caretAt(view, at('one') + 3);
    press(view, 'ArrowRight');
    expect(head(view)).toBe(at('two'));
    expect(view.state.selection.main.assoc).toBe(1);
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(at('one') + 3);
    expect(view.state.selection.main.assoc).toBe(-1);
  });

  it('the caret never rests on the delimiter row', () => {
    const view = mount();
    const delimiter = view.state.doc.lineAt(at('| ---'));
    caretAt(view, at('A'));
    caretAt(view, delimiter.from + 3);
    expect(view.state.doc.lineAt(head(view)).number).not.toBe(delimiter.number);
    expect(cell(view)).not.toBeNull();
  });

  it('End and ArrowRight from the last cell stay in a cell', () => {
    const view = mount();
    caretAt(view, at('two') + 1);
    press(view, 'End');
    expect(cell(view)).toEqual({ row: 1, col: 1 });
    caretAt(view, at('two') + 3);
    press(view, 'ArrowRight');
    expect(cell(view)).toEqual({ row: 2, col: 0 });
    expect(head(view)).toBe(at('three'));
  });

  it('ArrowLeft from a row\'s first cell goes to the row above, and from the header out of the table', () => {
    const view = mount();
    caretAt(view, at('three'));
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(at('two') + 3);
    caretAt(view, at('A'));
    press(view, 'ArrowLeft');
    expect(head(view)).toBe(at('\n| A'));
  });

  it('ArrowRight from the last cell of the last row leaves the table', () => {
    const view = mount();
    caretAt(view, EMPTY);
    press(view, 'ArrowRight');
    expect(view.state.doc.lineAt(head(view)).number).toBe(view.state.doc.lineAt(at('| three')).number + 1);
  });
});

describe('cell keys', () => {
  it('Tab goes to the start of the next cell, wrapping to the next row and over the delimiter row', () => {
    const view = mount();
    caretAt(view, at('A'));
    expect(press(view, 'Tab')).toBe(true);
    expect(head(view)).toBe(at('B'));
    press(view, 'Tab');
    expect(head(view)).toBe(at('one'));
    press(view, 'Tab');
    press(view, 'Tab');
    expect(head(view)).toBe(at('three'));
    expect(text(view)).toBe(DOC);
  });

  it('Shift-Tab goes to the previous cell, and stays in the first', () => {
    const view = mount();
    caretAt(view, at('one') + 1);
    press(view, 'Tab', { shiftKey: true });
    expect(head(view)).toBe(at('B'));
    press(view, 'Tab', { shiftKey: true });
    press(view, 'Tab', { shiftKey: true });
    expect(head(view)).toBe(at('A'));
    expect(text(view)).toBe(DOC);
  });

  it('Tab from the last cell adds a row with the table\'s cell count and goes to its first cell', () => {
    const view = mount();
    caretAt(view, EMPTY);
    const mark = view.seen.length;
    press(view, 'Tab');
    expect(text(view)).toBe(DOC.replace('| three |  |', '| three |  |\n|  |  |'));
    expect(cell(view)).toEqual({ row: 3, col: 0 });
    expect(head(view)).toBe(at('| three |  |') + '| three |  |\n| '.length);
    expect(view.seen.slice(mark).filter((tr) => tr.docChanged)).toHaveLength(1);
    undo(view);
    expect(text(view)).toBe(DOC);
  });

  it('Enter moves down a row keeping the column, and adds a row from the last one', () => {
    const view = mount();
    caretAt(view, at('B'));
    expect(press(view, 'Enter')).toBe(true);
    expect(head(view)).toBe(at('two'));
    press(view, 'Enter');
    expect(cell(view)).toEqual({ row: 2, col: 1 });
    expect(text(view)).toBe(DOC);
    press(view, 'Enter');
    expect(text(view)).toBe(DOC.replace('| three |  |', '| three |  |\n|  |  |'));
    expect(cell(view)).toEqual({ row: 3, col: 1 });
  });

  it('ArrowDown and ArrowUp keep the column and step over the delimiter row', () => {
    const view = mount();
    caretAt(view, at('B'));
    press(view, 'ArrowDown');
    expect(cell(view)).toEqual({ row: 1, col: 1 });
    press(view, 'ArrowDown');
    expect(cell(view)).toEqual({ row: 2, col: 1 });
    press(view, 'ArrowUp');
    press(view, 'ArrowUp');
    expect(cell(view)).toEqual({ row: 0, col: 1 });
    expect(text(view)).toBe(DOC);
  });

  it('on a read-only document the keys still move, and none of them writes', () => {
    const view = mount(DOC, { extra: EditorState.readOnly.of(true) });
    caretAt(view, at('three'));
    press(view, 'Tab');
    expect(head(view)).toBe(EMPTY);
    press(view, 'Tab');
    press(view, 'Enter');
    expect(text(view)).toBe(DOC);
  });

  it('outside a table Tab and Enter are the editor\'s own', () => {
    const view = mount();
    caretAt(view, 5);
    press(view, 'Enter');
    expect(text(view)).toBe(DOC.replace('Intro', 'Intro\n'));
  });

  it('a deliberate keypress edit, not a caret move: ArrowDown from the last row of a table that ends the document adds the blank lines to leave it', () => {
    const view = mount(`${TABLE}`);
    caretAt(view, TABLE.indexOf('three'));
    const mark = view.seen.length;
    press(view, 'ArrowDown');
    expect(text(view)).toBe(`${TABLE}\n\n`);
    expect(head(view)).toBe(TABLE.length + 2);
    expect(currentTable(view.state)).toBeNull();
    const edits = view.seen.slice(mark).filter((tr) => tr.docChanged);
    expect(edits).toHaveLength(1);
    expect(edits[0].isUserEvent('input')).toBe(true);
    undo(view);
    expect(text(view)).toBe(TABLE);
  });

  it('a deliberate keypress edit, not a caret move: ArrowUp from the header row of a table that starts the document adds the blank lines to leave it', () => {
    const view = mount(`${TABLE}\n\nAfter`);
    caretAt(view, TABLE.indexOf('B'));
    const mark = view.seen.length;
    press(view, 'ArrowUp');
    expect(text(view)).toBe(`\n\n${TABLE}\n\nAfter`);
    expect(head(view)).toBe(0);
    const edits = view.seen.slice(mark).filter((tr) => tr.docChanged);
    expect(edits).toHaveLength(1);
    expect(edits[0].isUserEvent('input')).toBe(true);
  });

  it('the edge arrows write nothing with Shift held, nor on a read-only document, nor away from the document\'s edge', () => {
    const shifted = mount(`${TABLE}`);
    caretAt(shifted, TABLE.indexOf('three'));
    press(shifted, 'ArrowDown', { shiftKey: true });
    press(shifted, 'ArrowUp', { shiftKey: true });
    expect(text(shifted)).toBe(TABLE);
    const locked = mount(`${TABLE}`, { extra: EditorState.readOnly.of(true) });
    caretAt(locked, TABLE.indexOf('three'));
    press(locked, 'ArrowDown');
    caretAt(locked, TABLE.indexOf('A'));
    press(locked, 'ArrowUp');
    expect(text(locked)).toBe(TABLE);
    const inside = mount();
    caretAt(inside, at('three'));
    press(inside, 'ArrowDown');
    caretAt(inside, at('A'));
    press(inside, 'ArrowUp');
    expect(text(inside)).toBe(DOC);
  });

  it('the row a key adds is one transaction with a user event', () => {
    const view = mount();
    caretAt(view, EMPTY);
    const mark = view.seen.length;
    press(view, 'Tab');
    const edits = view.seen.slice(mark).filter((tr) => tr.docChanged);
    expect(edits).toHaveLength(1);
    expect(edits[0].isUserEvent('input.table-row')).toBe(true);
  });
});

describe('which tables the grid draws', () => {
  const rows = (view) => view.contentDOM.querySelectorAll('.cm-lp-table-row').length;
  const blocks = (view) => view.contentDOM.querySelectorAll('.cm-lp-table-block').length;

  it('a clean table is rows of cells, not the table widget; its delimiter row is a collapsed line', () => {
    const view = mount();
    expect(blocks(view)).toBe(0);
    expect(rows(view)).toBe(3);
    expect(view.contentDOM.querySelectorAll('.cm-lp-table-header').length).toBe(1);
    expect(view.contentDOM.querySelector('.cm-lp-table-delim')?.textContent).toBe('');
    const body = [...view.contentDOM.querySelectorAll('.cm-lp-table-row')][1];
    expect([...body.querySelectorAll('.cm-lp-cell')].map((c) => c.textContent)).toEqual(['one', 'two']);
    expect(body.textContent).toBe('onetwo'); // nothing of the row is drawn outside a cell
    const last = [...view.contentDOM.querySelectorAll('.cm-lp-table-row')][2];
    expect(last.querySelectorAll('.cm-lp-cell-empty').length).toBe(1);
  });

  it('inline markup in a cell is drawn as in a paragraph', () => {
    const doc = DOC.replace('two', '**b**').replace('three', '[[Page]]');
    const view = mount(doc);
    caretAt(view, 0);
    expect(view.contentDOM.querySelector('.cm-lp-cell .cm-lp-strong')?.textContent).toBe('b');
    expect(view.contentDOM.querySelector('.cm-lp-cell .cm-lp-linkchip')?.textContent).toBe('Page');
  });

  it('a ragged table is drawn by the table widget, with no grid rows', () => {
    const view = mount(DOC.replace('| one | two |', '| one |'));
    caretAt(view, 0);
    expect(blocks(view)).toBe(1);
    expect(rows(view)).toBe(0);
    expect(currentTable(view.state)).toBeNull();
  });

  it('a table in a list or a quote is not a grid', () => {
    const view = mount(`> ${TABLE.split('\n').join('\n> ')}\n\nAfter`);
    expect(rows(view)).toBe(0);
  });

  it('an edit that makes a table ragged hands it to the widget on that update, and a repair hands it back', () => {
    const view = mount();
    caretAt(view, 0);
    const pipe = at('| two |') ;
    view.dispatch({ changes: { from: pipe, to: pipe + 1, insert: '/' } }); // `| one / two |`: one cell short
    expect(rows(view)).toBe(0);
    expect(blocks(view)).toBe(1);
    view.dispatch({ changes: { from: pipe, to: pipe + 1, insert: '|' } });
    expect(blocks(view)).toBe(0);
    expect(rows(view)).toBe(3);
    expect(text(view)).toBe(DOC);
  });

  describe('the mouse in a cell', () => {
    // happy-dom fires `selectionchange` synchronously while CodeMirror is mid-update (browsers deliver it later), which
    // makes the observer re-enter the view: hold those events back, as the round-trip sweep does.
    const holdSelectionChange = (e) => e.stopImmediatePropagation();
    beforeEach(() => window.addEventListener('selectionchange', holdSelectionChange, true));
    afterEach(() => window.removeEventListener('selectionchange', holdSelectionChange, true));
    const cellEl = (view, label) => [...view.contentDOM.querySelectorAll('.cm-lp-cell')].find((el) => el.textContent === label);
    const mouse = (el, type, init = {}) => el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, buttons: 1, ...init }));
    const selected = (view) => [view.state.selection.main.anchor, view.state.selection.main.head];
    /** A view whose hit test answers `hit()`: what a real layout would for the pointer's place. */
    const hitting = (hit, doc = DOC) => { const view = mount(doc); caretAt(view, 0); view.posAtCoords = () => hit(); return view; };

    it('a click never lands the caret in another cell: right of the text it goes to the text end, left of it to its start', () => {
      let hit = at('two'); // right of `one` in a wide column, the hit test answers the far side of the hidden pipe
      const view = hitting(() => hit);
      mouse(cellEl(view, 'one'), 'mousedown');
      mouse(cellEl(view, 'one'), 'mouseup', { buttons: 0 });
      expect(selected(view)).toEqual([at('one') + 3, at('one') + 3]);
      hit = at('one') - 2;
      mouse(cellEl(view, 'one'), 'mousedown');
      mouse(cellEl(view, 'one'), 'mouseup', { buttons: 0 });
      expect(selected(view)).toEqual([at('one'), at('one')]);
      expect(text(view)).toBe(DOC);
    });

    it('a press in a cell\'s blank area starts a selection there, and a drag across the text selects it without leaving the cell', () => {
      let hit = at('two');
      const view = hitting(() => hit);
      mouse(cellEl(view, 'one'), 'mousedown');
      expect(selected(view)).toEqual([at('one') + 3, at('one') + 3]);
      mouse(cellEl(view, 'one'), 'mousemove'); // still right of the text: the hit is the next cell, the selection is not
      expect(selected(view)).toEqual([at('one') + 3, at('one') + 3]);
      hit = at('one') + 1;
      mouse(cellEl(view, 'one'), 'mousemove');
      expect(selected(view)).toEqual([at('one') + 3, at('one') + 1]);
      hit = at('one') - 2; // into the left padding
      mouse(cellEl(view, 'one'), 'mousemove');
      expect(selected(view)).toEqual([at('one') + 3, at('one')]);
      mouse(cellEl(view, 'one'), 'mouseup', { buttons: 0 });
      expect(selected(view)).toEqual([at('one') + 3, at('one')]);
    });

    it('a drag over a cell\'s text released right of it ends at the text end; dragged on into another cell it goes there', () => {
      let hit = at('one');
      const view = hitting(() => hit);
      mouse(cellEl(view, 'one'), 'mousedown');
      hit = at('two');
      mouse(cellEl(view, 'one'), 'mousemove');
      expect(selected(view)).toEqual([at('one'), at('one') + 3]);
      hit = at('two') + 2;
      mouse(cellEl(view, 'two'), 'mousemove');
      expect(selected(view)).toEqual([at('one'), at('two') + 2]);
      mouse(cellEl(view, 'two'), 'mouseup', { buttons: 0 });
    });

    it('a Shift-click, a double click and a modifier click are the editor\'s own', () => {
      const view = hitting(() => at('two'));
      caretAt(view, at('A'));
      for (const init of [{ shiftKey: true }, { detail: 2 }, { ctrlKey: true }, { altKey: true }]) {
        mouse(cellEl(view, 'one'), 'mousedown', init);
        mouse(cellEl(view, 'one'), 'mouseup', { buttons: 0 });
        expect(view.state.selection.main.head, JSON.stringify(init)).not.toBe(at('one') + 3);
        caretAt(view, at('A'));
      }
    });

    it('a click on a chip in a cell opens its card: the cell rule stands aside', () => {
      const doc = DOC.replace('one', '[[Page]]');
      const opened = [];
      const view = mount(doc, { extra: onOpenLinkCard.of((card) => { if (card) opened.push(card); }) });
      caretAt(view, 0);
      view.posAtCoords = () => at('two', doc);
      const chip = view.contentDOM.querySelector('.cm-lp-cell .cm-lp-linkchip');
      expect(mouse(chip, 'mousedown')).toBe(false); // taken by the chip's handler
      expect(opened).toHaveLength(1);
      expect(head(view)).toBe(0);
    });

    it('clicking an empty cell puts the caret in it', () => {
      const view = hitting(() => at('After'));
      const box = view.contentDOM.querySelector('.cm-lp-cell-empty');
      mouse(box, 'mousedown');
      mouse(box, 'mouseup', { buttons: 0 });
      expect(head(view)).toBe(EMPTY);
      expect(text(view)).toBe(DOC);
    });
  });

  it('with live mode off there is no grid', () => {
    const view = mount(DOC, { live: false });
    expect(rows(view)).toBe(0);
    expect(currentTable(view.state)).toBeNull();
  });
});

describe('currentTable', () => {
  it('is the table and cell holding the main caret, and null outside a cell', () => {
    const view = mount();
    caretAt(view, at('two') + 1);
    const cur = currentTable(view.state);
    expect(cur.row).toBe(1);
    expect(cur.col).toBe(1);
    expect(cur.table.columns).toBe(2);
    expect(cur.table.body).toHaveLength(2);
    expect(currentTable(view.state)).toBe(cur); // memoised per (document, caret)
    caretAt(view, 2);
    expect(currentTable(view.state)).toBeNull();
  });
});

describe('cost', () => {
  const tableOf = (n) => ['| A | B | C |', '| --- | --- | --- |', ...Array.from({ length: n }, (_, i) => `| r${i} | some *text* ${i} | [[Page${i}]] |`)].join('\n');

  it('only the rows in the viewport are decorated, and a keystroke in a cell keeps it so', () => {
    const view = mount(tableOf(500));
    const count = () => { let n = 0; for (const it = view.plugin(tableGridPlugin).decorations.iter(); it.value; it.next()) n += 1; return n; };
    const drawn = view.contentDOM.querySelectorAll('.cm-lp-table-row').length;
    expect(drawn).toBeGreaterThan(0);
    expect(drawn).toBeLessThan(200);
    expect(count()).toBeLessThanOrEqual(drawn * 4);
    caretAt(view, view.state.doc.line(4).from + 2);
    type(view, 'x');
    expect(view.contentDOM.querySelectorAll('.cm-lp-table-row').length).toBeLessThan(200);
    expect(count()).toBeLessThanOrEqual(view.contentDOM.querySelectorAll('.cm-lp-table-row').length * 4);
    let inline = 0;
    for (const it = view.plugin(livePreviewPlugin).decorations.iter(); it.value; it.next()) inline += 1;
    expect(inline).toBeLessThan(200 * 12);
  });

  it('a keystroke in one table does not cost more for the other tables of the document', () => {
    const make = (n) => {
      const view = mount(`${tableOf(5)}\n\n${Array.from({ length: n / 10 }, () => tableOf(8)).join('\n\ntext\n\n')}`);
      return view;
    };
    const keys = (view) => {
      caretAt(view, view.state.doc.line(3).from + 2);
      for (let i = 0; i < 100; i += 1) type(view, 'a');
      for (let i = 0; i < 100; i += 1) caretAt(view, view.state.doc.line(3 + (i % 3)).from + 2);
    };
    const { ratio, bound } = linearCost(make, keys, { small: 1000 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
});
