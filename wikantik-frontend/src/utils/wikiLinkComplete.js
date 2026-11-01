// CodeMirror 6 completion source for internal links. Triggers:
//   [[frag           → live page search (spaces ignored: page names are CamelCase); inserts native [[Name]]
//   [[Page#frag      → the target page's h2/h3 headings; inserts [[Page#Heading]] ([[#Heading]] on this page)
//   [[Page#^frag     → the target page's blocks; inserts [[Page#^id]] ([[#^id]] lists this buffer's blocks), first
//                      giving an id-less block an id. The server decides both: for another page its saved text (GET
//                      and POST /blocks; only when it says canAssign, otherwise listed disabled), for this page the
//                      unsaved buffer (POST /blocks/draft — a marker edit applied in the same transaction as the link)
//   ](frag           → pages + this page's attachments; replaces only the link target
//   ](Page#frag      → headings of Page; ](#frag → headings of the page being edited
// Anchors come from headingsFromMarkdown (the page view's ids). Results are ranked server-side, so
// CodeMirror's own filtering is disabled (filter: false keeps our order).
import { startCompletion, closeCompletion } from '@codemirror/autocomplete';
import { StateEffect, StateField } from '@codemirror/state';
import { titleToSlug, isValidSlug } from './slugUtils';
import { revealEffects } from './markdownFold';
import { isBlockRef } from './blockIds';
import { draftBlockLink } from './draftBlockLink';

