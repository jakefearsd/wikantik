import { useRef, useState } from 'react';
import WorkBody from './WorkBody';

const grow = (el) => {
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${el.scrollHeight}px`;
};

/**
 * The item text: the rendered body, and for people who may edit it a plain auto-growing Markdown textarea.
 * (The page editor is not embedded: it is built around wiki pages, uploads and page-link completion.) Preview
 * shows the last saved rendering; the unsaved text stays in the textarea. `onSave(body)` resolves true on success.
 */
export default function WorkBodyEditor({ html, body, canEdit, onSave }) {
  const [draft, setDraft] = useState(null);
  const [preview, setPreview] = useState(false);
  const [saving, setSaving] = useState(false);
  const ref = useRef(null);

  function save() {
    if (saving) return;
    if (draft === body) { setDraft(null); return; }
    setSaving(true);
    onSave(draft)
      .then((ok) => { if (ok) { setDraft(null); setPreview(false); } })
      .catch((e) => console.warn('WorkBodyEditor: saving the text failed', e))
      .finally(() => setSaving(false));
  }

  if (draft === null) {
    return (
      <section className="work-section" aria-label="Description">
        <WorkBody html={html} />
        {canEdit && (
          <button type="button" className="btn btn-ghost btn-sm" aria-label="Edit text" onClick={() => setDraft(body ?? '')}>Edit text</button>
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
          className="work-body-input" aria-label="Item text" rows={6} value={draft} disabled={saving}
          onChange={(e) => { setDraft(e.target.value); grow(e.target); }}
        />
      )}
      <div className="work-actions">
        <button type="button" className="btn btn-primary btn-sm" aria-label="Save text" disabled={saving} onClick={save}>Save</button>
        <button type="button" className="btn btn-ghost btn-sm" disabled={saving} onClick={() => { setDraft(null); setPreview(false); }}>Cancel</button>
      </div>
    </section>
  );
}
