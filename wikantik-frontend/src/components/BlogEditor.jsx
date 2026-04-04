import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api } from '../api/client';
import { reconstructContent } from '../utils/frontmatterUtils';
import { remarkAttachments } from '../utils/remarkAttachments';
import { useAttachments } from '../hooks/useAttachments';
import { useEditorDrop } from '../hooks/useEditorDrop';
import AttachmentPanel from './AttachmentPanel';
import '../styles/article.css';
import '../styles/admin.css';

export default function BlogEditor() {
  const { username, pageName } = useParams();
  const navigate = useNavigate();
  const [content, setContent] = useState('');
  const [originalVersion, setOriginalVersion] = useState(null);
  const [changeNote, setChangeNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [panelOpen, setPanelOpen] = useState(false);
  const textareaRef = useRef(null);

  const isHome = pageName === 'Blog';
  const blogPageName = `blog/${username}/${pageName}`;
  const attachments = useAttachments(blogPageName);

  const handleInsert = useCallback((text, pos) => {
    setContent(prev => prev.slice(0, pos) + text + prev.slice(pos));
  }, []);

  useEditorDrop(textareaRef, handleInsert);

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
    setLoading(true);
    const fetcher = isHome
      ? api.blog.get(username)
      : api.blog.getEntry(username, pageName);

    fetcher.then(data => {
      setContent(reconstructContent(data.metadata, data.content));
      setOriginalVersion(data.version);
      setLoading(false);
    }).catch(err => {
      setError(err.message || 'Failed to load page');
      setLoading(false);
    });
  }, [username, pageName]);

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      if (isHome) {
        await api.blog.update(username, content);
      } else {
        await api.blog.updateEntry(username, pageName, content);
      }
      navigate(`/blog/${encodeURIComponent(username)}/${encodeURIComponent(pageName)}`);
    } catch (err) {
      setError(err.message || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  // Strip frontmatter from preview — show only the body portion
  const previewContent = useMemo(() => {
    const match = content.match(/^---\r?\n[\s\S]*?\r?\n---\r?\n([\s\S]*)$/);
    return match ? match[1] : content;
  }, [content]);

  if (loading) return <div className="loading">Loading\u2026</div>;

  return (
    <div className={`page-enter${panelOpen ? ' editor-with-panel' : ''}`}>
      <div className="editor-toolbar">
        <div className="editor-toolbar-group">
          <h2 style={{ fontFamily: 'var(--font-display)', fontSize: '1.25rem', fontWeight: 600 }}>
            Edit: {isHome ? `${username}'s Blog` : pageName}
          </h2>
        </div>
        <div className="editor-toolbar-group">
          <input
            type="text"
            placeholder="Change note\u2026"
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
          <Link
            to={`/blog/${encodeURIComponent(username)}/${encodeURIComponent(pageName)}`}
            className="btn btn-ghost"
          >
            Cancel
          </Link>
          <button className="btn btn-primary" onClick={save} disabled={saving}>
            {saving ? 'Saving\u2026' : 'Save'}
          </button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="editor-container">
        <div className="editor-pane">
          <textarea
            ref={textareaRef}
            className="editor-textarea"
            value={content}
            onChange={e => setContent(e.target.value)}
            spellCheck="false"
          />
        </div>
        <div className="editor-pane editor-preview">
          <article className="article-prose">
            <ReactMarkdown remarkPlugins={[
              remarkGfm,
              [remarkAttachments, { attachments: attachments.list, pageName: blogPageName }],
            ]}>
              {previewContent}
            </ReactMarkdown>
          </article>
        </div>
      </div>

      <AttachmentPanel
        open={panelOpen}
        onClose={() => setPanelOpen(false)}
        pageName={blogPageName}
        attachments={attachments.list}
        onUpload={attachments.uploadAttachment}
        onRename={handleRename}
        onDelete={attachments.deleteAttachment}
        editorContent={content}
      />
    </div>
  );
}
