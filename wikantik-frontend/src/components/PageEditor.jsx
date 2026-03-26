import { useState, useEffect } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api } from '../api/client';
import { reconstructContent } from '../utils/frontmatterUtils';
import '../styles/article.css';

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

  useEffect(() => {
    api.getPage(name).then(page => {
      setContent(reconstructContent(page.metadata, page.content));
      setOriginalVersion(page.version);
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

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      await api.savePage(name, {
        content,
        changeNote: changeNote || (isNew ? 'Created page' : 'Updated page'),
        expectedVersion: isNew ? undefined : originalVersion,
      });
      navigate(`/wiki/${name}`);
    } catch (err) {
      if (err.status === 409) {
        setError('Version conflict — someone else edited this page. Please reload and try again.');
      } else {
        setError(err.message || 'Save failed');
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="page-enter">
      <div className="editor-toolbar">
        <div className="editor-toolbar-group">
          <h2 style={{ fontFamily: 'var(--font-display)', fontSize: '1.25rem', fontWeight: 600 }}>
            {isNew ? 'Create' : 'Edit'}: {name}
          </h2>
        </div>
        <div className="editor-toolbar-group">
          <input
            type="text"
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
          <button className="btn btn-ghost" onClick={() => navigate(`/wiki/${name}`)}>
            Cancel
          </button>
          <button className="btn btn-primary" onClick={save} disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="editor-container">
        <div className="editor-pane">
          <textarea
            className="editor-textarea"
            value={content}
            onChange={e => setContent(e.target.value)}
            spellCheck="false"
          />
        </div>
        <div className="editor-pane editor-preview">
          <article className="article-prose">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>
              {content}
            </ReactMarkdown>
          </article>
        </div>
      </div>
    </div>
  );
}
