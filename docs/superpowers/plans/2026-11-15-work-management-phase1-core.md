# Work Management Phase 1 (Core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship projects, products, epics/stories/tasks, history, comments, links and the board, dates, activity, item and epic views, so human-to-human work is complete and private to project members.

**Architecture:** A new `wikantik-work` module holds the domain, the policy and a `Jdbc`-backed store, and depends on neither `wikantik-api` nor `wikantik-main` (the `wikantik-insights` shape). `wikantik-main` wires it (`com.wikantik.work.runtime`) and supplies identity and rendering through ports; `wikantik-rest` exposes `/api/work/*`; the SPA gets `/work`. Every read and write goes through `WorkService`, which takes an `Actor` and applies visibility and policy in one place.

**Tech Stack:** Java 25, Maven, PostgreSQL 18 via `com.wikantik.jdbc.Jdbc`, JUnit 5 + Mockito + `PostgresTestDb`, Gson, React 19 + Vite + vitest. No new runtime dependencies, Java or npm.

**Spec:** `docs/superpowers/specs/2026-11-15-work-management-design.md` — read it first; this plan covers its phase 1 only.

## Global Constraints

- All database access through `com.wikantik.jdbc.Jdbc`; multi-statement writes use `inTransaction` and each gets a rollback test with `FaultInjectingDataSource`.
- Schema only via `bin/db/migrations/V074__work_management_core.sql`: idempotent DDL, `:app_user` grants, a no-op when re-applied, DDL only.
- Database tests: `@RequiresPostgres`, `PostgresTestDb.createDataSource()`, `PostgresTestDb.truncate(…)` in `@BeforeEach`; never `DROP`/`ALTER` in a test.
- `wikantik-work` must not depend on `wikantik-api` or `wikantik-main`.
- Non-members get **404, never 403**, and lists omit the project. Wiki Admins see everything.
- Nothing is hard-deleted. `work_event` is append-only.
- Work items never reach the page store, search index, bundles, sitemap, change feed, Page Graph, reference manager or the shared render caches.
- Statuses are fixed: `draft, refining, ready, in_progress, review, done, cancelled`. Epics use only `draft, in_progress, done, cancelled`.
- Hierarchy: task → story → epic, nothing deeper; parent must be in the same project.
- Feature flag `wikantik.work.enabled`, default `false`. When off: `/api/work/*` returns 404, services are not started, SPA routes stay registered, navigation entry hidden.
- Policy columns (`default_autonomy`, `autonomy_ceiling`, `deploy_gate`, `autonomy`, `done_means`, `allow_direct`) exist with defaults and are stored and returned but drive no behaviour in this phase.
- Sprints are not built but must stay addable as a new table plus nullable `work_item` columns. Do not encode "no sprints" into queries, the board model or event types.
- Never swallow an exception; log at least `LOG.warn` with context. Use `RestServletBase.sendError`, not the response's.
- Logins are shown only through `PublicIdentity` display names.
- No `News.md` entries. Stage files by name; never `git add -A`. Work on `main`.

## Review Focus

1. **Stale edit.** Two people edit one item; the second save carries an old `version`. Expect 409 with the current item, and the editor keeps the unsaved text.
2. **Access removed mid-session.** A member is removed, or leaves the granting group, while a board is open. Expect the next request to return 404, with no cached membership.
3. **Hostile or odd body text.** A body containing raw HTML/script, an `[{ALLOW view Admin}]` directive, or a wikilink to a private page. Expect inert display: no script runs, no access rule takes effect, no page title or existence is revealed.
4. **Bad parentage.** Setting a parent in another project, a task under an epic's sibling level, a cycle, or an epic with a product. Expect 400 with the reason.
5. **Archived or large projects.** An archived project is read-only (writes 409). A `done` column with thousands of items returns the 50 most recently closed plus a count.

---

## File Structure

```
wikantik-work/                                   new module
  pom.xml
  CLAUDE.md                                      module notes
  src/main/java/com/wikantik/work/
    WorkLevel, WorkStatus, WorkKind, WorkRole, Autonomy, DoneMeans, DeployGate   enums
    Actor, Project, Member, Product, Item, ItemRevision, WorkEvent, Comment, Link   records
    ItemDraft, ItemPatch, ItemFilter, BoardColumn, Decision, Action
    WorkPolicy                                   may(actor, role, action, item)
    WorkStore, JdbcWorkStore                     persistence, visibility-scoped
    WorkService                                  the only entry point for callers
    WorkRenderer                                 port: markdown → display HTML
    WorkMetrics                                  micrometer gauges/counters
    WorkNotFoundException, WorkDeniedException, WorkConflictException, WorkValidationException
bin/db/migrations/V074__work_management_core.sql
wikantik-main/src/main/java/com/wikantik/work/runtime/
    WorkWiring, WikiWorkActors, WikiWorkRenderer
wikantik-rest/src/main/java/com/wikantik/rest/
    WorkResourceBase, WorkProjectResource, WorkProductResource, WorkItemResource,
    WorkActivityResource, WorkMeResource
wikantik-frontend/src/api/work.js
wikantik-frontend/src/components/work/
    WorkLayout, WorkBoard, WorkCard, WorkDates, WorkActivity, WorkItemView,
    WorkItemForm, WorkEpicView, WorkTimeline, WorkSettings   (+ .test.jsx each)
```

---

### Task 1: Module, migration and feature flag

