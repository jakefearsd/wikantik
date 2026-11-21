# GoF Refactors, Pass 2 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the second Gang-of-Four design pass (one defect fix plus 15 behaviour-preserving refactors approved by the owner on 2026-11-20), pushed to main with no release.

**Architecture:** Each task finishes a pattern the code half-applies (Template Method, Strategy, Command table, Builder, Facade) or moves one concern out of a large class along a real seam. Tasks fall into five lanes by module: main, work, mcp, rest, frontend. Lanes do not share files and may run in parallel; tasks inside a lane run in order.

**Tech Stack:** Java 25 / Maven multi-module, JUnit 5 + Mockito, PostgresTestDb; React 19 + Vitest + happy-dom; CodeMirror 6 / Lezer.

**Spec:** No separate design doc. The source is the five-area survey of 2026-11-20 (engine core, REST/HTTP, work, knowledge/MCP, frontend) as triaged with the owner: "Fix + A1–A8 + B1–B7". Each task's **Finding** line summarises its survey evidence. Out of scope, by the owner's triage: C1 (`SpaRoutingFilter` split) and C2 (`PageEditor` flow hooks) get their own plans later; the marginal items (`WorkService` prologue, `useEditTarget`, `LateBoundServices`, `WorkJson` actor table) are not done.

## Global Constraints

- Behaviour-preserving. No change to REST/MCP wire shapes or key order, HTTP status codes, error and log message text that tests or operators match, config keys, SQL, event order, or listener registration targets. The one deliberate exception is Task 1 (prompt text).
- Characterise first. Where code being moved has no direct test, add one against the CURRENT code, see it pass, then refactor and keep it passing unchanged. The existing tests must stay green without edits to their assertions; a needed assertion edit is a finding to report, not a fix to make.
- Data access only through `com.wikantik.jdbc.Jdbc`. No new `register*` on `WikiEngine`, no new `getManager` callers, never `getManager` inside a lambda (`DecompositionArchTest`). After an ArchUnit run, a changed `wikantik-main/src/test/resources/archunit_store/` is restored with `git checkout --` by the controller; a shrink from a removed violation is kept.
- PMD complexity ratchet: `mvn -o -q -pl <module> pmd:check -Pcomplexity-gate` passes. No new suppressions. When a refactor removes a baseline entry's cause, delete that entry from `build-support/pmd-complexity-baseline.properties` (entries only ever come out).
- No empty catch blocks; at least `LOG.warn` with context.
- wikantik-work rules (`wikantik-work/CLAUDE.md`) bind Task 4 and 5: every caller through `WorkService`, no facade folded back, no suppressions.
- Frontend: reuse `src/components/ui/`; React 19 wipes innerHTML-managed DOM on re-render, so memoise hosts. Promise chains, not async/await, where an effect sets state (`set-state-in-effect` lint).
- Parallel lanes share one working tree. Implementers never run state-changing git (no add/commit/stash/checkout/restore), never `mvn install`, never `bin/run-tests.sh`. They run module-scoped checks only: `mvn -o -q -pl <module> test -Dtest=<Classes> -Dsurefire.failIfNoSpecifiedTests=false`, the module's PMD check, or `npx vitest run <paths>` / `npx eslint <paths>` from `wikantik-frontend`. The controller reviews each diff and commits by name.
- Commit messages are 1–3 lines and end with the two trailer lines the session's system reminder gives.
- Final gate: `bin/run-tests.sh --parallel 4`, ALL PASSED, then push. No release, no CHANGELOG entry (the owner did not ask for a release).

## Review Focus

1. **A page renamed while a listener's target throws** (Task 2): the exception must be caught and warned per listener exactly as today, and the other listeners must still receive the event. Pin with a test that a throwing `onRenamed` logs and does not propagate.
2. **An MCP tool called with an argument the schema does not advertise** (Task 4): the refusal text and the set of accepted names must be byte-identical to today for all 16 tools. Pin with a test that walks every tool and compares its derived allowed set to its schema property names, and one refusal-text test.
3. **A bulk action with a blank id, an unknown action, or an empty `ids`** (Task 9): same 400 status and body as today for both resources. Pin each in the characterisation test before refactoring.
4. **A Lucene search whose index names a page that no longer exists** (Task 13): the stale-hit decision (delete from index vs keep) must be unchanged. Pin with a direct test of the extracted reconciler for both outcomes.
5. **A live-preview document mixing every node type** (Task 14): decoration output identical. The existing parity/corpus/property tests are the oracle; add one fixture that hits every handler in the new table.

---

## Lane MCP (wikantik-admin-mcp, wikantik-knowledge, wikantik-mcp-core)

### Task 1: Admin MCP prompts name only tools that exist (defect)

**Finding:** `WikiPrompts` text tells agents to call `search_pages`, `export_content`, `import_content`, `preview_import` and `query_metadata` (about 27 mentions across 7 prompts). None is on the admin surface; `list_pages` and `list_metadata_values` live on `/knowledge-mcp`.

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/prompts/WikiPrompts.java`
- Test: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/prompts/WikiPromptsToolNamesTest.java` (new)

