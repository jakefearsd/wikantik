# Phase 1 — Context Bundle Assembly + Reranker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build RAG-as-a-Service: assemble retrieval into a ranked, de-duplicated, citation-bearing **context bundle** — `retrieve → per-page shortlist → 4B LLM rerank → parent-section + dedup → version-pinned citations → top-N` — exposed via a REST endpoint + MCP action, scored on the Phase-0 harness.

**Architecture:** Pure-assembly layer over the existing `ContextRetrievalService` (no new retrieval, no synthesis, no model swap — the spike sweep proved 0.6B+instruction is the right first stage). The one external dependency is the listwise reranker (`gemma4:e4b` via Ollama, `think:false`, reusing `OllamaChatRequest`). Rides every existing rail.

**Tech Stack:** Java 21, JUnit 5, Maven, `com.wikantik.knowledge.*`, the eval harness from Phase 0, Gson, `java.net.http.HttpClient`.

**Design source:** `docs/superpowers/specs/2026-06-13-rag-as-a-service-and-knowledge-base-design.md` (Phase 1 + "Exploration concluded"), ADRs 0001/0003/0005. Vocabulary: `CONTEXT.md`.

---

## Context the implementer needs

- **Retrieval entry point:** `ContextRetrievalService.retrieve(ContextQuery)` → `RetrievalResult(query, List<RetrievedPage>, totalMatched)`. `RetrievedPage(name, url, score, summary, cluster, tags, contributingChunks, relatedPages, author, lastModified)`; `RetrievedChunk(List<String> headingPath, String text, double chunkScore, List<String> matchedTerms)`. `name` is the page slug.
- **`ContextQuery`** is a 4-arg record: `new ContextQuery(query, ContextQuery.MAX_PAGES_CAP, ContextQuery.MAX_CHUNKS_PER_PAGE_CAP, null)` (20 pages, 5 chunks/page, no filter).
- **Reranker reuses `OllamaChatRequest.body(model, systemPrompt, userPrompt, keepAlive)`** (`com.wikantik.knowledge.extraction`, package-private — the reranker lives in a sibling package, so make a tiny public re-export or move it; see Task 3) — it always sets `think:false` + `format:json` + `stream:false`. The HTTP-call shape to copy is `OllamaEntityExtractor.callOllama` (HttpClient, POST `<baseUrl>/api/chat`, parse `message.content`).
- **canonical_id:** `PageCanonicalIdsDao.findBySlug(slug)` → `Optional<Row>` (`Row.canonicalId()`). **version:** `pageManager.getPage(slug).getVersion()`. **span hash:** SHA-256 hex of the section text (copy `BundleMetricsCalculator.sha256`).
- **Harness (Phase 0):** `BundleEvalRunner(BundleRetriever, int precisionK).run(List<BundleEvalQuestion>)` → `BundleEvalReport`; `BundleRetriever` is `Function<String, List<com.wikantik.api.eval.BundleSection>>`; `BundleCorpusLoader.load(Path)`. The frozen baseline (`baseline-notes.md`): max-chunk-0.6B ~0.41@5 / 0.54@12; **the bundle must beat dense on the harness.**
- **Surface templates:** REST → mirror `wikantik-rest/.../rest/PageForAgentResource.java` (`extends RestServletBase`). MCP → mirror `wikantik-knowledge/.../mcp/GetPageForAgentTool.java` (`implements McpTool`: `name()`, `inputSchema()`, `execute(Map)`), registered in `KnowledgeMcpInitializer`.
- **Every new `.java`** starts with the 17-line ASF header (copy from any sibling).
- **Build:** `mvn -q -pl wikantik-main -am test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`. Run the full IT reactor before the final prod-code commit (`mvn clean install -Pintegration-tests -fae`).

---

## File structure

**New (api types — `wikantik-api/src/main/java/com/wikantik/api/bundle/`):**
- `CitationHandle.java` — `(canonicalId, version, headingPath, span, spanSha256)`.
- `BundleSection.java` — `(canonicalId, slug, headingPath, text, score, citation)`.
- `ContextBundle.java` — `(query, List<BundleSection> sections)`.

