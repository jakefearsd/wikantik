import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';

/**
 * "Referenced by" panel — lists pages that link to the current page.
 * Backed by GET /api/backlinks/{name}. Degrades silently (renders nothing)
 * when there are no backlinks or the request fails, mirroring SimilarPagesPanel.
 */
export default function BacklinksPanel({ pageName }) {
  const [backlinks, setBacklinks] = useState([]);

  useEffect(() => {
    if (!pageName) return;
    let cancelled = false;
    api.getBacklinks(pageName)
      .then(data => { if (!cancelled) setBacklinks(data.backlinks || []); })
      .catch(() => { /* silently degrade */ });
    return () => { cancelled = true; };
  }, [pageName]);

  if (backlinks.length === 0) return null;

  return (
    <div style={{ marginTop: 'var(--space-sm)', padding: 'var(--space-sm) var(--space-md)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)', fontSize: '0.85em' }}>
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
