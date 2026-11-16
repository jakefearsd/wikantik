import { useEffect, useState } from 'react';
import Select from '../ui/Select';
import { registerAgent } from '../../api/work';
import { api } from '../../api/client';
import { useToast } from '../../hooks/useToast';

const NAME_PATTERN = /^[a-z0-9][a-z0-9._-]{0,39}(@[a-z0-9][a-z0-9.-]{0,39})?$/;
const NAME_MESSAGE = 'Agent name is lowercase letters, digits, dots, dashes and underscores, optionally followed by @host (up to 40 characters before the @).';

/** Register an agent. `onRegistered({ agent, token })` receives the one-time token; this form keeps none of it. */
export default function WorkAgentForm({ queues, onRegistered, onCancel }) {
  const toast = useToast();
  const [f, setF] = useState({ name: '', vendor: '', runtime: '', owner: '' });
  const [picked, setPicked] = useState([]);
  const [owners, setOwners] = useState([]);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));

  useEffect(() => {
    let live = true;
    api.listMentionableUsers('', 50)
      .then((r) => { if (live) setOwners((r?.users || []).map((u) => u.displayName).filter(Boolean)); })
      .catch((err) => console.warn('WorkAgentForm: could not load the owner list', err));
    return () => { live = false; };
  }, []);

  const toggle = (key) => setPicked((p) => (p.includes(key) ? p.filter((k) => k !== key) : [...p, key]));

  function submit(e) {
    e.preventDefault();
    if (busy) return;
    if (!NAME_PATTERN.test(f.name)) { setError(NAME_MESSAGE); return; }
    setError(null); setBusy(true);
    registerAgent({ name: f.name, vendor: f.vendor.trim(), runtime: f.runtime.trim(), queues: picked, owner: f.owner || null })
      .then((made) => onRegistered(made))
      .catch((err) => {
        setBusy(false);
        if (err?.status === 403) toast.error(err.message); else setError(err?.message || 'Could not register the agent.');
      });
  }

  return (
    <form className="work-form" onSubmit={submit} noValidate>
      <label className="work-form-field" htmlFor="work-a-name">Agent name</label>
      <input id="work-a-name" className="work-field-input" value={f.name} onChange={set('name')}
        aria-invalid={error ? 'true' : undefined} aria-describedby={error ? 'work-a-error' : undefined} />
      <label className="work-form-field" htmlFor="work-a-vendor">Vendor</label>
      <input id="work-a-vendor" className="work-field-input" value={f.vendor} onChange={set('vendor')} />
      <label className="work-form-field" htmlFor="work-a-runtime">Runtime</label>
      <input id="work-a-runtime" className="work-field-input" value={f.runtime} onChange={set('runtime')} />
      <span className="work-form-field" id="work-a-owner-label">Key owner</span>
      <Select ariaLabel="Key owner" value={f.owner} options={owners} placeholder="Me (default)" disabled={busy}
        onChange={(owner) => setF((s) => ({ ...s, owner }))} />
      <fieldset className="work-fieldset">
        <legend>Queues</legend>
        {queues.length === 0 && <span className="work-muted">No queues to join yet.</span>}
        {queues.map((q) => (
          <label key={q.key} className="work-check">
            <input type="checkbox" checked={picked.includes(q.key)} onChange={() => toggle(q.key)} /> {q.name}
          </label>
        ))}
      </fieldset>
      {error && <div id="work-a-error" className="work-form-error" role="alert">{error}</div>}
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>Register</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}
