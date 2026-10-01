/**
 * Rank how well {@code query} matches {@code text}: 0 prefix, 1 substring,
 * 2 subsequence, -1 no match. Case-insensitive; whitespace is ignored.
 */
export function fuzzyRank(text, query) {
  const q = (query || '').toLowerCase().replace(/\s+/g, '');
  if (!q) return 0;
  const t = text.toLowerCase().replace(/\s+/g, '');
  if (t.startsWith(q)) return 0;
  if (t.includes(q)) return 1;
  let i = 0;
  for (const ch of t) { if (ch === q[i]) i += 1; if (i === q.length) return 2; }
  return -1;
}
