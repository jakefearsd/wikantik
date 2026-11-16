# Work Management Phase 3b — Changes and Autonomy — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An item's code changes are recorded from branch to merge, and one autonomy level per item decides which gates (approve as ready, review, merge, close) an agent may pass.

**Architecture:** `wikantik-work` gains a `work_change` table, a pure resolver that turns an item, its ancestors, its project and its product into an effective policy (`EffectivePolicy`), and a gate class (`WorkGates`) that every gated operation asks. Changes get their own store and service facade (`WorkService.changes()`), like claims did, so no existing class grows past the PMD limits. The policy columns that have been stored and inert since phase 1 become live. An item an agent closes at the `autonomous` level is flagged until a person acknowledges it.

**Tech Stack:** Java 25, PostgreSQL 18 (`Jdbc` primitive, `PostgresTestDb`), JUnit 5, ArchUnit, Mockito, React 19 + vitest, Cargo/Selenide ITs.

**Spec:** `docs/superpowers/specs/2026-11-15-work-management-design.md`, sections "Lifecycle", "Changes (phase 3)" and "Autonomy (phase 3)". Agent identity it builds on: `docs/superpowers/specs/2026-11-16-work-agent-identity-design.md`.

## Decisions this plan makes where the spec is silent

The owner has not ruled on these. Each is the plan's call and is listed in the handoff for the owner to overturn before execution.

