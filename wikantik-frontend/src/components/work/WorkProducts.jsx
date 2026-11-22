import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { archiveProduct, createProduct, listProducts, productHistory, updateProduct } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { useCapabilities } from '../../hooks/useCapabilities';
import useAsyncAction from '../../hooks/useAsyncAction';
import { useToast } from '../../hooks/useToast';
import { formatRelative } from '../../utils/datetime';
import WorkConfirm from './WorkConfirm';
import WorkLoadError from './WorkLoadError';
import WorkProductHook from './WorkProductHook';

const KEY_PATTERN = /^[a-z][a-z0-9-]{1,39}$/;
const KEY_MESSAGE = 'Key must start with a lowercase letter and use only lowercase letters, digits and hyphens (2 to 40 characters).';
const GATES = [{ value: 'human', label: 'A person approves deployments' }, { value: 'agent', label: 'An agent may approve deployments' }];
const CEILINGS = ['supervised', 'delegated', 'autonomous'];
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const BLANK = { key: '', name: '', repoUrl: '', defaultBranch: '', wikiCluster: '', autonomyCeiling: 'delegated', deployGate: 'human' };

const FIELD_LABELS = {
  name: 'Name', repo_url: 'Repository URL', default_branch: 'Default branch', wiki_cluster: 'Wiki cluster',
  autonomy_ceiling: 'Agents may do at most', deploy_gate: 'Deployments are approved by',
};
const VALUE_LABELS = {
  autonomy_ceiling: Object.fromEntries(CEILINGS.map((c) => [c, cap(c)])),
  deploy_gate: Object.fromEntries(GATES.map((g) => [g.value, g.label])),
};
const shown = (v, field) => (v == null || v === '' ? 'nothing' : (VALUE_LABELS[field]?.[v] ?? v));

function describe(e) {
  const who = e.actorName || e.actor;
  const when = formatRelative(e.at);
  if (e.field === 'archived') return `${when} · ${who} archived the product`;
  return `${when} · ${who} changed ${FIELD_LABELS[e.field] || e.field} from ${shown(e.from, e.field)} to ${shown(e.to, e.field)}`;
}

/** The product's recorded setting changes, newest first. */
function ProductHistory({ productKey }) {
  const [state, setState] = useState({ events: null, error: null });
  useEffect(() => {
    let live = true;
    productHistory(productKey)
      .then((r) => { if (live) setState({ events: r?.events || [], error: null }); })
      .catch((error) => {
        console.warn('WorkProducts: could not load the history of', productKey, error);
        if (live) setState({ events: null, error });
      });
    return () => { live = false; };
  }, [productKey]);
  return (
    <div className="work-product-history">
      <h2 className="work-form-field">History</h2>
      {state.error && <div className="work-form-error" role="alert">{state.error.message || 'Could not load the history.'}</div>}
      {!state.error && state.events === null && <Spinner label="Loading history…" />}
      {state.events && state.events.length === 0 && <p className="work-muted">No changes recorded yet.</p>}
      {state.events && state.events.length > 0 && (
        <ul className="work-muted">
          {state.events.map((e, i) => <li key={i}>{describe(e)}</li>)}
        </ul>
      )}
    </div>
  );
}

function ProductForm({ product, onDone, onCancel }) {
  const toast = useToast();
  const editing = !!product;
  const [f, setF] = useState(editing
    ? { key: product.key, name: product.name || '', repoUrl: product.repoUrl || '', defaultBranch: product.defaultBranch || '',
      wikiCluster: product.wikiCluster || '', autonomyCeiling: product.autonomyCeiling || 'delegated',
      deployGate: product.deployGate || 'human' }
    : BLANK);
  const { run, busy, error, setError } = useAsyncAction(
    (fields) => (editing ? updateProduct(product.key, fields) : createProduct({ key: f.key, ...fields })),
    {
      fallbackMessage: 'Could not save the product.',
      onSuccess: () => { toast.success(editing ? 'Product saved' : `Created ${f.key}`); onDone(); },
    },
  );
  const set = (name) => (e) => setF((s) => ({ ...s, [name]: e.target.value }));

  function submit(e) {
    e.preventDefault();
    if (!editing && !KEY_PATTERN.test(f.key)) { setError(KEY_MESSAGE); return; }
    if (!f.name.trim()) { setError('Enter a name.'); return; }
    const fields = { name: f.name.trim(), repoUrl: f.repoUrl.trim() || null, defaultBranch: f.defaultBranch.trim() || null,
      wikiCluster: f.wikiCluster.trim() || null, autonomyCeiling: f.autonomyCeiling,
      deployGate: f.deployGate };
    run(fields);
  }

  return (
    <form className="work-form work-product-form" onSubmit={submit} noValidate>
      <label className="work-form-field" htmlFor="work-prod-key">Key</label>
      <input id="work-prod-key" className="work-field-input" value={f.key} disabled={editing} onChange={set('key')} />
      {!editing && <p className="work-muted">2 to 40 lowercase letters, digits and hyphens, starting with a letter. It cannot be changed later.</p>}
      <label className="work-form-field" htmlFor="work-prod-name">Name</label>
      <input id="work-prod-name" className="work-field-input" value={f.name} onChange={set('name')} />
      <label className="work-form-field" htmlFor="work-prod-repo">Repository URL</label>
      <input id="work-prod-repo" className="work-field-input" value={f.repoUrl} onChange={set('repoUrl')} />
      <label className="work-form-field" htmlFor="work-prod-branch">Default branch</label>
      <input id="work-prod-branch" className="work-field-input" value={f.defaultBranch} onChange={set('defaultBranch')} />
      <label className="work-form-field" htmlFor="work-prod-cluster">Wiki cluster</label>
      <input id="work-prod-cluster" className="work-field-input" value={f.wikiCluster} onChange={set('wikiCluster')} />
      <label className="work-form-field" htmlFor="work-prod-ceiling">Agents may do at most</label>
      <select id="work-prod-ceiling" className="work-field-input" value={f.autonomyCeiling} onChange={set('autonomyCeiling')}>
        {CEILINGS.map((c) => <option key={c} value={c}>{cap(c)}</option>)}
      </select>
      <p className="work-muted">The most agents may do unattended on work for this product, whatever an epic says.</p>
      <label className="work-form-field" htmlFor="work-prod-gate">Deployments are approved by</label>
      <select id="work-prod-gate" className="work-field-input" value={f.deployGate} onChange={set('deployGate')}>
        {GATES.map((g) => <option key={g.value} value={g.value}>{g.label}</option>)}
      </select>
      <p className="work-muted">Who may approve a deployment of this product. Autonomy never opens this gate.</p>
      {error && <div className="work-form-error" role="alert">{error}</div>}
      <div className="work-actions">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>{editing ? 'Save product' : 'Create product'}</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
      {editing && <ProductHistory productKey={product.key} />}
    </form>
  );
}