- [ ] **Step 1:** Write `WikiPromptsToolNamesTest.everyToolAPromptNamesExists`: render every prompt (empty arguments), extract each `snake_case` token that looks like a tool name, and assert each is in the admin tool set or the knowledge tool set. Source the two sets from the code that registers them (the admin tool registry / `KnowledgeMcpInitializer` tool list, or the instructions files the drift tests already read), not from a hand-typed copy. Run it: FAIL, naming the five stale tools.
- [ ] **Step 2:** Rewrite each stale instruction to the real tool: content search → `retrieve_context` / `assemble_bundle` on `/knowledge-mcp` (say which endpoint); metadata queries → `list_pages_by_filter` / `list_metadata_values` (knowledge); export → `read_page` / `read_pages`; import and preview-import → `write_pages` (create-only) or `update_page`. Where a prompt's whole purpose was an export/import round trip, reword it to read-then-write with those tools. Keep each prompt's name, arguments and intent.
- [ ] **Step 3:** Run `WikiPromptsToolNamesTest` and `WikiPromptsTest`: PASS. Commit: `fix(mcp): admin prompts name only tools that exist`.

### Task 3: Prompt definitions as data (after Task 1)

**Finding:** each of the 7 prompts repeats build-schema → null-guarded `getOrDefault` per argument → `String.formatted` with positional `%s` (up to 9 repeats in `renamePage`) → wrap one USER `TextContent` in `GetPromptResult`.

**Files:**
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/prompts/PromptDef.java`
- Modify: `WikiPrompts.java`
- Test: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/prompts/WikiPromptsGoldenTest.java` (new)

**Interfaces:**
- Produces: `record PromptDef( String name, String description, List< Arg > args, String template )` with `record Arg( String name, String description, boolean required, String defaultValue )`; `McpSchema.Prompt prompt()`; `McpSchema.GetPromptResult render( Map< String, Object > arguments )`. Templates use `{argName}` placeholders; a missing optional argument takes `defaultValue`.

- [ ] **Step 1:** Write `WikiPromptsGoldenTest`: for every prompt, render with (a) no arguments and (b) every argument set to a distinct value, and assert the text equals a golden captured from the CURRENT (post-Task-1) code. Capture the goldens by running the current code once (store them as test resources under `src/test/resources/prompts/`). Also assert each prompt's `McpSchema.Prompt` (name, description, argument names, required flags) unchanged. PASS on current code.
- [ ] **Step 2:** Implement `PromptDef` and express the 7 prompts as `PromptDef` constants; `WikiPrompts` registers them from one list. Replace positional `%s` with named `{arg}` placeholders.
- [ ] **Step 3:** Golden test, `WikiPromptsTest`, `WikiPromptsToolNamesTest`: PASS unchanged. Commit: `refactor(mcp): prompts as PromptDef data with named placeholders`.

### Task 6: One save-refusal mapper for `update_page` and `write_pages`

**Finding:** `UpdatePageTool.doExecute` (~161-386) and `WritePagesTool` (~120-268) each map `ContentValidationException`, `AccessRuleShapeException` and `FrontmatterValidationException` to `{error, violations[, total]}`, each run the `FrontmatterWarningSink`/`ContentWarningSink` clear-drain-put sequence, and each copy the frontmatter parse-error hint verbatim.

**Files:**
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/SaveRefusals.java`, `SaveWarnings.java`
- Modify: `UpdatePageTool.java`, `WritePagesTool.java`
- Test: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/SaveRefusalsTest.java` (new); keep `UpdatePageTool*Test`, `WritePagesTool*Test` green unchanged

**Interfaces:**
- Produces: `final class SaveRefusals { static Map< String, Object > refusalFor( Throwable t, String flagKey ) /* null when t is none of the three */; static Map< String, Object > parseError( FrontmatterParseException e, String flagKey ); }` — `flagKey` is `"created"` or `"updated"`, written as `false` exactly where today's code writes it.
- Produces: `final class SaveWarnings { static void clear(); static void attach( Map< String, Object > entry, String pageName ); }`

- [ ] **Step 1:** Write `SaveRefusalsTest` against the CURRENT tools first: for each of the three exceptions and the parse error, call each tool through its existing test seam and record the exact result map (keys, order, values, `total` presence). Then the same assertions will be re-pointed at `SaveRefusals` directly in Step 3. PASS now.
- [ ] **Step 2:** Implement both helpers; both tools call them. `write_pages` keeps its extra catch-all `Exception` branch; `update_page` keeps having none. Each tool keeps its own flow (create-only and batch vs hash guard and merge).
- [ ] **Step 3:** Add direct `SaveRefusals` assertions equal to Step 1's maps; run the new and existing tool tests: PASS. Commit: `refactor(mcp): one save-refusal mapper for update_page and write_pages`.

### Task 12: `VerifyPagesTool` checks as Strategy objects

