# Work Management for Small Teams — Part 5: Code-host integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A product's repository posts signed GitHub or generic webhook deliveries to one endpoint, and its pushes, pull requests and reviews become change records with real commit times, without the wiki ever calling the code host; merges outside policy wait for a lead.
**Architecture:** One receive-only servlet (`WorkHookResource`) checks the feature flag and body size, asks `WorkService.features().codeHost().authenticate` (the HMAC check runs inside `wikantik-work`, so the secret never leaves the module), translates the body with a GitHub or generic adapter into neutral `CodeEvent`s, and hands them to `codeHost().apply`. Applying resolves item keys and people first, then writes each change in its own transaction under a per-product advisory lock (change rows, then the item row), with a new `system` actor kind on the events. Flagged merges join the leads' waiting lane through `WorkChangesService.acknowledgeFlag`.
**Tech Stack:** Java 25, PostgreSQL 18 (`Jdbc`, `PostgresTestDb`, `FaultInjectingDataSource`), JUnit 5, Mockito, Gson (adapters, in `wikantik-rest`), `javax.crypto.Mac` (HMAC-SHA256), React 19 + vitest, Cargo IT.
**Spec:** docs/superpowers/specs/2026-11-21-work-team-features-design.md, section 5 (decisions D14–D18); index and global constraints: docs/superpowers/plans/2026-11-21-work-team-features.md
**Depends on:** Nothing from Parts 1–4 is required. Optional: Part 3 (notifications). If `wikantik-work/src/main/java/com/wikantik/work/Recipients.java` exists when Task 5.3c runs, this part adds one rule to `Recipients.of(WorkEvent, EventFacts)`; otherwise Task 5.3c records the rule for Part 3 (see 5.3c). Part 6 consumes `work_change.first_commit_at`, `merge_kind`, `policy_flag`, `flag_acknowledged_at` and `superseded_by` as produced here.

## Cross-part rulings

Added when the eight part plans were reconciled (index: `docs/superpowers/plans/2026-11-21-work-team-features.md`). **Where a ruling and the text below disagree, the ruling wins.**

