import { useState, useCallback, useEffect, useRef } from 'react';
import { useParams, Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import { useAuth } from '../hooks/useAuth';
import { renderMath } from '../utils/math';
import PageMeta from './PageMeta';
import MetadataPanel from './MetadataPanel';
import SimilarPagesPanel from './SimilarPagesPanel';
import ChangeNotesPanel from './ChangeNotesPanel';
import CommentsDrawer from './CommentsDrawer';
import MentionPicker from './MentionPicker';
import { useMentionPicker } from '../hooks/useMentionPicker';
import { captureSelection } from '../utils/commentAnchor';
import { anchorThreads, clearHighlights, anchorPendingHighlight, clearPendingHighlight } from '../utils/commentHighlight';
import 'katex/dist/katex.min.css';
import '../styles/article.css';
import '../styles/admin.css';

/** Inline composer popover anchored to a selection rect. Replaces window.prompt
 *  so the affordance flows with the page (theme-aware, multi-line, no native
 *  "localhost:8080 says" chrome). Local state for the draft text keeps PageView
 *  re-renders cheap while typing. */
function CommentComposer({ rect, quote, onSubmit, onCancel }) {
  const [text, setText] = useState('');
  const taRef = useRef(null);
  useEffect(() => { taRef.current?.focus(); }, []);
  const grow = (el) => { el.style.height = 'auto'; el.style.height = el.scrollHeight + 'px'; };
  const submit = () => { if (text.trim()) onSubmit(text.trim()); };
  // Mention picker — fetch candidates on every keystroke that opens an `@<token>`.
  // The hook handles debounce/state; we just wire onChange/onKeyDown into the
  // existing textarea handlers and render the dumb popover next to the composer.
  const picker = useMentionPicker({
    textareaRef: taRef,
    fetchCandidates: async (q) => {
      try { const r = await api.listMentionableUsers(q); return r.users || []; }
      catch { return []; }
    },
  });
  const acceptLogin = (login) => {
    const { replacement, selectionStart } = picker.accept(login);
    setText(replacement);
    // After React renders, restore caret + auto-grow.
    setTimeout(() => {
      const ta = taRef.current;
      if (!ta) return;
      ta.setSelectionRange(selectionStart, selectionStart);
      grow(ta);
      ta.focus();
    }, 0);
  };
  // Keep the composer on-screen if the selection sits near the right edge.
  const left = Math.max(8, Math.min(rect.left, (window.innerWidth || 1024) - 340));
  return (
    <div
      className="comment-composer"
      style={{ position: 'fixed', top: rect.bottom + 6, left }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') { e.preventDefault(); onCancel(); }
      }}
    >
      <div className="comment-composer-quote">“{quote}”</div>
      <textarea
        ref={taRef}
        className="comment-composer-input"
        placeholder="Add a comment"
        value={text}
        rows={2}
        onChange={(e) => { setText(e.target.value); grow(e.target); picker.onChange(e); }}
        onKeyDown={(e) => {
          // Picker intercepts navigation keys + Enter/Tab while open. On Enter/Tab,
          // commit the highlighted candidate; otherwise just swallow the event.
          if (picker.onKeyDown(e)) {
            if (e.key === 'Enter' || e.key === 'Tab') {
              const sel = picker.candidates[picker.selectedIndex];
              if (sel) acceptLogin(sel.loginName);
            }
            return;
          }
          if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submit(); }
        }}
      />
      <div className="comment-composer-actions">
        <button type="button" className="comment-composer-cancel" onClick={onCancel}>Cancel</button>
        <button
          type="button"
          className="comment-composer-submit"
          disabled={!text.trim()}
          onClick={submit}
        >
          Comment
        </button>
      </div>
      <MentionPicker
        open={picker.open}
        candidates={picker.candidates}
        selectedIndex={picker.selectedIndex}
        onSelect={acceptLogin}
        anchorPos={picker.anchorPos}
      />
    </div>
  );
}

