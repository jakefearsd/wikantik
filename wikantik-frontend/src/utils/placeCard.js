const GUTTER = 8;
const GAP = 6;

/**
 * Fixed-position top/left for a hover card of `size` next to a link `rect` inside `viewport`: below the link,
 * left-aligned with it, pulled back from the right edge, and flipped above the link when it doesn't fit below.
 */
export function placeCard(rect, size, viewport) {
  const maxLeft = Math.max(GUTTER, viewport.width - size.width - GUTTER);
  const left = Math.min(Math.max(GUTTER, rect.left), maxLeft);
  let top = rect.bottom + GAP;
  if (top + size.height > viewport.height - GUTTER) {
    top = Math.max(GUTTER, rect.top - GAP - size.height);
  }
  return { left: Math.round(left), top: Math.round(top) };
}
