import { useEffect, useId, useRef, useState } from 'react';
import { LOCKED_HINT } from './WorkFieldRow';
import Select from '../ui/Select';

/**
 * Changing status and assignee together is a handoff, and a handoff needs a note (the server refuses a blank one).
 * `onSend(status, assignee, note)` resolves true on success.
 */
export default function WorkHandoff({
  status, assignee, statusOptions, assigneeOptions, onSend, error, locked = false, onOpen, onDraft, onClose,
}) {
  const uid = useId();
  const [open, setOpen] = useState(false);
  const [toStatus, setToStatus] = useState(status);
  const [toAssignee, setToAssignee] = useState(assignee || '');
  const [note, setNote] = useState('');
  const [saving, setSaving] = useState(false);
  const formRef = useRef(null);
  const openRef = useRef(null);
  const refocus = useRef(false);

  useEffect(() => {
    if (open) formRef.current?.querySelector('select')?.focus();
    else if (refocus.current) { refocus.current = false; openRef.current?.focus(); }
  }, [open]);
  const close = () => { refocus.current = true; setOpen(false); onClose?.(); };
  const draft = (patch) => onDraft?.({ status: toStatus, assignee: toAssignee, note, ...patch });

  if (!open) {
    return (
      <>
        <button
          ref={openRef} type="button" className="btn btn-secondary btn-sm" disabled={locked}
          aria-describedby={locked ? `${uid}-hint` : undefined} title={locked ? LOCKED_HINT : undefined}
          onClick={() => {
            const a = assignee || '';
            setToStatus(status); setToAssignee(a); setOpen(true);
            onOpen?.({ status, assignee: a });
          }}
        >Hand off</button>
        {locked && <span id={`${uid}-hint`} className="work-visually-hidden">{LOCKED_HINT}</span>}
      </>
    );
  }
  const changed = toStatus !== status || toAssignee !== (assignee || '');
  const ok = changed && note.trim() !== '' && !saving;

  function send() {
    if (!ok) return;
    setSaving(true);
    onSend(toStatus, toAssignee === '' ? null : toAssignee, note.trim())
      .then((done) => { if (done) { close(); setNote(''); } })
      .catch((e) => console.warn('WorkHandoff: handoff failed', e))
      .finally(() => setSaving(false));
  }

  return (
    <form ref={formRef} className="work-handoff" onSubmit={(e) => { e.preventDefault(); send(); }} aria-label="Hand off this item">
      <label className="work-form-field" htmlFor={`${uid}-status`}>Status after handoff</label>
      <Select id={`${uid}-status`} ariaLabel="Status after handoff" value={toStatus} options={statusOptions} onChange={(v) => { setToStatus(v); draft({ status: v }); }} />
      <label className="work-form-field" htmlFor={`${uid}-who`}>Hand off to</label>
      <Select id={`${uid}-who`} ariaLabel="Hand off to" value={toAssignee} options={assigneeOptions} onChange={(v) => { setToAssignee(v); draft({ assignee: v }); }} />
      <label className="work-form-field" htmlFor={`${uid}-note`}>Handoff note</label>
      <textarea
        id={`${uid}-note`} className="work-form-body" rows={3} required aria-required="true" value={note}
        onChange={(e) => { setNote(e.target.value); draft({ note: e.target.value }); }} placeholder="What the next person needs to know"
      />
      {error && <p className="work-field-error" role="alert">{error}</p>}
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={!ok}>Send handoff</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={close}>Cancel</button>
      </div>
    </form>
  );
}
