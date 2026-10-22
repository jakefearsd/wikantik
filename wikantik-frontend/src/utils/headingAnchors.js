/**
 * addHeadingAnchors — append a hover-reveal anchor link to each h2/h3 that
 * already has an `id` attribute (assigned by the Phase-2 TOC effect).
 *
 * Clicking an anchor both follows the fragment (so the address bar shows the
 * section) and copies the absolute section URL to the clipboard.
 *
 * Idempotent: guarded by a `data-anchor-injected` attribute so calling this
 * function twice on the same DOM tree does not duplicate anchors.
 *
 * @param {Element} container - The DOM element to search for h2/h3 headings.
 * @param {{ onCopy: () => void, onError: (err: Error) => void }} callbacks
 */
export function addHeadingAnchors(container, { onCopy, onError } = {}) {
  if (!container) return;

  const headings = container.querySelectorAll('h2, h3');
  headings.forEach((el) => {
    const id = el.id;
    if (!id) return; // only annotate headings that already have an id
    if (el.dataset.anchorInjected) return; // idempotent guard
    el.dataset.anchorInjected = '1';

    const text = el.textContent.trim();

    const anchor = document.createElement('a');
    anchor.className = 'heading-anchor';
    anchor.setAttribute('href', `#${id}`);
    anchor.setAttribute('aria-label', `Copy link to section: ${text}`);
    anchor.setAttribute('title', 'Copy link to this section');
    // Use '#' as the visible glyph — no SVG dependency in a DOM helper.
    anchor.textContent = '#';

    anchor.addEventListener('click', () => {
      const url = `${window.location.origin}${window.location.pathname}#${id}`;
      // Promise.resolve().then so a missing clipboard API (insecure context) rejects instead of throwing.
      Promise.resolve().then(() => navigator.clipboard.writeText(url)).then(
        () => {
          if (typeof onCopy === 'function') onCopy();
        },
        (err) => {
          if (typeof onError === 'function') {
            onError(err);
          } else {
            console.warn('[headingAnchors] clipboard write failed', err?.message || err);
          }
        },
      );
    });

    el.appendChild(anchor);
  });
}
