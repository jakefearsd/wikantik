import { useEffect, useState } from 'react';
import { api } from '../api/client';

export function useMyPages({ enabled }) {
  const [pages, setPages] = useState([]);
  const [loading, setLoading] = useState(enabled);

  // Adjust state during render the moment `enabled` changes, instead of via a
  // synchronous setState inside the effect below.
  const [wasEnabled, setWasEnabled] = useState(enabled);
  if (enabled !== wasEnabled) {
    setWasEnabled(enabled);
    setLoading(enabled);
    if (!enabled) setPages([]);
  }

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;
    api.getMyPages()
      .then((d) => { if (!cancelled) setPages(d.pages || []); })
      .catch(() => { if (!cancelled) setPages([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [enabled]);

  return { pages, loading };
}
