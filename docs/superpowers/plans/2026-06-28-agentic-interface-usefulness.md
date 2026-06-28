# Agentic Interface Usefulness (Theme C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-request retrieval-mode toggle to the context bundle (A3) and reposition/guard the retrieval tool descriptions (A1/A2) so a grounded agent loops less and grounds better.

**Architecture:** A3 introduces a `RetrievalMode {HYBRID,DENSE,LEXICAL}` enum threaded through a backward-compatible `assemble(query, mode)` overload; the wiring builds a `Map<RetrievalMode,SectionCandidateSource>` (LEXICAL = `HybridChunkSectionSource` with a dense-weight-0 fuser) that `DefaultBundleAssemblyService` selects from per call, degrading to the default mode when a source is unavailable. A1/A2 are pure tool-description edits, validated by a median eval run and reverted if they show no lift.

**Tech Stack:** Java 21, JUnit5, Mockito (A3); Python 3 stdlib + pytest (eval wiring); the `eval/agent-grounding` harness for validation.

## Global Constraints

- **Backward compatible:** `BundleAssemblyService.assemble(String query)` must keep working unchanged for every existing caller; the new `assemble(String, RetrievalMode)` is additive.
- **Default mode = `HYBRID`** — `assemble(query)` and a missing `?mode=`/`mode` arg both mean `HYBRID` (current behavior).
- **Wire values are lowercase:** `hybrid`, `dense`, `lexical`. The enum is uppercase. Invalid wire value → a clear error listing the three valid values.
- **Fail-soft:** if a requested mode has no available source (e.g. `LEXICAL`/`DENSE` when BM25/embeddings are down), degrade to the default-mode source and log ONE WARN — never throw, never return null (the bundle is fail-soft by design).
- **A1/A2 descriptions are revertible:** they are isolated strings; if the median eval shows no lift, revert them in one commit and keep A3.
- **Never swallow exceptions silently** (CLAUDE.md): every catch logs with context.
- **Keep `BundleServiceWiring` off the `getManager` allow-list** (DecompositionArchTest R-2) — do not add `getManager` calls in the wiring; keep the arch test green.
- Build/test: `mvn test -pl <module> -Dtest=<Class>` (no `-T`); Python: `cd eval/agent-grounding && python3 -m pytest <file> -q`.

---

### Task 1: RetrievalMode enum + per-mode selection in DefaultBundleAssemblyService

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/bundle/RetrievalMode.java`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/bundle/BundleAssemblyService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/bundle/RetrievalModeTest.java` (create), `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceTest.java` (extend)

**Interfaces:**
- Produces: `RetrievalMode {HYBRID,DENSE,LEXICAL}` with `static RetrievalMode fromWire(String)`; `BundleAssemblyService.assemble(String, RetrievalMode)` (default delegates to `assemble(String)`); a `DefaultBundleAssemblyService` constructor taking `Map<RetrievalMode,SectionCandidateSource> sources` + `RetrievalMode defaultMode`.
- Consumes: existing `SectionCandidateSource`, `SectionReranker`, `ContextBundle`.

- [ ] **Step 1: Write the failing RetrievalMode test**

`RetrievalModeTest.java`:

```java
package com.wikantik.api.bundle;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalModeTest {
    @Test void fromWire_parses_each_valid_value_case_insensitively() {
        assertEquals( RetrievalMode.HYBRID,  RetrievalMode.fromWire( "hybrid" ) );
        assertEquals( RetrievalMode.DENSE,   RetrievalMode.fromWire( "DENSE" ) );
        assertEquals( RetrievalMode.LEXICAL, RetrievalMode.fromWire( "Lexical" ) );
    }
    @Test void fromWire_null_or_blank_defaults_to_hybrid() {
        assertEquals( RetrievalMode.HYBRID, RetrievalMode.fromWire( null ) );
        assertEquals( RetrievalMode.HYBRID, RetrievalMode.fromWire( "  " ) );
    }
    @Test void fromWire_invalid_throws_listing_valid_values() {
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
            () -> RetrievalMode.fromWire( "fuzzy" ) );
        assertTrue( e.getMessage().contains( "hybrid" ) && e.getMessage().contains( "dense" )
            && e.getMessage().contains( "lexical" ), e.getMessage() );
    }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `mvn test -pl wikantik-api -Dtest=RetrievalModeTest -q`
Expected: compile failure / FAIL (RetrievalMode does not exist).

- [ ] **Step 3: Create the enum**

`RetrievalMode.java` (include the standard Apache license header copied from a sibling file in the same package):

```java
package com.wikantik.api.bundle;

