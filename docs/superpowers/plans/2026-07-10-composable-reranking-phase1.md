# Composable Deterministic Reranking — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generalize the bundle pipeline's single section reranker into an ordered, config-driven chain and add the first deterministic stage — a lexical Maximal Marginal Relevance (MMR) diversity reranker — defaulting OFF so behavior is byte-identical until explicitly enabled.

**Architecture:** The bundle pipeline already has the seam: `DefaultBundleAssemblyService` calls a single `SectionReranker.rerank(query, sections)` (fail-closed contract: "degrade to input order, never drop"), selected in `BundleServiceWiring.rerankerFor()`. Phase 1 adds a `SectionRerankChain` (composes rerankers, fail-closed by composition, empty→identity) and an `MmrSectionReranker` (reorders by λ·relevance − (1−λ)·novelty), then teaches `rerankerFor()` a new `wikantik.bundle.rerank.chain` config key while leaving the legacy path untouched. All classes join the existing `com.wikantik.knowledge.bundle` package in `wikantik-main` — no new module, no new package, no `wikantik-api` change.

**Tech Stack:** Java 25, JUnit 5, Mockito (already on the `wikantik-main` test path), Log4j2, Maven. No new dependencies.

## Design decisions (grounded in the current code, and where they diverge from the roadmap brief)

Read [RoadmapComposableReranking](../../wikantik-pages/) (wiki) for the why; this section records what reading the actual pipeline forced to change:

1. **The SPI already exists — do not create a new one.** The brief proposed a new `RerankStage` contract in `wikantik-api`. The real seam is `com.wikantik.knowledge.bundle.SectionReranker` (in `wikantik-main`), already `List< CandidateSection > rerank( String, List< CandidateSection > )` with the exact fail-closed contract, an `IDENTITY` instance, and config-driven selection. Phase 1 reuses it. Promoting `SectionReranker` to `wikantik-api` is deferred until an out-of-module consumer needs it (YAGNI) — adding implementations to this existing package is explicitly permitted by the target-architecture "no *new* packages in `wikantik-main`" invariant.

2. **MMR is LEXICAL, not embedding-cosine.** The brief assumed "cosine similarity over already-computed chunk embeddings." The candidate model `CandidateSection( slug, headingPath, text, denseScore )` carries only a scalar `denseScore` — **no embedding vector**. Threading vectors through `DenseChunkSectionSource` → `SectionCandidates` → `CandidateSection` is a larger change touching the record and both candidate paths. Phase 1 therefore computes the MMR diversity term as a deterministic term-frequency cosine over `text()`. Embedding-vector MMR becomes a measured follow-up only if lexical diversity underperforms. This keeps Phase 1 a reorder-only, dependency-free change and honors measure-first.

3. **Knee cutoff is OUT of Phase 1.** A knee/cutoff stage *drops* candidates, which violates the `SectionReranker` "never drop sections" contract and needs a different (cutoff-capable) seam. Phase 1 is chain + MMR (reorder-only), exactly the brief's "first session scope."

4. **The recall@12 ≥ 0.74 gate is a MANUAL measurement, not a hermetic unit test.** The bundle eval's real-corpus tier (`BundleEvalGateTest`) is Docker/embedding-snapshot-gated and currently non-blocking (floors 0.0). So the numeric recall no-regression gate is run against a live dense index and recorded in `eval/bundle-corpus/baseline-notes.md`, mirroring how the 0.74 baseline was established. The hermetic unit tests in this plan cover flag-off identity, the MMR diversity property, fail-closed degradation, and config parsing. This is stated honestly rather than faked as CI.

## Global Constraints

