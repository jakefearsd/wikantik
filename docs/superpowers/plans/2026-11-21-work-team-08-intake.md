# Work Management for Small Teams — Part 8: Intake — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** People outside a project can be given a `reporter` role that lets them file stories and tasks into it and see, comment on, attach to and watch only the items they reported, while every existing read treats them exactly as a stranger.

**Architecture:** `WorkStanding` keeps meaning what it means today: `roleIn` and `visibleProjects` answer "viewer or above", so a reporter row grants nothing through any existing path (fail-closed). A reporter's only way in is three new, explicit questions on `WorkStanding` (`mayFile`, `canSee`, `reportsOnly`), asked by two new resolvers on `WorkScopes` (`intakeScope`, `ownItemScope`) and used by a new facade `WorkService.features().intake()` (`WorkIntakeService`), plus the few surfaces of Parts 3 and 7 the design opens to a reporter's own item. A reporter is shown a separate record, `ReportedItemView`, that holds no `Item`, so no field can leak by serialisation.

**Tech Stack:** Java 25, PostgreSQL 18 (`Jdbc`, `PostgresTestDb`), JUnit 5, Mockito, React 19 + vitest, Cargo/Selenide ITs.

**Spec:** docs/superpowers/specs/2026-11-21-work-team-features-design.md, section 8 (decisions D12, D13); index and global constraints: docs/superpowers/plans/2026-11-21-work-team-features.md

**Depends on:** Parts 1–7, all built and released. Names below are the outline's; where the built code names a thing differently, use the built name and keep the behaviour this plan pins.
- Part 1: `enum WorkType { FEATURE, BUG, CHORE }` with `fromWire`; `ItemDraft` carrying `type` and `labels`; `WorkService.labelsOf(Actor, String projectKey) : List<LabelCount>`.
- Part 2: `WorkService.features().sprints() : WorkSprintsService` (`list`, `get`, `create`, `update`, `start`, `close`, the report and velocity reads). Used only in tests here.
- Part 3: `WorkService.features().notifications() : WorkNotificationsService` with `dispatch(int maxEvents) : int`, `inbox(actor, cursor, limit)`, `unread(actor) : int`, `markRead(actor, itemKey)`, `markAllRead(actor)`, `watch/mute/unwatch(actor, itemKey)`, `sendEmail(Instant now) : int`; `Recipients.of(WorkEvent, EventFacts)`; `JdbcWorkNotifications` (the inbox and unread statements); `WorkService.features().webhooks() : WorkWebhooksService` (`list`, `add`, `update`, `remove`); `WorkWatchStore.watch(itemId, login)`; `WorkNotifyDispatcher` in `com.wikantik.work.runtime`.
- Part 4: `WorkService.features().find() : WorkFindService` with `find(Actor, FindFilter) : Page<ItemSummary>`, `saved(actor)`, `save`; work home's built-in "Reported by me" view.
- Part 5: `WorkChangesService.acknowledgeFlag(actor, changeId)`; waiting reason `change_outside_policy`; `Actor.Kind.SYSTEM`; the `codehost/` subpackage.
- Part 6: `WorkService.features().flow() : WorkFlowService` with `project(actor, projectKey, FlowQuery)`, `dora(actor, projectKey, DoraQuery)`, `doraForProduct(...)`.
- Part 7: `WorkService.features().attachments() : WorkAttachmentsService` (upload, list, content, remove; routes `POST|GET /api/work/items/{key}/attachments`, `GET|DELETE …/attachments/{id}`); event type `attached` (`WorkEvent.ATTACHED`); `Action.ATTACH`; `WorkAttachments.jsx`; `uploadAttachment(key, file)` in `api/work.js`.

## Cross-part rulings

Added when the eight part plans were reconciled (index: `docs/superpowers/plans/2026-11-21-work-team-features.md`). **Where a ruling and the text below disagree, the ruling wins.**

