import { useEffect, useId, useRef, useState } from 'react';
import { LOCKED_HINT } from './WorkFieldRow';
import AssigneePicker, { targetValue } from './AssigneePicker';
import Select from '../ui/Select';

/** The draft the item page watches: always the same four keys, so staleness can compare it with what the form opened with. */
export const handoffShape = (status, assignee, queue, note) => ({ status, assignee: assignee || '', queue: queue || '', note });

/**
 * A hand-off moves an item to a different person or queue (never both) and needs a note; changing only the status is
 * an ordinary update, not a hand-off. `onSend(status, target, note)` resolves true on success, `target` being
 * `{ assignee }` or `{ queue }`. `claimHolder` names an agent whose claim the hand-off would end.
 */
export default function WorkHandoff({
  status, assignee, queue, assigneeName, queueName, statusOptions, members, queues, claimHolder, onSend, error,
  locked = false, onOpen, onDraft, onClose,
}) {
  const uid = useId();
  const [open, setOpen] = useState(false);
  const [toStatus, setToStatus] = useState(status);
  const [toTarget, setToTarget] = useState({});
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
  const draft = (patch) => {
    const t = patch.target ?? toTarget;
    onDraft?.(handoffShape(patch.status ?? toStatus, t.assignee, t.queue, patch.note ?? note));
  };

  if (!open) {
    return (
      <>
        <button
          ref={openRef} type="button" className="btn btn-secondary btn-sm" disabled={locked}
          aria-describedby={locked ? `${uid}-hint` : undefined} title={locked ? LOCKED_HINT : undefined}
          onClick={() => {
            setToStatus(status); setToTarget(queue ? { queue } : assignee ? { assignee } : {}); setOpen(true);
            onOpen?.(handoffShape(status, assignee, queue, undefined));
          }}
        >Hand off</button>
        {locked && <span id={`${uid}-hint`} className="work-visually-hidden">{LOCKED_HINT}</span>}
      </>
    );
  }
  const targetChanged = targetValue(toTarget) !== targetValue(queue ? { queue } : { assignee });
  const hasTarget = targetValue(toTarget) !== '';
  const ok = targetChanged && hasTarget && note.trim() !== '' && !saving;

  function send() {
    if (!ok) return;
    setSaving(true);
    onSend(toStatus, toTarget.queue ? { queue: toTarget.queue } : { assignee: toTarget.assignee }, note.trim())
      .then((done) => { if (done) { close(); setNote(''); } })
      .catch((e) => console.warn('WorkHandoff: handoff failed', e))
      .finally(() => setSaving(false));
  }

  return (
    <form ref={formRef} className="work-handoff" onSubmit={(e) => { e.preventDefault(); send(); }} aria-label="Hand off this item">
      <label className="work-form-field" htmlFor={`${uid}-status`}>Status after handoff</label>
      <Select id={`${uid}-status`} ariaLabel="Status after handoff" value={toStatus} options={statusOptions} onChange={(v) => { setToStatus(v); draft({ status: v }); }} />
      <label className="work-form-field" htmlFor={`${uid}-who`}>Hand off to</label>
      <AssigneePicker
        id={`${uid}-who`} ariaLabel="Hand off to" members={members} queues={queues} value={toTarget}
        labels={{ assigneeName, queueName }} allowNone={false} placeholder="Choose a person or queue"
        onChange={(t) => { const next = t.queue ? { queue: t.queue } : { assignee: t.assignee }; setToTarget(next); draft({ target: next }); }} />
      {!targetChanged && <p className="work-field-helper">Choose a different person or queue. To change only the status, edit Status.</p>}
      {claimHolder && <p className="work-claim-warning">{claimHolder} is working on this; handing it off releases its claim.</p>}
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
