// Obsidian-style `^id` block markers: the editor's mirror of the server's BlockIds / BlockWalk
// (com.wikantik.parser.markdown), rule for rule. The shared fixture __fixtures__/block-ids.json pins the editor
// preview (remarkBlockIds) and the reader's render to the same anchors; blockIds.test.js mirrors BlockIdsTest.
//
// Blocks, in pre-order: paragraphs (not a list item's own first paragraph — the item is the block), list items,
// tables, block quotes (a callout is a 'callout'), fenced and indented code (a `math` fence and `$$…$$` are 'math'),
// and a list only when a standalone marker addresses it. Headings are never blocks, and neither is a paragraph of
// nothing but access rules (`[{ALLOW …}]`, `[{DENY …}]`): the reader never shows one.
//
// Markers: inline (whitespace + `^id` ending a paragraph's last text node), last-line (`^id` alone on the last line of
// a multi-line paragraph — the line break is the whitespace it needs) and standalone (a paragraph that is only `^id`,
// tagging the block right before it in the same container, or an orphan; so is a paragraph of access rules and one
// marker, which is what the render's rule stripping leaves of it). Code, math and headings never hold one. A list item
// whose first line is only access rules is numbered as the empty item the render sees.
// The first occurrence of an id in source order wins.
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import { toString } from 'mdast-util-to-string';
import remarkInlineMath from './remarkInlineMath';
import remarkCallouts from './remarkCallouts';
import { stripFrontmatter } from './frontmatterUtils';
import { findWikiLinks, wikiLinkDisplayText } from './wikiLinkSyntax';

/** An inline marker at the end of a paragraph's last text node; group 1 is the id. */
export const BLOCK_ID_RE = /(?<=[ \t])\^([A-Za-z0-9-]+)[ \t]*\r?$/;
/** A line (or text node) that is only a marker; group 1 is the id. */
export const STANDALONE_RE = /^\^([A-Za-z0-9-]+)[ \t]*\r?$/;
/** A text that is a marker and whitespace: beside access rules, all that is left once they are stripped. */
export const MARKER_WITH_SPACE = /^\s*\^([A-Za-z0-9-]+)\s*$/;

