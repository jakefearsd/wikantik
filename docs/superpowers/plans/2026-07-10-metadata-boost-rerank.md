# Composable Reranking Stage 2 — Metadata (Confidence) Boost Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second deterministic `RerankStage` — a bounded confidence boost — to the bundle rerank chain, so that among near-equally-relevant sections the more-verified (AUTHORITATIVE > PROVISIONAL > STALE) one surfaces first, without overriding relevance or regressing recall. Defaults OFF (chain key unset).

**Architecture:** Reuse the `SectionReranker` chain SPI shipped in the reranking Phase 1 (the `SectionRerankChain` + `rerankerFor()` config-driven selection). Add `MetadataBoostSectionReranker` (reorder-only, fail-closed) that reorders the top `window` candidates by an effective score `denseScore · (1 + factor · sign(confidence))`, where confidence comes from an injected `Function< String, Confidence > confidenceOf`. Confidence is the materialized `page_verification` value resolved slug → canonical_id → `DefaultStructuralIndexService.verificationOf()`. Wired through `rerankerFor` (new `confidenceOf` arg) and `BundleServiceWiring.build` (new nullable arg, via an overload so existing call sites stay byte-identical), with the lookup built at the `WikiEngine` bundle seam.

**Tech Stack:** Java 25, JUnit 5, Log4j2, Maven. No new dependencies. No schema change (confidence is already materialized in `page_verification`, V014).

## Design decisions (grounded in the current code)

1. **The rerank chain SPI already exists** (Phase 1): `SectionReranker` (`List< CandidateSection > rerank( String, List< CandidateSection > )`, fail-closed "never drop"), `SectionRerankChain`, and `BundleServiceWiring.rerankerFor()` parsing `wikantik.bundle.rerank.chain`. This task adds one stage + threads a confidence lookup.

2. **Signal = `Confidence` from the materialized `page_verification` table, NOT live frontmatter parsing.** `com.wikantik.api.pagegraph.Confidence` is a 3-value enum (AUTHORITATIVE/PROVISIONAL/STALE). `DefaultStructuralIndexService.verificationOf( canonicalId )` → `Optional< Verification >` → `Verification.confidence()` is the single materialized read (the structural rebuild already computed it via `ConfidenceComputer`). Do NOT call `PageManager.getPage` + reparse frontmatter per candidate.

3. **`CandidateSection` carries no metadata** (`slug, headingPath, text, denseScore`) — so confidence is resolved externally by an injected `Function< String, Confidence > confidenceOf` (slug → confidence). A `null` function ⇒ the stage is a no-op (fail-open: no lookup wired ⇒ no boost).

4. **Bounded tie-breaker, not a relevance lever.** The boost is `effective = denseScore · (1 + factor · s)` with `s = +1 (AUTHORITATIVE) / 0 (PROVISIONAL) / −1 (STALE)` and a small `factor` (default 0.05, clamped [0, 0.5]). It only reorders near-ties; it must not override a real relevance gap. Confidence is a QUALITY signal — the honest success criterion is "no recall@12 regression below 0.74" (a neutral corpus result is success for a tie-breaker); the stage's own effect is proven on a constructed equal-score set.

5. **Bound the lookup cost with a top-`window`.** Only the top `window` candidates by input order (default 24 = 2×MAX_SECTIONS) are boosted/resolved; the rest keep their order appended after. A bundle keeps only top-12, so boosting beyond ~24 cannot change the output — this caps confidence lookups at ~`window` per query regardless of the ~300 candidates.

6. **Byte-identical when the chain key is unset** (Phase 1 gate still holds): `rerankerFor` only builds a chain when `wikantik.bundle.rerank.chain` is set; the default properties leave it unset. The new `build`/`rerankerFor` args are added via overloads so every existing call site and test is untouched.

## Global Constraints

