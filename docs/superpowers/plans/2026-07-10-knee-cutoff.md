# Bundle Knee-Cutoff (Dynamic-N) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the bundle's fixed top-12 cut with an optional score-elbow ("knee") cut, so a thin query whose dense relevance falls off a cliff returns fewer, denser sections instead of 12 padded with weak matches — sharpening the coverage signal. Off by default (byte-identical when disabled).

**Architecture:** The knee is NOT a rerank-chain stage — the `SectionReranker` contract is reorder-only ("never drop"), and a cutoff drops. Instead the knee is a **dynamic-N selector** applied at the assembler's existing top-N cut: `DefaultBundleAssemblyService.assemble` computes `effectiveN = knee(cand.sections' dense scores, maxSections)` from the dense-sorted candidates (which carry `denseScore`, and `cand.topSimilarity()` is the top cosine already used for coverage), then breaks the output loop at `effectiveN` instead of `maxSections`. Pure knee logic lives in a new `KneeCutoff`; the assembler gains one nullable collaborator.

**Tech Stack:** Java 25, JUnit 5, Log4j2, Maven. No new dependency, no schema change, no new module.

## Design decisions (grounded in the shipped code)

1. **Not a chain stage — a dynamic-N in the assembler.** `SectionReranker` is reorder-only. `DefaultBundleAssemblyService.assemble` does `ranked = reranker.rerank(query, cand.sections())` → dedup loop → `if (out.size() >= maxSections) break`. The knee changes only that break threshold: `effectiveN` in place of the constant `maxSections`. This is the drop-capable seam, and it never touches the rerank chain.

2. **Knee is computed from the DENSE-sorted candidate scores, not the reranked order.** `cand.sections()` from `DenseChunkSectionSource` is sorted descending by `denseScore`; `cand.topSimilarity()` is the top cosine. The knee = "how many sections are worth keeping" from that dense distribution; the rerank decided the order. Applied as `min(maxSections, kneeN)` on the reranked output loop: rerank picks *which/what order*, knee picks *how many*.

3. **Ratio-to-top elbow rule (simple, robust, testable).** `effectiveN = count of leading candidates whose denseScore ≥ topScore · retainRatio`, clamped to `[1, maxSections]` (always keep the top result; never exceed the cap). Default `retainRatio = 0.5` — keep sections at least half as strong as the best. Because candidate scores are descending, this is the first index where the score falls below the retain line.

4. **No-op when there is no per-section cosine.** The page-gated `RetrievalSectionSource` sets `topSimilarity = -1` and has no meaningful per-section scores — the knee must degrade to `maxSections` there (same guard the coverage calculator uses: `topSimilarity < 0`). Disabled config ⇒ `maxSections` always ⇒ byte-identical.

5. **Off by default.** `wikantik.bundle.knee.enabled = false`. When disabled the assembler uses `maxSections` exactly as today; the default `wikantik.properties` documents the keys commented.

6. **Coverage is computed from the KEPT sections** (unchanged): `coverageCalc.compute(cand.topSimilarity(), out)` already runs on the final `out` list — a shorter `out` from the knee naturally yields a coverage block over the kept sections, which is the intended sharpening (fewer sections but the same strong `topSimilarity`).

## Global Constraints

- **Java 25.** New `KneeCutoff` in existing package `com.wikantik.knowledge.bundle` (wikantik-main); no new package/module/dependency; no schema change.
- **ASF license header verbatim** on the new `.java` file (copy `SectionReranker.java` lines 1–18).
- **Byte-identical when disabled:** the default `wikantik.bundle.knee.enabled=false` ⇒ `effectiveN == maxSections`; every existing `DefaultBundleAssemblyServiceTest` and bundle test stays green. New assembler collaborator added via a constructor overload so existing constructors/call sites are untouched.
- **Never drop below 1 / never exceed maxSections.** The knee only ever returns `[1, maxSections]`.
- **Never swallow an exception without a `LOG.warn`** (CLAUDE.md); the knee is pure and total (no throw path), but the assembler must degrade to `maxSections` if anything is off.
- **Code style:** spaces in generics/parens, `final`, Log4j2.
- **TDD:** failing test first. Run one class with `mvn test -pl wikantik-main -Dtest=ClassName`.
- Worked directly on `main` (sole-dev) — no branch; commit directly.

---

### Task 1: `KneeCutoff` — pure dynamic-N selector

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/KneeCutoff.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/KneeCutoffTest.java`

