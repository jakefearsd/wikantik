# Work Management for Small Teams — Part 6: Flow and DORA metrics — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A project's members can read its flow (throughput, cycle time, lead time, work in progress) and its DORA figures (deployment frequency, lead time for changes, change failure rate, recovery time, rework rate, merge mix), computed at read time from the records the module already keeps, with fix-forward deployments counted as failures through `work_deployment.remediates_id`.

**Architecture:** One schema change (`remediates_id`, V089) and a new read-only facade `WorkService.features().flow()` (`WorkFlowService`) over a SELECT-only store (`WorkFlowStore` / `JdbcWorkFlow`). The SQL returns facts (one row per closed item, open item, deployment, shipped change); pure functions (`MetricWindow`, `Percentiles`, `Flow`, `Dora`) turn facts into figures and carry the table-driven tests. No table, cache or Prometheus series holds a figure.

**Tech Stack:** Java 25, PostgreSQL 18 (`Jdbc`, `PostgresTestDb`, `FaultInjectingDataSource`), JUnit 5, Mockito, React 19 + vitest, inline SVG.

**Spec:** docs/superpowers/specs/2026-11-21-work-team-features-design.md, section 6 (and D9, D10, D13); index and global constraints: docs/superpowers/plans/2026-11-21-work-team-features.md

**Depends on:**
- **Part 1** (labels and type): `work_item.type`, `work_item.labels TEXT[]`, `enum WorkType` with `fromWire(String)`, `Labels.normalise(Collection<String>) : List<String>`, and `ItemFilter` carrying `WorkType type` and `Set<String> labels`, rendered by `ItemQuery.conditions`.
- **Part 2** (sprints): `work_item.sprint_id`, `work_item.estimate NUMERIC(4,1)`, `WorkStore.sprints() : WorkSprintStore` with `byId(long) : Optional<Sprint>`, `Sprint.projectId()`, `Sprint.startsOn()`, `Sprint.endsOn()` (`LocalDate`), `ItemFilter`'s sprint field (an id form) rendered by `ItemQuery.conditions`; the routes `GET /api/work/projects/{key}/velocity` and `GET /api/work/sprints/{id}/report` and their client functions in `api/work.js`; `BurndownChart.jsx`.
- **Part 5** (code host): `work_change.first_commit_at`, `policy_flag`, `flag_acknowledged_at`; `Change.firstCommitAt()`; REST change open/update accepting `firstCommitAt` (ISO instant).
- Where a Part 1, 2 or 5 name differs from the above as built, use the built name; the SQL below names columns, which the outline fixed.

## Cross-part rulings

Added when the eight part plans were reconciled (index: `docs/superpowers/plans/2026-11-21-work-team-features.md`). **Where a ruling and the text below disagree, the ruling wins.**

