import { api } from '../api/client';

// Promise cache keyed `${page}#${section}`: dedupes concurrent requests and survives re-renders.
// Shared by the preview pane's WikiEmbed and the editor's live-preview embed widget.
const cache = new Map();

/** Drop every cached embed so the next load re-fetches. */
export function clearEmbedCache() {
  cache.clear();
}

/** Fetch (or reuse) the server-rendered embed for a page/section. Resolves {html, missing, restricted, truncated}. */
export function loadEmbed(page, section) {
  const key = `${page}#${section || ''}`;
  if (!cache.has(key)) {
    const p = api.getPageEmbed(page, { section: section || undefined });
    // A failed fetch must not be cached, or a transient error would stick until the cache is cleared.
    p.catch(() => { if (cache.get(key) === p) cache.delete(key); });
    cache.set(key, p);
  }
  return cache.get(key);
}
