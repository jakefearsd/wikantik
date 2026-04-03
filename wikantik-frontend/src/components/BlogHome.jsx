import { useParams, Link } from 'react-router-dom';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import { useAuth } from '../hooks/useAuth';
import '../styles/article.css';

export default function BlogHome() {
  const { username } = useParams();
  const { user } = useAuth();
  const pageName = `blog/${username}/Blog`;
  const { data: page, loading, error } = useApi(
    () => api.getPage(pageName, { render: true }),
    [username, user?.authenticated]
  );

  const isOwner = user?.authenticated && user.loginName?.toLowerCase() === username?.toLowerCase();

  if (loading) return <div className="loading">Loading…</div>;
  if (error?.status === 404) {
    return (
      <div className="page-enter" style={{ textAlign: 'center', padding: 'var(--space-2xl) 0' }}>
        <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '2rem', marginBottom: 'var(--space-md)' }}>
          Blog not found
        </h1>
        <p style={{ color: 'var(--text-muted)', marginBottom: 'var(--space-lg)' }}>
          {username} does not have a blog yet.
        </p>
        <Link to="/blog" className="btn btn-ghost">Back to Blogs</Link>
      </div>
    );
  }
  if (error) return <div className="error-banner">Failed to load blog: {error.message}</div>;
  if (!page) return null;

  return (
    <div className="page-enter">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-md)' }}>
        <div>
          <Link to="/blog" style={{ color: 'var(--text-muted)', fontSize: '0.875rem', textDecoration: 'none' }}>
            ← All Blogs
          </Link>
          <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '2rem', margin: 'var(--space-xs) 0 0' }}>
            {username}'s Blog
          </h1>
        </div>
        {isOwner && (
          <div style={{ display: 'flex', gap: 'var(--space-sm)', flexShrink: 0 }}>
            <Link to={`/blog/${encodeURIComponent(username)}/new`} className="btn btn-primary">
              New Entry
            </Link>
            <Link to={`/edit/${encodeURIComponent(pageName)}`} className="btn btn-ghost">
              Edit Blog Page
            </Link>
          </div>
        )}
      </div>

      <article
        className="article-prose"
        dangerouslySetInnerHTML={{ __html: page.contentHtml || page.content || '' }}
      />
    </div>
  );
}
