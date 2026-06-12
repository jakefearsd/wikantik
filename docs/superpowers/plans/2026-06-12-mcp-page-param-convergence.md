# MCP page-name parameter convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every MCP page-by-name tool advertises `slug`/`slugs` as its parameter while still silently accepting the legacy names (`pageName`/`pageNames`/`name`/`names`/`page`/`pages`); `canonical_id` is untouched.

**Architecture:** Two shared accessors in `McpToolUtils` (`pageSlug`, `pageSlugs`) own the alias set so it can't drift. Each tool's input schema renames its page-id property to `slug`/`slugs` and resolves through the accessor. A convergence-guard test walks the admin registry and asserts no tool advertises `pageName`/`pageNames`.

**Tech Stack:** Java 21, JUnit 5, Mockito, Maven. Run a single test class: `mvn -pl <module> test -Dtest=ClassName`.

**Context for the implementer:**
- `McpToolUtils` lives in `wikantik-admin-mcp` (`com.wikantik.mcp.tools`); `wikantik-knowledge` depends on it, so shared accessors there serve both modules. After editing `McpToolUtils`, `wikantik-knowledge` won't see it until admin-mcp is installed: `mvn -q install -pl wikantik-admin-mcp -DskipTests`.
- `McpToolUtils.getStringAny(args, keys…)` already exists (returns first non-blank string among keys). `getString(args, key)` exists.
- A tool's advertised input properties are `tool.definition().inputSchema().properties()` (a `Map<String,Object>`); required fields are `…inputSchema().required()`.
- `McpToolRegistryTest` builds `new McpToolRegistry( Mockito.mock(WikiEngine.class) )` and iterates `registry.readOnlyTools()` + `registry.authorConfigurableTools()`.

---

## File Structure

- `wikantik-admin-mcp/.../mcp/tools/McpToolUtils.java` — add `pageSlug`, `pageSlugs`, `firstListArg` (Task 1).
- `wikantik-admin-mcp/.../mcp/tools/McpToolUtilsTest.java` — accessor tests (Task 1).
- `wikantik-admin-mcp/.../mcp/McpToolRegistryTest.java` — convergence-guard test (Task 2).
- 6 singular admin tools (Task 3), 3 plural admin tools (Task 4), `UpdatePageTool` + `WritePagesTool` (Task 5).
- `wikantik-knowledge/.../mcp/GetPageTool.java`, `ReadPagesTool.java` (Task 6) — drop advertised `pageName`/`pageNames`, route through shared accessors.

---

## Task 1: Shared accessors in `McpToolUtils`

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/McpToolUtils.java`
- Test: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/McpToolUtilsTest.java`

- [ ] **Step 1: Write the failing test**

Append to `McpToolUtilsTest` (inside the class):

```java
    @org.junit.jupiter.api.Test
    void pageSlug_resolvesCanonicalAndAliases() {
        assertEquals( "X", McpToolUtils.pageSlug( java.util.Map.of( "slug", "X" ) ) );
        assertEquals( "X", McpToolUtils.pageSlug( java.util.Map.of( "pageName", "X" ) ) );
        assertEquals( "X", McpToolUtils.pageSlug( java.util.Map.of( "name", "X" ) ) );
        assertNull( McpToolUtils.pageSlug( java.util.Map.of() ) );
    }

    @org.junit.jupiter.api.Test
    void pageSlugs_resolvesCanonicalAndAliases() {
        assertEquals( java.util.List.of( "A" ), McpToolUtils.pageSlugs( java.util.Map.of( "slugs", java.util.List.of( "A" ) ) ) );
        assertEquals( java.util.List.of( "A" ), McpToolUtils.pageSlugs( java.util.Map.of( "pageNames", java.util.List.of( "A" ) ) ) );
        assertNull( McpToolUtils.pageSlugs( java.util.Map.of() ) );
    }
```

