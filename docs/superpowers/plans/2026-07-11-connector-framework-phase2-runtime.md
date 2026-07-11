# Connector Framework Phase 2.1 (Runtime & Operations) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Phase-1 connector library live and operable — register a filesystem connector from config, trigger a sync and see status via `/admin/connectors/*`, and run syncs on a schedule.

**Architecture:** The runtime (`ConnectorRegistry`, `ConnectorRuntime`, scheduler, `ConnectorStatusReader`) lives in the `wikantik-connectors` module (`wikantik-api`-only deps). A thin `ConnectorWiringHelper` in `wikantik-main`'s existing `com.wikantik.derived` package builds everything from config at `WikiEngine` startup and registers the runtime as a manager. `ConnectorAdminResource` (`wikantik-rest`) exposes the admin surface. Default-off; no schema or Phase-1 SPI change.

**Tech Stack:** Java 25, Maven, JUnit 5 + Mockito, H2 (DAO tests), `ScheduledExecutorService`, servlet admin resources.

**Spec:** `docs/superpowers/specs/2026-07-11-connector-framework-phase2-runtime-design.md`

## Global Constraints

- **Invariant #6:** runtime types in `wikantik-connectors`; `wikantik-main` gains only `ConnectorWiringHelper` + a shared `DerivedIngestionServiceFactory` in the EXISTING `com.wikantik.derived` package (no new `wikantik-main` package); admin resource in `wikantik-rest`.
- **`wikantik-connectors` depends on `wikantik-api` only** (main scope). Runtime classes use only the Phase-1 in-module types + `wikantik-api`.
- **No schema change** — `ConnectorStatusReader` reads the existing `V046` tables. **No Phase-1 SPI change** — `SourceConnector`/`SyncOrchestrator`/`SyncStateStore`/`DerivedPageSink` are fixed.
- **Default-off:** `wikantik.connectors.enabled=false` by default ⇒ `ConnectorWiringHelper` is a no-op (no runtime registered). `wikantik.connectors.sync.interval.hours=0` ⇒ scheduler disabled.
- **Async invariant:** the scheduler runs off-thread (single-thread `ScheduledExecutorService`, exactly like `OntologyRebuildScheduler`). The manual trigger is synchronous by deliberate 2.1 scope.
- **Fail-closed / no swallowed exceptions:** every catch logs ≥ `LOG.warn` with context; one connector's sync failure never aborts the scheduler's other connectors.
- **Every module already has `mockito-core`** (test scope) — `wikantik-connectors` got it in Phase 1; no new module here.
- **TDD:** failing test first; run only the task's targeted test (`mvn test -pl <module> -Dtest=<Class>`), not the full suite. Controller runs the full build once at the end.

---

### Task 1: `ConnectorStatus` + `ConnectorStatusReader`

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorStatus.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorStatusReader.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/runtime/ConnectorStatusReaderTest.java`

**Interfaces:**
- Consumes: `javax.sql.DataSource`; the existing `connector_sync_state` + `connector_synced_item` tables (V046).
- Produces: `ConnectorStatus(String connectorId, String connectorType, String lastRun, String lastStatus, int syncedItemCount)`; `ConnectorStatusReader(DataSource ds)` with `ConnectorStatus read(String connectorId, String connectorType)` — reads `last_run`/`status` from `connector_sync_state` (nulls if the row is absent) and `count(*)` from `connector_synced_item`.

- [ ] **Step 1: Write the failing test** (H2, mirrors `JdbcSyncStateStoreTest` setup):
```java
package com.wikantik.connectors.runtime;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.junit.jupiter.api.Assertions.*;

class ConnectorStatusReaderTest {

    private DataSource ds;

    @BeforeEach void schema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:connstatus;DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        ds = h2;
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_sync_state (connector_id VARCHAR PRIMARY KEY, cursor VARCHAR, last_run TIMESTAMP WITH TIME ZONE, status VARCHAR)" );
            s.execute( "CREATE TABLE IF NOT EXISTS connector_synced_item (connector_id VARCHAR NOT NULL, source_uri VARCHAR NOT NULL, content_hash VARCHAR NOT NULL, page_name VARCHAR NOT NULL, acl_refs VARCHAR NOT NULL DEFAULT '[]', first_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), last_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), PRIMARY KEY (connector_id, source_uri))" );
            s.execute( "INSERT INTO connector_sync_state (connector_id, cursor, last_run, status) VALUES ('fs1','cur','2026-07-11T10:00:00Z','ok')" );
            s.execute( "INSERT INTO connector_synced_item (connector_id, source_uri, content_hash, page_name) VALUES ('fs1','file:a.md','h','A'),('fs1','file:b.md','h2','B')" );
        }
    }

    @Test void readsLastRunStatusAndItemCount() {
        ConnectorStatus st = new ConnectorStatusReader( ds ).read( "fs1", "filesystem" );
        assertEquals( "fs1", st.connectorId() );
        assertEquals( "filesystem", st.connectorType() );
        assertEquals( "ok", st.lastStatus() );
        assertNotNull( st.lastRun() );
        assertEquals( 2, st.syncedItemCount() );
    }

    @Test void neverRunConnectorHasNullsAndZeroCount() {
        ConnectorStatus st = new ConnectorStatusReader( ds ).read( "never", "filesystem" );
        assertNull( st.lastRun() );
        assertNull( st.lastStatus() );
        assertEquals( 0, st.syncedItemCount() );
    }
}
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=ConnectorStatusReaderTest -q`).

- [ ] **Step 3: Implement** (Apache header on both files). `ConnectorStatus` is a plain record. `ConnectorStatusReader` uses the `PageVerificationDao`/`JdbcSyncStateStore` JDBC idiom (`try (Connection c = ds.getConnection())`, `LOG.warn` on `SQLException`, safe defaults):
```java
package com.wikantik.connectors.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javax.sql.DataSource;
import java.sql.*;

