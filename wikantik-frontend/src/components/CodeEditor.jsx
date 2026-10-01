import { forwardRef, useImperativeHandle, useRef, useMemo, useCallback } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { markdown } from '@codemirror/lang-markdown';
import { languages } from '@codemirror/language-data';
import { EditorView, keymap } from '@codemirror/view';
import { Prec } from '@codemirror/state';
import { autocompletion } from '@codemirror/autocomplete';
import { foldAll, unfoldAll } from '@codemirror/language';
import { frontmatterFold, revealEffects } from '../utils/markdownFold';
import { createWikiLinkSource } from '../utils/wikiLinkComplete';
import { filesFromPaste, filesFromDrop } from '../utils/editorFileEvents';

/**
 * #19 — CodeMirror 6 markdown source editor.
 *
 * A thin wrapper around @uiw/react-codemirror that:
 *   - renders the markdown language with line wrapping,
 *   - is controlled by the parent via `value` / `onChange` (parent owns state),
 *   - respects the app's dark mode via the `dark` prop,
 *   - registers a high-precedence keymap for Mod-s / Mod-b / Mod-i / Mod-k that
 *     delegates back to parent callbacks (CodeMirror otherwise swallows these), and
 *   - exposes an imperative handle for character-offset selection used by the
 *     formatting toolbar and drag-and-drop insertion.
 *
 * Imperative API (via ref):
 *   getSelection()              -> { selStart, selEnd } character offsets
 *   setSelection(start, end)    -> set selection + focus the editor
 *   focus()                     -> focus the editor
 *   insertText(pos, text)       -> insert via a CodeMirror transaction; false if no view
 *   replaceText(find, repl)     -> replace first exact match via a transaction; false if no view
 *
 * Props:
 *   value       string            current document text
 *   onChange    (text) => void    called on every edit
 *   dark        boolean           dark-mode theme toggle
 *   onSave      () => void        Mod-s handler (preventDefault'd, save)
 *   onBold      () => void        Mod-b handler
 *   onItalic    () => void        Mod-i handler
 *   onLink      () => void        Mod-k handler
 *   slashSource (ctx) => CompletionResult|null  slash-command completion source (optional)
 *   linkCompletion { searchPages(q), getHeadings(page|null), getAttachmentNames() }  link autocomplete sources
 *   onFiles     (files, pos, { pasted }) => void   pasted/dropped OS files (e.g. images to upload)
 *   className   string            applied to the wrapping div
 *   'data-testid' string         applied to the wrapping div
 */
/**
 * Reveal rule (R9): any programmatic cursor/scroll jump unfolds the fold containing its target first.
 * No-op when the view has no fold state (e.g. the textarea stub in tests).
 */
function reveal(view, pos) {
  if (typeof view.state.field !== 'function') return;
  const effects = revealEffects(view.state, pos);
  if (effects.length > 0) view.dispatch({ effects });
}

