# MultiEdit Phase 3 — Draft UX and Attribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A shared draft explains itself and credits its authors. Opening the editor shows who has unpublished changes and when they last edited. Publishing goes through a dialog that shows the diff and the contributors. Every published version records its contributors, and the history UI shows them by display name. Audit entries, metrics, retention and an admin purge make the feature operable, and the editor Selenide suite passes in collaborative mode. **The kill switch stays off** (see Decision 1).

**Architecture:**
- **Session (engine-free, `com.wikantik.collab`).** A new `Contributors` value records, per author key, the first and last text version and meta seq accepted since the last publish. It is rebuilt on load from the snapshot plus the retained log. At publish it credits exactly the authors whose edits are inside the published snapshot (V, M_V). The session writes those authors to `page_version_contributor` before it clears the pending-publish marker, so E39 recovery repeats the write and never loses it. It emits `draft-state` events and answers a draft summary.
- **Store.** Migration `V067` adds `collab_snapshot.contributors`. `CollabStore` gains the contributor ledger (`recordContributors`, `contributorsByVersion`) and a session listing.
- **Registry.** Adds the retention sweep (`draft.retentionDays`, never publishes), admin list and purge, a `draftActive` index with no database read on the request path, a participant count, and the settle cadence deferred from Phase 1.
- **Ports.** `CollabAudit` and `CollabMetrics` are ports; their adapters in `com.wikantik.collab.adapter` wrap `AuditService` and Micrometer. The core never imports either library.
- **REST.** `GET /api/collab/{page}/draft` returns the draft summary with a server-rendered HTML diff. `/admin/collab/drafts` lists and purges drafts. `/api/history` carries `contributors`. The page payload, the for-agent payload and the `get_page_for_agent` MCP tool carry `draft_active` (§16 decision 2).
- **Frontend (`wikantik-frontend/src/collab/`).** Adds `DraftBanner`, `PublishDialog` and `ContributorList`. `useCollabSession` exposes live draft state and the publish/discard/oversize notices. `PageEditor` mounts them. `ChangeNotesPanel` and `DiffViewer` render contributors.
- **IT harness.** A `--collab` mode for `bin/run-tests.sh` runs the Selenide suite with collaboration on, and the editor ITs are adapted to it (ruling P12).

**Tech Stack:** Java 25, Gson, `com.wikantik.jdbc.Jdbc`, Postgres migration `V067`, Servlet async, Micrometer; React plus CodeMirror 6, `@codemirror/collab` 6.1.1, vitest; Cargo ITs, the Selenide suite and k6.

**Spec:** `docs/MultiEdit.MD`. **§0 Review amendments are authoritative.** Read:
- §0: A5, A10, A11, A13, and the Minor items on migration, deleted users, audit, orphans and docs;
- §0 §16 decisions 1, 2 and 4;
- D3 (draft banner), §6.2 (`join.draft`, events), §7.3 (Attribution paragraph), §7.7, §8.2–8.3, §9 (Audit);
- §10 (`page_version_contributor`, as amended: keyed by `canonical_id`), §11 (`draft.retentionDays`), §12.3, §13.4 (load), §14, §15 (Phase 3 row).

The Phase 1 and Phase 2 plans (`docs/superpowers/plans/2026-11-06-multiedit-phase{1-core-text,2-presence-meta-external}.md`) define every interface consumed below. Their amendments, and the ledger rulings P1–P12 and Q1–Q9, are binding here.

## Preconditions

- **Phase 2 is complete on main**, Task 12 and its gate included. Phase 2 Task 3 is uncommitted in the main tree while this plan is written, and Phase 2 Tasks 5–8 and 12 have not run yet. Each task below names the Phase 2 outputs it consumes:
  - Phase 2 Task 3: `CollabSession.evict( String, String )`, `rekey`, `freeze`, `frozen`, `answersTo`, `Lifecycle`, the eviction tombstone and `JoinResponse.frozen`.
  - Phase 2 Task 5: `CollabSessionRegistry.liveForPage`, `hasDraft`, `evictAuthor`, `settleAll`, and `CollabPageEventListener`.
  - Phase 2 Task 6: `CollabEngineAccess.registry( Engine )`.
  - Phase 2 Task 7: the REST eviction and renamed-session handling.
  - Phase 1 Task 7 fix `c382b56730` (`markupSyntax` on `PublishRequest` and `CollabSession.publish(…, markupSyntax)`, page-name validation, discard edit check) is cherry-picked.
- Before editing, a task greps for these names and reports any rename to the controller. A task starts only once every task it consumes is committed on main.
- **Phase 1 and Phase 2 close-outs:** Task 14 checks that the Phase 1 close-out (Phase 1 Task 11) and the Phase 2 gate ran. If either did not, Task 14 runs it before the Phase 3 gate (ledger note "close-out: run `bin/run-tests.sh --parallel 4` before declaring the phase done").

## Planner decisions (binding unless the user overrides them; Task 14 records them in `docs/MultiEdit.MD`)

1. **Phase 3 does not flip `wikantik.collab.enabled` to true.** §15 says Phase 3 ships "Default on". This plan delivers default-on *readiness*: the Selenide suite passes in collaborative mode (P12), plus audit, metrics, retention, purge and a load figure. The flip itself becomes a one-line change taken later. Reasons:
   - §15's staged rollout (prod for the admin group first, Phase 2) has not happened yet. There is no real multi-user soak to judge from, and the audit entries and metrics an operator needs for that judgement land in this phase.
   - There is no per-group gate. Enabling in prod today means enabling for everyone.
   - §0 Minor requires a check of Cloudflare's bot and WAF rate rules before enabling.
   - A default flip changes the editor for every deployment and makes the collab-mode Selenide run the default gate. Making the suite collab-ready first keeps the flip reversible and cheap.

   Task 14 writes a default-on checklist into §15.
2. **Contributors are credited at the publish point, not at head.** For a publish of collab version V and meta seq M_V, an author is credited when one of their accepted text updates has version ≤ V or one of their meta ops has seq ≤ M_V, counting since the last publish. Edits made during the save (W) are credited to the next version. The session creator's A11 seed (`initialText`/`initialMeta`, stored with the creator's author key) counts as a contribution. External merges, discards and publish rewrites carry no author and credit nobody. The publisher is not added unless they contributed: the version's author already names them.
3. **No names are auto-appended to the change note** (§7.3). Attribution is structured data: the version author is the publisher, through `AuthorKeys` as today, and the contributors are rows in `page_version_contributor`. Both are rendered at read time through `PublicIdentity`, so a later rename or account deletion shows correctly (`FORMER_MEMBER`). @mention notifications credit the publisher (§0 Minor, accepted for v1).
4. **`page_version_contributor` rows are replaced, not merged.** `recordContributors( canonicalId, v, keys, created )` deletes the rows at `(canonicalId, v)` and inserts the new ones, in one transaction. When the publish created the page (base version 0, a new page or a recreate), every earlier row of that `canonical_id` is deleted first, because version numbers restart. A publish whose saved frontmatter has no `canonical_id` records nothing and logs a WARN.

   Residual risk: a page deleted and recreated through the single-author editor with the same `canonical_id` keeps the old rows until a collab publish creates it again. Task 14 records this in §10.
5. **Contributors persist in the snapshot**, through `V067` (`collab_snapshot.contributors JSONB`), and not in a per-push table. A reload takes the snapshot's map and replays the retained updates and meta ops that carry an author key. A10 truncation always keeps every update after the newest snapshot at or below the cut, so nothing is lost and pushes add no writes.
6. **The draft diff is rendered by the server** (`GET /api/collab/{page}/draft`, through `DifferenceManager.makeDiff`, the renderer behind `DiffViewer`). No npm diff dependency is added. The summary covers the server head, so the client syncs before asking for it.
7. **§0 "a publish that leaves no participant with `edit` freezes the draft for an administrator"** is realised as a `collab.orphaned` audit entry plus the admin drafts list. It does not use the session's `frozen` status, because `frozen` means "page deleted" and its publish recreates the page with expected version 0, which would be wrong for a page that still exists. Anyone who later holds `edit` can open the draft. An administrator can purge it (`DELETE /admin/collab/drafts/{id}`, §0 "an admin purge lands before Phase 4"). The admin **view** stays in Phase 4 (§15).
8. **Convert to Markdown in collab mode becomes "Convert and publish".** The whole-body replacement goes in as one transaction (E7), and the draft is published at once with `markupSyntax: "markdown"`, after a confirmation naming the editor count. Other participants refetch the page's markup syntax on the `published` event. The syntax therefore always travels with a page version, never with an unpublished draft. Alternative: keep Convert hidden until Phase 4.
9. **The collab-mode Selenide run** (`bin/run-tests.sh --module custom-jdbc --collab`) joins `--all`, the release run, and Task 14. It is not in the default `--parallel 4` gate until the default flips.

## Global Constraints

