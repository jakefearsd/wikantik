# Work Management for Small Teams — Part 4: Find and saved filters — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One query over every project a person can see, a `/work/find` page whose URL is the filter, saved filters that can be shared with a project's members, and three built-in views on work home.
**Architecture:** A new facade `WorkService.features().find()` (`WorkFindService`) resolves a `FindFilter` against the caller's visible projects (from `WorkStanding`) and runs one statement with `project_id = ANY(?)`, as the assignment log does, paging on a `(sort value, id)` cursor. Saved filters are a preference table (`work_saved_filter`, V087) holding the filter's URL form; a shared filter shares the question and is always run for whoever opens it. `GET /api/work/items` becomes a find when no `project` is given; with `project` it is unchanged.
**Tech Stack:** Java 25, PostgreSQL 18 (`Jdbc`, `PostgresTestDb`), JUnit 5, Mockito, Gson, React 19 + vitest, Cargo ITs.
**Spec:** docs/superpowers/specs/2026-11-21-work-team-features-design.md, section 4; index and global constraints: docs/superpowers/plans/2026-11-21-work-team-features.md
**Depends on:** Parts 1, 2 and 3 for three filter fields each; nothing else. Exactly what is used:
- **Part 1** — `enum WorkType implements WireEnum { FEATURE, BUG, CHORE }` with `fromWire(String)`; `Labels.normalise(Collection<String>) : List<String>` (throws `WorkValidationException` field `labels`); columns `work_item.type`, `work_item.labels TEXT[]`; `Item.type()`, `Item.labels()`. Gives the `type` and `label` filters.
- **Part 2** — the sprint reference type the outline calls `SprintRef` (a sprint id, `active` or `none`) and its parser for `sprint=`; the `ItemFilter`/`ItemQuery` sprint clause; columns `work_item.sprint_id`, `work_sprint.state`. Gives the `sprint` filter. If Part 2 as built named the type or parser differently, use its names; the wire form `sprint=<id>|active|none` is what this part relies on.
- **Part 3** — table `work_watch(item_id, login, state)` with state `'watching'` or `'muted'`. Gives the `watching` filter and the "Watching" view.

**Leaving a field out.** Every step below that touches a part-specific field is tagged `[P1]`, `[P2]` or `[P3]`. If that part is not built, drop the tagged field from `FindFilter`, `FindParams`, `FindSpec`, `FindQuery`, `findParams.js`, `WorkFilterBar.jsx` and the tagged tests, and add it back in that part's own run.

## Cross-part rulings

Added when the eight part plans were reconciled (index: `docs/superpowers/plans/2026-11-21-work-team-features.md`). **Where a ruling and the text below disagree, the ruling wins.**

- **R1 — new facades hang off `WorkService.features()`, not `WorkService`.** `WorkService` is at the PMD `TooManyMethods` limit (40). Part 1 creates `com.wikantik.work.WorkFeatures` (built in `WorkService`'s constructor, returned by the one new method `WorkService.features()`); every facade accessor this plan calls `WorkService.x()` is `WorkService.features().x()`. `WorkServiceVisibilityPostgresTest` treats `WorkFeatures`' accessors as facades (its `FACADES` set). No suppression, no baseline entry.
- **R3 — reserved project keys.** `FIND` goes in `WorkInputs.RESERVED_KEYS` (P4-14); nothing else reserves it.
- **R11 — names from other parts are confirmed:** Part 2's `SprintRef( Kind kind, Long sprintId )` with `SprintRef.parse`; Part 1's label filter accepts repeated and comma-separated values; Part 3's table is `work_watch(item_id, login, state)`.

## What this part builds on

Verified in the code on 2026-11-21:

- **Visibility.** `WorkStanding.visibleProjects(Actor) : List<Project>` (`wikantik-work/src/main/java/com/wikantik/work/WorkStanding.java`), reached as `scopes.standing()`; for an agent it is its own projects that its sponsor can also see. `WorkStandingSourceRuleTest` forbids any other class calling `store.visibleProjects(` or `store.roleFor(`.
- **The model to copy.** `WorkService.assignmentLog` → `AssignmentLogs.read(store, query, visible)` → `JdbcWorkEventLog.eventsAcross(...)`: the visible project ids are passed as `e.project_id = ANY( ? )` (a `bigint[]`) in the one statement, newest first, cursor `(at, id)` written as a row comparison. A named project the caller cannot see is filtered out before the query.
- **Today's item list.** `GET /api/work/items` in `wikantik-rest/src/main/java/com/wikantik/rest/WorkItemResource.java` requires `project` (400 field `project`, pinned by `WorkItemResourceTest.projectIsRequiredForTheList`) and answers a bare JSON array from `WorkService.itemSummaries(actor, projectKey, ItemFilter)`, built by `WorkSummaries.list` (each item's fields plus `commentCount`, `blocked`, `productKey`, `parentKey`). Parameters (`WorkRequests.filter`): `status` (comma list), `assignee`, `product`, `level` (one), `parent`, `q`, `dueBefore`, `order` (`key|due`), `queue`, `limit` (clamped to 200 by `WorkResourceBase.clampLimit`). Parts 1 and 2 add `type`, `label`, `sprint`. Frontend callers (`listItems` in `wikantik-frontend/src/api/work.js`): `WorkItemView.jsx:200`, `WorkEpicView.jsx:25,34`, `WorkDates.jsx:31`, `WorkItemForm.jsx:39`; all pass `project`. The client's `query()` joins arrays with commas.
- **Filter SQL.** `ItemQuery.conditions` (`ItemQuery.java`); its private `text(...)` does the escaped `ILIKE` on title and body. `JdbcWorkStore.ITEM_SELECT` / `ITEM_ROW` are package-visible statics.
- **Card extras.** `ItemViews.summarise(long projectId, List<Item>)` → `WorkStore.itemExtras(projectId, ids)`, one project per call.
- **People.** `WorkPeople.login(Actor, String ref) : Optional<String>` (a display name for anyone, a login only for a wiki admin); `ItemViews.pinned` maps an unresolvable assignee to the sentinel `"\n"` so the filter matches nothing. REST shows people by display name through `WorkJson.enrich` (`NAME_KEYS` include `assignee`, `reporter`).
- **Agents by id.** `Actor(login, groups, admin, kind, agentId, sponsor)`; `work_item.claimed_by_agent`, `work_item.reporter_agent_id` (module rule: compare agents by id).
- **Classification.** `WorkServiceVisibilityPostgresTest`: `FACADES`, `READS`, `WRITES`; `argumentSets` feeds `null` for unknown parameter types; `isEmptyResult` treats only a `Collection` as empty.
- **Policy table.** `Action` enum; `WorkPolicyActionTableTest` compares every `Action.values()` row to `wikantik-work/src/test/resources/com/wikantik/work/policy-action-table.golden`.
- **Reserved keys.** `WorkInputs.RESERVED_KEYS = Set.of("ITEM", "PRODUCTS", "AGENTS", "LOG")`, tested in `WorkInputEdgesTest`.
- **Unique-violation precedent.** `JdbcWorkChanges.UNIQUE_VIOLATION = "23505"`; conflict reason `WorkConflictException.EXISTS`.
- **Test truncation.** `WorkAgentFixtures.TABLES` with `TRUNCATE … CASCADE`: a row with no foreign key to a truncated table survives unless its table is listed.
- **Store accessors.** `WorkStore.changes()`, `deployments()` returning sub-stores built in the `JdbcWorkStore` constructor.
- **Arch rule.** `WorkAccessArchTest.only_the_work_packages_touch_the_store` lists the store interfaces REST may not touch.

