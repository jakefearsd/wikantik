# Cycle 4: admin-mcp Rename + Write Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename `wikantik-mcp` → `wikantik-admin-mcp` (directory + artifactId + endpoint URL), add two new write tools (`write_pages`, `update_page`), and delete 9 obsolete tools whose capabilities now live in `/knowledge-mcp` (cycle 2) or in the new write tools.

**Architecture:** Keep the Java package `com.wikantik.mcp` (and `com.wikantik.mcp.tools`) unchanged — the package name describes the shared MCP tool contract, not the module's role. Only the Maven artifactId + filesystem directory + wire endpoint change. Two new write tools replace the old 3-step export/preview/import dance: `write_pages` batch-creates new pages, `update_page` edits with optimistic locking via `expectedContentHash`. Both are `AuthorConfigurable` (follow the existing `ImportContentTool` / `RenamePageTool` pattern).

**Tech Stack:** Java 21, Maven multi-module, JUnit 5 + Mockito, existing `PageSaveHelper` / `PageManager` / frontmatter utilities, `McpTool` contract from the same module.

**Reference spec:** `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`

---

## What gets dropped from the admin-mcp

Tools removed (capability moved to `/knowledge-mcp` in cycle 2 or to the new write tools in this cycle):

| Old tool | Replaced by |
|---|---|
| `read_page` | `/knowledge-mcp` `get_page` (cycle 2) |
| `search_pages` | `/knowledge-mcp` `retrieve_context` (cycle 2) |
| `query_metadata` | `/knowledge-mcp` `list_pages` (cycle 2) |
| `recent_changes` | `/knowledge-mcp` `list_pages(modifiedAfter)` (cycle 2) |
| `list_pages` (old) | `/knowledge-mcp` `list_pages` (cycle 2) |
| `list_metadata_values` (old) | `/knowledge-mcp` `list_metadata_values` (cycle 2) |
| `export_content` | Retired — agent authors content directly now |
| `preview_import` | Retired — `update_page` does a hash-check dry-run in its error path |
| `import_content` | `write_pages` (new) + `update_page` (new) |

What stays on `/wikantik-admin-mcp`:
- Writes: `rename_page` (existing), **`write_pages`** (new), **`update_page`** (new)
- Maintenance: `verify_pages`, `get_broken_links`, `get_orphaned_pages`, `get_outbound_links`, `get_backlinks`, `get_page_history`, `diff_page`, `preview_structured_data`, `ping_search_engines`, `get_wiki_stats`, `list_proposals`, `propose_knowledge`

Total after this cycle: **15 tools** (3 writes + 12 maintenance).

---

## File Structure

**Rename (directory + artifactId, Java package unchanged):**
- `wikantik-mcp/` → `wikantik-admin-mcp/`
- `wikantik-mcp/pom.xml` artifactId `wikantik-mcp` → `wikantik-admin-mcp`
- Parent `pom.xml` `<modules>` list
- `wikantik-war/pom.xml` dependency ref
- `wikantik-knowledge/pom.xml` dependency ref

**Endpoint rename (`/mcp` → `/wikantik-admin-mcp`):**
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpServerInitializer.java`

**Creating (in `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/`):**
- `WritePagesTool.java` — batch-create new pages
- `UpdatePageTool.java` — edit an existing page with hash-check

**Creating (in `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/`):**
- `WritePagesToolTest.java`
- `UpdatePageToolTest.java`

**Modifying:**
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java` — register new write tools, deregister obsolete tools

**Deleting (production + tests):**
- `ReadPageTool.java` + test
- `SearchPagesTool.java` + test
- `QueryMetadataTool.java` + test
- `RecentChangesTool.java` + test
- `ListPagesTool.java` + test
- `ListMetadataValuesTool.java` + test
- `ExportContentTool.java` + test
- `PreviewImportTool.java` + test
- `ImportContentTool.java` + test
- Supporting classes only used by these (e.g., `ExportManifest.java` — check before deleting)

---

## Conventions

- Apache 2.0 header on new Java source files.
- Generic spacing `< T >`.
- `git mv` for directory rename (preserves history).
- `McpToolUtils.SHARED_GSON` / `jsonResult` / `errorResult` for JSON shaping.
- `AuthorConfigurable` for tools that write (both new tools implement it, following the `ImportContentTool` pattern).
- Stage specific files for commits (no `git add -A`).

---

