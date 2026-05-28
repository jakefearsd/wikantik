/** Compute viewport-relative coordinates of the caret inside a <textarea>.
 *  Uses the standard "mirror div" technique: clone the textarea's styling
 *  into a hidden element, place a span at the caret position, read its
 *  bounding rect. Returns {top, left} viewport coords pointing at the line
 *  BELOW the caret (offset by line-height), suitable for anchoring a popover
 *  immediately under the in-flight character. */
export function getCaretCoordinates(textarea) {
  if (!textarea) return { top: 0, left: 0 };
  const value = textarea.value || '';
  const selectionStart = textarea.selectionStart ?? 0;
  const before = value.substring(0, selectionStart);

  const computed = (typeof window !== 'undefined' && window.getComputedStyle)
    ? window.getComputedStyle(textarea)
    : null;
  const mirror = document.createElement('div');
  document.body.appendChild(mirror);

  // Copy the styles that affect text layout. This list is intentionally
  // exhaustive: any missing prop can throw off the caret position by pixels.
  const PROPS = [
    'boxSizing', 'width', 'height', 'overflowX', 'overflowY',
    'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth',
    'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
    'fontStyle', 'fontVariant', 'fontWeight', 'fontStretch', 'fontSize',
    'lineHeight', 'fontFamily', 'textAlign', 'textTransform', 'textIndent',
    'textDecoration', 'letterSpacing', 'wordSpacing', 'tabSize', 'MozTabSize',
  ];
  if (computed) PROPS.forEach((p) => { mirror.style[p] = computed[p]; });

  // Mirror-specific overrides.
  mirror.style.position = 'absolute';
  mirror.style.visibility = 'hidden';
  mirror.style.whiteSpace = 'pre-wrap';
  mirror.style.wordWrap = 'break-word';
  mirror.style.top = '0px';
  mirror.style.left = '-9999px';

  mirror.textContent = before;
  const span = document.createElement('span');
  span.textContent = value.substring(selectionStart) || '.';
  mirror.appendChild(span);

  const taRect = textarea.getBoundingClientRect();
  const spanRect = span.getBoundingClientRect();
  const mirrorRect = mirror.getBoundingClientRect();

  const scrollTop = textarea.scrollTop || 0;
  const scrollLeft = textarea.scrollLeft || 0;
  const top = taRect.top + (spanRect.top - mirrorRect.top) - scrollTop;
  const left = taRect.left + (spanRect.left - mirrorRect.left) - scrollLeft;

  // Drop the popover BELOW the caret line.
  const lineHeight = computed
    ? (parseFloat(computed.lineHeight) || parseFloat(computed.fontSize) * 1.2 || 20)
    : 20;

  document.body.removeChild(mirror);
  return { top: top + lineHeight + 2, left };
}

export default getCaretCoordinates;
