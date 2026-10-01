import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';

const DEBOUNCE_MS = 1500;
const WARMING_RETRY_MS = 5000;

export function useUnlinkedMentions({ page, text, enabled }) {
  const [state, setState] = useState({ status: 'idle', mentions: [] });
  const [nonce, setNonce] = useState(0);
  const rescan = useCallback(() => setNonce((n) => n + 1), []);
  const latest = useRef(nonce);

  useEffect(() => {
    if (!enabled || !text) return undefined;
    const ctl = new AbortController();
    const immediate = nonce !== latest.current;
    latest.current = nonce;
    let retry;
    const id = setTimeout(() => {
      setState((s) => ({ ...s, status: 'loading' }));
      api.scanMentions({ page, text, signal: ctl.signal })
        .then((d) => setState({ status: 'ok', mentions: d?.mentions || [] }))
        .catch((err) => {
          if (err?.name === 'AbortError') return;
          if (err?.status === 503) {
            setState({ status: 'warming', mentions: [] });
            retry = setTimeout(() => setNonce((n) => n + 1), WARMING_RETRY_MS);
            return;
          }
          console.warn('[mentions] scan failed', err?.message || err);
          setState({ status: 'error', mentions: [] });
        });
    }, immediate ? 0 : DEBOUNCE_MS);
    return () => { ctl.abort(); clearTimeout(id); clearTimeout(retry); };
  }, [page, text, enabled, nonce]);

  return { ...state, rescan };
}
