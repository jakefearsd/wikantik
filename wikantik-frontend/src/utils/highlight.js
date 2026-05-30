import React from 'react';

/**
 * Escape a string so it can be used literally inside a RegExp.
 */
function escapeRegex(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * highlightTerms(text, query) → React node[]
 *
 * Splits `text` on case-insensitive matches of any whitespace-delimited term
 * in `query` and wraps each match in a <mark> element. Non-matching segments
 * remain as plain strings. Returns an array suitable for spreading into a
 * React element's children.
 *
 * Safe: never uses dangerouslySetInnerHTML; regex special chars in query are
 * escaped before building the pattern.
 *
 * @param {string|null|undefined} text
 * @param {string|null|undefined} query
 * @returns {Array<string|React.ReactElement>}
 */
export function highlightTerms(text, query) {
  const safeText = text ?? '';
  const safeQuery = query ?? '';

  if (!safeText) return [safeText];
  if (!safeQuery.trim()) return [safeText];

  const terms = safeQuery
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map(escapeRegex);

  if (terms.length === 0) return [safeText];

  const pattern = new RegExp(`(${terms.join('|')})`, 'gi');
  const parts = safeText.split(pattern);

  // split() with a capturing group: odd indices are the matches
  return parts.map((part, i) => {
    if (i % 2 === 1) {
      // This is a matched segment — wrap in <mark>
      return React.createElement('mark', { key: i }, part);
    }
    return part;
  });
}
