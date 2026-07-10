# Retrieval Eval Harness — Phase 1 (Scheduled Runs + Persistence + Regression Alert) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the existing bundle-eval harness from a manual measurement into a scheduled job that runs the frozen corpus against the live bundle path, persists each run's recall@12 (overall + per-category) to a table, and logs a regression alert when any recall falls below its threshold floor — so the recall gate reranking/agentic/multimodal work depends on becomes enforceable instead of manual.

**Architecture:** Reuse the existing harness end-to-end — `BundleEvalRunner.run(corpus)` → `BundleEvalReport`, fed by the existing `BundleHarnessAdapter` (which wraps `BundleAssemblyService.assemble()` — the dense-chunk bundle path, the one reranking affects), with the corpus from `BundleCorpusLoader.load(eval/bundle-corpus/queries.csv)`. Add: a per-category threshold loader + a pure regression check, a `bundle_eval_run` table (V045) + a fail-safe JDBC DAO (templated on `JdbcBriefingLogService`), and a `BundleEvalScheduler` (templated on `OntologyRebuildScheduler`) that orchestrates run → check → persist → alert, wired at the post-startup point where the bundle service becomes live. Default interval 0 = disabled (opt-in, because a real run needs a live embedding index).

**Tech Stack:** Java 25, JUnit 5, Mockito (on the `wikantik-main` test path), Log4j2, PostgreSQL (numbered SQL migration), Maven. No new dependencies.

## Design decisions (grounded in the current code)

1. **Reuse the whole harness — build only scheduling, persistence, and the alert.** `com.wikantik.knowledge.eval.{BundleEvalRunner, BundleEvalReport, BundleMetricsCalculator, BundleCorpusLoader}` and `com.wikantik.api.eval.{BundleEvalQuestion, BundleSection, BundleCategory}` all exist. `BundleEvalReport` is `record(double overallRecall, double overallPrecisionAtK, Map<BundleCategory,Double> recallByCategory, Map<BundleCategory,Double> precisionByCategory, int questionsScored)`. Do not reimplement any of this.

2. **Measure the BUNDLE path, via the existing `BundleHarnessAdapter` — NOT `ContextServiceBundleRetriever`.** `ContextServiceBundleRetriever` wraps the older page-gated `ContextRetrievalService.retrieve` (the 0.37–0.57 threshold path in `thresholds.properties`). `com.wikantik.knowledge.bundle.BundleHarnessAdapter( BundleAssemblyService )` calls `assemble(query)` and maps to `com.wikantik.api.eval.BundleSection` — that is the dense-chunk bundle path reranking gates on. The scheduler uses `BundleHarnessAdapter`.

3. **`BundleCategory` has exactly three values: SIMILARITY, RELATIONAL, BOUNDARY** (confirmed from `eval/bundle-corpus/thresholds.properties`, keys `recall.SIMILARITY/RELATIONAL/BOUNDARY.min` + `recall.OVERALL.min`). The table flattens per-category recall into three columns rather than a child table.

4. **Regression = below the per-category/overall floor in `thresholds.properties`, not below the previous run.** The floors already exist and are the project's ratchet. The check is a pure function of (report, thresholds); Phase 1 does not compare run-to-run (YAGNI).

5. **Default interval 0 = disabled (opt-in).** Mirrors `OntologyRebuildScheduler` (no-op when `intervalHours <= 0`). A real run needs a live embedding index, so it must not fire in CI or a fresh install; operators enable it in a deployment that has the index (`wikantik.bundle.eval.interval.hours`).

