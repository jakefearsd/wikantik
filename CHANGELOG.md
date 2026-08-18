# Changelog

All notable changes to Wikantik are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **Merged the two CPU-inference pages, which collided case-insensitively.**
  `docs/wikantik-pages/` held both `CPUInference.md` and `CpuInference.md` — the
  only case-collision in the repo. On a case-insensitive filesystem (macOS APFS
  default) git can materialise only one of the pair, so one page silently
  vanished from a Mac clone and corpus-walking tests saw a different file set
  there than on Linux.

  They were two genuinely different pages rather than a stray duplicate —
  distinct `canonical_id`s, "CPU Inference" (cluster `machine-learning/mlops`)
  versus "CPU Inference for Large Language Models" (cluster `agentic-ai`) — but
  heavily overlapping in substance, both covering AVX-512/AMX, quantization,
  llama.cpp and OpenVINO. They are now one page under the surviving
  `CPUInference.md` and its `canonical_id`, keeping the LLM-specific material
  the other page had alone: the memory-bandwidth bottleneck and its quantization
  mitigation, ARM SME, and the serving-architecture comparison. Tags and
  `related` are unioned; the three inbound `CpuInference` links (from
  `OpenSourceLLMs` and `ModelQuantization`) are retargeted.

  **`cluster:` deliberately stays the scalar `machine-learning/mlops` rather
  than becoming a two-cluster list.** Multi-membership would be the natural fit
  for a page spanning both, but Knowledge Graph inclusion is fail-closed — an
  explicit EXCLUDE on *any* membership wins outright — and the policy table is
  default-exclude and lives in the database, so it cannot be checked from the
  repo. Adding `agentic-ai` blind risked silently dropping the merged page out
  of the KG. Worth revisiting deliberately by someone who can read the policy.

  Also dropped a generation artefact from the surviving page: a trailing
  "Summary of Technical implementation added" section that was meta-commentary
  about the edit rather than content.

  **Live wiki brought in line (2026-08-18).** Prod turned out to hold materially
  better versions of *both* pages than the repo mirror — prod is authoritative
  for content and the two corpora had diverged. So the prod merge was redone
  from the prod bodies, not the repo ones: `CPUInference` (~2,500 words, Roofline
  model, TCO analysis, five application domains) absorbed the only material
  `CpuInference` held alone — the memory-bandwidth/token-rate ceiling with its
  hardware table, and the GGUF K-quant formats — and `CpuInference` was deleted
  after its four prod backlinks (`AIInfrastructureHub`, `HomeLabInfrastructureHub`,
  `ModelQuantization`, `OpenSourceLLMs`) were retargeted. Prod had four backlinks
  where the repo had two, and one of those referrers does not exist in the repo
  at all. `kg_include: true` was carried over so the merge does not quietly drop
  the absorbed content out of the Knowledge Graph. The repo copy of the page is
  now synced to the prod result, which also corrects its `cluster` to the prod
  value `machine-learning`.

  Two defects in the retired page are worth recording rather than repeating: its
  body was **truncated** (the intro promised thread pinning and llama.cpp
  optimizations, then stopped mid-section 2), and its `$$` block was **corrupted**
  — the stored bytes held control characters where `\approx` and `\frac` should
  have been. The formula was rewritten cleanly rather than carried across.
  `verify_pages` reports 0 broken links and no metadata/SEO/retrieval warnings,
  and an `assemble_bundle` check ranks the new section second for
  "how many tokens per second can a CPU generate for a quantized 7B LLM".


### Fixed
- **Integration tests failed on macOS because select-all was hard-coded to
  Ctrl+A.** `EditWikiPage.saveText()` clears the editor with a select-all chord
  before typing, but CodeMirror 6 binds select-all to `Mod-a`, and `Mod` resolves
  to Command on macOS. On a Mac the chord selected nothing, the following single
  `DELETE` removed about one character, and the next line —
  `shouldHave( exactText( "" ) )` — failed as an exact-text assertion. It only
  bit when the editor already had content, which is why a first save to a new
  empty page passed and a second save to that now-populated page failed.
  Reported as four failures in `wikantik-it-test-custom-jdbc` (the only module
  that runs the Selenide browser suite), including
  `EditIT.createPageAndTestEditPermissions` and `SearchIT.performSearches`.

  New `PlatformKeys.selectAll()` resolves the modifier once from `os.name`;
  both call sites use it (the other was `StructuredFrontmatterEditorIT`, a plain
  textarea — still ⌘A on macOS). A sweep of the IT tree found no other
  platform-sensitive key usage.

  `saveText` also gained the check whose absence let this surface late and
  confusingly: after typing it now proves the CodeMirror buffer matches what was
  typed, reading it via `innerText` rather than Selenide's text extraction
  (which normalises whitespace and would mask a mismatch). The only prior guard
  was a *contains* check on the preview pane — the file's own comment already
  noted that could not catch appended content.

- **`PreviewClickHoldsStillIT` flaked roughly one run in three, on Linux.**
  Measured over repeated runs: `Clicked block moved 2.7 px` against a `<= 2` px
  tolerance. The comment above the assertion claimed it was "deterministic across
  runs"; it was not. Both tolerances raised to 8 px — about 3x the observed noise
  floor, while a genuine scroll echo (the bug this test exists to catch) moves
  content by hundreds of px, so the gate still cannot miss a real regression.
  The 2.7 px sample is recorded inline so the number is not re-tightened later.

- **Four admin ITs raced the post-login session-principal binding.**
  `AdminAuthFilter` is mapped to `/admin/*` and runs before `SpaRoutingFilter`,
  so it gates the page navigation itself — losing the race 403s the navigation
  and every later selector times out. `RestSeedHelper.awaitAdminReady()` exists
  for exactly this and three sibling ITs already called it;
  `EdgeCurationBrowserIT`, `KnowledgeTabIT`, `HubDiscoveryAdminIT` and
  `HubOverviewAdminIT` now do too. `JDBCPluginIT`'s own comment records why this
  matters beyond a timeout: a lost race renders "requires administrator
  privileges" into the page-render cache, poisoning later tests in the class.

- **Five unconditional `Thread.sleep`-then-assert sites replaced with bounded
  polls** — `RestApiIT.testSearch` / `testBacklinks` /
  `testPageGraphSnapshotRendersSeededWikilinks`, `SelfApiKeyLifecycleIT` and
  `AdminDriftIT`. Each waited on genuinely async work (Lucene indexing, the
  reference manager, cache invalidation, a startup sweep) with one fixed sleep
  and no retry. Budgets are 20-30s with failure messages naming what was awaited
  and the last observed value. `RestApiIT`'s deliberate `@Order` sequence and
  every assertion are unchanged.

  Validation: the IT phase now passes four consecutive runs (380 tests) where it
  previously failed one in three, plus 9,174 unit tests and the complexity gate.


### Changed
- **PMD complexity ratchet burned down: 150 -> 132 baseline entries.** Fifteen
  classes were refactored until PMD stopped flagging them, and their baseline
  lines deleted; three more lines were already stale and simply removed; seven
  further lines had a rule dropped. Every burn-down target was chosen because it
  was the class's *only* violation, so clearing it deletes a whole line rather
  than shrinking one.

  Cleared: `WikiBackgroundThread.run`, `SearchMatcher.matchPageContent`,
  `PropertyReader.propertyExpansion`, `HubSetPlugin.renderCards`,
  `DefaultSpamRateLimiter.checkSinglePageChange` (Cognitive); `NodeSignature
  .stripEdgeNoise`, `FrontmatterParser.parseStrict`, `WikiSecurityEvent`'s two
  name/description switches, `PageListEngine.matchesFilter`, `MathSpanExtractor
  .extract`, `JfrProfilingService.start`, `MarkupParser.compileGlobPattern`,
  `OllamaEmbeddingClient.parseEmbeddings`, `QueryEmbedder.invokeWithTimeout`
  (Cyclomatic); and `VisibilityRow.of` (ExcessiveParameterList, 9 params -> 8 via
  a `SnapshotWindow` record) — which retires one of the four `wikantik-insights`
  lines added earlier the same day, as promised when they were fenced.

  Behaviour-preserving throughout; no test was modified, and the suite is
  unchanged at 9,174 unit + 380 integration + 1,568 frontend tests.

  Two of the seven trims cost nothing at all: `FrontmatterParser` and
  `ScimUserResource` stopped tripping `CognitiveComplexity` as a side effect of
  the de-duplication pass, and the burn-down then cleared `FrontmatterParser`
  outright.

  **Recorded in the file header so they are not re-derived as easy wins:**
  `KgEdgeRepository.upsertEdge` looks like a one-over `CyclomaticComplexity` fix
  but the class also trips a *class-total* cyclomatic of 95 against a threshold
  of 80 under the same rule name, so the method fix cannot clear the line; and
  `DefaultContextRetrievalService`'s dead `similarity` constructor argument only
  takes 11 parameters to 10 against a threshold of 8, clearing nothing while
  touching a DI composition root.


### Fixed
- **The weekly `unit-tests` CI job is green again.** It had been failing before
  the 2.4.11 work as well (the 2026-08-17 scheduled run was already red) and went
  unnoticed because the job is `if: github.event_name != 'push'` — hosted-minute
  thrift means a normal push never runs the Java suite. Two test-isolation
  defects, no production code involved, so the 2.4.11 artifact is unaffected.

  `LuceneSearchProviderTest.testSearchMetricsIncrementOnHit` sampled the
  zero-result counter *before* an Awaitility wait that itself issues searches
  against a cold index. Each failed poll legitimately bumps the counter the test
  then asserts is unchanged, so the test measured its own polling: one poll on a
  fast machine (passes), four on a slower CI runner (`expected: <0> but was: <4>`).
  The counters are now sampled after the index is warm, around a single query.

  `DefaultPageManagerTest.testLatestGet{,2,3,4}` raced the ontology subsystem's
  startup self-heal (`OntologyRebuildCoordinator#rebuildIfEmpty`), whose
  `wikantik-ontology-rebuild` daemon walks pages through `VerySimpleProvider` and
  overwrites `m_latestReq` between the call under test and the assertion. These
  tests assert which page the provider was asked for, so they now disable the
  ontology subsystem outright rather than racing it — which also drops the class
  from ~35s to ~2s.


## [2.4.11] - 2026-08-18

### Fixed
- **The CI quality gates are green again.** Two independent failures had been
  red on every run of `quality-gates.yml` for at least four consecutive pushes.

  *Complexity ratchet:* `wikantik-insights` carried 11 PMD violations and had no
  entry in the burn-down baseline at all — the module postdates the 2026-07-03
  baseline generation and was never fenced, so the gate failed the moment the
  module gained enough logic to trip a rule. Four baseline lines now fence that
  inherited debt (`ImportedOpportunityParser`, `JdbcInsightsStore`,
  `OpportunityEngine`, `VisibilityRow`). This is **new debt being fenced, not
  burned down**, which the baseline's own rules require to be justified: 9 of
  the 11 are structural (`OpportunityEngine` is a God Class at WMC=88,
  `JdbcInsightsStore` has a class-total cyclomatic complexity of 118), and
  decomposing them is real work rather than a mechanical fix. The two cheap ones
  — `VisibilityRow.of`'s 9-parameter list and `ImportedOpportunityParser.parseRow`
  — are the first candidates to come back out.

  *OSV scan:* five known vulnerabilities, all in unmanaged transitive Maven
  dependencies (the npm side was clean). `httpcore5` 5.2.5
  (GHSA-hf6x-8p5f-cgmf, 7.5 HIGH) and `httpcore5-h2` 5.2.4
  (GHSA-v3jc-474w-2wm6, 7.5 HIGH) are pinned to 5.4.3; `httpclient5` 5.3.1/5.6.2
  (GHSA-hjcp-jmpx-g3qm) to 5.6.4; `jsoup` 1.15.4 (GHSA-pmhh-3w7g-xqp8) to
  1.23.1, the version `wikantik-connectors` already declared directly. All five
  are `dependencyManagement` pins in the root POM following the existing `sec.*`
  convention — no code change, and no collision with the two standing holds
  (Apache parent 39, libthrift 0.23).

- **`wikantik-insights` was missing from the aggregate coverage report.** The
  module was never added to `wikantik-coverage-report`'s dependency list, so its
  25 main classes were invisible in every coverage figure the project reported.

### Changed
- **De-duplication pass across seven modules — 991 lines removed, 538 added.**
  Every item was identified by PMD CPD and is a behavior-preserving extraction;
  no test was modified. The largest was a 104-line block duplicated between
  `KgJdbcSupport` and `SpineJdbcSupport`, which was *deliberate* — the Page Graph
  copy existed to avoid a knowledge/pagegraph coupling. That rationale no longer
  holds: both classes live in the same Maven module and no ArchUnit rule enforces
  the boundary, so the shared Template Method core moved to a neutral
  `com.wikantik.jdbc.JdbcSupport` and `SpineJdbcSupport` was deleted outright.

  Also extracted: `RestJson` (killing the 8 methods whose own Javadoc read
  "Verbatim copy of `RestServletBase#…`"), `UserProfileMapping`, `ScimIo`,
  `ScimUserFields`, `WebConnectorSupport` (a 3-way duplicate, not the 2-way one
  CPD reported), and `SlugPatternCheck`. `FrontmatterParser.parse()` and
  `parseStrict()` now share one delimiter-splitting routine and differ only in
  error policy, and `Release`'s two version comparators share one walk.

  Three CPD findings were deliberately **not** actioned: the MCP tool-builder
  boilerplate (an idiom repeated across 18 files, not a two-file defect), CLI
  argument parsing (a fix requires threading a loop index across files), and two
  hot-path blocks where a holder object would touch more lines than the
  duplicate. One duplicate turned out to be two *different* operations wearing
  the same shape — `KgMaterializationService` deletes a rejection where the judge
  path inserts one — and was left alone.


## [2.4.10] - 2026-08-17

### Added
- **The expected-CTR curve is now imported from jakemon rather than invented.**
  `ENGINE_DIVERGENCE` priority is `weak_impressions × (ctr(strong_pos) −
  ctr(weak_pos))`, so those findings were being priced with a placeholder table
  while jakemon's measured curve arrived in the ingest payload and was silently
  discarded. `V057` stores the shipped 1–10 table and
  `ExpectedCtrCurve.fromTable` mirrors jakemon's lookup exactly — clamp-to-1
  rounding, table hit, page-2 floor, beyond-20 decay.

  Falls back to the built-in curve when no shipment exists or the latest has
  gone stale, and both read surfaces (`list_content_opportunities` and
  `GET /admin/insights/backlog`) now report `ctrCurveSource` —
  `imported:<date>` or `builtin` — because a shipment going quiet must be
  visible rather than merely inferable from the numbers looking different.

  **One deliberate coupling:** jakemon ships the table but not `_DEEP_CTR`
  (0.008) or the beyond-20 decay, so those two scalars are mirrored here as
  configuration (`wikantik.insights.ctr.deep`, `…deep_max_position`). A parity
  test names the exact expected values, so a future divergence fails a test
  rather than quietly mis-pricing opportunities.

## [2.4.9] - 2026-08-17

### Fixed
- **Admin-area grants are now creatable through the policy API.** Adding the
  permission type to the model was not sufficient: the REST layer validates
  `permissionType` and `actions` against two *separate* allowlists, and `admin`
  was missing from the second — so the grant could not be created at all, while
  every unit test of the permission class itself still passed. The first attempt
  against a deployed instance returned `Invalid action 'access' for permission
  type 'admin'. Valid actions: []`.

### Added
- **Integration coverage for scoped admin grants** (`ScopedAdminGrantIT`): four
  accounts across the full assertion matrix. The cross-checks carry the proof — a
  scoped account returning 200 on its own area demonstrates nothing, since a full
  administrator passes that too, so each scoped account asserts **403 on the
  other's area**. Also asserts the audit behaviour in both directions: an allowed
  scoped request must not log `access.denied`, and a genuine denial still must.
  Verified non-vacuous by mutation.

## [2.4.8] - 2026-08-17

### Added
- **Scoped `/admin/*` grants.** The admin surface spans 26 functional areas
  behind a single `AllPermission` check, so any credential able to reach one
  could reach all of them — including `connector-credentials` (the encrypted
  GitHub / Confluence / Drive secret store), `apikeys` (mint credentials), and
  `policy` (rewrite the permission model itself). An integration that only posts
  visibility rows should not hold that.

  New `AdminPermission` (`permission_type='admin'`, target = the area, e.g.
  `insights`) gives `AdminAuthFilter` a second, narrower way to pass. **Strictly
  additive:** `AllPermission` implies every `AdminPermission`, so no existing
  administrator's reach changes. The area is *derived* from the first path
  segment rather than read from a lookup table, so there is no map to drift out
  of sync — a newly added endpoint gets its own area, nobody holds a grant for
  it, and it keeps requiring `AllPermission` exactly as before. Grants are
  creatable at `/admin/security`, carrying the same broad-role guard
  `AllPermission` has, so `admin:*` cannot be given to `Authenticated`.

  Two failure modes are guarded explicitly, because either would have been
  silent. `AllPermission.implies()` returns false for any type absent from
  `PermissionChecks.isJSPWikiPermission`, so omitting the new type there locks
  **every** administrator out of the **entire** admin surface. And
  `checkPermission` is the audited call, so testing `AllPermission` first would
  have emitted `access.denied` for requests that then *succeeded* via a scoped
  grant — false denials for allowed traffic. The scoped check therefore runs
  first, through the silent `isPermitted` twin, leaving the audit path unchanged.