**Files:**
- Create: `wikantik-work/pom.xml` (copy `wikantik-insights/pom.xml`; same dependencies), `wikantik-work/CLAUDE.md`
- Create: `bin/db/migrations/V074__work_management_core.sql`
- Modify: root `pom.xml` (`<module>wikantik-work</module>` after `wikantik-insights`, plus every other place `wikantik-insights` is declared there), `wikantik-main/pom.xml`, `wikantik-rest/pom.xml`, `wikantik-war/pom.xml`, `wikantik-coverage-report/pom.xml` (mirror the `wikantik-insights` entries)
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (new `[Work]` section)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/CapabilitiesResource.java`
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkSchemaTest.java`, `CapabilitiesResourceTest` (existing)

**Interfaces:**
- Produces: tables `work_project, work_member, work_product, work_item, work_item_revision, work_event, work_comment, work_mention, work_link`; property `wikantik.work.enabled`; capability `work` (boolean) on `/api/capabilities`.

- [ ] **Step 1: Write the failing schema test.** `WorkSchemaTest` (`@RequiresPostgres`): `allNineTablesExist()` queries `information_schema.tables` for the nine names; `statusCheckRejectsUnknown()` inserts a `work_item` with `status='bogus'` and expects `SQLException`; `itemKeyIsUniquePerProject()` inserts two items with the same `(project_id, seq)` and expects `SQLException`; `policyColumnsDefault()` inserts a minimal project, product and item and asserts `default_autonomy='delegated'`, `autonomy_ceiling='delegated'`, `deploy_gate='human'`, `autonomy IS NULL`, `done_means IS NULL`, `allow_direct IS NULL`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest=WorkSchemaTest` — expect FAIL (module/tables missing).
- [ ] **Step 3: Write the migration.** Columns per the spec's phase 1 schema block, with these decisions:
  - Ids `BIGINT GENERATED ALWAYS AS IDENTITY`; timestamps `TIMESTAMP WITH TIME ZONE`; `due_on DATE`.
  - `work_project.key` `TEXT UNIQUE`, `CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$')`; `next_seq INTEGER NOT NULL DEFAULT 1`.
  - `work_product.key` `TEXT UNIQUE`, `CHECK (key ~ '^[a-z][a-z0-9-]{1,39}$')`.
  - `work_member` primary key `(project_id, principal_kind, principal)`; `principal_kind CHECK IN ('user','group','agent')`; `role CHECK IN ('viewer','member','lead')`.
  - `work_item`: `UNIQUE (project_id, seq)`; `level CHECK IN ('epic','story','task')`; `kind CHECK IN ('software','docs','admin')`; `status CHECK` the seven statuses; `priority SMALLINT NOT NULL DEFAULT 3` (`1` highest to `4`); `rank DOUBLE PRECISION NOT NULL`; `version INTEGER NOT NULL DEFAULT 1`; `parent_id` and `product_id` nullable foreign keys; `autonomy`, `done_means`, `allow_direct` nullable.
  - `work_event.type TEXT NOT NULL` with **no** `CHECK` (later phases add types); index `(project_id, at DESC)` and `(item_id, at)`.
  - `work_link.kind CHECK IN ('page','issue','url','blocked_by','commit')`.
  - Indexes: `work_item (project_id, status, rank)`, `(parent_id)`, `(assignee)`, `(due_on) WHERE due_on IS NOT NULL`.
  - Grants `SELECT, INSERT, UPDATE` to `:app_user` on all nine; `DELETE` only on `work_member` and `work_link`.
- [ ] **Step 4: Declare the key** in `wikantik.properties`: description, `# Type: boolean`, `wikantik.work.enabled = false`. Add `PROP_WORK_ENABLED` and `result.put( "work", … )` (default `false`) to `CapabilitiesResource`, with a test in `CapabilitiesResourceTest`.
- [ ] **Step 5: Regenerate docs:** `bin/config-reference.sh --write`.
- [ ] **Step 6: Run** `mvn test -pl wikantik-work -Dtest=WorkSchemaTest` and `mvn test -pl wikantik-main -Dtest='ConfigSurfaceDriftTest,ConfigReferenceRegressionTest'` and `mvn test -pl wikantik-rest -Dtest=CapabilitiesResourceTest` — expect PASS. Re-apply the migration by hand against the test container once to confirm a no-op.
- [ ] **Step 7: Commit** `feat(work): module, V074 schema and wikantik.work.enabled flag`.

---

### Task 2: Domain types and policy

**Files:**
- Create: the enums, records, exceptions, `Decision`, `Action`, `WorkPolicy` under `wikantik-work/src/main/java/com/wikantik/work/`
- Test: `WorkStatusTest`, `WorkLevelTest`, `WorkPolicyTest`

