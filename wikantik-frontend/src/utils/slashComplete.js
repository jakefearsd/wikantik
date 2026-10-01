import { syntaxTree } from '@codemirror/language';
import { fuzzyRank } from './fuzzy';
import { formatKeys } from './keyHints';
import { styleOf } from './remarkCallouts';
import { frontmatterCloseLine } from './markdownFold';

const ICON_KIND = {
  'insert-table': 'table', 'code-block': 'code', 'math-block': 'math', 'horizontal-rule': 'rule',
  'insert-image': 'image', 'insert-link': 'link',
};

/**
 * The CodeMirror completion {@code type} for a slash command: space-separated classes that become
 * {@code .cm-completionIcon-slash-*} on the option's icon, so the editor theme can draw a per-kind glyph
 * (and tint callouts with their style colour).
 */
export function slashIconType(id) {
  if (/^heading-\d$/.test(id)) return `slash slash-${id}`;
  if (id.startsWith('callout-')) return `slash slash-callout slash-callout-${styleOf(id.slice('callout-'.length))}`;
  return `slash slash-${ICON_KIND[id] || 'command'}`;
}

const CODE_NODES = new Set(['FencedCode', 'CodeBlock', 'InlineCode', 'CodeText', 'CodeMark', 'URL', 'Autolink']);

/** True when a slash at {@code slashPos} may open the menu: not in frontmatter, code, URLs or math. */
export function slashAllowed(state, slashPos) {
  const doc = state.doc;
  const slashLine = doc.lineAt(slashPos).number;
  const close = frontmatterCloseLine((n) => doc.line(n).text, doc.lines);
  if (close > 0 && slashLine <= close) return false;                               // inside frontmatter
  if (close === 0 && doc.lines >= 2 && doc.line(1).text.trim() === '---'
      && /^[A-Za-z_][\w-]*\s*:/.test(doc.line(2).text)) return false;              // frontmatter still being typed
  for (let node = syntaxTree(state).resolveInner(slashPos, 1); node; node = node.parent) {
    if (CODE_NODES.has(node.name)) return false;
  }
  const line = doc.lineAt(slashPos);
  const before = line.text.slice(0, slashPos - line.from);
  if (((before.match(/(?<!\\)\$/g) || []).length % 2) === 1) return false;          // inside $…$
  if (/(^|[\s`])`[^`]*$/.test(before)) return false;                               // unterminated inline code
  let mathFences = 0;
  for (let n = 1; n < line.number; n += 1) if (doc.line(n).text.trim() === '$$') mathFences += 1;
  return mathFences % 2 === 0;                                                      // inside $$ … $$
}

/** The slash menu's logical order (headings, callouts, blocks, media) — used as-is for short queries. */
const SLASH_ORDER = ['heading-1', 'heading-2', 'heading-3', 'callout-note', 'callout-tip', 'callout-info',
  'callout-warning', 'callout-danger', 'insert-table', 'code-block', 'math-block', 'horizontal-rule', 'insert-image',
  'insert-link'];
const orderOf = (id) => { const i = SLASH_ORDER.indexOf(id); return i < 0 ? SLASH_ORDER.length : i; };
const labelOf = (c) => c.slashLabel || c.title;
const byFixedOrder = (a, b) => orderOf(a.id) - orderOf(b.id) || labelOf(a).localeCompare(labelOf(b));

/** CodeMirror completion source that lists the registry's slash commands after a line-initial or spaced "/". */
export function createSlashSource(getCommands, run) {
  return (ctx) => {
    const m = ctx.matchBefore(/(?:^|\s)\/[\w-]*$/);
    if (!m) return null;
    const slashPos = m.text.startsWith('/') ? m.from : m.from + 1;
    if (!slashAllowed(ctx.state, slashPos)) return null;
    const query = ctx.state.sliceDoc(slashPos + 1, ctx.pos);
    const options = getCommands()
      .filter((c) => c.slash)
      .map((c) => ({ c, r: fuzzyRank(labelOf(c), query) }))
      .filter((x) => x.r >= 0)
      // Empty / one-character queries keep the fixed logical order; longer ones rank by match quality.
      .sort((a, b) => (query.length > 1 ? a.r - b.r : 0) || byFixedOrder(a.c, b.c))
      .map(({ c }) => ({
        label: labelOf(c),
        type: slashIconType(c.id),
        detail: c.keys ? formatKeys(c.keys) : undefined,
        apply: (view, _completion, _from, to) => {
          view.dispatch({ changes: { from: slashPos, to, insert: '' } });
          run(c.id);
        },
      }));
    return options.length ? { from: slashPos, options, filter: false } : null;
  };
}