/** /work/products: admins only. */
export default function WorkProducts() {
  const { user, loading: authLoading } = useAuth();
  const toast = useToast();
  const { capabilities } = useCapabilities();
  const hooksOn = !!capabilities?.workCodeHost;
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
        // A failed reload keeps the last good list.
        if (mine === seq.current) setState((s) => ({ products: s.products, error }));
      });
  }, []);

  useEffect(() => { if (isAdmin) load(); }, [isAdmin, load]);

  const { run: archive, busy: archiveBusy, error: archiveError, reset: resetArchiveError } = useAsyncAction((key) => archiveProduct(key).then(() => key), {
    fallbackMessage: 'Could not archive the product.',
    onSuccess: (key) => { setArchiving(null); toast.success(`Archived ${key}`); load(); },
  });

  // Until /api/auth/user resolves the user is null: deciding now would bounce a signed-in admin.
  if (authLoading) return <Spinner label="Loading…" />;
  if (!isAdmin) return <Navigate to="/work" replace />;
  if (state.error && !state.products) return <WorkLoadError error={state.error} fallback="Could not load the products." onRetry={load} />;
  if (!state.products) return <Spinner label="Loading products…" />;

  const done = () => { setMode(null); load(); };
  const active = state.products.filter((p) => !p.archivedAt);

  return (
    <div className="work-products">
      <h1 className="work-heading"><Link to="/work">Work</Link> <span aria-hidden="true">▸</span> Products</h1>
      {state.error && (
        <div className="work-form-error" role="alert">
          {state.error.message || 'Could not refresh the products.'} <button type="button" className="btn btn-sm" onClick={load}>Retry</button>
        </div>
      )}
      <p className="work-muted">A product is something you build and ship: a repository and its default branch. Items target a product, and deployments ship its versions.</p>
      {mode === 'new'
        ? <ProductForm onDone={done} onCancel={() => setMode(null)} />
        : mode === null && <p><button type="button" className="btn btn-sm" onClick={() => setMode('new')}>New product</button></p>}
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
                          Agents may do at most: {cap(p.autonomyCeiling || 'delegated')}. Deployments are approved by {p.deployGate === 'agent' ? 'an agent or a person' : 'a person'}.
                        </div>
                        {hooksOn && p.hook && <WorkProductHook productKey={p.key} hook={p.hook} onChanged={load} />}
                      </div>
                      {archiving === p.key
                        ? (
                          <WorkConfirm yes={`Yes, archive ${p.key}`} disabled={archiveBusy} error={archiveError}
                            onYes={() => archive(p.key)} onNo={() => { setArchiving(null); resetArchiveError(); }}>
                            Archive {p.key}? It will no longer be offered on items.
                          </WorkConfirm>
                        )
                        : (
                          <div className="work-actions">
                            <Link className="btn btn-sm" to={`/work/products/${p.key}/dora`} aria-label={`Delivery figures for ${p.key}`}>Delivery figures</Link>
                            <button type="button" className="btn btn-sm" aria-label={`Edit ${p.key}`} onClick={() => setMode(p.key)}>Edit</button>
                            <button type="button" className="btn btn-sm btn-ghost" aria-label={`Archive ${p.key}`} onClick={() => { resetArchiveError(); setArchiving(p.key); }}>Archive</button>
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
