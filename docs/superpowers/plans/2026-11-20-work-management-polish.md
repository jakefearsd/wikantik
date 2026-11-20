# Work Management Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make work management solid and easy to pick up before the owner's hands-on testing: squash merges count as reviewed, board order survives ties, product setting changes are recorded, a user guide exists, and a newcomer's pass fixes what it finds.

**Architecture:** Three small backend changes in `wikantik-work` (each through `WorkService`, tests against `PostgresTestDb`), their REST/MCP/UI edges, one migration (V083), then a user guide and a browser walkthrough of `/work` on the local deployment.

**Tech Stack:** Java 25, Maven, PostgreSQL (pgvector test container), React 19 + vitest.

**Spec:** `docs/superpowers/specs/2026-11-20-work-management-polish-design.md`

## Global Constraints

- TDD: every behaviour change starts with a test seen failing. Never an empty catch block; log `LOG.warn` with context.
- Read `wikantik-work/CLAUDE.md` first. Every caller goes through `WorkService`; non-members get 404, never 403; nothing is hard-deleted; no project/product/item labels on metrics.
- A new public `WorkService` method must be classified in `WorkServiceVisibilityPostgresTest`.
- Migrations: DDL only, idempotent (`IF NOT EXISTS`), `:app_user` grants, a no-op when re-applied; see `bin/db/migrations/README.md`. No data backfill.
- Database tests: `@RequiresPostgres` + `PostgresTestDb`; truncate in `@BeforeEach`; a multi-statement write gets a rollback test (`FaultInjectingDataSource`).
- Module-scoped test runs while iterating (`mvn -o -q -pl wikantik-work test -Dtest=…`; `npx vitest run <files>` in `wikantik-frontend`). Parallel implementers: no git state changes, no `mvn install`; the controller commits.
- Commit messages: 1–3 lines, ending with the session's attribution lines.

## Review Focus

1. **A squash merge after a post-approval push.** An agent is refused (`not_approved`); a person's merge is recorded `unreviewed`. The relaxation must not reach this case. → Task 1, tests 3–4.
2. **A drop at the edge of a tied column** (before the first card; at the end with no `before`). The order asked for must hold after reload. → Task 2, tests 2–3.
3. **Two people reordering one column at once.** Renumbering touches other cards' ranks but bumps no version, so the other person's unrelated move is not refused as stale. → Task 2, test 4.
4. **A product update that changes nothing, or fails half-way, records nothing.** → Task 3, tests 2 and 4.
5. **A `before` key that names a card in another column or project.** Refused as a validation error on `before`, never silently placed elsewhere and never revealing another project's item. → Task 2, test 5.

---

### Task 1: Squash and merge commits count as reviewed

