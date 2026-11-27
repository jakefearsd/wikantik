# Work Management Onboarding and Everyday Usability — Design

Date: 2026-11-27
Status: approved by owner 2026-11-27 ("turn it into a design document and plan from that")
Parent: `docs/superpowers/specs/2026-11-15-work-management-design.md`
Supersedes: the root-level draft `WorkPackageImprovements.md` (removed once this document captured it)

## Intent

The work module's domain model is sound. Setting it up for a first project with one coding agent is
not: it takes eight steps over four screens, with nothing linking them, and several of the steps fail
silently. An agent left out of the project gets `Not found.` or an empty `list_claimable` and nothing
saying why. An item filed without a product fails later at `open_change`. This round removes that
friction and leaves the rules alone.

### The setup path today

1. Set `wikantik.work.enabled=true` and restart.
2. Create a product on `/work/products` (wiki admin only).
3. Create a project on `/work` (wiki admin only).
4. Open `/work/<KEY>/settings` and type exact display names into a plain text box with no suggestions.
5. Create queues on `/work/agents` (admin only) from nothing: V076 creates `work_queue` empty.
6. Register an agent on `/work/agents` and copy a bare `wka_` token. The panel shows no `/work-mcp` URL
   and no setup example.
7. Go back to `/work/<KEY>/settings`, switch the kind to Agent and add the agent as a member. Skip this
   and the agent sees nothing (an agent with no member row has nothing, whoever sponsors it).
8. File items in `WorkItemForm`. It shows ten fields at once, including Kind and Type, which look like
   the same thing. Product starts blank even when only one product exists.

## Goals

1. **One-pass agent onboarding.** Registering an agent also admits it to the projects the caller leads.
   The token panel shows the token, the `/work-mcp` URL and a ready-to-run setup command.
2. **Project default product.** A project may name a default product. Stories and tasks created
   without a product (UI, REST or MCP) get it.
3. **A lighter item form.** The essentials stay visible and the rest sits behind "More options". Type
   and Kind each get a one-line explanation.
4. **A getting-started card on `/work`.** It shows real completion state, offers a one-click "Create
   starter queues", and says what to do when the viewer cannot do a step themselves.
5. **Member suggestions in project settings.** Users and groups are suggested as the lead types.
6. **Agents are told why they can claim nothing.** `get_work_context` carries a `setup` list naming
   what is missing: a sponsor, a project membership or a queue.
7. **Fix the autonomy fallback.** `WorkSettings.jsx` falls back to `'supervised'` (lines 76 and 359),
   while V074's column default is `'delegated'`.

## Non-goals

