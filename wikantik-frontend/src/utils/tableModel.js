/**
 * A GFM table read out of the editor's syntax tree, and the row and column operations of the Rich table grid.
 *
 * Every operation returns the text changes that make the edit and nothing else: no row is re-padded or re-aligned,
 * and a moved cell travels with the padding its author gave it. In a shared session a change that rewrote a row
 * nobody edited would turn one person's edit into everyone's conflict.
 *
 * Positions are document offsets. The model is a snapshot of one state: read it again after any change.
 */
import { syntaxTree } from '@codemirror/language';

const PIPE = '|';
/** What a new column adds after a pipe: an empty cell with room for the caret, and its delimiter cell. */
const NEW_CELL = '  |';
const NEW_DELIMITER_CELL = ' --- |';
const ALIGNS = ['left', 'center', 'right', null];

/**
 * The cells of one row. `pipes` are the offsets of its cell pipes and `contents` the `{from, to}` text of each
 * non-blank cell, both ascending. A cell is the stretch between two pipes; the stretch before the first pipe or after
 * the last is a cell only when it holds text (a row without its outer pipes), which is how the parser counts them.
 * `framed` says the row has both outer pipes, so that every cell lies between two of them.
 *
 * A cell is `{from, to, outerFrom, outerTo}`: `from..to` is its text without the padding (for an empty cell, the
 * zero-width place where typing goes), `outerFrom..outerTo` everything between its pipes.
 */
function cellsOf(from, to, pipes, contents) {
  const cells = [];
  let next = 0;
  for (let i = 0; i <= pipes.length; i += 1) {
    const outerFrom = i === 0 ? from : pipes[i - 1] + 1;
    const outerTo = i < pipes.length ? pipes[i] : to;
    const content = next < contents.length && contents[next].from < outerTo ? contents[next] : null;
    if (content) next += 1;
    const edge = i === 0 || i === pipes.length;
    if (edge && !content) continue;
    if (content) {
      cells.push({ from: content.from, to: content.to, outerFrom, outerTo });
    } else {
      // Between the two spaces of `|  |`; with less room than that, straight after the pipe.
      const at = outerFrom + (outerTo - outerFrom >= 2 ? 1 : 0);
      cells.push({ from: at, to: at, outerFrom, outerTo });
    }
  }
  const framed = pipes.length >= 2 && pipes[0] === from && cells.length === pipes.length - 1;
  return { cells, framed };
}

/** A header or body row, from a cursor standing on its node (and left there). */
function readRow(cursor) {
  const { from, to } = cursor;
  const pipes = [];
  const contents = [];
  if (cursor.firstChild()) {
    do {
      if (cursor.name === 'TableDelimiter') pipes.push(cursor.from);
      else if (cursor.name === 'TableCell') contents.push({ from: cursor.from, to: cursor.to });
    } while (cursor.nextSibling());
    cursor.parent();
  }
  return { from, to, ...cellsOf(from, to, pipes, contents) };
}

/** The delimiter row, which the tree keeps as one node: its text holds only pipes, dashes, colons and blanks. */
function readDelimiter(doc, from, to) {
  const text = doc.sliceString(from, to);
  const pipes = [];
  const contents = [];
  const aligns = [];
  const cell = /[:-]+/g;
  for (let i = text.indexOf(PIPE); i >= 0; i = text.indexOf(PIPE, i + 1)) pipes.push(from + i);
  for (let m = cell.exec(text); m; m = cell.exec(text)) {
    contents.push({ from: from + m.index, to: from + m.index + m[0].length });
    const left = m[0].startsWith(':');
    const right = m[0].endsWith(':');
    aligns.push(left && right ? 'center' : left ? 'left' : right ? 'right' : null);
  }
  return { from, to, aligns, ...cellsOf(from, to, pipes, contents) };
}