## [2.4.7] - 2026-08-17

### Added
- **The ingest parser now reads the `query_page` cross product.** It previously
  read only `by_page` and `by_query` — two *disjoint* projections, neither of
  which attributes a query to a page, so nothing in the fact store could say
  which queries a given page ranks for. Such rows need no schema change (the
  primary key is already `(snapshot_date, engine, site_host, page_path,
  query_text)`, so a row with both slots populated sits alongside the two
  rollups rather than colliding with them), but without the parser reading the
  array the upstream collector change would have landed with nowhere to go.

  Unblocks, once the collector ships those rows: the shared-query restriction on
  `ENGINE_DIVERGENCE`, case (a) of `VOCABULARY_GAP`, and effect measurement's
  `query_intersection` mode — which until then degrades to `page_rollup` and
  records per verdict that it did.

## [2.4.6] - 2026-08-17

### Added
- **Content Intelligence Phase 2 — the loop closes.** The opportunity engine
  shipped in 2.4.5 was a library with no consumer: nothing in production
  constructed it. It is now wired end to end. `ContentOpportunityService`
  assembles demand, visibility, page facts, snoozes and cooldowns, runs the rule
  engine, and is reachable from two new admin MCP tools
  (`list_content_opportunities`, `snooze_opportunity` — the admin surface moves
  27 → 29), `GET /admin/insights/backlog`, and a Backlog panel on
  `/admin/insights`.
- **Effect measurement** (`EffectEvaluator` + a nightly scheduler): recorded
  content changes get an `improved` / `no_effect` / `regressed` /
  `insufficient_data` verdict 28 days on, with a difference-in-differences
  adjustment against the site's own trend.
- **Self-calibration** (`WeightCalibrator`): once a rule type has enough
  evaluated outcomes, its priority weight moves toward the observed
  predicted-vs-realised ratio, damped and clamped. Eligibility counts usable
  calibration *samples*, not evaluated changes — `insufficient_data` verdicts
  carry no click delta and would otherwise let a type cross the threshold on
  rows that teach it nothing.
- Imported-opportunity ingestion (`V056`): the ingest endpoint accepts jakemon's
  five detector opportunities and the engine ranks them alongside the native
  four. Inert until the collector ships them.
- `V054` opportunity provenance ledger and `V055` calibration columns.
- All of the design's configuration keys are now real (`wikantik.insights.*`),
  documented in `ini/wikantik.properties`.

### Changed
- **The three volume-driven rules now sit behind a site traffic gate**
  (`wikantik.insights.rules.gate.impressions28d`, default 5000). Measured against
  production: 479 pages share 2,626 impressions and 10 clicks per 28 days, median
  page 2 impressions, so the specified thresholds selected 0–1 pages and a page
  reported at "position 2.0" had been seen once. Below the gate those rules do
  not run and are reported as `suppressed` with measured-vs-required values, so
  an empty backlog is distinguishable from an untested one. `AGENT_GAP` is never
  gated — its denominator is retrieval traffic.
- `ENGINE_DIVERGENCE` moved onto page-rollup rows and now requires a support
  floor on **both** engines. It previously required a row carrying both a
  non-blank page path and a non-blank query, which no row in the store satisfies,
  so the rule matched zero rows and was silently dead. Its priority is expected-CTR
  uplift rather than clicks, which at 10 clicks sitewide scored every finding zero.

### Fixed
- **The MCP retrieval surfaces recorded no bundle coverage.** `assemble_bundle`
  and `get_briefing` called the four-argument query-log overload while their REST
  twins passed coverage, so every agent retrieval stored `coverage` NULL. The
  `AGENT_GAP` rule triggers on `coverage ∈ {weak, unknown}`, and 37 of 52 logged
  retrievals in a 28-day production window came through that path — the one rule
  with a live denominator was running on half its designed input.

## [2.4.5] - 2026-08-16

## [2.4.4] - 2026-08-16

## [2.4.3] - 2026-08-16

### Added
- **npm supply-chain hardening.** The shipped WAR now builds the frontend with
  `npm ci --ignore-scripts` rather than `npm install`, so the bundle is installed
  from `package-lock.json` exactly. `npm install` re-resolved every caret range at
  build time across 406 transitive packages, meaning a malicious patch release
  published after the last lockfile update would be pulled silently into the
  production bundle — and CI already used `npm ci`, so the tree that was *tested*
  was not guaranteed to be the tree that *shipped*. The build step is now
  `npm run build` instead of `npx vite build`, because `npx` silently downloads
  from the registry when it cannot resolve a package locally.
- `wikantik-frontend/.npmrc`: `ignore-scripts` (the install-time RCE vector; only
  `fsevents` declares one here, dev/macOS-only), an explicit `registry=` pin so a
  stray environment variable cannot redirect resolution, and `audit-level=high`.
- Dependabot now covers **npm** as well as maven — the 406-package frontend tree
  previously received no automated vulnerability PRs at all, on the more actively
  attacked of the two registries.
- `quality-gates.yml` runs `npm audit signatures`, `npm audit`, and asserts the
  install did not rewrite the lockfile. These run on push to main, which is the
  path this repo actually uses; `dependency-review.yml` is pull_request-only and
  so only ever sees Dependabot's PRs.

### Changed
- Dependency sweep to latest stable: anthropic-java 2.54.0, archunit 1.5.0,
  commons-collections 4.6.0, junit 6.1.3, lucene 10.5.1, nekohtml 3.0.4,
  docker-maven-plugin 0.49.0, jackson3 3.2.2, selenium 4.47.0,
  crawler-commons 1.6, and `@testing-library/jest-dom` 6 → 7 plus seven frontend
  patch bumps. katex is held at 0.16 (rehype-katex hard-depends on `^0.16.0`;
  taking 0.18 would bundle two katex copies and split editor/reader math
  rendering) and the junrar 8.x major is held (Tika 3.3.2 targets 7.6.0).
- **Browser integration tests now run headless by default.** Four headed Chrome
  instances contending for one compositor — with Chrome throttling unpainted
  windows — was a standing source of timing flakiness under parallel ITs.

### Fixed
- **junrar 7.6.1** — carries "prevent directory creation outside target
  directory", a path-traversal fix in the RAR parser Tika uses, which is
  reachable from `POST /api/ingest` and the connector ingestion path.
