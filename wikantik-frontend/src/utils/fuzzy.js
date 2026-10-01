const SEPARATORS = new Set(['-', '_', '/']);
const isUpper = (ch) => /\p{Lu}/u.test(ch);
const isLower = (ch) => /\p{Ll}/u.test(ch);
const isDigit = (ch) => /\p{Nd}/u.test(ch);
const isLetter = (ch) => /\p{L}/u.test(ch);

/** CamelCase / acronym / letter-digit boundary at {@code k} in the ORIGINAL-case text. */
function caseOrDigitBoundary(s, k) {
  if (k === 0) return true;
  const prev = s[k - 1];
  const cur = s[k];
  if (isUpper(cur)) {
    return isLower(prev) || isDigit(prev) || (isUpper(prev) && k + 1 < s.length && isLower(s[k + 1]));
  }
  return isDigit(cur) ? isLetter(prev) : isLetter(cur) && isDigit(prev);
}

/**
 * Lower-cased, whitespace-free key plus the indices (into the key) that start a word: the first character,
 * the character after whitespace / "-" / "_" / "/", and CamelCase / acronym / letter-digit boundaries.
 * Same rules as the server's {@code MatchKey}.
 */
function matchKey(text) {
  let key = '';
  const starts = new Set();
  let afterSeparator = true;
  for (let k = 0; k < text.length; k += 1) {
    const ch = text[k];
    if (/\s/.test(ch)) { afterSeparator = true; continue; }
    if (afterSeparator || caseOrDigitBoundary(text, k)) starts.add(key.length);
    key += ch.toLowerCase();
    afterSeparator = SEPARATORS.has(ch);
  }
  return { key, starts };
}

/** Next viable match positions for {@code ch}: a word start after the earliest match, or a run continuation. */
function step({ key, starts }, prev, ch) {
  const next = new Set();
  const from = prev ? Math.min(...prev) + 1 : 0;
  starts.forEach((j) => { if (j >= from && key[j] === ch) next.add(j); });
  if (prev) prev.forEach((p) => { if (key[p + 1] === ch) next.add(p + 1); });
  return next;
}

/** Every query character starts a word or continues the previous match (tracks all positions, no greedy dead ends). */
function matchesWordStarts(mk, q) {
  let at = null;
  for (const ch of q) {
    at = step(mk, at, ch);
    if (at.size === 0) return false;
  }
  return true;
}

/**
 * Rank how well {@code query} matches {@code text}: 0 prefix, 1 substring, 2 word-start subsequence,
 * -1 no match. Case-insensitive; whitespace is ignored. In the subsequence tier each query character must
 * start a word or continue a matched run, so "bond" does not match "Background Job Processing".
 */
export function fuzzyRank(text, query) {
  const q = (query || '').toLowerCase().replace(/\s+/g, '');
  if (!q) return 0;
  const mk = matchKey(text);
  if (mk.key.startsWith(q)) return 0;
  if (mk.key.includes(q)) return 1;
  return matchesWordStarts(mk, q) ? 2 : -1;
}
