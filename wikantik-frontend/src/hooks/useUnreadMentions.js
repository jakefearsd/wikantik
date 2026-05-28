import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';

/** Tracks the per-user unread-mentions count via a poll on mount + on
 *  document.visibilitychange (back-to-foreground). Returns `{count, refresh}`. */
export function useUnreadMentions({ enabled = true } = {}) {
  const [count, setCount] = useState(0);
  const aliveRef = useRef(true);

  const refresh = useCallback(async () => {
    if (!enabled) return;
    try {
      const res = await api.getMyMentionsUnreadCount();
      if (aliveRef.current) setCount(typeof res?.count === 'number' ? res.count : 0);
    } catch {
      if (aliveRef.current) setCount(0);
    }
  }, [enabled]);

  useEffect(() => {
    aliveRef.current = true;
    refresh();
    const onVis = () => { if (document.visibilityState === 'visible') refresh(); };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      aliveRef.current = false;
      document.removeEventListener('visibilitychange', onVis);
    };
  }, [refresh]);

  return { count, refresh };
}