/**
 * The model of the `Table` node `tableNode` of `state`:
 * `{ from, to, editable, columns, aligns, header, delimiter, body, doc }`, where `header` and each of `body` are rows
 * `{ from, to, cells }`, `delimiter` is the delimiter row with its own `cells`, `columns` is the delimiter row's cell
 * count and `doc` the document the offsets belong to.
 *
 * `editable` says the grid can write every cell back to the span it was read from, and that the operations below
 * apply. It takes all of: a top-level table (not one in a list item or a quote); one header row; every row on one
 * line, with the delimiter row's cell count, and with both its outer pipes. The outer pipes matter because a row
 * without them loses a cell when that cell is emptied (`a | ` is one cell), so the grid could not keep the columns.
 * A table that is not editable is still read, as far as it goes, with the first header row as `header`.
 */
export function readTable(state, tableNode) {
  const { doc } = state;
  const headers = [];
  const body = [];
  let delimiter = null;
  let framed = true;
  let singleLines = true;
  const cursor = tableNode.cursor();
  const keep = (row, list) => {
    framed = framed && row.framed;
    singleLines = singleLines && doc.lineAt(row.from).to >= row.to;
    const { from, to, cells } = row;
    list.push({ from, to, cells });
  };
  if (cursor.firstChild()) {
    do {
      if (cursor.name === 'TableHeader') keep(readRow(cursor), headers);
      else if (cursor.name === 'TableRow') keep(readRow(cursor), body);
      else if (cursor.name === 'TableDelimiter' && !delimiter) delimiter = readDelimiter(doc, cursor.from, cursor.to);
    } while (cursor.nextSibling());
  }
  const columns = delimiter ? delimiter.cells.length : 0;
  const header = headers[0] || { from: tableNode.from, to: tableNode.from, cells: [] };
  const sameCount = (row) => row.cells.length === columns;
  const editable = Boolean(delimiter) && delimiter.framed && framed && singleLines && headers.length === 1
    && Boolean(tableNode.parent?.type.isTop) && columns > 0 && sameCount(header) && body.every(sameCount);
  return {
    from: tableNode.from,
    to: tableNode.to,
    editable,
    columns,
    aligns: delimiter ? delimiter.aligns : [],
    header,
    delimiter: delimiter
      ? { from: delimiter.from, to: delimiter.to, cells: delimiter.cells }
      : { from: tableNode.to, to: tableNode.to, cells: [] },
    body,
    doc,
  };
}

/** The model of the table `pos` is in or at the edge of, or null. Costs a tree lookup and a read of that one table. */
export function tableAt(state, pos) {
  const tree = syntaxTree(state);
  for (const side of [1, -1]) {
    for (let node = tree.resolve(pos, side); node; node = node.parent) {
      if (node.name === 'Table') return readTable(state, node);
    }
  }
  return null;
}

/** The index of the cell of `row` that `pos` is in (between its pipes, edges included), or -1. */
function columnAt(row, pos) {
  return row.cells.findIndex((c) => pos >= c.outerFrom && pos <= c.outerTo);
}

/**
 * The cell `pos` is in: `{ row, col }` with row 0 the header and body rows from 1. Null on the delimiter row, outside
 * a row's outer pipes, and outside the table.
 */
export function cellAt(table, pos) {
  let row = -1;
  if (pos >= table.header.from && pos <= table.header.to) {
    row = 0;
  } else {
    let lo = 0;
    let hi = table.body.length - 1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (pos < table.body[mid].from) hi = mid - 1;
      else if (pos > table.body[mid].to) lo = mid + 1;
      else { row = mid + 1; break; }
    }
  }
  if (row < 0) return null;
  const col = columnAt(row === 0 ? table.header : table.body[row - 1], pos);
  return col < 0 ? null : { row, col };
}

/**
 * `text` as it is stored in a cell: a `|` gets a backslash unless it has one (a backslash that is itself escaped does
 * not count), and each run of line breaks becomes one space, since a cell holds no line break.
 */
export function escapeCell(text) {
  let out = '';
  let escaped = false;
  for (const ch of text.replace(/[\r\n]+/g, ' ')) {
    out += ch === PIPE && !escaped ? '\\|' : ch;
    escaped = !escaped && ch === '\\';
  }
  return out;
}

