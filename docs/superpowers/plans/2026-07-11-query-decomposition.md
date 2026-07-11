# Query Decomposition (Structure-Conditional) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add server-side, structure-conditional query decomposition to the context bundle so cross-page comparative ("X vs Y") questions retrieve *both* sides instead of crowding one out — default-off, fail-closed, measured against the RELATIONAL eval set.

**Architecture:** A cheap lexical heuristic gates an LLM planner (local Ollama, `think:false`, same client idiom as the KG extractor) that emits 2–4 focused sub-queries for genuinely multi-part queries (passthrough otherwise). Each sub-query — plus the original — runs through the *existing* `SectionCandidateSource`; the resulting candidate lists are N-ary-RRF-fused into one `SectionCandidates` that flows into the unchanged rerank → dedup → cut → cite → coverage pipeline. All new code lives inside `com.wikantik.knowledge.bundle` (its `SectionCandidates`/`CandidateSection` records are package-private). When the feature flag is off, `assemble` is byte-identical to today.

**Tech Stack:** Java 25, JUnit 5, Mockito, `java.net.http.HttpClient` + Gson (reused from `OllamaEntityExtractor`), Flexmark-unrelated. No new Maven deps.

## Global Constraints

- **Default-off, byte-identical when off.** `wikantik.bundle.decomposition.enabled` defaults `false`; with it off, `DefaultBundleAssemblyService.assemble` must execute the exact current code path (existing constructors delegate to a passthrough planner). A characterization test asserts identical output flag-off.
- **Fail-closed everywhere.** Any planner failure — Ollama down, non-2xx, malformed/empty JSON, timeout, interrupt — degrades to the single-pass result (`List.of(query)` → no fusion). Never throw to the caller, never return an error bundle. Log `LOG.warn(...)` with context on failure (never a silent empty catch — repo rule).
- **Public surface unchanged.** No change to `BundleAssemblyService`, `ContextBundle`, `BundleSection`, `BundleCoverage`, the `assemble_bundle` MCP tool, or `GET /api/bundle`. Sub-query provenance in the bundle is explicitly OUT of scope this phase (deferred — it would touch public API types).
- **In-package.** New classes go in `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/` (except config, which may live alongside). Tests in `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/`.
- **ADR-0001: never answer synthesis.** The planner decomposes queries only; it never generates content or answers.
- **Every new Maven module needs mockito-core** — N/A here (wikantik-main already has it).
- **Bounded cost.** Sub-query count clamped to `[2, max_subqueries]` (default 4); the LLM is only consulted when the lexical heuristic fires.

---

### Task 1: QueryStructureHeuristic (lexical multi-part gate)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/QueryStructureHeuristic.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/QueryStructureHeuristicTest.java`

**Interfaces:**
- Consumes: nothing (pure).
- Produces: `final class QueryStructureHeuristic { static boolean looksMultiPart(String query); }` — liberal recall gate. Returns `true` if the query carries a comparative/conjunctive marker worth an LLM planner call; `false` for obviously single-intent queries (so the LLM is skipped). Over-firing is acceptable (the LLM is the precision filter); under-firing loses a decomposition opportunity, so bias toward `true` on ambiguity.