**Finding:** `VerifyPagesTool` (~132-353) hand-wires five checks (existence, links, backlinks, metadata, seo, retrieval) with `activeChecks.contains(...)` gates, five mutable aggregates and a conditional summary block (~215-228); a new check is about five edits. SEO and retrieval already delegate to `PageCheck` strategies.

**Files:**
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/verify/VerifyCheck.java` and one class per check in that package
- Modify: `VerifyPagesTool.java`
- Test: keep `VerifyPagesTool*Test` green unchanged; add `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/VerifyPagesToolOutputTest.java` (new) if no existing test pins the full output for all checks active

**Interfaces:**
- Produces: `interface VerifyCheck { String name(); void apply( PageContext page, Map< String, Object > entry, VerifyTotals totals ); void summarise( VerifyTotals totals, Map< String, Object > summary ); }`; `PageContext` gives the page name, its existence, and a lazily parsed frontmatter (parsed at most once, only if a check asks); `VerifyTotals` holds the aggregates (issue lists and the broken-link count).

- [ ] **Step 1:** If no test pins it, write `VerifyPagesToolOutputTest`: two pages (one healthy, one with a broken link, no backlinks, missing metadata, SEO and retrieval issues), all checks active and then each check alone; assert the full result map including key order and the combined "outbound_links or broken_links" gate. PASS on current code.
- [ ] **Step 2:** Implement the interface and the six checks; the tool iterates the active ones in today's order.
- [ ] **Step 3:** All `VerifyPagesTool*` tests: PASS unchanged; PMD check on wikantik-admin-mcp passes (remove the tool's baseline entry if its cause is gone). Commit: `refactor(mcp): verify_pages checks as VerifyCheck strategies`.

### Task 15: One endpoint skeleton for the admin and knowledge MCP initializers

**Finding:** `McpServerInitializer` (~85-180, ~282-315) and `KnowledgeMcpInitializer` (~75-190, ~270-308) each repeat find-engine → bootstrapper phases 1-2 → assemble tools → `McpServer.sync(...).toolCall(...)` → `setAttribute`, with ~9 near-identical try/catch-log-return blocks and a copied `auditedExecute` (timing, client name, the `threw=`/`isError=` log line; admin adds a `surface` field).

**Files:**
- Create: `wikantik-mcp-core/src/main/java/com/wikantik/mcp/McpEndpointInitializer.java` (abstract) and `wikantik-mcp-core/src/main/java/com/wikantik/mcp/AuditedToolCall.java`
- Modify: `McpServerInitializer.java`, `KnowledgeMcpInitializer.java`
- Test: `wikantik-mcp-core/src/test/java/com/wikantik/mcp/AuditedToolCallTest.java` (new); keep both initializers' tests and `McpProtocolIT` green

**Interfaces:**
- Produces: `abstract class McpEndpointInitializer implements ServletContextListener` with a final `contextInitialized` running the phases in today's order, each inside one `phase( String what, Runnable step )` wrapper that logs and stops exactly as today; hooks `protected abstract McpEndpointBootstrapper.Builder bootstrapper( Engine engine )`, `protected abstract void registerTools( McpServer.SyncSpecification< ? > server, Engine engine )`, `protected abstract String attributeName()`.
- Produces: `final class AuditedToolCall { static McpSchema.CallToolResult run( McpTool tool, Map< String, Object > args, McpSyncServerExchange exchange, String surfaceOrNull, Supplier< McpSchema.CallToolResult > call ); }` — writes today's log line; the `surface=` field appears only when `surfaceOrNull` is non-null (admin passes it, knowledge passes null).

- [ ] **Step 1:** Write `AuditedToolCallTest` capturing the log line with a Log4j test appender for: a successful call, an `isError` result, and a thrown exception, with and without a surface. Copy the expected line formats from the CURRENT two `auditedExecute` methods. Run: FAIL (no class).
- [ ] **Step 2:** Implement both classes; port the two initializers. Every log message string, the `loadOnStartup` values and the attribute names stay byte-identical. Admin's `resolveAuthor` / `CallerIdentity.runAs` wrapper stays in admin (it wraps the `Supplier`). Admin's instructions drift check stays admin-only. `WorkMcpInitializer` is out of scope (Lane work owns that module).
- [ ] **Step 3:** Run the new test and the existing tests of `wikantik-mcp-core`, `wikantik-admin-mcp`, `wikantik-knowledge`: PASS. Commit: `refactor(mcp): one endpoint initializer skeleton and audited tool call`.

---

## Lane main (wikantik-main)

### Task 2: `PageLifecycleListener` base for page-event listeners

**Finding:** `CitationEventListener` (~46-76), `StructuralIndexEventListener` (~52-90), `ContentDateEventListener` (~45-73), `OntologyEventListener` (~50-89) and `ChunkLifecycleListener` (~55-95) each re-implement: register on page sources, test `WikiPageRenameEvent` before `WikiPageEvent` (the rename event is a subclass), switch on `POST_SAVE_END` / `PAGE_DELETED`, one try/catch with `LOG.warn`. Four Javadocs repeat the "rename is keyed on the renamer" trap.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/event/PageLifecycleListener.java`
- Modify: the five listeners above (`CollabPageEventListener` and `AuditEventListener` stay as they are)
- Test: `wikantik-main/src/test/java/com/wikantik/event/PageLifecycleListenerTest.java` (new); keep each listener's existing tests green unchanged

