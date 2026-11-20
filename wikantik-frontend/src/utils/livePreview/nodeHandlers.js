import { MARKER, styleOf } from '../remarkCallouts';
import { parseFenceInfo } from '../fences/fenceInfo';
import { rendererFor } from '../fences/registry';
import { normalizeLabel, bracketless } from '../dialect/inlineTags';
import {
  FOOTNOTE_DEF, firstAtLeast, paragraphMarker, referenceLabels, resolveImageSrc, gridTableOf, cellShownEnd,
} from './ranges';

/*
 * The live-preview tree walk's node dispatch as a table (see `livePreviewSpecs` in ranges.js).
 *
 * `createSpecEmitter` holds what a walk accumulates (`specs`, `excluded`, `markers`, `lineClasses`, `inlineMath`,
 * `wikis`) and the primitives and per-construct emitters that push into it. `visitNode` looks the Lezer node up in
 * `NODE_HANDLERS` (by name) or `PATTERN_HANDLERS` (headings, by name pattern) and returns the walk's descend flag.
 *
 * Handler contract: `(emitter, node, match?) => boolean`. `true` descends into the node's children; `false` skips
 * them. A node no handler claims descends. The order of pushes into `specs` is the order nodes are visited.
 */
const MARKER_NODE = new Set(['QuoteMark', 'ListMark']);
const CONTAINER_NODE = new Set(['Blockquote', 'BulletList', 'OrderedList', 'ListItem']);
const LINKED_URL_PARENT = new Set(['Link', 'Image', 'Autolink']);
const CLOSING_FENCE = /^(?:[ \t]*>)*[ \t]*(`{3,}|~{3,})[ \t]*$/;
const HEADING = /^ATXHeading([1-6])$/;
const SETEXT = /^SetextHeading([12])$/;

export function createSpecEmitter(state, activeLines, { from, to, context }) {
  const doc = state.doc;
  const specs = [];
  const excluded = []; // code/table/etc. ranges the line-regex pass must not touch
  const markers = []; // block markers (`^id`) ending the paragraphs in range
  const lineClasses = new Map(); // line start -> Set<cls>
  const inactive = (pos) => !activeLines.has(doc.lineAt(pos).number);
  const spaceAfter = (pos) => (doc.sliceString(pos, pos + 1) === ' ' ? 1 : 0);
  // `group` ties a construct's specs together (e.g. an emphasis mark and its two hidden markers) so Task 3 can
  // drop a construct as a whole when it cuts through a wikilink/math range.
  // `{ sticky: true }`: emitted whether or not the line is active and flagged, so it stays hidden under the caret
  // (the caret rules that make that usable are in sticky.js). Non-sticky specs carry no flag.
  // `{ lineMarker: true }` (with sticky): a block marker — the caret rests after it and Backspace there removes it.
  // `{ hiddenLine: 'open' | 'close' }` (with sticky): the range is all its line shows (a fence's opening or closing
  // line), so the caret never rests on that line at all; the block's body is below an 'open' line and above a 'close'
  // one. Never together with lineMarker: Backspace must not remove a fence.
  const flags = ({ sticky = false, lineMarker = false, hiddenLine = null, chip: isAChip = false } = {}) => (sticky
    ? { sticky: true, ...(lineMarker && { lineMarker: true }), ...(hiddenLine && { hiddenLine }), ...(isAChip && { chip: true }) } : null);
  // `{ drawn: true }`: emitted on an active line too, but not flagged: a part of a chip, whose one sticky range is the
  // mark over the whole link (so the caret rule and the arrow keys see one unit, not its parts).
  const hide = (a, b, group, opts) => {
    if (b > a && (opts?.sticky || opts?.drawn || inactive(a))) specs.push({ kind: 'hide', from: a, to: b, group, ...flags(opts) });
  };
  const mark = (a, b, cls, group, opts) => {
    if (b > a) specs.push({ kind: 'mark', from: a, to: b, cls, group, ...flags(opts) });
  };
  const widget = (a, b, w, opts) => {
    if (b > a && (opts?.sticky || opts?.drawn || inactive(a))) specs.push({ kind: 'widget', from: a, to: b, widget: w, ...flags(opts) });
  };
  const DRAWN = { drawn: true };
  // `chip`: the range is a link's one unit — Backspace after it (Delete before it) selects it first (sticky.js).
  const STICKY = { sticky: true, chip: true };
  /**
   * Whether the link, wikilink, embed or image at [a, b] is a chip: drawn also with the caret on its line, one atomic
   * unit that its card edits. Not while a caret or an end of a selection is strictly inside it — it is then being
   * typed, or a find match ends in its hidden text, and it shows its source as before (a selection that covers it
   * leaves it a chip) — and not across a line break, which a chip cannot hide.
   */
  const within = (pos, a, b) => pos > a && pos < b;
  const isChip = (a, b) => b <= doc.lineAt(a).to && !state.selection.ranges.some((r) => within(r.from, a, b) || within(r.to, a, b));
  /** The one sticky range of a chip. Its parts (hidden brackets, the shown text) are `drawn`, not sticky. */
  const chip = (a, b, group) => mark(a, b, 'cm-lp-linkchip', group, STICKY);
  // Whether a quote or list marker stays hidden under the caret. Not where the block it opens on its line is in the
  // "expensive" set, which is edited as source: a footnote definition, or a table inside the container.
  const stickyMarker = (m) => {
    const line = doc.lineAt(m.from);
    if (line.text.includes('[^') && FOOTNOTE_DEF.test(line.text)) return false;
    let next = m.nextSibling;
    while (next && next.from <= line.to) {
      if (next.name === 'Table') return false;
      if (MARKER_NODE.has(next.name)) next = next.nextSibling;
      else if (CONTAINER_NODE.has(next.name)) next = next.firstChild;
      else break;
    }
    return true;
  };
  const addLine = (lineStart, cls) => {
    if (!lineClasses.has(lineStart)) lineClasses.set(lineStart, new Set());
    lineClasses.get(lineStart).add(cls);
  };
  const eachLine = (a, b, fn) => {
    const last = doc.lineAt(Math.min(b, to)).number;
    for (let n = doc.lineAt(Math.max(a, from)).number; n <= last; n += 1) fn(doc.line(n));
  };
  const markedInline = (node, cls, markName) => {
    const g = `${node.name}@${node.from}`;
    mark(node.from, node.to, cls, g);
    for (let c = node.node.firstChild; c; c = c.nextSibling) if (c.name === markName) hide(c.from, c.to, g);
  };
  const heading = (node, level) => {
    addLine(doc.lineAt(node.from).from, `cm-lp-h${level}`);
    const m = node.node.firstChild;
    if (m && m.name === 'HeaderMark') hide(m.from, m.to + spaceAfter(m.to), undefined, { sticky: true, lineMarker: true });
  };
  const setextHeading = (node, level) => {
    const mk = node.node.getChild('HeaderMark');
    const underline = mk ? doc.lineAt(mk.from).number : -1;
    eachLine(node.from, node.to, (line) => addLine(line.from, line.number === underline ? 'cm-lp-setext-underline' : `cm-lp-h${level}`));
  };
  const autolink = (node) => {
    const n = node.node;
    const url = n.getChild('URL');
    const g = `Autolink@${n.from}`;
    if (url) mark(url.from, url.to, 'cm-lp-link', g);
    for (const m of n.getChildren('LinkMark')) hide(m.from, m.to, g);
  };
  const link = (node) => {
    const n = node.node;
    const marks = n.getChildren('LinkMark');
    if (marks.length < 2 || doc.sliceString(marks[0].from, marks[0].to) !== '[') return;
    if (!n.getChild('URL')) {
      // A reference link: only when the document defines its label. `[^…]` is a footnote (the line pass), never a link.
      const text = doc.sliceString(marks[0].to, marks[1].from);
      const l = n.getChild('LinkLabel');
      if (text.startsWith('^')) return; // footnote
      if (!l && text.startsWith('!')) return; // a shortcut `[!type]` is a callout marker; `[!x][r]` is a real reference
      const given = l ? bracketless(doc.sliceString(l.from, l.to)) : '';
      if (!referenceLabels(state).has(normalizeLabel(given || text))) return;
    }
    const g = `Link@${n.from}`;
    // A chip only for the inline form with text to show: a reference-style link is in the "expensive" set, and a link
    // without text would be a chip nobody can see.
    const opts = n.getChild('URL') && marks[1].from > marks[0].to && isChip(n.from, n.to) ? DRAWN : undefined;
    if (opts) chip(n.from, n.to, g);
    mark(marks[0].to, marks[1].from, 'cm-lp-link', g);
    hide(marks[0].from, marks[0].to, g, opts);
    hide(marks[1].from, n.to, g, opts);
  };
  const image = (node) => {
    const n = node.node;
    const url = n.getChild('URL');
    const marks = n.getChildren('LinkMark');
    if (!url || marks.length < 2) return;
    const src = resolveImageSrc(doc.sliceString(url.from, url.to), context);
    if (src) widget(n.from, n.to, { type: 'image', src, alt: doc.sliceString(marks[0].to, marks[1].from) }, isChip(n.from, n.to) ? STICKY : undefined);
  };
  const blockquote = (node) => {
    const n = node.node;
    let child = n.firstChild;
    while (child && child.name === 'QuoteMark') child = child.nextSibling;
    const m = child && child.name === 'Paragraph'
      ? MARKER.exec(doc.sliceString(child.from, Math.min(child.to, child.from + 80))) : null;
    const firstLine = doc.lineAt(n.from).number;
    eachLine(n.from, n.to, (line) => {
      if (!m) { addLine(line.from, 'cm-lp-quote'); return; }
      addLine(line.from, 'cm-lp-callout');
      addLine(line.from, `cm-lp-callout-${styleOf(m[1])}`);
      if (line.number === firstLine) addLine(line.from, 'cm-lp-callout-title');
    });
    // The marker (with its fold sign and the blanks after it) is a line marker like the `> ` before it: drawn as the
    // type picker also under the caret, which rests after it, where the title text starts.
    if (m) widget(child.from, child.from + m[0].length, { type: 'callout-title', style: styleOf(m[1]), rawType: m[1] }, { sticky: true, lineMarker: true });
  };
  const listItem = (node) => {
    const n = node.node;
    const listMark = n.getChild('ListMark');
    if (!listMark) return;
    const marker = n.getChild('Task')?.getChild('TaskMarker');
    // The marker's one trailing space belongs to it: the range then ends where the item's text starts, which is where
    // the caret rests and where Backspace removes the marker as a whole (sticky.js).
    const sticky = stickyMarker(listMark);
    const opts = { sticky, lineMarker: true };
    if (marker) {
      const ch = doc.sliceString(marker.from + 1, marker.from + 2);
      widget(listMark.from, marker.to + spaceAfter(marker.to), { type: 'checkbox', checked: ch === 'x' || ch === 'X', markerFrom: marker.from, text: doc.sliceString(marker.to, doc.lineAt(marker.to).to).trim().slice(0, 40) }, opts);
    } else if (n.parent?.name === 'BulletList') {
      widget(listMark.from, listMark.to + spaceAfter(listMark.to), { type: 'bullet' }, opts);
    } else if (sticky) {
      // A number is shown as it is written (it carries the item's value); the mark only makes it one atomic unit.
      mark(listMark.from, listMark.to + spaceAfter(listMark.to), 'cm-lp-olmark', undefined, opts);
    }
  };
  /**
   * A fence that gets a header: closed, with at least one body line between its fences (the caret needs a line to
   * rest on — an unclosed or empty fence stays source, so it can be typed). `{ openLine, closeLine, language, kind }`,
   * or null. Reads the node's first and last children only: a fence in a quote has one QuoteMark child per line.
   */
  const headedFence = (node) => {
    const open = node.node.firstChild;
    const close = node.node.lastChild;
    if (!open || open.name !== 'CodeMark' || !close || close.name !== 'CodeMark' || close.from === open.from) return null;
    const openLine = doc.lineAt(open.from);
    const closeLine = doc.lineAt(close.from);
    if (closeLine.number - openLine.number < 2) return null;
    const info = open.nextSibling?.name === 'CodeInfo' ? doc.sliceString(open.nextSibling.from, open.nextSibling.to) : '';
    const fenceInfo = parseFenceInfo(info);
    const drawable = !!rendererFor(fenceInfo);
    // A top-level fence a renderer draws is a block widget (blockSpecs) while none of its lines is active.
    if (drawable && node.node.parent?.name === 'Document') {
      let active = false;
      for (const n of activeLines) if (n >= openLine.number && n <= closeLine.number) { active = true; break; }
      if (!active) return null;
    }
    return { openLine, closeLine, closeFrom: close.from, language: fenceInfo.language, kind: drawable ? 'diagram' : 'code' };
  };
  const fenced = (node) => {
    const first = doc.lineAt(node.from).number;
    const last = doc.lineAt(node.to).number;
    const closed = last > first && CLOSING_FENCE.test(doc.line(last).text);
    const headed = headedFence(node);
    const lo = Math.max(first, doc.lineAt(Math.max(from, node.from)).number);
    const hi = Math.min(last, doc.lineAt(Math.min(to, node.to)).number);
    for (let n = lo; n <= hi; n += 1) {
      const line = doc.line(n);
      if (line.to < from || line.from > to) continue;
      if (headed && n === last) addLine(line.from, 'cm-lp-fence-close');
      else addLine(line.from, n === first || (closed && n === last) ? 'cm-lp-fence' : 'cm-lp-codeblock');
    }
    if (!headed) return;
    const { openLine, closeLine } = headed;
    // The header stands for the opening line's text from the fence on (a quote or list marker before it keeps its own
    // spec); the closing line is hidden whole, with the `> ` and indentation before its fence.
    if (openLine.to >= from && openLine.from <= to) widget(node.from, openLine.to, { type: 'fence-header', language: headed.language, kind: headed.kind }, { sticky: true, hiddenLine: 'open' });
    if (closeLine.to >= from && closeLine.from <= to) hide(CLOSING_FENCE.test(closeLine.text) ? closeLine.from : headed.closeFrom, closeLine.to, undefined, { sticky: true, hiddenLine: 'close' });
  };
  /**
   * A table the grid draws (tableGrid.js draws the cells' boxes; this hides what is not cell text). Per row in range:
   * the opening pipe, and after each cell its trailing padding, its closing pipe and the next cell's leading padding,
   * as one sticky range each — an empty cell's is the widget that draws its box. The delimiter row is a hidden line.
   * No range is a line marker: Backspace never removes a pipe.
   */
  const gridTable = (table) => {
    const inRange = (row) => row.to >= from && row.from <= to;
    const HIDDEN = { sticky: true };
    const gridRow = (row) => {
      const line = doc.lineAt(row.from);
      const last = row.cells.length - 1;
      row.cells.forEach((c, i) => {
        if (i === 0) hide(line.from, c.from, undefined, HIDDEN);
        const end = i < last ? row.cells[i + 1].from : line.to;
        const shown = cellShownEnd(c, state.selection);
        if (shown === c.from) widget(c.from, end, { type: 'table-cell', col: i, last: i === last, align: table.aligns[i] || null }, HIDDEN);
        else hide(shown, end, undefined, HIDDEN);
      });
    };
    if (inRange(table.header)) gridRow(table.header);
    if (inRange(table.delimiter)) {
      const line = doc.lineAt(table.delimiter.from);
      addLine(line.from, 'cm-lp-table-delim');
      hide(line.from, line.to, undefined, { sticky: true, hiddenLine: 'close' });
    }
    for (let i = firstAtLeast(table.body, from, 'to'); i < table.body.length && table.body[i].from <= to; i += 1) gridRow(table.body[i]);
  };
  const inlineMath = []; // `$…$` nodes in document order; typeset in the line pass, after wikilinks and plugins claim theirs
  const wikis = []; // `[[…]]` / `![[…]]` nodes in document order; the line pass claims their ranges before plugins do
  return {
    state, doc, specs, excluded, markers, lineClasses, inlineMath, wikis,
    inactive, hide, mark, widget, DRAWN, STICKY, isChip, chip, eachLine, addLine, spaceAfter,
    markedInline, heading, setextHeading, autolink, link, image, blockquote, listItem, fenced, gridTable, stickyMarker,
  };
}

/** A range the line-regex pass must not touch, and whose children the walk skips. */
const skipRange = (e, node) => { e.excluded.push([node.from, node.to]); return false; };
const descend = () => true;

export const NODE_HANDLERS = new Map([
  ['Frontmatter', skipRange],
  ['CommentBlock', skipRange],
  ['ProcessingInstructionBlock', skipRange],
  ['CodeBlock', skipRange],
  ['HTMLBlock', (e, node) => {
    e.eachLine(node.from, node.to, (line) => e.addLine(line.from, 'cm-lp-html'));
    return skipRange(e, node);
  }],
  ['LinkReference', (e, node) => {
    const l = node.node.getChild('LinkLabel');
    if (l && e.doc.sliceString(l.from + 1, l.from + 2) === '^') {
      // A footnote definition: only the text after `[^id]:` is excluded, so the line pass can chip the label.
      const colon = node.node.getChild('LinkMark');
      e.excluded.push([colon ? colon.to : l.to, node.to]);
      return false;
    }
    e.eachLine(node.from, node.to, (line) => e.addLine(line.from, 'cm-lp-linkdef'));
    return skipRange(e, node);
  }],
  ['Table', (e, node) => {
    // A grid table's cells are text like a paragraph's: the walk goes on into them. Any other table stays source.
    const table = gridTableOf(e.state, node.node);
    if (table) { e.gridTable(table); return true; }
    return skipRange(e, node);
  }],
  ['FencedCode', (e, node) => { e.fenced(node); return skipRange(e, node); }],
  ['InlineCode', (e, node) => { e.markedInline(node, 'cm-lp-code', 'CodeMark'); return skipRange(e, node); }],
  ['DisplayMath', skipRange],
  ['InlineMath', (e, node) => { e.inlineMath.push([node.from, node.to]); return false; }],
  ['WikiLink', (e, node) => { e.wikis.push([node.from, node.to]); return false; }],
  ['WikiEmbed', (e, node) => { e.wikis.push([node.from, node.to]); return false; }],
  ['Paragraph', (e, node) => { const m = paragraphMarker(e.doc, node.node); if (m) e.markers.push(m); return true; }],
  ['Task', (e, node) => { const m = paragraphMarker(e.doc, node.node); if (m) e.markers.push(m); return true; }],
  ['Image', (e, node) => { e.image(node); return false; }],
  ['Autolink', (e, node) => { e.autolink(node); return true; }],
  ['URL', (e, node) => {
    if (!LINKED_URL_PARENT.has(node.node.parent?.name)) e.mark(node.from, node.to, 'cm-lp-link');
    return true;
  }],
  ['Escape', (e, node) => { e.hide(node.from, node.from + 1, `Escape@${node.from}`); return true; }],
  ['Emphasis', (e, node) => { e.markedInline(node, 'cm-lp-em', 'EmphasisMark'); return true; }],
  ['StrongEmphasis', (e, node) => { e.markedInline(node, 'cm-lp-strong', 'EmphasisMark'); return true; }],
  ['Strikethrough', (e, node) => { e.markedInline(node, 'cm-lp-del', 'StrikethroughMark'); return true; }],
  ['Link', (e, node) => { e.link(node); return true; }],
  ['Blockquote', (e, node) => { e.blockquote(node); return true; }],
  ['QuoteMark', (e, node) => {
    e.hide(node.from, node.to + e.spaceAfter(node.to), undefined, { sticky: e.stickyMarker(node.node), lineMarker: true });
    return true;
  }],
  ['HorizontalRule', (e, node) => { e.widget(node.from, node.to, { type: 'rule' }); return true; }],
  ['ListItem', (e, node) => { e.listItem(node); return true; }],
]);

/** Handlers keyed by a pattern over the node name; the first capture group is the level. */
export const PATTERN_HANDLERS = [
  [HEADING, (e, node, m) => { e.heading(node, m[1]); return true; }],
  [SETEXT, (e, node, m) => { e.setextHeading(node, m[1]); return true; }],
];

/** Dispatch one syntax-tree node: whether the walk descends into its children. */
export function visitNode(e, node) {
  const handler = NODE_HANDLERS.get(node.name);
  if (handler) return handler(e, node);
  for (const [pattern, patternHandler] of PATTERN_HANDLERS) {
    const m = pattern.exec(node.name);
    if (m) return patternHandler(e, node, m);
  }
  return descend();
}
