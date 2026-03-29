# Remove JSP UI and /app/ Prefix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the JSP UI, delete ~228 dead code files, and serve the React SPA from `/` instead of `/app/`, making `/wiki/Main` the default entry point.

**Architecture:** Move the React bundle from `/app/` to WAR root, rewrite `SpaRoutingFilter` to handle `/wiki/*` and other SPA routes, add server-side redirects for `/` and `/wiki/`, delete all JSP files/tags/UI classes/forms, and clean up web.xml. Each task is independently deployable.

**Tech Stack:** Java 21, React 18, React Router v6, Vite, Tomcat 11.

**Spec:** `docs/superpowers/specs/2026-03-29-remove-jsp-and-app-prefix-design.md`

---

## File Structure

| File | Change |
|------|--------|
| `wikantik-war/pom.xml` | MODIFY — change frontend targetPath from `app` to root |
| `wikantik-frontend/src/main.jsx` | MODIFY — change BrowserRouter basename from `"/app"` to `"/"` |
| `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java` | MODIFY — rewrite to handle `/wiki/*`, `/edit/*`, `/diff/*`, `/search`, `/preferences`, `/reset-password` + redirects for `/` and `/wiki/` |
| `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java` | MODIFY — update tests for new paths |
| `wikantik-war/src/main/webapp/WEB-INF/web.xml` | MODIFY — remove JSP filters/servlets, update SpaRoutingFilter mappings |
| `wikantik-war/src/main/webapp/*.jsp` | DELETE — all 25 root-level JSP files |
| `wikantik-war/src/main/webapp/templates/` | DELETE — entire directory (43 JSP template files + assets) |
| `wikantik-war/src/main/webapp/scripts/mootools.js` | DELETE |
| `wikantik-main/src/main/java/com/wikantik/tags/` | DELETE — all 69 tag classes |
| `wikantik-main/src/main/java/com/wikantik/ui/` | DELETE — 30 classes (keep WikiServletFilter, WikiRequestWrapper, SitemapServlet) |
| `wikantik-main/src/main/java/com/wikantik/forms/` | DELETE — all 10 form classes |
| `wikantik-main/src/main/java/com/wikantik/WikiServlet.java` | DELETE |
| `wikantik-main/src/test/java/com/wikantik/tags/` | DELETE — any tag tests |
| `wikantik-frontend/src/components/PageView.jsx` | MODIFY — remove `/app/` references from link interceptor |
| `wikantik-frontend/vite.config.js` | MODIFY — update base path if configured |

---

### Task 1: Update SpaRoutingFilter and web.xml

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

The filter needs to:
1. Redirect `/` and `/wiki/` to `/wiki/Main`
2. Forward SPA routes (`/wiki/*`, `/edit/*`, `/diff/*`, `/search`, `/preferences`, `/reset-password`) to `/index.html`
3. Let static assets (JS, CSS, images) pass through
4. Let servlet paths (`/api/*`, `/admin/*`, `/mcp`, `/metrics`, `/attach/*`) pass through (they won't match the filter's URL patterns anyway)

- [ ] **Step 1: Update SpaRoutingFilterTest**

Rewrite tests for the new routing:
```java
// Redirects
testRootRedirectsToWikiMain()          // GET / → 302 /wiki/Main
testWikiSlashRedirectsToWikiMain()     // GET /wiki/ → 302 /wiki/Main

// SPA routes forwarded to index.html
testWikiPageForwardsToIndex()          // GET /wiki/SomePage → forward /index.html
testEditPageForwardsToIndex()          // GET /edit/SomePage → forward /index.html
testDiffPageForwardsToIndex()          // GET /diff/SomePage → forward /index.html
testSearchForwardsToIndex()            // GET /search?q=test → forward /index.html
testPreferencesForwardsToIndex()       // GET /preferences → forward /index.html
testResetPasswordForwardsToIndex()     // GET /reset-password → forward /index.html
testAdminForwardsToIndex()             // GET /admin/security → forward /index.html

// Static assets pass through
testStaticAssetsPassThrough()          // GET /assets/index-ABC.js → chain.doFilter
testFaviconPassesThrough()             // GET /favicon.ico → chain.doFilter
```

