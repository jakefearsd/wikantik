# Cycle 5: Tool-Server Shape Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the OpenAPI tool-server (`/tools/search_wiki`) into shape parity with MCP `retrieve_context` — each result includes a `contributingChunks` array (heading path + text + score) and a `relatedPages` array (name + reason), not just a single flattened `snippet` string.

**Architecture:** `SearchWikiTool` already routes through `ContextRetrievalService` (landed in cycle 1), so the data is already available in `RetrievedPage.contributingChunks()` and `RetrievedPage.relatedPages()`. The tool currently flattens the top chunk to a single `snippet` string — cycle 5 adds the richer arrays while keeping `snippet` as a backward-compat convenience. `OpenApiDocument` gets two new schemas (`ContributingChunk`, `RelatedPageHint`) wired into `SearchResult`.

**Tech Stack:** Java 21, JUnit 5 + Mockito, existing `wikantik-tools` module.

**Reference spec:** `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`

---

## File Structure

**Modifying:**
- `wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java` — emit `contributingChunks` + `relatedPages` arrays alongside `snippet`.
- `wikantik-tools/src/main/java/com/wikantik/tools/OpenApiDocument.java` — schema for the two new array fields.
- `wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java` — assertions on the new fields.
- `wikantik-tools/src/test/java/com/wikantik/tools/OpenApiDocumentTest.java` — schema assertions (if it verifies schema shape — otherwise unchanged).

---

## Conventions

- Existing file, existing conventions — generics `< T >`, Apache 2 headers stay as-is.
- `snippet` is retained for backward compatibility with any OpenWebUI consumer that already reads it. Newer consumers use `contributingChunks`.
- Tests use Mockito (already wired — project-wide mockito-core with dynamic agent loading).

---

## Task 1: Enrich `SearchWikiTool` response

**Files:**
- Modify: `wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java`
- Modify: `wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java`

- [ ] **Step 1: Append failing tests**

Open `wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java` and add two new tests that exercise the new fields. Since existing tests already mock `ContextRetrievalService` and build `RetrievedPage` values, follow that pattern. Append inside the test class (before the closing brace):

