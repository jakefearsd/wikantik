import { useCallback, useEffect, useRef } from 'react';
import { pastedImageName, normalizeAttachmentName, uniqueAttachmentName, attachmentMarkup } from '../utils/attachmentNameValidator';

const isImage = (file) => /^image\//.test(file.type || '');

/**
 * Paste/drop upload for the editor. All placeholders are inserted at `pos` up front (one per line),
 * then files upload one at a time; each placeholder becomes the attachment markup, or is removed
 * (and the failure toasted) if its upload fails.
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

  return useCallback(async (files, pos, { pasted = false } = {}) => {
    const taken = [...(namesRef.current || [])];
    const plan = [];
    for (const file of files) {
      const base = pasted && isImage(file) ? pastedImageName(file.type) : normalizeAttachmentName(file.name);
      if (!base) {
        toast.error(`Cannot upload "${file.name}": it has no usable file name or extension`);
        continue;
      }
      const name = uniqueAttachmentName(base, taken);
      taken.push(name);
      plan.push({ file, name, placeholder: `![Uploading ${name}…]()` });
    }
    if (plan.length === 0) return;
    updateBody((prev) => prev.slice(0, pos) + plan.map((p) => p.placeholder).join('\n') + prev.slice(pos));
    for (const { file, name, placeholder } of plan) {
      try {
        await upload(file, name);
        updateBody((prev) => prev.replace(placeholder, attachmentMarkup(name, isImage(file))));
      } catch (err) {
        updateBody((prev) => prev.replace(placeholder, ''));
        toast.error(`Upload of ${name} failed: ${err?.message || err}`);
      }
    }
  }, [upload, updateBody, toast]);
}