## Decisions

- **P4-1. `GET /api/work/items` has two modes, chosen by `project`.** With `project`: today's list, same parameters, same bare-array response; the find-only parameters (`projects`, `priority`, `reporter`, `watching`, `updatedSince`, `sort`, `cursor`) are refused 400 by name. Without `project` (today a 400): a find answering `{items, next}`; the list-only `parent` and `order` are refused 400 by name. Reason: the outline names this route, and no current caller omits `project`. Cost if wrong: an alias `GET /api/work/find` is one route.
- **P4-2. The filter's wire form is its URL query**: parameter name → string, lists comma-joined. Saved filters store that map as JSONB, and `FindParams` is the one parser for both. Multi-valued parameters also accept repeats (`label=a&label=b`), joined before parsing. Cost: none known.
- **P4-3. `me` is resolved when the query runs, never when it is saved.** For a person it is their login. For an agent, `assignee=me` means items whose claim it holds, `reporter=me` items it created (`reporter_agent_id`), and `watching=true` matches nothing (agents do not watch). A person whose display name is exactly `me` cannot be named in a filter. Cost: one rename.
- **P4-4. A name that resolves to nobody matches nothing** (a person, a product key), as the board filter does today, so a find never says which names exist. At save time an unresolvable person is refused (field `assignee`/`reporter`), so a stored filter never holds an unresolved name.
- **P4-5. A shared filter searches its own project only.** On save, `projects` absent is set to the scope project's key; any other value is refused (field `projects`). Reason: a stored question naming another project would show its key to members who cannot see it, and a project's existence is confidential.
- **P4-6. Saved filters are people's.** An agent's `saved` is empty and its writes are `WorkDeniedException` "Saved filters belong to people." (asked of every agent alike, so it reveals nothing about a project). No MCP tool in this part.
- **P4-7. Saved-filter writes are single statements and write no `work_event`**: they are preferences, like Part 3's watches, and Part 3's dispatcher reads `work_event` as an outbox. So there is no rollback test in this part. Cost if wrong: a project `settings` event with note `filter` on share and delete.
- **P4-8. Who may do what to a saved filter.** Save private: anyone (a project scope needs `VIEW`). Share: new `Action.SHARE_FILTER(WorkRole.MEMBER, false, false)`, project not archived. Rename: the owner only. Delete: the owner, or for a shared filter `MANAGE_PROJECT` of its project (a lead) or a wiki admin. A private filter is seen by its owner only, admins included. Rename and delete still work in an archived project (cleanup).
- **P4-9. Sorts:** `updated` (default) and `created` newest first, `priority` 1 first, `due` soonest first with no date last (`COALESCE(i.due_on, DATE '9999-12-31')`). Ties break on `i.id` in the same direction. The cursor is opaque (base64url of `sort|value|id`) and belongs to one sort.
- **P4-10. Find covers archived projects too**, as `assignmentLog` and `listProjectsWithRoles` do.
- **P4-11. `updatedSince`** takes a date (00:00 UTC that day) or an ISO instant; `dueBefore` a date, as today; `q` is at most 200 characters, title and body as today. `limit` 0 or absent means 50; above 200 is clamped to 200, as `clampLimit` does.
- **P4-12. Home loads saved filters from `GET /api/work/filters`; `/api/work/me` (`WorkMeResource`) is not changed.** Reason: the list route exists for the CRUD anyway, and every mock-based `/me` test (`WorkMeResourceTest`, `WorkDeploymentResourceTest`) would otherwise need a `find()` stub. Cost: one request more on home.
- **P4-13. The built-in views show open work** (`draft`, `refining`, `ready`, `in_progress`, `review`), newest updated first; the bar widens them.
- **P4-14. `FIND` becomes a reserved project key.** React Router matches `/work/find` case-insensitively, so a project `FIND` would lose its board.

## Review Focus

1. **Find returning an item from a project the caller cannot see**: named in `projects`, reached by an agent whose sponsor lacks standing, or asked by a stranger. Task 4.1c (`WorkFindPostgresTest`, `WorkServiceVisibilityPostgresTest`).
2. **A shared filter leaking**: listed to a non-member, its stored question naming a hidden project, or run for its saver instead of its runner (`me`). Task 4.2c.
3. **Paging that loses or repeats rows** when many items share the sort value, or accepts another sort's cursor. Tasks 4.1a, 4.1b.
4. **Breaking today's callers of `GET /api/work/items`.** Task 4.3a keeps `listsItemsOfAProject` and adds the refusal pins.
5. **A login reaching a non-admin** through a saved filter's people or owner. Task 4.3b.
6. **`/work/find` swallowing a project's board.** Task 4.1c (reserved key).

---

