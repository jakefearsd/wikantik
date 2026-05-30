import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useToast } from '../hooks/useToast';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { formatRelative } from '../utils/datetime';
import EmptyState from './ui/EmptyState';
import Icon from './ui/Icon';

export default function MentionsPage() {
  const [status, setStatus] = useState('unread');
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const toast = useToast();
  useDocumentTitle('My mentions');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.listMyMentions({ status, limit: 50 });
      setItems(res.mentions || []);
    } catch (e) {
      setError(e.message || String(e));
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => { load(); }, [load]);

  const markOne = async (id) => {
    // Optimistic update: immediately mark item as read in local state
    const prev = items;
    setItems(current => current.map(m => m.id === id ? { ...m, readAt: new Date().toISOString() } : m));
    try {
      await api.markMentionRead(id);
      await load();
    } catch (e) {
      // Revert on failure
      setItems(prev);
      toast.error('Failed to mark mention as read');
    }
  };

  const markAll = async () => {
    // Optimistic update: immediately mark all items as read
    const prev = items;
    const now = new Date().toISOString();
    setItems(current => current.map(m => ({ ...m, readAt: m.readAt || now })));
    try {
      await api.markAllMentionsRead();
      await load();
    } catch (e) {
      // Revert on failure
      setItems(prev);
      toast.error('Failed to mark all mentions as read');
    }
  };

  return (
    <div className="mentions-page page-enter">
      <header className="mentions-page-header">
        <h1>My mentions</h1>
        <div className="mentions-page-controls">
          <div className="mentions-filter" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={status === 'unread'}
              className={status === 'unread' ? 'mentions-filter-tab active' : 'mentions-filter-tab'}
              onClick={() => setStatus('unread')}
            >
              Unread
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={status === 'all'}
              className={status === 'all' ? 'mentions-filter-tab active' : 'mentions-filter-tab'}
              onClick={() => setStatus('all')}
            >
              All
            </button>
          </div>
          <button type="button" className="mentions-mark-all" onClick={markAll}>Mark all read</button>
        </div>
      </header>
      {loading && <p className="mentions-empty">Loading…</p>}
      {error && <p className="mentions-error">Failed to load: {error}</p>}
      {!loading && !error && items.length === 0 && (
        <EmptyState
          icon={<Icon name="comment" />}
          message="No mentions yet."
        />
      )}
      <ul className="mentions-list">
        {items.map((m, i) => (
          <li
            key={m.id}
            className={`mentions-item stagger-in ${m.readAt ? 'read' : 'unread'} ${m.isOwnerMention ? 'owner' : ''}`}
            style={{ animationDelay: i < 10 ? `${i * 40}ms` : '400ms' }}
          >
            <div className="mentions-item-meta">
              <span className="mentions-item-author">@{m.mentionedBy}</span>
              <span className="mentions-item-context">
                on <Link
                  to={`/wiki/${encodeURIComponent(m.pageName)}?thread=${encodeURIComponent(m.threadId)}&comment=${encodeURIComponent(m.commentId)}`}
                >{m.pageName}</Link>
              </span>
              {m.isOwnerMention && <span className="mentions-item-owner-tag">(your page)</span>}
              <span className="mentions-item-when">{formatRelative(m.mentionedAt)}</span>
              {!m.readAt && (
                <button
                  type="button"
                  className="mentions-item-dismiss"
                  title="Mark read"
                  onClick={() => markOne(m.id)}
                >✕</button>
              )}
            </div>
            <div className="mentions-item-snippet">&quot;{m.snippet}&quot;</div>
          </li>
        ))}
      </ul>
    </div>
  );
}

