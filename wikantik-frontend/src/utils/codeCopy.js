/**
 * addCopyButtons — inject copy-to-clipboard buttons into every <pre> block
 * found in `container`.
 *
 * Idempotent: guarded by a `data-copy-injected` attribute so calling this
 * function twice on the same DOM tree does not duplicate buttons.
 *
 * @param {Element} container - The DOM element to search for <pre> blocks.
 * @param {{ onCopy: () => void, onError: (err: Error) => void }} callbacks
 */
export function addCopyButtons(container, { onCopy, onError } = {}) {
  if (!container) return;

  const pres = container.querySelectorAll('pre');
  pres.forEach((pre) => {
    if (pre.dataset.copyInjected) return; // idempotent guard
    pre.dataset.copyInjected = '1';

    const btn = document.createElement('button');
    btn.className = 'code-copy-btn';
    btn.setAttribute('aria-label', 'Copy code');
    btn.textContent = 'Copy';
    btn.type = 'button';

    btn.addEventListener('click', () => {
      // Prefer <code> contents; fall back to all text nodes except the button itself.
      const codeEl = pre.querySelector('code');
      const text = codeEl ? codeEl.textContent : (pre.textContent || '').replace(/^Copy/, '');
      navigator.clipboard.writeText(text).then(
        () => {
          btn.textContent = 'Copied';
          setTimeout(() => { btn.textContent = 'Copy'; }, 2000);
          if (typeof onCopy === 'function') onCopy();
        },
        (err) => {
          if (typeof onError === 'function') {
            onError(err);
          } else {
            console.warn('[codeCopy] clipboard write failed', err?.message || err);
          }
        },
      );
    });

    // Position the wrapper so the button sits inside the pre (top-right).
    // We insert the button as the first child of the pre rather than wrapping
    // it, keeping the original pre structure intact for downstream selectors.
    pre.style.position = 'relative';
    pre.insertBefore(btn, pre.firstChild);
  });
}
