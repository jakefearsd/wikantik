# Remove JSP UI and /app/ Context Path Prefix

## Problem

The React SPA has full feature parity with the JSP UI (achieved in v1.1.0), but the SPA is served at `/app/` while the JSP UI remains at `/`. This creates confusion, two sets of URLs for the same content, and ~228 dead code files (~2.2 MB) that inflate the WAR, increase the attack surface, and complicate testing.

## Goal

Make the React SPA the sole UI, served directly from `/` (no `/app/` prefix). Delete all JSP dead code. The `/wiki/PageName` URL structure stays — it's clean, unambiguous, and avoids routing conflicts with servlets.

## Scope

**In scope:**
- Move React SPA from `/app/` to `/` (change basename, move static assets)
- Update `SpaRoutingFilter` to serve React routes at `/wiki/*`, `/edit/*`, `/diff/*`, `/search`, `/preferences`, `/reset-password`, `/admin/*` (without `/app/` prefix)
- Redirect `/` to `/wiki/Main`
- Delete 228 JSP dead code files (JSP pages, tags, UI classes, forms, legacy JS)
- Remove JSP-related web.xml config (WikiJSPFilter, WikiServlet, jsp-config)
- Update sitemap and Atom feed URL generation
- Update all internal references (links, API client basename, tests)

**Out of scope:**
- Keeping `/app/*` paths working (clean break — they return 404)
- Changing the `/wiki/` URL structure
- Changes to the REST API paths (`/api/*`, `/admin/*` stay as-is)

## Breaking Changes

1. **`/app/*` paths stop working** — bookmarks to `/app/wiki/Main` will 404
2. **JSP pages removed** — direct access to `*.jsp` URLs will 404
3. **Legacy CSS/JS removed** — the Haddock template and mootools.js are deleted

## Architecture

### URL Routing (After Change)

| Path | Handler | Notes |
|------|---------|-------|
| `/` | Redirect → `/wiki/Main` | Welcome page |
| `/wiki/*` | React SPA (`index.html`) | Page viewing |
| `/edit/*` | React SPA (`index.html`) | Page editing |
| `/diff/*` | React SPA (`index.html`) | Version comparison |
| `/search` | React SPA (`index.html`) | Search |
| `/preferences` | React SPA (`index.html`) | User preferences |
| `/reset-password` | React SPA (`index.html`) | Password recovery |
| `/admin/*` | React SPA (`index.html`) for UI | Admin panel |
| `/api/*` | REST servlets | Unchanged |
| `/mcp` | MCP server | Unchanged |
| `/metrics` | Prometheus | Unchanged |
| `/api/health` | Health check | Unchanged |
| `/attach/*` | AttachmentServlet | Unchanged (keep until REST fully replaces) |
| `*.js, *.css, *.png, ...` | Tomcat static serving | React bundle assets |

### SpaRoutingFilter Changes

The filter currently matches `/app/*`. It needs to match multiple path patterns instead:

```
/wiki/*
/edit/*
/diff/*
/search
/preferences
/reset-password
```

For `/admin/*`: the React admin UI and the admin REST endpoints share the `/admin/` prefix. The filter must distinguish:
- Requests with `Accept: text/html` or no extension → forward to `index.html` (React UI)
- Requests with `Content-Type: application/json` → pass through to REST servlets (already handled by servlet mappings taking precedence over filters)

Actually, this is simpler than it seems: servlet mappings are more specific than filter URL patterns. The `AdminGroupResource`, `AdminPolicyResource`, `AdminUserResource`, `AdminContentResource` servlets have explicit mappings like `/admin/groups/*`. Requests matching those paths go to the servlets. The filter only catches requests that DON'T match a servlet — which are exactly the React admin page navigation requests.

### React SPA Changes

