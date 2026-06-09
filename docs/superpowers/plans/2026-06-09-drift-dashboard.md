# Drift Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corpus-wide frontmatter + SHACL drift sweep, persisted as aggregate snapshots, surfaced on an `/admin/drift` burn-down dashboard.

**Architecture:** A `DriftSweepService` (wikantik-main, new `com.wikantik.drift` package) runs the existing `SchemaDrivenFrontmatterValidator` over every page and counts SHACL violations from the ontology snapshot, persisting `(family, code, severity, count)` rows per sweep into two new tables (V038). It is triggered by a new post-rebuild hook on `OntologyRebuildCoordinator` and by `POST /admin/drift/sweep`. `AdminDriftResource` serves summary/trend/live-page-list; `AdminDriftPage.jsx` renders them.

**Tech Stack:** Java 21, JDBC (PostgreSQL prod / H2 tests), JUnit 5 + Mockito, Gson, React + vitest, existing `Sparkline` component.

**Spec:** `docs/superpowers/specs/2026-06-09-drift-dashboard-design.md`. One deliberate deviation: the whole-block YAML parse failure is counted under code **`yaml.parse`** (the code the save path already emits in `SchemaValidationPageFilter`), not `yaml.malformed` as the spec draft said — same code everywhere, Task 12 updates the spec line.

**Context for implementers (read once):**
- `FieldViolation` is a record: `(String field, Severity severity, String code, String message, String suggestion)` in `com.wikantik.api.frontmatter.schema` (wikantik-api). `Severity` is `ERROR | WARNING`.
- `SchemaDrivenFrontmatterValidator.validate(Map<String,Object> metadata, ValidationCtx ctx)` returns `List<FieldViolation>`. `ValidationCtx` is a record `(Predicate<String> pageResolves, Predicate<String> isTrustedAuthor, Severity nonCanonicalEnumSeverity)` with a `ValidationCtx.lenient()` factory. Both in `com.wikantik.frontmatter.schema` (wikantik-main).
- Page enumeration: `PageManager.getAllPages()` (throws `ProviderException`), text via `PageManager.getPureText(Page)`, name via `Page.getName()`.
- Frontmatter parse: `FrontmatterParser.parseStrict(text)` → `ParsedPage.metadata()`; throws `FrontmatterParseException`. Only texts starting with `---\n` / `---\r\n` have frontmatter.
- SHACL: `new OntologyShaclValidator().validate(modelManager.inferenceSnapshot())` → `List<Violation>` where `Violation` is record `(String focusNode, String path, String message)` (wikantik-ontology).
- `OntologyRebuildCoordinator` (wikantik-main `com.wikantik.ontology.runtime`): rebuild runs on a daemon thread in `runRebuild()`; `modelManager()` exposes the TDB2 manager. Built and registered in `OntologyWiringHelper.wireOntology(engine, props, dataSource, pageManager, filterManager)` (currently `void`), called from `WikiEngine.java:1573`.
- REST: admin resources extend `RestServletBase` (`extractPathParam(req)`, `sendError`, `sendNotFound`, `getSubsystems()`, protected static `GSON`). Admin auth is `AdminAuthFilter` on `/admin/*` — resources do not gate themselves. Wire pattern: `getSubsystems().pageGraph().<service>()` backed by `PageGraphSubsystem.Services` record + `PageGraphSubsystemFactory.create()` (`engine.getManager(...)`).
- SPA-vs-API on `/admin/*`: `SpaRoutingFilter` (wikantik-rest) forwards only browser navigations (`Accept: text/html`); JSON fetches reach servlets. No filter change needed for `/admin/drift`.
- Frontend: API calls go in `src/api/client.js` under `api.admin.*` using `request(path, options)`. Admin pages live in `src/components/admin/`, routed as children of `<Route path="/admin" element={<AdminLayout />}>` in `src/main.jsx` (lazy import), with a link entry in `AdminSidebar.jsx`. `Sparkline.jsx` takes `{ values, width=120, height=24, stroke }`.
- H2 DAO tests: `org.h2.jdbcx.JdbcDataSource`, URL `jdbc:h2:mem:<name>;MODE=PostgreSQL;DB_CLOSE_DELAY=-1`, create tables inline in `@BeforeEach` (see `PageCanonicalIdsDaoTest`).
- Build hygiene: compile-check one module with `mvn compile -pl <module> -q`; after record/constructor changes also `mvn test-compile -pl <module> -q`. Single test: `mvn test -pl <module> -Dtest=Class`. wikantik-rest resolves wikantik-main from `~/.m2`: after changing wikantik-main, run `mvn install -pl wikantik-main -DskipTests -q` before testing wikantik-rest. Never run ITs with `-T`.
- Commits: stage exact files by name (never `git add -A`); 1–3 line messages ending with the `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` trailer.

---

### Task 1: Migration V038

**Files:**
- Create: `bin/db/migrations/V038__drift_snapshots.sql`

- [ ] **Step 1: Write the migration** (idempotent DDL, `:app_user` grants, per `bin/db/migrations/README.md`)

```sql
-- V038: drift dashboard aggregate snapshots (drift_sweeps + drift_snapshot_counts).
-- Column is triggered_by, not "trigger" — TRIGGER is a keyword in H2 (unit tests).

CREATE TABLE IF NOT EXISTS drift_sweeps (
    id            BIGSERIAL PRIMARY KEY,
    swept_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    pages_scanned INT         NOT NULL,
    duration_ms   BIGINT      NOT NULL,
    triggered_by  TEXT        NOT NULL,
    shacl_checked BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS drift_snapshot_counts (
    sweep_id  BIGINT NOT NULL REFERENCES drift_sweeps(id) ON DELETE CASCADE,
    family    TEXT   NOT NULL,
    code      TEXT   NOT NULL,
    severity  TEXT   NOT NULL,
    count     INT    NOT NULL,
    PRIMARY KEY (sweep_id, family, code, severity)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON drift_sweeps, drift_snapshot_counts TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE drift_sweeps_id_seq TO :app_user;
```

- [ ] **Step 2: Sanity-check numbering and idempotency**

Run: `ls bin/db/migrations/ | tail -3` — V038 must be the next number (V037 is current latest). Every statement uses `IF NOT EXISTS` / `ON CONFLICT`-free grant forms, so re-application is a no-op. `bin/deploy-local.sh` applies it on next deploy.

- [ ] **Step 3: Commit**

```bash
git add bin/db/migrations/V038__drift_snapshots.sql
git commit -m "feat(drift): V038 drift snapshot tables"
```

---

### Task 2: DTOs + DriftSnapshotRepository (H2 TDD)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/drift/DriftCount.java`
- Create: `wikantik-main/src/main/java/com/wikantik/drift/DriftSweepRecord.java`
- Create: `wikantik-main/src/main/java/com/wikantik/drift/PageViolation.java`
- Create: `wikantik-main/src/main/java/com/wikantik/drift/DriftSnapshotRepository.java`
- Test: `wikantik-main/src/test/java/com/wikantik/drift/DriftSnapshotRepositoryTest.java`

