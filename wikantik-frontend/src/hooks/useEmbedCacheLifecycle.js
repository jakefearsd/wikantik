import { useEffect, useRef } from 'react';
import { clearWikiEmbedCache } from '../components/WikiEmbed';

/**
 * Keeps the module-level embed cache honest for an editor: cleared when the preview is re-opened
 * (so edits to embedded pages show up) but not on first mount (child effects run before the parent's,
 * so that would drop the initial in-flight promises), and cleared on unmount so it never outlives the editor.
 */
export function useEmbedCacheLifecycle(previewOpen) {
  const first = useRef(true);
  useEffect(() => {
    if (first.current) { first.current = false; return; }
    if (previewOpen) clearWikiEmbedCache();
  }, [previewOpen]);
  useEffect(() => clearWikiEmbedCache, []);
}
