import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';

/**
 * "Referenced by" panel — lists pages that link to the current page.
 * Backed by GET /api/backlinks/{name}. Without `emptyText` it renders nothing
 * when there are no backlinks or the request fails (mirroring SimilarPagesPanel); with `emptyText`
 * (the editor rail) it shows Loading / the empty text / "Backlinks unavailable". Failures are logged.
 */
export default function BacklinksPanel({ pageName, emptyText }) {
  // The result is keyed by page so a page change reads as loading without a setState in the effect.
  const [result, setResult] = useState({ page: null, backlinks: [], failed: false });
  const current = result.page === pageName;
  const backlinks = current ? result.backlinks : [];
  const status = !current ? 'loading' : result.failed ? 'failed' : 'ready';

  useEffect(() => {
    if (!pageName) return;
    let cancelled = false;
    api.getBacklinks(pageName)
      .then(data => { if (!cancelled) setResult({ page: pageName, backlinks: data.backlinks || [], failed: false }); })
      .catch(err => {
        console.warn('[backlinks] could not load backlinks for', pageName, err?.message || err);
        if (!cancelled) setResult({ page: pageName, backlinks: [], failed: true });
      });
    return () => { cancelled = true; };
  }, [pageName]);

  if (backlinks.length === 0) {
    if (!emptyText) return null;
    const text = { loading: 'Loading…', failed: 'Backlinks unavailable', ready: emptyText }[status];
    return <p className="editor-rail-empty">{text}</p>;
  }

  return (
    <div data-testid="backlinks-panel" style={{ marginTop: 'var(--space-sm)', padding: 'var(--space-sm) var(--space-md)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)', fontSize: '0.85em' }}>
      <strong>Referenced by:</strong>{' '}
      {backlinks.map((name, i) => (
        <span key={name}>
          {i > 0 && ', '}
          <Link to={`/wiki/${name}`} style={{ textDecoration: 'none' }}>
            {name}
          </Link>
        </span>
      ))}
    </div>
  );
}
