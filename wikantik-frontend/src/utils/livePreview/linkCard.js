// The editor's half of the link card (Rich mode): which link the card is open on, kept through edits, and the request
// to open it. The card itself is React (components/editor/LinkCard.jsx); it hears of every change through the
// `onOpenLinkCard` callback and reads the open link's current range from `linkCardField` when it writes.
// Imports nothing of the live preview, so its widgets can ask for a card; what shows the card closes it on leaving Rich.
import { StateEffect, StateField, Facet } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { readLinkAt } from '../linkEdit';

/** `{ pos }`: open the card on the link at `pos`. Does nothing when there is no link there. */
export const openLinkCard = StateEffect.define();
export const closeLinkCard = StateEffect.define();

/**
 * Callbacks `(card | null, view)`: the card opened or its link moved (`{ from, to, source, openedAt }`), or it closed
 * (null). `openedAt` is where the link was when the card opened: it tells one opening from the next, never moves.
 */
export const onOpenLinkCard = Facet.define();

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

/**
 * The link the card is open on, or null. Its range follows every change (a collaborator typing above it); a change to
 * the link's own text, its deletion or a document turned read-only closes the card.
 */
export const linkCardField = StateField.define({
  create: () => null,
  update(card, tr) {
    let next = card;
    if (next && tr.docChanged) {
      const from = tr.changes.mapPos(next.from, 1);
      const to = tr.changes.mapPos(next.to, -1);
      next = to - from === next.source.length && tr.state.sliceDoc(from, to) === next.source ? { ...next, from, to } : null;
    }
    for (const e of tr.effects) {
      if (e.is(closeLinkCard)) next = null;
      else if (e.is(openLinkCard)) {
        const model = cardModelAt(tr.state, e.value.pos);
        next = model ? { from: model.from, to: model.to, source: model.source, openedAt: model.from } : null;
      }
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

/**
 * Opens the card on the link at `pos`. False (and nothing happens) when there is no link there, on a read-only
 * document, or when nothing shows cards: the caller then does what it did before there were cards.
 */
export function requestLinkCard(view, pos) {
  const { state } = view;
  if (state.readOnly || state.field(linkCardField, false) === undefined || !state.facet(onOpenLinkCard).length) return false;
  if (!cardModelAt(state, pos)) return false;
  view.dispatch({ effects: openLinkCard.of({ pos }) });
  return true;
}

const CHIP = '.cm-lp-linkchip, .cm-lp-image-wrap';

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
