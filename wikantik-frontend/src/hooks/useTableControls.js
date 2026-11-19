import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { registerCommands } from '../commands/registry';
import { buildTableCommands } from '../utils/editorCommands';
import { tableAbilities, tableCommandEdit } from '../utils/tableCommands';
import { tableAt } from '../utils/tableModel';
import { currentTable } from '../utils/livePreview/tableGrid';

/**
 * The user event of a table command's transaction. Deliberately not an `input…`, `delete…` or `move.drop` event:
 * those are the ones the grid's input rules (tableGrid.js, gridInput) take for typing in a cell.
 */
const TABLE_EDIT = 'table';
const rowsOf = (table) => [table.header, ...table.body];
const sameState = (a, b) => a === b || (!!a && !!b && Object.keys(b).every((key) => a[key] === b[key]));
const isAltT = (e) => e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey && (e.code === 'KeyT' || e.key === 't' || e.key === 'T');

/**
 * Where the controls go, in the coordinates of the editor's scroller (their offset parent): over the blank line
 * above the table, at the table's left edge; when that line is not blank (or the table starts the document), under
 * the table instead — never over a row of the table. Null when the caret is not in a grid table.
 * Reads layout: not to be called during an editor update.
 */
function measure(view) {
  const cur = currentTable(view.state);
  if (!cur) return null;
  const { table } = cur;
  const { doc } = view.state;
  const first = doc.lineAt(table.from);
  const above = first.number > 1 && doc.line(first.number - 1).text.trim() === '';
  const block = view.lineBlockAt(above ? table.from : table.to);
  const box = view.scrollDOM.getBoundingClientRect();
  // The first header cell's box when it is drawn (the header may be scrolled out of the drawn range), else the text column.
  const line = view.domAtPos(table.header.cells[0].from).node;
  const row = (line.nodeType === 1 ? line : line.parentElement)?.closest?.('.cm-lp-table-row');
  const edge = (row?.querySelector('.cm-lp-cell') ?? view.contentDOM).getBoundingClientRect();
  return {
    above,
    top: view.documentTop + (above ? block.top : block.bottom) - box.top + view.scrollDOM.scrollTop,
    left: edge.left - box.left + view.scrollDOM.scrollLeft,
  };
}

function place(bar, at) {
  if (!bar || !at) return;
  bar.style.top = `${Math.round(at.top)}px`;
  bar.style.left = `${Math.round(at.left)}px`;
  bar.classList.toggle('table-controls-above', at.above);
}

/**
 * The row and column controls of the page editor's table grid (Rich mode), and the table commands behind them.
 *
 * - `onViewChange()` goes where the editor reports a caret move, an edit, a scroll or a mode change: it keeps track
 *   of whether the caret is in a table the grid edits (`currentTable`, which is memoised: a caret move costs one
 *   lookup) and of what that cell allows. The state changes, and the host re-renders, only when that answer does.
 * - `controls` are TableControls' props while the caret is in such a table and the document can be edited, else
 *   null. The controls are drawn into the editor's scroller, so they scroll with the table.
 * - While `controls` are there, and only then, the table commands are in the command registry (and so in the
 *   palette). They are registered once per stay in a table: each reads the caret's table and cell when it runs.
 * - Alt-T in the editor, with the caret in such a table, moves the focus to the controls; Escape there returns it.
 *
 * A command is one `applyChanges` call (one transaction, its own undo step) with the changes tableModel computes,
 * followed by a caret move to the cell the command names (no document change, so still one undo step). It writes
 * nothing on a read-only document, outside Rich mode, or where the command does not apply.
 */
export function useTableControls({ editorRef, readOnly = false }) {
  const [at, setAt] = useState(null);
  const barRef = useRef(null);

  const viewOf = useCallback(() => {
    const view = editorRef.current?.getView?.();
    return view && typeof view.state?.field === 'function' ? view : null; // a stub view (tests) has no fields
  }, [editorRef]);

  const requestPlace = useCallback((view) => {
    view.requestMeasure({ key: barRef, read: measure, write: (measured) => place(barRef.current, measured) });
  }, []);

  const onViewChange = useCallback(() => {
    const view = viewOf();
    const cur = view ? currentTable(view.state) : null;
    const next = cur ? { host: view.scrollDOM, ...tableAbilities(cur) } : null;
    setAt((prev) => (sameState(prev, next) ? prev : next));
    if (cur) requestPlace(view); // the table may have moved (an edit above it), and a move to another table is no state change
  }, [viewOf, requestPlace]);

  const run = useCallback((id) => {
    const editor = editorRef.current;
    const view = viewOf();
    if (!editor || !view) return;
    const cur = view.state.readOnly ? null : currentTable(view.state);
    const edit = tableCommandEdit(cur, id);
    if (!edit) { editor.focus(); return; }
    const { table, row, col } = cur;
    const cell = rowsOf(table)[row].cells[col];
    const into = Math.max(0, Math.min(view.state.selection.main.head, cell.to) - cell.from);
    editor.applyChanges(edit.changes, { userEvent: TABLE_EDIT });
    // No operation changes anything before the table's first pipe, so the table is still at `table.from`.
    const changed = edit.row === undefined ? null : tableAt(view.state, table.from);
    const target = changed?.editable ? rowsOf(changed)[edit.row]?.cells[edit.col] : null;
    if (target) {
      const pos = edit.keepOffset ? Math.min(target.from + into, target.to) : target.from;
      editor.setSelection(pos, pos);
    } else {
      editor.focus(); // the caret stays where the change left it
    }
  }, [editorRef, viewOf]);

  const leave = useCallback(() => editorRef.current?.focus?.(), [editorRef]);

  const shown = at !== null && !readOnly;
  useEffect(() => (shown ? registerCommands(buildTableCommands({ runTable: run })) : undefined), [shown, run]);

  // Placed before the first paint, so the controls never show at the scroller's corner first.
  useLayoutEffect(() => {
    const view = shown ? viewOf() : null;
    if (!view) return;
    try {
      place(barRef.current, measure(view));
    } catch (err) {
      console.warn('[rich] could not place the table controls yet; they are placed with the next measure', err?.message || err);
      requestPlace(view);
    }
  }, [shown, at, viewOf, requestPlace]);

  useEffect(() => {
    if (!shown) return undefined;
    const onKeyDown = (e) => {
      if (e.defaultPrevented || e.repeat || e.isComposing || !isAltT(e)) return;
      const view = viewOf();
      const first = barRef.current?.querySelector('button:not(:disabled)');
      if (!view?.hasFocus || !first) return;
      e.preventDefault(); // also keeps the character Alt-T types on some keyboards out of the cell
      first.focus();
    };
    const onResize = () => { const view = viewOf(); if (view) requestPlace(view); };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('resize', onResize);
    };
  }, [shown, viewOf, requestPlace]);

  const controls = useMemo(() => (shown ? { ...at, barRef, onRun: run, onLeave: leave } : null), [shown, at, run, leave]);
  return { onViewChange, controls };
}
