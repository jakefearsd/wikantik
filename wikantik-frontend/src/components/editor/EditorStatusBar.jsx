import { useMemo } from 'react';
import { readingTime } from '../../utils/readingTime';
import { useEditorCursor } from '../../hooks/useEditorCursor';

const fmt = (n) => n.toLocaleString('en-US');

export default function EditorStatusBar({ body, cursorStore }) {
  const { line, col, selectionText } = useEditorCursor(cursorStore);
  const { words, minutes } = useMemo(() => readingTime(body), [body]);
  const selected = selectionText ? readingTime(selectionText).words : 0;
  return (
    <div className="editor-status-bar" data-testid="editor-status-bar">
      <span data-testid="status-words">{selected ? `${fmt(selected)} of ${fmt(words)} words` : `${fmt(words)} words`}</span>
      <span aria-hidden="true">·</span>
      <span>{minutes} min read</span>
      <span aria-hidden="true">·</span>
      <span>Ln {line}, Col {col}</span>
    </div>
  );
}
