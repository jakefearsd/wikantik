import { Link, Navigate, useParams, useSearchParams } from 'react-router-dom';
import Spinner from '../ui/Spinner';
import { getProductDora } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import DoraPanel from './DoraPanel';
import FlowWindow from './FlowWindow';
import { paramsFrom, toSearch, windowError } from './flowModel';
import useFlowReport from './useFlowReport';
import WorkLoadError from './WorkLoadError';

/** /work/products/:product/dora — a product's delivery figures over every project. Wiki admins only. */
export default function WorkProductDora() {
  const { product } = useParams();
  const { user, loading: authLoading } = useAuth();
  const isAdmin = !!user?.roles?.includes('Admin');
  const [sp, setSp] = useSearchParams();
  useDocumentTitle(`Delivery figures: ${product}`);
  const params = paramsFrom(sp, ['from', 'to']);
  const problem = windowError(params);
  const key = isAdmin && !problem ? `pdora:${product}:${params.from || ''}:${params.to || ''}` : null;
  const dora = useFlowReport(() => getProductDora(product, params), key, 'delivery figures');

  if (authLoading) return <Spinner label="Loading…" />;
  if (!isAdmin) return <Navigate to="/work" replace />;

  return (
    <div className="work-product-dora">
      <h1 className="work-heading">
        <Link to="/work">Work</Link> <span aria-hidden="true">▸</span> <Link to="/work/products">Products</Link>{' '}
        <span aria-hidden="true">▸</span> {product} delivery figures
      </h1>
      <FlowWindow key={`${params.from}|${params.to}`} from={params.from} to={params.to} outsideError={problem}
        onApply={(w) => setSp(toSearch(w))} />
      {dora.notFound && <WorkLoadError error={{ status: 404 }} />}
      {dora.error && (
        <div className="work-form-error" role="alert">
          {dora.error.message || 'Could not load the delivery figures.'}{' '}
          <button type="button" className="btn btn-sm" onClick={dora.reload}>Retry</button>
        </div>
      )}
      {!problem && !dora.notFound && !dora.data && !dora.error && <Spinner label="Loading delivery figures…" />}
      {!dora.notFound && dora.data && <DoraPanel report={dora.data} wholeProduct />}
    </div>
  );
}
