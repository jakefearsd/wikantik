import { useEffect, useRef, useState } from 'react';
import WorkBody from './WorkBody';

const grow = (el) => {
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${el.scrollHeight}px`;
};

/**
 * The item text: the rendered body, and for people who may edit it a plain auto-growing Markdown textarea.
 * (The page editor is not embedded: it is built around wiki pages, uploads and page-link completion.) Preview
 * shows the last saved rendering; the unsaved text stays in the textarea.
 *
 * Like every editor it remembers the item version it was opened against: `onSave(body, baseVersion)` resolves
 * true on success (anything else keeps the text), and while open with `version` past that base it calls
 * `onStale(text)` so the page can raise the conflict banner. `rebase` changes on "keep editing on top of theirs".
 */
export default function WorkBodyEditor({ html, body, canEdit, error, onSave, version, rebase = 0, onStale }) {
  const [edit, setEdit] = useState(null); // { draft, base, orig }
  const [seenRebase, setSeenRebase] = useState(rebase);
  const [preview, setPreview] = useState(false);
  const [saving, setSaving] = useState(false);
  const ref = useRef(null);
  const openRef = useRef(null);
  const refocus = useRef(false);
  const editing = edit !== null;

  if (seenRebase !== rebase) {
    setSeenRebase(rebase);
    if (edit) setEdit({ ...edit, base: version });
  }

  const draft = edit?.draft;
  const stale = editing && version !== undefined && version > edit.base;
  useEffect(() => {
    if (stale) onStale?.(draft);
  }, [stale, draft, onStale]);

  useEffect(() => {
    if (editing) ref.current?.focus();
    else if (refocus.current) { refocus.current = false; openRef.current?.focus(); }
  }, [editing]);

  const close = () => { refocus.current = true; setEdit(null); setPreview(false); };

  function save() {
    if (saving) return;
    if (edit.draft === edit.orig) { close(); return; }
    setSaving(true);
    onSave(edit.draft, edit.base)
      .then((ok) => { if (ok === true) close(); })
      .catch((e) => console.warn('WorkBodyEditor: saving the text failed', e))
      .finally(() => setSaving(false));
  }

  if (!editing) {
    return (
      <section className="work-section" aria-label="Description">
        <WorkBody html={html} />
        {canEdit && (
          <button
            ref={openRef} type="button" className="btn btn-ghost btn-sm" aria-label="Edit text"
            onClick={() => setEdit({ draft: body ?? '', base: version, orig: body ?? '' })}
          >Edit text</button>
        )}
      </section>
    );
  }
  return (
    <section className="work-section" aria-label="Description">
      <div className="work-body-tabs" role="group" aria-label="Text view">
        <button type="button" className="btn btn-ghost btn-sm" aria-pressed={!preview} onClick={() => setPreview(false)}>Write</button>
        <button type="button" className="btn btn-ghost btn-sm" aria-pressed={preview} onClick={() => setPreview(true)}>Preview</button>
      </div>
      {preview ? (
        <>
          <p className="work-muted">Showing the last saved version; your unsaved text is kept.</p>
          <WorkBody html={html} />
        </>
      ) : (
        <textarea
          ref={(el) => { ref.current = el; grow(el); }}
          className="work-body-input" aria-label="Item text" rows={6} value={edit.draft} disabled={saving}
          onChange={(e) => { setEdit({ ...edit, draft: e.target.value }); grow(e.target); }}
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
