import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';

export default function SimilarPagesPanel({ pageName }) {
  const [similar, setSimilar] = useState([]);

  useEffect(() => {
    if (!pageName) return;
    let cancelled = false;
    api.getSimilarPages(pageName, 5)
      .then(data => { if (!cancelled) setSimilar(data.similar || []); })
      .catch(() => { /* silently degrade */ });
    return () => { cancelled = true; };
  }, [pageName]);

  if (similar.length === 0) return null;

  return (
    <div style={{ marginTop: 'var(--space-sm)', padding: 'var(--space-sm) var(--space-md)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)', fontSize: '0.85em' }}>
      <strong>Similar pages:</strong>{' '}
      {similar.map((s, i) => (
        <span key={s.name}>
          {i > 0 && ', '}
          <Link to={`/wiki/${s.name}`} style={{ textDecoration: 'none' }}>
            {s.name}
          </Link>
        </span>
      ))}
    </div>
  );
}