If `McpToolUtilsTest` lacks `assertEquals`/`assertNull`, add `import static org.junit.jupiter.api.Assertions.*;`.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl wikantik-admin-mcp test -Dtest=McpToolUtilsTest`
Expected: FAIL — `pageSlug` / `pageSlugs` don't exist (compile error).

- [ ] **Step 3: Add the accessors**

In `McpToolUtils.java`, after the existing `getStringAny(...)` method, add:

```java
    /** First list-valued argument among {@code keys}, or null. */
    public static List< ? > firstListArg( final Map< String, Object > args, final String... keys ) {
        if ( args == null ) { return null; }
        for ( final String k : keys ) {
            if ( args.get( k ) instanceof List< ? > l ) { return l; }
        }
        return null;
    }

    /** Canonical singular page identifier: advertises `slug`, accepts legacy/guessable aliases. */
    public static String pageSlug( final Map< String, Object > args ) {
        return getStringAny( args, "slug", "pageName", "name", "page" );
    }

    /** Canonical plural page identifiers: first list-valued arg among the accepted keys, else null. */
    public static List< ? > pageSlugs( final Map< String, Object > args ) {
        return firstListArg( args, "slugs", "pageNames", "names", "pages" );
    }
```

(`List` and `Map` are already imported in `McpToolUtils`.)

- [ ] **Step 4: Run to verify pass**

Run: `mvn -pl wikantik-admin-mcp test -Dtest=McpToolUtilsTest`
Expected: PASS.

- [ ] **Step 5: Install admin-mcp so knowledge-mcp can compile against it later**

Run: `mvn -q install -pl wikantik-admin-mcp -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/McpToolUtils.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/McpToolUtilsTest.java
git commit -m "feat(mcp): shared pageSlug/pageSlugs accessors (canonical slug + legacy aliases)"
```

---

## Task 2: Convergence-guard test (red until all tools converge)

**Files:**
- Test: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpToolRegistryTest.java`

- [ ] **Step 1: Write the guard test**

Append to `McpToolRegistryTest` (it already has a `registry` field built from a mocked `WikiEngine`):

```java
    @Test
    void noToolAdvertisesLegacyPageNameParam() {
        final List< McpTool > all = new java.util.ArrayList<>( registry.readOnlyTools() );
        all.addAll( registry.authorConfigurableTools() );
        final List< String > offenders = new java.util.ArrayList<>();
        for ( final McpTool t : all ) {
            final var props = t.definition().inputSchema().properties();
            if ( props != null && ( props.containsKey( "pageName" ) || props.containsKey( "pageNames" ) ) ) {
                offenders.add( t.name() );
            }
        }
        assertTrue( offenders.isEmpty(),
                "tools still advertising pageName/pageNames (use slug/slugs): " + offenders );
    }
```

Add `import java.util.List;` and `import static org.junit.jupiter.api.Assertions.assertTrue;` if absent.

- [ ] **Step 2: Run to verify it fails (lists the offenders)**

Run: `mvn -pl wikantik-admin-mcp test -Dtest=McpToolRegistryTest#noToolAdvertisesLegacyPageNameParam`
Expected: FAIL — offenders list includes `read_page, diff_page, get_backlinks, get_outbound_links, get_page_history, preview_structured_data, delete_pages, mark_page_verified, verify_pages, update_page, write_pages`.

- [ ] **Step 3: Commit the red guard**

```bash
git add wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpToolRegistryTest.java
git commit -m "test(mcp): convergence guard — no tool advertises pageName/pageNames (red)"
```

(The guard turns green at the end of Task 5. Do not skip it; it is the definition of done.)

---

## Task 3: Admin singular tools → `slug`

Each tool: rename the input property `pageName`→`slug`, the required-field entry `"pageName"`→`"slug"`, update the property description, and resolve via `McpToolUtils.pageSlug(arguments)`. Leave any *output* `result.put("pageName", …)` untouched (output-field naming is out of scope). The error message for a missing identifier becomes `"a page identifier is required (one of: slug, pageName, name)"`.

**Files (all under `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/`):** `ReadPageTool.java`, `DiffPageTool.java`, `GetBacklinksTool.java`, `GetOutboundLinksTool.java`, `GetPageHistoryTool.java`, `PreviewStructuredDataTool.java`.