- **R1 — new facades hang off `WorkService.features()`, not `WorkService`.** `WorkService` is at the PMD `TooManyMethods` limit (40). Part 1 creates `com.wikantik.work.WorkFeatures` (built in `WorkService`'s constructor, returned by the one new method `WorkService.features()`); every facade accessor this plan calls `WorkService.x()` is `WorkService.features().x()`. `WorkServiceVisibilityPostgresTest` treats `WorkFeatures`' accessors as facades (its `FACADES` set). No suppression, no baseline entry.
- **R3 — reserved project keys.** `SUBMIT` goes in `WorkInputs.RESERVED_KEYS` (P8-11).
- **R9 — the source-rule test.** Part 5 has no subpackage, so the `Files.walk` change in Task 8.1b is a hardening only; keep it.

## What this part builds on

Verified at `66b56b228f` (2.7.9-SNAPSHOT). Line numbers will have moved after Parts 1–7; re-find each by the quoted text.

- `WorkRole` (`wikantik-work/src/main/java/com/wikantik/work/WorkRole.java`): `VIEWER, MEMBER, LEAD`, declaration order is privilege order; `atLeast(WorkRole)`, `static highest(Collection<WorkRole>)`, `fromWire(String)` (field `role`).
- `WorkStanding` (`…/work/WorkStanding.java`, package-private): `Optional<WorkRole> roleIn(long projectId, Actor)`, `List<Project> visibleProjects(Actor)`. An agent's role is the lower of its own row and its sponsor's (`ceiling.get().atLeast(own.get()) ? own : ceiling`); with a reporter sponsor that expression would hand the agent `REPORTER` today. `WorkStandingSourceRuleTest` scans only the top-level package (`Files.list`) for `store.roleFor(` and `store.visibleProjects(`.
- `JdbcWorkCatalog` (`…/work/JdbcWorkCatalog.java`): `ROLES_FOR_SQL` (raw rows; `roleOn` returns `WorkRole.highest`), `VISIBLE_PROJECTS_SQL` (`EXISTS` over any member row: a reporter row would make the project visible today), `members(projectId)`. `JdbcWorkStore extends JdbcWorkCatalog implements WorkStore`; `WorkStore extends WorkCatalogStore`.
- `WorkScopes` (`…/work/WorkScopes.java`): `scope(Actor, String)` throws `WorkNotFoundException(NOT_FOUND)` when `roleIn` is empty and the actor is not an admin; `itemScope`, `itemScopeById`, `commentScope` go through it; `isMember(projectId, login)` is `roleIn(...).isPresent()`; `Scope.require(Action, Item)` throws `WorkDeniedException` (a 403) when `WorkPolicy.may` refuses.
- `WorkPolicy.may(Actor, WorkRole, Action, Item)`, `WorkPolicy.startStatus(Actor, WorkLevel)` (a person's story or task starts `ready`, an epic and every agent's item `draft`). `Action` carries `minRole`, `management`, `fenced`. `WorkPolicyActionTableTest` pins every decision against `src/test/resources/com/wikantik/work/policy-action-table.golden`, with roles `{ null, VIEWER, MEMBER, LEAD }`.
- `WorkInputs.requireAgentRole(WorkRole)` refuses `LEAD` ("An agent's role is viewer or member."), called by `WorkService.putMember` and by `JdbcWorkMembers.put`. `WorkInputs.RESERVED_KEYS = { ITEM, PRODUCTS, AGENTS, LOG }` (project keys that would collide with `/work/<word>` routes; the router is case-insensitive).
- `ItemViews.view(Scope, Item) : ItemView` and `WorkService.ItemView` (holds the whole `Item`, links, parent and product keys). REST serialises records whole with `WorkJson.GSON` and then swaps logins for display names (`NAME_KEYS` includes `reporter`, `author`, `actor`). So anything placed in a record reaches the wire.
- `store.createItem(Project, Item, Actor)` writes the item and its `created` event in one transaction; `ItemPatchApplier.forCreate(Project, ItemDraft, WorkStatus)` validates title, level and shape.
- `WorkServiceVisibilityPostgresTest`: sets `READS`, `WRITES`, `SYSTEM`, `OWN`, `YES_OR_NO`, `INSTANCE_READS`, `FACADES`; `strangers()` drives every read; `everyProjectScopedWriteHidesInvisibleProject` lists writes.
- REST: `WorkItemResource.create` refuses a field outside `DRAFT_FIELDS` with `WorkValidationException(field, "Unknown field: " + field)`; `WorkMeResource` (`/api/work/me`); `WorkResourceBase.projectWithRole` dumps the whole `Project` (description included). Tests use `WorkHttp.wire(servlet, mockService, actor)`.
- Frontend: `/work/item/:key` → `WorkItemView.jsx` (its `ItemPage` calls `getItem`); `WorkTimeline` fetches `listEvents` itself; `WorkComments({ comments, members, canComment, isLead, onAdd, onEdit, onRemove })`; `WorkSettings.jsx` `ROLES`, `AGENT_ROLES`, `SPONSOR_NOTE`, `changeRole`, `afterOwnRoleChange`.
- IT: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkBoardIT.java` (users `Alice`, `Bob`, password `password`; admin `Env.LOGIN_JANNE_USERNAME`).

## The audit: every place standing is decided

Decision P8-1 makes `roleIn` and `visibleProjects` mean "viewer or above". Under it, a site that asks "has a role?" keeps its meaning and a reporter arrives there as a stranger. The table lists every site found and what it must become. **Task 8.1b re-runs the search after Parts 1–7 and classifies every new hit the same way before anything else is written.**

| # | Site (at `66b56b228f`) | What it does | Must become |
|---|---|---|---|
| 1 | `WorkStanding.java:49-66` `roleIn` | raw role; agent capped by sponsor | Filter the person's role, the agent's own row and the sponsor's ceiling to `atLeast(VIEWER)`. A reporter, and an agent whose sponsor is only a reporter, get empty. |
| 2 | `WorkStanding.java:69-86` `visibleProjects` | store list; agent ∩ sponsor | Code unchanged; correct once row 4 changes (the sponsor's list is the same query). |
| 3 | `JdbcWorkCatalog.java:62-67,279-295` `ROLES_FOR_SQL`, `roleFor`, `roleOn` | highest of the caller's rows | Unchanged: stays raw. A `viewer` group row beats a `reporter` user row through `highest`. Only `WorkStanding` and `JdbcWorkClaims.stands` read it. |
| 4 | `JdbcWorkCatalog.java:70-78` `VISIBLE_PROJECTS_SQL` | `EXISTS` any row | Add `AND m.role <> 'reporter'` inside the `EXISTS`. |
| 5 | `JdbcWorkCatalog.java:273` `members` | every row of a project | Unchanged: viewers and up see reporter rows in the member list. |
| 6 | `WorkScopes.java:154-166` `scope` | 404 when no role | Unchanged; a reporter now gets 404. |
| 7 | `WorkScopes.java:168-198` `itemScope`, `itemScopeById`, `commentScope` | through `scope` | Unchanged: a reporter's own item is 404 here too. |
| 8 | `WorkScopes.java:220-224` `isMember` → `ItemTargets.java:102`, `WorkService.java:136,461,475` | assignee and mention checks | Unchanged: a reporter is neither assignable nor mentionable (P8-6). |
| 9 | 19 calls of `require( Action.VIEW, … )` (`WorkService`, `Acknowledgements:45`, `WorkClaimsService:118`, `WorkChangesService`, `WorkDeploymentsService`) | 403 for a role below `VIEWER` | Unreachable for a reporter (row 6 throws first). A `REPORTER` scope exists only from the two new resolvers; `ItemViews.view` refuses one (Task 8.2a). |
| 10 | `WorkService.java:150-164` `listProjects`, `listProjectsWithRoles` (→ `assignmentLog:444-448`, `GetWorkContextTool.java:67-71`, `WorkResourceBase.java:309-315`, `WorkMeResource.java:43`) | visible projects with roles | Unchanged. |
| 11 | `WorkService.java:235-239` `roleIn(actor, key)` (→ `WorkResourceBase.java:318-321`, `WorkProjectResource.java:46,48`) | the caller's role | Unchanged: 404 for a reporter. |
| 12 | `WorkService.java:247-256` `listProducts`; `WorkDeploymentsService.java:287-293` `visibleProduct`; `WorkRegistryService.java:409-411` `canSeeRegistry` | "in at least one project" | Unchanged: a person who is only a reporter sees no products, no deployments by product, no agents or queues. |
| 13 | `WorkService.java:514-540` `waitingOn`; `Acknowledgements.java:60-71` `awaiting` | visible projects; lead check | Unchanged. |
| 14 | `AgentSponsorship.java:64-71` `leadsAnyProject`, `:90-96` `sharedWith`, `:159` takeover | lead / visible | Unchanged. |
| 15 | `AgentSponsorship.java:178-184` `standingIn` | `role == VIEWER ? SPONSOR_VIEWER : EFFECTIVE` | Unchanged code; would have reported a reporter sponsor as `EFFECTIVE`. Now empty → `SPONSOR_NOT_MEMBER`. Pinned by a test. |
| 16 | `WorkClaimsService.java:113-129` `claimableProjects`, `:146-155` `myClaims` | visible; agent needs `MEMBER` | Unchanged. |
| 17 | `SponsorSweep.java:96-97` | sponsor `atLeast(MEMBER)` | Unchanged; a sponsor demoted to reporter loses their agents' claims. Pinned. |
| 18 | `DeploymentAccess.java:55-62` | `role.isPresent()` → item visible | Unchanged code; would have shown a reporter every deployment carrying any item of the project. Pinned. |
| 19 | `JdbcWorkClaims.java:114-120` `SPONSOR_LOCK_SQL` (`role IN ('member','lead')`), `MEMBER_LOCK_SQL` (`role = 'member'`), `:384-387` `stands` (`atLeast(MEMBER)`) | claim locks | Unchanged: they name roles explicitly. |
| 20 | `JdbcWorkMembers.java:45-77` `put` | upsert, release on demotion | Refuses an agent `REPORTER` through `requireAgentRole` (row 21); `:72` unchanged. |
| 21 | `WorkInputs.java:80-84` `requireAgentRole` | refuses `LEAD` | Also refuses `REPORTER`, same message. |
| 22 | `JdbcWorkRegistry.java:160,168` | agent rows only | Unchanged. |
| 23 | `JdbcWorkEventLog.java:71-101` (assignment log) | `e.project_id = ANY(?)` from row 10 | Unchanged: no `work_member` join. |
| 24 | `WorkPolicy.java:46-74` `may` | `roleOrNull.atLeast(needed)` | `REPORTER` is below every existing `minRole`, so every existing action refuses it; add the `FILE_ITEM` rule (Task 8.1c). |
| 25 | `WorkPolicy.java:96-99` `startStatus` | person's story/task `ready` | Intake overload always `draft` (Task 8.1c). |
| 26 | `WorkProjectResource.java:141-144` `optRole` | parses role | Unchanged; accepts `reporter` once the enum has it. |
| 27 | `wikantik-work-mcp`: only `GetWorkContextTool.java:67-71` reads a role | lists visible projects | Unchanged. No tool reaches a reporter path: a person's `work` key on `/work-mcp` gets a reporter `Not found.` everywhere (P8-14). |
| 28 | `wikantik-main/.../work/runtime`: `WorkClaimSweeper.java:106` (`sweepStanding`) | sponsor sweep | Unchanged. No other runtime class decides standing. |
| 29 | Part 3 dispatcher: drop of recipients whose `roleIn` is empty | would drop the reporter from their own item | `canSee` plus the reporter event filter (Task 8.2c). |
| 30 | Part 3 inbox and unread statements in `JdbcWorkNotifications` | standing applied in SQL | Admit a row of a reporter-only project only for an item the reader reported and an event type the reporter sees (Task 8.2c). |
| 31 | Part 3 `sendEmail` per-row standing re-check | `roleIn` | Same predicate as 29. |
| 32 | Part 3 `watch`/`mute`/`unwatch`/`markRead`; Part 7 upload/list/content/remove | `itemScope` | `ownItemScope` (Task 8.2c). |
| 33 | Parts 1, 2, 4, 5, 6 and Part 3 webhooks | `scope`, `itemScope`, `visibleProjects` | Unchanged; each driven as a reporter in Task 8.2c. |
| 34 | `WorkStanding.seesProject` (Part 3: `NoticePlanning`, `EmailSender`, `SavedFilters`) | `admin \|\| roleIn(...).filter(atLeast(VIEWER))` | Unchanged: false for a reporter. Pinned by `aReporterHasNoRoleAndSeesNoProject`. |
| 35 | `WorkScopes.Scope.effectiveRole` | admin → `LEAD`, else raw | Reachable with `REPORTER` only from `intakeScope` / `ownItemScope`; `ItemViews.view` refuses a reporter scope (Task 8.2a). |
| 36 | `WorkStanding.canSee`, `mayFile`, `reportsOnly`, `reportableProjects`, `outsideReporters` | new | Persons only (ruling 5): an agent, however named or sponsored, gets nothing; nothing else calls them (`WorkStandingSourceRuleTest`). |
| 37 | `WorkRole` order users: `atLeast`, `highest`; no `ordinal()`, `values()` or other `compareTo` on roles in the four modules | order | `REPORTER` declared first is lowest; pinned by `reporterSortsBelowViewer` (ruling 15). |
| 38 | SQL on role values: `ROLES_FOR_SQL`, `VISIBLE_PROJECTS_SQL`, `SPONSOR_LOCK_SQL ('member','lead')`, `MEMBER_LOCK_SQL 'member'`, dispatch `LEADS 'lead'`, `JdbcWorkMembers:72`, `JdbcWorkEventLog` (`type <> 'member'`) | name roles explicitly | Only `VISIBLE_PROJECTS_SQL` needed an edit (row 4); none uses `<> 'viewer'`, `NOT IN` or `!=`. |
| 39 | Part 4 `find()` (`find`, `saved`, `save`, `rename`, `delete`), `labelsOf`, Part 2 `sprints()`, Part 6 `flow()` (`project`, `dora`, `doraForProduct`) | `visibleProjects` / `scope` | Unchanged: empty / not found for a reporter; `doraForProduct` resolves its product through visible projects, never a raw role. Driven in 8.2b/8.2c. |
| 40 | Part 3 `notifications().watchState` and `markAllRead` | `itemScope`; `visibleIds` only | `ownItemScope`; `markAllRead` passes the reporter-only project ids too (ruling 4, Task 8.2c). |
| 41 | Part 3 `NoticePlanning.mayWatch` and the `standing` / `visibleRows.sees` caches | `seesProject` keyed per project | Item-aware for a reporter-only person, cache keyed by project/login/item (ruling 2, Task 8.2c). |
| 42 | `NoticeView.event` in the inbox | carries `note`, `agentId` whole | Stripped for a reporter-only project (ruling 1, Task 8.2c). |
| 43 | Part 7 attachments `list`, `listing`, `content`, `upload`, `mayAttach`, `remove` | `require(VIEW/ATTACH)` | `ownItemScope`, policy skipped when `reporterOnly()` (ruling 3, Task 8.2c). |
| 44 | REST `outsideReporters` marker on `GET items/{k}`, `PATCH items/{k}`, `GET items?project=`, `GET projects/{k}/board` | bounded by the logins in the response (ruling 9) | Single-project shapes only; the cross-project find shape carries no marker (Task 8.3a). |
| 45 | `WorkProjectResource` members JSON; `WorkItemForm`, `WorkHandoff`, `WorkComments`, `WorkFind` pickers | viewers+ see reporter rows | Pickers filter `role !== 'reporter'` (ruling 11, Task 8.3b). |
| 46 | `WikiWorkRenderer` (item body and comment HTML), `work-attachment:` tokens, comment mentions | text only, links as typed | No lookup, so no other item's title is revealed; a reporter's mention stores nothing (Task 8.2a). |

Frontend role sites: `WorkItemView.jsx:181,193,307-308`, `WorkBoard.jsx:17`, `WorkAgents.jsx:147` are unchanged (a reporter never gets a project or full item). `WorkSettings.jsx:13,25,188-197,295` change in Task 8.3b.

## Decisions

- **P8-1. Fail-closed standing.** `WorkStanding.roleIn` and `visibleProjects` answer "viewer or above"; the reporter row is visible only to three new questions. *Why:* the audit found three sites (rows 15, 18 and the agent ceiling in row 1) where "has a role" silently granted reads; a missed or future site defaults to "stranger". *If wrong:* a caller that needs the raw role must ask `WorkStanding` for it; nothing else changes.
- **P8-2. A reporter has their own routes.** `intake()` and `/api/work/intake/*`; the full item, comment, event, board and activity routes answer a reporter 404, even for their own item. Parts 3 and 7 open only the actions the design names (watch, inbox, attachments). *Why:* every full path stays strict without branching on role. *If wrong:* the UI needs two fetches for a reporter (Task 8.3b handles it).
- **P8-3. `ReportedItemView` holds no `Item`.** Fields: key, project key and name, level, type, status, title, body and its HTML, reporter, created and updated times, comments, events, archived. Events are `created`, `status`, `commented`, `attached`, with `note` removed. *Why:* REST serialises records whole. *If wrong:* adding a field is one component and one shape-test line.
- **P8-4. Anyone with a role may file through intake; agents never.** Viewers, members and leads may use `/work/submit` too; the item always starts `draft` and unassigned. New `Action.FILE_ITEM` (min role `REPORTER`), refused to agents by `WorkPolicy.may`. *Why:* role order is privilege order; the design says intake items start draft "whoever the reporter is".
- **P8-5. Level defaults to `story`; `epic` is refused** (field `level`, "Intake files a story or a task.").
- **P8-6. A reporter is neither assignable nor mentionable, and their comments mention nobody.** `isMember` stays viewer-or-above; a reporter's comment is stored through `WorkPeople.mentions` with no member accepted. *Why:* assignment or a mention would point them at work they cannot open, and a mention probe would reveal who is a member. The reporter still hears about their item as its watcher.
- **P8-7. What a reporter may do on their own item:** read it, add comments (not edit or remove them), attach files, remove their own uploads, watch, mute, mark read. Not edit the item. *If wrong:* comment editing is one more `ownItemScope` call.
- **P8-8. "Reported from outside" is computed when read** from the reporter's current standing (holds only the reporter role now); nothing is stored. *Why:* the notice says the reporter "can read this discussion", which is only true while they hold the role. A project with no reporter rows pays one indexed `EXISTS`.
- **P8-9. A recipient who is only a reporter hears only the four reporter event types**, on their own items. Hand-offs, changes, deployments and claim expiries are dropped.
- **P8-10. "Reported by me" splits by standing.** Part 4's find view keeps covering viewer-or-above projects; `intake().reported` covers projects where the caller is only a reporter, in reduced form. No item appears in both. Find never returns a reporter-only project's items (an `ItemSummary` carries assignee, parent and labels).
- **P8-11. `SUBMIT` becomes a reserved project key** (route `/work/submit`). An existing project keyed `SUBMIT` keeps its API; its board URL is shadowed (stated in the release notes).
- **P8-12. The database refuses an agent `reporter` (and `lead`) row** with a CHECK, besides the service and store refusals.
- **P8-13. One URL per item.** `WorkItemView` asks for the reporter view when the full view is 404, so emails, the inbox and lists can all link `/work/item/KEY`.
- **P8-14. No MCP tool for intake.** The design keeps agents out of intake; a person's `work` key on `/work-mcp` is a stranger to a reporter-only project.
- **P8-15. Files chosen on the submit form upload after the item is filed** (an upload needs an item); a failed upload leaves the item filed and shows the error on its page.
- **P8-16. A reporter learns a project's key and name only** (never its description or settings): `/api/work/me` `reportable` and `ReportedItemView` carry just those.

## Review Focus

1. **A reporter reaching anything but their own items** through a site still treating "has a role" as "may see": board, dates, activity, log, find, saved filters, labels, sprints, flow, DORA, changes, deployments, products, agents, webhooks, another item's attachments, the parent, links. Tasks 8.1b (standing), 8.2b (every read as three kinds of reporter) and 8.2c (Parts 1–7 surfaces).
2. **A field of the full item in a reporter's response** (assignee, parent, product, queue, claim, priority, labels, sprint, estimate, notes). Task 8.2a (record shape) and Task 8.3a (exact JSON key set).
3. **An agent sponsored by a person who is only a reporter**, or a sponsor demoted to reporter: it must have nothing and lose its claims. Task 8.1b.
4. **A notification or email to a reporter about a change, deployment, hand-off or link**, or one that survives their removal. Task 8.2c.
5. **A group holding the reporter role**: its members file and see their own items; a person with a reporter group row and a viewer user row sees the project normally. Task 8.1b.
6. **A reporter's item that starts `ready` or assigned** and so lands in a queue an agent can claim. Task 8.2a.

---

### Task 8.1a: Schema V091 and the role

**Files:**
- Create: `bin/db/migrations/V091__work_reporter_role.sql`
- Create (test): `wikantik-work/src/test/java/com/wikantik/work/WorkReporterSchemaTest.java`
- Modify: `wikantik-work/src/main/java/com/wikantik/work/WorkRole.java`, `WorkInputs.java` (`requireAgentRole`, `RESERVED_KEYS`)
- Test: `WorkInputEdgesTest.java`, `JdbcWorkStoreProjectsPostgresTest.java`

**Interfaces — produces:**
```sql
-- V091: work management intake. A fourth project role, 'reporter', below 'viewer': a person or a group who files
-- items into a project and sees only the items they reported. An agent's role is viewer or member, never reporter
-- or lead: the service and the store refuse it, and the CHECK makes it structural. Requires V074.
-- Idempotent: constraints are dropped if present and re-added; the index is IF NOT EXISTS.
ALTER TABLE work_member DROP CONSTRAINT IF EXISTS work_member_role_check;
ALTER TABLE work_member DROP CONSTRAINT IF EXISTS work_member_role_chk;
ALTER TABLE work_member ADD CONSTRAINT work_member_role_chk
    CHECK (role IN ('reporter', 'viewer', 'member', 'lead'));
ALTER TABLE work_member DROP CONSTRAINT IF EXISTS work_member_agent_role_chk;
ALTER TABLE work_member ADD CONSTRAINT work_member_agent_role_chk
    CHECK (principal_kind <> 'agent' OR role IN ('viewer', 'member'));
-- "does this project have reporters at all": the outside-reporter read exits on this before touching items.
CREATE INDEX IF NOT EXISTS idx_work_member_reporters ON work_member (project_id) WHERE role = 'reporter';
```
- `WorkRole`: `REPORTER("reporter")` declared **first**; Javadoc: "A reporter files items and sees only the ones they reported; it gives no standing in the project (see `WorkStanding`)."
- `WorkInputs.requireAgentRole`: refuses `LEAD` and `REPORTER`, message unchanged.
- `WorkInputs.RESERVED_KEYS` gains `"SUBMIT"`.

- [ ] **Step 1: Failing tests.** `WorkReporterSchemaTest` (modelled on `WorkProductEventsSchemaTest`): `aUserOrGroupMayHoldTheReporterRole`; `anAgentRowMayNotBeAReporterOrALead` (SQLState `23514` for both); `anUnknownRoleIsRefused` (`'owner'` → `23514`); `theOldUnnamedCheckIsGone` (`SELECT count(*) FROM pg_constraint WHERE conname = 'work_member_role_check'` is 0, `work_member_role_chk` is 1); `theReporterIndexExists`; `v091IsANoOpWhenReapplied` (exec the file twice). `WorkInputEdgesTest`: `anAgentRoleIsViewerOrMember` (`REPORTER` and `LEAD` refused with field `role`); `submitIsAReservedProjectKey` (`submit`, `SUBMIT`). `JdbcWorkStoreProjectsPostgresTest`: `theStoreRefusesAnAgentReporter` (`store.putMember(p, "<agentId>", "agent", REPORTER, ROOT)` → `WorkValidationException` field `role`, no row, no event); `aReporterRowRoundTrips` (`members(p)` returns it with `WorkRole.REPORTER`).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkReporterSchemaTest,WorkInputEdgesTest,JdbcWorkStoreProjectsPostgresTest'`. Expected: compilation failure (`REPORTER`), then schema failures.
- [ ] **Step 3:** Write the migration, the enum constant, the two `WorkInputs` changes.
- [ ] **Step 4:** Re-run: PASS. `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-work-mcp`.
- [ ] **Step 5: Commit** `git add bin/db/migrations/V091__work_reporter_role.sql wikantik-work/src/main/java/com/wikantik/work/WorkRole.java wikantik-work/src/main/java/com/wikantik/work/WorkInputs.java wikantik-work/src/test/java/com/wikantik/work/WorkReporterSchemaTest.java wikantik-work/src/test/java/com/wikantik/work/WorkInputEdgesTest.java wikantik-work/src/test/java/com/wikantik/work/JdbcWorkStoreProjectsPostgresTest.java` — `feat(work): reporter role (V091), never an agent's`.

### Task 8.1b: Standing stays viewer-or-above; the three reporter questions

**Files:**
- Modify: `WorkStanding.java`, `WorkCatalogStore.java`, `JdbcWorkCatalog.java`
- Test: `WorkStandingPostgresTest.java`, `WorkStandingSourceRuleTest.java`, `JdbcWorkClaimsSponsorPostgresTest.java`, `WorkSponsorStandingPostgresTest.java`, `WorkDeploymentAccessPostgresTest.java`

**Interfaces — produces:**
- `WorkCatalogStore`: `List<Project> reportableProjects(Actor actor)` (not archived; any `user` or `group` row of the person, reporter included; every non-archived project for a wiki admin; `List.of()` for any other kind, without a query) and `List<String> reporterCandidates(long projectId)`:
```sql
SELECT DISTINCT i.reporter FROM work_item i
 WHERE i.project_id = ? AND i.reporter_agent_id IS NULL
   AND EXISTS ( SELECT 1 FROM work_member r WHERE r.project_id = i.project_id AND r.role = 'reporter' )
   AND NOT EXISTS ( SELECT 1 FROM work_member m WHERE m.project_id = i.project_id
                    AND m.principal_kind = 'user' AND m.principal = i.reporter AND m.role <> 'reporter' )
```
- `VISIBLE_PROJECTS_SQL`: `AND m.role <> 'reporter'` inside its `EXISTS`.
- `WorkStanding`:
  - `Optional<WorkRole> roleIn(long, Actor)`: as today, but the person's role, the agent's own role and the sponsor ceiling are each filtered with `r -> r.atLeast( WorkRole.VIEWER )` first. Javadoc: "viewer or above; a reporter row is no standing."
  - `boolean mayFile(long projectId, Actor actor)`: a person (`Kind.HUMAN`) who is a wiki admin or has any raw role.
  - `boolean canSee(Actor actor, Item item)`: admin; or `roleIn(item.projectId(), actor)` present; or a person with a raw role in the project who reported it (`item.reporterAgentId() == null && actor.login().equals( item.reporter() )`).
  - `boolean reportsOnly(long projectId, Actor person)`: a person, not admin, whose raw role is exactly `REPORTER`.
  - `List<Project> reportableProjects(Actor)`: `store.reportableProjects`.
  - `Set<String> outsideReporters(long projectId, Function<String, Optional<WorkDirectory.Person>> people)`: of `store.reporterCandidates(projectId)`, the logins whose person resolves and `reportsOnly` (built as `Actor.human(p.login(), p.groups(), p.admin())`). An unresolved login is not outside.
- `WorkStandingSourceRuleTest`: walk the package tree (`Files.walk`, so Part 5's `codehost/` is scanned) and also forbid `store.reportableProjects(` and `store.reporterCandidates(` outside `WorkStanding`.

- [ ] **Step 1: Failing tests.** In `WorkStandingPostgresTest`:
  - `aReporterHasNoRoleAndSeesNoProject` (`roleIn` empty; absent from `visibleProjects`; present in `reportableProjects`).
  - `aReporterCanSeeOnlyTheirOwnItem` (`canSee` true for an item reported as ana; false for one reported by bob; false for one an agent named `ana` reported, `reporterAgentId` set).
  - `aRemovedReporterSeesNothing` (after `removeMember`, `canSee` and `mayFile` false).
  - `aGroupMayHoldTheReporterRole` (`ana("Customers")` with a `Customers` group row: `mayFile` and `canSee` own; `roleIn` empty).
  - `aViewerRowBeatsAReporterRow` (user row `reporter`, group row `viewer` → `roleIn` is `VIEWER`, project visible, `reportsOnly` false).
  - `anAgentSponsoredByAReporterHasNothing` (agent row `MEMBER`, sponsor row `REPORTER` → `roleIn` empty, `visibleProjects` empty, `canSee` false for an item the sponsor reported).
  - `anAgentNeverFiles` (`mayFile` false for an agent with an admin sponsor and a member row).
  - `outsideReportersNamesOnlyThoseHoldingOnlyTheReporterRole` (rita reported and is reporter → `{rita}`; promoted to viewer → `{}`; removed → `{}`; a project with no reporter rows → `{}`).
  - `WorkSponsorStandingPostgresTest`: `aReporterSponsorIsNotAMember` (`registry().agentStandings` / `standingIn` reports `SPONSOR_NOT_MEMBER`). `JdbcWorkClaimsSponsorPostgresTest`: `demotingTheSponsorToReporterReleasesTheAgentsClaims` (one `released` event, note `sponsor access removed`). `WorkDeploymentAccessPostgresTest`: `aReporterDoesNotSeeADeploymentCarryingTheirProjectsItems` (`deployments().deployment(rita, id)` → not found, also when the deployment carries rita's own item).
  - `WorkStandingSourceRuleTest`: run as is first (it passes); add the two strings and the walk; it still passes once only `WorkStanding` calls them.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkStandingPostgresTest,WorkSponsorStandingPostgresTest,JdbcWorkClaimsSponsorPostgresTest,WorkDeploymentAccessPostgresTest,WorkStandingSourceRuleTest'`. Expected: compile failure, then `aReporterHasNoRole…`, `anAgentSponsoredByAReporter…` (today it returns `REPORTER`), `aReporterSponsorIsNotAMember` (today `EFFECTIVE`) and the deployment test fail.
- [ ] **Step 3: Audit after Parts 1–7.** Run `grep -rn -a --include=*.java -E 'roleIn\(|visibleProjects\(|WorkRole\.|\.atLeast\(|isMember\(|work_member' wikantik-work/src/main wikantik-rest/src/main wikantik-work-mcp/src/main wikantik-main/src/main/java/com/wikantik/work`. Every hit must be a row of the audit table or be classified now in the same way (a site that should admit a reporter's own item is moved to Task 8.2c's list; everything else stays as it is). Write the new rows into the table in this plan file.
- [ ] **Step 4:** Implement the store methods, the SQL change and `WorkStanding`.
- [ ] **Step 5:** Re-run: PASS. Then the whole module: `mvn test -pl wikantik-work` (no existing test may change).
- [ ] **Step 6: Commit** the five test files plus `WorkStanding.java`, `WorkCatalogStore.java`, `JdbcWorkCatalog.java` and this plan file by name — `feat(work): a reporter row gives no standing; mayFile, canSee and reportsOnly`.

### Task 8.1c: Policy: filing, the draft start and the golden table

**Files:**
- Modify: `Action.java`, `WorkPolicy.java`
- Test: `WorkPolicyTest.java`, `WorkPolicyActionTableTest.java`, `src/test/resources/com/wikantik/work/policy-action-table.golden`

**Interfaces — produces:**
- `Action.FILE_ITEM( WorkRole.REPORTER, false, false )`, appended last (constant order is stable).
- `WorkPolicy.may`: before the admin check, `if ( action == Action.FILE_ITEM && actor.kind() != Actor.Kind.HUMAN ) return Decision.deny( "A person files intake; an agent creates items in the projects it belongs to." );`
- `static WorkStatus startStatus( Actor actor, WorkLevel level, boolean intake )`: `intake` → `DRAFT`; else as today. The two-argument form delegates with `false`.

- [ ] **Step 1: Failing tests.** `WorkPolicyTest`: `aReporterMayOnlyFile` (for every `Action` other than `FILE_ITEM`, `may(alice, REPORTER, a, null)` is refused, and the reason is "This action needs the <role> role; you hold reporter." where the action has a role); `filingTakesAPersonWithAnyRole` (human with each of the four roles allowed; `null` role refused; an agent with an admin sponsor and role `MEMBER` refused with the text above); `anIntakeItemStartsDraftWhoeverFilesIt` (human member, human reporter, admin; story and task). `WorkPolicyActionTableTest`: add `WorkRole.REPORTER` to the `roles` array after `null`.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkPolicyTest,WorkPolicyActionTableTest'`. Expected: compile failure, then the golden mismatch.
- [ ] **Step 3:** Implement. Regenerate the golden: in `decisionTableMatchesGolden`, before the assertion, write `table()` to `target/policy-action-table.actual` (one line each, `\n`). Run once, then `diff wikantik-work/src/test/resources/com/wikantik/work/policy-action-table.golden wikantik-work/target/policy-action-table.actual | grep '^<'` must print nothing (no existing decision changed); every `>` line has role `reporter` or action `FILE_ITEM`. Copy the actual file over the golden.
- [ ] **Step 4:** Re-run: PASS.
- [ ] **Step 5: Commit** `Action.java`, `WorkPolicy.java`, the two tests and the golden — `feat(work): filing intake is a person's act; intake items start draft`.

### Task 8.2a: Scopes, the reporter's view and the intake facade

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/WorkIntakeService.java`, `IntakeDraft.java`, `ReportedItemView.java`, `ReportedItem.java`
- Modify: `WorkScopes.java`, `ItemViews.java`, `WorkService.java` (`intake()`), `WorkStore.java` + `JdbcWorkDiscussion.java` + `JdbcWorkStore.java` (`reportedBy`)
- Test: `WorkReporterPostgresTest.java` (new), `ReportedItemViewTest.java` (new), `JdbcWorkStoreDiscussionPostgresTest.java`

**Interfaces — produces:**
- `WorkScopes.Scope.reporterOnly() : boolean` — `role == WorkRole.REPORTER && !actor.instanceAdmin()`.
- `WorkScopes.intakeScope( Actor, String projectKey ) : Scope` — project by key (unknown → 404); `roleIn` present or admin → that scope; else `standing.mayFile` → scope with role `REPORTER`; else 404.
- `WorkScopes.ownItemScope( Actor, String itemKey ) : ItemScope` — key pattern, project, item in that project (any miss → 404); `roleIn` present or admin → the ordinary scope; else `standing.canSee( actor, item )` → scope with role `REPORTER`; else 404. Javadoc: "The only way a reporter reaches an item. Never pass its scope to `ItemViews.view`."
- `ItemViews.view`: first line `if ( s.reporterOnly() ) throw new IllegalStateException( "A reporter is shown ReportedItemView, never the full view." );`
- `public record IntakeDraft( String projectKey, WorkLevel level, WorkType type, String title, String body )`.
- `public record ReportedItemView( String key, String projectKey, String projectName, WorkLevel level, WorkType type, WorkStatus status, String title, String body, String bodyHtml, String reporter, Instant createdAt, Instant updatedAt, List< CommentView > comments, List< WorkEvent > events, boolean archived )` with `public static final Set< String > EVENT_TYPES = Set.of( WorkEvent.CREATED, WorkEvent.STATUS, WorkEvent.COMMENTED, WorkEvent.ATTACHED );`.
- `public record ReportedItem( String key, String projectKey, String projectName, WorkLevel level, WorkType type, WorkStatus status, String title, Instant createdAt, Instant updatedAt, int commentCount )`.
- `WorkStore.reportedBy( String login, Set< Long > projectIds, int limit ) : List< Item >` — `reporter = ? AND reporter_agent_id IS NULL AND project_id = ANY( ? ) ORDER BY updated_at DESC, id DESC LIMIT ?`.
- `WorkService.features().intake() : WorkIntakeService` (constructed with `store, scopes, views, people, applier, renderer`).
- `WorkIntakeService`:
  - `List< WorkService.ProjectRole > reportableProjects( Actor )` — `standing.reportableProjects`, each with `roleIn` (effective) or `REPORTER`.
  - `ReportedItemView submit( Actor, IntakeDraft )` — `intakeScope`; `require( Action.FILE_ITEM, null )`; `requireWritable()`; level null → `STORY`, `EPIC` → `WorkValidationException( "level", "Intake files a story or a task." )`; type null → `FEATURE`; build the `ItemDraft` (as Part 1 left its constructor) with project key, level, type, title, body and every other field null or empty; `applier.forCreate( project, draft, WorkPolicy.startStatus( actor, level, true ) )`; `store.createItem`; return `reportedView`.
  - `ReportedItemView item( Actor, String key )` — `ownItemScope`; `reportedView`.
  - `List< ReportedItem > reported( Actor )` — people only (agents `List.of()`); projects from `reportableProjects` where `reportsOnly`; `store.reportedBy( login, ids, 200 )`; comment counts from `store.itemExtras` per project.
  - `CommentView comment( Actor, String key, String body )` — `ownItemScope`; not `reporterOnly` → `require( Action.COMMENT, item )`; `requireWritable()`; `WorkInputs.requireBody`; `people.mentions( actor, body )`; members accepted: `reporterOnly` → none (`login -> false`), else `scopes.isMember`; `store.addComment`.
  - `Set< String > outsideReporters( Actor, String projectKey )` — `scope( actor, projectKey )`, `require( Action.VIEW, null )`, `standing.outsideReporters( id, scopes::person )`.
  - private `reportedView( Scope, Item )`: comments through `views.comment`; events `EventKeys.resolve( store, projectId, store.itemEvents( id ) )` filtered to `EVENT_TYPES`, each rebuilt with `note` null.

- [ ] **Step 1: Failing tests.** `ReportedItemViewTest`: `carriesOnlyTheAllowedComponents` (record component names equal the 15 above, and none has type `Item`, `Link`, `Change`, `Deployment` or a collection of them). `JdbcWorkStoreDiscussionPostgresTest`: `reportedByIgnoresItemsAnAgentOfTheSameNameReported`. `WorkReporterPostgresTest` (fixture: project `INT` with lena lead, alice member, rita reporter, a `Customers` group reporter; `WorkService` as in `WorkServiceVisibilityPostgresTest`):
  - `aReporterFilesADraftUnassignedStory` (status `draft`, assignee and queue null, level `story`, type as given, reporter rita, one `created` event).
  - `aMemberFilingThroughIntakeAlsoGetsADraft`.
  - `anEpicCannotBeFiled`; `anAgentCannotFile` (`WorkDeniedException` with the policy text for a member agent; not found for an agent with no row).
  - `anArchivedProjectRefusesIntake` (submit and comment → `WorkConflictException` reason `archived`).
  - `aReporterSeesTheirOwnItemAndNothingElse` (`intake().item(rita, own)` works; on alice's item and on gus's report → not found; `service.item(rita, own)` → not found).
  - `theReportersViewCarriesOnlyTheirEvents` (after alice assigns it, links it, sets priority, moves it to `ready` with a note and comments: event types are `created`, `status`, `commented`; every `note` is null).
  - `aReporterCommentsAndMentionsNobody` (rita writes `@alice please look`: the comment is stored, no mention row for alice).
  - `aReporterCannotEditTheirOwnItemOrCommentThroughTheFullRoutes` (`updateItem`, `addComment`, `addLink`, `events`, `revisions`, `policyOf` on her item → not found).
  - `aReporterIsNeitherAssignableNorMentionable` (alice assigns rita → `WorkValidationException` field `assignee`; alice's comment `@rita` stores no mention row).
  - `aRemovedReporterLosesTheirItems` (`item` → not found, `reported` empty).
  - `reportedListsOnlyReporterOnlyProjects` (rita sees her item; alice, who also filed one through intake in INT, gets an empty list).
  - `outsideReportersFeedsTheMembersNotice` (alice gets `{rita}`; rita gets not found).
  - `aReporterScopeNeverReachesTheFullView` (calls `ItemViews.view` with a reporter scope → `IllegalStateException`).
  - `aReportersItemIsNeverClaimable` (an agent of a queue: `claims().claimable` excludes it; it is `draft` and unqueued).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkReporterPostgresTest,ReportedItemViewTest,JdbcWorkStoreDiscussionPostgresTest'`. Expected: compile failure.
- [ ] **Step 3:** Implement. Keep `WorkService` under the PMD limits: it gains one field and one accessor only.
- [ ] **Step 4:** Re-run: PASS. `mvn pmd:check -Pcomplexity-gate -pl wikantik-work`.
- [ ] **Step 5: Commit** the files by name — `feat(work): intake facade and the reporter's own view`.

### Task 8.2b: Every read driven as a reporter

**Files:**
- Modify (test): `wikantik-work/src/test/java/com/wikantik/work/WorkServiceVisibilityPostgresTest.java`

**Interfaces — produces (test only):**
- Fixture: `rita = Actor.human( "rita", Set.of(), false )` with a `user` `REPORTER` row in PLAT and her own item (created by `store.createItem( plat, …, rita )`, i.e. `PLAT-4`); `gus = Actor.human( "gus", Set.of( "Customers" ), false )` with a `group` `Customers` `REPORTER` row and his own item; `codexUnderAReporter = Actor.agent( codex, "codex@docker2", rita )`.
- `strangers()` gains `rita`, `gus`, `codexUnderAReporter`.
- `FACADES` gains `"intake"`. `READS` gains `"outsideReporters"` (`"item"` is already there). `WRITES` gains `"submit"`, `"comment"`. New set `INTAKE_OWN = Set.of( "reportableProjects", "reported" )`, added to the classification union and the disjointness checks.

- [ ] **Step 1: Write the changes** and run `mvn test -pl wikantik-work -Dtest=WorkServiceVisibilityPostgresTest`. Expected before Task 8.1b would have been: `members`, deployments and others leaking to rita. Now expected: PASS for the existing reads (Task 8.1b is in), and FAIL on `everyPublicMethodIsClassifiedAsReadOrWrite` until the sets are updated; fix the sets.
- [ ] **Step 2: Add** `intakeOwnReadsAnswerOnlyTheCaller`: rita → `reportableProjects` is `[PLAT]` and `reported` is `[PLAT-4]`; gus → `[PLAT]` and his own; mallory and every agent stranger → both empty; lena → `[PLAT]` and `[]`. Extend `everyProjectScopedWriteHidesInvisibleProject`: as rita and gus, `createItem` (PLAT), `updateItem( "PLAT-4" )`, `addComment( "PLAT-4" )`, `addLink( "PLAT-4" )`, `editComment`, `removeComment` → not found; `intake().comment( rita, "PLAT-1", … )` → not found; `intake().submit` as mallory → not found.
- [ ] **Step 3:** Re-run: PASS. Any read that leaks is fixed in the service (not in the test) before moving on.
- [ ] **Step 4: Commit** `git add wikantik-work/src/test/java/com/wikantik/work/WorkServiceVisibilityPostgresTest.java` — `test(work): every read driven as a reporter, a group reporter and an agent under one`.

### Task 8.2c: The surfaces Parts 1–7 added

**Files:**
- Modify: Part 3's `WorkNotificationsService.java` (dispatch, watch, mute, unwatch, markRead, sendEmail) and `JdbcWorkNotifications.java` (inbox and unread statements); Part 7's `WorkAttachmentsService.java` (upload, list, content, remove)
- Create (test): `wikantik-work/src/test/java/com/wikantik/work/WorkReporterSurfacesPostgresTest.java`

**Interfaces — produces:**
- A recipient is kept by the dispatcher, and a row by `sendEmail`, only when `standing.canSee( person, item )` and, if `standing.reportsOnly( projectId, person )`, the event type is in `ReportedItemView.EVENT_TYPES`. `person` is `Actor.human( p.login(), p.groups(), p.admin() )` from `WorkDirectory.person`; an unresolved person is dropped.
- Inbox and unread: the service passes, besides the visible project ids, the reporter-only project ids (`reportableProjects` filtered by `reportsOnly`) and the login; the statements' standing predicate becomes:
```sql
( n.project_id = ANY( :visible )
  OR ( n.project_id = ANY( :reportOnly ) AND i.reporter = :login AND i.reporter_agent_id IS NULL
       AND e.type = ANY( :reporterTypes ) ) )
```
  joined to `work_item i ON i.id = n.item_id` and `work_event e ON e.id = n.event_id` (add the joins where the statement lacks them).
- `watch`, `mute`, `unwatch`, `markRead`: `scopes.ownItemScope` in place of `scopes.itemScope`.
- Attachments: `ownItemScope`; upload: `reporterOnly` → no `require( Action.ATTACH, … )` (the scope already proved it is their own item; Part 7's comment-belongs-to-item check stays); list and content: as built; remove: `reporterOnly` → only the uploader (`uploaded_by` equals the login and `uploaded_by_agent_id` is null), else refuse "Only the uploader may remove this file."

- [ ] **Step 1: Failing tests** in `WorkReporterSurfacesPostgresTest` (fixture as in 8.2a, plus a product, an epic parent, a sprint, a shared saved filter and a webhook in INT; a change and a verified deployment carrying rita's item):
  - Notifications: `theReporterHearsOfStatusAndCommentsOnTheirItem` (a dispatch after alice comments and moves it: rows for both); `theReporterDoesNotHearOfHandoffsChangesOrDeployments` (after a hand-off, an opened and merged change and a `shipped` event: no rows); `aRemovedReporterSeesNoOldRowsAndGetsNoNewOnes` (`inbox` and `unread` drop to 0; the next dispatch writes none); `emailForAReporterCarriesOnlyTheirEvents` (recording mailer); `aReporterWatchesAndMarksReadOnlyTheirOwnItem` (`watch`/`mute`/`markRead` on their item work; on alice's item → not found).
  - Attachments: `aReporterAttachesToAndReadsTheirOwnItem` (upload, list including alice's file on it, content); `aSiblingsAttachmentIsNotFound` (list and content of alice's item, and alice's attachment id through rita's item URL → not found); `aReporterRemovesOnlyTheirOwnUpload` (own → removed; alice's → `WorkDeniedException`).
  - Find and saved filters: `findReturnsNothingToAReporter` (`find` with no filter, with `projectIds = {INT}`, with `reporter = "me"` → empty); `aSharedFilterIsNotListedToAReporter` (`saved( rita )` excludes it; running its filter as rita → empty).
  - Every other read as rita → not found: `labelsOf`; `sprints()` list, get, report, velocity; `flow().project`, `flow().dora`; `webhooks().list`; `changes().views` on her own item; `acknowledgeFlag` on a flagged change of her item; `deployments().shippedIn` on her item; `activity`; `members`; `board`. `waitingOn( rita )` and `assignmentLog( rita, all )` are empty; `listProducts( rita )` is empty; `registry().listAgents( rita )` is empty.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=WorkReporterSurfacesPostgresTest`. Expected: the notification, watch and attachment tests fail (Part 3 and 7 resolve through `itemScope` and `roleIn`); the "not found" reads already pass.
- [ ] **Step 3:** Implement the changes above. If Part 3 classified `inbox`/`unread` as `READS` and rita now has rows of her own in the visibility fixture, move them to `INTAKE_OWN`-style own tests rather than weaken the predicate.
- [ ] **Step 4:** Re-run with the earlier suites: `mvn test -pl wikantik-work -Dtest='WorkReporterSurfacesPostgresTest,WorkReporterPostgresTest,WorkServiceVisibilityPostgresTest,WorkDispatchPostgresTest,WorkInboxPostgresTest'`: PASS.
- [ ] **Step 5: Commit** the changed service and Jdbc files and the new test by name — `feat(work): a reporter's inbox, watch and attachments cover only their own items`.

### Task 8.3a: REST

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/WorkIntakeResource.java`, test `WorkIntakeResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`, `WorkMeResource.java`, `WorkItemResource.java`, `WorkProjectResource.java`, `WorkResourceBase.java`; tests `WorkMeResourceTest.java`, `WorkItemResourceTest.java`, `WorkProjectResourceTest.java`

**Interfaces — produces:**
- `web.xml`: servlet `WorkIntakeResource`, mapping `/api/work/intake/*`.
- Routes (relative to `/api/work/`):
  - `GET intake/projects` → `[{key, name, role}]` from `reportableProjects` (built field by field: no description or settings).
  - `POST intake/items` → 201 `ReportedItemView`. Body fields `projectKey` (or `project`), `level`, `type`, `title`, `body`. A body naming `assignee`, `priority`, `parentKey`, `productKey`, `labels`, `sprint`, `estimate`, `queue`, `dueOn`, `kind` or `status` → 400 with `field` set and message "A project member sets this after triage."; any other field → 400 "Unknown field: <name>".
  - `GET intake/items` → `[ReportedItem]`.
  - `GET intake/items/{key}` → `ReportedItemView` (404 as the service says).
  - `POST intake/items/{key}/comments` → 201 `CommentView`, body `{body}`.
- `GET /api/work/me` gains `reportable: [{key, name, role}]`.
- Item and card JSON for viewers and up gain `reportedFromOutside: true|false`: `WorkResourceBase.outsideFlags( Call, String projectKey ) : Set<String>` (one call of `intake().outsideReporters`) and `markOutside( JsonElement tree, Map<Long,Item> byId, Set<String> logins )` (sets the flag on each object whose `id` names an item with `reporterAgentId == null` and its `reporter` in `logins`). Used by `GET items/{key}`, `PATCH items/{key}`, `GET items` (summaries) and `GET projects/{key}/board`.

- [ ] **Step 1: Failing tests.** `WorkIntakeResourceTest`: `submitReturns201WithTheReportedView`; `aMembersFieldIsRefusedByName` (each of the 11 fields → 400 with that `field`); `anUnknownFieldIsRefused`; `theReportedViewHasExactlyTheseKeys` (the top-level key set equals the 15 components plus the `reporterName` twin `WorkJson` adds; no `assignee`, `parentKey`, `links`, `productKey`, `queue`, `claimedByAgent`, `priority`, `labels`, `sprint`, `estimate`, `viewerRole`); `theReporterIsShownByDisplayName`; `reportableCarriesKeyNameAndRoleOnly` (`description` absent); `notFoundIs404` for item and comment; `anAgentFilingIs403WithThePolicyText`. `WorkMeResourceTest`: `meListsReportableProjects`. `WorkItemResourceTest`: `anItemFiledFromOutsideIsMarked` and `anOrdinaryItemIsNot`. `WorkProjectResourceTest`: `boardCardsCarryTheOutsideMarker`; `aReporterRoleIsAccepted` (`PUT …/members` with role `reporter` reaches `putMember` with `WorkRole.REPORTER`).
- [ ] **Step 2:** `mvn test -pl wikantik-rest -Dtest='WorkIntakeResourceTest,WorkMeResourceTest,WorkItemResourceTest,WorkProjectResourceTest'`. Expected: compile failure.
- [ ] **Step 3:** Implement; `mvn test -pl wikantik-rest -Dtest='Work*'`: PASS (`WorkAccessArchTest` included).
- [ ] **Step 4: Commit** the files by name — `feat(work): intake REST routes and the outside-reporter marker`.

### Task 8.3b: UI

**Files:**
- Create: `wikantik-frontend/src/components/work/WorkSubmit.jsx` (+ `WorkSubmit.test.jsx`), `WorkReportedItem.jsx` (+ `WorkReportedItem.test.jsx`)
- Modify: `src/main.jsx` (route `submit` under `/work`, before `:project`), `src/api/work.js` (+ `work.test.js`), `WorkItemView.jsx` (+ test), `WorkCard.jsx` (+ test), `WorkHome.jsx` (+ test), `WorkSettings.jsx` (+ test)

**Interfaces — produces:**
- `api/work.js`: `listReportable()` → `GET intake/projects`; `submitIntake(draft)` → `POST intake/items`; `listReported()` → `GET intake/items`; `getReportedItem(key)`; `addReportedComment(key, body)`.
- `WorkSubmit`: project select from `getMe().reportable` (one project preselected), level (Story, Task), type (Feature, Bug, Chore), title, body (plain textarea), file input; on submit: `submitIntake`, then `uploadAttachment` for each file in turn, then navigate to `/work/item/<key>`; a failed upload navigates anyway and passes the error in route state. No reportable project → "You cannot file work into any project."
- `WorkReportedItem({ view, onReload })`: title, key, type, status, body (`WorkBody` with `view.bodyHtml`), a short history from `view.events` through `eventText`, `WorkComments` with `members={[]}`, `canComment={!view.archived}`, `isLead={false}`, `onAdd={(b) => addReportedComment(view.key, b)}` and no edit or remove controls, Part 7's `WorkAttachments` and Part 3's `WorkWatchButton`. No assignee, parent, links, changes, deployments, sprint or estimate.
- `WorkItemView`: when `getItem` rejects with status 404, call `getReportedItem`; on success render `WorkReportedItem`; on 404 show the existing not-found state. When `view.reportedFromOutside`, show above the comments: "Reported from outside the project: the reporter can read this discussion."
- `WorkCard`: a marker (`className="work-card-outside"`, title "Reported from outside the project") when `item.reportedFromOutside`.
- `WorkHome`: a "File an item" link to `/work/submit` when `reportable` is non-empty; a "Filed into other projects" list from `listReported()` when non-empty.
- `WorkSettings`: `ROLES = ['reporter', 'viewer', 'member', 'lead']` with the help line "Reporter: files items and sees only the ones they filed."; `AGENT_ROLES` unchanged; switching the add form to an agent turns `reporter` into `member` as it does `lead`; `SPONSOR_NOTE` reads "Removing a person, or making them a viewer or a reporter, also ends the claims of the agents they sponsor in this project."; changing your own role to `reporter` goes to `/work` like removing yourself.

- [ ] **Step 1: Failing vitest.** `WorkSubmit.test.jsx`: `files a bug and lands on its page`; `uploads chosen files after filing`; `a failed upload still lands on the item with the error`; `says so when there is no project to file into`. `WorkReportedItem.test.jsx`: `shows no assignee, links, changes or sprint`; `adding a comment calls addReportedComment`; `offers no edit or remove on comments`. `WorkItemView.test.jsx`: `falls back to the reported view on 404`; `shows the outside notice to members`. `WorkCard.test.jsx`: `marks an item reported from outside`. `WorkHome.test.jsx`: `lists items filed into other projects`. `WorkSettings.test.jsx`: `offers reporter for people and not for agents`; `demoting yourself to reporter leaves the project`. `work.test.js`: the five client functions hit their URLs.
- [ ] **Step 2:** `cd wikantik-frontend && npx vitest run src/components/work/WorkSubmit.test.jsx src/components/work/WorkReportedItem.test.jsx src/components/work/WorkItemView.test.jsx src/components/work/WorkCard.test.jsx src/components/work/WorkHome.test.jsx src/components/work/WorkSettings.test.jsx src/api/work.test.js`. Expected: failures.
- [ ] **Step 3:** Implement; re-run: PASS. `npm run lint`: clean. Use only CSS tokens already defined in `src/styles`.
- [ ] **Step 4: Commit** the files by name — `feat(work-ui): submit page, the reporter's item page and the outside marker`.

### Task 8.3c: IT, gate and docs

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkIntakeIT.java`
- Modify: `wikantik-work/CLAUDE.md`, `docs/admin/Security.md`, `docs/developer/ProjectReference.md`, `docs/superpowers/specs/2026-11-15-work-management-design.md`, `CHANGELOG.md`

- [ ] **Step 1: IT** `WorkIntakeIT.aReporterFilesSeesOnlyTheirItemAndReadsTheDiscussion` (modelled on `WorkBoardIT`): the admin creates project `INTK` over REST, adds `Alice` as member and `Bob` as reporter; Bob signs in, opens `/work/submit`, files a bug, lands on its page (no assignee or links section); `/work/INTK` shows not found; `fetch('/api/work/projects/INTK/board')` and `fetch('/api/work/items/<key>')` are 404 and `fetch('/api/work/intake/items/<key>')` is 200; Alice opens the item, sees the outside notice and comments; Bob reloads and sees the comment. Run: `bin/agent-build.sh start it-rest -- bin/run-tests.sh --module rest`, then `bin/agent-build.sh wait it-rest 540` until SUCCESS.
- [ ] **Step 2: Docs.**
  - `wikantik-work/CLAUDE.md`: a new section "## Intake (reporters)": `roleIn` and `visibleProjects` mean viewer or above, a reporter row gives no standing; a reporter reaches an item only through `WorkScopes.ownItemScope`/`intakeScope` and `WorkStanding.canSee`/`mayFile`; never give a reporter scope to `ItemViews.view`; `ReportedItemView` holds no `Item` and its events are the four types without notes; a reporter is neither assignable nor mentionable; notifications to a reporter-only person carry only those four types on their own items; "reported from outside" is computed, not stored; `SUBMIT` is reserved; V091 in the migrations line. Amend "A new item's status" (an intake item starts `draft` for everyone) and the agents line ("never `lead` or `reporter`; a CHECK refuses both").
  - `docs/admin/Security.md`: the reporter role, what it reaches (their own items: read, comment, attach, watch) and what it does not (board, other items, parent, links, changes, deployments, products, agents, find, metrics); that a reporter reads the whole discussion on their item (D12: there are no internal comments); that agents cannot hold it.
  - `docs/developer/ProjectReference.md` (work management status block): Part 8 shipped, V091, `/api/work/intake/*`, `/work/submit`.
  - `docs/superpowers/specs/2026-11-15-work-management-design.md`: the roles table gains `reporter` ("files items; sees only their own") with a pointer to the 2026-11-21 design.
  - `CHANGELOG.md` `[Unreleased]`: intake and the reporter role; the `SUBMIT` key note; before migrating, `SELECT count(*) FROM work_member WHERE principal_kind = 'agent' AND role NOT IN ('viewer','member')` must be 0 (V091's CHECK refuses otherwise).
  - No `ini/wikantik.properties` key is added and no operator action is needed beyond the migration, so `docs/admin/WikantikOperations.md` is unchanged.
- [ ] **Step 3: Gate.** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll `bin/agent-build.sh status gate` / `wait gate 540` until SUCCESS. Fix any red test in this session.
- [ ] **Step 4: Commit** `WorkIntakeIT.java` and the five docs by name — `docs(work): intake and the reporter role`.

## Deviations from the outline

- **`roleIn` no longer returns the reporter role** (outline: "`WorkStanding.roleIn` keeps returning the role", "audit every … check: each becomes `atLeast(VIEWER)` or `canSee`"). Under the outline's version every site in the audit had to be edited and any one missed (rows 15 and 18, the agent ceiling in row 1) would have leaked; here the same audit is done but a missed site fails closed (P8-1). `canSee`, `visibleProjects` (viewer or above) and `reportableProjects` are kept, as the outline named them.
- **`WorkService` item reads and comment writes do not use `canSee`** (outline 8.2). They stay strict; a reporter uses `intake()` (P8-2).
- **`WorkPolicy.startStatus` gains an `intake` flag** rather than reading the caller's role: an intake item starts `draft` for anyone who files through intake (design: "whoever the reporter is").
- **`WorkFindService` is not changed** (outline: "reported-by-me for a reporter"). An `ItemSummary` would leak assignee and parent; the reporter's list is `intake().reported` (P8-10).
- **V091 also adds a CHECK refusing agent `reporter`/`lead` rows and a partial index** (outline: replace the role CHECK only) (P8-12, P8-8).
- **Added `Action.FILE_ITEM`, `SUBMIT` as a reserved key, `ReportedItemView`/`ReportedItem`, `/api/work/intake/*`**, which the outline did not name; the outline's `WorkMeResource` `reportable` and `WorkSubmit.jsx` are kept.
- **Task 8.1 and 8.2 are split** into 8.1a–c and 8.2a–c; 8.3 into 8.3a–c.
- **The outline's `WorkRoleTest`** does not exist; enum round-trips are in `WorkInputEdgesTest`, which is extended instead.
