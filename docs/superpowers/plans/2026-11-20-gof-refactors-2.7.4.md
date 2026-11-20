# GoF Refactors (2.7.4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the medium and larger design refactors from the 2026-11-20 Gang-of-Four review as patch release 2.7.4, with no change in behaviour.

**Architecture:** Each task finishes a pattern the code already half-applies (Strategy, Template Method, Facade, Command), or moves one concern out of a god class behind the interface it already has. Tasks fall into six independent lanes (search, connectors, REST, insights, engine core, frontend). Lanes can run in parallel; tasks inside a lane run in order.

**Tech Stack:** Java 25 / Maven multi-module, JUnit 5 + Mockito, PostgresTestDb (pgvector); React 19 + Vitest + happy-dom; CodeMirror 6.

**Spec:** No separate design doc. The source is the review findings collated in the session of 2026-11-20 and summarised under each task's "Finding" line. The 2.7.3 quick wins and defects already shipped (commits `202e9edf72`..`ed94257aef`).

## Global Constraints

- Behaviour-preserving. No change to REST/MCP wire shapes, status codes, log message text asserted by tests, config keys, SQL schema or event order. Any deliberate exception is named in its task.
- TDD for refactors means characterise first. Where the code being moved has no direct test, add one against the CURRENT code, run it green, then refactor and keep it green unchanged.
- Data access only through `com.wikantik.jdbc.Jdbc` (`JdbcAccessArchTest`). No new `register*` on `WikiEngine`, and no new `getManager` callers (`DecompositionArchTest`).
- ArchUnit attributes calls made inside a lambda to the synthetic `lambda$method$N`, which is a NEW violation the freeze store rejects. Never call `getManager` inside a lambda. Resolve managers in the approved method body and pass them in.
- After any ArchUnit run, compare `wikantik-main/src/test/resources/archunit_store/` against git. A red run rewrites it: restore with `git checkout --` (controller only). A shrink from a removed violation is kept and committed.
- PMD complexity ratchet: `mvn -o -q -pl <module> pmd:check -Pcomplexity-gate` passes. No new suppressions.
- No empty catch blocks; at least `LOG.warn` with context.
- Frontend: reuse `src/components/ui/`. React 19 wipes innerHTML-managed DOM on re-render, so memoise hosts.
- Parallel execution: implementers never run state-changing git (no add/commit/stash/checkout/restore), never `mvn install`, never `bin/run-tests.sh`. They run module-scoped tests only, `mvn -o -q -pl <module> test -Dtest=…` or `npx vitest run <paths>`. The controller reviews each diff and commits by name.
- Commit messages are 1–3 lines and end with the two trailer lines the session's system reminder gives.
- Release gate before cutting: `bin/run-tests.sh --all`. Release notes go under `## [Unreleased]` (no version header; `cut-release.sh` writes it).

## Review Focus

1. **A connector config whose record does not match its type.** Today this is a `ClassCastException` deep in the assembler. After Task 3 it must return `Optional.empty()` with a WARN naming the id and both types. It must never throw out of wiring. (Test in Task 3.)
2. **A reference snapshot written by 2.7.3** must still load after Task 9. Write the fixture with the current code before refactoring, then load it with the new store. (Test in Task 9.)
3. **A path the new route table does not know**, including a trailing slash and an empty segment, must give the same 404 status and body as before. (Test in Task 5.)
4. **A boot step that throws** must behave as before for each subsystem: connectors, collab and work continue; the rest abandon later steps through the outer catch, exactly as today. (Test in Task 11.)
5. **An admin page reloading after a mutation** must keep its table on screen, with no spinner flash. (Test in Task 13.)

---

## Lane A: search (wikantik-main)

### Task 1: Dense backend as a Strategy

