import { useEffect, useRef } from 'react';
import { useParams, Link } from 'react-router-dom';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import { useAuth } from '../hooks/useAuth';
import { renderMath } from '../utils/math';
import '../styles/article.css';

export default function BlogEntry() {
  const { username, entryName } = useParams();
  const { user } = useAuth();
  const { data: entry, loading, error } = useApi(
    (signal) => api.blog.getEntry(username, entryName, { render: true, signal }),
    [username, entryName, user?.authenticated]
  );

  const articleRef = useRef(null);

  // Depend on the `entry` object reference (not the contentHtml string) so
  // the effect fires on every refetch — e.g. auth state transitions where
  // dangerouslySetInnerHTML resets the DOM and wipes previously-rendered
  // KaTeX output. renderMath is idempotent (guards with `math-rendered`).
  useEffect(() => {
    if (articleRef.current && entry?.contentHtml) {
      renderMath(articleRef.current);
    }
  }, [entry]);

  const isOwner = user?.authenticated && user.loginPrincipal?.toLowerCase() === username?.toLowerCase();
  const isAdmin = user?.authenticated && user.roles?.includes('Admin');

  if (loading) return <div className="loading">Loading…</div>;
  if (error?.status === 404) {
    return (
      <div className="page-enter" style={{ textAlign: 'center', padding: 'var(--space-2xl) 0' }}>
        <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '2rem', marginBottom: 'var(--space-md)' }}>
          Entry not found
        </h1>
        <Link to={`/blog/${encodeURIComponent(username)}/Blog`} className="btn btn-ghost">
          Back to Blog
        </Link>
      </div>
    );
  }
  if (error) return <div className="error-banner">Failed to load entry: {error.message}</div>;
  if (!entry) return null;

  return (
    <div className="page-enter">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-md)' }}>
        <Link
          to={`/blog/${encodeURIComponent(username)}/Blog`}
          style={{ color: 'var(--text-muted)', fontSize: '0.875rem', textDecoration: 'none' }}
        >
          ← {username}'s Blog
        </Link>
        {(isOwner || isAdmin) && (
          <Link to={`/edit/blog/${encodeURIComponent(username)}/${encodeURIComponent(entryName)}`} className="btn btn-ghost">
            Edit Entry
          </Link>
        )}
      </div>

      <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '2rem', marginBottom: 'var(--space-xs)' }}>
        {entry.title || entryName}
      </h1>

      {entry.date && (
        <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem', marginBottom: 'var(--space-lg)' }}>
          {new Date(entry.date).toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' })}
        </p>
      )}

      <article
        ref={articleRef}
        className="article-prose"
        dangerouslySetInnerHTML={{ __html: entry.contentHtml || entry.content || '' }}
      />
    </div>
  );
}
