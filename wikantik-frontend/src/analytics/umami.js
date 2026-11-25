// Reader analytics: a thin, always-safe wrapper over the Umami tracker the server injects on reader pages
// (see SpaRoutingFilter). `window.umami` exists only when analytics is configured, the reader landed on a reader
// route and no blocker removed the script, so every call here must be a no-op without it.
//
// The tracker is injected with data-auto-track="false": it neither sends pageviews nor follows history changes
// (its pushState hook is never installed), so its idea of the "current url"/"referrer" is frozen at the landing
// page. Every payload below therefore overrides url (and, for pageviews after the first, referrer) explicitly
// through the function form of umami.track(), which v3 supports.

const CAMPAIGN_PARAMS = /^(utm_.*|gclid|msclkid|fbclid|li_fat_id|ttclid|twclid)$/i;

function basePath() {
  const base = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';
  return base.replace(/\/+$/, '');
}

/** Router-relative path: strips the BrowserRouter basename if present. Idempotent for already-relative paths. */
export function routePath(pathname) {
  const base = basePath();
  let p = pathname || '/';
  if (base && (p === base || p.startsWith(`${base}/`))) p = p.slice(base.length) || '/';
  return p;
}

/**
 * Mirrors the server's reader-route list exactly (SpaRoutingFilter.readerRoute): `/`, `/index.html`, `/wiki/*`,
 * `/search`, `/page-graph`. Everything else (admin, editor, diff, preferences, work, auth pages, mentions) is untracked.
 */
export function isTrackedRoute(pathname) {
  const p = routePath(pathname);
  return p === '/' || p === '/index.html' || p.startsWith('/wiki/') || p === '/search' || p === '/page-graph';
}

function umami() {
  const u = typeof window !== 'undefined' ? window.umami : undefined;
  return u && typeof u.track === 'function' ? u : null;
}

function currentUrl() {
  return window.location.pathname + window.location.search;
}

// url of the previous tracked pageview; the referrer of the next one. null until the first pageview is sent.
let previousUrl = null;

/** Sends a custom event. No-op without the tracker; never throws. */
export function track(name, props) {
  const u = umami();
  if (!u) return;
  try {
    const url = currentUrl();
    const result = u.track((p) => ({ ...p, url, name, data: props }));
    if (result && typeof result.catch === 'function') {
      result.catch((e) => console.debug('[analytics] event failed', name, e?.message || e));
    }
  } catch (e) {
    console.debug('[analytics] event failed', name, e?.message || e);
  }
}

/** Sends a pageview for the current URL (campaign params included, so Umami can attribute them). */
export async function trackPageview() {
  const u = umami();
  if (!u) return;
  const url = currentUrl();
  const referrer = previousUrl;
  previousUrl = url;
  try {
    await u.track((p) => ({ ...p, url, ...(referrer ? { referrer } : {}) }));
  } catch (e) {
    console.debug('[analytics] pageview failed', e?.message || e);
  }
}

/** Removes campaign/click-id params from the address bar (after the pageview carried them). */
export function stripCampaignParams() {
  try {
    const params = new URLSearchParams(window.location.search);
    const doomed = [...params.keys()].filter((k) => CAMPAIGN_PARAMS.test(k));
    if (doomed.length === 0) return;
    doomed.forEach((k) => params.delete(k));
    const qs = params.toString();
    const next = window.location.pathname + (qs ? `?${qs}` : '') + window.location.hash;
    window.history.replaceState(window.history.state, '', next);
  } catch (e) {
    console.debug('[analytics] could not strip campaign params', e?.message || e);
  }
}

// ---- page context shared between PageView (which loads the page) and the App-level hook / small widgets ----

let pageContext = { path: null, canonicalId: undefined, cluster: undefined };

/** First entry when `cluster` is a list; undefined when absent. */
export function primaryCluster(cluster) {
  const c = Array.isArray(cluster) ? cluster[0] : cluster;
  return c ? String(c) : undefined;
}

export function setReaderPageContext(path, canonicalId, cluster) {
  pageContext = { path: routePath(path), canonicalId, cluster: primaryCluster(cluster) };
}

/** Context of the page at `path` (default: the browser's current path); empty when it is not the loaded page. */
export function getReaderPageContext(path = window.location.pathname) {
  return pageContext.path === routePath(path) ? pageContext : { path: routePath(path) };
}

/** Test seam: forget module state. */
export function resetAnalyticsState() {
  previousUrl = null;
  pageContext = { path: null, canonicalId: undefined, cluster: undefined };
}