- **Anonymous Docker volume leaked per integration-test container.** The
  postgres/pgvector images declare `VOLUME /var/lib/postgresql/data`, so Docker
  auto-creates a volume per container; it carries no reapable label and dies only
  with `docker rm -v`, which nothing in the test path passed. On the development
  machine this had accumulated 3,345 volumes / 169GB. Fixed at all four sites, and
  `fulltestsuite.sh` no longer force-removes *every* pgvector container on the
  host (it matched `ancestor=`, killing other projects' live testcontainers).
- **Three intermittent gate failures, root-caused rather than retried.**
  `TestEngine` deleted its work directory while a Lucene indexer was still
  writing to it (engine shutdown only *requests* that a background thread stop),
  which orphaned indexers that then starved later tests; `WikiBackgroundThread`
  now wakes a sleeping thread on shutdown so this costs 27ms rather than 1s per
  engine. `KnowledgeTabIT`'s two "panel ready" guards were both ineffective —
  Selenide's `shouldNot(exist)` passes vacuously before an element mounts, and
  `[class*=kg-panel-]` is a substring match that also matches the
  `kg-panel-loading` placeholder. `KnowledgeGraphNavDisabledIT` asserted a
  negative against a deliberately fail-open capabilities UI on too tight a budget.
- Documentation reconciled against code truth across the repo, the marketing site
  and the module site docs: MCP tool counts (admin 26 → 27), connector counts
  (documented as six while listing seven; six are admin-creatable), migration
  range (V001..V049), plus removal of three documented-but-nonexistent things
  (`get_cluster`, `traverse_relations`, and two KG "Missing/Low-Plausibility
  Edges" features) and a `jspwiki.plugin.searchPath` property that silently
  no-ops.

## [2.4.2] - 2026-08-16

### Added
- **`update_page` gains `removeKeys` — the MCP surface can now retire a frontmatter field.**
  The metadata merge is unconditional by design (the existing frontmatter is always the base, so a
  one-field edit cannot drop the rest), which meant there was no way to *delete* a field at all:
  passing `hubs: null` writes a null-valued key rather than removing it, and frontmatter inside
  `content` merges the same way. `removeKeys` takes a list of field names and is applied **after**
  the merge, so a single call can set one field and retire another, and an explicitly-removed key
  cannot be resurrected by the existing frontmatter. Keys the page does not carry are ignored
  rather than erroring.

  `canonical_id` is **refused**: it is the page's rename-stable identity, and dropping it would make
  the save-time filter mint a fresh one, orphaning the page's history and every citation pinned to
  it. Silently ignoring the request would have been worse than refusing — an agent would believe it
  had succeeded.

  Found while planning the production `hubs:` retirement (ClusterDeclarationDesign Phase 5): the
  corpus migration is impossible through the MCP surface without it.

## [2.4.1] - 2026-08-15

### Changed
- **Restored the complexity ratchet and OSV scan to green after 2.4.0.** The cluster work tripped
  three static gates, and only one of them was a false positive:
  - `PageDescriptor` and `PageRecord` grew single-cluster convenience constructors that trip
    `ExcessiveParameterList`. PMD cannot see that a record's canonical constructor already carries
    the same list, so these are suppressed with justification — removing the overloads would force
    every single-cluster call site to spell out a redundant derived argument.
  - `DefaultStructuralIndexService.applyIncrementalUpdate` exceeded the NPath threshold. Extracting
    `toDescriptor()` and `persistCanonicalId()` fixes it and removes a genuine duplication: the full
    rebuild and the incremental update were each carrying their own copy of the
    frontmatter-to-descriptor mapping, which is exactly the pair that must never disagree about how
    a page is read.
  - `SchemaDrivenFrontmatterValidator` exceeded its cyclomatic budget. The cluster rules moved to a
    dedicated `ClusterFieldValidator`: `cluster` is the one schema field that is scalar-or-list,
    whose legality depends on another field (`type`), and which consults the live structural index —
    so isolating it keeps the generic validator generic.

  These are complexity-only changes with no behavioural difference; the 2.4.0 artifact is
  unaffected, and this release exists so the released tag reflects the tidied code.
- Pinned `nanoid` to 3.3.18 via an npm `overrides` entry (GHSA-2v37-7h3g-55p8, CVSS 8.2, a dev-only
  transitive). Pinned rather than added as a direct dev-dependency, which would have been a phantom
  import and tripped `knip`.

## [2.4.0] - 2026-08-15

### Added
- **Cluster declaration — the hub page is now the authoritative declaration of its cluster**
  (`ClusterDeclarationDesign`, ADR-0009; all seven phases). A cluster exists if and only if exactly
  one page carries `type: hub` plus a `cluster: <path>` — the pair the corpus already wrote, now a
  declaration rather than a coincidence. No new frontmatter fields. This was prompted by an
  evaluation of directory-structured content storage, which is **rejected and recorded as such**:
  a path in frontmatter is data (validatable, re-projectable, multi-valued, revertable per page),
  while a path in the filesystem is a location that would bind page identity, `page_slug_history`,
  `OLD/`, `-att/`, URLs and wikilinks to one axis — and every partition that became a retrieval
  filter in this system has measurably *cost* recall. Cluster nesting deeper than one level, and
  any filesystem-shaped export of the corpus, are permanently out of scope.
- **Multi-membership.** `cluster:` may now be a scalar **or a list** on non-hub pages; the first
  entry is primary and drives breadcrumbs, JSON-LD `articleSection`/`isPartOf`, the embedding
  prefix and sidebar placement, so no tie-break field was needed. Hubs stay scalar — a list-valued
  hub is a 422 at save and a `MULTI_CLUSTER_HUB` conflict on `/admin/drift`. This consolidates a
  capability the corpus was *already* asserting: 89 pages carried multiple `hubs:` entries in a
  field the schema did not even describe.
- **`rename_cluster`** — `POST /admin/clusters/rename?from=&to=[&confirm=true]` and an MCP tool on
  `/wikantik-admin-mcp` (26 → **27** tools). Renames a cluster across every member in one
  operation, carrying sub-clusters along. Unlike `rename_page`, an unconfirmed call is **not an
  error — it returns the plan**: a bulk rewrite is exactly the operation whose blast radius a
  curator should see first, and computing the plan writes nothing. A target another hub already
  declares is refused (409 / MCP error naming the incumbent) *before* any write, so a half-applied
  rename can never split the corpus across two names; past that gate the sweep never aborts and
  reports failed pages by name. It rewrites frontmatter only — no page names, `canonical_id`s or
  URLs move.
- **`cluster_status` on the page payload** — `{path, parent, hub_slug, hub_declared, member_count}`,
  plus an additive `memberships[]` array when a page has more than one. Whether a cluster has a
  declaring hub is not in the page's own frontmatter, so the server derives it. `hub_declared` is
  an always-present boolean because Gson omits null keys and a reader must not have to infer
  meaning from an absent field.
- **Cluster status in the reader** (`ClusterStatus.jsx` in `PageMeta`): four states — hub
  declaration, member of a declared cluster, member of an undeclared one, unclustered — **gated on
  `page.permissions.edit` and rendered client-side only**, never in SSR. A reader who cannot edit
  sees exactly what they saw before, so the anonymous render path stays byte-identical, ongoing SEO
  tuning is unconfounded, and edge caching is untouched.
- **`CorpusDivergenceCli`** (`wikantik-extract-cli`) — compares `docs/wikantik-pages/` against a
  live wiki's `/api/structure/sitemap`. It exists because the repository corpus and the production
  page store turned out to be *different corpora, not two copies*: planning a corpus-wide change
  from the checkout produced a plan that was wrong for production and briefly introduced the exact
  duplicate-declaration defect this design forbids. **Exit 2 means refused because a snapshot was
  incomplete** — a partial corpus turns every unread page into a phantom "missing from production",
  so comparing one is worse than not comparing at all.
- Four new `StructuralConflict.Kind` values feeding the `/admin/drift` burn-down —
  `DUPLICATE_CLUSTER_DECLARATION`, `HEADLESS_CLUSTER`, `UNDECLARED_CLUSTER`, `CLUSTERLESS_HUB` —
  plus `MULTI_CLUSTER_HUB` from the multi-membership phase.

### Changed
- **Knowledge Graph inclusion resolves cluster policy segment-aware and fail-closed.** Two fixes in
  one: `DefaultKgInclusionPolicy` now walks ancestors, so a `parent/sub` cluster inherits its
  parent's policy instead of matching exactly and being silently excluded — a live defect that
  would have removed every newly created sub-cluster from the Knowledge Graph; and across several
  memberships an explicit EXCLUDE on **any** of them wins outright. Inclusion is the one place
  cluster is authoritative rather than advisory, so anything weaker would let an unrelated author
  undo a curator's deliberate exclusion by adding a second membership.
- **Hub selection is deterministic.** Pages arrive from `listFiles()` in unsorted filesystem order,
  and the previous last-writer-wins `put()` made `list_clusters` report a different hub run to run
  without ever reporting a conflict. Ties now break on lowest slug and the loser is reported.
- **Cluster matching is segment-aware everywhere**, through a single `ClusterPath` class in
  `wikantik-api`. A bare `startsWith` reports `machine-learning-ops` as a descendant of
  `machine-learning`, silently merging two unrelated clusters. Membership is transitive and resolved
  at query time, so re-parenting needs no reindex, and the transitive lookup de-duplicates: a page
  naming both a parent and its own sub-cluster would otherwise be counted twice.
- **Hub `hasPart` is derived from real cluster membership** rather than the hub's `related:` list,
  with `related` retained only as a degraded-index fallback. This fixes a live SEO defect — `MLHub`
  was publishing `hasPart` for ten pages that were not in its cluster at all. Hub `related:` is
  rescoped to editorial highlights.
- **The ontology projects one `dct:subject` per membership**, each sub-cluster keeping its own
  `skos:broader` chain. `PageRecord` is now built from the page's frontmatter rather than the
  `page_canonical_ids` row, which stores only the primary — without that the multi-valued
  projection could never have emitted anything.
- **The structured frontmatter editor round-trips a list-valued `cluster`.** The field used the
  plain TEXT widget, where React coerces an array to `"a,b"` and the first edit saved that back as
  a single invalid slug — silent destruction of a page's memberships. It now emits a scalar for one
  value and an array for several, matching the server's rule exactly.
- Corpus migration: 90 hubs declaring 90 distinct clusters in production, with 0 duplicate
  declarations, 0 headless clusters, 0 clusterless hubs and 28 sub-clusters, all one level deep.

### Removed
- **`HubSyncFilter` and the `hubs:` frontmatter field.** The wiki carried *three* rival membership
  mechanisms — `cluster:` (validated, read by every consumer), `hubs:` (473 pages, 40% of the
  corpus, absent from `FrontmatterSchema` so no schema grep would find it), and hub `related:` —
  with a bidirectional filter rewriting the latter two on every save. They had fully diverged:
  `MLHub` listed 11 related members against 44 actual, `hubs:` pointed at 46 pages that do not
  exist, and 13 pages named themselves as their own hub. The filter's bulk re-save cascades were
  load-bearing enough that two unrelated subsystems carried explicit workarounds for them. The
  repository migration resolved 586 `hubs:` entries across 473 pages: 410 redundant, 46 dangling,
  33 pointing at pages that declare no cluster, 45 sitting on hub pages (hub-to-hub navigation, not
  membership — promoting those would have made 17 hubs list-valued), and **52 kept, giving 51 pages
  a second cluster membership**. Every migrated page kept its original value as the first entry, so
  no page's primary cluster moved.

### Notes
- The Phase 2 duplicate-declaration 422 ships **dark**:
  `wikantik.cluster_declaration.enforcement.enabled` defaults to `false`. Both corpora verified
  clean, so the flip is safe — but enabling it against a corpus that still holds duplicates makes
  the offending hub pages un-saveable, trapping exactly the content that needs editing to fix them.
- Production is authoritative for content, and the `hubs:` retirement was applied to the
  repository corpus only; the same migration must be run against production separately.

## [2.3.17] - 2026-08-15

### Removed
- **Dead-code sweep (2026-08-15 audit).** Nineteen commits, 113 files, 28,694 deletions against 171
  insertions of production change. Stale JSPWiki-era wiring — four `classmappings.xml` mappings for
  classes deleted long ago (`RSSGenerator`, `EditorManager`, `TemplateManager`, `AdminBeanManager`),
  the dead `plain` editor module block, seven JSP-era `WikiModuleInfo` getters, a
  `com.wikantik.WikiServlet` log4j2 logger plus the orphaned `AccessLog` appender it fed (both the
  Tomcat and Docker log configs), and two dead root-`pom.xml` entries. Unread configuration keys —
  `wikantik.rss.*`, ~50 `wikantik.defaultprefs.*`, `wikantik.securecookie`,
  `wikantik.cache.custom-config-file`. The entire JSP-template i18n bundle —
  `templates/default{,_es,_ru}.properties` (464 keys × 3 locales; the 4 still-read
  `notification.createUserProfile.*` keys moved to `CoreResources`) — plus 105 dead `install.jsp.*`
  keys and the 217-line `TranslationsCheck` CLI that existed only to diff those bundles. The
  `PAGE_RSS`/`WIKI_INSTALL`/`WIKI_WORKFLOW`/`WIKI_MESSAGE` commands and the whole JSP
  content-template column (`Command.getContentTemplate()`, the third `ContextEnum` constructor
  argument across all 23 surviving constants). 16 unreferenced Java classes and their 12 test
  classes, plus 16 unreferenced methods — among the classes, the finished `AssignCanonicalIdsCli`
  backfill (made redundant by save-time `canonical_id` enforcement) and the unused
  `ContextServiceBundleRetriever` eval adapter. A final orphan re-scan caught one more a level
  deeper: `MathSyntaxFixer`, the library `MathSyntaxFixCli` alone drove, deleted alongside its test
  once nothing else called it — deletions cascade, and a per-task sweep cannot see that.
  4 unused frontend dependencies, a dead probe script, 8 unused exports, and 2 duplicate default exports
  (`knip` now reports zero findings). Non-Tomcat deployment descriptors (`geronimo-web.xml`,
  `jboss-deployment-structure.xml`), JSPWiki's inherited 543 KB `OldChangeLog`, duplicated IDE
  config, an unreferenced container policy file, and licence files for artifacts no longer shipped.
  Also repaired: dangling `[{SessionsPlugin}]`/`[{ListLocksPlugin}]` invocations and five
  pre-rebrand `jspwiki.` variables in the three translated `SystemInfo` pages, and `wikantik.rss.*`
  references in the shipped `SystemInfo`/`InstallationTips` pages. The SpamFilter chain and the
  `wikantik-cache-memcached` module were reviewed and deliberately kept as dormant,
  re-activatable subsystems; both now document why they are inert.

## [2.3.16] - 2026-08-06

### Added
- `WIKANTIK_EMBEDDING_BATCH_SIZE` and `WIKANTIK_EMBEDDING_TIMEOUT_MS` entrypoint passthroughs. These
  two are coupled to how much CPU the embedding backend is allowed: a batch must complete inside the
  timeout, or the request fails, is classified transient, and after 3 retries the entire reconcile
  aborts. Capping the embedder's cores (a docker `cpus:` limit) multiplies per-batch time, so the
  stock 32-texts/30s pair — fine unthrottled — starts timing out and embedding fails outright rather
  than merely running slower. Found the hard way on a host capped to 2 CPUs to stop it power-cycling
  under sustained all-core inference. Neither knob was reachable from the container env before, so
  the only way to survive a CPU cap was to rebuild the WAR.
- `WIKANTIK_EMBEDDING_COMMIT_BATCH_SIZE` entrypoint passthrough for
  `wikantik.search.embedding.commit-batch-size`, so the embedding backfill's commit interval can be
  tuned per host from `.env` without rebuilding the WAR. Added because the setting matters most
  exactly where you cannot rebuild conveniently: a host that keeps dying mid-backfill wants a small
  interval (the value is how much embedding work a crash discards), and the default of 256 is chosen
  for a machine that stays up.

## [2.3.15] - 2026-08-06

### Fixed
- **The embedding backfill could never persist anything, so dense retrieval had been silently running
  empty.** `EmbeddingIndexService.indexAll`/`indexStale` wrapped the *entire corpus* in one
  transaction — `setAutoCommit(false)`, drain ~19.5k chunks, single `commit()` at the very end. On a
  CPU embedder that is roughly 3.5 hours in one open transaction, so any interruption (crash,
  restart, redeploy) rolled back every embedded row. Production had `content_chunk_embeddings`
  row_count 0 for days while looking healthy: the embedder was fine, ollama was fine, and the
  progress log cheerfully reported "2208 rows upserted so far" — all of it uncommitted, and all of it
  discarded when the host died mid-run.
  Completed work is now committed every `wikantik.search.embedding.commit-batch-size` rows (default
  256 — eight embedding batches, so commits land on batch boundaries). The interval is sized against
  inference cost, not commit cost: a commit is sub-millisecond next to seconds-per-chunk embedding,
  so there is no throughput argument for a larger window, and it bounds an interrupted run's loss to
  minutes instead of everything.
  Two details worth knowing. The drain uses **separate read and write connections** because the
  SELECT streams behind a server-side cursor and committing on that same connection would invalidate
  it mid-drain. And the progress callback now fires *after* the commit, so observers never see
  progress a crash would erase — the old message said "upserted", which is what made a
  never-persisting backfill look like a working one. `indexChunks` (the page-save path) deliberately
  keeps single-transaction semantics: all-or-nothing for one page is correct, and the set is tiny.

### Changed
- **Jena 6.1.0 → 6.2.0, retiring the `libthrift` security pin.** `libthrift` had been held at 0.23.0
  because 0.24.0 is binary-incompatible with jena-arq 6.1.0 and breaks TDB2 write transactions —
  `Tdb2SmokeTest` failed with a masked "forced abort", and the KG/ontology subsystem then reported
  itself disabled, 503-ing every admin KG endpoint. Jena 6.2.0 declares libthrift 0.24.0 itself, so
  the incompatibility is resolved upstream and the override is deleted rather than carried forward —
  the version now floats in from jena-arq, which is what the pin block's own rule asks for ("remove
  a pin once the declaring dependency ships the fix itself"). 0.24.0 still carries the
  CVE-2026-43869 fix, so dropping the pin costs nothing on the security side.
- **The Google Drive connector stack moves as one unit.** google-api-client 2.7.0 → 2.9.0,
  google-auth-library-oauth2-http 1.30.1 → 1.50.0, google-api-services-drive `rev20240914` →
  `rev20260720`, and the google-http-client family 1.45.3 → 2.2.0. They cannot move independently:
  google-api-client 2.9.0 requires google-http-client 2.x. All five versions now live in
  `google-*.version` properties, so the family convergence the old inline comment merely *asked* for
  is enforced by the build instead of by hand.
  google-http-client 2.0's sole breaking change is its Guava dependency moving to the `-android`
  flavour, which changes nothing here: 1.45.3 already requested `-android` (30.1.1) and the
  `sec.guava` pin has been overriding that all along. pac4j declares guava 33.6.0-jre — the exact
  version the pin carries — so the WAR still resolves a single guava and the SSO stack is untouched
  (`SSOLoginIT`/`SAMLLoginIT`/`SSOEdgeCaseIT` green). A class-level diff of google-http-client
  1.45.3 → 2.2.0 shows zero removals, three additions, and an unchanged Java 8 baseline.
  Note: no test exercises a live Drive OAuth exchange, so this is verified to compile and to not
  regress anything else — an `/admin/connectors` dry-run against a real Drive connector is still
  the only thing that proves Drive sync itself.
- Routine dependency refresh: jsoup 1.18.3 → 1.23.1 (the connectors' HTML parser), okio 3.18.0 →
  3.18.1, cyclonedx-maven-plugin 2.9.2 → 2.9.3, and the local dev Tomcat download 11.0.18 → 11.0.24.
  Frontend in-range updates land in `package-lock.json` only — the existing carets already permitted
  them — moving react/react-dom to 19.2.8, vite to 8.2.0, vitest and `@vitest/coverage-v8` to 4.1.10,
  eslint to 10.8.0, react-router-dom to 7.18.2, plus the CodeMirror and tooling packages.
  `actions/setup-node` in `quality-gates.yml` goes v4 → v6, matching `release.yml`.
  Deliberately held: `katex` (0.18.1 is out, but `rehype-katex@7.0.1` still pins `katex: ^0.16.0` and
  the app imports katex directly too, so bumping would ship two copies and split math rendering
  between the two paths) and `@testing-library/jest-dom` 7.0.0 (major, no benefit here).

### Removed
- **The blog feature is gone in its entirety.** `BlogManager` and its engine wiring, the
  `/api/blog` REST surface, the `/blog/*` SPA routes, the `BlogListing`/`ArticleListing`/
  `LatestArticle` plugins, the six React blog screens, and all blog content. Blogging moved to a
  separate application, so this removes a subsystem rather than deprecating it.
  The notable part is the page-storage layer: blog pages had their own on-disk layout
  (`blog/<username>/<slug>` as real subdirectories, with `OLD/` nested *inside* the user directory
  rather than at the top level) plus username case-folding on every cache key. That special-casing
  reached ~12 sites across `AbstractFileProvider` and `VersioningFileProvider` — shared code on the
  hot path for every page, not just blog ones — and is now gone, collapsing eight
  `isBlogPage(…) ? normaliseBlogName(…) : …` ternaries to their else-branch.

## [2.3.14] - 2026-08-01

### Fixed
- **The lexical half of `/api/bundle` stopped seeing new content the moment a page was saved.**
  `LuceneBm25ChunkIndex` was built once, at wiring time, and had no update path at all — so every
  page written since the last restart was invisible to BM25 chunk retrieval, while the dense half
  of the same fused query upserted on every save. BM25 is precisely the half that catches exact
  terms, identifiers and rare tokens that embeddings miss, and on a wiki whose pages are authored
  continuously by agents over MCP the gap widened for as long as an instance stayed up. The index
  is now maintained in place (`upsertChunks`/`reload` over a `SearcherManager`), driven from the
  chunk-projection seam. Deliberately *not* from the post-embedding callback: that only fires when
  the embedding succeeded, and BM25 — which needs chunk text alone — is what retrieval degrades to
  when the embedding backend is down, so gating its refresh there would have frozen lexical
  retrieval during exactly that outage. The same reasoning fixed the no-embedding-client
  configuration, which previously had no refresh path whatsoever in the one mode where BM25 is the
  only ranker. Reconciliation is page-scoped rather than id-scoped, because the save-time
  notification carries only the chunk ids a page still has: a page re-chunked from three chunks
  down to one would otherwise leave the two dropped chunks searchable forever.
- **`ChunkProjector`'s post-chunk sink silently dropped a consumer.** It was a single slot with two
  unrelated registrants — the search wiring and the entity-extraction wiring — and the second
  displaced the first. The only thing preventing lost work was a hand-rolled chain in
  `KnowledgeWiringHelper` that had to know about its neighbour. Sinks now accumulate
  (`addPostChunkSink`) with per-sink failure isolation, so registering a third consumer no longer
  requires knowing about the first two.

### Changed
- The embeddings test container's healthcheck matches its model tag exactly (`awk` on the first
  field) instead of `grep "^tag[[:space:]]"` — the tag contains a `.`, which was a regex wildcard,
  and the anchored prefix also matched longer variant tags whose embedding dimension differs.
- `bin/run-tests.sh` is now the single source of the IT embedder port, passing it as
  `-Dit.embed.port`; the deployed wiki's `embedding.base-url` and `DenseBundleIT`'s readiness probe
  both derive from it rather than repeating the literal.
- The SSO callback logs a WARN naming the session ID and response-committed state when it completes
  without throwing but leaves the session anonymous — instrumentation for a single unreproduced
  `SSOLoginIT` failure (#49) that previously left no evidence at all.

## [2.3.13] - 2026-07-31

### Removed
- **The Knowledge-Graph page-level graph rerank is gone** — 2,464 lines across seven production
  classes (`GraphRerankStep`, `GraphProximityScorer`, `QueryEntityResolver`, `PageMentionsLoader`,
  `InMemoryGraphNeighborIndex`, `GraphNeighborIndex`, `GraphRerankConfig`) and their seven test
  classes, plus the `wikantik.search.graph.*` properties. The 2026-06-16 ceiling spike settled it
  twice over: the shipped dense-chunk context bundle never invoked the step at all (output was
  bit-identical at every boost value), and on the page-gated path where it did run it was
  net-negative — re-extracting the evaluation slice with a much richer Claude-generated knowledge
  graph moved the result only to *zero* net lift at recall@12 and −1 at recall@5. Relational
  section relevance is not an entity-proximity signal: the graph knows which entities relate, not
  which passage answers the question. Evidence and verdict in `eval/kg-spike/A1-findings.md`.
  `RetrievalMode.HYBRID_GRAPH` / `HYBRID_GRAPH_WEIGHTED` are retained as wire-compatible labels so
  historical `retrieval_runs` rows still parse; requesting either mode now logs a warning and
  degrades to `HYBRID`. The Knowledge Graph and RDF ontology are unaffected — they remain
  first-class for the human knowledge base, agent traversal, and SPARQL.

### Fixed
- **SCIM provisioning was broken against any strictly-validating IdP.** Our
  `/scim/v2/ServiceProviderConfig` emitted `"documentationUri": ""`. The field is OPTIONAL
  (RFC 7643 §5) and an empty string is not a valid URI reference, so Authentik — which
  validates it as a URL — failed to parse the document, logged "failed to get
  ServiceProviderConfig", could not establish our capabilities, and then silently
  provisioned nobody. Every one of our own SCIM tests passed because none of them
  URL-validate. The field is now omitted, pinned by `ScimDiscoveryResourceTest` plus a
  recursive guard that no `*Uri`/`*Url` field in the payload is ever blank.
- **A rendering defect in the admin security page.** `ContainerRoleVerifier` built its roles
  header with `.append(roles.length).append(1)`, which concatenates digits rather than adding —
  two roles rendered `colspan="21"` instead of `colspan="3"`. Caught by a test written to fail
  first.
- **A repeat deploy no longer destroys the rollback target.** `remote.sh deploy` tagged the
  remote `wikantik:latest` as `wikantik:rollback` *before* loading the new image, so deploying
  the same image twice — a retry, a double-invoke, an idempotent re-deploy — copied the
  just-deployed image onto `:rollback`, leaving `:rollback == :latest` and nothing to roll back
  to (observed on the 2.3.12 deploy). The prior image is now parked under a temp tag and
  promoted to `:rollback` only once the newly-loaded image is known to differ. IDs are compared
  remote-side because `docker save | docker load` recomputes the image ID when the two daemons
  use different storage backends.
- **A deploy that silently changes nothing now fails.** The health poll only proved *something*
  healthy was listening, so it passed when compose declined to recreate the container and the
  previous version stayed up. Deploy now asserts the running container's image is the image it
  just shipped, and fails loudly naming both IDs.

- **The backup sidecar no longer overwrites a caller's environment.**
  `docker/backup/backup.sh` unconditionally reset `PATH` and re-exported every variable from
  PID 1's environ (the BusyBox crond fix), which clobbered any explicitly-supplied `PATH` or
  env — silently defeating the offline test harness, whose `pg_dump`/`psql` stubs were dropped
  on the floor so `bin/tests/test-backup.sh` could never pass anywhere. Both steps now *repair*
  a stripped environment instead of overwriting a good one: `PATH` is restored only when
  `pg_dump` is not already resolvable, and PID 1's values fill in only variables the caller
  left unset. Cron behaviour is unchanged.
- **Two green CI gates that were red.** The complexity ratchet caught two violations introduced
  by the 2.3.12 design-pattern pass — `AnthropicHttpCaller.call` at 9 parameters and
  `PageCanonicalIdsDao.upsert` at cognitive complexity 24 (threshold 20). The former groups its
  four connection arguments into an `Endpoint` record; the latter splits its INSERT and UPDATE
  branches into `insertNew`/`updateExisting` with a shared stale-slug-owner warning. Behaviour
  is unchanged. Dependency scanning flagged `brace-expansion` and `postcss` (both dev-only,
  both upgraded).

### Added
- **Version-identified rollback targets.** Deploy tags `wikantik:<X.Y.Z>` on the remote from the
  image's `org.opencontainers.image.version` label and retains the newest `ROLLBACK_KEEP`
  (default 5), so prior releases stay addressable instead of becoming dangling `<none>` images
  that `docker image prune` deletes. `remote.sh rollback --to X.Y.Z` re-promotes a named
  version (validated as X.Y.Z before it reaches a remote shell) and lists what is available
  when the requested one is absent.
- **The `bin/tests/` shell suites now run in CI** (`quality-gates.yml`), covering `remote.sh`,
  `container.sh`, the backup sidecar and the DB helpers — none of which the Maven reactor
  touches, so until now they only ran when someone remembered to. The job seeds `.env` from
  `.env.example` first, because the compose files declare it as a required `env_file` and
  without it `docker compose config` fails outright.
- **`osv-scanner.toml`** — a reviewed suppression list for the dependency gate. Its single
  entry documents why the react-router RSC advisory cannot apply here (the SPA uses no RSC
  APIs) and that no fixed version exists for `react-router-dom`, with an expiry so it cannot
  rot silently.

## [2.3.12] - 2026-07-26

### Fixed
- **Six latent defects from the 2026-07-25 quality audit.** `COEPFilter` read its mode from
  an init-param named `CORPValue`, so a configured `COEPValue` was silently ignored and the
  filter always emitted its default policy. `BasicAttachmentProvider.deleteVersion` was a
  `// FIXME: Does nothing yet` stub that returned normally without deleting the version file
  or its author/changenote properties. The plugin registry used a plain
  `HashMap` mutated after publication (unsafe under concurrent plugin lookup). `ReferenceManager.clearPageEntries`
  NPE'd on a page with no recorded references. `GenericCommand.toString` doubled the target
  in its output. `AdminProfilingServlet` swallowed exceptions in unlogged catch blocks and
  returned Tomcat's HTML error page instead of the JSON error envelope; it also set
  `Content-Disposition` download headers before the 404 check, attaching them to JSON error
  responses.

### Changed
- **Design-pattern consolidation pass** across the header filters, MCP tool surface, JDBC
  repositories and request context — behaviour-preserving except where noted. Ten
  single-header security filters now share `SingleValueHeaderFilter`; `AbstractMcpTool` moved
  to mcp-core and covers all 43 tools, which means **ten admin MCP tools that previously
  returned bare error strings now return the standard JSON error envelope**. Also: a
  `RouteTable` dispatcher, `KgJdbcSupport`/`SpineJdbcSupport` JDBC Template Method (68 methods
  across 7 repositories), `WikiContext` scope objects, `isNodePublic` as the single ACL
  authority for ontology projection, and extracted `OllamaHttpCaller`/`AnthropicHttpCaller`
  helpers shared by the four extractor/judge implementations.

### Internal
- **Test-suite performance and reliability pass.** Per-class `TestEngine` instances for 15
  wikantik-rest and 5 wikantik-main classes (~85s of class setup saved), a shared
  `RestTestSupport` fixture, a `deleteQuietly` helper replacing ad-hoc cleanup in 17 files,
  a session-id isolation seam, a sub-second CLI poll seam, parameterized `TextUtilTest`, and
  vacuous asserts (post-polling `if (size > 0)` guards that passed on zero results) repaired.

## [2.3.11] - 2026-07-25

### Added
- **Code-health site.** `bin/site.sh` generates an aggregated Maven site — unit+IT
  coverage, module coupling (graphviz SVG), PMD/CPD, SpotBugs, surefire, tech-debt
  (TODO/FIXME) and dependency-update reports, with per-module drill-down — and
  `bin/deploy-site.sh` publishes it to https://wikantik.com/site (excluded from
  indexing). Reporting only: no build gate and no change to `mvn install`.

### Removed
- **The legacy `pageName` / `pageNames` / `name` aliases for `slug` / `slugs`** on the
  admin and knowledge MCP tools. Every affected tool already declared `slug` required
  and advertised it in its description; the aliases only worked because mcp-sdk 1.x did
  not validate tool inputs. Under 2.0.0 the SDK rejects an alias-only call before the
  tool runs, so the aliases are gone rather than left half-working. **Callers must send
  `slug`/`slugs`.** MCP *prompt* arguments (e.g. `audit-links`) are a separate surface
  and still use `pageName`; response payloads still carry a `pageName` field.

### Security
- **Server-side rendering of `/wiki/*` now enforces the view ACL.** `SpaRoutingFilter`
  rendered the full page body into the SSR `#root` and the JSON data island for every
  HTML request, with no permission check at all — so an anonymous `GET /wiki/RestrictedPage`
  returned the entire body even though `GET /wiki/RestrictedPage?format=md` correctly 404'd
  (`WikiPageFormatFilter`, mapped to the same URL pattern, has always gated on `view`).
  The SSR path now resolves the caller's session and applies the same check, serving the
  bare SPA shell with a 404 when denied — hiding the body, title, summary and the page's
  existence. The permission decision is also folded into the ETag, so a browser cannot be
  handed a 304 for a body it is no longer allowed to see after logging out.
- **Inline page ACLs now grant to the principal they name.** The page-text fast path handed
  `parseAcl` the whole `[{ALLOW view Admin}]` match, whose `StringTokenizer` folded the
  trailing `}]` into the *last* principal name — so `[{ALLOW view Admin}]` produced an entry
  for a principal literally called `Admin}]`. The ACL was non-empty (so the page was
  restricted) but matched nobody, denying every non-admin caller including the intended
  grantee; with a comma-separated list only the final principal was affected. The braces are
  now stripped before parsing, exactly as the render path has always done.

### Changed
- **mcp-sdk 1.1.2 → 2.0.0.** The SDK now validates tool inputs against the declared JSON
  schema before `execute()` runs, and returns the input schema as a plain `Map` rather than
  a typed `JsonSchema`. Consequently the **legacy `pageName`/`pageNames`/`name` aliases for
  `slug`/`slugs` are retired** on the admin and knowledge MCP tools: every one of those tools
  already declared `slug` required and advertised it in its description, and the aliases only
  worked because 1.x validated nothing. Callers must send `slug`/`slugs`. The MCP *prompt*
  arguments (e.g. `audit-links`) are a separate surface and still use `pageName`.
- **Dependency refresh to latest stable.** apache parent 35 → 39 (which also required pinning
  `maven.compiler.release`/`javaVersion` — the new parent derives all three compiler
  coordinates from its own `javaVersion`, default 8, and `release` wins over `source`/`target`,
  which silently compiled the reactor to Java 8), anthropic-java 2.44.0 → 2.52.0, junit
  6.1.0 → 6.1.2, log4j2 2.26.0 → 2.26.1, pac4j 6.5.4 → 6.5.5, postgresql 42.7.11 → 42.7.13,
  tika 3.3.1 → 3.3.2, selenide 7.16.2 → 7.17.0, selenium 4.45.0 → 4.46.0, bouncycastle
  1.84 → 1.85, jackson3 3.2.0 → 3.2.1, okio 3.17.0 → 3.18.0, plus cargo/jar/spotbugs/taglist/
  versions plugin bumps. `libthrift` is deliberately held at 0.23.0: 0.24.0 is binary-
  incompatible with jena-arq 6.1.0 and breaks TDB2 write transactions (which in turn made the
  Knowledge Graph subsystem report itself disabled and 503 every admin KG endpoint), and
  0.23.0 already carries the CVE-2026-43869 fix.
- **Code-quality pass** from a full SpotBugs + PMD + CPD sweep. Three swallowed auth
  exceptions now log (notably `JDBCGroupDatabase`, where a `SQLException` made a database
  failure look like an empty group, silently suppressing every permission its members
  derive from it); the five worst CPD duplications were factored out; dead loggers, fields
  and imports removed; `QueryStructureHeuristic` pinned to `Locale.ROOT` (its ASCII markers
  stopped matching under a Turkish default locale). `LuceneSearchProvider` lost two
  vestigial ACL fields and a stale comment that together implied it filtered results by
  permission — it does not; that happens at the REST layer.

### Fixed
- **`bin/run-tests.sh` no longer reports IT results from a stale build.** Phase 1 ends with
  `install`; when it failed, the IT modules (which resolve from `~/.m2` without `-am`) ran
  against the *previous* build's jars and reported pass/fail for code that was not in the
  working tree. The IT phase is now skipped with an explicit reason when the unit phase
  fails. `--it` still runs unconditionally — that flag already means "assumes `--unit` ran".

## [2.3.10] - 2026-07-21

### Added
- **CPU embedding sidecar for prod (docker-compose.prod.yml).** An `ollama` service
  (no host port, compose-network only, `cpus: 6` / 4G limits, model kept resident)
  serves `qwen3-embedding:0.6b` so docker1 runs `genai.mode=embeddings-only` while
  the GPU inference host is packed: hybrid search and the dense bundle source stay
  live (~150 ms warm query embeds on the Ryzen 5825U); chat inference (extractor,
  judge, reranker) remains force-disabled.

## [2.3.9] - 2026-07-21

### Fixed
- **Context bundle now works without an LLM.** With no embedding client (hybrid search
  disabled, or the `wikantik.genai.mode` ceiling forcing embeddings off) the search wiring
  bailed out before registering the bundle's chunk sources — so `/api/bundle`, `/api/briefing`,
  and the `assemble_bundle`/`get_briefing` MCP tools returned zero sections even though the
  BM25 lexical index needs no LLM at all. The LEXICAL/HYBRID sources are now wired BM25-only
  in that configuration; an explicitly-dense request honestly returns empty. Found while
  validating an inference-host-offline deployment.
- **Per-thread guest sessions no longer leak across engines.** The static guest-session cache
  handed the first engine's guest (with that engine's manager references) to any engine on the
  same thread — in embedded multi-engine hosts this could silently drop permission-filtered
  results or fail session lookups. The cache now rebinds when the engine changes.

### Changed
- **Gang-of-Four refactoring pass.** Builders for the entity-extraction indexer,
  `DefaultKnowledgeGraphService`, and `RetrievedPage`; a Template Method base enforcing the
  fail-closed `poll()` contract once for the Confluence/GitHub/Drive connectors and the shared
  error envelope for all 19 knowledge MCP tools; admin content/hub-discovery resources aligned
  to the house Command dispatch-table idiom; shared bulk-action result envelope. No wire-format
  changes; PMD complexity ratchet unchanged.

## [2.3.8] - 2026-07-21

### Fixed
- **Concurrent renders of one page could throw and silently serve raw markdown.** The parsed-
  document cache shares a single flexmark AST across requests, and flexmark mutates document
  state during render (footnote ordinal resolution) — concurrent first-renders of the same page
  intermittently threw `ConcurrentModificationException`, and the SPA then degraded to showing
  the page's raw markdown instead of HTML. Renders are now serialized per document instance
  (repeat renders of a hot page are absorbed by the HTML cache before that point), a regression
  test hammers the path from 16 threads, and the render-failure log now includes the stack trace
  instead of just the exception message.

### Changed
- **Test-suite overhaul: full pre-commit gate 23:44 → ~4:30 with more coverage.** Canonical gate
  is now `bin/run-tests.sh --parallel 4` (unit tests once + all four IT modules 4-wide on
  reserved ports; the previously-omitted `knowledge-disabled` IT module is back in the default
  gate). Unit-test JVMs run with a reduced bcrypt work factor via the new clamped, warn-logged
  `com.wikantik.util.bcrypt.cost` system property — production and the IT Tomcat keep cost 12
  and runtime behavior is unchanged. Ten flaky or order-dependent tests were root-caused and
  fixed, and five previously-vacuous search assertions now assert for real.

- **`bin/container.sh smoke-test` / `-e test` now run the compose overlay on its base file.**
  `docker-compose.test.yml` is deltas-only; standalone use was invalid, collided with a
  bare-metal Tomcat instance on port 8080, and `down -v` shared the `dev` project's Docker
  namespace (risking cross-environment volume deletion). It now runs base + overlay under a
  dedicated `-p wikantik-test` project with `WIKANTIK_HOST_PORT=18080`.

## [2.3.7] - 2026-07-16

### Added
- **`wikantik.genai.mode` cost ceiling.** A new `GenAiMode` enum (`full` | `embeddings-only` |
  `none`) caps which LLM operations a deployment is allowed to perform. Each config resolves the
  ceiling once inside its own `fromProperties` (extractor, judge, reranker, decomposition,
  embedding) and ANDs it into the effective enabled/backend value, rather than checking it at
  scattered call sites. An absent, blank, or unrecognized value falls back to `full` with a logged
  warning — existing deployments and operator typos degrade gracefully instead of failing closed.
- **`wikantik.knowledge.enabled` subsystem flag.** Turning the Knowledge Graph off skips KG
  service construction entirely — no KG tools registered on either MCP server,
  `/admin/knowledge-graph/*` and `/api/page-knowledge/*` return 503 naming the flag — while
  chunking and the embedding/dense-retrieval pipeline stay fully active; entity extraction is
  skipped regardless of the configured extractor backend.
- **`GET /api/capabilities`.** A public, pre-login endpoint reporting `knowledgeGraph`,
  `hybridSearch`, `genaiMode`, `ontology`, `connectors`, and `citations` flags computed fresh from
  wiki properties on every request. The frontend fetches it once via a fail-open
  `CapabilitiesProvider` and hides Knowledge Graph navigation (reader + admin sidebar) up front
  instead of flashing it and then hiding it.
- **Cloud deployment reference infrastructure.** `deploy/aws/` and `deploy/gcp/` are minimal
  single-VM Terraform modules (EC2 + EBS/DLM snapshots or Compute Engine + a persistent disk with
  snapshots, one security group / two firewall rules, a static IP, secrets in SSM Parameter Store
  / Secret Manager, an optional DNS record) sharing one cloud-init template that installs Docker,
  relocates its data root onto the persistent volume, fetches secrets, and brings up the Compose
  stack for a chosen GenAI tier and ingress mode. A new `docker-compose.cloud.yml` overlay adds the
  registry image reference (no local build), `caddy` (Let's Encrypt) / `cloudflared` (tunnel) /
  `ollama-embed` (CPU embeddings) profiles, and a named-volume backup sidecar.
- **Pull-based VM updates.** `deploy/bin/wikantik-update.sh` (GHCR pull, retag-to-rollback, `.env`
  image swap, health-poll, auto-rollback) and a new `bin/remote.sh deploy --pull TAG` mode
  (remote-side pull + tag instead of a local build/save/ssh-load) let a cloud-init-provisioned VM
  upgrade without a local build.
- **Reranker and decomposition rows in the llm-activity log**, plus GenAI cost-tier documentation
  (`docs/CostTiers.md`) covering which LLM seams `wikantik.genai.mode` gates and how to verify
  enforcement.
- **Claude page extractor wired for admin batch KG extraction.** `POST
  /admin/knowledge-graph/extract-mentions` previously returned 503 under `backend=claude` because
  the bootstrap-indexer dispatch only ever built an `OllamaPageExtractor`; it now selects the
  extractor by the configured backend, so a cloud (Anthropic-only) tier can backfill Knowledge
  Graph mentions across an existing corpus.

### Changed
- **`RemoteIpValve` header is now configurable.** `wikantik.proxy.remoteIpHeader` (default
  `CF-Connecting-IP`, unchanged for docker1) lets a cloud deployment behind a different trusted
  proxy (an AWS/GCP load balancer, etc.) supply its own header via `PROXY_REMOTE_IP_HEADER`.
- **`capabilities.hybridSearch` is now ceiling-adjusted.** It ANDs the raw flag with
  `GenAiMode.allowsEmbeddings()` — mirroring `EmbeddingConfig`'s effective-enablement formula —
  instead of a raw property pass-through, closing a drift where `/api/capabilities` could report
  hybrid search as available under `genai.mode=embeddings-only`/`none` when the embedding config
  itself had disabled it. A parity test (`CapabilitiesResourceTest`) guards the two from drifting
  apart again.

### Fixed
- **Cloud compose overlay review fixes.** A dedicated DB host/port seam avoids `POSTGRES_HOST`
  shadowing between the app and bundled-db containers, the textfile-collector volume is
  unconditionally named (was silently anonymous with the backup profile off), and the Caddy
  empty-domain failure mode is verified rather than assumed.
- **AWS cloud-init secret handling hardened.** Secrets are fetched fail-closed and are never
  sourced as shell variables, closing an injection surface in the boot script.
- **`bin/remote.sh deploy --pull TAG` validates the tag/image ref before interpolation**, and
  `wikantik-update.sh` gained a self-lock plus documented notes on when a rollback is skipped.
- **RSS/Atom feed connector: an all-feeds-unreachable sync now returns an incomplete batch**
  instead of an empty-but-successful one, so the sync-status probe correctly reports "unreachable"
  rather than silently looking healthy.
- **Hub-discovery 503s now cite `wikantik.knowledge.enabled` by name** instead of a generic error
  when the Knowledge Graph subsystem is off.

## [2.3.6] - 2026-07-15

### Added
- **Connector framework — six external-source connector types syncing into derived pages.** A new
  `wikantik-connectors` module adds a `SourceConnector` SPI (`com.wikantik.api.connectors`) and a
  hash-dedup, cursor-resume, tombstone-deriving `SyncOrchestrator` shared by filesystem, auth-free
  web-crawler (jsoup + crawler-commons; BFS with scope/depth/robots.txt/politeness), sitemap
  (urlset + sitemapindex), RSS/Atom feed (Rome-backed; full-article or inline; archive-window
  semantics), Google Drive (OAuth2 refresh-token consent flow; Google Docs → markdown), and
  GitHub + Confluence (static-token REST clients) connectors. Every connector rides the existing
  derived-page ingestion pipeline (`derived_from` provenance, machine-owned body per ADR-0004) via
  a new `DerivedPageSinkAdapter`; `poll()` is contractually never-throws, so one connector's
  failure never blocks the sync scheduler or mass-deletes pages from a stale/failed listing.
- **Admin UI for connector management** at `/admin/connectors` — a list + detail view
  (overview/settings/authorization/runs/pages tabs, gated delete) and a guided **Add Connector
  wizard** (type picker → source config → authorize → dry-run test → review) that also supports
  one-click import of legacy properties-defined connectors and an OAuth deep-link flow for Google
  Drive consent.
- **DB-backed connector configuration with hot-apply.** Connector definitions now live in
  `connector_configs` (migration V048; `ConnectorConfigCodec` validates all 6 typed shapes) and are
  CRUD'd through `/admin/connectors/*` REST; `ConnectorConfigService` rebuilds and hot-swaps the
  connector registry on every change — no restart required. Per-connector sync intervals are driven
  by a due-tick scheduler (replacing one fixed-rate loop shared by every connector), and per-run
  history is recorded in `connector_sync_run` (migration V049, purged on connector delete so a
  recreated connector id starts clean).
- **Encrypted connector credentials store.** Secrets (GitHub token, Confluence API token, Google
  Drive client secret + refresh token) are AES-256-GCM encrypted (`AesGcmCipher`) in a new
  `connector_credentials` table (migration V047) keyed by `wikantik.connectors.crypto.key`,
  injected/listed/deleted via `/admin/connector-credentials/*`; the config-body validator
  independently rejects secret-named keys submitted in plaintext as a defense-in-depth backstop.
- **Reader-facing provenance for derived pages.** A provenance banner on the page view and ↯
  badges in search results and the sidebar mark pages that originated from a connector sync;
  per-connector content defaults (cluster/tags/name-prefix) apply only at page creation, never
  overwriting curation.
- **Kill switch.** `wikantik.connectors.enabled` (default **true** — it's a kill switch, not an
  opt-in) is a hard stop for syncing; config CRUD keeps working even when disabled, so a read
  replica or a maintenance window can pause syncing without losing configuration.
- **Experimental query decomposition for the context bundle** (default off). A fail-closed LLM
  query planner (`wikantik.bundle.decomposition.*`) can split a multi-part query into sub-queries
  fused via N-ary RRF. Measured on a relational eval set it scored *worse* than the undecomposed
  control (hop-recall 0.611 → 0.500 with RRF score-sum letting the majority co-mention topic drown
  out the minority side); a round-robin fusion variant closed part of the gap but still trailed
  control. Ships disabled pending a larger multi-hop eval corpus — verdicts banked in
  `eval/bundle-corpus/baseline-notes.md`.

### Fixed
- **GitHub / Confluence connector hardening (final review).** GitHub URL path segments are now
  percent-encoded before use, and a malformed Confluence listing page no longer silently tombstones
  the rest of the sync batch (`PageListing.skippedMalformed` surfaces the fault instead).
- **Tombstones never derive from an untrusted snapshot.** A connector-side listing failure could
  previously be misread as "everything was deleted"; tombstone derivation now requires a trusted,
  complete snapshot, closing a mass-delete-on-outage risk.
- **Per-connector sync lock.** A manual `/admin` sync trigger could previously race the scheduler's
  own due-tick for the same connector; both paths now share one lock.
- **Response and download sizes are capped** across the HTTP-based connectors (GitHub, Confluence,
  Google Drive, web crawler) via a shared `CappedBodySubscriber`, as an OOM defense against a
  malicious or misconfigured upstream.

## [2.3.5] - 2026-07-10

### Changed
- **MCP `update_page` can now edit editorial default-content system pages (e.g. `About`).**
  System pages stay write-protected against MCP, but pages exempted via the new
  `SystemPageRegistry.isMcpEditable` predicate (configurable through
  `wikantik.systemPages.mcpEditable`, default `About`) are curator-maintainable through
  the agent surface. Destructive operations (`delete_pages`, `rename_page`,
  `mark_page_verified`) remain blocked for all system pages, so the discovery anchor
  cannot be removed or renamed.

## [2.3.4] - 2026-07-08

### Changed
- **Toolchain and runtime upgraded from Java 21 to Java 25 (LTS).** The `jdk.version`
  21→25 bump cascades to the compiler source/target, the `requireJavaVersion` enforcer
  gate, and every PMD `targetJdk`; the Docker build/runtime images move to
  `maven:3.9-eclipse-temurin-25` / `tomcat:11.0.22-jdk25-temurin`; CI + release workflows
  and the operator/developer prerequisite docs are synced to JDK 25. The pinned toolchain
  (JaCoCo 0.8.15, Mockito 5.23.0) already supports class-file v69; the incubating Vector
  API stays enabled via `--add-modules=jdk.incubator.vector`.
- Local deploy scripts now fail loud when run on a JDK older than 25.

### Added
- **Fail-loud OIDC discovery reachability self-check at startup.** When SSO is configured,
  the engine verifies the OIDC discovery endpoint is reachable at boot and fails loud,
  instead of surfacing the misconfiguration only on the first (lazily fetched, cached)
  login attempt.

## [2.3.3] - 2026-07-05

### Added
- Context briefing service: get_briefing MCP tool + GET /api/briefing (session-start context injection for coding agents), briefing_log telemetry (V044), client shims under clients/.

## [2.3.1] - 2026-07-03

### Changed
- **Ontology snapshots are now cached.** `/sparql`, the `sparql_query` MCP tool, ontology-aware
  query expansion, and the RDF export no longer rebuild the full corpus-wide RDFS materialization
  per call; `OntologyModelManager` caches the inference/union snapshots and invalidates on write,
  guarded by a write-generation counter against concurrent-build races.
- **Default dense retrieval backend is now `lucene-hnsw`** (was `inmemory`). True ANN with
  incremental upserts replaces the O(corpus) brute-force scan per query; `inmemory` remains
  available for dev/small corpora. One canonical `ChunkVectorIndex` instance is now shared by the
  hybrid search wiring and `retrieve_context`'s dense path (previously two full index builds per
  boot, one silently stale).
- **SSR `/wiki/*` responses are now revalidatable.** `private, no-cache` + weak ETag (shell
  fingerprint, page version, mtime) replaces `no-store`; repeat navigations get 304s that skip the
  body read, render, and injection entirely. Frontmatter is parsed once per SSR request (was 2-3×).

### Fixed
- **`PermissionFactory` could return the wrong page's permission on a hashcode collision** (the
  long-standing XOR-key FIXME). Replaced the `synchronized(WeakHashMap)` — a process-global lock
  taken on every permission check — with a bounded Caffeine cache keyed by a value record.
