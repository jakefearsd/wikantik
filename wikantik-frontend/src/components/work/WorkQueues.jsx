import { useState } from 'react';
import EmptyState from '../ui/EmptyState';
import { createQueue, retireQueue } from '../../api/work';
import { useToast } from '../../hooks/useToast';
import WorkConfirm from './WorkConfirm';

const KEY_PATTERN = /^[a-z][a-z0-9-]{1,29}$/;
const KEY_MESSAGE = 'Key must start with a lowercase letter and use only lowercase letters, digits and hyphens (2 to 30 characters).';

function QueueForm({ onDone, onCancel }) {
  const toast = useToast();
  const [f, setF] = useState({ key: '', name: '', description: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));

  function submit(e) {
    e.preventDefault();
    if (busy) return;
    if (!KEY_PATTERN.test(f.key)) { setError(KEY_MESSAGE); return; }
    if (!f.name.trim()) { setError('Enter a name.'); return; }
    setError(null); setBusy(true);
    createQueue({ key: f.key, name: f.name.trim(), description: f.description.trim() })
      .then(() => { toast.success(`Created queue ${f.key}`); onDone(); })
      .catch((err) => {
        if (err?.status === 403) toast.error(err.message); else setError(err?.message || 'Could not create the queue.');
        setBusy(false);
      });
  }

  return (
    <form className="work-form" onSubmit={submit} noValidate>
      <label className="work-form-field" htmlFor="work-q-key">Queue key</label>
      <input id="work-q-key" className="work-field-input" value={f.key} onChange={set('key')}
        aria-invalid={error ? 'true' : undefined} aria-describedby={error ? 'work-q-error' : undefined} />
      <label className="work-form-field" htmlFor="work-q-name">Queue name</label>
      <input id="work-q-name" className="work-field-input" value={f.name} onChange={set('name')} />
      <label className="work-form-field" htmlFor="work-q-desc">Queue description</label>
      <input id="work-q-desc" className="work-field-input" value={f.description} onChange={set('description')} />
      {error && <div id="work-q-error" className="work-form-error" role="alert">{error}</div>}
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>Create queue</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

/** The queue half of /work/agents. `queues` is the loaded list; `reload` re-reads it. */
export default function WorkQueues({ queues, reload }) {
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [retiring, setRetiring] = useState(null);
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
        : <p><button type="button" className="btn btn-sm" onClick={() => setCreating(true)}>New queue</button></p>}
      {sorted.length === 0 ? <EmptyState message="No queues yet." /> : (
        <ul className="work-product-rows">
          {sorted.map((q) => (
            <li key={q.key} data-testid="queue-row" className={`work-product-row${q.retiredAt ? ' work-retired' : ''}`}>
              <div>
                <strong>{q.name}</strong> <span className="work-chip">{q.key}</span>
                {q.retiredAt && <span className="work-chip">retired</span>}
                {q.description && <div className="work-muted">{q.description}</div>}
              </div>
              {!q.retiredAt && (retiring === q.key
                ? (
                  <WorkConfirm yes={`Yes, retire ${q.key}`} disabled={busy} onYes={() => retire(q.key)} onNo={() => setRetiring(null)}>
                    Retire queue {q.key}? It will no longer be offered to agents.
                  </WorkConfirm>
                )
                : (
                  <div className="work-actions">
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