### Task 4.1a: The filter, its wire form and the cursor

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/FindFilter.java`, `FindParams.java`, `FindCursor.java` (same directory)
- Test: `wikantik-work/src/test/java/com/wikantik/work/FindParamsTest.java`, `FindCursorTest.java`

**Interfaces — produces:**
```java
public record FindFilter( Set< String > projectKeys, Set< WorkStatus > statuses, Set< WorkLevel > levels,
                          WorkType type /*[P1]*/, Set< String > labels /*[P1]*/, Set< Integer > priorities,
                          String assignee, String queue, String reporter, String productKey,
                          SprintRef sprint /*[P2]*/, boolean watching /*[P3]*/, String text, LocalDate dueBefore,
                          Instant updatedSince, Sort sort, int limit, String cursor ) {
    public static final String ME = "me";
    public enum Sort implements WireEnum { UPDATED( "updated" ), PRIORITY( "priority" ), DUE( "due" ), CREATED( "created" );
        public static Sort fromWire( String v ) /* WireEnum.parse( Sort.class, v, "sort" ) */ }
    public static FindFilter all();                              // nothing set, sort UPDATED, limit 0, cursor null
    public FindFilter page( String cursor, int limit );          // copy with another cursor and limit
}
```
The compact constructor turns null sets into empty immutable copies and a null sort into `UPDATED`.

`public final class FindParams` (static): `Set<String> NAMES`; `Set<String> FIND_ONLY = Set.of("projects","priority","reporter","watching","updatedSince","sort","cursor")`; `FindFilter parse(Map<String,String> params)`; `SortedMap<String,String> toParams(FindFilter f)` (canonical: empty fields, `limit` and `cursor` left out, lists sorted and comma-joined). A blank value counts as absent. One private method per parameter (PMD: no method over CyclomaticComplexity 15).

| Parameter | Field | Accepts | Refused as (field: message) |
|---|---|---|---|
| `projects` | `projectKeys` | comma list of `[A-Za-z][A-Za-z0-9]{1,9}`, at most 50, upper-cased | `projects`: "projects must be project keys such as PLAT,OPS." |
| `status` | `statuses` | `WorkStatus` wire values | `status`: WireEnum's "Unknown status: x" |
| `level` | `levels` | `WorkLevel` wire values | `level`: "Unknown level: x" |
| `type` [P1] | `type` | one `WorkType` | `type`: "Unknown type: x" |
| `label` [P1] | `labels` | `Labels.normalise` | `label`: Part 1's message (rethrown with field `label`) |
| `priority` | `priorities` | integers 1–4 | `priority`: "priority must be 1 to 4." |
| `assignee`, `reporter` | same | `me`, a display name, or (admin) a login; at most 100 chars | the parameter: "<name> is at most 100 characters." |
| `queue`, `product` | `queue`, `productKey` | text, at most 64 chars | the parameter: "<name> is at most 64 characters." |
| `sprint` [P2] | `sprint` | `active`, `none`, a positive id (Part 2's parser) | `sprint`: Part 2's message |
| `watching` [P3] | `watching` | `true` or `false` | `watching`: "watching must be true or false." |
| `q` | `text` | at most 200 chars | `q`: "q is at most 200 characters." |
| `dueBefore` | `dueBefore` | ISO date | `dueBefore`: "dueBefore must be a date such as 2026-12-31." |
| `updatedSince` | `updatedSince` | ISO date (00:00 UTC) or ISO instant | `updatedSince`: "updatedSince must be a date such as 2026-11-01 or an instant." |
| `sort` | `sort` | `updated`, `priority`, `due`, `created` | `sort`: "Unknown sort: x" |
| `limit` | `limit` | a whole number ≥ 0 | `limit`: "limit must be a whole number." |
| `cursor` | `cursor` | at most 200 chars, checked by `FindCursor` | `cursor`: see below |
| anything else | — | — | the name: "Unknown filter field." |

`record FindCursor( FindFilter.Sort sort, String value, long id )` (package-private): `static final LocalDate NO_DUE = LocalDate.of( 9999, 12, 31 )`; `static FindCursor after( Item last, FindFilter.Sort sort )` (value = `updatedAt`/`createdAt` as `Instant.toString()`, `priority`, or `dueOn` else `NO_DUE`); `String encode()` (URL-safe Base64, no padding, of `sort.wire() + "|" + value + "|" + id`); `static FindCursor decode( String cursor, FindFilter.Sort sort )` throwing `WorkValidationException( "cursor", "This page marker does not belong to this search." )` on bad Base64, wrong part count, another sort, an unparseable value or id; `Object sqlValue()` (`Timestamp`, `Integer` or `java.sql.Date`).

- [ ] **Step 1: Failing tests.** `FindParamsTest`: `parsesEveryParameter` (one map holding all names → each field); `listsAreCommaSeparatedAndTrimmed` (`"ready, review"`); `projectKeysAreUpperCased` (`plat` → `PLAT`); `blankMeansAbsent`; `eachBadValueNamesItsParameter` (one row per refusal row of the table, asserting the field); `anUnknownParameterIsRefusedByName` (`colour`); `updatedSinceTakesADateOrAnInstant` (`2026-11-01` → `2026-11-01T00:00:00Z`; `2026-11-01T09:30:00Z` kept); `toParamsRoundTripsEveryField` (`parse(toParams(f))` equals `f.page(null, 0)` for an `f` with every field set); `toParamsLeavesOutEmptiesLimitAndCursor`. `FindCursorTest`: `roundTripsForEverySort` (an item with micro-second `updatedAt` `2026-11-21T10:00:00.123456Z`); `aCursorOfAnotherSortIsRefused`; `garbageIsRefused` (`"%%%"`, Base64 of `"updated|x"`, Base64 of `"updated|2026-11-21T10:00:00Z|abc"`), each asserting field `cursor`; `noDueDateSortsLast` (`after` of an item with no due date carries `9999-12-31`).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='FindParamsTest,FindCursorTest'` — expect a compilation failure.
- [ ] **Step 3:** Implement the three classes.
- [ ] **Step 4:** Re-run: PASS.
- [ ] **Step 5: Commit** `git add wikantik-work/src/main/java/com/wikantik/work/FindFilter.java wikantik-work/src/main/java/com/wikantik/work/FindParams.java wikantik-work/src/main/java/com/wikantik/work/FindCursor.java wikantik-work/src/test/java/com/wikantik/work/FindParamsTest.java wikantik-work/src/test/java/com/wikantik/work/FindCursorTest.java` — `feat(work): find filter, its URL form and the page cursor`.

### Task 4.1b: The cross-project statement

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/FindSpec.java`, `FindQuery.java`, `WorkFindStore.java`, `JdbcWorkFind.java`
- Modify: `WorkStore.java` (`WorkFindStore finder()`), `JdbcWorkStore.java` (field built in the constructor, accessor), `ItemQuery.java` (`text(...)` becomes package-private static; [P2] the sprint clause becomes package-private static `sprint( SprintRef, StringBuilder, List<Object> )` so both queries share it)
- Test: `wikantik-work/src/test/java/com/wikantik/work/JdbcWorkFindPostgresTest.java`

**Interfaces — produces:**
```java
record FindSpec( Set< Long > projectIds, Set< WorkStatus > statuses, Set< WorkLevel > levels, WorkType type,
                 Set< String > labels, Set< Integer > priorities, String assigneeLogin, Long assigneeAgentId,
                 String queue, String reporterLogin, Long reporterAgentId, Long productId, SprintRef sprint,
                 String watcherLogin, String text, LocalDate dueBefore, Instant updatedSince,
                 FindFilter.Sort sort, int fetch, FindCursor after ) { }
public interface WorkFindStore { List< Item > find( FindSpec spec ) throws SQLException; }
```
`find` returns at most `spec.fetch()` rows and runs no query when `projectIds` is empty. `FindQuery.build( FindSpec, Connection )` returns the SQL and parameters. The statement's shape matters (visibility is a predicate of this one statement):

```
JdbcWorkStore.ITEM_SELECT + " WHERE i.project_id = ANY( ? )"          -- bigint[] of visible, named projects
  [ AND i.status = ANY( ? ) ]                                          -- text[]
  [ AND i.level = ANY( ? ) ]                                           -- text[]
  [ AND i.type = ? ]                                    [P1]
  [ AND i.labels @> ?::text[] ]                         [P1]
  [ AND i.priority = ANY( ? ) ]                                        -- int[]
  [ AND i.assignee = ? ]  |  [ AND i.claimed_by_agent = ? ]
  [ AND i.assignee_queue = ? ]
  [ AND i.reporter = ? AND i.reporter_agent_id IS NULL ]  |  [ AND i.reporter_agent_id = ? ]
  [ AND i.product_id = ? ]
  [ ItemQuery.sprint clause ]                           [P2]   -- id: i.sprint_id = ?; active: i.sprint_id IN
                                                               --   (SELECT s.id FROM work_sprint s WHERE s.state = 'active'); none: IS NULL
  [ AND EXISTS ( SELECT 1 FROM work_watch w WHERE w.item_id = i.id AND w.login = ? AND w.state = 'watching' ) ]  [P3]
  [ ItemQuery.text clause ]
  [ AND i.due_on < ? ]   [ AND i.updated_at >= ? ]
  [ AND ( <expr>, i.id ) < ( ?, ? ) ]  for UPDATED, CREATED;   [ AND ( <expr>, i.id ) > ( ?, ? ) ]  for PRIORITY, DUE