/** Retrieval strategy for a context-bundle request. Wire form is the lowercase name. */
public enum RetrievalMode {
    HYBRID, DENSE, LEXICAL;

    /** Parse a wire value (case-insensitive); null/blank → HYBRID; unknown → IllegalArgumentException. */
    public static RetrievalMode fromWire( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return HYBRID;
        }
        switch ( raw.trim().toLowerCase( java.util.Locale.ROOT ) ) {
            case "hybrid":  return HYBRID;
            case "dense":   return DENSE;
            case "lexical": return LEXICAL;
            default: throw new IllegalArgumentException(
                "invalid retrieval mode '" + raw + "'; valid: hybrid, dense, lexical" );
        }
    }
}
```

- [ ] **Step 4: Add the interface overload**

In `BundleAssemblyService.java`, add a default method (keep the existing `assemble(String)` abstract):

```java
    /**
     * Assemble a bundle using the given retrieval mode. Default implementation ignores
     * mode and delegates to {@link #assemble(String)}; the production implementation
     * selects a per-mode candidate source.
     */
    default ContextBundle assemble( String query, RetrievalMode mode ) {
        return assemble( query );
    }
```

- [ ] **Step 5: Write the failing service-selection test**

In `DefaultBundleAssemblyServiceTest.java`, add (reuse the test's existing helpers for `SectionReranker`/canonicalId/version fns; if it builds the service via the single-source ctor, mirror that style):

```java
@Test
void assemble_selects_source_by_mode_and_degrades_to_default_when_missing() {
    final SectionCandidateSource denseSrc   = q -> List.of( candidate( "DensePage", "d" ) );
    final SectionCandidateSource lexicalSrc = q -> List.of( candidate( "LexPage", "l" ) );
    final java.util.Map< RetrievalMode, SectionCandidateSource > sources =
        java.util.Map.of( RetrievalMode.HYBRID, denseSrc, RetrievalMode.LEXICAL, lexicalSrc );
    final DefaultBundleAssemblyService svc = new DefaultBundleAssemblyService(
        sources, RetrievalMode.HYBRID, (q, s) -> s, slug -> java.util.Optional.empty(),
        slug -> 0, 12 );

    // explicit mode routes to its source
    assertEquals( "LexPage", svc.assemble( "x", RetrievalMode.LEXICAL ).sections().get( 0 ).slug() );
    // DENSE has no source → degrade to default (HYBRID) source
    assertEquals( "DensePage", svc.assemble( "x", RetrievalMode.DENSE ).sections().get( 0 ).slug() );
    // no-mode call uses default
    assertEquals( "DensePage", svc.assemble( "x" ).sections().get( 0 ).slug() );
}
```

(Add a small `candidate(slug, text)` helper if the test class lacks one — build a `CandidateSection` the same way the existing tests do; check the class's existing fixtures first and reuse them.)

- [ ] **Step 6: Run, verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=DefaultBundleAssemblyServiceTest -q`
Expected: FAIL (no Map constructor).

- [ ] **Step 7: Implement the Map-based selection**

In `DefaultBundleAssemblyService.java`: add fields `private final Map<RetrievalMode,SectionCandidateSource> sources;` and `private final RetrievalMode defaultMode;`. Add the new constructor; make the existing single-`source` constructor delegate to it with `Map.of(RetrievalMode.HYBRID, source)` + `HYBRID`. Refactor `assemble(String)` to call `assemble(query, defaultMode)`, and implement:

```java
    public DefaultBundleAssemblyService( final Map< RetrievalMode, SectionCandidateSource > sources,
                                         final RetrievalMode defaultMode,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections ) {
        this.sources = Map.copyOf( sources );
        this.defaultMode = defaultMode;
        this.reranker = reranker;
        this.canonicalIdOf = canonicalIdOf;
        this.versionOf = versionOf;
        this.maxSections = maxSections;
    }

    @Override
    public ContextBundle assemble( final String query ) {
        return assemble( query, defaultMode );
    }

    @Override
    public ContextBundle assemble( final String query, final RetrievalMode mode ) {
        SectionCandidateSource src = sources.get( mode );
        if ( src == null ) {
            LOG.warn( "Retrieval mode {} has no wired source; degrading to default {}", mode, defaultMode );
            src = sources.get( defaultMode );
        }
        final List< CandidateSection > ranked = reranker.rerank( query, src.candidates( query ) );
        // ... rest of the existing assemble() body, using `ranked` exactly as before ...
    }
```