/** Read-only status from the V046 sync-state tables. No schema change; no Phase-1 SPI change. */
public final class ConnectorStatusReader {

    private static final Logger LOG = LogManager.getLogger( ConnectorStatusReader.class );
    private final DataSource ds;

    public ConnectorStatusReader( final DataSource ds ) { this.ds = ds; }

    public ConnectorStatus read( final String connectorId, final String connectorType ) {
        String lastRun = null, status = null;
        int count = 0;
        try ( Connection c = ds.getConnection() ) {
            try ( PreparedStatement ps = c.prepareStatement(
                    "SELECT last_run, status FROM connector_sync_state WHERE connector_id=?" ) ) {
                ps.setString( 1, connectorId );
                try ( ResultSet rs = ps.executeQuery() ) {
                    if ( rs.next() ) {
                        final Timestamp ts = rs.getTimestamp( 1 );
                        lastRun = ts == null ? null : ts.toInstant().toString();
                        status = rs.getString( 2 );
                    }
                }
            }
            try ( PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM connector_synced_item WHERE connector_id=?" ) ) {
                ps.setString( 1, connectorId );
                try ( ResultSet rs = ps.executeQuery() ) { if ( rs.next() ) count = rs.getInt( 1 ); }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "ConnectorStatusReader failed for '{}': {}", connectorId, e.getMessage() );
        }
        return new ConnectorStatus( connectorId, connectorType, lastRun, status, count );
    }
}
```
```java
package com.wikantik.connectors.runtime;
/** Operable status of one connector (derived from the V046 sync-state tables). */
public record ConnectorStatus(
    String connectorId, String connectorType, String lastRun, String lastStatus, int syncedItemCount ) {}
```

- [ ] **Step 4: Run — PASS**. **Step 5: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorStatus.java wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorStatusReader.java wikantik-connectors/src/test/java/com/wikantik/connectors/runtime/ConnectorStatusReaderTest.java
git commit -m "feat(connectors): ConnectorStatus + ConnectorStatusReader (reads V046 tables, no schema change)"
```

---

### Task 2: `ConnectorRegistry` + `ConnectorRuntime` (with scheduler)

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorRegistry.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorRuntime.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/runtime/ConnectorRuntimeTest.java`

**Interfaces:**
- Consumes: `SourceConnector`, `SyncReport` (Phase 1); `SyncOrchestrator` (Phase 1, `sync(SourceConnector)→SyncReport`); `ConnectorStatusReader` (T1).
- Produces:
  - `ConnectorRegistry(Map<String,SourceConnector> byId, Map<String,String> typeById)` with `Optional<SourceConnector> get(String id)`, `Set<String> ids()`, `String typeOf(String id)`.
  - `ConnectorRuntime(ConnectorRegistry registry, SyncOrchestrator orchestrator, ConnectorStatusReader statusReader)` with:
    - `SyncReport syncNow(String id)` — `IllegalArgumentException` if id unknown; else `orchestrator.sync(registry.get(id))`.
    - `ConnectorStatus status(String id)` — `statusReader.read(id, registry.typeOf(id))`.
    - `List<ConnectorStatus> list()` — one per `registry.ids()`.
    - `void startScheduler(long intervalHours)` — `≤0` ⇒ no-op + `LOG.info` disabled; else a single-thread `ScheduledExecutorService` (thread name `wikantik-connector-sync-scheduler`) running `scheduleAtFixedRate(this::syncAll, interval, interval, HOURS)`. `syncAll` iterates `registry.ids()` calling `syncNow`, each wrapped in try/catch(`LOG.warn`) so one failure never aborts the rest.
    - `void stop()` — `executor.shutdownNow()` if started.

- [ ] **Step 1: Write the failing test** (fakes; no DB, no threads for the core assertions — the scheduler-disabled path is asserted directly):
```java
package com.wikantik.connectors.runtime;