- **Java 25.** New code in existing package `com.wikantik.knowledge.bundle` (wikantik-main); no new package/module/dependency. No schema change.
- **ASF license header verbatim** on every new `.java` file (copy `SectionReranker.java` lines 1–18).
- **Reorder-only / fail-closed:** never drop a section; `null` confidenceOf or any exception ⇒ return input order (mirrors `MmrSectionReranker`).
- **Default OFF / byte-identical:** default `wikantik.properties` leaves `wikantik.bundle.rerank.chain` unset; existing `BundleServiceWiringTest` cases and the `build(...)` call sites stay green unchanged (new args via overloads).
- **Never swallow an exception without a `LOG.warn`** (CLAUDE.md).
- **Code style:** spaces in generics/parens, `final` on params/locals, Log4j2.
- **TDD:** failing test first. Run one class with `mvn test -pl wikantik-main -Dtest=ClassName`.
- Worked directly on `main` (sole-dev) — no branch; commit directly.

---

### Task 1: `MetadataBoostSectionReranker` — bounded confidence tie-breaker

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/MetadataBoostSectionReranker.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/MetadataBoostSectionRerankerTest.java`

**Interfaces:**
- Consumes: `SectionReranker`, `CandidateSection`, `com.wikantik.api.pagegraph.Confidence`.
- Produces: `MetadataBoostSectionReranker implements SectionReranker`, constructor `MetadataBoostSectionReranker( Function< String, Confidence > confidenceOf, double factor, int window )`. `factor` clamped to [0, 0.5] (out of range → 0.05); `window` ≤ 0 → 24. Reorders the top-`window` candidates by `denseScore · (1 + factor · sign(confidence))` (AUTHORITATIVE +1, PROVISIONAL 0, STALE −1; unknown/absent → PROVISIONAL), stable within equal effective scores; appends the remaining (beyond window) unchanged. Returns input unchanged when `confidenceOf == null`, `factor == 0`, `≤ 1` section, or on any exception.

- [ ] **Step 1: Write the failing test**

```java
/* <ASF license header — copy SectionReranker.java lines 1–18> */
package com.wikantik.knowledge.bundle;

