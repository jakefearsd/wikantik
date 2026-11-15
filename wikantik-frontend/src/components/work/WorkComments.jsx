import { useRef, useState } from 'react';
import MentionPicker from '../MentionPicker';
import { useMentionPicker } from '../../hooks/useMentionPicker';
import { nameMap, splitBody, toDisplay, toWire } from './mentionText';

const when = (iso) => (iso ? new Date(iso).toLocaleString() : '');
const grow = (el) => {
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${el.scrollHeight}px`;
};

/**
 * A textarea with @-mention completion over the project's own members. The text holds display names; the picker
 * inserts `@Display Name`, and conversion to the `@login` the server reads happens on submit (mentionText.js).
 * The page's MentionPicker popover is reused as is; its insertion (a wiki user token) is not, because work
 * comments use plain `@login`.
 */
function MentionTextarea({ label, value, onChange, users, onSubmit, rows = 3 }) {
  const ref = useRef(null);
  const picker = useMentionPicker({
    textareaRef: ref,
    fetchCandidates: (q) => {
      const needle = q.toLowerCase();
      return Promise.resolve(users
        .filter((m) => m.displayName.toLowerCase().includes(needle))
        .map((m) => ({ id: m.principal, displayName: m.displayName })));
    },
  });

  function accept(candidate) {
    const ta = ref.current;
    if (!ta) return;
    const caret = ta.selectionStart;
    const start = caret - 1 - picker.query.length;
    const inserted = `@${candidate.displayName} `;
    const next = ta.value.slice(0, start) + inserted + ta.value.slice(caret);
    const pos = start + inserted.length;
    onChange(next);
    picker.close();
    setTimeout(() => {
      const el = ref.current;
      if (!el) return;
      el.setSelectionRange(pos, pos);
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

function CommentText({ body, byLogin }) {
  return (
    <div className="work-comment-body">
      {splitBody(body, byLogin).map((p, i) => (typeof p === 'string'
        ? p
        : <span key={i} className="comment-mention-chip">@{p.name}</span>))}
    </div>
  );
}

function Comment({ comment, users, byLogin, canEdit, canRemove, onEdit, onRemove }) {
  const [draft, setDraft] = useState(null);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);

  if (comment.removedAt) {
    return <li className="work-comment work-comment-removed"><em>Comment removed</em></li>;
  }

  const run = (promise, onOk) => {
    setBusy(true);
    promise
      .then((ok) => { if (ok) onOk(); })
      .catch((e) => console.warn('WorkComments: comment action failed', e))
      .finally(() => setBusy(false));
  };
  const save = () => {
    if (busy || !draft.trim()) return;
    run(onEdit(comment.id, toWire(draft.trim(), users)), () => setDraft(null));
  };

  return (
    <li className="work-comment">
      <div className="work-comment-head">
        <strong>{comment.authorName}</strong>
        <time dateTime={comment.createdAt}>{when(comment.createdAt)}</time>
        {comment.editedAt && <span className="work-muted">(edited)</span>}
      </div>
      {draft === null ? <CommentText body={comment.body} byLogin={byLogin} /> : (
        <div className="work-comment-edit">
          <MentionTextarea label="Edit your comment" value={draft} onChange={setDraft} users={users} onSubmit={save} />
          <div className="work-actions">
            <button type="button" className="btn btn-primary btn-sm" aria-label="Save comment" disabled={busy} onClick={save}>Save</button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDraft(null)}>Cancel</button>
          </div>
        </div>
      )}
      {draft === null && (canEdit || canRemove) && (
        confirming ? (
          <div className="work-actions" role="group" aria-label="Confirm removal">
            <span>Remove this comment?</span>
            <button type="button" className="btn btn-danger btn-sm" disabled={busy} onClick={() => run(onRemove(comment.id), () => setConfirming(false))}>Yes, remove</button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setConfirming(false)}>Keep</button>
          </div>
        ) : (
          <div className="work-actions">
            {canEdit && (
              <button type="button" className="work-link-button" aria-label={`Edit comment by ${comment.authorName}`}
                onClick={() => setDraft(toDisplay(comment.body, byLogin))}>Edit</button>
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
 * The discussion. `members` are the project's own members (listMembers); only user rows are offered or resolved.
 * Authors edit their own comments; authors and leads remove; viewers (`canComment` false) get no controls.
 * `onAdd(body)`, `onEdit(id, body)` and `onRemove(id)` resolve true on success.
 */
export default function WorkComments({ comments = [], members = [], canComment, currentLogin, isLead, onAdd, onEdit, onRemove }) {
  const users = members.filter((m) => m.principalKind === 'user');
  const byLogin = nameMap(users);
  const [text, setText] = useState('');
  const [posting, setPosting] = useState(false);

  function post() {
    const body = text.trim();
    if (!body || posting) return;
    setPosting(true);
    onAdd(toWire(body, users))
      .then((ok) => { if (ok) setText(''); })
      .catch((e) => console.warn('WorkComments: posting failed', e))
      .finally(() => setPosting(false));
  }

  return (
    <section className="work-section" aria-label="Discussion">
      <h2 className="work-subheading">Discussion</h2>
      {comments.length === 0 ? <p className="work-muted">No comments yet.</p> : (
        <ul className="work-comment-list">
          {comments.map((c) => {
            const mine = !!currentLogin && c.author === currentLogin;
            return (
              <Comment key={c.id} comment={c} users={users} byLogin={byLogin}
                canEdit={canComment && mine} canRemove={canComment && (mine || !!isLead)}
                onEdit={onEdit} onRemove={onRemove} />
            );
          })}
        </ul>
      )}
      {canComment && (
        <div className="work-comment-new">
          <MentionTextarea label="Add a comment" value={text} onChange={setText} users={users} onSubmit={post} />
          <div className="work-actions">
            <button type="button" className="btn btn-primary btn-sm" disabled={posting} onClick={post}>Comment</button>
          </div>
        </div>
      )}
    </section>
  );
}
