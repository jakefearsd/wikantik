import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { acknowledgeItem, createProject, getMe } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { useCapabilities } from '../../hooks/useCapabilities';
import { useToast } from '../../hooks/useToast';
import WaitingLane from './WaitingLane';
import WorkLoadError from './WorkLoadError';

const KEY_PATTERN = /^[A-Z][A-Z0-9]{1,9}$/;
const KEY_MESSAGE = 'Key must be 2 to 10 characters: capital letters and digits, starting with a letter.';

function NewProjectForm({ onCancel }) {
  const toast = useToast();
  const navigate = useNavigate();
  const [f, setF] = useState({ key: '', name: '', description: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));

  function submit(e) {
    e.preventDefault();
    if (!KEY_PATTERN.test(f.key)) { setError(KEY_MESSAGE); return; }
    if (!f.name.trim()) { setError('Enter a name.'); return; }
    setError(null); setBusy(true);
    createProject({ key: f.key, name: f.name.trim(), description: f.description.trim() })
      .then((p) => { toast.success(`Created ${f.key}`); navigate(`/work/${p?.key || f.key}`); })
      .catch((err) => {
        setBusy(false);
        if (err?.status === 403) toast.error(err.message); else setError(err?.message || 'Could not create the project.');
      });
  }

  return (
    <form className="work-form" onSubmit={submit} noValidate>
      <label className="work-form-field" htmlFor="work-new-key">Key</label>
      <input id="work-new-key" className="work-field-input" value={f.key} onChange={set('key')} />
      <label className="work-form-field" htmlFor="work-new-name">Name</label>
      <input id="work-new-name" className="work-field-input" value={f.name} onChange={set('name')} />
      <label className="work-form-field" htmlFor="work-new-desc">Description</label>
      <textarea id="work-new-desc" className="work-field-input work-form-body" rows={2} value={f.description} onChange={set('description')} />
      {error && <div className="work-form-error" role="alert">{error}</div>}
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>Create project</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

/** /work: the viewer's projects and the items waiting on them. */
export default function WorkHome() {
  const { user, loading: authLoading } = useAuth();
  const { capabilities, loading } = useCapabilities();
  const enabled = !loading && !!capabilities?.work;
  const isAdmin = !!user?.roles?.includes('Admin');
  const [state, setState] = useState({ me: null, error: null });
  const [creating, setCreating] = useState(false);
  const [ackError, setAckError] = useState(null);
  const seq = useRef(0);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return getMe()
      .then((me) => { if (mine === seq.current) setState({ me, error: null }); })
      .catch((error) => {
        console.warn('WorkHome: could not load the projects', error);
        if (mine === seq.current) setState({ me: null, error });
      });
  }, []);

  useEffect(() => { if (enabled) load(); }, [enabled, load]);

  if (loading || authLoading) return <Spinner label="Loading…" />;
  if (!capabilities?.work) return <EmptyState message="Work management is not enabled" />;
  if (state.error) return <WorkLoadError error={state.error} fallback="Could not load your projects." onRetry={load} />;
  if (!state.me) return <Spinner label="Loading…" />;
  const { projects = [], waitingOn = [], deploymentsAwaiting = [] } = state.me;
  const keyOf = new Map(projects.map((p) => [p.id, p.key]));
  const active = projects.filter((p) => !p.archived);
  const archived = projects.filter((p) => p.archived);
  const projectList = (list) => (
    <ul className="work-project-list">
      {list.map((p) => (
        <li key={p.id}><Link to={`/work/${p.key}`}>{p.key} {p.name}</Link>
          <span className="work-muted"> · {p.role}</span></li>
      ))}
    </ul>
  );

  return (
    <div className="work-home">
      <h1 className="work-heading">Work</h1>
      {projects.length > 0 && (
        <p className="work-log-link">
          <Link to="/work/log" className="btn btn-sm">Assignments and claims</Link>
          <span className="work-muted"> Who took what, and what happened next.</span>
        </p>
      )}
      {(isAdmin || projects.length > 0) && (
        <div className="work-actions">
          {isAdmin && <Link to="/work/products">Products</Link>}
          {/* Anyone in a project may see the agents working in it; a lead registers their own there. */}
          <Link to="/work/agents">Agents &amp; queues</Link>
          <Link to="/work/deployments">Deployments</Link>
          {isAdmin && !creating && <button type="button" className="btn btn-sm" onClick={() => setCreating(true)}>New project</button>}
        </div>
      )}
      {isAdmin && creating && <NewProjectForm onCancel={() => setCreating(false)} />}
      {waitingOn.length > 0 && (
        <WaitingLane as="section" items={waitingOn}
          error={ackError}
          onAcknowledge={(i) => acknowledgeItem(i.key).then(() => { setAckError(null); return load(); })
            .catch((e) => {
              console.warn('WorkHome: could not acknowledge', i.key, e);
              setAckError(e?.message || 'Could not acknowledge that. Try again.');
            })}
          suffix={(i) => keyOf.has(i.projectId) && <span className="work-muted"> · {keyOf.get(i.projectId)}</span>} />
      )}
      {deploymentsAwaiting.length > 0 && (
        <section className="work-waiting" data-testid="deployments-awaiting">
          <strong>Deployments waiting for your approval ({deploymentsAwaiting.length})</strong>
          <ul className="work-waiting-list">
            {deploymentsAwaiting.map((d) => (
              <li key={d.id}>
                <Link to={`/work/deployments/${d.id}`}>{d.product} {d.version}</Link>
                <span className="work-muted"> · {d.environment} · {d.itemCount} {d.itemCount === 1 ? 'item' : 'items'}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      <h2 className="work-subheading">Projects</h2>
      {active.length === 0
        ? <EmptyState message={archived.length ? 'You have no active projects.' : 'You are not a member of any project yet.'} />
        : projectList(active)}
      {archived.length > 0 && (
        <section aria-label="Archived projects">
          <h2 className="work-subheading">Archived</h2>
          <p className="work-muted">Read-only; kept for reference.</p>
          {projectList(archived)}
        </section>
      )}
    </div>
  );
}
