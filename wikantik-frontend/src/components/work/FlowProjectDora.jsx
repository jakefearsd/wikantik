import { useEffect, useState } from 'react';
import { getProjectDora, listProducts } from '../../api/work';
import Select from '../ui/Select';
import DoraPanel from './DoraPanel';
import useFlowReport from './useFlowReport';

/** The delivery figures of a project, loaded on their own, with an optional narrowing to one product. */
export default function FlowProjectDora({ project, window: win, windowKey, product, onProduct }) {
  const [products, setProducts] = useState([]);
  useEffect(() => {
    let live = true;
    listProducts()
      .then((list) => { if (live) setProducts((list || []).filter((p) => !p.archivedAt && !p.archived)); })
      .catch((e) => console.warn('FlowProjectDora: could not load the products', e));
    return () => { live = false; };
  }, []);
  const params = { ...win, ...(product ? { product } : {}) };
  const dora = useFlowReport(() => getProjectDora(project, params), windowKey ? `dora:${project}:${windowKey}:${product || ''}` : null, 'delivery figures');
  return (
    <section aria-label="Delivery figures">
      <h2 className="work-subheading">Delivery</h2>
      <div className="work-actions">
        <span className="work-muted">Product</span>
        <Select ariaLabel="Delivery product" placeholder="All products" value={product || ''}
          options={products.map((p) => ({ value: p.key, label: p.name || p.key }))} onChange={onProduct} />
      </div>
      {dora.notFound && <p className="work-muted">Not found</p>}
      {dora.error && (
        <div className="work-form-error" role="alert">
          {dora.error.message || 'Could not load the delivery figures.'}{' '}
          <button type="button" className="btn btn-sm" onClick={dora.reload}>Retry</button>
        </div>
      )}
      {!dora.notFound && dora.loading && !dora.data && <p className="work-muted">Loading delivery figures…</p>}
      {!dora.notFound && dora.data && <DoraPanel report={dora.data} />}
    </section>
  );
}