const MAX_OPTIONS = 20;
const SEARCH_DEBOUNCE_MS = 150;
// `|` is excluded so completion stops once an alias is being typed (`[[Name|al`) and never overwrites it.
const WIKI_TRIGGER = /\[\[([^\]\n#|]*)(?:#([^\]\n|]*))?$/;
const TARGET_TRIGGER = /\]\(([^)\s#]*)(?:#([^)\s]*))?$/;
const HAS_SCHEME_OR_ROOT = /^(?:[a-z][a-z0-9+.-]*:|\/)/i;

const BLOCK_LABEL_CHARS = 120;
const NO_ID_DETAIL = 'no id — needs edit access';
const PICK_AGAIN = 'This page changed — pick the block again';
const TEXT_CHANGED = 'The text changed — pick the block again';
/** What a refused draft link of this buffer's block says (draftBlockLink's codes). */
const LOCAL_REASONS = {
  changed: TEXT_CHANGED,
  'no-block': "Couldn't locate this block — pick it again",
  refused: "Couldn't add a block id here",
};
const ASSIGN_REASONS = {
  stale: PICK_AGAIN,
  derived: "This page is generated; block ids can't be added here",
  'block-not-located': "Couldn't locate this block — pick it again",
};

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

async function safely(label, promiseFn) {
  try {
    return await promiseFn();
  } catch (err) {
    console.warn(`[link-complete] ${label} failed`, err?.message || err);
    return null;
  }
}

// Brackets and backslashes in link text would end or corrupt the link.
export const escapeLinkText = (text) => text.replace(/[\\[\]]/g, '\\$&');

// Heading text goes inside [[ ]], where brackets, pipes and backslashes would end or split the link.
const wikiHeadingText = (text) => text.replace(/[\\[\]|]/g, '');

function newPageOption(fragment, names, applyFor) {
  const text = (fragment || '').trim();
  if (!text) return null;
  const slug = titleToSlug(text);
  if (!isValidSlug(slug)) return null;
  const taken = new Set(names.map((n) => n.toLowerCase()));
  if (taken.has(slug.toLowerCase()) || taken.has(text.toLowerCase())) return null;
  return { label: `Link to new page: ${slug}`, type: 'newpage', apply: applyFor(slug, text) };
}

// The typed `[[Page#^q` spans of picks waiting on a server assign, mapped through every edit made meanwhile, so the
// link lands where the user left it. A span is `broken` once an edit lands strictly inside it (or replaces any of it);
// edits elsewhere — including right before or right after it — only move it.
const trackSpan = StateEffect.define();
const untrackSpan = StateEffect.define();
function mapSpan(span, changes) {
  let broken = false;
  changes.iterChangedRanges((fromA, toA) => { if (fromA < span.to && toA > span.from) broken = true; });
  return broken ? { ...span, broken } : { from: changes.mapPos(span.from, 1), to: changes.mapPos(span.to, -1), broken };
}
const pendingSpans = StateField.define({
  create: () => new Map(),
  update(spans, tr) {
    let next = spans;
    if (tr.docChanged && spans.size) next = new Map([...spans].map(([k, sp]) => [k, sp.broken ? sp : mapSpan(sp, tr.changes)]));
    for (const e of tr.effects) {
      if (e.is(trackSpan)) next = new Map(next).set(e.value.key, { from: e.value.from, to: e.value.to, broken: false });
      else if (e.is(untrackSpan)) { next = new Map(next); next.delete(e.value); }
    }
    return next;
  },
});
let lastSpanKey = 0;
/** Starts tracking [from, to) in `view` (installing the field on first use); returns the span's key. */
function trackPending(view, from, to) {
  if (view.state.field(pendingSpans, false) === undefined) view.dispatch({ effects: StateEffect.appendConfig.of(pendingSpans) });
  lastSpanKey += 1;
  view.dispatch({ effects: trackSpan.of({ key: lastSpanKey, from, to }) });
  return lastSpanKey;
}

/** A block's text as one line of at most 120 chars; a block with no text (an empty table, …) shows its kind. */
function blockLabel(b) {
  const text = (b.text || '').replace(/\s+/g, ' ').trim();
  if (!text) return `(${b.kind})`;
  return text.length > BLOCK_LABEL_CHARS ? `${text.slice(0, BLOCK_LABEL_CHARS - 1)}…` : text;
}

/**
 * @param {object} deps
 * @param {(q: string) => Promise<string[]>} deps.searchPages  ranked, ACL-filtered page names
 * @param {(page: string|null) => Promise<Array<{text: string, id: string|null}>>} deps.getHeadings
 *        headings of `page`, or of the page being edited when null
 * @param {() => string[]} [deps.getAttachmentNames]  attachment file names of the page being edited
 * @param {(page: string|null, q?: string) => Promise<{version?: number, canAssign: boolean, local?: boolean,
 *          complete?: boolean, blocks: Array<{index: number, id: string|null, kind: string, text: string}>}|null>}
 *        [deps.getBlocks]  blocks of `page`, or of the page being edited when null (`local: true` for the live buffer:
 *        indices are the buffer's own, and an id is added by editing it); another page's `index`/`version` are the
 *        server's. `complete: false` marks a listing cut at its limit: the source then asks again with `q`, the
 *        server-side search (whose rows it does not filter again)
 * @param {(text: string, offset: number) => Promise<{id: string, change: object|null, block: object}>} [deps.draftLink]
 *        the server's link to the block at `offset` of this buffer's `text` (POST /blocks/draft); rejects as the request
 * @param {(page: string, version: number, index: number) => Promise<{id: string}>} [deps.assignBlock]
 *        gives block `index` of `page` (at `version`) an id on the server; rejects with the request's error
 * @param {(message: string) => void} [deps.notify]  shows a message to the user (a toast)
 */
export function createWikiLinkSource({
  searchPages, getHeadings, getAttachmentNames = () => [], getBlocks = async () => null,
  draftLink = async () => { throw new Error('block ids cannot be read here'); },
  assignBlock = async () => { throw new Error('block ids cannot be assigned here'); },
  notify = (message) => console.warn(`[link-complete] ${message}`),
}) {
  async function headingOptions(page, fragment, applyFor) {
    const headings = await safely('heading lookup', () => getHeadings(page || null));
    if (!headings) return null;
    const frag = fragment.toLowerCase();
    return headings
      .filter((h) => h.id && h.text.toLowerCase().includes(frag))
      .slice(0, MAX_OPTIONS)
      .map((h) => ({ label: h.text, detail: `#${h.id}`, type: 'heading', apply: applyFor(h) }));
  }

  /**
   * Links the picked block of this buffer. The server reads the buffer (as it is now — it must still be the text the
   * listing came from) and answers the block's id, or the edit that gives it one; that edit and the link go in ONE
   * transaction (one undo step), unfolding whatever hides the edit. Edits made meanwhile are mapped; one to the block,
   * or to the typed link, abandons the pick.
   */
  async function linkLocalBlock(view, listing, b, linkTo, from, to) {
    if (listing.text != null && view.state.doc.toString() !== listing.text) {
      notify(TEXT_CHANGED);
      return;
    }
    const key = trackPending(view, from, to);
    const release = (spec = {}) => view.dispatch({ ...spec, effects: [...(spec.effects || []), untrackSpan.of(key)] });
    let link;
    try {
      link = await draftBlockLink(view, b.from, draftLink);
    } catch (err) {
      release();
      console.warn('[link-complete] could not link a block of this page', b.index, err?.code, err?.cause?.message || err?.message || err);
      notify(LOCAL_REASONS[err?.code] || "Couldn't add a block id — try again");
      return;
    }
    const span = view.state.field(pendingSpans).get(key);
    if (span.broken) {
      release();
      console.warn('[link-complete] the link text was edited while the block id was read; not inserting', link.id);
      notify(TEXT_CHANGED);
      return;
    }
    const edits = link.change ? [link.change] : [];
    const text = linkTo(link.id);
    const changes = view.state.changes([...edits, { from: span.from, to: span.to, insert: text }]);
    release({
      changes,
      // never edit text hidden inside a fold (the marker may go under a folded heading): unfold it in the same step
      effects: edits.flatMap((e) => [...revealEffects(view.state, e.from), ...revealEffects(view.state, e.to)]),
      selection: { anchor: changes.mapPos(span.from, -1) + text.length },
      userEvent: 'input.complete',
    });
  }

  /**
   * Asks the server for an id on another page's block, then links it where the completion was: the typed span is
   * tracked through edits made meanwhile, and the link is dropped only when that span itself was edited.
   */
  async function linkRemoteBlock(view, page, version, b, linkTo, from, to) {
    const key = trackPending(view, from, to);
    const release = (spec = {}) => view.dispatch({ ...spec, effects: untrackSpan.of(key) });
    let id;
    try {
      ({ id } = await assignBlock(page, version, b.index));
    } catch (err) {
      release();
      const reason = err?.status === 409 ? err.body?.reason : null;
      console.warn('[link-complete] could not add a block id', page, b.index, reason || err?.message || err);
      if (reason === 'stale') {
        // the listing was taken at an older version: fetch it again (the assign dropped the cached one) and reopen it
        await safely('block re-fetch', () => getBlocks(page));
      }
      notify(ASSIGN_REASONS[reason] || `Couldn't add a block id to ${page}`);
      if (reason === 'stale') {
        try { startCompletion(view); } catch (e) { console.warn('[link-complete] could not reopen completion', e?.message || e); }
      }
      return;
    }
    const span = view.state.field(pendingSpans).get(key);
    if (span.broken) {
      release();
      console.warn('[link-complete] the link text was edited while adding a block id; not inserting', page, id);
      notify(TEXT_CHANGED);
      return;
    }
    const link = linkTo(id);
    const { head } = view.state.selection.main;
    const caretAtSpan = head >= span.from && head <= span.to;   // only then does the caret follow the link
    release({
      changes: { from: span.from, to: span.to, insert: link },
      ...(caretAtSpan ? { selection: { anchor: span.from + link.length } } : {}),
      userEvent: 'input.complete',
    });
  }

  /**
   * The listing to offer: the page's cached listing when it is complete (or there is nothing to search for), else —
   * after the search debounce — the server's own `q` search, which matches whole block text, not the 120-char
   * excerpt, so it is not filtered again. `{ listing, narrowed }`, or null.
   */
  async function blockListing(page, fragment, context) {
    const listing = await safely('block lookup', () => getBlocks(page || null));
    if (!listing) return null;
    if (listing.complete !== false || !fragment) return { listing, narrowed: false };
    await sleep(SEARCH_DEBOUNCE_MS);
    if (context.aborted) return null;
    const narrowed = await safely('block search', () => getBlocks(page, fragment));
    return narrowed ? { listing: narrowed, narrowed: true } : null;
  }

  async function blockOptions(page, fragment, context) {
    const found = await blockListing(page, fragment, context);
    if (!found) return null;
    const { listing, narrowed } = found;
    const local = !page || listing.local === true;
    const frag = fragment.toLowerCase();
    const linkTo = (id) => `[[${page}#^${id}]]`;
    return (listing.blocks || [])
      .filter((b) => narrowed || (b.text || '').toLowerCase().includes(frag) || (b.id || '').toLowerCase().includes(frag))
      .slice(0, MAX_OPTIONS)
      .map((b) => {
        const label = blockLabel(b);
        if (b.id) return { label, detail: `^${b.id}`, type: 'block', apply: linkTo(b.id) };
        if (local) {
          return {
            label, type: 'block',
            apply: (view, _c, from, to) => {
              closeCompletion(view);   // nothing is inserted until the server answers; a second pick must not re-link
              linkLocalBlock(view, listing, b, linkTo, from, to)
                .catch((err) => console.warn('[link-complete] could not link the block', b.index, err?.message || err));
            },
          };
        }
        if (listing.canAssign) {
          return {
            label, type: 'block',
            apply: (view, _c, from, to) => {
              closeCompletion(view);   // nothing is inserted until the server answers; a second pick must not re-assign
              linkRemoteBlock(view, page, listing.version, b, linkTo, from, to)
                .catch((err) => console.warn('[link-complete] could not link the block', page, b.index, err?.message || err));
            },
          };
        }
        // Listed so the block can be found, but it cannot be linked: picking it does nothing (the popup stays open).
        return {
          label, detail: NO_ID_DETAIL, type: 'block', disabled: true,
          info: 'This block has no id yet, and you need edit access to that page to give it one.',
          apply: () => {},
        };
      });
  }

  async function pageNames(fragment, context) {
    await sleep(SEARCH_DEBOUNCE_MS);
    if (context.aborted) return null;
    const names = await safely('page search', () => searchPages(fragment.replace(/\s+/g, '')));
    return context.aborted ? null : names;
  }

  const result = (from, options, to) => {
    if (!options || !options.length) return null;
    return to === undefined ? { from, options, filter: false } : { from, to, options, filter: false };
  };

  // closeBrackets types `]]` after an opening `[[`; the completion must replace it or it is left behind.
  const autoClosedBrackets = (context) => (
    context.state.doc.sliceString(context.pos, context.pos + 2) === ']]' ? context.pos + 2 : undefined
  );

  async function completeWiki(match, context) {
    const to = autoClosedBrackets(context);
    const [, page, heading] = WIKI_TRIGGER.exec(match.text);
    if (isBlockRef(heading)) {
      const options = await blockOptions(page, heading.slice(1), context);
      return context.aborted ? null : result(match.from, options, to);
    }
    if (heading !== undefined) {
      const options = await headingOptions(page, heading, (h) => `[[${page}#${wikiHeadingText(h.text)}]]`);
      return context.aborted ? null : result(match.from, options, to);
    }
    const names = await pageNames(page, context);
    if (!names) return null;
    const options = names.slice(0, MAX_OPTIONS).map((name) => ({ label: name, type: 'wikilink', apply: `[[${name}]]` }));
    const created = newPageOption(page, names, (slug, text) => `[[${text}]]`);
    return result(match.from, created ? [...options, created] : options, to);
  }

  async function completeTarget(match, context) {
    const [, target, heading] = TARGET_TRIGGER.exec(match.text);
    if (HAS_SCHEME_OR_ROOT.test(target)) return null;
    const from = match.from + 2; // replace the link target only, never the "]("
    if (heading !== undefined) {
      const options = await headingOptions(target, heading, (h) => (target ? `${target}#${h.id}` : h.id));
      return context.aborted ? null : result(target ? from : from + 1, options);
    }
    const names = await pageNames(target, context);
    if (!names) return null;
    const frag = target.toLowerCase();
    const pages = names.slice(0, MAX_OPTIONS).map((name) => ({ label: name, type: 'wikilink', apply: name }));
    const attachments = getAttachmentNames()
      .filter((n) => n.toLowerCase().includes(frag))
      .map((n) => ({ label: n, type: 'attachment', apply: n }));
    const created = newPageOption(target, names, (slug) => slug);
    return result(from, [...pages, ...attachments, ...(created ? [created] : [])]);
  }

  return async (context) => {
    const wiki = context.matchBefore(WIKI_TRIGGER);
    if (wiki) return completeWiki(wiki, context);
    const target = context.matchBefore(TARGET_TRIGGER);
    if (target) return completeTarget(target, context);
    return null;
  };
}
