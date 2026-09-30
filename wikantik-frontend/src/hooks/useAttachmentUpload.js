import { useCallback, useEffect, useRef } from 'react';
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
 * @param {{ error: (msg: string) => void }} o.toast
 */
export function useAttachmentUpload({ existingNames, upload, updateBody, toast }) {
  const namesRef = useRef(existingNames);
  useEffect(() => { namesRef.current = existingNames; });
  // Names claimed by uploads this hook started. The attachment list only learns them after a refresh,
  // so an overlapping paste would otherwise pick the same name and overwrite the earlier upload.
  const reservedRef = useRef(new Set());

  return useCallback(async (files, pos, { pasted = false, defer = false } = {}) => {
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
    updateBody((prev) => prev.slice(0, pos) + plan.map((p) => p.placeholder).join('\n') + prev.slice(pos));
    const start = async () => {
      for (const { file, name, placeholder } of plan) {
        try {
          // The multipart filename must carry the chosen name's extension (server checks they match).
          await upload(new File([file], name, { type: file.type }), name);
          updateBody((prev) => prev.replace(placeholder, attachmentMarkup(name, isImage(file))));
        } catch (err) {
          updateBody((prev) => prev.replace(placeholder, ''));
          toast.error(`Upload of ${name} failed: ${err?.message || err}`);
        }
      }
    };
    if (!defer) {
      await start();
      return undefined;
    }
    // Deferred (unsaved page): placeholders are in place; the caller starts or cancels the uploads.
    const cancel = () => {
      for (const { name, placeholder } of plan) {
        reservedRef.current.delete(name);
        updateBody((prev) => prev.replace(placeholder, ''));
      }
    };
    return { start, cancel };
  }, [upload, updateBody, toast]);
}
