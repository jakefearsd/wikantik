// The model behind the link card: reads the link, wikilink, embed or image at a document position, and writes a
// (possibly patched) model back as Markdown. A model that was read and not changed writes back as its own source,
// and a changed one re-emits only the part whose field changed: the rest is copied from the source as written.
import { syntaxTree } from '@codemirror/language';
import { parseWikiLink, isImageFileName } from './wikiLinkSyntax';

const KIND = { WikiLink: 'wiki', WikiEmbed: 'embed', Link: 'link', Image: 'image' };
const SIZE = /^\d{1,5}(?:x\d{1,5})?$/;
const BRACKET = /[[\]]/;
const BLANK_LINE = /\n[ \t]*\n/;

const isWiki = (model) => model.kind === 'wiki' || model.kind === 'embed';
const blankToNull = (s) => (s == null || s === '' ? null : s);
const trimmed = (s) => blankToNull(s == null ? null : String(s).trim());

/**
 * The link at `pos`, or null. A position at either end of a link counts as on it, and of nested links
 * (`[![alt](img)](url)`) the innermost one holding `pos` wins. Null for anything that is not an inline link, wikilink,
 * embed or image: reference-style links, autolinks, bare URLs, `WikiLinkText` (a bracket pair that is no wikilink)
 * and code, none of which has such a node. Costs the nesting depth at `pos`, never the document.
 *
 * Fields by kind — `wiki`/`embed`: `target`, `heading`, `alias`, `size` (the `300` or `300x200` of an embedded image
 * file, as text; `alias` is then null); `link`: `text`, `url`, `title`; `image`: `alt`, `url`, `title`. An absent field is
 * null, except `target`, `url`, `text` and `alt`, which are then ''. `original` is what was read; `writeLink`
 * compares against it, so patch a copy (`{ ...model, target }`) and leave `original` alone.
 */
export function readLinkAt(state, pos) {
  const tree = syntaxTree(state);
  for (const side of [1, -1]) {
    for (let node = tree.resolveInner(pos, side); node; node = node.parent) {
      const kind = KIND[node.name];
      if (!kind) continue;
      const source = state.sliceDoc(node.from, node.to);
      const read = isWiki({ kind }) ? readWiki(source, node) : readMarkdown(source, node);
      if (read) return { kind, from: node.from, to: node.to, source, ...read.fields, original: Object.freeze(read) };
    }
  }
  return null;
}

function readWiki(source, node) {
  const ref = parseWikiLink(source);
  const m = /^(!?\[\[)([^]*)\]\]$/.exec(source);
  if (!ref || !m) return null;
  const inner = m[2];
  const pipe = inner.indexOf('|');
  const escaped = pipe > 0 && inner[pipe - 1] === '\\';
  const sepFrom = pipe < 0 ? inner.length : pipe - (escaped ? 1 : 0);
  // As the preview draws it: only an embedded image file reads `|300` as a size; elsewhere it is display text.
  const size = ref.size && ref.embed && isImageFileName.test(ref.fileName) ? ref.alias : null;
  return {
    fields: { target: ref.target, heading: ref.heading, alias: size ? null : ref.alias, size },
    parts: {
      open: m[1],
      target: inner.slice(0, sepFrom),
      sep: pipe < 0 ? '' : inner.slice(sepFrom, pipe + 1),
      alias: pipe < 0 ? '' : inner.slice(pipe + 1),
      // A bare pipe would end a table cell, so a link in one separates with `\|`.
      newSep: inTable(node) ? '\\|' : '|',
    },
  };
}

function inTable(node) {
  for (let n = node.parent; n; n = n.parent) if (n.name === 'Table') return true;
  return false;
}

