import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../../api/client', () => ({ api: { renderFence: vi.fn() } }));

import { api } from '../../api/client';
import { draw } from './serverRenderer';
import { drawFence } from './drawFence';

const OK = { lightSvg: '<svg id="l"><g/></svg>', darkSvg: '<svg id="d"><g/></svg>' };

beforeEach(() => {
  vi.useFakeTimers();
  api.renderFence.mockReset();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('serverRenderer.draw', () => {
  it('debouncesThenFetches', async () => {
    api.renderFence.mockResolvedValue(OK);
    const host = document.createElement('div');
    const p = draw(host, 'A -> B', { language: 'plantuml', isStale: () => false });
    await vi.advanceTimersByTimeAsync(499);
    expect(api.renderFence).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    await p;
    expect(api.renderFence).toHaveBeenCalledWith('plantuml', 'A -> B');
  });

  it('staleSkipsFetch', async () => {
    const host = document.createElement('div');
    let stale = false;
    const p = draw(host, 'A -> B', { language: 'plantuml', isStale: () => stale });
    stale = true;
    await vi.advanceTimersByTimeAsync(600);
    await p;
    expect(api.renderFence).not.toHaveBeenCalled();
    expect(host.children).toHaveLength(0);
  });

  it('lateResponseAfterStaleIsDiscarded', async () => {
    let resolveFetch;
    api.renderFence.mockReturnValue(new Promise((r) => { resolveFetch = r; }));
    const host = document.createElement('div');
    host.innerHTML = '<p>untouched</p>';
    let stale = false;
    const p = draw(host, 'x', { language: 'plantuml', isStale: () => stale });
    await vi.advanceTimersByTimeAsync(500);
    expect(api.renderFence).toHaveBeenCalledTimes(1);
    stale = true;
    resolveFetch(OK);
    await p;
    expect(host.innerHTML).toBe('<p>untouched</p>');
    expect(host.hasAttribute('role')).toBe(false);
  });

  it('fillsBothVariants', async () => {
    api.renderFence.mockResolvedValue(OK);
    const host = document.createElement('div');
    const p = draw(host, 'A -> B', { language: 'plantuml', isStale: () => false });
    await vi.advanceTimersByTimeAsync(500);
    await p;
    const variants = host.querySelectorAll('.wk-fence-variant');
    expect(variants).toHaveLength(2);
    expect(variants[0].dataset.variant).toBe('light');
    expect(variants[0].hidden).toBe(false);
    expect(variants[0].querySelector('svg#l')).not.toBeNull();
    expect(variants[1].dataset.variant).toBe('dark');
    expect(variants[1].hidden).toBe(true);
    expect(variants[1].querySelector('svg#d')).not.toBeNull();
    expect(host.getAttribute('role')).toBe('img');
    expect(host.getAttribute('aria-label')).toBeTruthy();
  });

  it('errorCarriesLine', async () => {
    api.renderFence.mockResolvedValue({ error: 'syntax-error', message: 'Bad arrow', line: 3 });
    const host = document.createElement('div');
    const p = draw(host, 'x', { language: 'plantuml', isStale: () => false });
    const assertion = expect(p).rejects.toThrow('PlantUML line 3: Bad arrow');
    await vi.advanceTimersByTimeAsync(500);
    await assertion;
  });

  it('errorWithoutLineNamesTheReason', async () => {
    api.renderFence.mockResolvedValue({ error: 'too-large' });
    const p = draw(document.createElement('div'), 'x', { language: 'plantuml', isStale: () => false });
    const assertion = expect(p).rejects.toMatchObject({ message: 'PlantUML: too-large', fenceReason: 'too-large' });
    await vi.advanceTimersByTimeAsync(500);
    await assertion;
  });

  it('serverDraw401ShowsFailure', async () => {
    api.renderFence.mockRejectedValue(Object.assign(new Error('Unauthorized'), { status: 401 }));
    const wrap = document.createElement('div');
    wrap.innerHTML = '<figure class="wk-fence" data-fence="plantuml" data-fence-kind="browser">'
      + '<pre class="wk-fence-source"><code class="language-plantuml">A -&gt; B</code></pre></figure>';
    document.body.appendChild(wrap);
    const fig = wrap.querySelector('figure');
    const p = drawFence(fig);
    await vi.advanceTimersByTimeAsync(500);
    await expect(p).resolves.toBe('failed');
    expect(fig.dataset.fenceState).toBe('failed');
    expect(fig.querySelector('.wk-fence-error').textContent).toContain('could not be drawn');
    expect(fig.querySelector('pre.wk-fence-source code').textContent).toBe('A -> B');
    expect(fig.querySelector('.wk-fence-source-open, .wk-fence-drawing')).toBeNull();
  });
});
