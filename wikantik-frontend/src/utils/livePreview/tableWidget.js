import { WidgetType } from '@codemirror/view';
import { renderInlineMarkdown } from './inlineCells';

/** The cells of one table line: split on unescaped `|`, `\|` → `|`, optional outer pipes dropped, cells trimmed. */
function cellsOf(line) {
  const text = line.trim();
  const cells = [];
  let cell = '';
  let lastWasPipe = false;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    lastWasPipe = false;
    if (ch === '\\' && i + 1 < text.length) {
      const next = text[i + 1];
      cell += next === '|' ? '|' : ch + next; // other escapes stay for the inline renderer
      i += 1;
    } else if (ch === '|') {
      cells.push(cell);
      cell = '';
      lastWasPipe = true;
    } else {
      cell += ch;
    }
  }
  if (!lastWasPipe) cells.push(cell); // a trailing unescaped pipe closes the row instead of opening a cell
  if (text.startsWith('|')) cells.shift();
  return cells.map((c) => c.trim());
}

const alignOf = (cell) => {
  const left = cell.startsWith(':');
  const right = cell.endsWith(':') && cell.length > 1;
  if (left && right) return 'center';
  if (right) return 'right';
  return left ? 'left' : null;
};

const fit = (cells, width, fill) => Array.from({ length: width }, (_, i) => (i < cells.length ? cells[i] : fill));

/**
 * A GFM table's source (whole lines: header, delimiter, then body rows) as cell text. Short rows are padded with
 * '', long rows truncated to the header width; the alignment comes from the delimiter row (line 2).
 */
export function parseTableSource(source) {
  const lines = source.split('\n');
  const header = cellsOf(lines[0] ?? '');
  const width = header.length;
  return {
    align: fit(cellsOf(lines[1] ?? '').map(alignOf), width, null),
    header,
    rows: lines.slice(2).map((l) => fit(cellsOf(l), width, '')),
  };
}

function cell(tag, text, align) {
  const node = document.createElement(tag);
  if (align) node.style.textAlign = align;
  node.appendChild(renderInlineMarkdown(text));
  return node;
}

/**
 * The 0-based source line (within the table) of the row an event hit: the header row → 0, body row k (1-based) → k + 1
 * (the delimiter row is line 1). Anything outside a row → 0, the table start.
 */
function sourceLineOf(target, wrap) {
  const row = target?.closest?.('tr');
  if (!row || !wrap.contains(row)) return 0;
  if (row.parentElement?.tagName === 'THEAD') return 0;
  return [...row.parentElement.children].indexOf(row) + 2;
}

function buildTable(source) {
  const { align, header, rows } = parseTableSource(source);
  const table = document.createElement('table');
  table.className = 'cm-lp-table';
  const thead = document.createElement('thead');
  const headRow = document.createElement('tr');
  header.forEach((text, i) => headRow.appendChild(cell('th', text, align[i])));
  thead.appendChild(headRow);
  const tbody = document.createElement('tbody');
  for (const r of rows) {
    const tr = document.createElement('tr');
    r.forEach((text, i) => tr.appendChild(cell('td', text, align[i])));
    tbody.appendChild(tr);
  }
  table.append(thead, tbody);
  return table;
}

// Built tables by source, least recently used first: every cell is a remark parse, so a large table costs real time
// to build, and CodeMirror redraws a widget whenever it scrolls back into view or its line is re-decorated.
const TABLE_CACHE_SIZE = 20;
const tableCache = new Map();

/** A fresh copy of the table for `source` (the cached original never enters the document). */
function tableFor(source) {
  let table = tableCache.get(source);
  if (table) {
    tableCache.delete(source);
  } else {
    table = buildTable(source);
    if (tableCache.size >= TABLE_CACHE_SIZE) tableCache.delete(tableCache.keys().next().value);
  }
  tableCache.set(source, table);
  return table.cloneNode(true);
}

/** Whether a mousedown on the wrapper itself landed on its own scrollbar (outside the client area). */
function onOwnScrollbar(e, wrap) {
  if (e.target !== wrap) return false;
  const horizontal = wrap.offsetHeight > wrap.clientHeight && e.offsetY >= wrap.clientHeight;
  const vertical = wrap.offsetWidth > wrap.clientWidth && e.offsetX >= wrap.clientWidth;
  return horizontal || vertical;
}

/** A top-level GFM table, drawn as an HTML table; a mousedown on a row puts the caret on that row's source line. */
export class TableWidget extends WidgetType {
  constructor(source) { super(); this.source = source; }
  eq(o) { return o instanceof TableWidget && o.source === this.source; }
  get estimatedHeight() { return this.source.split('\n').length * 30; } // ≈ one row per source line (the delimiter's share pads the borders)
  toDOM(view) {
    const wrap = document.createElement('div');
    wrap.className = 'cm-lp-table-block';
    wrap.appendChild(tableFor(this.source));
    if (view) {
      wrap.addEventListener('mousedown', (e) => {
        if (onOwnScrollbar(e, wrap)) return; // a wide table's scrollbar: let the browser drag it
        e.preventDefault();
        try {
          const start = view.posAtDOM(wrap);
          const doc = view.state.doc;
          const first = doc.lineAt(start).number;
          const line = doc.line(Math.min(doc.lines, first + sourceLineOf(e.target, wrap)));
          view.dispatch({ selection: { anchor: line.from } });
          view.focus();
        } catch (err) {
          console.warn('[live-preview] could not place the caret in a table', err?.message || err);
        }
      });
    }
    return wrap;
  }
  ignoreEvent() { return true; } // the widget places the caret itself
}
