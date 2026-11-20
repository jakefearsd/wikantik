/** Join outcomes after which the page is edited single-author (feature off, derived page, session full, join failed). */
export const COLLAB_FALLBACK = new Set(['disabled', 'unavailable', 'full', 'error']);
/** Statuses in which a joined collab editor is mounted ('forbidden' with a joined editor: evicted, read-only). */
export const COLLAB_EDITING = new Set(['live', 'reconnecting', 'resync', 'frozen', 'auth-required', 'forbidden']);

/**
 * What the editor shows for a collaboration session: the flags derived from the session's join `status`, whether it
 * has joined an editor (`extensions`), and whether collaboration is `wanted` for this page. Pure.
 *   mode       collaborative mode is on (wanted, and the join did not fall back to single-author);
 *   editing    a joined collab editor is mounted;
 *   forbidden  the join was refused for lack of edit permission (no editor joined);
 *   evicted    the joined editor was told 'forbidden' (it stays, read-only);
 *   frozen     the session is frozen (read-only);
 *   readOnly   the joined editor refuses writes (frozen, out of sync, or evicted);
 *   locked     the editor takes no input: `readOnly || forbidden`.
 */
export function collabView(status, extensions, wanted) {
  const mode = wanted && !COLLAB_FALLBACK.has(status);
  const editing = mode && !!extensions && COLLAB_EDITING.has(status);
  const forbidden = mode && status === 'forbidden' && !extensions;
  const evicted = editing && status === 'forbidden';
  const frozen = editing && status === 'frozen';
  const readOnly = editing && (frozen || status === 'resync' || evicted);
  return { mode, editing, forbidden, evicted, frozen, readOnly, locked: readOnly || forbidden };
}
