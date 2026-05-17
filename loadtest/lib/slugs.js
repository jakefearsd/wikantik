/*
 * Loads the page-slug pool the read scenario walks. k6's open() runs only in
 * init context, so loadSlugs() must be called at module top level.
 */

const FALLBACK = ['Main', 'HybridRetrieval', 'PageGraphVsKnowledgeGraph'];

/**
 * Read newline-delimited slugs from `path` (default the bundled sample).
 * Returns a non-empty array — falls back to FALLBACK if the file is empty
 * or unreadable.
 */
export function loadSlugs(path) {
  const file = path || './slugs.sample.txt';
  try {
    const slugs = open(file)
      .split('\n')
      .map((s) => s.trim())
      .filter((s) => s !== '' && !s.startsWith('#'));
    return slugs.length > 0 ? slugs : FALLBACK;
  } catch (e) {
    return FALLBACK;
  }
}

/** Deterministic-ish random pick. */
export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