**Interfaces:**
- Produces:
  - Each enum has `String wire()` (lowercase, as stored) and `static X fromWire(String)` throwing `WorkValidationException` on unknown.
  - `WorkStatus.allowedFor(WorkLevel) : Set<WorkStatus>`; `WorkLevel.canParent(WorkLevel child) : boolean`.
  - `record Actor(String login, Set<String> groups, boolean admin, Kind kind, Long agentId)` with `enum Kind { HUMAN, AGENT }`; `Actor.human(login, groups, admin)`.
  - `record Project(long id, String key, String name, String description, Autonomy defaultAutonomy, Instant createdAt, Instant archivedAt)`.
  - `record Member(long projectId, String principal, String principalKind, WorkRole role)`.
  - `record Product(long id, String key, String name, String repoUrl, String defaultBranch, String wikiCluster, Autonomy autonomyCeiling, DeployGate deployGate, Instant archivedAt)`.
  - `record Item(long id, long projectId, String key, WorkLevel level, Long parentId, Long productId, WorkKind kind, String title, String body, WorkStatus status, int priority, double rank, LocalDate dueOn, String assignee, String reporter, Autonomy autonomy, DoneMeans doneMeans, Boolean allowDirect, int version, Instant createdAt, Instant updatedAt, Instant closedAt)`.
  - `record ItemDraft(String projectKey, WorkLevel level, String parentKey, String productKey, WorkKind kind, String title, String body, Integer priority, LocalDate dueOn, String assignee)`.
  - `record ItemPatch(int expectedVersion, Map<String,Object> changes, String note)` — `changes` keys are a closed set: `title, body, status, priority, rank, dueOn, assignee, parentKey, productKey, kind, autonomy, doneMeans, allowDirect`; a key present with `null` clears the field.
  - `record ItemRevision(long itemId, int version, String title, String body, String editedBy, Instant editedAt)`.
  - `record WorkEvent(long id, long projectId, Long itemId, String itemKey, Instant at, String actor, Actor.Kind actorKind, Long agentId, String type, String fromValue, String toValue, String note)`.
  - `record Comment(long id, long itemId, String author, String body, Instant createdAt, Instant editedAt, Instant removedAt)`.
  - `record Link(long id, long itemId, String kind, String target)`.
  - `enum Action { VIEW, CREATE_ITEM, EDIT_ITEM, MOVE, COMMENT, LINK, MANAGE_PROJECT, SET_POLICY, MANAGE_INSTANCE }`.
  - `record Decision(boolean allowed, String reason)`; `WorkPolicy.may(Actor actor, WorkRole roleOrNull, Action action, Item itemOrNull) : Decision`.
  - Event type constants in `WorkEvent`: `CREATED, EDITED, STATUS, ASSIGNED, HANDOFF, FIELD, COMMENTED, COMMENT_EDITED, COMMENT_REMOVED, LINKED, UNLINKED, MEMBER, SETTINGS`.

- [ ] **Step 1: Write the failing tests.**
  - `WorkStatusTest`: `epicAllowsOnlyFour()` → `allowedFor(EPIC)` equals `{DRAFT, IN_PROGRESS, DONE, CANCELLED}`; `storyAndTaskAllowAllSeven()`; `fromWireRejectsUnknown()`.
  - `WorkLevelTest`: `EPIC.canParent(STORY)`, `STORY.canParent(TASK)` true; `EPIC.canParent(TASK)`, `TASK.canParent(TASK)`, `STORY.canParent(EPIC)` false.
  - `WorkPolicyTest`, parameterised over the table below; also `deniedDecisionCarriesReason()` (reason non-blank) and `agentCannotSetPolicyOrManage()` (an `AGENT` actor with role `MEMBER` is denied `SET_POLICY`, `MANAGE_PROJECT`).

  | Role | VIEW | CREATE_ITEM, EDIT_ITEM, MOVE, COMMENT, LINK | MANAGE_PROJECT, SET_POLICY | MANAGE_INSTANCE |
  |---|---|---|---|---|
  | none (not admin) | deny | deny | deny | deny |
  | viewer | allow | deny | deny | deny |
  | member | allow | allow | deny | deny |
  | lead | allow | allow | allow | deny |
  | admin (any role or none) | allow | allow | allow | allow |

- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest='WorkStatusTest,WorkLevelTest,WorkPolicyTest'` — expect FAIL.
- [ ] **Step 3: Implement** the types and `WorkPolicy`. `SET_POLICY` covers `autonomy`, `doneMeans`, `allowDirect` and project `defaultAutonomy`; it always requires a `HUMAN` actor.
- [ ] **Step 4: Run** the same command — expect PASS.
- [ ] **Step 5: Commit** `feat(work): domain types and role policy`.

---

### Task 3: Store — projects, members, products, visibility

**Files:**
- Create: `WorkStore.java`, `JdbcWorkStore.java`
- Test: `JdbcWorkStoreProjectsPostgresTest.java`

**Interfaces:**
- Consumes: Task 2 records.
- Produces (`WorkStore`; all throw `SQLException`):
  - `Project createProject(String key, String name, String description)`
  - `Optional<Project> project(String key)`; `List<Project> projects()`
  - `void updateProject(long id, String name, String description, Autonomy defaultAutonomy)`; `void archiveProject(long id, Instant at)`
  - `void putMember(long projectId, String principal, String principalKind, WorkRole role)`; `void removeMember(long projectId, String principal, String principalKind)`; `List<Member> members(long projectId)`
  - `Optional<WorkRole> roleFor(long projectId, Actor actor)` — the highest role among the actor's `user` row and any `group` row whose principal is in `actor.groups()`; agents match `agent` rows by `agentId` as text.
  - `List<Project> visibleProjects(Actor actor)` — all if `actor.admin()`, else those with a role.
  - `Product createProduct(Product draft)`; `Optional<Product> product(String key)`; `List<Product> products()`; `void updateProduct(Product p)`; `void archiveProduct(long id, Instant at)`

- [ ] **Step 1: Write the failing tests** (truncate the nine tables in `@BeforeEach`):
  - `nonMemberSeesNoProjects()`, `userRowGrantsRole()`, `groupRowGrantsRole()`, `highestRoleWins()` (user=viewer, group=lead → `LEAD`), `adminSeesAllWithoutRows()`, `removingMemberRemovesVisibility()`, `duplicateProjectKeyRejected()`, `lowercaseProjectKeyRejected()`, `archivedProjectStillVisibleToMembers()`, `productRoundTrip()`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest=JdbcWorkStoreProjectsPostgresTest` — expect FAIL.
