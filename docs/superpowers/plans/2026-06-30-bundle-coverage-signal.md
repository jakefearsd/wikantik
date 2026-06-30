# Bundle Coverage Signal + Tool-Routing Guidance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `coverage` block to the RAG context bundle (so the agent knows when to stop re-querying or escalate) and steer count/enumeration questions to the structured tools via tool descriptions.

**Architecture:** A new `BundleCoverage` record rides on `ContextBundle`. The true top dense cosine is threaded out of the candidate sources via a `SectionCandidates` carrier (the default hybrid path currently discards it). A `BundleCoverageCalculator` in wikantik-main derives a `strong/partial/weak/unknown` label from that cosine plus section/page counts. The two agent surfaces (`assemble_bundle` MCP, `/api/bundle` REST) recompute counts after view-gating and serialize the block for free.

**Tech Stack:** Java 21 records, JUnit 5, Mockito, Gson (MCP serialization), Maven multi-module reactor (wikantik-api → wikantik-main → wikantik-knowledge / wikantik-rest).

## Global Constraints

- House style: 4-space indent, spaces inside generics and parens (`List< String >`, `foo( x )`) — match each file's existing style exactly.
- Every new `.java` file starts with the Apache license header (copy verbatim from any sibling file in the same package).
- `wikantik-api` stays logic-free: `BundleCoverage` is a pure data record; all threshold/confidence logic lives in `wikantik-main`.
- Never swallow exceptions with empty catch blocks — at least `LOG.warn()` with context.
- Config reads use `com.wikantik.util.TextUtil.getDoubleProperty( props, key, default )`; guard against null `props`.
- Confidence labels are the exact lowercase strings: `strong`, `partial`, `weak`, `unknown`.
- Provisional thresholds: `wikantik.bundle.coverage.strong_similarity`=`0.55`, `wikantik.bundle.coverage.partial_similarity`=`0.40`.
- TDD: every behavior change starts with a failing test. Compile-check the touched module with `mvn -q -pl <module> -am test-compile` before running tests; one full `mvn clean install -DskipITs` plus the IT reactor only at the end.

---

### Task 1: `BundleCoverage` record (wikantik-api)

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/bundle/BundleCoverage.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/bundle/BundleCoverageTest.java`

**Interfaces:**
- Produces: `record BundleCoverage(int sectionCount, int distinctPageCount, double topSimilarity, String confidence)` with `public static final String STRONG/PARTIAL/WEAK/UNKNOWN`, `static BundleCoverage empty()`, `static int distinctPages(List<BundleSection>)`, `static BundleCoverage recount(BundleCoverage original, List<BundleSection> gatedSections)`.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.api.bundle;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundleCoverageTest {

    private static BundleSection sec( final String canonical, final String slug ) {
        return new BundleSection( canonical, slug, List.of( "H" ), "t", 0.9,
                new CitationHandle( canonical, 1, List.of( "H" ), "t", "h" ) );
    }

    @Test
    void emptyIsUnknownAndZero() {
        final BundleCoverage c = BundleCoverage.empty();
        assertEquals( 0, c.sectionCount() );
        assertEquals( 0, c.distinctPageCount() );
        assertEquals( -1.0, c.topSimilarity() );
        assertEquals( BundleCoverage.UNKNOWN, c.confidence() );
    }

    @Test
    void distinctPagesCountsUniqueCanonicalIds() {
        assertEquals( 2, BundleCoverage.distinctPages(
                List.of( sec( "A", "Pa" ), sec( "A", "Pa2" ), sec( "B", "Pb" ) ) ) );
    }

    @Test
    void distinctPagesIgnoresNullCanonicalIds() {
        assertEquals( 1, BundleCoverage.distinctPages(
                List.of( sec( "A", "Pa" ), sec( null, "Pn" ) ) ) );
    }

    @Test
    void recountFixesCountsButPreservesCosineAndConfidence() {
        final BundleCoverage original = new BundleCoverage( 12, 5, 0.8, BundleCoverage.STRONG );
        final BundleCoverage r = BundleCoverage.recount( original, List.of( sec( "A", "Pa" ) ) );
        assertEquals( 1, r.sectionCount() );
        assertEquals( 1, r.distinctPageCount() );
        assertEquals( 0.8, r.topSimilarity() );
        assertEquals( BundleCoverage.STRONG, r.confidence() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-api test -Dtest=BundleCoverageTest`
Expected: FAIL — `BundleCoverage` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
/* <Apache license header — copy verbatim from BundleSection.java> */
package com.wikantik.api.bundle;

import java.util.List;
import java.util.Objects;

/** Coverage signal for a context bundle: how much, how corroborated, how confident.
 *  Pure data — confidence/threshold logic lives in wikantik-main (BundleCoverageCalculator). */
