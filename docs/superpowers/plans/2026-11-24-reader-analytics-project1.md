# Reader Analytics Project 1 (Wikantik) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Load the Umami tracker on every reader page through a same-origin proxy, emit the engagement events, read the suite's views back nightly into two rollup tables, and show the results on `/admin/insights` and through one admin MCP tool.

**Architecture:**
- A servlet in `wikantik-http` relays `/analytics/<script>` and `/analytics/api/send` to Umami.
- `SpaRoutingFilter`'s head injection adds the tracker tag when analytics is enabled.
- A small frontend module sends pageviews and events.
- In `wikantik-insights` (which depends on `wikantik-jdbc` only), a read-back reads the suite views through a second, read-only DataSource and replaces rows in `reader_engagement_daily` / `reader_acquisition_daily`. It's wired and scheduled from `com.wikantik.insights.runtime` in wikantik-main, the way `EffectEvaluationScheduler` is.

**Tech Stack:** Java 25, Jakarta Servlet, `java.net.http.HttpClient`, `com.wikantik.jdbc.Jdbc`, PostgreSQL JDBC (`PGSimpleDataSource`), Micrometer, React + Vite, vitest, JUnit 5 + Mockito, `PostgresTestDb`.

**Spec:** `docs/superpowers/specs/2026-11-24-reader-acquisition-retention-design.md`, plus Project 1 of the wiki page `SimpleAgilityReaderEngagementLoop` (the event vocabulary and the read-back rules).

**Depends on:** `docs/superpowers/plans/2026-11-24-backoffice-reader-views.md` for production verification only. Every task here builds and tests against stubs; Task 9 is the production switch-on.

## Global Constraints

- **Analytics is off unless configured.** Blank website id means no tracker tag, a proxy that returns 404, and no read-back.
- **Umami being down or slow never changes a page, delays first render, or logs a browser error that blocks anything.**
- **CSP is unchanged.** The operative policy is the `web.xml` init-param, and `script-src 'self'` / `connect-src 'self'` already allow same-origin `/analytics/*`. `SecurityHeadersIT` must stay green untouched.
- **No tracker and no pageviews on `/admin`, `/admin/*`, `/edit/*`, `/diff/*`, `/preferences`, or `/work*`.**
- **Event properties.** Names and values come from page metadata or a fixed set, never from page text. The vocabulary and channel list are exactly the ones in the suite plan's Global Constraints.
- **Active time** counts only while the tab is visible and the reader has interacted within the last 30 s. `page-exit` fires once per page visit.
- **"Day" is UTC.** Each read-back replaces the trailing `wikantik.analytics.readback.days` (default 3) days.
- **Returning share** is labelled "returning this month". The first 7 days of each month are excluded from any returning-share figure shown over a window.
- **Repo rules.** TDD. Every config key is declared in `ini/wikantik.properties` first (`ConfigSurfaceDriftTest`), and the config docs are regenerated with `bin/config-reference.sh --write`. Migrations are DDL-only and idempotent. No empty catch blocks; log `LOG.warn` with context. A new admin MCP tool goes into `wikantik-mcp-instructions.txt` (both copies) and `McpProtocolIT.EXPECTED_TOOLS`.
- **Config keys** are lower-case and dotted with no underscores, so `PropertyReader` maps `WIKANTIK_*` env vars onto them.

## Review Focus

1. **Umami unreachable, or answering in 30 s.**
   - The proxy answers within its timeout, and once the in-flight cap (16) is full it answers `503` immediately.
   - Pages render the same.
   - The read-back logs one warning and keeps the old rows.
   - Tests: Task 2 (slow-upstream test), Task 6 (failure test).
