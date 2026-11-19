import { EditorState, EditorSelection, Prec, StateEffect, Transaction } from '@codemirror/state';
import { Decoration, EditorView, ViewPlugin, keymap } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { cellAt, escapeCell, insertRow } from '../tableModel';
import { liveModeField, setLiveMode, refreshLivePreview } from './plugin';
import { gridTableOf, gridTableAt, gridRowAt, cellShownEnd, caretsInCellPadding } from './ranges';
import { composing } from './sticky';

/**
 * The table grid of Rich mode: a top-level table that tableModel.js calls `editable` is drawn as a grid of cells while
 * its rows stay the editor lines they are. Typing in a cell is ordinary typing in the one editor — one text change per
 * keystroke, so shared editing, undo and IME input need nothing of their own. Any other table (ragged rows, in a list
 * or quote, without its outer pipes) keeps the table widget of tableWidget.js; a table changes hands on the update
 * that changes what it is.
 *
 * Who does what:
 * - ranges.js (`gridTable` in livePreviewSpecs) hides what is not cell text — the pipes with the padding around them,
 *   and the delimiter row as a hidden line — as sticky ranges, so sticky.js makes them atomic and keeps the caret out
 *   of them, and the inline pass decorates a cell's text as it does a paragraph's.
 * - This file draws the boxes: a line decoration per row carrying the table's column widths, and one mark per cell at
 *   the lowest precedence (so the inline marks nest inside it) running to the next cell's text, with that cell's
 *   hidden padding and pipe inside it. It also holds the grid's caret rule, its input rules and its keys.
 *
 * Input rules (gridInput): only local user input is rewritten — `input`, `delete` and `move.drop` user events. A remote
 * change, an undo or redo, a change with no user event (a command, a table operation) and a composition pass through
 * untouched, and so does a change whose selection starts or ends outside the table, which behaves as in Source. What
 * is rewritten stays one transaction and is what Source would do for "this text in this cell", except that the text is
 * stored escaped (escapeCell) and a deletion stops at the cell.
 *
 * Nothing here dispatches from a composition or DOM input event (see sticky.js). The one dispatch that is not a key's
 * is the redraw after a width measurement, from a microtask after the measure cycle, and never during a composition.
 */

const live = (state) => state.field(liveModeField, false) === true;

// ---- The cell at a position -----------------------------------------------------------------------------------

const rowsOf = (table) => [table.header, ...table.body];

const currentCache = new WeakMap(); // doc → { tree, head, value }
/**
 * The grid table and cell holding the main caret: `{ table, row, col }` (row 0 is the header), or null when the caret
 * is not in a cell of a table the grid draws (which is always so outside Rich mode). Memoised per (document, caret).
 * The table is a snapshot of this state: ask again after any change.
 */
export function currentTable(state) {
  if (!live(state)) return null;
  const { head } = state.selection.main;
  const tree = syntaxTree(state);
  const hit = currentCache.get(state.doc);
  if (hit && hit.head === head && hit.tree === tree) return hit.value;
  const table = gridTableAt(state, head);
  const at = table ? cellAt(table, head) : null;
  const value = at ? { table, row: at.row, col: at.col } : null;
  currentCache.set(state.doc, { tree, head, value });
  return value;
}

/** currentTable with the row and cell themselves, when the selection is one range. */
function cellOfSelection(state) {
  if (state.selection.ranges.length !== 1) return null;
  const cur = currentTable(state);
  if (!cur) return null;
  const rows = rowsOf(cur.table);
  return { ...cur, rows, cell: rows[cur.row].cells[cur.col] };
}

// ---- Caret rule ------------------------------------------------------------------------------------------------

/**
 * Where a caret at `head` on a grid row rests, and how it leans: `{ head, assoc }`, or null off a grid row (the
 * delimiter row is a hidden line, which sticky.js handles). The nearest cell's text, edges included. At a cell's start
 * the caret must not lean back, nor forward at its end (ArrowRight across a pipe leaves it leaning back): it would be
 * drawn at the neighbouring cell's edge although typing goes here.
 */