## Task 1: Rename `wikantik-mcp` → `wikantik-admin-mcp`

**Files:**
- Rename: `wikantik-mcp/` → `wikantik-admin-mcp/` (via `git mv`)
- Modify: `wikantik-admin-mcp/pom.xml` (renamed file — update `<artifactId>`)
- Modify: `pom.xml` (parent) — `<modules>` list
- Modify: `wikantik-war/pom.xml` — dependency `<artifactId>` ref
- Modify: `wikantik-knowledge/pom.xml` — dependency `<artifactId>` ref

- [ ] **Step 1: Baseline the build succeeds**

```bash
cd /home/jakefear/source/jspwiki && mvn -pl wikantik-mcp -am -q compile
```

Expected: BUILD SUCCESS. If it fails here, stop and report — don't rename over a broken build.

- [ ] **Step 2: Rename the directory**

```bash
cd /home/jakefear/source/jspwiki
git mv wikantik-mcp wikantik-admin-mcp
```

- [ ] **Step 3: Update artifact ids + module entries**

Edit `wikantik-admin-mcp/pom.xml` (at the previous `<artifactId>wikantik-mcp</artifactId>` line — NOT the parent reference). Change only the module's own artifactId to `wikantik-admin-mcp`.

Edit top-level `pom.xml`. In the `<modules>` list, change `<module>wikantik-mcp</module>` to `<module>wikantik-admin-mcp</module>`.

Edit `wikantik-war/pom.xml`. Change `<artifactId>wikantik-mcp</artifactId>` dependency reference to `<artifactId>wikantik-admin-mcp</artifactId>`.

Edit `wikantik-knowledge/pom.xml`. Same change — update the dependency ref.

Spot-check nothing else referenced the old name:

```bash
grep -rn "wikantik-mcp" --include="pom.xml" --include="*.xml" .
```

Should return nothing after the edits.

- [ ] **Step 4: Full-build + tests**

```bash
mvn clean install -T 1C -DskipITs 2>&1 | grep -E "BUILD|ERROR" | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A wikantik-admin-mcp pom.xml wikantik-war/pom.xml wikantik-knowledge/pom.xml
git commit -m "refactor(build): rename wikantik-mcp module to wikantik-admin-mcp"
```

(`-A` is fine here since the only untracked-tree changes are the moved files.)

---

## Task 2: Rename the MCP endpoint `/mcp` → `/wikantik-admin-mcp`

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpServerInitializer.java`

- [ ] **Step 1: Inspect current endpoint references**

```bash
grep -n "/mcp\b\|\"/mcp\"" wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpServerInitializer.java
```

Expected hits: class Javadoc (line 55), filter mapping (line 100), `mcpEndpoint("/mcp")` (106), `addMapping("/mcp")` (112), LOG.info (174). 5 occurrences.

- [ ] **Step 2: Rename all 5 occurrences**

Edit the file — replace every `/mcp` (within quoted strings and in the Javadoc) with `/wikantik-admin-mcp`. The endpoint description in the LOG message should also be updated. Example for each line type:

```java
 * registers a Streamable HTTP transport servlet at {@code /wikantik-admin-mcp}.
```
```java
filterReg.addMappingForUrlPatterns( EnumSet.of( DispatcherType.REQUEST ), false, "/wikantik-admin-mcp" );
```
```java
.mcpEndpoint( "/wikantik-admin-mcp" )
```
```java
registration.addMapping( "/wikantik-admin-mcp" );
```
```java
LOG.info( "MCP server started with {} tools, 6 resources, 8 prompts, and 3 completions at /wikantik-admin-mcp", totalTools );
```

- [ ] **Step 3: Full build**

```bash
mvn -pl wikantik-admin-mcp,wikantik-war -am -q install -DskipITs
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Spot-check nothing else hardcoded `/mcp`**

```bash
grep -rn "\"/mcp\"\|'/mcp'\|/mcp\b" wikantik-admin-mcp wikantik-war/src wikantik-knowledge/src --include="*.java" --include="*.xml" --include="*.md" | grep -v "/knowledge-mcp\|/wikantik-admin-mcp\|/mcp/"
```