- **Bulk viewability filtering no longer re-reads page bodies.** `DefaultAclManager` caches parsed
  ACLs keyed by (name, version, mtime), and `/api/pages` uses a batch filter with a one-time
  blanket-grant fast path — ACL-less pages skip per-page policy evaluation entirely; response
  semantics (`total` over the viewable set, restricted names hidden) unchanged.
- **`GET /admin/users` N+1 query storm.** One `SELECT` via the new `UserDatabase.findAllProfiles()`
  replaces 1+N pool checkouts; a single unmappable profile no longer fails the whole response.
- **Rendering no longer builds throwaway flexmark machinery.** `MarkdownRenderer` stops
  instantiating a full discarded `MarkdownParser` per render; the six stateless stock flexmark
  extensions are shared statics; `InMemoryChunkVectorIndex.upsertChunks` drops from ~3 corpus
  copies per save to 1.

## [2.3.0] - 2026-07-03

### Added
- **Two-tier per-IP rate limiting on the public HTTP surface.** A `RateLimitFilter`
  (sliding-window, Caffeine-backed) guards `/api/*`, `/sparql`, `/id/*`, and `/export/*` against
  compute-amplification abuse, with separate burst and sustained tiers per client IP. Client IP is
  resolved through Tomcat's `RemoteIpValve` (CF-Connecting-IP), so limits key on the real caller
  behind Cloudflare. Default-on; tunable via `WIKANTIK_RATELIMIT_*` environment variables. The
  reusable `SlidingWindowRateLimiter` lives in `wikantik-http`.
