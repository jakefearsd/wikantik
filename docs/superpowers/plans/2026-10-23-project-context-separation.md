# Project Context Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A cluster-scoped briefing delivers the project's own pages, then the shared pages the project cites, and nothing from sibling projects as body text — derived from cluster membership and wikilinks, with no new frontmatter or request parameters.

**Architecture:** A per-request `BriefingScope` (wikantik-main, briefing package) computes two ordered tiers from `StructuralIndexService` + `ReferenceManager`: members ranked by in-cluster in-degree, then cited pages ranked by citers. `ItemAssembler` walks the tiers; `DefaultBriefingAssemblyService` partitions a larger bundle candidate pool by tier, caps sections per page, and turns out-of-scope sections into `elsewhere` pointer items; the renderer gains two headings. Content (Phase 0) and protocol (Phase 3) land through the admin MCP surface and the sibling repos' `CLAUDE.md` files.

**Tech Stack:** Java 25, JUnit 5 + Mockito, Maven; Python 3 stdlib for the eval runner; Wikantik admin MCP (`write_pages`, `update_page`) for prod content.

**Spec:** `docs/superpowers/specs/2026-10-23-project-context-separation-design.md`

## Global Constraints

- Cluster matching is `ClusterPath` segment-aware; `startsWith` on a cluster path is a defect. `StructuralIndexService.getCluster(name).articles()` is already transitive (`StructuralProjection`), so tier 1 needs no extra walk.
- Every new path degrades to today's output **with a warning**, never an exception: `structuralIndex`, `referenceManager` and `bundleService` are all nullable in the assembler.
- `wikantik.*` keys: declare in `wikantik-main/src/main/resources/ini/wikantik.properties` (section `# [Context bundle & briefing]`, with description, `# Type:` and explicit default) **before** reading them, and read them as full string literals (`"wikantik.briefing.sections_per_page"`), never `PREFIX + "x"` — `ConfigSurfaceDriftTest.KEY_LITERAL` only sees literals. Regenerate docs with `bin/config-reference.sh --write`.
- Defaults from the spec: `wikantik.briefing.sections_per_page = 4`, `wikantik.briefing.candidate_pool = 36`, `wikantik.briefing.scope.cited.enabled = true`, `wikantik.briefing.scope.cited.max = 12`, `wikantik.briefing.elsewhere.max = 8`, `wikantik.briefing.pointers.max = 30`. Existing caps stay: `MAX_CLUSTER_MEMBERS = 200`, `MAX_PINS = 25`, `MAX_CLUSTERS = 10`.
- `BriefingItem.origin` values: `pin`, `cluster`, `cited`, `elsewhere`. `pointerCount` (log + surfaces) counts only `!included` items whose origin is `pin` or `cluster`.
- JSON changes are additive only: new record fields, new headings only when non-empty. `/api/bundle` and `assemble_bundle` wire behaviour is unchanged.
- Schema change ⇒ one numbered idempotent migration, DDL only (`ADD COLUMN IF NOT EXISTS`), no data backfill.
- Prod is authoritative for content. Phase 0 and Phase 3 content changes go through the admin MCP tools, plan-first, applied only after the user's explicit yes in the session. `update_page` always passes `expectedContentHash`.
- Commit messages 1–3 lines, end with the attribution lines from the session reminder; stage files by name.
- Any Maven run that can exceed ~5 minutes goes through `bin/agent-build.sh`. Single-module checks: `mvn -q -pl <module> test -Dtest=<Class>`.

## Review Focus

Inputs the spec implies but no happy-path test exercises; each is pinned to a test in the owning task.

1. A tier-2 cited page the caller may not view — `BriefingAclGate` must drop it (title included) exactly like a member pointer. → Task 6 (`gate_dropsRestrictedCitedAndElsewhereItems`).
2. `ReferenceManager.findReferrers` / `findRefersTo` returning `null` or an unknown page name (JSPWiki heritage) — scope computation must treat it as empty, not NPE. → Task 3 (`nullReferrersTreatedAsEmpty`).
3. A page that is both a member of requested cluster A and cited by requested cluster B — it is tier 1 once, never duplicated into tier 2. → Task 3 (`memberOfOneRequestedClusterIsNeverCited`).
4. A cited slug with no `canonical_id` / no structural descriptor (system pages) — skipped from tier 2, no warning spam. → Task 3 (`citedPageWithoutDescriptorIsSkipped`).
5. STRICT mode with clusters but the bundle's whole pool out of scope — zero sections, no `elsewhere` items (STRICT never widens), coverage recounted to 0. → Task 5 (`strictWithNothingInScopeYieldsNoSectionsAndNoElsewhere`).

---

### Task 1: Briefing-scope eval harness (Phase 1, seeded with the eight observed runs)

