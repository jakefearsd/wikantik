# MultiEdit Phase 1 — Core Text Collaboration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Several authenticated editors of one page share a live text draft, persisted in Postgres, and any of them publishes it through the save pipeline. The feature ships dark behind `wikantik.collab.enabled=false`.

**Architecture:**
- **Engine-free core.** `CollabSession` and `CollabSessionRegistry` (`com.wikantik.collab`) implement §7 over the Phase 0 ports. They are driven by the Phase 0 harness through a `SimServer` adapter, so every fuzz, recovery and concurrency test written against the stub now also runs against the real session.
- **Adapters at the edges.** `com.wikantik.collab.adapter` holds the save pipeline, Postgres, permissions and identity adapters. `CollabResource` in wikantik-rest is the async HTTP surface.
- **Frontend.** A `collabPeer` CodeMirror plugin drives `@codemirror/collab` inside an uncontrolled editor.

**Tech Stack:**
- Server: Java 25, `com.wikantik.jdbc.Jdbc`, Postgres with migration `V066`, Servlet async (Tomcat 11).
- Frontend: React plus CodeMirror 6, `@codemirror/collab` 6.1.1, vitest.
- Integration tests: Cargo, under `wikantik-it-tests/wikantik-it-test-rest`.

**Spec:** `docs/MultiEdit.MD`. **§0 Review amendments are authoritative.** Read §0, §6–§7, §8.1–8.2, §9, §10, §11 and §13.2–13.4. Phase 0 (`docs/superpowers/plans/2026-11-06-multiedit-phase0-foundations.md`) defines every port, record and harness type used below; its ledger records the actual names.

## Global Constraints

- Every Phase 0 global constraint applies: TDD red-first; never swallow exceptions; no `synchronized`, `Thread.sleep` or wall clock in `com.wikantik.collab..` outside `..adapter..` (`CollabSeamArchTest`); LF-only text; Gson; `Jdbc` only; stage files by name; the commit trailer.
- Migration `bin/db/migrations/V066__collab_sessions.sql` contains DDL only, is idempotent, grants to `:app_user`, keys `page_version_contributor` by `canonical_id`, and is a no-op on re-apply. No data backfill.
- Config keys are exactly §11's ten. Each is declared in `wikantik-main/src/main/resources/ini/wikantik.properties` under a new `# [Collaborative editing]` section, with a description, `Type: int`/`boolean` and an explicit default, **before** code reads it. Then regenerate with `bin/config-reference.sh --write`.
- The kill switch `wikantik.collab.enabled` defaults to `false`. When it is off, `join` answers 503 `{reason:"collab-disabled"}` and the editor behaves exactly as today.
- Wire format and status codes: §6.1/§6.2 as amended. Every 409 carries a `reason` (A10 Minor). Presence is piggybacked on push and pull (A8/A12); there is no `/presence` endpoint in v1.
- Every `/api/collab/*` response carries `Cache-Control: no-store`.
- The registry is a plain engine field reached from wikantik-rest. It is **never** added to the `getManager` allow-list (DecompositionArchTest R-2); follow the `queryLogReader` field pattern near `WikiEngine.java:1552`.

## Review Focus

1. **Lost acknowledgement.** A client whose accepted push's response was lost retries and must not duplicate text. Covered by E9, run against the real session in Task 3.
2. **External save during publish.** A save that lands while a publish is inside the page save must not duplicate the published text (A3). Task 4 test `externalSaveDuringPublishIsMergedOnceAfterIt`.
3. **Crash after save, before post-publish.** Recovery must claim the version only when its hash and author match the marker (A2/E39). Task 4 tests `e39_recoveryFinishesThePublish` and `e39_aForeignVersionIsNotClaimed`.
4. **Two tabs, one user.** Each tab is its own participant. Neither may push with the other's clientID (A6). Task 3 test `twoTabsOfOneUserAreTwoParticipants`.
5. **Kill switch flipped on a live session.** Pushes already in flight complete, new joins get 503, and nothing is lost. Task 7 test `disablingCollabRefusesJoinsButKeepsDrafts`.

---

### Task 1: Migration `V066` + `JdbcCollabStore`