import com.wikantik.api.pagegraph.Confidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataBoostSectionRerankerTest {

    private static CandidateSection sec( final String slug, final double score ) {
        return new CandidateSection( slug, List.of( "H" ), slug + " text", score );
    }

    private static Function< String, Confidence > confidences( final Map< String, Confidence > m ) {
        return slug -> m.getOrDefault( slug, Confidence.PROVISIONAL );
    }

    @Test
    void verifiedRanksAboveStale_whenDenseScoresEqual() {
        // Equal denseScore: the AUTHORITATIVE section must be promoted above the STALE one.
        final var in = List.of( sec( "stalePage", 0.80 ), sec( "authPage", 0.80 ) );
        final var conf = confidences( Map.of( "stalePage", Confidence.STALE, "authPage", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( conf, 0.05, 24 ).rerank( "q", in );
        assertEquals( "authPage", out.get( 0 ).slug() );
        assertEquals( "stalePage", out.get( 1 ).slug() );
    }

    @Test
    void boostDoesNotOverrideARealRelevanceGap() {
        // A much higher denseScore stale section still beats a barely-lower authoritative one:
        // 0.90·(1−0.05)=0.855 > 0.83·(1+0.05)=0.8715? No — pick a gap the small factor cannot cross.
        // stale 0.95·0.95 = 0.9025 vs auth 0.80·1.05 = 0.84 -> stale stays first.
        final var in = List.of( sec( "staleStrong", 0.95 ), sec( "authWeak", 0.80 ) );
        final var conf = confidences( Map.of( "staleStrong", Confidence.STALE, "authWeak", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( conf, 0.05, 24 ).rerank( "q", in );
        assertEquals( "staleStrong", out.get( 0 ).slug(), "a small boost must not cross a real relevance gap" );
    }

    @Test
    void factorZero_isIdentity() {
        final var in = List.of( sec( "a", 0.9 ), sec( "b", 0.8 ) );
        assertSame( in, new MetadataBoostSectionReranker( confidences( Map.of() ), 0.0, 24 ).rerank( "q", in ) );
    }

    @Test
    void nullConfidenceLookup_isIdentity() {
        final var in = List.of( sec( "a", 0.9 ), sec( "b", 0.8 ) );
        assertSame( in, new MetadataBoostSectionReranker( null, 0.05, 24 ).rerank( "q", in ) );
    }

    @Test
    void neverDropsSections_andBeyondWindowUntouched() {
        // window=1: only the first candidate is in the boost window; the rest keep their order and all survive.
        final var in = List.of( sec( "a", 0.9 ), sec( "stale", 0.85 ), sec( "auth", 0.80 ) );
        final var conf = confidences( Map.of( "stale", Confidence.STALE, "auth", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( conf, 0.05, 1 ).rerank( "q", in );
        assertEquals( in.size(), out.size() );
        assertTrue( out.containsAll( in ) );
        // beyond the window (index >= 1) order is preserved: stale before auth
        assertEquals( List.of( "a", "stale", "auth" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void lookupThrows_returnsInputOrder() {
        final var in = List.of( sec( "a", 0.9 ), sec( "b", 0.8 ) );
        final Function< String, Confidence > boom = slug -> { throw new IllegalStateException( "boom" ); };
        assertSame( in, new MetadataBoostSectionReranker( boom, 0.05, 24 ).rerank( "q", in ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=MetadataBoostSectionRerankerTest`
Expected: FAIL — `MetadataBoostSectionReranker` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
/* <ASF license header> */
package com.wikantik.knowledge.bundle;

import com.wikantik.api.pagegraph.Confidence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Bounded confidence tie-breaker: reorders the top-{@code window} candidates by
 *  {@code denseScore · (1 + factor · sign(confidence))} (AUTHORITATIVE +1, PROVISIONAL 0, STALE −1),
 *  so a more-verified section wins a near-tie without overriding a real relevance gap. Reorder-only —
 *  never drops — and returns the input unchanged when the lookup is null, factor is 0, there is ≤1
 *  section, or anything throws. */
final class MetadataBoostSectionReranker implements SectionReranker {

    private static final Logger LOG = LogManager.getLogger( MetadataBoostSectionReranker.class );

    private final Function< String, Confidence > confidenceOf;
    private final double factor;
    private final int window;

    MetadataBoostSectionReranker( final Function< String, Confidence > confidenceOf,
                                  final double factor, final int window ) {
        this.confidenceOf = confidenceOf;
        this.factor = ( factor < 0.0 || factor > 0.5 ) ? 0.05 : factor;
        this.window = window > 0 ? window : 24;
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        if ( sections == null ) return List.of();
        if ( confidenceOf == null || factor == 0.0 || sections.size() <= 1 ) return sections;
        try {
            final int w = Math.min( window, sections.size() );
            final List< CandidateSection > head = new ArrayList<>( sections.subList( 0, w ) );
            // stable sort by boosted score descending (List.sort is stable → ties keep input order)
            head.sort( ( a, b ) -> Double.compare( boosted( b ), boosted( a ) ) );
            final List< CandidateSection > out = new ArrayList<>( sections.size() );
            out.addAll( head );
            out.addAll( sections.subList( w, sections.size() ) );  // beyond the window: untouched
            return out;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Metadata-boost rerank failed ({}); using prior order", e.getMessage() );
            return sections;
        }
    }

    private double boosted( final CandidateSection c ) {
        return c.denseScore() * ( 1.0 + factor * sign( confidenceOf.apply( c.slug() ) ) );
    }

    private static int sign( final Confidence conf ) {
        if ( conf == Confidence.AUTHORITATIVE ) return 1;
        if ( conf == Confidence.STALE ) return -1;
        return 0;  // PROVISIONAL or null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=MetadataBoostSectionRerankerTest`
Expected: PASS (6 tests). If `verifiedRanksAboveStale` fails, re-derive: at equal denseScore 0.80, auth boosted = 0.80·1.05 = 0.84 > stale 0.80·0.95 = 0.76 — auth must sort first. Do not weaken the assertion.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/MetadataBoostSectionReranker.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/MetadataBoostSectionRerankerTest.java
git commit -m "feat(bundle): bounded confidence metadata-boost reranker (reorder-only, fail-closed)"
```

---

### Task 2: Wire the `metadata-boost` stage + thread the confidence lookup

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java` (`buildChain` gains a `metadata-boost` case; `rerankerFor` + `build` gain a nullable `confidenceOf` via overloads; new `metadataBoostFactor`/`metadataBoostWindow` helpers)
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (`patchContextRetrievalService`: build `confidenceOf` and pass it to `build`)
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (document the two new keys, commented)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java` (add cases; existing cases stay green)

**Interfaces:**
- Consumes: `MetadataBoostSectionReranker` (Task 1), `com.wikantik.api.pagegraph.Confidence`, existing chain plumbing.
- Produces: `rerankerFor( Properties, Function< String, Confidence > confidenceOf )` (the existing `rerankerFor( Properties )` delegates with `null`); a `build(...)` overload taking `Function< String, Confidence > confidenceOf` (existing `build(...)` delegates with `null`); `buildChain` handles stage name `metadata-boost` (skipped when `confidenceOf == null`); helpers `metadataBoostFactor( Properties )` (`wikantik.bundle.rerank.metadata_boost.factor`, default 0.05) and `metadataBoostWindow( Properties )` (`.window`, default 24).

- [ ] **Step 1: Write the failing test** (append to `BundleServiceWiringTest`; `import com.wikantik.api.pagegraph.Confidence;` and `java.util.function.Function`)

```java
    @Test
    void rerankChainMetadataBoost_withConfidence_buildsChainContainingBoost() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.chain", "metadata-boost" );
        final Function< String, Confidence > conf = slug -> Confidence.PROVISIONAL;
        final SectionReranker r = BundleServiceWiring.rerankerFor( p, conf );
        final SectionRerankChain chain = (SectionRerankChain) r;
        assertEquals( 1, chain.stages().size() );
        assertInstanceOf( MetadataBoostSectionReranker.class, chain.stages().get( 0 ) );
    }

    @Test
    void rerankChainMetadataBoost_nullConfidence_skipsStage_fallsBackToIdentity() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.chain", "metadata-boost" );
        final List< CandidateSection > in = List.of( sec( "A" ) );
        // no confidence lookup wired -> the boost stage is skipped -> empty chain -> IDENTITY
        assertSame( in, BundleServiceWiring.rerankerFor( p, null ).rerank( "q", in ) );
    }

    @Test
    void rerankChainMmrAndBoost_ordersStages() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.chain", "mmr, metadata-boost" );
        final SectionRerankChain chain = (SectionRerankChain) BundleServiceWiring.rerankerFor( p, slug -> Confidence.PROVISIONAL );
        assertEquals( 2, chain.stages().size() );
        assertInstanceOf( MmrSectionReranker.class, chain.stages().get( 0 ) );
        assertInstanceOf( MetadataBoostSectionReranker.class, chain.stages().get( 1 ) );
    }

    @Test
    void legacyRerankerFor_singleArg_stillIdentityByDefault() {
        // the old 1-arg rerankerFor must be unchanged (delegates with null confidence)
        final List< CandidateSection > in = List.of( sec( "A" ) );
        assertSame( in, BundleServiceWiring.rerankerFor( new Properties() ).rerank( "q", in ) );
    }

    @Test
    void metadataBoostFactorAndWindow_defaultsAndParse() {
        assertEquals( 0.05, BundleServiceWiring.metadataBoostFactor( new Properties() ), 1e-9 );
        assertEquals( 24, BundleServiceWiring.metadataBoostWindow( new Properties() ) );
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.metadata_boost.factor", "0.1" );
        p.setProperty( "wikantik.bundle.rerank.metadata_boost.window", "30" );
        assertEquals( 0.1, BundleServiceWiring.metadataBoostFactor( p ), 1e-9 );
        assertEquals( 30, BundleServiceWiring.metadataBoostWindow( p ) );
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest`
Expected: FAIL — the 2-arg `rerankerFor`, `metadataBoostFactor/Window`, and the `metadata-boost` case don't exist.

- [ ] **Step 3: Modify `BundleServiceWiring`** — add imports `com.wikantik.api.pagegraph.Confidence` and `java.util.function.Function`. Replace `rerankerFor`/`buildChain` and add helpers:

```java
    /** Back-compat 1-arg overload: no confidence lookup (metadata-boost stage, if requested, is skipped). */
    static SectionReranker rerankerFor( final Properties props ) {
        return rerankerFor( props, null );
    }

    /** Selects the reranker. Chain spec (wikantik.bundle.rerank.chain) → ordered SectionRerankChain;
     *  otherwise the legacy reranker.enabled → LLM / else IDENTITY path (unchanged). */
    static SectionReranker rerankerFor( final Properties props, final Function< String, Confidence > confidenceOf ) {
        final String chainSpec = props == null ? null : props.getProperty( "wikantik.bundle.rerank.chain" );
        if ( chainSpec != null && !chainSpec.isBlank() ) {
            return buildChain( chainSpec, props, confidenceOf );
        }
        final boolean enabled = props != null && Boolean.parseBoolean(
            props.getProperty( RerankerConfig.PREFIX + "enabled", "false" ) );
        return enabled ? new LlmSectionReranker( RerankerConfig.fromProperties( props ) ) : IDENTITY;
    }

    private static SectionReranker buildChain( final String spec, final Properties props,
                                               final Function< String, Confidence > confidenceOf ) {
        final java.util.List< SectionReranker > stages = new java.util.ArrayList<>();
        for ( final String raw : spec.split( "," ) ) {
            final String name = raw.trim().toLowerCase( java.util.Locale.ROOT );
            switch ( name ) {
                case "" -> { /* stray comma — ignore */ }
                case "mmr" -> stages.add( new MmrSectionReranker( mmrLambda( props ) ) );
                case "metadata-boost" -> {
                    if ( confidenceOf == null ) {
                        LOG.warn( "rerank stage 'metadata-boost' requested but no confidence lookup wired; skipping" );
                    } else {
                        stages.add( new MetadataBoostSectionReranker(
                            confidenceOf, metadataBoostFactor( props ), metadataBoostWindow( props ) ) );
                    }
                }
                case "llm" -> stages.add( new LlmSectionReranker( RerankerConfig.fromProperties( props ) ) );
                default -> LOG.warn( "Unknown rerank stage '{}' in wikantik.bundle.rerank.chain; skipping", name );
            }
        }
        return stages.isEmpty() ? IDENTITY : new SectionRerankChain( stages );
    }

    /** Confidence boost magnitude, {@code wikantik.bundle.rerank.metadata_boost.factor}, default 0.05. */
    static double metadataBoostFactor( final Properties props ) {
        if ( props == null ) return 0.05;
        return com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.rerank.metadata_boost.factor", 0.05 );
    }

    /** Top-N candidates the boost may reorder, {@code wikantik.bundle.rerank.metadata_boost.window}, default 24. */
    static int metadataBoostWindow( final Properties props ) {
        if ( props == null ) return 24;
        return (int) com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.rerank.metadata_boost.window", 24 );
    }
```

- [ ] **Step 4: Add the `build` overload** — keep the existing `build( retrieval, sourceMap, dao, pageManager, props )` but make it delegate to a new overload that also takes `confidenceOf`, defaulting `null`. Find the existing `build(...)` method; rename its body to accept a trailing `final Function< String, Confidence > confidenceOf` param, change its internal `rerankerFor( props )` call to `rerankerFor( props, confidenceOf )`, and add a 5-arg delegator:

```java
    public static BundleAssemblyService build( final ContextRetrievalService retrieval,
                                               final Map< RetrievalMode, SectionCandidateSource > sourceMap,
                                               final PageCanonicalIdsDao dao,
                                               final PageManager pageManager,
                                               final Properties props ) {
        return build( retrieval, sourceMap, dao, pageManager, props, null );
    }

    public static BundleAssemblyService build( final ContextRetrievalService retrieval,
                                               final Map< RetrievalMode, SectionCandidateSource > sourceMap,
                                               final PageCanonicalIdsDao dao,
                                               final PageManager pageManager,
                                               final Properties props,
                                               final Function< String, Confidence > confidenceOf ) {
        // ... existing body UNCHANGED except the reranker line:
        //     final SectionReranker reranker = rerankerFor( props, confidenceOf );
    }
```

- [ ] **Step 5: Run the wiring tests + confirm no regression**

Run: `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest`
Expected: PASS — all pre-existing cases (unchanged, since the 5-arg `build` and 1-arg `rerankerFor` delegate with null) + the 5 new cases.

- [ ] **Step 6: Build `confidenceOf` at the `WikiEngine` seam.** In `patchContextRetrievalService`, where `bundleSvc` is built via `BundleServiceWiring.build(...)`, construct a confidence lookup from `pageCanonicalIdsDao()` (slug → canonical_id) + the structural index (`canonical_id → Verification.confidence()`) and pass it as the new 6th arg. Read the method for the exact structural-index accessor: `pageGraphSubsystem` is a field; obtain the structural index via `pageGraphSubsystem != null ? pageGraphSubsystem.structuralIndexService() : null` (the same accessor used near `WikiEngine.java:1012`). `verificationOf( canonicalId )` returns `Optional< Verification >`; `Verification.confidence()` is the enum. Example:

```java
        final com.wikantik.api.pagegraph.StructuralIndexService evalStructural =
            pageGraphSubsystem != null ? pageGraphSubsystem.structuralIndexService() : null;
        final java.util.function.Function< String, com.wikantik.api.pagegraph.Confidence > confidenceOf =
            ( evalStructural == null ) ? null : slug -> pageCanonicalIdsDao() == null
                ? com.wikantik.api.pagegraph.Confidence.PROVISIONAL
                : pageCanonicalIdsDao().findBySlug( slug )
                    .map( com.wikantik.pagegraph.spine.PageCanonicalIdsDao.Row::canonicalId )
                    .flatMap( evalStructural::verificationOf )
                    .map( com.wikantik.api.pagegraph.Verification::confidence )
                    .orElse( com.wikantik.api.pagegraph.Confidence.PROVISIONAL );
```

Then change the `bundleSvc` build call to pass `confidenceOf` as the 6th arg: `BundleServiceWiring.build( svc, bundleSectionSources(), pageCanonicalIdsDao(), pageSubsystem != null ? pageSubsystem.pages() : null, properties, confidenceOf )`.

**Verify the accessors before writing:** confirm `StructuralIndexService.verificationOf(String) : Optional<Verification>` exists on the interface `pageGraphSubsystem.structuralIndexService()` returns (it is defined on `DefaultStructuralIndexService:238` — if it is NOT on the interface, obtain the concrete `DefaultStructuralIndexService` the same way the structural-spine wiring does, or add the method to the interface). Confirm `Verification.confidence()` and the `Confidence`/`Verification` package (`com.wikantik.api.pagegraph`). If the structural index or `verificationOf` is genuinely unreachable at this seam, STOP and report BLOCKED rather than fabricating — the feature degrades correctly with `confidenceOf == null` (boost stage skipped), so a null is acceptable if wiring is truly blocked, but flag it.

- [ ] **Step 7: Document the config keys** (commented) in `wikantik.properties`, in the existing rerank block:

```properties
# metadata-boost stage: bounded confidence tie-breaker (AUTHORITATIVE up, STALE down) applied to the
# top-N candidates. Only active when 'metadata-boost' is in wikantik.bundle.rerank.chain AND a
# confidence lookup is wired. factor = boost magnitude (0 disables; small keeps it a tie-breaker).
# wikantik.bundle.rerank.metadata_boost.factor = 0.05
# wikantik.bundle.rerank.metadata_boost.window = 24
```

- [ ] **Step 8: Compile-check + commit**

Run: `mvn -q -o compile -pl wikantik-main` (or `mvn test-compile -pl wikantik-main`) then `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest`
Expected: compiles; wiring tests green.

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-main/src/main/resources/ini/wikantik.properties \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java
git commit -m "feat(bundle): wire metadata-boost rerank stage + confidence lookup at bundle seam"
```

---

### Task 3: Config docs + measurement gate + full-suite

**Files:**
- Modify: `eval/bundle-corpus/baseline-notes.md` (append the metadata-boost measurement procedure + results table)

- [ ] **Step 1: Append the measurement gate** to `eval/bundle-corpus/baseline-notes.md`:

```markdown
## Metadata-boost rerank measurement gate (Stage 2, 2026-07-10)

Confidence is a QUALITY signal, not relevance — this stage is a bounded tie-breaker. Success =
NO recall@12 regression (a neutral corpus result is the expected, acceptable outcome for a
tie-breaker); the verified-above-stale behavior is proven by unit test on a constructed equal-score
set. Manual run against a live-index deployment with a populated page_verification table:

1. Control: `wikantik.bundle.rerank.chain` unset (or `= mmr`). Record recall@12.
2. Treatment: `wikantik.bundle.rerank.chain = metadata-boost` (or `mmr, metadata-boost`), redeploy, re-run.
3. ACCEPT only if treatment recall@12 >= control − 0.0 (no regression, floor 0.74). If it regresses,
   REJECT and record in the dead-levers list — a quality tie-breaker that costs recall is not worth it.
4. Sweep factor in {0.03, 0.05, 0.10} and window in {12, 24}; keep the largest non-regressing factor.
   Note: on a corpus whose gold pages have uniform confidence, expect ~zero movement — that is a
   PASS (no regression), and the stage still reorders near-ties in production where confidence varies.

| Config | recall@12 | p95 assemble latency | verdict |
|--------|-----------|----------------------|---------|
| chain = mmr (control) |  |  | baseline |
| chain = mmr, metadata-boost (factor 0.05, window 24) |  |  |  |
| chain = mmr, metadata-boost (factor 0.10, window 24) |  |  |  |
```

- [ ] **Step 2: Full module unit suite** — prove no regression + flag-off byte-identical.

Run: `mvn test -pl wikantik-main`
Expected: BUILD SUCCESS; quote `Tests run: N, Failures: 0, Errors: 0`. The chain default-unset means no existing test activates the boost.

- [ ] **Step 3: Commit**

```bash
git add eval/bundle-corpus/baseline-notes.md
git commit -m "docs(bundle): metadata-boost measurement gate (quality tie-breaker, no-regression)"
```

---

## Acceptance criteria mapping (from RoadmapComposableReranking, Stage 2)

| Brief gate | Covered by |
|---|---|
| Flag-off byte-identical | Task 2 `legacyRerankerFor_singleArg_stillIdentityByDefault` + 5-arg `build`/1-arg `rerankerFor` delegators + Task 3 full-suite; default properties leave chain unset |
| No recall regression (≥ 0.74) | Task 3 manual measurement gate (live index required; honest — not hermetic) |
| Stage's own metric moves (verified above equal-score stale) | Task 1 `verifiedRanksAboveStale_whenDenseScoresEqual` (unit) |
| Latency negligible | Top-`window` (≤24) bounds confidence lookups; Task 3 p95 column records the real number |

**Honest note:** confidence is a quality, not relevance, signal — the corpus gate's expected PASS is "no regression / ~neutral," and the stage's value (surfacing verified content among near-ties) is realized in production where confidence varies, proven in unit by the constructed equal-score test. If the corpus gate regresses, the stage is rejected as a dead lever (measure-first). Knee-cutoff and embedding-vector MMR remain deferred (separate follow-ups).

## Self-Review

- **Spec coverage:** reranker stage (Task 1), chain wiring + confidence lookup + config (Task 2), measurement gate + full-suite (Task 3). Brief's Stage-2 "bounded multiplicative boosts from frontmatter — verified up, stale down; fixed signal set first" matches (Confidence enum, factor, window). Non-goals respected (no user-defined expression language; no knee).
- **Placeholder scan:** none — code complete; the one adaptive step is Task 2 Step 6 (`WikiEngine` structural-index accessor), handled with verify-then-BLOCKED-if-unreachable and a correct null-degradation fallback.
- **Type consistency:** `Function< String, Confidence >` used identically in `MetadataBoostSectionReranker` (Task 1), `rerankerFor`/`build` (Task 2), and the `WikiEngine` seam; `Confidence` = `com.wikantik.api.pagegraph.Confidence`; `MetadataBoostSectionReranker( Function, double, int )` signature consistent Task 1 ↔ Task 2 `buildChain`; `metadataBoostFactor`/`metadataBoostWindow` consistent Task 2 impl ↔ tests; `SectionRerankChain.stages()` (Phase 1) asserted in Task 2.
