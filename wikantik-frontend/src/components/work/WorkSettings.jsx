import { useCallback, useEffect, useRef, useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import Select from '../ui/Select';
import Spinner from '../ui/Spinner';
import { archiveProject, listMembers, putMember, removeMember, updateProject } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { useToast } from '../../hooks/useToast';
import { useWorkProject } from './useWorkProject';
import WorkLoadError from './WorkLoadError';

const ROLES = ['viewer', 'member', 'lead'];
const KINDS = [{ value: 'user', label: 'User' }, { value: 'group', label: 'Group' }];
const LATER = 'Takes effect in a later release';

/** Inline confirmation strip: no browser dialogs. */
function Confirm({ children, yes, onYes, onNo, disabled = false }) {
  return (
    <div className="work-confirm" role="alertdialog" aria-label="Confirm">
      <p>{children}</p>
      <div className="work-actions">
        <button type="button" className="btn btn-sm btn-danger" disabled={disabled} onClick={onYes}>{yes}</button>
        <button type="button" className="btn btn-sm btn-ghost" onClick={onNo}>Cancel</button>
      </div>
    </div>
  );
}

function Details({ project, onSaved }) {
  const toast = useToast();
  const [name, setName] = useState(project.name || '');
  const [description, setDescription] = useState(project.description || '');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  function save(e) {
    e.preventDefault();
    setBusy(true); setError(null);
    updateProject(project.key, { name, description })
      .then(() => { toast.success('Project details saved'); onSaved(); })
      .catch((err) => {
        if (err?.status === 403) toast.error(err.message); else setError(err?.message || 'Could not save.');
      })
      .finally(() => setBusy(false));
  }

  return (
    <form className="work-form" onSubmit={save}>
      <label className="work-form-field" htmlFor="work-set-name">Project name</label>
      <input id="work-set-name" className="work-field-input" value={name} onChange={(e) => setName(e.target.value)} />
      <label className="work-form-field" htmlFor="work-set-desc">Description</label>
      <textarea id="work-set-desc" className="work-field-input work-form-body" rows={3} value={description}
        onChange={(e) => setDescription(e.target.value)} />
      {error && <div className="work-form-error" role="alert">{error}</div>}
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy || !name.trim()}>Save details</button>
      </div>
    </form>
  );
}

