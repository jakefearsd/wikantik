# Admin Deferred Features + UI Test Hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the dashboard cards/fields deferred from the admin refresh (every one), and drive admin UI automated coverage as high as practical — exhaustive Vitest at the component/interaction layer plus targeted Selenide browser flows.

**Architecture:** Extends `GET /admin/overview` (`AdminOverviewResource` + `com.wikantik.rest.overview.*`) and `OverviewDashboard.jsx`. Backend adds a few narrow count methods to existing services. Tests follow the established Vitest mock idiom and Selenide page-object idiom (documented below).

**Tech Stack:** Java 21, Maven, Micrometer, Gson; React 18, Vitest + @testing-library/react; Selenide (Cargo Tomcat + PostgreSQL/pgvector).

**Data-source map** (from investigation — use verbatim):
- Locked users → NEW `long countLockedUsers()` on `UserDatabase` + `JDBCUserDatabase` (`SELECT COUNT(*) FROM users WHERE lock_expiry IS NOT NULL AND lock_expiry > CURRENT_TIMESTAMP`).
- KG orphans → EXISTING `kgService().countOrphanedNodes(new HashMap<>())`.
- KG stubs → NEW `long countStubNodes()` on `KgNodeRepository` (`SELECT COUNT(*) FROM kg_nodes WHERE source_page IS NULL`) + `KnowledgeGraphService` + `DefaultKnowledgeGraphService`.
- Judge pending depth → EXISTING `kgService().countPendingUnjudgedProposals()`.
- Failed logins → metric `wikantik.auth.logins{result=failure}` exists but is never fired; add `fireEvent(WikiSecurityEvent.LOGIN_FAILED, ...)` in `DefaultAuthenticationManager` before `registerFailedLogin`. Read tag-aware.
- for-agent bytes → `DistributionSummary wikantik_for_agent_response_bytes` via `reg.find(name).summary()`.
- Verification mix → iterate `structuralIndexService().listPagesByFilter(StructuralFilter.none())` + `verificationOf(id)`; factor a shared `verificationCounts()`.
- Retrieval per mode → EXISTING `retrievalQualityRunner().recentRuns(null, mode, 1)` for BM25/HYBRID/HYBRID_GRAPH.
- Attachments → CONFIG only (`AttachmentManager.PROP_*` via `getSubsystems().core().properties()`); no count metric.

**Phasing:** A (backend) → B (frontend cards + EmptyState + testids) → C (Vitest coverage) → D (Selenide). A and the test phases are independently shippable.

---

## PHASE A — Backend: enrichment + new accessors

### Task A1: Make MetricReads tag-aware + add summary helpers
**Files:** `wikantik-rest/.../overview/MetricReads.java`; test `MetricReadsTest.java` (extend).

- [ ] **Add tests** to `MetricReadsTest` (keep existing): a tagged counter read selects the right tag; a `DistributionSummary` count+mean read; null-safe defaults.
```java
@Test void taggedCounterSelectsTag() {
    final MeterRegistry reg = new SimpleMeterRegistry();
    reg.counter("c", "result", "success").increment(3);
    reg.counter("c", "result", "failure").increment();
    assertEquals(3.0, MetricReads.counter(reg, "c", "result", "success", -1));
    assertEquals(1.0, MetricReads.counter(reg, "c", "result", "failure", -1));
    assertEquals(-1.0, MetricReads.counter(reg, "missing", "result", "x", -1));
}
@Test void summaryCountAndMean() {
    final MeterRegistry reg = new SimpleMeterRegistry();
    final DistributionSummary s = DistributionSummary.builder("s").register(reg);
    s.record(100); s.record(300);
    assertEquals(2L, MetricReads.summaryCount(reg, "s", 0));
    assertEquals(200.0, MetricReads.summaryMean(reg, "s", 0));
    assertEquals(0L, MetricReads.summaryCount(null, "s", 0));
}
```
- [ ] **Run** `mvn test -pl wikantik-rest -Dtest=MetricReadsTest` → FAIL.
- [ ] **Implement** the three new static methods (keep existing two):
```java
public static double counter(final MeterRegistry reg, final String name,
                             final String tagKey, final String tagVal, final double dflt) {
    if (reg == null) return dflt;
    final Counter c = reg.find(name).tag(tagKey, tagVal).counter();
    return c == null ? dflt : c.count();
}
public static long summaryCount(final MeterRegistry reg, final String name, final long dflt) {
    if (reg == null) return dflt;
    final io.micrometer.core.instrument.DistributionSummary s = reg.find(name).summary();
    return s == null ? dflt : (long) s.count();
}
public static double summaryMean(final MeterRegistry reg, final String name, final double dflt) {
    if (reg == null) return dflt;
    final io.micrometer.core.instrument.DistributionSummary s = reg.find(name).summary();
    return s == null ? dflt : s.mean();
}
```
- [ ] **Run** test → PASS. **Commit** `feat(admin-overview): tag-aware + summary MetricReads helpers`.

