/**
 * Obsidian inline `#tags` for the editor preview: turns a `#tag` in prose into the link the reader renders
 * (`<a class="tag inline-tag" href="/search?tag=a-b">#a/b</a>`, the server's `InlineTagNodeRenderer`). The rule is the
 * server's `InlineTagSyntax`: the `#` follows the start of its line or whitespace, the tag is `[\p{L}\p{N}_/-]+` with at
 * least one character that is not a digit (`#123` is text, `#fff` is a tag), and an ATX heading line is skipped whole.
 * Code, math and link text are never text nodes of the right kind, so they are skipped by construction; the shared
 * fixture `__fixtures__/inline-tags.json` (also run by `InlineTagParityTest`) pins the two sides together.
 *
 * This runs after the caller's stack (EditorPreviewMarkdown), on the mdast `text` nodes it leaves, and
 * `%%comments%%` are already gone.
 */
import { normaliseTag } from './inlineTags';

const TAG = /(?<![^\s])#([\p{L}\p{N}_/-]+)/gu;
const HAS_NON_DIGIT = /[\p{L}_-]/u;
/** Nodes whose text is never prose: code, math, headings (checked separately), links and definitions. */
const SKIP = new Set(['inlineCode', 'code', 'math', 'inlineMath', 'link', 'linkReference', 'definition', 'html', 'yaml']);
const LINE_START_PARENTS = new Set(['paragraph', 'tableCell']);

function tagNode(written) {
  return {
    type: 'inlineTag',
    data: {
      hName: 'a',
      hProperties: { className: ['tag', 'inline-tag'], href: `/search?tag=${encodeURIComponent(normaliseTag(written))}` },
    },
    children: [{ type: 'text', value: `#${written}` }],
  };
}

/** Whether a `#` at the very start of a text node starts a line: first in its paragraph, or right after a hard break. */
function startsLine(parent, index) {
  if (!LINE_START_PARENTS.has(parent.type)) return false;
  return index === 0 || parent.children[index - 1].type === 'break';
}

/** The text node's pieces: its text, with a tag node wherever a tag is. Returns null when it holds none. */
function split(node, atLineStart, source) {
  const value = node.value;
  if (!value.includes('#')) return null;
  const start = node.position?.start?.offset;
  const end = node.position?.end?.offset;
  // A backslash escape (`\#foo`) is text to the reader: value and source then differ, so look at the source itself.
  if (source != null && start != null && end != null && end - start !== value.length && source.slice(start, end).includes('\\#')) {
    return null;
  }
  const out = [];
  let last = 0;
  for (const m of value.matchAll(TAG)) {
    if (!HAS_NON_DIGIT.test(m[1])) continue;
    if (m.index === 0 && !atLineStart) continue;
    if (m.index > last) out.push({ type: 'text', value: value.slice(last, m.index) });
    out.push(tagNode(m[1]));
    last = m.index + m[0].length;
  }
  if (!out.length) return null;
  if (last < value.length) out.push({ type: 'text', value: value.slice(last) });
  return out;
}

function isAtxHeading(node, source) {
  const at = node.position?.start?.offset;
  return source == null || at == null || source[at] === '#' || /^\s{0,3}#/.test(source.slice(at, at + 4));
}

function walk(parent, source) {
  const kids = parent.children;
  if (!kids) return;
  for (let i = 0; i < kids.length; i += 1) {
    const child = kids[i];
    if (SKIP.has(child.type) || (child.type === 'heading' && isAtxHeading(child, source))) continue;
    if (child.type === 'text') {
      const pieces = split(child, startsLine(parent, i), source);
      if (pieces) {
        kids.splice(i, 1, ...pieces);
        i += pieces.length - 1;
      }
    } else {
      walk(child, source);
    }
  }
}

/** remark plugin: `#tag` -> `<a class="tag inline-tag" href="/search?tag=…">#tag</a>`. */
export default function remarkInlineTags() {
  return (tree, file) => {
    walk(tree, file?.value == null ? null : String(file.value));
  };
}
