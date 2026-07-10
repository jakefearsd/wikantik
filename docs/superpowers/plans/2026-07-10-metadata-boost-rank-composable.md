# Metadata-Boost Rank-Composable Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign `MetadataBoostSectionReranker` so it boosts a candidate's **incoming rank position** (bounded positional nudge) instead of its absolute `denseScore`, so the stage composes with upstream rerankers (MMR, LLM) instead of silently re-sorting their output back into denseScore order. This restores the `mmr, metadata-boost` chain combo.

**Architecture:** Replace the boost model `effective = denseScore·(1 + factor·sign)` with `adjusted_key = i − positions·sign(confidence)`, where `i` is the candidate's incoming index within the top-`window` and `sign` is +1 AUTHORITATIVE / 0 PROVISIONAL / −1 STALE. Stable-sort the window ascending by `adjusted_key`; append the rest unchanged. An AUTHORITATIVE item overtakes a STALE one only when they are within `2·positions` positions of each other — a uniform, bounded tie-breaker that ignores `denseScore` entirely (hence composes with any upstream ordering). Everything else (the chain SPI, wiring, confidence lookup, fail-closed contract) is unchanged; the config knob `factor` is renamed to `positions`.

**Tech Stack:** Java 25, JUnit 5, Log4j2, Maven. No new dependencies, no schema change, no new class — a behavior redesign of one existing class + a config rename.

## Design decisions (grounded in the shipped code)

