# Search Indexing Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `wiki.wikantik.com` indexable: real home page at `/`, stable titles, one `<h1>`, meaningful sitemap dates, and private clusters hidden by permission.

**Architecture:** Four independent parts. Titles/headings are frontend + shell edits. The home page is served by `SpaRoutingFilter` through the existing server-render path. Content dates are a new small table fed by a `POST_SAVE_END` listener and read by the sitemap and JSON-LD. Private clusters are one gate inside `DefaultAuthorizationManager.decide`, so every surface inherits it.

**Tech Stack:** Java 25, Jakarta Servlet, JUnit 5 + Mockito, PostgreSQL via `com.wikantik.jdbc.Jdbc`, React 19 + vitest, Cargo ITs.

**Spec:** `docs/superpowers/specs/2026-11-11-search-indexing-fixes-design.md` — read it before your task.

## Global Constraints

- TDD: the failing test is written and seen to fail before the implementation.
- Work on `main`. Stage files by name (never `git add -A`). Commit messages 1–3 lines.
- Run `unset $(env | grep -o '^WIKANTIK_[A-Z_]*')` before any `mvn`. Single-module checks: `mvn test -pl <module> -Dtest=<Class>`; after a signature change run `mvn test-compile -pl <module>` too.
- Never swallow exceptions: at least `LOG.warn` with context.
- DB access only through `com.wikantik.jdbc.Jdbc`; DB tests use `@RequiresPostgres` + `PostgresTestDb`; migrations are DDL only, idempotent.
- Every new `wikantik.*` key is declared in `ini/wikantik.properties` (find it with `git ls-files '*ini/wikantik.properties'`) with description, `Type:` line and explicit default **before** code reads it; then run `bin/config-reference.sh --write` and commit the regenerated files.
- Compare clusters only with `ClusterPath` (never `startsWith`).
- Browser title format is exactly `"{title} - Wikantik"`.
- Do not hard-code `Main`; use `Engine.getFrontPage()` on the server.
- Must work under a non-root context path (ITs run under one).

## Model tiers

| Task | Model |
|---|---|
| 1 | haiku |
| 2, 4, 5, 6, 7, 8, 10, 11 | sonnet |
| 3 (starts with a root-cause investigation) | sonnet |
| 9 (permission gate) | opus |
| Final cross-cutting review | opus |

Tasks 1–2, 3–4, 5–8 and 9–10 are four independent groups; groups may run in parallel, tasks inside a group are sequential (3 and 2 both edit `SpaRoutingFilter` — run 2 before 3).

## Review Focus

1. A page whose `cluster:` is a **list** containing a private cluster second → hidden (Task 9 test).
2. A private page with `[{ALLOW view All}]` in its body → still hidden from anonymous (Task 9 test).
3. A brand-new private page requested anonymously **immediately** after its first save → 404, no index lag (Task 10 test).
4. Ten small edits, each under threshold, that together exceed it → date advances on the edit that crosses it (Task 6 test).
5. `/` under a non-root context path, and `/` when the front page does not exist → rendered home / plain shell, never a 500 (Task 3 tests).

---

### Task 1: Title format and shell headings (frontend)

**Files:**
- Modify: `wikantik-frontend/src/hooks/useDocumentTitle.js`, `wikantik-frontend/src/hooks/useDocumentTitle.test.js`
- Modify: `wikantik-frontend/src/components/PageEditor.jsx:922`, `PageView.jsx`, `SearchResultsPage.jsx:94`, `MentionsPage.jsx` (callers)
- Modify: `wikantik-frontend/index.html:179,191`

