import { useRef, useState } from 'react';
import { api } from '../api/client';
import { useMentionPicker } from '../hooks/useMentionPicker';
import MentionPicker from './MentionPicker';
import CommentBody from './CommentBody';

function ThreadCard({
  thread, detached, canModerate, focusedThreadId,
  onReply, onResolve, onReopen, onDeleteThread, onFocusThread,
}) {
  const [reply, setReply] = useState('');
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const replyRef = useRef(null);
  const isFocused = thread.id === focusedThreadId;
  // Match the composer's auto-grow behaviour: set the textarea's height to its
  // scrollHeight on every change so the input expands with the user's text and
  // wraps within the drawer's width. Capped with internal scroll via CSS.
  const growReply = (el) => { el.style.height = 'auto'; el.style.height = el.scrollHeight + 'px'; };
  const submitReply = () => {
    if (!reply.trim()) return;
    onReply(thread.id, reply.trim());
    setReply('');
    if (replyRef.current) replyRef.current.style.height = 'auto';
  };
  // @-mention picker against the reply textarea. Mirrors the composer's wiring
  // in PageView — the hook handles debounce + caret tracking; we just feed the
  // existing textarea handlers and render the dumb popover.
  const picker = useMentionPicker({
    textareaRef: replyRef,
    fetchCandidates: async (q) => {
      try { const r = await api.listMentionableUsers(q); return r.users || []; }
      catch { return []; }
    },
  });
  const acceptLogin = (login) => {
    const { replacement, selectionStart } = picker.accept(login);
    setReply(replacement);
    setTimeout(() => {
      const ta = replyRef.current;
      if (!ta) return;
      ta.setSelectionRange(selectionStart, selectionStart);
      growReply(ta);
      ta.focus();
    }, 0);
  };
  // In-app two-step delete (no native confirm dialog): the first click reveals
  // the confirm row; the second click commits. Cancel reverts.
  const deleteButton = canModerate && (
    <button
      type="button"
      className="comment-thread-delete"
      title="Delete thread"
      onClick={() => setConfirmingDelete(true)}
    >
      🗑 Delete
    </button>
  );
  return (
    <div className={`comment-thread${isFocused ? ' focused' : ''}`} onClick={() => onFocusThread(thread.id)}>
      <div className="comment-thread-anchor">“{thread.anchor?.exact}”</div>
      {thread.comments.map((c) => (
        <div key={c.id} className="comment-item">
          <span className="comment-author">{c.author}</span>
          <CommentBody body={c.body} />
        </div>
      ))}
      {confirmingDelete ? (
        <div className="comment-thread-confirm" onClick={(e) => e.stopPropagation()}>
          <span className="comment-thread-confirm-text">Delete this thread permanently?</span>
          <button
            type="button"
            className="comment-thread-confirm-cancel"
            onClick={() => setConfirmingDelete(false)}
          >
            Cancel
          </button>
          <button
            type="button"
            className="comment-thread-confirm-yes"
            onClick={() => { setConfirmingDelete(false); onDeleteThread(thread.id); }}
          >
            Delete
          </button>
        </div>
      ) : thread.status === 'open' && !detached ? (
        <div
          className="comment-reply-block"
          onClick={(e) => e.stopPropagation()}
        >
          <textarea
            ref={replyRef}
            className="comment-reply-input"
            placeholder="Reply…"
            value={reply}
            rows={1}
            onChange={(e) => { setReply(e.target.value); growReply(e.target); picker.onChange(e); }}
            onKeyDown={(e) => {
              if (picker.onKeyDown(e)) {
                if (e.key === 'Enter' || e.key === 'Tab') {
                  const sel = picker.candidates[picker.selectedIndex];
                  if (sel) acceptLogin(sel.loginName);
                }
                return;
              }
              if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submitReply(); }
            }}
          />
          <div className="comment-reply-actions">
            <button onClick={submitReply}>Reply</button>
            <button onClick={() => onResolve(thread.id)}>Resolve</button>
            {deleteButton}
          </div>
          <MentionPicker
            open={picker.open}
            candidates={picker.candidates}
            selectedIndex={picker.selectedIndex}
            onSelect={acceptLogin}
            anchorPos={picker.anchorPos}
          />
        </div>
      ) : (
        <div className="comment-reply-row" onClick={(e) => e.stopPropagation()}>
          {thread.status === 'resolved' && (
            <button onClick={() => onReopen(thread.id)}>Reopen</button>
          )}
          {deleteButton}
        </div>
      )}
    </div>
  );
}

export default function CommentsDrawer({
  open, threads, detachedIds = [], statusFilter, canModerate = false,
  focusedThreadId = null,
  onStatusFilter, onReply, onResolve, onReopen, onDeleteThread = () => {},
  onFocusThread, onClose,
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
  const cardProps = {
    canModerate, focusedThreadId, onReply, onResolve, onReopen, onDeleteThread, onFocusThread,
  };

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
          <ThreadCard key={t.id} thread={t} detached={false} {...cardProps} />
        ))}
        {detached.length > 0 && (
          <div className="comments-detached">
            <div className="comments-detached-label">Detached</div>
            {detached.map((t) => (
              <ThreadCard key={t.id} thread={t} detached {...cardProps} />
            ))}
          </div>
        )}
        {visible.length === 0 && <p className="comments-empty">No comments.</p>}
      </div>
    </aside>
  );
}
