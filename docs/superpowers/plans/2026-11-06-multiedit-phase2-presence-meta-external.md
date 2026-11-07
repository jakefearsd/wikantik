# MultiEdit Phase 2 — Presence, Metadata Ops and External Changes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Editors in a shared draft see each other's cursors and names, co-edit the frontmatter, and watch outside writes (agents, renames, deletes, file edits) arrive live in the draft. The feature stays dark behind `wikantik.collab.enabled=false`.

**Architecture:**
- **Session (engine-free, `com.wikantik.collab`).** `CollabSession` gains client meta edits that the server resolves under its lock: last-writer-wins for scalar fields, add/remove for `tags`, `related` and `aliases`. It also gains lifecycle operations: rekey, freeze, recreate, and eviction with a reason. `CollabSessionRegistry` gains page-name lookup, rekey/freeze for live and stored sessions, author and session eviction, and settle-all.
- **Adapters (`com.wikantik.collab.adapter`).** `CollabPageEventListener` turns `POST_SAVE_END`, `POST_SAVE_BEGIN`, `WikiPageRenameEvent`, `PAGE_DELETED` and the security events into registry calls. External changes are detected by content hash only (A1). Engine glue adds a rename guard and a `UserDeletionService` hook.
- **Engine write paths.** Unevented `putPageText` writers take the per-page save lock (Phase 0 deferral). The collab publish adapter refuses to recreate a page that was renamed or deleted away.
- **Frontend (`wikantik-frontend/src/collab/`).** `remoteCursors.js`, `externalHighlights.js` and `PresenceBar.jsx` render presence. `collabMeta.js` queues debounced field ops and set ops. `PageEditor` unlocks the frontmatter in collab mode and renders the lifecycle events.

**Tech Stack:** Java 25, Gson, `com.wikantik.jdbc.Jdbc`, Servlet async; React plus CodeMirror 6, `@codemirror/collab` 6.1.1, vitest; Cargo ITs and the Selenide suite.

**Spec:** `docs/MultiEdit.MD`. **§0 Review amendments are authoritative.** Read §0 (A1, A3, A5, A12, A13, A14 and the Minor items on migration, deleted users and protocol), §6.2, §7.4 (delete/rename), §7.5, §8.1–8.3, §9, §13.0 (E24, E27–E29, E34, invariant 7) and §13.2–13.4. The Phase 1 plan (`docs/superpowers/plans/2026-11-06-multiedit-phase1-core-text.md`) defines every interface consumed below. Its "Phase 0 final-review amendments" and "Publish details" are binding here too. So are the Phase 1 ledger rulings P1–P10 (`.superpowers/sdd/2026-11-06-multiedit-phase1-core-text/progress.md`).

## Preconditions

- Phase 1 Tasks 4–7 are landing while this plan is written. Each task below names the Phase 1 outputs it consumes, per the Phase 1 plan text:
  - Task 4: `CollabSession.reconcile(PageState)`, `settle()`, `publish(…)`, `startPublish`/`finishPublish`/`failPublish`.
  - Task 5: `CollabSessionRegistry(CollabStore, CollabPageWriter, CollabPermissions, CollabIdentity, Clock, CollabConfig)`, `join(String, JoinRequest)`, `live(UUID)`, `tick()`, `rekey(String, String)`, `freeze(String)`, `setEnabled(boolean)`.
  - Task 6: `PageSaveHelperPageWriter`, `WikiCollabPermissions`, `PublicIdentityCollabIdentity`, `CollabWiring`, and the registry accessor on `WikiEngine`.
  - Task 7: `CollabResource`.
- Before editing, a task greps for these names and reports any rename to the controller. Task 4 split session internals into `Draft`, `ExternalMerge`, `Answers` and `MetaMerge`; grep before editing them.
- A task starts only once every Phase 1 task it consumes is committed on main.

## Global Constraints

- Every Phase 0 and Phase 1 global constraint applies, and so do rulings P1–P10:
  - TDD red-first; never swallow exceptions; LF-only text; Gson; `Jdbc` only; stage files by name; the commit trailer.
  - The seam rule (`CollabSeamArchTest`).
  - Display names, never logins, on every wire surface (§9).
  - `Cache-Control: no-store` on every collab response.
- **No migration.** A set op is resolved by the server into an ordinary last-writer-wins assignment. That assignment is stored in `collab_meta_op` and broadcast as `MetaOp`. `collab_session.status` already allows `frozen`. A task that finds it needs a schema change stops and reports; the change would be `V067`.
- **No new config keys.** The meta debounce (400 ms) and the presence interval (150 ms) are client constants. Permission re-checks reuse `wikantik.collab.permission.recheckSeconds`. A task that needs a key declares it first (Phase 1 Global Constraints).
- **Wire changes are additive to protocol v1.** They land in `wikantik-frontend/src/collab/__fixtures__/collab-protocol.json` first (Task 1). `CollabResourceTest` and `fakeCollabServer.test.js` both assert that file (ruling P2).
- **External changes are detected by content hash, never by version** (A1). Events received while a publish is in its save are queued (A3). Both mechanisms are Phase 1's (`settle`/`reconcile`); Phase 2 only delivers events to them.
- **Listener and hook calls never throw into the save, rename, delete or user-delete path.** Catch `RuntimeException` and `LOG.warn` with the page, session or login context.
- `CollabPageEventListener` touches engine event types, so it lives in `com.wikantik.collab.adapter`, overriding §5.1's placement. The registry stays engine-free and is never on the `getManager` allow-list (R-2).
- **Frontend.** `CodeEditor` stays uncontrolled in collab mode (A4). Every decoration maps through every transaction. No full-document replacement ever reaches `collab()`.
- **Parallel work** (rulings P8, P11):
  - Java tasks marked "worktree" run in their own git worktree, with no `mvn install` there; the controller cherry-picks them onto main.
  - Two Maven builds never share one tree.
  - Frontend tasks run no Maven.
