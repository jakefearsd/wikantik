import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import Spinner from '../ui/Spinner';
import { getItemPoints, listItems } from '../../api/work';
import { STATUS_LABELS } from './boardModel';
import { formatPoints } from './sprintModel';

// Task counts need one request per story; only the first stories are counted eagerly, the rest on demand.
const EAGER_COUNTS = 20;
const LIMIT = 200;

function countText(n) {
  if (n === undefined) return null;
  if (n === null) return 'tasks unavailable';
  const label = n >= LIMIT ? `${LIMIT}+` : String(n);
  return `${label} ${n === 1 ? 'task' : 'tasks'}`;
}

/** What an epic adds to the item view: its stories with task counts, progress and the products they touch. */
export default function WorkEpicView({ view }) {
  const epicKey = view.item.key;
  const projectKey = view.projectKey;
  const [state, setState] = useState({ children: null, error: null });
  const [counts, setCounts] = useState({});
  const [points, setPoints] = useState(null);

  const loadCount = useCallback((storyKey) => listItems({ project: projectKey, parent: storyKey, limit: LIMIT })
    .then((rows) => setCounts((c) => ({ ...c, [storyKey]: (rows || []).length })))
    .catch((e) => {
      console.warn(`WorkEpicView: could not count the tasks of ${storyKey}`, e);
      setCounts((c) => ({ ...c, [storyKey]: null }));
    }), [projectKey]);

  useEffect(() => {
    let cancelled = false;
    listItems({ project: projectKey, parent: epicKey, limit: LIMIT })
      .then((rows) => {
        if (cancelled) return;
        const children = rows || [];
        setState({ children, error: null });
        children.slice(0, EAGER_COUNTS).forEach((s) => { loadCount(s.key); });
      })
      .catch((e) => {
        console.warn('WorkEpicView: could not load the stories', e);
        if (!cancelled) setState({ children: null, error: e?.message || 'Could not load the stories.' });
      });
    return () => { cancelled = true; };
  }, [projectKey, epicKey, loadCount]);

  useEffect(() => {
    let cancelled = false;
    getItemPoints(epicKey)
      .then((reply) => { if (!cancelled) setPoints(reply?.points ?? null); })
      .catch((e) => console.warn(`WorkEpicView: could not load the points of ${epicKey}`, e));
    return () => { cancelled = true; };
  }, [epicKey]);

  const { children, error } = state;
  const active = children ? children.filter((c) => c.status !== 'cancelled') : [];
  const done = active.filter((c) => c.status === 'done').length;
  const percent = active.length ? Math.round((done / active.length) * 100) : 0;
  const products = view.derivedProductKeys || [];

  return (
    <section className="work-section work-epic" aria-label="Epic">
      <h2 className="work-subheading">Stories</h2>
      {points != null && <p className="work-epic-points">Points: {formatPoints(points)}</p>}
      {error && <div className="error-banner" role="alert">{error}</div>}
      {!error && !children && <Spinner label="Loading stories…" />}
      {children && children.length === 0 && <p className="work-muted">No stories yet.</p>}
      {children && children.length > 0 && (
        active.length === 0 ? <p className="work-muted">No active stories.</p> : (
          <div className="work-progress">
            <progress value={done} max={active.length} aria-label="Epic progress" />
            <span>{done} of {active.length} done ({percent}%)</span>
          </div>
        )
      )}
      {children && children.length > 0 && (
        <ul className="work-story-list">
          {children.map((s, i) => (
            <li key={s.id} data-testid={`story-${s.key}`}>
              <Link to={`/work/item/${s.key}`}>{s.key}</Link>
              <span className="work-story-title">{s.title}</span>
              <span className="work-story-status">{STATUS_LABELS[s.status] || s.status}</span>
              {countText(counts[s.key]) && <span className="work-story-count">{countText(counts[s.key])}</span>}
              {counts[s.key] === undefined && i >= EAGER_COUNTS && (
                <button type="button" className="work-link-button" aria-label={`Count tasks for ${s.key}`} onClick={() => loadCount(s.key)}>Count tasks</button>
              )}
            </li>
          ))}
        </ul>
      )}
      <h2 className="work-subheading">Products</h2>
      {products.length === 0 ? <p className="work-muted">No products yet.</p> : (
        <ul className="work-product-list">
          {products.map((p) => <li key={p}><span className="work-chip">{p}</span></li>)}
        </ul>
      )}
    </section>
  );
}