**Files:**
- Create: `bin/db/migrations/V066__collab_sessions.sql`
- Create: `wikantik-main/src/main/java/com/wikantik/collab/adapter/JdbcCollabStore.java`
- Test: `wikantik-main/src/test/java/com/wikantik/collab/adapter/JdbcCollabStoreContractTest.java` (extends Phase 0's `AbstractCollabStoreContractTest`, `@RequiresPostgres`, `PostgresTestDb.truncate("collab_session", …)` in `@BeforeEach`)
- Test: `JdbcCollabStoreRollbackTest.java`, using `FaultInjectingDataSource`

**Interfaces:**
- Consumes `CollabStore` and its records (Phase 0 Task 7).
- Produces `public JdbcCollabStore(Jdbc jdbc)`.

The schema is §10 as amended:
- `page_version_contributor(canonical_id TEXT, page_version INTEGER, author_key TEXT, PRIMARY KEY(canonical_id, page_version, author_key))`;
- `GRANT SELECT, INSERT, UPDATE, DELETE ON … TO :app_user` for every table;
- JSONB columns are written with Gson and read back to `Map<String,Object>` normalised through JSON (A14).

`appendUpdates` runs inside `Jdbc.inTransaction`: insert the rows, then `UPDATE collab_session SET head_version = head_version + n WHERE id = ? AND head_version = ?`. If 0 rows update, it throws `IllegalStateException`; that check is the ordering arbiter (§12.4).

- [ ] **Step 1:** Write the contract subclass and the rollback tests:
  - `appendUpdatesRollsBackOnTheSecondStatement` (fail on statement 2; assert no rows and the head unchanged);
  - `aMigrationReapplyIsANoOp` (apply V066 twice through `MigrationApplier`).
- [ ] **Step 2:** Run red: `mvn -o -q test -pl wikantik-main -Dtest='JdbcCollabStore*'`. Docker is required; the test is skipped without it, and that skip must show as a visible reason.
- [ ] **Step 3:** Write the migration and the store.
- [ ] **Step 4:** Run green. Also run `TestSchemaSingleSourceTest`.
- [ ] **Step 5:** Commit `feat(collab): V066 schema and JdbcCollabStore held to the store contract`.

### Task 2: Configuration surface

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (new section)
- Create: `wikantik-main/src/main/java/com/wikantik/collab/CollabConfig.java`, a record of the ten values plus `static CollabConfig from(Properties p)`
- Regenerated: `docs/ConfigurationReference.md` and the wiki page `WikantikConfigurationReference` (`bin/config-reference.sh --write`)
- Test: `CollabConfigTest.java`; `ConfigSurfaceDriftTest` and `ConfigReferenceRegressionTest` must pass

- [ ] **Step 1:** Write `CollabConfigTest`: defaults equal §11; out-of-range values (≤ 0 where a positive value is required) fall back to the default with a WARN. Run it red.
- [ ] **Step 2:** Declare the keys and implement `from`.
- [ ] **Step 3:** Regenerate the docs. Run green: `mvn -o -q test -pl wikantik-main -Dtest=CollabConfigTest`, then `-pl wikantik-war -Dtest='ConfigSurfaceDriftTest,ConfigReferenceRegressionTest'`.
- [ ] **Step 4:** Commit `feat(collab): configuration keys (dark by default)`.

### Task 3: `CollabSession` — join, push, pull, ring, catch-up, snapshots

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/collab/CollabSession.java`, `Participant.java`, `UpdateRing.java`, `PullWaiter.java` (an interface the resource implements; the session only calls `complete(PullResponse)`), `model/JoinRequest.java`, `model/JoinResponse.java`, `model/PushRequest.java`, `model/PushResponse.java`, `model/PullResponse.java`
- Create (test): `wikantik-main/src/test/java/com/wikantik/collab/testing/CollabSessionSimServer.java`, a `SimServer` adapter over the real session
- Test: `CollabSessionTest.java`. Also parameterise `CollabConvergenceFuzzTest`, `CollabRegressionTracesTest`, `ConcurrencyHarnessTest` and `RecoveryTest` over `{stub, session}`.

**Interfaces:**
- `CollabSession.create(SessionRecord, CollabStore, Clock, CollabConfig)` and `CollabSession.load(UUID, CollabStore, Clock, CollabConfig)`.
- `JoinResponse join(JoinRequest r)`, where `JoinRequest(String authorKey, Object wikiSession, String displayName, String initialText, Map<String,Object> initialMeta)`.
- `PushResponse push(String participantId, String authorKey, int version, List<Update> updates)`:
  - the participant must belong to `authorKey` (A6), else a 403-class result;
  - every `clientId` must be the participant's (E14).
- `PullResponse pullNow(String participantId, int since)` returns `null` when the call would park; `void park(String participantId, int since, PullWaiter w)`; `List<Runnable> expireWaiters()` for the clock-driven timeout.
- `void leave(String participantId)`.

Push follows §7.2 exactly: store first, then memory. On a store failure it returns 503 and changes nothing (E35).

Catch-up for a `since` below the ring is served from `store.updatesSince` (A10). The session answers 410 only when the store has truncated below `since`.

A snapshot is written every `snapshot.everyUpdates`. `load` rebuilds `doc`, `head`, `local` and the ring from the latest snapshot plus the log; `local` is recomputed as `TextDiff.changeSet(baseBody, doc)` when the snapshot has none.

A presence selection rides on push and pull as `{anchor, head, version, unconfirmed}` (A12), mapped server-side through the updates since `version`. Keep the per-participant last selection only; rendering is Phase 2.

- [ ] **Step 1:** Write `CollabSessionTest`. Port every stub test from Phase 0 Task 10 (E8, E10, E14, E15, surrogate, oversize, parking, timeout). Add:
  - `twoTabsOfOneUserAreTwoParticipants`;
  - `catchUpBelowTheRingIsServedFromTheStore`;
  - `a410OnlyWhenTheStoreTruncatedBelowSince`;
  - `loadRebuildsTheCommittedState` (E37);
  - `snapshotCadence`.

  Add `CollabSessionSimServer` and parameterise the four harness tests.
- [ ] **Step 2:** Run red.
- [ ] **Step 3:** Implement. Concurrency: one `ReentrantLock` per session. Waiters are completed outside the lock. No `synchronized`.
- [ ] **Step 4:** Run green. The fuzz must pass for both servers across every profile at the default seed count; report the runtime.
- [ ] **Step 5:** Commit `feat(collab): CollabSession text core, held to the Phase 0 harness`.

### Task 4: Publish, external merge, crash recovery

**Files:**
- Modify: `CollabSession.java`
- Create: `model/PublishResponse.java`
- Test: `CollabSessionPublishTest.java`; the simulator gains `EXTERNAL_SAVE` and `PUBLISH` weights for the session server (Phase 0 ruling R1)

**Interfaces:**
- `PublishResponse publish(String participantId, String authorKey, int version, String changeNote)`. It drives `CollabPageWriter.publish` outside the lock, as §7.3 describes.
- `void reconcile(PageState latest)` is the external-merge procedure of §7.4. It is called by publish (A1 backstop), by `load` (lazy reconciliation, in version order via `writer.version(page, v)`), and in Phase 2 by the event listener.
- Change detection compares content hashes, not versions (A1). Bodies are LF-normalised before diffing (A9).
- While `publishing` is set, `reconcile` calls are queued. After the post-publish step they run in order, each against its predecessor version (A3).
- Pending-publish marker and crash recovery (E39) per §7.3 plus A2. Recovery claims version B+1 only if its LF body hash equals `S_V`'s and its author is the marked publisher.
- Post-publish re-check of every participant's `edit` (A5) through `CollabPermissions`; a participant who lost it is evicted.
- Edge cases E16–E26 and E39 (§13.0) are named tests.

- [ ] **Step 1:** Write the named E-tests plus:
  - `externalSaveDuringPublishIsMergedOnceAfterIt`;
  - `e39_recoveryFinishesThePublish`;
  - `e39_aForeignVersionIsNotClaimed`;
  - `filterRewritesConvergeForEveryone` (E17, using `rewriteOnSave`);
  - `aWatcherOverwriteWithoutANewVersionIsReconciledAtPublish` (`overwriteWithoutVersion`).

  Turn on the simulator's external-save and publish actions.
- [ ] **Step 2:** Run red.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run green, including the fuzz with external saves and publishes.
- [ ] **Step 5:** Commit `feat(collab): publish with normalisation mapping, external merge, crash recovery`.

### Task 5: `CollabSessionRegistry`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/collab/CollabSessionRegistry.java`
- Test: `CollabSessionRegistryTest.java`; extend `ConcurrencyHarnessTest` with registry-level joins, leaves and evictions (E33)

**Interfaces:**
- `CollabSessionRegistry(CollabStore, CollabPageWriter, CollabPermissions, CollabIdentity, Clock, CollabConfig)`.
- `JoinOutcome join(String pageName, JoinRequest r)`: create or load. `JoinOutcome` is a sealed type: `Joined`, `Refused(int status, String reason)`, `Disabled`.
  - Derived pages (frontmatter has `derived_from`) → 409 `derived-page`.
  - No `edit` → 403.
  - More than `maxParticipants` → 429 `session-full`.
- `Optional<CollabSession> live(UUID id)`.
- `void tick()`: presence timeouts, waiter timeouts, permission re-checks every `permission.recheckSeconds` (A5), and idle eviction after `memory.idleEvictMinutes`. Eviction deletes a draft with no changes (§7.7, A10 Minor).
- `void rekey(String from, String to)` and `void freeze(String pageName)` are exercised by Phase 2's listener.
- `void setEnabled(boolean)`.

- [ ] **Step 1:** Write the tests: E13, E30, E32, E33, idle eviction keeps a draft with changes and deletes one without, `derivedPagesAreRefused`, `sessionFullIs429`. Run red.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green.
- [ ] **Step 4:** Commit `feat(collab): session registry, lifecycle and eviction`.

### Task 6: Adapters and engine wiring

**Files:**
- Create in `com.wikantik.collab.adapter`:
  - `PageSaveHelperPageWriter.java`, a `CollabPageWriter` over `PageSaveHelper.saveTracked` (Phase 0 Task 1), `PageManager`, `FrontmatterParser` and `computeContentHash`;
  - `WikiCollabPermissions.java`, which checks `edit`/`createPages` through the same `PermissionFilter`/`AuthorizationManager` path as `RestServletBase.checkPagePermission` with the participant's wiki `Session`, uncached for publish (A5);
  - `PublicIdentityCollabIdentity.java`;
  - `CollabWiring.java`, which builds the registry with `JdbcCollabStore` on the wiki datasource and a `ScheduledExecutorService` calling `registry.tick()` every second (daemon thread; shut down with the engine).
- Modify: the engine wiring that owns plain service fields (`WikiEngine`, near `:1552`).
- Test: `PageSaveHelperPageWriterTest.java` (TestEngine): publish returns the written version and LF body; filters' rewrites come back in `savedBody`; a conflict maps to `VersionConflictException`. Plus `CollabWiringTest.java`: the registry exists, is disabled by default, and `DecompositionArchTest` still passes.

- [ ] **Step 1:** Write the tests. Run red.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green: `mvn -o -q test -pl wikantik-main -Dtest='PageSaveHelperPageWriterTest,CollabWiringTest'`, then install and `mvn -o -q test -pl wikantik-war -Dtest='DecompositionArchTest,CollabSeamArchTest,JdbcAccessArchTest'`.
- [ ] **Step 4:** Commit `feat(collab): save, permission and identity adapters; engine wiring`.

### Task 7: `CollabResource` (REST, async long-poll)

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/CollabResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (servlet + `/api/collab/*`, `async-supported`); `CLAUDE.md` (the `/api` servlet count, A10 Minor)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/CollabResourceTest.java`, using Mockito request/response and Phase 0's `FakeAsyncContext` (copy it into wikantik-rest's tests only if wikantik-main's test-jar does not expose it; check first)

**Interfaces:** the §6.2 endpoints as amended:
- `POST /api/collab/{page}/join` with `{initialText?, initialMeta?}`;
- `POST /updates` with `{sessionId, participantId, version, updates, selection?}`;
- `GET /updates?sessionId&participantId&since`;
- `POST /publish`, `POST /discard`, `POST /leave`.

Rules:
- Authentication is required (401 otherwise).
- `participantId` must belong to the caller's author key (A6).
- Response bodies are §6.2's, plus `reason` on every 409.
- The long-poll uses `startAsync`, the configured timeout, and a `PullWaiter` that writes the JSON and calls `complete()` exactly once.
- Errors go through `RestServletBase.sendError` (memory: `rest sendError D10 leak`).
- Every response sets `Cache-Control: no-store`.

- [ ] **Step 1:** Write tests for every status in §6.2 plus:
  - `disablingCollabRefusesJoinsButKeepsDrafts`;
  - `aWaiterCompletesExactlyOnceOnWakeAndOnTimeout` (E32);
  - `anotherUsersParticipantIdIs403`;
  - `responsesAreNoStore`.

  Run red.
- [ ] **Step 2:** Implement and register the servlet.
- [ ] **Step 3:** Run green. Also run `ApiFilterChainAsyncTest`, which now resolves the real servlet mapping.
- [ ] **Step 4:** Commit `feat(collab): /api/collab REST surface with async long-poll`.

### Task 8: Frontend transport, peer and session hook

**Files:**
- Create in `wikantik-frontend/src/collab/`: `collabTransport.js`, `collabPeer.js`, `useCollabSession.js`, and `fakeCollabServer.js` (test helper: an in-memory authority implementing the same JSON contract, built on `@codemirror/state`)
- Tests: `collabPeer.test.js`, `collabTransport.test.js`, `useCollabSession.test.jsx`

Behaviour:
- **`collabPeer`** is a `ViewPlugin`: one in-flight push of `sendableUpdates` (100 ms debounce); a long-poll pull loop into `receiveUpdates`; 409 `stale-base` → pull, then retry; exponential backoff on network errors (cap 10 s); 410 → resync callback (A10: hand unsynced text to a recovery callback, never re-apply).
- **`collabTransport`** uses the existing `api/client.js` (credentials, CSRF/Origin as today).
- **`useCollabSession`** joins on mount; leaves on unmount and on `pagehide` via `navigator.sendBeacon`; exposes `{status, participants, events, publish, discard, docAtJoin, startVersion}`.

- [ ] **Step 1:** Write the tests with real `EditorState`s and `fakeCollabServer`:
  - `threeEditorsTypingConcurrentlyConverge` (a seeded random edit loop, 500 steps, random delivery delays);
  - `aLostPushResponseDoesNotDuplicate`;
  - `stalePushRebasesAndRetries`;
  - `networkErrorBacksOff`;
  - `resyncHandsUnsyncedTextToRecovery`;
  - `undoOnlyUndoesMyEdits`.

  Run red: `npx vitest run src/collab`.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green. Run `npx eslint src/collab`.
- [ ] **Step 4:** Commit `feat(collab): frontend transport, collab peer and session hook`.

### Task 9: Editor integration (collab mode)

**Files:**
- Modify: `wikantik-frontend/src/components/CodeEditor.jsx` (uncontrolled mode when `collabExtensions` is given, A4); `PageEditor.jsx` (collab mode when capabilities report `collab.enabled` and `join` succeeds; Publish replaces Save; `useDraft` disabled; navigation guard only while `sendableUpdates` is non-empty; 422 rendering reused; every body replacement — attachment rename, conversion, restore — goes through the view as a minimal transaction)
- Modify: the capabilities payload, adding `collab: { enabled }` (find `/api/capabilities` in wikantik-rest)
- Test: `PageEditor.collab.test.jsx`, `CodeEditor.collab.test.jsx`

- [ ] **Step 1:** Write the tests:
  - `noFullDocumentReplacementReachesCollab` (spy on transactions; parent re-renders with a new `value` must not dispatch);
  - `publishWaitsForSync`;
  - `aRemoteEditDoesNotMoveMyCaret`;
  - `disabledCollabKeepsTheSingleAuthorEditor` (existing PageEditor tests stay green);
  - `422IsRenderedAsToday`.

  Run red.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green: `npx vitest run src/components src/collab`, then `npx eslint .`.
- [ ] **Step 4:** Commit `feat(collab): collaborative mode in the page editor (dark)`.

### Task 10: End-to-end tests

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/CollabRestIT.java`. Enable collab for that IT module only, through its test properties overlay (check how other ITs set `wikantik.*` keys).
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/test/java/com/wikantik/its/CollabTwoBrowserIT.java`, two headless browsers on one page (follow `reference_admin_it_login_race`: `awaitAdminReady` after login).

Tests:
- `CollabRestIT`, through real Tomcat async:
  - two HTTP clients join, push concurrently, long-poll, converge, publish;
  - a third client's `PUT /api/pages` (an agent write) during the session is merged at publish;
  - the kill switch returns 503;
  - the long-poll completes on push and on timeout.
- `CollabTwoBrowserIT`: A types, B sees it within 2 s; both type; both converge; A publishes; the page view shows the merged text.

- [ ] **Step 1:** Write the ITs. Run red: `bin/run-tests.sh --module rest` and the selenide module through `bin/run-tests.sh`. Use `bin/agent-build.sh` for long runs.
- [ ] **Step 2:** Fix what fails, test-first at the unit level where the defect lives.
- [ ] **Step 3:** Run green.
- [ ] **Step 4:** Commit `test(collab): REST and two-browser end-to-end tests`.

### Task 11: Close-out

- [ ] Run the full gate (`bin/run-tests.sh --parallel 4`) to green, plus a soak: `-Dcollab.fuzz.seeds=50000` and `-Dcollab.stress.runs=5000` on the collab tests.
- [ ] Update §15 of `docs/MultiEdit.MD` and `docs/developer/ProjectReference.md`. Add a CHANGELOG entry under `### Added`, marked dark.
- [ ] Commit `docs(MultiEdit): Phase 1 complete`.