**Interfaces:**
- Produces: `public abstract class PageLifecycleListener implements WikiEventListener` with `public final void actionPerformed( WikiEvent event )` (rename first, then save/delete, one try/catch: `LOG.warn( "{} event handling failed for {}: {}", label(), event, e.getMessage(), e )`), hooks `protected void onSaved( String page )`, `protected void onDeleted( String page )`, `protected void onRenamed( String from, String to )` (no-op defaults), `protected abstract String label()`, and `protected final void listenTo( Object... sources )` which calls `WikiEventManager.addWikiEventListener( source, this )` for each non-null source.
- Each subclass keeps its own public `register(...)` signature and calls `listenTo` with exactly the sources it registers on today (citation: page manager + filter manager; others as their code shows). Do not add a source a listener does not listen to today.

- [ ] **Step 1:** Write `PageLifecycleListenerTest` with a test subclass recording calls: a rename event reaches `onRenamed` only (not `onSaved`); `POST_SAVE_END` reaches `onSaved`; `PAGE_DELETED` reaches `onDeleted`; other types reach nothing; a hook that throws is caught and logged once at WARN and does not propagate (Review Focus 1); `listenTo` skips null sources. Run: FAIL (no class).
- [ ] **Step 2:** Implement the base. Port each listener: its existing warn text, if a test asserts it, wins over the base's (make `label()` produce it, or keep a listener-specific override of the catch only if a test pins a different text). Citation does not override `onDeleted`, and its comment about why moves onto the class Javadoc. Chunk's `mayStillExist` guard stays inside its `onDeleted`. Keep the strong reference the wiring holds to each listener (`WikiEventManager` holds weak refs).
- [ ] **Step 3:** Run the new test plus the five listeners' tests and `DecompositionArchTest`: PASS. Commit: `refactor(events): PageLifecycleListener base for page-event listeners`.

### Task 8: `DefaultRecentArticlesManager` split into extractor and renderer (after Task 2)

**Finding:** 773 lines, GodClass + Cyclomatic baseline. Title/excerpt extraction (~524-680: `extractTitle`, `extractExcerpt`, `stripHtml`, `decodeNumericEntities`, seven static regexes) is engine-independent; template rendering (~238-360, ~719-755) has three modes each repeating "try `textToHTML`, warn, fall back"; query/cache/visibility (~194-470) is the manager's own job.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/content/ArticleTextExtractor.java`, `ArticleTemplateRenderer.java`
- Modify: `DefaultRecentArticlesManager.java`
- Test: `wikantik-main/src/test/java/com/wikantik/content/ArticleTextExtractorTest.java`, `ArticleTemplateRendererTest.java` (new); keep `DefaultRecentArticlesManager*Test` green unchanged

