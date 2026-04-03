import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import { useAuth } from '../hooks/useAuth';

export default function BlogDiscovery() {
  const { user } = useAuth();
  const { data, loading, error } = useApi((signal) => api.blog.list({ signal }), []);

  if (loading) return <div className="loading">Loading…</div>;
  if (error) return <div className="error-banner">Failed to load blogs: {error.message}</div>;

  const blogs = data?.blogs || data || [];

  return (
    <div className="page-enter">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-lg)' }}>
        <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '2rem', margin: 0 }}>Blogs</h1>
        {user?.authenticated && (
          <Link to="/blog/create" className="btn btn-primary">Create My Blog</Link>
        )}
      </div>

      {blogs.length === 0 ? (
        <p style={{ color: 'var(--text-muted)' }}>No blogs yet. Be the first to create one!</p>
      ) : (
        <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
          {blogs.map((blog) => (
            <li key={blog.username} style={{ padding: 'var(--space-md)', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)' }}>
              <Link
                to={`/blog/${encodeURIComponent(blog.username)}/Blog`}
                style={{ fontWeight: 600, fontSize: '1.1rem', textDecoration: 'none' }}
              >
                {blog.title || `${blog.username}'s Blog`}
              </Link>
              {blog.authorFullName && (
                <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem', margin: 'var(--space-xs) 0 0' }}>
                  by {blog.authorFullName}
                </p>
              )}
              {blog.description && (
                <p style={{ color: 'var(--text-muted)', margin: 'var(--space-xs) 0 0' }}>{blog.description}</p>
              )}
              {blog.entryCount != null && (
                <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem', margin: 'var(--space-xs) 0 0' }}>
                  {blog.entryCount} {blog.entryCount === 1 ? 'entry' : 'entries'}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
