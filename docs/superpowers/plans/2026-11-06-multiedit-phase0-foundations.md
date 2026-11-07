# MultiEdit Phase 0 — Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build everything Phase 1 (core text collaboration) is written test-first into: an atomic page save, an async-capable `/api/*` filter chain, a Java port of CodeMirror's `ChangeSet` held byte-for-byte to the real library, `TextDiff`, the collab ports, and the deterministic test harness (F0–F9 + `CollabInvariants`) running against a stub authority. Nothing user-visible ships except the A2 save-race fix.

**Architecture:** Production code adds `com.wikantik.collab.text` (pure text algebra), `com.wikantik.collab` (port interfaces and value records only) in wikantik-main, a guarded save on `PageManager` (wikantik-api) implemented atomically in `DefaultPageLifecycle`, and filter/limiter fixes in wikantik-http/wikantik-war. All harness code is test scope in `wikantik-main/src/test/java/com/wikantik/collab/testing/`; the cross-language fixtures live in `wikantik-frontend/src/collab/__fixtures__/`.

**Tech Stack:** Java 25, JUnit 5, Gson, jrcs `MyersDiff` (already a wikantik-main dependency), ArchUnit; Node 20+/vitest with `@codemirror/state` (present) and `@codemirror/collab` (added in Task 13).

**Spec:** `docs/MultiEdit.MD` — **§0 Review amendments are authoritative** over later sections. Read §0, §5.2, §6.1, §7.1–7.2, §10, §12.1–12.2 and §13.0–13.1 before any task.

## Global Constraints

