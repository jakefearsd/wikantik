import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Counts (and can fail) evaluations of the fence pass module, to prove renderFences loads it only on demand.
const state = vi.hoisted(() => ({ loads: 0 }));
vi.mock('./drawFence.js', async (importOriginal) => {
  state.loads += 1;
  return importOriginal();
});
vi.mock('./mermaidRenderer.js', () => ({ draw: vi.fn() }));

const figure = '<figure class="wk-fence" data-fence="mermaid" data-fence-kind="browser">'
  + '<pre class="wk-fence-source"><code class="language-mermaid">flowchart TD\n  click A call f()</code></pre></figure>';

function mount(html) {
  const root = document.createElement('article');
  root.innerHTML = html;
  document.body.appendChild(root);
  return root;
}

beforeEach(() => {
  vi.resetModules();
  state.loads = 0;
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('renderFences loads the fence pass lazily', () => {
  it('never loads drawFence for a page without fences, and loads it once there is one', async () => {
    const { renderFences } = await import('./renderFences.js');
    await renderFences(mount('<pre><code class="language-js">x</code></pre>'));
    expect(state.loads).toBe(0);
    const root = mount(figure);
    await renderFences(root);
    expect(state.loads).toBe(1);
    expect(root.querySelector('figure').dataset.fenceState).toBe('refused');
  });

  it('leaves the source untouched and warns when the pass cannot be loaded', async () => {
    vi.doMock('./drawFence.js', () => { throw new Error('chunk failed to load'); });
    const { renderFences } = await import('./renderFences.js');
    const root = mount(figure);
    const before = root.innerHTML;
    await renderFences(root);
    expect(root.innerHTML).toBe(before);
    expect(console.warn).toHaveBeenCalledWith('[fences] could not load the fence pass; diagrams stay as source',
      expect.any(Error));
  });
});