(Move the existing `assemble(String)` body into `assemble(String, RetrievalMode)` verbatim from the `reranker.rerank(...)` line onward; only the `source` reference becomes the per-mode `src`. Update the single-source constructor to delegate. Add `import com.wikantik.api.bundle.RetrievalMode;` and `java.util.Map`.)

- [ ] **Step 8: Run both test classes**

Run: `mvn test -pl wikantik-api -Dtest=RetrievalModeTest -q` then `mvn test -pl wikantik-main -Dtest=DefaultBundleAssemblyServiceTest -q`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/bundle/RetrievalMode.java \
        wikantik-api/src/main/java/com/wikantik/api/bundle/BundleAssemblyService.java \
        wikantik-api/src/test/java/com/wikantik/api/bundle/RetrievalModeTest.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceTest.java
git commit -m "feat(bundle): RetrievalMode enum + per-mode source selection (A3 core)"
```

---

### Task 2: Wire the per-mode source map (SearchWiringHelper → WikiEngine → BundleServiceWiring)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java` (`buildBundleSource` → returns a map)
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (the `bundleSectionSource` field/accessors + the `BundleServiceWiring.build` call ~line 2043)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java` (`build` takes the map)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java` (extend)

**Interfaces:**
- Consumes: `RetrievalMode`, `DefaultBundleAssemblyService(Map, RetrievalMode, …)` from Task 1; existing `DenseChunkSectionSource`, `HybridChunkSectionSource`, `HybridFuser`, `LuceneBm25ChunkIndex`.
- Produces: `BundleServiceWiring.build(ContextRetrievalService, Map<RetrievalMode,SectionCandidateSource>, PageCanonicalIdsDao, PageManager, Properties)`; `SearchWiringHelper.buildBundleSourceMap(...) → Map<RetrievalMode,SectionCandidateSource>`; `WikiEngine.bundleSectionSources()` / `setBundleSectionSources(Map)`.

- [ ] **Step 1: Write the failing wiring test**

In `BundleServiceWiringTest.java`, add a test asserting `build(...)` with a map degrades correctly and selects by mode. If the test currently passes a single `denseSource`, update those call sites to a `Map.of(RetrievalMode.HYBRID, denseSource)` and add:

```java
@Test
void build_with_mode_map_routes_each_mode() {
    final SectionCandidateSource dense   = q -> List.of();
    final SectionCandidateSource lexical = q -> List.of();
    final var map = java.util.Map.of( RetrievalMode.HYBRID, dense, RetrievalMode.DENSE, dense,
                                      RetrievalMode.LEXICAL, lexical );
    final BundleAssemblyService svc = BundleServiceWiring.build(
        stubRetrieval(), map, null, null, new java.util.Properties() );
    assertNotNull( svc );
    // null/empty map → page-gated fallback, still non-null
    assertNotNull( BundleServiceWiring.build( stubRetrieval(), java.util.Map.of(), null, null, new java.util.Properties() ) );
}
```

