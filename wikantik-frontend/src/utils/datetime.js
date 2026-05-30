/**
 * Unified date/time formatting utility.
 *
 * All functions return '' for null/undefined/empty input and return the
 * original string unchanged for inputs that cannot be parsed as a valid date.
 */

/**
 * Parse `iso` into a Date. Returns null for empty/missing input, and a Date
 * whose .getTime() is NaN for inputs that are present but unparseable.
 */
function parseDate(iso) {
  if (iso == null || iso === '') return null;
  return new Date(iso);
}

/**
 * Format an ISO date string as a locale date string.
 * e.g. "May 30, 2026"
 *
 * @param {string|null|undefined} iso
 * @returns {string}
 */
export function formatDate(iso) {
  const date = parseDate(iso);
  if (date === null) return '';
  if (isNaN(date.getTime())) return iso;
  return date.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

/**
 * Format an ISO date string as a locale date+time string.
 *
 * @param {string|null|undefined} iso
 * @returns {string}
 */
export function formatDateTime(iso) {
  const date = parseDate(iso);
  if (date === null) return '';
  if (isNaN(date.getTime())) return iso;
  return date.toLocaleString();
}

/**
 * Format an ISO date string as a locale time string.
 *
 * @param {string|null|undefined} iso
 * @returns {string}
 */
export function formatTime(iso) {
  const date = parseDate(iso);
  if (date === null) return '';
  if (isNaN(date.getTime())) return iso;
  return date.toLocaleTimeString();
}

/**
 * Format an ISO date string as a human-friendly relative time string.
 *
 * @param {string|null|undefined} iso - The date to format.
 * @param {Date|number} [now] - Reference point for "now". Accepts a Date
 *   object or epoch milliseconds. Defaults to `new Date()` when omitted,
 *   making this parameter injectable for deterministic tests.
 * @returns {string}
 */
export function formatRelative(iso, now) {
  const date = parseDate(iso);
  if (date === null) return '';
  if (isNaN(date.getTime())) return iso;

  const nowMs = now instanceof Date ? now.getTime() : (now != null ? now : Date.now());
  const deltaMs = nowMs - date.getTime();

  // Future dates (or exactly now): treat as "just now".
  if (deltaMs < 0) return 'just now';

  const deltaS = deltaMs / 1000;

  if (deltaS < 60) return 'just now';

  const deltaMin = Math.floor(deltaS / 60);
  if (deltaMin < 60) return `${deltaMin}m ago`;

  const deltaH = Math.floor(deltaS / 3600);
  if (deltaH < 24) return `${deltaH}h ago`;

  const deltaD = Math.floor(deltaS / 86400);
  if (deltaD < 30) return `${deltaD}d ago`;

  return formatDate(iso);
}
