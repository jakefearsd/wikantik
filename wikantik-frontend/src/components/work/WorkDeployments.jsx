import { useCallback, useEffect, useState } from 'react';
import WorkLoadError from './WorkLoadError';
import { Link } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Select from '../ui/Select';
import Spinner from '../ui/Spinner';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import useAsyncAction from '../../hooks/useAsyncAction';
import { listDeployments, listProducts, requestDeployment } from '../../api/work';

export const STATE_LABEL = {
  requested: 'waiting for approval', approved: 'approved', running: 'running', verified: 'verified',
  failed: 'failed', rolled_back: 'rolled back', rejected: 'rejected',
};
export const stateLabel = (s) => STATE_LABEL[s] || String(s).replace(/_/g, ' ');
const BLANK = { version: '', environment: '', items: '' };
const who = (name, kind) => (name ? `${name}${kind === 'agent' ? ' (agent)' : ''}` : 'someone');

function RequestForm({ product, onDone, onCancel }) {
  const [f, setF] = useState(BLANK);
  const { run, busy, error, setError } = useAsyncAction(requestDeployment, {
    fallbackMessage: 'Could not request the deployment.',
    onSuccess: (d) => onDone(d),
  });
  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));

  function submit(e) {
    e.preventDefault();
    const items = f.items.split(/[\s,]+/).map((k) => k.trim()).filter(Boolean);
    if (!f.version.trim()) { setError('Enter the version.'); return; }
    if (items.length === 0) { setError('Name the items this version ships.'); return; }
    run({ product, version: f.version.trim(), environment: f.environment.trim() || null, items });
  }

  return (
    <form className="work-form" onSubmit={submit} noValidate>
      <label className="work-form-field" htmlFor="work-dep-version">Version</label>
      <input id="work-dep-version" className="work-field-input" value={f.version} onChange={set('version')} />
      <label className="work-form-field" htmlFor="work-dep-env">Environment</label>
      <input id="work-dep-env" className="work-field-input" value={f.environment} onChange={set('environment')} placeholder="production" />
      <label className="work-form-field" htmlFor="work-dep-items">Items it ships</label>
      <input id="work-dep-items" className="work-field-input" value={f.items} onChange={set('items')} placeholder="PLAT-12, PLAT-15" />
      {error && <div className="work-form-error" role="alert">{error}</div>}
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>Request deployment</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

/** /work/deployments: a product's deployments, newest first, and the form that requests one. */
export default function WorkDeployments() {
  useDocumentTitle('Deployments');
  const [products, setProducts] = useState(null);
  const [product, setProduct] = useState('');
  const [deployments, setDeployments] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [requesting, setRequesting] = useState(false);

  useEffect(() => {
    listProducts()
      .then((list) => {
        const active = (list || []).filter((p) => !p.archived);
        setProducts(active);
        setProduct((cur) => cur || active[0]?.key || '');
      })
      .catch((e) => { console.warn('WorkDeployments: could not load the products', e); setProducts([]); });
  }, []);

  const load = useCallback(() => {
    if (!product) return undefined;
    return listDeployments(product)
      .then((r) => { setLoadError(null); setDeployments(r?.deployments || []); })
      .catch((e) => { console.warn('WorkDeployments: could not load the deployments', e); setLoadError(e || {}); setDeployments([]); });
  }, [product]);

  useEffect(() => { load(); }, [load]);

  if (products === null) return <Spinner label="Loading…" />;
  if (products.length === 0) return <EmptyState message="There are no products to deploy." />;

  return (
    <div className="work-deployments">
      <h1 className="work-heading"><Link to="/work">Work</Link> <span aria-hidden="true">▸</span> Deployments</h1>
      <div className="work-actions">
        <span className="work-muted">Product</span>
        <Select ariaLabel="Product" value={product} options={products.map((p) => ({ value: p.key, label: p.name || p.key }))}
          onChange={(v) => { if (v && v !== product) { setDeployments(null); setProduct(v); } }} />
        {!requesting && <button type="button" className="btn btn-secondary btn-sm" onClick={() => setRequesting(true)}>Request a deployment</button>}
      </div>
      {requesting && (
        <RequestForm product={product} onCancel={() => setRequesting(false)}
          onDone={() => { setRequesting(false); load(); }} />
      )}
      {deployments === null ? <Spinner label="Loading…" />
        : loadError ? <WorkLoadError error={loadError} fallback="Could not load the deployments." onRetry={load} />
        : deployments.length === 0 ? <p className="work-muted">No deployments of this product that you can see.</p> : (
          <ul className="work-deployment-list">
            {deployments.map((d) => (
              <li key={d.id} className="work-deployment">
                <Link to={`/work/deployments/${d.id}`}>{d.product} {d.version}</Link>
                <span className="work-muted"> · {d.environment}</span>{' '}
                <span className="work-chip">{stateLabel(d.state)}</span>
                <div className="work-muted">
                  requested by {who(d.requestedByName, d.requestedByKind)} · {d.items.length} {d.items.length === 1 ? 'item' : 'items'}
                </div>
              </li>
            ))}
          </ul>
        )}
    </div>
  );
}