- **Commands:**
  - Java: `unset ${!WIKANTIK_@}; mvn -o -q test -pl <module> -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
  - Before a wikantik-rest test: `mvn -o -q install -pl wikantik-main -DskipTests` (main tree only).
  - Frontend: `cd wikantik-frontend && npx vitest run <path> && npx eslint <path>`.
  - Anything over about 5 minutes goes through `bin/agent-build.sh`.

## Review Focus

1. **External save during publish, through the real events.** One outside save that lands while a publish is inside its save fires both `POST_SAVE_BEGIN` and `POST_SAVE_END`. Both events are queued and merged exactly once afterwards: no duplicated text, no lost text. Task 5 test `aSaveEventDuringPublishIsQueuedAndMergedOnce`.
2. **Concurrent set additions survive.** Two editors add different tags from the same base without seeing each other. Both tags end up in the result, and the second push is not refused as stale. Task 2 test `concurrentTagAddsBothSurvive`.
3. **Rename during publish (E27).** A rename that lands while a publish is inside its save never recreates the old page name, and the retried publish lands on the new name. Task 6 test `e27_aRenameDuringPublishLandsOnTheNewName`.
4. **User deletion is never blocked by collaboration.** A failure in the collab hook is logged and the account delete completes. The deleted user's participants in every live session, both tabs included, are evicted. Task 6 test `aCollabFailureNeverAbortsAUserDelete`.
5. **My pending tag add does not flicker or double.** Between the push acknowledgement and the pull that delivers it, the displayed tags still contain my add. A lost acknowledgement followed by a retry adds the tag once. Task 10 test `myTagAddSurvivesTheGapBetweenAckAndPull`.

## Parallel waves

| Wave | Tasks | Notes |
|---|---|---|
| 0 | 1 | Frontend-only fixture; gates every other task |
| 1 | 2 (main tree) ∥ 4 (worktree) ∥ 9 ∥ 10 (frontend) | 2 needs Phase 1 Task 4; 4 needs Phase 1 Task 6 |
| 2 | 3 (after 2) ∥ 11 (after 9 and 10) | 11 is the only `PageEditor.jsx` owner |
| 3 | 5 (main tree, after 3 and Phase 1 Task 5) ∥ 7 (worktree, after 3 and Phase 1 Task 7) ∥ 8 (worktree, after 3 and 10) | 8 changes test scope only, plus `ScriptedPageWriter` |
| 4 | 6 (after 4 and 5, and Phase 1 Task 6) | |
| 5 | 12 | IT, E2E, gate, docs |

---

### Task 1: Protocol fixture and fake server for Phase 2 (frontend-only)

**Files:**
- Modify: `wikantik-frontend/src/collab/__fixtures__/collab-protocol.json`, `wikantik-frontend/src/collab/fakeCollabServer.js`, `wikantik-frontend/src/collab/fakeCollabServer.test.js`

**Interfaces:**
- Consumes: the Phase 1 fixture (protocol v1, rulings P2–P7).
- Produces these additive v1 shapes:
  - **Meta edits on `POST /updates`.** `metaOps` items are `{field, value}` (SET; a present `value: null` removes the field), `{field, add: "x"}` (ADD) or `{field, remove: "x"}` (REMOVE). Exactly one of `value`, `add`, `remove` is present, else `400 {reason:"rejected"}`. ADD and REMOVE take a string and are allowed only on `tags`, `related` and `aliases`.
  - A push with empty `updates` and non-empty `metaOps` skips the version check, as a presence-only push does (P7).
  - A refused push applies nothing: no text, meta or selection.
  - **Responses.** A push `200` carries `metaSeq`. A pulled meta op carries `by` (display name) when a participant authored it; server ops omit it. Meta ops are the server-resolved assignment `{field, value}`, never add/remove.
  - **Events.** Each event is `{seq, type, …}`:
    - `participant-joined {participant:{participantId, displayName, color}}`;
    - `participant-left {participantId, reason: left|timeout|evicted}`;
    - `external-change {source, pageVersion, version, ranges:[[from,to]…], conflictFields:[…], recreated?}`, where `version` is the collab head right after the server update and `ranges` are in that document's coordinates;
    - `page-renamed {to}`;
    - `page-deleted {}`;
    - `evicted {participantId, reason: lost-edit|signed-out|account-deleted}`.
  - **Eviction.** Every post-join call by an evicted participant answers `403 {reason:"evicted", detail: lost-edit|signed-out|account-deleted}`.
  - **Frozen publish.** Publishing a frozen draft without `createPages` answers `403 {reason:"no-create"}`.
  - **Renamed sessions.** Post-join calls resolve the session by `sessionId`. The `{pageName}` path segment may be the session's current name or a name it held earlier; any other name answers `404`.
  - **Idempotence.** Every meta edit is idempotent: re-applying a SET, an ADD of a present item, or a REMOVE of an absent item changes nothing. A lost acknowledgement and retry is therefore safe.
- Produces fake-server test controls: `fake.renamePage(to)`, `fake.freeze()`, `fake.evict(participantId, reason)`, `fake.externalChange({source, text, meta})`, `fake.setCanCreate(bool)`.

- [ ] **Step 1:** Add the fixture cases and notes:
  - `updates.post.200.meta-set-ops`, `updates.post.200.meta-only`, `updates.post.400.bad-meta-op`;
  - `updates.get.200.meta-by`, `updates.get.200.lifecycle-events`;
  - `updates.post.403.evicted`, `updates.get.403.evicted`;
  - `publish.403.no-create`;
  - notes `metaEdits`, `metaIdempotent`, `renamedSession`, `evicted`.

  Extend `fakeCollabServer.test.js` to replay every case against the fake. Run red: `npx vitest run src/collab/fakeCollabServer.test.js`.
- [ ] **Step 2:** Implement set-op resolution in the fake, matching Task 2's rules exactly. Add `by`, the events, the 403 eviction, the rename tolerance and the controls.
- [ ] **Step 3:** Run green: `npx vitest run src/collab && npx eslint src/collab`.
- [ ] **Step 4:** Commit `test(collab): protocol fixture for meta set ops, presence, lifecycle events and eviction`.

### Task 2: Session meta edits with set semantics (A14, server)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/collab/model/MetaEdit.java`, `wikantik-main/src/main/java/com/wikantik/collab/MetaEdits.java`
- Modify:
  - `CollabSession.java` (push), `SessionFeed.java` (`by`), `model/MetaOp.java`, `model/PushResponse.java`;
  - `CollabStore.java` (`appendBatch`), `adapter/JdbcCollabStore.java` (and `CollabWrites.java` if the SQL lives there);
  - test `InMemoryCollabStore.java`, `AbstractCollabStoreContractTest.java`.
- Test: `CollabSessionMetaTest.java`; `JdbcCollabStoreRollbackTest` (new case)

**Interfaces:**
- Consumes:
  - `MetaJson.normalise(Map)`;
  - `MetaMerge.published(meta, original)` and `MetaMerge.fold(…)`;
  - `StoredMetaOp(long seq, String authorKey, String field, Object value)`;
  - `CollabStore.appendMetaOps`;
  - `Gate.push`.
- Produces:
  - `public record MetaEdit( String field, Kind kind, Object value ) { public enum Kind { SET, ADD, REMOVE } }`, with factories `set(String, Object)`, `add(String, String)` and `remove(String, String)`. ADD and REMOVE require a non-blank `String` value.
  - `final class MetaEdits`:
    - `static final Set< String > SET_FIELDS = Set.of( "tags", "related", "aliases" )`;
    - `static String invalid( MetaEdit e )` returns null when valid, else a message. It rejects a blank field, a field longer than 128 characters, `__yaml__`, and add/remove outside `SET_FIELDS`.
    - `static Object resolve( Object current, MetaEdit e )` returns the normalised value after the edit:
      - SET → the normalised value;
      - ADD → append when no equal (normalised) item is present;
      - REMOVE → drop every equal item;
      - a scalar string `current` counts as a one-item list (never split on commas);
      - absent counts as an empty list.
  - `CollabStore`: `void appendBatch( UUID id, List< StoredUpdate > updates, List< StoredMetaOp > ops )` is all-or-nothing, with the contiguity rules of `appendUpdates` and `appendMetaOps`. The JDBC version runs in one `Jdbc.inTransaction`.
  - `CollabSession`: `PushResponse push( String participantId, String authorKey, int version, List< Update > updates, List< MetaEdit > metaEdits, Selection selection )`. The existing overloads delegate with `List.of()`.
  - `public record PushResponse( int status, int version, String reason, long metaSeq )`. A 3-arg constructor keeps refusals unchanged (`metaSeq` 0), and `accepted( int version, long metaSeq )` replaces `accepted( int )`; update its callers.
  - `public record MetaOp( String field, Object value, String by )`, plus a 2-arg constructor with `by` null.
  - `SessionFeed.metaOpsAfter( long since, long metaSeq, Function< String, String > byName )`. `byName` maps an author key to the participant's display name, or to `CollabIdentity.displayName`; it never returns the key, and a null name omits `by`.

