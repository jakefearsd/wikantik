import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

export default function CreateBlog() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState(null);

  async function handleCreate() {
    setCreating(true);
    setError(null);
    try {
      await api.blog.create();
      navigate(`/blog/${encodeURIComponent(user.loginPrincipal)}/Blog`);
    } catch (err) {
      setError(err.body?.message || err.message || 'Failed to create blog');
      setCreating(false);
    }
  }

  if (!user?.authenticated) {
    return (
      <div className="page-enter" style={{ textAlign: 'center', padding: 'var(--space-2xl) 0' }}>
        <p style={{ color: 'var(--text-muted)', marginBottom: 'var(--space-lg)' }}>
          You must be logged in to create a blog.
        </p>
        <Link to="/blog" className="btn btn-ghost">Back to Blogs</Link>
      </div>
    );
  }

  return (
    <div className="page-enter" style={{ maxWidth: '480px', margin: '0 auto', padding: 'var(--space-2xl) 0' }}>
      <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '2rem', marginBottom: 'var(--space-md)' }}>
        Create Your Blog
      </h1>
      <p style={{ color: 'var(--text-muted)', marginBottom: 'var(--space-lg)' }}>
        This will create a personal blog for <strong>{user.loginPrincipal}</strong>.
      </p>

      {error && (
        <div className="error-banner" style={{ marginBottom: 'var(--space-md)' }}>{error}</div>
      )}

      <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
        <Link to="/blog" className="btn btn-ghost">Cancel</Link>
        <button className="btn btn-primary" onClick={handleCreate} disabled={creating}>
          {creating ? 'Creating…' : 'Create Blog'}
        </button>
      </div>
    </div>
  );
}
