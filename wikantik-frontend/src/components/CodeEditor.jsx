import { forwardRef, useImperativeHandle, useRef, useMemo, useCallback } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { markdown } from '@codemirror/lang-markdown';
import { EditorView, keymap } from '@codemirror/view';
import { Prec } from '@codemirror/state';

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
  { value, onChange, dark = false, onSave, onBold, onItalic, onLink, className, ...rest },
  ref,
) {
  const viewRef = useRef(null);

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
  }), []);

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

  const extensions = useMemo(
    () => [markdown(), EditorView.lineWrapping, shortcutKeymap],
    [shortcutKeymap],
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