**Files:**
- Create: `eval/briefing-scope/README.md`
- Create: `eval/briefing-scope/cases.json`
- Create: `eval/briefing-scope/run_eval.py`
- Create: `eval/briefing-scope/test_run_eval.py`
- Create: `eval/briefing-scope/BASELINE.md` (output of the first run)

**Interfaces:**
- Consumes: `GET {base}/api/briefing?clusters=&prompt=&scope_mode=&format=json` → `ContextBriefing` JSON (`sections[].slug`, `items[].{slug,origin,included}`, `warnings[]`, `usedTokens`).
- Produces: `python3 eval/briefing-scope/run_eval.py --base-url URL [--case ID] [--write-baseline PATH]`; exit 0 when every case passes, 1 otherwise.

Case schema (one object per case):
```json
{ "id": "roller-telemetry-prefer",
  "clusters": ["roller"], "prompt": "add Prometheus metrics endpoint and ECS logging to Roller", "scope_mode": "prefer",
  "must_include_as_section": [], "must_include_as_item": ["RollerHub", "SimpleAgilityTelemetryContract"],
  "must_not_include_as_body": ["StockroomPhase7PhotoIdentificationPlan"],
  "may_point_to": ["SimpleAgilityTelemetryAdoption"], "max_sections_per_page": 4 }
```
`must_not_include_as_body` fails on a matching `sections[].slug` or an `items[]` entry with `included == true`; an `elsewhere`/pointer item never fails it. The runner fetches `GET {base}/api/structure/sitemap` once and derives each requested cluster's member slugs from it. Two scores per case: **separation** = estimated tokens (chars ÷ 4) of sections and included items whose slug is a requested-cluster member or has an `items[]` entry with `origin == "cited"`, ÷ the same estimate over all sections and included items; **diversity** = distinct `sections[].slug` ÷ `sections.length`.

Seed cases (ids): `sa-roller-telemetry` (row 1: must_not body `StockroomPhase7PhotoIdentificationPlan`; must item `SimpleAgilityStackHub`), `roller-telemetry-prefer` (above), `roller-telemetry-strict` (must item `SimpleAgilityTelemetryContract`; `max_sections_per_page: 4`), `jakemon-push-feed` (must_not body any slug starting `Incident`; must item `SimpleAgilityObservabilityPlane`), `stockroom-itemsold` (must item `StockroomHub`; no `unknown cluster` warning), `nocluster-lease-guard` (`max_sections_per_page: 4` only), `wikidev-trace-id` (must_not body `RollerTelemetryHandoffSpec`; may_point_to it; must_not body `LoginHelp`), `roller-unknown-arg` (`extra_params: {"scope": "strict"}` → warning matching `^unknown argument: scope`).

- [ ] **Step 1: Write `test_run_eval.py`** — pure-function tests over a canned JSON response: `test_body_violation_detected_for_included_item`, `test_pointer_does_not_violate_body_rule`, `test_separation_score_counts_only_in_scope_tokens`, `test_diversity_score`, `test_per_page_cap_violation`, `test_unknown_argument_warning_match`.
- [ ] **Step 2: Run** `cd eval/briefing-scope && python3 -I -m pytest -q test_run_eval.py` → FAIL (`run_eval` not importable).
- [ ] **Step 3: Implement `run_eval.py`** — stdlib only (`urllib`, `json`, `argparse`); functions `evaluate(case: dict, briefing: dict) -> CaseResult` and `main(argv) -> int`; `--write-baseline` renders a markdown table (case, pass/fail, separation, diversity, violations) and the raw per-case counts. Model the file layout and docstring style on `eval/agent-grounding/run_eval.py`.
- [ ] **Step 4: Run** the pytest again → PASS.
- [ ] **Step 5: Write `cases.json`** with the eight seed cases exactly as listed, and `README.md` (what it measures, how to run, that it is manual like `eval/agent-grounding`).
- [ ] **Step 6: Run against prod (read-only):** `python3 -I eval/briefing-scope/run_eval.py --base-url https://wiki.wikantik.com --write-baseline eval/briefing-scope/BASELINE.md`. Expected: most cases FAIL (that is the pre-change baseline); the table lands in `BASELINE.md` under a heading `## Before Phase 0 (2026-10-23)`.
- [ ] **Step 7: Commit** `eval/briefing-scope/*` — `eval: briefing-scope harness seeded with the eight 2026-10-23 runs`.

---

### Task 2: Phase 0 — Stockroom hub and cluster on prod (content, gated)

**Files:** none in the repo except `eval/briefing-scope/BASELINE.md` (append). All writes are prod pages via `mcp__wikantik-admin__*`.

