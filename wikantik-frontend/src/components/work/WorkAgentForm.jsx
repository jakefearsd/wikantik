import { useEffect, useState } from 'react';
import Select from '../ui/Select';
import { registerAgent, updateAgent } from '../../api/work';
import { api } from '../../api/client';
import { useToast } from '../../hooks/useToast';

const NAME_PATTERN = /^[a-z0-9][a-z0-9._-]{0,39}(@[a-z0-9][a-z0-9.-]{0,39})?$/;
const NAME_MESSAGE = 'Agent name is lowercase letters, digits, dots, dashes and underscores, optionally followed by @host (up to 40 characters before the @).';
const FIELDS = ['name', 'queues', 'owner'];
const SEARCH_DELAY_MS = 250;

/** Field errors by control: the server's `field`, else a conflict on the name, else the form as a whole. */
function toErrors(err, fallback) {
  const field = err?.body?.field;
  const message = err?.message || fallback;
  if (FIELDS.includes(field)) return { [field]: message };
  if (err?.status === 409 && err?.body?.reason === 'exists') return { name: message };
  return { form: message };
}

/** Key-owner picker: search-as-you-type over the people directory; the chosen name stays selectable. */
function OwnerPicker({ owner, onChange, disabled, error, errorId }) {
  const [query, setQuery] = useState('');
  const [names, setNames] = useState([]);
  const [loadError, setLoadError] = useState(null);

  useEffect(() => {
    let live = true;
    const timer = setTimeout(() => {
      api.listMentionableUsers(query, 20)
        .then((r) => { if (live) { setNames((r?.users || []).map((u) => u.displayName).filter(Boolean)); setLoadError(null); } })
        .catch((err) => {
          console.warn('WorkAgentForm: could not search users', err);
          if (live) setLoadError('Could not search users.');
        });
    }, query === '' ? 0 : SEARCH_DELAY_MS);
    return () => { live = false; clearTimeout(timer); };
  }, [query]);

  const options = owner && !names.includes(owner) ? [owner, ...names] : names;
  return (
    <>
      <label className="work-form-field" htmlFor="work-a-owner-q">Find key owner</label>
      <input id="work-a-owner-q" className="work-field-input" value={query} onChange={(e) => setQuery(e.target.value)} disabled={disabled}
        aria-describedby={loadError ? 'work-a-owner-load' : undefined} />
      {loadError && <div id="work-a-owner-load" className="work-field-error" role="alert">{loadError}</div>}
      <Select ariaLabel="Key owner" value={owner} options={options} placeholder="Me (default)" disabled={disabled}
        onChange={onChange} />
      {error && <div id={errorId} className="work-field-error" role="alert">{error}</div>}
    </>
  );
}

/**
 * Register an agent, or (with `agent`) edit one. `onRegistered({ agent, token })` receives the one-time token; this
 * form keeps none of it. Editing never mints or shows a key and cannot change the key owner.
 */
export default function WorkAgentForm({ queues, agent, locked = false, lockedHint, onRegistered, onSaved, onCancel }) {
  const toast = useToast();
  const editing = !!agent;
  const [f, setF] = useState({ name: agent?.name || '', vendor: agent?.vendor || '', runtime: agent?.runtime || '', owner: '' });
  const [picked, setPicked] = useState(agent?.queues || []);
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);
  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));
  const toggle = (key) => setPicked((p) => (p.includes(key) ? p.filter((k) => k !== key) : [...p, key]));
  const described = (field) => (errors[field] ? `work-a-err-${field}` : undefined);
  const note = (field) => errors[field] && <div id={`work-a-err-${field}`} className="work-field-error" role="alert">{errors[field]}</div>;

  // Offered: active queues, plus any retired queue this agent already serves (checked, so saving does not drop it).
  const offered = queues.filter((q) => !q.retiredAt || (agent?.queues || []).includes(q.key));

  function submit(e) {
    e.preventDefault();
    if (busy || (locked && !editing)) return;
    if (!NAME_PATTERN.test(f.name)) { setErrors({ name: NAME_MESSAGE }); return; }
    setErrors({}); setBusy(true);
    const fields = { name: f.name, vendor: f.vendor.trim(), runtime: f.runtime.trim(), queues: picked };
    const request = editing ? updateAgent(agent.id, fields) : registerAgent({ ...fields, owner: f.owner || null });
    request
      .then((made) => {
        if (editing) { toast.success(`Saved ${f.name}`); onSaved(); } else onRegistered(made);
      })
      .catch((err) => {
        setBusy(false);
        if (err?.status === 403) toast.error(err.message);
        else setErrors(toErrors(err, editing ? 'Could not save the agent.' : 'Could not register the agent.'));
      });
  }

  return (
    <form className="work-form" onSubmit={submit} noValidate>
      <label className="work-form-field" htmlFor="work-a-name">Agent name</label>
      <input id="work-a-name" className="work-field-input" value={f.name} onChange={set('name')}
        aria-invalid={errors.name ? 'true' : undefined} aria-describedby={described('name')} />
      {note('name')}
      <label className="work-form-field" htmlFor="work-a-vendor">Vendor</label>
      <input id="work-a-vendor" className="work-field-input" value={f.vendor} onChange={set('vendor')} />
      <label className="work-form-field" htmlFor="work-a-runtime">Runtime</label>
      <input id="work-a-runtime" className="work-field-input" value={f.runtime} onChange={set('runtime')} />
      {!editing && (
        <OwnerPicker owner={f.owner} disabled={busy} onChange={(owner) => setF((s) => ({ ...s, owner }))}
          error={errors.owner} errorId="work-a-err-owner" />
      )}
      <fieldset className="work-fieldset" aria-describedby={described('queues')}>
        <legend>Queues</legend>
        {offered.length === 0 && <span className="work-muted">No queues to join yet.</span>}
        {offered.map((q) => (
          <label key={q.key} className="work-check">
            <input type="checkbox" checked={picked.includes(q.key)} onChange={() => toggle(q.key)} /> {q.name}{q.retiredAt ? ' (retired)' : ''}
          </label>
        ))}
        <p className="work-muted">
          Removing a queue from an agent: it keeps any work it has already claimed; it just will not be offered new work from that queue.
        </p>
      </fieldset>
      {note('queues')}
      {errors.form && <div className="work-form-error" role="alert">{errors.form}</div>}
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy || (locked && !editing)}
          aria-describedby={locked && !editing ? lockedHint : undefined}>{editing ? 'Save agent' : 'Register'}</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}
