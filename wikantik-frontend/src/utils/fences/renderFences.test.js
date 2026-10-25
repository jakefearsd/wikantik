import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('./mermaidRenderer.js', () => ({
  draw: vi.fn(async (host, source) => {
    if (source.includes('BAD')) throw new Error('Parse error on line 1:');
    host.innerHTML = '<svg viewBox="0 0 100 50"><g></g></svg>';
  }),
}));

import * as rendererModule from './mermaidRenderer.js';
import { draw } from './mermaidRenderer.js';
import { renderFences } from './renderFences';
import { FENCE_RENDERERS } from './registry';

const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

// The server escapes the source; happy-dom's parser (like a browser's) must never see a raw `-->`.
function figure(source, kind = 'browser') {
  const html = `<figure class="wk-fence" data-fence="mermaid" data-fence-kind="${kind}">`
    + (kind === 'server' ? '<div class="wk-fence-drawing"><svg></svg></div>' : '')
    + `<pre class="wk-fence-source"><code class="language-mermaid">${escapeHtml(source)}</code></pre></figure>`;
  return html;
}

function mount(html) {
  const root = document.createElement('article');
  root.innerHTML = html;
  document.body.appendChild(root);
  return root;
}

beforeEach(() => {
  vi.clearAllMocks();
  // vitest 4 resolves only the first of several CONCURRENT dynamic imports of a mocked module to the mock; the
  // others get the real module (real Mermaid). So `load` hands back the mocked namespace directly.
  vi.spyOn(FENCE_RENDERERS.mermaid, 'load').mockImplementation(async () => rendererModule);
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  document.documentElement.setAttribute('data-theme', 'light');
});
afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('renderFences', () => {
  it('ignores a missing root', async () => {
    await expect(renderFences(null)).resolves.toBeUndefined();
  });

  it('idempotent', async () => {
    const root = mount(figure('flowchart TD\n A-->B') + figure('flowchart TD\n C-->D'));
    await renderFences(root);
    await renderFences(root);
    expect(draw).toHaveBeenCalledTimes(2);
    expect(root.querySelectorAll('[data-fence-state="drawn"]')).toHaveLength(2);
    expect(root.querySelectorAll('.wk-fence-drawing')).toHaveLength(2);
  });

  it('isolatesFailures', async () => {
    const root = mount(figure('flowchart TD\n BAD') + figure('flowchart TD\n A-->B'));
    await renderFences(root);
    const [bad, good] = root.querySelectorAll('figure');
    expect(bad.dataset.fenceState).toBe('failed');
    expect(good.dataset.fenceState).toBe('drawn');
  });

  it('noImportWithoutFences', async () => {
    const load = vi.spyOn(FENCE_RENDERERS.mermaid, 'load');
    const root = mount('<p>no diagrams</p><pre><code class="language-mermaid">flowchart TD</code></pre>');
    await renderFences(root);
    expect(load).not.toHaveBeenCalled();
    expect(draw).not.toHaveBeenCalled();
  });

  it('skipsServerKind', async () => {
    const root = mount(figure('x', 'server'));
    const before = root.innerHTML;
    await renderFences(root);
    expect(draw).not.toHaveBeenCalled();
    expect(root.innerHTML).toBe(before);
  });

  // A hand-written browser-kind envelope for a server language (allowHTML on) must not make every reader's browser
  // POST it to /api/fences/render: the reader pass leaves it as source. Editor previews draw through drawFence.
  it('leavesBrowserKindServerLanguageAsSource', async () => {
    const load = vi.spyOn(FENCE_RENDERERS.plantuml, 'load');
    const handWritten = '<figure class="wk-fence" data-fence="plantuml" data-fence-kind="browser">'
      + '<pre class="wk-fence-source"><code class="language-plantuml">A -&gt; B</code></pre></figure>';
    const root = mount(handWritten + figure('flowchart TD\n A-->B'));
    const before = root.querySelector('figure').outerHTML;
    await renderFences(root);
    expect(load).not.toHaveBeenCalled();
    const [plantuml, mermaid] = root.querySelectorAll('figure');
    expect(plantuml.outerHTML).toBe(before);
    expect(plantuml.hasAttribute('data-fence-state')).toBe(false);
    expect(mermaid.dataset.fenceState).toBe('drawn');
  });

  it('serverFiguresDecoratedWithoutLoadingRenderers', async () => {
    const load = vi.spyOn(FENCE_RENDERERS.plantuml, 'load');
    const root = mount('<figure class="wk-fence" data-fence="plantuml" data-fence-kind="server" data-fence-state="drawn">'
      + '<div class="wk-fence-drawing"><div class="wk-fence-variant" data-variant="light"><svg></svg></div></div>'
      + '<pre class="wk-fence-source"><code class="language-plantuml">A -&gt; B</code></pre></figure>'
      + '<figure class="wk-fence" data-fence="plantuml" data-fence-kind="server" data-fence-error="include">'
      + '<pre class="wk-fence-source"><code class="language-plantuml">!include x</code></pre></figure>');
    await renderFences(root);
    expect(load).not.toHaveBeenCalled();
    expect(draw).not.toHaveBeenCalled();
    const [drawn, refused] = root.querySelectorAll('figure');
    expect(drawn.querySelector('.wk-fence-toolbar')).not.toBeNull();
    expect(refused.dataset.fenceState).toBe('refused');
    expect(refused.querySelector('.wk-fence-error')).not.toBeNull();
  });

  it('writes nothing once stale', async () => {
    const root = mount(figure('flowchart TD\n A-->B'));
    let stale = false;
    const p = renderFences(root, () => stale);
    stale = true;
    await p;
    expect(root.querySelector('figure').hasAttribute('data-fence-state')).toBe(false);
  });

  it('redrawsOnThemeChange', async () => {
    const root = mount(figure('flowchart TD\n A-->B') + figure('flowchart TD\n click A call f()'));
    await renderFences(root);
    expect(draw).toHaveBeenCalledTimes(1);
    const firstDrawing = root.querySelector('.wk-fence-drawing');

    document.documentElement.setAttribute('data-theme', 'dark');
    await vi.waitFor(() => expect(draw).toHaveBeenCalledTimes(2));
    expect(draw.mock.calls[1][2]).toEqual({ dark: true });
    await vi.waitFor(() => expect(root.querySelector('.wk-fence-drawing')).not.toBe(firstDrawing));
    const fig = root.querySelector('figure');
    expect(fig.querySelectorAll('.wk-fence-drawing')).toHaveLength(1);
    expect(fig.querySelectorAll('.wk-fence-toolbar')).toHaveLength(1);
    expect(fig.dataset.fenceState).toBe('drawn');
    // the refused figure is never drawn by a theme change
    expect(root.querySelectorAll('figure')[1].querySelector('.wk-fence-drawing')).toBeNull();
  });

  it('keeps the old drawing when a theme re-draw fails', async () => {
    const root = mount(figure('flowchart TD\n A-->B'));
    await renderFences(root);
    const firstDrawing = root.querySelector('.wk-fence-drawing');
    draw.mockRejectedValueOnce(new Error('boom'));
    document.documentElement.setAttribute('data-theme', 'dark');
    await vi.waitFor(() => expect(console.warn).toHaveBeenCalled());
    expect(root.querySelector('.wk-fence-drawing')).toBe(firstDrawing);
    expect(root.querySelector('figure').dataset.fenceState).toBe('drawn');
  });

  it('ignores attribute changes that do not change the theme', async () => {
    const root = mount(figure('flowchart TD\n A-->B'));
    await renderFences(root);
    document.documentElement.setAttribute('data-theme', 'light');
    document.documentElement.setAttribute('lang', 'en');
    await new Promise((r) => setTimeout(r, 20));
    expect(draw).toHaveBeenCalledTimes(1);
  });
});
