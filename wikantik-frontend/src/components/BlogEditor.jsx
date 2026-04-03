import { useState, useEffect, useMemo } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api } from '../api/client';
import { reconstructContent } from '../utils/frontmatterUtils';
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

  const isHome = pageName === 'Blog';

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

  if (loading) return <div className="loading">Loading…</div>;

  return (
    <div className="page-enter">
      <div className="editor-toolbar">
        <div className="editor-toolbar-group">
          <h2 style={{ fontFamily: 'var(--font-display)', fontSize: '1.25rem', fontWeight: 600 }}>
            Edit: {isHome ? `${username}'s Blog` : pageName}
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
          <Link
            to={`/blog/${encodeURIComponent(username)}/${encodeURIComponent(pageName)}`}
            className="btn btn-ghost"
          >
            Cancel
          </Link>
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
              {previewContent}
            </ReactMarkdown>
          </article>
        </div>
      </div>
    </div>
  );
}
