# Cycle 2: `/knowledge-mcp` New Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose `ContextRetrievalService` to MCP clients by registering 4 new tools (`retrieve_context`, `get_page`, `list_pages`, `list_metadata_values`) on the `/knowledge-mcp` endpoint alongside the existing 6 KG tools.

**Architecture:** Each new tool is a `McpTool` implementation in `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/` that accepts JSON args, calls the corresponding `ContextRetrievalService` method, and serializes the result via Gson (using the existing `KnowledgeMcpUtils.GSON`). `KnowledgeMcpInitializer` is extended to construct the 4 new tools from the engine-registered service and register them on the MCP server.

**Tech Stack:** Java 21, JUnit 5 + Mockito, `io.modelcontextprotocol.spec.McpSchema`, Gson, existing `McpTool` contract from `wikantik-mcp`.

**Reference spec:** `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`
**Reference cycle 1:** `docs/superpowers/plans/2026-04-24-cycle-1-context-retrieval-service.md`

---

## File Structure

**Creating (in `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/`):**
- `RetrieveContextTool.java` — the primary RAG tool
- `GetPageTool.java` — pinned-context fetch
- `ListPagesTool.java` — filter-driven browse
- `ListMetadataValuesTool.java` — metadata discovery helper

**Creating (in `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/`):**
- `RetrieveContextToolTest.java`
- `GetPageToolTest.java`
- `ListPagesToolTest.java`
- `ListMetadataValuesToolTest.java`

**Modifying:**
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java` — register the 4 new tools

## Conventions

- Apache 2.0 license header on every new Java source file (copy from `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java`).
- Generic spacing: `List< Foo >` with spaces inside the diamond.
- JSON shaping: use `KnowledgeMcpUtils.GSON` for results (handles Instant as ISO-8601). Use `McpToolUtils.jsonResult(GSON, data)` / `McpToolUtils.errorResult(GSON, msg)` for the `CallToolResult` envelope.
- Argument extraction: use `McpToolUtils.getString(args, key)` / `getInt(args, key, default)`. For nested maps (filters), cast `(Map< String, Object >) args.get("filters")`.
- Tool NAME constants (e.g. `public static final String TOOL_NAME = "retrieve_context"`).
- Tests use Mockito to mock `ContextRetrievalService`; mockito-core is already a test-scope dep in `wikantik-knowledge`.

---

## Task 1: `RetrieveContextTool`

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/RetrieveContextTool.java`
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/RetrieveContextToolTest.java`

- [ ] **Step 1: Write failing test**

Create `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/RetrieveContextToolTest.java` (Apache 2 header, then):

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RetrieveContextToolTest {

    @Test
    void name_isRetrieveContext() {
        final RetrieveContextTool t = new RetrieveContextTool( mock( ContextRetrievalService.class ) );
        assertEquals( "retrieve_context", t.name() );
    }

    @Test
    void definition_requiresQuery() {
        final RetrieveContextTool t = new RetrieveContextTool( mock( ContextRetrievalService.class ) );
        final McpSchema.Tool def = t.definition();
        assertEquals( "retrieve_context", def.name() );
        assertTrue( def.inputSchema().required().contains( "query" ) );
    }

    @Test
    void execute_returnsShapedJson() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.retrieve( any( ContextQuery.class ) ) ).thenReturn(
            new RetrievalResult( "search query", List.of( new RetrievedPage(
                "Alpha", "https://wiki.example/Alpha", 0.87, "alpha summary",
                "search", List.of( "retrieval" ),
                List.of( new RetrievedChunk(
                    List.of( "Alpha", "Intro" ), "alpha body", 0.9, List.of() ) ),
                List.of( new RelatedPage( "Beta", "similarity 0.75" ) ),
                "alice", new Date() ) ), 3 ) );

        final RetrieveContextTool t = new RetrieveContextTool( svc );
        final McpSchema.CallToolResult result = t.execute( Map.of( "query", "search query" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"query\":\"search query\"" ) );
        assertTrue( text.contains( "\"name\":\"Alpha\"" ) );
        assertTrue( text.contains( "\"summary\":\"alpha summary\"" ) );
        assertTrue( text.contains( "\"cluster\":\"search\"" ) );
        assertTrue( text.contains( "\"contributingChunks\"" ) );
        assertTrue( text.contains( "\"headingPath\"" ) );
        assertTrue( text.contains( "\"alpha body\"" ) );
        assertTrue( text.contains( "\"relatedPages\"" ) );
        assertTrue( text.contains( "\"Beta\"" ) );
        assertTrue( text.contains( "\"totalMatched\":3" ) );
    }

    @Test
    void execute_passesFiltersToService() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.retrieve( any( ContextQuery.class ) ) )
            .thenReturn( new RetrievalResult( "q", List.of(), 0 ) );

        final RetrieveContextTool t = new RetrieveContextTool( svc );
        t.execute( Map.of(
            "query", "q",
            "maxPages", 3,
            "chunksPerPage", 2,
            "filters", Map.of( "cluster", "search", "tags", List.of( "retrieval" ) ) ) );

        final var captor = org.mockito.ArgumentCaptor.forClass( ContextQuery.class );
        verify( svc ).retrieve( captor.capture() );
        final ContextQuery q = captor.getValue();
        assertEquals( "q", q.query() );
        assertEquals( 3, q.maxPages() );
        assertEquals( 2, q.chunksPerPage() );
        assertEquals( "search", q.filter().cluster() );
        assertEquals( List.of( "retrieval" ), q.filter().tags() );
    }

    @Test
    void execute_returnsErrorOnBlankQuery() {
        final RetrieveContextTool t = new RetrieveContextTool( mock( ContextRetrievalService.class ) );
        final McpSchema.CallToolResult result = t.execute( Map.of( "query", "" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
cd /home/jakefear/source/jspwiki && mvn -pl wikantik-knowledge -am -q test -Dtest=RetrieveContextToolTest
```

