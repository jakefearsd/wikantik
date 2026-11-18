import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import Combobox from '../ui/Combobox';
import { placeCard } from '../../utils/placeCard';
import { linkChange } from '../../utils/linkEdit';
import { isImageFileName } from '../../utils/wikiLinkSyntax';

// Size used before the card has been measured (and where there is no layout, e.g. tests): the CSS width.
const ESTIMATE = { width: 320, height: 220 };
const viewport = () => ({ width: window.innerWidth, height: window.innerHeight });
/** Where the card goes when the link has no place on screen (scrolled out of the drawn range). */
const NO_ANCHOR = { left: 16, top: 16, bottom: 16, right: 16 };

/** The card's fields for a model: `[field of the model, label]`, in the order shown. */
function fieldsOf(model, draft) {
  if (model.kind === 'link') return [['url', 'URL'], ['text', 'Text']];
  if (model.kind === 'image') return [['url', 'Source'], ['alt', 'Alt text']];
  const fields = [['target', 'Page'], ['heading', 'Heading']];
  if (model.kind === 'wiki') fields.push(['alias', 'Text']);
  // A size is an embedded image file's alone: elsewhere the same slot is display text.
  else if (model.size != null || isImageFileName.test(draft.target)) fields.push(['size', 'Size']);
  return fields;
}

const held = (model, name) => model[name] ?? '';
/** Fields that are '' when empty; the others (heading, display text, size) are then absent: null. */
const NEVER_ABSENT = new Set(['target', 'url', 'text', 'alt']);
const controlsOf = (card) => [...card.querySelectorAll('input:not([disabled]), button:not([disabled])')];
/** Tells the host that the focus is already somewhere else: it must not be pulled back to the editor. */
const FOCUS_LEFT = Object.freeze({ focusLeft: true });

/**
 * The card that edits one link, wikilink, embed or image (linkEdit's model). It writes nothing itself: one
 * `onCommit(patch)` with the changed fields on Enter or when the focus leaves it, `onClose()` when nothing was changed
 * or on Escape, `onRemove()` and `onOpen()` for its two actions. A value the syntax cannot carry is refused here, with
 * the reason shown, and nothing is committed. `onOpen` may be absent: the link then leads nowhere that can be opened.
 *
 * It takes the focus when it opens and keeps Tab inside itself. Giving the focus back is the host's: `onCommit` and
 * `onClose` get `{ focusLeft: true }` as their last argument when the card ended because the focus went elsewhere
 * (a click in the text, another field of the page) — it then stays where the user put it.
 *
 * A `pending` model is text that is not a link yet (Insert link on a selection): the card writes it as one only when
 * it is committed with an address, and otherwise just closes. `flushRef`, when given, receives a function that ends
 * the card as the focus leaving it would (the host calls it when another card is about to take its place).
 */
