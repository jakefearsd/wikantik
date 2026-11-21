# Work Management for Small Teams — Part 1: Labels and item type — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every work item has a type (`feature`, `bug`, `chore`) and up to ten free-text labels; both can be set and filtered over REST, MCP and the UI, and changing either writes one `field` event.
**Architecture:** Two `work_item` columns (V084) carried on the existing `Item`, `ItemDraft` and `ItemFilter` records, written by the existing item insert and update in `JdbcWorkStore` / `JdbcWorkItemWrites`, validated in `ItemPatchApplier` through a new pure `Labels` class, and diffed by `WorkEventDiff`. There is no label table: the one new read, `WorkService.labelsOf`, aggregates the labels a project's items carry. No new facade, event type, config key or MCP tool.
**Tech Stack:** Java 25, PostgreSQL 18 (`Jdbc`, `PostgresTestDb`, `FaultInjectingDataSource`), JUnit 5, Mockito, React 19 + vitest, Cargo IT (`wikantik-it-test-rest`).
**Spec:** docs/superpowers/specs/2026-11-21-work-team-features-design.md, section 1; index and global constraints: docs/superpowers/plans/2026-11-21-work-team-features.md
**Depends on:** nothing in this round. Part 4 (find) later consumes `ItemFilter.type()`, `ItemFilter.labels()`, `WorkType` and `Labels.normalise`; keep those names.

## Cross-part rulings

Added when the eight part plans were reconciled (index: `docs/superpowers/plans/2026-11-21-work-team-features.md`). **Where a ruling and the text below disagree, the ruling wins.**