- No change to visibility, standing, claims, gates or autonomy rules.
- No new or renamed MCP tool. `WorkMcpIT.EXPECTED_TOOLS` stays at 16.
- No data in migrations. Queues are not seeded by V099 (the owner's rule: migrations are DDL only).
- No self-service product or project creation for non-admins. Both stay wiki-admin only.
- No setup wizard spanning several pages. The card links to the existing screens.

## Anti-goals

- Exposing a login or a surrogate id anywhere new. Suggestions return display names and group names.
  Project JSON carries `defaultProductKey` and never `defaultProductId`.
- Shifting a failure later instead of removing it. Inheriting an archived product, or silently
  skipping inheritance so that `open_change` fails later, would recreate the problem goal 2 removes.
- A token that leaves the panel for anywhere but the clipboard on an explicit click.

## Constraints

- **PMD ceilings.** `WorkService` is at its 40-method limit and `JdbcWorkStore` at the GodClass
  boundary. New service methods go on `WorkFeatures`, and new SQL goes in a new `Jdbc*` class.
- **Visibility classification.** Every new public method on `WorkService` or a facade is listed in
  `WorkServiceVisibilityPostgresTest` (name-based sets: `WRITES`, `READS`, `INSTANCE_READS`, …).
- **No convenience constructors on records.** `Item` and `ItemDraft` keep only their full constructors
  on purpose. `Project` follows the same rule, so all 28 `new Project(` sites gain the new component.
- **Migration** `V099__work_project_default_product.sql`: DDL only, idempotent.
- **Events.** Every change writes its `work_event` in the same transaction. A project default-product
  change is a `settings` event whose note is `default_product` and whose from/to values are product
  **keys**, not ids, so the activity feed needs no translation in `EventKeys`.
- **Cross-repo contracts.** All MCP changes are additive: an optional `setup` field, and inheritance
  that applies only when `productKey` is omitted. Neither binds another repository, so no `SA-<n>`
  entry is needed.

## Approaches considered

**Starter queues.**
- (a) Seed them in V099, as the draft proposed. Rejected: it puts data in a migration, which breaks
  the owner's rule. It also adds two queues to an installation whose own queues have other keys.
- (b) Create them when work starts with an empty table. Rejected: hidden, and it brings back queues an
  admin deliberately retired.
- **(c) Chosen:** an explicit "Create starter queues" button wherever the empty queue state shows (the
  Queues panel and the getting-started card). It calls the existing `createQueue` twice
  (`implementer`, `review`), and a queue that already exists is skipped. The click is explicit, the
  queues are ordinary rows, and the migration holds no data.

**Member suggestions.**
- (a) A new `WorkDirectory.suggestPrincipals` that walks every account, as the draft proposed.
  Rejected: it re-solves login privacy that `MentionableUsersResource` already solves, and it scans
  every account on each keystroke.
- **(b) Chosen:** users from the existing `GET /api/users/mentionable`. It is signed-in only, matches
  display names only, never matches or returns a login, and is capped at 10. `putMember` already
  resolves a display name (`WorkPeople` → `loginForDisplayName`). Groups come from a new, small,
  lead-only `GET /api/work/projects/{key}/group-suggestions?q=`, because the only group listing today
  is admin-only `/admin/groups`.

**Default product when its product is archived.**
- (a) Skip inheritance silently, as the draft proposed. Rejected: see the anti-goals.
- (b) Refuse item creation. Rejected: it blocks a whole team over a settings slip.
- **(c) Chosen:** archiving a product clears it as the default of every project that names it, in the
  archive transaction, with one `settings` event per project (actor: the archiver). A default can
  therefore never point at an archived product. Inheritance still treats an archived default (a
  concurrent race) as "no default" rather than failing.

**Onboarding orchestration.**
- (a) A new parameter on `POST /api/work/agents` that admits too. Rejected: registry changes write no
  `work_event`, while `putMember` writes a project `member` event and re-checks sponsor standing.
  Joining them crosses transaction boundaries the module keeps apart.
- **(b) Chosen:** the UI calls `registerAgent`, shows the token at once, then calls `putMember` per
  selected project and reports any failures inline.

## Design

### 1. One-pass agent onboarding (frontend only)

- `WorkAgents.jsx` passes `projects` to `WorkAgentForm` when registering (`!agent`).
- **Eligible projects:** unarchived (`!p.archived`) projects where the caller is a wiki admin or
  `p.role === 'lead'`.
- **Control:** a fieldset "Add to projects as member" below the Queues checkboxes, one checkbox per
  eligible project, labelled `KEY — Name`.
  - It is pre-checked when exactly one project is eligible, and unchecked otherwise.
  - It is hidden when no project is eligible.
- **Order, so the one-time token is never lost:**
  1. `registerAgent(fields)` resolves to `{ agent, token }`.
  2. `addKey(agent.name, token)` runs immediately.
  3. Then `Promise.allSettled` over the selected projects calls
     `putMember(key, { principal: agent.name, principalKind: 'agent', role: 'member' })`.
  4. A failure shows an inline warning naming each failed project, with the advice to add the agent
     from that project's Settings. The token panel stays.
- **Token panel** (`WorkTokenPanel.jsx`) gains an optional `endpoint` prop.
  - The endpoint is computed as `${window.location.origin}${apiUrl('/work-mcp')}`, the same way
    `WorkProductHook.jsx` builds its URLs.
  - The panel shows the token (Copy token), the endpoint (Copy URL), and a setup command (Copy
    command): `claude mcp add --transport http wikantik-work <endpoint> --header "Authorization: Bearer <token>"`.
  - The command embeds the token, so it carries the same "copy now, shown once" warning.
  - The existing rules hold: the token is never logged or stored, it is dropped on Done, and only a
    resolved `writeText` counts as "Copied".
  - Rotating a key shows the same panel with the same endpoint.

### 2. Project default product

- **Schema** (V099):
  `ALTER TABLE work_project ADD COLUMN IF NOT EXISTS default_product_id BIGINT REFERENCES work_product (id);`
  No backfill.
- **Model:** `Project` gains a final component `Long defaultProductId`. `JdbcWorkCatalog.PROJECT_COLUMNS`
  and `PROJECT_ROW` read it. Every `new Project(` site passes it (`null` in tests unless a test is
  about it).
- **Store:** a new package-private `JdbcProjectDefaults` holds the SQL.
  - `set(conn, projectId, Product orNull, Actor by)` locks the project row, compares, updates, and
    appends the `settings` event (note `default_product`, from/to product keys or null). It writes
    nothing when the value is unchanged.
  - `clearFor(conn, Product archived, Actor by)` runs
    `UPDATE work_project SET default_product_id = NULL WHERE default_product_id = ? RETURNING id` and
    appends one event per returned project.
  - `WorkCatalogStore` gains `setDefaultProduct(long projectId, Product productOrNull, Actor by)` and
    `Optional<Product> product(long id)`. `JdbcWorkCatalog` implements both, delegating the first.
  - `JdbcWorkCatalog.archiveProduct` calls `clearFor` in its transaction, only when it archived a row.
- **Service:** `WorkFeatures.setDefaultProduct(Actor, String projectKey, String productKey)`.
  - It runs `scopes.scope`, requires `Action.MANAGE_PROJECT`, then `requireWritable()`.
  - A blank `productKey` clears the default. Otherwise the product must exist and be unarchived, or
    the call is refused with `WorkValidationException("defaultProductKey", "No such product: <key>")`.
  - It is classified under `WRITES`.
- **Inheritance** (`ItemPatchApplier.forCreate`), by the value of `productKey`:
  - **null (omitted):** a non-epic in a project with a default gets the default, if it is unarchived;
    otherwise the item has no product.
  - **blank (`""`):** explicitly no product. Today a blank key fails with "No such product", so no
    working caller changes.
  - **any other key:** resolved as today.
  - An epic never inherits.
  - REST `optString` and MCP `stringArg` already keep `""` distinct from absent and JSON null.
- **REST:**
  - Project JSON (`WorkResourceBase.projectWithRole`) removes `defaultProductId` and adds
    `defaultProductKey` (null when none), resolved from one `listProducts(actor)` map per response.
  - `PATCH /api/work/projects/{key}` accepts `defaultProductKey`: present means call
    `setDefaultProduct`, where `""` or null clears.
  - `POST /api/work/projects` is unchanged. The new-project form makes a second PATCH call.
- **MCP:**
  - `create_item`'s `productKey` argument text becomes: "The product's key. Omit it to use the
    project's default product; pass an empty string for none."
  - The `create_item` line in `wikantik-work-mcp-instructions.txt` gains the same sentence.
- **Frontend:**
  - `WorkSettings` Details shows a Default product select of unarchived products, plus the current
    default even if it has since been archived. It saves through `updateProject`.
  - `NewProjectForm` (admin) shows the same select, pre-selected when exactly one product is active.
    After `createProject` it calls `updateProject(key, { defaultProductKey })`. If that call fails, the
    project is still opened and a toast says the default was not set.
  - `WorkItemForm` initialises the product as follows:
    - `project.defaultProductKey` if that product is active;
    - else the only active product, if exactly one exists;
    - else `''`.
  - For a non-epic it always sends `productKey`, with `''` when the user chose "No product".

### 3. The item form

- **Always visible:** Level, Title, Description, Assignee, Parent (when parents exist), and Product
  (non-epic, only when no product was pre-selected).
- **"More options"** (collapsed by default) holds Product (non-epic, always editable here), Type
  (non-epic), Kind, Priority, Due date and Labels.
  - The toggle shows a live summary, e.g. `More options (Product: web · Feature · Software · P3)`.
  - Captions: Type is "What kind of work: feature, bug or chore"; Kind is "Which area: software,
    docs or admin".
- **Submission is unchanged** whether the section is open or not (defaults: `kind=software`,
  `type=feature`, `priority=3`).
- **Autonomy fallback:** both `'supervised'` fallbacks in `WorkSettings.jsx` become `'delegated'`.

### 4. Getting-started card (`WorkHome.jsx`)

- **When it shows:** for a signed-in user with no active project, or an admin with no active product,
  queue or agent. Admins load `listProducts`, `listQueues` and `listAgents`; others load only
  projects. A failed load leaves the card out; it never blocks the page.
- **Steps for admins:**

  | Step | Done when | Action when not done |
  |---|---|---|
  | Create a product (optional unless you track branches or deploys) | an active product exists | link to `/work/products` |
  | Create a project | an active project exists | open the new-project form in place |
  | Create queues | an active queue exists | **Create starter queues** button |
  | Register an agent (optional) | an active agent exists | link to `/work/agents` |

- **Non-admins with no project** see one line: "You are not in any project yet. A wiki admin
  creates projects, and a project lead adds people."
- **A note on drafts:** new epics and agent-created items start as drafts, and the board hides
  drafts until you choose Show.
- **Dismissing:** a Dismiss button stores `wikantik.work.gettingStarted.dismissed` in `localStorage`
  (reads and writes in try/catch; without storage the card simply shows).
- **Starter queues:** a shared helper `createStarterQueues(existing)` creates `implementer`
  ("Implementer", "Ready work waiting for an agent to implement.") and `review` ("Review", "Completed
  work waiting for review."). It skips a key that already exists, retired or not. `WorkQueues.jsx`'s
  empty state offers the same button to admins.

### 5. Member suggestions (`WorkSettings.jsx`)

- **User kind:** typing in `#work-add-principal` calls `listMentionableUsers(q, 10)` after a 200 ms
  debounce, with a minimum of 2 characters. The display names fill a `<datalist>` attached to the
  input.
- **Group kind:** the same, from `suggestGroups(projectKey, q)`, which calls
  `GET /api/work/projects/{key}/group-suggestions?q=`.
- **Backend:**
  - `WorkDirectory` gains `default Set<String> groupNames() { return Set.of(); }`.
  - `WikiWorkDirectory` implements it from `GroupManager.getRoles()`, as `WikiWorkGroups` does.
  - `WorkFeatures.groupSuggestions(Actor, String projectKey, String query)` requires
    `MANAGE_PROJECT`. It matches case-insensitively by substring, puts prefix matches first and then
    sorts alphabetically, and caps at 10. A blank query returns nothing. It is classified under
    `READS`, so a non-member gets 404.
- **Typing stays free.** The datalist only suggests, so typing a full name and the existing form
  tests keep working.

### 6. `get_work_context` setup hints

- `GetWorkContextTool` adds `setup`: a list of strings, always present, and empty for a person or a
  correctly set-up agent. For an agent it holds, in this order:
  - **No sponsor** (`Agent.sponsor()` is null or blank): "Nobody sponsors you, so you can do nothing.
    A wiki admin or a project lead must take you over."
  - **No visible project:** "You are not a member of any project you can see. Ask your sponsor to add
    you to a project (they must be a member of it too)."
  - **No queue:** "You serve no queue, so list_claimable is always empty. Ask your sponsor to add a
    queue to you."
- The hints describe only the agent itself and name no project, so they reveal nothing a stranger
  could not see.
- The tool description and the instructions text name the `setup` field.

### 7. Documentation

- `docs/user/WorkManagement.md`:
  - §2: the project default product.
  - §3: member suggestions, plus the default product on create and in settings.
  - §7: one-pass admission, the setup command, starter queues and the `setup` hints.
- `wikantik-work/CLAUDE.md`: one rule each for the default product (the clearing on archive; the
  null versus `""` contract) and for group suggestions (lead-only).
- A CHANGELOG `[Unreleased]` line.

## Review focus (inputs no happy-path test exercises)

1. A blank versus an omitted `productKey`, on REST and on MCP.
2. A default product archived while an item is being created (the race), and archived before the
   settings page loads (the stale default is still shown).
3. A lead who leads no project registering an agent: no fieldset, and no `putMember` calls.
4. A `putMember` rejection after a successful registration: the token stays and the warning names
   the project.
5. A non-member calling `group-suggestions`: 404, identical to an unknown project.

## Left to the builder

- Exact CSS and markup, following the existing `work-*` classes and `src/components/ui/`.
- Whether the setup-command block is collapsible.
- Test file organisation within each module's existing pattern.