ORDER BY <expr> <dir>, i.id <dir> LIMIT ?
```
`<expr>`: `i.updated_at` DESC, `i.created_at` DESC, `i.priority` ASC, `COALESCE( i.due_on, DATE '9999-12-31' )` ASC.

- [ ] **Step 1: Failing tests** in `JdbcWorkFindPostgresTest` (setUp as `JdbcWorkStoreAssignmentLogPostgresTest`: projects PLAT and OPS; `updated_at`, `created_at`, `claimed_by_agent` set by direct SQL through `WorkAgentFixtures.exec` where a test needs them). Each builds a `FindSpec` directly: `onlyTheGivenProjectsAreSearched`; `noProjectsRunsNoQueryAndFindsNothing`; one method per filter alone — `statusesNarrow`, `levelsNarrow`, `typeNarrows` [P1], `labelsMustAllBeCarried` [P1] (items `[ui]`, `[ui,billing]`, `[billing]`; asking `ui,billing` returns the second only), `prioritiesNarrow`, `assigneeLoginNarrows`, `assigneeAgentMatchesTheClaimHolder`, `queueNarrows`, `reporterLoginIgnoresAgentCreatedItems`, `reporterAgentMatchesById`, `productNarrows`, `sprintIdActiveAndNone` [P2], `watchingKeepsWatchedNotMuted` [P3], `textMatchesTitleOrBodyAndEscapesWildcards` (`50%` does not match `500`), `dueBeforeLeavesOutUndated`, `updatedSinceIsInclusive`; `twoFiltersCombine` (status and assignee); `eachSortOrdersAsDocumented` (four orders, `due` with an undated item last); `pagingIsStableWhenRowsTie` — 7 items with one `updated_at`, `created_at`, `priority` and no due date; for each sort, pages of 3 by `FindCursor.after(last, sort)` give 3, 3, 1 rows, 7 distinct ids, none repeated.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=JdbcWorkFindPostgresTest` — expect a compilation failure.
- [ ] **Step 3:** Implement `FindSpec`, `FindQuery`, `WorkFindStore`, `JdbcWorkFind`; wire `finder()`.
- [ ] **Step 4:** Re-run: PASS. `mvn test -pl wikantik-work -Dtest='JdbcWorkStoreItemsPostgresTest,JdbcWorkStoreAssignmentLogPostgresTest'`: PASS (the `ItemQuery` methods they use changed visibility).
- [ ] **Step 5: Commit** `git add` the four new main files, `WorkStore.java`, `JdbcWorkStore.java`, `ItemQuery.java`, `JdbcWorkFindPostgresTest.java` — `feat(work): one statement finds items across visible projects`.

### Task 4.1c: The find facade

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/WorkFindService.java`, `FindPage.java`, `FindSpecs.java`
- Modify: `WorkService.java` (field built in the full constructor; `public WorkFindService find()`), `ItemViews.java` (`summariseAcross`), `WorkInputs.java` (`RESERVED_KEYS` gains `"FIND"`)
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkFindPostgresTest.java`; modify `WorkServiceVisibilityPostgresTest.java`, `WorkInputEdgesTest.java`

**Interfaces — consumes:** `WorkFindStore.find(FindSpec)`, `FindCursor`, `FindParams` (4.1a–b); `scopes.standing().visibleProjects(Actor)`; `WorkPeople.login(Actor, String)`; `store.products()`.

**Produces:**
- `public record FindPage( List< WorkService.ItemSummary > items, String next ) { public static final FindPage EMPTY = new FindPage( List.of(), null ); }`
- `public final class WorkFindService` with `WorkFindService( WorkStore store, WorkScopes scopes, WorkPeople people, ItemViews views )` and `public FindPage find( Actor actor, FindFilter filter ) throws SQLException`.
- `final class FindSpecs` (static): `Optional< FindSpec > resolve( Actor actor, FindFilter f, List< Project > visible, List< Product > products, WorkPeople people )`; empty means "matches nothing". In order: decode the cursor (so its refusal is the same for everyone); covered = visible projects whose key is in `projectKeys` (ignoring case), or all visible when none named; none → empty. `assignee`/`reporter`: `me` → the login, or for an agent its id (P4-3); otherwise `people.login(actor, ref)`, empty → empty. `productKey` → the product's id (exact key, as `WorkRequests.filter` does), unknown → empty. [P3] `watching` → the login; for an agent → empty. Limit: ≤ 0 → 50, > 200 → 200; `fetch = limit + 1`.
- `find`: `resolve`; empty → `FindPage.EMPTY`; rows = `store.finder().find(spec)`; keep the first `limit`; `next` = `FindCursor.after(last kept, sort).encode()` when more rows came back, else null; items = `views.summariseAcross(kept)`.
- `ItemViews.summariseAcross( List< Item > items ) : List< ItemSummary >` — groups the ids by `projectId`, calls `store.itemExtras` once per project, returns summaries in the input order.

- [ ] **Step 1: Failing tests.** `WorkFindPostgresTest` (setUp as `WorkServiceAssignmentLogTest`; its `WorkDirectory` stub answers `loginForDisplayName("Lena Lead") → lena`; projects AAA (lena lead, bob member) and BBB (bob lead); a registered agent `codex`): `resultsNeverIncludeAProjectTheCallerCannotSee` (lena: AAA only; `projects=BBB` → empty page, no exception; `projects=AAA,BBB` → AAA only); `aStrangerGetsAnEmptyPage`; `anAdminSearchesEveryProject`; `anAgentSeesOnlyWhatItsStandingAllows` (codex member of both, sponsored by bob, who is then removed from AAA → BBB only; no sponsor → empty); `meMeansTheCaller` (`assignee=me` gives lena hers and bob his; same for `reporter`); `meForAnAgentIsItsClaimsAndItsOwnItems`; `watchingForAnAgentMatchesNothing` [P3]; `aPersonIsNamedByDisplayName` (`Lena Lead` matches; the login `lena` from a non-admin matches nothing; from an admin it matches); `anUnknownProductOrPersonMatchesNothing`; `limitDefaultsTo50AndIsCappedAt200` (60 items); `theLastPageHasNoNextCursor`; `cardExtrasComeFromEachItemsProject` (a comment on an AAA item, a product on a BBB item); `archivedProjectsAreSearched`; `aBadCursorIsRefusedTheSameForAStranger`. `WorkInputEdgesTest`: add `"find"` to the reserved list.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkFindPostgresTest,WorkInputEdgesTest'` — expect a compilation failure.
- [ ] **Step 3:** Implement. `WorkFindService` reads visibility only through `scopes.standing()` (`WorkStandingSourceRuleTest`).
- [ ] **Step 4:** In `WorkServiceVisibilityPostgresTest`: `FACADES` gains `"find"`; `READS` gains `"find"`; `argumentSets` gains `: type == FindFilter.class ? List.of( FindFilter.all() )`; `isEmptyResult` gains `|| result instanceof FindPage p && p.items().isEmpty()`. Run `mvn test -pl wikantik-work -Dtest='WorkFindPostgresTest,WorkInputEdgesTest,WorkServiceVisibilityPostgresTest,WorkStandingSourceRuleTest'`: PASS (the visibility test proves `find` returned real data to lena and nothing to its five strangers).
- [ ] **Step 5:** `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-work-mcp`: compiles. **Commit** `git add` the three new main files, `WorkService.java`, `ItemViews.java`, `WorkInputs.java`, `WorkFindPostgresTest.java`, `WorkServiceVisibilityPostgresTest.java`, `WorkInputEdgesTest.java` — `feat(work): cross-project find over the caller's visible projects`.

