import { useEffect, useState } from 'react';
import { api } from '../api/client';

export function useMyPages({ enabled }) {
  const [pages, setPages] = useState([]);
  const [loading, setLoading] = useState(enabled);

  useEffect(() => {
    if (!enabled) { setPages([]); setLoading(false); return; }
    let cancelled = false;
    setLoading(true);
    api.getMyPages()
      .then((d) => { if (!cancelled) setPages(d.pages || []); })
      .catch(() => { if (!cancelled) setPages([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [enabled]);

  return { pages, loading };
}
