import { useDeferredValue, useEffect, useState } from 'react';

/** A page this small is always previewed live, whatever the last render cost (it cannot be slow). */
export const PREVIEW_ALWAYS_LIVE_CHARS = 2000;
/** A preview render costing more than this (ms) switches the preview to "render when typing pauses". */
export const PREVIEW_FRAME_BUDGET_MS = 12;
/** How long typing must pause before a slow preview re-renders. */
export const PREVIEW_SETTLE_MS = 300;

/**
 * The text the editor preview renders, and the meter the preview reports its render cost to.
 *
 * react-markdown re-processes the WHOLE page on every run, synchronously (~4 µs per character: ~150 ms for a
 * 2,000-line page). So the preview follows every keystroke only while its last render fit a frame budget;
 * past it, the preview shows the text as of the last pause in typing (`settled`, which always trails the text
 * by one pause, so switching modes never shows older text). A page that was never previewed (first load) is
 * shown on the next tick. The result is a deferred value either way: a keystroke's own render commits first.
 */
export function usePreviewSource(text) {
  const [settled, setSettled] = useState(text);
  const meter = useState(() => ({ ms: 0 }))[0];
  useEffect(() => {
    if (settled === text) return undefined;
    const id = setTimeout(() => setSettled(text), settled === '' ? 0 : PREVIEW_SETTLE_MS);
    return () => clearTimeout(id);
  }, [text, settled]);
  const live = text.length <= PREVIEW_ALWAYS_LIVE_CHARS || meter.ms <= PREVIEW_FRAME_BUDGET_MS;
  return [useDeferredValue(live ? text : settled), meter];
}