import com.wikantik.api.connectors.*;
import com.wikantik.connectors.SyncOrchestrator;
import com.wikantik.connectors.SyncReport;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectorRuntimeTest {

    private static SourceConnector connector( String id ) {
        return new SourceConnector() {
            public String connectorId() { return id; }
            public SyncBatch poll( SyncCursor c ) {
                return new SyncBatch( List.of(), List.of(), new SyncCursor( "done" ), true );
            }
        };
    }

    private static ConnectorRuntime runtime( DataSource ds, SourceConnector... cs ) {
        Map<String,SourceConnector> byId = new LinkedHashMap<>();
        Map<String,String> typeById = new LinkedHashMap<>();
        for ( SourceConnector c : cs ) { byId.put( c.connectorId(), c ); typeById.put( c.connectorId(), "filesystem" ); }
        ConnectorRegistry reg = new ConnectorRegistry( byId, typeById );
        // A real orchestrator over fake state + fake sink so syncNow returns a real SyncReport.
        SyncStateStore store = mock( SyncStateStore.class );
        when( store.loadCursor( anyString() ) ).thenReturn( Optional.empty() );
        when( store.syncedHash( anyString(), anyString() ) ).thenReturn( Optional.empty() );
        when( store.knownUris( anyString() ) ).thenReturn( List.of() );
        DerivedPageSink sink = mock( DerivedPageSink.class );
        SyncOrchestrator orch = new SyncOrchestrator( store, sink );
        return new ConnectorRuntime( reg, orch, new ConnectorStatusReader( ds ) );
    }

    @Test void syncNowRunsRegisteredConnector() {
        // ds unused by syncNow; pass a mock
        SyncReport r = runtime( mock( DataSource.class ), connector( "fs1" ) ).syncNow( "fs1" );
        assertNotNull( r );          // empty fixture → 0 of everything, but a real report
        assertEquals( 0, r.created() + r.updated() + r.unchanged() + r.deleted() + r.failed() );
    }

    @Test void syncNowUnknownIdThrows() {
        assertThrows( IllegalArgumentException.class,
            () -> runtime( mock( DataSource.class ), connector( "fs1" ) ).syncNow( "nope" ) );
    }

    @Test void schedulerDisabledWhenIntervalNonPositive() {
        ConnectorRuntime rt = runtime( mock( DataSource.class ), connector( "fs1" ) );
        rt.startScheduler( 0 );      // must NOT start a thread
        assertFalse( rt.isSchedulerRunning() );
        rt.stop();                   // safe even when never started
    }

    @Test void schedulerStartsWhenIntervalPositive() {
        ConnectorRuntime rt = runtime( mock( DataSource.class ), connector( "fs1" ) );
        rt.startScheduler( 24 );
        assertTrue( rt.isSchedulerRunning() );
        rt.stop();
        assertFalse( rt.isSchedulerRunning() );
    }
}
```
> Add a package-private/`public boolean isSchedulerRunning()` to `ConnectorRuntime` for the test (returns `executor != null && !executor.isShutdown()`). This is a legitimate observable, not test-only cruft.

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=ConnectorRuntimeTest -q`).

