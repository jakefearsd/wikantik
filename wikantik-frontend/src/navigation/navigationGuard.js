// Decides whether a document click is an in-app navigation the unsaved-changes guard should hold.
// Returns the router path to navigate to (basename stripped), or null to let the browser handle it.
export function interceptableHref(e, loc = window.location, base = window.__WIKANTIK_BASE__ || '') {
  if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return null;
  const anchor = e.target?.closest?.('a[href]');
  if (!anchor) return null;
  const target = anchor.getAttribute('target');
  if ((target && target !== '_self') || anchor.hasAttribute('download')) return null;
  const url = new URL(anchor.getAttribute('href'), loc.href);
  if (url.origin !== loc.origin) return null;
  if (url.pathname === loc.pathname && url.search === loc.search) return null; // same page (incl. #hash jumps)
  let path = url.pathname;
  if (base && base !== '/' && path.startsWith(base)) path = path.slice(base.length) || '/';
  return path + url.search + url.hash;
}

// The client-side routes declared in main.jsx. Anything else on this origin (attachments, /sparql, /export/*,
// static .html pages) is served by the server and must be loaded as a full page, not handed to the router.
const SPA_EXACT = new Set(['/', '/wiki', '/search', '/page-graph', '/knowledge-graph', '/preferences',
  '/reset-password', '/login', '/change-password', '/admin']);
const SPA_PREFIXES = ['/wiki/', '/edit/', '/diff/', '/me/', '/admin/'];

/** True when the router serves `path` (a basename-stripped path, optionally with ?query / #hash). */
export function isSpaPath(path) {
  const pathname = path.split(/[?#]/, 1)[0];
  return SPA_EXACT.has(pathname) || SPA_PREFIXES.some((p) => pathname.startsWith(p));
}

// Set immediately before a confirmed full-page load (location.assign) so the editor's own beforeunload
// handler does not raise a second, native "Leave site?" prompt. The flag self-clears after a short window
// because a load that never unloads the page (e.g. an attachment download) must not disarm the editor.
let leaving = false;
let leavingTimer = null;
export function markLeaving() {
  leaving = true;
  clearTimeout(leavingTimer);
  leavingTimer = setTimeout(() => { leaving = false; }, 2000);
}
export function isLeaving() { return leaving; }
export function resetLeaving() { leaving = false; clearTimeout(leavingTimer); }
