# Work Management Phase 2 (Agents) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add queues, an agent registry, claims that expire, the `work` API-key scope and the `/work-mcp` endpoint, so work moves human→agent, agent→agent, agent→human and human→human, and an agent sees only the projects it was admitted to.

**Architecture:** `wikantik-work` gains queue, agent and claim tables (V076), records every agent write by its `work_agent` id, and adds the claim operations to `WorkStore`/`WorkService` (claim logic in package-private collaborators so `WorkService` stays a thin door). `wikantik-main` derives the actor from a bound API key, mints agent keys through a port, and runs a one-minute expiry sweep. `wikantik-rest` accepts `work`-scoped bearer keys on `/api/work/*`. A new `wikantik-work-mcp` module serves `/work-mcp` on `wikantik-mcp-core`, key-only, with nine tools. The SPA shows queues, claims, agents and the agent→human lane.

**Tech Stack:** Java 25, Maven, PostgreSQL 15+ (prod 18) via `com.wikantik.jdbc.Jdbc`, MCP Java SDK 2.2.0 (`HttpServletStreamableServerTransportProvider.Builder.contextExtractor`), JUnit 5 + Mockito + ArchUnit + `PostgresTestDb`, Gson, React 19 + Vite + vitest. No new runtime dependencies, Java or npm.

**Spec:** `docs/superpowers/specs/2026-11-15-work-management-design.md` — read it first; this plan covers phase 2 only. Phase 1 plan and its execution ledger (rulings R1–R23): `docs/superpowers/plans/2026-11-15-work-management-phase1-core.md`, `.superpowers/sdd/2026-11-15-work-management-phase1-core/progress.md`. Module rules: `wikantik-work/CLAUDE.md`.

## Global Constraints

- All database access through `com.wikantik.jdbc.Jdbc`; multi-statement writes use `inTransaction` and each gets a rollback test with `FaultInjectingDataSource`.
- Schema only via `bin/db/migrations/V076__work_management_agents.sql`: idempotent DDL only, `:app_user` grants, a true no-op when re-applied. Never edit V074/V075.
- Database tests: `@RequiresPostgres`, `PostgresTestDb.createDataSource()`, `PostgresTestDb.truncate(…)` in `@BeforeEach`; never `DROP`/`ALTER`; never truncate `api_keys` (insert a fresh row with a random `key_hash` per agent instead).
- `wikantik-work` depends on neither `wikantik-api` nor `wikantik-main`; API keys reach it only through the `AgentKeys` port. Nothing outside `com.wikantik.work` (other than `WorkWiring`) touches `WorkStore`.
- Unknown and hidden are the identical 404 (REST) or the identical tool error `Not found.` (MCP), on every surface including every new one. An agent sees only projects with an `agent` member row naming it; it never inherits its key owner's or any group's access, and an agent actor's `admin` is always `false`.
- An agent's role is `viewer` or `member`, never `lead`. Agents are denied `SET_POLICY`, `MANAGE_PROJECT`, `MANAGE_INSTANCE` (R5). Only a human wiki Admin manages queues and agents.
- `actor_kind` is derived from the key binding, never supplied by a caller: a key bound to an active `work_agent` is that agent; a key bound to a retired agent is no actor at all; any other key or session is its human owner.
- Every write by an agent records its `work_agent` id: `work_event.agent_id`, `work_comment.author_agent_id`, `work_item.reporter_agent_id`, `work_item_revision.edited_by_agent_id`. Identity checks on agents compare ids, never names.
- `work_event` stays append-only (grants `SELECT, INSERT` only). Every state change, including claim, renewal, release and expiry, writes its event in the same transaction.
- Releasing or expiring a claim touches only the claim columns, `status` and `version`; it never clears or changes anything else on the item (phase 3 changes stay attached).
- Claims, queues and agents carry no autonomy or gate logic. Policy columns stay inert. Do not encode "no sprints" anywhere.
- `/metrics`: instance-wide counts only; label keys `status, level, kind`, `action` and `outcome` only. No project, product, item, queue or agent labels.
- Feature flag `wikantik.work.enabled` (default `false`): when off, `/api/work/*` and `/work-mcp` return 404 and nothing of work is started.
- Non-admins see and send display names only (R19); logins and API-key ids reach Admins only. Agent and queue names are not confidential to anyone who belongs to a project.
- Never swallow an exception; log at least `LOG.warn` with context. Use `RestServletBase.sendError`. A new module needs `mockito-core` (test scope).
- PMD: run from the root, `mvn -B -fae pmd:check -Pcomplexity-gate`; the baseline never grows. Coverage floors capped at `0.90`.
- Long Maven runs via `bin/agent-build.sh`. No `News.md` entries. Stage files by name; never `git add -A`. Work on `main`.

## Review Focus

