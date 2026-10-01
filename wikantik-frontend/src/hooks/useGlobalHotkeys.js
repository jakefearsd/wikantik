import { useEffect } from 'react';

const MODES = { k: 'pages', o: 'pages', p: 'commands' };

/**
 * App-wide shortcuts: Mod-K / Mod-O open the quick overlay on pages, Mod-P on commands. A keydown something
 * else already handled (e.g. CodeMirror's Mod-K "insert link") is ignored.
 */
export function useGlobalHotkeys({ onOpenOverlay } = {}) {
  useEffect(() => {
    const handler = (e) => {
      if (e.defaultPrevented || !(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey) return;
      const mode = MODES[e.key?.toLowerCase()];
      if (!mode) return;
      e.preventDefault();
      onOpenOverlay?.(mode);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onOpenOverlay]);
}
