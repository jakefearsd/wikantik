import { Link } from 'react-router-dom';
import { useCallback, useEffect, useRef, useState } from 'react';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { listAgents, listProjects, putMember, listQueues, releaseAgentSponsorship, retireAgent, rotateAgentKey, takeOverAgent } from '../../api/work';
import { apiUrl } from '../../api/client';
import { useAuth } from '../../hooks/useAuth';
import { useToast } from '../../hooks/useToast';
import WorkAgentForm from './WorkAgentForm';
import WorkConfirm from './WorkConfirm';
import WorkLoadError from './WorkLoadError';
import WorkQueues from './WorkQueues';
import WorkTokenPanel from './WorkTokenPanel';

const LOCK_HINT = 'work-agents-lock-hint';
const day = (iso) => (iso ? String(iso).slice(0, 10) : '');

// What each confirmation asks, says on its button, and says when the server refuses without a message.
const ACTIONS = {
  rotate: { yes: (n) => `Yes, rotate ${n}`, ask: (n) => `Rotate the key of ${n}? The old key stops working immediately.`, failed: 'Could not rotate the key.' },
  retire: { yes: (n) => `Yes, retire ${n}`, ask: (n) => `Retire ${n}? This releases all its claims, revokes its key, and cannot be undone.`, failed: 'Could not retire the agent.' },
  release: { yes: (n) => `Yes, release ${n}`, ask: (n) => `Release ${n}? This agent stops working until someone takes it over.`, failed: 'Could not release the agent.' },
  takeover: { yes: (n) => `Yes, take over ${n}`, ask: (n) => `Take over ${n}? You become responsible for what this agent does. It can never do more than you can.`, failed: 'Could not take over the agent.' },
};
// Why an agent that is not retired is doing nothing, in plain words.
const SUSPENDED = {
  no_sponsor: 'Suspended: no sponsor',
  sponsor_gone: 'Suspended: its sponsor no longer has an account',
};

/**
 * /work/agents: agents (with the one-time key panel) and queues. Open to anyone who can see a work project; the
 * server returns the agents that person may know of. An agent is managed by its sponsor or a wiki admin; queues
 * are instance-wide and managed by wiki admins only.
 */
