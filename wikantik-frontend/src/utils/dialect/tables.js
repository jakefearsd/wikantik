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

/** `@lezer/markdown`'s GFM `TableParser`, reading rows with this file's `parseRow`. */
class TableParser {
  constructor() {
    // null: the second line is not seen yet; false: not a table; an array: the rows so far.
    this.rows = null;
  }

  nextLine(cx, line, leaf) {
    if (this.rows == null) {
      this.rows = false;
      let lineText;
      if ((line.next === 45 || line.next === 58 || line.next === PIPE) && isDelimiter(lineText = line.text.slice(line.pos))) {
        const firstRow = [];
        const firstCount = parseRow(cx, leaf.content, 0, firstRow, leaf.start);
        if (firstCount === parseRow(cx, lineText, 0)) {
          this.rows = [cx.elt('TableHeader', leaf.start, leaf.start + leaf.content.length, firstRow),
            cx.elt('TableDelimiter', cx.lineStart + line.pos, cx.lineStart + line.text.length)];
        }
      }
    } else if (this.rows) {
      const content = [];
      parseRow(cx, line.text, line.pos, content, cx.lineStart);
      this.rows.push(cx.elt('TableRow', cx.lineStart + line.pos, cx.lineStart + line.text.length, content));
    }
    return false;
  }

  finish(cx, leaf) {
    if (!this.rows) return false;
    cx.addLeafElement(leaf, cx.elt('Table', leaf.start, leaf.start + leaf.content.length, this.rows));
    return true;
  }
}

/**
 * GFM tables as the reader splits them: the `Table` block of `@lezer/markdown`'s GFM, replaced by name, with a row
 * splitter that keeps a code span or inline math span whole across a pipe. Node names are GFM's own.
 *
 * Known limit: a same-name spec replaces `parse` and `leaf` but GFM's own `endLeaf` stays registered (the config API
 * cannot remove it, and its table class is not exported to test for). It splits rows with the stock splitter, so a
 * paragraph directly followed by a header row whose code span or math holds a pipe, over a delimiter row whose cell count
 * matches only under the stock split, is cut after the paragraph and the header row becomes text, not a table.
 */
export const tableExtension = {
  parseBlock: [{
    name: 'Table',
    leaf: (_, leaf) => (hasPipe(leaf.content, 0) ? new TableParser() : null),
    endLeaf(cx, line, leaf) {
      if (leaf.parsers.some((p) => p instanceof TableParser) || !hasPipe(line.text, line.basePos)) return false;
      const next = cx.peekLine();
      return isDelimiter(next) && parseRow(cx, line.text, line.basePos) === parseRow(cx, next, line.basePos);
    },
    before: 'SetextHeading',
  }],
};