- [ ] **Step 3: Implement** `JdbcWorkStore(DataSource)` holding a `Jdbc`. `roleFor` is one query (`principal_kind='user' AND principal=? OR principal_kind='group' AND principal = ANY(?)`), ordered by role rank.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(work): project, member and product store with visibility`.

---

### Task 4: Store — items, revisions, events

**Files:**
- Modify: `WorkStore.java`, `JdbcWorkStore.java`
- Create: `ItemFilter.java`, `BoardColumn.java`
- Test: `JdbcWorkStoreItemsPostgresTest.java`, `JdbcWorkStoreItemsRollbackTest.java`

**Interfaces:**
- Produces:
  - `Item createItem(Project project, Item draft, String actor, Actor.Kind kind)` — one transaction: bump `next_seq`, insert item (`key` = `PROJ-seq`, `rank` = max rank in that status + 1024), insert revision `version=1`, insert `CREATED` event.
  - `Optional<Item> item(String key)` — key matched case-insensitively.
  - `Item updateItem(Item current, Item next, String actor, Actor.Kind kind, String note)` — one transaction: `UPDATE … WHERE id=? AND version=?` (zero rows → `WorkConflictException`), `version+1`; a revision row iff title or body changed; one event per changed field (`STATUS`, `ASSIGNED`, `EDITED` for title/body, `FIELD` otherwise, with `from_value`/`to_value`); `HANDOFF` replaces separate `STATUS`+`ASSIGNED` when both change and `note` is non-blank; sets `closed_at` on entering `done`/`cancelled` and clears it on leaving.
  - `record ItemFilter(Long projectId, Set<WorkStatus> statuses, String assignee, Long productId, WorkLevel level, Long parentId, String text, LocalDate dueBefore, int limit)`; `List<Item> items(ItemFilter f)`.
  - `record BoardColumn(WorkStatus status, int total, List<Item> items)`; `List<BoardColumn> board(long projectId, ItemFilter f)` — every status in order; `done` and `cancelled` capped at 50 by `closed_at DESC`, others by `rank`.
  - `List<ItemRevision> revisions(long itemId)`; `List<WorkEvent> itemEvents(long itemId)`; `List<WorkEvent> projectEvents(long projectId, Instant before, int limit)`
  - `Set<Long> productIdsUnder(long epicId)` — distinct products of descendants.
  - `void appendEvent(Connection c, WorkEvent e)` (package-private, reused by Task 5).

- [ ] **Step 1: Write the failing tests.**
  - `keysAreSequentialPerProject()` → `PLAT-1`, `PLAT-2`, and a second project starts at `-1`.
  - `concurrentCreatesGetDistinctKeys()` — 8 threads × 5 creates, 40 distinct keys.
  - `createWritesRevisionAndEvent()`; `lookupIsCaseInsensitive()` (`plat-1`).
  - `staleVersionThrowsConflict()`; `titleChangeWritesRevisionAndEditedEvent()`; `priorityChangeWritesFieldEventOnly()` (no new revision; `from_value='3'`, `to_value='1'`).
  - `statusPlusAssigneeWithNoteIsOneHandoffEvent()`; `closingSetsClosedAtAndReopeningClearsIt()`.
  - `boardReturnsAllSevenColumnsInOrder()`; `doneColumnCappedAtFiftyWithTrueTotal()` (insert 60).
  - `textFilterMatchesTitleAndBody()`; `epicProductsDerivedFromDescendants()`.
  - Rollback (`FaultInjectingDataSource`): `createRollsBackWhenEventInsertFails()` (no item row, `next_seq` unchanged); `updateRollsBackWhenRevisionInsertFails()` (item unchanged, version unchanged).
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest='JdbcWorkStoreItems*'` — expect FAIL.
- [ ] **Step 3: Implement.** Sequence allocation is `UPDATE work_project SET next_seq = next_seq + 1 WHERE id = ? RETURNING next_seq - 1` inside the transaction. Text filter is `ILIKE` on title and body.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(work): item store with revisions, events and board query`.

---

### Task 5: Store — comments, mentions, links

**Files:**
- Modify: `WorkStore.java`, `JdbcWorkStore.java`
- Test: `JdbcWorkStoreDiscussionPostgresTest.java`

**Interfaces:**
- Produces:
  - `Comment addComment(Item item, String author, Actor.Kind kind, String body, List<String> mentioned)` — comment + mention rows + `COMMENTED` event, one transaction.
  - `Comment editComment(long commentId, String body, String actor)` (`COMMENT_EDITED`); `void removeComment(long commentId, String actor)` — sets `removed_at`, blanks nothing, writes `COMMENT_REMOVED`.
  - `Optional<Comment> comment(long id)`; `List<Comment> comments(long itemId)` — a removed comment is returned with `body = ""`.
  - `Link addLink(Item item, String kind, String target, String actor)`; `void removeLink(long linkId, String actor)`; `List<Link> links(long itemId)`
  - `boolean isBlocked(long itemId)` — true if any `blocked_by` link targets an item key whose status is not `done`/`cancelled`.
  - `List<Item> mentioning(String login, Set<Long> projectIds)`; `List<Item> assignedOpen(String login, Set<Long> projectIds)`

- [ ] **Step 1: Write the failing tests:** `commentWritesMentionsAndEvent()`, `removedCommentKeepsRowAndHidesBody()`, `editSetsEditedAt()`, `linkAndUnlinkWriteEvents()`, `blockedByOpenItemBlocks()`, `blockedByDoneItemDoesNot()`, `mentioningIsScopedToGivenProjects()` (a mention in a project outside the set is not returned), `commentRollsBackWhenEventInsertFails()`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest=JdbcWorkStoreDiscussionPostgresTest` — expect FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(work): comments, mentions and links`.

---

### Task 6: WorkService — the single entry point

**Files:**
- Create: `WorkService.java`, `WorkRenderer.java`
- Test: `WorkServiceTest.java` (Mockito over `WorkStore`), `WorkServiceVisibilityPostgresTest.java`

**Interfaces:**
- Consumes: `WorkStore`, `WorkPolicy`, `WorkRenderer`.
- Produces: `WorkService(WorkStore store, WorkPolicy policy, WorkRenderer renderer, Clock clock)`. Every method takes `Actor` first. It throws `WorkNotFoundException` when the project or item is unknown **or not visible**, `WorkDeniedException(reason)` when visible but the action is refused, `WorkValidationException`, `WorkConflictException`.
  - Projects: `listProjects(a)`, `project(a, key)`, `createProject(a, key, name, description)`, `updateProject(a, key, name, description, defaultAutonomy)`, `archiveProject(a, key)`, `members(a, key)`, `putMember(a, key, principal, principalKind, role)`, `removeMember(a, key, principal, principalKind)`, `roleIn(a, key) : WorkRole`
  - Products: `listProducts(a)` (requires admin or membership of any project), `createProduct(a, draft)`, `updateProduct(a, product)`, `archiveProduct(a, key)`
  - Items: `createItem(a, ItemDraft)`, `item(a, key) : ItemView`, `updateItem(a, key, ItemPatch) : ItemView`, `items(a, projectKey, ItemFilter)`, `board(a, projectKey, ItemFilter)`, `revisions(a, key)`, `events(a, key)`, `activity(a, projectKey, before, limit)`
  - Discussion: `addComment(a, key, body)`, `editComment(a, commentId, body)`, `removeComment(a, commentId)`, `addLink(a, key, kind, target)`, `removeLink(a, linkId)`
  - Me: `waitingOn(a) : List<Item>` — open items assigned to the actor plus items mentioning them, across visible projects only.
  - `record ItemView(Item item, String bodyHtml, String projectKey, String parentKey, String productKey, List<String> derivedProductKeys, List<Comment> comments, List<Link> links, boolean blocked, WorkRole viewerRole)`
  - `WorkRenderer { String toHtml(String markdown); }`

- [ ] **Step 1: Write the failing tests.**
  - Visibility: `nonMemberItemLookupIsNotFound()`, `nonMemberBoardIsNotFound()`, `viewerEditIsDeniedNotNotFound()`, `adminNeedsNoMembership()`, `commentIdFromInvisibleProjectIsNotFound()`, `linkIdFromInvisibleProjectIsNotFound()`.
  - Validation (each expects `WorkValidationException` naming the field): `parentInOtherProject()`, `taskDirectlyUnderEpic()`, `itemParentedToItself()`, `epicWithProduct()`, `epicWithStatusReady()`, `unknownChangeKey()`, `handoffWithoutNote()` (status and assignee both change, blank note), `blankTitle()`, `titleOver200Chars()`, `assigneeNotAProjectMember()`.
  - Behaviour: `archivedProjectRejectsWritesWithConflict()`; `setPolicyFieldsRequiresLead()` (member sets `autonomy` → denied); `mentionsParsedFromBodyAreLimitedToMembers()` (`@outsider` produces no mention row); `onlyAuthorEditsComment()`; `authorOrLeadRemovesComment()`; `waitingOnExcludesInvisibleProjects()`; `createProjectRequiresAdminAndAddsNoMembers()`.
  - `WorkServiceVisibilityPostgresTest.everyReadMethodHidesInvisibleProject()` — reflectively call every public read method with a non-member actor against a real store; each must throw `WorkNotFoundException` or return an empty collection. This is the guard that a new read method cannot skip the check.
- [ ] **Step 2: Run** `mvn test -pl wikantik-work -Dtest='WorkService*'` — expect FAIL.
- [ ] **Step 3: Implement.** One private `Scope scope(Actor, projectKey)` resolves project + role and throws not-found; every method starts there. Mentions are `@login` tokens in a comment body, kept only if the login has a role in the project.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(work): WorkService with visibility, validation and policy`.

