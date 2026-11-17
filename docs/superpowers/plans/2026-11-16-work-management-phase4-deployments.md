# Work Management Phase 4 — Deployments and the Autonomous Incident Path — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A product version going to production is a record that carries items, passes the product's deploy gate, needs evidence to be verified, and can be checked by the deploy script; items under an `in_production` epic close only when a verified deployment carried them.

**Architecture:** `wikantik-work` gains deployment tables, a store and a service facade (`WorkService.deployments()`), following the claims and changes facades. A deployment belongs to a product, not a project, so it has its own append-only history table and its visibility is derived from the items it carries. The `done_means` and `deploy_gate` columns, stored since phase 1, become live. The deploy scripts gain an opt-in check against one small REST route.

**Tech Stack:** Java 25, PostgreSQL 18 (`Jdbc` primitive, `PostgresTestDb`), JUnit 5, ArchUnit, Mockito, React 19 + vitest, Bash, Cargo/Selenide ITs.

**Spec:** `docs/superpowers/specs/2026-11-15-work-management-design.md`, section "Deployments and the autonomous path (phase 4)". Builds on `docs/superpowers/plans/2026-11-16-work-management-phase3b-changes-autonomy.md`, which must be complete first.

## What phase 3b left this plan to build on

Phase 3b is built; these are its names and rulings as they stand (its ledger and `wikantik-work/CLAUDE.md`, "Changes and autonomy", are the record). Where a task below says otherwise, this section wins.

- The resolved policy is `EffectivePolicy( level, directAllowed, doneWhen )` from `WorkAutonomy.resolve( store.policyChain( itemId ) )`. `doneWhen` is what this plan calls done-means; it is already resolved (epic only) and nothing acts on it yet.
- Status gates live in `ItemGates.requireStatusChange( Scope, Item, WorkStatus )`, reached through `WorkScopes.gates()`; it returns early for a person. The `in_production` rule binds everyone, so it goes before that early return, or better under the item-row lock in `JdbcWorkItemWrites` beside `requireNoLiveChange` (the same reason: a verify and a close must not cross).
- `Item`, `ItemView` and `ItemSummary` are deliberately unchanged: add new reads beside them (as `WorkService.policyOf`, `changes().views` and `changes().counts` did) and new REST routes beside the item routes (`items/{key}/policy`, `items/{key}/changes`, `projects/{key}/change-counts`). So "shipped in" is its own read and route, not a field of the item view.
- `WaitingItem( item, reason, handoff )` already has reason `acknowledge` and the "Done without you" lane exists (`Acknowledgements`, `WaitingLane.jsx`). A deployment awaiting approval has no item: give it its own list on `GET /api/work/me` (`deploymentsAwaiting`) rather than widening `WaitingItem`.
- A gate decision for an agent is reported through `WorkScopes.decide( scope, gate, action, decision )` (`wikantik_work_gates_total`); the deploy gate reports as gate `deploy`. A deployment has no single project scope, so `DeploymentAccess` needs its own small equivalent that calls the same listener.
- Event `actor_kind` values on the wire and in the database are `user` and `agent`.
- MCP conventions: an item argument is `key`, numeric ids are named for the thing (`change`; here `deployment`); audit actions are single hyphenated tokens; a name that looks like a tool in the instructions file must be added to the drift test's exclusion set. The endpoint has thirteen tools before this phase.
- The store facades are split to stay under the PMD limits (`JdbcWorkItemWrites`, `JdbcWorkAcks`, `JdbcWorkChanges`): add `JdbcWorkDeployments` the same way and expect to split the service.
- The work event log's change events use `from_value` `change:<id>`; use `deployment:<id>` for the per-item deployment events.

## Changes made by the final review (2026-11-17)

The whole-branch review found ordinary sequences of calls that defeated promises of this phase. What was built differs from the text below in these ways; this section wins.

- **A deployment can be rejected.** `requested→rejected` and `approved→rejected` join the allowed moves (state `rejected`): whoever requested it withdraws it, or anyone who may approve it turns it down. Before, the only way to clear a mistaken request was to approve it.
- **Only production ships.** "Shipped" means a verified deployment to `production`, verified at or after the item's last merged change. The deploy script compares the environment as well as the version.
- **A deployment carries work that is `ready` or later**, never a draft, refining or cancelled item, and never an epic.
- **Leaving an `in_production` epic is a loosening**: a lead, never an agent.
- **The deploy scripts ask about the version actually shipped** (`bin/work-deploy-check.sh`: the pulled tag, the image label, or the pom version), and nothing in the environment can tell them the check already happened.