- TDD: every task writes its failing test first and runs it red before implementing (CLAUDE.md).
- Never swallow exceptions: every catch logs at least `LOG.warn` with context (CLAUDE.md).
- No `synchronized`, `Thread.sleep`, `System.currentTimeMillis()`/`Instant.now()` or engine lookup inside `com.wikantik.collab..` main code (§5.2); time comes from an injected `java.time.Clock`.
- Text in the collab layer is LF-only; lengths are UTF-16 code units = Java `String` indices (§6.1, A9).
- Database access only through `com.wikantik.jdbc.Jdbc` (ArchUnit J-1). No migration in Phase 0.
- JSON with Gson (the project's library). No new Java dependencies (jqwik is not in the build: property tests are seeded loops, §0 Minor).
- Frontend dependency pins: exact versions at least two weeks old; install with `npm ci --ignore-scripts` hygiene (memory: npm supply-chain posture).
- Stage files by name; never `git add -A`. Commit trailer:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01F1Gxe3xanoeQBqwJ6XfKzk`.
- Java single-module test: `mvn -o -q test -pl wikantik-main -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false` (unset `WIKANTIK_*` first: `unset ${!WIKANTIK_@}`). Frontend: `cd wikantik-frontend && npx vitest run <path>`.

## Review Focus

1. A save of page P made from inside P's own guarded save (a filter or listener re-entering) must not deadlock — Task 1 test `aReentrantSaveOfTheSamePageDoesNotDeadlock`.
2. A filter registered through wikantik-http's `web-fragment.xml` (not `web.xml`) that lacks async support must still fail the chain test — Task 2 test reads both files.
3. `/api/collaborate` or `/api/collabX` must not inherit the `/api/collab/` rate-limit exemption — Task 3 test `onlyTheCollabPathPrefixIsExempt`.
4. `TextDiff` on a 256 KB single-line change must stay fast (not quadratic in characters) — Task 6 test `aLongSingleLineEditDiffsInLinearTime`.
5. A change set whose boundary splits a surrogate pair is reported, not silently applied — Task 5 test `aBoundaryInsideASurrogatePairIsDetected`.

---

### Task 1: Atomic guarded page save (A2)

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/pages/SaveGuard.java`, `wikantik-api/src/main/java/com/wikantik/api/pages/SaveOutcome.java`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/managers/PageManager.java` (new default method), `wikantik-api/src/main/java/com/wikantik/api/pages/PageSaveHelper.java:69-110`, `wikantik-main/src/main/java/com/wikantik/pages/DefaultPageManager.java:178`, `wikantik-main/src/main/java/com/wikantik/page/subsystem/lifecycle/DefaultPageLifecycle.java:73-115` (and its `PageLifecycle` interface)
- Test: `wikantik-main/src/test/java/com/wikantik/page/subsystem/lifecycle/GuardedSaveTest.java`, extend `PageSaveHelper`'s existing test

**Interfaces:**
- Produces:
  - `@FunctionalInterface public interface SaveGuard { void check( Page current, String currentText ) throws VersionConflictException; SaveGuard NONE = (p, t) -> {}; }` — `current` is null for a page that does not exist.
  - `public record SaveOutcome( Page page, int version, boolean unchanged ) {}` — `page`/`version` are the version this call wrote (or the existing latest when `unchanged`).
  - `PageManager`: `default SaveOutcome saveText( Context context, String text, SaveGuard guard ) throws WikiException` — the default (for test doubles) checks then calls `saveText(context, text)` without atomicity.
  - `PageSaveHelper`: `public SaveOutcome saveTracked( String pageName, String text, SaveOptions options ) throws WikiException`; `saveText(...)` keeps its signature and returns `saveTracked(...).page()`.

Decisions: the lock is a **per-page-name `ReentrantLock`** held in `DefaultPageLifecycle` (lazily created, e.g. `ConcurrentHashMap<String, ReentrantLock>` with removal when unlocked and unqueued, or Guava `Striped.lazyWeakLock(1024)` if Guava is already a wikantik-main dependency — check `mvn dependency:tree`, add nothing). Lock scope = **guard check → `accessRuleShapes.check` → `putPageText` → reading back the written version**. Pre-save filters run *before* the lock (they depend on the input text, not on the stored page); post-save filtering, reindex and events run *after* unlock, so a listener that saves another page cannot deadlock. The existing identical-text bail-out (`:76-80`) returns `SaveOutcome(latest, latest.version, true)`. `PageSaveHelper.checkOptimisticLocking` becomes the `SaveGuard` it passes; `expectedVersion <= 0` keeps meaning "no version check" (A11's "must not exist" is Phase 1).

- [ ] **Step 1: Write the failing tests** in `GuardedSaveTest` (TestEngine):
  - `aStrictSaveOvertakenWhileInItsFiltersIsRefused` (the defect): register a test pre-save filter that, for page `GuardRace` when the text contains `HOLD`, counts down `entered` and awaits `release`. Seed `GuardRace` v1. Thread A calls `PageSaveHelper.saveTracked("GuardRace", "a HOLD", expectedVersion=1)`; once `entered`, the main thread saves `"b"` with `SaveGuard.NONE` (lands as v2); then release A. Assert A throws `VersionConflictException` and the latest text is `"b"` (today A passes its check before the filters and silently overwrites B as v3).
  - `anUncontendedStrictSaveSucceeds`: the same without B: A's outcome is version 2 with text `"a HOLD"`.
  - `theOutcomeIsTheVersionThisCallWrote`: save v1, then save v2 through `saveText(ctx, text, NONE)`; assert `outcome.version() == 2` and `outcome.page().getVersion() == 2` even though a third save (v3) is made by a post-save listener for that page during the call (register a listener that saves once on `POST_SAVE_END`).
  - `anUnchangedTextReportsUnchangedAndWritesNothing`: save identical text; `outcome.unchanged()` true, version unchanged.
  - `aReentrantSaveOfTheSamePageDoesNotDeadlock`: a post-save listener saves the same page once; the outer call returns within `assertTimeoutPreemptively(10s)`.
  - In the `PageSaveHelper` test: `saveTrackedReturnsTheWrittenVersionNotTheLatest`.
- [ ] **Step 2: Run them red.** `mvn -o -q test -pl wikantik-main -Dtest=GuardedSaveTest -Dsurefire.failIfNoSpecifiedTests=false` → compile failure / FAIL.
- [ ] **Step 3: Implement** the interfaces above. Run every existing `PageSaveHelper`/`PageResource`/MCP write test class to confirm no behaviour change for callers that pass no guard (`mvn -o -q test -pl wikantik-main -Dtest='PageSaveHelper*,DefaultPageLifecycle*,*PageManager*'`, then `-pl wikantik-rest -Dtest='PageResource*'`).
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `fix(pages): atomic guarded save; PageSaveHelper returns the version it wrote (MultiEdit A2)`; add a CHANGELOG `[Unreleased]` → `### Fixed` bullet: strict-mode (`expectedVersion`) saves could be overtaken by an unguarded save between the check and the write.

### Task 2: Async-supported `/api/*` filter chain (A7)

**Files:**
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (add `<async-supported>true</async-supported>` to `BasicAuthFilter`, `RememberMeAuthFilter`, `MustChangePasswordFilter`, `BuildVersionFilter`); the `RequestMetricsFilter` and `RequestCorrelationFilter` classes (find with `grep -rl 'class RequestMetricsFilter\|class RequestCorrelationFilter' --include=*.java`)
- Test: `wikantik-war/src/test/java/com/wikantik/war/ApiFilterChainAsyncTest.java`; unit tests next to each changed filter class

**Interfaces:** none produced for later tasks; Phase 1's `CollabResource` relies on the chain being async.

Decisions: the chain test parses `web.xml` **and** `wikantik-http/src/main/resources/META-INF/web-fragment.xml`, resolves which filters' `url-pattern`s match the path `/api/collab/Page/updates` (servlet spec matching: exact, `/prefix/*`, `*.ext`, `/`), and asserts each is `async-supported=true`. When a request is in async mode after `chain.doFilter` returns (`request.isAsyncStarted()`), `RequestMetricsFilter` records in an `AsyncListener.onComplete/onTimeout/onError` (status from the response then), and `RequestCorrelationFilter` keeps the MDC/correlation id for the async completion and clears it in the listener instead of its `finally`. Each filter class needs a test with a Mockito request whose `isAsyncStarted()` is true and whose `getAsyncContext()` captures the listener: assert nothing is recorded/cleared until the listener fires.

- [ ] **Step 1: Write `ApiFilterChainAsyncTest`** (`everyFilterOnTheCollabPathIsAsyncSupported`) and the two filter tests (`recordsTheLatencyWhenTheAsyncRequestCompletes`, `keepsTheCorrelationIdUntilTheAsyncRequestCompletes`).
- [ ] **Step 2: Run red** (`mvn -o -q test -pl wikantik-war -Dtest=ApiFilterChainAsyncTest`; the filter tests in their module). The chain test must name the four filters.
- [ ] **Step 3: Implement** the web.xml flags and the two `AsyncListener` paths. Re-read each of the four newly-async filters for work done after `chain.doFilter` returns and list any in the commit message (the spec flags ThreadLocals such as `AuditRequestContext`, `FrontmatterWarningSink`, `ContentWarningSink`; leave them as they are when only synchronous servlets set them).
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `fix(http): every /api/* filter is async-supported; metrics and MDC survive async (MultiEdit A7)`.

### Task 3: `/api/collab/` leaves the per-IP default bucket (A8)

**Files:**
- Modify: `RateLimitFilter` (find with grep; it reads env vars `WIKANTIK_RATELIMIT_*`, defaults at `:95-102`)
- Test: that filter's existing test class

Decisions: add `ENV_SESSION_PATHS = "WIKANTIK_RATELIMIT_SESSION_PATHS"`, default `"/api/collab/"`: requests whose path (after the context path) **starts with one of these prefixes, each ending in `/`**, skip the per-IP default bucket (the expensive and global buckets do not apply to them either). The collab session enforces per-participant caps in Phase 1; record that in the class Javadoc. No `wikantik.*` property (the filter is env-configured), so `ConfigSurfaceDriftTest` is unaffected.

- [ ] **Step 1: Write the failing tests** `collabRequestsDoNotSpendTheIpBucket` (with the default per-client limit set low through the existing test seam, 50 requests to `/api/collab/P/updates` from one IP all pass) and `onlyTheCollabPathPrefixIsExempt` (`/api/collaborate` and `/api/collabX/a` are limited as before).
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `feat(http): session-limited paths skip the per-IP bucket; /api/collab/ by default (MultiEdit A8)`.

### Task 4: F0 — the ChangeSet conformance corpus

**Files:**
- Create: `wikantik-frontend/scripts/collab-fixtures.mjs`, `wikantik-frontend/src/collab/__fixtures__/changesets.json` (generated), `wikantik-frontend/src/collab/changesets.conformance.test.js`
- Modify: `wikantik-frontend/package.json` + `package-lock.json` (script `"collab:fixtures": "node scripts/collab-fixtures.mjs"`; add `@codemirror/collab` with `npm install --save-exact`, pinned to a release at least two weeks old — check `npm view @codemirror/collab time` — so Task 11 can read its `receiveUpdates` source and Task 13 can use it)

**Interfaces:**
- Produces `changesets.json`:
  ```json
  { "generator": "collab-fixtures.mjs mulberry32(129)", "library": "@codemirror/state <exact installed version>", "cases": [
    { "doc": "...", "a": [..], "b": [..], "c": [..],
      "applyA": "...", "composeAC": [..], "mapBA": [..], "mapBABefore": [..],
      "positions": [ { "pos": 3, "left": 3, "right": 5 } ] } ] }
  ```
  `a` and `b` are concurrent change sets on `doc`; `c` is a change set on `applyA`; all change sets are `ChangeSet.toJSON()`. `mapBA = b.map(a)`, `mapBABefore = b.map(a, true)`, `composeAC = a.compose(c)`; `positions[i].left/right = a.mapPos(pos, -1/1)` for every `pos` in `0..doc.length` when the doc is ≤ 40 chars, else 20 sampled positions.

Decisions: 1,200 cases from a seeded `mulberry32(129)`: docs of length 0–200 drawn from an alphabet that includes ASCII, `\n`, CJK (`中文`), and astral characters (`😀`, `𝔸`); 0–4 changes per set at random ranges that never split a surrogate pair; 1/10 of docs empty; every set touches position 0 or the end at least sometimes. The script imports `ChangeSet` from `@codemirror/state` and writes with `JSON.stringify(_, null, 0)` one case per line. The vitest test replays every case through the real library and asserts each recorded field (it fails if a library upgrade changes semantics).

- [ ] **Step 1: Write `changesets.conformance.test.js`** reading the fixture (red: file absent).
- [ ] **Step 2: Run red.** `npx vitest run src/collab/changesets.conformance.test.js`.
- [ ] **Step 3: Write the generator; run `npm run collab:fixtures`.**
- [ ] **Step 4: Run green; `npx eslint scripts/collab-fixtures.mjs src/collab`; `npm audit --omit=dev` reports nothing new.**
- [ ] **Step 5: Commit** `test(collab): ChangeSet conformance corpus from the real @codemirror/state (F0)`.

### Task 5: Java `ChangeSet` + `ChangeSetJson`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/collab/text/ChangeSet.java`, `ChangeSetJson.java`, `CollabText.java`
- Test: `wikantik-main/src/test/java/com/wikantik/collab/text/ChangeSetConformanceTest.java`, `ChangeSetPropertiesTest.java`, `CollabTextTest.java`

**Interfaces:**
- Consumes: `changesets.json` (Task 4), read via `Path.of("..", "wikantik-frontend", "src", "collab", "__fixtures__", "changesets.json")`.
- Produces (all in `com.wikantik.collab.text`, immutable):
  - `ChangeSet`: `int length()`, `int newLength()`, `boolean empty()`, `String apply(String doc)` (`IllegalArgumentException` when `doc.length() != length()`), `ChangeSet compose(ChangeSet next)`, `ChangeSet map(ChangeSet other, boolean before)`, `ChangeSet map(ChangeSet other)` (= `before=false`), `int mapPos(int pos, int assoc)` (`assoc` −1 or 1), `static ChangeSet empty(int length)`, `static ChangeSet replace(int length, int from, int to, String insert)`, `List<int[]> touchedRanges()` (`{fromA, toA}` of each change, ascending).
  - `ChangeSetJson`: `static ChangeSet fromJson(com.google.gson.JsonArray json)` (rejects malformed input with `IllegalArgumentException` naming the defect), `static JsonArray toJson(ChangeSet cs)`; `toJson(fromJson(x))` equals `x`.
  - `CollabText`: `static String toLf(String text)` (CRLF and lone CR → LF), `static boolean hasLoneSurrogate(CharSequence s)`, `static boolean splitsSurrogatePair(String doc, ChangeSet cs)` (any change boundary between a high and a low surrogate of `doc`).

Decisions: mirror CodeMirror's internal representation — a flat `int[] sections` of `(len, ins)` pairs, `ins == -1` meaning "kept", plus `String[] inserted` — so the port follows `@codemirror/state/src/change.ts` function by function (`composeSets`, `mapSet`, `mapPos`); read that source in `node_modules/@codemirror/state/dist/index.js`. Inserted text is stored as a joined string; JSON uses CodeMirror's line-array form (`[deleted, ...lines]`).

- [ ] **Step 1: Write the failing tests:** `ChangeSetConformanceTest.everyCaseMatchesTheLibrary` (for each case: `fromJson(a).apply(doc) == applyA`; `toJson(a.compose(c)) == composeAC`; `toJson(b.map(a)) == mapBA`; `toJson(b.map(a, true)) == mapBABefore`; every `mapPos`); `ChangeSetPropertiesTest` seeded loops (seed 128, 5,000 iterations): `apply` length invariant, `compose` associativity, the diamond `a.compose(b.map(a)).apply(d) == b.compose(a.map(b, true)).apply(d)`, JSON round trip; `aBoundaryInsideASurrogatePairIsDetected`; `CollabTextTest` for `toLf` and lone surrogates; `malformedJsonIsRejected` (non-array, negative length, wrong element type, insert past end).
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `feat(collab): Java ChangeSet held to @codemirror/state by the shared corpus`.

### Task 6: `TextDiff`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/collab/text/TextDiff.java`
- Test: `wikantik-main/src/test/java/com/wikantik/collab/text/TextDiffTest.java`

**Interfaces:**
- Consumes: `ChangeSet.replace`, `compose` (Task 5).
- Produces: `static ChangeSet changeSet(String from, String to)` with `changeSet(x, y).apply(x).equals(y)`, both inputs LF.

Decisions: common prefix/suffix trimmed first; the middle diffed with jrcs `MyersDiff` (as `TraditionalDiffProvider` uses it) at **line** granularity; each changed hunk refined to characters by prefix/suffix trimming within the hunk (not a second Myers). Change boundaries never split a surrogate pair (widen by one char when they would). A hunk refinement must not be quadratic.

- [ ] **Step 1: Write the failing tests:** seeded loop (seed 6, 3,000 pairs including astral and empty texts) asserting `apply` equality and `!splitsSurrogatePair`; `anEditInTheMiddleOfALineTouchesOnlyThatSpan` (touched ranges equal the edited span); `aLongSingleLineEditDiffsInLinearTime` (256 KB line, one char changed in the middle: under 200 ms after warm-up, and a 4× input costs < 8× — compare ratios like `LinearTime`).
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `feat(collab): TextDiff, line Myers refined to characters`.

### Task 7: Collab ports, value records, seam rule

**Files:**
- Create in `wikantik-main/src/main/java/com/wikantik/collab/`: `CollabPageWriter.java`, `CollabStore.java`, `CollabPermissions.java`, `CollabIdentity.java`, `model/PageState.java`, `model/PublishRequest.java`, `model/PublishOutcome.java`, `model/SessionRecord.java`, `model/StoredUpdate.java`, `model/StoredMetaOp.java`, `model/Snapshot.java`, `package-info.java`
- Create: `wikantik-war/src/test/java/com/wikantik/architecture/CollabSeamArchTest.java`

**Interfaces (produced; later tasks and Phase 1 implement or use them):**
- `record PageState(int version, String body, Map<String,Object> meta, String contentHash)` — `body` as stored (CRLF allowed); callers apply `CollabText.toLf`.
- `record PublishRequest(String pageName, String body, Map<String,Object> meta, int expectedVersion, String authorKey, String changeNote)`; `record PublishOutcome(int version, String savedBody, Map<String,Object> savedMeta, boolean unchanged)`.
- `interface CollabPageWriter { Optional<PageState> latest(String pageName); Optional<PageState> version(String pageName, int version); PublishOutcome publish(PublishRequest request) throws com.wikantik.api.exceptions.WikiException; }`
- `record SessionRecord(UUID id, String pageName, String status, int basePageVersion, String baseBody, Map<String,Object> baseMeta, int headVersion, long metaSeq, boolean hasUnpublished, Integer pendingPublishVersion, Integer pendingPublishBaseVersion, String createdBy, Instant createdAt, Instant lastEditAt)` with `withHead(int)`, `withPending(Integer, Integer)`, `withBase(int, String, Map)` copy methods. Status values `"active"`, `"frozen"`.
- `record StoredUpdate(int version, String kind, String clientId, String authorKey, ChangeSet changes)` (`kind` `"text"`/`"server"`); `record StoredMetaOp(long seq, String authorKey, String field, Object value)`; `record Snapshot(int version, long metaSeq, String body, Map<String,Object> meta, ChangeSet local)`.
- `interface CollabStore` (§10's tables):
  `void createSession(SessionRecord s)` (`IllegalStateException` if the page already has a session) · `Optional<SessionRecord> session(UUID id)` · `Optional<SessionRecord> sessionForPage(String pageName)` · `void updateSession(SessionRecord s)` (base, status, page name, pending marker, last edit; never `headVersion`/`metaSeq`) · `void appendUpdates(UUID id, List<StoredUpdate> updates)` (atomic; the first version must be `headVersion + 1` and they must be contiguous, else `IllegalStateException`; bumps `headVersion`) · `List<StoredUpdate> updatesSince(UUID id, int sinceVersion)` (ascending, `version > since`) · `void appendMetaOps(UUID id, List<StoredMetaOp> ops)` (same contiguity rule on `metaSeq`) · `List<StoredMetaOp> metaOpsSince(UUID id, long sinceSeq)` · `void writeSnapshot(UUID id, Snapshot s)` · `Optional<Snapshot> latestSnapshot(UUID id)` · `void truncateUpdatesBelow(UUID id, int version)` (A10) · `void deleteSession(UUID id)`.
- `interface CollabPermissions { boolean canEdit(Object participantSession, String pageName); boolean canCreate(Object participantSession, String pageName); }` — the wiki `Session` per A5; typed `Object` here so the port stays engine-free; the Phase 1 adapter casts.
- `interface CollabIdentity { String displayName(String authorKey); }`
- `CollabSeamArchTest` (ArchUnit, `@AnalyzeClasses(packages = "com.wikantik.collab", importOptions = DoNotIncludeTests)`): classes in `com.wikantik.collab..` except `..collab.adapter..` must not call `Thread.sleep`, `System.currentTimeMillis`, `Instant.now()`, `LocalDateTime.now()`, must not have `synchronized` methods, and must not depend on `com.wikantik.WikiEngine`, `com.wikantik.api.core.Engine` or `java.sql..`.

- [ ] **Step 1: Write `CollabSeamArchTest`.** Prove it bites: with the records in place (Step 3), add a temporary `Instant.now()` call to `model/Snapshot.java`, run the test red, remove the call; say so in the report.
- [ ] **Step 2: Run red** (`mvn -o -q install -DskipTests -pl wikantik-main && mvn -o -q test -pl wikantik-war -Dtest=CollabSeamArchTest`).
- [ ] **Step 3: Create the interfaces and records** (records validate nulls and non-negative versions in compact constructors; maps copied with `Map.copyOf` where values are non-null — meta values may be null in `StoredMetaOp` only).
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `feat(collab): ports and value records; ArchUnit seam rule`.

### Task 8: F5 `MutableClock`, F9 `InMemoryCollabStore` + contract test, F8 fault points

**Files:**
- Create in `wikantik-main/src/test/java/com/wikantik/collab/testing/`: `MutableClock.java`, `InMemoryCollabStore.java`, `AbstractCollabStoreContractTest.java`, `InMemoryCollabStoreContractTest.java`

**Interfaces:**
- Consumes: `CollabStore`, records (Task 7).
- Produces: `MutableClock extends Clock` — `MutableClock(Instant start)`, `void advance(Duration d)`, `void set(Instant t)`; `InMemoryCollabStore implements CollabStore` — plus `enum Op { CREATE_SESSION, UPDATE_SESSION, APPEND_UPDATES, APPEND_META, WRITE_SNAPSHOT, TRUNCATE, DELETE }`, `void failOn(Op op, int nth)` (the n-th call of that op throws `IllegalStateException("injected failure: …")` **before** mutating anything), `InMemoryCollabStore copy()` (deep copy, for "recover over the same store"); `AbstractCollabStoreContractTest` with `protected abstract CollabStore newStore()`. Phase 1 adds `JdbcCollabStoreContractTest` beside it.

- [ ] **Step 1: Write the contract tests** (one per `CollabStore` method's documented rule, including `appendUpdatesRejectsAGapOrRepeat`, `appendUpdatesIsAtomic` (two updates, contiguity broken in the second → nothing stored, head unchanged), `updateSessionNeverMovesTheHead`, `truncateKeepsVersionsAtAndAbove`, `aSecondSessionForThePageIsRefused`, `deleteSessionRemovesItsLogs`) and `MutableClockTest`, `failOnInjectsBeforeMutating`.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** (a `ReentrantLock`-guarded map store is fine in test scope).
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `test(collab): MutableClock, InMemoryCollabStore and the store contract (F5, F8, F9)`.

### Task 9: F4 `ScriptedPageWriter`

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/collab/testing/ScriptedPageWriter.java`, `ScriptedPageWriterTest.java`

**Interfaces:**
- Consumes: `CollabPageWriter`, `PageState`, `PublishRequest`, `PublishOutcome` (Task 7).
- Produces `ScriptedPageWriter implements CollabPageWriter`:
  - `void seed(String pageName, String body, Map<String,Object> meta)` (creates version 1)
  - `PauseHandle pauseNextPublish()`; `interface PauseHandle { void awaitEntered(Duration timeout); void release(); void releaseWith(Exception e); boolean entered(); }` — while paused the publishing thread blocks; in the single-threaded simulator use `publishAsync(request)` which returns a `PendingPublish` the simulator later `complete()`s, so both styles share the pause point.
  - `void failNextPublish(Exception e)`; `void persistThenFail(Exception e)` (stores the version, then throws); `void rewriteOnSave(UnaryOperator<String> fn)`
  - `int externalSave(String pageName, String body, Map<String,Object> meta, String authorKey)` returning the new version, notifying `Consumer<ExternalChange>` listeners registered with `onExternalChange(Consumer<ExternalChange>)`; `record ExternalChange(String pageName, int version, String source)`; `void overwriteWithoutVersion(String pageName, String body)` (the `VersioningFileProvider`/watcher shape from A1)
  - `void rename(String from, String to)`, `void delete(String pageName)`, `List<String> history(String pageName)`.
  - `publish` honours `expectedVersion` atomically (A2 semantics: `> 0` must equal latest else `VersionConflictException`), applies `rewriteOnSave`, stores CRLF like the real save (`TextUtil.normalizePostData`-equivalent: LF→CRLF plus trailing CRLF) so A9 is exercised, and reports `unchanged` for identical text.

- [ ] **Step 1: Write `ScriptedPageWriterTest`** covering every knob above (including `aPausedPublishBlocksUntilReleased` with real threads and `persistThenFailStoresTheVersion`).
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `test(collab): ScriptedPageWriter, the controllable save pipeline (F4)`.

### Task 10: `SimServer`, the stub authority, `CollabInvariants`

**Files:**
- Create in `wikantik-main/src/test/java/com/wikantik/collab/testing/`: `SimServer.java`, `StubCollabAuthority.java`, `CollabInvariants.java`, `StubCollabAuthorityTest.java`, `CollabInvariantsTest.java`

**Interfaces:**
- Consumes: `ChangeSet`, `ChangeSetJson`, `CollabText`, `TextDiff`, `CollabStore`, `CollabPageWriter` (Tasks 5–9).
- Produces:
  - `record Update(String clientId, ChangeSet changes)`
  - `interface SimServer` — the protocol surface Phase 1's `CollabSession` will be adapted to:
    `Joined join(String authorKey)`; `PushResult push(String participantId, int version, List<Update> updates)`; `PullResult pull(String participantId, int since)`; `PublishResult publish(String participantId, int version, String note)`; `void leave(String participantId)`; `List<Wakeup> drainWakeups()`; `InvariantView view()`; `void tick()` (timeouts, from the clock).
  - Records: `Joined(String participantId, String clientId, int version, String doc)`; `PushResult(int status, int version)` (200 accepted, 409 stale, 400 rejected); `PullResult(int status, List<Update> updates, int version, boolean parked)` (200; `parked` true means the pull is waiting and its answer will arrive through `drainWakeups()`; 410 resync); `Wakeup(String participantId, PullResult result)`; `PublishResult(int status, int pageVersion)`.
  - `record InvariantView(UUID sessionId, String doc, int head, String baseBody, ChangeSet local, int basePageVersion, List<StoredUpdate> ring, boolean oversize, long maxPageBytes, CollabStore store, CollabPageWriter writer, String pageName, boolean externalChangePending, boolean publishPending)`
  - `StubCollabAuthority implements SimServer`: one page, constructed with `(String pageName, CollabStore, CollabPageWriter, Clock, long maxPageBytes, int ringSize)`. Implements §7.2 push exactly (version check, clientID binding, length check, surrogate check, size check, store first then memory, `local` composed), pull with parking and wake on push, ring with 410 when `since` is below it, timeout via `tick()` after 25 s, and a **naive** publish (requires `version == head`, saves `doc`, sets base to the saved LF body, resets `local`). No external merges, no meta, no presence: those are Phase 1/2 and land in the real `CollabSession`.
  - `final class CollabInvariants`: `static void check(InvariantView v)` throws `AssertionError` naming the invariant (1–7 of §13.0; 3 only when `checkReplay` is true); `static void check(InvariantView v, boolean checkReplay)`. Invariant 4 is skipped while `publishPending || externalChangePending`; invariant 7 is a no-op until presence exists (document that).
- [ ] **Step 1: Write the failing tests:** `StubCollabAuthorityTest` for E8 (stale push 409), E10 (duplicated push → second is 409, applied once), E14 (other participant's clientID → 400, unchanged), E15 (length mismatch, malformed → 400, unchanged), a lone-surrogate push → 400, an oversize push → 413, pull parking and wake, 410 below the ring, timeout empty 200 after `tick()` past 25 s, naive publish; `CollabInvariantsTest` builds a valid view and one violating view per invariant and asserts each message names it.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `test(collab): SimServer protocol surface, stub authority, CollabInvariants`.

### Task 11: F2 `SimClient`

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/collab/testing/SimClient.java`, `SimClientTest.java`

**Interfaces:**
- Consumes: `Update`, `Joined`, `PushResult`, `PullResult`, `ChangeSet` (Tasks 5, 10).
- Produces `SimClient` — a Java model of `@codemirror/collab`'s client half: `SimClient(String name, Joined joined)`; `String doc()`; `int version()`; `void localEdit(ChangeSet cs)` (must apply to `doc()`); `List<Update> sendable()` (unconfirmed, in order); `void receive(List<Update> updates, int fromVersion)` (`receiveUpdates`: own updates by `clientId` confirm, others rebase unconfirmed ones — port it from `wikantik-frontend/node_modules/@codemirror/collab/dist/index.js`, installed in Task 4); `void onPushResult(PushResult r)`; `void resync(Joined fresh)` (A10: unsynced text goes to `recoveryText()`, not re-applied); `String recoveryText()`; `List<String> trace()` (protocol events for F3).
- [ ] **Step 1: Write `SimClientTest`:** own-update confirmation; rebase of a concurrent remote update (E4: two clients insert at the same position end in the same order); E9 (applied push whose response is lost: the client's own updates come back in a pull and are confirmed, nothing applied twice); E11 (a stale or duplicated pull batch with `fromVersion <= version()` is ignored); resync keeps unsynced text in `recoveryText()`.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `test(collab): SimClient, a Java model of the collab client (F2)`.

### Task 12: F1 simulated network, fuzz loop, shrinker, regression replay

**Files:**
- Create in `wikantik-main/src/test/java/com/wikantik/collab/testing/`: `SimNetwork.java`, `SimScheduler.java`, `SimProfile.java`, `SimTrace.java`, `Shrinker.java`, `CollabConvergenceFuzzTest.java`, `CollabRegressionTracesTest.java`; resource dir `wikantik-main/src/test/resources/collab/regressions/` (with a `.gitkeep`)

**Interfaces:**
- Consumes: `SimServer`, `StubCollabAuthority`, `SimClient`, `CollabInvariants`, `ScriptedPageWriter`, `InMemoryCollabStore`, `MutableClock` (Tasks 5–11).
- Produces:
  - `enum SimProfile { CALM, LOSSY, AGENT_HEAVY, PUBLISH_STORM }` each with action weights (local edit, send push, send pull, deliver, drop, duplicate, advance clock, external save, publish, crash-recover); `AGENT_HEAVY` and external saves/crash use the stub only where it supports them (it ignores external saves until Phase 1 — weight 0 for the stub run).
  - `SimScheduler(long seed, SimProfile profile, Supplier<SimServer> serverFactory, int clients)`; `SimTrace run(int steps)`; after every step `CollabInvariants.check(server.view(), step % 25 == 0)`; at quiescence (drain: deliver all, push all, pull all, until no messages) every `client.doc()` equals `view().doc()`.
  - `SimTrace` — JSON-serialisable: seed, profile, initial doc, ordered steps (`{action, client, messageId, …}`), per-client local edits (as ChangeSet JSON) and received batches (`fromVersion`, updates) — the F3 export format.
  - `Shrinker.shrink(SimTrace failing, Predicate<SimTrace> stillFails)` — repeatedly drop single steps while the predicate holds; replay is by trace, not by seed.
  - On failure the fuzz test writes `target/collab-failures/<seed>.json` (full) and `<seed>.shrunk.json`, and the assertion message prints seed, profile and the paths.
  - Seeds: `-Dcollab.fuzz.seeds` (default 2,000), steps 200; `CollabRegressionTracesTest` replays every `regressions/*.json` against a fresh stub.
- [ ] **Step 1: Write `CollabConvergenceFuzzTest`** (parameterised over profiles) and `CollabRegressionTracesTest`; plus `SimSchedulerTest.theSameSeedReplaysTheSameTrace` and `ShrinkerTest.dropsStepsThatDoNotMatter` (a synthetic predicate).
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement.** The default run must finish in under 60 s on the dev box; report the time.
- [ ] **Step 4: Run green** — every profile converges against the stub; if the fuzzer finds a stub or `ChangeSet` defect, fix it test-first, commit its shrunk trace under `regressions/`.
- [ ] **Step 5: Commit** `test(collab): seeded simulator, shrinker and convergence fuzz (F1)`.

### Task 13: F3 cross-language trace replay

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/collab/testing/CollabTraceCorpusDriftTest.java`, `wikantik-frontend/src/collab/__fixtures__/collab-traces/*.json` (generated), `wikantik-frontend/src/collab/collabTraceReplay.test.js`

**Interfaces:**
- Consumes: `SimTrace` (Task 12).
- Produces: a corpus of 200 traces (50 seeds × 4 profiles, seeds `1..50`, 120 steps each); `CollabTraceCorpusDriftTest` regenerates them and fails on any difference unless `-Dcollab.traces.write=true` is set, in which case it writes them (the same refresh pattern as `CommentsDifferentialFixture`). The traces pin **protocol messages**, not random draws (§0 Minor). `collabTraceReplay.test.js` drives a real `EditorState` with `collab({ startVersion, clientID })` per client, applies each local edit, compares `sendableUpdates` (as ChangeSet JSON) and the document with the Java client's recorded values after every step, and feeds received batches through `receiveUpdates`.

- [ ] **Step 1: Write both tests** (red: no corpus).
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Write the corpus with the drift test's write switch.**
- [ ] **Step 4: Run green**, plus `npx eslint src/collab`.
- [ ] **Step 5: Commit** `test(collab): Java client traces replayed through the real @codemirror/collab (F3)`.

### Task 14: F6 `FakeAsyncContext`, F7 `ConcurrencyHarness`, F8 recovery helper

**Files:**
- Create in `wikantik-main/src/test/java/com/wikantik/collab/testing/`: `FakeAsyncContext.java`, `ConcurrencyHarness.java`, `Recovery.java`, and their tests `FakeAsyncContextTest.java`, `ConcurrencyHarnessTest.java`, `RecoveryTest.java`

**Interfaces:**
- Consumes: `SimServer`, `StubCollabAuthority`, `InMemoryCollabStore`, `ScriptedPageWriter`, `CollabInvariants` (Tasks 8–10).
- Produces:
  - `FakeAsyncContext implements jakarta.servlet.AsyncContext` — records `complete()`, fails (`AssertionError`) on a second `complete()`, `void fireTimeout()` invokes registered listeners' `onTimeout`, `void assertCompletedOnce()`. (`jakarta.servlet-api` must be on wikantik-main's test classpath; check the pom; add it as `test` scope only if absent.)
  - `ConcurrencyHarness(int threads, int runs, Duration runTimeout, Supplier<SimServer> factory)`, `Report run(long seed)` — workers released by a `CyclicBarrier` perform a seeded mix of join/push/pull/publish/leave; on timeout it dumps all thread stacks and fails; after each run asserts contiguous accepted versions, store log == memory ring suffix, and `CollabInvariants`. Tagged `@Tag("stress")`; default `runs = 50`, `-Dcollab.stress.runs` overrides.
  - `static SimServer Recovery.recover(InMemoryCollabStore store, ScriptedPageWriter writer, BiFunction<CollabStore, CollabPageWriter, SimServer> reload)` — discards the old server and builds a fresh one over the same store and writer. For the stub, `static StubCollabAuthority StubCollabAuthority.reload(String pageName, CollabStore, CollabPageWriter, Clock, long maxPageBytes, int ringSize)` rebuilds `doc`, `head` and `local` from the latest snapshot plus the log (recomputing `local` as `TextDiff.changeSet(baseBody, doc)`, §7.1).
- [ ] **Step 1: Write the tests:** `FakeAsyncContextTest` (double complete fails; timeout fires listeners); `ConcurrencyHarnessTest.e38_parallelPushesToOneVersionAcceptExactlyOne` against the stub; `RecoveryTest.e35_aFailedAppendLeavesMemoryUnchanged` (`failOn(APPEND_UPDATES, 1)`), `recoveredEqualsCommitted` (E37 against the stub).
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** (the stub needs a `ReentrantLock` around push/publish for E38 — it is test code, the seam rule does not apply).
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Commit** `test(collab): FakeAsyncContext, concurrency harness, crash recovery (F6–F8)`.

### Task 15: Phase 0 close-out

**Files:**
- Modify: `docs/MultiEdit.MD` (§15: mark Phase 0 done with commit list; note the harness's actual run times), `docs/developer/ProjectReference.md` (status block), `CHANGELOG.md` (`[Unreleased]`: A7 and A8 under `### Changed`)

- [ ] **Step 1: Run the full gate** `bin/agent-build.sh start p0 -- bin/run-tests.sh --parallel 4` and poll to SUCCESS.
- [ ] **Step 2: Update the docs.**
- [ ] **Step 3: Commit** `docs(MultiEdit): Phase 0 complete`.
