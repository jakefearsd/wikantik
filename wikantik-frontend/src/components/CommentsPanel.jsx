import { useState } from 'react';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

export default function CommentsPanel({ pageName }) {
  const { user } = useAuth();
  const [expanded, setExpanded] = useState(false);
  const [comments, setComments] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [newComment, setNewComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState(null);

  const toggle = async () => {
    if (!expanded && comments === null) {
      setLoading(true);
      try {
        const data = await api.getComments(pageName);
        setComments(data.comments || []);
      } catch (err) {
        setError(err.message || 'Failed to load comments');
      } finally {
        setLoading(false);
      }
    }
    setExpanded(v => !v);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!newComment.trim()) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const comment = await api.addComment(pageName, newComment.trim());
      setComments(prev => [...(prev || []), comment]);
      setNewComment('');
    } catch (err) {
      setSubmitError(err.message || 'Failed to add comment');
    } finally {
      setSubmitting(false);
    }
  };

  const chevron = expanded ? '\u25BE' : '\u25B8';
  const count = comments !== null ? comments.length : null;

  return (
    <div style={{ marginBottom: 'var(--space-lg)' }}>
      <button
        onClick={toggle}
        style={{
          color: 'var(--text-muted)',
          fontSize: '0.8rem',
          cursor: 'pointer',
          background: 'none',
          border: 'none',
          padding: '0',
          userSelect: 'none',
        }}
      >
        Comments{count !== null ? ` (${count})` : ''} {chevron}
      </button>

      {expanded && (
        <div
          style={{
            marginTop: 'var(--space-sm)',
            background: 'var(--bg-elevated)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-md)',
            padding: 'var(--space-md)',
          }}
        >
          {loading && <div style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>Loading...</div>}
          {error && <div className="error-banner">{error}</div>}
          {comments && comments.length === 0 && (
            <div style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>No comments yet.</div>
          )}
          {comments && comments.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
              {comments.map((c, i) => (
                <div
                  key={`${c.date}-${i}`}
                  style={{
                    borderBottom: i < comments.length - 1 ? '1px solid var(--border)' : 'none',
                    paddingBottom: i < comments.length - 1 ? 'var(--space-md)' : '0',
                  }}
                >
                  <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'baseline', marginBottom: 'var(--space-xs)' }}>
                    <span style={{ fontWeight: 600, fontSize: '0.875rem' }}>{c.author}</span>
                    <span style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                      {c.date ? new Date(c.date).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''}
                    </span>
                  </div>
                  <div style={{ fontSize: '0.875rem', whiteSpace: 'pre-wrap' }}>{c.text}</div>
                </div>
              ))}
            </div>
          )}

          {user && user.authenticated && (
            <form onSubmit={handleSubmit} style={{ marginTop: 'var(--space-md)' }}>
              <textarea
                value={newComment}
                onChange={(e) => setNewComment(e.target.value)}
                placeholder="Add a comment..."
                rows={3}
                style={{
                  width: '100%',
                  padding: 'var(--space-sm)',
                  fontFamily: 'var(--font-ui)',
                  fontSize: '0.875rem',
                  border: '1px solid var(--border)',
                  borderRadius: 'var(--radius-sm)',
                  background: 'var(--bg-page)',
                  color: 'var(--text)',
                  resize: 'vertical',
                  boxSizing: 'border-box',
                }}
              />
              {submitError && (
                <div className="error-banner" style={{ marginTop: 'var(--space-xs)', fontSize: '0.8rem' }}>{submitError}</div>
              )}
              <div style={{ marginTop: 'var(--space-sm)', textAlign: 'right' }}>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={submitting || !newComment.trim()}
                  style={{ fontSize: '0.8rem' }}
                >
                  {submitting ? 'Posting...' : 'Post Comment'}
                </button>
              </div>
            </form>
          )}
        </div>
      )}
    </div>
  );
}