function cellPlace(state, range) {
  const { head } = range;
  const table = gridTableAt(state, head);
  const row = table ? gridRowAt(table, head) : null;
  if (!row) return null;
  const { cells } = row;
  const endOf = (c) => cellShownEnd(c, state.selection);
  let i = cells.findIndex((c) => head <= endOf(c));
  let pos = head;
  if (i < 0) {
    i = cells.length - 1; // past the last cell's text: after the row's closing pipe
    pos = endOf(cells[i]);
  } else if (head < cells[i].from) {
    // In the hidden stretch before cell i: the nearer of the previous cell's end and this cell's start.
    if (i > 0 && head - endOf(cells[i - 1]) < cells[i].from - head) {
      i -= 1;
      pos = endOf(cells[i]);
    } else {
      pos = cells[i].from;
    }
  }
  // Only a lean the wrong way is corrected: a caret that leans neither way (typing leaves it so) is drawn in its own
  // cell at either edge, and correcting it would cost every keystroke at a cell's end a second pass over the change.
  let { assoc } = range;
  if (pos === cells[i].from) assoc = assoc < 0 || pos !== head ? 1 : assoc;
  else if (pos === endOf(cells[i])) assoc = assoc > 0 || pos !== head ? -1 : assoc;
  return { head: pos, assoc };
}

/**
 * Keeps every caret on a grid row inside a cell, leaning into it. Like the sticky caret rule, which it follows: runs on
 * a transaction that sets the selection or changes the document (a remote change maps the caret), skips a
 * composition, leaves non-empty ranges alone, and only ever adds a selection.
 */
const gridCaret = EditorState.transactionFilter.of((tr) => {
  if (!(tr.selection || tr.docChanged) || !live(tr.startState) || composing(tr)) return tr;
  const { state } = tr;
  if (!live(state)) return tr;
  let moved = false;
  const ranges = state.selection.ranges.map((range) => {
    if (!range.empty) return range;
    const place = cellPlace(state, range);
    if (!place || (place.head === range.head && place.assoc === range.assoc)) return range;
    moved = true;
    const sameSpot = place.head === range.head;
    return EditorSelection.cursor(place.head, place.assoc, undefined, sameSpot ? range.goalColumn : undefined);
  });
  if (!moved) return tr;
  return [tr, { selection: EditorSelection.create(ranges, state.selection.mainIndex), sequential: true }];
});

// ---- Input rules -----------------------------------------------------------------------------------------------

const BACKSLASH = '\\';
const trailingBackslashes = (text) => { let n = 0; while (n < text.length && text[text.length - 1 - n] === BACKSLASH) n += 1; return n; };

/**
 * `text` as it is stored when inserted after `before` (the cell's text up to the insertion): escapeCell, except that a
 * backslash already ending `before` escapes what follows it — a `|` typed after a `\` is the pipe that backslash escapes.
 */
function storedText(before, text) {
  if (!text) return '';
  return trailingBackslashes(before) % 2 === 1 ? escapeCell(`${BACKSLASH}${text}`).slice(1) : escapeCell(text);
}

/**
 * The change `[fromA, toA) → text` of local user input as the grid makes it, `{ from, to, insert, before }`, or null
 * when it is left as it is: outside a grid table, a selection (or change) that starts or ends outside the table, or one
 * anchored outside a cell. Otherwise the range is clipped to the cell its selection's anchor is in — its text and
 * whatever padding a caret rests in, never a pipe — and the text is stored escaped. A pipe straight after the change
 * keeps its meaning: the insertion gets a backslash more before a pipe that was escaped, a space before the cell's
 * closing pipe when it would otherwise escape it.
 */
function cellChange(state, fromA, toA, text) {
  const table = gridTableAt(state, fromA) || (toA > fromA ? gridTableAt(state, toA) : null);
  if (!table) return null;
  const sel = state.selection.ranges.find((r) => r.from <= toA && r.to >= fromA);
  if (Math.min(fromA, sel ? sel.from : fromA) < table.from || Math.max(toA, sel ? sel.to : toA) > table.to) return null;
  const pivot = sel ? sel.anchor : fromA;
  const at = cellAt(table, pivot);
  if (!at) return null;
  const cell = rowsOf(table)[at.row].cells[at.col];
  const lo = Math.min(cell.from, pivot);
  const hi = Math.max(cellShownEnd(cell, state.selection), pivot);
  const from = Math.min(Math.max(fromA, lo), hi);
  const to = Math.min(Math.max(toA, lo), hi);
  const before = state.doc.sliceString(cell.outerFrom, from);
  let insert = storedText(before, text);
  if (state.doc.sliceString(to, to + 1) === '|') {
    const escapes = trailingBackslashes(before + insert) % 2 === 1;
    const closing = to === cell.outerTo;
    if (escapes && closing) insert += ' ';
    else if (!escapes && !closing) insert += BACKSLASH;
  }
  // A deletion that only took an escaping character and puts it back changes nothing.
  if (insert && insert === state.doc.sliceString(from, to)) return { from, to: from, insert: '', before };
  return { from, to, insert, before };
}

