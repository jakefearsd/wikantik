import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { deleteFilter, listFilters, renameFilter } from '../../api/work';
import { BUILT_IN_VIEWS, fromSaved, toQuery } from './findParams';
import WorkConfirm from './WorkConfirm';

const findLink = (filter) => {
  const q = toQuery(filter);
  return q ? `/work/find?${q}` : '/work/find';
};

function RenameField({ filter, onDone }) {
  const [name, setName] = useState(filter.name);
  const [error, setError] = useState(null);
  function save() {
    if (!name.trim()) { setError('Enter a name.'); return; }
    renameFilter(filter.id, name.trim())
      .then(() => onDone(true))
      .catch((e) => {
        console.warn('WorkFindSection: could not rename the filter', e);
        setError(e?.status === 409 ? 'You already have a filter with that name.' : (e?.message || 'Could not rename the filter.'));
      });
  }
  return (
    <span className="work-find-rename">
      <input className="work-field-input" aria-label={`New name for ${filter.name}`} value={name} autoFocus
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); save(); }
          if (e.key === 'Escape') onDone(false);
        }} />
      {error && <span className="work-form-error" role="alert"> {error}</span>}
    </span>
  );
}

/**
 * The "Find work" section of the Work home: built-in views, a link to search everything, and the viewer's saved
 * filters. It loads the filters in its own effect (never with the projects), so a slow or failed load leaves the rest
 * of the page alone; a failed reload keeps the filters already shown.
 */
export default function WorkFindSection() {
  const [state, setState] = useState({ filters: null, error: null });
  const [renaming, setRenaming] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [deleteError, setDeleteError] = useState(null);
  const seq = useRef(0);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return listFilters()
      .then((r) => {
        const filters = Array.isArray(r) ? r : (r?.filters || []);
        if (mine === seq.current) setState({ filters, error: null });
      })
      .catch((error) => {
        console.warn('WorkFindSection: could not load the saved filters', error);
        if (mine === seq.current) setState((s) => ({ ...s, error }));
      });
  }, []);

  useEffect(() => { load(); return () => { seq.current += 1; }; }, [load]);

  function confirmDelete() {
    deleteFilter(deleting.id)
      .then(() => { setDeleting(null); setDeleteError(null); return load(); })
      .catch((e) => {
        console.warn('WorkFindSection: could not delete the filter', e);
        setDeleteError(e?.message || 'Could not delete the filter.');
      });
  }

  const { filters, error } = state;
  return (
    <section aria-label="Find work" className="work-find-section">
      <h2 className="work-subheading">Find work</h2>
      <ul className="work-find-views">
        {BUILT_IN_VIEWS.map((v) => <li key={v.id}><Link to={findLink(v.filter)}>{v.label}</Link></li>)}
        <li><Link to="/work/find">Search all work</Link></li>
      </ul>
      {error && (
        <p className="work-form-error" role="alert">
          {error.status === 401 ? 'Sign in to see your saved filters.' : 'Could not load your saved filters.'}{' '}
          <button type="button" className="btn btn-sm" onClick={load}>Retry</button>
        </p>
      )}
      {!filters && !error && <p className="work-muted" role="status">Loading your saved filters</p>}
      {filters && filters.length > 0 && (
        <ul className="work-find-views" aria-label="Saved filters">
          {filters.map((f) => (
            <li key={f.id}>
              {renaming === f.id
                ? <RenameField filter={f} onDone={(saved) => { setRenaming(null); if (saved) load(); }} />
                : <Link to={findLink(fromSaved(f.filter))}>{f.name}</Link>}
              {f.shared && <span className="work-muted"> · shared in {f.project} by {f.ownerName}</span>}
              {f.mine && renaming !== f.id && (
                <> <button type="button" className="btn btn-sm btn-ghost" aria-label={`Rename ${f.name}`}
                  onClick={() => setRenaming(f.id)}>Rename</button></>
              )}
              {f.mayDelete && (
                <> <button type="button" className="btn btn-sm btn-ghost" aria-label={`Delete ${f.name}`}
                  onClick={() => { setDeleting(f); setDeleteError(null); }}>Delete</button></>
              )}
            </li>
          ))}
        </ul>
      )}
      {deleting && (
        <WorkConfirm yes="Yes, delete" onYes={confirmDelete} onNo={() => setDeleting(null)} error={deleteError}>
          Delete the filter &ldquo;{deleting.name}&rdquo;?{deleting.shared ? ' Everyone it is shared with loses it.' : ''}
        </WorkConfirm>
      )}
    </section>
  );
}