### Task 4.2a: Schema V087

**Files:**
- Create: `bin/db/migrations/V087__work_saved_filters.sql` (take the next free number at execution time)
- Modify: `wikantik-work/src/test/java/com/wikantik/work/WorkAgentFixtures.java` (`TABLES` gains `"work_saved_filter"` first: a private filter has no project, so `CASCADE` from `work_project` does not remove it)
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkSavedFiltersSchemaTest.java` (modelled on `WorkProductEventsSchemaTest`)

**Interfaces — produces** (after the licence header and a comment saying what the table is and that its rows may be deleted):
```sql
CREATE TABLE IF NOT EXISTS work_saved_filter (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL,
  project_id BIGINT REFERENCES work_project (id), shared BOOLEAN NOT NULL DEFAULT FALSE,
  filter JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (NOT shared OR project_id IS NOT NULL), UNIQUE (owner, name));

GRANT SELECT, INSERT, UPDATE, DELETE ON work_saved_filter TO :app_user;
```

- [ ] **Step 1: Failing tests:** `aSharedFilterNeedsAProject` (SQLState `23514`); `aNameIsUniquePerOwner` (`23505`); `twoOwnersMayUseOneName`; `theFilterIsJson` (`filter->>'q'` reads back; `'not json'` is `22P02`); `theProjectMustExist` (`23503`); `theAppRoleMayReadWriteAndDelete` (the GRANT statements of the file, comments stripped, equal the one line above); `v087IsANoOpWhenReapplied`.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=WorkSavedFiltersSchemaTest` — expect FAIL, relation missing.
- [ ] **Step 3:** Write the migration and the fixture change. Re-run: PASS. Apply the file twice with `psql` against the local database: no error.
- [ ] **Step 4: Commit** `git add bin/db/migrations/V087__work_saved_filters.sql wikantik-work/src/test/java/com/wikantik/work/WorkAgentFixtures.java wikantik-work/src/test/java/com/wikantik/work/WorkSavedFiltersSchemaTest.java` — `feat(work): saved filters table (V087)`.

### Task 4.2b: The saved-filter store

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/SavedFilter.java`, `WorkFilterStore.java`, `JdbcWorkFilters.java`
- Modify: `WorkStore.java` (`WorkFilterStore filters()`), `JdbcWorkStore.java`
- Test: `wikantik-work/src/test/java/com/wikantik/work/JdbcWorkFiltersPostgresTest.java`

**Interfaces — produces:**
```java
public record SavedFilter( long id, String owner, String name, Long projectId, String projectKey, boolean shared,
                           SortedMap< String, String > filter, Instant createdAt ) { }
public interface WorkFilterStore {
    SavedFilter insert( String owner, String name, Long projectId, boolean shared, SortedMap< String, String > filter ) throws SQLException;
    Optional< SavedFilter > byId( long id ) throws SQLException;
    List< SavedFilter > visibleTo( String owner, Collection< Long > projectIds, int limit ) throws SQLException;
    SavedFilter rename( long id, String name ) throws SQLException;
    boolean delete( long id ) throws SQLException;
}
```
- `insert`: `INSERT … VALUES (?,?,?,?,?::jsonb) ON CONFLICT (owner, name) DO NOTHING RETURNING id`; no row → `WorkConflictException( "You already have a saved filter named " + name + ".", WorkConflictException.EXISTS )`.
- `visibleTo`: `… FROM work_saved_filter f LEFT JOIN work_project p ON p.id = f.project_id WHERE f.owner = ? OR ( f.shared AND f.project_id = ANY( ? ) ) ORDER BY lower( f.name ), f.id LIMIT ?`.
- `rename`: `UPDATE … SET name = ? WHERE id = ?`; SQLState `23505` → the same conflict as `insert` (the exception is the cause, never swallowed).
- JSON is written and read with Gson as a `TreeMap<String,String>`.

- [ ] **Step 1: Failing tests:** `insertThenReadBack` (the map round-trips, `projectKey` filled); `aDuplicateNameIsExists`; `visibleToListsOwnAndSharedOfTheGivenProjects` (rows: own private, own shared in PLAT, bob's shared in PLAT, bob's shared in OPS, bob's private; for lena with `[PLAT]` → the first three, by name); `renameToATakenNameIsExists`; `deleteRemovesTheRow`; `deleteOfAnUnknownIdIsFalse`.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=JdbcWorkFiltersPostgresTest` — expect a compilation failure.
- [ ] **Step 3:** Implement; re-run: PASS.
- [ ] **Step 4: Commit** `git add` the three new main files, `WorkStore.java`, `JdbcWorkStore.java`, `JdbcWorkFiltersPostgresTest.java` — `feat(work): saved filter store`.

### Task 4.2c: Saved filters on the facade

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/SavedFilterDraft.java`, `SavedFilterView.java`, `SavedFilters.java`
- Modify: `WorkFindService.java`, `Action.java` (`SHARE_FILTER( WorkRole.MEMBER, false, false )`, last), `wikantik-work/src/test/resources/com/wikantik/work/policy-action-table.golden` (append the 64 `SHARE_FILTER|…` lines `WorkPolicyActionTableTest.table()` now produces; no other line changes)
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkSavedFiltersPostgresTest.java`; modify `WorkServiceVisibilityPostgresTest.java`