**Interfaces:**
- Produces: `final class ArticleTextExtractor { static String title( String markdown, String pageName ); static String excerpt( String html, int maxLength ); static String stripHtml( String html ); }` (pure; names may follow the existing private methods' names and parameters exactly — keep their semantics).
- Produces: `final class ArticleTemplateRenderer { ArticleTemplateRenderer( Function< String, String > textToHtml ); String render( String template, List< ? > articles /* existing article type */ ); }` with one private `renderOrFallback` holding the warn-and-fall-back tail. The `RecentArticlesManager` interface and cache keys are unchanged.

- [ ] **Step 1:** Characterise: copy representative inputs from the existing manager tests (and add HTML with numeric entities, nested tags, an empty body, a template with `%%ARTICLE_START%%`, a simple template, the default) and assert today's outputs through the manager. PASS.
- [ ] **Step 2:** Move the code verbatim into the two classes; the manager delegates. Write the two direct test classes with the same inputs and expected outputs.
- [ ] **Step 3:** All three test classes plus existing manager tests: PASS unchanged. PMD: remove the class's baseline entries whose cause is gone. Commit: `refactor(content): RecentArticles text extraction and template rendering out of the manager`.

### Task 10: `RecentChangesPlugin` page and attachment rows (after Task 8)

**Finding:** `RecentChangesPlugin.execute` (~92-238) is one 145-line method (baseline: Cognitive, NPath, Ncss). It tests `pageref instanceof Attachment` three times (href, info link and icon, diff link vs plain time cell), and "compact" format is four mutable locals.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/plugin/RecentChangesPlugin.java` (nested types are fine; a new file only if it reads better)
- Test: keep `RecentChangesPluginTest` green unchanged; add cases if the attachment row and compact format are not both covered

**Interfaces:**
- Produces (private/nested): `record Layout( String spacing, String tableWidth, boolean showAuthor, boolean showChangenote )` with `FULL` and `COMPACT`; `sealed interface ChangeRow permits PageRow, AttachmentRow` with the name cell and the time cell; methods `visibleChanges(...)`, `dateHeaderRow(...)`, `row(...)`.

- [ ] **Step 1:** If missing, add `RecentChangesPluginTest` cases for an attachment change in full and compact formats, asserting the full HTML. PASS on current code.
- [ ] **Step 2:** Refactor as above; HTML output byte-identical.
- [ ] **Step 3:** Plugin tests: PASS unchanged; remove the method's baseline entries. Commit: `refactor(plugin): RecentChanges rows and layout as types`.

### Task 13: `DefaultLuceneSearcher.findPages` as steps (after Task 10)

**Finding:** `findPages` (~203-345, baseline: Cognitive, Cyclomatic, NPath, Ncss) builds the query and highlighter, loads pages, reconciles stale index entries in a nested 25-line block, ACL-filters in a batch, maps results, catches four exception types and records metrics.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/search/subsystem/lucene/StaleHitReconciler.java`
- Modify: `DefaultLuceneSearcher.java`
- Test: `wikantik-main/src/test/java/com/wikantik/search/subsystem/lucene/StaleHitReconcilerTest.java` (new); keep `DefaultLuceneSearcher*Test` and `LuceneSearchProvider*Test` green unchanged

**Interfaces:**
- Produces: `final class StaleHitReconciler { enum Outcome { DELETED_FROM_INDEX, KEPT } Outcome reconcile( String pageName ); }` holding today's confirm-absence-then-delete-or-keep policy and its collaborators; private steps in the searcher: `buildQuery(...)`, `loadHits(...)`, `filterViewable(...)`. `findPages` stays the orchestrator owning the exception handling and metrics tail.

- [ ] **Step 1:** Write `StaleHitReconcilerTest` against the policy as the current block implements it: page confirmed absent → deleted from index; page present again / absence not confirmed → kept (Review Focus 4). Run: FAIL (no class).
- [ ] **Step 2:** Extract. The index reader lease must still span every stored-field read; `IndexNotFoundException` and `IOException` keep today's handling and return values.
- [ ] **Step 3:** New and existing search tests: PASS unchanged; remove the method's baseline entries. Commit: `refactor(search): findPages as steps; stale-hit policy in StaleHitReconciler`.

---

## Lane work (wikantik-work, wikantik-work-mcp)

### Task 4: Declarative `ToolSpec` for the 16 work MCP tools

**Finding:** every `*Tool` in `wikantik-work-mcp` repeats `TOOL_NAME`, `name()`, `writes()`, a hand-built `McpSchema.Tool`, the annotations, and lists its argument names three times (`props.put`, the `required` list, a separate `ALLOWED` set passed to `rejectOthers`; 24 `ALLOWED` references). `currentVersion()` is overridden identically in `UpdateItemTool` and `HandoffItemTool`.

**Files:**
- Create: `wikantik-work-mcp/src/main/java/com/wikantik/work/mcp/ToolSpec.java`
- Modify: `WorkMcpTool.java` and the 16 `*Tool.java` files
- Test: `wikantik-work-mcp/src/test/java/com/wikantik/work/mcp/ToolSpecTest.java` (new); keep every existing tool test and the assembly test green unchanged

**Interfaces:**
- Produces: `final class ToolSpec` builder: `static ToolSpec reads( String name )`, `static ToolSpec writes( String name )`, `ToolSpec description( String )`, `ToolSpec arg( String name, String type, String description, Object example )`, `ToolSpec requiredArg( ... same )`, `ToolSpec arg( String name, Map< String, Object > schema, boolean required )` for the odd shapes, `McpSchema.Tool definition()`, `Set< String > allowed()`, `boolean writes()`, `String name()`.
- `WorkMcpTool` gains a constructor taking a `ToolSpec` and derives `name()`, `definition()`, `writes()` and the allowed set from it; `rejectOthers( args, allowed(), ... )` reads the spec. The shared `currentVersion` behaviour moves to the base behind a spec flag or a small shared helper; `run()` bodies are untouched.

- [ ] **Step 1:** Write `ToolSpecTest.everyToolsSchemaIsUnchanged`: for each of the 16 tools, serialise `definition()` (name, description, input schema JSON, required list, annotations) and compare with a golden captured from the CURRENT code (store under `src/test/resources/tools/`). Add `allowedEqualsSchemaProperties` (Review Focus 2): for each tool, the set it refuses against equals its schema's property names, and one test that an unadvertised argument gets today's refusal text. PASS on current code.
- [ ] **Step 2:** Implement `ToolSpec`; port tools one at a time, re-running `ToolSpecTest` after each. Keep the tool order in `WorkMcpInitializer`'s assembly.
- [ ] **Step 3:** All wikantik-work-mcp tests: PASS unchanged. Commit: `refactor(work-mcp): tools declared by ToolSpec; allowed arguments derived from the schema`.

### Task 5: `Action` carries its own policy attributes (after Task 4)

**Finding:** `WorkPolicy` classifies `Action` four ways in separate places: `FENCED` (EnumSet), `LEAD_ACTIONS` (EnumSet), `isManagement` (an `==` chain) and `roleNeeded` (if-chain with a "handled above" `IllegalStateException` for `MANAGE_INSTANCE`). A new action can be left unclassified silently.

**Files:**
- Modify: `wikantik-work/src/main/java/com/wikantik/work/Action.java`, `WorkPolicy.java`
- Test: `wikantik-work/src/test/java/com/wikantik/work/WorkPolicyActionTableTest.java` (new); keep `WorkPolicyTest` and the gates tests green unchanged

**Interfaces:**
- Produces: `enum Action { VIEW( WorkRole.VIEWER, false, false ), ... ; WorkRole minRole() /* null for MANAGE_INSTANCE: decided before the role check */; boolean management(); boolean fenced(); }` — values copied from today's sets: fenced = EDIT_ITEM, MOVE, LINK, OPEN_CHANGE, REVIEW_CHANGE, MERGE_CHANGE; management = SET_POLICY, MANAGE_PROJECT, MANAGE_INSTANCE, MANAGE_AGENT; minRole = VIEWER for VIEW, LEAD for MANAGE_PROJECT, SET_POLICY, MANAGE_AGENT, null for MANAGE_INSTANCE, MEMBER otherwise. The constant order in the enum is unchanged (ordinals may be persisted or sorted on).

- [ ] **Step 1:** Write `WorkPolicyActionTableTest`: for every `Action` × actor (human non-admin, human admin, agent with admin sponsor, agent with member sponsor) × role (none, VIEWER, MEMBER, LEAD) × item (null, claimed by another agent, claimed by self), record `WorkPolicy.may(...)`'s decision (allowed and reason) from the CURRENT code into a golden resource, and assert equality. PASS.
- [ ] **Step 2:** Move the attributes onto the enum; `WorkPolicy` reads them; delete `FENCED`, `LEAD_ACTIONS`, `isManagement` and the `IllegalStateException` branch.
- [ ] **Step 3:** Table test and all wikantik-work policy/gate tests: PASS unchanged. Commit: `refactor(work): Action carries role, management and fencing`.

---

## Lane rest (wikantik-rest)

### Task 9: Bulk-action template and route tables for the last hand-routed servlets

**Finding:** `AdminUserResource.doBulkAction`/`applyBulkAction` (~129-262) and `AdminApiKeysResource.doBulkAction`/`revokeOneBulkId` (~99-110, ~212-285) share one skeleton (require `action`, reject unsupported, require non-empty `ids`, loop with blank-id guard, one summary log line, `BulkActionResult.toResponseBody`) with copy-pasted 400s; `AdminUserResource` keeps two parallel tables (`actionPastTense` and the action switch). `AuthResource` (~93-190), `AdminUserResource` (`endsWith("/lock")`, `/bulk-action` pre-dispatch, duplicated null-login 400s ~435-455) and `AdminApiKeysResource` (~104) still dispatch by hand while 15+ resources use `SegmentRouteTable`/`PathRouteTable`.

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/BulkActions.java`
- Modify: `AdminUserResource.java`, `AdminApiKeysResource.java`, `AuthResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/BulkActionsTest.java` (new); keep `AdminUserResource*Test`, `AdminApiKeysResource*Test`, `AuthResource*Test` green unchanged

**Interfaces:**
- Produces: `final class BulkActions { record Action( String name, String pastTense, IdHandler perId ) {} interface IdHandler { void apply( String id, BulkActionResult.Builder result ) throws Exception; } static void handle( HttpServletRequest req, HttpServletResponse resp, String resource, Map< String, Action > actions, Prepare prepare ) throws IOException; interface Prepare { /* optional pre-step, e.g. resolve the add-to-group target once; may send a 400 and return false */ boolean run( String action, JsonObject body, HttpServletResponse resp ) throws IOException; } }` — owns validation, the loop, the summary log line and the response; the 400 texts are built from the `actions` key list exactly as today's literals read.
- Routing: each of the three servlets builds a `SegmentRouteTable` (or `PathRouteTable` where segments cannot express it) per the table-per-servlet, lazy transient rebuild convention documented in `PathRouteTable`. `POST users/bulk-action` is registered ahead of `{login}` patterns.

- [ ] **Step 1:** Characterise both bulk endpoints (Review Focus 3): unknown action, missing action, missing/empty `ids`, a blank id inside `ids`, a mixed success/failure batch; assert status and exact body; and the 404/400 bodies of each servlet for unknown paths, a trailing slash, an empty segment and a null login on PUT/DELETE. PASS on current code (add to existing test classes where they fit, else `BulkActionsTest`).
- [ ] **Step 2:** Implement `BulkActions`; port both resources (AdminUser's group resolution and delete session lookup become its `Prepare`/per-id handlers). Then move the three servlets onto route tables one at a time, running their tests after each.
- [ ] **Step 3:** All Step 1 assertions and existing tests: PASS unchanged; remove baseline entries whose cause is gone. Commit (two commits are fine): `refactor(rest): BulkActions template` / `refactor(rest): route tables for auth, users and API keys`.

### Task 11: `AdminContentResource` handlers by concern (after Task 9)

**Finding:** 751 lines (baseline: Cyclomatic, GodClass); its 13-route table (~121-136) is clean, but behind it sit page health (stats, orphans, broken links, locked pages, broken block refs), page mutation (bulk delete, purge versions), search/embedding index admin with ~300 lines of DTO mapping (~398-710), and cache flush.

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminContentHealthHandlers.java`, `AdminContentMutationHandlers.java`, `AdminIndexHandlers.java`, `AdminCacheHandlers.java` (fold cache into mutation if it is under ~40 lines)
- Modify: `AdminContentResource.java` (keeps the route table and dispatch)
- Test: keep `AdminContentResource*Test` green unchanged

**Interfaces:**
- Follow `ConnectorAdminReadHandlers` / `ConnectorAdminWriteHandlers`: each handler class takes a small context (the servlet's engine and subsystem accessors and the `RestServletBase` response helpers it already uses) and exposes one method per route; the route-table lines point at them.

- [ ] **Step 1:** Confirm every route has at least one test asserting status and body shape; add any missing (especially `index-status`, `reindex-embeddings` and the chunk/outlier DTOs). PASS on current code.
- [ ] **Step 2:** Move the code verbatim into the handler classes.
- [ ] **Step 3:** Tests: PASS unchanged; remove the class's baseline entries whose cause is gone. Commit: `refactor(rest): AdminContentResource handlers by concern`.

---

## Lane frontend (wikantik-frontend)

### Task 7: Toolbar format commands as a table

**Finding:** `PageEditor.jsx` `runToolbarCommand` (~925-975) is a ~25-arm switch mapping a command id to a pure edit of `{text, selStart, selEnd}`, plus `callout:`/`fence:` prefix branches. The `==`, `~~`, `%%` markers are hard-coded again in `utils/obsidianSyntaxKeymap.js` (~16, 37-39); `utils/editorCommands.js` and `CodeEditor.jsx` (~576-579) repeat parts. Only `codeblock` (reads `liveActive`) and `accessrule` (takes `arg`) need context.

**Files:**
- Create: `wikantik-frontend/src/utils/editorFormatCommands.js`, `editorFormatCommands.test.js`
- Modify: `components/PageEditor.jsx`, `utils/obsidianSyntaxKeymap.js`, `utils/editorCommands.js` (and `CodeEditor.jsx` only if it duplicates a marker)

**Interfaces:**
- Produces: `export const FORMAT_COMMANDS` (id → `(state, ctx) => edit`), `export const MARKERS = { highlight: '==', strike: '~~', comment: '%%' }` (names may follow the code), `export function resolveFormatCommand(id)` handling `callout:*` and `fence:*`; `ctx` is `{ liveActive, arg }`. `runToolbarCommand` becomes lookup + the existing apply tail.

- [ ] **Step 1:** Write `editorFormatCommands.test.js` first as a characterisation of the CURRENT switch: export the switch's logic temporarily if needed, or drive `PageEditor`'s toolbar through its existing test harness; for every command id (and one `callout:` and one `fence:` id, `codeblock` with `liveActive` true and false, `accessrule` with an arg), assert the resulting `{text, selStart, selEnd}` for a fixed input with and without a selection.
- [ ] **Step 2:** Implement the table; point `runToolbarCommand`, the Obsidian keymap and the palette commands at it.
- [ ] **Step 3:** `npx vitest run src/utils/editorFormatCommands.test.js src/components/PageEditor* src/utils/obsidianSyntaxKeymap* src/utils/editorCommands*` and `npx eslint` on touched files: PASS. Commit: `refactor(editor): toolbar format commands as a table`.

### Task 14: Live-preview node handlers as a table (after Task 7)

**Finding:** `utils/livePreview/ranges.js` `livePreviewSpecs` (~571-~880, ~300 lines) closes over `specs`, `excluded`, `markers`, `lineClasses`, `hide`, `mark`, `widget`, then dispatches through ~15 early-return `if (name === …)` lines and a second 10-case `switch(name)`; returning `false` skips children and `undefined` descends, silently.

**Files:**
- Create: `wikantik-frontend/src/utils/livePreview/nodeHandlers.js` (and its test if a direct one adds value)
- Modify: `utils/livePreview/ranges.js`

**Interfaces:**
- Produces: a `SpecEmitter` (object or class) holding `hide`, `mark`, `widget`, `excluded`, `markers`, `lineClasses` and flags; `NODE_HANDLERS` (Lezer node name → `(emitter, node, ctx) => boolean` where `true` means descend), plus pattern entries for headings and setext. The post-walk wiki-link and chip logic stays in `ranges.js`. The order of pushes into `specs` is unchanged.

- [ ] **Step 1:** Add one fixture document that hits every handler (every node name in both dispatch styles) to the existing parity or corpus test, asserting today's spec output. PASS on current code (Review Focus 5).
- [ ] **Step 2:** Refactor.
- [ ] **Step 3:** `npx vitest run src/utils/livePreview` (parity, corpus, incremental, property tests included) and eslint: PASS unchanged. Commit: `refactor(live-preview): node handlers as a table`.

### Task 16: `useAsyncAction` for mutation busy/error state (after Task 14)

**Finding:** ~38 `setBusy/setSaving/setSubmitting(true)` sites in ~28 files repeat guard → `setError(null)` → busy → call → `.then(onSuccess)` → `.catch(err => setError(err?.message || '…'))` → `.finally(() => setBusy(false))`; some miss an unmount guard. `useAdminResource` covers reads only.

**Files:**
- Create: `wikantik-frontend/src/hooks/useAsyncAction.js`, `useAsyncAction.test.js`
- Modify: the work components first (`components/work/Work*.jsx` that hand-roll it), then the admin modals (`UserFormModal`, `GroupFormModal`, `PolicyGrantFormModal`), `ConnectorSettingsForm`, `AdminApiKeysPage`, `MyApiKeys`, `ChangePasswordPage`, `UserPreferencesPage`

**Interfaces:**
- Produces: `export default function useAsyncAction(fn, { fallbackMessage, onSuccess } = {})` returning `{ run, busy, error, setError, reset }`; `run(...args)` is ignored while busy, clears the error, sets busy, calls `fn(...args)` (a promise), calls `onSuccess(result)`, sets `error` to `err?.message || fallbackMessage` on rejection and returns `undefined`, clears busy in `finally`, and never sets state after unmount. Promise chain inside, no async/await.

- [ ] **Step 1:** Write `useAsyncAction.test.js` (renderHook): success path calls `onSuccess` and clears busy; rejection sets the message, falling back to `fallbackMessage` when the error has none; a second `run` while busy is ignored; no state update after unmount (no React warning); `reset` clears the error. Run: FAIL (no hook).
- [ ] **Step 2:** Implement the hook. Migrate files one at a time; each file's existing tests must pass unchanged, including exact fallback strings. Skip a site whose flow does not fit (several sequential awaits with partial state), and list skipped sites in the report.
- [ ] **Step 3:** `npx vitest run` on the hook and every migrated file's tests, eslint on touched files: PASS. Commit (one per group is fine): `refactor(ui): useAsyncAction for work forms` / `… for admin and account forms`.

---

### Task 17: Gate and push (controller)

- [ ] **Step 1:** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll to completion. Expected: `RESULT: ALL PASSED`.
- [ ] **Step 2:** Confirm `git status` is clean apart from known untracked files and that the PMD baseline only lost entries (`git diff --stat build-support/pmd-complexity-baseline.properties` shows deletions only).
- [ ] **Step 3:** Push to `origin main` once the owner has approved the push. No release.

---

## Outcome and follow-ups (2026-11-20)

All tasks landed (commits `9fd977988d`..HEAD). Deliberate deviations and follow-ups the final review left open:

- **Task 15** shipped as a static `StartupPhase` helper plus `AuditedToolCall`, not an abstract initializer: the first cut (a 13-hook Template Method) was reviewed as harder to read and replaced. `WorkMcpInitializer` still hand-writes its try/catch phases and could adopt `StartupPhase`.
- **Task 16** migrated 7 of ~28 busy/error sites to `useAsyncAction`. The rest need a hook extension (an `onError` or error-code routing) or stay hand-written:
  - per-field errors, 403→toast, rollback in catch: `WorkAgentForm`, `WorkQueues`, `WorkHome`, `WorkSettings` (details/autonomy/archive/members), `WorkAgents`, `UserFormModal`;
  - errors only logged (`console.warn`): `WorkBodyEditor`, `WorkFieldRow`, `WorkClaim`, `WorkHandoff`, `WorkLinks`, `WorkComments`;
  - shared `act(call, after, onError)` with two error slots: `WorkChanges`, `WorkDeploymentView`;
  - other bespoke flows: `ChangePasswordPage` (`err.body?.message` precedence), `UserPreferencesPage`, `MyApiKeys`, `ConnectorSettingsForm` (no catch: errors propagate), `AddConnectorWizard`, `ConnectorDetailPage`, `PageEditor`.
- **Task 12**: `VerifyTotals` is one shared bag of every check's aggregates (as planned); a check owning its own aggregate would make a new check a single class. The package-private `check*` delegates on `VerifyPagesTool` exist only for the unedited `VerifyPagesToolTest` and can go when those tests move onto the strategies.
- **Logger categories**: the audit line (Task 15) and the stale-hit lines (Task 13) now log under `AuditedToolCall` and `StaleHitReconciler`; Task 2 kept per-listener categories. No in-repo log config routes by the old names.
- PMD baseline entries for `UpdatePageTool` and `WritePagesTool` may now be stale (not verified).
- Out of scope: C1 (`SpaRoutingFilter` split) and C2 (`PageEditor` flow hooks) remain for their own plans.