(Reuse the test's existing `stubRetrieval()`/retrieval stub; if it does not exist, build a minimal `ContextRetrievalService` stub returning an empty `RetrievalResult` — mirror however the class already stubs retrieval.)

- [ ] **Step 2: Run, verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest -q`
Expected: FAIL (build signature mismatch).

- [ ] **Step 3: Change `BundleServiceWiring.build` to take the map**

Replace the `denseSource` parameter with `Map<RetrievalMode,SectionCandidateSource> sourceMap`. Build the assembly service from the map; when the map is null/empty, fall back to a page-gated map:

```java
    public static BundleAssemblyService build( final ContextRetrievalService retrieval,
                                               final Map< RetrievalMode, SectionCandidateSource > sourceMap,
                                               final PageCanonicalIdsDao dao,
                                               final PageManager pageManager,
                                               final Properties props ) {
        if ( retrieval == null ) {
            LOG.debug( "ContextRetrievalService not yet wired — bundle assembly service unavailable" );
            return null;
        }
        final Map< RetrievalMode, SectionCandidateSource > sources =
            ( sourceMap == null || sourceMap.isEmpty() )
                ? Map.of( RetrievalMode.HYBRID, new RetrievalSectionSource( retrieval, sectionsPerPageFrom( props ) ) )
                : sourceMap;
        final SectionReranker reranker = rerankerFor( props );
        final Function< String, Optional< String > > canonicalIdOf = slug ->
            dao == null ? Optional.empty()
                        : dao.findBySlug( slug ).map( PageCanonicalIdsDao.Row::canonicalId );
        final Function< String, Integer > versionOf = slug -> {
            if ( pageManager == null ) return 0;
            final Page p = pageManager.getPage( slug );
            return p == null ? 0 : p.getVersion();
        };
        LOG.info( "Bundle assembly service wired (modes={}, reranker={}, maxSections={})",
            sources.keySet(), reranker instanceof LlmSectionReranker ? "on" : "off", MAX_SECTIONS );
        return new DefaultBundleAssemblyService(
            sources, RetrievalMode.HYBRID, reranker, canonicalIdOf, versionOf, MAX_SECTIONS );
    }
```

(Add imports for `RetrievalMode` and `Map`. Remove the now-unused `denseEnabled` local.)

- [ ] **Step 4: Make `SearchWiringHelper.buildBundleSource` return the map**

Rename to `buildBundleSourceMap` returning `Map<RetrievalMode,SectionCandidateSource>`. Always build the dense source; when BM25 is enabled and its index builds, also build the hybrid (dense+BM25) and lexical (dense-weight-0 fuser) sources. Replace the body's `return bundleSource;` region with:

```java
        final com.wikantik.knowledge.bundle.DenseChunkSectionSource dense =
            new com.wikantik.knowledge.bundle.DenseChunkSectionSource( embedder, vectorIndex, chunkRepo, denseTopK );
        final java.util.Map< RetrievalMode, com.wikantik.knowledge.bundle.SectionCandidateSource > map =
            new java.util.EnumMap<>( RetrievalMode.class );
        map.put( RetrievalMode.DENSE, dense );
        map.put( RetrievalMode.HYBRID, dense );   // overwritten below if BM25 builds

        if ( Boolean.parseBoolean( props.getProperty( "wikantik.bundle.bm25.enabled", "false" ) ) ) {
            try {
                final String bm25Analyzer = props.getProperty( "wikantik.bundle.bm25.analyzer", "standard" );
                final com.wikantik.search.hybrid.LuceneBm25ChunkIndex bm25Index =
                    com.wikantik.search.hybrid.LuceneBm25ChunkIndex.fromDataSource( ds,
                        com.wikantik.search.hybrid.LuceneBm25ChunkIndex.analyzerFor( bm25Analyzer ) );
                final double bm25Weight  = com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.bm25.bm25_weight", 0.5 );
                final double denseWeight = com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.bm25.dense_weight", 1.5 );
                final int rrfK     = com.wikantik.util.TextUtil.getIntegerProperty( props, "wikantik.bundle.bm25.rrf_k", 20 );
                final int truncate = com.wikantik.util.TextUtil.getIntegerProperty( props, "wikantik.bundle.bm25.truncate", 20 );
                final com.wikantik.search.hybrid.HybridFuser hybridFuser =
                    new com.wikantik.search.hybrid.HybridFuser( rrfK, bm25Weight, denseWeight, truncate );
                final com.wikantik.search.hybrid.HybridFuser lexicalFuser =
                    new com.wikantik.search.hybrid.HybridFuser( rrfK, bm25Weight, 0.0, truncate );  // dense weight 0 → BM25-only ranking
                map.put( RetrievalMode.HYBRID, new com.wikantik.knowledge.bundle.HybridChunkSectionSource(
                    embedder, vectorIndex, bm25Index, chunkRepo, hybridFuser, denseTopK ) );
                map.put( RetrievalMode.LEXICAL, new com.wikantik.knowledge.bundle.HybridChunkSectionSource(
                    embedder, vectorIndex, bm25Index, chunkRepo, lexicalFuser, denseTopK ) );
                LOG.info( "Bundle sources: HYBRID+DENSE+LEXICAL (bm25_w={}, dense_w={}, rrfK={}, trunc={}), bm25 chunks={}",
                    bm25Weight, denseWeight, rrfK, truncate, bm25Index.size() );
            } catch ( final RuntimeException e ) {
                LOG.warn( "BM25 chunk index build failed; LEXICAL/HYBRID degrade to dense-only: {}", e.getMessage(), e );
            }
        }
        return map;
```

Update the method signature/return type and its caller in `SearchWiringHelper` accordingly (the caller currently does `engine.setBundleSectionSource(buildBundleSource(...))` → `engine.setBundleSectionSources(buildBundleSourceMap(...))`).

- [ ] **Step 5: Update WikiEngine field + accessors + the build call**

In `WikiEngine.java`: change the field at ~line 1849 from `SectionCandidateSource bundleSectionSource` to `Map<RetrievalMode,SectionCandidateSource> bundleSectionSources` (volatile); rename `setBundleSectionSource`/`bundleSectionSource()` to `setBundleSectionSources(Map)`/`bundleSectionSources()`; at ~line 2043 pass `bundleSectionSources()` to `BundleServiceWiring.build(...)`. Add imports. Grep for any other references to the old accessor names and update them (there should be only SearchWiringHelper + this call site).

- [ ] **Step 6: Run wiring test + compile main**

Run: `mvn -q -pl wikantik-main test-compile` then `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest -q`
Expected: BUILD SUCCESS, PASS.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java
git commit -m "feat(bundle): wire per-mode source map (HYBRID/DENSE/LEXICAL) (A3 wiring)"
```

---

### Task 3: Expose mode on /api/bundle and assemble_bundle

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/BundleResource.java` (~line 74–99)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/AssembleBundleTool.java` (~line 71 schema, ~line 80–93 execute)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/BundleResourceTest.java`, `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/AssembleBundleToolTest.java`

**Interfaces:**
- Consumes: `RetrievalMode.fromWire(String)`, `BundleAssemblyService.assemble(String, RetrievalMode)`.
- Produces: `GET /api/bundle?q=&mode=` and `assemble_bundle` `mode` arg honoring the three modes; invalid value → error.

- [ ] **Step 1: Write the failing REST + tool tests**

In `BundleResourceTest.java` add: a request with `mode=lexical` calls `svc.assemble(q, RetrievalMode.LEXICAL)` (verify via Mockito); a request with `mode=bogus` returns HTTP 400 with a body mentioning valid modes; a request with no `mode` calls `assemble(q, RetrievalMode.HYBRID)`. (The existing tests likely stub `assemble(q)`; switch the stub to `assemble(eq(q), any())` / specific mode.)

In `AssembleBundleToolTest.java` add: `mode="dense"` → `service.assemble(query, RetrievalMode.DENSE)`; missing `mode` → `RetrievalMode.HYBRID`; `mode="bogus"` → an error result mentioning valid modes (no `assemble` call).

- [ ] **Step 2: Run, verify they fail**

Run: `mvn test -pl wikantik-rest -Dtest=BundleResourceTest -q` and `mvn test -pl wikantik-knowledge -Dtest=AssembleBundleToolTest -q`
Expected: FAIL.

- [ ] **Step 3: Implement BundleResource mode parsing**

After reading `q` (~line 74), parse mode and branch on invalid:

```java
        final RetrievalMode mode;
        try {
            mode = RetrievalMode.fromWire( req.getParameter( "mode" ) );
        } catch ( final IllegalArgumentException e ) {
            resp.setStatus( 400 );
            resp.setContentType( "application/json" );
            resp.getWriter().write( "{\"error\":\"" + e.getMessage().replace( "\"", "'" ) + "\"}" );
            return;
        }
```

and change the assemble call (~line 99) from `svc.assemble( q )` to `svc.assemble( q, mode )`. Add `import com.wikantik.api.bundle.RetrievalMode;`.

- [ ] **Step 4: Implement AssembleBundleTool mode arg**

Add `mode` to the input schema `properties` (~line 71) as an optional string (description: `"Retrieval mode: hybrid (default), dense, or lexical."`). In `execute` (~line 80–93), after the query check:

```java
            final RetrievalMode mode;
            try {
                mode = RetrievalMode.fromWire( McpToolUtils.getString( arguments, "mode" ) );
            } catch ( final IllegalArgumentException e ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
            }
            final ContextBundle bundle = service.assemble( query, mode );
```

Add `import com.wikantik.api.bundle.RetrievalMode;`. (If `McpToolUtils.getString` throws on a missing key rather than returning null, fetch it defensively so a missing `mode` yields `null` → `HYBRID`; mirror how other optional args are read in this tool.)

- [ ] **Step 5: Run the tests**

Run: `mvn test -pl wikantik-rest -Dtest=BundleResourceTest -q` and `mvn test -pl wikantik-knowledge -Dtest=AssembleBundleToolTest -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/BundleResource.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/AssembleBundleTool.java \
        wikantik-rest/src/test/java/com/wikantik/rest/BundleResourceTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/AssembleBundleToolTest.java
git commit -m "feat(bundle): expose mode on /api/bundle + assemble_bundle (A3 surface)"
```

---

### Task 4: A1/A2 — reposition + guardrail tool descriptions

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/AssembleBundleTool.java` (description string)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/RetrieveContextTool.java` (description string ~line 120–123)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java` (server-instructions blurb ~line 210)

**Interfaces:** none (description strings only; no logic, no signature change).

- [ ] **Step 1: Reposition assemble_bundle as the primary answer-grounding tool**

In `AssembleBundleTool.java`, set the tool description to:

```
"PRIMARY tool for answering a question from the wiki. Returns a ranked, de-duplicated, "
+ "version-pinned, citation-bearing set of section texts to ground your answer on. Prefer this "
+ "for how/why/what questions. Ground your answer ONLY in the returned sections and cite them. "
+ "Optional 'mode' arg: hybrid (default), dense, or lexical. Does NOT synthesize an answer."
```

- [ ] **Step 2: Reframe retrieve_context as discovery + guardrails**

In `RetrieveContextTool.java` (~line 120), set the description to:

```
"Discover which wiki pages/sections are relevant to a query. Returns {query, pages: [{name, url, "
+ "score, summary, cluster, tags, contributingChunks, relatedPages, author, lastModified}], totalMatched}. "
+ "To COMPOSE AN ANSWER, prefer assemble_bundle (cited section text). One call usually suffices — raise "
+ "maxPages/chunksPerPage rather than re-querying with reworded queries. Ground any claim only in the "
+ "returned chunk text; do not add specifics that are not present."
```

- [ ] **Step 3: Update the server-instructions blurb**

In `KnowledgeMcpInitializer.java` (~line 210), change the "For wiki content use retrieve_context (primary RAG)…" phrasing so `assemble_bundle` is named the primary answer-grounding tool and `retrieve_context` is for page/section discovery. Keep the rest of the blurb intact.

- [ ] **Step 4: Compile**

Run: `mvn -q -pl wikantik-knowledge test-compile`
Expected: BUILD SUCCESS. (No behavioral test — these are description strings; the eval in Task 6 is the validation.)

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/AssembleBundleTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/RetrieveContextTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java
git commit -m "docs(mcp): reposition assemble_bundle as primary + retrieve_context guardrails (A1/A2)"
```

---

### Task 5: Wire the eval harness --lexical to mode=lexical

**Files:**
- Modify: `eval/agent-grounding/bundle_client.py` (`fetch_bundle`)
- Modify: `eval/agent-grounding/arms.py` (the `grounded_mcp` `assemble_bundle` call + the `grounded_bundle` bundle fetch)
- Test: `eval/agent-grounding/test_bundle_client.py`

**Interfaces:**
- Consumes: the A3 `?mode=` param (Task 3).
- Produces: `fetch_bundle(base_url, query, lexical, http)` sends `&mode=lexical` when `lexical=True`; the MCP arm passes `mode="lexical"` to `assemble_bundle` when lexical.

- [ ] **Step 1: Write the failing test**

In `test_bundle_client.py`, add: with `lexical=True` and an injected `http` that captures the URL, assert the requested URL contains `mode=lexical`; with `lexical=False`, assert it does NOT contain `mode=` (or contains `mode=hybrid` — pick one; default to omitting it).

- [ ] **Step 2: Run, verify it fails**

Run: `cd eval/agent-grounding && python3 -m pytest test_bundle_client.py -q`
Expected: FAIL.

- [ ] **Step 3: Implement**

In `bundle_client.fetch_bundle`, build `params = {"q": query}` and add `params["mode"] = "lexical"` when `lexical` is truthy (leave it off otherwise so default HYBRID applies). In `arms.py`, where the `grounded_mcp` arm calls the `assemble_bundle` MCP tool, pass `{"query": ..., "mode": "lexical"}` when `cfg.lexical` (else omit `mode`); the `grounded_bundle` arm already routes through `fetch_bundle(..., lexical=cfg.lexical)` — confirm `cfg.lexical` is threaded.

- [ ] **Step 4: Run the harness tests**

Run: `cd eval/agent-grounding && python3 -m pytest -q`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add eval/agent-grounding/bundle_client.py eval/agent-grounding/arms.py eval/agent-grounding/test_bundle_client.py
git commit -m "feat(eval): wire --lexical to bundle mode=lexical (A3 unblock)"
```

---

### Task 6: Validation — median eval run, dense-gap investigation, keep/revert decision

**Files:** none (validation); possibly a revert commit; an investigation note under `eval/agent-grounding/runs/`.

**Interfaces:** consumes the deployed A1/A2 descriptions (Task 4) and A3 (Tasks 1–3). NOTE: the eval hits **prod** — the description changes must be deployed/pushed for the eval to see them. If the in-session MCP targets prod and prod runs from `main`, confirm whether a deploy is required; if the descriptions are not yet live on prod, this task measures only what is live — record that caveat and run after deploy.

- [ ] **Step 1: Confirm prerequisites**

Inference host UP (`curl -sS -m 10 http://inference.jakefear.com:11434/api/tags >/dev/null && echo UP`); `ANTHROPIC_API_KEY` + `MCP_ACCESS_KEYS` available (`source .env`); the A1/A2 descriptions are live on the eval target.

- [ ] **Step 2: Run a median before/after eval**

Run a 3-sample run: `cd eval/agent-grounding && python3 run_eval.py --run-id theme-c-$(date -u +%Y%m%dT%H%M%SZ) --samples 3`, then `python3 grade.py runs/<id>` and `python3 report.py runs/<id>`. Compare `scorecard.md` + `interface-findings.md` against the post-Theme-A baseline (`grounded_mcp` 1.625; loop counts in interface-findings).

- [ ] **Step 3: Decide keep vs revert (A1/A2)**

KEEP the description edits if grounded_mcp mean ≥ prior AND `retrieve_context` loop count and/or the mcp-vs-bundle gap dropped. REVERT (one commit reverting Task 4) if grounded_mcp regressed or nothing improved — A3 stays regardless. Record the decision + numbers in `eval/agent-grounding/runs/theme-c-after.md`.

- [ ] **Step 4: Dense-gap investigation**

Call `assemble_bundle` (MCP) for "What are the three dense index backend options and which is the production default?" and inspect the returned section text: if the 3-backend table is truncated → open a follow-up (real bundle bug); if returned whole → record "model-synthesis/variance, no fix" in `theme-c-after.md`. No code change here unless truncation is found.

- [ ] **Step 5: Commit the validation record**

```bash
git add eval/agent-grounding/runs/theme-c-after.md
git commit -m "docs(eval): Theme C validation — A3 + A1/A2 median run + dense-gap finding"
```

---

## Self-Review

**Spec coverage:** RetrievalMode enum + overload → Task 1 ✓; per-mode selection + fail-soft degrade → Task 1 (service) + Task 2 (wiring) ✓; LEXICAL via dense-weight-0 fuser → Task 2 Step 4 ✓; `?mode=` + `assemble_bundle` mode arg + invalid→error → Task 3 ✓; eval `--lexical` unblock → Task 5 ✓; A1/A2 reposition+guardrails → Task 4 ✓; revertible-if-no-lift → Task 6 Step 3 ✓; median validation → Task 6 ✓; dense-gap investigation → Task 6 Step 4 ✓; backward-compat assemble(String) → Task 1 (default method + delegating ctor) ✓; fail-soft + never-throw → Global Constraints + Task 1 Step 7 ✓.

**Placeholder scan:** none — all code shown; the two "mirror the existing fixture/stub" notes (DefaultBundleAssemblyServiceTest `candidate(...)`, BundleServiceWiringTest `stubRetrieval()`) point at concrete existing helpers to reuse, not unwritten code.

**Type/name consistency:** `RetrievalMode {HYBRID,DENSE,LEXICAL}` + `fromWire` used identically across Tasks 1/3/5; `assemble(String, RetrievalMode)` consistent; `Map<RetrievalMode,SectionCandidateSource>` + `RetrievalMode.HYBRID` default consistent across Tasks 1/2; `setBundleSectionSources`/`bundleSectionSources()` rename consistent in Task 2; wire values lowercase `hybrid/dense/lexical` consistent in Tasks 1/3/5.