public record BundleCoverage(
    int sectionCount, int distinctPageCount, double topSimilarity, String confidence
) {
    public static final String STRONG = "strong";
    public static final String PARTIAL = "partial";
    public static final String WEAK = "weak";
    public static final String UNKNOWN = "unknown";

    public static BundleCoverage empty() {
        return new BundleCoverage( 0, 0, -1.0, UNKNOWN );
    }

    /** Distinct non-null canonical_ids across the sections — thin vs corroborated coverage. */
    public static int distinctPages( final List< BundleSection > sections ) {
        return (int) sections.stream()
            .map( BundleSection::canonicalId )
            .filter( Objects::nonNull )
            .distinct().count();
    }

    /** Recompute counts over a (post-ACL-gate) section subset, preserving the retrieval-derived
     *  topSimilarity + confidence (cosine is unaffected by view filtering). See design §5. */
    public static BundleCoverage recount( final BundleCoverage original,
                                          final List< BundleSection > gatedSections ) {
        return new BundleCoverage( gatedSections.size(), distinctPages( gatedSections ),
            original.topSimilarity(), original.confidence() );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-api test -Dtest=BundleCoverageTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/bundle/BundleCoverage.java \
        wikantik-api/src/test/java/com/wikantik/api/bundle/BundleCoverageTest.java
git commit -m "feat(bundle): BundleCoverage record (api) — counts + cosine + confidence label"
```

---

### Task 2: `ContextBundle` carries coverage (wikantik-api)

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/bundle/ContextBundle.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/bundle/BundleTypesTest.java` (add cases)

**Interfaces:**
- Consumes: `BundleCoverage` (Task 1).
- Produces: `record ContextBundle(String query, List<BundleSection> sections, BundleCoverage coverage)` with a back-compat secondary constructor `ContextBundle(String query, List<BundleSection> sections)` that defaults coverage to `BundleCoverage.empty()`.

- [ ] **Step 1: Write the failing test** (append to `BundleTypesTest`)

```java
@Test
void twoArgConstructorDefaultsToEmptyCoverage() {
    final ContextBundle b = new ContextBundle( "q", java.util.List.of() );
    assertNotNull( b.coverage() );
    assertEquals( BundleCoverage.UNKNOWN, b.coverage().confidence() );
}

@Test
void threeArgConstructorRetainsCoverage() {
    final BundleCoverage cov = new BundleCoverage( 3, 2, 0.7, BundleCoverage.PARTIAL );
    final ContextBundle b = new ContextBundle( "q", java.util.List.of(), cov );
    assertEquals( cov, b.coverage() );
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-api test -Dtest=BundleTypesTest`
Expected: FAIL — `ContextBundle` has no `coverage()` / no 3-arg constructor.

- [ ] **Step 3: Write minimal implementation** (replace the record body)

```java
/** The unit RAG-as-a-Service returns: a ranked, de-duplicated, cited set of sections + coverage. */
public record ContextBundle( String query, List< BundleSection > sections, BundleCoverage coverage ) {
    public ContextBundle {
        if ( query == null ) {
            throw new IllegalArgumentException( "query must not be null" );
        }
        sections = sections == null ? List.of() : List.copyOf( sections );
        coverage = coverage == null ? BundleCoverage.empty() : coverage;
    }

    /** Back-compat: bundle without an explicit coverage signal (defaults to empty/unknown). */
    public ContextBundle( final String query, final List< BundleSection > sections ) {
        this( query, sections, BundleCoverage.empty() );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-api test -Dtest=BundleTypesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/bundle/ContextBundle.java \
        wikantik-api/src/test/java/com/wikantik/api/bundle/BundleTypesTest.java
git commit -m "feat(bundle): ContextBundle carries coverage; back-compat 2-arg ctor"
```

---

### Task 3: `SectionCandidates` carrier — thread top cosine out of the sources (wikantik-main)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SectionCandidates.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SectionCandidateSource.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DenseChunkSectionSource.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/HybridChunkSectionSource.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/RetrievalSectionSource.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DenseChunkSectionSourceTest.java`, `HybridChunkSectionSourceTest.java`, `HybridChunkSectionSourceCandidatesTest.java` (update `.candidates()` callers + add topSimilarity assertions)

**Interfaces:**
- Produces: `record SectionCandidates(List<CandidateSection> sections, double topSimilarity)`; `SectionCandidateSource.candidates(String)` now returns `SectionCandidates`.
- Behavior: dense source → `topSimilarity` = max real cosine (`-1` if none); hybrid source → max dense cosine *before* RRF (`-1` if embedder down); retrieval source → `-1`.

- [ ] **Step 1: Create the carrier**

```java
/* <Apache license header> */
package com.wikantik.knowledge.bundle;

import java.util.List;

/** Candidate sections for a query plus the true top dense cosine (for the coverage signal).
 *  topSimilarity is -1.0 when no dense similarity is available (e.g. embedder down,
 *  or the page-gated path which has no per-section cosine). */
record SectionCandidates( List< CandidateSection > sections, double topSimilarity ) {
    SectionCandidates {
        sections = sections == null ? List.of() : List.copyOf( sections );
    }
    static SectionCandidates of( final List< CandidateSection > sections, final double topSimilarity ) {
        return new SectionCandidates( sections, topSimilarity );
    }
}
```

- [ ] **Step 2: Change the SPI return type**

In `SectionCandidateSource.java`, change the method signature (keep `@FunctionalInterface`):

```java
    /** Candidate sections for the query (best-first) plus the top dense cosine. */
    SectionCandidates candidates( String query );
```

- [ ] **Step 3: Write the failing source tests** (update existing call sites + add cosine assertions)

In `DenseChunkSectionSourceTest`, every `source.candidates( q )` becomes `source.candidates( q ).sections()`, and add:

```java
@Test
void topSimilarityIsMaxCosine() {
    // existing fixture wiring: embedder returns a vector, index returns scored chunks.
    // The highest ScoredChunk.score() must surface as topSimilarity.
    final SectionCandidates c = source.candidates( "deploy" );
    assertEquals( c.sections().get( 0 ).denseScore(), c.topSimilarity(), 1e-9 );
}

@Test
void embedderUnavailableYieldsMinusOne() {
    when( embedder.embed( anyString() ) ).thenReturn( java.util.Optional.empty() );
    final SectionCandidates c = source.candidates( "deploy" );
    assertTrue( c.sections().isEmpty() );
    assertEquals( -1.0, c.topSimilarity() );
}
```

In `HybridChunkSectionSourceTest` / `HybridChunkSectionSourceCandidatesTest`, update `.candidates(...)` callers to `.candidates(...).sections()` and add:

```java
@Test
void topSimilarityIsMaxDenseCosineBeforeFusion() {
    // dense index returns scored chunks; the max score must surface even though
    // the fused section order uses the rank proxy.
    final SectionCandidates c = source.candidates( "deploy" );
    assertEquals( EXPECTED_MAX_DENSE_SCORE, c.topSimilarity(), 1e-9 );
}

@Test
void noDenseRankingYieldsMinusOne() {
    when( embedder.embed( anyString() ) ).thenReturn( java.util.Optional.empty() );
    final SectionCandidates c = source.candidates( "deploy" );
    assertEquals( -1.0, c.topSimilarity() );
}
```

Run: `mvn -q -pl wikantik-main -am test-compile` — Expected: FAIL (sources still return `List`).

- [ ] **Step 4: Update the three implementors**

`DenseChunkSectionSource.candidates(...)` — wrap returns:

```java
    @Override
    public SectionCandidates candidates( final String query ) {
        final Optional< float[] > qv = embedder.embed( query );
        if ( qv.isEmpty() ) {
            LOG.warn( "Query embedding unavailable; dense-chunk bundle candidates empty for '{}'", query );
            return SectionCandidates.of( List.of(), -1.0 );
        }
        final List< ScoredChunk > top = index.topKChunks( qv.get(), topK );
        if ( top.isEmpty() ) return SectionCandidates.of( List.of(), -1.0 );

        final List< UUID > ids = new ArrayList<>( top.size() );
        for ( final ScoredChunk sc : top ) ids.add( sc.chunkId() );
        final Map< UUID, MentionableChunk > byId = new LinkedHashMap<>();
        for ( final MentionableChunk c : chunkRepo.findByIds( ids ) ) byId.put( c.id(), c );

        final Map< SectionKey, CandidateSection > best = new LinkedHashMap<>();
        for ( final ScoredChunk sc : top ) {
            final MentionableChunk c = byId.get( sc.chunkId() );
            if ( c == null ) continue;
            final SectionKey k = new SectionKey( c.pageName(), c.headingPath() );
            final CandidateSection cur = best.get( k );
            if ( cur == null || sc.score() > cur.denseScore() ) {
                best.put( k, new CandidateSection( c.pageName(), c.headingPath(), c.text(), sc.score() ) );
            }
        }
        final List< CandidateSection > out = new ArrayList<>( best.values() );
        out.sort( Comparator.comparingDouble( CandidateSection::denseScore ).reversed() );
        final double topSim = out.isEmpty() ? -1.0 : out.get( 0 ).denseScore();
        return SectionCandidates.of( out, topSim );
    }
```

`HybridChunkSectionSource.candidates(...)` — capture the dense ScoredChunk list before discarding scores:

```java
    @Override
    public SectionCandidates candidates( final String query ) {
        final Optional< float[] > qv = embedder.embed( query );
        final List< ScoredChunk > dense = qv.isPresent()
            ? denseIndex.topKChunks( qv.get(), topK ) : List.of();
        final double topSim = dense.stream().mapToDouble( ScoredChunk::score ).max().orElse( -1.0 );
        final List< String > denseRanked = rankedIds( dense );
        final List< String > bm25Ranked = rankedIds( bm25Index.topKChunks( query, topK ) );
        if ( denseRanked.isEmpty() && bm25Ranked.isEmpty() ) {
            LOG.warn( "Hybrid chunk source: no dense and no BM25 candidates for '{}'", query );
            return SectionCandidates.of( List.of(), topSim );
        }
        final List< String > fusedIds = fuser.fuse( bm25Ranked, denseRanked );

        final List< UUID > ids = new ArrayList<>( fusedIds.size() );
        for ( final String s : fusedIds ) ids.add( UUID.fromString( s ) );
        final Map< UUID, MentionableChunk > byId = new LinkedHashMap<>();
        for ( final MentionableChunk c : chunkRepo.findByIds( ids ) ) byId.put( c.id(), c );

        return SectionCandidates.of( groupToSections( fusedIds, byId ), topSim );
    }
```

`RetrievalSectionSource.candidates(...)`:

```java
    @Override
    public SectionCandidates candidates( final String query ) {
        return SectionCandidates.of( assembler.assemble( retrieval.retrieve( new ContextQuery(
            query, ContextQuery.MAX_PAGES_CAP, ContextQuery.MAX_CHUNKS_PER_PAGE_CAP, null ) ) ), -1.0 );
    }
```

- [ ] **Step 5: Run the source tests**

Run: `mvn -q -pl wikantik-main test -Dtest=DenseChunkSectionSourceTest,HybridChunkSectionSourceTest,HybridChunkSectionSourceCandidatesTest`
Expected: PASS. (The `DefaultBundleAssemblyServiceTest` will not compile yet — fixed in Task 5; that's expected.)

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SectionCandidates.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SectionCandidateSource.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DenseChunkSectionSource.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/HybridChunkSectionSource.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/RetrievalSectionSource.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DenseChunkSectionSourceTest.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/HybridChunkSectionSourceTest.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/HybridChunkSectionSourceCandidatesTest.java
git commit -m "feat(bundle): thread true top dense cosine via SectionCandidates carrier"
```

---

### Task 4: `BundleCoverageCalculator` — confidence logic (wikantik-main)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleCoverageCalculator.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleCoverageCalculatorTest.java`

**Interfaces:**
- Consumes: `BundleCoverage` (Task 1), `BundleSection`.
- Produces: `BundleCoverageCalculator(double strongThreshold, double partialThreshold)`, `static BundleCoverageCalculator defaults()` (0.55 / 0.40), `BundleCoverage compute(double topSimilarity, List<BundleSection> sections)`.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.bundle;

import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BundleCoverageCalculatorTest {

    private final BundleCoverageCalculator calc = BundleCoverageCalculator.defaults(); // 0.55 / 0.40

    private static List< BundleSection > sections( final int n ) {
        return IntStream.range( 0, n ).mapToObj( i -> new BundleSection(
                "C" + i, "P" + i, List.of( "H" ), "t", 0.9,
                new CitationHandle( "C" + i, 1, List.of( "H" ), "t", "h" ) ) ).toList();
    }

    @Test
    void unavailableCosineIsUnknown() {
        assertEquals( BundleCoverage.UNKNOWN, calc.compute( -1.0, sections( 5 ) ).confidence() );
    }

    @Test
    void zeroSectionsIsWeak() {
        assertEquals( BundleCoverage.WEAK, calc.compute( 0.9, List.of() ).confidence() );
    }

    @Test
    void strongNeedsHighCosineAndThreeSections() {
        assertEquals( BundleCoverage.STRONG, calc.compute( 0.60, sections( 3 ) ).confidence() );
        // high cosine but too few sections → not strong
        assertEquals( BundleCoverage.PARTIAL, calc.compute( 0.60, sections( 2 ) ).confidence() );
    }

    @Test
    void partialBetweenThresholds() {
        assertEquals( BundleCoverage.PARTIAL, calc.compute( 0.45, sections( 5 ) ).confidence() );
    }

    @Test
    void belowPartialIsWeak() {
        assertEquals( BundleCoverage.WEAK, calc.compute( 0.30, sections( 5 ) ).confidence() );
    }

    @Test
    void populatesCountsAndCosine() {
        final BundleCoverage c = calc.compute( 0.72, sections( 4 ) );
        assertEquals( 4, c.sectionCount() );
        assertEquals( 4, c.distinctPageCount() );
        assertEquals( 0.72, c.topSimilarity() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=BundleCoverageCalculatorTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
/* <Apache license header> */
package com.wikantik.knowledge.bundle;

import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;

import java.util.List;

/** Derives the bundle confidence label from the top dense cosine + section/page counts.
 *  Thresholds are provisional/tunable (design §4); lives in wikantik-main so wikantik-api
 *  stays logic-free. */
final class BundleCoverageCalculator {

    static final double DEFAULT_STRONG = 0.55;
    static final double DEFAULT_PARTIAL = 0.40;

    private final double strongThreshold;
    private final double partialThreshold;

    BundleCoverageCalculator( final double strongThreshold, final double partialThreshold ) {
        this.strongThreshold = strongThreshold;
        this.partialThreshold = partialThreshold;
    }

    static BundleCoverageCalculator defaults() {
        return new BundleCoverageCalculator( DEFAULT_STRONG, DEFAULT_PARTIAL );
    }

    BundleCoverage compute( final double topSimilarity, final List< BundleSection > sections ) {
        final int n = sections.size();
        final int pages = BundleCoverage.distinctPages( sections );
        final String confidence;
        if ( topSimilarity < 0 ) {
            confidence = BundleCoverage.UNKNOWN;
        } else if ( n == 0 ) {
            confidence = BundleCoverage.WEAK;
        } else if ( topSimilarity >= strongThreshold && n >= 3 ) {
            confidence = BundleCoverage.STRONG;
        } else if ( topSimilarity >= partialThreshold ) {
            confidence = BundleCoverage.PARTIAL;
        } else {
            confidence = BundleCoverage.WEAK;
        }
        return new BundleCoverage( n, pages, topSimilarity, confidence );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=BundleCoverageCalculatorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleCoverageCalculator.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleCoverageCalculatorTest.java
git commit -m "feat(bundle): BundleCoverageCalculator — confidence label from cosine + counts"
```

---

### Task 5: Wire coverage into `DefaultBundleAssemblyService` + `BundleServiceWiring` (wikantik-main)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceTest.java`, `BundleServiceWiringTest.java`

**Interfaces:**
- Consumes: `SectionCandidates` (Task 3), `BundleCoverageCalculator` (Task 4).
- Produces: `assemble(query, mode)` returns a `ContextBundle` whose `coverage()` is computed from the source's `topSimilarity` + final section list. New base-constructor param `BundleCoverageCalculator coverageCalc`; convenience constructors pass `BundleCoverageCalculator.defaults()`.

- [ ] **Step 1: Write the failing test** (append to `DefaultBundleAssemblyServiceTest`)

```java
@Test
void assemblePopulatesCoverageFromSourceTopSimilarity() {
    // Source stub returns 3 sections with topSimilarity 0.7 (>= strong threshold).
    final SectionCandidateSource src = q -> SectionCandidates.of( List.of(
            new CandidateSection( "Pa", List.of( "H1" ), "t1", 0.9 ),
            new CandidateSection( "Pb", List.of( "H2" ), "t2", 0.8 ),
            new CandidateSection( "Pc", List.of( "H3" ), "t3", 0.7 ) ), 0.7 );
    final DefaultBundleAssemblyService svc = new DefaultBundleAssemblyService(
            src, ( q, s ) -> s, slug -> java.util.Optional.of( slug ), slug -> 1, 12,
            BundleCoverageCalculator.defaults() );

    final ContextBundle b = svc.assemble( "q" );
    assertEquals( 3, b.coverage().sectionCount() );
    assertEquals( 0.7, b.coverage().topSimilarity(), 1e-9 );
    assertEquals( BundleCoverage.STRONG, b.coverage().confidence() );
}
```

> Note: this test uses the **source-based** convenience constructor with an explicit calculator. If the existing test file constructs the service via a constructor whose arity changes, update those call sites too (see Step 3).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=DefaultBundleAssemblyServiceTest`
Expected: FAIL — `candidates()` now returns `SectionCandidates` (service still treats it as `List`), and the new constructor/`coverage()` wiring is missing.

- [ ] **Step 3: Update the service**

Add the field + thread it through all three constructors. The map-based (base) constructor gains a `coverageCalc` param; the two convenience constructors delegate with `BundleCoverageCalculator.defaults()`:

```java
    private final BundleCoverageCalculator coverageCalc;

    public DefaultBundleAssemblyService( final ContextRetrievalService retrieval,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections,
                                         final int sectionsPerPage ) {
        this( new RetrievalSectionSource( retrieval, sectionsPerPage ),
              reranker, canonicalIdOf, versionOf, maxSections );
    }

    public DefaultBundleAssemblyService( final SectionCandidateSource source,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections ) {
        this( source, reranker, canonicalIdOf, versionOf, maxSections,
              BundleCoverageCalculator.defaults() );
    }

    public DefaultBundleAssemblyService( final SectionCandidateSource source,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections,
                                         final BundleCoverageCalculator coverageCalc ) {
        this( Map.of( RetrievalMode.HYBRID, source ), RetrievalMode.HYBRID,
              reranker, canonicalIdOf, versionOf, maxSections, coverageCalc );
    }

    public DefaultBundleAssemblyService( final Map< RetrievalMode, SectionCandidateSource > sources,
                                         final RetrievalMode defaultMode,
                                         final SectionReranker reranker,
                                         final Function< String, Optional< String > > canonicalIdOf,
                                         final Function< String, Integer > versionOf,
                                         final int maxSections,
                                         final BundleCoverageCalculator coverageCalc ) {
        Objects.requireNonNull( sources.get( defaultMode ), "defaultMode must be present in sources" );
        this.sources = Map.copyOf( sources );
        this.defaultMode = defaultMode;
        this.reranker = reranker;
        this.canonicalIdOf = canonicalIdOf;
        this.versionOf = versionOf;
        this.maxSections = maxSections;
        this.coverageCalc = coverageCalc;
    }
```

Update `assemble(query, mode)` to unpack the carrier and compute coverage:

```java
    @Override
    public ContextBundle assemble( final String query, final RetrievalMode mode ) {
        SectionCandidateSource src = sources.get( mode );
        if ( src == null ) {
            LOG.warn( "Retrieval mode {} has no wired source; degrading to default {}", mode, defaultMode );
            src = sources.get( defaultMode );
        }
        final SectionCandidates cand = src.candidates( query );
        final List< CandidateSection > ranked = reranker.rerank( query, cand.sections() );

        final Set< SectionKey > seen = new LinkedHashSet<>();
        final List< BundleSection > out = new ArrayList<>();
        for ( final CandidateSection cs : ranked ) {
            if ( !seen.add( new SectionKey( cs.slug(), cs.headingPath() ) ) ) continue;
            final String canonical = canonicalIdOf.apply( cs.slug() ).orElse( null );
            if ( canonical == null ) continue;
            final CitationHandle cite = new CitationHandle(
                canonical, versionOf.apply( cs.slug() ), cs.headingPath(), cs.text(), sha256( cs.text() ) );
            out.add( new BundleSection( canonical, cs.slug(), cs.headingPath(), cs.text(), cs.denseScore(), cite ) );
            if ( out.size() >= maxSections ) break;
        }
        return new ContextBundle( query, out, coverageCalc.compute( cand.topSimilarity(), out ) );
    }
```

Add the import: `import com.wikantik.api.bundle.BundleCoverage;` is not needed here (calculator returns it), but ensure `BundleCoverageCalculator` is in-package (no import needed).

- [ ] **Step 4: Update `BundleServiceWiring.build()` to pass a config-driven calculator**

Add a helper and use the 7-arg base constructor:

```java
    /** Coverage confidence thresholds from config (provisional defaults 0.55 / 0.40). */
    static BundleCoverageCalculator coverageCalcFrom( final Properties props ) {
        if ( props == null ) return BundleCoverageCalculator.defaults();
        final double strong = com.wikantik.util.TextUtil.getDoubleProperty(
            props, "wikantik.bundle.coverage.strong_similarity", BundleCoverageCalculator.DEFAULT_STRONG );
        final double partial = com.wikantik.util.TextUtil.getDoubleProperty(
            props, "wikantik.bundle.coverage.partial_similarity", BundleCoverageCalculator.DEFAULT_PARTIAL );
        return new BundleCoverageCalculator( strong, partial );
    }
```

And in `build()`, replace the return statement:

```java
        return new DefaultBundleAssemblyService(
            sources, RetrievalMode.HYBRID, reranker, canonicalIdOf, versionOf, MAX_SECTIONS,
            coverageCalcFrom( props ) );
```

- [ ] **Step 5: Run tests**

Run: `mvn -q -pl wikantik-main test -Dtest=DefaultBundleAssemblyServiceTest,BundleServiceWiringTest`
Expected: PASS (update any pre-existing constructor call sites in these tests to the new arity if the compiler flags them).

- [ ] **Step 6: Compile-check the module**

Run: `mvn -q -pl wikantik-main -am test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceTest.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java
git commit -m "feat(bundle): compute coverage in DefaultBundleAssemblyService; config thresholds"
```

---

### Task 6: `assemble_bundle` MCP — recount after view-gate + serialize (wikantik-knowledge)

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/AssembleBundleTool.java`
- Test: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/AssembleBundleToolTest.java`

**Interfaces:**
- Consumes: `BundleCoverage.recount` (Task 1), `ContextBundle.coverage()` (Task 2).

- [ ] **Step 1: Write the failing test** (append to `AssembleBundleToolTest`; reuse the existing JSON-parse helper pattern)

```java
@Test
void serializesCoverageBlock() {
    final BundleCoverage cov = new BundleCoverage( 1, 1, 0.82, BundleCoverage.STRONG );
    final ContextBundle withCoverage = new ContextBundle(
            "deploy", FIXED_BUNDLE.sections(), cov );
    final BundleAssemblyService stub = query -> withCoverage;
    final AssembleBundleTool t = new AssembleBundleTool( stub, () -> null );

    final McpSchema.CallToolResult res = t.execute( Map.of( "query", "deploy" ) );
    final String json = ( (McpSchema.TextContent) res.content().get( 0 ) ).text();
    final JsonObject obj = JsonParser.parseString( json ).getAsJsonObject();
    final JsonObject coverage = obj.getAsJsonObject( "coverage" );
    assertEquals( "strong", coverage.get( "confidence" ).getAsString() );
    assertEquals( 0.82, coverage.get( "topSimilarity" ).getAsDouble(), 1e-9 );
    assertEquals( 1, coverage.get( "sectionCount" ).getAsInt() );
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-knowledge test -Dtest=AssembleBundleToolTest`
Expected: FAIL — gated bundle is built with the 2-arg constructor (empty coverage), so `confidence` is `unknown`.

- [ ] **Step 3: Update the tool** (the gated-bundle construction)

```java
            final ContextBundle gated = new ContextBundle( bundle.query(), filteredSections,
                    com.wikantik.api.bundle.BundleCoverage.recount( bundle.coverage(), filteredSections ) );
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-knowledge test -Dtest=AssembleBundleToolTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/AssembleBundleTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/AssembleBundleToolTest.java
git commit -m "feat(bundle): assemble_bundle recounts coverage post view-gate + serializes it"
```

---

### Task 7: `/api/bundle` REST — recount after ACL filter + serialize (wikantik-rest)

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/BundleResource.java:124-126`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/BundleResourceTest.java`

**Interfaces:**
- Consumes: `BundleCoverage.recount` (Task 1), `ContextBundle.coverage()` (Task 2).

- [ ] **Step 1: Write the failing test** (append to `BundleResourceTest`, mirroring its existing JSON-assertion style)

```java
@Test
void responseIncludesCoverageBlock() {
    // Arrange the assembly stub to return a bundle with a known coverage.
    final BundleCoverage cov = new BundleCoverage( 2, 2, 0.6, BundleCoverage.STRONG );
    stubAssemble( new ContextBundle( "q", twoViewableSections(), cov ) );

    final String body = doGet( "/api/bundle?q=anything" );   // existing helper
    final JsonObject obj = JsonParser.parseString( body ).getAsJsonObject();
    assertTrue( obj.has( "coverage" ) );
    assertEquals( "strong", obj.getAsJsonObject( "coverage" ).get( "confidence" ).getAsString() );
}
```

> Use the test's existing assembly-stub and HTTP-invoke helpers; the names above are illustrative — match what `BundleResourceTest` already defines.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-rest test -Dtest=BundleResourceTest`
Expected: FAIL — filtered bundle rebuilt with 2-arg constructor → coverage `unknown`.

- [ ] **Step 3: Update `BundleResource`** (lines 124-126)

```java
        final ContextBundle bundle = new ContextBundle( assembled.query(),
                assembled.sections().stream()
                        .filter( s -> viewable.contains( s.slug() ) ).toList() );
```

becomes:

```java
        final java.util.List< com.wikantik.api.bundle.BundleSection > viewableSections =
                assembled.sections().stream()
                        .filter( s -> viewable.contains( s.slug() ) ).toList();
        final ContextBundle bundle = new ContextBundle( assembled.query(), viewableSections,
                com.wikantik.api.bundle.BundleCoverage.recount( assembled.coverage(), viewableSections ) );
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-rest test -Dtest=BundleResourceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/BundleResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/BundleResourceTest.java
git commit -m "feat(bundle): /api/bundle recounts coverage post-ACL + serializes it"
```

---

### Task 8: Tool-description routing (#2) + assertion tests (wikantik-knowledge)

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/AssembleBundleTool.java` (description string, line ~83-86)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SparqlQueryTool.java` (description, line ~84)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetOntologyTool.java` (description, line ~67)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/DiscoverSchemaTool.java` (description, line ~81)
- Test: Create `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ToolDescriptionRoutingTest.java`

**Interfaces:** none new — description-string edits only, locked by assertion tests so they don't silently drift.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.mcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the #2 routing guidance into the tool descriptions so it can't silently drift. */
class ToolDescriptionRoutingTest {

    @Test
    void assembleBundleMentionsCoverageEscalation() {
        final String d = new AssembleBundleTool( q -> null, () -> null ).definition().description();
        assertTrue( d.contains( "coverage" ), "assemble_bundle should mention the coverage block" );
        assertTrue( d.toLowerCase().contains( "sparql_query" ) || d.toLowerCase().contains( "get_ontology" ),
                "assemble_bundle should name the escalation tools" );
    }

    @Test
    void sparqlMentionsCountsAndEnumerations() {
        final String d = new SparqlQueryTool( /* ctor args — match existing */ ).definition().description();
        assertTrue( d.toLowerCase().contains( "count" ) || d.toLowerCase().contains( "enumerat" ),
                "sparql_query should advertise exact counts/enumerations" );
    }
}
```

> If `SparqlQueryTool`/`GetOntologyTool`/`DiscoverSchemaTool` constructors need collaborators, follow the construction already used in their existing unit tests (e.g. `SparqlQueryToolTest`); add the analogous assertions for `GetOntologyTool` and `DiscoverSchemaTool`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-knowledge test -Dtest=ToolDescriptionRoutingTest`
Expected: FAIL — descriptions do not yet contain the routing guidance.

- [ ] **Step 3: Edit the descriptions**

`AssembleBundleTool.definition()` — append to the description string:

```
" The response includes a 'coverage' block (confidence: strong|partial|weak|unknown);"
+ " when confidence is weak or partial, refine your query or escalate to sparql_query /"
+ " get_ontology for exact counts and enumerations."
```

`SparqlQueryTool` description — append:

```
" Use this for EXACT counts and enumerations (e.g. how many predicates/classes, list all of a type)"
+ " rather than free-text retrieval."
```

`GetOntologyTool` description — append:

```
" Use this for authoritative counts/lists of classes and predicates rather than free-text search."
```

`DiscoverSchemaTool` description — append:

```
" Use this to enumerate node types and counts in the knowledge base."
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-knowledge test -Dtest=ToolDescriptionRoutingTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/AssembleBundleTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SparqlQueryTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetOntologyTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/DiscoverSchemaTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ToolDescriptionRoutingTest.java
git commit -m "feat(mcp): route count/enumeration questions to structured tools via descriptions"
```

---

### Task 9: Threshold calibration + config documentation

**Files:**
- Modify: `docs/wikantik-pages/HybridRetrieval.md` (document the two coverage props)
- Reference (read-only): `eval/agent-grounding/questions.json`, `wikantik-knowledge/.../HybridChunkSectionSource.debugRankings`

**Interfaces:** none — measurement + docs only.

- [ ] **Step 1: One-shot top-cosine measurement**

With a deployed local instance (per CLAUDE.md), call `/api/bundle?debug=rankings` (the existing gated endpoint) for ~5 known-good queries from `questions.json` and ~3 deliberately off-topic queries. Record the max dense cosine per query.

Run (example):
```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
curl -s -u "${login}:${password}" \
  "http://localhost:8080/api/bundle?debug=rankings&q=how+many+KG+predicates" | head
```
Expected: a JSON `dense` ranking with descending scores; note the top score.

- [ ] **Step 2: Confirm or adjust the defaults**

If known-good queries cluster above ~0.55 and off-topic below ~0.40, keep `0.55`/`0.40`. Otherwise adjust `DEFAULT_STRONG`/`DEFAULT_PARTIAL` in `BundleCoverageCalculator` to the observed split and re-run `BundleCoverageCalculatorTest` (update the boundary fixtures only if you move a threshold across a test's value).

- [ ] **Step 3: Document the config**

Add to `docs/wikantik-pages/HybridRetrieval.md`, under the bundle config notes:

```
- `wikantik.bundle.coverage.strong_similarity` (default 0.55) /
  `wikantik.bundle.coverage.partial_similarity` (default 0.40): top-dense-cosine
  thresholds for the bundle `coverage.confidence` label (strong/partial/weak/unknown).
  Provisional — calibrated against eval/agent-grounding top-cosine distribution.
```

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleCoverageCalculator.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleCoverageCalculatorTest.java \
        docs/wikantik-pages/HybridRetrieval.md
git commit -m "chore(bundle): calibrate + document coverage confidence thresholds"
```

---

### Task 10: Full-reactor + IT verification gate

**Files:** none — verification only.

- [ ] **Step 1: Full unit build**

Run: `mvn clean install -DskipITs`
Expected: BUILD SUCCESS across all modules (no `-T 1C` — see memory `reference_provider_test_flakes`).

- [ ] **Step 2: Integration tests** (gate prod-code commits per memory `feedback_full_it_after_targeted_fix`)

Run: `mvn clean install -Pintegration-tests -fae`
Expected: all IT submodules run; green. Re-run any isolated flake (e.g. `EditIT`, `BundleResource` ITs) in isolation before treating as a blocker.

- [ ] **Step 3: Eval re-run (validation, optional but recommended)**

Re-run the grounded eval and confirm the `grounded_mcp` arm shows fewer repeated `assemble_bundle` calls and no regression on `kg-predicates-count` / `read-path-acl`:

Run: `cd eval/agent-grounding && python run_eval.py --samples 3` (match the harness's documented invocation)
Expected: `interface-findings.md` shows reduced "Repeated tool calls"; scorecard `grounded_mcp` ≥ prior baseline. Small-N — directional only.

- [ ] **Step 4: Final commit (if calibration or docs changed during verification)**

```bash
git add -p   # stage reviewed changes only
git commit -m "test(bundle): verify coverage signal across reactor + ITs"
```

---

## Self-Review

**Spec coverage:**
- Design §1 `BundleCoverage` → Task 1. ✓
- §2 `ContextBundle` coverage + back-compat → Task 2. ✓
- §3 `SectionCandidates` carrier + 3 sources → Task 3. ✓
- §4 confidence calc + config thresholds → Task 4 (logic) + Task 5 (wiring) + Task 9 (calibration/docs). ✓
- §5 view-gating recount → Task 6 (MCP) + Task 7 (REST). ✓
- §6 tool-description routing → Task 8. ✓
- Testing section → per-task TDD + Task 10 reactor/IT gate. ✓
- Validation (eval re-run) → Task 10 Step 3. ✓

**Placeholder scan:** Task 7/8 note "match the existing helper/ctor" — this is deliberate (the surrounding test infra names are file-local and must be read at execution time), not a code placeholder; the behavior and assertions are fully specified.

**Type consistency:** `SectionCandidates.of(...)`, `candidates()` → `SectionCandidates`, `BundleCoverageCalculator.compute(double, List<BundleSection>)`, `BundleCoverage.recount(BundleCoverage, List<BundleSection>)`, `BundleCoverage.distinctPages(List<BundleSection>)`, the 7-arg base `DefaultBundleAssemblyService` constructor + `defaults()`-delegating convenience constructors — all consistent across Tasks 1–8.
