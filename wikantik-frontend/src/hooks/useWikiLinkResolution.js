import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import { MAX_NAMES } from '../utils/pageNameQuery';
import { collectNativeWikiLinkTargets } from '../utils/wikiLinkSyntax';

const RESOLVE_DELAY_MS = 500;
const FAILURE_BACKOFF_MS = 5000;

function sameMap(a, b) {
  if (a.size !== b.size) return false;
  for (const [k, v] of a) if (!b.has(k) || b.get(k) !== v) return false;
  return true;
}

/**
 * Map of lowercased native-wikilink target -> canonical page name (string) or null when the page does
 * not exist / is not viewable. Resolved through GET /api/pages?names=&resolve=true, 500 ms after the
 * last edit. Results are cached for the editing session; names already in flight, or that failed
 * within the last few seconds, are not re-requested. Each chunk's result is applied independently.
 */
export function useWikiLinkResolution(markdown) {
  const [resolved, setResolved] = useState(() => new Map());
  const current = useRef(resolved);
  const known = useRef(new Map()); // lowercased target -> string | null
  const inflight = useRef(new Set());
  const failedUntil = useRef(new Map());
  const targetsRef = useRef([]);
  const controller = useRef(null);

  useEffect(() => {
    controller.current = new AbortController();
    return () => controller.current.abort();
  }, []);

  useEffect(() => {
    const publish = () => {
      const next = new Map();
      targetsRef.current.forEach((t) => {
        const key = t.toLowerCase();
        if (known.current.has(key)) next.set(key, known.current.get(key));
      });
      if (sameMap(next, current.current)) return;
      current.current = next;
      setResolved(next);
    };

    const resolveChunk = (names, signal) => {
      names.forEach((n) => inflight.current.add(n.toLowerCase()));
      return api.listPages({ names, resolve: true, limit: MAX_NAMES, signal })
        .then((r) => {
          Object.entries(r?.resolved || {}).forEach(([k, v]) => known.current.set(k.toLowerCase(), v ?? null));
        })
        .catch((err) => {
          if (signal.aborted) return;
          console.warn('[wikilink-resolve] resolution failed for', names, err?.message || err);
          names.forEach((n) => failedUntil.current.set(n.toLowerCase(), Date.now() + FAILURE_BACKOFF_MS));
        })
        .finally(() => {
          names.forEach((n) => inflight.current.delete(n.toLowerCase()));
          if (!signal.aborted) publish();
        });
    };

    const id = setTimeout(() => {
      const targets = collectNativeWikiLinkTargets(markdown);
      targetsRef.current = targets;
      const seen = new Set();
      const now = Date.now();
      const unknown = targets.filter((t) => {
        const key = t.toLowerCase();
        if (seen.has(key)) return false;
        seen.add(key);
        return !known.current.has(key) && !inflight.current.has(key) && !(failedUntil.current.get(key) > now);
      });
      publish();
      const { signal } = controller.current;
      for (let i = 0; i < unknown.length; i += MAX_NAMES) resolveChunk(unknown.slice(i, i + MAX_NAMES), signal);
    }, RESOLVE_DELAY_MS);
    return () => clearTimeout(id);
  }, [markdown]);

  return resolved;
}