---

### Task 7: Runtime wiring, identity and rendering

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/work/runtime/WorkWiring.java`, `WikiWorkActors.java`, `WikiWorkRenderer.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (next to the `InsightsWiring.build` call near line 1154, plus a `workService()` accessor like `contentOpportunityService()`)
- Create: `wikantik-work/src/main/java/com/wikantik/work/WorkMetrics.java`
- Test: `wikantik-main/src/test/java/com/wikantik/work/runtime/WikiWorkRendererTest.java`, `WikiWorkActorsTest.java`, `WorkWiringTest.java`; `wikantik-work/…/WorkMetricsTest.java`

**Interfaces:**
- Consumes: `WorkService` constructor (Task 6).
- Produces:
  - `WorkWiring.build(DataSource ds, Properties props, Engine engine) : WorkService` — returns `null` when `wikantik.work.enabled` is false.
  - `WikiEngine.workService() : WorkService` (nullable).
  - `WikiWorkActors.actorFor(Engine engine, Session session) : Actor` — login, the names of the wiki groups the user belongs to (resolved on each call, no caching), `admin` = holds `AllPermission`. Returns `null` for an anonymous session.
  - `WikiWorkRenderer implements WorkRenderer`.
  - `WorkMetrics(MeterRegistry, WorkStore)` — gauge `wikantik_work_items{status,level,kind}`; counter `wikantik_work_denied_total{action}`. No project, product or item labels.