Rationale (from the 2026-07-11 RELATIONAL measurement): the addressable failures are cross-page comparative/conjunctive — r01 "decide between BM25 and dense", r02 "…rerank and what is the default", r06 "…affect RAG quality and what pattern", r08 "canary … differ from blue-green and when". Markers must catch these.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryStructureHeuristicTest {

    @Test void comparativeMarkersFire() {
        assertTrue( QueryStructureHeuristic.looksMultiPart( "how does canary deployment differ from blue-green and when should I use each" ) );
        assertTrue( QueryStructureHeuristic.looksMultiPart( "how does graph RAG differ from standard RAG" ) );
        assertTrue( QueryStructureHeuristic.looksMultiPart( "canary vs blue-green deployment" ) );
        assertTrue( QueryStructureHeuristic.looksMultiPart( "compare BM25 and dense retrieval" ) );
        assertTrue( QueryStructureHeuristic.looksMultiPart( "what is the difference between a hub and an article" ) );
    }

    @Test void conjunctiveTwoPartFires() {
        assertTrue( QueryStructureHeuristic.looksMultiPart( "how does Wikantik hybrid retrieval decide between BM25 and dense results" ) );
        assertTrue( QueryStructureHeuristic.looksMultiPart( "what configuration enables the Knowledge Graph rerank and what is the default" ) );
    }

    @Test void singleIntentDoesNotFire() {
        assertFalse( QueryStructureHeuristic.looksMultiPart( "what embedding model does the retrieval harness use" ) );
        assertFalse( QueryStructureHeuristic.looksMultiPart( "how do I rebuild the search index" ) );
        assertFalse( QueryStructureHeuristic.looksMultiPart( "canary deployment" ) );
    }

    @Test void nullAndBlankAreSafe() {
        assertFalse( QueryStructureHeuristic.looksMultiPart( null ) );
        assertFalse( QueryStructureHeuristic.looksMultiPart( "   " ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=QueryStructureHeuristicTest -q`
Expected: FAIL — `QueryStructureHeuristic` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.wikantik.knowledge.bundle;

import java.util.List;

/**
 * Cheap lexical gate: does a query look multi-part enough to be worth an LLM
 * decomposition planner call? Liberal by design — the {@link QueryPlanner} is
 * the precision filter (it returns passthrough for single-intent). Over-firing
 * only costs an extra planner call that returns no sub-queries; under-firing
 * silently loses a decomposition opportunity, so we bias toward firing.
 *
 * <p>Markers chosen from the 2026-07-11 RELATIONAL measurement: the addressable
 * failure class is cross-page comparative/conjunctive questions.
 */
final class QueryStructureHeuristic {

    private QueryStructureHeuristic() {}

    /** Substring markers (matched on a lowercased, space-padded query). */
    private static final List< String > MARKERS = List.of(
        " vs ", " vs.", " versus ", " differ", " difference between ",
        " compare ", " compared to ", " comparison ", " between ",
        " and what ", " and when ", " and how ", " and why ", " and which ",
        " as well as ", " both " );

    static boolean looksMultiPart( final String query ) {
        if ( query == null ) return false;
        final String q = " " + query.trim().toLowerCase() + " ";
        if ( q.isBlank() ) return false;
        for ( final String m : MARKERS ) {
            if ( q.contains( m ) ) return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=QueryStructureHeuristicTest -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/QueryStructureHeuristic.java wikantik-main/src/test/java/com/wikantik/knowledge/bundle/QueryStructureHeuristicTest.java
git commit -m "feat(bundle): lexical multi-part heuristic for query decomposition gate"
```

---

### Task 2: SubQueryFusion (N-ary RRF over SectionCandidates)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SubQueryFusion.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/SubQueryFusionTest.java`

**Interfaces:**
- Consumes: `SectionCandidates` (record: `List<CandidateSection> sections, double topSimilarity, boolean denseCosineScale`); `CandidateSection` (record: `String slug, List<String> headingPath, String text, double denseScore`). Both package-private — this class MUST be in-package.
- Produces: `final class SubQueryFusion { SubQueryFusion(double rrfK); SectionCandidates fuse(List<SectionCandidates> perQuery); }`
  - N-ary weighted RRF (uniform weight 1.0 per list) keyed by `(slug, headingPath)`: each list contributes `1/(rrfK + rank)` (1-based rank within that list) to a section's fused score; scores sum across lists; sections sorted by fused score desc.
  - The retained `CandidateSection` per key keeps the **max `denseScore`** seen across lists (best dense cosine).
  - Result `topSimilarity` = max of inputs' `topSimilarity`; `denseCosineScale` = `true` iff every non-empty input had it `true` (so `KneeCutoff` only trusts a cosine-scale fused list when all sources are cosine-scale).
  - Empty/singleton input handled: `fuse(List.of())` → `SectionCandidates.of(List.of(), -1.0)`; `fuse(List.of(one))` → returns `one` unchanged.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubQueryFusionTest {

    private static CandidateSection sec( String slug, String heading, double dense ) {
        return new CandidateSection( slug, List.of( heading ), slug + "/" + heading + " body", dense );
    }

    @Test void fusesTwoListsInterleavingBothSides() {
        // sub-query A surfaces the "canary" side; sub-query B the "blue-green" side.
        SectionCandidates a = SectionCandidates.of(
            List.of( sec( "Canary", "Traffic Splitting", 0.80 ), sec( "Canary", "Analysis", 0.70 ) ), 0.80, true );
        SectionCandidates b = SectionCandidates.of(
            List.of( sec( "BlueGreen", "When canary wins", 0.78 ), sec( "BlueGreen", "Cutover", 0.60 ) ), 0.78, true );

        SectionCandidates fused = new SubQueryFusion( 60 ).fuse( List.of( a, b ) );

        // both top-ranked sections from each side survive and rank above the tails
        List< String > slugs = fused.sections().stream().map( CandidateSection::slug ).toList();
        assertTrue( slugs.indexOf( "Canary" ) >= 0 && slugs.indexOf( "BlueGreen" ) >= 0 );
        // rank-1 of each list (rrf 1/61 each) outranks rank-2 of each list (1/62)
        assertEquals( "Traffic Splitting", fused.sections().get( 0 ).headingPath().get( 0 ) );
        assertEquals( "When canary wins", fused.sections().get( 1 ).headingPath().get( 0 ) );
    }

    @Test void keepsMaxDenseScoreAcrossLists() {
        SectionCandidates a = SectionCandidates.of( List.of( sec( "P", "H", 0.40 ) ), 0.40, true );
        SectionCandidates b = SectionCandidates.of( List.of( sec( "P", "H", 0.90 ) ), 0.90, true );
        SectionCandidates fused = new SubQueryFusion( 60 ).fuse( List.of( a, b ) );
        assertEquals( 1, fused.sections().size() );          // deduped by (slug, headingPath)
        assertEquals( 0.90, fused.sections().get( 0 ).denseScore(), 1e-9 );
        assertEquals( 0.90, fused.topSimilarity(), 1e-9 );
    }

    @Test void denseCosineScaleFalseIfAnyInputFalse() {
        SectionCandidates a = SectionCandidates.of( List.of( sec( "P", "H", 0.4 ) ), 0.4, true );
        SectionCandidates b = SectionCandidates.of( List.of( sec( "Q", "H", 0.0 ) ), -1.0, false );
        assertTrue( ! new SubQueryFusion( 60 ).fuse( List.of( a, b ) ).denseCosineScale() );
    }

    @Test void emptyAndSingleton() {
        assertEquals( 0, new SubQueryFusion( 60 ).fuse( List.of() ).sections().size() );
        SectionCandidates one = SectionCandidates.of( List.of( sec( "P", "H", 0.5 ) ), 0.5, true );
        assertEquals( one, new SubQueryFusion( 60 ).fuse( List.of( one ) ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=SubQueryFusionTest -q`
Expected: FAIL — `SubQueryFusion` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.wikantik.knowledge.bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N-ary reciprocal-rank fusion over per-sub-query {@link SectionCandidates}.
 * Each section is keyed by {@code (slug, headingPath)}; each input list
 * contributes {@code 1/(rrfK + rank)} (1-based rank) to a section's fused score,
 * summed across lists. Generalises {@link com.wikantik.search.hybrid.HybridFuser}
 * (which is hard-coded to two lists) to the N sub-queries a decomposition emits.
 */
final class SubQueryFusion {

    private final double rrfK;

    SubQueryFusion( final double rrfK ) {
        this.rrfK = rrfK > 0 ? rrfK : 60;
    }

    SectionCandidates fuse( final List< SectionCandidates > perQuery ) {
        if ( perQuery == null || perQuery.isEmpty() ) {
            return SectionCandidates.of( List.of(), -1.0 );
        }
        if ( perQuery.size() == 1 ) return perQuery.get( 0 );

        final Map< SectionKey, CandidateSection > bestByKey = new LinkedHashMap<>();
        final Map< SectionKey, Double > scoreByKey = new LinkedHashMap<>();
        double topSim = -1.0;
        boolean cosineScale = true;
        boolean anyNonEmpty = false;

        for ( final SectionCandidates cand : perQuery ) {
            if ( cand == null ) continue;
            topSim = Math.max( topSim, cand.topSimilarity() );
            final List< CandidateSection > list = cand.sections();
            if ( !list.isEmpty() ) {
                anyNonEmpty = true;
                if ( !cand.denseCosineScale() ) cosineScale = false;
            }
            for ( int rank = 0; rank < list.size(); rank++ ) {
                final CandidateSection cs = list.get( rank );
                final SectionKey key = new SectionKey( cs.slug(), cs.headingPath() );
                scoreByKey.merge( key, 1.0 / ( rrfK + rank + 1 ), Double::sum );
                bestByKey.merge( key, cs, ( a, b ) -> a.denseScore() >= b.denseScore() ? a : b );
            }
        }
        if ( !anyNonEmpty ) return SectionCandidates.of( List.of(), topSim, cosineScale );

        final List< CandidateSection > fused = new ArrayList<>( bestByKey.values() );
        fused.sort( ( a, b ) -> Double.compare(
            scoreByKey.get( new SectionKey( b.slug(), b.headingPath() ) ),
            scoreByKey.get( new SectionKey( a.slug(), a.headingPath() ) ) ) );
        return SectionCandidates.of( fused, topSim, cosineScale );
    }
}
```

Note: `SectionKey` is the existing package-private `(slug, headingPath)` record used by `DefaultBundleAssemblyService` dedup. If it is a nested/private type, promote it to a package-private top-level record `SectionKey(String slug, List<String> headingPath)` in its own file first (mechanical extraction; the implementer confirms via `grep -rn "class SectionKey\|record SectionKey" wikantik-main/src/main/java`).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=SubQueryFusionTest -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SubQueryFusion.java wikantik-main/src/test/java/com/wikantik/knowledge/bundle/SubQueryFusionTest.java
git commit -m "feat(bundle): N-ary RRF fusion of per-sub-query section candidates"
```

---

### Task 3: QueryPlanner + LlmQueryPlanner (fail-closed Ollama) + config

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/QueryPlanner.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/LlmQueryPlanner.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleDecompositionConfig.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/LlmQueryPlannerTest.java`

**Interfaces:**
- Consumes: `java.net.http.HttpClient`, `com.wikantik.knowledge.extraction.OllamaChatRequest.body(model, systemPrompt, userPrompt, keepAlive)` (returns `Map<String,Object>` with `stream:false, format:"json", think:false`).
- Produces:
  - `interface QueryPlanner { List<String> plan(String query); }` — returns 2–4 sub-queries for a multi-part query, or `List.of(query)` for single-intent / any failure (passthrough is the fail-closed default). Never returns empty, never throws.
  - `final class PassthroughQueryPlanner implements QueryPlanner` — always `List.of(query)`. The default wired when the feature is off.
  - `final class LlmQueryPlanner implements QueryPlanner` — `LlmQueryPlanner(HttpClient http, BundleDecompositionConfig config)`.
  - `record BundleDecompositionConfig(boolean enabled, String model, String baseUrl, long timeoutMs, int maxSubqueries, double rrfK)` with `static BundleDecompositionConfig fromProperties(Properties props)` and `static final String PREFIX = "wikantik.bundle.decomposition.";`.

Prompt contract for `LlmQueryPlanner` (mirror `OllamaEntityExtractor.callOllama`: POST `stripTrailingSlash(baseUrl)+"/api/chat"`, `HttpRequest` with `timeout(Duration.ofMillis(timeoutMs))`, Gson parse `message.content`):
- System prompt: instructs JSON-only output `{"subqueries": ["...", "..."]}`, 2–4 focused sub-queries one per part/entity for a genuinely multi-part question, or `{"subqueries": []}` for single-intent. Never answers. (ADR-0001.)
- Parse: read `subqueries` array; trim; drop blanks; if size < 2 → passthrough `List.of(query)`; else clamp to `maxSubqueries` and return (do NOT prepend the original here — the assembler adds the original list to the fusion set).
- **Fail-closed** (return `List.of(query)`, `LOG.warn` with context): non-2xx status, `IOException`, `InterruptedException` (re-interrupt the thread), JSON parse failure, missing/empty `subqueries`, timeout.

- [ ] **Step 1: Write the failing tests** (fail-closed is the spec — cover every failure mode)

```java
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmQueryPlannerTest {

    private static BundleDecompositionConfig cfg() {
        return new BundleDecompositionConfig( true, "gemma4-assist:latest", "http://localhost:11434", 4000, 4, 60 );
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse< String > resp( int status, String body ) {
        HttpResponse< String > r = mock( HttpResponse.class );
        when( r.statusCode() ).thenReturn( status );
        when( r.body() ).thenReturn( body );
        return r;
    }

    @Test void decomposesMultiPartQuery() throws Exception {
        HttpClient http = mock( HttpClient.class );
        when( http.send( any(), any() ) ).thenReturn( resp( 200,
            "{\"message\":{\"content\":\"{\\\"subqueries\\\":[\\\"canary deployment traffic splitting\\\",\\\"blue-green deployment cutover\\\"]}\"}}" ) );
        List< String > out = new LlmQueryPlanner( http, cfg() ).plan( "canary vs blue-green" );
        assertEquals( List.of( "canary deployment traffic splitting", "blue-green deployment cutover" ), out );
    }

    @Test void singleIntentReturnsPassthrough() throws Exception {
        HttpClient http = mock( HttpClient.class );
        when( http.send( any(), any() ) ).thenReturn( resp( 200,
            "{\"message\":{\"content\":\"{\\\"subqueries\\\":[]}\"}}" ) );
        assertEquals( List.of( "one thing" ), new LlmQueryPlanner( http, cfg() ).plan( "one thing" ) );
    }

    @Test void non2xxFailsClosed() throws Exception {
        HttpClient http = mock( HttpClient.class );
        when( http.send( any(), any() ) ).thenReturn( resp( 500, "boom" ) );
        assertEquals( List.of( "q" ), new LlmQueryPlanner( http, cfg() ).plan( "q" ) );
    }

    @Test void malformedJsonFailsClosed() throws Exception {
        HttpClient http = mock( HttpClient.class );
        when( http.send( any(), any() ) ).thenReturn( resp( 200, "not json at all" ) );
        assertEquals( List.of( "q" ), new LlmQueryPlanner( http, cfg() ).plan( "q" ) );
    }

    @Test void ioExceptionFailsClosed() throws Exception {
        HttpClient http = mock( HttpClient.class );
        when( http.send( any(), any() ) ).thenThrow( new java.io.IOException( "conn refused" ) );
        assertEquals( List.of( "q" ), new LlmQueryPlanner( http, cfg() ).plan( "q" ) );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=LlmQueryPlannerTest -q`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Implement `QueryPlanner`, `PassthroughQueryPlanner`, `BundleDecompositionConfig`, `LlmQueryPlanner`**

`QueryPlanner.java`:
```java
package com.wikantik.knowledge.bundle;

import java.util.List;

/** Decomposes a multi-part query into focused sub-queries; passthrough on single-intent or any failure. */
interface QueryPlanner {
    /** @return 2–4 sub-queries for a multi-part query, else {@code List.of(query)}. Never empty, never throws. */
    List< String > plan( String query );
}
```

`PassthroughQueryPlanner.java`:
```java
package com.wikantik.knowledge.bundle;

import java.util.List;

/** The disabled/default planner: every query is single-intent. */
final class PassthroughQueryPlanner implements QueryPlanner {
    @Override public List< String > plan( final String query ) { return List.of( query ); }
}
```

`BundleDecompositionConfig.java` — follow the `EntityExtractorConfig.fromProperties` idiom (private static tolerant getters; blank/missing → default; never throws). Defaults: `enabled=false`, `model="gemma4-assist:latest"`, `baseUrl="http://inference.jakefear.com:11434"`, `timeoutMs=4000`, `maxSubqueries=4`, `rrfK=60`. Keys under `PREFIX`: `enabled, model, base_url, timeout_ms, max_subqueries, rrf_k`.

`LlmQueryPlanner.java` — mirror `OllamaEntityExtractor.callOllama`:
```java
package com.wikantik.knowledge.bundle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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

final class LlmQueryPlanner implements QueryPlanner {

    private static final Logger LOG = LogManager.getLogger( LlmQueryPlanner.class );
    private static final Gson GSON = new Gson();
    private static final String SYSTEM = """
        You are a query decomposition planner for a document retrieval system. \
        You NEVER answer the question and NEVER generate content. \
        If the user question is comparative or multi-part (asks about two or more \
        distinct topics/entities that live in separate documents), output JSON \
        {"subqueries": ["...", "..."]} with 2 to 4 focused single-topic sub-queries, \
        one per part. If it is a single-intent question, output {"subqueries": []}. \
        Output JSON only.""";

    private final HttpClient http;
    private final BundleDecompositionConfig config;

    LlmQueryPlanner( final HttpClient http, final BundleDecompositionConfig config ) {
        this.http = http;
        this.config = config;
    }

    @Override
    public List< String > plan( final String query ) {
        try {
            final String url = stripTrailingSlash( config.baseUrl() ) + "/api/chat";
            final String body = GSON.toJson( OllamaChatRequest.body( config.model(), SYSTEM, query, null ) );
            final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
                .timeout( Duration.ofMillis( config.timeoutMs() ) )
                .header( "Content-Type", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( body ) )
                .build();
            final HttpResponse< String > resp = http.send( req, HttpResponse.BodyHandlers.ofString() );
            if ( resp.statusCode() / 100 != 2 ) {
                LOG.warn( "Query planner non-2xx {} for '{}'; single-pass", resp.statusCode(), query );
                return List.of( query );
            }
            return parse( resp.body(), query );
        } catch ( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            LOG.warn( "Query planner interrupted for '{}'; single-pass", query );
            return List.of( query );
        } catch ( final RuntimeException | java.io.IOException e ) {
            LOG.warn( "Query planner failed for '{}': {}; single-pass", query, e.getMessage() );
            return List.of( query );
        }
    }

    private List< String > parse( final String responseBody, final String query ) {
        try {
            final JsonObject outer = GSON.fromJson( responseBody, JsonObject.class );
            final String content = outer.getAsJsonObject( "message" ).get( "content" ).getAsString();
            final JsonObject inner = GSON.fromJson( content, JsonObject.class );
            final var arr = inner.getAsJsonArray( "subqueries" );
            final List< String > subs = new ArrayList<>();
            if ( arr != null ) {
                for ( final var el : arr ) {
                    final String s = el.getAsString().trim();
                    if ( !s.isBlank() ) subs.add( s );
                }
            }
            if ( subs.size() < 2 ) return List.of( query );
            return subs.size() > config.maxSubqueries() ? subs.subList( 0, config.maxSubqueries() ) : subs;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Query planner unparseable output for '{}': {}; single-pass", query, e.getMessage() );
            return List.of( query );
        }
    }

    private static String stripTrailingSlash( final String s ) {
        return s != null && s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=LlmQueryPlannerTest -q`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/QueryPlanner.java wikantik-main/src/main/java/com/wikantik/knowledge/bundle/PassthroughQueryPlanner.java wikantik-main/src/main/java/com/wikantik/knowledge/bundle/LlmQueryPlanner.java wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleDecompositionConfig.java wikantik-main/src/test/java/com/wikantik/knowledge/bundle/LlmQueryPlannerTest.java
git commit -m "feat(bundle): fail-closed LLM query planner + decomposition config"
```

---

### Task 4: Wire decomposition into DefaultBundleAssemblyService (default-off, byte-identical)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceDecompositionTest.java`

**Interfaces:**
- Consumes: `QueryPlanner`, `QueryStructureHeuristic.looksMultiPart`, `SubQueryFusion`, the existing `SectionCandidateSource` map, `SectionCandidates`.
- Produces: a new constructor overload that accepts `(QueryPlanner planner, SubQueryFusion fusion, boolean decompositionEnabled)` in addition to the current params. **Every existing constructor must delegate to the new one with `new PassthroughQueryPlanner()`, a `SubQueryFusion(60)`, and `decompositionEnabled=false`** — so all current call sites keep today's behavior exactly.

Decomposition flow, spliced immediately after the first-pass `src.candidates(query)` and BEFORE `reranker.rerank(...)`:
```java
SectionCandidates cand = src.candidates( query );
if ( decompositionEnabled && QueryStructureHeuristic.looksMultiPart( query ) ) {
    final List< String > subs = planner.plan( query );      // fail-closed → [query]
    if ( subs.size() > 1 ) {
        final List< SectionCandidates > perQuery = new ArrayList<>();
        perQuery.add( cand );                                // include the original pass
        for ( final String sq : subs ) perQuery.add( src.candidates( sq ) );
        cand = fusion.fuse( perQuery );
        LOG.info( "Bundle decomposition: '{}' -> {} sub-queries, fused {} sections",
            query, subs.size(), cand.sections().size() );
    }
}
// ... unchanged: rerank(query, cand.sections()) -> cut -> dedup/cite loop -> coverage
```

- [ ] **Step 1: Write the failing test** (off = identical path; on = fuses sub-query candidates)

```java
package com.wikantik.knowledge.bundle;

import com.wikantik.api.bundle.ContextBundle;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultBundleAssemblyServiceDecompositionTest {

    // A stub source that records every query it is asked for and returns one section per query.
    private static final class RecordingSource implements SectionCandidateSource {
        final List< String > seen = new ArrayList<>();
        @Override public SectionCandidates candidates( final String query ) {
            seen.add( query );
            return SectionCandidates.of(
                List.of( new CandidateSection( "P-" + query, List.of( query ), query + " body", 0.7 ) ), 0.7, true );
        }
    }

    private static DefaultBundleAssemblyService svc( SectionCandidateSource src, QueryPlanner planner, boolean on ) {
        // Use the project's existing simplest constructor path; wire canonicalId/version resolvers to identity stubs.
        // (Implementer: match the real constructor signature — see DefaultBundleAssemblyService and its existing tests
        // for the canonicalIdOf / versionOf / coverageCalc / reranker / knee args; pass IDENTITY reranker + disabled knee.)
        return DefaultBundleAssemblyServiceTestSupport.withDecomposition( src, planner, on );
    }

    @Test void offDoesNotConsultPlannerAndAsksOnlyOriginalQuery() {
        RecordingSource src = new RecordingSource();
        QueryPlanner planner = q -> { throw new AssertionError( "planner must not be called when disabled" ); };
        ContextBundle b = svc( src, planner, false ).assemble( "canary vs blue-green" );
        assertEquals( List.of( "canary vs blue-green" ), src.seen );     // single pass only
        assertTrue( b.sections().size() >= 1 );
    }

    @Test void onFusesSubQueryCandidates() {
        RecordingSource src = new RecordingSource();
        QueryPlanner planner = q -> List.of( "canary side", "blue-green side" );
        ContextBundle b = svc( src, planner, true ).assemble( "canary vs blue-green" );
        // original + 2 sub-queries all retrieved
        assertTrue( src.seen.contains( "canary vs blue-green" ) );
        assertTrue( src.seen.contains( "canary side" ) );
        assertTrue( src.seen.contains( "blue-green side" ) );
        // both sides present in the fused bundle
        List< String > slugs = b.sections().stream().map( s -> s.slug() ).toList();
        assertTrue( slugs.stream().anyMatch( s -> s.contains( "canary side" ) ) );
        assertTrue( slugs.stream().anyMatch( s -> s.contains( "blue-green side" ) ) );
    }

    @Test void onButSingleIntentPlannerPassthroughIsSinglePass() {
        RecordingSource src = new RecordingSource();
        QueryPlanner planner = q -> List.of( q );        // passthrough
        svc( src, planner, true ).assemble( "single topic only" );
        assertEquals( List.of( "single topic only" ), src.seen );
    }
}
```

Note: the implementer creates a tiny package-private `DefaultBundleAssemblyServiceTestSupport.withDecomposition(src, planner, on)` factory (or inlines the real constructor call) that builds the service with an IDENTITY reranker, disabled knee, `canonicalIdOf = slug -> Optional.of(slug)`, `versionOf = slug -> 1`, and `BundleCoverageCalculator.defaults()` — matching the real constructor. Keep it in the test source tree.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=DefaultBundleAssemblyServiceDecompositionTest -q`
Expected: FAIL — new constructor overload / support factory absent.

- [ ] **Step 3: Implement the constructor overload + decomposition splice** (per the flow above). Existing constructors delegate with a passthrough planner and `decompositionEnabled=false`.

- [ ] **Step 4: Run the new test AND the existing assembler test (guard byte-identical-off)**

Run: `mvn test -pl wikantik-main -Dtest=DefaultBundleAssemblyServiceDecompositionTest,DefaultBundleAssemblyServiceTest -q`
Expected: PASS — new decomposition tests green AND all existing `DefaultBundleAssemblyServiceTest` cases still green (proves off-path unchanged).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceDecompositionTest.java wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceTestSupport.java
git commit -m "feat(bundle): coverage-independent structure-conditional decomposition in assembler (default off)"
```

---

### Task 5: Wire config + planner construction in BundleServiceWiring

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java` (extend)

**Interfaces:**
- Consumes: `BundleDecompositionConfig.fromProperties(props)`, `LlmQueryPlanner`, `PassthroughQueryPlanner`, `SubQueryFusion`, the new `DefaultBundleAssemblyService` constructor overload.
- Produces: `build(...)` constructs the planner — `LlmQueryPlanner(HttpClient.newHttpClient(), cfg)` when `cfg.enabled()`, else `PassthroughQueryPlanner` — and passes `(planner, new SubQueryFusion(cfg.rrfK()), cfg.enabled())` into the service. The existing `LOG.info("Bundle assembly service wired (...)")` line gains a `decomposition={}` field.

- [ ] **Step 1: Write the failing test**

```java
// Add to BundleServiceWiringTest:
@Test void decompositionDisabledByDefaultWiresPassthrough() {
    Properties p = new Properties();                     // no decomposition keys
    BundleAssemblyService svc = BundleServiceWiring.build( retrieval, sourceMap, dao, pageManager, p );
    // smoke: service builds and a single-intent assemble works without an Ollama call
    assertNotNull( svc );
    assertEquals( "q", svc.assemble( "q" ).query() );
}

@Test void decompositionConfigParsedFromProperties() {
    Properties p = new Properties();
    p.setProperty( "wikantik.bundle.decomposition.enabled", "true" );
    p.setProperty( "wikantik.bundle.decomposition.max_subqueries", "3" );
    BundleDecompositionConfig cfg = BundleDecompositionConfig.fromProperties( p );
    assertTrue( cfg.enabled() );
    assertEquals( 3, cfg.maxSubqueries() );
}
```

(Match the existing `BundleServiceWiringTest` fixtures for `retrieval/sourceMap/dao/pageManager` — reuse whatever mock setup the class already has.)

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest -q`
Expected: FAIL — config keys not read / passthrough not wired.

- [ ] **Step 3: Implement** — add `decompositionEnabled`/config read + planner construction + extend the log line. Wire `HttpClient.newHttpClient()` only when enabled (don't allocate a client on the disabled path).

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java
git commit -m "feat(bundle): wire query-decomposition config + planner (default off)"
```

---

### Task 6: Register the config keys in the properties reference

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`

**Interfaces:** none (documentation/registration only).

- [ ] **Step 1: Add the commented default block** (match the style of the existing `wikantik.bundle.knee.*` / `wikantik.bundle.coverage.*` entries):

```properties
# --- Query decomposition (structure-conditional, default OFF) ---
# When enabled, comparative/multi-part queries are split into 2-4 sub-queries
# by the local LLM (fail-closed to single-pass on any error), retrieved
# separately, and RRF-fused. Targets cross-page comparative ("X vs Y") queries.
#wikantik.bundle.decomposition.enabled = false
#wikantik.bundle.decomposition.model = gemma4-assist:latest
#wikantik.bundle.decomposition.base_url = http://inference.jakefear.com:11434
#wikantik.bundle.decomposition.timeout_ms = 4000
#wikantik.bundle.decomposition.max_subqueries = 4
#wikantik.bundle.decomposition.rrf_k = 60
```

- [ ] **Step 2: Verify it compiles/packages** (no code change; just confirm the module still builds)

Run: `mvn -q -pl wikantik-main -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "docs(bundle): register wikantik.bundle.decomposition.* config keys"
```

---

## Post-implementation measurement (controller-run, not a subagent task)

After all tasks pass and the full `wikantik-main` suite is green, run the acceptance A/B (this is the gate the roadmap defers to `[OWNER]`):

1. Ensure a working dense embedder (local `qwen3-embedding:0.6b` on `localhost:11434`) and a working chat model for the planner (`gemma4-assist:latest`) — point `wikantik.bundle.decomposition.base_url` at whichever host serves the chat model.
2. Control: decomposition off → run `scratchpad/measure_relational.py` (per-hop RELATIONAL recall). Baseline is already 0.61 hop-recall, 3/9 full multi-hop.
3. Treatment: `wikantik.bundle.decomposition.enabled=true`, restart, re-run.
4. **Acceptance:** treatment recovers cross-page comparative questions (r01/r02/r06/r08) → target full multi-hop ≥ 6/9 and hop-recall ≥ +0.10, with the single-intent eval set (existing `measure_recall.py`) unchanged (no regression). Record the result in `eval/bundle-corpus/baseline-notes.md` and update `RoadmapAgenticRetrieval`. If the lift doesn't clear the gate, the finding (not the feature) ships: keep it off and document why.

## Global self-review notes

- **Byte-identical-off** is the load-bearing safety property — Task 4's guard (existing `DefaultBundleAssemblyServiceTest` stays green) is the proof.
- **Fail-closed** is proven by Task 3's five failure-mode tests + Task 4's passthrough test.
- **Public surface** (`BundleAssemblyService`/`ContextBundle`/MCP/REST) is never touched — verified by the absence of any change under `wikantik-api`, `wikantik-knowledge/.../mcp`, `wikantik-rest`.
- Sub-query **provenance** in the bundle is deferred (would touch public API types); note it as a follow-up on the roadmap.
