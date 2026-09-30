import { useEffect, useState } from 'react';
import { loadLowlight } from '../utils/codeHighlight';

/** The highlighter once loaded (null before, or while `enabled` is false — no download for pages without code). */
export function useLowlight(enabled) {
  const [lowlight, setLowlight] = useState(null);
  useEffect(() => {
    if (!enabled || lowlight) return undefined;
    let alive = true;
    loadLowlight()
      .then((l) => { if (alive) setLowlight(l); })
      .catch((err) => console.warn('[highlight] failed to load the highlighter', err?.message || err));
    return () => { alive = false; };
  }, [enabled, lowlight]);
  return lowlight;
}
