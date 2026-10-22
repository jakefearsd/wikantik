// Any of these means the reader has taken over scrolling (or moved to another fragment).
const READER_SCROLL_INPUTS = ['wheel', 'touchstart', 'keydown', 'pointerdown', 'hashchange', 'popstate'];

/**
 * pinFragmentTarget — scroll a URL fragment's target (`/wiki/Foo#section`) to
 * the top of the viewport and keep it there while the page settles.
 *
 * One scroll is not enough on a cold load: web-font swaps, KaTeX, code
 * highlighting and blocks rendered late above the section reflow the page
 * after it, pushing the section hundreds of pixels away. Every animation frame
 * re-lands the section if it has moved, until the reader's first scroll input
 * or `settleMs` elapses. The target is looked up by id each frame, so a
 * refetch that replaces the article DOM does not lose it.
 *
 * @param {Element} root - Only an element inside root counts as the target.
 * @param {string} id - The fragment, without its leading '#'.
 * @param {{ settleMs?: number }} [options]
 * @returns {(() => void) | null} stop function, or null when the target is not rendered.
 */
export function pinFragmentTarget(root, id, { settleMs = 4000 } = {}) {
  const find = () => {
    const el = document.getElementById(id);
    return el && root.contains(el) ? el : null;
  };
  const land = (el) => {
    el.scrollIntoView({ block: 'start', behavior: 'instant' });
    return el.getBoundingClientRect().top;
  };

  const target = find();
  if (!target) return null;
  let landedTop = land(target);

  let frame;
  let settleTimer;
  const stop = () => {
    cancelAnimationFrame(frame);
    clearTimeout(settleTimer);
    READER_SCROLL_INPUTS.forEach((type) => window.removeEventListener(type, stop, true));
  };
  const check = () => {
    const el = find();
    // Near the end of the page the section cannot reach the top, so compare
    // against where it actually landed rather than against zero.
    if (el && Math.abs(el.getBoundingClientRect().top - landedTop) > 1) landedTop = land(el);
    frame = requestAnimationFrame(check);
  };
  frame = requestAnimationFrame(check);
  settleTimer = setTimeout(stop, settleMs);
  READER_SCROLL_INPUTS.forEach((type) => window.addEventListener(type, stop, { capture: true, passive: true }));
  return stop;
}
