/**
 * Attachment-link rewriting for an attachment rename: every markdown image `![…](old)` or link `[…](old)` whose
 * destination is exactly the old file name is pointed at the new one. Bare mentions and other destinations are
 * left alone.
 */

const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * The edits that rename `oldName` to `newName` in `text`, as `{ from, to, insert }` character ranges that all
 * refer to `text` itself (ascending, non-overlapping) — ready for one editor transaction, so the caret and the
 * rest of the document are mapped rather than replaced.
 */
export function attachmentLinkChanges(text, oldName, newName) {
  if (!text || !oldName) return [];
  const pattern = new RegExp(`(!?\\[[^\\]]*\\])\\(${escapeRegExp(oldName)}\\)`, 'g');
  const changes = [];
  for (let m = pattern.exec(text); m; m = pattern.exec(text)) {
    const from = m.index + m[1].length + 1; // just inside the "("
    changes.push({ from, to: from + oldName.length, insert: newName });
  }
  return changes;
}

/** `text` with the rename applied — the no-editor fallback. */
export function rewriteAttachmentLinks(text, oldName, newName) {
  return attachmentLinkChanges(text, oldName, newName)
    .reduceRight((acc, c) => acc.slice(0, c.from) + c.insert + acc.slice(c.to), text);
}