**Files:**
- Modify: `wikantik-work/src/main/java/com/wikantik/work/WorkChangesService.java:145-163` (`merge`)
- Modify: `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/MergeChangeTool.java` (description strings, lines ~34, 62, 66–69)
- Modify: `wikantik-work-mcp/src/main/resources/wikantik-work-mcp-instructions.txt:40-41,78`
- Modify: `docs/admin/WikantikOperations.md:561-562`
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkChangeGatesPostgresTest.java`

**Interfaces:**
- Produces: `merge` records `MergeKind.REVIEWED` iff `locked.approvedForHead()`; the reported sha is stored as `merged_sha` unchanged. No signature changes.

- [ ] **Step 1: Write the failing tests** in `WorkChangeGatesPostgresTest`, using the class's existing fixtures (open → push head → approve as agent B → merge):
  1. `anAgentSquashMergesItsApprovedChangeAsReviewed` — approving agent merges with `mergedSha` ≠ head sha → no exception; `mergeKind() == REVIEWED`; `mergedSha()` equals the reported sha.
  2. `aPersonsSquashMergeAfterApprovalIsReviewed` — a person merges with a new sha after approval → `REVIEWED`.
  3. Keep `aNewCommitAfterApprovalBlocksTheMerge` as is (agent refused after a post-approval push), and add the squash variant `aSquashAfterAPostApprovalPushIsStillRefusedForAnAgent` — new head pushed after approval, agent merges with a third sha → `WorkConflictException` reason `NOT_APPROVED`.
  4. `aPersonsMergeAfterAPostApprovalPushIsUnreviewed` — same set-up, a person merges → `UNREVIEWED`.
- [ ] **Step 2: Run** `mvn -o -q -pl wikantik-work test -Dtest=WorkChangeGatesPostgresTest`. Expected: tests 1 and 2 FAIL (agent refused / `UNREVIEWED`); 3 and 4 pass already (they pin the anti-goal).
- [ ] **Step 3: Implement.** In `merge`, `reviewed = locked.approvedForHead()` (drop the `sha.equals( locked.headSha() )` term). Update the method Javadoc: an agent must be the one whose approval stands on the change's current commit; the commit it reports is the one the merge produced (a squash or merge commit is fine).
- [ ] **Step 4: Update wording.** `MergeChangeTool`: class Javadoc; `merged_sha` property → "The commit the merge produced on the code host (a squash or merge commit is fine)."; drop "and the commit must be the approved one"; `not_approved:` → "(no approval stands on the change's current commit)". Instructions file lines 40–41: "Only the agent whose approval stands on the current commit may; the merged commit may be a squash or merge commit." Operations guide line 561: "…A new commit clears it; an agent may merge only while its approval stands on the current commit, and the merge may produce a squash or merge commit." Update any test in `wikantik-work-mcp` that pinned the old sha-equality refusal (grep `not_approved`, `merged_sha`).
- [ ] **Step 5: Run** `mvn -o -q -pl wikantik-work test -Dtest='WorkChange*'` and `mvn -o -q -pl wikantik-work-mcp test`. Expected: PASS.
- [ ] **Step 6: Commit** `fix(work): a squash or merge commit counts as reviewed while the approval stands on the change's head`.

---

### Task 2: Board order survives tied ranks

**Files:**
- Modify: `wikantik-work/src/main/java/com/wikantik/work/ItemPatchApplier.java` (`KEYS`, `MOVE_KEYS` gain `"before"`; `apply` ignores it for the merged item)
- Modify: `wikantik-work/src/main/java/com/wikantik/work/WorkService.java:319-345` (`updateItem`)
- Modify: `wikantik-work/src/main/java/com/wikantik/work/WorkStore.java` + `JdbcWorkItemWrites.java` (new method)
- Modify: `wikantik-frontend/src/components/work/WorkBoard.jsx:149-185` (`move`)
- Test: create `wikantik-work/src/test/java/com/wikantik/work/WorkBoardOrderPostgresTest.java`; modify `wikantik-frontend/src/components/work/WorkBoard.test.jsx`

**Interfaces:**
- Produces (store): `boolean renumberColumn( long projectId, WorkStatus status, long movedItemId, Long beforeItemId ) throws SQLException` on `WorkStore`, in its own transaction: locks the column's rows (`SELECT id, rank … WHERE project_id = ? AND status = ? ORDER BY rank, id FOR UPDATE`), builds the wanted order — the other cards in `(rank, id)` order with the moved card inserted immediately before `beforeItemId`, or, when it is null, after every card whose rank ≤ the moved card's rank. If the moved card's rank is unique in the column and strictly between its wanted neighbours, it changes nothing and returns `false`; otherwise it sets `rank = (i + 1) * 1024.0` for every card in wanted order (rank only: **no version bump, no event** — rank is presentation order and is not an event field today) and returns `true`.
- Produces (PATCH): optional change key `before` (string, an item key in the same project). A patch of only `status`/`rank`/`before` is still a move (`Action.MOVE`). Old clients and agents that never send `before` behave as today, except that a rank colliding with another card triggers the renumber.
- Produces (client contract): when the server renumbered, the returned `item.rank` differs from the rank the client sent; the board then reloads.

