import { useCallback, useEffect, useMemo, useRef } from 'react';
import { pastedImageName, normalizeAttachmentName, uniqueAttachmentName, attachmentMarkup } from '../utils/attachmentNameValidator';

const isImage = (file) => /^image\//.test(file.type || '');

/**
 * Paste/drop upload for the editor. All placeholders are inserted at `pos` up front (one per line),
 * then files upload one at a time; each placeholder becomes the attachment markup, or is removed
 * (and the failure toasted) if its upload fails. With `defer` the uploads wait: the promise resolves to
 * `{ start, cancel }` (placeholders are already in the body).
 *
 * @param {object} o
 * @param {string[]} o.existingNames  attachment names already on the page
 * @param {(file: File, name: string) => Promise<unknown>} o.upload  uploads and refreshes the list
 * @param {(fn: (prev: string) => string) => void} o.updateBody  functional body setter
 * @param {{ insertText: (pos: number, text: string) => boolean, replaceText: (find: string, repl: string) => boolean }} [o.editor]
 *        optional editor adapter: edits are applied as editor transactions so typing cannot clobber them.
 *        A `false` return (no editor view) falls back to `updateBody`.
 * @param {{ error: (msg: string) => void }} o.toast
 */
export function useAttachmentUpload({ existingNames, upload, updateBody, toast, editor }) {
  const namesRef = useRef(existingNames);
  useEffect(() => { namesRef.current = existingNames; });
  // Names claimed by uploads this hook started. The attachment list only learns them after a refresh,
  // so an overlapping paste would otherwise pick the same name and overwrite the earlier upload.
  const reservedRef = useRef(new Set());

  const insertAt = useCallback((pos, text) => {
    if (editor?.insertText(pos, text)) return;
    updateBody((prev) => prev.slice(0, pos) + text + prev.slice(pos));
  }, [editor, updateBody]);
  const replaceOnce = useCallback((find, replacement) => {
    if (editor?.replaceText(find, replacement)) return;
    updateBody((prev) => prev.replace(find, () => replacement));
  }, [editor, updateBody]);

  // Uploads each planned file in turn: its placeholder becomes the attachment markup, or is removed (and the failure
  // toasted) if the upload fails. Shared by the stacked path and the placed path.
  const runPlan = useCallback(async (plan) => {
    for (const { file, name, placeholder } of plan) {
      try {
        // The multipart filename must carry the chosen name's extension (server checks they match).
        await upload(new File([file], name, { type: file.type }), name);
        replaceOnce(placeholder, attachmentMarkup(name, isImage(file)));
      } catch (err) {
        replaceOnce(placeholder, '');
        toast.error(`Upload of ${name} failed: ${err?.message || err}`);
      }
    }
  }, [upload, replaceOnce, toast]);
  const cancelPlan = useCallback((plan) => {
    for (const { name, placeholder } of plan) {
      reservedRef.current.delete(name);
      replaceOnce(placeholder, '');
    }
  }, [replaceOnce]);

  /**
   * Pasted images that already sit at a position in `text` (a `{ file, token }` per image; the token is where the image
   * was in the converted Markdown). Names are picked now (`pasted-image-<stamp>-<n>.<ext>`) and each token is swapped for
   * its `![Uploading name…]()` placeholder, so the returned `text` is what the caller inserts. Nothing uploads until
   * `start()` is called — after the caller has inserted `text` — and `cancel()` (a deferred paste on an unsaved page that
   * is abandoned) removes the placeholders again. Synchronous: returns `{ text, start, cancel }`.
   */
  const uploadPlaced = useCallback((images, text) => {
    const taken = [...(namesRef.current || []), ...reservedRef.current];
    const plan = [];
    let placed = text;
    images.forEach(({ file, token }, i) => {
      const base = pastedImageName(file.type).replace(/^pasted-/, 'pasted-image-').replace(/(\.\w+)$/, `-${i + 1}$1`);
      const name = uniqueAttachmentName(base, taken);
      taken.push(name);
      reservedRef.current.add(name);
      const placeholder = `![Uploading ${name}…]()`;
      plan.push({ file, name, placeholder });
      placed = placed.split(token).join(placeholder);
    });
    return { text: placed, start: () => runPlan(plan), cancel: () => cancelPlan(plan) };
  }, [runPlan, cancelPlan]);

  const uploadFiles = useCallback(async (files, pos, { pasted = false, defer = false } = {}) => {
    const taken = [...(namesRef.current || []), ...reservedRef.current];
    const plan = [];
    for (const file of files) {
      const base = pasted && isImage(file) ? pastedImageName(file.type) : normalizeAttachmentName(file.name);
      if (!base) {
        toast.error(`Cannot upload "${file.name}": it has no usable file name or extension`);
        continue;
      }
      const name = uniqueAttachmentName(base, taken);
      taken.push(name);
      reservedRef.current.add(name);
      plan.push({ file, name, placeholder: `![Uploading ${name}…]()` });
    }
    if (plan.length === 0) return undefined;
    insertAt(pos, plan.map((p) => p.placeholder).join('\n'));
    const start = () => runPlan(plan);
    if (!defer) {
      await start();
      return undefined;
    }
    // Deferred (unsaved page): placeholders are in place; the caller starts or cancels the uploads.
    const cancel = () => cancelPlan(plan);
    return { start, cancel };
  }, [insertAt, runPlan, cancelPlan, toast]);

  // One callable (the stacked path, unchanged) that also carries the placed-image entry point.
  // eslint-disable-next-line react-hooks/refs -- both callbacks read their refs only when invoked (paste/drop handlers), never during render
  return useMemo(() => Object.assign((...args) => uploadFiles(...args), { uploadPlaced }), [uploadFiles, uploadPlaced]);
}