- Every Phase 0–2 global constraint applies, and so do rulings P1–P12 and Q1–Q9:
  - TDD red-first; never swallow exceptions; LF-only text; Gson; `Jdbc` only; stage files by name; the commit trailer.
  - The seam rule (`CollabSeamArchTest`).
  - Display names, never logins, on every non-admin wire surface (§9).
  - `Cache-Control: no-store` on every collab response.
- **One migration: `bin/db/migrations/V067__collab_snapshot_contributors.sql`.** It is DDL only (`ALTER TABLE collab_snapshot ADD COLUMN IF NOT EXISTS contributors JSONB NOT NULL DEFAULT '[]'::jsonb`), idempotent, with no backfill, and re-applying it is a no-op. A task that finds it needs any other schema change stops and reports.
- **No new config keys.** Retention reads the existing `wikantik.collab.draft.retentionDays`. The settle safety net (15 s), the draft-page index refresh (30 s) and the retention sweep cadence (hourly) are constants.
- **The kill switch stays `false`** (Decision 1). No task edits its default in `ini/wikantik.properties`.
- **Wire changes are additive to protocol v1** and land in `wikantik-frontend/src/collab/__fixtures__/collab-protocol.json` first (Task 1). `CollabResourceTest` and `fakeCollabServer.test.js` both assert that file (P2).
- **Audit and metrics go through ports.** `com.wikantik.collab..` outside `..adapter..` never depends on `io.micrometer..` or `com.wikantik.audit..`; Task 5 extends `CollabSeamArchTest` to enforce it. Adapters never throw into a push, pull, publish or tick: they catch `RuntimeException` and log at WARN with the session and page.
- **No database read on the page `GET` path** for `draft_active`; it reads the registry's in-memory index (Task 4).
- **Admin surfaces** (`/admin/audit`, `/admin/collab/*`) may show author keys (logins). Every other surface shows `PublicIdentity` display names.
- **File ownership.** `PageEditor.jsx` has one owner, Task 11. `CollabSession.java`, `PublishFlow.java`, `SessionPush.java`, `DraftDiscard.java`, `Draft.java` and `SessionLoader.java` belong to Task 3, then Task 5 (which adds only port calls). `CollabSessionRegistry.java`, `SessionSlot.java` and `SessionSweeps.java` belong to Task 4, then Task 5 (which adds only the constructor and audit calls). `CollabResource.java`, `CollabWire.java` and `CollabPull.java` belong to Task 7.
- **Parallel work** (P8, P11): a task marked "worktree" runs in its own git worktree, with no `mvn install` there; the controller cherry-picks it onto main. Two Maven builds never share one tree. Frontend tasks run no Maven.
- **Commands:**
  - Java: `unset ${!WIKANTIK_@}; mvn -o -q test -pl <module> -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
  - All collab unit tests: `-Dtest='**/collab/*Test,**/collab/**/*Test'`. The narrower `**/collab/**/*Test` misses the top-level tests; delete stale surefire reports first.
  - Before a wikantik-rest or wikantik-knowledge test: `mvn -o -q install -pl wikantik-main -DskipTests` (main tree only).
  - Frontend: `cd wikantik-frontend && npx vitest run <path> && npx eslint <path>`.
  - Anything over about 5 minutes goes through `bin/agent-build.sh`.
  - A merge verify includes `LogLevelEnforcementTest` and the war arch tests (`CollabSeamArchTest`, `DecompositionArchTest`, `JdbcAccessArchTest`).

## Review Focus

1. **The credit boundary.** An author whose only edits arrive while the publish is inside its save is not credited for that version, but is credited for the next. An author with edits on both sides is credited for both. Task 3 test `anEditDuringTheSaveIsCreditedToTheNextVersionOnly`.
2. **Crash after save (E39).** Recovery writes the published version's contributor rows exactly once and never credits the next draft's authors to it. Task 3 test `e39_recoveryRecordsThePublishedVersionsContributors`.
3. **Contributors survive truncation and restart.** After A10 truncation and a reload, the session's contributor set and its order equal the set before the restart. Task 3 test `contributorsSurviveTruncationAndReload`.
4. **No login on any reader surface.** Contributors in `/api/history` are display names; a deleted account shows as `FORMER_MEMBER`; an email-shaped key is never returned. Task 8 test `historyContributorsAreDisplayNamesNeverLogins`.
5. **The collab-mode Selenide run is not vacuous.** With `--collab`, every adapted editor IT proves the editor reached `collab-status = live` before typing, and an editor that fell back to single-author mode fails the run. Task 12 test `CollabModeGuardTest.aFallbackToTheSingleAuthorEditorFailsTheTest`.

## Parallel waves

| Wave | Tasks | Notes |
|---|---|---|
| 0 | 1 | Frontend-only fixture and fake; gates 7, 9 and 11 |
| 1 | 2 (main tree) ∥ 9 ∥ 10 (frontend) | 2 needs Phase 2 complete |
| 2 | 3 (main tree, after 2) ∥ 4 (worktree, after 2) ∥ 11 (frontend, after 9) | 11 is the only `PageEditor.jsx` owner |
| 3 | 5 (main tree, after 3 and 4) ∥ 6 (worktree, test scope, after 3) ∥ 8 (worktree, after 2 and 4) | |
| 4 | 7 (main tree, after 3, 4 and 5) | |
| 5 | 12 (main tree, after 7 and 11) ∥ 13 (worktree, after 7, 8 and 11) | different IT modules; never two Maven builds in one tree |
| 6 | 14 | gate, docs, soak |

---

### Task 1: Protocol fixture and fake server for Phase 3 (frontend-only)

**Files:**
- Modify: `wikantik-frontend/src/collab/__fixtures__/collab-protocol.json`, `wikantik-frontend/src/collab/fakeCollabServer.js`, `wikantik-frontend/src/collab/fakeCollabServer.test.js`

**Interfaces:**
- Consumes: the Phase 2 fixture (protocol v1, Q3–Q8).
- Produces these additive v1 shapes:
  - **`join.draft.contributors`** is the list of display names of the authors with unpublished contributions, in first-contribution order. It is empty when the draft has none.
  - **Event `draft-state {seq, unpublished, contributors:[displayName], lastEditAt}`.** It is emitted when the contributor set or `unpublished` changes: an author's first contribution since the last publish, a publish, a discard, a purge. It is not emitted on every edit; a client advances `lastEditAt` itself whenever it receives an update or meta op.
  - **Event `published {by, pageVersion}`**, unchanged, is now pinned by a fixture case. So are `publish-failed {by}`, `draft-discarded {by}` and `oversize {bytes, limit}`.
  - **`GET /draft?sessionId&participantId`** answers `200 {basePageVersion, version, unpublished, contributors:[displayName], lastEditAt, diff:"<html>", metaChanges:[{field, before, after}]}`:
    - `diff` is the server's HTML diff of the published body against the draft head; it is the empty string when there is no text change.
    - `metaChanges` lists the top-level fields whose normalised values differ, in field order. A field the draft removed has `after: null`, and a field it added has `before: null`.
    - Refusals: `403 {reason:"not-your-participant"}`, `403 {reason:"evicted", detail}`, `409 {reason:"rejoin"}`, `404` (a page name the session does not answer to).
  - **`POST /publish`** accepts an optional `markupSyntax: "markdown"` (Phase 1 fix `c382b56730`), passed to the save.
  - **Eviction detail `purged`.** An administrator removed the draft: the event `evicted {participantId, reason:"purged"}`, and `403 {reason:"evicted", detail:"purged"}` on later calls. The client keeps unsynced text in the recovery panel, as for `signed-out`.
- Produces fake-server test controls: `fake.purge()`, `fake.oversize(bytes, limit)` and `fake.failNextPublish(by)`. The fake tracks contributors with Decision 2's rule, by display name.

- [ ] **Step 1:** Add the fixture cases:
  - `join.200.draft-contributors`;
  - `updates.get.200.draft-events` (`draft-state`, `published`, `publish-failed`, `draft-discarded`, `oversize`);
  - `draft.get.200`, `draft.get.200.unchanged` (empty `diff` and `metaChanges`), `draft.get.403.evicted`, `draft.get.404`;
  - `publish.200.markup`;
  - `updates.get.403.purged`.

  Add the notes `draftState`, `draftSummary`, `contributorsAreDisplayNames` and `purged`. Extend `fakeCollabServer.test.js` to replay every case. Pin cross-session addressing, a parked Phase 1 Task 8 minor: a call carrying session A's id under page B's path answers 404, and an unknown (evicted) id answers `409 rejoin`, as the server does (`CollabResource.java:263-282`).
- [ ] **Step 2:** Run red: `npx vitest run src/collab/fakeCollabServer.test.js`.
- [ ] **Step 3:** Implement in the fake. The fake's diff is a placeholder `<pre>` of the changed lines; the tests assert presence, not the HTML.
- [ ] **Step 4:** Run green: `npx vitest run src/collab && npx eslint src/collab`.
- [ ] **Step 5:** Commit `test(collab): protocol fixture for draft state, draft summary, publish events and purge`.

### Task 2: Store — snapshot contributors, contributor ledger, session listing

**Files:**
- Create: `bin/db/migrations/V067__collab_snapshot_contributors.sql`, `wikantik-main/src/main/java/com/wikantik/collab/model/Contribution.java`
- Modify:
  - `wikantik-main/src/main/java/com/wikantik/collab/model/Snapshot.java`, `CollabStore.java`;
  - `adapter/JdbcCollabStore.java`, `adapter/CollabWrites.java`, `adapter/CollabRows.java`;
  - test `InMemoryCollabStore.java`, `AbstractCollabStoreContractTest.java`.
- Test: `model/ContributionTest.java`; contract cases; a `JdbcCollabStoreRollbackTest` case

**Interfaces:**
- Consumes: the existing `CollabStore` contract, `CollabRows.mapSnapshot`, `CollabWrites`' snapshot upsert (`CollabWrites.java:123-126`) and `PostgresTestDb`.
- Produces:
  - `public record Contribution( int firstVersion, int lastVersion, long firstMetaSeq, long lastMetaSeq )`, with `public static final int NONE = 0`. Versions and seqs are 1-based, so 0 means "none of this kind". The constructor rejects:
    - a negative value;
    - a kind with only one bound set;
    - `first > last` within a kind;
    - both kinds unset.
  - `Snapshot` gains a 7th component `Map< String, Contribution > contributors` (an insertion-ordered, unmodifiable copy). A 6-arg constructor keeps every existing call site, and every test fixture, unchanged with `Map.of()`.
  - `CollabStore`:
    - `void recordContributors( String canonicalId, int pageVersion, Set< String > authorKeys, boolean created )`: replaces the rows at `(canonicalId, pageVersion)`; with `created`, it first deletes every row of `canonicalId`. One transaction, idempotent. An empty `authorKeys` still performs the deletes. It throws `IllegalArgumentException` for a blank `canonicalId` or a `pageVersion < 1`.
    - `Map< Integer, List< String > > contributorsByVersion( String canonicalId )`: version to author keys, each list sorted; an empty map when there are none.
    - `List< SessionRecord > sessions()`: every session, active and frozen, ordered by `lastEditAt` (nulls by `createdAt`), oldest first.
  - JSON format of `collab_snapshot.contributors`: an array in first-contribution order (JSONB does not keep object key order), `[{"a":"<authorKey>","v":[first,last],"m":[first,last]}]`.

- [ ] **Step 1:** Write the tests:
  - `ContributionTest`: `aValidContributionHasBothBoundsOfAtLeastOneKind`, `firstAfterLastIsRejected`, `aHalfSetKindIsRejected`.
  - Contract, both stores:
    - `snapshotContributorsRoundTripInOrder` (three authors inserted out of key order come back in insertion order);
    - `aSnapshotWithoutContributorsReadsAsEmpty`;
    - `recordContributorsReplacesTheVersionsRows`;
    - `recordContributorsIsIdempotent`;
    - `createdClearsEveryEarlierRowOfTheCanonicalId`;
    - `contributorsByVersionIsSortedPerVersion`;
    - `aBlankCanonicalIdIsRejected`;
    - `sessionsListsActiveAndFrozenOldestEditFirst`;
    - `deleteSessionKeepsTheLedger` (ledger rows outlive the draft).
  - Rollback: `recordContributorsRollsBackOnTheInsert` (`FaultInjectingDataSource` on the insert after the delete; the old rows survive).
  - Migration: `v067IsANoOpOnReapply` (apply it twice through `MigrationApplier`; the column exists once; old rows read `[]`).
- [ ] **Step 2:** Run red: `-Dtest='ContributionTest,InMemoryCollabStoreContractTest,JdbcCollabStore*'`. The JDBC tests need Docker; a skip must show its reason.
- [ ] **Step 3:** Implement. Write the migration first, then the store. In `InMemoryCollabStore`, add `RECORD_CONTRIBUTORS` to `Op` for `failOn`.
- [ ] **Step 4:** Run green, then the collab pattern `-Dtest='**/collab/*Test,**/collab/**/*Test'`. Nothing else may change.
- [ ] **Step 5:** Commit `feat(collab): snapshot contributors, the page-version contributor ledger and a session listing (V067)`.

### Task 3: Session — contributor tracking, credit at publish, draft state and summary

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/collab/Contributors.java`, `wikantik-main/src/main/java/com/wikantik/collab/model/DraftSummary.java`
- Modify:
  - `CollabSession.java` (join, `View`, `draftSummary`), `Draft.java` (contributors field, snapshots), `SessionPush.java`, `PublishFlow.java`, `PublishStart.java` (`Ticket.metaSeq`), `DraftDiscard.java`, `SessionLoader.java`, `Roster.java`;
  - `model/JoinResponse.java`.
