import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { isTrackedRoute, currentPageUrl, track, trackPageview, stripCampaignParams, getReaderPageContext } from './umami';

// After this long without a click/key/scroll/pointer move the reader counts as idle: time stops accruing.
const IDLE_GRACE_MS = 30000;
const INTERACTION_EVENTS = ['scroll', 'click', 'keydown', 'mousemove', 'touchstart'];

function scrollPct() {
  const el = document.documentElement;
  const total = el.scrollHeight;
  if (!total || total <= 0) return 0;
  const seen = (window.scrollY || el.scrollTop || 0) + (window.innerHeight || el.clientHeight || 0);
  return Math.min(100, Math.max(0, Math.round((seen / total) * 100)));
}

/**
 * Sends a pageview on every tracked route entry (the tracker's own auto-tracking is off) and one `page-exit` per
 * view with active seconds and max scroll depth. Routes outside the reader list send nothing.
 *
 * The exit is sent from the route change, `visibilitychange`(hidden) and `pagehide`, whichever comes first, once.
 * The pinned tracker posts with fetch `keepalive: true`, which survives unload, so no sendBeacon fallback is needed.
 */
export function useReaderAnalytics() {
  const { pathname } = useLocation();

  useEffect(() => {
    if (!isTrackedRoute(pathname)) return undefined;

    const view = {
      path: pathname, url: currentPageUrl(), activeMs: 0, lastTs: Date.now(), lastInteraction: Date.now(), maxScroll: 0, sent: false,
      visible: document.visibilityState !== 'hidden',
    };

    const accrue = () => {
      const now = Date.now();
      if (view.visible) {
        const activeUntil = view.lastInteraction + IDLE_GRACE_MS;
        view.activeMs += Math.max(0, Math.min(now, activeUntil) - view.lastTs);
      }
      view.lastTs = now;
    };
    const onInteraction = () => {
      accrue();
      view.lastInteraction = view.lastTs;
      view.maxScroll = Math.max(view.maxScroll, scrollPct());
    };
    // `sample` is false for a route-change exit: by then the DOM is already the next page's, so the scroll
    // depth recorded by scroll events is all that is trustworthy.
    const sendExit = (sample) => {
      if (view.sent) return;
      view.sent = true;
      accrue();
      if (sample === true) view.maxScroll = Math.max(view.maxScroll, scrollPct());
      const ctx = getReaderPageContext(view.path);
      track('page-exit', {
        canonical_id: ctx.canonicalId,
        cluster: ctx.cluster,
        active_s: Math.round(view.activeMs / 1000),
        max_scroll_pct: view.maxScroll,
      }, view.url);
    };
    const onVisibility = () => {
      accrue(); // closes the stretch that was running under the previous visibility state
      view.visible = document.visibilityState !== 'hidden';
      if (!view.visible) sendExit(true);
    };

    INTERACTION_EVENTS.forEach((ev) => window.addEventListener(ev, onInteraction, { passive: true, capture: true }));
    document.addEventListener('visibilitychange', onVisibility);
    const onPageHide = () => sendExit(true);
    window.addEventListener('pagehide', onPageHide);
    view.maxScroll = scrollPct();

    // Strip campaign params only after the pageview (which carried them) has been handed to the tracker.
    trackPageview().then(stripCampaignParams);

    return () => {
      INTERACTION_EVENTS.forEach((ev) => window.removeEventListener(ev, onInteraction, { capture: true }));
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('pagehide', onPageHide);
      sendExit(false);
    };
  }, [pathname]);
}

export default useReaderAnalytics;
