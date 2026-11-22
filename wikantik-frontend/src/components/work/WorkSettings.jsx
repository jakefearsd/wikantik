import { useCallback, useEffect, useRef, useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import Badge from '../ui/Badge';
import Select from '../ui/Select';
import Spinner from '../ui/Spinner';
import { archiveProject, listAgents, listMembers, putMember, removeMember, updateProject } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { useToast } from '../../hooks/useToast';
import { useWorkProject } from './useWorkProject';
import WorkConfirm from './WorkConfirm';
import WorkLoadError from './WorkLoadError';
import WorkWebhooks from './WorkWebhooks';

const ROLES = ['viewer', 'member', 'lead'];
const AGENT_ROLES = ['viewer', 'member'];
const KINDS = [{ value: 'user', label: 'User' }, { value: 'group', label: 'Group' }, { value: 'agent', label: 'Agent' }];
const RELEASES = 'Any work this agent has claimed in this project goes back to its queue.';
const isAgent = (m) => m.principalKind === 'agent';
// An agent's access is capped by its sponsor's standing here, so its row can be on record and do nothing.
const NO_EFFECT = {
  no_sponsor: 'No effect: this agent has no sponsor',
  sponsor_gone: 'No effect: its sponsor no longer has an account',
  sponsor_not_member: 'No effect: its sponsor is not a member of this project',
  sponsor_viewer: 'Read-only: its sponsor is a viewer here',
};
const SPONSOR_NOTE = 'Removing a person, or making them a viewer, also ends the claims of the agents they sponsor in this project.';
const LEVELS = [{ value: 'supervised', label: 'Supervised' }, { value: 'delegated', label: 'Delegated' }, { value: 'autonomous', label: 'Autonomous' }];
const LEVEL_TEXT = {
  supervised: 'A person approves work as ready, merges and closes it.',
  delegated: 'A person approves work as ready; agents review, merge and close.',
  autonomous: 'Agents may also approve work as ready; each item an agent closes waits for a person to acknowledge it.',
};
const NAME_HELP = {
  user: "Type the person's display name as it appears on the wiki.",
  group: "Type the group's name.",
  agent: 'Choose from the agents registered on the Agents page.',
};

function Details({ project, onSaved, readOnly }) {
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
      <input id="work-set-name" className="work-field-input" value={name} readOnly={readOnly} onChange={(e) => setName(e.target.value)} />
      <label className="work-form-field" htmlFor="work-set-desc">Description</label>
      <textarea id="work-set-desc" className="work-field-input work-form-body" rows={3} value={description} readOnly={readOnly}
        onChange={(e) => setDescription(e.target.value)} />
      {error && <div className="work-form-error" role="alert">{error}</div>}
      {!readOnly && (
        <div className="work-actions">
          <button type="submit" className="btn btn-primary btn-sm" disabled={busy || !name.trim()}>Save details</button>
        </div>
      )}
    </form>
  );
}

function Autonomy({ project, onSaved, readOnly }) {
  const saved = project.defaultAutonomy || 'supervised';
  const [value, setValue] = useState(saved);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  function change(next) {
    if (busy || next === saved) { setValue(next); return; }
    setValue(next); setError(null); setBusy(true);
    updateProject(project.key, { defaultAutonomy: next })
      .then(() => onSaved())
      .catch((err) => { setValue(saved); setError(err?.message || 'Could not change the default autonomy.'); })
      .finally(() => setBusy(false));
  }

  return (
    <>
      {readOnly
        ? <p><span className="work-chip">{saved}</span></p>
        : <Select ariaLabel="Default autonomy" value={value} options={LEVELS} disabled={busy} onChange={change} />}
      <p className="work-muted">{LEVEL_TEXT[readOnly ? saved : value]}</p>
      <p className="work-muted">Applies to items with no epic. An epic or an item can set its own level.</p>
      {error && <div className="work-form-error" role="alert">{error}</div>}
    </>
  );
}

function PendingConfirm({ pending, project, busy, error, onYes, onNo }) {
  const { member, action, role } = pending;
  const name = member.displayName || 'member';
  if (action === 'remove' && !member.self) {
    const agent = isAgent(member);
    return (
      <WorkConfirm disabled={busy} error={error} onYes={onYes} onNo={onNo} yes={`Yes, remove ${name}`}
        fields={agent ? <p className="work-muted">{RELEASES}</p>
          : member.principalKind === 'user' ? <p className="work-muted">Agents they sponsor lose their claims in this project.</p> : null}>
        Remove {name} from {project.name}?
      </WorkConfirm>
    );
  }
  if (isAgent(member)) {
    return (
      <WorkConfirm disabled={busy} error={error} onYes={onYes} onNo={onNo} yes={`Yes, make ${name} a viewer`}>
        {`Make ${name} a viewer in this project?`} {RELEASES}
      </WorkConfirm>
    );
  }
  return (
    <WorkConfirm disabled={busy} error={error} onYes={onYes} onNo={onNo}
      yes={action === 'remove' ? 'Yes, remove me' : 'Yes, change my role'}>
      {action === 'remove'
        ? 'You are about to remove yourself as lead. You will lose access to these settings.'
        : `You are about to change your own role from lead to ${role}. You will lose access to these settings.`}
    </WorkConfirm>
  );
}

