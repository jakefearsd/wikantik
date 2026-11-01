// Obsidian-style `^id` block markers: the editor's mirror of the server's BlockIds / BlockWalk
// (com.wikantik.parser.markdown). The shared fixture __fixtures__/block-ids.json pins the editor preview
// (remarkBlockIds) and the reader's render to the same anchors; blockIds.test.js mirrors BlockIdsTest.
//
// Blocks, in pre-order: paragraphs (not a list item's own first paragraph — the item is the block), list items,
// tables, block quotes (a callout is a 'callout'), fenced and indented code (a `math` fence and `$$…$$` are 'math'),
// and a list only when a standalone marker addresses it. Headings are never blocks, and neither is a paragraph of
// nothing but access rules (`[{ALLOW …}]`, `[{DENY …}]`): the reader never shows one.
//
// Markers: inline (whitespace + `^id` ending a paragraph's last text node), last-line (`^id` alone on the last line of
// a multi-line paragraph — the line break is the whitespace it needs) and standalone (a paragraph that is only `^id`,
// tagging the block right before it in the same container, or an orphan). Code, math and headings never hold one.
// The first occurrence of an id in source order wins.
//
// Access rules. The server reads the text a render parses — the page with its access rules stripped — so whatever
// stripping does to the structure is in its numbering by construction. This mirror reads the raw buffer (the preview
// draws it, rules as chips) and reads the rules as the stripping would leave things, for the shapes that matter:
//   - a paragraph of only rules is no block, and a standalone marker looks past it;
//   - rules and one marker are a standalone marker (`[{ALLOW view Admin}] ^id`, the marker before or after);
//   - rules after a marker, on its line or on lines of their own, end the paragraph before them (`Para ^id [{…}]`);
//   - a list item whose first line is only rules is the empty item the render sees;
//   - a top-level paragraph whose first non-rule character stripping leaves four columns in is code.
// Where it can still differ (pinned per case in the fixture's "editor" entries, with the reason), and why it may:
//   - a rule that is the text of a setext heading (`[{ALLOW …}]\n===`): the reader shows `===` as a paragraph;
//   - indentation left by stripping inside a container (a quote, a list item): the reader may see code there;
//   - a rule wrapped over lines, in lower case, or with a spelling only the server's two readings know;
//   - the preview's drawing of a rule-only list item and of what the reader shows as code (ids agree there).
// None of this reaches the server: the editor's block indices are its own (the picker's local list, a marker inserted
// into the buffer and read back here); a block on another page is listed and tagged by the server's index and version.
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
/**
 * A text that is a marker and blanks: beside access rules, all that is left once they are stripped. Blanks are spaces,
 * tabs and line breaks only (the server's walk takes no other whitespace beside a marker: a vertical tab is text).
 */
export const MARKER_WITH_SPACE = /^[ \t\r\n]*\^([A-Za-z0-9-]+)[ \t\r\n]*$/;
/** Trailing blanks (spaces, tabs, line breaks). */
const TRAILING_BLANKS = /[ \t\r\n]+$/;

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

/**
 * Where character `index` of the text node `node` sits in the source, or -1 when the source there reads otherwise. On
 * the value's last line that is tailAt's answer. On an earlier line (a marker with access rules on the lines after it)
 * it is found from the end of its own line, counted from the node's start — the value's lines lose their container
 * prefixes (`> `, a list item's indent), so offsets within the value say nothing about a later line: the rest of that
 * line of the value, trailing blanks aside, must be what the source line ends with.
 */
function sourceAt(node, source, index) {
  const v = node.value;
  const lineStart = v.lastIndexOf('\n', index - 1) + 1;
  const nl = v.indexOf('\n', index);
  const tail = v.slice(index, nl < 0 ? v.length : nl).replace(/[ \t\r]+$/, '');
  const start = node.position?.start?.offset;
  if (nl < 0 || start == null || typeof source !== 'string') return tailAt(node, source, v.slice(index));
  let from = start;
  for (let i = v.indexOf('\n'); i >= 0 && i < lineStart; i = v.indexOf('\n', i + 1)) {
    from = source.indexOf('\n', from) + 1;
    if (from === 0) return -1;
  }
  let to = source.indexOf('\n', from);
  if (to < 0) to = source.length;
  while (to > from && ' \t\r'.includes(source[to - 1])) to--;
  return source.slice(to - tail.length, to) === tail ? to - tail.length : -1;
}

const charBefore = (source, at) => (typeof source === 'string' ? source[at - 1] ?? '' : null);

/**
 * The standalone marker of a paragraph, as `{ id, text, cut, caret, beside }`, else null: a paragraph that is only
 * `^id`, or one of access rules and one marker (`[{ALLOW view Admin}] ^id`, or the rule on the line before) — the render
 * strips the rules before it parses, which leaves exactly a standalone marker. `cut` is the `^`'s index in the text
 * node's value; `beside` says an access rule shares the marker's line (so the blanks before it go with it).
 */
function standaloneOf(p, source, parent) {
  const t = p.children.length === 1 && p.children[0].type === 'text' ? p.children[0] : null;
  const m = t && STANDALONE_RE.exec(t.value);
  if (!m) return besideRules(p, source, parent?.type === 'root');
  const at = tailAt(t, source, t.value);
  return at >= 0 && charBefore(source, at) !== '\\' ? { id: m[1], text: t, cut: 0, caret: at, beside: false } : null;
}

/**
 * Whether stripping the access rules before the caret at `at` leaves its line indented four columns or more (a tab
 * counts to the next multiple of four): a top-level paragraph then starts an indented code block, not a marker.
 */
