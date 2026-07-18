import { useState, useRef } from 'react';
import { isValidAttachmentName, getExtension } from '../utils/attachmentNameValidator';
import { api } from '../api/client';

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function AttachmentUploadForm({ onUpload }) {
  const [file, setFile] = useState(null);
  const [name, setName] = useState('');
  const [ext, setExt] = useState('');
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState(null);
  const fileRef = useRef(null);

  const handleFileChange = (e) => {
    const f = e.target.files[0];
    if (!f) return;
    setFile(f);
    const fileExt = getExtension(f.name);
    setExt(fileExt);
    // Default the name to the original filename stem
    const dot = f.name.lastIndexOf('.');
    setName(dot > 0 ? f.name.substring(0, dot) : f.name);
    setError(null);
  };

  const fullName = name && ext ? `${name}.${ext}` : '';
  const isValid = fullName && isValidAttachmentName(fullName);

  const handleUpload = async () => {
    if (!file || !isValid) return;
    setUploading(true);
    setError(null);
    try {
      await onUpload(file, fullName);
      setFile(null);
      setName('');
      setExt('');
      if (fileRef.current) fileRef.current.value = '';
    } catch (err) {
      setError(err.message || 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="attachment-upload-form">
      <label>Upload file</label>
      <input type="file" ref={fileRef} onChange={handleFileChange}
        style={{ fontFamily: 'var(--font-ui)', fontSize: '0.8rem' }} />
      {file && (
        <>
          <label>Name</label>
          <div className="attachment-name-input">
            <input
              type="text"
              value={name}
              onChange={e => { setName(e.target.value); setError(null); }}
              placeholder="filename"
            />
            <span className="ext-badge">.{ext}</span>
          </div>
          {fullName && !isValid && (
            <span className="attachment-validation-error">
              Only a-z, 0-9, hyphens, underscores. Max 40 chars total.
            </span>
          )}
          {error && <span className="attachment-validation-error">{error}</span>}
          <button className="btn btn-primary btn-sm" onClick={handleUpload}
            disabled={!isValid || uploading}>
            {uploading ? 'Uploading...' : 'Upload'}
          </button>
        </>
      )}
    </div>
  );
}

function IngestForm() {
  const [file, setFile] = useState(null);
  const [ingesting, setIngesting] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const fileRef = useRef(null);

  const handleFileChange = (e) => {
    const f = e.target.files[0];
    if (!f) return;
    setFile(f);
    setResult(null);
    setError(null);
  };

  const handleIngest = async () => {
    if (!file) return;
    setIngesting(true);
    setResult(null);
    setError(null);
    try {
      const data = await api.ingestDocument(file);
      setResult(data);
      setFile(null);
      if (fileRef.current) fileRef.current.value = '';
    } catch (err) {
      setError(err.message || 'Ingest failed');
    } finally {
      setIngesting(false);
    }
  };

  return (
    <div className="attachment-upload-form" style={{ borderTop: '1px solid var(--border)', marginTop: 'var(--space-sm)', paddingTop: 'var(--space-sm)' }}>
      <label>Ingest as derived page</label>
      <input type="file" ref={fileRef} onChange={handleFileChange}
        style={{ fontFamily: 'var(--font-ui)', fontSize: '0.8rem' }} />
      {error && <span className="attachment-validation-error">{error}</span>}
      {result && (
        <div style={{ fontSize: '0.8rem', marginTop: 'var(--space-xs)' }}>
          <span style={{ marginRight: 'var(--space-xs)' }}>{result.status}</span>
          {result.page && (
            <a href={`/wiki/${result.page}`}>{result.page}</a>
          )}
        </div>
      )}
      <button className="btn btn-primary btn-sm" onClick={handleIngest}
        disabled={!file || ingesting}>
        {ingesting ? 'Ingesting…' : 'Ingest'}
      </button>
    </div>
  );
}

function AttachmentRow({ attachment, pageName, onRename, onDelete, editorContent }) {
  const [renaming, setRenaming] = useState(false);
  const [newStem, setNewStem] = useState('');
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const ext = getExtension(attachment.fileName);
  const stem = attachment.fileName.substring(0, attachment.fileName.lastIndexOf('.'));

  const handleDragStart = (e) => {
    const md = attachment.isImage
      ? `![${stem}](${attachment.fileName})`
      : `[${stem}](${attachment.fileName})`;
    e.dataTransfer.setData('text/plain', md);
    e.dataTransfer.effectAllowed = 'copy';
    e.currentTarget.classList.add('dragging');
  };

  const handleDragEnd = (e) => {
    e.currentTarget.classList.remove('dragging');
  };

  const startRename = () => {
    setNewStem(stem);
    setRenaming(true);
  };

  const confirmRename = async () => {
    const newName = `${newStem}.${ext}`;
    if (newName === attachment.fileName) { setRenaming(false); return; }
    if (!isValidAttachmentName(newName)) return;
    try {
      await onRename(attachment.fileName, newName);
      setRenaming(false);
    } catch {
      // Error handled by parent
    }
  };

  const handleDeleteClick = () => {
    setConfirmingDelete(true);
  };

  const handleDeleteConfirm = () => {
    setConfirmingDelete(false);
    onDelete(attachment.fileName);
  };

  const handleDeleteCancel = () => {
    setConfirmingDelete(false);
  };

  const isReferenced = (() => {
    const pattern = new RegExp(`(!?\\[[^\\]]*\\])\\(${attachment.fileName.replace(/\./g, '\\.')}\\)`);
    return pattern.test(editorContent || '');
  })();

  const thumbUrl = attachment.isImage
    ? `/attach/${pageName}/${attachment.fileName}`
    : null;

  return (
    <div className="attachment-row" draggable onDragStart={handleDragStart} onDragEnd={handleDragEnd}>
      {thumbUrl
        ? <img className="attachment-thumb" src={thumbUrl} alt={attachment.fileName} />
        : <div className="attachment-file-icon">{ext}</div>
      }
      <div className="attachment-info">
        {renaming ? (
          <div className="attachment-name-input" style={{ marginBottom: 0 }}>
            <input
              className="attachment-rename-input"
              value={newStem}
              onChange={e => setNewStem(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') confirmRename(); if (e.key === 'Escape') setRenaming(false); }}
              autoFocus
            />
            <span className="ext-badge">.{ext}</span>
          </div>
        ) : (
          <div className="name">{attachment.fileName}</div>
        )}
        <div className="size">{formatSize(attachment.size)}</div>
      </div>
      <div className="attachment-actions">
        {renaming ? (
          <>
            <button onClick={confirmRename} title="Confirm">OK</button>
            <button onClick={() => setRenaming(false)} title="Cancel">X</button>
          </>
        ) : confirmingDelete ? (
          <div className="attachment-delete-confirm">
            {isReferenced && (
              <span className="attachment-delete-warning">
                referenced {(() => {
                  const pattern = new RegExp(`(!?\\[[^\\]]*\\])\\(${attachment.fileName.replace(/\./g, '\\.')}\\)`, 'g');
                  const matches = (editorContent || '').match(pattern);
                  return matches ? matches.length : 1;
                })()} time(s)
              </span>
            )}
            <span className="attachment-delete-label">Delete &ldquo;{attachment.fileName}&rdquo;?</span>
            <button onClick={handleDeleteCancel}>Cancel</button>
            <button className="delete" onClick={handleDeleteConfirm}>Delete</button>
          </div>
        ) : (
          <>
            <button onClick={startRename} title="Rename">R</button>
            <button className="delete" onClick={handleDeleteClick} title="Delete">D</button>
          </>
        )}
      </div>
    </div>
  );
}

export default function AttachmentPanel({ open, onClose, pageName, attachments, onUpload, onRename, onDelete, editorContent }) {
  return (
    <div className={`attachment-panel${open ? ' open' : ''}`}>
      <div className="attachment-panel-header">
        <span>Attachments</span>
        <button className="btn btn-ghost" onClick={onClose} style={{ padding: '2px 8px', fontSize: '0.8rem' }}>
          X
        </button>
      </div>
      <div className="attachment-panel-body">
        <AttachmentUploadForm onUpload={onUpload} />
        <IngestForm />
        {attachments.length === 0 ? (
          <div className="attachment-empty">No attachments yet</div>
        ) : (
          attachments.map(att => (
            <AttachmentRow
              key={att.fileName}
              attachment={att}
              pageName={pageName}
              onRename={onRename}
              onDelete={onDelete}
              editorContent={editorContent}
            />
          ))
        )}
      </div>
      <div className="attachment-panel-hint">Drag items into the editor to insert</div>
    </div>
  );
}