If other code references `/mcp` directly (not as prefix of `/knowledge-mcp`), update it too. Reference CLAUDE.md or any doc strings describing the MCP endpoint — update those as well.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpServerInitializer.java
git commit -m "refactor(admin-mcp): move endpoint /mcp to /wikantik-admin-mcp"
```

---

## Task 3: `WritePagesTool` — batch-create new pages

**Files:**
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/WritePagesTool.java`
- Create: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/WritePagesToolTest.java`

Behavior: accepts `{pages: [{pageName, content, metadata}]}`. For each page, creates it if it does not exist; records per-page `{pageName, created: true|false, error?}` in the result. Best-effort batch — one failure doesn't stop the rest. Author resolved via `AuthorConfigurable.setDefaultAuthor()`.

- [ ] **Step 1: Write failing test**

Inspect `ImportContentTool.java` and `RenamePageTool.java` first to understand the `AuthorConfigurable` + `PageSaveHelper` usage pattern:

```bash
grep -n "AuthorConfigurable\|PageSaveHelper\|saveText\|pageExists" wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ImportContentTool.java | head -20
```

Then create `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/WritePagesToolTest.java`:

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WritePagesToolTest {

    @Test
    void name_isWritePages() {
        final WritePagesTool t = new WritePagesTool( mock( PageSaveHelper.class ), mock( PageManager.class ) );
        assertEquals( "write_pages", t.name() );
    }

    @Test
    void definition_requiresPagesArray() {
        final WritePagesTool t = new WritePagesTool( mock( PageSaveHelper.class ), mock( PageManager.class ) );
        assertTrue( t.definition().inputSchema().required().contains( "pages" ) );
    }

    @Test
    void execute_createsNewPages() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( anyString() ) ).thenReturn( null );  // none exist yet

        final WritePagesTool tool = new WritePagesTool( helper, pm );
        tool.setDefaultAuthor( "test-agent" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pages", List.of(
                Map.of( "pageName", "NewPageA", "content", "body A", "metadata", Map.of( "cluster", "x" ) ),
                Map.of( "pageName", "NewPageB", "content", "body B" ) ) ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "NewPageA" ) );
        assertTrue( text.contains( "NewPageB" ) );
        assertTrue( text.contains( "\"created\":true" ) );
        assertTrue( text.contains( "\"total\":2" ) );
        assertTrue( text.contains( "\"createdCount\":2" ) );

        verify( helper, times( 2 ) ).saveText(
            anyString(), anyString(), any(), eq( "test-agent" ), anyMap() );
    }

    @Test
    void execute_returnsFailureForExistingPage() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( "Exists" ) ).thenReturn( mock( Page.class ) );
        when( pm.getPage( "Fresh" ) ).thenReturn( null );

        final WritePagesTool tool = new WritePagesTool( helper, pm );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pages", List.of(
                Map.of( "pageName", "Exists", "content", "body" ),
                Map.of( "pageName", "Fresh", "content", "body" ) ) ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "already exists" ) );
        assertTrue( text.contains( "\"createdCount\":1" ) );
        assertTrue( text.contains( "\"failedCount\":1" ) );

        verify( helper, times( 1 ) ).saveText( eq( "Fresh" ), anyString(), any(), anyString(), anyMap() );
        verify( helper, never() ).saveText( eq( "Exists" ), anyString(), any(), anyString(), anyMap() );
    }

    @Test
    void execute_rejectsEmptyPagesList() {
        final WritePagesTool tool = new WritePagesTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ) );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "pages", List.of() ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }

    @Test
    void execute_rejectsMissingPagesKey() {
        final WritePagesTool tool = new WritePagesTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ) );
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }
}
```

Note: `PageSaveHelper.saveText` signature may differ — inspect the real API before finalizing the mock verification. The signature above matches `(String pageName, String body, Map<String,Object> metadata, String author, Map<String,Object> options)`. Adjust based on what `ImportContentTool` calls today:

```bash
grep -n "saveText\|pageSaveHelper\.\|helper\." wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ImportContentTool.java
```

Update the mock arguments in the test to match the exact signature used.

- [ ] **Step 2: Run to verify compile failure**

```bash
cd /home/jakefear/source/jspwiki && mvn -pl wikantik-admin-mcp -am -q test -Dtest=WritePagesToolTest
```

Expected: compile error on `WritePagesTool` unresolved.

- [ ] **Step 3: Create `WritePagesTool.java`**

Create `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/WritePagesTool.java` (Apache 2 header from a neighbor file like `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/RenamePageTool.java`, then):

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: batch-create new wiki pages. Fails individual pages that already
 * exist (callers should use {@code update_page} for those). Best-effort —
 * one page's failure doesn't stop the rest.
 */