- [ ] **Step 1: Write the failing Postgres tests** in `WorkBoardOrderPostgresTest` (drive `WorkService.updateItem` as a project member; build a column of cards A, B, C with A and B at the same rank via the store):
  1. `aMoveBetweenTwoTiedCardsKeepsTheAskedOrderAfterReload` — move C with `rank = A.rank` (the midpoint of a tie) and `before = B` → board query order is A, C, B.
  2. `aDropBeforeTheFirstTiedCardHolds` — column A, B tied; move C with `before = A` → C, A, B.
  3. `aDropAtTheEndOfATiedColumnHolds` — move A with no `before` and `rank = B.rank` → B, …, A order holds and every rank in the column is distinct.
  4. `renumberingBumpsNoOtherVersionAndWritesNoEvent` — after a renumbering move, the other cards' `version` is unchanged and `work_event` gained only the moved card's events; a second member's move of B with B's old `expectedVersion` succeeds.
  5. `aBeforeKeyOutsideTheTargetColumnIsRefused` — `before` naming a card in another column → `WorkValidationException` on field `before`; naming an item of another project (or a nonexistent key) → the same validation error (no 404/403 that reveals it).
  6. `aMoveWithRoomBetweenNeighboursRenumbersNothing` — distinct ranks, a midpoint move → other cards' ranks unchanged.
- [ ] **Step 2: Run** `mvn -o -q -pl wikantik-work test -Dtest=WorkBoardOrderPostgresTest`. Expected: FAIL (`Unknown field: before`).
- [ ] **Step 3: Implement.** `ItemPatchApplier`: accept `before` (validated as a non-blank string). `WorkService.updateItem`: resolve `before` to an item id in the same project whose status equals the moved card's target status, else `WorkValidationException( "before", "The card to place this before is not in that column." )`; after the item write, when the patch moved the card (`rank`, `status` or `before` present), call `store.renumberColumn(...)`; when it returns `true`, re-read the item so the returned view carries the new rank. Add `renumberColumn` to `WorkStore` and implement it in `JdbcWorkItemWrites` with `jdbc.inTransaction`.
- [ ] **Step 4: Run** the new test class, then `mvn -o -q -pl wikantik-work test`. Expected: PASS (incl. `WorkServiceVisibilityPostgresTest`, `WorkAccessArchTest`).
- [ ] **Step 5: Frontend failing tests** in `WorkBoard.test.jsx`:
  - `sends the key of the card it was dropped before` — drag a card to index 1 of a column → `patchItem` called with `changes.before === <key of card at index 1>`; dropping at the end sends no `before`.
  - `reloads the board when the server renumbered the column` — `patchItem` resolves with `item.rank` ≠ the sent rank → the board's load call fires again.
  - `does not reload when the server kept the rank` — equal rank → no reload.
- [ ] **Step 6: Run** `npx vitest run src/components/work/WorkBoard` → FAIL; **implement** in `move`: add `before: others[at]?.key` to `changes` when `at < others.length`; in the success handler, if `rank !== undefined && view.item.rank !== rank`, call `load()` after updating the card. **Run** again → PASS; then `npx vitest run src/components/work` and `npx eslint src/components/work`.
- [ ] **Step 7: Commit** `fix(work): the server renumbers a crowded board column so a dropped card keeps its place`.

---

### Task 3: Product setting changes are recorded