**Interfaces:**
- Consumes: `mcp__wikantik-knowledge__list_pages_by_filter(cluster="simple-agility")`, `mcp__wikantik-admin__read_page`, `write_pages`, `update_page(expectedContentHash)`.
- Produces: cluster `stockroom` declared by `StockroomHub`; the 11 `Stockroom*` pages carry `cluster: stockroom`; `simple-agility` retains its 11 cross-project pages.

- [ ] **Step 1: Derive the plan from the live index.** List `cluster=simple-agility`; the move set is every page whose slug starts `Stockroom` (expected 11: `StockroomIntakeInventoryPricingDesign`, `StockroomPhase1FoundationPlan` … `StockroomPhase7PhotoIdentificationPlan`, `StockroomBarcodePhysicalTrackingDesign`, `StockroomChannelsAndSalesDesign`, `StockroomPhotoIdentificationDesign`). Read `SimpleAgilityStackHub`.
- [ ] **Step 2: Draft `StockroomHub`** (do not write yet). Frontmatter: `type: hub`, `cluster: stockroom`, `status: active`, `audience: [humans, agents]`, `tags: [stockroom, reselling, inventory, simple-agility, hub]`, `summary` 50–160 chars, `related:` = the 11 pages + `SimpleAgilityStackHub`, `SimpleAgilityCommerceDataFlow`, `ResellingHub`, `RollerHub`. Body: the "Part of the [Simple Agility stack](SimpleAgilityStackHub)" callout in the first line (same form as `RollerHub`), a one-paragraph role statement taken from the Stack Hub components row, a "Designs" list and a "Phase plans" list (one line each, from the pages' summaries), and a "Contracts this project is bound by" list linking `SimpleAgilityCommerceDataFlow`, `SimpleAgilityTelemetryContract`, `SimpleAgilityDecisionLog` (SA-8, SA-10, SA-11, SA-12), `SimpleAgilityAgentCoordination`. No `[{ALLOW}]` directives.
- [ ] **Step 3: Draft the two `SimpleAgilityStackHub` edits:** (a) components row for Stockroom: replace "Design & contracts: [ResellingHub]…" tail with "Hub: [StockroomHub](StockroomHub); contracts: [SimpleAgilityCommerceDataFlow]…"; (b) the sentence "and for Stockroom its design page until it has a hub" → "and [StockroomHub](StockroomHub)"; (c) `related:` add `StockroomHub`, remove `StockroomIntakeInventoryPricingDesign`.
- [ ] **Step 4: Present the plan to the user** — the 11 slugs, the hub draft, the three Stack Hub edits — and **stop until they say yes**.
- [ ] **Step 5: Apply:** `write_pages` for `StockroomHub`; `update_page` ×11 changing only `cluster` (metadata argument, `expectedContentHash` from a fresh `read_page`); `update_page` on `SimpleAgilityStackHub`. On any hash mismatch re-read and retry once.
- [ ] **Step 6: Verify** `mcp__wikantik-knowledge__list_clusters` shows `stockroom` with `articleCount: 11` and a hub; `list_pages_by_filter(cluster="simple-agility")` returns 11 pages, none `Stockroom*`; `get_briefing(clusters=["simple-agility"])` standing context contains no Stockroom page.
- [ ] **Step 7: Re-run the harness** with `--write-baseline` appending `## After Phase 0 (2026-10-23)` to `BASELINE.md`. Expected: `sa-roller-telemetry` and `stockroom-itemsold` pass; the rest still fail.
- [ ] **Step 8: Commit** `eval/briefing-scope/BASELINE.md` — `eval: baseline after Phase 0 (Stockroom cluster)`.

---

### Task 3: `BriefingScope` — tiers from the Page Graph

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/BriefingScope.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/briefing/BriefingScopeTest.java`

**Interfaces:**
- Consumes: `StructuralIndexService.getCluster(String)`, `.resolveCanonicalIdFromSlug(String)`, `.getByCanonicalId(String)`; `com.wikantik.api.managers.ReferenceManager.findRefersTo(String)` (outbound) and `.findReferrers(String)` (inbound) — both may return `null`.
- Produces:
```java
final class BriefingScope {
    /** Tier-2 entry: a page outside the requested clusters that a member links to. */
    record Cited( PageDescriptor page, int citers, int reach ) {}
    static final BriefingScope NONE;                       // no clusters requested, or no index: tierOf() == 0 for every slug, members()/cited() empty, isNone() true
    static BriefingScope compute( List<String> clusters, StructuralIndexService index, ReferenceManager refs,
                                  boolean citedEnabled, int citedMax, List<String> warnings, Set<String> warnedClusters );
    List<PageDescriptor> members();   // tier 1, ordered: hub(s) first, then in-degree desc, hasSummary desc, updated desc (nulls last)
    List<Cited> cited();              // tier 2, ordered: citers desc, reach desc, updated desc; size <= citedMax
    int tierOf( String slug );        // 1, 2, or 0 (out of scope)
    boolean isNone();
}
```
  Definitions (spec §Vocabulary): *in-degree* = members of the requested clusters linking to the member; *citers* = members linking to the cited page; *reach* = distinct clusters (any `clusters()` entry of any referrer's descriptor, plus the page's own) across all referrers. A slug whose descriptor cannot be resolved is skipped (debug log). `index == null` with clusters requested → `NONE` + the existing warning `structural index unavailable; clusters skipped` (moved here from `ItemAssembler`). `refs == null` → `cited()` empty + warning `reference data unavailable; cited pages skipped`. Unknown cluster → existing `unknown cluster: <name>` warning once (via `warnedClusters`). `citedEnabled == false` → `cited()` empty, no warning.

- [ ] **Step 1: Write `BriefingScopeTest`.** Fixture: the eight-page corpus from the spec's "trivial example" (`StackHub`, `TelemetryContract`, `CommerceDataFlow` in `simple-agility`; `RollerHub`, `RollerMcp`, `RollerTelemetrySpec` in `roller`; `StockroomHub`, `Phase7Plan` in `stockroom`) built with the `StubIndex`/`desc()` helpers copied from `DefaultBriefingAssemblyServiceTest` (extract both into a package-private `BriefingTestFixtures` class in the test tree so Tasks 4–5 reuse them) and a Mockito `ReferenceManager` whose `findRefersTo`/`findReferrers` return the spec's edges. Tests:
  - `rollerMembersOrderedHubThenInDegreeThenUpdated` → `[RollerHub, RollerMcp, RollerTelemetrySpec]`
  - `rollerCitedOrderedByCitersThenReach` → `[TelemetryContract(citers 2, reach 3), StackHub(citers 1, reach 3)]`
  - `stockroomPhase7NeverEntersRollerScope` → `tierOf("Phase7Plan") == 0`
  - `simpleAgilityCitesProjectHubsNotPlans` → cited slugs `{RollerHub, StockroomHub}`, `tierOf("Phase7Plan") == 0`
  - `memberOfOneRequestedClusterIsNeverCited` (Review Focus 3): request `[roller, simple-agility]` → `tierOf("TelemetryContract") == 1`, not in `cited()`
  - `nullReferrersTreatedAsEmpty` (Review Focus 2): `findReferrers` returns `null` for one slug → no exception, in-degree 0
  - `citedPageWithoutDescriptorIsSkipped` (Review Focus 4): a member links to `SandBox`, index has no canonical id for it → absent from `cited()`, `warnings` unchanged
  - `citedCapKeepsTopByCiters` → `citedMax = 1` keeps `TelemetryContract` only
  - `nullReferenceManagerWarnsAndYieldsNoCited`
  - `memberWithoutSummaryOrInboundSortsLast`
  - `noClustersReturnsNone` → `isNone()`, `tierOf(anything) == 0`
- [ ] **Step 2: Run** `mvn -q -pl wikantik-main test -Dtest=BriefingScopeTest` → FAIL (class missing).
- [ ] **Step 3: Implement `BriefingScope`** per the interface. Resolve descriptors slug→`resolveCanonicalIdFromSlug`→`getByCanonicalId`, memoised in a per-compute `Map<String, Optional<PageDescriptor>>`. Only `members()`' outbound links are followed (one `findRefersTo` per member); `findReferrers` is called once per member (in-degree) and once per cited candidate (citers/reach).
- [ ] **Step 4: Run** the test → PASS.
- [ ] **Step 5: Commit** — `feat(briefing): BriefingScope derives member and cited tiers from the Page Graph`.

---

### Task 4: `ItemAssembler` walks the tiers; `BriefingItem` gains `cluster`

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/briefing/BriefingItem.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/ItemAssembler.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/DefaultBriefingAssemblyService.java` (constructor + `Assembly` wiring only; partition logic is Task 5)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/briefing/DefaultBriefingAssemblyServiceTest.java`

**Interfaces:**
- Consumes: `BriefingScope` (Task 3).
- Produces:
  - `BriefingItem( String slug, String canonicalId, String title, String summary, String origin, boolean included, String content, String cluster )` plus the existing 7-arg constructor delegating with `cluster = null`. Javadoc lists the four origins.
  - `DefaultBriefingAssemblyService( BundleAssemblyService, StructuralIndexService, ReferenceManager, PageManager, BriefingLimits )` with `record BriefingLimits( int defaultBudget, int maxBudget, int sectionsPerPage, int candidatePool, boolean citedEnabled, int citedMax, int elsewhereMax, int pointersMax )` (nested in `BriefingConfig`, with `BriefingLimits.defaults()`), and the existing 5-arg constructor kept, delegating with `refs = null` and defaults.
  - `ItemAssembler( Logger, PageManager, StructuralIndexService, SectionBudget, List<String> cappedPins, BriefingScope scope, int pointersMax, List<String> warnings )`; `assembleClusterMembers()` now walks `scope.members()` (origin `cluster`) then `scope.cited()` (origin `cited`, `cluster` = `page.cluster()`), full-body-or-pointer as today; pointers (any origin) stop being emitted once `pointersMax` pointers exist, with one warning `pointer list capped at N`.

- [ ] **Step 1: Update tests.** In `DefaultBriefingAssemblyServiceTest` replace `clusterMembersHubFirstThenByUpdatedDesc` with `clusterMembersHubFirstThenByInDegree` (fixture from Task 3; assert item order `[RollerHub, RollerMcp, RollerTelemetrySpec]` then cited `[TelemetryContract, StackHub]` with `origin == "cited"` and `cluster == "simple-agility"`). Add `citedPageDegradesToPointerUnderBudget`, `pointerListCappedWithWarning` (12 members, `pointersMax = 5`, budget 200 → exactly 5 `!included` items + warning), `legacyConstructorStillWorksWithoutReferenceManager` (5-arg ctor → no `cited` items, warning `reference data unavailable…` only when clusters requested).
- [ ] **Step 2: Run** `mvn -q -pl wikantik-api install -DskipTests && mvn -q -pl wikantik-main test -Dtest=DefaultBriefingAssemblyServiceTest` → FAIL (compile: new ctor/field).
- [ ] **Step 3: Implement** the `BriefingItem` field + ctor, `BriefingLimits`, the new `DefaultBriefingAssemblyService` constructor, and rewrite `ItemAssembler.assembleClusterMembers` / remove `clusterMembers(name)` (ordering now lives in `BriefingScope`). `Assembly` builds the scope once: `BriefingScope.compute( cappedClusters, structuralIndex, refs, limits.citedEnabled(), limits.citedMax(), warnings, warnedClusters )`.
- [ ] **Step 4: Run** the whole briefing test package: `mvn -q -pl wikantik-main test -Dtest='com.wikantik.knowledge.briefing.*Test'` → PASS.
- [ ] **Step 5: Commit** — `feat(briefing): items follow BriefingScope tiers; cited origin; pointer cap`.

---

### Task 5: Tiered section partition, candidate pool, per-page cap, `elsewhere` items

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/bundle/BundleAssemblyService.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/bundle/BundleOptions.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java:175-197`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/DefaultBriefingAssemblyService.java` (`assembleSections`, `computeScope`, `partitionByScope`)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceTest.java` (existing; add one test)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/briefing/DefaultBriefingAssemblyServiceTest.java`

**Interfaces:**
- Produces: `record BundleOptions( RetrievalMode mode, Integer maxSections )` with `static BundleOptions defaults()`; `BundleAssemblyService.assemble( String query, BundleOptions options )` as a `default` method delegating to `assemble(query, mode)` and ignoring `maxSections`; `DefaultBundleAssemblyService` overrides it so a non-null `maxSections` replaces `this.maxSections` in the `cut` computation (knee still applies). `assemble(query)` / `assemble(query, mode)` behaviour byte-identical.
- Briefing: `assembleSections()` calls `bundleService.assemble( prompt, new BundleOptions( null, limits.candidatePool() ) )`; partitions the pool into tier 1 / tier 2 / out by `scope.tierOf(slug)` (stable within tier); STRICT keeps tiers 1–2, PREFER keeps tiers 1–2 as sections and turns **out** into `elsewhere` items (`BriefingItem( slug, canonicalId, slug, String.join(" › ", headingPath), "elsewhere", false, null, clusterOf(slug) )`, at most `elsewhereMax`, de-duplicated by slug); when `scope.isNone()` every section is in scope. Per-page cap `sectionsPerPage` applied in tier order before the token-budget loop. `ledger.coverage = BundleCoverage.recount( bundleCoverage, kept )` unchanged.

- [ ] **Step 1: Bundle test.** Add `assembleWithOptionsMaxSectionsOverridesCap` to `DefaultBundleAssemblyServiceTest`: a source yielding 30 candidates, service built with `maxSections = 12`, `assemble(q, new BundleOptions(null, 36))` returns 30 sections while `assemble(q)` returns 12.
- [ ] **Step 2: Run** `mvn -q -pl wikantik-main test -Dtest=DefaultBundleAssemblyServiceTest` → FAIL (no such method).
- [ ] **Step 3: Implement** `BundleOptions`, the default interface method, the override. Install `wikantik-api` (`mvn -q -pl wikantik-api install -DskipTests`).
- [ ] **Step 4: Run** Step 2 again → PASS. Commit — `feat(bundle): additive assemble(query, BundleOptions) with maxSections override`.
- [ ] **Step 5: Briefing tests.** Replace `preferScopeReordersInScopeFirst` with `preferScopeKeepsTiersAsSectionsAndOutAsElsewhere` (pool: 2 tier-1, 1 tier-2, 2 out-of-scope sections → `sections` = tier-1 then tier-2 slugs; 2 `elsewhere` items with `included == false`, `cluster` set, `origin == "elsewhere"`); keep `strictScopeDropsOutOfClusterSections` but assert a tier-2 section is **kept**; add `strictWithNothingInScopeYieldsNoSectionsAndNoElsewhere` (Review Focus 5); `perPageCapPrefersTier1SectionsOfAPage` (one page with 3 tier-1 + 3 tier-2 sections, cap 4 → its 3 tier-1 + 1 tier-2 kept, next page's sections follow); `candidatePoolRequestedFromBundle` (Mockito `BundleAssemblyService` verifying `assemble(eq(prompt), argThat(o -> o.maxSections() == 36))`); `elsewhereCappedAtMax`; `noClustersAppliesCapOnly` (row 6: 12 sections from one page, cap 4 → 4 sections, no `elsewhere`).
- [ ] **Step 6: Run** `-Dtest=DefaultBriefingAssemblyServiceTest` → FAIL.
- [ ] **Step 7: Implement** in `DefaultBriefingAssemblyService.Assembly`: replace `computeScope`/`partitionByScope` with `partition( List<BundleSection> pool )` returning tiered lists, `capPerPage( List<BundleSection>, int )`, and `addElsewhere( List<BundleSection> out )`. `run()` order becomes: `assembleSections()` (which stashes the out-of-scope list), `itemAssembler.assemblePins()`, `itemAssembler.assembleClusterMembers()`, then `addElsewhere(...)` appending to the already package-visible `itemAssembler.items` — so items are ordered pins, members, cited, elsewhere.
- [ ] **Step 8: Run** the briefing test package → PASS.
- [ ] **Step 9: Commit** — `feat(briefing): tiered partition, candidate pool, per-page cap, elsewhere pointers`.

---

### Task 6: Renderer headings and ACL gate coverage

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/MarkdownBriefingRenderer.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/briefing/MarkdownBriefingRendererTest.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/briefing/BriefingAclGateTest.java`

**Interfaces:**
- Rendered order: `## Task-relevant sections` → `## Standing context` (included items, origin `pin`/`cluster`) → `## Contracts this project cites` (origin `cited`: body when included, pointer line when not) → `## Elsewhere in the wiki` (origin `elsewhere`, grouped under `**<cluster>**` sub-bullets, line `- <slug> › <headingPath> (\`canonicalId\`)`) → `## Available on request` (`!included`, origin `pin`/`cluster`) → warnings. Every heading omitted when empty.

- [ ] **Step 1: Tests.** In `MarkdownBriefingRendererTest` add `rendersCitedUnderOwnHeading` (included cited → body under the heading; pointer cited → pointer line under the same heading, not under Available on request), `rendersElsewhereGroupedByCluster`, `omitsNewHeadingsWhenEmpty` (a pins-only briefing renders byte-identical to today's expected string). In `BriefingAclGateTest` add `gate_dropsRestrictedCitedAndElsewhereItems` (Review Focus 1).
- [ ] **Step 2: Run** both classes → FAIL.
- [ ] **Step 3: Implement** the renderer changes (filter by origin; `BriefingAclGate` needs no code change — the test documents that).
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** — `feat(briefing): render cited and elsewhere tiers`.

---

### Task 7: Configuration keys, wiring, unknown-argument warnings

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (section `# [Context bundle & briefing]`, next to `wikantik.briefing.log.enabled`)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/BriefingConfig.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/BriefingServiceWiring.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:1742-1746`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetBriefingTool.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/BriefingResource.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/briefing/BriefingServiceWiringTest.java`, `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetBriefingToolTest.java`, `wikantik-rest/src/test/java/com/wikantik/rest/BriefingResourceTest.java`

**Interfaces:**
- `BriefingConfig.limits( Properties )` → `BriefingLimits`, reading the six new keys as literals; also declare the three legacy keys (`wikantik.briefing.enabled`, `.default_budget`, `.max_budget`) in the ini and switch their readers to literals (they are currently invisible to the drift test — `docs/admin/RagContextBundle.md:191` even says so).
- `BriefingServiceWiring.build( BundleAssemblyService, StructuralIndexService, ReferenceManager, PageManager, Properties )`; old 4-arg overload kept, passing `null` refs. `WikiEngine` passes `getManager( ReferenceManager.class )`.
- Unknown arguments: `GetBriefingTool` compares `arguments.keySet()` to its `definition()` property names and appends `unknown argument: <name>` (sorted) to the gated briefing's warnings; `BriefingResource` does the same from `req.getParameterMap().keySet()` against `{pins, clusters, prompt, budget, scope_mode, format}`. Both rebuild `ContextBriefing` with the extra warnings before rendering/serialising.

- [ ] **Step 1: Tests.** `BriefingServiceWiringTest.buildReadsLimitsFromProperties` (set `wikantik.briefing.sections_per_page=2`, cast the result to `DefaultBriefingAssemblyService`, assert `limits().sectionsPerPage() == 2` through a new package-private `BriefingLimits limits()` accessor); `GetBriefingToolTest.execute_unknownArgument_isWarnedNotIgnored` (`Map.of("clusters", List.of("roller"), "scope", "strict")` → markdown contains `unknown argument: scope`); `BriefingResourceTest.unknownQueryParam_appearsInWarnings`.
- [ ] **Step 2: Run** the three classes → FAIL.
- [ ] **Step 3: Declare the nine keys in the ini** (each: one-line description, `# Type: int|boolean`, explicit default) and implement `BriefingConfig.limits`, wiring, `WikiEngine` call site, and the two warning hooks.
- [ ] **Step 4: Run** the three classes → PASS; then `mvn -q -pl wikantik-war test -Dtest=ConfigSurfaceDriftTest` → PASS; then `bin/config-reference.sh --write` and `mvn -q -pl wikantik-war test -Dtest=ConfigReferenceRegressionTest` → PASS.
- [ ] **Step 5: Commit** (ini, Java, generated docs, wiki page `docs/wikantik-pages/WikantikConfigurationReference.md`) — `feat(briefing): scope/diversity config keys; warn on unknown arguments`.

---

### Task 8: `briefing_log` tier columns (Phase 3, data)

**Files:**
- Create: `bin/db/migrations/V064__briefing_log_tiers.sql`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/briefing/BriefingLogEntry.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/querylog/JdbcBriefingLogService.java`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetBriefingTool.java`, `wikantik-rest/src/main/java/com/wikantik/rest/BriefingResource.java` (the two `blog.log(...)` call sites)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/querylog/JdbcBriefingLogServiceTest.java`, plus the two surface tests' log assertions

**Interfaces:**
- Migration: `ALTER TABLE briefing_log ADD COLUMN IF NOT EXISTS cited_count INTEGER NOT NULL DEFAULT 0, ADD COLUMN IF NOT EXISTS elsewhere_count INTEGER NOT NULL DEFAULT 0, ADD COLUMN IF NOT EXISTS scope_mode TEXT;` (no grant change needed — table grant already covers new columns).
- `BriefingLogEntry( …existing nine…, int citedCount, int elsewhereCount, String scopeMode )` with the existing 9-arg constructor delegating `(0, 0, null)`. `pointerCount` semantics: `!included && origin ∈ {pin, cluster}`; `citedCount` = items with origin `cited` (any `included`); `elsewhereCount` = origin `elsewhere`.

- [ ] **Step 1: Tests.** `JdbcBriefingLogServiceTest.log_insertsTwelveFields` (verify `setInt(10,…)`, `setInt(11,…)`, `setString(12,"strict")`); `GetBriefingToolTest.execute_logsCitedAndElsewhereCounts`; `BriefingResourceTest.briefingLog_carriesScopeModeAndTierCounts`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** migration, record, INSERT, two call sites. **Step 4: Run** → PASS; then `bin/db/migrate.sh --status` against the local DB shows V064 pending, `bin/db/migrate.sh` applies it, re-running is a no-op.
- [ ] **Step 5: Commit** — `feat(briefing): log cited/elsewhere counts and scope_mode (V064)`.

---

### Task 9: Repository docs, ADR-0011, changelog

**Files:**
- Create: `docs/adr/0011-project-context-is-derived-from-citations.md`
- Modify: `docs/adr/README.md` (index row), `docs/admin/RagContextBundle.md` (§Session briefings: tiers, the two new headings, the `elsewhere` origin, the unknown-argument warning, the six keys in §Configuration reference), `CHANGELOG.md` (`## [Unreleased]` → `### Added` / `### Changed`), `CLAUDE.md` (`/api/briefing` row: "tiered project scope (members → cited → elsewhere)"), `docs/superpowers/specs/2026-10-23-project-context-separation-design.md` (two amendments below).
- Spec amendments to record (the plan diverges on two details): (1) "JSON gains `tier` on sections" → sections carry no tier field; they are ordered by tier and items carry `origin`/`cluster`; (2) "3× its section budget" → explicit `wikantik.briefing.candidate_pool` (36); `assemble_bundle` gets no unknown-argument warning because `ContextBundle` has no warnings channel.

- [ ] **Step 1: Write ADR-0011** in the style of `0010-one-data-access-primitive.md`: title *Project context is derived from citations, not declared*; statement = the spec's one sentence; context = the eight simulated runs; decision = tiers + the permanent close of cluster nesting for projects (ADR-0009 depth rule); consequences = adoption-by-link rule, drift follow-on.
- [ ] **Step 2: Edit** the docs listed; run `mvn -q -pl wikantik-war test -Dtest=ConfigReferenceRegressionTest` → PASS (no key changes here, sanity only).
- [ ] **Step 3: Commit** — `docs: ADR-0011 and briefing tier documentation`.

---

### Task 10: Protocol — SA-13, coordination header, roadmap, sibling repos (content, gated)

**Files:**
- Prod pages via admin MCP: `SimpleAgilityDecisionLog`, `SimpleAgilityAgentCoordination`, `SimpleAgilityRoadmap`; new page `ProjectContextSeparationDesign` (the spec, published under `wikantik-development`, `type: design`, `related: [WikantikAgentContextGuide, WikantikRagContextBundle, ClusterDeclarationDesign]`).
- Modify: `CLAUDE.md` (this repo), `~/source/roller/CLAUDE.md:11-19`, `~/source/stockroom/CLAUDE.md:14-22`, `~/source/jakemon/CLAUDE.md` (no header today — add the full block near the top).

**Interfaces:**
- SA-13 entry (Decision / Why / Consequences, index row dated the day it is applied): the spec's one sentence plus "project hierarchy is expressed by citations, never by cluster nesting"; normative page `ProjectContextSeparationDesign`.
- Header line added to the standard block in `SimpleAgilityAgentCoordination` §2 and each repo: `- **Session start:** call \`get_briefing(clusters=["<project cluster>"], prompt="<your first task>")\` on the Wikantik knowledge MCP; the briefing carries this project's pages and the contracts it cites.` with the cluster filled per repo (`roller`, `stockroom`, `jakemon`, `wikantik-development`).
- Roadmap: the "Cluster restructuring" bullet under *Open questions* → *Settled by SA-13 (…date…): hierarchy is expressed by citations; nesting is permanently out of scope.*

- [ ] **Step 1: Draft** all four page edits and the four `CLAUDE.md` diffs; **present and stop for the user's yes** (prod writes).
- [ ] **Step 2: Apply** page edits with `update_page(expectedContentHash)` / `write_pages`; apply the `CLAUDE.md` edits in each repo (they are separate git repos — commit each with its own 1-line message; do not push).
- [ ] **Step 3: Verify** `mcp__wikantik-knowledge__get_page_neighbors("ProjectContextSeparationDesign")` shows the hub inbound link; `read_pages(["SimpleAgilityDecisionLog"])` shows SA-13 in the index.
- [ ] **Step 4: Commit** this repo's `CLAUDE.md` — `docs: session-start briefing call in the agent header`.

---

### Task 11: Gate, release, after-baseline

**Files:** `eval/briefing-scope/BASELINE.md` (append).

- [ ] **Step 1: Unit + IT gate:** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll `bin/agent-build.sh status gate` until SUCCESS. On any red: fix in-session (no "pre-existing defect" deferrals).
- [ ] **Step 2: Local smoke:** `mvn clean install -DskipTests -T 1C && bin/redeploy.sh`; `curl -s 'http://localhost:8080/api/briefing?clusters=wikantik-development&prompt=trace.id&format=md' | head -60` shows the `## Contracts this project cites` heading when the local corpus has a citing page, and `?scope=strict` adds `unknown argument: scope` to the warnings line.
- [ ] **Step 3: Ask the user** whether to cut and deploy the release now (outward-facing). If yes: `bin/cut-release.sh` per `docs/wikantik-pages/WikantikReleaseAndDeployProcess.md`, then `bin/deploy-release.sh`; `bin/db/migrate.sh --status` on prod shows V064 applied.
- [ ] **Step 4: After-baseline:** `python3 -I eval/briefing-scope/run_eval.py --base-url https://wiki.wikantik.com --write-baseline eval/briefing-scope/BASELINE.md` appending `## After Phase 2 (<date>)`. Expected: all eight cases PASS; separation and diversity ≥ the Phase 0 row on every case. Any failing case is a defect to fix before closing — not a case to relax.
- [ ] **Step 5: Commit** — `eval: briefing-scope baseline after Phase 2`.