const CodeEditor = forwardRef(function CodeEditor(
  { value, onChange, dark = false, onSave, onBold, onItalic, onLink, linkCompletion, slashSource, onViewChange, onFiles, className, ...rest },
  ref,
) {
  const viewRef = useRef(null);

  // `[[` / `](` link autocomplete. The sources are held in a ref so the completion
  // source — built once below — always calls the latest ones without reconfiguring the editor.
  const linkCompletionRef = useRef(linkCompletion);
  linkCompletionRef.current = linkCompletion;
  const slashSourceRef = useRef(slashSource);
  slashSourceRef.current = slashSource;

  // Fired on scroll / caret move / edit so the parent can sync the preview.
  // Held in a ref so the extension (built once) always calls the latest handler.
  const onViewChangeRef = useRef(onViewChange);
  onViewChangeRef.current = onViewChange;

  // Pasted / dropped files. Held in a ref like the other callbacks so the extension is built once.
  const onFilesRef = useRef(onFiles);
  onFilesRef.current = onFiles;
  const fileDropExtension = useMemo(() => EditorView.domEventHandlers({
    paste(event, view) {
      const files = filesFromPaste(event.clipboardData);
      if (!files.length || !onFilesRef.current) return false;
      event.preventDefault();
      onFilesRef.current(files, view.state.selection.main.head, { pasted: true });
      return true;
    },
    drop(event, view) {
      const files = filesFromDrop(event.dataTransfer);
      if (!files.length || !onFilesRef.current) return false;
      event.preventDefault();
      const pos = view.posAtCoords({ x: event.clientX, y: event.clientY }) ?? view.state.selection.main.head;
      onFilesRef.current(files, pos, { pasted: false });
      return true;
    },
  }), []);

  const handleCreateEditor = useCallback((view) => {
    viewRef.current = view;
  }, []);

  useImperativeHandle(ref, () => ({
    getSelection() {
      const view = viewRef.current;
      if (!view) return { selStart: 0, selEnd: 0 };
      const range = view.state.selection.main;
      return { selStart: range.from, selEnd: range.to };
    },
    setSelection(selStart, selEnd) {
      const view = viewRef.current;
      if (!view) return;
      const len = view.state.doc.length;
      const from = Math.max(0, Math.min(selStart, len));
      const to = Math.max(0, Math.min(selEnd, len));
      reveal(view, from);
      reveal(view, to);
      view.focus();
      view.dispatch({ selection: { anchor: from, head: to } });
    },
    focus() {
      viewRef.current?.focus();
    },
    /** Fold every foldable heading / frontmatter block. */
    foldAll() {
      const view = viewRef.current;
      if (view) foldAll(view);
    },
    unfoldAll() {
      const view = viewRef.current;
      if (view) unfoldAll(view);
    },
    /**
     * The 1-based source line currently at the top of the editor viewport, plus
     * the document line count — drives editor→preview scroll sync. Using the
     * top-visible line follows both manual scrolling and typing (CodeMirror keeps
     * the caret in view, so the caret's line stays within the viewport).
     */
    getViewport() {
      const view = viewRef.current;
      if (!view) return null;
      let topLine;
      try {
        const block = view.lineBlockAtHeight(view.scrollDOM.scrollTop);
        topLine = view.state.doc.lineAt(block.from).number;
      } catch {
        // best-effort sync — fall back to the top of the document
        topLine = 1;
      }
      return { topLine, totalLines: view.state.doc.lines };
    },
    /**
     * Insert `text` at character offset `pos`. A normal (non-external) transaction, so onChange fires and
     * the parent's state follows; going through the view also sidesteps react-codemirror's typing latch,
     * which defers external `value` changes and can overwrite them. Returns false when there is no view.
     */
    insertText(pos, text) {
      const view = viewRef.current;
      if (!view) return false;
      const at = Math.max(0, Math.min(pos, view.state.doc.length));
      view.dispatch({ changes: { from: at, insert: text } });
      return true;
    },
    /**
     * Replace the first exact occurrence of `find` with `replacement` (literal, like String.replace with
     * a string pattern but without `$` expansion). A missing `find` is a handled no-op. Returns false
     * when there is no view.
     */
    replaceText(find, replacement) {
      const view = viewRef.current;
      if (!view) return false;
      const at = view.state.sliceDoc(0, view.state.doc.length).indexOf(find);
      if (at >= 0) view.dispatch({ changes: { from: at, to: at + find.length, insert: replacement } });
      return true;
    },
    /** Caret position (1-based line/column) and the selected text — drives the status bar. */
    getCursor() {
      const view = viewRef.current;
      if (!view) return null;
      const { from, to, head } = view.state.selection.main;
      const lineObj = view.state.doc.lineAt(head);
      return { line: lineObj.number, col: head - lineObj.from + 1, selectionText: view.state.sliceDoc(from, to) };
    },
    /** Scroll the editor so `line` (1-based) sits at the top — preview→editor sync. */
    scrollToLine(line) {
      const view = viewRef.current;
      if (!view) return;
      const total = view.state.doc.lines;
      const clamped = Math.max(1, Math.min(Math.round(line), total));
      const pos = view.state.doc.line(clamped).from;
      reveal(view, pos);
      try {
        view.scrollDOM.scrollTop = view.lineBlockAt(pos).top;
      } catch {
        // best-effort sync — leave the scroll position unchanged
      }
    },
    /**
     * Returns the bounding rect of the CM6 scroll container (`.cm-scroller`).
     * Used by the caller to compute viewport-relative offsets that account for
     * any gap between the editor scroller's top and the preview container's top.
     */
    getScrollerRect() {
      return viewRef.current?.scrollDOM?.getBoundingClientRect() ?? null;
    },
    /**
     * Place the caret at the start of `line` (1-based), focus, and scroll so the
     * line's top sits `viewportOffset` px below the top of the editor's scroll
     * container (align, NOT center).
     *
     * Two-step alignment: first uses `lineBlockAt` (estimated) to bring the line
     * into the viewport, then refines with `coordsAtPos` (actual rendered position)
     * in the next animation frame — so the alignment is exact even when CM6's block
     * height estimate differs from the rendered height (e.g. for lines outside the
     * current render window).
     *
     * @param {number} line            1-based source line number
     * @param {number} viewportOffset  desired px from the scroller top to the line
     */
    jumpToLineAligned(line, viewportOffset) {
      const view = viewRef.current;
      if (!view) return;
      const total = view.state.doc.lines;
      const clamped = Math.max(1, Math.min(Math.round(line), total));
      const pos = view.state.doc.line(clamped).from;
      reveal(view, pos);
      view.focus();
      view.dispatch({ selection: { anchor: pos } }); // caret only — no scrollIntoView
      try {
        // Step 1 (synchronous): rough scroll using estimated position so the line
        // enters the CM6 rendered viewport.
        const roughTarget = view.lineBlockAt(pos).top - (viewportOffset || 0);
        view.scrollDOM.scrollTop = Math.max(0, roughTarget);
        // Step 2 (async, next rAF): after CM6 renders the line into the viewport,
        // refine the scroll using the actual rendered position from coordsAtPos.
        const scrollDom = view.scrollDOM;
        requestAnimationFrame(() => {
          try {
            const coords = view.coordsAtPos(pos);
            if (coords) {
              const scrollerTop = scrollDom.getBoundingClientRect().top;
              const lineDocTop = coords.top - scrollerTop + scrollDom.scrollTop;
              scrollDom.scrollTop = Math.max(0, lineDocTop - (viewportOffset || 0));
            }
          } catch {
            // best-effort — leave at the rough position
          }
        });
      } catch {
        // best-effort — leave the scroll position unchanged
      }
    },
  }), []);

  // Notify the parent on scroll, caret move, or edit (so it can sync the preview).
  const syncExtension = useMemo(() => [
    EditorView.domEventHandlers({ scroll() { onViewChangeRef.current?.(); return false; } }),
    EditorView.updateListener.of((u) => {
      if (u.selectionSet || u.docChanged) onViewChangeRef.current?.();
    }),
  ], []);

  // High-precedence keymap so our shortcuts win over CodeMirror's defaults
  // (e.g. Mod-s is normally undefined but the browser-level Save dialog would
  // otherwise fire; Mod-i/b have no default CM6 binding but we keep parity).
  const shortcutKeymap = useMemo(() => {
    const run = (cb) => () => {
      if (cb) {
        cb();
        return true; // handled — stop further processing
      }
      return false;
    };
    return Prec.highest(
      keymap.of([
        { key: 'Mod-s', preventDefault: true, run: run(onSave) },
        { key: 'Mod-b', preventDefault: true, run: run(onBold) },
        { key: 'Mod-i', preventDefault: true, run: run(onItalic) },
        { key: 'Mod-k', preventDefault: true, run: run(onLink) },
      ]),
    );
  }, [onSave, onBold, onItalic, onLink]);

  const wikiLinkAutocomplete = useMemo(
    () => autocompletion({
      override: [createWikiLinkSource({
        searchPages: (q) => linkCompletionRef.current?.searchPages(q) ?? Promise.resolve([]),
        getHeadings: (page) => linkCompletionRef.current?.getHeadings(page) ?? Promise.resolve([]),
        getAttachmentNames: () => linkCompletionRef.current?.getAttachmentNames?.() ?? [],
      }), (ctx) => slashSourceRef.current?.(ctx) ?? null],
    }),
    [],
  );

  const extensions = useMemo(
    () => [markdown({ codeLanguages: languages }), EditorView.lineWrapping, shortcutKeymap, wikiLinkAutocomplete, syncExtension, fileDropExtension, frontmatterFold],
    [shortcutKeymap, wikiLinkAutocomplete, syncExtension, fileDropExtension],
  );

  return (
    <div className={className} {...rest}>
      <CodeMirror
        value={value}
        onChange={onChange}
        onCreateEditor={handleCreateEditor}
        extensions={extensions}
        theme={dark ? 'dark' : 'light'}
        basicSetup={{
          lineNumbers: false,
          foldGutter: true,
          highlightActiveLine: false,
          highlightActiveLineGutter: false,
          autocompletion: false,
        }}
        height="100%"
        style={{ height: '100%', fontSize: '0.9rem' }}
      />
    </div>
  );
});

export default CodeEditor;
