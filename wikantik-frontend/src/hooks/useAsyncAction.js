import { useState, useEffect, useCallback, useRef } from 'react';

/**
 * The busy/error half of a mutation (save, delete, submit): `run(...args)` is ignored
 * while a run is in flight, clears the error, sets `busy`, calls `fn(...args)` (a
 * promise) and then `onSuccess(result)`, awaiting what it returns. A rejection sets `error` to the error's
 * message, or `fallbackMessage` when it has none, and `run` resolves `undefined`.
 * `busy` is cleared either way, and nothing sets state after unmount.
 *
 * `fn` and `onSuccess` are read at call time, so they may close over current render state.
 *
 * @param {(...args: any[]) => Promise<any>} fn
 * @param {{fallbackMessage?: string, onSuccess?: (result: any) => any}} [options]
 * @returns {{run: (...args: any[]) => Promise<any>, busy: boolean, error: string|null,
 *   setError: Function, reset: () => void}}
 */
export default function useAsyncAction(fn, { fallbackMessage, onSuccess } = {}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const busyRef = useRef(false);
  const mountedRef = useRef(true);
  const latest = useRef({ fn, onSuccess, fallbackMessage });
  useEffect(() => {
    latest.current = { fn, onSuccess, fallbackMessage };
  });
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  const run = useCallback((...args) => {
    if (busyRef.current) return Promise.resolve(undefined);
    busyRef.current = true;
    setError(null);
    setBusy(true);
    const { fn: action, onSuccess: success, fallbackMessage: fallback } = latest.current;
    // Calls fn synchronously (a sync throw becomes a rejection) so the call lands inside the event handler.
    return new Promise((resolve) => resolve(action(...args)))
      .then((result) => {
        // A promise returned by onSuccess (a reload) is awaited, so busy stays set until it settles.
        if (mountedRef.current && success) return Promise.resolve(success(result)).then(() => result);
        return result;
      })
      .catch((err) => {
        if (mountedRef.current) setError(err?.message || fallback);
        return undefined;
      })
      .finally(() => {
        busyRef.current = false;
        if (mountedRef.current) setBusy(false);
      });
  }, []);

  const reset = useCallback(() => setError(null), []);

  return { run, busy, error, setError, reset };
}