- [ ] **Step 1: Worked example — `ReadPageTool.java`**

Edit (a): the input property registration —
```java
        properties.put( "pageName", Map.of(
```
→
```java
        properties.put( "slug", Map.of(
```
and in that block's `"description"` value, change wording to e.g. `"Name (slug) of the wiki page."`.

Edit (b): the required list —
```java
                        List.of( "pageName" ), null, null, null ) )
```
→
```java
                        List.of( "slug" ), null, null, null ) )
```

Edit (c): the resolution —
```java
            final String pageName = McpToolUtils.getString( arguments, "pageName" );
```
→
```java
            final String pageName = McpToolUtils.pageSlug( arguments );
```
(Keep the local variable name `pageName`; only its source changes. Leave the later `result.put( "pageName", pageName )` as-is.)

- [ ] **Step 2: Apply the same three edits to the other five tools**

Identical transformation; the exact current lines per tool:
- `DiffPageTool.java`: `properties.put( "pageName"` (L63); required `List.of( "pageName", "version1", "version2" )` (L94) → `List.of( "slug", "version1", "version2" )`; `getString( arguments, "pageName" )` (L102) → `pageSlug( arguments )`.
- `GetBacklinksTool.java`: `properties.put( "pageName"` (L48); `List.of( "pageName" )` (L69) → `List.of( "slug" )`; `getString( arguments, "pageName" )` (L77) → `pageSlug( arguments )`.
- `GetOutboundLinksTool.java`: `properties.put( "pageName"` (L49); `List.of( "pageName" )` (L67) → `List.of( "slug" )`; `getString( arguments, "pageName" )` (L75) → `pageSlug( arguments )`.
- `GetPageHistoryTool.java`: `properties.put( "pageName"` (L52); `List.of( "pageName" )` (L84) → `List.of( "slug" )`; `getString( arguments, "pageName" )` (L92) → `pageSlug( arguments )`.
- `PreviewStructuredDataTool.java`: `properties.put( "pageName"` (L63); `List.of( "pageName" )` (L90) → `List.of( "slug" )`; `getString( arguments, "pageName" )` (L98) → `pageSlug( arguments )`.

- [ ] **Step 3: Compile + run the guard (offenders shrink)**

Run: `mvn -pl wikantik-admin-mcp test -Dtest=McpToolRegistryTest#noToolAdvertisesLegacyPageNameParam`
Expected: still FAIL, but offenders now only the plural + complex tools (`delete_pages, mark_page_verified, verify_pages, update_page, write_pages`).

- [ ] **Step 4: Run the affected tools' own tests (alias still works)**