2. **An ad blocker blocks `/analytics/script.js`.** `window.umami` is undefined, every `track` call is a no-op, and no exception is thrown. Test: Task 4.
3. **A spoofed `X-Forwarded-For` from the browser.** It's replaced by the address `RemoteIpValve` resolved, never forwarded. Test: Task 2.
4. **Landing paths that aren't simple slugs.** URL-encoded slugs, a trailing slash, `/`, a renamed page's old slug, `/search`, and a path with a query string. Each resolves to the page's `canonical_id`, or to the site row (`canonical_id = ''`). Test: Task 5.
5. **SPA navigation from a reader page into `/edit/X`.** No pageview is sent, and a `page-exit` fires for the page being left. Test: Task 4.

---

### Task 1: Config surface and the migration

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (new section "Reader analytics (Umami)" near the insights keys, ~line 2294)
- Create: `bin/db/migrations/V097__reader_analytics_rollups.sql`
- Regenerate: `docs/ConfigurationReference.md` via `bin/config-reference.sh --write` (and the wiki page it names)

**Interfaces:**
- Produces config keys (exact names and defaults):
  - `wikantik.analytics.website.id=` (blank = everything off)
  - `wikantik.analytics.upstream=` (Umami base URL, e.g. `http://192.168.5.52:8085`; blank = proxy off)
  - `wikantik.analytics.script.name=script.js`
  - `wikantik.analytics.proxy.timeout.ms=5000`
  - `wikantik.analytics.proxy.max.inflight=16`
  - `wikantik.analytics.readback.url=` (JDBC URL; blank = read-back off)
  - `wikantik.analytics.readback.user=wikantik_ro`
  - `wikantik.analytics.readback.password=`
  - `wikantik.analytics.readback.days=3`
  - `wikantik.analytics.readback.hours=24`
  - `wikantik.analytics.site.host=` (blank means: the host of the configured base URL)
- Produces tables (PK in brackets; every count column is `INTEGER NOT NULL DEFAULT 0`; `GRANT SELECT, INSERT, UPDATE, DELETE … TO :app_user`):
  - `reader_engagement_daily` [`day DATE, site_host TEXT, canonical_id TEXT`]. Columns: `views`, `visitors`, `exits`, `active_median_s NUMERIC`, `active_p90_s NUMERIC`, `exits_scroll75`, `code_copies`, `wikilink_clicks`, `outbound_clicks`, `diagram_interacts`, `toc_clicks`, `sign_in_clicks`, `lcp_p75_ms NUMERIC`, `inp_p75_ms NUMERIC`, `cls_p75 NUMERIC`, `refreshed_at TIMESTAMPTZ NOT NULL DEFAULT now()`. The numeric columns are nullable: null means no data, never zero.
  - `reader_acquisition_daily` [`day, site_host, canonical_id, channel, source`]. Columns: `landings`, `continued`, `returning`, `refreshed_at`. Index on (`site_host`, `day`).
  - `canonical_id = ''` is the site row for paths that aren't pages.

