import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { startCompletion } from '@codemirror/autocomplete';
import { linkChange, unwrapLink } from '../utils/linkEdit';
import { cardModelAt, cardModelOf, closeLinkCard, linkCardField, requestLinkCard, requestLinkWrap } from '../utils/livePreview/linkCard';
import { getContext } from '../utils/livePreview/plugin';
import { attachmentUrl, resolveImageSrc } from '../utils/livePreview/ranges';
import { hrefFor } from '../utils/linkInteraction';
import { isImageFileName, parseWikiLink, wikiLinkHref } from '../utils/wikiLinkSyntax';

const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';
const inApp = (path) => (path ? `${BASE}${path}` : null);

/** Where the link leads, as a URL to open, or null (a same-page link, an address the app will not open). */
export function linkCardHref(model, { pageName, attachments } = {}) {
  if (model.kind === 'link') return hrefFor(model.url);
  if (model.kind === 'image') {
    const src = resolveImageSrc(model.url, { pageName, attachments });
    if (!src) return null;
    return /^https?:\/\//i.test(src) ? src : inApp(src.startsWith('/') ? src : null);
  }
  const ref = parseWikiLink(model.source);
  if (!ref || ref.isSamePage) return null;
  if (ref.embed && isImageFileName.test(ref.fileName)) {
    const owner = ref.isAttachment ? ref.pageName : pageName;
    return owner ? inApp(attachmentUrl(owner, ref.fileName)) : null;
  }
  return hrefFor(wikiLinkHref(ref));
}

/** The link's place on screen, for the card to sit under; null when it has none (not drawn, no layout). */
function rectOf(view, { from, to }) {
  try {
    const a = view.coordsAtPos(from, 1);
    const b = view.coordsAtPos(to, -1);
    if (!a || !b) return null;
    return { left: Math.min(a.left, b.left), right: Math.max(a.right, b.right), top: Math.min(a.top, b.top), bottom: Math.max(a.bottom, b.bottom) };
  } catch (err) {
    console.warn('[rich] could not find where the link is drawn; the card opens at the corner', err?.message || err);
    return null;
  }
}

const focusInCard = () => !!document.activeElement?.closest?.('.link-card');

/**
 * The link card of the page editor (Rich mode): what it is open on and what its actions write.
 *
 * - `onOpenLinkCard(open, view, leaving)` goes to CodeEditor: the editor reports what the card is open on (opened,
 *   moved by an edit above it), null (closed; the link changed under the card or was deleted — what was typed in it
 *   is dropped), or, with `leaving`, that another card is about to take its place: the open card then commits what
 *   was changed in it, as it does when the focus leaves it.
 * - `runLinkCommand()` is "Insert link" / Mod-K in Rich: on a link it opens the card; a selection on one line opens
 *   the card on that text, which becomes `[text](url)` only when the card is committed with an address; with nothing
 *   selected it writes `[[]]` and opens the page completion. False outside Rich: the caller's Source behaviour applies.
 * - `cardProps` are LinkCard's props while the card is open, else null.
 * - `flush()` has the open card write what it holds, by taking the focus out of it (before a save by shortcut). False
 *   when the card refused a value (it stays open, showing the reason); true otherwise, also when no card is open.
 *
 * Every write is one change through the editor's `applyChanges` (one undo step), over the card's range as it is now,
 * and only by the card that is open: each opening has an id, and a callback of an earlier card writes nothing.
 * A card that ended by key or button returns the focus to the editor, the caret after the link; one that ended
 * because the focus went elsewhere leaves focus and caret alone.
 */
