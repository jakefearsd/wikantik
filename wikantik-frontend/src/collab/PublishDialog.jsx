import Modal from '../components/ui/Modal';
import { DiffHtml } from '../components/DiffViewer';
import ContributorList from './ContributorList.jsx';

/** A frontmatter value as one line of text (a list reads "a, c"; nothing reads "—"). Rendered as text, never markup. */
function showValue(v) {
  if (v === null || v === undefined || v === '') return '—';
  if (Array.isArray(v) && v.every((x) => typeof x === 'string')) return v.length ? v.join(', ') : '—';
  return typeof v === 'string' ? v : JSON.stringify(v);
}

/**
 * Publishing the shared draft (§8.3, §16 decision 1). Shows what will be published against the page: the server's HTML
 * diff (R6, through `DiffHtml` only) or "No text changes", the changed frontmatter fields, the contributors, a warning
 * naming anyone still typing, and how many edits arrived after the summary was taken. `mode="view"` is the read-only
 * "View changes" form: no change note and no confirm.
 */
export default function PublishDialog({
  isOpen, mode = 'publish', summary, unsynced = [], newerEdits = 0, changeNote = '', onChangeNote, onConfirm, onCancel, busy = false,
}) {
  const view = mode === 'view';
  const close = () => { if (!busy) onCancel?.(); };
  const metaChanges = Array.isArray(summary?.metaChanges) ? summary.metaChanges : [];
  const stillTyping = Array.isArray(unsynced) ? unsynced : [];
  const newer = typeof newerEdits === 'number' && newerEdits > 0 ? newerEdits : 0;
  const submit = (e) => {
    e.preventDefault();
    if (!busy) onConfirm?.();
  };
  return (
    <Modal isOpen={isOpen} onClose={close} labelledBy="publish-dialog-title" testId="publish-dialog"
      className="collab-diff-modal collab-publish-dialog">
      <h2 id="publish-dialog-title" className="collab-diff-title">
        {view ? 'Unpublished changes' : 'Publish the shared draft'}
      </h2>
      {!summary && <p className="collab-publish-hint">Loading…</p>}
      {summary && (
        <>
          {summary.contributors?.length > 0 && (
            <p className="collab-publish-contributors">Changes by <ContributorList names={summary.contributors} /></p>
          )}
          {stillTyping.length > 0 && (
            <div className="warning-banner" role="alert" data-testid="publish-dialog-unsynced">
              Still typing: <ContributorList names={stillTyping} />. Their latest edits may not have reached the draft yet.
            </div>
          )}
          {newer > 0 && (
            <p className="collab-publish-hint" data-testid="publish-dialog-newer">
              {newer} newer {newer === 1 ? 'edit' : 'edits'} will be included
            </p>
          )}
          {summary.diff ? <DiffHtml html={summary.diff} /> : <p className="collab-publish-hint">No text changes</p>}
          {metaChanges.length > 0 && (
            <table className="collab-meta-changes">
              <caption>Frontmatter changes</caption>
              <thead><tr><th scope="col">Field</th><th scope="col">Before</th><th scope="col">After</th></tr></thead>
              <tbody>
                {metaChanges.map((c) => (
                  <tr key={c.field}><td>{c.field}</td><td>{showValue(c.before)}</td><td>{showValue(c.after)}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
      <form onSubmit={submit}>
        {!view && (
          <div className="collab-publish-note">
            <label htmlFor="publish-dialog-note">Change note</label>
            <input id="publish-dialog-note" type="text" className="form-input" value={changeNote ?? ''} disabled={busy}
              onChange={(e) => onChangeNote?.(e.target.value)} />
          </div>
        )}
        <div className="modal-actions">
          <button type="button" className="btn btn-secondary" onClick={close} disabled={busy}>{view ? 'Close' : 'Cancel'}</button>
          {!view && (
            <button type="submit" className="btn btn-primary" data-testid="publish-dialog-confirm" disabled={busy}>
              {busy ? 'Publishing…' : 'Publish'}
            </button>
          )}
        </div>
      </form>
    </Modal>
  );
}
