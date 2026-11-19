/**
 * The table grid on a real EditorView: an editable top-level table's rows stay editor lines, drawn as cells. Typing in
 * a cell is ordinary typing in the one editor; these pin the cell input rules, the caret's confinement to cells, the
 * cell keys, and which tables the grid owns at all.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { EditorState, EditorSelection, Transaction } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap, indentWithTab, history, undo, redo } from '@codemirror/commands';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';
import { livePreviewPlugin } from './plugin';
import { currentTable, fitColumns, tableGridPlugin } from './tableGrid';
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

  it('a backslash typed against a closing pipe does not swallow the pipe', () => {
    const doc = DOC.replace('| one |', '| one|');
    const view = mount(doc);
    caretAt(view, at('one', doc) + 3);
    type(view, '\\');
    expect(text(view)).toBe(doc.replace('| one|', '| one\\ |'));
    expect(currentTable(view.state)).not.toBeNull();
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

describe('what the input rules leave alone', () => {
  it('a remote change inserting a raw pipe is not escaped', () => {
    const view = mount();
    caretAt(view, at('two'));
    view.dispatch({ changes: { from: at('one') + 3, insert: '|' }, annotations: Transaction.remote.of(true), userEvent: 'input.type' });
    expect(text(view)).toBe(DOC.replace('| one |', '| one| |'));
  });

  it('a change with no user event (a command, a table operation) is not rewritten', () => {
    const view = mount();
    view.dispatch({ changes: { from: at('one'), to: at('two') + 3, insert: 'x\ny' } });
    expect(text(view)).toBe(DOC.replace('one | two', 'x\ny'));
  });

  it('a composition transaction is not rewritten, nor is the caret moved under it (Review Focus 4)', () => {
    const view = mount();
    caretAt(view, at('one') + 3);
    view.dispatch({ changes: { from: at('one') + 3, insert: '|' }, selection: { anchor: at('one') + 4 }, userEvent: 'input.type.compose' });
    expect(text(view)).toBe(DOC.replace('| one |', '| one| |'));
    expect(head(view)).toBe(at('one') + 4);
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

  it('a table that ends the document can be left: ArrowDown from its last row adds a line below it', () => {
    const view = mount(`${TABLE}`);
    caretAt(view, TABLE.indexOf('three'));
    press(view, 'ArrowDown');
    expect(text(view)).toBe(`${TABLE}\n\n`);
    expect(head(view)).toBe(TABLE.length + 2);
    expect(currentTable(view.state)).toBeNull();
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

  it('clicking an empty cell puts the caret in it', () => {
    const view = mount();
    caretAt(view, 0);
    const box = view.contentDOM.querySelector('.cm-lp-cell-empty');
    box.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    expect(head(view)).toBe(EMPTY);
    expect(text(view)).toBe(DOC);
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

describe('column widths', () => {
  it('columns that fit keep their natural width, at least the minimum', () => {
    expect(fitColumns([100, 20, 300], 600, 64, 192)).toEqual([100, 64, 300]);
  });

  it('a table too wide is squeezed: columns under the fair share keep theirs, the rest share what is left', () => {
    expect(fitColumns([100, 900, 500], 700, 64, 192)).toEqual([100, 300, 300]);
  });

  it('a table that cannot fit even at the minimum is not squeezed: it scrolls, with columns capped at the wide limit', () => {
    expect(fitColumns([100, 900, 30, 250], 200, 64, 192)).toEqual([100, 192, 64, 192]);
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
