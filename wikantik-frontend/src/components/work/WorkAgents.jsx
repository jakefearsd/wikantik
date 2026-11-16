import { useCallback, useEffect, useRef, useState } from 'react';
import { Navigate } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { listAgents, listQueues, retireAgent, rotateAgentKey } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { useToast } from '../../hooks/useToast';
import WorkAgentForm from './WorkAgentForm';
import WorkConfirm from './WorkConfirm';
import WorkLoadError from './WorkLoadError';
import WorkOwnerPicker from './WorkOwnerPicker';
import WorkQueues from './WorkQueues';
import WorkTokenPanel from './WorkTokenPanel';

const LOCK_HINT = 'work-agents-lock-hint';
const day = (iso) => (iso ? String(iso).slice(0, 10) : '');

/** /work/agents: instance admins only. Agents (with the one-time key panel) and queues. */
export default function WorkAgents() {
  const { user, loading: authLoading } = useAuth();
  const toast = useToast();
  const isAdmin = !!user?.roles?.includes('Admin');
  const [state, setState] = useState({ agents: null, queues: null, error: null });
  const [refreshError, setRefreshError] = useState(null); // a failed re-read that kept the previous lists
  const [editing, setEditing] = useState(null); // agent id being edited
  const [registering, setRegistering] = useState(false);
  const [pending, setPending] = useState(null); // { agent, action: 'rotate' | 'retire' }
  const [busy, setBusy] = useState(false);
  const [newOwner, setNewOwner] = useState(''); // rotate: display name of the person who should own the new key; '' keeps the owner
  const [pendingError, setPendingError] = useState(null); // the server's refusal of the open confirmation
  const [announce, setAnnounce] = useState('');
  // Every minted key, each with its own panel. Append-only until that entry's Done: a later register or rotate can
  // never replace a key the admin has not copied. Component state only: dropped on Done and on unmount.
  const [keys, setKeys] = useState([]); // [{ id, agentName, token }]
  const nextKey = useRef(0);
  const keyCount = useRef(0);
  const addKey = (agentName, token) => {
    // A key shown while another panel is open must not pull focus from it: say so politely instead.
    if (keyCount.current > 0) setAnnounce(`A key for ${agentName} is shown below.`);
    keyCount.current += 1;
    setKeys((k) => [...k, { id: ++nextKey.current, agentName, token }]);
  };
  const dropKey = (id) => {
    keyCount.current = Math.max(0, keyCount.current - 1);
    setKeys((all) => all.filter((x) => x.id !== id));
  };
  const seq = useRef(0);
  const mounted = useRef(true);
  // Set true in the body too: StrictMode's simulated unmount would otherwise leave it false for good.
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return Promise.all([listAgents(), listQueues()])
      .then(([agents, queues]) => {
        if (mine === seq.current) { setState({ agents: agents || [], queues: queues || [], error: null }); setRefreshError(null); }
      })
      .catch((error) => {
        console.warn('WorkAgents: could not load agents and queues', error);
        if (mine !== seq.current) return;
        // Keep what is on screen (and any open key panel) when a re-read fails; only a first load blocks the page.
        setState((prev) => (prev.agents ? prev : { agents: null, queues: null, error }));
        setRefreshError('Could not refresh the lists. What is shown may be out of date.');
      });
  }, []);

  useEffect(() => { if (isAdmin) load(); }, [isAdmin, load]);

  // Until /api/auth/user resolves the user is null: deciding now would bounce a signed-in admin.
  if (authLoading) return <Spinner label="Loading…" />;
  if (!isAdmin) return <Navigate to="/work" replace />;
  const locked = keys.length > 0;
  const lockProps = { disabled: locked, 'aria-describedby': locked ? LOCK_HINT : undefined };
  const panel = keys.map((k, i) => (
    <WorkTokenPanel key={k.id} agentName={k.agentName} token={k.token} autoFocus={i === 0} onDone={() => dropKey(k.id)} />
  ));
  // The key panel sits above every load state: a failed or slow list read must never hide an unseen key.
  if (state.error) return <div className="work-products">{panel}<WorkLoadError error={state.error} fallback="Could not load agents and queues." onRetry={load} /></div>;
  if (!state.agents) return <div className="work-products">{panel}<Spinner label="Loading agents…" /></div>;

  const queueName = (key) => state.queues.find((q) => q.key === key)?.name || key;
  const sorted = [...state.agents].sort((a, b) => (a.retiredAt ? 1 : 0) - (b.retiredAt ? 1 : 0));

  function registered(made) {
    if (!mounted.current) return;
    setRegistering(false);
    addKey(made.agent?.name || 'agent', made.token);
    load();
  }

  function confirmed() {
    if (busy || locked || !pending) return;
    const { agent, action } = pending;
    setBusy(true);
    const done = () => { if (mounted.current) { setPending(null); setBusy(false); } };
    if (action === 'rotate') {
      const owner = newOwner || null;
      (owner ? rotateAgentKey(agent.id, owner) : rotateAgentKey(agent.id))
        .then((made) => { if (mounted.current) { addKey(agent.name, made.token); setNewOwner(''); } done(); })
        .catch((err) => {
          // Keep the confirmation open with the server's own words (e.g. an unknown new owner), so it can be fixed in place.
          if (!mounted.current) return;
          setPendingError(err?.message || 'Could not rotate the key.');
          setBusy(false);
        });
    } else {
      retireAgent(agent.id)
        .then(() => { toast.success(`Retired ${agent.name}`); return load(); })
        .catch((err) => toast.error(err?.message || 'Could not retire the agent.'))
        .finally(done);
    }
  }

  return (
    <div className="work-products work-agents">
      <h1 className="work-heading">Agents &amp; queues</h1>
      {panel}
      <p role="status" aria-live="polite" className="work-visually-hidden" data-testid="work-agents-announce">{announce}</p>
      {locked && <p id={LOCK_HINT} className="work-visually-hidden">Copy or dismiss the key shown above first.</p>}
      {refreshError && <div className="work-form-error" role="alert">{refreshError}</div>}
      <section className="work-section" aria-label="Agents">
        <h2 className="work-subheading">Agents</h2>
        {registering
          ? <WorkAgentForm queues={state.queues} locked={locked} lockedHint={LOCK_HINT} onRegistered={registered} onCancel={() => setRegistering(false)} />
          : <p><button type="button" className="btn btn-sm" {...lockProps} onClick={() => setRegistering(true)}>Register agent</button></p>}
        {sorted.length === 0 ? <EmptyState message="No agents registered yet." /> : (
          <ul className="work-product-rows">
            {sorted.map((a) => (
              <li key={a.id} data-testid="agent-row" className={`work-product-row${a.retiredAt ? ' work-retired' : ''}`}>
                {editing === a.id
                  ? <WorkAgentForm agent={a} queues={state.queues} onSaved={() => { setEditing(null); load(); }} onCancel={() => setEditing(null)} />
                  : <div>
                  <strong>{a.name}</strong> {a.retiredAt && <span className="work-chip">retired</span>}
                  <div className="work-muted">
                    {[a.vendor, a.runtime].filter(Boolean).join(' · ') || 'No vendor or runtime set'}
                  </div>
                  <div className="work-muted">
                    Queues: {(a.queues || []).length ? a.queues.map((k) => <span key={k} className="work-chip">{queueName(k)}</span>) : 'none'}
                  </div>
                  <div className="work-muted">
                    Created {day(a.createdAt)}{a.retiredAt && `, retired ${day(a.retiredAt)}`}
                  </div>
                </div>}
                {!a.retiredAt && editing !== a.id && (pending?.agent.id === a.id
                  ? (
                    <WorkConfirm disabled={busy || locked} describedBy={LOCK_HINT} error={pendingError}
                      onYes={confirmed} onNo={() => { setPending(null); setPendingError(null); setNewOwner(''); }}
                      fields={pending.action === 'rotate' ? (
                        <WorkOwnerPicker owner={newOwner} onChange={setNewOwner} disabled={busy} idPrefix="work-r-owner"
                          findLabel="Find new owner" ariaLabel="New owner" placeholder="Keep the current owner" />
                      ) : null}
                      yes={pending.action === 'rotate' ? `Yes, rotate ${a.name}` : `Yes, retire ${a.name}`}>
                      {pending.action === 'rotate'
                        ? `Rotate the key of ${a.name}? The old key stops working immediately.`
                        : `Retire ${a.name}? This releases all its claims, revokes its key, and cannot be undone.`}
                    </WorkConfirm>
                  )
                  : (
                    <div className="work-actions">
                      <button type="button" className="btn btn-sm" aria-label={`Edit ${a.name}`} {...lockProps}
                        onClick={() => setEditing(a.id)}>Edit</button>
                      <button type="button" className="btn btn-sm" aria-label={`Rotate key of ${a.name}`} {...lockProps}
                        onClick={() => { setPendingError(null); setNewOwner(''); setPending({ agent: a, action: 'rotate' }); }}>Rotate key</button>
                      <button type="button" className="btn btn-sm btn-ghost" aria-label={`Retire ${a.name}`} {...lockProps}
                        onClick={() => { setPendingError(null); setPending({ agent: a, action: 'retire' }); }}>Retire</button>
                    </div>
                  ))}
              </li>
            ))}
          </ul>
        )}
      </section>
      <WorkQueues queues={state.queues} reload={load} />
    </div>
  );
}
