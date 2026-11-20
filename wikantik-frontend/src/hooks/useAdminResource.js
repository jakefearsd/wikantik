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
 * With `refetchShowsLoading`, a change in `deps` (a new query, e.g. a filter) sets
 * `loading` and clears `error` before the new fetch; `reload()` stays silent in
 * every mode. Default false.
 *
 * @param {(signal: AbortSignal) => Promise<any>} fetcher
 * @param {any[]} [deps]
 * @param {{refetchShowsLoading?: boolean}} [options]
 * @returns {{data: any, setData: Function, loading: boolean, error: string|null, reload: () => Promise<void>}}
 *   `error` is the thrown error's message (possibly empty) or, for a thrown non-error, its string form.
 */
export function useAdminResource(fetcher, deps = [], { refetchShowsLoading = false } = {}) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const controllerRef = useRef(null);

  // Store-previous-and-compare (as usePaginatedQuery does) rather than a setState in an effect.
  const queryKey = JSON.stringify(deps);
  const [prevQueryKey, setPrevQueryKey] = useState(queryKey);
  if (queryKey !== prevQueryKey) {
    setPrevQueryKey(queryKey);
    if (refetchShowsLoading) {
      setLoading(true);
      setError(null);
    }
  }

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
        // An Error's message as-is, even an empty one (falsy: no banner); anything else as a string
        setError(typeof err?.message === 'string' ? err.message : String(err));
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
