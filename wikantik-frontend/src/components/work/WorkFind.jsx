import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { findItems, getMe, listProducts, listQueues, saveFilter } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { STATUS_LABELS } from './boardModel';
import { fromQuery, toQuery, toSaved } from './findParams';
import WorkFilterBar from './WorkFilterBar';
import WorkLoadError from './WorkLoadError';
import { whenText } from './workTime';

const PAGE = 50;

/** The project key of an item key: everything before the last dash (project keys hold no dash). */
export const projectOf = (key) => (key && key.includes('-') ? key.slice(0, key.lastIndexOf('-')) : '');

/** Appends the rows of a later page, dropping any already shown. */
function append(items, page) {
  const seen = new Set(items.map((i) => i.key));
  return [...items, ...page.filter((i) => !seen.has(i.key))];
}

function ResultRow({ item }) {
  return (
    <tr>
      <td><Link to={`/work/item/${item.key}`} className="work-card-key">{item.key}</Link></td>
      <td>{item.title}</td>
      <td>{projectOf(item.key)}</td>
      <td>{STATUS_LABELS[item.status] || item.status}</td>
      <td>{item.type || ''}</td>
      <td>{(item.labels || []).join(', ')}</td>
      <td>{item.assigneeName || ''}</td>
      <td>P{item.priority}</td>
      <td>{item.dueOn || ''}</td>
      <td>{item.updatedAt ? whenText(item.updatedAt) : ''}</td>
    </tr>
  );
}

function ResultTable({ items }) {
  return (
    <table className="work-find-table">
      <thead>
        <tr>
          <th>Key</th><th>Title</th><th>Project</th><th>Status</th><th>Type</th><th>Labels</th>
          <th>Assignee</th><th>Priority</th><th>Due</th><th>Updated</th>
        </tr>
      </thead>
      <tbody>{items.map((i) => <ResultRow key={i.key} item={i} />)}</tbody>
    </table>
  );
}

/** The project the viewer may share a filter in: the one the filter names, when they are a member or lead of it (or an admin). */
export function shareableProject(filter, me, isAdmin) {
  const keys = filter.projects || [];
  if (keys.length !== 1) return null;
  const key = keys[0].toUpperCase();
  const mine = (me?.projects || []).find((p) => p.key === key);
  if (isAdmin) return key;
  return mine && (mine.role === 'member' || mine.role === 'lead') ? key : null;
}

function SaveFilter({ filter, shareIn }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [shared, setShared] = useState(false);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  function submit(e) {
    e.preventDefault();
    if (!name.trim()) { setError('Enter a name.'); return; }
    setBusy(true); setError(null);
    const body = { name: name.trim(), shared: shared && !!shareIn, filter: toSaved(filter) };
    if (body.shared) body.project = shareIn;
    saveFilter(body)
      .then(() => { setBusy(false); setSaved(true); setOpen(false); setName(''); setShared(false); })
      .catch((err) => {
        console.warn('WorkFind: could not save the filter', err);
        setBusy(false);
        setError(err?.status === 409 ? 'You already have a filter with that name.' : (err?.message || 'Could not save the filter.'));
      });
  }

  if (!open) {
    return (
      <div className="work-find-save">
        <button type="button" className="btn btn-sm" onClick={() => { setOpen(true); setSaved(false); }}>Save filter</button>
        {saved && <span className="work-muted" role="status"> Saved. Find it on your Work page.</span>}
      </div>
    );
  }
  return (
    <form className="work-find-save" onSubmit={submit} noValidate>
      <label className="work-toggle">Filter name
        <input className="work-field-input" value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      {shareIn && (
        <label className="work-toggle">
          <input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} /> Share with {shareIn} members
        </label>
      )}
      <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>Save</button>
      <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setOpen(false); setError(null); }}>Cancel</button>
      {error && <div className="work-form-error" role="alert">{error}</div>}
    </form>
  );
}

/** Loads one list once; a failure is logged and leaves the list empty (the filter bar still works). */
function useOnce(fetcher, label) {
  const [list, setList] = useState([]);
  useEffect(() => {
    let live = true;
    fetcher().then((r) => { if (live) setList(Array.isArray(r) ? r : []); })
      .catch((e) => console.warn(`WorkFind: could not load ${label}`, e));
    return () => { live = false; };
  }, [fetcher, label]);
  return list;
}

