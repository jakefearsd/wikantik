import { Fragment } from 'react';
import { Link } from 'react-router-dom';
import Icon from './ui/Icon';
import { usePageTrail } from '../hooks/usePageTrail';

/**
 * Navigation-history trail for the page reader. Shows up to the last 3 DISTINCT
 * pages visited in this tab (sessionStorage-backed via usePageTrail), oldest →
 * newest. Earlier entries are links; the current page (last entry) is plain text.
 *
 * This replaced the former hierarchical (Home › cluster › page) breadcrumb.
 * The SEO BreadcrumbList JSON-LD emitted server-side by SemanticHeadRenderer
 * stays hierarchical and is intentionally unaffected — a per-user history trail
 * must not appear in canonical structured data.
 */
export default function Breadcrumbs() {
  const { items } = usePageTrail();
  if (!items || items.length === 0) return null;

  const lastIdx = items.length - 1;

  return (
    <nav aria-label="Recent pages" className="breadcrumbs">
      <ol className="breadcrumbs-list">
        {items.map((entry, i) => {
          const label = entry.title || entry.slug;
          return (
            <Fragment key={entry.slug}>
              {i > 0 && (
                <li className="breadcrumbs-separator" aria-hidden="true">
                  <Icon name="chevron" size={14} />
                </li>
              )}
              <li className="breadcrumbs-item">
                {i === lastIdx ? (
                  <span className="breadcrumbs-current" aria-current="page">{label}</span>
                ) : (
                  <Link to={`/wiki/${entry.slug}`} className="breadcrumbs-link">{label}</Link>
                )}
              </li>
            </Fragment>
          );
        })}
      </ol>
    </nav>
  );
}
