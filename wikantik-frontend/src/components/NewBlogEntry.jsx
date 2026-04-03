import { useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import '../styles/article.css';
import '../styles/admin.css';

export default function NewBlogEntry() {
  const { username } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [topic, setTopic] = useState('');
  const [body, setBody] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const isOwner = user?.authenticated && user.loginPrincipal?.toLowerCase() === username?.toLowerCase();

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
      const result = await api.blog.createEntry(username, strippedTopic, body.trim() || undefined);
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
    <div className="page-enter" style={{ padding: 'var(--space-2xl) 0' }}>
      <div style={{ marginBottom: 'var(--space-md)' }}>
        <Link
          to={`/blog/${encodeURIComponent(username)}/Blog`}
          style={{ color: 'var(--text-muted)', fontSize: '0.875rem', textDecoration: 'none' }}
        >
          ← Back to Blog
        </Link>
      </div>

      <form onSubmit={handleSubmit}>
        <div style={{ textAlign: 'center', marginBottom: 'var(--space-lg)' }}>
          <input
            type="text"
            value={topic}
            onChange={(e) => setTopic(e.target.value)}
            placeholder="Enter blog post title…"
            autoFocus
            style={{
              textAlign: 'center',
              fontSize: '1.5rem',
              fontFamily: 'var(--font-display)',
              fontWeight: 600,
              border: '1px dashed var(--border)',
              borderRadius: 'var(--radius-md)',
              padding: 'var(--space-sm) var(--space-lg)',
              width: '80%',
              maxWidth: '500px',
              background: 'transparent',
              color: 'var(--text)',
            }}
          />
          <p style={{ color: 'var(--text-muted)', fontSize: '0.75rem', marginTop: 'var(--space-xs)' }}>
            Spaces will be removed to form the page name
          </p>
        </div>

        <div className="editor-container">
          <div className="editor-pane">
            <textarea
              className="editor-textarea"
              value={body}
              onChange={(e) => setBody(e.target.value)}
              placeholder="Write your blog post here… (Markdown supported)"
            />
          </div>
          <div className="editor-pane editor-preview article-content">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{body}</ReactMarkdown>
          </div>
        </div>

        {error && (
          <div className="error-banner" style={{ marginTop: 'var(--space-md)' }}>{error}</div>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--space-sm)', marginTop: 'var(--space-md)' }}>
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