- [ ] **Step 2: Rewrite SpaRoutingFilter**

```java
public class SpaRoutingFilter implements Filter {

    /** SPA route prefixes — requests matching these are forwarded to index.html. */
    private static final String[] SPA_PREFIXES = {
        "/wiki/", "/edit/", "/diff/", "/admin/"
    };

    /** Exact SPA paths — forwarded to index.html. */
    private static final String[] SPA_EXACT = {
        "/search", "/preferences", "/reset-password"
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                          FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest req = (HttpServletRequest) request;
        final HttpServletResponse resp = (HttpServletResponse) response;
        final String path = req.getRequestURI();

        // Redirect / and /wiki/ to /wiki/Main
        if ("/".equals(path) || "/wiki/".equals(path) || "/wiki".equals(path)) {
            resp.sendRedirect("/wiki/Main");
            return;
        }

        // Let static assets through (JS, CSS, images, fonts, favicon)
        if (path.contains(".") && !path.endsWith(".html")) {
            chain.doFilter(request, response);
            return;
        }

        // Check if this is a SPA route
        for (String prefix : SPA_PREFIXES) {
            if (path.startsWith(prefix)) {
                req.getRequestDispatcher("/index.html").forward(request, response);
                return;
            }
        }
        for (String exact : SPA_EXACT) {
            if (path.equals(exact) || path.startsWith(exact + "?")) {
                req.getRequestDispatcher("/index.html").forward(request, response);
                return;
            }
        }

        // Everything else passes through (servlets, API, etc.)
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 3: Update web.xml — SpaRoutingFilter mapping**

Change from:
```xml
<filter-mapping>
    <filter-name>SpaRoutingFilter</filter-name>
    <url-pattern>/app/*</url-pattern>
</filter-mapping>
```

To:
```xml
<filter-mapping>
    <filter-name>SpaRoutingFilter</filter-name>
    <url-pattern>/wiki/*</url-pattern>
    <url-pattern>/edit/*</url-pattern>
    <url-pattern>/diff/*</url-pattern>
    <url-pattern>/search</url-pattern>
    <url-pattern>/preferences</url-pattern>
    <url-pattern>/reset-password</url-pattern>
    <url-pattern>/</url-pattern>
</filter-mapping>
```

Note: `/admin/*` SPA routing is handled by the filter catching requests that don't match admin servlet mappings. The `AdminAuthFilter` and admin servlets have more specific URL patterns, so they take precedence.

- [ ] **Step 4: Run tests**

```bash
mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterTest
```

- [ ] **Step 5: Commit**

```bash
git commit -m "Rewrite SpaRoutingFilter for root-level SPA serving without /app/ prefix"
```

---

### Task 2: Move React Bundle to WAR Root

**Files:**
- Modify: `wikantik-war/pom.xml`
- Modify: `wikantik-frontend/src/main.jsx`
- Modify: `wikantik-frontend/vite.config.js` (if base path is configured)

- [ ] **Step 1: Change WAR packaging target path**

In `wikantik-war/pom.xml`, change the frontend resource target:
```xml
<!-- Before -->
<targetPath>app</targetPath>

<!-- After — remove targetPath to package at WAR root -->
```

Remove the `<targetPath>app</targetPath>` line entirely (or set to empty). The frontend dist files (index.html, assets/) will be at the WAR root instead of under `/app/`.

- [ ] **Step 2: Change React Router basename**

In `wikantik-frontend/src/main.jsx`:
```jsx
// Before
<BrowserRouter basename="/app">

// After
<BrowserRouter basename="/">
```

- [ ] **Step 3: Check vite.config.js base**

If vite.config.js has a `base: '/app/'` setting, change it to `base: '/'`. If no base is set (default is `/`), no change needed.

- [ ] **Step 4: Update PageView.jsx link interceptor**

Remove the `/app/` prefix handling from the click interceptor — it's no longer needed:

```javascript
// The interceptor should handle /wiki/, /edit/, /diff/, /search paths
// Remove the /app/ prefix branch entirely
if (href.startsWith('/wiki/') || href.startsWith('/edit/') ||
    href.startsWith('/diff/') || href.startsWith('/search')) {
  // These are internal SPA routes
  internalPath = href;
} else {
  return; // external link
}
```

- [ ] **Step 5: Build and verify**

```bash
cd wikantik-frontend && npm run build
mvn clean install -Dmaven.test.skip -T 1C
```

Verify that `wikantik-war/target/Wikantik.war` contains `index.html` at root (not under `app/`).

- [ ] **Step 6: Commit**

```bash
git commit -m "Move React SPA from /app/ to WAR root, change basename to /"
```

---

### Task 3: Remove JSP Files and Templates

**Files:**
- DELETE: all `wikantik-war/src/main/webapp/*.jsp` (25 files)
- DELETE: `wikantik-war/src/main/webapp/templates/` (entire directory)
- DELETE: `wikantik-war/src/main/webapp/scripts/mootools.js`
- DELETE: `wikantik-war/src/main/webapp/admin/` (JSP admin pages, if exists)

- [ ] **Step 1: Delete JSP files**

```bash
rm wikantik-war/src/main/webapp/*.jsp
rm -rf wikantik-war/src/main/webapp/templates/
rm -rf wikantik-war/src/main/webapp/scripts/
rm -rf wikantik-war/src/main/webapp/admin/
```

Keep: `wikantik-war/src/main/webapp/error/Forbidden.html` (referenced by web.xml error-page)
Keep: `wikantik-war/src/main/webapp/WEB-INF/` (web.xml, etc.)
Keep: `wikantik-war/src/main/webapp/favicons/` (if exists)
Keep: `wikantik-war/src/main/webapp/images/` (if referenced)

- [ ] **Step 2: Verify build**

```bash
mvn clean install -Dmaven.test.skip -T 1C
```

- [ ] **Step 3: Commit**

```bash
git commit -m "Delete JSP pages, templates, and legacy JavaScript (68 files)"
```

---

### Task 4: Remove JSP Tag Classes

**Files:**
- DELETE: all files in `wikantik-main/src/main/java/com/wikantik/tags/` (69 files)
- DELETE: any tag tests in `wikantik-main/src/test/java/com/wikantik/tags/`
- MODIFY: `wikantik-main/src/main/java/com/wikantik/WikiServlet.java` — DELETE this file

- [ ] **Step 1: Delete tag classes and WikiServlet**

```bash
rm -rf wikantik-main/src/main/java/com/wikantik/tags/
rm -rf wikantik-main/src/test/java/com/wikantik/tags/
rm wikantik-main/src/main/java/com/wikantik/WikiServlet.java
```

- [ ] **Step 2: Fix compilation — remove imports referencing deleted classes**

Check for any remaining imports of `com.wikantik.tags.*` or `WikiServlet` in other classes. Fix any compilation errors.

```bash
grep -rn "import com.wikantik.tags\|import com.wikantik.WikiServlet" wikantik-main/src/main/java/ | grep -v "^Binary"
```

- [ ] **Step 3: Build and test**

```bash
mvn clean test -pl wikantik-main -T 1C
```

- [ ] **Step 4: Commit**

```bash
git commit -m "Delete JSP tag classes and WikiServlet (70 files)"
```

---

### Task 5: Remove JSP UI Classes and Forms

**Files:**
- DELETE: ~30 classes from `wikantik-main/src/main/java/com/wikantik/ui/` (keep WikiServletFilter, WikiRequestWrapper, SitemapServlet)
- DELETE: all files in `wikantik-main/src/main/java/com/wikantik/forms/` (10 files)
- DELETE: any related tests

- [ ] **Step 1: Delete form classes**

```bash
rm -rf wikantik-main/src/main/java/com/wikantik/forms/
rm -rf wikantik-main/src/test/java/com/wikantik/forms/
```

- [ ] **Step 2: Delete UI classes (except keepers)**

Delete all UI classes EXCEPT WikiServletFilter.java, WikiRequestWrapper.java, SitemapServlet.java:

```bash
cd wikantik-main/src/main/java/com/wikantik/ui/
# Keep these 3:
#   WikiServletFilter.java
#   WikiRequestWrapper.java
#   SitemapServlet.java
# Delete everything else
find . -name "*.java" \
  ! -name "WikiServletFilter.java" \
  ! -name "WikiRequestWrapper.java" \
  ! -name "SitemapServlet.java" \
  -delete
```

Also delete related test files:
```bash
find wikantik-main/src/test/java/com/wikantik/ui/ -name "*.java" \
  ! -name "*WikiServletFilter*" \
  -delete 2>/dev/null
```

- [ ] **Step 3: Delete WikiAjaxDispatcherServlet** (if it exists and is only used by JSP)

```bash
rm -rf wikantik-main/src/main/java/com/wikantik/ajax/
```

- [ ] **Step 4: Fix compilation errors**

```bash
grep -rn "import com.wikantik.forms\|import com.wikantik.ui\." wikantik-main/src/main/java/ | grep -v "WikiServletFilter\|WikiRequestWrapper\|SitemapServlet" | grep -v "^Binary"
```

Fix any broken imports. Some classes may reference deleted UI/form classes — update or remove those references.

- [ ] **Step 5: Build and test**

```bash
mvn clean test -T 1C -DskipITs
```

Fix any test failures caused by deleted classes.

- [ ] **Step 6: Commit**

```bash
git commit -m "Delete JSP UI classes and form classes (~40 files, keep WikiServletFilter/SitemapServlet)"
```

---

### Task 6: Clean Up web.xml

**Files:**
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Remove JSP-related config**

Remove these elements:
- `WikiJSPFilter` filter definition and filter-mapping
- `WikiServlet` servlet definition and servlet-mapping
- `WikiAjaxDispatcherServlet` servlet definition and servlet-mapping
- `<jsp-config>` section
- `<welcome-file-list>` with `Wiki.jsp`
- Container-managed auth block (the entire `<!-- REMOVE ME TO ENABLE CONTAINER-MANAGED AUTH -->` commented section)
- Container-managed JDBC and JavaMail `<resource-ref>` commented sections

Remove the `WikiServletFilter` mapping for `/wiki/*` (the SpaRoutingFilter now handles `/wiki/*`). Keep the WikiServletFilter mapping for `/attach/*`.

Update `WikiServletFilter` comment block to remove JSP references.

- [ ] **Step 2: Verify the error-page for 403 still works**

Keep:
```xml
<error-page>
    <error-code>403</error-code>
    <location>/error/Forbidden.html</location>
</error-page>
```

- [ ] **Step 3: Build and verify**

```bash
mvn clean install -Dmaven.test.skip -T 1C
```

- [ ] **Step 4: Commit**

```bash
git commit -m "Clean up web.xml: remove JSP filters, servlets, and legacy config"
```

---

### Task 7: Full Verification

- [ ] **Step 1: Run full test suite**

```bash
mvn clean test -T 1C -DskipITs
```

All tests must pass. Fix any failures.

- [ ] **Step 2: Deploy locally and test**

```bash
tomcat/tomcat-11/bin/shutdown.sh
rm -rf tomcat/tomcat-11/webapps/ROOT tomcat/tomcat-11/work/Catalina
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

Verify:
- `http://localhost:8080/` → redirects to `/wiki/Main`
- `http://localhost:8080/wiki/` → redirects to `/wiki/Main`
- `http://localhost:8080/wiki/Main` → React SPA renders the page
- `http://localhost:8080/edit/Main` → React page editor
- `http://localhost:8080/search?q=test` → React search page
- `http://localhost:8080/admin/security` → React admin panel (after login)
- `http://localhost:8080/api/pages/Main` → JSON response (REST API)
- `http://localhost:8080/app/wiki/Main` → 404 (old path broken)
- No `*.jsp` pages accessible
- Clicking wiki links within rendered content stays in the SPA
- Login/logout works
- Delete, rename, diff, comments all work

- [ ] **Step 3: Commit any fixes**

```bash
git commit -m "Fix issues found during JSP removal verification"
```

- [ ] **Step 4: Tag as v1.2.0-rc1**

```bash
git tag -a v1.2.0-rc1 -m "Release candidate: JSP removed, SPA at root"
git push origin v1.2.0-rc1
```
