import { useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

export default function NewBlogEntry() {
  const { username } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [topic, setTopic] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const isOwner = user?.authenticated && user.loginName?.toLowerCase() === username?.toLowerCase();

  async function handleSubmit(e) {
    e.preventDefault();
    const strippedTopic = topic.replace(/\s+/g, '');
    if (!strippedTopic) {
      setError('Topic name cannot be blank');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const result = await api.blog.createEntry(username, strippedTopic);
      const entryName = result.name || result.entryName || strippedTopic;
      navigate(`/blog/${encodeURIComponent(username)}/${encodeURIComponent(entryName)}`);
    } catch (err) {
      setError(err.body?.message || err.message || 'Failed to create entry');
      setSubmitting(false);
    }
  }

  if (!isOwner) {
    return (
      <div className="page-enter" style={{ textAlign: 'center', padding: 'var(--space-2xl) 0' }}>
        <p style={{ color: 'var(--text-muted)', marginBottom: 'var(--space-lg)' }}>
          You are not authorized to create entries on this blog.
        </p>
        <Link to={`/blog/${encodeURIComponent(username)}/Blog`} className="btn btn-ghost">
          Back to Blog
        </Link>
      </div>
    );
  }

  return (
    <div className="page-enter" style={{ maxWidth: '480px', margin: '0 auto', padding: 'var(--space-2xl) 0' }}>
      <div style={{ marginBottom: 'var(--space-md)' }}>
        <Link
          to={`/blog/${encodeURIComponent(username)}/Blog`}
          style={{ color: 'var(--text-muted)', fontSize: '0.875rem', textDecoration: 'none' }}
        >
          ← Back to Blog
        </Link>
      </div>

      <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '2rem', marginBottom: 'var(--space-lg)' }}>
        New Blog Entry
      </h1>

      <form onSubmit={handleSubmit}>
        <label style={{ display: 'block', marginBottom: 'var(--space-xs)', fontWeight: 500 }}>
          Topic Name
        </label>
        <input
          type="text"
          className="form-input"
          value={topic}
          onChange={(e) => setTopic(e.target.value)}
          placeholder="e.g. MyFirstPost"
          autoFocus
          style={{ width: '100%', marginBottom: 'var(--space-sm)' }}
        />
        <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem', marginBottom: 'var(--space-md)' }}>
          Spaces will be removed from the topic name.
        </p>

        {error && (
          <div className="error-banner" style={{ marginBottom: 'var(--space-md)' }}>{error}</div>
        )}

        <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
          <Link to={`/blog/${encodeURIComponent(username)}/Blog`} className="btn btn-ghost">
            Cancel
          </Link>
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting ? 'Creating…' : 'Create Entry'}
          </button>
        </div>
      </form>
    </div>
  );
}
