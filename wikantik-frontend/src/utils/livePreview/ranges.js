import { syntaxTree } from '@codemirror/language';
import { MARKER, styleOf, defaultTitle } from '../remarkCallouts';
import { matchInlineMath } from '../inlineMath';
import { parseFenceInfo } from '../fences/fenceInfo';
import { rendererFor } from '../fences/registry';
import { findWikiLinks, parseWikiLink, isImageFileName } from '../wikiLinkSyntax';
import { chipFor } from './chips';
import { BLOCK_ID_RE, STANDALONE_RE, RULE_TEXT, MARKER_WITH_SPACE } from '../blockIds';
/** Nodes whose whole range stays source and is never scanned by the line regexes (Task 3). */
const SKIP = new Set(['Frontmatter', 'Table', 'HTMLBlock', 'CommentBlock', 'ProcessingInstructionBlock', 'CodeBlock', 'LinkReference']);
const HEADING = /^ATXHeading([1-6])$/;
const SETEXT = /^SetextHeading([12])$/;
const FOOTNOTE_DEF = /^( {0,3})\[\^([^\]\s]+)\]:/;
const FOOTNOTE_REF = /\[\^([^\]\s]+)\]/g;
const LINKED_URL_PARENT = new Set(['Link', 'Image', 'Autolink']);
const BLOCK_MATH = /^\$\$[ \t]*\n[\s\S]*\n[ \t]*\$\$[ \t]*$/;
const CLOSING_FENCE = /^(?:[ \t]*>)*[ \t]*(`{3,}|~{3,})[ \t]*$/;
const PLUGIN = /\[\{[^\n]*?\}\](?:\(\))?/g;
const SCHEME = /^[a-z][a-z0-9+.-]*:/i;
export function activeLinesOf(state) {
  const lines = new Set();
  for (const r of state.selection.ranges) {
    const last = state.doc.lineAt(r.to).number;
    for (let n = state.doc.lineAt(r.from).number; n <= last; n += 1) lines.add(n);
  }
  return lines;
}
/** A paragraph that is display math: `$$` alone on its first and last line. */
export function isBlockMathText(text) { return BLOCK_MATH.test(text); }
const encodePath = (p) => String(p).split('/').map(encodeURIComponent).join('/');
/** `/attach/<owner>/<file>` with every path segment percent-encoded (as remarkWikiLinks does). */
export function attachmentUrl(owner, file) { return `/attach/${encodePath(owner)}/${encodePath(file)}`; }
/** The image URL the preview would use (remarkAttachments rules); null for a non-http scheme. */
export function resolveImageSrc(url, { pageName, attachments = [] } = {}) {
  if (/^https?:\/\//i.test(url) || url.startsWith('/')) return url;
  if (SCHEME.test(url)) return null;
  const hit = attachments.find((a) => a.toLowerCase() === url.toLowerCase());
  return hit && pageName ? attachmentUrl(pageName, hit) : url;
}
/** `T`, `T#H`, `T|A`, `T#H|A`, `#H` (the inside of `[[ ]]`) via the shared wikilink parser; null when not a wikilink. */
export function parseWikiTarget(inner) {
  const ref = parseWikiLink(`[[${inner}]]`);
  return ref ? { target: ref.target, heading: ref.heading, alias: ref.alias } : null;
}
const isAttachmentRef = (ref, { attachments = [] } = {}) =>
  ref.isAttachment || (!ref.isSamePage && attachments.some((a) => a.toLowerCase() === ref.target.toLowerCase()));
/** The page embed `{ target, heading }` when `text` is exactly one `![[Page]]` / `![[Page#H]]`; else null. */
export function pageEmbedLine(text, context = {}) {
  const ref = parseWikiLink(text.trim());
  if (!ref || !ref.embed || ref.isSamePage || isAttachmentRef(ref, context)) return null;
  return { target: ref.target, heading: ref.heading };
}
/** A top-level single-line paragraph holding only a page embed (the only place an embed becomes a block). */
function blockEmbedAt(state, pos, context) {
  for (let n = syntaxTree(state).resolveInner(pos, 1); n; n = n.parent) {
    if (n.name !== 'Paragraph') continue;
    return n.parent?.name === 'Document' && state.doc.lineAt(n.from).number === state.doc.lineAt(n.to).number
      && pageEmbedLine(state.doc.sliceString(n.from, n.to), context) !== null;
  }
  return false;
}
/**
 * A top-level, closed fenced code block whose info string names a registered, non-`example` renderer, as a
 * fence widget candidate (null otherwise). The source is the lines between the fences with the opening fence's
 * indentation removed, each line newline-terminated: the same text the server puts in the envelope.
 */
function fenceCandidateOf(doc, node) {
  const marks = [];
  let info = '';
  for (let c = node.firstChild; c; c = c.nextSibling) {
    if (c.name === 'CodeMark') marks.push(c);
    else if (c.name === 'CodeInfo') info = doc.sliceString(c.from, c.to);
  }
  if (marks.length !== 2) return null; // unclosed: stays source
  const fenceInfo = parseFenceInfo(info);
  if (!rendererFor(fenceInfo)) return null;
  const open = doc.lineAt(node.from);
  const close = doc.lineAt(marks[1].from);
  if (close.number <= open.number) return null;
  const indent = /^ */.exec(open.text)[0].length;
  let source = '';
  for (let n = open.number + 1; n < close.number; n += 1) {
    const text = doc.line(n).text;
    let strip = 0;
    while (strip < indent && text.charCodeAt(strip) === 32) strip += 1;
    source += `${text.slice(strip)}\n`;
  }
  const end = doc.lineAt(node.to);
  return { kind: 'block', from: open.from, to: end.to, widget: { type: 'fence', language: fenceInfo.language, source } };
}
/**
 * The block widget a top-level node becomes (display math, whole-line page embed, a rendered fence), or null.
 * Only TOP-LEVEL nodes qualify (we walk the document's direct children), so a fence nested in a list or
 * blockquote stays code in live preview, the same rule display math follows.
 */
function blockCandidateOf(doc, node, context) {
  if (node.name === 'FencedCode') return fenceCandidateOf(doc, node);
  if (node.name !== 'Paragraph') return null;
  const first = doc.sliceString(node.from, Math.min(node.to, node.from + 3));
  if (!first.startsWith('$$') && !first.startsWith('![')) return null; // cheap prefix test before slicing the paragraph
  const startLine = doc.lineAt(node.from);
  const endLine = node.to <= startLine.to ? startLine : doc.lineAt(node.to);
  const text = doc.sliceString(node.from, node.to);
  if (isBlockMathText(text)) {
    const tex = text.replace(/^\$\$[ \t]*\n/, '').replace(/\n[ \t]*\$\$[ \t]*$/, '');
    return { kind: 'block', from: startLine.from, to: endLine.to, widget: { type: 'math', tex, display: true } };
  }
  if (startLine !== endLine) return null;
  const embed = pageEmbedLine(text, context);
  return embed ? { kind: 'block', from: startLine.from, to: endLine.to, widget: { type: 'embed', target: embed.target, section: embed.heading } } : null;
}
/**
 * Every block-widget candidate (display math, whole-line page embed, rendered fence) regardless of the caret: top-level
 * paragraphs only, each covering whole lines, in document order (so both `from` and `to` ascend).
 */
export function blockCandidates(state, context = {}) {
  const out = [];
  for (let node = syntaxTree(state).topNode.firstChild; node; node = node.nextSibling) {
    const c = blockCandidateOf(state.doc, node, context);
    if (c) out.push(c);
  }
  return out;
}
/** The top-level node (a child of the document node) at `pos` in `tree` that starts exactly there, or null. */
function topLevelStartingAt(tree, pos) {
  let n = tree.resolve(pos, 1);
  while (n.parent && n.parent.parent) n = n.parent;
  return n.parent && n.from === pos ? n : null;
}
/**
 * `blockCandidates` for `tr.state`, given `prev` = the candidates of `tr.startState`: only the top-level
 * nodes from the one before the edit up to the first node past it that the old tree also had (same type,
 * same span, mapped) are re-examined; candidates before keep their positions and those after are mapped
 * through the changes. The markdown block parser restarts at every top-level block, so once a node past the
 * edit matches the old tree, every later node does too. Equal to a full rescan by construction (tested); a
 * partial old tree falls back to the full rescan.
 */
export function updateBlockCandidates(prev, tr, context = {}) {
  const { changes } = tr;
  const doc = tr.state.doc;
  let fromB = Infinity;
  let toB = -1;
  changes.iterChangedRanges((_fa, _ta, fb, tb) => { fromB = Math.min(fromB, fb); toB = Math.max(toB, tb); });
  if (toB < 0) return prev;
  const tree = syntaxTree(tr.state);
  const oldTree = syntaxTree(tr.startState);
  // A partial old parse (large page, parser still catching up) left `prev` without the blocks past its end;
  // the tail cannot be mapped from it.
  if (oldTree.length < tr.startState.doc.length) return blockCandidates(tr.state, context);
  const top = tree.topNode;
  let node = top.childBefore(fromB) || top.firstChild;
  if (!node) return [];
  if (node.prevSibling) node = node.prevSibling; // a change can re-shape the block just before it (setext, merges)
  const start = node.from;
  const out = prev.filter((c) => c.to < start); // untouched: wholly before the first change
  const inverted = changes.invertedDesc;
  for (; node; node = node.nextSibling) {
    // Past the edit including the node's line prefix (indentation), so the mapped candidate spans are exact.
    if (doc.lineAt(node.from).from > toB) {
      const oldFrom = inverted.mapPos(node.from);
      const old = topLevelStartingAt(oldTree, oldFrom);
      if (old && old.name === node.name && old.to - old.from === node.to - node.from) {
        for (const c of prev) {
          // Block nodes span whole lines, so a candidate at or after the matched node ends at or after its
          // start (its own `from` may sit before it: a paragraph can be indented).
          if (c.to < oldFrom) continue;
          const to = changes.mapPos(c.to);
          // Past a partial new parse's end a full scan sees no blocks yet; neither does this.
          if (to <= tree.length) out.push({ ...c, from: changes.mapPos(c.from), to });
        }
        return out;
      }
    }
    const c = blockCandidateOf(doc, node, context);
    if (c) out.push(c);
  }
  return out;
}
const LINE_PREFIX = /^[ \t>]*/;   // a continuation line's indentation and block-quote marks
const NOT_A_HOLDER = /^(?:ATXHeading\d|SetextHeading\d|FencedCode)$/;
/** Lezer's text blocks: a paragraph, or a task item's text (GFM gives a task item a Task node, not a Paragraph). */
const TEXT_BLOCK = new Set(['Paragraph', 'Task']);
/**
 * The `^id` block marker ending a text block (a Paragraph or Task node), as `{ id, from }` (`from` = the caret), or null. The same marker
 * forms blockIds.js reads — inline (whitespace + `^id` ending the last line), last-line (`^id` alone on the last line)
 * and standalone (a paragraph that is only `^id`), with its regexes — but over the Lezer block structure, which costs a
 * line slice instead of a remark parse. Code, tables, HTML and headings are never Paragraph nodes; display math and a
 * callout's title line are ruled out here. (The inline tree is not consulted, so a `^x^` superscript cannot mis-tint.)
 * Visuals only: the chips this tints decide nothing — block identity is the server's (POST /blocks/draft).
 */
function paragraphMarker(doc, node) {
  if (doc.sliceString(node.from, node.from + 2) === '$$' && isBlockMathText(doc.sliceString(node.from, node.to))) return null;
  const text = doc.sliceString(node.from, node.to);
  if (node.parent?.name === 'Document' && strippedToCode(doc, node.from, text)) return null;
  const beside = besideRulesMarker(text);
  if (beside) return { id: beside.id, from: node.from + beside.at };
  const end = node.from + keptBeforeRules(text);
  const last = doc.lineAt(end);
  const onFirstLine = last.from <= node.from;
  let start = onFirstLine ? node.from : last.from;
  let seg = doc.sliceString(start, end);
  if (!onFirstLine) {
    const prefix = LINE_PREFIX.exec(seg)[0].length;
    start += prefix;
    seg = seg.slice(prefix);
  } else if (node.parent?.name === 'Blockquote' && MARKER.test(seg)) {
    return null; // a callout's title line, which the server never reads a marker from
  }
  const alone = STANDALONE_RE.exec(seg);
  if (alone) return { id: alone[1], from: start };
  const m = BLOCK_ID_RE.exec(seg);
  return m ? { id: m[1], from: start + m.index } : null;
}
/**
 * Whether a top-level paragraph is code once its access rules are stripped: its first character that is not a rule sits
 * four columns into its line (a tab counts to the next multiple of four). blockIds.js reads it so too.
 */
function strippedToCode(doc, from, text) {
  if (!text.includes('[{')) return false;
  const first = text.replace(RULE_TEXT, (r) => ' '.repeat(r.length)).search(/[^ \t\r\n]/);
  if (first < 0) return false;
  const at = from + first;
  const prefix = doc.sliceString(doc.lineAt(at).from, at).replace(RULE_TEXT, '');
  if (!/^[ \t]*$/.test(prefix)) return false;
  let columns = 0;
  for (const ch of prefix) columns = ch === '\t' ? columns + 4 - (columns % 4) : columns + 1;
  return columns >= 4;
}
/**
 * How much of a paragraph's text is left at its end once trailing access rules (on the last line, or on lines of their
 * own) and the blanks around them are taken away — the render strips the rules before it parses, so a marker before
 * them ends the paragraph. blockIds.js reads it so too.
 */
function keptBeforeRules(text) {
  if (!text.includes('[{')) return text.length;
  return text.replace(RULE_TEXT, (r) => ' '.repeat(r.length))
    .replace(/\n[ \t>]*/g, (x) => `\n${' '.repeat(x.length - 1)}`)   // continuation lines' prefixes
    .replace(/[ \t\r\n]+$/, '').length;
}
/**
 * A paragraph of access rules and one marker (`[{ALLOW view Admin}] ^id`, `^id [{DENY edit Guest}]`, either on its own
 * line): the render strips the rules first, which leaves a standalone marker — blockIds.js reads it so, and so does
 * this. `{ id, at }` with `at` the caret's offset in `text`, or null.
 */
function besideRulesMarker(text) {
  if (!text.includes('[{')) return null;
  let rule = false;
  const masked = text.replace(RULE_TEXT, (r) => { rule = true; return ' '.repeat(r.length); })
    .replace(/\n[ \t>]*/g, (x) => `\n${' '.repeat(x.length - 1)}`);   // continuation lines' prefixes
  const m = rule ? MARKER_WITH_SPACE.exec(masked) : null;
  return m ? { id: m[1], at: masked.indexOf('^') } : null;
}
const firstMarkerCache = new WeakMap(); // doc → { tree, upTo, firsts }
/**
 * Each id's first marker position (Map id → caret) among the markers up to `upTo`: enough to apply the server's
 * first-wins rule (all markers ordered by position) to any marker at or before `upTo`, since an earlier marker of the
 * same id is in a block that starts before it. Walks only the block structure from the top (a text block is never
 * descended into), once per (document, parse tree) — the walk ends at the last marker in view, so it is short while
 * the viewport is near the top — and only when a marker is in view at all.
 */
function firstMarkers(state, upTo) {
  const tree = syntaxTree(state);
  const hit = firstMarkerCache.get(state.doc);
  if (hit && hit.tree === tree && hit.upTo >= upTo) return hit.firsts;
  const firsts = new Map();
  tree.iterate({
    from: 0,
    to: upTo,
    enter: (node) => {
      if (SKIP.has(node.name) || NOT_A_HOLDER.test(node.name)) return false;
      if (!TEXT_BLOCK.has(node.name)) return undefined;
      const m = paragraphMarker(state.doc, node.node);
      if (m && !firsts.has(m.id)) firsts.set(m.id, m.from);
      return false;
    },
  });
  firstMarkerCache.set(state.doc, { tree, upTo, firsts });
  return firsts;
}
const refLabelCache = new WeakMap(); // doc → { tree, labels }
/** CommonMark label matching: whitespace collapsed, case-insensitive. */
export const normalizeLabel = (s) => s.trim().replace(/\s+/g, ' ').toLowerCase();
/** The `[label]` of a LinkReference or LinkLabel node's text, brackets off. */
const labelText = (raw) => raw.replace(/^\[/, '').replace(/\]$/, '');
/**
 * Normalised labels of every link reference definition (`[label]: url`) in the document, footnote definitions
 * (`^…`) excluded. Walks only the block structure (a text block is never descended into), once per (document,
 * parse tree).
 */
export function referenceLabels(state) {
  const tree = syntaxTree(state);
  const hit = refLabelCache.get(state.doc);
  if (hit && hit.tree === tree) return hit.labels;
  const labels = new Set();
  tree.iterate({
    enter: (node) => {
      if (node.name === 'LinkReference') {
        const l = node.node.getChild('LinkLabel');
        const raw = l ? labelText(state.doc.sliceString(l.from, l.to)) : '';
        if (raw && !raw.startsWith('^')) labels.add(normalizeLabel(raw));
        return false;
      }
      return !(SKIP.has(node.name) || NOT_A_HOLDER.test(node.name) || TEXT_BLOCK.has(node.name));
    },
  });
  refLabelCache.set(state.doc, { tree, labels });
  return labels;
}
/** The block widgets to show: every candidate none of whose lines is active. */
export function blockSpecs(state, activeLines, context = {}) {
  const doc = state.doc;
  const anyActive = (a, b) => {
    for (let n = doc.lineAt(a).number, e = doc.lineAt(b).number; n <= e; n += 1) if (activeLines.has(n)) return true;
    return false;
  };
  return blockCandidates(state, context).filter((s) => !anyActive(s.from, s.to));
}
/** Drops replacing specs that cross a line break or overlap an earlier one; sorts by from, then to. */
function finalize(doc, specs) {
  const out = [];
  let lastEnd = -1;
  for (const s of [...specs].sort((a, b) => a.from - b.from || a.to - b.to)) {
    if (s.kind === 'hide' || s.kind === 'widget') {
      if (doc.sliceString(s.from, s.to).includes('\n') || s.from < lastEnd) continue;
      lastEnd = s.to;
    }
    out.push(s);
  }
  return out;
}
export function livePreviewSpecs(state, activeLines, { from = 0, to = state.doc.length, context = {} } = {}) {
  const doc = state.doc;
  const specs = [];
  const excluded = []; // code/table/etc. ranges the line-regex pass must not touch
  const markers = []; // block markers (`^id`) ending the paragraphs in range
  const lineClasses = new Map(); // line start -> Set<cls>
  const inactive = (pos) => !activeLines.has(doc.lineAt(pos).number);
  const spaceAfter = (pos) => (doc.sliceString(pos, pos + 1) === ' ' ? 1 : 0);
  // `group` ties a construct's specs together (e.g. an emphasis mark and its two hidden markers) so Task 3 can
  // drop a construct as a whole when it cuts through a wikilink/math range.
  const hide = (a, b, group) => { if (b > a && inactive(a)) specs.push({ kind: 'hide', from: a, to: b, group }); };
  const mark = (a, b, cls, group) => { if (b > a) specs.push({ kind: 'mark', from: a, to: b, cls, group }); };
  const widget = (a, b, w) => { if (b > a && inactive(a)) specs.push({ kind: 'widget', from: a, to: b, widget: w }); };
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
    if (m && m.name === 'HeaderMark') hide(m.from, m.to + spaceAfter(m.to));
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
      if (text.startsWith('^')) return;
      const l = n.getChild('LinkLabel');
      const given = l ? labelText(doc.sliceString(l.from, l.to)) : '';
      if (!referenceLabels(state).has(normalizeLabel(given || text))) return;
    }
    const g = `Link@${n.from}`;
    mark(marks[0].to, marks[1].from, 'cm-lp-link', g);
    hide(marks[0].from, marks[0].to, g);
    hide(marks[1].from, n.to, g);
  };
  const image = (node) => {
    const n = node.node;
    const url = n.getChild('URL');
    const marks = n.getChildren('LinkMark');
    if (!url || marks.length < 2) return;
    const src = resolveImageSrc(doc.sliceString(url.from, url.to), context);
    if (src) widget(n.from, n.to, { type: 'image', src, alt: doc.sliceString(marks[0].to, marks[1].from) });
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
    if (m) {
      const markerTo = child.from + m[0].length;
      const rest = doc.sliceString(markerTo, doc.lineAt(child.from).to).trim();
      widget(child.from, markerTo, { type: 'callout-title', style: styleOf(m[1]), title: rest ? '' : defaultTitle(m[1]) });
    }
  };
  const listItem = (node) => {
    const n = node.node;
    const listMark = n.getChild('ListMark');
    if (!listMark) return;
    const marker = n.getChild('Task')?.getChild('TaskMarker');
    if (marker) {
      const ch = doc.sliceString(marker.from + 1, marker.from + 2);
      widget(listMark.from, marker.to, { type: 'checkbox', checked: ch === 'x' || ch === 'X', markerFrom: marker.from, text: doc.sliceString(marker.to, doc.lineAt(marker.to).to).trim().slice(0, 40) });
    } else if (n.parent?.name === 'BulletList') {
      widget(listMark.from, listMark.to, { type: 'bullet' });
    }
  };
  const fenced = (node) => {
    const first = doc.lineAt(node.from).number;
    const last = doc.lineAt(node.to).number;
    const closed = last > first && CLOSING_FENCE.test(doc.line(last).text);
    const lo = Math.max(first, doc.lineAt(Math.max(from, node.from)).number);
    const hi = Math.min(last, doc.lineAt(Math.min(to, node.to)).number);
    for (let n = lo; n <= hi; n += 1) {
      const line = doc.line(n);
      if (line.to < from || line.from > to) continue;
      addLine(line.from, n === first || (closed && n === last) ? 'cm-lp-fence' : 'cm-lp-codeblock');
    }
  };
  syntaxTree(state).iterate({
    from, to,
    enter: (node) => {
      const { name } = node;
      if (name === 'HTMLBlock') eachLine(node.from, node.to, (line) => addLine(line.from, 'cm-lp-html'));
      if (name === 'LinkReference') {
        const l = node.node.getChild('LinkLabel');
        if (!l || !doc.sliceString(l.from + 1, l.from + 2).startsWith('^')) eachLine(node.from, node.to, (line) => addLine(line.from, 'cm-lp-linkdef'));
      }
      if (SKIP.has(name)) { excluded.push([node.from, node.to]); return false; }
      if (name === 'FencedCode') { fenced(node); excluded.push([node.from, node.to]); return false; }
      if (name === 'InlineCode') { markedInline(node, 'cm-lp-code', 'CodeMark'); excluded.push([node.from, node.to]); return false; }
      if (name === 'Paragraph' && doc.sliceString(node.from, node.from + 2) === '$$' && isBlockMathText(doc.sliceString(node.from, node.to))) { excluded.push([node.from, node.to]); return false; }
      if (TEXT_BLOCK.has(name)) { const m = paragraphMarker(doc, node.node); if (m) markers.push(m); }
      if (name === 'Image') { image(node); return false; }
      const h = HEADING.exec(name);
      if (h) { heading(node, h[1]); return undefined; }
      const setext = SETEXT.exec(name);
      if (setext) { setextHeading(node, setext[1]); return undefined; }
      switch (name) {
        case 'Autolink': autolink(node); break;
        case 'URL': if (!LINKED_URL_PARENT.has(node.node.parent?.name)) mark(node.from, node.to, 'cm-lp-link'); break;
        case 'Escape': hide(node.from, node.from + 1, `Escape@${node.from}`); break;
        case 'Emphasis': markedInline(node, 'cm-lp-em', 'EmphasisMark'); break;
        case 'StrongEmphasis': markedInline(node, 'cm-lp-strong', 'EmphasisMark'); break;
        case 'Strikethrough': markedInline(node, 'cm-lp-del', 'StrikethroughMark'); break;
        case 'Link': link(node); break;
        case 'Blockquote': blockquote(node); break;
        case 'QuoteMark': hide(node.from, node.to + spaceAfter(node.to)); break;
        case 'HorizontalRule': widget(node.from, node.to, { type: 'rule' }); break;
        case 'ListItem': listItem(node); break;
        default: break;
      }
      return undefined;
    },
  });
  const treeCount = specs.length;
  const claimed = [];
  const overlaps = (list, a, b) => list.some(([x, y]) => a < y && b > x);
  const wikiSpecs = (ref, lineFrom, whole) => {
    const a = lineFrom + ref.from;
    const b = lineFrom + ref.to;
    const open = a + (ref.embed ? 3 : 2);
    const attachment = isAttachmentRef(ref, context);
    if (ref.embed && attachment && isImageFileName.test(ref.fileName)) {
      const owner = ref.isAttachment ? ref.pageName : context.pageName;
      const w = { type: 'image', src: attachmentUrl(owner, ref.fileName), alt: ref.alias != null && !ref.size ? ref.alias : ref.fileName };
      if (ref.size) { w.width = ref.size[0]; if (ref.size[1] >= 0) w.height = ref.size[1]; }
      widget(a, b, w);
      return;
    }
    if (ref.embed && !attachment && whole && inactive(a) && blockEmbedAt(state, a, context)) return; // blockSpecs renders it
    const inner = doc.sliceString(open, b - 2);
    const pipe = inner.indexOf('|');
    const showSpan = (start, end) => { hide(a, start); mark(start, end, 'cm-lp-link'); hide(end, b); };
    if (ref.embed && attachment && ref.alias != null) { showSpan(open, open + ref.target.length); return; }
    if (ref.alias != null) {
      const raw = inner.slice(pipe + 1);
      const start = open + pipe + 1 + (raw.length - raw.trimStart().length);
      showSpan(start, start + ref.alias.length);
    } else if (ref.heading == null) {
      showSpan(open, open + ref.target.length);
    } else if (ref.isSamePage) {
      const raw = inner.slice(1);
      const start = open + 1 + (raw.length - raw.trimStart().length);
      showSpan(start, start + ref.heading.length);
    } else {
      const hashAt = open + ref.target.length + (inner.slice(ref.target.length).indexOf('#'));
      const raw = inner.slice(hashAt - open + 1);
      const hStart = hashAt + 1 + (raw.length - raw.trimStart().length);
      hide(a, open);
      mark(open, open + ref.target.length, 'cm-lp-link');
      widget(hashAt, hStart, { type: 'text', text: ' > ', cls: 'cm-lp-link' });
      mark(hStart, hStart + ref.heading.length, 'cm-lp-link');
      hide(hStart + ref.heading.length, b);
    }
  };
  eachLine(from, to, (line) => {
    const text = line.text;
    if (text.includes('[[')) {
      for (const ref of findWikiLinks(text)) {
        const a = line.from + ref.from;
        const b = line.from + ref.to;
        if (overlaps(excluded, a, b)) continue;
        claimed.push([a, b]);
        wikiSpecs(ref, line.from, text.trim() === ref.raw);
      }
    }
    if (text.includes('[{')) {
      for (const m of text.matchAll(PLUGIN)) {
        const a = line.from + m.index;
        const b = a + m[0].length;
        if (overlaps(excluded, a, b) || overlaps(claimed, a, b)) continue;
        claimed.push([a, b]);
        if (inactive(a)) widget(a, b, { type: 'chip', ...chipFor(m[0]), title: m[0] });
        else mark(a, b, 'cm-lp-plugin');
      }
    }
    if (text.includes('[^')) {
      const def = FOOTNOTE_DEF.exec(text);
      if (def) {
        const a = line.from + def[1].length;
        const b = a + def[2].length + 4; // `[^id]:`
        if (!overlaps(excluded, a, b) && !overlaps(claimed, a, b)) {
          claimed.push([a, b]);
          widget(a, b, { type: 'chip', text: `${def[2]}:`, cls: 'cm-lp-chip cm-lp-footnote-def', title: `Footnote ${def[2]}` });
        }
      }
      for (const m of text.matchAll(FOOTNOTE_REF)) {
        const a = line.from + m.index;
        const b = a + m[0].length;
        if (overlaps(excluded, a, b) || overlaps(claimed, a, b)) continue;
        claimed.push([a, b]);
        widget(a, b, { type: 'chip', text: m[1], cls: 'cm-lp-chip cm-lp-footnote-ref', title: `Footnote ${m[1]}` });
      }
    }
    if (text.includes('$')) {
      for (let i = 0; i < text.length; i += 1) {
        if (text[i] !== '$' || (i > 0 && text[i - 1] === '\\')) continue;
        const end = matchInlineMath(text, i);
        if (end < 0) continue;
        const a = line.from + i;
        const b = line.from + end;
        if (overlaps(excluded, a, b) || overlaps(claimed, a, b)) continue;
        claimed.push([a, b]);
        widget(a, b, { type: 'math', tex: text.slice(i + 1, end - 1), display: false });
        i = end - 1;
      }
    }
  });
  // A tree construct that cuts through a claimed range is dropped whole (its group); one that contains it is kept.
  const cuts = (sp) => claimed.some(([x, y]) => sp.from < y && sp.to > x && !(sp.from <= x && sp.to >= y));
  const cutGroups = new Set(specs.slice(0, treeCount).filter((sp) => sp.kind !== 'line' && cuts(sp)).map((sp) => sp.group ?? sp));
  const kept = specs.filter((sp, i) => i >= treeCount || !cutGroups.has(sp.group ?? sp));
  specs.length = 0;
  specs.push(...kept);
  // A block marker reads as a muted chip off the caret's line; a later duplicate of an id (first wins) is flagged.
  const firsts = markers.length ? firstMarkers(state, Math.max(...markers.map((m) => m.from))) : null;
  for (const m of markers) {
    if (!inactive(m.from)) continue;
    const first = firsts.get(m.id);
    const end = m.from + 1 + m.id.length;
    if (first !== undefined && first < m.from) {
      specs.push({ kind: 'mark', from: m.from, to: end, cls: 'cm-lp-blockid-dup', title: `Duplicate block id — only the first ^${m.id} is linkable` });
    } else {
      specs.push({ kind: 'mark', from: m.from, to: end, cls: 'cm-lp-blockid' });
    }
  }
  for (const [lineStart, classes] of lineClasses) {
    for (const cls of classes) specs.push({ kind: 'line', from: lineStart, to: lineStart, cls });
  }
  return finalize(doc, specs);
}