## Decisions this plan makes where the spec is silent

Each is the plan's call, listed in the handoff for the owner to overturn before execution.

- **D1. Who sees a deployment.** A deployment is visible to a wiki admin and to anyone who can see at least one project it carries an item from. Its item list shows only the items the caller can see; the number of hidden items is not shown. A deployment the caller can see none of is `Not found`.
- **D2. Who may request.** A person or agent with at least `member` in every project the deployment carries an item from. Every carried item must target the deployment's product. A deployment carries at least one item.
- **D3. Who may approve under a `human` gate.** A wiki admin, or a person who is `lead` in every project the deployment carries an item from. An agent is refused.
- **D4. Under an `agent` gate,** anyone who may request may approve, including the agent that requested. A person may always approve under the D3 rule as well.
- **D5. Who may run it.** Marking a deployment `running`, `verified`, `failed` or `rolled_back` takes the same standing as requesting it (D2), person or agent, under either gate. The gate governs approval only.
- **D6. Evidence** is plain text, required and non-blank for `verified`, at most 20,000 characters, shown preformatted and never rendered as Markdown.
- **D7. "Shipped in" is derived, not stored on the item.** An item's view lists the verified deployments that carried it. Each carried item also gets a `shipped` event when its deployment is verified, so the timeline and activity feed show it.
- **D8. The deploy-script check is opt-in.** `bin/deploy-release.sh` and `bin/remote.sh deploy` check a deployment only when `WORK_DEPLOYMENT_ID` is set. With it unset they behave exactly as today, so a work outage can never block a deploy that did not ask for the check.
- **D9. The jakemon trigger is documented, not built.** The on-call automation that would receive an alert is not built (Simple Agility roadmap). This phase proves, with an integration test, that an agent can run the whole incident path under a standing autonomous epic; wiring an alert to that agent belongs to the agent runtime.
- **D10. Environment** is free text, default `production`, at most 40 characters of `[a-z0-9-]`.
- **D11. A `failed` deployment can be retried only as a new deployment.** States move forward only.

## Global Constraints

- Root `CLAUDE.md`, `wikantik-work/CLAUDE.md`, `wikantik-work-mcp/CLAUDE.md` bind every task. TDD: a failing test first, the failing run captured.
- `wikantik-work` depends on neither `wikantik-api` nor `wikantik-main`. Every caller outside the module goes through `WorkService` and its facades. A new facade accessor is added to `WorkServiceVisibilityPostgresTest.FACADES` and every new public method is classified there.
- Database access only through `com.wikantik.jdbc.Jdbc`. A multi-statement write gets a rollback test. Tests use `PostgresTestDb`; never hand-write DDL.
- Migrations are DDL-only, idempotent, use `:app_user` for grants; applied ones are never edited. This plan's migration is **V082**.
- A project's existence is confidential. No deployment response, error, count, log line or metric may reveal an item, a project key or the number of items from a project the caller cannot see. Non-members get 404, never 403.
- Role and visibility come only from `WorkStanding`; `done_means` and the autonomy level only from `WorkAutonomy.resolve`; the deploy gate only from the new `DeployGates`. An agent's standing is capped by its sponsor on every call.
- A person with the D3 standing is never refused at the deploy gate. Autonomy never opens the deploy gate: under a `human` gate an agent can only request, inside an `autonomous` epic too.
- Deployment history (`work_deployment_event`) is append-only: grants are `SELECT, INSERT` only. Every state change writes its history row in the same transaction.
- A failed directory or store lookup is `WorkUnavailableException` (REST 503, MCP `Work is unavailable right now.`), never "approved" and never "refused". The script check treats anything but a clear "approved" as a refusal.
- No project, product or item labels on metrics. No swallowed exceptions. Log ids and counts, never tokens, evidence text or caller-supplied text. No PMD suppressions.
- Exact values (use verbatim):
  - States: `requested`, `approved`, `running`, `verified`, `failed`, `rolled_back`.
  - Allowed moves: `requested→approved`, `approved→running`, `running→verified`, `running→failed`, `verified→rolled_back`, `failed→rolled_back`. Anything else: conflict reason `bad_transition`, `A deployment that is <state> cannot become <target>.`
  - Item event types: `deployment_requested`, `shipped`, `rolled_back`.
  - Refusals:
    - `A person approves deployments of this product.`
    - `Approving this deployment takes a lead of every project it carries, or a wiki administrator.`
    - Validation, field `evidence`: `Attach the health and smoke-test output before marking this verified.`
    - Validation, field `items`: `A deployment carries at least one item.` / `<KEY> does not target this product.`
    - Conflict, reason `awaiting_deployment`: `This closes when a deployment that carries it is verified.`
  - Script exit codes: `0` approved, `1` refused, `2` could not ask.