6. **Hermetic everywhere except the live recall number.** The regression check (pure), the scheduler orchestration (stub retriever, like `BundleEvalGateTest`'s oracle tier), and the DAO (Mockito-mocked JDBC chain) are all hermetic unit tests. The real recall number against a live index is what runs on the schedule in a deployment — not a CI test — exactly the oracle-vs-real-corpus split already in `BundleEvalGateTest`. Migration real-apply idempotency is a documented manual/IT check.

## Global Constraints

- **Java 25.** New code in existing packages: harness/scheduler/DAO in `com.wikantik.knowledge.eval` (wikantik-main); no new package, no new module, no new dependency.
- **ASF license header verbatim** on every new `.java` file (copy from `BundleEvalReport.java` lines 1–18).
- **Every schema change adds a numbered migration** under `bin/db/migrations/` — next is `V045`. Idempotent DDL (`CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`), `:app_user` psql var for grants, verified a no-op on re-apply. Never edit an applied migration.
- **Fail-safe persistence:** every DB write swallows failures with a `LOG.warn` and never propagates (a flaky eval log must never crash the app) — pattern from `JdbcBriefingLogService`.
- **Default OFF:** `wikantik.bundle.eval.interval.hours` defaults to `0` (disabled); the default `wikantik.properties` documents it commented/at 0. No scheduled run in CI or a fresh install.
- **Never swallow an exception without at least a `LOG.warn` with context** (CLAUDE.md).
- **Code style:** spaces inside generics/parens (`Map< BundleCategory, Double >`, `( final String q )`), `final` on params/locals, Log4j2 `LogManager.getLogger`.
- **TDD:** failing test first. Run one class with `mvn test -pl wikantik-main -Dtest=ClassName`.
- Worked directly on `main` (sole-dev repo) — no branch; commit directly.

---

### Task 1: `bundle_eval_run` table (V045) + `BundleEvalRun` row record

**Files:**
- Create: `bin/db/migrations/V045__bundle_eval_run.sql`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalRun.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalRunTest.java`

**Interfaces:**
- Produces: `record BundleEvalRun( String configId, double overallRecall, double overallPrecision, double recallSimilarity, double recallRelational, double recallBoundary, int questionsScored, boolean regression )` with a static `from(BundleEvalReport report, String configId, boolean regression)` factory that reads the three categories out of `report.recallByCategory()` (0.0 when absent — the report guarantees all keys present, but be null-safe).

- [ ] **Step 1: Write the failing test**

```java
/* <ASF license header — copy BundleEvalReport.java lines 1–18> */
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BundleEvalRunTest {

    private static BundleEvalReport report( final double overall, final double sim, final double rel, final double bnd ) {
        final Map< BundleCategory, Double > recall = new EnumMap<>( BundleCategory.class );
        recall.put( BundleCategory.SIMILARITY, sim );
        recall.put( BundleCategory.RELATIONAL, rel );
        recall.put( BundleCategory.BOUNDARY, bnd );
        final Map< BundleCategory, Double > prec = new EnumMap<>( BundleCategory.class );
        for ( final BundleCategory c : BundleCategory.values() ) prec.put( c, 0.0 );
        return new BundleEvalReport( overall, 0.0, recall, prec, 42 );
    }

    @Test
    void from_flattensCategoriesAndCarriesFields() {
        final BundleEvalRun run = BundleEvalRun.from( report( 0.74, 0.70, 0.80, 0.72 ), "2.3.6-SNAPSHOT", false );
        assertEquals( "2.3.6-SNAPSHOT", run.configId() );
        assertEquals( 0.74, run.overallRecall(), 1e-9 );
        assertEquals( 0.70, run.recallSimilarity(), 1e-9 );
        assertEquals( 0.80, run.recallRelational(), 1e-9 );
        assertEquals( 0.72, run.recallBoundary(), 1e-9 );
        assertEquals( 42, run.questionsScored() );
        assertFalse( run.regression() );
    }

    @Test
    void from_missingCategory_defaultsToZero() {
        final Map< BundleCategory, Double > recall = new EnumMap<>( BundleCategory.class );
        recall.put( BundleCategory.SIMILARITY, 0.5 );  // RELATIONAL, BOUNDARY absent
        final Map< BundleCategory, Double > prec = new EnumMap<>( BundleCategory.class );
        final BundleEvalReport r = new BundleEvalReport( 0.5, 0.0, recall, prec, 1 );
        final BundleEvalRun run = BundleEvalRun.from( r, "x", true );
        assertEquals( 0.0, run.recallRelational(), 1e-9 );
        assertEquals( 0.0, run.recallBoundary(), 1e-9 );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalRunTest`
Expected: FAIL — `BundleEvalRun` does not exist.

- [ ] **Step 3: Write the migration**

`bin/db/migrations/V045__bundle_eval_run.sql`:

```sql
-- Scheduled bundle-eval run results (RetrievalEvaluationObservability Phase 1, 2026-07-10).
-- One row per scheduled harness run; recall@12 overall + per BundleCategory
-- (SIMILARITY/RELATIONAL/BOUNDARY); regression=true when any recall fell below its
-- threshold floor (eval/bundle-corpus/thresholds.properties).
CREATE TABLE IF NOT EXISTS bundle_eval_run (
    id                BIGSERIAL   PRIMARY KEY,
    run_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    config_id         TEXT        NOT NULL,
    overall_recall    DOUBLE PRECISION NOT NULL,
    overall_precision DOUBLE PRECISION NOT NULL,
    recall_similarity DOUBLE PRECISION NOT NULL,
    recall_relational DOUBLE PRECISION NOT NULL,
    recall_boundary   DOUBLE PRECISION NOT NULL,
    questions_scored  INTEGER     NOT NULL,
    regression        BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_bundle_eval_run_run_at ON bundle_eval_run (run_at);
GRANT SELECT, INSERT ON bundle_eval_run TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE bundle_eval_run_id_seq TO :app_user;
```

- [ ] **Step 4: Write the record**

`BundleEvalRun.java`:

```java
/* <ASF license header> */
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;

/** One persisted scheduled-eval run: overall + per-category recall@12, plus the regression verdict.
 *  {@code run_at} is stamped by the DB default, so it is not a field here. */
public record BundleEvalRun(
    String configId,
    double overallRecall,
    double overallPrecision,
    double recallSimilarity,
    double recallRelational,
    double recallBoundary,
    int questionsScored,
    boolean regression
) {
    /** Flattens a {@link BundleEvalReport}'s per-category recall map (missing category → 0.0). */
    public static BundleEvalRun from( final BundleEvalReport report, final String configId, final boolean regression ) {
        return new BundleEvalRun(
            configId,
            report.overallRecall(),
            report.overallPrecisionAtK(),
            report.recallByCategory().getOrDefault( BundleCategory.SIMILARITY, 0.0 ),
            report.recallByCategory().getOrDefault( BundleCategory.RELATIONAL, 0.0 ),
            report.recallByCategory().getOrDefault( BundleCategory.BOUNDARY, 0.0 ),
            report.questionsScored(),
            regression );
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalRunTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add bin/db/migrations/V045__bundle_eval_run.sql \
        wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalRun.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalRunTest.java
git commit -m "feat(eval): bundle_eval_run table (V045) + BundleEvalRun row record"
```

---

### Task 2: `BundleEvalThresholds` loader + `BundleEvalRegressionCheck` (pure)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalThresholds.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalRegressionCheck.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalRegressionCheckTest.java`

**Interfaces:**
- Consumes: `BundleEvalReport`, `BundleCategory`.
- Produces: `record BundleEvalThresholds( double overall, double similarity, double relational, double boundary )` with `static BundleEvalThresholds fromProperties( java.util.Properties p )` reading `recall.OVERALL.min`/`recall.SIMILARITY.min`/`recall.RELATIONAL.min`/`recall.BOUNDARY.min` (missing → 0.0, which never triggers a regression — fail-open on missing thresholds is correct: a missing floor should not manufacture an alert); and `record RegressionResult( boolean regression, String detail )` from `static BundleEvalRegressionCheck.evaluate( BundleEvalReport report, BundleEvalThresholds t )`.

> **Before writing:** grep for an existing thresholds loader (`grep -rn "thresholds.properties\|recall.OVERALL" wikantik-main/src`). `BundleEvalGateTest` references the corpus but its real-corpus tier was a TODO, so a loader likely does not exist yet. If one does, reuse it and skip `BundleEvalThresholds`.

- [ ] **Step 1: Write the failing test**

```java
/* <ASF license header> */
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleEvalRegressionCheckTest {

    private static BundleEvalReport report( final double overall, final double sim, final double rel, final double bnd ) {
        final Map< BundleCategory, Double > recall = new EnumMap<>( BundleCategory.class );
        recall.put( BundleCategory.SIMILARITY, sim );
        recall.put( BundleCategory.RELATIONAL, rel );
        recall.put( BundleCategory.BOUNDARY, bnd );
        return new BundleEvalReport( overall, 0.0, recall, new EnumMap<>( BundleCategory.class ), 10 );
    }

    private static final BundleEvalThresholds FLOORS =
        new BundleEvalThresholds( 0.35, 0.30, 0.40, 0.45 );  // matches thresholds.properties seed

    @Test
    void allAboveFloors_noRegression() {
        final var res = BundleEvalRegressionCheck.evaluate( report( 0.74, 0.70, 0.80, 0.72 ), FLOORS );
        assertFalse( res.regression() );
    }

    @Test
    void overallBelowFloor_isRegression_withDetail() {
        final var res = BundleEvalRegressionCheck.evaluate( report( 0.30, 0.70, 0.80, 0.72 ), FLOORS );
        assertTrue( res.regression() );
        assertTrue( res.detail().contains( "OVERALL" ), "detail names the breached floor, was: " + res.detail() );
    }

    @Test
    void oneCategoryBelowFloor_isRegression() {
        // RELATIONAL 0.35 < 0.40 floor; others fine.
        final var res = BundleEvalRegressionCheck.evaluate( report( 0.74, 0.70, 0.35, 0.72 ), FLOORS );
        assertTrue( res.regression() );
        assertTrue( res.detail().contains( "RELATIONAL" ) );
    }

    @Test
    void exactlyAtFloor_isNotRegression() {
        final var res = BundleEvalRegressionCheck.evaluate( report( 0.35, 0.30, 0.40, 0.45 ), FLOORS );
        assertFalse( res.regression(), "at-floor is acceptable; only strictly-below regresses" );
    }

    @Test
    void thresholdsFromProperties_parseAndMissingIsZero() {
        final Properties p = new Properties();
        p.setProperty( "recall.OVERALL.min", "0.35" );
        p.setProperty( "recall.SIMILARITY.min", "0.30" );
        // RELATIONAL, BOUNDARY absent -> 0.0
        final BundleEvalThresholds t = BundleEvalThresholds.fromProperties( p );
        assertEquals( 0.35, t.overall(), 1e-9 );
        assertEquals( 0.30, t.similarity(), 1e-9 );
        assertEquals( 0.0, t.relational(), 1e-9 );
        assertEquals( 0.0, t.boundary(), 1e-9 );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalRegressionCheckTest`
Expected: FAIL — `BundleEvalThresholds`/`BundleEvalRegressionCheck` do not exist.

- [ ] **Step 3: Write `BundleEvalThresholds`**

```java
/* <ASF license header> */
package com.wikantik.knowledge.eval;

import java.util.Properties;

/** Per-category + overall context-recall floors (from eval/bundle-corpus/thresholds.properties).
 *  A missing floor reads as 0.0 — fail-open, so an unconfigured category never manufactures a regression. */
public record BundleEvalThresholds( double overall, double similarity, double relational, double boundary ) {

    public static BundleEvalThresholds fromProperties( final Properties p ) {
        return new BundleEvalThresholds(
            d( p, "recall.OVERALL.min" ),
            d( p, "recall.SIMILARITY.min" ),
            d( p, "recall.RELATIONAL.min" ),
            d( p, "recall.BOUNDARY.min" ) );
    }

    private static double d( final Properties p, final String key ) {
        final String raw = p.getProperty( key );
        if ( raw == null || raw.isBlank() ) return 0.0;
        try {
            return Double.parseDouble( raw.trim() );
        } catch ( final NumberFormatException e ) {
            return 0.0;
        }
    }
}
```

- [ ] **Step 4: Write `BundleEvalRegressionCheck`**

```java
/* <ASF license header> */
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;

/** Pure regression verdict: a run regresses when overall recall, or any per-category recall,
 *  is STRICTLY below its threshold floor. The detail string names every breached floor. */
public final class BundleEvalRegressionCheck {

    private BundleEvalRegressionCheck() {}

    public record RegressionResult( boolean regression, String detail ) {}

    public static RegressionResult evaluate( final BundleEvalReport report, final BundleEvalThresholds t ) {
        final StringBuilder breaches = new StringBuilder();
        check( breaches, "OVERALL", report.overallRecall(), t.overall() );
        check( breaches, "SIMILARITY", recall( report, BundleCategory.SIMILARITY ), t.similarity() );
        check( breaches, "RELATIONAL", recall( report, BundleCategory.RELATIONAL ), t.relational() );
        check( breaches, "BOUNDARY", recall( report, BundleCategory.BOUNDARY ), t.boundary() );
        return breaches.isEmpty()
            ? new RegressionResult( false, "all recall floors met" )
            : new RegressionResult( true, "recall below floor: " + breaches );
    }

    private static void check( final StringBuilder sb, final String name, final double value, final double floor ) {
        if ( value < floor ) {
            if ( sb.length() > 0 ) sb.append( "; " );
            sb.append( String.format( "%s %.3f < %.3f", name, value, floor ) );
        }
    }

    private static double recall( final BundleEvalReport report, final BundleCategory c ) {
        return report.recallByCategory().getOrDefault( c, 0.0 );
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalRegressionCheckTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalThresholds.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalRegressionCheck.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalRegressionCheckTest.java
git commit -m "feat(eval): threshold loader + pure recall regression check"
```

---

### Task 3: `BundleEvalRunDao` — fail-safe JDBC insert

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalRunDao.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalRunDaoTest.java`

**Interfaces:**
- Consumes: `BundleEvalRun` (Task 1), `javax.sql.DataSource`.
- Produces: `class BundleEvalRunDao( DataSource dataSource )` with `void insert( BundleEvalRun run )` — writes one row, swallowing any failure with a `LOG.warn` (never throws). Templated on `JdbcBriefingLogService.insert`.

- [ ] **Step 1: Write the failing test** (Mockito-mock the JDBC chain — hermetic, no DB)

```java
/* <ASF license header> */
package com.wikantik.knowledge.eval;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BundleEvalRunDaoTest {

    private static BundleEvalRun run() {
        return new BundleEvalRun( "2.3.6", 0.74, 0.10, 0.70, 0.80, 0.72, 42, false );
    }

    @Test
    void insert_bindsAllColumnsAndExecutes() throws Exception {
        final PreparedStatement ps = mock( PreparedStatement.class );
        final Connection conn = mock( Connection.class );
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( anyString() ) ).thenReturn( ps );

        new BundleEvalRunDao( ds ).insert( run() );

        verify( ps ).setString( 1, "2.3.6" );
        verify( ps ).setDouble( 2, 0.74 );   // overall_recall
        verify( ps ).setDouble( 3, 0.10 );   // overall_precision
        verify( ps ).setDouble( 4, 0.70 );   // recall_similarity
        verify( ps ).setDouble( 5, 0.80 );   // recall_relational
        verify( ps ).setDouble( 6, 0.72 );   // recall_boundary
        verify( ps ).setInt( 7, 42 );
        verify( ps ).setBoolean( 8, false );
        verify( ps ).executeUpdate();
    }

    @Test
    void insert_swallowsSqlException_neverThrows() throws Exception {
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "db down" ) );
        // must not throw
        new BundleEvalRunDao( ds ).insert( run() );
    }

    @Test
    void insert_nullRun_isNoOp() throws Exception {
        final DataSource ds = mock( DataSource.class );
        new BundleEvalRunDao( ds ).insert( null );
        verify( ds, never() ).getConnection();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalRunDaoTest`
Expected: FAIL — `BundleEvalRunDao` does not exist.

- [ ] **Step 3: Write the DAO** (mirror `JdbcBriefingLogService`)

```java
/* <ASF license header> */
package com.wikantik.knowledge.eval;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/** Fail-safe JDBC writer for {@link BundleEvalRun} rows. A write failure is swallowed with a
 *  {@code LOG.warn} and never propagates — a flaky eval log must never affect the app. */
public final class BundleEvalRunDao {

    private static final Logger LOG = LogManager.getLogger( BundleEvalRunDao.class );

    private static final String INSERT_SQL =
        "INSERT INTO bundle_eval_run (config_id, overall_recall, overall_precision, "
        + "recall_similarity, recall_relational, recall_boundary, questions_scored, regression) "
        + "VALUES (?,?,?,?,?,?,?,?)";

    private final DataSource dataSource;

    public BundleEvalRunDao( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    public void insert( final BundleEvalRun run ) {
        if ( run == null ) {
            return;
        }
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( INSERT_SQL ) ) {
            ps.setString( 1, run.configId() );
            ps.setDouble( 2, run.overallRecall() );
            ps.setDouble( 3, run.overallPrecision() );
            ps.setDouble( 4, run.recallSimilarity() );
            ps.setDouble( 5, run.recallRelational() );
            ps.setDouble( 6, run.recallBoundary() );
            ps.setInt( 7, run.questionsScored() );
            ps.setBoolean( 8, run.regression() );
            ps.executeUpdate();
        } catch ( final Exception e ) {
            LOG.warn( "bundle_eval_run write failed (configId={}): {}", run.configId(), e.getMessage() );
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalRunDaoTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalRunDao.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalRunDaoTest.java
git commit -m "feat(eval): fail-safe JDBC BundleEvalRunDao"
```

---

### Task 4: `BundleEvalScheduler` — run → check → persist → alert

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalScheduler.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalSchedulerTest.java`

**Interfaces:**
- Consumes: `BundleEvalRunner`, `BundleEvalRunner.BundleRetriever`, `BundleCorpusLoader`, `BundleEvalReport`, `BundleEvalThresholds`, `BundleEvalRegressionCheck`, `BundleEvalRun`, `BundleEvalRunDao`.
- Produces: `class BundleEvalScheduler( BundleEvalRunner.BundleRetriever retriever, java.nio.file.Path corpusCsv, BundleEvalThresholds thresholds, BundleEvalRunDao dao, String configId, int precisionK, long intervalHours )` with `void start()` (no-op when `intervalHours <= 0`), `void stop()`, and package-visible `void runOnce()` that: loads the corpus, runs `new BundleEvalRunner(retriever, precisionK).run(corpus)`, evaluates the regression check, persists a `BundleEvalRun`, and logs `WARN` on regression / `INFO` on pass. `runOnce` never throws (any failure → one `LOG.warn`).

- [ ] **Step 1: Write the failing test** (hermetic — oracle/stub retrievers, no Docker)

```java
/* <ASF license header> */
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleSection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleEvalSchedulerTest {

    /** Repo-root-relative; surefire CWD = the wikantik-main module dir (same as BundleEvalGateTest). */
    private static final Path CORPUS = Path.of( "..", "eval", "bundle-corpus", "queries.csv" );

    private static final BundleEvalThresholds FLOORS =
        new BundleEvalThresholds( 0.35, 0.30, 0.40, 0.45 );

    /** DAO that captures the last row instead of touching a DB. */
    private static final class CapturingDao extends BundleEvalRunDao {
        final AtomicReference< BundleEvalRun > last = new AtomicReference<>();
        CapturingDao() { super( null ); }
        @Override public void insert( final BundleEvalRun run ) { last.set( run ); }
    }

    @Test
    void runOnce_oracleRetriever_persistsNoRegression() {
        // Oracle: return each question's own gold sections -> perfect recall -> above all floors.
        final List< BundleEvalQuestion > corpus = BundleCorpusLoader.load( CORPUS );
        final BundleEvalRunner.BundleRetriever oracle = query -> corpus.stream()
            .filter( q -> q.query().equals( query ) ).findFirst()
            .map( q -> q.goldSections().stream()
                .map( g -> new BundleSection( g.canonicalId(), g.headingPath(), "x" ) ).toList() )
            .orElse( List.of() );
        final CapturingDao dao = new CapturingDao();

        new BundleEvalScheduler( oracle, CORPUS, FLOORS, dao, "test", 5, 0 ).runOnce();

        final BundleEvalRun row = dao.last.get();
        assertNotNull( row, "a row must be persisted" );
        assertFalse( row.regression(), "oracle recall is perfect -> no regression" );
    }

    @Test
    void runOnce_emptyRetriever_persistsRegression() {
        // Empty retriever -> zero recall -> below every floor -> regression.
        final CapturingDao dao = new CapturingDao();
        new BundleEvalScheduler( query -> List.of(), CORPUS, FLOORS, dao, "test", 5, 0 ).runOnce();
        assertTrue( dao.last.get().regression(), "zero recall must be flagged as a regression" );
    }

    @Test
    void start_disabledWhenIntervalNonPositive_noThrow() {
        // interval 0 -> start() is a no-op (never schedules); must not throw.
        new BundleEvalScheduler( q -> List.of(), CORPUS, FLOORS, new CapturingDao(), "test", 5, 0 ).start();
    }
}
```

> `BundleEvalQuestion.goldSections()` returns `List< com.wikantik.api.eval.BundleSection >` (confirm the accessor name by reading `BundleEvalQuestion.java`; the oracle in `BundleEvalGateTest` already uses `q.goldSections()` and `g.canonicalId()`/`g.headingPath()`).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalSchedulerTest`
Expected: FAIL — `BundleEvalScheduler` does not exist.

- [ ] **Step 3: Write the scheduler** (mirror `OntologyRebuildScheduler` start/stop; orchestrate in `runOnce`)

```java
/* <ASF license header> */
package com.wikantik.knowledge.eval;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Periodically runs the frozen bundle-eval corpus against the live bundle path, persists the
 *  result, and logs a regression alert when recall falls below a threshold floor. Disabled when
 *  {@code intervalHours <= 0}. {@link #runOnce} never throws — a failed eval must not affect the app. */
public final class BundleEvalScheduler {

    private static final Logger LOG = LogManager.getLogger( BundleEvalScheduler.class );

    private final BundleEvalRunner.BundleRetriever retriever;
    private final Path corpusCsv;
    private final BundleEvalThresholds thresholds;
    private final BundleEvalRunDao dao;
    private final String configId;
    private final int precisionK;
    private final long intervalHours;
    private ScheduledExecutorService executor;

    public BundleEvalScheduler( final BundleEvalRunner.BundleRetriever retriever, final Path corpusCsv,
                                final BundleEvalThresholds thresholds, final BundleEvalRunDao dao,
                                final String configId, final int precisionK, final long intervalHours ) {
        this.retriever = retriever;
        this.corpusCsv = corpusCsv;
        this.thresholds = thresholds;
        this.dao = dao;
        this.configId = configId;
        this.precisionK = precisionK;
        this.intervalHours = intervalHours;
    }

    /** Starts the timer (no-op when {@code intervalHours <= 0}). First run is one interval out. */
    public void start() {
        if ( intervalHours <= 0 ) {
            LOG.info( "bundle-eval scheduler disabled (interval={}h)", intervalHours );
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-bundle-eval-scheduler" );
            t.setDaemon( true );
            return t;
        } );
        executor.scheduleAtFixedRate( this::runOnce, intervalHours, intervalHours, TimeUnit.HOURS );
        LOG.info( "bundle-eval scheduler started (every {}h, precisionK={})", intervalHours, precisionK );
    }

    /** One scheduled tick: load corpus → run → check → persist → alert. Never throws. */
    void runOnce() {
        try {
            final List< BundleEvalQuestion > corpus = BundleCorpusLoader.load( corpusCsv );
            if ( corpus.isEmpty() ) {
                LOG.warn( "bundle-eval corpus empty at {}; skipping run", corpusCsv );
                return;
            }
            final BundleEvalReport report = new BundleEvalRunner( retriever, precisionK ).run( corpus );
            final BundleEvalRegressionCheck.RegressionResult res =
                BundleEvalRegressionCheck.evaluate( report, thresholds );
            dao.insert( BundleEvalRun.from( report, configId, res.regression() ) );
            if ( res.regression() ) {
                LOG.warn( "BUNDLE-EVAL REGRESSION (configId={}, questions={}): {}",
                    configId, report.questionsScored(), res.detail() );
            } else {
                LOG.info( "bundle-eval ok (configId={}, overallRecall={}, questions={})",
                    configId, String.format( "%.3f", report.overallRecall() ), report.questionsScored() );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "bundle-eval run failed: {}", e.getMessage(), e );
        }
    }

    public void stop() {
        if ( executor != null ) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalSchedulerTest`
Expected: PASS (3 tests). If the oracle test regresses, the corpus loader or gold-accessor name differs — read `BundleEvalGateTest`/`BundleEvalQuestion` and match it; do not weaken the assertion.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalScheduler.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalSchedulerTest.java
git commit -m "feat(eval): BundleEvalScheduler (run/check/persist/alert, disabled by default)"
```

---

### Task 5: Wire the scheduler at the bundle-service derivation point + config key

**Files:**
- Modify: the post-startup point where the `BundleAssemblyService` becomes live and other schedulers start. **Read first:** `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` around `patchContextRetrievalService` (~line 1603–1609, where `BundleServiceWiring.build(...)` is called), and `OntologyWiringHelper.java:104-105` (how `OntologyRebuildScheduler` reads its interval and calls `.start()`). Mirror that wiring exactly. If a small `BundleEvalWiring` helper reads cleaner than inlining (it will), create `wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalWiring.java`.
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (document the new key, default 0).
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalWiringTest.java` (if a helper is created).

**Interfaces:**
- Consumes: the live `BundleAssemblyService`, a `DataSource`, `Properties`, the corpus path, and `slug → canonicalId` (already available at the `BundleServiceWiring.build` call site). Produces a started (or disabled) `BundleEvalScheduler`.
- Config: `wikantik.bundle.eval.interval.hours` (default `0` = disabled), `wikantik.bundle.eval.precision_k` (default `12`), `wikantik.bundle.eval.corpus` (default `eval/bundle-corpus/queries.csv`).

- [ ] **Step 1: Write the failing test** — a `BundleEvalWiring.build(...)` that returns a disabled scheduler when interval is 0/absent and a live one otherwise, mirroring `BundleServiceWiring`'s null-tolerant static build.

```java
/* <ASF license header> */
package com.wikantik.knowledge.eval;

import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.ContextBundle;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BundleEvalWiringTest {

    private static BundleAssemblyService stubBundle() {
        return new BundleAssemblyService() {
            @Override public ContextBundle assemble( final String q ) { return new ContextBundle( q, java.util.List.of(), null ); }
            @Override public ContextBundle assemble( final String q, final com.wikantik.api.bundle.RetrievalMode m ) { return assemble( q ); }
        };
    }

    @Test
    void build_nullBundleService_returnsNull() {
        assertNull( BundleEvalWiring.build( null, null, new Properties(), s -> java.util.Optional.empty() ) );
    }

    @Test
    void build_intervalZero_returnsDisabledScheduler_notNull() {
        // A scheduler is still constructed (so it can be stopped), just never scheduled.
        final Properties p = new Properties();  // interval defaults to 0
        final BundleEvalScheduler s = BundleEvalWiring.build( stubBundle(), null, p, slug -> java.util.Optional.of( "CID" ) );
        assertNotNull( s );
        s.start();  // must be a no-op, no throw
        s.stop();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalWiringTest`
Expected: FAIL — `BundleEvalWiring` does not exist.

- [ ] **Step 3: Write `BundleEvalWiring`** (null-tolerant static build, mirror `BundleServiceWiring.build`)

```java
/* <ASF license header> */
package com.wikantik.knowledge.eval;

import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.knowledge.bundle.BundleHarnessAdapter;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/** Single derivation point for the {@link BundleEvalScheduler}, mirroring {@code BundleServiceWiring}.
 *  Returns {@code null} when no bundle service is available; otherwise builds a scheduler that is
 *  started by the caller. Interval 0 (default) yields a scheduler whose {@code start()} is a no-op. */
public final class BundleEvalWiring {

    private static final Logger LOG = LogManager.getLogger( BundleEvalWiring.class );

    private BundleEvalWiring() {}

    public static BundleEvalScheduler build( final BundleAssemblyService bundleService, final DataSource dataSource,
                                             final Properties props, final Function< String, Optional< String > > slugToCanonicalId ) {
        if ( bundleService == null ) {
            LOG.debug( "bundle service not wired — bundle-eval scheduler unavailable" );
            return null;
        }
        final long interval = (long) TextUtil.getDoubleProperty( props, "wikantik.bundle.eval.interval.hours", 0 );
        final int precisionK = (int) TextUtil.getDoubleProperty( props, "wikantik.bundle.eval.precision_k", 12 );
        final String corpus = props == null ? "eval/bundle-corpus/queries.csv"
            : props.getProperty( "wikantik.bundle.eval.corpus", "eval/bundle-corpus/queries.csv" );
        final BundleEvalThresholds thresholds = loadThresholds( corpus );
        final String configId = props == null ? "unknown"
            : props.getProperty( "wikantik.applicationName", "wikantik" );
        return new BundleEvalScheduler(
            new BundleHarnessAdapter( bundleService ), Path.of( corpus ),
            thresholds, new BundleEvalRunDao( dataSource )::insert, configId, precisionK, interval );
        // NB: the 4th arg is a Consumer< BundleEvalRun > (the Task-4 write seam); the DAO stays final
        // and we pass its ::insert method reference, not the DAO itself.
    }

    /** Loads thresholds from the sibling thresholds.properties next to the corpus; floors default to 0.0. */
    private static BundleEvalThresholds loadThresholds( final String corpusCsv ) {
        final Path p = Path.of( corpusCsv ).resolveSibling( "thresholds.properties" );
        final Properties t = new Properties();
        try ( var in = java.nio.file.Files.newInputStream( p ) ) {
            t.load( in );
        } catch ( final java.io.IOException e ) {
            LOG.warn( "bundle-eval thresholds not loaded from {} ({}); using 0.0 floors", p, e.getMessage() );
        }
        return BundleEvalThresholds.fromProperties( t );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalWiringTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Call the wiring at the bundle-service derivation point.** In `WikiEngine.patchContextRetrievalService`, the built bundle service is the local `bundleSvc` (verify by reading the method). A `DataSource` is NOT in scope there — resolve it via JNDI exactly as `WikiEngine.java:983` and `:1225` already do (`(DataSource) new InitialContext().lookup( <the same JNDI name those lines use> )`), fail-soft to null. The `slug → canonicalId` function comes from `pageCanonicalIdsDao()` (the same DAO `BundleServiceWiring.build` is handed). Add, right after `bundleSvc` is built (before or after the `KnowledgeSubsystem.Services` rebuild — it only needs `bundleSvc`, `properties`, and the resolved collaborators):

```java
        // Scheduled retrieval-quality eval (disabled unless wikantik.bundle.eval.interval.hours > 0).
        javax.sql.DataSource evalDs = null;
        try {
            evalDs = ( javax.sql.DataSource ) new javax.naming.InitialContext().lookup( datasource );
        } catch ( final javax.naming.NamingException e ) {
            LOG.warn( "bundle-eval scheduler: no JNDI DataSource ({}); eval persistence disabled", e.getMessage() );
        }
        final java.util.function.Function< String, java.util.Optional< String > > evalCanonicalId =
            slug -> pageCanonicalIdsDao() == null ? java.util.Optional.empty()
                  : pageCanonicalIdsDao().findBySlug( slug )
                        .map( com.wikantik.pagegraph.spine.PageCanonicalIdsDao.Row::canonicalId );
        final com.wikantik.knowledge.eval.BundleEvalScheduler bundleEvalScheduler =
            com.wikantik.knowledge.eval.BundleEvalWiring.build( bundleSvc, evalDs, properties, evalCanonicalId );
        if ( bundleEvalScheduler != null ) {
            bundleEvalScheduler.start();
        }
```

`datasource` is the JNDI-name variable/field used at `WikiEngine.java:983`/`:1225` — read those lines and reuse exactly what they reference (do not invent a name). `LOG` and `properties` are existing members of `WikiEngine`. If `bundleSvc`, `pageCanonicalIdsDao()`, `properties`, or the `datasource` name genuinely isn't reachable at this seam, STOP and report BLOCKED rather than fabricating.

- [ ] **Step 6: Document the config keys** (commented / off) in `wikantik.properties`, after the `wikantik.bundle.rerank.*` block from the reranking work:

```properties
# --- Scheduled retrieval-quality eval (RetrievalEvaluationObservability Phase 1) ---
# Interval in hours between scheduled bundle-eval runs against eval/bundle-corpus.
# 0 = DISABLED (default) — a real run needs a live embedding index, so enable only in a
# deployment that has one. Each run persists recall@12 to bundle_eval_run and logs a
# regression WARN when any recall falls below its threshold floor (thresholds.properties).
wikantik.bundle.eval.interval.hours = 0
# wikantik.bundle.eval.precision_k = 12
# wikantik.bundle.eval.corpus = eval/bundle-corpus/queries.csv
```

- [ ] **Step 7: Run focused tests + confirm the module still compiles**

Run: `mvn test -pl wikantik-main -Dtest=BundleEvalWiringTest,BundleServiceWiringTest`
Expected: PASS. Then `mvn -q -o compile -pl wikantik-main` (or a focused build) to confirm the `WikiEngine` edit compiles.

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/eval/BundleEvalWiring.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalWiringTest.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "feat(eval): wire BundleEvalScheduler at bundle-service startup (off by default)"
```

---

### Task 6: Runbook + full-suite verification

**Files:**
- Create: `docs/agents/bundle-eval-runbook.md`
- Modify: `eval/bundle-corpus/baseline-notes.md` (point to the runbook + note the scheduled table)

**Interfaces:** none (docs). No behavior change.

- [ ] **Step 1: Write the runbook** — `docs/agents/bundle-eval-runbook.md`:

```markdown
# Bundle-eval scheduled run — runbook

The `BundleEvalScheduler` (off by default) runs `eval/bundle-corpus/queries.csv` against the live
bundle path every `wikantik.bundle.eval.interval.hours` and writes one `bundle_eval_run` row per run.

## Enable it
Set `wikantik.bundle.eval.interval.hours` > 0 in a deployment that has a live embedding index
(prod / a local Tomcat with the dense index built). It stays disabled in CI and fresh installs.

## Read a result row
`SELECT run_at, config_id, overall_recall, recall_similarity, recall_relational, recall_boundary,
questions_scored, regression FROM bundle_eval_run ORDER BY run_at DESC LIMIT 10;`

- `regression = true` means overall or a per-category recall fell STRICTLY below its floor in
  `eval/bundle-corpus/thresholds.properties`. The scheduler also logged a `BUNDLE-EVAL REGRESSION`
  WARN naming the breached floor(s).
- `regression = false` with recall near the floor is a warning sign; compare to the baseline in
  `eval/bundle-corpus/baseline-notes.md`.

## Respond to a regression
1. What changed since the last non-regressed `run_at`? A retrieval config flip
   (`wikantik.bundle.rerank.chain`, dense backend, chunker), a re-index, or a corpus edit.
2. Reproduce with the manual measurement in `baseline-notes.md`.
3. If a config change caused it, revert or re-measure; if the corpus/index is the cause, fix and
   re-run. Ratchet the floors UP in `thresholds.properties` only after a confirmed improvement — never
   silently down.
```

- [ ] **Step 2: Point baseline-notes at the runbook** — append to `eval/bundle-corpus/baseline-notes.md`:

```markdown
## Scheduled runs (Phase 1, 2026-07-10)

A scheduled `BundleEvalScheduler` now persists recall@12 to the `bundle_eval_run` table and logs a
regression WARN below the `thresholds.properties` floors. Off by default
(`wikantik.bundle.eval.interval.hours = 0`); enable in a deployment with a live index. See
`docs/agents/bundle-eval-runbook.md`.
```

- [ ] **Step 3: Full module unit suite** — prove nothing regressed and the scheduler defaults off.

Run: `mvn test -pl wikantik-main`
Expected: BUILD SUCCESS; quote the aggregate `Tests run: N, Failures: 0, Errors: 0`. The scheduler being disabled by default means no existing test spins up a scheduled run.

- [ ] **Step 4: Commit**

```bash
git add docs/agents/bundle-eval-runbook.md eval/bundle-corpus/baseline-notes.md
git commit -m "docs(eval): bundle-eval scheduled-run runbook + baseline pointer"
```

---

## Acceptance criteria mapping (from RoadmapRetrievalEvaluationObservability, Phase 1)

| Brief acceptance gate | Covered by |
|---|---|
| 1. Scheduled run executes harness + persists recall@12 (overall + per-category) with timestamp + config id | Tasks 1 (table + record, `run_at` default), 3 (DAO), 4 (scheduler `runOnce`), 5 (wiring + interval) |
| 2. Induced regression fires the alert; a normal run does not | Task 2 (pure check unit tests) + Task 4 (`runOnce` oracle→no-regression, empty→regression, both persisted) |
| 3. Runbook documents how to read a row and respond | Task 6 |
| 4. Results-table migration re-applies as a no-op | Task 1 (`CREATE TABLE/INDEX IF NOT EXISTS`); real double-apply is a manual/IT check (documented) |

**Honest scope note:** every gate above is proven by hermetic unit tests EXCEPT the live recall number (which needs a live embedding index and runs on the schedule in a deployment, not CI — mirroring `BundleEvalGateTest`'s oracle-vs-real-corpus split) and the migration's real-PG double-apply (manual/IT). Dashboard (admin UI) and OpenTelemetry traces are later phases, out of scope here.

## Self-Review

- **Spec coverage:** scheduled run (Task 4+5), persistence table+DAO (Tasks 1,3), regression alert (Tasks 2,4), runbook (Task 6), migration idempotency (Task 1). First-session scope of the brief ("scheduled harness + results table + regression alert, config not new code of substance") matches — the only genuinely new logic is the pure regression check; everything else is wiring/persistence over the existing harness. Non-goals respected (no dashboard, no OTel).
- **Placeholder scan:** none — every code step is complete. The one deliberately-adaptive step is Task 5 Step 5 (`WikiEngine` variable names), which instructs reading the real seam and reporting BLOCKED rather than fabricating.
- **Type consistency:** `BundleEvalReport` accessors (`overallRecall()`, `overallPrecisionAtK()`, `recallByCategory()`, `questionsScored()`) used identically across Tasks 1/2/4; `BundleEvalRun.from(report, configId, regression)` signature consistent between Tasks 1, 4; `BundleEvalRunner.BundleRetriever` (Function<String,List<BundleSection>>) used in Tasks 4/5; `BundleHarnessAdapter(BundleAssemblyService)` (existing) consumed in Task 5; `BundleEvalRunDao.insert(BundleEvalRun)` consistent Tasks 3/4; `BundleEvalThresholds.fromProperties` / `BundleEvalRegressionCheck.evaluate` consistent Tasks 2/4/5.
