import { useEffect } from 'react';

const MODES = { k: 'pages', o: 'pages', p: 'commands' };

/**
 * App-wide shortcuts: Mod-K / Mod-O open the quick overlay on pages, Mod-P on commands, Mod-Alt-N opens today's daily note. A keydown something
 * else already handled (e.g. CodeMirror's Mod-K "insert link") is ignored.
 */
export function useGlobalHotkeys({ onOpenOverlay, onDailyNote } = {}) {
  useEffect(() => {
    const handler = (e) => {
      if (!e.defaultPrevented && (e.metaKey || e.ctrlKey) && e.altKey && !e.shiftKey && e.code === 'KeyN') {
        // AltGr reports ctrl+alt on Windows (Polish AltGr+N types `ń`): never steal a character. macOS Option
        // rewrites e.key, so only non-Mac layouts can also require the key to be `n`.
        if (e.getModifierState?.('AltGraph')) return;
        if (!e.metaKey && e.key?.toLowerCase() !== 'n') return;
        if (onDailyNote) { e.preventDefault(); onDailyNote(); }
        return;
      }
      if (e.defaultPrevented || !(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey) return;
      const mode = MODES[e.key?.toLowerCase()];
      if (!mode) return;
      e.preventDefault();
      onOpenOverlay?.(mode);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onOpenOverlay, onDailyNote]);
}