Rules:
- Validate the whole push (text per §7.2 and every meta edit) before storing anything. One invalid edit → `400 rejected`, nothing applied.
- Meta edits are resolved in request order against the live `meta`, under the session lock.
- An edit whose resolved value equals the current one (after `MetaJson.normalise` on both sides) stores nothing; `metaSeq` is unchanged and the push is still accepted. This settles the Phase 1 Task 1 deferral to Phase 2: one normaliser, both sides, SnakeYAML maps included.
- The text and meta of one push are stored with one `appendBatch`. On failure the answer is `503 store` and memory is unchanged (E35).
- A refused push (stale-base, frozen, rate-limited, oversize, …) applies no meta edit. A push with only meta edits skips the version check.
- Accepted meta ops wake parked pulls that are behind on `metaSince`, and they mark the draft unpublished (`meta != baseMeta`).

- [ ] **Step 1:** Write the tests:
  - `aSetEditIsStoredAppliedAndBroadcast`: the other participant's pull returns `MetaOp("summary", "New", "Ada")`.
  - `concurrentTagAddsBothSurvive` (**Review Focus #2**): both clients start from `[a]`; A pushes ADD x, then B pushes ADD y without pulling. Assert `meta.tags == [a, x, y]` and that both pushes return 200.
  - `addThenRemoveOfTheSameTagIsOrderedByTheServer` (E34, set against remove).
  - `e34_concurrentSetsOfOneFieldServerOrderWins`.
  - `aNoOpEditStoresNothing`.
  - `aScalarTagsValueIsTreatedAsAOneItemList`.
  - `setOpsOnANonSetFieldAreRejected`: 400, and the text in the same push is not applied.
  - `aStalePushAppliesNoMetaEdits`.
  - `aMetaOnlyPushSkipsTheVersionCheck`.
  - `aMetaEditWakesParkedPulls`.
  - `textAndMetaOfOnePushAreAtomic`, using `InMemoryCollabStore.failOn(APPEND_BATCH, 1)`: 503, and neither text nor meta is applied.
  - `aYamlDateAndItsJsonStringAreOneValue`: base `date` is a `java.util.Date`; a client SET of `"2026-01-15"` stores nothing.
  - `aClientEditOnAnotherFieldKeepsTheBaseDateTyped`: after a tags edit, the publish request captured by `ScriptedPageWriter` carries the original `Date` (the Phase 1 Task 3 → Task 4 note).
  - `aMetaEditDuringThePublishSaveSurvivesIt`: `pauseNextPublish`, a meta edit, release. After the publish the edit is still in `meta`, and `hasUnpublished` is true.
  - Contract (both stores): `appendBatchIsAtomic`, and `metaOpValuesRoundTripIdenticallyInBothStores` (Integer, Long, Double, Date, List, nested Map; the Phase 1 Task 1 parity deferral).
  - Rollback: `appendBatchRollsBackOnTheMetaInsert` (`FaultInjectingDataSource`).
- [ ] **Step 2:** Run red: `-Dtest='CollabSessionMetaTest,InMemoryCollabStoreContractTest,JdbcCollabStore*'`. JDBC needs Docker; a skip must show its reason.
- [ ] **Step 3:** Implement. Add `APPEND_BATCH` to `InMemoryCollabStore.Op`.
- [ ] **Step 4:** Run green. Also run `-Dtest='CollabSession*,CollabConvergenceFuzzTest,CollabRegressionTracesTest'`: the existing fuzz must stay green with no meta actions yet.
- [ ] **Step 5:** Commit `feat(collab): metadata ops with set semantics for tags, related and aliases (A14)`.

### Task 3: Session lifecycle — participant events, eviction reasons, rename, freeze, recreate

**Files:**
- Modify: `CollabSession.java`, `Roster.java`, `Presence.java`, `ExternalMerge.java`, `Gate.java`, `model/SessionRecord.java`; test `ScriptedPageWriter.java`
- Test: `CollabSessionLifecycleTest.java`; extend `CollabSessionTest` (presence)

**Interfaces:**
- Consumes:
  - Task 2's push;
  - Phase 1 `evict(String)`, `settle()`, `reconcile(PageState)`, `startPublish`, the private `recheckEdit()`;
  - `CollabPermissions.canCreate(Object, String)`;
  - `CollabStore.updateSession`, which throws `IllegalStateException` when renaming onto a page that already has a session.
- Produces:
  - `SessionRecord.withStatus( String status )` and `SessionRecord.withPageName( String pageName )`.
  - `CollabSession`:
    - `public void rekey( String newPageName )` stores the new name, emits `page-renamed {to}`, wakes parked pulls, and keeps the old name in an in-memory `formerNames` set. It throws `IllegalStateException`, with the session unchanged, when the store refuses.
    - `public void freeze()` sets the status to `frozen` in store and memory, emits `page-deleted`, and wakes pulls.
    - `public boolean frozen()`.
    - `public boolean answersTo( String pageName )`: the current name or any of `formerNames`.
    - `public void evict( String participantId, String reason )`: `evict(String)` delegates with `lost-edit`.
    - `public void recheckEdits()` replaces the private `recheckEdit()` and evicts with `lost-edit`.
    - `public static final String EVICTED = "evicted"`.
  - The reasons `lost-edit`, `signed-out` and `account-deleted` are constants on `CollabSession`.

Rules:
- **Participant events.** `participant-joined` on join; `participant-left` on leave (`left`), on the presence timeout (`timeout`) and on eviction (`evicted`, together with an `evicted {participantId, reason}` event).
- **Eviction tombstone.** An evicted participant id is kept, bounded and pruned after the presence-timeout retention. Its later push, pull, publish or discard answers `403 evicted` with `detail` (fixture). Its parked pull completes at once.
- **Selections follow the head.** On every accepted text or server update, each kept selection with `unconfirmed == 0` that sits at the old head is mapped through the change (`mapPos(…, -1)`) to the new head. A receiver can then place it exactly (Task 9). Selections with `unconfirmed > 0` are kept as sent. This finishes the Phase 2 note in `Presence.java`.
- **Presence wakes.** The Phase 1 fix round already wakes parked pulls on presence changes (the deferred task-3 report note). Pin it with a test and change nothing.
- **Frozen sessions.**
  - Pushes and discards answer `409 frozen`; pulls are served.
  - Publishing requires an uncached `canCreate` for the publisher (A5), else `403 no-create`. It publishes with `expectedVersion 0` ("must not exist", A11) and, on success, returns the status to `active`. This settles the Phase 0 final-ruling deferral: deleted-page freezing is Phase 2.
  - `settle()` or `reconcile` on a frozen session whose page exists again unfreezes it. It merges the recreated page as one external change against `writer.latest` (no version walk: the version numbers restarted), with `recreated: true` on the event.
- `external-change` events gain `version`, the collab head right after the server update.
- A frozen session reloads as frozen (`load`).
- **`ScriptedPageWriter`** refuses a publish with `expectedVersion > 0` to a missing page (`VersionConflictException`), mirroring Task 4's adapter rule.

- [ ] **Step 1:** Write the tests:
  - `joinLeaveTimeoutAndEvictionEmitParticipantEvents`;
  - `anEvictedParticipantGets403EvictedWithItsReason` (push, pull, publish);
  - `e29_lostEditEvictsWithReasonLostEdit`;
  - `keptSelectionsFollowTheHead` (a push and a server update);
  - `aPresenceChangeWakesParkedPullsOnce`;
  - `rekeyMovesTheSessionAndEmitsPageRenamed` (store row, event, woken pull);
  - `rekeyOntoAPageWithADraftIsRefused`;
  - `e28_freezeMakesPushes409FrozenAndKeepsPulls`;
  - `e28_publishOfAFrozenDraftRecreatesThePageOnlyWithCreatePages`;
  - `aRecreatedPageUnfreezesTheDraftAsOneExternalChange`;
  - `freezeIsDurableAcrossReload`;
  - `externalChangeEventCarriesTheCollabVersion`;
  - `e27_renameDuringPublishLandsOnTheNewName`: `pauseNextPublish`, then `writer.rename`, then `session.rekey`, then release. The answer is 409 `publish-merged-retry`; the retry publishes to the new name; the old name does not exist.

  Run red.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green: `-Dtest='CollabSession*,CollabConvergenceFuzzTest,CollabRegressionTracesTest,ConcurrencyHarnessTest,RecoveryTest'`.
- [ ] **Step 4:** Commit `feat(collab): participant events, eviction reasons, rename, freeze and recreate in the session`.

### Task 4: Engine write paths — page lock for unevented writers, safe collab publish (worktree)

**Files:**
- Modify:
  - `wikantik-main/src/main/java/com/wikantik/page/subsystem/lifecycle/PageLifecycle.java` and `DefaultPageLifecycle.java`;
  - `wikantik-main/src/main/java/com/wikantik/pages/DefaultPageManager.java:160`;
  - `wikantik-main/src/main/java/com/wikantik/content/DefaultPageRenamer.java:129, :190-215`;
  - `wikantik-main/src/main/java/com/wikantik/auth/acl/DefaultAclManager.java:306`;
  - `wikantik-main/src/main/java/com/wikantik/collab/adapter/PageSaveHelperPageWriter.java` (Phase 1 Task 6).
- Test: extend `GuardedSaveTest.java`; `DefaultPageRenamer`'s and `DefaultAclManager`'s existing test classes; `PageSaveHelperPageWriterTest.java`

**Interfaces:**
- Consumes: Phase 0's per-page `PageLock` in `DefaultPageLifecycle` (reentrant, reference-counted); Phase 1's `PageSaveHelperPageWriter` and its own `SaveGuard` for `expectedVersion == 0`.
- Produces:
  - `PageLifecycle`: `< T > T underPageLock( String pageName, PageLockedAction< T > action ) throws WikiException`, where `@FunctionalInterface interface PageLockedAction< T > { T run() throws WikiException; }`.
  - `PageLifecycle`: `void putPageText( Page page, String text ) throws ProviderException` takes the page lock. `DefaultPageManager.putPageText` delegates to it. Reentrancy keeps the guarded save's own write safe.

Decisions:
- This settles the Phase 0 deferral "putPageText writers bypass the lock (`DefaultPageRenamer:129,208`; `DefaultAclManager:306`)".
- **Renamer.** Each referrer's read → rewrite → put runs inside `underPageLock(referrer, …)`, so a guarded save of that referrer cannot be lost between the read and the write. The renamed page's own write takes the new name's lock.
- **ACL manager.** `DefaultAclManager` puts under the lock, then fires `POST_SAVE_BEGIN` for the page through the filter manager. That is exactly `PageDirectoryWatcher.invalidateCaches` (`PageDirectoryWatcher.java:333-347`), so render caches flush and collab reconciles.
- **Publish adapter.** `PageSaveHelperPageWriter` passes its own `SaveGuard` for `expectedVersion > 0` too. Inside the lock, a missing page throws `VersionConflictException` with the message `page-gone`. A collab publish therefore never recreates a page that was renamed or deleted away (E27/E28).
- The Phase 0 ruling ">0 on a missing page follows the real save" is superseded for the collab adapter only. `PageSaveHelper` and REST PUT keep their behaviour.

- [ ] **Step 1:** Write the tests:
  - `aPutPageTextWaitsForAGuardedSaveOfThatPage`: a guard blocks inside the lock; `putPageText` on another thread waits until release.
  - `putPageTextOfAnotherPageDoesNotWait`.
  - `aReentrantPutInsideTheGuardedSectionDoesNotDeadlock` (`assertTimeoutPreemptively` 10 s).
  - `aReferrerRewriteDoesNotLoseAConcurrentSave`: referrer R's guarded save is held, then target T is renamed. After release, R holds both the saved text and the rewritten link.
  - `setPermissionsFiresPostSaveBegin`.
  - `aPublishToAPageRenamedAwayIsAConflictNotARecreate`.
  - `aPublishToADeletedPageIsAConflict`.
  - `expectedVersionZeroStillCreates`.

  Run red: `-Dtest='GuardedSaveTest,PageSaveHelperPageWriterTest,*PageRenamer*,*AclManager*'`.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green, then `-Dtest='PageSaveHelper*,DefaultPageLifecycle*,*PageManager*,*Rename*'`. No change for callers that pass no guard.
- [ ] **Step 4:** Commit `fix(pages): unevented page writes take the per-page lock; a collab publish never recreates a page renamed or deleted away`. Add a CHANGELOG `[Unreleased]` → `### Fixed` bullet: rename referrer rewrites could overwrite a concurrent save of the referrer.

### Task 5: Registry additions and `CollabPageEventListener`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/collab/CollabSessionRegistry.java` (Phase 1 Task 5), `CollabPermissions.java`, `adapter/WikiCollabPermissions.java`
- Create: `wikantik-main/src/main/java/com/wikantik/collab/adapter/CollabPageEventListener.java`
- Test: `CollabSessionRegistryLifecycleTest.java`, `adapter/CollabPageEventListenerTest.java` (real registry over `InMemoryCollabStore`, `ScriptedPageWriter`, `MutableClock`; events constructed directly); extend `ConcurrencyHarnessTest` with listener-driven merges

**Interfaces:**
- Consumes:
  - Task 3's `rekey`, `freeze`, `frozen`, `evict(String, String)`, `recheckEdits`;
  - Phase 1's `settle()`, `tick()`, `live(UUID)`;
  - `WikiPageEvent.POST_SAVE_BEGIN/POST_SAVE_END/PAGE_DELETED`;
  - `WikiPageRenameEvent.getOldPageName()/getNewPageName()`;
  - `WikiSecurityEvent.LOGOUT/SESSION_EXPIRED/GROUP_ADD/GROUP_REMOVE`. `SessionMonitor.java:321` fires `SESSION_EXPIRED` with the wiki `Session` as target; `DefaultAuthenticationManager.java:420` fires `LOGOUT` with the principal and a null target.
- Produces on `CollabSessionRegistry`:
  - `Optional< CollabSession > liveForPage( String pageName )`.
  - `void rekey( String from, String to )` (named by the Phase 1 plan): a live session is rekeyed and re-indexed; a stored-only one is renamed in the store. If the store refuses because the target has a draft, the session is frozen under its old name with a WARN.
  - `void freeze( String pageName )`: live → `session.freeze()`; stored-only → `updateSession(rec.withStatus(FROZEN))`.
  - `boolean hasDraft( String pageName )`, from `store.sessionForPage`.
  - `int evictAuthor( String authorKey )`: every participant of that author in every live session is evicted with `account-deleted`; returns the count.
  - `int evictSignedOut( Object wikiSession )` evicts that session's participants with `signed-out`.
  - `int evictSignedOutAll()` evicts every participant whose session `CollabPermissions.signedIn` reports false.
  - `void requestEditRecheck()`: the next `tick()` calls `recheckEdits()` on every live session.
  - `void settleAll()`.
- Produces `CollabPermissions.signedIn`: `default boolean signedIn( Object participantSession ) { return true; }`, overridden in `WikiCollabPermissions` with `Session.isAuthenticated()`.
- Produces `public final class CollabPageEventListener implements WikiEventListener`:
  - constructor `( CollabSessionRegistry registry, CollabPageWriter writer )`;
  - `void register( PageManager pm, FilterManager fm, PageRenamer renamer, Object... securitySources )`, which follows `OntologyEventListener.register`;
  - `actionPerformed( WikiEvent )`.

Listener rules:
- `POST_SAVE_END` or `POST_SAVE_BEGIN` → `registry.liveForPage(name).ifPresent(CollabSession::settle)`. Content hash makes the second event of a save a no-op. A session that is not live reconciles lazily on load. A missing page is a no-op: the watcher fires `POST_SAVE_BEGIN` for a file delete (`PageDirectoryWatcher.java:300-325`), and a `git mv` must not freeze anything.
- `WikiPageRenameEvent` → `registry.rekey(old, new)`, then `registry.settleAll()`. Referrer rewrites write through `putPageText` without events, so every live session is reconciled by content hash (A1).
- `PAGE_DELETED` → freeze only when `writer.latest(name)` is empty. A rename fires no `PAGE_DELETED` (the `ChunkLifecycleListener` pattern); a page that still exists is kept, with a WARN.
- `SESSION_EXPIRED` → `evictSignedOut(target)`; `LOGOUT` → `evictSignedOutAll()`. Under A13 the client keeps its edits and prompts the user to log in.
- `GROUP_ADD` or `GROUP_REMOVE` → `requestEditRecheck()` (E29; at most one tick later).
- Any `RuntimeException` → `LOG.warn` naming the event and the page; never rethrown.

- [ ] **Step 1:** Write the tests:
  - `aSaveEventDuringPublishIsQueuedAndMergedOnce` (**Review Focus #1**): `pauseNextPublish`, then `writer.externalSave`, then both `POST_SAVE_BEGIN` and `POST_SAVE_END` through the listener, then release. The session doc contains the external text once and the published text once, and the store log holds one external server update.
  - `bothEventsOfOneSaveMergeOnce`.
  - `aWatcherOverwriteWithoutANewVersionIsMergedOnTheEvent` (`overwriteWithoutVersion` + `POST_SAVE_BEGIN`).
  - `aWatcherDeleteDoesNotFreezeTheDraft`.
  - `renameRekeysAndReconcilesReferrers`: a live session on referrer R; R's text is changed with no event, then T is renamed. R's session merges the change.
  - `aRenameIsNotTakenForADelete`.
  - `pageDeletedFreezesLiveAndStoredSessions`.
  - `pageDeletedForAnExistingPageKeepsTheDraft`.
  - `sessionExpiredEvictsThatSessionsParticipantsOnly`.
  - `logoutEvictsSignedOutParticipants`.
  - `groupChangeRechecksOnTheNextTick`.
  - `evictAuthorReachesBothTabs`.
  - `rekeyOntoADraftFreezesTheOrphan`.
  - `aThrowingSessionNeverEscapesTheListener`.
  - `listenerDrivenMergesKeepTheHarnessInvariants` (F7 with external saves delivered through the listener).

  Run red.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green: `-Dtest='CollabSessionRegistry*,CollabPageEventListenerTest,ConcurrencyHarnessTest'`, then install and `mvn -o -q test -pl wikantik-war -Dtest='CollabSeamArchTest,DecompositionArchTest'`.
- [ ] **Step 4:** Commit `feat(collab): CollabPageEventListener and registry rekey, freeze and eviction`.

### Task 6: Engine glue — listener registration, rename guard, deleted-user hook

**Files:**
- Modify:
  - `wikantik-main/src/main/java/com/wikantik/collab/adapter/CollabWiring.java` (Phase 1 Task 6);
  - `wikantik-main/src/main/java/com/wikantik/content/DefaultPageRenamer.java` (rename guard at entry);
  - `wikantik-main/src/main/java/com/wikantik/auth/UserDeletionService.java` (`delete`, after `endLiveSessions`).
- Create: `wikantik-main/src/main/java/com/wikantik/collab/adapter/CollabEngineAccess.java`
- Test: `adapter/CollabEngineWritersTest.java` (TestEngine; registry over `InMemoryCollabStore` and the real adapters, set on the engine through the Phase 1 setter); extend `UserDeletionServiceTest`; extend `DefaultPageRenamer`'s test

**Interfaces:**
- Consumes:
  - Task 5's listener, `hasDraft` and `evictAuthor`;
  - Task 4's lock and adapter rule;
  - the `WikiEngine` registry accessor from Phase 1 Task 6 (grep `CollabSessionRegistry` in `WikiEngine.java`; the `queryLogReader` pattern near `:1537`);
  - `AuditEventListener`'s registration in `WikiEngine` (near `:1295`), to find the `SessionMonitor` and `AuthenticationManager` security sources.
- Produces:
  - `public static CollabSessionRegistry CollabEngineAccess.registry( Engine engine )` returns null when collab is not wired. It is the one place that unwraps the engine.
  - `CollabWiring` builds and registers `CollabPageEventListener` on the PageManager, FilterManager, PageRenamer and the two security sources.
  - The rename guard: `DefaultPageRenamer.renamePage` refuses, before any write, when `registry.hasDraft(newName)`. It throws `WikiException("Page '<to>' has an unpublished collaborative draft; publish or discard it first")` (§0 Minor; the store allows one draft per page).
  - The deletion hook: `UserDeletionService.delete` calls `registry.evictAuthor(loginName)` after the account row is deleted. It is best-effort: a `RuntimeException` is logged at WARN and the delete completes (§0 Minor, Deleted users). The construction sites `RestServletBase:125` and `AdminUserResource:109` stay unchanged, because the service reaches the registry through `CollabEngineAccess`.

- [ ] **Step 1:** Write the tests:
  - `eachNonEditorWriterIsMergedIntoTheLiveDraft` (§13.2): `PageSaveHelper.saveTracked` (the REST/MCP path), `ClusterRenameService`, `BlockIdService`, `putPageText` + `POST_SAVE_BEGIN` (the watcher's shape) and `DefaultAclManager.setPermissions`. Each yields one server update in a live session, with an `external-change` event.
  - `ownPublishDoesNotProduceAServerUpdate`.
  - `e27_aRenameDuringPublishLandsOnTheNewName` (**Review Focus #3**): a blocking pre-save filter holds the publish before the lock, as `GuardedSaveTest` does; another thread renames via `PageRenamer`; release. The first answer is 409 `publish-merged-retry`, the retry lands on the new name, and the old name does not exist.
  - `renamingOntoAPageWithADraftIsRefusedBeforeAnyWrite`.
  - `pageDeleteFreezesTheDraft`.
  - `aCollabFailureNeverAbortsAUserDelete` (**Review Focus #4**): a registry whose `evictAuthor` throws; the delete completes, with a WARN.
  - `aDeletedUsersParticipantsAreEvictedInEverySession`.
  - `unwiredCollabIsANoOp` (`CollabEngineAccess.registry` returns null).

  Run red.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green: `-Dtest='CollabEngineWritersTest,UserDeletionServiceTest,*PageRenamer*,CollabWiringTest'`. Then install and run `-pl wikantik-war -Dtest='DecompositionArchTest,CollabSeamArchTest,JdbcAccessArchTest'`.
- [ ] **Step 4:** Commit `feat(collab): wire the page listener, the rename guard and the deleted-user hook`.

### Task 7: REST surface — meta edits, eviction, renamed sessions (worktree)

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/CollabResource.java` (Phase 1 Task 7)
- Test: extend `wikantik-rest/src/test/java/com/wikantik/rest/CollabResourceTest.java`

**Interfaces:**
- Consumes: Task 1's fixture; Task 2's `MetaEdit`, push and `PushResponse.metaSeq`; Task 3's `answersTo`, the eviction tombstone, `frozen` and `no-create`.
- Produces the fixture's wire shapes:
  - `metaOps` parsed into `MetaEdit` by key presence (`value` present → SET; `add` → ADD; `remove` → REMOVE). More than one key, or none, → `400 {reason:"rejected"}`, checked before the session is called.
  - The push `200` body carries `metaSeq`. Pulled meta ops carry `by` when it is non-null. Events serialise as `{seq, type, …fields}`.
  - `403 {reason:"evicted", detail}` on every post-join call.
  - Post-join calls resolve by `sessionId`. The path page must satisfy `session.answersTo(name)`, else `404`. The `edit` check uses the session's current page.
  - A frozen publish without `createPages` → `403 {reason:"no-create"}`.

- [ ] **Step 1:** Write the tests:
  - every new fixture case, asserted against `collab-protocol.json` as in Phase 1;
  - `metaEditsAreParsedIntoKinds`;
  - `aMetaEditWithTwoKindsIs400`;
  - `pushAnswersCarryMetaSeq`;
  - `pulledMetaOpsNameTheAuthorByDisplayName`;
  - `anEvictedParticipantGets403WithDetail`;
  - `requestsUnderTheOldNameReachARenamedSession`;
  - `anUnrelatedPageNameIs404`;
  - `frozenPublishWithoutCreatePagesIs403`;
  - `responsesAreStillNoStore`.

  Run red: `mvn -o -q test -pl wikantik-rest -Dtest=CollabResourceTest`. In the worktree, the controller first installs wikantik-main on main and the worktree reuses `~/.m2`.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green. Also run `ApiFilterChainAsyncTest`.
- [ ] **Step 4:** Commit `feat(collab): REST surface for meta edits, eviction and renamed sessions`.

### Task 8: Simulator coverage — presence, meta edits, rename, delete (worktree, test scope)

**Files:**
- Modify in `wikantik-main/src/test/java/com/wikantik/collab/testing/`: `SimProfile.java`, `SimScheduler.java`, `SimClient.java`, `SimServer.java`, `CollabSessionSimServer.java`, `CollabInvariants.java`, `InvariantView.java`, `ScriptedPageWriter.java` (only if Task 3 left a gap)
- Create: `wikantik-main/src/test/java/com/wikantik/collab/testing/CollabMetaCasesTest.java`. It replays Task 10's `wikantik-frontend/src/collab/__fixtures__/collab-meta-cases.json` against the Java model.
- Test: `CollabConvergenceFuzzTest`, `CollabInvariantsTest`, `SimSchedulerTest`, `CollabRegressionTracesTest`, `CollabTraceCorpusDriftTest`

**Interfaces:**
- Consumes: Tasks 2–3 through `CollabSession`; `ScriptedPageWriter.rename/delete`; Task 10's meta-cases fixture.
- Produces:
  - `SimProfile.Kind` gains `SELECT`, `META_EDIT`, `RENAME` and `DELETE`. These are session-only through the per-factory weights overlay (Phase 1 amendment), so the stub corpus does not churn.
  - `SimClient`:
    - a selection, sent on push and pull per A12;
    - a meta model with the pending-overlay rule of `collabMeta.js` (Task 10), documented as its Java mirror and held to it by `CollabMetaCasesTest`;
    - on rename, it keeps going by `sessionId`;
    - on delete, it stops local edits on `409 frozen`.
  - `SimServer`: `default void renamed( String to )` and `default void deleted()` (the stub's defaults are unsupported).

Invariants:
- **inv7**, implemented (it was a no-op until Phase 2): every live participant held an `edit` grant at its last check.
- **New inv8, presence:**
  - every server-held selection lies within `[0, len(doc at its version)]`;
  - one with `unconfirmed == 0` is at the head;
  - at quiescence, each live participant's server-held selection equals its `SimClient`'s last selection.
- **New inv9, meta:**
  - at quiescence, every client's displayed meta equals the server meta;
  - every ADD whose item no later REMOVE or SET of that field removed is present.

This settles the deferral "presence not fuzzed (Phase 2)".

- [ ] **Step 1:** Write `CollabMetaCasesTest`, the inv7, inv8 and inv9 unit cases in `CollabInvariantsTest`, and the scheduler cases for the new kinds. Run red.
- [ ] **Step 2:** Implement. Turn the new kinds on for the session factory in every profile.
- [ ] **Step 3:** Run green with the default seed count across every profile, for both servers; report the runtime. Also:
  - a 20k-seed soak (`-Dcollab.fuzz.seeds=20000`);
  - a mutant check: resolving ADD as a SET of the client's list must fail the fuzz with a shrunk seed (report it, then revert);
  - corpus drift and regression traces stay green, with the stub corpus unchanged.
- [ ] **Step 4:** Commit `test(collab): fuzz presence, metadata ops, rename and delete`.

### Task 9: Remote cursors, external-change highlights, presence bar (frontend)

**Files:**
- Create in `wikantik-frontend/src/collab/`: `remoteCursors.js`, `externalHighlights.js`, `PresenceBar.jsx`; tests `remoteCursors.test.js`, `externalHighlights.test.js`, `PresenceBar.test.jsx`, `remoteCursors.realCodeMirror.test.jsx`
- Modify: `useCollabSession.js` (extensions, participants, rename), `collabTransport.js` (`retarget`), `useCollabSession.test.jsx`, `collabTransport.test.js`; styles in `src/styles/globals.css` next to `.collab-status`

**Interfaces:**
- Consumes:
  - Task 1's fixture and fake;
  - `getSyncedVersion` and `sendableUpdates` from `@codemirror/collab`;
  - the peer's `onPresence`/`onEvents` (`collabPeer.js:37-39`; not modified here; Task 10 owns `collabPeer.js`).
- Produces:
  - `placeAtHead( pos, version, unconfirmed, state ) → { pos, approximate }`, a pure helper:
    - `version === getSyncedVersion(state)` and `unconfirmed === 0` → exact; mapped through the composed changes of `sendableUpdates(state)`.
    - Anything else → clamped to `state.doc.length`, `approximate: true`.
  - `remoteCursors()` extension: a `StateField` of decorations (caret widget with a name label, and a selection mark). Effect `setRemotePresence.of( presenceMap )`; entries with null selections draw nothing. Every transaction maps the positions (`tr.changes.mapPos`). An approximate entry gets class `cm-remote-approx`.
  - `externalHighlights()` extension: effect `addExternalHighlight.of( { ranges, version, id } )`, placed through `placeAtHead`, mapped through transactions, and removed after 10 s (`removeExternalHighlight.of( id )`).
  - `PresenceBar({ self, presence, status })`: initials and display name in the participant colour; "N editing"; connection state. Display names only.
  - `useCollabSession` changes:
    - `extensions` includes both new extensions;
    - presence updates dispatch `setRemotePresence`;
    - an `external-change` event dispatches `addExternalHighlight`;
    - `participants` = self (from join) plus the presence entries;
    - a `page-renamed {to}` event calls `transport.retarget(to)` with no leave or rejoin, and exposes `renamedTo`.
  - `createCollabTransport(pageName).retarget( newName )`.

- [ ] **Step 1:** Write the tests:
  - `aSelectionAtTheSyncedVersionIsPlacedExactlyThroughMyUnsyncedEdits`;
  - `aSelectionWithUnconfirmedUpdatesIsClampedAndMarkedApproximate`;
  - `cursorsFollowEveryTransaction`;
  - `aParticipantWithoutASelectionHasNoCaretButIsInTheBar`;
  - `aLeftParticipantsCaretIsRemoved`;
  - `externalChangeRangesAreHighlightedAndFadeAfterTenSeconds` (fake timers);
  - `theBarShowsDisplayNamesAndColors`;
  - `participantsFollowThePresenceMap`;
  - `pageRenamedRetargetsTheTransportWithoutRejoining` (the next push goes to `/api/collab/New/updates`; the join count stays 1);
  - `twoRealEditorsSeeEachOthersCaret` (two real `EditorView`s via the fake, typing concurrently).

  Run red: `npx vitest run src/collab`.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green: `npx vitest run src/collab && npx eslint src/collab`.
- [ ] **Step 4:** Commit `feat(collab): remote cursors, external-change highlights and the presence bar`.

### Task 10: Client metadata queue — debounced field ops and set ops (frontend)

**Files:**
- Create: `wikantik-frontend/src/collab/collabMeta.js`, `collabMeta.test.js`, `__fixtures__/collab-meta-cases.json`, `collabMeta.cases.test.js`
- Modify: `collabPeer.js` (meta in push; `whenSynced` covers meta; eviction), `collabPeer.test.js`

**Interfaces:**
- Consumes: Task 1's fixture and fake; the peer's `whenSynced()`/`isSynced()` (`collabPeer.js:93-100, :308`) and `onMetaOps`.
- Produces:
  - `diffMeta( prev, next ) → MetaEdit[]`, with `{field, value}` / `{field, add}` / `{field, remove}` as on the wire:
    - set fields (`tags`, `related`, `aliases`) become adds and removes;
    - other changed fields become SET;
    - a removed key becomes SET null.
  - `createMetaState({ meta, metaSeq })`:
    - `edit(field, value)`, `add(field, item)`, `remove(field, item)` queue edits;
    - `takeSendable()`, `acked(edits, metaSeq)`, `applyRemote(ops, metaSeq)`;
    - `view()` returns the display meta: server meta with the pending edits replayed on top;
    - `pending()`.
  - **Pending-overlay rule.** A sent edit leaves the pending list only when a pull has delivered `metaSeq >= its acked metaSeq`. A remote op before mine is overlaid by mine; a remote op after mine (higher seq) wins.
  - `createMetaQueue({ debounceMs = 400, schedule })`: per-field debounce; `flush()` sends at once (blur, publish).
  - `collabPeer`:
    - `handle.queueMetaEdits(edits)`;
    - pending edits ride on the next push, and a push with only meta edits is allowed;
    - on `200` it calls `onMetaAck(edits, res.metaSeq)`;
    - after a stale-base, network failure or 429, the edits stay queued and are resent, which is safe because edits are idempotent;
    - `whenSynced()` also waits for an empty meta queue after a flush;
    - `403 evicted` maps to a status by `detail`: `lost-edit` → `forbidden` with `evictedReason`, unsynced text discarded (A13: discarding is for permission revocation only); `signed-out` → `auth-required`, edits kept; `account-deleted` → `forbidden`.
  - `collab-meta-cases.json`: sequences of local edits, acks, remote ops and lost acks, each with the expected `view()` after every step. Task 8 replays the file in Java.

- [ ] **Step 1:** Write the tests:
  - `diffMetaTurnsSetFieldChangesIntoAddsAndRemoves`;
  - `diffMetaTurnsOtherChangesIntoSetsAndRemovedKeysIntoNull`;
  - `fieldEditsAreDebouncedPerField` (five keystrokes in 300 ms send one SET);
  - `flushSendsAtOnce`;
  - `myTagAddSurvivesTheGapBetweenAckAndPull` (**Review Focus #5**: ack, then pull; the display holds the add throughout; a lost ack and retry against the fake leaves one `x`);
  - `aRemoteOpAfterMineWins`;
  - `aRemoteOpBeforeMineIsOverlaid`;
  - `metaEditsRideOnAPresenceOnlyPush`;
  - `aStalePushKeepsTheMetaEditsForTheRetry`;
  - `whenSyncedWaitsForTheMetaQueue`;
  - `evicted403MapsToStatusByDetail`;
  - `collabMeta.cases.test.js` replays the fixture.

  Run red.
- [ ] **Step 2:** Implement. Write the fixture from the implemented rule, and have a second test re-derive it so it cannot drift.
- [ ] **Step 3:** Run green: `npx vitest run src/collab && npx eslint src/collab`.
- [ ] **Step 4:** Commit `feat(collab): client metadata queue with debounced field ops and set ops`.

### Task 11: Editor integration — frontmatter co-editing and lifecycle UI (frontend)

**Files:**
- Modify:
  - `wikantik-frontend/src/components/PageEditor.jsx`;
  - `wikantik-frontend/src/components/frontmatter/FrontmatterEditor.jsx`, `FieldWidget.jsx`;
  - `wikantik-frontend/src/collab/useCollabSession.js` (expose meta), `src/styles/globals.css`.
- Test: `PageEditor.collab.test.jsx`, `FrontmatterEditor.test.jsx`, `FieldWidget.test.jsx`; all existing `PageEditor*.test.jsx` stay green

**Interfaces:**
- Consumes:
  - Task 9's `PresenceBar`, `participants`, `presence` and `renamedTo`;
  - Task 10's `createMetaState`, `createMetaQueue`, `diffMeta`, `handle.queueMetaEdits` and the eviction statuses;
  - `api.getDiff(name, from, to)` (`api/client.js:326`).
- Produces:
  - `useCollabSession` returns `meta: { view, edit, add, remove, change(prev, next), flush, remoteChanged }`.
  - `FrontmatterEditor` and `FieldWidget` take optional `onFieldFocus(field)`, `onFieldBlur(field)` and `remoteChanged: { [field]: displayName }`.

Behaviour:
- **Frontmatter is editable in collab mode.** Remove the `<fieldset disabled={collabMode}>` and the `collab-frontmatter-locked` hint (`PageEditor.jsx:1540-1553`); this was Task 9's "frontmatter locked (Phase 2)" concern.
  - Field widgets → `meta.edit`/`add`/`remove`, debounced.
  - The whole-object RawYaml and AdvancedKeyValues `onChange` → `meta.change(prev, next)` (A14).
  - The form renders `meta.view()`.
- **Focused field.** A remote op on my focused field leaves my input untouched and shows "changed by {name}". On blur: if I changed the value since focus, my value is sent (LWW); otherwise the remote value is applied (§8.2).
- **Ruling P9 lifted.** Delete the `collabBlockedFor` latch and the `collab-frontmatter-blocked` fallback (`PageEditor.jsx:256-259, :1412`). Blocking frontmatter errors only disable Publish. A publish `422` stays in collab mode and maps violations to fields (the existing `showRefusal`).
- **Publish and guard.** Publish calls `meta.flush()` and then waits on `whenSynced` (which covers meta). The navigation guard also covers unsent meta edits.
- **Presence.** `PresenceBar` replaces the toolbar's " · N editing" text (`:1314`); `editorCount` = 1 + presence entries.
- **Offline-draft "alone" check.** It uses the live presence map and re-reads it at click time. This narrows ruling P10's window; it was Task 9's parked minor (a).
- **Events:**
  - `external-change` → toast "Updated by {source}" with **View diff**: a Modal rendering `api.getDiff(name, pageVersion - 1, pageVersion)`, reusing `DiffViewer`'s diff rendering (extract a component if needed). `conflictFields` → a "kept yours; {source} also changed this" note on those fields.
  - `page-renamed` (`renamedTo`) → `navigate('/edit/' + to, { replace: true, state: { collabKeep: true } })` without reloading the page or the session.
  - `page-deleted` (`status === 'frozen'`) → banner "This page was deleted — copy your text or recreate the page", with Publish labelled **Recreate page**. A `403 no-create` explains the missing permission.
  - `evictedReason === 'lost-edit'` → read-only, plus a message that the unsynced text was discarded. `signed-out` uses the existing sign-in banner.

- [ ] **Step 1:** Write the tests:
  - `frontmatterIsEditableInCollabMode` (a tags add reaches the fake as `{field:'tags', add}`; a second editor's form shows it);
  - `aBlockingFrontmatterErrorIsFixableInCollabMode`;
  - `a422KeepsCollabModeAndMapsViolations`;
  - `aRemoteOpOnMyFocusedFieldShowsTheIndicatorAndKeepsMyInput`;
  - `onBlurMyValueIsSentLastWriterWins`;
  - `rawYamlEditsBecomeFieldOps`;
  - `externalChangeShowsAToastWithViewDiff`;
  - `conflictFieldsAreFlaggedOnTheForm`;
  - `pageRenamedUpdatesTheUrlWithoutReloading`;
  - `pageDeletedShowsTheFrozenBannerAndRecreate`;
  - `recreateWithoutCreatePagesExplainsWhy`;
  - `evictedForLostEditGoesReadOnlyWithAMessage`;
  - `signedOutKeepsEditsAndPromptsLogin`;
  - `restoreIsRefusedWhenAPresenceEntryArrivesBeforeTheClick`;
  - `thePresenceBarReplacesTheEditingCount`;
  - `theGuardCoversUnsentMetaEdits`.

  Run red: `npx vitest run src/components/PageEditor.collab.test.jsx src/components/frontmatter`.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green: `npx vitest run src/components src/collab`, then `npx eslint .`. Re-run any timing-flaky test alone before treating it as a failure (memory: UI shared layer + vitest).
- [ ] **Step 4:** Commit `feat(collab): frontmatter co-editing and lifecycle UI in the page editor`.

### Task 12: REST IT, two-browser E2E, gate and docs

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/CollabRestIT.java` (Phase 1 Task 10; find where it landed)
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/CollabPresenceMetaIT.java`. The Selenide suite lives in `src/main/java` and runs from the `custom-jdbc` module. Enable collab in that module's test-properties overlay the same way Phase 1 did for the REST module.
- Modify (docs):
  - `docs/MultiEdit.MD` §15: the Phase 2 row's Ships cell, plus a "Phase 2 outcome" paragraph like Phase 0's;
  - `docs/developer/ProjectReference.md`;
  - `CHANGELOG.md` `[Unreleased]` → `### Added`, marked dark.

**Tests:**
- **`CollabRestIT`**, through real Tomcat:
  - A and B add different tags concurrently; both are in the published frontmatter.
  - A `PUT /api/pages` (an agent write) during the session reaches A's long-poll as a server update plus an `external-change` event, through the real listener, without a publish.
  - A rename through the REST rename endpoint (find it) gives a `page-renamed` event, and later calls under the new name work.
  - A delete gives `page-deleted`, and the next push is `409 frozen`. A recreate publish succeeds for an admin.
  - B's selection appears in A's pull.
- **`CollabPresenceMetaIT`**, two headless browsers (`awaitAdminReady` after login, per memory "Admin IT login race"):
  - B's caret label is visible in A;
  - B adds a tag in the form, and A's form shows it within 2 s;
  - A focuses `summary` while B changes it, and A sees the indicator;
  - an MCP or REST write shows A the toast;
  - A publishes, and the page view shows the text, tags and summary.

- [ ] **Step 1:** Write the ITs. Run red through `bin/agent-build.sh`: `bin/run-tests.sh --module rest` and `bin/run-tests.sh --module custom-jdbc`.
- [ ] **Step 2:** Fix what fails, test-first at the unit level where the defect lives.
- [ ] **Step 3:** Run green. Then run the full gate `bin/run-tests.sh --parallel 4`, plus the soaks `-Dcollab.fuzz.seeds=50000` and `-Dcollab.stress.runs=5000` on the collab tests.
- [ ] **Step 4:** Update the docs. Record the enablement step (admin group first, §15) for the operator. Do not flip the kill switch.
- [ ] **Step 5:** Commit `test(collab): Phase 2 REST IT and two-browser E2E; docs(MultiEdit): Phase 2 complete`.

## Out of scope (recorded so nothing is silently dropped)

- **Phase 3:** draft banner, publish-dialog diff, `page_version_contributor` and the history UI, audit entries, metrics.
- **Not assigned to any §15 row:** the `draft_active` flag on the page and for-agent payloads (§16 decision 2). Proposed for Phase 3.
- **Phase 1 Task 7's decision:** sending `markupSyntax` on publish (Task 9 concern 2). Convert-to-Markdown stays hidden in collab mode until it lands.
- **Deferred, optional:** `docHash` divergence detection (§14; protocol note `docHash`).
