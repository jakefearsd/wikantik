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
import WorkQueues from './WorkQueues';
import WorkTokenPanel from './WorkTokenPanel';

const day = (iso) => (iso ? String(iso).slice(0, 10) : '');

/** /work/agents: instance admins only. Agents (with the one-time key panel) and queues. */
export default function WorkAgents() {
  const { user, loading: authLoading } = useAuth();
  const toast = useToast();
  const isAdmin = !!user?.roles?.includes('Admin');
  const [state, setState] = useState({ agents: null, queues: null, error: null });
  const [registering, setRegistering] = useState(false);
  const [pending, setPending] = useState(null); // { agent, action: 'rotate' | 'retire' }
  const [busy, setBusy] = useState(false);
  // The one-time key. Component state only: dropped on Done and on unmount, never persisted anywhere.
  const [secret, setSecret] = useState(null); // { agentName, token }
  const seq = useRef(0);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return Promise.all([listAgents(), listQueues()])
      .then(([agents, queues]) => { if (mine === seq.current) setState({ agents: agents || [], queues: queues || [], error: null }); })
      .catch((error) => {
        console.warn('WorkAgents: could not load agents and queues', error);
        if (mine === seq.current) setState({ agents: null, queues: null, error });
      });
  }, []);

  useEffect(() => { if (isAdmin) load(); }, [isAdmin, load]);

  // Until /api/auth/user resolves the user is null: deciding now would bounce a signed-in admin.
  if (authLoading) return <Spinner label="Loading…" />;
  if (!isAdmin) return <Navigate to="/work" replace />;
  if (state.error) return <WorkLoadError error={state.error} fallback="Could not load agents and queues." onRetry={load} />;
  if (!state.agents) return <Spinner label="Loading agents…" />;

  const queueName = (key) => state.queues.find((q) => q.key === key)?.name || key;
  const sorted = [...state.agents].sort((a, b) => (a.retiredAt ? 1 : 0) - (b.retiredAt ? 1 : 0));

  function registered(made) {
    setRegistering(false);
    setSecret({ agentName: made.agent?.name || 'agent', token: made.token });
    load();
  }

  function confirmed() {
    if (busy || !pending) return;
    const { agent, action } = pending;
    setBusy(true);
    const done = () => { if (mounted.current) { setPending(null); setBusy(false); } };
    if (action === 'rotate') {
      rotateAgentKey(agent.id)
        .then((made) => { if (mounted.current) setSecret({ agentName: agent.name, token: made.token }); })
        .catch((err) => toast.error(err?.message || 'Could not rotate the key.'))
        .finally(done);
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
      {secret && <WorkTokenPanel agentName={secret.agentName} token={secret.token} onDone={() => setSecret(null)} />}
      <section className="work-section" aria-label="Agents">
        <h2 className="work-subheading">Agents</h2>
        {registering
          ? <WorkAgentForm queues={state.queues.filter((q) => !q.retiredAt)} onRegistered={registered} onCancel={() => setRegistering(false)} />
          : <p><button type="button" className="btn btn-sm" onClick={() => setRegistering(true)}>Register agent</button></p>}
        {sorted.length === 0 ? <EmptyState message="No agents registered yet." /> : (
          <ul className="work-product-rows">
            {sorted.map((a) => (
              <li key={a.id} data-testid="agent-row" className={`work-product-row${a.retiredAt ? ' work-retired' : ''}`}>
                <div>
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
                </div>
                {!a.retiredAt && (pending?.agent.id === a.id
                  ? (
                    <WorkConfirm disabled={busy} onYes={confirmed} onNo={() => setPending(null)}
                      yes={pending.action === 'rotate' ? `Yes, rotate ${a.name}` : `Yes, retire ${a.name}`}>
                      {pending.action === 'rotate'
                        ? `Rotate the key of ${a.name}? The old key stops working immediately.`
                        : `Retire ${a.name}? This releases all its claims, revokes its key, and cannot be undone.`}
                    </WorkConfirm>
                  )
                  : (
                    <div className="work-actions">
                      <button type="button" className="btn btn-sm" aria-label={`Rotate key of ${a.name}`}
                        onClick={() => setPending({ agent: a, action: 'rotate' })}>Rotate key</button>
                      <button type="button" className="btn btn-sm btn-ghost" aria-label={`Retire ${a.name}`}
                        onClick={() => setPending({ agent: a, action: 'retire' })}>Retire</button>
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