**Interfaces — produces:**
- `public record SavedFilterDraft( String name, String projectKey, boolean shared, Map< String, String > filter )`
- `public record SavedFilterView( SavedFilter filter, boolean mine, boolean mayDelete )`
- On `WorkFindService`: `public List< SavedFilterView > saved( Actor actor )`, `public SavedFilterView save( Actor actor, SavedFilterDraft draft )`, `public SavedFilterView rename( Actor actor, long id, String name )`, `public void delete( Actor actor, long id )` (all `throws SQLException`).
- `final class SavedFilters` holds the checks, so the facade stays small:
  - an agent: `saved` → empty list; the three writes → `WorkDeniedException( "Saved filters belong to people." )` before any lookup;
  - name: trimmed, 1–80 characters, no control characters, else field `name`: "A saved filter needs a name of at most 80 characters.";
  - filter: `FindParams.parse`; a `cursor` or `limit` key is refused by that name: "Not part of a saved filter."; stored as `FindParams.toParams(parsed)` with `assignee`/`reporter` other than `me` replaced by `people.login(actor, ref)`, unresolvable → field `assignee`/`reporter`: "No one has that name.";
  - project: `shared` without `projectKey` → field `project`: "A shared filter belongs to a project."; with a `projectKey`, `scopes.scope(actor, key)` (stranger: not found) then `require(Action.VIEW)`, and when shared `require(Action.SHARE_FILTER)` and `requireWritable()` (`archived`); a shared filter's `projects` is set to the scope key when absent and otherwise must equal it, else field `projects`: "A shared filter searches its own project only.";
  - `saved`: `store.filters().visibleTo( actor.login(), <ids of visibleProjects>, 200 )`; `mayDelete` = mine, or shared and (wiki admin or `standing().roleIn(project)` is lead);
  - `rename`/`delete`: unknown id, or someone else's filter that is private or of a project the caller cannot see → `WorkNotFoundException`; `rename` by another → `WorkDeniedException( "Only its owner may rename a saved filter." )`; `delete` by another → `scopes.scope(actor, projectKey).require( Action.MANAGE_PROJECT, null )` (the policy's denial text).