Finding: `ChunkVectorIndex` lacks `reload`/`upsertChunks`/`size`, so `SearchWiringHelper.wireHybridRetrieval` (~200-250) and `SearchSubsystemFactory.buildChunkVectorIndexForBackend` (~228-290) each switch on the backend name and bind method refs by hand. The pgvector `ef_search` parsing is duplicated.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/search/hybrid/RefreshableChunkVectorIndex.java`
- Modify: `search/hybrid/InMemoryChunkVectorIndex.java`, `search/hybrid/LuceneHnswChunkVectorIndex.java` (implement it); `search/subsystem/DenseBackends.java` → becomes enum `DenseBackend`; `search/subsystem/SearchWiringHelper.java`; `search/subsystem/SearchSubsystemFactory.java`
- Test: `wikantik-main/src/test/java/com/wikantik/search/subsystem/DenseBackendTest.java` (new); keep `DenseBackendResolutionTest`, `SearchWiringHelperTest`, `SearchSubsystemFactoryTest` green unchanged

**Interfaces:**
- Produces: `interface RefreshableChunkVectorIndex extends ChunkVectorIndex { void reload(); void upsertChunks(Collection<UUID> chunkIds); int size(); }`
- Produces: `enum DenseBackend { INMEMORY("inmemory"), PGVECTOR("pgvector"), LUCENE_HNSW("lucene-hnsw"); String wire(); static DenseBackend parse(String value) /* throws the same IllegalArgumentException text DenseBackends.unsupported gives today */; ChunkVectorIndex build(DataSource ds, String modelCode, Properties props); }`

- [ ] **Step 1:** Write `DenseBackendTest`:
  - `parse` accepts each wire value case-insensitively.
  - `parse("bogus")` throws with exactly today's `DenseBackends.unsupported("bogus")` message (copy the text from the current source).
  - `PGVECTOR.build(ds, "m", props)` reads `wikantik.search.dense.pgvector.ef_search` (default `100`).
  - `INMEMORY.build` and `LUCENE_HNSW.build` return a `RefreshableChunkVectorIndex`; `PGVECTOR.build` returns a non-refreshable index.
- [ ] **Step 2:** Run `mvn -o -q -pl wikantik-main test -Dtest=DenseBackendTest`. Expected: FAIL (no `DenseBackend`).
- [ ] **Step 3:** Implement the interface and the enum.
  - Both call sites build through `DenseBackend.build`.
  - The wiring derives its hooks with `if (idx instanceof RefreshableChunkVectorIndex r) { upsertCallback = r::upsertChunks; hook = r::reload; empty = () -> r.size() == 0; } else { upsertCallback = null; hook = () -> {}; empty = () -> false; }`.
  - Keep the two DIFFERENT defaults (`lucene-hnsw` in `SearchWiringHelper.resolveDenseBackend`, `inmemory` in the factory) as explicit arguments at each call site.
  - Keep `engine.setManager(InMemoryChunkVectorIndex.class, …)` exactly where it is.
  - Keep each backend's startup LOG line text.
  - Delete `DenseBackends`.
- [ ] **Step 4:** Run `mvn -o -q -pl wikantik-main test -Dtest='DenseBackend*,SearchWiringHelper*,SearchSubsystem*,*ChunkVectorIndex*,DecompositionArchTest'`, plus the PMD gate. Expected: all PASS.
- [ ] **Step 5:** Commit `refactor(search): dense backends as a DenseBackend strategy; refreshable indexes declare reload/upsert/size`.

---

## Lane B: connectors (wikantik-connectors, wikantik-main `derived`)

### Task 2: Connector type codecs as a registry

Finding: `ConnectorConfigCodec` switches on the type string three times (`validate` ~81, `build` ~106, `toJson` ~131, with casts) and keeps `UI_TYPES` by hand.

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/config/ConnectorTypeCodec.java`
- Modify: `config/ConnectorConfigCodec.java` and the six per-type codecs (`WebCrawlerCodec`, `SitemapCodec`, `FeedCodec`, `DriveCodec`, `GithubCodec`, `ConfluenceCodec`)
- Test: `ConnectorConfigCodecTest` stays green unchanged; add `ConnectorTypeCodecRegistryTest`

**Interfaces:**
- Produces: `interface ConnectorTypeCodec<C> { String type(); Class<C> configClass(); ConnectorConfigCodec.Validation validate(JsonObject config); C build(JsonObject config, boolean forTest); JsonObject toJson(C config); }`
- Produces: `ConnectorConfigCodec.codecFor(String type): Optional<ConnectorTypeCodec<?>>`
  - The public static `validate`/`toConfig`/`toConfigForTest`/`toJson` keep their signatures and messages, now one lookup each.
  - `UI_TYPES` is the registry's key set; `filesystem` stays excluded, as its javadoc says.
  - `toJson` with a config of the wrong class throws `IllegalArgumentException("config for <type> must be <Class>")` instead of `ClassCastException`.

- [ ] **Step 1:** Write `ConnectorTypeCodecRegistryTest`:
  - `UI_TYPES` equals `{webcrawler, sitemap, feed, gdrive, github, confluence}`.
  - `codecFor("filesystem")` is empty.
  - `toJson("feed", new SitemapConfig(...))` throws `IllegalArgumentException` mentioning `feed`.
  - `codecFor(t).get().type().equals(t)` for every `UI_TYPES` member.