export default function LinkCard({ model, anchorRect, suggestPages, onCommit, onRemove, onOpen, onClose, flushRef }) {
  const cardRef = useRef(null);
  const [draft, setDraft] = useState(() => ({}));
  // What the handlers read: a field's own Enter or blur updates it in the same event that then commits the card.
  const draftRef = useRef(draft);
  const doneRef = useRef(false);
  const [error, setError] = useState(null);
  useLayoutEffect(() => { controlsOf(cardRef.current)[0]?.focus(); }, []);

  const rect = anchorRect || NO_ANCHOR;
  // Refine the estimate-based position with the card's real size once it is laid out (as LinkPreviewCard does).
  useLayoutEffect(() => {
    const card = cardRef.current;
    if (!card) return;
    const { width, height } = card.getBoundingClientRect();
    if (!width || !height) return;
    const pos = placeCard(rect, { width, height }, viewport());
    card.style.left = `${pos.left}px`;
    card.style.top = `${pos.top}px`;
  }, [rect]);

  const valueOf = (name) => (name in draftRef.current ? draftRef.current[name] : held(model, name));
  const set = (name, value) => {
    draftRef.current = { ...draftRef.current, [name]: value };
    setDraft(draftRef.current);
    setError(null);
  };
  const shownDraft = { target: draft.target ?? held(model, 'target') };
  const fields = fieldsOf(model, shownDraft);

  /** The changed fields, by name. */
  const patchOf = () => {
    const patch = {};
    for (const [name] of fieldsOf(model, { target: valueOf('target') })) {
      const value = valueOf(name);
      if (value === held(model, name)) continue;
      patch[name] = value === '' && !NEVER_ABSENT.has(name) ? null : value;
    }
    return patch;
  };
  const finish = (report) => {
    if (doneRef.current) return;
    doneRef.current = true;
    report();
  };
  /** Commits what was changed, or closes when nothing was. False when a value was refused: the card stays, with the reason. */
  const commit = (...how) => {
    if (doneRef.current) return true;
    const patch = patchOf();
    // Text that is not a link yet becomes one only with an address.
    const nothing = Object.keys(patch).length === 0 || (model.pending && valueOf('url').trim() === '');
    if (nothing) { finish(() => onClose(...how)); return true; }
    try {
      linkChange(model, patch);
    } catch (err) {
      if (!(err instanceof RangeError)) throw err;
      setError(err.message);
      return false;
    }
    finish(() => onCommit(patch, ...how));
    return true;
  };

  // Every render, so the host always reaches this render's handlers.
  useEffect(() => {
    if (!flushRef) return undefined;
    const mine = () => commit(FOCUS_LEFT);
    flushRef.current = mine;
    return () => { if (flushRef.current === mine) flushRef.current = null; };
  });

  const onKeyDown = (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      finish(() => onClose());
    } else if (e.key === 'Tab') {
      const controls = controlsOf(cardRef.current);
      const edge = controls[e.shiftKey ? 0 : controls.length - 1];
      if (document.activeElement !== edge && cardRef.current.contains(document.activeElement)) return;
      e.preventDefault();
      controls[e.shiftKey ? controls.length - 1 : 0]?.focus();
    } else if (e.key === 'Enter' && e.target.tagName === 'INPUT' && !e.nativeEvent.isComposing) {
      e.preventDefault();
      commit();
    }
  };
  const onBlur = (e) => {
    // Another window or tab took the focus (also what "Open" does): it comes back to the same field.
    if (cardRef.current?.contains(e.relatedTarget) || !document.hasFocus()) return;
    // Refused: the focus comes back to the field, so the reason is read and Escape still discards.
    if (!commit(FOCUS_LEFT)) e.target.focus?.();
  };

  const pos = placeCard(rect, ESTIMATE, viewport());
  return (
    <div ref={cardRef} className="link-card" role="dialog" aria-label="Edit link" data-testid="link-card"
         style={{ position: 'fixed', top: pos.top, left: pos.left }} onKeyDown={onKeyDown} onBlur={onBlur}>
      {fields.map(([name, label]) => {
        const id = `link-card-${name}`;
        const value = draft[name] ?? held(model, name);
        return (
          <div className="link-card-field" key={name}>
            <label className="link-card-label" htmlFor={id}>{label}</label>
            {name === 'target'
              ? <Combobox id={id} value={value} onChange={(v) => set(name, v)} fetchOptions={suggestPages} />
              : (
                <input id={id} className="ui-combobox-field" type="text" value={value} autoComplete="off" spellCheck={name === 'text' || name === 'alias' || name === 'alt'}
                       readOnly={name === 'text' && model.textLocked === true}
                       title={name === 'text' && model.textLocked ? 'This text holds formatting or an image: it can be edited in Source mode' : undefined}
                       placeholder={name === 'size' ? '300 or 300x200' : undefined}
                       onChange={(e) => set(name, e.target.value)} />
              )}
          </div>
        );
      })}
      {error && <p className="link-card-error" role="alert">{error}</p>}
      <div className="link-card-actions">
        <button type="button" className="btn btn-ghost" disabled={!onOpen} onClick={() => onOpen?.()}>Open</button>
        <button type="button" className="btn btn-ghost" disabled={model.pending === true} onClick={() => finish(() => onRemove())}>Remove</button>
      </div>
    </div>
  );
}
