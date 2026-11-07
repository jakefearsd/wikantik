import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import { useNavigationGuard, useGuardedNavigate } from '../navigation/NavigationGuardProvider';
import { isLeaving } from '../navigation/navigationGuard';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import remarkInlineMath from '../utils/remarkInlineMath';
import remarkCallouts from '../utils/remarkCallouts';
import remarkBlockIds, { rehypeBlockIds } from '../utils/remarkBlockIds';
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
import { remarkWikiMarkup } from '../utils/remarkWikiMarkup';
import { remarkMissingLinks } from '../utils/wikiLinkTargets';
import { useMissingPages } from '../hooks/useMissingPages';
import { remarkWikiLinks } from '../utils/remarkWikiLinks';
import { useWikiLinkResolution } from '../hooks/useWikiLinkResolution';
import { usePreviewSource } from '../hooks/usePreviewSource';
import EditorPreviewMarkdown from './editor/EditorPreviewMarkdown';
import { WikiEmbedElement } from './WikiEmbed';
import { useEmbedCacheLifecycle } from '../hooks/useEmbedCacheLifecycle';
import { useTagSuggestions } from '../hooks/useTagSuggestions';
import { useLowlight } from '../hooks/useLowlight';
import { rehypeRenderedFences } from '../utils/fences/rehypeRenderedFences';
import WikiFence from './WikiFence';
import { rehypeHighlightCode } from '../utils/codeHighlight';
import { headingsFromMarkdown } from '../utils/headings';
import { draftBlockLink } from '../utils/draftBlockLink';
import { revealEffects } from '../utils/markdownFold';
import { useAttachments } from '../hooks/useAttachments';
import { useAttachmentUpload } from '../hooks/useAttachmentUpload';
import { dragCarriesFiles, filesFromDrop } from '../utils/editorFileEvents';
import { useEditorDrop } from '../hooks/useEditorDrop';
import LinkPreviewCard from './LinkPreviewCard';
import { useLinkPreview } from '../hooks/useLinkPreview';
import { loadPreview, evictPreview } from '../hooks/usePagePreview';
import { wikiLinkTarget } from '../utils/wikiLinkTargets';
import { useDraft } from '../hooks/useDraft';
import { useAuth } from '../hooks/useAuth';
import { useToast } from '../hooks/useToast';
import { formatRelative } from '../utils/datetime';
import { formatKeys } from '../utils/keyHints';
import { toggleWrap, toggleLinePrefix, insertLink, insertTable, insertCodeBlock, setHeading, insertCallout, insertMathBlock, insertRule } from '../utils/markdownFormat';
import { useDarkMode } from '../hooks/useDarkMode';
import EditorToolbar from './EditorToolbar';
import CodeEditor, { minimalChange } from './CodeEditor';
import { attachmentLinkChanges, rewriteAttachmentLinks } from '../utils/attachmentLinks';
import EditorRail from './editor/EditorRail';
import EditorStatusBar from './editor/EditorStatusBar';
import UnlinkedMentionsPanel from './editor/UnlinkedMentionsPanel';
import { useUnlinkedMentions } from '../hooks/useUnlinkedMentions';
import { locatePhrase, linkMarkup } from '../utils/mentionLink';
import { createEditorCursorStore } from '../utils/editorCursorStore';
import { useRailOpen } from '../hooks/useRailOpen';
import { useEditorMode } from '../hooks/useEditorMode';
import { loadEmbedState } from '../utils/livePreview/embedSource';
import { useRunCommand } from '../commands/useCommands';
import { getCommands, registerCommands } from '../commands/registry';
import { buildEditorCommands } from '../utils/editorCommands';
import { createSlashSource } from '../utils/slashComplete';
import { createTagSource } from '../utils/tagComplete';
import AttachmentPanel from './AttachmentPanel';
import AccessRuleLocation from './AccessRuleLocation';
import ConfirmDialog from './ui/ConfirmDialog';
import Chip from './ui/Chip';
import LoginForm from './LoginForm';
import { EditorView } from '@codemirror/view';
import { getSyncedVersion, sendableUpdates } from '@codemirror/collab';
import { useCapabilities } from '../hooks/useCapabilities';
import { useCollabSession, describeMetaEdit } from '../collab/useCollabSession';
import { pageEditHref } from '../utils/pageUrl';
import PresenceBar from '../collab/PresenceBar';
import DraftBanner from '../collab/DraftBanner';
import PublishDialog from '../collab/PublishDialog';
import Modal from './ui/Modal';
import { DiffHtml } from './DiffViewer';
import '../styles/article.css';
import '../styles/admin.css';

/**
 * [[Page#^ completion: a page's first BLOCK_LIST_LIMIT blocks (the server's maximum) are fetched once and filtered
 * locally; when the server cut the listing there, each query goes to the server instead, BLOCK_QUERY_LIMIT rows.
 */
/**
 * Writes the text `pending` resolves to onto the clipboard: as a ClipboardItem of the pending text where the browser has
 * one (the write starts now, inside the user gesture, and the text follows), else with writeText once it is known.
 */
function writeClipboard(pending) {
  if (typeof ClipboardItem !== 'undefined' && navigator.clipboard?.write) {
    return navigator.clipboard.write([new ClipboardItem({ 'text/plain': pending.then((t) => new Blob([t], { type: 'text/plain' })) })]);
  }
  return pending.then((t) => navigator.clipboard.writeText(t));
}
const BLOCK_LIST_LIMIT = 100;
const BLOCK_QUERY_LIMIT = 20;

/** Join outcomes after which the page is edited single-author (feature off, derived page, session full, join failed). */
const COLLAB_FALLBACK = new Set(['disabled', 'unavailable', 'full', 'error']);
/** Statuses in which a joined collab editor is mounted ('forbidden' with a joined editor: evicted, read-only). */
const COLLAB_EDITING = new Set(['live', 'reconnecting', 'resync', 'frozen', 'auth-required', 'forbidden']);
const COLLAB_STATUS_LABEL = {
  joining: 'Joining…', live: 'Live', reconnecting: 'Reconnecting…', resync: 'Out of sync', frozen: 'Read-only',
  'auth-required': 'Signed out', forbidden: 'Read-only',
};
const editorsLabel = (n) => `${n} editor${n === 1 ? '' : 's'}`;
/** "for all 3 editors", or "for everyone" when I am alone (a count of one reads oddly). */
const forEditors = (n) => (n > 1 ? `all ${editorsLabel(n)}` : 'everyone');
/** A stable small number per joined session (its extensions object): the collab editor's remount key (A4). */
const sessionKeys = new WeakMap();
let nextSessionKey = 1;
function sessionKeyOf(extensions) {
  if (!sessionKeys.has(extensions)) sessionKeys.set(extensions, nextSessionKey++);
  return sessionKeys.get(extensions);
}
/**
 * Where a queued insert goes once the editor is remounted (its offset is stale: the remounted document lacks the user's
 * unsynced text and has everyone else's): next to the text that surrounded it when it was queued, trying the full
 * context, then the text after it, then the text before it, with long and then short anchors. An anchor counts only
 * where it occurs exactly once. With no anchor found it goes at the end of the document — never into someone else's
 * text at a stale offset; with only anchors that occur more than once it is not guessed (null: the caller hands the
 * text back to the user).
 */
const ANCHOR_LENGTHS = [40, 12];
function anchoredInsertPos(text, { before = '', after = '' }) {
  let ambiguous = false;
  /** The one place `probe` occurs, or -1 (absent, or present more than once). */
  const onlyAt = (probe) => {
    const at = text.indexOf(probe);
    if (at < 0) return -1;
    if (text.indexOf(probe, at + 1) >= 0) { ambiguous = true; return -1; }
    return at;
  };
  for (const n of ANCHOR_LENGTHS) {
    const b = before.slice(-n);
    const a = after.slice(0, n);
    if (b && a) {
      const at = onlyAt(b + a);
      if (at >= 0) return at + b.length;
    }
    if (a) {
      const at = onlyAt(a);
      if (at >= 0) return at;
    }
    if (b) {
      const at = onlyAt(b);
      if (at >= 0) return at + b.length;
    }
  }
  return ambiguous ? null : text.length;
}
/** Offline (localStorage) drafts already offered in this browser tab: each stored draft is offered once (§8.2). */
const offeredOfflineDrafts = new Set();
const offlineDraftKey = (login, page, savedAt) => `${login}\u0000${page}\u0000${savedAt}`;
const LEAVE_NOTE = 'Your changes stay in the shared draft until someone publishes or discards them';
/** Handed-back insert texts with `find` replaced (first match each); a text left empty (a failed upload) drops out. */
const replaceInUnplaced = (list, find, replacement) =>
  list.map((t) => (t.includes(find) ? t.replace(find, () => replacement) : t)).filter(Boolean);
/** Local work the server never got (unsynced text, field edits), as one text to copy. */
const hasLocalWork = (w) => !!w && (!!w.unsyncedText || w.metaEdits?.length > 0);
function localWorkText(w) {
  return [w.unsyncedText, ...(w.metaEdits ?? []).map(describeMetaEdit)].filter(Boolean).join('\n');
}
function publishFailureMessage(res) {
  if (res.reason === 'not-live') return 'Not connected to the shared draft — your edits are kept; publish again once it reconnects.';
  if (res.reason === 'publish-in-progress') return 'Someone else is publishing this page — try again in a moment.';
  if (res.reason === 'retries-exhausted') return 'The page kept changing while publishing — try again.';
  if (res.reason === 'guarded') {
    return 'This draft held restricted content from a deleted page; an administrator must publish it.';
  }
  if (res.reason === 'no-create') {
    return 'This page was deleted, and recreating it needs permission to create pages, which you don\u2019t have. Copy your text to keep it.';
  }
  return res.message || 'Publish failed';
}

/**
 * The page editor. `collabTransport` (tests only) replaces the collaboration HTTP transport; collaborative mode itself
 * is on when /api/capabilities reports `collab.enabled` and the join succeeds (docs/MultiEdit.MD §8.2).
 */