All new Java files carry the standard Apache license header (copy from any neighbor).

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.drift;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DriftSnapshotRepositoryTest {

    private DataSource ds;
    private DriftSnapshotRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:drift" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE drift_sweeps (
                    id            BIGSERIAL PRIMARY KEY,
                    swept_at      TIMESTAMP WITH TIME ZONE NOT NULL,
                    pages_scanned INT     NOT NULL,
                    duration_ms   BIGINT  NOT NULL,
                    triggered_by  TEXT    NOT NULL,
                    shacl_checked BOOLEAN NOT NULL DEFAULT TRUE
                )""" );
            s.executeUpdate( """
                CREATE TABLE drift_snapshot_counts (
                    sweep_id  BIGINT NOT NULL REFERENCES drift_sweeps(id) ON DELETE CASCADE,
                    family    TEXT   NOT NULL,
                    code      TEXT   NOT NULL,
                    severity  TEXT   NOT NULL,
                    count     INT    NOT NULL,
                    PRIMARY KEY (sweep_id, family, code, severity)
                )""" );
        }
        this.repo = new DriftSnapshotRepository( ds );
    }

    @Test
    void insertAndReadBackLatest() {
        final long id = repo.insertSweep( Instant.now(), 42, 1234L, "manual", true,
                List.of( new DriftCount( "frontmatter", "status.noncanonical", "WARNING", 7 ),
                         new DriftCount( "shacl", "implements", "ERROR", 2 ) ) );
        assertTrue( id > 0 );

        final Optional< DriftSweepRecord > latest = repo.latest();
        assertTrue( latest.isPresent() );
        assertEquals( 42, latest.get().pagesScanned() );
        assertEquals( "manual", latest.get().triggeredBy() );
        assertTrue( latest.get().shaclChecked() );
        assertEquals( 2, latest.get().counts().size() );
        assertTrue( latest.get().counts().contains(
                new DriftCount( "frontmatter", "status.noncanonical", "WARNING", 7 ) ) );
    }

    @Test
    void latestIsEmptyBeforeFirstSweep() {
        assertTrue( repo.latest().isEmpty() );
    }

    @Test
    void previousBeforeReturnsTheSweepJustBefore() {
        final long first = repo.insertSweep( Instant.now().minus( 1, ChronoUnit.DAYS ), 10, 1L,
                "scheduled", true, List.of( new DriftCount( "frontmatter", "x", "WARNING", 5 ) ) );
        final long second = repo.insertSweep( Instant.now(), 11, 1L,
                "scheduled", true, List.of( new DriftCount( "frontmatter", "x", "WARNING", 3 ) ) );

        final Optional< DriftSweepRecord > prev = repo.previousBefore( second );
        assertTrue( prev.isPresent() );
        assertEquals( first, prev.get().id() );
        assertEquals( 5, prev.get().counts().get( 0 ).count() );
        assertTrue( repo.previousBefore( first ).isEmpty() );
    }

    @Test
    void trendReturnsWindowAscending() {
        repo.insertSweep( Instant.now().minus( 40, ChronoUnit.DAYS ), 1, 1L, "scheduled", true, List.of() );
        final long recentOld = repo.insertSweep( Instant.now().minus( 5, ChronoUnit.DAYS ), 2, 1L,
                "scheduled", true, List.of() );
        final long newest = repo.insertSweep( Instant.now(), 3, 1L, "manual", false, List.of() );

        final List< DriftSweepRecord > trend = repo.trend( 30 );
        assertEquals( 2, trend.size() );
        assertEquals( recentOld, trend.get( 0 ).id() );
        assertEquals( newest, trend.get( 1 ).id() );
        assertFalse( trend.get( 1 ).shaclChecked() );
    }

    @Test
    void emptyCountListPersistsSweepRowOnly() {
        final long id = repo.insertSweep( Instant.now(), 0, 0L, "manual", false, List.of() );
        assertEquals( id, repo.latest().orElseThrow().id() );
        assertTrue( repo.latest().orElseThrow().counts().isEmpty() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=DriftSnapshotRepositoryTest -q`
Expected: COMPILATION ERROR — `DriftSnapshotRepository`, `DriftCount`, `DriftSweepRecord` do not exist.

- [ ] **Step 3: Implement the DTOs and repository**

`DriftCount.java`:

```java
package com.wikantik.drift;

/** One aggregate snapshot row: how many violations of one code at one severity a sweep found. */
public record DriftCount( String family, String code, String severity, int count ) {}
```

`DriftSweepRecord.java`:

```java
package com.wikantik.drift;

import java.time.Instant;
import java.util.List;

/** A persisted sweep with its aggregate counts. */
public record DriftSweepRecord(
        long id,
        Instant sweptAt,
        int pagesScanned,
        long durationMs,
        String triggeredBy,
        boolean shaclChecked,
        List< DriftCount > counts
) {}
```

`PageViolation.java`:

```java
package com.wikantik.drift;

/** One live (non-persisted) offender for the per-code drill-down. */
public record PageViolation(
        String pageName,
        String field,
        String severity,
        String code,
        String message,
        String suggestion
) {}
```

`DriftSnapshotRepository.java`:

```java
package com.wikantik.drift;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for drift sweep snapshots (V038: drift_sweeps + drift_snapshot_counts).
 * Inserts are transactional: a failed sweep persists nothing.
 */
public final class DriftSnapshotRepository {

    private static final Logger LOG = LogManager.getLogger( DriftSnapshotRepository.class );

    private final DataSource dataSource;

    public DriftSnapshotRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    /** Persists one sweep + its counts atomically; returns the new sweep id. */
    public long insertSweep( final Instant sweptAt, final int pagesScanned, final long durationMs,
                             final String triggeredBy, final boolean shaclChecked,
                             final List< DriftCount > counts ) {
        try ( Connection conn = dataSource.getConnection() ) {
            final boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit( false );
            try {
                final long sweepId;
                try ( PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO drift_sweeps ( swept_at, pages_scanned, duration_ms, triggered_by, shacl_checked ) "
                      + "VALUES ( ?, ?, ?, ?, ? )", Statement.RETURN_GENERATED_KEYS ) ) {
                    ps.setTimestamp( 1, Timestamp.from( sweptAt ) );
                    ps.setInt( 2, pagesScanned );
                    ps.setLong( 3, durationMs );
                    ps.setString( 4, triggeredBy );
                    ps.setBoolean( 5, shaclChecked );
                    ps.executeUpdate();
                    try ( ResultSet keys = ps.getGeneratedKeys() ) {
                        if ( !keys.next() ) {
                            throw new SQLException( "no generated key for drift_sweeps insert" );
                        }
                        sweepId = keys.getLong( 1 );
                    }
                }
                try ( PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO drift_snapshot_counts ( sweep_id, family, code, severity, count ) "
                      + "VALUES ( ?, ?, ?, ?, ? )" ) ) {
                    for ( final DriftCount c : counts ) {
                        ps.setLong( 1, sweepId );
                        ps.setString( 2, c.family() );
                        ps.setString( 3, c.code() );
                        ps.setString( 4, c.severity() );
                        ps.setInt( 5, c.count() );
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                return sweepId;
            } catch ( final SQLException e ) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit( prevAutoCommit );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to persist drift sweep (triggeredBy={}): {}", triggeredBy, e.getMessage(), e );
            throw new IllegalStateException( "drift sweep persistence failed", e );
        }
    }

    /** The most recent sweep, with counts. */
    public Optional< DriftSweepRecord > latest() {
        return querySingle( "SELECT id, swept_at, pages_scanned, duration_ms, triggered_by, shacl_checked "
                          + "FROM drift_sweeps ORDER BY id DESC LIMIT 1", ps -> {} );
    }

    /** The sweep immediately before {@code sweepId} (for deltas). */
    public Optional< DriftSweepRecord > previousBefore( final long sweepId ) {
        return querySingle( "SELECT id, swept_at, pages_scanned, duration_ms, triggered_by, shacl_checked "
                          + "FROM drift_sweeps WHERE id < ? ORDER BY id DESC LIMIT 1",
                ps -> ps.setLong( 1, sweepId ) );
    }

    /** All sweeps in the last {@code days}, oldest first, with counts. */
    public List< DriftSweepRecord > trend( final int days ) {
        final Timestamp cutoff = Timestamp.from( Instant.now().minus( days, ChronoUnit.DAYS ) );
        final List< DriftSweepRecord > out = new ArrayList<>();
        final String sql = "SELECT id, swept_at, pages_scanned, duration_ms, triggered_by, shacl_checked "
                         + "FROM drift_sweeps WHERE swept_at >= ? ORDER BY id ASC";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setTimestamp( 1, cutoff );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    out.add( rowToRecord( conn, rs ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read drift trend ({} days): {}", days, e.getMessage(), e );
            throw new IllegalStateException( "drift trend query failed", e );
        }
        return out;
    }

    @FunctionalInterface
    private interface Binder { void bind( PreparedStatement ps ) throws SQLException; }

    private Optional< DriftSweepRecord > querySingle( final String sql, final Binder binder ) {
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            binder.bind( ps );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) {
                    return Optional.empty();
                }
                return Optional.of( rowToRecord( conn, rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read drift sweep: {}", e.getMessage(), e );
            throw new IllegalStateException( "drift sweep query failed", e );
        }
    }

    private DriftSweepRecord rowToRecord( final Connection conn, final ResultSet rs ) throws SQLException {
        final long id = rs.getLong( "id" );
        return new DriftSweepRecord(
                id,
                rs.getTimestamp( "swept_at" ).toInstant(),
                rs.getInt( "pages_scanned" ),
                rs.getLong( "duration_ms" ),
                rs.getString( "triggered_by" ),
                rs.getBoolean( "shacl_checked" ),
                countsFor( conn, id ) );
    }

    private List< DriftCount > countsFor( final Connection conn, final long sweepId ) throws SQLException {
        final List< DriftCount > counts = new ArrayList<>();
        try ( PreparedStatement ps = conn.prepareStatement(
                "SELECT family, code, severity, count FROM drift_snapshot_counts "
              + "WHERE sweep_id = ? ORDER BY family, code, severity" ) ) {
            ps.setLong( 1, sweepId );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    counts.add( new DriftCount( rs.getString( "family" ), rs.getString( "code" ),
                            rs.getString( "severity" ), rs.getInt( "count" ) ) );
                }
            }
        }
        return counts;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=DriftSnapshotRepositoryTest -q`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/drift/DriftCount.java \
        wikantik-main/src/main/java/com/wikantik/drift/DriftSweepRecord.java \
        wikantik-main/src/main/java/com/wikantik/drift/PageViolation.java \
        wikantik-main/src/main/java/com/wikantik/drift/DriftSnapshotRepository.java \
        wikantik-main/src/test/java/com/wikantik/drift/DriftSnapshotRepositoryTest.java
git commit -m "feat(drift): snapshot DTOs + JDBC repository (H2-tested)"
```

---

### Task 3: DriftSweepService — the sweep

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/drift/DriftSweepService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/drift/DriftSweepServiceTest.java`

Uses the **real** validator with the default schema + `ValidationCtx.lenient()` so the test exercises true violation codes; PageManager and repository are Mockito mocks.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.drift;

import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.managers.PageManager;
import com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator;
import com.wikantik.frontmatter.schema.ValidationCtx;
import com.wikantik.ontology.OntologyShaclValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DriftSweepServiceTest {

    private static Page page( final String name ) {
        final Page p = Mockito.mock( Page.class );
        when( p.getName() ).thenReturn( name );
        return p;
    }

    private static PageManager pm( final Object... namesAndTexts ) throws Exception {
        final PageManager pm = Mockito.mock( PageManager.class );
        final java.util.List< Page > pages = new java.util.ArrayList<>();
        for ( int i = 0; i < namesAndTexts.length; i += 2 ) {
            final Page p = page( ( String ) namesAndTexts[ i ] );
            when( pm.getPureText( p ) ).thenReturn( ( String ) namesAndTexts[ i + 1 ] );
            pages.add( p );
        }
        Mockito.doReturn( pages ).when( pm ).getAllPages();
        return pm;
    }

    private static DriftSweepService service( final PageManager pm,
            final DriftSnapshotRepository repo,
            final java.util.function.Supplier< List< OntologyShaclValidator.Violation > > shacl ) {
        return new DriftSweepService( pm,
                new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() ),
                ValidationCtx.lenient(), shacl, repo );
    }

    @Test
    void sweepAggregatesFrontmatterViolationsByCode() throws Exception {
        // Two pages with a non-canonical status (warning each), one clean, one without frontmatter.
        final PageManager pm = pm(
                "Drifty1", "---\ntype: article\nstatus: bogus-state\n---\n\nbody",
                "Drifty2", "---\ntype: article\nstatus: bogus-state\n---\n\nbody",
                "Clean",   "---\ntype: article\nstatus: active\n---\n\nbody",
                "NoFm",    "Just a body, no frontmatter." );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService svc = service( pm, repo, null );
        final DriftSweepService.SweepOutcome outcome = svc.runSweep( "manual" );

        assertEquals( 4, outcome.pagesScanned() );
        assertFalse( outcome.shaclChecked() );

        @SuppressWarnings( "unchecked" )
        final ArgumentCaptor< List< DriftCount > > counts = ArgumentCaptor.forClass( List.class );
        verify( repo ).insertSweep( any(), eq( 4 ), anyLong(), eq( "manual" ), eq( false ),
                counts.capture() );
        final DriftCount statusDrift = counts.getValue().stream()
                .filter( c -> "status.noncanonical".equals( c.code() ) )
                .findFirst().orElseThrow();
        assertEquals( "frontmatter", statusDrift.family() );
        assertEquals( "WARNING", statusDrift.severity() );
        assertEquals( 2, statusDrift.count() );
    }

    @Test
    void malformedYamlIsCountedNotFatal() throws Exception {
        final PageManager pm = pm(
                "Broken", "---\ntitle: foo: bar: baz\n  bad indent\n---\n\nbody",
                "Clean",  "---\ntype: article\nstatus: active\n---\n\nbody" );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService.SweepOutcome outcome = service( pm, repo, null ).runSweep( "manual" );

        assertEquals( 2, outcome.pagesScanned() );
        final DriftCount yaml = outcome.counts().stream()
                .filter( c -> "yaml.parse".equals( c.code() ) ).findFirst().orElseThrow();
        assertEquals( "ERROR", yaml.severity() );
        assertEquals( 1, yaml.count() );
    }

    @Test
    void unreadablePageIsSkippedAndNotScanned() throws Exception {
        final PageManager pm = Mockito.mock( PageManager.class );
        final Page good = page( "Good" );
        final Page bad = page( "Bad" );
        when( pm.getPureText( good ) ).thenReturn( "---\ntype: article\nstatus: active\n---\n\nbody" );
        when( pm.getPureText( bad ) ).thenThrow( new RuntimeException( "provider exploded" ) );
        Mockito.doReturn( List.of( good, bad ) ).when( pm ).getAllPages();
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService.SweepOutcome outcome = service( pm, repo, null ).runSweep( "manual" );
        assertEquals( 1, outcome.pagesScanned() );
    }

    @Test
    void shaclViolationsCountedByPath() throws Exception {
        final PageManager pm = pm( "Clean", "---\ntype: article\nstatus: active\n---\n\nbody" );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );
        final List< OntologyShaclValidator.Violation > violations = List.of(
                new OntologyShaclValidator.Violation( "wk:e1", "wk:implements", "subject must be technology" ),
                new OntologyShaclValidator.Violation( "wk:e2", "wk:implements", "subject must be technology" ),
                new OntologyShaclValidator.Violation( "wk:e3", "wk:located_in", "object must be place" ) );

        final DriftSweepService.SweepOutcome outcome =
                service( pm, repo, () -> violations ).runSweep( "scheduled" );

        assertTrue( outcome.shaclChecked() );
        assertEquals( 2, outcome.counts().stream()
                .filter( c -> "shacl".equals( c.family() ) && "wk:implements".equals( c.code() ) )
                .findFirst().orElseThrow().count() );
        assertEquals( 1, outcome.counts().stream()
                .filter( c -> "shacl".equals( c.family() ) && "wk:located_in".equals( c.code() ) )
                .findFirst().orElseThrow().count() );
    }

    @Test
    void shaclSourceFailureDegradesToUnchecked() throws Exception {
        final PageManager pm = pm( "Clean", "---\ntype: article\nstatus: active\n---\n\nbody" );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService.SweepOutcome outcome = service( pm, repo,
                () -> { throw new IllegalStateException( "tdb2 gone" ); } ).runSweep( "scheduled" );

        assertFalse( outcome.shaclChecked() );
        verify( repo ).insertSweep( any(), anyInt(), anyLong(), anyString(), eq( false ), anyList() );
    }

    @Test
    void concurrentSweepIsRefused() throws Exception {
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final PageManager pm = Mockito.mock( PageManager.class );
        final Page slow = page( "Slow" );
        when( pm.getPureText( slow ) ).thenAnswer( inv -> {
            entered.countDown();
            release.await();
            return "---\ntype: article\n---\n\nbody";
        } );
        Mockito.doReturn( List.of( slow ) ).when( pm ).getAllPages();
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService svc = service( pm, repo, null );
        final AtomicReference< Throwable > worker = new AtomicReference<>();
        final Thread t = new Thread( () -> {
            try { svc.runSweep( "manual" ); } catch ( final Throwable e ) { worker.set( e ); }
        } );
        t.start();
        entered.await();
        assertTrue( svc.isRunning() );
        assertThrows( DriftSweepService.SweepAlreadyRunningException.class,
                () -> svc.runSweep( "manual" ) );
        release.countDown();
        t.join();
        assertNull( worker.get() );
        assertFalse( svc.isRunning() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=DriftSweepServiceTest -q`
Expected: COMPILATION ERROR — `DriftSweepService` does not exist.

- [ ] **Step 3: Implement the service**

```java
package com.wikantik.drift;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.managers.PageManager;
import com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator;
import com.wikantik.frontmatter.schema.ValidationCtx;
import com.wikantik.ontology.OntologyShaclValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Corpus-wide drift sweep: runs the schema validator over every page and counts SHACL
 * conformance violations, persisting aggregate {@code (family, code, severity, count)}
 * snapshots via {@link DriftSnapshotRepository}. Single-flight: one sweep at a time.
 *
 * <p>Family {@code frontmatter} uses the validator's violation codes plus {@code yaml.parse}
 * for whole-block parse failures (same code the save path emits). Family {@code shacl} uses
 * the violated shape's property path as the code, severity {@code ERROR}.</p>
 */
public final class DriftSweepService {

    private static final Logger LOG = LogManager.getLogger( DriftSweepService.class );

    /** A sweep was requested while another is in flight. */
    public static final class SweepAlreadyRunningException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public SweepAlreadyRunningException() { super( "a drift sweep is already running" ); }
    }

    /** What one completed sweep produced (already persisted). */
    public record SweepOutcome( long sweepId, int pagesScanned, long durationMs,
                                boolean shaclChecked, List< DriftCount > counts ) {}

    private record CountKey( String family, String code, String severity ) {}

    private final PageManager pageManager;
    private final SchemaDrivenFrontmatterValidator validator;
    private final ValidationCtx ctx;
    /** Null when the ontology subsystem is disabled — the shacl family is then skipped. */
    private final Supplier< List< OntologyShaclValidator.Violation > > shaclSource;
    private final DriftSnapshotRepository repository;
    private final AtomicBoolean running = new AtomicBoolean( false );

    public DriftSweepService( final PageManager pageManager,
                              final SchemaDrivenFrontmatterValidator validator,
                              final ValidationCtx ctx,
                              final Supplier< List< OntologyShaclValidator.Violation > > shaclSource,
                              final DriftSnapshotRepository repository ) {
        this.pageManager = pageManager;
        this.validator = validator;
        this.ctx = ctx;
        this.shaclSource = shaclSource;
        this.repository = repository;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Runs one sweep synchronously and persists it. */
    public SweepOutcome runSweep( final String triggeredBy ) {
        if ( !running.compareAndSet( false, true ) ) {
            throw new SweepAlreadyRunningException();
        }
        try {
            final long startedAt = System.currentTimeMillis();
            final Map< CountKey, Integer > counts = new LinkedHashMap<>();
            final int pagesScanned = sweepFrontmatter( counts );
            final boolean shaclChecked = sweepShacl( counts );

            final long durationMs = System.currentTimeMillis() - startedAt;
            final List< DriftCount > rows = counts.entrySet().stream()
                    .map( e -> new DriftCount( e.getKey().family(), e.getKey().code(),
                            e.getKey().severity(), e.getValue() ) )
                    .toList();
            final long sweepId = repository.insertSweep(
                    Instant.now(), pagesScanned, durationMs, triggeredBy, shaclChecked, rows );
            LOG.info( "drift sweep complete (id={}, trigger={}, pages={}, codes={}, shacl={})",
                    sweepId, triggeredBy, pagesScanned, rows.size(), shaclChecked );
            return new SweepOutcome( sweepId, pagesScanned, durationMs, shaclChecked, rows );
        } finally {
            running.set( false );
        }
    }

    /** Starts a sweep on a daemon thread; throws {@link SweepAlreadyRunningException} if busy. */
    public void triggerAsync( final String triggeredBy ) {
        if ( running.get() ) {
            throw new SweepAlreadyRunningException();
        }
        final Thread t = new Thread( () -> {
            try {
                runSweep( triggeredBy );
            } catch ( final SweepAlreadyRunningException e ) {
                LOG.info( "drift sweep skipped — already running" );
            } catch ( final RuntimeException e ) {
                LOG.warn( "drift sweep failed: {}", e.getMessage(), e );
            }
        }, "wikantik-drift-sweep" );
        t.setDaemon( true );
        t.start();
    }

    /** Live offender list for one code — never persisted, always current. */
    public List< PageViolation > currentPageList( final String family, final String code ) {
        if ( "shacl".equals( family ) ) {
            if ( shaclSource == null ) {
                return List.of();
            }
            return shaclSource.get().stream()
                    .filter( v -> v.path().equals( code ) )
                    .map( v -> new PageViolation( v.focusNode(), v.path(), "ERROR", v.path(),
                            v.message(), null ) )
                    .toList();
        }
        final List< PageViolation > out = new ArrayList<>();
        forEachParsedPage( ( name, parsedOrNull, parseError ) -> {
            if ( parseError != null ) {
                if ( "yaml.parse".equals( code ) ) {
                    out.add( new PageViolation( name, "__yaml__", "ERROR", "yaml.parse",
                            parseError.getMessage(), null ) );
                }
                return;
            }
            for ( final FieldViolation v : validator.validate( parsedOrNull.metadata(), ctx ) ) {
                if ( v.code().equals( code ) ) {
                    out.add( new PageViolation( name, v.field(), v.severity().name(), v.code(),
                            v.message(), v.suggestion() ) );
                }
            }
        } );
        return out;
    }

    private int sweepFrontmatter( final Map< CountKey, Integer > counts ) {
        final int[] scanned = { 0 };
        forEachParsedPage( ( name, parsedOrNull, parseError ) -> {
            scanned[ 0 ]++;
            if ( parseError != null ) {
                bump( counts, "frontmatter", "yaml.parse", "ERROR" );
                return;
            }
            if ( parsedOrNull == null ) {
                return; // no frontmatter block — nothing to validate, but the page was scanned
            }
            for ( final FieldViolation v : validator.validate( parsedOrNull.metadata(), ctx ) ) {
                bump( counts, "frontmatter", v.code(), v.severity().name() );
            }
        } );
        return scanned[ 0 ];
    }

    private boolean sweepShacl( final Map< CountKey, Integer > counts ) {
        if ( shaclSource == null ) {
            return false;
        }
        try {
            for ( final OntologyShaclValidator.Violation v : shaclSource.get() ) {
                bump( counts, "shacl", v.path(), "ERROR" );
            }
            return true;
        } catch ( final RuntimeException e ) {
            LOG.warn( "drift sweep: SHACL conformance check unavailable, persisting without it: {}",
                    e.getMessage(), e );
            return false;
        }
    }

    @FunctionalInterface
    private interface PageVisitor {
        /** Exactly one of parsedOrNull / parseError is meaningful; both null = page without frontmatter. */
        void visit( String name, ParsedPage parsedOrNull, FrontmatterParseException parseError );
    }

    private void forEachParsedPage( final PageVisitor visitor ) {
        final Collection< Page > pages;
        try {
            pages = pageManager.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "drift sweep: page enumeration failed: {}", e.getMessage(), e );
            throw new IllegalStateException( "page enumeration failed", e );
        }
        for ( final Page page : pages ) {
            final String name = page.getName();
            final String text;
            try {
                text = pageManager.getPureText( page );
            } catch ( final RuntimeException e ) {
                LOG.warn( "drift sweep: failed to read '{}', skipping: {}", name, e.getMessage() );
                continue;
            }
            if ( text == null || text.isEmpty()
                    || ( !text.startsWith( "---\n" ) && !text.startsWith( "---\r\n" ) ) ) {
                visitor.visit( name, null, null );
                continue;
            }
            try {
                visitor.visit( name, FrontmatterParser.parseStrict( text ), null );
            } catch ( final FrontmatterParseException e ) {
                visitor.visit( name, null, e );
            }
        }
    }

    private static void bump( final Map< CountKey, Integer > counts, final String family,
                              final String code, final String severity ) {
        counts.merge( new CountKey( family, code, severity ), 1, Integer::sum );
    }
}
```

Note: `forEachParsedPage` visits no-frontmatter pages with `(null, null)` so the scanned counter includes them, but `currentPageList`'s visitor must tolerate that (the code above does: a null `parsedOrNull` with null `parseError` falls through `validator.validate` — **guard it**: in `currentPageList`'s lambda add `if ( parsedOrNull == null ) return;` before the validate loop, mirroring `sweepFrontmatter`). The test in Step 1 exercises a no-frontmatter page through `runSweep`; make sure `currentPageList` carries the same guard.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=DriftSweepServiceTest -q`
Expected: PASS (6 tests). If `malformedYamlIsCountedNotFatal` fails because the sample text parses cleanly, make the YAML more broken (e.g. `"---\n\t{ not yaml\n---\n\nbody"`) — the assertion is on the `yaml.parse` count, not the exact text.

- [ ] **Step 5: Add the `currentPageList` test (red → green)**

Append to `DriftSweepServiceTest`:

```java
    @Test
    void currentPageListReturnsOffendersForCode() throws Exception {
        final PageManager pm = pm(
                "Drifty", "---\ntype: article\nstatus: bogus-state\n---\n\nbody",
                "Clean",  "---\ntype: article\nstatus: active\n---\n\nbody",
                "NoFm",   "Just a body." );
        final DriftSweepService svc = service( pm, mock( DriftSnapshotRepository.class ), null );

        final List< PageViolation > offenders = svc.currentPageList( "frontmatter", "status.noncanonical" );
        assertEquals( 1, offenders.size() );
        assertEquals( "Drifty", offenders.get( 0 ).pageName() );
        assertEquals( "status", offenders.get( 0 ).field() );
        assertNotNull( offenders.get( 0 ).message() );
    }

    @Test
    void currentPageListForShaclFiltersByPath() throws Exception {
        final PageManager pm = pm();
        final DriftSweepService svc = service( pm, mock( DriftSnapshotRepository.class ),
                () -> List.of(
                        new OntologyShaclValidator.Violation( "wk:e1", "wk:implements", "bad subject" ),
                        new OntologyShaclValidator.Violation( "wk:e2", "wk:located_in", "bad object" ) ) );

        final List< PageViolation > offenders = svc.currentPageList( "shacl", "wk:implements" );
        assertEquals( 1, offenders.size() );
        assertEquals( "wk:e1", offenders.get( 0 ).pageName() );
    }
```

Run: `mvn test -pl wikantik-main -Dtest=DriftSweepServiceTest -q` → PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/drift/DriftSweepService.java \
        wikantik-main/src/test/java/com/wikantik/drift/DriftSweepServiceTest.java
git commit -m "feat(drift): corpus sweep service — frontmatter + SHACL aggregation, single-flight, live drill-down"
```

---

### Task 4: Post-rebuild hook on OntologyRebuildCoordinator

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyRebuildCoordinator.java`
- Test: `wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyRebuildCoordinatorTest.java` (extend)

- [ ] **Step 1: Write the failing tests** (append to the existing test class; mirror its existing `coordinator(...)` helper usage — see `rebuildRunsAsyncAndMaterializesGraphs` for how it awaits completion)

```java
    @Test
    void postRebuildHookRunsAfterSuccessfulRebuild() throws Exception {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final OntologyRebuildCoordinator svc = new OntologyRebuildCoordinator(
                mgr, List::of, List::of, List::of, true );
        final java.util.concurrent.CountDownLatch hookRan = new java.util.concurrent.CountDownLatch( 1 );
        svc.onRebuildComplete( hookRan::countDown );

        svc.triggerRebuild();
        assertTrue( hookRan.await( 10, java.util.concurrent.TimeUnit.SECONDS ),
                "post-rebuild hook must run after a successful rebuild" );
    }

    @Test
    void throwingHookDoesNotPoisonRebuildOrOtherHooks() throws Exception {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final OntologyRebuildCoordinator svc = new OntologyRebuildCoordinator(
                mgr, List::of, List::of, List::of, true );
        final java.util.concurrent.CountDownLatch secondHookRan = new java.util.concurrent.CountDownLatch( 1 );
        svc.onRebuildComplete( () -> { throw new IllegalStateException( "boom" ); } );
        svc.onRebuildComplete( secondHookRan::countDown );

        svc.triggerRebuild();
        assertTrue( secondHookRan.await( 10, java.util.concurrent.TimeUnit.SECONDS ),
                "a throwing hook must not prevent later hooks" );
        // coordinator must return to IDLE with no error recorded
        org.awaitility.Awaitility.await().atMost( java.time.Duration.ofSeconds( 10 ) )
                .until( () -> "IDLE".equals( svc.status().state() ) );
        assertNull( svc.status().lastError() );
    }
```

If `OntologyModelManager.inMemory()` or Awaitility isn't what the existing tests use, copy the construction/await idiom from the existing tests in this file verbatim — the assertions above are what matter.

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -pl wikantik-main -Dtest=OntologyRebuildCoordinatorTest -q`
Expected: COMPILATION ERROR — `onRebuildComplete` does not exist.

- [ ] **Step 3: Implement the hook**

In `OntologyRebuildCoordinator`, add a field and method:

```java
    private final java.util.List< Runnable > rebuildCompleteHooks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Registers a callback invoked (on the rebuild thread) after each successful full rebuild.
     *  Hook exceptions are caught and logged — they can never fail the rebuild. */
    public void onRebuildComplete( final Runnable hook ) {
        rebuildCompleteHooks.add( hook );
    }
```

And in `runRebuild()`, inside the `try` block immediately after the existing
`LOG.info( "ontology rebuild complete: {} named graphs", written );` line:

```java
            for ( final Runnable hook : rebuildCompleteHooks ) {
                try {
                    hook.run();
                } catch ( final RuntimeException e ) {
                    LOG.warn( "post-rebuild hook failed: {}", e.getMessage(), e );
                }
            }
```

(Hooks run while state is still `RUNNING`; `/admin/ontology/status` showing RUNNING during the sweep is acceptable and keeps a second rebuild from starting mid-sweep.)

- [ ] **Step 4: Run to verify pass**

Run: `mvn test -pl wikantik-main -Dtest=OntologyRebuildCoordinatorTest -q`
Expected: PASS (all tests, old and new).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyRebuildCoordinator.java \
        wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyRebuildCoordinatorTest.java
git commit -m "feat(ontology): onRebuildComplete hook (exception-isolated) for the drift sweep"
```

---

### Task 5: Wiring — ctx factory, DriftWiringHelper, WikiEngine, Services record

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/frontmatter/schema/SchemaValidationPageFilter.java` (promote `buildCtx`)
- Modify: `wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java` (return the coordinator)
- Create: `wikantik-main/src/main/java/com/wikantik/drift/DriftWiringHelper.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:1573` (capture coordinator, call wireDrift)
- Modify: `wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystem.java` (Services record gains `driftSweepService`)
- Modify: `wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystemFactory.java` (resolve it)

No new behavior to TDD here (composition only); the safety net is compilation + the existing unit suite + the ArchUnit decomposition test + the Task 10 IT, which proves end-to-end wiring.

- [ ] **Step 1: Promote the ctx builder.** In `SchemaValidationPageFilter`, change `private static ValidationCtx buildCtx(...)` to `public static ValidationCtx engineBackedCtx(...)` (same body), update the internal call in the production constructor, and add one line of javadoc: `/** Engine-backed context shared by the save filter and the drift sweep. */`

- [ ] **Step 2: Make `wireOntology` return the coordinator.** Change the signature from `public static void wireOntology(...)` to `public static OntologyRebuildCoordinator wireOntology(...)`; `return null;` at the early-exit disabled branch, `return coordinator;` at the end.

- [ ] **Step 3: Write `DriftWiringHelper`**

```java
package com.wikantik.drift;

import com.wikantik.WikiEngine;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.managers.PageManager;
import com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator;
import com.wikantik.frontmatter.schema.SchemaValidationPageFilter;
import com.wikantik.ontology.OntologyShaclValidator;
import com.wikantik.ontology.runtime.OntologyRebuildCoordinator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Constructs the drift sweep service and registers it on the engine. Independent of the
 * ontology flag: with the ontology disabled ({@code coordinator == null}) the sweep still
 * works, just without the shacl family and without the scheduled post-rebuild trigger.
 * Named {@code *WiringHelper} per the decomposition convention (setManager only).
 */
public final class DriftWiringHelper {

    private static final Logger LOG = LogManager.getLogger( DriftWiringHelper.class );

    private DriftWiringHelper() {}

    public static void wireDrift( final WikiEngine engine,
                                  final Properties props,
                                  final DataSource dataSource,
                                  final PageManager pageManager,
                                  final OntologyRebuildCoordinator coordinator ) {
        final Supplier< List< OntologyShaclValidator.Violation > > shaclSource;
        if ( coordinator != null && coordinator.modelManager() != null ) {
            final OntologyShaclValidator shacl = new OntologyShaclValidator();
            final var mgr = coordinator.modelManager();
            shaclSource = () -> shacl.validate( mgr.inferenceSnapshot() );
        } else {
            shaclSource = null;
        }

        final DriftSweepService service = new DriftSweepService(
                pageManager,
                new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() ),
                SchemaValidationPageFilter.engineBackedCtx( props, pageManager ),
                shaclSource,
                new DriftSnapshotRepository( dataSource ) );
        engine.setManager( DriftSweepService.class, service );

        if ( coordinator != null ) {
            coordinator.onRebuildComplete( () -> {
                try {
                    service.runSweep( "scheduled" );
                } catch ( final DriftSweepService.SweepAlreadyRunningException e ) {
                    LOG.info( "post-rebuild drift sweep skipped — already running" );
                }
            } );
        }
        LOG.info( "drift sweep wired (shacl={})", shaclSource != null );
    }
}
```

- [ ] **Step 4: Call it from WikiEngine.** At `WikiEngine.java:1573`, replace

```java
            com.wikantik.ontology.runtime.OntologyWiringHelper.wireOntology( this, props, ds, pageManager, filterManager );
```

with

```java
            final com.wikantik.ontology.runtime.OntologyRebuildCoordinator ontologyCoordinator =
                    com.wikantik.ontology.runtime.OntologyWiringHelper.wireOntology( this, props, ds, pageManager, filterManager );
            com.wikantik.drift.DriftWiringHelper.wireDrift( this, props, ds, pageManager, ontologyCoordinator );
```

(Confirm the surrounding variable names — `props`, `ds`, `pageManager` — match the actual call site; adjust to whatever is in scope there.)

- [ ] **Step 5: Extend the Services record.** In `PageGraphSubsystem.java`, add a final component to `Services`:

```java
    public record Services(
        StructuralIndexService       structuralIndexService,
        PageGraphService             pageGraphService,
        ReferenceManager             referenceManager,
        ContentIndexRebuildService   contentIndexRebuildService,
        OntologyRebuildCoordinator   ontologyRebuildCoordinator,
        com.wikantik.drift.DriftSweepService driftSweepService
    ) {}
```

Document it in the record's javadoc list ("null when the engine boots without a datasource"). In `PageGraphSubsystemFactory.create(...)`, add:

```java
        final com.wikantik.drift.DriftSweepService driftSweepService =
            engine.getManager( com.wikantik.drift.DriftSweepService.class );
```

and pass it as the sixth constructor argument.

- [ ] **Step 6: Fix every other Services construction site.**

Run: `grep -rn "new PageGraphSubsystem.Services(" --include=*.java wikantik-*/src`
Every hit (prod + tests) gains a sixth argument (`null` in tests that don't exercise drift). Then:

```bash
mvn compile -pl wikantik-main -q && mvn test-compile -pl wikantik-main -q
```

(test-compile is mandatory here — record-component changes break test files silently otherwise.)

- [ ] **Step 7: Run the decomposition + filter tests**

Run: `mvn test -pl wikantik-main -Dtest='DecompositionArchTest,SchemaValidationPageFilter*' -q`
Expected: PASS. If `DecompositionArchTest` fails on the new `engine.getManager` call in the factory: the freeze store legitimately gains this entry — but **restore the store from git first** (`git checkout -- wikantik-main/src/test/resources/archunit*` or wherever the store lives), re-run once, and only commit a store change that corresponds exactly to the new factory line (the store self-mutates on failing runs; never commit a store diff from a red run).

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/frontmatter/schema/SchemaValidationPageFilter.java \
        wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java \
        wikantik-main/src/main/java/com/wikantik/drift/DriftWiringHelper.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystem.java \
        wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystemFactory.java
# plus any test files fixed in Step 6 and a legitimate ArchUnit store update, listed by name
git commit -m "feat(drift): wire sweep service — engine-backed ctx, post-rebuild trigger, subsystem accessor"
```

---

### Task 6: AdminDriftResource

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminDriftResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminDriftResourceTest.java`

First: `mvn install -pl wikantik-main -DskipTests -q` (wikantik-rest resolves wikantik-main from ~/.m2).

- [ ] **Step 1: Write the failing test.** Copy the stubbed-servlet pattern from `PageKnowledgeResourceTest` (same package): a private subclass overriding `getSubsystems()` to return a Mockito-mocked `WikiSubsystems` whose `pageGraph()` returns a `PageGraphSubsystem.Services` carrying mocks. Mock `HttpServletRequest`/`HttpServletResponse` with a `StringWriter`-backed `PrintWriter`.

```java
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.WikiSubsystems;
import com.wikantik.drift.DriftCount;
import com.wikantik.drift.DriftSnapshotRepository;
import com.wikantik.drift.DriftSweepRecord;
import com.wikantik.drift.DriftSweepService;
import com.wikantik.drift.PageViolation;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminDriftResourceTest {

    private DriftSweepService service;
    private DriftSnapshotRepository repo;
    private AdminDriftResource servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter body;

    /** Test servlet: injects mocked subsystems, bypassing engine boot. */
    private final class Stub extends AdminDriftResource {
        @Override protected WikiSubsystems getSubsystems() {
            final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
            final PageGraphSubsystem.Services pg = new PageGraphSubsystem.Services(
                    null, null, null, null, null, service );
            when( subs.pageGraph() ).thenReturn( pg );
            return subs;
        }
        @Override DriftSnapshotRepository repository() { return repo; }
    }

    @BeforeEach
    void setUp() throws Exception {
        service = mock( DriftSweepService.class );
        repo = mock( DriftSnapshotRepository.class );
        servlet = new Stub();
        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body, true ) );
    }

    private JsonObject json() {
        return JsonParser.parseString( body.toString() ).getAsJsonObject();
    }

    @Test
    void summaryEmptyStateBeforeFirstSweep() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/summary" );
        when( repo.latest() ).thenReturn( Optional.empty() );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        assertTrue( json().get( "sweptAt" ).isJsonNull() );
        assertEquals( 0, json().getAsJsonArray( "counts" ).size() );
    }

    @Test
    void summaryIncludesCountsAndDeltas() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/summary" );
        when( repo.latest() ).thenReturn( Optional.of( new DriftSweepRecord( 2L,
                Instant.parse( "2026-06-09T05:00:00Z" ), 100, 1234L, "scheduled", true,
                List.of( new DriftCount( "frontmatter", "status.noncanonical", "WARNING", 5 ),
                         new DriftCount( "frontmatter", "date.format", "WARNING", 3 ) ) ) ) );
        when( repo.previousBefore( 2L ) ).thenReturn( Optional.of( new DriftSweepRecord( 1L,
                Instant.parse( "2026-06-08T05:00:00Z" ), 99, 1000L, "scheduled", true,
                List.of( new DriftCount( "frontmatter", "status.noncanonical", "WARNING", 8 ) ) ) ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        assertEquals( "2026-06-09T05:00:00Z", out.get( "sweptAt" ).getAsString() );
        assertEquals( 100, out.get( "pagesScanned" ).getAsInt() );
        assertEquals( "scheduled", out.get( "triggeredBy" ).getAsString() );
        assertTrue( out.get( "shaclChecked" ).getAsBoolean() );

        final var counts = out.getAsJsonArray( "counts" );
        assertEquals( 2, counts.size() );
        final JsonObject status = counts.get( 0 ).getAsJsonObject();
        assertEquals( "status.noncanonical", status.get( "code" ).getAsString() );
        assertEquals( 5, status.get( "count" ).getAsInt() );
        assertEquals( -3, status.get( "delta" ).getAsInt() );
        final JsonObject date = counts.get( 1 ).getAsJsonObject();
        assertTrue( date.get( "delta" ).isJsonNull(), "code absent from previous sweep → null delta" );
    }

    @Test
    void trendReturnsWindowedSweeps() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/trend" );
        when( req.getParameter( "days" ) ).thenReturn( "7" );
        when( repo.trend( 7 ) ).thenReturn( List.of( new DriftSweepRecord( 1L,
                Instant.parse( "2026-06-08T05:00:00Z" ), 99, 1000L, "scheduled", true,
                List.of( new DriftCount( "frontmatter", "x", "WARNING", 8 ) ) ) ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        assertEquals( 1, json().getAsJsonArray( "sweeps" ).size() );
    }

    @Test
    void pagesReturnsLiveList() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/pages" );
        when( req.getParameter( "family" ) ).thenReturn( "frontmatter" );
        when( req.getParameter( "code" ) ).thenReturn( "status.noncanonical" );
        when( service.currentPageList( "frontmatter", "status.noncanonical" ) ).thenReturn(
                List.of( new PageViolation( "Drifty", "status", "WARNING", "status.noncanonical",
                        "Non-canonical status", "active" ) ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject first = json().getAsJsonArray( "pages" ).get( 0 ).getAsJsonObject();
        assertEquals( "Drifty", first.get( "pageName" ).getAsString() );
        assertEquals( "active", first.get( "suggestion" ).getAsString() );
    }

    @Test
    void pagesWithoutCodeIs400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/pages" );
        when( req.getParameter( "family" ) ).thenReturn( "frontmatter" );
        when( req.getParameter( "code" ) ).thenReturn( null );

        servlet.doGet( req, resp );
        verify( resp ).setStatus( 400 );
    }

    @Test
    void sweepTriggerReturns202() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/sweep" );

        servlet.doPost( req, resp );

        verify( service ).triggerAsync( "manual" );
        verify( resp ).setStatus( 202 );
    }

    @Test
    void sweepWhileRunningReturns409() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/sweep" );
        doThrow( new DriftSweepService.SweepAlreadyRunningException() )
                .when( service ).triggerAsync( "manual" );

        servlet.doPost( req, resp );
        verify( resp ).setStatus( 409 );
    }

    @Test
    void serviceUnavailableIs503() throws Exception {
        service = null;
        when( req.getPathInfo() ).thenReturn( "/summary" );
        servlet.doGet( req, resp );
        verify( resp ).setStatus( 503 );
    }
}
```

If `doGet`/`doPost` are protected and not visible to the test, make the test class share the package (it does) — `protected` is package-visible there. If `setStatus` interactions are asserted differently in `PageKnowledgeResourceTest` (e.g. via `sendError` capture), mirror that idiom instead; assertions stay the same.

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -pl wikantik-rest -Dtest=AdminDriftResourceTest -q`
Expected: COMPILATION ERROR — `AdminDriftResource` does not exist.

- [ ] **Step 3: Implement the resource**

```java
package com.wikantik.rest;

import com.wikantik.drift.DriftCount;
import com.wikantik.drift.DriftSnapshotRepository;
import com.wikantik.drift.DriftSweepRecord;
import com.wikantik.drift.DriftSweepService;
import com.wikantik.drift.PageViolation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin drift dashboard endpoints (AdminAuthFilter gates /admin/*):
 * GET  /admin/drift/summary — latest sweep counts + deltas vs the previous sweep
 * GET  /admin/drift/trend?days=N — sweeps in the window, oldest first
 * GET  /admin/drift/pages?family=F&code=C — live offender list (never persisted)
 * POST /admin/drift/sweep — manual async trigger (202 / 409)
 */
public class AdminDriftResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminDriftResource.class );
    private static final com.google.gson.Gson NULL_SAFE_GSON =
            new com.google.gson.GsonBuilder().serializeNulls().create();

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final DriftSweepService service = service();
        if ( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "drift sweep service not available" );
            return;
        }
        final String action = extractPathParam( request );
        if ( "summary".equals( action ) ) {
            handleSummary( response );
        } else if ( "trend".equals( action ) ) {
            handleTrend( request, response );
        } else if ( "pages".equals( action ) ) {
            handlePages( request, response, service );
        } else {
            sendNotFound( response, "Unknown drift endpoint: " + action );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final DriftSweepService service = service();
        if ( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "drift sweep service not available" );
            return;
        }
        if ( !"sweep".equals( extractPathParam( request ) ) ) {
            sendNotFound( response, "Unknown drift endpoint" );
            return;
        }
        try {
            service.triggerAsync( "manual" );
            LOG.info( "manual drift sweep triggered" );
            sendJsonWithStatus( response, 202, Map.of( "state", "RUNNING" ) );
        } catch ( final DriftSweepService.SweepAlreadyRunningException e ) {
            sendJsonWithStatus( response, HttpServletResponse.SC_CONFLICT,
                    Map.of( "state", "RUNNING", "error", "a drift sweep is already running" ) );
        }
    }

    private void handleSummary( final HttpServletResponse response ) throws IOException {
        final Optional< DriftSweepRecord > latest = repository().latest();
        final Map< String, Object > out = new LinkedHashMap<>();
        if ( latest.isEmpty() ) {
            out.put( "sweptAt", null );
            out.put( "counts", List.of() );
            sendJsonWithStatus( response, 200, out );
            return;
        }
        final DriftSweepRecord sweep = latest.get();
        final Map< String, Integer > previous = new LinkedHashMap<>();
        repository().previousBefore( sweep.id() ).ifPresent( prev ->
                prev.counts().forEach( c -> previous.put( countKey( c ), c.count() ) ) );

        final List< Map< String, Object > > counts = new ArrayList<>();
        for ( final DriftCount c : sweep.counts() ) {
            final Map< String, Object > row = new LinkedHashMap<>();
            row.put( "family", c.family() );
            row.put( "code", c.code() );
            row.put( "severity", c.severity() );
            row.put( "count", c.count() );
            final Integer prev = previous.get( countKey( c ) );
            row.put( "delta", prev == null ? null : c.count() - prev );
            counts.add( row );
        }
        out.put( "sweptAt", DateTimeFormatter.ISO_INSTANT.format( sweep.sweptAt() ) );
        out.put( "pagesScanned", sweep.pagesScanned() );
        out.put( "durationMs", sweep.durationMs() );
        out.put( "triggeredBy", sweep.triggeredBy() );
        out.put( "shaclChecked", sweep.shaclChecked() );
        out.put( "counts", counts );
        sendJsonWithStatus( response, 200, out );
    }

    private void handleTrend( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        int days = 30;
        final String daysParam = request.getParameter( "days" );
        if ( daysParam != null ) {
            try {
                days = Math.max( 1, Integer.parseInt( daysParam ) );
            } catch ( final NumberFormatException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "days must be an integer" );
                return;
            }
        }
        final List< Map< String, Object > > sweeps = new ArrayList<>();
        for ( final DriftSweepRecord s : repository().trend( days ) ) {
            final Map< String, Object > row = new LinkedHashMap<>();
            row.put( "sweptAt", DateTimeFormatter.ISO_INSTANT.format( s.sweptAt() ) );
            row.put( "shaclChecked", s.shaclChecked() );
            row.put( "counts", s.counts().stream().map( c -> Map.of(
                    "family", c.family(), "code", c.code(),
                    "severity", c.severity(), "count", c.count() ) ).toList() );
            sweeps.add( row );
        }
        sendJsonWithStatus( response, 200, Map.of( "sweeps", sweeps ) );
    }

    private void handlePages( final HttpServletRequest request, final HttpServletResponse response,
                              final DriftSweepService service ) throws IOException {
        final String family = request.getParameter( "family" );
        final String code = request.getParameter( "code" );
        if ( family == null || family.isBlank() || code == null || code.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "family and code are required" );
            return;
        }
        final List< Map< String, Object > > pages = new ArrayList<>();
        for ( final PageViolation v : service.currentPageList( family, code ) ) {
            final Map< String, Object > row = new LinkedHashMap<>();
            row.put( "pageName", v.pageName() );
            row.put( "field", v.field() );
            row.put( "severity", v.severity() );
            row.put( "code", v.code() );
            row.put( "message", v.message() );
            row.put( "suggestion", v.suggestion() );
            pages.add( row );
        }
        sendJsonWithStatus( response, 200, Map.of( "pages", pages ) );
    }

    private static String countKey( final DriftCount c ) {
        return c.family() + "|" + c.code() + "|" + c.severity();
    }

    private DriftSweepService service() {
        return getSubsystems().pageGraph().driftSweepService();
    }

    /** Package-visible seam so the unit test can inject a mock repository. */
    DriftSnapshotRepository repository() {
        return service().repository();
    }

    private void sendJsonWithStatus( final HttpServletResponse response, final int status,
                                     final Object payload ) throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( NULL_SAFE_GSON.toJson( payload ) );
    }
}
```

- [ ] **Step 3b: Expose the repository from the service.** The resource's `repository()` delegates to the service, so `DriftSweepService` (Task 3 file) needs one accessor — add it now:

```java
    /** Read access for the REST layer — same repository the sweep persists into. */
    public DriftSnapshotRepository repository() {
        return repository;
    }
```

(The test's `Stub` overrides `repository()` directly, so the servlet stays free of JDBC plumbing and the unit tests never touch a real DataSource.)

- [ ] **Step 4: Run to verify pass**

```bash
mvn install -pl wikantik-main -DskipTests -q   # pick up the new repository() accessor
mvn test -pl wikantik-rest -Dtest=AdminDriftResourceTest -q
```
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminDriftResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminDriftResourceTest.java \
        wikantik-main/src/main/java/com/wikantik/drift/DriftSweepService.java
git commit -m "feat(drift): /admin/drift REST surface — summary deltas, trend, live pages, async sweep"
```

---

### Task 7: web.xml registration

**Files:**
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Add the servlet** (immediately after the `AdminOntologyResource` `<servlet>` block, ~line 568):

```xml
   <servlet>
       <servlet-name>AdminDriftResource</servlet-name>
       <servlet-class>com.wikantik.rest.AdminDriftResource</servlet-class>
   </servlet>
```

- [ ] **Step 2: Add the mapping** (immediately after the `AdminOntologyResource` `<servlet-mapping>` block, ~line 840):

```xml
   <servlet-mapping>
       <servlet-name>AdminDriftResource</servlet-name>
       <url-pattern>/admin/drift/*</url-pattern>
   </servlet-mapping>
```

- [ ] **Step 3: Commit**

```bash
git add wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(drift): register /admin/drift/* servlet"
```

---

### Task 8: Frontend API client + route + sidebar

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` (inside the `admin: {` object)
- Modify: `wikantik-frontend/src/main.jsx` (lazy import + child route under `/admin`)
- Modify: `wikantik-frontend/src/components/admin/AdminSidebar.jsx`

- [ ] **Step 1: API client methods** — add inside `api.admin`, next to the retrieval-quality methods:

```js
    getDriftSummary: () => request('/admin/drift/summary'),

    getDriftTrend: (days = 30) => request(`/admin/drift/trend?days=${days}`),

    getDriftPages: (family, code) =>
      request(`/admin/drift/pages?family=${encodeURIComponent(family)}&code=${encodeURIComponent(code)}`),

    runDriftSweep: () => request('/admin/drift/sweep', { method: 'POST', body: '{}' }),
```

- [ ] **Step 2: Route.** In `main.jsx`, add next to the other admin lazy imports:

```js
const AdminDriftPage = React.lazy(() => import('./components/admin/AdminDriftPage'));
```

and a child route inside `<Route path="/admin" element={<AdminLayout />}>`, mirroring the retrieval-quality child route's exact syntax:

```jsx
<Route path="drift" element={<AdminDriftPage />} />
```

(`/admin/*` is already an SPA prefix in `SpaRoutingFilter` — browser navigations forward to the SPA, JSON fetches reach the servlet. No web.xml/SPA_EXACT change for the page route.)

- [ ] **Step 3: Sidebar.** In `AdminSidebar.jsx`, in the `Knowledge & Search` group after Retrieval Quality:

```js
      { to: '/admin/drift', label: 'Drift' },
```

- [ ] **Step 4: Verify nothing broke**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/AdminSidebar.test.jsx`
Expected: PASS (the page itself comes in Task 9; the route's lazy import will 404 in dev until then — that's fine, nothing imports it yet except the route, and vitest doesn't render routes).

Note: if `AdminSidebar.test.jsx` asserts an exact link count/snapshot, update it for the new entry — that's the failing-test-first signal for this task.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/api/client.js wikantik-frontend/src/main.jsx \
        wikantik-frontend/src/components/admin/AdminSidebar.jsx \
        wikantik-frontend/src/components/admin/AdminSidebar.test.jsx
git commit -m "feat(drift): admin API client methods, /admin/drift route, sidebar entry"
```

---

### Task 9: AdminDriftPage (vitest TDD)

**Files:**
- Create: `wikantik-frontend/src/components/admin/AdminDriftPage.jsx`
- Test: `wikantik-frontend/src/components/admin/AdminDriftPage.test.jsx`

Reuse `Sparkline` for per-code trends and mirror `AdminRetrievalQualityPage`'s wrapper usage (`AdminPage`, `PageHeader`, `../../styles/admin.css`) — open that file and copy its exact wrapper props.

- [ ] **Step 1: Write the failing test**

```jsx
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AdminDriftPage from './AdminDriftPage';

vi.mock('../../api/client', () => ({
  api: {
    admin: {
      getDriftSummary: vi.fn(),
      getDriftTrend: vi.fn(),
      getDriftPages: vi.fn(),
      runDriftSweep: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const SUMMARY = {
  sweptAt: '2026-06-09T05:00:00Z',
  pagesScanned: 120,
  durationMs: 4200,
  triggeredBy: 'scheduled',
  shaclChecked: true,
  counts: [
    { family: 'frontmatter', code: 'status.noncanonical', severity: 'WARNING', count: 5, delta: -3 },
    { family: 'shacl', code: 'wk:implements', severity: 'ERROR', count: 2, delta: null },
  ],
};

const TREND = {
  sweeps: [
    { sweptAt: '2026-06-08T05:00:00Z', shaclChecked: true,
      counts: [{ family: 'frontmatter', code: 'status.noncanonical', severity: 'WARNING', count: 8 }] },
    { sweptAt: '2026-06-09T05:00:00Z', shaclChecked: true,
      counts: [{ family: 'frontmatter', code: 'status.noncanonical', severity: 'WARNING', count: 5 }] },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
  api.admin.getDriftSummary.mockResolvedValue(SUMMARY);
  api.admin.getDriftTrend.mockResolvedValue(TREND);
});

describe('AdminDriftPage', () => {
  it('renders the latest sweep summary with counts and deltas', async () => {
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByText('status.noncanonical')).toBeInTheDocument());
    expect(screen.getByText('wk:implements')).toBeInTheDocument();
    expect(screen.getByTestId('drift-pages-scanned')).toHaveTextContent('120');
    expect(screen.getByTestId('delta-status.noncanonical')).toHaveTextContent('-3');
  });

  it('shows the empty state before the first sweep', async () => {
    api.admin.getDriftSummary.mockResolvedValue({ sweptAt: null, counts: [] });
    api.admin.getDriftTrend.mockResolvedValue({ sweeps: [] });
    render(<AdminDriftPage />);
    await waitFor(() =>
      expect(screen.getByTestId('drift-empty-state')).toBeInTheDocument());
  });

  it('expanding a row fetches the live page list', async () => {
    api.admin.getDriftPages.mockResolvedValue({
      pages: [{ pageName: 'Drifty', field: 'status', severity: 'WARNING',
                code: 'status.noncanonical', message: 'Non-canonical status', suggestion: 'active' }],
    });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByText('status.noncanonical')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('expand-status.noncanonical'));
    await waitFor(() => expect(screen.getByText('Drifty')).toBeInTheDocument());
    expect(api.admin.getDriftPages).toHaveBeenCalledWith('frontmatter', 'status.noncanonical');
    expect(screen.getByText(/active/)).toBeInTheDocument();
  });

  it('run-now triggers a sweep and reloads after completion', async () => {
    api.admin.runDriftSweep.mockResolvedValue({ state: 'RUNNING' });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByTestId('drift-run-now')).toBeEnabled());

    fireEvent.click(screen.getByTestId('drift-run-now'));
    expect(api.admin.runDriftSweep).toHaveBeenCalled();
    await waitFor(() => expect(api.admin.getDriftSummary.mock.calls.length).toBeGreaterThan(1));
  });

  it('shows a badge when SHACL was not checked', async () => {
    api.admin.getDriftSummary.mockResolvedValue({ ...SUMMARY, shaclChecked: false });
    render(<AdminDriftPage />);
    await waitFor(() =>
      expect(screen.getByTestId('drift-shacl-unchecked')).toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/AdminDriftPage.test.jsx`
Expected: FAIL — module `./AdminDriftPage` not found.

- [ ] **Step 3: Implement the page**

```jsx
import { useState, useEffect, useCallback, useRef } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import PageHeader from './PageHeader';
import Sparkline from './Sparkline';
import '../../styles/admin.css';

const POLL_INTERVAL_MS = 2000;
const POLL_MAX_TRIES = 60;

// Per-code series out of the trend payload, for row sparklines.
function seriesFor(trend, family, code) {
  return (trend?.sweeps || []).map(s =>
    (s.counts || [])
      .filter(c => c.family === family && c.code === code)
      .reduce((sum, c) => sum + c.count, 0));
}

export default function AdminDriftPage() {
  const [summary, setSummary] = useState(null);
  const [trend, setTrend] = useState(null);
  const [error, setError] = useState(null);
  const [sweeping, setSweeping] = useState(false);
  const [expanded, setExpanded] = useState(null);     // code currently expanded
  const [pageLists, setPageLists] = useState({});     // code -> pages[]
  const pollRef = useRef(null);

  const load = useCallback(async () => {
    const [s, t] = await Promise.all([api.admin.getDriftSummary(), api.admin.getDriftTrend(30)]);
    setSummary(s);
    setTrend(t);
    return s;
  }, []);

  useEffect(() => {
    let cancelled = false;
    load().catch(err => { if (!cancelled) setError(err.message); });
    return () => { cancelled = true; clearTimeout(pollRef.current); };
  }, [load]);

  const runNow = async () => {
    setError(null);
    setSweeping(true);
    const before = summary?.sweptAt;
    try {
      await api.admin.runDriftSweep();
      // Poll until a new sweep lands (sweptAt advances past the pre-trigger value).
      let tries = 0;
      const poll = async () => {
        tries += 1;
        const s = await load();
        if (s?.sweptAt && s.sweptAt !== before) {
          setSweeping(false);
        } else if (tries < POLL_MAX_TRIES) {
          pollRef.current = setTimeout(poll, POLL_INTERVAL_MS);
        } else {
          setSweeping(false);
          setError('Sweep did not complete within the polling window; refresh to check.');
        }
      };
      pollRef.current = setTimeout(poll, 0);
    } catch (err) {
      setSweeping(false);
      setError(err?.status === 409 ? 'A sweep is already running.' : err.message);
    }
  };

  const toggleExpand = async (family, code) => {
    if (expanded === code) {
      setExpanded(null);
      return;
    }
    setExpanded(code);
    if (!pageLists[code]) {
      try {
        const resp = await api.admin.getDriftPages(family, code);
        setPageLists(prev => ({ ...prev, [code]: resp.pages || [] }));
      } catch (err) {
        setError(err.message);
      }
    }
  };

  const counts = summary?.counts || [];

  return (
    <AdminPage>
      <PageHeader title="Drift" subtitle="Vocabulary drift burn-down — frontmatter warnings and SHACL violations across the corpus" />

      {error && <div className="admin-error" role="alert">{error}</div>}

      <div className="admin-toolbar">
        <button type="button" data-testid="drift-run-now" disabled={sweeping} onClick={runNow}>
          {sweeping ? 'Sweeping…' : 'Run sweep now'}
        </button>
        {summary?.sweptAt && (
          <span className="admin-meta">
            Last sweep {new Date(summary.sweptAt).toLocaleString()} ({summary.triggeredBy}) —{' '}
            <span data-testid="drift-pages-scanned">{summary.pagesScanned}</span> pages scanned
          </span>
        )}
        {summary && summary.shaclChecked === false && (
          <span className="admin-badge" data-testid="drift-shacl-unchecked">SHACL not checked</span>
        )}
      </div>

      {summary && !summary.sweptAt && (
        <p data-testid="drift-empty-state">
          No sweeps yet. Run one now, or wait for the nightly ontology rebuild to trigger the first sweep.
        </p>
      )}

      {counts.length > 0 && (
        <table className="admin-table">
          <thead>
            <tr>
              <th>Family</th><th>Code</th><th>Severity</th><th>Count</th><th>Δ</th><th>Trend</th><th></th>
            </tr>
          </thead>
          <tbody>
            {counts.map(c => (
              <FragmentRow key={`${c.family}|${c.code}|${c.severity}`} count={c} trend={trend}
                expanded={expanded === c.code} pages={pageLists[c.code]}
                onToggle={() => toggleExpand(c.family, c.code)} />
            ))}
          </tbody>
        </table>
      )}
    </AdminPage>
  );
}

function FragmentRow({ count: c, trend, expanded, pages, onToggle }) {
  return (
    <>
      <tr>
        <td>{c.family}</td>
        <td>{c.code}</td>
        <td>{c.severity}</td>
        <td>{c.count}</td>
        <td data-testid={`delta-${c.code}`}>{c.delta === null || c.delta === undefined ? '—' : c.delta}</td>
        <td><Sparkline values={seriesFor(trend, c.family, c.code)} /></td>
        <td>
          <button type="button" data-testid={`expand-${c.code}`} onClick={onToggle}>
            {expanded ? 'Hide pages' : 'Show pages'}
          </button>
        </td>
      </tr>
      {expanded && (
        <tr>
          <td colSpan={7}>
            {!pages && <span>Loading…</span>}
            {pages && pages.length === 0 && <span>No current offenders — drift resolved since the sweep.</span>}
            {pages && pages.length > 0 && (
              <ul className="admin-sublist">
                {pages.map(p => (
                  <li key={`${p.pageName}|${p.field}`}>
                    <a href={`/edit/${encodeURIComponent(p.pageName)}`}>{p.pageName}</a>
                    {' — '}{p.field}: {p.message}
                    {p.suggestion && <em> (suggested: {p.suggestion})</em>}
                  </li>
                ))}
              </ul>
            )}
          </td>
        </tr>
      )}
    </>
  );
}
```

Adapt `AdminPage` / `PageHeader` props to their real signatures (open `AdminRetrievalQualityPage.jsx` and copy its wrapper usage exactly); keep all `data-testid` attributes — the test depends on them.

- [ ] **Step 4: Run to verify pass**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/AdminDriftPage.test.jsx`
Expected: PASS (5 tests). (Memory note: vitest concurrency can produce false failures — if something unrelated flakes, re-run this file alone before chasing it.)

- [ ] **Step 5: Full frontend suite**

Run: `cd wikantik-frontend && npx vitest run`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/admin/AdminDriftPage.jsx \
        wikantik-frontend/src/components/admin/AdminDriftPage.test.jsx
git commit -m "feat(drift): AdminDriftPage — burn-down table, sparkline trends, live drill-down, run-now"
```

---

### Task 10: REST integration test

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/AdminDriftIT.java`

Model directly on `AdminOntologyRebuildIT.java` (same package): same `baseUrl` system property, `secureCookieOverHttp()` cookie handler, `loginAsAdmin()` (`janne` / `myP@5sw0rd`), `get`/`post` helpers — copy those private helpers verbatim. For seeding a drifting page, copy the page-save call used by `FrontmatterSaveContractIT.java` (same package) — it shows the exact `/api/pages` PUT payload shape.

- [ ] **Step 1: Write the IT**

```java
package com.wikantik.its.rest;

// imports + cookie/login/get/post helpers copied verbatim from AdminOntologyRebuildIT

/**
 * Wire-level IT for the drift dashboard: seed a page with a non-canonical status,
 * trigger a sweep, and assert the summary aggregates it and the live page list names it.
 */
public class AdminDriftIT {

    // ... setUp/baseUrl/client/helpers as in AdminOntologyRebuildIT ...

    @Test
    void sweepAggregatesSeededDriftAndListsThePage() throws Exception {
        try {
            loginAsAdmin();

            // 1. Seed a page whose status is non-canonical (advisory warning on save — 200).
            //    Save call copied from FrontmatterSaveContractIT.
            savePage( "DriftItSeed", "---\ntype: article\nstatus: definitely-not-canonical\n---\n\nDrift IT seed body." );

            // 2. Trigger a sweep (202; 409 only if the post-rebuild sweep is racing — retry once).
            HttpResponse< String > trigger = post( "/admin/drift/sweep", "{}" );
            if ( trigger.statusCode() == 409 ) {
                Thread.sleep( 3000 );
                trigger = post( "/admin/drift/sweep", "{}" );
            }
            assertEquals( 202, trigger.statusCode(), "sweep trigger: " + trigger.body() );

            // 3. Poll the summary until a sweep with our code lands.
            JsonObject summary = null;
            boolean found = false;
            for ( int i = 0; i < 30 && !found; i++ ) {
                Thread.sleep( 2000 );
                final HttpResponse< String > resp = get( "/admin/drift/summary" );
                assertEquals( 200, resp.statusCode(), resp.body() );
                summary = JsonParser.parseString( resp.body() ).getAsJsonObject();
                if ( !summary.get( "sweptAt" ).isJsonNull() ) {
                    for ( final var el : summary.getAsJsonArray( "counts" ) ) {
                        final JsonObject c = el.getAsJsonObject();
                        if ( "status.noncanonical".equals( c.get( "code" ).getAsString() )
                                && c.get( "count" ).getAsInt() >= 1 ) {
                            found = true;
                        }
                    }
                }
            }
            assertTrue( found, "summary must aggregate the seeded drift: " + summary );

            // 4. Live page list names the seeded page with a message.
            final HttpResponse< String > pages =
                    get( "/admin/drift/pages?family=frontmatter&code=status.noncanonical" );
            assertEquals( 200, pages.statusCode(), pages.body() );
            assertTrue( pages.body().contains( "DriftItSeed" ),
                    "live page list must include the seeded page: " + pages.body() );
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void summaryAnonymousIs403() throws Exception {
        // anonymous GET helper as in AdminOntologyRebuildIT.violations_anonymousIs403
        final HttpResponse< String > resp = anonymousGet( "/admin/drift/summary" );
        assertEquals( 403, resp.statusCode(), resp.body() );
    }
}
```

(The `// copied verbatim` markers are instructions to the implementer, not placeholders for unwritten design: the referenced helpers exist character-for-character in the two named files in the same package.)

- [ ] **Step 2: Build prerequisites + run the IT module** (full `clean` — the IT war overlay goes stale without it; skip wikantik-main's unit tests the sanctioned way)

```bash
mvn clean install -DskipTests -q
mvn clean verify -Pintegration-tests -pl wikantik-it-tests/wikantik-it-test-rest -am \
    -Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=AdminDriftIT -fae
```

Expected: `AdminDriftIT` green. If the seeded page save returns 422, the status check became blocking — that's a real regression to investigate (it must be an advisory warning), not a test to weaken.

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/AdminDriftIT.java
git commit -m "test(drift): wire-level IT — seeded drift aggregates into summary + live page list"
```

---

### Task 11: Docs

**Files:**
- Modify: `docs/OntologyManagement.md` (new section before "Quick reference")
- Modify: `CLAUDE.md` (admin resource count in the wikantik-rest agent-surface row)
- Modify: `docs/superpowers/specs/2026-06-09-drift-dashboard-design.md` (`yaml.malformed` → `yaml.parse`, two occurrences)

- [ ] **Step 1: Add a "Measuring drift" section to `docs/OntologyManagement.md`** (insert between the "Evolving the vocabulary" section and "Quick reference"):

```markdown
---

## Measuring drift (the burn-down dashboard)

Advisory warnings only matter if someone can see them in aggregate. The **drift
dashboard** (`/admin/drift`) makes vocabulary drift measurable:

- A **corpus-wide sweep** runs the same schema validator over every page (plus the
  SHACL conformance check over the materialized ontology) and persists one count per
  `(family, code, severity)` — family `frontmatter` for field warnings, `shacl` for
  non-conformant edges. Sweeps run automatically after each nightly ontology rebuild,
  or on demand via the dashboard's *Run sweep now* (`POST /admin/drift/sweep`).
- The dashboard shows the latest counts with **deltas vs. the previous sweep**, a
  per-code **trend sparkline**, and a live **per-code page list** — each offender
  linked to the editor with the validator's suggested fix.
- This is the evidence for the escalation lever: when a code's count reaches zero
  and stays there, it is safe to ratchet that check from warning to error
  (`wikantik.frontmatter.enum.nonCanonical.severity`).

Endpoints: `GET /admin/drift/summary`, `GET /admin/drift/trend?days=N`,
`GET /admin/drift/pages?family=F&code=C`, `POST /admin/drift/sweep`.
```

Also add one row to the Quick reference "Surfaces" table:

```markdown
| Drift burn-down (sweep + counts) | `/admin/drift/*` |
```

- [ ] **Step 2: CLAUDE.md.** In the agent-facing surface table's `/admin/*` row, change "11 admin resources (incl. …)" to "12 admin resources (incl. …, and the `/admin/drift/*` drift burn-down)".

- [ ] **Step 3: Spec alignment.** In the spec, replace both `yaml.malformed` occurrences with `yaml.parse` (the save path's existing code).

- [ ] **Step 4: Commit**

```bash
git add docs/OntologyManagement.md CLAUDE.md docs/superpowers/specs/2026-06-09-drift-dashboard-design.md
git commit -m "docs: drift dashboard — OntologyManagement section, surface counts, spec code alignment"
```

---

### Task 12: Final verification (gates the feature being "done")

- [ ] **Step 1: Full unit reactor**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Frontend suite**

Run: `cd wikantik-frontend && npx vitest run`
Expected: all green.

- [ ] **Step 3: Full IT reactor — sequential, fail-at-end** (house rule: every prod-code change gates on this; run detached, it exceeds a foreground call's wall clock)

```bash
nohup mvn clean install -Pintegration-tests -fae > /tmp/drift-it-reactor.log 2>&1 &
# poll /tmp/drift-it-reactor.log for "BUILD SUCCESS" / "BUILD FAILURE"
```

Expected: BUILD SUCCESS. Known parallel-flaky wikantik-main provider tests pass in isolation — re-run alone before chasing.

- [ ] **Step 4: Done.** Do not push — the user decides when to push.

---

## Self-review notes (already applied)

- `currentPageList` must guard `parsedOrNull == null` (no-frontmatter pages) — called out in Task 3 Step 3's note; the implementer must include the guard.
- Resource ↔ service ↔ repository type names are consistent across Tasks 2/3/6 (`DriftCount`, `DriftSweepRecord`, `PageViolation`, `SweepOutcome`, `triggerAsync`, `repository()`).
- The Services-record change (Task 5) is the cross-module ripple risk: Step 6's grep + `test-compile` is mandatory before commit.
- Wire contract is camelCase throughout (`sweptAt`, `pagesScanned`, `triggeredBy`, `shaclChecked`, `pageName`) — matches the structured-curation convention.
