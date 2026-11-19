/**
 * useTableControls wired as PageEditor wires it — the real CodeEditor, the hook, the controls — and the table
 * commands run through the command registry, as the palette and the controls run them.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- the editor must be gone before the next test's selection events
import { render, act, screen, fireEvent, cleanup } from '@testing-library/react';
import { useCallback, useEffect, useRef } from 'react';
import { EditorView } from '@codemirror/view';
import { undo, undoDepth } from '@codemirror/commands';
import '../test/asyncSelectionChange';
import { parseFully } from '../test/fullParse';
import CodeEditor from '../components/CodeEditor';
import TableControls from '../components/editor/TableControls';
import { getCommand, getCommands, runCommand, subscribe, __resetRegistryForTest } from '../commands/registry';
import { currentTable } from '../utils/livePreview/tableGrid';
import { TABLE_COMMAND_IDS } from '../utils/editorCommands';
import { useTableControls } from './useTableControls';

const TABLE = '| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |';
const DOC = `before\n\n${TABLE}\n\nafter\n`;
const wrap = (table) => `before\n\n${table}\n\nafter\n`;
const seen = { controls: null };

function Host({ doc = DOC, live = true, readOnly = false }) {
  const editorRef = useRef(null);
  const tableControls = useTableControls({ editorRef, readOnly });
  const { onViewChange } = tableControls;
  const handleViewChange = useCallback(() => onViewChange(), [onViewChange]);
  useEffect(() => { seen.controls = tableControls.controls; });
  return (
    <>
      <CodeEditor ref={editorRef} value={doc} onChange={() => {}} livePreview={live} readOnly={readOnly}
        livePreviewContext={{ pageName: 'Here', attachments: [] }} onViewChange={handleViewChange} />
      {tableControls.controls && <TableControls {...tableControls.controls} />}
    </>
  );
}

function mount(props = {}) {
  const utils = render(<Host {...props} />);
  const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
  parseFully(view);
  return { view, ...utils };
}
const docOf = (view) => view.state.doc.toString();
const caretAt = (view, pos) => act(() => { view.dispatch({ selection: { anchor: pos } }); });
/** Puts the caret in the cell that holds `text` (the first place `text` occurs in the document). */
const caretIn = (view, text) => caretAt(view, docOf(view).indexOf(text));
const run = (id) => act(async () => { await runCommand(id); });
const cellOf = (view) => { const cur = currentTable(view.state); return cur && { row: cur.row, col: cur.col }; };
const toolbar = () => screen.queryByRole('toolbar', { name: 'Table' });
const registered = () => getCommands().filter((c) => c.section === 'Table').map((c) => c.id);

afterEach(() => {
  document.getSelection()?.removeAllRanges();
  cleanup();
  __resetRegistryForTest();
  seen.controls = null;
});

describe('where the table commands and controls exist', () => {
  it('only while the caret is in a table the grid edits', () => {
    const { view } = mount();
    caretAt(view, 2);
    expect(registered()).toEqual([]);
    expect(toolbar()).toBe(null);
    caretIn(view, '4');
    expect(registered()).toEqual(TABLE_COMMAND_IDS);
    expect(toolbar()).not.toBe(null);
    caretAt(view, docOf(view).indexOf('after'));
    expect(registered()).toEqual([]);
    expect(getCommand('table-column-right')).toBeUndefined();
    expect(toolbar()).toBe(null);
  });

  it('registers once per table, not once per caret move inside it', () => {
    const { view } = mount();
    caretIn(view, '1');
    const changed = vi.fn();
    const stop = subscribe(changed);
    const before = getCommand('table-row-below');
    caretIn(view, '4');
    caretIn(view, 'a');
    expect(changed).not.toHaveBeenCalled();
    expect(getCommand('table-row-below')).toBe(before);
    stop();
  });

  it('not for a table the grid does not edit (a ragged row)', () => {
    const { view } = mount({ doc: wrap('| a | b |\n| --- | --- |\n| 1 |') });
    caretIn(view, '1');
    expect(registered()).toEqual([]);
    expect(toolbar()).toBe(null);
  });

  it('not in Source mode, and they come and go with the mode while the caret stays in the table', () => {
    const { view, rerender } = mount({ live: false });
    caretIn(view, '4');
    expect(registered()).toEqual([]);
    expect(toolbar()).toBe(null);
    rerender(<Host live />);
    expect(registered()).toEqual(TABLE_COMMAND_IDS);
    expect(toolbar()).not.toBe(null);
    rerender(<Host live={false} />);
    expect(registered()).toEqual([]);
    expect(toolbar()).toBe(null);
  });

  it('not on a read-only document, and a command kept from before writes nothing', async () => {
    const { view, rerender } = mount();
    caretIn(view, '4');
    const kept = seen.controls.onRun;
    rerender(<Host readOnly />);
    expect(registered()).toEqual([]);
    expect(toolbar()).toBe(null);
    act(() => { kept('table-row-below'); });
    expect(docOf(view)).toBe(DOC);
  });

  it('draws the controls inside the editor\'s scroller, so that they scroll with the table', () => {
    const { view } = mount();
    caretIn(view, '4');
    expect(toolbar().parentElement).toBe(view.scrollDOM);
  });
});