**Interfaces:**
- Consumes: `CandidateSection` (for `denseScore()`).
- Produces: `record KneeCutoff( boolean enabled, double retainRatio )` with `static KneeCutoff disabled()` (a `maxSections`-passthrough), `static KneeCutoff of( boolean enabled, double retainRatio )` (retainRatio clamped to `(0,1]`, out of range → 0.5), and `int effectiveN( List< CandidateSection > denseSorted, double topSimilarity, int maxSections )` returning `maxSections` when disabled or `topSimilarity < 0` or the list is empty, else `clamp( count of leading denseScore ≥ topSimilarity·retainRatio, 1, maxSections )`.

- [ ] **Step 1: Write the failing test**

```java
/* <ASF license header — copy SectionReranker.java lines 1–18> */
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KneeCutoffTest {

    private static CandidateSection sec( final double score ) {
        return new CandidateSection( "p" + score, List.of( "H" ), "t", score );
    }

    // topSimilarity is the top dense cosine (== denseScore of the first candidate for the dense path).
    private static int n( final KneeCutoff k, final List< CandidateSection > s, final int max ) {
        return k.effectiveN( s, s.isEmpty() ? -1.0 : s.get( 0 ).denseScore(), max );
    }

    @Test
    void disabled_alwaysReturnsMaxSections() {
        final var s = List.of( sec( 0.9 ), sec( 0.1 ), sec( 0.05 ) );
        assertEquals( 12, n( KneeCutoff.disabled(), s, 12 ) );
    }

    @Test
    void thinQuery_cutsBelowTheRetainLine() {
        // top 0.90; retainRatio 0.5 -> keep >= 0.45. Scores 0.90,0.80,0.20,0.10 -> keep 2.
        final var s = List.of( sec( 0.90 ), sec( 0.80 ), sec( 0.20 ), sec( 0.10 ) );
        assertEquals( 2, n( KneeCutoff.of( true, 0.5 ), s, 12 ) );
    }

    @Test
    void flatStrongQuery_keepsAllUpToMax() {
        // all within ratio of the top -> keep all, capped at maxSections.
        final var s = List.of( sec( 0.90 ), sec( 0.88 ), sec( 0.85 ), sec( 0.82 ) );
        assertEquals( 4, n( KneeCutoff.of( true, 0.5 ), s, 12 ) );
        assertEquals( 3, n( KneeCutoff.of( true, 0.5 ), s, 3 ), "never exceeds maxSections" );
    }

    @Test
    void alwaysKeepsAtLeastTheTop() {
        // even a lone strong section above a cliff keeps >= 1.
        final var s = List.of( sec( 0.90 ), sec( 0.10 ), sec( 0.05 ) );
        assertEquals( 1, n( KneeCutoff.of( true, 0.5 ), s, 12 ) );
    }

    @Test
    void noPerSectionCosine_topSimilarityNegative_returnsMaxSections() {
        // page-gated path: topSimilarity -1 -> knee cannot apply -> maxSections.
        final var s = List.of( sec( 0.0 ), sec( 0.0 ) );
        assertEquals( 12, KneeCutoff.of( true, 0.5 ).effectiveN( s, -1.0, 12 ) );
    }

    @Test
    void emptyCandidates_returnsMaxSections() {
        assertEquals( 12, KneeCutoff.of( true, 0.5 ).effectiveN( List.of(), -1.0, 12 ) );
    }

    @Test
    void retainRatioClampedToDefault_whenOutOfRange() {
        // ratio 2.0 is invalid -> default 0.5; scores 0.90,0.80,0.20 -> keep 2 (>=0.45).
        final var s = List.of( sec( 0.90 ), sec( 0.80 ), sec( 0.20 ) );
        assertEquals( 2, n( KneeCutoff.of( true, 2.0 ), s, 12 ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=KneeCutoffTest`
Expected: FAIL — `KneeCutoff` does not exist.

- [ ] **Step 3: Write the implementation**

```java
/* <ASF license header> */
package com.wikantik.knowledge.bundle;

import java.util.List;

/** Dynamic-N selector: how many leading (dense-sorted) sections to keep before the score falls off a
 *  cliff. Ratio-to-top elbow — keep candidates whose {@code denseScore ≥ topSimilarity·retainRatio},
 *  clamped to {@code [1, maxSections]}. Disabled, or a non-dense path ({@code topSimilarity < 0}), or an
 *  empty list all return {@code maxSections} (the fixed cut — byte-identical to pre-knee behaviour). */
record KneeCutoff( boolean enabled, double retainRatio ) {

    static KneeCutoff disabled() {
        return new KneeCutoff( false, 0.5 );
    }

    static KneeCutoff of( final boolean enabled, final double retainRatio ) {
        final double r = ( retainRatio > 0.0 && retainRatio <= 1.0 ) ? retainRatio : 0.5;
        return new KneeCutoff( enabled, r );
    }

    int effectiveN( final List< CandidateSection > denseSorted, final double topSimilarity, final int maxSections ) {
        if ( !enabled || topSimilarity < 0.0 || denseSorted.isEmpty() ) {
            return maxSections;
        }
        final double retainLine = topSimilarity * retainRatio;
        int kept = 0;
        for ( final CandidateSection c : denseSorted ) {
            if ( c.denseScore() < retainLine ) break;   // descending scores → first miss ends the run
            kept++;
            if ( kept >= maxSections ) break;
        }
        return Math.max( 1, Math.min( kept, maxSections ) );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=KneeCutoffTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/KneeCutoff.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/KneeCutoffTest.java
git commit -m "feat(bundle): KneeCutoff dynamic-N selector (ratio-to-top elbow, off-by-default passthrough)"
```

