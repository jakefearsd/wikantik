import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { getSprintReport } from '../../api/work';
import BurndownChart from './charts/BurndownChart';
import { formatPoints } from './sprintModel';

const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;

function ItemList({ title, items, empty }) {
  return (
    <section className="work-section" aria-label={title}>
      <h2 className="work-subheading">{title}</h2>
      {items.length === 0 ? <p className="work-muted">{empty}</p> : (
        <ul className="work-story-list">
          {items.map((i) => (
            <li key={i.key}>
              <Link to={`/work/item/${i.key}`}>{i.key}</Link>
              <span className="work-story-title">{i.title}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** What a sprint committed to against what it finished, its burndown, and what was added or removed along the way. */
export default function WorkSprintReport() {
  const { project: projectKey, id } = useParams();
  const [state, setState] = useState({ id: null, report: null, error: null });

  useEffect(() => {
    let cancelled = false;
    getSprintReport(id)
      .then((report) => { if (!cancelled) setState({ id, report, error: null }); })
      .catch((error) => {
        console.warn('WorkSprintReport: could not load the report', error);
        if (!cancelled) setState({ id, report: null, error });
      });
    return () => { cancelled = true; };
  }, [id]);

  const current = state.id === id ? state : { report: null, error: null };
  if (current.error) {
    return <EmptyState message={current.error.status === 404 ? 'Not found' : (current.error.message || 'Could not load the report.')} />;
  }
  if (!current.report) return <Spinner label="Loading report…" />;

  const { sprint, days = [], added = [], removed = [] } = current.report;
  const committed = sprint.committed;
  const completed = sprint.completed;
  const now = sprint.current;
  return (
    <div className="work-sprint-report">
      <p><Link to={`/work/${projectKey}/sprints`}>Sprints</Link></p>
      <h2 className="work-subheading">{sprint.name} <span className="work-muted">· {sprint.state} · {sprint.startsOn} – {sprint.endsOn}</span></h2>
      {sprint.goal && <p className="work-sprint-goal">{sprint.goal}</p>}
      {committed && (
        <p className="work-sprint-committed">
          Committed {plural(committed.items, 'item')}, {formatPoints(committed.points)} points.
        </p>
      )}
      {completed && committed && (
        <p className="work-sprint-completed">
          Completed {completed.items} of {plural(committed.items, 'item')}, {formatPoints(completed.points)} of {formatPoints(committed.points)} points
        </p>
      )}
      {sprint.state === 'active' && now && (
        <p className="work-sprint-now">
          Now: {now.doneItems} of {plural(now.items, 'item')} done, {formatPoints(now.donePoints)} of {formatPoints(now.points)} points.
        </p>
      )}
      <BurndownChart days={days} committedPoints={committed?.points ?? now?.points ?? 0}
        startsOn={sprint.startsOn} endsOn={sprint.endsOn} />
      <ItemList title="Added after the start" items={added} empty="Nothing was added." />
      <ItemList title="Removed after the start" items={removed} empty="Nothing was removed." />
    </div>
  );
}
