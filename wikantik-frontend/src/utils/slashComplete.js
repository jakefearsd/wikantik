import { syntaxTree } from '@codemirror/language';
import { fuzzyRank } from './fuzzy';

const CODE_NODES = new Set(['FencedCode', 'CodeBlock', 'InlineCode', 'CodeText', 'CodeMark', 'URL', 'Autolink']);

/** True when a slash at {@code slashPos} may open the menu: not in frontmatter, code, URLs or math. */
export function slashAllowed(state, slashPos) {
  const doc = state.doc;
  if (doc.line(1).text.trim() === '---') {
    let closed = false;
    for (let n = 2; n <= doc.lines; n += 1) {
      const line = doc.line(n);
      if (line.from > slashPos) break;
      if (line.text.trim() === '---') { closed = true; if (line.to >= slashPos) return false; break; }
    }
    if (!closed) return false;
  }
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
      .map((c) => ({ c, r: fuzzyRank(c.slashLabel || c.title, query) }))
      .filter((x) => x.r >= 0)
      .sort((a, b) => a.r - b.r || (a.c.slashLabel || a.c.title).localeCompare(b.c.slashLabel || b.c.title))
      .map(({ c }) => ({
        label: c.slashLabel || c.title,
        detail: c.keys,
        apply: (view, _completion, _from, to) => {
          view.dispatch({ changes: { from: slashPos, to, insert: '' } });
          run(c.id);
        },
      }));
    return options.length ? { from: slashPos, options, filter: false } : null;
  };
}
