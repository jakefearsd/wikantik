import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate, useParams } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import Tabs from '../ui/Tabs';
import { getProject, listSprints } from '../../api/work';
import { WorkProjectContext } from './useWorkProject';
import { ARCHIVED_TEXT } from './archived';

const TABS = [
  { id: 'board', label: 'Board' },
  { id: 'dates', label: 'Dates' },
  { id: 'sprints', label: 'Sprints' },
  { id: 'activity', label: 'Activity' },
  { id: 'log', label: 'Assignments and claims' },
  { id: 'settings', label: 'Settings' },
];

const EMPTY_SPRINTS = [];

/**
 * Shell of /work/:project: loads the project, owns the header and tabs, and gives the views below it
 * { project, role, reload, sprints, reloadSprints } through useWorkProject(). The Sprints tab shows once the project has
 * a sprint, or to a lead who may make the first.
 */
export default function WorkLayout() {
  const { project: key } = useParams();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [state, setState] = useState({ key: null, project: null, error: null });
  const [sprintState, setSprintState] = useState({ key: null, sprints: [], settled: false, error: null });

  const load = useCallback(() => getProject(key)
    .then((project) => setState({ key, project, error: null }))
    .catch((error) => setState({ key, project: null, error })), [key]);

  // A failed load keeps the last good list (for this project) and says so; the pages do not wait on it, only the
  // board's choice of default does, and it proceeds once the load has settled either way.
  const loadSprints = useCallback(() => listSprints(key)
    .then((sprints) => setSprintState({ key, sprints: sprints || [], settled: true, error: null }))
    .catch((e) => {
      console.warn('WorkLayout: could not load the sprints', e);
      setSprintState((s) => ({
        key, sprints: s.key === key ? s.sprints : [], settled: true, error: e?.message || 'Could not load the sprints.',
      }));
    }), [key]);

  useEffect(() => { load(); loadSprints(); }, [load, loadSprints]);

  const current = state.key === key ? state : { project: null, error: null };
  const sprintsHere = sprintState.key === key;
  const sprints = sprintsHere ? sprintState.sprints : EMPTY_SPRINTS;
  const sprintsSettled = sprintsHere && sprintState.settled;
  const sprintsError = sprintsHere ? sprintState.error : null;
  const value = useMemo(() => ({
    project: current.project, role: current.project?.role ?? null, reload: load, sprints, reloadSprints: loadSprints,
    sprintsSettled,
  }), [current.project, load, sprints, loadSprints, sprintsSettled]);

  if (current.error) {
    // 404 covers both "no such project" and "not a member": one neutral state for both.
    return <EmptyState message={current.error.status === 404 ? 'Not found' : (current.error.message || 'Could not load the project.')} />;
  }
  if (!current.project) return <Spinner label="Loading project…" />;

  const tabs = TABS.filter((t) => t.id !== 'sprints' || sprints.length > 0 || current.project.role === 'lead');
  const rest = pathname.split('/').filter(Boolean).slice(2)[0];
  const active = tabs.some((t) => t.id === rest) ? rest : 'board';

  return (
    <WorkProjectContext.Provider value={value}>
      <header className="work-header">
        <h1 className="work-heading">
          <Link to="/work">Work</Link> <span aria-hidden="true">▸</span> {current.project.key} {current.project.name}
        </h1>
      </header>
      {current.project.archived && <p className="work-archived-banner" role="status">{ARCHIVED_TEXT}. It is read-only.</p>}
      {sprintsError && (
        <div className="error-banner" role="alert">
          Could not load the sprints: {sprintsError}{' '}
          <button type="button" className="btn btn-secondary btn-sm" onClick={loadSprints}>Retry</button>
        </div>
      )}
      <Tabs tabs={tabs} active={active}
        onChange={(id) => navigate(id === 'board' ? `/work/${key}` : `/work/${key}/${id}`)}>
        <Outlet />
      </Tabs>
    </WorkProjectContext.Provider>
  );
}