- **Retrieval-coverage signal on the context bundle.** Every `/api/bundle` response and
  `assemble_bundle` MCP result now carries a `coverage` block — `sectionCount`, `distinctPageCount`,
  `topSimilarity` (true dense cosine), and a `confidence` label (`strong` / `partial` / `weak`) —
  so an agent can tell how well-grounded an answer will be before composing it. Coverage is recounted
  after the ACL view-gate, downgrading `strong`→`partial` when access filtering thins the result
  below the strong floor. MCP tool descriptions now route count / enumeration questions to the
  structured `sparql_query` / ontology tools rather than the prose bundle.

### Changed
- **`wikantik-mcp-core` module extracted.** The shared MCP substrate (`McpTool`, `McpToolUtils`,
  `McpAudit`, endpoint bootstrap, access filter, config, and the shared `query_nodes` /
  `search_knowledge` tools) moved into a new `wikantik-mcp-core` module, eliminating the
  `wikantik-knowledge → wikantik-admin-mcp` dependency edge (a module cycle).
- **WikiEngine god-class reduced via a late-bound service registry.** The 78 hand-maintained
  `mgr_*` fields and their typed reader/writer maps are replaced by a generic
  `EngineServiceRegistry` (CBO 143→86, ~2337→1894 LOC); an ArchUnit guard (R-5) prevents
  re-accretion of service fields/setters. Documented in ADR-0008.
- **Three critical-area god-class / complexity decompositions.** `AdminKnowledgeResource`
  (1666→290 LOC dispatch-only; handlers extracted to `com.wikantik.rest.knowledge`),
  `DefaultContextRetrievalService` (644→396 LOC; `RelatedPagesFinder` / `PageListEngine` /
  `ContributingChunkAssembler` extracted), and `SemanticHeadRenderer` (516→182 LOC facade;
  `PageSeoModel` + `JsonLdEmitter` + `HeadTagWriter`, NPath 46,080→clean) — all behavior-preserving,
  guarded by their characterization suites.

### Fixed
- **SpotBugs real findings repaired.** A lock-chain return-value bug and dead `WikiEngine.injector`
  Guice plumbing were fixed; two idiom-level detectors that produce only false positives in this
  codebase were suppressed.

### Internal
- **CI complexity ratchet.** A `pmd:check`-backed `complexity-gate` profile fails the build on any
  *new* PMD design-rule violation not already in `build-support/pmd-complexity-baseline.properties`
  (a burn-down baseline — entries only ever come out). `SemanticHeadRenderer` (6 rules) is the first
  burn-down.
- **Retrieval-experiment harness moved out of the production WAR.** The 14 experiment classes moved
  from `src/main` to `src/test` (test-scope only); the production jar now contains zero experiment
  classes. The grounded-agent eval harness defaults to a local Ollama (`gemma4:12b`) backend.

## [2.2.0] - 2026-06-29

### Added
- **Per-request retrieval-mode toggle on the context bundle.** `GET /api/bundle?mode=hybrid|dense|lexical`
  and a `mode` argument on the `assemble_bundle` MCP tool now select the retrieval strategy per call:
  `hybrid` (default — unchanged behavior), `dense` (vector-only), `lexical` (BM25-only). Backed by a
  `RetrievalMode` enum and a per-mode candidate-source map wired through the bundle assembly service;
  an unavailable mode degrades to the default with a logged warning, and an invalid value returns a
  clear error listing the valid modes. The existing `assemble(query)` API and the no-`mode` request
  path are fully backward compatible.

### Changed
- **`assemble_bundle` repositioned as the primary answer-grounding MCP tool; `retrieve_context`
  reframed as page/section discovery.** The tool descriptions now steer agents to `assemble_bundle`
  (ranked, de-duplicated, version-pinned, citation-bearing section text) for composing answers, and
  add anti-loop and ground-only-in-returned-text guardrails to `retrieve_context`. Measured against
  the grounded-agent eval: agents adopt `assemble_bundle` as their primary tool (from unused to
  most-used) and per-answer retrieval looping drops sharply, with answer correctness held flat.
- **KG judge guard-rejections log at INFO instead of WARN.** Closed-vocabulary and
  SHACL-non-conformant edge skips are the ontology gate working as designed; they no longer read as
  errors in the logs.

### Fixed
- **Embedding indexer distinguishes transient backend failures from poison-pill chunks.** A
  503 / timeout / connection error while (re)embedding is now retried with bounded backoff instead of
  being treated like a permanently-bad chunk and mass-skipped, so a brief inference-backend hiccup no
  longer leaves silent holes in the dense index. Transactional indexing paths also roll back
  explicitly on a runtime error rather than relying on connection-close.
- **KG judge log-spam during a backend outage.** The per-proposal WARN flood is replaced by one
  per-tick transient-unavailable summary, and transport + parse failures are demoted to DEBUG (they
  are aggregated in the tick summary).
- **KG judge JSON parsing hardened.** A judge response missing its message content now degrades to a
  clean transient-retry verdict instead of throwing a `NullPointerException`.
- **`wikantik.bundle.dense.enabled=false` honored again.** The page-gated fallback that this property
  documents was inadvertently bypassed by the per-mode rewiring; it is restored.

### Internal
- **Grounded-agent eval harness** (`eval/agent-grounding/`) — a reproducible scorecard measuring
  whether MCP grounding beats a cold model on Wikantik-internals questions; gained an opt-in
  `--samples N` median mode for noise-robust gated runs and was used to validate the interface
  changes above.

## [2.1.7] - 2026-06-27

### Changed
- **Dependency upgrades.** Apache Jena 5.2.0 → 6.1.0 (major; the ontology RDF/SPARQL/SHACL/TDB2
  surface uses only bedrock APIs and is unaffected), Lucene 10.4 → 10.5, pac4j 6.5.3 → 6.5.4,
  anthropic-java 2.42 → 2.44, cyclonedx-maven-plugin 2.9.1 → 2.9.2. Verified across the full unit
  and integration suites.
- **Frontend upgraded to React 19 and Vite 8 (Rolldown).** React 18 → 19 and the build toolchain to
  Vite 8 with the Rust-based Rolldown bundler — ~8× faster production builds, and esbuild + rollup are
  dropped (clearing their advisories; `npm audit` is clean). Heavy vendor libraries (React, katex,
  CodeMirror, Cytoscape) are split into separate long-term-cacheable chunks, shrinking the eager entry
  bundle. Node 20.19+ (or 22.12+) is the documented build prerequisite (corrected from "18+").

### Fixed
- **React 19 rendering regression.** React 19 re-applies `dangerouslySetInnerHTML` on every re-render
  of the host element (React 18 skipped an unchanged value), which wiped the copy buttons, KaTeX
  output, and comment highlights that post-render effects inject into rendered pages. The article
  element is now memoized on its HTML string, so unrelated re-renders (scroll-spy, drawer, text
  selection, modals) keep the enhancements. Affected the page reader (`PageView`) and the blog views.
- **Spam rate-limiter crash.** `DefaultSpamRateLimiter` cleared its temporary-ban list and
  modification queue via `CopyOnWriteArrayList.iterator().remove()`, which throws
  `UnsupportedOperationException` — so cleanup threw whenever an expired entry was present. Rewritten
  with `removeIf()`.
- **`WikiDocument.getContext()` NPE** when `setContext()` had never been called (the `WeakReference`
  field was null); now null-guarded.

### Removed
- **Dead Guice integration.** `WikiModule`, the `WikiEngine` Guice `injector` field/branch, and the
  `com.google.inject:guice` dependency are removed — Guice was scaffolded but never wired (never
  instantiated, no injector ever assigned, no `@Inject` anywhere). The `guava` security pin is
  retained (guava is still pulled transitively via pac4j-oidc/pac4j-saml).

### Internal
- **Code-quality pass.** Extracted `KnowledgeJsonMapper` from `AdminKnowledgeResource` (Extract Class),
  deduplicated the SCIM resource HTTP dispatch into `AbstractScimServlet` (Template Method), and added
  ~315 behavior-asserting unit tests — reactor line coverage 79.0% → 80.2%.
- **SpotBugs configured for deeper analysis** (effort=Max, threshold=Low). Fixed the genuine findings
  (audit-chain `ResultSet` handling, `Locale.ROOT` on locale-independent case conversions, XHTML DTD
  constants made `final`) and suppressed the documented-convention noise so the scan stays actionable.

## [2.1.6] - 2026-06-26

### Security
- **Security headers now actually reach server-rendered HTML pages.** The `Content-Security-Policy`,
  `X-Frame-Options`, HSTS, `X-Content-Type-Options`, Referrer-Policy, and COEP/CORP filters were
  mapped in `web.xml` *after* `SpaRoutingFilter` / `WikiPageFormatFilter` — and those filters serve
  `/wiki/*` (and the SPA shell) by writing the response body and returning **without** continuing the
  filter chain. The net effect: no server-rendered page (`/wiki/*` and every SPA route a browser
  loads) carried any security header — they appeared only on API/JSON and static-asset responses, so
  the 2.1.4 CSP/clickjacking hardening never protected the pages users actually view. The
  content-serving filters are now ordered last in the chain, after the header filters. Locked in by a
  web.xml-ordering unit guard (`SecurityHeaderRegistrationTest`) and a `/wiki/*` wire-level case in
  `SecurityHeadersIT`.

## [2.1.5] - 2026-06-26

### Security
- **`list_clusters` (knowledge-mcp) no longer leaks a restricted cluster hub page.** Each cluster's
  hub-page descriptor (slug, title) is now redacted to `null` when the hub page is not viewable by an
  anonymous guest, completing the slug-gateable part of the agent-surface access-control work for the
  aggregate enumeration tools. The cluster name and article count are unchanged.

## [2.1.4] - 2026-06-25

