import { useCallback, useEffect, useRef, useState } from 'react';
import { attachmentUrl, listAttachments, removeAttachment, uploadAttachment } from '../../api/work';
import { formatSize } from '../../utils/formatSize';
import { limitsFor, noteUploaded, rememberLimits, sizeProblem, uploadErrorText } from './attachmentErrors';
import WorkConfirm from './WorkConfirm';
import { fullTime } from './workTime';

// Only these four raster types are ever shown as a picture; the server serves nothing else inline either.
const THUMBNAIL_TYPES = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp']);

function Row({ a, itemKey, canRemove, confirming, busy, removeError, onAsk, onCancel, onRemove }) {
  const href = attachmentUrl(itemKey, a.id);
  const thumb = a.inline && THUMBNAIL_TYPES.has(a.contentType);
  return (
    <li className="work-attachment-row">
      <a className="work-attachment-name" href={href} rel="noopener" title={a.filename}
        {...(thumb ? { target: '_blank' } : { download: a.filename })}>
        {thumb && <img className="work-attachment-thumb" src={href} alt="" loading="lazy" />}
        <span className="work-attachment-filename">{a.filename}</span>
      </a>
      <span className="work-muted">{formatSize(a.sizeBytes)}</span>
      {a.uploadedByName && <span className="work-muted">{a.uploadedByName}</span>}
      <time className="work-muted" dateTime={a.uploadedAt}>{fullTime(a.uploadedAt)}</time>
      {a.commentId != null && <span className="work-muted">on a comment</span>}
      {canRemove && !confirming && (
        <button type="button" className="work-link-button" aria-label={`Remove ${a.filename}`} onClick={onAsk}>Remove</button>
      )}
      {confirming && (
        <WorkConfirm yes="Yes, remove" disabled={busy} error={removeError} onYes={onRemove} onNo={onCancel}>
          Remove {a.filename}? Its bytes are deleted; links to it in the text stop working.
        </WorkConfirm>
      )}
    </li>
  );
}

/**
 * The item's attachments: a row per file (a thumbnail for the four inline image types, a download link for all else),
 * an "Attach a file" input and removal. The list loads on its own (`refreshKey` reloads it silently) and a failed
 * reload keeps the last good list. `onChanged` tells the page something was attached or removed.
 */
export default function WorkAttachments({ itemKey, canAttach = false, isLead = false, refreshKey = 0, onChanged }) {
  const [list, setList] = useState(null);
  const [mayAttach, setMayAttach] = useState(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [confirming, setConfirming] = useState(null);
  const [removeError, setRemoveError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [uploads, setUploads] = useState([]);
  const [uploading, setUploading] = useState(false);
  const stop = useRef(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);

  const load = useCallback((silent) => listAttachments(itemKey, silent ? { silent: true } : undefined)
    .then((r) => {
      if (!alive.current) return;
      setList(r?.attachments || []);
      rememberLimits(itemKey, r);
      setMayAttach(limitsFor(itemKey)?.mayAttach ?? null);
      setLoadFailed(false);
    })
    .catch((e) => {
      console.warn('WorkAttachments: could not load the attachments', e);
      if (alive.current) setLoadFailed(true);
    }), [itemKey]);
  const first = useRef(true);
  useEffect(() => { load(!first.current); first.current = false; }, [load, refreshKey]);

  const remove = (a) => {
    setBusy(true);
    setRemoveError(null);
    removeAttachment(itemKey, a.id)
      .then(() => {
        setList((cur) => (cur || []).filter((x) => x.id !== a.id));
        setConfirming(null);
        onChanged?.();
      })
      .catch((e) => {
        console.warn('WorkAttachments: removing an attachment failed', e);
        setRemoveError(e?.status === 403 ? 'Not allowed: only the uploader or a lead may remove this file.'
          : uploadErrorText(e, null, itemKey).replace(/^./, (c) => c.toUpperCase()));
      })
      .finally(() => setBusy(false));
  };

  async function attach(files) {
    stop.current = false;
    setUploading(true);
    const rows = files.map((f, i) => ({ id: i, name: f.name, state: 'waiting', error: null }));
    setUploads(rows);
    const mark = (i, patch) => { rows[i] = { ...rows[i], ...patch }; if (alive.current) setUploads([...rows]); };
    let sent = false;
    for (let i = 0; i < files.length; i++) {
      if (stop.current) { mark(i, { state: 'cancelled' }); continue; }
      const tooBig = sizeProblem(files[i], itemKey);
      if (tooBig) { mark(i, { state: 'failed', error: tooBig }); continue; }
      mark(i, { state: 'uploading' });
      try {
        await uploadAttachment(itemKey, files[i]);
        sent = true;
        noteUploaded(itemKey, files[i].size);
        mark(i, { state: 'done' });
      } catch (e) {
        console.warn('WorkAttachments: uploading a file failed', e);
        mark(i, { state: 'failed', error: uploadErrorText(e, files[i].name, itemKey) });
      }
    }
    if (alive.current) setUploading(false);
    if (sent) { load(true); onChanged?.(); }
  }

  const shown = list || [];
  return (
    <section className="work-section" aria-label="Attachments">
      <h2 className="work-subheading">Attachments</h2>
      {loadFailed && (
        <p className="work-muted">
          Could not load the attachments.{' '}
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => load(false)}>Retry</button>
        </p>
      )}
      {list && shown.length === 0 && !loadFailed && <p className="work-muted">No attachments.</p>}
      {shown.length > 0 && (
        <ul className="work-attachment-list">
          {shown.map((a) => (
            <Row key={a.id} a={a} itemKey={itemKey} canRemove={canAttach && (isLead || a.mine === true)}
              confirming={confirming === a.id} busy={busy} removeError={confirming === a.id ? removeError : null}
              onAsk={() => { setRemoveError(null); setConfirming(a.id); }} onCancel={() => setConfirming(null)}
              onRemove={() => remove(a)} />
          ))}
        </ul>
      )}
      {canAttach && mayAttach !== false && (
        <div className="work-attachment-add">
          <label className="work-attachment-pick">
            <span className="work-visually-hidden">Attach a file</span>
            <input type="file" multiple aria-label="Attach a file" disabled={uploading}
              onChange={(e) => { const files = Array.from(e.target.files || []); e.target.value = ''; if (files.length) attach(files); }} />
          </label>
          {uploading && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => { stop.current = true; }}>Cancel remaining</button>
          )}
          <ul className="work-attachment-uploads" aria-live="polite">
            {uploads.filter((u) => u.state !== 'done').map((u) => (
              u.state === 'failed'
                ? <li key={u.id} role="alert" className="work-field-error">{u.error}</li>
                : <li key={u.id} className="work-muted">{u.name}: {u.state === 'uploading' ? 'uploading…' : u.state}</li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
