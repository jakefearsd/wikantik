import { forwardRef, useImperativeHandle, useRef, useMemo, useCallback } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { markdown } from '@codemirror/lang-markdown';
import { EditorView, keymap } from '@codemirror/view';
import { Prec } from '@codemirror/state';
import { autocompletion } from '@codemirror/autocomplete';
import { createWikiLinkSource } from '../utils/wikiLinkComplete';

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
 *
 * Props:
 *   value       string            current document text
 *   onChange    (text) => void    called on every edit
 *   dark        boolean           dark-mode theme toggle
 *   onSave      () => void        Mod-s handler (preventDefault'd, save)
 *   onBold      () => void        Mod-b handler
 *   onItalic    () => void        Mod-i handler
 *   onLink      () => void        Mod-k handler
 *   className   string            applied to the wrapping div
 *   'data-testid' string         applied to the wrapping div
 */
const CodeEditor = forwardRef(function CodeEditor(
  { value, onChange, dark = false, onSave, onBold, onItalic, onLink, getLinkCompletions, onViewChange, className, ...rest },
  ref,
) {
  const viewRef = useRef(null);

  // `[[`-triggered internal-link autocomplete. The getter is held in a ref so
  // the completion source — built once below — always reads the latest page
  // list without forcing the editor to reconfigure.
  const linkCompletionsRef = useRef(getLinkCompletions);
  linkCompletionsRef.current = getLinkCompletions;

  // Fired on scroll / caret move / edit so the parent can sync the preview.
  // Held in a ref so the extension (built once) always calls the latest handler.
  const onViewChangeRef = useRef(onViewChange);
  onViewChangeRef.current = onViewChange;

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
      view.focus();
      view.dispatch({ selection: { anchor: from, head: to } });
    },
    focus() {
      viewRef.current?.focus();
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
      let topLine = 1;
      try {
        const block = view.lineBlockAtHeight(view.scrollDOM.scrollTop);
        topLine = view.state.doc.lineAt(block.from).number;
      } catch {
        // best-effort sync — fall back to the top of the document
        topLine = 1;
      }
      return { topLine, totalLines: view.state.doc.lines };
    },
    /** Scroll the editor so `line` (1-based) sits at the top — preview→editor sync. */
    scrollToLine(line) {
      const view = viewRef.current;
      if (!view) return;
      const total = view.state.doc.lines;
      const clamped = Math.max(1, Math.min(Math.round(line), total));
      const pos = view.state.doc.line(clamped).from;
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
      override: [createWikiLinkSource(() => (linkCompletionsRef.current ? linkCompletionsRef.current() : []))],
    }),
    [],
  );

  const extensions = useMemo(
    () => [markdown(), EditorView.lineWrapping, shortcutKeymap, wikiLinkAutocomplete, syncExtension],
    [shortcutKeymap, wikiLinkAutocomplete, syncExtension],
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
          foldGutter: false,
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