---

### Task 2: Apply the knee in the assembler + wire config + measurement gate + full suite

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java` (add a `KneeCutoff` collaborator via a constructor overload; use `effectiveN` as the output-loop break threshold)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java` (build the `KneeCutoff` from config, pass it to the assembler; helpers `kneeEnabled`/`kneeRetainRatio`)
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (document the keys, commented / off)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceTest.java` (add cases; existing stay green)

**Interfaces:**
- Consumes: `KneeCutoff` (Task 1). The assembler’s canonical (map-based) constructor gains a trailing `KneeCutoff knee` param; the existing constructors delegate with `KneeCutoff.disabled()` (so existing call sites/tests are byte-identical). In `assemble`, after `cand = src.candidates(query)` and `ranked = reranker.rerank(...)`, compute `final int cut = knee.effectiveN( cand.sections(), cand.topSimilarity(), maxSections );` and change the loop break to `if ( out.size() >= cut ) break;`.
- Produces (wiring): `kneeEnabled( Properties )` (`wikantik.bundle.knee.enabled`, default false), `kneeRetainRatio( Properties )` (`wikantik.bundle.knee.retain_ratio`, default 0.5); `BundleServiceWiring.build` constructs `KneeCutoff.of( kneeEnabled(props), kneeRetainRatio(props) )` and passes it into the assembler.

- [ ] **Step 1: Write the failing test** — a `DefaultBundleAssemblyService` built with a knee that cuts, proving the output is shortened; and one built disabled proving `maxSections` behaviour. **Read `DefaultBundleAssemblyServiceTest` first** for the existing stub `SectionCandidateSource`/reranker helpers and reuse them. Add:

```java
    @Test
    void knee_cutsThinResultsBelowMaxSections() {
        // a dense source returning 4 sections with a cliff after the 2nd; knee retainRatio 0.5.
        // Build the service via the map/knee-aware constructor with an enabled KneeCutoff and assert
        // the bundle has 2 sections (not the full set), while the same source with KneeCutoff.disabled()
        // yields all (up to maxSections).
        // (Use the test's existing candidate-source stub; scores 0.90,0.80,0.20,0.10; maxSections 12.)
    }
```

Fill this in concretely against the test file’s existing fixtures (a stub `SectionCandidateSource` returning `SectionCandidates.of(list, topSimilarity)` with the 4 scored sections, canonicalIdOf/versionOf stubs). Assert `assemble("q").sections().size() == 2` for the enabled knee and `== 4` for `KneeCutoff.disabled()`.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=DefaultBundleAssemblyServiceTest`
Expected: FAIL — the knee-aware constructor / cut behaviour doesn't exist.

- [ ] **Step 3: Modify `DefaultBundleAssemblyService`** — add a `private final KneeCutoff knee;` field. Add a new canonical constructor param `final KneeCutoff knee` (last), assign it; have every other constructor delegate with `KneeCutoff.disabled()`. In `assemble`:

```java
        final SectionCandidates cand = src.candidates( query );
        final List< CandidateSection > ranked = reranker.rerank( query, cand.sections() );
        final int cut = knee.effectiveN( cand.sections(), cand.topSimilarity(), maxSections );
        // ... existing dedup loop, but:
        if ( out.size() >= cut ) break;   // was: >= maxSections
```

Keep everything else identical (dedup by SectionKey, citation build, coverage from `out`).

- [ ] **Step 4: Run the assembler tests**

Run: `mvn test -pl wikantik-main -Dtest=DefaultBundleAssemblyServiceTest`
Expected: PASS — the new knee cases + all pre-existing cases (which use the delegating constructors → `KneeCutoff.disabled()` → `maxSections`, byte-identical).

- [ ] **Step 5: Wire config in `BundleServiceWiring`** — add `kneeEnabled`/`kneeRetainRatio` helpers and construct+pass the `KneeCutoff` in `build`:

```java
    static boolean kneeEnabled( final Properties props ) {
        return props != null && Boolean.parseBoolean( props.getProperty( "wikantik.bundle.knee.enabled", "false" ) );
    }

    static double kneeRetainRatio( final Properties props ) {
        if ( props == null ) return 0.5;
        return com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.knee.retain_ratio", 0.5 );
    }
```