- **D1. Acknowledgement moves from phase 4 into this phase.** The spec's schema puts `ack_required`, `acknowledged_by`, `acknowledged_at` and the "done without you" lane in phase 4. This phase makes `autonomous` take effect, so an agent could close work with no person in the path and nothing would flag it until phase 4. The columns, the acknowledge action and the lane are built here.
- **D2. "Approves as ready" is the move out of `draft` or `refining`** into any later status. A move between later statuses (a holder handing its claimed item to another queue as `ready`) is not an approval.
- **D3. "Closes" is the move to `done`.** `cancelled` is not gated by level; the existing draft rule for `cancelled` stays.
- **D4. "A different agent" means a different `work_agent` id from the change's opener,** at every level including `supervised`. Two agents with one sponsor are different agents (owner's ruling in phase 3a). A change a person opened may be reviewed by any agent.
- **D5. An approval is for one commit.** It records the `head_sha` it approved. A new head clears it, and a merge needs an approval for the current head.
- **D6. "The reviewer merges" binds agents only.** An agent may report a merge only if it is the agent whose approval stands on the current head. A person may merge any change, with or without an approval; the change records which (`reviewed`, `unreviewed`, `direct`).
- **D7. A change needs a product.** An item that targets no product cannot have a change.
- **D8. An agent's direct commit needs `allow_direct` and a level above `supervised`.** At `supervised` a person merges, so `allow_direct` gives an agent nothing there.
- **D9. Who may set the policy fields.** `autonomous` anywhere: a human lead (unchanged `SET_POLICY`). Any value that makes an item stricter than it would inherit: a human member. Any value that makes it looser, `allow_direct: true`, and `done_means`: a human lead. An agent never sets any of them (unchanged).
- **D10. The "done without you" lane is shown to the project's human leads and to wiki admins.** Any human member of the project may acknowledge.
- **D11. Agent change operations follow the existing claim fence** (refused when another agent holds the item's claim) and need the `member` role. They do not require the agent to hold the claim itself, matching item edits today.

## Global Constraints

- Root `CLAUDE.md`, `wikantik-work/CLAUDE.md`, `wikantik-work-mcp/CLAUDE.md` bind every task. TDD: a failing test first, the failing run captured.
- `wikantik-work` depends on neither `wikantik-api` nor `wikantik-main`. Every caller outside the module goes through `WorkService` and its facades (`WorkAccessArchTest`, `WorkMcpAccessArchTest`). A new facade accessor is added to `WorkServiceVisibilityPostgresTest.FACADES` and every new public method is classified there as a read or a write.
- Database access only through `com.wikantik.jdbc.Jdbc`. A multi-statement write gets a rollback test (`FaultInjectingDataSource`). Tests use `PostgresTestDb`; never hand-write DDL.
- Migrations are DDL-only, idempotent, use `:app_user` for grants; V074–V080 are applied in production and never edited. This plan's migration is **V081**.
- A non-member gets `WorkNotFoundException` (404), never 403. A change, its id and its count are as hidden as the item they belong to. This holds for an agent whose sponsor lacks standing.
- Role and visibility come only from `WorkStanding` (`WorkStandingSourceRuleTest`). The effective autonomy level comes only from `WorkAutonomy.resolve`; no other class reads `autonomy`, `allow_direct`, `done_means`, `default_autonomy` or `autonomy_ceiling` to make a decision.
- A person is never refused at a gate (approve, review, merge, close). Gates restrict agents only. The one rule that binds everyone: an item cannot be `done` while it has a change that is `open` or `in_review`.
- An agent never merges a change it opened, at any level, and never sets a policy field. Neither is configurable.
- Every state change writes its `work_event` in the same transaction; `work_event` is append-only. An agent's write records its id (`agent_id`), never only its name.
- A failed directory or store lookup is `WorkUnavailableException` (REST 503, MCP `Work is unavailable right now.`); it is never read as "allowed" or "refused" and changes nothing.
- No project, product or item labels on metrics. No swallowed exceptions. Log ids and counts, never tokens, row text or caller-supplied text. No PMD suppressions: split a class that crosses a limit.
- Sprints must stay addable; nothing here may assume continuous flow.
- Exact values (use verbatim):
  - Change states: `open`, `in_review`, `merged`, `abandoned`. Merge kinds: `reviewed`, `unreviewed`, `direct`.
  - Level order, strictest first: `supervised` < `delegated` < `autonomous`.
  - Event types: `change_opened`, `change_updated`, `review_requested`, `change_reviewed`, `change_merged`, `change_abandoned`, `acknowledged`.
  - Branch name hint shown to callers: `work/<ITEM-KEY>-<slug>` (a hint, never enforced).
  - Refusals (`WorkDeniedException` unless marked):
    - `A person approves work as ready at this autonomy level.`
    - `A person closes work at the supervised level.`
    - `An agent may not review its own change.`
    - `An agent may not merge its own change.`
    - `A person merges at the supervised level.`
    - `The agent that approved this change merges it.`
    - `Direct commits by an agent need a lead to allow them on this item or its epic.`
    - `Making this looser than it inherits takes a project lead.`
    - Validation, field `productKey`: `Set a product on this item before opening a change.`
    - Conflict, reason `open_changes`: `Close or merge this item's open changes first.`
    - Conflict, reason `not_approved`: `This change has no approval for its current commit.`
    - Conflict, reason `change_closed`: `This change is already merged or abandoned.`
- Commits: stage files by name, 1–3 line messages. Never push. Long Maven runs through `bin/agent-build.sh`, one Maven build at a time. Before running an IT module alone: `mvn install -DskipTests -T 1C`.

## Review Focus

1. **A new commit after approval.** Agent B approves head `aaa`; agent A pushes `bbb`; B (or anyone) reports a merge of `bbb`. Expected: `not_approved`, nothing merged. Test in Task 5.
2. **The level drops while a change is in flight.** A lead lowers the epic from `delegated` to `supervised` (or an admin lowers the product ceiling) after an agent's approval. Expected: the agent's merge and close are refused on its next call; nothing already merged is undone. Tests in Tasks 4 and 5.
3. **Two reports of the same merge at once.** Two callers merge one change concurrently. Expected: one `change_merged` event, the second caller gets `change_closed`. Test in Task 5 with the `CommitGate` pattern.
4. **A change on an item the caller cannot see.** A non-member, and a member agent whose sponsor lost standing, call every change read and write by item key and by change id. Expected: `Not found`, identical to an id that does not exist. Tests in Tasks 5, 7 and 8.
5. **An item moved to another parent or product with a change open.** Re-parenting under an epic with a different level, or changing the product (so the ceiling changes). Expected: the effective level is re-resolved on every gate call; the product of an existing change does not change; a product change is refused while a change is `open` or `in_review`. Tests in Tasks 2 and 5.

---

### Task 1: Schema V081 — changes and acknowledgement

**Files:**
- Create: `bin/db/migrations/V081__work_changes_and_acknowledgement.sql`
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkChangesSchemaTest.java` (new; follow `WorkAgentsSchemaTest`)

**Interfaces:**
- Produces:
  - `work_change (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, item_id BIGINT NOT NULL REFERENCES work_item (id), product_id BIGINT NOT NULL REFERENCES work_product (id), branch TEXT NOT NULL, base_branch TEXT NOT NULL, head_sha TEXT, review_url TEXT, state TEXT NOT NULL DEFAULT 'open' CHECK (state IN ('open','in_review','merged','abandoned')), merged_sha TEXT, merge_kind TEXT CHECK (merge_kind IN ('reviewed','unreviewed','direct')), opened_by TEXT NOT NULL, opened_by_agent_id BIGINT REFERENCES work_agent (id), reviewed_by TEXT, reviewed_by_agent_id BIGINT REFERENCES work_agent (id), reviewed_head_sha TEXT, reviewed_at TIMESTAMP WITH TIME ZONE, merged_by TEXT, merged_by_agent_id BIGINT REFERENCES work_agent (id), opened_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(), closed_at TIMESTAMP WITH TIME ZONE)`.
  - `CHECK ((state = 'merged') = (merge_kind IS NOT NULL))`.
  - Unique index `idx_work_change_live_branch ON work_change (item_id, branch) WHERE state IN ('open','in_review')`; index `idx_work_change_item ON work_change (item_id)`.
  - `work_item` gains `ack_required BOOLEAN NOT NULL DEFAULT FALSE`, `acknowledged_by TEXT`, `acknowledged_at TIMESTAMP WITH TIME ZONE`; partial index `idx_work_item_ack ON work_item (project_id) WHERE ack_required AND acknowledged_at IS NULL`.
  - Grants: `SELECT, INSERT, UPDATE ON work_change`.

- [ ] **Step 1: Failing schema tests:** `aSecondLiveChangeOnOneBranchIsRefused` (SQLState `23505`), `aMergedChangeDoesNotBlockTheBranch`, `aMergedChangeNeedsAMergeKind` and `anOpenChangeMayNotCarryOne` (SQLState `23514`), `anUnknownStateIsRefused`, `ackColumnsDefaultToNotRequired`, `theAppRoleCannotDeleteAChange` (least-privilege role helper used by the existing schema tests).
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest=WorkChangesSchemaTest`. Expected: FAIL, relation `work_change` does not exist.
- [ ] **Step 3:** Write the migration (`CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`).
- [ ] **Step 4:** Re-run the test: PASS. Apply the migration file twice with `psql` against the test container (the README's no-op check): no error on the second run.
- [ ] **Step 5: Commit** `feat(work): V081 change records and acknowledgement columns`.

---

### Task 2: The effective policy — one resolver

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/EffectivePolicy.java`, `WorkAutonomy.java`
- Modify: `WorkStore.java`, `JdbcWorkStore.java` (one read), `Autonomy.java` (ordering helper)
- Test: `WorkAutonomyTest.java` (pure), `WorkAutonomyPostgresTest.java`, `WorkAutonomySourceRuleTest.java`

**Interfaces:**
- Produces:
  - `public record EffectivePolicy( Autonomy level, boolean allowDirect, DoneMeans doneMeans )`.
  - `Autonomy.strictest( Autonomy a, Autonomy b )` and `boolean looserThan( Autonomy other )`.
  - `record PolicyChain( Item item, List< Item > ancestors, Project project, Product productOrNull )` (ancestors nearest first).
  - `static EffectivePolicy WorkAutonomy.resolve( PolicyChain chain )`: `level` = the item's own `autonomy`, else the nearest ancestor's, else `project.defaultAutonomy()`; then `strictest(level, product.autonomyCeiling())` when the item has a product. `allowDirect` = the item's own value, else the nearest ancestor's non-null value, else `false`. `doneMeans` = the nearest **epic** ancestor's value (or the item's own when it is an epic), else `MERGED`.
  - `static Autonomy WorkAutonomy.inherited( PolicyChain chain )`: the level the item would have with its own `autonomy` null, before the ceiling (Task 4 uses it to tell stricter from looser).
  - `PolicyChain WorkStore.policyChain( long itemId ) throws SQLException` (one query: the item, its parent and grandparent, project, product).

- [ ] **Step 1: Failing table test** `WorkAutonomyTest.levelResolution`: rows for own value wins over epic (both directions), story value inherited by a task, project default with no epic, ceiling caps `autonomous` to `delegated`, ceiling never raises, no product means no ceiling. `allowDirectInheritsFromTheEpicUnlessTheItemSaysOtherwise`, `doneMeansComesFromTheEpicOnly` (a story's own `in_production` under a `merged` epic is ignored).
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest=WorkAutonomyTest`. Expected: compilation failure, `WorkAutonomy` missing.
- [ ] **Step 3:** Implement the records and `resolve`/`inherited`.
- [ ] **Step 4: Failing store test** `WorkAutonomyPostgresTest.policyChainReturnsTaskStoryEpicProjectAndProduct`, `anItemWithNoParentOrProductHasAnEmptyChain`, `reparentingChangesTheResolvedLevel` (Review Focus 5). Implement `policyChain`.
- [ ] **Step 5: Source rule** `WorkAutonomySourceRuleTest`, modelled on `WorkStandingSourceRuleTest`: no main class of `com.wikantik.work` other than `WorkAutonomy`, the stores, `ItemPatchApplier`, `PatchValues` and the records themselves calls `autonomy()`, `allowDirect()`, `doneMeans()`, `defaultAutonomy()` or `autonomyCeiling()`. Expected to pass once written; make it fail first by adding a temporary call in `WorkPolicy`, see it fail, remove the call.
- [ ] **Step 6:** `mvn test -pl wikantik-work -Dtest='WorkAutonomy*'`: PASS. **Commit** `feat(work): resolve an item's effective autonomy, direct-commit and done-means policy in one place`.

---

### Task 3: The gates — who may pass each one

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/WorkGates.java`, `Change.java`, `ChangeState.java`, `MergeKind.java`
- Modify: `Action.java` (add `OPEN_CHANGE, REVIEW_CHANGE, MERGE_CHANGE, ACKNOWLEDGE`), `WorkPolicy.java` (`roleNeeded`: all four take `MEMBER`; the claimed-by-other fence also covers `OPEN_CHANGE`, `REVIEW_CHANGE`, `MERGE_CHANGE`)
- Test: `WorkGatesTest.java`, `WorkPolicyTest.java` (extend the role table)

**Interfaces:**
- Consumes: `EffectivePolicy` (Task 2), `Actor`, `Decision`.
- Produces:
  - `public record Change( long id, long itemId, long productId, String branch, String baseBranch, String headSha, String reviewUrl, ChangeState state, String mergedSha, MergeKind mergeKind, String openedBy, Long openedByAgentId, String reviewedBy, Long reviewedByAgentId, String reviewedHeadSha, Instant reviewedAt, String mergedBy, Long mergedByAgentId, Instant openedAt, Instant closedAt )` with `boolean approvedForHead()` (`reviewedHeadSha != null && reviewedHeadSha.equals(headSha)`).
  - `enum ChangeState { OPEN, IN_REVIEW, MERGED, ABANDONED }` and `enum MergeKind { REVIEWED, UNREVIEWED, DIRECT }`, each with `wire()`/`fromWire` like `Autonomy`.
  - `WorkGates` (final, static, every method returns `Decision`; a `HUMAN` actor is always allowed):
    - `mayApprove( Actor a, EffectivePolicy p )`: agent allowed only at `AUTONOMOUS`.
    - `mayClose( Actor a, EffectivePolicy p )`: agent refused at `SUPERVISED`.
    - `mayReview( Actor a, Change c )`: agent refused when `c.openedByAgentId()` equals its id.
    - `mayMerge( Actor a, Change c, EffectivePolicy p )`: agent refused, in this order, when it opened the change; at `SUPERVISED`; when it is not `reviewedByAgentId` with `approvedForHead()`.
    - `mayOpenDirect( Actor a, EffectivePolicy p )`: agent allowed only when `p.allowDirect()` and `p.level() != SUPERVISED`.
    - `boolean closesWithoutAPerson( Actor a, EffectivePolicy p )`: true for an agent at `AUTONOMOUS`.

- [ ] **Step 1: Failing table test** `WorkGatesTest.gateTable`: every level × {person, opener agent, approving agent, third agent} × {approve, close, review, merge, direct}, each row asserting allowed or the exact refusal string from Global Constraints. Add `anApprovalForAnOlderCommitDoesNotLetTheReviewerMerge`, `aPersonIsNeverRefused` (all five gates, all levels, including merging their own change).
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest=WorkGatesTest`. Expected: compilation failure.
- [ ] **Step 3:** Implement the enums, `Change`, `WorkGates`, and the `Action`/`WorkPolicy` additions.
- [ ] **Step 4:** `mvn test -pl wikantik-work -Dtest='WorkGatesTest,WorkPolicyTest,WorkAutonomySourceRuleTest'`: PASS. `WorkMetrics.denied` must accept the new actions (its switch or tag map); fix what the compiler names.
- [ ] **Step 5: Commit** `feat(work): the approve, review, merge and close gates by autonomy level`.

---

### Task 4: Item gates — approve, close, policy fields, acknowledgement

**Files:**
- Modify: `WorkService.java` (`updateItem`), `WorkClaimsService.java` (`handoff`), `WorkScopes.java` (`requireStatusChange`), `WorkPolicy.java` (`mayChangeStatus` delegates the draft/refining move to `WorkGates.mayApprove`), `ItemPatchApplier.java` (policy-key rule), `Item.java` (+ `boolean ackRequired, String acknowledgedBy, Instant acknowledgedAt`), `JdbcWorkStore.java` (row mapper, `writeItem` sets `ack_required`), `WorkStore.java`, `WaitingItem.java` users in `WorkService.waitingOn`
- Create: `wikantik-work/src/main/java/com/wikantik/work/ItemGates.java` (keeps the new checks out of `WorkService`)
- Test: `WorkItemGatesPostgresTest.java`, `WorkDraftRulePostgresTest.java` (update), `WorkPolicyFieldsPostgresTest.java`, `WorkAcknowledgePostgresTest.java`, `JdbcWorkStoreAckRollbackTest.java`

**Interfaces:**
- Consumes: `WorkStore.policyChain`, `WorkAutonomy.resolve`/`inherited`, `WorkGates`.
- Produces:
  - `ItemGates.requireStatusChange( Actor a, PolicyChain chain, WorkStatus target )`: approve gate when the item is `DRAFT` or `REFINING` and the target is later; close gate when the target is `DONE`; an agent's move of a draft to `CANCELLED` keeps today's text `A person cancels or moves a draft.`
  - `ItemGates.requirePolicyChange( Scope s, PolicyChain chain, Map< String, Object > changes )` implementing D9.
  - `WorkStore.ItemWrite` gains nothing; `writeItem` takes a `boolean ackRequired` and sets the column when the write moves the item to `done`; a move out of `done` clears `ack_required`, `acknowledged_by`, `acknowledged_at`.
  - `ItemView WorkService.acknowledge( Actor actor, String itemKey ) throws SQLException`: human with at least `member`; an agent is refused with `A person acknowledges work closed without one.`; writes event `acknowledged`; acknowledging an item that needs none is a no-op returning the view.
  - `WorkService.waitingOn` adds items with `ack_required AND acknowledged_at IS NULL` in projects where the actor is a lead or the actor is an admin, with `reason` `acknowledge`.

- [ ] **Step 1: Failing tests** `WorkItemGatesPostgresTest`: `anAgentMovesADraftToReadyOnlyWhenAutonomous`, `anAgentMovesRefiningToReadyOnlyWhenAutonomous`, `aHolderHandingAClaimedItemToAnotherQueueAsReadyIsNotAnApproval` (allowed at `delegated`), `anAgentCannotCloseWhenSupervised`, `anAgentClosesWhenDelegated`, `loweringTheEpicLevelRefusesTheNextClose` (Review Focus 2), `theProductCeilingCapsAnAutonomousEpic`, `aPersonIsNeverGated`. Each through `updateItem` and through `claims().handoff`.
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest=WorkItemGatesPostgresTest`. Expected: FAIL (an agent closes at `supervised` today; the draft rule refuses at `autonomous`).
- [ ] **Step 3:** Implement `ItemGates` and call it from both entry points. Update `WorkDraftRulePostgresTest` for the `autonomous` exception only; every other assertion stays.
- [ ] **Step 4: Failing tests** `WorkPolicyFieldsPostgresTest`: `aMemberMakesAStoryStricterThanItsEpic`, `aMemberCannotMakeItLooser` (exact refusal), `aLeadMakesItLooser`, `onlyALeadSetsAutonomous`, `onlyALeadSetsAllowDirectOrDoneMeans`, `clearingAStricterValueIsALoosening`, `anAgentSetsNoPolicyField`. Implement `requirePolicyChange`; `WorkService.updateItem` line 314 (`SET_POLICY` for any policy key) is replaced by it.
- [ ] **Step 5: Failing tests** `WorkAcknowledgePostgresTest`: `anAutonomousAgentCloseIsFlagged`, `aDelegatedAgentCloseIsNot`, `aPersonsCloseIsNot`, `acknowledgingClearsTheLaneAndWritesOneEvent`, `anAgentCannotAcknowledge`, `aNonMemberGetsNotFound`, `reopeningAnItemClearsTheFlag`, `theLaneIsShownToLeadsAndAdminsOnly`. `JdbcWorkStoreAckRollbackTest`: a failure after the `done` write leaves the item open and unflagged. Implement.
- [ ] **Step 6:** Classify `acknowledge` (write) in `WorkServiceVisibilityPostgresTest`. Run `mvn test -pl wikantik-work`: PASS.
- [ ] **Step 7: Commit** `feat(work): autonomy gates on approving and closing items, who sets policy fields, and acknowledging work closed without a person`.

---

### Task 5: Change records — store and service

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/WorkChangeStore.java`, `JdbcWorkChanges.java`, `WorkChangesService.java`, `ChangeDraft.java`, `ChangeEvents.java`
- Modify: `WorkStore.java` (`WorkChangeStore changes()`), `JdbcWorkStore.java`, `WorkService.java` (`public WorkChangesService changes()`; `ItemView` gains `List< Change > changes` and `EffectivePolicy effective`; the done rule and the product-change rule), `WorkService.ItemSummary` (+ `int openChanges, boolean inReview`)
- Test: `JdbcWorkChangesPostgresTest.java`, `WorkChangesServiceTest.java`, `WorkChangeGatesPostgresTest.java`, `WorkChangeRacePostgresTest.java`, `JdbcWorkChangesRollbackTest.java`, `WorkServiceVisibilityPostgresTest.java` (extend)

**Interfaces:**
- Consumes: `Change`, `ChangeState`, `MergeKind`, `WorkGates`, `WorkStore.policyChain`, `WorkStanding`.
- Produces:
  - `record ChangeDraft( String branch, String baseBranch, String headSha, String reviewUrl, String mergedSha )` (`baseBranch` null means the product's `default_branch`; a non-null `mergedSha` means opened and merged in one step).
  - `WorkChangeStore`: `Change open( Item item, ChangeDraft d, MergeKind kindOrNull, Actor by )`, `Optional< Change > change( long id )`, `List< Change > forItem( long itemId )`, `Change update( long id, String headSha, String reviewUrl, Actor by )`, `Change requestReview( long id, Actor by )`, `Change review( long id, boolean approved, String note, Actor by )`, `Change merge( long id, String mergedSha, MergeKind kind, Actor by )`, `Change abandon( long id, String note, Actor by )`, `boolean hasLive( long itemId )`. Every write runs in one transaction: `SELECT … FOR UPDATE` on the change row, a state precondition (else `WorkConflictException` reason `change_closed`), the update, and one `work_event` on the item.
  - `WorkChangesService` (all take `Actor` first; an unseen item or change is `WorkNotFoundException`):
    - `List< Change > list( Actor a, String itemKey )`
    - `Change open( Actor a, String itemKey, ChangeDraft d )`: needs a product (D7); with `mergedSha`, `WorkGates.mayOpenDirect` and kind `DIRECT`.
    - `Change update( Actor a, long changeId, String headSha, String reviewUrl )`: a new `headSha` clears the approval columns (D5).
    - `Change requestReview( Actor a, long changeId )`: `open` → `in_review`.
    - `Change review( Actor a, long changeId, boolean approved, String note )`: needs `in_review`; `WorkGates.mayReview`; approved records reviewer and `reviewed_head_sha`; not approved returns the change to `open` with the note in the event.
    - `Change merge( Actor a, long changeId, String mergedSha )`: `WorkGates.mayMerge`; an agent with no standing approval gets conflict `not_approved`; kind is `REVIEWED` when `approvedForHead()`, else `UNREVIEWED`.
    - `Change abandon( Actor a, long changeId, String note )`.
  - Rules added to items: `updateItem`/`handoff` to `done` with `changes().hasLive` is `WorkConflictException` reason `open_changes`, for everyone; changing `productKey` while a change is live is the same refusal.
  - Text limits: `branch` and `baseBranch` 1–200 chars with no whitespace or control characters; `headSha`/`mergedSha` 7–64 hex; `reviewUrl` `http`/`https` only, at most 500 chars; `note` at most 2,000.

- [ ] **Step 1: Failing store tests** `JdbcWorkChangesPostgresTest`: `openWritesAChangeAndOneEvent`, `aSecondLiveChangeOnTheSameBranchIsAValidationError` (field `branch`, not a raw SQL error), `updateWithANewHeadClearsTheApproval`, `updateWithTheSameHeadKeepsIt`, `reviewRecordsTheReviewerAndHead`, `aRejectedReviewReturnsToOpen`, `mergeSetsStateKindAndClosedAt`, `anAgentsWritesRecordItsId`, `abandonedAndMergedChangesRefuseEveryWrite` (`change_closed`).
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest=JdbcWorkChangesPostgresTest`. Expected: compilation failure. Implement the store.
- [ ] **Step 3: Rollback tests** `JdbcWorkChangesRollbackTest`: for open, review and merge, a fault on the event insert leaves the change row untouched.
- [ ] **Step 4: Failing gate tests** `WorkChangeGatesPostgresTest` (real store, real standing): `theOpenerAgentCannotReviewItsOwnChange`, `aSecondAgentOfTheSameSponsorReviewsIt` (D4), `theApprovingAgentMergesAtDelegated`, `aThirdAgentCannotMerge`, `noAgentMergesAtSupervised`, `aNewCommitAfterApprovalBlocksTheMerge` (Review Focus 1), `loweringTheLevelAfterApprovalRefusesTheMerge` (Review Focus 2), `aPersonMergesTheirOwnChangeUnreviewed`, `aPersonOpensAndMergesInOneCall`, `anAgentsDirectCommitNeedsAllowDirect`, `allowDirectOnTheEpicCoversItsTasks`, `allowDirectGivesNothingAtSupervised`, `anAgentIsFencedByAnotherAgentsClaim`, `anItemWithNoProductHasNoChanges`, `anItemCannotBeDoneWithALiveChange`, `theProductCannotChangeUnderALiveChange`, `anExpiredClaimLeavesTheChangeAttached` (the next agent's `get` shows it).
- [ ] **Step 5:** Implement `WorkChangesService`. Run the gate tests: PASS.
- [ ] **Step 6: Race test** `WorkChangeRacePostgresTest.twoMergesOfOneChangeProduceOneEvent` (Review Focus 3) and `aMergeRacingANewHeadIsRefusedOrLandsFirst`.
- [ ] **Step 7: Visibility.** Add `changes` to `FACADES` and classify the seven methods; add the stranger cases by item key and by change id for a non-member, an agent under a non-member sponsor, and an agent with none (Review Focus 4). A change id of a hidden item and an id that does not exist must be indistinguishable.
- [ ] **Step 8:** `mvn test -pl wikantik-work`: PASS. **Commit** `feat(work): change records from open to merged, with review bound to a commit and the merge gate`.

---

### Task 6: REST

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/WorkChangeResource.java`
- Modify: `WorkRoutes.java`, `WorkItemResource.java` (acknowledge route), `WorkJson.java`, `WorkSummaries.java`, `WorkEventNames.java`, `wikantik-war/src/main/webapp/WEB-INF/web.xml` only if `/api/work/*` is not already one mapping
- Test: `WorkChangeResourceTest.java`, `WorkItemResourceTest.java` (extend), `WorkJsonTest.java` (extend)

**Interfaces:**
- Consumes: `WorkService.changes()`, `acknowledge`.
- Produces (relative to `/api/work`):
  - `GET items/{key}/changes` → `{changes:[…]}`; `POST items/{key}/changes` body `{branch, baseBranch?, headSha?, reviewUrl?, mergedSha?}` → 201.
  - `PATCH changes/{id}` body `{headSha?, reviewUrl?}`; `POST changes/{id}/request-review`; `POST changes/{id}/review` body `{approved:boolean, note?}`; `POST changes/{id}/merge` body `{mergedSha}`; `POST changes/{id}/abandon` body `{note?}`.
  - `POST items/{key}/acknowledge`.
  - Change JSON: `id, itemKey, productKey, branch, baseBranch, headSha, reviewUrl, state, mergedSha, mergeKind, openedBy, reviewedBy, approvedForHead, mergedBy, openedAt, closedAt`. People by display name, agents by name, never a login for a non-admin (`WorkNames`).
  - Item JSON gains `changes`, `effective:{autonomy, allowDirect, doneMeans}`, `ackRequired`, `acknowledgedBy`, `acknowledgedAt`. Board and list summaries gain `openChanges`, `inReview`.
  - Status mapping: `WorkConflictException` → 409 with `reason`; denied → 403 with the message; not found → 404; validation → 400 with `field`.

- [ ] **Step 1: Failing tests** `WorkChangeResourceTest`: one per route for the happy path and for 400, 403, 404 and 409 (`open_changes`, `not_approved`, `change_closed`); `anAgentTokenOpensAChangeAsTheAgent`; `aNonNumericChangeIdIsNotFound`; `aNonAdminSeesNoLoginInChangeJson`. Extend `WorkItemResourceTest` for `acknowledge` and the new item fields.
- [ ] **Step 2:** Run `mvn test -pl wikantik-rest -Dtest='WorkChangeResourceTest,WorkItemResourceTest,WorkJsonTest'`. Expected: FAIL (404 on the new routes).
- [ ] **Step 3:** Implement. Errors go through `RestServletBase.sendError`, not the response's own.
- [ ] **Step 4:** Re-run: PASS. `mvn test -pl wikantik-rest -Dtest='WorkAccessArchTest'`: PASS.
- [ ] **Step 5: Commit** `feat(work-rest): change routes and acknowledge`.

---

### Task 7: MCP tools

**Files:**
- Create: `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/OpenChangeTool.java`, `UpdateChangeTool.java`, `ReviewChangeTool.java`, `MergeChangeTool.java`
- Modify: `WorkMcpInitializer.java` (`assembleTools`), `WorkMcpJson.java`, `GetItemTool.java`, `ClaimItemTool.java` (both return `changes` and `effective`), `WorkMcpTool.java` (map the three new conflict reasons to `open_changes:`, `not_approved:`, `change_closed:` prefixes), `src/main/resources/wikantik-work-mcp-instructions.txt`
- Test: `ChangeToolsTest.java`, `WriteToolsAuditTest.java`, `WorkMcpInitializerTest.java`, `WorkMcpInstructionsDriftTest.java`, `WorkMcpFlowPostgresTest.java` (extend)

**Interfaces:**
- Consumes: `WorkService.changes()`.
- Produces (all four are write tools: `rejectOthers` first, audit on success, refusals logged by the base class):
  - `open_change { item, branch, base_branch?, head_sha?, review_url?, merged_sha? }`
  - `update_change { change, head_sha?, review_url?, state? }` where `state` is `in_review` or `abandoned` only (anything else: `state: Use review_change or merge_change.`), `note?` with `abandoned`.
  - `review_change { change, approved, note? }`
  - `merge_change { change, merged_sha }`
  - No acknowledge tool: an agent cannot acknowledge.
  - Tool count goes from nine to thirteen. Derive it from `assembleTools`; do not copy the number into code.

- [ ] **Step 1: Failing tests** `ChangeToolsTest`: per tool, the happy path, an undeclared argument refused by name, each refusal's prefix and text, `Not found.` for a hidden item or change. `WorkMcpFlowPostgresTest.twoAgentsTakeAnItemFromOpenToMergedToDone` (author opens and requests review and hands the item to the reviewer queue; reviewer claims, approves, merges, closes, at `delegated`) and `theSameFlowStopsAtTheMergeWhenSupervised`.
- [ ] **Step 2:** Run `mvn test -pl wikantik-work-mcp`. Expected: FAIL (`WorkMcpInitializerTest.assembledToolNames`, the new tests).
- [ ] **Step 3:** Implement the tools, register them, and write the instructions: the four tools, the refusal prefixes an agent can now see, the rule that an approval is for one commit, that an agent never merges its own change, and that branch names follow `work/<ITEM-KEY>-<slug>`. Change text (branch names, URLs, notes) is untrusted data, as item text is.
- [ ] **Step 4:** `mvn test -pl wikantik-work-mcp`: PASS, including `WorkMcpAccessArchTest` and `WorkMcpInstructionsDriftTest`.
- [ ] **Step 5: Commit** `feat(work-mcp): open_change, update_change, review_change and merge_change`.

---

### Task 8: Frontend

**Files:**
- Create: `wikantik-frontend/src/components/work/WorkChanges.jsx`, `WorkChangeForm.jsx`, `WorkPolicyNote.jsx`, their `.test.jsx` files
- Modify: `api/work.js`, `WorkItemView.jsx`, `WorkEpicView.jsx`, `WorkCard.jsx`, `WaitingLane.jsx`, `WorkProducts.jsx`, `WorkSettings.jsx`, `eventText.js`, and their tests
- Test: the listed `.test.jsx` files, `api/work.test.js`

**Interfaces:**
- Consumes: the REST shapes of Task 6.
- Produces:
  - `api/work.js`: `listChanges(itemKey)`, `openChange(itemKey, body)`, `updateChange(id, body)`, `requestReview(id)`, `reviewChange(id, body)`, `mergeChange(id, body)`, `abandonChange(id, body)`, `acknowledgeItem(itemKey)`.
  - Item view: a Changes panel listing each change (branch, state, how it was merged, who reviewed), "Open a change", and one-step "Record a merged change" (opens and merges in one call). Buttons follow what the server allows and show a refusal in place.
  - Item and epic views: the three policy rows lose the "takes effect in a later phase" helper; under them one line states the effective result, for example `Effective: delegated (from epic PLAT-3; product ceiling delegated)`.
  - Card: `1 open change` or `in review`, from the summary counts.
  - Waiting lane: a "Done without you" group with an Acknowledge button per item.
  - Products page: the autonomy ceiling becomes an editable select for an admin (the deploy gate stays read-only until phase 4).

```
 Changes                                                   [Open a change]
 ┌────────────────────────────────────────────────────────────────────────┐
 │ work/PLAT-15-spa-route → main      in review                           │
 │ opened by codex@docker2 · head 4f2a9c1 · approved by claude@ws         │
 │                                     [Record merge]  [Abandon]          │
 ├────────────────────────────────────────────────────────────────────────┤
 │ main                                merged directly                    │
 │ merged by Jake Fear · 9b01c77                                          │
 └────────────────────────────────────────────────────────────────────────┘
 Effective: delegated (from epic PLAT-3; product ceiling delegated)
```

- [ ] **Step 1: Failing tests** for each new component and each modified one: the panel renders every state and merge kind; a 409 `not_approved` shows its message without losing the form; the lane shows and clears an acknowledged item; the card badge; the effective line for own value, inherited value and a capped value; the event texts for the seven new event types.
- [ ] **Step 2:** Run `npx vitest run src/components/work src/api/work.test.js` in `wikantik-frontend`. Expected: FAIL on the new tests.
- [ ] **Step 3:** Implement, reusing `src/components/ui/` and the existing `WorkFieldRow`, `WorkConfirm` and `WorkLoadError`. No new CSS variables that are not defined.
- [ ] **Step 4:** `npx vitest run` and `npx eslint src`: PASS.
- [ ] **Step 5: Commit** `feat(work-ui): changes on the item view, the effective autonomy line, and the done-without-you lane`.

---

### Task 9: Metrics, integration tests, documentation, gate

**Files:**
- Modify: `WorkMetrics.java`, `WorkListener.java`, `GuardedListener.java` (`gate(String gate, boolean allowed)` → `wikantik_work_gates_total{gate,outcome}`, gates `approve|close|review|merge|direct`); `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkChangeApiIT.java` (new), `WorkMcpIT.java` (`EXPECTED_TOOLS`), a Selenide `WorkChangesIT` beside the existing work browser ITs
- Modify docs: `docs/admin/McpAgents.md`, `docs/admin/WikantikOperations.md` (section 1.15), `docs/developer/ProjectReference.md`, `wikantik-work/CLAUDE.md`, `wikantik-work-mcp/CLAUDE.md`, root `CLAUDE.md` (module line, design-doc status), the parent spec's phase list, `CHANGELOG.md` `[Unreleased]`
- Test: `WorkMetricsTest.java`, the ITs above

- [ ] **Step 1: Failing test** `WorkMetricsTest.gateOutcomesAreCountedWithNoProjectLabel`; implement; the service fires `gate` once per gate decision.
- [ ] **Step 2: ITs.** `WorkChangeApiIT`: `twoAgentsDeliverAnItemAtDelegated`, `anAgentCannotMergeItsOwnChange`, `anItemCannotCloseWithALiveChange`, `aNonMemberLearnsNothingAboutAChange`, `anAutonomousCloseWaitsForAPerson`. `WorkMcpIT`: the four tools listed and one end-to-end call each. `WorkChangesIT`: a person opens and merges a change in one step from the item view.
- [ ] **Step 3:** Documentation: what each level lets an agent do, the commit-bound approval, direct commits, acknowledgement, the new tools and routes, the new metric. Remove every "inert until phase 3" statement.
- [ ] **Step 4:** `bin/agent-build.sh start cov -- mvn clean install -Pcoverage -DskipITs`, wait: SUCCESS, no floor lowered. Then `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`, wait: `RESULT: ALL PASSED`.
- [ ] **Step 5: Commit** `docs(work): phase 3b — changes, autonomy gates and acknowledgement; changelog`.