/** The annotations of `tr`, to carry over to the transaction that replaces it. */
function annotationsOf(tr) {
  if (Array.isArray(tr.annotations)) return tr.annotations;
  const kept = [Transaction.userEvent, Transaction.addToHistory].filter((type) => tr.annotation(type) !== undefined);
  return kept.map((type) => type.of(tr.annotation(type)));
}

const gridInput = EditorState.transactionFilter.of((tr) => {
  const state = tr.startState;
  if (!tr.docChanged || !live(state) || state.readOnly) return tr;
  if (tr.annotation(Transaction.remote) || tr.isUserEvent('undo') || tr.isUserEvent('redo')) return tr;
  if (!(tr.isUserEvent('input') || tr.isUserEvent('delete') || tr.isUserEvent('move.drop')) || composing(tr)) return tr;
  const parts = [];
  let rewritten = false;
  try {
    tr.changes.iterChanges((fromA, toA, fromB, toB, inserted) => {
      const text = inserted.toString();
      const fix = cellChange(state, fromA, toA, text);
      const same = !fix || (fix.from === fromA && fix.to === toA && fix.insert === text);
      if (!same) rewritten = true;
      parts.push({ fromB, toB, text, ...(same ? { from: fromA, to: toA, insert: text, before: null } : fix) });
    }, true);
  } catch (err) {
    console.warn('[rich] could not apply the table cell rules; the edit is left as typed', err?.message || err);
    return tr;
  }
  if (!rewritten) return tr;
  const changes = state.changes(parts.map(({ from, to, insert }) => ({ from, to, insert })));
  const back = tr.changes.invertedDesc;
  // A position of the transaction's own result, in the rewritten result: inside a change's text, the same place in
  // the stored text (after it, when it was at the end); elsewhere, the same place of the unchanged text.
  const mapPos = (pos) => {
    const part = parts.find((p) => pos >= p.fromB && pos <= p.toB);
    if (!part) return changes.mapPos(back.mapPos(pos), 1);
    const offset = pos - part.fromB;
    let stored = offset; // a change left as it was
    if (part.before !== null) {
      stored = offset >= part.text.length ? part.insert.length : Math.min(storedText(part.before, part.text.slice(0, offset)).length, part.insert.length);
    }
    return changes.mapPos(part.from, -1) + stored;
  };
  const selection = tr.selection
    ? EditorSelection.create(tr.selection.ranges.map((r) => EditorSelection.range(mapPos(r.anchor), mapPos(r.head))), tr.selection.mainIndex)
    : undefined;
  return [{
    changes,
    selection,
    effects: StateEffect.mapEffects(tr.effects, back.composeDesc(changes.desc)),
    annotations: annotationsOf(tr),
    scrollIntoView: tr.scrollIntoView,
  }];
});

// ---- Keys ------------------------------------------------------------------------------------------------------

/** A one-cursor selection (a bare range in a transaction spec would lose its lean). */
const caret = (pos, assoc = 0) => EditorSelection.create([EditorSelection.cursor(pos, assoc)]);

const goTo = (view, pos, assoc = 1) => {
  view.dispatch({ selection: caret(pos, assoc), scrollIntoView: true, userEvent: 'select' });
  return true;
};

/**
 * A new last row, with the caret in its cell `col`: one change (insertRow) and its selection in one transaction, with
 * no user event — it is a table operation, which the input rules leave alone. Nothing on a read-only document.
 */
function appendRow(view, table, col) {
  if (view.state.readOnly) return true;
  const [change] = insertRow(table, table.body.length + 1);
  if (!change) return true;
  // The typing position of cell `col` in the inserted text, by tableModel's rule: between the two spaces of `|  |`.
  let pipe = -1;
  for (let k = 0; k <= col; k += 1) pipe = change.insert.indexOf('|', pipe + 1);
  const room = change.insert.indexOf('|', pipe + 1) - pipe - 1;
  const typingAt = change.from + pipe + 1 + (room >= 2 ? 1 : 0);
  view.dispatch({ changes: change, selection: caret(typingAt, 1), scrollIntoView: true });
  return true;
}

