# Lexical Injection Code/Identifier Retrieval — Implementation Plan (Phase 0 + 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the context bundle serve code-symbol/config-lookup queries by injecting dense-cold, high-confidence BM25(code) sections into the shipped dense-heavy hybrid bundle — without regressing natural-language retrieval.

**Architecture:** A new `LexicalInjectionSource` (a `SectionCandidateSource` decorator) wraps the shipped hybrid base unchanged. Per query it computes a code-aware BM25 ranking + each candidate's dense rank, and splices in up to N sections that BM25(code) ranks highly **and** dense ranked coldly (self-gating: near-silent on natural-language). All gates are config knobs, tuned measure-first on an expanded identifier corpus + the natural corpus; default-off until tuned, then shipped default-on.

**Tech Stack:** Java 21, Lucene 10.4 (`LuceneBm25ChunkIndex` + `analyzerFor("code")`), the bundle pipeline (`com.wikantik.knowledge.bundle.*`), Python eval harnesses (`bin/eval/*.py`), PostgreSQL (`kg_content_chunks`, `page_canonical_ids`).

**Spec:** `docs/superpowers/specs/2026-06-19-lexical-injection-code-retrieval-design.md`

---

## File Structure

**Phase 0 (eval data):**
- Modify: `eval/bundle-corpus/queries-identifiers.csv` — expand 13 → ~35–40 verified-gold queries.
- Create: `bin/eval/verify-golds.py` — checks every gold (canonical_id + heading-path) resolves to a real section in `chunk-section-map.tsv`.

**Phase 1 (bundle injector), all in `com.wikantik.knowledge.bundle` unless noted:**
- Create: `InjectionConfig.java` — the knob record + `fromProperties`.
- Create: `SymbolDetector.java` — literal code-symbol regex detector.
- Create: `LexicalInjectionSource.java` — the decorator: `candidates()` (I/O) + a pure static `merge()` (gate logic) + `debugRankings()`.
- Create tests: `InjectionConfigTest.java`, `SymbolDetectorTest.java`, `LexicalInjectionSourceTest.java`.
- Modify: `wikantik-main/.../search/subsystem/SearchWiringHelper.java` — build the code BM25 index + wrap the bundle source in the injector when `inject.enabled`.
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` — the `wikantik.bundle.inject.*` knobs (default off).
- Modify: `wikantik-rest/.../rest/BundleResource.java` — extend `?debug=rankings` to also return the BM25(code) ranking.
- Create: `bin/eval/sweep-injection.py` — offline knob sweep over both corpora.

**Interfaces locked here (used across tasks):**
- `SectionCandidateSource`: `List<CandidateSection> candidates(String query)` (functional, package-private consumers).
- `CandidateSection(String slug, List<String> headingPath, String text, double denseScore)` — package-private record.
- `ScoredChunk(UUID chunkId, String pageName, double score)`.
- `LuceneBm25ChunkIndex`: `topKChunks(String, int) → List<ScoredChunk>`, `analyzerFor(String) → Analyzer`, `fromDataSource(DataSource, Analyzer)`, `size()`.
- `ChunkVectorIndex.topKChunks(float[], int) → List<ScoredChunk>`; `QueryEmbedder.embed(String) → Optional<float[]>`.
- `ContentChunkRepository.findByIds(List<UUID>) → List<MentionableChunk>`; `MentionableChunk(UUID id, String pageName, int chunkIndex, List<String> headingPath, String text)`.

---

## Phase 0 — Expand the identifier eval corpus

### Task 1: Expand identifier corpus + gold-verification script

**Files:**
- Modify: `eval/bundle-corpus/queries-identifiers.csv`
- Create: `bin/eval/verify-golds.py`
- Uses: `eval/bm25-chunk-spike/chunk-section-map.tsv` (chunkId → canonical_id → heading_path)

- [ ] **Step 1: Regenerate the chunk-section map (source of truth for golds)**

```bash
set -a; . ./.env; set +a; export PGPASSWORD="$POSTGRES_PASSWORD"
psql -h localhost -p ${POSTGRES_PORT:-5432} -U ${POSTGRES_USER:-wikantik} -d ${POSTGRES_DB:-wikantik} -tA -F $'\t' \
  -c "select c.id::text, p.canonical_id, coalesce(array_to_json(c.heading_path)::text,'[]')
      from kg_content_chunks c join page_canonical_ids p on p.current_slug = c.page_name" \
  > eval/bm25-chunk-spike/chunk-section-map.tsv
wc -l eval/bm25-chunk-spike/chunk-section-map.tsv   # expect ~17k rows
```

- [ ] **Step 2: Mine distinctive identifiers + their sections (run, eyeball, pick targets)**

```bash
set -a; . ./.env; set +a; export PGPASSWORD="$POSTGRES_PASSWORD"
PSQL="psql -h localhost -p ${POSTGRES_PORT:-5432} -U ${POSTGRES_USER:-wikantik} -d ${POSTGRES_DB:-wikantik} -tA"
# camelCase + snake_case identifiers that appear in exactly one section (unambiguous gold):
$PSQL -c "
with toks as (
  select distinct c.page_name, array_to_json(c.heading_path)::text hp,
         (regexp_matches(c.text, '[a-z]+_[a-z]+_[a-z_]+|[A-Z][a-z]+[A-Z][A-Za-z]+', 'g'))[1] tok
  from kg_content_chunks c)
select tok, min(page_name||' :: '||hp) sec from toks group by tok having count(*)=1 and length(tok)>10 order by random() limit 60;"
# config keys (dotted, distinctive):
$PSQL -c "
with toks as (
  select distinct c.page_name, array_to_json(c.heading_path)::text hp,
         lower((regexp_matches(c.text,'wikantik\.[a-zA-Z0-9_.]+','g'))[1]) tok
  from kg_content_chunks c)
select tok, min(page_name||' :: '||hp) sec from toks group by tok having count(*)<=2 and length(tok)>18 order by tok limit 30;"
# canonical_ids for chosen pages:
$PSQL -c "select current_slug, canonical_id from page_canonical_ids where current_slug in ('<page1>','<page2>');"
```

- [ ] **Step 3: Write the verification script**

Create `bin/eval/verify-golds.py`:

```python
#!/usr/bin/env python3
"""Verify every gold (canonical_id + heading-path sublist) in a corpus CSV resolves to a
real section in chunk-section-map.tsv. Fails loudly on any unresolvable gold.
Usage: python3 bin/eval/verify-golds.py eval/bundle-corpus/queries-identifiers.csv"""
import csv, json, re, sys
CORPUS = sys.argv[1]
MAP = "eval/bm25-chunk-spike/chunk-section-map.tsv"
def norm(s): return re.sub(r"\s+", " ", s).strip().lower()
sections = set()  # (canonical_id, tuple(heading_path))
for ln in open(MAP, encoding="utf-8"):
    p = ln.rstrip("\n").split("\t")
    if len(p) < 3: continue
    try: hp = tuple(norm(x) for x in json.loads(p[2]))
    except Exception: hp = ()
    sections.add((p[1], hp))
