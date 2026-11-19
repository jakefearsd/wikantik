// The editor's half of the link card (Rich mode): which link the card is open on, kept through edits, and the request
// to open it. The card itself is React (components/editor/LinkCard.jsx); it hears of every change through the
// `onOpenLinkCard` callback and reads the open link's current range from `linkCardField` when it writes.
// Imports nothing of the live preview, so its widgets can ask for a card; what shows the card closes it on leaving Rich.
import { StateEffect, StateField, Facet } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { readLinkAt, inTableAt, textOfSource } from '../linkEdit';

/**
 * `{ pos }`: open the card on the link at `pos` (nothing happens when there is no link there). `{ wrap: { from, to } }`:
 * open it on that text, which the card will turn into a link when it is committed — until then nothing is written.
 */
export const openLinkCard = StateEffect.define();
export const closeLinkCard = StateEffect.define();

/**
 * Callbacks `(card | null, view, leaving)`. `(card, view)`: the card opened or its range moved — `{ id, from, to,
 * source }`, plus `wrap: true` for text about to become a link; `id` tells one opening from every other. `(null, view)`:
 * it closed. `(card, view, true)`: another card is about to open in its place — the last moment to write what this one
 * holds, while the field still has its range.
 */
export const onOpenLinkCard = Facet.define();

let openings = 0;

const TEXT_PARTS = new Set(['LinkMark', 'URL', 'LinkTitle', 'Escape']);

/**
 * The card's model for the link at `pos` (linkEdit's `readLinkAt`), or null. `textLocked` is set when a Markdown
 * link's text holds markup of its own (an image, bold): the card shows that text but cannot edit it as plain text.
 */
export function cardModelAt(state, pos) {
  const model = readLinkAt(state, pos);
  if (!model || model.kind !== 'link') return model;
  let link = syntaxTree(state).resolveInner(model.from, 1);
  while (link && !(link.name === 'Link' && link.from === model.from && link.to === model.to)) link = link.parent;
  let textLocked = false;
  for (let n = link?.firstChild; n; n = n.nextSibling) {
    if (!TEXT_PARTS.has(n.name)) { textLocked = true; break; }
  }
  return { ...model, textLocked };
}

/** The card's model for what the field holds: the link read from the document as it is now, or the text to wrap. */
export function cardModelOf(state, card) {
  if (!card) return null;
  if (card.wrap) {
    // Built, not read: it carries whether it will be written in a table cell, where the link escapes its pipes.
    const cell = inTableAt(state, card.from);
    return { kind: 'link', from: card.from, to: card.to, source: card.source, text: textOfSource(card.source, cell), url: '', title: null, pending: true, cell };
  }
  const model = cardModelAt(state, card.from);
  return model && model.from === card.from && model.to === card.to ? model : null;
}

function opened(state, { pos, wrap }) {
  if (wrap) return { id: ++openings, from: wrap.from, to: wrap.to, source: state.sliceDoc(wrap.from, wrap.to), wrap: true };
  const model = cardModelAt(state, pos);
  return model ? { id: ++openings, from: model.from, to: model.to, source: model.source } : null;
}

/**
 * What the card is open on, or null: a link, or (`wrap`) text that will become one. Its range follows every change (a
 * collaborator typing above it); a change to its own text, its deletion or a document turned read-only closes the card.
 */
export const linkCardField = StateField.define({
  create: () => null,
  update(card, tr) {
    let next = card;
    if (next && tr.docChanged) {
      const from = tr.changes.mapPos(next.from, 1);
      const to = tr.changes.mapPos(next.to, -1);
      if (to - from !== next.source.length || tr.state.sliceDoc(from, to) !== next.source) next = null;
      else if (from !== next.from || to !== next.to) next = { ...next, from, to };
    }
    for (const e of tr.effects) {
      if (e.is(closeLinkCard)) next = null;
      else if (e.is(openLinkCard)) next = opened(tr.state, e.value);
    }
    if (next && tr.state.readOnly) next = null;
    return next;
  },
});

const tellHosts = EditorView.updateListener.of((u) => {
  const card = u.state.field(linkCardField, false) ?? null;
  if (card === (u.startState.field(linkCardField, false) ?? null)) return;
  for (const tell of u.state.facet(onOpenLinkCard)) tell(card, u.view);
});

const canShow = (state) => !state.readOnly && state.field(linkCardField, false) !== undefined && state.facet(onOpenLinkCard).length > 0;

/**
 * Before another card opens: the hosts get to write what the open one holds. `positions` (in the document before
 * that write) come back as they are after it — the write is the open card's range alone, so everything past its
 * start moves by the change in length.
 */
