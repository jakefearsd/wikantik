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
  const [backlinks, setBacklinks] = useState([]);
  const [status, setStatus] = useState('loading'); // loading | ready | failed

  useEffect(() => {
    if (!pageName) return;
    let cancelled = false;
    setStatus('loading');
    api.getBacklinks(pageName)
      .then(data => { if (!cancelled) { setBacklinks(data.backlinks || []); setStatus('ready'); } })
      .catch(err => {
        console.warn('[backlinks] could not load backlinks for', pageName, err?.message || err);
        if (!cancelled) setStatus('failed');
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
