import { useDeferredValue, useEffect, useState } from 'react';

/** A page this small is always previewed live, whatever the last render cost (it cannot be slow). */
export const PREVIEW_ALWAYS_LIVE_CHARS = 2000;
/** A preview render costing more than this (ms) switches the preview to "render when typing pauses". */
export const PREVIEW_FRAME_BUDGET_MS = 12;
/** ...and only a render cheaper than this switches it back (hysteresis: cost noise must not flip modes). */
export const PREVIEW_LIVE_AGAIN_MS = 6;
/** How long typing must pause before a slow preview re-renders. */
export const PREVIEW_SETTLE_MS = 300;

/**
 * The text the editor preview renders, and the meter the preview reports its render cost to.
 *
 * react-markdown re-processes the WHOLE page on every run, synchronously (~4 µs per character: ~150 ms for a
 * 2,000-line page). So the preview follows every keystroke while its renders fit a frame budget; past it, it
 * shows the text as of the last pause in typing. The switch has hysteresis (slow above 12 ms, live again only
 * below 6 ms), and the preview never goes back to text older than what it last showed: every text gets a
 * version, and a stale settled text loses to the last shown one. `key` (the page name) starts over on
 * another page, so a previous page's text is never shown. A page that was never previewed (first load) is
 * shown on the next tick. The result is a deferred value either way: a keystroke's own render commits first.
 *
 * The returned meter is a PreviewTracker: the preview writes `ms` after each render; the rest is this hook's
 * bookkeeping, updated idempotently during render (same inputs, same state), so a repeated render is harmless.
 */
export function usePreviewSource(text, key) {
  const [tracker] = useState(() => new PreviewTracker(text, key));
  const [settled, setSettled] = useState({ text, version: 0, key });
  const current = tracker.observe(text, key);
  const sameKey = settled.key === key;

  useEffect(() => {
    if (sameKey && settled.text === text) return undefined;
    const delay = !sameKey || settled.text === '' ? 0 : PREVIEW_SETTLE_MS;
    const id = setTimeout(() => setSettled({ text, version: current.version, key }), delay);
    return () => clearTimeout(id);
  }, [text, key, sameKey, settled, current.version]);

  const shown = tracker.choose(current, sameKey ? settled : null, text.length <= PREVIEW_ALWAYS_LIVE_CHARS);
  return [useDeferredValue(shown.text), tracker];
}

/** Versions each distinct text, holds the live/slow mode (with hysteresis) and the last text shown. */
export class PreviewTracker {
  constructor(text, key) {
    this.ms = 0; // the preview's last render cost, written by the preview
    this.slow = false;
    this.key = key;
    this.text = text;
    this.version = 0;
    this.shown = { text, version: 0 };
  }

  /** Record the current text (and page); returns it with its version. Another page starts over, live. */
  observe(text, key) {
    if (this.key !== key) {
      this.key = key;
      this.ms = 0;
      this.slow = false;
      this.text = text;
      this.version += 1;
      this.shown = { text, version: this.version };
    } else if (this.text !== text) {
      this.text = text;
      this.version += 1;
    }
    return { text, version: this.version };
  }

  /** The text to show: current when live, else the settled one — but never older than the last shown. */
  choose(current, settled, alwaysLive) {
    if (this.slow ? this.ms < PREVIEW_LIVE_AGAIN_MS : this.ms > PREVIEW_FRAME_BUDGET_MS) this.slow = !this.slow;
    let shown = alwaysLive || !this.slow || !settled ? current : settled;
    if (shown.version < this.shown.version) shown = this.shown;
    this.shown = shown;
    return shown;
  }
}
