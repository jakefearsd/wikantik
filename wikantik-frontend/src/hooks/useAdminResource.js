import { useState, useEffect, useCallback, useRef } from 'react';

/**
 * Loads one resource for an admin page: `data`, a first-load `loading` flag and a
 * message-string `error`, as `AdminPage` expects them.
 *
 * Unlike `useApi`, `reload()` is silent: it never sets `loading` back to true, so
 * a page that reloads after a save keeps its table on screen instead of swapping
 * it for the spinner. Only the initial fetch is "loading".
 *
 * Every fetch aborts the one before it, and unmount aborts the last; a fetcher
 * receives the `AbortSignal` and results of aborted fetches are discarded.
 * `deps` re-runs the fetch when they change; `fetcher` itself is not a
 * dependency, so callers fold what it closes over into `deps`.
 *
 * @param {(signal: AbortSignal) => Promise<any>} fetcher
 * @param {any[]} [deps]
 * @returns {{data: any, setData: Function, loading: boolean, error: string|null, reload: () => Promise<void>}}
 */
export function useAdminResource(fetcher, deps = []) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const controllerRef = useRef(null);

  const reload = useCallback(async () => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    try {
      const result = await fetcher(controller.signal);
      if (!controller.signal.aborted) {
        setData(result);
        setError(null);
      }
    } catch (err) {
      if (!controller.signal.aborted) {
        setError(err?.message || String(err));
      }
    } finally {
      if (!controller.signal.aborted) {
        setLoading(false);
      }
    }
    // deps is the caller-supplied, dynamic-length dependency list; see useApi.
    // eslint-disable-next-line react-hooks/exhaustive-deps, react-hooks/use-memo
  }, deps);

  useEffect(() => {
    reload();
    return () => controllerRef.current?.abort();
  }, [reload]);

  return { data, setData, loading, error, reload };
}