### Task A2: countLockedUsers()
**Files:** `wikantik-main/.../auth/user/UserDatabase.java` (interface), `JDBCUserDatabase.java`, plus other impls (`XMLUserDatabase`, `DummyUserDatabase`/in-memory) for compile; test `JDBCUserDatabaseTest` or a focused test if one exists.

- [ ] Add to `UserDatabase`: `long countLockedUsers() throws WikiSecurityException;` with a `default` that iterates `getWikiNames()`+`findByWikiName` counting `getLockExpiry()` in the future — so non-JDBC impls work without change. (Confirm `WikiSecurityException` import; mirror existing interface methods.)
- [ ] Override in `JDBCUserDatabase` with the single-query form (`SELECT COUNT(*) FROM users WHERE lock_expiry IS NOT NULL AND lock_expiry > CURRENT_TIMESTAMP`), using the same `ds.getConnection()`/`PreparedStatement` pattern as `deleteByLoginName`. Read the count column. Log+wrap SQLException as the class already does.
- [ ] **Test** (mirror an existing JDBCUserDatabase test if present; else a focused H2 test): seed 2 users, lock one with a future expiry → `countLockedUsers()==1`; expired lock not counted. Run targeted test → PASS.
- [ ] `mvn test-compile -pl wikantik-main` (catch impl breakage). **Commit** `feat(auth): UserDatabase.countLockedUsers()`.

### Task A3: countStubNodes()
**Files:** `wikantik-main/.../knowledge/...KgNodeRepository.java`, `wikantik-api/.../KnowledgeGraphService.java`, `DefaultKnowledgeGraphService.java`; test.

- [ ] `KgNodeRepository.countStubNodes()` → `SELECT COUNT(*) FROM kg_nodes WHERE source_page IS NULL` (model on `countNodes()` `queryCount(...)`).
- [ ] Add `long countStubNodes()` to `KnowledgeGraphService` interface + delegate in `DefaultKnowledgeGraphService` (mirror `countOrphanedNodes`).
- [ ] **Test** (mirror existing KgNodeRepository tests): seed nodes with/without `source_page` → count matches. Run → PASS.
- [ ] `mvn test-compile -pl wikantik-main`. **Commit** `feat(kg): KnowledgeGraphService.countStubNodes()`.

### Task A4: Fire LOGIN_FAILED so the failure metric is live
**Files:** `wikantik-main/.../auth/DefaultAuthenticationManager.java`; test.
> Behavior note: this fires an existing `WikiSecurityEvent.LOGIN_FAILED` that `WikiMetrics` already listens for; no new event type, no auth-decision change.

- [ ] **Test first** (mirror existing DefaultAuthenticationManager tests): a failed `login()` fires `WikiSecurityEvent.LOGIN_FAILED` (assert via a registered listener/spy). Run → FAIL.
- [ ] Add `fireEvent(WikiSecurityEvent.LOGIN_FAILED, <principal/username>, session)` immediately before `registerFailedLogin(username)`. Match the `fireEvent` signature used for the existing `LOGIN_AUTHENTICATED` calls in this class.
- [ ] Run test → PASS. `mvn test-compile -pl wikantik-main`. **Commit** `fix(auth): fire LOGIN_FAILED event on authentication failure`.

### Task A5: Shared verificationCounts()
**Files:** `wikantik-*` `StructuralIndexService` (interface + impl), refactor `AdminVerificationResource` to use it; test.