- [ ] **Step 1: Update `useDocumentTitle.test.js` first** — assertions: `useDocumentTitle('NoSQL Database Types')` sets `document.title === 'NoSQL Database Types - Wikantik'`; falsy title leaves it unchanged; unmount restores the previous title.
- [ ] **Step 2:** Run `npx vitest run src/hooks/useDocumentTitle.test.js` in `wikantik-frontend` — expect FAIL.
- [ ] **Step 3:** Change the hook to `` `${title} - Wikantik` ``; fix its doc comment.
- [ ] **Step 4: Callers.** `PageView` must pass the page's frontmatter `title` (fall back to the page name), not the slug — check what it passes today and add a test in the existing PageView test file asserting a page with `metadata.title: 'NoSQL Database Types'` and name `NoSqlDatabaseTypes` yields `document.title === 'NoSQL Database Types - Wikantik'`. `PageEditor.jsx:922` becomes `` `${isNew ? 'Create' : 'Edit'} ${name} - Wikantik` ``. Search passes `` `Search: ${q}` ``.
- [ ] **Step 5:** In `index.html` change both `<h1>Wikantik</h1>` elements to `<p class="shell-title">` keeping their inline styles/visual size (add a rule to the shell's inline `<style>` for `#loading-fallback .shell-title` matching the old h1 look). Give the `<noscript>` wrapper div `id="noscript-notice"` (Task 2 relies on this id).
- [ ] **Step 6:** `npx vitest run` and `npx eslint src` — expect PASS. `grep -rn "Wikantik: " src` must return nothing outside tests' negative assertions.
- [ ] **Step 7:** Commit `fix(frontend): keep server title format; shell titles are not headings`.

### Task 2: No "JavaScript is required" on server-rendered pages

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java` (`serveIndexHtml` / `injectSemantic`)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java`

**Interfaces:** Consumes `id="noscript-notice"` from Task 1. Produces package-private `static String stripNoscriptNotice(String html)`.

- [ ] **Step 1: Failing tests** in `SpaRoutingFilterTest`: (a) `stripNoscriptNotice` removes the whole `<noscript>…</noscript>` element containing `id="noscript-notice"` and leaves html without that marker unchanged; (b) a served `/wiki/<existing page>` response contains no `JavaScript is required` and exactly zero `<h1>Wikantik`; (c) a served `/search` (no page) response still contains `JavaScript is required`. Follow the existing test fixtures in that class for the shell html and engine stubs; make the fixture shell include the Task 1 noscript block.
- [ ] **Step 2:** Run `mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterTest` — expect FAIL.
- [ ] **Step 3:** Implement; apply it only when semantic content was actually injected (page exists and `viewAllowed`).
- [ ] **Step 4:** Re-run — PASS. Commit `fix(ssr): drop the noscript notice from server-rendered pages`.

### Task 3: Serve the home page at `/`

**Files:**
- Modify: `wikantik-rest/.../SpaRoutingFilter.java` (the `/` branch ~line 216, `injectSemantic`)
- Modify: `wikantik-main/src/main/java/com/wikantik/ui/PageSeoModel.java` (+ `SemanticHeadRenderer`, `JsonLdEmitter` if they build URLs themselves)
- Possibly modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`
- Test: `SpaRoutingFilterTest`, `PageSeoModelTest` (or the existing test class for it), new IT `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/HomePageRootIT.java`

**Interfaces — Produces:** `PageSeoModel.from(String pageName, ParsedPage parsed, String baseUrl, String appName, Date modified, List<String> hubMembers, String frontPageName)`; when `pageName.equals(frontPageName)` the `canonical()` is `baseUrl + "/"`. Existing overloads delegate with `frontPageName = null`.

- [ ] **Step 1: Root cause first (superpowers:systematic-debugging).** Known facts: the filter is mapped to `/` in `web.xml` and its code 302s `/` → `/wiki/Main`, yet prod answers `GET /` with 200 and the static `index.html`. Write `HomePageRootIT` asserting `GET {base}/` (no redirect following) returns 200 with `<link rel="canonical" href="…/"` and the front page's `<title>`; run it (`bin/run-tests.sh --module rest` or the module's documented invocation) and record what actually comes back and why (candidates: Tomcat welcome-file resolution rewriting the path to `/index.html` before the filter's `path.contains(".")` pass-through; dispatcher types). Write the cause into the commit message. Do not guess a fix before the cause is observed.
- [ ] **Step 2: Unit tests** (`SpaRoutingFilterTest`): `/` → 200, body has the front page's rendered content and canonical `{base}/`; `/` with context path `/ctx` → same with `/ctx`-aware asset paths; `/` when the front page does not exist → 200 plain shell, no exception; `/wiki` and `/wiki/` → 301 to `{ctx}/`; `/wiki/{frontPage}` → 200 with canonical `{base}/` (not `/wiki/{frontPage}`); `/index.html` behaves as `/`. `PageSeoModel` test: front page → `canonical()` is `base + "/"`, `og:url`/JSON-LD `url` follow it; other pages unchanged.
- [ ] **Step 3:** Implement. `/` calls `serveIndexHtml(req, resp, contextPath, engine.getFrontPage())` (ETag/caching identical to `/wiki/{frontPage}`); thread `frontPageName` into the SEO model. Fix whatever Step 1 found so the filter really handles `/`.
- [ ] **Step 4:** Unit tests + `HomePageRootIT` PASS. Commit `fix(seo): server-render the home page at / and make it canonical`.

### Task 4: Home links, SPA route and sitemap entry

**Files:**
- Modify: `wikantik-frontend/src/main.jsx:49-51`, `components/Sidebar.jsx:109,145`, and the other `'/wiki/Main'` navigations (`grep -rn "/wiki/Main" src`)
- Modify: `wikantik-main/src/main/java/com/wikantik/ui/SitemapServlet.java`, `JsonLdEmitter.java` (BreadcrumbList "Home" item)
- Test: frontend route test (follow an existing router test), `SitemapServletTest`, JSON-LD test

- [ ] **Step 1: Failing tests.** Frontend: rendering the app at `/` shows the Main page view **without** the location changing to `/wiki/Main`; the sidebar logo/"Main page" links have `href="/"`. Sitemap: the front page's `<loc>` is `{base}/` and `{base}/wiki/{frontPage}` is absent. JSON-LD: breadcrumb "Home" item URL is `{base}/`.
- [ ] **Step 2:** Run them — FAIL.
- [ ] **Step 3:** Implement. `/` route renders the page view for the home page (the SPA learns the front-page name the way it does today — keep `Main` only where the frontend already hard-codes it, behind one exported constant `HOME_PAGE`); `/wiki` navigates to `/`. Post-login/after-delete navigations go to `/`.
- [ ] **Step 4:** vitest + `mvn test -pl wikantik-main -Dtest=SitemapServletTest,<JsonLd test>` PASS. Commit `fix(seo): home links and sitemap point at /`.

### Task 5: The content-change rule (pure function)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/seo/ContentChangeRule.java`
- Test: `wikantik-main/src/test/java/com/wikantik/seo/ContentChangeRuleTest.java`

**Interfaces — Produces:**
```java
public record ContentChangeRule(int minPercent, int minWords) {
    /** Body normalised for comparison: frontmatter stripped, CRLF→LF, whitespace runs collapsed,
     *  a trailing line starting "**Cluster Hub:**" dropped. */
    public static String normalize(String fullPageText);
    /** True when newText is a substantial change from baselineText (both full page texts). */
    public boolean isSubstantial(String baselineText, String newText);
}
```
Algorithm (pinned): words = `normalize(..)` split on whitespace. `changed` = Σ over distinct words of `|count_new − count_old|` (bag difference). `threshold = min(ceil(oldWordCount × minPercent / 100.0), minWords)`, floor 1. Substantial iff `changed >= threshold` **or** the set of ATX heading texts (lines matching `^#{1,6}\s+(.*)$`, trimmed, outside code fences) in the new text contains one not in the old. A null/blank baseline with non-blank new text is substantial.

- [ ] **Step 1: Failing tests** with defaults `(5, 100)`: frontmatter-only change → false; CRLF vs LF of same body → false; added/removed `**Cluster Hub:** [X](X)` footer → false; one-word typo in a 1,000-word body → false; 50 changed words in a 1,000-word body → true (5%); 100 changed words in a 10,000-word body → true (100 cap); 99 in 10,000 → false; new `## Heading` with 3 words → true; renamed heading → true; heading-like line inside a ``` fence → not a heading; blank baseline → true; identical → false.
- [ ] **Step 2:** `mvn test -pl wikantik-main -Dtest=ContentChangeRuleTest` — FAIL. **Step 3:** implement. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(seo): substantial-content-change rule`.

### Task 6: Store and maintain the content-changed date

**Files:**
- Create: `bin/db/migrations/V072__page_content_dates.sql`
- Create: `wikantik-main/src/main/java/com/wikantik/seo/ContentDateRepository.java`, `ContentDateService.java`, `ContentDateEventListener.java`
- Modify: wiring — register the listener where `CitationEventListener` is registered (see `com.wikantik.citation.CitationWiringHelper` and its caller); `ini/wikantik.properties`
- Test: `ContentDateRepositoryTest` (`@RequiresPostgres`), `ContentDateServiceTest` (Mockito `PageManager`)

**Interfaces — Consumes:** `ContentChangeRule` (Task 5). **Produces:**
```sql
CREATE TABLE IF NOT EXISTS page_content_dates (
  page_name          TEXT PRIMARY KEY,
  content_changed_at TIMESTAMPTZ NOT NULL,
  baseline_version   INTEGER     NOT NULL,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW());
-- GRANT SELECT, INSERT, UPDATE, DELETE … TO :app_user
```
```java
public record ContentDate(String pageName, Instant contentChangedAt, int baselineVersion) {}
class ContentDateRepository { Optional<ContentDate> find(String page); Map<String,Instant> findAll();
    void upsert(ContentDate d); void delete(String page); void rename(String from, String to); }
public class ContentDateService {
    /** Called after a save. */                public void onPageSaved(String pageName);
    /** Walks full history oldest→newest. */   public ContentDate recompute(String pageName);
    public void onPageDeleted(String pageName); public void onPageRenamed(String from, String to);
    /** Stored date, else the page's lastModified, else null. */ public Instant contentDate(Page page);
    public Map<String,Instant> allContentDates(); }
```
Settings: `wikantik.seo.contentChange.minPercent=5`, `wikantik.seo.contentChange.minWords=100` (Type: integer).

Behaviour: `onPageSaved` — no row → `recompute`. Row exists → load baseline text `pageManager.getPureText(name, baselineVersion)`; if `rule.isSubstantial(baseline, current)` → upsert `(now-of-that-version = page.getLastModified(), currentVersion)`; else leave the row untouched (baseline stays, so small edits accumulate). Baseline text unavailable (null/blank) → treat as substantial. `recompute` — iterate `pageManager.getVersionHistory(name)` ascending: version 1 sets `(v1.lastModified, 1)`; each later version that is substantial vs the current baseline version's text moves the date/baseline to it; a page with no history uses its `lastModified` and current version. Listener handles `POST_SAVE_END`, `PAGE_DELETED`, and the rename event (mirror `StructuralIndexEventListener`); failures are logged at WARN and never fail the save.

- [ ] **Step 1: Failing tests.** Repository: upsert/find/findAll/delete/rename round-trips; upsert twice is one row. Service (mock `PageManager` with scripted versions): new page → date = v1 time; frontmatter-only v2 → unchanged; **ten small edits each under threshold that together cross it → date moves on the crossing edit, baseline moves with it**; missing baseline text → moves; `recompute` over [v1 body A, v2 frontmatter-only, v3 big change, v4 typo] → date = v3 time, baseline 3; repository throwing → `onPageSaved` logs and returns.
- [ ] **Step 2:** run → FAIL. **Step 3:** migration (verify re-apply is a no-op), implement, wire, declare properties, `bin/config-reference.sh --write`. **Step 4:** tests + `ConfigSurfaceDriftTest` + `ConfigReferenceRegressionTest` PASS.
- [ ] **Step 5:** Commit `feat(seo): record a content-changed date per page (V072)`.

### Task 7: Sitemap, news sitemap and JSON-LD read the content date

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/ui/SitemapServlet.java:237-246,~378`; `wikantik-rest/.../SpaRoutingFilter.java` (`modified = page.getLastModified()` in `injectSemantic`, ~line 525); any other caller that passes `modified` into `SemanticHeadRenderer.renderHead` / `PageSeoModel.from` (`grep -rn "renderHead(" --include=*.java`), including `preview_structured_data`.
- Test: `SitemapServletTest`, `SpaRoutingFilterTest`

**Interfaces — Consumes:** `ContentDateService.allContentDates()` / `contentDate(Page)`; obtain the service the way the servlet obtains other engine services (expose it via the engine manager lookup used for `CitationSync`).

- [ ] **Step 1: Failing tests.** Sitemap: page with file mtime 2026-11-09 and stored content date 2026-03-02 → `<lastmod>2026-03-02</lastmod>`; no stored date → mtime; tagged page with mtime today but content date 30 days ago → **no** `<news:news>`; content date today → has it, `publication_date` = content date; the servlet calls `allContentDates()` exactly once per request. SSR: JSON-LD `dateModified` equals the content date. Service absent/throwing → falls back to mtime with a WARN.
- [ ] **Step 2–4:** fail → implement → pass. Leave ETags, Atom feed, `/api/changes`, recent changes on `getLastModified()`.
- [ ] **Step 5:** Commit `fix(seo): sitemap and JSON-LD dates use the content-changed date`.

### Task 8: Backfill endpoint

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminContentDatesResource.java`
- Modify: `web.xml` (servlet + mapping `/admin/seo/content-dates/*`; covered by the existing `AdminAuthFilter` `/admin/*` mapping); CLAUDE.md `/admin/*` servlet count and `docs/developer/ProjectReference.md` list
- Test: `AdminContentDatesResourceTest`; add to the admin IT that enumerates admin endpoints if one exists

**Interfaces:** `POST /admin/seo/content-dates/backfill?force=true|false` → starts one background run (virtual thread), returns `202 {"state":"running"}`; a second POST while running returns `409`. `GET /admin/seo/content-dates/status` → `{"state":"idle|running|done|failed","total":N,"done":N,"failed":N,"startedAt":…}`. Without `force` it skips pages that already have a row (resumable); with `force` it calls `recompute` for every page. Per-page failure increments `failed`, logs WARN, continues. Use `RestServletBase.sendError`, not the response's.

- [ ] **Step 1: Failing tests:** 202 then status reaches `done` with `done == total`; rows exist for all pages; second POST during a run → 409; one page throwing → `failed == 1`, others done; `force=false` leaves an existing row untouched.
- [ ] **Step 2–4:** fail → implement → pass.
- [ ] **Step 5:** Commit `feat(seo): admin backfill for content-changed dates`.

### Task 9: Private-cluster gate (opus)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/auth/PrivateClusterGate.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthorizationManager.java` (`decide` ~line 219, `filterViewable` ~line 180); `ini/wikantik.properties`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/PrivateClusterGateTest.java`, additions to `DefaultAuthorizationManagerTest` (or the existing authorization test class)

**Interfaces — Produces:**
```java
final class PrivateClusterGate {
    PrivateClusterGate(Set<String> privateClusters, Set<String> viewerGroups, PageManager pages);
    static PrivateClusterGate fromProperties(Properties p, PageManager pages);
    boolean enabled();                       // false when privateClusters is empty
    /** True when any cluster membership of the page is, or is a sub-cluster of, a private cluster. */
    boolean isPrivate(Page page);
    Set<String> viewerGroups();
}
```
Settings: `wikantik.access.privateClusters` (blank default; comma-separated cluster paths; "restart required; run an ontology rebuild after changing") and `wikantik.access.privateClusters.viewers` (blank; comma-separated group names; Admin always allowed).

Pinned decisions:
- Membership source is the page's **stored text**: `pages.getPureText(name, LATEST)` → `FrontmatterParser.parse` → `ClusterPath.memberships(meta.get("cluster"))`, matched with `ClusterPath.isSelfOrDescendant(membership, privateCluster)`. No flexmark body parse. Cache results keyed by (name, version, lastModified) exactly as `DefaultAclManager`'s parsed-ACL cache does. Never consult the structural index.
- In `decide`, after the static-permission check and for every `PagePermission` action: if `gate.enabled()` and the page exists and `gate.isPrivate(page)` and the session holds none of the viewer groups (`hasRoleOrPrincipal(session, new GroupPrincipal(name))`) → `new Decision(false, "private-cluster")`. Otherwise continue to the unchanged ACL evaluation — this ordering *is* the intersection. AllPermission/bootstrap already returned above, so Admin passes.
- `filterViewable`'s blanket fast path must apply the same gate before adding a page.
- Unparseable frontmatter with the gate enabled → not private, `LOG.warn` once per page version.
- When disabled (default) there is zero added work per decision — assert it.

- [ ] **Step 1: Failing tests.** Gate: scalar cluster match; list with private cluster second; sub-cluster `home-lab-infrastructure/x` private; `home-lab-infrastructure-notes` **not** private; hub page of the cluster private; no cluster / null text / broken YAML → not private; cache hit does not re-read text for the same version and does re-read after a version bump. Authorization (private page, gate enabled): anonymous view → denied reason `private-cluster`; authenticated non-member view and edit → denied; member of a viewer group → allowed; Admin → allowed; private page whose body has `[{ALLOW view All}]` → anonymous still denied; private page with `[{ALLOW view Admin}]` → viewer-group member denied (page narrows); locked page → viewer denied; `filterViewable(anonymous, [public, private])` → `[public]`; gate disabled → every case behaves as before and `getPureText` is never called by the gate.
- [ ] **Step 2:** `mvn test -pl wikantik-main -Dtest=PrivateClusterGateTest,DefaultAuthorizationManagerTest` — FAIL. **Step 3:** implement, declare properties, regenerate the config reference. **Step 4:** PASS, plus `mvn test -pl wikantik-main -Dtest='*Acl*,*Authorization*,*Permission*'` and the ArchUnit tests (`*ArchTest`) PASS.
- [ ] **Step 5:** Commit `feat(auth): private clusters hidden by permission`.

### Task 10: Surface-sweep integration test

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/PrivateClusterSurfaceIT.java`
- Modify: that IT module's wiki properties (where its `wikantik-custom.properties`/overlay is defined) to set `wikantik.access.privateClusters=it-private`
- If the dense module is needed for bundle/briefing/knowledge-MCP, add a second small IT there (`wikantik-it-test-dense`) rather than enabling embeddings in rest.

**Interfaces — Consumes:** Task 9's settings.

- [ ] **Step 1: Write the IT.** Setup as admin (`test.properties` credentials pattern used by sibling ITs): create hub `ItPrivateHub` (`type: hub`, `cluster: it-private`), `ItPrivateRunbook` (`cluster: [it-public, it-private]`, body contains the marker `zzprivmarker192`), and public `ItPublicPage` linking to `ItPrivateRunbook`. **Immediately after the create call returns**, as anonymous assert:
  - `GET /wiki/ItPrivateRunbook` → 404 and body lacks the marker; `?format=md` and `?format=json` → 404
  - `GET /api/pages/ItPrivateRunbook` (and the for-agent projection) → denied, no marker
  - `GET /api/search?q=zzprivmarker192` → no hit; `/sitemap.xml` lacks both private pages; Atom feed and `/api/changes` lack them
  - link preview/embed endpoints for the page → denied; backlinks of the private page and "similar pages" of `ItPublicPage` do not name it; Page Graph API omits it
  - knowledge MCP `get_page`, `retrieve_context`/`search_knowledge` for the marker, `list_pages_by_filter(cluster=it-private)`, `list_clusters` → no private page (enumerate the remaining surfaces from the spec's anti-goal list and the CLAUDE.md endpoint table; every one gets an assertion or a comment saying why it cannot expose a page)
  - `/sparql` query for the page and `/id/…` → absent (after triggering the ontology sync the module's other ITs use)
  - As admin: `/wiki/ItPrivateRunbook` → 200 with the marker; then anonymous again with cache-busting off → still 404 (render cache did not leak).
  - Admin MCP `update_page` on the private page still succeeds (jakemon's path).
- [ ] **Step 2:** Run via `bin/run-tests.sh --module rest` (through `bin/agent-build.sh` — it exceeds 5 minutes). Any surface that leaks is a **bug to fix in that surface's permission call**, in this task, with a unit test beside the fix; report each one found.
- [ ] **Step 3:** Commit `test(auth): private-cluster surface sweep IT` (plus any fixes, each its own commit).

### Task 11: Docs, gate, review

- [ ] **Step 1:** Update `docs/admin/WikantikOperations.md` with a "Private clusters" and a "Content dates backfill" section (settings, restart + ontology rebuild after a change, the backfill call, anonymous verification commands from the spec's "After deploy"). Update `docs/admin/IndexingSupport.md` (lastmod semantics, `/` canonical). Add a short Security Model bullet and the module/endpoint line to `CLAUDE.md`. Changelog entry.
- [ ] **Step 2:** `bin/run-tests.sh --parallel 4` via `bin/agent-build.sh` — must be green; fix anything red in-session.
- [ ] **Step 3:** Opus cross-cutting review (superpowers:requesting-code-review) focused on Task 9/10 and the spec's anti-goals; apply findings.
- [ ] **Step 4:** Commit and push. Hand the owner the prod steps: release + `! bin/deploy-release.sh`, add the two `wikantik.access.privateClusters*` lines to prod custom properties, restart, `POST /admin/seo/content-dates/backfill`, ontology rebuild; then verify as anonymous and do the Search Console steps.