public class WritePagesTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( WritePagesTool.class );
    public static final String TOOL_NAME = "write_pages";

    private final PageSaveHelper saveHelper;
    private final PageManager pageManager;
    private String defaultAuthor = "mcp-agent";

    public WritePagesTool( final PageSaveHelper saveHelper, final PageManager pageManager ) {
        this.saveHelper = saveHelper;
        this.pageManager = pageManager;
    }

    @Override
    public void setDefaultAuthor( final String author ) {
        if ( author != null && !author.isBlank() ) {
            this.defaultAuthor = author;
        }
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > pageSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "pageName", Map.of( "type", "string" ),
                "content",  Map.of( "type", "string" ),
                "metadata", Map.of( "type", "object" ) ),
            "required", List.of( "pageName", "content" ) );
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pages", Map.of(
            "type", "array",
            "items", pageSchema,
            "description", "Pages to create. Each item: {pageName, content, metadata?}." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Batch-create new wiki pages. Fails individual pages that already exist " +
                "— use update_page for those. Per-page {created, error?} results let the agent " +
                "retry only the failures." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of( "pages" ), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
            .build();
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final Object rawPages = arguments.get( "pages" );
        if ( !( rawPages instanceof List< ? > ) || ( (List< ? >) rawPages ).isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                "pages must be a non-empty array" );
        }
        final List< Map< String, Object > > pages = (List< Map< String, Object > >) rawPages;

        final List< Map< String, Object > > results = new ArrayList<>();
        int createdCount = 0;
        int failedCount = 0;
        for ( final Map< String, Object > p : pages ) {
            final String pageName = asString( p.get( "pageName" ) );
            final String content = asString( p.get( "content" ) );
            final Map< String, Object > metadata = asMap( p.get( "metadata" ) );

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "pageName", pageName );
            if ( pageName == null || pageName.isBlank() ) {
                entry.put( "created", false );
                entry.put( "error", "pageName must not be blank" );
                results.add( entry );
                failedCount++;
                continue;
            }
            if ( content == null ) {
                entry.put( "created", false );
                entry.put( "error", "content must not be null" );
                results.add( entry );
                failedCount++;
                continue;
            }
            final Page existing = pageManager.getPage( pageName );
            if ( existing != null ) {
                entry.put( "created", false );
                entry.put( "error", "already exists" );
                results.add( entry );
                failedCount++;
                continue;
            }
            try {
                saveHelper.saveText( pageName, content, metadata, defaultAuthor, Map.of() );
                entry.put( "created", true );
                entry.put( "contentHash", McpToolUtils.computeContentHash(
                    serialize( content, metadata ) ) );
                results.add( entry );
                createdCount++;
            } catch ( final RuntimeException e ) {
                LOG.error( "write_pages failed for '{}': {}", pageName, e.getMessage(), e );
                entry.put( "created", false );
                entry.put( "error", e.getMessage() );
                results.add( entry );
                failedCount++;
            }
        }

        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "results", results );
        final Map< String, Object > summary = new LinkedHashMap<>();
        summary.put( "total", pages.size() );
        summary.put( "createdCount", createdCount );
        summary.put( "failedCount", failedCount );
        payload.put( "summary", summary );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, payload );
    }

    private static String asString( final Object o ) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > asMap( final Object o ) {
        if ( o instanceof Map< ?, ? > ) return (Map< String, Object >) o;
        return Map.of();
    }

    private static String serialize( final String content, final Map< String, Object > metadata ) {
        // A minimal serialization for hash stability — production PageSaveHelper
        // handles the actual frontmatter serialization.
        return ( metadata == null || metadata.isEmpty() ? "" : metadata.toString() + "\n---\n" ) + content;
    }
}
```

**If `PageSaveHelper.saveText` signature doesn't match `(String, String, Map, String, Map)`** — inspect `ImportContentTool` to see the real signature and adapt the call. The fundamental behavior is: hand `pageName`, `content`, `metadata`, `author` into the save helper.

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -pl wikantik-admin-mcp -am -q test -Dtest=WritePagesToolTest
```

Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/WritePagesTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/WritePagesToolTest.java
git commit -m "feat(admin-mcp): add write_pages batch-create tool"
```

---

## Task 4: `UpdatePageTool` — edit existing page with hash check

**Files:**
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/UpdatePageTool.java`
- Create: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/UpdatePageToolTest.java`

Behavior: accepts `{pageName, content, metadata?, expectedContentHash}`. Fetches the current page; if `expectedContentHash` doesn't match current content hash, returns `{updated: false, error: "hash mismatch", currentHash}`. Otherwise saves and returns `{updated: true, newContentHash, newVersion}`.

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpdatePageToolTest {

    @Test
    void name_isUpdatePage() {
        assertEquals( "update_page",
            new UpdatePageTool( mock( PageSaveHelper.class ), mock( PageManager.class ) ).name() );
    }

    @Test
    void definition_requiresPageNameContentAndHash() {
        final UpdatePageTool t = new UpdatePageTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ) );
        final var req = t.definition().inputSchema().required();
        assertTrue( req.contains( "pageName" ) );
        assertTrue( req.contains( "content" ) );
        assertTrue( req.contains( "expectedContentHash" ) );
    }

    @Test
    void execute_updatesWhenHashMatches() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "current body" );

        final String currentHash = McpToolUtils.computeContentHash( "current body" );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pageName", "P",
            "content", "new body",
            "expectedContentHash", currentHash ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":true" ) );
        assertTrue( text.contains( "\"newContentHash\"" ) );
        verify( helper, times( 1 ) ).saveText( eq( "P" ), eq( "new body" ), any(), eq( "bot" ), anyMap() );
    }

    @Test
    void execute_failsOnHashMismatch() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( "P" ) ).thenReturn( mock( Page.class ) );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "drift body" );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm );
        tool.setDefaultAuthor( "bot" );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pageName", "P",
            "content", "new body",
            "expectedContentHash", "staleHashValue" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":false" ) );
        assertTrue( text.contains( "hash mismatch" ) );
        assertTrue( text.contains( "\"currentHash\"" ) );
        verify( helper, never() ).saveText( anyString(), anyString(), any(), anyString(), anyMap() );
    }

    @Test
    void execute_failsWhenPageDoesNotExist() {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        when( pm.getPage( "Missing" ) ).thenReturn( null );

        final UpdatePageTool tool = new UpdatePageTool( helper, pm );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pageName", "Missing",
            "content", "body",
            "expectedContentHash", "doesNotMatter" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"updated\":false" ) );
        assertTrue( text.contains( "not found" ) );
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

```bash
mvn -pl wikantik-admin-mcp -am -q test -Dtest=UpdatePageToolTest
```

Expected: compile error.

- [ ] **Step 3: Create `UpdatePageTool.java`**

Apache 2 header, then:

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.providers.PageProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: edit an existing wiki page with optimistic locking via
 * {@code expectedContentHash}. On hash mismatch, returns {updated:false,
 * error:"hash mismatch", currentHash} so the agent can re-fetch and retry.
 */
public class UpdatePageTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( UpdatePageTool.class );
    public static final String TOOL_NAME = "update_page";

    private final PageSaveHelper saveHelper;
    private final PageManager pageManager;
    private String defaultAuthor = "mcp-agent";

    public UpdatePageTool( final PageSaveHelper saveHelper, final PageManager pageManager ) {
        this.saveHelper = saveHelper;
        this.pageManager = pageManager;
    }

    @Override
    public void setDefaultAuthor( final String author ) {
        if ( author != null && !author.isBlank() ) {
            this.defaultAuthor = author;
        }
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of( "type", "string",
            "description", "Name of the existing page to update." ) );
        properties.put( "content", Map.of( "type", "string",
            "description", "New markdown body." ) );
        properties.put( "metadata", Map.of( "type", "object",
            "description", "Optional frontmatter metadata to merge." ) );
        properties.put( "expectedContentHash", Map.of( "type", "string",
            "description", "SHA-256 of the page's current raw text, obtained from " +
                "the last get_page or retrieve_context call. Required for optimistic locking." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Edit an existing page with optimistic locking. Returns " +
                "{updated, newContentHash, newVersion} on success or " +
                "{updated:false, error:'hash mismatch', currentHash} on drift so " +
                "the agent can re-fetch and retry." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties,
                List.of( "pageName", "content", "expectedContentHash" ), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String pageName = McpToolUtils.getString( arguments, "pageName" );
            final String content = McpToolUtils.getString( arguments, "content" );
            final String expectedHash = McpToolUtils.getString( arguments, "expectedContentHash" );
            if ( pageName == null || pageName.isBlank() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "pageName must not be blank" );
            }
            if ( content == null ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "content must not be null" );
            }
            if ( expectedHash == null || expectedHash.isBlank() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "expectedContentHash required" );
            }

            final Page existing = pageManager.getPage( pageName );
            if ( existing == null ) {
                final Map< String, Object > notFound = new LinkedHashMap<>();
                notFound.put( "pageName", pageName );
                notFound.put( "updated", false );
                notFound.put( "error", "not found" );
                return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, notFound );
            }

            final String currentText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
            final String currentHash = McpToolUtils.computeContentHash(
                currentText == null ? "" : currentText );
            if ( !expectedHash.equals( currentHash ) ) {
                final Map< String, Object > mismatch = new LinkedHashMap<>();
                mismatch.put( "pageName", pageName );
                mismatch.put( "updated", false );
                mismatch.put( "error", "hash mismatch" );
                mismatch.put( "currentHash", currentHash );
                return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, mismatch );
            }

            @SuppressWarnings( "unchecked" )
            final Map< String, Object > metadata = arguments.get( "metadata" ) instanceof Map< ?, ? >
                ? (Map< String, Object >) arguments.get( "metadata" ) : Map.of();
            saveHelper.saveText( pageName, content, metadata, defaultAuthor, Map.of() );

            final String newHash = McpToolUtils.computeContentHash( content );
            final Map< String, Object > ok = new LinkedHashMap<>();
            ok.put( "pageName", pageName );
            ok.put( "updated", true );
            ok.put( "newContentHash", newHash );
            ok.put( "newVersion", McpToolUtils.normalizeVersion( existing.getVersion() + 1 ) );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, ok );
        } catch ( final RuntimeException e ) {
            LOG.error( "update_page failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn -pl wikantik-admin-mcp -am -q test -Dtest=UpdatePageToolTest
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/UpdatePageTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/UpdatePageToolTest.java
git commit -m "feat(admin-mcp): add update_page tool with optimistic locking"
```