**Files:**
- Create: `bin/db/migrations/V083__work_product_events.sql`
- Create: `wikantik-work/src/main/java/com/wikantik/work/ProductEvent.java`
- Modify: `wikantik-work/src/main/java/com/wikantik/work/WorkCatalogStore.java`, `JdbcWorkCatalog.java:342-361`, `WorkService.java:264-275`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/WorkProductResource.java` (new route)
- Modify: `wikantik-frontend/src/api/work.js`, `wikantik-frontend/src/components/work/WorkProducts.jsx`
- Modify: `wikantik-work/CLAUDE.md` (the "Product changes are not recorded" sentence)
- Test: create `wikantik-work/src/test/java/com/wikantik/work/WorkProductHistoryPostgresTest.java`; modify `JdbcWorkStoreCatalogRollbackTest.java`, `WorkServiceVisibilityPostgresTest.java`, `wikantik-rest/src/test/java/com/wikantik/rest/WorkProductResourceTest.java`, `wikantik-frontend/src/components/work/WorkProducts.test.jsx`; the schema test pattern of `WorkDeploymentsSchemaTest`

**Interfaces:**
- Produces (schema): `work_product_event( id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, product_id BIGINT NOT NULL REFERENCES work_product (id), at TIMESTAMPTZ NOT NULL DEFAULT NOW(), actor TEXT NOT NULL, actor_kind TEXT NOT NULL DEFAULT 'user', agent_id BIGINT, field TEXT NOT NULL, from_value TEXT, to_value TEXT )`, index `(product_id, at DESC, id DESC)`, grants to `:app_user` like V082.
- Produces (record): `public record ProductEvent( long id, Instant at, String actor, Actor.Kind actorKind, Long agentId, String field, String fromValue, String toValue )`.
- Produces (store, replacing the old signatures): `void updateProduct( Product before, Product after, Actor by )`, `void archiveProduct( Product product, Instant at, Actor by )`, `List< ProductEvent > productEvents( long productId, int limit )` (newest first, ties by id).
- Produces (service): `List< ProductEvent > productHistory( Actor actor, String key )` — `MANAGE_INSTANCE`, unknown key → `WorkNotFoundException`; limit 100.
- Produces (REST): `GET /api/work/products/{key}/history` → `{ "events": [ { "at", "actor", "field", "from", "to" } ] }`, names resolved the way `WorkActivityResource` resolves actors.
- Field wire names (exact): `name`, `repo_url`, `default_branch`, `wiki_cluster`, `autonomy_ceiling`, `deploy_gate`, `archived` (to_value = ISO instant).

- [ ] **Step 1: Migration + schema test.** Write the schema test first (table and index exist after `PostgresTestDb` applies migrations; re-applying V083 is a no-op), see it fail, write V083 with the repo's licence header and a comment block like V081/V082.
- [ ] **Step 2: Failing tests** in `WorkProductHistoryPostgresTest` (via `WorkService` as a wiki admin):
  1. `anUpdateRecordsOneRowPerChangedField` — change `deployGate` and `autonomyCeiling` → exactly two events, fields `deploy_gate` and `autonomy_ceiling`, from/to wire values, actor = the admin's login, kind `user`.
  2. `anUpdateThatChangesNothingRecordsNothing`.
  3. `archivingRecordsArchived` — one `archived` event, `to_value` = the instant.
  4. In `JdbcWorkStoreCatalogRollbackTest`: `aFailedProductUpdateRecordsNoEventAndChangesNothing` — fault on the event insert → product row unchanged, no event row.
  5. `onlyAnAdminReadsProductHistory` — a non-admin gets the same refusal `createProduct` gives a non-admin; classify `productHistory` as a read in `WorkServiceVisibilityPostgresTest`.
  6. `historyIsNewestFirst`.
- [ ] **Step 3: Run** → FAIL. **Implement:** `updateProduct` and `archiveProduct` write the row change and one `work_product_event` per changed field in one `jdbc.inTransaction`; `WorkService.updateProduct/archiveProduct` pass `existing`, the merged product and the actor; add `productEvents` and `productHistory`. Update `wikantik-work/CLAUDE.md`: product changes are recorded in `work_product_event`, one row per field. **Run** `mvn -o -q -pl wikantik-work test` → PASS.
- [ ] **Step 4: REST.** Failing tests in `WorkProductResourceTest`: `historyListsTheProductsEventsNewestFirst`, `historyOfAnUnknownProductIs404`, `historyForANonAdminIsRefused` (same status the PATCH route gives a non-admin). Implement the route; **run** `mvn -o -q -pl wikantik-rest test -Dtest=WorkProductResourceTest` → PASS.
- [ ] **Step 5: UI.** `api.work.productHistory( key )`. In `WorkProducts.jsx`, the product's edit form shows a "History" list, newest first: `<relative time> · <actor> changed <label> from <from> to <to>` (labels: Name, Repository, Default branch, Wiki cluster, Autonomy ceiling, Deploy gate; `archived` reads "archived the product"); empty → "No changes recorded yet." Failing tests first in `WorkProducts.test.jsx`: `shows the product's history when editing`, `says when no changes are recorded`, `a failed history load shows the load error, not an empty list`. **Run** `npx vitest run src/components/work/WorkProducts` → PASS; `npx eslint src/components/work`.
- [ ] **Step 6: Commit** `feat(work): product setting changes are recorded (V083) and shown as the product's history`.

