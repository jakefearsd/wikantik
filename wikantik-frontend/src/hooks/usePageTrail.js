import { useCallback, useEffect, useState } from 'react';

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
const listeners = new Set();

function read() {
  if (typeof sessionStorage === 'undefined') return [];
  try {
    return JSON.parse(sessionStorage.getItem(KEY)) || [];
  } catch {
    return [];
  }
}

export function usePageTrail() {
  const [items, setItems] = useState(read);

  // Re-read on mount and whenever another instance records (same tab) or another
  // tab writes the same key.
  useEffect(() => {
    setItems(read());
    const onChange = () => setItems(read());
    listeners.add(onChange);
    const onStorage = (e) => { if (e.key === KEY) onChange(); };
    window.addEventListener('storage', onStorage);
    return () => {
      listeners.delete(onChange);
      window.removeEventListener('storage', onStorage);
    };
  }, []);

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
    setItems(next);
    listeners.forEach((fn) => fn());
  }, []);

  return { items, record };
}
