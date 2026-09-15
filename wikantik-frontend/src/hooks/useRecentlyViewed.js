import { useCallback, useSyncExternalStore } from 'react';

const CAP = 20;
const keyFor = (login) => `wikantik.recent.${login}`;
const EMPTY = [];

// Module-level fan-out so every live hook instance converges on the same
// localStorage state. The sidebar (PersonalZone) and the article view
// (PageView) each mount their own instance; the sidebar stays mounted across
// SPA navigation, so without this it would only read localStorage once at
// mount and never reflect pages recorded later by PageView. A same-tab write
// does not fire the native `storage` event, so we notify subscribers directly.
// localStorage is a genuine external store, so it's read via
// useSyncExternalStore rather than an effect that calls setState.
const listeners = new Set();

function readRaw(login) {
  if (!login) return null;
  try {
    return localStorage.getItem(keyFor(login));
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

function read(login) {
  return login ? parse(readRaw(login)) : [];
}

// getSnapshot must return a referentially-stable value when nothing changed
// (useSyncExternalStore's contract), so cache per login, keyed by the raw
// string, and only re-parse when localStorage actually changed.
const cache = new Map(); // login -> { raw, items }

function readCached(login) {
  const raw = readRaw(login);
  const hit = cache.get(login);
  if (hit && hit.raw === raw) return hit.items;
  const items = parse(raw);
  cache.set(login, { raw, items });
  return items;
}

export function useRecentlyViewed({ login, enabled }) {
  const active = enabled && !!login;

  const subscribe = useCallback((onStoreChange) => {
    if (!active) return () => {};
    listeners.add(onStoreChange);
    const onStorage = (e) => { if (e.key === keyFor(login)) onStoreChange(); };
    window.addEventListener('storage', onStorage);
    return () => {
      listeners.delete(onStoreChange);
      window.removeEventListener('storage', onStorage);
    };
  }, [active, login]);

  const getSnapshot = useCallback(() => (active ? readCached(login) : EMPTY), [active, login]);

  const items = useSyncExternalStore(subscribe, getSnapshot);

  const record = useCallback(({ slug, title }) => {
    if (!active || !slug) return;
    // localStorage is the source of truth so concurrent instances stay in sync.
    const prev = read(login);
    const next = [{ slug, title: title || slug }, ...prev.filter((i) => i.slug !== slug)].slice(0, CAP);
    try {
      localStorage.setItem(keyFor(login), JSON.stringify(next));
    } catch (e) {
      console.warn('useRecentlyViewed: failed to persist', e);
    }
    listeners.forEach((fn) => fn());
  }, [active, login]);

  return { items, record };
}