describe('table commands', () => {
  it('table-column-right adds the column after the caret\'s, puts the caret in it on the same row, and is one undo step', async () => {
    const { view } = mount();
    caretIn(view, '4');
    expect(cellOf(view)).toEqual({ row: 2, col: 1 });
    await run('table-column-right');
    expect(docOf(view)).toBe(wrap('| a | b |  |\n| --- | --- | --- |\n| 1 | 2 |  |\n| 3 | 4 |  |'));
    expect(cellOf(view)).toEqual({ row: 2, col: 2 });
    expect(view.state.selection.main.empty).toBe(true);
    expect(undoDepth(view.state)).toBe(1);
    act(() => { undo(view); });
    expect(docOf(view)).toBe(DOC);
  });

  it.each([
    ['table-row-above', '3', '| a | b |\n| --- | --- |\n| 1 | 2 |\n|  |  |\n| 3 | 4 |', { row: 2, col: 0 }],
    ['table-row-below', '3', '| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n|  |  |', { row: 3, col: 0 }],
    ['table-row-below', 'b |', '| a | b |\n| --- | --- |\n|  |  |\n| 1 | 2 |\n| 3 | 4 |', { row: 1, col: 1 }],
    ['table-column-left', '2', '| a |  | b |\n| --- | --- | --- |\n| 1 |  | 2 |\n| 3 |  | 4 |', { row: 1, col: 1 }],
    ['table-column-right', 'a', '| a |  | b |\n| --- | --- | --- |\n| 1 |  | 2 |\n| 3 |  | 4 |', { row: 0, col: 1 }],
    ['table-delete-row', '1', '| a | b |\n| --- | --- |\n| 3 | 4 |', { row: 1, col: 0 }],
    ['table-delete-row', '4', '| a | b |\n| --- | --- |\n| 1 | 2 |', { row: 1, col: 1 }],
    ['table-delete-column', '1', '| b |\n| --- |\n| 2 |\n| 4 |', { row: 1, col: 0 }],
    ['table-delete-column', '4', '| a |\n| --- |\n| 1 |\n| 3 |', { row: 2, col: 0 }],
    ['table-move-column-left', '4', '| b | a |\n| --- | --- |\n| 2 | 1 |\n| 4 | 3 |', { row: 2, col: 0 }],
    ['table-move-column-right', '1', '| b | a |\n| --- | --- |\n| 2 | 1 |\n| 4 | 3 |', { row: 1, col: 1 }],
    ['table-align-left', '4', '| a | b |\n| --- | :--- |\n| 1 | 2 |\n| 3 | 4 |', { row: 2, col: 1 }],
    ['table-align-center', '4', '| a | b |\n| --- | :---: |\n| 1 | 2 |\n| 3 | 4 |', { row: 2, col: 1 }],
    ['table-align-right', '3', '| a | b |\n| ---: | --- |\n| 1 | 2 |\n| 3 | 4 |', { row: 2, col: 0 }],
  ])('%s with the caret at "%s": the text, the caret\'s cell, and one undo back to the original', async (id, at, table, cell) => {
    const { view } = mount();
    caretIn(view, at);
    await run(id);
    expect(docOf(view)).toBe(wrap(table));
    expect(cellOf(view)).toEqual(cell);
    expect(undoDepth(view.state)).toBe(1);
    act(() => { undo(view); });
    expect(docOf(view)).toBe(DOC);
  });

  it('table-align-none takes the column\'s alignment away', async () => {
    const aligned = '| a | b |\n| --- | :---: |\n| 1 | 2 |';
    const { view } = mount({ doc: wrap(aligned) });
    caretIn(view, '2');
    expect(seen.controls.align).toBe('center');
    await run('table-align-none');
    expect(docOf(view)).toBe(wrap('| a | b |\n| --- | --- |\n| 1 | 2 |'));
    expect(seen.controls.align).toBe(null);
    act(() => { undo(view); });
    expect(docOf(view)).toBe(wrap(aligned));
  });

  it('a moved column keeps the caret where it was in the cell\'s text', async () => {
    const { view } = mount({ doc: wrap('| a | bcd |\n| --- | --- |\n| 1 | 234 |') });
    caretAt(view, docOf(view).indexOf('34'));
    await run('table-move-column-left');
    expect(docOf(view)).toBe(wrap('| bcd | a |\n| --- | --- |\n| 234 | 1 |'));
    expect(view.state.selection.main.head).toBe(docOf(view).indexOf('34'));
  });

  it.each([
    ['table-delete-row', 'a'],
    ['table-row-above', 'b |'],
    ['table-move-column-left', '3'],
    ['table-move-column-right', '4'],
    ['table-align-none', '1'],
  ])('%s with the caret at "%s" changes nothing, and leaves nothing to undo', async (id, at) => {
    const { view } = mount();
    caretIn(view, at);
    const head = view.state.selection.main.head;
    await run(id);
    expect(docOf(view)).toBe(DOC);
    expect(view.state.selection.main.head).toBe(head);
    expect(undoDepth(view.state)).toBe(0);
  });

  it('table-delete-column never removes the last column', async () => {
    const one = wrap('| a |\n| --- |\n| 1 |');
    const { view } = mount({ doc: one });
    caretIn(view, '1');
    expect(seen.controls.canDeleteColumn).toBe(false);
    await run('table-delete-column');
    expect(docOf(view)).toBe(one);
  });

  it('leaves the rows it does not touch exactly as written', async () => {
    const messy = '|a|  bb  | c\\|c |\n|:-|:---:|  ---: |\n| 1 || `x|y` |  \n|   |2| 3 |';
    const { view } = mount({ doc: wrap(messy) });
    caretIn(view, '2');
    await run('table-row-above');
    expect(docOf(view)).toBe(wrap('|a|  bb  | c\\|c |\n|:-|:---:|  ---: |\n| 1 || `x|y` |  \n|  |  |  |\n|   |2| 3 |'));
    expect(cellOf(view)).toEqual({ row: 2, col: 1 });
    act(() => { undo(view); });
    expect(docOf(view)).toBe(wrap(messy));
  });
});