/** Every row of the table, the delimiter row included, in document order. */
const allRows = (table) => [table.header, table.delimiter, ...table.body];
const isColumn = (table, index) => Number.isInteger(index) && index >= 0 && index < table.columns;

/**
 * A new row of empty cells as body row `index` (1 is the first body row, `body.length + 1` appends): one inserted
 * line. Nothing for a table that is not editable or an index outside that range.
 */
export function insertRow(table, index) {
  if (!table.editable || !Number.isInteger(index) || index < 1 || index > table.body.length + 1) return [];
  // After the end of the row above rather than before the row below: the same change appends, and a row below keeps its indent.
  const above = index === 1 ? table.delimiter : table.body[index - 2];
  return [{ from: above.to, to: above.to, insert: `\n${PIPE}${NEW_CELL.repeat(table.columns)}` }];
}

/** Body row `index` (from 1) removed: one line and one line break. Never the header (row 0). */
export function deleteRow(table, index) {
  if (!table.editable || !Number.isInteger(index) || index < 1 || index > table.body.length) return [];
  const above = index === 1 ? table.delimiter : table.body[index - 2];
  return [{ from: above.to, to: table.body[index - 1].to, insert: '' }];
}

/** A new empty column at `index` (0 to `columns`, the last appending): one insertion in each row. */
export function insertColumn(table, index) {
  if (!table.editable || !Number.isInteger(index) || index < 0 || index > table.columns) return [];
  return allRows(table).map((row) => {
    // Straight after the pipe that opens the cell now at `index`; when appending, after the row's closing pipe.
    const at = index < table.columns ? row.cells[index].outerFrom : row.cells[index - 1].outerTo + 1;
    return { from: at, to: at, insert: row === table.delimiter ? NEW_DELIMITER_CELL : NEW_CELL };
  });
}

/** Column `index` removed: in each row, the cell and the pipe that closes it. Never the last remaining column. */
export function deleteColumn(table, index) {
  if (!table.editable || !isColumn(table, index) || table.columns < 2) return [];
  return allRows(table).map((row) => ({ from: row.cells[index].outerFrom, to: row.cells[index].outerTo + 1, insert: '' }));
}

/**
 * Column `from` moved so that it becomes column `to`: in each row the cell, padding and closing pipe included, is
 * removed where it was and inserted where it goes. The columns it passes over are not rewritten.
 */
export function moveColumn(table, from, to) {
  if (!table.editable || !isColumn(table, from) || !isColumn(table, to) || from === to) return [];
  const changes = [];
  for (const row of allRows(table)) {
    const cell = row.cells[from];
    const remove = { from: cell.outerFrom, to: cell.outerTo + 1, insert: '' };
    const at = to < from ? row.cells[to].outerFrom : row.cells[to].outerTo + 1;
    const add = { from: at, to: at, insert: table.doc.sliceString(remove.from, remove.to) };
    if (to < from) changes.push(add, remove);
    else changes.push(remove, add);
  }
  return changes;
}

/**
 * Column `col` aligned `'left'`, `'center'`, `'right'` or not at all (`null`): only the colons of that column's
 * delimiter cell are added or removed (`:---`, `:---:`, `---:`, `---`), the dashes staying as written.
 */
export function setAlign(table, col, align) {
  if (!table.editable || !isColumn(table, col) || !ALIGNS.includes(align)) return [];
  const cell = table.delimiter.cells[col];
  const now = table.aligns[col];
  const has = { left: now === 'left' || now === 'center', right: now === 'right' || now === 'center' };
  const want = { left: align === 'left' || align === 'center', right: align === 'right' || align === 'center' };
  const changes = [];
  if (want.left && !has.left) changes.push({ from: cell.from, to: cell.from, insert: ':' });
  if (has.left && !want.left) changes.push({ from: cell.from, to: cell.from + 1, insert: '' });
  if (want.right && !has.right) changes.push({ from: cell.to, to: cell.to, insert: ':' });
  if (has.right && !want.right) changes.push({ from: cell.to - 1, to: cell.to, insert: '' });
  return changes;
}
