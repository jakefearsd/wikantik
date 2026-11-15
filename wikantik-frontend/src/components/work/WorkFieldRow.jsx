import { useState } from 'react';
import Select from '../ui/Select';

/**
 * One field that edits in place: the value and an Edit button, or a control with Save and Cancel. The draft
 * lives here, so a save that does not go through (conflict, refusal) leaves exactly what was typed.
 * `onSave(value)` resolves true when the server accepted it, 'restore' when it refused the value (the editor closes, the previous value shows) and false to keep the draft. `value` is the control's string; `parse` turns the
 * draft into what is sent.
 */
export default function WorkFieldRow({
  label, value = '', display, type = 'text', options = [], canEdit = false, helper, error, parse, onSave,
}) {
  const [draft, setDraft] = useState(null);
  const [saving, setSaving] = useState(false);
  const editing = draft !== null;

  function save() {
    if (saving) return;
    if (draft === value) { setDraft(null); return; }
    setSaving(true);
    onSave(parse ? parse(draft) : draft)
      .then((ok) => { if (ok) setDraft(null); })
      .catch((e) => console.warn(`WorkFieldRow: saving ${label} failed`, e))
      .finally(() => setSaving(false));
  }

  function onKeyDown(e) {
    if (e.key === 'Escape') { e.preventDefault(); setDraft(null); }
    else if (e.key === 'Enter' && type !== 'select') { e.preventDefault(); save(); }
  }

  return (
    <div className="work-field">
      <span className="work-field-label">{label}</span>
      {editing ? (
        <span className="work-field-edit" onKeyDown={onKeyDown}>
          {type === 'select' ? (
            <Select ariaLabel={label} value={draft} options={options} onChange={setDraft} disabled={saving} />
          ) : (
            <input
              className="work-field-input" type={type} aria-label={label} value={draft}
              onChange={(e) => setDraft(e.target.value)} disabled={saving}
            />
          )}
          <button type="button" className="btn btn-primary btn-sm" aria-label={`Save ${label}`} disabled={saving} onClick={save}>Save</button>
          <button type="button" className="btn btn-ghost btn-sm" aria-label={`Cancel editing ${label}`} disabled={saving} onClick={() => setDraft(null)}>Cancel</button>
        </span>
      ) : (
        <span className="work-field-value">
          <span>{display ?? value}</span>
          {canEdit && (
            <button type="button" className="work-link-button" aria-label={`Edit ${label}`} onClick={() => setDraft(value)}>Edit</button>
          )}
        </span>
      )}
      {error && <span className="work-field-error" role="alert">{error}</span>}
      {helper && <span className="work-field-helper">{helper}</span>}
    </div>
  );
}