export default function PageView() {
  const { name = 'Main' } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { data: page, loading, error } = useApi((signal) => api.getPage(name, { render: true, signal }), [name, user?.authenticated]);

  const articleRef = useRef(null);

  const [threads, setThreads] = useState([]);
  const [detachedIds, setDetachedIds] = useState([]);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [statusFilter, setStatusFilter] = useState('open');
  const [selection, setSelection] = useState(null); // {selector, rect} | {error} | null
  const [composerOpen, setComposerOpen] = useState(false);

  const loadThreads = useCallback(async () => {
    try {
      const res = await api.listCommentThreads(name, 'all');
      setThreads(res.threads || []);
    } catch (e) {
      console.warn('Failed to load comment threads', e);
    }
  }, [name]);

  useEffect(() => { loadThreads(); }, [loadThreads]);

  // Render LaTeX math expressions via KaTeX after page content is injected.
  // Depend on the `page` object reference (not the contentHtml string) so the
  // effect fires on every refetch — e.g. auth state transitions where
  // dangerouslySetInnerHTML resets the DOM and wipes previously-rendered
  // KaTeX output. renderMath is idempotent (guards with `math-rendered`).
  useEffect(() => {
    if (articleRef.current && page?.contentHtml) {
      renderMath(articleRef.current);
    }
  }, [page]);

  // Re-anchor comment highlights into the rendered article. Placed AFTER the
  // renderMath effect so it runs after KaTeX has settled the DOM. Depends on
  // the same `page` reference the renderMath effect uses (re-runs on refetch)
  // plus `threads` (re-runs when comments load/change).
  useEffect(() => {
    const root = articleRef.current;
    if (!root || !page?.contentHtml) return;
    clearHighlights(root);
    const { detached } = anchorThreads(root, threads);
    setDetachedIds(detached);
  }, [page, threads]);

  const onArticleMouseUp = () => {
    const root = articleRef.current;
    if (!root) return;
    clearPendingHighlight(root); // drop any stale pending paint from a prior selection
    setComposerOpen(false);      // any new selection starts with composer closed
    setSelection(captureSelection(root));
  };

  const focusThread = (threadId) => {
    const mark = articleRef.current?.querySelector(`mark[data-thread-id="${threadId}"]`);
    if (mark) {
      mark.scrollIntoView({ block: 'center', behavior: 'smooth' });
      mark.classList.add('comment-highlight-pulse');
      setTimeout(() => mark.classList.remove('comment-highlight-pulse'), 1200);
    }
  };

  // Deep-link from the mentions feed: ?thread=<id>&comment=<id> opens the drawer
  // and focuses the thread. Strip the params so a refresh doesn't re-focus.
  const dlAppliedRef = useRef(false);
  useEffect(() => {
    if (dlAppliedRef.current) return;
    const threadId = searchParams.get('thread');
    if (!threadId || threads.length === 0) return;
    const exists = threads.some((t) => t.id === threadId);
    if (!exists) return;
    dlAppliedRef.current = true;
    setStatusFilter('all');
    setDrawerOpen(true);
    // Focus after the drawer + mark render. The anchoring effect runs in the
    // same commit but re-runs whenever the `page` reference changes (auth-driven
    // refetches teardown + rebuild the marks). Retry briefly so a transient
    // "no mark yet" window doesn't drop the scroll.
    const tryFocus = (attemptsLeft) => {
      const mark = articleRef.current?.querySelector(`mark[data-thread-id="${threadId}"]`);
      if (mark) { focusThread(threadId); return; }
      if (attemptsLeft > 0) setTimeout(() => tryFocus(attemptsLeft - 1), 50);
    };
    setTimeout(() => tryFocus(20), 0);
    // Strip the query params from the URL so refresh doesn't re-trigger.
    if (typeof window !== 'undefined' && window.history?.replaceState) {
      const url = window.location.pathname + window.location.hash;
      window.history.replaceState({}, '', url);
    }
  }, [searchParams, threads]);

  const createThread = async (text) => {
    if (!selection?.selector || !text.trim()) return;
    try {
      await api.createCommentThread(name, { ...selection.selector, text: text.trim() });
    } catch (e) {
      console.warn('Failed to create comment thread', e);
    } finally {
      if (articleRef.current) clearPendingHighlight(articleRef.current);
      setComposerOpen(false);
      setSelection(null);
      window.getSelection()?.removeAllRanges();
      await loadThreads();
    }
    setDrawerOpen(true);
  };

  // Sync document.title with current page. Integration tests assert titles
  // like "Wikantik: Main" so keep the format stable.
  useEffect(() => {
    if (error?.status === 404) {
      document.title = `Wikantik: Not Found`;
    } else if (page) {
      document.title = `Wikantik: ${name}`;
    }
  }, [name, page, error]);

  // If the current page is forbidden (403) or requires authentication (401),
  // bounce to Main. This mirrors the legacy haddock template's server-side
  // redirect behavior when a user loses view permission on the active page
  // (e.g. after logout on a page guarded by [{ALLOW view Janne}]). Without
  // this redirect the SPA would render a bare error banner and integration
  // tests that assert the post-logout page is Main would fail.
  useEffect(() => {
    if (name !== 'Main' && (error?.status === 403 || error?.status === 401)) {
      navigate('/wiki/Main', { replace: true });
    }
  }, [name, error, navigate]);
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
    const mark = e.target.closest && e.target.closest('mark.comment-highlight');
    if (mark) {
      setDrawerOpen(true);
      focusThread(mark.dataset.threadId);
      return;
    }

    const anchor = e.target.closest('a');
    if (!anchor) return;

    const href = anchor.getAttribute('href');
    if (!href) return;

    // Handle internal wiki links:
    //   /wiki/PageName
    //   /edit/PageName
    //   /diff/PageName
    //   /search?q=...
    let internalPath = null;

    if (href.startsWith('/wiki/')) {
      internalPath = href;
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
    <div className="page-enter" data-testid="page-view" data-page-name={name}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-md)' }}>
        <PageMeta page={page} />
        <div style={{ display: 'flex', gap: 'var(--space-sm)', flexShrink: 0 }}>
          {threads.length > 0 && (
            <button className="btn btn-ghost" onClick={() => setDrawerOpen((o) => !o)}>
              💬 Comments ({threads.filter((t) => t.status === 'open').length})
            </button>
          )}
          {page.permissions?.edit && (
            <Link to={`/edit/${name}`} className="btn btn-ghost" data-testid="edit-page-link">
              ✎ Edit
            </Link>
          )}
          {page.permissions?.rename && (
            <button className="btn btn-ghost" data-testid="rename-page-button" onClick={() => { setShowRename(true); setNewName(name); setRenameError(null); }}>
              Rename
            </button>
          )}
          {page.permissions?.delete && (
            <button className="btn btn-ghost btn-danger" data-testid="delete-page-button" onClick={() => { setConfirmDelete(true); setDeleteError(null); }}>
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
      <SimilarPagesPanel pageName={name} />
      <ChangeNotesPanel pageName={name} />

      <article
        ref={articleRef}
        className="article-prose"
        onClick={handleContentClick}
        onMouseUp={onArticleMouseUp}
        dangerouslySetInnerHTML={{ __html: page.contentHtml || page.content || '' }}
      />

      <CommentsDrawer
        open={drawerOpen}
        threads={threads}
        detachedIds={detachedIds}
        statusFilter={statusFilter}
        canModerate={!!page.permissions?.delete}
        onStatusFilter={setStatusFilter}
        onReply={async (threadId, text) => {
          try { await api.addCommentReply(threadId, text); }
          catch (e) { console.warn('Failed to add reply', e); }
          finally { await loadThreads(); }
        }}
        onDeleteThread={async (threadId) => {
          try { await api.deleteCommentThread(threadId); }
          catch (e) { console.warn('Failed to delete thread', e); }
          finally { await loadThreads(); }
        }}
        onResolve={async (threadId) => {
          try { await api.resolveCommentThread(threadId); }
          catch (e) { console.warn('Failed to resolve thread', e); }
          finally { await loadThreads(); }
        }}
        onReopen={async (threadId) => {
          try { await api.reopenCommentThread(threadId); }
          catch (e) { console.warn('Failed to reopen thread', e); }
          finally { await loadThreads(); }
        }}
        onFocusThread={focusThread}
        onClose={() => setDrawerOpen(false)}
      />

      {selection?.selector && !composerOpen && (
        <button
          className="comment-add-floating"
          style={{ position: 'fixed', top: selection.rect.bottom + 6, left: selection.rect.left }}
          onClick={() => {
            // Paint a pending-highlight before opening the composer so the
            // selected text stays visible after focus moves to the textarea
            // (the native selection would otherwise collapse).
            if (articleRef.current && selection.selector) {
              anchorPendingHighlight(articleRef.current, selection.selector);
            }
            setComposerOpen(true);
          }}
        >
          💬 Comment
        </button>
      )}
      {selection?.selector && composerOpen && (
        <CommentComposer
          rect={selection.rect}
          quote={selection.selector.exact}
          onSubmit={createThread}
          onCancel={() => {
            if (articleRef.current) clearPendingHighlight(articleRef.current);
            setComposerOpen(false);
            setSelection(null);
            window.getSelection()?.removeAllRanges();
          }}
        />
      )}
      {selection?.error === 'math' && (
        <div className="comment-math-hint" style={{ position: 'fixed', top: 8, right: 8 }}>
          Can’t comment on math expressions — select plain text.
        </div>
      )}

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
