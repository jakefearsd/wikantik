import { useEffect, useId, useRef, useState } from 'react';
import Select from '../ui/Select';

/**
 * One field that edits in place: the value and an Edit button, or a control with Save and Cancel. The draft
 * lives here, so a save that does not go through (conflict, refusal) leaves exactly what was typed.
 *
 * The page allows one versioned editor at a time and owns the version it was opened against: this row reports
 * `onOpen(orig)`, `onDraft(draft)` and `onClose()`, and is `locked` (Edit disabled, with a hint) while another
 * editor is open. `onSave(value)` resolves true when the server accepted the value, 'restore' when it refused
 * it and the previous value should show (the editor closes), and false to keep the draft. `value` is the
 * control's string; `parse` turns the draft into what is sent. `beforeSave(draft)` may complete the draft first (a control
 * holding text it has not yet put in the draft), before the unchanged check. `renderControl({ value, onChange, disabled })` supplies a
 * custom control (the assignee picker) in place of the built-in select or input.
 */
export const LOCKED_HINT = 'Finish or cancel the open edit first';

export default function WorkFieldRow({
  label, value = '', display, type = 'text', options = [], canEdit = false, helper, error, parse, beforeSave, onSave, renderControl,
  locked = false, onOpen, onDraft, onClose,
}) {
  const [edit, setEdit] = useState(null); // { draft, orig } while editing
  const [saving, setSaving] = useState(false);
  const hintId = useId();
  const editorRef = useRef(null);
  const openRef = useRef(null);
  const refocus = useRef(false);
  const editing = edit !== null;

  useEffect(() => {
    if (editing) {
      editorRef.current?.querySelector('input, select')?.focus();
    } else if (refocus.current) {
      refocus.current = false;
      openRef.current?.focus();
    }
  }, [editing]);

  const change = (draft) => { setEdit((e) => ({ ...e, draft })); onDraft?.(draft); };
  const close = () => { refocus.current = true; setEdit(null); onClose?.(); };

  function save() {
    if (saving) return;
    const draft = beforeSave ? beforeSave(edit.draft) : edit.draft;
    if (draft === edit.orig) { close(); return; }
    setSaving(true);
    onSave(parse ? parse(draft) : draft)
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
          {renderControl ? renderControl({ value: edit.draft, onChange: change, disabled: saving }) : type === 'select' ? (
            <Select ariaLabel={label} value={edit.draft} options={options} onChange={change} disabled={saving} />
          ) : (
            <input
              className="work-field-input" type={type} aria-label={label} value={edit.draft}
              onChange={(e) => change(e.target.value)} disabled={saving}
            />
          )}
          <button type="button" className="btn btn-primary btn-sm" aria-label={`Save ${label}`} disabled={saving} onClick={save}>Save</button>
          <button type="button" className="btn btn-ghost btn-sm" aria-label={`Cancel editing ${label}`} disabled={saving} onClick={close}>Cancel</button>
        </span>
      ) : (
        <span className="work-field-value">
          <span>{display ?? value}</span>
          {canEdit && (
            <>
              <button
                ref={openRef} type="button" className="work-link-button" aria-label={`Edit ${label}`} disabled={locked}
                aria-describedby={locked ? hintId : undefined} title={locked ? LOCKED_HINT : undefined}
                onClick={() => { setEdit({ draft: value, orig: value }); onOpen?.(value); }}
              >Edit</button>
              {locked && <span id={hintId} className="work-visually-hidden">{LOCKED_HINT}</span>}
            </>
          )}
        </span>
      )}
      {error && <span className="work-field-error" role="alert">{error}</span>}
      {helper && <span className="work-field-helper">{helper}</span>}
    </div>
  );
}
