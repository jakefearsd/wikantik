import { useState, useCallback } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import { useAuth } from '../hooks/useAuth';
import PageMeta from './PageMeta';
import MetadataPanel from './MetadataPanel';
import ChangeNotesPanel from './ChangeNotesPanel';
import CommentsPanel from './CommentsPanel';
import '../styles/article.css';
import '../styles/admin.css';

export default function PageView() {
  const { name = 'Main' } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const { data: page, loading, error } = useApi(() => api.getPage(name, { render: true }), [name, user?.authenticated]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleteError, setDeleteError] = useState(null);
  const [showRename, setShowRename] = useState(false);
  const [newName, setNewName] = useState('');
  const [renameError, setRenameError] = useState(null);
  const [renaming, setRenaming] = useState(false);

  async function handleDelete() {
    try {
      await api.deletePage(name);
      setConfirmDelete(false);
      navigate('/wiki/Main');
    } catch (err) {
      setDeleteError(err.message || 'Failed to delete page');
    }
  }

  async function handleRename() {
    if (!newName.trim()) {
      setRenameError('New page name cannot be blank');
      return;
    }
    setRenaming(true);
    setRenameError(null);
    try {
      const result = await api.renamePage(name, newName.trim());
      setShowRename(false);
      setNewName('');
      navigate(`/wiki/${result.newName}`);
    } catch (err) {
      setRenameError(err.body?.message || err.message || 'Failed to rename page');
    } finally {
      setRenaming(false);
    }
  }

  // Intercept clicks on internal wiki links in rendered HTML content
  // so they use React Router navigation instead of full-page reloads.
  const handleContentClick = useCallback((e) => {
    const anchor = e.target.closest('a');
    if (!anchor) return;

    const href = anchor.getAttribute('href');
    if (!href) return;

    // Handle internal wiki links — these may be:
    //   /app/wiki/PageName  (React-prefixed)
    //   /wiki/PageName      (rendered by wiki engine without /app/ prefix)
    //   /app/edit/PageName  (React edit links)
    //   /edit/PageName      (edit links without prefix)
    let internalPath = null;

    if (href.startsWith('/app/')) {
      internalPath = href.substring('/app'.length); // keep leading /
    } else if (href.startsWith('/wiki/')) {
      internalPath = href; // /wiki/PageName → navigate as /wiki/PageName
    } else if (href.startsWith('/edit/')) {
      internalPath = href;
    } else if (href.startsWith('/diff/')) {
      internalPath = href;
    } else if (href.startsWith('/search')) {
      internalPath = href;
    } else {
      return; // external or unrecognized link — let browser handle it
    }

    // Ctrl/Cmd+click or middle-click: let browser open in new tab
    if (e.metaKey || e.ctrlKey || e.button !== 0) return;

    e.preventDefault();
    navigate(internalPath);
  }, [navigate]);

  if (loading) return <div className="loading">Loading…</div>;
  if (error?.status === 404) return <NotFound name={name} />;
  if (error) return <div className="error-banner">Failed to load page: {error.message}</div>;
  if (!page) return null;

  return (
    <div className="page-enter">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-md)' }}>
        <PageMeta page={page} />
        <div style={{ display: 'flex', gap: 'var(--space-sm)', flexShrink: 0 }}>
          {page.permissions?.edit && (
            <Link to={`/edit/${name}`} className="btn btn-ghost">
              ✎ Edit
            </Link>
          )}
          {page.permissions?.rename && (
            <button className="btn btn-ghost" onClick={() => { setShowRename(true); setNewName(name); setRenameError(null); }}>
              Rename
            </button>
          )}
          {page.permissions?.delete && (
            <button className="btn btn-ghost btn-danger" onClick={() => { setConfirmDelete(true); setDeleteError(null); }}>
              🗑 Delete
            </button>
          )}
        </div>
      </div>

      {confirmDelete && (
        <div className="modal-overlay" onClick={() => setConfirmDelete(false)}>
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3>Delete Page</h3>
            <p>Are you sure you want to delete "{name}"? This action cannot be undone.</p>
            {deleteError && <p className="error-banner" style={{ marginBottom: 'var(--space-sm)' }}>{deleteError}</p>}
            <div className="modal-actions">
              <button className="btn btn-ghost" onClick={() => setConfirmDelete(false)}>Cancel</button>
              <button className="btn btn-primary btn-danger" onClick={handleDelete}>Delete</button>
            </div>
          </div>
        </div>
      )}

      {showRename && (
        <div className="modal-overlay" onClick={() => setShowRename(false)}>
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3>Rename Page</h3>
            <p>Enter a new name for "{name}":</p>
            <input
              type="text"
              className="form-input"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && !renaming) handleRename(); }}
              autoFocus
              style={{ width: '100%', marginBottom: 'var(--space-sm)' }}
            />
            {renameError && <p className="error-banner" style={{ marginBottom: 'var(--space-sm)' }}>{renameError}</p>}
            <div className="modal-actions">
              <button className="btn btn-ghost" onClick={() => setShowRename(false)} disabled={renaming}>Cancel</button>
              <button className="btn btn-primary" onClick={handleRename} disabled={renaming}>
                {renaming ? 'Renaming...' : 'Rename'}
              </button>
            </div>
          </div>
        </div>
      )}
      <MetadataPanel metadata={page.metadata} />
      <ChangeNotesPanel pageName={name} />

      <article
        className="article-prose"
        onClick={handleContentClick}
        dangerouslySetInnerHTML={{ __html: page.contentHtml || page.content || '' }}
      />

      <CommentsPanel pageName={name} />

      {page.metadata?.tags && (
        <div style={{ marginTop: 'var(--space-2xl)', display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
          {(Array.isArray(page.metadata.tags) ? page.metadata.tags : [page.metadata.tags]).map(tag => (
            <Link key={tag} to={`/search?q=${encodeURIComponent(tag)}`} className="tag" style={{ textDecoration: 'none' }}>
              {tag}
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

function NotFound({ name }) {
  return (
    <div className="page-enter" style={{ textAlign: 'center', padding: 'var(--space-2xl) 0' }}>
      <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '2rem', marginBottom: 'var(--space-md)' }}>
        Page not found
      </h1>
      <p style={{ color: 'var(--text-muted)', marginBottom: 'var(--space-lg)' }}>
        "{name}" doesn't exist yet.
      </p>
      <Link to={`/edit/${name}`} className="btn btn-primary">
        Create "{name}"
      </Link>
    </div>
  );
}
