import { Table } from '@lezer/markdown';
import { inlineMathEnd } from './math';

const PIPE = 124;
const BACKSLASH = 92;
const BACKTICK = 96;
const DOLLAR = 36;
const TAB = 9;
const SPACE = 32;
const CR = 13;

const delimiterLine = /^[>\s]*\|?(\s*:?-+:?\s*\|)+(\s*:?-+:?\s*)?$/;
/** GFM's delimiter-row test, on the row without its CR (the stock one fails a CRLF row, so a CRLF table is no table). */
const isDelimiter = (text) => delimiterLine.test(text.replace(/\r$/, ''));

/**
 * Where the code span or inline math opening at `i` of `line` ends, or `i + 1` when it opens nothing. The reader
 * (flexmark) keeps both whole across a cell pipe: `| `a|b` |` and `| $a | b$ |` are one cell each, where GFM would
 * split them. A backtick run closes at the next run of the same length in the row; math is the shared Pandoc rule.
 */
function spanEnd(line, i) {
  const c = line.charCodeAt(i);
  if (c === BACKTICK) {
    let n = 1;
    while (line.charCodeAt(i + n) === BACKTICK) n += 1;
    for (let j = i + n; j < line.length;) {
      if (line.charCodeAt(j) !== BACKTICK) { j += 1; continue; }
      let m = 1;
      while (line.charCodeAt(j + m) === BACKTICK) m += 1;
      if (m === n) return j + m;
      j += m;
    }
    return i + n; // no closer: the backticks are text
  }
  const end = inlineMathEnd(line, i);
  return end < 0 ? i + 1 : end;
}

/**
 * `@lezer/markdown`'s `parseRow` (the cell splitter) with one difference: a pipe inside a code span or inline math span
 * does not split the cell, and a trailing CR is blank (GFM's counts it as a cell, so a CRLF table is no table). Returns the cell count; with `elts`, also pushes `TableCell`/`TableDelimiter` elements.
 */
function parseRow(cx, line, startI = 0, elts, offset = 0) {
  let count = 0;
  let first = true;
  let cellStart = -1;
  let cellEnd = -1;
  let esc = false;
  const parseCell = () => {
    elts.push(cx.elt('TableCell', offset + cellStart, offset + cellEnd, cx.parser.parseInline(line.slice(cellStart, cellEnd), offset + cellStart)));
  };
  for (let i = startI; i < line.length; i += 1) {
    const next = line.charCodeAt(i);
    if (next === PIPE && !esc) {
      if (!first || cellStart > -1) count += 1;
      first = false;
      if (elts) {
        if (cellStart > -1) parseCell();
        elts.push(cx.elt('TableDelimiter', i + offset, i + offset + 1));
      }
      cellStart = -1;
      cellEnd = -1;
    } else if (!esc && (next === BACKTICK || next === DOLLAR)) {
      const end = spanEnd(line, i);
      if (cellStart < 0) cellStart = i;
      cellEnd = end;
      i = end - 1;
    } else if (esc || (next !== SPACE && next !== TAB && next !== CR)) {
      if (cellStart < 0) cellStart = i;
      cellEnd = i + 1;
    }
    esc = !esc && next === BACKSLASH;
  }
  if (cellStart > -1) {
    count += 1;
    if (elts) parseCell();
  }
  return count;
}

function hasPipe(str, start) {
  for (let i = start; i < str.length; i += 1) {
    const next = str.charCodeAt(i);
    if (next === PIPE) return true;
    if (next === BACKSLASH) i += 1;
  }
  return false;
}

/**
 * The reader's table, read line by line. A table starts at the first line of a paragraph (the reader never ends a
 * paragraph for one): every pipe-bearing line above the delimiter row is a header row (flexmark's `MAX_HEADER_ROWS` is
 * unbounded and each row's inline text is parsed on its own), and every line below it, up to the end of the paragraph,
 * is a body row. A line with no pipe above the delimiter row means no table. Header and delimiter cell counts are not
 * compared (the reader sets `HEADER_SEPARATOR_COLUMN_MATCH` off).
 */
class TableParser {
  constructor() {
    // null: the delimiter row is not seen yet (`pending` holds the header candidates); false: not a table; an array: the rows.
    this.rows = null;
    this.pending = null;
  }

  nextLine(cx, line, leaf) {
    if (this.rows === false) return false;
    if (this.rows == null) {
      // The first call: the leaf holds just its first line, a header candidate (it has a pipe, or there is no parser).
      if (!this.pending) this.pending = [{ text: leaf.content, from: 0, offset: leaf.start, end: leaf.start + leaf.content.length }];
      const lineText = line.text.slice(line.pos);
      if ((line.next === 45 || line.next === 58 || line.next === PIPE) && isDelimiter(lineText)) {
        this.rows = this.pending.map((p) => cx.elt('TableHeader', p.offset + p.from, p.end, this.cellsOf(cx, p)));
        this.rows.push(cx.elt('TableDelimiter', cx.lineStart + line.pos, cx.lineStart + line.text.length));
        this.pending = null;
      } else if (hasPipe(line.text, line.pos)) {
        this.pending.push({ text: line.text, from: line.pos, offset: cx.lineStart, end: cx.lineStart + line.text.length });
      } else {
        this.rows = false;
        this.pending = null;
      }
    } else {
      const content = [];
      parseRow(cx, line.text, line.pos, content, cx.lineStart);
      this.rows.push(cx.elt('TableRow', cx.lineStart + line.pos, cx.lineStart + line.text.length, content));
    }
    return false;
  }

  cellsOf(cx, p) {
    const cells = [];
    parseRow(cx, p.text, p.from, cells, p.offset);
    return cells;
  }

  finish(cx, leaf) {
    if (!Array.isArray(this.rows)) return false;
    cx.addLeafElement(leaf, cx.elt('Table', leaf.start, leaf.start + leaf.content.length, this.rows));
    return true;
  }
}

/**
 * Tables as the reader reads them, replacing `@lezer/markdown`'s GFM `Table` whole (see `gfmWithoutTable`): a row
 * splitter that keeps a code span or inline math span whole across a pipe, several header rows, no header/delimiter
 * count match, and no paragraph ended by a table. Node names and styles are GFM's own (`Table.defineNodes`).
 */
export const tableExtension = {
  defineNodes: Table.defineNodes,
  parseBlock: [{
    name: 'Table',
    leaf: (_, leaf) => (hasPipe(leaf.content, 0) ? new TableParser() : null),
    before: 'SetextHeading',
  }],
};