1. **The stage already exists** (`MetadataBoostSectionReranker`, wired via `BundleServiceWiring.buildChain`'s `metadata-boost` case, off by default). This plan changes its algorithm + renames one config key; the reranker class, its chain wiring, the `WikiEngine` confidence lookup, and the `SectionReranker` fail-closed contract are otherwise untouched.

2. **Boost the incoming RANK, not `denseScore`.** The shipped version sorts the window by `denseScore·(1±factor)`, which discards the incoming order — so after MMR it re-sorts to denseScore order (final-review finding). The redesign uses the candidate's incoming index `i` as the relevance baseline: `adjusted_key = i − positions·sign`. Because it never reads `denseScore`, it respects whatever order the prior stage produced and only swaps confidence-differing near-neighbors.

3. **Bounded, uniform tie-breaker.** AUTHORITATIVE `key = i − positions`; STALE `key = i + positions`; PROVISIONAL/unknown `key = i`. An AUTHORITATIVE at position `i` overtakes a STALE at position `j < i` iff `i − positions < j + positions` ⇔ `i − j < 2·positions`. Default `positions = 1.5` ⇒ swaps only within 3 positions. It cannot cross a real relevance gap; among equal-confidence items the order is preserved exactly (stable sort, distinct keys) — the composability guarantee.

4. **Config rename `factor → positions`.** `wikantik.bundle.rerank.metadata_boost.factor` becomes `wikantik.bundle.rerank.metadata_boost.positions` (default 1.5). Safe: the key was only ever commented/off, never set in any deployment. `positions` is clamped to `[0.0, 10.0]` (out of range → 1.5); `0` ⇒ identity.

5. **`mmr, metadata-boost` is valid again.** The prior branch's doc caveat ("metadata-boost must be the sole ordering stage") is now false and must be reverted; the measurement gate's `mmr, metadata-boost` row is restored.

## Global Constraints

- **Java 25.** Change is confined to `com.wikantik.knowledge.bundle` (wikantik-main) + `wikantik.properties` + `baseline-notes.md`. No new package/module/dependency, no schema change.
- **Reorder-only / fail-closed unchanged:** never drop a section; `null` confidenceOf / `positions == 0` / `≤ 1` section / any exception ⇒ return input order (same as shipped).
- **Off by default / byte-identical when chain unset:** the default `wikantik.properties` leaves `wikantik.bundle.rerank.chain` unset; existing `BundleServiceWiringTest` cases (other than the renamed factor→positions helper test) stay green.
- **Never swallow an exception without a `LOG.warn`** (CLAUDE.md).
- **Code style:** spaces in generics/parens, `final` on params/locals, Log4j2. Keep the ASF header (existing file).
- **TDD:** failing test first. Run one class with `mvn test -pl wikantik-main -Dtest=ClassName`.
- Worked directly on `main` (sole-dev) — no branch; commit directly.

---

### Task 1: Redesign `MetadataBoostSectionReranker` to rank-composable

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/MetadataBoostSectionReranker.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/MetadataBoostSectionRerankerTest.java`

**Interfaces:**
- Consumes: `SectionReranker`, `CandidateSection`, `com.wikantik.api.pagegraph.Confidence` (unchanged).
- Produces: constructor `MetadataBoostSectionReranker( Function< String, Confidence > confidenceOf, double positions, int window )` (renamed 2nd param; `positions` clamped `[0,10]`, out of range → 1.5; `window ≤ 0 → 24`). `rerank` computes `adjusted_key[i] = i − positions·sign(confidence(slug))` for the top-`window` candidates (i = index within the window), stable-sorts the window ascending by key, appends `subList(window, size)` unchanged. Returns input unchanged when `confidenceOf == null`, `positions == 0`, `≤ 1` section, or any exception. Does NOT read `denseScore`.

- [ ] **Step 1: Replace the test file body** (the boost semantics changed from denseScore-multiplicative to rank-positional; the old tests assert the old model). Rewrite `MetadataBoostSectionRerankerTest`:

```java
/* <keep the existing ASF license header — lines 1–18> */
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

    // denseScore is deliberately uniform/irrelevant: the boost works on INCOMING ORDER, not denseScore.
    private static CandidateSection sec( final String slug ) {
        return new CandidateSection( slug, List.of( "H" ), slug + " text", 0.5 );
    }

    private static Function< String, Confidence > conf( final Map< String, Confidence > m ) {
        return slug -> m.getOrDefault( slug, Confidence.PROVISIONAL );
    }

    @Test
    void authoritativeAdjacentToStale_isPromoted() {
        // incoming order [stale, auth]; positions 1.5 -> auth (i=1) key -0.5 < stale (i=0) key 1.5 -> [auth, stale]
        final var in = List.of( sec( "stale" ), sec( "auth" ) );
        final var c = conf( Map.of( "stale", Confidence.STALE, "auth", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( c, 1.5, 24 ).rerank( "q", in );
        assertEquals( List.of( "auth", "stale" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void ignoresDenseScore_usesIncomingOrder() {
        // Same incoming order as above but give 'stale' the HIGHER denseScore — must not change the result,
        // proving the boost is rank-based (composable), not denseScore-based.
        final var in = List.of(
            new CandidateSection( "stale", List.of( "H" ), "t", 0.99 ),
            new CandidateSection( "auth", List.of( "H" ), "t", 0.01 ) );
        final var c = conf( Map.of( "stale", Confidence.STALE, "auth", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( c, 1.5, 24 ).rerank( "q", in );
        assertEquals( List.of( "auth", "stale" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void equalConfidence_preservesIncomingOrder_exactly() {
        // all PROVISIONAL -> keys = i -> order unchanged. This is the composability guarantee:
        // metadata-boost does not disturb an upstream stage's ordering among same-confidence items.
        final var in = List.of( sec( "a" ), sec( "b" ), sec( "c" ), sec( "d" ) );
        final var out = new MetadataBoostSectionReranker( conf( Map.of() ), 1.5, 24 ).rerank( "q", in );
        assertEquals( List.of( "a", "b", "c", "d" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void boundedByPositions_cannotCrossALargeGap() {
        // incoming [p0,p1,p2,p3,auth4]; positions 1.5 -> auth key 2.5 -> auth moves 4->3, not to the top.
        final var in = List.of( sec( "p0" ), sec( "p1" ), sec( "p2" ), sec( "p3" ), sec( "auth4" ) );
        final var c = conf( Map.of( "auth4", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( c, 1.5, 24 ).rerank( "q", in );
        assertEquals( List.of( "p0", "p1", "p2", "auth4", "p3" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void positionsZero_isIdentity() {
        final var in = List.of( sec( "a" ), sec( "b" ) );
        assertSame( in, new MetadataBoostSectionReranker( conf( Map.of() ), 0.0, 24 ).rerank( "q", in ) );
    }

    @Test
    void nullConfidenceLookup_isIdentity() {
        final var in = List.of( sec( "a" ), sec( "b" ) );
        assertSame( in, new MetadataBoostSectionReranker( null, 1.5, 24 ).rerank( "q", in ) );
    }

    @Test
    void neverDrops_andBeyondWindowUntouched() {
        // window=2: only indices 0,1 are boostable; indices 2,3 keep their order and all survive.
        final var in = List.of( sec( "stale0" ), sec( "auth1" ), sec( "x2" ), sec( "y3" ) );
        final var c = conf( Map.of( "stale0", Confidence.STALE, "auth1", Confidence.AUTHORITATIVE ) );
        final var out = new MetadataBoostSectionReranker( c, 1.5, 2 ).rerank( "q", in );
        assertEquals( in.size(), out.size() );
        assertTrue( out.containsAll( in ) );
        // window [stale0,auth1] -> [auth1,stale0]; tail [x2,y3] unchanged
        assertEquals( List.of( "auth1", "stale0", "x2", "y3" ), out.stream().map( CandidateSection::slug ).toList() );
    }

    @Test
    void lookupThrows_returnsInputOrder() {
        final var in = List.of( sec( "a" ), sec( "b" ) );
        final Function< String, Confidence > boom = slug -> { throw new IllegalStateException( "boom" ); };
        assertSame( in, new MetadataBoostSectionReranker( boom, 1.5, 24 ).rerank( "q", in ) );
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest=MetadataBoostSectionRerankerTest`
Expected: FAIL — the constructor still takes `factor` with the old multiplicative semantics; `authoritativeAdjacentToStale_isPromoted` / `ignoresDenseScore` / `boundedByPositions` assert the new rank model.

- [ ] **Step 3: Rewrite the implementation** (keep the ASF header + package + `implements SectionReranker`):

```java
    private final Function< String, Confidence > confidenceOf;
    private final double positions;
    private final int window;

    MetadataBoostSectionReranker( final Function< String, Confidence > confidenceOf,
                                  final double positions, final int window ) {
        this.confidenceOf = confidenceOf;
        this.positions = ( positions < 0.0 || positions > 10.0 ) ? 1.5 : positions;
        this.window = window > 0 ? window : 24;
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        if ( sections == null ) return List.of();
        if ( confidenceOf == null || positions == 0.0 || sections.size() <= 1 ) return sections;
        try {
            final int w = Math.min( window, sections.size() );
            // decorate: adjusted rank key = incoming index − positions·sign(confidence), computed once per element
            final List< Ranked > decorated = new ArrayList<>( w );
            for ( int i = 0; i < w; i++ ) {
                final CandidateSection c = sections.get( i );
                decorated.add( new Ranked( c, i - positions * sign( confidenceOf.apply( c.slug() ) ) ) );
            }
            decorated.sort( ( a, b ) -> Double.compare( a.key(), b.key() ) );  // ascending; stable → ties keep input order
            final List< CandidateSection > out = new ArrayList<>( sections.size() );
            for ( final Ranked r : decorated ) out.add( r.section() );
            out.addAll( sections.subList( w, sections.size() ) );  // beyond the window: untouched
            return out;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Metadata-boost rerank failed ({}); using prior order", e.getMessage() );
            return sections;
        }
    }

    private static int sign( final Confidence conf ) {
        if ( conf == Confidence.AUTHORITATIVE ) return 1;
        if ( conf == Confidence.STALE ) return -1;
        return 0;  // PROVISIONAL or null
    }

    /** A candidate decorated with its adjusted rank key (lower = earlier). */
    private record Ranked( CandidateSection section, double key ) {}
```

Update the class Javadoc to describe the rank-positional model ("boosts incoming rank by ±positions per confidence; an AUTHORITATIVE item overtakes a STALE one only within 2·positions positions; ignores denseScore so it composes with any upstream ordering"). Remove the old `boosted()`/`Scored` members. Keep imports tidy (`java.util.ArrayList`, `java.util.List`, `java.util.function.Function`, the `Confidence` import, Log4j2).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=MetadataBoostSectionRerankerTest`
Expected: PASS (8 tests). If `boundedByPositions_cannotCrossALargeGap` fails, re-derive the keys by hand (p0..p3 → 0,1,2,3; auth4 → 4−1.5=2.5; ascending → p0,p1,p2,auth4,p3) and fix the implementation — do not weaken the assertion.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/MetadataBoostSectionReranker.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/MetadataBoostSectionRerankerTest.java
git commit -m "refactor(bundle): rank-composable metadata boost (boost incoming rank, not denseScore)"
```

---

### Task 2: Config rename (`factor` → `positions`) + restore `mmr, metadata-boost` docs + full suite

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java` (`metadataBoostFactor` → `metadataBoostPositions`; the `metadata-boost` case constructs with `metadataBoostPositions(props)`)
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java` (the `metadataBoostFactorAndWindow_defaultsAndParse` test → positions)
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (rename the key comment; remove the sole-ordering NOTE)
- Modify: `eval/bundle-corpus/baseline-notes.md` (restore the `mmr, metadata-boost` gate; drop the composition caveat)

**Interfaces:**
- Produces: `metadataBoostPositions( Properties )` (`wikantik.bundle.rerank.metadata_boost.positions`, default 1.5); the `metadata-boost` chain case passes `metadataBoostWindow(props)` and `metadataBoostPositions(props)` to the reranker.

- [ ] **Step 1: Update the wiring test** — replace `metadataBoostFactorAndWindow_defaultsAndParse` in `BundleServiceWiringTest`:

```java
    @Test
    void metadataBoostPositionsAndWindow_defaultsAndParse() {
        assertEquals( 1.5, BundleServiceWiring.metadataBoostPositions( new Properties() ), 1e-9 );
        assertEquals( 24, BundleServiceWiring.metadataBoostWindow( new Properties() ) );
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.metadata_boost.positions", "3" );
        p.setProperty( "wikantik.bundle.rerank.metadata_boost.window", "30" );
        assertEquals( 3.0, BundleServiceWiring.metadataBoostPositions( p ), 1e-9 );
        assertEquals( 30, BundleServiceWiring.metadataBoostWindow( p ) );
    }
```

The other metadata-boost wiring tests (`rerankChainMetadataBoost_*`, `rerankChainMmrAndBoost_ordersStages`) are unaffected — leave them.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest`
Expected: FAIL — `metadataBoostPositions` does not exist.

- [ ] **Step 3: Rename the helper + update the case** in `BundleServiceWiring`:

Replace `metadataBoostFactor`:

```java
    /** Confidence rank-boost magnitude in positions, {@code wikantik.bundle.rerank.metadata_boost.positions},
     *  default 1.5 (an AUTHORITATIVE section overtakes a STALE one only within 2·positions positions). */
    static double metadataBoostPositions( final Properties props ) {
        if ( props == null ) return 1.5;
        return com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.rerank.metadata_boost.positions", 1.5 );
    }
```

And in `buildChain`'s `metadata-boost` case, construct with positions:

```java
                case "metadata-boost" -> {
                    if ( confidenceOf == null ) {
                        LOG.warn( "rerank stage 'metadata-boost' requested but no confidence lookup wired; skipping" );
                    } else {
                        stages.add( new MetadataBoostSectionReranker(
                            confidenceOf, metadataBoostPositions( props ), metadataBoostWindow( props ) ) );
                    }
                }
```

- [ ] **Step 4: Run the wiring tests**

Run: `mvn test -pl wikantik-main -Dtest=BundleServiceWiringTest`
Expected: PASS (all cases; the metadata-boost stage-building tests still pass since the constructor arity is unchanged).

- [ ] **Step 5: Update `wikantik.properties`** — rename the key and remove the sole-ordering NOTE added last branch. The metadata-boost comment block should read:

```properties
# metadata-boost stage: bounded confidence rank tie-breaker (AUTHORITATIVE up, STALE down) that nudges a
# candidate by up to ±positions rank slots relative to the upstream order. Composes with mmr/llm (it
# boosts incoming RANK, not denseScore). Active only when 'metadata-boost' is in the chain AND a
# confidence lookup is wired. positions = max rank shift (0 disables); small keeps it a tie-breaker.
# wikantik.bundle.rerank.metadata_boost.positions = 1.5
# wikantik.bundle.rerank.metadata_boost.window = 24
```

- [ ] **Step 6: Restore the measurement gate** in `eval/bundle-corpus/baseline-notes.md` — in the "Metadata-boost rerank measurement gate" section: remove the "Composition caveat" sentence added last branch, and change the treatment rows back to the composed combo:

```markdown
- Treatment now composes: test `chain = mmr, metadata-boost` against control `chain = mmr` — the
  rank-based boost preserves MMR's ordering among equal-confidence sections and only swaps
  confidence-differing near-neighbours. Sweep positions in {1, 1.5, 3}.

| Config | recall@12 | p95 assemble latency | verdict |
|--------|-----------|----------------------|---------|
| chain = mmr (control) |  |  | baseline |
| chain = mmr, metadata-boost (positions 1.5, window 24) |  |  |  |
| chain = mmr, metadata-boost (positions 3, window 24) |  |  |  |
```

- [ ] **Step 7: Full module unit suite** — prove no regression + flag-off byte-identical.

Run: `mvn test -pl wikantik-main`
Expected: BUILD SUCCESS; quote `Tests run: N, Failures: 0, Errors: 0`.

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/BundleServiceWiringTest.java \
        wikantik-main/src/main/resources/ini/wikantik.properties \
        eval/bundle-corpus/baseline-notes.md
git commit -m "feat(bundle): rename metadata-boost factor->positions; restore mmr,metadata-boost combo"
```

---

## Acceptance criteria mapping

| Requirement | Covered by |
|---|---|
| Composes with upstream (respects incoming order) | Task 1 `ignoresDenseScore_usesIncomingOrder` + `equalConfidence_preservesIncomingOrder_exactly` |
| Bounded tie-breaker (can't cross a real gap) | Task 1 `boundedByPositions_cannotCrossALargeGap` |
| Verified promotion | Task 1 `authoritativeAdjacentToStale_isPromoted` |
| Reorder-only / fail-closed unchanged | Task 1 `neverDrops_*`, `positionsZero_isIdentity`, `nullConfidenceLookup_isIdentity`, `lookupThrows_*` |
| Flag-off byte-identical | Task 2 full suite; chain unset by default |
| No recall regression (composed) | Task 2 measurement gate (manual, live index) — now testable on `mmr, metadata-boost` honestly |

**Honest note:** this is a redesign of an off-by-default stage; the manual `mmr, metadata-boost` recall + p95 measurement (Task 2 gate) remains the arbiter before enabling, and the p95 sweep is mandatory (the confidence lookup is DB-backed, bounded to the window). Confidence remains a quality signal — a neutral recall result is still a PASS.

## Self-Review

- **Spec coverage:** rank model (Task 1), config rename + doc restoration + suite (Task 2). The final-review finding (composition with MMR) is directly resolved: `ignoresDenseScore_usesIncomingOrder` + `equalConfidence_preservesIncomingOrder_exactly` prove the stage now respects upstream order.
- **Placeholder scan:** none — code complete; no adaptive steps (the wiring/lookup from the prior branch is unchanged, only the helper name + reranker internals).
- **Type consistency:** `MetadataBoostSectionReranker( Function< String, Confidence >, double, int )` arity unchanged (only param name/semantics), so `buildChain` construction stays 3-arg; `metadataBoostPositions`/`metadataBoostWindow` consistent between Task 2 impl and test; `Confidence` = `com.wikantik.api.pagegraph.Confidence`.
