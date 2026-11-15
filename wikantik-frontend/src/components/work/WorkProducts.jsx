import { useCallback, useEffect, useRef, useState } from 'react';
import { Navigate } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { archiveProduct, createProduct, listProducts, updateProduct } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { useToast } from '../../hooks/useToast';
import WorkLoadError from './WorkLoadError';

const KEY_PATTERN = /^[a-z][a-z0-9-]{1,39}$/;
const KEY_MESSAGE = 'Key must start with a lowercase letter and use only lowercase letters, digits and hyphens (2 to 40 characters).';
const LATER = 'Takes effect in a later release';
const BLANK = { key: '', name: '', repoUrl: '', defaultBranch: '', wikiCluster: '' };

function ProductForm({ product, onDone, onCancel }) {
  const toast = useToast();
  const editing = !!product;
  const [f, setF] = useState(editing
    ? { key: product.key, name: product.name || '', repoUrl: product.repoUrl || '', defaultBranch: product.defaultBranch || '',
      wikiCluster: product.wikiCluster || '' }
    : BLANK);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));

  function submit(e) {
    e.preventDefault();
    if (!editing && !KEY_PATTERN.test(f.key)) { setError(KEY_MESSAGE); return; }
    if (!f.name.trim()) { setError('Enter a name.'); return; }
    setError(null); setBusy(true);
    const fields = { name: f.name.trim(), repoUrl: f.repoUrl.trim() || null, defaultBranch: f.defaultBranch.trim() || null,
      wikiCluster: f.wikiCluster.trim() || null };
    const request = editing ? updateProduct(product.key, fields) : createProduct({ key: f.key, ...fields });
    request
      .then(() => { toast.success(editing ? 'Product saved' : `Created ${f.key}`); onDone(); })
      .catch((err) => {
        if (err?.status === 403) toast.error(err.message); else setError(err?.message || 'Could not save the product.');
      })
      .finally(() => setBusy(false));
  }

  return (
    <form className="work-form work-product-form" onSubmit={submit} noValidate>
      <label className="work-form-field" htmlFor="work-prod-key">Key</label>
      <input id="work-prod-key" className="work-field-input" value={f.key} disabled={editing} onChange={set('key')} />
      <label className="work-form-field" htmlFor="work-prod-name">Name</label>
      <input id="work-prod-name" className="work-field-input" value={f.name} onChange={set('name')} />
      <label className="work-form-field" htmlFor="work-prod-repo">Repository URL</label>
      <input id="work-prod-repo" className="work-field-input" value={f.repoUrl} onChange={set('repoUrl')} />
      <label className="work-form-field" htmlFor="work-prod-branch">Default branch</label>
      <input id="work-prod-branch" className="work-field-input" value={f.defaultBranch} onChange={set('defaultBranch')} />
      <label className="work-form-field" htmlFor="work-prod-cluster">Wiki cluster</label>
      <input id="work-prod-cluster" className="work-field-input" value={f.wikiCluster} onChange={set('wikiCluster')} />
      <p className="work-muted">
        Autonomy ceiling: {editing ? product.autonomyCeiling || 'delegated' : 'delegated'}; deploy gate:{' '}
        {editing ? product.deployGate || 'human' : 'human'}. {LATER}
      </p>
      {error && <div className="work-form-error" role="alert">{error}</div>}
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>{editing ? 'Save product' : 'Create product'}</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

/** /work/products: admins only. */
export default function WorkProducts() {
  const { user } = useAuth();
  const toast = useToast();
  const isAdmin = !!user?.roles?.includes('Admin');
  const [state, setState] = useState({ products: null, error: null });
  const [mode, setMode] = useState(null); // null | 'new' | product key being edited
  const [archiving, setArchiving] = useState(null);
  const seq = useRef(0);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return listProducts()
      .then((products) => { if (mine === seq.current) setState({ products: products || [], error: null }); })
      .catch((error) => {
        console.warn('WorkProducts: could not load products', error);
        if (mine === seq.current) setState({ products: null, error });
      });
  }, []);

  useEffect(() => { if (isAdmin) load(); }, [isAdmin, load]);

  if (!isAdmin) return <Navigate to="/work" replace />;
  if (state.error) return <WorkLoadError error={state.error} fallback="Could not load the products." onRetry={load} />;
  if (!state.products) return <Spinner label="Loading products…" />;

  const done = () => { setMode(null); load(); };
  function archive(key) {
    archiveProduct(key)
      .then(() => { setArchiving(null); toast.success(`Archived ${key}`); load(); })
      .catch((err) => { setArchiving(null); toast.error(err?.message || 'Could not archive the product.'); });
  }
  const active = state.products.filter((p) => !p.archivedAt);

  return (
    <div className="work-products">
      <h1 className="work-heading">Products</h1>
      {mode === 'new'
        ? <ProductForm onDone={done} onCancel={() => setMode(null)} />
        : <p><button type="button" className="btn btn-sm" onClick={() => setMode('new')}>New product</button></p>}
      {active.length === 0
        ? <EmptyState message="No products yet." />
        : (
          <ul className="work-product-rows">
            {active.map((p) => (
              <li key={p.key} className="work-product-row">
                {mode === p.key
                  ? <ProductForm product={p} onDone={done} onCancel={() => setMode(null)} />
                  : (
                    <>
                      <div>
                        <strong>{p.name}</strong> <span className="work-chip">{p.key}</span>
                        <div className="work-muted">
                          {[p.repoUrl, p.defaultBranch && `branch ${p.defaultBranch}`, p.wikiCluster && `cluster ${p.wikiCluster}`]
                            .filter(Boolean).join(' · ') || 'No repository or cluster set'}
                        </div>
                        <div className="work-muted">
                          Autonomy ceiling {p.autonomyCeiling || 'delegated'}, deploy gate {p.deployGate || 'human'}. {LATER}
                        </div>
                      </div>
                      {archiving === p.key
                        ? (
                          <div className="work-confirm" role="alertdialog" aria-label="Confirm">
                            <p>Archive {p.key}? It will no longer be offered on items.</p>
                            <div className="work-actions">
                              <button type="button" className="btn btn-sm btn-danger" onClick={() => archive(p.key)}>Yes, archive {p.key}</button>
                              <button type="button" className="btn btn-sm btn-ghost" onClick={() => setArchiving(null)}>Cancel</button>
                            </div>
                          </div>
                        )
                        : (
                          <div className="work-actions">
                            <button type="button" className="btn btn-sm" aria-label={`Edit ${p.key}`} onClick={() => setMode(p.key)}>Edit</button>
                            <button type="button" className="btn btn-sm btn-ghost" aria-label={`Archive ${p.key}`} onClick={() => setArchiving(p.key)}>Archive</button>
                          </div>
                        )}
                    </>
                  )}
              </li>
            ))}
          </ul>
        )}
    </div>
  );
}