- [ ] **Step 3: Implement** (Apache headers). `ConnectorRegistry`:
```java
package com.wikantik.connectors.runtime;

import com.wikantik.api.connectors.SourceConnector;
import java.util.*;

/** Immutable id → connector registry, built once at wiring time. */
public final class ConnectorRegistry {
    private final Map< String, SourceConnector > byId;
    private final Map< String, String > typeById;
    public ConnectorRegistry( final Map< String, SourceConnector > byId, final Map< String, String > typeById ) {
        this.byId = Map.copyOf( byId );
        this.typeById = Map.copyOf( typeById );
    }
    public Optional< SourceConnector > get( final String id ) { return Optional.ofNullable( byId.get( id ) ); }
    public Set< String > ids() { return byId.keySet(); }
    public String typeOf( final String id ) { return typeById.getOrDefault( id, "unknown" ); }
}
```
`ConnectorRuntime` (scheduler shape copied from `OntologyRebuildScheduler`):
```java
package com.wikantik.connectors.runtime;

import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.connectors.SyncOrchestrator;
import com.wikantik.connectors.SyncReport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Operable facade over the Phase-1 sync stack: manual trigger, status, and a periodic scheduler. */
public final class ConnectorRuntime {

    private static final Logger LOG = LogManager.getLogger( ConnectorRuntime.class );

    private final ConnectorRegistry registry;
    private final SyncOrchestrator orchestrator;
    private final ConnectorStatusReader statusReader;
    private ScheduledExecutorService executor;

    public ConnectorRuntime( final ConnectorRegistry registry, final SyncOrchestrator orchestrator,
                             final ConnectorStatusReader statusReader ) {
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.statusReader = statusReader;
    }

    public SyncReport syncNow( final String connectorId ) {
        final SourceConnector c = registry.get( connectorId ).orElseThrow(
            () -> new IllegalArgumentException( "unknown connector: " + connectorId ) );
        return orchestrator.sync( c );
    }

    public ConnectorStatus status( final String connectorId ) {
        if ( registry.get( connectorId ).isEmpty() ) {
            throw new IllegalArgumentException( "unknown connector: " + connectorId );
        }
        return statusReader.read( connectorId, registry.typeOf( connectorId ) );
    }

    public List< ConnectorStatus > list() {
        final List< ConnectorStatus > out = new ArrayList<>();
        for ( final String id : registry.ids() ) out.add( statusReader.read( id, registry.typeOf( id ) ) );
        return out;
    }

    public synchronized void startScheduler( final long intervalHours ) {
        if ( intervalHours <= 0 ) {
            LOG.info( "connector sync scheduler disabled (interval={}h)", intervalHours );
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-connector-sync-scheduler" );
            t.setDaemon( true );
            return t;
        } );
        executor.scheduleAtFixedRate( this::syncAll, intervalHours, intervalHours, TimeUnit.HOURS );
        LOG.info( "connector sync scheduler started (every {}h, {} connectors)", intervalHours, registry.ids().size() );
    }

    private void syncAll() {
        for ( final String id : registry.ids() ) {
            try {
                final SyncReport r = syncNow( id );
                LOG.info( "scheduled connector sync '{}': {}", id, r );
            } catch ( final RuntimeException e ) {
                LOG.warn( "scheduled connector sync '{}' failed: {}", id, e.getMessage() );
            }
        }
    }

    public boolean isSchedulerRunning() { return executor != null && !executor.isShutdown(); }

    public synchronized void stop() {
        if ( executor != null ) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
```

- [ ] **Step 4: Run — PASS**. **Step 5: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorRegistry.java wikantik-connectors/src/main/java/com/wikantik/connectors/runtime/ConnectorRuntime.java wikantik-connectors/src/test/java/com/wikantik/connectors/runtime/ConnectorRuntimeTest.java
git commit -m "feat(connectors): ConnectorRuntime + registry + single-thread sync scheduler"
```

---

### Task 3: Extract `DerivedIngestionServiceFactory` (shared real-wiki wiring)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/derived/DerivedIngestionServiceFactory.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/DerivedIngestResource.java:196-242` (`buildService()` delegates to the factory)
- Test: `wikantik-main/src/test/java/com/wikantik/derived/DerivedIngestionServiceFactoryTest.java`

**Why:** both `DerivedIngestResource` (existing) and the new `ConnectorWiringHelper` (Task 4) need a `DerivedPageIngestionService` wired to the real wiki (page seams over `PageManager`/`AttachmentManager`/`PageSaveHelper`). Extract the seam-wiring once so the page-writer logic isn't duplicated.

