/**
 * What each table command of Rich mode does to the table the caret is in: the text changes (tableModel.js's
 * operations, nothing added to them) and the cell the caret belongs in afterwards.
 *
 * `cur` is tableGrid.js's `currentTable(state)`: `{ table, row, col }`, row 0 the header.
 */
import { insertRow, deleteRow, insertColumn, deleteColumn, moveColumn, setAlign } from './tableModel';

const align = (how) => ({ table, col }) => ({ changes: setAlign(table, col, how) });

/** By command id: `cur` → `{ changes, row?, col?, keepOffset? }`. Without a row and column the caret stays put. */
const EDITS = {
  'table-row-above': ({ table, row, col }) => ({ changes: insertRow(table, row), row, col }),
  'table-row-below': ({ table, row, col }) => ({ changes: insertRow(table, row + 1), row: row + 1, col }),
  'table-column-left': ({ table, row, col }) => ({ changes: insertColumn(table, col), row, col }),
  'table-column-right': ({ table, row, col }) => ({ changes: insertColumn(table, col + 1), row, col: col + 1 }),
  // The neighbouring cell: the row (column) that takes the deleted one's place, or the one before the last.
  'table-delete-row': ({ table, row, col }) => ({ changes: deleteRow(table, row), row: row < table.body.length ? row : row - 1, col }),
  'table-delete-column': ({ table, row, col }) => ({ changes: deleteColumn(table, col), row, col: col < table.columns - 1 ? col : col - 1 }),
  // The caret travels with its cell, at the same place in the cell's text.
  'table-move-column-left': ({ table, row, col }) => ({ changes: moveColumn(table, col, col - 1), row, col: col - 1, keepOffset: true }),
  'table-move-column-right': ({ table, row, col }) => ({ changes: moveColumn(table, col, col + 1), row, col: col + 1, keepOffset: true }),
  'table-align-left': align('left'),
  'table-align-center': align('center'),
  'table-align-right': align('right'),
  'table-align-none': align(null),
};

/**
 * The edit command `id` makes with the caret at `cur`: `{ changes, row, col, keepOffset }` — non-overlapping
 * `{ from, to, insert }` changes, and the cell (row 0 the header) of the changed table where the caret goes, at the
 * start of its text or, with `keepOffset`, as far into it as the caret was. `row` is undefined when the caret stays
 * where the change leaves it. Null when the command does not apply there (the header row is never deleted nor has a
 * row above it, the last column stays, a column at the edge does not move past it) or is not a table command.
 */
export function tableCommandEdit(cur, id) {
  const edit = cur && Object.hasOwn(EDITS, id) ? EDITS[id](cur) : null;
  return edit && edit.changes.length > 0 ? edit : null;
}

/** What the caret's cell allows, for the controls to offer: which commands apply, and the column's alignment. */
export function tableAbilities({ table, row, col }) {
  return {
    canInsertRowAbove: row > 0,
    canDeleteRow: row > 0,
    canDeleteColumn: table.columns > 1,
    canMoveLeft: col > 0,
    canMoveRight: col < table.columns - 1,
    align: table.aligns[col] ?? null,
  };
}
