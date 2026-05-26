import { useState } from 'react';

function ThreadCard({ thread, detached, onReply, onResolve, onReopen, onFocusThread }) {
  const [reply, setReply] = useState('');
  return (
    <div className="comment-thread" onClick={() => onFocusThread(thread.id)}>
      <div className="comment-thread-anchor">“{thread.anchor?.exact}”</div>
      {thread.comments.map((c) => (
        <div key={c.id} className="comment-item">
          <span className="comment-author">{c.author}</span>
          <span className="comment-body">{c.body}</span>
        </div>
      ))}
      {thread.status === 'open' && !detached && (
        <div className="comment-reply-row" onClick={(e) => e.stopPropagation()}>
          <input
            className="comment-reply-input"
            placeholder="Reply…"
            value={reply}
            onChange={(e) => setReply(e.target.value)}
          />
          <button
            onClick={() => { if (reply.trim()) { onReply(thread.id, reply.trim()); setReply(''); } }}
          >
            Reply
          </button>
          <button onClick={() => onResolve(thread.id)}>Resolve</button>
        </div>
      )}
      {thread.status === 'resolved' && (
        <div className="comment-reply-row" onClick={(e) => e.stopPropagation()}>
          <button onClick={() => onReopen(thread.id)}>Reopen</button>
        </div>
      )}
    </div>
  );
}

export default function CommentsDrawer({
  open, threads, detachedIds = [], statusFilter,
  onStatusFilter, onReply, onResolve, onReopen, onFocusThread, onClose,
}) {
  if (!open) return null;
  const isDetached = (id) => detachedIds.includes(id);
  const visible = threads.filter((t) => {
    if (statusFilter === 'open') return t.status === 'open';
    if (statusFilter === 'resolved') return t.status === 'resolved';
    return true;
  });
  const attached = visible.filter((t) => !isDetached(t.id));
  const detached = visible.filter((t) => isDetached(t.id));

  return (
    <aside className="comments-drawer">
      <div className="comments-drawer-header">
        <span>Comments</span>
        <select value={statusFilter} onChange={(e) => onStatusFilter(e.target.value)}>
          <option value="open">Open</option>
          <option value="resolved">Resolved</option>
          <option value="all">All</option>
        </select>
        <button aria-label="Close comments" onClick={onClose}>✕</button>
      </div>
      <div className="comments-drawer-body">
        {attached.map((t) => (
          <ThreadCard key={t.id} thread={t} detached={false}
            onReply={onReply} onResolve={onResolve} onReopen={onReopen} onFocusThread={onFocusThread} />
        ))}
        {detached.length > 0 && (
          <div className="comments-detached">
            <div className="comments-detached-label">Detached</div>
            {detached.map((t) => (
              <ThreadCard key={t.id} thread={t} detached
                onReply={onReply} onResolve={onResolve} onReopen={onReopen} onFocusThread={onFocusThread} />
            ))}
          </div>
        )}
        {visible.length === 0 && <p className="comments-empty">No comments.</p>}
      </div>
    </aside>
  );
}
