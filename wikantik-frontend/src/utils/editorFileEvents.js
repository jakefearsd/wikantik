/**
 * Files carried by a paste — or none when the clipboard also has text: copying from a word processor
 * puts both the text and a rendered image of it on the clipboard, and the text is what was meant.
 */
export function filesFromPaste(clipboardData) {
  if (!clipboardData) return [];
  if (clipboardData.getData && clipboardData.getData('text/plain')) return [];
  return Array.from(clipboardData.files || []);
}

/** Files carried by a drop (OS file drags). */
export function filesFromDrop(dataTransfer) {
  if (!dataTransfer) return [];
  return Array.from(dataTransfer.files || []);
}

/** True when a drag carries OS files (not text or an attachment-row drag). */
export function dragCarriesFiles(dataTransfer) {
  return !!dataTransfer && Array.from(dataTransfer.types || []).includes('Files');
}
