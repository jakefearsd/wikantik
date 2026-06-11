import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import { api } from '../api/client';
import { reconstructContent, stripFrontmatter, frontmatterOffsetLines } from '../utils/frontmatterUtils';
import { frontmatterLineCount, caretToPreviewFraction, previewFractionToLine, previewScrollTopFor } from '../utils/scrollSync';
import rehypeSourceLine from '../utils/rehypeSourceLine';
import FrontmatterPreview from './FrontmatterPreview';
import FrontmatterEditor from './frontmatter/FrontmatterEditor';
import ValidationSummary from './frontmatter/ValidationSummary';
import MathValidationSummary from './MathValidationSummary';
import { useFrontmatterValidation } from '../hooks/useFrontmatterValidation';
import KnowledgeGraphPanel from './knowledge/KnowledgeGraphPanel';
import Tabs from './ui/Tabs';
import { remarkAttachments } from '../utils/remarkAttachments';
import { useAttachments } from '../hooks/useAttachments';
import { useEditorDrop } from '../hooks/useEditorDrop';
import { useDraft } from '../hooks/useDraft';
import { useAuth } from '../hooks/useAuth';
import { useToast } from '../hooks/useToast';
import { formatRelative } from '../utils/datetime';
import { toggleWrap, toggleLinePrefix, insertLink, insertTable, insertCodeBlock } from '../utils/markdownFormat';
import { useDarkMode } from '../hooks/useDarkMode';
import EditorToolbar from './EditorToolbar';
import CodeEditor from './CodeEditor';
import AttachmentPanel from './AttachmentPanel';
import '../styles/article.css';
import '../styles/admin.css';

