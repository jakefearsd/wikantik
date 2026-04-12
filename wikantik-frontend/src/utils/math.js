import katex from 'katex';

/**
 * Scans a DOM container for math elements rendered by the Flexmark GitLab extension
 * and renders them with KaTeX.
 *
 * Inline math: <span class="math-inline">...</span>
 * Display math: <div class="math-display">...</div>
 */
export function renderMath(container) {
  if (!container) return;

  container.querySelectorAll('.math-inline').forEach(el => {
    if (el.classList.contains('math-rendered')) return;
    try {
      katex.render(el.textContent, el, { displayMode: false, throwOnError: false });
      el.classList.add('math-rendered');
    } catch (e) {
      el.classList.add('math-error');
    }
  });

  container.querySelectorAll('.math-display').forEach(el => {
    if (el.classList.contains('math-rendered')) return;
    try {
      katex.render(el.textContent, el, { displayMode: true, throwOnError: false });
      el.classList.add('math-rendered');
    } catch (e) {
      el.classList.add('math-error');
    }
  });
}