- [ ] **Step 2:** Run `mvn -o -q -pl wikantik-connectors test -Dtest=ConnectorTypeCodecRegistryTest`. Expected: FAIL.
- [ ] **Step 3:** Implement. Each per-type codec becomes a `ConnectorTypeCodec` singleton (keep its static methods as delegates if tests call them). Registry: an immutable `Map<String, ConnectorTypeCodec<?>>` in `ConnectorConfigCodec`.
- [ ] **Step 4:** Run `mvn -o -q -pl wikantik-connectors test` plus the PMD gate. Expected: PASS.
- [ ] **Step 5:** Commit `refactor(connectors): connector type codecs as a registry; UI_TYPES derived`.

### Task 3: Connector assembly as a factory registry (after Task 2)

Finding: `derived/ConnectorAssembler.build(id, type, Object config, creds)` (~65-105) switches on type and casts `Object`. `resolveDriveSecret` is copied in `ConnectorAssembler` (~108) and `ConnectorConfigService`. `ConnectorConfigService.create` (~203) special-cases `"gdrive"` by string.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorFactory.java`, `derived/ConnectorFactories.java`
- Modify: `derived/ConnectorAssembler.java`, `derived/ConnectorConfigService.java`
- Test: existing `ConnectorAssembler*Test`, `ConnectorConfigService*Test` green; add `ConnectorFactoriesTest`

**Interfaces:**
- Consumes: `ConnectorTypeCodec.configClass()` (Task 2).
- Produces: `interface ConnectorFactory<C> { String type(); Class<C> configClass(); SourceConnector create(String id, C config, CredentialStore creds); default ConnectorConfigCodec.Validation extraValidation(JsonObject config) { return ok; } }`. The gdrive factory owns the secret resolution and the `redirect_uri` check.
- Produces: `ConnectorFactories.forType(String): Optional<ConnectorFactory<?>>`. `ConnectorAssembler.build` keeps its signature and returns `Optional.empty()` + WARN when the config's class is not `configClass()` (Review Focus 1).

- [ ] **Step 1:** Write `ConnectorFactoriesTest`:
  - `ConnectorAssembler.build("x", "feed", sitemapConfig, creds)` returns empty and logs a WARN containing `x`, `feed` and `SitemapConfig` (use the project's log-capture helper if one exists, else assert empty only).
  - A drive config with no secret resolves through the credential store exactly as before (copy the current assertion from the assembler test).
  - Every `ConnectorConfigCodec.UI_TYPES` member has a factory.
- [ ] **Step 2:** Run `mvn -o -q -pl wikantik-main test -Dtest=ConnectorFactoriesTest`. Expected: FAIL.
- [ ] **Step 3:** Implement. Delete both `resolveDriveSecret` copies in favour of the gdrive factory, and remove the `"gdrive"` string special case from `ConnectorConfigService`.
- [ ] **Step 4:** Run `mvn -o -q -pl wikantik-main test -Dtest='Connector*,*ConnectorConfig*,DecompositionArchTest'` plus the PMD gate. Expected: PASS.
- [ ] **Step 5:** Commit `refactor(derived): connectors assembled through a factory registry; one drive-secret resolver`.

### Task 4: Web connectors share the batch tail

Finding: the token connectors' final `poll()` owns the trusted/untrusted cursor tail. Sitemap hand-writes `trusted ? new SyncCursor(size), true : cursor, false` (~70-73), and Feed and Crawler repeat close variants. 2.7.3 added `WebConnectorSupport.guardedPoll`.

**Files:** Modify `TokenAuthenticatedSourceConnector.java` and `web/{Feed,Sitemap,WebCrawler}SourceConnector.java`; create `wikantik-connectors/src/main/java/com/wikantik/connectors/SyncBatches.java`. Test: `SyncBatchesTest` (new), existing connector tests green.

**Interfaces:** Produces `final class SyncBatches { static SyncBatch finish(List<SyncItem> items, boolean trusted, SyncCursor input); static SyncBatch failed(SyncCursor input); }`. Use the real item type name from `SyncBatch`. Every connector and `guardedPoll` uses them.

- [ ] **Step 1:** Write `SyncBatchesTest`:
  - `finish(items, true, c)` gives cursor `String.valueOf(items.size())` and `complete=true`.
  - `finish(items, false, c)` keeps `c` and gives `complete=false`.
  - `failed(c)` gives no items, no tombstones, `c`, `complete=false`.
- [ ] **Step 2:** Run it; expected FAIL.
- [ ] **Step 3:** Implement and replace the hand-written tails. Feed keeps `reflectsFullCorpus()=false` and its current cursor shape; if Feed's tail differs from `finish`, leave Feed's tail as is and say so in the commit.
- [ ] **Step 4:** Run `mvn -o -q -pl wikantik-connectors test` plus the PMD gate. Expected: PASS.
- [ ] **Step 5:** Commit `refactor(connectors): one SyncBatches.finish/failed tail for token and web connectors`.

---

## Lane C: REST (wikantik-rest)

### Task 5: One route table for REST servlets

Finding: `WorkRoutes` gives the 8 `Work*Resource` servlets a typed matcher, but it hardcodes `/api/work` and stores `Object[]` triples. `CommentThreadResource` (~362) and `MyMentionsResource` (~240) have byte-identical private `segments()`. `StructureResource`, `SelfApiKeysResource` and `AdminUserResource` (`endsWith("/lock")`) split paths by hand.

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/RouteTable.java`
- Modify: `WorkRoutes.java` (delete once `WorkResourceBase` uses `RouteTable`), `WorkResourceBase.java`, `CommentThreadResource.java`, `MyMentionsResource.java`, `StructureResource.java`, `SelfApiKeysResource.java`, `AdminUserResource.java`
- Test: `RouteTableTest` (new); every existing resource test green unchanged

