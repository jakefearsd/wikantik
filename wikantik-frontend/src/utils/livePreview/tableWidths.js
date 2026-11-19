/**
 * Column widths of the table grid (tableGrid.js): what a cell's text measures, how a table's columns are fitted into
 * the editor, and what of the view those depend on.
 *
 * A column's natural width is the widest of its cells over ALL rows, off-screen ones included, or the column would
 * change width as rows scroll in. So a width has to be known for text that is not drawn: an estimate from a canvas,
 * cached by `font|source text`, which a measurement of the drawn cell may only ever raise — lowering one moved a
 * column while its table was being scrolled.
 */

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

/**
 * Cell text widths by `font|text`: `widthOf(font, text)` is the estimate (`estimate(font, drawn text)`, e.g. a canvas
 * measurement) or what `raise(font, text, width)` recorded for the drawn cell, whichever is larger. `generation`
 * changes whenever a width does.
 *
 * Bounded without ever lowering a width in use. The estimates are dropped together at `limit` — an estimate is
 * computed again to the same value. The measurements are kept by recency of use (every `widthOf` that reads one makes
 * it the newest) and the oldest go first, so the ones a drawn table reads on each build outlive the ones nobody reads.
 */
export function createWidthCache(estimate, limit = WIDTH_CACHE_LIMIT) {
  const estimated = new Map();
  const measured = new Map();
  const cache = {
    generation: 0,
    widthOf(font, text) {
      const key = `${font}|${text}`;
      let width = estimated.get(key);
      if (width === undefined) {
        if (estimated.size >= limit) estimated.clear();
        width = Math.ceil(estimate(font, drawnText(text)));
        estimated.set(key, width);
      }
      const real = measured.get(key);
      if (real === undefined) return width;
      measured.delete(key);
      measured.set(key, real);
      return real > width ? real : width;
    },
    raise(font, text, width) {
      const key = `${font}|${text}`;
      if (width <= (measured.get(key) ?? 0)) return;
      measured.delete(key);
      measured.set(key, width);
      if (measured.size > limit) measured.delete(measured.keys().next().value);
      cache.generation += 1;
    },
  };
  return cache;
}

let canvas = null;
/** The widths of every editor on the page: the keys carry the font. */
export const widths = createWidthCache((font, text) => {
  canvas.font = font;
  return canvas.measureText(text).width;
});

/**
 * What the widths depend on in the view, or null while there is no layout (a hidden editor, a test DOM). The room for a
 * row is the scroller's, never the content's: a row that does not wrap widens `.cm-content`, which would feed back.
 */
export function readGeometry(view) {
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
export function naturalWidthOf(el) {
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

const tableStyles = new WeakMap(); // table model → { key, style }
/**
 * The row style of `table` in a view of `geometry`: its column widths as custom properties (`--lp-col-0:97px;…`), one
 * string for all its rows; '' without a geometry. Computed once per (table model, geometry, width generation).
 */
export function tableStyle(table, geometry) {
  if (!geometry) return '';
  const key = `${geometry.key}|${widths.generation}`;
  const hit = tableStyles.get(table);
  if (hit && hit.key === key) return hit.style;
  const natural = new Array(table.columns).fill(0);
  const { doc } = table;
  const add = (row, font) => row.cells.forEach((c, i) => {
    if (c.to > c.from) natural[i] = Math.max(natural[i], widths.widthOf(font, doc.sliceString(c.from, c.to)));
  });
  add(table.header, geometry.header);
  for (const row of table.body) add(row, geometry.body);
  const room = geometry.room - table.columns * (geometry.pad + geometry.border) - geometry.border;
  const fitted = fitColumns(natural, room, Math.round(MIN_CH * geometry.ch), Math.round(WIDE_CH * geometry.ch));
  const style = fitted.map((w, i) => `--lp-col-${i}:${w}px`).join(';');
  tableStyles.set(table, { key, style });
  return style;
}