Expected: compile failure — `RetrieveContextTool` unresolved.

- [ ] **Step 3: Create `RetrieveContextTool.java`**

Create `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/RetrieveContextTool.java` (Apache 2 header, then):

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: run the full retrieval pipeline (BM25 + dense + graph rerank)
 * for a natural-language query. Returns pages with their top contributing
 * chunks and a small list of KG-mention neighbors.
 */
public class RetrieveContextTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( RetrieveContextTool.class );
    public static final String TOOL_NAME = "retrieve_context";

    private final ContextRetrievalService service;

    public RetrieveContextTool( final ContextRetrievalService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "query", Map.of(
            "type", "string",
            "description", "Natural-language query for BM25 + dense retrieval." ) );
        properties.put( "maxPages", Map.of(
            "type", "integer",
            "description", "Max pages to return (default 5, max 20)." ) );
        properties.put( "chunksPerPage", Map.of(
            "type", "integer",
            "description", "Top contributing chunks per page (default 3, max 5)." ) );
        properties.put( "filters", Map.of(
            "type", "object",
            "description", "Optional pre-filter — cluster, tags, type, modifiedAfter (ISO-8601)." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Retrieve wiki context for a natural-language query. " +
                "Returns {query, pages: [{name, url, score, summary, cluster, tags, " +
                "contributingChunks, relatedPages, author, lastModified}], totalMatched}. " +
                "Primary RAG entry point for agents consuming wiki context." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of( "query" ), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String query = McpToolUtils.getString( arguments, "query" );
            final int maxPages = McpToolUtils.getInt( arguments, "maxPages", 5 );
            final int chunksPerPage = McpToolUtils.getInt( arguments, "chunksPerPage", 3 );
            final PageListFilter filter = parseFilter( arguments );
            final ContextQuery q = new ContextQuery( query, maxPages, chunksPerPage, filter );
            final RetrievalResult result = service.retrieve( q );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, result );
        } catch ( final IllegalArgumentException e ) {
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        } catch ( final RuntimeException e ) {
            LOG.error( "retrieve_context failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }

    @SuppressWarnings( "unchecked" )
    private PageListFilter parseFilter( final Map< String, Object > arguments ) {
        final Object rawFilters = arguments.get( "filters" );
        if ( !( rawFilters instanceof Map< ?, ? > ) ) return null;
        final Map< String, Object > filters = (Map< String, Object >) rawFilters;
        return new PageListFilter(
            asString( filters.get( "cluster" ) ),
            asStringList( filters.get( "tags" ) ),
            asString( filters.get( "type" ) ),
            asString( filters.get( "author" ) ),
            asInstant( filters.get( "modifiedAfter" ) ),
            asInstant( filters.get( "modifiedBefore" ) ),
            50, 0 );
    }

    private static String asString( final Object o ) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > asStringList( final Object o ) {
        if ( !( o instanceof List< ? > ) ) return null;
        return ( (List< Object >) o ).stream().filter( java.util.Objects::nonNull )
            .map( Object::toString ).toList();
    }

    private static Instant asInstant( final Object o ) {
        if ( !( o instanceof String ) || ( (String) o ).isBlank() ) return null;
        try {
            return Instant.parse( (String) o );
        } catch ( final DateTimeParseException e ) {
            throw new IllegalArgumentException( "Invalid ISO-8601 instant: " + o );
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=RetrieveContextToolTest
```

Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/RetrieveContextTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/RetrieveContextToolTest.java
git commit -m "feat(knowledge-mcp): add retrieve_context tool"
```

---

## Task 2: `GetPageTool`

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetPageTool.java`
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetPageToolTest.java`

- [ ] **Step 1: Write failing test**

Create the test (Apache 2 header, then):

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RetrievedPage;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetPageToolTest {

    @Test
    void name_isGetPage() {
        assertEquals( "get_page", new GetPageTool( mock( ContextRetrievalService.class ) ).name() );
    }

    @Test
    void definition_requiresPageName() {
        final GetPageTool t = new GetPageTool( mock( ContextRetrievalService.class ) );
        assertTrue( t.definition().inputSchema().required().contains( "pageName" ) );
    }

    @Test
    void execute_returnsPageJson() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.getPage( "Alpha" ) ).thenReturn( new RetrievedPage(
            "Alpha", "https://wiki.example/Alpha", 0.0, "alpha summary",
            "search", List.of( "retrieval" ),
            List.of(), List.of(), "alice", new Date() ) );

        final McpSchema.CallToolResult result =
            new GetPageTool( svc ).execute( Map.of( "pageName", "Alpha" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"name\":\"Alpha\"" ) );
        assertTrue( text.contains( "\"summary\":\"alpha summary\"" ) );
        assertTrue( text.contains( "\"cluster\":\"search\"" ) );
        assertTrue( text.contains( "\"author\":\"alice\"" ) );
    }

    @Test
    void execute_returnsNotFoundWhenMissing() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.getPage( "Nope" ) ).thenReturn( null );

        final McpSchema.CallToolResult result =
            new GetPageTool( svc ).execute( Map.of( "pageName", "Nope" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"exists\":false" ) );
        assertTrue( text.contains( "\"pageName\":\"Nope\"" ) );
    }

    @Test
    void execute_returnsErrorOnBlankPageName() {
        final GetPageTool t = new GetPageTool( mock( ContextRetrievalService.class ) );
        final McpSchema.CallToolResult result = t.execute( Map.of( "pageName", "" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=GetPageToolTest
```

Expected: compile failure.

- [ ] **Step 3: Create `GetPageTool.java`**

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: fetch a single page by name for pinned-context flows. Returns
 * {name, url, score=0, summary, cluster, tags, author, lastModified} or
 * {exists:false, pageName} if the page does not exist.
 */
public class GetPageTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( GetPageTool.class );
    public static final String TOOL_NAME = "get_page";

    private final ContextRetrievalService service;

    public GetPageTool( final ContextRetrievalService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "pageName", Map.of(
            "type", "string",
            "description", "Name of the wiki page to fetch." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Fetch a single wiki page by name. Use for pinned-context flows " +
                "when you already know which page to load. Returns the page's frontmatter " +
                "metadata and a URL. Use retrieve_context instead when querying by topic." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of( "pageName" ), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String pageName = McpToolUtils.getString( arguments, "pageName" );
            if ( pageName == null || pageName.isBlank() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "pageName must not be blank" );
            }
            final RetrievedPage page = service.getPage( pageName );
            if ( page == null ) {
                final Map< String, Object > missing = new LinkedHashMap<>();
                missing.put( "exists", false );
                missing.put( "pageName", pageName );
                return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, missing );
            }
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, page );
        } catch ( final RuntimeException e ) {
            LOG.error( "get_page failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=GetPageToolTest
```

Expected: all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetPageTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetPageToolTest.java
git commit -m "feat(knowledge-mcp): add get_page tool"
```

---

## Task 3: `ListPagesTool`

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListPagesTool.java`
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListPagesToolTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievedPage;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListPagesToolTest {

    @Test
    void name_isListPages() {
        assertEquals( "list_pages", new ListPagesTool( mock( ContextRetrievalService.class ) ).name() );
    }

    @Test
    void definition_hasNoRequiredFields() {
        final McpSchema.Tool def = new ListPagesTool( mock( ContextRetrievalService.class ) ).definition();
        assertTrue( def.inputSchema().required() == null || def.inputSchema().required().isEmpty() );
    }

    @Test
    void execute_returnsShapedJson() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ) ) ).thenReturn(
            new PageList( List.of(
                new RetrievedPage( "Alpha", "u1", 0.0, "", "search", List.of(),
                    List.of(), List.of(), null, new Date() ),
                new RetrievedPage( "Beta", "u2", 0.0, "", "search", List.of(),
                    List.of(), List.of(), null, new Date() ) ),
                5, 50, 0 ) );

        final McpSchema.CallToolResult result =
            new ListPagesTool( svc ).execute( Map.of( "cluster", "search" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"totalMatched\":5" ) );
        assertTrue( text.contains( "\"name\":\"Alpha\"" ) );
        assertTrue( text.contains( "\"name\":\"Beta\"" ) );
    }

    @Test
    void execute_passesFiltersToService() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ) ) )
            .thenReturn( new PageList( List.of(), 0, 50, 0 ) );

        new ListPagesTool( svc ).execute( Map.of(
            "cluster", "search",
            "tags", List.of( "retrieval", "bm25" ),
            "limit", 25,
            "offset", 5 ) );

        final ArgumentCaptor< PageListFilter > captor = ArgumentCaptor.forClass( PageListFilter.class );
        verify( svc ).listPages( captor.capture() );
        final PageListFilter f = captor.getValue();
        assertEquals( "search", f.cluster() );
        assertEquals( List.of( "retrieval", "bm25" ), f.tags() );
        assertEquals( 25, f.limit() );
        assertEquals( 5, f.offset() );
    }

    @Test
    void execute_defaultsToEmptyFilter() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ) ) )
            .thenReturn( new PageList( List.of(), 0, 50, 0 ) );

        new ListPagesTool( svc ).execute( Map.of() );

        final ArgumentCaptor< PageListFilter > captor = ArgumentCaptor.forClass( PageListFilter.class );
        verify( svc ).listPages( captor.capture() );
        assertNull( captor.getValue().cluster() );
        assertEquals( 50, captor.getValue().limit() );
    }
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=ListPagesToolTest
```

Expected: compile failure.

- [ ] **Step 3: Create `ListPagesTool.java`**

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: filter-driven browse over wiki pages. No ranking, no chunks —
 * use retrieve_context for query-driven retrieval.
 */
public class ListPagesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListPagesTool.class );
    public static final String TOOL_NAME = "list_pages";

    private final ContextRetrievalService service;

    public ListPagesTool( final ContextRetrievalService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "cluster", Map.of( "type", "string",
            "description", "Filter by frontmatter cluster value (exact match)." ) );
        properties.put( "tags", Map.of( "type", "array", "items", Map.of( "type", "string" ),
            "description", "Filter to pages that have ALL listed tags." ) );
        properties.put( "type", Map.of( "type", "string",
            "description", "Filter by frontmatter type value." ) );
        properties.put( "author", Map.of( "type", "string",
            "description", "Filter by page author." ) );
        properties.put( "modifiedAfter", Map.of( "type", "string",
            "description", "ISO-8601 instant — include only pages modified after this time." ) );
        properties.put( "modifiedBefore", Map.of( "type", "string",
            "description", "ISO-8601 instant — include only pages modified before this time." ) );
        properties.put( "limit", Map.of( "type", "integer",
            "description", "Max rows (default 50, max 200)." ) );
        properties.put( "offset", Map.of( "type", "integer",
            "description", "Pagination offset (default 0)." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Browse wiki pages by metadata filters. Returns page summaries " +
                "(no chunks, no relatedPages). All filters are optional and combined with AND. " +
                "Use retrieve_context for query-driven retrieval." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final PageListFilter filter = buildFilter( arguments );
            final PageList result = service.listPages( filter );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, result );
        } catch ( final IllegalArgumentException e ) {
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        } catch ( final RuntimeException e ) {
            LOG.error( "list_pages failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }

    private PageListFilter buildFilter( final Map< String, Object > arguments ) {
        return new PageListFilter(
            asString( arguments.get( "cluster" ) ),
            asStringList( arguments.get( "tags" ) ),
            asString( arguments.get( "type" ) ),
            asString( arguments.get( "author" ) ),
            asInstant( arguments.get( "modifiedAfter" ) ),
            asInstant( arguments.get( "modifiedBefore" ) ),
            McpToolUtils.getInt( arguments, "limit", 50 ),
            McpToolUtils.getInt( arguments, "offset", 0 ) );
    }

    private static String asString( final Object o ) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > asStringList( final Object o ) {
        if ( !( o instanceof List< ? > ) ) return null;
        return ( (List< Object >) o ).stream().filter( java.util.Objects::nonNull )
            .map( Object::toString ).toList();
    }

    private static Instant asInstant( final Object o ) {
        if ( !( o instanceof String ) || ( (String) o ).isBlank() ) return null;
        try {
            return Instant.parse( (String) o );
        } catch ( final DateTimeParseException e ) {
            throw new IllegalArgumentException( "Invalid ISO-8601 instant: " + o );
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=ListPagesToolTest
```