---

## Task 5: Register new tools + deregister obsolete tools in `McpToolRegistry`

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java`

- [ ] **Step 1: Inspect current registry**

```bash
cat wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java
```

Note the two lists (`readOnlyList`, `authorConfigurableList`) and which tools are currently in each.

- [ ] **Step 2: Update the registry**

Deregister 9 obsolete tools:
- From `readOnlyList`: `readPage`, `searchPages`, `listPages` (old), `queryMetadata`, `recentChanges`, `listMetadataValues` (old), `exportContent`, `previewImport`
- From `authorConfigurableList`: `importContent`

Register the 2 new write tools in `authorConfigurableList`:
- `new WritePagesTool( pageSaveHelper, pageManager )`
- `new UpdatePageTool( pageSaveHelper, pageManager )`

Also delete the unused local variables and imports for the removed tools.

After the change the two lists should contain only:

`readOnlyList`:
```
verifyPages, getBacklinks, getOutboundLinks, getBrokenLinks, getOrphanedPages,
getPageHistory, diffPage, getWikiStats, previewStructuredData, pingSearchEngines,
listProposals (if kgService != null)
```

`authorConfigurableList`:
```
renamePage, writePages, updatePage, proposeKnowledge (if kgService != null)
```

Remove the `attachmentManager` field if it's only used by the deleted `ExportContentTool` — check first.

Remove the `systemPageRegistry` usages if the `ReadPageTool` was the only consumer (it wasn't — `ListPagesTool` old and `RecentChangesTool` also used it). Keep the field if any surviving tool still uses it.

- [ ] **Step 3: Build + run registry test**

```bash
mvn -pl wikantik-admin-mcp -am -q test -Dtest=McpToolRegistry*
```

If no registry test exists, run `-Dtest=McpServerInitializerTest` or the nearest existing test. A full module test run catches the surface:

```bash
mvn -pl wikantik-admin-mcp -am -q test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: passes. If pre-existing tests referenced the removed tools directly, those tests belong to Task 6's deletion cleanup — surface the class names now.