- [ ] **Step 1: Failing tests** (Review Focus 2) in `WorkSavedFiltersPostgresTest` (setUp as `WorkFindPostgresTest`, plus vera, a viewer of AAA): `aSharedFilterIsListedToMembersAndViewersAndNotToAStranger`; `aPrivateFilterIsListedToItsOwnerOnlyEvenAgainstAnAdmin`; `runningSomeonesSharedFilterGivesTheRunnersResults` (lena shares `status=ready` in AAA; bob runs `FindParams.parse(view.filter().filter())` and gets the AAA items bob sees; the filter names only AAA); `aFilterNamingMeMeansTheRunner` (stored map holds `me`; bob's run returns bob's items); `peopleAreStoredAsLogins` (`assignee=Lena Lead` stored as `lena`); `anUnknownPersonIsRefusedByField`; `aViewerCannotShare` (denied); `sharingIntoAnUnseenProjectIsNotFound`; `aSharedFilterSearchesItsOwnProjectOnly` (absent → `AAA`; `BBB` and `AAA,BBB` refused, field `projects`); `aSharedFilterNeedsAProject`; `cursorAndLimitAreNotSaved`; `aDuplicateNameIsExists`; `onlyTheOwnerRenames` (bob denied; a stranger not found); `aLeadDeletesASharedFilterAndAMemberMayNot`; `aStrangerDeletingGetsNotFound`; `anAgentHasNoSavedFiltersAndCannotSave`; `sharingIntoAnArchivedProjectIsArchivedButRenameAndDeleteWork`; `leavingTheProjectHidesItsSharedFilters` (bob removed from AAA: `saved(bob)` no longer lists lena's).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='WorkSavedFiltersPostgresTest,WorkPolicyActionTableTest,WorkPolicyTest'` — expect a compilation failure.
- [ ] **Step 3:** Implement; append the golden lines.
- [ ] **Step 4:** In `WorkServiceVisibilityPostgresTest`: `READS` gains `"saved"`; `WRITES` gains `"save"`, `"rename"`, `"delete"`; setUp inserts lena's shared filter in PLAT through `store.filters().insert(...)` and keeps its id; `everyProjectScopedWriteHidesInvisibleProject` adds `save( mallory, new SavedFilterDraft( "x", "PLAT", true, Map.of() ) )`, `rename( mallory, filterId, "y" )` and `delete( mallory, filterId )`, each `WorkNotFoundException`. Run `mvn test -pl wikantik-work`: PASS.
- [ ] **Step 5: Commit** `git add` the three new main files, `WorkFindService.java`, `Action.java`, `policy-action-table.golden`, `WorkSavedFiltersPostgresTest.java`, `WorkServiceVisibilityPostgresTest.java` — `feat(work): saved filters, shared per project`.

### Task 4.3a: Find on `GET /api/work/items`

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/WorkItemResource.java` (the `GET items` route), `WorkResourceBase.java` (`Call.queryJoined( String name )`: `req.getParameterValues`, falling back to `query(name)` when null, each value trimmed, blanks dropped, joined with commas), `WorkRequests.java` (`static Map< String, String > findParams( Call c )`, `static void refuseFindOnly( Call c )`)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/WorkItemResourceTest.java`

**Interfaces — consumes:** `WorkService.features().find()`, `WorkFindService.find(Actor, FindFilter)`, `FindParams.parse`, `FindParams.FIND_ONLY`, `FindPage`.

**Produces:**
- `GET /api/work/items` **with** `project`: as today; a `FIND_ONLY` parameter present → 400, field = its name, "<name> is a find parameter; leave out project= to search across projects."
- **Without** `project`: `parent` or `order` present → 400, field = its name, "<name> applies to one project's list." Otherwise `FindParams.parse( findParams( c ) )` (only `FindParams.NAMES` are read; `limit` through `clampLimit`), then 200 `{"items": <summariesTree of page.items()>, "next": <string|null>}`. Validation is 400 with the field, as everywhere.

- [ ] **Step 1: Failing tests.** Replace `projectIsRequiredForTheList` with `withoutAProjectTheListIsAFind` (200; `items` empty; `next` null; the captured filter equals `FindFilter.all().page( null, 0 )`). Add `aFindPassesItsParametersThrough` (`projects`, `status`, `priority`, `assignee=me`, `sort=due`, `cursor` → the captured fields); `aFindAnswersItemsWithCardExtrasAndTheNextCursor`; `aFindShowsDisplayNamesNotLogins` (as `WorkMeResourceTest.responseShowsDisplayNameNotLogin`); `repeatedAndCommaSeparatedValuesAreTheSame` (the `tweak` overload of `WorkHttp.call` stubs `getParameterValues( "status" )` → `{"ready","review"}`; same filter as `status=ready,review`); `aFindRefusesParentAndOrderByName`; `theProjectListRefusesFindParametersByName` (`project=PLAT&sort=updated` → 400 field `sort`; `project=PLAT&projects=OPS` → field `projects`); `badFindValuesAre400` (`status=bogus`, `sort=x`). `listsItemsOfAProject` and `badFilterValuesAre400` stay unchanged. setUp stubs `svc.find()` with a mock `WorkFindService`.
- [ ] **Step 2:** `mvn install -pl wikantik-work -DskipTests -q`, then `mvn test -pl wikantik-rest -Dtest=WorkItemResourceTest` — expect FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `mvn test -pl wikantik-rest -Dtest='WorkItemResourceTest,WorkRequestEdgesTest'`: PASS.
- [ ] **Step 5: Commit** `git add wikantik-rest/src/main/java/com/wikantik/rest/WorkItemResource.java wikantik-rest/src/main/java/com/wikantik/rest/WorkResourceBase.java wikantik-rest/src/main/java/com/wikantik/rest/WorkRequests.java wikantik-rest/src/test/java/com/wikantik/rest/WorkItemResourceTest.java` — `feat(work): GET /api/work/items without a project finds across projects`.

### Task 4.3b: Saved filters on REST

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/WorkFilterResource.java`, `WorkFindJson.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (a `<servlet>` `WorkFilterResource` → `com.wikantik.rest.WorkFilterResource` beside `WorkMeResource`, and a `<servlet-mapping>` for `/api/work/filters` and `/api/work/filters/*`), `wikantik-rest/src/test/java/com/wikantik/rest/WorkAccessArchTest.java` (the store rule also names `com.wikantik.work.WorkFindStore` and `com.wikantik.work.WorkFilterStore`)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/WorkFilterResourceTest.java`

**Interfaces — produces:**
- `GET /api/work/filters` → 200 `{"filters": [view…]}`.
- `POST /api/work/filters`, body `{name, project?, shared?, filter: {<param>: "<text>"}}` → 201 view. A body field outside those four → 400 naming it ("Unknown field."); a `filter` value that is not text → 400 field `filter`, "filter values are text."
- `PATCH /api/work/filters/{id}`, body `{name}` → 200 view. `DELETE /api/work/filters/{id}` → 204.
- Errors as the base class maps them: 400 field, 403 denied, 404 not found (also a non-numeric id), 409 `reason` `exists` or `archived`.
- View (built by hand with `WorkFindJson.saved( SavedFilterView, Function< String, String > names )` and sent with `c.tree`, so `WorkJson.enrich` does not touch it): `{id, name, project: "<key>"|null, shared, mine, mayDelete, ownerName, createdAt, filter: {…}}`. In `filter`, `assignee` and `reporter` hold the display name, except `me`, which stays `me`. No login appears, for admins either (the filter is a URL anyone may open).

- [ ] **Step 1: Failing tests:** `listsFiltersWithDisplayNamesAndNoLogins` (owner `alice`, filter `assignee=alice` and `reporter=me`: body contains `"Name of alice"` and `"me"` and no `"alice"` outside those strings); `saveAnswers201AndPassesTheDraft`; `saveRefusesAnUnknownFieldByName`; `filterValuesMustBeText`; `renameTakesAName`; `deleteIs204`; `anUnknownIdIs404`; `aDenialIs403`; `aDuplicateIs409WithReasonExists`; `disabledFeatureIs404`; `anonymousIs401` (the last two as `WorkMeResourceTest`).
- [ ] **Step 2:** `mvn test -pl wikantik-rest -Dtest=WorkFilterResourceTest` — expect a compilation failure.
- [ ] **Step 3:** Implement; edit `web.xml` and the arch rule.
- [ ] **Step 4:** `mvn test -pl wikantik-rest -Dtest='WorkFilterResourceTest,WorkAccessArchTest'`: PASS.
- [ ] **Step 5: Commit** `git add wikantik-rest/src/main/java/com/wikantik/rest/WorkFilterResource.java wikantik-rest/src/main/java/com/wikantik/rest/WorkFindJson.java wikantik-war/src/main/webapp/WEB-INF/web.xml wikantik-rest/src/test/java/com/wikantik/rest/WorkAccessArchTest.java wikantik-rest/src/test/java/com/wikantik/rest/WorkFilterResourceTest.java` — `feat(work): saved filters on REST`.

### Task 4.3c: The filter in the browser

**Files:**
- Create: `wikantik-frontend/src/components/work/findParams.js`, `findParams.test.js`
- Modify: `wikantik-frontend/src/api/work.js`, `wikantik-frontend/src/api/work.test.js`

**Interfaces — produces:**
- `findParams.js`: `FIELDS` (ordered `{ name, multi }` for every `FindParams.NAMES` entry but `limit` and `cursor`); `OPEN_STATUSES = ['draft','refining','ready','in_progress','review']`; `fromQuery(searchParams) : filter` (multi fields as arrays, `watching` as a boolean [P3], unknown parameters ignored); `toQuery(filter) : string` (no `?`, `FIELDS` order, empties left out, arrays comma-joined); `fromSaved(map) : filter`; `toSaved(filter) : { [name]: string }`; `BUILT_IN_VIEWS`: `[{ id: 'assigned', label: 'Assigned to me', filter: { assignee: 'me', status: OPEN_STATUSES, sort: 'updated' } }, { id: 'reported', label: 'Reported by me', filter: { reporter: 'me', … } }, { id: 'watching', label: 'Watching', filter: { watching: true, … } } /* [P3] */]`.
- `api/work.js`: `findItems(params)` → `request('/api/work/items' + query(params))` (callers never pass `project`); `listFilters()`; `saveFilter(body)` (POST); `renameFilter(id, name)` (PATCH `{ name }`); `deleteFilter(id)` (DELETE).

- [ ] **Step 1: Failing vitest:** `findParams.test.js`: `round-trips every field through the URL`; `writes a canonical query and leaves out empty fields`; `ignores unknown parameters`; `round-trips a saved filter`; `built-in views use me and open statuses`. `work.test.js`: the export list gains the five names; `findItems({ label: ['ui'], assignee: 'me' })` calls `/api/work/items?label=ui&assignee=me`; `deleteFilter(4)` sends DELETE to `/api/work/filters/4`.
- [ ] **Step 2:** In `wikantik-frontend`: `npx vitest run src/components/work/findParams.test.js src/api/work.test.js` — expect FAIL.
- [ ] **Step 3:** Implement. Re-run: PASS. `npm run lint`: clean.
- [ ] **Step 4: Commit** `git add wikantik-frontend/src/components/work/findParams.js wikantik-frontend/src/components/work/findParams.test.js wikantik-frontend/src/api/work.js wikantik-frontend/src/api/work.test.js` — `feat(work-ui): find filter URL model and API client`.

### Task 4.3d: `/work/find`

**Files:**
- Create: `wikantik-frontend/src/components/work/WorkFilterBar.jsx`, `WorkFilterBar.test.jsx`, `WorkFind.jsx`, `WorkFind.test.jsx`
- Modify: `wikantik-frontend/src/main.jsx` (lazy `WorkFind`; `<Route path="find" element={<WorkFind />} />` inside `/work`, beside `log`)

**Interfaces — produces:**
- `WorkFilterBar({ value, onApply, projects, products, queues })`: a form holding a draft of `value`: projects (checkboxes of the viewer's projects), statuses, levels, type [P1], labels (comma text) [P1], priorities, assignee and reporter (text; placeholder "Display name or me"), queue and product (selects), sprint [P2] (Any / Active sprint / No sprint, plus "Sprint <id>" when the value is an id), watching [P3], text, due before, updated since, sort. "Apply" calls `onApply(draft)`; "Clear" calls `onApply({})`.
- `WorkFind`: reads the filter with `fromQuery(useSearchParams())`; loads `getMe()`, `listProducts()`, `listQueues()` once; calls `findItems({ ...filter, limit: 50 })` (the `fromQuery` object; `query()` joins its arrays) whenever the query changes, guarding against stale responses with a sequence number as `WorkLog.jsx` does; `onApply` writes `toQuery(filter)` to the URL. A table: key (link to `/work/item/<key>`), title, project, status, type [P1], labels [P1], assignee (`assigneeName`), priority, due, updated. "Load more" passes `cursor: next` and appends. Empty: `EmptyState` "Nothing matches this filter." Error: `WorkLoadError` with retry. "Save filter": a name field, and "Share with <KEY> members" only when the filter names exactly one project in which the viewer's role (from `getMe().projects`) is member or lead, or the viewer is an admin; it calls `saveFilter({ name, project, shared, filter: toSaved(filter) })`; a 409 shows "You already have a filter with that name." inline.

- [ ] **Step 1: Failing vitest.** `WorkFind.test.jsx`: `loading /work/find?label=ui&assignee=me issues that request` (`findItems` called with an object containing `label: ['ui']` and `assignee: 'me'`); `applying the bar writes the URL`; `load more passes the cursor and appends`; `a stale response does not replace a newer one`; `an empty result says nothing matches`; `an error offers a retry`; `sharing is offered only for one project the viewer is a member of`; `saving sends the canonical filter and shows a duplicate name inline`. `WorkFilterBar.test.jsx`: `shows the value`; `apply emits the draft`; `clear emits an empty filter`; `a sprint id is shown when given` [P2].
- [ ] **Step 2:** `npx vitest run src/components/work/WorkFind.test.jsx src/components/work/WorkFilterBar.test.jsx` — expect FAIL.
- [ ] **Step 3:** Implement; add the route. Re-run: PASS. `npm run lint`: clean.
- [ ] **Step 4: Commit** `git add` the four new files and `wikantik-frontend/src/main.jsx` — `feat(work-ui): find across projects with the filter in the URL`.

### Task 4.3e: Work home views

**Files:**
- Modify: `wikantik-frontend/src/components/work/WorkHome.jsx`, `WorkHome.test.jsx` (the `vi.mock('../../api/work', …)` factory gains `listFilters`, `renameFilter`, `deleteFilter`)

**Produces:** when the viewer has a project, a "Find work" section after the waiting lane: the built-in views as links to `/work/find?<toQuery(view.filter)>`, a "Search all work" link to `/work/find`, then the saved filters from `listFilters()` (loaded beside `getMe()`; its failure shows "Could not load your saved filters." in the section and hides nothing else). Each saved filter links to `/work/find?<toQuery(fromSaved(f.filter))>`; a shared one shows `· shared in <project> by <ownerName>`; "Rename" when `mine` (inline field, Enter saves through `renameFilter`); "Delete" when `mayDelete`, through `WorkConfirm`, then reload.

- [ ] **Step 1: Failing vitest:** `built-in views link to find` (exact hrefs); `saved filters are listed with their links`; `saving then reloading home lists it` (`listFilters` answers `[]`, then the new filter on the second load); `delete asks, deletes and reloads`; `rename saves on Enter`; `a failed filter load leaves the projects shown`; `no find section without a project`.
- [ ] **Step 2:** `npx vitest run src/components/work/WorkHome.test.jsx` — expect FAIL.
- [ ] **Step 3:** Implement. Re-run: PASS. `npm run lint`: clean.
- [ ] **Step 4: Commit** `git add wikantik-frontend/src/components/work/WorkHome.jsx wikantik-frontend/src/components/work/WorkHome.test.jsx` — `feat(work-ui): built-in views and saved filters on work home`.

### Task 4.3f: IT, gate and docs

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkApiIT.java`, `wikantik-work/CLAUDE.md`, `docs/developer/ProjectReference.md`, `docs/user/WorkManagement.md`, `CHANGELOG.md`

- [ ] **Step 1: IT** `WorkApiIT.findSearchesOnlyVisibleProjectsAndSharedFiltersStayInTheirProject`: projects `FNDA` and `FNDB` (not `FIND`, which is reserved), an item in each; a member of `FNDA` only gets `FNDA` keys from `GET /api/work/items`, and an empty `items` (200) for `?projects=FNDB`; they save a shared filter in `FNDA`; a second `FNDA` member lists it; an outsider's `GET /api/work/filters` does not list it and their `GET /api/work/items` has empty `items`.
- [ ] **Step 2: Gate.** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll `bin/agent-build.sh wait gate 540` until `SUCCESS exit=0` (`RESULT: ALL PASSED`, PMD ratchet and vitest included). No `wikantik.*` key is added, so `ConfigSurfaceDriftTest` and the config reference are untouched; no MCP tool changes, so `WorkMcpIT.EXPECTED_TOOLS` is untouched.
- [ ] **Step 3: Docs.**
  - `wikantik-work/CLAUDE.md`, a new section "Find and saved filters": find's visible projects come from `WorkStanding` and are a predicate (`project_id = ANY(?)`) of the one statement; a named hidden project gives no rows, never an error; cursor `(sort value, id)`, bound to its sort; `me` resolved when the query runs (agents: claim holder, own items, no watching); `FindParams` is the only parser of the URL form and saved filters store that form; a shared filter searches its own project only and is always run for the runner; saved-filter writes write no `work_event` (preference table, rows may be deleted); `GET /api/work/items` has two modes chosen by `project`; `FIND` is a reserved key. Add `V087 (work_saved_filter)` to the Migrations line.
  - `docs/developer/ProjectReference.md`, "Work management — status": Part 4 built (find, saved filters, V087, routes `GET /api/work/items` without `project`, `/api/work/filters`, `/work/find`).
  - `docs/user/WorkManagement.md`, new section "Finding work and saved filters": the filter bar, that a link is a shareable view, `me`, saving and sharing, that shared filters show each person their own results.
  - `CHANGELOG.md` `[Unreleased]` / Added: "Work: find items across all your projects at `/work/find`, save filters, share them with a project, and open Assigned to me, Reported by me and Watching from work home."
  - `docs/admin/WikantikOperations.md`: nothing (no setting, no operator step).
- [ ] **Step 4: Commit** `git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkApiIT.java wikantik-work/CLAUDE.md docs/developer/ProjectReference.md docs/user/WorkManagement.md CHANGELOG.md` — `docs(work): find and saved filters`.

## Deviations from the outline

- **`FindFilter` carries `projectKeys` and `productKey`, not `projectIds` and `productId`.** The URL and the saved form name projects and products by key; the service turns keys into ids against the visible projects, which is also what makes a named hidden project yield nothing.
- **`Page<ItemSummary>` is `FindPage(List<ItemSummary> items, String next)`.** The module has no generic page type.
- **`find` is on a sub-store, `WorkStore.finder()` (`WorkFindStore`, `JdbcWorkFind`), not on `WorkStore` itself.** Keeps `JdbcWorkStore` from growing (PMD `TooManyMethods`/`GodClass`, no suppressions) and follows `changes()`/`deployments()`.
- **Lists are comma-separated, and repeated parameters are also accepted.** The outline (Part 1) says "repeated `label=`"; the API client's `query()` joins arrays with commas, so both are read.
- **V087 adds the `GRANT` line**, which the outline's DDL left out.
- **Tasks are split** into 4.1a–c, 4.2a–c and 4.3a–f so each can be reviewed on its own; 4.3f holds the gate and docs the outline put in 4.3.
