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
        // AltGr reports ctrl+alt on Windows (Polish AltGr+N types `ń`): off-Mac the key must really be `n`, so a
        // composed character is never stolen. getModifierState('AltGraph') is deliberately not consulted: Windows
        // reports it for a plain Ctrl+Alt and Firefox on macOS for Option, which would disable the shortcut there.
        // macOS Option rewrites e.key, so the Cmd path matches on e.code alone.
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