/** The inline form only, `[text](dest "title")`: a reference-style or shortcut link has no `(` right after its `]`. */
function readMarkdown(source, node) {
  const marks = [];
  let url = null;
  let title = null;
  for (let child = node.firstChild; child; child = child.nextSibling) {
    if (child.name === 'LinkMark') marks.push(child);
    else if (child.name === 'URL') url = child;
    else if (child.name === 'LinkTitle') title = child;
    else if (child.name === 'LinkLabel') return null;
  }
  if (marks.length !== 4 || marks[1].to !== marks[2].from) return null;
  const at = (p) => p - node.from;
  const textTo = at(marks[1].from);
  const destFrom = at((url ?? title ?? marks[3]).from);
  const destTo = url ? at(url.to) : destFrom;
  const titleFrom = title ? at(title.from) : destTo;
  const titleTo = title ? at(title.to) : destTo;
  const parts = {
    open: source.slice(0, at(marks[0].to)),
    text: source.slice(at(marks[0].to), textTo),
    mid: source.slice(textTo, destFrom),
    dest: source.slice(destFrom, destTo),
    gap: source.slice(destTo, titleFrom),
    title: source.slice(titleFrom, titleTo),
    tail: source.slice(titleTo),
  };
  const text = parts.text.replace(/\\([[\]])/g, '$1');
  const fields = {
    ...(node.name === 'Image' ? { alt: text } : { text }),
    url: /^<[^]*>$/.test(parts.dest) ? parts.dest.slice(1, -1) : parts.dest,
    title: title ? unquoted(parts.title) : null,
  };
  return { fields, parts };
}

/** A link title without its delimiters (`"…"`, `'…'` or `(…)`) and without the backslashes escaping them inside. */
function unquoted(titleSource) {
  const open = titleSource[0];
  const delimiters = open === '(' ? '()' : open;
  return titleSource.slice(1, -1).replace(/\\(.)/g, (whole, ch) => (delimiters.includes(ch) ? ch : whole));
}

/** The fields of `model` as they will be written: trimmed where the syntax trims, '' and undefined as null. */
function fieldsOf(model) {
  if (isWiki(model)) {
    return {
      target: String(model.target ?? '').trimEnd(),
      heading: trimmed(model.heading),
      alias: trimmed(model.alias),
      size: trimmed(model.size),
    };
  }
  return { text: String((model.kind === 'image' ? model.alt : model.text) ?? ''), url: String(model.url ?? ''), title: blankToNull(model.title) };
}

/** `text` with each character `chars` matches escaped, and a backslash that would escape what follows it doubled. */
function escaped(text, chars) {
  const out = text.replace(new RegExp(`\\\\(?=${chars.source})|${chars.source}`, 'g'), '\\$&');
  return /(?:^|[^\\])(?:\\\\)*\\$/.test(out) ? `${out}\\` : out;
}

const WIKI_PARTS = { target: '', sep: '', alias: '', newSep: '|' };
const MARKDOWN_PARTS = { text: '', mid: '](', dest: '', gap: '', title: '', tail: ')' };

/**
 * The Markdown for `model`. Exactly `model.source` when no field differs from what was read; otherwise only the part
 * of a changed field is written anew. A model without `original` (one that was built, not read) is written whole.
 * Does not validate: `linkChange` does.
 */
export function writeLink(model) {
  const now = fieldsOf(model);
  const was = model.original?.fields ?? {};
  const changed = (...names) => !model.original || names.some((name) => now[name] !== was[name]);
  if (!changed(...Object.keys(now))) return model.source;
  return isWiki(model) ? writeWiki(model, now, changed) : writeMarkdown(model, now, changed);
}

function writeWiki(model, now, changed) {
  const parts = { open: model.kind === 'embed' ? '![[' : '[[', ...WIKI_PARTS, ...model.original?.parts };
  const target = changed('target', 'heading') ? now.target + (now.heading ? `#${now.heading}` : '') : parts.target;
  let rest = parts.sep + parts.alias;
  if (changed('alias', 'size')) {
    const shown = wikiSlot(model, now);
    rest = shown == null ? '' : (parts.sep || parts.newSep) + shown;
  }
  return `${parts.open}${target}${rest}]]`;
}

/** What goes after the pipe: display text and size share that one slot, and whichever was changed takes it. */
function wikiSlot(model, now) {
  const sizeChanged = now.size !== (model.original?.fields.size ?? null);
  return sizeChanged ? now.size ?? now.alias : now.alias ?? now.size;
}