**Interfaces:**
- Produces: `final class RouteTable<H> { RouteTable(String prefix); RouteTable<H> add(String method, String pattern, H handler); Optional<Match<H>> match(HttpServletRequest request); record Match<H>(H handler, List<String> captured) {} static String[] segments(HttpServletRequest request, String prefix); }`
  - `{}` captures one segment.
  - Matching is exact on method and segment count, and empty segments are dropped, the same as `WorkRoutes` today.
- `PageResource` is NOT converted: page names contain `/`, so it keeps suffix dispatch.

- [ ] **Step 1:** Write `RouteTableTest`:
  - Prefix stripping for `/api/work` and `/api/comments`.
  - `{}` capture order.
  - Method mismatch gives empty.
  - `/api/comments/` (trailing slash) and `/api/comments//x` (empty segment) match as `WorkRoutes` matches them today. Assert the same segment arrays the old `segments()` returns.
- [ ] **Step 2:** Add characterisation tests for each converted servlet's unknown-path response, as they are today: status and body for an unknown path, a trailing slash and an empty segment (Review Focus 3). Run them green against the current code.
- [ ] **Step 3:** Implement `RouteTable`, move `WorkResourceBase` onto it, then convert the five servlets one at a time, re-running each servlet's tests after each.
- [ ] **Step 4:** Run `mvn -o -q -pl wikantik-rest test -Dtest='RouteTable*,Work*Resource*,CommentThreadResource*,MyMentionsResource*,StructureResource*,SelfApiKeysResource*,AdminUserResource*'` plus the PMD gate. Expected: PASS.
- [ ] **Step 5:** Commit `refactor(rest): one RouteTable for path dispatch (work, comments, mentions, structure, api keys, admin users)`.

### Task 6: `PageResource` split into handlers

Finding: `PageResource` is 1,094 lines; `doGet` is ~240 lines (203-445); the `doPut` error ladder is ~577-612 with statics at 616-700. `PageBlocksHandler` (`new PageBlocksHandler(this)`) is the established pattern.

**Files:** Create `wikantik-rest/src/main/java/com/wikantik/rest/PageSaveHandler.java` (`doPut`, `doPatch`, error-body statics), `PageReadHandler.java` (`doGet` body), `PageRenameHandler.java` (`handleRename`). Modify `PageResource.java`. Test: `PageResource*Test` and `PageResourceAccessRulesTest` green unchanged.

**Interfaces:**
- Produces package-private `PageSaveHandler(RestServletBase servlet)`, `PageReadHandler(RestServletBase servlet)` and `PageRenameHandler(RestServletBase servlet)`, mirroring `PageBlocksHandler(RestServletBase)`.
- `PageResource` keeps every package-private static that tests reference (`validationErrorBody`, `accessRuleShapeErrorBody`, `mathValidationErrorBody`, `sanitizeSaveErrorMessage`, `parseMaxPageBytes`, `isExpectedVersionRequired`, `isInvalidPageName`) as one-line delegates.

- [ ] **Step 1:** Record the before state: `mvn -o -q -pl wikantik-rest test -Dtest='PageResource*'` must pass. Note the count.
- [ ] **Step 2:** Move one handler at a time: save, then read, then rename. Re-run the same selection after each and expect the same count passing.
- [ ] **Step 3:** Run the PMD gate. `PageResource` should end under ~350 lines.
- [ ] **Step 4:** Commit `refactor(rest): PageResource delegates save, read and rename to handlers like PageBlocksHandler`.

