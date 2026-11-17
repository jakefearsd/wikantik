import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getShippedIn } from '../../api/work';

/** "Shipped in wikantik 2.5.26 (production)": the verified deployments that carried the item; nothing when none. */
export default function WorkShippedIn({ itemKey, refreshKey = 0 }) {
  const [shipped, setShipped] = useState([]);
  const [failed, setFailed] = useState(false);
  const load = useCallback(() => getShippedIn(itemKey)
    .then((r) => { setShipped(r?.shippedIn || []); setFailed(false); })
    .catch((e) => {
      console.warn('WorkShippedIn: could not load what the item shipped in', e);
      setFailed(true);
    }), [itemKey]);
  useEffect(() => { load(); }, [load, refreshKey]);
  if (failed) {
    return (
      <p className="work-muted">
        Could not load what this shipped in.{' '}
        <button type="button" className="btn btn-ghost btn-sm" onClick={load}>Retry</button>
      </p>
    );
  }
  if (shipped.length === 0) return null;
  return (
    <p className="work-shipped-in" data-testid="shipped-in">
      Shipped in{' '}
      {shipped.map((d, i) => (
        <span key={d.id}>
          {i > 0 && ', '}
          <Link to={`/work/deployments/${d.id}`}>{d.product} {d.version}</Link>
          <span className="work-muted"> ({d.environment})</span>
        </span>
      ))}
    </p>
  );
}