- **R1 — new facades hang off `WorkService.features()`, not `WorkService`.** `WorkService` is at the PMD `TooManyMethods` limit (40). Part 1 creates `com.wikantik.work.WorkFeatures` (built in `WorkService`'s constructor, returned by the one new method `WorkService.features()`); every facade accessor this plan calls `WorkService.x()` is `WorkService.features().x()`. `WorkServiceVisibilityPostgresTest` treats `WorkFeatures`' accessors as facades (its `FACADES` set). No suppression, no baseline entry.
- **R4 — superseded changes.** `superseded_by` (P5-2) is honoured by Part 6: every count over `work_change` excludes superseded rows. If Part 6 is already built when this part runs, this part adds that predicate and its test to Part 6's queries.
- **R5 — flagged merges.** Task 5.3c's check stands: with Part 3 present, add `change_flagged` → leads to `Recipients`; without it, do nothing (Part 3 adds it).

## What this part builds on

Verified in the code on 2026-11-21:

- `work_change` (V081, `bin/db/migrations/V081__work_changes_and_acknowledgement.sql`): `opened_by TEXT NOT NULL`, `merge_kind` CHECK, `work_change_merge_kind_chk ((state = 'merged') = (merge_kind IS NOT NULL))`, unique live index `idx_work_change_live_branch ON (item_id, branch) WHERE state IN ('open','in_review')`, `head_agent_ids BIGINT[]`. Grants `SELECT, INSERT, UPDATE`.
- `Change` (`wikantik-work/.../Change.java`): 21-component record ending `headAgentIds`; `approvedForHead()`, `live()`. Constructed in `JdbcWorkChanges.ROW`, `WorkGatesTest`, `WorkChangeResourceTest` only.
- `ChangeDraft(branch, baseBranch, headSha, reviewUrl, mergedSha)`: 19 constructor calls (service, `WorkChangeJson.draft`, `OpenChangeTool`, seven test classes).
- `ChangeInputs` (`sha`, `requiredSha`, `branch`, `url`, `note`), `ChangeState.mayBecome`, `MergeKind {REVIEWED, UNREVIEWED, DIRECT}`.
- `WorkChangeStore` / `JdbcWorkChanges`: every write is `write(item, id, by, body)`: lock the change `FOR UPDATE`, refuse a closed one (`change_closed`), `lockItem` (`FOR SHARE`, fences agents), body, one event via `catalog.appendEvent` with `from_value` `change:<id>`. Opening records `opened_by = by.login()`; merging records `merged_by = by.login()`.
- `WorkChangesService(WorkStore, WorkScopes)` (package-private ctor), built in the `WorkService` constructor; `merge` resolves `WorkAutonomy.resolve(store.policyChain(id))` before the transaction and decides on the locked row.
- `Actor(login, groups, admin, Kind, agentId, sponsor)`, `enum Kind { HUMAN, AGENT }`. No `switch` on `Kind` anywhere; the kind is tested by `==` in ~30 places. Kind is written by `SqlValues.actorKindWire` (`AGENT ? "agent" : "user"`) and read back by `"agent".equals(...) ? AGENT : HUMAN` in `JdbcWorkStore` (line 87, events), `JdbcWorkCatalog.productEvents` (line 422), `JdbcWorkDeployments` (line 60). The only `actor_kind` CHECK is on `work_deployment_event` (V082); `work_event` and `work_product_event` have none.
- `JdbcWorkCatalog.requireAttributed(Kind, Long)` is called by every store writer (items, comments, links, claims, members, changes, deployments, projects, products) and by `appendEvent`. `JdbcWorkStoreAttributionPostgresTest` pins it.
- `WorkStanding.roleIn(long, Actor)` / `visibleProjects(Actor)`; `WorkPolicy.may(Actor, WorkRole, Action, Item)`; `Action` enum (constant order is stable; `WorkPolicyActionTableTest` compares every action to `src/test/resources/com/wikantik/work/policy-action-table.golden`; `WorkPolicyTest.adminMayDoEverythingWithAnyRole` loops `Action.values()`).
- `WaitingItem(Item, String reason, WorkEvent handoff)` with `ASSIGNED`, `MENTIONED`, `ACKNOWLEDGE`; `WorkService.waitingOn` appends `Acknowledgements.awaiting(actor, visible)` (projects the actor leads, archived skipped; all for an admin). `WorkMeResource` renders `waiting.reason`; `WaitingLane.jsx` groups by reason.
- Products: `WorkService.createProduct/updateProduct/archiveProduct` need `scopes.requireInstance(actor, Action.MANAGE_INSTANCE)` (a wiki admin); changes are rows in `work_product_event` written by `JdbcWorkCatalog.appendProductEvent` (private). `WorkAudit.record(actorLogin, action, targetType, target, detail)` is the audit port (`WorkRegistryService` uses it).
- `WorkDirectory` (port; default methods allowed, implemented by `com.wikantik.work.runtime.WikiWorkDirectory`). `UserDatabase.findByEmail` is exact and case-sensitive (`JDBCUserDatabase.FIND_BY_EMAIL = "SELECT * FROM users WHERE email=?"`).
- `WorkListener` (default no-op methods) → `GuardedListener` → `WorkMetrics` (`wikantik_work_deployments_total{state}` is the pattern).
- `WorkServiceVisibilityPostgresTest` classifies methods **by name** into `WRITES`, `READS`, `SYSTEM` (act for no caller), `INSTANCE_READS` (wiki-admin only), `FACADES`. `verify` is already a write name (deployments), so the HMAC check must be named otherwise.
- `WorkAccessArchTest` (`wikantik-rest` tests) forbids classes outside the exact package `com.wikantik.work` from depending on the stores; `WorkStandingSourceRuleTest` and `WorkAutonomySourceRuleTest` scan `src/main/java/com/wikantik/work` with `Files.list` (not recursive). **A `codehost` subpackage would escape both source rules, break the arch rule, and lose package-private access** — this part stays in `com.wikantik.work`.
- `CsrfProtectionFilter` (`wikantik-http/.../filter/CsrfProtectionFilter.java`): `/api/*` is checked by Origin only (`isRestApiEndpoint` → `isOriginAllowed`, which allows a request with **no** `Origin`); the token check applies to non-REST paths; `/sso/callback` is the only named exemption.
- `/api/work/*` servlets extend `WorkResourceBase` (401 when there is no actor). `WorkApiKeyFilter` passes requests with no bearer header untouched. Config keys live in `wikantik-main/src/main/resources/ini/wikantik.properties` (`[Work]` section, line ~2390); rest-side readers use `TextUtil.getBooleanProperty/getIntegerProperty` (as `WorkApiKeyFilter.PROP_RATE_LIMIT`). `CapabilitiesResource` reports `work`.
- IT: `wikantik-it-tests/wikantik-it-test-rest`, helper `WorkIt` (`admin()`, `account(admin, login, display)` creates `login@example.com`), cascade `src/test/config/it-rest-cascade.properties`.

## Decisions

- **P5-1 A default-branch push makes at most one direct change per item, with `merged_sha` = the push's `after`.** Only commits the host marks `distinct` (GitHub's "first time this commit reached the repository") can make one; `first_commit_at` is the earliest of them and `head_sha` the last. Why: GitHub sets `merge_commit_sha` to the commit the base branch was updated to, which is the push's `after` for merge, squash and rebase merges alike, so one key matches the pull request's merge in either order; and the commits of a merged branch arrive in the push as non-distinct. Cost if wrong: a commit first pushed to a branch with no item key and then fast-forwarded onto the default branch by hand is never seen as direct (D17 under-flags it); documented.
- **P5-2 A rebase merge whose push arrives first is not rewritten; the direct row is marked superseded.** New column `superseded_by`; the pull request's change is merged as usual and keeps everything agents and people reported on it. A superseded row leaves the waiting lane and must be left out of every count (Part 6). Cost: one extra row an item's history shows as "recorded again as change N".
- **P5-3 A head a change has already had is never set again** (checked against the change's own `change_opened`/`change_updated` events). Why: a redelivered old push or `synchronize` would otherwise move the head back and clear a standing approval. Cost: a deliberate force-push back to an old commit is ignored.
- **P5-4 Deliveries for one product are applied one at a time**: every code-host transaction first takes `pg_advisory_xact_lock(1464550216, product_id)` (two-int key space, which never overlaps the audit chain's one-bigint key). Only code-host transactions take it, before any row lock, so no cycle with the change→item order. Cost: deliveries of one product queue behind each other; volume is low.
- **P5-5 A non-draft pull request opened or reopened is `in_review`; a draft is `open`** until `ready_for_review` or `review_requested`. (The outline says so; the design table is silent on non-drafts.)
- **P5-6 The merge's policy is resolved before the transaction and decided on the locked row**, exactly as `WorkChangesService.merge` does today (`PolicyChains.load` reads on its own connection, so "under the item lock" is not available without a new store path).
- **P5-7 The hook secret is stored as issued** (HMAC verification needs it; unlike agent tokens it cannot be hashed). It is shown once; `POST …/hook` again replaces it (rotation); `DELETE` stamps `disabled_at`. Each writes a `work_product_event` row (field `hook`, to `enabled`/`rotated`/`disabled`, never the secret) and a `WorkAudit` record `work.product.hook.enable|rotate|disable`.
- **P5-8 A failed directory lookup answers 503 and records nothing** (`WorkUnavailableException`), so the code host shows a failed delivery that can be redelivered; people are resolved before any transaction opens.
- **P5-9 The system actor is shown as `GitHub` or `Code host`** (`actorName`), with a `code host` badge where agents get `agent`: "GitHub merged a change without review at 9b01c77". `actor` stores `codehost:github` / `codehost:generic`.
- **P5-10 A closed item gets no new live change from the code host** (outcome `ignored`), matching the module's own refusal; a direct commit naming a closed item **is** recorded (it is merged, so it holds nothing).
- **P5-11 Dismissed GitHub reviews are not processed.** Cost: an approval dismissed by hand and merged without a new one counts as reviewed.
- **P5-12 The host comes from the URL** (`github` or `generic`), not from the body; learned usernames are kept per host, so a generic sender cannot teach GitHub identities.
- **P5-13 A `reopened` delivery opens a new change only when its time is after the abandoned change's `closed_at`**, so a redelivered old `reopened` does nothing.
- **P5-14 A flagged merge writes a second event, `change_flagged`** (to `unreviewed` or `direct`), in the merge's transaction. Notifications route that type to leads; no extra facts are needed.
- **P5-15 `authenticate` always computes one HMAC** (with a fixed dummy key when the product or hook is missing) before answering, so response time does not tell an unknown product from a bad signature.
- **P5-16 When the product names a repository, a delivery from another repository is ignored** (logged `repo_mismatch`); the product's `default_branch`, else the delivery's own, decides what is the default branch.
- **P5-17 Code-host head moves never add to `head_agent_ids`** (the system actor has no agent id); approvals are still cleared by them.

## Review Focus

1. **Forged or tampered delivery** — unknown product, no hook, disabled hook, missing or wrong signature, feature off: byte-identical 404, nothing recorded, `last_received_at` untouched. Task 5.2b (`WorkCodeHostAuthPostgresTest`), Task 5.4b (`WorkHookResourceTest.everyRejectionIsTheSame404`).
2. **A pull-request merge that also arrives as a default-branch push, in either order, for merge, squash and rebase merges** — exactly one merged change that is not superseded, the right kind, the right flag. Task 5.2d (`WorkCodeHostMergeOrderPostgresTest`).
3. **Redelivery and out-of-order delivery** — every fixture applied twice changes nothing; an old head never comes back; an old `reopened` opens nothing; an approval for a superseded commit records nothing. Task 5.2c and 5.2d (`WorkCodeHostIdempotencePostgresTest`).
4. **Reconciliation** — a change an agent opened by MCP keeps `opened_by_agent_id`, `head_agent_ids`, its reviewer and merger; the code host only fills nulls. Task 5.2c (`anAgentsChangeIsAdoptedNotDuplicated`).
5. **D17** — a matched person's unreviewed merge is flagged; a direct commit is flagged exactly when direct commits are not allowed; a member cannot see or acknowledge it, a lead can. Tasks 5.2d, 5.3a.
6. **The system actor gaining a foothold** — refused by every existing store writer, by `WorkPolicy`, and has no standing. Task 5.1b.

---

### Task 5.1a: Schema V088

**Files:** Create `bin/db/migrations/V088__work_codehost.sql`, `wikantik-work/src/test/java/com/wikantik/work/WorkCodeHostSchemaTest.java`. Modify `wikantik-work/src/test/java/com/wikantik/work/WorkAgentFixtures.java` (`TABLES` gains `work_codehost_identity` and `work_product_hook` before `work_product`).

**Interfaces — produces (verbatim DDL; use the next free number if V088 is taken):**
```sql
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS first_commit_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'reported'
  CHECK (source IN ('reported','codehost'));
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS external_opened_by TEXT;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS external_reviewed_by TEXT;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS external_merged_by TEXT;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS review_number TEXT;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS policy_flag TEXT CHECK (policy_flag IN ('unreviewed','direct'));
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS flag_acknowledged_by TEXT;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS flag_acknowledged_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS superseded_by BIGINT REFERENCES work_change (id);
ALTER TABLE work_change ALTER COLUMN opened_by DROP NOT NULL;
ALTER TABLE work_change DROP CONSTRAINT IF EXISTS work_change_opener_chk;
ALTER TABLE work_change ADD CONSTRAINT work_change_opener_chk
  CHECK (opened_by IS NOT NULL OR external_opened_by IS NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS idx_work_change_direct ON work_change (item_id, merged_sha)
  WHERE merge_kind = 'direct';
CREATE INDEX IF NOT EXISTS idx_work_change_flag ON work_change (item_id)
  WHERE policy_flag IS NOT NULL AND flag_acknowledged_at IS NULL AND superseded_by IS NULL;
CREATE INDEX IF NOT EXISTS idx_work_change_review ON work_change (item_id, review_number)
  WHERE review_number IS NOT NULL;
CREATE TABLE IF NOT EXISTS work_product_hook (
  product_id BIGINT PRIMARY KEY REFERENCES work_product (id),
  secret TEXT NOT NULL, created_by TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  last_received_at TIMESTAMP WITH TIME ZONE, disabled_at TIMESTAMP WITH TIME ZONE);
CREATE TABLE IF NOT EXISTS work_codehost_identity (
  host TEXT NOT NULL, username TEXT NOT NULL, login TEXT NOT NULL,
  learned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(), PRIMARY KEY (host, username));
GRANT SELECT, INSERT, UPDATE ON work_product_hook, work_codehost_identity TO :app_user;
```
Header comment in the V081 style: what each column is for, that the secret is stored as issued (HMAC needs it), and that nothing is deleted.

- [ ] **Step 1: Failing test** `WorkCodeHostSchemaTest` (model on `WorkChangesSchemaTest`: insert rows with `WorkAgentFixtures.exec`): `aChangeNeedsAnInsideOrOutsideOpener` (both null → `23514`; either alone inserts), `anUnknownSourceOrFlagIsRefused` (`23514`), `oneDirectChangePerItemAndCommit` (second `direct` row with the same `(item_id, merged_sha)` → `23505`; a `reviewed` row with that sha inserts), `theFlagIndexLeavesOutAcknowledgedAndSuperseded` (query `pg_indexes.indexdef` contains all three predicates), `aHookBelongsToOneProduct` (second row for a product → `23505`), `theAppRoleCannotDeleteHooksOrIdentities` (`has_table_privilege(:app_user, …, 'DELETE')` false, as `WorkDeploymentsSchemaTest.theAppRoleCannotUpdateOrDeleteHistory` does), `reapplyingIsANoOp` (run the file's text through psql twice; second run raises nothing).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=WorkCodeHostSchemaTest` → FAIL (column `first_commit_at` does not exist).
- [ ] **Step 3:** Write the migration; add the two tables to `WorkAgentFixtures.TABLES`.
- [ ] **Step 4:** Re-run → PASS. `mvn test -pl wikantik-work -Dtest='WorkChangesSchemaTest,WorkChangeGatesPostgresTest'` still PASS.
- [ ] **Step 5: Commit** `git add bin/db/migrations/V088__work_codehost.sql wikantik-work/src/test/java/com/wikantik/work/WorkCodeHostSchemaTest.java wikantik-work/src/test/java/com/wikantik/work/WorkAgentFixtures.java` — `feat(work): V088 code-host change columns, product hook and learned identities`.

### Task 5.1b: The `system` actor kind

**Files:** Modify `Actor.java`, `SqlValues.java`, `JdbcWorkCatalog.java`, `JdbcWorkStore.java`, `JdbcWorkDeployments.java`, `WorkPolicy.java`, `WorkStanding.java`. Test `ActorTest.java`, `JdbcWorkStoreAttributionPostgresTest.java`, `WorkStandingPostgresTest.java`, `WorkPolicyTest.java`, `WorkServiceVisibilityPostgresTest.java` (all under `wikantik-work/src/{main,test}/java/com/wikantik/work/`).

**Interfaces — produces:**
- `enum Kind { HUMAN, AGENT, SYSTEM }`; `public static Actor system(String host)` → `new Actor("codehost:" + host, Set.of(), false, Kind.SYSTEM, null, null)`. Compact constructor adds: a `SYSTEM` actor with a non-null `agentId` or non-empty groups → `IllegalArgumentException`.
- `SqlValues.actorKindWire(Kind)` → `"agent"`, `"system"`, else `"user"`; new `static Actor.Kind actorKind(String wire)` (`"agent"`→AGENT, `"system"`→SYSTEM, anything else HUMAN), used by the three readers listed above.
- `JdbcWorkCatalog.requireAttributed(Kind, Long)`: `SYSTEM` → `IllegalArgumentException("a system actor records only code-host changes")`. New `static void requireEventAttribution(Kind, Long)`: the old two rules plus "a system event carries no agent id"; `appendEvent` calls this one instead. Every existing writer keeps calling `requireAttributed`, so all of them refuse the system actor; only the new `JdbcWorkCodeHost*` classes (Task 5.2c) call `requireSystem(Actor)` and write through `appendEvent`.
- `WorkPolicy.may`: first line `if (actor.kind() == Actor.Kind.SYSTEM) return Decision.deny("The code host acts only through its webhook.");`
- `WorkStanding.roleIn` → `Optional.empty()` and `visibleProjects` → `List.of()` for `SYSTEM`.
- Not changed, on purpose: the `work_deployment_event.actor_kind` CHECK in V082 (the system actor never writes deployments; `JdbcWorkDeployments.request/move` refuse it through `requireAttributed`).

- [ ] **Step 1: Failing tests.** `ActorTest`: `aSystemActorIsNeverAdminAndHasNoAgentIdOrGroups` (flag dropped; `new Actor("x", Set.of("g"), false, SYSTEM, null, null)` throws; with agent id 3 throws; `Actor.system("github").login()` is `codehost:github`). `JdbcWorkStoreAttributionPostgresTest.aSystemActorIsRefusedByEveryExistingWriter`: `createItem`, `updateItem`, `addComment`, `addLink`, `claims().claim…`, `changes().open`, `putMember`, `deployments().request` each throw `IllegalArgumentException`; `work_event` row count unchanged. `WorkStandingPostgresTest.aSystemActorHasNoStanding` (a member row whose principal is the literal `codehost:github` still gives nothing). `WorkPolicyTest.aSystemActorMayDoNothing` (every `Action.values()` × every role → denied with that text). `WorkServiceVisibilityPostgresTest`: add `Actor.system("github")` to the strangers every read is driven as.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='ActorTest,JdbcWorkStoreAttributionPostgresTest,WorkStandingPostgresTest,WorkPolicyTest,WorkServiceVisibilityPostgresTest'` → FAIL (no `SYSTEM`).
- [ ] **Step 3:** Implement. Then `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-work-mcp,wikantik-main`.
- [ ] **Step 4:** Re-run → PASS; `WorkPolicyActionTableTest` unchanged (it builds no system actor).
- [ ] **Step 5: Commit** (the files above by name) — `feat(work): system actor kind, refused everywhere but code-host change writes`.

### Task 5.1c: Showing the system actor

**Files:** Modify `wikantik-rest/src/main/java/com/wikantik/rest/WorkJson.java`, `WorkNames.java`; `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/WorkMcpJson.java`; `wikantik-frontend/src/components/work/EventActor.jsx`, `eventText.js`. Test `WorkJsonTest.java`, `wikantik-work-mcp/.../WorkMcpJsonTest.java` (create if absent, else extend the class that tests `event`), `EventActor` case in `WorkTimeline.test.jsx`, `eventText.test.js`.

**Interfaces — produces:**
- `WorkNames.systemLabel(String stored) : String` → `codehost:github` → `GitHub`, any other `codehost:*` → `Code host`. In `WorkJson.enrich`, for key `actor` when `actorKind` is `system`: put `actorName` = the label and replace `actor` with it for every viewer (it is not a login); the directory is never asked.
- MCP `event`: `actorKind` `system`, `actor` the same label.
- `EventActor`: `actorKind === 'system'` → `<span className="work-agent-badge">code host</span>` after the name. `actorLabel` falls back to `'The code host'` for a system event with no name.

- [ ] **Step 1: Failing tests.** `WorkJsonTest.aSystemEventIsNamedForItsHostAndNeverLookedUp` (names function is a mock that fails the test if called with `codehost:github`; `actorName` and `actor` are `GitHub` for admin and non-admin). MCP: `actorKind` is `system`, actor `GitHub`. vitest: the badge text `code host`; `eventSentence` of a system `change_merged` is `GitHub merged a change without review at 9b01c77`.
- [ ] **Step 2:** `mvn test -pl wikantik-rest -Dtest=WorkJsonTest`; `mvn test -pl wikantik-work-mcp -Dtest=WorkMcpJsonTest`; `cd wikantik-frontend && npx vitest run src/components/work/eventText.test.js src/components/work/WorkTimeline.test.jsx` → FAIL.
- [ ] **Step 3:** Implement. `npm run lint`.
- [ ] **Step 4:** Re-run → PASS.
- [ ] **Step 5: Commit** — `feat(work): show code-host events as GitHub or Code host`.

### Task 5.1d: The new change columns and `first_commit_at` reported by hand

**Files:** Modify `Change.java`, `ChangeDraft.java`, `ChangeInputs.java`, `WorkChangeStore.java`, `JdbcWorkChanges.java`, `WorkChangesService.java`, `WorkService.java` (pass `clock` to `WorkChangesService`); `wikantik-rest/.../WorkChangeJson.java`, `WorkChangeResource.java`, `WorkItemResource.java` (`openChange`); `wikantik-work-mcp/.../OpenChangeTool.java`, `UpdateChangeTool.java`, `WorkMcpChanges.java`, `src/main/resources/wikantik-work-mcp-instructions.txt`. Tests: `WorkGatesTest`, `WorkChangeResourceTest` (constructor update), `WorkChangeGatesPostgresTest`, `WorkChangeResourceTest`, `wikantik-work-mcp/.../ChangeToolsPostgresTest.java`, `WriteToolsAuditTest` unchanged.

**Interfaces — produces:**
- `Change` gains, after `headAgentIds`: `Instant firstCommitAt, String source, String externalOpenedBy, String externalReviewedBy, String externalMergedBy, String reviewNumber, String policyFlag, String flagAcknowledgedBy, Instant flagAcknowledgedAt, Long supersededBy`. `JdbcWorkChanges.COLUMNS` and `ROW` gain them; make `COLUMNS`, `SELECT` and `ROW` package-private `static final` so Task 5.2c reuses them.
- `ChangeDraft` gains `String firstCommitAt` (ISO-8601); keep a second constructor with the old five arguments that passes `null`, so the 19 existing calls compile unchanged.
- `ChangeInputs.firstCommitAt(String raw, Instant now) : Instant` — null/blank → null; unparseable → `WorkValidationException("firstCommitAt", "Give the first commit time as an ISO-8601 instant.")`; later than `now` → `("firstCommitAt", "A first commit cannot be in the future.")`.
- `WorkChangeStore.update(Item, long id, String headSha, String reviewUrl, Instant firstCommitAt, Check, Actor)` replaces the six-argument form (callers: `WorkChangesService`, `WorkChangeRacePostgresTest`). Inside the locked row: a non-null `firstCommitAt` after `locked.closedAt()` of a merged change → `WorkValidationException("firstCommitAt", "The first commit comes before the merge.")`; stored as `LEAST(COALESCE(first_commit_at, ?), ?)` (only moves earlier); a call that changes nothing writes no event (as today). `open` stores the draft's value (with `mergedSha`, it must not be after now).
- `WorkChangesService.update(Actor, long, String headSha, String reviewUrl, String firstCommitAt)`; the four-argument `update` delegates with `null`.
- REST: `POST /api/work/items/{key}/changes` and `PATCH /api/work/changes/{id}` accept `firstCommitAt`; `WorkChangeJson.DRAFT_FIELDS` gains it. Change JSON gains every new field (Gson does it); `WorkJson.NAME_KEYS` gains `flagAcknowledgedBy` (a login).
- MCP: `open_change` and `update_change` gain argument `first_commit_at` ("The time of the branch's first commit, ISO-8601; only moves earlier."); `WorkMcpChanges.change` gains `first_commit_at`, `source`, `review_number`, `policy_flag`, `flag_acknowledged` (boolean), `superseded_by`; `opened_by`/`reviewed_by`/`merged_by` fall back to `"<external name> (outside the wiki)"` when the inside value is null. Instructions text: describe `first_commit_at`, and add "When the code host reports changes, `merge_change` on a change it already merged answers `change_closed:`; read the change with `get_item`."

- [ ] **Step 1: Failing tests.** `WorkChangeGatesPostgresTest`: `aReportedFirstCommitTimeOnlyMovesEarlier` (open with 10:00, update 11:00 → still 10:00, update 09:00 → 09:00, one `change_updated` event per actual move), `aFutureFirstCommitIsRefused` (field `firstCommitAt`), `aFirstCommitAfterTheMergeIsRefused`. `WorkChangeResourceTest`: round-trip of `firstCommitAt`; an unknown field is still refused by name. `ChangeToolsPostgresTest.firstCommitAtIsAccepted` and `opened_by` fallback for a row with only `external_opened_by` (insert it with SQL).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkChangeGatesPostgresTest,WorkGatesTest,WorkChangeRacePostgresTest'` and `mvn test -pl wikantik-rest -Dtest=WorkChangeResourceTest`, `mvn test -pl wikantik-work-mcp -Dtest=ChangeToolsPostgresTest` → compile FAIL.
- [ ] **Step 3:** Implement; `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-work-mcp`.
- [ ] **Step 4:** Re-run → PASS; `mvn test -pl wikantik-work-mcp -Dtest='WorkMcpInstructionsDriftTest,WriteToolsAuditTest'` PASS.
- [ ] **Step 5: Commit** — `feat(work): first commit time and code-host columns on change records`.

### Task 5.1e: The neutral event and the key scan

**Files:** Create `wikantik-work/src/main/java/com/wikantik/work/CodeEvent.java`, `ItemKeyScan.java`; tests `ItemKeyScanTest.java`, `CodeEventTest.java`.

**Interfaces — produces:**
```java
public record CodeEvent( Kind kind, String repo, String defaultBranch, String branch, String baseBranch,
                         String before, String after, Instant at, List< Commit > commits, Review review,
                         Person actor, String mergedSha ) {
    public enum Kind { PUSH, REVIEW_OPENED, REVIEW_READY, REVIEW_UPDATED, REVIEW_APPROVED, REVIEW_MERGED, REVIEW_CLOSED }
    public record Commit( String sha, String message, Instant at, boolean distinct, Person author ) { }
    public record Review( String number, String url, String title, String headSha, boolean draft, Person author ) { }
    public record Person( String username, String name, String email ) { }
}
```
`commits` copied with `List.copyOf` (null → empty); `actor` is the reviewer for `REVIEW_APPROVED`, the merger for `REVIEW_MERGED`; `at` is the host's time of the event (PR `updated_at`, review `submitted_at`, push head commit time).
- `ItemKeyScan.keys(String text) : List<String>` — distinct matches of `(?<![A-Z0-9])[A-Z][A-Z0-9]{1,9}-[1-9][0-9]{0,8}(?![0-9])`, in order, at most 10; null → empty. `ItemKeyScan.reviewNumbers(String message) : Set<String>` — every `#(\d{1,9})` (squash `(#12)`, merge `Merge pull request #12 from …`).

- [ ] **Step 1: Failing tests.** `ItemKeyScanTest`: `PLAT-15-fix-login` → `[PLAT-15]`; `feature/PLAT-15` → `[PLAT-15]`; `Fix PLAT-15 and OPS-2` → `[PLAT-15, OPS-2]`; `XPLAT-15` → `[XPLAT-15]`; `plat-15` → `[]`; `PLAT-0` → `[]`; `UTF-8` → `[UTF-8]` (the scan returns it; Task 5.2c proves it is ignored); 12 keys → first 10; `PLAT-15 PLAT-15` → one. `reviewNumbers("Fix login (#12)")` → `{12}`; `"Merge pull request #12 from o/PLAT-15-x"` → `{12}`. `CodeEventTest`: null commits become an empty list; the list is unmodifiable.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='ItemKeyScanTest,CodeEventTest'` → FAIL.
- [ ] **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** — `feat(work): neutral code-host event and item key scan`.

### Task 5.2a: People by email, and learned usernames

Run after Task 5.2b: `CodeHostIdentities` uses `WorkCodeHostStore.learned/learn` from it. (The number stays 5.2a so the outline's numbering holds.)

**Files:** Modify `wikantik-main/src/main/java/com/wikantik/auth/user/UserDatabase.java`, `JDBCUserDatabase.java`, `wikantik-work/.../WorkDirectory.java`, `wikantik-main/src/main/java/com/wikantik/work/runtime/WikiWorkDirectory.java`. Create `wikantik-work/.../CodeHostIdentities.java`. Tests `JDBCUserDatabaseTest`, `WikiWorkDirectoryTest`, `CodeHostIdentitiesTest` (Mockito store and directory).

**Interfaces — produces:**
- `UserDatabase.loginsForEmailIgnoreCase(String email) : List<String>` default: scan `findAllProfiles()`, compare `equalsIgnoreCase` after trim, return at most 2 logins. `JDBCUserDatabase` override: `SELECT login_name FROM users WHERE lower(email) = lower(?) LIMIT 2`.
- `WorkDirectory.loginForEmail(String email) : Optional<String>` default `Optional.empty()`. `WikiWorkDirectory`: blank → empty; exactly one login → it; zero or two → empty; `WikiSecurityException`/`RuntimeException` → `WorkUnavailableException` (logged WARN with the exception message, never the address).
- `record CodeHostWho(String login, String external)` (login null when unmatched; `external` = username, else name, else `unknown`).
- `CodeHostIdentities(WorkCodeHostStore store, WorkDirectory directory)`, `CodeHostWho resolve(String host, CodeEvent.Person p)`: null person → `(null, "unknown")`; a learned `(host, username)` wins; else an email not ending `@users.noreply.github.com` (case-insensitive) is looked up; a hit with a username present calls `store.learn(host, username, login)`. Memoised per `apply` call only (a new instance per delivery).

- [ ] **Step 1: Failing tests.** `JDBCUserDatabaseTest.loginsForEmailIgnoresCase` (`Ana@Example.com` finds `ana@example.com`; two accounts sharing an address → two logins). `WikiWorkDirectoryTest`: one match → login; two → empty; database throws → `WorkUnavailableException`. `CodeHostIdentitiesTest`: `anEmailMatchTeachesTheUsername` (verify `learn("github","octocat","ana")`), `aLearnedUsernameResolvesWithoutAnEmail`, `aNoreplyAddressIsNeverLookedUp` (directory never called), `noMatchKeepsTheHostName` (`external` = `octocat`; no username → name; neither → `unknown`), `learningIsPerHost` (a learned `generic` username does not resolve for `github`).
- [ ] **Step 2:** `mvn test -pl wikantik-main -Dtest='JDBCUserDatabaseTest,WikiWorkDirectoryTest'`, `mvn test -pl wikantik-work -Dtest=CodeHostIdentitiesTest` → FAIL (no `loginsForEmailIgnoreCase`, no `CodeHostIdentities`).
- [ ] **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** — `feat(work): match code-host authors to wiki users by email, learn usernames`.

### Task 5.2b: The hook: enabling, listing and authenticating

**Files:** Create `WorkCodeHostStore.java`, `JdbcWorkHooks.java` (hook and identity rows), `CodeHostSecrets.java`, `HookDelivery.java`, `HookStatus.java`, `WorkCodeHostService.java`. Modify `WorkStore.java` (`WorkCodeHostStore codeHost()`), `JdbcWorkStore.java`, `JdbcWorkCatalog.java` (make `appendProductEvent` package-private), `WorkService.java` (`public WorkCodeHostService codeHost()`; construct with `store, scopes, directory, clock, observer, audit`), `WorkServiceVisibilityPostgresTest.java`, `wikantik-rest/src/test/java/com/wikantik/rest/WorkAccessArchTest.java` (add `com.wikantik.work.WorkCodeHostStore` to the store list). Tests `CodeHostSecretsTest`, `WorkCodeHostAuthPostgresTest`, `JdbcWorkHooksRollbackTest`.

**Interfaces — produces:**
- `record Hook(long productId, String secret, String createdBy, Instant createdAt, Instant lastReceivedAt, Instant disabledAt) { boolean live() { return disabledAt == null; } }` (nested in `WorkCodeHostStore`; never leaves the module: `toString()` masks `secret`).
- `WorkCodeHostStore` (this task's half): `Optional<Hook> hook(long productId)`, `Map<Long, Hook> hooks()`, `boolean putHook(Product p, String secret, Actor by)` (true = replaced a live one; one transaction: upsert `secret, created_by, created_at = NOW(), disabled_at = NULL`, plus `appendProductEvent(conn, p.id(), by, "hook", <old state>, "enabled"|"rotated")`), `boolean disableHook(Product p, Actor by)` (false when none or already disabled; stamps `disabled_at`, product event `hook` live→`disabled`), `void received(long productId, Instant at)`, `Optional<String> learned(String host, String username)`, `void learn(String host, String username, String login)` (`ON CONFLICT DO NOTHING`).
- `CodeHostSecrets`: `static String newSecret()` (32 bytes `SecureRandom`, lower-case hex); `static boolean matches(String secret, byte[] body, String header)` — header must be `sha256=` + 64 hex (case-insensitive), compared with `MessageDigest.isEqual` against `HmacSHA256(secret, body)` hex; `static final String DUMMY_KEY` (a constant used when there is no live hook).
- `HookDelivery`: `public final class` with a **package-private** constructor; accessors `productId()`, `productKey()`, `host()`, `repoUrl()`, `defaultBranch()`. Only `authenticate` can make one, so `apply` cannot be called without a verified delivery.
- `record HookStatus(String productKey, boolean enabled, Instant enabledAt, Instant lastReceivedAt)`.
- `WorkCodeHostService`:
  - `Optional<HookDelivery> authenticate(String productKey, String host, byte[] body, String signatureHeader) throws SQLException` — **SYSTEM**. Empty for an unknown or archived product, no hook, a disabled hook, a host other than `github`/`generic`, a missing or wrong signature; always computes one HMAC (P5-15). Never throws for a bad signature.
  - `String enableHook(Actor a, String productKey)` — **WRITE**: `scopes.requireInstance(a, Action.MANAGE_INSTANCE)`; unknown → `WorkNotFoundException`; archived product → `WorkConflictException` `archived`; returns the new secret; audit `work.product.hook.enable` or `.rotate`, target type `work_product`, target the key, detail null.
  - `void disableHook(Actor a, String productKey)` — **WRITE**, same gate; audit `work.product.hook.disable` when it changed anything.
  - `Map<String, HookStatus> hooks(Actor a)` — **INSTANCE_READS** (by product key).
- Visibility test: `FACADES` += `codeHost`; `SYSTEM` += `authenticate`, `apply`; `WRITES` += `enableHook`, `disableHook`, `acknowledgeFlag` (Task 5.3a); `INSTANCE_READS` += `hooks`.

- [ ] **Step 1: Failing tests.** `CodeHostSecretsTest`: the known vector (secret `It's a Secret to Everybody`, body `Hello, World!` → `sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17`, GitHub's documented example); upper-case hex accepted; `sha1=…`, missing prefix, 63 hex, null → false; two secrets differ and are 64 hex. `WorkCodeHostAuthPostgresTest`: `anEnabledHookAuthenticatesItsOwnBody`; `everyRejectionIsEmpty` (unknown product, archived product, no hook, disabled hook, wrong host `gitlab`, missing header, wrong signature, a body changed by one byte); `rotationRetiresTheOldSecret`; `enablingWritesAProductEventAndAnAuditRecordWithoutTheSecret` (row field `hook`, to `enabled`; `productHistory` text and the recorded audit detail never contain the secret); `aNonAdminCannotEnableDisableOrList` (`WorkDeniedException` for a project lead; `WorkNotFoundException` for an unknown product as admin); `hooksShowsLastReceived`. `JdbcWorkHooksRollbackTest`: a fault on the product-event insert leaves no hook row (statements: 1 upsert, 2 event). Visibility test passes its classification.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='CodeHostSecretsTest,WorkCodeHostAuthPostgresTest,JdbcWorkHooksRollbackTest,WorkServiceVisibilityPostgresTest'` → FAIL.
- [ ] **Step 3:** Implement. `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-work-mcp,wikantik-main`.
- [ ] **Step 4:** Re-run → PASS; `mvn test -pl wikantik-rest -Dtest=WorkAccessArchTest` PASS.
- [ ] **Step 5: Commit** — `feat(work): product webhook secret, rotation and signed-delivery check`.

### Task 5.2c: Applying branch and pull-request events

**Files:** Create `CodeHostTargets.java`, `CodeHostApplier.java`, `CodeHostOutcome.java`, `JdbcWorkCodeHost.java` (live-change writes). Modify `WorkCodeHostStore.java`, `WorkCodeHostService.java` (`apply`), `WorkEvent.java` (no new type yet), `WorkListener.java`, `GuardedListener.java`, `WorkMetrics.java`. Tests `WorkCodeHostApplyPostgresTest`, `WorkCodeHostIdempotencePostgresTest` (live half), `JdbcWorkCodeHostRollbackTest`, `WorkMetricsTest`, fixture helper `CodeEvents.java` (test builders).

**Interfaces — produces:**
- `enum CodeHostOutcome { APPLIED, DUPLICATE, IGNORED }` with `wire()` (`applied`, `duplicate`, `ignored`).
- `record Applied(int applied, int duplicate, int ignored)` (nested in `WorkCodeHostService`); `Applied apply(HookDelivery d, List<CodeEvent> events) throws SQLException` — **SYSTEM**: `store.codeHost().received(d.productId(), clock.instant())`, then each event through `CodeHostApplier`; each event's outcome reported once through `listener.codeHost(kind.name().toLowerCase(Locale.ROOT), outcome.wire())`. Content it cannot use is `IGNORED`, never an exception; `SQLException` and `WorkUnavailableException` propagate (REST answers 503).
- `WorkListener.codeHost(String kind, String outcome)` (default no-op), guarded in `GuardedListener`; `WorkMetrics` counter `wikantik_work_codehost_events_total{kind,outcome}` (no product label).
- `CodeHostTargets.items(HookDelivery d, CodeEvent e) : Targets` where `record Targets(List<Target> items, int ignoredKeys)`, `record Target(Item item, EffectivePolicy policy, boolean closed)`. Keys: from `branch` and `review.title` for review events and for a `PUSH` to a non-default branch; from each **distinct** commit's message for a `PUSH` to the default branch (default = `d.defaultBranch()`, else `e.defaultBranch()`). A key is kept only when `store.item(key)` exists, is not an epic, `store.policyChain(id).productOrNull()` is this product, and the project is not archived; everything else counts in `ignoredKeys` and is not logged by key. When `d.repoUrl()` is set and `e.repo()` is set, `repoUrl` must end with `/` + repo or `/` + repo + `.git` (case-insensitive), else every key is ignored and the service logs `repo_mismatch` once.
- `WorkCodeHostStore` (this task's half), every method one transaction that starts with `SELECT pg_advisory_xact_lock(1464550216, ?::int)` on the product id, calls `requireSystem(by)`, locks the change row(s) it writes `FOR UPDATE` in id order, then the item `FOR SHARE`, and writes each event with `catalog.appendEvent` (`from_value` `change:<id>`, actor `by`):
  - `HostWrite branchPush(long productId, Item item, BranchFacts f, Actor by)`; `record BranchFacts(String branch, String baseBranch, String headSha, Instant firstCommitAt, CodeHostWho opener)`.
  - `HostWrite review(long productId, Item item, ReviewFacts f, ReviewStep step, CodeHostWho reviewer, Actor by)`; `record ReviewFacts(String number, String url, String branch, String baseBranch, String headSha, Instant at, CodeHostWho author)`; `enum ReviewStep { OPENED, OPENED_DRAFT, READY, UPDATED, APPROVED, CLOSED }`.
  - `record HostWrite(CodeHostOutcome outcome, Change change)` (change null when nothing was written).
- Behaviour (each line has a test below):
  - **Find the change**: a live change of the item with `review_number = f.number`; else a live change on `(item, branch)`; adopting fills only null columns (`review_number`, `review_url`, `external_opened_by` when `opened_by` and `opened_by_agent_id` are null, `first_commit_at` only earlier). It never touches `opened_by*`, `reviewed_by*`, `merged_by*`, `head_agent_ids`.
  - **Open** when none is found and the item is open: `source = 'codehost'`, `opened_by = opener.login()` (null when unmatched) with `external_opened_by = opener.external()` when the login is null; `base_branch` from the event else the default branch; one `change_opened` event. A closed item → `IGNORED` (P5-10). For `OPENED`, a change with that number in state `abandoned` is reopened as a **new** row only when `f.at()` is after its `closed_at` (P5-13); one in state `merged` → `DUPLICATE`.
  - **Old news**: a branch push whose `headSha` some change of `(item, branch)` already has, or had (P5-3: a `change_opened` event with `note = sha` or a `change_updated` with `to_value = sha` for any change of that item and branch), opens nothing and moves nothing (`DUPLICATE`, but `first_commit_at` is still filled earlier, with a `change_updated` event noting `first commit`).
  - **Head move** (`branchPush`, `UPDATED`, and `OPENED` on an adopted change): a new, never-seen head is set exactly as `JdbcWorkChanges.update` does (clears the approval; no agent id appended, P5-17); one `change_updated` event.
  - `OPENED` (non-draft) and `READY` → `in_review` when `open` (one `review_requested`); `OPENED_DRAFT` stays `open`.
  - `APPROVED`: when `f.headSha()` equals the change's head and no approval stands on it: state `in_review`, `reviewed_by = reviewer.login()`, `external_reviewed_by = reviewer.external()` when the login is null, `reviewed_head_sha = head`, `reviewed_at = f.at()`; one `change_reviewed` event (to `approved`, note null). A different head, or an approval already standing → `DUPLICATE`, nothing written.
  - `CLOSED` (not merged): the live change → `abandoned`, one `change_abandoned` (note `closed on the code host`); none live → `DUPLICATE`.
  - The applier opens the change first (as `OPENED`) when a `READY`, `UPDATED` or `APPROVED` finds none (a hook enabled after the pull request was opened).
- `CodeHostApplier` (package-private): resolves people with a fresh `CodeHostIdentities` before any store call, maps `CodeEvent.Kind` to the store calls above (merge and direct pushes in Task 5.2d), sums outcomes; one item's `IGNORED` never stops another's write.

- [ ] **Step 1: Failing tests** (`WorkCodeHostApplyPostgresTest`, real `JdbcWorkStore`, a product `wk` with default branch `main`, project `PLAT`, item `PLAT-1` targeting it, a person `ana` with `ana@example.com`):
  - `aBranchPushNamingAnItemOpensAChange` — `source` `codehost`, `opened_by` `ana`, head = last commit, `first_commit_at` = earliest commit time, one `change_opened` event with `actor_kind` `system`, `actor` `codehost:github`.
  - `aSecondPushMovesTheHeadAndKeepsTheEarliestCommitTime`.
  - `aPullRequestOpenedAdoptsTheBranchChangeAndRecordsItsLink` — same row, `review_url`, `review_number` `12`, state `in_review`.
  - `aDraftPullRequestStaysOpenUntilReady`.
  - `anApprovalRecordsTheCommitItApproved`; `anApprovalForAnOlderCommitRecordsNothing`.
  - `aNewCommitClearsAStandingApproval`.
  - `aPullRequestClosedWithoutMergingIsAbandoned`.
  - `aBranchNamingTwoItemsMakesAChangeOnEach`.
  - `anUnknownKeyAnEpicAnotherProductsItemAndUTF8AreIgnored` — `Applied.ignored` counts, no rows, no events.
  - `anArchivedProjectsItemIsIgnored`; `aClosedItemGetsNoNewLiveChange`; `aDeliveryFromAnotherRepositoryIsIgnored`.
  - `anAgentsChangeIsAdoptedNotDuplicated` — agent opened `work/PLAT-1-x` by `changes().open` with head A and approved by a second agent: a push and `OPENED` keep `opened_by_agent_id`, `head_agent_ids` = [author], the agent reviewer; `first_commit_at` and `review_number` filled; still one change.
  - `anUnmatchedAuthorIsRecordedByName` — `opened_by` null, `external_opened_by` `octocat`.
  - `WorkCodeHostIdempotencePostgresTest`: `everyLiveEventTwiceChangesNothing` (row snapshot and `work_event` count equal after the second apply; outcomes `duplicate`); `anOldPushRedeliveredAfterANewerOneLeavesTheHead` (push A, push B, approve B, redeliver A → head B, approval stands); `anOldReopenedOpensNothing`; `aPullRequestOpenedRedeliveredAfterItsMergeOpensNothing`.
  - `JdbcWorkCodeHostRollbackTest`: open — a fault on the `change_opened` event insert leaves no change and no event; approve — a fault on the event leaves no approval.
  - `WorkMetricsTest.codeHostEventsAreCountedByKindAndOutcomeOnly` (no `product` tag).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkCodeHostApplyPostgresTest,WorkCodeHostIdempotencePostgresTest,JdbcWorkCodeHostRollbackTest,WorkMetricsTest'` → FAIL.
- [ ] **Step 3:** Implement. Keep each class under the PMD limits by the split named above; no suppressions. `mvn pmd:check -Pcomplexity-gate -pl wikantik-work`.
- [ ] **Step 4:** Re-run → PASS.
- [ ] **Step 5: Commit** — `feat(work): apply code-host branch and pull-request events to change records`.

### Task 5.2d: Merges, direct pushes and flags

**Files:** Create `CodeHostFlags.java`, `JdbcWorkCodeHostMerges.java`. Modify `WorkCodeHostStore.java`, `CodeHostApplier.java`, `WorkEvent.java` (`CHANGE_FLAGGED = "change_flagged"`, `CHANGE_SUPERSEDED = "change_superseded"`), `wikantik-frontend/src/components/work/eventText.js`. Tests `CodeHostFlagsTest`, `WorkCodeHostMergePostgresTest`, `WorkCodeHostMergeOrderPostgresTest`, `WorkCodeHostIdempotencePostgresTest` (merge half), `JdbcWorkCodeHostRollbackTest` (merge half), `eventText.test.js`.

**Interfaces — produces:**
- `CodeHostFlags.forMerge(MergeKind kind, EffectivePolicy policy) : String` → `"unreviewed"` for `UNREVIEWED`; `"direct"` for `DIRECT` when `!policy.directAllowed()`; else null. Applied to **every** merge this service records, whoever the author is (D17); never to merges reported through `WorkChangesService`.
- `CodeHostFlags.kind(Change locked, ReviewFacts f) : MergeKind` → `REVIEWED` when `locked.approvedForHead()` and the approval is not the author's own (`external_reviewed_by` equals `f.author().external()` ignoring case, or `reviewed_by` equals `f.author().login()`, is self); else `UNREVIEWED`.
- `WorkCodeHostStore.merge(long productId, Item item, ReviewFacts f, String mergedSha, CodeHostWho merger, EffectivePolicy policy, Actor by) : HostWrite` — under the advisory lock:
  1. A change of the item with `review_number = f.number` (or on `(item, branch)`) already `merged` → `DUPLICATE`.
  2. Find the live change as in 5.2c, or open one in the same transaction (a pull request whose opening was never seen).
  3. If `f.headSha()` is set, differs from the head and was never seen, move the head first (clears the approval).
  4. Decide `kind = CodeHostFlags.kind(locked, f)` and `flag = forMerge(kind, policy)` on the locked row; update `state, merged_sha, merge_kind, merged_by = merger.login(), external_merged_by` (when the login is null), `closed_at`, `policy_flag`; event `change_merged` (to kind, note sha); when flagged, event `change_flagged` (to flag).
  5. A `direct`, `codehost`, not-superseded change of the item with `merged_sha = mergedSha` (its push came first, P5-2): lock it (id order with the change above), set `superseded_by` = this change, set this change's `first_commit_at` to the earlier of the two, event `change_superseded` (from `change:<direct id>`, to `change:<this id>`).
- `WorkCodeHostStore.direct(long productId, Item item, DirectFacts f, String flagOrNull, Actor by) : HostWrite`; `record DirectFacts(String branch, String after, String headSha, Instant firstCommitAt, Set<String> pushShas, Set<String> reviewNumbers, CodeHostWho author)` — under the advisory lock and the item `FOR SHARE`:
  - `DUPLICATE` when any change of the item has `merged_sha = f.after()`, or `merged_sha` or `head_sha` in `pushShas` (all commits of the push, distinct or not), or `review_number` in `reviewNumbers`.
  - Otherwise `INSERT … ON CONFLICT (item_id, merged_sha) WHERE merge_kind = 'direct' DO NOTHING RETURNING id` (the partial-index inference form) with `branch = base_branch = f.branch()`, `state merged`, `merge_kind direct`, `merged_sha = f.after()`, `head_sha = f.headSha()`, `first_commit_at`, `opened_by = merged_by = author.login()` (externals when null), `source codehost`, `policy_flag`, `closed_at NOW()`; no row returned → `DUPLICATE`. Events: `change_merged` (to `direct`, note `after`), and `change_flagged` when flagged.
- Applier, `PUSH` to the default branch: per target item, one `direct` call with the item's distinct naming commits (P5-1). `REVIEW_MERGED`: per target item one `merge` call. The policy is `Target.policy`, resolved before the transaction (P5-6).
- `eventText.js`: `change_flagged` → `flagged a merge outside policy: ` + (`without review` | `a direct commit where direct commits are not allowed`); `change_superseded` → `recorded a direct commit again as the pull request's merge`; both added to the `TYPES` list in `eventText.test.js`.

- [ ] **Step 1: Failing tests.**
  - `CodeHostFlagsTest`: the table `{REVIEWED, UNREVIEWED, DIRECT} × {directAllowed true, false}`; `kind` with an outside approval, a self-approval by username, a self-approval by login, no approval.
  - `WorkCodeHostMergePostgresTest`: `aMergeAfterApprovalIsReviewedAndUnflagged`; `aMatchedPersonsUnreviewedMergeIsFlagged` (opener and merger `ana`, `policy_flag` `unreviewed`, a `change_flagged` event); `aMergeWithANewerHeadThanTheApprovalIsUnreviewed`; `aDirectPushIsFlaggedOnlyWhereDirectCommitsAreNotAllowed` (set `allow_direct` on the item through `updateItem` as a lead); `aDirectCommitNamingAClosedItemIsRecorded`; `aMergeOfAPullRequestNeverSeenOpenedOpensAndMergesIt`; `aMergeReportedByAnAgentIsNotOverwritten` (agent merged by `changes().merge` first; the code-host merge is `DUPLICATE`; no flag).
  - `WorkCodeHostMergeOrderPostgresTest` (Review Focus 2), each ending with exactly one merged, not-superseded change for the item and `count(change_merged events not followed by change_superseded)` = 1:
    - `mergeCommitPullRequestFirst` — `REVIEW_MERGED` (mergedSha M), then a push with `after` M whose commits are the PR's (non-distinct) and the merge commit `Merge pull request #12 from o/PLAT-1-x` (distinct): no direct change.
    - `mergeCommitPushFirst` — the same push, then `REVIEW_MERGED`: the push is skipped by the PR head in `pushShas` and by `#12`.
    - `squashPullRequestFirst` / `squashPushFirst` — push of one distinct commit `PLAT-1 fix login (#12)`, `after` M.
    - `squashPushFirstWithTheNumberEditedOut` — message `PLAT-1 fix login`: the push makes a direct change (flagged when not allowed), the later merge supersedes it.
    - `rebasePullRequestFirst` / `rebasePushFirst` — two distinct rebased commits naming `PLAT-1`, no number, `after` M: pull-request-first adds nothing; push-first makes one direct change that the merge supersedes; the waiting lane (Task 5.3a) shows no flag for the superseded row.
  - `WorkCodeHostIdempotencePostgresTest`: `everyMergeAndDirectEventTwiceChangesNothing`; `aDirectPushRedeliveredMakesNoSecondChange`.
  - `JdbcWorkCodeHostRollbackTest`: merge — a fault on the `change_flagged` insert leaves the change live, unflagged, with no `change_merged` event; direct — a fault on its event leaves no change.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='CodeHostFlagsTest,WorkCodeHostMerge*PostgresTest,WorkCodeHostIdempotencePostgresTest,JdbcWorkCodeHostRollbackTest'`; `cd wikantik-frontend && npx vitest run src/components/work/eventText.test.js` → FAIL.
- [ ] **Step 3:** Implement; `mvn pmd:check -Pcomplexity-gate -pl wikantik-work`; `npm run lint`.
- [ ] **Step 4:** Re-run → PASS.
- [ ] **Step 5: Commit** — `feat(work): code-host merges and direct pushes, recorded once and flagged outside policy`.

### Task 5.3a: Acknowledging a flag, and the waiting lane

**Files:** Modify `Action.java` (append `ACKNOWLEDGE_FLAG( WorkRole.LEAD, true, false )` last), `WorkCodeHostStore.java`, `JdbcWorkCodeHostMerges.java` (or a new `JdbcWorkChangeFlags.java` if PMD asks), `WorkChangesService.java`, `WaitingItem.java`, `Acknowledgements.java`, `WorkService.java` (`waitingOn` appends the flagged lane), `WorkEvent.java` (`CHANGE_FLAG_ACKNOWLEDGED = "change_flag_acknowledged"`), `WorkConflictException.java` (`ALREADY_ACKNOWLEDGED = "already_acknowledged"`, `NOT_FLAGGED = "not_flagged"`), `wikantik-rest/.../WorkChangeResource.java`, `WorkMeResource.java` (Javadoc names the new reason), `src/test/resources/com/wikantik/work/policy-action-table.golden`. Tests `WorkChangeFlagsPostgresTest`, `WorkChangeResourceTest`, `WorkMeResourceTest`, `WorkPolicyActionTableTest` (golden), `WorkServiceVisibilityPostgresTest`.

**Interfaces — produces:**
- `WorkCodeHostStore.flagged(Set<Long> projectIds) : List<Item>` — items with a change where `policy_flag IS NOT NULL AND flag_acknowledged_at IS NULL AND superseded_by IS NULL`, newest change first, distinct items (`JdbcWorkStore.ITEM_SELECT`, `ITEM_ROW`, as `JdbcWorkAcks.awaiting`).
- `WorkCodeHostStore.acknowledgeFlag(Item item, long changeId, Actor by) : Change` — one transaction: lock the change `FOR UPDATE` (`WHERE id = ? AND item_id = ?`); no flag → `WorkConflictException(NOT_FLAGGED)` "This change was not merged outside policy."; already acknowledged → `ALREADY_ACKNOWLEDGED` "A lead already acknowledged this merge."; set `flag_acknowledged_by = by.login(), flag_acknowledged_at = NOW()`; event `change_flag_acknowledged` (from `change:<id>`, to the flag). The flag is never cleared. Uses `requireAttributed` (a person; an agent is refused earlier by the action).
- `WorkChangesService.acknowledgeFlag(Actor a, long changeId) : Change` — **WRITE**: `scopeOf(a, changeId)` (unknown and unseen are one `WorkNotFoundException`), `require(Action.ACKNOWLEDGE_FLAG, item)` (a lead or an admin; an agent is refused because the action is management), `requireWritable()` (archived → `archived`).
- `WaitingItem.CHANGE_OUTSIDE_POLICY = "change_outside_policy"`; `Acknowledgements.outsidePolicy(Actor, List<Project> visible) : List<WaitingItem>` uses the same led-project set as `awaiting` (refactor that set into one private method).
- REST: `POST /api/work/changes/{id}/acknowledge-flag` → 200 with the change JSON (as `reply`); 404 for a stranger; 403 for a member or agent (`WorkDeniedException`, as other refusals); 409 `already_acknowledged`, `not_flagged`, `archived`.
- Golden: regenerate `policy-action-table.golden` by appending the 64 `ACKNOWLEDGE_FLAG|…` lines `WorkPolicyActionTableTest.table()` produces; `git diff` must show additions only.

- [ ] **Step 1: Failing tests.** `WorkChangeFlagsPostgresTest`: `aLeadSeesAFlaggedMergeWaitingAndAMemberDoesNot`; `anAdminSeesItToo`; `aLeadAcknowledgesOnceAndTheLaneEmpties` (second → `already_acknowledged`; flag still set; one `change_flag_acknowledged` event); `aMemberAndAnAgentCannotAcknowledge`; `aStrangerGetsNotFound`; `anUnflaggedChangeIsNotFlagged`; `anArchivedProjectRefuses`; `aSupersededFlagNeverWaits`. Rollback: a fault on the event leaves the flag unacknowledged. `WorkChangeResourceTest`: route happy path, 409 body `reason`. `WorkMeResourceTest`: `waiting.reason` is `change_outside_policy`.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkChangeFlagsPostgresTest,WorkPolicyActionTableTest,WorkPolicyTest,WorkServiceVisibilityPostgresTest'`, `mvn test -pl wikantik-rest -Dtest='WorkChangeResourceTest,WorkMeResourceTest'` → FAIL.
- [ ] **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** — `feat(work): merges outside policy wait for a lead to acknowledge them`.

### Task 5.3b: The flag and the outside author in the UI

**Files:** Modify `wikantik-frontend/src/components/work/WaitingLane.jsx`, `WorkChanges.jsx`, `WorkItemView.jsx` (pass `canAcknowledge={isLead}` to `WorkChanges`), `eventText.js`, `src/api/work.js`. Tests `WaitingLane.test.jsx`, `WorkChanges.test.jsx`, `eventText.test.js`, `src/api/work.test.js`.

**Interfaces — produces:**
- `api/work.js`: `export const acknowledgeFlag = (id) => request(\`${base}/changes/${enc(id)}/acknowledge-flag\`, { method: 'POST' });`
- `WaitingLane`: items with `waiting.reason === 'change_outside_policy'` are left out of "Waiting on you" and shown in a group `data-testid="outside-policy"`, heading `Merged outside policy (n)`, line `· merged on the code host without review, or committed directly where that is not allowed`, keys `flag-${i.id}`, each linking to the item.
- `WorkChanges` row: `via GitHub` chip when `source === 'codehost'`; `opened by octocat (outside the wiki)` when `openedByName` is absent and `externalOpenedBy` is set (likewise reviewed/merged); `first commit <relative time>` when `firstCommitAt`; a `work-chip` `outside policy: without review` / `outside policy: direct commit`; `acknowledged by <flagAcknowledgedByName>` when acknowledged, else an `Acknowledge` button when `canAcknowledge`; `recorded again as change <supersededBy>` when superseded (and no flag chip).
- `eventText.js`: `change_flag_acknowledged` → `acknowledged a merge outside policy`; added to `TYPES`.

- [ ] **Step 1: Failing vitest:** the lane groups a flagged item apart and does not call it "Assigned by"; an item both assigned and flagged appears in both groups without a duplicate-key warning; a code-host change row shows the chip, the outside author and the first commit time; the Acknowledge button calls `acknowledgeFlag(id)` and reloads; no button without `canAcknowledge`; a superseded row shows no flag; `acknowledgeFlag` posts to the route.
- [ ] **Step 2:** `cd wikantik-frontend && npx vitest run src/components/work/WaitingLane.test.jsx src/components/work/WorkChanges.test.jsx src/components/work/eventText.test.js src/api/work.test.js` → FAIL.
- [ ] **Step 3:** Implement; `npm run lint`. **Step 4:** PASS.
- [ ] **Step 5: Commit** — `feat(work-ui): code-host changes, outside authors and the outside-policy lane`.

### Task 5.3c: Notifying leads of a flag (conditional on Part 3)

- [ ] **Step 0:** Run `test -f wikantik-work/src/main/java/com/wikantik/work/Recipients.java && echo present || echo absent`.
- [ ] **If present:** add to `RecipientsTest` `aFlaggedMergeReachesTheLeads` — `Recipients.of(event("change_flagged", actor system), facts(leads = {lena, leo}, watchers = {ana}))` → `{lena: acknowledge, leo: acknowledge}` and nobody else from this rule (watchers still hear the `change_merged` event of the same transaction through the existing rule); the system actor is never a recipient. Run `mvn test -pl wikantik-work -Dtest=RecipientsTest` → FAIL; add the rule (`CHANGE_FLAGGED` → leads, reason `acknowledge`) to `Recipients.of`; re-run → PASS. Commit `git add wikantik-work/src/main/java/com/wikantik/work/Recipients.java wikantik-work/src/test/java/com/wikantik/work/RecipientsTest.java` — `feat(work): leads are notified of merges outside policy`.
- [ ] **If absent:** no code. Task 5.5 writes into `wikantik-work/CLAUDE.md` (code-host section): "Notifications: `change_flagged` goes to the project's leads with reason `acknowledge`; `change_flag_acknowledged` and `change_superseded` go to nobody." Report to the controller that Part 3's Task 3.2 must add that rule and its test; the design (section 5, "with notifications built the leads are notified") requires it.

### Task 5.4a: The GitHub and generic adapters

**Files:** Create `wikantik-rest/src/main/java/com/wikantik/rest/GithubHookAdapter.java`, `GenericHookAdapter.java`; fixtures under `wikantik-rest/src/test/resources/work-hooks/`: `push-branch.json`, `push-default-direct.json`, `push-default-merge-commit.json`, `push-default-squash.json`, `push-default-rebase.json`, `push-tag.json`, `push-delete.json`, `pr-opened.json`, `pr-opened-draft.json`, `pr-reopened.json`, `pr-ready.json`, `pr-review-requested.json`, `pr-synchronize.json`, `review-approved.json`, `review-commented.json`, `pr-merged.json`, `pr-closed.json`, `ping.json`, `generic-push.json`, `generic-merged.json`. Tests `GithubHookAdapterTest`, `GenericHookAdapterTest`.

**Interfaces — produces:**
- `GithubHookAdapter.translate(String eventHeader, JsonObject body) : List<CodeEvent>` (header `X-GitHub-Event`). Field names, as GitHub sends them:
  - `push`: `ref` (`refs/heads/<branch>`; `refs/tags/*` → none), `deleted: true` → none, `before`, `after`, `repository.full_name` → `repo`, `repository.default_branch`, `head_commit.timestamp` → `at`, `commits[]`: `id` → sha, `message`, `timestamp` (offset form, parsed with `OffsetDateTime`), `distinct`, `author.name`, `author.email`, `author.username`. One `PUSH`.
  - `pull_request`: `action` (`opened`, `reopened` → `REVIEW_OPENED`; `ready_for_review`, `review_requested` → `REVIEW_READY`; `synchronize` → `REVIEW_UPDATED`; `closed` with `pull_request.merged: true` → `REVIEW_MERGED`, `merged: false` → `REVIEW_CLOSED`; any other action → none), `pull_request.number` (as text), `html_url`, `title`, `draft`, `user.login` → review author username, `head.ref` → branch, `head.sha` → `review.headSha`, `base.ref` → `baseBranch`, `merge_commit_sha` → `mergedSha` (only when merged), `merged_by.login` → actor, `updated_at` → `at`.
  - `pull_request_review`: `action: submitted` and `review.state: approved` (lower-case in webhook payloads) → `REVIEW_APPROVED` with `review.commit_id` → `review.headSha`, `review.user.login` → actor, `review.submitted_at` → `at`, the pull request's number/branch/title/author as above; `commented`, `changes_requested`, `dismissed` → none (P5-11).
  - `ping`, and every other header → none. A body missing a field the kind needs → none (never an exception).
- `GenericHookAdapter.translate(JsonObject body) : List<CodeEvent>` — the wire form of `CodeEvent` in camel case: `kind` in snake case (`push`, `review_opened`, `review_ready`, `review_updated`, `review_approved`, `review_merged`, `review_closed`), `repo`, `defaultBranch`, `branch`, `baseBranch`, `before`, `after`, `at`, `commits[{sha, message, at, distinct (default true), author{username,name,email}}]`, `review{number, url, title, headSha, draft, author}`, `actor`, `mergedSha`. Unknown `kind` or missing `branch` → none. Optionally a JSON array of such objects (several events in one delivery).

- [ ] **Step 1: Failing tests.** One test per fixture asserting the whole `CodeEvent` (kind, branch, heads, `distinct` flags, times as `Instant`, people). Shape notes for the fixtures: `push-default-merge-commit.json` has the PR's two commits with `distinct: false` and `Merge pull request #12 from octo/PLAT-1-fix-login` with `distinct: true`, `after` = that merge commit's `id`; `pr-merged.json` has `merged: true`, `merge_commit_sha` equal to that `after`; `push-default-rebase.json` has two `distinct: true` commits and no `#12`. `review-commented.json` and `ping.json` give `[]`. Generic: `generic-push.json` translates; `{"kind":"tag"}` and a push with no `branch` give `[]`; `distinct` absent means true.
- [ ] **Step 2:** `mvn test -pl wikantik-rest -Dtest='GithubHookAdapterTest,GenericHookAdapterTest'` → FAIL.
- [ ] **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** (the two classes, the two tests and the fixture directory by path) — `feat(work): GitHub and generic code-host payload adapters`.

### Task 5.4b: The endpoint, its flag and the CSRF pin

**Files:** Create `wikantik-rest/src/main/java/com/wikantik/rest/WorkHookResource.java`, test `WorkHookResourceTest.java`. Modify `wikantik-war/src/main/webapp/WEB-INF/web.xml` (servlet `WorkHookResource`, mapping `/api/work/hooks/*`), `wikantik-main/src/main/resources/ini/wikantik.properties` (`[Work]`), `wikantik-rest/.../CapabilitiesResource.java` (`workCodeHost`), `wikantik-http/src/test/java/com/wikantik/http/filter/CsrfProtectionFilterTest.java`, `CapabilitiesResourceTest.java`.

**Interfaces — produces:**
- Keys (declare first, then `bin/config-reference.sh --write`):
  ```
  # Receive signed webhook deliveries from a product's code host at /api/work/hooks/<product>/<github|generic>
  # and record pushes, pull requests and reviews as change records. Receive only: the wiki never calls the
  # code host and stores no token for it. Needs wikantik.work.enabled. When false the endpoint answers 404.
  # Type: boolean
  wikantik.work.codehost.enabled = false

  # The largest webhook body accepted, in bytes; a larger one is answered 413 before it is checked.
  # Type: int
  wikantik.work.codehost.maxBodyBytes = 5242880
  ```
- `WorkHookResource extends RestServletBase` (not `WorkResourceBase`: there is no actor). `POST /api/work/hooks/{productKey}/{github|generic}`:
  1. Work off (`workService()` null) or `wikantik.work.codehost.enabled` false, wrong method or path shape → 404 with `WorkJson.errorBody(404, "Not found.")`.
  2. `Content-Length` above `maxBodyBytes`, or more bytes read than that (`readNBytes(max + 1)`) → 413.
  3. `svc.codeHost().authenticate(key, format, body, header)` with header `X-Hub-Signature-256` (github) or `X-Wikantik-Signature` (generic); empty → the same 404 bytes.
  4. Parse JSON (failure → 204, logged `outcome=ignored`), translate, `apply`; → 204 always.
  5. `WorkUnavailableException` or `SQLException` → 503 `{"error":true,"status":503,"message":"Work is unavailable right now."}`, logged at WARN by class and SQLState only.
  - Never calls `getSession`, `Wiki.session()`, or reads `Authorization`/cookies. One INFO line per delivery: `work codehost delivery product=<key> format=<f> event=<X-GitHub-Event or kind> delivery=<X-GitHub-Delivery, ≤64 chars, control characters stripped> outcome=<accepted|rejected|ignored> applied=<n> duplicate=<n> ignored=<n>`. A rejection also counts `listener` `codeHost("delivery", "rejected")` through the service (add `WorkCodeHostService.rejected()`, **SYSTEM**, which only reports the metric).
- `CapabilitiesResource`: `workCodeHost` = the `work` value AND `wikantik.work.codehost.enabled`.
- No change to `CsrfProtectionFilter`: `/api/*` is checked by Origin, and a code host sends none. Pin it.

- [ ] **Step 1: Failing tests.** `WorkHookResourceTest` (Mockito `WorkService`/`WorkCodeHostService`, mock request with a byte body): `everyRejectionIsTheSame404` (flag off, work off, unknown format, GET, `authenticate` empty: response bodies and statuses byte-identical, `apply` never called); `aVerifiedDeliveryIs204WhateverItHolds` (`ping`, unparseable JSON, a push); `anOversizedBodyIs413AndIsNotChecked` (`authenticate` never called); `noSessionIsTouched` (`verify(request, never()).getSession()` and `getSession(anyBoolean())`); `aDatabaseFailureIs503WithNoDetail`; `theDeliveryLineCarriesNoBodyText` (log captured with the existing Log4j test appender used by `WorkMcpLoggingTest`; no commit message appears). `CsrfProtectionFilterTest`: `aHookPostWithNoOriginPassesUntouched` (chain called) and `aHookPostFromAForeignBrowserOriginIsRefused` (403). `CapabilitiesResourceTest`: `workCodeHost` false unless both flags. `ConfigSurfaceDriftTest` and `ConfigReferenceRegressionTest` pass after `--write`.
- [ ] **Step 2:** `mvn test -pl wikantik-rest -Dtest='WorkHookResourceTest,CapabilitiesResourceTest'`, `mvn test -pl wikantik-http -Dtest=CsrfProtectionFilterTest` → FAIL (the two CSRF tests pass already: that is the point of the pin; record it in the commit message).
- [ ] **Step 3:** Implement; `bin/config-reference.sh --write`; `mvn test -pl wikantik-main -Dtest='ConfigSurfaceDriftTest,ConfigReferenceRegressionTest'`.
- [ ] **Step 4:** Re-run → PASS.
- [ ] **Step 5: Commit** (the files above plus `docs/ConfigurationReference.md` and the regenerated wiki page by path) — `feat(work): signed, receive-only code-host webhook endpoint`.

### Task 5.4c: Managing the hook on the product page

**Files:** Modify `wikantik-rest/.../WorkProductResource.java`, `wikantik-frontend/src/components/work/WorkProducts.jsx`, `src/api/work.js`. Tests `WorkProductResourceTest.java`, `WorkProducts.test.jsx`, `src/api/work.test.js`.

**Interfaces — produces:**
- `POST /api/work/products/{key}/hook` → 200 `{ "secret": "<64 hex>", "githubUrl": "/api/work/hooks/<key>/github", "genericUrl": "/api/work/hooks/<key>/generic" }`; repeating it rotates. `DELETE /api/work/products/{key}/hook` → 204. Non-admin → 403 as product edits are; unknown → 404; archived → 409 `archived`.
- `GET /api/work/products` for an admin: each product gains `hook: { enabled, enabledAt, lastReceivedAt }` from `codeHost().hooks(actor)` (absent hook → `{enabled:false, enabledAt:null, lastReceivedAt:null}`); never a secret. Non-admins' rows are unchanged.
- `api/work.js`: `enableHook(key)`, `disableHook(key)`.
- `WorkProducts` (admins, and only when `useCapabilities().capabilities.workCodeHost`): per product, "Code host: on · last delivery <relative>" or "off"; buttons Turn on / New secret / Turn off (Turn off through `WorkConfirm`). After turning on: a panel shows the full URL (`window.location.origin` + `githubUrl`), the secret, "Shown once. In GitHub: Settings → Webhooks → Add webhook; content type application/json; events: Pushes, Pull requests, Pull request reviews.", and is cleared when dismissed or the page reloads.

- [ ] **Step 1: Failing tests.** Resource: the two routes; `secret` absent from `GET /products` and from `GET /products/{key}/history` after enabling; 403 for a member. vitest: the panel shows the secret once and it is gone after Dismiss; no hook controls when `workCodeHost` is false; Turn off asks for confirmation.
- [ ] **Step 2:** `mvn test -pl wikantik-rest -Dtest=WorkProductResourceTest`; `npx vitest run src/components/work/WorkProducts.test.jsx src/api/work.test.js` → FAIL.
- [ ] **Step 3:** Implement; `npm run lint`. **Step 4:** PASS.
- [ ] **Step 5: Commit** — `feat(work): turn a product's code-host webhook on and off`.

### Task 5.5: IT, gate and documentation

**Files:** Create `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkCodeHostIT.java`, `docs/admin/WorkCodeHost.md`. Modify `wikantik-it-tests/wikantik-it-test-rest/src/test/config/it-rest-cascade.properties` (`wikantik.work.codehost.enabled = true`), `docs/admin/Security.md`, `docs/admin/WikantikOperations.md`, `docs/developer/ProjectReference.md`, `docs/admin/McpAgents.md`, `docs/superpowers/specs/2026-11-15-work-management-design.md`, `wikantik-work/CLAUDE.md`, `wikantik-work-mcp/CLAUDE.md`, `CHANGELOG.md`.

- [ ] **Step 1: IT** `WorkCodeHostIT` (payloads built inline with GitHub's field names, signed with `javax.crypto.Mac` in the test; plain `HttpClient`, no cookies, no `Origin`): admin creates product `hookprod` (default branch `main`), project `HOOK`, item `HOOK-1` targeting it, account `hookana` (`hookana@example.com`) as a member; `POST /api/work/products/hookprod/hook` gives the secret. `oneMergedReviewedChangeFromFiveDeliveries`: post signed `push` (branch `HOOK-1-fix`, author email `hookana@example.com`, username `hookana-gh`), `pull_request` opened, `pull_request_review` approved by `reviewer-gh` for the head, `pull_request` closed merged (`merge_commit_sha` M), `push` to `main` with `after` M and the merge commit message `Merge pull request #3 from o/HOOK-1-fix`; every answer is 204; `GET /api/work/items/HOOK-1/changes` shows one change, `mergeKind` `reviewed`, `openedByName` `Hookana …`, `firstCommitAt` set, `policyFlag` null. `repostingAllFiveChangesNothing`: post all five again → still one change, same `firstCommitAt`. `aDirectPushWaitsForALead`: post a `push` to `main` with a distinct commit `HOOK-1 hotfix` → `GET /api/work/me` as the admin (a lead) lists `HOOK-1` with `waiting.reason` `change_outside_policy`; as `hookana` (member) it does not; `POST changes/{id}/acknowledge-flag` as admin → 200, and the lane is empty. `badDeliveriesAreTheSame404`: unknown product, wrong signature, missing header → identical status and body. Run `bin/run-tests.sh --module rest` through `bin/agent-build.sh start it-rest -- bin/run-tests.sh --module rest` and poll `status`/`wait` until it ends.
- [ ] **Step 2: Docs**
  - `docs/admin/WorkCodeHost.md` (new): turning it on (`wikantik.work.codehost.enabled`, then a wiki admin turns the hook on per product and copies the URL and secret once); GitHub setup (Payload URL, content type `application/json`, the secret, events Pushes, Pull requests, Pull request reviews; the `ping` shows as "last delivery"); the generic format with every field, a `curl` example (`printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET"` → `X-Wikantik-Signature: sha256=<hex>`) and a sample `post-receive` hook that posts a `push` per updated ref; what becomes what (the design's table plus P5-1, P5-2, P5-5, P5-10, P5-13); how people are matched (email, learned usernames, noreply addresses never, outside authors); what is flagged (D17) and how a lead allows direct commits on an epic or item; that nothing calls GitHub and no GitHub token exists; that a dismissed review is not seen (P5-11) and a commit first pushed to a branch with no item key is never direct (P5-1); rotating and turning off.
  - `docs/admin/Security.md`: the endpoint has no session and no credential, only the HMAC over the body; every rejection is one 404 with one timing (P5-15); it needs **no** CSRF exemption because `/api/*` is Origin-checked and a code host sends no Origin (and the endpoint acts on no cookie); the secret is stored as issued, shown once, rotated by turning on again; a delivery can only fill in change records of items that target that product.
  - `docs/admin/WikantikOperations.md`: the two keys, enabling order, `wikantik_work_codehost_events_total{kind,outcome}`, the delivery log line, what a 503 means (redeliver from GitHub's Recent Deliveries).
  - `docs/developer/ProjectReference.md` (Work management status): code-host integration built (V088, the facade `codeHost()`, the endpoint, the decisions P5-1…P5-17).
  - `docs/admin/McpAgents.md` and `wikantik-work-mcp/CLAUDE.md` ("Change tools"): `first_commit_at` on `open_change`/`update_change`; the new change fields; `change_closed:` when the code host already merged; system events render as actor `GitHub`, `actorKind: system`.
  - `wikantik-work/CLAUDE.md`: a section "Code host" — receive only; `Actor.Kind.SYSTEM` is refused by every store writer except `JdbcWorkCodeHost*` and has no standing; `HookDelivery` comes only from `authenticate`; per-product advisory lock first, then change rows in id order, then the item; a head is never re-applied; direct changes keyed by `(item_id, merged_sha = push after)`; `superseded_by` rows count nowhere (Part 6 must exclude them); flags are set only by code-host merges and never cleared; the notification rule from Task 5.3c; migrations list gains V088.
  - `docs/superpowers/specs/2026-11-15-work-management-design.md`: an inbound code-host integration exists (receive only); issue sync, enforcement on the host and taking deployments from it remain non-goals.
  - `CHANGELOG.md` `[Unreleased]`: one entry for the integration, one for `first_commit_at` reported by hand or MCP.
- [ ] **Step 3: Gate.** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll `bin/agent-build.sh status gate` / `wait gate 540` until `SUCCESS` (fix and re-run on `FAILED`); `mvn pmd:check -Pcomplexity-gate` clean.
- [ ] **Step 4: Commit** (every doc and the IT by path) — `docs(work): code-host integration`.

## Deviations from the outline

- **No `codehost/` subpackage.** The outline put the new classes in `codehost/`. They stay in `com.wikantik.work` (names prefixed `CodeHost…`, `JdbcWorkCodeHost…`, `JdbcWorkHooks`, `WorkCodeHostStore/Service`), because `WorkStandingSourceRuleTest` and `WorkAutonomySourceRuleTest` scan that one directory non-recursively, `WorkAccessArchTest` allows store access only from the exact package, and the classes need package-private `WorkScopes`, `JdbcWorkCatalog` and `JdbcWorkChanges` members.
- **No CSRF exemption.** The outline adds one "as the SAML callback is". `/api/*` already uses the Origin check, which passes a request with no Origin, so the code host's POST goes through unchanged. Task 5.4b pins this with a test instead of widening the filter.
- **The HMAC check lives in `wikantik-work` (`CodeHostSecrets`, `authenticate`), not in a REST `HookSignatures`.** That way the secret never leaves the module. `apply` takes a `HookDelivery` that only `authenticate` can create.
- **`CodeEvent` changed shape.** `host` is dropped (it comes from the URL, P5-12). Added: `defaultBranch`, `before`, `after`, `at`, `Kind.REVIEW_UPDATED` (synchronize), `Commit.distinct`, `Review.draft`. These are needed for the two hard cases and P5-13.
- **V088 adds `superseded_by` and `idx_work_change_review`, and the flag index leaves out superseded rows.** The outline's rule ("rewrite the direct row into the review's merge") would leave the pull request's own live change open and throw away what agents reported on it (P5-2).
- **Two extra event types: `change_flagged` and `change_superseded`** (alongside `change_flag_acknowledged`). The notification rule routes `change_flagged`; it no longer has to look at a `change_merged` event for a flag (P5-14).
- **A direct change is keyed by the push's `after`, one per item per push, and counts only distinct commits.** The outline had one per commit, keyed by the commit (P5-1). The outline's skip rules are kept and extended to cover every commit of the push.
- **The merge's policy is resolved before the transaction** (P5-6), not "under the item lock".
- **The V082 `work_deployment_event.actor_kind` CHECK is left alone.** The system actor cannot write deployments.
- **Matching email without regard to case needs `UserDatabase.loginsForEmailIgnoreCase`.** The outline names only `WorkDirectory.loginForEmail`, but the existing `findByEmail` is exact and returns one profile.
- **Hook management is on the `codeHost()` facade**, not `WorkRegistryService`. The registry covers queues, agents and identity, and keeping it out of there holds the PMD limits. The product-event row and the audit record are as the outline says.
- **The metric gains an outcome `rejected` (kind `delivery`)**, so unverified deliveries are counted too.
- **The UI field for typing a first commit time stays in Part 6, Task 6.4**, as the outline places it. This part adds it to REST and MCP and shows it read-only.
