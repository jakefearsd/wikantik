# Connector Admin UI (P2.4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DB-backed, hot-applied connector configuration with a full admin UI (list, detail, guided setup wizard, per-run sync history) and reader-visible marking of externally-derived wiki content.

**Architecture:** New `connector_configs` (V048) + `connector_sync_run` (V049) tables. A `ConnectorConfigService` (wikantik-main) validates/persists configs and rebuild-and-swaps the `ConnectorRegistry` in the live `ConnectorRuntime` (hot-apply). `ConnectorAdminResource` grows to the full CRUD/test/import surface. The React admin panel gains a Connectors section (list → detail → wizard). Ingestion stamps `derived_connector`/`derived_source_url` frontmatter; PageView, search results, and the sidebar page list render provenance. Spec: `docs/superpowers/specs/2026-07-15-connector-admin-ui-design.md`.

**Tech Stack:** Java 25, gson (parent-managed), H2 `MODE=PostgreSQL` for store tests, JUnit 5 + Mockito, React 19 + vitest 4, Cargo IT (`wikantik-it-test-rest`).

## Global Constraints

- **Style:** ASF license header on every new file (copy from a sibling). Wikantik Java style: `final` params, spaces inside parens, log4j2 `LogManager.getLogger`. Never swallow exceptions — at least `LOG.warn` with context.
- **JSON errors:** in wikantik-rest always use `RestServletBase.sendError( response, code, msg )` / `sendNotFound` — never raw `response.sendError` (leaks Tomcat HTML on codes outside the web.xml error-page net).
- **Secret hygiene:** secret values appear in NO log message, NO exception message, NO response body, NO audit payload. Endpoints report secret *names* and set/unset only.
- **poll() never throws** — nothing in this plan may change that contract or add a try/catch around `SyncOrchestrator.sync`'s poll loop.
- **Migrations:** idempotent DDL (`CREATE TABLE IF NOT EXISTS`), `GRANT … TO :app_user`, NO data backfills (data fixups live outside `bin/db/migrations/`).
- **Signature changes:** after changing any record/interface used by tests, run `mvn test-compile -pl <module> -q` (plain `compile` skips test sources).
- **Builds:** compile-check single modules with `mvn compile -pl <module> -q -am -DskipTests`… prefer `mvn test -pl <module> -Dtest=Class`. Never use `-T` with integration tests; wikantik-main unit runs also avoid `-T 1C` (provider flakes).
- **Frontend tests:** `cd wikantik-frontend && npx vitest run <file>` — if a failure looks unrelated, re-run the file alone (vitest concurrency produces false failures here).
- **Commits:** 1–3 lines, stage exact files by name (never `git add -A`).
- **SPA routing:** `/admin/*` children need NO web.xml/SPA_EXACT changes — `/admin/` is already a SPA prefix and Accept-header gating separates browser navs from `fetch()` (SpaRoutingFilter). Only react-router routes are added.
- **Model tiers (sprint dispatch):** each task carries a `Model:` line — haiku = mechanical/single-file with complete spec here; sonnet = multi-file integration. Final review (Task 25) runs in the main session.

**Task → model map:** T1 haiku · T2 haiku · T3 sonnet · T4 sonnet · T5 sonnet · T6 sonnet · T7 sonnet · T8 sonnet · T9 sonnet · T10 sonnet · T11 sonnet · T12 sonnet · T13 sonnet · T14 haiku · T15 sonnet · T16 haiku · T17 sonnet · T18 sonnet · T19 sonnet · T20 sonnet · T21 haiku · T22 sonnet · T23 sonnet · T24 sonnet · T25 main session.

---

## Phase A — persistence & runtime core

### Task 1: V048 migration + JdbcConnectorConfigStore

**Model:** haiku

**Files:**
- Create: `bin/db/migrations/V048__connector_configs.sql`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/config/ConnectorConfigRow.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/config/JdbcConnectorConfigStore.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/config/JdbcConnectorConfigStoreTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `ConnectorConfigRow( String connectorId, String connectorType, boolean enabled, int syncIntervalHours, String configJson, String cluster, String defaultTags, String pagePrefix )` and `JdbcConnectorConfigStore( DataSource ds )` with `List<ConnectorConfigRow> list()`, `Optional<ConnectorConfigRow> get( String id )`, `void upsert( ConnectorConfigRow row )`, `void delete( String id )`. Used by Tasks 7 and 9.

- [ ] **Step 1: Write the migration**

```sql
-- Admin-managed connector definitions (Connector Admin UI P2.4, 2026-07-15).
-- Non-secret, type-specific settings live in config (JSON); secrets stay in
-- connector_credentials (V047). sync_interval_hours 0 = manual-only.
CREATE TABLE IF NOT EXISTS connector_configs (
    connector_id        TEXT PRIMARY KEY,
    connector_type      TEXT NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    sync_interval_hours INTEGER NOT NULL DEFAULT 0,
    config              TEXT NOT NULL,
    cluster             TEXT,
    default_tags        TEXT,
    page_prefix         TEXT,
    created             TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified            TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE, DELETE ON connector_configs TO :app_user;
```

- [ ] **Step 2: Write the failing test** — mirror `JdbcSyncStateStoreTest`'s H2 harness (`jdbc:h2:mem:...;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`, `@BeforeEach` creates the table with `TEXT`→`VARCHAR`, `TIMESTAMPTZ`→`TIMESTAMP WITH TIME ZONE`):