describe('the controls', () => {
  it('say what the caret\'s cell allows', () => {
    const { view } = mount();
    caretIn(view, 'a');
    expect(seen.controls).toMatchObject({ canInsertRowAbove: false, canDeleteRow: false, canDeleteColumn: true, canMoveLeft: false, canMoveRight: true, align: null });
    caretIn(view, '4');
    expect(seen.controls).toMatchObject({ canInsertRowAbove: true, canDeleteRow: true, canDeleteColumn: true, canMoveLeft: true, canMoveRight: false, align: null });
  });

  it('keep their props while the caret moves inside one cell', () => {
    const { view } = mount({ doc: wrap('| abc | b |\n| --- | --- |\n| 1 | 2 |') });
    caretIn(view, 'abc');
    const before = seen.controls;
    caretIn(view, 'bc');
    expect(seen.controls).toBe(before);
  });

  it('"+ row" adds a row under the caret\'s and returns to the editor, the caret in the new row', () => {
    const { view } = mount();
    caretIn(view, '2');
    fireEvent.click(screen.getByRole('button', { name: 'Insert row below' }));
    expect(docOf(view)).toBe(wrap('| a | b |\n| --- | --- |\n| 1 | 2 |\n|  |  |\n| 3 | 4 |'));
    expect(cellOf(view)).toEqual({ row: 2, col: 1 });
    expect(view.hasFocus).toBe(true);
  });

  it('Alt-T in the table goes to the controls, Escape back to the editor', () => {
    const { view } = mount();
    caretIn(view, '2');
    act(() => { view.focus(); });
    expect(fireEvent.keyDown(view.contentDOM, { key: 't', code: 'KeyT', altKey: true })).toBe(false);
    const first = screen.getByRole('button', { name: 'Insert row below' });
    expect(first).toHaveFocus();
    fireEvent.keyDown(first, { key: 'Escape' });
    expect(view.hasFocus).toBe(true);
    expect(docOf(view)).toBe(DOC);
  });

  it('Alt-T outside a table is left alone', () => {
    const { view } = mount();
    caretAt(view, 2);
    act(() => { view.focus(); });
    expect(fireEvent.keyDown(view.contentDOM, { key: 't', code: 'KeyT', altKey: true })).toBe(true);
  });
});