Expected: all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListPagesTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListPagesToolTest.java
git commit -m "feat(knowledge-mcp): add list_pages tool"
```

---

## Task 4: `ListMetadataValuesTool`

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListMetadataValuesTool.java`
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListMetadataValuesToolTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.MetadataValue;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListMetadataValuesToolTest {

    @Test
    void name_isListMetadataValues() {
        assertEquals( "list_metadata_values",
            new ListMetadataValuesTool( mock( ContextRetrievalService.class ) ).name() );
    }

    @Test
    void definition_requiresField() {
        final McpSchema.Tool def = new ListMetadataValuesTool( mock( ContextRetrievalService.class ) ).definition();
        assertTrue( def.inputSchema().required().contains( "field" ) );
    }

    @Test
    void execute_returnsShapedJson() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listMetadataValues( "cluster" ) ).thenReturn( List.of(
            new MetadataValue( "search", 14 ),
            new MetadataValue( "kg", 8 ) ) );

        final McpSchema.CallToolResult result =
            new ListMetadataValuesTool( svc ).execute( Map.of( "field", "cluster" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"field\":\"cluster\"" ) );
        assertTrue( text.contains( "\"value\":\"search\"" ) );
        assertTrue( text.contains( "\"count\":14" ) );
        assertTrue( text.contains( "\"value\":\"kg\"" ) );
    }

    @Test
    void execute_returnsErrorOnBlankField() {
        final ListMetadataValuesTool t = new ListMetadataValuesTool( mock( ContextRetrievalService.class ) );
        final McpSchema.CallToolResult result = t.execute( Map.of( "field", "" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=ListMetadataValuesToolTest
```

Expected: compile failure.

- [ ] **Step 3: Create `ListMetadataValuesTool.java`**

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.MetadataValue;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: distinct frontmatter field values across all pages, with
 * per-value page counts. Useful for "what clusters exist?" discovery queries.
 */
public class ListMetadataValuesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListMetadataValuesTool.class );
    public static final String TOOL_NAME = "list_metadata_values";

    private final ContextRetrievalService service;

    public ListMetadataValuesTool( final ContextRetrievalService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "field", Map.of(
            "type", "string",
            "description", "Frontmatter key (e.g. cluster, type, tags) to enumerate." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Return distinct values of a frontmatter field across all pages, " +
                "with the count of pages for each value. Results are sorted by count descending." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of( "field" ), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String field = McpToolUtils.getString( arguments, "field" );
            if ( field == null || field.isBlank() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "field must not be blank" );
            }
            final List< MetadataValue > values = service.listMetadataValues( field );
            final Map< String, Object > payload = new LinkedHashMap<>();
            payload.put( "field", field );
            payload.put( "values", values );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, payload );
        } catch ( final RuntimeException e ) {
            LOG.error( "list_metadata_values failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=ListMetadataValuesToolTest
```

Expected: all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListMetadataValuesTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListMetadataValuesToolTest.java
git commit -m "feat(knowledge-mcp): add list_metadata_values tool"
```

---

## Task 5: Register new tools in `KnowledgeMcpInitializer`

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`

Currently the initializer only starts the MCP server if `KnowledgeGraphService` is configured. With cycle-2 tools, we should also start when `ContextRetrievalService` is available — independently of the KG tools. Both sets of tools get registered when both services are present.

- [ ] **Step 1: Inspect the existing initializer**

Read `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java` (already reviewed in cycle 1) to confirm structure. The current flow:

1. Obtain `Engine` via `Wiki.engine().find(ctx, null)`.
2. Obtain `KnowledgeGraphService` — bail if null.
3. Register transport servlet at `/knowledge-mcp`.
4. Construct the 6 KG tools.
5. Build and start the MCP server with those tools.

The change for cycle 2: don't bail early if `KnowledgeGraphService` is null. Obtain `ContextRetrievalService` too. If BOTH are null, bail. Otherwise register whichever tools are applicable.

- [ ] **Step 2: Edit the initializer**

Replace the body of `contextInitialized(...)` in `KnowledgeMcpInitializer.java` with:

```java
    @Override
    public void contextInitialized( final ServletContextEvent sce ) {
        final ServletContext servletContext = sce.getServletContext();

        final Engine engine;
        try {
            engine = Wiki.engine().find( servletContext, null );
        } catch ( final Exception e ) {
            LOG.warn( "WikiEngine could not be created — Knowledge MCP server not started: {}", e.getMessage() );
            return;
        }

        final KnowledgeGraphService kgService = engine.getManager( KnowledgeGraphService.class );
        final ContextRetrievalService ctxService = engine.getManager( ContextRetrievalService.class );

        if ( kgService == null && ctxService == null ) {
            LOG.info( "Neither KnowledgeGraphService nor ContextRetrievalService configured — " +
                "Knowledge MCP server not started" );
            return;
        }

        try {
            final HttpServletStreamableServerTransportProvider transportProvider =
                    HttpServletStreamableServerTransportProvider.builder()
                            .mcpEndpoint( "/knowledge-mcp" )
                            .build();

            final ServletRegistration.Dynamic registration =
                    servletContext.addServlet( "KnowledgeMcpTransportServlet", transportProvider );
            registration.addMapping( "/knowledge-mcp" );
            registration.setAsyncSupported( true );
            registration.setLoadOnStartup( 3 );

            final List< McpTool > tools = new ArrayList<>();

            // KG-native tools (only if KG is configured).
            if ( kgService != null ) {
                tools.add( new DiscoverSchemaTool( kgService ) );
                tools.add( new QueryNodesTool( kgService ) );
                tools.add( new GetNodeTool( kgService ) );
                tools.add( new TraverseTool( kgService ) );
                tools.add( new SearchKnowledgeTool( kgService ) );
                final NodeMentionSimilarity similarity = engine.getManager( NodeMentionSimilarity.class );
                if ( similarity != null ) {
                    tools.add( new FindSimilarTool( similarity ) );
                }
            }

            // Context retrieval tools (only if ContextRetrievalService is configured).
            if ( ctxService != null ) {
                tools.add( new RetrieveContextTool( ctxService ) );
                tools.add( new GetPageTool( ctxService ) );
                tools.add( new ListPagesTool( ctxService ) );
                tools.add( new ListMetadataValuesTool( ctxService ) );
            }

            final var serverImpl = new McpSchema.Implementation(
                    "wikantik-knowledge", "Wikantik Knowledge Graph", Release.getVersionString() );

            final var builder = McpServer.sync( transportProvider )
                    .serverInfo( serverImpl )
                    .instructions( "Agent-facing MCP endpoint. For wiki content use " +
                        "retrieve_context (primary RAG), get_page (pinned fetch), " +
                        "list_pages (browse), or list_metadata_values (discovery). " +
                        "For knowledge graph structure use discover_schema, query_nodes, " +
                        "get_node, traverse, search_knowledge, or find_similar." )
                    .capabilities( ServerCapabilities.builder()
                            .tools( true )
                            .build() );

            for ( final McpTool tool : tools ) {
                builder.toolCall( tool.definition(), ( exchange, request ) ->
                        tool.execute( request.arguments() ) );
            }

            mcpServer = builder.build();

            servletContext.setAttribute( ATTR_KNOWLEDGE_MCP_SERVER, mcpServer );
            LOG.info( "Knowledge MCP server started successfully with {} tools at /knowledge-mcp", tools.size() );

        } catch ( final Exception e ) {
            LOG.error( "Failed to start Knowledge MCP server: {}", e.getMessage(), e );
        }
    }
```

Add these imports at the top of the file (check which are already present):

```java
import com.wikantik.api.knowledge.ContextRetrievalService;
```

- [ ] **Step 3: Verify compile + existing tests still pass**

```bash
cd /home/jakefear/source/jspwiki && mvn -pl wikantik-knowledge -am -q test -Dtest=KnowledgeMcpToolsTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: existing KG tool tests still pass. (They don't exercise the initializer directly, but this confirms nothing was broken.)

Run the full knowledge module:
```bash
mvn -pl wikantik-knowledge -am -q test
```

Expected: all tests pass (33 from cycle 1 + 17 new from tasks 1-4 = 50).

- [ ] **Step 4: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java
git commit -m "feat(knowledge-mcp): register 4 new context tools alongside KG tools"
```

---

## Task 6: Full build + close cycle

**Files:** none created

- [ ] **Step 1: Full build**

```bash
cd /home/jakefear/source/jspwiki && mvn clean install -T 1C -DskipITs 2>&1 | grep -E "BUILD|ERROR|Tests run:" | tail -20
```

Expected: BUILD SUCCESS. Total test count ≥ cycle-1 count + 17.

- [ ] **Step 2: Mark cycle 2 complete in the spec**

Edit `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`, change the cycle 2 heading to:

```
2. **Cycle 2 — new `/knowledge-mcp` tools. ✓** `retrieve_context`,
```

- [ ] **Step 3: Commit spec update**

```bash
git add docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md
git commit -m "chore(spec): mark cycle 2 complete — new /knowledge-mcp tools"
```

---

## Summary

At cycle 2 complete:

- 4 new MCP tools exposed on `/knowledge-mcp`: `retrieve_context`, `get_page`, `list_pages`, `list_metadata_values`.
- 6 existing KG tools continue to work unchanged.
- `KnowledgeMcpInitializer` starts the MCP server when EITHER `KnowledgeGraphService` OR `ContextRetrievalService` is configured (both paths independent).
- ~17 new unit tests pass; full build green.

Next cycle: rebacking the 6 existing KG tools onto the mention-based graph (task 3 of the spec's rollout).
