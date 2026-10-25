import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('./mermaidRenderer.js', () => ({
  draw: vi.fn(async (host) => {
    host.innerHTML = '<svg viewBox="0 0 1000 200" style="max-width: 1000px;" width="100%"><g></g></svg>';
  }),
}));

import * as rendererModule from './mermaidRenderer.js';
import { draw } from './mermaidRenderer.js';
import { drawFence, redrawFence } from './drawFence';
import { FENCE_RENDERERS } from './registry';

const FLOW = 'flowchart TD\n  A-->B\n';
const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** Builds the server envelope (wikantik-main RenderedFenceNodeRenderer) and attaches it to the document. */
function envelope(source, { error, lang = 'mermaid', html } = {}) {
  const wrap = document.createElement('div');
  const err = error ? ` data-fence-error="${error}"` : '';
  wrap.innerHTML = `<figure class="wk-fence" data-fence="${lang}" data-fence-kind="browser"${err}>\n`
    + `<pre class="wk-fence-source"><code class="language-${lang}">${html ?? escapeHtml(source)}</code></pre>\n</figure>`;
  document.body.appendChild(wrap);
  return wrap.querySelector('figure');
}

const sourcePre = (fig) => fig.querySelector('pre.wk-fence-source');
const errorText = (fig) => fig.querySelector('.wk-fence-error')?.textContent ?? '';

