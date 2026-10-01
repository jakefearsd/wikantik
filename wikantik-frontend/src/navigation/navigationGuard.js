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
