import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate, useParams } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import Tabs from '../ui/Tabs';
import { getProject } from '../../api/work';
import { WorkProjectContext } from './useWorkProject';
import { ARCHIVED_TEXT } from './archived';

const TABS = [
  { id: 'board', label: 'Board' },
  { id: 'dates', label: 'Dates' },
  { id: 'activity', label: 'Activity' },
  { id: 'settings', label: 'Settings' },
];

/**
 * Shell of /work/:project: loads the project, owns the header and tabs, and gives the views below it
 * { project, role, reload } through useWorkProject().
 */
export default function WorkLayout() {
  const { project: key } = useParams();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [state, setState] = useState({ key: null, project: null, error: null });

  const load = useCallback(() => getProject(key)
    .then((project) => setState({ key, project, error: null }))
    .catch((error) => setState({ key, project: null, error })), [key]);

  useEffect(() => { load(); }, [load]);

  const current = state.key === key ? state : { project: null, error: null };
  const value = useMemo(() => ({
    project: current.project, role: current.project?.role ?? null, reload: load,
  }), [current.project, load]);

  if (current.error) {
    // 404 covers both "no such project" and "not a member": one neutral state for both.
    return <EmptyState message={current.error.status === 404 ? 'Not found' : (current.error.message || 'Could not load the project.')} />;
  }
  if (!current.project) return <Spinner label="Loading project…" />;

  const rest = pathname.split('/').filter(Boolean).slice(2)[0];
  const active = TABS.some((t) => t.id === rest) ? rest : 'board';

  return (
    <WorkProjectContext.Provider value={value}>
      <header className="work-header">
        <h1 className="work-heading">
          <Link to="/work">Work</Link> <span aria-hidden="true">▸</span> {current.project.key} {current.project.name}
        </h1>
      </header>
      {current.project.archived && <p className="work-archived-banner" role="status">{ARCHIVED_TEXT}. It is read-only.</p>}
      <Tabs tabs={TABS} active={active}
        onChange={(id) => navigate(id === 'board' ? `/work/${key}` : `/work/${key}/${id}`)}>
        <Outlet />
      </Tabs>
    </WorkProjectContext.Provider>
  );
}