export default function WorkAgents() {
  const { user, loading: authLoading } = useAuth();
  const toast = useToast();
  const isAdmin = !!user?.roles?.includes('Admin');
  const [state, setState] = useState({ agents: null, queues: null, projects: null, error: null });
  const [refreshError, setRefreshError] = useState(null); // a failed re-read that kept the previous lists
  const [editing, setEditing] = useState(null); // agent id being edited
  const [registering, setRegistering] = useState(false);
  const [pending, setPending] = useState(null); // { agent, action: a key of ACTIONS }
  const [busy, setBusy] = useState(false);
  const [pendingError, setPendingError] = useState(null); // the server's refusal of the open confirmation
  const [announce, setAnnounce] = useState('');
  const [admitWarning, setAdmitWarning] = useState(null); // projects a new agent could not be added to
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
    // The viewer's role per project says whether they lead anywhere; if that cannot be read it is simply unknown
    // (null) and every button stays, because the server is the authority.
    const projects = listProjects().catch((err) => { console.warn('WorkAgents: could not read the projects', err); return null; });
    return Promise.all([listAgents(), listQueues(), projects])
      .then(([agents, queues, projs]) => {
        if (mine === seq.current) { setState({ agents: agents || [], queues: queues || [], projects: projs, error: null }); setRefreshError(null); }
      })
      .catch((error) => {
        console.warn('WorkAgents: could not load agents and queues', error);
        if (mine !== seq.current) return;
        // Keep what is on screen (and any open key panel) when a re-read fails; only a first load blocks the page.
        setState((prev) => (prev.agents ? prev : { agents: null, queues: null, projects: null, error }));
        setRefreshError('Could not refresh the lists. What is shown may be out of date.');
      });
  }, []);

  const signedIn = !!user?.authenticated;
  useEffect(() => { if (signedIn) load(); }, [signedIn, load]);

  // Until /api/auth/user resolves the user is null: nothing is loaded for a visitor who is not signed in.
  if (authLoading) return <Spinner label="Loading…" />;
  const endpoint = `${window.location.origin}${apiUrl('/work-mcp')}`;
  const locked = keys.length > 0;
  const lockProps = { disabled: locked, 'aria-describedby': locked ? LOCK_HINT : undefined };
  const panel = keys.map((k, i) => (
    <WorkTokenPanel key={k.id} agentName={k.agentName} token={k.token} endpoint={endpoint} autoFocus={i === 0} onDone={() => dropKey(k.id)} />
  ));
  // The key panel sits above every load state: a failed or slow list read must never hide an unseen key.
  if (state.error) return <div className="work-products">{panel}<WorkLoadError error={state.error} fallback="Could not load agents and queues." onRetry={load} /></div>;
  if (!state.agents) return <div className="work-products">{panel}<Spinner label="Loading agents…" /></div>;

  const queueName = (key) => state.queues.find((q) => q.key === key)?.name || key;
  const sorted = [...state.agents].sort((a, b) => (a.retiredAt ? 1 : 0) - (b.retiredAt ? 1 : 0));

  function registered(made) {
    if (!mounted.current) return;
    setRegistering(false);
    const name = made.agent?.name || 'agent';
    addKey(name, made.token); // the key is shown first: nothing below may lose it
    const admitTo = made.admitTo || [];
    setAdmitWarning(null);
    Promise.allSettled(admitTo.map((key) => putMember(key, { principal: name, principalKind: 'agent', role: 'member' })))
      .then((results) => {
        const failed = admitTo.filter((_, i) => results[i].status === 'rejected');
        failed.forEach((key) => console.warn('WorkAgents: could not add the new agent to', key));
        if (mounted.current && failed.length) {
          setAdmitWarning(`Could not add ${name} to ${failed.join(', ')}. Add it from ${failed.length > 1 ? "those projects'" : "that project's"} Settings.`);
        }
        if (mounted.current) load();
      });
  }

  function ask(agent, action) {
    setPendingError(null);
    setPending({ agent, action });
  }

  function confirmed() {
    if (busy || locked || !pending) return;
    const { agent, action } = pending;
    setBusy(true);
    const done = () => { if (mounted.current) { setPending(null); setBusy(false); } };
    // Keep the confirmation open with the server's own words, so the person sees why and can act on it.
    const refused = (err) => {
      if (!mounted.current) return;
      setPendingError(err?.message || ACTIONS[action].failed);
      setBusy(false);
    };
    if (action === 'rotate') {
      rotateAgentKey(agent.id)
        .then((made) => { if (mounted.current) addKey(agent.name, made.token); done(); })
        .catch(refused);
    } else if (action === 'retire') {
      retireAgent(agent.id)
        .then(() => { toast.success(`Retired ${agent.name}`); done(); return load(); })
        .catch(refused);
    } else {
      (action === 'takeover' ? takeOverAgent(agent.id) : releaseAgentSponsorship(agent.id))
        .then(() => {
          toast.success(action === 'takeover' ? `You now sponsor ${agent.name}` : `Released ${agent.name}`);
          done();
          return load();
        })
        .catch(refused);
    }
  }

  // The server is the authority; these only decide which buttons are worth offering.
  // Leading an unarchived project is what registering, editing and taking over need (besides being a wiki admin).
  // Unknown (the projects could not be read) offers the button and lets the server answer.
  const leads = state.projects ? state.projects.some((p) => p.role === 'lead' && !p.archived) : true;
  const eligible = (state.projects || []).filter((p) => !p.archived && (isAdmin || p.role === 'lead'));
  const mayRegister = isAdmin || leads;
  const manages = (a) => !a.retiredAt && (isAdmin || a.sponsoredByMe);
  const mayEdit = (a) => manages(a) && (isAdmin || leads);
  // The server decides who may take an agent over (it knows which projects the agent is in); the page only asks.
  const mayTakeOver = (a) => !a.retiredAt && !a.sponsoredByMe && a.mayTakeOver === true;

  return (
    <div className="work-products work-agents">
      <h1 className="work-heading"><Link to="/work">Work</Link> <span aria-hidden="true">▸</span> Agents &amp; queues</h1>
      {panel}
      <p role="status" aria-live="polite" className="work-visually-hidden" data-testid="work-agents-announce">{announce}</p>
      {locked && <p id={LOCK_HINT} className="work-visually-hidden">Copy or dismiss the key shown above first.</p>}
      {admitWarning && <div className="work-form-error" role="alert">{admitWarning}</div>}
      {refreshError && <div className="work-form-error" role="alert">{refreshError}</div>}
      <section className="work-section" aria-label="Agents">
        <h2 className="work-subheading">Agents</h2>
        {registering
          ? <WorkAgentForm queues={state.queues} projects={eligible} locked={locked} lockedHint={LOCK_HINT} onRegistered={registered} onCancel={() => setRegistering(false)} />
          : mayRegister && <p><button type="button" className="btn btn-sm" {...lockProps} onClick={() => setRegistering(true)}>Register agent</button></p>}
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
                    {a.sponsorName ? `Sponsor: ${a.sponsorName}` : 'No sponsor'}
                  </div>
                  {!a.retiredAt && SUSPENDED[a.status] && <div className="work-form-error">{SUSPENDED[a.status]}</div>}
                  {(a.createdAt || a.retiredAt) && (
                    <div className="work-muted">
                      {a.createdAt ? `Created ${day(a.createdAt)}` : ''}{a.retiredAt && `${a.createdAt ? ', retired' : 'Retired'} ${day(a.retiredAt)}`}
                    </div>
                  )}
                </div>}
                {editing !== a.id && (manages(a) || mayTakeOver(a)) && (pending?.agent.id === a.id
                  ? (
                    <WorkConfirm disabled={busy || locked} describedBy={LOCK_HINT} error={pendingError}
                      onYes={confirmed} onNo={() => { setPending(null); setPendingError(null); }}
                      yes={ACTIONS[pending.action].yes(a.name)}>
                      {ACTIONS[pending.action].ask(a.name)}
                    </WorkConfirm>
                  )
                  : (
                    <div className="work-actions">
                      {manages(a) && (
                        <>
                          {mayEdit(a) && (
                            <button type="button" className="btn btn-sm" aria-label={`Edit ${a.name}`} {...lockProps}
                              onClick={() => setEditing(a.id)}>Edit</button>
                          )}
                          <button type="button" className="btn btn-sm" aria-label={`Rotate key of ${a.name}`} {...lockProps}
                            onClick={() => ask(a, 'rotate')}>Rotate key</button>
                          <button type="button" className="btn btn-sm btn-ghost" aria-label={`Retire ${a.name}`} {...lockProps}
                            onClick={() => ask(a, 'retire')}>Retire</button>
                        </>
                      )}
                      {a.sponsoredByMe && !a.retiredAt && (
                        <button type="button" className="btn btn-sm btn-ghost" aria-label={`Release sponsorship of ${a.name}`} {...lockProps}
                          onClick={() => ask(a, 'release')}>Release sponsorship</button>
                      )}
                      {mayTakeOver(a) && (
                        <button type="button" className="btn btn-sm" aria-label={`Take over ${a.name}`} {...lockProps}
                          onClick={() => ask(a, 'takeover')}>Take over</button>
                      )}
                    </div>
                  ))}
              </li>
            ))}
          </ul>
        )}
      </section>
      <WorkQueues queues={state.queues} reload={load} canManage={isAdmin} />
    </div>
  );
}