- [ ] **Step 1: Write the failing tests.**
  - `WikiWorkRendererTest`: `rendersMarkdown()`; `scriptTagIsNotExecutableOutput()` (output contains no `<script`); `aclDirectiveIsInertText()` (`[{ALLOW view Admin}]` in the body changes no page ACL and is not rendered as a rule); `wikilinkDoesNotRegisterReference()` (after rendering `[[SomePage]]`, the reference manager reports no referrers from any work source and `SomePage` gains no backlink); `outputIsNotCached()` (render twice with a changed body; second result reflects the change and the document/HTML caches hold no new entry — use the `setManager` rendering seam); `linkToMissingPageRendersLikeExistingPage()` (same markup either way, so existence is not revealed).
  - `WikiWorkActorsTest`: `anonymousIsNull()`, `groupsReflectMembershipChangeImmediately()`, `adminFlagFollowsAllPermission()`.
  - `WorkWiringTest`: `disabledFlagBuildsNothing()`.
  - `WorkMetricsTest`: `gaugeHasNoProjectLabel()` — assert the meter's tag keys are exactly `status, level, kind`.
- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest='WikiWork*Test,WorkWiringTest'` — expect FAIL.
- [ ] **Step 3: Implement.** Find the rendering path the editor preview uses and render through it with a throwaway context that is flagged `Context.VAR_VIEWER_SENSITIVE`; sanitise as page rendering does. Do not add `WorkWiring` to the `getManager` allow-list — follow the `InsightsWiring` seam.
- [ ] **Step 4: Run** the same command plus `mvn test -pl wikantik-main -Dtest='*ArchTest'` — expect PASS. If an ArchUnit freeze store changed, `git restore` it and fix the violation instead.
- [ ] **Step 5: Commit** `feat(work): runtime wiring, actor resolution, display-only rendering, metrics`.

---

### Task 8: REST surface

**Files:**
- Create: `WorkResourceBase.java`, `WorkProjectResource.java`, `WorkProductResource.java`, `WorkItemResource.java`, `WorkActivityResource.java`, `WorkMeResource.java` in `wikantik-rest/src/main/java/com/wikantik/rest/`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (five servlets + mappings)
- Test: one `…ResourceTest` per resource (Mockito over `WorkService`); `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkApiIT.java`, `WorkLeakIT.java`; enable `wikantik.work.enabled=true` in that IT module's properties overlay.

**Interfaces:**
- Consumes: `WikiEngine.workService()`, `WikiWorkActors.actorFor`.
- Produces (JSON field names are the record component names):

  | Method and path | Body / query | Returns |
  |---|---|---|
  | `GET /api/work/projects` | | `[Project + role]` |
  | `POST /api/work/projects` | `key, name, description` | `Project` (201) |
  | `GET|PATCH /api/work/projects/{key}` | `name, description, defaultAutonomy` | `Project + role` |
  | `POST /api/work/projects/{key}/archive` | | 204 |
  | `GET /api/work/projects/{key}/members` | | `[Member + displayName]` |
  | `PUT|DELETE /api/work/projects/{key}/members` | `principal, principalKind, role` | 204 |
  | `GET /api/work/projects/{key}/board` | `assignee, product, level, parent, q` | `[BoardColumn]` |
  | `GET /api/work/products`, `POST`, `PATCH /{key}`, `POST /{key}/archive` | `Product` fields | `Product` |
  | `GET /api/work/items` | `project` (required), `status, assignee, product, level, parent, q, dueBefore, limit` | `[Item]` |
  | `POST /api/work/items` | `ItemDraft` | `ItemView` (201) |
  | `GET /api/work/items/{key}` | | `ItemView` |
  | `PATCH /api/work/items/{key}` | `expectedVersion, changes, note` | `ItemView` |
  | `GET /api/work/items/{key}/revisions`, `/events` | | lists |
  | `POST /api/work/items/{key}/comments` | `body` | `Comment` (201) |
  | `PATCH|DELETE /api/work/comments/{id}` | `body` | `Comment` / 204 |
  | `POST /api/work/items/{key}/links` | `kind, target` | `Link` (201) |
  | `DELETE /api/work/links/{id}` | | 204 |
  | `GET /api/work/activity` | `project` (required), `before`, `limit` (default 50, max 200) | `[WorkEvent]` |
  | `GET /api/work/me` | | `{ waitingOn: [Item], projects: [Project + role] }` |

  `/api/work/comments/*` and `/api/work/links/*` are served by `WorkItemResource` (extra mappings).
- `WorkResourceBase extends RestServletBase`: `service()` returns 404 when `workService()` is null; 401 when the actor is null; maps `WorkNotFoundException`→404, `WorkDeniedException`→403 with the reason, `WorkValidationException`→400, `WorkConflictException`→409 with the current `ItemView` in the body. Actor names in responses go through `PublicIdentity`.

- [ ] **Step 1: Write the failing unit tests** — per resource: happy path per row above; `disabledFeatureIs404()`; `anonymousIs401()`; `notFoundMapsTo404()`; `deniedMapsTo403WithReason()`; `conflictMapsTo409WithCurrentItem()`; `validationMapsTo400()`; `responseShowsDisplayNameNotLogin()`; `limitIsClampedTo200()`; `unknownJsonFieldInChangesIs400()`.
  - `WorkAccessArchTest` (ArchUnit, in `wikantik-rest`): no class outside `com.wikantik.work..` depends on `WorkStore` or `JdbcWorkStore`, except `com.wikantik.work.runtime.WorkWiring`. This is what keeps every read behind `WorkService`'s visibility check.
- [ ] **Step 2: Run** `mvn test -pl wikantik-rest -Dtest='Work*ResourceTest,WorkAccessArchTest'` — expect FAIL.
- [ ] **Step 3: Implement** the resources and register them in `web.xml`.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Write the ITs.**
  - `WorkApiIT`: admin creates project `PLAT` and product `wikantik`, adds a member; member creates epic → story → task, moves the task across every status, edits the story twice, comments, links a page; assert revisions (3), events present in order, board columns. `nonMemberGets404Everywhere()` walks every GET path above as a second user and asserts 404 or `[]`. `staleVersionIs409()`. `removedMemberLosesAccessOnNextRequest()`.
  - `WorkLeakIT`: create an item whose title and body contain the token `zqworkleak7`; assert the token is absent from `/api/search`, `/api/bundle`, `/api/changes`, `/sitemap.xml`, the page list, and that a wikilink in the body to an existing page adds no entry to that page's backlinks (`/api/pages/{name}/backlinks` or the equivalent the IT suite already uses).
- [ ] **Step 6: Run** `bin/run-tests.sh --module rest` (or the module-scoped IT invocation the script documents) — expect PASS.
- [ ] **Step 7: Commit** `feat(work): /api/work REST surface with visibility and leak ITs`.

---

### Task 9: Frontend — shell, client, board

**Files:**
- Create: `wikantik-frontend/src/api/work.js`, `components/work/WorkLayout.jsx`, `WorkBoard.jsx`, `WorkCard.jsx`, `WorkItemForm.jsx` (+ tests), `styles/work.css`
- Modify: `wikantik-frontend/src/main.jsx` (routes), the navigation component that lists top-level entries (add "Work", shown only when `capabilities.work` and `/api/work/me` returns at least one project or the user is admin)
- Modify: `wikantik-rest/…/SpaRoutingFilter.java` (`"/work/"` in `SPA_PREFIXES`, `"/work"` in `SPA_EXACT`), `web.xml` (filter mappings `/work` and `/work/*`)
- Test: `SpaRoutingFilterTest` (existing), vitest files

**Interfaces:**
- Consumes: Task 8 endpoints.
- Produces: `work.js` exports one function per endpoint, named `listProjects, getProject, createProject, updateProject, archiveProject, listMembers, putMember, removeMember, getBoard, listProducts, createProduct, updateProduct, listItems, createItem, getItem, patchItem, listRevisions, listEvents, addComment, editComment, removeComment, addLink, removeLink, getActivity, getMe`. `patchItem` rejects with `{ status: 409, current }` on conflict.
- Routes: `/work` (project list + waiting-on-you), `/work/:project` (board), `/work/:project/dates`, `/work/:project/activity`, `/work/:project/settings`, `/work/item/:key`, `/work/products` (admin).

- [ ] **Step 1: Write the failing tests.**
  - `SpaRoutingFilterTest`: `/work`, `/work/PLAT`, `/work/item/PLAT-1` forward to the shell.
  - `WorkBoard.test.jsx`: `rendersSevenColumnsInOrder`; `showsWaitingOnYouLaneOnlyWhenNonEmpty`; `dropCallsPatchItemWithStatusAndExpectedVersion`; `refusedMoveShowsReasonAndRestoresCard` (403 with reason); `conflictReloadsBoard` (409); `viewerCannotDrag` (role viewer → cards not draggable); `doneColumnShowsTotalWhenCapped` ("50 of 312"); `groupsByEpicWhenToggled`; `keyboardMoveMenuMovesCard` (a "Move to…" menu on each card, since drag is not keyboard-accessible).
  - `WorkCard.test.jsx`: shows key, title, product key, assignee display name, priority, due date, `overdue` class when `dueOn` is past and the item is open, blocked marker, comment count.
  - `WorkItemForm.test.jsx`: level choice restricts parent options (task → stories, story → epics, epic → none); epic hides product; submit disabled while title blank.
- [ ] **Step 2: Run** `cd wikantik-frontend && npx vitest run src/components/work` and `mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterTest` — expect FAIL.
- [ ] **Step 3: Implement.** Native HTML5 drag and drop; no new npm dependency. Reuse `src/components/ui/` primitives and defined CSS tokens only. Dropping between cards sets `rank` to the midpoint of its neighbours.
- [ ] **Step 4: Run** both commands and `npx eslint src/components/work src/api/work.js` — expect PASS.
- [ ] **Step 5: Commit** `feat(work): SPA shell, API client and board`.

---

### Task 10: Frontend — item and epic views

**Files:**
- Create: `components/work/WorkItemView.jsx`, `WorkTimeline.jsx`, `WorkEpicView.jsx`, `WorkComments.jsx`, `WorkLinks.jsx` (+ tests)

**Interfaces:**
- Consumes: `work.js`; existing `DiffViewer`, `MentionPicker`, `CommentBody`.
- Produces: `WorkItemView` at `/work/item/:key`; it renders `WorkEpicView` when `item.level === 'epic'`.

- [ ] **Step 1: Write the failing tests.**
  - `WorkItemView.test.jsx`: `rendersBodyHtmlFromServer`; `fieldEditPatchesOnlyThatField`; `conflictKeepsUnsavedTextAndOffersReload` (409 → the editor still holds the typed text, a banner shows the newer version); `handoffRequiresNote` (changing status and assignee together shows a required note field); `viewerSeesNoEditControls`; `policyFieldsEditableOnlyByLead` (autonomy, done-means, allow-direct are shown to all, editable by lead/admin, labelled "takes effect in a later release"); `notFoundShowsNeutralMessage` (404 → "No such item", no hint the project exists).
  - `WorkTimeline.test.jsx`: renders one row per event with display name, type-specific text (`status draft → ready`), and the handoff note; `revisionRowsOpenDiff` (an `EDITED` event links to a diff of that version against the previous).
  - `WorkComments.test.jsx`: `mentionPickerOffersProjectMembersOnly` (fed from `listMembers`, not the wiki user list); `removedCommentShowsMarker`; `onlyAuthorSeesEdit`.
  - `WorkLinks.test.jsx`: `pageLinkRendersAsWikiLinkByNameOnly`; `blockedByShowsTargetStatusOnlyIfVisible`.
  - `WorkEpicView.test.jsx`: `listsStoriesWithTaskCounts`; `progressIsDoneOverNonCancelled`; `showsDerivedProducts`.
- [ ] **Step 2: Run** `npx vitest run src/components/work` — expect FAIL.
- [ ] **Step 3: Implement.** The body editor is the existing Markdown editor component in its plain mode; save sends `{ expectedVersion, changes: { body } }`.
- [ ] **Step 4: Run** vitest and eslint — expect PASS.
- [ ] **Step 5: Commit** `feat(work): item view, timeline, comments, links and epic view`.

---

### Task 11: Frontend — dates, activity, settings

**Files:**
- Create: `components/work/WorkDates.jsx`, `WorkActivity.jsx`, `WorkSettings.jsx`, `WorkProducts.jsx`, `WorkHome.jsx` (+ tests)

- [ ] **Step 1: Write the failing tests.**
  - `WorkDates.test.jsx`: `groupsIntoOverdueThisWeekLaterNoDate`; `excludesDoneAndCancelled`; `overdueSortedOldestFirst`.
  - `WorkActivity.test.jsx`: `loadsFiftyThenMoreWithBeforeCursor`; `filtersByTypeAndActorClientSide`; `itemKeysLinkToItemView`.
  - `WorkSettings.test.jsx`: `hiddenFromNonLeads` (member → redirected to the board); `addMemberByUserOrGroup`; `changingOwnLeadRoleAsksForConfirmation`; `archiveAsksForConfirmationInline` (no browser dialog).
  - `WorkProducts.test.jsx`: `adminOnly`; `createValidatesKeyPattern` (`^[a-z][a-z0-9-]{1,39}$`).
  - `WorkHome.test.jsx`: `listsVisibleProjectsWithRole`; `showsWaitingOnYou`; `adminSeesCreateProject`; `emptyStateForUserWithNoProjects`; `disabledFeatureShowsNotEnabled` (when `capabilities.work` is false).
- [ ] **Step 2: Run** `npx vitest run src/components/work` — expect FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** vitest and eslint — expect PASS.
- [ ] **Step 5: Commit** `feat(work): dates, activity, project settings and products`.

---

### Task 12: Browser IT, documentation and the gate

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/WorkBoardIT.java`
- Modify: root `CLAUDE.md` (module list entry for `wikantik-work`; pointer to `wikantik-work/CLAUDE.md`), `docs/developer/ProjectReference.md` (agent-facing surface table: `/api/work/*`), `docs/admin/WikantikOperations.md` (enabling the feature, creating the first project)

- [ ] **Step 1: Write `WorkBoardIT`** (Selenide, headless): log in as admin, `awaitAdminReady`, create project and a story through the UI, move it to `ready` with the "Move to…" menu, open it, edit the body, add a comment, confirm the timeline shows four events; log in as a non-member and assert `/work/PLAT` shows the neutral not-found state and "Work" is absent from the navigation.
- [ ] **Step 2: Write the docs.** `wikantik-work/CLAUDE.md` states: the module depends on neither `wikantik-api` nor `wikantik-main`; every caller goes through `WorkService`; non-members get 404; nothing is hard-deleted; no project labels on metrics; sprints must remain addable.
- [ ] **Step 3: Run the gate:** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`, poll `bin/agent-build.sh status gate` until it reports SUCCESS or FAILED. Fix anything red in this session, including failures that look unrelated.
- [ ] **Step 4: Run** `mvn pmd:check -Pcomplexity-gate` and `mvn clean install -Pcoverage -DskipITs -pl wikantik-work -am`; set `wikantik-work`'s coverage floor in its pom to the measured level rounded down, capped at `0.90`.
- [ ] **Step 5: Deploy locally and look:** `mvn clean install -DskipTests -T 1C && bin/redeploy.sh` with `wikantik.work.enabled=true` in `tomcat/tomcat-11/lib/wikantik-custom.properties`; walk the board, an item, dates and activity in a browser, and run `bin/simple-agility-conformance.sh --base-url http://localhost:8080 --prefix wikantik`.
- [ ] **Step 6: Commit** `feat(work): browser IT, docs and coverage floor for phase 1`.
