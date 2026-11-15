import { useId, useState } from 'react';
import Select from '../ui/Select';

/**
 * Changing status and assignee together is a handoff, and a handoff needs a note (the server refuses a blank one).
 * `onSend(status, assignee, note)` resolves true on success.
 */
export default function WorkHandoff({ status, assignee, statusOptions, assigneeOptions, onSend }) {
  const uid = useId();
  const [open, setOpen] = useState(false);
  const [toStatus, setToStatus] = useState(status);
  const [toAssignee, setToAssignee] = useState(assignee || '');
  const [note, setNote] = useState('');
  const [saving, setSaving] = useState(false);

  if (!open) {
    return <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setToStatus(status); setToAssignee(assignee || ''); setOpen(true); }}>Hand off</button>;
  }
  const changed = toStatus !== status || toAssignee !== (assignee || '');
  const ok = changed && note.trim() !== '' && !saving;

  function send() {
    if (!ok) return;
    setSaving(true);
    onSend(toStatus, toAssignee === '' ? null : toAssignee, note.trim())
      .then((done) => { if (done) { setOpen(false); setNote(''); } })
      .catch((e) => console.warn('WorkHandoff: handoff failed', e))
      .finally(() => setSaving(false));
  }

  return (
    <form className="work-handoff" onSubmit={(e) => { e.preventDefault(); send(); }} aria-label="Hand off this item">
      <label className="work-form-field" htmlFor={`${uid}-status`}>Status after handoff</label>
      <Select id={`${uid}-status`} ariaLabel="Status after handoff" value={toStatus} options={statusOptions} onChange={setToStatus} />
      <label className="work-form-field" htmlFor={`${uid}-who`}>Hand off to</label>
      <Select id={`${uid}-who`} ariaLabel="Hand off to" value={toAssignee} options={assigneeOptions} onChange={setToAssignee} />
      <label className="work-form-field" htmlFor={`${uid}-note`}>Handoff note</label>
      <textarea
        id={`${uid}-note`} className="work-form-body" rows={3} required aria-required="true" value={note}
        onChange={(e) => setNote(e.target.value)} placeholder="What the next person needs to know"
      />
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={!ok}>Send handoff</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(false)}>Cancel</button>
      </div>
    </form>
  );
}
