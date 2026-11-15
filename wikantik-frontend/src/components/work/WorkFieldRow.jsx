import { useEffect, useRef, useState } from 'react';
import Select from '../ui/Select';

/**
 * One field that edits in place: the value and an Edit button, or a control with Save and Cancel. The draft
 * lives here, so a save that does not go through (conflict, refusal) leaves exactly what was typed.
 *
 * The editor remembers the item `version` it was opened against and passes it to
 * `onSave(value, baseVersion)`, so a refetch that moved the item on can never rebase an unsaved edit. While
 * it is open and `version` has moved past that base it calls `onStale(draft)` so the page can raise the
 * conflict banner. `rebase` changes when the person chooses to keep editing on top of the newer version.
 *
 * `onSave` resolves true when the server accepted the value, 'restore' when it refused it and the previous
 * value should show (the editor closes), and false to keep the draft. `value` is the control's string;
 * `parse` turns the draft into what is sent.
 */
export default function WorkFieldRow({
  label, value = '', display, type = 'text', options = [], canEdit = false, helper, error, parse, onSave,
  version, rebase = 0, onStale,
}) {
  const [edit, setEdit] = useState(null); // { draft, base, orig } while editing
  const [seenRebase, setSeenRebase] = useState(rebase);
  const [saving, setSaving] = useState(false);
  const editorRef = useRef(null);
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
    if (editing) {
      editorRef.current?.querySelector('input, select')?.focus();
    } else if (refocus.current) {
      refocus.current = false;
      openRef.current?.focus();
    }
  }, [editing]);

  const close = () => { refocus.current = true; setEdit(null); };

  function save() {
    if (saving) return;
    if (edit.draft === edit.orig) { close(); return; }
    setSaving(true);
    onSave(parse ? parse(edit.draft) : edit.draft, edit.base)
      .then((ok) => { if (ok) close(); })
      .catch((e) => console.warn(`WorkFieldRow: saving ${label} failed`, e))
      .finally(() => setSaving(false));
  }

  function onKeyDown(e) {
    if (e.key === 'Escape') { e.preventDefault(); close(); }
    else if (e.key === 'Enter' && e.target.tagName === 'INPUT') { e.preventDefault(); save(); }
  }

  return (
    <div className="work-field">
      <span className="work-field-label">{label}</span>
      {editing ? (
        <span className="work-field-edit" ref={editorRef} onKeyDown={onKeyDown}>
          {type === 'select' ? (
            <Select ariaLabel={label} value={edit.draft} options={options} onChange={(v) => setEdit({ ...edit, draft: v })} disabled={saving} />
          ) : (
            <input
              className="work-field-input" type={type} aria-label={label} value={edit.draft}
              onChange={(e) => setEdit({ ...edit, draft: e.target.value })} disabled={saving}
            />
          )}
          <button type="button" className="btn btn-primary btn-sm" aria-label={`Save ${label}`} disabled={saving} onClick={save}>Save</button>
          <button type="button" className="btn btn-ghost btn-sm" aria-label={`Cancel editing ${label}`} disabled={saving} onClick={close}>Cancel</button>
        </span>
      ) : (
        <span className="work-field-value">
          <span>{display ?? value}</span>
          {canEdit && (
            <button
              ref={openRef} type="button" className="work-link-button" aria-label={`Edit ${label}`}
              onClick={() => setEdit({ draft: value, base: version, orig: value })}
            >Edit</button>
          )}
        </span>
      )}
      {error && <span className="work-field-error" role="alert">{error}</span>}
      {helper && <span className="work-field-helper">{helper}</span>}
    </div>
  );
}