function strippedIntoCode(source, at) {
  if (typeof source !== 'string') return false;
  const prefix = source.slice(source.lastIndexOf('\n', at - 1) + 1, at).replace(RULE_TEXT, '');
  if (!/^[ \t]*$/.test(prefix)) return false;
  let columns = 0;
  for (const ch of prefix) columns = ch === '\t' ? columns + 4 - (columns % 4) : columns + 1;
  return columns >= 4;
}

/**
 * The marker of a paragraph of access rules and one `^id` (see standaloneOf), else null. `topLevel`: the paragraph is
 * not in a container, so a line the stripping leaves indented four columns is code (in a container the indent counts
 * from the container's content, which this mirror does not work out — see the fixture's editor notes).
 */
function besideRules(p, source, topLevel) {
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
  if (at < 0 || charBefore(source, at) === '\\' || (topLevel && strippedIntoCode(source, at))) return null;
  return { ...found, caret: at };
}

/**
 * Whether a top-level paragraph is, once its access rules are stripped, an indented code block: its first character that
 * is not a rule sits four columns in (`[{ALLOW view Admin}]\t^id`, or `    text` on the line after a rule line).
 */
function startsAsCode(p, source) {
  for (const c of p.children) {
    if (isAccessRule(c) || c.type === 'break') continue;
    if (c.type !== 'text') return false;
    const first = c.value.replace(RULE_TEXT, (r) => ' '.repeat(r.length)).search(/[^ \t\r\n]/);
    if (first >= 0) {
      const at = sourceAt(c, source, first);
      return at >= 0 && strippedIntoCode(source, at);
    }
  }
  return false;
}

/** The id of a paragraph that is only a standalone marker, else null. */
function standaloneId(p, source, parent) {
  return standaloneOf(p, source, parent)?.id ?? null;
}

function standalone(p, parent, source) {
  const s = standaloneOf(p, source, parent);
  return { id: s.id, form: 'standalone', paragraph: p, parent, text: s.text, caret: s.caret, cut: s.cut,
    beside: s.beside, rules: s.cut > 0 || p.children.length > 1 };
}

/**
 * The text node that ends `p` once trailing access rules are taken away, with the length of its value that is left —
 * what the render's rule stripping leaves at the end of the paragraph: rule links, line breaks and texts of only rules
 * and blanks after it are skipped, and rules and blanks at the end of its own value are cut off. Null when something
 * else ends the paragraph.
 */
function lastTextBeforeRules(p) {
  for (let i = p.children.length - 1; i >= 0; i--) {
    const c = p.children[i];
    if (isAccessRule(c) || c.type === 'break') continue;
    if (c.type !== 'text') return null;
    const kept = c.value.replace(RULE_TEXT, (r) => ' '.repeat(r.length)).replace(TRAILING_BLANKS, '').length;
    if (kept > 0) return { text: c, kept };
  }
  return null;
}

/** Index just past the id at `caret` in `value` and the spaces and tabs after it. */
function markerEnd(value, caret, id) {
  let end = caret + 1 + id.length;
  while (isBlank(value[end])) end++;
  return end;
}

/**
 * The inline or last-line marker ending `p`, or null. Access rules after it — on its line or on lines of their own — are
 * read as gone, as the render strips them before it parses. `cut` is where the marker (with the blanks before it, or
 * the line break before a last-line one) starts in its text node's value, `end` where it ends.
 */
function inlineMarker(p, source) {
  const found = lastTextBeforeRules(p);
  if (!found) return null;
  const last = found.text;
  const v = last.value;
  const head = v.slice(0, found.kept);
  const m = BLOCK_ID_RE.exec(head);
  if (m) {
    const at = sourceAt(last, source, m.index);
    const before = charBefore(source, at);
    if (at < 0 || (before !== null && before !== ' ' && before !== '\t')) return null;
    return { id: m[1], form: 'inline', paragraph: p, text: last, caret: at, cut: m.index, end: markerEnd(v, m.index, m[1]) };
  }
  const nl = head.lastIndexOf('\n');
  const line = head.slice(nl + 1);
  const alone = STANDALONE_RE.exec(line);
  const prev = p.children[p.children.indexOf(last) - 1];
  if (!alone || (nl < 0 && prev?.type !== 'break')) return null;
  const at = sourceAt(last, source, nl + 1);
  if (at < 0 || charBefore(source, at) === '\\') return null;
  return { id: alone[1], form: 'last-line', paragraph: p, text: last, caret: at, cut: nl, end: markerEnd(v, nl + 1, alone[1]) };
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
    return next?.type === 'paragraph' && standaloneId(next, source, parent) != null ? next : null;
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
    if (parent.type === 'root' && startsAsCode(p, source)) {   // what the render's rule stripping makes of it
      entries.push(tagged(p, parent, 'code'));
      return;
    }
    if (standaloneId(p, source, parent) != null) {
      if (!claimed.has(p)) orphans.push(standalone(p, parent, source));
      return;
    }
    if (parent.type === 'listItem' && parent.children[0] === p) return;   // the list item is the block
    entries.push({ node: p, parent, kind: 'paragraph', marker: inlineMarker(p, source) || claimNext(p, parent), textLine: p });
  };

  const listItem = (item, parent) => {
    const fp = item.children[0];
    const first = fp?.type === 'paragraph' && standaloneId(fp, source, item) == null && !isRuleOnly(fp) ? fp : null;
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
      // the access rules after it (if any) stay: the editor shows them
      const v = m.text.value;
      const after = v.slice(m.end);
      m.text.value = v.slice(0, m.cut).trimEnd() + (after.trim() === '' ? '' : after);
    } else {   // a last-line marker that is a text node of its own, after a hard break
      const at = p.children.indexOf(m.text);
      const after = m.text.value.slice(m.end);
      p.children.splice(at - 1, 2, ...(after.trim() === '' ? [] : [{ type: 'text', value: after }]));
      const last = p.children[at - 2];
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