export default function PageEditor({ collabTransport } = {}) {
  const { name } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToast();
  const tagSuggestions = useTagSuggestions();
  // The frontmatter object and the markdown BODY are the two canonical pieces; CodeMirror edits the
  // body only, the structured FrontmatterEditor edits the object. Full text is derived where needed
  // (draft autosave, dirty baseline, frontmatter preview, clipboard).
  const [body, setBody] = useState('');
  const [metadata, setMetadata] = useState({});
  const [violations, setViolations] = useState([]);
  // A 422 access_rule_shape (#104): each refused rule, placed by body line or frontmatter field.
  const [accessRuleViolations, setAccessRuleViolations] = useState([]);
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
  const [activeMetaTab, setActiveMetaTab] = useState('frontmatter');
  const [isDragging, setIsDragging] = useState(false);
  const editorRef = useRef(null);
  const [cursorStore] = useState(createEditorCursorStore);
  const [railOpen, toggleRail] = useRailOpen();
  const { user } = useAuth();
  // Collaborative mode needs an explicit `collab.enabled: true` (absent = off, unlike the fail-open capabilities). The
  // load waits for /api/capabilities so the mode is known before the first keystroke.
  const { capabilities, loading: capabilitiesLoading } = useCapabilities();
  const collabWanted = !capabilitiesLoading && capabilities?.collab?.enabled === true;
  // Legacy JSPWiki syntax is not Markdown: never live-render it as such (and never persist a mode choice from it).
  const isLegacyWiki = markupSyntax === 'wiki' || markupSyntax === 'likely-wiki';
  const [editorMode, toggleEditorMode] = useEditorMode({
    authenticated: user?.authenticated === true,
    persist: !isLegacyWiki,
  });
  const liveActive = editorMode === 'live' && !isLegacyWiki;
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
  // A refused save/publish's violations hold only while their field keeps the value that was refused: a change by any
  // route (a widget, Raw YAML, another editor) hands the field back to live validation.
  const [refusedMeta, setRefusedMeta] = useState(null);
  const displayViolations = useMemo(() => {
    const top = (v) => String(v.field || '').split('.')[0];
    const same = (f) => JSON.stringify(metadata?.[f] ?? null) === JSON.stringify(refusedMeta?.[f] ?? null);
    const refused = refusedMeta ? violations.filter((v) => !top(v) || same(top(v))) : violations;
    const seen = new Set(liveViolations.map((v) => `${v.field}|${v.code}`));
    return [...liveViolations, ...refused.filter((v) => !seen.has(`${v.field}|${v.code}`))];
  }, [liveViolations, violations, metadata, refusedMeta]);
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

  const login = user?.authenticated ? user.loginPrincipal : null;
  // In collab mode the server holds the draft: the autosave effect below stays off, and an existing offline draft is
  // offered once for import into the shared draft (§8.2).
  const { draft, saveDraft, clearDraft } = useDraft({
    login,
    pageId: name,
    enabled: !!login,
  });
  const [restorePrompt, setRestorePrompt] = useState(false);
  const [restoring, setRestoring] = useState(null);
  // The dirty-check baseline (reconstructed text as loaded from the server).
  // State, not a ref, since it's read during render (isDirty below).
  const [loadedContent, setLoadedContent] = useState(null);

  // Full reconstructed text (frontmatter + body) — derived, used for the draft, the dirty baseline,
  // the frontmatter preview card, and clipboard copy on conflict.
  const fullText = useMemo(() => reconstructContent(metadata, body), [metadata, body]);

  // #20 — isDirty: true only once the page has loaded and the reconstructed text differs from baseline.
  const isDirty = loaded && fullText !== loadedContent;

  // ── Collaborative mode (docs/MultiEdit.MD §0 A4/A10/A11/A13, §8.2) ──
  // What a NEW session starts from (A11): a new page's template, a restored version. Null = the page as saved.
  const [collabSeed, setCollabSeed] = useState(null);
  const [loadedFor, setLoadedFor] = useState(null);
  // A page renamed under the session (`page-renamed`): the editor follows the new name on the same session, without
  // reloading the page (the load effect below skips it) and without leaving the session.
  const [collabKeptFor, setCollabKeptFor] = useState(null);
  // Frontmatter is co-edited (A14): blocking errors only disable Publish, and a refused publish stays in collab mode.
  const collab = useCollabSession(name, {
    transport: collabTransport,
    initialText: collabSeed?.name === name ? collabSeed.initialText : undefined,
    initialMeta: collabSeed?.name === name ? collabSeed.initialMeta : undefined,
    enabled: collabWanted && (loadedFor === name || collabKeptFor === name),
  });
  const collabMode = collabWanted && !COLLAB_FALLBACK.has(collab.status);
  const collabEditing = collabMode && !!collab.extensions && COLLAB_EDITING.has(collab.status);
  // a join refused for lack of edit permission (no editor joined); an eviction keeps the joined editor, read-only
  const collabForbidden = collabMode && collab.status === 'forbidden' && !collab.extensions;
  const collabEvicted = collabEditing && collab.status === 'forbidden';
  const collabFrozen = collabEditing && collab.status === 'frozen';
  const collabReadOnly = collabEditing && (collabFrozen || collab.status === 'resync' || collabEvicted);
  // me plus everyone in the live presence map
  const editorCount = 1 + Object.keys(collab.presence ?? {}).length;
  const collabMeta = collabEditing ? collab.meta : null;
  const renamedTo = collabMode ? collab.renamedTo : null;
  if (renamedTo && renamedTo !== name && collabKeptFor !== renamedTo) setCollabKeptFor(renamedTo);
  useEffect(() => {
    if (renamedTo && renamedTo !== name) navigate(pageEditHref(renamedTo), { replace: true, state: { collabKeep: true } });
  }, [renamedTo, name, navigate]);
  // Unsynced local edits (sendableUpdates non-empty): the only collab work that leaving would lose.
  const [unsynced, setUnsynced] = useState(false);
  // The editor's synced version: the publish dialog counts the edits that arrived after its summary was taken.
  const [syncedVersion, setSyncedVersion] = useState(null);
  const unsyncedListener = useMemo(() => EditorView.updateListener.of((u) => {
    if (!u.transactions.length) return;
    setUnsynced(sendableUpdates(u.state).length > 0);
    setSyncedVersion(getSyncedVersion(u.state));
  }), []);
  const collabExtensions = useMemo(
    () => (collab.extensions ? [collab.extensions, unsyncedListener] : null),
    [collab.extensions, unsyncedListener],
  );
  // A (re)join: the editor remounts from the joined document; the body and metadata follow the session.
  const [restoreOffer, setRestoreOffer] = useState(null);
  const [offlineDraftOffer, setOfflineDraftOffer] = useState(null);
  const [joinedExtensions, setJoinedExtensions] = useState(null);
  // The draft banner (D3, §8.3): shown on open when the join reported unpublished changes, until dismissed or published.
  const [draftBannerOpen, setDraftBannerOpen] = useState(false);
  if (collabMode && collab.extensions && collab.extensions !== joinedExtensions) {
    setJoinedExtensions(collab.extensions);
    const joinedDoc = String(collab.docAtJoin ?? '');
    setBody(joinedDoc);
    setUnsynced(false);
    setSyncedVersion(collab.startVersion ?? null);
    if (!joinedExtensions) setDraftBannerOpen(!!collab.draft?.unpublished);
    // A restore into a session that already existed (others are editing): offer it instead of clobbering their work.
    const seedText = collabSeed?.name === name && collabSeed.restoreFrom ? String(collabSeed.initialText ?? '').replace(/\r\n?/g, '\n') : null;
    // (the server may end a session's text in a newline (A9): that alone does not make it someone else's session)
    const differs = seedText != null && seedText.replace(/\n+$/, '') !== joinedDoc.replace(/\n+$/, '');
    setRestoreOffer(differs && !joinedExtensions ? { text: seedText, from: collabSeed.restoreFrom } : null);
    // An offline (localStorage) draft of this page, offered once per stored draft on entering collab mode (§8.2).
    const offlineKey = draft?.content ? offlineDraftKey(login, name, draft.savedAt) : null;
    if (!joinedExtensions && offlineKey && !offeredOfflineDrafts.has(offlineKey)) {
      const offline = stripFrontmatter(draft.content).replace(/\r\n?/g, '\n');
      setOfflineDraftOffer(offline !== joinedDoc ? { text: offline, savedAt: draft.savedAt, key: offlineKey } : null);
    }
  }
  // remembered once shown (not during render: a discarded render must not use up the offer)
  useEffect(() => {
    if (offlineDraftOffer?.key) offeredOfflineDrafts.add(offlineDraftOffer.key);
  }, [offlineDraftOffer]);
  const collabDraft = collabMode ? collab.draft : null;
  if (draftBannerOpen && collabDraft && !collabDraft.unpublished) setDraftBannerOpen(false);
  // In collab mode the form shows the co-edited metadata; the metadata state follows it (validation, preview, a fallback).
  const metaView = collabMeta ? collabMeta.view() : null;
  if (metaView && metaView !== metadata) setMetadata(metaView);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [confirmOfflineDiscard, setConfirmOfflineDiscard] = useState(false);
  const [confirmConvert, setConfirmConvert] = useState(false);
  // The publish dialog (§8.3): { mode: 'publish' | 'view', summary (null while loading), unsynced: display names }
  const [publishDialog, setPublishDialog] = useState(null);
  const publishDialogRef = useRef(null);
  useEffect(() => { publishDialogRef.current = publishDialog; });
  // Queued inserts whose place in the remounted document is ambiguous: handed back to the user, never guessed.
  const [unplacedInserts, setUnplacedInserts] = useState([]);
  // the ref is the source of truth, kept in step synchronously: a replace arriving before the next render must see it
  const unplacedRef = useRef(unplacedInserts);
  const updateUnplaced = useCallback((next, { defer = false } = {}) => {
    unplacedRef.current = next;
    if (defer) queueMicrotask(() => setUnplacedInserts(next)); else setUnplacedInserts(next);
  }, []);
  // Recreate with local work the publish cannot carry: { unsyncedText, metaEdits, asking }
  const [recreateCheck, setRecreateCheck] = useState(null);
  const [loginOpen, setLoginOpen] = useState(false);
  // An outside save merged into the session (§7.4): a notice with the diff of that page version, and a toast.
  const externalChange = collabMode ? collab.externalChange : null;
  const [externalDismissed, setExternalDismissed] = useState(null);
  const [diffFor, setDiffFor] = useState(null); // { pageVersion, html, error }
  useEffect(() => {
    if (externalChange) toast.info(`Updated by ${externalChange.source || 'an outside change'}`);
  }, [externalChange, toast]);
  const showExternal = externalChange && externalChange !== externalDismissed ? externalChange : null;

  // Draft notices (Phase 3). Toasts are about someone else's action (my own has its own feedback); the page state a
  // publish changed (its version, its markup syntax) follows every publish, my own other tab's included.
  const selfName = collab.self?.displayName ?? null;
  const published = collabMode ? collab.published : null;
  const notice = collabMode ? collab.notice : null;
  const [seenPublished, setSeenPublished] = useState(null);
  if (published !== seenPublished) {
    setSeenPublished(published);
    // the page has a newer version now: a later single-author save must expect it
    if (published?.pageVersion != null) setOriginalVersion(published.pageVersion);
  }
  // R12: converted for everyone but not yet published as Markdown (the draft is never reverted); the retry carries the flag
  const [convertPending, setConvertPending] = useState(false);
  const convertPendingRef = useRef(false);
  useEffect(() => { convertPendingRef.current = convertPending; });
  // the page's markup syntax could not be re-read after a publish (tried twice): a later publish may state it wrongly
  const [markupStale, setMarkupStale] = useState(false);
  const refreshMarkup = useCallback(() => {
    const read = () => api.getPage(name);
    return read()
      .catch((err) => {
        console.warn('[collab] could not refresh the page after a publish; retrying once', err?.message || err);
        return read();
      })
      .then((page) => {
        const syntax = page?.markupSyntax || 'markdown';
        if (syntax === 'markdown') setConvertPending(false);
        // a converted, unpublished draft keeps its Markdown flag until it is published as such
        if (syntax === 'markdown' || !convertPendingRef.current) setMarkupSyntax(syntax);
        setMarkupStale(false);
      })
      .catch((err) => {
        console.warn('[collab] could not refresh the page after a publish', err?.message || err);
        setMarkupStale(true);
      });
  }, [name]);
  const announcedRef = useRef({ published: null, notice: null });
  useEffect(() => {
    if (!published || announcedRef.current.published === published) return;
    announcedRef.current.published = published;
    // the publish may have changed the page's markup syntax (Convert and publish), from any tab
    refreshMarkup();
    if (published.by === selfName) return;
    toast.info(published.pageVersion != null
      ? `Published by ${published.by} as version ${published.pageVersion}` : `Published by ${published.by}`);
  }, [published, selfName, refreshMarkup, toast]);
  useEffect(() => {
    if (!notice || announcedRef.current.notice === notice) return;
    announcedRef.current.notice = notice;
    if (notice.by && notice.by === selfName) return;
    if (notice.type === 'publish-failed') toast.error(`${notice.by || 'Someone'}'s publish was refused`);
    else if (notice.type === 'draft-discarded') toast.info(`${notice.by || 'Someone'} discarded the shared draft`);
  }, [notice, selfName, toast]);
  // E25: the server reported the draft over the page size limit; the warning follows the local document's size
  const oversizeLimit = collabMode ? collab.oversizeLimit : null;
  const bodyBytes = useMemo(() => (oversizeLimit != null ? new TextEncoder().encode(body).length : 0), [body, oversizeLimit]);
  const oversize = collabEditing && oversizeLimit != null && bodyBytes > oversizeLimit;
  const openExternalDiff = (ev) => {
    const to = ev.pageVersion;
    setDiffFor({ pageVersion: to, html: null, error: null });
    api.getDiff(name, to - 1, to)
      .then((d) => setDiffFor((cur) => (cur?.pageVersion === to ? { ...cur, html: d?.diff || '' } : cur)))
      .catch((err) => {
        console.warn('[collab] could not load the diff of the outside change', to, err?.message || err);
        setDiffFor((cur) => (cur?.pageVersion === to ? { ...cur, error: err?.message || 'Could not load the diff' } : cur));
      });
  };
  // Programmatic writes (attachment rename, upload placeholders, drops) cannot reach a collab editor that is joining or
  // stopped (resync, frozen): they are queued and applied once a writable editor is mounted, never dropped silently.
  const collabWriteBlocked = collabMode && !(collabEditing && !collabReadOnly);
  const collabModeRef = useRef(collabMode);
  const collabWriteBlockedRef = useRef(collabWriteBlocked);
  const collabFrozenRef = useRef(false);
  useEffect(() => {
    collabModeRef.current = collabMode;
    collabWriteBlockedRef.current = collabWriteBlocked;
    collabFrozenRef.current = collabMode && collab.status === 'frozen';
  });
  const pendingWritesRef = useRef([]);
  // Fallback body writer for edits made without a view: never in collab mode, where the uncontrolled editor would
  // diverge from React state (A4).
  const fallbackSetBody = useCallback((update) => {
    if (collabModeRef.current) {
      console.warn('[collab] body update skipped: the collab editor view is not mounted');
      return;
    }
    setBody(update);
  }, []);

  const guardActive = collabMode
    ? (collabEditing && (unsynced || !!collabMeta?.pending)) || !!collab.resync?.unsyncedText || unplacedInserts.length > 0
    : isDirty || (collabWanted && !!collab.resync?.unsyncedText);
  useNavigationGuard(guardActive);
  const guardedNavigate = useGuardedNavigate();

  // §8.2: leaving with synced, unpublished work of mine is never blocked (only unsynced typing is); a note says where
  // that work stays. Not after publishing it, and not for the navigation that follows a rename of the page.
  const draftHasMine = !!collabDraft?.unpublished
    && (!selfName || !collabDraft.contributors?.length || collabDraft.contributors.includes(selfName));
  const leftPublishedRef = useRef(false);
  const leaveNoteRef = useRef(false);
  const toastRef = useRef(toast);
  useEffect(() => {
    toastRef.current = toast;
    leaveNoteRef.current = collabEditing && (collab.status === 'live' || collab.status === 'reconnecting')
      && draftHasMine && !guardActive && !(renamedTo && renamedTo !== name);
  });
  useEffect(() => {
    const note = leaveNoteRef;
    const published = leftPublishedRef;
    const notify = toastRef;
    published.current = false;
    return () => {
      if (note.current && !published.current) notify.current.info(LEAVE_NOTE);
    };
  }, [name]);

  // Keep the body in a ref so the stable scroll-sync / format callbacks read current text.
  // Written in an effect (runs after every render) rather than during render,
  // since refs may not be written synchronously in the render body.
  const bodyRef = useRef(body);
  useEffect(() => {
    bodyRef.current = body;
  });
  // The metadata the body is saved behind: the server's draft reading of the body needs it (a body behind frontmatter
  // reads a leading `---` pair as content; without any, the body is the whole page, as a save without metadata stores it).
  const metadataRef = useRef(metadata);
  useEffect(() => {
    metadataRef.current = metadata;
  });

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

  const handleViewChange = useCallback(() => {
    const editor = editorRef.current;
    const vp = editor?.getViewport?.();
    const cursor = editor?.getCursor?.();
    cursorStore.set({ ...(cursor || {}), ...(vp ? { topLine: vp.topLine } : {}) });
    syncPreview();
  }, [cursorStore, syncPreview]);
  const jumpToHeading = useCallback((line) => editorRef.current?.jumpToLineAligned?.(line, 0), []);

  const [ignoredMentions, setIgnoredMentions] = useState(() => new Set());
  // Linked targets stay hidden only until the next scan result (identity of `mentions` changes), so an
  // Undo of the link makes the row reappear; Ignore is session-long.
  const [justLinked, setJustLinked] = useState({ forScan: null, targets: new Set() });
  const mentionScan = useUnlinkedMentions({ page: name, text: body, enabled: railOpen && loaded });
  const rescanMentions = mentionScan.rescan;
  const linkedNow = justLinked.forScan === mentionScan.mentions ? justLinked.targets : null;
  const visibleMentions = mentionScan.mentions.filter((m) => !ignoredMentions.has(m.target) && !linkedNow?.has(m.target));
  const ignoreMention = useCallback((m) => setIgnoredMentions((s) => new Set(s).add(m.target)), []);
  const currentScan = mentionScan.mentions;

  // Server offsets index the LF-only editor document, so locate against its live text.
  const linkMention = useCallback((m) => {
    const text = editorRef.current?.getText?.() ?? bodyRef.current;
    const loc = locatePhrase(text, m);
    if (!loc) {
      toast.info('Text changed — rescanned');
      rescanMentions();
      return;
    }
    const markup = linkMarkup(text.slice(loc.from, loc.to), m.target);
    if (!editorRef.current?.replaceRange?.(loc.from, loc.to, markup)) {
      fallbackSetBody((prev) => prev.slice(0, loc.from) + markup + prev.slice(loc.to));
    }
    setJustLinked((prev) => {
      const forScan = currentScan;
      const targets = new Set(prev.forScan === forScan ? prev.targets : []);
      return { forScan, targets: targets.add(m.target) };
    });
  }, [toast, rescanMentions, currentScan, fallbackSetBody]);

  const jumpToMention = useCallback((m) => {
    const text = editorRef.current?.getText?.() ?? bodyRef.current;
    const loc = locatePhrase(text, m) || m;
    editorRef.current?.setSelection(loc.from, loc.to);
    editorRef.current?.scrollToLine(m.line);
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
      editor.scrollToLine(previewFractionToLine(fraction, total, fm), { reveal: false });
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

  // Live link completion: server-ranked, ACL-filtered page search; heading lists per target page are
  // fetched once per editing session (the page being edited uses the live draft).
  const headingCacheRef = useRef(new Map());
  const blockCacheRef = useRef(new Map());
  const attachmentNamesRef = useRef([]);
  useEffect(() => {
    attachmentNamesRef.current = (attachments.list || []).map((a) => a.fileName);
  });
  const linkCompletion = useMemo(() => ({
    searchPages: (q) => api.listPages({ q, limit: 20 }).then((d) => (d.pages || []).map((p) => p.name)),
    getHeadings: (page) => {
      if (!page) return Promise.resolve(headingsFromMarkdown(bodyRef.current));
      const cache = headingCacheRef.current;
      if (!cache.has(page)) {
        cache.set(page, api.getPage(page)
          .then((p) => headingsFromMarkdown(p.content || ''))
          .catch((err) => {
            // A page that doesn't exist has no headings — remember that; evict any other failure to retry.
            if (err?.status === 404) return [];
            cache.delete(page);
            throw err;
          }));
      }
      return cache.get(page);
    },
    getAttachmentNames: () => attachmentNamesRef.current,
    // [[Page#^ completion. Block identity is the server's, for this page too: this page's blocks are the server's
    // reading of the live text (the editor's, which bodyRef lags by a render — POST /blocks/draft, never cached, the
    // buffer changes), and an id is added by the edit the server answers (draftLink); another page's come from its
    // saved text, cached per page for the session, and are tagged by the server's index/version.
    getBlocks: (page, q) => {
      if (!page || page === name) {
        const text = editorRef.current?.getText?.() ?? bodyRef.current;
        const metadata = metadataRef.current;
        return (q ? api.draftBlocks(name, { text, metadata, q, limit: BLOCK_QUERY_LIMIT }) : api.draftBlocks(name, { text, metadata, limit: BLOCK_LIST_LIMIT }))
          .then((l) => ({ local: true, canAssign: true, complete: q ? true : (l.blocks || []).length < BLOCK_LIST_LIMIT, text, blocks: l.blocks || [] }));
      }
      // Per page: '' → the first BLOCK_LIST_LIMIT blocks (complete when the server returned fewer), q → the server's
      // own search for q (it matches whole block text). The completion asks with q only when the first is incomplete.
      const cache = blockCacheRef.current;
      if (!cache.has(page)) cache.set(page, new Map());
      const perPage = cache.get(page);
      const key = q || '';
      if (!perPage.has(key)) {
        const request = q
          ? api.getPageBlocks(page, { q, limit: BLOCK_QUERY_LIMIT })
          : api.getPageBlocks(page, { limit: BLOCK_LIST_LIMIT })
            .then((l) => ({ ...l, complete: (l.blocks || []).length < BLOCK_LIST_LIMIT }));
        perPage.set(key, request.catch((err) => {
          // A page that doesn't exist (or can't be seen) has no blocks — remember that; evict anything else.
          if (err?.status === 404) return { version: 0, canAssign: false, blocks: [], complete: true };
          perPage.delete(key);
          throw err;
        }));
      }
      return perPage.get(key);
    },
    // The page has a new version after an assign (or a stale listing after a refusal): drop every listing of it.
    draftLink: (text, offset) => api.draftBlocks(name, { text, metadata: metadataRef.current, offset }),
    assignBlock: (page, version, index) => api.assignBlockId(page, { version, index })
      .finally(() => blockCacheRef.current.delete(page)),
    notify: (message) => toast.error(message),
  }), [name, toast]);

  // Search-backed option source for the `related` field's page picker.
  const pageSearch = useCallback((q) =>
    Promise.resolve()
      .then(() => (api.search ? api.search(q) : { results: [] }))
      .then((r) => (r.results || []).map((x) => x.name)), []);

  // Text edits made outside typing go through the editor as transactions: a React `value` change can be
  // deferred by @uiw/react-codemirror while the user types, and the editor's onChange would then overwrite it.
  // insertText/replaceText return false when there is no editor view; callers then fall back to setBody.
  // In collab mode while the editor cannot take writes, they are queued (and reported as handled).
  const queueCollabWrite = useCallback((op) => {
    if (collabFrozenRef.current) {
      console.warn('[collab] write refused: the page is read-only', op.kind);
      toast.error('This page is read-only now — the change to its text was not applied');
      return;
    }
    pendingWritesRef.current.push(op);
  }, [toast]);
  const editorEdits = useMemo(() => ({
    insertText: (pos, text) => {
      if (collabWriteBlockedRef.current) {
        // remember the text around the insert point: the offset means nothing in the remounted document
        const around = editorRef.current?.getText?.() ?? bodyRef.current ?? '';
        const at = Math.max(0, Math.min(pos, around.length));
        queueCollabWrite({ kind: 'insert', before: around.slice(Math.max(0, at - 40), at), after: around.slice(at, at + 40), text });
        return true;
      }
      return editorRef.current?.insertText?.(pos, text) ?? false;
    },
    replaceText: (find, replacement) => {
      if (collabWriteBlockedRef.current) { queueCollabWrite({ kind: 'replace', find, replacement }); return true; }
      // a finished upload whose placeholder was handed back (not placed): its link follows it into the recovery panel
      const live = editorRef.current?.getText?.();
      if (live != null && !live.includes(find) && unplacedRef.current.some((t) => t.includes(find))) {
        updateUnplaced(replaceInUnplaced(unplacedRef.current, find, replacement));
        return true;
      }
      return editorRef.current?.replaceText?.(find, replacement) ?? false;
    },
  }), [queueCollabWrite, updateUnplaced]);

  // Replace the whole body while the editor is open (conversion, draft restore, conflict reload): through the view
  // as one undoable transaction touching only the changed span, so the caret is mapped and nothing is parked behind
  // react-codemirror's typing latch to be replayed over later keystrokes. setBody only when there is no view.
  // Incoming text is normalised to LF first: the server stores CRLF, the editor always holds LF, and text that
  // differs only in line endings must not count as a change.
  const replaceBody = useCallback((raw) => {
    const text = String(raw ?? '').replace(/\r\n?/g, '\n');
    const editor = editorRef.current;
    const live = editor?.getText?.();
    if (live === text) return;
    if (live != null && editor.applyChanges?.([minimalChange(live, text)])) return;
    fallbackSetBody(text);
  }, [fallbackSetBody]);

  // Queued collab writes (see queueCollabWrite) go in once the editor takes writes again, each as one transaction.
  const applyPendingWrites = useCallback(() => {
    const ops = pendingWritesRef.current;
    const editor = editorRef.current;
    if (!ops.length || collabWriteBlockedRef.current || editor?.getText?.() == null) return;
    pendingWritesRef.current = [];
    let unplaced = unplacedRef.current;
    for (const op of ops) {
      if (op.kind === 'rename') editor.applyChanges(attachmentLinkChanges(editor.getText(), op.oldName, op.newName));
      else if (op.kind === 'replace') {
        // a placeholder handed back below (an upload that finished while out of sync) gets its final link there
        if (!editor.getText().includes(op.find) && unplaced.some((t) => t.includes(op.find))) {
          unplaced = replaceInUnplaced(unplaced, op.find, op.replacement);
        } else editor.replaceText(op.find, op.replacement);
      } else if (op.kind === 'insert') {
        const at = anchoredInsertPos(editor.getText(), op);
        if (at === null) unplaced = [...unplaced, op.text]; else editor.insertText(at, op.text);
      }
    }
    // (state outside the caller's effect: the panel is news for the next render, not a cascade of this one)
    if (unplaced !== unplacedRef.current) updateUnplaced(unplaced, { defer: true });
  }, [updateUnplaced]);
  // A fresh view: its mount runs inside react-codemirror's layout effect, before this component's refs are updated.
  const handleEditorReady = useCallback(() => { setTimeout(applyPendingWrites, 0); }, [applyPendingWrites]);
  useEffect(() => {
    if (!collabWriteBlocked) applyPendingWrites();
  }, [collabWriteBlocked, applyPendingWrites]);

  const handleInsert = useCallback((text, pos) => {
    if (editorEdits.insertText(pos, text)) return;
    fallbackSetBody(prev => prev.slice(0, pos) + text + prev.slice(pos));
  }, [editorEdits, fallbackSetBody]);

  const getDropOffset = useCallback(() => {
    return editorRef.current ? editorRef.current.getSelection().selStart : 0;
  }, []);

  useEditorDrop(dropContainerRef, handleInsert, getDropOffset);

  // The editor holds the body only, so the preview renders it directly (no frontmatter to strip).
  const previewContent = useMemo(() => stripFrontmatter(body), [body]);
  const missingPages = useMissingPages(previewContent);
  const wikiLinkResolution = useWikiLinkResolution(previewContent);
  const previewComponents = useMemo(() => ({ 'wiki-embed': WikiEmbedElement, 'wiki-fence': WikiFence }), []);
  const lowlight = useLowlight(/(^|\n)(```|~~~)/.test(previewContent));
  const [previewSource, previewCost] = usePreviewSource(previewContent, name);

  const handleRename = useCallback(async (oldName, newName) => {
    const result = await attachments.renameAttachment(oldName, newName);
    if (collabWriteBlockedRef.current) {
      queueCollabWrite({ kind: 'rename', oldName, newName });
      return result;
    }
    // This resolves at an arbitrary moment, typically while the user types. Rewrite the links in the LIVE editor
    // text as one transaction (the caret is mapped through it); a setBody here would be parked by the typing
    // latch and later replayed stale over the keystrokes typed in between.
    const editor = editorRef.current;
    const live = editor?.getText?.();
    if (live != null && editor.applyChanges?.(attachmentLinkChanges(live, oldName, newName))) return result;
    fallbackSetBody((prev) => rewriteAttachmentLinks(prev, oldName, newName));
    return result;
  }, [attachments, fallbackSetBody, queueCollabWrite]);

  // Captured once per page: the route state is cleared after it is applied, so Back / reload to
  // /edit/P does not re-apply the restore (and clearing it must not re-trigger the load effect).
  const [restoreFor, setRestoreFor] = useState({ name, version: location.state?.restoreVersion });
  let restoreVersion = restoreFor.version;
  if (restoreFor.name !== name) {
    restoreVersion = location.state?.restoreVersion;
    setRestoreFor({ name, version: restoreVersion });
  }
  const pathname = location.pathname;

  // (only for the rename this editor followed: history state outlives a reload, the component state does not)
  const collabKeep = location.state?.collabKeep === true && collabKeptFor === name;
  useEffect(() => {
    if (capabilitiesLoading) return;
    // the page was renamed under the live session: same editor, same session, nothing to reload
    if (collabKeep) return;
    api.getPage(name).then(async (page) => {
      const meta = page.metadata || {};
      const pageBody = page.content || '';
      const full = reconstructContent(meta, pageBody);
      setLoadedContent(full); // dirty baseline = current server content
      setOriginalVersion(page.version); // expectedVersion stays the CURRENT version
      setMarkupSyntax(page.markupSyntax || 'markdown');
      setIsNew(false);
      if (restoreVersion) {
        try {
          const old = await api.getPage(name, { version: restoreVersion });
          setMetadata(old.metadata || {});
          // Load-time setBody calls (here, below and the 404 bootstrap) may bypass the editor: on first load they run
          // before it mounts (`loaded` is false), and on a switch to another page the user has already left this one
          // (a dirty editor must pass the navigation guard first), so no keystrokes are in flight for them to race.
          setBody(old.content || '');
          setChangeNote(`Restored version ${restoreVersion}`);
          setRestoring({ from: restoreVersion, current: page.version });
          setCollabSeed({ name, initialText: old.content || '', initialMeta: old.metadata || {}, restoreFrom: restoreVersion });
          navigate(pathname, { replace: true, state: {} });
          return;
        } catch (err) {
          console.warn('[editor] could not load version for restore', restoreVersion, err?.message || err);
          setError(`Could not load version ${restoreVersion}: ${err?.message || 'request failed'}`);
        }
      }
      setMetadata(meta);
      setBody(pageBody);
      setCollabSeed(null);
      if (!collabWanted && draft && draft.content && draft.content !== full) {
        setRestorePrompt(true);
      }
    }).catch(err => {
      if (err.status === 404) {
        const initialBody = location.state?.initialContent || `# ${name}\n\nWrite your content here.`;
        const initialMeta = location.state?.initialMetadata || {};
        setBody(initialBody);
        setMetadata(initialMeta);
        const full = reconstructContent(initialMeta, initialBody);
        setLoadedContent(full);
        setCollabSeed({ name, initialText: initialBody, initialMeta });
        if (!collabWanted && draft && draft.content && draft.content !== full) {
          setRestorePrompt(true);
        }
        setIsNew(true);
      } else {
        setError(err.message);
      }
    }).finally(() => {
      setLoaded(true);
      setLoadedFor(name);
    });
  }, [name, draft, restoreVersion, pathname, navigate, location.state?.initialContent, location.state?.initialMetadata, capabilitiesLoading, collabWanted, collabKeep]);

  // Debounced autosave — fires 800 ms after the user stops typing.
  useEffect(() => {
    if (!login || collabWanted) return;
    if (loadedContent === null) return;
    const id = setTimeout(() => {
      if (fullText === loadedContent) {
        clearDraft();
      } else {
        saveDraft({ content: fullText, title: name });
      }
    }, 800);
    return () => clearTimeout(id);
  }, [fullText, name, login, saveDraft, clearDraft, loadedContent, collabWanted]);

  useEffect(() => {
    document.title = `Wikantik: ${isNew ? 'Create' : 'Edit'} ${name}`;
  }, [name, isNew]);

  useEffect(() => {
    const handler = (e) => {
      if (isLeaving()) return; // the user already confirmed Leave in the guard dialog
      e.preventDefault();
      e.returnValue = '';
    };
    if (guardActive) {
      window.addEventListener('beforeunload', handler);
      return () => window.removeEventListener('beforeunload', handler);
    }
  }, [guardActive]);

  const applyFormat = useCallback((command) => {
    const editor = editorRef.current;
    if (!editor) return;

    const { selStart, selEnd } = editor.getSelection();
    const state = {
      // Live editor text: bodyRef lags a render behind edits made just before this runs (slash apply).
      text: editor.getText?.() ?? bodyRef.current,
      selStart,
      selEnd,
    };

    let next;
    if (command.startsWith('callout:')) {
      next = insertCallout(state, command.slice('callout:'.length));
    } else switch (command) {
      case 'bold':    next = toggleWrap(state, '**'); break;
      case 'italic':  next = toggleWrap(state, '*');  break;
      case 'code':    next = toggleWrap(state, '`');  break;
      case 'highlight': next = toggleWrap(state, '=='); break; // Mod-Shift-H in the editor keymap does the same
      case 'comment': next = toggleWrap(state, '%%'); break;   // Mod-/ in the editor keymap does the same
      case 'codeblock': next = insertCodeBlock(state); break;
      case 'heading': next = toggleLinePrefix(state, '## '); break;
      case 'list':    next = toggleLinePrefix(state, '- ');  break;
      case 'table':   next = insertTable(state); break;
      case 'link':    next = insertLink(state); break;
      case 'h1':      next = setHeading(state, 1); break;
      case 'h2':      next = setHeading(state, 2); break;
      case 'h3':      next = setHeading(state, 3); break;
      case 'mathblock': next = insertMathBlock(state); break;
      case 'rule':    next = insertRule(state); break;
      default: return;
    }

    // Through the view as one transaction: setBody would be deferred by react-codemirror's typing latch.
    if (editor.applyEdit?.(next.text, next.selStart, next.selEnd)) return;
    fallbackSetBody(next.text);
    requestAnimationFrame(() => {
      editorRef.current?.setSelection(next.selStart, next.selEnd);
    });
  }, [fallbackSetBody]);

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

  // Mod-e: source <-> live preview (Obsidian's binding), wherever focus is. CodeMirror does not bind Mod-e.
  useEffect(() => {
    const handler = (e) => {
      if (e.defaultPrevented || e.repeat || e.isComposing || !(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey) return;
      // Not while a dialog/overlay (QuickOverlay, ConfirmDialog, Modal) is open over the editor.
      if (document.querySelector('[aria-modal="true"],[role="dialog"]')) return;
      // e.key is a deliberate fallback to e.code so the shortcut still works on non-QWERTY layouts.
      if (e.code === 'KeyE' || e.key === 'e' || e.key === 'E') { e.preventDefault(); toggleEditorMode(); }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [toggleEditorMode]);

  const [previewOpen, setPreviewOpen] = useState(true);
  // Re-fetch embeds whenever the preview is re-opened so edits to embedded pages show up.
  useEmbedCacheLifecycle(previewOpen);

  const previewArticleRef = useRef(null);
  const { card: previewCard } = useLinkPreview(previewArticleRef, previewOpen);

  // Ctrl/Cmd-hover over a link in the source editor.
  const [sourceHover, setSourceHover] = useState(null); // { url, rect }
  const [sourceResult, setSourceResult] = useState(null);
  const handleLinkHover = useCallback((url, rect) => {
    setSourceHover((prev) => {
      if (!url || !rect) return prev ? null : prev;
      return prev && prev.url === url ? prev : { url, rect };
    });
  }, []);
  const sourceUrl = sourceHover?.url;
  const sourceOpen = !!sourceHover;
  useEffect(() => {
    if (!sourceOpen) return undefined;
    const onKey = (e) => {
      if (e.type === 'keydown' ? e.key === 'Escape' : (e.key === 'Control' || e.key === 'Meta')) setSourceHover(null);
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('keyup', onKey);
    return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('keyup', onKey); };
  }, [sourceOpen]);
  useEffect(() => {
    const target = sourceUrl ? wikiLinkTarget(sourceUrl) : null;
    if (!target) return undefined;
    const section = sourceUrl.includes('#') ? sourceUrl.slice(sourceUrl.indexOf('#') + 1) : null;
    const ctl = new AbortController();
    loadPreview(target, section, ctl.signal)
      .then((r) => { if (!ctl.signal.aborted) setSourceResult({ url: sourceUrl, r }); })
      .catch((err) => {
        if (err?.name !== 'AbortError') console.warn('[link-preview] source preview failed', target, err?.message || err);
      });
    return () => ctl.abort();
  }, [sourceUrl]);
  const sourceCard = sourceHover && wikiLinkTarget(sourceHover.url)
    ? <LinkPreviewCard rect={sourceHover.rect} result={sourceResult?.url === sourceHover.url ? sourceResult.r : null} /> : null;

  const imageInputRef = useRef(null);
  const runCommand = useRunCommand();
  const saveNow = useCallback(() => { if (!savingRef.current) latestSaveRef.current?.(); }, []);
  const pickImage = useCallback(() => imageInputRef.current?.click(), []);
  const togglePreview = useCallback(() => setPreviewOpen((v) => !v), []);
  const foldAll = useCallback(() => editorRef.current?.foldAll?.(), []);
  const unfoldAll = useCallback(() => editorRef.current?.unfoldAll?.(), []);
  // "Paste as plain text" from the palette: the browser may refuse a programmatic clipboard read (permission prompt
  // dismissed, insecure context, Firefox), so the failure points at the shortcut, which always works.
  const pastePlain = useCallback(async () => {
    try {
      const text = await navigator.clipboard.readText();
      editorRef.current?.pasteText(text);
    } catch (err) {
      console.warn('[editor] clipboard read for plain-text paste failed', err?.message || err);
      toast.error(`The browser blocked clipboard access — press ${formatKeys('Mod-Shift-V')} to paste as plain text`);
    }
  }, [toast]);
  // "Copy block link": the server reads the buffer and answers the block at the caret — its id, or the edit that gives
  // it one (applied as one undo step) — then `[[ThisPage#^id]]` is copied. The clipboard write starts before the
  // request is answered (a ClipboardItem of a pending link), inside the palette's user gesture, where the browser has one.
  const copyBlockLink = useCallback(async () => {
    const view = editorRef.current?.getView?.();
    if (!view) return;   // still loading: there is no text to read
    const pos = view.state.selection.main.head;
    const linked = draftBlockLink(view, pos, (text, offset) => api.draftBlocks(name, { text, metadata: metadataRef.current, offset }))
      .then(({ id, change }) => {
        // a resync remount while the server answered: this view is destroyed, and its text is not the editor's any more
        if (editorRef.current?.getView?.() !== view) throw Object.assign(new Error('Block link: changed'), { code: 'changed' });
        if (change) {
          view.dispatch({
            changes: change,
            effects: [...revealEffects(view.state, change.from), ...revealEffects(view.state, change.to)],
            userEvent: 'input',
          });
        }
        return `[[${name}#^${id}]]`;
      });
    const copied = writeClipboard(linked);
    try {
      await linked;
    } catch (err) {
      copied.catch((e) => console.debug('[editor] block link not copied', e?.message || e));
      if (err?.code === 'no-block') toast.info('Put the cursor in a paragraph, list item or table');
      else if (err?.code === 'no-page') toast.info('Save the page first to link its blocks');
      else if (err?.code === 'refused') toast.error("Couldn't add a block id here");
      else if (err?.code === 'changed') toast.error('The text changed — copy the block link again');
      else {
        console.warn('[editor] could not get a block link', name, pos, err?.cause?.message || err?.message || err);
        toast.error("Couldn't get a block link — try again");
      }
      return;
    }
    try {
      await copied;
      toast.success('Block link copied');
    } catch (err) {
      console.warn('[editor] could not copy the block link', name, err?.message || err);
      toast.error("Couldn't copy link");
    }
  }, [name, toast]);
  // Built inside the effect (not during render): several actions read refs, which render must not touch.
  useEffect(() => registerCommands(buildEditorCommands({
    format: applyFormat, save: saveNow, pickImage, togglePreview, toggleRail, foldAll, unfoldAll, toggleLivePreview: toggleEditorMode,
    pastePlain, copyBlockLink,
  })), [applyFormat, saveNow, pickImage, togglePreview, toggleRail, foldAll, unfoldAll, toggleEditorMode, pastePlain, copyBlockLink]);
  // A paste converted to Markdown: the first one of the session says how to get plain text instead. Embedded raster
  // images upload in place (handlePasteImages); the ones that cannot (svg, blob:/file:/cid:, unreadable, oversize, past the
  // per-paste cap) are reported every time rather than dropped silently.
  const pasteHintShownRef = useRef(false);
  const handlePasteConverted = useCallback(({ kind, droppedImages }) => {
    const notes = [];
    if (kind === 'markdown' && !pasteHintShownRef.current) {
      pasteHintShownRef.current = true;
      notes.push(`Pasted as Markdown — ${formatKeys('Mod-Shift-V')} pastes plain text`);
    }
    if (droppedImages > 0) {
      notes.push(droppedImages === 1
        ? '1 embedded image could not be pasted — upload it as an attachment'
        : `${droppedImages} embedded images could not be pasted — upload them as attachments`);
    }
    if (notes.length) toast.info(notes.join('. '));
  }, [toast]);
  // Memoised on content: the attachments hook may hand back a fresh array each render.
  const attachmentNamesKey = (attachments.list || []).map((a) => a.fileName).join('\n');
  const livePreviewContext = useMemo(() => ({
    pageName: name,
    attachments: attachmentNamesKey ? attachmentNamesKey.split('\n') : [],
    loadEmbed: loadEmbedState,
  }), [name, attachmentNamesKey]);
  // The preview's plugin stack, stable across renders that do not change its inputs (EditorPreviewMarkdown is
  // memoized on these): only fileName of an attachment is read by the preview plugins.
  const previewAttachments = useMemo(
    () => (attachmentNamesKey ? attachmentNamesKey.split('\n').map((fileName) => ({ fileName })) : []),
    [attachmentNamesKey],
  );
  const previewRemarkPlugins = useMemo(() => [
    [remarkGfm, { singleTilde: false }],
    [remarkMath, { singleDollarTextMath: false }],
    remarkInlineMath,
    remarkCallouts,
    remarkBlockIds,
    remarkWikiMarkup,
    [remarkWikiLinks, { resolved: wikiLinkResolution, attachments: previewAttachments, pageName: name }],
    [remarkMissingLinks, { missing: missingPages }],
    [remarkAttachments, { attachments: previewAttachments, pageName: name }],
  ], [wikiLinkResolution, previewAttachments, name, missingPages]);
  const previewRehypePlugins = useMemo(
    () => [rehypeBlockIds, rehypeKatex, rehypeRenderedFences, [rehypeHighlightCode, { lowlight }], rehypeSourceLine],
    [lowlight],
  );
  const slashSource = useMemo(() => createSlashSource(getCommands, (id) => runCommand(id)), [runCommand]);
  // Inline #tag completion over the tag distribution (most used first); CodeEditor reads the source through a ref.
  const tagSource = useMemo(() => createTagSource(() => tagSuggestions), [tagSuggestions]);

  const handleBold = useCallback(() => applyFormat('bold'), [applyFormat]);
  const handleItalic = useCallback(() => applyFormat('italic'), [applyFormat]);
  const handleLink = useCallback(() => applyFormat('link'), [applyFormat]);

  /** Converts the body to Markdown in place; true once the converted text is in the editor (one transaction, E7). */
  const convertBody = async () => {
    // Convert exactly what the editor shows, and apply the result only if it still shows that: anything typed
    // during the request would otherwise be silently discarded by the replacement.
    const sent = editorRef.current?.getText?.() ?? bodyRef.current;
    setConverting(true);
    setError(null);
    try {
      const result = await api.convertWikiToMarkdown(sent);
      const live = editorRef.current?.getText?.() ?? bodyRef.current;
      if (live !== sent) {
        setError('The page changed while converting — run Convert again.');
        return false;
      }
      replaceBody(result.markdown);
      setMarkupSyntax('markdown');
      setConversionWarnings(result.warnings || []);
      return true;
    } catch (err) {
      console.warn('[editor] wiki-to-markdown conversion failed', err?.message || err);
      setError('Conversion failed: ' + (err.message || 'Unknown error'));
      return false;
    } finally {
      setConverting(false);
    }
  };
  const handleConvert = () => { convertBody(); };
  // Decision 8: in collab mode the markup syntax travels with a page version, so converting publishes for everyone.
  const convertAndPublish = async () => {
    setConfirmConvert(false);
    if (!(await convertBody())) return;
    // R12: a failed publish does not revert the shared draft (it is Markdown for everyone now): offer the retry instead
    if (!(await publishAsMarkdown())) setConvertPending(true);
  };
  const publishAsMarkdown = () => publishContent({ stay: true, note: 'Converted to Markdown', markup: 'markdown' });

  // #4 — extracted save function used by button and keyboard shortcut. Sends body + metadata object
  // (replaceMetadata=true for full-object replace); maps a 422 to inline field violations, surfaces
  // 200 warnings as an advisory toast.
  // Resolves true on success-with-stay (`{ stay: true }`), false on every failure branch; without `stay`
  // a success navigates to the page view. The Save button passes a click event, which destructures to stay=false.
  // A 422 from a save or a publish (same bodies, §6.2): placed in the violation UI.
  const showRefusal = (body) => {
    if (body?.error === 'access_rule_shape') {
      // The save would add an access rule the server cannot read (it would lock the page, #104).
      const refused = body.violations || [];
      setAccessRuleViolations(refused);
      if (!refused.length) setError('An access rule on this page cannot be read.');
      toast.error('An access rule on this page cannot be read');
    } else if (body?.error === 'math_validation_failed') {
      setMathViolations(body.violations || []);
      toast.error('Fix the highlighted math errors');
    } else {
      setViolations(body?.violations || []);
      setRefusedMeta(metadata);
      toast.error('Fix the highlighted frontmatter fields');
    }
  };

  // Collab mode's Save: publish the shared draft once every local edit is on the server (the hook waits for sync).
  // `note` and `markup` override the change note and the markup syntax (Convert and publish).
  const publishContent = async ({ stay = false, recreateConfirmed = false, note, markup } = {}) => {
    // Recreating a deleted page publishes the shared draft as the server holds it: the publish cannot carry this tab's
    // unsynced text or field edits (the frozen draft refuses pushes). Show them to copy and ask first — never drop them.
    if (collabFrozen && !recreateConfirmed) {
      const local = collab.captureLocal();
      if (local.unsyncedText || local.metaEdits.length) {
        setRecreateCheck({ ...local, asking: true });
        return false;
      }
    }
    setSaving(true);
    savingRef.current = true;
    setError(null);
    setAccessRuleViolations([]);
    try {
      // always the editor's known syntax: left out, the server would infer it from the file extension (R12).
      // "likely-wiki" is only a heuristic over a page stored as Markdown.
      const syntax = markup ?? (markupSyntax === 'wiki' ? 'wiki' : 'markdown');
      const res = await collab.publish(note || changeNote || (isNew ? 'Created page' : 'Updated page'), { markupSyntax: syntax });
      if (!res.ok) {
        if (res.status === 422) showRefusal(res.body);
        else {
          console.warn('[collab] publish failed', res.status, res.reason, res.message);
          setError(publishFailureMessage(res));
        }
        return false;
      }
      setViolations([]);
      setMathViolations([]);
      if (syntax === 'markdown') setConvertPending(false);
      evictPreview(name);
      const warns = res.warnings || [];
      const mathWarns = res.mathWarnings || [];
      if (mathWarns.length) {
        setMathViolations(mathWarns);
        toast.info(`Published with ${mathWarns.length} math warning${mathWarns.length > 1 ? 's' : ''}`);
      } else if (warns.length) {
        toast.info(`Published with ${warns.length} advisory warning${warns.length > 1 ? 's' : ''}`);
      } else {
        toast.success('Published');
      }
      if (stay) {
        setIsNew(false);
        setOriginalVersion(res.pageVersion ?? originalVersion);
        return true;
      }
      leftPublishedRef.current = true; // leaving after publishing: nothing stays behind in the draft to mention
      // Local work the publish did not carry (a recreate of a deleted page) goes through the guard, which asks first.
      // Read live: the guard's own registration follows a render behind the sync that a normal publish waited for.
      if (hasLocalWork(collab.captureLocal())) guardedNavigate(`/wiki/${name}`);
      else navigate(`/wiki/${name}`);
      return true;
    } finally {
      setSaving(false);
      savingRef.current = false;
    }
  };

  const saveContent = async ({ stay = false } = {}) => {
    setSaving(true);
    savingRef.current = true;
    setError(null);
    setAccessRuleViolations([]);
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
      evictPreview(name);
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
      if (stay) {
        setIsNew(false);
        setOriginalVersion(res?.version ?? originalVersion);
        setLoadedContent(reconstructContent(metadata, body));
        return true;
      }
      navigate(`/wiki/${name}`);
      return true;
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
          console.warn('[editor] version conflict, and fetching the current server version failed', fetchErr?.message || fetchErr);
          setError('Version conflict, and failed to fetch the current server version.');
        }
      } else if (err.status === 422) {
        showRefusal(err.body);
      } else {
        setError(err.message || 'Save failed');
      }
      return false;
    } finally {
      setSaving(false);
      savingRef.current = false;
    }
  };

  // Written in an effect (runs after every render) rather than during render.
  // Any collab state publishes (a join in progress answers "Not connected…"): never the single-author PUT, which would
  // write e.g. the recovery text over the shared draft.
  const commit = collabMode ? publishContent : saveContent;

  // Publish in collab mode goes through the dialog (§8.3, §16 decision 1): the summary is fetched once every local edit
  // has reached the server, then shown. Recreating a deleted page keeps its own confirm.
  const summaryToken = useRef(0);
  const openPublishDialog = async (mode = 'publish') => {
    if (publishDialogRef.current) return false;
    const token = ++summaryToken.current;
    const pending = { mode, summary: null, unsynced: collab.unsyncedParticipants() };
    publishDialogRef.current = pending;
    setPublishDialog(pending);
    setError(null);
    const res = await collab.draftSummary();
    if (token !== summaryToken.current) return false;
    if (!res.ok) {
      console.warn('[collab] could not load the draft summary', res.status, res.reason);
      publishDialogRef.current = null;
      setPublishDialog(null);
      setError(res.reason === 'not-live' ? publishFailureMessage(res) : 'Could not load the changes to publish — try again.');
      return false;
    }
    setPublishDialog((d) => (d ? { ...d, summary: res.summary, unsynced: collab.unsyncedParticipants() } : d));
    return true;
  };
  const closePublishDialog = () => {
    summaryToken.current += 1;
    publishDialogRef.current = null;
    setPublishDialog(null);
  };
  const confirmPublish = async () => {
    await publishContent();
    closePublishDialog();
  };
  // edits that reached this editor after the summary was taken (they are published too: publish takes the head)
  const newerEdits = publishDialog?.summary?.version != null && syncedVersion != null
    ? Math.max(0, syncedVersion - publishDialog.summary.version) : 0;
  const save = collabMode && !collabFrozen ? () => openPublishDialog('publish') : commit;
  useEffect(() => {
    latestSaveRef.current = save;
  });

  const restoreDraft = useCallback(async () => {
    const full = draft?.content || '';
    replaceBody(stripFrontmatter(full));
    const m = full.match(/^---\r?\n([\s\S]*?)\r?\n---/);
    if (m) {
      try {
        const result = await api.validateFrontmatter({ frontmatter: m[1] });
        setMetadata(result?.metadata || {});
      } catch (err) {
        console.warn('[editor] could not parse the draft frontmatter; restoring without metadata', err?.message || err);
        setMetadata({});
      }
    } else {
      setMetadata({});
    }
    setRestorePrompt(false);
  }, [draft, replaceBody]);

  const dragCounterRef = useRef(0);

  const { uploadFiles, uploadPlaced } = useAttachmentUpload({
    existingNames: (attachments.list || []).map((a) => a.fileName),
    upload: attachments.uploadAttachment,
    updateBody: fallbackSetBody,
    editor: editorEdits,
    toast,
  });
  // Attachments need an existing page; on a new page the paste/drop waits for a first save.
  // Placeholders are inserted at paste time; the uploads start after the first save (or are cancelled).
  const [pendingUpload, setPendingUpload] = useState(null);
  const handleFiles = useCallback((files, pos, opts) => {
    if (isNew) {
      uploadFiles(files, pos, { ...opts, defer: true }).then((handle) => {
        if (handle) setPendingUpload((prev) => ({ handles: [...(prev?.handles || []), handle] }));
      }).catch((err) => console.warn('[editor] could not queue upload', err?.message || err));
      return;
    }
    uploadFiles(files, pos, opts);
  }, [isNew, uploadFiles]);

  // Embedded data: images of a converted paste: placeholders go in with the pasted text, at each image's position.
  // On a new page the uploads wait for the first save, exactly as handleFiles defers them.
  const handlePasteImages = useCallback((images, text) => {
    const placed = uploadPlaced(images, text);
    const begin = isNew
      ? () => setPendingUpload((prev) => ({ handles: [...(prev?.handles || []), { start: placed.start, cancel: placed.cancel }] }))
      : () => {
        placed.start().catch((err) => {
          console.warn('[editor] pasted image upload failed', err?.message || err);
          toast.error(`Pasted image upload failed: ${err?.message || err}`);
        });
      };
    return { text: placed.text, begin };
  }, [isNew, uploadPlaced, toast]);

  const handleDragEnter = useCallback((e) => {
    if (!dragCarriesFiles(e.dataTransfer)) return;
    dragCounterRef.current += 1;
    setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback((e) => {
    if (!dragCarriesFiles(e.dataTransfer)) return;
    dragCounterRef.current -= 1;
    if (dragCounterRef.current <= 0) {
      dragCounterRef.current = 0;
      setIsDragging(false);
    }
  }, []);

  const handleDragOver = useCallback((e) => {
    if (dragCarriesFiles(e.dataTransfer)) e.preventDefault();
  }, []);

  const handleDrop = useCallback((e) => {
    dragCounterRef.current = 0;
    setIsDragging(false);
    // A file dropped on the pane's padding (outside CodeMirror's content) is not handled by the editor:
    // cancel the browser's open-the-file default and upload at the caret instead.
    if (!e.defaultPrevented && dragCarriesFiles(e.dataTransfer)) {
      e.preventDefault();
      handleFiles(filesFromDrop(e.dataTransfer), getDropOffset(), { pasted: false });
    }
  }, [handleFiles, getDropOffset]);

  const handleCancel = () => guardedNavigate(`/wiki/${name}`);

  const discardSharedDraft = async () => {
    setConfirmDiscard(false);
    const res = await collab.discard();
    if (res.ok) {
      toast.success('Shared draft discarded');
      return;
    }
    console.warn('[collab] discard failed', res.status, res.reason, res.message);
    setError(res.reason === 'not-live' ? 'Not connected to the shared draft — try again once it reconnects.' : (res.message || 'Discard failed'));
  };

  const copyRecoveryText = () => copyText(collab.resync?.unsyncedText || '');
  // Ruling P10, at click time: the rendered "alone" may lag a participant whose presence already reached the session.
  const restoreOfflineDraft = () => {
    if (Object.keys(collab.presenceNow?.() ?? {}).length > 0) {
      toast.info('Someone else joined the shared draft — copy what you need from your offline draft instead.');
      return;
    }
    replaceBody(offlineDraftOffer.text);
    dropOfflineDraft();
  };
  // Every decision on the offline draft (restore, copy, delete) clears the local copy.
  const dropOfflineDraft = () => {
    clearDraft();
    setOfflineDraftOffer(null);
  };
  const copyOfflineDraft = async () => {
    if (await copyText(offlineDraftOffer.text)) dropOfflineDraft();
  };
  /** Copies `text` to the clipboard; true on success. */
  const copyText = async (text) => {
    try {
      await navigator.clipboard.writeText(text);
      toast.success('Copied');
      return true;
    } catch (err) {
      console.warn('[collab] could not copy the text to the clipboard', err?.message || err);
      toast.error("Couldn't copy — select the text and copy it by hand");
      return false;
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
    replaceBody(conflict.serverBody);
    setOriginalVersion(conflict.serverVersion);
    setConflict(null);
  };

  const handleCopyAndLoad = async () => {
    try {
      await navigator.clipboard.writeText(fullText);
    } catch (err) {
      // Clipboard is not available in all contexts; load the server version anyway.
      console.warn('[editor] could not copy the local text to the clipboard', err?.message || err);
    }
    setMetadata(conflict.serverMetadata);
    replaceBody(conflict.serverBody);
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
        <div className={`editor-toolbar-group${collabMode ? ' editor-toolbar-title-group' : ''}`}>
          <div className="editor-toolbar-title">
            <h2 data-testid="editor-heading" className={collabMode ? 'editor-heading-compact' : undefined}
              title={`${isNew ? 'Create' : 'Edit'}: ${name}`}
              style={{ fontFamily: 'var(--font-display)', fontSize: '1.25rem', fontWeight: 600 }}>
              {isNew ? 'Create' : 'Edit'}: {name}
            </h2>
            {collabMode && (
              <div className="collab-subrow" data-testid="collab-subrow">
            {collabMode && (collabEditing ? (
              <div className="collab-status" data-testid="collab-status" data-status={collab.status}>
                <PresenceBar self={collab.self} presence={collab.presence} status={collab.status} />
              </div>
            ) : (
              <span className="collab-status" data-testid="collab-status" data-status={collab.status} role="status">
                {COLLAB_STATUS_LABEL[collab.status] || collab.status}
              </span>
            ))}
            {collabEditing && collabDraft?.unpublished && (
              <div className="collab-draft-chip" data-testid="collab-draft-chip"><Chip label="Unpublished draft" /></div>
            )}
              </div>
            )}
          </div>
        </div>
        <div className="editor-toolbar-group">
          {!collabMode && (
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
          )}
          <button className="btn btn-ghost" onClick={() => setPanelOpen(p => !p)}
            style={{ fontSize: '1.1rem', padding: 'var(--space-xs) var(--space-sm)' }}
            title="Attachments">
            Attach
          </button>
          <button className="btn btn-ghost" data-testid="editor-rail-toggle" onClick={toggleRail}
            aria-pressed={railOpen} title="Toggle outline rail">
            Outline
          </button>
          <button className="btn btn-ghost" data-testid="editor-cancel" onClick={handleCancel}>
            Cancel
          </button>
          {collabEditing && !collabEvicted && (
            <button type="button" className="btn btn-ghost" data-testid="collab-discard" disabled={collabReadOnly}
              onClick={() => setConfirmDiscard(true)}>
              Discard draft
            </button>
          )}
          {!collabForbidden && !collabEvicted && (
          <button
            className="btn btn-primary"
            data-testid="editor-save"
            onClick={save}
            disabled={saving || hasBlockingErrors || (collabMode && !(collab.status === 'live' || collab.status === 'reconnecting' || collabFrozen))}
            title={hasBlockingErrors ? 'Fix the highlighted errors before saving' : undefined}
          >
            {collabFrozen
              ? (saving ? 'Recreating…' : 'Recreate page')
              : collabMode ? (saving ? 'Publishing…' : 'Publish') : (saving ? 'Saving…' : 'Save')}
          </button>
          )}
        </div>
      </div>

      {(markupSyntax === 'wiki' || markupSyntax === 'likely-wiki') && (
        <div className="info-banner">
          {collabMode ? (
            // Decision 8: the markup syntax travels with a page version, so converting publishes for all editors.
            <>
              <span>This page uses legacy wiki syntax. Convert it to Markdown and publish it?</span>
              <button className="btn btn-primary btn-sm" onClick={() => setConfirmConvert(true)}
                disabled={converting || saving || !collabEditing || collabReadOnly || collab.status === 'auth-required'}>
                {converting ? 'Converting…' : 'Convert and publish'}
              </button>
            </>
          ) : (
            <>
              <span>This page uses legacy wiki syntax. Convert to Markdown?</span>
              <button className="btn btn-primary btn-sm" onClick={handleConvert} disabled={converting}>
                {converting ? 'Converting…' : 'Convert to Markdown'}
              </button>
            </>
          )}
        </div>
      )}

      {metadata.derived_from && (
        <div className="warning-banner" role="status" data-testid="derived-body-banner">
          This page&apos;s body is machine-generated from an uploaded source (<em>{String(metadata.derived_from)}</em>).
          A reflow will overwrite hand edits — curate in frontmatter, tags, and the Knowledge Graph instead.
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

      {collabEditing && draftBannerOpen && (
        <DraftBanner draft={collabDraft} selfName={selfName}
          onPublish={collabReadOnly ? undefined : () => openPublishDialog('publish')}
          onDiscard={collabReadOnly ? undefined : () => setConfirmDiscard(true)}
          onViewChanges={() => openPublishDialog('view')}
          onDismiss={() => setDraftBannerOpen(false)} />
      )}
      {collabEditing && convertPending && (
        <div className="warning-banner" role="alert" data-testid="collab-convert-unpublished">
          <span>Converted for everyone but not published — </span>
          <button type="button" className="btn btn-primary btn-sm" disabled={saving || collabReadOnly}
            onClick={publishAsMarkdown}>Publish as Markdown</button>
        </div>
      )}
      {collabEditing && markupStale && (
        <div className="warning-banner" role="alert" data-testid="collab-markup-stale">
          <span>Couldn&apos;t check this page&apos;s markup syntax after the latest publish, so publishing may mark it wrongly.</span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={refreshMarkup}>Retry</button>
        </div>
      )}
      {oversize && (
        <div className="warning-banner" role="alert" data-testid="collab-oversize">
          This draft is over the page size limit ({bodyBytes.toLocaleString()} of {oversizeLimit.toLocaleString()}); trim it to publish
        </div>
      )}
      {unplacedInserts.length > 0 && (
        <div className="warning-banner" role="alert" data-testid="collab-insert-recovery">
          <strong>Text added while your editor was out of sync could not be placed.</strong>{' '}
          <span>The text around it now appears more than once, so it was not inserted. Copy it and paste it where it belongs:</span>
          <pre className="collab-recovery-text">{unplacedInserts.join('\n')}</pre>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => copyText(unplacedInserts.join('\n'))}>Copy</button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => updateUnplaced([])}>Dismiss</button>
        </div>
      )}

      {collab.resync && (
        <div className="warning-banner" role="alert" data-testid="collab-recovery">
          <strong>Your editor lost its place in the shared draft.</strong>{' '}
          {collab.resync.unsyncedText ? (
            <>
              <span>These edits of yours did not reach it. Copy them, then paste them back where they belong:</span>
              <pre className="collab-recovery-text" data-testid="collab-recovery-text">{collab.resync.unsyncedText}</pre>
              <button type="button" className="btn btn-ghost btn-sm" onClick={copyRecoveryText}>Copy my text</button>
            </>
          ) : <span>None of your edits were lost.</span>}
          {collab.status === 'resync'
            ? <button type="button" className="btn btn-primary btn-sm" onClick={() => collab.restart()}>Continue editing</button>
            : <button type="button" className="btn btn-ghost btn-sm" onClick={collab.dismissResync}>Dismiss</button>}
        </div>
      )}
      {collabEditing && collab.status === 'auth-required' && (
        <div className="warning-banner" role="alert" data-testid="collab-auth-required">
          <span>Your sign-in has expired. Your edits are kept in this tab and are sent once you sign in again.</span>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setLoginOpen(true)}>Sign in again</button>
        </div>
      )}
      {collabFrozen && (
        <div className="warning-banner" role="alert" data-testid="collab-frozen">
          This page was deleted — copy your text or recreate the page.
        </div>
      )}
      {recreateCheck && (
        <div className="warning-banner" role="alert" data-testid="collab-recreate-recovery">
          <strong>These edits of yours never reached the shared draft, so recreating the page will not include them.</strong>
          {recreateCheck.unsyncedText && <pre className="collab-recovery-text">{recreateCheck.unsyncedText}</pre>}
          {recreateCheck.metaEdits.length > 0 && (
            <ul className="collab-recovery-meta-list">
              {recreateCheck.metaEdits.map((e, i) => <li key={i}>{describeMetaEdit(e)}</li>)}
            </ul>
          )}
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => copyText(localWorkText(recreateCheck))}>Copy my text</button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setRecreateCheck(null)}>Dismiss</button>
        </div>
      )}
      {collabEvicted && (
        <div className="warning-banner" role="alert" data-testid="collab-evicted">
          <span>
            {collab.evictedReason === 'purged'
              ? 'An administrator removed this draft, so the editor is read-only.'
              : 'You no longer have permission to edit this page, so the editor is read-only.'}
            {hasLocalWork(collab.evictedRecovery)
              ? (collab.evictedReason === 'purged'
                ? ' Your edits below had not reached the draft before it was removed — copy them if you need them.'
                : ' Your edits that had not reached the shared draft were not saved — copy it if you need it.')
              : ' None of your edits were lost.'}
          </span>
          {hasLocalWork(collab.evictedRecovery) && (
            <>
              <pre className="collab-recovery-text" data-testid="collab-evicted-text">{localWorkText(collab.evictedRecovery)}</pre>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => copyText(localWorkText(collab.evictedRecovery))}>Copy</button>
            </>
          )}
        </div>
      )}
      {collabEditing && collabMeta?.carried.length > 0 && (
        <div className="warning-banner" role="alert" data-testid="collab-recovery-meta">
          <strong>Your unsaved field changes did not reach the shared draft.</strong>{' '}
          <span>They are not applied automatically, because the fields may have changed since. Apply the ones you still want:</span>
          <ul className="collab-recovery-meta-list">
            {collabMeta.carried.map((e, i) => (
              <li key={i}>
                <code>{describeMetaEdit(e)}</code>{' '}
                <button type="button" className="btn btn-ghost btn-sm" disabled={collabReadOnly}
                  onClick={() => collabMeta.applyCarried(e)}>Apply</button>
              </li>
            ))}
          </ul>
          <button type="button" className="btn btn-ghost btn-sm"
            onClick={() => copyText(collabMeta.carried.map(describeMetaEdit).join('\n'))}>Copy</button>
          <button type="button" className="btn-link" onClick={collabMeta.dismissCarried}>Discard them</button>
        </div>
      )}
      {showExternal && (
        <div className="info-banner collab-external-change" role="status" data-testid="collab-external-change">
          <span>Updated by {showExternal.source || 'an outside change'}.</span>
          {Number.isInteger(showExternal.pageVersion) && showExternal.pageVersion > 1 && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => openExternalDiff(showExternal)}>View diff</button>
          )}
          <button type="button" className="btn-link" onClick={() => setExternalDismissed(showExternal)}>Dismiss</button>
        </div>
      )}
      {collabForbidden && (
        <div className="info-banner" role="status" data-testid="collab-forbidden">
          You don&apos;t have permission to edit this page.
        </div>
      )}
      {collabWanted && collab.status === 'full' && (
        <div className="info-banner" role="status" data-testid="collab-full">
          Too many people are editing this page together — you are editing on your own, and saving merges your changes into theirs.
        </div>
      )}
      {collabEditing && offlineDraftOffer && (
        <div className="draft-restore-banner" role="status" data-testid="collab-offline-draft">
          {/* Ruling P10: replacing the shared text with a stale local copy would silently delete what others wrote since
              (undo is per author), so it is a restore only while nobody else is in the session; otherwise text to copy. */}
          {editorCount <= 1 ? (
            <>
              <span className="draft-restore-text">
                You have an offline draft of this page from {formatRelative(offlineDraftOffer.savedAt)}. Restore it into
                the shared draft? (Its text only; the frontmatter stays as it is.)
              </span>
              <button type="button" className="draft-btn draft-btn-restore" disabled={collabReadOnly}
                onClick={restoreOfflineDraft}>
                Restore into the shared draft
              </button>
            </>
          ) : (
            <>
              <span className="draft-restore-text">
                You have an offline draft of this page from {formatRelative(offlineDraftOffer.savedAt)}. Others are
                editing the shared draft now, so it is not applied: copy what you need from it and paste it in yourself.
              </span>
              <pre className="collab-recovery-text" data-testid="collab-offline-draft-text">{offlineDraftOffer.text}</pre>
              <button type="button" className="draft-btn draft-btn-restore" onClick={copyOfflineDraft}>
                Copy my offline text
              </button>
            </>
          )}
          <button type="button" className="draft-btn draft-btn-discard" onClick={() => setConfirmOfflineDiscard(true)}>
            Discard it
          </button>
        </div>
      )}
      {collabWanted && collab.status === 'error' && (
        <div className="info-banner" role="status" data-testid="collab-error">
          Editing together is unavailable right now — you are editing on your own.
        </div>
      )}
      {collabEditing && restoreOffer && (
        <div className="info-banner" role="status" data-testid="collab-restore-offer">
          <span>
            Others are already editing this page. Applying version {restoreOffer.from} changes the page for
            all {editorsLabel(editorCount)} (its text only; the frontmatter stays as it is).
          </span>
          <button type="button" className="btn btn-primary btn-sm" disabled={collabReadOnly}
            onClick={() => { replaceBody(restoreOffer.text); setRestoreOffer(null); }}>
            Apply version {restoreOffer.from}
          </button>
          <button type="button" className="btn-link" onClick={() => setRestoreOffer(null)}>Keep the shared text</button>
        </div>
      )}
      {accessRuleViolations.length > 0 && (
        <div className="error-banner" data-testid="editor-access-rule-violations">
          <ul>
            {accessRuleViolations.map((v, i) => (
              <li key={i}><AccessRuleLocation line={v.line} field={v.field} />: {v.message}</li>
            ))}
          </ul>
        </div>
      )}

      {pendingUpload && (
        <div className="info-banner" role="status" data-testid="upload-needs-save">
          <span>{collabEditing ? 'Publish' : 'Save'} the page once to add attachments.</span>
          <button type="button" className="btn btn-primary btn-sm" disabled={saving || hasBlockingErrors}
            onClick={async () => {
              const p = pendingUpload;
              // R13: this first publish of a NEW page bypasses the publish dialog — accepted: there is no published page
              // to diff against, and the uploads need the page to exist
              if (await commit({ stay: true })) {
                setPendingUpload(null);
                for (const h of p.handles) await h.start();
              }
            }}>
            {collabEditing ? 'Publish' : 'Save'} and upload
          </button>
          <button type="button" className="btn-link" onClick={() => {
            pendingUpload.handles.forEach((h) => h.cancel());
            setPendingUpload(null);
          }}>Cancel</button>
        </div>
      )}

      {restoring && (
        <div className="info-banner" role="status" data-testid="restore-banner">
          Editing a copy of version {restoring.from} — {collabMode ? 'publishing' : 'saving'} creates version {restoring.current + 1}.
        </div>
      )}

      {restorePrompt && (
        <div className="draft-restore-banner" role="status">
          <span className="draft-restore-text" title={new Date(draft.savedAt).toLocaleString()}>
            You have unsaved changes from{' '}
            {formatRelative(draft.savedAt)}.
          </span>
          <button type="button" className="draft-btn draft-btn-restore" onClick={restoreDraft}>
            Restore
          </button>
          <button type="button" className="draft-btn draft-btn-discard"
            onClick={() => { clearDraft(); setRestorePrompt(false); }}>
            Discard
          </button>
          <button type="button" className="draft-btn-dismiss"
            aria-label="Dismiss draft notice"
            onClick={() => setRestorePrompt(false)}>
            <span aria-hidden="true">×</span>
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
              {/* Co-edited in collab mode (A14); not while joining (the join's metadata replaces it) or read-only. */}
              <fieldset disabled={collabMode && (!collabMeta || collabReadOnly)}
                style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
                <FrontmatterEditor
                  metadata={metadata}
                  onChange={setMetadata}
                  onFieldChange={collabMeta ? collabMeta.setField : undefined}
                  onReplace={collabMeta ? collabMeta.change : undefined}
                  onFieldFocus={collabMeta ? collabMeta.focus : undefined}
                  onFieldBlur={collabMeta ? collabMeta.blur : undefined}
                  remoteChanged={collabMeta?.remoteChanged}
                  externalConflicts={collabMeta?.conflicts}
                  violations={displayViolations}
                  pageSearch={pageSearch}
                  tagSuggestions={tagSuggestions}
                />
              </fieldset>
            </>
          )}
          {activeMetaTab === 'knowledge' && (
            <KnowledgeGraphPanel pageName={name} />
          )}
        </Tabs>
      </section>

      <MathValidationSummary violations={mathViolations} onJump={jumpToMath} />

      <EditorToolbar onRun={runCommand} liveMode={liveActive} liveDisabled={isLegacyWiki} />

      <div className={`editor-layout${railOpen ? ' rail-open' : ''}`}>
      <div className={`editor-container${previewOpen ? '' : ' preview-hidden'}`}>
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
              Drop to upload
            </div>
          )}
          {collabMode && !collabEditing && !collabForbidden ? (
            <div className="editor-textarea collab-joining" data-testid="collab-joining" role="status">
              Joining the shared draft…
            </div>
          ) : (
          <CodeEditor
            key={collabEditing ? `collab-${sessionKeyOf(collab.extensions)}` : 'single'}
            ref={editorRef}
            data-testid="editor-textarea"
            className="editor-textarea"
            value={body}
            collabExtensions={collabEditing ? collabExtensions : undefined}
            collabDoc={collabEditing ? collab.docAtJoin : undefined}
            readOnly={collabReadOnly || collabForbidden}
            onEditorReady={handleEditorReady}
            onPasteConverted={handlePasteConverted}
            onPasteImages={handlePasteImages}
            pasteAsMarkdown={!isLegacyWiki}
            onChange={handleBodyChange}
            dark={dark}
            livePreview={liveActive}
            livePreviewContext={livePreviewContext}
            onBold={handleBold}
            onItalic={handleItalic}
            onLink={handleLink}
            linkCompletion={linkCompletion}
            slashSource={slashSource}
            tagSource={tagSource}
            onLinkHover={handleLinkHover}
            wikiLinkResolution={wikiLinkResolution}
            onViewChange={handleViewChange}
            onFiles={handleFiles}
          />
          )}
        </div>
        {previewOpen && (
        <div className="editor-pane editor-preview" ref={previewRef} onScroll={syncEditor}>
          <FrontmatterPreview content={fullText} />
          <article className="article-prose" ref={previewArticleRef} onClick={handlePreviewClick}>
            <EditorPreviewMarkdown content={previewSource} components={previewComponents} costMeter={previewCost}
              remarkPlugins={previewRemarkPlugins} rehypePlugins={previewRehypePlugins} />
          </article>
          {previewCard}
        </div>
        )}
        {sourceCard}
        <input
          type="file"
          ref={imageInputRef}
          hidden
          accept="image/*"
          multiple
          data-testid="editor-image-input"
          onChange={(e) => {
            handleFiles([...e.target.files], editorRef.current?.getSelection().selStart ?? bodyRef.current.length, { pasted: false });
            e.target.value = '';
          }}
        />
      </div>
      <EditorRail body={body} pageName={name} isNew={isNew} cursorStore={cursorStore}
        open={railOpen} onToggle={toggleRail} onJump={jumpToHeading}>
        <UnlinkedMentionsPanel status={mentionScan.status} mentions={visibleMentions}
          onLink={linkMention} onIgnore={ignoreMention}
          onJump={jumpToMention} onRetry={mentionScan.rescan} />
      </EditorRail>
      </div>
      <EditorStatusBar body={body} cursorStore={cursorStore} />

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

      {collabFrozen && recreateCheck?.asking && (
        <ConfirmDialog
          title="Recreate the page without your unsynced edits?"
          message="Your edits shown on the page are not included in the recreated page — copy them first if you need them."
          confirmLabel="Recreate page"
          cancelLabel="Not now"
          onConfirm={() => { setRecreateCheck((c) => (c ? { ...c, asking: false } : c)); publishContent({ recreateConfirmed: true }); }}
          onCancel={() => setRecreateCheck((c) => (c ? { ...c, asking: false } : c))}
        />
      )}
      {confirmOfflineDiscard && (
        <ConfirmDialog
          title="Delete your offline draft?"
          message="Your offline copy of this page is deleted from this browser. The shared draft does not change."
          confirmLabel="Delete offline draft"
          onConfirm={() => { setConfirmOfflineDiscard(false); dropOfflineDraft(); }}
          onCancel={() => setConfirmOfflineDiscard(false)}
        />
      )}
      {confirmConvert && (
        <ConfirmDialog
          title="Convert to Markdown and publish?"
          message={`This converts and publishes the page for ${forEditors(editorCount)}.`}
          confirmLabel="Convert and publish"
          danger={false}
          onConfirm={convertAndPublish}
          onCancel={() => setConfirmConvert(false)}
        />
      )}
      <PublishDialog isOpen={!!publishDialog} mode={publishDialog?.mode} summary={publishDialog?.summary}
        unsynced={publishDialog?.unsynced} newerEdits={newerEdits} changeNote={changeNote} onChangeNote={setChangeNote}
        onConfirm={confirmPublish} onCancel={closePublishDialog} busy={saving} />
      {confirmDiscard && (
        <ConfirmDialog
          title="Discard the shared draft?"
          message={`Discarding resets the shared draft to the published page for ${forEditors(editorCount)}. Unpublished changes are lost.`}
          confirmLabel="Discard for everyone"
          onConfirm={discardSharedDraft}
          onCancel={() => setConfirmDiscard(false)}
        />
      )}
      <Modal isOpen={!!diffFor} onClose={() => setDiffFor(null)} labelledBy="collab-diff-title" testId="collab-diff"
        className="collab-diff-modal">
        <h2 id="collab-diff-title" className="collab-diff-title">
          Outside change: version {diffFor ? diffFor.pageVersion - 1 : ''} → {diffFor?.pageVersion}
        </h2>
        {diffFor?.error && <div className="error-banner">{diffFor.error}</div>}
        {diffFor && !diffFor.error && diffFor.html == null && <p className="field-hint">Loading…</p>}
        {diffFor?.html != null && <DiffHtml html={diffFor.html} />}
        <div className="modal-actions">
          <button type="button" className="btn btn-secondary" onClick={() => setDiffFor(null)}>Close</button>
        </div>
      </Modal>
      {loginOpen && <LoginForm onClose={() => { setLoginOpen(false); collab.resume(); }} />}

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