/** Tab: the start of the next cell, the next row's first after a row's last; from the table's last cell, a new row. */
function nextCell(view) {
  const cur = cellOfSelection(view.state);
  if (!cur) return false;
  const { table, rows, row, col } = cur;
  if (col + 1 < table.columns) return goTo(view, rows[row].cells[col + 1].from);
  if (row + 1 < rows.length) return goTo(view, rows[row + 1].cells[0].from);
  return appendRow(view, table, 0);
}

/** Shift-Tab: the start of the previous cell; the table's first cell keeps the caret. */
function previousCell(view) {
  const cur = cellOfSelection(view.state);
  if (!cur) return false;
  const { table, rows, row, col } = cur;
  if (col > 0) return goTo(view, rows[row].cells[col - 1].from);
  if (row > 0) return goTo(view, rows[row - 1].cells[table.columns - 1].from);
  return true;
}

/** Enter: the same column of the next row — a cell holds no line break; from the last row, a new row. */
function cellBelow(view) {
  const cur = cellOfSelection(view.state);
  if (!cur) return false;
  const { table, rows, row, col } = cur;
  if (row + 1 < rows.length) return goTo(view, rows[row + 1].cells[col].from);
  return appendRow(view, table, col);
}

/** Whether the caret is on the last (first, going up) visual line of its cell: a wrapped cell is moved through first. */
function onEdgeLine(view, cell, range, forward) {
  const edge = forward ? cellShownEnd(cell, view.state.selection) : cell.from;
  if (range.head === edge) return true;
  const a = view.coordsAtPos(range.head, range.assoc || 1);
  const b = view.coordsAtPos(edge, forward ? -1 : 1);
  if (!a || !b) return true;
  return Math.abs(a.top - b.top) <= Math.max(1, (a.bottom - a.top) / 2);
}

/**
 * Where in the cell `to` of the row below (above) the caret goes: under (over) where it is drawn — on the cell's first
 * visual line going down, its last going up, at the column the motion started from (`goal`, kept across presses as
 * CodeMirror's own vertical motion keeps it). Without layout (the row is not drawn), the same offset into the text.
 */
function placeBeside(view, to, range, cell, forward) {
  const fallback = { pos: Math.min(to.from + (range.head - cell.from), to.to) };
  try {
    const start = view.coordsAtPos(range.head, range.assoc || 1);
    const line = view.coordsAtPos(forward ? to.from : to.to, forward ? 1 : -1);
    if (!start || !line || line.bottom <= line.top) return fallback;
    const left = view.contentDOM.getBoundingClientRect().left;
    const goal = range.goalColumn ?? start.left - left;
    // With its side: at a wrapped cell's line break the same position is the end of one visual line and the start of the next.
    const hit = view.posAndSideAtCoords({ x: left + goal, y: (line.top + line.bottom) / 2 }, false);
    const pos = Math.min(Math.max(hit.pos, to.from), to.to);
    return { pos, assoc: pos === hit.pos ? hit.assoc : 0, goal };
  } catch (err) {
    console.warn('[rich] could not read where the caret is drawn; it keeps its offset in the cell', err?.message || err);
    return fallback;
  }
}

/**
 * ArrowDown/ArrowUp with the caret in a cell: the same column of the next (previous) row, over the delimiter row.
 * Declines inside a wrapped cell until its last (first) visual line, and at the table's edge, where the editor's own
 * motion leaves the table — except that a table ending the document could not be left downwards at all, nor one
 * starting it upwards: there the plain key adds a blank line and an empty one beyond it (one empty line would be read
 * as a row of the table once typed on) and goes there. Not on a read-only document.
 */
const rowBeside = (forward) => (view) => {
  const { state } = view;
  const cur = cellOfSelection(state);
  if (!cur || !state.selection.main.empty) return false;
  const { table, rows, row, col, cell } = cur;
  const range = state.selection.main;
  if (!onEdgeLine(view, cell, range, forward)) return false;
  const target = rows[row + (forward ? 1 : -1)];
  if (target) {
    const to = target.cells[col];
    const { pos, assoc, goal } = placeBeside(view, to, range, cell, forward);
    const cursor = EditorSelection.cursor(pos, assoc || (pos === to.from ? 1 : -1), undefined, goal);
    view.dispatch({ selection: EditorSelection.create([cursor]), scrollIntoView: true, userEvent: 'select' });
    return true;
  }
  const { doc } = state;
  const atEdge = forward ? doc.lineAt(table.to).to === doc.length : doc.lineAt(table.from).from === 0;
  if (!atEdge) return false;
  if (state.readOnly) return true;
  const changes = state.changes({ from: forward ? doc.length : 0, insert: '\n\n' });
  view.dispatch({ changes, selection: caret(forward ? changes.newLength : 0), scrollIntoView: true });
  return true;
};

