# Retrieval-Aware Content Authoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enhance the `wiki-content` skill to author retrieval-optimized frontmatter/headings, backed by a server-side `retrieval_readiness` lint check and a new `list_retrieval_queries` MCP tool over the real query log.

**Architecture:** Two independent server tracks plus a skill rewrite. Track A adds a `retrieval_readiness` check to the existing `verify_pages` tool using the established `PageCheck` Strategy pattern. Track B adds a read interface over the `retrieval_query_log` table (V041) surfaced as a new read-only admin-MCP tool, wired through the existing `engine instanceof WikiEngine` idiom. The skill then documents the author→lint→live-check loop, written portably (pure MCP server tools — no REST/`/api/*`) so it migrates cleanly to `../wiki-content/`.

**Tech Stack:** Java 21, Maven, MCP (`io.modelcontextprotocol`), JDBC (PostgreSQL prod / H2 `MODE=PostgreSQL` unit tests), JUnit 5, Mockito, Cargo-launched Tomcat ITs.

## Global Constraints

- Java 21; Maven 3.9+. Build single modules with `mvn compile -pl <module> -q` / `mvn test -pl <module> -Dtest=<Class>`; one full build at the end.
- After any constructor/interface signature change run `mvn test-compile -pl <module>` (plain `compile` skips test sources).
- Never swallow exceptions with an empty catch — at least `LOG.warn()` with context.
- All new `retrieval_readiness` findings are advisory `Severity.WARNING` — `verify_pages` never blocks a save.
- Query-log **write** path stays fail-open/async; the new **read** path is a synchronous admin query that may surface errors to the caller (the tool catches + returns an MCP error, never an empty catch).
- No new DB migration — `retrieval_query_log` already exists at V041.
- Integration tests run sequentially (no `-T`), always `-fae`. Gate the final prod-code commit on `mvn clean install -Pintegration-tests -fae`.
- Stage files by exact name (never `git add -A`). End commit messages with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **Skill prose must use ONLY MCP server tools** — no REST/`/api/*`, no curl, no bash, no Claude-Code-specific mechanisms — so it is portable to Antigravity. The live bundle check uses the `assemble_bundle` MCP tool (knowledge-mcp), never `GET /api/bundle`.

---

## File Structure

**Track A — static lint (`wikantik-admin-mcp`):**
- Modify `wikantik-admin-mcp/.../mcp/tools/PageChecks.java` — add 4 `PageCheck` strategies under a "Retrieval Checks" section.
- Modify `wikantik-admin-mcp/.../mcp/tools/VerifyPagesTool.java` — `RETRIEVAL_CHECKS`, `retrievalIssues`, `checkRetrievalReadiness`, body capture, schema.
- Test `wikantik-admin-mcp/.../mcp/tools/PageChecksTest.java` (extend) and `VerifyPagesToolTest.java` (extend).

**Track B — query-log read tool:**
- Create `wikantik-api/.../api/querylog/QueryLogReader.java`, `AggregatedQuery.java`, `QueryLogQuery.java`.
- Create `wikantik-main/.../knowledge/querylog/JdbcQueryLogReader.java`; modify `QueryLogWiring.java`.
- Create `wikantik-admin-mcp/.../mcp/tools/ListRetrievalQueriesTool.java`.
- Modify `wikantik-main/.../WikiEngine.java` (accessor + startup wiring) and `wikantik-admin-mcp/.../mcp/McpToolRegistry.java` (registration).
- Tests: `JdbcQueryLogReaderTest.java` (H2), `ListRetrievalQueriesToolTest.java`, and a Cargo IT under `wikantik-it-tests/wikantik-selenide-tests/.../mcp/`.

**Skill + docs:**
- Modify `.claude/skills/wiki-content/SKILL.md`.
- Modify `CLAUDE.md`, `README.md`, `CHANGELOG.md`.

---

## Track A — Static lint (`retrieval_readiness`)

### Task 1: Retrieval-readiness `PageCheck` strategies

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/PageChecks.java`
- Test: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/PageChecksTest.java`

**Interfaces:**
- Consumes: `PageCheck`, `PageCheckContext(pageName, metadata, body, page, pageManager)`, `PageCheckResult(pageName, Severity, category, issue, detail)`, `Severity.WARNING`.
- Produces: `PageChecks.SummarySpecificityCheck`, `PageChecks.HeadingQualityCheck`, `PageChecks.ClusterPresentCheck`, `PageChecks.TitleSpecificityCheck` — all no-arg constructors, category `"retrieval"`.

- [ ] **Step 1: Write the failing tests**

Add to `PageChecksTest.java` (imports: `com.wikantik.api.core.Page`, `java.util.*`, `static org.junit.jupiter.api.Assertions.*`). A helper builds a context:

```java
private static PageCheckContext ctx( String name, Map<String,Object> meta, String body ) {
    return new PageCheckContext( name, meta, body == null ? "" : body, null, null );
}

@Test
void summarySpecificity_flagsMissingRestatementAndThinSummaries() {
    var c = new PageChecks.SummarySpecificityCheck();
    assertEquals( "summary_missing_for_retrieval",
        c.check( ctx( "P", Map.of(), "" ) ).get( 0 ).issue() );
    assertEquals( "summary_restates_title",
        c.check( ctx( "P", Map.of( "title", "Hybrid Retrieval", "summary", "hybrid retrieval" ), "" ) )
         .get( 0 ).issue() );
    assertEquals( "summary_low_specificity",
        c.check( ctx( "P", Map.of( "title", "T", "summary", "A short blurb here" ), "" ) )
         .get( 0 ).issue() );
    assertTrue( c.check( ctx( "P", Map.of( "title", "T",
        "summary", "BM25 plus dense vector retrieval fused with reciprocal rank fusion for section recall" ) , "" ) ).isEmpty() );
}

@Test
void headingQuality_flagsGenericHeadingsAndNoHeadings() {
    var c = new PageChecks.HeadingQualityCheck();
    assertEquals( "no_headings",
        c.check( ctx( "P", Map.of(), "Just a paragraph, no headings at all." ) ).get( 0 ).issue() );
    var generic = c.check( ctx( "P", Map.of(), "## Overview\ntext\n## Tuning The Reranker\nmore" ) );
    assertEquals( 1, generic.size() );
    assertEquals( "generic_heading", generic.get( 0 ).issue() );
    assertTrue( c.check( ctx( "P", Map.of(),
        "## Tuning The Reranker\ntext\n### Cross-Encoder Latency\nmore" ) ).isEmpty() );
}

@Test
void headingQuality_ignoresHeadingsInsideCodeFences() {
    var c = new PageChecks.HeadingQualityCheck();
    assertTrue( c.check( ctx( "P", Map.of(),
        "## Real Section\n```\n# Overview\n```\nbody" ) ).isEmpty() );
}