def sub(g, full):  # g is a contiguous sublist of full
    n = len(g)
    return n > 0 and any(full[i:i+n] == g for i in range(0, len(full)-n+1))
bad = []
seen = set()
for raw in open(CORPUS, encoding="utf-8"):
    ln = raw.strip()
    if not ln or ln.startswith("#") or ln.startswith("query_id,"): continue
    qid, q, cat, cid, hp = next(csv.reader([ln]))[:5]
    seen.add(qid)
    g = tuple(norm(x) for x in hp.split(">") if x.strip())
    if not any(c == cid and sub(g, full) for (c, full) in sections):
        bad.append((qid, cid, hp))
print(f"{len(seen)} queries; {len(bad)} unresolvable golds")
for b in bad: print("  UNRESOLVABLE:", b)
sys.exit(1 if bad else 0)
```

- [ ] **Step 4: Verify the CURRENT 13-query corpus resolves (baseline for the script)**

Run: `python3 bin/eval/verify-golds.py eval/bundle-corpus/queries-identifiers.csv`
Expected: `13 queries; 0 unresolvable golds` (exit 0). If any are unresolvable, fix those rows first.

- [ ] **Step 5: Add ~25 more queries to reach ~35–40**

Append rows to `eval/bundle-corpus/queries-identifiers.csv` (same header/columns: `query_id,query,category,gold_canonical_id,gold_heading_path,notes`). Rubric — split roughly evenly across:
- **Literal-symbol form** (query contains the literal token): `"what does HybridChunkSectionSource do"`, `"the kg_content_chunks table"`, `"wikantik.search.graph.boost setting"`.
- **Concept-phrased form** (natural language, no literal token, content has the symbol): `"the actor system in akka"`, `"add conditional edges in langgraph"`.

For each: pick a mined identifier (Step 2), set `gold_canonical_id` to its page's canonical_id, `gold_heading_path` to the most distinctive trailing 1–2 segments of its section's heading path (`>`-separated), `category=IDENTIFIER`, and put the source identifier in `notes`. Keep `>`-separators and exact heading text (the verifier normalises case/whitespace).

- [ ] **Step 6: Verify the expanded corpus resolves**

Run: `python3 bin/eval/verify-golds.py eval/bundle-corpus/queries-identifiers.csv`
Expected: `~38 queries; 0 unresolvable golds` (exit 0). Fix any unresolvable rows.

- [ ] **Step 7: Commit**

```bash
git add eval/bundle-corpus/queries-identifiers.csv bin/eval/verify-golds.py
git commit -m "eval(bundle): expand identifier corpus to ~38 + gold-verification script"
```

---

## Phase 1 — The bundle injector

### Task 2: `InjectionConfig` (knob record + `fromProperties`)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/InjectionConfig.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/InjectionConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class InjectionConfigTest {
    @Test
    void defaultsWhenUnset() {
        final InjectionConfig c = InjectionConfig.fromProperties(new Properties());
        assertFalse(c.enabled());
        assertEquals(20, c.bm25RankMax());
        assertEquals(50, c.denseColdMin());
        assertEquals(0.3, c.scoreFrac(), 1e-9);
        assertEquals(3, c.maxInject());
        assertEquals(3, c.position());
        assertTrue(c.symbolBoost());
        assertEquals(50, c.jBoost());
        assertEquals(0.1, c.alphaBoost(), 1e-9);
        assertEquals(300, c.denseScanK());
    }
    @Test
    void overridesParsed() {
        final Properties p = new Properties();
        p.setProperty("wikantik.bundle.inject.enabled", "true");
        p.setProperty("wikantik.bundle.inject.bm25_rank_max", "10");
        p.setProperty("wikantik.bundle.inject.score_frac", "0.5");
        p.setProperty("wikantik.bundle.inject.symbol_boost", "false");
        final InjectionConfig c = InjectionConfig.fromProperties(p);
        assertTrue(c.enabled());
        assertEquals(10, c.bm25RankMax());
        assertEquals(0.5, c.scoreFrac(), 1e-9);
        assertFalse(c.symbolBoost());
    }
    @Test
    void malformedFallsBackToDefault() {
        final Properties p = new Properties();
        p.setProperty("wikantik.bundle.inject.bm25_rank_max", "notanumber");
        assertEquals(20, InjectionConfig.fromProperties(p).bm25RankMax());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl wikantik-main test -Dtest=InjectionConfigTest`
Expected: FAIL — `InjectionConfig` does not exist (compile error).

- [ ] **Step 3: Implement `InjectionConfig`**

```java
package com.wikantik.knowledge.bundle;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Properties;

/**
 * Tuning knobs for {@link LexicalInjectionSource}. A section is injected when BM25(code)
 * ranks it within {@code bm25RankMax} with score ≥ {@code scoreFrac} × the query's top BM25
 * score, AND dense ranked it worse than {@code denseColdMin} (the self-gating signal). Up to
 * {@code maxInject} sections are spliced in at {@code position}. When the query contains a
 * literal code symbol and {@code symbolBoost} is on, {@code jBoost}/{@code alphaBoost} relax
 * the rank/score bars. {@code denseScanK} = how deep to scan dense for the cold signal.
 */
public record InjectionConfig(
        boolean enabled, int bm25RankMax, int denseColdMin, double scoreFrac,
        int maxInject, int position, boolean symbolBoost, int jBoost, double alphaBoost,
        int denseScanK) {

    private static final Logger LOG = LogManager.getLogger(InjectionConfig.class);

    public static InjectionConfig fromProperties(final Properties p) {
        return new InjectionConfig(
            bool(p, "wikantik.bundle.inject.enabled", false),
            intp(p, "wikantik.bundle.inject.bm25_rank_max", 20),
            intp(p, "wikantik.bundle.inject.dense_cold_min", 50),
            dbl(p, "wikantik.bundle.inject.score_frac", 0.3),
            intp(p, "wikantik.bundle.inject.max_inject", 3),
            intp(p, "wikantik.bundle.inject.position", 3),
            bool(p, "wikantik.bundle.inject.symbol_boost", true),
            intp(p, "wikantik.bundle.inject.j_boost", 50),
            dbl(p, "wikantik.bundle.inject.alpha_boost", 0.1),
            intp(p, "wikantik.bundle.inject.dense_scan_k", 300));
    }

    private static boolean bool(final Properties p, final String k, final boolean def) {
        final String v = p == null ? null : p.getProperty(k);
        return v == null || v.isBlank() ? def : Boolean.parseBoolean(v.trim());
    }
    private static int intp(final Properties p, final String k, final int def) {
        final String v = p == null ? null : p.getProperty(k);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); }
        catch (final NumberFormatException e) { LOG.warn("Invalid {} '{}'; using {}", k, v, def); return def; }
    }
    private static double dbl(final Properties p, final String k, final double def) {
        final String v = p == null ? null : p.getProperty(k);
        if (v == null || v.isBlank()) return def;
        try { return Double.parseDouble(v.trim()); }
        catch (final NumberFormatException e) { LOG.warn("Invalid {} '{}'; using {}", k, v, def); return def; }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -pl wikantik-main test -Dtest=InjectionConfigTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/InjectionConfig.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/InjectionConfigTest.java
git commit -m "feat(bundle): InjectionConfig knobs for lexical injection"
```

