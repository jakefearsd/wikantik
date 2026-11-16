import { useState } from 'react';
import EmptyState from '../ui/EmptyState';
import { createQueue, retireQueue, updateQueue } from '../../api/work';
import { useToast } from '../../hooks/useToast';
import WorkConfirm from './WorkConfirm';

const KEY_PATTERN = /^[a-z][a-z0-9-]{1,29}$/;
const KEY_MESSAGE = 'Key must start with a lowercase letter and use only lowercase letters, digits and hyphens (2 to 30 characters).';

function QueueForm({ queue, onDone, onCancel, locked }) {
  const toast = useToast();
  const editing = !!queue;
  const [f, setF] = useState({ key: queue?.key || '', name: queue?.name || '', description: queue?.description || '' });
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);
  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));
  const described = (field) => (errors[field] ? `work-q-err-${field}` : undefined);
  const note = (field) => errors[field] && <div id={`work-q-err-${field}`} className="work-field-error" role="alert">{errors[field]}</div>;

  function submit(e) {
    e.preventDefault();
    if (busy || locked) return;
    if (!editing && !KEY_PATTERN.test(f.key)) { setErrors({ key: KEY_MESSAGE }); return; }
    if (!f.name.trim()) { setErrors({ name: 'Enter a name.' }); return; }
    setErrors({}); setBusy(true);
    const fields = { name: f.name.trim(), description: f.description.trim() };
    const request = editing ? updateQueue(queue.key, fields) : createQueue({ key: f.key, ...fields });
    request
      .then(() => { toast.success(editing ? 'Queue saved' : `Created queue ${f.key}`); onDone(); })
      .catch((err) => {
        setBusy(false);
        if (err?.status === 403) { toast.error(err.message); return; }
        const message = err?.message || 'Could not save the queue.';
        const field = err?.body?.field === 'name' ? 'name' : err?.body?.field === 'key' || err?.body?.reason === 'exists' ? 'key' : 'form';
        setErrors({ [field]: message });
      });
  }

  return (
    <form className="work-form" onSubmit={submit} noValidate>
      <label className="work-form-field" htmlFor="work-q-key">Queue key</label>
      <input id="work-q-key" className="work-field-input" value={f.key} onChange={set('key')} disabled={editing}
        aria-invalid={errors.key ? 'true' : undefined} aria-describedby={described('key')} />
      {note('key')}
      <label className="work-form-field" htmlFor="work-q-name">Queue name</label>
      <input id="work-q-name" className="work-field-input" value={f.name} onChange={set('name')}
        aria-invalid={errors.name ? 'true' : undefined} aria-describedby={described('name')} />
      {note('name')}
      <label className="work-form-field" htmlFor="work-q-desc">Queue description</label>
      <input id="work-q-desc" className="work-field-input" value={f.description} onChange={set('description')} />
      {errors.form && <div className="work-form-error" role="alert">{errors.form}</div>}
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>{editing ? 'Save queue' : 'Create queue'}</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

/** The queue half of /work/agents. `queues` is the loaded list; `reload` re-reads it. */
export default function WorkQueues({ queues, reload, locked = false, lockedHint }) {
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [retiring, setRetiring] = useState(null);
  const [editing, setEditing] = useState(null);
  const [busy, setBusy] = useState(false);
  const sorted = [...queues].sort((a, b) => (a.retiredAt ? 1 : 0) - (b.retiredAt ? 1 : 0));

  function retire(key) {
    if (busy) return;
    setBusy(true);
    retireQueue(key)
      .then(() => { toast.success(`Retired queue ${key}`); reload(); })
      .catch((err) => toast.error(err?.message || 'Could not retire the queue.'))
      .finally(() => { setRetiring(null); setBusy(false); });
  }

  return (
    <section className="work-section" aria-label="Queues">
      <h2 className="work-subheading">Queues</h2>
      {creating
        ? <QueueForm onDone={() => { setCreating(false); reload(); }} onCancel={() => setCreating(false)} />
        : <p><button type="button" className="btn btn-sm" disabled={locked} aria-describedby={locked ? lockedHint : undefined}
          onClick={() => setCreating(true)}>New queue</button></p>}
      {sorted.length === 0 ? <EmptyState message="No queues yet." /> : (
        <ul className="work-product-rows">
          {sorted.map((q) => (
            <li key={q.key} data-testid="queue-row" className={`work-product-row${q.retiredAt ? ' work-retired' : ''}`}>
              {editing === q.key
                ? <QueueForm queue={q} onDone={() => { setEditing(null); reload(); }} onCancel={() => setEditing(null)} />
                : (
                  <div>
                    <strong>{q.name}</strong> <span className="work-chip">{q.key}</span>
                    {q.retiredAt && <span className="work-chip">retired</span>}
                    {q.description && <div className="work-muted">{q.description}</div>}
                  </div>
                )}
              {!q.retiredAt && editing !== q.key && (retiring === q.key
                ? (
                  <WorkConfirm yes={`Yes, retire ${q.key}`} disabled={busy} onYes={() => retire(q.key)} onNo={() => setRetiring(null)}>
                    Retire queue {q.key}? It will no longer be offered to agents.
                  </WorkConfirm>
                )
                : (
                  <div className="work-actions">
                    <button type="button" className="btn btn-sm" aria-label={`Edit queue ${q.key}`} disabled={locked}
                      aria-describedby={locked ? lockedHint : undefined} onClick={() => setEditing(q.key)}>Edit</button>
                    <button type="button" className="btn btn-sm btn-ghost" aria-label={`Retire queue ${q.key}`}
                      onClick={() => setRetiring(q.key)}>Retire</button>
                  </div>
                ))}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