---

## Lane D: insights (wikantik-insights)

### Task 7: Opportunity rules as Strategy objects

Finding: `OpportunityEngine.evaluateGated` (~750-817, 11 parameters) hard-codes four rules, copies the traffic gate three times, then runs suppress/cooldown/snooze/calibration/sort inline. `evaluate` (~701, 8 parameters) delegates to it.

**Files:** Create `wikantik-insights/src/main/java/com/wikantik/insights/EvaluationInput.java` and `OpportunityRule.java`. Modify `OpportunityEngine.java`. Test: all `OpportunityEngine*Test` green unchanged; add `OpportunityRuleGateTest`.

**Interfaces:**
- Produces: `record EvaluationInput(List<DemandRow> demandRows, List<VisibilityRow> visibilityRows, List<Opportunity> imported, PageFacts pageFacts, List<OpportunitySnooze> snoozes, Map<String, LocalDate> lastChangeByTarget, int siteImpressions28d, Set<String> calibratedTypes, ExpectedCtrCurve curve, LocalDate today, OpportunityEngineConfig config)`. Field types are copied from `evaluateGated`'s parameters.
- Produces: `interface OpportunityRule { String type(); boolean gated(); List<Opportunity> evaluate(EvaluationInput in); }`, with one implementation per rule: `AGENT_GAP` (`gated()=false`), `ENGINE_DIVERGENCE`, `VOCABULARY_GAP`, `STALE_HIGH_TRAFFIC`.
- `evaluate(...)`, `evaluateGated(...)` and the four public `evaluateX` methods keep their signatures as thin delegates.

- [ ] **Step 1:** Write `OpportunityRuleGateTest`:
  - Below `config.gateImpressions28d()`, the three gated rules each appear once in `Backlog.suppressed()` with reason `"traffic_gate"` and their measured/required values.
  - `AGENT_GAP` still runs.
  - Imported `ctr_gap`/`striking_distance` opportunities pass through when the gate is closed (the second-order effect in the comment at ~777-784).
  - Run it green against the CURRENT code before refactoring.
- [ ] **Step 2:** Refactor `evaluateGated` into: build `EvaluationInput` → for each rule (gated and closed → `SuppressedRule`, else evaluate) → the existing post-filters in their existing order.
- [ ] **Step 3:** Run `mvn -o -q -pl wikantik-insights test` plus the PMD gate. Expected: PASS, with the same count as before plus the new tests.
- [ ] **Step 4:** Commit `refactor(insights): opportunity rules as OpportunityRule strategies over one EvaluationInput`.

### Task 8: `JdbcInsightsStore` split by aggregate (after Task 7, same module)

Finding: `JdbcInsightsStore` is 967 lines across visibility (~359-454), changes and snoozes (~454-538), demand (~583-623), seen (~684-758), imported opportunities (~770-847, with the type-to-action switch at ~895-905) and CTR curves (~848-894). This is the same split `wikantik-work` made for PMD.

**Files:** Create `JdbcVisibilityStore.java`, `JdbcChangeLedger.java` (changes, snoozes, seen, effects/calibration), `JdbcDemandStore.java`, `JdbcImportedOpportunities.java` and `JdbcCtrCurves.java` in `com.wikantik.insights`. Move `suggestedActionForImportedType` to `ImportedOpportunityActions.java`. Modify `JdbcInsightsStore.java` into a Facade that implements `InsightsStore` by delegating. Test: existing `JdbcInsightsStore*PostgresTest` green unchanged.

**Interfaces:** `InsightsStore` is unchanged. Each part is a package-private class constructed with the shared `Jdbc`.

- [ ] **Step 1:** Record the before state: run `mvn -o -q -pl wikantik-insights test` green and note the count.
- [ ] **Step 2:** Move one aggregate at a time, re-running the Postgres tests after each.
- [ ] **Step 3:** Run the PMD gate. No part should exceed ~300 lines.
- [ ] **Step 4:** Commit `refactor(insights): JdbcInsightsStore is a facade over per-aggregate stores`.

---

## Lane E: engine core (wikantik-main)

### Task 9: Reference snapshot persistence out of `DefaultReferenceManager`

Finding: the 1,265-line `DefaultReferenceManager` mixes the link index with the debounced disk snapshot (`requestSerialization` ~445, `deferredFlush` ~456, `flushSerialization` ~470, `serializeToDisk` ~489, `unserializeFromDisk` ~415, `SAFE_DESERIALIZE_CLASSES` ~202) and the per-page attribute files (~534-660).