### Task 3: `SymbolDetector` (literal code-symbol regex)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SymbolDetector.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/SymbolDetectorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SymbolDetectorTest {
    @Test
    void detectsCamelCase() { assertTrue(SymbolDetector.hasCodeSymbol("what does HybridChunkSectionSource do")); }
    @Test
    void detectsSnakeCase() { assertTrue(SymbolDetector.hasCodeSymbol("the kg_content_chunks table")); }
    @Test
    void detectsDottedKey() { assertTrue(SymbolDetector.hasCodeSymbol("wikantik.search.graph.boost setting")); }
    @Test
    void naturalLanguageIsFalse() {
        assertFalse(SymbolDetector.hasCodeSymbol("how do I deploy the wiki locally"));
        assertFalse(SymbolDetector.hasCodeSymbol("the actor system in akka"));
        assertFalse(SymbolDetector.hasCodeSymbol(""));
        assertFalse(SymbolDetector.hasCodeSymbol(null));
    }
    @Test
    void singleCapitalizedWordIsNotASymbol() {  // avoid firing on proper nouns / sentence case
        assertFalse(SymbolDetector.hasCodeSymbol("Ollama installation"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl wikantik-main test -Dtest=SymbolDetectorTest`
Expected: FAIL — `SymbolDetector` missing.

- [ ] **Step 3: Implement `SymbolDetector`**

```java
package com.wikantik.knowledge.bundle;

import java.util.regex.Pattern;

/**
 * Cheap, high-precision detector for queries that literally name a code symbol — used as a
 * confidence *booster* for injection (NOT a router). Fires on a camelCase token with an
 * internal case change ({@code HybridChunkSectionSource}, {@code getUserId}), a snake_case
 * token ({@code kg_content_chunks}), or a dotted key with ≥2 dots ({@code a.b.c}). Deliberately
 * does NOT fire on a single Capitalized word (sentence case / proper nouns).
 */
public final class SymbolDetector {
    private static final Pattern CAMEL = Pattern.compile("[a-z][A-Za-z]*[A-Z][A-Za-z]*");
    private static final Pattern SNAKE = Pattern.compile("[A-Za-z0-9]+_[A-Za-z0-9_]+");
    private static final Pattern DOTTED = Pattern.compile("[A-Za-z0-9]+\\.[A-Za-z0-9]+\\.[A-Za-z0-9.]+");

    private SymbolDetector() {}

    public static boolean hasCodeSymbol(final String query) {
        if (query == null || query.isBlank()) return false;
        return CAMEL.matcher(query).find() || SNAKE.matcher(query).find() || DOTTED.matcher(query).find();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -pl wikantik-main test -Dtest=SymbolDetectorTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/SymbolDetector.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/SymbolDetectorTest.java
git commit -m "feat(bundle): SymbolDetector for literal code-symbol queries"
```

### Task 4: The pure `merge()` gate logic

This is the crux — a pure static function so all gates are unit-tested with fixtures, no I/O.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/LexicalInjectionSource.java` (start with the inner records + `merge`; `candidates` added in Task 5)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/LexicalInjectionSourceTest.java`

- [ ] **Step 1: Write the failing test (gates + placement + dedup + booster)**

```java
package com.wikantik.knowledge.bundle;

import com.wikantik.knowledge.bundle.LexicalInjectionSource.Candidate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LexicalInjectionSourceTest {
    private static CandidateSection sec(String slug, String head, String text, double score) {
        return new CandidateSection(slug, List.of(head), text, score);
    }
    // Candidate(section, bm25Score, bm25Rank, denseRank)
    private static Candidate cand(String slug, String head, double bm25, int bm25Rank, int denseRank) {
        return new Candidate(sec(slug, head, "t-" + slug, bm25), bm25, bm25Rank, denseRank);
    }

    @Test
    void injectsDenseColdHighConfidenceSection() {
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0), sec("P1","H1","b1",0.9),
            sec("P2","H2","b2",0.8), sec("P3","H3","b3",0.7));
        // candidate: bm25 rank 0, score == top (10.0), dense rank 99 (cold, > C=50) → inject
        List<Candidate> cands = List.of(cand("GOLD","HG", 10.0, 0, 99));
        InjectionConfig cfg = InjectionConfig.fromProperties(props(true));  // enabled, defaults
        List<CandidateSection> out = LexicalInjectionSource.merge(base, cands, 10.0, false, cfg);
        // inserted at position 3 (default P)
        assertEquals("GOLD", out.get(3).slug());
        assertEquals(5, out.size());
    }

    @Test
    void skipsDenseWarmSection() {  // dense rank 10 <= C=50 → not cold → no inject
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0));
        List<Candidate> cands = List.of(cand("WARM","HW", 10.0, 0, 10));
        List<CandidateSection> out = LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(true)));
        assertEquals(1, out.size());
    }

    @Test
    void skipsLowRelativeScore() {  // score 2.0 < 0.3 * top(10.0) = 3.0 → skip
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0));
        List<Candidate> cands = List.of(cand("WEAK","HW", 2.0, 0, 99));
        List<CandidateSection> out = LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(true)));
        assertEquals(1, out.size());
    }

    @Test
    void skipsBeyondRankMax() {  // bm25Rank 25 >= J=20 → skip
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0));
        List<Candidate> cands = List.of(cand("FAR","HF", 10.0, 25, 99));
        List<CandidateSection> out = LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(true)));
        assertEquals(1, out.size());
    }

    @Test
    void dedupsAgainstBase() {  // candidate already in base (same slug+heading) → skip
        List<CandidateSection> base = List.of(sec("DUP","HD","b",1.0));
        List<Candidate> cands = List.of(cand("DUP","HD", 10.0, 0, 99));
        List<CandidateSection> out = LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(true)));
        assertEquals(1, out.size());
    }

    @Test
    void capsAtMaxInject() {  // 5 eligible, maxInject=3
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0));
        List<Candidate> cands = List.of(
            cand("G1","H1",10,0,99), cand("G2","H2",9,1,99), cand("G3","H3",8,2,99),
            cand("G4","H4",7,3,99), cand("G5","H5",6,4,99));
        List<CandidateSection> out = LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(true)));
        assertEquals(4, out.size());  // 1 base + 3 injected
    }

    @Test
    void disabledReturnsBaseUnchanged() {
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0));
        List<Candidate> cands = List.of(cand("GOLD","HG",10,0,99));
        List<CandidateSection> out = LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(false)));
        assertSame(base, out);
    }

    @Test
    void symbolBoostRelaxesGates() {  // rank 30 (>J=20 but <jBoost=50) injects only with boost
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0), sec("P1","H1","b1",0.9),
            sec("P2","H2","b2",0.8), sec("P3","H3","b3",0.7));
        List<Candidate> cands = List.of(cand("GOLD","HG", 10.0, 30, 99));
        InjectionConfig cfg = InjectionConfig.fromProperties(props(true));
        assertEquals(4, LexicalInjectionSource.merge(base, cands, 10.0, false, cfg).size());   // no boost → skip
        assertEquals(5, LexicalInjectionSource.merge(base, cands, 10.0, true, cfg).size());     // boost → inject
    }

    private static java.util.Properties props(boolean enabled) {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("wikantik.bundle.inject.enabled", String.valueOf(enabled));
        return p;
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl wikantik-main test -Dtest=LexicalInjectionSourceTest`
Expected: FAIL — `LexicalInjectionSource` / `Candidate` missing.

- [ ] **Step 3: Implement the records + pure `merge`**

```java
package com.wikantik.knowledge.bundle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// NOTE: candidates()/debugRankings()/constructor added in Task 5; this step adds the pure core.
public final class LexicalInjectionSource implements SectionCandidateSource {

    /** A BM25(code) section candidate with its lexical score/rank and best dense rank. */
    public record Candidate(CandidateSection section, double bm25Score, int bm25Rank, int denseRank) {}

    private record Key(String slug, List<String> headingPath) {}
    private static Key key(final CandidateSection s) { return new Key(s.slug(), s.headingPath()); }

    /**
     * Pure gate + splice. Returns {@code base} unchanged (same reference) when disabled. Otherwise
     * selects up to {@code maxInject} candidates that are (a) not already in base, (b) dense-cold
     * (denseRank &gt; denseColdMin), (c) within the rank bar, and (d) above the relative-score bar,
     * and inserts them at {@code position}. {@code symbolBoost} relaxes the rank/score bars.
     */
    static List<CandidateSection> merge(final List<CandidateSection> base, final List<Candidate> candidates,
                                        final double topBm25Score, final boolean symbolBoost,
                                        final InjectionConfig cfg) {
        if (!cfg.enabled()) return base;
        final int rankMax = symbolBoost ? cfg.jBoost() : cfg.bm25RankMax();
        final double alpha = symbolBoost ? cfg.alphaBoost() : cfg.scoreFrac();
        final double scoreBar = alpha * topBm25Score;

        final Set<Key> baseKeys = new LinkedHashSet<>();
        for (final CandidateSection s : base) baseKeys.add(key(s));

        final List<CandidateSection> picked = new ArrayList<>();
        for (final Candidate c : candidates) {
            if (picked.size() >= cfg.maxInject()) break;
            if (c.bm25Rank() >= rankMax) continue;
            if (c.bm25Score() < scoreBar) continue;
            if (c.denseRank() <= cfg.denseColdMin()) continue;   // dense-warm → dense already has it
            if (baseKeys.contains(key(c.section()))) continue;   // dedup
            baseKeys.add(key(c.section()));
            picked.add(c.section());
        }
        if (picked.isEmpty()) return base;

        final List<CandidateSection> out = new ArrayList<>(base);
        final int pos = Math.max(0, Math.min(cfg.position(), out.size()));
        out.addAll(pos, picked);
        return out;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -pl wikantik-main test -Dtest=LexicalInjectionSourceTest`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/LexicalInjectionSource.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/LexicalInjectionSourceTest.java
git commit -m "feat(bundle): pure lexical-injection merge/gate logic"
```

### Task 5: `LexicalInjectionSource.candidates()` + `debugRankings()` (the I/O wrapper)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/bundle/LexicalInjectionSource.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/bundle/LexicalInjectionSourceTest.java` (add cases)

- [ ] **Step 1: Write the failing test (candidates end-to-end with mocked deps + fail-open)**

```java
// Add to LexicalInjectionSourceTest. Imports:
//   import com.wikantik.knowledge.chunking.ContentChunkRepository;
//   import com.wikantik.knowledge.chunking.ContentChunkRepository.MentionableChunk;
//   import com.wikantik.search.hybrid.*;
//   import java.util.*; import static org.mockito.Mockito.*; import static org.mockito.ArgumentMatchers.*;

    @Test @SuppressWarnings("unchecked")
    void candidatesInjectsDenseColdCodeSection() {
        final UUID goldId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        final QueryEmbedder embedder = mock(QueryEmbedder.class);
        when(embedder.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f}));
        final ChunkVectorIndex dense = mock(ChunkVectorIndex.class);   // dense returns base pages only (gold absent → cold)
        when(dense.topKChunks(any(), anyInt())).thenReturn(List.of(
            new ScoredChunk(UUID.randomUUID(), "P0", 0.9)));
        final LuceneBm25ChunkIndex bm25code = mock(LuceneBm25ChunkIndex.class);  // bm25(code) ranks the gold chunk #0
        when(bm25code.topKChunks(anyString(), anyInt())).thenReturn(List.of(
            new ScoredChunk(goldId, "GoldPage", 12.0)));
        final ContentChunkRepository repo = mock(ContentChunkRepository.class);
        when(repo.findByIds(anyList())).thenReturn(List.of(
            new MentionableChunk(goldId, "GoldPage", 0, List.of("Gold Section"), "gold text")));
        final SectionCandidateSource base = q -> new ArrayList<>(List.of(
            new CandidateSection("P0", List.of("H0"), "b0", 1.0)));
        final Properties p = new Properties();
        p.setProperty("wikantik.bundle.inject.enabled", "true");
        p.setProperty("wikantik.bundle.inject.position", "0");
        final LexicalInjectionSource src = new LexicalInjectionSource(
            base, embedder, dense, bm25code, repo, InjectionConfig.fromProperties(p));
        final List<CandidateSection> out = src.candidates("the gold thing");
        assertEquals("GoldPage", out.get(0).slug());     // injected at position 0
        assertEquals("gold text", out.get(0).text());
    }

    @Test @SuppressWarnings("unchecked")
    void candidatesFailsOpenWhenBm25Throws() {
        final QueryEmbedder embedder = mock(QueryEmbedder.class);
        when(embedder.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f}));
        final ChunkVectorIndex dense = mock(ChunkVectorIndex.class);
        when(dense.topKChunks(any(), anyInt())).thenReturn(List.of());
        final LuceneBm25ChunkIndex bm25code = mock(LuceneBm25ChunkIndex.class);
        when(bm25code.topKChunks(anyString(), anyInt())).thenThrow(new RuntimeException("boom"));
        final ContentChunkRepository repo = mock(ContentChunkRepository.class);
        final List<CandidateSection> baseList = List.of(new CandidateSection("P0", List.of("H0"), "b0", 1.0));
        final SectionCandidateSource base = q -> baseList;
        final Properties p = new Properties();
        p.setProperty("wikantik.bundle.inject.enabled", "true");
        final LexicalInjectionSource src = new LexicalInjectionSource(
            base, embedder, dense, bm25code, repo, InjectionConfig.fromProperties(p));
        assertEquals(baseList, src.candidates("q"));   // base unchanged on failure
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl wikantik-main test -Dtest=LexicalInjectionSourceTest`
Expected: FAIL — constructor + `candidates` not implemented.

- [ ] **Step 3: Implement the constructor, `candidates`, helpers, and `debugRankings`**

Add to `LexicalInjectionSource` (imports: `com.wikantik.knowledge.chunking.ContentChunkRepository` + `.MentionableChunk`, `com.wikantik.search.hybrid.{ChunkVectorIndex,LuceneBm25ChunkIndex,QueryEmbedder,ScoredChunk}`, `org.apache.logging.log4j.*`, `java.util.*`):

```java
    private static final Logger LOG = LogManager.getLogger(LexicalInjectionSource.class);

    private final SectionCandidateSource base;
    private final QueryEmbedder embedder;
    private final ChunkVectorIndex denseIndex;
    private final LuceneBm25ChunkIndex bm25CodeIndex;
    private final ContentChunkRepository chunkRepo;
    private final InjectionConfig config;

    public LexicalInjectionSource(final SectionCandidateSource base, final QueryEmbedder embedder,
                                  final ChunkVectorIndex denseIndex, final LuceneBm25ChunkIndex bm25CodeIndex,
                                  final ContentChunkRepository chunkRepo, final InjectionConfig config) {
        this.base = base; this.embedder = embedder; this.denseIndex = denseIndex;
        this.bm25CodeIndex = bm25CodeIndex; this.chunkRepo = chunkRepo; this.config = config;
    }

    @Override
    public List<CandidateSection> candidates(final String query) {
        final List<CandidateSection> baseSections = base.candidates(query);
        if (!config.enabled()) return baseSections;
        try {
            final List<ScoredChunk> bm25 = bm25CodeIndex.topKChunks(query, config.bm25RankMax() > config.jBoost()
                ? config.bm25RankMax() : config.jBoost());
            if (bm25.isEmpty()) return baseSections;
            final List<ScoredChunk> dense = embedder.embed(query)
                .map(qv -> denseIndex.topKChunks(qv, config.denseScanK())).orElseGet(List::of);

            // dense rank per section key (best/lowest rank); sections absent from the dense scan are cold.
            final List<ScoredChunk> denseSorted = sortedDesc(dense);
            final Map<UUID, MentionableChunk> hydr = hydrate(bm25, denseSorted);
            final Map<String, Integer> denseRankBySection = new HashMap<>();
            for (int r = 0; r < denseSorted.size(); r++) {
                final MentionableChunk c = hydr.get(denseSorted.get(r).chunkId());
                if (c == null) continue;
                denseRankBySection.merge(sectionKey(c.pageName(), c.headingPath()), r, Math::min);
            }

            // build BM25(code) section candidates (first/best chunk per section, in bm25 rank order).
            final List<ScoredChunk> bm25Sorted = sortedDesc(bm25);
            final double topScore = bm25Sorted.get(0).score();
            final Set<String> seen = new LinkedHashSet<>();
            final List<Candidate> cands = new ArrayList<>();
            for (int r = 0; r < bm25Sorted.size(); r++) {
                final ScoredChunk sc = bm25Sorted.get(r);
                final MentionableChunk c = hydr.get(sc.chunkId());
                if (c == null) continue;
                final String key = sectionKey(c.pageName(), c.headingPath());
                if (!seen.add(key)) continue;   // first (best) chunk per section
                final int denseRank = denseRankBySection.getOrDefault(key, config.denseScanK());
                cands.add(new Candidate(
                    new CandidateSection(c.pageName(), c.headingPath(), c.text(), sc.score()), sc.score(), r, denseRank));
            }
            final boolean boost = config.symbolBoost() && SymbolDetector.hasCodeSymbol(query);
            return merge(baseSections, cands, topScore, boost, config);
        } catch (final RuntimeException e) {
            LOG.warn("Lexical injection failed for '{}'; returning base bundle: {}", query, e.getMessage());
            return baseSections;
        }
    }

    private Map<UUID, MentionableChunk> hydrate(final List<ScoredChunk> a, final List<ScoredChunk> b) {
        final LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (final ScoredChunk s : a) ids.add(s.chunkId());
        for (final ScoredChunk s : b) ids.add(s.chunkId());
        final Map<UUID, MentionableChunk> byId = new HashMap<>();
        for (final MentionableChunk c : chunkRepo.findByIds(new ArrayList<>(ids))) byId.put(c.id(), c);
        return byId;
    }

    private static List<ScoredChunk> sortedDesc(final List<ScoredChunk> in) {
        final List<ScoredChunk> out = new ArrayList<>(in);
        out.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return out;
    }

    private static String sectionKey(final String slug, final List<String> hp) {
        return slug + " " + String.join("", hp == null ? List.of() : hp);
    }

    /** Debug rankings for the offline sweep: dense + BM25(standard, from base if exposed) + BM25(code). */
    public Map<String, List<HybridChunkSectionSource.DebugRank>> debugRankings(final String query, final int k) {
        final Map<String, List<HybridChunkSectionSource.DebugRank>> out = new LinkedHashMap<>();
        if (base instanceof HybridChunkSectionSource h) out.putAll(h.debugRankings(query, k));  // dense + bm25_standard
        final List<HybridChunkSectionSource.DebugRank> code = new ArrayList<>();
        for (final ScoredChunk sc : sortedDesc(bm25CodeIndex.topKChunks(query, k)))
            code.add(new HybridChunkSectionSource.DebugRank(sc.chunkId().toString(), sc.score()));
        out.put("bm25_code", code);
        return out;
    }
```

Note: `HybridChunkSectionSource.debugRankings` returns keys `dense` + `bm25` — rename the base's `bm25` to `bm25_standard` here for clarity. To do that, after `out.putAll(...)`, run: `if (out.containsKey("bm25")) out.put("bm25_standard", out.remove("bm25"));`. Add that line.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -pl wikantik-main test -Dtest=LexicalInjectionSourceTest`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/bundle/LexicalInjectionSource.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/bundle/LexicalInjectionSourceTest.java
git commit -m "feat(bundle): LexicalInjectionSource candidates() + debugRankings (fail-open)"
```

### Task 6: Wire the injector in `SearchWiringHelper` + ini knobs

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java` (the bundle-source block, ~line 258–272 where `engine.setBundleSectionSource(bundleSource)` is called)
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (after the `wikantik.bundle.bm25.*` block)

- [ ] **Step 1: Add the ini knobs (default off)**

Append after the `wikantik.bundle.bm25.analyzer = standard` line:

```properties
# Lexical injection (code/identifier retrieval). Wraps the dense-heavy hybrid base and injects
# dense-cold, high-confidence BM25(code) sections so code-symbol/config queries are served without
# regressing natural-language. Default OFF until tuned. Builds a 2nd RAM BM25 chunk index (code
# analyzer). See docs/superpowers/specs/2026-06-19-lexical-injection-code-retrieval-design.md.
wikantik.bundle.inject.enabled = false
wikantik.bundle.inject.bm25_rank_max = 20
wikantik.bundle.inject.dense_cold_min = 50
wikantik.bundle.inject.score_frac = 0.3
wikantik.bundle.inject.max_inject = 3
wikantik.bundle.inject.position = 3
wikantik.bundle.inject.symbol_boost = true
wikantik.bundle.inject.j_boost = 50
wikantik.bundle.inject.alpha_boost = 0.1
wikantik.bundle.inject.dense_scan_k = 300
```

- [ ] **Step 2: Wrap the bundle source in `SearchWiringHelper`**

Immediately BEFORE the existing `engine.setBundleSectionSource( bundleSource );` line, insert:

```java
        // Lexical injection: wrap the (hybrid or dense) bundle source so code-symbol/config queries
        // get dense-cold BM25(code) sections injected. Default off; builds a 2nd code-analyzer BM25
        // index. Fail-open — any build failure leaves the base source untouched.
        final com.wikantik.knowledge.bundle.InjectionConfig injectCfg =
            com.wikantik.knowledge.bundle.InjectionConfig.fromProperties( props );
        if ( injectCfg.enabled() ) {
            try {
                final com.wikantik.search.hybrid.LuceneBm25ChunkIndex codeIndex =
                    com.wikantik.search.hybrid.LuceneBm25ChunkIndex.fromDataSource( ds,
                        com.wikantik.search.hybrid.LuceneBm25ChunkIndex.analyzerFor( "code" ) );
                bundleSource = new com.wikantik.knowledge.bundle.LexicalInjectionSource(
                    bundleSource, embedder, vectorIndex, codeIndex, chunkRepo, injectCfg );
                LOG.info( "Bundle lexical injection ON (code chunks indexed={}, J={}, C={}, alpha={}, N={}, P={})",
                    codeIndex.size(), injectCfg.bm25RankMax(), injectCfg.denseColdMin(), injectCfg.scoreFrac(),
                    injectCfg.maxInject(), injectCfg.position() );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Lexical injection index build failed; base bundle source unchanged: {}", e.getMessage(), e );
            }
        }
        engine.setBundleSectionSource( bundleSource );
```

(Confirm `embedder`, `vectorIndex`, `chunkRepo`, `ds`, `bundleSource`, `props` are all in scope here — they are, from the existing hybrid-source block above this line.)

- [ ] **Step 3: Compile**

Run: `mvn -pl wikantik-main compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java \
        wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "feat(bundle): wire LexicalInjectionSource behind wikantik.bundle.inject.* (default off)"
```

### Task 7: Extend `?debug=rankings` to expose the BM25(code) ranking

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/BundleResource.java` (`handleDebugRankings`)

- [ ] **Step 1: Update the debug handler to support the injector source**

In `handleDebugRankings`, change the `instanceof HybridChunkSectionSource` branch so it also handles `LexicalInjectionSource` (which has its own `debugRankings` returning `dense` + `bm25_standard` + `bm25_code`):

```java
        final java.util.Map<String, java.util.List<com.wikantik.knowledge.bundle.HybridChunkSectionSource.DebugRank>> rankings;
        if ( src instanceof com.wikantik.knowledge.bundle.LexicalInjectionSource inj ) {
            rankings = inj.debugRankings( q, k );
        } else if ( src instanceof com.wikantik.knowledge.bundle.HybridChunkSectionSource hybrid ) {
            rankings = hybrid.debugRankings( q, k );
        } else {
            resp.setStatus( 409 );
            resp.getWriter().write( "{\"error\":\"chunk-hybrid source not active (set wikantik.bundle.bm25.enabled=true)\"}" );
            return;
        }
        resp.setStatus( 200 );
        resp.getWriter().write( BUNDLE_GSON.toJson( rankings ) );
```

(Replace the existing single `instanceof HybridChunkSectionSource` block + its `hybrid.debugRankings` write with the above. Keep the `k` parsing above it.)

- [ ] **Step 2: Compile both modules**

Run: `mvn -pl wikantik-main install -DskipTests -q && mvn -pl wikantik-rest compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/BundleResource.java
git commit -m "feat(rest): debug=rankings exposes bm25_code when the injector is active"
```

### Task 8: Offline injection sweep harness

**Files:**
- Create: `bin/eval/sweep-injection.py`
- Uses: `?debug=rankings` (dense + bm25_standard + bm25_code), `eval/bm25-chunk-spike/chunk-section-map.tsv`, both corpora.

- [ ] **Step 1: Write the sweep harness**

Create `bin/eval/sweep-injection.py`. It reconstructs the base bundle from `dense + bm25_standard` (the shipped dense-heavy RRF: rrfK=20, bm25=0.5, dense=1.5, truncate=20 — same as `sweep-bm25-fusion.py`), then applies the injection gates from `bm25_code` + dense ranks, and sweeps `J,C,α,N,P` over BOTH corpora. Key pieces (mirror `sweep-bm25-fusion.py` for the base, add injection):

```python
#!/usr/bin/env python3
"""Offline sweep of the lexical-injection knobs over the natural + identifier corpora.
Fetches dense + bm25_standard + bm25_code rankings once per query via /api/bundle?debug=rankings,
reconstructs the shipped dense-heavy base, then sweeps J/C/alpha/N/P. No restart per combo.
Run with the server in inject-ENABLED mode (so the debug endpoint exposes bm25_code).
Usage: python3 bin/eval/sweep-injection.py [base_url]"""
import csv, json, re, sys, urllib.parse, urllib.request
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
MAP = "eval/bm25-chunk-spike/chunk-section-map.tsv"
CORPORA = {"natural": "eval/bundle-corpus/queries.csv", "identifier": "eval/bundle-corpus/queries-identifiers.csv"}
NS = [5, 12]; FETCH_K = 300
# base fusion (shipped): dense_w=1.5, bm25_w=0.5, rrfK=20, truncate=20
BASE_DW, BASE_BW, BASE_K, BASE_TR = 1.5, 0.5, 20, 20

def norm(s): return re.sub(r"\s+", " ", s).strip().lower()
def load_map():
    m = {}
    for ln in open(MAP, encoding="utf-8"):
        p = ln.rstrip("\n").split("\t")
        if len(p) < 3: continue
        try: hp = tuple(norm(x) for x in json.loads(p[2]))
        except Exception: hp = ()
        m[p[0]] = (p[1], hp)        # chunkId -> (canonical, heading_path)
    return m
def load(path):
    qs, order = {}, []
    for raw in open(path, encoding="utf-8"):
        ln = raw.strip()
        if not ln or ln.startswith("#") or ln.startswith("query_id,"): continue
        qid, q, cat, cid, hp = next(csv.reader([ln]))[:5]
        qs.setdefault(qid, {"q": q, "golds": []})["golds"].append((cid, tuple(norm(x) for x in hp.split(">") if x.strip())))
        if qid not in order: order.append(qid)
    return [(o, qs[o]) for o in order]
def fetch(q):
    u = BASE + "/api/bundle?" + urllib.parse.urlencode({"q": q, "debug": "rankings", "k": FETCH_K})
    with urllib.request.urlopen(u, timeout=90) as r: d = json.load(r)
    g = lambda key: [(x["chunkId"], x["score"]) for x in d.get(key, [])]
    return g("dense"), g("bm25_standard"), g("bm25_code")

CAMEL = re.compile(r"[a-z][A-Za-z]*[A-Z][A-Za-z]*"); SNAKE = re.compile(r"[A-Za-z0-9]+_[A-Za-z0-9_]+")
DOTTED = re.compile(r"[A-Za-z0-9]+\.[A-Za-z0-9]+\.[A-Za-z0-9.]+")
def has_symbol(q): return bool(CAMEL.search(q) or SNAKE.search(q) or DOTTED.search(q))

def rrf(dense, bm25, dw, bw, k, tr):
    s = {}
    for r, (c, _) in enumerate(dense[:tr]): s[c] = s.get(c, 0) + dw / (k + r + 1)
    for r, (c, _) in enumerate(bm25[:tr]): s[c] = s.get(c, 0) + bw / (k + r + 1)
    return s
def to_sections(scored_chunks, cmap):   # chunkId->score dict OR [(chunkId,score)] -> ordered section keys (first per section)
    items = scored_chunks.items() if isinstance(scored_chunks, dict) else scored_chunks
    ordered = sorted(items, key=lambda kv: -kv[1])
    seen, out = set(), []
    for cid, sc in ordered:
        key = cmap.get(cid)
        if key is None or key in seen: continue
        seen.add(key); out.append((key, sc, cid))
    return out
def base_sections(dense, bm25std, cmap):
    return [k for k, _, _ in to_sections(rrf(dense, bm25std, BASE_DW, BASE_BW, BASE_K, BASE_TR), cmap)]
def dense_rank_by_section(dense, cmap, scan_k):
    rank = {}
    for r, (cid, _) in enumerate(dense[:scan_k]):
        key = cmap.get(cid)
        if key and key not in rank: rank[key] = r
    return rank

def inject(base_keys, bm25code, cmap, drank, has_sym, cfg):
    J, C, A, N, P, JB, AB, SCAN = cfg
    rankmax, alpha = (JB, AB) if has_sym else (J, A)
    code_secs = to_sections(bm25code, cmap)        # [(key, score, cid)] best chunk per section, bm25 order
    if not code_secs: return base_keys
    top = code_secs[0][1]
    picked, basek = [], set(base_keys)
    for r, (key, score, cid) in enumerate(code_secs):
        if len(picked) >= N: break
        if r >= rankmax: continue
        if score < alpha * top: continue
        if drank.get(key, SCAN) <= C: continue
        if key in basek: continue
        basek.add(key); picked.append(key)
    if not picked: return base_keys
    out = list(base_keys); pos = max(0, min(P, len(out)))
    out[pos:pos] = picked
    return out

def sub(g, full): n = len(g); return n > 0 and any(full[i:i+n] == g for i in range(0, len(full)-n+1))
def recall(secs, golds, n):
    top = secs[:n]
    return [1.0 if any(k[0] == gc and sub(gh, k[1]) for k in top) else 0.0 for gc, gh in golds]

def main():
    cmap = load_map()
    cache = {}  # corpus -> [(qid, qd, base_keys, bm25code, drank_fn_inputs)]
    for name, path in CORPORA.items():
        rows = []
        for qid, qd in load(path):
            dense, bm25std, bm25code = fetch(qd["q"])
            rows.append((qid, qd, base_sections(dense, bm25std, cmap), bm25code, dense, has_symbol(qd["q"])))
        cache[name] = rows
    def evalu(name, cfg):
        ov = {n: [] for n in NS}
        for qid, qd, basek, bm25code, dense, sym in cache[name]:
            drank = dense_rank_by_section(dense, cmap, cfg[7])
            secs = inject(basek, bm25code, cmap, drank, sym, cfg)
            for n in NS:
                ov[n].extend(recall(secs, qd["golds"], n))
        return {n: round(sum(ov[n]) / len(ov[n]), 4) for n in NS}
    # baseline (inject off == N=0)
    OFF = (20, 50, 0.3, 0, 3, 50, 0.1, 300)
    print("baseline (no inject):  natural", evalu("natural", OFF), " identifier", evalu("identifier", OFF))
    best = []
    for J in (10, 20, 50):
      for C in (20, 50, 100):
        for A in (0.2, 0.3, 0.5):
          for N in (1, 2, 3):
            for P in (1, 3, 6):
              cfg = (J, C, A, N, P, max(J, 50), min(A, 0.1), 300)
              nat, ide = evalu("natural", cfg), evalu("identifier", cfg)
              best.append((cfg, nat, ide))
    # rank by identifier@12 subject to natural@12 >= 0.715
    ok = [b for b in best if b[1][12] >= 0.715]
    ok.sort(key=lambda b: -b[2][12])
    print(f"\n{len(ok)}/{len(best)} combos hold natural@12>=0.715; top 12 by identifier@12:")
    print(f"{'J':>3}{'C':>4}{'a':>5}{'N':>3}{'P':>3}  {'nat@5':>7}{'nat@12':>7}{'ide@5':>7}{'ide@12':>7}")
    for cfg, nat, ide in ok[:12]:
        print(f"{cfg[0]:>3}{cfg[1]:>4}{cfg[2]:>5}{cfg[3]:>3}{cfg[4]:>3}  {nat[5]:>7}{nat[12]:>7}{ide[5]:>7}{ide[12]:>7}")

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Commit (harness only; the run is Task 9)**

```bash
git add bin/eval/sweep-injection.py
git commit -m "eval(bundle): offline lexical-injection knob sweep harness"
```

### Task 9: Run the sweep, pick the operating point, live-confirm

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (set the tuned defaults)
- Runtime config: `tomcat/tomcat-11/lib/wikantik-custom.properties` (local only, not committed)

- [ ] **Step 1: Build + deploy with injection ENABLED (so the debug endpoint exposes bm25_code)**

```bash
mvn -pl wikantik-main install -DskipTests -q && mvn -pl wikantik-rest install -DskipTests -q && mvn -pl wikantik-war install -DskipTests -q
# in tomcat/tomcat-11/lib/wikantik-custom.properties set: wikantik.bundle.inject.enabled = true
bin/redeploy.sh
# wait for /api/bundle?q=test -> 200, and a 200 from /api/bundle?q=test&debug=rankings with a "bm25_code" key
```

- [ ] **Step 2: Regenerate the chunk-section map (corpus may have changed) and run the sweep**

```bash
# (Task 1 Step 1 regenerates chunk-section-map.tsv if not already current.)
python3 bin/eval/sweep-injection.py http://localhost:8080
```
Expected: a `baseline (no inject)` line showing `natural ~0.706/0.721`-ish and `identifier ~0.538`, then a ranked table of combos that hold `natural@12 >= 0.715`. Pick the combo with the best `identifier@12` that also keeps `natural@5` from dropping materially (compare to the baseline natural@5).

- [ ] **Step 3: Set the chosen knobs as the ini defaults**

Edit `wikantik-main/src/main/resources/ini/wikantik.properties` — replace the `wikantik.bundle.inject.{bm25_rank_max,dense_cold_min,score_frac,max_inject,position,...}` values with the winning combo from Step 2. Leave `wikantik.bundle.inject.enabled = false` for now (flipped in Task 10).

- [ ] **Step 4: Live-confirm the chosen knobs on BOTH corpora**

```bash
# set the winning knobs in tomcat/tomcat-11/lib/wikantik-custom.properties (+ inject.enabled=true), rebuild war, redeploy
mvn -pl wikantik-main install -DskipTests -q && mvn -pl wikantik-war install -DskipTests -q && bin/redeploy.sh
python3 bin/eval/measure-corpus.py http://localhost:8080 eval/bundle-corpus/queries-identifiers.csv   # identifier: up vs 0.538
python3 bin/eval/measure-corpus.py http://localhost:8080 eval/bundle-corpus/queries.csv                # natural: @12 >= ~0.715
```
Expected: live numbers match the sweep's chosen combo (identifier@12 materially up, natural@12 ≥ ~0.715, natural@5 not materially down). If live diverges from offline, the sweep's base-fusion or injection replication is off — reconcile before shipping.

- [ ] **Step 5: Commit the tuned defaults**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "eval(bundle): set tuned lexical-injection defaults from the sweep"
```

### Task 10: Ship default-on + full IT-reactor gate

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (flip `enabled` to true)

- [ ] **Step 1: Flip the default**

In `wikantik-main/src/main/resources/ini/wikantik.properties` set `wikantik.bundle.inject.enabled = true` and update its comment to "default ON since 2026-06-19; degrades to base bundle if the code index build fails."

- [ ] **Step 2: Full unit build (all modules) + the two new test classes**

Run: `mvn -pl wikantik-main test -Dtest='InjectionConfigTest,SymbolDetectorTest,LexicalInjectionSourceTest'`
Expected: all PASS.

- [ ] **Step 3: Full IT reactor (the ship gate — a prod default change)**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS, Reactor Summary all SUCCESS. (This wall-clocks tens of minutes and includes wikantik-main's unit suite + the Cargo IT suites. If a single invocation times out, re-run; judge by the BUILD line + Reactor Summary, not the shell exit code. A transient maven-clean-plugin failure on the first run → re-run once.) Pay attention to any failure in bundle / `BundleResource` / search areas.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "feat(bundle): ship lexical injection default-on (code/identifier retrieval)"
```

- [ ] **Step 5: Update findings + memory pointer**

Append a short "shipped" note to `eval/bm25-chunk-spike/analyzer-and-efsearch-findings.md` (the regime-conflict writeup) pointing at the spec + the final knobs, and commit it.

---

## Done criteria

- Identifier corpus expanded to ~35–40 verified-gold queries.
- `LexicalInjectionSource` injects dense-cold, high-confidence BM25(code) sections; every gate unit-tested; fails open.
- Tuned on both corpora; identifier recall@12 materially above 0.538 with natural-language @12 ≥ ~0.715 and no material @5 dip; live-confirmed.
- Shipped default-on, gated by a green full IT reactor.
- `assemble_bundle` MCP gets injection for free (it consumes the same `BundleAssemblyService` → same wrapped source — no MCP-specific change needed; the IT reactor's `AssembleBundleToolTest` covers it).
- Out of scope (separate plan): `/api/search` page-level analog (Phase 2).
