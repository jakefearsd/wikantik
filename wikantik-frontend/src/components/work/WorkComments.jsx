import { useRef, useState } from 'react';
import MentionPicker from '../MentionPicker';
import { useMentionPicker } from '../../hooks/useMentionPicker';
import WorkBody from './WorkBody';

const when = (iso) => (iso ? new Date(iso).toLocaleString() : '');
const messageOf = (e, fallback) => e?.message || e?.body?.message || fallback;
const grow = (el) => {
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${el.scrollHeight}px`;
};

/**
 * A textarea with @-mention completion over the project's own members (only those the server gave a mention id).
 * It reuses the wiki's comment picker and its token: choosing a member inserts `@[Display Name](user:<id>)`, which
 * the server resolves by id. No login is ever typed, shown or sent.
 */
function MentionTextarea({ label, value, onChange, users, onSubmit, rows = 3 }) {
  const ref = useRef(null);
  const picker = useMentionPicker({
    textareaRef: ref,
    fetchCandidates: (q) => {
      const needle = q.toLowerCase();
      return Promise.resolve(users
        .filter((m) => m.mentionId && m.displayName && m.displayName.toLowerCase().includes(needle))
        .map((m) => ({ id: m.mentionId, displayName: m.displayName })));
    },
  });

  function accept(candidate) {
    const { replacement, selectionStart } = picker.accept(candidate);
    onChange(replacement);
    setTimeout(() => {
      const el = ref.current;
      if (!el) return;
      el.setSelectionRange(selectionStart, selectionStart);
      grow(el);
      el.focus();
    }, 0);
  }

  return (
    <>
      <textarea
        ref={ref} className="work-comment-input" aria-label={label} rows={rows} value={value}
        onChange={(e) => { onChange(e.target.value); grow(e.target); picker.onChange(e); }}
        onBlur={() => picker.close()}
        onKeyDown={(e) => {
          if (picker.onKeyDown(e)) {
            if (e.key === 'Enter' || e.key === 'Tab') {
              const sel = picker.candidates[picker.selectedIndex];
              if (sel) accept(sel);
            }
            return;
          }
          if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); onSubmit(); }
        }}
      />
      <MentionPicker open={picker.open} candidates={picker.candidates} selectedIndex={picker.selectedIndex} onSelect={accept} anchorPos={picker.anchorPos} />
    </>
  );
}

function Comment({ comment, users, canEdit, canRemove, onEdit, onRemove }) {
  const [draft, setDraft] = useState(null);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  if (comment.removedAt) {
    return <li className="work-comment work-comment-removed"><em>Comment removed</em></li>;
  }

  const run = (promise, onOk, fallback) => {
    setBusy(true);
    setError(null);
    promise
      .then((ok) => { if (ok) onOk(); })
      .catch((e) => {
        console.warn('WorkComments: comment action failed', e);
        setError(messageOf(e, fallback));
      })
      .finally(() => setBusy(false));
  };
  const save = () => {
    if (busy || !draft.trim()) return;
    run(onEdit(comment.id, draft.trim()), () => setDraft(null), 'Could not save the comment. Try again.');
  };

  return (
    <li className="work-comment">
      <div className="work-comment-head">
        <strong>{comment.authorName}</strong>
        <time dateTime={comment.createdAt}>{when(comment.createdAt)}</time>
        {comment.editedAt && <span className="work-muted">(edited)</span>}
      </div>
      {draft === null ? (
        <WorkBody html={comment.bodyHtml} mentions={comment.mentions} className="article-prose work-comment-body" empty={null} />
      ) : (
        <div className="work-comment-edit">
          <MentionTextarea label="Edit your comment" value={draft} onChange={(v) => { setError(null); setDraft(v); }} users={users} onSubmit={save} />
          {error && <div className="work-form-error" role="alert">{error}</div>}
          <div className="work-actions">
            <button type="button" className="btn btn-primary btn-sm" aria-label="Save comment" disabled={busy} onClick={save}>Save</button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setError(null); setDraft(null); }}>Cancel</button>
          </div>
        </div>
      )}
      {draft === null && (canEdit || canRemove) && (
        confirming ? (
          <div className="work-actions" role="group" aria-label="Confirm removal">
            <span>Remove this comment?</span>
            <button type="button" className="btn btn-danger btn-sm" disabled={busy} onClick={() => run(onRemove(comment.id), () => setConfirming(false), 'Could not remove the comment. Try again.')}>Yes, remove</button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setError(null); setConfirming(false); }}>Keep</button>
            {error && <div className="work-form-error" role="alert">{error}</div>}
          </div>
        ) : (
          <div className="work-actions">
            {canEdit && (
              <button type="button" className="work-link-button" aria-label={`Edit comment by ${comment.authorName}`}
                onClick={() => setDraft(comment.body || '')}>Edit</button>
            )}
            {canRemove && (
              <button type="button" className="work-link-button" aria-label={`Remove comment by ${comment.authorName}`}
                onClick={() => setConfirming(true)}>Remove</button>
            )}
          </div>
        )
      )}
    </li>
  );
}

/**
 * The discussion. `members` are the project's own members (listMembers); only user rows are offered in the picker.
 * Each comment arrives rendered (`bodyHtml`, with `mentions` to mark) and says whether it is the viewer's (`mine`).
 * Authors edit their own comments; authors and leads remove; viewers (`canComment` false) get no controls.
 * `onAdd(body)`, `onEdit(id, body)` and `onRemove(id)` resolve true on success.
 */
export default function WorkComments({ comments = [], members = [], canComment, isLead, onAdd, onEdit, onRemove }) {
  const users = members.filter((m) => m.principalKind === 'user');
  const [text, setText] = useState('');
  const [posting, setPosting] = useState(false);
  const [postError, setPostError] = useState(null);

  function post() {
    const body = text.trim();
    if (!body || posting) return;
    setPosting(true);
    setPostError(null);
    onAdd(body)
      .then((ok) => { if (ok) setText(''); })
      .catch((e) => {
        console.warn('WorkComments: posting failed', e);
        setPostError(messageOf(e, 'Could not save the comment. Try again.'));
      })
      .finally(() => setPosting(false));
  }

  return (
    <section className="work-section" aria-label="Discussion">
      <h2 className="work-subheading">Discussion</h2>
      {comments.length === 0 ? <p className="work-muted">No comments yet.</p> : (
        <ul className="work-comment-list">
          {comments.map((c) => {
            const mine = c.mine === true;
            return (
              <Comment key={c.id} comment={c} users={users}
                canEdit={canComment && mine} canRemove={canComment && (mine || !!isLead)}
                onEdit={onEdit} onRemove={onRemove} />
            );
          })}
        </ul>
      )}
      {canComment && (
        <div className="work-comment-new">
          <MentionTextarea label="Add a comment" value={text} onChange={(v) => { setPostError(null); setText(v); }} users={users} onSubmit={post} />
          {postError && <div className="work-form-error" role="alert">{postError}</div>}
          <div className="work-actions">
            <button type="button" className="btn btn-primary btn-sm" disabled={posting} onClick={post}>Comment</button>
          </div>
        </div>
      )}
    </section>
  );
}
