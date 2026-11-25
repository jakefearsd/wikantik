import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useEffect } from 'react';
import { render, act } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { useReaderAnalytics } from './useReaderAnalytics';
import { resetAnalyticsState, setReaderPageContext } from './umami';

let nav;
function Harness() {
  useReaderAnalytics();
  const navigate = useNavigate();
  useEffect(() => { nav = navigate; }, [navigate]);
  return null;
}

function mount(path) {
  return render(<MemoryRouter initialEntries={[path]}><Harness /></MemoryRouter>);
}

function setScroll({ y = 0, inner = 1000, total = 4000 }) {
  Object.defineProperty(document.documentElement, 'scrollHeight', { configurable: true, value: total });
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: inner });
  Object.defineProperty(window, 'scrollY', { configurable: true, value: y });
}

let sent;
let visibility;
beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-11-24T00:00:00Z'));
  resetAnalyticsState();
  sent = [];
  window.umami = { track: vi.fn((arg) => { sent.push(typeof arg === 'function' ? arg({}) : arg); return Promise.resolve(); }) };
  window.history.replaceState(null, '', '/wiki/A');
  visibility = 'visible';
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility });
  setScroll({});
});
afterEach(() => { vi.useRealTimers(); delete window.umami; });

const exits = () => sent.filter((p) => p.name === 'page-exit');
const pageviews = () => sent.filter((p) => !p.name);

describe('useReaderAnalytics', () => {
  it('sends one pageview on the first tracked route, then strips campaign params only afterwards', async () => {
    window.history.replaceState(null, '', '/wiki/A?utm_source=x&gclid=y&keep=1');
    let release;
    window.umami.track = vi.fn((arg) => { sent.push(arg({})); return new Promise((r) => { release = r; }); });
    mount('/wiki/A');
    expect(window.umami.track).toHaveBeenCalledTimes(1);
    expect(sent[0].url).toBe('/wiki/A?utm_source=x&gclid=y&keep=1');
    expect(window.location.search).toContain('utm_source');
    await act(async () => { release(); await Promise.resolve(); });
    expect(window.location.search).toBe('?keep=1');
  });

  it('navigating to an untracked route sends no pageview and one page-exit for the page left', async () => {
    mount('/wiki/A');
    setReaderPageContext('/wiki/A', 'id-A', ['hub']);
    await act(async () => { nav('/edit/A'); });
    expect(pageviews()).toHaveLength(1);
    expect(exits()).toHaveLength(1);
    expect(exits()[0].data).toMatchObject({ canonical_id: 'id-A', cluster: 'hub' });
  });

  it('untracked first route sends nothing at all', async () => {
    mount('/admin');
    expect(window.umami.track).not.toHaveBeenCalled();
  });

  it('counts 10 s of visible, interacting time as active_s 10', async () => {
    mount('/wiki/A');
    await act(async () => { vi.advanceTimersByTime(5000); window.dispatchEvent(new Event('click')); vi.advanceTimersByTime(5000); nav('/search'); });
    expect(exits()[0].data.active_s).toBe(10);
  });

  it('stops counting 30 s after the last interaction (5 + 30 = 35)', async () => {
    mount('/wiki/A');
    await act(async () => { vi.advanceTimersByTime(5000); window.dispatchEvent(new Event('keydown')); vi.advanceTimersByTime(55000); nav('/search'); });
    expect(exits()[0].data.active_s).toBe(35);
  });

  it('does not count hidden time', async () => {
    mount('/wiki/A');
    await act(async () => {
      vi.advanceTimersByTime(4000);
      visibility = 'hidden'; document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(exits()).toHaveLength(1);
    expect(exits()[0].data.active_s).toBe(4);
  });

  it('records the maximum scroll depth, capped at 100', async () => {
    mount('/wiki/A');
    await act(async () => {
      setScroll({ y: 1500 }); window.dispatchEvent(new Event('scroll'));
      setScroll({ y: 200 }); window.dispatchEvent(new Event('scroll'));
      nav('/search');
    });
    expect(exits()[0].data.max_scroll_pct).toBe(63);
    sent.length = 0;
    setScroll({ y: 9000 });
    await act(async () => { nav('/wiki/B'); });
    await act(async () => { window.dispatchEvent(new Event('scroll')); nav('/search'); });
    expect(exits().at(-1).data.max_scroll_pct).toBe(100);
  });

  it('sends page-exit once even when visibilitychange and pagehide both fire', async () => {
    const { unmount } = mount('/wiki/A');
    await act(async () => {
      visibility = 'hidden'; document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event('pagehide'));
    });
    unmount();
    expect(exits()).toHaveLength(1);
  });

  it('stamps the exit with the page being left, even after the real URL has already changed', async () => {
    window.history.replaceState(null, '', '/wiki/A?x=1');
    mount('/wiki/A');
    await act(async () => {
      window.history.pushState(null, '', '/edit/A');
      nav('/edit/A');
    });
    expect(exits()).toHaveLength(1);
    expect(exits()[0].url).toBe('/wiki/A?x=1');
  });

  it('does not sample scroll depth at a route-change exit (the DOM is already the next page)', async () => {
    mount('/wiki/A');
    await act(async () => {
      setScroll({ y: 500 }); window.dispatchEvent(new Event('scroll'));
      setScroll({ y: 9000 }); // next page's layout, no scroll event for this view
      nav('/search');
    });
    expect(exits()[0].data.max_scroll_pct).toBe(38);
  });

  it('does take a final scroll sample on a pagehide exit', async () => {
    mount('/wiki/A');
    await act(async () => {
      setScroll({ y: 9000 });
      window.dispatchEvent(new Event('pagehide'));
    });
    expect(exits()[0].data.max_scroll_pct).toBe(100);
  });
});