/**
 * ArrowRight at the end of a row's last cell, ArrowLeft at the start of its first: on to the next row's first cell
 * (the previous row's last), or out of the table at its edge. The editor's own motion would stop at the row's closing
 * (opening) pipe, where the caret rule sends it straight back, so the caret could never leave the row sideways.
 */
const rowEdge = (forward) => (view) => {
  const { state } = view;
  const cur = cellOfSelection(state);
  if (!cur || !state.selection.main.empty) return false;
  const { table, rows, row, col, cell } = cur;
  const { head } = state.selection.main;
  const { doc } = state;
  if (forward) {
    if (col !== table.columns - 1 || head !== cellShownEnd(cell, state.selection)) return false;
    if (rows[row + 1]) return goTo(view, rows[row + 1].cells[0].from);
    const after = doc.lineAt(table.to).to + 1;
    return after <= doc.length ? goTo(view, after) : true;
  }
  if (col !== 0 || head !== cell.from) return false;
  if (row > 0) {
    const last = rows[row - 1].cells[table.columns - 1];
    return goTo(view, cellShownEnd(last, state.selection), -1);
  }
  const before = doc.lineAt(table.from).from - 1;
  return before >= 0 ? goTo(view, before, -1) : true;
};

// Highest, like the sticky keys: lang-markdown binds Enter at Prec.high, and Tab indents by default. Each declines
// outside a grid cell. (An open completion list keeps Enter: its keymap is registered before the live preview's.)
const gridKeymap = Prec.highest(keymap.of([
  { key: 'Tab', run: nextCell, shift: previousCell },
  { key: 'Enter', run: cellBelow },
  { key: 'ArrowDown', run: rowBeside(true) },
  { key: 'ArrowUp', run: rowBeside(false) },
  { key: 'ArrowRight', run: rowEdge(true) },
  { key: 'ArrowLeft', run: rowEdge(false) },
]));

/**
 * A mousedown on an empty cell's box puts the caret in that cell. The box is a widget that stands after the cell's
 * typing position, so the editor's own hit test gives its far side — the next cell — for the right half of it.
 */
const emptyCellClick = EditorView.domEventHandlers({
  mousedown(event, view) {
    const box = event.target?.closest?.('.cm-lp-cell-empty');
    if (!box || event.button !== 0 || event.shiftKey || !live(view.state)) return false;
    try {
      view.dispatch({ selection: caret(view.posAtDOM(box), 1), userEvent: 'select.pointer' });
      view.focus();
      event.preventDefault();
      return true;
    } catch (err) {
      console.warn('[rich] could not place the caret in an empty table cell', err?.message || err);
      return false;
    }
  },
});

// ---- Column widths ---------------------------------------------------------------------------------------------

/** A column is never squeezed under this many characters (`ch`) to make a table fit the editor. */
const MIN_CH = 8;
/**
 * A table that cannot fit even with every column at the minimum scrolls sideways, and is then not squeezed at all:
 * each column keeps its natural width up to this many characters. (Squeezed to the minimum and scrolling anyway, a
 * twelve-column table drew five-line rows.)
 */
const WIDE_CH = 24;
/** The cell box around its text, as the theme draws it (editorTheme.js); read from a drawn cell when there is one. */
const CELL_BOX = { pad: 16, border: 1 };
const WIDTH_CACHE_LIMIT = 20000;

/**
 * Column widths (px of cell text) for `natural` widths in `room` px: the naturals, each at least `min`, when they fit;
 * else columns under the fair share keep theirs and the rest share what is left, never under `min`; and when even
 * `min` each does not fit, the naturals capped at `wide` (the row then overflows and the editor scrolls sideways).
 */
