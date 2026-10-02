import { forwardRef, useImperativeHandle, useRef, useMemo, useCallback } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { markdown } from '@codemirror/lang-markdown';
import { languages } from '@codemirror/language-data';
import { EditorView, keymap } from '@codemirror/view';
import { Prec } from '@codemirror/state';
import { autocompletion } from '@codemirror/autocomplete';
import { foldAll, unfoldAll } from '@codemirror/language';
import { isolateHistory } from '@codemirror/commands';
import { frontmatterFold, revealEffects } from '../utils/markdownFold';
import { editorMarkdownConfig } from '../utils/editorMarkdown';
import { calloutMarkers } from '../utils/calloutMarkers';
import { editorChrome } from '../utils/editorTheme';
import { createWikiLinkSource } from '../utils/wikiLinkComplete';
import { filesFromPaste, filesFromDrop } from '../utils/editorFileEvents';
import { linkInteraction } from '../utils/linkInteraction';

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
 *   getText()                   -> the live document text, or null without a view
 *   getSelection()              -> { selStart, selEnd } character offsets
 *   setSelection(start, end)    -> set selection + focus the editor
 *   focus()                     -> focus the editor
 *   insertText(pos, text)       -> insert via a CodeMirror transaction; false if no view
 *   replaceText(find, repl)     -> replace first exact match via a transaction; false if no view
 *   applyEdit(text, start, end) -> make the document `text` with selection [start, end] in one transaction
 *   applyChanges(changes)       -> apply non-overlapping [{from, to, insert}] (all against the current doc) as one
 *                                  isolated undo step, the selection mapped through them; false if no view
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
function reveal(view, ...positions) {
  if (typeof view.state.field !== 'function') return;
  const effects = positions.flatMap((pos) => revealEffects(view.state, pos));
  if (effects.length > 0) view.dispatch({ effects });
}
const isHighSurrogate = (code) => code >= 0xD800 && code <= 0xDBFF;
const isLowSurrogate = (code) => code >= 0xDC00 && code <= 0xDFFF;

/**
 * The single change turning `prev` into `next`: their common prefix and suffix are left alone. The boundaries never
 * split a CRLF pair or a UTF-16 surrogate pair: a change ending in a lone `\r` (an LF document diffed against CRLF
 * text) would make CodeMirror insert an extra line break.
 */
export function minimalChange(prev, next) {
  let from = 0;
  const max = Math.min(prev.length, next.length);
  while (from < max && prev[from] === next[from]) from += 1;
  // The prefix is identical in both strings, so checking `prev` is enough.
  while (from > 0 && (prev[from - 1] === '\r' || isHighSurrogate(prev.charCodeAt(from - 1)))) from -= 1;
  let tail = 0;
  while (tail < max - from && prev[prev.length - 1 - tail] === next[next.length - 1 - tail]) tail += 1;
  // The suffix must not start on the \n of a CRLF pair (in either string) or on a low surrogate.
  for (;;) {
    if (tail === 0) break;
    const p = prev.length - tail;
    const n = next.length - tail;
    const startsInsidePair = (prev[p] === '\n' && (prev[p - 1] === '\r' || next[n - 1] === '\r'))
      || isLowSurrogate(prev.charCodeAt(p));
    if (!startsInsidePair) break;
    tail -= 1;
  }
  return { from, to: prev.length - tail, insert: next.slice(from, next.length - tail) };
}

