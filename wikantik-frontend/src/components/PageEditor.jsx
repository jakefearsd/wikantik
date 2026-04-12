import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api } from '../api/client';
import { reconstructContent, stripFrontmatter } from '../utils/frontmatterUtils';
import { remarkAttachments } from '../utils/remarkAttachments';
import { useAttachments } from '../hooks/useAttachments';
import { useEditorDrop } from '../hooks/useEditorDrop';
import AttachmentPanel from './AttachmentPanel';
import '../styles/article.css';
import '../styles/admin.css';

export default function PageEditor() {
  const { name } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [content, setContent] = useState('');
  const [originalVersion, setOriginalVersion] = useState(null);
  const [changeNote, setChangeNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [isNew, setIsNew] = useState(false);
  const [conflict, setConflict] = useState(null);
  const [markupSyntax, setMarkupSyntax] = useState('markdown');
  const [converting, setConverting] = useState(false);
  const [conversionWarnings, setConversionWarnings] = useState([]);
  const [panelOpen, setPanelOpen] = useState(false);
  const textareaRef = useRef(null);
  const attachments = useAttachments(name);

  const handleInsert = useCallback((text, pos) => {
    setContent(prev => prev.slice(0, pos) + text + prev.slice(pos));
  }, []);

  useEditorDrop(textareaRef, handleInsert);

  // Strip frontmatter from preview — show only the body portion
  const previewContent = useMemo(() => stripFrontmatter(content), [content]);

  const handleRename = useCallback(async (oldName, newName) => {
    const result = await attachments.renameAttachment(oldName, newName);
    setContent(prev => {
      const escaped = oldName.replace(/\./g, '\\.');
      return prev
        .replace(new RegExp(`(!\\[[^\\]]*\\])\\(${escaped}\\)`, 'g'), `$1(${newName})`)
        .replace(new RegExp(`(\\[[^\\]]*\\])\\(${escaped}\\)`, 'g'), `$1(${newName})`);
    });
    return result;
  }, [attachments]);

  useEffect(() => {
    api.getPage(name).then(page => {
      setContent(reconstructContent(page.metadata, page.content));
      setOriginalVersion(page.version);
      setMarkupSyntax(page.markupSyntax || 'markdown');
      setIsNew(false);
    }).catch(err => {
      if (err.status === 404) {
        setContent(location.state?.initialContent || `# ${name}\n\nWrite your content here.`);
        setIsNew(true);
      } else {
        setError(err.message);
      }
    });
  }, [name]);

  // Sync document.title so selenide tests can assert editor context.
  useEffect(() => {
    document.title = `Wikantik: ${isNew ? 'Create' : 'Edit'} ${name}`;
  }, [name, isNew]);

  const handleConvert = async () => {
    setConverting(true);
    setError(null);
    try {
      const result = await api.convertWikiToMarkdown(content);
      setContent(result.markdown);
      setMarkupSyntax('markdown');
      setConversionWarnings(result.warnings || []);
    } catch (err) {
      setError('Conversion failed: ' + (err.message || 'Unknown error'));
    } finally {
      setConverting(false);
    }
  };

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      await api.savePage(name, {
        content,
        changeNote: changeNote || (isNew ? 'Created page' : 'Updated page'),
        expectedVersion: isNew ? undefined : originalVersion,
        markupSyntax: markupSyntax === 'markdown' ? 'markdown' : undefined,
      });
      navigate(`/wiki/${name}`);
    } catch (err) {
      if (err.status === 409) {
        try {
          const serverPage = await api.getPage(name);
          const serverContent = reconstructContent(serverPage.metadata, serverPage.content);
          setConflict({ serverContent, serverVersion: serverPage.version });
        } catch (fetchErr) {
          setError('Version conflict, and failed to fetch the current server version.');
        }
      } else {
        setError(err.message || 'Save failed');
      }
    } finally {
      setSaving(false);
    }
  };

  const handleOverwrite = async () => {
    setSaving(true);
    setConflict(null);
    setError(null);
    try {
      await api.savePage(name, {
        content,
        changeNote: changeNote || 'Updated page (overwrite)',
      });
      navigate(`/wiki/${name}`);
    } catch (err) {
      setError(err.message || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const handleDiscard = () => {
    setContent(conflict.serverContent);
    setOriginalVersion(conflict.serverVersion);
    setConflict(null);
  };

  const handleCopyAndLoad = async () => {
    try {
      await navigator.clipboard.writeText(content);
    } catch {
      // Fallback: select-and-copy not available in all contexts; proceed anyway
    }
    setContent(conflict.serverContent);
    setOriginalVersion(conflict.serverVersion);
    setConflict(null);
  };

  return (
    <div className={`page-enter${panelOpen ? ' editor-with-panel' : ''}`} data-testid="page-editor" data-page-name={name}>
      <div className="editor-toolbar">
        <div className="editor-toolbar-group">
          <h2 data-testid="editor-heading" style={{ fontFamily: 'var(--font-display)', fontSize: '1.25rem', fontWeight: 600 }}>
            {isNew ? 'Create' : 'Edit'}: {name}
          </h2>
        </div>
        <div className="editor-toolbar-group">
          <input
            type="text"
            data-testid="editor-change-note"
            placeholder="Change note…"
            value={changeNote}
            onChange={e => setChangeNote(e.target.value)}
            style={{
              padding: 'var(--space-xs) var(--space-sm)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius-sm)',
              fontSize: '0.85rem',
              width: '200px',
              background: 'var(--bg)',
            }}
          />
          <button className="btn btn-ghost" onClick={() => setPanelOpen(p => !p)}
            style={{ fontSize: '1.1rem', padding: 'var(--space-xs) var(--space-sm)' }}
            title="Attachments">
            Attach
          </button>
          <button className="btn btn-ghost" data-testid="editor-cancel" onClick={() => navigate(`/wiki/${name}`)}>
            Cancel
          </button>
          <button className="btn btn-primary" data-testid="editor-save" onClick={save} disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>

      {(markupSyntax === 'wiki' || markupSyntax === 'likely-wiki') && (
        <div className="info-banner">
          <span>This page uses legacy wiki syntax. Convert to Markdown?</span>
          <button className="btn btn-primary btn-sm" onClick={handleConvert} disabled={converting}>
            {converting ? 'Converting…' : 'Convert to Markdown'}
          </button>
        </div>
      )}

      {conversionWarnings.length > 0 && (
        <div className="warning-banner">
          <strong>Conversion notes:</strong>
          <ul>
            {conversionWarnings.map((w, i) => <li key={i}>{w}</li>)}
          </ul>
        </div>
      )}

      {error && <div className="error-banner" data-testid="editor-error">{error}</div>}

      <div className="editor-container">
        <div className="editor-pane">
          <textarea
            ref={textareaRef}
            className="editor-textarea"
            data-testid="editor-textarea"
            value={content}
            onChange={e => setContent(e.target.value)}
            spellCheck="false"
          />
        </div>
        <div className="editor-pane editor-preview">
          <article className="article-prose">
            <ReactMarkdown remarkPlugins={[
              remarkGfm,
              [remarkAttachments, { attachments: attachments.list, pageName: name }],
            ]}>
              {previewContent}
            </ReactMarkdown>
          </article>
        </div>
      </div>

      {conflict && (
        <div className="modal-overlay" onClick={() => setConflict(null)}>
          <div className="modal-content admin-modal" onClick={e => e.stopPropagation()}>
            <h3 style={{
              fontFamily: 'var(--font-display)',
              fontSize: '1.25rem',
              fontWeight: 600,
              marginBottom: 'var(--space-md)',
            }}>
              Version Conflict
            </h3>
            <p style={{
              fontFamily: 'var(--font-ui)',
              fontSize: '0.9rem',
              color: 'var(--text-secondary)',
              lineHeight: 1.6,
              marginBottom: 'var(--space-lg)',
            }}>
              Someone else edited this page while you were working. Choose how to proceed:
            </p>
            <div className="modal-actions" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
              <button className="btn btn-primary" onClick={handleOverwrite} disabled={saving}>
                {saving ? 'Saving...' : 'Overwrite with my version'}
              </button>
              <button className="btn btn-ghost" onClick={handleDiscard}>
                Discard my changes (load server version)
              </button>
              <button className="btn btn-ghost" onClick={handleCopyAndLoad}>
                Copy my text to clipboard, then load server version
              </button>
            </div>
          </div>
        </div>
      )}

      <AttachmentPanel
        open={panelOpen}
        onClose={() => setPanelOpen(false)}
        pageName={name}
        attachments={attachments.list}
        onUpload={attachments.uploadAttachment}
        onRename={handleRename}
        onDelete={attachments.deleteAttachment}
        editorContent={content}
      />
    </div>
  );
}
