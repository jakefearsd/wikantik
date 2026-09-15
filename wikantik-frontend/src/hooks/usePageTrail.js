import { useCallback, useSyncExternalStore } from 'react';

// Per-tab navigation trail backing the reader breadcrumb: the last few DISTINCT
// pages visited, oldest → newest, with the current page LAST. Deliberately
// distinct from useRecentlyViewed (that one is localStorage, per-login,
// authenticated-only, cap 20 — a persistent "recently viewed" list). This trail
// is sessionStorage (per tab, survives refresh, gone when the tab closes), works
// for anonymous readers, and is capped at 3.
const KEY = 'wikantik.pageTrail';
const CAP = 3;

// Module-level fan-out so separate live instances converge on the same state.
// PageView mounts one instance to record; Breadcrumbs mounts another to read.
// A same-tab write does NOT fire the native `storage` event, so notify directly.
// sessionStorage is a genuine external store (mutated outside React, and by
// other tabs), so it's read via useSyncExternalStore rather than an effect
// that calls setState.
const listeners = new Set();

function readRaw() {
  if (typeof sessionStorage === 'undefined') return null;
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

function parse(raw) {
  try {
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function read() {
  return parse(readRaw());
}

// getSnapshot must return a referentially-stable value when nothing changed
// (useSyncExternalStore's contract — a fresh array every call would spin into
// an infinite re-render loop), so cache the parsed array keyed on the raw
// string and only re-parse when sessionStorage actually changed.
let cachedRaw;
let cachedItems = [];

function getSnapshot() {
  const raw = readRaw();
  if (raw !== cachedRaw) {
    cachedRaw = raw;
    cachedItems = parse(raw);
  }
  return cachedItems;
}

function subscribe(onStoreChange) {
  listeners.add(onStoreChange);
  const onStorage = (e) => { if (e.key === KEY) onStoreChange(); };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(onStoreChange);
    window.removeEventListener('storage', onStorage);
  };
}

export function usePageTrail() {
  const items = useSyncExternalStore(subscribe, getSnapshot);

  const record = useCallback(({ slug, title }) => {
    if (!slug) return;
    // sessionStorage is the source of truth so concurrent instances stay in sync.
    // Dedup by slug; append as the most-recent (last) entry; keep the last CAP.
    const prev = read();
    const next = [...prev.filter((i) => i.slug !== slug), { slug, title: title || slug }].slice(-CAP);
    try {
      sessionStorage.setItem(KEY, JSON.stringify(next));
    } catch (e) {
      console.warn('usePageTrail: failed to persist', e);
    }
    listeners.forEach((fn) => fn());
  }, []);

  return { items, record };
}