1. **Two agents claim the same item at once, or a renewal races the expiry sweep.** Expect exactly one holder and one `claimed` event; the loser gets 409 `not_claimable` naming the holder; a renewal that loses to the sweep becomes a fresh claim (`claim_expired` then `claimed`), never a lost or doubled claim. Pinned in Task 5.
2. **An agent loses access while holding a claim** (removed from the project, demoted to viewer, retired, or its key rotated). Expect the claim released in the same transaction with a `released` event, the item back in its queue, and the agent's next call refused (404 or 401), with no fallback to the key owner. Pinned in Tasks 5, 6 and 12.
3. **An agent whose key belongs to a project lead or a wiki Admin.** Expect it to see only projects it was admitted to, on REST and MCP alike. Pinned in Tasks 6 and 10.
4. **A person moves, reassigns or releases an item an agent holds.** Expect the person to succeed (people are never locked out), the claim cleared, and the agent's next write refused with the reason (403 claimed by another / 409 stale), nothing silently overwritten. Pinned in Tasks 3 and 7.
5. **An agent named like a person** (an agent `jake` while a user's login is `jake`, or a display name `Jane Doe` and agent `jane-doe`). Expect no crossover: the person cannot edit the agent's comments, the agent gets none of the person's items or projects, and names display correctly. Pinned in Tasks 6 and 7.

## Decisions made in this plan

- **Queue assignee is a separate column `work_item.assignee_queue`** (FK `work_queue.key`), with `CHECK (assignee IS NULL OR assignee_queue IS NULL)`. Why: `assignee` keeps meaning "a login" for every phase 1 query (waiting-on-you, filters), so no query can mistake a queue key for a person. Cost if wrong: one column to fold later.
- **Event values name a queue as `queue:<key>`** (`assigned`: `queue:reviewer`; `handoff`: `review|queue:reviewer`); member events name an agent `agent:<id>:<role>`. Why: events need one text value per side. Cost: a login literally shaped `queue:x` would display as a queue in the timeline; cosmetic only, events never grant access.
- **Agent writes carry the agent id in dedicated columns** (`reporter_agent_id`, `author_agent_id`, `edited_by_agent_id`; `work_event.agent_id` gets its FK) and the text column holds the agent name at write time. Why: logins have no reserved characters, so no text prefix can tell an agent from a person. Cost: three nullable columns.
- **Store writes take `Actor by`** in place of `(String actor, Actor.Kind kind)`. Why: the store must write the agent id; one signature change instead of a parallel parameter. Cost: a mechanical edit across store, service and tests.
- **Claimable = assigned to a queue the agent serves, unclaimed, not blocked, status `ready` or `review`.** Claiming `ready` moves it to `in_progress`; claiming `review` leaves it in `review`. Expiry or release returns `in_progress` to `ready` and leaves `review` as is. Why: the spec routes agent→agent review through a queue, which needs a review claim (see Questions). Cost if the owner wants ready-only: drop `'review'` from one SQL list and one test.
- **Only agents claim; people are assigned.** A human `claim` is denied. Any human member may release a claim (unsticking); among agents only the holder may edit, move, link, hand off or release a claimed item. Why: spec ("People are assigned directly; agent work goes to a queue"; people never locked out). Cost: none foreseen.
- **Claim renewal is the same call**: `claim` by the holder extends `claim_expires_at`, writes `claim_renewed`, and does not bump `version`. Why: one tool to learn; agents' in-flight edits are not made stale by a renewal. Cost: none.
- **Expiry is swept, not lazy**: a single scheduler thread runs `sweepExpiredClaims()` every 60 s (`FOR UPDATE SKIP LOCKED`, 500 rows per pass). Why: the `claim_expired` event is written when it happens, the board needs no "effective state" logic, and concurrent sweeps on two nodes cannot double-write. Cost: an expired claim may sit up to 60 s; until swept, only its holder can renew it.
- **Default claim expiry 240 minutes, key `wikantik.work.claim.expiryMinutes`** (int, 1–10080; out of range → default with a warn). Why: phases 1–2 assume attended sessions ("generous claim expiry"); 4 h covers a working block. Cost: a crashed session holds an item up to 4 h unless a person releases it.
- **Registering an agent mints its key** (`work` scope, owner = a named person, default the registering Admin); rotation mints a new key, rebinds, then revokes the old one; retirement revokes the key and releases the agent's claims. Pre-existing keys cannot be bound. Why: guarantees one key ↔ one agent and the narrowest scope; nothing assumes one person owns every agent. Cost: an operator cannot reuse an `all` key for an agent.
- **`work` scope matches only `work` (and `all` keys, as every scope does).** Non-admins may self-mint `work` keys. Why: an unbound `work` key acts exactly as its owner's session would. Cost: none.
- **`/work-mcp` is key-only**: its access filter ignores `mcp.access.allowUnrestricted` and `mcp.access.allowedCidrs`. Why: every tool call needs an actor; the shared unrestricted/CIDR modes skip key verification entirely. Cost: an operator cannot open `/work-mcp` by IP.
- **REST accepts bearer keys on `/api/work/*` only** (new `WorkApiKeyFilter`), session auth unchanged. Why: the spec promises agents REST as well as MCP; widening bearer auth to all of `/api/*` is out of scope. Cost: none.
- **Removing an agent member or demoting it to `viewer` releases its claims in that project** in the same transaction. Why: a claim the holder can no longer see would strand the item for hours. Cost: none.
- **Queues are instance-wide, any queue may be used in any project; a queue can be retired** (`retired_at`: hidden from pickers, refused for new assignment; items already in it stay claimable). Why: nothing is hard-deleted and a mistyped queue must not stay in every picker. Cost: one column.
- **Agent names match `^[a-z0-9][a-z0-9._-]{0,39}(@[a-z0-9][a-z0-9.-]{0,39})?$`, unique; queue keys match `^[a-z][a-z0-9-]{1,29}$`.** Agents are not mentionable in phase 2 (mentions resolve wiki users only). Cost: an `@agent` mention needs a later change.
- **Registry changes (queues, agents) write no `work_event`** (its `project_id` is `NOT NULL`, as for products); agent membership changes do write `member` events; registry writes are logged at INFO by `WorkRegistry` (who, what). Cost: no in-product history of registry edits.
- **`get_item`/`claim_item` return Markdown source, not HTML**, and the item's product `wikiCluster`. Why: agents edit source; the spec requires the cluster on claim. Cost: none.
- **Project key `AGENTS` becomes reserved** (the admin page lives at `/work/agents`, like `PRODUCTS`). Cost: an existing project of that key would need renaming (none expected).
- **Claim and registry SQL live in package-private collaborators `JdbcWorkRegistry` and `JdbcWorkClaims`; service logic in `WorkRegistry` and `WorkClaims`,** with `JdbcWorkStore`/`WorkService` delegating (the R7 pattern). Why: `WorkService` is already 462 lines and the PMD baseline may not grow. Cost: small indirection.

## Questions for the owner

1. **May agents claim `review` work?** The spec says "only `ready` work is claimable", yet routes agent→agent review through a queue ("a move to another queue", `reviewer`). *Recommended: yes — `ready` and `review` are claimable; a review claim keeps the status `review`.* The plan is written this way.
2. **May an agent create items over MCP (`create_item`)?** Story refinement often means splitting a story into tasks; the role policy already lets a `member` create. *Recommended: yes, with new items landing in `draft`.* The plan includes the tool; dropping it removes one tool class and one test file.

---

## File Structure

```
bin/db/migrations/V076__work_management_agents.sql
wikantik-work/src/main/java/com/wikantik/work/
  Queue, Agent, AgentDraft, WaitingItem, ClaimOutcome, WorkSettings          records/enums (new)
  WorkListener, AgentKeys                                                   ports (new)
  JdbcWorkRegistry, JdbcWorkClaims                                          SQL collaborators (new)
  WorkRegistry, WorkClaims                                                  service collaborators (new)
  Actor, Item, ItemDraft, ItemFilter, Comment, CommentView, ItemRevision,
  WorkEvent, Action, WorkPolicy, WorkInputs, WorkConflictException,
  WorkEventDiff, ItemPatchApplier, ItemViews, WorkStore, JdbcWorkStore,
  JdbcWorkCatalog, JdbcWorkDiscussion, WorkService, WorkMetrics              modified
wikantik-main/src/main/java/com/wikantik/auth/apikeys/ApiKeyService.java    Scope.WORK
wikantik-main/src/main/java/com/wikantik/work/runtime/
  WikiAgentKeys, WorkClaimSweeper (new); WikiWorkActors, WorkWiring (modified)
wikantik-mcp-core/…/McpEndpointBootstrapper.java, McpAccessFilter.java      key-only + context extractor
wikantik-work-mcp/                                                          new module
  src/main/java/com/wikantik/work/mcp/
    WorkMcpInitializer, WorkMcpActors, WorkMcpTool, WorkMcpJson
    GetWorkContextTool, ListClaimableTool, GetItemTool, ClaimItemTool, UpdateItemTool,
    CommentOnItemTool, HandoffItemTool, ReleaseItemTool, CreateItemTool
  src/main/resources/wikantik-work-mcp-instructions.txt
wikantik-rest/src/main/java/com/wikantik/rest/
  WorkApiKeyFilter, WorkQueueResource, WorkAgentResource (new)
  WorkResourceBase, WorkItemResource, WorkProjectResource, WorkMeResource, WorkJson, SelfApiKeysResource (modified)
wikantik-frontend/src/
  api/work.js; components/work/AssigneePicker.jsx, WorkAgents.jsx, WorkClaim.jsx (new, + tests)
  components/work/WorkCard, WorkBoard, WorkItemView, WorkHandoff, WorkSettings, WorkTimeline, WorkHome, eventText.js;
  components/admin/AdminApiKeysPage.jsx, components/MyApiKeys.jsx; main.jsx  (modified)
```

---

### Task 1: V076 migration

**Files:**
- Create: `bin/db/migrations/V076__work_management_agents.sql`
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkAgentsSchemaTest.java`

**Interfaces:**
- Produces: tables `work_queue(key, name, description, created_at, retired_at)`, `work_agent(id, name, vendor, runtime, api_key_id, created_at, retired_at)`, `work_agent_queue(agent_id, queue_key)`; columns `work_item.assignee_queue, claimed_by_agent, claimed_at, claim_expires_at, reporter_agent_id`, `work_comment.author_agent_id`, `work_item_revision.edited_by_agent_id`; FK `work_event.agent_id → work_agent.id`; `api_keys.scope` admits `'work'`.

- [ ] **Step 1: Write the failing schema test** (`@RequiresPostgres`; truncate `work_agent_queue, work_agent, work_queue` and the nine phase 1 tables):
  ```java
  @Test void phaseTwoTablesExist()            // information_schema.tables has work_queue, work_agent, work_agent_queue
  @Test void itemCannotHaveBothAssigneeAndQueue()   // UPDATE … SET assignee='lena', assignee_queue='implementer' → SQLState "23514"
  @Test void claimColumnsAreAllOrNothing()    // claimed_by_agent set, claim_expires_at NULL → "23514"
  @Test void oneKeyBindsOneAgent()            // two work_agent rows with the same api_key_id → "23505"
  @Test void agentQueueNeedsAKnownQueue()     // work_agent_queue row with queue_key 'nope' → "23503"
  @Test void queueKeyPatternEnforced()        // work_queue key 'Implementer' → "23514"
  @Test void agentNamePatternEnforced()       // work_agent name 'Codex Bot' → "23514"; 'codex@docker2' accepted
  @Test void apiKeyScopeAdmitsWork()          // INSERT INTO api_keys (… scope) VALUES (… 'work') succeeds
  @Test void eventAgentIdMustNameAnAgent()    // work_event with agent_id 999999 → "23503"
  ```
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest=WorkAgentsSchemaTest` — expect FAIL (relations missing).
- [ ] **Step 3: Write the migration**, header comment naming V074 and V010 as prerequisites:
  - `work_queue`: `key TEXT PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9-]{1,29}$')`, `name TEXT NOT NULL`, `description TEXT NOT NULL DEFAULT ''`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `retired_at TIMESTAMPTZ`.
  - `work_agent`: `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `name TEXT NOT NULL UNIQUE CHECK (name ~ '^[a-z0-9][a-z0-9._-]{0,39}(@[a-z0-9][a-z0-9.-]{0,39})?$')`, `vendor TEXT NOT NULL DEFAULT ''`, `runtime TEXT NOT NULL DEFAULT ''`, `api_key_id INTEGER NOT NULL UNIQUE REFERENCES api_keys (id)`, `created_at`, `retired_at`.
  - `work_agent_queue`: `agent_id BIGINT NOT NULL REFERENCES work_agent (id)`, `queue_key TEXT NOT NULL REFERENCES work_queue (key)`, `PRIMARY KEY (agent_id, queue_key)`.
  - `ALTER TABLE … ADD COLUMN IF NOT EXISTS` with the FK inline (so a re-run adds nothing): `work_item.assignee_queue TEXT REFERENCES work_queue (key)`, `claimed_by_agent BIGINT REFERENCES work_agent (id)`, `claimed_at TIMESTAMPTZ`, `claim_expires_at TIMESTAMPTZ`, `reporter_agent_id BIGINT REFERENCES work_agent (id)`; `work_comment.author_agent_id BIGINT REFERENCES work_agent (id)`; `work_item_revision.edited_by_agent_id BIGINT REFERENCES work_agent (id)`.
  - CHECKs `work_item_one_assignee_chk (assignee IS NULL OR assignee_queue IS NULL)`, `work_item_claim_chk ((claimed_by_agent IS NULL) = (claim_expires_at IS NULL) AND (claimed_by_agent IS NULL) = (claimed_at IS NULL))` and FK `work_event_agent_fk` each added inside a `DO $$ … IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '…') THEN ALTER TABLE … END IF; $$` block.
  - `api_keys_scope_chk`: `DROP CONSTRAINT IF EXISTS` + `ADD CONSTRAINT … CHECK (scope IN ('mcp','mcp_admin','mcp_read','tools','all','work'))` (the V061 pattern).
  - Indexes: `idx_work_item_claimable ON work_item (assignee_queue, status, priority) WHERE claimed_by_agent IS NULL AND assignee_queue IS NOT NULL`; `idx_work_item_claim_expiry ON work_item (claim_expires_at) WHERE claimed_by_agent IS NOT NULL`; `idx_work_agent_queue_queue ON work_agent_queue (queue_key)`.
  - Grants: `SELECT, INSERT, UPDATE` on `work_queue, work_agent`; `SELECT, INSERT, DELETE` on `work_agent_queue` (derived rows, replaced on update); nothing new on `work_event`.
- [ ] **Step 4: Run** the test — expect PASS. Then apply the migration a second time by hand to the test container (`psql -v app_user=… -f bin/db/migrations/V076__work_management_agents.sql`) and confirm it reports no change and no error.
- [ ] **Step 5: Commit** `feat(work): V076 queues, agent registry, claims and agent attribution`.

---

### Task 2: Domain types and policy

**Files:**
- Create in `wikantik-work/src/main/java/com/wikantik/work/`: `Queue.java`, `Agent.java`, `AgentDraft.java`, `WaitingItem.java`, `ClaimOutcome.java`, `WorkSettings.java`, `WorkListener.java`, `AgentKeys.java`
- Modify: `Actor.java`, `Item.java`, `ItemDraft.java`, `ItemFilter.java`, `Comment.java`, `CommentView.java`, `ItemRevision.java`, `WorkEvent.java`, `Action.java`, `WorkPolicy.java`, `WorkInputs.java`, `WorkConflictException.java`, `JdbcWorkStore.java` / `JdbcWorkDiscussion.java` row mappers (read the new columns), every `new Item(` / `new ItemDraft(` / `new ItemFilter(` / `new Comment(` site in main and test code of `wikantik-work` and `wikantik-rest` (`WorkHttp`)
- Test: `WorkPolicyTest` (extend), `WorkInputsAgentTest.java`, `ActorTest.java`

**Interfaces:**
- Produces:
  - `Actor.agent(long agentId, String name) : Actor` = `new Actor(name, Set.of(), false, Kind.AGENT, agentId)`.
  - `Item` appends `String queue, Long claimedByAgent, Instant claimedAt, Instant claimExpiresAt, Long reporterAgentId`.
  - `ItemDraft` appends `String queue`. `ItemFilter` appends `String queue`; `with(projectId, assignee)` keeps it; new `ItemFilter withQueue(String queue)`.
  - `Comment` and `CommentView` append `Long authorAgentId`; `ItemRevision` appends `Long editedByAgentId`.
  - `record Queue(String key, String name, String description, Instant retiredAt)`.
  - `record Agent(long id, String name, String vendor, String runtime, Integer apiKeyId, List<String> queues, Instant createdAt, Instant retiredAt)` with `boolean active()` (`retiredAt == null`).
  - `record AgentDraft(String name, String vendor, String runtime, List<String> queues, String owner)` — `owner` names the key's person (display name, or a login from an Admin); null = the caller.
  - `record WaitingItem(Item item, String reason, WorkEvent handoff)`; `static final String ASSIGNED = "assigned", MENTIONED = "mentioned"`; `handoff` nullable.
  - `enum ClaimOutcome { CLAIMED, RENEWED, REFUSED, RELEASED, EXPIRED; String wire(); }` (lowercase).
  - `record WorkSettings(Duration claimExpiry)`; `static final WorkSettings DEFAULTS = new WorkSettings(Duration.ofMinutes(240))`.
  - `interface WorkListener { default void denied(Action a) {} default void claim(ClaimOutcome o) {} }`.
  - `interface AgentKeys { record Minted(int keyId, String token) {} Minted mint(String ownerLogin, String label); void revoke(int keyId, String revokedBy); AgentKeys NONE = … }` — `NONE.mint` throws `WorkConflictException("Agent keys are not available.")`.
  - `WorkEvent` constants `CLAIMED = "claimed"`, `CLAIM_RENEWED = "claim_renewed"`, `RELEASED = "released"`, `CLAIM_EXPIRED = "claim_expired"`.
  - `Action.CLAIM`. `WorkConflictException.NOT_CLAIMABLE = "not_claimable"`, `NOT_CLAIMED = "not_claimed"`.
  - `WorkInputs.requireQueueKey(String)`, `requireAgentName(String)` (patterns from Task 1, `WorkValidationException` naming field `key` / `name`), `requireAgentRole(WorkRole)` (`LEAD` → field `role`, "An agent's role is viewer or member."); `RESERVED_KEYS` adds `AGENTS`; `requireMember` no longer rejects `principalKind=agent` (lifts R21; Task 6 validates against the registry).

- [ ] **Step 1: Write the failing tests.**
  - `ActorTest.agentHasNoGroupsAndIsNeverAdmin()`: `Actor.agent(7, "codex@docker2")` → `groups()` empty, `admin()` false, `kind()` AGENT, `agentId()` 7, `login()` "codex@docker2".
  - `WorkPolicyTest`, new rows (parameterised):

    | Actor | Role | CLAIM | EDIT_ITEM / MOVE / LINK on an item claimed by agent 9 | same, claimed by self (agent 7) | COMMENT on it |
    |---|---|---|---|---|---|
    | agent 7 | viewer | deny | deny | deny | deny |
    | agent 7 | member | allow | deny | allow | allow |
    | human | member | deny | allow | — | allow |
    | human admin | none | deny | allow | — | allow |

    plus `claimDeniedToHumansSaysAssignInstead()` (reason contains "assign"), `claimedByOtherDenialNamesNoLogin()` (reason does not contain any login).
  - `WorkInputsAgentTest`: `queueKeyPattern()` (`implementer` ok; `Impl`, `i`, 31 chars rejected), `agentNamePattern()` (`codex@docker2`, `claude-ws` ok; `Codex`, `a b`, `x@` rejected), `agentLeadRoleRejected()`, `agentsProjectKeyIsReserved()` (`requireUnreservedKey("AGENTS")` throws), `agentPrincipalKindAccepted()`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest='WorkPolicyTest,WorkInputsAgentTest,ActorTest'` — expect FAIL.
- [ ] **Step 3: Implement** the types; extend `WorkPolicy.may` in this order: (1) `CLAIM` by a `HUMAN` → deny "People are assigned work; assign it to yourself instead."; (2) the R5 management rule; (3) an `AGENT` whose id differs from a non-null `item.claimedByAgent()` asking `EDIT_ITEM`, `MOVE` or `LINK` → deny "This item is claimed by another agent."; (4) the existing admin/role logic, `CLAIM` needing `MEMBER`. Map the new columns in the row mappers (`assignee_queue` → `queue`). Fix every constructor call site.
- [ ] **Step 4: Run** the Step 2 command, then `mvn -q test-compile -pl wikantik-work,wikantik-main,wikantik-rest -am` — expect PASS / clean compile.
- [ ] **Step 5: Commit** `feat(work): agent, queue and claim domain types; claim policy`.

---

### Task 3: Store — agent attribution, queue assignment, handoff

**Files:**
- Modify: `WorkStore.java`, `JdbcWorkStore.java`, `JdbcWorkCatalog.java`, `JdbcWorkDiscussion.java`, `WorkEventDiff.java`, `WorkService.java` and its collaborators (call sites only), existing store/service tests (signature only)
- Test: `JdbcWorkStoreAttributionPostgresTest.java`, `JdbcWorkStoreHandoffRollbackTest.java`

**Interfaces:**
- Consumes: Task 2 types; V076 columns.
- Produces (`WorkStore`; all throw `SQLException`). Each `(String actor, Actor.Kind kind)` pair becomes `Actor by`:
  - `updateProject(long id, String name, String description, Autonomy defaultAutonomy, Actor by)`; `archiveProject(long id, Instant at, Actor by)`
  - `putMember(long projectId, String principal, String principalKind, WorkRole role, Actor by)`; `removeMember(long projectId, String principal, String principalKind, Actor by)`
  - `createItem(Project project, Item draft, Actor by)` — writes `assignee_queue` from `draft.queue()`, `reporter`=`by.login()`, `reporter_agent_id`=`by.agentId()`.
  - `updateItem(Item current, Item next, Actor by, String note)` — also writes `assignee_queue`; clears `claimed_by_agent, claimed_at, claim_expires_at` when status, `assignee` or `assignee_queue` changes.
  - `Item handoff(Item current, Item next, Actor by, String note)` — new; same guarded `UPDATE` and claim clearing, always exactly one `handoff` event, from `status|target` to `status|target`.
  - `addComment(Item item, Actor by, String body, List<String> mentioned)`; `editComment(long commentId, String body, Actor by, List<String> mentioned)`; `removeComment(long commentId, Actor by)`; `addLink(Item item, String kind, String target, Actor by)`; `removeLink(long linkId, Actor by)`
  - `Map<Long, WorkEvent> lastHandoffs(Collection<Long> itemIds)` — newest `handoff` event per item (`DISTINCT ON (item_id) … ORDER BY item_id, at DESC, id DESC`).
  - Every event row: `actor`=`by.login()`, `actor_kind` from `by.kind()`, `agent_id`=`by.agentId()`. Revisions: `edited_by_agent_id`. Comments: `author_agent_id`.
  - Assignment text in `assigned`/`handoff` values: a login as is, a queue as `queue:<key>`; `WorkEventDiff` treats `(assignee, queue)` as one assignment (moving login→queue is one `assigned` event).
  - `ItemQuery.conditions` honours `ItemFilter.queue()` (`i.assignee_queue = ?`) for `items` and `board`.

- [ ] **Step 1: Write the failing tests.**
  - `agentCommentRecordsAgentId()`: `addComment(item, Actor.agent(a, "codex@docker2"), "hi", List.of())` → `comment.authorAgentId()==a`, `author()=="codex@docker2"`, the `commented` event has `actorKind()==AGENT`, `agentId()==a`.
  - `agentCreateRecordsReporterAgent()`; `agentEditRecordsRevisionAgent()` (title change → revision `editedByAgentId()==a`).
  - `humanWritesLeaveAgentColumnsNull()`.
  - `assigningAQueueWritesQueueValue()`: assignee `lena` → queue `implementer` → one `assigned` event, from `"lena"` to `"queue:implementer"`; item `assignee()==null`, `queue()=="implementer"`.
  - `handoffToQueueIsOneEvent()`: `in_progress` + queue `implementer` → `review` + queue `reviewer`, note "ready for review" → exactly one new event, type `handoff`, from `"in_progress|queue:implementer"`, to `"review|queue:reviewer"`, note kept.
  - `humanMoveClearsClaim()` (Review Focus 4): set a claim on the row by SQL, `updateItem` by a human changing status → `claimedByAgent()==null`, `claimExpiresAt()==null`.
  - `bodyEditKeepsClaim()`: same setup, change only `body` → claim columns unchanged.
  - `lastHandoffsPicksNewestPerItem()`; `queueFilterMatchesOnlyThatQueue()` (items and board).
  - Rollback: `handoffRollsBackWhenEventInsertFails()` — item status, queue, version and claim unchanged.
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest='JdbcWorkStoreAttribution*,JdbcWorkStoreHandoffRollbackTest'` — expect FAIL.
- [ ] **Step 3: Implement**, then change every caller (`WorkService` passes `actor`) and every existing test call (`Actor.human("lena", Set.of(), false)` in place of `"lena", Actor.Kind.HUMAN`).
- [ ] **Step 4: Run** `mvn test -pl wikantik-work` — expect PASS (whole module: the signature change touches every store test).
- [ ] **Step 5: Commit** `feat(work): store writes record the agent; queue assignment and handoff`.

---

### Task 4: Store — queue and agent registry

**Files:**
- Create: `JdbcWorkRegistry.java` (package-private; `JdbcWorkStore` delegates)
- Modify: `WorkStore.java`, `JdbcWorkStore.java`
- Test: `JdbcWorkRegistryPostgresTest.java`, `JdbcWorkRegistryRollbackTest.java`

**Interfaces:**
- Produces (`WorkStore`):
  - `Queue createQueue(String key, String name, String description)`; `Optional<Queue> queue(String key)`; `List<Queue> queues()` (by key); `void updateQueue(String key, String name, String description)`; `void retireQueue(String key, Instant at)`
  - `Agent registerAgent(String name, String vendor, String runtime, int apiKeyId, List<String> queues)` — one transaction: agent row + one `work_agent_queue` row per queue.
  - `Optional<Agent> agent(long id)`; `Optional<Agent> agentByName(String name)` (case-insensitive); `Optional<Agent> agentByApiKey(int apiKeyId)` (retired included); `List<Agent> agents()` (by name; each with its queues, one query)
  - `void updateAgent(long id, String name, String vendor, String runtime, List<String> queues)` — one transaction: update row, replace queue rows.
  - `void rebindAgentKey(long id, int apiKeyId)`

- [ ] **Step 1: Write the failing tests** (helper `int newKey()` inserts an `api_keys` row with a random `key_hash`, scope `work`):
  - `queueRoundTrip()`, `duplicateQueueRejected()` (SQLState `23505`), `retiredQueueStillListedWithRetiredAt()`.
  - `registerAgentWithQueues()` → `agents()` shows `queues()==["implementer","reviewer"]`.
  - `agentByApiKeyFindsRetiredAgentToo()`; `agentByNameIgnoresCase()`.
  - `updateAgentReplacesQueues()`; `rebindAgentKeyMovesBinding()` (old key id no longer resolves).
  - `sameKeyCannotBindTwoAgents()` (`23505`).
  - Rollback: `registerRollsBackWhenQueueRowFails()` (unknown queue in the list → no agent row); `updateRollsBackWhenQueueRowFails()` (name and queues unchanged).
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest='JdbcWorkRegistry*'` — expect FAIL.
- [ ] **Step 3: Implement** in `JdbcWorkRegistry(Jdbc)`. Names stored lowercase.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(work): queue and agent registry store`.

---

### Task 5: Store — claims, release, expiry

**Files:**
- Create: `JdbcWorkClaims.java` (package-private; `JdbcWorkStore` delegates)
- Modify: `WorkStore.java`, `JdbcWorkStore.java`, `JdbcWorkCatalog.java` (`putMember`/`removeMember` for agents)
- Test: `JdbcWorkClaimsPostgresTest.java`, `JdbcWorkClaimsConcurrencyPostgresTest.java`, `JdbcWorkClaimsRollbackTest.java`

**Interfaces:**
- Consumes: Task 3 `appendEvent`, Task 4 registry.
- Produces (`WorkStore`):
  - `Optional<Item> claim(Item item, Actor agent, Duration expiry)` — empty when not claimable; writes `claimed` (from old status, to new status, note = expiry ISO-8601) or, for the holder, `claim_renewed` (to = new expiry).
  - `Optional<Item> release(Item item, Actor by, String note)` — releases the claim held by `item.claimedByAgent()`; empty when that claim is gone; `released` event, from/to status, note.
  - `List<Item> sweepExpiredClaims(int max)` — `claim_expired` events attributed to the expired agent (actor = its name, kind agent, its id).
  - `List<Item> claimable(Long agentId, Set<Long> projectIds, String queue, int limit)` — `agentId` null: any queue. Order `priority ASC, due_on ASC NULLS LAST, id ASC`.
  - `List<Item> claimsHeldBy(long agentId, Set<Long> projectIds)`; `long activeClaimCount()`
  - `void retireAgent(long agentId, Instant at, Actor by)` — one transaction: `retired_at`, release every claim (`released`, note `agent retired`).
  - `putMember` (role `viewer` for an agent) and `removeMember` of an `agent` row release that agent's claims in the project in the same transaction (note `access removed`).

- [ ] **Step 1: Write the failing tests** (fixture: project PLAT, queue `implementer`, agents A and B serving it, both `member`; a `ready` task in `implementer`):
  - `claimTakesReadyItem()` → status `in_progress`, `claimedByAgent()==A`, `claimExpiresAt()` ≈ now + expiry, version +1, one `claimed` event with `agentId()==A`, from `"ready"` to `"in_progress"`.
  - `claimTakesReviewItemWithoutMoving()` → status stays `review`, version unchanged.
  - `holderClaimRenews()` → `claimedAt` unchanged, expiry later, version unchanged, one `claim_renewed` event.
  - `notClaimable()` parameterised → empty: status `draft`/`in_progress`/`done`; held by B; queue not served by A; assigned to a person; no assignee; blocked by an open item.
  - `releaseReturnsToQueue()` → `ready`, claim columns null, `queue()` unchanged, `released` event with note.
  - `releaseOfReviewClaimKeepsReview()`.
  - `sweepExpiresOnlyPastClaims()` (two claims, one with `claim_expires_at` set in the past by SQL) → one item back to `ready`, one `claim_expired` event attributed to its agent; the other untouched.
  - `sweepThenClaimGivesExpiredThenClaimed()` (Review Focus 1, the renewal-loses-to-sweep path) — after a sweep, A's `claim` is a fresh take: events in order `claimed`, `claim_expired`, `claimed`.
  - `expiryLeavesOtherColumnsAlone()` — title, body, priority, links, comments identical before and after.
  - `claimableListsOnlyServedQueuesInGivenProjects()`; `claimableExcludesBlocked()`.
  - `removingAgentMemberReleasesItsClaims()`, `demotingAgentToViewerReleasesItsClaims()`, `retireAgentReleasesAllClaims()` (Review Focus 2).
  - Concurrency (`JdbcWorkClaimsConcurrencyPostgresTest`, Review Focus 1): `concurrentClaimsYieldExactlyOneWinner()` — 8 agents serving the queue, 8 threads released by one `CountDownLatch`, each claims the item once → exactly 1 present result, item `claimedByAgent` equals the winner, exactly one `claimed` event. `concurrentSweepsWriteOneEventPerClaim()` — 2 threads sweep 20 expired claims → 20 `claim_expired` events total.
  - Rollback: `claimRollsBackWhenEventInsertFails()` (status, claim, version unchanged); `releaseRollsBackWhenEventInsertFails()`; `sweepRollsBackWhenEventInsertFails()`; `retireRollsBackWhenEventInsertFails()` (agent not retired, claims kept).
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest='JdbcWorkClaims*'` — expect FAIL.
- [ ] **Step 3: Implement.** The claim is this single statement, inside `inTransaction` with the event insert (agent id bound at every `:agent`, positional in JDBC):
  ```sql
  UPDATE work_item AS i
     SET claimed_by_agent = :agent,
         claimed_at       = CASE WHEN i.claimed_by_agent = :agent THEN i.claimed_at ELSE now() END,
         claim_expires_at = now() + make_interval(secs => :expirySeconds),
         status           = CASE WHEN i.status = 'ready' THEN 'in_progress' ELSE i.status END,
         version          = i.version + CASE WHEN i.status = 'ready' THEN 1 ELSE 0 END,
         updated_at       = now()
   WHERE i.id = :itemId
     AND EXISTS (SELECT 1 FROM work_agent_queue aq
                  WHERE aq.agent_id = :agent AND aq.queue_key = i.assignee_queue)
     AND (   (i.claimed_by_agent IS NULL AND i.status IN ('ready', 'review'))
          OR  i.claimed_by_agent = :agent )
     AND NOT EXISTS (
           SELECT 1 FROM work_link l
             JOIN work_item b ON b.project_id = i.project_id AND b.status NOT IN ('done', 'cancelled')
             JOIN work_project bp ON bp.id = b.project_id
            WHERE l.item_id = i.id AND l.kind = 'blocked_by'
              AND UPPER(TRIM(l.target)) = UPPER(bp.key || '-' || b.seq))
  RETURNING i.id, (i.claimed_at = now()) AS taken
  ```
  `taken` false means a renewal. SET expressions read the old row; under READ COMMITTED a claim that waited on a sweep's row lock re-checks the WHERE against the swept row and becomes a fresh take. The sweep:
  ```sql
  WITH expired AS (
      SELECT w.id, w.claimed_by_agent AS agent_id, a.name AS agent_name, w.status AS from_status
        FROM work_item w JOIN work_agent a ON a.id = w.claimed_by_agent
       WHERE w.claimed_by_agent IS NOT NULL AND w.claim_expires_at <= now()
       ORDER BY w.id LIMIT :max
         FOR UPDATE OF w SKIP LOCKED)
  UPDATE work_item i
     SET claimed_by_agent = NULL, claimed_at = NULL, claim_expires_at = NULL,
         status  = CASE WHEN i.status = 'in_progress' THEN 'ready' ELSE i.status END,
         version = i.version + CASE WHEN i.status = 'in_progress' THEN 1 ELSE 0 END,
         updated_at = now()
    FROM expired e WHERE i.id = e.id
  RETURNING i.id, i.project_id, e.agent_id, e.agent_name, e.from_status, i.status
  ```
  followed, in the same transaction, by one `claim_expired` event per returned row. Release is the same `UPDATE` keyed `WHERE id = ? AND claimed_by_agent = ?`.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(work): atomic claims, release and expiry sweep`.

---

### Task 6: Service — registry, identity and agent members

**Files:**
- Create: `WorkRegistry.java` (package-private collaborator)
- Modify: `WorkService.java` (constructor + delegating methods), `WorkScopes.java`, `WorkInputs.java`
- Test: `WorkRegistryServiceTest.java` (Mockito over `WorkStore`, fake `AgentKeys`), `WorkServiceVisibilityPostgresTest.java` (extend), `WorkAgentIdentityPostgresTest.java`

**Interfaces:**
- Consumes: Tasks 2, 4, 5.
- Produces (`WorkService`):
  - Constructor `WorkService(WorkStore store, WorkRenderer renderer, WorkDirectory directory, AgentKeys keys, Clock clock, WorkSettings settings, WorkListener listener)`; the existing 4- and 5-arg constructors delegate (`AgentKeys.NONE`, `WorkSettings.DEFAULTS`, a listener wrapping the `Consumer<Action>`).
  - `List<Queue> listQueues(Actor a)` — Admin or anyone with a visible project; else `[]`.
  - `Queue createQueue(Actor a, String key, String name, String description)`; `void updateQueue(Actor a, String key, String name, String description)`; `void retireQueue(Actor a, String key)` — `MANAGE_INSTANCE`.
  - `List<Agent> listAgents(Actor a)` — Admin: all, with `apiKeyId`; anyone with a visible project: all with `apiKeyId == null`; else `[]`.
  - `record AgentWithToken(Agent agent, String token)` (nested in `WorkService`).
  - `AgentWithToken registerAgent(Actor a, AgentDraft d)` — authorise, validate (name pattern, unique, queues exist and not retired, owner resolves through `WorkPeople.login`), then `keys.mint(ownerLogin, "work-agent:" + name)`, then `store.registerAgent`; if the store throws, `keys.revoke(minted.keyId(), a.login())` and rethrow.
  - `void updateAgent(Actor a, long agentId, AgentDraft d)` (owner ignored); `AgentWithToken rotateAgentKey(Actor a, long agentId)` (mint, rebind, revoke old; revoke the new key if rebind fails); `void retireAgent(Actor a, long agentId)` (store retire, then revoke; a revoke failure is a `LOG.warn`).
  - `Actor actorForApiKey(int apiKeyId, String ownerLogin)` — active bound agent → `Actor.agent(id, name)`; retired bound agent → `null`; unbound → `Actor.human(ownerLogin, directory.groupsOf(ownerLogin), false)`.
  - `putMember`/`removeMember` with `principalKind = "agent"`: principal is the agent's name; resolves through `agentByName` (unknown or retired → `WorkValidationException("principal", "No such agent.")`), stored as the id text (R1); role checked by `requireAgentRole`.

- [ ] **Step 1: Write the failing tests.**
  - `WorkRegistryServiceTest`: `nonAdminCannotManageQueuesOrAgents()` (each write → `WorkDeniedException`); `agentActorCannotManage()` (an `Actor.agent` → denied, R5); `registerMintsThenStores()` (fake keys records `mint("lena", "work-agent:codex@docker2")`); `registerRevokesKeyWhenStoreFails()` (store throws `SQLException` → fake keys saw `revoke(thatId, "admin")`, exception rethrown); `rotateRevokesOldAfterRebind()`; `rotateRevokesNewWhenRebindFails()`; `retireRevokesKey()`; `retiredQueueRefusedForAgent()`; `unknownOwnerRejected()`; `nonAdminSeesAgentsWithoutKeyIds()`.
  - `WorkAgentIdentityPostgresTest`: `boundKeyIsTheAgent()`; `retiredAgentKeyIsNoActor()` (Review Focus 2); `unboundKeyIsTheOwnerNeverAdmin()`; `agentOwnedByLeadSeesOnlyAdmittedProjects()` (Review Focus 3: lena leads PLAT and OPS, agent keyed to lena admitted to PLAT only → `listProjects` = [PLAT]; `item(agent, "OPS-1")` → `WorkNotFoundException` with the same message as `item(agent, "NOPE-1")`); `agentNamedLikeALoginGetsNoneOfThePersonsItems()` (Review Focus 5: agent `jake`, user `jake` member of PLAT, agent not admitted → `listProjects` empty, `waitingOn` empty); `agentMemberAsLeadRejected()`; `agentMemberByNameStoresId()` (member row principal = id text, `members` shows kind `agent`).
  - `WorkServiceVisibilityPostgresTest`: add `listQueues`, `listAgents` to `READS`; `createQueue`, `updateQueue`, `retireQueue`, `registerAgent`, `updateAgent`, `rotateAgentKey`, `retireAgent` to `WRITES`; new set `SYSTEM = Set.of("actorForApiKey", "sweepExpiredClaims")` (Task 7 adds the second method) included in the classification, with `systemMethodsTakeNoActor()`. Add `everyInstanceWriteRequiresAdmin()` (mallory → `WorkDeniedException` for each instance write). Strangers become `{mallory, rogueAgent}` where `rogueAgent = Actor.agent(<registered, unadmitted id>, "rogue@x")`; the fixture adds queue `implementer` and agent `codex@docker2` so the new reads return data to lena.
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest='WorkRegistryServiceTest,WorkAgentIdentityPostgresTest,WorkServiceVisibilityPostgresTest'` — expect FAIL.
- [ ] **Step 3: Implement** in `WorkRegistry`; `WorkService` methods are one-line delegations.
- [ ] **Step 4: Run** — expect PASS; then `mvn test -pl wikantik-work` — expect PASS.
- [ ] **Step 5: Commit** `feat(work): queue and agent registry service, key-bound identity, agent members`.

---

### Task 7: Service — claim, release, handoff, claimable, waiting on you

**Files:**
- Create: `WorkClaims.java` (package-private collaborator)
- Modify: `WorkService.java`, `ItemPatchApplier.java` (queue key), `ItemViews.java` (`wikiCluster`), `WorkScopes.java`
- Test: `WorkClaimsServiceTest.java` (Mockito), `WorkHandoffDirectionsPostgresTest.java`, `WorkServiceVisibilityPostgresTest.java` (extend)

**Interfaces:**
- Consumes: Tasks 3, 5, 6.
- Produces (`WorkService`):
  - `ItemView` appends `String wikiCluster` (the item's product's `wiki_cluster`; null when no product).
  - `record ClaimResult(ItemView view, boolean renewed)`; `record Handoff(int expectedVersion, WorkStatus status, String assignee, String queue, String note)` (nested).
  - `List<ItemSummary> claimable(Actor a, String projectKey, String queue, int limit)` — `limit` default 20, clamped to 50; an agent: its queues, projects where it is `member`; a person: any queue, visible projects. A named project goes through `scope()` (404 when invisible); a named queue the agent does not serve → `[]`.
  - `List<Item> myClaims(Actor a)` — the agent's claims in projects it can still see; `[]` for a person.
  - `ClaimResult claim(Actor a, String itemKey)` — `itemScope` → `require(CLAIM)` → `requireWritable` → `store.claim(item, a, settings.claimExpiry())`; empty → `WorkConflictException(reason NOT_CLAIMABLE)` whose message says why: claimed by `<agent name>` until `<ISO time>`, not `ready` or `review`, blocked, or not in a queue you serve. Listener: `CLAIMED`, `RENEWED` or `REFUSED`.
  - `ItemView release(Actor a, String itemKey, String note)` — `require(MOVE)` (the claim-holder rule stops other agents); no claim → `WorkConflictException(reason NOT_CLAIMED)`; listener `RELEASED`.
  - `ItemView handoff(Actor a, String itemKey, Handoff h)` — `require(MOVE)` and `require(EDIT_ITEM)`; exactly one of `assignee`/`queue`; person must be a project member (display name; a login only from an Admin); queue must exist and not be retired; note non-blank, ≤ 2000 chars; status allowed for the level; target differs from the current assignment; then `store.handoff`.
  - `int sweepExpiredClaims()` — `store.sweepExpiredClaims(500)`, listener `EXPIRED` per item.
  - `List<WaitingItem> waitingOn(Actor a)` (return type changes) — `[]` for an agent; for a person, assigned items carry `lastHandoffs`.
  - `WorkScopes.requireAuthor` compares ids for an agent (`comment.authorAgentId()` equals `actor.agentId()`) and, for a person, requires `authorAgentId() == null` and `author()` equal to the login.
  - `createItem` accepts `ItemDraft.queue`; `updateItem` accepts change key `queue` (setting one of `assignee`/`queue` clears the other; both non-null in one patch → `WorkValidationException("queue", …)`).

- [ ] **Step 1: Write the failing tests.**
  - `WorkClaimsServiceTest`: `humanClaimDenied()`; `viewerAgentClaimDenied()`; `refusedClaimNamesHolder()` (message contains "codex@docker2" and no login); `refusedClaimCountsRefused()` (listener saw `REFUSED`); `renewalReported()` (`renewed()` true, listener `RENEWED`); `otherAgentCannotReleaseOrHandOff()` (→ `WorkDeniedException`); `personMayReleaseAnyClaim()` (Review Focus 4); `handoffNeedsExactlyOneTarget()`; `handoffWithoutNoteRejected()`; `handoffToRetiredQueueRejected()`; `handoffToNonMemberRejected()`; `handoffToSameTargetRejected()`; `bothAssigneeAndQueueInPatchRejected()`; `claimableLimitClampedTo50()`; `claimOnArchivedProjectIs409Archived()`.
  - `WorkHandoffDirectionsPostgresTest` (real store, fake renderer/directory/keys) — one test per direction, each asserting the `handoff` event, its note, and where the item now shows:
    - `humanToAgent()`: lena assigns PLAT-1 to queue `implementer`, status `ready` → in A's `claimable`; A claims → `in_progress`, held by A.
    - `agentToAgent()`: A hands off to queue `reviewer`, status `review` → claim cleared; B (serving `reviewer`) claims; status stays `review`.
    - `agentToHuman()`: B hands off to person "Lena Lead", status `review` → `waitingOn(lena)` contains PLAT-1 with `reason()=="assigned"` and `handoff().note()=="please approve"`, `handoff().agentId()==B`.
    - `humanToHuman()`: lena hands off to "Mo Member" → in Mo's `waitingOn`.
    - `staleAgentWriteAfterHumanMoveIsRefused()` (Review Focus 4): A holds PLAT-2; lena moves it to `refining`; A's `updateItem` with its old `expectedVersion` → `WorkConflictException` reason `stale`; A's `release` → reason `not_claimed`.
    - `humanCannotEditAgentCommentOfSameName()` (Review Focus 5): agent `jake` comments; human `jake` `editComment` → `WorkDeniedException`.
  - `WorkServiceVisibilityPostgresTest`: `claimable`, `myClaims` to `READS`; `claim`, `release`, `handoff` to `WRITES` and to the project-scoped write list (mallory and rogueAgent → `WorkNotFoundException`); `sweepExpiredClaims` in `SYSTEM`. Proving actors become `{lena, codex}` (codex admitted as `member` and holding a claim) so `myClaims` proves itself.
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest='WorkClaimsServiceTest,WorkHandoffDirectionsPostgresTest,WorkServiceVisibilityPostgresTest'` — expect FAIL.
- [ ] **Step 3: Implement** in `WorkClaims`; `WorkService` delegates.
- [ ] **Step 4: Run** — expect PASS; then `mvn test -pl wikantik-work` — expect PASS.
- [ ] **Step 5: Commit** `feat(work): claim, release, handoff and the waiting-on-you lane in WorkService`.

---

### Task 8: Runtime — `work` scope, key-bound actors, agent keys, sweep, metrics, config

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/apikeys/ApiKeyService.java` (`Scope.WORK("work", 0)`), `wikantik-rest/src/main/java/com/wikantik/rest/SelfApiKeysResource.java` (`SELF_SERVICE_SCOPES` adds `WORK`)
- Create: `wikantik-main/src/main/java/com/wikantik/work/runtime/WikiAgentKeys.java`, `WorkClaimSweeper.java`
- Modify: `WikiWorkActors.java`, `WorkWiring.java`, `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (register/close the sweeper), `wikantik-work/src/main/java/com/wikantik/work/WorkMetrics.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (`[Work]`)
- Test: `ApiKeyScopeTest` (extend), `SelfApiKeysResourceTest` (extend), `WikiAgentKeysTest.java`, `WikiWorkActorsTest` (extend), `WorkClaimSweeperTest.java`, `WorkWiringTest` (extend), `WorkMetricsTest` (extend)

**Interfaces:**
- Consumes: `WorkService.actorForApiKey`, `sweepExpiredClaims`, the 7-arg constructor (Task 6).
- Produces:
  - `ApiKeyService.Scope.WORK` (wire `work`, matches only itself; `ALL` matches it).
  - `WikiAgentKeys(ApiKeyService) implements AgentKeys` — mints `Scope.WORK` keys; `revoke` delegates.
  - `WikiWorkActors.actorForKey(WorkService svc, ApiKeyService.Record record) : Actor` — null when the record's scope does not match `WORK`, else `svc.actorForApiKey(record.id(), record.principalLogin())`.
  - `WorkWiring.PROP_CLAIM_EXPIRY = "wikantik.work.claim.expiryMinutes"`; `build(ds, props, engine)` reads it (1–10080, else 240 with a warn), passes `WikiAgentKeys` (or `AgentKeys.NONE` without an `ApiKeyService`) and `WorkMetrics` as the listener.
  - `WorkClaimSweeper implements AutoCloseable`: `static WorkClaimSweeper start(WorkService svc, Duration interval)`; `INTERVAL = Duration.ofSeconds(60)`; daemon thread `work-claim-sweeper`; a failed pass is a `LOG.warn`; registered with `engine.setManager( WorkClaimSweeper.class, … )` after `setWorkService`, closed by `closeQuietly( WorkClaimSweeper.class )` in `WikiEngine.shutdown()`.
  - `WorkMetrics implements WorkListener`: counter `wikantik_work_claims_total{outcome}`; gauge `wikantik_work_claims_active` (`store.activeClaimCount()`).

- [ ] **Step 1: Write the failing tests.**
  - `ApiKeyScopeTest.workMatchesOnlyWorkAndAll()`: `WORK.matches(WORK)` true, `WORK.matches(MCP_READ)` false, `MCP_ADMIN.matches(WORK)` false, `ALL.matches(WORK)` true; `fromWire("work")==WORK`.
  - `SelfApiKeysResourceTest.nonAdminMayMintWorkKey()` (201, scope `work`).
  - `WikiAgentKeysTest.mintsWorkScopedKeyForOwner()`.
  - `WikiWorkActorsTest`: `mcpReadKeyIsNoWorkActor()`, `workKeyDelegatesToService()`.
  - `WorkClaimSweeperTest`: `sweepsOnSchedule()` (interval 50 ms, service mock called ≥ 2 times within 1 s), `failureIsLoggedAndSweepingContinues()`, `closeStopsTheThread()`.
  - `WorkWiringTest`: `claimExpiryReadFromProperty()` (`30` → 30 min), `outOfRangeExpiryFallsBackTo240()` (`0`, `99999`).
  - `WorkMetricsTest`: `claimCounterHasOnlyOutcomeTag()` (tag keys exactly `outcome`, values the `ClaimOutcome` wires); `activeClaimsGaugeHasNoTags()`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest='ApiKeyScopeTest,WikiAgentKeysTest,WikiWorkActorsTest,WorkClaimSweeperTest,WorkWiringTest'`, `mvn test -pl wikantik-work -Dtest=WorkMetricsTest`, `mvn test -pl wikantik-rest -Dtest=SelfApiKeysResourceTest` — expect FAIL.
- [ ] **Step 3: Implement.** Declare the key first in `wikantik.properties` `[Work]`: description ("minutes an agent's claim lasts before the item returns to its queue; renewing the claim restarts it"), `# Type: int`, `wikantik.work.claim.expiryMinutes = 240`. Then `bin/config-reference.sh --write`.
- [ ] **Step 4: Run** the Step 2 commands plus `mvn test -pl wikantik-war -Dtest=ConfigSurfaceDriftTest`, `mvn test -pl wikantik-extract-cli -Dtest=ConfigReferenceRegressionTest`, `mvn test -pl wikantik-main -Dtest='*ArchTest'` — expect PASS (if an ArchUnit freeze store changed, `git restore` it and fix the violation).
- [ ] **Step 5: Commit** `feat(work): work API-key scope, key-bound actors, agent keys, claim sweep and metrics`.

---

### Task 9: REST — bearer keys, queues, agents, claims, handoff

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/WorkApiKeyFilter.java`, `WorkQueueResource.java`, `WorkAgentResource.java`
- Modify: `WorkResourceBase.java` (actor from key; name maps), `WorkItemResource.java`, `WorkProjectResource.java` (agent members), `WorkMeResource.java`, `WorkJson.java`, `wikantik-war/src/main/webapp/WEB-INF/web.xml`
- Test: `WorkApiKeyFilterTest.java`, `WorkQueueResourceTest.java`, `WorkAgentResourceTest.java`, `WorkItemResourceTest` / `WorkProjectResourceTest` / `WorkMeResourceTest` / `WorkJsonTest` (extend)

**Interfaces:**
- Consumes: Tasks 6–8 (`WorkService` methods, `WikiWorkActors.actorForKey`, `ApiKeyServiceHolder.get(Properties)`).
- Produces:

  | Method and path | Body / query | Returns |
  |---|---|---|
  | `GET /api/work/queues` | | `[Queue]` |
  | `POST /api/work/queues` | `key, name, description` | `Queue` (201) |
  | `PATCH /api/work/queues/{key}` | `name, description` | `Queue` |
  | `POST /api/work/queues/{key}/retire` | | 204 |
  | `GET /api/work/agents` | | `[Agent]` (`apiKeyId` for Admins only) |
  | `POST /api/work/agents` | `name, vendor, runtime, queues, owner` | `{agent, token}` (201, `Cache-Control: no-store`) |
  | `PATCH /api/work/agents/{id}` | `name, vendor, runtime, queues` | `Agent` |
  | `POST /api/work/agents/{id}/rotate-key` | | `{agent, token}` (`no-store`) |
  | `POST /api/work/agents/{id}/retire` | | 204 |
  | `GET /api/work/claimable` | `project, queue, limit` (default 20, max 50) | `[ItemSummary]` |
  | `GET /api/work/projects/{key}/board`, `GET /api/work/items` | existing params plus `queue` | as phase 1 |
  | `POST /api/work/items`, `PATCH /api/work/items/{key}` | `ItemDraft.queue`; change key `queue` | as phase 1 |
  | `POST /api/work/items/{key}/claim` | | `{item: ItemView, renewed}` |
  | `POST /api/work/items/{key}/release` | `note` | `ItemView` |
  | `POST /api/work/items/{key}/handoff` | `expectedVersion, status, assignee` or `queue`, `note` | `ItemView` |
  | `PUT|DELETE /api/work/projects/{key}/members` | `principalKind: agent`, `principal`: agent name | 204 |
  | `GET /api/work/me` | | `waitingOn[]` items gain `waiting: {reason, by, byName, note, at}` |

  - 409 bodies carry `reason` (`not_claimable`, `not_claimed`, `stale` + `current`, `archived`).
  - `WorkApiKeyFilter` on `/api/work/*`: no `Bearer` header → pass through untouched; work disabled → pass through (the resource 404s); unknown or revoked key → 401 `{"error":"Invalid API key"}`; scope not matching `WORK` → 403 `{"error":"Key not authorized for work"}`; else wraps in `ApiKeyPrincipalRequest` with `ATTR_API_KEY_RECORD`. Denials go to the `SecurityLog` logger.
  - `WorkResourceBase.actor(request)`: a request carrying `ATTR_API_KEY_RECORD` → `WikiWorkActors.actorForKey`; otherwise the session path; null → 401.
  - `WorkJson` names, for every viewer: an event with `actorKind` agent → `actorName` = agent name; `authorAgentId` → `authorName`; `reporterAgentId` → `reporterName`; `claimedByAgent` → `claimedByName`; `queue` → `queueName`; event values `queue:<key>` → `…Name` = queue name; member rows of kind `agent` → `displayName` and `principal` = agent name, `self` false; a comment by agent X is `mine` only for actor X.
  - `WorkQueueResource`, `WorkAgentResource` register their paths; `WorkItemResource` adds `/api/work/claimable`.

- [ ] **Step 1: Write the failing tests.**
  - `WorkApiKeyFilterTest`: `noBearerPassesThroughUnwrapped()`, `revokedKeyIs401()`, `mcpReadKeyIs403()`, `workKeyWrapsRequestWithRecord()`, `allKeyAccepted()`, `disabledFeaturePassesThrough()`.
  - `WorkAgentResourceTest`: `registerReturnsTokenOnceWithNoStore()`; `listHidesKeyIdFromNonAdmin()`; `nonAdminRegisterIs403()`; `rotateReturnsNewToken()`; `unknownAgentIs404()`.
  - `WorkQueueResourceTest`: happy path per row; `nonAdminCreateIs403()`; `nonMemberListIsEmpty()`.
  - `WorkItemResourceTest`: `claimReturnsItemAndRenewedFlag()`; `notClaimableIs409WithReason()`; `releaseWithoutClaimIs409NotClaimed()`; `handoffNeedsNote400()`; `claimableLimitClampedTo50()`; `bearerAgentActorUsed()` (request attribute set → service saw `Actor.kind()==AGENT`).
  - `WorkJsonTest`: `agentEventShowsAgentName()`; `queueValueShowsQueueName()`; `agentMemberShowsAgentName()`; `noLoginForNonAdminInAnyAgentField()`.
  - `WorkMeResourceTest.waitingOnCarriesHandoffByNameAndNote()`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-rest -Dtest='Work*Test'` — expect FAIL.
- [ ] **Step 3: Implement**; in `web.xml` add the `WorkApiKeyFilter` filter + mapping `/api/work/*` immediately after `BasicAuthFilter`'s mapping, servlets `WorkQueueResource` (`/api/work/queues`, `/api/work/queues/*`), `WorkAgentResource` (`/api/work/agents`, `/api/work/agents/*`), and `/api/work/claimable` on `WorkItemResource`.
- [ ] **Step 4: Run** the Step 2 command and `mvn test -pl wikantik-rest -Dtest=WorkAccessArchTest` — expect PASS.
- [ ] **Step 5: Commit** `feat(work): REST for queues, agents, claims and handoff; work-scoped bearer keys`.

---

### Task 10: `wikantik-work-mcp` — module, `/work-mcp` endpoint, read tools

**Files:**
- Create: `wikantik-work-mcp/pom.xml` (dependencies as `wikantik-knowledge/pom.xml` minus ontology, plus `wikantik-work`, `archunit-junit5` test, `mockito-core` test), `wikantik-work-mcp/CLAUDE.md`
- Create in `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/`: `WorkMcpInitializer.java`, `WorkMcpActors.java`, `WorkMcpTool.java`, `WorkMcpJson.java`, `GetWorkContextTool.java`, `ListClaimableTool.java`, `GetItemTool.java`; resource `wikantik-work-mcp/src/main/resources/wikantik-work-mcp-instructions.txt`
- Modify: `wikantik-mcp-core/src/main/java/com/wikantik/mcp/McpEndpointBootstrapper.java`, `McpAccessFilter.java`; root `pom.xml` (`<module>wikantik-work-mcp</module>` after `wikantik-work`), `wikantik-war/pom.xml`, `wikantik-coverage-report/pom.xml` (mirror the `wikantik-knowledge` entries); `web.xml` (listener after `KnowledgeMcpInitializer`; `/work-mcp`, `/work-mcp/*` on `RequestMetricsFilter`); `wikantik-http/src/main/java/com/wikantik/http/filter/CsrfProtectionFilter.java` (`isMcpEndpoint` adds `/work-mcp`)
- Test: `McpAccessFilterTest`, `McpEndpointBootstrapperTest` (extend), `CsrfProtectionFilterTest` (extend); in the new module `WorkMcpInitializerTest.java`, `WorkMcpActorsTest.java`, `WorkMcpInstructionsDriftTest.java`, `WorkMcpAccessArchTest.java`, `GetWorkContextToolTest.java`, `ListClaimableToolTest.java`, `GetItemToolTest.java`

**Interfaces:**
- Consumes: `WikiEngine.workService()`, `WikiWorkActors.actorForKey`, `WikiWorkDirectory`, `ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD`, Tasks 6–7 service methods.
- Produces:
  - `McpEndpointBootstrapper.Builder.keyOnly(boolean)` — when true, the filter gets no CIDR allowlist and `allowUnrestricted=false` whatever `mcp.access.*` says; `registerTransport(ServletContext, McpTransportContextExtractor<HttpServletRequest>)` overload passing `contextExtractor`.
  - `McpAccessFilter(String allowedCidrs, boolean allowUnrestricted, SlidingWindowRateLimiter, ApiKeyService, ApiKeyService.Scope)` constructor.
  - `WorkMcpActors.KEY_RECORD = "wikantik.work.apiKeyRecord"`; `static McpTransportContext extract(HttpServletRequest)` (empty context when no record); `static Actor actorFor(McpTransportContext ctx, WorkService svc)` (null when absent).
  - `abstract class WorkMcpTool { String name(); McpSchema.Tool definition(); CallToolResult call(Actor actor, Map<String,Object> args); }` — `call` maps `WorkNotFoundException` → error text exactly `Not found.`; `WorkDeniedException` → its reason; `WorkValidationException` → `field: message`; `WorkConflictException` → `reason: message` (+ `currentVersion` when stale); null actor → `This endpoint needs a work API key.`
  - `WorkMcpJson(WorkDirectory directory, Map<Long,String> agents, Map<String,String> queues)` — renders people by display name only, agents and queues by name; never a login, never a key id.
  - `WorkMcpInitializer implements ServletContextListener` — `workService()` null → logs and registers nothing (so `/work-mcp` is a container 404); else `keyOnly(true)`, scope `WORK`, path `/work-mcp`, filter `WorkMcpAccessFilter`, servlet `WorkMcpTransportServlet`, `loadOnStartup(4)`; server info `("wikantik-work", "Wikantik Work", Release.getVersionString())`; instructions from the classpath resource; each tool registered as `(exchange, req) -> tool.call(WorkMcpActors.actorFor(exchange.transportContext(), svc), req.arguments())`; `static List<WorkMcpTool> assembleTools(WorkService svc, WorkDirectory dir, Duration claimExpiry)`.
  - Read tools (read-only annotations):

    | Tool | Inputs | Output |
    |---|---|---|
    | `get_work_context` | — | `{agent: {name, vendor, runtime, queues} or null, person: name or null, projects: [{key, name, role}], claims: [{key, title, status, expiresAt}], claimExpiryMinutes}` |
    | `list_claimable` | `project?`, `queue?`, `limit?` (20, max 50) | `{items: [{key, title, level, kind, status, priority, queue, productKey, parentKey, dueOn}]}` |
    | `get_item` | `key` | `{key, title, body (Markdown), level, kind, status, priority, version, dueOn, assignee (name), queue, claim: {agent, expiresAt} or null, parentKey, productKey, wikiCluster, blocked, links: [{kind, target}], comments: [{author, body, at}], events: [last 20: {type, actor, from, to, note, at}]}` |

- [ ] **Step 1: Write the failing tests.**
  - `McpAccessFilterTest.keyOnlyRejectsKeylessRequestEvenWhenUnrestrictedConfigured()` (403, no `Allowed`); `keyOnlyRejectsMcpReadKeyForWorkScope()`.
  - `McpEndpointBootstrapperTest.contextExtractorReachesTransport()`.
  - `CsrfProtectionFilterTest.workMcpIsAnMcpEndpoint()`.
  - `WorkMcpInitializerTest`: `disabledFeatureRegistersNothing()` (mock `ServletContext`: no `addFilter`, no `addServlet`); `enabledRegistersKeyOnlyFilterAndServletAtWorkMcp()`; `assembledToolNames()` (equals `{get_work_context, list_claimable, get_item}` here; Task 11 extends it to the nine).
  - `WorkMcpActorsTest`: `noRecordIsNoActor()`, `boundKeyIsAgent()`.
  - `WorkMcpInstructionsDriftTest`: `instructionsNameEveryTool()` and `instructionsNameNoUnregisteredTool()` — the tool-like tokens (snake_case beginning `get_`, `list_`, `claim_`, `update_`, `comment_`, `handoff_`, `release_`, `create_`) in the resource equal `assembleTools(…)` names exactly.
  - `WorkMcpAccessArchTest.noWorkMcpClassTouchesTheStore()` — no class in `com.wikantik.work.mcp..` depends on `WorkStore` or `JdbcWorkStore`.
  - `GetItemToolTest`: `hiddenAndUnknownGiveIdenticalError()` (visible-to-nobody item and nonexistent key → identical `CallToolResult` text `Not found.`); `bodyIsMarkdownSource()`; `includesWikiCluster()`; `noLoginInOutput()` (user `jdoe`/"Jane Doe" comments → output contains "Jane Doe", not "jdoe").
  - `GetWorkContextToolTest`: `agentSeesOnlyAdmittedProjects()` (Review Focus 3, on MCP); `unboundKeyShowsPersonAndNoClaims()`.
  - `ListClaimableToolTest`: `limitClampedTo50()`; `unknownProjectIsNotFound()`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-mcp-core -Dtest='McpAccessFilterTest,McpEndpointBootstrapperTest'`, `mvn test -pl wikantik-http -Dtest=CsrfProtectionFilterTest`, `mvn test -pl wikantik-work-mcp` — expect FAIL.
- [ ] **Step 3: Implement.** The instructions open with what the endpoint is for, then one line per tool, then the loop: `get_work_context` → `list_claimable` → `claim_item` (renew by calling it again before `expiresAt`) → `get_item` → work → `comment_on_item`/`update_item` → `handoff_item` or `release_item`; and "ask the knowledge MCP `get_briefing` with the item's `wikiCluster`".
- [ ] **Step 4: Run** the Step 2 commands — expect PASS.
- [ ] **Step 5: Commit** `feat(work): wikantik-work-mcp module and key-only /work-mcp endpoint with read tools`.

---

### Task 11: `/work-mcp` write tools

**Files:**
- Create in `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/`: `ClaimItemTool.java`, `UpdateItemTool.java`, `CommentOnItemTool.java`, `HandoffItemTool.java`, `ReleaseItemTool.java`, `CreateItemTool.java`
- Modify: `WorkMcpInitializer.assembleTools`, `wikantik-work-mcp-instructions.txt`
- Test: one `…ToolTest` per tool (Mockito over `WorkService`), `WorkMcpFlowPostgresTest.java` (real service and store)

**Interfaces:**
- Consumes: Task 10 base classes; `WorkService.claim`, `updateItem`, `addComment`, `handoff`, `release`, `createItem`.
- Produces:

  | Tool | Inputs | Output |
  |---|---|---|
  | `claim_item` | `key` | `{claim: {expiresAt, renewed}, item: <get_item shape>}` |
  | `update_item` | `key`, `expectedVersion`, any of `title, body, status, priority, dueOn`, `note?` | `<get_item shape>` |
  | `comment_on_item` | `key`, `body` | `{id, at}` |
  | `handoff_item` | `key`, `expectedVersion`, `status`, `assignee` (a person's display name) or `queue`, `note` | `<get_item shape>` |
  | `release_item` | `key`, `note?` | `{key, status}` |
  | `create_item` | `project`, `level`, `title`, `body?`, `parentKey?`, `productKey?`, `kind?`, `priority?`, `queue?` | `{key, version}` (status `draft`) |

  `update_item` rejects `assignee`, `queue`, `autonomy`, `doneMeans`, `allowDirect` with "use handoff_item" / "policy fields are not settable by agents". Every write logs `McpAudit.logWrite(tool, action, key, actor.login())`.

- [ ] **Step 1: Write the failing tests.**
  - Per tool: happy path; `hiddenAndUnknownGiveIdenticalError()`; conflict and denial text mapping (`claim_item` on a held item → text starts `not_claimable:` and names the holder; `update_item` stale → `stale:` with `currentVersion`).
  - `UpdateItemToolTest.assigneeAndPolicyFieldsRejected()`.
  - `WorkMcpFlowPostgresTest` — drives the tools only (no direct service calls after fixture setup): `fourHandoffDirectionsThroughTools()` (lena → queue `implementer`; agent A `claim_item`, `comment_on_item`, `handoff_item` to queue `reviewer`; agent B `claim_item`, `handoff_item` to "Lena Lead"; assert lena's `waitingOn` holds it with B's note; lena's handoff to "Mo Member" through the service completes the fourth); `agentCannotTouchOtherAgentsClaim()`; `releaseReturnsItemToQueue()`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-work-mcp` — expect FAIL.
- [ ] **Step 3: Implement**; add the six tools to `assembleTools` and the instructions file.
- [ ] **Step 4: Run** — expect PASS (including the drift test with all nine names).
- [ ] **Step 5: Commit** `feat(work): /work-mcp write tools — claim, update, comment, hand off, release, create`.

---

### Task 12: Integration tests — REST agents, `/work-mcp`, visibility, feature off

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkAgentApiIT.java`, `WorkMcpIT.java`; `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/WorkDisabledIT.java`
- Modify: `WorkIt.java` (helper `HttpResponse<String> bearer(String token, String method, String path, String body)`)

**Interfaces:**
- Consumes: Task 9 REST, Tasks 10–11 tools; the rest module's `it-rest-cascade.properties` (`wikantik.work.enabled = true`).
- Produces: `WorkMcpIT.EXPECTED_TOOLS = Set.of("get_work_context", "list_claimable", "get_item", "claim_item", "update_item", "comment_on_item", "handoff_item", "release_item", "create_item")`.

- [ ] **Step 1: Write the ITs.**
  - `WorkAgentApiIT`: admin creates project `AGT`, queues `implementer` and `reviewer`, registers `codex@it` (queue `implementer`) and `claude@it` (queue `reviewer`) through `POST /api/work/agents`, keeping each token; admits both as `member`.
    - `fourHandoffDirectionsOverRest()` — the four directions with bearer calls for agents and session calls for people; assert board columns and `/api/work/me` `waiting.byName` = "claude@it".
    - `agentSeesOnlyAdmittedProjects()` — project `HID` the admin owns; table-driven over every agent-reachable GET (`/api/work/projects`, `/projects/HID`, `/projects/HID/board`, `/items?project=HID`, `/items/HID-1`, `/items/HID-1/events`, `/activity?project=HID`, `/claimable?project=HID`) comparing the real path with a ghost (`GHOST`, `GHOST-1`): same status (404 or `[]`) and byte-identical body.
    - `rotatedKeyStopsWorkingImmediately()` and `retiredAgentKeyIsRefused()` (Review Focus 2): old token → 401; retired agent's held item is back in `ready`.
    - `mcpReadKeyIs403OnWorkApi()`; `staleHandoffIs409()`.
  - `WorkMcpIT` (MCP Java client, `httpRequestCustomizer` adding `Authorization: Bearer <token>`): `listToolsIsExactlyTheNine()` (equals `EXPECTED_TOOLS`); `instructionsMentionEveryTool()`; `keylessInitializeIsRefused()` (raw POST without a key → 403, though `mcp.access.allowUnrestricted=true` in this module); `claimLoopOverTheWire()` (`get_work_context` → `list_claimable` → `claim_item` → `comment_on_item` → `handoff_item` → `release_item` by the next agent); `hiddenItemErrorEqualsGhostError()`.
  - `WorkDisabledIT` (shared selenide module, so it runs in every IT module): `Assumptions.assumeFalse` on `/api/capabilities` `work`; then `GET /api/work/me` → 404 and `POST /work-mcp` → 404.
- [ ] **Step 2: Run** `bin/agent-build.sh start itrest -- bin/run-tests.sh --module rest` and poll `bin/agent-build.sh status itrest` until it ends — expect SUCCESS with the three new classes in the report (check the `CMD:` line of the log).
- [ ] **Step 3: Commit** `test(work): REST, /work-mcp and feature-off integration tests for agents`.

---

### Task 13: Frontend — client, agents and queues admin, agent members, key scopes

**Files:**
- Modify: `wikantik-frontend/src/api/work.js`, `src/main.jsx` (route `agents` under `/work`), `components/work/WorkSettings.jsx`, `components/work/WorkHome.jsx` (Admin link "Agents & queues"), `components/admin/AdminApiKeysPage.jsx`, `components/MyApiKeys.jsx`
- Create: `components/work/WorkAgents.jsx` (+ test)
- Test: `WorkAgents.test.jsx`, `WorkSettings.test.jsx`, `AdminApiKeysPage.test.jsx`, `MyApiKeys.test.jsx`, `api/work.test.js` (extend)

**Interfaces:**
- Consumes: Task 9 endpoints.
- Produces: `work.js` exports `listQueues, createQueue, updateQueue, retireQueue, listAgents, registerAgent, updateAgent, rotateAgentKey, retireAgent, listClaimable, claimItem, releaseItem, handoffItem`; `handoffItem` rejects with `{ status: 409, reason, current }` like `patchItem`. Route `/work/agents` (Admin; others redirected to `/work`).

- [ ] **Step 1: Write the failing tests.**
  - `WorkAgents.test.jsx`: `adminOnly`; `createQueueValidatesKeyPattern` (`^[a-z][a-z0-9-]{1,29}$`); `registerShowsTokenOnceWithCopyAndWarning` (the token is gone after "Done"); `registerValidatesNamePattern`; `rotateAsksInlineConfirmationThenShowsNewToken`; `retireAsksInlineConfirmation`; `retiredRowsShownMutedAndLast`; `ownerPickerUsesDisplayNames`.
  - `WorkSettings.test.jsx`: `addAgentMemberOffersActiveAgentsOnly`; `agentRoleOptionsAreViewerAndMemberOnly`; `agentRowShowsAgentChip`.
  - `AdminApiKeysPage.test.jsx` / `MyApiKeys.test.jsx`: `offersWorkScope` (self-service list becomes `['tools', 'mcp_read', 'work']`).
- [ ] **Step 2: Run** `cd wikantik-frontend && npx vitest run src/components/work src/components/admin/AdminApiKeysPage.test.jsx src/components/MyApiKeys.test.jsx src/api` — expect FAIL.
- [ ] **Step 3: Implement**, reusing `src/components/ui/` primitives and defined CSS tokens only; no new npm dependency.
- [ ] **Step 4: Run** the vitest command and `npx eslint src/components/work src/api/work.js src/components/admin src/components/MyApiKeys.jsx` — expect PASS.
- [ ] **Step 5: Commit** `feat(work): agents and queues admin page, agent members, work key scope in key pages`.

---

### Task 14: Frontend — queues and claims on the board, item, timeline and lane

**Files:**
- Create: `components/work/AssigneePicker.jsx`, `components/work/WorkClaim.jsx` (+ tests), `components/work/WorkHandoff.test.jsx`
- Modify: `WorkCard.jsx`, `WorkBoard.jsx`, `WorkItemView.jsx`, `WorkHandoff.jsx`, `WorkItemForm.jsx`, `WorkTimeline.jsx`, `eventText.js`, `WorkHome.jsx`, `boardModel.js` (+ their tests)

**Interfaces:**
- Consumes: Task 13 client; item JSON `queue, queueName, claimedByAgent, claimedByName, claimExpiresAt`; event JSON `actorName`, `…Name`; `/api/work/me` `waiting`.
- Produces: `AssigneePicker({ members, queues, value, onChange })` → `onChange({ assignee })` or `onChange({ queue })`; members by display name, queues listed as "Queue · name", retired queues absent.

- [ ] **Step 1: Write the failing tests.**
  - `AssigneePicker.test.jsx`: `offersMembersAndActiveQueues`; `choosingQueueClearsPerson`; `agentsAreNotOffered`.
  - `WorkCard.test.jsx`: `showsQueueChip`; `showsClaimedByAndUntil` ("codex@docker2 · until 15:40"); `expiredClaimMarked` (expiry past → `claim-expired` class).
  - `WorkBoard.test.jsx`: `assigneeFilterIncludesQueues` (choosing a queue sends `queue=`); `waitingLaneShowsHandoffFromAndNote`.
  - `WorkClaim.test.jsx`: `showsHolderSinceAndExpiry`; `memberMayReleaseWithNote` (calls `releaseItem`); `viewerSeesNoRelease`; `claimableHintWhenReadyInQueue` ("Claimable by agents serving Implementer").
  - `WorkHandoff.test.jsx`: `handOffToQueueSendsQueue`; `noteRequired`; `conflictReloads` (409 `stale`).
  - `WorkItemView.test.jsx`: `assigneeFieldUsesPicker`; `rendersClaimPanel`.
  - `WorkItemForm.test.jsx`: `createWithQueueSendsQueue`.
  - `eventText.test.js` / `WorkTimeline.test.jsx`: `claimedText` ("claimed by codex@docker2 · until …"), `claimRenewedText`, `releasedTextWithNote`, `claimExpiredText` ("claim by codex@docker2 expired"), `handoffToQueueText` ("in progress → review, handed to Queue · Reviewer"), `agentActorShowsAgentName` (not "unknown contributor").
  - `WorkHome.test.jsx`: `waitingShowsWhoHandedOff`.
- [ ] **Step 2: Run** `npx vitest run src/components/work` — expect FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** vitest and `npx eslint src/components/work` — expect PASS.
- [ ] **Step 5: Commit** `feat(work): queues, claims and agent names across board, item, timeline and lane`.

---

### Task 15: Browser IT, documentation and the gate

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkBoardIT.java`
- Modify: root `CLAUDE.md` (module list: `wikantik-work-mcp`; `wikantik-work` entry mentions queues/agents/claims; the "Work management design" design-doc line: phase 2 built), `wikantik-work/CLAUDE.md`, `docs/developer/ProjectReference.md` (status block; surface table rows `/work-mcp` and updated `/api/work/*` auth), `docs/admin/WikantikOperations.md` (new section 1.15), `docs/admin/McpAgents.md` (endpoints table, key-only note, Claude Code config for `/work-mcp`), `docs/admin/ApiKeys.md` (scope table: `work`)
- Create: `wikantik-work-mcp/CLAUDE.md` (if not written in Task 10, complete it here)

- [ ] **Step 1: Write the browser step** `WorkBoardIT.agentHandoffShowsOnBoardAndInWaitingLane()` (Selenide, headless): admin `awaitAdminReady`; on `/work/agents` creates queue `implementer` and registers `codex@ui` (token read from the page); creates a story, assigns it to Queue · Implementer and moves it to Ready with the "Move to…" menu; the agent claims over REST with the token; reload the board → the card is under In progress showing "codex@ui · until"; the agent hands off to the admin's display name over REST; `/work` shows the item in "Waiting on you" with the note.
- [ ] **Step 2: Write the docs.** `wikantik-work/CLAUDE.md` adds: agents act only through key binding (retired → no actor); agent identity is by id columns, never names; claimable = queue served + `ready`/`review` + unclaimed + unblocked; claim/expiry touch only claim columns, status, version; registry changes write no `work_event`; `assignee` is logins only, queues live in `assignee_queue`. `wikantik-work-mcp/CLAUDE.md`: key-only by design; identity via the transport context extractor; no `WorkStore`; the instructions file and `WorkMcpIT.EXPECTED_TOOLS` change with every tool (the admin `McpProtocolIT.EXPECTED_TOOLS` is not involved). WikantikOperations 1.15: create queues, register an agent (owner, queues), hand the token to the session once, admit it to projects, rotate and retire; `wikantik.work.claim.expiryMinutes`.
- [ ] **Step 3: Run the gate:** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll `bin/agent-build.sh status gate` until SUCCESS or FAILED. Fix anything red in this session, including failures that look unrelated.
- [ ] **Step 4: Run** `mvn -B -fae pmd:check -Pcomplexity-gate` from the root (fix new violations by extraction; never add to the baseline) and `mvn clean install -Pcoverage -DskipITs -pl wikantik-work,wikantik-work-mcp -am`; keep `wikantik-work` at `0.90`, set `wikantik-work-mcp`'s floor to its measured level rounded down, capped at `0.90`.
- [ ] **Step 5: Deploy locally and look:** `mvn clean install -DskipTests -T 1C && bin/redeploy.sh` (V076 applies through `migrate.sh`); with `wikantik.work.enabled=true`, register an agent on `/work/agents`, point a Claude Code session at `http://localhost:8080/work-mcp` with its token, run the claim loop end to end, and check the board, item timeline and waiting lane in a browser; then `bin/simple-agility-conformance.sh --base-url http://localhost:8080 --prefix wikantik` and `curl -s localhost:8080/metrics | grep wikantik_work_claims`.
- [ ] **Step 6: Commit** `feat(work): browser IT, docs and coverage floors for phase 2`.
