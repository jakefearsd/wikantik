# Hybrid Real-Cosine Section Score (Unblock the Knee on the Default Path) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry the real per-section dense cosine through `HybridChunkSectionSource` (instead of the `1.0/(1+pos)` rank proxy), so `denseScore` is on the same cosine scale as `topSimilarity` — making the knee-cutoff meaningful on the DEFAULT hybrid bundle path (where it currently correctly no-ops) and the displayed section score honest. Pair it with an order-independent `KneeCutoff.effectiveN` (count-above-line, not leading-run) since hybrid candidates arrive in fused order, not dense-descending.

**Architecture:** `HybridChunkSectionSource.candidates` already computes the dense `List<ScoredChunk>` (chunkId → cosine). Thread a `chunkId → cosine` map into `groupToSections` so each section's `denseScore` is its best-fused chunk's dense cosine (0.0 when that chunk has no dense score — a BM25-only chunk), and mark the candidates `denseCosineScale=true`. `KneeCutoff.effectiveN` changes from "count the leading descending run above the retain line" to "count ALL candidates whose denseScore ≥ topSimilarity·retainRatio" — identical on the pre-sorted dense path, correct on the fused-order hybrid path.

**Tech Stack:** Java 25, JUnit 5, Log4j2, Maven. No new dependency, no schema change, no new class.

## Design decisions (grounded)

1. **Root cause of the knee no-op on the default path.** `HybridChunkSectionSource.groupToSections` (`:147`) sets `denseScore = 1.0/(1+pos)` — a rank proxy "for display" — while `topSimilarity` (`:78`) is the top dense cosine. The knee's `denseScore ≥ topSimilarity·retainRatio` compared mismatched scales, so the prior branch gated the knee to `denseCosineScale=true` sources only (pure dense). This plan makes the hybrid `denseScore` a real cosine so the gate opens.

2. **The dense cosine per section = the best-fused chunk's dense cosine.** `candidates()` has `dense` (`List<ScoredChunk>`, chunkId→cosine). Build `Map<String,Double> denseCosById`. In `groupToSections`, the section keeps the first (best-fused) chunk; its `denseScore` = `denseCosById.getOrDefault(bestChunkId, 0.0)`. A BM25-only best chunk (not in the dense top-K) → 0.0.