---

### Task 4: User guide

**Files:**
- Create: `docs/user/WorkManagement.md`
- Modify: `wikantik-frontend/src/components/work/WorkHome.jsx` (help link), `docs/admin/WikantikOperations.md` (link from the work section)
- Test: `wikantik-frontend/src/components/work/WorkHome.test.jsx`

**Interfaces:**
- Consumes: Tasks 1–3's behaviour (squash merges reviewed, history list).

- [ ] **Step 1:** Read two existing guides (`docs/user/Editing.md`, `docs/user/CommentsAndMentions.md`) for voice and structure, and the work sections of the operations guide and the design spec for facts. Write the guide in the order the spec gives: turning it on (admin flag `wikantik.work.enabled`), a product, a project and members, items and the board (drag to reorder), epics and dates, a change (open → review → approve → merge; a squash merge is fine), a deployment (request → approve → run → verify), registering an agent and its `wka_` token, autonomy in one paragraph, the "Done without you" lane. Every step ends with **You should see:** one line. Plain words; no internal class names.
- [ ] **Step 2:** Failing test `links to the work management guide` in `WorkHome.test.jsx` (a link whose href is `https://github.com/jakefearsd/wikantik/blob/main/docs/user/WorkManagement.md`, opening in a new tab); implement a small "Guide" link in the Work home header; run `npx vitest run src/components/work/WorkHome` → PASS. Add the link to the operations guide's work section.
- [ ] **Step 3: Commit** `docs(work): a user guide for work management, linked from the Work home`.

---

### Task 5: Newcomer's pass over /work (controller, not a subagent)

**Files:** whatever the pass finds; findings list at `.superpowers/sdd/2026-11-20-work-management-polish/findings.md` (git-ignored).

- [ ] **Step 1:** Build and deploy locally: `mvn clean install -DskipTests -T 1C`, then `bin/redeploy.sh` (V083 applies). Confirm `curl -s localhost:8080/api/capabilities` shows `"work": true`.
- [ ] **Step 2:** Follow `docs/user/WorkManagement.md` step by step in a browser as the `testbot` admin and as a second, non-admin user, at desktop width and at 390 px. At each step note: an empty state that does not say what to do next, a dead end, confusing or internal wording, a layout break, a guide step that does not match the screen.
- [ ] **Step 3:** Fix each clear defect test-first (a vitest test for UI, a Postgres/REST test for server), one commit per fix or per closely related group. Correct the guide wherever it was wrong.
- [ ] **Step 4:** Write the judgment calls to the findings file and bring them to the owner as a short list.

---

### Final verification

- [ ] `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll `status` until it ends. Expected: `RESULT: ALL PASSED` (units, PMD ratchet, eslint, vitest, ITs).
- [ ] Final whole-branch review (opus) of the commits since `a950146a20`; fix what it confirms; rerun the gate if code changed.
- [ ] Push to `origin/main`. Release only on the owner's say-so.
