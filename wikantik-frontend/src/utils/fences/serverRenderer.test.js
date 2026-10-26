import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../../api/client', () => ({ api: { renderFence: vi.fn() } }));

import { api } from '../../api/client';
import { draw, DEBOUNCE_MS } from './serverRenderer';
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

describe('serverRenderer.draw: options, payloads and ordering', () => {
  const settle = (ms = DEBOUNCE_MS) => vi.advanceTimersByTimeAsync(ms);

  it('exportsTheDebounceInterval', () => {
    expect(DEBOUNCE_MS).toBe(500);
  });

  it('defaultsToPlantumlAndNeverStale', async () => {
    api.renderFence.mockResolvedValue(OK);
    const host = document.createElement('div');
    const p = draw(host, 'A -> B');
    await settle();
    await p;
    expect(api.renderFence).toHaveBeenCalledWith('plantuml', 'A -> B');
    expect(host.getAttribute('aria-label')).toBe('PlantUML diagram');
  });

  it('anUnregisteredLanguageIsLabelledWithItsOwnName', async () => {
    api.renderFence.mockResolvedValue(OK);
    const host = document.createElement('div');
    const p = draw(host, 'x', { language: 'ditaa' });
    await settle();
    await p;
    expect(api.renderFence).toHaveBeenCalledWith('ditaa', 'x');
    expect(host.getAttribute('aria-label')).toBe('ditaa diagram');
  });

  it('aRefusalWithAMessageButNoLineOmitsTheLine', async () => {
    api.renderFence.mockResolvedValue({ error: 'include', message: 'Includes are not allowed' });
    const p = draw(document.createElement('div'), 'x');
    const assertion = expect(p).rejects.toMatchObject({
      message: 'PlantUML: Includes are not allowed', fenceReason: 'include', fenceMessage: 'Includes are not allowed',
    });
    await settle();
    await assertion;
  });

  it('aRefusalWithALineButNoMessageNamesTheReasonAtThatLine', async () => {
    api.renderFence.mockResolvedValue({ error: 'syntax-error', line: 9 });
    const p = draw(document.createElement('div'), 'x');
    const assertion = expect(p).rejects.toMatchObject({ message: 'PlantUML line 9: syntax-error', fenceLine: 9 });
    await settle();
    await assertion;
  });

  it.each([
    ['null', null],
    ['undefined', undefined],
    ['an empty object', {}],
    ['one variant only', { lightSvg: '<svg/>' }],
    ['non-string variants', { lightSvg: 1, darkSvg: 2 }],
    ['a null variant', { lightSvg: '<svg/>', darkSvg: null }],
  ])('treatsAnUnusablePayload(%s)AsNoDrawing', async (_name, payload) => {
    api.renderFence.mockResolvedValue(payload);
    const host = document.createElement('div');
    host.innerHTML = '<p>previous</p>';
    const p = draw(host, 'x');
    const assertion = expect(p).rejects.toThrow('PlantUML: no drawing returned');
    await settle();
    await assertion;
    expect(host.innerHTML).toBe('<p>previous</p>');
  });

  it('anErrorFieldWinsOverUsableSvg', async () => {
    api.renderFence.mockResolvedValue({ ...OK, error: 'busy' });
    const host = document.createElement('div');
    const p = draw(host, 'x');
    const assertion = expect(p).rejects.toMatchObject({ fenceReason: 'busy' });
    await settle();
    await assertion;
    expect(host.children).toHaveLength(0);
  });

  it('networkAndHttpFailuresPropagateAndLeaveTheHostAlone', async () => {
    for (const failure of [
      new TypeError('Failed to fetch'),
      Object.assign(new Error('Server Error'), { status: 500 }),
      Object.assign(new Error('Too Many Requests'), { status: 429 }),
    ]) {
      api.renderFence.mockRejectedValueOnce(failure);
      const host = document.createElement('div');
      host.innerHTML = '<p>previous</p>';
      const p = draw(host, 'x');
      const assertion = expect(p).rejects.toBe(failure);
      await settle();
      await assertion;
      expect(host.innerHTML).toBe('<p>previous</p>');
    }
  });

  it('aStaleFailureIsStillAFailureOfItsOwnRequest', async () => {
    // A rejected request is reported even when it went stale meanwhile: there is no drawing to discard, and the caller
    // (drawFence) checks staleness itself before showing anything.
    const failure = new Error('boom');
    api.renderFence.mockRejectedValue(failure);
    let stale = false;
    const p = draw(document.createElement('div'), 'x', { isStale: () => stale });
    const assertion = expect(p).rejects.toBe(failure);
    await settle();
    stale = true;
    await assertion;
  });

  it('replacesWhateverTheHostHeldBeforeAndInsertsTheServerSvgAsMarkup', async () => {
    api.renderFence.mockResolvedValue({ lightSvg: '<svg id="l2"><g class="a"/></svg>', darkSvg: '<svg id="d2"/>' });
    const host = document.createElement('div');
    host.innerHTML = '<div class="wk-fence-variant">old</div><span>junk</span>';
    const p = draw(host, 'x');
    await settle();
    await p;
    expect(host.children).toHaveLength(2);
    expect(host.textContent).toBe('');
    expect(host.querySelector('svg#l2 g.a')).not.toBeNull();
  });

  it('oneSourceDrawnTwiceReplacesRatherThanAppends', async () => {
    api.renderFence.mockResolvedValue(OK);
    const host = document.createElement('div');
    for (let i = 0; i < 2; i += 1) {
      const p = draw(host, 'x');
      await settle();
      await p;
    }
    expect(host.querySelectorAll('.wk-fence-variant')).toHaveLength(2);
  });

  it('anOlderResponseArrivingAfterANewerOneNeverOverwritesIt', async () => {
    // Two edits in flight: the first request is slow, the second fast. Staleness is per call (the caller marks the
    // first one stale as soon as the second starts), so the late first response is dropped.
    const resolvers = [];
    api.renderFence.mockImplementation(() => new Promise((r) => { resolvers.push(r); }));
    const host = document.createElement('div');
    let firstStale = false;
    const first = draw(host, 'first', { isStale: () => firstStale });
    await settle();
    firstStale = true; // the user typed again
    const second = draw(host, 'second', { isStale: () => false });
    await settle();
    expect(api.renderFence).toHaveBeenCalledTimes(2);

    resolvers[1]({ lightSvg: '<svg id="second"/>', darkSvg: '<svg id="second-d"/>' });
    await second;
    resolvers[0]({ lightSvg: '<svg id="first"/>', darkSvg: '<svg id="first-d"/>' });
    await first;

    expect(host.querySelector('svg#second')).not.toBeNull();
    expect(host.querySelector('svg#first')).toBeNull();
  });

  it('aSourceThatWentStaleDuringTheDebounceNeverReachesTheNetworkEvenIfNewerOnesDo', async () => {
    api.renderFence.mockResolvedValue(OK);
    const host = document.createElement('div');
    let stale = false;
    const a = draw(host, 'a', { isStale: () => stale });
    await settle(200);
    stale = true;
    const b = draw(host, 'b');
    await settle(600);
    await Promise.all([a, b]);
    expect(api.renderFence).toHaveBeenCalledTimes(1);
    expect(api.renderFence).toHaveBeenCalledWith('plantuml', 'b');
  });
});
