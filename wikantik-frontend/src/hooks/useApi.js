import { useState, useEffect, useCallback, useRef } from 'react';

export function useApi(fetcher, deps = [], options = {}) {
  // initialData seeds the first render from server-supplied data (e.g. the SSR
  // data island) so the component can show content on first paint without
  // waiting for the network. `loading` starts false when seeded; the effect
  // still refreshes in the background. Callers decide whether to show a spinner
  // while `loading` (PageView keeps seeded content visible — see its guard — so
  // crawlers never see an empty/loading DOM, which Google flags as Soft 404).
  const { initialData = null } = options;
  const [data, setData] = useState(initialData);
  const [loading, setLoading] = useState(initialData == null);
  const [error, setError] = useState(null);
  const controllerRef = useRef(null);

  const load = useCallback(async () => {
    if (controllerRef.current) {
      controllerRef.current.abort();
    }
    const controller = new AbortController();
    controllerRef.current = controller;

    setLoading(true);
    setError(null);
    try {
      const result = await fetcher(controller.signal);
      if (!controller.signal.aborted) {
        setData(result);
      }
    } catch (err) {
      if (!controller.signal.aborted) {
        setError(err);
      }
    } finally {
      if (!controller.signal.aborted) {
        setLoading(false);
      }
    }
    // deps is an intentionally caller-supplied, dynamic-length array — the
    // whole point of this generic hook's contract (see the doc comment on
    // usePaginatedQuery's `deps` param below for the sibling case). ESLint
    // can't statically verify a non-literal dependency list, and `fetcher`
    // itself is deliberately excluded: callers own memoizing it and folding
    // whatever it depends on into `deps` (existing convention already
    // encoded in the ESLint suppressions below on usePaginatedQuery).
    // eslint-disable-next-line react-hooks/exhaustive-deps, react-hooks/use-memo
  }, deps);

  useEffect(() => {
    load();
    return () => {
      if (controllerRef.current) {
        controllerRef.current.abort();
      }
    };
  }, [load]);

  return { data, loading, error, reload: load };
}

/**
 * Pagination + debounced-search shell for admin tables that drive a paged
 * fetcher. Replaces the page+searchInput+search+totaldebounce+pageReset
 * boilerplate every Knowledge Graph admin pane was open-coding.
 *
 * `search` is the controlled input value (what `setSearch` writes); the hook
 * internally debounces 300ms before passing it to `fetcher`. `deps` are extra
 * filter values that trigger a fetch and reset `page` to 0 when they change.
 *
 * @param {(params: {page:number,pageSize:number,search:string}) => Promise<{rows:any[], total:number}>} fetcher
 * @param {any[]} deps
 * @param {{ pageSize?: number }} [options]
 */
export function usePaginatedQuery(fetcher, deps = [], options = {}) {
  const { pageSize = 50 } = options;

  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [error, setError] = useState(null);
  const [firstLoad, setFirstLoad] = useState(true);

  // Stable ref to the fetcher so load's identity depends only on
  // page/search/deps — otherwise every host render creates a new fetcher
  // closure and we'd thrash the effect. Written in an effect (runs after
  // every render, no dep array) rather than during render, since refs may
  // not be written synchronously in the render body.
  const fetcherRef = useRef(fetcher);
  useEffect(() => {
    fetcherRef.current = fetcher;
  });

  // Debounce search → debouncedSearch.
  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);

  // Reset page to 0 when the debounced search or any external filter
  // changes — derived during render (store-previous-and-compare) rather
  // than a setState call in an effect. `deps` is caller-supplied and may
  // contain new-identity-but-equal-value arrays/objects each render, so the
  // comparison is by serialized value, not reference.
  const resetKey = JSON.stringify([debouncedSearch, ...deps]);
  const [prevResetKey, setPrevResetKey] = useState(resetKey);
  if (resetKey !== prevResetKey) {
    setPrevResetKey(resetKey);
    setPage(0);
  }

  const load = useCallback(
    async (currentPage) => {
      try {
        const data = await fetcherRef.current({
          page: currentPage,
          pageSize,
          search: debouncedSearch,
        });
        setRows(data?.rows || []);
        setTotal(typeof data?.total === 'number' ? data.total : (data?.rows?.length || 0));
        setError(null);
      } catch (err) {
        setError(err?.message || String(err));
      } finally {
        setFirstLoad(false);
      }
    },
    // deps is caller-supplied and dynamic-length — see the useApi `load`
    // suppression above for the same rationale.
    // eslint-disable-next-line react-hooks/exhaustive-deps, react-hooks/use-memo
    [pageSize, debouncedSearch, ...deps],
  );

  useEffect(() => { load(page); }, [load, page]);

  const reload = useCallback(() => load(page), [load, page]);

  return {
    rows,
    total,
    page,
    setPage,
    search,
    setSearch,
    error,
    setError,
    firstLoad,
    reload,
    pageSize,
  };
}
