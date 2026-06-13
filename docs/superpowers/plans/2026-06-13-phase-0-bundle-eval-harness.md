# Phase 0 — Bundle-Quality Evaluation Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a frozen, versioned, section-level **bundle-quality** evaluation harness that gates CI, and use it to establish *one trustworthy baseline* for retrieval — so every later RAG-as-a-Service / Knowledge-Base change can be judged by measured lift.

**Architecture:** Extend the existing page-level retrieval-quality harness (`com.wikantik.knowledge.eval`) with a parallel **bundle** layer: section-level gold passages, three bundle metrics (context recall, context precision, citation faithfulness), a CSV-backed frozen corpus, and a reproducible gate test. Deterministic and LLM-free in the gate. Freeze one baseline model set (Gemma4:12b extraction, qwen3 embeddings); turn the dormant KG rerank honestly *off pending the Phase-4 fair trial*; root-cause the known all-retriever misses.

**Tech Stack:** Java 21, JUnit 5, Maven, the existing `wikantik-main`/`wikantik-api` eval packages, H2 (wiring tests) + Testcontainers/pgvector (real-corpus gate), the frozen page corpus already version-controlled under `docs/wikantik-pages/`.

**Design source:** `docs/superpowers/specs/2026-06-13-rag-as-a-service-and-knowledge-base-design.md` (Phase 0) and ADRs 0001–0007. Vocabulary: `CONTEXT.md`.

---

## Context the implementer needs

- **Existing harness is page-level.** `RetrievalMetricsCalculator.score(List<String> predicted, Set<String> expected)` scores nDCG/recall/MRR over **canonical_ids** (binary relevance). We are *not* changing it. We add a **section-level** sibling because the unit of a [context bundle] is an evidence *section*, not a page.
- **Retrieval entry point:** `ContextRetrievalService.retrieve(ContextQuery)` → `RetrievalResult(query, List<RetrievedPage>, totalMatched)`. Each `RetrievedPage(name, url, score, summary, cluster, tags, contributingChunks, relatedPages, author, lastModified)` carries `List<RetrievedChunk>`. Each `RetrievedChunk(headingPath, text, chunkScore, matchedTerms)` — `headingPath` is the section breadcrumb (e.g. `["Retrieval Experiment Harness","7. Model selection"]`). `name` is the page *slug*; canonical_id is resolved separately.
- **Slug → canonical_id** resolution already exists as a `Function<String,Optional<String>>` in the runner constructors (see `RetrievalQualitySmokeTest` line 160). Reuse that shape.
- **Every Java file** starts with the standard ASF license header (17 lines — copy it verbatim from any existing file in the same module, e.g. `RetrievalMetricsCalculator.java`). It is omitted from the code blocks below for brevity; **include it**.
- **Frozen corpus = `docs/wikantik-pages/`** (already in git). The evaluation corpus references pages by `canonical_id` (frontmatter) + section heading-path, so golds survive re-chunking/re-extraction (ADR-0004, ADR-0005).
- **CI has no embedder.** The live embedder (Ollama) is unavailable in CI, so the reproducible gate runs against **checked-in, pre-computed embeddings** for the frozen corpus (Task 7) — the embeddings are part of the frozen snapshot; re-snapshot when you level up (the user accepted this in the design session).
- **Build commands:** single-module compile `mvn -q -pl wikantik-main -am test-compile`; single test `mvn -q -pl wikantik-main test -Dtest=ClassName`; run signature-affecting checks with `test-compile` (not just `compile`). Follow CLAUDE.md token rules: compile-check the module, fix all, one full build at the end.

---

## File structure

**New (data, the frozen corpus):**
- `eval/bundle-corpus/queries.csv` — section-level corpus: `query_id,query,category,gold_canonical_id,gold_heading_path,notes` (one row per gold section; rows sharing `query_id` belong to one question).
- `eval/bundle-corpus/thresholds.properties` — per-category gate floors (versioned, ratcheted).
- `eval/bundle-corpus/embeddings/` — checked-in chunk-embedding fixture for the frozen corpus (Task 7).
- `eval/bundle-corpus/baseline-notes.md` — the recorded baseline + miss-case root-causes (Tasks 9, 12).

**New (api types):**
- `wikantik-api/src/main/java/com/wikantik/api/eval/BundleCategory.java`
- `wikantik-api/src/main/java/com/wikantik/api/eval/GoldSection.java`
- `wikantik-api/src/main/java/com/wikantik/api/eval/BundleSection.java`
- `wikantik-api/src/main/java/com/wikantik/api/eval/BundleEvalQuestion.java`

**New (harness logic, `wikantik-main`):**
- `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleMetricsCalculator.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleCorpusLoader.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalRunner.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalReport.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/eval/ContextServiceBundleRetriever.java`

**New (tests):**
- `wikantik-api/src/test/java/com/wikantik/api/eval/BundleRecordsTest.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleMetricsCalculatorTest.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleCorpusLoaderTest.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalRunnerTest.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalGateTest.java`

**Modified (config cleanup):**
- `wikantik-main/src/main/resources/ini/wikantik.properties:1305` (graph.boost default → 0.0, honest comment)
- deployed `wikantik-custom.properties` + prod `.env`/template (remove "TEMP DIAGNOSTIC" wording)

---

## PART A — The bundle-quality harness (deterministic core, TDD)