- [ ] **Step 1: Write the failing check.** Run `mvn test -pl wikantik-main -Dtest=ConfigSurfaceDriftTest` after adding a reader of one key (Task 3's settings record). It fails as `MISSING` until the key is declared. Alternatively, declare the keys and let `ConfigReferenceRegressionTest` fail until the docs are regenerated.
- [ ] **Step 2: Declare the keys** with description, `Type:` and default (`site.host` gets `Blank means:`). Write the migration. Apply it twice to a local DB (`bin/db/migrate.sh`) and confirm the second run is a no-op.
- [ ] **Step 3: Regenerate the docs and run** `mvn test -pl wikantik-main -Dtest='ConfigSurfaceDriftTest,ConfigReferenceRegressionTest'`. Expected: PASS.
- [ ] **Step 4: Commit:** `feat(analytics): config surface and V097 reader rollup tables`.

### Task 2: `/analytics/*` proxy servlet

**Files:**
- Create: `wikantik-http/src/main/java/com/wikantik/http/analytics/AnalyticsProxyServlet.java`
- Create: `wikantik-http/src/test/java/com/wikantik/http/analytics/AnalyticsProxyServletTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (declare near the insights servlets ~813, map `/analytics/*` ~1226)
- Modify: `wikantik-http/src/main/java/com/wikantik/http/filter/CsrfProtectionFilter.java:217` (exempt `/analytics/api/send` JSON POSTs, alongside `/api/` and `/admin/`)

**Interfaces:**
- Produces: `AnalyticsProxyServlet(HttpClient client, Supplier<AnalyticsProxyConfig> config)`, plus a no-arg constructor that reads the engine properties the way existing `wikantik-http` components reach them (wikantik-http does not depend on wikantik-main, so it parses the keys itself rather than using Task 3's `AnalyticsSettings`). Also `record AnalyticsProxyConfig(String upstream, String scriptName, Duration timeout, int maxInflight)`, where `enabled()` means `upstream` and the website id are both non-blank.

- [ ] **Step 1: Write the failing tests** (JDK `HttpServer` as the stub upstream):
  - `scriptIsRelayedAndCached`: two `GET /analytics/script.js` → one upstream hit; response `200`, `Content-Type: application/javascript`, `Cache-Control: public, max-age=3600`.
  - `staleScriptServedWhenUpstreamDown`: after one success, stop the upstream → still `200` with the cached body.
  - `sendIsRelayedWithResolvedClientIp`: `POST /analytics/api/send` with `request.getRemoteAddr()` = `203.0.113.7` and an incoming `X-Forwarded-For: 6.6.6.6` → upstream sees `X-Forwarded-For: 203.0.113.7` exactly, plus the original `User-Agent`, and the body unchanged. The upstream's status and body are returned (Review Focus 3).
  - `onlyTwoPathsExist`: `GET /analytics/api/send`, `GET /analytics/other.js`, `/analytics/../x` → `404`.
  - `oversizeBodyRejected`: body > 64 KiB → `413`, no upstream call.
  - `disabledIs404`: blank upstream → `404` for both paths.
  - `slowUpstreamFailsFast`: upstream sleeps 30 s, timeout 200 ms → `504` within 1 s. With the in-flight cap at 1 and one request stuck, the second request gets `503` immediately (Review Focus 1).
  - `CsrfProtectionFilterTest`: a JSON POST to `/analytics/api/send` with no CSRF token passes; a form POST does not.
- [ ] **Step 2: Run** `mvn test -pl wikantik-http -Dtest='AnalyticsProxyServletTest,CsrfProtectionFilterTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - A `Semaphore(maxInflight)` with `tryAcquire()`.
  - The connect timeout is the same as the request timeout.
  - Redirects are `NEVER`.
  - The script cache is an `AtomicReference` holding the body and its fetch time, refreshed after 1 h and kept on refresh failure.
  - Every failure logs `LOG.warn` with the path and the cause, at most once a minute (a counter, not per request).
- [ ] **Step 4: Run the tests.** Expected: PASS. Also run `mvn test -pl wikantik-http`.
- [ ] **Step 5: Commit:** `feat(analytics): same-origin /analytics proxy to Umami`.

### Task 3: Tracker tag injection

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java` (the head-injection path, ~380 / ~586)
- Create: `wikantik-main/src/main/java/com/wikantik/analytics/AnalyticsSettings.java` (record parsed from properties; shared by the filter, the servlet's no-arg constructor and Task 6)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterAnalyticsTest.java`

**Interfaces:**
- Produces: `AnalyticsSettings.from(Properties)` → `record AnalyticsSettings(String websiteId, String upstream, String scriptName, Duration proxyTimeout, int proxyMaxInflight, String readbackUrl, String readbackUser, String readbackPassword, int readbackDays, int readbackHours, String siteHost)`, with `trackerEnabled()` and `readbackEnabled()`.
- The tag is exactly:
  ```html
  <script defer src="/analytics/script.js" data-website-id="{id}" data-host-url="/analytics" data-auto-track="false" data-performance="true"></script>
  ```
  The script name comes from config, and the context path is prefixed if the app isn't deployed at ROOT.

- [ ] **Step 1: Write the failing tests:**
  - an enabled SSR `/wiki/Main` response contains the tag once, inside `<head>`;
  - a disabled one contains no `/analytics/`;
  - `/admin/insights` and `/edit/Main` never contain the tag, even when enabled;
  - an existing ETag test still distinguishes enabled from disabled: add the settings to the shell fingerprint so a cached 304 can't serve a page without the tag.
- [ ] **Step 2: Run** `mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterAnalyticsTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Before writing it, list every way reader HTML reaches a browser (SSR `/wiki/*`, `/`, the other SPA routes served from `index.html`). Each one must go through this injection. Any path that bypasses it gets a test.
- [ ] **Step 4: Run** `mvn test -pl wikantik-rest`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(analytics): inject the Umami tracker on reader pages`.

### Task 4: Frontend analytics module and events

**Files:**
- Create: `wikantik-frontend/src/analytics/umami.js`, `src/analytics/umami.test.js`
- Create: `wikantik-frontend/src/analytics/useReaderAnalytics.js` (route pageviews + page-exit), `useReaderAnalytics.test.jsx`
- Modify: `src/App.jsx` (call the hook beside `useLocation()` at ~41)
- Modify: `src/components/PageView.jsx` (`handleContentClick` ~528-567 → `wikilink-click` / `outbound-click`; pass page context to the hook)
- Modify: `src/utils/codeCopy.js` (`code-copy`), `src/components/TableOfContents.jsx` (`toc-click`), `src/components/WikiFence.jsx` and the KaTeX click path (`diagram-interact`), `src/components/SsoLoginButton.jsx` and `UserBadge.jsx` sign-in entry (`sign-in-click`)

**Interfaces:**
- Produces:
  - `track(name: string, props: object): void`, which is a no-op without `window.umami` and swallows errors with a `console.debug`.
  - `trackPageview(): Promise<void>`
  - `stripCampaignParams(): void`, which removes `utm_*`, `gclid`, `msclkid`, `fbclid`, `li_fat_id`, `ttclid`, `twclid` via `history.replaceState`.
  - `isTrackedRoute(pathname): boolean`, which is false for the excluded routes in Global Constraints.
  - `useReaderAnalytics({canonicalId, cluster})`.
- Page context: `canonical_id` and the primary `cluster` come from the page object `PageView` already loads. Confirm the field names in the page API response, and take the first entry when `cluster` is a list.

- [ ] **Step 1: Write the failing vitest tests:**
  - `track` with no `window.umami` doesn't throw (Review Focus 2).
  - The first tracked route calls `umami.track()` once, then strips `?utm_source=x&gclid=y` from `location.search` while keeping other params. Stripping happens only after the pageview promise resolves.
  - Navigating `/wiki/A` → `/edit/A` sends no pageview and one `page-exit` for A (Review Focus 5).
  - Active time with fake timers: 10 s visible with interaction → `active_s≈10`; 60 s with no interaction after the first 5 s → `active_s≈35` (5 + the 30 s grace); hidden time is not counted.
  - `max_scroll_pct` is the maximum reached, capped at 100. `page-exit` is sent once even if `visibilitychange` and `pagehide` both fire.
  - Each action handler sends its event with the exact property names, and `outbound-click` `dest` is the host only.
- [ ] **Step 2: Run** `cd wikantik-frontend && npx vitest run src/analytics src/components/PageView.test.jsx`. Expected: FAIL.
- [ ] **Step 3: Implement.** Check whether the pinned Umami tracker's send survives unload (`keepalive`). If it doesn't, send `page-exit` with `navigator.sendBeacon('/analytics/api/send', new Blob([json], {type:'application/json'}))` in the tracker's payload shape, and record which path was taken in a code comment.
- [ ] **Step 4: Run** `npx vitest run` and `npx eslint src`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(analytics): reader pageviews, page-exit and action events`.

### Task 5: Path to `canonical_id` resolver

**Files:**
- Create: `wikantik-insights/src/main/java/com/wikantik/insights/readers/PathResolver.java` (port)
- Create: `wikantik-main/src/main/java/com/wikantik/insights/runtime/WikiPathResolver.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/pagegraph/spine/PageCanonicalIdsDao.java` (add `Optional<String> findCanonicalIdByHistoricalSlug(String slug)` over `page_slug_history`)
- Test: `wikantik-main/src/test/java/com/wikantik/insights/runtime/WikiPathResolverTest.java` (`@RequiresPostgres`)

**Interfaces:**
- Produces: `interface PathResolver { Optional<String> canonicalIdFor(String urlPath); }` and `WikiPathResolver(PageCanonicalIdsDao dao, String frontPageName)`.

- [ ] **Step 1: Write the failing tests** (Review Focus 4). Seed `page_canonical_ids` with `Main → 01MAIN`, `IntervalTrees → 01IT`, and slug history `OldTrees → 01IT`:
  - `/wiki/IntervalTrees`, `/wiki/IntervalTrees/` and `/wiki/Interval%54rees` → `01IT`
  - `/` → `01MAIN`
  - `/wiki/OldTrees` → `01IT`
  - `/search`, `/wiki/Nope` and `/login` → empty
  - `/wiki/IntervalTrees?x=1` → `01IT`
- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest=WikiPathResolverTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Strip the query, URL-decode, trim a trailing `/`, and try the current slug, then the historical slug. Use a per-run memo map, and don't cache across runs.
- [ ] **Step 4: Run the test.** Expected: PASS.
- [ ] **Step 5: Commit:** `feat(analytics): resolve Umami paths to canonical ids`.

### Task 6: Read-back: views client, rollup store and job

**Files** (in `wikantik-insights/src/main/java/com/wikantik/insights/readers/`; tests under `src/test/java/.../readers/`):
- Create: `UmamiViews.java` (port), `JdbcUmamiViews.java`
- Create: `ReaderRollupStore.java`, `JdbcReaderRollupStore.java`
- Create: `ReaderAnalyticsReadback.java`
- Create: `EngagementDay.java`, `AcquisitionDay.java` (records matching the V097 columns)

**Interfaces:**
- Consumes: Task 5 `PathResolver`; the suite views' column lists (suite plan, Tasks 2-3).
- Produces:
  - `UmamiViews`:
    - `List<TrafficRow> traffic(LocalDate from, LocalDate to)`
    - `List<EngagementRow> engagement(LocalDate from, LocalDate to)`
    - `List<VitalsRow> vitals(LocalDate from, LocalDate to)`
    - `List<AcquisitionRow> acquisition(LocalDate from, LocalDate to)`
    
    All are filtered on `website_id = ?`. `JdbcUmamiViews(Jdbc jdbc, UUID websiteId)`.
  - `ReaderRollupStore.replace(String siteHost, LocalDate from, LocalDate to, List<EngagementDay> engagement, List<AcquisitionDay> acquisition)` runs in one `inTransaction`: delete the window, then insert.
  - `ReaderAnalyticsReadback(UmamiViews views, ReaderRollupStore store, PathResolver resolver, String siteHost, int days, Clock clock, MeterRegistry meters)`, with `ReadbackResult runOnce()`. `ReadbackResult` is `ok | failed`, with row counts and the error message.
- Merge rules:
  - Path-keyed rows (traffic, vitals, acquisition) map to `canonical_id` through the resolver, with unresolved paths going to `''`.
  - Several paths with one id sum their counts. Vitals take the path with the most `samples`.
  - Engagement rows are already keyed by `canonical_id`.
  - Visitors from several paths are summed, and the panel labels the figure "visitors (sum of paths)".
- Metrics: `wikantik_reader_readback_runs_total{outcome}`, `wikantik_reader_readback_last_success_seconds` (gauge).

- [ ] **Step 1: Write the failing tests.**
  - `JdbcReaderRollupStoreTest` (`@RequiresPostgres`, truncate both tables in `@BeforeEach`):
    - replacing a window twice with the same rows leaves identical rows;
    - rows outside the window are untouched;
    - a `FaultInjectingDataSource` failure on the 2nd insert leaves the previous rows intact.
  - `JdbcUmamiViewsTest` (`@RequiresPostgres`): create the four views as stub tables with the suite's exact column lists in the test schema. Never use a migration for this, because they are another database's objects. Assert the `website_id` filter and the date bounds.
  - `ReaderAnalyticsReadbackTest` (Mockito `UmamiViews`):
    - two paths for one id merge;
    - an unresolved path goes to the `''` row;
    - `views.engagement` throwing `RuntimeException` → `failed`, `store.replace` is never called, `runs_total{outcome="failed"}` is incremented and a warning is logged (Review Focus 1);
    - the window is `[today-days+1 .. today]` in UTC from the `Clock`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-insights` and the `@RequiresPostgres` tests. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn test -pl wikantik-insights`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(analytics): nightly read-back of Umami views into reader rollups`.

### Task 7: Wiring and scheduling

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/insights/runtime/ReaderAnalyticsScheduler.java` (mirror `EffectEvaluationScheduler`: one daemon thread `wikantik-reader-readback`; the first run 5 minutes after start, not one full interval, so a deploy yields data the same day)
- Create: `wikantik-main/src/main/java/com/wikantik/insights/runtime/UmamiDataSourceFactory.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/insights/runtime/InsightsWiring.java` (~55-100: build the read-back when `readbackEnabled()`)
- Test: `ReaderAnalyticsSchedulerTest`, `UmamiDataSourceFactoryTest`, `InsightsWiringTest` (extend)

**Interfaces:**
- Produces:
  - `UmamiDataSourceFactory.create(AnalyticsSettings) : DataSource`, a `PGSimpleDataSource` with `readOnly=true`, `connectTimeout=5`, `socketTimeout=120`, `loginTimeout=5`, `options=-c statement_timeout=60000`.
  - `ReaderAnalyticsScheduler(ReaderAnalyticsReadback readback, int intervalHours)` with `start()` and `stop()`.
  - `InsightsWiring` keeps the scheduler reference, and its engine-shutdown path calls `stop()`. Add the hook if the wiring has none: `EffectEvaluationScheduler` today leaks its thread, so fix both while you're here.

- [ ] **Step 1: Write the failing tests:**
  - the factory sets the five properties;
  - the scheduler is not started when `readbackEnabled()` is false;
  - `runOnce` throwing never kills the schedule (the second tick still runs);
  - `stop()` ends the thread.
- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest='ReaderAnalyticsSchedulerTest,UmamiDataSourceFactoryTest,InsightsWiringTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit:** `feat(analytics): wire and schedule the reader read-back`.

### Task 8: Read surface: admin panel, REST and MCP tool

**Files:**
- Create: `wikantik-insights/src/main/java/com/wikantik/insights/readers/ReaderReport.java` (window queries over both rollups plus `search_visibility_snapshot`)
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/InsightsReadersResource.java`; map `GET /admin/insights/readers` in `web.xml` beside the other insights servlets
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListReaderEngagementTool.java`; register it read-only in `McpToolRegistry.java:~148`
- Modify: `wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt`, `wikantik-mcp-core/src/test/resources/wikantik-mcp-instructions.txt`, `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/McpProtocolIT.java:61`
- Create: `wikantik-frontend/src/components/admin/ReaderEngagementPanel.jsx` (+ test), rendered inside `InsightsPanel.jsx`; client method `getInsightsReaders(days)` in `src/api/client.js` beside `getInsightsBacklog` (~642)

**Interfaces:**
- Produces: `ReaderReport.pages(String siteHost, int days, int limit) : List<PageReaderStats>`, with these fields:
  - `canonical_id`, slug, `views`, `visitors`;
  - `typical_active_s` (the exit-weighted median of daily medians, labelled exactly that way), `scroll75_rate`;
  - `actions_per_view`;
  - `landings` (by channel: a map), `continuation_rate`, `returning_this_month_rate` (computed over days ≥ 8 of each month only);
  - `lcp_p75_ms`, `inp_p75_ms`, `cls_p75`.
  
  Also `ReaderReport.coverage(String siteHost, int days) : List<CoverageRow(engine, canonical_id, search_clicks, organic_landings)>` plus a site-wide ratio.
- REST JSON is `{ pages: [...], coverage: { site: {engine: ratio}, pages: [...] }, refreshedAt }`, ordered by views descending. `days` defaults to 28, max 90.
- MCP tool `list_reader_engagement(days?, limit?, cluster?)` returns the same `pages` array plus `refreshedAt`, in the evidence style of `ListContentOpportunitiesTool`.

- [ ] **Step 1: Write the failing tests:**
  - `ReaderReportTest` (`@RequiresPostgres`): the exit-weighted median of {(30 s, 2 exits), (60 s, 1 exit)} → `30`; returning share ignores rows dated on the 1st–7th; the coverage ratio is `organic_landings / search_clicks`, joined on engine key and `canonical_id` (via the page's path in `search_visibility_snapshot.page_path` → resolver).
  - `InsightsReadersResourceTest`: 403 without admin; `days=500` → clamped to 90.
  - `ListReaderEngagementToolTest`, and the instructions drift test (`InstructionsRegistryDriftTest`).
  - `ReaderEngagementPanel.test.jsx`: renders "returning this month" and "typical active time"; shows an empty state reading "No reader data yet — analytics off or not yet read back" when `pages` is empty.
- [ ] **Step 2: Run** `mvn test -pl wikantik-insights,wikantik-rest,wikantik-admin-mcp` and `npx vitest run src/components/admin`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the same commands.** Expected: PASS.
- [ ] **Step 5: Commit:** `feat(analytics): reader engagement on /admin/insights and list_reader_engagement`.

### Task 9: Full gate, runbook and production switch-on

**Files:**
- Modify: `docs/admin/WikantikOperations.md`, adding a section "Reader analytics (Umami)" with:
  - the one-time website registration with a fixed UUID as the `wikantik` Umami account (`POST /api/websites {"id","name":"wiki.wikantik.com","domain":"wiki.wikantik.com"}`, per Pressroom's `docs/dev/umami-api.md`);
  - the `.env.prod` lines `WIKANTIK_ANALYTICS_WEBSITE_ID`, `WIKANTIK_ANALYTICS_UPSTREAM=http://192.168.5.52:8085`, `WIKANTIK_ANALYTICS_READBACK_URL=jdbc:postgresql://192.168.5.52:8087/umami`, `WIKANTIK_ANALYTICS_READBACK_PASSWORD`;
  - the checks below.

- [ ] **Step 1: Run the full gate.** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`, then poll `status` / `wait` until it finishes. Expected: `SUCCESS exit=0`. Fix anything red in this session.
- [ ] **Step 2: Request code review** (superpowers:requesting-code-review) over the whole branch.
- [ ] **Step 3: Release, then hand Jake the switch-on.** This happens after the suite plan's Task 4 deploy. Deploys and `.env.prod` edits on docker1 are blocked for agents, so give the exact commands. In the same release window, update `PrivacyPolicy` on prod through the admin MCP with the cookieless-analytics, referrer/campaign and page-speed statements from the spec, following the `wiki-content` skill.
- [ ] **Step 4: Verify in production and report each item:**
  - a visit to a wiki page shows a pageview in Umami's wiki website, and leaving shows a `page-exit` with plausible `active_s` / `max_scroll_pct`;
  - `?utm_source=test` is gone from the address bar after load;
  - `curl -sI https://wiki.wikantik.com/wiki/Main` CSP is byte-identical to before;
  - stopping Umami (Jake, on docker2) leaves pages unaffected;
  - the next read-back fills both tables for yesterday, and re-running it changes nothing;
  - `/admin/insights` shows the reader panel.
