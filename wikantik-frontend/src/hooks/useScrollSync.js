import { useCallback, useEffect, useRef } from 'react';
import { frontmatterOffsetLines } from '../utils/frontmatterUtils';
import { scrollAnchors, previewScrollFor, editorScrollFor, isEcho } from '../utils/scrollSync';
import { collectPreviewAnchors, caretBlock, sortedByLine, PREVIEW_CARET_CLASS } from '../utils/previewAnchors';

/**
 * The editor <-> preview scroll sync of the page editor, block by block (utils/scrollSync): each rendered block's top
 * in the preview is matched to its line's top in the editor. The hook owns all the sync's state (scroll map, echo
 * records, the settle flag, the pending-sync record and the frame handles); the caller owns the panes and the guard.
 *
 * `editorRef` is the CodeEditor handle, `previewRef` the scrolling preview pane, `previewArticleRef` the rendered
 * article in it, `bodyRef` the current body text, and `clickSyncGuardRef` a flag the caller holds true while a click
 * in the preview is moving the editor (the sync stands still then).
 *
 * Returns:
 *   syncPreview({ caretMoved, force })  editor -> preview, one sync per frame;
 *   syncEditor()                        preview -> editor (the preview pane's onScroll);
 *   previewBlocks()                     the preview's measured blocks (cached per layout);
 *   resetScrollMap(previewShown)        drops the measured blocks; a hidden preview also drops everything that holds
 *                                       its elements (the marked caret block, the map, the echo record);
 *   prepareClickJump(offset)            for a click in the preview: cancels a pending sync, makes `offset` the
 *                                       reading line and drops a pending caret move.
 */