3. **Honest hybrid-knee semantics (document, don't hide).** With this change the knee on the hybrid path is a DENSE-relevance cutoff: a section whose best-fused chunk is strong in BM25 but weak/absent in dense gets `denseScore ≈ 0` and can be cut. That is a deliberate, defensible behavior for a dense-relevance elbow (and dense dominates the fusion: `dense_weight 1.5 > bm25_weight 0.5`), but it means the hybrid knee is not a *fused*-relevance elbow. A future refinement (a real fused score from `HybridFuser`) would make it fused-relevance-aware; that is out of scope. The measurement gate note records this.

4. **`effectiveN` must be order-independent.** Hybrid `groupToSections` returns sections in FUSED order (LinkedHashMap insertion), not dense-descending. The current `effectiveN` scans the leading run and stops at the first below-line score — correct only for pre-sorted (dense) input. Change it to count ALL candidates with `denseScore ≥ retainLine`. On the pre-sorted dense path this is identical (leading run == all-above-line); on the hybrid fused order it is correct. Clamp unchanged `[1, maxSections]`.

5. **Output `score` change is acceptable and better.** `BundleSection.score` carries `denseScore`; on the hybrid path it changes from a rank proxy (1.0, 0.5, …) to a real dense cosine (0.0 for BM25-only). A real cosine is more meaningful for display/debugging and is consistent with the coverage block's `topSimilarity`.

## Global Constraints

- **Java 25.** Changes confined to `com.wikantik.knowledge.bundle` (wikantik-main); no new package/module/dependency; no schema change.
- **Keep ASF headers** (existing files). Style: spaces in generics/parens, `final`, Log4j2.
- **Fail-closed / no-throw preserved:** `HybridChunkSectionSource` still degrades to whichever ranker is available and never throws; `KneeCutoff` stays pure/total.
- **Byte-identical where nothing changed:** the pure `DenseChunkSectionSource` path is unaffected (already cosine-scale); `KneeCutoff.effectiveN` returns the same value on pre-sorted input; the knee is still OFF by default. Existing tests stay green except those asserting the old hybrid rank-proxy score.
- **TDD:** failing test first. Run one class with `mvn test -pl wikantik-main -Dtest=ClassName`.
- Worked directly on `main` (sole-dev) — no branch; commit directly.

---

### Task 1: `HybridChunkSectionSource` carries the real dense cosine + `denseCosineScale=true`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/HybridChunkSectionSource.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/HybridChunkSectionSourceTest.java` and/or `HybridChunkSectionSourceCandidatesTest.java` (whichever tests `groupToSections` — read both)

**Interfaces:**
- `groupToSections` gains a `Map< String, Double > denseCosById` param: `static List< CandidateSection > groupToSections( List< String > fusedIds, Map< UUID, MentionableChunk > byId, Map< String, Double > denseCosById )`. Each section's `denseScore` = `denseCosById.getOrDefault( bestFusedChunkId, 0.0 )` (the string chunk id). `candidates()` builds `denseCosById` from the `dense` list (`sc.chunkId().toString() → sc.score()`) and returns `SectionCandidates.of( groupToSections(...), topSim, true )`.

- [ ] **Step 1: Update `groupToSections` tests** — read the existing test(s) that call `groupToSections(fusedIds, byId)`. Update them to the 3-arg form, passing a `denseCosById` map, and assert the section `denseScore` equals the mapped cosine of the section's best-fused chunk (and 0.0 for a chunk absent from the map). Add a case: two sections, chunk A cosine 0.8 (fused first), chunk B cosine 0.5 → sections carry denseScore 0.8 and 0.5 (not 1.0 and 0.5). Add a BM25-only case: a best-fused chunk absent from `denseCosById` → denseScore 0.0.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=HybridChunkSectionSourceTest,HybridChunkSectionSourceCandidatesTest`
Expected: FAIL — `groupToSections` still 2-arg / still emits `1/(1+pos)`.

- [ ] **Step 3: Implement** — change `groupToSections` signature + body:

```java
    static List< CandidateSection > groupToSections( final List< String > fusedIds,
                                                     final Map< UUID, MentionableChunk > byId,
                                                     final Map< String, Double > denseCosById ) {
        final Map< SectionKey, CandidateSection > best = new LinkedHashMap<>();
        for ( final String fid : fusedIds ) {
            final MentionableChunk c = byId.get( UUID.fromString( fid ) );
            if ( c == null ) continue;
            final SectionKey key = new SectionKey( c.pageName(), c.headingPath() );
            if ( best.containsKey( key ) ) continue;
            // section score = its best-fused chunk's dense cosine (0.0 if the chunk had no dense score,
            // e.g. BM25-only). Same cosine scale as topSimilarity, so the knee can apply.
            best.put( key, new CandidateSection( c.pageName(), c.headingPath(), c.text(),
                denseCosById.getOrDefault( fid, 0.0 ) ) );
        }
        return new ArrayList<>( best.values() );
    }
```

And in `candidates()`, build the map and pass `true` for `denseCosineScale`:

```java
        final Map< String, Double > denseCosById = new java.util.HashMap<>();
        for ( final ScoredChunk sc : dense ) denseCosById.put( sc.chunkId().toString(), sc.score() );
        // ... after building byId:
        return SectionCandidates.of( groupToSections( fusedIds, byId, denseCosById ), topSim, true );
```

Update the class Javadoc on `groupToSections` (drop "monotonically-decreasing proxy of fused rank … for display"; state it's the best-fused chunk's dense cosine).

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=HybridChunkSectionSourceTest,HybridChunkSectionSourceCandidatesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/HybridChunkSectionSource.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/HybridChunkSectionSourceTest.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/HybridChunkSectionSourceCandidatesTest.java
git commit -m "feat(bundle): hybrid section score is the real dense cosine (enables knee on default path)"
```

---

### Task 2: order-independent `KneeCutoff.effectiveN` + doc the hybrid knee semantics + full suite

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/KneeCutoff.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/KneeCutoffTest.java`
- Modify: `eval/bundle-corpus/baseline-notes.md` (knee gate: it now applies to the default path; note the dense-relevance semantics)

**Interfaces:** `effectiveN` unchanged signature; behavior changes from "leading run above line" to "count ALL candidates with `denseScore ≥ topSimilarity·retainRatio`", clamped `[1, maxSections]`.

- [ ] **Step 1: Add the failing test** — a non-sorted input where a below-line score precedes an above-line one, proving the leading-run bug is fixed:

```java
    @Test
    void countsAllAboveLine_notJustLeadingRun_forFusedOrderInput() {
        // fused (non-descending) order: 0.90 (above), 0.20 (below), 0.80 (above); top 0.90, ratio 0.5 -> line 0.45.
        // leading-run logic would stop at 0.20 -> 1; correct count-above-line -> 2.
        final var s = List.of( sec( 0.90 ), sec( 0.20 ), sec( 0.80 ) );
        assertEquals( 2, KneeCutoff.of( true, 0.5 ).effectiveN( s, 0.90, 12 ) );
    }
```

(Keep all existing KneeCutoff tests — they must still pass, since on their descending inputs count-above-line == leading-run.)

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=KneeCutoffTest`
Expected: FAIL — the new case returns 1 (leading run stops at 0.20).

- [ ] **Step 3: Implement** — change the loop in `effectiveN` from break-on-first-miss to count-all:

```java
        final double retainLine = topSimilarity * retainRatio;
        int kept = 0;
        for ( final CandidateSection c : denseSorted ) {
            if ( c.denseScore() >= retainLine ) kept++;
        }
        return Math.max( 1, Math.min( kept, maxSections ) );
```

Update the `KneeCutoff` Javadoc: "keep candidates whose denseScore ≥ topSimilarity·retainRatio (order-independent count), clamped [1, maxSections]".

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=KneeCutoffTest`
Expected: PASS (8 tests — the 7 existing + the new fused-order case).

- [ ] **Step 5: Update the knee measurement gate** in `eval/bundle-corpus/baseline-notes.md` — in the knee section, replace the "Scope: activates only on the pure-dense path" note with:

```markdown
- **Scope (updated):** the knee now applies on BOTH the pure-dense and the default hybrid path (the hybrid
  section score is the best-fused chunk's real dense cosine as of 2026-07-11). On the hybrid path the knee is
  a DENSE-relevance cutoff: a section strong in BM25 but weak/absent in dense (denseScore ~0) can be cut.
  Dense dominates the fusion (weight 1.5 vs 0.5), so this is usually benign, but a fused-relevance knee
  (a real HybridFuser score) is the future refinement. Measure recall@12 on the DEFAULT (bm25.enabled=true)
  config now, not only the pure-dense config.
```

- [ ] **Step 6: Full module unit suite**

Run: `mvn test -pl wikantik-main`
Expected: BUILD SUCCESS; quote `Tests run: N, Failures: 0, Errors: 0`. Knee still OFF by default → no live-path shape change; the only behavior change is the hybrid section score value (asserted in Task 1's tests) and `effectiveN`'s count semantics (asserted here).

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/KneeCutoff.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/KneeCutoffTest.java \
        eval/bundle-corpus/baseline-notes.md
git commit -m "feat(bundle): order-independent knee count + enable knee on hybrid; doc dense-relevance semantics"
```

---

## Acceptance criteria mapping

| Requirement | Covered by |
|---|---|
| Hybrid section score is a real dense cosine (same scale as topSimilarity) | Task 1 tests (0.8/0.5 not 1.0/0.5; 0.0 for BM25-only) |
| Knee applies on the default hybrid path | Task 1 (`denseCosineScale=true`) + Task 2 (order-independent count) |
| Knee correct on fused (non-descending) order | Task 2 `countsAllAboveLine_notJustLeadingRun` |
| Pure-dense path unchanged; knee still off-by-default | Task 2 existing KneeCutoff tests + full suite |
| Honest hybrid-knee semantics documented | Task 2 baseline-notes update |

**Honest note:** the hybrid knee is a DENSE-relevance cutoff (can cut BM25-strong/dense-weak sections); a fused-relevance knee needs a real `HybridFuser` score (future). The knee remains off by default; the recall@12 + sections/bundle measurement on the default config is the operator gate before enabling.

## Self-Review

- **Spec coverage:** hybrid real cosine (Task 1), order-independent knee + docs + suite (Task 2). The prior branch's follow-up ("carry real cosines through HybridChunkSectionSource so the knee is meaningful on the default path") is delivered; the fused-order pitfall is handled (Task 2).
- **Placeholder scan:** none — code complete; Task 1 Step 1 references the existing hybrid test files (read them for the exact stub/fixture shape; the `groupToSections` signature change is the only structural edit).
- **Type consistency:** `groupToSections( List<String>, Map<UUID,MentionableChunk>, Map<String,Double> )` used in `candidates()` + tests; `SectionCandidates.of(sections, topSim, true)` (3-arg from the prior branch); `KneeCutoff.effectiveN` signature unchanged (behavior only).
