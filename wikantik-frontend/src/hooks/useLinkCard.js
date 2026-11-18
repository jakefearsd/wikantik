import { useCallback, useEffect, useState } from 'react';
import { startCompletion } from '@codemirror/autocomplete';
import { linkChange, unwrapLink, writeLink } from '../utils/linkEdit';
import { cardModelAt, closeLinkCard, linkCardField, requestLinkCard } from '../utils/livePreview/linkCard';
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

/** The model of the link the card is open on, read from the document as it is now; null when it is no longer that link. */
function modelOf(view, open) {
  const model = open ? cardModelAt(view.state, open.from) : null;
  return model && model.from === open.from && model.to === open.to ? model : null;
}

/**
 * The link card of the page editor (Rich mode): which link it is open on and what its actions write.
 *
 * - `onOpenLinkCard(open, view)` goes to CodeEditor: the editor reports the card's link (opened, moved by an edit
 *   above it) or null (closed; the link changed under the card or was deleted — what was typed in it is dropped).
 * - `runLinkCommand()` is "Insert link" / Mod-K in Rich: on a link it opens the card; a selection on one line becomes
 *   `[selection]()` with the card open on its address; with nothing selected it writes `[[]]` and opens the page
 *   completion. False when it did nothing and the caller's Source behaviour applies (a selection across lines).
 * - `cardProps` are LinkCard's props while the card is open, else null.
 *
 * Every write is one change through the editor's `applyChanges` (one undo step), over the link's range as it is now.
 * A card that ended by key or button returns the focus to the editor, the caret after the link; one that ended
 * because the focus went elsewhere leaves focus and caret alone.
 */
export function useLinkCard({ editorRef, live, suggestPages }) {
  const [card, setCard] = useState(null);

  const onOpenLinkCard = useCallback((open, view) => {
    const model = modelOf(view, open);
    if (!model) {
      // Closed from the editor's side while the card held the focus: it would otherwise be left on nothing.
      if (focusInCard()) view.focus();
      setCard(null);
      return;
    }
    setCard({ key: open.openedAt, model, anchorRect: rectOf(view, open), href: linkCardHref(model, getContext(view.state)) });
  }, []);

  const viewOf = useCallback(() => {
    const view = editorRef.current?.getView?.();
    return view && typeof view.state?.field === 'function' ? view : null;
  }, [editorRef]);

  /** Ends the card: `insert` (when given) replaces the link, as one change; the caret goes after it unless the focus left. */
  const end = useCallback((insert, userEvent, how) => {
    const view = viewOf();
    const open = view?.state.field(linkCardField, false);
    if (!open) { setCard(null); return; }
    let caret = open.to;
    if (insert != null && insert !== open.source) {
      editorRef.current.applyChanges([{ from: open.from, to: open.to, insert }], { userEvent });
      caret = open.from + insert.length;
    }
    if (view.state.field(linkCardField, false)) view.dispatch({ effects: closeLinkCard.of(null) });
    if (!how?.focusLeft) editorRef.current.setSelection(caret, caret);
  }, [editorRef, viewOf]);

  const commit = useCallback((patch, how) => {
    const view = viewOf();
    const open = view?.state.field(linkCardField, false);
    const model = view ? modelOf(view, open) : null;
    let insert = null;
    try {
      if (model) insert = linkChange(model, patch).insert;
    } catch (err) {
      // The card checks before it commits; this is a link that changed shape since.
      console.warn('[rich] the link card could not write its change', err?.message || err);
    }
    end(insert, 'input.link-card', how);
  }, [end, viewOf]);

  const remove = useCallback(() => {
    const view = viewOf();
    const open = view?.state.field(linkCardField, false);
    const model = view ? modelOf(view, open) : null;
    end(model ? unwrapLink(model) : null, 'delete.link-card');
  }, [end, viewOf]);

  const close = useCallback((how) => end(null, null, how), [end]);

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
    if (on && to <= on.to) return requestLinkCard(view, from);
    if (empty) {
      editorRef.current.applyChanges([{ from, to, insert: '[[]]' }], { userEvent: 'input.link' });
      editorRef.current.setSelection(from + 2, from + 2);
      startCompletion(view);
      return true;
    }
    const text = view.state.sliceDoc(from, to);
    if (text.includes('\n')) return false;
    editorRef.current.applyChanges([{ from, to, insert: writeLink({ kind: 'link', text, url: '' }) }], { userEvent: 'input.link' });
    requestLinkCard(view, from + 1);
    return true;
  }, [editorRef, live, viewOf]);

  const href = card?.href;
  const open = useCallback(() => { if (href) window.open(href, '_blank', 'noopener'); }, [href]);

  const cardProps = card && live ? {
    key: card.key, model: card.model, anchorRect: card.anchorRect, suggestPages,
    onCommit: commit, onRemove: remove, onClose: close, onOpen: href ? open : null,
  } : null;
  return { onOpenLinkCard, runLinkCommand, cardProps };
}