export function useScrollSync({ editorRef, previewRef, previewArticleRef, bodyRef, clickSyncGuardRef }) {
  const syncRafRef = useRef(0);
  const editorRafRef = useRef(0);
  // The preview's blocks are measured once per layout: again when its text was re-rendered (the cache is dropped), or
  // its height or width changed (a picture loaded, a resize).
  const anchorCacheRef = useRef(null);
  const previewBlocks = useCallback(() => {
    const preview = previewRef.current;
    const article = previewArticleRef.current;
    if (!preview || !article) return { anchors: [], byLine: [] };
    const cached = anchorCacheRef.current;
    if (cached && cached.article === article && cached.height === preview.scrollHeight && cached.width === preview.clientWidth) {
      return cached;
    }
    const anchors = collectPreviewAnchors(article, preview);
    anchorCacheRef.current = { article, height: preview.scrollHeight, width: preview.clientWidth, anchors, byLine: sortedByLine(anchors) };
    return anchorCacheRef.current;
  }, [previewRef, previewArticleRef]);
  // The reading line: how far down the editor's window the panes are held level, in px. It is where the caret was
  // in the window when it last moved there (typing, a click, the arrow keys), and 0 (the window's top) before that.
  // Every sync, a scroll included, matches that height in both panes, so a scroll after typing does not jump; the
  // tops meet at the top and the ends at the end (utils/scrollSync previewScrollFor).
  const readingLineRef = useRef(0);
  // The scroll map, rebuilt only when a block, the text or either pane's height moved: mapping every block into the
  // editor is one height lookup per block, too much to repeat on every frame of a scroll.
  const scrollMapRef = useRef(null);
  // The two panes as utils/scrollSync reads them, for the editor's geometry `g` (CodeEditor.scrollGeometry), the
  // preview's blocks and the body's line offset.
  const paneFor = useCallback((g, preview, anchors, offset) => {
    const previewHeight = preview.scrollHeight;
    let map = scrollMapRef.current;
    if (!map || map.anchors !== anchors || map.doc !== g.doc || map.height !== g.height
        || map.previewHeight !== previewHeight || map.offset !== offset) {
      const points = scrollAnchors(anchors.map((a) => [g.lineTop(a.line + offset), a.top]), g.height, previewHeight);
      map = { anchors, doc: g.doc, height: g.height, previewHeight, offset, points };
      scrollMapRef.current = map;
    }
    return {
      anchors: map.points,
      readingLine: readingLineRef.current,
      editorMax: g.maxScroll,
      editorWindow: g.height - g.maxScroll,
      previewMax: Math.max(0, previewHeight - preview.clientHeight),
    };
  }, []);
  // The preview block holding the caret, lightly marked; left alone while it stays marked.
  const caretBlockRef = useRef(null);
  const markCaretBlock = useCallback((el) => {
    const prev = caretBlockRef.current;
    if (prev === el && (!el || el.classList.contains(PREVIEW_CARET_CLASS))) return;
    if (prev && prev !== el) prev.classList.remove(PREVIEW_CARET_CLASS);
    if (el) el.classList.add(PREVIEW_CARET_CLASS);
    caretBlockRef.current = el;
  }, []);
  // Where the sync last scrolled each pane, while that pane stays there (isEcho).
  const previewEchoRef = useRef(null);
  const editorEchoRef = useRef(null);
  // Set for the frame after the preview scrolled the editor. CodeMirror measures the lines it scrolled to in that frame
  // and moves its scroll to keep them in place, so the editor reports a place the sync did not give it: the reader is
  // moving the preview, and the editor is put back in line with it (alignEditor) rather than the preview moved.
  const editorSettlingRef = useRef(false);
  // Preview -> editor, through the same map: the editor to the place the preview's scroll maps to. `settle` arms the
  // correction above for this write.
  const alignEditor = useCallback((editor, g, preview, { settle = false } = {}) => {
    const pane = paneFor(g, preview, previewBlocks().anchors, frontmatterOffsetLines(bodyRef.current));
    const target = Math.max(0, Math.round(editorScrollFor(preview.scrollTop, pane)));
    if (Math.abs(g.scrollTop - target) < 1) {
      editorEchoRef.current = g.scrollTop;
      return;
    }
    const landed = editor.setScrollTop(target);
    editorEchoRef.current = typeof landed === 'number' ? landed : target;
    if (!settle) return;
    editorSettlingRef.current = true;
    requestAnimationFrame(() => requestAnimationFrame(() => { editorSettlingRef.current = false; }));
  }, [paneFor, previewBlocks, bodyRef]);
  const pendingSyncRef = useRef({ caretMoved: false, force: false });
  // `caretMoved` makes the caret's height in the window the reading line, when the caret is in the window. Calls
  // within one frame share one sync. The editor reporting the sync's own scroll is not synced back, unless the caret
  // moved too or `force` asks (a re-rendered preview).
  const syncPreview = useCallback(({ caretMoved = false, force = false } = {}) => {
    if (clickSyncGuardRef.current) return;
    const pending = pendingSyncRef.current;
    pending.caretMoved ||= caretMoved;
    pending.force ||= force;
    if (syncRafRef.current) return;
    syncRafRef.current = requestAnimationFrame(() => {
      syncRafRef.current = 0;
      const { caretMoved: moved, force: forced } = pendingSyncRef.current;
      pendingSyncRef.current = { caretMoved: false, force: false };
      if (clickSyncGuardRef.current) return;
      const preview = previewRef.current;
      const g = preview ? editorRef.current?.scrollGeometry?.() : null;
      if (!g) return;
      const echo = isEcho(editorEchoRef, g.scrollTop);
      const settling = editorSettlingRef.current;
      editorSettlingRef.current = false;
      const caretInWindow = g.caret.top - g.scrollTop;
      if (moved && caretInWindow >= 0 && caretInWindow < g.height - g.maxScroll) readingLineRef.current = caretInWindow;
      const offset = frontmatterOffsetLines(bodyRef.current);
      const blocks = previewBlocks();
      markCaretBlock(caretBlock(blocks.byLine, g.caret.line - offset));
      // The editor is where the sync put it, or the reader is scrolling the preview this frame: the editor follows
      // the preview, not the other way round.
      // A re-rendered preview (`forced`) does not change that: the reader still put the preview where it is.
      if ((echo && !moved && !forced) || editorRafRef.current) return;
      if (settling && !moved) {
        alignEditor(editorRef.current, g, preview);
        return;
      }
      const target = Math.max(0, Math.round(previewScrollFor(g.scrollTop, paneFor(g, preview, blocks.anchors, offset))));
      if (Math.abs(preview.scrollTop - target) < 1) return;
      preview.scrollTop = target;
      previewEchoRef.current = preview.scrollTop;
    });
  }, [previewBlocks, paneFor, markCaretBlock, alignEditor, clickSyncGuardRef, previewRef, editorRef, bodyRef]);

  // Preview -> editor: the same, the other way: level at the reading line, through the same blocks; the end follows the end.
  const syncEditor = useCallback(() => {
    const preview = previewRef.current;
    if (!preview || clickSyncGuardRef.current) return;
    if (isEcho(previewEchoRef, preview.scrollTop) || editorRafRef.current) return;
    editorRafRef.current = requestAnimationFrame(() => {
      editorRafRef.current = 0;
      const editor = editorRef.current;
      const shown = previewRef.current;
      const g = shown && editor?.setScrollTop ? editor.scrollGeometry?.() : null;
      if (g) alignEditor(editor, g, shown, { settle: true });
    });
  }, [alignEditor, clickSyncGuardRef, previewRef, editorRef]);

  useEffect(() => () => {
    if (syncRafRef.current) cancelAnimationFrame(syncRafRef.current);
    if (editorRafRef.current) cancelAnimationFrame(editorRafRef.current);
  }, []);

  const resetScrollMap = useCallback((previewShown) => {
    anchorCacheRef.current = null;
    if (!previewShown) {
      caretBlockRef.current = null;
      scrollMapRef.current = null;
      previewEchoRef.current = null;
    }
  }, []);

  const prepareClickJump = useCallback((offset) => {
    if (syncRafRef.current) {
      cancelAnimationFrame(syncRafRef.current);
      syncRafRef.current = 0;
    }
    // The caret lands level with the clicked block, so that height is the reading line from here on.
    readingLineRef.current = Math.max(0, offset);
    pendingSyncRef.current.caretMoved = false;
  }, []);

  return { syncPreview, syncEditor, previewBlocks, resetScrollMap, prepareClickJump };
}
