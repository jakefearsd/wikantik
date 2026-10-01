import { api } from '../api/client';

const MAX = 200;
const cache = new Map(); // key → result; insertion order = LRU order

const keyOf = (name, section) => `${name}#${section || ''}`;

/** Loads a page preview: `{status:'ok',data}` or `{status:'missing'}` (404 — absent or not viewable). */
export async function loadPreview(name, section, signal) {
  const key = keyOf(name, section);
  if (cache.has(key)) {
    const hit = cache.get(key);
    cache.delete(key);
    cache.set(key, hit);
    return hit;
  }
  let result;
  try {
    result = { status: 'ok', data: await api.getPagePreview(name, { section, signal }) };
  } catch (err) {
    if (err?.status !== 404) throw err;
    result = { status: 'missing' };
  }
  cache.set(key, result);
  if (cache.size > MAX) cache.delete(cache.keys().next().value);
  return result;
}

export function evictPreview(name) {
  for (const key of [...cache.keys()]) if (key.startsWith(`${name}#`)) cache.delete(key);
}

export function __resetPreviewCacheForTest() { cache.clear(); }