- **Java version:** JDK 25 (`jdk.version` in the parent POM). One line each below is a hard requirement of every task.
- **No new package / no new module:** all new classes go in `com.wikantik.knowledge.bundle` inside `wikantik-main`.
- **No new dependency:** use only what `wikantik-main` already has (JUnit 5, Mockito, Log4j2, Gson).
- **ASF license header verbatim** at the top of every new `.java` file (copy from any existing file in the package, e.g. `SectionReranker.java` lines 1–18).
- **Default OFF / byte-identical:** the default `wikantik.properties` must NOT set `wikantik.bundle.rerank.chain`; with it unset, `rerankerFor()` returns exactly what it returns today. Every pre-existing test must stay green unchanged.
- **Fail-closed:** every reranker degrades to its input order on any failure and never drops a section. Never swallow an exception without at least a `LOG.warn` with context (CLAUDE.md rule).
- **Code style:** spaces inside generics and parens (`List< CandidateSection >`, `( final String x )`), `final` on params and locals, Log4j2 `LogManager.getLogger`.
- **TDD:** failing test first, minimal implementation, green, commit. Run a single class with `mvn test -pl wikantik-main -Dtest=ClassName` (surefire CWD = the `wikantik-main` module dir).

---

### Task 1: `LexicalSimilarity` — deterministic term-frequency cosine

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/LexicalSimilarity.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/LexicalSimilarityTest.java`

**Interfaces:**
- Produces: `static Map< String, Integer > LexicalSimilarity.vector( String text )` (lower-cased, split on `[^a-z0-9]+`, term→count); `static double LexicalSimilarity.cosine( Map< String, Integer > a, Map< String, Integer > b )` (0.0 when either is empty); convenience `static double LexicalSimilarity.cosine( String a, String b )`.
- Consumes: nothing.

- [ ] **Step 1: Write the failing test**

```java
/* <ASF license header — copy lines 1–18 from SectionReranker.java> */
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalSimilarityTest {

    @Test
    void identicalText_cosineIsOne() {
        assertEquals( 1.0, LexicalSimilarity.cosine( "alpha beta gamma", "alpha beta gamma" ), 1e-9 );
    }

    @Test
    void disjointText_cosineIsZero() {
        assertEquals( 0.0, LexicalSimilarity.cosine( "alpha beta", "gamma delta" ), 1e-9 );
    }

    @Test
    void emptyOrNull_cosineIsZero() {
        assertEquals( 0.0, LexicalSimilarity.cosine( "", "alpha" ), 1e-9 );
        assertEquals( 0.0, LexicalSimilarity.cosine( null, "alpha" ), 1e-9 );
    }

    @Test
    void partialOverlap_isBetweenZeroAndOne() {
        final double c = LexicalSimilarity.cosine( "alpha beta gamma", "alpha beta delta" );
        assertTrue( c > 0.0 && c < 1.0, "partial overlap must be strictly between 0 and 1, was " + c );
    }

    @Test
    void caseAndPunctuationInsensitive() {
        assertEquals( 1.0, LexicalSimilarity.cosine( "Alpha, Beta!", "alpha beta" ), 1e-9 );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=LexicalSimilarityTest`
Expected: FAIL — `LexicalSimilarity` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
/* <ASF license header — copy lines 1–18 from SectionReranker.java> */
package com.wikantik.knowledge.bundle;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Deterministic term-frequency cosine similarity over two texts, used by {@link MmrSectionReranker}
 *  for the MMR diversity term. Pure: lower-cases, splits on non-alphanumerics, cosine over raw
 *  term counts. Returns 0.0 when either side has no tokens. */
final class LexicalSimilarity {

    private LexicalSimilarity() {}

    static Map< String, Integer > vector( final String text ) {
        final Map< String, Integer > m = new HashMap<>();
        if ( text == null ) return m;
        for ( final String tok : text.toLowerCase( Locale.ROOT ).split( "[^a-z0-9]+" ) ) {
            if ( !tok.isEmpty() ) m.merge( tok, 1, Integer::sum );
        }
        return m;
    }

    static double cosine( final Map< String, Integer > a, final Map< String, Integer > b ) {
        if ( a.isEmpty() || b.isEmpty() ) return 0.0;
        // iterate the smaller map for the dot product
        final Map< String, Integer > small = a.size() <= b.size() ? a : b;
        final Map< String, Integer > large = small == a ? b : a;
        double dot = 0.0;
        for ( final Map.Entry< String, Integer > e : small.entrySet() ) {
            final Integer o = large.get( e.getKey() );
            if ( o != null ) dot += (double) e.getValue() * o;
        }
        final double na = norm( a );
        final double nb = norm( b );
        return ( na == 0.0 || nb == 0.0 ) ? 0.0 : dot / ( na * nb );
    }

    static double cosine( final String a, final String b ) {
        return cosine( vector( a ), vector( b ) );
    }

    private static double norm( final Map< String, Integer > v ) {
        double s = 0.0;
        for ( final int c : v.values() ) s += (double) c * c;
        return Math.sqrt( s );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=LexicalSimilarityTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/LexicalSimilarity.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/LexicalSimilarityTest.java
git commit -m "feat(bundle): deterministic lexical cosine for MMR diversity term"
```

---

### Task 2: `SectionRerankChain` — ordered, fail-closed composition

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SectionRerankChain.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/SectionRerankChainTest.java`

**Interfaces:**
- Consumes: `SectionReranker` (existing interface), `CandidateSection` (existing record).
- Produces: `SectionRerankChain implements SectionReranker` with constructor `SectionRerankChain( List< SectionReranker > stages )` and package-visible accessor `List< SectionReranker > stages()`.

- [ ] **Step 1: Write the failing test**

```java
/* <ASF license header> */
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SectionRerankChainTest {

    private static CandidateSection sec( final String head, final double score ) {
        return new CandidateSection( "p", List.of( head ), head + " text", score );
    }

    @Test
    void emptyChain_returnsInputUnchanged() {
        final List< CandidateSection > in = List.of( sec( "A", 0.9 ), sec( "B", 0.1 ) );
        assertSame( in, new SectionRerankChain( List.of() ).rerank( "q", in ) );
    }

    @Test
    void stagesApplyInOrder_eachFedPriorOutput() {
        // Stage 1 reverses; stage 2 reverses again -> original order, proving both ran in sequence.
        final SectionReranker reverse = ( q, s ) -> { final var c = new java.util.ArrayList<>( s ); java.util.Collections.reverse( c ); return c; };
        final List< CandidateSection > in = List.of( sec( "A", 0.9 ), sec( "B", 0.5 ), sec( "C", 0.1 ) );
        final var out = new SectionRerankChain( List.of( reverse, reverse ) ).rerank( "q", in );
        assertEquals( List.of( "A", "B", "C" ), out.stream().map( c -> c.headingPath().get( 0 ) ).toList() );
    }

    @Test
    void aThrowingStage_isSkipped_priorOrderKept() {
        final SectionReranker boom = ( q, s ) -> { throw new IllegalStateException( "boom" ); };
        final List< CandidateSection > in = List.of( sec( "A", 0.9 ), sec( "B", 0.1 ) );
        // chain: boom then identity -> boom is caught, input preserved
        final var out = new SectionRerankChain( List.of( boom, ( q, s ) -> s ) ).rerank( "q", in );
        assertEquals( List.of( "A", "B" ), out.stream().map( c -> c.headingPath().get( 0 ) ).toList() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=SectionRerankChainTest`
Expected: FAIL — `SectionRerankChain` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
/* <ASF license header> */
package com.wikantik.knowledge.bundle;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/** Applies an ordered list of {@link SectionReranker}s, each fed the previous stage's output.
 *  Each stage already degrades to its input on failure; the chain additionally catches any stage
 *  that throws and keeps the prior order, so the chain is fail-closed by composition. An empty
 *  chain returns the input unchanged (identity). Reorder-only: no stage drops sections. */
final class SectionRerankChain implements SectionReranker {

    private static final Logger LOG = LogManager.getLogger( SectionRerankChain.class );

    private final List< SectionReranker > stages;

    SectionRerankChain( final List< SectionReranker > stages ) {
        this.stages = List.copyOf( stages );
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        List< CandidateSection > cur = sections;
        for ( final SectionReranker stage : stages ) {
            try {
                cur = stage.rerank( query, cur );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Rerank stage {} failed ({}); keeping prior order",
                    stage.getClass().getSimpleName(), e.getMessage() );
            }
        }
        return cur;
    }

    List< SectionReranker > stages() {
        return stages;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=SectionRerankChainTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SectionRerankChain.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/SectionRerankChainTest.java
git commit -m "feat(bundle): fail-closed ordered SectionRerankChain"
```

---

### Task 3: `MmrSectionReranker` — lexical MMR diversity reorder

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/MmrSectionReranker.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/MmrSectionRerankerTest.java`

**Interfaces:**
- Consumes: `SectionReranker`, `CandidateSection`, `LexicalSimilarity` (Task 1).
- Produces: `MmrSectionReranker implements SectionReranker`, constructors `MmrSectionReranker( double lambda )` (λ clamped to [0,1], default 0.7 when out of range) and `MmrSectionReranker( double lambda, BiFunction< Map< String, Integer >, Map< String, Integer >, Double > similarity )` for test injection. Relevance term = min-max normalized `denseScore` over the candidate set (uniform 1.0 when all scores equal); diversity term = 1 − max lexical cosine to already-selected sections. Reorder-only, never drops, returns input on `≤ 1` section or any failure.

- [ ] **Step 1: Write the failing test**

```java
/* <ASF license header> */
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmrSectionRerankerTest {

    private static CandidateSection sec( final String slug, final String head, final String text, final double score ) {
        return new CandidateSection( slug, List.of( head ), text, score );
    }

    @Test
    void singleOrEmpty_returnedUnchanged() {
        assertEquals( List.of(), new MmrSectionReranker( 0.7 ).rerank( "q", List.of() ) );
        final List< CandidateSection > one = List.of( sec( "p", "A", "alpha", 0.9 ) );
        assertSame( one, new MmrSectionReranker( 0.7 ).rerank( "q", one ) );
    }

    @Test
    void topRelevanceSectionStaysFirst() {
        // Highest denseScore must remain the first pick (MMR's first selection = argmax relevance).
        final var in = List.of(
            sec( "p1", "A", "alpha beta gamma", 0.95 ),
            sec( "p2", "B", "delta epsilon zeta", 0.40 ),
            sec( "p3", "C", "eta theta iota", 0.30 ) );
        final var out = new MmrSectionReranker( 0.7 ).rerank( "q", in );
        assertEquals( "p1", out.get( 0 ).slug() );
    }

    @Test
    void nearDuplicateIsDemotedBelowNovelLowerRankedSection() {
        // dense order: X (0.90), X' near-duplicate of X (0.85), Y novel (0.80).
        // Identity keeps X, X', Y. MMR must demote X' below Y because X' duplicates the already-picked X.
        final var in = List.of(
            sec( "px",  "X",  "cache eviction lru policy ttl", 0.90 ),
            sec( "px2", "X2", "cache eviction lru policy ttl entries", 0.85 ),  // near-duplicate of X
            sec( "py",  "Y",  "postgres vacuum autovacuum bloat", 0.80 ) );     // novel
        final var out = new MmrSectionReranker( 0.5 ).rerank( "q", in );
        assertEquals( "px", out.get( 0 ).slug(), "top relevance stays first" );
        assertEquals( "py", out.get( 1 ).slug(), "novel section promoted above the near-duplicate" );
        assertEquals( "px2", out.get( 2 ).slug(), "near-duplicate demoted last" );
    }

    @Test
    void neverDropsSections() {
        final var in = List.of(
            sec( "p1", "A", "alpha", 0.9 ), sec( "p2", "B", "alpha", 0.8 ), sec( "p3", "C", "beta", 0.7 ) );
        final var out = new MmrSectionReranker( 0.7 ).rerank( "q", in );
        assertEquals( in.size(), out.size(), "MMR reorders but never drops" );
        assertTrue( out.containsAll( in ) );
    }

    @Test
    void injectedSimilarityThrows_returnsInputOrder() {
        final var in = List.of( sec( "p1", "A", "alpha", 0.9 ), sec( "p2", "B", "beta", 0.8 ) );
        final MmrSectionReranker mmr = new MmrSectionReranker( 0.7,
            ( a, b ) -> { throw new IllegalStateException( "sim boom" ); } );
        assertSame( in, mmr.rerank( "q", in ), "any failure degrades to the input order" );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=MmrSectionRerankerTest`
Expected: FAIL — `MmrSectionReranker` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
/* <ASF license header> */
package com.wikantik.knowledge.bundle;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Maximal Marginal Relevance reorder: trades query-relevance (min-max normalized {@code denseScore})
 *  against novelty (1 − max lexical cosine to already-selected sections), λ balancing the two. The
 *  first pick is the highest-relevance section, so the top result is preserved. Reorders only —
 *  never drops a section — and returns the input unchanged on {@code ≤ 1} section or any failure. */
final class MmrSectionReranker implements SectionReranker {

    private static final Logger LOG = LogManager.getLogger( MmrSectionReranker.class );

    private final double lambda;
    private final BiFunction< Map< String, Integer >, Map< String, Integer >, Double > similarity;

    MmrSectionReranker( final double lambda ) {
        this( lambda, LexicalSimilarity::cosine );
    }

    MmrSectionReranker( final double lambda,
                        final BiFunction< Map< String, Integer >, Map< String, Integer >, Double > similarity ) {
        this.lambda = ( lambda < 0.0 || lambda > 1.0 ) ? 0.7 : lambda;
        this.similarity = similarity;
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        if ( sections == null ) return List.of();
        if ( sections.size() <= 1 ) return sections;
        try {
            final int n = sections.size();
            final double[] rel = normalizedRelevance( sections );
            final List< Map< String, Integer > > vecs = new ArrayList<>( n );      // precompute once
            for ( final CandidateSection c : sections ) vecs.add( LexicalSimilarity.vector( c.text() ) );

            final boolean[] picked = new boolean[ n ];
            final List< Integer > selected = new ArrayList<>( n );
            final List< CandidateSection > out = new ArrayList<>( n );

            for ( int step = 0; step < n; step++ ) {
                int best = -1;
                double bestScore = Double.NEGATIVE_INFINITY;
                for ( int i = 0; i < n; i++ ) {
                    if ( picked[ i ] ) continue;
                    double maxSim = 0.0;
                    for ( final int s : selected ) {
                        final double sim = similarity.apply( vecs.get( i ), vecs.get( s ) );
                        if ( sim > maxSim ) maxSim = sim;
                    }
                    final double mmr = lambda * rel[ i ] - ( 1.0 - lambda ) * maxSim;
                    if ( mmr > bestScore ) { bestScore = mmr; best = i; }
                }
                picked[ best ] = true;
                selected.add( best );
                out.add( sections.get( best ) );
            }
            return out;
        } catch ( final RuntimeException e ) {
            LOG.warn( "MMR rerank failed ({}); using prior order", e.getMessage() );
            return sections;
        }
    }

    /** Min-max normalize denseScore to [0,1]; uniform 1.0 when all scores are equal (no signal). */
    private static double[] normalizedRelevance( final List< CandidateSection > s ) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for ( final CandidateSection c : s ) {
            min = Math.min( min, c.denseScore() );
            max = Math.max( max, c.denseScore() );
        }
        final double range = max - min;
        final double[] rel = new double[ s.size() ];
        for ( int i = 0; i < s.size(); i++ ) {
            rel[ i ] = range <= 0.0 ? 1.0 : ( s.get( i ).denseScore() - min ) / range;
        }
        return rel;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=MmrSectionRerankerTest`
Expected: PASS (5 tests). If `nearDuplicateIsDemotedBelowNovelLowerRankedSection` fails, the injected λ or the relevance normalization is off — do NOT weaken the assertion; re-derive the expected MMR scores by hand for λ=0.5 and fix the implementation.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/MmrSectionReranker.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/MmrSectionRerankerTest.java
git commit -m "feat(bundle): lexical MMR diversity reranker (reorder-only, fail-closed)"
```

---

### Task 4: Wire the chain into `BundleServiceWiring.rerankerFor()`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java` (the `rerankerFor` method, ~lines 150–154; add helpers)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java` (add cases; existing cases must stay green)

**Interfaces:**
- Consumes: `SectionRerankChain` (Task 2), `MmrSectionReranker` (Task 3), existing `LlmSectionReranker`, `RerankerConfig`, `IDENTITY`.
- Produces: extended `static SectionReranker rerankerFor( Properties )` reading `wikantik.bundle.rerank.chain` (CSV of stage names `mmr`/`llm`); helper `static double mmrLambda( Properties )` (`wikantik.bundle.rerank.mmr.lambda`, default 0.7). Legacy behavior unchanged when `rerank.chain` is absent.

- [ ] **Step 1: Write the failing test** (append to `BundleServiceWiringTest`)

```java
    @Test
    void rerankChainUnset_preservesLegacyIdentity() {
        // No rerank.chain key -> must behave exactly as today (identity when reranker.enabled absent).
        final List< CandidateSection > in = List.of( sec( "A" ), sec( "B" ) );
        assertSame( in, BundleServiceWiring.rerankerFor( new Properties() ).rerank( "q", in ) );
    }

    @Test
    void rerankChainMmr_buildsChainContainingMmr() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.chain", "mmr" );
        final SectionReranker r = BundleServiceWiring.rerankerFor( p );
        assertInstanceOf( SectionRerankChain.class, r );
        final SectionRerankChain chain = (SectionRerankChain) r;
        assertEquals( 1, chain.stages().size() );
        assertInstanceOf( MmrSectionReranker.class, chain.stages().get( 0 ) );
    }

    @Test
    void rerankChainUnknownStage_isSkipped() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.chain", "mmr, bogus" );
        final SectionRerankChain chain = (SectionRerankChain) BundleServiceWiring.rerankerFor( p );
        assertEquals( 1, chain.stages().size(), "unknown stage names are skipped with a warn" );
        assertInstanceOf( MmrSectionReranker.class, chain.stages().get( 0 ) );
    }

    @Test
    void rerankChainAllUnknown_fallsBackToIdentity() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.chain", "bogus" );
        final List< CandidateSection > in = List.of( sec( "A" ) );
        assertSame( in, BundleServiceWiring.rerankerFor( p ).rerank( "q", in ),
            "an all-unknown chain degrades to identity, not an empty chain" );
    }

    @Test
    void mmrLambda_defaultsToPoint7AndParses() {
        assertEquals( 0.7, BundleServiceWiring.mmrLambda( new Properties() ), 1e-9 );
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.mmr.lambda", "0.5" );
        assertEquals( 0.5, BundleServiceWiring.mmrLambda( p ), 1e-9 );
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest`
Expected: FAIL — new methods/behavior absent (`mmrLambda` undefined; chain key ignored).

- [ ] **Step 3: Write minimal implementation** — replace the existing `rerankerFor` method and add helpers. Add imports `java.util.ArrayList` and `java.util.Locale` at the top.

```java
    /**
     * Selects the section reranker from config. If {@code wikantik.bundle.rerank.chain} is set
     * (CSV of stage names: {@code mmr}, {@code llm}), builds an ordered {@link SectionRerankChain}
     * from it. Otherwise falls back to the legacy behavior: {@code wikantik.bundle.reranker.enabled}
     * defaults to {@code false} → identity; {@code true} → the listwise LLM reranker.
     */
    static SectionReranker rerankerFor( final Properties props ) {
        final String chainSpec = props == null ? null : props.getProperty( "wikantik.bundle.rerank.chain" );
        if ( chainSpec != null && !chainSpec.isBlank() ) {
            return buildChain( chainSpec, props );
        }
        final boolean enabled = props != null && Boolean.parseBoolean(
            props.getProperty( RerankerConfig.PREFIX + "enabled", "false" ) );
        return enabled ? new LlmSectionReranker( RerankerConfig.fromProperties( props ) ) : IDENTITY;
    }

    /** Builds an ordered reranker chain from a CSV of stage names; unknown names are skipped with a
     *  warn, and an all-unknown/empty spec degrades to {@link #IDENTITY}. */
    private static SectionReranker buildChain( final String spec, final Properties props ) {
        final java.util.List< SectionReranker > stages = new java.util.ArrayList<>();
        for ( final String raw : spec.split( "," ) ) {
            final String name = raw.trim().toLowerCase( java.util.Locale.ROOT );
            switch ( name ) {
                case "" -> { /* empty token from stray comma — ignore */ }
                case "mmr" -> stages.add( new MmrSectionReranker( mmrLambda( props ) ) );
                case "llm" -> stages.add( new LlmSectionReranker( RerankerConfig.fromProperties( props ) ) );
                default -> LOG.warn( "Unknown rerank stage '{}' in wikantik.bundle.rerank.chain; skipping", name );
            }
        }
        return stages.isEmpty() ? IDENTITY : new SectionRerankChain( stages );
    }

    /** MMR λ from {@code wikantik.bundle.rerank.mmr.lambda}, default 0.7 (higher = more relevance-biased). */
    static double mmrLambda( final Properties props ) {
        if ( props == null ) return 0.7;
        return com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.rerank.mmr.lambda", 0.7 );
    }
```

- [ ] **Step 4: Run test to verify it passes** — and confirm nothing regressed.

Run: `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest`
Expected: PASS (all pre-existing cases + 5 new). The `rerankChainUnset_preservesLegacyIdentity` case is the flag-off byte-identical gate.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java
git commit -m "feat(bundle): config-driven rerank chain (wikantik.bundle.rerank.chain), legacy path intact"
```

---

### Task 5: Document config (OFF by default) and the measurement gate

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (add commented keys near the existing `wikantik.bundle.reranker.*` block, ~line 1423)
- Modify: `eval/bundle-corpus/baseline-notes.md` (append the measurement procedure + a results-to-fill table)

**Interfaces:** none (docs + config comments only). No behavior change: the keys are commented out, so `rerankerFor()` still returns identity by default.

- [ ] **Step 1: Add commented config keys** (do NOT set them live — off by default is the gate)

Insert after the existing `wikantik.bundle.reranker.timeout_ms` line:

```properties
# --- Deterministic rerank chain (Phase 1: composable reranking) ---
# Ordered, comma-separated stage names applied after RRF fusion, before dedup/top-N.
# Stages: mmr (lexical MMR diversity), llm (listwise LLM reranker; overrides reranker.enabled).
# UNSET by default -> identity (bundle behavior byte-identical to pre-chain). Enable only after
# a measured recall@12 no-regression run (see eval/bundle-corpus/baseline-notes.md).
# wikantik.bundle.rerank.chain = mmr
# MMR lambda: 1.0 = pure relevance (no diversity), 0.0 = pure diversity. Default 0.7.
# wikantik.bundle.rerank.mmr.lambda = 0.7
```

- [ ] **Step 2: Append the measurement procedure to `eval/bundle-corpus/baseline-notes.md`**

```markdown
## MMR rerank measurement gate (Phase 1, 2026-07-10)

The recall@12 no-regression gate is a MANUAL run — the bundle eval real-corpus tier is
Docker/embedding-snapshot-gated and non-blocking, so this mirrors how the 0.74 baseline was set.

Procedure (against a local deployment with a live dense index):
1. Baseline: `wikantik.bundle.rerank.chain` UNSET. Run the corpus (queries.csv) through the live
   bundle and record overall section recall@12 → the control number (expect ~0.74).
2. Treatment: set `wikantik.bundle.rerank.chain = mmr`, redeploy, re-run the same corpus.
3. Record both below. ACCEPT the stage only if treatment recall@12 >= control (no regression)
   AND the diversity metric improves (distinct-slug count among the top-12, averaged over the
   corpus, goes up). REJECT and record in the dead-levers list otherwise.
4. Sweep lambda in {0.5, 0.7, 0.9} and keep the best non-regressing point.

| Config | recall@12 | mean distinct-slug @12 | p95 assemble latency | verdict |
|--------|-----------|------------------------|----------------------|---------|
| chain unset (control) |  |  |  | baseline |
| chain=mmr, lambda=0.7 |  |  |  |  |
| chain=mmr, lambda=0.5 |  |  |  |  |
| chain=mmr, lambda=0.9 |  |  |  |  |
```

- [ ] **Step 3: Full module build to confirm no regression anywhere**

Run: `mvn test -pl wikantik-main`
Expected: PASS — the whole `wikantik-main` unit suite, proving flag-off is byte-identical (no existing bundle/wiring test changed behavior).

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties eval/bundle-corpus/baseline-notes.md
git commit -m "docs(bundle): document rerank-chain config (off by default) and the MMR measurement gate"
```

---

## Acceptance criteria mapping (from RoadmapComposableReranking)

| Brief acceptance gate | Covered by |
|---|---|
| 1. Flag-off byte-identical | Task 4 `rerankChainUnset_preservesLegacyIdentity` + Task 5 Step 3 full-suite green; default properties leave the key unset |
| 2. No recall regression (recall@12 ≥ 0.74) | Task 5 manual measurement gate (honest: not hermetic — live index required) |
| 3. Stage's own metric moves (diversity) | Task 3 `nearDuplicateIsDemotedBelowNovelLowerRankedSection` (unit) + Task 5 mean-distinct-slug@12 (corpus) |
| 4. Latency negligible (~20 ms @ k=300) | Task 3 precomputes vectors once (O(n) vectorization + O(n²) map-cosine); Task 5 p95 column records the real number |

**Explicitly deferred (not this plan):** metadata-boost stage, knee-cutoff stage (needs a drop-capable seam), embedding-vector MMR (needs vector plumbing through `CandidateSection`). Each is its own follow-up gated the same way.

## Post-implementation note (2026-07-10, whole-branch review)

The whole-branch review caught that the greedy MMR loop as written in Task 3's Step 3 code recomputes each candidate's max-similarity against *all* already-selected sections every step — that is **O(n³)** similarity calls, not the O(n²) the acceptance table claims, and at `top_k=300` on the hot bundle path. The shipped implementation (commit `d6f960480e`) replaces the inner "max over selected" loop with a running `maxSimToSelected[]` cache updated once per pick, restoring true **O(n²)** with a bit-identical selection order (verified equivalent by induction; all five `MmrSectionRerankerTest` cases pass unchanged). If you re-derive the MMR code from this plan, use the running-max form, not the Step-3 nested loop. The latency remains a manual-measurement gate (Task 5).

## Self-Review

- **Spec coverage:** SPI/chain (Tasks 1–2), MMR stage (Task 3), config-driven wiring with legacy intact (Task 4), config doc + measurement gate (Task 5). All four brief acceptance gates mapped above. First-session scope ("SPI + MMR + one harness run") matches. Non-goals respected (no knee, no metadata-boost, no LLM stage added to defaults).
- **Placeholder scan:** none — every code step is complete and compilable; the only intentionally-empty cells are the measurement-results table the human fills after running.
- **Type consistency:** `SectionReranker.rerank( String, List< CandidateSection > )` used identically across Tasks 2–4; `LexicalSimilarity.vector`/`cosine( Map, Map )` signatures consumed by `MmrSectionReranker`'s injected `BiFunction< Map< String, Integer >, Map< String, Integer >, Double >` and by Task 4's default construction; `SectionRerankChain.stages()` asserted in Task 4. `mmrLambda`/`rerankerFor` signatures consistent between Task 4 impl and tests.