- Commits: stage files by name, 1–3 line messages. Never push. Long Maven runs through `bin/agent-build.sh`. Agent shells cannot run `bin/deploy-release.sh`; script changes are verified with the stub server in Task 7, never by a real deploy.

## Review Focus

1. **A deployment that spans a project the caller cannot see.** A member of project A views a deployment carrying items of A and B. Expected: A's items only, no count of B's, no B key in any field, history rows naming a hidden item omitted. An agent whose sponsor lost standing in A sees `Not found`. Tests in Tasks 3, 5 and 6.
2. **Approval racing the deploy script.** The script asks while an approval, or a move to `failed`, commits. Expected: the answer is the committed state at read time; a deployment that is `verified`, `failed` or `rolled_back` is never reported as approved. Tests in Tasks 3 and 7.
3. **The work service is down or slow when the script asks.** Timeout, 5xx, 404, malformed JSON, a wrong version. Expected: exit 2 or 1, never 0, with one line saying why. Test in Task 7.
4. **An item carried by two deployments, one verified and one failed,** or re-opened after shipping. Expected: an `in_production` item may close once any carrying deployment is verified and not rolled back; a rolled-back deployment no longer counts; "shipped in" lists verified ones only. Tests in Task 4.
5. **The epic's `done_means` changes while items wait.** A lead moves an epic from `in_production` back to `merged`. Expected: its items may close on the next call; nothing closes by itself. Test in Task 4.

---

### Task 1: Schema V082 — deployments

**Files:**
- Create: `bin/db/migrations/V082__work_deployments.sql`
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkDeploymentsSchemaTest.java` (new)

**Interfaces:**
- Produces:
  - `work_deployment (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, product_id BIGINT NOT NULL REFERENCES work_product (id), environment TEXT NOT NULL DEFAULT 'production', version TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'requested' CHECK (state IN ('requested','approved','running','verified','failed','rolled_back')), requested_by TEXT NOT NULL, requested_by_agent_id BIGINT REFERENCES work_agent (id), approved_by TEXT, approved_by_agent_id BIGINT REFERENCES work_agent (id), requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(), approved_at TIMESTAMP WITH TIME ZONE, started_at TIMESTAMP WITH TIME ZONE, verified_at TIMESTAMP WITH TIME ZONE, closed_at TIMESTAMP WITH TIME ZONE, evidence TEXT)`.
  - `CHECK (state <> 'verified' OR (evidence IS NOT NULL AND btrim(evidence) <> ''))`.
  - `work_deployment_item (deployment_id BIGINT NOT NULL REFERENCES work_deployment (id), item_id BIGINT NOT NULL REFERENCES work_item (id), PRIMARY KEY (deployment_id, item_id))`; index on `(item_id)`.
  - `work_deployment_event (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, deployment_id BIGINT NOT NULL REFERENCES work_deployment (id), at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(), actor TEXT NOT NULL, actor_kind TEXT NOT NULL CHECK (actor_kind IN ('user','agent')), agent_id BIGINT REFERENCES work_agent (id), from_state TEXT, to_state TEXT NOT NULL, note TEXT)`; index `(deployment_id, at, id)`.
  - Index `idx_work_deployment_product ON work_deployment (product_id, requested_at DESC)`; partial index `idx_work_deployment_waiting ON work_deployment (product_id) WHERE state = 'requested'`.
  - Grants: `SELECT, INSERT, UPDATE ON work_deployment`; `SELECT, INSERT ON work_deployment_item, work_deployment_event`.

- [ ] **Step 1: Failing schema tests:** `aVerifiedDeploymentNeedsEvidence` (SQLState `23514`, also for blank evidence), `anUnknownStateIsRefused`, `anItemIsCarriedOnceByADeployment`, `theAppRoleCannotUpdateOrDeleteHistory`, `theAppRoleCannotRemoveACarriedItem`.
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest=WorkDeploymentsSchemaTest`. Expected: FAIL, relation missing.
- [ ] **Step 3:** Write the migration; re-run: PASS; apply it twice with `psql`: no error.
- [ ] **Step 4: Commit** `feat(work): V082 deployment records, carried items and history`.