const CodeEditor = forwardRef(function CodeEditor(
  { value, onChange, dark = false, onSave, onBold, onItalic, onLink, linkCompletion, slashSource, onLinkHover, wikiLinkResolution, onViewChange, onFiles, className, ...rest },
  ref,
) {
  const viewRef = useRef(null);

  // `[[` / `](` link autocomplete. The sources are held in a ref so the completion
  // source — built once below — always calls the latest ones without reconfiguring the editor.
  const linkCompletionRef = useRef(linkCompletion);
  linkCompletionRef.current = linkCompletion;
  const slashSourceRef = useRef(slashSource);
  slashSourceRef.current = slashSource;
  const onLinkHoverRef = useRef(onLinkHover);
  onLinkHoverRef.current = onLinkHover;
  // Resolved wikilink targets (lowercased raw target -> page name | null); read live by the link interaction.
  const wikiLinkResolutionRef = useRef(wikiLinkResolution);
  wikiLinkResolutionRef.current = wikiLinkResolution;

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
    getText() {
      return viewRef.current ? viewRef.current.state.doc.toString() : null;
    },
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
      reveal(view, from, to);
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
     * Replace the character range [from, to) with `text` in one normal (non-external) transaction, so
     * it is a single undoable edit and onChange fires. Returns false when there is no view.
     */
    replaceRange(from, to, text) {
      const view = viewRef.current;
      if (!view) return false;
      const len = view.state.doc.length;
      const a = Math.min(from, len);
      const b = Math.min(to, len);
      reveal(view, a, b); // never edit text hidden inside a fold
      view.dispatch({ changes: { from: a, to: b, insert: text } });
      return true;
    },
    /**
     * Turn the document into `text` with the selection [selStart, selEnd], as ONE normal transaction (only the
     * changed span is replaced). Editor commands must use this rather than the `value` prop: react-codemirror
     * defers a `value` change that arrives within its typing latch and later replays it over newer keystrokes.
     * Returns false when there is no view.
     */
    applyEdit(text, selStart, selEnd) {
      const view = viewRef.current;
      if (!view) return false;
      const len = text.length;
      const anchor = Math.max(0, Math.min(selStart, len));
      const head = Math.max(0, Math.min(selEnd, len));
      reveal(view, Math.min(anchor, view.state.doc.length));
      view.dispatch({ changes: minimalChange(view.state.doc.toString(), text), selection: { anchor, head } });
      view.focus();
      return true;
    },
    /**
     * Apply `changes` — `{ from, to, insert }` ranges that all refer to the CURRENT document — as one normal
     * transaction: onChange fires once and the caret/selection is mapped through the edits, so a background edit
     * (attachment rename, conversion, draft or conflict reload) never moves the user's caret or races
     * react-codemirror's typing latch the way a `value` change does. Positions are clamped to the document and a
     * reversed range is ordered, and CRLF / lone CR in inserted text become LF (the document is always LF).
     * Changes must NOT overlap: after sorting by position, a change that starts inside an earlier one is skipped
     * with a console warning (the rest still apply). The edit is its own undo step (never merged with adjacent
     * typing). It neither focuses the editor nor reveals folds. Returns false when there is no view.
     */
    applyChanges(changes) {
      const view = viewRef.current;
      if (!view) return false;
      const len = view.state.doc.length;
      const clamp = (pos) => Math.max(0, Math.min(pos ?? 0, len));
      const sorted = (changes || []).map((c) => {
        const a = clamp(c.from);
        const b = clamp(c.to ?? c.from);
        return { from: Math.min(a, b), to: Math.max(a, b), insert: String(c.insert ?? '').replace(/\r\n?/g, '\n') };
      }).sort((x, y) => x.from - y.from || x.to - y.to);
      const spec = [];
      for (const c of sorted) {
        const prev = spec[spec.length - 1];
        if (prev && c.from < prev.to) {
          console.warn('[editor] applyChanges: skipping a change that overlaps an earlier one', c, prev);
          continue;
        }
        spec.push(c);
      }
      if (spec.length === 0) return true;
      view.dispatch({ changes: spec, annotations: isolateHistory.of('full') });
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
    /**
     * Scroll the editor so `line` (1-based) sits at the top — preview→editor sync. Reveals the target's
     * folds by default; passive scroll sync passes `{ reveal: false }` so folds survive.
     */
    scrollToLine(line, { reveal: doReveal = true } = {}) {
      const view = viewRef.current;
      if (!view) return;
      const total = view.state.doc.lines;
      const clamped = Math.max(1, Math.min(Math.round(line), total));
      const pos = view.state.doc.line(clamped).from;
      if (doReveal) reveal(view, pos);
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

  const linkExtension = useMemo(
    () => linkInteraction({
      onHover: (url, rect) => onLinkHoverRef.current?.(url, rect),
      resolve: (key) => wikiLinkResolutionRef.current?.get(key),
    }),
    [],
  );

  const extensions = useMemo(
    () => [
      // Folds: headings, the frontmatter block and fenced code only (editorFoldConfig + frontmatterFold).
      markdown({ ...editorMarkdownConfig, codeLanguages: languages }),
      EditorView.lineWrapping, shortcutKeymap, wikiLinkAutocomplete, syncExtension, fileDropExtension, linkExtension,
      frontmatterFold, calloutMarkers, editorChrome,
    ],
    [shortcutKeymap, wikiLinkAutocomplete, syncExtension, fileDropExtension, linkExtension],
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