**Interfaces:**
- Consumes: `Engine`, `PageManager`, `AttachmentManager`, `PageSaveHelper`, `Wiki.contents()`, `FrontmatterParser`, `SaveOptions`, `TikaSourceExtractor` (all `wikantik-api`/`wikantik-ingest`, already on `wikantik-main`'s classpath).
- Produces: `DerivedIngestionServiceFactory.build(Engine engine, PageManager pm, AttachmentManager am) → DerivedPageIngestionService` — the exact seams currently in `DerivedIngestResource.buildService()`.

- [ ] **Step 1: Write the failing test** (in-memory managers; assert a page written through the factory-built service round-trips `derived_from`):
```java
package com.wikantik.derived;
// (imports: Engine/PageManager/AttachmentManager mocks via Mockito, JUnit)
// Build the service via the factory with Mockito mocks for Engine/PageManager/AttachmentManager,
// stub pm.getPureText(...) to return null (page absent) and capture saveText via the PageSaveHelper path.
// Because PageSaveHelper is concrete, assert at the seam the factory exposes: call
// factory.build(engine, pm, am).ingest("body".getBytes(), "Doc", "text/markdown",
//   new IngestOptions(false, "sync", "file:x")) and verify pm.deletePage / storeAttachment / save interactions
// occur (i.e. the service is really wired, not null). Keep the assertion to "service built + a create path runs
// without throwing and calls the page manager" — the detailed derived_from behavior is already covered by
// DerivedPageIngestionServiceTest.
```
> Implementer: model this test on the existing `DerivedIngestResourceTest` setup (it already mocks these managers). The goal is a characterization that the factory produces a working service; keep it small. If mocking `PageSaveHelper` is awkward, assert the factory returns a non-null service and that a delete flows to `pm.deletePage(...)` (the simplest real seam).

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-main -Dtest=DerivedIngestionServiceFactoryTest -q`).

- [ ] **Step 3: Implement the factory** (move the seam lambdas verbatim from `DerivedIngestResource.buildService()` lines 202-241; Apache header):
```java
package com.wikantik.derived;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.spi.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.ingest.TikaSourceExtractor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.util.Optional;

/** Builds a {@link DerivedPageIngestionService} wired to the real wiki managers. Shared by the
 *  derived-ingest REST resource and the connector runtime so the page-seam wiring lives in one place. */
public final class DerivedIngestionServiceFactory {

    private static final Logger LOG = LogManager.getLogger( DerivedIngestionServiceFactory.class );

    private DerivedIngestionServiceFactory() {}

    public static DerivedPageIngestionService build( final Engine engine, final PageManager pm,
                                                     final AttachmentManager am ) {
        final PageSaveHelper saveHelper = new PageSaveHelper( engine, pm );

        final DerivedPageIngestionService.AttachmentStore attachmentStore = ( pageName, filename, bytes ) -> {
            final var att = Wiki.contents().attachment( engine, pageName, filename );
            am.storeAttachment( att, new ByteArrayInputStream( bytes ) );
        };
        final DerivedPageIngestionService.PageReader pageReader = pageName -> {
            try {
                final String text = pm.getPureText( pageName, WikiProvider.LATEST_VERSION );
                if ( text == null || text.isBlank() ) return Optional.empty();
                return Optional.of( FrontmatterParser.parse( text ).metadata() );
            } catch ( final Exception e ) {
                LOG.warn( "DerivedIngestionServiceFactory: could not read page '{}': {}", pageName, e.getMessage() );
                return Optional.empty();
            }
        };
        final DerivedPageIngestionService.PageWriter pageWriter = ( pageName, body, metadata, author ) -> {
            final SaveOptions opts = SaveOptions.builder()
                .metadata( metadata ).author( author ).replaceMetadata( true )
                .changeNote( "derived page — ingested from source document" ).build();
            saveHelper.saveText( pageName, body, opts );
        };
        final DerivedPageIngestionService.PageDeleter pageDeleter = pm::deletePage;

        return new DerivedPageIngestionService( new TikaSourceExtractor(), attachmentStore, pageReader, pageWriter, pageDeleter );
    }
}
```

- [ ] **Step 4: Refactor `DerivedIngestResource.buildService()`** to delegate (keep the method `protected` + same return):
```java
    protected DerivedPageIngestionService buildService() {
        return DerivedIngestionServiceFactory.build(
            getEngine(), getSubsystems().page().pages(), getSubsystems().page().attachments() );
    }
```
Remove the now-unused imports (`ByteArrayInputStream`, `FrontmatterParser`, `SaveOptions`, `WikiProvider`, `PageSaveHelper`, `TikaSourceExtractor`, `Wiki`) from `DerivedIngestResource` **only if** they are unused elsewhere in the file — check first.

- [ ] **Step 5: Run — PASS**, and prove the refactor didn't change behavior:
`mvn test -pl wikantik-main -Dtest=DerivedIngestionServiceFactoryTest -q` then
`mvn test -pl wikantik-rest -Dtest=DerivedIngestResourceTest -q`
Expected: both green (the existing 11 `DerivedIngestResourceTest` cases prove the delegation is behavior-preserving).

- [ ] **Step 6: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/derived/DerivedIngestionServiceFactory.java wikantik-main/src/test/java/com/wikantik/derived/DerivedIngestionServiceFactoryTest.java wikantik-rest/src/main/java/com/wikantik/rest/DerivedIngestResource.java
git commit -m "refactor(derived): extract DerivedIngestionServiceFactory (shared real-wiki wiring)"
```

---

### Task 4: `ConnectorWiringHelper` + `WikiEngine` startup + config keys

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (call the helper at startup, next to the ontology wiring ~line 1129)
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (register the config keys)
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java`

**Interfaces:**
- Consumes: `Properties`, `DataSource`, `Engine`, `PageManager`, `AttachmentManager`; `DerivedIngestionServiceFactory.build` (T3); `DerivedPageSinkAdapter` (Phase 1); `JdbcSyncStateStore` (Phase 1); `FilesystemSourceConnector` (Phase 1); `SyncOrchestrator` (Phase 1); `ConnectorRegistry`/`ConnectorRuntime`/`ConnectorStatusReader` (T1/T2).
- Produces: `ConnectorWiringHelper.wireConnectors(Engine engine, Properties props, DataSource ds, PageManager pm, AttachmentManager am) → Optional<ConnectorRuntime>`. When `wikantik.connectors.enabled != true` ⇒ returns `Optional.empty()` (no-op). Else: parse `wikantik.connectors.filesystem.<id>.root` keys into `FilesystemSourceConnector`s, build the sink + orchestrator + registry + runtime, `engine.setManager(ConnectorRuntime.class, runtime)`, `runtime.startScheduler(intervalHours)`, return it.
- Also expose a package-visible static `Map<String,String> filesystemRoots(Properties)` (parses the `*.root` keys → id→root) so the test can assert config parsing without a live engine.

- [ ] **Step 1: Write the failing test** (config parsing, the testable core — full wiring is covered by the IT in Task 6):
```java
package com.wikantik.derived;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class ConnectorWiringHelperTest {

    @Test void parsesFilesystemRootsById() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.filesystem.docs.root", "/data/docs" );
        p.setProperty( "wikantik.connectors.filesystem.wiki-src.root", "/data/wiki" );
        p.setProperty( "wikantik.connectors.filesystem.docs.ignored", "x" ); // non-.root key ignored
        Map< String, String > roots = ConnectorWiringHelper.filesystemRoots( p );
        assertEquals( 2, roots.size() );
        assertEquals( "/data/docs", roots.get( "docs" ) );
        assertEquals( "/data/wiki", roots.get( "wiki-src" ) );
    }

    @Test void disabledByDefaultReturnsEmpty() {
        // enabled flag absent → wireConnectors is a no-op. Pass nulls for collaborators it must not touch.
        assertTrue( ConnectorWiringHelper.wireConnectors( null, new Properties(), null, null, null ).isEmpty() );
    }

    @Test void noFilesystemRootsMeansEmptyMap() {
        assertTrue( ConnectorWiringHelper.filesystemRoots( new Properties() ).isEmpty() );
    }
}
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest -q`).

- [ ] **Step 3: Implement `ConnectorWiringHelper`** (Apache header):
```java
package com.wikantik.derived;

