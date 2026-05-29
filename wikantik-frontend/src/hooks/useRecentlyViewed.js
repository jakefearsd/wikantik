import { useCallback, useState } from 'react';

const CAP = 20;
const keyFor = (login) => `wikantik.recent.${login}`;

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

  const record = useCallback(({ slug, title }) => {
    if (!active || !slug) return;
    setItems((prev) => {
      const next = [{ slug, title: title || slug }, ...prev.filter((i) => i.slug !== slug)].slice(0, CAP);
      try {
        localStorage.setItem(keyFor(login), JSON.stringify(next));
      } catch (e) {
        console.warn('useRecentlyViewed: failed to persist', e);
      }
      return next;
    });
  }, [active, login]);

  return { items, record };
}
