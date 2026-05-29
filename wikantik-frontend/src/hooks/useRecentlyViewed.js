import { useCallback, useEffect, useState } from 'react';

const CAP = 20;
const keyFor = (login) => `wikantik.recent.${login}`;

// Module-level fan-out so every live hook instance converges on the same
// localStorage state. The sidebar (PersonalZone) and the article view
// (PageView) each mount their own instance; the sidebar stays mounted across
// SPA navigation, so without this it would only read localStorage once at
// mount and never reflect pages recorded later by PageView. A same-tab write
// does not fire the native `storage` event, so we notify subscribers directly.
const listeners = new Set();

function read(login) {
  if (!login) return [];
  try {
    return JSON.parse(localStorage.getItem(keyFor(login))) || [];
  } catch {
    return [];
  }
}

export function useRecentlyViewed({ login, enabled }) {
  const active = enabled && !!login;
  const [items, setItems] = useState(() => (active ? read(login) : []));

  // Re-read on mount and whenever another instance records (same tab) or
  // another browser tab writes the same key.
  useEffect(() => {
    if (!active) {
      setItems([]);
      return undefined;
    }
    setItems(read(login));
    const onChange = () => setItems(read(login));
    listeners.add(onChange);
    const onStorage = (e) => { if (e.key === keyFor(login)) onChange(); };
    window.addEventListener('storage', onStorage);
    return () => {
      listeners.delete(onChange);
      window.removeEventListener('storage', onStorage);
    };
  }, [active, login]);

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
    setItems(next);
    listeners.forEach((fn) => fn());
  }, [active, login]);

  return { items, record };
}