```java
class JdbcConnectorConfigStoreTest {

    private DataSource ds;

    @BeforeEach void schema() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:connconfig" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_configs (connector_id VARCHAR PRIMARY KEY,"
                + " connector_type VARCHAR NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                + " sync_interval_hours INT NOT NULL DEFAULT 0, config VARCHAR NOT NULL,"
                + " cluster VARCHAR, default_tags VARCHAR, page_prefix VARCHAR,"
                + " created TIMESTAMP WITH TIME ZONE DEFAULT now(), modified TIMESTAMP WITH TIME ZONE DEFAULT now())" );
        }
    }

    @Test void upsertGetListDeleteRoundTrip() {
        final JdbcConnectorConfigStore store = new JdbcConnectorConfigStore( ds );
        assertTrue( store.list().isEmpty() );
        final ConnectorConfigRow row = new ConnectorConfigRow( "gh-notes", "github", true, 24,
            "{\"repo\":\"jake/notes\"}", "Engineering", "notes,github", "Gh" );
        store.upsert( row );
        assertEquals( row, store.get( "gh-notes" ).orElseThrow() );
        store.upsert( new ConnectorConfigRow( "gh-notes", "github", false, 12,
            "{\"repo\":\"jake/notes\",\"branch\":\"main\"}", null, null, null ) );   // upsert overwrites
        assertFalse( store.get( "gh-notes" ).orElseThrow().enabled() );
        assertEquals( 1, store.list().size() );
        store.delete( "gh-notes" );
        assertTrue( store.get( "gh-notes" ).isEmpty() );
    }

    @Test void listOrdersById() {
        final JdbcConnectorConfigStore store = new JdbcConnectorConfigStore( ds );
        store.upsert( new ConnectorConfigRow( "b", "feed", true, 0, "{}", null, null, null ) );
        store.upsert( new ConnectorConfigRow( "a", "feed", true, 0, "{}", null, null, null ) );
        assertEquals( List.of( "a", "b" ), store.list().stream().map( ConnectorConfigRow::connectorId ).toList() );
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvn test -pl wikantik-connectors -Dtest=JdbcConnectorConfigStoreTest -q`
Expected: COMPILE ERROR (classes don't exist).

- [ ] **Step 4: Implement** — `ConnectorConfigRow` is a plain record (javadoc: one row of `connector_configs`; `configJson` is the type-specific non-secret JSON). `JdbcConnectorConfigStore` mirrors `JdbcCredentialStore`'s JDBC idiom (try-with-resources, `RuntimeException` wrap with context, no secrets in messages):

```java
public final class JdbcConnectorConfigStore {

    private final DataSource ds;

    public JdbcConnectorConfigStore( final DataSource ds ) { this.ds = ds; }

    public List< ConnectorConfigRow > list() {
        final List< ConnectorConfigRow > out = new ArrayList<>();
        try ( var c = ds.getConnection(); var ps = c.prepareStatement(
                "SELECT connector_id, connector_type, enabled, sync_interval_hours, config, cluster,"
                + " default_tags, page_prefix FROM connector_configs ORDER BY connector_id" ) ) {
            try ( var rs = ps.executeQuery() ) {
                while ( rs.next() ) out.add( rowFrom( rs ) );
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_configs list failed: " + e.getMessage(), e );
        }
        return out;
    }

    public Optional< ConnectorConfigRow > get( final String id ) { /* same idiom, WHERE connector_id=? */ }

    public void upsert( final ConnectorConfigRow row ) {
        try ( var c = ds.getConnection(); var ps = c.prepareStatement(
                "INSERT INTO connector_configs (connector_id, connector_type, enabled, sync_interval_hours,"
                + " config, cluster, default_tags, page_prefix) VALUES (?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (connector_id) DO UPDATE SET connector_type=EXCLUDED.connector_type,"
                + " enabled=EXCLUDED.enabled, sync_interval_hours=EXCLUDED.sync_interval_hours,"
                + " config=EXCLUDED.config, cluster=EXCLUDED.cluster, default_tags=EXCLUDED.default_tags,"
                + " page_prefix=EXCLUDED.page_prefix, modified=now()" ) ) {
            ps.setString( 1, row.connectorId() );  ps.setString( 2, row.connectorType() );
            ps.setBoolean( 3, row.enabled() );     ps.setInt( 4, row.syncIntervalHours() );
            ps.setString( 5, row.configJson() );   ps.setString( 6, row.cluster() );
            ps.setString( 7, row.defaultTags() );  ps.setString( 8, row.pagePrefix() );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_configs upsert failed for '" + row.connectorId() + "': " + e.getMessage(), e );
        }
    }

    public void delete( final String id ) { /* DELETE FROM connector_configs WHERE connector_id=? */ }

    private static ConnectorConfigRow rowFrom( final ResultSet rs ) throws SQLException {
        return new ConnectorConfigRow( rs.getString( 1 ), rs.getString( 2 ), rs.getBoolean( 3 ),
            rs.getInt( 4 ), rs.getString( 5 ), rs.getString( 6 ), rs.getString( 7 ), rs.getString( 8 ) );
    }
}
```

- [ ] **Step 5: Run tests to verify pass**

Run: `mvn test -pl wikantik-connectors -Dtest=JdbcConnectorConfigStoreTest -q`
Expected: PASS (2 tests). H2 supports `ON CONFLICT` in PostgreSQL mode.

- [ ] **Step 6: Verify migration is idempotent** — re-read the SQL: every statement is `IF NOT EXISTS`/`GRANT`. No further action.

- [ ] **Step 7: Commit**

```bash
git add bin/db/migrations/V048__connector_configs.sql \
  wikantik-connectors/src/main/java/com/wikantik/connectors/config/ \
  wikantik-connectors/src/test/java/com/wikantik/connectors/config/
git commit -m "feat(connectors): V048 connector_configs + JdbcConnectorConfigStore (DB-backed definitions)"
```

---

### Task 2: V049 migration + JdbcSyncRunStore

**Model:** haiku

**Files:**
- Create: `bin/db/migrations/V049__connector_sync_run.sql`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/state/SyncRunRow.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/state/JdbcSyncRunStore.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/state/JdbcSyncRunStoreTest.java`

**Interfaces:**
- Consumes: `com.wikantik.connectors.SyncReport` (record: `created, updated, unchanged, deleted, failed` ints).
- Produces: `SyncRunRow( long runId, String connectorId, String trigger, java.time.Instant started, java.time.Instant finished, String status, int created, int updated, int unchanged, int deleted, int failed, String error )`; `JdbcSyncRunStore( DataSource ds )` with `long start( String connectorId, String trigger )` (inserts status `running`, returns runId), `void finish( long runId, SyncReport report )` (status `ok`), `void fail( long runId, String error )` (status `failed`), `List<SyncRunRow> list( String connectorId, int limit )` (newest first), and pruning to the newest 100 rows per connector inside `start`. Used by Tasks 3 and 10.

- [ ] **Step 1: Write the migration**

```sql
-- Per-run connector sync history (Connector Admin UI P2.4, 2026-07-15).
-- One row per SyncOrchestrator drain; status: running | ok | failed. A row
-- stuck in 'running' means the JVM died mid-sync (rendered as interrupted).
CREATE TABLE IF NOT EXISTS connector_sync_run (
    run_id       BIGSERIAL PRIMARY KEY,
    connector_id TEXT NOT NULL,
    trigger_kind TEXT NOT NULL,
    started      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished     TIMESTAMPTZ,
    status       TEXT NOT NULL DEFAULT 'running',
    created      INTEGER NOT NULL DEFAULT 0,
    updated      INTEGER NOT NULL DEFAULT 0,
    unchanged    INTEGER NOT NULL DEFAULT 0,
    deleted      INTEGER NOT NULL DEFAULT 0,
    failed       INTEGER NOT NULL DEFAULT 0,
    error        TEXT
);
CREATE INDEX IF NOT EXISTS idx_sync_run_connector ON connector_sync_run (connector_id, started DESC);
GRANT SELECT, INSERT, UPDATE, DELETE ON connector_sync_run TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE connector_sync_run_run_id_seq TO :app_user;
```

(Column is `trigger_kind` — `trigger` is a reserved word in PostgreSQL.)

- [ ] **Step 2: Write the failing test** (same H2 harness as Task 1; H2 DDL uses `BIGINT AUTO_INCREMENT PRIMARY KEY` for `run_id`):

```java
@Test void startFinishListRoundTrip() {
    final JdbcSyncRunStore store = new JdbcSyncRunStore( ds );
    final long id = store.start( "c1", "manual" );
    List< SyncRunRow > runs = store.list( "c1", 10 );
    assertEquals( 1, runs.size() );
    assertEquals( "running", runs.get( 0 ).status() );
    assertNull( runs.get( 0 ).finished() );
    store.finish( id, new SyncReport( 3, 1, 2, 0, 1 ) );
    runs = store.list( "c1", 10 );
    assertEquals( "ok", runs.get( 0 ).status() );
    assertEquals( 3, runs.get( 0 ).created() );
    assertEquals( 1, runs.get( 0 ).failed() );
    assertNotNull( runs.get( 0 ).finished() );
}

@Test void failRecordsErrorText() {
    final JdbcSyncRunStore store = new JdbcSyncRunStore( ds );
    final long id = store.start( "c1", "scheduled" );
    store.fail( id, "boom: connection refused" );
    assertEquals( "failed", store.list( "c1", 1 ).get( 0 ).status() );
    assertEquals( "boom: connection refused", store.list( "c1", 1 ).get( 0 ).error() );
}

@Test void listIsNewestFirstAndLimited() {
    final JdbcSyncRunStore store = new JdbcSyncRunStore( ds );
    for ( int i = 0; i < 5; i++ ) store.finish( store.start( "c1", "manual" ), new SyncReport( i, 0, 0, 0, 0 ) );
    final List< SyncRunRow > two = store.list( "c1", 2 );
    assertEquals( 2, two.size() );
    assertTrue( two.get( 0 ).runId() > two.get( 1 ).runId() );
}

@Test void startPrunesBeyond100PerConnector() {
    final JdbcSyncRunStore store = new JdbcSyncRunStore( ds );
    for ( int i = 0; i < 105; i++ ) store.finish( store.start( "c1", "manual" ), new SyncReport( 0, 0, 0, 0, 0 ) );
    assertEquals( 100, store.list( "c1", 1000 ).size() );
}
```

- [ ] **Step 3: Run to verify failure** — `mvn test -pl wikantik-connectors -Dtest=JdbcSyncRunStoreTest -q` → compile error.

- [ ] **Step 4: Implement** — same JDBC idiom as Task 1. `start` uses `INSERT … RETURNING run_id` via `Statement.RETURN_GENERATED_KEYS` (works on both PG and H2), then prunes:

```java
public long start( final String connectorId, final String trigger ) {
    try ( var c = ds.getConnection() ) {
        long id;
        try ( var ps = c.prepareStatement(
                "INSERT INTO connector_sync_run (connector_id, trigger_kind) VALUES (?,?)",
                Statement.RETURN_GENERATED_KEYS ) ) {
            ps.setString( 1, connectorId );  ps.setString( 2, trigger );
            ps.executeUpdate();
            try ( var keys = ps.getGeneratedKeys() ) { keys.next(); id = keys.getLong( 1 ); }
        }
        try ( var prune = c.prepareStatement(
                "DELETE FROM connector_sync_run WHERE connector_id=? AND run_id NOT IN ("
                + " SELECT run_id FROM connector_sync_run WHERE connector_id=? ORDER BY run_id DESC LIMIT 100)" ) ) {
            prune.setString( 1, connectorId );  prune.setString( 2, connectorId );
            prune.executeUpdate();
        }
        return id;
    } catch ( final SQLException e ) {
        throw new RuntimeException( "connector_sync_run start failed for '" + connectorId + "': " + e.getMessage(), e );
    }
}
```

`finish`: `UPDATE connector_sync_run SET finished=now(), status='ok', created=?, updated=?, unchanged=?, deleted=?, failed=? WHERE run_id=?`. `fail`: `UPDATE … SET finished=now(), status='failed', error=? WHERE run_id=?`. `list`: `SELECT … WHERE connector_id=? ORDER BY run_id DESC LIMIT ?`, mapping timestamps via `rs.getTimestamp(n)` → `.toInstant()` (null-safe for `finished`).

- [ ] **Step 5: Run tests** — `mvn test -pl wikantik-connectors -Dtest=JdbcSyncRunStoreTest -q` → PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add bin/db/migrations/V049__connector_sync_run.sql \
  wikantik-connectors/src/main/java/com/wikantik/connectors/state/SyncRunRow.java \
  wikantik-connectors/src/main/java/com/wikantik/connectors/state/JdbcSyncRunStore.java \
  wikantik-connectors/src/test/java/com/wikantik/connectors/state/JdbcSyncRunStoreTest.java
git commit -m "feat(connectors): V049 connector_sync_run + JdbcSyncRunStore (per-run history)"
```

---

### Task 3: ConnectorRuntime — run recording, triggers, kill switch, origin-aware registry, swap

**Model:** sonnet

**Files:**
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorRegistry.java`
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorRuntime.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/runtime/ConnectorRuntimeTest.java` (extend existing)

**Interfaces:**
- Consumes: `JdbcSyncRunStore` from Task 2 — but through a new inner seam so unit tests don't need a DB: `public interface RunRecorder { long start( String connectorId, String trigger ); void finish( long runId, SyncReport report ); void fail( long runId, String error ); }` (declare in `ConnectorRuntime`; `JdbcSyncRunStore` implements it — add `implements ConnectorRuntime.RunRecorder`... **no**: to avoid a dependency cycle declare `RunRecorder` as its own file `wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/RunRecorder.java` and have `JdbcSyncRunStore implements RunRecorder`).
- Produces (used by Tasks 7, 8, 9, 10, 11):
  - `ConnectorRegistry` gains origins: new primary ctor `ConnectorRegistry( Map<String,SourceConnector> byId, Map<String,String> typeById, Map<String,String> originById )`; existing 2-arg ctor delegates with every id mapped to origin `"properties"`; new `public String originOf( String id )` (default `"properties"`).
  - `ConnectorRuntime`: new ctor `ConnectorRuntime( ConnectorRegistry registry, SyncOrchestrator orchestrator, ConnectorStatusReader statusReader, RunRecorder runRecorder, boolean syncingEnabled )` (old 3-arg ctor delegates with a no-op recorder and `true`); `public SyncReport syncNow( String connectorId, String trigger )` (old 1-arg delegates `"manual"`); `public void swapRegistry( ConnectorRegistry next )`; `public ConnectorRegistry registry()`; `public boolean syncingEnabled()`; new exception `ConnectorsDisabledException extends RuntimeException` (own file, runtime package).

- [ ] **Step 1: Write the failing tests** (add to the existing `ConnectorRuntimeTest`; follow its existing stub style for connector/orchestrator — read the file first and reuse its fixtures):

```java
@Test void syncNowRecordsRunHistory() {
    final List< String > events = new ArrayList<>();
    final RunRecorder rec = new RunRecorder() {
        @Override public long start( final String id, final String trigger ) { events.add( "start:" + id + ":" + trigger ); return 7L; }
        @Override public void finish( final long runId, final SyncReport r ) { events.add( "finish:" + runId + ":" + r.created() ); }
        @Override public void fail( final long runId, final String error ) { events.add( "fail:" + runId ); }
    };
    // runtime whose orchestrator returns new SyncReport(1,0,0,0,0) for connector "c1"
    final ConnectorRuntime rt = runtimeWith( rec, true );
    rt.syncNow( "c1", "scheduled" );
    assertEquals( List.of( "start:c1:scheduled", "finish:7:1" ), events );
}

@Test void syncNowRecordsFailureWhenOrchestratorThrows() {
    // orchestrator stub throws RuntimeException("db down"); expect fail: event and rethrow
    ...
    assertThrows( RuntimeException.class, () -> rt.syncNow( "c1", "manual" ) );
    assertEquals( List.of( "start:c1:manual", "fail:7" ), events );
}

@Test void killSwitchRejectsSync() {
    final ConnectorRuntime rt = runtimeWith( noopRecorder(), false );   // syncingEnabled=false
    assertThrows( ConnectorsDisabledException.class, () -> rt.syncNow( "c1" ) );
}

@Test void swapRegistryChangesVisibleConnectors() {
    final ConnectorRuntime rt = runtimeWith( noopRecorder(), true );    // registry contains c1
    rt.swapRegistry( new ConnectorRegistry( Map.of( "c2", stubConnector( "c2" ) ), Map.of( "c2", "feed" ), Map.of( "c2", "db" ) ) );
    assertThrows( IllegalArgumentException.class, () -> rt.syncNow( "c1" ) );
    assertEquals( "db", rt.registry().originOf( "c2" ) );
    assertEquals( "properties", rt.registry().originOf( "unknown" ) );
}
```

- [ ] **Step 2: Run to verify failure** — `mvn test -pl wikantik-connectors -Dtest=ConnectorRuntimeTest -q` → compile errors.

- [ ] **Step 3: Implement.** In `ConnectorRuntime`: make `registry` a `volatile ConnectorRegistry` (non-final); `syncNow( id, trigger )` becomes:

```java
public SyncReport syncNow( final String connectorId, final String trigger ) {
    if ( !syncingEnabled ) {
        throw new ConnectorsDisabledException( "connector syncing disabled by operator (wikantik.connectors.enabled=false)" );
    }
    final SourceConnector c = registry.get( connectorId ).orElseThrow(
        () -> new IllegalArgumentException( "unknown connector: " + connectorId ) );
    final ReentrantLock lock = syncLocks.computeIfAbsent( connectorId, k -> new ReentrantLock() );
    if ( !lock.tryLock() ) {
        throw new SyncInProgressException( connectorId );
    }
    final long runId = runRecorder.start( connectorId, trigger );
    try {
        final SyncReport report = orchestrator.sync( c );
        runRecorder.finish( runId, report );
        return report;
    } catch ( final RuntimeException e ) {
        runRecorder.fail( runId, e.getMessage() );
        throw e;
    } finally {
        lock.unlock();
    }
}
```

`swapRegistry` just assigns the volatile field (in-flight syncs hold their `SourceConnector` reference; locks map is keyed by id and survives). The no-op recorder for the compat ctor: `start` returns `-1`, `finish`/`fail` do nothing. `ConnectorsDisabledException` carries only the fixed message. `scheduleAtFixedRate`/`syncAll` stay as-is in this task (replaced in Task 8) but `syncAll` now calls `syncNow( id, "scheduled" )`.

Then make `JdbcSyncRunStore implements RunRecorder` (its three methods already match — adjust signatures if needed so they match the interface exactly).

- [ ] **Step 4: Run module tests** — `mvn test -pl wikantik-connectors -q` → PASS (full module: the 2-arg registry ctor and 3-arg runtime ctor keep existing tests compiling).

- [ ] **Step 5: Commit**

```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ \
  wikantik-connectors/src/main/java/com/wikantik/connectors/state/JdbcSyncRunStore.java \
  wikantik-connectors/src/test/java/com/wikantik/connectors/runtime/ConnectorRuntimeTest.java
git commit -m "feat(connectors): run-history recording, kill switch, origin-aware registry + hot swap"
```

---

### Task 4: ConnectorConfigCodec — JSON ↔ typed configs + validation

**Model:** sonnet

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/config/ConnectorConfigCodec.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/config/ConnectorConfigCodecTest.java`

**Interfaces:**
- Consumes: the six config records — `WebCrawlerConfig( List<String> seeds, boolean sameHostOnly, String pathPrefix, int maxPages, int maxDepth, long delayMs, String userAgent, boolean respectRobots )`, `SitemapConfig( List<String> sitemapUrls, int maxPages, long delayMs, String userAgent, boolean respectRobots, boolean sameHostOnly )`, `FeedConfig( List<String> feedUrls, int maxItems, boolean fetchFullArticles, long delayMs, String userAgent, boolean respectRobots, boolean sameHostOnly )`, `DriveConfig( List<String> folderIds, int maxFiles, String clientId, String clientSecret, String redirectUri, String exportMimeType )`, `GithubConfig( String repo, String branch, String pathPrefix, int maxFiles )`, `ConfluenceConfig( String baseUrl, String spaceKey, String email, int maxPages )`. Verify each record's exact component order by opening it before writing the builder — do not trust this list blindly.
- Produces (used by Tasks 7, 9, 12):
  - `public static final Set<String> UI_TYPES = Set.of( "webcrawler", "sitemap", "feed", "gdrive", "github", "confluence" )` (no `filesystem` — D9).
  - `public record Validation( Map<String,String> errors ) { public boolean ok() { return errors.isEmpty(); } }`
  - `public static Validation validateId( String id )` — `[a-z0-9-]{1,64}` else error under key `"connector_id"`.
  - `public static Validation validate( String type, JsonObject config )` — field-keyed error map; unknown type → `{"connector_type": "unknown type: …"}`.
  - `public static Object toConfig( String type, JsonObject config )` — returns the typed record with defaults applied; throws `IllegalArgumentException` if `validate` would fail (callers validate first). For gdrive, `clientSecret` is set to `null` (resolved from the CredentialStore at assembly, Task 5).
  - `public static Object toConfigForTest( String type, JsonObject config )` — same, but caps clamped: `max_pages`/`max_files`/`max_items` → `min(value, 3)`, `max_depth` → 1, `delay_ms` → 0.

- [ ] **Step 1: Write the failing tests** (representative set — one happy + one error case per type, plus defaults and clamping):

```java
@Test void githubHappyPathAppliesDefaults() {
    final JsonObject c = JsonParser.parseString( "{\"repo\":\"jake/notes\"}" ).getAsJsonObject();
    assertTrue( ConnectorConfigCodec.validate( "github", c ).ok() );
    final GithubConfig cfg = ( GithubConfig ) ConnectorConfigCodec.toConfig( "github", c );
    assertEquals( "jake/notes", cfg.repo() );
    assertNull( cfg.branch() );
    assertEquals( 500, cfg.maxFiles() );
}

@Test void githubRejectsBadRepoShape() {
    final JsonObject c = JsonParser.parseString( "{\"repo\":\"not-a-repo\"}" ).getAsJsonObject();
    final var v = ConnectorConfigCodec.validate( "github", c );
    assertFalse( v.ok() );
    assertTrue( v.errors().containsKey( "repo" ) );
}

@Test void webcrawlerRequiresHttpSeeds() {
    final JsonObject c = JsonParser.parseString( "{\"seeds\":[\"ftp://x\"]}" ).getAsJsonObject();
    assertTrue( ConnectorConfigCodec.validate( "webcrawler", c ).errors().containsKey( "seeds" ) );
}

@Test void confluenceRequiresBaseUrlSpaceEmail() {
    final var v = ConnectorConfigCodec.validate( "confluence", JsonParser.parseString( "{}" ).getAsJsonObject() );
    assertEquals( Set.of( "base_url", "space_key", "email" ), v.errors().keySet() );
}

@Test void gdriveRequiresFoldersAndClientId() {
    final var v = ConnectorConfigCodec.validate( "gdrive", JsonParser.parseString( "{}" ).getAsJsonObject() );
    assertTrue( v.errors().containsKey( "folder_ids" ) );
    assertTrue( v.errors().containsKey( "client_id" ) );
    assertFalse( v.errors().containsKey( "client_secret" ) );   // secret is NOT part of config JSON
}

@Test void idValidation() {
    assertTrue( ConnectorConfigCodec.validateId( "team-notes-2" ).ok() );
    assertFalse( ConnectorConfigCodec.validateId( "Bad.Id" ).ok() );
    assertFalse( ConnectorConfigCodec.validateId( "" ).ok() );
}

@Test void testClampShrinksCaps() {
    final JsonObject c = JsonParser.parseString( "{\"seeds\":[\"https://example.com\"],\"max_pages\":500,\"max_depth\":4}" ).getAsJsonObject();
    final WebCrawlerConfig cfg = ( WebCrawlerConfig ) ConnectorConfigCodec.toConfigForTest( "webcrawler", c );
    assertEquals( 3, cfg.maxPages() );
    assertEquals( 1, cfg.maxDepth() );
    assertEquals( 0L, cfg.delayMs() );
}

@Test void unknownTypeRejected() {
    assertTrue( ConnectorConfigCodec.validate( "filesystem", new JsonObject() ).errors().containsKey( "connector_type" ) );
}
```

- [ ] **Step 2: Run to verify failure** — compile error.

- [ ] **Step 3: Implement.** JSON field names are exactly the property suffixes `ConnectorWiringHelper` uses: `seeds`, `same_host_only`, `path_prefix`, `max_pages`, `max_depth`, `delay_ms`, `user_agent`, `respect_robots`, `sitemap_urls`, `feed_urls`, `max_items`, `fetch_full_articles`, `folder_ids`, `max_files`, `client_id`, `redirect_uri`, `export_mime`, `repo`, `branch`, `base_url`, `space_key`, `email`. Defaults are identical to the wiring helper's defaults (crawler `max_pages` 100 / `max_depth` 3 / `delay_ms` 1000 / UA `"WikantikCrawler/1.0 (+https://wiki.wikantik.com)"` / robots true / same-host true; sitemap+feed+confluence `max_pages`/`max_items` per helper; gdrive `max_files` 500, `export_mime` `text/markdown`). Validation rules: URL-list fields require every entry to parse as `http`/`https` `java.net.URI` with a host; `repo` must match `[^/\s]+/[^/\s]+`; numeric fields must be ≥ 0 (`max_depth` ≥ 1) — report each bad field under its own key with a human message (e.g. `"seeds": "at least one http(s) URL is required"`). Helper methods `str( obj, key )`, `intVal( obj, key, def )`, `boolVal( obj, key, def )`, `strList( obj, key )` (accepts JSON array of strings) keep the per-type builders flat. `filesystem` is deliberately rejected — javadoc cites design D9.

- [ ] **Step 4: Run tests** — `mvn test -pl wikantik-connectors -Dtest=ConnectorConfigCodecTest -q` → PASS.

- [ ] **Step 5: Commit** — `git add` the two files; `git commit -m "feat(connectors): ConnectorConfigCodec — validation + JSON->typed config (6 UI types)"`

---

### Task 5: ConnectorAssembler — shared build path (extracted from ConnectorWiringHelper)

**Model:** sonnet

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorAssembler.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java` (the six per-type `byId.put(...)` loops delegate to the assembler)
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorAssemblerTest.java`

**Interfaces:**
- Consumes: config records (Task 4's list), `CredentialStore`, the API factories (`GoogleDriveApiFactory`, `HttpGithubApiFactory`, `HttpConfluenceApiFactory`), `HttpPageFetcher`, the connector classes.
- Produces (used by Tasks 7, 9, 12): `public final class ConnectorAssembler` with
  - `public static Optional<SourceConnector> build( String id, String type, Object config, CredentialStore credStore )` — switch on type; returns the same instances `ConnectorWiringHelper` builds today (crawler/sitemap/feed with a fresh `HttpPageFetcher( cfg.userAgent(), Duration.ofSeconds(20) )` and `Thread.sleep` sleeper; gdrive with `() -> credStore.get( id, "refresh_token" )` and a **secret-resolved** `DriveConfig`: if `cfg.clientSecret() == null`, rebuild the record with `credStore.get( id, "client_secret" ).orElse( null )`; github with `() -> credStore.get( id, "token" )`; confluence with `() -> credStore.get( id, "api_token" )`). Unknown type → `Optional.empty()` + `LOG.warn`.
  - Factories are created once as private static finals (they are stateless).

- [ ] **Step 1: Write the failing test**

```java
class ConnectorAssemblerTest {

    private final CredentialStore store = new CredentialStore() {
        @Override public boolean enabled() { return true; }
        @Override public void put( final String c, final String n, final String s ) {}
        @Override public Optional< String > get( final String c, final String n ) { return Optional.of( "sekrit-" + n ); }
        @Override public List< String > list( final String c ) { return List.of(); }
        @Override public void delete( final String c, final String n ) {}
    };

    @Test void buildsEachUiType() {
        assertTrue( ConnectorAssembler.build( "w", "webcrawler",
            new WebCrawlerConfig( List.of( "https://e.com" ), true, null, 10, 2, 0L, "UA", true ), store ).isPresent() );
        assertTrue( ConnectorAssembler.build( "s", "sitemap",
            new SitemapConfig( List.of( "https://e.com/sitemap.xml" ), 10, 0L, "UA", true, true ), store ).isPresent() );
        assertTrue( ConnectorAssembler.build( "f", "feed",
            new FeedConfig( List.of( "https://e.com/rss" ), 10, true, 0L, "UA", true, true ), store ).isPresent() );
        assertTrue( ConnectorAssembler.build( "g", "gdrive",
            new DriveConfig( List.of( "folder1" ), 10, "cid", null, "https://cb", "text/markdown" ), store ).isPresent() );
        assertTrue( ConnectorAssembler.build( "gh", "github",
            new GithubConfig( "o/r", null, null, 10 ), store ).isPresent() );
        assertTrue( ConnectorAssembler.build( "cf", "confluence",
            new ConfluenceConfig( "https://x.atlassian.net", "SP", "a@b.c", 10 ), store ).isPresent() );
    }

    @Test void unknownTypeIsEmpty() {
        assertTrue( ConnectorAssembler.build( "x", "nope", new Object(), store ).isEmpty() );
    }

    @Test void connectorIdsPropagate() {
        final SourceConnector c = ConnectorAssembler.build( "gh", "github",
            new GithubConfig( "o/r", null, null, 10 ), store ).orElseThrow();
        assertEquals( "gh", c.connectorId() );
    }
}
```

- [ ] **Step 2: Verify failure** — `mvn test -pl wikantik-main -Dtest=ConnectorAssemblerTest -q` → compile error.

- [ ] **Step 3: Implement the assembler**, then refactor `ConnectorWiringHelper.wireConnectors` so each of the six loops becomes `ConnectorAssembler.build( id, "type", cfg, credStore ).ifPresent( c -> { byId.put( id, c ); typeById.put( id, "type" ); } )` — the filesystem loop stays inline (not a UI type). Behavior must not change: same connector classes, same suppliers, same secret names.

- [ ] **Step 4: Run the wiring tests** — `mvn test -pl wikantik-main -Dtest='ConnectorWiringHelperTest,ConnectorAssemblerTest' -q` → PASS (existing wiring tests are the extraction guard).

- [ ] **Step 5: Commit** — `git commit -m "refactor(connectors): extract ConnectorAssembler — one build path for properties + DB origins"`

---

### Task 6: SPI — connector identity at the sink + sync-state purge/items

**Model:** sonnet (cross-module signature change — run test-compile everywhere)

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/connectors/DerivedPageSink.java` — `ingest( SourceItem item )` → `ingest( String connectorId, SourceItem item )`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/connectors/SyncStateStore.java` — add `void purge( String connectorId );` and `List<SyncedItem> items( String connectorId );` with `record SyncedItem( String sourceUri, String pageName, java.time.Instant lastSynced ) {}` nested in `SyncStateStore`
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/SyncOrchestrator.java` — pass `id` to `sink.ingest( id, item )`
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/state/JdbcSyncStateStore.java` — implement `purge` (DELETE from both `connector_sync_state` and `connector_synced_item` by connector_id) and `items` (SELECT source_uri, page_name, last_synced ORDER BY page_name)
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/DerivedPageSinkAdapter.java` — accept the id (ignore it for now; Task 15 uses it)
- Modify: every test fake of `DerivedPageSink`/`SyncStateStore` that no longer compiles (find with step 3)

**Interfaces:**
- Produces: `SyncStateStore.purge/items` (used by Tasks 7, 10) and connector identity at the sink (used by Task 15).

- [ ] **Step 1: Write the failing tests** — add to `JdbcSyncStateStoreTest`:

```java
@Test void purgeRemovesStateAndItems() {
    final JdbcSyncStateStore store = new JdbcSyncStateStore( ds );
    store.saveCursor( "c1", new SyncCursor( "cur" ) );
    store.recordSynced( "c1", "u:1", "h", "PageA", List.of() );
    store.recordSynced( "c2", "u:2", "h", "PageB", List.of() );
    store.purge( "c1" );
    assertTrue( store.loadCursor( "c1" ).isEmpty() );
    assertTrue( store.knownUris( "c1" ).isEmpty() );
    assertEquals( List.of( "u:2" ), store.knownUris( "c2" ) );   // untouched
}

@Test void itemsListsPageNames() {
    final JdbcSyncStateStore store = new JdbcSyncStateStore( ds );
    store.recordSynced( "c1", "u:1", "h", "PageA", List.of() );
    final List< SyncStateStore.SyncedItem > items = store.items( "c1" );
    assertEquals( 1, items.size() );
    assertEquals( "PageA", items.get( 0 ).pageName() );
    assertEquals( "u:1", items.get( 0 ).sourceUri() );
    assertNotNull( items.get( 0 ).lastSynced() );
}
```

And in `SyncOrchestratorTest` (existing file — extend its recording sink fake): assert the fake now receives the connector id: `assertEquals( "c1", recordingSink.lastConnectorId )`.

- [ ] **Step 2: Make the signature changes** listed under Files.

- [ ] **Step 3: Find every breakage across the reactor**

Run: `mvn test-compile -q -pl wikantik-api,wikantik-connectors,wikantik-main 2>&1 | grep -E "ERROR|error:" | head -50`
Fix each: test fakes gain the `connectorId` parameter and `purge`/`items` stubs (`purge` no-op, `items` → `List.of()`).

- [ ] **Step 4: Run tests** — `mvn test -pl wikantik-connectors -q && mvn test -pl wikantik-main -Dtest='DerivedPageSinkAdapterTest,*SyncOrchestrator*' -q` → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(connectors): sink receives connectorId; SyncStateStore purge + items (SPI)"`

---

### Task 7: ConnectorConfigService — CRUD, hot-apply, delete semantics, import

**Model:** sonnet

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorConfigService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorConfigServiceTest.java`

**Interfaces:**
- Consumes: `JdbcConnectorConfigStore` (T1), `ConnectorConfigCodec` (T4), `ConnectorAssembler` (T5), `ConnectorRuntime.swapRegistry`/`registry()` (T3), `SyncStateStore.purge/items` (T6), `CredentialStore`.
- Produces (used by Tasks 9–12, 15):

```java
public final class ConnectorConfigService {

    /** Per-connector content defaults for pages the connector creates (design D10). */
    public record ContentDefaults( String cluster, List< String > tags, String pagePrefix ) {
        public static final ContentDefaults EMPTY = new ContentDefaults( null, List.of(), null );
    }
    /** REST-facing view of one connector (no secrets, both origins). */
    public record ConnectorView( String id, String type, String origin, boolean enabled,
            int syncIntervalHours, JsonObject config, String cluster, String defaultTags,
            String pagePrefix, List< String > secretsSet ) {}
    public record DeleteResult( int pagesKept, int pagesDeleted, int credentialsDeleted ) {}
    /** Thrown for mutations against properties-origin connectors (REST maps to 409). */
    public static final class PropertiesOriginException extends RuntimeException { ... }

    public ConnectorConfigService( JdbcConnectorConfigStore configStore, SyncStateStore syncState,
            CredentialStore credStore, ConnectorRuntime runtime,
            Map< String, SourceConnector > propertiesConnectors, Map< String, String > propertiesTypes,
            Consumer< String > pageDeleter, Consumer< String > orphanStamper, Properties props ) { ... }

    public List< ConnectorView > list();
    public Optional< ConnectorView > get( String id );
    public ConnectorConfigCodec.Validation create( String id, String type, JsonObject config,
            boolean enabled, int syncIntervalHours, String cluster, String defaultTags, String pagePrefix );
    public ConnectorConfigCodec.Validation update( String id, JsonObject config,
            boolean enabled, int syncIntervalHours, String cluster, String defaultTags, String pagePrefix );
    public DeleteResult delete( String id, boolean deletePages );
    public ConnectorConfigCodec.Validation importFromProperties( String id, JsonObject configFromCaller );
    public ContentDefaults defaultsFor( String connectorId );
    public long intervalHoursFor( String connectorId );          // DB row value; properties-origin → global default
    public synchronized void rebuild();                          // registry swap (see below)
}
```

**Behavior contract:**
- `create`: `validateId` + duplicate check (against DB rows AND properties ids → error key `connector_id`) + `validate(type,…)`; type must be in `UI_TYPES`. gdrive: if `redirect_uri` absent, default it to `props.getProperty("wikantik.baseURL","") + "/admin/connector-oauth/gdrive/callback"` before persisting. On ok: persist row (`configJson` = `config.toString()`), `rebuild()`.
- `update`: id must exist as DB row; a properties-origin id throws `PropertiesOriginException`. Type is immutable (taken from the existing row). Validate, persist, `rebuild()`.
- `delete`: DB-origin only (else `PropertiesOriginException`). Enumerate `syncState.items( id )`; if `deletePages` → `pageDeleter.accept( pageName )` each (count `pagesDeleted`); else → `orphanStamper.accept( pageName )` each (count `pagesKept`). Then `syncState.purge( id )`; delete each stored credential (`credStore.list(id)` + `delete`, count); `configStore.delete( id )`; `rebuild()`.
- `importFromProperties`: id must be a properties connector and not already a DB row. The caller passes the config JSON (the REST layer reconstructs it from the live properties — see Task 11); validate + persist with the properties connector's type, `enabled=true`, interval = global default; `rebuild()`. After import the DB row shadows the properties definition.
- `rebuild()`: start from the properties maps (`propertiesConnectors`/`propertiesTypes`, origin `properties`), then for each DB row (which **shadows** a same-id properties entry): `ConnectorConfigCodec.toConfig` + `ConnectorAssembler.build`; skip-with-warn on any per-row failure (one bad row must not take down the rest). Only rows with `enabled=true` produce registry entries — but views still list disabled rows. Swap: `runtime.swapRegistry( new ConnectorRegistry( byId, typeById, originById ) )`. Also rebuild the `DriveAuthCoordinator` if any gdrive configs exist — collect `Map<String,DriveConfig>` from BOTH origins (secret-resolved for DB rows) and set a new `DefaultDriveAuthCoordinator( drives, new GoogleDriveOAuthService(), credStore )` via a `Consumer<DriveAuthCoordinator>` seam passed in the ctor (wiring provides `c -> engine.setManager( DriveAuthCoordinator.class, c )`). **Correction to ctor above:** add that seam — `Consumer<DriveAuthCoordinator> coordinatorInstaller` — as the 10th ctor arg.
- `secretsSet` in views: `credStore.list( id )`. `list()` = properties views (origin `properties`, config JSON reconstructed as empty object `{}` — the UI renders properties entries read-only from status alone) + DB views (origin `db`).
- `intervalHoursFor`: DB row → its value; properties-origin → `Long.parseLong( props.getProperty( "wikantik.connectors.sync.interval.hours", "0" ) )` (parse-failure → 0).

- [ ] **Step 1: Write the failing tests** — pure unit test: in-memory fakes for the stores (`JdbcConnectorConfigStore` is a final class — wrap it? No: back it with a real H2 `DataSource` exactly like Task 1's harness; that's cheaper than seam-ifying). Fake `CredentialStore` (Map-backed), fake `SyncStateStore` (Map-backed with `purge`/`items`), real `ConnectorRuntime` with stub orchestrator/status-reader, recording `pageDeleter`/`orphanStamper` lists, recording `coordinatorInstaller`. Cases:

```java
@Test void createValidRowAppearsInRegistryAndList()      // create github row → runtime.registry().get("gh").isPresent(), originOf=="db", view.secretsSet empty
@Test void createRejectsDuplicateOfPropertiesId()        // propertiesConnectors has "legacy"; create("legacy",…) → errors contains connector_id
@Test void createRejectsBadConfig()                      // github without repo → errors contains repo; nothing persisted, registry unchanged
@Test void updatePropertiesOriginThrows()                // update("legacy",…) → PropertiesOriginException
@Test void disabledRowIsListedButNotRegistered()         // create enabled=false → list() contains it, registry doesn't
@Test void deleteKeepStampsOrphansAndPurges()            // syncState items → 2 pages; delete(id,false) → orphanStamper got both, pageDeleter empty, DeleteResult(2,0,credCount), config row gone, registry entry gone
@Test void deleteCascadeDeletesPages()                   // delete(id,true) → pageDeleter got both, DeleteResult(0,2,…)
@Test void importPersistsPropertiesConnector()           // import("legacy", validJson) → DB row exists, registry originOf("legacy")=="db"
@Test void gdriveCreateDefaultsRedirectUri()             // props wikantik.baseURL=https://w.example; create gdrive w/o redirect_uri → persisted config contains https://w.example/admin/connector-oauth/gdrive/callback; coordinatorInstaller invoked
@Test void badDbRowIsSkippedNotFatal()                   // hand-insert a row with configJson "{}" (invalid for github) directly via store; rebuild() → other connectors still present
```

- [ ] **Step 2: Verify failure** — `mvn test -pl wikantik-main -Dtest=ConnectorConfigServiceTest -q` → compile error.

- [ ] **Step 3: Implement** per the behavior contract. Keep `rebuild()` `synchronized`; all mutation methods call it last, after persistence succeeded.

- [ ] **Step 4: Run tests** — PASS (10 tests).

- [ ] **Step 5: Commit** — `git commit -m "feat(connectors): ConnectorConfigService — DB CRUD, hot rebuild+swap, delete/import semantics"`

---

### Task 8: Due-tick scheduler (per-connector intervals)

**Model:** sonnet

**Files:**
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorRuntime.java`
- Test: extend `wikantik-connectors/src/test/java/com/wikantik/connectors/runtime/ConnectorRuntimeTest.java`

**Interfaces:**
- Consumes: `ConnectorStatusReader.read( id, type )` → `ConnectorStatus` whose `lastRun` component is an ISO-8601 instant string or null.
- Produces (used by Task 9): on `ConnectorRuntime`:
  - `@FunctionalInterface public interface IntervalProvider { long intervalHoursFor( String connectorId ); }` (nested in ConnectorRuntime)
  - `public synchronized void startDueTickScheduler( IntervalProvider intervals )` — 60s-period daemon single thread; each tick calls `syncDue( intervals, java.time.Instant.now() )`.
  - `int syncDue( IntervalProvider intervals, java.time.Instant now )` — package-visible for testing; for each registry id: interval ≤ 0 → skip; `lastRun` null → due; else due iff `lastRun.plus( interval, HOURS ) <= now`. Due → `syncNow( id, "scheduled" )` guarded exactly like the old `syncAll` (catch `SyncInProgressException` at INFO, `RuntimeException` at WARN — including `ConnectorsDisabledException`). Returns how many syncs ran.
  - Delete `startScheduler( long )` and `syncAll()` (Task 9 updates the only production caller; fix any tests that used them).

- [ ] **Step 1: Write the failing tests**

```java
@Test void syncDueRunsOnlyDueConnectors() {
    // statusReader stub: "fresh" lastRun = now-1h, "stale" lastRun = now-25h, "never" lastRun = null
    // intervals: fresh=24, stale=24, never=24, manualOnly=0 (also in registry)
    final int ran = rt.syncDue( intervals, now );
    assertEquals( 2, ran );                          // stale + never
    assertEquals( Set.of( "stale", "never" ), syncedIds );
}

@Test void syncDueSkipsZeroInterval() {
    assertEquals( 0, rt.syncDue( id -> 0L, now ) );
}

@Test void syncDueSurvivesFailures() {
    // orchestrator throws for "bad", succeeds for "good" — both due
    assertEquals( 1, rt.syncDue( id -> 1L, now ) );   // only "good" counted, no exception escapes
}
```

- [ ] **Step 2: Verify failure**, **Step 3: Implement** (parse `lastRun` with `Instant.parse`, `DateTimeParseException` → treat as due-never-ran with a WARN), **Step 4:** `mvn test -pl wikantik-connectors -q` → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(connectors): due-tick scheduler — per-connector sync intervals"`

---

### Task 9: Wiring — DB rows at startup, service registration, kill-switch default flip

**Model:** sonnet

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java`
- Create: `wikantik-main/src/main/java/com/wikantik/derived/DerivedPageOrphanStamper.java`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/DerivedPageOrphanStamperTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–8; `DerivedIngestionServiceFactory.build( engine, pm, am )` (existing); `PageManager.getText/deletePage`; `DerivedPage.isDerived`.
- Produces: `wikantik.connectors.enabled` now defaults **true**; runtime always registered (even with zero connectors); `ConnectorConfigService` registered as a manager (`engine.setManager( ConnectorConfigService.class, service )`); `DerivedPageOrphanStamper implements Consumer<String>` — stamps `derived_orphaned: true`.

- [ ] **Step 1: Write the failing tests.** In `ConnectorWiringHelperTest` (extend, following its existing fixture style):

```java
@Test void runtimeWiresWithZeroConnectorsByDefault()      // empty props → wireConnectors returns present; registry empty
@Test void enabledFalseSuppressesSyncing()                // props enabled=false → runtime present but syncNow throws ConnectorsDisabledException
@Test void dbRowsJoinPropertiesConnectors()               // H2 ds with a valid github row + properties feed config → registry has both, origins db/properties
```

`DerivedPageOrphanStamperTest`: fake `PageManager`-shaped seams (see Step 3 — the stamper takes functional seams, not PageManager itself):

```java
@Test void stampsDerivedPage() {
    // metadataReader returns Map.of("derived_from","x"); capture written metadata
    stamper.accept( "PageA" );
    assertEquals( Boolean.TRUE, writtenMetadata.get( "derived_orphaned" ) );
    assertEquals( "unchanged-body", writtenBody );     // body passes through untouched
}
@Test void skipsNonDerivedPage() {                     // metadataReader returns Map.of() → writer never called, WARN logged
@Test void skipsMissingPage() {                        // metadataReader returns empty Optional → writer never called
```

- [ ] **Step 2: Verify failure.**

- [ ] **Step 3: Implement.**
  - `DerivedPageOrphanStamper( DerivedPageIngestionService.PageReader reader, java.util.function.Function<String,String> bodyReader, DerivedPageIngestionService.PageWriter writer, String author )` — reads metadata; if absent or `!DerivedPage.isDerived(meta)` → WARN + return; else `meta.put( "derived_orphaned", true )` and `writer.write( pageName, bodyReader.apply( pageName ), meta, author )`. Reuse the *same* reader/writer implementations `DerivedIngestionServiceFactory` builds — add a small static factory there if its seams aren't exposed: `DerivedIngestionServiceFactory.orphanStamper( engine, pm )` returning the configured stamper (author `"connector-sync"`, bodyReader = `pm.getPureText( page )`-based like the factory's own body handling — copy its exact mechanism).
  - `ConnectorWiringHelper.wireConnectors`:
    1. Kill switch: `final boolean enabled = Boolean.parseBoolean( props.getProperty( PREFIX + "enabled", "true" ) );` — **default flips to "true"**; remove the early-return-when-no-sources block (an empty registry is now fine).
    2. Build properties maps as today (via `ConnectorAssembler`).
    3. `new ConnectorRuntime( registry, orchestrator, statusReader, new JdbcSyncRunStore( ds ), enabled )`.
    4. Construct `ConnectorConfigService` with: `new JdbcConnectorConfigStore( ds )`, the `JdbcSyncStateStore`, credStore, runtime, properties maps, `pm::deletePage`-based deleter, the orphan stamper, props, and `c -> engine.setManager( DriveAuthCoordinator.class, c )`. Call `service.rebuild()` once (loads DB rows), then `engine.setManager( ConnectorConfigService.class, service )`.
    5. Replace `runtime.startScheduler( intervalHours )` with `runtime.startDueTickScheduler( service::intervalHoursFor )`.
    6. Keep the existing gdrive properties-origin coordinator setup only as part of `rebuild()` (delete the inline `setManager( DriveAuthCoordinator.class, … )` block — rebuild owns it now).

- [ ] **Step 4: Run tests** — `mvn test -pl wikantik-main -Dtest='ConnectorWiringHelperTest,DerivedPageOrphanStamperTest,ConnectorConfigServiceTest' -q` → PASS. Then `mvn test-compile -pl wikantik-main -q` (signature/caller sweep).

- [ ] **Step 5: Commit** — `git commit -m "feat(connectors): wire DB-backed configs + config service; kill-switch default true"`

---

## Phase B — REST surface

### Task 10: ConnectorAdminResource — GET expansion (list enrichment, detail, runs, pages)

**Model:** sonnet

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/ConnectorAdminResource.java`
- Test: extend `wikantik-rest/src/test/java/com/wikantik/rest/ConnectorAdminResourceTest.java` (exists — reuse its stub/mock pattern; it overrides `resolveRuntime()`; add a parallel `resolveConfigService()` / `resolveRunStore()` / `resolveSyncState()` seam trio)

**Interfaces:**
- Consumes: `ConnectorConfigService.list/get/defaultsFor/intervalHoursFor` + `ConnectorView` (T7), `JdbcSyncRunStore.list` via `RunRecorder`? No — reading needs the concrete store: add protected seam `resolveRunStore()` returning `JdbcSyncRunStore` (registered? it isn't a manager) — **resolution rule:** Task 9 registers the run store too: add `engine.setManager( JdbcSyncRunStore.class, runStore )` in `ConnectorWiringHelper` (append to Task 9's wiring — do it in this task if Task 9 didn't), and `engine.setManager( com.wikantik.api.connectors.SyncStateStore.class, syncStateStore )` likewise.
- Produces (consumed by frontend Tasks 19, 20):
  - `GET /admin/connectors` → `{ "syncingEnabled": bool, "credentialStoreEnabled": bool, "connectors": [ { "id", "type", "origin", "enabled", "syncIntervalHours", "lastRun", "lastStatus", "pageCount", "secretsSet": ["token"] } ] }` — merges `ConnectorConfigService.list()` (id/type/origin/enabled/interval/secretsSet) with `runtime.status(id)` fields (`lastRun`, `status`→`lastStatus`, `itemCount`→`pageCount`; disabled DB rows aren't in the registry — their status fields are null and `pageCount` comes from `SyncStateStore.items(id).size()`).
  - `GET /admin/connectors/{id}` → `{ ...same fields..., "config": {…}, "cluster", "defaultTags", "pagePrefix" }` (404 unknown).
  - `GET /admin/connectors/{id}/runs?limit=20` → `{ "runs": [ { "runId", "trigger", "started", "finished", "status", "created", "updated", "unchanged", "deleted", "failed", "error" } ] }` (newest first).
  - `GET /admin/connectors/{id}/pages` → `{ "pages": [ { "pageName", "sourceUri", "lastSynced" } ] }`.
  - Existing `GET {id}/status` unchanged.

- [ ] **Step 1: Write the failing tests** — follow the existing test file's mock style (`MockHttpServletRequest`/`Response` or Mockito — copy whatever it uses):

```java
@Test void listMergesConfigViewsAndStatus()      // 1 db view + status stub → payload has origin, secretsSet, lastRun, syncingEnabled true
@Test void listIncludesDisabledDbRow()           // view enabled=false, not in registry → appears with lastRun null, pageCount from items()
@Test void detailReturnsConfigJson()             // GET {id} → config object, cluster/defaultTags/pagePrefix present
@Test void detailUnknownIs404()
@Test void runsReturnsHistoryNewestFirst()       // stub run store rows
@Test void pagesListsSyncedItems()
@Test void listWorksWithoutConfigService()       // resolveConfigService()==null (legacy engine) → connectors from runtime.list() only, origin "properties"
```

- [ ] **Step 2: Verify failure**, **Step 3: Implement.** Route in `doGet` by segments: `[]`→list, `[id]`→detail, `[id,"status"]`→existing, `[id,"runs"]`→runs (parse `limit` via `parseIntParam( request, "limit", 20 )`), `[id,"pages"]`→pages. Keep every response via `sendJson`. `credentialStoreEnabled` = `resolveCredentialStore() != null && store.enabled()` (same resolution idiom as `ConnectorCredentialsResource`).

- [ ] **Step 4: Run** — `mvn test -pl wikantik-rest -Dtest=ConnectorAdminResourceTest -q` → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(rest): connector admin GET surface — enriched list, detail, runs, pages"`

---

### Task 11: ConnectorAdminResource — mutations (create/update/delete/import) + audit

**Model:** sonnet

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/ConnectorAdminResource.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java` — only if Task 10 didn't already register the extra managers
- Test: extend `ConnectorAdminResourceTest`

**Interfaces:**
- Consumes: `ConnectorConfigService.create/update/delete/importFromProperties` (T7); audit via `WikiEngine.getAuditService()` + `AuditEntry.builder()` exactly like `AdminApiKeysResource` (category `AuditCategory.ADMIN`, `actorPrincipal` = `request.getUserPrincipal()`-derived login as the existing resources do it — copy the idiom, wrapped in try/catch WARN).
- Produces (consumed by Tasks 19, 20, 22):
  - `POST /admin/connectors` body `{ "id", "type", "enabled"?, "syncIntervalHours"?, "config": {…}, "cluster"?, "defaultTags"?, "pagePrefix"? }` → 201 + detail payload; 422 `{ "errors": { field: msg } }`; audit `connector.create` (targetId=id, targetType="connector").
  - `PUT /admin/connectors/{id}` same body minus id/type → 200/422/404/409 (`PropertiesOriginException`→409 with its message); audit `connector.update`.
  - `DELETE /admin/connectors/{id}?deletePages=true|false` (default false) → 200 `{ "pagesKept", "pagesDeleted", "credentialsDeleted" }`; 404/409; audit `connector.delete` (targetLabel = `deletePages=<bool>`).
  - `POST /admin/connectors/{id}/import` → 200 detail; 404 (not a properties connector) /409 (already imported — service returns validation error key `connector_id`; map to 409); audit `connector.import`. The REST layer builds the config JSON for import from the engine properties: re-run the matching `ConnectorWiringHelper.<type>Configs( engine.getWikiProperties() )` parser for that id and serialize the typed record back to the codec's JSON field names via a new `ConnectorConfigCodec.toJson( String type, Object config )` (add it + 2 codec tests: round-trips `toConfig(toJson(cfg))` for github and webcrawler).
  - Sync route change: `POST {id}/sync` now also maps `ConnectorsDisabledException` → 409 (message as-is).

- [ ] **Step 1: Write the failing tests**

```java
@Test void createReturns201AndAudits()           // stub service returns ok validation; assert audit recorder saw connector.create
@Test void createValidationErrorsAre422()        // service returns errors map → 422 body {"errors":{"repo":"…"}}
@Test void updatePropertiesOriginIs409()
@Test void deleteReturnsCounts()                 // DeleteResult(2,0,1) → JSON fields
@Test void deleteUnknownIs404()
@Test void importRoundTrip()                     // properties github connector → service.importFromProperties called with reconstructed JSON containing "repo"
@Test void syncDisabledIs409()                   // syncNow throws ConnectorsDisabledException → 409
@Test void codecJsonRoundTrip()                  // in ConnectorConfigCodecTest: toConfig(type, toJson(type, cfg)) == cfg for github + webcrawler
```

- [ ] **Step 2: Verify failure**, **Step 3: Implement** (`doPost` routes `[]`→create, `[id,"import"]`→import, `[id,"sync"]`→sync, `[id,"test"]`→reserved for Task 12; add `doPut`, `doDelete`). Parse bodies with `parseJsonBody( request, response )` (existing helper — returns null after sending 400). Missing `ConnectorConfigService` manager → 503 via the existing `sendServiceUnavailable` pattern with message "Connector configuration service unavailable".

- [ ] **Step 4: Run** — `mvn test -pl wikantik-rest -Dtest=ConnectorAdminResourceTest -q && mvn test -pl wikantik-connectors -Dtest=ConnectorConfigCodecTest -q` → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(rest): connector create/update/delete/import + audit events"`

---

### Task 12: Test-connection endpoints

**Model:** sonnet

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorTestService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorTestServiceTest.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/ConnectorAdminResource.java` (+its test)

**Interfaces:**
- Consumes: `ConnectorConfigCodec.validate/toConfigForTest` (T4), `ConnectorAssembler.build` (T5), `SourceConnector.poll( null )` → `SyncBatch( List<SourceItem> items, SyncCursor nextCursor, boolean complete, List<String> tombstonedUris )` (verify the record's exact shape before use), `CredentialStore`.
- Produces:
  - `public record TestResult( boolean ok, int found, List<String> sample, boolean complete, String message ) {}`
  - `public static TestResult testUnsaved( String type, JsonObject config, Map<String,String> transientCredentials, CredentialStore realStore )` — builds an **overlay** CredentialStore: `get` returns the transient value when present, else delegates to `realStore` (null-safe: a null/disabled realStore delegate returns empty); transient values are never persisted or logged. Assembles with `toConfigForTest` config, calls `poll( null )`, and judges: `items.isEmpty() && !complete` → `ok=false, message="source unreachable or not authorized — no items returned"`; otherwise `ok=true, found=items.size(), sample=first 3 sourceUris, message="reachable — found N item(s) in a capped probe"`.
  - `public static TestResult testSaved( SourceConnector connector )` — same judgment on a plain `poll( null )` of the live connector (**note:** live caps apply; acceptable — the saved-test path is used post-OAuth for gdrive where caps are already user-chosen).
  - REST: `POST /admin/connectors/test` body `{ "type", "config": {…}, "credentials": { name: value }? }` → 200 `TestResult` JSON (400 malformed body; 422 validation errors first — same shape as create). `POST /admin/connectors/{id}/test` → 200 `TestResult` (404 unknown id). Neither route audits (no state change) and neither logs credential values.

- [ ] **Step 1: Write the failing tests** — `ConnectorTestServiceTest` uses a stub `SourceConnector` (record-style fake returning a canned `SyncBatch`) via a package-visible seam: `testUnsaved` takes an assembler function parameter in a package-visible overload `testUnsaved( …, BiFunction<Object,CredentialStore,Optional<SourceConnector>> assembler )` so the unit test injects the stub (the public method passes `(cfg, store) -> ConnectorAssembler.build( id?, … )` — signature detail: the public method also takes `String id` for assembly; keep public signature `testUnsaved( String id, String type, JsonObject config, Map<String,String> creds, CredentialStore realStore )`).

```java
@Test void unreachableSourceFails()          // stub batch: empty+incomplete → ok=false
@Test void reachableSourceReportsSample()    // 5 items → ok, found=5, sample size 3
@Test void transientCredentialOverlays()     // assembler receives store whose get("id","token") == transient value; realStore untouched
@Test void emptyButCompleteIsOkZero()        // empty+complete → ok=true, found=0 (an empty-but-healthy source)
```

REST tests: `testUnsavedEndpointReturnsResult`, `testSavedUnknownIdIs404`.

- [ ] **Step 2–4:** fail → implement → `mvn test -pl wikantik-main -Dtest=ConnectorTestServiceTest -q && mvn test -pl wikantik-rest -Dtest=ConnectorAdminResourceTest -q` → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(connectors): dry-run test-connection service + REST endpoints"`

---

### Task 13: OAuth polish — client_secret from store, return_to, credential-change rebuild + audit

**Model:** sonnet

**Files:**
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DefaultDriveAuthCoordinator.java` (+its test)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/GoogleDriveAuthResource.java` (+its test)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/ConnectorCredentialsResource.java` (+its test)

**Interfaces:**
- Consumes: `DriveAuthCoordinator` interface (unchanged), `CredentialStore`, `ConnectorConfigService.rebuild` (T7).
- Produces:
  - `DefaultDriveAuthCoordinator`: wherever it currently reads `config.clientSecret()`, resolve `credStore.get( id, "client_secret" ).orElse( config.clientSecret() )` (read its current code first; it already holds the credStore).
  - `GoogleDriveAuthResource`: `authorize` accepts optional `?return_to=` — valid iff it starts with `/admin/connectors` and contains no `"//"` and no scheme (`:`); stored in session attr `gdrive.oauth.return_to` alongside the existing state attrs. `callback`: when a stored return_to exists, respond `302` to `contextPath + returnTo + (returnTo.contains("?") ? "&" : "?") + "oauth=" + outcome` where outcome is `ok` or the lowercase `AuthResult` name (`exchange_failed` etc.); without return_to keep today's JSON responses. On SUCCESS also best-effort `resolveConfigService().rebuild()` (new protected seam; null-safe) so the fresh refresh token reaches the live connector.
  - `ConnectorCredentialsResource`: after successful POST/DELETE — audit `connector.credential.set` / `connector.credential.delete` (targetId = connectorId, targetLabel = credential *name*; never the value) and best-effort `resolveConfigService().rebuild()` (secret changes re-assemble connectors, e.g. gdrive client_secret).

- [ ] **Step 1: Write the failing tests**

```java
// DefaultDriveAuthCoordinatorTest (extend): storedClientSecretWinsOverConfig()
// GoogleDriveAuthResourceTest (extend):
@Test void authorizeStoresValidReturnTo()            // ?return_to=/admin/connectors/new → session attr set, 302 to Google
@Test void authorizeRejectsForeignReturnTo()         // ?return_to=https://evil.com and /wiki/x and //evil → attr NOT set (flow proceeds without it)
@Test void callbackRedirectsBackWithOutcome()        // stored return_to → 302 Location ends with ?oauth=ok; failure case → ?oauth=exchange_failed
@Test void callbackWithoutReturnToKeepsJson()
// ConnectorCredentialsResourceTest (extend): postAuditsAndRebuilds(), deleteAuditsAndRebuilds()
```

- [ ] **Step 2–4:** fail → implement → run the three test classes → PASS. Also `mvn test-compile -pl wikantik-connectors,wikantik-rest -q`.

- [ ] **Step 5: Commit** — `git commit -m "feat(gdrive): client_secret from store, wizard return_to on consent flow; credential audit+rebuild"`

---

## Phase C — provenance & derived-content marking

### Task 14: Connectors emit source_url in sourceMetadata

**Model:** haiku

**Files:**
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveItems.java` (+`DriveItemsTest`)
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/github/GithubItems.java` (+`GithubItemsTest`)
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/ConfluenceItems.java` (+`ConfluenceItemsTest`)
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/web/WebFetchItems.java` (+`WebFetchItemsTest`)

**Interfaces:**
- Produces: convention key `"source_url"` in `SourceItem.sourceMetadata` — a human-clickable https URL. Consumed by Task 15.
- Contract per builder (open each file first; each already builds a `sourceMetadata` map or passes one through):
  - `WebFetchItems` (crawler/sitemap/feed): the fetched URL itself → `source_url`.
  - `GithubItems`: `"https://github.com/" + repo + "/blob/" + branch + "/" + path` (URL-encode path segments the same way `HttpGithubApi` does — reuse its encoder if package-visible, else copy the one-liner). Where branch is the configured branch or the listing's resolved default.
  - `ConfluenceItems`: the v2 API returns a webui link (`_links.webui`) — if the existing `ConfluencePage` record carries it, use `baseUrl + webui`; if it does NOT carry it, extend `ConfluencePage` with a nullable `webuiPath` component populated in `HttpConfluenceApi`'s parser and thread it through (this is the only allowed scope growth).
  - `DriveItems`: `DriveFile` similarly — use its `webViewLink` if present; else extend `DriveFile` with nullable `webViewLink` populated in `GoogleDriveApi`'s file mapping.

- [ ] **Step 1:** For each of the four builders: add a failing assertion to its existing test — `assertEquals( expectedUrl, item.sourceMetadata().get( "source_url" ) )` on the existing happy-path fixture.
- [ ] **Step 2:** Verify the four tests fail. `mvn test -pl wikantik-connectors -Dtest='DriveItemsTest,GithubItemsTest,ConfluenceItemsTest,WebFetchItemsTest' -q`
- [ ] **Step 3:** Implement per contract. If `ConfluencePage`/`DriveFile` grow a component: `mvn test-compile -pl wikantik-connectors -q` and fix constructor call sites (pass `null` in tests that don't care).
- [ ] **Step 4:** Re-run the four test classes → PASS. Run the connector suites too: `mvn test -pl wikantik-connectors -q`.
- [ ] **Step 5: Commit** — `git commit -m "feat(connectors): source_url in sourceMetadata (human-clickable origin)"`

---

### Task 15: Ingestion stamps derived_connector / derived_source_url / content defaults

**Model:** sonnet

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/DerivedPage.java` — add constants `DERIVED_CONNECTOR = "derived_connector"`, `DERIVED_SOURCE_URL = "derived_source_url"`, `DERIVED_ORPHANED = "derived_orphaned"` (Task 9's stamper switches to the constant if it inlined the string)
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/IngestOptions.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/DerivedPageIngestionService.java` (`buildMetadata`)
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/DerivedPageSinkAdapter.java`
- Test: extend `DerivedPageIngestionServiceTest` + `DerivedPageSinkAdapterTest` (existing)

**Interfaces:**
- Consumes: `ConnectorConfigService.ContentDefaults` + `defaultsFor` (T7), `ingest( connectorId, item )` (T6), `source_url` metadata (T14).
- Produces:
  - `IngestOptions( boolean force, String author, String derivedFrom, String connectorId, String sourceUrl, String cluster, List<String> tags )` — existing 2- and 3-arg convenience ctors delegate with nulls. (Page-name prefix is NOT an IngestOptions concern — it changes the *filename* below.)
  - `buildMetadata` additions, after the existing provenance block:

```java
if ( opts.connectorId() != null )  meta.put( DerivedPage.DERIVED_CONNECTOR,  opts.connectorId() );
if ( opts.sourceUrl() != null )    meta.put( DerivedPage.DERIVED_SOURCE_URL, opts.sourceUrl() );
// content defaults: creation only — never clobber curation on update (design D10)
if ( !existing.isPresent() ) {
    if ( opts.cluster() != null && !meta.containsKey( "cluster" ) )  meta.put( "cluster", opts.cluster() );
    if ( opts.tags() != null && !opts.tags().isEmpty() && !meta.containsKey( "tags" ) )  meta.put( "tags", opts.tags() );
}
```

  - `DerivedPageSinkAdapter` gains a defaults lookup: new ctor `DerivedPageSinkAdapter( ingestion, deleter, author, Function<String,ConnectorConfigService.ContentDefaults> defaultsFor )` (old ctor delegates with `id -> ContentDefaults.EMPTY`); `ingest( connectorId, item )` becomes:

```java
final var d = defaultsFor.apply( connectorId );
final String prefix = d.pagePrefix() != null ? d.pagePrefix() : "";
final String sourceUrl = item.sourceMetadata() != null
    ? java.util.Objects.toString( item.sourceMetadata().get( "source_url" ), null ) : null;
final IngestResult r = ingestion.ingest(
    item.content(), prefix + flatName( item.sourceUri() ), item.contentType(),
    new IngestOptions( false, author, item.sourceUri(), connectorId, sourceUrl,
        d.cluster(), d.tags() ) );
```

  - Task 9's wiring passes `service::defaultsFor` — but the sink is built *before* the service (service needs runtime needs orchestrator needs sink). Break the cycle with a mutable holder: wiring creates `final AtomicReference<Function<String,ContentDefaults>> defaultsRef = new AtomicReference<>( id -> ContentDefaults.EMPTY )`, passes `id -> defaultsRef.get().apply( id )` to the adapter, and after constructing the service sets `defaultsRef.set( service::defaultsFor )`. Update `ConnectorWiringHelper` accordingly in this task.

- [ ] **Step 1: Write the failing tests**

```java
// DerivedPageIngestionServiceTest additions:
@Test void stampsConnectorProvenance()        // opts w/ connectorId+sourceUrl → metadata has derived_connector + derived_source_url
@Test void clusterAndTagsOnlyOnCreate()       // create: cluster+tags land; re-ingest w/ different defaults + existing cluster → cluster unchanged
@Test void noDefaultsNoKeys()                 // null connectorId → neither key present (manual /api/ingest unaffected)
// DerivedPageSinkAdapterTest additions:
@Test void prefixAppliedToPageName()          // defaults pagePrefix "News" → ingestion received filename "News" + flatName(...)
@Test void sourceUrlThreadedFromMetadata()    // sourceMetadata {"source_url": "https://x"} → opts.sourceUrl()=="https://x"
```

- [ ] **Step 2–4:** fail → implement → `mvn test -pl wikantik-main -Dtest='DerivedPageIngestionServiceTest,DerivedPageSinkAdapterTest,ConnectorWiringHelperTest' -q` → PASS; `mvn test-compile -pl wikantik-main,wikantik-rest -q` (IngestOptions callers: `IngestResource` uses the 3-arg ctor — must still compile).

- [ ] **Step 5: Commit** — `git commit -m "feat(derived): stamp derived_connector/derived_source_url + create-only content defaults + name prefix"`

---

### Task 16: PageView provenance banner

**Model:** haiku

**Files:**
- Create: `wikantik-frontend/src/components/DerivedProvenanceBanner.jsx`
- Create: `wikantik-frontend/src/components/DerivedProvenanceBanner.test.jsx`
- Modify: `wikantik-frontend/src/components/PageView.jsx` — render `<DerivedProvenanceBanner metadata={page.metadata} lastModified={page.lastModified} />` immediately BEFORE the existing `<MetadataPanel metadata={page.metadata} />` line (~line 539)
- Modify: `wikantik-frontend/src/styles/article.css` — append the banner styles

**Interfaces:**
- Consumes: page payload `metadata` object (frontmatter as JSON — keys `derived_from`, `derived_connector`, `derived_source_url`, `derived_orphaned`) and `page.lastModified` (verify the field name PageView already uses for dates by grepping the file; use whatever it renders in its header).
- Produces: nothing downstream.

- [ ] **Step 1: Write the failing test**

```jsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import DerivedProvenanceBanner from './DerivedProvenanceBanner';

describe('DerivedProvenanceBanner', () => {
  it('renders nothing for non-derived pages', () => {
    const { container } = render(<DerivedProvenanceBanner metadata={{}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders source link, connector and machine-managed note', () => {
    render(<DerivedProvenanceBanner metadata={{
      derived_from: 'https://eng.atlassian.net/wiki/x',
      derived_connector: 'team-confluence',
      derived_source_url: 'https://eng.atlassian.net/wiki/x',
    }} lastModified="2026-07-15T06:00:00Z" />);
    const link = screen.getByRole('link', { name: /eng\.atlassian\.net/ });
    expect(link).toHaveAttribute('href', 'https://eng.atlassian.net/wiki/x');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByText(/team-confluence/)).toBeInTheDocument();
    expect(screen.getByText(/machine-managed/i)).toBeInTheDocument();
  });

  it('falls back to derived_from text when no source_url and shows orphaned state', () => {
    render(<DerivedProvenanceBanner metadata={{ derived_from: 'report.pdf', derived_orphaned: true }} />);
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText(/report\.pdf/)).toBeInTheDocument();
    expect(screen.getByText(/no longer syncing/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2:** `cd wikantik-frontend && npx vitest run src/components/DerivedProvenanceBanner.test.jsx` → FAIL (module not found).

- [ ] **Step 3: Implement**

```jsx
// Reader-facing provenance banner for externally-derived pages (design D6).
// Renders only when frontmatter carries derived_from. Pure function of props —
// no fetching, so it is safe on public/anonymous views.
export default function DerivedProvenanceBanner({ metadata, lastModified }) {
  if (!metadata || !metadata.derived_from) return null;
  const sourceUrl = metadata.derived_source_url
    || (/^https?:\/\//.test(String(metadata.derived_from)) ? String(metadata.derived_from) : null);
  const sourceLabel = sourceUrl ? sourceUrl.replace(/^https?:\/\//, '') : String(metadata.derived_from);
  const connector = metadata.derived_connector;
  const orphaned = metadata.derived_orphaned === true || metadata.derived_orphaned === 'true';
  return (
    <div className="derived-banner" role="note" data-testid="derived-provenance-banner">
      <span className="derived-banner-icon" aria-hidden="true">↯</span>
      <span>
        Synced from {sourceUrl
          ? <a href={sourceUrl} target="_blank" rel="noopener noreferrer">{sourceLabel}</a>
          : <em>{sourceLabel}</em>}
        {lastModified && <> · last synced {new Date(lastModified).toLocaleDateString()}</>}
        {connector && <> · via connector <strong>{connector}</strong></>}
        {' · '}body is machine-managed
        {orphaned && <span className="derived-banner-orphaned"> — source no longer syncing</span>}
      </span>
    </div>
  );
}
```

CSS (append to `article.css`):

```css
/* Derived-page provenance banner (distinct from the editor warning banner) */
.derived-banner { display: flex; gap: 0.5rem; align-items: baseline; padding: 0.5rem 0.75rem;
  margin: 0.5rem 0 1rem; border-left: 3px solid var(--color-accent, #6b7fd7);
  background: color-mix(in srgb, var(--color-accent, #6b7fd7) 8%, transparent);
  border-radius: 4px; font-size: 0.85rem; }
.derived-banner-icon { font-weight: 700; }
.derived-banner-orphaned { font-style: italic; opacity: 0.8; }
```

- [ ] **Step 4:** vitest run → PASS. Also run `npx vitest run src/components/PageView.test.jsx` (banner renders null on existing fixtures — must not break).
- [ ] **Step 5: Commit** — `git commit -m "feat(ui): reader provenance banner for derived pages"`

---

### Task 17: Derived flag in search results

**Model:** sonnet

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievedPage.java` — append component `boolean derived`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java` — `buildRetrievedPage` passes `meta.get( "derived_from" ) != null`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SearchResource.java` — `if ( p.derived() ) entry.put( "derived", true );`
- Modify: `wikantik-frontend/src/components/SearchResultsPage.jsx` — render badge next to the result title link
- Tests: whichever existing tests construct `RetrievedPage` (fix compile), extend `SearchResourceTest` if present, extend `SearchResultsPage.test.jsx`

- [ ] **Step 1:** Append the component; run `mvn test-compile -q -pl wikantik-api,wikantik-knowledge,wikantik-rest,wikantik-main 2>&1 | grep -E "error:" | head -30` and fix every constructor site (prod site passes the meta check; test sites pass `false` unless the test is about derived).
- [ ] **Step 2: Failing tests.** Java: in the test class covering `DefaultContextRetrievalService.getPage` (find it: `grep -rln "getPage" wikantik-knowledge/src/test | head`), add `derivedFlagSetFromFrontmatter()` — metadata containing `derived_from` → `page.derived() == true`; absent → false. Frontend (`SearchResultsPage.test.jsx` — follow its existing fetch-mock fixture): a result `{ name: 'X', derived: true }` renders `screen.getByTitle('Synced from an external source')`, and a non-derived result doesn't.
- [ ] **Step 3: Implement.** Frontend badge, in the result-item title row (locate the element rendering the page name link):

```jsx
{r.derived && (
  <span className="derived-badge" title="Synced from an external source" aria-label="Synced from an external source">↯</span>
)}
```

CSS append to `globals.css` (shared by search + sidebar + admin):

```css
.derived-badge { display: inline-block; margin-left: 0.3rem; font-size: 0.8em;
  opacity: 0.7; cursor: help; }
```

- [ ] **Step 4:** `mvn test -pl wikantik-knowledge -q` (module suite — record change is invasive) + `npx vitest run src/components/SearchResultsPage.test.jsx` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(search): derived flag on RetrievedPage -> search payload + result badge"`

---

### Task 18: Derived flag in the page list / sidebar cluster tree

**Model:** sonnet

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/pagegraph/PageDescriptor.java` — append component `boolean derived`
- Modify: `wikantik-main/src/main/java/com/wikantik/pagegraph/spine/DefaultStructuralIndexService.java` — both `new PageDescriptor(` sites (~lines 149, 294): populate from the same frontmatter map that feeds `cluster`/`tags` there — `meta.get( "derived_from" ) != null` at the build-from-frontmatter site; at the second site (descriptor rewrite/carry-over), carry the existing descriptor's value.
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java` — alongside `loadClusterBySlug`, collect a `Set<String> derivedSlugs` in the SAME `idx.sitemap().pages()` pass (refactor `loadClusterBySlug` to return both — e.g. a small record `SpineMeta( Map<String,String> clusterBySlug, Set<String> derivedSlugs )`); entry gains `if ( derivedSlugs.contains( page.getName() ) ) entry.put( "derived", true );`
- Modify: `wikantik-frontend/src/components/Sidebar.jsx` — pages from `api.listPages` render `{p.derived && <span className="derived-badge" title="Synced from an external source">↯</span>}` after the page link text
- Tests: fix `PageDescriptor` construction sites across tests (`mvn test-compile`), extend the structural-index test that covers descriptor building (find via `grep -rln "PageDescriptor" wikantik-main/src/test | head -3`), extend `Sidebar.test.jsx`.

- [ ] **Step 1:** Append component; `mvn test-compile -q -pl wikantik-api,wikantik-main,wikantik-rest 2>&1 | grep error: | head -40`; fix sites (tests pass `false`).
- [ ] **Step 2: Failing tests:** structural-index test — a page whose frontmatter has `derived_from: x` yields `descriptor.derived() == true`; Sidebar vitest — `api.listPages` mock returning `{ pages: [{ name: 'A', derived: true }, { name: 'B' }] }` renders exactly one element with title `Synced from an external source`.
- [ ] **Step 3: Implement**, **Step 4:** `mvn test -pl wikantik-main -Dtest='*StructuralIndex*' -q` + `mvn test -pl wikantik-rest -Dtest=PageListResourceTest -q` (if it exists) + `npx vitest run src/components/Sidebar.test.jsx` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(pagegraph): derived flag on PageDescriptor -> page list + sidebar badge"`

---

## Phase D — admin UI

**Shared UI conventions for Tasks 19–23:** all pages live in `wikantik-frontend/src/components/admin/`, use `AdminPage`/`PageHeader`/`AdminTable` (see `AdminApiKeysPage.jsx` as the canonical template), import `'../../styles/admin.css'`, and add `data-testid` attributes for every interactive element. API calls go through `api.connectors.*` (Task 19). Vitest files mock `../../api/client` with `vi.mock`.

### Task 19: API client + routes + sidebar + AdminConnectorsPage (list)

**Model:** sonnet

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` — add to the `api` object:

```js
connectors: {
  list: () => request('/admin/connectors'),
  get: (id) => request(`/admin/connectors/${encodeURIComponent(id)}`),
  create: (body) => request('/admin/connectors', { method: 'POST', body: JSON.stringify(body), extraErrorCodes: [422] }),
  update: (id, body) => request(`/admin/connectors/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body), extraErrorCodes: [422] }),
  remove: (id, deletePages) => request(`/admin/connectors/${encodeURIComponent(id)}?deletePages=${!!deletePages}`, { method: 'DELETE' }),
  sync: (id) => request(`/admin/connectors/${encodeURIComponent(id)}/sync`, { method: 'POST' }),
  runs: (id, limit = 20) => request(`/admin/connectors/${encodeURIComponent(id)}/runs?limit=${limit}`),
  pages: (id) => request(`/admin/connectors/${encodeURIComponent(id)}/pages`),
  importFromProperties: (id) => request(`/admin/connectors/${encodeURIComponent(id)}/import`, { method: 'POST' }),
  test: (body) => request('/admin/connectors/test', { method: 'POST', body: JSON.stringify(body), extraErrorCodes: [422] }),
  testSaved: (id) => request(`/admin/connectors/${encodeURIComponent(id)}/test`, { method: 'POST' }),
  listCredentials: (id) => request(`/admin/connector-credentials/${encodeURIComponent(id)}`),
  setCredential: (id, name, value) => request(`/admin/connector-credentials/${encodeURIComponent(id)}/${encodeURIComponent(name)}`, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: value }),
  deleteCredential: (id, name) => request(`/admin/connector-credentials/${encodeURIComponent(id)}/${encodeURIComponent(name)}`, { method: 'DELETE' }),
},
```

  (First read `request()`'s error handling to confirm how `extraErrorCodes` surfaces the 422 body — mirror how an existing 4xx-tolerant call does it, e.g. frontmatter validate.)
- Modify: `wikantik-frontend/src/main.jsx` — lazy imports + routes under the `/admin` route group: `connectors` → `AdminConnectorsPage`, `connectors/new` → `AddConnectorWizard` (Task 22 — create a placeholder module now exporting `() => null` so the route compiles… **no placeholders**: instead add the `connectors/new` and `connectors/:id` routes in Tasks 22/20 respectively; this task adds ONLY `connectors`).
- Modify: `wikantik-frontend/src/components/admin/AdminSidebar.jsx` — Content group gains `{ to: '/admin/connectors', label: 'Connectors' }` after Page Ownership.
- Create: `wikantik-frontend/src/components/admin/AdminConnectorsPage.jsx` + `.test.jsx`

**Page contract:** loads `api.connectors.list()`. Renders:
- Banner `data-testid="connectors-disabled-banner"` when `syncingEnabled === false`: "Connector syncing is disabled by the operator (`wikantik.connectors.enabled=false` in wikantik-custom.properties). Configuration remains editable; syncs will not run."
- Banner `data-testid="credstore-disabled-banner"` when `credentialStoreEnabled === false`: "Credential storage is not configured, so GitHub / Confluence / Google Drive connectors cannot store secrets. Web crawler, sitemap and feed connectors still work. To enable: generate a key with `openssl rand -base64 32` and set `wikantik.connectors.crypto.key=<key>` in wikantik-custom.properties, then restart." (render the command in a `<code>` block).
- `AdminTable` columns: type (icon glyph per type: webcrawler 🕸 sitemap 🗺 feed 📰 gdrive 📁 github 🐙 confluence 🌀 filesystem 💾 — plain text glyphs), id (link to `/admin/connectors/${id}`), origin chip (`config file` when `origin==='properties'`), enabled (`admin-badge active/locked`), `syncIntervalHours` (`manual` when 0), lastRun (relative date or `—`), lastStatus, pageCount, and a Sync Now button per row (`data-testid="sync-${id}"`) calling `api.connectors.sync(id)` then reloading; disable the button while in flight; surface a thrown 409 as an inline message (`sync already running` / `disabled by operator`).
- Header action: "Add Connector" button → `navigate('/admin/connectors/new')`.
- Empty state (no connectors): explains what connectors do — "Connectors sync external sources — websites, feeds, Google Drive folders, GitHub repos, Confluence spaces — into wiki pages marked with their origin." + Add Connector button.

- [ ] **Step 1: Write the failing test** (mock `api.connectors.list`):

```jsx
it('renders connector rows with origin chip and sync button', async () => { ... });   // 1 db + 1 properties row
it('shows kill-switch banner when syncingEnabled false', async () => { ... });
it('shows credential-store banner with openssl command when store disabled', async () => { ... });
it('shows empty state with Add Connector', async () => { ... });
it('sync button posts and reloads list', async () => { ... });                        // api.connectors.sync called with id
```

- [ ] **Step 2:** vitest run → FAIL. **Step 3:** implement. **Step 4:** `npx vitest run src/components/admin/AdminConnectorsPage.test.jsx` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(ui): Connectors admin list page + api client + sidebar entry"`

---

### Task 20: ConnectorDetailPage (Overview / Settings / Authorization / Pages) + delete modal

**Model:** sonnet

**Files:**
- Create: `wikantik-frontend/src/components/admin/ConnectorDetailPage.jsx` + `.test.jsx`
- Create: `wikantik-frontend/src/components/admin/ConnectorSettingsForm.jsx` + `.test.jsx` (shared with the wizard, Task 22)
- Modify: `wikantik-frontend/src/main.jsx` — route `connectors/:id` → `ConnectorDetailPage`

**Interfaces:**
- Consumes: `api.connectors.get/update/remove/sync/runs/pages/testSaved/listCredentials/setCredential/deleteCredential`; `CONNECTOR_TYPES` field metadata from Task 21 (`connectorGuides.js`) — **build order note:** Task 21 has no dependencies; dispatch it before or alongside this task.
- Produces: `ConnectorSettingsForm({ type, initialValues, onSubmit, submitLabel, errors })` — renders the per-type fields from `CONNECTOR_TYPES[type].fields` (text/number/bool/list inputs; `list` = one-per-line textarea serialized to array) plus the common fields (`syncIntervalHours` number, `cluster` text, `defaultTags` text, `pagePrefix` text, `enabled` checkbox); `errors` is the server's field-keyed 422 map rendered under matching inputs (`data-testid="field-error-${name}"`). Used by Task 22.

**Page contract:** `useParams().id`; loads `get(id)`; four tabs (simple button-tabs, `data-testid="tab-overview|settings|authorization|pages"`).
- **Overview:** status strip (enabled, interval, lastRun, pageCount) + Sync Now + runs table from `runs(id)` — columns trigger/started/finished/status/created/updated/unchanged/deleted/failed; a `running` row whose `started` is older than 1h renders status as `interrupted`; failed rows render `error` in an expandable `<details>` block.
- **Settings:** `ConnectorSettingsForm` with `initialValues` from the detail payload; on submit → `update(id, body)`; 422 → pass `errors` down; 409 (properties origin) is pre-empted: when `origin === 'properties'` render the form read-only with an "Import to database" button (`importFromProperties(id)` then reload — after import the form unlocks) and the note "Defined in wikantik-custom.properties — import to edit here. For Google Drive, re-enter the client secret under Authorization after importing."
- **Authorization:** for each secret name of the type (from `CONNECTOR_TYPES[type].secrets`): row with set/unset state (from detail `secretsSet`), a password input + Save (`setCredential`), Delete. gdrive additionally: consent state = `secretsSet` contains `refresh_token` → "Authorized ✓" else "Not authorized"; an "Authorize with Google" link: `href={`${base}/admin/connector-oauth/gdrive/${id}/authorize?return_to=${encodeURIComponent(`/admin/connectors/${id}?oauth_return=1`)}`}` (plain `<a>` — it's a top-level navigation); on mount, if `location.search` contains `oauth=ok` show a success toast/inline note, `oauth=<error>` show the error inline. Types with no secrets render "This connector type needs no credentials."
- **Pages:** table from `pages(id)` — pageName (link `/wiki/${pageName}`), sourceUri, lastSynced.
- **Delete:** header Danger button → modal: fetches `pages(id)` count → "This connector created N pages. They will be kept and marked 'no longer syncing'." + checkbox `data-testid="delete-pages-checkbox"` "Also delete all N derived pages" — when checked, require typing the connector id into a confirm input before enabling the destructive button. Calls `remove(id, checked)` → navigate back to `/admin/connectors`.

- [ ] **Step 1: Failing tests** (mock api module; use `MemoryRouter initialEntries={['/admin/connectors/gh']}` + `Routes`):

```jsx
it('renders overview with runs and interrupted render', ...);
it('settings tab shows read-only + import for properties origin', ...);
it('settings submit passes 422 errors to fields', ...);
it('authorization tab saves a secret and shows set state', ...);
it('gdrive authorization renders authorize link with return_to', ...);
it('delete modal gates page deletion behind checkbox + typed id', ...);   // remove called with deletePages=true only after both
```

Plus `ConnectorSettingsForm.test.jsx`: renders fields from type metadata; list field serializes lines→array; error map renders under the right field.

- [ ] **Step 2–4:** fail → implement → `npx vitest run src/components/admin/ConnectorDetailPage.test.jsx src/components/admin/ConnectorSettingsForm.test.jsx` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(ui): connector detail page — overview/settings/authorization/pages + gated delete"`

---

### Task 21: connectorGuides.js — type metadata + walkthrough content

**Model:** haiku (pure content + shape; no logic)

**Files:**
- Create: `wikantik-frontend/src/components/admin/connectorGuides.js`
- Test: `wikantik-frontend/src/components/admin/connectorGuides.test.js`

**Interfaces (consumed by Tasks 20, 22):**

```js
export const CONNECTOR_TYPES = {
  webcrawler: {
    label: 'Web crawler', icon: '🕸',
    blurb: 'Crawl a website by following links from seed URLs.',
    goodFor: 'Documentation sites and blogs without a sitemap or feed.',
    secrets: [],                       // credential names, in wizard order
    fields: [                          // rendered by ConnectorSettingsForm, in order
      { name: 'seeds', type: 'list', label: 'Seed URLs', required: true,
        help: 'One URL per line. The crawl starts here and follows same-host links.' },
      { name: 'path_prefix', type: 'text', label: 'Path prefix', help: 'Only crawl URLs whose path starts with this (e.g. /docs/).' },
      { name: 'max_pages', type: 'number', label: 'Max pages', default: 100 },
      { name: 'max_depth', type: 'number', label: 'Max link depth', default: 3 },
      { name: 'delay_ms', type: 'number', label: 'Delay between fetches (ms)', default: 1000 },
      { name: 'respect_robots', type: 'bool', label: 'Respect robots.txt', default: true },
      { name: 'same_host_only', type: 'bool', label: 'Stay on the seed host', default: true },
    ],
    authGuide: null,
    expectations: 'The first sync fetches up to your max-pages cap and creates one wiki page per crawled page…',
  },
  sitemap: { …, fields: sitemap_urls(list, required) / max_pages(500) / delay_ms / respect_robots / same_host_only },
  feed: { …, fields: feed_urls(list, required) / max_items(100) / fetch_full_articles(bool, true,
      help: 'Fetch each linked article page for full content; off = use the summary embedded in the feed.') / delay_ms / respect_robots / same_host_only },
  github: {
    …, secrets: ['token'],
    fields: repo(text, required, help: 'owner/name, e.g. jakefearsd/wikantik') / branch(text, help: 'Blank = default branch')
      / path_prefix(text, help: 'Only sync files under this path, e.g. docs/') / max_files(number, 500),
    authGuide: {
      secretName: 'token', optionalNote: 'Public repositories work without a token.',
      steps: [
        'Open GitHub → Settings → Developer settings → Fine-grained personal access tokens.',
        'Generate new token; under Repository access choose Only select repositories and pick just this repo.',
        'Under Permissions → Repository permissions set Contents: Read-only. Grant nothing else.',
        'Set an expiration you can live with (you will paste a fresh token here when it expires).',
        'Generate and copy the token now — GitHub shows it only once.',
      ],
    },
    expectations: 'The first sync walks the repository tree and creates one wiki page per markdown file…',
  },
  confluence: {
    …, secrets: ['api_token'],
    fields: base_url(text, required, help: 'https://your-site.atlassian.net/wiki') / space_key(text, required) / email(text, required,
      help: 'The Atlassian account email the API token belongs to.') / max_pages(number, 500),
    authGuide: {
      secretName: 'api_token',
      steps: [
        'Open id.atlassian.com → Account settings → Security → Create and manage API tokens.',
        'Create API token; give it a label like "wikantik sync".',
        'Copy the token — Atlassian shows it only once.',
        'The token authenticates together with your account email (Basic auth) — enter that email in the Source step.',
      ],
    },
    expectations: 'The first sync lists every page in the space and creates one wiki page per Confluence page…',
  },
  gdrive: {
    …, secrets: ['client_secret', 'refresh_token'],       // refresh_token is set by the consent flow, not typed
    fields: folder_ids(list, required, help: 'Folder IDs from the Drive URL: drive.google.com/drive/folders/<THIS>')
      / max_files(number, 500) / export_mime(text, default 'text/markdown') / redirect_uri(text,
      help: 'Leave blank to use this wiki\'s callback URL — you will register it in Google Cloud in the next step.'),
    authGuide: {
      secretName: 'client_secret',
      steps: [
        'Open console.cloud.google.com → create (or pick) a project.',
        'APIs & Services → Enable APIs → enable the Google Drive API.',
        'APIs & Services → OAuth consent screen → External → fill the minimum fields → add yourself as a test user.',
        'APIs & Services → Credentials → Create credentials → OAuth client ID → Web application.',
        'Add the redirect URI shown below exactly as printed, then create.',
        'Copy the Client ID into the Source step and paste the Client secret here.',
        'After saving both, click Authorize with Google — you will be sent to Google\'s consent screen and back here.',
      ],
    },
    expectations: 'After authorization, the first sync walks the folders, exports Google Docs as markdown…',
  },
};
export const TYPE_ORDER = ['webcrawler', 'sitemap', 'feed', 'github', 'confluence', 'gdrive'];
```

Write the FULL content for every type (the `…` above are for plan brevity ONLY — the file itself must be complete: every field object fully written with name/type/label/help/default/required, every expectations string 2–4 full sentences covering: how many pages to expect (cap), page naming (`<Prefix><flattened-source-path>`), cluster/tags placement, "bodies are machine-managed — edits are overwritten on the next sync; frontmatter curation is preserved", and "pages appear in search after the async index catches up, typically under a minute").

- [ ] **Step 1: Failing test:**

```js
import { CONNECTOR_TYPES, TYPE_ORDER } from './connectorGuides';
it('covers exactly the six UI types in order', () => {
  expect(TYPE_ORDER).toEqual(['webcrawler', 'sitemap', 'feed', 'github', 'confluence', 'gdrive']);
  expect(Object.keys(CONNECTOR_TYPES).sort()).toEqual([...TYPE_ORDER].sort());
});
it('every type has label, blurb, fields, expectations; secret types have authGuide steps', () => {
  for (const t of TYPE_ORDER) {
    const d = CONNECTOR_TYPES[t];
    expect(d.label).toBeTruthy();
    expect(d.fields.length).toBeGreaterThan(0);
    expect(d.expectations.length).toBeGreaterThan(80);
    if (d.secrets.length) expect(d.authGuide.steps.length).toBeGreaterThanOrEqual(3);
  }
});
it('required fields are flagged', () => {
  expect(CONNECTOR_TYPES.github.fields.find(f => f.name === 'repo').required).toBe(true);
});
```

- [ ] **Step 2–4:** fail → write the full content → `npx vitest run src/components/admin/connectorGuides.test.js` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(ui): connector type metadata + setup walkthrough content"`

---

### Task 22: AddConnectorWizard

**Model:** sonnet

**Files:**
- Create: `wikantik-frontend/src/components/admin/AddConnectorWizard.jsx` + `.test.jsx`
- Modify: `wikantik-frontend/src/main.jsx` — route `connectors/new` → `AddConnectorWizard`

**Interfaces:**
- Consumes: `CONNECTOR_TYPES`/`TYPE_ORDER` (T21), `ConnectorSettingsForm` (T20), `api.connectors.create/test/setCredential/sync` (T19).

**Wizard contract (state machine, no router sub-steps — one component, `step` state):**
0. **Type picker:** card per `TYPE_ORDER` entry (label, icon, blurb, goodFor); click → step 1.
1. **Source:** id input (`data-testid="connector-id"`, help: lowercase letters/digits/hyphens) + `ConnectorSettingsForm` (initialValues from field defaults) + instruction pane listing each field's `help`. Next validates locally: id regex `^[a-z0-9-]{1,64}$`, required fields non-empty → else inline errors, no server call.
2. **Authorize** (only when `type.secrets` includes a typeable secret): numbered `authGuide.steps` list + password input for the secret value (held in component state — NOT sent anywhere yet, since the connector doesn't exist). For gdrive additionally show the computed redirect URI in a copy-paste `<code>` block with a Copy button: value = `${window.location.origin}/admin/connector-oauth/gdrive/callback`. Note under it: "Register this exact URI in Google Cloud (step 5)."
3. **Test:** button "Run test" → `api.connectors.test({ type, config, credentials: secretValue ? { [secretName]: secretValue } : undefined })`; render `TestResult`: ok → green summary (`found N item(s); first: <sample>`), fail → red block with `message` + "Check your settings in step 1" hint. 422 → jump errors back into step 1's form. Skippable: "Skip test" link with warning text. gdrive: this step instead shows "Google Drive is tested after authorization — finish the wizard, then use Authorize with Google on the connector's Authorization tab." (test button hidden).
4. **Review & create:** summary table of everything + the type's `expectations` paragraph + what-happens-next block (naming/cluster/machine-managed/search-lag — from `expectations`). Buttons: **Save** → `create(body)` → then if a secret was typed, `setCredential(id, secretName, value)` → navigate to `/admin/connectors/${id}` (gdrive: append `?next=authorize` and the detail page's Authorization tab note from Task 20 shows). **Save & sync now** → same then `sync(id)` fire-and-forget → navigate to detail Overview. Create-422 → back to step 1 with server errors.

Back/Next controls persist state across steps; no step loses entered data.

- [ ] **Step 1: Failing tests:**

```jsx
it('type picker shows six cards and advances', ...);
it('source step blocks next on missing required field', ...);
it('github flow: authorize step shows PAT steps and captures token', ...);
it('test step calls api with transient credentials and shows found count', ...);
it('failed test shows message and hint, allows skip', ...);
it('review shows expectations and create+setCredential+navigate on Save', ...);
it('gdrive: shows redirect URI copy block and skips live test', ...);
it('server 422 on create returns to source step with field error', ...);
```

- [ ] **Step 2–4:** fail → implement → `npx vitest run src/components/admin/AddConnectorWizard.test.jsx` → PASS. Then run the whole admin UI suite: `npx vitest run src/components/admin/` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(ui): guided Add Connector wizard (type picker -> source -> authorize -> test -> review)"`

---

### Task 23: Wizard/OAuth loose ends — detail-page deep link + list-page import affordance

**Model:** sonnet

**Files:**
- Modify: `wikantik-frontend/src/components/admin/ConnectorDetailPage.jsx` (+test) — honor `?next=authorize` (open Authorization tab on mount) and `?oauth=…` (already from Task 20 — verify both compose)
- Modify: `wikantik-frontend/src/components/admin/AdminConnectorsPage.jsx` (+test) — properties-origin rows get an "Import" button (`importFromProperties` + reload) next to Sync Now, and a row-level tooltip "Defined in wikantik-custom.properties"

- [ ] **Step 1: Failing tests:** `it('opens authorization tab when ?next=authorize', ...)`; `it('import button shows only for properties origin and calls import', ...)`.
- [ ] **Step 2–4:** fail → implement → `npx vitest run src/components/admin/` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(ui): oauth deep-link + one-click import for properties connectors"`

---

## Phase E — verification

### Task 24: Cargo IT — connector admin round-trip

**Model:** sonnet

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/…/ConnectorAdminIT.java` (place beside the existing REST ITs — find the package with `ls wikantik-it-tests/wikantik-it-test-rest/src/test/java/**/`; follow the login/seed helpers the neighbors use, incl. `RestSeedHelper.awaitAdminReady` after login)

**Scenario (single IT class, ordered):** against the Cargo-launched instance as admin:
1. `GET /admin/connectors` → 200; note `credentialStoreEnabled` (IT env may not configure a crypto key — every credential assertion below is conditional on it).
2. `POST /admin/connectors` with a **feed** connector (no secrets, no egress needed for CRUD): `{ "id":"it-feed", "type":"feed", "syncIntervalHours":0, "config": { "feed_urls":["https://localhost:1/nope.xml"], "fetch_full_articles": false } }` → 201; `GET /admin/connectors/it-feed` → config round-trips.
3. `PUT` changing `max_items` → 200 and reflected.
4. `POST /admin/connectors/it-feed/sync` → 200 with a SyncReport (the unreachable URL fails closed inside poll — never-throws holds) → `GET runs` → exactly 1 run, status `ok`, counts 0.
5. `POST /admin/connectors/test` with the same body → 200 `ok=false` (unreachable) — proves the dry-run path.
6. Validation: `POST` with `{"id":"Bad.Id"...}` → 422 `errors.connector_id`; github with bad repo → 422 `errors.repo`.
7. `DELETE /admin/connectors/it-feed?deletePages=false` → 200 `{pagesKept:0,...}`; `GET` → 404; list no longer contains it.
8. Anonymous client (no login): `GET /admin/connectors` → 401/403 (whatever `AdminAuthFilter` returns for the neighbors — assert consistently with an existing IT).

- [ ] **Step 1:** Write the IT (it will fail against current WAR until… actually all backend tasks are merged by now — it should pass; the "failing first" step here is running it before writing assertions is meaningless — write it complete).
- [ ] **Step 2:** Run: `mvn clean install -pl wikantik-it-tests/wikantik-it-test-rest -am -Pintegration-tests -fae -Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConnectorAdminIT` (the `-Dtest=ZZZ…` trick skips wikantik-main's unit tests; `clean` is required — stale WAR overlay causes false 404s). Expected: PASS.
- [ ] **Step 3: Commit** — `git commit -m "test(it): connector admin REST round-trip (CRUD/sync/runs/test/validation/authz)"`

---

### Task 25: Final verification + docs (main session — not a subagent)

- [ ] **Step 1:** Full unit reactor: `mvn clean install -DskipITs` (NO `-T` — provider flakes). Expected: BUILD SUCCESS, RAT clean (every new file has the ASF header).
- [ ] **Step 2:** Full IT reactor: `mvn clean install -Pintegration-tests -fae`. Known acceptable flake: `EditIT#createPageAndTestEditPermissions` (CodeMirror race) — re-run isolated before treating as regression.
- [ ] **Step 3:** Complexity gate: `mvn pmd:check -Pcomplexity-gate`.
- [ ] **Step 4:** Frontend suite: `cd wikantik-frontend && npx vitest run`.
- [ ] **Step 5:** Docs: update `CLAUDE.md` module table — wikantik-connectors line gains "DB-backed connector configs (V048) + sync-run history (V049), hot-apply via ConnectorConfigService"; `/admin/*` REST row count +1 surface; note the `wikantik.connectors.enabled` **default flip to true** in the release notes location the repo uses (check `docs/ProjectReference.md`). Mark the spec's status line "Approved design" → "Implemented <date>".
- [ ] **Step 6:** `superpowers:requesting-code-review` per repo convention, then commit docs: `git commit -m "docs: connector admin UI shipped — module map + release notes"`.

---

## Self-Review (performed at plan-writing time)

- **Spec coverage:** D1 (T1,7,9,11) · D2 (T4 secret-free config, T5 secret resolution, T13) · D3 (T7 delete, T9 stamper, T20 modal) · D4 (T7 interval, T8 scheduler) · D5 (T21,22) · D6 (T14–18) · D7 (T2,3,10,20) · D8 (T3,9,11,19) · D9 (T4 rejects filesystem; wiring keeps it properties-only) · D10 (T7 defaults, T15) · D11 (all six types in T4/T5/T21/T22). Audit (spec §5) → T11, T13. Test endpoints (§5) → T12. OAuth return_to (§5) → T13. Prereq banners (§6) → T19.
- **Known deliberate cuts:** no per-item sync results (spec out-of-scope), properties-origin connectors render config read-only from status alone (their parsed config is not round-tripped into the view except at import time), `/admin/connectors/{id}/status` kept for compat.
- **Type consistency spot-checks:** `ConnectorConfigRow` 8 components used consistently (T1↔T7); `RunRecorder` 3 methods (T2↔T3); `ContentDefaults` (T7↔T15); `SyncedItem` (T6↔T7↔T10); `TestResult` 5 fields (T12↔T22 rendering); secret names `token`/`api_token`/`client_secret`/`refresh_token` consistent across T4/T5/T13/T20/T21/T22.
