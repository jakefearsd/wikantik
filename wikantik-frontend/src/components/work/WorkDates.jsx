import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import Badge from '../ui/Badge';
import Spinner from '../ui/Spinner';
import { listItems, listLabels } from '../../api/work';
import { useWorkProject } from './useWorkProject';
import { groupByDue, todayIso } from './dateGroups';
import { PRIORITY_OPTIONS, labelRows } from './boardModel';
import WorkTypeLabelFilters from './WorkTypeLabelFilters';
import WorkLoadError from './WorkLoadError';

const LIMIT = 200; // the list endpoint clamps to this
const OPEN = 'draft,refining,ready,in_progress,review';
const CLOSED = new Set(['done', 'cancelled']);

const priorityLabel = (p) => PRIORITY_OPTIONS.find((o) => String(o.value) === String(p))?.label ?? `P${p}`;

function formatDue(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

/** /work/:project/dates: open items grouped by due date. */
export default function WorkDates() {
  const { project } = useWorkProject();
  const [state, setState] = useState({ key: null, items: null, error: null });
  const [labels, setLabels] = useState([]);
  const [params, setParams] = useSearchParams();
  const typeFilter = params.get('type') || '';
  const labelFilter = params.get('label') || '';
  const seq = useRef(0);

  const load = useCallback(() => {
    const mine = ++seq.current;
    // Soonest due first (no date last), so the cap keeps the nearest dates.
    return listItems({ project: project.key, status: OPEN, order: 'due', limit: LIMIT,
      ...(typeFilter ? { type: typeFilter } : {}), ...(labelFilter ? { label: labelFilter } : {}) })
      .then((items) => { if (mine === seq.current) setState({ key: project.key, items: items || [], error: null }); })
      .catch((error) => {
        console.warn('WorkDates: could not load items', error);
        if (mine === seq.current) setState({ key: project.key, items: null, error });
      });
  }, [project.key, typeFilter, labelFilter]);

  const setFilter = (name, value) => {
    const next = new URLSearchParams(params);
    if (value) next.set(name, value); else next.delete(name);
    setParams(next, { replace: true });
  };

  const retry = () => { setState({ key: project.key, items: null, error: null }); load(); };

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    let cancelled = false;
    listLabels(project.key)
      .then((reply) => { if (!cancelled) setLabels(labelRows(reply)); })
      .catch((e) => {
        console.warn('WorkDates: could not load the labels', e);
        if (!cancelled) setLabels([]);
      });
    return () => { cancelled = true; };
  }, [project.key]);

  if (state.key !== project.key || (!state.items && !state.error)) return <Spinner label="Loading…" />;
  if (state.error) return <WorkLoadError error={state.error} fallback="Could not load the items." onRetry={retry} />;

  const open = state.items.filter((i) => !CLOSED.has(i.status));
  const groups = groupByDue(open, todayIso());
  const emptyText = typeFilter || labelFilter ? 'No open items match these filters.' : 'Nothing open in this project.';

  return (
    <div className="work-dates">
      <div className="work-board-toolbar">
        <WorkTypeLabelFilters type={typeFilter} label={labelFilter} labels={labels}
          onType={(v) => setFilter('type', v)} onLabel={(v) => setFilter('label', v)} />
      </div>
      {open.length === 0 && <p className="work-muted">{emptyText}</p>}
      {state.items.length >= LIMIT && <p className="work-muted">Showing the first {LIMIT} items</p>}
      {groups.filter((g) => g.items.length > 0).map((g) => (
        <section key={g.id} className="work-date-group" aria-label={g.label}>
          <h2 className="work-subheading">{g.label} <span className="work-muted">({g.items.length})</span></h2>
          <ul className="work-date-list">
            {g.items.map((i) => (
              <li key={i.id} className={`work-date-row${g.id === 'overdue' ? ' overdue' : ''}`}>
                <Link to={`/work/item/${i.key}`} className="work-card-key">{i.key}</Link>
                <span className="work-date-title">{i.title}</span>
                {i.type === 'bug' && <Badge variant="warning" title="Bug">Bug</Badge>}
                <span className="work-muted">{i.assigneeName || 'Unassigned'}</span>
                <span className="work-muted">{priorityLabel(i.priority)}</span>
                <span className="work-date-due">{i.dueOn ? formatDue(i.dueOn) : 'No date'}</span>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