@Test
void clusterPresent_flagsMissingAndNonKebab() {
    var c = new PageChecks.ClusterPresentCheck();
    assertEquals( "cluster_missing_for_retrieval",
        c.check( ctx( "P", Map.of(), "" ) ).get( 0 ).issue() );
    assertEquals( "cluster_not_kebab",
        c.check( ctx( "P", Map.of( "cluster", "Hybrid Retrieval" ), "" ) ).get( 0 ).issue() );
    assertTrue( c.check( ctx( "P", Map.of( "cluster", "hybrid-retrieval/eu" ), "" ) ).isEmpty() );
}

@Test
void titleSpecificity_flagsMissingAndSlugEcho() {
    var c = new PageChecks.TitleSpecificityCheck();
    assertEquals( "title_missing",
        c.check( ctx( "HybridRetrieval", Map.of(), "" ) ).get( 0 ).issue() );
    assertEquals( "title_equals_slug",
        c.check( ctx( "HybridRetrieval", Map.of( "title", "HybridRetrieval" ), "" ) ).get( 0 ).issue() );
    assertTrue( c.check( ctx( "HybridRetrieval", Map.of( "title", "Hybrid Retrieval" ), "" ) ).isEmpty() );
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -pl wikantik-admin-mcp -Dtest=PageChecksTest -q`
Expected: FAIL — `SummarySpecificityCheck` etc. do not exist (compile error).

- [ ] **Step 3: Implement the four checks**

Append before the final closing brace of `PageChecks.java` (after the `StalenessCheck` block), inside the class:

```java
    // -----------------------------------------------------------------------
    //  Retrieval Checks — the four levers prepended into chunk embeddings
    //  (EmbeddingTextBuilder.forDocument: Page:title | Cluster:cluster |
    //  Section:heading + Summary:summary). All advisory WARNING / category "retrieval".
    // -----------------------------------------------------------------------

    private static final java.util.Set< String > GENERIC_HEADINGS = java.util.Set.of(
            "overview", "introduction", "intro", "details", "notes", "misc",
            "miscellaneous", "summary", "background", "more", "other", "info" );

    private static final java.util.regex.Pattern KEBAB =
            java.util.regex.Pattern.compile( "^[a-z0-9]+(-[a-z0-9]+)*(/[a-z0-9]+(-[a-z0-9]+)*)*$" );

    /** Summary is prepended to every chunk embedding — flag missing, title-restatement, or thin summaries. */
    public static class SummarySpecificityCheck implements PageCheck {
        @Override
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            final Object val = ctx.metadata().get( "summary" );
            final String summary = val != null ? val.toString().strip() : null;
            if ( summary == null || summary.isEmpty() ) {
                return List.of( new PageCheckResult( ctx.pageName(), PageCheckResult.Severity.WARNING,
                        "retrieval", "summary_missing_for_retrieval",
                        "No summary — chunk embeddings lack the page-level disambiguation context that lifted section recall ~0.60→0.74" ) );
            }
            final Object titleVal = ctx.metadata().get( "title" );
            final String title = titleVal != null ? titleVal.toString().strip() : ctx.pageName();
            if ( summary.equalsIgnoreCase( title ) ) {
                return List.of( new PageCheckResult( ctx.pageName(), PageCheckResult.Severity.WARNING,
                        "retrieval", "summary_restates_title",
                        "Summary merely restates the title — add concrete concepts/vocabulary so chunk embeddings disambiguate" ) );
            }
            final int words = summary.split( "\\s+" ).length;
            if ( words < 6 ) {
                return List.of( new PageCheckResult( ctx.pageName(), PageCheckResult.Severity.WARNING,
                        "retrieval", "summary_low_specificity",
                        "Summary is only " + words + " words — too thin to disambiguate chunks; name the page's key concepts/vocabulary" ) );
            }
            return List.of();
        }
    }

    /** Body headings become each chunk's heading_path in the embedding — flag generic or absent headings. */
    public static class HeadingQualityCheck implements PageCheck {
        @Override
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            final List< String > headings = extractHeadings( ctx.body() );
            if ( headings.isEmpty() ) {
                return List.of( new PageCheckResult( ctx.pageName(), PageCheckResult.Severity.WARNING,
                        "retrieval", "no_headings",
                        "Page has no section headings — the whole body shares one chunk context; add descriptive H2/H3 sections" ) );
            }
            final List< PageCheckResult > out = new ArrayList<>();
            final java.util.Set< String > seen = new java.util.HashSet<>();
            for ( final String h : headings ) {
                final String norm = h.toLowerCase( java.util.Locale.ROOT ).strip();
                if ( GENERIC_HEADINGS.contains( norm ) && seen.add( norm ) ) {
                    out.add( new PageCheckResult( ctx.pageName(), PageCheckResult.Severity.WARNING,
                            "retrieval", "generic_heading",
                            "Heading '" + h.strip() + "' is generic — chunks under it carry weak section context; make it topic-specific" ) );
                }
            }
            return out;
        }

        /** ATX headings (#..######), skipping fenced code blocks (``` or ~~~). */
        private static List< String > extractHeadings( final String body ) {
            final List< String > out = new ArrayList<>();
            if ( body == null || body.isBlank() ) {
                return out;
            }
            boolean inFence = false;
            for ( final String raw : body.split( "\n", -1 ) ) {
                final String line = raw.strip();
                if ( line.startsWith( "```" ) || line.startsWith( "~~~" ) ) {
                    inFence = !inFence;
                    continue;
                }
                if ( inFence ) {
                    continue;
                }
                final java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile( "^#{1,6}\\s+(.+?)\\s*#*$" ).matcher( line );
                if ( m.matches() ) {
                    out.add( m.group( 1 ) );
                }
            }
            return out;
        }
    }

    /** Cluster is prepended to chunk embeddings (domain disambiguation) — flag missing or non-kebab. */
    public static class ClusterPresentCheck implements PageCheck {
        @Override
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            final Object val = ctx.metadata().get( "cluster" );
            final String cluster = val != null ? val.toString().strip() : null;
            if ( cluster == null || cluster.isEmpty() ) {
                return List.of( new PageCheckResult( ctx.pageName(), PageCheckResult.Severity.WARNING,
                        "retrieval", "cluster_missing_for_retrieval",
                        "No cluster — chunk embeddings lack the domain prefix that disambiguates cross-topic queries" ) );
            }
            if ( !KEBAB.matcher( cluster ).matches() ) {
                return List.of( new PageCheckResult( ctx.pageName(), PageCheckResult.Severity.WARNING,
                        "retrieval", "cluster_not_kebab",
                        "Cluster '" + cluster + "' is not kebab-case — use lowercase-hyphenated slugs (e.g. hybrid-retrieval)" ) );
            }
            return List.of();
        }
    }

    /** Title is prepended to chunk embeddings — flag missing or a bare slug echo. */
    public static class TitleSpecificityCheck implements PageCheck {
        @Override
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            final Object val = ctx.metadata().get( "title" );
            final String title = val != null ? val.toString().strip() : null;
            if ( title == null || title.isEmpty() ) {
                return List.of( new PageCheckResult( ctx.pageName(), PageCheckResult.Severity.WARNING,
                        "retrieval", "title_missing",
                        "No title — the embedding falls back to the slug; add a natural-language title" ) );
            }
            if ( title.equals( ctx.pageName() ) ) {
                return List.of( new PageCheckResult( ctx.pageName(), PageCheckResult.Severity.WARNING,
                        "retrieval", "title_equals_slug",
                        "Title is just the page slug — a natural-language title improves both the embedding and the <title> tag" ) );
            }
            return List.of();
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -pl wikantik-admin-mcp -Dtest=PageChecksTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/PageChecks.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/PageChecksTest.java
git commit -m "feat(verify): retrieval-readiness PageCheck strategies (summary/heading/cluster/title)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Wire `retrieval_readiness` into `verify_pages`

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/VerifyPagesTool.java`
- Test: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/VerifyPagesToolTest.java`

**Interfaces:**
- Consumes: the four checks from Task 1; `ParsedPage.body()`.
- Produces: `verify_pages` accepts `"retrieval_readiness"` in `checks`; per-page `retrievalWarnings` list; summary `retrievalIssues` list.

- [ ] **Step 1: Write the failing test**

Add to `VerifyPagesToolTest.java` (follow the file's existing mock setup for `PageManager`/`ReferenceManager`; a page whose pure text has thin frontmatter + a generic heading):

```java
@Test
void retrievalReadiness_reportsWeakFrontmatterAndHeadings() {
    final String raw = "---\ntitle: HybridRetrieval\ncluster: Hybrid Retrieval\nsummary: hybrid retrieval\n---\n## Overview\nbody";
    when( pageManager.getPage( "HybridRetrieval" ) ).thenReturn( existingPage( "HybridRetrieval", 1 ) );
    when( pageManager.getPureText( eq( "HybridRetrieval" ), anyInt() ) ).thenReturn( raw );

    final var result = tool.execute( Map.of(
            "slugs", List.of( "HybridRetrieval" ),
            "checks", List.of( "retrieval_readiness" ) ) );

    final String json = textOf( result );          // existing helper that extracts the tool's text payload
    assertTrue( json.contains( "retrievalIssues" ) );
    assertTrue( json.contains( "summary_restates_title" ) );
    assertTrue( json.contains( "cluster_not_kebab" ) );
    assertTrue( json.contains( "generic_heading" ) );
    assertTrue( json.contains( "title_equals_slug" ) );
}
```

> Reuse the test class's existing helpers (`existingPage(...)`, `textOf(...)` or equivalent). If the class lacks a text extractor, parse `result.content().get(0)` as `McpSchema.TextContent` and read `.text()`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -pl wikantik-admin-mcp -Dtest=VerifyPagesToolTest#retrievalReadiness_reportsWeakFrontmatterAndHeadings -q`
Expected: FAIL — `retrievalIssues` absent (check not wired).

- [ ] **Step 3: Implement the wiring**

In `VerifyPagesTool.java`:

(a) After the `SEO_CHECKS` field add:

```java
    /** Composed retrieval-readiness checks — see PageChecks "Retrieval Checks". */
    private static final List< PageCheck > RETRIEVAL_CHECKS = List.of(
            new PageChecks.SummarySpecificityCheck(),
            new PageChecks.HeadingQualityCheck(),
            new PageChecks.ClusterPresentCheck(),
            new PageChecks.TitleSpecificityCheck()
    );
```

(b) In `definition()`, change the `checks` description and add to the output example. Replace the `checks` description string with:

```java
                        "Valid values: existence, broken_links, backlinks, outbound_links, metadata_completeness, seo_readiness, retrieval_readiness",
```

and add `"retrievalWarnings", List.of()` to the first example page map and `"retrievalIssues", List.of( "AgentMemory: No summary — chunk embeddings lack ..." )` to the summary example map.

(c) In `execute()`, add the aggregate list beside `seoIssues`:

```java
        final List< String > retrievalIssues = new ArrayList<>();
```

(d) Replace the metadata-parse block so it also captures the body when retrieval is active:

```java
            final boolean needsMetadata = activeChecks.contains( "metadata_completeness" )
                    || activeChecks.contains( "seo_readiness" )
                    || activeChecks.contains( "retrieval_readiness" );
            Map< String, Object > metadata = Map.of();
            String body = "";
            if ( needsMetadata ) {
                final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
                final ParsedPage parsed = FrontmatterParser.parse( rawText );
                metadata = parsed.metadata();
                body = parsed.body();
            }
```

(e) After the `checkSeoReadiness( ... )` call add:

```java
            checkRetrievalReadiness( pageName, metadata, body, page, entry, activeChecks, retrievalIssues );
```

(f) In the summary-assembly block, after the `seo_readiness` clause add:

```java
        if ( activeChecks.contains( "retrieval_readiness" ) ) {
            summary.put( "retrievalIssues", retrievalIssues );
        }
```

(g) Add the method (mirrors `checkSeoReadiness`, but passes the real `body`):

```java
    /**
     * Check retrieval readiness using composed {@link PageCheck} strategies. Unlike SEO checks,
     * these need the body (heading structure feeds each chunk's embedding), so it is passed in.
     */
    void checkRetrievalReadiness( final String pageName, final Map< String, Object > metadata,
                                  final String body, final Page page, final Map< String, Object > entry,
                                  final Set< String > activeChecks, final List< String > retrievalIssues ) {
        if ( !activeChecks.contains( "retrieval_readiness" ) ) {
            return;
        }
        final PageCheckContext checkCtx = new PageCheckContext(
                pageName, metadata, body == null ? "" : body, page, pageManager );
        final List< String > warnings = new ArrayList<>();
        for ( final PageCheck check : RETRIEVAL_CHECKS ) {
            for ( final PageCheckResult result : check.check( checkCtx ) ) {
                warnings.add( result.detail() );
            }
        }
        entry.put( "retrievalWarnings", warnings );
        if ( !warnings.isEmpty() ) {
            retrievalIssues.add( pageName + ": " + String.join( "; ", warnings ) );
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -pl wikantik-admin-mcp -Dtest=VerifyPagesToolTest -q`
Expected: PASS (the whole class, to confirm no regression).

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/VerifyPagesTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/VerifyPagesToolTest.java
git commit -m "feat(verify): add retrieval_readiness check to verify_pages

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Track B — Query-log read tool

### Task 3: `QueryLogReader` interface + value types (`wikantik-api`)

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/querylog/QueryLogReader.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/querylog/AggregatedQuery.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/querylog/QueryLogQuery.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/querylog/QueryLogQueryTest.java`

**Interfaces:**
- Produces:
  - `QueryLogReader.topQueries(QueryLogQuery) -> List<AggregatedQuery>`
  - `AggregatedQuery(String queryText, long occurrences, double avgResultCount, long zeroResultCount, Instant lastSeen)`
  - `QueryLogQuery(Instant since, ActorType actor, SourceSurface surface, Integer maxAvgResultCount, int minOccurrences, int limit)` — `actor`/`surface`/`maxAvgResultCount` nullable; compact ctor requires `since`, floors `minOccurrences`≥1 and `limit`≥1 (default 50).

- [ ] **Step 1: Write the failing test**

`QueryLogQueryTest.java` (use the ASF license header from any sibling file):

```java
package com.wikantik.api.querylog;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class QueryLogQueryTest {
    @Test
    void floorsDefaultsAndRequiresSince() {
        final var q = new QueryLogQuery( Instant.EPOCH, null, null, null, 0, 0 );
        assertEquals( 1, q.minOccurrences() );
        assertEquals( 50, q.limit() );
        assertThrows( IllegalArgumentException.class,
                () -> new QueryLogQuery( null, null, null, null, 1, 10 ) );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-api -Dtest=QueryLogQueryTest -q`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Create the three types** (each with the ASF header)

`QueryLogReader.java`:

```java
package com.wikantik.api.querylog;

import java.util.List;

/**
 * Read side of the retrieval query log — aggregates real traffic for grounding content work.
 * Distinct from {@link QueryLogService} (write side) so the fail-open write contract stays clean
 * (Interface Segregation). Implementations run synchronously and may throw on a read failure.
 */
public interface QueryLogReader {

    /** Top queries matching the filter, grouped by exact query text, most-frequent first. */
    List< AggregatedQuery > topQueries( QueryLogQuery query );
}
```

`AggregatedQuery.java`:

```java
package com.wikantik.api.querylog;

import java.time.Instant;

/**
 * One aggregated query-log row: a distinct query string and its traffic stats over the window.
 * {@code avgResultCount} and {@code zeroResultCount} are the under-served signal — low averages /
 * high zero counts mark real queries the corpus answers poorly.
 */
public record AggregatedQuery(
        String queryText,
        long occurrences,
        double avgResultCount,
        long zeroResultCount,
        Instant lastSeen
) {}
```

`QueryLogQuery.java`:

```java
package com.wikantik.api.querylog;

import java.time.Instant;

/**
 * Filter for {@link QueryLogReader#topQueries}. Nullable {@code actor}/{@code surface} mean "all";
 * nullable {@code maxAvgResultCount} disables the miss filter. {@code since} is required.
 */
public record QueryLogQuery(
        Instant since,
        ActorType actor,
        SourceSurface surface,
        Integer maxAvgResultCount,
        int minOccurrences,
        int limit
) {
    public QueryLogQuery {
        if ( since == null ) {
            throw new IllegalArgumentException( "since must not be null" );
        }
        if ( minOccurrences < 1 ) {
            minOccurrences = 1;
        }
        if ( limit < 1 ) {
            limit = 50;
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl wikantik-api -Dtest=QueryLogQueryTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/querylog/QueryLogReader.java \
        wikantik-api/src/main/java/com/wikantik/api/querylog/AggregatedQuery.java \
        wikantik-api/src/main/java/com/wikantik/api/querylog/QueryLogQuery.java \
        wikantik-api/src/test/java/com/wikantik/api/querylog/QueryLogQueryTest.java
git commit -m "feat(querylog): read interface + AggregatedQuery/QueryLogQuery types

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: `JdbcQueryLogReader` + wiring helper (`wikantik-main`)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/querylog/JdbcQueryLogReader.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/querylog/QueryLogWiring.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/querylog/JdbcQueryLogReaderTest.java`

**Interfaces:**
- Consumes: `QueryLogReader`, `QueryLogQuery`, `AggregatedQuery`, `ActorType.wire()`, `SourceSurface.wire()`.
- Produces: `new JdbcQueryLogReader(DataSource)`; `QueryLogWiring.buildReader(DataSource) -> QueryLogReader`.

> **Cross-DB note:** the miss filter uses `SUM(result_count) <= M * COUNT(result_count)` (algebraic form of `AVG <= M`) to stay integer-only and portable across H2 + Postgres. Real-Postgres coverage of this SQL comes from the Cargo IT in Task 7; this task's unit test runs H2 in `MODE=PostgreSQL`.

- [ ] **Step 1: Write the failing test**

`JdbcQueryLogReaderTest.java` (mirror `JdbcQueryLogServiceTest`'s H2 setup — copy the `@BeforeEach` table-create verbatim from that file):

```java
package com.wikantik.knowledge.querylog;

import com.wikantik.api.querylog.AggregatedQuery;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogQuery;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbcQueryLogReaderTest {

    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:qlogread;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE IF EXISTS retrieval_query_log" );
            s.executeUpdate( """
                CREATE TABLE retrieval_query_log (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    query_text TEXT NOT NULL,
                    actor_type VARCHAR(16) NOT NULL,
                    source_surface VARCHAR(32) NOT NULL,
                    result_count INTEGER,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""" );
        }
        insert( "how do I deploy locally", "agent", "api_bundle", 0 );
        insert( "how do I deploy locally", "agent", "api_bundle", 0 );
        insert( "how do I deploy locally", "human", "api_search", 2 );
        insert( "embedding model choice", "human", "api_search", 8 );
    }

    private void insert( String q, String actor, String surface, Integer count ) throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO retrieval_query_log (query_text, actor_type, source_surface, result_count) VALUES (?,?,?,?)" ) ) {
            ps.setString( 1, q ); ps.setString( 2, actor ); ps.setString( 3, surface );
            if ( count == null ) ps.setNull( 4, java.sql.Types.INTEGER ); else ps.setInt( 4, count );
            ps.executeUpdate();
        }
    }

    @Test
    void aggregatesGroupsAndOrdersByFrequency() {
        final var rows = new JdbcQueryLogReader( ds ).topQueries(
                new QueryLogQuery( Instant.EPOCH, null, null, null, 1, 50 ) );
        assertEquals( 2, rows.size() );
        final AggregatedQuery top = rows.get( 0 );
        assertEquals( "how do I deploy locally", top.queryText() );
        assertEquals( 3, top.occurrences() );
        assertEquals( 2, top.zeroResultCount() );
        assertEquals( ( 0.0 + 0 + 2 ) / 3, top.avgResultCount(), 1e-9 );
        assertNotNull( top.lastSeen() );
    }

    @Test
    void missFilterKeepsOnlyLowAverageQueries() {
        final var rows = new JdbcQueryLogReader( ds ).topQueries(
                new QueryLogQuery( Instant.EPOCH, null, null, 1, 1, 50 ) );
        assertEquals( 1, rows.size() );
        assertEquals( "how do I deploy locally", rows.get( 0 ).queryText() );
    }

    @Test
    void actorFilterRestrictsRows() {
        final var rows = new JdbcQueryLogReader( ds ).topQueries(
                new QueryLogQuery( Instant.EPOCH, ActorType.HUMAN, null, null, 1, 50 ) );
        assertEquals( 2, rows.size() );      // one human deploy row + one human embedding row
        assertTrue( rows.stream().allMatch( r -> r.occurrences() == 1 ) );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=JdbcQueryLogReaderTest -q`
Expected: FAIL — `JdbcQueryLogReader` does not exist.

- [ ] **Step 3: Implement the reader**

`JdbcQueryLogReader.java` (ASF header):

```java
package com.wikantik.knowledge.querylog;

import com.wikantik.api.querylog.AggregatedQuery;
import com.wikantik.api.querylog.QueryLogQuery;
import com.wikantik.api.querylog.QueryLogReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC {@link QueryLogReader}. Aggregates {@code retrieval_query_log} by query text over a window.
 * The miss filter is written as {@code SUM(result_count) <= M * COUNT(result_count)} (the
 * integer-only algebraic form of {@code AVG(result_count) <= M}) so it is portable across H2 and
 * PostgreSQL. A read failure is logged and rethrown — the calling MCP tool surfaces it to the agent.
 */
public final class JdbcQueryLogReader implements QueryLogReader {

    private static final Logger LOG = LogManager.getLogger( JdbcQueryLogReader.class );

    private final DataSource dataSource;

    public JdbcQueryLogReader( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    @Override
    public List< AggregatedQuery > topQueries( final QueryLogQuery q ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT query_text, COUNT(*) AS occurrences, "
              + "SUM(result_count) AS sum_results, COUNT(result_count) AS counted_results, "
              + "SUM(CASE WHEN result_count = 0 THEN 1 ELSE 0 END) AS zero_results, "
              + "MAX(created_at) AS last_seen "
              + "FROM retrieval_query_log WHERE created_at >= ?" );
        final List< Object > params = new ArrayList<>();
        params.add( Timestamp.from( q.since() ) );
        if ( q.actor() != null ) {
            sql.append( " AND actor_type = ?" );
            params.add( q.actor().wire() );
        }
        if ( q.surface() != null ) {
            sql.append( " AND source_surface = ?" );
            params.add( q.surface().wire() );
        }
        sql.append( " GROUP BY query_text HAVING COUNT(*) >= ?" );
        params.add( q.minOccurrences() );
        if ( q.maxAvgResultCount() != null ) {
            sql.append( " AND SUM(result_count) <= ? * COUNT(result_count)" );
            params.add( q.maxAvgResultCount() );
        }
        sql.append( " ORDER BY occurrences DESC, last_seen DESC LIMIT ?" );
        params.add( q.limit() );

        final List< AggregatedQuery > out = new ArrayList<>();
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final long counted = rs.getLong( "counted_results" );
                    final long sum = rs.getLong( "sum_results" );
                    final double avg = counted > 0 ? ( double ) sum / counted : 0.0;
                    final Timestamp ts = rs.getTimestamp( "last_seen" );
                    out.add( new AggregatedQuery(
                            rs.getString( "query_text" ),
                            rs.getLong( "occurrences" ),
                            avg,
                            rs.getLong( "zero_results" ),
                            ts != null ? ts.toInstant() : null ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Retrieval query-log read failed: {}", e.getMessage() );
            throw new IllegalStateException( "Failed to read retrieval query log", e );
        }
        return out;
    }
}
```

- [ ] **Step 4: Add the wiring helper**

In `QueryLogWiring.java` add the import `import com.wikantik.api.querylog.QueryLogReader;` and the method:

```java
    /** A read-only {@link QueryLogReader} over {@code dataSource} (always available — no enable flag). */
    public static QueryLogReader buildReader( final DataSource dataSource ) {
        return new JdbcQueryLogReader( dataSource );
    }
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=JdbcQueryLogReaderTest -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/querylog/JdbcQueryLogReader.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/querylog/QueryLogWiring.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/querylog/JdbcQueryLogReaderTest.java
git commit -m "feat(querylog): JdbcQueryLogReader (portable aggregation) + wiring helper

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: `ListRetrievalQueriesTool` (`wikantik-admin-mcp`)

**Files:**
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListRetrievalQueriesTool.java`
- Test: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ListRetrievalQueriesToolTest.java`

**Interfaces:**
- Consumes: `QueryLogReader`, `QueryLogQuery`, `AggregatedQuery`, `ActorType`, `SourceSurface`, `McpTool`, `McpToolUtils.{SHARED_GSON,jsonResult,errorResult}`.
- Produces: tool name `"list_retrieval_queries"`; params `since_days`(int, default 30), `actor`(string, optional), `surface`(string, optional), `max_avg_results`(int, optional), `min_occurrences`(int, default 1), `limit`(int, default 50).

- [ ] **Step 1: Write the failing test**

`ListRetrievalQueriesToolTest.java`:

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.querylog.AggregatedQuery;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogQuery;
import com.wikantik.api.querylog.QueryLogReader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ListRetrievalQueriesToolTest {

    @Test
    void mapsArgumentsToFilterAndRendersRows() {
        final QueryLogReader reader = mock( QueryLogReader.class );
        when( reader.topQueries( any() ) ).thenReturn( List.of(
                new AggregatedQuery( "how do I deploy locally", 3, 0.67, 2, Instant.EPOCH ) ) );

        final var tool = new ListRetrievalQueriesTool( reader );
        final var result = tool.execute( Map.of(
                "since_days", 7, "actor", "agent", "max_avg_results", 1, "limit", 25 ) );

        final ArgumentCaptor< QueryLogQuery > cap = ArgumentCaptor.forClass( QueryLogQuery.class );
        verify( reader ).topQueries( cap.capture() );
        assertEquals( ActorType.AGENT, cap.getValue().actor() );
        assertEquals( 1, cap.getValue().maxAvgResultCount() );
        assertEquals( 25, cap.getValue().limit() );
        assertFalse( result.isError() != null && result.isError() );

        final String json = ( ( io.modelcontextprotocol.spec.McpSchema.TextContent )
                result.content().get( 0 ) ).text();
        assertTrue( json.contains( "how do I deploy locally" ) );
        assertTrue( json.contains( "zeroResultCount" ) );
    }

    @Test
    void rejectsUnknownSurface() {
        final QueryLogReader reader = mock( QueryLogReader.class );
        final var result = new ListRetrievalQueriesTool( reader )
                .execute( Map.of( "surface", "not_a_surface" ) );
        assertTrue( result.isError() );
        verifyNoInteractions( reader );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-admin-mcp -Dtest=ListRetrievalQueriesToolTest -q`
Expected: FAIL — tool does not exist.

- [ ] **Step 3: Implement the tool** (ASF header)

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.AggregatedQuery;
import com.wikantik.api.querylog.QueryLogQuery;
import com.wikantik.api.querylog.QueryLogReader;
import com.wikantik.api.querylog.SourceSurface;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only MCP tool over the retrieval query log. Returns real queries (deduped, ranked by
 * frequency) so content work can target what people actually search for; {@code max_avg_results}
 * surfaces under-served queries (low/zero result counts). The agent judges true "misses" by
 * running candidates through the assemble_bundle MCP tool — this tool intentionally bakes in no relevance judgment.
 */
public class ListRetrievalQueriesTool implements McpTool {

    public static final String TOOL_NAME = "list_retrieval_queries";
    private static final Logger LOG = LogManager.getLogger( ListRetrievalQueriesTool.class );

    private final QueryLogReader reader;

    public ListRetrievalQueriesTool( final QueryLogReader reader ) {
        this.reader = reader;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "since_days", Map.of( "type", "integer",
                "description", "Look back this many days (default 30)" ) );
        properties.put( "actor", Map.of( "type", "string",
                "description", "Filter by actor: human | agent | unknown (default: all)" ) );
        properties.put( "surface", Map.of( "type", "string",
                "description", "Filter by surface: api_bundle | api_search | mcp_assemble_bundle | tools_search_wiki (default: all)" ) );
        properties.put( "max_avg_results", Map.of( "type", "integer",
                "description", "Only return queries whose average result count is <= this (find under-served queries)" ) );
        properties.put( "min_occurrences", Map.of( "type", "integer",
                "description", "Only return queries seen at least this many times (default 1)" ) );
        properties.put( "limit", Map.of( "type", "integer",
                "description", "Max distinct queries to return (default 50)" ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of( "queries", List.of(
                Map.of( "query", "how do I deploy locally", "occurrences", 3,
                        "avgResultCount", 0.67, "zeroResultCount", 2, "lastSeen", "2026-06-19T10:00:00Z" ) ) ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List real retrieval queries from the query log, deduped and ranked by frequency. "
                        + "Use max_avg_results to find under-served queries the corpus answers poorly, then "
                        + "run candidates through the assemble_bundle MCP tool to confirm the right section surfaces." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final int sinceDays = intArg( arguments, "since_days", 30 );
        final int minOccurrences = intArg( arguments, "min_occurrences", 1 );
        final int limit = intArg( arguments, "limit", 50 );
        final Integer maxAvg = arguments.get( "max_avg_results" ) == null
                ? null : intArg( arguments, "max_avg_results", 0 );

        ActorType actor = null;
        final Object actorArg = arguments.get( "actor" );
        if ( actorArg != null && !actorArg.toString().isBlank() ) {
            actor = ActorType.fromWire( actorArg.toString().strip() );
            if ( actor == ActorType.UNKNOWN && !"unknown".equalsIgnoreCase( actorArg.toString().strip() ) ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "Unknown actor: " + actorArg,
                        "Use one of: human, agent, unknown." );
            }
        }

        SourceSurface surface = null;
        final Object surfaceArg = arguments.get( "surface" );
        if ( surfaceArg != null && !surfaceArg.toString().isBlank() ) {
            try {
                surface = SourceSurface.fromWire( surfaceArg.toString().strip() );
            } catch ( final IllegalArgumentException e ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "Unknown surface: " + surfaceArg,
                        "Use one of: api_bundle, api_search, mcp_assemble_bundle, tools_search_wiki." );
            }
        }

        final QueryLogQuery query = new QueryLogQuery(
                Instant.now().minus( sinceDays, ChronoUnit.DAYS ),
                actor, surface, maxAvg, minOccurrences, limit );

        final List< AggregatedQuery > rows;
        try {
            rows = reader.topQueries( query );
        } catch ( final RuntimeException e ) {
            LOG.warn( "list_retrieval_queries read failed: {}", e.getMessage() );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Failed to read the retrieval query log",
                    "Check that the database is reachable and V041 has been applied." );
        }

        final List< Map< String, Object > > rendered = new ArrayList<>();
        for ( final AggregatedQuery r : rows ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "query", r.queryText() );
            m.put( "occurrences", r.occurrences() );
            m.put( "avgResultCount", r.avgResultCount() );
            m.put( "zeroResultCount", r.zeroResultCount() );
            m.put( "lastSeen", r.lastSeen() == null ? null : r.lastSeen().toString() );
            rendered.add( m );
        }
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "queries", rendered );
        result.put( "count", rendered.size() );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    private static int intArg( final Map< String, Object > args, final String key, final int dflt ) {
        final Object v = args.get( key );
        if ( v instanceof Number n ) {
            return n.intValue();
        }
        if ( v != null ) {
            try {
                return Integer.parseInt( v.toString().strip() );
            } catch ( final NumberFormatException e ) {
                return dflt;
            }
        }
        return dflt;
    }
}
```

> If `McpSchema.CallToolResult.isError()` returns a boxed `Boolean` (nullable) in this MCP version, adjust the test's assertion accordingly (the test already guards with `!= null`). Confirm against `VerifyPagesTool`'s result handling.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl wikantik-admin-mcp -Dtest=ListRetrievalQueriesToolTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListRetrievalQueriesTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ListRetrievalQueriesToolTest.java
git commit -m "feat(admin-mcp): list_retrieval_queries tool over the query log

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Wire the reader into `WikiEngine` and register the tool

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java`

**Interfaces:**
- Consumes: `QueryLogWiring.buildReader`, `ListRetrievalQueriesTool`, the existing `queryLogService` wiring seam.
- Produces: `WikiEngine.queryLogReader() -> QueryLogReader` (nullable when no datasource); `list_retrieval_queries` registered as a read-only tool when a reader is present.

> Pure dependency wiring — behavior is asserted end-to-end by the Cargo IT in Task 7 (which lists tools over the wire and calls the tool against real Postgres). No separate unit test for the two wiring lines.

- [ ] **Step 1: Add the accessor + field to `WikiEngine`**

Beside the existing `queryLogService` field/accessors (around line 1868-1877) add:

```java
    private volatile com.wikantik.api.querylog.QueryLogReader queryLogReader;

    /** Called at startup once the persistence DataSource is available. */
    public void setQueryLogReader( final com.wikantik.api.querylog.QueryLogReader reader ) {
        this.queryLogReader = reader;
    }

    /** The query-log read side; {@code null} when no datasource is configured. */
    public com.wikantik.api.querylog.QueryLogReader queryLogReader() {
        return queryLogReader;
    }
```

- [ ] **Step 2: Build the reader at startup**

At the query-log build site (the line `this.setQueryLogService( ... QueryLogWiring.build( ds, props ) );`, ~line 1626) add immediately after it:

```java
            this.setQueryLogReader( com.wikantik.knowledge.querylog.QueryLogWiring.buildReader( ds ) );
```

- [ ] **Step 3: Register the tool in `McpToolRegistry`**

In `McpToolRegistry`, after the KG block and before `readOnly = List.copyOf( readOnlyList );` add:

```java
        // Query-log read tool — only when the engine has a datasource-backed reader.
        if ( engine instanceof com.wikantik.WikiEngine we && we.queryLogReader() != null ) {
            readOnlyList.add( new ListRetrievalQueriesTool( we.queryLogReader() ) );
        }
```

- [ ] **Step 4: Compile-check both modules**

Run: `mvn test-compile -pl wikantik-main,wikantik-admin-mcp -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java
git commit -m "feat(querylog): wire reader into WikiEngine + register list_retrieval_queries

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Wire-level Cargo IT for `list_retrieval_queries`

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/ListRetrievalQueriesIT.java`

**Interfaces:**
- Consumes: the existing admin-MCP IT harness used by `AdminMcpReadToolsIT` / `McpProtocolIT` (MCP session init helper, base URL, admin bearer/API key). Reuse its session-init + `tools/call` helper rather than re-rolling SSE parsing.

- [ ] **Step 1: Write the IT**

Mirror `AdminMcpReadToolsIT`'s structure (same base class / helper for `initialize` → `Mcp-Session-Id` → `notifications/initialized` → `tools/call`, per the CLAUDE.md MCP testing notes). Two assertions:

```java
// 1. tools/list contains the new tool
List<String> toolNames = mcp.listToolNames();   // existing helper
assertTrue( toolNames.contains( "list_retrieval_queries" ) );

// 2. tools/call returns a well-formed (non-error) payload against the real schema.
//    The IT DB may have no rows yet — assert structure, not specific queries.
JsonNode payload = mcp.callTool( "list_retrieval_queries",
        Map.of( "since_days", 3650, "limit", 5 ) );
assertFalse( payload.path( "isError" ).asBoolean( false ) );
assertTrue( payload.at( "/structuredContent/queries" ).isArray()
         || payload.toString().contains( "\"queries\"" ) );
```

> Match the exact helper names/return shapes used in `AdminMcpReadToolsIT`. If that IT inlines the SSE handling, copy its private helpers into this class. The point of this IT is real-Postgres coverage of the reader SQL + registry wiring end-to-end.

- [ ] **Step 2: Run the IT module (sequential, fail-at-end)**

Run: `mvn clean install -pl wikantik-it-tests/wikantik-selenide-tests -am -Pintegration-tests -fae -Dtest=ListRetrievalQueriesIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the IT passes (Cargo boots Tomcat + Postgres, tool lists and calls cleanly).

> Per the IT war-overlay gotcha, this uses `clean` so the war overlay is rebuilt with the new tool. If you hit a stale-overlay 404, purge `~/.m2/repository/.../wikantik-war` and retry.

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/ListRetrievalQueriesIT.java
git commit -m "test(admin-mcp): wire-level IT for list_retrieval_queries

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Skill + docs

### Task 8: Rewrite the `wiki-content` skill for retrieval-aware authoring

**Files:**
- Modify: `.claude/skills/wiki-content/SKILL.md`

- [ ] **Step 1: Replace the "Metadata Conventions" intro + add the retrieval section**

After the existing metadata table (the row block ending at `summary`), insert a new top-level section immediately before `### SEO and Web Visibility`:

```markdown
## Retrieval-Aware Frontmatter (read this before writing summaries/headings)

Four author-controlled levers are **prepended verbatim into every chunk's embedding** before it
enters the dense index (`EmbeddingTextBuilder.forDocument`), in this exact shape:

​```
Page: {title} | Cluster: {cluster} | Section: {heading > path}
Summary: {summary}

{chunk body}
​```

This contextual-embedding lever lifted section recall@12 from ~0.60 to ~0.74 — the single biggest
retrieval gain in the stack, larger than any model change. So `title`, `cluster`, `summary`, and the
body's **heading structure** are not just SEO metadata — they are the primary retrieval levers.
Because retrieval is **dense + BM25 hybrid**, literal vocabulary in body and headings also counts.

**The dual-purpose overlap (retrieval + SEO in one field):**

| Field | Retrieval role (embedded) | SEO role | Authoring rule |
|-------|---------------------------|----------|----------------|
| `summary` | Page-level disambiguation on every chunk | `<meta description>` | Describe the page's value + name its key concepts/vocabulary, specifically. ~80–160 chars. Not a title restatement, not marketing fluff. |
| `cluster` | Domain prefix on every chunk | JSON-LD `articleSection` | Always set; kebab-case (`hybrid-retrieval`, sub-clusters `parent/child`). |
| `title` | Topic signal on every chunk | `<title>`, JSON-LD | Natural-language, specific — never just the slug. |
| headings | Each chunk's `Section:` path | (page structure) | Self-contained and specific. Avoid `Overview`/`Introduction`/`Details`/`Notes`/`Summary`/`Background` — they give chunks weak section context. |

**Do NOT chase rejected levers** (measured dead ends): rerankers, bigger embedding models, HyDE,
doc2query, KG graph rerank, lexical injection. The frontmatter levers above are the ones that move recall.

## Retrieval Verification Loop

Run this when authoring a new page or updating an existing one:

1. **Static lint** — `verify_pages` with `checks=["retrieval_readiness"]`. Fix every entry in
   `retrievalIssues` (advisory warnings: thin/title-restating summaries, generic or missing headings,
   missing/non-kebab cluster, slug-echo titles). Combine with `["seo_readiness"]` in the same call to
   catch both faces at once.
2. **Live check** — confirm the page's answering section actually surfaces, using the `assemble_bundle`
   MCP tool (knowledge-mcp surface). **MCP-only — never REST/`/api/bundle`.**
   - New page (no traffic yet): write 3–5 expected queries it should answer, call `assemble_bundle` with
     each, and confirm the page's section appears in the returned bundle. If not, strengthen the
     summary/headings and re-index.
   - Existing content (maintenance): use `list_retrieval_queries` (see below) to pull **real** queries,
     then `assemble_bundle`-check the ones a given page should answer.
3. **Maintenance sweep** — `list_retrieval_queries` with `max_avg_results` set (e.g. `1`) lists real
   queries the corpus answers poorly. For each, identify the page that *should* answer it, call
   `assemble_bundle` to confirm it's a real miss, then apply step 1's lint + fixes and re-verify.
```

- [ ] **Step 2: Add the two new tools to the tool tables**

In the **Verification tools** table add:

```markdown
| `list_retrieval_queries` | Real retrieval queries (deduped, ranked); `max_avg_results` finds under-served queries | maintenance sweeps grounded in real traffic |
```

and append to the `verify_pages` row purpose: `… metadata, SEO readiness, and retrieval readiness for multiple pages`.

- [ ] **Step 3: Add a portability note near the top (after Overview)**

```markdown
> **Portability (MCP-only):** every action here routes through MCP server tools — no REST/`/api/*`,
> curl, or bash — so this skill runs identically under Claude Code and Antigravity. The live bundle
> check is the `assemble_bundle` MCP tool, never `GET /api/bundle`. Do not add client-specific mechanisms.
```

- [ ] **Step 4: Commit**

```bash
git add .claude/skills/wiki-content/SKILL.md
git commit -m "docs(skill): retrieval-aware frontmatter + verification loop in wiki-content

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: Update repo docs (tool counts, CHANGELOG)

**Files:**
- Modify: `CLAUDE.md`, `README.md`, `CHANGELOG.md`

- [ ] **Step 1: Confirm the live tool count**

Run: `grep -c "readOnlyList.add\|authorConfigurableList.add\|= new ArrayList<>( List.of(" wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java`
Then read the registry to confirm the new total is **26** (was 25). Use the real number you observe.

- [ ] **Step 2: Edit CLAUDE.md**

In the `wikantik-admin-mcp` bullet, change `25 tools` → `26 tools` and append `, plus list_retrieval_queries (real-traffic query log read)` to the tool description. In the agent-surface table row for `/wikantik-admin-mcp`, change `25` → `26`. In the `wikantik-rest`/`verify_pages` mentions where SEO checks are listed, note the added `retrieval_readiness` check.

- [ ] **Step 3: Edit README.md**

Update any admin-MCP tool count (`25` → `26`) and, if the README enumerates verify checks, add `retrieval_readiness`.

- [ ] **Step 4: Add a CHANGELOG entry** (top, under the current unreleased/snapshot heading)

```markdown
### Added
- **Retrieval-aware content authoring.** `verify_pages` gains a `retrieval_readiness` check
  (summary specificity, heading quality, cluster, title — the frontmatter levers prepended into
  chunk embeddings) and a new `list_retrieval_queries` admin-MCP tool over the real query log
  (V041) for finding under-served queries. The `wiki-content` skill now documents the
  author → static-lint → live-bundle-check verification loop.
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md README.md CHANGELOG.md
git commit -m "docs: admin-mcp 25->26 (list_retrieval_queries) + verify_pages retrieval_readiness

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 10: Full verification, build, deploy to localhost, and live trial

**Files:** none (verification + deploy).

- [ ] **Step 1: Full unit build (no parallel for the reactor's serial unit tier)**

Run: `mvn clean install -DskipITs`
Expected: BUILD SUCCESS. (Avoid `-T 1C` — it triggers the known security-policy-init flake in wikantik-main.)

- [ ] **Step 2: Full IT reactor (cross-module gate)**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: green. Known unrelated flakes (admin-login-race, ontology-materialization) re-run clean in isolation if they appear.

- [ ] **Step 3: Deploy to localhost:8080**

Run: `bin/deploy-local.sh` then `tomcat/tomcat-11/bin/startup.sh` (or `bin/redeploy.sh` if already set up).
Expected: app reachable at `http://localhost:8080/`. (Deploy applies no new migration — V041 already present.)

- [ ] **Step 4: Exercise `retrieval_readiness` live**

Drive the admin MCP endpoint at `http://localhost:8080/wikantik-admin-mcp` (auth via `test.properties` testbot creds / configured MCP access key). Per CLAUDE.md MCP notes: `initialize` → capture `Mcp-Session-Id` → `notifications/initialized` → `tools/call`. Call `verify_pages` with `checks=["retrieval_readiness"]` on a known weak page and confirm `retrievalIssues` is populated.

- [ ] **Step 5: Exercise `list_retrieval_queries` live**

Call `list_retrieval_queries` with `{"since_days": 365, "limit": 20}` and confirm a well-formed `queries` array (real traffic has accrued since V041 shipped). Then call with `{"max_avg_results": 1}` and confirm it narrows to under-served queries. Record the observed behavior.

- [ ] **Step 6: Final commit (if any doc tweaks resulted from the live trial)**

```bash
git add -- <only files you changed>
git commit -m "docs: notes from live retrieval-tooling trial on localhost

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Component 1 (static lint `retrieval_readiness`) → Tasks 1–2. ✅ (4 checks + `verify_pages` wiring, advisory warnings, `body` captured for heading checks.)
- Component 2 (`list_retrieval_queries` read tool, the new code) → Tasks 3–7. ✅ (api types, JDBC reader, tool, engine/registry wiring, wire-level IT.)
- Component 3 (live check, no server code) → documented in Task 8 skill workflow. ✅
- Component 4 (skill rewrite, portability) → Task 8. ✅
- SEO overlap captured → Task 8 dual-purpose table + combined `seo_readiness`+`retrieval_readiness` lint. ✅
- Testing (unit per check, H2 reader, tool unit, Cargo IT) → Tasks 1,3,4,5,7. ✅
- Rollout (no migration, deploy + live trial, tool count 25→26) → Tasks 9–10. ✅

**Placeholder scan:** All code steps contain full code. The two "mirror the existing harness" notes (Tasks 4 PG-coverage-via-IT, 7 IT helpers) are deliberate reuse of existing test infrastructure, with the assertion bodies written out — not deferred work.

**Type consistency:** `QueryLogReader.topQueries(QueryLogQuery) -> List<AggregatedQuery>` is used identically in Tasks 3/4/5. `AggregatedQuery(queryText, occurrences, avgResultCount, zeroResultCount, lastSeen)` field names match across the reader, the tool render, and both tests. `ListRetrievalQueriesTool(QueryLogReader)` constructor matches Tasks 5/6. `WikiEngine.queryLogReader()` matches Tasks 6/registry. Check names (`SummarySpecificityCheck`, `HeadingQualityCheck`, `ClusterPresentCheck`, `TitleSpecificityCheck`) match Tasks 1/2.