---

### Task 2: Domain, gate and store

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/Deployment.java`, `DeploymentState.java`, `DeploymentEvent.java`, `DeploymentDraft.java`, `DeployGates.java`, `WorkDeploymentStore.java`, `JdbcWorkDeployments.java`
- Modify: `WorkStore.java` (`WorkDeploymentStore deployments()`), `JdbcWorkStore.java`, `Action.java` (`REQUEST_DEPLOYMENT, APPROVE_DEPLOYMENT, RUN_DEPLOYMENT`)
- Test: `DeploymentStateTest.java`, `DeployGatesTest.java`, `JdbcWorkDeploymentsPostgresTest.java`, `JdbcWorkDeploymentsRollbackTest.java`

**Interfaces:**
- Produces:
  - `enum DeploymentState { REQUESTED, APPROVED, RUNNING, VERIFIED, FAILED, ROLLED_BACK }` with `wire()`, `fromWire`, `boolean mayBecome( DeploymentState target )` (the six moves in Global Constraints) and `boolean clearedToRun()` (true for `APPROVED` and `RUNNING` only).
  - `record Deployment( long id, long productId, String environment, String version, DeploymentState state, String requestedBy, Long requestedByAgentId, String approvedBy, Long approvedByAgentId, Instant requestedAt, Instant approvedAt, Instant startedAt, Instant verifiedAt, Instant closedAt, String evidence, List< Long > itemIds )`.
  - `record DeploymentDraft( String productKey, String environment, String version, List< String > itemKeys )`; `record DeploymentEvent( long id, long deploymentId, Instant at, String actor, Actor.Kind actorKind, Long agentId, DeploymentState from, DeploymentState to, String note )`.
  - `DeployGates` (final, static): `Decision mayApprove( Actor a, DeployGate gate, boolean leadOfEveryProject, boolean memberOfEveryProject )` implementing D3 and D4 with the two exact refusals.
  - `WorkDeploymentStore`: `Deployment request( Product p, String environment, String version, List< Item > items, Actor by )` (deployment, carried items, one history row, one `deployment_requested` event per item, in one transaction); `Optional< Deployment > deployment( long id )`; `List< Deployment > forProduct( long productId, int limit )`; `List< Deployment > carrying( long itemId )`; `Deployment move( long id, DeploymentState target, String evidenceOrNull, String note, Actor by )` (`FOR UPDATE`, `mayBecome` else conflict `bad_transition`, the update, a history row, and on `VERIFIED` one `shipped` event per carried item, on `ROLLED_BACK` one `rolled_back` event per carried item); `List< DeploymentEvent > history( long id )`; `boolean shippedVerified( long itemId )` (a carrying deployment in state `VERIFIED`); `List< Deployment > awaitingApproval()`.

- [ ] **Step 1: Failing tests** `DeploymentStateTest.transitionTable` (all 36 pairs) and `DeployGatesTest.gateTable` (gate × {admin, lead of all, lead of some, member, agent member of all, agent member of some}).
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest='DeploymentStateTest,DeployGatesTest'`. Expected: compilation failure. Implement the enum, records and `DeployGates`.
- [ ] **Step 3: Failing store tests** `JdbcWorkDeploymentsPostgresTest`: `requestWritesTheDeploymentItsItemsOneHistoryRowAndAnEventPerItem`, `moveFollowsTheTransitionTable`, `verifiedStoresEvidenceAndShipsEveryItem`, `rolledBackWritesAnEventPerItem`, `anAgentsMoveRecordsItsId`, `shippedVerifiedIgnoresFailedAndRolledBack` (Review Focus 4), `carryingListsEveryDeploymentOfAnItem`.
- [ ] **Step 4:** Implement the store. `JdbcWorkDeploymentsRollbackTest`: a fault on the last per-item event leaves no deployment (request) and no state change (move).
- [ ] **Step 5:** `mvn test -pl wikantik-work -Dtest='*Deploy*'`: PASS. **Commit** `feat(work): deployment states, the deploy gate and the deployment store`.

---