- **R1 — new facades hang off `WorkService.features()`, not `WorkService`.** `WorkService` is at the PMD `TooManyMethods` limit (40). Part 1 creates `com.wikantik.work.WorkFeatures` (built in `WorkService`'s constructor, returned by the one new method `WorkService.features()`); every facade accessor this plan calls `WorkService.x()` is `WorkService.features().x()`. `WorkServiceVisibilityPostgresTest` treats `WorkFeatures`' accessors as facades (its `FACADES` set). No suppression, no baseline entry. In this part: `labelsOf` is a method of `WorkFeatures`, not of `WorkService`; Task 1.2a creates the class and the accessor, and its PMD step expects `WorkService` at 40.
- **R3 — reserved project keys.** `WorkInputs.RESERVED_KEYS` is the one list. This part adds `DEPLOYMENTS` to it (an existing gap: `/work/deployments` is a page today and the key is not reserved), with a test in `WorkInputEdgesTest`.
- **R6 — routes.** `/api/work/projects/*` and `/api/work/items/*` each belong to one existing servlet; new routes under them are added inside that resource, never as a new servlet mapping.

## What this part builds on

Verified in the code on 2026-11-21.

- `Item` (`wikantik-work/src/main/java/com/wikantik/work/Item.java`): a 27-component record ending `…, Long claimedByAgent, Instant claimedAt, Instant claimExpiresAt, Long reporterAgentId`. Main code constructs it in exactly four places: `JdbcWorkStore.ITEM_ROW`, `ItemPatchApplier.forCreate` and `.merged`, `WorkInputs.withVersion`. About 45 test call sites (in `wikantik-work`, `wikantik-rest` `WorkHttp`/`WorkJsonTest`, `wikantik-work-mcp` `MockWork`).
- `ItemDraft( projectKey, level, parentKey, productKey, kind, title, body, priority, dueOn, assignee, queue )`. Built in main code by `WorkItemResource.create`, `CreateItemTool.run` and **`WorkService.createItem`, which rebuilds the draft whenever an assignee is given** (to resolve the display name to a login). About 30 test call sites.
- `ItemFilter( projectId, statuses, assignee, productId, level, parentId, text, dueBefore, limit, order, queue )` with `with( projectId, assignee )` and `withQueue( queue )`. Built in main code by `WorkScopes.Scope.pin` (null filter), `WorkRequests.filter` (REST) and **`JdbcWorkStore.board`, which rebuilds a "scoped" filter field by field**. About 20 test call sites.
- `ItemQuery.conditions( ItemFilter, boolean withStatuses, StringBuilder where, List<Object> params )`; parameters are bound by `SqlBinder.positional`, which calls `ps.setObject` for each.
- `JdbcWorkStore.ITEM_SELECT` / `ITEM_ROW` are the one item column list and mapper, shared by `JdbcWorkClaims`, `JdbcWorkAcks` and `JdbcWorkDiscussion`. `JdbcWorkStore.createItem` inserts with 20 positional parameters inside `jdbc.inTransaction( conn -> … )`. `JdbcWorkItemWrites.update( conn, … )` updates with 26 positional parameters; `writeItem` runs, in order: `lockHolder` (a SELECT), the UPDATE, an optional revision insert, one `appendEvent` per event from `WorkEventDiff.changeEvents`, `itemById`.
- `WorkEventDiff.changeEvents` ends with `fieldEvent( out, cur, by, "<column>", from, to )` lines (`priority`, `due_on`, `parent_id`, `product_id`, `kind`, `autonomy`, `done_means`, `allow_direct`); a `field` event names the field in `note`; no event when from equals to.
- `ItemPatchApplier.KEYS` is the closed set of patch keys; `checkKeys` refuses others ("Unknown field: x"). `PatchValues` holds the per-field coercions (`kind( Object )`, `string( field, Object )`, …). `validateShape( Item next, Item newParent )` already holds level-shape rules such as "An epic has no product of its own." (field `productKey`).
- `WireEnum` (package-private interface) with `parse( Class, String, label )` giving `WorkValidationException( label, "Unknown <label>: <value>" )`; `WorkKind` is the model for a new enum.
- `WorkService` reads a project with `scopes.scope( actor, key )` then `s.require( Action.VIEW, null )`; a stranger gets `WorkNotFoundException`. `WorkService` has 39 methods besides its constructors; the PMD `TooManyMethods` limit (`build-support/pmd-complexity-ruleset.xml`) is 40 and no work class is in the baseline.
- `WorkServiceVisibilityPostgresTest`: `READS` drives each read reflectively as lena and as every stranger, and fails a read that never returned real data to the lead; its `setUp` updates PLAT-1 with a 27-argument `new Item( … )`.
- REST: `WorkRequests.plainValue( field, JsonElement )` refuses any non-primitive PATCH value ("<field> must be a plain value."); `WorkItemResource.DRAFT_FIELDS` is the closed set of create fields; `WorkResourceBase.Call.query( name )` reads one parameter. Item JSON is Gson over the `Item` record (`WorkJson.GSON`, enums by `wire()`), so new record components appear in every item, summary and board payload with no JSON code. `/api/work/projects/*` is already mapped in `web.xml` to `WorkProjectResource`.
- MCP: `UpdateItemTool.SETTABLE` and `REASONS` (`kind` is "not settable"), `CreateItemTool` args, `WorkMcpJson.summary` and `.item` (both put `kind`), `WorkMcpDeployments.itemKeys` (the only list-argument reader today), `wikantik-work-mcp-instructions.txt`.
- Frontend: `api/work.js` `query()` joins an array value with commas into one parameter; `WorkCard.jsx`, `WorkItemForm.jsx` (`KINDS`), `WorkItemView.jsx` (`KIND_OPTIONS`, `valueOf`, `theirValue`, `WorkFieldRow` with `renderControl`/`parse`/`display`), `WorkBoard.jsx` (filters in URL params, `onCreated` re-checks filters before inserting a card), `WorkDates.jsx` (no filters today), `boardModel.js` (`PRIORITY_OPTIONS`), `eventText.js` (generic `field` line). `styles/tokensUsage.test.js` fails any `var(--x)` not declared in `globals.css`; the callout palette (`--callout-note`, `-abstract`, `-tip`, `-success`, `-question`, `-warning`, `-example`, `-quote`) has light and dark values.

## Decisions

- **P1-1. Compatibility constructors.** `Item`, `ItemDraft` and `ItemFilter` each keep their current-arity constructor as a secondary constructor that delegates with type `FEATURE`/`null` and no labels. Main code uses only the canonical constructor; every main-code construction site is pinned by a test that would lose type or labels (Review Focus 1). Reason: about 95 test call sites stay untouched. If wrong: a future main-code site using the short form silently drops the fields; the fix is deleting the secondary constructors and a mechanical edit of the tests.
- **P1-2. Empty label set in an event is null.** A `labels` field event stores the sorted names joined with `,`, and null for no labels, so the UI shows "none" as for any cleared field.
- **P1-3. The label filter accepts repeated and comma-separated values.** `label=ui&label=api` and `label=ui,api` are the same filter (labels cannot contain a comma). Reason: the existing `api/work.js` `query()` sends arrays comma-joined; the outline's repeated form also works.
- **P1-4. Filter labels are normalised in `ItemFilter`'s compact constructor** with `Labels.normalise`, so REST, MCP and tests all get the same lower-case, de-duplicated set; a malformed filter label is a 400 naming `labels`.
- **P1-5. `labelsOf` returns at most 200 labels**, most used first, then by name; labels on `cancelled` items do not count, labels on `done` items do.
- **P1-6. Board and Dates filter by one type and one label** (two single selects). The API takes several labels ("carries all of them"); the multi-label UI comes with Find in Part 4.
- **P1-7. A card shows at most three labels, then `+N`.** The item view shows all of them.
- **P1-8. Label colours** come from eight callout tokens (note, abstract, tip, success, question, warning, example, quote); the red ones (failure, danger, bug) are left out so a label never looks like the bug marker. The marker is `Badge variant="warning"` reading "Bug".
- **P1-9. Type and labels are in MCP `summary` too** (`list_claimable`, `claim_item`, `get_work_context` claims), not only `get_item`: an agent choosing work should see that it is a bug.
- **P1-10. A PATCH `labels` value is a JSON array.** `WorkRequests.plainValue` accepts a flat array of plain values (returned as a `List`); objects and nested arrays are still refused.
- **P1-11. Labels written through `createArrayOf`, filtered through a quoted array literal.** Inserts and updates bind `conn.createArrayOf( "text", … )`; the filter binds the string `{"a","b"}` cast `?::text[]` because `SqlBinder.positional` has no connection. Every element is double-quoted so a label spelled `null` is text, not SQL NULL. Labels are regex-validated, so they never contain `"` or `\`.
- **P1-12. ITs.** One Cargo IT method in `WorkApiIT` and one probe line in its 404 sweep; the MCP path is proven at Postgres level in `WorkMcpFlowPostgresTest` (real dispatch, service, store) instead of a new `WorkMcpIT` method, because no tool was added.

## Review Focus

1. **An edit that silently drops type or labels.** A title-only patch, a hand-off, a claim, a create *with an assignee* (the path where `WorkService.createItem` rebuilds the draft), and the board (which rebuilds the filter) must all keep them. Tests: Task 1.1c `createWithAnAssigneeKeepsTypeAndLabels`, `aTitleOnlyPatchKeepsTypeAndLabels`, `aHandoffKeepsTypeAndLabels`; Task 1.2a `theBoardHonoursTypeAndLabelFilters`, `withAndWithQueueKeepTypeAndLabels`.
2. **Labels of another project.** `labelsOf` as any stranger is not found; filtering by a label shared by two projects returns only the pinned project's items. Tests: Task 1.2a visibility classification, `itemsFilteredByLabelStayInTheProject`; Task 1.4 `WorkApiIT` probe.
3. **Hostile label text in a filter.** A label `null`, a label with SQL in it, and twelve labels: the first matches as text, the second is a 400 naming `labels`, the third a 400. Tests: Task 1.2a `aLabelNamedNullFiltersAsText`, `ItemFilterTest`.
4. **Event noise.** Sending the same labels in another order or case writes no event. Test: Task 1.1c `reorderingOrRecasingTheSameLabelsWritesNoEvent`.
5. **A failed write leaving half a change.** Type and labels roll back with the rest of the item write. Test: Task 1.1c `aFailedLabelsEventRollsBackTypeAndLabels`.

---

### Task 1.1a: Migration V084

**Files:**
- Create: `bin/db/migrations/V084__work_labels_and_type.sql`
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkLabelsSchemaTest.java` (new, `@RequiresPostgres`, modelled on `WorkChangesSchemaTest`: `PostgresTestDb.truncate( WorkAgentFixtures.TABLES )`, raw inserts through `WorkAgentFixtures.exec/scalar`)

**Interfaces — produces (DDL verbatim, after the licence header and a comment saying what V084 is):**
```sql
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'feature'
    CHECK (type IN ('feature', 'bug', 'chore'));
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS labels TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX IF NOT EXISTS idx_work_item_labels ON work_item USING GIN (labels);
```
No grant line: V074 grants `SELECT, INSERT, UPDATE ON work_item` at table level.

- [ ] **Step 1: Failing tests.** `anItemInsertedWithoutEitherColumnIsAFeatureWithNoLabels` (raw insert as in `WorkChangesSchemaTest.setUp`, then `SELECT type, cardinality(labels)` gives `feature`, `0`); `anUnknownTypeIsRefused` (update to `'epic'`: SQLState `23514`); `labelsAreNotNull` (update to NULL: `23502`); `theLabelsIndexIsGin` (`SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_work_item_labels'` contains `USING gin (labels)`); `v084IsANoOpWhenReapplied` (read the file from `../bin/db/migrations/`, replace `:app_user` with `CURRENT_USER`, exec twice, then insert a row).
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest=WorkLabelsSchemaTest`. Expected: FAIL (column `type` does not exist; file missing).
- [ ] **Step 3:** Write the migration. `PostgresTestDb` applies every migration, so the tests now see the columns.
- [ ] **Step 4:** Re-run: PASS.
- [ ] **Step 5: Commit** `git add bin/db/migrations/V084__work_labels_and_type.sql wikantik-work/src/test/java/com/wikantik/work/WorkLabelsSchemaTest.java` — `feat(work): V084 item type and labels columns`.

### Task 1.1b: `WorkType` and `Labels`

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/WorkType.java`, `Labels.java`
- Test: `wikantik-work/src/test/java/com/wikantik/work/LabelsTest.java` (new); modify `WireEnumTest.java`

**Interfaces — produces:**
- `public enum WorkType implements WireEnum { FEATURE("feature"), BUG("bug"), CHORE("chore"); public String wire(); public static WorkType fromWire( String value ) }` — `fromWire` is `WireEnum.parse( WorkType.class, value, "type" )`.
- `public final class Labels { public static final int MAX = 10; public static List<String> normalise( Collection<String> raw ) }`: null or empty gives `List.of()`; each element trimmed and lower-cased (`Locale.ROOT`), de-duplicated, sorted; returns an unmodifiable list. Refusals, all `WorkValidationException` field `labels`:
  - a null element, or an element that after trimming and lower-casing does not match `^[a-z0-9][a-z0-9._-]{0,39}$`: `"A label is 1 to 40 lowercase letters, digits, dots, dashes or underscores, starting with a letter or digit: <element as given>"` (the element truncated to 50 characters);
  - more than `MAX` distinct labels after normalising: `"An item has at most 10 labels."`.

- [ ] **Step 1: Failing tests.** `LabelsTest`: `trimsLowerCasesDeduplicatesAndSorts` (`[" UI ","ui","Billing"]` → `["billing","ui"]`); `nullAndEmptyAreNoLabels`; `aLabelMayUseDotsDashesAndUnderscores` (`v2.1`, `front-end`, `needs_design` kept); `aLabelSpelledNullIsAnOrdinaryLabel` (`["NULL"]` → `["null"]`); `refusesASpace`, `refusesEmpty`, `refusesALeadingDash`, `refusesFortyOneCharacters`, `refusesANullElement` (each: field `labels`); `fortyCharactersIsAllowed`; `elevenDistinctLabelsAreRefused` (message `An item has at most 10 labels.`); `elevenThatCollapseToTenAreAllowed` (`"a"` and `"A"` plus nine others). `WireEnumTest.parseThrowsTheEnumSpecificMessage` gains `assertEquals( "Unknown type: epic", … WorkType.fromWire( "epic" ) … )`.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='LabelsTest,WireEnumTest'`. Expected: compilation failure (`WorkType`, `Labels` missing).
- [ ] **Step 3:** Implement both classes.
- [ ] **Step 4:** Re-run: PASS.
- [ ] **Step 5: Commit** `git add wikantik-work/src/main/java/com/wikantik/work/WorkType.java wikantik-work/src/main/java/com/wikantik/work/Labels.java wikantik-work/src/test/java/com/wikantik/work/LabelsTest.java wikantik-work/src/test/java/com/wikantik/work/WireEnumTest.java` — `feat(work): item type enum and label rules`.

### Task 1.1c: Type and labels on items: model, store, events, rules

**Files:**
- Modify: `wikantik-work/src/main/java/com/wikantik/work/Item.java`, `ItemDraft.java`, `PatchValues.java`, `ItemPatchApplier.java`, `WorkEventDiff.java`, `WorkInputs.java`, `JdbcWorkStore.java`, `JdbcWorkItemWrites.java`, `WorkService.java` (`createItem` only)
- Modify test: `JdbcWorkStoreItemsPostgresTest.java`, `JdbcWorkStoreItemsRollbackTest.java`
- Create test: `wikantik-work/src/test/java/com/wikantik/work/WorkLabelsPostgresTest.java` (uses `GateFixture`: lena lead, mia member, agent `author`, product `wikantik`)

**Interfaces — consumes:** `WorkType`, `Labels.normalise` (Task 1.1b).
**Interfaces — produces:**
- `Item`: two components appended after `reporterAgentId`: `WorkType type, List<String> labels`. Compact constructor: `type` null → `WorkType.FEATURE`; `labels` null → `List.of()`, else `List.copyOf( labels )`. Secondary constructor with the old 27 parameters delegating with `WorkType.FEATURE, List.of()` (P1-1), Javadoc "for tests and callers that predate type and labels; main code uses the canonical constructor".
- `ItemDraft`: `WorkType type, List<String> labels` appended after `queue` (null means feature / none); secondary constructor with the old 11 parameters delegating with `null, null`.
- `ItemPatchApplier.KEYS` gains `"type"` and `"labels"`. `PatchValues.type( Object v ) : WorkType` = `WorkType.fromWire( requiredString( "type", v ) )` ("type cannot be cleared."). `PatchValues.labels( Object v ) : List<String>`: null → `List.of()`; a `List` whose elements are all `String` → `Labels.normalise( list )`; anything else → `WorkValidationException( "labels", "labels must be a list of labels." )`.
- `ItemPatchApplier.forCreate` builds the item with `d.type() == null ? WorkType.FEATURE : d.type()` and `Labels.normalise( d.labels() )`; `merged` picks `type` and `labels` with `PatchValues::type` / `PatchValues::labels`. `validateShape` gains: level `EPIC` and type not `FEATURE` → `WorkValidationException( "type", "An epic is always a feature." )`.
- `WorkInputs.withVersion` passes `i.type(), i.labels()` through.
- `WorkService.createItem`: the rebuilt draft passes `draft.type(), draft.labels()`.
- `WorkEventDiff.changeEvents`: after the `kind` line, `fieldEvent( out, cur, by, "type", cur.type().wire(), nxt.type().wire() )` and `fieldEvent( out, cur, by, "labels", joined( cur.labels() ), joined( nxt.labels() ) )` with `private static String joined( List<String> l )` = null when empty, else `String.join( ",", l )`.
- `JdbcWorkStore.ITEM_SELECT` ends `…, i.reporter_agent_id, i.type, i.labels`; `ITEM_ROW` adds `WorkType.fromWire( rs.getString( "type" ) )` and `labels( rs.getArray( "labels" ) )` with `static List<String> labels( java.sql.Array a )` = `a == null ? List.of() : List.of( (String[]) a.getArray() )`. `createItem`'s insert adds columns `type, labels` after `reporter_agent_id`: parameter 21 `draft.type().wire()`, parameter 22 `conn.createArrayOf( "text", draft.labels().toArray( new String[ 0 ] ) )`.
- `JdbcWorkItemWrites.update`: `type = ?, labels = ?` inserted right after `assignee_queue = ?` (parameters 15 and 16); the former parameters 15–26 become 17–28.

- [ ] **Step 1: Failing store tests** in `JdbcWorkStoreItemsPostgresTest` (build items with the canonical constructor through a new helper `static Item typed( Item base, WorkType type, List<String> labels )`): `aNewItemIsAFeatureWithNoLabels`; `typeAndLabelsAreStoredAndReadBack` (create BUG with `["api","ui"]`, re-read by key); `aLabelsChangeWritesOneFieldEvent` (none → `api,ui`: one `field` event, note `labels`, from null, to `api,ui`; then `api,ui` → `ui`: from `api,ui`, to `ui`); `aTypeChangeWritesOneFieldEvent` (note `type`, `feature` → `bug`); `aPriorityOnlyUpdateWritesNoTypeOrLabelsEvent`; `aLabelSpelledNullIsStoredAsText` (`["null"]` reads back as `"null"`).
- [ ] **Step 2: Failing rollback test** in `JdbcWorkStoreItemsRollbackTest`: `aFailedLabelsEventRollsBackTypeAndLabels` — an item, then an update changing only type (to BUG) and labels (to `["ui"]`); statements in `writeItem` are 1 `lockHolder`, 2 UPDATE, 3 the `type` event, 4 the `labels` event; `faulty.failOn( 4, … )`; assert one rollback, the re-read item is still `FEATURE` with no labels, and `store.itemEvents` holds no `field` event.
- [ ] **Step 3: Failing service tests** in `WorkLabelsPostgresTest`: `labelsAreNormalisedOnCreateAndOnPatch`; `reorderingOrRecasingTheSameLabelsWritesNoEvent` (item with `[billing, ui]`, patch `["UI","billing"]`: no `field` event with note `labels` added); `aBadLabelIsRefusedNamingLabels` (patch `["has space"]`: field `labels`, item unchanged); `labelsMustBeAList` (patch `labels: "ui"`: field `labels`, message `labels must be a list of labels.`); `typeCannotBeCleared` (patch `type: null`: field `type`); `anUnknownTypeIsRefused` (`Unknown type: story`); `anEpicIsAlwaysAFeature` (create epic with BUG: field `type`, `An epic is always a feature.`; create epic with no type: `FEATURE`; patch an epic's type to `chore`: refused the same way); `createWithAnAssigneeKeepsTypeAndLabels` (lena creates a task assigned to `mia` with BUG and `["ui"]`: both stored); `aTitleOnlyPatchKeepsTypeAndLabels`; `aHandoffKeepsTypeAndLabels` (`svc.claims().handoff` of a labelled item to a queue: type and labels unchanged); `anAgentMemberMaySetTypeAndLabels` (the fixture agent patches a lena draft: stored, the events' `agentId` is the agent's); `anArchivedProjectRefusesALabelChange` (`WorkConflictException`, reason `archived`).
- [ ] **Step 4:** `mvn test -pl wikantik-work -Dtest='JdbcWorkStoreItemsPostgresTest,JdbcWorkStoreItemsRollbackTest,WorkLabelsPostgresTest'`. Expected: compilation failure, then (once the records compile) assertion failures.
- [ ] **Step 5:** Implement the produces list above.
- [ ] **Step 6:** Re-run: PASS. Then `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-work-mcp -q` (the secondary constructors keep every other call site compiling) and `mvn test -pl wikantik-work` (the whole module, since `ITEM_ROW` is shared).
- [ ] **Step 7: Commit** `git add` the nine main files and three test files above by path — `feat(work): item type and labels on the model, store and event log`.

### Task 1.2a: Filtering and the project's labels

**Files:**
- Create: `wikantik-work/src/main/java/com/wikantik/work/LabelCount.java`; test `wikantik-work/src/test/java/com/wikantik/work/ItemFilterTest.java`
- Modify: `ItemFilter.java`, `ItemQuery.java`, `WorkScopes.java` (`Scope.pin`), `WorkStore.java`, `JdbcWorkStore.java` (`board`, new `labelCounts`), `WorkService.java`
- Modify test: `JdbcWorkStoreItemsPostgresTest.java`, `WorkLabelsPostgresTest.java`, `WorkServiceVisibilityPostgresTest.java`

**Interfaces — produces:**
- `ItemFilter`: `WorkType type, Set<String> labels` appended after `queue`. Compact constructor: `labels` null or empty → `Set.of()`, else `Set.copyOf( Labels.normalise( labels ) )` (P1-4). Secondary constructor with the old 11 parameters (P1-1). `with` and `withQueue` pass `type, labels` through.
- `ItemQuery.conditions` appends, after the queue clause: `AND i.type = ?` (`type.wire()`) when `type` is set; `AND i.labels @> ?::text[]` with the parameter `textArray( labels )` when `labels` is non-empty, where `static String textArray( Collection<String> labels )` returns the sorted labels as `{"a","b"}` (P1-11).
- `JdbcWorkStore.board`: the scoped filter passes `f.type(), f.labels()`. `WorkScopes.Scope.pin`: the null-filter branch uses the canonical constructor with `null, null`.
- `public record LabelCount( String name, int items )`.
- `WorkStore.labelCounts( long projectId ) : List<LabelCount>`, implemented in `JdbcWorkStore`:
  ```sql
  SELECT l.name, COUNT(*) AS items
    FROM work_item i CROSS JOIN LATERAL unnest( i.labels ) AS l(name)
   WHERE i.project_id = ? AND i.status <> 'cancelled'
   GROUP BY l.name ORDER BY items DESC, l.name LIMIT 200
  ```
- `WorkService.labelsOf( Actor actor, String projectKey ) : List<LabelCount>`: `scopes.scope( actor, projectKey )`, `s.require( Action.VIEW, null )`, `store.labelCounts( s.project().id() )`. A read.

- [ ] **Step 1: Failing tests.** `ItemFilterTest`: `labelsAreNormalised` (`{" UI "}` → `{"ui"}`); `noLabelsIsAnEmptySet` (null → `Set.of()`); `aBadFilterLabelIsRefusedNamingLabels` (`"x'; drop table work_item; --"`: field `labels`); `elevenFilterLabelsAreRefused`; `withAndWithQueueKeepTypeAndLabels`. `JdbcWorkStoreItemsPostgresTest`: `filteringOnTwoLabelsReturnsOnlyItemsCarryingBoth` (items `[ui]`, `[api]`, `[api, ui]`: filter `{api, ui}` gives the third only); `filteringOnTypeReturnsOnlyThatType`; `theBoardHonoursTypeAndLabelFilters` (`board()` columns hold only the matching card, `total` counts only it); `aLabelNamedNullFiltersAsText`; `labelCountsCountNonCancelledItemsMostUsedFirst` (`ui` on three items of which one cancelled → 2, `api` on two → 2, `zz` on one → 1: order `api`, `ui`, `zz`; a label of another project is absent). `WorkLabelsPostgresTest`: `labelsOfListsTheProjectsLabels`; `itemsFilteredByLabelStayInTheProject` (a second project `OPS` where lena is no member has an item labelled `ui`; `svc.itemSummaries( lena, "PLAT", filter{ui} )` holds only PLAT items, and so does `svc.items` given a filter whose `projectId` names OPS). `WorkServiceVisibilityPostgresTest`: add `"labelsOf"` to `READS`; in `setUp` build `assigned` with the canonical constructor and labels `List.of( "secret" )` so the lead sees real data.
- [ ] **Step 2:** `mvn test -pl wikantik-work -Dtest='ItemFilterTest,JdbcWorkStoreItemsPostgresTest,WorkLabelsPostgresTest,WorkServiceVisibilityPostgresTest'`. Expected: compilation failure, then `labelsOf` missing from the classification.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Re-run: PASS. `mvn test-compile -pl wikantik-work,wikantik-rest,wikantik-work-mcp -q`.
- [ ] **Step 5:** `mvn pmd:check -Pcomplexity-gate -pl wikantik-work`. Expected: no violation (`WorkService` is now at 40 methods, the limit; `WorkStore` at 39 counted). If `TooManyMethods` fires on `WorkService`, move `labelsOf`'s body into `ItemViews` as `labelsOf( Scope s )` and keep the one-line delegate: do not add a suppression or a baseline entry.
- [ ] **Step 6: Commit** `git add` the files above by path — `feat(work): filter items by type and labels; a project's labels`.

### Task 1.2b: REST

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/WorkRequests.java`, `WorkResourceBase.java` (`Call`), `WorkItemResource.java`, `WorkProjectResource.java`
- Modify test: `WorkItemResourceTest.java`, `WorkProjectResourceTest.java`, `WorkJsonTest.java`

**Interfaces — produces:**
- `WorkResourceBase.Call.queryValues( String name ) : List<String>`: every value of `req.getParameterValues( name )` (null → none), each split on `,`, trimmed, blanks dropped.
- `WorkRequests.plainValue`: a JSON array of primitives or nulls returns a `List<Object>` of the same plain values; an object, or an array holding an array or object, is still `"<field> must be a plain value."` (P1-10).
- `WorkRequests.optStringList( JsonObject body, String field ) : List<String>`: absent or JSON null → null; a JSON array of strings → the list; anything else → `WorkValidationException( field, field + " must be a list of text." )`.
- `WorkRequests.filter`: reads `type` (`WorkType.fromWire`, so `Unknown type: x` is a 400 naming `type`) and `queryValues( "label" )` (empty → null) into the canonical `ItemFilter` constructor. Applies to `GET /api/work/items?project=…` and `GET /api/work/projects/{key}/board`.
- `WorkItemResource.DRAFT_FIELDS` gains `"type"`, `"labels"`; `create` passes `type` (`WorkType.fromWire` when present) and `optStringList( b, "labels" )`. PATCH `changes.type` (string) and `changes.labels` (array) reach the service unchanged.
- `GET /api/work/projects/{key}/labels` → 200 `[{"name":"ui","items":3}, …]` (`c.json( c.svc.labelsOf( c.actor, key ) )`); a stranger or unknown project → 404 (the existing `WorkNotFoundException` mapping).
- Item, summary and board JSON gain `"type":"feature"|"bug"|"chore"` and `"labels":[…]` from the record, with no JSON code.

- [ ] **Step 1: Failing tests.** `WorkJsonTest`: `anItemCarriesTypeAndLabels` (an `Item` built with BUG and `["api","ui"]` serialises `type` `bug` and the array). `WorkItemResourceTest`: `createPassesTypeAndLabels` (captor on `svc.createItem`: `type()` BUG, `labels()` `["UI","api"]` as sent); `createRefusesLabelsThatAreNotAListOfText` (`"labels":"ui"` and `"labels":[1]`: 400, field `labels`); `createRefusesAnUnknownType` (400, field `type`); `patchPassesLabelsAsAList` (captor on `svc.updateItem`: `changes.get( "labels" )` equals `List.of( "ui", "api" )`); `patchStillRefusesAnObjectValue` (`"labels":{}`: 400 `labels must be a plain value.`); `aLabelRefusalFromTheServiceIs400NamingLabels`; `listFilterCarriesTypeAndLabels` (`type=bug`, and `getParameterValues( "label" )` stubbed through `WorkHttp.call`'s `tweak` to `{"ui","Api,billing"}`: the captured filter has BUG and `{api, billing, ui}`). `WorkProjectResourceTest`: `boardFilterCarriesTypeAndLabels` (same, on `board`); `labelsRouteListsCounts` (200, first element `name` `ui`, `items` 3); `labelsRouteForAStrangerIs404`.
- [ ] **Step 2:** `mvn test -pl wikantik-rest -Dtest='WorkJsonTest,WorkItemResourceTest,WorkProjectResourceTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Re-run: PASS. `mvn test -pl wikantik-rest -Dtest='Work*'` (arch and edge tests included).
- [ ] **Step 5: Commit** `git add` the four main and three test files by path — `feat(work): type and labels on the REST API`.

### Task 1.2c: MCP

**Files:**
- Modify: `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/WorkMcpArgs.java`, `CreateItemTool.java`, `UpdateItemTool.java`, `WorkMcpJson.java`, `src/main/resources/wikantik-work-mcp-instructions.txt`
- Modify test: `CreateItemToolTest.java`, `UpdateItemToolTest.java`, `GetItemToolTest.java`, `ListClaimableToolTest.java`, `WorkMcpFlowPostgresTest.java`

**Interfaces — produces:**
- `WorkMcpArgs.stringListArg( Map<String,Object> args, String key ) : List<String>`: absent → null; null → `List.of()`; a `List` of `String` → the list; anything else → `WorkValidationException( key, "must be a list of labels." )` (shown to the agent as `labels: must be a list of labels.`).
- `WorkMcpArgs.labelsSchema() : Map<String,Object>`: `{"type":"array","items":{"type":"string"},"description":"Labels: lowercase letters, digits, dots, dashes and underscores, at most 10. The whole set is replaced.","examples":[["ui","billing"]]}`.
- `create_item`: new optional args `type` (string, "feature (default), bug or chore; an epic is always a feature.", example `bug`) and `labels` (`labelsSchema()`), passed into the canonical `ItemDraft`. Description and instructions line add "type, labels".
- `update_item`: `SETTABLE` gains `type` and `labels` (`labels` read with `stringListArg`, `type` with `stringArg`); description "Change an item's title, body, status, priority, dueOn, type or labels…"; `kind` stays not settable.
- `WorkMcpJson.summary` and `.item` put `"type"` (wire) after `"kind"` and `"labels"` (the list).
- Instructions file: the `get_item`, `update_item` and `create_item` lines name type and labels; one sentence: "labels replaces the whole set; an unknown or malformed label is refused as labels:". No new tool, so `WorkMcpInitializerTest`, `WorkMcpInstructionsDriftTest` and `WorkMcpIT.EXPECTED_TOOLS` are unchanged.

- [ ] **Step 1: Failing tests.** `CreateItemToolTest`: `typeAndLabelsArePassedToTheService` (captor: BUG, `["ui"]`); `labelsMustBeAListOfText` (`"labels","ui"`: text `labels: must be a list of labels.`, service not called); `anUnknownTypeIsAValidationErrorByName` (`type: Unknown type: epic`). `UpdateItemToolTest`: `typeAndLabelsAreSettable` (captor: `changes` holds `type` `bug` and `labels` `["ui","api"]`); `labelsMustBeAListOfText`; the `policyAndStructuralFieldsAreRejectedByName` list is unchanged (still contains `kind`). `GetItemToolTest`: `includesTypeAndLabels`. `ListClaimableToolTest`: `aSummaryCarriesTypeAndLabels`. `WorkMcpFlowPostgresTest`: `anAgentSetsTypeAndLabelsThroughTheRealService` (through the real dispatch: `update_item` with `type: bug`, `labels: ["UI"]`; `get_item` shows `bug` and `["ui"]`; the item's events end with two `field` events, notes `type` and `labels`, attributed to the agent).
- [ ] **Step 2:** `mvn test -pl wikantik-work-mcp -Dtest='CreateItemToolTest,UpdateItemToolTest,GetItemToolTest,ListClaimableToolTest,WorkMcpFlowPostgresTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Re-run: PASS. `mvn test -pl wikantik-work-mcp` (drift, audit and arch tests).
- [ ] **Step 5: Commit** `git add` the five main and five test files by path — `feat(work-mcp): type and labels on create_item, update_item and item views`.

### Task 1.3a: Label colour, label chips and editor, event text

**Files:**
- Create: `wikantik-frontend/src/components/work/labelColor.js`, `labelColor.test.js`, `WorkLabels.jsx`, `WorkLabels.test.jsx`
- Modify: `wikantik-frontend/src/api/work.js`, `components/work/boardModel.js`, `components/work/eventText.js`, `components/work/eventText.test.js`, `styles/work.css`

**Interfaces — produces:**
- `api/work.js`: `export const listLabels = (key) => request(\`${base}/projects/${enc(key)}/labels\`);`
- `boardModel.js`: `export const TYPE_OPTIONS = [{ value: 'feature', label: 'Feature' }, { value: 'bug', label: 'Bug' }, { value: 'chore', label: 'Chore' }];` and `export const LABEL_PATTERN = /^[a-z0-9][a-z0-9._-]{0,39}$/; export const MAX_LABELS = 10;`
- `labelColor.js`: `export const LABEL_PALETTE` = the eight strings `'var(--callout-note)'`, `'var(--callout-abstract)'`, `'var(--callout-tip)'`, `'var(--callout-success)'`, `'var(--callout-question)'`, `'var(--callout-warning)'`, `'var(--callout-example)'`, `'var(--callout-quote)'` written literally (so `tokensUsage.test.js` checks them); `export function labelColor(name)`: `h = 0; for each UTF-16 code c: h = (h * 31 + c) >>> 0;` return `LABEL_PALETTE[h % LABEL_PALETTE.length]`.
- `WorkLabels({ labels = [], suggestions = [], editable = false, max, onChange })`: read-only, a list of `<span className="work-label">` chips, style `{ color, borderColor: color, background: \`color-mix(in srgb, ${color} 12%, transparent)\` }` with `color = labelColor(name)`; with `max`, at most `max` chips then `<span className="work-label-more">+N</span>`. Editable: each chip has a remove button (`aria-label="Remove label <name>"`) calling `onChange(labels without it)`; an `<input aria-label="Add a label">` with a `<datalist>` of `suggestions` not already present; Enter trims and lower-cases the text, and if it matches `LABEL_PATTERN`, is new and `labels.length < MAX_LABELS`, calls `onChange([...labels, name].sort())` and clears; otherwise shows inline `role="alert"` text: "Use lowercase letters, digits, dots, dashes or underscores, up to 40." or "At most 10 labels."; Enter never submits an enclosing form.
- `eventText.js` `case 'field'`: when `ev.note === 'labels'`, each side shows `value.split(',').join(', ')` (none stays "none").
- `work.css`: `.work-label` (inline-block, 1px solid border, `var(--radius-sm)`, `0 var(--space-xs)`, `0.75rem`), `.work-label-more`, `.work-labels` (flex, wrap, gap `var(--space-xs)`), `.work-label-input`. No new custom property.

- [ ] **Step 1: Failing vitest.** `labelColor.test.js`: `the same name always gets the same colour`; `every colour is from the palette`; `the palette uses no red token` (no entry contains `failure`, `danger` or `bug`). `WorkLabels.test.jsx`: `shows a chip per label`; `with max 3 shows three chips and +2`; `typing an unknown label and Enter adds it` (onChange called with the sorted list; input cleared); `upper case is lower-cased`; `a label with a space shows the rule and adds nothing`; `an eleventh label is refused`; `a label already present adds nothing`; `remove calls onChange without it`; `Enter does not submit the form` (wrapped in a `<form onSubmit={spy}>`: spy not called). `eventText.test.js`: `changed labels none → api, ui` and `changed type feature → bug`.
- [ ] **Step 2:** `cd wikantik-frontend && npx vitest run src/components/work/labelColor.test.js src/components/work/WorkLabels.test.jsx src/components/work/eventText.test.js`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Re-run: PASS. `npx vitest run src/styles/tokensUsage.test.js` and `npm run lint`.
- [ ] **Step 5: Commit** `git add` the nine files by path — `feat(work-ui): label chips, label colours and label event text`.

### Task 1.3b: Cards, the new-item form and the item view

**Files:**
- Modify: `wikantik-frontend/src/components/work/WorkCard.jsx`, `WorkItemForm.jsx`, `WorkItemView.jsx`
- Modify test: `WorkCard.test.jsx`, `WorkItemForm.test.jsx`, `WorkItemView.test.jsx`, `WorkItemView.edges.test.jsx` (add `listLabels: vi.fn(() => Promise.resolve([]))` to each `vi.mock('../../api/work', …)` that renders these components)

**Interfaces — consumes:** `WorkLabels`, `TYPE_OPTIONS`, `listLabels` (Task 1.3a).
**Interfaces — produces:**
- `WorkCard`: in `work-card-head`, `item.type === 'bug'` renders `<Badge variant="warning" title="Bug">Bug</Badge>`; below the title, `<WorkLabels labels={item.labels} max={3} />` when there are labels.
- `WorkItemForm`: new prop `labelSuggestions = []`; a Type select (`TYPE_OPTIONS`, default `feature`) shown unless level is `epic`; a Labels field (`WorkLabels editable`); the draft sends `type` (omitted for an epic) and `labels` (omitted when empty).
- `WorkItemView`: loads `listLabels(view.projectKey)` once per project (failure: `console.warn`, no suggestions); a `Type` `WorkFieldRow` (`type="select"`, `TYPE_OPTIONS`, `onSave={field('type')}`) not shown for an epic; a `Labels` `WorkFieldRow` with `value={(item.labels || []).join(',')}`, `display={<WorkLabels labels={item.labels} />}` (or "None"), `renderControl` rendering `WorkLabels editable` over the comma string, `parse={(v) => (v ? v.split(',') : [])}`, `onSave={field('labels')}`. `valueOf` gains `case 'type': return item.type || 'feature';` and `case 'labels': return (item.labels || []).join(',');`; `theirValue` shows labels as `join(', ')` or `(none)`.

- [ ] **Step 1: Failing vitest.** `WorkCard.test.jsx`: `a bug card shows the bug marker`; `a feature card shows none`; `a card shows its labels, at most three then +N`. `WorkItemForm.test.jsx`: `sends type and labels`; `an epic has no type control and sends no type`; `sends no labels when none were added`. `WorkItemView.test.jsx`: `saving labels patches the whole set` (`patchItem` called with `{ changes: { labels: ['api', 'ui'] }, … }`); `saving type patches type`; `an epic shows no type row`; `a 409 on labels lists their labels in the conflict banner`.
- [ ] **Step 2:** `npx vitest run src/components/work/WorkCard.test.jsx src/components/work/WorkItemForm.test.jsx src/components/work/WorkItemView.test.jsx src/components/work/WorkItemView.edges.test.jsx`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Re-run: PASS. `npm run lint`.
- [ ] **Step 5: Commit** `git add` the seven files by path — `feat(work-ui): type and labels on cards, the item form and the item view`.

### Task 1.3c: Board and Dates filters

**Files:**
- Create: `wikantik-frontend/src/components/work/WorkTypeLabelFilters.jsx`, `WorkTypeLabelFilters.test.jsx`
- Modify: `WorkBoard.jsx`, `WorkDates.jsx`
- Modify test: `WorkBoard.test.jsx`, `WorkBoard.edges.test.jsx`, `WorkDates.test.jsx` (add `listLabels` to their api mocks)

**Interfaces — produces:**
- `WorkTypeLabelFilters({ type, label, labels, onType, onLabel })`: two `Select`s, `ariaLabel="Filter by type"` (placeholder "All types", `TYPE_OPTIONS`) and `ariaLabel="Filter by label"` (placeholder "All labels", options from `labels` as `{ value: name, label: \`${name} (${items})\` }`). Presentational: it loads nothing.
- `WorkBoard`: loads `listLabels(project.key)` with products/members (failure: `console.warn`, empty list); URL params `type` and `label` (set and cleared like `product`); `getBoard` gets `type` and `label`; `filtered` and the `onCreated` match also check `item.type === typeFilter` and `item.labels.includes(labelFilter)`; passes `labelSuggestions` to `WorkItemForm`. Refresh the label list after an item is created.
- `WorkDates`: reads `type` and `label` with `useSearchParams`, renders `WorkTypeLabelFilters`, loads `listLabels(project.key)`, passes `type` and `label` to `listItems`, and the empty text becomes "No open items match these filters." when a filter is set; each row shows the bug marker for a bug.

- [ ] **Step 1: Failing vitest.** `WorkTypeLabelFilters.test.jsx`: `lists the labels with their counts`; `choosing a type calls onType`. `WorkBoard.test.jsx`: `the label filter requests label=` (choose `ui`: `getBoard` last called with `{ label: 'ui' }`, and the URL holds `label=ui`); `the type filter requests type=`; `a created item that does not carry the filtered label is not added to the board`. `WorkDates.test.jsx`: `filters by type and label` (`listItems` called with `type: 'bug', label: 'ui'` from `?type=bug&label=ui`); `a bug row shows the marker`.
- [ ] **Step 2:** `npx vitest run src/components/work/WorkTypeLabelFilters.test.jsx src/components/work/WorkBoard.test.jsx src/components/work/WorkBoard.edges.test.jsx src/components/work/WorkDates.test.jsx`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Re-run: PASS. `npx vitest run src/components/work` and `npm run lint`.
- [ ] **Step 5: Commit** `git add` the seven files by path — `feat(work-ui): filter the board and dates by type and label`.

### Task 1.4: IT, gate and docs

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkApiIT.java`
- Modify docs: `wikantik-work/CLAUDE.md`, `wikantik-work-mcp/CLAUDE.md`, `docs/developer/ProjectReference.md`, `docs/admin/McpAgents.md`, `docs/user/WorkManagement.md`, `CHANGELOG.md`

- [ ] **Step 1: IT.** `WorkApiIT.nonMemberGets404Everywhere` gains `new Probe( "GET", "/api/work/projects/PLAT/labels", "/api/work/projects/GHOST/labels", null, 404 )`. New method `typeAndLabelsRoundTripAndFilter`: the member creates a task with `type: bug`, `labels: ["UI", "billing"]` (response `labels` `["billing","ui"]`); patches `labels` to `["ui"]`; `GET /api/work/items/<key>/events` ends with a `field` event, note `labels`, from `billing,ui`, to `ui`; `GET /api/work/projects/PLAT/board?label=ui&type=bug` holds the item and `?label=billing` does not; `GET /api/work/projects/PLAT/labels` lists `ui`.
- [ ] **Step 2: Docs.**
  - `wikantik-work/CLAUDE.md`: a rule bullet: "**Type and labels** (V084): `work_item.type` is `feature|bug|chore` (an epic is always `feature`, refused otherwise in `ItemPatchApplier.validateShape`); `work_item.labels` is a sorted, lower-case `TEXT[]` of at most 10 names matching `^[a-z0-9][a-z0-9._-]{0,39}$`, validated only by `Labels.normalise`. There is no label table: `WorkService.labelsOf` aggregates non-cancelled items. A change writes one `field` event (notes `type`, `labels`; labels comma-joined, none as null). `Item`, `ItemDraft` and `ItemFilter` keep secondary constructors without the two fields for tests; main code uses the canonical constructors." Add V084 to the migrations bullet. Amend the phase-3b sentence "`ItemView`, `ItemSummary` and `Item` are unchanged by this phase" to say it applied to phase 3b and that `Item` gained `type` and `labels` in V084.
  - `wikantik-work-mcp/CLAUDE.md`: in "Allow-listed arguments", `update_item` and `create_item` take `type` and `labels`; `kind` stays not settable.
  - `docs/developer/ProjectReference.md`: under "Work management — status", a line "**Small teams, part 1 (labels and type).** V084; plan `docs/superpowers/plans/2026-11-21-work-team-01-labels-and-type.md`." and add "labels (`projects/{key}/labels`), type and label filters" to the `/api/work/*` row of the agent-facing surface table.
  - `docs/admin/McpAgents.md`: the `update_item` and `create_item` rows name type and labels.
  - `docs/user/WorkManagement.md`, section 4 "Items and the board": a paragraph on type (what bug, feature and chore are for; epics are features), labels (type one to create it, Enter adds it, at most 10, colour is automatic, a label disappears from suggestions when no open or done item carries it) and the two filters on Board and Dates.
  - `CHANGELOG.md` `[Unreleased]` → `### Added`: "Work items have a type (feature, bug or chore) and up to ten labels. Both can be set from the board's new-item form, the item page, REST (`type`, `labels`; `GET /api/work/projects/{key}/labels`) and the work MCP (`create_item`, `update_item`), and the board and dates views filter by them. Schema `V084`."
  - `docs/admin/WikantikOperations.md`: no change; nothing for an operator to do (V084 is applied by `bin/db/migrate.sh` as part of `bin/redeploy.sh`).
- [ ] **Step 3: Gate.** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`, then `bin/agent-build.sh wait gate 540` repeatedly until it reports `SUCCESS exit=0`. A failure is fixed in this task, whatever its origin.
- [ ] **Step 4: Commit** `git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WorkApiIT.java wikantik-work/CLAUDE.md wikantik-work-mcp/CLAUDE.md docs/developer/ProjectReference.md docs/admin/McpAgents.md docs/user/WorkManagement.md CHANGELOG.md` — `docs(work): labels and item type`.

## Deviations from the outline

- **The epic rule lives in `ItemPatchApplier.validateShape`, not `WorkInputs`.** The outline put it in `WorkInputs`, which only holds name, key and link checks and never sees the level and type together on both create and patch; `validateShape` already holds the sibling rule "An epic has no product of its own." and runs on both paths. `WorkInputs` changes only so `withVersion` carries the two fields.
- **Files the outline missed:** `LabelCount.java`, `WorkStore.java`, `WorkScopes.java` (null-filter pin), `WorkService.java` (`createItem` rebuilds the draft; `labelsOf`), `WorkResourceBase.java` (`queryValues`), `WorkMcpArgs.java`, the instructions file, `boardModel.js`, `work.css`, a shared `WorkTypeLabelFilters.jsx`, and tests `ItemFilterTest`, `WorkLabelsPostgresTest`, `JdbcWorkStoreItemsRollbackTest`, `WorkMcpFlowPostgresTest`, `WorkApiIT`. `JdbcWorkStore.java` and `JdbcWorkItemWrites.java` are both modified (the outline wrote "/").
- **Outline Task 1.1 split into 1.1a (migration), 1.1b (pure rules) and 1.1c (model, store, events); 1.2 into 1.2a (store and service), 1.2b (REST), 1.2c (MCP); 1.3 into 1.3a, 1.3b, 1.3c.** Same content, smaller review units.
- **`ItemFilter.labels` is normalised on construction** (P1-4), so a filter label is lower-cased and validated like a stored one; the outline did not say.
- **The label filter also accepts comma-separated values** (P1-3), because the existing frontend client sends arrays that way.