- [ ] **Step 4: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java
git commit -m "refactor(admin-mcp): register write_pages/update_page, drop absorbed tools"
```

---

## Task 6: Delete obsolete tool files + tests

**Files deleted (production):**
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ReadPageTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/SearchPagesTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/QueryMetadataTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/RecentChangesTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListPagesTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListMetadataValuesTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ExportContentTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/PreviewImportTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ImportContentTool.java`

**Supporting classes to check for deletion:**
- `ExportManifest.java` — likely only used by `ExportContentTool`; delete if so.

**Test files deleted (if they exist):**
- Search for matching `*Test.java` files and delete them alongside the production file.

- [ ] **Step 1: Identify what depends on the doomed files**

```bash
cd /home/jakefear/source/jspwiki
for cls in ReadPageTool SearchPagesTool QueryMetadataTool RecentChangesTool ListPagesTool ListMetadataValuesTool ExportContentTool PreviewImportTool ImportContentTool ExportManifest; do
  echo "=== $cls ==="
  grep -rln "\b$cls\b" --include="*.java" . 2>/dev/null | grep -v "/target/" | grep -v "^Binary"
done
```

Any hits outside `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/` need inspection — update them (should be just `McpToolRegistry.java`, already handled in Task 5, plus the test files for each tool).