function writeMarkdown(model, now, changed) {
  const parts = { open: model.kind === 'image' ? '![' : '[', ...MARKDOWN_PARTS, ...model.original?.parts };
  const text = markdownText(model, now, parts);
  let dest = parts.dest;
  if (changed('url')) dest = /[\s()]/.test(now.url) ? `<${now.url}>` : now.url;
  let title = parts.gap + parts.title;
  if (changed('title')) title = now.title == null ? '' : `${parts.gap || ' '}"${escaped(now.title, /"/)}"`;
  // With no destination a title would be read as the destination; `<>` is the empty one.
  if (dest === '' && now.title != null) dest = '<>';
  return `${parts.open}${text}${parts.mid}${dest}${title}${parts.tail}`;
}

/** The link text (an image's alt text) as source: what was written when unchanged, else with its brackets escaped. */
function markdownText(model, now, parts) {
  const unchanged = model.original && now.text === model.original.fields[model.kind === 'image' ? 'alt' : 'text'];
  return unchanged ? parts.text : escaped(now.text, BRACKET);
}

/**
 * The text that stays when the link is removed: a wikilink's display text, else its page (its heading for a same-page
 * link); a Markdown link's text and an image's alt text, as source.
 */
export function unwrapLink(model) {
  const now = fieldsOf(model);
  if (isWiki(model)) return now.alias ?? (now.target || now.heading || '');
  return markdownText(model, now, { ...MARKDOWN_PARTS, ...model.original?.parts });
}

/**
 * The text change that turns `model` into `{ ...model, ...patch }`.
 *
 * @throws {RangeError} with a message for the author when a changed field holds what the syntax cannot carry — a
 * square bracket or line break in a wikilink, `|` or `#` in a page name, a line break in an address and so on.
 */
export function linkChange(model, patch) {
  const next = { ...model, ...patch };
  const now = fieldsOf(next);
  const was = model.original?.fields ?? {};
  const changed = (name) => !model.original || now[name] !== was[name];
  if (isWiki(next)) checkWiki(next, now, changed);
  else checkMarkdown(next, now, changed);
  return { from: model.from, to: model.to, insert: writeLink(next) };
}

function refuse(message) {
  throw new RangeError(message);
}

/** Only changed fields are checked, so a link is never refused for what it already held. */
function checkWiki(model, now, changed) {
  const check = (name, label, noPipe) => {
    const value = now[name];
    if (value == null || !changed(name)) return;
    if (value.includes('\n')) refuse(`${label} cannot contain a line break.`);
    if (BRACKET.test(value)) refuse(`${label} cannot contain a square bracket.`);
    if (noPipe && value.includes('|')) refuse(`${label} cannot contain |${name === 'alias' ? ' inside a table' : ''}.`);
  };
  check('target', 'The page name', true);
  check('heading', 'The heading', true);
  check('alias', 'The display text', model.original?.parts.newSep === '\\|');
  if (changed('target')) {
    if (now.target.includes('#')) refuse('The page name cannot contain #; the heading has its own field.');
    if (/^\s/.test(now.target)) refuse('The page name cannot start with a space.');
  }
  if (now.target === '' && now.heading == null) refuse('A wikilink needs a page or a heading.');
  if (changed('size') && now.size != null && !SIZE.test(now.size)) {
    refuse('The size is a width in pixels, or width x height: 300 or 300x200.');
  }
  // The net under the rules above: what is written must read back as the same link.
  const written = parseWikiLink(writeLink(model));
  const slot = wikiSlot(model, now);
  if (!written || written.target !== now.target || written.heading !== now.heading
    || (changed('alias') || changed('size') ? written.alias !== slot : false)) {
    refuse('That would not read back as the same link.');
  }
}

function checkMarkdown(model, now, changed) {
  const textLabel = model.kind === 'image' ? 'The alt text' : 'The text';
  if (changed('text') && BLANK_LINE.test(now.text)) refuse(`${textLabel} cannot contain a blank line.`);
  if (changed('title') && now.title != null && BLANK_LINE.test(now.title)) refuse('The title cannot contain a blank line.');
  if (!changed('url')) return;
  if (now.url.includes('\n')) refuse('The address cannot contain a line break.');
  if (/[<>]/.test(now.url) && (/[\s()]/.test(now.url) || now.url.startsWith('<'))) {
    refuse('The address cannot contain < or > next to a space or a parenthesis; write them as %3C and %3E.');
  }
}
