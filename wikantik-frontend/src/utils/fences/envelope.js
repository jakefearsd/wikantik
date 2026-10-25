/**
 * Fills a `figure` with the rendered-fence envelope the server emits (see wikantik-main RenderedFenceNodeRenderer):
 *   <figure class="wk-fence" data-fence="mermaid" data-fence-kind="browser">
 *     <pre class="wk-fence-source"><code class="language-mermaid">source</code></pre>
 *   </figure>
 * so the editor previews feed drawFence and fences.css exactly what the reader view does. The source goes in as
 * text, never markup.
 */
export function fillFenceEnvelope(figure, language, source) {
  // A re-used figure (the editor preview re-fills the same element when the source changes) must look fresh to
  // drawFence: it records its outcome in data-fence-state (and reads data-fence-error), and replaceChildren below
  // drops everything else it adds (toolbar, drawing host, error box, dialog).
  delete figure.dataset.fenceState;
  figure.removeAttribute('data-fence-error');
  figure.className = 'wk-fence';
  figure.dataset.fence = language;
  figure.dataset.fenceKind = 'browser';
  const pre = document.createElement('pre');
  pre.className = 'wk-fence-source';
  const code = document.createElement('code');
  code.className = `language-${language}`;
  code.textContent = source;
  pre.append(code);
  figure.replaceChildren(pre);
  return figure;
}