Run: `mvn -pl wikantik-admin-mcp test -Dtest='ReadPageToolTest,DiffPageToolTest,GetBacklinksToolTest,GetOutboundLinksToolTest,GetPageHistoryToolTest,PreviewStructuredDataToolTest'`
Expected: PASS. Existing tests that pass `pageName` still work (accepted by `pageSlug`). If a test asserts the *schema advertises* `pageName`, update it to assert `slug`. If a test class doesn't exist, skip it in the `-Dtest` list.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ReadPageTool.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/DiffPageTool.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/GetBacklinksTool.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/GetOutboundLinksTool.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/GetPageHistoryTool.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/PreviewStructuredDataTool.java
git commit -m "refactor(mcp): admin singular page tools advertise slug (pageName kept as alias)"
```

---

## Task 4: Admin plural tools → `slugs`

Each: input property `pageNames`→`slugs`, required entry `"pageNames"`→`"slugs"`, and resolve the list via `McpToolUtils.pageSlugs( arguments )` instead of `arguments.get( "pageNames" )`.

**Files (under `wikantik-admin-mcp/.../mcp/tools/`):** `DeletePagesTool.java`, `MarkPageVerifiedTool.java`, `VerifyPagesTool.java`.

- [ ] **Step 1: `DeletePagesTool.java`**
- `properties.put( "pageNames"` (L70) → `properties.put( "slugs"`.
- `List.of( "pageNames", "confirm" )` (L119) → `List.of( "slugs", "confirm" )`.
- `final Object raw = arguments.get( "pageNames" );` (L128) → `final Object raw = McpToolUtils.pageSlugs( arguments );`.

- [ ] **Step 2: `MarkPageVerifiedTool.java`**
- `properties.put( "pageNames"` (L76) → `properties.put( "slugs"`.
- `List.of( "pageNames" )` (L131) → `List.of( "slugs" )`.
- `final Object rawNames = arguments.get( "pageNames" );` (L141) → `final Object rawNames = McpToolUtils.pageSlugs( arguments );`.

- [ ] **Step 3: `VerifyPagesTool.java`**
- `properties.put( "pageNames"` (L72) → `properties.put( "slugs"`.
- `List.of( "pageNames" )` (L118) → `List.of( "slugs" )`.
- `final List< String > pageNames = ( List< String > ) arguments.get( "pageNames" );` (L127) → `final List< String > pageNames = ( List< String > ) McpToolUtils.pageSlugs( arguments );`.

- [ ] **Step 4: Run the guard + the three tools' tests**

Run: `mvn -pl wikantik-admin-mcp test -Dtest='McpToolRegistryTest#noToolAdvertisesLegacyPageNameParam,DeletePagesToolTest,MarkPageVerifiedToolTest,VerifyPagesToolTest'`
Expected: guard FAIL with only `update_page, write_pages` remaining; the three tool tests PASS (update any that assert the schema advertises `pageNames`).

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/DeletePagesTool.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/MarkPageVerifiedTool.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/VerifyPagesTool.java
git commit -m "refactor(mcp): admin plural page tools advertise slugs (pageNames kept as alias)"
```

---

## Task 5: `update_page` + `write_pages`

**Files:** `wikantik-admin-mcp/.../mcp/tools/UpdatePageTool.java`, `WritePagesTool.java`.

- [ ] **Step 1: `UpdatePageTool.java` (singular, with extra required fields)**
- `properties.put( "pageName"` (L74) → `properties.put( "slug"`.
- `List.of( "pageName", "content", "expectedContentHash" )` (L131) → `List.of( "slug", "content", "expectedContentHash" )`.
- `final String pageName = McpToolUtils.getString( arguments, "pageName" );` (L140) → `final String pageName = McpToolUtils.pageSlug( arguments );`.
- Leave the output `refused.put( "pageName", pageName )` as-is.

- [ ] **Step 2: `WritePagesTool.java` (per-item identifier in the `pages` array)**
- In the per-item schema, `"pageName", Map.of( "type", "string" )` (L73) → `"slug", Map.of( "type", "string" )`, and per-item `"required", List.of( "pageName", "content" )` (L76) → `"required", List.of( "slug", "content" )`. (The outer `pages` array name is unchanged — it's a list of page objects.)
- The per-item resolution `final String pageName = asString( p.get( "pageName" ) );` (L131) → accept the alias per item:
```java
            String pageName = asString( p.get( "slug" ) );
            if ( pageName == null || pageName.isBlank() ) { pageName = asString( p.get( "pageName" ) ); }
```
- Leave the output `entry.put( "pageName", pageName )` and examples as-is.

- [ ] **Step 3: Run the guard — now GREEN — plus both tools' tests**

Run: `mvn -pl wikantik-admin-mcp test -Dtest='McpToolRegistryTest,UpdatePageToolTest,WritePagesToolTest'`
Expected: `noToolAdvertisesLegacyPageNameParam` PASSES (offenders empty); update/write tool tests PASS (fix any schema-advertises-pageName assertions to expect `slug`).

- [ ] **Step 4: Install admin-mcp (knowledge-mcp builds against it in Task 6)**

Run: `mvn -q install -pl wikantik-admin-mcp -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/UpdatePageTool.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/WritePagesTool.java
git commit -m "refactor(mcp): update_page/write_pages advertise slug (pageName kept as alias)"
```

---

## Task 6: Knowledge-MCP — drop advertised `pageName`, route through shared accessors

`get_page` currently advertises **both** `slug` and `pageName` properties; `read_pages` advertises `slugs`. Remove the advertised `pageName` property, keep it accepted via the shared accessors, and add per-tool schema assertions (the admin registry guard doesn't cover knowledge tools).

**Files:** `wikantik-knowledge/.../mcp/GetPageTool.java`, `ReadPagesTool.java`; tests `GetPageToolTest.java`, `ReadPagesToolTest.java`.

- [ ] **Step 1: Failing schema assertions**

Add to `GetPageToolTest`:
```java
    @org.junit.jupiter.api.Test
    void schema_advertisesSlugNotPageName() {
        final var props = new GetPageTool( mock( ContextRetrievalService.class ) )
                .definition().inputSchema().properties();
        assertTrue( props.containsKey( "slug" ) );
        assertFalse( props.containsKey( "pageName" ) );
    }
```
Add to `ReadPagesToolTest`:
```java
    @Test
    void schema_advertisesSlugsNotPageNames() {
        final var props = tool.definition().inputSchema().properties();
        assertTrue( props.containsKey( "slugs" ) );
        assertFalse( props.containsKey( "pageNames" ) );
    }
```

- [ ] **Step 2: Run to verify the get_page one fails**

Run: `mvn -pl wikantik-knowledge test -Dtest='GetPageToolTest#schema_advertisesSlugNotPageName,ReadPagesToolTest#schema_advertisesSlugsNotPageNames'`
Expected: `GetPageToolTest` FAILS (it still advertises `pageName`); `ReadPagesToolTest` likely PASSES (already only `slugs`).

- [ ] **Step 3: Remove the advertised `pageName` from `GetPageTool` + route via shared accessor**

In `GetPageTool.java`, delete the `properties.put( "pageName", Map.of( … ) );` block (keep the `slug` property; update its description to note common aliases are still accepted). Replace the resolution `McpToolUtils.getStringAny( arguments, "slug", "pageName", "name", "page" )` with `McpToolUtils.pageSlug( arguments )`. In `ReadPagesTool.java`, replace the local `firstListArg(...)` call with `McpToolUtils.pageSlugs( arguments )` and delete the now-unused private `firstListArg` helper.

- [ ] **Step 4: Run the knowledge tool tests**

Run: `mvn -pl wikantik-knowledge test -Dtest='GetPageToolTest,ReadPagesToolTest'`
Expected: PASS (incl. the new schema assertions and the existing alias tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetPageTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ReadPagesTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetPageToolTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ReadPagesToolTest.java
git commit -m "refactor(mcp): knowledge get_page/read_pages advertise only slug/slugs, share accessors"
```

---

## Task 7: Full verification

- [ ] **Step 1: Both module suites green**

Run: `mvn -pl wikantik-admin-mcp test && mvn -pl wikantik-knowledge test`
Expected: BUILD SUCCESS both; `McpToolRegistryTest#noToolAdvertisesLegacyPageNameParam` green.

- [ ] **Step 2: Confirm no remaining advertised legacy param in source (belt-and-suspenders)**

Run: `grep -rnE 'properties.put\( "pageNames?"' wikantik-admin-mcp/src/main wikantik-knowledge/src/main`
Expected: no output (all converged).

- [ ] **Step 3: No commit** — all changes committed in Tasks 1–6.

---

## Self-Review Notes (for the author)

- **Spec coverage:** shared accessors (Task 1), advertised-`slug` convergence on every page tool — admin singular (3), plural (4), update/write (5), knowledge (6), `canonical_id` untouched (not modified anywhere), aliases retained (accessors), convergence-guard test (2), per-tool tests (3–6), out-of-scope KG/cluster `name` not touched. Covered.
- **Type/name consistency:** `pageSlug`/`pageSlugs`/`firstListArg`, property keys `slug`/`slugs`, guard test name `noToolAdvertisesLegacyPageNameParam` used consistently. Output `pageName` fields deliberately left (out of scope, noted).
- **No placeholders:** every step has exact files, lines, and code.