const ID_RE = /^[A-Za-z0-9-]+$/;
/** An access rule written as text (`[{ALLOW …}]`, `[{DENY …}]`, with or without `()`). Global: use with replace. */
export const RULE_TEXT = /\[\{(?:ALLOW|DENY)[^}\n]*\}\](?:\(\))?/g;
const RULE_LINK_TEXT = /^\{(?:ALLOW|DENY)/;
const NEW_ID_CHARS = 'abcdefghijklmnopqrstuvwxyz0123456789';
const NEW_ID_LENGTH = 6;
const FINGERPRINT_LENGTH = 200;

/** Whether a wikilink heading part addresses a block: it starts with `^`. The one discriminator, as on the server. */
export function isBlockRef(heading) {
  return typeof heading === 'string' && heading.startsWith('^');
}

// ---- the tree walk ---------------------------------------------------------------------------------------------------

const classesOf = (node) => node.data?.hProperties?.className || [];
const isCalloutTitle = (node) => node.type === 'paragraph' && classesOf(node).includes('callout-title');
const isCalloutContent = (node) => node.type === 'blockquote' && classesOf(node).includes('callout-content');
const isCallout = (node) => node.type === 'blockquote' && classesOf(node).includes('callout');

/**
 * An access rule written as a link, `[{ALLOW …}]()`. (Without the `()` it is still text here; the server adds the
 * `()` before it parses, so the text form is matched too — see isRuleOnly.) Read before remarkWikiMarkup makes chips.
 */
const isAccessRule = (node) => node.type === 'link' && node.url === '' && RULE_LINK_TEXT.test(toString(node));

/** Whether a paragraph holds nothing but access rules (and whitespace or line breaks): the reader never shows it. */
function isRuleOnly(p) {
  let any = false;
  for (const c of p.children) {
    if (isAccessRule(c)) {
      any = true;
    } else if (c.type === 'text') {
      const rest = c.value.replace(RULE_TEXT, () => { any = true; return ''; });
      if (rest.trim() !== '') return false;
    } else if (c.type !== 'break') {
      return false;
    }
  }
  return any;
}

/**
 * Where `tail` — the end of `node.value` — starts in the source, or -1 when the source there reads otherwise (an
 * escape or an entity, which the parsed value no longer shows). Without a source or a position nothing can be checked:
 * the value is trusted.
 */
function tailAt(node, source, tail) {
  const end = node.position?.end?.offset;
  if (end == null) return 0;
  const at = end - tail.length;
  if (typeof source !== 'string') return at;
  return source.slice(at, end) === tail ? at : -1;
}

const charBefore = (source, at) => (typeof source === 'string' ? source[at - 1] ?? '' : null);

/**
 * The standalone marker of a paragraph, as `{ id, text, cut, caret, beside }`, else null: a paragraph that is only
 * `^id`, or one of access rules and one marker (`[{ALLOW view Admin}] ^id`, or the rule on the line before) — the render
 * strips the rules before it parses, which leaves exactly a standalone marker. `cut` is the `^`'s index in the text
 * node's value; `beside` says an access rule shares the marker's line (so the blanks before it go with it).
 */
function standaloneOf(p, source) {
  const t = p.children.length === 1 && p.children[0].type === 'text' ? p.children[0] : null;
  const m = t && STANDALONE_RE.exec(t.value);
  if (!m) return besideRules(p, source);
  const at = tailAt(t, source, t.value);
  return at >= 0 && charBefore(source, at) !== '\\' ? { id: m[1], text: t, cut: 0, caret: at, beside: false } : null;
}

/** The marker of a paragraph of access rules and one `^id` (see standaloneOf), else null. */
function besideRules(p, source) {
  let rule = false;
  let found = null;
  for (let i = 0; i < p.children.length; i++) {
    const c = p.children[i];
    if (isAccessRule(c)) { rule = true; continue; }
    if (c.type === 'break') continue;
    if (c.type !== 'text') return null;
    const masked = c.value.replace(RULE_TEXT, (r) => { rule = true; return ' '.repeat(r.length); });
    if (masked.trim() === '') continue;
    const m = MARKER_WITH_SPACE.exec(masked);
    if (found || !m) return null;
    const cut = masked.indexOf('^');
    const lineStart = c.value.lastIndexOf('\n', cut - 1) + 1;
    const prev = p.children[i - 1];
    const beside = masked.slice(lineStart, cut) !== c.value.slice(lineStart, cut)
      || (lineStart === 0 && prev != null && prev.type !== 'break');
    found = { id: m[1], text: c, cut, beside };
  }
  if (!rule || !found) return null;
  const at = tailAt(found.text, source, found.text.value.slice(found.cut));
  if (at < 0 || charBefore(source, at) === '\\') return null;
  return { ...found, caret: at };
}

/** The id of a paragraph that is only a standalone marker, else null. */
function standaloneId(p, source) {
  return standaloneOf(p, source)?.id ?? null;
}

function standalone(p, parent, source) {
  const s = standaloneOf(p, source);
  return { id: s.id, form: 'standalone', paragraph: p, parent, text: s.text, caret: s.caret, cut: s.cut,
    beside: s.beside, rules: s.cut > 0 || p.children.length > 1 };
}

/** The inline or last-line marker ending `p`, or null. */
function inlineMarker(p, source) {
  const last = p.children[p.children.length - 1];
  if (!last || last.type !== 'text') return null;
  const v = last.value;
  const m = BLOCK_ID_RE.exec(v);
  if (m) {
    const at = tailAt(last, source, v.slice(m.index));
    const before = charBefore(source, at);
    if (at < 0 || (before !== null && before !== ' ' && before !== '\t')) return null;
    return { id: m[1], form: 'inline', paragraph: p, text: last, caret: at, cut: m.index };
  }
  const nl = v.lastIndexOf('\n');
  const line = v.slice(nl + 1);
  const alone = STANDALONE_RE.exec(line);
  const prev = p.children[p.children.length - 2];
  if (!alone || (nl < 0 && prev?.type !== 'break')) return null;
  const at = tailAt(last, source, line);
  if (at < 0 || charBefore(source, at) === '\\') return null;
  return { id: alone[1], form: 'last-line', paragraph: p, text: last, caret: at, cut: nl };
}

/**
 * Numbers the addressable blocks under `root` in pre-order and finds their markers, as the server's BlockWalk does.
 * Read-only. `source` is the markdown the tree was parsed from (positions index it); without it, escapes are not
 * checked. Entries: `{ node, parent, kind, marker, textLine }`.
 */
export function walkBlocks(root, source) {
  const entries = [];
  const orphans = [];
  const claimed = new Set();

  const standaloneAfter = (node, parent) => {
    const siblings = parent.children;
    let i = siblings.indexOf(node) + 1;
    while (siblings[i]?.type === 'paragraph' && isRuleOnly(siblings[i])) i++;
    const next = siblings[i];
    return next?.type === 'paragraph' && standaloneId(next, source) != null ? next : null;
  };
  const claimNext = (node, parent) => {
    const next = standaloneAfter(node, parent);
    if (!next) return null;
    claimed.add(next);
    return standalone(next, parent, source);
  };
  const tagged = (node, parent, kind) => ({ node, parent, kind, marker: claimNext(node, parent), textLine: null });

  const paragraph = (p, parent) => {
    if (isCalloutTitle(p) || isRuleOnly(p)) return;
    if (standaloneId(p, source) != null) {
      if (!claimed.has(p)) orphans.push(standalone(p, parent, source));
      return;
    }
    if (parent.type === 'listItem' && parent.children[0] === p) return;   // the list item is the block
    entries.push({ node: p, parent, kind: 'paragraph', marker: inlineMarker(p, source) || claimNext(p, parent), textLine: p });
  };

  const listItem = (item, parent) => {
    const fp = item.children[0];
    const first = fp?.type === 'paragraph' && standaloneId(fp, source) == null && !isRuleOnly(fp) ? fp : null;
    const marker = first ? inlineMarker(first, source) || claimNext(first, item) : null;
    entries.push({ node: item, parent, kind: 'list-item', marker, textLine: first });
  };

  /** Records `node` if it is a block; returns whether its children can hold blocks. */
  const visitNode = (node, parent) => {
    switch (node.type) {
      case 'paragraph': paragraph(node, parent); return false;
      case 'listItem': listItem(node, parent); return true;
      case 'list':
        if (standaloneAfter(node, parent)) entries.push(tagged(node, parent, 'list'));
        return true;
      case 'table': entries.push(tagged(node, parent, 'table')); return false;
      case 'blockquote':
        if (!isCalloutContent(node)) entries.push(tagged(node, parent, isCallout(node) ? 'callout' : 'quote'));
        return true;
      case 'code': entries.push(tagged(node, parent, node.lang === 'math' && !node.meta ? 'math' : 'code')); return false;
      case 'math': entries.push(tagged(node, parent, 'math')); return false;
      default: return true;
    }
  };

  // Iterative pre-order: nesting depth is unbounded in a page.
  const stack = [];
  const pushChildren = (node) => {
    const children = node.children || [];
    for (let i = children.length - 1; i >= 0; i--) stack.push([children[i], node]);
  };
  pushChildren(root);
  while (stack.length) {
    const [node, parent] = stack.pop();
    if (visitNode(node, parent)) pushChildren(node);
  }

  const markers = [...orphans, ...entries.filter((e) => e.marker).map((e) => e.marker)]
    .sort((a, b) => a.caret - b.caret);
  const winners = new Set();
  const seen = new Set();
  for (const m of markers) {
    if (!seen.has(m.id)) { seen.add(m.id); winners.add(m); }
  }
  return {
    entries,
    orphans,
    markers,
    wins: (m) => winners.has(m),
    idOf: (e) => (e.marker && winners.has(e.marker) ? e.marker.id : null),
  };
}

/**
 * Takes every marker of `walk` out of the tree, as rendering does: inline ones are trimmed from their text (a last-line
 * one with its line break, and the blanks that break left), standalone paragraphs are removed — an orphan that wins its
 * id is replaced by what `orphanAnchor(marker)` gives (removed when it gives null).
 */
export function detachMarkers(walk, orphanAnchor = () => null) {
  const orphans = new Set(walk.orphans);
  for (const m of walk.markers) {
    const p = m.paragraph;
    if (m.form === 'standalone') {
      const anchor = orphans.has(m) && walk.wins(m) ? orphanAnchor(m) : null;
      if (m.rules) {   // beside access rules: the rules stay (the editor shows them), only the marker goes
        const v = m.text.value;
        m.text.value = v.slice(0, m.cut) + v.slice(m.cut + 1 + m.id.length);
        m.parent.children.splice(m.parent.children.indexOf(p) + 1, 0, ...(anchor ? [anchor] : []));
      } else {
        m.parent.children.splice(m.parent.children.indexOf(p), 1, ...(anchor ? [anchor] : []));
      }
    } else if (m.cut >= 0) {
      m.text.value = m.text.value.slice(0, m.cut).trimEnd();
    } else {   // a last-line marker that is a text node of its own: it is the last child, after a hard break
      p.children.splice(-2, 2);
      const last = p.children[p.children.length - 1];
      if (last?.type === 'text') last.value = last.value.trimEnd();
    }
  }
}

// ---- markdown → blocks -----------------------------------------------------------------------------------------------

let parser = null;
const markdownParser = () => {
  if (!parser) {
    parser = unified().use(remarkParse).use(remarkGfm, { singleTilde: false })
      .use(remarkMath, { singleDollarTextMath: false }).use(remarkInlineMath).use(remarkCallouts);
  }
  return parser;
};

/** The parsed tree of `body`, with callouts built. */
function parseTree(body) {
  const p = markdownParser();
  return p.runSync(p.parse(body), body);
}

/**
 * `body` with the access rules blanked to spaces on every list item's first line that holds nothing else
 * (`- [{ALLOW view Admin}]`), or null when there is none. The render strips those rules before it parses, which leaves
 * an empty item — and an empty item parses differently (it cannot interrupt a paragraph; a blank line after it ends it).
 * Spaces keep every offset where it is. The server's BlockIds does the same.
 */
function emptyRuleItems(body, tree) {
  let out = null;
  const visit = (node) => {
    for (const item of node.children || []) {
      const p = item.type === 'listItem' ? item.children[0] : null;
      if (p?.type === 'paragraph' && p.position && p.position.start.line === item.position?.start?.line) {
        const from = p.position.start.offset;
        const nl = body.indexOf('\n', from);
        const to = Math.min(nl < 0 ? body.length : nl, p.position.end.offset);
        const line = body.slice(from, to);
        const blanked = line.replace(RULE_TEXT, (r) => ' '.repeat(r.length));
        if (blanked !== line && blanked.trim() === '') {
          out = (out ?? body).slice(0, from) + blanked + (out ?? body).slice(to);
        }
      }
      visit(item);
    }
  };
  visit(tree);
  return out;
}

/** The tree the blocks of `body` are numbered in: list items of only access rules read as empty, as rendered. */
function parseBlocksTree(body) {
  const tree = parseTree(body);
  const emptied = emptyRuleItems(body, tree);
  return emptied == null ? { tree, source: body } : { tree: parseTree(emptied), source: emptied };
}

const isBlank = (c) => c === ' ' || c === '\t';

/** The plain text of a block: markdown and markers reduced away, wikilinks to their display text, embeds dropped. */
function plainText(node) {
  return reduceWikiLinks(collectText(node)).trim();
}

const BLOCK_TYPES = new Set(['paragraph', 'heading', 'list', 'listItem', 'blockquote', 'code', 'math', 'table',
  'tableRow', 'thematicBreak', 'html', 'definition', 'footnoteDefinition']);

/** One line per child block; cells of a row joined by a space. */
function collectText(node) {
  const children = node.children || [];
  if (node.type === 'tableRow') return children.map((c) => inlineText(c).trim()).join(' ');
  if (!children.some((c) => BLOCK_TYPES.has(c.type))) return inlineText(node);
  return children.map(collectText).map((t) => t.trim()).filter((t) => t !== '').join('\n');
}

function inlineText(node) {
  if (node.type === 'break') return '\n';
  if (node.children) return node.children.map(inlineText).join('');
  return node.value ?? node.alt ?? '';   // an image counts by its alt text
}

function reduceWikiLinks(text) {
  let out = '';
  let pos = 0;
  for (const ref of findWikiLinks(text)) {
    out += text.slice(pos, ref.from) + (ref.embed ? '' : wikiLinkDisplayText(ref));
    pos = ref.to;
  }
  return out + text.slice(pos);
}

/** Removes the access rules from the tree, as the render path does before it parses. */
function dropAccessRules(node) {
  if (!node.children) return;
  node.children = node.children.filter((c) => !isAccessRule(c));
  for (const c of node.children) {
    if (c.type === 'text') c.value = c.value.replace(RULE_TEXT, '');
    else dropAccessRules(c);
  }
}

/** Source coordinates, from the parsed body back to the full text. */
function coordsOf(md, bodyStart, body) {
  const startOf = (node) => bodyStart + node.position.start.offset;
  const endOf = (node, from) => {
    let end = bodyStart + node.position.end.offset;
    while (end > from && /\s/.test(md[end - 1])) end--;
    return end;
  };
  const spaceBefore = (pos) => { let p = pos; while (p > 0 && isBlank(md[p - 1])) p--; return p; };
  const spaceAfter = (pos) => { let p = pos; while (p < md.length && isBlank(md[p])) p++; return p; };
  /** A paragraph starts at its first inline: a callout's body paragraph still spans the title line. */
  const paragraphStart = (p, calloutLine) => {
    if (calloutLine != null && p.position?.start?.line === calloutLine) {
      // remarkCallouts took the title line off the front: the body starts on the next line, after the `> ` prefix
      let at = body.indexOf('\n', p.position.start.offset) + 1;
      while (at < body.length && (isBlank(body[at]) || body[at] === '>')) at++;
      return bodyStart + at;
    }
    return startOf(p);
  };
  return { startOf, endOf, spaceBefore, spaceAfter, paragraphStart };
}

const lineStart = (text, pos) => text.lastIndexOf('\n', pos - 1) + 1;

/**
 * Every addressable block of `md` (the editor buffer; leading frontmatter is skipped), in the server's order:
 * `{ index, id, kind, from, to, markerFrom, markerTo, inlineAt, text }`. Offsets index `md`. `id` is null without a
 * winning marker; `markerFrom`/`markerTo` cover the block's marker (an inline one from the blanks before its `^`), or
 * are -1; `inlineAt` is where an inline marker would go, or -1 when the block cannot take one. `kind` is one of
 * paragraph, list-item, list, table, quote, callout, code, math (the REST API's names).
 */
export function blocksFromMarkdown(md) {
  if (!md) return [];
  const body = stripFrontmatter(md);
  const bodyStart = md.length - body.length;
  const { tree, source } = parseBlocksTree(body);
  const walk = walkBlocks(tree, source);
  const c = coordsOf(md, bodyStart, source);

  const calloutLines = new Map();   // callout content wrapper → the callout's first line
  const noteCallouts = (node) => (node.children || []).forEach((child) => {
    if (isCallout(node) && isCalloutContent(child)) calloutLines.set(child, node.position?.start?.line);
    noteCallouts(child);
  });
  noteCallouts(tree);

  const rangeOf = (m) => {
    if (!m) return [-1, -1];
    const caret = bodyStart + m.caret;
    return [m.form === 'standalone' && !m.beside ? caret : c.spaceBefore(caret), c.spaceAfter(caret + 1 + m.id.length)];
  };
  const located = walk.entries.map((e) => {
    const node = e.node;
    // (an indented code block's position already starts at its indent, as the server's range does)
    const from = e.kind === 'paragraph' ? c.paragraphStart(node, calloutLines.get(e.parent)) : c.startOf(node);
    const to = c.endOf(node, from);
    let inlineAt = -1;
    if (e.kind === 'paragraph') inlineAt = to;
    else if (e.textLine) inlineAt = c.endOf(e.textLine, c.startOf(e.textLine));
    return { from, to, inlineAt, range: rangeOf(e.marker) };
  });

  detachMarkers(walk);
  dropAccessRules(tree);
  return walk.entries.map((e, index) => ({
    index,
    id: walk.idOf(e),
    kind: e.kind,
    from: located[index].from,
    to: located[index].to,
    markerFrom: located[index].range[0],
    markerTo: located[index].range[1],
    inlineAt: located[index].inlineAt,
    text: plainText(e.node),
  }));
}

/**
 * Every marker of `md` in source order — block markers, losing duplicates and orphans — as `{ id, caret, wins }`
 * (`caret` indexes `md`; `wins` is the first-occurrence rule). The editor's live preview is checked against it.
 */
export function blockMarkers(md) {
  if (!md) return [];
  const body = stripFrontmatter(md);
  const bodyStart = md.length - body.length;
  const { tree, source } = parseBlocksTree(body);
  const walk = walkBlocks(tree, source);
  return walk.markers.map((m) => ({ id: m.id, caret: bodyStart + m.caret, wins: walk.wins(m) }));
}

// ---- editing ---------------------------------------------------------------------------------------------------------

/** A line prefix turned into the prefix of a continuation line: block-quote `>` and tabs kept, the rest spaces. */
const continuationPrefix = (prefix) => [...prefix].map((ch) => (ch === '>' || ch === '\t' ? ch : ' ')).join('');

/** Whether the line at `at` holds nothing but whitespace and block-quote markers (or there is no line). */
function isBlankLine(text, at) {
  for (let i = at; i < text.length && text[i] !== '\n'; i++) {
    if (text[i] !== '>' && !/\s/.test(text[i])) return false;
  }
  return true;
}

/** The insertion that puts `^id` on a marker line of its own after `b`, inside `b`'s container, set apart by blank lines. */
function standaloneMarkerChange(text, b, id) {
  const prefix = continuationPrefix(text.slice(lineStart(text, b.from), b.from));
  const blank = prefix.trimEnd();
  const nl = text.indexOf('\n', b.to);
  if (nl < 0) {
    const eol = text.includes('\r\n') ? '\r\n' : '\n';
    return { from: text.length, to: text.length, insert: `${eol}${blank}${eol}${prefix}^${id}` };
  }
  const eol = nl > 0 && text[nl - 1] === '\r' ? '\r\n' : '\n';
  const at = nl + 1;
  let insert = `${blank}${eol}${prefix}^${id}${eol}`;
  if (!isBlankLine(text, at)) insert += `${blank}${eol}`;   // keep the marker its own paragraph
  return { from: at, to: at, insert };
}

const applyChange = (md, c) => md.slice(0, c.from) + c.insert + md.slice(c.to);

/**
 * The one edit that tags `block` (from `blocksFromMarkdown(md)`) with `id`, as `{ from, to, insert }` over `md`: an
 * inline ` ^id` at the end of a paragraph or of a list item's first line, otherwise a standalone marker line after the
 * block; a losing duplicate's marker is replaced. Mirrors the server's BlockIds.assign. The editor applies it as one
 * transaction, so it is one undo step and the caret maps through it.
 *
 * @throws Error when `id` is malformed, `block` is not a current block of `md`, it already has an id or can't take
 *   one, or the edited text does not read back with the block tagged.
 */
export function markerChange(md, block, id) {
  if (typeof id !== 'string' || !ID_RE.test(id)) throw new Error(`Not a block id: ${id}`);
  const b = blocksFromMarkdown(md)[block?.index];
  if (!b || b.kind !== block.kind || b.from !== block.from || b.to !== block.to) {
    throw new Error(`Block ${block?.index} is not a current block of this text`);
  }
  if (b.id != null) throw new Error(`Block ${b.index} already has id ^${b.id}`);
  let change;
  if (b.markerFrom >= 0) {   // a duplicate: its marker carries no id, so its ^id is replaced
    change = { from: md.indexOf('^', b.markerFrom), to: b.markerTo, insert: `^${id}` };
  } else if (b.inlineAt >= 0) {
    change = { from: b.inlineAt, to: b.inlineAt, insert: ` ^${id}` };
  } else if (b.kind === 'list-item') {
    throw new Error(`List item ${b.index} has no text line to carry an id`);
  } else {
    change = standaloneMarkerChange(md, b, id);
  }
  if (blocksFromMarkdown(applyChange(md, change))[b.index]?.id !== id) {
    throw new Error(`Adding ^${id} to block ${b.index} (${b.kind}) did not tag it`);
  }
  return change;
}

/** `md` with `block` tagged `id`: `markerChange` applied. Same contract and errors. */
export function insertMarker(md, block, id) {
  return applyChange(md, markerChange(md, block, id));
}

/** The innermost block of `md` whose range holds `pos` (ends included), or null (a heading, a blank line, …). */
export function blockAt(md, pos) {
  let best = null;
  for (const b of blocksFromMarkdown(md)) {
    if (b.from <= pos && pos <= b.to && (!best || b.to - b.from <= best.to - best.from)) best = b;
  }
  return best;
}

/**
 * Every `^word` in `md` — ids, losing duplicates, orphans, even look-alikes in code — so a fresh id cannot collide with
 * any of them (over-collecting only narrows the choice).
 */
export function takenBlockIds(md) {
  return new Set([...(md || '').matchAll(/\^([A-Za-z0-9-]+)/g)].map((m) => m[1]));
}

/**
 * What "copy a link to the block at `pos`" needs: `{ id, change }`, where `change` is the marker edit for a block
 * without an id (null when it already has one), or null when no block holds `pos`.
 */
export function blockLinkEdit(md, pos) {
  const b = blockAt(md, pos);
  if (!b) return null;
  if (b.id) return { id: b.id, change: null };
  const id = newBlockId(takenBlockIds(md));
  return { id, change: markerChange(md, b, id) };
}

/** A fresh id: six chars of [a-z0-9], not in `existing` (a Set, an array, or nothing). */
export function newBlockId(existing) {
  const taken = existing instanceof Set ? existing : new Set(existing || []);
  for (;;) {
    const bytes = globalThis.crypto.getRandomValues(new Uint8Array(NEW_ID_LENGTH));
    if (bytes.some((v) => v >= 252)) continue;   // 252 = 7 × 36: keep every char equally likely
    const id = [...bytes].map((v) => NEW_ID_CHARS[v % NEW_ID_CHARS.length]).join('');
    if (!taken.has(id)) return id;
  }
}

/** The text lowercased, reduced to letters and digits, first 200 chars ('' for none) — the server's fingerprint. */
export function blockFingerprint(text) {
  if (text == null) return '';
  return String(text).toLowerCase().replace(/[^\p{L}\p{N}]/gu, '').slice(0, FINGERPRINT_LENGTH);
}
