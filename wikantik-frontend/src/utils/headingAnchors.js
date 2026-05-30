/**
 * addHeadingAnchors — append a hover-reveal anchor link to each h2/h3 that
 * already has an `id` attribute (assigned by the Phase-2 TOC effect).
 *
 * Idempotent: guarded by a `data-anchor-injected` attribute so calling this
 * function twice on the same DOM tree does not duplicate anchors.
 *
 * @param {Element} container - The DOM element to search for h2/h3 headings.
 */
export function addHeadingAnchors(container) {
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
    anchor.setAttribute('aria-label', `Link to section: ${text}`);
    // Use '#' as the visible glyph — no SVG dependency in a DOM helper.
    anchor.textContent = '#';

    el.appendChild(anchor);
  });
}
