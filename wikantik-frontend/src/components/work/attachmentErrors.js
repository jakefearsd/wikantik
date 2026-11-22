import { formatSize } from '../../utils/formatSize';

// What the server said about attaching to each item, learned from the attachment list: whether this caller may
// attach, the per-file and per-item limits, and the bytes already counted toward the item limit. Until an item's
// list has loaded nothing is known, nothing is pre-checked and the server answers. The server stays the authority;
// this only spares a doomed upload of a big file.
let known = new Map();

/** Remember the limits fields of a list response for the item (ignored when the response carries none). */
export function rememberLimits(itemKey, response) {
  if (!response || typeof response.maxFileBytes !== 'number') return;
  known.set(itemKey, {
    mayAttach: response.mayAttach !== false,
    maxFileBytes: response.maxFileBytes,
    maxItemBytes: response.maxItemBytes,
    usedBytes: response.usedBytes || 0,
  });
}

/** `{ mayAttach, maxFileBytes, maxItemBytes, usedBytes }` for the item, or null before its list has loaded. */
export const limitsFor = (itemKey) => known.get(itemKey) || null;

/** This page stored a file of `bytes` on the item: count it until the next list load says the real figure. */
export function noteUploaded(itemKey, bytes) {
  const cur = known.get(itemKey);
  if (cur) known.set(itemKey, { ...cur, usedBytes: cur.usedBytes + bytes });
}

/** Test seam: forget everything the server taught. */
export function resetLimits() { known = new Map(); }

/** Words for a file that is too big to send, or null when it may go (or nothing is known yet). */
export function sizeProblem(file, itemKey) {
  const lim = limitsFor(itemKey);
  if (!file || !lim) return null;
  if (file.size > lim.maxFileBytes) {
    return `${file.name} is larger than ${formatSize(lim.maxFileBytes)}, the most one file may be.`;
  }
  const left = Math.max(0, lim.maxItemBytes - lim.usedBytes);
  if (file.size > left) {
    return `${file.name} does not fit: this item may hold ${formatSize(lim.maxItemBytes)} of files and `
      + `${formatSize(lim.usedBytes)} is used, leaving ${formatSize(left)}.`;
  }
  return null;
}

/** Words for a failed upload, from the thrown error (status and body as client.js sets them). The 413 words are keyed on `reason`. */
export function uploadErrorText(err, name, itemKey) {
  const status = err?.status;
  const reason = err?.body?.reason;
  const message = err?.body?.message || err?.message || '';
  const label = name ? `${name}: ` : '';
  const lim = itemKey ? limitsFor(itemKey) : null;
  if (reason === 'too_large') {
    return `${label}${lim ? `too large; one file may be at most ${formatSize(lim.maxFileBytes)}.` : 'too large for one file.'}`;
  }
  if (reason === 'item_full') {
    return `${label}${lim ? `this item is full; its files may total at most ${formatSize(lim.maxItemBytes)}.`
      : 'this item is full; remove a file to make room.'}`;
  }
  if (reason === 'reporter_full') {
    return `${label}you have attached as much to this request as you may; remove one of your files to make room.`;
  }
  if (reason === 'archived' || (status === 409 && !reason)) return `${label}this project is archived; nothing was attached.`;
  if (status === 413) return `${label}too large.`;
  if (status === 403) return `${label}you are not allowed to attach files here.`;
  if (status === 404) return `${label}this item is no longer available.`;
  if (status == null) return `${label}could not reach the server. Check the connection and try again.`;
  return `${label}${message || 'the upload failed.'}`;
}