- [ ] **Step 2: Delete the files**

```bash
git rm wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ReadPageTool.java
git rm wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/SearchPagesTool.java
git rm wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/QueryMetadataTool.java
git rm wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/RecentChangesTool.java
git rm wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListPagesTool.java
git rm wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListMetadataValuesTool.java
git rm wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ExportContentTool.java
git rm wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/PreviewImportTool.java
git rm wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ImportContentTool.java
```

If `ExportManifest.java` is only referenced by `ExportContentTool` (verified in step 1), also delete:
```bash
git rm wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ExportManifest.java
```

Delete matching tests:
```bash
cd /home/jakefear/source/jspwiki
for cls in ReadPageTool SearchPagesTool QueryMetadataTool RecentChangesTool ListPagesTool ListMetadataValuesTool ExportContentTool PreviewImportTool ImportContentTool FrontmatterRoundTrip; do
  testFile="wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/${cls}Test.java"
  if [ -f "$testFile" ]; then git rm "$testFile"; fi
done
```

(`FrontmatterRoundTripTest` was probably for the import flow — check and include if it only exercises the deleted tools.)

- [ ] **Step 3: Full build**

```bash
cd /home/jakefear/source/jspwiki && mvn clean install -T 1C -DskipITs 2>&1 | grep -E "BUILD|ERROR" | tail -10
```

Expected: BUILD SUCCESS. If anything still references a deleted class, the compile error will point to it — fix in this same commit.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(admin-mcp): delete 9 absorbed tools — capabilities moved to /knowledge-mcp"
```

(Staging is already done via `git rm`.)

---

## Task 7: Client config + close cycle

**Files:** 
- Update: any local Claude MCP config references (note for the user; not a code change).
- Modify: `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md` to mark cycle 4 complete.

- [ ] **Step 1: Full build + integration tests**

```bash
cd /home/jakefear/source/jspwiki && mvn clean install -T 1C -DskipITs 2>&1 | grep -E "BUILD|ERROR" | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Note the client config change in the release notes**

Update `docs/wikantik-pages/News.md` (if a release-notes section exists there) with a short note: "MCP endpoint moved from `/mcp` to `/wikantik-admin-mcp`; re-register your MCP client." If News.md doesn't have a convenient slot, skip — the cycle 4 spec marker captures it.

- [ ] **Step 3: Mark cycle 4 complete in the spec**

Edit `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`. Change cycle 4's heading to `**Cycle 4 — admin-mcp rename + writes. ✓**`.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md
git commit -m "chore(spec): mark cycle 4 complete — admin-mcp rename + write tools"
```

---

## Summary

At cycle 4 complete:

- Module renamed `wikantik-mcp` → `wikantik-admin-mcp` (directory + artifactId; Java package unchanged).
- Endpoint moved `/mcp` → `/wikantik-admin-mcp`.
- Two new write tools (`write_pages`, `update_page`) replace the old 3-step export/preview/import flow.
- 9 obsolete tools deleted (capabilities absorbed into `/knowledge-mcp` or replaced by new writes).
- 15 tools total on `/wikantik-admin-mcp` (3 writes + 12 maintenance).
- Full build green.

Breaking change: any MCP client configured against `/mcp` must be reconfigured to `/wikantik-admin-mcp`. Note this in the release notes.

Deferred to cycle 5:
- Tool-server (`wikantik-tools`, OpenAPI) already aligned with the consumption service in cycle 1 — cycle 5 covers residual shape parity.

Deferred to cycle 6:
- `GraphProjector` retirement.
