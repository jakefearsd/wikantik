# Work Onboarding and Everyday Usability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut first-project setup friction in work management: one-pass agent onboarding, a project default product, a lighter item form, a getting-started card, member suggestions, and setup hints for agents.

**Architecture:**
- **Backend:** one DDL migration, a new `Project` component, and SQL in a new `JdbcProjectDefaults`. Two new `WorkFeatures` methods (`WorkService` is at its PMD limit). The REST and MCP changes are additive.
- **Frontend:** the changes stay inside `src/components/work/` and `src/api/work.js`.
- **Contracts:** no new MCP tool and no data in migrations.

**Tech Stack:** Java 25, JDBC over `wikantik-jdbc` (`PostgresTestDb`), Gson, JUnit 5 + Mockito, React 19 + Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-11-27-work-onboarding-usability-design.md`

## Global Constraints

- **Migration:** `bin/db/migrations/V099__work_project_default_product.sql`. DDL only, idempotent (`ADD COLUMN IF NOT EXISTS`), no backfill, and no `INSERT` into any table.
- **Where new code goes:** add no method to `WorkService` (40-method PMD limit) or to `JdbcWorkStore` (GodClass). New service entry points go on `WorkFeatures`; new SQL goes in a new `Jdbc*` class.
- **Visibility classification:** every new public method on a facade is added by name to the right set in `WorkServiceVisibilityPostgresTest`: `setDefaultProduct` under `WRITES`, `groupSuggestions` under `READS`.
- **`Project` constructor:** keeps only its canonical constructor. All 28 `new Project(` sites gain the final `Long defaultProductId` argument; none get a convenience overload.
- **Default-product events:** a `settings` event with note `default_product`, from/to = product **keys** (or null), in the same transaction as the change.
- **Project JSON:** carries `defaultProductKey` and never `defaultProductId`.
- **Suggestions:** carry display names and group names only, never a login.
- **`productKey` on create:**
  - omitted or JSON null: use the project default (non-epic, unarchived default only);
  - `""`: no product;
  - any other key: resolved as today.
- **MCP surface:** no tool added or renamed (`WorkMcpIT.EXPECTED_TOOLS` stays 16), and `WorkMcpInstructionsDriftTest` must stay green.
- **Never swallow exceptions:** every catch logs at least `LOG.warn` (Java) or `console.warn` (frontend), with context.
- **Token handling:** the token is never logged or stored; it leaves the page only through `navigator.clipboard.writeText` on an explicit click.
- **Exact copy:**
  - Starter queues: `implementer` / "Implementer" / "Ready work waiting for an agent to implement."; `review` / "Review" / "Completed work waiting for review."
  - Setup command: `claude mcp add --transport http wikantik-work <endpoint> --header "Authorization: Bearer <token>"`
  - Type caption: "What kind of work: feature, bug or chore". Kind caption: "Which area: software, docs or admin".
  - The three `setup` hint strings are verbatim in the spec, section 6.
- **Final gate:** `bin/run-tests.sh --parallel 4`, run through `bin/agent-build.sh`.

## Review Focus

1. **Blank vs omitted `productKey`:** `""` must give no product and omitted must inherit, on REST and MCP. The tests are in Task 2 (applier) and Task 3 (REST + MCP).
2. **A default product archived concurrently with item creation:** inheritance yields no product rather than failing. The Task 2 test `archivedDefaultIsNotInherited`.
3. **A caller who leads no project registers an agent:** no fieldset and zero `putMember` calls. Task 6 test.
4. **`putMember` rejects after a successful register:** the token panel stays and the warning names the project. Task 6 test.
5. **A non-member calls `group-suggestions`:** 404, identical to an unknown project. Task 4 visibility classification plus a REST test.

---

### Task 1: Schema, `Project.defaultProductId`, and the catalog store

**Files:**
- Create: `bin/db/migrations/V099__work_project_default_product.sql`
- Create: `wikantik-work/src/main/java/com/wikantik/work/JdbcProjectDefaults.java`
- Modify:
  - `wikantik-work/src/main/java/com/wikantik/work/Project.java:24`
  - `JdbcWorkCatalog.java`: lines 41 and 47-50 (columns and mapper), line 401 (`archiveProduct`), plus the new methods
  - `WorkCatalogStore.java`
- Modify all 28 `new Project(` sites:
  - `JdbcWorkCatalog.java:47`
  - `wikantik-rest/src/test/java/com/wikantik/rest/WorkHttp.java:130`
  - Under `wikantik-work/src/test/java/com/wikantik/work/`: `WorkServiceTest` (6), `WorkClaimsServiceTest` (5), `WorkServiceFixWaveTest` (4), `FindSpecsTest` (2), `WorkServiceDenialListenerTest` (2), and one each in `CodeHostApplierFactsTest`, `ItemViewsTest`, `NoticesUnitTest`, `PolicyItems`, `WorkClaimListenerTest` and `WorkRegistryServiceTest`
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkProjectDefaultProductPostgresTest.java` (new)

**Interfaces:**
- Produces:
  - `record Project( long id, String key, String name, String description, Autonomy defaultAutonomy, Instant createdAt, Instant archivedAt, Long defaultProductId )`
  - `WorkCatalogStore.setDefaultProduct( long projectId, Product productOrNull, Actor by ) throws SQLException`
  - `WorkCatalogStore.product( long id ) throws SQLException` returns `Optional< Product >` (archived included)
  - Archiving a product clears it from every project's default.

- [ ] **Step 1: Write the migration.**

```sql
-- Work: a project may name a default product for the stories and tasks filed in it (DDL only).
ALTER TABLE work_project
    ADD COLUMN IF NOT EXISTS default_product_id BIGINT REFERENCES work_product (id);
```

- [ ] **Step 2: Write the failing Postgres test.** Use `@RequiresPostgres` and `PostgresTestDb.truncate(...)` in `@BeforeEach`, following `WorkServiceVisibilityPostgresTest` for the fixtures.

```java
@Test void setDefaultProductPersistsAndWritesOneSettingsEventWithKeys()
    // set to product "web": project(key).defaultProductId() == web.id();
    // events of the project contain exactly one SETTINGS event: note "default_product", from null, to "web"
@Test void settingTheSameDefaultAgainWritesNoEvent()
@Test void clearingWritesFromKeyToNull()
    // set "web", then null -> event from "web", to null; defaultProductId() == null
@Test void archivingAProductClearsItAsEveryProjectsDefault()
    // two projects default to "web"; archiveProduct(web) -> both defaultProductId() null,
    // each project has a SETTINGS event note "default_product" from "web" to null, actor = the archiver
@Test void productByIdFindsArchivedProductsToo()
```

- [ ] **Step 3: Run it to verify it fails.**
Run: `mvn test -pl wikantik-work -Dtest=WorkProjectDefaultProductPostgresTest -q`
Expected: compilation failure (no `setDefaultProduct` / 8-arg `Project`).

- [ ] **Step 4: Implement.**
  - Add the `Project` component. Add `default_product_id` to `PROJECT_COLUMNS` and read it in `PROJECT_ROW` with `NullableColumns.getLong` (or the module's existing nullable-long helper). Update every construction site (`null` unless the test is about the default).
  - `JdbcProjectDefaults` (package-private, final):
    - `static void set( Connection conn, long projectId, Product productOrNull, Actor by )` locks the row with `SELECT default_product_id … FOR UPDATE`. If the value changed, it updates, then appends `JdbcWorkCatalog.projectEvent( projectId, by, WorkEvent.SETTINGS, fromKey, toKey, "default_product" )`. It resolves the old key by id within the same connection.
    - `static void clearFor( Connection conn, Product archived, Actor by )` runs `UPDATE … SET default_product_id = NULL WHERE default_product_id = ? RETURNING id` and appends one event per returned id (from = `archived.key()`, to = null).
  - `JdbcWorkCatalog`:
    - `setDefaultProduct` wraps `set` in a transaction.
    - `product( long id )` runs `SELECT <product columns> FROM work_product WHERE id = ?`.
    - `archiveProduct` calls `clearFor` inside its existing transaction, only when the archive `UPDATE` changed a row.

- [ ] **Step 5: Run the module tests.**
Run: `mvn test -pl wikantik-work -q`, then `mvn test-compile -pl wikantik-rest -am -q -DskipTests`
Expected: all pass, and wikantik-rest test sources compile.

- [ ] **Step 6: Commit.**
`git add` the migration, `Project.java`, `JdbcProjectDefaults.java`, `JdbcWorkCatalog.java`, `WorkCatalogStore.java`, the new test, and the 13 edited test files by name. Message: `feat(work): projects can name a default product; archiving a product clears it`.

---

### Task 2: `WorkFeatures.setDefaultProduct` and inheritance on create

**Files:**
- Modify: `wikantik-work/src/main/java/com/wikantik/work/WorkFeatures.java`, `ItemPatchApplier.java:66-75`
- Test: `WorkProjectDefaultProductPostgresTest.java` (extend), `WorkServiceVisibilityPostgresTest.java:54` (`WRITES`)

**Interfaces:**
- Consumes: Task 1's `setDefaultProduct( long, Product, Actor )` and `product( long )`.
- Produces: `public void setDefaultProduct( Actor actor, String projectKey, String productKey ) throws SQLException` on `WorkFeatures`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void leadSetsDefaultMemberIsDenied()          // lead OK; plain member -> WorkForbiddenException (or the module's denial type); stranger -> WorkNotFoundException
@Test void unknownOrArchivedProductIsRefusedNamingDefaultProductKey()
    // WorkValidationException field "defaultProductKey", message "No such product: nope"
@Test void archivedProjectIsRefusedAsArchived()     // WorkConflictException reason archived
@Test void storyWithoutProductKeyInheritsTheDefault()      // draft productKey null -> item.productId() == web.id()
@Test void blankProductKeyMeansNoProduct()                 // draft productKey "" -> productId() null
@Test void explicitProductKeyWins()                        // "api" -> api.id()
@Test void epicNeverInherits()                             // level EPIC, productKey null -> productId() null
@Test void archivedDefaultIsNotInherited()
    // set default "web", then archive web directly with SQL (simulating the race, bypassing clearFor):
    // UPDATE work_product SET archived_at = now() WHERE id = ?; create story -> productId() null, no exception
```

Add `"setDefaultProduct"` to `WRITES` in `WorkServiceVisibilityPostgresTest`.

- [ ] **Step 2: Run them to verify they fail.**
Run: `mvn test -pl wikantik-work -Dtest='WorkProjectDefaultProductPostgresTest,WorkServiceVisibilityPostgresTest' -q`
Expected: FAIL.

- [ ] **Step 3: Implement.**
  - `WorkFeatures.setDefaultProduct`:
    - Run `scopes.scope( actor, projectKey )`, then `s.require( Action.MANAGE_PROJECT, null )`, then `s.requireWritable()`.
    - If `productKey` is blank, pass null. Otherwise use `store.product( key.trim() )`, keeping it only if it exists and is unarchived; failing that, throw the validation error above.
    - Finish with `store.setDefaultProduct( s.project().id(), product, actor )`.
  - `ItemPatchApplier.forCreate`:
    - Replace line 71 with `final Product product = productFor( project, d );`.
    - New private `productFor`, by the value of `d.productKey()`:
      - null: `inherited( project, d.level() )`;
      - blank: null;
      - otherwise: `resolveProduct( key )`.
    - `inherited` returns null for `WorkLevel.EPIC` or a null `defaultProductId`, and otherwise `store.product( id )` filtered to unarchived, or null.

- [ ] **Step 4: Run the module tests.**
Run: `mvn test -pl wikantik-work -q`
Expected: PASS.

- [ ] **Step 5: Commit.**
Message: `feat(work): stories and tasks filed without a product take the project default`.

---

### Task 3: REST project JSON and PATCH; MCP `create_item` text

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/WorkResourceBase.java:331-350`, `WorkProjectResource.java` (`update`, about line 131)
- Modify: `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/CreateItemTool.java:54`, `wikantik-work-mcp/src/main/resources/wikantik-work-mcp-instructions.txt:34`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/WorkProjectResourceTest.java`, `WorkItemResource` test class (whichever exists for item create), `wikantik-work-mcp/src/test/java/com/wikantik/work/mcp/CreateItemToolTest.java`

**Interfaces:**
- Consumes: `WorkFeatures.setDefaultProduct` from Task 2, and `Project.defaultProductId()`.
- Produces:
  - Project JSON field `defaultProductKey` (string or null).
  - `PATCH /api/work/projects/{key}` accepts `defaultProductKey`.

- [ ] **Step 1: Write the failing tests.**

```java
// WorkProjectResourceTest
@Test void projectJsonCarriesDefaultProductKeyNeverItsId()
    // GET project: json.get("defaultProductKey").getAsString() == "web"; !json.has("defaultProductId")
@Test void projectListCarriesDefaultProductKeyToo()
@Test void patchDefaultProductKeySetsAndBlankClears()
    // PATCH {"defaultProductKey":"web"} -> verify(features).setDefaultProduct(actor,"PLAT","web")
    // PATCH {"defaultProductKey":""} -> setDefaultProduct(actor,"PLAT","")
@Test void patchWithoutDefaultProductKeyDoesNotTouchIt()   // verify(features, never()).setDefaultProduct(...)
// item-create resource test
@Test void blankProductKeyReachesTheDraftAsBlank()          // body {"productKey":""} -> captured ItemDraft.productKey() == ""
@Test void absentProductKeyReachesTheDraftAsNull()
// CreateItemToolTest
@Test void productKeyArgumentExplainsTheDefault()            // arg description contains "Omit it to use the project's default product"
@Test void emptyProductKeyIsPassedThroughAsEmpty()
```

- [ ] **Step 2: Run them to verify they fail.**
Run: `mvn test -pl wikantik-rest -Dtest='WorkProjectResourceTest' -q` and `mvn test -pl wikantik-work-mcp -Dtest=CreateItemToolTest -q`
Expected: FAIL.

- [ ] **Step 3: Implement.**
  - `projectWithRole( Project, String role, Map< Long, String > productKeys )`: remove `defaultProductId` from the Gson tree, then add `defaultProductKey` (`productKeys.get(id)`, or null).
  - `visibleProjects` / `projectJson` build the map once per response from `c.svc.listProducts( c.actor )` (id → key). Update every caller.
  - In `WorkProjectResource.update`: if `b.has( "defaultProductKey" )`, call `c.svc.features().setDefaultProduct( c.actor, key, optString( b, "defaultProductKey" ) )`. A null here means clear, so pass `""` when it is null.
  - The MCP argument text, and the `create_item` line in the instructions file, gain: "Omit it to use the project's default product; pass an empty string for none."

- [ ] **Step 4: Run the tests.**
Run: `mvn test -pl wikantik-rest,wikantik-work-mcp -q`
Expected: PASS, including `WorkMcpInstructionsDriftTest`.

- [ ] **Step 5: Commit.**
Message: `feat(work): project JSON and PATCH carry defaultProductKey; create_item explains inheritance`.

---

### Task 4: Group suggestions for project leads

**Files:**
- Modify:
  - `wikantik-work/src/main/java/com/wikantik/work/WorkDirectory.java`
  - `wikantik-main/src/main/java/com/wikantik/work/runtime/WikiWorkDirectory.java`
  - `WorkFeatures.java`
  - `wikantik-rest/src/main/java/com/wikantik/rest/WorkProjectResource.java` (route table at line 36)
  - `wikantik-frontend/src/api/work.js`
- Test:
  - `WorkServiceVisibilityPostgresTest` (`READS`)
  - a new `WorkGroupSuggestionsTest` (Mockito, in `wikantik-work`)
  - `WikiWorkDirectoryTest` (create if absent)
  - `WorkProjectResourceTest`
  - `src/api/work.test.js`

**Interfaces:**
- Produces:
  - `default Set< String > groupNames()` on `WorkDirectory`.
  - `public List< String > groupSuggestions( Actor actor, String projectKey, String query ) throws SQLException` on `WorkFeatures`.
  - `GET /api/work/projects/{key}/group-suggestions?q=` returns `{ "groups": [ "…" ] }`.
  - `suggestGroups( projectKey, q )` in `work.js`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void matchesCaseInsensitivelyPrefixFirstThenAlphabeticalCappedAtTen()
    // groupNames {"Engineering","Admin","Eng-Leads","Reviewers", +12 "Team-NN"}; q "eng" -> ["Eng-Leads","Engineering"]
@Test void blankQueryReturnsNothing()
@Test void memberWhoIsNotLeadIsDeniedStrangerGets404()
@Test void wikiDirectoryListsGroupNamesFromGroupManagerRoles()   // WikiWorkDirectoryTest
@Test void groupSuggestionsRouteReturnsNamesOnly()               // WorkProjectResourceTest: {"groups":["Engineering"]}
```

Add `"groupSuggestions"` to `READS`.

- [ ] **Step 2: Run them to verify they fail.**
Expected: FAIL (missing methods).

- [ ] **Step 3: Implement.**
  - `WorkFeatures.groupSuggestions`: requires `Action.MANAGE_PROJECT`. A blank query returns `List.of()`. Otherwise filter `directory.groupNames()` (from `FeatureDeps.directory()`; keep a field) by lower-case substring, sort prefix matches first and then `String.CASE_INSENSITIVE_ORDER`, and keep the first 10.
  - `WikiWorkDirectory.groupNames()` iterates `GroupManager.getRoles()` exactly as `WikiWorkGroups.groupsOf` does, collecting `getName()`.
  - REST route: `GET projects/{}/group-suggestions`.
  - `work.js`: `suggestGroups(key, q)` calls GET with `encodeURIComponent(q)`.

- [ ] **Step 4: Run the tests.**
Run: `mvn test -pl wikantik-work,wikantik-rest -q`, then `mvn test -pl wikantik-main -Dtest=WikiWorkDirectoryTest -q`, then (from `wikantik-frontend`) `npx vitest run src/api/work.test.js`
Expected: PASS.

- [ ] **Step 5: Commit.**
Message: `feat(work): project leads get group-name suggestions`.

---

### Task 5: `get_work_context` setup hints

**Files:**
- Modify: `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/GetWorkContextTool.java`, `wikantik-work-mcp-instructions.txt`
- Test: `GetWorkContextToolTest.java`

**Interfaces:**
- Produces: a `setup` key on the result (a list of strings, always present).

- [ ] **Step 1: Write the failing tests.**

```java
@Test void personGetsAnEmptySetupList()
@Test void wellSetUpAgentGetsAnEmptySetupList()         // sponsor "jake", one project, one queue
@Test void agentWithoutSponsorIsToldFirst()             // sponsor null -> setup.get(0) starts "Nobody sponsors you"
@Test void agentInNoProjectIsToldToAskItsSponsor()      // listProjectsWithRoles -> [] -> contains the project hint verbatim
@Test void agentWithNoQueueIsToldListClaimableIsEmpty() // queues [] -> contains "You serve no queue"
@Test void descriptionNamesTheSetupField()
```

- [ ] **Step 2: Run them to verify they fail.**
Run: `mvn test -pl wikantik-work-mcp -Dtest=GetWorkContextToolTest -q`
Expected: FAIL.

- [ ] **Step 3: Implement.**
  - Build `setup` after `projects`, from data the tool already has: `Agent self` (sponsor, queues) and the projects list. Use the three spec strings verbatim, in the spec's order.
  - Add `setup:[…]` to the description's return shape and to the instructions text's `get_work_context` line.

- [ ] **Step 4: Run the module.**
Run: `mvn test -pl wikantik-work-mcp -q`
Expected: PASS, including the drift test.

- [ ] **Step 5: Commit.**
Message: `feat(work-mcp): get_work_context says why an agent can claim nothing`.

---

### Task 6: One-pass agent onboarding and the token panel

**Files:**
- Modify: `wikantik-frontend/src/components/work/WorkAgents.jsx` (about lines 54-120), `WorkAgentForm.jsx`, `WorkTokenPanel.jsx`
- Test: `WorkAgents.test.jsx`, `WorkAgentForm.test.jsx`, `WorkTokenPanel.test.jsx` (new)

**Interfaces:**
- Consumes: `registerAgent`, `putMember` (`src/api/work.js`), and `apiUrl` (`src/api/client`).
- Produces:
  - `WorkAgentForm` prop `projects` (eligible list); its `onSubmit` result carries `admitTo: string[]`.
  - `WorkTokenPanel` prop `endpoint`.

- [ ] **Step 1: Write the failing tests.**

```js
// WorkTokenPanel.test.jsx
it('shows the endpoint and a claude mcp add command holding the token', ...)
  // getByText(endpoint); getByText(/claude mcp add --transport http wikantik-work https:\/\/h\/work-mcp --header "Authorization: Bearer wka_x"/)
it('copies token, URL and command only on click, and logs nothing', ...)   // spy console.log/info not called with the token
// WorkAgentForm.test.jsx
it('pre-checks the only eligible project', ...)
it('leaves several eligible projects unchecked', ...)
it('hides the fieldset when no project is eligible', ...)
// WorkAgents.test.jsx
it('shows the token before admitting, then admits to each checked project', ...)
  // registerAgent resolves; token panel visible; putMember called with ('PLAT', {principal:'bot', principalKind:'agent', role:'member'})
it('keeps the token and names the project when admission fails', ...)
  // putMember rejects for 'OPS' -> alert text includes 'OPS' and 'Settings'; token panel still present
it('makes no putMember call when nothing is checked', ...)
```

- [ ] **Step 2: Run them to verify they fail.**
Run (from `wikantik-frontend`): `npx vitest run src/components/work/WorkAgents.test.jsx src/components/work/WorkAgentForm.test.jsx src/components/work/WorkTokenPanel.test.jsx`
Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Eligible projects:** `projects.filter(p => !p.archived && (isAdmin || p.role === 'lead'))`.
  - **Fieldset:** sits below Queues and is pre-checked when exactly one project is eligible.
  - **`registered(made, admitTo)`:**
    1. `addKey` first.
    2. Then `Promise.allSettled` over `putMember`.
    3. Collect the rejected keys into an inline `role="alert"` warning: "Could not add bot to OPS. Add it from that project's Settings."
    4. Then `load()`.
  - **Token panel:**
    - `endpoint` comes from `${window.location.origin}${apiUrl('/work-mcp')}`, computed in `WorkAgents` and passed down. Rotating a key passes it too.
    - Three copy buttons share the existing `copy` logic, parameterised by the text and a label for the status message.
    - Each `console.warn` logs only `err?.name`.

- [ ] **Step 4: Run the tests.**
Run: the same vitest command plus `npx vitest run src/components/work/WorkAgents.edges.test.jsx`
Expected: PASS.

- [ ] **Step 5: Commit.**
Message: `feat(work-ui): register an agent and admit it in one pass; key panel shows the MCP setup`.

---

### Task 7: Default product in the UI, the lighter item form, and the autonomy fix

**Files:**
- Modify:
  - `wikantik-frontend/src/components/work/WorkItemForm.jsx` (state at line 24, submit at line 66, fields at lines 95-140)
  - `WorkSettings.jsx` (`Details` at line 40; lines 76 and 359)
  - `WorkHome.jsx` (`NewProjectForm`, lines 18-40)
- Test: `WorkItemForm.test.jsx`, `WorkSettings.test.jsx`, `WorkHome.test.jsx`

**Interfaces:**
- Consumes:
  - Project JSON `defaultProductKey` (Task 3).
  - Products as `[{ key, name, archived }]` (non-admin shape).
  - `updateProject( key, { defaultProductKey } )`.

- [ ] **Step 1: Write the failing tests.**

```js
// WorkItemForm.test.jsx
it('pre-selects the project default product', ...)          // project.defaultProductKey 'web' -> submitted draft.productKey === 'web'
it('pre-selects the only active product when there is no default', ...)
it('sends an empty productKey when the user chooses No product', ...)  // draft.productKey === ''
it('omits productKey for an epic', ...)
it('keeps Type, Kind, Priority, Due date and Labels under More options, collapsed', ...)
  // queryByLabelText('Priority') null until click 'More options'; toggle text matches /Product: web · Feature · Software · P3/
it('explains Type and Kind', ...)                            // the two exact captions
it('submits the same defaults whether More options was opened or not', ...)
// WorkSettings.test.jsx
it('falls back to delegated autonomy', ...)                  // project without defaultAutonomy -> 'Delegated' selected
it('saves the default product and shows an archived current default', ...)
// WorkHome.test.jsx
it('sets the chosen default product after creating a project', ...)   // createProject then updateProject('NEW',{defaultProductKey:'web'})
it('still opens the new project when setting the default fails', ...) // toast error, navigate called
```

- [ ] **Step 2: Run them to verify they fail.**
Run: `npx vitest run src/components/work/WorkItemForm.test.jsx src/components/work/WorkSettings.test.jsx src/components/work/WorkHome.test.jsx`
Expected: FAIL.

- [ ] **Step 3: Implement.** Follow spec sections 2 (Frontend) and 3.
  - **Item form:**
    - `initialProduct(project, products)` returns `project.defaultProductKey` if it is among the active products, else the only active product's key if there is exactly one, else `''`.
    - Submit sets `draft.productKey = productKey` for every non-epic, empty string included.
    - The "More options" toggle is a `<button aria-expanded>`.
  - **Settings and new-project form:** the Default product select uses the existing `Select` component. `WorkSettings` adds `listProducts` to its imports.

- [ ] **Step 4: Run all the work component tests.**
Run: `npx vitest run src/components/work/`
Expected: PASS.

- [ ] **Step 5: Commit.**
Message: `feat(work-ui): default product on projects and items; item form shows essentials first`.

---

### Task 8: Getting-started card, starter queues, and member suggestions

**Files:**
- Create: `wikantik-frontend/src/components/work/starterQueues.js`, `WorkGettingStarted.jsx`
- Modify: `WorkHome.jsx`, `WorkQueues.jsx` (the empty state at about line 83), `WorkSettings.jsx` (the add-member form at lines 281-303)
- Test: `starterQueues.test.js`, `WorkGettingStarted.test.jsx`, `WorkQueues.test.jsx`, `WorkSettings.test.jsx`

**Interfaces:**
- Consumes: `createQueue`, `listQueues`, `listAgents`, `listProducts` (`work.js`); `listMentionableUsers` (`client.js`); `suggestGroups` (Task 4).
- Produces: `createStarterQueues(existingKeys: string[]) => Promise<{ created: string[], failed: string[] }>`.

- [ ] **Step 1: Write the failing tests.**

```js
// starterQueues.test.js
it('creates implementer and review with the exact names and descriptions', ...)
it('skips a key that already exists, retired or not', ...)
it('reports a failed create without throwing', ...)      // console.warn called with context
// WorkGettingStarted.test.jsx
it('shows admins each step with its real state', ...)
it('offers Create starter queues when there is no active queue', ...)
it('tells a non-admin with no project who to ask', ...)  // exact sentence from the spec
it('stays hidden after Dismiss and survives storage that throws', ...)  // localStorage.getItem throws -> card renders
it('renders nothing when a load fails', ...)
// WorkQueues.test.jsx
it('offers starter queues to admins on the empty state only', ...)
// WorkSettings.test.jsx
it('suggests display names for a user after two characters', ...)   // datalist options from listMentionableUsers(q,10), after 200 ms debounce (fake timers)
it('suggests groups from the project endpoint', ...)
it('still adds a typed name that was never suggested', ...)
```

- [ ] **Step 2: Run them to verify they fail.**
Expected: FAIL.

- [ ] **Step 3: Implement.** Follow spec sections 4 and 5.
  - The card sits at the top of `WorkHome`.
  - Its localStorage key is `wikantik.work.gettingStarted.dismissed`.
  - The datalist id is `work-add-principal-suggestions`, attached by `list=` to `#work-add-principal`. It is cleared when the kind changes.

- [ ] **Step 4: Run all frontend checks.**
Run: `npx vitest run src/components/work/ src/api/work.test.js`, then `npx eslint src/components/work src/api/work.js`
Expected: PASS, no lint errors.

- [ ] **Step 5: Commit.**
Message: `feat(work-ui): getting-started card, starter queues, member suggestions`.

---

### Task 9: Documentation and the full gate

**Files:**
- Modify: `docs/user/WorkManagement.md` (sections 2, 3 and 7), `wikantik-work/CLAUDE.md`, `CHANGELOG.md` (`[Unreleased]`)

- [ ] **Step 1: Write the docs.**
  - `WorkManagement.md`:
    - §2: the default product, and that archiving a product clears it.
    - §3: suggestions, and setting the default on create and in settings.
    - §7: one-pass admission, the setup command, starter queues, and the `setup` hints.
  - `wikantik-work/CLAUDE.md` gets two bullets:
    - the null / `""` `productKey` contract plus clear-on-archive (`JdbcProjectDefaults`, `WorkProjectDefaultProductPostgresTest`);
    - `groupSuggestions` (lead-only, names only).
  - CHANGELOG: one line.

- [ ] **Step 2: Run the gate.**
Run: `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`, then poll `bin/agent-build.sh status gate` until it finishes.
Expected: `SUCCESS exit=0`.

- [ ] **Step 3: Commit.**
Message: `docs(work): onboarding, default product, suggestions and setup hints`.