**Files:** Create `wikantik-main/src/main/java/com/wikantik/pagegraph/references/ReferenceSnapshotStore.java`. Modify `DefaultReferenceManager.java`. Test: existing `DefaultReferenceManager*Test` green; add `ReferenceSnapshotStoreTest` with a fixture `src/test/resources/references/refmgr-2.7.3.ser`.

**Interfaces:**
- Produces: `final class ReferenceSnapshotStore implements AutoCloseable`
  - `ReferenceSnapshotStore(Path workDir, long intervalNanos, Supplier<Snapshot> state)`
  - `Optional<Snapshot> load()`
  - `void requestSave()` (debounced)
  - `void flush()`
  - `void close()` (flushes then stops)
  - `record Snapshot(Map<String, Collection<String>> refersTo, Map<String, Set<String>> referredBy, long savedAt)`, with types copied from the current fields.
- The file format, `serialVersionUID`, the `deepHashCode` check and the deserialisation allow-list move unchanged. Per-page attribute files stay in the manager unless the move is mechanical.

- [ ] **Step 1:** Before refactoring, write a test that makes the CURRENT manager serialise a small known graph to a temp dir, and copy the file to the fixture path (Review Focus 2). Commit the fixture with the test.
- [ ] **Step 2:** Write `ReferenceSnapshotStoreTest`:
  - `load()` of the fixture yields the known graph.
  - A file holding a class outside the allow-list is rejected (empty, WARN), as today.
  - `requestSave()` twice within the interval writes once.
  - `close()` flushes a pending save.
- [ ] **Step 3:** Run it; expected FAIL (no class). Implement the store and make the manager delegate.
- [ ] **Step 4:** Run `mvn -o -q -pl wikantik-main test -Dtest='*ReferenceManager*,ReferenceSnapshotStore*,DecompositionArchTest'` plus the PMD gate. Expected: PASS.
- [ ] **Step 5:** Commit `refactor(references): ReferenceSnapshotStore owns the on-disk snapshot; DefaultReferenceManager is the index`.

### Task 10: Login success tail written once

Finding: in `DefaultAuthenticationManager`, `LOGIN_AUTHENTICATED` → the `PRINCIPAL_ADD` loop → `injectAuthorizerRoles` is written twice (~239-249 in `attemptContainerOrSsoLogin`, ~285-300 in `login(session, request, user, pass)`). `final AuthenticationManager authenticationMgr = this` is a pointless alias.

**Files:** Modify `wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthenticationManager.java`. Test: `DefaultAuthenticationManager*Test` green; add an event-order test if none exists.

**Interfaces:** Produces `private void establish(Session session, Set<Principal> principals, HttpServletRequest request, Authorizer authorizer)`, which fires the same events in the same order. Session-id rotation stays only on the username/password path.

- [ ] **Step 1:** Write a test (or confirm one exists) that records the event sequence for a container login and for a password login: `LOGIN_AUTHENTICATED`, then one `PRINCIPAL_ADD` per principal, then the role injection. Run it green on the current code.
- [ ] **Step 2:** Extract `establish`, remove the alias, and run `mvn -o -q -pl wikantik-main test -Dtest='*AuthenticationManager*,*Login*'`. Expected: PASS.
- [ ] **Step 3:** Commit `refactor(auth): one establish() for the login success tail`.

### Task 11: Engine boot as an ordered `BootSequence` (last in the lane; largest risk)

Finding: `WikiEngine.initDataSourceSubsystems` (~958-1202) is about 245 lines calling ~14 `*WiringHelper.wireX` methods. Their order is load-bearing (comments ~1056-1064, ~1122). The failure policy is implied by where a try/catch sits. `shutdown()` hand-lists a reversed set of `closeQuietly` calls.

**Files:** Create `wikantik-main/src/main/java/com/wikantik/runtime/boot/BootStep.java`, `BootSequence.java` and `WiringEnv.java`. Modify `WikiEngine.java`. Test: `BootSequenceTest` (new); `WikiEngine*Test`, `DecompositionArchTest`, and the IT reactor in the release gate.

**Interfaces:**
- Produces: `record WiringEnv(Properties props, DataSource ds, PageManager pageManager, FilterManager filterManager, ReferenceManager referenceManager, StructuralIndexService structuralIndex, /* every value the helpers take today; resolve them all in initDataSourceSubsystems' body */ ...)`
- Produces: `enum FailurePolicy { ABORT_REST, CONTINUE }`
- Produces: `interface BootStep { String name(); FailurePolicy policy(); void start(WiringEnv env) throws Exception; default void stop() {} }`
- Produces: `final class BootSequence { BootSequence(List<BootStep> steps); void start(WiringEnv env); void stop(); }`. `stop()` runs the started steps' `stop()` in reverse; `start` applies each step's policy.
- Steps are declared as a `List<BootStep>` in `WikiEngine`. Step lambdas must not call `getManager`; see Global Constraints.

