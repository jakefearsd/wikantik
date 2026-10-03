import { syntaxTree } from '@codemirror/language';
import { MARKER, styleOf, defaultTitle } from '../remarkCallouts';
import { matchInlineMath } from '../inlineMath';
import { findWikiLinks, parseWikiLink, isImageFileName } from '../wikiLinkSyntax';
/** Nodes whose whole range stays source and is never scanned by the line regexes (Task 3). */
const SKIP = new Set(['Frontmatter', 'Table', 'HTMLBlock', 'CommentBlock', 'ProcessingInstructionBlock', 'CodeBlock', 'LinkReference']);
const HEADING = /^ATXHeading([1-6])$/;
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
/** The block widget a top-level node becomes (display math, whole-line page embed), or null. */
function blockCandidateOf(doc, node, context) {
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
 * Every block-widget candidate (display math, whole-line page embed) regardless of the caret: top-level
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
  const link = (node) => {
    const n = node.node;
    const marks = n.getChildren('LinkMark');
    if (!n.getChild('URL') || marks.length < 2 || doc.sliceString(marks[0].from, marks[0].to) !== '[') return;
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
      if (SKIP.has(name)) { excluded.push([node.from, node.to]); return false; }
      if (name === 'FencedCode') { fenced(node); excluded.push([node.from, node.to]); return false; }
      if (name === 'InlineCode') { markedInline(node, 'cm-lp-code', 'CodeMark'); excluded.push([node.from, node.to]); return false; }
      if (name === 'Paragraph' && doc.sliceString(node.from, node.from + 2) === '$$' && isBlockMathText(doc.sliceString(node.from, node.to))) { excluded.push([node.from, node.to]); return false; }
      if (name === 'Image') { image(node); return false; }
      const h = HEADING.exec(name);
      if (h) { heading(node, h[1]); return undefined; }
      switch (name) {
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
        mark(a, b, 'cm-lp-plugin');
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
  for (const [lineStart, classes] of lineClasses) {
    for (const cls of classes) specs.push({ kind: 'line', from: lineStart, to: lineStart, cls });
  }
  return finalize(doc, specs);
}
