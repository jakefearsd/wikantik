// Mention tokens are stored in comment text as `@[Display Name](user:<uid>)`.
// The display text is a snapshot taken when the comment was written; the uid is
// the key. Keep the pattern in step with the server's MentionExtractor
// (wikantik-main, com.wikantik.comments.mentions.MentionExtractor.TOKEN).
const TOKEN_SOURCE = '@\\[([^\\]\\n]{1,64})\\]\\(user:([A-Za-z0-9-]{1,64})\\)';

const SNAPSHOT_MAX = 64;

/**
 * Splits a comment body into ordered segments:
 * `{ type: 'text', text }` and `{ type: 'mention', uid, snapshot }`.
 * A legacy `@login` is not a token and stays inside the surrounding text segment.
 */
export function splitMentions(body) {
  if (!body) return [];
  const segments = [];
  let last = 0;
  // A fresh RegExp per call: matchAll on a shared /g regex would share lastIndex.
  for (const match of body.matchAll(new RegExp(TOKEN_SOURCE, 'g'))) {
    if (match.index > last) segments.push({ type: 'text', text: body.substring(last, match.index) });
    segments.push({ type: 'mention', uid: match[2], snapshot: match[1] });
    last = match.index + match[0].length;
  }
  if (last < body.length) segments.push({ type: 'text', text: body.substring(last) });
  return segments;
}

/**
 * Builds the token for a picker candidate. The snapshot is cleaned so it always
 * fits the server pattern: no brackets, no control characters, at most 64 chars.
 */
export function buildMentionToken(uid, displayName) {
  let snapshot = '';
  for (const ch of String(displayName ?? '')) {
    if (ch === '[' || ch === ']' || /[\p{Cc}]/u.test(ch)) continue;
    if (snapshot.length + ch.length > SNAPSHOT_MAX) break;
    snapshot += ch;
  }
  snapshot = snapshot.trim() || 'user';
  return `@[${snapshot}](user:${uid})`;
}

/**
 * The current display name the server resolved for `uid`, or null when it did not.
 *
 * A token's uid is attacker-controlled text and `constructor`, `toString`, `valueOf`... all satisfy the
 * uid pattern, so a bare `names[uid]` would find them on the prototype chain. Only an own entry whose
 * value is a non-empty string counts as resolved. Every renderer that shows a current name goes
 * through here.
 */
export function resolvedMentionName(names, uid) {
  if (!names || !Object.hasOwn(names, uid)) return null;
  const name = names[uid];
  return typeof name === 'string' && name ? name : null;
}

// A token the server's snippet length cut off: `@[Na`, `@[Name]`, `@[Name](`, `@[Name](user:u-`.
const PARTIAL_TAIL = /@\[[^\]\n]*(?:\](?:\([A-Za-z0-9:-]*)?)?$/;

/**
 * Plain-text form of a comment fragment, for one-line previews such as the "My
 * mentions" snippet: complete tokens become `@<current name or snapshot>`, and a
 * token cut off at the end of the fragment is replaced by an ellipsis.
 */
export function flattenMentions(text, names = {}) {
  if (!text) return '';
  return splitMentions(text.replace(PARTIAL_TAIL, '…'))
    .map((s) => (s.type === 'text' ? s.text : `@${resolvedMentionName(names, s.uid) ?? s.snapshot}`))
    .join('');
}