function Members({ project, reloadProject, readOnly }) {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [state, setState] = useState({ members: null, error: null });
  const [pending, setPending] = useState(null); // { member, action: 'role' | 'remove', role? }
  const [pendingError, setPendingError] = useState(null); // the server's refusal of a removal, shown in the strip
  const [adding, setAdding] = useState({ principal: '', kind: 'user', role: 'member' });
  const [addError, setAddError] = useState(null);
  const [rowError, setRowError] = useState(null); // { id, message }: the server's refusal of a role change, on that row
  const [agents, setAgents] = useState(null); // registered agents, read once an agent is being added
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

  const wantsAgents = adding.kind === 'agent';
  useEffect(() => {
    if (!wantsAgents || agents) return undefined;
    let live = true;
    listAgents()
      .then((list) => { if (live) setAgents(list || []); })
      .catch((err) => {
        console.warn('WorkSettings: could not load the agents', err);
        if (live) setAddError('Could not load the agents.');
      });
    return () => { live = false; };
  }, [wantsAgents, agents]);

  // The server marks the viewer's own row; the page never needs (or sees) a login.
  const isMe = (m) => m.self === true;
  const rowId = (m) => `${m.principalKind}:${m.principal}`;

  // One request at a time: every control is disabled while one is in flight, so a double click cannot submit twice.
  // A refusal is shown on the member's own row and stays until the next change; the list is re-read so the
  // role shown is the one the server still holds.
  function run(request, { member, self, afterSelf }) {
    if (busy) return Promise.resolve();
    setBusy(true); setRowError(null);
    return request()
      .then(() => (self ? afterSelf() : load()))
      .catch((err) => {
        setRowError({ id: rowId(member), message: err?.message || 'Could not change the member.' });
        load({ quiet: !!self });
      })
      .finally(() => setBusy(false));
  }

  // Your own role changed: the project context must be re-read so the page redirects when you lost lead.
  const afterOwnRoleChange = () => Promise.resolve(reloadProject()).then(() => load({ quiet: true }));
  // Removing yourself leaves no access (the board would be not-found), so go to the work home.
  const afterOwnRemoval = () => { navigate('/work'); };

  function changeRole(m, role) {
    if (role === m.role) return;
    if (isMe(m) && m.role === 'lead') { setPending({ member: m, action: 'role', role }); return; }
    if (isAgent(m) && role === 'viewer') { setPending({ member: m, action: 'role', role }); return; }
    doRole(m, role);
  }
  function doRole(m, role) {
    setPending(null);
    return run(() => putMember(project.key, { principal: m.principal, principalKind: m.principalKind, role }),
      { member: m, self: isMe(m), afterSelf: afterOwnRoleChange });
  }
  function askRemove(m) {
    setPendingError(null);
    setPending({ member: m, action: 'remove' });
  }
  // A refusal stays in the open strip so the person sees why; nothing is reloaded unless the removal worked.
  function doRemove(m) {
    if (busy) return Promise.resolve();
    setBusy(true); setPendingError(null);
    return removeMember(project.key, { principal: m.principal, principalKind: m.principalKind })
      .then(() => { setPending(null); return isMe(m) ? afterOwnRemoval() : load(); })
      .catch((err) => setPendingError(err?.message || 'Could not change the member.'))
      .finally(() => setBusy(false));
  }

  function add(e) {
    e.preventDefault();
    if (busy) return;
    const principal = adding.principal.trim();
    if (!principal) {
      setAddError(adding.kind === 'user' ? 'Enter a display name.' : adding.kind === 'agent' ? 'Choose an agent.' : 'Enter a group name.');
      return;
    }
    // putMember is an upsert: adding must never silently change an existing member's role.
    const lower = principal.toLowerCase();
    const existing = (state.members || []).some((m) => m.principalKind === adding.kind
      && [m.principal, m.displayName].some((n) => n && n.toLowerCase() === lower));
    if (existing) { setAddError('Already a member \u2014 change the role in the list below.'); return; }
    setAddError(null);
    setBusy(true);
    putMember(project.key, { principal, principalKind: adding.kind, role: adding.role })
      .then(() => { setAdding((a) => ({ ...a, principal: '' })); return load(); })
      .catch((err) => {
        setAddError(err?.message || 'Could not add the member.');
        if (err?.status === 403) return;
        // The agent may have been retired meanwhile: re-read the registry so the picker stops offering it.
        if (adding.kind === 'agent') { setAgents(null); setAdding((a) => ({ ...a, principal: '' })); }
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
              <li key={rowId(m)} className="work-member-row">
                <span className="work-member-name">{m.displayName || 'Unnamed member'}</span>
                {isAgent(m)
                  ? <Badge variant="default" className="work-agent-badge" title="An agent, not a person">agent</Badge>
                  : <span className="work-chip">{m.principalKind}</span>}
                {isAgent(m) && m.effective === false && NO_EFFECT[m.reason]
                  && <span className="work-form-error">{NO_EFFECT[m.reason]}</span>}
                {readOnly ? <span className="work-chip">{m.role}</span> : (
                  <>
                    <Select ariaLabel={`Role of ${m.displayName || 'member'}`} value={m.role} options={isAgent(m) ? AGENT_ROLES : ROLES}
                      disabled={busy} onChange={(r) => changeRole(m, r)} />
                    <button type="button" className="work-link-button" aria-label={`Remove ${m.displayName || 'member'}`}
                      disabled={busy} onClick={() => askRemove(m)}>Remove</button>
                    {rowError?.id === rowId(m) && <span className="work-field-error" role="alert">{rowError.message}</span>}
                  </>
                )}
              </li>
            ))}
          </ul>
        )}
      {state.members.some(isAgent) && <p className="work-muted">{SPONSOR_NOTE}</p>}
      {pending && <PendingConfirm pending={pending} project={project} busy={busy} error={pendingError}
        onYes={() => (pending.action === 'remove' ? doRemove(pending.member) : doRole(pending.member, pending.role))}
        onNo={() => { const wasRole = pending.action === 'role'; setPending(null); setPendingError(null); if (wasRole) load(); }} />}
      {!readOnly && <form className="work-link-form" onSubmit={add}>
        {adding.kind === 'agent'
          ? (
            <Select ariaLabel="Agent to add" value={adding.principal} placeholder="Choose an agent" disabled={busy || !agents}
              options={(agents || []).filter((a) => !a.retiredAt
                && !(state.members || []).some((m) => isAgent(m) && m.principal === a.name)).map((a) => a.name)}
              onChange={(principal) => setAdding({ ...adding, principal })} />
          )
          : (
            <>
              <label className="work-visually-hidden" htmlFor="work-add-principal">Display name or group name</label>
              <input id="work-add-principal" className="work-field-input" placeholder={adding.kind === 'user' ? 'Display name' : 'Group name'} value={adding.principal}
                onChange={(e) => setAdding({ ...adding, principal: e.target.value })} aria-invalid={addError ? 'true' : undefined} />
            </>
          )}
        <p className="work-muted work-add-help">{NAME_HELP[adding.kind]}</p>
        <Select ariaLabel="Kind" value={adding.kind} options={KINDS} disabled={busy}
          onChange={(kind) => { setAddError(null); setAdding({ principal: (kind === 'agent') !== (adding.kind === 'agent') ? '' : adding.principal, kind, role: kind === 'agent' && adding.role === 'lead' ? 'member' : adding.role }); }} />
        <Select ariaLabel="Role for new member" value={adding.role} options={adding.kind === 'agent' ? AGENT_ROLES : ROLES}
          onChange={(role) => setAdding({ ...adding, role })} />
        <button type="submit" className="btn btn-sm" disabled={busy}>Add member</button>
        {addError && <span className="work-field-error" role="alert">{addError}</span>}
      </form>}
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
      <p className="work-muted">An archived project becomes read-only and moves to the Archived list. Its items are kept.</p>
      {confirming
        ? <WorkConfirm yes={`Yes, archive ${project.key}`} disabled={busy} onYes={archive} onNo={() => setConfirming(false)}>
            Archive {project.key} {project.name}? Nobody will be able to change it; it stays readable under Archived. Any claim an agent holds on an item in this project is released.
          </WorkConfirm>
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
  const readOnly = !!project.archived;

  return (
    <div className="work-settings">
      <section className="work-section">
        <h2 className="work-subheading">Details</h2>
        <Details key={project.key} project={project} onSaved={reload} readOnly={readOnly} />
      </section>
      <section className="work-section">
        <h2 className="work-subheading">Members</h2>
        <Members project={project} reloadProject={reload} readOnly={readOnly} />
      </section>
      <section className="work-section">
        <h2 className="work-subheading">Default autonomy</h2>
        <Autonomy key={project.defaultAutonomy || 'supervised'} project={project} onSaved={reload} readOnly={readOnly} />
      </section>
      <WorkWebhooks projectKey={project.key} role={role} isAdmin={isAdmin} archived={readOnly} />
      {isAdmin && !readOnly && <ArchiveSection project={project} />}
    </div>
  );
}
