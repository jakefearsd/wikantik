import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import { collectNativeWikiLinkTargets } from '../utils/wikiLinkSyntax';

const RESOLVE_DELAY_MS = 500;
const MAX_NAMES = 50;

/**
 * Map of lowercased native-wikilink target -> canonical page name (string) or null when the page does
 * not exist / is not viewable. Resolved through GET /api/pages?names=&resolve=true, 500 ms after the
 * last edit, cached for the editing session so only newly typed targets hit the server.
 */
export function useWikiLinkResolution(markdown) {
  const [resolved, setResolved] = useState(() => new Map());
  const known = useRef(new Map()); // lowercased target -> string | null

  useEffect(() => {
    let cancelled = false;
    const id = setTimeout(() => {
      const targets = collectNativeWikiLinkTargets(markdown);
      const unknown = targets.filter((t) => !known.current.has(t.toLowerCase()));
      const chunks = [];
      for (let i = 0; i < unknown.length; i += MAX_NAMES) chunks.push(unknown.slice(i, i + MAX_NAMES));
      Promise.all(chunks.map((names) => api.listPages({ names, resolve: true, limit: MAX_NAMES })))
        .then((results) => {
          results.forEach((r) => Object.entries(r?.resolved || {}).forEach(([k, v]) => {
            known.current.set(k.toLowerCase(), v ?? null);
          }));
          if (cancelled) return;
          const next = new Map();
          targets.forEach((t) => {
            const key = t.toLowerCase();
            if (known.current.has(key)) next.set(key, known.current.get(key));
          });
          setResolved(next);
        })
        .catch((err) => console.warn('[wikilink-resolve] resolution failed for', unknown, err?.message || err));
    }, RESOLVE_DELAY_MS);
    return () => { cancelled = true; clearTimeout(id); };
  }, [markdown]);

  return resolved;
}
