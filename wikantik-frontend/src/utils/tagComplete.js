// CodeMirror completion source for inline #tags. Typing `#` plus a character in prose offers the wiki's existing tags,
// ranked by how many pages carry them (the structural index's tag distribution, already in that order). Picking one
// inserts the tag as the index names it (its normalised form). It never opens after `# ` (a heading marker), on a
// heading line, in code, URLs or math (`slashAllowed`'s rule), inside a `[[wikilink]]`, or in frontmatter.
import { slashAllowed } from './slashComplete';
import { normaliseTag } from './inlineTags';

const MAX_OPTIONS = 20;
const TRIGGER = /(?:^|\s)#([\p{L}\p{N}_/-]+)$/u;
const ATX_HEADING = /^\s{0,3}#{1,6}\s/;
const VALID_FOR = /^#[\p{L}\p{N}_/-]*$/u;

/** Tags that start with the typed text, then those that contain it; the incoming (count) order is kept within each. */
export function rankTags(tags, typed) {
  const q = normaliseTag(typed);
  const starts = [];
  const contains = [];
  for (const tag of tags) {
    if (tag.startsWith(q)) starts.push(tag);
    else if (tag.includes(q)) contains.push(tag);
  }
  return [...starts, ...contains];
}

/** `getTags` () => string[] of existing tags, most used first. */
export function createTagSource(getTags) {
  return (ctx) => {
    const line = ctx.state.doc.lineAt(ctx.pos);
    const before = line.text.slice(0, ctx.pos - line.from);
    const m = TRIGGER.exec(before);
    if (!m) return null;
    if (ATX_HEADING.test(line.text) || /\[\[[^\]]*$/.test(before)) return null;
    const hashPos = line.from + m.index + m[0].indexOf('#');
    if (!slashAllowed(ctx.state, hashPos)) return null;
    const options = rankTags(getTags() || [], m[1]).slice(0, MAX_OPTIONS)
      .map((tag) => ({ label: `#${tag}`, type: 'tag', apply: `#${tag}` }));
    if (!options.length) return null;
    return { from: hashPos, options, filter: false, validFor: VALID_FOR };
  };
}
