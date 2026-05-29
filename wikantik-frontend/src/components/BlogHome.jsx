import { useEffect, useRef, useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import { useAuth } from '../hooks/useAuth';
import { renderMath } from '../utils/math';
import '../styles/article.css';
import '../styles/admin.css';

export default function BlogHome() {
  const { username } = useParams();
  const { user } = useAuth();
  const { data: page, loading, error } = useApi(
    (signal) => api.blog.get(username, { render: true, signal }),
    [username, user?.authenticated]
  );

  const articleRef = useRef(null);
  const navigate = useNavigate();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleteError, setDeleteError] = useState(null);

  async function handleDeleteBlog() {
    try {
      await api.blog.remove(username);
      navigate('/blog');
    } catch (err) {
      setDeleteError(err.body?.message || err.message || 'Failed to delete blog');
    }
  }

  // Depend on the `page` object reference (not the contentHtml string) so the
  // effect fires on every refetch — e.g. auth state transitions where
  // dangerouslySetInnerHTML resets the DOM and wipes previously-rendered
  // KaTeX output. renderMath is idempotent (guards with `math-rendered`).
  useEffect(() => {
    if (articleRef.current && page?.contentHtml) {
      renderMath(articleRef.current);
    }
  }, [page]);

  const isOwner = user?.authenticated && user.loginPrincipal?.toLowerCase() === username?.toLowerCase();
  const isAdmin = user?.authenticated && user.roles?.includes('Admin');

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
        {(isOwner || isAdmin) && (
          <div style={{ display: 'flex', gap: 'var(--space-sm)', flexShrink: 0 }}>
            {isOwner && (
              <Link to={`/blog/${encodeURIComponent(username)}/new`} className="btn btn-primary">
                New Entry
              </Link>
            )}
            {isOwner && (
              <Link to={`/edit/blog/${username}/Blog`} className="btn btn-ghost">
                Edit Blog Page
              </Link>
            )}
            {(isOwner || isAdmin) && (
              <button className="btn btn-ghost btn-danger" data-testid="delete-blog-button"
                onClick={() => { setConfirmDelete(true); setDeleteError(null); }}>
                Delete Blog
              </button>
            )}
          </div>
        )}
      </div>

      <article
        ref={articleRef}
        className="article-prose"
        dangerouslySetInnerHTML={{ __html: page.contentHtml || page.content || '' }}
      />

      {confirmDelete && (
        <div className="modal-overlay" onClick={() => setConfirmDelete(false)}>
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3>Delete Blog</h3>
            <p>Delete {username}'s entire blog, including all entries? This action cannot be undone.</p>
            {deleteError && <p className="error-banner" style={{ marginBottom: 'var(--space-sm)' }}>{deleteError}</p>}
            <div className="modal-actions">
              <button className="btn btn-ghost" onClick={() => setConfirmDelete(false)}>Cancel</button>
              <button className="btn btn-primary btn-danger" onClick={handleDeleteBlog}>Delete Blog</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
