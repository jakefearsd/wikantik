import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import { MAX_NAMES } from '../utils/pageNameQuery';
import { collectWikiLinkTargets } from '../utils/wikiLinkTargets';

const CHECK_DELAY_MS = 500;

/**
 * Lowercased names of pages linked from `markdown` that do not exist (or that the caller cannot
 * view). Checked in batches through GET /api/pages?names=, 500 ms after the last edit; results are
 * cached for the editing session so only newly typed targets hit the server.
 */
export function useMissingPages(markdown) {
  const [missing, setMissing] = useState(() => new Set());
  const known = useRef(new Map()); // lowercased name → exists

  useEffect(() => {
    let cancelled = false;
    const id = setTimeout(() => {
      const targets = collectWikiLinkTargets(markdown);
      const unknown = targets.filter((t) => !known.current.has(t.toLowerCase()));
      const chunks = [];
      for (let i = 0; i < unknown.length; i += MAX_NAMES) chunks.push(unknown.slice(i, i + MAX_NAMES));
      Promise.all(chunks.map((names) => api.listPages({ names, limit: MAX_NAMES })))
        .then((results) => {
          const existing = new Set(results.flatMap((r) => (r.pages || []).map((p) => p.name.toLowerCase())));
          unknown.forEach((t) => known.current.set(t.toLowerCase(), existing.has(t.toLowerCase())));
          if (!cancelled) {
            setMissing(new Set(targets.map((t) => t.toLowerCase()).filter((t) => known.current.get(t) === false)));
          }
        })
        .catch((err) => console.warn('[missing-pages] existence check failed', err?.message || err));
    }, CHECK_DELAY_MS);
    return () => { cancelled = true; clearTimeout(id); };
  }, [markdown]);

  return missing;
}
