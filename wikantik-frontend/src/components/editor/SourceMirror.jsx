import { memo, useEffect, useRef } from 'react';
import { EditorView, Decoration, ViewPlugin } from '@codemirror/view';
import { EditorState, StateEffect, StateField, RangeSetBuilder } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { syntaxTree } from '@codemirror/language';
import { editorMarkdownConfig } from '../../utils/editorMarkdown';
import { minimalChange } from '../CodeEditor';
import { useEditorCursor } from '../../hooks/useEditorCursor';

/*
 * The page's Markdown source beside the Rich editor: read-only, with the main caret's line highlighted and kept in
 * view. A learning aid, not an editor: it is a bare CodeMirror view rather than a second CodeEditor, so it has no
 * key bindings, completion, paste or drop handling, link interaction, live preview or collab, writes to no cursor
 * store or draft, and never reports a change. Colours come from globals.css (`.editor-source-mirror`).
 */

const setCaretLine = StateEffect.define();
const caretLineDecoration = Decoration.line({ class: 'cm-mirror-caret-line' });

/** The highlighted line number (1-based), clamped to the document when it is drawn. */
const caretLineField = StateField.define({
  create: () => 1,
  update(value, tr) {
    for (const effect of tr.effects) if (effect.is(setCaretLine)) return effect.value;
    return value;
  },
  provide: (field) => EditorView.decorations.compute([field, 'doc'], (state) => (
    Decoration.set([caretLineDecoration.range(caretLineStart(state, state.field(field)))])
  )),
});

function caretLineStart(state, line) {
  return state.doc.line(Math.max(1, Math.min(line, state.doc.lines))).from;
}

const markDecoration = Decoration.mark({ class: 'cm-mirror-mark' });

/** Markdown's own markers (`#`, `**`, `-`, `>`, backticks, link brackets) in the visible lines only. */
function visibleMarks(view) {
  const builder = new RangeSetBuilder();
  const tree = syntaxTree(view.state);
  for (const { from, to } of view.visibleRanges) {
    tree.iterate({
      from,
      to,
      enter(node) {
        if (node.to > node.from && node.name.endsWith('Mark')) builder.add(node.from, node.to, markDecoration);
      },
    });
  }
  return builder.finish();
}

const markerHighlight = ViewPlugin.fromClass(class {
  constructor(view) { this.decorations = visibleMarks(view); }

  update(update) {
    if (update.docChanged || update.viewportChanged || syntaxTree(update.startState) !== syntaxTree(update.state)) {
      this.decorations = visibleMarks(update.view);
    }
  }
}, { decorations: (plugin) => plugin.decorations });

const MIRROR_EXTENSIONS = [
  EditorState.readOnly.of(true),
  EditorView.editable.of(false),
  EditorView.lineWrapping,
  EditorView.contentAttributes.of({ 'aria-label': 'Markdown source (read-only)', 'aria-readonly': 'true', tabindex: '0' }),
  markdown(editorMarkdownConfig),
  markerHighlight,
];

const lineNumberOf = (line) => (Number.isFinite(line) ? Math.max(1, Math.round(line)) : 1);

/**
 * Props:
 *   text  string  the Markdown to show (LF line endings, as the editor document always is)
 *   line  number  the main caret's line in `text`, 1-based; out-of-range values are clamped
 */
function SourceMirror({ text, line }) {
  const hostRef = useRef(null);
  const viewRef = useRef(null);
  const initialRef = useRef({ text, line });

  useEffect(() => {
    const initial = initialRef.current;
    const first = lineNumberOf(initial.line);
    const view = new EditorView({
      parent: hostRef.current,
      state: EditorState.create({
        doc: initial.text ?? '',
        extensions: [...MIRROR_EXTENSIONS, caretLineField.init(() => first)],
      }),
    });
    viewRef.current = view;
    view.dispatch({ effects: EditorView.scrollIntoView(caretLineStart(view.state, first), { y: 'center' }) });
    return () => {
      viewRef.current = null;
      view.destroy();
    };
  }, []);

  // The text arrives on the preview's debounce. One minimal change, so the mirror keeps its scroll position.
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const next = text ?? '';
    const prev = view.state.doc.toString();
    if (prev !== next) view.dispatch({ changes: minimalChange(prev, next) });
  }, [text]);

  // `y: 'nearest'` scrolls only when the line is outside the visible area.
  useEffect(() => {
    const view = viewRef.current;
    const next = lineNumberOf(line);
    if (!view || view.state.field(caretLineField) === next) return;
    view.dispatch({
      effects: [setCaretLine.of(next), EditorView.scrollIntoView(caretLineStart(view.state, next), { y: 'nearest', yMargin: 24 })],
    });
  }, [line]);

  return <div ref={hostRef} className="editor-source-mirror" data-testid="source-mirror" />;
}

const MemoSourceMirror = memo(SourceMirror);
export default MemoSourceMirror;

/**
 * The mirror following the main editor's caret. It reads the caret from the editor's cursor store, so a caret move
 * re-renders this wrapper only (never PageEditor), and the mirror itself only when the line number changes.
 * `lineOffset` is the number of leading document lines `text` leaves out (the frontmatter block).
 */
export function CaretSourceMirror({ text, cursorStore, lineOffset = 0 }) {
  const { line } = useEditorCursor(cursorStore);
  return <MemoSourceMirror text={text} line={(line || 1) - lineOffset} />;
}