beforeEach(() => {
  vi.clearAllMocks();
  // vitest 4 resolves only the first of several CONCURRENT dynamic imports of a mocked module to the mock; the
  // others get the real module (real Mermaid). So `load` hands back the mocked namespace directly.
  vi.spyOn(FENCE_RENDERERS.mermaid, 'load').mockImplementation(async () => rendererModule);
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  document.documentElement.removeAttribute('data-theme');
});
afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('drawFence', () => {
  it('drawsAndKeepsSourceInDom', async () => {
    const fig = envelope(FLOW);
    await expect(drawFence(fig)).resolves.toBe('drawn');
    expect(fig.dataset.fenceState).toBe('drawn');
    expect(fig.querySelector('.wk-fence-drawing svg')).not.toBeNull();
    expect(fig.querySelector('.wk-fence-toolbar')).not.toBeNull();
    expect(sourcePre(fig)).not.toBeNull();
    expect(sourcePre(fig).querySelector('code').textContent).toBe(FLOW);
    expect(fig.querySelector('.wk-fence-error')).toBeNull();
    // drawing sits between the toolbar and the source
    expect(fig.querySelector('.wk-fence-drawing').nextElementSibling).toBe(sourcePre(fig));
  });

  it('passesDecodedSourceToRenderer', async () => {
    const fig = envelope(null, { html: 'flowchart TD\n  A["a&lt;br/&gt;b &amp; c"]--&gt;B' });
    await drawFence(fig);
    expect(draw).toHaveBeenCalledTimes(1);
    const [host, source, opts] = draw.mock.calls[0];
    expect(host.classList.contains('wk-fence-drawing')).toBe(true);
    expect(source).toBe('flowchart TD\n  A["a<br/>b & c"]-->B');
    expect(opts).toEqual({ dark: false });
  });

  it('passes the dark theme when the document is dark', async () => {
    document.documentElement.setAttribute('data-theme', 'dark');
    await drawFence(envelope(FLOW));
    expect(draw.mock.calls[0][2]).toEqual({ dark: true });
  });

  it('re-draws once drawn when the theme flipped mid-draw', async () => {
    draw.mockImplementationOnce(async (host) => {
      document.documentElement.setAttribute('data-theme', 'dark');
      host.innerHTML = '<svg viewBox="0 0 10 10"></svg>';
    });
    const fig = envelope(FLOW);
    await drawFence(fig);
    await vi.waitFor(() => expect(draw).toHaveBeenCalledTimes(2));
    expect(draw.mock.calls[0][2]).toEqual({ dark: false });
    expect(draw.mock.calls[1][2]).toEqual({ dark: true });
  });

  it('refusedShowsReasonAndSource', async () => {
    const fig = envelope(FLOW, { error: 'callback' });
    await expect(drawFence(fig)).resolves.toBe('refused');
    expect(fig.dataset.fenceState).toBe('refused');
    expect(errorText(fig)).toMatch(/not drawn/i);
    expect(errorText(fig)).toMatch(/callback/i);
    expect(fig.querySelector('.wk-fence-error').getAttribute('role')).toBe('note');
    expect(sourcePre(fig).querySelector('code').textContent).toBe(FLOW);
    expect(draw).not.toHaveBeenCalled();
    expect(fig.querySelector('.wk-fence-drawing')).toBeNull();
  });

  it.each([
    ['unsafe-link', /link/i],
    ['unsafe-config', /configuration/i],
    ['render-failed', /could not be drawn/i],
    ['something-new', /not drawn/i],
  ])('maps the server reason %s to a human message', async (reason, re) => {
    const fig = envelope(FLOW, { error: reason });
    await expect(drawFence(fig)).resolves.toBe('refused');
    expect(errorText(fig)).toMatch(re);
  });

  // A hand-written envelope (allowed through the HTML sanitizer when allowHTML is on) never meets the server
  // policy, so this browser check is its only gate — drawFence.js relies on this test.
  it('clientPolicyRefusesEvenWithoutServerFlag', async () => {
    const fig = envelope('flowchart TD\n  A-->B\n  click A call f()');
    await expect(drawFence(fig)).resolves.toBe('refused');
    expect(fig.dataset.fenceState).toBe('refused');
    expect(errorText(fig)).toMatch(/callback/i);
    expect(sourcePre(fig)).not.toBeNull();
    expect(draw).not.toHaveBeenCalled();
  });

  it('parseFailureShowsErrorAndSource', async () => {
    draw.mockRejectedValueOnce(new Error("Parse error on line 3:\n...A-->\n-------^\nExpecting 'NODE', got 'EOF'\n  \n"));
    const fig = envelope(FLOW);
    await expect(drawFence(fig)).resolves.toBe('failed');
    expect(fig.dataset.fenceState).toBe('failed');
    expect(errorText(fig)).toMatch(/could not be drawn/i);
    // The first line says where, the last non-blank one what Mermaid expected; the caret art between is dropped.
    expect(fig.querySelector('.wk-fence-error-detail').textContent)
      .toBe("Mermaid: Parse error on line 3: \u2026 Expecting 'NODE', got 'EOF'");
    expect(fig.querySelector('.wk-fence-drawing')).toBeNull();
    expect(fig.querySelector('.wk-fence-toolbar')).toBeNull();
    expect(sourcePre(fig).querySelector('code').textContent).toBe(FLOW);
    expect(console.warn).toHaveBeenCalled();
  });

  it('reports a library that fails to load as failed, source kept', async () => {
    vi.spyOn(FENCE_RENDERERS.mermaid, 'load').mockRejectedValueOnce(new Error('chunk 404'));
    const fig = envelope(FLOW);
    await expect(drawFence(fig)).resolves.toBe('failed');
    expect(errorText(fig)).toMatch(/could not be loaded/i);
    expect(sourcePre(fig)).not.toBeNull();
  });

  it('marks a fence of an unregistered language failed instead of guessing', async () => {
    const fig = envelope('x', { lang: 'vega' });
    await expect(drawFence(fig)).resolves.toBe('failed');
    expect(errorText(fig)).toMatch(/vega/);
    expect(draw).not.toHaveBeenCalled();
  });

  it('toggleShowsSource', async () => {
    const fig = envelope(FLOW);
    await drawFence(fig);
    const btn = fig.querySelector('.wk-fence-toolbar button[data-action="source"]');
    expect(btn.textContent).toBe('Show source');
    expect(btn.getAttribute('aria-expanded')).toBe('false');
    btn.click();
    expect(sourcePre(fig).classList.contains('wk-fence-source-open')).toBe(true);
    expect(btn.textContent).toBe('Hide source');
    expect(btn.getAttribute('aria-expanded')).toBe('true');
    btn.click();
    expect(sourcePre(fig).classList.contains('wk-fence-source-open')).toBe(false);
    expect(btn.textContent).toBe('Show source');
  });

  it('staleWritesNothing', async () => {
    const fig = envelope(FLOW);
    const before = fig.outerHTML;
    let stale = false;
    const pending = drawFence(fig, () => stale);
    stale = true;
    await expect(pending).resolves.toBe('stale');
    expect(fig.outerHTML).toBe(before);
    expect(fig.hasAttribute('data-fence-state')).toBe(false);
  });

  it('a stale pass on a figure that is still drawn by a later pass leaves it drawn once', async () => {
    const fig = envelope(FLOW);
    let staleA = false;
    const a = drawFence(fig, () => staleA);
    staleA = true;
    const b = drawFence(fig);
    expect(await a).toBe('stale');
    expect(await b).toBe('drawn');
    expect(fig.querySelectorAll('.wk-fence-drawing')).toHaveLength(1);
    expect(fig.querySelectorAll('.wk-fence-toolbar')).toHaveLength(1);
  });

  it('two concurrent passes draw a figure once', async () => {
    const fig = envelope(FLOW);
    const [a, b] = await Promise.all([drawFence(fig), drawFence(fig)]);
    expect([a, b]).toEqual(['drawn', 'drawn']);
    expect(draw).toHaveBeenCalledTimes(1);
    expect(fig.querySelectorAll('.wk-fence-drawing')).toHaveLength(1);
  });

  it('returns the existing state for an already-processed figure without redrawing', async () => {
    const fig = envelope(FLOW);
    await drawFence(fig);
    await expect(drawFence(fig)).resolves.toBe('drawn');
    expect(draw).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['an unregistered language', { lang: 'vega' }, null],
    ['a server refusal', { error: 'callback' }, null],
    ['a library load failure', {}, 'load'],
    ['a draw failure', {}, 'draw'],
    ['a finished draw', {}, 'late'],
  ])('writes nothing when the pass turns stale before %s is shown', async (_name, opts, mode) => {
    if (mode === 'load') vi.spyOn(FENCE_RENDERERS.mermaid, 'load').mockRejectedValueOnce(new Error('404'));
    if (mode === 'draw') draw.mockRejectedValueOnce(new Error('Parse error'));
    const fig = envelope(FLOW, opts);
    const before = fig.outerHTML;
    // already stale by the time the pass looks (refusals are decided synchronously)
    let calls = 0;
    const isStale = () => (mode === 'late' || mode === 'draw' ? ++calls > 1 : true);
    await expect(drawFence(fig, isStale)).resolves.toBe('stale');
    expect(fig.outerHTML).toBe(before);
  });

  it('reports an unexpected internal error as failed without throwing', async () => {
    vi.spyOn(FENCE_RENDERERS.mermaid, 'check').mockImplementationOnce(() => { throw new Error('bug'); });
    const fig = envelope(FLOW);
    await expect(drawFence(fig)).resolves.toBe('failed');
    expect(console.warn).toHaveBeenCalledWith('[fences] drawing a fence failed unexpectedly', expect.any(Error));
  });

  it('shows a one-line Mermaid error once, not twice', async () => {
    draw.mockRejectedValueOnce(new Error('\n  No diagram type detected  \n'));
    const fig = envelope(FLOW);
    await drawFence(fig);
    expect(fig.querySelector('.wk-fence-error-detail').textContent).toBe('Mermaid: No diagram type detected');
  });

  it('shows no detail line when the parse error has no message', async () => {
    draw.mockRejectedValueOnce('');
    const fig = envelope(FLOW);
    await drawFence(fig);
    expect(fig.querySelector('.wk-fence-error-detail')).toBeNull();
    expect(errorText(fig)).toMatch(/could not be drawn/i);
  });

  it('treats a figure without data-fence as an unregistered language', async () => {
    const fig = envelope(FLOW);
    fig.removeAttribute('data-fence');
    await expect(drawFence(fig)).resolves.toBe('failed');
  });

  describe('redrawFence', () => {
    it('skips figures that are not drawn, or whose language is unknown', async () => {
      const fig = envelope(FLOW, { error: 'callback' });
      await drawFence(fig);
      await expect(redrawFence(fig)).resolves.toBe('skipped');
      const drawn = envelope(FLOW);
      await drawFence(drawn);
      drawn.dataset.fence = 'vega';
      await expect(redrawFence(drawn)).resolves.toBe('skipped');
    });

    it('re-checks the source before re-drawing and refuses one that no longer passes', async () => {
      const fig = envelope(FLOW);
      await drawFence(fig);
      const drawing = fig.querySelector('.wk-fence-drawing');
      fig.querySelector('.wk-fence-source code').textContent = 'flowchart TD\n  A-->B\n  click A call f()';
      await expect(redrawFence(fig)).resolves.toBe('refused');
      expect(draw).toHaveBeenCalledTimes(1);
      expect(fig.querySelector('.wk-fence-drawing')).toBe(drawing);
      expect(console.warn).toHaveBeenCalled();
    });

    it('drops a re-draw superseded by a newer one, keeping exactly one drawing', async () => {
      const fig = envelope(FLOW);
      await drawFence(fig);
      const first = redrawFence(fig);
      const second = redrawFence(fig);
      await expect(first).resolves.toBe('stale');
      await expect(second).resolves.toBe('drawn');
      expect(fig.querySelectorAll('.wk-fence-drawing')).toHaveLength(1);
    });

    it('drops a re-draw for a figure removed from the page', async () => {
      const fig = envelope(FLOW);
      await drawFence(fig);
      let release;
      draw.mockImplementationOnce((host) => new Promise((resolve) => { release = () => { host.innerHTML = '<svg></svg>'; resolve(); }; }));
      const pending = redrawFence(fig);
      await vi.waitFor(() => expect(release).toBeTypeOf('function'));
      fig.remove();
      release();
      await expect(pending).resolves.toBe('stale');
    });
  });

  describe('Expand', () => {
    const stubWidth = (w) => vi.spyOn(Element.prototype, 'getBoundingClientRect')
      .mockImplementation(() => ({ width: w, height: 100, top: 0, left: 0, right: w, bottom: 100, x: 0, y: 0 }));

    it('is offered only when the drawing is scaled below ~80% of its natural width', async () => {
      stubWidth(500);
      const small = envelope(FLOW);
      await drawFence(small);
      expect(small.querySelector('button[data-action="expand"]').hidden).toBe(false);

      stubWidth(900);
      const big = envelope(FLOW);
      await drawFence(big);
      expect(big.querySelector('button[data-action="expand"]').hidden).toBe(true);
    });

    it('stays hidden when the natural width is unknown', async () => {
      stubWidth(10);
      draw.mockImplementationOnce(async (host) => { host.innerHTML = '<svg width="100%"></svg>'; });
      const fig = envelope(FLOW);
      await drawFence(fig);
      expect(fig.querySelector('button[data-action="expand"]').hidden).toBe(true);
    });

    it('opens a dialog holding a natural-size clone, closed by the backdrop and the close button', async () => {
      stubWidth(500);
      const fig = envelope(FLOW);
      await drawFence(fig);
      fig.querySelector('button[data-action="expand"]').click();
      const dialog = document.querySelector('dialog.wk-fence-dialog');
      expect(dialog).not.toBeNull();
      expect(dialog.open).toBe(true);
      const clone = dialog.querySelector('svg');
      expect(clone).not.toBeNull();
      expect(clone).not.toBe(fig.querySelector('.wk-fence-drawing svg'));
      expect(clone.getAttribute('width')).toBe('1000');
      expect(clone.style.maxWidth).toBe('none');

      dialog.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(document.querySelector('dialog.wk-fence-dialog')).toBeNull();

      fig.querySelector('button[data-action="expand"]').click();
      const again = document.querySelector('dialog.wk-fence-dialog');
      again.querySelector('svg').dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(document.querySelector('dialog.wk-fence-dialog')).not.toBeNull();
      again.querySelector('button.wk-fence-dialog-close').click();
      expect(document.querySelector('dialog.wk-fence-dialog')).toBeNull();
    });

    it('reads the natural width from a max-width style when there is no viewBox', async () => {
      stubWidth(100);
      draw.mockImplementationOnce(async (host) => { host.innerHTML = '<svg style="max-width: 640px;"></svg>'; });
      const fig = envelope(FLOW);
      await drawFence(fig);
      expect(fig.querySelector('button[data-action="expand"]').hidden).toBe(false);
    });

    it('opens inline when showModal is unavailable', async () => {
      stubWidth(500);
      vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(() => { throw new Error('nope'); });
      const fig = envelope(FLOW);
      await drawFence(fig);
      fig.querySelector('button[data-action="expand"]').click();
      expect(document.querySelector('dialog.wk-fence-dialog').hasAttribute('open')).toBe(true);
      expect(console.warn).toHaveBeenCalled();
    });

    it('does nothing when the drawing has gone', async () => {
      stubWidth(500);
      const fig = envelope(FLOW);
      await drawFence(fig);
      fig.querySelector('.wk-fence-drawing').replaceChildren();
      fig.querySelector('button[data-action="expand"]').click();
      expect(document.querySelector('dialog.wk-fence-dialog')).toBeNull();
    });

    it('closes on Escape (the dialog cancel event)', async () => {
      stubWidth(500);
      const fig = envelope(FLOW);
      await drawFence(fig);
      fig.querySelector('button[data-action="expand"]').click();
      const dialog = document.querySelector('dialog.wk-fence-dialog');
      dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
      expect(document.querySelector('dialog.wk-fence-dialog')).toBeNull();
    });
  });
});