### Task 3: The deployments service

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/WorkDeploymentsService.java`, `DeploymentView.java`, `DeploymentAccess.java`
- Modify: `WorkService.java` (`public WorkDeploymentsService deployments()`), `WorkInputs.java` (version, environment and evidence validation)
- Test: `WorkDeploymentsServiceTest.java`, `WorkDeploymentAccessPostgresTest.java`, `WorkDeploymentRacePostgresTest.java`, `WorkServiceVisibilityPostgresTest.java` (extend)

**Interfaces:**
- Consumes: `WorkDeploymentStore`, `DeployGates`, `WorkStanding`.
- Produces:
  - `record DeploymentView( Deployment deployment, String productKey, List< Item > visibleItems, boolean mayApprove, boolean mayRun )`.
  - `DeploymentAccess` (package-private): from an actor and a deployment's carried items, the set of projects, the caller's role in each through `WorkStanding`, and `visibleItems`, `seesAny`, `memberOfEvery`, `leadOfEvery`. This is the only class that joins deployments to standing.
  - `WorkDeploymentsService` (an unseen deployment is `WorkNotFoundException`):
    - `DeploymentView request( Actor a, DeploymentDraft d )` (D2; a product the actor cannot list, or an item key it cannot see, is `Not found`; an item of another product is the `items` validation error naming the key).
    - `DeploymentView deployment( Actor a, long id )`
    - `List< DeploymentView > forProduct( Actor a, String productKey, int limit )` (only those the actor sees any of)
    - `List< DeploymentEvent > history( Actor a, long id )` (visibility as the deployment)
    - `DeploymentView approve( Actor a, long id, String note )` (`DeployGates.mayApprove`)
    - `DeploymentView start( Actor a, long id )`, `verify( Actor a, long id, String evidence )`, `fail( Actor a, long id, String note )`, `rollBack( Actor a, long id, String note )` (D5)
    - `boolean cleared( Actor a, long id, String version )`: true only when the actor sees the deployment, its `version` equals the argument and its state `clearedToRun()`.
    - `List< DeploymentView > awaitingMyApproval( Actor a )`: `requested` deployments the actor may approve.
  - `version`: 1–60 characters of `[A-Za-z0-9._+-]`.

- [ ] **Step 1: Failing tests** `WorkDeploymentAccessPostgresTest` (real standing): `aMemberOfOneCarriedProjectSeesOnlyItsItems` and `noFieldRevealsTheOtherProject` (Review Focus 1; assert on the whole view and on `history`), `aNonMemberGetsNotFound`, `anAgentUnderASponsorWithNoStandingGetsNotFound`, `aRequestNeedsMembershipOfEveryCarriedProject`, `anItemOfAnotherProductIsRefusedByKey`, `anEmptyDeploymentIsRefused`, `anAgentCannotApproveUnderAHumanGate` (also inside an `autonomous` epic), `aLeadOfOnlySomeProjectsCannotApprove`, `anAdminApproves`, `underAnAgentGateTheRequestingAgentApproves`, `verifyNeedsEvidence`, `aTooLongEvidenceIsRefused`, `clearedIsTrueOnlyForApprovedOrRunningAndTheSameVersion`.
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest=WorkDeploymentAccessPostgresTest`. Expected: compilation failure. Implement.
- [ ] **Step 3: Race test** `WorkDeploymentRacePostgresTest.clearedNeverReportsAClosedDeployment` (Review Focus 2: `cleared` read against a concurrent move to `failed`, both orders, `CommitGate` pattern) and `twoApprovalsWriteOneHistoryRow`.
- [ ] **Step 4:** Add `deployments` to `FACADES`; classify every method; add stranger cases by deployment id. `mvn test -pl wikantik-work`: PASS.
- [ ] **Step 5: Commit** `feat(work): request, approve, run and verify deployments, visible only through the items they carry`.

---

### Task 4: `done_means`, shipped-in and the waiting lane

**Files:**
- Modify: `JdbcWorkItemWrites.java` (the `in_production` rule under the item-row lock, beside `requireNoLiveChange`), `WorkService.java` (a `shippedIn( actor, itemKey )` read; the item view is not changed), `WorkDeploymentsService.java` (`awaitingMyApproval`, surfaced by `GET /api/work/me` as `deploymentsAwaiting`)
- Test: `WorkDoneMeansPostgresTest.java`, `WorkWaitingDeploymentsPostgresTest.java`

**Interfaces:**
- Consumes: `EffectivePolicy.doneMeans()`, `WorkDeploymentStore.shippedVerified`/`carrying`, `WorkDeploymentsService.awaitingMyApproval`.
- Produces:
  - The item write refuses a move to `done` for **everyone** when the effective `doneWhen` is `IN_PRODUCTION` and no verified deployment carries the item: `WorkConflictException` reason `awaiting_deployment`. The service resolves `doneWhen` and passes it to the write; the store checks the deployments under the item-row lock. Epics themselves are not gated.
  - `List< Deployment > WorkService.shippedIn( Actor, String itemKey )`: verified deployments carrying the item, newest first (a read; classify it in the visibility test).
  - `GET /api/work/me` gains `deploymentsAwaiting`: the `requested` deployments the caller may approve.