export function fitColumns(natural, room, min, wide) {
  const n = natural.length;
  const w = natural.map((x) => Math.max(x, min));
  if (w.reduce((a, b) => a + b, 0) <= room) return w;
  if (min * n > room) return w.map((x) => Math.min(x, Math.max(wide, min)));
  const order = w.map((_, i) => i).sort((a, b) => w[a] - w[b]);
  const out = new Array(n);
  let left = room;
  order.forEach((i, k) => {
    out[i] = Math.max(min, Math.min(w[i], Math.floor(left / (n - k))));
    left -= out[i];
  });
  return out;
}

/** Roughly what a cell shows once its marks are hidden: a link's text without its destination, no emphasis or code marks. */
function drawnText(source) {
  return source.replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/\[\[(?:[^\]|]*\|)?([^\]]*)\]\]/g, '$1')
    .replace(/(\*\*|__|~~|==|`+)/g, '').replace(/(^|\W)[*_](\S(?:.*?\S)?)[*_](?=\W|$)/g, '$1$2').replace(/\\([|*_`\\])/g, '$1');
}

// Cell text widths by `font|source text`, for every view: an estimate from a canvas, and what a drawn cell measured.
// A measurement only ever raises a width: lowering one moved a column while its table was being scrolled.
const estimated = new Map();
const measured = new Map();
let generation = 0; // bumped when a width changes: a table's computed style is good for one generation
let canvas = null;

function widthOf(font, text) {
  const key = `${font}|${text}`;
  let width = estimated.get(key);
  if (width === undefined) {
    if (estimated.size > WIDTH_CACHE_LIMIT) { estimated.clear(); measured.clear(); }
    canvas.font = font;
    width = Math.ceil(canvas.measureText(drawnText(text)).width);
    estimated.set(key, width);
  }
  const real = measured.get(key);
  return real !== undefined && real > width ? real : width;
}

/** Each column's natural width over every row of the table, off-screen ones included, or a column would change width as rows scroll in. */
function naturalWidths(table, fonts) {
  const widths = new Array(table.columns).fill(0);
  const { doc } = table;
  const add = (row, font) => row.cells.forEach((c, i) => {
    if (c.to > c.from) widths[i] = Math.max(widths[i], widthOf(font, doc.sliceString(c.from, c.to)));
  });
  add(table.header, fonts.header);
  for (const row of table.body) add(row, fonts.body);
  return widths;
}

/**
 * What the widths depend on in the view, or null while there is no layout (a hidden editor, a test DOM). The room for a
 * row is the scroller's, never the content's: a row that does not wrap widens `.cm-content`, which would feed back.
 */
function readGeometry(view) {
  const scroller = view.scrollDOM;
  if (!scroller.clientWidth) return null;
  canvas ||= document.createElement('canvas').getContext('2d');
  if (!canvas) return null;
  const cs = getComputedStyle(view.contentDOM);
  const body = `${cs.fontStyle} ${cs.fontWeight} ${cs.fontSize} ${cs.fontFamily}`;
  const header = `${cs.fontStyle} 600 ${cs.fontSize} ${cs.fontFamily}`;
  canvas.font = body;
  const ch = canvas.measureText('0').width;
  const line = view.contentDOM.querySelector('.cm-line');
  const ls = line ? getComputedStyle(line) : null;
  const linePad = ls ? parseFloat(ls.paddingLeft) + parseFloat(ls.paddingRight) : 0;
  const cell = view.contentDOM.querySelector('.cm-lp-cell');
  const cellStyle = cell ? getComputedStyle(cell) : null;
  const pad = cellStyle ? parseFloat(cellStyle.paddingLeft) + parseFloat(cellStyle.paddingRight) : CELL_BOX.pad;
  const border = cellStyle ? parseFloat(cellStyle.borderLeftWidth) : CELL_BOX.border;
  const gutter = view.contentDOM.getBoundingClientRect().left - scroller.getBoundingClientRect().left + scroller.scrollLeft;
  const room = Math.floor(scroller.clientWidth - gutter - linePad);
  return { body, header, ch, room, pad, border, key: `${body}|${room}|${pad}|${border}` };
}