In `build`, where the `DefaultBundleAssemblyService` is constructed, pass `KneeCutoff.of( kneeEnabled( props ), kneeRetainRatio( props ) )` as the new trailing arg. If `build` currently calls a constructor that does not take the knee, switch it to the knee-aware one.

- [ ] **Step 6: Document config** (commented / off) in `wikantik.properties`, in the bundle block:

```properties
# Knee cutoff: dynamic-N — return fewer, denser sections when dense relevance falls off a cliff,
# instead of always padding to the top-N. OFF by default (fixed top-N). retain_ratio keeps sections
# whose dense score is >= retain_ratio × the top score (0.5 = at least half as strong as the best).
# wikantik.bundle.knee.enabled = false
# wikantik.bundle.knee.retain_ratio = 0.5
```

- [ ] **Step 7: Full module unit suite**

Run: `mvn test -pl wikantik-main`
Expected: BUILD SUCCESS; quote `Tests run: N, Failures: 0, Errors: 0`. Knee disabled by default ⇒ no existing test's output shape changes.

- [ ] **Step 8: Append the measurement gate** to `eval/bundle-corpus/baseline-notes.md`:

```markdown
## Knee-cutoff measurement gate (2026-07-10)

Knee is a PRECISION/coverage-sharpening lever, not a recall lever — cutting the tail can only lower
recall@12 (fewer sections returned). Success = recall@12 does not regress meaningfully while the
coverage signal sharpens (weak-coverage bundles get shorter). Manual run against a live index:

1. Control: `wikantik.bundle.knee.enabled = false`. Record recall@12 + mean sections/bundle.
2. Treatment: `= true`, sweep `retain_ratio` in {0.4, 0.5, 0.6}. Record recall@12 + mean sections/bundle
   + coverage-confidence distribution.
3. ACCEPT the largest retain_ratio where recall@12 stays within ~0.01 of control AND mean
   sections/bundle drops for weak-coverage queries. REJECT if recall regresses beyond that — a
   sharper-but-lossy cut is not worth it.

| Config | recall@12 | mean sections/bundle | verdict |
|--------|-----------|----------------------|---------|
| knee off (control) |  |  | baseline |
| knee on, retain 0.5 |  |  |  |
| knee on, retain 0.4 |  |  |  |
```

- [ ] **Step 9: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java \
        wikantik-main/src/main/resources/ini/wikantik.properties \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/DefaultBundleAssemblyServiceTest.java \
        eval/bundle-corpus/baseline-notes.md
git commit -m "feat(bundle): apply knee-cutoff dynamic-N at the assembler top-N cut (off by default)"
```

---

## Acceptance criteria mapping

| Requirement | Covered by |
|---|---|
| Drops the tail without touching the reorder-only chain | Task 2 (dynamic-N at the assembler cut; the `SectionReranker` chain is untouched) |
| Thin query returns fewer, denser sections | Task 1 `thinQuery_cutsBelowTheRetainLine` + Task 2 `knee_cutsThinResultsBelowMaxSections` |
| Always keeps ≥1, ≤ maxSections | Task 1 `alwaysKeepsAtLeastTheTop`, `flatStrongQuery_keepsAllUpToMax` |
| No-op on the page-gated path (no per-section cosine) | Task 1 `noPerSectionCosine_topSimilarityNegative_returnsMaxSections` |
| Flag-off byte-identical | Task 1 `disabled_alwaysReturnsMaxSections` + Task 2 delegating constructors + full suite |
| No recall regression (precision lever) | Task 2 manual measurement gate (live index) |

**Honest note:** the knee can only lower recall@12 (it returns fewer sections), so its win is coverage-signal sharpening + fewer weak padding sections, at a bounded recall cost — the manual gate accepts it only if recall stays within ~0.01 of control. Off by default; the measurement is the operator gate before enabling.

## Self-Review

- **Spec coverage:** pure selector (Task 1), assembler application + config + gate (Task 2). The "drop-capable seam" design question is resolved by placing the cut in the assembler (not the reorder-only chain). Non-goals: no change to rerank stages; no new module.
- **Placeholder scan:** Task 2 Step 1's test is described against the existing `DefaultBundleAssemblyServiceTest` fixtures rather than fully written out — the implementer must read that test file and reuse its stub source/canonicalId helpers (they are test-file-specific; writing them blind here would risk drift). Every production code step is complete.
- **Type consistency:** `KneeCutoff.effectiveN( List< CandidateSection >, double, int )` used identically in Task 1 tests and Task 2's assembler; `KneeCutoff.of`/`disabled` consistent across wiring + assembler delegators; `kneeEnabled`/`kneeRetainRatio` consistent between Task 2 impl and any wiring test.