- **R1 — new facades hang off `WorkService.features()`, not `WorkService`.** `WorkService` is at the PMD `TooManyMethods` limit (40). Part 1 creates `com.wikantik.work.WorkFeatures` (built in `WorkService`'s constructor, returned by the one new method `WorkService.features()`); every facade accessor this plan calls `WorkService.x()` is `WorkService.features().x()`. `WorkServiceVisibilityPostgresTest` treats `WorkFeatures`' accessors as facades (its `FACADES` set). No suppression, no baseline entry.
- **R2 — one zone.** P6-1 is amended: the zone is the work clock's (`clock.getZone()`), which Part 2 sets to the server's default zone. It is not UTC by definition; tests pin a fixed `Clock` with a non-UTC zone to prove the code reads it.
- **R4 — superseded changes.** Part 5 adds `work_change.superseded_by`. Every query in this part that reads `work_change` (lead time, the merge mix, flagged counts) carries `AND c.superseded_by IS NULL`, and `WorkDoraPostgresTest` gains `aSupersededDirectChangeIsCountedNowhere`.
- **R6 — routes.** Confirmed: no `WorkFlowResource`; the routes live in the existing project and product resources.

## What this part builds on

Verified in the code on 2026-11-21.

- **Deployments.** `work_deployment`, `work_deployment_item`, `work_deployment_event` (`bin/db/migrations/V082__work_deployments.sql`). `work_deployment` has `started_at` (set on `running`), `verified_at` (set on `verified`, kept when later rolled back) and `closed_at`, which **every** ending state overwrites (`DeploymentState.ended()`), so a rollback replaces the verify or fail time there. The time a deployment failed or was rolled back is therefore read from `work_deployment_event.at` where `to_state` is `failed` / `rolled_back`, never from `closed_at`.
- **"Shipped"** is `JdbcWorkDeployments.shippedVerified(Jdbc, Connection, long itemId)` (`wikantik-work/src/main/java/com/wikantik/work/JdbcWorkDeployments.java`): environment `production`, state `verified` (a rolled-back one no longer counts), `verified_at >=` the item's last merged change's `closed_at`, and the deployment's product equals the item's product. DORA lead time applies the same four conditions per change (P6-8).
- `record Deployment( long id, long productId, String environment, String version, DeploymentState state, String requestedBy, Long requestedByAgentId, String approvedBy, Long approvedByAgentId, Instant requestedAt, Instant approvedAt, Instant startedAt, Instant verifiedAt, Instant closedAt, String evidence, List< Long > itemIds )`; `record DeploymentDraft( String productKey, String environment, String version, List< String > itemKeys )`. `new Deployment(` appears in `JdbcWorkDeployments.ROW`, `WorkDeploymentsService.view`, `WorkInputEdgesTest`, `wikantik-rest/.../WorkDeploymentResourceTest`; `new DeploymentDraft(` in `WorkDeploymentResource`, `RequestDeploymentTool`, `WorkDeploymentResourceTest`, `WorkDeploymentRacePostgresTest`, `WorkDeploymentAccessPostgresTest`, `WorkGateMetricsPostgresTest`, `WorkDoneMeansPostgresTest`.
- `WorkDeploymentStore.request( Product, String environment, String version, List< Item >, Actor )`; its rollback test is `JdbcWorkDeploymentsPostgresTest.aFailedRequestLeavesNothing` (statements 1 deployment, 2 items, 3 history, 4..n item events).
- `WorkDeploymentsService.request( Actor, DeploymentDraft )` and its private `seen( Actor, long )` (unknown and unseen are one `WorkNotFoundException`), `DeploymentAccess` (the only join of deployments to standing). The service is 294 lines: remediation checks go in a new class (Task 6.1b).
- **Status history.** `WorkEventDiff` writes `type = 'status'`, `from_value`/`to_value` = `WorkStatus.wire()` (`draft`, `refining`, `ready`, `in_progress`, `review`, `done`, `cancelled`). Event times are the database's `NOW()`, not the service clock, so Postgres tests set times by SQL (as `WorkAutonomyPostgresTest` does with `WorkAgentFixtures.exec`). Index `idx_work_event_item (item_id, at)`.
- **Changes.** `work_change.closed_at` is the merge time of a merged change; `work_change.product_id` never changes; `merge_kind` in `reviewed|unreviewed|direct` (V081).
- **Scopes.** `WorkScopes.scope( Actor, String projectKey )` (unknown and hidden → `WorkNotFoundException`), `Scope.require( Action.VIEW, null )` as `WorkService.board` does; `WorkScopes.requireInstance( actor, Action.MANAGE_INSTANCE )` as `WorkService.productHistory` does (throws `WorkDeniedException`, REST 403). `ItemViews.pinned( Actor, Scope, ItemFilter )` pins the project and resolves an assignee display name (unknown → a sentinel that matches nobody). `ItemQuery.conditions( ItemFilter, boolean withStatuses, StringBuilder, List<Object> )` appends `AND i.…` clauses.
- **Facade wiring.** `WorkService`'s last constructor builds every facade (`new WorkDeploymentsService( store, scopes )` etc.); production passes `Clock.systemUTC()` (`wikantik-main/.../work/runtime/WorkWiring.java:73-74`), so the clock's zone is UTC.
- **Visibility test.** `WorkServiceVisibilityPostgresTest` classifies by method **name** in `Set.of(...)` constants (a duplicate name makes `Set.of` throw), enumerates facades through `FACADES`, passes `null` for any parameter type it has no fixture for, and calls an `INSTANCE_READS` method on the admin with exactly `{ admin, "web" }`. Its fixture has project `PLAT`, product `web` and a verified production deployment carrying `PLAT-1`.
- **REST.** `/api/work/projects/*` and `/api/work/products/*` are single servlets (`web.xml` lines 1056-1064) with `SegmentRouteTable` routes; `projects/{}/change-counts` (route in `WorkProjectResource`, JSON in `WorkChangeJson`) is the pattern for a read beside the project. `WorkResourceBase` maps `WorkNotFoundException`→404, `WorkDeniedException`→403, `WorkValidationException`→400 with the field.
- **Frontend.** `/work/:project` children in `wikantik-frontend/src/main.jsx` (lines 83-89); tabs are `TABS` in `components/work/WorkLayout.jsx`; `query()` in `api/work.js`; `styles/work.css` uses only tokens defined in `globals.css` (`tokensUsage.test.js` fails otherwise); admin check `user?.roles?.includes('Admin')` (`WorkProducts.jsx:129`).
- `WorkMetrics` is the Micrometer listener and is **not** touched by this part.

## Decisions

- **P6-1 Zone and weeks.** Days and weeks are taken in the zone of the service's `Clock` (UTC in production). A week starts Monday 00:00 in that zone. Every report carries `zone`. *Reason:* the outline's "server zone" is, in this code, the UTC clock; one zone for every work figure. *If wrong:* one setting feeding `MetricWindow`.
- **P6-2 As of now.** Figures describe the records as they are now. An item counts as closed at its **last** `status → done` event and only while it is still `done`; a deployment's state is its state now. Nothing is replayed to a past date. *If wrong:* a past window would need an event replay like Part 2's burndown.
- **P6-3 Work in progress** is items in `in_progress` or `review`. Each item's age runs from its first `status → in_progress` event, or from `created_at` when it has none. Each status lists its 50 oldest items plus its total count (the design asks for "the age of each item").
- **P6-4 Deployment frequency** counts every production deployment whose `verified_at` falls in the window, including one later rolled back: it reached production; its rollback is counted as a failure.
- **P6-5 "Ran"** = production deployment with `started_at` in the window whose state is `verified`, `failed` or `rolled_back`. One still `running` is not counted yet.
- **P6-6 Remediation.** A deployment is "remediated" when a deployment that is not `rejected` names it in `remediates_id`. Remediators and the "next verified deployment" are looked up **product-wide**, not only among deployments that carry the project's items; only their existence and `verified_at` are used, never listed. *Reason:* a fix-forward filed in another project still means this deployment failed. *If wrong:* restrict the two lookups with the project clause; a team then looks better than it is.
- **P6-7 Recovery.** Start: `verified_at` if the deployment ever went live, else its `failed` event time. End: the earliest, at or after the start, of its `rolled_back` event, the `verified_at` of any remediator, and, only for one that failed without going live, the `verified_at` of the next verified production deployment of the product. A failure with no end is reported in `unrecovered` and left out of the durations.
- **P6-8 Lead time for changes.** Per merged change: from `coalesce(first_commit_at, opened_at)` to the `verified_at` of the earliest deployment that satisfies `shippedVerified`'s conditions for that change (production, state `verified`, carries its item, verified at or after this change's `closed_at`, product = the change's own `product_id`). A change counts in the window its shipping deployment was verified in; a change never shipped is left out; a negative duration is dropped. `changesWithoutCommitTime` counts the fallbacks.
- **P6-9 No duplicate sprint figures.** Velocity, commitment and burndown stay with Part 2's routes; the flow page calls them. `FlowReport` does not recompute them.
- **P6-10 `remediates`.** Given only at request (D10). Unknown or unseen → not found; another product or environment, or a target not `verified`/`failed`/`rolled_back` → validation on field `remediates`. Several deployments may remediate one. A row cannot name itself (table CHECK; at request time the new row has no id, so the service cannot be asked). Responses show the raw id `remediatesId` to every viewer of the remediating deployment, as event `from_value`s already show ids.
- **P6-11 Filters** are the item's current values (sprint, label, type, assignee) through `ItemFilter` / `ItemQuery`, so the flow page filters exactly as the board does.
- **P6-12 Window defaults.** `to` defaults to today, `from` to `to − 89 days` (90 days inclusive). With a sprint chosen and no dates, the sprint's `starts_on`…`ends_on`. At most 366 days inclusive.
- **P6-13 Percentiles** are nearest-rank on the sorted values: the p-th percentile of n values is the value at 1-based rank `ceil(p × n / 100)`.
- **P6-14** No configuration key and no MCP tool for the figures (agents that need them read REST with their token). `request_deployment` gains the `remediates` argument only.

## Review Focus

1. **A fix-forward that looks like success.** D1 verified, D2 requested with `remediates` = D1 and verified: D1 is a failure, recovery is D2.verified − D1.verified, D2 counts as rework. A rejected remediator marks nothing. (Tasks 6.3a `DoraTest`, 6.3b `WorkDoraPostgresTest`.)
2. **A verified-then-rolled-back deployment.** Counted in frequency, as a failure, recovery = rollback − verify, and it ships no change: the change's lead time runs to the next verified deployment. (Tasks 6.3a, 6.3b.)
3. **Reopened items and items never in progress.** Closed, reopened, closed again: counted once, in the week of the last close. Closed then reopened and still open: nowhere. Never in progress: in lead time only. (Tasks 6.2b, 6.2c.)
4. **A figure crossing a project boundary.** A member of A sees only deployments carrying A's items; one carrying A and B counts in both; a stranger gets not found; a non-admin asking for a product's instance view is refused. (Tasks 6.2c, 6.3b, 6.4a.)
5. **A week boundary.** Sunday 23:59:59 and Monday 00:00 in the zone fall in different weeks; the window end is exclusive at `to + 1` 00:00. (Task 6.2a.)
6. **A stored figure creeping in.** `WorkFlowNoStoreRuleTest` fails on any write statement in the flow store or any metric registration in the flow classes. (Task 6.2c.)

---

### Task 6.1a: `remediates_id` — schema, record and store

**Files:**
- Create: `bin/db/migrations/V089__work_deployment_remediates.sql`
- Test (new): `wikantik-work/src/test/java/com/wikantik/work/WorkDeploymentRemediatesSchemaTest.java`
- Modify: `wikantik-work/src/main/java/com/wikantik/work/Deployment.java`, `DeploymentDraft.java`, `WorkDeploymentStore.java`, `JdbcWorkDeployments.java`, `WorkDeploymentsService.java` (`view` constructor call only); `wikantik-work/src/test/java/com/wikantik/work/WorkInputEdgesTest.java`, `JdbcWorkDeploymentsPostgresTest.java`; `wikantik-rest/src/test/java/com/wikantik/rest/WorkDeploymentResourceTest.java` (constructor calls)

**Interfaces — produces:**
```sql
-- V089: a deployment may name the earlier deployment it remediates (fix-forward). Read by the DORA figures.
ALTER TABLE work_deployment ADD COLUMN IF NOT EXISTS remediates_id BIGINT REFERENCES work_deployment (id);
ALTER TABLE work_deployment DROP CONSTRAINT IF EXISTS work_deployment_remediates_self_chk;
ALTER TABLE work_deployment ADD CONSTRAINT work_deployment_remediates_self_chk
    CHECK (remediates_id IS NULL OR remediates_id <> id);
CREATE INDEX IF NOT EXISTS idx_work_deployment_remediates ON work_deployment (remediates_id)
    WHERE remediates_id IS NOT NULL;
```
(Licence header as V082. No grant: `UPDATE` on `work_deployment` already exists, and the column is written by the `INSERT`.)
- `Deployment` gains `Long remediatesId` between `evidence` and `itemIds`.
- `DeploymentDraft( String productKey, String environment, String version, List< String > itemKeys, Long remediatesId )` plus a secondary constructor with the old four arguments that passes `null` (the seven existing call sites keep compiling).
- `WorkDeploymentStore.request( Product p, String environment, String version, List< Item > items, Long remediatesId, Actor by )`; the old five-argument `request` becomes a `default` method passing `null`. The `INSERT` writes `remediates_id` as its sixth column; no statement is added.
- `JdbcWorkDeployments.SELECT` gains `d.remediates_id`; `ROW` reads it with `getNullableLong`.

- [ ] **Step 1: Failing schema test** `WorkDeploymentRemediatesSchemaTest` (model on `WorkDeploymentsSchemaTest`, read the file with its own `v089()` helper): `remediatesIdIsANullableReference` (`information_schema.columns`: `bigint`, nullable); `anUnknownTargetIsRefused` (SQLState `23503`); `aDeploymentCannotRemediateItself` (`UPDATE … SET remediates_id = id` → `23514`); `v089IsANoOpWhenReapplied` (exec the text twice with `:app_user` → `CURRENT_USER`); `v089AddsNoTableAndNoGrant` (the text, comments stripped, contains neither `CREATE TABLE` nor `GRANT`).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=WorkDeploymentRemediatesSchemaTest`. Expected: FAIL (file missing).
- [ ] **Step 3:** Write the migration. Re-run: PASS.
- [ ] **Step 4: Failing store tests** in `JdbcWorkDeploymentsPostgresTest`: `aRequestRecordsWhatItRemediates` (request D1, move it to verified, request D2 with `remediatesId = D1.id()`: `deployment(D2).remediatesId() == D1.id()`, D1's is null); `aFailedRequestWithARemediationLeavesNothing` (as `aFailedRequestLeavesNothing`, `failingOn( 5 )`, with a `remediatesId`: no new `work_deployment` row).
- [ ] **Step 5:** `mvn test -pl wikantik-work -Dtest=JdbcWorkDeploymentsPostgresTest`: compile failure. Add the record component, the draft constructor, the store overload and the column; fix the four `new Deployment(` sites (pass `null`, or `d.remediatesId()` in `WorkDeploymentsService.view`). Re-run: PASS. `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-work-mcp`.
- [ ] **Step 6: Commit** `git add bin/db/migrations/V089__work_deployment_remediates.sql wikantik-work/src/main/java/com/wikantik/work/{Deployment,DeploymentDraft,WorkDeploymentStore,JdbcWorkDeployments,WorkDeploymentsService}.java wikantik-work/src/test/java/com/wikantik/work/{WorkDeploymentRemediatesSchemaTest,JdbcWorkDeploymentsPostgresTest,WorkInputEdgesTest}.java wikantik-rest/src/test/java/com/wikantik/rest/WorkDeploymentResourceTest.java` — `feat(work): a deployment may name the deployment it remediates (V089)`.

### Task 6.1b: Requesting a remediating deployment — service, REST, MCP, UI

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/Remediation.java`; test `WorkRemediationPostgresTest.java`
- Modify: `WorkDeploymentsService.java` (`request`), `wikantik-rest/src/main/java/com/wikantik/rest/WorkDeploymentResource.java` (`REQUEST_FIELDS`, parse `remediates`), `WorkDeploymentResourceTest.java`; `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/RequestDeploymentTool.java`, `WorkMcpDeployments.java` (`deployment` map), `src/main/resources/wikantik-work-mcp-instructions.txt`, `DeploymentToolsPostgresTest.java`; `wikantik-frontend/src/components/work/WorkDeployments.jsx` (+ `.test.jsx`), `WorkDeploymentView.jsx` (+ `.test.jsx`)

**Interfaces:**
- Consumes: `WorkDeploymentsService.seen( Actor, long )` (private; `Remediation` receives the looked-up `Deployment` from it).
- Produces:
  - `final class Remediation { static void check( Deployment target, Product product, String environment ) }` (package-private): throws `WorkValidationException( "remediates", "A remediating deployment fixes an earlier deployment of the same product and environment." )` on a product or environment mismatch, and `WorkValidationException( "remediates", "Only a deployment that was verified, failed or rolled back can be remediated." )` for any other state.
  - In `request`: when `d.remediatesId() != null`, `Remediation.check( seen( a, d.remediatesId() ).deployment(), product, environment )` **before** the store call; the id is passed to `store.deployments().request(…, d.remediatesId(), a)`.
  - REST `POST /api/work/deployments` accepts `remediates` (integer). A non-integer is 400 field `remediates`, `remediates must be a deployment number.`. The response's deployment JSON carries `remediatesId` (Gson omits it when null).
  - MCP `request_deployment` gains optional `remediates` (`integer`, "The deployment this one fixes (fix-forward): an earlier deployment of the same product and environment that was verified, failed or rolled back.", example `4`), read with `intArg`; the deployment map gains `"remediates"` (the id or null). The instructions text's `request_deployment` line names the argument.
  - UI: the request form has a "Remediates" select (default "Nothing") listing this product's loaded deployments in state `verified`, `failed` or `rolled_back` whose environment equals the form's environment (`production` when blank), each `#<id> <version> (<state label>)`; it sends `remediates: <id>` or omits it. `WorkDeploymentView` shows "Remediates" with a link to `/work/deployments/<id>` when `remediatesId` is present.

- [ ] **Step 1: Failing tests.** `WorkRemediationPostgresTest`: `aVerifiedDeploymentCanBeRemediated`, `aFailedAndARolledBackOneCanBeRemediated`, `aRequestedApprovedOrRunningOneCannot` (field `remediates`, second message), `anotherProductOrEnvironmentIsRefused` (field `remediates`, first message), `anUnknownOrUnseenTargetIsNotFound` (999999, and a deployment carrying only items of a project the caller is not in: both `WorkNotFoundException` with the same message), `twoDeploymentsMayRemediateOne`. `WorkDeploymentResourceTest.passesRemediatesToTheDraft` (captor: `remediatesId() == 4L`) and `aNonNumericRemediatesIs400`. `DeploymentToolsPostgresTest.theIncidentPathEndToEnd` gains: the fix-forward deployment is requested with `remediates` = the bad one, and `get_deployment` shows `"remediates"`; `argumentsAreCheckedByName` accepts `remediates`. vitest: `WorkDeployments.test.jsx` "offers only verified, failed or rolled back deployments of the same environment as remediation targets" and "sends remediates when one is chosen"; `WorkDeploymentView.test.jsx` "links the deployment it remediates".
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=WorkRemediationPostgresTest` (FAIL: no class); `cd wikantik-frontend && npx vitest run src/components/work/WorkDeployments.test.jsx src/components/work/WorkDeploymentView.test.jsx` (FAIL).
- [ ] **Step 3:** Implement `Remediation` and the service call; the REST field; the MCP argument and map entry; the two components.
- [ ] **Step 4:** Re-run the above, then `mvn install -pl wikantik-work -DskipTests -q && mvn test -pl wikantik-rest -Dtest=WorkDeploymentResourceTest && mvn test -pl wikantik-work-mcp -Dtest='DeploymentToolsPostgresTest,WorkMcpInstructionsDriftTest'`; `npm run lint`. All PASS.
- [ ] **Step 5: Commit** (name every file above) — `feat(work): request a deployment that remediates an earlier one (REST, MCP, UI)`.

### Task 6.2a: The window and percentiles (pure)

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/MetricWindow.java`, `Percentiles.java`, `Durations.java`; tests `MetricWindowTest.java`, `PercentilesTest.java`

**Interfaces — produces:**
- `public record Durations( int n, Duration p50, Duration p85 )` with `public static final Durations NONE = new Durations( 0, null, null )`.
- `final class Percentiles { static Duration nearestRank( List< Duration > sortedAscending, int percent ); static Durations of( Collection< Duration > values ) }` — `of` drops nulls and negatives, sorts, returns `NONE` when empty.
- `record MetricWindow( LocalDate from, LocalDate to, ZoneId zone )` (package-private) with:
  - `static MetricWindow of( LocalDate from, LocalDate to, Clock clock )` — defaults per P6-12 (today = `LocalDate.now( clock )`); `from` after `to` → `WorkValidationException( "from", "The start date is after the end date." )`; more than 366 days inclusive → `WorkValidationException( "to", "A report covers at most 366 days." )`.
  - `Instant start()` = `from` 00:00 in `zone`; `Instant end()` = `to + 1` 00:00 (exclusive); `boolean contains( Instant t )` = `start <= t < end`.
  - `LocalDate weekOf( Instant t )` = the Monday on or before `t`'s date in `zone`; `List< LocalDate > weekStarts()` = every Monday from `weekOf( start() )` to `weekOf( end() − 1 ns )`.

- [ ] **Step 1: Failing tests.** `PercentilesTest` (table, values in minutes): `[5]` → p50 5, p85 5; `[1,2]` → p50 1, p85 2; `1..20` → p50 10, p85 17; `[3,1,2]` (unsorted) → p50 2, p85 3; `[]` → `NONE`; `[null, −1, 4]` → n 1, p50 4. `MetricWindowTest` with `Clock.fixed( 2026-11-21T10:00Z, UTC )` (a Saturday): `defaultsToTheLast90Days` (2026-08-24 … 2026-11-21); `fromOnlyEndsToday`, `toOnlyStarts89DaysBefore`; `366DaysIsAllowed367IsNot` (2025-12-31…2026-12-31 ok; 2025-12-30…2026-12-31 refused naming `to`); `fromAfterToIsRefusedNamingFrom`; `theEndIsExclusive` (`contains( 2026-11-21T23:59:59Z )` true, `contains( 2026-11-22T00:00Z )` false for to = 2026-11-21); `sundayNightAndMondayAreDifferentWeeks` (`weekOf( 2026-11-08T23:59:59Z )` = 2026-11-02, `weekOf( 2026-11-09T00:00Z )` = 2026-11-09); `theZoneDecidesTheWeek` (zone `Europe/Berlin`: `weekOf( 2026-11-08T23:30Z )` = 2026-11-09); `weekStartsCoversPartialWeeks` (2026-11-04…2026-11-18 → [11-02, 11-09, 11-16]).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='MetricWindowTest,PercentilesTest'`: compile failure.
- [ ] **Step 3:** Implement the three classes. Re-run: PASS.
- [ ] **Step 4: Commit** `git add` the five files — `feat(work): report window and nearest-rank percentiles`.

### Task 6.2b: Flow figures (pure)

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/WorkFlowStore.java` (the fact records only in this task; methods in 6.2c), `FlowReport.java`, `Flow.java`; test `FlowTest.java`

**Interfaces — produces:**
- In `WorkFlowStore` (package-private interface): `record ClosedItem( long id, String key, Instant createdAt, Instant firstInProgressAt, Instant closedAt, BigDecimal estimate )`; `record OpenItem( String key, String title, WorkStatus status, Instant createdAt, Instant firstInProgressAt )`.
- `public record FlowReport( LocalDate from, LocalDate to, String zone, List< Week > throughput, Durations cycleTime, Durations leadTime, List< Wip > wip )` with nested `public record Week( LocalDate weekStart, int items, BigDecimal points )`, `public record Wip( WorkStatus status, int count, List< WipItem > items )`, `public record WipItem( String key, String title, Duration age )`.
- `final class Flow` (static, pure):
  - `List< FlowReport.Week > throughput( List< ClosedItem > closed, MetricWindow w )`: one `Week` per `w.weekStarts()`, zero weeks included; an item counts in `w.weekOf( closedAt )`; points sum `estimate`, null as 0.
  - `Durations cycleTime( List< ClosedItem > closed )`: `closedAt − firstInProgressAt` for items whose `firstInProgressAt` is non-null.
  - `Durations leadTime( List< ClosedItem > closed )`: `closedAt − createdAt` for all.
  - `List< FlowReport.Wip > wip( List< OpenItem > open, Instant now )`: one entry for `IN_PROGRESS` and one for `REVIEW`, always both; age = `now − coalesce( firstInProgressAt, createdAt )`; items oldest first, at most 50, `count` the total.

- [ ] **Step 1: Failing `FlowTest`** (window 2026-11-02…2026-11-15, UTC): `throughputHasAWeekForEveryWeekEvenEmpty` (one item closed 11-10 → [11-02: 0, 11-09: 1]); `pointsTreatAMissingEstimateAsZero` (estimates 3, null, 1.5 in one week → points 4.5, items 3); `anItemNeverInProgressCountsInLeadTimeOnly` (cycle n 0, lead n 1); `cycleTimeRunsFromTheFirstStart` (first in progress 11-02T09, closed 11-10T09 → p50 8 days); `wipListsBothStatusesOldestFirstWithTheirAge` (now fixed; an item without a start ages from `createdAt`); `wipCapsTheListAt50ButCountsAll` (60 in progress → 50 items, count 60).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=FlowTest`: compile failure.
- [ ] **Step 3:** Implement. Re-run: PASS.
- [ ] **Step 4: Commit** the four files — `feat(work): flow figures as pure functions over item facts`.

### Task 6.2c: Flow store, service and facade

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/JdbcWorkFlow.java`, `WorkFlowService.java`, `FlowQuery.java`; tests `WorkFlowPostgresTest.java`, `WorkFlowNoStoreRuleTest.java`, `FlowFixtures.java` (test helper)
- Modify: `WorkFlowStore.java` (methods), `WorkStore.java` (`WorkFlowStore flow()`), `JdbcWorkStore.java` (field + accessor), `WorkService.java` (`flow()` accessor; construct the facade), `WorkServiceVisibilityPostgresTest.java`

**Interfaces:**
- Consumes: `ItemViews.pinned`, `ItemQuery.conditions`, `WorkScopes.scope`, `store.sprints().byId` (Part 2), `Labels.normalise` and `WorkType` (Part 1), `MetricWindow`, `Flow`.
- Produces:
  - `public record FlowQuery( LocalDate from, LocalDate to, Long sprintId, String label, WorkType type, String assignee )`; `null` as a whole means all defaults.
  - `WorkFlowStore`: `List< ClosedItem > closedItems( ItemFilter pinned, Instant from, Instant to ) throws SQLException`; `List< OpenItem > openItems( ItemFilter pinned ) throws SQLException`.
  - `public final class WorkFlowService` (constructor package-private `( WorkStore store, WorkScopes scopes, ItemViews views, Clock clock )`): `public FlowReport project( Actor actor, String projectKey, FlowQuery query ) throws SQLException` — `scopes.scope`, `require( Action.VIEW, null )`; a `sprintId` whose sprint is unknown or of another project → `WorkNotFoundException( WorkScopes.NOT_FOUND )`; builds an `ItemFilter` holding only type, labels (`Set.of` the one normalised label), sprint (Part 2's id form) and the raw assignee reference, then `views.pinned`; window via `MetricWindow.of( from, to, clock )` (sprint dates when a sprint is given and both dates are null). Archived projects are readable.
  - `WorkService.features().flow() : WorkFlowService`, built as `new WorkFlowService( store, scopes, views, clock )` (move `this.clock = clock` above it).
  - SQL, `closedItems` (filter clauses appended by `ItemQuery.conditions( pinned, false, where, params )` after the two window parameters):
```sql
SELECT i.id, p.key || '-' || i.seq AS item_key, i.created_at, i.estimate, d.closed_at,
       ( SELECT MIN( s.at ) FROM work_event s
          WHERE s.item_id = i.id AND s.type = 'status' AND s.to_value = 'in_progress' ) AS first_in_progress_at
  FROM work_item i
  JOIN work_project p ON p.id = i.project_id
  JOIN LATERAL ( SELECT MAX( e.at ) AS closed_at FROM work_event e
                  WHERE e.item_id = i.id AND e.type = 'status' AND e.to_value = 'done' ) d ON TRUE
 WHERE i.status = 'done' AND i.level <> 'epic'
   AND d.closed_at >= ? AND d.closed_at < ?
   /* ItemQuery.conditions: AND i.project_id = ? [AND i.assignee = ?] [type] [labels] [sprint] */
 ORDER BY d.closed_at, i.id
```
  - SQL, `openItems`:
```sql
SELECT p.key || '-' || i.seq AS item_key, i.title, i.status, i.created_at,
       ( SELECT MIN( s.at ) FROM work_event s
          WHERE s.item_id = i.id AND s.type = 'status' AND s.to_value = 'in_progress' ) AS first_in_progress_at
  FROM work_item i
  JOIN work_project p ON p.id = i.project_id
 WHERE i.status IN ( 'in_progress', 'review' ) AND i.level <> 'epic'
   /* ItemQuery.conditions */
 ORDER BY i.id
```
  - `FlowFixtures` (test): `long item( WorkStore, Project, String title, Instant createdAt )` (create, then `UPDATE work_item SET created_at`); `void move( DataSource, long itemId, long projectId, String from, String to, Instant at )` (`INSERT INTO work_event (project_id, item_id, at, actor, type, from_value, to_value) VALUES (…,'status',…)` and `UPDATE work_item SET status = to`).

- [ ] **Step 1: Failing tests.** `WorkFlowPostgresTest` (service built with `Clock.fixed( 2026-11-21T10:00Z, UTC )`, window 2026-11-02…2026-11-15 unless named):
  - `aReopenedThenClosedItemCountsOnceAtItsLastClose` (ready→in_progress 11-02, →done 11-03, done→in_progress 11-04, →done 11-10: throughput 11-02 = 0, 11-09 = 1; cycle p50 = 8 days; lead = 11-10 − created).
  - `anItemClosedThenReopenedAndStillOpenCountsNowhere` (closed 11-05, reopened 11-06: throughput all zero, lead n 0; it appears in `wip` under `in_progress`).
  - `anItemNeverInProgressCountsInLeadTimeOnly`.
  - `epicsAreLeftOut` (an epic moved to done in the window).
  - `theWindowEndIsExclusive` (done at 2026-11-15T23:59:59Z counts; at 2026-11-16T00:00Z does not).
  - `filtersNarrow` (label, type, sprint, assignee by display name each drop the other item; an unknown assignee name gives an empty report, not an error).
  - `aSprintOfAnotherProjectIsNotFound`; `aSprintWithNoDatesUsesTheSprintsDates`.
  - `wipShowsAgesAgainstTheClock`.
  - `aStrangerGetsNotFoundAndAnAgentMemberReads` (mallory → `WorkNotFoundException`; an agent member of PLAT, sponsored by a member, gets a report).
  - `anArchivedProjectIsStillReadable`.
  - `WorkFlowNoStoreRuleTest` (reads sources from `src/main/java/com/wikantik/work/`): `theFlowStoreOnlyReads` (`JdbcWorkFlow.java` matches none of `\b(INSERT|UPDATE|DELETE|CREATE|ALTER|TRUNCATE)\b`, case-insensitive, and calls no `jdbc.update` / `inTransaction`); `noFlowClassRegistersAMetric` (`JdbcWorkFlow`, `WorkFlowService`, `Flow`, `Dora`, `Percentiles`, `MetricWindow`, `FlowReport`, `DoraReport` mention neither `io.micrometer` nor `WorkListener`).
  - `WorkServiceVisibilityPostgresTest`: add `"flow"` to `FACADES`. Do **not** add `"project"` to `READS` (it is already there; a duplicate makes `Set.of` throw). The existing `everyReadMethodHidesInvisibleProject` then drives `flow().project( lena, "PLAT", null )` (real data: a non-null report) and every stranger (not found).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkFlowPostgresTest,WorkFlowNoStoreRuleTest,WorkServiceVisibilityPostgresTest'`: compile failure.
- [ ] **Step 3:** Implement the store, the service, the accessor and the fixture. If `WorkFlowService` crosses a PMD limit after Task 6.3b, move the DORA half into `DoraReports` (package-private) rather than suppress.
- [ ] **Step 4:** Re-run: PASS. `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-work-mcp`.
- [ ] **Step 5: Commit** (every file above) — `feat(work): flow report computed from the event log`.

### Task 6.3a: DORA figures (pure)

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/DoraReport.java`, `Dora.java`; test `DoraTest.java`
- Modify: `WorkFlowStore.java` (fact records)

**Interfaces — produces:**
- In `WorkFlowStore`: `record DeploymentFact( long id, long productId, DeploymentState state, Instant startedAt, Instant verifiedAt, Instant failedAt, Instant rolledBackAt, Long remediatesId, boolean remediated )`; `record RecoveryEnd( long id, long productId, Instant verifiedAt, Long remediatesId )`; `record ChangeFact( long id, Instant firstCommitAt, Instant openedAt, Instant mergedAt, Instant shippedAt )`.
- `public record DoraReport( LocalDate from, LocalDate to, String zone, String productKey, List< WeekCount > deploymentFrequency, Durations leadTimeForChanges, int changesWithoutCommitTime, Rate changeFailureRate, Durations recoveryTime, int unrecovered, Rate reworkRate, MergeMix merges )` with nested `public record WeekCount( LocalDate weekStart, int deployments )`, `public record Rate( int numerator, int denominator )`, `public record MergeMix( int reviewed, int unreviewed, int direct, int flagged, int flaggedUnacknowledged )`.
- `final class Dora` (static, pure):
  - `boolean ran( DeploymentFact f, MetricWindow w )` — P6-5.
  - `boolean failed( DeploymentFact f )` — state `FAILED` or `ROLLED_BACK`, or `remediated`.
  - `List< WeekCount > frequency( List< DeploymentFact > facts, MetricWindow w )` — P6-4, a row per `w.weekStarts()`.
  - `Rate changeFailureRate( List< DeploymentFact > facts, MetricWindow w )` — failed ∧ ran / ran.
  - `Rate rework( List< DeploymentFact > facts, MetricWindow w )` — ran ∧ `remediatesId != null` / ran.
  - `record Recovery( Durations durations, int unrecovered )`; `Recovery recovery( List< DeploymentFact > facts, List< RecoveryEnd > ends, MetricWindow w )` — over facts that ran and failed, P6-7. Remediators are the `ends` with `remediatesId == f.id()`; "next verified" is the earliest `ends` entry of the same product with `verifiedAt > failedAt`, used only when `f.verifiedAt() == null`.
  - `record LeadTime( Durations durations, int withoutCommitTime )`; `LeadTime leadTime( List< ChangeFact > changes )` — P6-8 (rows with `shippedAt == null` ignored).

- [ ] **Step 1: Failing `DoraTest`.** One scenario, window 2026-11-02…2026-11-15 UTC, written as a fact table in the test (`t( "11-02T10:00" )` = that instant in 2026, UTC):

| id | state | started | verified | failed | rolled back | remediates | remediated |
|---|---|---|---|---|---|---|---|
| 1 | verified | 11-02T10 | 11-02T11 | | | | no |
| 2 | rolled_back | 11-03T10 | 11-03T11 | | 11-03T13 | | no |
| 3 | failed | 11-04T10 | | 11-04T10:30 | | | no |
| 4 | verified | 11-05T11 | 11-05T12 | | | | no |
| 5 | verified | 11-09T10 | 11-09T11 | | | | yes (by 6) |
| 6 | verified | 11-09T14 | 11-09T15 | | | 5 | no |
| 7 | failed | 11-10T10 | | 11-10T10:05 | | | no (its remediator was rejected) |
| 9 | verified | 11-16T10 | 11-16T11 | | | | no |
| 10 | verified | 11-01T23 | 11-02T00:30 | | | | no |
| 11 | running | 11-12T10 | | | | | no |

Every row is product `web` except row 9, which is product `api`. `ends` = the verified rows 1, 2, 4, 5, 6, 9, 10 as `RecoveryEnd`. Tests and expected values: `frequencyCountsVerificationsPerWeekIncludingOneLaterRolledBack` (11-02: 1, 2, 4, 10 → 4; 11-09: 5, 6 → 2); `ranCountsOnlyFinishedDeploymentsStartedInTheWindow` (1–7 → 7; 9, 10, 11 out); `aRollbackAFailureAndAFixForwardAreFailures` (rate 4/7: 2, 3, 5, 7); `aRejectedRemediationIsNotAFailure` (7 counts only because it failed; set its state to verified with `remediated = false` and the rate drops to 3/7); `recoveryFromRollbackNextVerifiedAndRemediator` (2 → 2 h, 3 → 25 h 30 min to row 4, 5 → 4 h to row 6; 7 unrecovered: n 3, p50 4 h, p85 25 h 30 min, `unrecovered` 1); `aVerifiedFailureIsNotRecoveredByAnUnrelatedLaterDeployment` (drop row 6 and add row 12, `web`, verified 11-12T10: row 5 is unrecovered although row 12 went live after it; row 7, never live, now recovers at row 12); `anotherProductsDeploymentEndsNothing` (row 9, product `api`, is why row 7 is unrecovered in the base table); `reworkIsRemediatingOverRan` (1/7). Lead time: `leadTimeFallsBackToOpenedAtAndCountsIt` (C1 first commit 11-01T09, shipped 11-02T11 → 26 h; C2 no first commit, opened 11-03T08, shipped 11-05T12 → 52 h; C3 not shipped: n 2, p50 26 h, p85 52 h, `withoutCommitTime` 1); `aNegativeLeadTimeIsDropped`.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=DoraTest`: compile failure.
- [ ] **Step 3:** Implement. Re-run: PASS.
- [ ] **Step 4: Commit** the four files — `feat(work): DORA four keys and rework rate as pure functions`.

### Task 6.3b: DORA store and service

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/DoraQuery.java`; tests `WorkDoraPostgresTest.java`, `DoraFixtures.java` (test helper)
- Modify: `WorkFlowStore.java`, `JdbcWorkFlow.java`, `WorkFlowService.java`, `WorkServiceVisibilityPostgresTest.java`, `WorkFlowNoStoreRuleTest.java` (nothing new to list unless `DoraReports` was split out: add it)

**Interfaces — produces:**
- `public record DoraQuery( LocalDate from, LocalDate to, String productKey )`; `null` means defaults and every product.
- `WorkFlowService`:
  - `public DoraReport dora( Actor actor, String projectKey, DoraQuery query ) throws SQLException` — `scope` + `require( Action.VIEW, null )`; a named product that does not exist → not found (archived products are allowed).
  - `public DoraReport doraForProduct( Actor actor, String productKey, LocalDate from, LocalDate to ) throws SQLException` — `scopes.requireInstance( actor, Action.MANAGE_INSTANCE )` first (non-admin: `WorkDeniedException`), then the product (unknown → not found); no project clause.
- `WorkFlowStore` (projectId or productId may be null, never both: `IllegalArgumentException`):
  - `List< DeploymentFact > deployments( Long projectId, Long productId, Instant from, Instant to )`
  - `List< RecoveryEnd > recoveryEnds( Set< Long > productIds, Instant since )` (not called when nothing failed)
  - `List< ChangeFact > shippedChanges( Long projectId, Long productId, Instant from, Instant to )`
  - `DoraReport.MergeMix merges( Long projectId, Long productId, Instant from, Instant to )`
- SQL, `deployments` (bracketed clauses appended only for a non-null id):
```sql
SELECT d.id, d.product_id, d.state, d.started_at, d.verified_at, d.remediates_id,
       ( SELECT MIN( e.at ) FROM work_deployment_event e
          WHERE e.deployment_id = d.id AND e.to_state = 'failed' ) AS failed_at,
       ( SELECT MIN( e.at ) FROM work_deployment_event e
          WHERE e.deployment_id = d.id AND e.to_state = 'rolled_back' ) AS rolled_back_at,
       EXISTS ( SELECT 1 FROM work_deployment r
                 WHERE r.remediates_id = d.id AND r.state <> 'rejected' ) AS remediated
  FROM work_deployment d
 WHERE d.environment = 'production'
   AND ( ( d.started_at >= ? AND d.started_at < ? ) OR ( d.verified_at >= ? AND d.verified_at < ? ) )
   [ AND d.product_id = ? ]
   [ AND EXISTS ( SELECT 1 FROM work_deployment_item di JOIN work_item i ON i.id = di.item_id
                   WHERE di.deployment_id = d.id AND i.project_id = ? ) ]
 ORDER BY d.started_at, d.id
```
- SQL, `recoveryEnds` (product-wide by P6-6):
```sql
SELECT d.id, d.product_id, d.verified_at, d.remediates_id
  FROM work_deployment d
 WHERE d.environment = 'production' AND d.verified_at IS NOT NULL AND d.verified_at >= ?
   AND d.product_id = ANY( ? )
 ORDER BY d.verified_at, d.id
```
- SQL, `shippedChanges` (parameters: `to`, [project], [product], `from`, `to`):
```sql
SELECT x.id, x.first_commit_at, x.opened_at, x.merged_at, x.shipped_at FROM (
  SELECT c.id, c.first_commit_at, c.opened_at, c.closed_at AS merged_at,
         ( SELECT MIN( d.verified_at )
             FROM work_deployment_item di JOIN work_deployment d ON d.id = di.deployment_id
            WHERE di.item_id = c.item_id AND d.product_id = c.product_id
              AND d.environment = 'production' AND d.state = 'verified'
              AND d.verified_at >= c.closed_at ) AS shipped_at
    FROM work_change c JOIN work_item i ON i.id = c.item_id
   WHERE c.state = 'merged' AND c.closed_at < ?
     [ AND i.project_id = ? ] [ AND c.product_id = ? ]
) x
 WHERE x.shipped_at >= ? AND x.shipped_at < ?
 ORDER BY x.shipped_at, x.id
```
- SQL, `merges`:
```sql
SELECT COUNT(*) FILTER ( WHERE c.merge_kind = 'reviewed' )   AS reviewed,
       COUNT(*) FILTER ( WHERE c.merge_kind = 'unreviewed' ) AS unreviewed,
       COUNT(*) FILTER ( WHERE c.merge_kind = 'direct' )     AS direct,
       COUNT(*) FILTER ( WHERE c.policy_flag IS NOT NULL )   AS flagged,
       COUNT(*) FILTER ( WHERE c.policy_flag IS NOT NULL AND c.flag_acknowledged_at IS NULL ) AS flagged_open
  FROM work_change c JOIN work_item i ON i.id = c.item_id
 WHERE c.state = 'merged' AND c.closed_at >= ? AND c.closed_at < ?
   [ AND i.project_id = ? ] [ AND c.product_id = ? ]
```
- `DoraFixtures` (test): `long deploy( WorkStore, Product, List< Item >, Long remediates, Actor )`; `void ran( DataSource, long id, Instant started )`, `verified( …, Instant at )`, `failed( …, Instant at )`, `rolledBack( …, Instant at )` — each moves the deployment through the store, then sets `started_at` / `verified_at` and the matching `work_deployment_event.at` by SQL. `void merged( DataSource, long changeId, Instant mergedAt, Instant firstCommitAt )` sets `closed_at` and `first_commit_at`.

- [ ] **Step 1: Failing tests.** `WorkDoraPostgresTest` (projects A and B, product `web`, plus product `api`; a member of A, a member of B, an admin, mallory; clock fixed 2026-11-21T10:00Z, window 2026-11-02…2026-11-15):
  - `aMemberOfASeesOnlyDeploymentsCarryingAsItems` (X carries A-1, Y carries B-1, both verified 11-03: A frequency 1, B frequency 1).
  - `aDeploymentCarryingAAndBCountsInBoth`.
  - `stagingIsIgnored` (a verified `staging` deployment counts nowhere).
  - `theProductFilterNarrowsAndAnUnknownProductIsNotFound`.
  - `leadTimeRunsToTheFirstVerifiedDeploymentAfterTheMerge` (merge 11-04; one deployment verified 11-03 carrying the item (before the merge), one verified 11-05 then rolled back, one verified 11-07: lead time ends 11-07).
  - `aChangeWithoutCommitTimeFallsBackAndIsCounted`.
  - `aFixForwardThroughTheServiceCountsAsAFailure` (D2 requested through `service.deployments().request` with `remediatesId` = D1: rate 1/2, rework 1/2, recovery D2.verified − D1.verified).
  - `aRejectedRemediationMarksNothing`.
  - `aRemediatorCarryingOnlyBsItemsStillMarksAsDeploymentFailed` (P6-6).
  - `mergeMixCountsKindsAndFlags` (one of each kind; set `policy_flag`, and `flag_acknowledged_at` on one, by SQL: flagged 2, flaggedUnacknowledged 1).
  - `aStrangerGetsNotFound`; `theProductViewIsForWikiAdminsOnly` (member of A and mallory → `WorkDeniedException`; admin sees X and Y: frequency 2).
  - `WorkServiceVisibilityPostgresTest`: add `"dora"` to `READS` and `"doraForProduct"` to `INSTANCE_READS`; in `anInstanceReadRefusesAnyoneWhoIsNotAWikiAdmin`, replace the admin positive control's `new Object[] { admin, "web" }` with an array of `op.method().getParameterCount()` entries holding `admin` and `"web"` first and `null` after (so `productHistory( admin, "web" )` and `doraForProduct( admin, "web", null, null )` are both called).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkDoraPostgresTest,WorkServiceVisibilityPostgresTest'`: FAIL.
- [ ] **Step 3:** Implement the four store methods and the two service methods (the service resolves the window, reads facts, calls `Dora`, and calls `recoveryEnds` with the failing facts' product ids and their earliest start).
- [ ] **Step 4:** Re-run with `DoraTest,WorkFlowPostgresTest,WorkFlowNoStoreRuleTest`: PASS.
- [ ] **Step 5: Commit** (every file above) — `feat(work): DORA report per project and per product for wiki admins`.

### Task 6.4a: REST

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/WorkFlowJson.java`; test `WorkFlowJsonTest.java`
- Modify: `WorkProjectResource.java`, `WorkProductResource.java`, `WorkProjectResourceTest.java`, `WorkProductResourceTest.java`

**Interfaces — produces:**
- `GET /api/work/projects/{key}/flow?from=&to=&sprint=&label=&type=&assignee=` → 200 `WorkFlowJson.flow( FlowReport )`.
- `GET /api/work/projects/{key}/dora?from=&to=&product=` → 200 `WorkFlowJson.dora( DoraReport )`.
- `GET /api/work/products/{key}/dora?from=&to=` → 200 for a wiki admin, 403 otherwise (`WorkDeniedException`), 404 for an unknown product.
- Parameter errors (400 with field): `from`/`to` not `yyyy-MM-dd` → `Use a date like 2026-11-21.`; `sprint` not digits → `A sprint is named by its number.`; `type` through `WorkType.fromWire` (field `type`).
- `WorkFlowJson` (final, static; durations as whole seconds, `null` when `n` is 0):
  - flow: `{"from","to","zone","throughput":[{"weekStart","items","points"}],"cycleTime":{"n","p50Seconds","p85Seconds"},"leadTime":{…},"wip":[{"status","count","items":[{"key","title","ageSeconds"}]}]}`
  - dora: `{"from","to","zone","product","deploymentFrequency":[{"weekStart","deployments"}],"leadTimeForChanges":{…},"changesWithoutCommitTime","changeFailureRate":{"numerator","denominator"},"recoveryTime":{…},"unrecovered","reworkRate":{"numerator","denominator"},"merges":{"reviewed","unreviewed","direct","flagged","flaggedUnacknowledged"}}`
  - `static LocalDate date( String field, String raw )`, `static Long sprint( String raw )`.

- [ ] **Step 1: Failing tests.** `WorkFlowJsonTest`: `durationsAreSecondsAndNullWhenEmpty`, `aWeekWithoutPointsWritesZero`, `doraRatesKeepNumeratorAndDenominator`. `WorkProjectResourceTest` (mock `svc.flow()` returning a mocked `WorkFlowService`): `flowPassesEveryFilter` (captor on `FlowQuery`: dates, sprint 7, label, `WorkType.BUG`, assignee), `aBadDateIs400NamingIt`, `aBadSprintIs400`, `flowForAStrangerIs404` (service throws `WorkNotFoundException`), `doraPassesTheProduct`. `WorkProductResourceTest`: `productDoraForANonAdminIs403`, `productDoraForAnAdminIs200`.
- [ ] **Step 2:** `mvn install -pl wikantik-work -DskipTests -q && mvn test -pl wikantik-rest -Dtest='WorkFlowJsonTest,WorkProjectResourceTest,WorkProductResourceTest'`: FAIL.
- [ ] **Step 3:** Implement the three routes and `WorkFlowJson`. No `web.xml` change (both servlets already own these paths).
- [ ] **Step 4:** Re-run: PASS. **Commit** the six files — `feat(work): flow and DORA routes`.

### Task 6.4b: Shared chart primitives

**Files:**
- Create in `wikantik-frontend/src/components/work/charts/`: `chartScale.js` (+ `chartScale.test.js`), `ChartFrame.jsx`, `BarSeries.jsx` (+ `BarSeries.test.jsx`), `StatTile.jsx` (+ `StatTile.test.jsx`)
- Modify: `wikantik-frontend/src/components/work/BurndownChart.jsx` (Part 2) to use `chartScale` and `ChartFrame` if it holds its own copies; `styles/work.css` (`.work-chart-*`, `.work-stat-*`)

**Interfaces — produces:**
- `chartScale.js`: `linearScale( [d0, d1], [r0, r1] ) : (v) => number`; `niceTicks( max, count = 4 ) : number[]` (0 first, steps of 1, 2 or 5 × 10^k, last ≥ max; `max` 0 → `[0, 1]`).
- `ChartFrame({ title, description, width = 640, height = 200, yTicks, children })`: `<svg role="img" viewBox=… aria-labelledby>` with `<title>`, `<desc>`, horizontal gridlines and y labels; children draw inside the plot area through a render prop `({ x0, x1, y0, y1 })`.
- `BarSeries({ data: [{ label, value, muted }], title, description, format })`: one `<rect>` per datum (zero values drawn as a 1px baseline mark), a label every n-th bar so labels never overlap at 52 bars, `muted` bars (partial weeks) in the secondary token.
- `StatTile({ label, value, note })`: HTML, not SVG: the value large, the label, the note small.
- Every colour is a token defined in `globals.css`.

- [ ] **Step 1: Failing vitest.** `chartScale.test.js` (`niceTicks(0)` → `[0,1]`; `niceTicks(7)` → `[0,2,4,6,8]`; `niceTicks(0.5, 4)` ends ≥ 0.5; `linearScale([0,10],[100,0])(5)` → 50). `BarSeries.test.jsx` (12 data → 12 bars; a zero value still draws a mark; `<title>` text is the title; 52 data → fewer than 52 labels). `StatTile.test.jsx` (renders value, label and note). If `BurndownChart.jsx` was refactored, its existing test must stay green unchanged.
- [ ] **Step 2:** `cd wikantik-frontend && npx vitest run src/components/work/charts`: FAIL.
- [ ] **Step 3:** Implement; run `npx vitest run src/components/work/charts src/components/work/BurndownChart.test.jsx src/styles/tokensUsage.test.js`: PASS; `npm run lint`.
- [ ] **Step 4: Commit** the files — `feat(work-ui): shared inline-SVG chart primitives`.

### Task 6.4c: The flow page, the product view and the commit-time field

**Files:**
- Create: `wikantik-frontend/src/components/work/WorkFlow.jsx` (+ `WorkFlow.test.jsx`), `DoraPanel.jsx` (+ `DoraPanel.test.jsx`), `WorkProductDora.jsx` (+ `WorkProductDora.test.jsx`), `flowModel.js` (+ `flowModel.test.js`)
- Modify: `wikantik-frontend/src/main.jsx` (routes), `components/work/WorkLayout.jsx` (`TABS`), `WorkProducts.jsx` (admin link), `WorkChanges.jsx` (+ test), `api/work.js`

**Interfaces — produces:**
- `api/work.js`: `getProjectFlow( key, params )` → `GET …/projects/{key}/flow${query(params)}`; `getProjectDora( key, params )`; `getProductDora( key, params )`.
- Routes: `<Route path="flow" element={<WorkFlow />} />` under `:project`; `<Route path="products/:product/dora" element={<WorkProductDora />} />` under `/work`. Tab `{ id: 'flow', label: 'Flow' }` after `dates`, shown to every role.
- `flowModel.js`: `durationText( seconds )` (`"—"` for null, else `"45 min"`, `"5.5 h"`, `"3.2 d"`); `percent( { numerator, denominator } )` (`"—"` when the denominator is 0, else `"57%"`); `partialWeek( weekStart, from, to )` (true when the week starts before `from` or ends after `to`); `weekLabel( iso )` (`"16 Nov"`).
- `WorkFlow`: filter bar (from, to, sprint: Part 2's sprint list for the project, label, type, assignee) kept in the URL query; sections: throughput (`BarSeries`, partial weeks muted), cycle and lead time (`StatTile` p50 with p85 and n in the note), WIP (two lists, item key links to `/work/item/<key>`, age via `durationText`), sprint figures when the project has a sprint (Part 2's velocity chart data; with a sprint chosen, its `BurndownChart` from Part 2's report), and `DoraPanel` with a product select.
- `DoraPanel({ report })`: frequency (`BarSeries`), lead time for changes with the note **"N changes had no commit time; their lead time starts when the change was opened."** when `changesWithoutCommitTime > 0`, change failure rate, recovery time with "N not yet recovered" when `unrecovered > 0`, rework rate, and the merge mix (reviewed, unreviewed, direct, flagged, of which not acknowledged).
- `WorkProductDora`: admins only (same check and redirect as `WorkProducts`), product key from the route, date filter, `DoraPanel`. `WorkProducts` shows "Delivery figures" per product linking to it.
- `WorkChanges`: the open form gains "First commit (optional)" (`<input type="datetime-local">`), sent as `firstCommitAt: new Date(value).toISOString()` or omitted; a change row shows "first commit <time>" when `firstCommitAt` is present and Part 5 did not already show it.

- [ ] **Step 1: Failing vitest.** `flowModel.test.js` (each function's cases above). `WorkFlow.test.jsx`: "requests the flow with the filters from the URL"; "mutes partial weeks"; "shows the no-commit-time note only when some changes fell back"; "hides the sprint section for a project with no sprint"; "a 404 shows Not found". `DoraPanel.test.jsx`: "shows — for a rate over zero deployments"; "names the unrecovered failures". `WorkProductDora.test.jsx`: "a non-admin is sent to /work"; "an admin sees the product's figures". `WorkChanges.test.jsx`: "sends firstCommitAt as an ISO instant when given" and "omits it when blank".
- [ ] **Step 2:** `cd wikantik-frontend && npx vitest run src/components/work/WorkFlow.test.jsx src/components/work/DoraPanel.test.jsx src/components/work/WorkProductDora.test.jsx src/components/work/flowModel.test.js src/components/work/WorkChanges.test.jsx`: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Re-run: PASS. `npx vitest run src/components/work` (the layout test sees the new tab); `npm run lint`.
- [ ] **Step 5: Commit** (every file above) — `feat(work-ui): flow and delivery figures page`.

### Task 6.4d: Gate and docs

- [ ] **Docs.**
  - `docs/user/WorkManagement.md`: new section "Flow and delivery figures" with every definition from Decisions P6-1 to P6-8 and P6-12/13 in plain words: closed = last move to done while still done; cycle time from the first move to in progress; lead time from creation; weeks Monday 00:00 in the zone the page names; WIP and its ages; the five DORA keys exactly as the design's table, with the remediation rule, the recovery rule, the fallback for lead time and the merge mix; how to record a fix-forward (`Remediates` when requesting); filters use current values; a member of a project sees deployments carrying that project's items, an admin sees a whole product.
  - `docs/admin/WikantikOperations.md` section 1.14: two sentences — the figures are product figures computed at read time from the work tables; no table, cache or Prometheus series carries them, and none may be added (`WorkFlowNoStoreRuleTest`); link to the user section.
  - `docs/admin/McpAgents.md`: `request_deployment`'s `remediates` argument.
  - `wikantik-work/CLAUDE.md`: a "Flow and DORA metrics" section: the facade `flow()` (`WorkFlowService`, not `WorkMetrics`); `JdbcWorkFlow` is SELECT-only and returns facts, `Flow`/`Dora`/`Percentiles`/`MetricWindow` compute; no metrics store (rule test); the zone is the service clock's; failure and rollback times come from `work_deployment_event` because `closed_at` is overwritten; remediators are product-wide; lead time reuses `shippedVerified`'s conditions with the change's own product. Add V089 to the migrations line.
  - `docs/developer/ProjectReference.md`, "Work management — status": one bullet for this part (V089, facade, routes, page).
  - `CHANGELOG.md` `[Unreleased]` → `### Added`: flow and DORA figures at `/work/<project>/flow`, the admin product view, and remediating deployments.
- [ ] **Gate.** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`, then poll `bin/agent-build.sh status gate` / `wait gate 540` until SUCCESS. Read the per-module summaries, not the last line only.
- [ ] **Commit** `git add docs/user/WorkManagement.md docs/admin/WikantikOperations.md docs/admin/McpAgents.md wikantik-work/CLAUDE.md docs/developer/ProjectReference.md CHANGELOG.md` — `docs(work): flow and DORA figures and how they are defined`.

## Deviations from the outline

1. **No `WorkFlowResource` servlet.** `/api/work/projects/*` and `/api/work/products/*` already belong to one servlet each and a servlet pattern cannot match `projects/*/flow`. The routes go into `WorkProjectResource` and `WorkProductResource`, with the JSON in `WorkFlowJson`, as `change-counts` does.
2. **`doraForProduct` refuses a non-admin with `WorkDeniedException` (403), not not-found**, and takes `(Actor, String productKey, LocalDate from, LocalDate to)`. That is the module's existing rule for instance reads (`productHistory`, `INSTANCE_READS`), whose test asserts the denial; the product key is not confidential to someone in a project.
3. **Report shapes.** `FlowReport` drops `velocity` (P6-9) and gains `from`, `to`, `zone`; `Wip` lists each item with its age (the design asks for it) instead of only the oldest age; deployment frequency uses `WeekCount` (a week of deployments has no points); `DoraReport` gains `unrecovered` and `productKey`; `Durations` is a top-level record shared by both.
4. **Recovery end.** The outline used the next verified deployment only when there was neither rollback nor remediation; this plan takes the earliest of all candidates and allows the "next verified" candidate only for a deployment that never went live (P6-7), so an unrelated later deploy never ends the recovery of a live failure.
5. **"Not itself"** is a table CHECK, not a service test: at request time the new deployment has no id.
6. **The reporter case** in the outline's 6.2 test belongs to Part 8, which comes later (D13); `WorkFlowService` uses `scope` + `require( Action.VIEW, null )` like `board`, so Part 8's handling of `board` covers it.
7. **Definitions** go to `docs/user/WorkManagement.md` (where users read about the work UI), with a short pointer in the operations handbook.
8. **Charts.** Besides the outline's `BarSeries` and `StatTile`, `chartScale.js` and `ChartFrame.jsx` are named as the shared primitives, and `BurndownChart.jsx` (Part 2) is moved onto them if it has its own copies.