/**
 * /work/find: items across every project the viewer can see, the filter held in the URL so a find can be bookmarked
 * and shared. Results page with the server's opaque cursor; a failed page keeps what is already shown.
 */
export default function WorkFind() {
  const [params, setParams] = useSearchParams();
  const { user } = useAuth();
  const isAdmin = !!user?.roles?.includes('Admin');
  const queryText = params.toString();
  const filter = useMemo(() => fromQuery(new URLSearchParams(queryText)), [queryText]);
  const key = toQuery(filter);

  const [me, setMe] = useState(null);
  const products = useOnce(listProducts, 'products');
  const queues = useOnce(listQueues, 'queues');
  useEffect(() => {
    let live = true;
    getMe().then((m) => { if (live) setMe(m); }).catch((e) => console.warn('WorkFind: could not load the projects', e));
    return () => { live = false; };
  }, []);

  const [state, setState] = useState({ key: null, items: null, next: null, error: null, errKey: null });
  // Tagged with the search it belongs to, so a new search starts with a fresh Load more.
  const [moreState, setMoreState] = useState({ key: null, loading: false, error: null });
  const seq = useRef(0);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return findItems({ ...filter, limit: PAGE })
      .then((r) => {
        if (mine === seq.current) setState({ key, items: r?.items || [], next: r?.next || null, error: null, errKey: null });
      })
      .catch((error) => {
        console.warn('WorkFind: could not load the results', error);
        if (mine === seq.current) setState((s) => ({ ...s, error, errKey: key }));
      });
  }, [filter, key]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => () => { seq.current += 1; }, []);

  const retry = () => { setState((s) => ({ ...s, error: null, errKey: null })); load(); };

  function loadMore() {
    const mine = seq.current;
    setMoreState({ key, loading: true, error: null });
    findItems({ ...filter, limit: PAGE, cursor: state.next })
      .then((r) => {
        if (mine !== seq.current) return;
        setState((s) => ({ ...s, items: append(s.items, r?.items || []), next: r?.next || null }));
        setMoreState({ key, loading: false, error: null });
      })
      .catch((error) => {
        console.warn('WorkFind: could not load more results', error);
        if (mine === seq.current) setMoreState({ key, loading: false, error: error?.message || 'Could not load more.' });
      });
  }

  const apply = (next) => setParams(new URLSearchParams(toQuery(next)));
  const projects = me?.projects || [];
  const shareIn = shareableProject(filter, me, isAdmin);
  const { items } = state;
  const error = state.errKey === key ? state.error : null;
  const stale = !!items && state.key !== key;
  const loading = state.key !== key && !error;
  const more = moreState.key === key ? moreState : { loading: false, error: null };

  let body;
  if (!items && error) body = <WorkLoadError error={error} fallback="Could not load the results." onRetry={retry} />;
  else if (!items) body = <Spinner label="Loading…" />;
  else {
    body = (
      <>
        {error && (
          <div className="work-form-error" role="alert">
            {error.message || 'Could not load the results.'}{' '}
            <button type="button" className="btn btn-sm" onClick={retry}>Retry</button>
            {stale && <span className="work-muted"> The rows below are from the previous search.</span>}
          </div>
        )}
        {loading && <p className="work-muted" role="status">Loading…</p>}
        {items.length === 0 && !error
          ? <EmptyState message="Nothing matches this filter." />
          : items.length > 0 && <ResultTable items={items} />}
        {more.error && <p className="work-form-error" role="alert">{more.error}</p>}
        {state.next && !stale && (
          <button type="button" className="btn btn-sm" disabled={more.loading} onClick={loadMore}>
            {more.loading ? 'Loading…' : 'Load more'}
          </button>
        )}
      </>
    );
  }

  return (
    <div className="work-find">
      <header className="work-header">
        <h1 className="work-heading"><Link to="/work">Work</Link> <span aria-hidden="true">▸</span> Find work</h1>
      </header>
      <WorkFilterBar key={key} value={filter} onApply={apply} projects={projects} products={products} queues={queues} />
      <SaveFilter key={`save-${key}`} filter={filter} shareIn={shareIn} />
      {body}
    </div>
  );
}