```java
    @Test
    void execute_includesContributingChunksArray() {
        when( ctxService.retrieve( any( com.wikantik.api.knowledge.ContextQuery.class ) ) )
            .thenReturn( new com.wikantik.api.knowledge.RetrievalResult(
                "q",
                java.util.List.of( new com.wikantik.api.knowledge.RetrievedPage(
                    "Alpha", "", 5.0, "alpha summary", null, java.util.List.of(),
                    java.util.List.of(
                        new com.wikantik.api.knowledge.RetrievedChunk(
                            java.util.List.of( "Alpha", "Intro" ), "first chunk body", 0.9, java.util.List.of() ),
                        new com.wikantik.api.knowledge.RetrievedChunk(
                            java.util.List.of( "Alpha", "Details" ), "second chunk body", 0.8, java.util.List.of() ) ),
                    java.util.List.of(), null, null ) ),
                1 ) );

        final java.util.Map< String, Object > result = tool.execute( "q", 10, request );
        @SuppressWarnings( "unchecked" )
        final java.util.List< java.util.Map< String, Object > > results =
            (java.util.List< java.util.Map< String, Object > >) result.get( "results" );
        assertEquals( 1, results.size() );
        @SuppressWarnings( "unchecked" )
        final java.util.List< java.util.Map< String, Object > > chunks =
            (java.util.List< java.util.Map< String, Object > >) results.get( 0 ).get( "contributingChunks" );
        assertNotNull( chunks );
        assertEquals( 2, chunks.size() );
        assertEquals( "first chunk body", chunks.get( 0 ).get( "text" ) );
        assertEquals( java.util.List.of( "Alpha", "Intro" ), chunks.get( 0 ).get( "headingPath" ) );
        assertEquals( 0.9, (double) chunks.get( 0 ).get( "chunkScore" ), 1e-9 );
    }

    @Test
    void execute_includesRelatedPagesArray() {
        when( ctxService.retrieve( any( com.wikantik.api.knowledge.ContextQuery.class ) ) )
            .thenReturn( new com.wikantik.api.knowledge.RetrievalResult(
                "q",
                java.util.List.of( new com.wikantik.api.knowledge.RetrievedPage(
                    "Alpha", "", 5.0, "alpha summary", null, java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(
                        new com.wikantik.api.knowledge.RelatedPage( "Beta", "shared entities: x, y" ),
                        new com.wikantik.api.knowledge.RelatedPage( "Gamma", "shared entities: y" ) ),
                    null, null ) ),
                1 ) );

        final java.util.Map< String, Object > result = tool.execute( "q", 10, request );
        @SuppressWarnings( "unchecked" )
        final java.util.List< java.util.Map< String, Object > > results =
            (java.util.List< java.util.Map< String, Object > >) result.get( "results" );
        @SuppressWarnings( "unchecked" )
        final java.util.List< java.util.Map< String, Object > > related =
            (java.util.List< java.util.Map< String, Object > >) results.get( 0 ).get( "relatedPages" );
        assertNotNull( related );
        assertEquals( 2, related.size() );
        assertEquals( "Beta", related.get( 0 ).get( "name" ) );
        assertEquals( "shared entities: x, y", related.get( 0 ).get( "reason" ) );
    }

    @Test
    void execute_omitsContributingChunksWhenEmpty() {
        when( ctxService.retrieve( any( com.wikantik.api.knowledge.ContextQuery.class ) ) )
            .thenReturn( new com.wikantik.api.knowledge.RetrievalResult(
                "q",
                java.util.List.of( new com.wikantik.api.knowledge.RetrievedPage(
                    "Alpha", "", 5.0, "alpha summary", null, java.util.List.of(),
                    java.util.List.of(), java.util.List.of(), null, null ) ),
                1 ) );

        final java.util.Map< String, Object > result = tool.execute( "q", 10, request );
        @SuppressWarnings( "unchecked" )
        final java.util.List< java.util.Map< String, Object > > results =
            (java.util.List< java.util.Map< String, Object > >) result.get( "results" );
        assertFalse( results.get( 0 ).containsKey( "contributingChunks" ),
            "empty chunks should be omitted, not serialized as []" );
        assertFalse( results.get( 0 ).containsKey( "relatedPages" ),
            "empty related should be omitted, not serialized as []" );
        assertFalse( results.get( 0 ).containsKey( "snippet" ),
            "no snippet when no chunks to source from" );
    }
```

If the existing test file uses static imports for `Mockito.when` / `any()` / `assertEquals` / etc., reuse those — don't re-qualify them. The fully-qualified names above are just for clarity if imports aren't already present.

- [ ] **Step 2: Run tests to verify the new ones fail**

```bash
cd /home/jakefear/source/jspwiki && mvn -pl wikantik-tools -am -q test -Dtest=SearchWikiToolTest
```

Expected: the two array-field tests fail because the current `SearchWikiTool` doesn't emit `contributingChunks` or `relatedPages`. The "omits empty" test may also fail if current behavior already serializes an empty array.

- [ ] **Step 3: Modify `SearchWikiTool.execute`**

Open `wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java`. In the loop that builds the `shaped` entries (around the existing `snippet` block), add `contributingChunks` and `relatedPages` fields. Keep `snippet` for backward compatibility.

Replace the body of the `for ( final RetrievedPage p : retrieval.pages() )` loop with:

```java
        for ( final RetrievedPage p : retrieval.pages() ) {
            if ( shaped.size() >= clamped ) break;
            final Map< String, Object > entry = ResultShaper.orderedMap();
            entry.put( "name", p.name() );
            entry.put( "url", ResultShaper.citationUrl( p.name(), request, publicBaseUrl ) );
            entry.put( "score", p.score() );
            if ( !p.summary().isEmpty() ) entry.put( "summary", p.summary() );
            if ( !p.tags().isEmpty() ) entry.put( "tags", p.tags() );
            if ( p.cluster() != null ) entry.put( "cluster", p.cluster() );

            if ( !p.contributingChunks().isEmpty() ) {
                // Rich array for new consumers — matches MCP retrieve_context shape.
                final List< Map< String, Object > > chunksOut = new ArrayList<>( p.contributingChunks().size() );
                for ( final RetrievedChunk c : p.contributingChunks() ) {
                    final Map< String, Object > chunkEntry = ResultShaper.orderedMap();
                    chunkEntry.put( "headingPath", c.headingPath() );
                    chunkEntry.put( "text", c.text() );
                    chunkEntry.put( "chunkScore", c.chunkScore() );
                    chunksOut.add( chunkEntry );
                }
                entry.put( "contributingChunks", chunksOut );

                // Back-compat: single snippet from the top chunk, truncated.
                final RetrievedChunk c0 = p.contributingChunks().get( 0 );
                final String snippet = c0.text().length() > 320
                    ? c0.text().substring( 0, 320 ) + "…"
                    : c0.text();
                entry.put( "snippet", snippet );
            }

            if ( !p.relatedPages().isEmpty() ) {
                final List< Map< String, Object > > relatedOut = new ArrayList<>( p.relatedPages().size() );
                for ( final var r : p.relatedPages() ) {
                    final Map< String, Object > rEntry = ResultShaper.orderedMap();
                    rEntry.put( "name", r.name() );
                    rEntry.put( "reason", r.reason() );
                    relatedOut.add( rEntry );
                }
                entry.put( "relatedPages", relatedOut );
            }

            if ( p.lastModified() != null ) {
                entry.put( "lastModified", p.lastModified().toInstant().toString() );
            }
            if ( p.author() != null ) entry.put( "author", p.author() );
            shaped.add( entry );
        }
```

Add imports if not already present:

```java
import com.wikantik.api.knowledge.RetrievedChunk;
import java.util.ArrayList;
```

Check existing imports — `Map`, `List`, `RetrievedPage`, `ResultShaper` are already imported.

- [ ] **Step 4: Run tests to verify pass**

```bash
mvn -pl wikantik-tools -am -q test -Dtest=SearchWikiToolTest
```

Expected: all tests pass (the existing ones and the 3 new ones).

- [ ] **Step 5: Commit**

```bash
git add wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java \
        wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java
git commit -m "feat(tools): search_wiki emits contributingChunks + relatedPages arrays"
```

---

## Task 2: Update OpenAPI schema

**Files:**
- Modify: `wikantik-tools/src/main/java/com/wikantik/tools/OpenApiDocument.java`
- Modify: `wikantik-tools/src/test/java/com/wikantik/tools/OpenApiDocumentTest.java` (if schema assertions exist)

- [ ] **Step 1: Baseline existing tests**

```bash
cd /home/jakefear/source/jspwiki && mvn -pl wikantik-tools -am -q test -Dtest=OpenApiDocumentTest -Dsurefire.failIfNoSpecifiedTests=false
```

Confirm the test runs and passes today. Take note of what it asserts about the `SearchResult` schema.

- [ ] **Step 2: Add `ContributingChunk` and `RelatedPageHint` schemas + wire into SearchResult**

Open `wikantik-tools/src/main/java/com/wikantik/tools/OpenApiDocument.java`. Find the `schemas.put( "SearchResult", ... )` block (around line 99). Before it, add two new schemas:

```java
        schemas.put( "ContributingChunk", Map.of(
                "type", "object",
                "description", "One chunk of wiki content that contributed to the page's score",
                "properties", orderedProps(
                        Map.entry( "headingPath", Map.of(
                                "type", "array",
                                "items", Map.of( "type", "string" ),
                                "description", "Section breadcrumb (top to leaf)" ) ),
                        prop( "text", "string", "Chunk body text" ),
                        prop( "chunkScore", "number", "Retriever-specific chunk score; treat as ordinal" ) ) ) );

        schemas.put( "RelatedPageHint", Map.of(
                "type", "object",
                "description", "A page connected via knowledge-graph mention co-occurrence",
                "properties", orderedProps(
                        prop( "name", "string", "Wiki page name" ),
                        prop( "reason", "string", "Short human-readable explanation of the link" ) ) ) );
```

Then update the `SearchResult` schema to include `contributingChunks` and `relatedPages` as arrays referencing the new types. Replace the existing `SearchResult` block with:

```java
        schemas.put( "SearchResult", Map.of(
                "type", "object",
                "properties", orderedProps(
                        prop( "name", "string", "Wiki page name that matched the query" ),
                        prop( "url", "string", "Absolute URL to the page (use verbatim in citations)" ),
                        prop( "score", "number", "Relevance score; higher is better" ),
                        prop( "summary", "string", "Page summary from frontmatter, when present" ),
                        prop( "tags", "array", "Page tags from frontmatter, when present" ),
                        prop( "cluster", "string", "Knowledge-graph cluster label, when present" ),
                        Map.entry( "contributingChunks", Map.of(
                                "type", "array",
                                "items", Map.of( "$ref", "#/components/schemas/ContributingChunk" ),
                                "description", "Top chunks that drove this page's rank, when available" ) ),
                        Map.entry( "relatedPages", Map.of(
                                "type", "array",
                                "items", Map.of( "$ref", "#/components/schemas/RelatedPageHint" ),
                                "description", "Pages linked via KG-mention co-occurrence" ) ),
                        prop( "snippet", "string", "Leading excerpt from the top contributing chunk (legacy field — use contributingChunks for richer context)" ),
                        prop( "lastModified", "string", "ISO-8601 timestamp of the last edit" ),
                        prop( "author", "string", "Last author login, when recorded" ) ) ) );
```

**Note:** `orderedProps()` and `prop()` are helper methods already in the file. `Map.entry()` works alongside them because the helper accepts `Map.Entry` items — verify by reading the helper signature before making the change.

If `orderedProps` can only take `prop()`-shaped entries, either add a new helper or inline using `Map.entry(...)` calls directly. The existing file already uses `Map.entry` (e.g., in `searchOperation` response schema around line 169), so this pattern is proven.

- [ ] **Step 3: Update `OpenApiDocumentTest` if it enumerates `SearchResult` properties**

```bash
grep -n "SearchResult\|snippet\|contributingChunks" wikantik-tools/src/test/java/com/wikantik/tools/OpenApiDocumentTest.java
```

If any assertion enumerates the exact set of `SearchResult` property names, extend it to include `contributingChunks` and `relatedPages`. If the test only checks a few named fields (like "name" and "snippet"), add one assertion for each new field:

```java
        assertTrue( searchResultProps.containsKey( "contributingChunks" ) );
        assertTrue( searchResultProps.containsKey( "relatedPages" ) );
        assertTrue( schemas.containsKey( "ContributingChunk" ) );
        assertTrue( schemas.containsKey( "RelatedPageHint" ) );
```

Adapt to the test's variable names / assertion style.

- [ ] **Step 4: Run tool tests**

```bash
mvn -pl wikantik-tools -am -q test -Dtest=OpenApiDocumentTest,SearchWikiToolTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-tools/src/main/java/com/wikantik/tools/OpenApiDocument.java \
        wikantik-tools/src/test/java/com/wikantik/tools/OpenApiDocumentTest.java
git commit -m "feat(tools): OpenAPI schema — contributingChunks + relatedPages on SearchResult"
```

---

## Task 3: Full build + close cycle

**Files:** none created

- [ ] **Step 1: Full multi-module build**

```bash
cd /home/jakefear/source/jspwiki && mvn clean install -T 1C -DskipITs 2>&1 | grep -E "BUILD|ERROR" | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Optional — OpenWebUI sanity**

If the Tomcat server is running and OpenWebUI is configured against it, fetch the OpenAPI doc:

```bash
curl -sH "Authorization: Bearer $(grep '^api-key' test.properties 2>/dev/null | head -1 | cut -d'=' -f2)" \
    http://localhost:8080/tools/openapi.json | jq '.components.schemas.SearchResult.properties | keys'
```

Expected: includes `"contributingChunks"` and `"relatedPages"` in the keys list.

Skip this step if no server is running — the unit tests already cover the schema shape.

- [ ] **Step 3: Mark cycle 5 complete in the spec**

Edit `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`. Change cycle 5's heading to:

```
5. **Cycle 5 — tool-server upgrade. ✓**
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md
git commit -m "chore(spec): mark cycle 5 complete — tool-server response shape parity"
```

---

## Summary

At cycle 5 complete:

- `/tools/search_wiki` returns `contributingChunks: [{headingPath, text, chunkScore}]` and `relatedPages: [{name, reason}]` per result, matching MCP `retrieve_context` shape.
- `snippet` retained for backward compatibility.
- OpenAPI doc reflects the richer schema (`ContributingChunk`, `RelatedPageHint` components added).
- Tests cover the new fields, including the "omit when empty" behavior.

Breaking change: none — the added fields are additive to the JSON envelope. Consumers reading `snippet` continue to work.

Deferred:
- Cycle 6 — retire `GraphProjector`.