- [ ] **Step 1:** Write `BootSequenceTest`:
  - Steps start in order.
  - A `CONTINUE` step that throws logs a WARN naming the step, and later steps still run.
  - An `ABORT_REST` step that throws stops later steps and logs once, the same as the outer catch today.
  - `stop()` runs in reverse order, only for started steps, and a throwing `stop()` does not prevent the others.
- [ ] **Step 2:** Run it; expected FAIL. Implement the three types.
- [ ] **Step 3:** Characterise current failure behaviour per subsystem (Review Focus 4). For each step, record whether a failure today continues (connectors, collab and work have their own try/catch) or abandons the rest (the outer catch ~1199). Encode exactly that as each step's `FailurePolicy`, and put the table in the commit body.
- [ ] **Step 4:** Rewrite `initDataSourceSubsystems` as: resolve `WiringEnv` (all `getManager` calls in this method body) → `bootSequence.start(env)`. Move the matching `closeQuietly` calls into the steps' `stop()`, and keep any not tied to a step in `shutdown()`.
- [ ] **Step 5:** Run `mvn -o -q -pl wikantik-main test -Dtest='BootSequence*,WikiEngine*,DecompositionArchTest,*WiringHelper*'` plus the PMD gate, then check the ArchUnit store (Global Constraints). Expected: PASS, store unchanged or shrunk.
- [ ] **Step 6:** Commit `refactor(engine): datasource subsystems boot as an ordered BootSequence; each step owns its stop`.

---

## Lane F: frontend (wikantik-frontend)

### Task 12: `collabView()` and `useScrollSync`

Finding: `PageEditor.jsx` (~2,430 lines) derives six collab flags at ~338-352 from `COLLAB_FALLBACK`/`COLLAB_EDITING` (~119-121), and `readOnly || forbidden` is hand-written three times. The scroll sync is ~150 lines over 11 refs: `isEcho` ~213-223, `previewBlocks`/`paneFor`/`alignEditor`/`syncPreview` ~553-690, `syncEditor` ~755-775, the scroll-map reset ~1221.

**Files:**
- Create: `src/collab/collabView.js` (+ `collabView.test.js`), `src/hooks/useScrollSync.js`
- Modify: `src/components/PageEditor.jsx`, `src/utils/scrollSync.js` (receives `isEcho`)
- Test: `PageEditor.scrollSync.test.jsx` and `PageEditor.collab.test.jsx` must pass WITHOUT edits

**Interfaces:**
- Produces: `collabView(status, extensions, wanted) -> { mode, editing, forbidden, evicted, frozen, readOnly, locked }`, where `locked = readOnly || forbidden`. `COLLAB_FALLBACK` and `COLLAB_EDITING` move with it.
- Produces: `useScrollSync({ editorRef, previewRef, previewArticleRef, bodyRef, clickSyncGuardRef }) -> { syncPreview, syncEditor, previewBlocks, resetScrollMap }`. The refs it owns move inside it, and the rAF ordering is unchanged.

- [ ] **Step 1:** Write `collabView.test.js` as a table over every status in both sets plus `undefined`, crossed with `extensions` present/absent and `wanted` true/false. Derive the expected values from the CURRENT expressions at ~342-348 (copy them into the test as the oracle).
- [ ] **Step 2:** Run `npx vitest run src/collab/collabView.test.js`; expected FAIL. Implement it, and replace the six flag lines and the three hand-written `readOnly || forbidden` with it.
- [ ] **Step 3:** Move the scroll sync into `useScrollSync`. Do not change any expression's logic, only where it lives.
- [ ] **Step 4:** Run `npx vitest run src/components/PageEditor src/collab src/utils/scrollSync` and `npx eslint src/components/PageEditor.jsx src/hooks src/collab`. Expected: PASS with zero test edits.
- [ ] **Step 5:** Commit `refactor(editor): collabView() derives the collab flags; useScrollSync owns the scroll sync`.

### Task 13: `useAdminResource` for admin data loading

