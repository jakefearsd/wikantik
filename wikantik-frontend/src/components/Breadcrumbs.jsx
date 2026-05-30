import { Link } from 'react-router-dom';
import Icon from './ui/Icon';

/**
 * Breadcrumb trail for the page reader.
 * Trail: Home → [cluster] → current page title
 *
 * Props:
 *   page — the page object from the API. Cluster is read from
 *          page.metadata.cluster (consistent with PageMeta.jsx).
 */
export default function Breadcrumbs({ page }) {
  if (!page) return null;

  const title = page.title || page.name;
  const cluster = page.metadata?.cluster;

  return (
    <nav aria-label="Breadcrumb" className="breadcrumbs">
      <ol className="breadcrumbs-list">
        {/* Home crumb */}
        <li className="breadcrumbs-item">
          <Link to="/" className="breadcrumbs-link">Home</Link>
        </li>

        {/* Cluster crumb — plain label, no link */}
        {cluster && (
          <>
            <li className="breadcrumbs-separator" aria-hidden="true">
              <Icon name="chevron" size={14} />
            </li>
            <li className="breadcrumbs-item">
              <span className="breadcrumbs-label">{cluster}</span>
            </li>
          </>
        )}

        {/* Current page crumb — not a link */}
        <li className="breadcrumbs-separator" aria-hidden="true">
          <Icon name="chevron" size={14} />
        </li>
        <li className="breadcrumbs-item">
          <span className="breadcrumbs-current" aria-current="page">{title}</span>
        </li>
      </ol>
    </nav>
  );
}
