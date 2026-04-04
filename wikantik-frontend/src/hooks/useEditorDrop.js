import { useEffect } from 'react';

export function useEditorDrop(textareaRef, onInsert) {
  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;

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

      // Determine insertion position from drop coordinates
      let insertPos;
      if (document.caretPositionFromPoint) {
        const pos = document.caretPositionFromPoint(e.clientX, e.clientY);
        if (pos && pos.offsetNode === textarea) {
          insertPos = pos.offset;
        }
      } else if (document.caretRangeFromPoint) {
        const range = document.caretRangeFromPoint(e.clientX, e.clientY);
        if (range) {
          insertPos = range.startOffset;
        }
      }

      // Fallback: insert at current cursor position
      if (insertPos === undefined) {
        insertPos = textarea.selectionStart;
      }

      onInsert(text, insertPos);
    };

    textarea.addEventListener('dragover', handleDragOver);
    textarea.addEventListener('drop', handleDrop);
    return () => {
      textarea.removeEventListener('dragover', handleDragOver);
      textarea.removeEventListener('drop', handleDrop);
    };
  }, [textareaRef, onInsert]);
}