function leaveOpenCard(view, positions) {
  const open = view.state.field(linkCardField);
  if (!open) return positions;
  const before = view.state.doc.length;
  for (const tell of view.state.facet(onOpenLinkCard)) tell(open, view, true);
  const grown = view.state.doc.length - before;
  return positions.map((pos) => (pos > open.from ? Math.max(open.from, pos + grown) : pos));
}

/**
 * Opens the card on the link at `pos`. False (and nothing happens) when there is no link there, on a read-only
 * document, or when nothing shows cards: the caller then does what it did before there were cards. A card open on
 * another link is first given the chance to write its changes; one open on this link stays as it is.
 */
export function requestLinkCard(view, pos) {
  if (!canShow(view.state)) return false;
  const model = cardModelAt(view.state, pos);
  if (!model) return false;
  const open = view.state.field(linkCardField);
  if (open && !open.wrap && open.from === model.from && open.to === model.to) return true;
  const [at] = leaveOpenCard(view, [pos]);
  if (!canShow(view.state) || !cardModelAt(view.state, at)) return false;
  view.dispatch({ effects: openLinkCard.of({ pos: at }) });
  return true;
}

const LINK_NODES = new Set(['Link', 'Image', 'WikiLink', 'WikiEmbed']);

/**
 * Whether the text [from, to] can become a link's text. Not an empty range or one across a line break; not one that
 * cuts into a link, or holds a whole one (its brackets would be escaped, and it would stop being a link); and, in a
 * table, not one across a cell boundary — a pipe that is not escaped, or whose escaping backslash the range leaves out
 * — since the link would then swallow the boundary and the row lose a cell. Costs the range, never the document.
 */
function wrappable(state, from, to) {
  if (to <= from) return false;
  const text = state.sliceDoc(from, to);
  if (text.includes('\n')) return false;
  const inLink = (pos) => { const link = readLinkAt(state, pos); return !!link && pos > link.from && pos < link.to; };
  if (inLink(from) || inLink(to)) return false;
  let holdsLink = false;
  syntaxTree(state).iterate({
    from,
    to,
    enter: (node) => {
      if (LINK_NODES.has(node.name) && node.from >= from && node.to <= to) holdsLink = true;
      return !holdsLink;
    },
  });
  if (holdsLink) return false;
  if (inTableAt(state, from) || inTableAt(state, to - 1)) {
    const lineStart = state.doc.lineAt(from).from;
    const before = state.sliceDoc(lineStart, from);
    for (let i = text.indexOf('|'); i >= 0; i = text.indexOf('|', i + 1)) {
      let slashes = 0; // the backslashes right before this pipe, counted back to the line start
      const run = before + text.slice(0, i);
      while (slashes < run.length && run[run.length - 1 - slashes] === '\\') slashes += 1;
      if (i === 0 || slashes % 2 === 0) return false;
    }
  }
  return true;
}

/**
 * Opens the card on the text [from, to], to make a link of it: nothing is written until the card is committed. False
 * (and nothing happens) for a range that cannot become a link's text (`wrappable`: empty, across a line break, cutting
 * into a link or holding one, across a table cell boundary), a read-only document, or when nothing shows cards. A card
 * that is open first writes what it holds, and the range is checked again as that write left it.
 */
export function requestLinkWrap(view, from, to) {
  if (!canShow(view.state) || !wrappable(view.state, from, to)) return false;
  const [a, b] = leaveOpenCard(view, [from, to]);
  if (!canShow(view.state) || !wrappable(view.state, a, b)) return false;
  view.dispatch({ effects: openLinkCard.of({ wrap: { from: a, to: b } }) });
  return true;
}

/** What a click opens a card for: the grid's cell rule stands aside for these (tableGrid.js). */
export const CHIP = '.cm-lp-linkchip, .cm-lp-image-wrap';

/** A plain click on a chip or a drawn image opens its card instead of placing the caret. Ctrl/Cmd-click still follows the link. */
const chipClicks = EditorView.domEventHandlers({
  mousedown(event, view) {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return false;
    const chip = event.target?.closest?.(CHIP);
    if (!chip) return false;
    let opened = false;
    try {
      // posAtDOM gives the chip's start, where the innermost link is the chip itself.
      opened = requestLinkCard(view, view.posAtDOM(chip));
    } catch (err) {
      console.warn('[rich] could not open the link card', err?.message || err);
    }
    if (opened) event.preventDefault(); // the editor keeps its caret; the card takes the focus
    return opened;
  },
});

export const linkCardExtension = [linkCardField, tellHosts, chipClicks];
