import { useEffect } from 'react';

/**
 * Wire up drag-and-drop text insertion on an editor container element.
 *
 * @param {React.RefObject<HTMLElement>} containerRef — element to attach
 *        dragover/drop listeners to (e.g. the editor pane wrapping CodeMirror).
 * @param {(text: string, pos: number) => void} onInsert — insert `text` at offset `pos`.
 * @param {() => number} [getOffset] — returns the current caret character offset
 *        in the document. Used as the insertion position when the drop point
 *        cannot be mapped to a precise offset (CodeMirror has no selectionStart).
 */
export function useEditorDrop(containerRef, onInsert, getOffset) {
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const handleDragOver = (e) => {
      if (e.dataTransfer.types.includes('text/plain')) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'copy';
      }
    };

    const handleDrop = (e) => {
      const text = e.dataTransfer.getData('text/plain');
      if (!text) return;
      e.preventDefault();

      // Determine insertion position from the current caret offset.
      // (CodeMirror manages its own contenteditable, so caretPositionFromPoint
      // does not give us a usable document offset; fall back to the live caret.)
      let insertPos;
      if (typeof getOffset === 'function') {
        insertPos = getOffset();
      }
      if (insertPos === undefined || insertPos === null) {
        insertPos = 0;
      }

      onInsert(text, insertPos);
    };

    container.addEventListener('dragover', handleDragOver);
    container.addEventListener('drop', handleDrop);
    return () => {
      container.removeEventListener('dragover', handleDragOver);
      container.removeEventListener('drop', handleDrop);
    };
  }, [containerRef, onInsert, getOffset]);
}