**New (logic — `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/`):**
- `CandidateSection.java` — internal: `(slug, headingPath, text, denseScore)`.
- `SectionAssembler.java` — `RetrievalResult` → per-page shortlist of `CandidateSection`.
- `SectionReranker.java` (interface) + `LlmSectionReranker.java` — listwise rerank.
- `DefaultBundleAssemblyService.java` (+ `BundleAssemblyService` interface in api/bundle) — orchestration: assemble → rerank → dedup → cite → top-N.
- `RerankerConfig.java` — model/base-url/timeout.

**New (tests):** one per class under the mirrored test paths, plus `BundleHarnessGateTest.java`.

**New (surface):** `wikantik-rest/.../rest/BundleResource.java`; `wikantik-knowledge/.../mcp/AssembleBundleTool.java` (+ registration edit in `KnowledgeMcpInitializer`).

---

## PART A — Bundle types (api)

### Task 1: CitationHandle, BundleSection, ContextBundle

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/bundle/CitationHandle.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/bundle/BundleSection.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/bundle/ContextBundle.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/bundle/BundleTypesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.api.bundle;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundleTypesTest {
    private static CitationHandle cite() {
        return new CitationHandle( "01ABC", 7, List.of( "Setup" ), "body span", "deadbeef" );
    }

    @Test
    void citation_requires_canonicalId_and_copies_path() {
        final CitationHandle c = cite();
        assertEquals( "01ABC", c.canonicalId() );
        assertEquals( List.of( "Setup" ), c.headingPath() );
        assertThrows( IllegalArgumentException.class,
            () -> new CitationHandle( " ", 1, List.of(), "x", "y" ) );
    }

    @Test
    void bundleSection_and_bundle_validate_and_copy() {
        final BundleSection s = new BundleSection( "01ABC", "DeployGuide", List.of( "Setup" ), "txt", 0.9, cite() );
        assertEquals( "DeployGuide", s.slug() );
        assertEquals( 0.9, s.score(), 1e-9 );
        final ContextBundle b = new ContextBundle( "how to deploy", List.of( s ) );
        assertEquals( 1, b.sections().size() );
        assertThrows( IllegalArgumentException.class, () -> new ContextBundle( null, List.of() ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-api test -Dtest=BundleTypesTest`
Expected: FAIL — types not defined.

- [ ] **Step 3: Write minimal implementation** (each with the ASF header)

`CitationHandle.java`:
```java
package com.wikantik.api.bundle;

import java.util.List;

/** A version-pinned, span-hashed grounding handle for a bundle section (ADR-0005). */
public record CitationHandle(
    String canonicalId, int version, List< String > headingPath, String span, String spanSha256
) {
    public CitationHandle {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId must not be blank" );
        }
        headingPath = headingPath == null ? List.of() : List.copyOf( headingPath );
        span = span == null ? "" : span;
    }
}
```

`BundleSection.java`:
```java
package com.wikantik.api.bundle;

import java.util.List;

/** One ranked, cited evidence section in a context bundle. */
public record BundleSection(
    String canonicalId, String slug, List< String > headingPath, String text,
    double score, CitationHandle citation
) {
    public BundleSection {
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "slug must not be blank" );
        }
        headingPath = headingPath == null ? List.of() : List.copyOf( headingPath );
        text = text == null ? "" : text;
    }
}
```

`ContextBundle.java`:
```java
package com.wikantik.api.bundle;

import java.util.List;

/** The unit RAG-as-a-Service returns: a ranked, de-duplicated, cited set of sections. */
public record ContextBundle( String query, List< BundleSection > sections ) {
    public ContextBundle {
        if ( query == null ) {
            throw new IllegalArgumentException( "query must not be null" );
        }
        sections = sections == null ? List.of() : List.copyOf( sections );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-api test -Dtest=BundleTypesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/bundle/ wikantik-api/src/test/java/com/wikantik/api/bundle/BundleTypesTest.java
git commit -m "feat(bundle): context bundle value types + version-pinned citation handle"
```

---

## PART B — Assembly logic (wikantik-main)

### Task 2: SectionAssembler (retrieval → per-page shortlist)

Groups each retrieved page's `contributingChunks` into sections keyed by `headingPath`, scores each section by its **best chunk** (`max chunkScore`), keeps the best chunk's text, and takes the **top-S sections per page** (the spike's per-page shortlist — preserves the 0.97 page-recall).

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/CandidateSection.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SectionAssembler.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/SectionAssemblerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.bundle;

import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SectionAssemblerTest {
    private static RetrievedChunk chunk( List<String> path, String text, double score ) {
        return new RetrievedChunk( path, text, score, List.of() );
    }

    @Test
    void groups_by_heading_and_keeps_top_s_per_page_with_best_chunk() {
        final RetrievedPage page = new RetrievedPage(
            "DeployGuide", "/wiki/DeployGuide", 1.0, "", "ops", List.of(),
            List.of(
                chunk( List.of( "Setup" ), "setup A", 0.5 ),
                chunk( List.of( "Setup" ), "setup B (best)", 0.9 ),   // same section, higher score
                chunk( List.of( "Usage" ), "usage", 0.7 ),
                chunk( List.of( "Notes" ), "notes", 0.1 ) ),
            List.of(), "admin", null );

        final List<CandidateSection> secs = new SectionAssembler( 2 )
            .assemble( new RetrievalResult( "q", List.of( page ), 1 ) );

        // top-2 sections per page by best-chunk score: Setup(0.9), Usage(0.7); Notes dropped
        assertEquals( 2, secs.size() );
        assertEquals( List.of( "Setup" ), secs.get( 0 ).headingPath() );
        assertEquals( "setup B (best)", secs.get( 0 ).text() );
        assertEquals( 0.9, secs.get( 0 ).denseScore(), 1e-9 );
        assertEquals( List.of( "Usage" ), secs.get( 1 ).headingPath() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=SectionAssemblerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — classes not defined.

- [ ] **Step 3: Write minimal implementation**

`CandidateSection.java`:
```java
package com.wikantik.knowledge.bundle;

import java.util.List;

/** Internal: a page section assembled from contributing chunks, before reranking. */
record CandidateSection( String slug, List< String > headingPath, String text, double denseScore ) {
    CandidateSection {
        headingPath = headingPath == null ? List.of() : List.copyOf( headingPath );
    }
}
```

`SectionAssembler.java`:
```java
package com.wikantik.knowledge.bundle;

import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups each page's contributing chunks into sections (best chunk per heading-path) and keeps
 *  the top-S sections per page — the per-page shortlist the spike sweep validated. */
public final class SectionAssembler {

    private final int sectionsPerPage;

    public SectionAssembler( final int sectionsPerPage ) {
        if ( sectionsPerPage <= 0 ) {
            throw new IllegalArgumentException( "sectionsPerPage must be positive" );
        }
        this.sectionsPerPage = sectionsPerPage;
    }

    public List< CandidateSection > assemble( final RetrievalResult result ) {
        final List< CandidateSection > out = new ArrayList<>();
        for ( final RetrievedPage page : result.pages() ) {
            // heading-path -> best (score, text)
            final Map< List< String >, double[] > bestScore = new LinkedHashMap<>();
            final Map< List< String >, String > bestText = new LinkedHashMap<>();
            for ( final RetrievedChunk c : page.contributingChunks() ) {
                final List< String > key = c.headingPath();
                final double[] cur = bestScore.get( key );
                if ( cur == null || c.chunkScore() > cur[ 0 ] ) {
                    bestScore.put( key, new double[]{ c.chunkScore() } );
                    bestText.put( key, c.text() );
                }
            }
            bestScore.entrySet().stream()
                .sorted( Comparator.comparingDouble( ( Map.Entry< List< String >, double[] > e ) -> e.getValue()[ 0 ] ).reversed() )
                .limit( sectionsPerPage )
                .forEach( e -> out.add( new CandidateSection(
                    page.name(), e.getKey(), bestText.get( e.getKey() ), e.getValue()[ 0 ] ) ) );
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=SectionAssemblerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/CandidateSection.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SectionAssembler.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/SectionAssemblerTest.java
git commit -m "feat(bundle): per-page section shortlist assembler"
```

---

### Task 3: SectionReranker + LlmSectionReranker (listwise, gemma4:e4b)

Listwise rerank of the candidate sections via Ollama (`gemma4:e4b`, `think:false`). The reranker takes the query + ordered candidate sections, asks the model for a JSON ranking `{"ranking":[1-based indices best-first]}`, and returns the sections in that order (unranked items appended in original order, so it degrades safely).

**Make `OllamaChatRequest` reachable:** it's package-private in `com.wikantik.knowledge.extraction`. In this task, change its declaration from `final class OllamaChatRequest` to `public final class OllamaChatRequest` and `static Map<...> body(...)` to `public static Map<...> body(...)` (file `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/OllamaChatRequest.java`) so the `bundle` package can reuse it. Re-run `OllamaChatRequestTest` after — it must still pass.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/OllamaChatRequest.java` (widen visibility)
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/RerankerConfig.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SectionReranker.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/LlmSectionReranker.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/LlmSectionRerankerTest.java`

- [ ] **Step 1: Write the failing test** (parsing/reorder logic, with a stubbed HTTP responder so no network)

```java
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LlmSectionRerankerTest {

    private static CandidateSection sec( String slug, String head ) {
        return new CandidateSection( slug, List.of( head ), head + " text", 0.5 );
    }

    @Test
    void reorders_by_model_ranking_and_appends_unranked() {
        final List<CandidateSection> in = List.of( sec("p","A"), sec("p","B"), sec("p","C") );
        // model says order is 2,3 (1-based) -> B, C, then A appended (unranked)
        final LlmSectionReranker r = new LlmSectionReranker(
            new RerankerConfig( "gemma4:e4b", "http://x", 1000 ),
            ( prompt ) -> "{\"ranking\":[2,3]}" );   // injected responder (no network)
        final List<CandidateSection> out = r.rerank( "q", in );
        assertEquals( List.of("B","C","A"), out.stream().map( s -> s.headingPath().get(0) ).toList() );
    }

    @Test
    void empty_or_garbage_ranking_returns_input_order() {
        final List<CandidateSection> in = List.of( sec("p","A"), sec("p","B") );
        final LlmSectionReranker r = new LlmSectionReranker(
            new RerankerConfig( "m", "http://x", 1000 ), ( p ) -> "not json" );
        assertEquals( in, r.rerank( "q", in ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=LlmSectionRerankerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — classes not defined.

- [ ] **Step 3: Write minimal implementation**

`RerankerConfig.java`:
```java
package com.wikantik.knowledge.bundle;

/** Config for the listwise section reranker. Model defaults to the 4B sweet spot from the
 *  2026-06-13 spike sweep; think:false is enforced by OllamaChatRequest. */
public record RerankerConfig( String model, String baseUrl, long timeoutMs ) {
    public static final String PREFIX = "wikantik.bundle.reranker.";
    public RerankerConfig {
        if ( model == null || model.isBlank() ) model = "gemma4:e4b";
        if ( baseUrl == null || baseUrl.isBlank() ) baseUrl = "http://inference.jakefear.com:11434";
        if ( timeoutMs <= 0 ) timeoutMs = 30_000L;
    }
}
```

`SectionReranker.java`:
```java
package com.wikantik.knowledge.bundle;

import java.util.List;

/** Reorders candidate sections by relevance to the query. Implementations must degrade to the
 *  input order on any failure (never drop sections). */
public interface SectionReranker {
    List< CandidateSection > rerank( String query, List< CandidateSection > sections );
}
```

`LlmSectionReranker.java`:
```java
package com.wikantik.knowledge.bundle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.knowledge.extraction.OllamaChatRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Listwise reranker backed by an Ollama chat model (gemma4:e4b, think:false). The responder
 *  function is injectable for tests; production uses the real HTTP call. */
public final class LlmSectionReranker implements SectionReranker {

    private static final Logger LOG = LogManager.getLogger( LlmSectionReranker.class );
    private static final Gson GSON = new Gson();

    private final RerankerConfig config;
    private final Function< String, String > responder;  // prompt -> model text

    /** Production constructor: real HTTP responder. */
    public LlmSectionReranker( final RerankerConfig config ) {
        this( config, null );
    }

    /** Test/injected constructor. {@code responder == null} uses the real HTTP call. */
    public LlmSectionReranker( final RerankerConfig config, final Function< String, String > responder ) {
        this.config = config;
        this.responder = responder != null ? responder : this::callOllama;
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        if ( sections == null || sections.size() <= 1 ) return sections;
        try {
            final String text = responder.apply( buildPrompt( query, sections ) );
            final List< Integer > order = parseRanking( text, sections.size() );
            if ( order.isEmpty() ) return sections;
            final List< CandidateSection > out = new ArrayList<>( sections.size() );
            final boolean[] used = new boolean[ sections.size() ];
            for ( final int oneBased : order ) {
                final int i = oneBased - 1;
                if ( i >= 0 && i < sections.size() && !used[ i ] ) { out.add( sections.get( i ) ); used[ i ] = true; }
            }
            for ( int i = 0; i < sections.size(); i++ ) if ( !used[ i ] ) out.add( sections.get( i ) );
            return out;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Section rerank failed ({}); using dense order", e.getMessage() );
            return sections;
        }
    }

    private static String buildPrompt( final String query, final List< CandidateSection > s ) {
        final StringBuilder sb = new StringBuilder( "Query: " ).append( query )
            .append( "\n\nRank the passages from MOST to LEAST relevant to the query. "
                + "Respond ONLY as JSON: {\"ranking\":[passage numbers, best first]}.\n\nPassages:\n" );
        for ( int i = 0; i < s.size(); i++ ) {
            sb.append( i + 1 ).append( ". [" ).append( String.join( " > ", s.get( i ).headingPath() ) )
              .append( "] " ).append( truncate( s.get( i ).text(), 240 ) ).append( '\n' );
        }
        return sb.toString();
    }

    private static List< Integer > parseRanking( final String text, final int n ) {
        final List< Integer > out = new ArrayList<>();
        try {
            final JsonObject o = JsonParser.parseString( text ).getAsJsonObject();
            o.getAsJsonArray( "ranking" ).forEach( el -> {
                final int v = el.getAsInt();
                if ( v >= 1 && v <= n && !out.contains( v ) ) out.add( v );
            } );
        } catch ( final RuntimeException ignored ) {
            // malformed -> empty -> caller keeps dense order
        }
        return out;
    }

    private String callOllama( final String prompt ) {
        final var body = OllamaChatRequest.body( config.model(), "", prompt, null );
        final HttpRequest req = HttpRequest.newBuilder( URI.create( stripSlash( config.baseUrl() ) + "/api/chat" ) )
            .timeout( Duration.ofMillis( config.timeoutMs() ) )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( GSON.toJson( body ) ) )
            .build();
        try {
            final HttpResponse< String > res = HttpClient.newHttpClient()
                .send( req, HttpResponse.BodyHandlers.ofString() );
            if ( res.statusCode() / 100 != 2 ) { LOG.warn( "Rerank HTTP {}", res.statusCode() ); return ""; }
            return JsonParser.parseString( res.body() ).getAsJsonObject()
                .getAsJsonObject( "message" ).get( "content" ).getAsString();
        } catch ( final java.io.IOException e ) {
            LOG.warn( "Rerank IO error: {}", e.getMessage() ); return "";
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt(); return "";
        }
    }

    private static String truncate( final String s, final int n ) { return s.length() <= n ? s : s.substring( 0, n ); }
    private static String stripSlash( final String s ) { return s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s; }
}
```

- [ ] **Step 4: Run tests to verify they pass** (reranker + the still-green chat-request test)

Run: `mvn -q -pl wikantik-main test -Dtest='LlmSectionRerankerTest,OllamaChatRequestTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/OllamaChatRequest.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/RerankerConfig.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SectionReranker.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/LlmSectionReranker.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/LlmSectionRerankerTest.java
git commit -m "feat(bundle): listwise LLM section reranker (gemma4:e4b, think:false)"
```

---

### Task 4: DefaultBundleAssemblyService (orchestrate + dedup + cite + top-N)

Orchestrates the full pipeline and produces the `ContextBundle`: retrieve → assemble (per-page shortlist) → rerank → **dedup** by `(slug, headingPath)` keeping the first (highest-ranked) → take **top-N** → attach a `CitationHandle` (canonical_id via `PageCanonicalIdsDao`, version via `PageManager`, span = section text, `spanSha256` via SHA-256).

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/bundle/BundleAssemblyService.java` (interface: `ContextBundle assemble(String query)`)
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceTest.java`

- [ ] **Step 1: Write the failing test** (fakes for retrieval + reranker + canonical_id/version lookups, so no DB/network)

```java
package com.wikantik.knowledge.bundle;

import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class DefaultBundleAssemblyServiceTest {

    private static RetrievedChunk ch( String head, String text, double s ) {
        return new RetrievedChunk( List.of( head ), text, s, List.of() );
    }

    @Test
    void assembles_dedups_cites_and_caps_topN() {
        final RetrievedPage page = new RetrievedPage( "DeployGuide", "/wiki/DeployGuide", 1.0, "", "ops",
            List.of(), List.of( ch("Setup","setup",0.9), ch("Usage","usage",0.7) ), List.of(), "a", null );
        final ContextRetrievalService retrieval = new StubRetrieval(
            new RetrievalResult( "deploy", List.of( page ), 1 ) );
        final SectionReranker identity = ( q, secs ) -> secs;             // keep dense order
        final Function<String,Optional<String>> canon = slug -> Optional.of( "01DEP" );
        final Function<String,Integer> version = slug -> 7;

        final ContextBundle b = new DefaultBundleAssemblyService( retrieval, identity, canon, version, 3, 1 )
            .assemble( "deploy" );

        assertEquals( "deploy", b.query() );
        assertFalse( b.sections().isEmpty() );
        final var top = b.sections().get( 0 );
        assertEquals( "01DEP", top.canonicalId() );
        assertEquals( 7, top.citation().version() );
        assertEquals( top.text(), top.citation().span() );
        assertFalse( top.citation().spanSha256().isBlank() );
        // dedup: no two sections share (slug, headingPath)
        assertEquals( b.sections().stream().map( s -> s.slug()+s.headingPath() ).distinct().count(),
                      b.sections().size() );
    }

    private record StubRetrieval( RetrievalResult fixed ) implements ContextRetrievalService {
        public RetrievalResult retrieve( ContextQuery q ) { return fixed; }
        public RetrievedPage getPage( String n ) { return null; }
        public PageList listPages( PageListFilter f ) { return null; }
        public List<MetadataValue> listMetadataValues( String field ) { return List.of(); }
    }
}
```

> Confirm `ContextQuery.of`/constructor and the `PageList`/`PageListFilter`/`MetadataValue` types compile in the stub (they live in `com.wikantik.api.knowledge`); adjust the stub to the real signatures via `mvn -q -pl wikantik-api test-compile`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=DefaultBundleAssemblyServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — service not defined.

- [ ] **Step 3: Write minimal implementation**

`BundleAssemblyService.java` (api/bundle):
```java
package com.wikantik.api.bundle;

/** RAG-as-a-Service: assemble retrieval into a ranked, de-duplicated, cited context bundle.
 *  No answer synthesis (ADR-0001). */
public interface BundleAssemblyService {
    ContextBundle assemble( String query );
}
```

`DefaultBundleAssemblyService.java` (knowledge/bundle):
```java
package com.wikantik.knowledge.bundle;

import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Orchestrates retrieve → per-page shortlist → rerank → dedup → top-N → cite (ADR-0001/0003/0005). */
public final class DefaultBundleAssemblyService implements BundleAssemblyService {

    private final ContextRetrievalService retrieval;
    private final SectionReranker reranker;
    private final SectionAssembler assembler;
    private final Function< String, Optional< String > > canonicalIdOf;  // slug -> canonical_id
    private final Function< String, Integer > versionOf;                  // slug -> page version
    private final int maxSections;

    public DefaultBundleAssemblyService( final ContextRetrievalService retrieval,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections,
                                         final int sectionsPerPage ) {
        this.retrieval = retrieval;
        this.reranker = reranker;
        this.canonicalIdOf = canonicalIdOf;
        this.versionOf = versionOf;
        this.maxSections = maxSections;
        this.assembler = new SectionAssembler( sectionsPerPage );
    }

    @Override
    public ContextBundle assemble( final String query ) {
        final var result = retrieval.retrieve(
            new ContextQuery( query, ContextQuery.MAX_PAGES_CAP, ContextQuery.MAX_CHUNKS_PER_PAGE_CAP, null ) );
        final List< CandidateSection > ranked = reranker.rerank( query, assembler.assemble( result ) );

        final Set< String > seen = new LinkedHashSet<>();
        final List< BundleSection > out = new ArrayList<>();
        for ( final CandidateSection cs : ranked ) {
            final String key = cs.slug() + " " + String.join( "", cs.headingPath() );
            if ( !seen.add( key ) ) continue;          // dedup by (slug, heading-path)
            final String canonical = canonicalIdOf.apply( cs.slug() ).orElse( null );
            if ( canonical == null ) continue;         // can't cite an un-versioned page; skip
            final CitationHandle cite = new CitationHandle(
                canonical, versionOf.apply( cs.slug() ), cs.headingPath(), cs.text(), sha256( cs.text() ) );
            out.add( new BundleSection( canonical, cs.slug(), cs.headingPath(), cs.text(), cs.denseScore(), cite ) );
            if ( out.size() >= maxSections ) break;     // top-N
        }
        return new ContextBundle( query, out );
    }

    static String sha256( final String text ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( text.getBytes( StandardCharsets.UTF_8 ) );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( String.format( "%02x", b ) );
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=DefaultBundleAssemblyServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/bundle/BundleAssemblyService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceTest.java
git commit -m "feat(bundle): assembly service — shortlist+rerank+dedup+cite+topN"
```

---

## PART C — Harness gate, then the surface

### Task 5: Harness gate — the bundle must beat dense on the corpus

Adapt the bundle service to `BundleEvalRunner.BundleRetriever` (map `api.bundle.BundleSection` → `api.eval.BundleSection`) and assert it beats the dense baseline on the frozen corpus. This is the Phase-1 exit gate. **Real-corpus tier needs the index + the reranker, so mark it `@Testcontainers(disabledWithoutDocker=true)` and wire it like `RetrievalQualitySmokeTest`'s parity gate; the always-on tier uses a fake retrieval + identity reranker proving the adapter/scoring path.**

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleHarnessAdapter.java` (maps a `BundleAssemblyService` to `BundleEvalRunner.BundleRetriever`)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleHarnessGateTest.java`

- [ ] **Step 1:** Write `BundleHarnessAdapter` — `BundleEvalRunner.BundleRetriever` that calls `assemble(query)` and maps each `api.bundle.BundleSection` to `new com.wikantik.api.eval.BundleSection(canonicalId, headingPath, text)`.
- [ ] **Step 2:** Write the always-on test: load the corpus (`BundleCorpusLoader.load(Path.of("..","eval","bundle-corpus","queries.csv"))`), build a service over a fake retrieval that returns each question's golds as chunks + an identity reranker, assert `BundleEvalRunner(adapter,5).run(corpus).overallRecall() == 1.0`.
- [ ] **Step 3:** Run → iterate to green.
- [ ] **Step 4:** Add the `@Nested @Testcontainers(disabledWithoutDocker=true)` real-corpus tier (mirror `RetrievalQualitySmokeTest.ParityGate`): load the Task-7 embedding snapshot from Phase 0, build the real `ContextRetrievalService` + `LlmSectionReranker`, run the corpus, assert `overallRecall` ≥ the dense baseline recorded in `eval/bundle-corpus/baseline-notes.md` (~0.41@5-equivalent — i.e. **strictly ≥ dense, the Phase-1 exit criterion**).
- [ ] **Step 5: Commit** `git commit -m "feat(bundle): harness gate — bundle must beat dense on the frozen corpus"`

---

### Task 6: REST endpoint `GET /api/bundle?q=`

Mirror `wikantik-rest/.../rest/PageForAgentResource.java` (`extends RestServletBase`). New `BundleResource` reads `q`, calls `BundleAssemblyService.assemble(q)`, writes the `ContextBundle` as JSON. Register it in the same place `PageForAgentResource` is registered (web.xml + any servlet wiring — grep `PageForAgentResource` to find every registration site; **per the SPA-route/web.xml gotcha, register in ALL of them**).

- [ ] **Step 1–4:** Write `BundleResourceTest` (mock `BundleAssemblyService`, assert JSON shape: `query`, `sections[].{canonicalId,slug,headingPath,text,score,citation{version,spanSha256,...}}`), then `BundleResource`, then register, then verify with a mock-MVC-style servlet test mirroring `PageForAgentResourceTest`.
- [ ] **Step 5: Commit** `git commit -m "feat(bundle): GET /api/bundle endpoint"`

---

### Task 7: MCP action `assemble_bundle`

Mirror `wikantik-knowledge/.../mcp/GetPageForAgentTool.java` (`implements McpTool`: `name()` → `"assemble_bundle"`, `inputSchema()` with required `query`, `execute(Map)` → `CallToolResult` carrying the bundle JSON). Register in `KnowledgeMcpInitializer` next to `GetPageForAgentTool` (conditioned on the assembly service being wired).

- [ ] **Step 1–4:** Write `AssembleBundleToolTest` (stub service, assert the tool returns the bundle JSON + correct schema/name), then the tool, then the registration edit, then re-run the MCP initializer test.
- [ ] **Step 5: Commit** `git commit -m "feat(bundle): assemble_bundle MCP action on /knowledge-mcp"`

---

### Task 8: Wire the service in the engine + config

- [ ] Add `RerankerConfig.fromProperties` reading `wikantik.bundle.reranker.{model,base_url,timeout_ms}` (default `gemma4:e4b`); add the keys to `ini/wikantik.properties` with comments citing the spike sweep.
- [ ] Construct `DefaultBundleAssemblyService` in the knowledge subsystem factory (canonical_id via `PageCanonicalIdsDao::findBySlug` → `Row.canonicalId()`; version via `pageManager.getPage(slug).getVersion()`; `maxSections=12`, `sectionsPerPage=5` — the spike's shortlist), and inject it into `BundleResource` + `AssembleBundleTool`.
- [ ] **Commit** `git commit -m "wire(bundle): construct + inject the bundle assembly service; reranker config"`

---

## Final verification

- [ ] `mvn -q -pl wikantik-main -am install -DskipITs` → green.
- [ ] Full IT reactor before declaring done: `mvn clean install -Pintegration-tests -fae`.
- [ ] The harness gate (Task 5) shows the bundle **≥ dense** on the frozen corpus.
- [ ] `/api/bundle?q=…` and the `assemble_bundle` MCP tool both return a ranked, cited bundle; no answer synthesis anywhere.

## Self-review against the spec

- **Bundle = ranked, de-duped, cited sections, no synthesis** (ADR-0001) → Tasks 1, 4. ✓
- **Per-page shortlist → 4B rerank** (spike sweep) → Tasks 2, 3, 8. ✓
- **Version-pinned citation handle** (ADR-0005) → Tasks 1, 4. ✓
- **Dedicated REST + MCP surface** (ADR-0003, in-process) → Tasks 6, 7. ✓
- **Scored on the harness, beats dense** → Task 5. ✓
- **Keep 0.6B + instruction first stage** (exploration verdict) → unchanged; bundle rides existing retrieval. ✓
- **think:false reranker** (cost-governed, ADR-0007) → Task 3 via `OllamaChatRequest`. ✓
