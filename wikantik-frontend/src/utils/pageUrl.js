// Strip a trailing `.md` so nodes whose source_page includes the extension
// still link to a view URL the server recognizes.
export function normalizePageName(name) {
  if (!name) return '';
  return name.endsWith('.md') ? name.slice(0, -3) : name;
}

// Build the view URL for a wiki page. `/wiki/` matches
// ShortViewURLConstructor's default prefix and every other frontend link.
// Encoding mirrors the backend's DefaultURLConstructor.encodeURI (spaces
// become %20, which is exactly what encodeURIComponent produces).
export function pageHref(name) {
  const clean = normalizePageName(name);
  if (!clean) return '';
  return `/wiki/${encodeURIComponent(clean)}`;
}

// Build an edit URL. Targets the SPA route `/edit/:name` declared in main.jsx.
export function pageEditHref(name) {
  const clean = normalizePageName(name);
  if (!clean) return '';
  return `/edit/${encodeURIComponent(clean)}`;
}