Finding: 14 admin components hand-roll `[x,setX]` + loading + error + `loadX()` + mount effect: `AdminApiKeysPage`, `AdminAuditPage`, `AdminConnectorsPage`, `AdminContentPage`, `AdminUsersPage`, `BacklogPanel`, `ContentEmbeddingsTab`, `AdminRetrievalQualityPage`, `AdminPageOwnershipPage`, `InsightsPanel`, `ChunkInspectorTab`, `AdminDriftPage`, `AdminSecurityPage`, `ConnectorDetailPage`. `hooks/useApi.js` exists, but its `load` sets `loading=true`, which would blank `AdminPage`.

**Files:** Create `src/hooks/useAdminResource.js` (+ test). Modify the admin components, one commit per 3–5 pages. Delete the `components/admin/EmptyState.jsx` shim (it re-exports `ui/EmptyState`) and point `ContentEmbeddingsTab` at `../ui/EmptyState`.

**Interfaces:** Produces `useAdminResource(fetcher, deps = []) -> { data, setData, loading, error /* message string or null */, reload /* silent: does not set loading */ }`. It is abortable on unmount and on re-fetch, like `useApi`.

- [ ] **Step 1:** Write `useAdminResource.test.js`:
  - Initial `loading=true`, then data.
  - An error gives `error` as `err.message`.
  - `reload()` never sets `loading` back to true (Review Focus 5).
  - Unmount aborts (the signal passed to `fetcher` is aborted).
- [ ] **Step 2:** Run it; expected FAIL. Implement it.
- [ ] **Step 3:** Convert pages in batches. After each batch run `npx vitest run src/components/admin`; the per-page tests pass unchanged.
- [ ] **Step 4:** Commit each batch `refactor(admin): <pages> load through useAdminResource`.

### Task 14: `useCollabJoin` (after Task 12; same file)

Finding: `PageEditor.jsx` ~330-447 holds about 12 `useState`s for the join protocol (`collabSeed`, `loadedFor`, `collabKeptFor`, `restoreOffer`, `offlineDraftOffer`, `joinedExtensions`, `draftBannerOpen`, `seenPublished`, …). It also holds "adjust state during render" blocks (~366-398, ~432-440) whose ordering is load-bearing.

**Files:** Create `src/collab/useCollabJoin.js`. Modify `src/components/PageEditor.jsx`. Test: `PageEditor.collab.test.jsx` and `useCollabSession.test.jsx` green WITHOUT edits.

**Interfaces:** Produces `useCollabJoin({ collab, name, draft, published, login }) -> { restoreOffer, offlineDraftOffer, draftBannerOpen, joinedDoc, seenPublished, ...handlers }`. The exact set comes from the state that moves. The setState-during-render blocks move intact, in the same order, inside the hook.

- [ ] **Step 1:** Run `npx vitest run src/components/PageEditor.collab.test.jsx src/collab` and record the passing count.
- [ ] **Step 2:** Move the state and render-time blocks into the hook verbatim. Do not convert them to effects.
- [ ] **Step 3:** Re-run Step 1's selection plus the full `src/components/PageEditor*` set. Expected: the same counts passing, zero test edits. If a test needs an edit, stop: the move changed behaviour.
- [ ] **Step 4:** Commit `refactor(editor): useCollabJoin owns the collab join and draft-offer state`.

---

## Release

### Task 15: Cut 2.7.4

- [ ] **Step 1:** Add `### Changed` under `## [Unreleased]` in `CHANGELOG.md`: one line stating internal refactors with no user-visible change, plus any behaviour exception a task named.
- [ ] **Step 2:** Run `bin/run-tests.sh --all` through `bin/agent-build.sh start relgate -- …`. Expected: `RESULT: ALL PASSED`. Fix any red in-session, with no "pre-existing" excuse.
- [ ] **Step 3:** Run `printf 'n\n' | bin/cut-release.sh 2.7.4`, then `git push origin main v2.7.4`, then watch the `release.yml` run to success.
- [ ] **Step 4:** Hand the owner the deploy command (`! bin/deploy-release.sh 2.7.4`). After it runs, verify health, `/api/capabilities` and `bin/smoke-wiki.sh http://docker1:8080`.

## Out of scope (reviewed, deliberately not in this release)

- `LuceneSearchProvider` lazy getters: only worth doing alongside a rewrite of the tests that inject by reflection.
- `WorkService` builder: large test churn for modest value.
- `widgetFor` lookup table: aesthetic only.
- Referral-plugin Template Method and the `RecentArticles` renderer: candidates for a later pass.
- SCIM error mapping helper, MCP `auditedExecute` dispatcher in `mcp-core`, `ForAgentProjection.withCallerView`, `IndexHtmlRewriter`: small and independent; can join this release if a lane finishes early.