- [ ] Add `Map<Confidence,Integer> verificationCounts()` (or a small record `{authoritative,provisional,stale,noVerification}`) to `StructuralIndexService`, implemented by moving the tally loop out of `AdminVerificationResource` (`listPagesByFilter(none())` + `verificationOf`; `noVerification` = pages where `verificationOf(id).isEmpty()`).
- [ ] Refactor `AdminVerificationResource` to call it (no behavior change to that endpoint — verify its existing test still passes).
- [ ] **Test** the new method against a small structural index fixture. Run → PASS. `mvn test-compile`. **Commit** `refactor(pagegraph): shared StructuralIndexService.verificationCounts()`.

### Task A6: Enrich + add collectors in AdminOverviewResource
**Files:** `AdminOverviewResource.java`; extend `AdminOverviewResourceTest.java`.

- [ ] **Enrich** existing collectors (add fields):
  - `users`: add `locked` via `getUserDatabase().countLockedUsers()`.
  - `kgSize`: add `stubs` via `countStubNodes()`, `orphans` via `countOrphanedNodes(new HashMap<>())`.
  - `judge`: add `pending` via `countPendingUnjudgedProposals()`.
  - `auth`: read `logins` tag-aware `result=success` AND add `failed` = `result=failure` (use the new `MetricReads.counter(reg,name,tagKey,tagVal,dflt)`; FIX the existing untagged read).
  - `agentSurface`: add `forAgentBytes` = `MetricReads.summaryMean(reg,"wikantik_for_agent_response_bytes",0)` and `forAgentCount` = `summaryCount(...)`.