export default function PageEditor() {
  const { name } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToast();
  // The frontmatter object and the markdown BODY are the two canonical pieces; CodeMirror edits the
  // body only, the structured FrontmatterEditor edits the object. Full text is derived where needed
  // (draft autosave, dirty baseline, frontmatter preview, clipboard).
  const [body, setBody] = useState('');
  const [metadata, setMetadata] = useState({});
  const [violations, setViolations] = useState([]);
  const [mathViolations, setMathViolations] = useState([]);
  const [loaded, setLoaded] = useState(false);
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
  const [showDiscardConfirm, setShowDiscardConfirm] = useState(false);
  const [activeMetaTab, setActiveMetaTab] = useState('frontmatter');
  const [isDragging, setIsDragging] = useState(false);
  const editorRef = useRef(null);
  const dropContainerRef = useRef(null);
  const previewRef = useRef(null);
  const syncRafRef = useRef(0);
  const editorRafRef = useRef(0);
  const syncingRef = useRef(false);
  const clickSyncGuardRef = useRef(false);
  const [dark] = useDarkMode();
  const attachments = useAttachments(name);
  const savingRef = useRef(false);

  // Live, debounced frontmatter validation. Authoritative source of inline violations and the
  // Save gate; merges any save-response violations not already represented live.
  const { violations: liveViolations, validating } = useFrontmatterValidation(metadata, {
    enabled: metadata != null,
  });
  const displayViolations = useMemo(() => {
    const seen = new Set(liveViolations.map((v) => `${v.field}|${v.code}`));
    return [...liveViolations, ...violations.filter((v) => !seen.has(`${v.field}|${v.code}`))];
  }, [liveViolations, violations]);
  const hasBlockingErrors = displayViolations.some((v) => v.severity === 'ERROR')
    || mathViolations.some((v) => v.severity === 'ERROR');
  const jumpToField = (field) => {
    const top = String(field || '').split('.')[0];
    document.querySelector(`[data-field="${top}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  const jumpToMath = (loc) => {
    if (!loc || !editorRef.current) return;
    editorRef.current.setSelection(loc.startOffset, loc.endOffset);
    editorRef.current.scrollToLine(loc.line);
    editorRef.current.focus();
  };

  const { user } = useAuth();
  const login = user?.authenticated ? user.loginPrincipal : null;
  const { draft, saveDraft, clearDraft } = useDraft({
    login,
    pageId: name,
    enabled: !!login,
  });
  const [restorePrompt, setRestorePrompt] = useState(false);
  const loadedContentRef = useRef(null);

  // Full reconstructed text (frontmatter + body) — derived, used for the draft, the dirty baseline,
  // the frontmatter preview card, and clipboard copy on conflict.
  const fullText = useMemo(() => reconstructContent(metadata, body), [metadata, body]);

  // #20 — isDirty: true only once the page has loaded and the reconstructed text differs from baseline.
  const isDirty = loaded && fullText !== loadedContentRef.current;

  // Keep the body in a ref so the stable scroll-sync / format callbacks read current text.
  const bodyRef = useRef(body);
  bodyRef.current = body;

  // Wraps setBody to also clear stale math violations so they don't persist after the user edits.
  const handleBodyChange = useCallback((newBody) => {
    setBody(newBody);
    if (mathViolations.length > 0) setMathViolations([]);
  }, [mathViolations.length]);

  // Editor→preview scroll sync. The editor now holds the BODY only, so the frontmatter offset is 0;
  // the helpers stay (returning 0) for robustness.
  const syncPreview = useCallback(() => {
    if (syncingRef.current || syncRafRef.current || clickSyncGuardRef.current) return;
    syncRafRef.current = requestAnimationFrame(() => {
      syncRafRef.current = 0;
      if (clickSyncGuardRef.current) return;
      const editor = editorRef.current;
      const preview = previewRef.current;
      if (!editor?.getViewport || !preview) return;
      const vp = editor.getViewport();
      if (!vp) return;
      const fm = frontmatterLineCount(bodyRef.current);
      const fraction = caretToPreviewFraction(vp.topLine, vp.totalLines, fm);
      syncingRef.current = true;
      preview.scrollTop = previewScrollTopFor(fraction, preview.scrollHeight, preview.clientHeight);
      requestAnimationFrame(() => { syncingRef.current = false; });
    });
  }, []);

  const syncEditor = useCallback(() => {
    if (syncingRef.current || editorRafRef.current) return;
    editorRafRef.current = requestAnimationFrame(() => {
      editorRafRef.current = 0;
      const editor = editorRef.current;
      const preview = previewRef.current;
      if (!editor?.scrollToLine || !preview) return;
      const range = preview.scrollHeight - preview.clientHeight;
      const fraction = range > 0 ? preview.scrollTop / range : 0;
      const total = bodyRef.current ? bodyRef.current.split('\n').length : 1;
      const fm = frontmatterLineCount(bodyRef.current);
      syncingRef.current = true;
      editor.scrollToLine(previewFractionToLine(fraction, total, fm));
      requestAnimationFrame(() => { syncingRef.current = false; });
    });
  }, []);

  useEffect(() => () => {
    if (syncRafRef.current) cancelAnimationFrame(syncRafRef.current);
    if (editorRafRef.current) cancelAnimationFrame(editorRafRef.current);
  }, []);

  const handlePreviewClick = useCallback((e) => {
    const el = e.target.closest?.('[data-line]');
    if (!el) return;
    const bodyLine = parseInt(el.getAttribute('data-line'), 10);
    if (Number.isNaN(bodyLine)) return;
    const sourceLine = bodyLine + frontmatterOffsetLines(bodyRef.current);
    if (syncRafRef.current) {
      cancelAnimationFrame(syncRafRef.current);
      syncRafRef.current = 0;
    }
    const scrollerRect = editorRef.current?.getScrollerRect?.();
    const previewRect = previewRef.current?.getBoundingClientRect?.() ?? null;
    const scrollerTop = scrollerRect ? scrollerRect.top : previewRect ? previewRect.top : 0;
    const offset = el.getBoundingClientRect().top - scrollerTop;
    clickSyncGuardRef.current = true;
    syncingRef.current = true;
    editorRef.current?.jumpToLineAligned?.(sourceLine, offset);
    setTimeout(() => {
      clickSyncGuardRef.current = false;
      syncingRef.current = false;
    }, 300);
  }, []);

  // Page names for `[[`-triggered internal-link autocomplete in the editor.
  const pageNamesRef = useRef([]);
  useEffect(() => {
    let cancelled = false;
    api.listPages({ limit: 1000 })
      .then(d => { if (!cancelled) pageNamesRef.current = (d.pages || []).map(p => p.name); })
      .catch(() => { /* autocomplete is a nicety; degrade silently */ });
    return () => { cancelled = true; };
  }, []);
  const getPageNames = useCallback(() => pageNamesRef.current, []);

  // Search-backed option source for the `related` field's page picker.
  const pageSearch = useCallback((q) =>
    Promise.resolve()
      .then(() => (api.search ? api.search(q) : { results: [] }))
      .then((r) => (r.results || []).map((x) => x.name)), []);

  const handleInsert = useCallback((text, pos) => {
    setBody(prev => prev.slice(0, pos) + text + prev.slice(pos));
  }, []);

  const getDropOffset = useCallback(() => {
    return editorRef.current ? editorRef.current.getSelection().selStart : 0;
  }, []);

  useEditorDrop(dropContainerRef, handleInsert, getDropOffset);

  // The editor holds the body only, so the preview renders it directly (no frontmatter to strip).
  const previewContent = useMemo(() => stripFrontmatter(body), [body]);

  const handleRename = useCallback(async (oldName, newName) => {
    const result = await attachments.renameAttachment(oldName, newName);
    setBody(prev => {
      const escaped = oldName.replace(/\./g, '\\.');
      return prev
        .replace(new RegExp(`(!\\[[^\\]]*\\])\\(${escaped}\\)`, 'g'), `$1(${newName})`)
        .replace(new RegExp(`(\\[[^\\]]*\\])\\(${escaped}\\)`, 'g'), `$1(${newName})`);
    });
    return result;
  }, [attachments]);

  useEffect(() => {
    api.getPage(name).then(page => {
      const meta = page.metadata || {};
      const pageBody = page.content || '';
      setMetadata(meta);
      setBody(pageBody);
      const full = reconstructContent(meta, pageBody);
      loadedContentRef.current = full;
      if (draft && draft.content && draft.content !== full) {
        setRestorePrompt(true);
      }
      setOriginalVersion(page.version);
      setMarkupSyntax(page.markupSyntax || 'markdown');
      setIsNew(false);
    }).catch(err => {
      if (err.status === 404) {
        const initialBody = location.state?.initialContent || `# ${name}\n\nWrite your content here.`;
        const initialMeta = location.state?.initialMetadata || {};
        setBody(initialBody);
        setMetadata(initialMeta);
        const full = reconstructContent(initialMeta, initialBody);
        loadedContentRef.current = full;
        if (draft && draft.content && draft.content !== full) {
          setRestorePrompt(true);
        }
        setIsNew(true);
      } else {
        setError(err.message);
      }
    }).finally(() => {
      setLoaded(true);
    });
  }, [name]);

  // Debounced autosave — fires 800 ms after the user stops typing.
  useEffect(() => {
    if (!login) return;
    if (loadedContentRef.current === null) return;
    const id = setTimeout(() => {
      if (fullText === loadedContentRef.current) {
        clearDraft();
      } else {
        saveDraft({ content: fullText, title: name });
      }
    }, 800);
    return () => clearTimeout(id);
  }, [fullText, name, login, saveDraft, clearDraft]);

  useEffect(() => {
    document.title = `Wikantik: ${isNew ? 'Create' : 'Edit'} ${name}`;
  }, [name, isNew]);

  useEffect(() => {
    const handler = (e) => {
      e.preventDefault();
      e.returnValue = '';
    };
    if (isDirty) {
      window.addEventListener('beforeunload', handler);
      return () => window.removeEventListener('beforeunload', handler);
    }
  }, [isDirty]);

  const applyFormat = useCallback((command) => {
    const editor = editorRef.current;
    if (!editor) return;

    const { selStart, selEnd } = editor.getSelection();
    const state = {
      text: bodyRef.current,
      selStart,
      selEnd,
    };

    let next;
    switch (command) {
      case 'bold':    next = toggleWrap(state, '**'); break;
      case 'italic':  next = toggleWrap(state, '*');  break;
      case 'code':    next = toggleWrap(state, '`');  break;
      case 'codeblock': next = insertCodeBlock(state); break;
      case 'heading': next = toggleLinePrefix(state, '## '); break;
      case 'list':    next = toggleLinePrefix(state, '- ');  break;
      case 'table':   next = insertTable(state); break;
      case 'link':    next = insertLink(state); break;
      default: return;
    }

    setBody(next.text);

    requestAnimationFrame(() => {
      editorRef.current?.setSelection(next.selStart, next.selEnd);
    });
  }, []);

  // #4 — Cmd/Ctrl+S save. The handler is registered once but calls the LATEST save via a ref, so it
  // always saves the current body/metadata (not a stale first-render closure).
  const latestSaveRef = useRef(null);
  useEffect(() => {
    const handler = (e) => {
      const hotkey = (e.metaKey || e.ctrlKey);
      if (!hotkey) return;
      if (e.key === 's') {
        e.preventDefault();
        if (!savingRef.current) latestSaveRef.current?.();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const handleBold = useCallback(() => applyFormat('bold'), [applyFormat]);
  const handleItalic = useCallback(() => applyFormat('italic'), [applyFormat]);
  const handleLink = useCallback(() => applyFormat('link'), [applyFormat]);

  const handleConvert = async () => {
    setConverting(true);
    setError(null);
    try {
      const result = await api.convertWikiToMarkdown(body);
      setBody(result.markdown);
      setMarkupSyntax('markdown');
      setConversionWarnings(result.warnings || []);
    } catch (err) {
      setError('Conversion failed: ' + (err.message || 'Unknown error'));
    } finally {
      setConverting(false);
    }
  };

  // #4 — extracted save function used by button and keyboard shortcut. Sends body + metadata object
  // (replaceMetadata=true for full-object replace); maps a 422 to inline field violations, surfaces
  // 200 warnings as an advisory toast.
  const saveContent = async () => {
    setSaving(true);
    savingRef.current = true;
    setError(null);
    try {
      const res = await api.savePage(name, {
        content: body,
        metadata,
        replaceMetadata: true,
        changeNote: changeNote || (isNew ? 'Created page' : 'Updated page'),
        expectedVersion: isNew ? undefined : originalVersion,
        markupSyntax: markupSyntax === 'markdown' ? 'markdown' : undefined,
      });
      setViolations([]);
      setMathViolations([]);
      clearDraft();
      const warns = (res && res.warnings) || [];
      const mathWarns = (res && res.mathWarnings) || [];
      if (mathWarns.length) {
        setMathViolations(mathWarns);
        toast.info(`Saved with ${mathWarns.length} math warning${mathWarns.length > 1 ? 's' : ''}`);
      } else if (warns.length) {
        toast.info(`Saved with ${warns.length} advisory warning${warns.length > 1 ? 's' : ''}`);
      } else {
        toast.success('Saved');
      }
      navigate(`/wiki/${name}`);
    } catch (err) {
      if (err.status === 409) {
        try {
          const serverPage = await api.getPage(name);
          setConflict({
            serverMetadata: serverPage.metadata || {},
            serverBody: serverPage.content || '',
            serverVersion: serverPage.version,
          });
        } catch (fetchErr) {
          setError('Version conflict, and failed to fetch the current server version.');
        }
      } else if (err.status === 422) {
        if (err.body?.error === 'math_validation_failed') {
          setMathViolations((err.body && err.body.violations) || []);
          toast.error('Fix the highlighted math errors');
        } else {
          setViolations((err.body && err.body.violations) || []);
          toast.error('Fix the highlighted frontmatter fields');
        }
      } else {
        setError(err.message || 'Save failed');
      }
    } finally {
      setSaving(false);
      savingRef.current = false;
    }
  };

  latestSaveRef.current = saveContent;
  const save = saveContent;

  const restoreDraft = useCallback(async () => {
    const full = draft?.content || '';
    setBody(stripFrontmatter(full));
    const m = full.match(/^---\r?\n([\s\S]*?)\r?\n---/);
    if (m) {
      try {
        const result = await api.validateFrontmatter({ frontmatter: m[1] });
        setMetadata(result?.metadata || {});
      } catch {
        setMetadata({});
      }
    } else {
      setMetadata({});
    }
    setRestorePrompt(false);
  }, [draft]);

  const dragCounterRef = useRef(0);

  const handleDragEnter = useCallback(() => {
    dragCounterRef.current += 1;
    setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback(() => {
    dragCounterRef.current -= 1;
    if (dragCounterRef.current <= 0) {
      dragCounterRef.current = 0;
      setIsDragging(false);
    }
  }, []);

  const handleDragOver = useCallback((e) => {
    e.preventDefault();
  }, []);

  const handleDrop = useCallback(() => {
    dragCounterRef.current = 0;
    setIsDragging(false);
  }, []);

  const handleCancel = () => {
    if (isDirty) {
      setShowDiscardConfirm(true);
    } else {
      navigate(`/wiki/${name}`);
    }
  };

  const handleOverwrite = async () => {
    setSaving(true);
    setConflict(null);
    setError(null);
    try {
      await api.savePage(name, {
        content: body,
        metadata,
        replaceMetadata: true,
        changeNote: changeNote || 'Updated page (overwrite)',
      });
      clearDraft();
      navigate(`/wiki/${name}`);
    } catch (err) {
      setError(err.message || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const handleDiscard = () => {
    setMetadata(conflict.serverMetadata);
    setBody(conflict.serverBody);
    setOriginalVersion(conflict.serverVersion);
    setConflict(null);
  };

  const handleCopyAndLoad = async () => {
    try {
      await navigator.clipboard.writeText(fullText);
    } catch {
      // Fallback: clipboard not available in all contexts; proceed anyway
    }
    setMetadata(conflict.serverMetadata);
    setBody(conflict.serverBody);
    setOriginalVersion(conflict.serverVersion);
    setConflict(null);
  };

  if (!loaded) {
    return (
      <div className="page-enter" data-testid="page-editor" data-page-name={name} data-loading="true">
        <div className="editor-toolbar">
          <div className="editor-toolbar-group">
            <h2 style={{ fontFamily: 'var(--font-display)', fontSize: '1.25rem', fontWeight: 600 }}>
              Loading…
            </h2>
          </div>
        </div>
      </div>
    );
  }

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
          <button className="btn btn-ghost" data-testid="editor-cancel" onClick={handleCancel}>
            Cancel
          </button>
          <button
            className="btn btn-primary"
            data-testid="editor-save"
            onClick={save}
            disabled={saving || hasBlockingErrors}
            title={hasBlockingErrors ? 'Fix the highlighted errors before saving' : undefined}
          >
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

      {restorePrompt && (
        <div className="draft-restore-banner" role="status">
          <span title={new Date(draft.savedAt).toLocaleString()}>
            You have unsaved changes from{' '}
            {formatRelative(draft.savedAt)}.
          </span>
          <button type="button" className="btn-link" onClick={restoreDraft}>
            Restore
          </button>
          <button type="button" className="btn-link"
            onClick={() => { clearDraft(); setRestorePrompt(false); }}>
            Discard
          </button>
          <button type="button" className="btn-link"
            aria-label="Dismiss draft notice"
            onClick={() => setRestorePrompt(false)}>
            ×
          </button>
        </div>
      )}

      {/* Structured frontmatter / Knowledge Graph tabs — shares the edit pane; CodeMirror below is body-only. */}
      <section className="editor-frontmatter">
        <Tabs
          tabs={[
            { id: 'frontmatter', label: 'Frontmatter' },
            { id: 'knowledge', label: 'Knowledge' },
          ]}
          active={activeMetaTab}
          onChange={setActiveMetaTab}
        >
          {activeMetaTab === 'frontmatter' && (
            <>
              <ValidationSummary violations={displayViolations} validating={validating} onJump={jumpToField} />
              <FrontmatterEditor
                metadata={metadata}
                onChange={setMetadata}
                violations={displayViolations}
                pageSearch={pageSearch}
              />
            </>
          )}
          {activeMetaTab === 'knowledge' && (
            <KnowledgeGraphPanel pageName={name} />
          )}
        </Tabs>
      </section>

      <MathValidationSummary violations={mathViolations} onJump={jumpToMath} />

      <EditorToolbar onCommand={applyFormat} />

      <div className="editor-container">
        <div
          ref={dropContainerRef}
          className="editor-pane"
          onDragEnter={handleDragEnter}
          onDragLeave={handleDragLeave}
          onDragOver={handleDragOver}
          onDrop={handleDrop}
          style={{ position: 'relative' }}
        >
          {isDragging && (
            <div className="editor-dropzone-hint" aria-hidden="true">
              Drop images to upload
            </div>
          )}
          <CodeEditor
            ref={editorRef}
            data-testid="editor-textarea"
            className="editor-textarea"
            value={body}
            onChange={handleBodyChange}
            dark={dark}
            onBold={handleBold}
            onItalic={handleItalic}
            onLink={handleLink}
            getLinkCompletions={getPageNames}
            onViewChange={syncPreview}
          />
        </div>
        <div className="editor-pane editor-preview" ref={previewRef} onScroll={syncEditor}>
          <FrontmatterPreview content={fullText} />
          <article className="article-prose" onClick={handlePreviewClick}>
            <ReactMarkdown remarkPlugins={[
              remarkGfm,
              remarkMath,
              [remarkAttachments, { attachments: attachments.list, pageName: name }],
            ]} rehypePlugins={[rehypeKatex, rehypeSourceLine]}>
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

      {showDiscardConfirm && (
        <div className="modal-overlay" onClick={() => setShowDiscardConfirm(false)}>
          <div className="modal-content admin-modal" onClick={e => e.stopPropagation()}>
            <h3 style={{
              fontFamily: 'var(--font-display)',
              fontSize: '1.25rem',
              fontWeight: 600,
              marginBottom: 'var(--space-md)',
            }}>
              Discard unsaved changes?
            </h3>
            <p style={{
              fontFamily: 'var(--font-ui)',
              fontSize: '0.9rem',
              color: 'var(--text-secondary)',
              lineHeight: 1.6,
              marginBottom: 'var(--space-lg)',
            }}>
              You have unsaved changes. If you leave now they will be lost.
            </p>
            <div className="modal-actions">
              <button className="btn btn-ghost" onClick={() => setShowDiscardConfirm(false)}>
                Keep editing
              </button>
              <button className="btn btn-primary" onClick={() => {
                setShowDiscardConfirm(false);
                navigate(`/wiki/${name}`);
              }}>
                Discard
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
        editorContent={body}
      />
    </div>
  );
}