- Test: `ContributorsTest.java`, `CollabSessionContributorsTest.java`; extend `RecoveryTest` (E39) and `CollabSessionTest` (join)

**Interfaces:**
- Consumes:
  - Task 2's `Contribution`, `Snapshot.contributors`, `recordContributors`;
  - `PublishFlow.applyPublished`, `settleMarker`, `recoveredTicket` (`PublishFlow.java:188-217, :428-475`);
  - the five main `new Snapshot(` sites (`Draft.java:178, :220`, `PublishFlow.java:160`, `SessionLoader.java:62`, `DraftDiscard.java:142`);
  - `Draft.seed` (`Draft.java:151`; it already stores the seed with the creator's key);
  - `SessionFeed.emit`, `CollabIdentity.displayName`.
- Produces:
  - `final class Contributors` (package-private, engine-free; not thread-safe, used under the session lock):
    - `static Contributors of( Map< String, Contribution > m )`;
    - `void text( String authorKey, int version )`, `void meta( String authorKey, long seq )`;
    - `Set< String > creditedAt( int version, long metaSeq )`, in first-contribution order;
    - `Contributors afterPublish( int version, long metaSeq )`, which returns a new instance (the receiver is unchanged until the commit succeeds);
    - `void clear()`, `List< String > authors()`, `Map< String, Contribution > snapshot()`, `boolean isEmpty()`.
  - **`afterPublish` rule.** For each author: `textKept = lastVersion > V`, `metaKept = lastMetaSeq > M`. The author is kept iff one of them holds, as `Contribution( textKept ? V+1 : NONE, textKept ? lastVersion : NONE, metaKept ? M+1 : NONE, metaKept ? lastMetaSeq : NONE )`. Resetting `first` to V+1 is exact for the only question ever asked (`first ≤ V'`), because the next publish's V' is the head at that time, which is at least `lastVersion`. Write that sentence in the Javadoc.
  - `PublishStart.Ticket` gains `long metaSeq` (M_V's seq). `snapshotForSave` sets it from `d.metaSeq`; `recoveredTicket` reads it from the snapshot at or below V.
  - `public record DraftSummary( int status, String reason, String detail, int basePageVersion, int version, boolean unpublished, List< String > contributors, Instant lastEditAt, String baseBody, String doc, Map< String, Object > baseMeta, Map< String, Object > meta )`, plus `static DraftSummary refused( int status, String reason, String detail )`.
  - `CollabSession.draftSummary( String participantId, String authorKey )`. It runs `Gate.identity` (403, 409 rejoin, 403 evicted) and touches the participant; it is not version-checked and not rate-limited. It is a snapshot taken under the lock; the diff is the caller's job.
  - `JoinResponse` gains a 14th component `List< String > contributors` (display names), plus a 13-arg constructor that passes `List.of()`.
  - `View` gains `List< String > contributorKeys` and `boolean unpublished`.

Rules:
- **Tracking.** An accepted push records `text(author, v)` for each of its updates and `meta(author, seq)` for each stored meta op. `Draft.seed` records the creator. A load rebuilds the map from `latestSnapshot.contributors()`, then replays every retained update with `version > snapshot.version` and every meta op with `seq > snapshot.metaSeq` that carries an author key. Every snapshot written carries `contributors.snapshot()`.
- **Publish.** In `applyPublished`, before `d.commit(…)` clears the marker:
  - `credited = contributors.creditedAt( t.version(), t.metaSeq() )`;
  - `canonicalId = savedMeta.get( "canonical_id" )`;
  - `store.recordContributors( canonicalId, saved.version(), credited, t.baseVersion() == 0 )`.

  A blank `canonicalId` skips the write with a WARN naming the page. If the store throws, the publish is `unfinished( t )` (503, the marker stays, `settle()` retries): the version is saved but unattributed, and the retry attributes it. The commit snapshot carries `contributors.afterPublish( V, M_V ).snapshot()`, and that instance replaces the live one only after the commit succeeds. The E39 path runs through the same `finish`, so recovery repeats the idempotent write.
- **Discard** clears the contributors, and the discard snapshot carries `[]`.
- **`draft-state` event** (Task 1 shape). Compute `(contributor keys, unpublished)` before and after each accepted push, publish, discard, seed and external merge, and emit when either changed. Names come from `identity.displayName`, never the key. A blank name becomes `"Unknown contributor"`, never the positional `"Editor N"`.
- **Roster, a parked Phase 1 Task 3 note.** A rejoin by the same author key keeps its `"Editor N"` fallback within the session (map author key → n), so the label no longer changes on rejoin.

- [ ] **Step 1:** Write the tests:
  - `ContributorsTest`:
    - `creditedAtIsBoundedByVersionAndMetaSeq`;
    - `afterPublishKeepsOnlyLaterContributions`;
    - `aSeededModelAgreesWithBruteForce`: a 2,000-seed loop of random authors, edits and publish points; at each publish compare `creditedAt` with a brute-force scan of the op list since the previous publish.
  - `CollabSessionContributorsTest`:
    - `twoAuthorsArePublishedAsContributors` (the store holds rows for both at the new version);
    - `anEditDuringTheSaveIsCreditedToTheNextVersionOnly` (**Review Focus #1**: `pauseNextPublish`; B pushes; release; v+1's rows lack B; B publishes; v+2's rows hold B; an author with edits on both sides is in both);
    - `aMetaOnlyContributorIsCredited`;
    - `theCreatorsSeedIsCredited` (`initialText` from a template; publish with no typing);
    - `externalMergesCreditNobody`;
    - `discardClearsTheContributors`;
    - `aRecreatePublishClearsOldRows` (frozen draft, created = true);
    - `aMissingCanonicalIdSkipsTheLedgerWithAWarn` (asserts the WARN);
    - `aLedgerFailureLeavesThePublishUnfinishedAndTheRetryRecords` (`failOn( RECORD_CONTRIBUTORS, 1 )`, then `settle()`);
    - `contributorsSurviveTruncationAndReload` (**Review Focus #3**: three authors, `truncateLog()` past a cadence snapshot, `load`; equal set and order);
    - `joinCarriesContributorDisplayNames`;
    - `draftStateIsEmittedOnlyWhenTheSetOrUnpublishedChanges` (ten pushes by one author emit one event);
    - `draftSummaryIsIdentityCheckedAndCarriesBaseAndHead`;
    - `aRejoinKeepsItsEditorNumber`.
  - `RecoveryTest`:
    - `e39_recoveryRecordsThePublishedVersionsContributors` (**Review Focus #2**: crash after the save with B's edit landed after V; recovery writes rows for v+1 without B, once; the rows match a no-crash run);
    - `e39_aForeignVersionRecordsNothing`.

  Run red: `-Dtest='ContributorsTest,CollabSessionContributorsTest,RecoveryTest,CollabSessionTest'`.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green, then the collab pattern, plus `-Dtest='CollabConvergenceFuzzTest,CollabRegressionTracesTest,ConcurrencyHarnessTest'` at the default seeds. The stub corpus must not churn.
- [ ] **Step 4:** Commit `feat(collab): contributors tracked per draft and credited at the publish point (§7.3, §10)`.

### Task 4: Registry — settle cadence, retention, admin purge, draft index, tick hygiene (worktree)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/collab/CollabSessionRegistry.java`, `SessionSlot.java`, `SessionSweeps.java`
- Create: `wikantik-main/src/main/java/com/wikantik/collab/model/DraftListing.java`, `wikantik-main/src/main/java/com/wikantik/collab/model/PurgeOutcome.java`
- Test: `CollabSessionRegistryDraftsTest.java`; extend `CollabSessionRegistryTest` (settle cadence, tick logging)

**Interfaces:**
- Consumes:
  - Task 2's `sessions()`;
  - Phase 2's `liveForPage`, `hasDraft`, `evict( String, String )`, `Hooks.members/hasUnpublishedChanges/publishing/owesThePageStore`, `evictLocked`, `store.deleteSession`;
  - `SessionSlot.needsSettle` (`SessionSlot.java:116`) and `warnDue` (`:121`).
- Produces:
  - `public static final String PURGED = "purged"` on the registry (a participant's eviction reason; the constant stays out of `CollabSession.java` to avoid a conflict with Task 3).
  - `public record DraftListing( UUID sessionId, String pageName, String status, String createdBy, Instant createdAt, Instant lastEditAt, boolean live, int participants, boolean publishPending )`. The author key is kept here because this is an admin surface; display names are added by REST.
  - `public List< DraftListing > drafts()`: from `store.sessions()`, overlaid with live state.
  - `public record PurgeOutcome( int status, String reason, String pageName, boolean wasLive )`: `204`; `404 unknown`; `423 publish-in-progress` while the session publishes or holds a marker.
  - `public PurgeOutcome purge( UUID sessionId )`: under the slot lock, evicts every participant with `PURGED` and wakes parked pulls, then unmaps the slot and calls `deleteSession`. A store failure answers `503 store` with the draft kept.
  - `public boolean draftActive( String pageName )`: a live session with participants or unpublished changes, or the name is in `draftPages`. `draftPages` is a concurrent set reloaded from `store.sessions()` by the tick every 30 s, and updated synchronously on create, rekey, purge and delete. No store read on the call path.
  - `public int participantCount()`: the participants across live sessions (for Task 5's gauge).
  - `public Map< Integer, List< String > > contributorsByVersion( String canonicalId )`: delegates to the store, and answers an empty map with a WARN on a store failure.
  - **Retention sweep** (§7.7, §11: `draft.retentionDays` is read today but enforced nowhere). When `retentionDays > 0`, at most hourly, the tick deletes each stored draft whose `lastEditAt` (else `createdAt`) is older than the retention, unless it has live participants or a pending-publish marker. A live one is evicted from memory first. It never publishes, logs one INFO line per expired draft, and records the purge for Task 5's audit hook.

Changes deferred from Phase 1:
- **Settle cadence** (Task 5 ruling, "after Phase 2's page listener lands"). `needsSettle()` becomes `owesThePageStore || ( members non-empty && 15 s since the last settle )`. The listener delivers saves at once; the 15 s settle is the safety net for a missed event. Today every session with participants reads the page every second.
- **Tick log hygiene** (Phase 1 Task 5 nits; default-on traffic would make both noisy):
  - `lastWarn` becomes per step, so one step's warning no longer mutes another's;
  - a step that keeps throwing the same `Error` logs once per `warnDue` window, not every tick.

- [ ] **Step 1:** Write the tests (registry over `InMemoryCollabStore`, `ScriptedPageWriter`, `MutableClock`):
  - `draftsListsStoredAndLiveSessionsWithLiveState`;
  - `purgeEvictsEveryParticipantWithPurgedAndDeletesTheDraft` (a parked pull answers 403 evicted `purged`);
  - `purgeDuringAPublishIs423` (`pauseNextPublish`);
  - `purgeOfAnUnknownSessionIs404`;
  - `aPurgeStoreFailureKeepsTheDraft`;
  - `retentionExpiresOldIdleDraftsAndNeverPublishes` (advance 31 days with retention 30; the draft is gone; `ScriptedPageWriter` saw no publish);
  - `retentionSparesLiveAndPendingDrafts`;
  - `retentionZeroKeepsEverything`;
  - `draftActiveReadsNoStoreOnTheCallPath` (a store that throws on `sessions()` and `sessionForPage` after warm-up; `draftActive` still answers from memory);
  - `draftActiveFollowsCreateRekeyAndPurge`;
  - `participantCountSumsLiveSessions`;
  - `aSessionWithParticipantsSettlesEveryFifteenSecondsNotEverySecond` (count `writer.latest` calls over 30 ticks);
  - `anOwedSessionStillSettlesEveryTick`;
  - `oneStepsWarningDoesNotMuteAnother`;
  - `aRepeatingErrorIsLoggedOncePerWindow`.

  Run red: `-Dtest='CollabSessionRegistry*'`.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green, then the collab pattern and `-Dtest=ConcurrencyHarnessTest` (E33 still holds).
- [ ] **Step 4:** Commit `feat(collab): draft retention, admin purge, the draft-active index and a quieter settle cadence`.

### Task 5: Audit and metrics ports, adapters and wiring

**Files:**
- Create in `wikantik-main/src/main/java/com/wikantik/collab/`: `CollabAudit.java`, `CollabMetrics.java`; in `adapter/`: `AuditServiceCollabAudit.java`, `MicrometerCollabMetrics.java`
- Modify:
  - `CollabPorts.java`, `CollabSessionRegistry.java` (constructor, `metrics()`, audit at Task 4's purge and expiry sites, `sessionOpened`);
  - `CollabSession.java`, `PublishFlow.java`, `DraftDiscard.java`, `Lifecycle.java`, `ExternalMerge.java`, `Answers.java` (port calls only);
  - `adapter/CollabWiring.java`;
  - `wikantik-war/src/test/java/com/wikantik/architecture/CollabSeamArchTest.java`.
- Test: `CollabAuditTest.java`, `CollabMetricsTest.java` (recording fakes); `adapter/AuditServiceCollabAuditTest.java`, `adapter/MicrometerCollabMetricsTest.java` (`SimpleMeterRegistry`); extend `CollabWiringTest`

**Interfaces:**
- Consumes:
  - Task 3's `credited` set and the publish finish;
  - Task 4's `purge`, the retention sweep and `participantCount`;
  - `AuditService.record( AuditEntry )` with `AuditEntry.builder()` (pattern: `UserLifecycleService.java:108-138`);
  - `MeterRegistryHolder.get()` (`WikiEngine.java:1058`, may be null);
  - `WikiEngine.getAuditService()` (`:893`, built after the collab wiring, so resolve it lazily).
- Produces:
  - `public interface CollabAudit`, with `CollabAudit NONE` and default no-op methods:
    - `sessionOpened( UUID session, String page, String authorKey )`;
    - `joined( UUID session, String page, String authorKey, String participantId )`;
    - `published( UUID session, String page, String publisherKey, int pageVersion, int collabVersion, Set< String > contributorKeys )`;
    - `discarded( UUID session, String page, String authorKey )`;
    - `evicted( UUID session, String page, String authorKey, String reason )`;
    - `purged( UUID session, String page, String adminKey, boolean expired )`;
    - `orphaned( UUID session, String page )`.
  - `public interface CollabMetrics`, with `CollabMetrics NONE` and default no-op methods:
    - `push( String result )`, where `result ∈ { accepted, rebase, rejected }`;
    - `pushLatency( long nanos )`;
    - `publish( String result )`, where `result ∈ { published, unchanged, conflict, refused, failed, store }`;
    - `externalMerge()`;
    - `resync()`.
  - `CollabPorts( store, writer, identity, permissions, clock, audit, metrics )`. The existing 5-arg constructor passes `NONE` twice.
  - `CollabSessionRegistry( CollabPorts ports, CollabConfig config, long maxPageBytes )`; the existing constructors delegate. `public CollabMetrics metrics()`.
  - `AuditServiceCollabAudit( Supplier< AuditService > audit )`:
    - category `CONTENT` (`ADMIN` for `purged`);
    - event types `collab.session.open`, `collab.join`, `collab.publish`, `collab.discard`, `collab.evict`, `collab.purge`, `collab.expire`, `collab.orphaned`;
    - actor principal = the author key, actor type `user` (`system` for expiry), target type `page`, target id = page name;
    - `detail` JSON carries the session id plus the event's fields. The publish detail lists the contributor keys (an admin-only surface, §0 Minor: "collab audit entries name the publisher and the contributors").
    - A null supplier value, or a throwing `record`, logs at WARN.
  - `MicrometerCollabMetrics( MeterRegistry )` registers meters that scrape exactly as §12.3 names them:
    - `wikantik_collab_push_total{result}`, `wikantik_collab_push_latency_seconds`, `wikantik_collab_external_merges_total`, `wikantik_collab_publish_total{result}`, `wikantik_collab_resync_total`;
    - `bindGauges( CollabSessionRegistry )` adds `wikantik_collab_sessions_active` and `wikantik_collab_participants_active`.

    With no `MeterRegistry` installed, `CollabWiring` uses `NONE` and logs one WARN.

Call sites. Each runs after the session lock is released, through the existing `completions` lists:
- `sessionOpened` when the registry creates a session.
- `joined` on every join.
- `published` in `applyPublished`, with the credited set, on both the normal and the E39 path.
- `discarded` in `DraftDiscard`.
- `evicted` in `Lifecycle` eviction, for `lost-edit`, `signed-out`, `account-deleted` and `purged`.
- `purged` and `expired` at Task 4's sites. The admin key reaches `purge` from REST, so `purge( UUID, String adminKey )` replaces Task 4's one-argument form.
- `orphaned` in `endTicket` when the post-publish recheck evicted every participant and unpublished changes remain (Decision 7).
- Metrics: in `push` (classify the `PushResponse`: 200 → accepted; 409 `stale-base` → rebase; else rejected), at every publish answer, at each `external-change` emit, and at each 410 in `Answers`.

- [ ] **Step 1:** Write the tests:
  - `aJoinPublishDiscardAndEvictionAreAudited` (a recording fake; the publish entry carries the credited keys);
  - `theE39PathAuditsThePublishOnce`;
  - `anOrphaningPublishIsAudited`;
  - `purgeAndExpiryAreAuditedAsAdminAndSystem`;
  - `pushResultsAreCountedByKind` (200, stale 409, 413);
  - `publishResultsAreCounted`;
  - `externalMergesAndResyncsAreCounted`;
  - `aThrowingAuditDoesNotFailThePush`;
  - adapter: `entriesHaveTheDocumentedTypesAndDetail`, `aNullAuditServiceIsAWarnNotAThrow`, `metersScrapeUnderTheSpecNames`, `gaugesReadTheRegistry`;
  - wiring: `noMeterRegistryMeansNoOpMetricsAndOneWarn`;
  - `CollabSeamArchTest`: `coreDoesNotDependOnMicrometerOrAudit`.

  Run red: `-Dtest='CollabAuditTest,CollabMetricsTest,AuditServiceCollabAuditTest,MicrometerCollabMetricsTest,CollabWiringTest'`.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green, then the collab pattern. Install, then run `-pl wikantik-war -Dtest='CollabSeamArchTest,DecompositionArchTest,JdbcAccessArchTest'` and `-pl wikantik-main -Dtest=LogLevelEnforcementTest`.
- [ ] **Step 4:** Commit `feat(collab): audit entries and Prometheus metrics for collaborative editing (§9, §12.3)`.

### Task 6: Simulator coverage — contributors and the ledger (worktree, test scope)

**Files:**
- Modify in `wikantik-main/src/test/java/com/wikantik/collab/testing/`: `SimServer.java`, `CollabSessionSimServer.java`, `CollabInvariants.java`, `InvariantView.java`, `SimScheduler.java` (the publish bookkeeping only)
- Test: `CollabInvariantsTest`, `CollabConvergenceFuzzTest`, `RecoveryTest` (F8 replays), `CollabTraceCorpusDriftTest`

**Interfaces:**
- Consumes: Task 3 through `CollabSession` (`view().contributorKeys()`), Task 2's `InMemoryCollabStore.contributorsByVersion`, and the scheduler's publish completion and crash actions.
- Produces:
  - `SimServer`: `default Set< String > contributors()` and `default Map< Integer, List< String > > ledger( String canonicalId )`. The stub's defaults are unsupported, and the stub corpus does not churn (per-factory overlay).
  - **inv10, contributors.** At quiescence, the session's contributor set equals the authors of every accepted client update and meta op after the last completed publish's (V, M_V), as the scheduler's own model of accepted ops records them.
  - **inv11, ledger.** For every completed publish, the ledger rows at its page version equal the authors with accepted ops in (V_prev, V] or (M_prev, M_V], whatever crashes happened (F8).

- [ ] **Step 1:** Write the inv10 and inv11 unit cases in `CollabInvariantsTest`. Run red.
- [ ] **Step 2:** Implement. Turn the checks on for the session factory in every profile.
- [ ] **Step 3:** Run green at the default seed count for every profile, and report the runtime. Then:
  - a 20k-seed soak (`-Dcollab.fuzz.seeds=20000`);
  - a mutant check: crediting at the head instead of V must fail inv11 with a shrunk seed (report it, then revert);
  - corpus drift and the regression traces stay green.
- [ ] **Step 4:** Commit `test(collab): fuzz contributor tracking and the page-version ledger`.

### Task 7: REST — draft summary, contributors on the wire, admin drafts, telemetry

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/CollabResource.java`, `CollabWire.java`, `CollabJoin.java`, `CollabPush.java`, `CollabPull.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/CollabDraft.java`, `wikantik-rest/src/main/java/com/wikantik/rest/AdminCollabResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (servlet + `/admin/collab/*` mapping)
- Test: extend `CollabResourceTest`; create `AdminCollabResourceTest`; extend `CollabAsyncFiltersTest` (MDC)

**Interfaces:**
- Consumes:
  - Task 1's fixture;
  - Task 3's `draftSummary`, `JoinResponse.contributors` and the `draft-state` event;
  - Task 4's `drafts()`, `purge` and `PURGED`;
  - Task 5's `metrics()` and `purge( UUID, String )`;
  - `DifferenceManager.makeDiff( context, from, to )` (as `DiffResource.java:122-123` builds its context);
  - `MetaJson.normalise`;
  - `PublicIdentity` for the admin list's display names.
- Produces:
  - **`GET /api/collab/{page}/draft`** (route `draft` in `doGet`): resolves like every post-join call (`CollabResource.java:263-282`), then takes `session.draftSummary(…)` and renders `diff` outside any session lock. `metaChanges` compares `MetaJson.normalise` of `baseMeta` and `meta`. The body is exactly the fixture's; `no-store`.
  - **`CollabWire.joined`** fills `draft.contributors` (`CollabWire.java:169` writes `[]` today). Events serialise `draft-state`. Eviction detail `purged` passes through.
  - **Push latency.** `CollabPush` times each push into `metrics().pushLatency( nanos )`.
  - **MDC.** Every collab request puts `collab_session` (the session id, once known) into `ThreadContext`, and the async waiter's captured MDC (`CollabPull.java:100`) carries it (§12.3: logs carry the session id alongside `correlation_id`).
  - **CollabPull hygiene** (a parked Phase 1 Task 7 minor). A late hand-off after a timeout logs at DEBUG, not WARN.
  - **`AdminCollabResource`** (`/admin/collab/*`, behind `AdminAuthFilter`):
    - `GET /admin/collab/drafts` → `200 {drafts:[{sessionId, pageName, status, createdBy, createdByName, createdAt, lastEditAt, live, participants, publishPending}]}`.
    - `DELETE /admin/collab/drafts/{sessionId}` → `204`, `404`, `423 {reason:"publish-in-progress"}` or `503 {reason:"store"}`. It passes the admin's login as `adminKey`.
    - `no-store`; the delete is CSRF-checked like other admin writes.

- [ ] **Step 1:** Write the tests:
  - every new fixture case, asserted against `collab-protocol.json`;
  - `draftSummaryRendersTheHtmlDiffOutsideTheLock`;
  - `draftSummaryNamesContributorsByDisplayName`;
  - `draftSummaryOfAnEvictedParticipantIs403WithDetail`;
  - `draftSummaryUnderAnUnrelatedNameIs404`;
  - `metaChangesListAddedRemovedAndChangedFields` (a YAML `Date` against its JSON string is not a change, A14);
  - `joinCarriesDraftContributors`;
  - `aPurgedParticipantGets403Purged`;
  - `pushLatencyIsRecorded`;
  - `collabLogsCarryTheSessionId` (the MDC on the request thread and in the waiter's completion);
  - `aLateHandOffLogsAtDebug`;
  - `AdminCollabResourceTest`: `listsDraftsWithDisplayNamesAndLogins`, `deletePurges`, `deleteDuringPublishIs423`, `deleteUnknownIs404`, `nonAdminIs403` (through `AdminAuthFilter`), `responsesAreNoStore`.

  Run red: `mvn -o -q test -pl wikantik-rest -Dtest='CollabResourceTest,AdminCollabResourceTest,CollabAsyncFiltersTest'`.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green. Also run `ApiFilterChainAsyncTest`, and run `-pl wikantik-war -Dtest='ConfigSurfaceDriftTest'` to prove no undeclared key slipped in.
- [ ] **Step 4:** Commit `feat(collab): draft summary with diff, contributors on the wire, admin draft purge, collab telemetry`.

### Task 8: REST page surfaces — history contributors and `draft_active` (worktree)

**Files:**
- Modify:
  - `wikantik-rest/src/main/java/com/wikantik/rest/HistoryResource.java`, `PageResource.java` (GET payload near `:357`), `PageForAgentResource.java` (serialisation near `:127-146`);
  - `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetPageForAgentTool.java` and its wiring in `KnowledgeMcpInitializer`;
  - `wikantik-main/src/main/java/com/wikantik/collab/adapter/CollabEngineAccess.java` (Phase 2 Task 6).
- Test: `HistoryResourceTest`, `PageResourceTest` (or the existing GET payload test class), `PageForAgentResourceTest`, `GetPageForAgentToolTest`, `CollabEngineAccessTest`

**Interfaces:**
- Consumes: Task 4's `draftActive` and `contributorsByVersion`, Phase 2's `CollabEngineAccess.registry( Engine )`, `IdentityDisplay.ofAuthor( identity, key )`, and `FrontmatterParser`.
- Produces:
  - `CollabEngineAccess`:
    - `static boolean draftActive( Engine engine, String pageName )` returns false when collaboration is not wired;
    - `static Map< Integer, List< String > > contributorsByVersion( Engine engine, String canonicalId )` returns an empty map when it is not wired.
  - **`/api/history/{page}`.** Each version entry gains `contributors: [displayName]` (always present, possibly empty). The canonical id comes from the latest version's frontmatter. Names go through `IdentityDisplay.ofAuthor`, are deduplicated and sorted, and the version's own author is listed first when present. The ACL check is unchanged.
  - **Page `GET` and the for-agent payload.** Both gain `draft_active: boolean` (§16 decision 2). It is added at serialisation time, because `ForAgentProjection` is cached by `canonicalId + updatedAt` (`DefaultForAgentProjectionService.java:61`) and must not hold volatile state.
  - **`GetPageForAgentTool`** gains a constructor parameter `java.util.function.Predicate< String > draftActive` (the other constructors pass `p -> false`). `KnowledgeMcpInitializer` wires it from `CollabEngineAccess.draftActive`. Its example projection shows the field. Grep `InstructionsRegistryDriftTest`, `McpInstructionsDriftIT` and any output-schema test for the tool, and update what they pin.

- [ ] **Step 1:** Write the tests:
  - `historyListsContributorsPerVersion`;
  - `historyContributorsAreDisplayNamesNeverLogins` (**Review Focus #4**: a key with a display name, a tombstoned key → `FORMER_MEMBER`, an email-shaped key → `UNKNOWN_CONTRIBUTOR`; no raw key appears in the body);
  - `historyWithoutCollabWiringHasEmptyContributors`;
  - `aRenamedPageKeepsItsContributors` (canonical-id keyed);
  - `pageGetCarriesDraftActive`;
  - `forAgentCarriesDraftActiveWithoutCachingIt` (two GETs around a draft opening differ);
  - `theMcpToolCarriesDraftActive`;
  - `draftActiveIsFalseWhenUnwired`.

  Run red (`-pl wikantik-rest`, then `-pl wikantik-knowledge`).
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green, then `-pl wikantik-knowledge -Dtest='*Instructions*,GetPageForAgentTool*'` and `-pl wikantik-rest -Dtest='HistoryResource*,PageResource*,PageForAgent*'`.
- [ ] **Step 4:** Commit `feat(collab): contributors in page history and draft_active on page and agent payloads`.

### Task 9: Draft state, banner, publish dialog and contributor list (frontend)

**Files:**
- Create in `wikantik-frontend/src/collab/`: `DraftBanner.jsx`, `PublishDialog.jsx`, `ContributorList.jsx`; tests `DraftBanner.test.jsx`, `PublishDialog.test.jsx`, `ContributorList.test.jsx`
- Modify: `useCollabSession.js`, `collabTransport.js`, `collabMeta.js`, `useCollabSession.test.jsx`, `collabTransport.test.js`, `collabMeta.test.js`; styles in `src/styles/globals.css` next to `.collab-status`

**Interfaces:**
- Consumes:
  - Task 1's fixture and fake;
  - `components/ui/Modal`, `components/ui/ConfirmDialog`, `components/ui/Chip`;
  - the `formatRelative` helper `PageEditor` already uses;
  - `DiffViewer`'s diff markup classes;
  - presence entries' `unconfirmed` (A12).
- Produces:
  - `createCollabTransport( page ).draft({ sessionId, participantId })` → `GET …/draft`. `publish( body )` passes `markupSyntax` through.
  - `useCollabSession` adds:
    - `draft: { unpublished, contributors, lastEditAt }`, seeded from `join.draft`, replaced by each `draft-state` event, with `lastEditAt` advanced on every received update or meta op;
    - `published: { by, pageVersion, seq } | null`;
    - `notice: { type, by?, bytes?, limit?, seq } | null` for `publish-failed`, `draft-discarded` and `oversize`;
    - `oversizeLimit: number | null`;
    - `draftSummary()` → `{ ok: true, summary } | { ok: false, status, reason }`, which first flushes meta and waits on `whenSynced`;
    - `unsyncedParticipants()` → the display names of presence entries with `unconfirmed > 0` (§16 decision 1);
    - `publish( changeNote, { markupSyntax } = {} )`.
  - `DraftBanner({ draft, selfName, onPublish, onDiscard, onViewChanges, onDismiss })`: "Unpublished changes by Ana and Raj — last edit 2 hours ago" (D3). The user's own name reads "you". Hidden when `!draft.unpublished`.
  - `PublishDialog({ isOpen, mode = 'publish' | 'view', summary, unsynced, newerEdits, changeNote, onChangeNote, onConfirm, onCancel, busy })`:
    - the HTML diff in `DiffViewer`'s container, or "No text changes" when empty;
    - a `metaChanges` table;
    - a `ContributorList`;
    - an "Still typing: Raj" warning when `unsynced` is non-empty;
    - "N newer edits will be included" when `newerEdits > 0`;
    - the change note field. `mode='view'` hides confirm and the note.
  - `ContributorList({ names, max = 3 })`: "Ana, Raj and 2 others", with the full list in `title`.
  - **Parked minors:**
    - `collabMeta.unconfirmed()` no longer lists an edit whose ack and delivering pull already landed (Phase 2 Task 11 nit);
    - a capture with an empty `unsyncedText` never overwrites a recovery payload already delivered (Phase 1 Task 8 minor, judged unreachable; pin it with a guard and a test).

- [ ] **Step 1:** Write the tests:
  - `draftStateFollowsJoinAndEvents`;
  - `lastEditAtAdvancesOnRemoteUpdates`;
  - `publishedAndNoticesAreExposed`;
  - `draftSummaryWaitsForSync`;
  - `unsyncedParticipantsListsUnconfirmedPresence`;
  - `publishPassesMarkupSyntax`;
  - `theBannerNamesContributorsAndYou`;
  - `theBannerIsHiddenWithoutUnpublishedChanges`;
  - `theDialogShowsDiffMetaContributorsAndTheStillTypingWarning`;
  - `viewModeHasNoConfirm`;
  - `newerEditsAreAnnounced`;
  - `contributorListTruncatesWithATitle`;
  - `unconfirmedOmitsAnAppliedEdit`;
  - `anEmptyCaptureNeverOverwritesADeliveredPayload`.

  Run red: `npx vitest run src/collab`.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green: `npx vitest run src/collab && npx eslint src/collab`.
- [ ] **Step 4:** Commit `feat(collab): draft state, draft banner, publish dialog and contributor list`.

### Task 10: History UI shows contributors (frontend)

**Files:**
- Modify: `wikantik-frontend/src/components/ChangeNotesPanel.jsx`, `wikantik-frontend/src/components/DiffViewer.jsx`
- Test: `ChangeNotesPanel.test.jsx`, `DiffViewer.test.jsx`

**Interfaces:**
- Consumes: `api.getHistory( name )` (`api/client.js:323`), whose versions now carry `contributors: [displayName]` (Task 8); `ContributorList` from Task 9 (or a local copy if Task 9 has not landed, deduplicated in Task 11).
- Produces:
  - `ChangeNotesPanel` shows "{author} with {ContributorList of the others}" when contributors other than the author exist.
  - `DiffViewer`'s version options read `v8 — Ana +2 (note)`, with the full list in the option's `title`.
  - A missing or empty `contributors` renders exactly as today.

- [ ] **Step 1:** Write the tests: `aVersionWithContributorsNamesThem`, `theAuthorIsNotRepeated`, `noContributorsRendersAsToday`, `diffViewerOptionsCountContributors`. Run red.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run green: `npx vitest run src/components/ChangeNotesPanel.test.jsx src/components/DiffViewer.test.jsx && npx eslint src/components`.
- [ ] **Step 4:** Commit `feat(history): show the contributors of each page version`.

### Task 11: Editor integration — banner, publish dialog, notices, convert-and-publish (frontend)

**Files:**
- Modify: `wikantik-frontend/src/components/PageEditor.jsx`, `src/styles/globals.css`
- Test: `PageEditor.collab.test.jsx`; every existing `PageEditor*.test.jsx` stays green

**Interfaces:**
- Consumes: Task 9's `draft`, `published`, `notice`, `oversizeLimit`, `draftSummary`, `unsyncedParticipants`, `publish(…, { markupSyntax })`, `DraftBanner` and `PublishDialog`; the existing `discardSharedDraft`, `confirmDiscard`, `offlineDraftOffer`, `queueCollabWrite`/`applyPendingWrites` and `handleConvert` (`PageEditor.jsx:585-640, 1009-1029, 1267-1290`).
- Produces this behaviour:
  - **Draft banner on open** (D3, §8.3). Shown when the join reported unpublished changes. Its actions are Publish (opens the dialog), Discard (the existing confirm), View changes (dialog in `view` mode) and Dismiss. While `draft.unpublished`, the toolbar shows a compact "Unpublished draft" chip (`data-testid="collab-draft-chip"`).
  - **Publish goes through the dialog** (§8.3, §16 decision 1). Clicking Publish (`editor-save`) or pressing Ctrl+S calls `draftSummary()` and then opens the dialog:
    - the change note moves into the dialog, prefilled from the toolbar field;
    - `newerEdits` = `getSyncedVersion` at confirm time minus `summary.version`;
    - confirm (`data-testid="publish-dialog-confirm"`) calls `publish`; the existing 422 and retry handling is unchanged.

    The frozen "Recreate page" path keeps its existing confirm and does not use the dialog.
  - **Notices:**
    - `published` by someone else → toast "Published by Ana as version 8", and `originalVersion` updates.
    - `publish-failed` → toast "Ana's publish was refused".
    - `draft-discarded` → toast "Ana discarded the shared draft".
    - `oversize` → a warning banner "This draft is over the page size limit ({bytes} of {limit}); trim it to publish" (`data-testid="collab-oversize"`). It stays while the local doc's UTF-8 size exceeds the limit (§7.4, E25).
    - `evicted` with detail `purged` → "An administrator removed this draft", with unsynced text in the recovery panel.
  - **Leaving** (§8.2). Cancel or navigating away with synced, unpublished work shows a non-blocking toast "Your changes stay in the shared draft until someone publishes or discards them". The guard still blocks only for unsynced typing.
  - **Offline-draft import** (the §15 row item). Finish the Phase 1 flow:
    - the offer is made once per stored draft;
    - Dismiss asks, then clears the local copy;
    - restore and copy both clear it;
    - with others present, it stays copy-only (P10).
  - **Convert and publish** (Decision 8).
    - Remove `collab-no-convert` (`PageEditor.jsx:1424`).
    - In collab mode the button reads "Convert and publish". It confirms with "This converts and publishes the page for all {N} editors".
    - It applies the converted text through `replaceBody` as one transaction (E7), then publishes with `markupSyntax: 'markdown'` and change note "Converted to Markdown".
    - On a `published` event from someone else, the editor refetches the page's `markupSyntax` (`api.getPage`).
    - Every collab publish sends `markupSyntax` exactly as the single-author `PUT` does (`PageEditor.jsx:1122`).
  - **Parked UX minors:**
    - The recreate recovery panel stays until dismissed, even when the status leaves `frozen` because someone else recreated the page (Phase 2 Task 11 nit).
    - A queued insert whose 40-character anchor is missing, and whose 12-character fallback matches more than once, is not guessed. Its text goes to the recovery panel with a notice (Phase 1 Task 9 parked (b)).

- [ ] **Step 1:** Write the tests:
  - `theDraftBannerShowsOnOpenWithContributorsAndAge`;
  - `theDraftChipFollowsUnpublished`;
  - `publishOpensTheDialogWithTheDiff`;
  - `ctrlSOpensTheDialog`;
  - `confirmPublishesWithTheDialogsChangeNote`;
  - `theDialogWarnsAboutStillTypingParticipants`;
  - `newerEditsAreCountedAtConfirm`;
  - `someoneElsesPublishShowsAToastAndUpdatesTheVersion`;
  - `publishFailedAndDiscardedNotices`;
  - `oversizeBannerFollowsTheLocalSize`;
  - `aPurgeKeepsUnsyncedTextInTheRecoveryPanel`;
  - `leavingWithSyncedWorkShowsANonBlockingNote`;
  - `theOfflineDraftIsOfferedOnceAndClearedOnDecision`;
  - `convertAndPublishConfirmsAppliesOneTransactionAndPublishesMarkdown`;
  - `anotherEditorsConvertRefreshesMarkupSyntax`;
  - `collabPublishSendsMarkupSyntaxLikeThePut`;
  - `theRecreatePanelSurvivesAnUnfreeze`;
  - `anAmbiguousAnchorIsNotGuessed`.

  Run red: `npx vitest run src/components/PageEditor.collab.test.jsx`.
- [ ] **Step 2:** Implement. Keep the new UI in the Task 9 components; `PageEditor` only wires them.
- [ ] **Step 3:** Run green: `npx vitest run src/components src/collab`, then `npx eslint .`. Re-run a timing-flaky test alone before treating it as a failure. If `attachment rename while out of sync` (the Phase 2 Task 9 watch item) flakes, fix it here.
- [ ] **Step 4:** Commit `feat(collab): draft banner, publish dialog, draft notices and convert-and-publish in the editor`.

### Task 12: Collab-ready editor Selenide suite (ruling P12)

**Files:**
- Modify: `bin/run-tests.sh` (`--collab`); `wikantik-it-tests/pom.xml` (property `it.collab.jvmargs`, empty by default, appended to `cargo.jvmargs` near `:450`; a failsafe system property `it.collab`)
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/pages/spa/EditWikiPage.java`, `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RestSeedHelper.java`
- Create in `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/`: `CollabMode.java`, `CollabDraftCleanupExtension.java`, `CollabModeGuardTest.java` (a plain unit test of the guard logic)
- Modify: `BlockRefsIT.java`, `EditIT.java`, `LivePreviewIT.java`, `LivePreviewKeyboardIT.java`, `LivePreviewRoundTripIT.java`, `PasteAsMarkdownIT.java`, `SearchIT.java`, `StructuredFrontmatterEditorIT.java`, `WithIntegrationTestSetup.java` (register the extension)

**Interfaces:**
- Consumes:
  - Task 7's `/admin/collab/drafts`;
  - Task 11's `collab-status[data-status]`, `editor-save`, `publish-dialog-confirm` and `collab-draft-chip`;
  - P12's list of failing ITs;
  - `RestSeedHelper.awaitAdminReady`.
- Produces:
  - `bin/run-tests.sh --collab` sets `-Dit.collab.jvmargs="-Dwikantik.collab.enabled=true -Dwikantik.collab.poll.timeoutSeconds=4"` and `-Dit.collab=true` for the IT reactor. `--all` adds a collab-mode `custom-jdbc` pass (Decision 9). `usage` documents both.
  - `CollabMode.enabled()` reads the system property `it.collab`.
  - `CollabMode.awaitEditorReady()`: in collab mode it waits for `[data-testid=collab-status][data-status=live]` (10 s) and fails with "editor fell back to single-author mode" if `collab-status` is absent; in single-author mode it returns at once.
  - `EditWikiPage.saveText(…)`: in collab mode it clicks `editor-save`, then `publish-dialog-confirm`, then awaits the page view. `cancel()` leaves the draft to the cleanup extension. Every entry point to the editor calls `awaitEditorReady()` before typing.
  - `RestSeedHelper.purgeAllDrafts()`: `GET`, then `DELETE` for each `/admin/collab/drafts` entry.
  - `CollabDraftCleanupExtension` (`AfterEachCallback`) purges every draft after each test in collab mode, so a cancelled edit's draft never leaks into the next test that opens the same page.

- [ ] **Step 1:** Write `CollabModeGuardTest`:
  - `aFallbackToTheSingleAuthorEditorFailsTheTest` (**Review Focus #5**: the guard logic over a stubbed DOM probe);
  - `singleAuthorModeDoesNotWait`.

  Run `bin/run-tests.sh --module custom-jdbc --collab` through `bin/agent-build.sh`. Record each failing IT method with its cause class: harness (timing, labels, dialog, draft leak) or product defect.
- [ ] **Step 2:** Fix every product defect test-first at the unit level where it lives (vitest or Java), then adapt the harness. Do not weaken an assertion to pass. A behaviour that differs by design in collab mode gets a mode branch that asserts the collab behaviour (for example, the publish dialog appears).
- [ ] **Step 3:** Run green in both modes: `bin/run-tests.sh --module custom-jdbc --collab` and `bin/run-tests.sh --module custom-jdbc`. Re-run a known flake (memory: EditIT CodeMirror flake) alone before calling it a failure.
- [ ] **Step 4:** Commit `test(it): the editor Selenide suite runs in collaborative mode (--collab)`.

### Task 13: Phase 3 ITs and the collab load scenario (worktree)

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/CollabRestIT.java`
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/CollabDraftUxIT.java` (two headless browsers, in the module that already enables collab, per P12)
- Create: `loadtest/collab-load.js`; modify `loadtest/README.md`

**Tests:**
- **`CollabRestIT`**, through real Tomcat:
  - A and B both edit, A publishes; `/api/history` lists both as contributors at the new version, by display name.
  - B's edit during A's publish is credited to the next version.
  - `GET /draft` returns the HTML diff and `metaChanges`.
  - `/admin/audit` holds `collab.join`, `collab.publish` (with both contributor keys) and `collab.discard`.
  - The metrics scrape (`/observability/metrics`; check the path the `MeterRegistryHolder` warning in `WikiEngine` names) shows `wikantik_collab_push_total` and `wikantik_collab_sessions_active`.
  - `draft_active` is true on `/api/pages/{name}` and `/api/pages/for-agent/{id}` while A's draft has unpublished changes, and false after publish.
  - `DELETE /admin/collab/drafts/{id}` purges; B's next push is `403 evicted purged`.
- **`CollabDraftUxIT`**, two browsers (`awaitAdminReady` after login):
  - A types and leaves without publishing. B opens the editor and sees the draft banner naming A.
  - B publishes through the dialog, which shows the diff and A as a contributor.
  - The history panel lists A and B.
  - A's open editor shows "Published by B".
- **Load (§13.4):** `collab-load.js` drives N simulated editors × M pages (join, push at typing cadence, long-poll, an occasional publish) and reports push latency p95 and the 409/429 rates. The default run is 20 editors × 5 pages against local Tomcat.

- [ ] **Step 1:** Write the ITs. Run red: `bin/run-tests.sh --module rest` through `bin/agent-build.sh`.
- [ ] **Step 2:** Fix what fails, test-first at the unit level where the defect lives.
- [ ] **Step 3:** Run green. Run the load scenario against a local deploy with collab enabled, and record p95 (target < 150 ms server-side at 20 editors) and the rates in `loadtest/README.md`. A miss is reported to the controller, not tuned silently.
- [ ] **Step 4:** Commit `test(collab): Phase 3 REST and two-browser ITs; collab load scenario`.

### Task 14: Gate, soak, docs and the default-on checklist

**Files:**
- Modify:
  - `docs/MultiEdit.MD`: the §15 Phase 3 row's Ships cell reads "Default-on ready; kill switch still off (Decision 1)"; add a "Phase 3 outcome" paragraph like Phase 0's, the default-on checklist and Decisions 1–9; record Decision 4's residual risk in §10;
  - `docs/developer/ProjectReference.md` (the MultiEdit block);
  - `CLAUDE.md` (surface counts);
  - `CHANGELOG.md` `[Unreleased]` → `### Added`, marked dark;
  - `docs/admin/WikantikOperations.md` (retention, purge, metrics, audit event types, the load scenario);
  - `docs/admin/McpAgents.md` (`draft_active` on `get_page_for_agent`);
  - `docs/user/Editing.md` (an "Editing together" section linking the new page).
- Create:
  - `docs/user/CollaborativeEditing.md`, the full user guide;
  - `docs/wikantik-pages/CollaborativeEditing.md`, the user-facing wiki page: `cluster: wikantik-development` like `EditPageHelp.md`, and a fresh `canonical_id` in the corpus's 26-character ULID form. Link it from `docs/wikantik-pages/EditPageHelp.md`.

- [ ] **Step 1:** Check the close-outs. Confirm the Phase 1 close-out (Phase 1 Task 11) and the Phase 2 gate ran; run whichever did not. Confirm the Phase 1 Task 2 close-out minor is already settled: `CollabConfig` bounds `poll.timeoutSeconds` (≤ 60 and < `presence.timeoutSeconds`) and `maxUpdateBytes`, and warns on unparseable values (`CollabConfig.java:63-127`). Point to the `CollabConfigTest` case that pins each; add a test for any bound that lacks one.
- [ ] **Step 2:** Run the full gate through `bin/agent-build.sh`: `bin/run-tests.sh --parallel 4` to green. Then run `bin/run-tests.sh --module custom-jdbc --collab` to green. Then run the soaks on the collab tests: `-Dcollab.fuzz.seeds=50000` and `-Dcollab.stress.runs=5000`. Fix any red gate in this session; "pre-existing" is not an excuse.
- [ ] **Step 3:** Run telemetry conformance against a local deploy with collab enabled: `bin/simple-agility-conformance.sh --base-url http://localhost:8080 --prefix wikantik`. Confirm the new meters appear and the conformance run stays green.
- [ ] **Step 4:** Write the docs.
  - `docs/user/CollaborativeEditing.md` and the wiki page cover: joining, presence, the shared draft and its banner, publish through the dialog, contributors in history, discard, offline drafts, conflicts with outside writes, rename and delete, eviction, and convert-and-publish. They use display-name wording only.
  - **CLAUDE.md counts.** Re-derive from `web.xml` (memory: doc-drift verifier). The `/admin/*` row gains `AdminCollabResource` (`/admin/collab/*`: draft list + purge), and the distinct-servlet and url-pattern counts change accordingly. The `/api/*` row's collab entry adds `draft`. The knowledge-mcp row notes `draft_active`. No MCP tool count changes.
  - Regenerate nothing by hand. If a config reference changed, run `bin/config-reference.sh --write`; it should not have.
- [ ] **Step 5:** Write the default-on checklist into §15. Each item must be ticked before the flip, which is a separate one-line change:
  - the collab-mode Selenide run is green in `--all` (P12);
  - an admin-group or staging soak of at least a week, with the collab metrics in jakemon (push p95, rebase and reject rates, resyncs) and audit entries reviewed;
  - Cloudflare bot and WAF rate rules checked for `/api/collab/*` (§0 Minor);
  - `WIKANTIK_RATELIMIT_SESSION_PERCLIENT` sized from the load figure;
  - `draft.retentionDays` chosen for prod;
  - the per-group gate decided (built, or explicitly declined).
- [ ] **Step 6:** Commit `docs(MultiEdit): Phase 3 complete — draft UX, attribution, default-on checklist`.

## Out of scope (recorded so nothing is silently dropped)

- **The default flip itself** (Decision 1); Task 14's checklist gates it.
- **Phase 4 (§15):** the reader "being edited" badge, the WebSocket transport, the admin drafts **view** at `/admin/collab` (Phase 3 ships the REST list and purge), multi-node operation, watch-only participants.
- **Per-group enablement** (`wikantik.collab.enabled` per group, an optional Phase 2 follow-up): needs a config key; decided at the default-on checklist.
- **`docHash` divergence detection** (§14; protocol note `docHash`): still deferred and optional.
- **Keystroke-level history or replay:** not a product feature (§16 decision 4).
- **Accepted and not revisited:**
  - a resend after a lost ack can re-add a tag another editor removed (Phase 2 Task 10, last-writer-wins by value);
  - `observe()` before the lock in `CollabPull.complete()` (benign race);
  - the 30 s latch on a container thread while a response write is in flight (Phase 1 Task 7).