### Added
- **Admin → Content & Index: "Reindex Search (Lucene)" action.** A lightweight,
  non-destructive button that re-indexes every page into Lucene **only** — no rechunk
  and no re-embedding — for backfilling new Lucene index fields (such as the 2.1.3
  page-id DocValues) across existing segments without the full *Rebuild Indexes* cost.
  Surfaces the existing `POST /admin/content/reindex` endpoint, which previously had no
  UI control.

### Security
A hardening sweep across the read, agent, auth, and deployment surfaces, each change
landing with a failing-first test and gated on the full integration suite.

- **Read-path access control.** REST read endpoints that returned page content/metadata
  without an ACL check — `/api/diff` (full raw page text), `/api/history`, `/api/backlinks`,
  `/api/recent-changes`, `/api/pages`, `/api/search`, and `/api/pages/for-agent` — now enforce
  each page's view ACL (audited 403 for single-page reads; silent visibility-filtering for
  list/search results).
- **Agent-surface access control.** The `/knowledge-mcp` retrieval tools and Knowledge Graph
  tools (`query_nodes`, `search_knowledge`, `get_node`, `traverse`, `find_similar`), plus
  `GET /api/bundle`, now filter to guest-viewable content using the same publicity rule as the
  public RDF surface. `/wikantik-admin-mcp` keeps full (admin) access.
- **Password hashing → bcrypt.** New and changed passwords use bcrypt (cost 12); existing
  salted-SHA-256 / SSHA accounts migrate transparently to bcrypt on their next successful login
  — no reset, no password change, no schema migration.
- **Session-fixation defense.** A successful form login now rotates the `JSESSIONID`.
- **Browser security headers.** `Content-Security-Policy` and `X-Frame-Options: DENY` are now
  emitted on every response (the filters existed but were never registered in `web.xml`).
- **Tomcat hardening.** The bare-metal and container `server.xml` close the open-shutdown-port,
  runtime `autoDeploy`, WAR-context-injection, and error-page information-leak gaps; the dead
  `docker-files/` directory — which committed default `admin/admin` Tomcat Manager credentials —
  was removed. JSESSIONID `Secure` / `SameSite=Lax` / `HttpOnly` hardening is locked in by a new
  config regression guard.

## [2.1.3] - 2026-06-24

### Added
- **Pure `isPermitted()` authorization evaluator + enriched `access.denied` records.**
  `AuthorizationManager` gains an event-free `isPermitted(session, permission)` twin of
  `checkPermission` (both share one private `decide()`), and every *enforced* denial now records
  a `reason` (`no-session` / `policy-denied` / `acl-denied`), the caller's `authStatus`, and the
  `roles` held — merged into the existing audit `detail` JSON. No schema migration: the
  tamper-evident hash chain stays intact and pre-existing rows still verify.
- **Clickable audit-log rows open a record-detail modal.** Admin → Observability → Audit rows are
  keyboard-accessible and open a per-record modal that renders every stored field (target, actor,
  the request `detail` including the new reason/authStatus/roles, sourceIp, userAgent,
  correlationId, and the row/prev hash) — the table itself stays lean.

### Changed
- **Speculative permission checks no longer pollute the audit log.** Visibility/filtering checks —
  search and sitemap, Page-Graph and KG-snapshot, ontology guest view, `[{InsertPage}]`
  inclusion, and the REST capability-hint builders — route through a silent path
  (`isPermitted` / `PermissionFilter.canAccessQuietly` / `RestServletBase.hasPagePermission`);
  only genuine enforcement (a 403/redirect/blocked action) still emits an `ACCESS_DENIED` audit
  row. Previously a single page load fired five `hasPagePermission` checks that each wrote a
  denial row for an action nobody attempted.

### Performance
- **Read path: three allocation/CPU hot spots removed (read-mix p95 −64% in local profiling).**
  (1) `FrontmatterParser` reuses its hardened SnakeYAML parser per-thread (a `ThreadLocal`)
  instead of constructing a new one — compiling ~10 regex `Pattern`s — on every parse.
  (2) BM25 search reads each hit's page id from columnar **DocValues** instead of stored fields,
  removing an LZ4 stored-block decompression per hit (search p95 8.4 ms → 3.98 ms; the dominant
  search allocation → 0; correct via a stored-field fallback on pre-DocValues segments).
  (3) The context retriever reads page metadata through the existing `FrontmatterMetadataCache`
  instead of re-reading and re-parsing every candidate page's frontmatter per query
  (−75% parse allocation; retrieval p95 −23%). Details in `docs/ScalingCharacterization.md` §15.

### Operations
- **The DocValues search optimization needs a one-time index rebuild to take effect.** After
  deploying, run `POST /admin/content/rebuild-indexes` to populate the new page-id DocValues
  field on existing segments. Until then search results stay correct via the stored-field
  fallback — just without the per-hit decompression saving.

## [2.1.2] - 2026-06-21

### Security
- **Vulnerable transitive dependencies pinned to patched versions.** An OSV.dev scan of the
  resolved dependency tree flagged 8 vulnerable transitives; all are remediated compatibly via
  `dependencyManagement` pins (no direct-dependency major upgrade required): BouncyCastle
  1.83→1.84 (CVE-2026-5598/-0636/-5588), commons-beanutils 1.9.4→1.11.0 (CVE-2025-48734),
  Jackson 3 core/databind/dataformat-yaml 3.0.3→3.2.0 (CVE-2026-29062 +2), libthrift
  0.21.0→0.23.0 (CVE-2026-43869), junrar 7.5.8→7.6.0 (CVE-2026-41245), Guava 31.0.1→33.6.0-jre
  (CVE-2023-2976, CVE-2020-8908), and okio 3.2.0→3.17.0 (CVE-2023-3635). A post-pin re-scan
  reports zero known vulnerabilities.
- **Frontend build toolchain upgraded** (Vite 5.4→7.3.5, Vitest 1.6→4.1.9, @vitest/coverage-v8
  4.1.9, @vitejs/plugin-react 5.2.0), clearing 5 npm advisories (2 critical, 1 high, 2 moderate)
  in the dev/build toolchain — none of which ships in the production bundle.

### Changed
- **Routine compatible dependency and plugin upgrades:** anthropic-java 2.42.0, Micrometer 1.17.0,
  Tika 3.3.1, jaxen 2.0.6, Selenium 4.45.0 (test), SpotBugs 4.10.2, JaCoCo 0.8.15, and
  maven-site-plugin 3.22.0.

## [2.1.1] - 2026-06-21

### Added
- **First-class `all` (AllPermission) policy-grant type.** Admin → Security's "Grant AllPermission"
  control now round-trips end-to-end: the validator accepts `permissionType: all` (it was previously
  rejected, silently breaking the toggle). To keep the model unambiguous, AllPermission is expressed
  **only** via the `all` type, under strict rules — it must pin `target='*'` / `actions='*'`, and it
  cannot be granted to the built-in broad roles (`All`/`Anonymous`/`Asserted`/`Authenticated`),
  closing a one-typo "everyone is an admin" misconfiguration.
- **`access.denied` audit records now carry full forensic context.** Authorization denials record the
  *resource and attempted action* as the audit target (e.g. `edit → SecretPage`, or `all` for
  admin-surface denials), plus `sourceIp`, `userAgent`, `correlationId`, and a `detail` with the exact
  endpoint (URI/method) — sourced from the denied `Permission` and the request-thread MDC. The same
  request-context enrichment now applies to every audited security event, so `login.failed` also
  surfaces source IP and user-agent (brute-force visibility). Forward-only: no schema migration, and
  previously these rows showed a null target. Visible in Admin → Observability → Audit.

### Changed
- **Wildcard `*` actions are rejected on scoped (`page`/`wiki`/`group`) grants.** Such a grant
  silently resolved to AllPermission at runtime (a footgun); use the `all` type to grant AllPermission
  instead. Existing grants keep working at runtime (back-compat preserved). Migration `V043` converges
  the default Admin `page`/`wiki` wildcard rows onto a single canonical `all` row — conservatively, so
  a deliberately locked-down install is never re-granted.

## [2.1.0] - 2026-06-20

### Added
- **Corpus math-syntax fixer.** `MathSyntaxFixCli` (with `MathSyntaxFixer`) batch-repairs the page
  corpus: it escapes prose-currency `$` and reformats single-line `$$ x $$` into blank-line-isolated
  display blocks. It mirrors `MathStructureValidator` exactly — escaping the opening `$` of a
  prose-flagged inline pair only when it is followed by a digit, iterating to a fixpoint — so
  number-led math (`$2^{256}$`, `$90^\circ$`) is never broken.

### Changed
- **Documentation refreshed for the current architecture.** `WikantikArchitecture` was rewritten as
  a deep architecture reference (the module reactor, the three-graph model, the retrieval stack, the
  knowledge + ontology layer, and the agent MCP surface, with strengths, an honest critique, and a
  roadmap) and the front page was reframed around the human + AI-agent value proposition. The README,
  the marketing site, and the linked docs now describe production retrieval as BM25 + dense fused
  with RRF (fail-closed); the Knowledge-Graph-aware rerank is shelved and off by default, having
  measured no net ranking lift. MCP tool counts were corrected throughout (knowledge-mcp 20 tools,
  admin-mcp 26).

### Fixed
- **Frontmatter save-warnings no longer leak across pages.** `FrontmatterWarningSink` was a flat
  per-thread slot, so a nested or concurrent save of a different page could clobber the outer page's
  warnings (surfacing a foreign "summary is 188 chars" warning on `update_page`). The stash is now
  keyed by page name, so `update_page`, `write_pages`, and `PageResource` each drain exactly their own.

## [2.0.21] - 2026-06-20

### Removed
- **Legacy property-file API keys removed.** The `mcp.access.keys` and `tools.access.keys`
  comma-separated property keys are gone — not deprecated. DB-minted keys (via `/admin/apikeys`)
  are now the only Bearer-token key source. The CIDR allowlist (`*.access.allowedCidrs`) and
  `*.access.allowUnrestricted` flags are unchanged. **Operators must have at least one DB-minted
  key before upgrading** or the endpoint fails closed with HTTP 503.

### Added
- **Retrieval-aware content authoring.** `verify_pages` gains a `retrieval_readiness` check
  (summary specificity, heading quality, cluster, title — the frontmatter levers prepended into
  chunk embeddings) and a new `list_retrieval_queries` admin-MCP tool over the real query log
  (V041) for finding under-served queries. The `wiki-content` skill now documents the
  author → static-lint → live-bundle-check verification loop.
- **Self-service API keys.** Logged-in users can list, generate, rotate, and revoke their own
  API keys from the **API Keys** section of `/preferences`, backed by a new ownership-enforced
  `/api/self/apikeys` resource. Keys are bound to the caller's own principal (no privilege
  escalation), secrets are shown once, and storage stays hashed-only — "recovery" is by reissue.
- **Last-login column in admin → Users.** A new `users.last_login` column (V042) is stamped on
  every successful authentication (form login, SSO, remember-me re-auth) and shown next to
  *Created*; accounts that have not authenticated since the column shipped render as an em dash.

### Fixed
- **`update_page` (admin-MCP) is merge-safe.** Metadata now merges onto the page's existing
  frontmatter instead of silently wiping it, and `content` is optional — omit it for a
  metadata-only edit that leaves the body untouched.
- **Embeddings self-heal on startup.** Stale or dropped chunk embeddings (from a bulk edit or a
  crash mid-re-embed) are reconciled at boot, so retrieval converges without a manual rebuild.

## [2.0.20] - 2026-06-19

### Added