/** The unwrapped width of a drawn cell's content: per visual line the extent of its boxes, summed over the lines. */
function naturalWidthOf(el) {
  const range = document.createRange();
  range.selectNodeContents(el);
  const lines = new Map();
  for (const r of range.getClientRects()) {
    if (r.width === 0) continue;
    const k = Math.round(r.top / 4);
    const line = lines.get(k);
    if (!line) lines.set(k, [r.left, r.right]);
    else { line[0] = Math.min(line[0], r.left); line[1] = Math.max(line[1], r.right); }
  }
  // Boxes of one visual line can have different tops (a chip): lines whose keys are adjacent are one line.
  let total = 0;
  let last = null;
  for (const k of [...lines.keys()].sort((a, b) => a - b)) {
    const [left, right] = lines.get(k);
    if (last && k - last.k <= 1) { last.left = Math.min(last.left, left); last.right = Math.max(last.right, right); last.k = k; }
    else { if (last) total += last.right - last.left; last = { k, left, right }; }
  }
  return last ? total + last.right - last.left : total;
}

// ---- Drawing ---------------------------------------------------------------------------------------------------

const remeasured = StateEffect.define();
const redraws = (tr) => tr.effects.some((e) => e.is(setLiveMode) || e.is(refreshLivePreview) || e.is(remeasured));

const cellMarks = new Map();
/**
 * A cell's box. `inclusive`: a hidden range that starts or ends where the mark does is then drawn inside the cell's
 * span; outside it, the caret at the cell's end would be drawn at the cell's border with no height.
 */
function cellMark(col, last, align) {
  const key = `${col}|${last}|${align}`;
  let mark = cellMarks.get(key);
  if (!mark) {
    mark = Decoration.mark({
      inclusive: true,
      class: last ? 'cm-lp-cell cm-lp-cell-last' : 'cm-lp-cell',
      attributes: { 'data-col': String(col), style: `width:var(--lp-col-${col})${align ? `;text-align:${align}` : ''}` },
    });
    cellMarks.set(key, mark);
  }
  return mark;
}

const tableStyles = new WeakMap(); // table model → { key, style }

