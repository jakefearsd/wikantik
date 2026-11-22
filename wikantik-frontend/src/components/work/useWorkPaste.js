import { useCallback, useMemo } from 'react';
import { uploadAttachment } from '../../api/work';
import { useAttachmentUpload } from '../../hooks/useAttachmentUpload';
import { dragCarriesFiles, filesFromDrop, filesFromPaste } from '../../utils/editorFileEvents';
import { limitsFor, noteUploaded, sizeProblem, uploadErrorText } from './attachmentErrors';

const NO_TOAST = { error: () => {} };
const NO_NAMES = [];
// The server answers the text to insert (an image token for the four inline types, a link otherwise).
const markup = (name, isImage, uploaded) => uploaded.markdown;
// The server said this caller may not attach: leave the textarea's own paste and drop behaviour alone.
const isBlocked = (attach) => !attach || limitsFor(attach.itemKey)?.mayAttach === false;
const caret = (e) => e.target?.selectionStart ?? e.target?.value?.length ?? 0;

/**
 * Paste or drop files into item text or a comment: each file uploads as an attachment of the item (of the comment,
 * with `commentId`) and its `work-attachment:` markdown replaces an "Uploading" placeholder at the caret. `attach`
 * is `{ itemKey, toast, onUploaded }` or null (then nothing is intercepted and the textarea behaves as usual).
 * `setText` is a functional setter of the text being edited.
 */
export function useWorkPaste({ attach, commentId = null, setText }) {
  const itemKey = attach?.itemKey;
  const onUploaded = attach?.onUploaded;
  const upload = useCallback(async (file) => {
    const tooBig = sizeProblem(file, itemKey);
    if (tooBig) throw new Error(tooBig);
    try {
      const uploaded = await uploadAttachment(itemKey, file, commentId);
      noteUploaded(itemKey, file.size);
      onUploaded?.();
      return uploaded;
    } catch (e) {
      throw new Error(uploadErrorText(e, null, itemKey), { cause: e });
    }
  }, [itemKey, commentId, onUploaded]);
  const { uploadFiles } = useAttachmentUpload({
    existingNames: NO_NAMES, upload, updateBody: setText, toast: attach?.toast ?? NO_TOAST, markup,
  });

  const onPaste = useCallback((e) => {
    if (isBlocked(attach)) return undefined;
    const files = filesFromPaste(e.clipboardData);
    if (files.length === 0) return undefined;
    e.preventDefault();
    return uploadFiles(files, caret(e), { pasted: true });
  }, [attach, uploadFiles]);
  const onDrop = useCallback((e) => {
    if (isBlocked(attach)) return undefined;
    const files = filesFromDrop(e.dataTransfer);
    if (files.length === 0) return undefined;
    e.preventDefault();
    return uploadFiles(files, caret(e), { pasted: false });
  }, [attach, uploadFiles]);
  const onDragOver = useCallback((e) => {
    if (!isBlocked(attach) && dragCarriesFiles(e.dataTransfer)) e.preventDefault();
  }, [attach]);

  return useMemo(() => ({ onPaste, onDrop, onDragOver }), [onPaste, onDrop, onDragOver]);
}