- **Retrieval query logging.** Every retrieval query across `/api/bundle`, `/api/search`,
  `assemble_bundle` (knowledge-mcp) and `search_wiki` (`/tools`) is captured to a new
  `retrieval_query_log` table — query text, inferred actor (`human` | `agent` | `unknown`),
  source surface, and result count — to ground the section-recall eval corpus in real traffic.
  Human vs agent is inferred from surface + auth (MCP/tools are agent by construction; `/api` is
  `human` when session-authed, `agent` when `Authorization`-header'd, else `unknown`). Writes are
  asynchronous and fail-open — a logging failure never slows or breaks retrieval — and capture
  query text only (never results), so restricted content never enters the log. Default ON via
  `wikantik.querylog.enabled` (migration `V041`).

### Removed

- **Lexical-injection retrieval stack + superseded eval spikes.** The shelved code-symbol
  lexical-injection path (`LexicalInjectionSource`, `InjectionConfig`, `SymbolDetector`, the
  `wikantik.bundle.inject.*` knobs) was removed after the retrieval-experiment review found the base
  hybrid already handles ~88% of identifier queries (revive from git history if real agent traffic
  shows code-symbol queries). Twelve measured-and-rejected one-shot eval spike scripts were also
  deleted; their verdicts are banked in `eval/bundle-corpus/baseline-notes.md`.

### Fixed

- **Two benign startup ERROR logs eliminated.** (1) A malformed wikilink with a whitespace-only
  target (e.g. illustrative `[ ]( )` syntax inside a code span, which the regex scanner still
  matched) produced a blank page reference and logged `Illegal page name: ''` during reference-graph
  init — `MarkdownLinkScanner.findLocalLinks` now drops blank link targets, and the `related:`
  frontmatter scan filters whitespace-only entries. (2) A non-absolute `mcp.instructions.file` is now
  ignored quietly (the override must be an absolute path) instead of logging an ERROR before the
  (correct) fallback to the bundled instructions; a genuinely-unreadable absolute path now logs WARN.

## [2.0.19] - 2026-06-16

### Added

- **Derived pages — document ingestion (RAG-as-a-Service Phase 2).** PDFs, office documents
  (docx/pptx/xlsx), and text/markdown files become first-class wiki pages: the source binary is
  retained as an attachment, the body is **extracted via Apache Tika** (XHTML→markdown), and the
  page carries `derived_from` provenance frontmatter — riding every existing rail (search,
  embeddings, Knowledge Graph, ontology, chunking, the context bundle). The pure extractor lives in
  a new **`wikantik-ingest`** module (isolates the heavy PDFBox/POI dependencies). Ingestion is
  idempotent (filename-named page, update-in-place, source-SHA dedup); the body is **machine-owned
  and regenerable** (ADR-0004) — human edits are at-own-risk, version history is the recovery path.
  Surfaces: **`POST /api/ingest`** (multipart upload, `createPages`-gated), a batch
  **`IngestDocumentsCli`** (HTTP client over `/api/ingest`), and **`/admin/derived/{reflow,status}`**
  — reflow re-extracts from the retained source, clobbering the body while preserving
  body-independent curation (tags, verification, KG). A frontend "ingest as derived page" action +
  an editor "machine-owned body" banner. New code in `com.wikantik.ingest.*` (module) and
  `com.wikantik.derived.*` (wikantik-main). Config: `wikantik.citations.enabled` unaffected; Tika 3.3.0.

- **Citation edges + self-healing grounding (RAG-as-a-Service Phase 3).** Claims grounded in
  other pages are written as inline `cite://` body markup —
  `[claim](cite://<canonical_id>/<Heading Path> "verbatim span")` — and parsed at save into a
  derived, re-derivable `citations` table (migration `V040`). Each citation is version-pinned
  and span-hashed; staleness is **graded** and **span-level**: `current` → the cited span is
  still present in the target section, `stale` → the span drifted (content changed / heading
  moved), `target_missing` → the target page is gone (rename-safe via `canonical_id` liveness).
  Version drift alone is ignored (churn is the steady state). A `WikiEventListener` reconciles a
  page's outbound citations and re-grades inbound citations on every save/rename/delete, and a
  full `reconcileAll()` rides the ontology-rebuild cadence as the completeness safety net.
- **Bidirectional stale-citation surfaces.** `GET /admin/drift/citations` (outbound + inbound +
  status counts), the read-only **`list_stale_citations`** tool on `/knowledge-mcp`, and a
  `stale_citations` field on the `/api/pages/for-agent/{id}` projection — so agents and humans
  work the same self-healing curation queue. Rendered `cite://` links resolve to the target
  page; staleness is never shown to anonymous readers. Config: `wikantik.citations.enabled`
  (default true; no-op without a datasource). New code in `com.wikantik.citation`.

### Security

- **Page authorship is now derived from the authenticated session, not caller input.** `POST /api/ingest`,
  `PUT /api/pages`, and `/admin/derived/reflow` record the author as the session principal; a caller-supplied
  `author` (query param or request body) is ignored. Closes an authorship-spoofing / audit-forgery gap.
- **Document-upload surface hardening (derived pages).** Upload filenames are sanitized into a safe page name
  via the same `MarkupParser.cleanLink` the attachment store uses — preventing path traversal and arbitrary-page
  overwrite (ingest now refuses to overwrite a non-derived page). Tika extraction is bounded by a write limit +
  timeout so a malicious upload cannot OOM or hang the server. A failed attachment store rolls back a
  newly-created page (no orphans). The batch CLI uses HTTP Basic auth (the only scheme `/api/*` accepts).

## [2.0.18] - 2026-06-14

### Added

- **RAG-as-a-Service context bundle.** `GET /api/bundle?q=…` (REST) and the
  `assemble_bundle` tool on `/knowledge-mcp` return an assembled **context bundle** —
  a ranked, de-duplicated, **version-pinned-cited** set of wiki sections for a query,
  for grounding an agent. It does **not** synthesize an answer. Each section carries a
  `CitationHandle` (canonical_id + page version + span SHA-256). Backed by the new
  `com.wikantik.knowledge.bundle` layer.
- **Bundle-quality evaluation harness** — a frozen, section-level gold corpus
  (`eval/bundle-corpus/`) plus a deterministic recall / precision@K / citation-faithfulness
  runner, wired as a pre-merge gate.
- **Contextual document embeddings.** Each chunk is embedded with its page context
  (`Page: {title} | Cluster: {cluster} | Section: {heading-path}` + summary, from
  frontmatter) prepended — the largest single retrieval-recall lever (global
  section-recall@12 ≈ 0.60 → 0.74). The query side keeps its instruction prefix.
- **Configurable chunking knobs** — `wikantik.chunker.fragment_floor_tokens` (default 24)
  and `wikantik.chunker.overlap_tokens` (default 40); bundle knobs
  `wikantik.bundle.{dense.enabled, dense.top_k, sections_per_page, reranker.enabled}`.

### Changed

- **Bundle retrieval is global dense-chunk by default** — the top-K chunks across the
  whole corpus, grouped to sections, instead of a page-gated shortlist. It realizes the
  retrieval ceiling that the page pre-select was capping (realized bundle recall@12
  0.50 → 0.71 across the release's retrieval work).
- **Knowledge-graph extraction defaults to `gemma4-graph:12b`** with `think:false` sent
  on every Ollama extraction/judge request (a reasoning trace breaks structured-JSON
  extraction and is 10–20× slower).
- **KG rerank stays off** (`graph.boost = 0.0`) pending a Phase-4 fair trial on relational
  questions — replacing a stale `boost=0` "TEMP DIAGNOSTIC" override with an explicit state.

### Fixed

- **Chunker heading fidelity.** The chunker's merge-forward logic let a short preamble
  carry the *first* section's `heading_path` onto later sections' content, so early /
  first-`##` sections were unfindable by their own heading **and their citations were
  mis-anchored**. Each chunk's `heading_path` now matches the section its content came
  from. (Requires a content rebuild to take effect on existing pages.)

## [2.0.17] - 2026-06-12

### Added

- **Breadcrumb is now a clickable navigation-history trail.** The reader
  breadcrumb shows the last 3 distinct pages visited in the tab (oldest → newest);
  prior pages link to `/wiki/{slug}` and the current page is the last entry. Backed
  by a per-tab `usePageTrail` hook (sessionStorage — survives refresh, fresh per
  tab, works for anonymous readers). The SEO `BreadcrumbList` JSON-LD emitted
  server-side stays hierarchical and is intentionally unaffected.
- **Knowledge MCP `sparql_query` gains an optional `format: "compact"`** that
  returns token-dense flat `{var: value}` rows for `SELECT` queries.
- **`list_tags` / `list_clusters` are paginated** (`limit` default 50, `offset`,
  with `count` / `returned` / `hasMore` in the response).

### Changed

- **MCP page-identifier parameters converge on `slug` / `slugs`** across the admin
  and knowledge tool surfaces. Legacy `pageName` / `pageNames` (and guessable
  `name` / `page`) are still accepted as aliases via shared `pageSlug` /
  `pageSlugs` accessors, but only `slug` / `slugs` are advertised in the schemas;
  a convergence-guard test keeps it that way.
- **Stricter math validation on save.** Single-line or text-glued `$$ … $$`
  display math (which the parser mis-renders) is now a blocking **ERROR**, and
  prose inside inline `$…$` (e.g. an unescaped currency `$`) raises an advisory
  **WARNING**, both false-positive-guarded.
- **`ping_search_engines` is annotated as not read-only** (`readOnlyHint=false`,
  `openWorldHint=true`).

### Fixed

- **Blocked-save errors on non-common frontmatter fields are now visible.** The
  editor auto-expands the "More fields" disclosure when a field inside it has a
  validation error, so a 422-blocked save always shows its reason (regression from
  the 2.0.16 density redesign, which hid those inline errors inside the collapsed
  section).
- **Further corpus formula-rendering repairs** — isolated single-line `$$` display
  math across additional pages and resolved the remaining currency-`$` / glued-`$$`
  defects surfaced by the production render audit.

## [2.0.16] - 2026-06-12

### Added

- **Math (LaTeX) validation on save.** A KaTeX-oracle-derived LaTeX syntax linter
  (`MathValidationPageFilter`) runs on every page save, flagging malformed or
  un-isolated display math as blocking **errors** or advisory **warnings**. The
  violations are surfaced inline in the editor (`MathValidationSummary`, with
  click-to-jump), returned as structured `ContentViolation`s from the REST save
  path (`PageResource`), and enforced on the admin MCP write tools
  (`write_pages` / `update_page`).

### Changed

- **Structured frontmatter editor is far denser.** Fields are split into an
  always-open **Common** block (title/type/status/summary/tags/cluster) and a
  collapsible **More fields** disclosure (whose summary shows a `(N set)` count of
  populated fields); the read-only derived fields (canonical_id/confidence/
  agent_hints) move to a compact muted meta strip; every field is an inline
  `[label][control]` row. The default footprint shrinks ~65% (≈700–900px → ≈475px).
- **Page-scoped Knowledge Graph tab densified.** Tighter rows, smaller embedded
  type-selects and provenance badges, relation rows aligned compact-left, and a
  compact empty-state so empty Entities/Relations sections no longer reserve ~128px
  of padding (all-empty tab ≈561px → ≈228px).
- **Database application role defaults to `wikantik`**, and deploy-time migrations
  run as the owning role.

### Fixed

- **Corpus-wide math rendering repair** — isolated inline-glued display-math
  delimiters across 102 pages, and CRLF is normalized before math validation.
- **`bin/redeploy.sh` migration step.** It hardcoded a nonexistent `migrate` DB
  role with no password and never sourced `.env`, so the migration step failed and
  aborted every local redeploy before Tomcat started. It now sources `.env` and
  runs migrations as the app role (`POSTGRES_USER`, default `wikantik`) with a
  `postgres` superuser fallback, mirroring `deploy-local.sh`.

## [2.0.15] - 2026-06-11

### Added

- **First-login forced password change.** A fresh install seeds exactly one
  account — `admin` / `admin123` — flagged so the first login *requires* choosing
  a new password before anything else works. The general-purpose
  `users.password_must_change` flag (migration V039) is also raised when an
  administrator sets or resets a user's password and when the password-reset email
  issues a temporary one; it is cleared when the user changes their own password
  (NIST-validated, so the seeded default cannot be re-chosen). A
  `MustChangePasswordFilter` gates `/api/*` and `/admin/*` with
  `403 PASSWORD_CHANGE_REQUIRED` for flagged sessions — exempting only the auth
  surface needed to fix the situation — and the SPA routes flagged users to a
  forced `/change-password` screen (at login and mid-session).
- **Getting Started guide** (`docs/GettingStartedGuide.md`) walking first-time
  deployers through both the Docker Compose and bare-metal Tomcat paths, with a
  first-build pitfalls table.
- **First-start banners** in `deploy-local.sh` and the container entrypoint that
  print the initial credentials and the forced-change notice on a fresh database.

### Changed

- **Single canonical admin seed across both install paths.** The container init
  SQL (`docker/db/001-init.sql`) is reduced to enabling `pgvector`; schema and the
  admin seed now come exclusively from the numbered migrations via `migrate.sh`.
  This removes the long-standing split where a fresh container seeded a *different*
  admin password (`admin`) than the migrations (`admin123`). `seed-users.sql` is
  now admin-only and insert-if-absent, so a changed password survives every
  redeploy; personal/dev accounts move to a gitignored `seed-users.local.sql`.
- **`install-fresh.sh` fails fast on missing secrets.** It no longer defaults
  `DB_APP_PASSWORD`, and requires either `DB_MIGRATE_PASSWORD` or an explicit
  `--no-migrate-role` opt-out — surfacing the schema-ownership decision up front
  instead of as a failing ALTER migration later. Docs (`CLAUDE.md`, README, Docker
  and PostgreSQL deployment guides) were aligned to the single `admin / admin123`
  forced-change credential story and a consistent `wikantik` local DB user.

### Security

- No Wikantik install runs with a known default credential past first login: the
  seeded admin must set a password before any gated API call succeeds, and
  `DELETE /api/auth/*` (account self-deletion) stays gated for flagged sessions so
  a hijacked first-login session cannot delete the account in lieu of changing the
  password.

### Fixed

- **Scheduled off-box backups never ran (day-1 bug).** BusyBox `crond` in the
  backup sidecar silently ignored the bind-mounted crontab because the
  git-checkout file is group-writable and not root-owned; every snapshot since the
  feature shipped was a manual trigger. The compose entrypoint now stages the
  crontab to `/etc/crontabs/root` as `root:root 0600` before starting `crond`, and
  the proven `/proc/1/environ` env-import in `backup.sh` (cron strips the compose
  environment) is now committed.

## [2.0.14] - 2026-06-10

### Added

- **Live validation in the structured frontmatter editor.** Debounced, race-safe,
  fail-open validation against `/api/frontmatter/validate` surfaces errors and
  warnings inline as you edit. Save is disabled while blocking (ERROR-severity)
  issues exist; advisory warnings never block. Runbook sub-field violations now
  render against the matching control, with a validation-summary strip
  (counts + jump-to-field). The editor CSS was rebased onto the app's design
  tokens with a denser two-column grid.
- **Synonym-aware entity-type classification.** Both extractors resolve common
  LLM type synonyms (`database`/`framework`/`library`/`tool`/`service`/… →
  `technology`, plus organization/person/place/event/… synonyms) via a shared
  `EntityTypeVocabulary.TYPE_ALIASES`, instead of collapsing them to `concept`
  (chunk extractor) or dropping them (page extractor) — the source of mis-typed
  technologies under the `wk:implements` SHACL shape.

### Fixed

- **Audit writer dropped every batch under a least-privilege DB role.**
  `JdbcAuditRepository.ensurePartition` ran `CREATE TABLE … PARTITION OF` on every
  append; that DDL fails with `permission denied for schema public` for an app role
  with `USAGE` but not `CREATE` on schema `public` (the correct posture, and the
  PostgreSQL 15+ default), even when the partition already exists. It now checks
  existence first via a privilege-free `to_regclass` lookup.
- **Runbook frontmatter validator rejected legitimate `related_tools` entries.**
  `/admin/*` REST surfaces and kebab-case CLI tool names (`kg-policy`, `kg-extract`)
  are now accepted; a runbook validation failure returns a structured HTTP 422
  (field-addressable violations) instead of an opaque error, for all clients.

## [2.0.13] - 2026-06-08

### Fixed

- **MCP `update_page` returned a stale, unusable content hash.** The
  `newContentHash` was computed from a tool-side *reconstruction* of the submitted
  text, but the save-time `StructuralSpinePageFilter` rewrites the persisted bytes
  afterwards (canonical_id injection, frontmatter/date/line-ending normalization).
  The returned hash therefore drifted from what `read_page` reports, so an agent
  that chained a second edit using it hit a false `hash mismatch`. `update_page`
  now hashes the **actual persisted text** (re-read via `getPureText` — the same
  source `read_page` and the optimistic-lock check use), so the returned hash is
  authoritative and directly chainable. Tool docs corrected: content hashes are
  bare lowercase hex (no `sha256:` prefix) and come from `read_page`.

### Changed

- **Audit event listener** refactored to table-driven dispatch (declarative lookup
  maps replace the security/page-event `switch` blocks); behavior unchanged. Adds
  unit coverage for the audit mappings and for ten previously untested
  `wikantik-api` value/exception classes.
- **IndexNow verification key** rotated (ownership re-verification); the new key is
  served at the web root.

## [2.0.12] - 2026-06-07

A focused SEO / crawlability / rendering release. The headline fix: Google was
classifying every `/wiki/` page as a **Soft 404**, so none of them ranked.

### Added

- **SEO: social/sharing images.** Every page emits `og:image` + `twitter:image`
  (`twitter:card=summary_large_image`) — a per-page frontmatter `image:` when set, else a
  bundled 1200×630 default (`og-default.png`) — so links unfurl with a card on
  Slack/X/LinkedIn and qualify for Google Discover.
- **SEO: sitelinks search box.** The homepage emits `WebSite` + `SearchAction` JSON-LD so
  Google can show a search box for branded queries.
- **SEO: fuller SERP snippets.** `<meta name="robots" content="max-image-preview:large,
  max-snippet:-1, max-video-preview:-1">` on every page (does not affect indexability).
- **IndexNow.** `ping_search_engines` can now notify Bing/Yandex: `WIKANTIK_INDEXNOW_API_KEY`
  is wired through the container entrypoint to `wikantik.indexnow.apiKey`, with the key
  served as a static verification file at the web root.
- **SSR data island.** `SpaRoutingFilter` injects `window.__WIKANTIK_PAGE__` (the page's
  rendered HTML + metadata) so the React reader paints content immediately instead of
  refetching `/api/pages` — and crawlers' JS renderers never see an empty "Loading…" DOM.
- **Footer build version.** The site footer now shows the running build version.

### Fixed

- **Soft 404 across the wiki.** The reader refetched content from
  `/api/pages/{name}?render=true`, but `robots.txt` blocked all of `/api/`, so Google's
  renderer fetched nothing and saw an empty page. `robots.txt` now `Allow: /api/pages/`
  (ordered before the broad `Disallow: /api/`); the JSON stays `X-Robots-Tag: noindex`.
- **HTTP 404 for missing pages.** `/wiki/{nonexistent}` now returns HTTP 404 (with the SPA
  shell body so React still renders its NotFound view) instead of 200, so search engines
  drop dead URLs instead of soft-404'ing them.
- **baseURL for request-less tools.** `ping_search_engines` and `preview_structured_data`
  now read the configured `wikantik.baseURL` instead of the empty ROOT-context servlet
  context path, so they build absolute sitemap / IndexNow / structured-data URLs.

### Changed

- **Duplicate-content guards.** `/wiki/{slug}?format=md|json` responses are now
  `X-Robots-Tag: noindex` + `Link: rel="canonical"` to the HTML page.
- **robots.txt** blocks thin / auth-only routes: `/page-graph`, `/knowledge-graph`,
  `/login`, `/me/mentions`.
- **Crawlable no-JS body.** `SpaRoutingFilter` injects the full rendered HTML into `#root`
  (rendered once, reused by the data island) so non-JS crawlers get real content rather
  than a raw-text fallback.
- **Reduced layout shift (CLS).** The Similar-Pages and Backlinks panels render below the
  article instead of above it, so their async load no longer pushes the LCP element down.
- **Richer publisher metadata.** Article / CollectionPage JSON-LD `Organization` publisher
  now includes `url` + `logo`.

## [2.0.11] - 2026-06-06

### Added

- **AdSense `ads.txt`.** Added the Google authorized-sellers record
  (`google.com, pub-5083997587716933, DIRECT, f08c47fec0942fa0`) at the root of both
  served domains so AdSense can verify ad ownership: `wikantik-war/src/main/webapp/ads.txt`
  (served verbatim at `https://wiki.wikantik.com/ads.txt` — `SpaRoutingFilter` passes any
  `.`-bearing, non-`.html` path through to the default servlet, so it isn't swallowed by
  SPA routing) and `marketing/ads.txt` (served at `https://www.wikantik.com/ads.txt`; the
  apex `wikantik.com/ads.txt` reaches it via the existing CF apex→www redirect). A
  build-time guard (`AdsTxtTest`) pins the exact publisher line and fails closed on HTML
  contamination.

- **test:** opt-in parallel IT execution. `bin/run-tests.sh --it --parallel N` (or the
  `IT_PARALLELISM` env var) runs all four IT modules in a single `-T N` reactor; each
  module now reserves its own free TCP ports (Postgres, Cargo servlet + RMI + AJP,
  OIDC/SAML mock servers) via build-helper and uses a per-module-unique pgvector container
  name, so they no longer collide on the shared `55432`/`18080`/`8205`/`8009` ports.
  Module configs (`jdbc.url`, OIDC `discoveryUri`, `wikantik.baseURL`) were parameterised
  on the reserved ports. Default behaviour is unchanged (sequential, one module at a
  time); `--parallel 4` cuts the IT phase from ~3.5 min to ~1.5 min.

- **test:** parallelize an audited cluster of read-only browser ITs (JUnit-5 `@Execution(CONCURRENT)`) to trim custom-jdbc wall-clock.

- **Audit log retention purge.** `bin/db/audit-retention.sh` (scheduled monthly via a
  systemd timer, `bin/db/audit-retention-install-timer.sh`) pre-creates upcoming
  `audit_log` partitions and **archives-then-drops** partitions older than a
  configurable window (default **7 years**): each over-age partition is `pg_dump`ed to
  the off-box-backed archive directory and verified before it is dropped. Runs as the
  privileged `migrate` role (the app role stays INSERT/SELECT-only); the hash chain
  re-anchors on the oldest surviving row with no application-code change. A `< 1`-month
  guardrail and `--dry-run`/`--status` flags guard against accidental mass-drops.
- **SCIM 2.0 group provisioning** (`/scim/v2/Groups`). An IdP can sync group
  membership (which drives Wikantik ACLs / policy grants) via SCIM: create / read /
  list+filter (by `displayName`) / `PUT` / `PATCH` (member add, remove via
  `members[value eq "<uid>"]`, replace) / hard `DELETE`. Member changes flow through
  the audited `GroupManager` path. **Hard invariant: SCIM Groups never grant the
  Wikantik `Admin` role** — groups and the role table are separate stores, enforced by
  an integration-test assertion. `externalId` is keyed on `displayName` (not persisted).
- **SCIM 2.0 user provisioning** (`/scim/v2/Users` + discovery endpoints). An IdP
  (Okta/Entra) can automate onboarding and offboarding via bearer-authed SCIM. All
  deactivate/reactivate flows go through one unified, audited `UserLifecycleService`
  (shared by the admin UI and SCIM): `active:false` and `DELETE` both soft-decommission
  via the existing indefinite-lock mechanism (the user row is retained so audit and
  page-ownership references stay intact), `active:true` reactivates. SCIM-created users
  reconcile with SSO via the `sso.subject` marker (fail-closed on a non-SSO name
  collision). New `wikantik-scim` module. See
  [ScimProvisioningDesign](docs/wikantik-pages/ScimProvisioningDesign.md).

### Changed

- **Unified modal dialog styling.** The rename and delete dialogs now match the New
  Article / Login dialog family: centered display-font heading, muted field labels,
  bordered inputs, and a right-aligned action row. Extracted shared `.form-input` and
  `.field-label` classes and promoted `.modal-actions` into `globals.css` so reader-side
  dialogs pick them up regardless of route; `Modal` now honours a `style` prop, and the
  `search-dialog` double-padding was removed. The Graph/Edge explorer filter inputs gain
  real styling (the `.form-input` class was previously undefined).

### Fixed

- **Integration-test gate restored.** The post-2.0.10 dynamic-ports refactor left
  `wikantik-selenide-tests` without the `build-helper` re-declaration (so its Cargo
  servlet port resolved to empty → "Invalid port number"), and the SCIM sample fixtures
  were committed under `wikantik-it-test-rest` rather than the shared selenide
  test-resources directory every IT module reads from (so `ScimVendorPayloadIT` failed
  with "missing fixture"). Both fixed; the full IT reactor is green again.

- **Audit hash chain now verifies for events with a `detail` payload.** `detail` was
  stored as JSONB, which PostgreSQL reformats on read, so the verify-time rehash no
  longer matched the insert-time hash — any audited event carrying `detail` (e.g.
  `page.rename`, SCIM `user.deactivate`) broke `verifyChain`. `detail` is now stored as
  TEXT (exact round-trip; migration V037). Also fixed `page.rename` events never being
  audited (the listener was not registered against the `PageRenamer`).

- **Tamper-evident audit log.** A compliance-first, append-only audit trail
  capturing authentication/authorization (login, logout, session expiry, access
  denied), content changes (page save/delete/rename), admin/security-config
  actions (policy-grant changes, user enable/disable, API-key issuance), and
  opt-in sensitive page reads (frontmatter `audit_reads: true` or a configured
  cluster set, default off). Records are written by a single async writer under a
  PostgreSQL advisory lock and chained with SHA-256 (each row hashes the previous
  row's hash), so any edit or deletion of history is detectable. The `audit_log`
  table is month-partitioned and `INSERT`/`SELECT`-only to the app role
  (`UPDATE`/`DELETE` revoked). New admin surface: `GET /admin/audit` (filterable),
  `GET /admin/audit/verify` (chain integrity), `GET /admin/audit/export?format=csv`,
  and an **Audit** tab in the admin panel. Dropped-entry count is exposed as the
  `wikantik_audit_dropped_total` gauge. See
  [AuditLogDesign](docs/wikantik-pages/AuditLogDesign.md).

## [2.0.10] - 2026-06-02

### Changed

- **Side-by-side editor overhaul.** The source and preview panes now share one
  fixed-height region and each scroll internally (the preview no longer clipped
  early while the source grew). Scrolling/typing in either pane keeps the other
  aligned (bidirectional, frontmatter-zone-aware), and clicking a block in the
  preview jumps + centers the editor caret on that block's source line. The
  stripped frontmatter is shown as a compact collapsible card atop the preview.

### Fixed

- **Theme toggle now updates the editor pane immediately.** `useDarkMode` held
  per-instance state, so toggling in the sidebar left the editor's CodeMirror on
  its old theme until a refresh; all consumers now share one store.

### Dependencies

- npm: react-markdown 9→10, react-router-dom 6→7, plus minor/patch bumps
  (cytoscape, happy-dom, katex). React 19 and the vite 8 / vitest 4 toolchain
  deferred (real migrations).
- Maven GA minor/patch: junit 6.1.0, selenide 7.16.2, pac4j 6.5.3,
  anthropic-java 2.35.0, jaxen 2.0.5, jaxb-runtime 4.0.9, plus surefire,
  maven-dependency, and sonar plugins.

## [2.0.9] - 2026-06-02

### Changed

- **Sidebar cluster navigation** is consolidated under a single collapsible
  **"Browse Clusters"** section (collapsed by default) rather than a long stack
  of per-cluster sections; the active page's cluster still auto-expands inside.
  Its header matches the other section titles, top-level nav titles now use full
  text colour, and the expanded cluster tree has tighter spacing.

### Security

- **HTTP response-splitting guard** on the SPA `Location` redirects in
  `SpaRoutingFilter` — request-derived path/query fragments are rejected if they
  contain CR/LF before being written to the redirect header.

### Fixed

- SpotBugs `WMI_WRONG_MAP_ITERATOR` in `WatchDog`, `AbstractFileProvider`, and
  `DefaultKnowledgeGraphService` (iterate `entrySet()` instead of `keySet()` + `get()`).

### Internal

- Cut `PageForAgentResource.toJson` from an NPath complexity of ~110k to
  straight-line by extracting per-section JSON builders (wire format unchanged).
- Removed duplication flagged by CPD: shared `KgProposalRepository` filter clause,
  an `AbstractSpamStrategy` base for the spam helpers, a shared `ProposalVerdictParser`
  for the Claude/Ollama judges, and `denseRanking` lifted into `ExperimentHarness`.

## [2.0.8] - 2026-06-01

### Added

- **Backlinks panel.** The reader page view now shows a "Referenced by" panel
  listing the pages that link to the current page (backed by `/api/backlinks`).
- **Collapsible cluster tree** in the reader sidebar — clusters are now
  expandable/collapsible sections (state persisted), with the active page's
  cluster auto-expanded and an "Uncategorized" bucket for clusterless pages.
- **Search faceting.** The search results page gained a filter rail
  (topic / author / tag / modified-date) that narrows results client-side.
- **Editor insert helpers.** Table and fenced-code-block toolbar buttons, plus
  `[[`-triggered autocomplete that inserts internal `[Name](Name)` wiki links
  from the page list.

### Fixed

- **Sidebar cluster grouping.** `/api/pages` returned a cluster for only the
  first 100 pages (the structural-index query silently capped at 100), so the
  sidebar dumped most pages into "Uncategorized". It now reads the full sitemap
  projection — cluster coverage went from 100 to 1161 of 1204 pages.

## [2.0.7] - 2026-05-31

## [2.0.6] - 2026-05-30

## [2.0.5] - 2026-05-25

### Fixed

- **Search-engine indexing.** `robots.txt` advertised the sitemap on the wrong
  host (`wiki.jakefear.com`), so cross-domain rules made Google ignore it;
  it now points at `https://wiki.wikantik.com/sitemap.xml`. Every wiki page
  served the generic `<title>Wikantik</title>` — pages now emit a unique
  `<title>` from their frontmatter `title:` (falling back to the page name),
  and that readable title also flows into `og:title`/`twitter:title` instead of
  the raw page slug. Operator guide: [docs/SeoAndCrawling.md](docs/SeoAndCrawling.md).

## [2.0.4] - 2026-05-25

### Added

- Site footer on reader pages (including the home page) linking to the privacy
  policy and terms of service, satisfying OAuth-provider home-page requirements
  for SSO app verification.

## [2.0.3] - 2026-05-24

### Added

- **Single Sign-On (OIDC + SAML 2.0 via pac4j).** Google OIDC is live in
  production. Configurable through `wikantik.sso.*` (and `WIKANTIK_SSO_*` env
  vars in containers); full operator reference in
  [docs/SingleSignOn.md](docs/SingleSignOn.md). Includes a `/login` SPA route
  that surfaces SSO `?error=` codes, and public privacy/terms pages for
  provider onboarding.
- **Self-service account deletion** — `DELETE /api/auth/profile` with a
  preferences-page UI, a lockout-safe last-admin guard, and cascade cleanup of
  the user's group memberships and API keys.
- **Admin UI refresh** — grouped context-swap sidebar, a sectioned Overview
  dashboard landing page, and hybrid table density.
- **Off-box backup & recovery** — pull-model NAS backups, per-tier Prometheus
  textfile metrics, restore-drill verification, and `bin/dr-restore.sh` for
  one-command disaster recovery to a fresh host.

### Security

- SSO identity binding keys on the immutable `wikantik.sso.identityClaim`
  (default `sub`); auto-provisioned profiles carry an `sso.subject` marker and
  a name collision with a non-SSO local account **fails closed**. Successful
  SSO login rotates the HTTP session (fixation defense); IdP claims are
  sanitised (multi-valued normalised to first scalar, blank/control-character
  login names rejected).
- Anonymous and stateless API-key requests no longer create `HttpSession`s
  (fixes a session-leak regression).

## [2.0.2] - 2026-05-22

### Added

- **Lucene HNSW dense-retrieval backend** (`wikantik.search.dense.backend=lucene-hnsw`),
  now the production default — an in-process approximate-nearest-neighbour index
  (RAM `ByteBuffersDirectory`, rebuilt on boot from `content_chunk_embeddings`)
  that replaces the brute-force vector scan. Tunable via
  `wikantik.search.dense.lucene.{m,ef_construction,ef_search}`; rollback is a
  one-line flip to `inmemory`. Recall held within 0.02 nDCG@5 of brute force.

### Performance

- Dense retrieval no longer scans every chunk per query (had been ~60% of search
  CPU); the HNSW index visits a few hundred candidates, with chunk metadata read
  via Lucene DocValues (no per-hit stored-field decompression).
- Eliminated DB connection-pool exhaustion under load by caching the per-request
  lookups that drained it — API-key verification, user lookup by login name, and
  Knowledge Graph mention joins (short-TTL Caffeine; auth caches evict on
  revoke/mutation). At a 1200-VU search-heavy load this moved the server from
  congestion collapse (233 RPS, p95 11 s) to healthy (825 RPS, p95 361 ms).
- Removed per-request shared-lock contention on the request hot path: `Collator`
  (principal sort in every authz check), `TimeZone` (JSON date serialization),
  and `SecureRandom` (request-correlation IDs) are no longer re-acquired per
  request.
- Cut three search read-path costs: Lucene stored-field over-read when the
  highlighter is off, the wiki-syntax heuristic on every page GET, and a
  per-query regex recompile in Knowledge Graph entity resolution.

### Changed

- Backpressure admission cap (`WIKANTIK_MAX_INFLIGHT_REQUESTS`) default lowered
  700 → **390**: it must sit below Tomcat `maxThreads` (400) to take effect — the
  filter holds permits on worker threads, so the old default could never fire.
- DBCP `maxWaitMillis` 10 s → 5 s — fail faster now that the connection pool is
  no longer the bottleneck.

## [2.0.1] - 2026-05-17

## [2.0.0] - 2026-05-16

### Added — Admin UI

- `AdminTable` + selection-bar primitives with server-driven pagination,
  server-driven filtering, and a uniform bulk-action surface across all
  admin views.
- Bulk-action verticals: API Keys (revoke), Users (lock / unlock / delete),
  Knowledge Graph Proposals (approve / reject).
- Knowledge Graph Proposals admin: typed `Details` renderer (no more raw
  JSON), clickable `Machine` reasoning column with timestamps, machine-rejected
  filter, "Reject (no reason)" one-click speed action.
- Knowledge Graph Viewer reader route at `/knowledge-graph` (mirrors the
  Page Graph viewer): tier filter, node-type colours, provenance/status
  badges, large-graph warning.
- Knowledge Graph inclusion policy (cluster-primary), with admin dashboard
  at `/admin/kg-policy/*`, `bin/kg-policy.sh` CLI, and `kg_include` page
  frontmatter override.
- Verification metadata in frontmatter (`verified_at`, `verified_by`,
  `confidence`, `audience`) plus operator triage at `/admin/verification`.
- Runbook page type (`type: runbook`) with a six-key schema, save-time
  validation, and graceful read-time degradation.

### Added — Agent-facing surface

- 25 write/analytics MCP tools on `/wikantik-admin-mcp`.
- 16 read-only retrieval / Knowledge Graph traversal / structural-spine /
  agent-projection MCP tools on `/knowledge-mcp`.
- 2 OpenAPI 3.1 tools on `/tools/*` for OpenWebUI-compatible non-MCP clients.
- Worked input/output examples on every MCP / OpenAPI tool schema for
  reliable first-call success.
- `GET /api/pages/for-agent/{canonical_id}` token-budgeted page projection
  (summary, key facts, headings, recent changes, MCP tool hints, verification
  state) backed by a 1h / 5K-entry cache.
- Retrieval-quality CI (`DefaultRetrievalQualityRunner`): nightly nDCG@5/@10,
  Recall@20, MRR across BM25 / HYBRID / HYBRID_GRAPH, persisted to
  `retrieval_runs`, exposed at `/admin/retrieval-quality` and as Prometheus
  gauges.

### Added — Retrieval / search

- Hybrid retrieval pipeline: BM25 + dense (pgvector) + Knowledge Graph-aware
  rerank, with fail-closed BM25 fallback.
- Per-page entity-extraction pipeline (`bin/kg-extract.sh`) feeding the
  Knowledge Graph proposal queue with deduplicated, evidence-grounded
  extractions.
- LLM judge for proposal verdicts with per-proposal-type prompt branching
  (edge vs. node) and a pre-flight validator that synthesises explicit
  abstain reasons for malformed proposals.

### Added — Page Graph subsystem

- Structural-spine save-time enforcement: pages saved without `canonical_id`
  get one auto-assigned (toggle: `wikantik.structural_spine.enforcement.enabled`).
- Generated `Main.md` from `Main.pins.yaml` (hand-edits now revert on
  regeneration).
- `/api/structure/*` REST surface and matching MCP tools.
- Operator triage at `/admin/page-graph/conflicts`.

### Added — Deployment

- Tomcat 11.0.22 with hands-off `bin/deploy-local.sh` upgrade flow
  (snapshot, restore, template materialisation).
- Container-first deploy path: `Dockerfile`, `docker/entrypoint.sh`,
  `docker-compose.{yml,dev,prod,test}.yml` with `pgvector/pgvector:pg17`,
  automatic migration runner, env-injected DB password,
  `<CookieProcessor sameSiteCookies="strict"/>`, single canonical Tomcat-conf
  source-of-truth, stdout dual-write logging.
- `bin/db/migrate.sh` idempotent migration runner with `schema_migrations`
  ledger; runs automatically on every container start and every
  `deploy-local.sh` invocation.
- `bin/redeploy.sh` fast iteration helper: shutdown → rotate `catalina.out` →
  swap WAR → startup, no template / migration / secrets work.

### Added — Security

- AdminAuthFilter passes SPA navigation through to the React shell so the
  SPA's own login flow handles unauthenticated state.
- SPA `wikantik:auth-required` event: 401/403 mid-session triggers an
  auth-state refresh so stale-session clicks fail visibly.
- Database-backed policy grants (`policy_grants` table) and database-backed
  groups, both manageable from `/admin/security`.
- `<CookieProcessor sameSiteCookies="strict"/>` on both bare-metal and
  container deploys.
- NIST 800-63B password validation with common-password blocklist.
- `ObjectInputFilter` whitelists on every `ObjectInputStream` usage.

### Changed

- Frontend bundle structure: admin pages, editors, blog, and graph
  viewers are now lazy-loaded; only the reader hot path stays in the main
  chunk.
- `WikiEngine` initialisation refactored across 11 phases — typed subsystem
  accessors, factory-driven wiring, registry deletion. `getManager(Class)`
  removed from the public `Engine` interface; callers use typed subsystem
  reads.
- Search: `LuceneSearchProvider` decomposed (1251 → 724 LOC) into a facade
  plus three helpers; Lucene helpers wired into the Search subsystem.
- Typed `relations:` frontmatter removed (2026-05-02). Use `get_outbound_links`
  / `get_backlinks` on `/wikantik-admin-mcp` for Page Graph traversal instead.

### Fixed

- 66 pages with malformed YAML frontmatter (60 orphaned `relations:` children
  from the typed-relations removal, 6 unquoted-colon titles) — frontmatter now
  parses for the entire corpus.
- Knowledge Graph judge was sending edge-shaped prompts for new-node
  proposals, causing 99.93% of new-node proposals to abstain. Fix: branch the
  system prompt by proposal type and add a pre-flight validator for required
  fields.
- `/admin/knowledge-graph/proposals` machine-rejected filter actually loads
  rejected proposals.
- `JSESSIONID` no longer hard-codes `Secure` (broke local HTTP development);
  the cookie's secure flag is set by the container's session config.
- 15 integration tests in `wikantik-it-test-rest` were silently skipped
  due to an explicit failsafe whitelist; now discovered via `**/*IT.java`.
- Pagination on `/admin/knowledge-graph/proposals` is now stable across pages
  (id-DESC tiebreak; `count_proposals_filtered` mirrors the list filter).

### Operations

- Backup sidecar tested end-to-end: `pg_dump` + `pages.tar.gz` with SHA-256
  checksums, tiered retention, full restore round-trip verified
  (1079 pages + 25 migrations preserved across a wipe + restore).
- `docs/DockerDeployment.md` covers external services (Postgres, Ollama,
  SMTP), bare-metal ↔ container coexistence on alternate ports, and the
  one-time bare-metal-to-container migration procedure.