function Members({ project, login, reloadProject }) {
  const toast = useToast();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [state, setState] = useState({ members: null, error: null });
  const [pending, setPending] = useState(null); // { member, action: 'role' | 'remove', role? }
  const [adding, setAdding] = useState({ principal: '', kind: 'user', role: 'member' });
  const [addError, setAddError] = useState(null);
  const seq = useRef(0);

  // quiet: a refresh after the viewer changed their own access keeps the list on failure (a 403/404 there is
  // expected, not an error to show).
  const load = useCallback(({ quiet = false } = {}) => {
    const mine = ++seq.current;
    return listMembers(project.key)
      .then((members) => { if (mine === seq.current) setState({ members: members || [], error: null }); })
      .catch((error) => {
        console.warn('WorkSettings: could not load members', error);
        if (mine === seq.current && !quiet) setState({ members: null, error });
      });
  }, [project.key]);

  useEffect(() => { load(); }, [load]);

  const isMe = (m) => m.principalKind === 'user' && !!login && m.principal === login;
  const fail = (err, fallback) => toast.error(err?.message || fallback);

  // One request at a time: every control is disabled while one is in flight, so a double click cannot submit twice.
  function run(request, { self, afterSelf }) {
    if (busy) return Promise.resolve();
    setBusy(true);
    return request()
      .then(() => (self ? afterSelf() : load()))
      .catch((err) => { fail(err, 'Could not change the member.'); load({ quiet: !!self }); })
      .finally(() => setBusy(false));
  }

  // Your own role changed: the project context must be re-read so the page redirects when you lost lead.
  const afterOwnRoleChange = () => Promise.resolve(reloadProject()).then(() => load({ quiet: true }));
  // Removing yourself leaves no access (the board would be not-found), so go to the work home.
  const afterOwnRemoval = () => { navigate('/work'); };

  function changeRole(m, role) {
    if (role === m.role) return;
    if (isMe(m) && m.role === 'lead') { setPending({ member: m, action: 'role', role }); return; }
    doRole(m, role);
  }
  function doRole(m, role) {
    setPending(null);
    return run(() => putMember(project.key, { principal: m.principal, principalKind: m.principalKind, role }),
      { self: isMe(m), afterSelf: afterOwnRoleChange });
  }
  function askRemove(m) {
    if (isMe(m)) { setPending({ member: m, action: 'remove' }); return; }
    doRemove(m);
  }
  function doRemove(m) {
    setPending(null);
    return run(() => removeMember(project.key, { principal: m.principal, principalKind: m.principalKind }),
      { self: isMe(m), afterSelf: afterOwnRemoval });
  }

  function add(e) {
    e.preventDefault();
    if (busy) return;
    const principal = adding.principal.trim();
    if (!principal) { setAddError('Enter a login or group name.'); return; }
    setAddError(null);
    setBusy(true);
    putMember(project.key, { principal, principalKind: adding.kind, role: adding.role })
      .then(() => { setAdding((a) => ({ ...a, principal: '' })); return load(); })
      .catch((err) => {
        if (err?.status === 403) toast.error(err.message); else setAddError(err?.message || 'Could not add the member.');
      })
      .finally(() => setBusy(false));
  }

  if (state.error) return <WorkLoadError error={state.error} fallback="Could not load the members." onRetry={load} />;
  if (!state.members) return <Spinner label="Loading members…" />;

  return (
    <div>
      {state.members.length === 0
        ? <p className="work-muted">This project has no members.</p>
        : (
          <ul className="work-member-list">
            {state.members.map((m) => (
              <li key={`${m.principalKind}:${m.principal}`} className="work-member-row">
                <span className="work-member-name">{m.displayName || 'Unnamed member'}</span>
                <span className="work-chip">{m.principalKind}</span>
                <Select ariaLabel={`Role of ${m.displayName || 'member'}`} value={m.role} options={ROLES}
                  disabled={busy} onChange={(r) => changeRole(m, r)} />
                <button type="button" className="work-link-button" aria-label={`Remove ${m.displayName || 'member'}`}
                  disabled={busy} onClick={() => askRemove(m)}>Remove</button>
              </li>
            ))}
          </ul>
        )}
      {pending && (
        <Confirm
          disabled={busy}
          yes={pending.action === 'remove' ? 'Yes, remove me' : 'Yes, change my role'}
          onYes={() => (pending.action === 'remove' ? doRemove(pending.member) : doRole(pending.member, pending.role))}
          onNo={() => { setPending(null); load(); }}
        >
          {pending.action === 'remove'
            ? 'You are about to remove yourself as lead. You will lose access to these settings.'
            : `You are about to change your own role from lead to ${pending.role}. You will lose access to these settings.`}
        </Confirm>
      )}
      <form className="work-link-form" onSubmit={add}>
        <label className="work-visually-hidden" htmlFor="work-add-principal">Login or group name</label>
        <input id="work-add-principal" className="work-field-input" placeholder="Login or group name" value={adding.principal}
          onChange={(e) => setAdding({ ...adding, principal: e.target.value })} aria-invalid={addError ? 'true' : undefined} />
        <Select ariaLabel="Kind" value={adding.kind} options={KINDS} disabled={busy} onChange={(kind) => setAdding({ ...adding, kind })} />
        <Select ariaLabel="Role for new member" value={adding.role} options={ROLES} onChange={(role) => setAdding({ ...adding, role })} />
        <button type="submit" className="btn btn-sm" disabled={busy}>Add member</button>
        {addError && <span className="work-field-error" role="alert">{addError}</span>}
      </form>
    </div>
  );
}

function ArchiveSection({ project }) {
  const toast = useToast();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);

  function archive() {
    if (busy) return;
    setBusy(true);
    archiveProject(project.key)
      .then(() => { toast.success(`Archived ${project.key}`); navigate('/work'); })
      .catch((err) => { setConfirming(false); toast.error(err?.message || 'Could not archive the project.'); })
      .finally(() => setBusy(false));
  }

  return (
    <section className="work-section">
      <h2 className="work-subheading">Archive</h2>
      <p className="work-muted">An archived project leaves the project list. Its items are kept.</p>
      {confirming
        ? <Confirm yes={`Yes, archive ${project.key}`} disabled={busy} onYes={archive} onNo={() => setConfirming(false)}>
            Archive {project.key} {project.name}? It will disappear from everyone&apos;s project list.
          </Confirm>
        : <button type="button" className="btn btn-sm btn-danger" onClick={() => setConfirming(true)}>Archive project</button>}
    </section>
  );
}

/** /work/:project/settings: leads (and admins) only. */
export default function WorkSettings() {
  const { project, role, reload } = useWorkProject();
  const { user, loading: authLoading } = useAuth();
  const isAdmin = !!user?.roles?.includes('Admin');
  if (!project) return <Spinner label="Loading…" />;
  // Identity (own-row confirmations, the admin-only archive) is unknown until /api/auth/user resolves.
  if (authLoading) return <Spinner label="Loading…" />;
  if (role !== 'lead') return <Navigate to={`/work/${project.key}`} replace />;

  return (
    <div className="work-settings">
      <section className="work-section">
        <h2 className="work-subheading">Details</h2>
        <Details key={project.key} project={project} onSaved={reload} />
      </section>
      <section className="work-section">
        <h2 className="work-subheading">Members</h2>
        <Members project={project} login={user?.loginPrincipal} reloadProject={reload} />
      </section>
      <section className="work-section">
        <h2 className="work-subheading">Default autonomy</h2>
        <p><span className="work-chip">{project.defaultAutonomy || 'supervised'}</span></p>
        <p className="work-muted">{LATER}</p>
      </section>
      {isAdmin && <ArchiveSection project={project} />}
    </div>
  );
}
