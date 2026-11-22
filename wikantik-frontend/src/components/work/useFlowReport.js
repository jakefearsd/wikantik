import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Loads one report in its own effect, so the page around it never waits. `key` names the request (null = make none,
 * e.g. an invalid window); a new key reloads. A failed load keeps the last good report and sets `error` (retry with
 * `reload`); a 404 or 403 sets `notFound`, because the server refuses a stranger the same way it answers an unknown name.
 * `loading` is true until the current key has answered either way.
 */
export default function useFlowReport(fetcher, key, label = 'report') {
  const fetchRef = useRef(fetcher);
  useEffect(() => { fetchRef.current = fetcher; });
  const seq = useRef(0);
  const [state, setState] = useState({ key: null, data: null, error: null, notFound: false });

  const load = useCallback(() => {
    if (!key) return undefined;
    const mine = ++seq.current;
    return fetchRef.current()
      .then((data) => { if (mine === seq.current) setState({ key, data, error: null, notFound: false }); })
      .catch((error) => {
        console.warn(`useFlowReport: could not load the ${label}`, error);
        if (mine !== seq.current) return;
        const refused = error?.status === 404 || error?.status === 403;
        setState((s) => ({ key, data: s.data, error: refused ? null : error, notFound: refused }));
      });
  }, [key, label]);

  useEffect(() => { load(); }, [load]);

  return {
    data: state.data, error: state.error, notFound: state.key === key && state.notFound,
    loading: !!key && state.key !== key, reload: load,
  };
}