### Task 1: Bundle value types

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/eval/BundleCategory.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/eval/GoldSection.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/eval/BundleSection.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/eval/BundleEvalQuestion.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/eval/BundleRecordsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.api.eval;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundleRecordsTest {

    @Test
    void goldSection_normalizes_and_rejects_blank_id() {
        final GoldSection g = new GoldSection( "01ABC", List.of( "Overview", "Setup" ) );
        assertEquals( "01ABC", g.canonicalId() );
        assertEquals( List.of( "Overview", "Setup" ), g.headingPath() );
        assertThrows( IllegalArgumentException.class,
            () -> new GoldSection( "  ", List.of() ) );
    }

    @Test
    void bundleSection_defensively_copies_and_requires_text() {
        final BundleSection s = new BundleSection( "01ABC", List.of( "Overview" ), "body text" );
        assertEquals( "body text", s.text() );
        assertThrows( IllegalArgumentException.class,
            () -> new BundleSection( "01ABC", List.of( "Overview" ), null ) );
    }

    @Test
    void question_requires_at_least_one_gold() {
        final BundleEvalQuestion q = new BundleEvalQuestion(
            "q1", "how do I deploy locally", BundleCategory.SIMILARITY,
            List.of( new GoldSection( "01DEP", List.of( "Deploy" ) ) ) );
        assertEquals( BundleCategory.SIMILARITY, q.category() );
        assertEquals( 1, q.goldSections().size() );
        assertThrows( IllegalArgumentException.class,
            () -> new BundleEvalQuestion( "q2", "x", BundleCategory.RELATIONAL, List.of() ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-api test -Dtest=BundleRecordsTest`
Expected: FAIL — `BundleCategory`/`GoldSection`/`BundleSection`/`BundleEvalQuestion` do not exist (compile error).

- [ ] **Step 3: Write minimal implementation** (each in its own file, with the ASF header)

`BundleCategory.java`:
```java
package com.wikantik.api.eval;

/** The three deliberately-mixed question kinds in the evaluation corpus (Phase 0). */
public enum BundleCategory {
    /** Plain semantic-similarity question — dense retrieval's home turf. */
    SIMILARITY,
    /** Relational / multi-hop — the fair trial the Knowledge Graph is owed (ADR-0002). */
    RELATIONAL,
    /** Answer straddles a chunk/section boundary — the parent-child trigger evidence. */
    BOUNDARY
}
```

`GoldSection.java`:
```java
package com.wikantik.api.eval;

import java.util.List;

/**
 * The section a question is expected to be answered from: a page canonical_id +
 * its section heading-path. Section-level (not chunk-level) so it stays valid
 * across re-chunking and re-extraction.
 */
public record GoldSection( String canonicalId, List< String > headingPath ) {
    public GoldSection {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId must not be blank" );
        }
        headingPath = headingPath == null ? List.of() : List.copyOf( headingPath );
    }
}
```

`BundleSection.java`:
```java
package com.wikantik.api.eval;

import java.util.List;

/**
 * One evidence section as it appears in an assembled context bundle: the source
 * page canonical_id, the section heading-path, and the section text. The unit
 * the bundle metrics score against.
 */
public record BundleSection( String canonicalId, List< String > headingPath, String text ) {
    public BundleSection {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId must not be blank" );
        }
        if ( text == null ) {
            throw new IllegalArgumentException( "text must not be null" );
        }
        headingPath = headingPath == null ? List.of() : List.copyOf( headingPath );
    }
}
```

`BundleEvalQuestion.java`:
```java
package com.wikantik.api.eval;

import java.util.List;

/** One evaluation-corpus question: id, text, category, and its gold sections. */
public record BundleEvalQuestion(
    String queryId,
    String query,
    BundleCategory category,
    List< GoldSection > goldSections
) {
    public BundleEvalQuestion {
        if ( queryId == null || queryId.isBlank() ) {
            throw new IllegalArgumentException( "queryId must not be blank" );
        }
        if ( query == null || query.isBlank() ) {
            throw new IllegalArgumentException( "query must not be blank" );
        }
        if ( category == null ) {
            throw new IllegalArgumentException( "category must not be null" );
        }
        if ( goldSections == null || goldSections.isEmpty() ) {
            throw new IllegalArgumentException( "goldSections must not be empty" );
        }
        goldSections = List.copyOf( goldSections );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-api test -Dtest=BundleRecordsTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/eval/BundleCategory.java \
        wikantik-api/src/main/java/com/wikantik/api/eval/GoldSection.java \
        wikantik-api/src/main/java/com/wikantik/api/eval/BundleSection.java \
        wikantik-api/src/main/java/com/wikantik/api/eval/BundleEvalQuestion.java \
        wikantik-api/src/test/java/com/wikantik/api/eval/BundleRecordsTest.java
git commit -m "feat(eval): bundle-quality value types (section-level golds)"
```

---

### Task 2: BundleMetricsCalculator (the crown jewel — pure, LLM-free)

Three metrics, all deterministic. **Context recall** = fraction of gold sections covered by the bundle. **Context precision@K** = fraction of the top-K bundle slots that are gold (signal-to-noise proxy; no LLM judge in Phase 0). **Citation faithfulness** = SHA-256 of the resolved span equals the pinned hash (defined now; exercised in Phase 1 — there are no citations yet). A gold section is *covered* when some bundle section has the **same canonical_id** and a heading-path that **equals or extends** (starts-with) the gold heading-path.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleMetricsCalculator.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleMetricsCalculatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleSection;
import com.wikantik.api.eval.GoldSection;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundleMetricsCalculatorTest {

    private static BundleSection sec( String id, List<String> path, String text ) {
        return new BundleSection( id, path, text );
    }
    private static GoldSection gold( String id, List<String> path ) {
        return new GoldSection( id, path );
    }

    @Test
    void recall_counts_covered_gold_via_prefix_match() {
        final List<GoldSection> golds = List.of(
            gold( "01A", List.of( "Setup" ) ),
            gold( "01B", List.of( "Usage", "CLI" ) ) );
        final List<BundleSection> bundle = List.of(
            // covers 01A: same id, heading extends "Setup"
            sec( "01A", List.of( "Setup", "Prereqs" ), "..." ),
            // does NOT cover 01B: right id, wrong section
            sec( "01B", List.of( "Overview" ), "..." ) );
        assertEquals( 0.5, BundleMetricsCalculator.contextRecall( golds, bundle ), 1e-9 );
    }

    @Test
    void recall_is_zero_when_canonical_id_differs() {
        final List<GoldSection> golds = List.of( gold( "01A", List.of( "Setup" ) ) );
        final List<BundleSection> bundle = List.of( sec( "01Z", List.of( "Setup" ), "..." ) );
        assertEquals( 0.0, BundleMetricsCalculator.contextRecall( golds, bundle ), 1e-9 );
    }

    @Test
    void precisionAtK_is_gold_fraction_of_top_k_slots() {
        final List<GoldSection> golds = List.of( gold( "01A", List.of( "Setup" ) ) );
        final List<BundleSection> bundle = List.of(
            sec( "01A", List.of( "Setup" ), "gold" ),     // slot 1: gold
            sec( "01X", List.of( "Noise" ), "filler" ),   // slot 2: not
            sec( "01Y", List.of( "Noise" ), "filler" ) ); // slot 3: not
        // top-2: 1 gold of 2 slots = 0.5
        assertEquals( 0.5, BundleMetricsCalculator.contextPrecisionAtK( golds, bundle, 2 ), 1e-9 );
    }

    @Test
    void citationFaithfulness_passes_only_on_exact_hash_match() {
        final String body = "OntologyPageSync checks canonical_id liveness.";
        final String hash = BundleMetricsCalculator.sha256( body );
        assertTrue( BundleMetricsCalculator.citationFaithful( hash, body ) );
        assertFalse( BundleMetricsCalculator.citationFaithful( hash, body + " edited" ) );
    }

    @Test
    void empty_bundle_scores_zero_not_nan() {
        final List<GoldSection> golds = List.of( gold( "01A", List.of( "Setup" ) ) );
        assertEquals( 0.0, BundleMetricsCalculator.contextRecall( golds, List.of() ), 1e-9 );
        assertEquals( 0.0, BundleMetricsCalculator.contextPrecisionAtK( golds, List.of(), 5 ), 1e-9 );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=BundleMetricsCalculatorTest`
Expected: FAIL — `BundleMetricsCalculator` not defined.

- [ ] **Step 3: Write minimal implementation**

```java
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleSection;
import com.wikantik.api.eval.GoldSection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Pure, deterministic, LLM-free bundle-quality metrics for Phase 0:
 * context recall, context precision@K, and citation faithfulness. A gold
 * section is "covered" when a bundle section shares its canonical_id and a
 * heading-path that equals or extends (starts-with) the gold heading-path.
 */
public final class BundleMetricsCalculator {

    private BundleMetricsCalculator() {}

    /** Fraction of gold sections covered by the bundle. 0.0 for an empty bundle. */
    public static double contextRecall( final List< GoldSection > golds,
                                        final List< BundleSection > bundle ) {
        if ( golds == null || golds.isEmpty() ) return 0.0;
        int covered = 0;
        for ( final GoldSection g : golds ) {
            if ( isCovered( g, bundle ) ) covered++;
        }
        return (double) covered / golds.size();
    }

    /** Fraction of the top-K bundle slots that cover some gold section. */
    public static double contextPrecisionAtK( final List< GoldSection > golds,
                                              final List< BundleSection > bundle,
                                              final int k ) {
        if ( k <= 0 || bundle == null || bundle.isEmpty() ) return 0.0;
        final int cap = Math.min( k, bundle.size() );
        int goldSlots = 0;
        for ( int i = 0; i < cap; i++ ) {
            if ( coversAnyGold( bundle.get( i ), golds ) ) goldSlots++;
        }
        return (double) goldSlots / k;
    }

    /** A citation is faithful iff the pinned hash equals SHA-256 of the resolved span. */
    public static boolean citationFaithful( final String pinnedHash, final String resolvedText ) {
        if ( pinnedHash == null || resolvedText == null ) return false;
        return pinnedHash.equals( sha256( resolvedText ) );
    }

    /** Hex SHA-256 of UTF-8 text — the span content-hash used by citation handles (ADR-0005). */
    public static String sha256( final String text ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" )
                .digest( text.getBytes( StandardCharsets.UTF_8 ) );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( String.format( "%02x", b ) );
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }

    // ---- internals ----

    private static boolean isCovered( final GoldSection g, final List< BundleSection > bundle ) {
        if ( bundle == null ) return false;
        for ( final BundleSection s : bundle ) {
            if ( matches( g, s ) ) return true;
        }
        return false;
    }

    private static boolean coversAnyGold( final BundleSection s, final List< GoldSection > golds ) {
        if ( golds == null ) return false;
        for ( final GoldSection g : golds ) {
            if ( matches( g, s ) ) return true;
        }
        return false;
    }

    /** Same page, and the bundle section's heading-path equals or extends the gold's. */
    private static boolean matches( final GoldSection g, final BundleSection s ) {
        if ( !g.canonicalId().equals( s.canonicalId() ) ) return false;
        final List< String > gp = g.headingPath();
        final List< String > sp = s.headingPath();
        if ( sp.size() < gp.size() ) return false;
        for ( int i = 0; i < gp.size(); i++ ) {
            if ( !gp.get( i ).equalsIgnoreCase( sp.get( i ) ) ) return false;
        }
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=BundleMetricsCalculatorTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleMetricsCalculator.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleMetricsCalculatorTest.java
git commit -m "feat(eval): deterministic bundle metrics — recall, precision@K, citation faithfulness"
```

---

### Task 3: BundleCorpusLoader (CSV → questions)

Loads `eval/bundle-corpus/queries.csv`. Columns: `query_id,query,category,gold_canonical_id,gold_heading_path,notes`. Rows sharing `query_id` are grouped into one `BundleEvalQuestion`; `gold_heading_path` is `>`-separated (e.g. `Usage>CLI`). Lines starting with `#` and the header are skipped.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleCorpusLoader.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleCorpusLoaderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import com.wikantik.api.eval.BundleEvalQuestion;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundleCorpusLoaderTest {

    @Test
    void groups_rows_by_query_id_and_parses_heading_path() {
        final String csv = String.join( "\n",
            "# comment line",
            "query_id,query,category,gold_canonical_id,gold_heading_path,notes",
            "q1,how do I deploy,SIMILARITY,01DEP,Deploy>Local,seed",
            "q1,how do I deploy,SIMILARITY,01DEP,Deploy>Docker,seed",
            "q2,what uses the SHACL gate,RELATIONAL,01ONT,Validation,relational" );

        final List<BundleEvalQuestion> qs = BundleCorpusLoader.parse( new StringReader( csv ) );

        assertEquals( 2, qs.size() );
        final BundleEvalQuestion q1 = qs.stream().filter( q -> q.queryId().equals("q1") ).findFirst().orElseThrow();
        assertEquals( BundleCategory.SIMILARITY, q1.category() );
        assertEquals( 2, q1.goldSections().size() );
        assertEquals( List.of( "Deploy", "Local" ), q1.goldSections().get( 0 ).headingPath() );
        final BundleEvalQuestion q2 = qs.stream().filter( q -> q.queryId().equals("q2") ).findFirst().orElseThrow();
        assertEquals( BundleCategory.RELATIONAL, q2.category() );
    }

    @Test
    void rejects_unknown_category() {
        final String csv = String.join( "\n",
            "query_id,query,category,gold_canonical_id,gold_heading_path,notes",
            "q1,x,NONSENSE,01A,Setup,n" );
        assertThrows( IllegalArgumentException.class,
            () -> BundleCorpusLoader.parse( new StringReader( csv ) ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=BundleCorpusLoaderTest`
Expected: FAIL — `BundleCorpusLoader` not defined.

- [ ] **Step 3: Write minimal implementation**

```java
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import com.wikantik.api.eval.BundleEvalQuestion;
import com.wikantik.api.eval.GoldSection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the frozen evaluation corpus CSV into {@link BundleEvalQuestion}s.
 * Rows sharing {@code query_id} form one question; {@code gold_heading_path}
 * is '{@code >}'-separated. Comment ({@code #}) and header lines are skipped.
 */
public final class BundleCorpusLoader {

    private BundleCorpusLoader() {}

    private record Row( String queryId, String query, BundleCategory category, GoldSection gold ) {}

    public static List< BundleEvalQuestion > load( final Path csv ) {
        try ( Reader r = Files.newBufferedReader( csv ) ) {
            return parse( r );
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "Cannot read corpus: " + csv, e );
        }
    }

    public static List< BundleEvalQuestion > parse( final Reader reader ) {
        final Map< String, List< Row > > byId = new LinkedHashMap<>();
        try ( BufferedReader br = new BufferedReader( reader ) ) {
            String line;
            while ( ( line = br.readLine() ) != null ) {
                final String trimmed = line.strip();
                if ( trimmed.isEmpty() || trimmed.startsWith( "#" ) ) continue;
                if ( trimmed.startsWith( "query_id," ) ) continue; // header
                final Row row = parseRow( trimmed );
                byId.computeIfAbsent( row.queryId(), k -> new ArrayList<>() ).add( row );
            }
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "Error parsing corpus", e );
        }

        final List< BundleEvalQuestion > out = new ArrayList<>( byId.size() );
        for ( final Map.Entry< String, List< Row > > e : byId.entrySet() ) {
            final List< Row > rows = e.getValue();
            final Row first = rows.get( 0 );
            final List< GoldSection > golds = new ArrayList<>( rows.size() );
            for ( final Row row : rows ) golds.add( row.gold() );
            out.add( new BundleEvalQuestion( first.queryId(), first.query(), first.category(), golds ) );
        }
        return out;
    }

    private static Row parseRow( final String line ) {
        // Simple CSV: corpus authors must avoid commas in fields (use a heading-path
        // separator of '>', not ','). 6 columns expected.
        final String[] c = line.split( ",", -1 );
        if ( c.length < 5 ) {
            throw new IllegalArgumentException( "Malformed corpus row (need >=5 columns): " + line );
        }
        final BundleCategory cat;
        try {
            cat = BundleCategory.valueOf( c[ 2 ].strip().toUpperCase( Locale.ROOT ) );
        } catch ( final IllegalArgumentException ex ) {
            throw new IllegalArgumentException( "Unknown category '" + c[ 2 ] + "' in row: " + line, ex );
        }
        final List< String > headingPath = new ArrayList<>();
        for ( final String h : c[ 4 ].strip().split( ">" ) ) {
            if ( !h.isBlank() ) headingPath.add( h.strip() );
        }
        return new Row( c[ 0 ].strip(), c[ 1 ].strip(), cat,
            new GoldSection( c[ 3 ].strip(), headingPath ) );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=BundleCorpusLoaderTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleCorpusLoader.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleCorpusLoaderTest.java
git commit -m "feat(eval): bundle corpus CSV loader (grouped section golds)"
```

---

### Task 4: BundleEvalRunner + BundleEvalReport (orchestration)

A `BundleRetriever` is a `Function<String, List<BundleSection>>` (query → ordered bundle sections). The runner scores each question and aggregates **per category** and **overall**. Keeping the retriever a plain functional interface mirrors `DefaultRetrievalQualityRunner.Retriever` and keeps the runner testable with a fake.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalReport.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalRunner.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalRunnerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import com.wikantik.api.eval.BundleEvalQuestion;
import com.wikantik.api.eval.BundleSection;
import com.wikantik.api.eval.GoldSection;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BundleEvalRunnerTest {

    @Test
    void aggregates_recall_overall_and_per_category() {
        final List<BundleEvalQuestion> corpus = List.of(
            new BundleEvalQuestion( "q1", "deploy", BundleCategory.SIMILARITY,
                List.of( new GoldSection( "01A", List.of( "Setup" ) ) ) ),
            new BundleEvalQuestion( "q2", "what uses X", BundleCategory.RELATIONAL,
                List.of( new GoldSection( "01B", List.of( "Uses" ) ) ) ) );

        // q1 retrieves its gold (recall 1.0); q2 retrieves noise (recall 0.0).
        final BundleEvalRunner.BundleRetriever retriever = query -> {
            if ( query.contains( "deploy" ) )
                return List.of( new BundleSection( "01A", List.of( "Setup" ), "..." ) );
            return List.of( new BundleSection( "01Z", List.of( "Noise" ), "..." ) );
        };

        final BundleEvalReport report = new BundleEvalRunner( retriever, 5 ).run( corpus );

        assertEquals( 0.5, report.overallRecall(), 1e-9 );
        assertEquals( 1.0, report.recallByCategory().get( BundleCategory.SIMILARITY ), 1e-9 );
        assertEquals( 0.0, report.recallByCategory().get( BundleCategory.RELATIONAL ), 1e-9 );
        assertEquals( 2, report.questionsScored() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=BundleEvalRunnerTest`
Expected: FAIL — `BundleEvalRunner` / `BundleEvalReport` not defined.

- [ ] **Step 3: Write minimal implementation**

`BundleEvalReport.java`:
```java
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import java.util.Map;

/**
 * Aggregate bundle-quality result: overall and per-category context recall and
 * precision@K. Per-category maps always contain all {@link BundleCategory}
 * keys (0.0 when a category had no questions), so the gate can assert on each.
 */
public record BundleEvalReport(
    double overallRecall,
    double overallPrecisionAtK,
    Map< BundleCategory, Double > recallByCategory,
    Map< BundleCategory, Double > precisionByCategory,
    int questionsScored
) {
    public BundleEvalReport {
        recallByCategory = Map.copyOf( recallByCategory );
        precisionByCategory = Map.copyOf( precisionByCategory );
    }
}
```

`BundleEvalRunner.java`:
```java
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import com.wikantik.api.eval.BundleEvalQuestion;
import com.wikantik.api.eval.BundleSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Runs the bundle evaluation corpus through a {@link BundleRetriever} and
 * aggregates {@link BundleMetricsCalculator} scores overall and per category.
 */
public final class BundleEvalRunner {

    /** query text → ordered bundle sections. */
    @FunctionalInterface
    public interface BundleRetriever extends Function< String, List< BundleSection > > {}

    private final BundleRetriever retriever;
    private final int precisionK;

    public BundleEvalRunner( final BundleRetriever retriever, final int precisionK ) {
        this.retriever = retriever;
        this.precisionK = precisionK;
    }

    public BundleEvalReport run( final List< BundleEvalQuestion > corpus ) {
        final Map< BundleCategory, List< Double > > recallByCat = new EnumMap<>( BundleCategory.class );
        final Map< BundleCategory, List< Double > > precByCat = new EnumMap<>( BundleCategory.class );
        for ( final BundleCategory c : BundleCategory.values() ) {
            recallByCat.put( c, new ArrayList<>() );
            precByCat.put( c, new ArrayList<>() );
        }
        final List< Double > allRecall = new ArrayList<>();
        final List< Double > allPrec = new ArrayList<>();

        for ( final BundleEvalQuestion q : corpus ) {
            final List< BundleSection > bundle = retriever.apply( q.query() );
            final double recall = BundleMetricsCalculator.contextRecall( q.goldSections(), bundle );
            final double prec = BundleMetricsCalculator.contextPrecisionAtK( q.goldSections(), bundle, precisionK );
            recallByCat.get( q.category() ).add( recall );
            precByCat.get( q.category() ).add( prec );
            allRecall.add( recall );
            allPrec.add( prec );
        }

        return new BundleEvalReport(
            mean( allRecall ), mean( allPrec ),
            meanByCategory( recallByCat ), meanByCategory( precByCat ),
            corpus.size() );
    }

    private static Map< BundleCategory, Double > meanByCategory(
            final Map< BundleCategory, List< Double > > src ) {
        final Map< BundleCategory, Double > out = new EnumMap<>( BundleCategory.class );
        for ( final Map.Entry< BundleCategory, List< Double > > e : src.entrySet() ) {
            out.put( e.getKey(), mean( e.getValue() ) );
        }
        return out;
    }

    private static double mean( final List< Double > xs ) {
        if ( xs == null || xs.isEmpty() ) return 0.0;
        double s = 0.0;
        for ( final double x : xs ) s += x;
        return s / xs.size();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=BundleEvalRunnerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalReport.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalRunner.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalRunnerTest.java
git commit -m "feat(eval): bundle eval runner + per-category report"
```

---

### Task 5: ContextServiceBundleRetriever (production adapter)

Adapts the live `ContextRetrievalService` into a `BundleRetriever`: run `retrieve`, flatten each `RetrievedPage`'s `contributingChunks` into `BundleSection`s (resolving the page slug → canonical_id via the same `Function<String,Optional<String>>` shape the existing runner uses), preserving rank order.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/ContextServiceBundleRetriever.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalRunnerTest.java` (add a nested test) — or a new `ContextServiceBundleRetrieverTest.java`.

- [ ] **Step 1: Write the failing test** (`ContextServiceBundleRetrieverTest.java`)

```java
package com.wikantik.knowledge.eval;

import com.wikantik.api.knowledge.*;
import com.wikantik.api.eval.BundleSection;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ContextServiceBundleRetrieverTest {

    @Test
    void flattens_pages_into_sections_resolving_canonical_id() {
        final RetrievedChunk chunk = new RetrievedChunk( List.of( "Setup" ), "body", 1.0, List.of() );
        final RetrievedPage page = new RetrievedPage(
            "DeployGuide", "/wiki/DeployGuide", 1.0, "", "ops",
            List.of(), List.of( chunk ), List.of(), "admin", null );

        final ContextRetrievalService svc = new StubService(
            new RetrievalResult( "deploy", List.of( page ), 1 ) );

        final ContextServiceBundleRetriever retriever =
            new ContextServiceBundleRetriever( svc, slug -> Optional.of( "01DEP" ) );

        final List<BundleSection> sections = retriever.apply( "deploy" );
        assertEquals( 1, sections.size() );
        assertEquals( "01DEP", sections.get( 0 ).canonicalId() );
        assertEquals( List.of( "Setup" ), sections.get( 0 ).headingPath() );
    }

    /** Minimal stub returning a fixed result; other methods unused. */
    private record StubService( RetrievalResult fixed ) implements ContextRetrievalService {
        public RetrievalResult retrieve( ContextQuery q ) { return fixed; }
        public RetrievedPage getPage( String n ) { return null; }
        public PageList listPages( PageListFilter f ) { return null; }
        public List<MetadataValue> listMetadataValues( String field ) { return List.of(); }
    }
}
```

> Note: confirm the exact `ContextQuery` constructor/factory and `PageList`/`PageListFilter`/`MetadataValue` signatures with `mvn -q -pl wikantik-api test-compile` before finalizing the stub; adjust the stub to match. (These types live in `com.wikantik.api.knowledge`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-main test -Dtest=ContextServiceBundleRetrieverTest`
Expected: FAIL — `ContextServiceBundleRetriever` not defined.

- [ ] **Step 3: Write minimal implementation**

```java
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleSection;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Adapts the live {@link ContextRetrievalService} into a
 * {@link BundleEvalRunner.BundleRetriever}: runs retrieval and flattens each
 * page's contributing chunks into rank-ordered {@link BundleSection}s, resolving
 * the page slug to its canonical_id. Pages whose slug does not resolve are skipped
 * (they cannot match a gold canonical_id anyway).
 */
public final class ContextServiceBundleRetriever implements BundleEvalRunner.BundleRetriever {

    private final ContextRetrievalService service;
    private final Function< String, Optional< String > > slugToCanonicalId;

    public ContextServiceBundleRetriever( final ContextRetrievalService service,
                                          final Function< String, Optional< String > > slugToCanonicalId ) {
        this.service = service;
        this.slugToCanonicalId = slugToCanonicalId;
    }

    @Override
    public List< BundleSection > apply( final String query ) {
        final RetrievalResult result = service.retrieve( ContextQuery.of( query ) );
        final List< BundleSection > sections = new ArrayList<>();
        for ( final RetrievedPage page : result.pages() ) {
            final Optional< String > id = slugToCanonicalId.apply( page.name() );
            if ( id.isEmpty() ) continue;
            for ( final RetrievedChunk chunk : page.contributingChunks() ) {
                sections.add( new BundleSection( id.get(), chunk.headingPath(), chunk.text() ) );
            }
        }
        return sections;
    }
}
```

> `ContextQuery.of(query)` is assumed; if the real factory differs (e.g. a builder or all-args constructor), use the actual one — confirm via `grep -n "class ContextQuery\|static ContextQuery\|ContextQuery(" wikantik-api/src/main/java/com/wikantik/api/knowledge/ContextQuery.java`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=ContextServiceBundleRetrieverTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/eval/ContextServiceBundleRetriever.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/eval/ContextServiceBundleRetrieverTest.java
git commit -m "feat(eval): adapt ContextRetrievalService to a bundle retriever"
```

---

## PART B — The frozen corpus, baseline & gate

### Task 6: Author the evaluation corpus (`eval/bundle-corpus/queries.csv`)

**This is data, not code.** Acceptance criteria, not unit tests, gate it.

- [ ] **Step 1:** Create `eval/bundle-corpus/queries.csv` with the header
  `query_id,query,category,gold_canonical_id,gold_heading_path,notes`.
- [ ] **Step 2:** Seed from `eval/retrieval-queries.csv` (40 page-level queries). For each, open the `ideal_page` under `docs/wikantik-pages/`, find its `canonical_id` in frontmatter, and pick the **section** that actually answers the query → one or more gold rows. (LLM may *draft* candidate gold sections; a human verifies each against the page — per the design's LLM posture.)
- [ ] **Step 3:** Add **≥ 8 RELATIONAL** questions (multi-hop — e.g. "what implements the SHACL gate and what does it depend on", "which pages does OntologyPageSync rename-logic touch"). These are the fair-trial questions the KG is owed (ADR-0002); their gold sections will often span two pages.
- [ ] **Step 4:** Add **≥ 6 BOUNDARY** questions whose answer straddles a section/chunk boundary (the parent-child trigger evidence). Note in the `notes` column *why* it's a boundary case.
- [ ] **Step 5: Acceptance check** — write a tiny throwaway assertion (or reuse `BundleCorpusLoader.load`) proving every `gold_canonical_id` resolves to a real page and the file parses:

Run:
```bash
mvn -q -pl wikantik-main test -Dtest=BundleCorpusLoaderTest    # parser still green
grep -c '^[^#q]' eval/bundle-corpus/queries.csv                 # row count sanity
```
Expected: every category present; ≥ 40 questions total; loader parses without error.

- [ ] **Step 6: Commit**

```bash
git add eval/bundle-corpus/queries.csv
git commit -m "eval: section-level bundle corpus v1 (similarity + relational + boundary)"
```

---

### Task 7: Checked-in embeddings fixture for the frozen corpus

The CI gate must run **without** the live Ollama embedder. Pre-compute embeddings for the frozen `docs/wikantik-pages/` corpus once (against qwen3 / 1024-dim) and check them in as a SQL seed the gate loads into its Testcontainer. Re-snapshot only when you intentionally level up (new model/corpus).

- [ ] **Step 1:** Write `bin/eval/snapshot-corpus-embeddings.sh` — a documented one-shot that: builds chunks for every page under `docs/wikantik-pages/` via the existing chunker, embeds them via the configured Ollama backend, and dumps `kg_content_chunks` + `content_chunk_embeddings` rows (for those pages) to `eval/bundle-corpus/embeddings/corpus-seed.sql` as `INSERT`s. (Mirror the column set used in `RetrievalQualitySmokeTest.seedPage` — `kg_content_chunks(page_name,chunk_index,text,char_count,token_count_estimate,content_hash)` and `content_chunk_embeddings(chunk_id,model_code,dim,vec,embedding)`.)
- [ ] **Step 2:** Run it against your local deployment (embedder available) to generate the seed. Record the model code + dim in a header comment in the `.sql`.
- [ ] **Step 3: Commit**

```bash
git add bin/eval/snapshot-corpus-embeddings.sh eval/bundle-corpus/embeddings/corpus-seed.sql
git commit -m "eval: checked-in embedding snapshot of the frozen corpus (offline gate fixture)"
```

> Trade-off note (record in the script header): the seed is large and regenerated, not hand-edited; it is the embedding half of the frozen snapshot. This is the deliberate "level-up = re-snapshot" cost the design accepted.

---

### Task 8: BundleEvalGateTest (the reproducible CI gate)

Mirrors `RetrievalQualitySmokeTest`'s two-tier shape: a fast wiring test (always runs, fake retriever) + a real-corpus gate (Testcontainers, `disabledWithoutDocker = true`) that loads the Task-7 seed, builds the configured `ChunkVectorIndex`, runs `BundleEvalRunner` over the Task-6 corpus, and asserts per-category floors from `eval/bundle-corpus/thresholds.properties`.

**Files:**
- Create: `eval/bundle-corpus/thresholds.properties`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalGateTest.java`

- [ ] **Step 1:** Create `eval/bundle-corpus/thresholds.properties` with placeholders to be tightened in Task 9:
```properties
# Per-category context-recall floors. Ratchet UP as quality improves; never silently lower.
# Set from the Task-9 baseline (baseline minus a small epsilon). 0.0 until baselined.
recall.SIMILARITY.min = 0.0
recall.RELATIONAL.min = 0.0
recall.BOUNDARY.min   = 0.0
recall.OVERALL.min     = 0.0
precision.OVERALL.min  = 0.0
```
- [ ] **Step 2: Write the failing wiring test** (always-on tier):
```java
@Test
void gate_wiring_scores_corpus_with_a_fake_retriever() {
    final List<BundleEvalQuestion> corpus = BundleCorpusLoader.load(
        Path.of( "..", "eval", "bundle-corpus", "queries.csv" ) );
    assertFalse( corpus.isEmpty(), "corpus must load" );
    // Oracle retriever: returns each question's own first gold as a section.
    final BundleEvalRunner.BundleRetriever oracle = q -> corpus.stream()
        .filter( x -> x.query().equals( q ) ).findFirst()
        .map( x -> x.goldSections().stream()
            .map( g -> new BundleSection( g.canonicalId(), g.headingPath(), "x" ) ).toList() )
        .orElse( List.of() );
    final BundleEvalReport report = new BundleEvalRunner( oracle, 5 ).run( corpus );
    assertEquals( 1.0, report.overallRecall(), 1e-9, "oracle must achieve perfect recall" );
}
```
- [ ] **Step 3:** Run: `mvn -q -pl wikantik-main test -Dtest=BundleEvalGateTest` → FAIL (path or class). Fix the relative corpus path (tests run with CWD = module dir; `../eval/...` reaches repo root). Iterate to GREEN.
- [ ] **Step 4:** Add the `@Nested @Testcontainers(disabledWithoutDocker=true)` real-corpus tier: load `corpus-seed.sql`, build the index for the configured backend (reuse `buildRunnerFor`-style wiring from `RetrievalQualitySmokeTest`), wrap it in `ContextServiceBundleRetriever` *or* a direct index→section adapter, run the corpus, and assert each `recall.<CATEGORY>.min` / `recall.OVERALL.min` / `precision.OVERALL.min`.
- [ ] **Step 5: Commit**

```bash
git add eval/bundle-corpus/thresholds.properties \
        wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalGateTest.java
git commit -m "feat(eval): bundle-quality CI gate (wiring tier + pgvector real-corpus tier)"
```

---

### Task 9: Establish & freeze the baseline

- [ ] **Step 1:** Ensure the baseline model set: extractor = **Gemma4:12b** (set `wikantik.knowledge.extractor.ollama.model` accordingly; the operator prepares the model), embeddings = qwen3 (`qwen3-embedding-0.6b`), KG rerank **off** (Task 10), dense backend = whatever Task 11 found in prod.
- [ ] **Step 2:** Re-extract the corpus KG with Gemma4:12b (targeted is fine; full corpus preferred for a clean baseline) so the KG baseline reflects the floor model, then regenerate the Task-7 embedding seed if chunks changed.
- [ ] **Step 3:** Run the real-corpus gate locally with Docker to print overall + per-category recall/precision. Record the numbers, the model set, the dense backend, and the date in `eval/bundle-corpus/baseline-notes.md`.
- [ ] **Step 4:** Set each floor in `thresholds.properties` to **baseline − 0.03** (small epsilon, so the gate catches regressions without flapping). Commit the notes + thresholds.

```bash
git add eval/bundle-corpus/baseline-notes.md eval/bundle-corpus/thresholds.properties
git commit -m "eval: freeze Phase-0 baseline (Gemma4:12b extract / qwen3 embed / rerank off) + gate floors"
```

---

## PART C — Honest cleanup & investigation

### Task 10: Retire the misleading `boost=0` "TEMP DIAGNOSTIC"; make rerank honestly off

The KG rerank is off because of a stale "TEMP DIAGNOSTIC (2026-05-11)" override, not a decision. ADR-0002 says it stays off *pending the Phase-4 fair trial*. Make that explicit and the default.

- [ ] **Step 1:** Edit `wikantik-main/src/main/resources/ini/wikantik.properties:1305` — change `wikantik.search.graph.boost = 0.2` to `0.0` with the comment: `# OFF pending the Phase-4 fair KG-rerank trial (ADR-0002). Was 0.2; a weak extractor + ~7% coverage made it net-neutral. Re-enable only on measured bundle lift.`
- [ ] **Step 2:** In the deployed `tomcat/tomcat-11/lib/wikantik-custom.properties` (and the prod `.env`/template under `wikantik-war/src/main/config/tomcat/`), remove the "TEMP DIAGNOSTIC … Remove this override after the diagnostic" wording; if an override remains, make it `0.0` with the same honest comment. Confirm no tracked template still carries the diagnostic: `grep -rn "TEMP DIAGNOSTIC" . | grep -v /target/`.
- [ ] **Step 3:** Verify `GraphRerankConfig.enabled()` still returns false at boost 0.0 (it returns `boost > 0.0`) — no code change, just confirm with `grep -n "boost > 0" wikantik-main/src/main/java/com/wikantik/search/hybrid/GraphRerankConfig.java`.
- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "config: default graph.boost to 0.0 — rerank off pending Phase-4 fair trial (ADR-0002)"
```

---

### Task 11: Resolve & document the actual prod dense backend

The design flagged a contradiction: docs say `lucene-hnsw` is the docker1 default, but the checked-in local config is `inmemory`. The real value lives in the gitignored prod env.

- [ ] **Step 1:** On docker1 (or in `remote.env`/`.env.prod`), read the effective `wikantik.search.dense.backend`. (Operator task — the value isn't in the repo.)
- [ ] **Step 2:** Record the actual prod backend in `eval/bundle-corpus/baseline-notes.md` and, if it differs from `inmemory`, note the intended target. If prod is still `inmemory` (brute-force), file a follow-up to flip to `lucene-hnsw`/`pgvector` (the parity gate in `RetrievalQualitySmokeTest` already proves they're within 0.02 nDCG).
- [ ] **Step 3:** Run the baseline (Task 9) against the **actual prod backend** so the number reflects reality. Commit the notes.

---

### Task 12: Root-cause the 4 all-retriever miss cases

From `eval/report-qwen3-embedding-0.6b-2026-06-03-current-corpus.txt`, these miss across BM25/dense/hybrid: `EmbeddingsVectorDB`, `DockerSetup`, `JspwikiDeployment`, `TestDrivenDevelopmentGuide`.

- [ ] **Step 1:** For each, open the page under `docs/wikantik-pages/`, the failing query, and the chunk boundaries. Classify the miss as **chunking** (answer straddled a boundary / diluted in a 512-token chunk), **vocabulary** (query terms absent from the page), or **missing-content** (the page doesn't actually answer it).
- [ ] **Step 2:** Tag any chunking-caused miss as a **BOUNDARY** question in the corpus (Task 6) — that's the measured evidence that would later justify parent-child chunking (the deferred Q9 trigger).
- [ ] **Step 3:** Write findings (one paragraph per page + the classification) into `eval/bundle-corpus/baseline-notes.md`. Commit.

```bash
git add eval/bundle-corpus/baseline-notes.md
git commit -m "eval: root-cause the 4 all-retriever miss cases; tag boundary evidence"
```

---

## Final verification

- [ ] Full module build: `mvn -q -pl wikantik-main -am install -DskipITs` → green.
- [ ] Full reactor incl. ITs before any prod-code commit lands (per CLAUDE.md): `mvn clean install -Pintegration-tests -fae`.
- [ ] The gate is wired into the normal test run (the wiring tier always runs; the Docker tier runs where Docker is present).
- [ ] `eval/bundle-corpus/baseline-notes.md` records: the frozen baseline numbers, the model set, the actual prod dense backend, and the 4 miss-case classifications.
- [ ] Thresholds in `thresholds.properties` are set to baseline − 0.03 and committed.

## Self-review against the Phase-0 spec

- **Frozen, versioned, checked-in corpus** → Task 6 (`queries.csv`) + Task 7 (embeddings seed). ✓
- **Section-level golds** → Task 1 (`GoldSection`) + Task 6. ✓
- **Three-way question mix (similarity/relational/boundary)** → Task 6 steps 2–4 + `BundleCategory`. ✓
- **Bundle metrics: recall / precision / citation faithfulness** → Task 2. ✓ (faithfulness defined, exercised in Phase 1.)
- **CI gate, reproducible** → Task 8 + Task 7 (offline embeddings). ✓
- **Honest re-baseline; kill boost=0** → Tasks 9, 10. ✓
- **Resolve prod dense backend** → Task 11. ✓
- **Root-cause the 4 misses** → Task 12. ✓
- **LLM posture: freeze Gemma4:12b + qwen3; gate LLM-free** → Task 9 + Task 2 (no LLM in metrics). ✓
- **Level-up = data edit** → corpus is CSV, thresholds are properties; no code change to add a question. ✓