import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Engine;
import com.wikantik.connectors.SyncOrchestrator;
import com.wikantik.connectors.filesystem.FilesystemSourceConnector;
import com.wikantik.connectors.runtime.ConnectorRegistry;
import com.wikantik.connectors.runtime.ConnectorRuntime;
import com.wikantik.connectors.runtime.ConnectorStatusReader;
import com.wikantik.connectors.state.JdbcSyncStateStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** Thin startup wiring: builds the connector runtime from config and registers it. No-op unless
 *  {@code wikantik.connectors.enabled=true}. Lives in the existing derived package (invariant #6). */
public final class ConnectorWiringHelper {

    private static final Logger LOG = LogManager.getLogger( ConnectorWiringHelper.class );
    private static final String PREFIX = "wikantik.connectors.";

    private ConnectorWiringHelper() {}

    public static Optional< ConnectorRuntime > wireConnectors( final Engine engine, final Properties props,
            final DataSource ds, final PageManager pm, final AttachmentManager am ) {
        if ( !Boolean.parseBoolean( props.getProperty( PREFIX + "enabled", "false" ) ) ) {
            return Optional.empty();
        }
        final Map< String, String > roots = filesystemRoots( props );
        if ( roots.isEmpty() ) {
            LOG.info( "connectors enabled but no wikantik.connectors.filesystem.*.root configured — nothing to sync" );
            return Optional.empty();
        }
        final Map< String, SourceConnector > byId = new LinkedHashMap<>();
        final Map< String, String > typeById = new LinkedHashMap<>();
        for ( final Map.Entry< String, String > e : roots.entrySet() ) {
            byId.put( e.getKey(), new FilesystemSourceConnector( e.getKey(), Path.of( e.getValue() ) ) );
            typeById.put( e.getKey(), "filesystem" );
        }
        final DerivedPageIngestionService ingestion = DerivedIngestionServiceFactory.build( engine, pm, am );
        final DerivedPageSinkAdapter sink = new DerivedPageSinkAdapter( ingestion, pm::deletePage, "connector-sync" );
        final SyncOrchestrator orchestrator = new SyncOrchestrator( new JdbcSyncStateStore( ds ), sink );
        final ConnectorRuntime runtime = new ConnectorRuntime(
            new ConnectorRegistry( byId, typeById ), orchestrator, new ConnectorStatusReader( ds ) );

        engine.setManager( ConnectorRuntime.class, runtime );
        final long intervalHours = parseLong( props, "sync.interval.hours", 0L );
        runtime.startScheduler( intervalHours );
        LOG.info( "connector runtime wired: {} connector(s), scheduler interval {}h", roots.size(), intervalHours );
        return Optional.of( runtime );
    }

    /** id → root for every {@code wikantik.connectors.filesystem.<id>.root} key. Package-visible for testing. */
    static Map< String, String > filesystemRoots( final Properties props ) {
        final String p = PREFIX + "filesystem.";
        final Map< String, String > out = new LinkedHashMap<>();
        for ( final String key : props.stringPropertyNames() ) {
            if ( key.startsWith( p ) && key.endsWith( ".root" ) ) {
                final String id = key.substring( p.length(), key.length() - ".root".length() );
                if ( !id.isBlank() && !id.contains( "." ) ) out.put( id, props.getProperty( key ).trim() );
            }
        }
        return out;
    }

    private static long parseLong( final Properties props, final String suffix, final long def ) {
        try { return Long.parseLong( props.getProperty( PREFIX + suffix, String.valueOf( def ) ).trim() ); }
        catch ( final NumberFormatException e ) { return def; }
    }
}
```
> `engine.setManager(ConnectorRuntime.class, ...)` mirrors `OntologyWiringHelper`'s `engine.setManager(OntologyRebuildCoordinator.class, ...)`. If `DecompositionArchTest` enforces a `getManager` allow-list, add `ConnectorRuntime` the same way `OntologyRebuildCoordinator` is listed — grep the test for how it's declared.

- [ ] **Step 4: Wire it into `WikiEngine` startup.** Near the ontology wiring call (~line 1129), after the `DataSource ds`, `pageManager`, and attachment manager are available, add:
```java
        // Wire the connector runtime (default-off). Mirrors OntologyWiringHelper.
        try {
            com.wikantik.derived.ConnectorWiringHelper.wireConnectors(
                this, props, ds, pageManager, getAttachmentManager() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "connector runtime wiring failed (continuing without connectors): {}", e.getMessage() );
        }
```
> Confirm the exact accessors for the attachment manager + `DataSource` at that point in `WikiEngine` (grep near line 1129 for how `pageManager`/`ds` are named). Match them. The try/catch keeps a connector-wiring failure from breaking startup (fail-closed).

- [ ] **Step 5: Register config keys** in `wikantik-main/src/main/resources/ini/wikantik.properties` (near other `wikantik.*` runtime blocks):
```properties
# --- External source connectors (Phase 2.1 runtime, default OFF) ---
# When enabled, registers filesystem connectors from wikantik.connectors.filesystem.<id>.root
# keys and (optionally) runs periodic syncs. Manual trigger + status at /admin/connectors/*.
#wikantik.connectors.enabled = false
#wikantik.connectors.sync.interval.hours = 0
#wikantik.connectors.filesystem.docs.root = /data/docs
```

- [ ] **Step 6: Run — PASS** (`mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest -q`) and compile-check the module (`mvn -q -pl wikantik-main -am compile`).

- [ ] **Step 7: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java wikantik-main/src/main/java/com/wikantik/WikiEngine.java wikantik-main/src/main/resources/ini/wikantik.properties wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java
git commit -m "feat(connectors): ConnectorWiringHelper + WikiEngine startup wiring (default off)"
```

---

### Task 5: `ConnectorAdminResource` + web.xml registration

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/ConnectorAdminResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (servlet + mapping `/admin/connectors/*`)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/ConnectorAdminResourceTest.java`

**Interfaces:**
- Consumes: `ConnectorRuntime` (resolved via `getEngine().getManager(ConnectorRuntime.class)`, registered in Task 4); `SyncReport`, `ConnectorStatus`. Extends `RestServletBase` like `AdminDerivedResource`.
- Produces: the three endpoints. JSON via the same helper `AdminDerivedResource` uses (`sendJson`/`writeJson` — confirm the exact helper name in `RestServletBase`).

Routing (parse `request.getPathInfo()` after `/admin/connectors`):
- `GET  /admin/connectors` → `runtime.list()` → JSON array. Runtime absent (disabled) → `503` with a message.
- `POST /admin/connectors/{id}/sync` → `runtime.syncNow(id)` → `SyncReport` JSON. Unknown id (`IllegalArgumentException`) → `404`.
- `GET  /admin/connectors/{id}/status` → `runtime.status(id)` → `ConnectorStatus` JSON. Unknown id → `404`.

- [ ] **Step 1: Write the failing test** — model on `AdminDerivedResourceTest`; inject a stub `ConnectorRuntime` via a `getEngine().getManager` mock, drive `doGet`/`doPost` with mock request/response, assert status codes + body. (Read `AdminDerivedResourceTest` to copy the request/response mocking + engine-manager stubbing exactly.)
```java
// Skeleton — the implementer fills request/response mocking per AdminDerivedResourceTest:
// - GET /admin/connectors with a runtime having one connector → 200, body contains the connector id
// - POST /admin/connectors/fs1/sync → 200, body has created/updated/... fields
// - GET /admin/connectors/unknown/status → 404
// - GET /admin/connectors when runtime manager is absent → 503
```

- [ ] **Step 2: Run — FAIL**.

- [ ] **Step 3: Implement `ConnectorAdminResource`** (extends `RestServletBase`; resolve the runtime; route on path info; JSON via the base helper; every failure path `sendError` with a code; Apache header). Follow `AdminDerivedResource`'s structure for `doGet`/`doPost`, path parsing, and JSON serialization.

- [ ] **Step 4: Register the servlet** in `web.xml` (copy the `AdminDerivedResource` `<servlet>` + `<servlet-mapping>` blocks, change name/class/url-pattern):
```xml
    <servlet>
        <servlet-name>ConnectorAdminResource</servlet-name>
        <servlet-class>com.wikantik.rest.ConnectorAdminResource</servlet-class>
    </servlet>
    ...
    <servlet-mapping>
        <servlet-name>ConnectorAdminResource</servlet-name>
        <url-pattern>/admin/connectors/*</url-pattern>
    </servlet-mapping>
```

- [ ] **Step 5: Run — PASS** (`mvn test -pl wikantik-rest -Dtest=ConnectorAdminResourceTest -q`).

- [ ] **Step 6: Commit**
```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/ConnectorAdminResource.java wikantik-war/src/main/webapp/WEB-INF/web.xml wikantik-rest/src/test/java/com/wikantik/rest/ConnectorAdminResourceTest.java
git commit -m "feat(connectors): /admin/connectors admin resource (list, sync, status)"
```

---

### Task 6: Admin end-to-end integration test

**Files:**
- Test: `wikantik-it-tests/.../<the REST IT suite>/ConnectorAdminIT.java` (place beside the existing derived/admin REST ITs — find the package with `grep -rl "admin/derived" wikantik-it-tests/src`)
- Possibly modify: the IT module's test wiki config to set `wikantik.connectors.enabled=true` + a filesystem root pointing at a test-fixture dir the IT creates.

**Interfaces:**
- Consumes: the deployed wiki (Cargo + Postgres) with connectors enabled; the admin REST endpoints (Task 5); an authenticated admin session (reuse the IT's admin login helper — see `RestSeedHelper`/`awaitAdminReady` per the repo's IT conventions).

- [ ] **Step 1: Write the IT** proving the DoD end-to-end:
  - Arrange: a fixture directory (created by the test, or a checked-in fixture) with 2 files; the IT wiki configured with `wikantik.connectors.enabled=true` and `wikantik.connectors.filesystem.itfs.root=<fixture dir>`.
  - `POST /admin/connectors/itfs/sync` as admin → 200; body reports `created=2`.
  - Assert the two derived pages now exist (GET the page via the REST/content API; assert `derived_from` = the `file:` URI).
  - `GET /admin/connectors/itfs/status` → 200; `syncedItemCount=2`, `lastStatus` non-null.
  - `GET /admin/connectors` → array contains `itfs`.
  - A non-admin request to `POST /admin/connectors/itfs/sync` → 403 (AdminAuthFilter).
> Follow the existing derived/admin IT for Cargo config, admin auth, and page-existence assertions. If the IT wiki config is templated, add the two `wikantik.connectors.*` properties to that template; if a fixture dir must exist at container start, create it under the IT module's resources and point the root at it.

- [ ] **Step 2: Run the IT** (per the repo's IT invocation — `mvn clean install -Pintegration-tests -fae` for the relevant IT module, sequential, no `-T`). Iterate until green.

- [ ] **Step 3: Commit**
```bash
git add wikantik-it-tests/...
git commit -m "test(connectors): admin end-to-end IT — configured sync, derived pages, status, 403"
```

---

## Post-implementation (controller)

- Full reactor unit build: `mvn clean install -DskipITs` **with `WIKANTIK_*` env vars UNSET** (WikiTest counts them — a stray env inflates `Wiki.init` property count and fails `WikiTest.testWikiInit`; not a regression).
- The connector IT is the DoD gate: run the connector IT module under `-Pintegration-tests` (sequential).
- Whole-branch review (opus): invariant #6 (no new `wikantik-main` package — `ConnectorWiringHelper`/`DerivedIngestionServiceFactory` are in the existing `com.wikantik.derived`), dependency direction (`wikantik-connectors` main-scope deps still `wikantik-api` only), the `getManager` allow-list handling for `ConnectorRuntime`, default-off (disabled ⇒ no runtime, admin 503), the `DerivedIngestResource` refactor is behavior-preserving (its 11 tests green), and fail-closed startup wiring.

## Self-review notes

- **Spec coverage:** status reader (T1), runtime+scheduler (T2), shared ingestion wiring (T3), config+startup wiring (T4), admin surface (T5), end-to-end DoD (T6). All spec sections mapped.
- **Invariant #6:** runtime in `wikantik-connectors`; `wikantik-main` gains only `ConnectorWiringHelper` + `DerivedIngestionServiceFactory` in the existing `com.wikantik.derived` package.
- **No schema / no Phase-1 SPI change:** status from existing V046 tables; `SourceConnector`/`SyncOrchestrator`/`SyncStateStore`/`DerivedPageSink` untouched.
- **Default-off:** `wikantik.connectors.enabled=false` default ⇒ `wireConnectors` returns empty, no manager registered, admin resource returns 503.
- **Type consistency:** `ConnectorRuntime(registry, orchestrator, statusReader)`, `ConnectorStatus(connectorId, connectorType, lastRun, lastStatus, syncedItemCount)`, `wireConnectors(engine, props, ds, pm, am)`, `filesystemRoots(props)` identical across T1→T6.
