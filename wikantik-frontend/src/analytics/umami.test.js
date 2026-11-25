import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  track, trackPageview, stripCampaignParams, isTrackedRoute, routePath, resetAnalyticsState,
  setReaderPageContext, getReaderPageContext, primaryCluster,
} from './umami';

// Runs the function form of umami.track() against a fake default payload so tests see the final payload.
function fakeUmami() {
  const sent = [];
  const fn = vi.fn((arg) => {
    sent.push(typeof arg === 'function' ? arg({ website: 'w', url: '/landing', referrer: 'r' }) : arg);
    return Promise.resolve();
  });
  window.umami = { track: fn };
  return { fn, sent };
}

describe('umami wrapper', () => {
  beforeEach(() => { resetAnalyticsState(); window.history.replaceState(null, '', '/'); });
  afterEach(() => { delete window.umami; delete window.__WIKANTIK_BASE__; });

  it('track without window.umami does not throw', () => {
    expect(() => track('toc-click', { canonical_id: 'x' })).not.toThrow();
  });

  it('trackPageview without window.umami resolves', async () => {
    await expect(trackPageview()).resolves.toBeUndefined();
  });

  it('track swallows tracker errors with console.debug', () => {
    window.umami = { track: () => { throw new Error('boom'); } };
    const debug = vi.spyOn(console, 'debug').mockImplementation(() => {});
    expect(() => track('toc-click', {})).not.toThrow();
    expect(debug).toHaveBeenCalled();
    debug.mockRestore();
  });

  it('track sends name, data and the live url', () => {
    const { sent } = fakeUmami();
    window.history.replaceState(null, '', '/wiki/A?x=1');
    track('code-copy', { canonical_id: 'c1', lang: 'java' });
    expect(sent[0]).toMatchObject({ name: 'code-copy', data: { canonical_id: 'c1', lang: 'java' }, url: '/wiki/A?x=1' });
  });

  it('pageview carries the current url and the previous url as referrer after the first', async () => {
    const { sent } = fakeUmami();
    window.history.replaceState(null, '', '/wiki/A?utm_source=x');
    await trackPageview();
    window.history.replaceState(null, '', '/wiki/B');
    await trackPageview();
    expect(sent[0]).toMatchObject({ url: '/wiki/A?utm_source=x', referrer: 'r' });
    expect(sent[1]).toMatchObject({ url: '/wiki/B', referrer: '/wiki/A?utm_source=x' });
  });

  it('stripCampaignParams removes tracking params and keeps others, hash and history state', () => {
    window.history.replaceState({ usr: 1 }, '', '/wiki/A?utm_source=x&gclid=y&keep=1&msclkid=a&fbclid=b&li_fat_id=c&ttclid=d&twclid=e&utm_medium=m#h');
    stripCampaignParams();
    expect(window.location.search).toBe('?keep=1');
    expect(window.location.hash).toBe('#h');
    expect(window.history.state).toEqual({ usr: 1 });
  });

  it('stripCampaignParams leaves a clean URL alone', () => {
    window.history.replaceState(null, '', '/wiki/A?keep=1');
    const spy = vi.spyOn(window.history, 'replaceState');
    stripCampaignParams();
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  it('isTrackedRoute mirrors the server reader-route list', () => {
    ['/', '/index.html', '/wiki/A', '/wiki/A/B', '/search', '/page-graph'].forEach((p) => expect(isTrackedRoute(p)).toBe(true));
    ['/admin', '/admin/insights', '/edit/A', '/diff/A', '/preferences', '/work', '/work/p1', '/login',
      '/reset-password', '/change-password', '/me/mentions', '/wiki', '/searching'].forEach((p) => expect(isTrackedRoute(p)).toBe(false));
  });

  it('isTrackedRoute accounts for the router basename', () => {
    window.__WIKANTIK_BASE__ = '/ctx';
    expect(isTrackedRoute('/ctx/wiki/A')).toBe(true);
    expect(isTrackedRoute('/ctx')).toBe(true);
    expect(isTrackedRoute('/ctx/edit/A')).toBe(false);
    expect(isTrackedRoute('/wiki/A')).toBe(true);
    expect(routePath('/ctx/search')).toBe('/search');
  });

  it('page context is keyed by path and takes the first cluster of a list', () => {
    setReaderPageContext('/wiki/A', 'id-1', ['c1', 'c2']);
    expect(getReaderPageContext('/wiki/A')).toMatchObject({ canonicalId: 'id-1', cluster: 'c1' });
    expect(getReaderPageContext('/wiki/B').canonicalId).toBeUndefined();
    expect(primaryCluster('solo')).toBe('solo');
    expect(primaryCluster(undefined)).toBeUndefined();
  });
});