export function useLinkCard({ editorRef, live, suggestPages }) {
  const [card, setCard] = useState(null);
  // The open card's "commit what you hold" (LinkCard sets it): asked when another card is about to open.
  const flushRef = useRef(null);

  const onOpenLinkCard = useCallback((open, view, leaving) => {
    if (leaving) { flushRef.current?.(); return; }
    const model = open ? cardModelOf(view.state, open) : null;
    if (!model) {
      // Closed from the editor's side while the card held the focus: it would otherwise be left on nothing.
      if (focusInCard() && view?.dom?.isConnected) view.focus();
      setCard(null);
      return;
    }
    setCard({ id: open.id, model, anchorRect: rectOf(view, open), href: linkCardHref(model, getContext(view.state)) });
  }, []);

  const viewOf = useCallback(() => {
    const view = editorRef.current?.getView?.();
    return view && typeof view.state?.field === 'function' ? view : null;
  }, [editorRef]);

  /**
   * Ends card `id`: `insertFor(model)` (when given) says what replaces its range, as one change; the caret goes after
   * it unless the focus left. Nothing is written by a card that is no longer the open one, or on a read-only document.
   */
  const end = useCallback((id, insertFor, userEvent, how) => {
    const view = viewOf();
    const open = view?.state.field(linkCardField, false);
    if (!open || open.id !== id) {
      // Not this card's any more (it closed, or another opened): only its own leftover state is cleared.
      setCard((shown) => (shown?.id === id ? null : shown));
      return;
    }
    if (view.state.readOnly) { view.dispatch({ effects: closeLinkCard.of(null) }); return; }
    let caret = open.to;
    let insert = null;
    if (insertFor) {
      const model = cardModelOf(view.state, open);
      try {
        if (model) insert = insertFor(model);
        else console.warn('[rich] the link under the card can no longer be read; its change is dropped');
      } catch (err) {
        // The card checks before it commits; this is a link that changed shape since.
        console.warn('[rich] the link card could not write its change', err?.message || err);
      }
    }
    if (insert != null && insert !== open.source) {
      editorRef.current.applyChanges([{ from: open.from, to: open.to, insert }], { userEvent });
      caret = open.from + insert.length;
    }
    if (view.state.field(linkCardField, false)) view.dispatch({ effects: closeLinkCard.of(null) });
    if (!how?.focusLeft) editorRef.current.setSelection(caret, caret);
  }, [editorRef, viewOf]);

  // The card is Rich mode's: switching to Source closes it, and nothing of it is written.
  useEffect(() => {
    if (live) return;
    const view = viewOf();
    if (view?.state.field(linkCardField, false)) view.dispatch({ effects: closeLinkCard.of(null) });
  }, [live, viewOf]);

  const runLinkCommand = useCallback(() => {
    const view = viewOf();
    if (!live || !view) return false;
    if (view.state.readOnly) return true; // nothing to insert into, and no card to edit with
    const { from, to, empty } = view.state.selection.main;
    const on = cardModelAt(view.state, from);
    if (on && to <= on.to) {
      requestLinkCard(view, from);
    } else if (empty) {
      editorRef.current.applyChanges([{ from, to, insert: '[[]]' }], { userEvent: 'input.link' });
      editorRef.current.setSelection(from + 2, from + 2);
      startCompletion(view);
    } else {
      requestLinkWrap(view, from, to); // refused (no card, no change) across a line break or cutting into a link
    }
    return true;
  }, [editorRef, live, viewOf]);

  const href = card?.href;
  const id = card?.id;
  const cardProps = useMemo(() => (id == null ? null : {
    onCommit: (patch, how) => end(id, (model) => linkChange(model, patch).insert, 'input.link-card', how),
    onRemove: () => end(id, unwrapLink, 'delete.link-card'),
    onClose: (how) => end(id, null, null, how),
    onOpen: href ? () => window.open(href, '_blank', 'noopener') : null,
  }), [id, href, end]);

  const flush = useCallback(() => {
    // A field that reports its text when the focus leaves it (the page field) must lose the focus to be heard.
    const active = document.activeElement;
    if (active?.closest?.('.link-card')) active.blur?.();
    return flushRef.current?.() !== false;
  }, []);

  return {
    onOpenLinkCard,
    runLinkCommand,
    flush,
    cardProps: card && live ? { key: card.id, model: card.model, anchorRect: card.anchorRect, suggestPages, flushRef, ...cardProps } : null,
  };
}
