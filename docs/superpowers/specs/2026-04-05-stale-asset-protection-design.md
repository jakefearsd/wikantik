# Stale Asset Protection for React SPA Deployments

## Problem

When the Wikantik WAR is redeployed, Vite-built asset filenames change (content hashing).
Browsers holding a cached `index.html` request old asset URLs that no longer exist, producing
a blank page that requires a hard reload. This affects both local development and production
users.

### Root Cause

The `SpaRoutingFilter` correctly sets `Cache-Control: no-cache` on `index.html` when
forwarding SPA routes, and sets `immutable` on hashed assets — but the filter is only
mapped to SPA URL patterns (`/wiki/*`, `/edit/*`, etc.), **not** `/assets/*` or
`/index.html`. Requests to those paths bypass the filter entirely and are served by
Tomcat's DefaultServlet with default caching behavior.

## Design

Three defensive layers, each independent, providing defense in depth.

---

### Layer 1 — Auto-Recovery on Asset Load Failure

**File:** `wikantik-frontend/index.html`

Add an inline `<script>` (before the Vite module script) that catches chunk-load errors
and performs a single automatic reload.

**Behavior:**

1. Register a capture-phase `error` listener on `window` that fires for `<script>` and
   `<link>` element load failures.
2. On error, check `sessionStorage` for key `__wikantik_reload`.
   - If absent: set the key to `"1"`, call `window.location.reload()`.
   - If present: do nothing (prevents infinite reload loop).
3. The React app clears `__wikantik_reload` from `sessionStorage` on successful mount
   (in `main.jsx`, after `createRoot().render()`).

**Why inline:** The recovery script must load even when the JS bundle itself 404s.
An inline script has no external dependency.

---

### Layer 2 — Build Version Checking

Detects version drift in long-lived tabs and shows a non-blocking reload prompt.

#### 2a. Build-Time Version Stamp

**File:** `wikantik-frontend/vite.config.js`

Add an inline Vite plugin (`buildVersionPlugin`):

```js
function buildVersionPlugin() {
  const version = Date.now().toString();
  return {
    name: 'build-version',
    config() {
      return { define: { __BUILD_VERSION__: JSON.stringify(version) } };
    },
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'build-version.txt',
        source: version,
      });
    },
  };
}
```

- Generates a build ID (epoch millis) once per Vite invocation.
- Injects `__BUILD_VERSION__` as a compile-time constant in the JS bundle.
- Emits `build-version.txt` to `dist/`, which the Maven WAR plugin copies to the WAR root.

Both the SPA and the Java server read the same value from their respective sides.

#### 2b. Server-Side Header

**New file:** `wikantik-rest/src/main/java/com/wikantik/rest/BuildVersionFilter.java`

A servlet filter that:

1. At `init()`, reads `/build-version.txt` from the `ServletContext` (i.e., the WAR root).
   Stores the version string in an instance field.
2. On every request, sets `X-Build-Version: <version>` on the response, then calls
   `chain.doFilter()`.

**web.xml registration:** Mapped to `/api/*` and `/admin/*`, ordered before the REST
servlets. Lightweight — one `setHeader()` call per request.

#### 2c. SPA-Side Detection

**File:** `wikantik-frontend/src/api/client.js`

In the central `request()` function, after `fetch()` resolves:

1. Read `resp.headers.get('X-Build-Version')`.
2. Compare to the compiled-in `__BUILD_VERSION__`.
3. If they differ (and we haven't already signaled), dispatch
   `window.dispatchEvent(new CustomEvent('wikantik:version-mismatch'))`.
4. Use a module-level boolean flag (`versionMismatchSignaled`) to dispatch only once
   per page session.

**File:** `wikantik-frontend/src/App.jsx`

1. Add state: `const [updateAvailable, setUpdateAvailable] = useState(false)`.
2. `useEffect` listens for `'wikantik:version-mismatch'` → `setUpdateAvailable(true)`.
3. When `updateAvailable` is true, render a top-of-page toast banner:
   - Text: "A new version is available."
   - **Reload** button → `window.location.reload()`
   - **X** dismiss button → sets `sessionStorage.__wikantik_dismissed_version` to the
     server version, hides the toast for this session.
4. On mount, check `sessionStorage` — if already dismissed for this version, don't show.

**File:** `wikantik-frontend/src/styles/globals.css`

Toast banner styles: fixed to top of viewport, subtle background, z-index above sidebar,
auto-width, centered text with two action buttons.

---

### Layer 3 — Cache Header Coverage

**New file:** `wikantik-http/src/main/java/com/wikantik/http/filter/CacheHeaderFilter.java`

A filter mapped to `/*` that ensures correct caching headers regardless of how assets
are requested:

| Path pattern | Cache-Control |
|---|---|
| `/assets/*` with Vite hash (regex `-[A-Za-z0-9]{6,}\.`) | `public, max-age=31536000, immutable` |
| `/index.html` exactly | `no-cache` |
| Everything else | No header set (pass through) |

**web.xml registration:** Mapped to `/*`, ordered **before** `SpaRoutingFilter` so cache
headers are always set before any forwarding.

**Cleanup in SpaRoutingFilter:** Remove the dead asset-caching code block (lines 76–83)
that checks for `/assets/` and sets `immutable` — this logic moves to `CacheHeaderFilter`
where it is actually reachable. The `no-cache` header before SPA forwards (lines 96, 103)
stays, as it is part of the routing concern (the forwarded `index.html` should never be
cached under its SPA URL).

---

## Files Changed

| File | Change |
|------|--------|
| `wikantik-frontend/index.html` | Add inline recovery script (~12 lines) |
| `wikantik-frontend/vite.config.js` | Add `buildVersionPlugin` (~15 lines) |
| `wikantik-frontend/src/main.jsx` | Clear `__wikantik_reload` from sessionStorage after render |
| `wikantik-frontend/src/api/client.js` | Check `X-Build-Version` header, dispatch event on mismatch |
| `wikantik-frontend/src/App.jsx` | Listen for version-mismatch event, render toast banner |
| `wikantik-frontend/src/styles/globals.css` | Toast banner styles |
| `wikantik-rest/.../BuildVersionFilter.java` | **New** — reads version file, adds response header |
| `wikantik-http/.../CacheHeaderFilter.java` | **New** — sets cache headers for `/assets/*` and `/index.html` |
| `wikantik-rest/.../SpaRoutingFilter.java` | Remove dead `/assets/` caching code block |
| `wikantik-war/.../web.xml` | Register `BuildVersionFilter` and `CacheHeaderFilter` |

## Testing

- **Layer 1:** Build with one version, load the page, rebuild with a new version (new
  asset hashes), delete the old WAR and deploy the new one. Observe automatic reload
  instead of blank page.
- **Layer 2:** Open the wiki, redeploy, trigger any API call (navigate to a page). Observe
  the toast banner. Click Reload — page loads the new version. Dismiss — toast does not
  reappear.
- **Layer 3:** `curl -I http://localhost:8080/index.html` → `Cache-Control: no-cache`.
  `curl -I http://localhost:8080/assets/index-<hash>.js` → `Cache-Control: public, max-age=31536000, immutable`.
- **Unit tests:** `CacheHeaderFilter` and `BuildVersionFilter` tested with mock
  `HttpServletRequest`/`HttpServletResponse`, verifying correct headers for each path
  pattern.