- `main.jsx`: Change `BrowserRouter basename` from `"/app"` to `"/"`
- `api/client.js`: No change needed (already uses relative paths like `/api/pages/...`)
- `PageView.jsx` link interceptor: Update to handle paths without `/app/` prefix (already done in v1.1.0 — catches `/wiki/`, `/edit/`, etc.)
- All `<Link to="...">` components: Remove any `/app/` prefixes (they shouldn't have any since basename handles it)

### React Static Asset Location

Currently: React bundle is built to `wikantik-frontend/dist/` and packaged into the WAR at `/app/`.

After: Package into WAR root. The `index.html`, `assets/` directory with JS/CSS bundles serve from `/`.

Changes needed in:
- `wikantik-war/pom.xml` or build config — change where the frontend build output is copied into the WAR
- The WAR's `index.html` must not conflict with JSP's old welcome file (which is being removed)

## Files to Delete (from dead code catalog)

### JSP Pages (68 files)
All files in `wikantik-war/src/main/webapp/*.jsp` and `wikantik-war/src/main/webapp/templates/**/*.jsp`

### Custom Tag Classes (69 files)
All files in `wikantik-main/src/main/java/com/wikantik/tags/`

### UI Classes — DELETE (30 classes)
```
wikantik-main/src/main/java/com/wikantik/ui/ — all EXCEPT:
  - WikiServletFilter.java (KEEP — REST API auth)
  - WikiRequestWrapper.java (KEEP — used by WikiServletFilter)
  - SitemapServlet.java (KEEP — sitemap.xml)
```

### Form Classes (10 files)
All files in `wikantik-main/src/main/java/com/wikantik/forms/`

### Legacy JavaScript
`wikantik-war/src/main/webapp/scripts/mootools.js`

### Template Assets
`wikantik-war/src/main/webapp/templates/` (entire directory)

### Error Pages
`wikantik-war/src/main/webapp/error/` — keep `Forbidden.html` if still referenced, delete the rest

## web.xml Changes

Remove:
- `WikiJSPFilter` filter definition and filter-mapping
- `WikiServlet` servlet definition and servlet-mapping
- `WikiAjaxDispatcherServlet` servlet definition and mapping (verify not used by React)
- `<jsp-config>` section (if present)
- Container-managed auth block (commented out but still present)
- Welcome file pointing to `Wiki.jsp`

Update:
- `SpaRoutingFilter` mapping from `/app/*` to multiple patterns: `/wiki/*`, `/edit/*`, `/diff/*`, `/search`, `/preferences`, `/reset-password`
- Add a default servlet or welcome file for `/` → redirect to `/wiki/Main`

Keep:
- `WikiServletFilter` on `/attach/*` (still needed for attachment auth)
- All REST servlet mappings (`/api/*`, `/admin/*`)
- All security header filter mappings
- `AdminAuthFilter` on `/admin/*`
- Observability filters and servlets

## Sitemap and Feed Updates

- `SitemapServlet`: Update URL generation to use `/wiki/PageName` instead of any JSP-era paths
- `AtomFeedServlet`: Same — verify feed entry links use `/wiki/` paths
- `RecentArticlesServlet`: Verify JSON response links

## Test Updates

- Remove all tag-related tests (if any exist in `wikantik-main/src/test/`)
- Remove tests for deleted UI classes
- Update any tests that reference `/app/` paths
- `SpaRoutingFilterTest`: Update to test new path patterns instead of `/app/*`
- Verify all existing REST tests still pass (they shouldn't be affected)
- Manual verification: deploy and test all React SPA routes at new paths

## Verification

1. `mvn clean test -T 1C -DskipITs` — all tests pass
2. Deploy to local Tomcat:
   - `/wiki/Main` loads the React SPA
   - `/` redirects to `/wiki/Main`
   - `/api/pages/Main` returns JSON (REST API works)
   - `/admin/users` shows React admin panel (after login)
   - `/app/wiki/Main` returns 404 (old path broken as intended)
   - No `*.jsp` pages are accessible
   - Sitemap at `/sitemap.xml` has correct `/wiki/` URLs
3. Deploy to production and verify same