- [ ] **Step 1: Failing tests** `WorkDoneMeansPostgresTest`: `anItemUnderAnInProductionEpicCannotCloseAtMerged`, `itClosesOnceACarryingDeploymentIsVerified`, `aFailedOrRolledBackDeploymentDoesNotCount`, `oneVerifiedAmongSeveralIsEnough` (Review Focus 4), `anItemUnderAMergedEpicClosesAsBefore`, `changingTheEpicBackToMergedLetsItClose` (Review Focus 5), `aPersonIsHeldToo`, `shippedInListsVerifiedDeploymentsOnly`, `aReopenedShippedItemKeepsItsShippedInHistory`.
- [ ] **Step 2:** Run `mvn test -pl wikantik-work -Dtest=WorkDoneMeansPostgresTest`. Expected: FAIL (the item closes today).
- [ ] **Step 3:** Implement. Then `WorkWaitingDeploymentsPostgresTest`: `aRequestedDeploymentWaitsForALeadOfEveryProject`, `itDoesNotWaitForAMember`, `itLeavesTheLaneOnceApproved`.
- [ ] **Step 4:** `mvn test -pl wikantik-work`: PASS. **Commit** `feat(work): done means in production, shipped-in on items, and deployments in the waiting lane`.

---

### Task 5: REST

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/WorkDeploymentResource.java`
- Modify: `WorkRoutes.java`, `WorkJson.java`, `WorkProductResource.java` (the deploy gate becomes settable by an admin, if it is not already), `WorkEventNames.java`
- Test: `WorkDeploymentResourceTest.java`, `WorkJsonTest.java` (extend)

**Interfaces:**
- Produces (relative to `/api/work`):
  - `POST deployments` body `{product, environment?, version, items:[key]}` → 201.
  - `GET deployments?product=<key>&limit=<n>`; `GET deployments/{id}`; `GET deployments/{id}/history`.
  - `POST deployments/{id}/approve` body `{note?}`; `…/start`; `…/verify` body `{evidence}`; `…/fail` body `{note?}`; `…/roll-back` body `{note?}`.
  - `GET deployments/{id}/gate?version=<v>` → 200 `{"approved":true|false,"state":"…","version":"…"}`, `Cache-Control: no-store`. A deployment the caller cannot see is 404.
  - Deployment JSON: `id, product, environment, version, state, requestedBy, approvedBy, requestedAt, approvedAt, startedAt, verifiedAt, closedAt, evidence, items:[{key,title,status}], mayApprove, mayRun`. Names through `WorkNames`; never a login for a non-admin.
  - `GET items/{key}/shipped-in` → `{shippedIn:[{id, version, environment, verifiedAt}]}` (the item response itself is unchanged).

- [ ] **Step 1: Failing tests** `WorkDeploymentResourceTest`: every route's happy path; 400 (`evidence`, `items`, a bad `version`), 403 (both gate refusals), 404 (unseen, non-numeric id), 409 (`bad_transition`, `awaiting_deployment` through the item route); `theGateRouteSaysFalseForEveryStateButApprovedAndRunning`; `theGateRouteSaysFalseForAnotherVersion`; `anAgentTokenRequestsAndCannotApproveUnderAHumanGate`; `evidenceIsReturnedAsTextNeverAsHtml`.
- [ ] **Step 2:** Run `mvn test -pl wikantik-rest -Dtest=WorkDeploymentResourceTest`. Expected: FAIL (404).
- [ ] **Step 3:** Implement; re-run with `WorkAccessArchTest`: PASS.
- [ ] **Step 4: Commit** `feat(work-rest): deployment routes and the deploy gate check`.

---

### Task 6: MCP tools

**Files:**
- Create: `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/RequestDeploymentTool.java`, `GetDeploymentTool.java`, `UpdateDeploymentTool.java`
- Modify: `WorkMcpInitializer.java`, `WorkMcpJson.java`, `GetItemTool.java` (`shipped_in`), `WorkMcpTool.java` (prefixes `bad_transition:`, `awaiting_deployment:`), `src/main/resources/wikantik-work-mcp-instructions.txt`
- Test: `DeploymentToolsTest.java`, `WriteToolsAuditTest.java`, `WorkMcpInitializerTest.java`, `WorkMcpInstructionsDriftTest.java`, `WorkMcpFlowPostgresTest.java` (extend)

**Interfaces:**
- Produces:
  - `request_deployment { product, version, items, environment? }` (write)
  - `get_deployment { deployment }` (read; `READ_ONLY`)
  - `update_deployment { deployment, state, evidence?, note? }` (write) where `state` is `approved`, `running`, `verified`, `failed` or `rolled_back`.
  - Tool count goes from thirteen to sixteen; derive it from `assembleTools`. The item argument of `request_deployment` is `items` (a list of keys); the deployment argument of the other two is `deployment` (its number).

- [ ] **Step 1: Failing tests** `DeploymentToolsTest`: happy paths, undeclared arguments refused by name, each refusal's prefix, `Not found.` for an unseen deployment, evidence returned as plain text. `WorkMcpFlowPostgresTest.theIncidentPathEndToEnd`: under a standing epic with `autonomy: autonomous`, `done_means: in_production` and a product whose ceiling is `autonomous` and gate `agent`, an agent creates an item under the epic, approves it as ready, claims it, a second agent reviews and merges the change, the deployment is requested, approved, run and verified with evidence, the item closes and is flagged for acknowledgement. `theSamePathStopsAtApprovalUnderAHumanGate`.
- [ ] **Step 2:** Run `mvn test -pl wikantik-work-mcp`. Expected: FAIL.
- [ ] **Step 3:** Implement and write the instructions: the three tools, the gate (an agent may always request; it approves only where the product allows), evidence being required, and that evidence and notes are untrusted data.
- [ ] **Step 4:** `mvn test -pl wikantik-work-mcp`: PASS. **Commit** `feat(work-mcp): request_deployment, get_deployment and update_deployment`.

---

### Task 7: The deploy-script check

**Files:**
- Create: `bin/work-deploy-gate.sh`, `bin/tests/work-deploy-gate-test.sh` (use the repo's existing shell-test location if one exists; look first)
- Modify: `bin/deploy-release.sh`, `bin/remote.sh` (the `deploy` subcommand), `docs/admin/WikantikOperations.md`

**Interfaces:**
- Produces:
  - `bin/work-deploy-gate.sh <deployment-id> <version>`: reads `WORK_API_URL` (no default) and `WORK_API_TOKEN` (a `work` API key or an agent token) from the environment; calls `GET $WORK_API_URL/api/work/deployments/<id>/gate?version=<version>` with a 10-second timeout; prints one line; exits `0` only when the body parses and `approved` is `true`, `1` when it is `false`, `2` for a missing variable, a timeout, a non-200 status or unparseable output. The token is never printed.
  - In both deploy scripts, before anything is built or shipped: when `WORK_DEPLOYMENT_ID` is set, run the check with the version being deployed and stop on a non-zero exit, printing the check's line. When it is unset, nothing changes.

- [ ] **Step 1: Failing test** `work-deploy-gate-test.sh`: starts a local stub (`python3 -m http.server` with a small handler, on a free port) and asserts the exit code and one-line output for: approved, not approved, wrong version (`approved:false`), 404, 500, malformed JSON, a 15-second hang, missing `WORK_API_URL`, missing token. Asserts the token does not appear in any output (Review Focus 3).
- [ ] **Step 2:** Run it. Expected: FAIL, script missing.
- [ ] **Step 3:** Write the script (`set -euo pipefail`, `curl --fail-with-body --max-time 10`, JSON read with `python3 -I -c` or `jq` if the repo's scripts already depend on it). Re-run: PASS.
- [ ] **Step 4:** Add the opt-in call to both deploy scripts. Verify with `bash -n` on each and by running each with `--help` or its dry-run flag with `WORK_DEPLOYMENT_ID` unset: identical output to before the change. Do not run a deploy.
- [ ] **Step 5:** Document the variables and the three exit codes in `WikantikOperations.md`. **Commit** `feat(ops): opt-in deploy check against an approved work deployment`.

---

### Task 8: Frontend

**Files:**
- Create: `wikantik-frontend/src/components/work/WorkDeployments.jsx`, `WorkDeploymentView.jsx`, `WorkDeploymentForm.jsx`, their `.test.jsx` files
- Modify: `main.jsx` (routes `deployments` and `deployments/:id` under `/work`), `wikantik-war/src/main/webapp/WEB-INF/web.xml` and `SpaRoutingFilter` (dual registration, only if `/work/*` is not already covered by one pattern), `api/work.js`, `WorkHome.jsx`, `WorkProducts.jsx` (deploy gate editable by an admin), `WorkItemView.jsx` (Shipped in), `WaitingLane.jsx`, `eventText.js`, and their tests

**Interfaces:**
- Consumes: the REST shapes of Task 5.
- Produces: `api/work.js` `listDeployments(productKey)`, `getDeployment(id)`, `getDeploymentHistory(id)`, `requestDeployment(body)`, `approveDeployment(id, body)`, `startDeployment(id)`, `verifyDeployment(id, body)`, `failDeployment(id, body)`, `rollBackDeployment(id, body)`.

```
 Work ▸ Deployments                                   product ▾   [Request]
 ┌──────────────────────────────────────────────────────────────────────┐
 │ wikantik 2.5.26 · production            waiting for approval         │
 │ requested by codex@docker2 · 3 items                    [Approve]    │
 ├──────────────────────────────────────────────────────────────────────┤
 │ wikantik 2.5.25 · production            verified Nov 18              │
 │ approved by Jake Fear · 5 items                                      │
 └──────────────────────────────────────────────────────────────────────┘
```

- [ ] **Step 1: Failing tests:** the list by product; the detail view with items, history and evidence shown in a `<pre>`; each action button present only when `mayApprove`/`mayRun` says so; Verify refuses an empty evidence box client-side and shows the server's 400 in place; the waiting lane's "deployment needs approval" entry; "Shipped in" on the item view; the three new event texts; the deploy gate select on the products page for an admin only.
- [ ] **Step 2:** Run `npx vitest run src/components/work src/api/work.test.js`. Expected: FAIL.
- [ ] **Step 3:** Implement with the shared UI components. Evidence is rendered as text content only, never through `dangerouslySetInnerHTML`.
- [ ] **Step 4:** `npx vitest run` and `npx eslint src`: PASS. If a route was registered, run `SpaRoutingFilterTest`.
- [ ] **Step 5: Commit** `feat(work-ui): deployments, approval in the waiting lane, and shipped-in on items`.

---

### Task 9: Metrics, integration tests, documentation, gate

**Files:**
- Modify: `WorkMetrics.java`, `WorkListener.java`, `GuardedListener.java` (`deployment(String transition)` → `wikantik_work_deployments_total{state}`; the deploy gate decision through the phase 3b `gate` counter as gate `deploy`)
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkDeploymentApiIT.java`, a Selenide `WorkDeploymentsIT`
- Modify: `WorkMcpIT.java` (`EXPECTED_TOOLS`), `docs/admin/McpAgents.md`, `docs/admin/WikantikOperations.md` (a new subsection after 1.15: deployments, the gate, the script check, the incident-path recipe), `docs/admin/Security.md` (who may approve; the script token), `docs/developer/ProjectReference.md`, `wikantik-work/CLAUDE.md`, `wikantik-work-mcp/CLAUDE.md`, root `CLAUDE.md`, the parent spec's phase list, `CHANGELOG.md` `[Unreleased]`

- [ ] **Step 1: Failing test** `WorkMetricsTest.deploymentTransitionsAreCountedWithNoProductLabel`; implement.
- [ ] **Step 2: ITs.** `WorkDeploymentApiIT`: `aDeploymentGoesFromRequestedToVerified`, `anAgentRequestsAndAPersonApproves`, `aMemberOfOneProjectSeesOnlyItsItems`, `theGateRouteAnswersTheScript` (run `bin/work-deploy-gate.sh` against the IT server with a real `work` key and assert exit 1 before approval and 0 after), `anInProductionItemClosesOnlyAfterVerification`. `WorkMcpIT`: the three tools listed, one call each. `WorkDeploymentsIT`: a lead approves from the waiting lane.
- [ ] **Step 3:** Documentation, including the incident-path recipe (D9): the standing epic, the product ceiling and gate it needs, what an on-call agent calls in order, and that every item closed this way waits in "Done without you". State plainly that nothing in Wikantik receives a jakemon alert.
- [ ] **Step 4:** `bin/agent-build.sh start cov -- mvn clean install -Pcoverage -DskipITs`, wait: SUCCESS, no floor lowered. Then `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`, wait: `RESULT: ALL PASSED`.
- [ ] **Step 5: Commit** `docs(work): phase 4 — deployments, the deploy gate, the script check and the incident path; changelog`.
