import { useEffect, useState } from 'react';
import { api } from '../api/client';

export function useMyBlog({ login, enabled }) {
  const active = enabled && !!login;
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(active);

  useEffect(() => {
    if (!active) { setEntries([]); setLoading(false); return; }
    let cancelled = false;
    setLoading(true);
    // GET /api/blog/{login}/entries returns a bare JSON array (not { entries: [...] }).
    api.blog.listEntries(login)
      .then((d) => { if (!cancelled) setEntries(Array.isArray(d) ? d : (d?.entries ?? [])); })
      .catch(() => { if (!cancelled) setEntries([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [active, login]);

  return { entries, loading };
}
