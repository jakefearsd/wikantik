import { useCallback, useEffect, useId, useRef, useState } from 'react';
import WorkBody from './WorkBody';
import { LOCKED_HINT } from './WorkFieldRow';
import { useWorkPaste } from './useWorkPaste';

const grow = (el) => {
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${el.scrollHeight}px`;
};

/**
 * The item text: the rendered body, and for people who may edit it a plain auto-growing Markdown textarea.
 * (The page editor is not embedded: it is built around wiki pages, uploads and page-link completion.) "Saved
 * version" shows the last saved rendering, not the draft; the unsaved text stays in the textarea.
 *
 * The page allows one versioned editor at a time and owns the version it was opened against: this editor reports
 * `onOpen(orig)`, `onDraft(text)` and `onClose()` and is `locked` while another editor is open. `onSave(body)`
 * resolves true on success (anything else keeps the text). With `attach` ({ itemKey, toast, onUploaded }) a pasted or
 * dropped file uploads as an attachment and its token lands at the caret.
 */
export default function WorkBodyEditor({ html, body, canEdit, error, onSave, locked = false, onOpen, onDraft, onClose, attach = null }) {
  const [edit, setEdit] = useState(null); // { draft, base, orig }
  const [preview, setPreview] = useState(false);
  const [saving, setSaving] = useState(false);
  const ref = useRef(null);
  const openRef = useRef(null);
  const refocus = useRef(false);
  const hintId = useId();
  const editing = edit !== null;
  // The latest draft (null while closed): an upload finishing later edits it functionally, and nothing once closed.
  const draftRef = useRef(null);
  const apply = useCallback((next) => {
    draftRef.current = next;
    setEdit((cur) => (cur ? { ...cur, draft: next } : cur));
    onDraft?.(next);
  }, [onDraft]);
  const setText = useCallback((fn) => {
    if (draftRef.current === null) return;
    apply(fn(draftRef.current));
  }, [apply]);
  const paste = useWorkPaste({ attach, setText });

  useEffect(() => {
    if (editing) ref.current?.focus();
    else if (refocus.current) { refocus.current = false; openRef.current?.focus(); }
  }, [editing]);

  const close = () => { draftRef.current = null; refocus.current = true; setEdit(null); setPreview(false); onClose?.(); };

  function save() {
    if (saving) return;
    if (edit.draft === edit.orig) { close(); return; }
    setSaving(true);
    onSave(edit.draft)
      .then((ok) => { if (ok === true) close(); })
      .catch((e) => console.warn('WorkBodyEditor: saving the text failed', e))
      .finally(() => setSaving(false));
  }

  if (!editing) {
    return (
      <section className="work-section" aria-label="Description">
        <WorkBody html={html} />
        {canEdit && (
          <>
            <button
              ref={openRef} type="button" className="btn btn-ghost btn-sm" aria-label="Edit text" disabled={locked}
              aria-describedby={locked ? hintId : undefined} title={locked ? LOCKED_HINT : undefined}
              onClick={() => { draftRef.current = body ?? ''; setEdit({ draft: body ?? '', orig: body ?? '' }); onOpen?.(body ?? ''); }}
            >Edit text</button>
            {locked && <span id={hintId} className="work-visually-hidden">{LOCKED_HINT}</span>}
          </>
        )}
      </section>
    );
  }
  return (
    <section className="work-section" aria-label="Description">
      <div className="work-body-tabs" role="group" aria-label="Text view">
        <button type="button" className="btn btn-ghost btn-sm" aria-pressed={!preview} onClick={() => setPreview(false)}>Write</button>
        <button type="button" className="btn btn-ghost btn-sm" aria-pressed={preview} onClick={() => setPreview(true)}>Saved version</button>
      </div>
      {preview ? (
        <>
          <p className="work-muted">This is the last saved version, not your draft; your unsaved text is kept.</p>
          <WorkBody html={html} />
        </>
      ) : (
        <textarea
          ref={(el) => { ref.current = el; grow(el); }}
          className="work-body-input" aria-label="Item text" rows={6} value={edit.draft} disabled={saving}
          onChange={(e) => { apply(e.target.value); grow(e.target); }} {...paste}
        />
      )}
      {error && <p className="work-field-error" role="alert">{error}</p>}
      <div className="work-actions">
        <button type="button" className="btn btn-primary btn-sm" aria-label="Save text" disabled={saving} onClick={save}>Save</button>
        <button type="button" className="btn btn-ghost btn-sm" disabled={saving} onClick={close}>Cancel</button>
      </div>
    </section>
  );
}