export const tableGridPlugin = ViewPlugin.fromClass(class {
  constructor(view) {
    this.destroyed = false;
    this.geometry = null;
    this.rows = new Map(); // line start → { cells, header }, for the rows now drawn
    this.padding = '';
    try {
      this.geometry = live(view.state) ? readGeometry(view) : null;
    } catch (err) {
      console.warn('[rich] could not read the editor geometry; table columns size themselves', err?.message || err);
    }
    this.decorations = this.build(view);
    this.measure(view);
  }

  update(u) {
    const padding = u.selectionSet || u.docChanged ? caretsInCellPadding(u.state) : this.padding;
    if (u.docChanged || u.viewportChanged || padding !== this.padding || this.builtAt !== generation
      || syntaxTree(u.startState) !== syntaxTree(u.state) || u.transactions.some(redraws)) {
      this.padding = padding;
      this.decorations = this.build(u.view);
    }
    if (u.docChanged || u.viewportChanged || u.geometryChanged || u.transactions.some(redraws)) this.measure(u.view);
  }

  destroy() { this.destroyed = true; }

  /** The row style of `table`: its column widths as custom properties, one string for all its rows. */
  styleOf(table) {
    const g = this.geometry;
    if (!g) return '';
    const key = `${g.key}|${generation}`;
    const hit = tableStyles.get(table);
    if (hit && hit.key === key) return hit.style;
    const room = g.room - table.columns * (g.pad + g.border) - g.border;
    const widths = fitColumns(naturalWidths(table, g), room, Math.round(MIN_CH * g.ch), Math.round(WIDE_CH * g.ch));
    const style = widths.map((w, i) => `--lp-col-${i}:${w}px`).join(';');
    tableStyles.set(table, { key, style });
    return style;
  }

  build(view) {
    this.builtAt = generation;
    this.rows = new Map();
    const { state } = view;
    if (!live(state)) return Decoration.none;
    try {
      const ranges = [];
      const seen = new Set();
      const tree = syntaxTree(state);
      for (const { from, to } of view.visibleRanges) {
        tree.iterate({
          from,
          to,
          enter: (node) => {
            if (node.name !== 'Table') return node.type.isTop;
            const table = seen.has(node.from) ? null : gridTableOf(state, node.node);
            seen.add(node.from);
            if (table) this.drawTable(view, table, ranges);
            return false;
          },
        });
      }
      return Decoration.set(ranges, true);
    } catch (err) {
      console.warn('[rich] could not draw the table grid; its rows show as text for this update', err?.message || err);
      return Decoration.none;
    }
  }

  /** The row lines and cell boxes of the rows of `table` in the viewport. */
  drawTable(view, table, ranges) {
    const { state } = view;
    const visible = view.visibleRanges;
    const first = visible[0].from;
    const last = visible[visible.length - 1].to;
    const shown = (row) => visible.some((r) => row.from <= r.to && row.to >= r.from);
    const style = this.styleOf(table);
    const attributes = style ? { style } : undefined;
    const drawRow = (row, header) => {
      if (!shown(row)) return;
      const line = state.doc.lineAt(row.from);
      ranges.push(Decoration.line({ class: header ? 'cm-lp-table-row cm-lp-table-header' : 'cm-lp-table-row', attributes }).range(line.from));
      this.rows.set(line.from, { cells: row.cells, header });
      const n = row.cells.length;
      row.cells.forEach((c, i) => {
        // An empty cell has no text to mark: ranges.js puts a widget there that draws the box.
        if (cellShownEnd(c, state.selection) === c.from) return;
        const end = i + 1 < n ? row.cells[i + 1].from : line.to;
        ranges.push(cellMark(i, i === n - 1, table.aligns[i] || null).range(c.from, end));
      });
    };
    drawRow(table.header, true);
    let lo = 0;
    let hi = table.body.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (table.body[mid].to < first) lo = mid + 1; else hi = mid;
    }
    for (let i = lo; i < table.body.length && table.body[i].from <= last; i += 1) drawRow(table.body[i], false);
  }

  /**
   * Reads the geometry and the real width of every drawn cell; a cell drawn wider than its cached width raises it.
   * A change redraws from a microtask: a dispatch inside the measure cycle throws, and the microtask still runs before
   * the frame is painted. Not during a composition, which a dispatch can cut short: the next update redraws instead.
   */
  measure(view) {
    if (!live(view.state)) return;
    view.requestMeasure({
      key: this,
      read: (v) => {
        try {
          const geometry = readGeometry(v);
          const raised = [];
          if (!geometry) return { geometry, raised };
          for (const rowEl of v.contentDOM.querySelectorAll('.cm-lp-table-row')) {
            const row = this.rows.get(v.state.doc.lineAt(v.posAtDOM(rowEl, 0)).from);
            if (!row) continue;
            const font = row.header ? geometry.header : geometry.body;
            const drawn = new Map(); // column → width (a cell's box is one span; were it ever split, its parts add up)
            for (const el of rowEl.querySelectorAll('span.cm-lp-cell:not(.cm-lp-cell-empty)')) {
              const col = Number(el.dataset.col);
              drawn.set(col, (drawn.get(col) || 0) + naturalWidthOf(el));
            }
            for (const [col, natural] of drawn) {
              const c = row.cells[col];
              if (!c || c.to <= c.from) continue;
              // Keyed by the model's cell text, not by what the span holds: a hidden mark at a cell's edge can be drawn outside it.
              const text = v.state.doc.sliceString(c.from, c.to);
              const width = Math.ceil(natural); // rounded up as measured: any less and the widest cell wraps
              if (width - widthOf(font, text) >= 1) raised.push([`${font}|${text}`, width]);
            }
          }
          return { geometry, raised };
        } catch (err) {
          console.warn('[rich] could not measure the table grid; its columns keep their widths', err?.message || err);
          return null;
        }
      },
      write: (result, v) => {
        if (!result || this.destroyed) return;
        const { geometry, raised } = result;
        const moved = (geometry?.key ?? '') !== (this.geometry?.key ?? '');
        if (moved) { this.geometry = geometry; generation += 1; }
        for (const [key, width] of raised) measured.set(key, width);
        if (raised.length) generation += 1;
        if (!moved && !raised.length) return;
        Promise.resolve().then(() => {
          if (this.destroyed || v.composing || v.compositionStarted) return;
          try {
            v.dispatch({ effects: remeasured.of(null) });
          } catch (err) {
            console.warn('[rich] could not redraw the table grid after measuring; the next update does', err?.message || err);
          }
        });
      },
    });
  }
}, { decorations: (plugin) => plugin.decorations });

/**
 * The grid. Inert outside Rich mode. The filters' order is fixed by precedence (the last-registered filter runs
 * first): the input rules see the transaction as typed, before any caret rule; the grid's caret rule runs after the
 * sticky one, on whatever that left. The plugin is lowest so that its cell marks are the outermost spans of a row.
 */
export const tableGridExtension = [
  Prec.lowest(tableGridPlugin),
  Prec.low(gridInput),
  Prec.high(gridCaret),
  gridKeymap,
  emptyCellClick,
];