- [ ] **Add new collectors** (system-metrics band):
  - `contentQuality`: `{authoritative,provisional,stale,noVerification}` via `structuralIndexService().verificationCounts()`.
  - `retrievalModes`: `{bm25,hybrid,hybridGraph}` each = latest `recentRuns(null,mode,1)` `ndcgAt5` (omit a mode's field if no run).
  - `attachments`: `{provider,maxSize,allowedCount,forbiddenCount}` from `AttachmentManager.PROP_*` properties (counts = comma-split length; omit if unset).
  - Each collector accesses sources INSIDE its lambda; throwing degrades only itself (the assembler catches). No fabrication; omit fields not cleanly available.
- [ ] **Extend the unit test**: keep the always-`degraded`-present invariant; add assertions that, given a stubbed registry (SimpleMeterRegistry with known meters) — the resource is exercised through `doGetForTesting` — the envelope still parses and `data` includes the new card keys OR they appear in `degraded` (since DB-backed sources are null in the unit env). The key invariant: no new collector throws OUT of `doGet`.
- [ ] Run `mvn test -pl wikantik-rest -Dtest=AdminOverviewResourceTest` → PASS. `mvn test-compile -pl wikantik-rest`. **Commit** `feat(admin-overview): enrich cards (locked/stubs/orphans/judge/auth/for-agent) + content-quality, retrieval-modes, attachments cards`.

---

## PHASE B — Frontend: render new data, EmptyState, testids

### Task B1: Render enriched fields + 3 new cards + testids in OverviewDashboard
**Files:** `OverviewDashboard.jsx`; extend `OverviewDashboard.test.jsx`.

- [ ] Update `statusCards`/`metricCards`:
  - `users` meta → `${apiKeys} keys · ${locked} locked`.
  - `kgSize` meta → `${edges} edges · ${stubs} stubs · ${orphans} orphans`.
  - `judge` value `pending`, meta `${timeouts} timeout · ${shortCircuit} sc`.
  - `auth` value `logins`, meta `${failed} failed`.
  - `agentSurface` meta `${forAgentBytes}B avg · ${hintFailures} hint fails`.
- [ ] Add three `dim` metric cards: `contentQuality` (value authoritative, meta `${provisional} prov · ${stale} stale · ${noVerification} none`), `retrievalModes` (value `bm25`/`hybrid`/`hybridGraph` shown compactly), `attachments` (value provider, meta `${maxSize} · ${allowedCount} allowed`). Each `degraded={!c}`.
- [ ] Add `data-testid` to the dashboard root (`data-testid="admin-overview"`), each band, and pass a `testId` prop through `MetricCard` (e.g. `data-testid={`metric-card-${key}`}`) — needed by the Selenide ITs.
- [ ] **Extend the test** (the gap matrix flags these as missing): polling fires a refetch after `POLL_MS` (use `vi.useFakeTimers`), interval cleared on unmount, error state on rejection, a `to=` MetricCard renders a link to its route, and the new cards render. Run admin vitest → PASS.
- [ ] **Commit** `feat(admin): render enriched + new overview cards; add testids`.

### Task B2: data-testid hooks on AdminSidebar + MetricCard
**Files:** `AdminSidebar.jsx`, `MetricCard.jsx`; extend their tests.
- [ ] `AdminSidebar`: `data-testid="admin-sidebar"` on the aside, `data-testid="admin-nav-<slug>"` per link, `data-testid="admin-back-to-wiki"` on the back link.
- [ ] `MetricCard`: accept + render an optional `testId` → `data-testid`.
- [ ] Extend tests to assert the testids. Run → PASS. **Commit** `chore(admin): testid hooks for sidebar + metric card`.

### Task B3: Adopt EmptyState in admin empty-lists
**Files:** admin pages with list views that currently render nothing/ad-hoc on empty (per gap matrix: AdminUsersPage, AdminApiKeysPage list, AdminSecurityPage groups/grants, AdminContentPage orphaned/broken lists, the KG tabs' lists where natural). Be surgical — only where a list can be empty and currently shows nothing useful.
- [ ] For each, render `<EmptyState message="…" />` (with an action where a primary create exists) when the loaded list is empty and not loading/error. Keep behavior otherwise identical.
- [ ] Run admin vitest + `npm run build` → PASS. **Commit** `feat(admin): adopt EmptyState across admin list views`.

---

## PHASE C — Exhaustive Vitest coverage

**Mock idiom (apply to every task):** `vi.mock('../../api/client', () => ({ api: { admin:{...}, knowledge:{...}, getUser: vi.fn() } }))` declaring every method the component calls as `vi.fn()`; `beforeEach` → `vi.clearAllMocks()` + default `mockResolvedValue`; per-`it` override with `mockResolvedValueOnce`/`mockRejectedValueOnce`; `await screen.findBy…` past loading; bulk surface via `getByRole('toolbar',{name:'Bulk actions'})`. `admin/table/*` are pure (props only, no API mock). Each task: write tests, run `npx vitest run <files>` green, commit. NO production-code changes in Phase C unless a test exposes a real defect (if so, note it).

### Task C1: HIGH-priority untested pages
**Create tests:** `AdminContentPage.test.jsx`, `AdminSecurityPage.test.jsx`, `HubDiscoveryTab.test.jsx`, `HubProposalsTab.test.jsx`.
- [ ] **AdminContentPage** — cover: loading, list error, stats render, orphaned-pages list (+empty), broken-links list (+empty), history, flush-cache confirm→`flushCache`, purge-versions confirm→`purgeVersions`, bulk-delete-pages select+confirm (+partial fail). 
- [ ] **AdminSecurityPage** — policy-grant list + group list render, create/edit/delete each via their modals (`PolicyGrantFormModal`/`GroupFormModal`) with confirm, error + empty states for both lists.
- [ ] **HubDiscoveryTab** — run-discovery POST + result render, proposal list (+empty), dismissed panel, accept/dismiss per card, bulk-delete-dismissed, error.
- [ ] **HubProposalsTab** — generate, list (+empty), per-row + bulk approve/reject, threshold-approve, sync, error.
- [ ] Run the four files → PASS. **Commit** `test(admin): cover AdminContentPage, AdminSecurityPage, HubDiscoveryTab, HubProposalsTab`.

### Task C2: MED-priority untested components
**Create tests:** `ContentEmbeddingsTab`, `ExistingHubsPanel`, `ExistingHubDrilldown`, `HubDiscoveryCard`, `UserFormModal`, `GroupFormModal`, `PolicyGrantFormModal`, `MentionChunks`.
- [ ] Each: render + primary interactions + empty/error where applicable (per gap matrix bullets). `ContentEmbeddingsTab` includes polling (fake timers) + backfill trigger. Modals: create vs edit mode, validation, submit, cancel.
- [ ] Run → PASS. **Commit** `test(admin): cover embeddings/hubs panels, form modals, mention chunks`.

### Task C3: LOW + presentational
**Create tests:** `PageLink.test.jsx`, `PageEditLink.test.jsx`; extend `ProvenanceBadge.test.jsx` (each provenance variant).
- [ ] Run → PASS. **Commit** `test(admin): cover PageLink/PageEditLink + provenance variants`.

### Task C4: Harden thin tests
**Extend:** `AdminKnowledgePage.test.jsx` (switch through all 8 tabs → each panel mounts; clear-all flow success+error+clearing state), `MetricCard.test.jsx` (accent/dim/children feed), `AdminSidebar.test.jsx` (active state per route for every link), `AdminLayout.test.jsx` (non-admin → `<Navigate>` redirect), and add loading-state assertions to `AdminUsersPage`/`GraphExplorer`/`NodeDetail` where missing.
- [ ] Run full admin vitest `npx vitest run src/components/admin/` → all PASS. **Commit** `test(admin): tab-switching, redirect, prop-variant, loading-state coverage`.

---

## PHASE D — Selenide admin browser flows

**Idiom:** extend `com.wikantik.its.WithIntegrationTestSetup`; `@BeforeEach` `Selenide.closeWebDriver()` then login via `ViewWikiPage.open("Main").clickOnLogin().performLogin(Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD)`; page objects under `pages/admin/` using `data-testid` locators; seed via `RestSeedHelper`; use `RestSeedHelper.awaitAdminReady` to avoid the admin login race. Run sequentially (NO `-T`), `-fae`; clean stale port-55432 container first: `docker ps -a --filter "publish=55432" -q | xargs -r docker rm -f`.

### Task D1: Overview + navigation ITs
**Create:** `pages/admin/OverviewDashboardAdminPage.java`, `pages/admin/AdminSidebarPage.java`, `its/AdminOverviewIT.java`, `its/AdminNavigationIT.java`.
- [ ] **AdminOverviewIT** — login → open `/admin` → assert `admin-overview` present and ≥1 status `metric-card-*` visible → click a `to=` card (e.g. `metric-card-users`) → assert URL is `/admin/users`.
- [ ] **AdminNavigationIT** — `/admin` → assert `admin-sidebar` visible AND the reader sidebar is NOT (context swap) → click each `admin-nav-*` link → assert route + active class → click `admin-back-to-wiki` → assert back on a `/wiki/` route with the reader sidebar.
- [ ] Build WAR + run module sequentially: `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-selenide-tests -am`. If it cannot run in this env (infra), report DONE_WITH_CONCERNS with compile verification (`mvn test-compile`) and the failure — do not fake a pass.
- [ ] **Commit** `test(admin-e2e): Overview dashboard + sidebar navigation ITs`.

### Task D2: tabs + section smoke + auth redirect
**Create:** `its/AdminKnowledgeTabsIT.java`, `its/AdminSectionSmokeIT.java`, `its/AdminAuthRedirectIT.java` (+ any small page objects).
- [ ] **AdminKnowledgeTabsIT** — `/admin/knowledge-graph` → click each of the 8 tabs → assert each panel's testid visible (smoke per-tab mount). (Add `data-testid` to tab panels if missing — minimal prod change, note it.)
- [ ] **AdminSectionSmokeIT** — load each section route (`/admin/users`,`/admin/security`,`/admin/apikeys`,`/admin/content`,`/admin/retrieval-quality`,`/admin/kg-policy`) → assert the `page-header` title renders and no error banner.
- [ ] **AdminAuthRedirectIT** — anonymous (no login) open `/admin` → assert redirect to `/wiki/Main` (the `AdminLayout` `<Navigate>`).
- [ ] Run the module sequentially as in D1. **Commit** `test(admin-e2e): KG tabs, section smoke, auth redirect ITs`.

---

## Final verification
- [ ] `npx vitest run` (full frontend) green; `npm run build` succeeds.
- [ ] **Full IT reactor** (the gate for prod-code changes): `docker ps -a --filter "publish=55432" -q | xargs -r docker rm -f` then `mvn clean install -Pintegration-tests -fae`. Expect BUILD SUCCESS.
- [ ] Final whole-implementation review subagent; then finishing-a-development-branch (no-op merge — work is on main per repo policy).

## Notes for implementers
- Stage files by exact name; never `git add -A`.
- New `.java` files need the Apache license header (RAT check).
- `mvn test-compile -pl <module>` after any signature change.
- Backend collectors must DEGRADE (throw inside the lambda), never throw out of `doGet`, never fabricate values, never empty-catch.
- Phase C is test-only: do not change production code unless a test reveals a real bug (then call it out, don't paper over it).