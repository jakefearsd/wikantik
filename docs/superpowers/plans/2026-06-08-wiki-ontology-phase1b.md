# Wiki Ontology — Phase 1b Implementation Plan (App Wiring + Admin Rebuild Endpoint)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. (This session runs it **directly/inline**.) Steps use checkbox (`- [ ]`).

**Goal:** Wire the Phase 1 materialization engine into the running application: construct a TDB2-backed `OntologyModelManager` at startup, materialize the A-Box from the live repositories, expose a `POST /admin/ontology/rebuild` admin endpoint, and rebuild-on-startup-if-empty.

**Architecture:** Mirror the existing **`ContentIndexRebuildService`** end-to-end — it is the exact working twin (async daemon-thread rebuild, `synchronized triggerRebuild()` with `Conflict`/`Disabled` semantics, registered via `setManager`, exposed through `PageGraphSubsystem.Services`, triggered by an `AdminContentResource`-style raw servlet under `AdminAuthFilter`). A new `OntologyRebuildService` owns the `OntologyModelManager` + the data adapters and performs the rebuild; an `OntologyWiringHelper` constructs + registers it from `WikiEngine.initKnowledgeGraph`.

**Tech Stack:** Java 21, Maven, JUnit 5 + Mockito (unit), Testcontainers (where DB-backed), Cargo (the admin-endpoint IT). `wikantik-ontology` (Phase 1), `wikantik-main`, `wikantik-rest`.

**Spec:** `docs/superpowers/specs/2026-06-08-wiki-ontology-design.md` (§3 architecture, §5 Phase 1: "startup-if-empty + /admin/ontology/rebuild").

---

## ⚠️ Risk: the ArchUnit decomposition freeze store

`DecompositionArchTest` (wikantik-main) enforces **R-4 `no_get_manager_anywhere`**: only `WikiEngine`, `*SubsystemFactory`, `*SubsystemBridge`, `*WiringHelper` may call `engine.getManager(...)`. New code MUST be named with one of those suffixes (this plan uses `OntologyWiringHelper` + the existing `PageGraphSubsystemFactory`/`Bridge`). Both R-2 and R-4 are **frozen** rules with on-disk stores under `wikantik-main/src/test/resources/archunit_store/`. Adding new `getManager` call sites in `PageGraphSubsystemFactory`/`Bridge` produces **new R-2 violations** that must be deliberately re-frozen (Task 7). Per `reference_archunit_store_prunes_on_failure`: **the store mutates even on red runs — `git checkout` the `archunit_store/` directory before every retry**, and only commit a store delta you intend.

**Lowest-churn rule of thumb followed here:** `OntologyWiringHelper` calls **only `setManager`** (never `getManager`) → it adds no ArchUnit violations itself. The only new `getManager` calls are in the factory + bridge (both already whitelisted by R-4 suffix), handled in Task 7.

---

## Decisions

- **One new managed type: `OntologyRebuildService`** (the coordinator). It privately owns the `OntologyModelManager`; the manager is not separately registered in Phase 1b (Phase 3/4 can add `coordinator.modelManager()` access or register it then).
- **Persistence dir:** `wikantik.ontology.tdb2.dir`, default `${wikantik.workDir}/ontology-tdb2` (the property is `Engine.PROP_WORKDIR = "wikantik.workDir"`, read via `engine.getWorkDir()`). If `workDir` is null, fall back to a temp dir + `LOG.warn` (don't fail boot).
- **Enable flag:** `wikantik.ontology.enabled` (default `true`). When false, the wiring helper skips construction and the endpoint returns 503 — mirrors `wikantik.rebuild.enabled`.
- **Async rebuild** on a daemon thread named `wikantik-ontology-rebuild`; `triggerRebuild()` is `synchronized`, returns a status snapshot immediately (202), throws `ConflictException` (already running) / `DisabledException`.
- **Startup-if-empty:** after registration, if the TDB2 dataset has zero named graphs, kick a rebuild on the daemon thread (non-blocking boot).
- **Exposure:** add `ontologyRebuildService` to `PageGraphSubsystem.Services`; `AdminOntologyResource` reads `getSubsystems().pageGraph().ontologyRebuildService()`.
- **Apache header:** every new `.java` gets the standard ASF header (copy from any existing source).

## File structure

```
wikantik-main/src/main/java/com/wikantik/ontology/runtime/
    OntologyRebuildService.java        coordinator: owns manager, async rebuild, state machine, status
    OntologyRebuildStatus.java         immutable status snapshot record (state, graphCount, lastError, timestamps)
    PageRecordBuilder.java             PageCanonicalIdsDao.Row + PageManager + FrontmatterParser -> List<PageRecord>
    OntologyWiringHelper.java          constructs manager+coordinator from props/ds; setManager; startup-if-empty
wikantik-main/.../WikiEngine.java                     (edit) typed-field maps + mgr field + SNAPSHOT_REBUILDERS + helper call
wikantik-main/.../pagegraph/subsystem/PageGraphSubsystem.java        (edit) add Services + Deps component
wikantik-main/.../pagegraph/subsystem/PageGraphSubsystemFactory.java (edit) pack ontologyRebuildService
wikantik-main/.../pagegraph/subsystem/PageGraphSubsystemBridge.java  (edit) repopulate it on rebuild
wikantik-main/src/test/resources/archunit_store/                     (edit) refreeze R-2
wikantik-rest/src/main/java/com/wikantik/rest/AdminOntologyResource.java   (new) POST /admin/ontology/rebuild, GET status
wikantik-war/src/main/webapp/WEB-INF/web.xml                         (edit) servlet + mapping
wikantik-it-tests/.../AdminOntologyRebuildIT.java                    (new) Cargo IT
```

`wikantik-main/pom.xml` must depend on `wikantik-ontology` (add it).

---

## Task 1: `wikantik-main` depends on `wikantik-ontology`

**Files:** Modify `wikantik-main/pom.xml`.

- [ ] **Step 1:** Add the dependency (after the existing `wikantik-api` / sibling-module dependencies):

```xml
    <dependency>
      <groupId>${project.groupId}</groupId>
      <artifactId>wikantik-ontology</artifactId>
      <version>${project.version}</version>
    </dependency>
```

- [ ] **Step 2: Verify the reactor still builds the dependency edge** (no cycle — `wikantik-ontology` depends only on `wikantik-api`):

Run: `mvn -o validate -pl wikantik-main -am`
Expected: BUILD SUCCESS (reactor orders `wikantik-ontology` before `wikantik-main`).

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/pom.xml
git commit -m "build(ontology): wikantik-main depends on wikantik-ontology

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `OntologyRebuildStatus` + `OntologyRebuildService` (coordinator)

**Files:**
- Create `wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyRebuildStatus.java`
- Create `wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyRebuildService.java`
- Test `wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyRebuildServiceTest.java`

The coordinator takes (a) the `OntologyModelManager`, (b) a `Supplier<List<KgNode>>`, (c) `Supplier<List<KgEdge>>`, (d) `Supplier<List<PageRecord>>`, and (e) an `enabled` flag — all injected (no `getManager`, so it's ArchUnit-neutral and unit-testable with in-memory data).

- [ ] **Step 1: Create `OntologyRebuildStatus.java`:**

```java
package com.wikantik.ontology.runtime;

/** Immutable snapshot of the ontology rebuild coordinator's state. */
public record OntologyRebuildStatus(
        String state,        // "IDLE" | "STARTING" | "RUNNING"
        boolean enabled,
        long graphCount,     // named graphs after the last successful rebuild (-1 if never)
        String lastError     // null when healthy
) {}
```

- [ ] **Step 2: Write the failing coordinator test** (`OntologyRebuildServiceTest.java`):

```java
package com.wikantik.ontology.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.projection.PageRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class OntologyRebuildServiceTest {

    private static final UUID N1 = UUID.fromString( "00000000-0000-0000-0000-0000000000e1" );

    private OntologyRebuildService coordinator( final OntologyModelManager mgr, final boolean enabled ) {
        final List< KgNode > nodes = List.of(
                new KgNode( N1, "X", "concept", null, Provenance.HUMAN_AUTHORED, Map.of(), null, null, "human", null ) );
        return new OntologyRebuildService( mgr, () -> nodes, List::of, List::of, enabled );
    }

    @Test
    void rebuildRunsAsyncAndMaterializesGraphs() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final OntologyRebuildService svc = coordinator( mgr, true );
        final OntologyRebuildStatus started = svc.triggerRebuild();
        assertEquals( "STARTING", started.state() );

        Awaitility.await().atMost( 5, TimeUnit.SECONDS )
                .until( () -> "IDLE".equals( svc.status().state() ) );
        assertTrue( mgr.namedGraphExists( Iris.entity( N1 ) ), "entity graph materialized after async rebuild" );
        assertEquals( 1L, svc.status().graphCount() );
    }

    @Test
    void secondTriggerWhileRunningConflicts() throws Exception {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        // A slow source so the first rebuild is still running when we trigger again.
        final OntologyRebuildService svc = new OntologyRebuildService( mgr,
                () -> { try { Thread.sleep( 300 ); } catch ( InterruptedException ignored ) { Thread.currentThread().interrupt(); } return List.of(); },
                List::of, List::of, true );
        svc.triggerRebuild();
        assertThrows( OntologyRebuildService.ConflictException.class, svc::triggerRebuild );
        Awaitility.await().atMost( 5, TimeUnit.SECONDS ).until( () -> "IDLE".equals( svc.status().state() ) );
    }

    @Test
    void disabledCoordinatorRefusesAndReportsDisabled() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final OntologyRebuildService svc = coordinator( mgr, false );
        assertThrows( OntologyRebuildService.DisabledException.class, svc::triggerRebuild );
        assertEquals( false, svc.status().enabled() );
    }
}
```

- [ ] **Step 3: Run — verify it fails** (`OntologyRebuildService` does not exist).

Run: `mvn -o -pl wikantik-main test -Dtest=OntologyRebuildServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILE FAILURE.

- [ ] **Step 4: Implement `OntologyRebuildService.java`:**

```java
package com.wikantik.ontology.runtime;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.OntologyRebuildService.State;
import com.wikantik.ontology.projection.PageRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Coordinates asynchronous full rebuilds of the materialized ontology, mirroring
 * {@link com.wikantik.admin.ContentIndexRebuildService}'s state/conflict/disabled semantics.
 * Collaborators are injected (no WikiEngine#getManager) so this is ArchUnit-neutral
 * and unit-testable with in-memory data.
 */
public final class OntologyRebuildService {

    private static final Logger LOG = LogManager.getLogger( OntologyRebuildService.class );

    public enum State { IDLE, STARTING, RUNNING }

    public static final class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ConflictException( final String msg ) { super( msg ); }
    }

    public static final class DisabledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DisabledException() { super( "ontology rebuild disabled by configuration" ); }
    }

    private final OntologyModelManager manager;
    private final Supplier< List< KgNode > > nodeSource;
    private final Supplier< List< KgEdge > > edgeSource;
    private final Supplier< List< PageRecord > > pageSource;
    private final boolean enabled;
    private final com.wikantik.ontology.OntologyRebuildService delegate = new com.wikantik.ontology.OntologyRebuildService();

    private final AtomicReference< State > state = new AtomicReference<>( State.IDLE );
    private volatile long graphCount = -1;
    private volatile String lastError;

    public OntologyRebuildService( final OntologyModelManager manager,
                                   final Supplier< List< KgNode > > nodeSource,
                                   final Supplier< List< KgEdge > > edgeSource,
                                   final Supplier< List< PageRecord > > pageSource,
                                   final boolean enabled ) {
        this.manager = manager;
        this.nodeSource = nodeSource;
        this.edgeSource = edgeSource;
        this.pageSource = pageSource;
        this.enabled = enabled;
    }

    public synchronized OntologyRebuildStatus triggerRebuild() {
        if ( !enabled ) {
            throw new DisabledException();
        }
        if ( !state.compareAndSet( State.IDLE, State.STARTING ) ) {
            throw new ConflictException( "ontology rebuild already in state " + state.get() );
        }
        final Thread t = new Thread( this::runRebuild, "wikantik-ontology-rebuild" );
        t.setDaemon( true );
        t.start();
        return status();
    }

    /** Kicks a rebuild only if the dataset is empty (startup self-heal). Returns true if started. */
    public synchronized boolean rebuildIfEmpty() {
        if ( !enabled ) {
            return false;
        }
        if ( manager.namedGraphCount() > 0 ) {
            return false;
        }
        try {
            triggerRebuild();
            return true;
        } catch ( final ConflictException e ) {
            LOG.warn( "rebuildIfEmpty: rebuild already running: {}", e.getMessage() );
            return false;
        }
    }

    private void runRebuild() {
        state.set( State.RUNNING );
        try {
            final int written = delegate.rebuild( manager, nodeSource.get(), edgeSource.get(), pageSource.get() );
            graphCount = written;
            lastError = null;
            LOG.info( "ontology rebuild complete: {} named graphs", written );
        } catch ( final RuntimeException e ) {
            lastError = e.getMessage();
            LOG.warn( "ontology rebuild failed: {}", e.getMessage(), e );
        } finally {
            state.set( State.IDLE );
        }
    }

    public OntologyRebuildStatus status() {
        return new OntologyRebuildStatus( state.get().name(), enabled, graphCount, lastError );
    }
}
```

> Note: this references `OntologyModelManager.namedGraphCount()` — a small addition to the Phase 1 manager. Add it in Step 5 below.

- [ ] **Step 5: Add `namedGraphCount()` to `OntologyModelManager`** (`wikantik-ontology`, in the Phase 1 file) — between `namedGraphExists` and `clearAbox`:

```java
    /** Number of named (A-Box) graphs currently in the dataset. */
    public long namedGraphCount() {
        dataset.begin( org.apache.jena.query.ReadWrite.READ );
        try {
            long n = 0;
            for ( final java.util.Iterator< String > it = dataset.listNames(); it.hasNext(); it.next() ) {
                n++;
            }
            return n;
        } finally {
            dataset.end();
        }
    }
```

(Re-run the Phase 1 module tests to confirm the addition is clean: `mvn -o -pl wikantik-ontology test`.)

- [ ] **Step 6: Run the coordinator test — verify it passes.**

Run: `mvn -o -pl wikantik-main test -Dtest=OntologyRebuildServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 3 tests pass (async materialize, conflict, disabled).

- [ ] **Step 7: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/OntologyModelManager.java \
        wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyRebuildStatus.java \
        wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyRebuildService.java \
        wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyRebuildServiceTest.java
git commit -m "feat(ontology): async rebuild coordinator (mirrors ContentIndexRebuildService)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `PageRecordBuilder` — repositories/frontmatter → List<PageRecord>

**Files:**
- Create `wikantik-main/src/main/java/com/wikantik/ontology/runtime/PageRecordBuilder.java`
- Test `wikantik-main/src/test/java/com/wikantik/ontology/runtime/PageRecordBuilderTest.java`

Reads `PageCanonicalIdsDao.findAll()` for canonical_id/slug/title/type/cluster, then enriches each with frontmatter (tags/summary/date/author) via `PageManager.getPureText` + `FrontmatterParser`. Injected `PageManager` + `PageCanonicalIdsDao` (mockable; no getManager).

- [ ] **Step 1: Write the failing test** (mock `PageManager`; use a real `PageCanonicalIdsDao` is hard without a DB, so inject a `Supplier<List<PageCanonicalIdsDao.Row>>` instead — keeps it pure):

```java
package com.wikantik.ontology.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.wikantik.api.managers.PageManager;
import com.wikantik.ontology.projection.PageRecord;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith( MockitoExtension.class )
class PageRecordBuilderTest {

    @Mock PageManager pageManager;

    @Test
    void buildsPageRecordsWithFrontmatterEnrichment() {
        final PageCanonicalIdsDao.Row row = new PageCanonicalIdsDao.Row(
                "01CANON0000000000000000001", "GraphDb", "Graph DB", "article", "graph-databases",
                Instant.EPOCH, Instant.EPOCH );
        final String pageText = """
                ---
                tags: [databases, nosql]
                summary: A page about graph databases
                date: 2026-03-14
                author: claude-code-researcher
                ---
                Body text.
                """;
        when( pageManager.getPureText( "GraphDb", -1 ) ).thenReturn( pageText );

        final PageRecordBuilder builder = new PageRecordBuilder( pageManager, () -> List.of( row ) );
        final List< PageRecord > records = builder.build();

        assertEquals( 1, records.size() );
        final PageRecord r = records.get( 0 );
        assertEquals( "01CANON0000000000000000001", r.canonicalId() );
        assertEquals( "GraphDb", r.slug() );
        assertEquals( "article", r.type() );
        assertEquals( "graph-databases", r.cluster() );
        assertEquals( List.of( "databases", "nosql" ), r.tags() );
        assertEquals( "A page about graph databases", r.summary() );
        assertEquals( "2026-03-14", r.isoDate() );
        assertEquals( "claude-code-researcher", r.author() );
    }

    @Test
    void toleratesMissingFrontmatter() {
        final PageCanonicalIdsDao.Row row = new PageCanonicalIdsDao.Row(
                "01CANON0000000000000000002", "Bare", "Bare", "article", null, Instant.EPOCH, Instant.EPOCH );
        when( pageManager.getPureText( "Bare", -1 ) ).thenReturn( "no frontmatter here" );
        final PageRecordBuilder builder = new PageRecordBuilder( pageManager, () -> List.of( row ) );
        final PageRecord r = builder.build().get( 0 );
        assertEquals( List.of(), r.tags() );
        assertEquals( null, r.summary() );
    }
}
```

> Confirm `PageManager.getPureText(String,int)` uses `-1`/`PageProvider.LATEST_VERSION`; if the constant differs, adjust the stub. Read `PageProvider.LATEST_VERSION` before finalizing.

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement `PageRecordBuilder.java`:**

```java
package com.wikantik.ontology.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.ontology.projection.PageRecord;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Builds {@link PageRecord}s from page_canonical_ids rows enriched with frontmatter. */
public final class PageRecordBuilder {

    private static final Logger LOG = LogManager.getLogger( PageRecordBuilder.class );

    private final PageManager pageManager;
    private final Supplier< List< PageCanonicalIdsDao.Row > > rowSource;

    public PageRecordBuilder( final PageManager pageManager,
                              final Supplier< List< PageCanonicalIdsDao.Row > > rowSource ) {
        this.pageManager = pageManager;
        this.rowSource = rowSource;
    }

    public List< PageRecord > build() {
        final List< PageRecord > out = new ArrayList<>();
        for ( final PageCanonicalIdsDao.Row row : rowSource.get() ) {
            Map< String, Object > md = Map.of();
            try {
                final String text = pageManager.getPureText( row.currentSlug(), PageProvider.LATEST_VERSION );
                if ( text != null ) {
                    final ParsedPage parsed = FrontmatterParser.parse( text );
                    md = parsed.metadata();
                }
            } catch ( final RuntimeException e ) {
                LOG.warn( "frontmatter parse failed for {}: {}", row.currentSlug(), e.getMessage() );
            }
            out.add( new PageRecord(
                    row.canonicalId(), row.currentSlug(), row.title(), row.type(), row.cluster(),
                    tags( md.get( "tags" ) ), str( md.get( "summary" ) ), str( md.get( "date" ) ),
                    str( md.get( "author" ) ) ) );
        }
        return out;
    }

    private static List< String > tags( final Object raw ) {
        if ( raw instanceof List< ? > list ) {
            return list.stream().map( Object::toString ).collect( Collectors.toList() );
        }
        return List.of();
    }

    private static String str( final Object raw ) {
        return raw == null ? null : raw.toString();
    }
}
```

- [ ] **Step 4: Run — verify it passes** (2 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/PageRecordBuilder.java \
        wikantik-main/src/test/java/com/wikantik/ontology/runtime/PageRecordBuilderTest.java
git commit -m "feat(ontology): PageRecordBuilder (canonical_ids + frontmatter -> PageRecord)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: `OntologyWiringHelper` — construct + register (setManager only)

**Files:**
- Create `wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java`

This class ends with `WiringHelper` (R-4 whitelist). It calls **only `setManager`** (no `getManager`), receiving everything it needs as parameters. No standalone unit test (it's thin glue verified by the IT in Task 9); compile-checked here.

- [ ] **Step 1: Implement `OntologyWiringHelper.java`:**

```java
package com.wikantik.ontology.runtime;

import java.nio.file.Path;
import java.util.List;

import javax.sql.DataSource;

import com.wikantik.WikiEngine;
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.api.managers.PageManager;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.projection.PageRecord;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Constructs the ontology runtime from live collaborators and registers the coordinator. */
public final class OntologyWiringHelper {

    private static final Logger LOG = LogManager.getLogger( OntologyWiringHelper.class );

    private OntologyWiringHelper() {}

    /**
     * Builds the TDB2-backed manager + rebuild coordinator and registers the coordinator on the
     * engine. Called from {@link WikiEngine#initKnowledgeGraph} with already-resolved collaborators.
     */
    public static void wireOntology( final WikiEngine engine,
                                     final java.util.Properties props,
                                     final DataSource dataSource,
                                     final PageManager pageManager ) {
        final boolean enabled = Boolean.parseBoolean(
                props.getProperty( "wikantik.ontology.enabled", "true" ) );
        if ( !enabled ) {
            LOG.info( "ontology layer disabled (wikantik.ontology.enabled=false)" );
            return;
        }

        final String dir = resolveDir( engine, props );
        final OntologyModelManager mgr = OntologyModelManager.tdb2( dir );
        mgr.loadTBox();

        final KgNodeRepository nodeRepo = new KgNodeRepository( dataSource );
        final KgEdgeRepository edgeRepo = new KgEdgeRepository( dataSource );
        final PageCanonicalIdsDao pageDao = new PageCanonicalIdsDao( dataSource );
        final PageRecordBuilder pageBuilder = new PageRecordBuilder( pageManager, pageDao::findAll );

        final OntologyRebuildService coordinator = new OntologyRebuildService(
                mgr,
                () -> nodeRepo.getAllNodes( Tier.HUMAN ),   // full dump incl. human + machine tier
                () -> edgeRepo.getAllEdges( Tier.HUMAN ),
                pageBuilder::build,
                true );

        engine.setManager( OntologyRebuildService.class, coordinator );
        LOG.info( "ontology runtime wired (tdb2 dir={})", dir );

        // Startup-if-empty: non-blocking self-heal on first boot.
        coordinator.rebuildIfEmpty();
    }

    private static String resolveDir( final WikiEngine engine, final java.util.Properties props ) {
        final String explicit = props.getProperty( "wikantik.ontology.tdb2.dir" );
        if ( explicit != null && !explicit.isBlank() ) {
            return explicit;
        }
        final String workDir = engine.getWorkDir();
        if ( workDir != null && !workDir.isBlank() ) {
            return Path.of( workDir, "ontology-tdb2" ).toString();
        }
        final String tmp = Path.of( System.getProperty( "java.io.tmpdir" ), "wikantik-ontology-tdb2" ).toString();
        LOG.warn( "wikantik.workDir unset; ontology TDB2 store falling back to {}", tmp );
        return tmp;
    }
}
```

> Confirm `KgNodeRepository.getAllNodes(Tier)` / `getAllEdges(Tier)` and `Tier.HUMAN` exist as found in research; if `Tier` has a different "include all" value, use it. Confirm `WikiEngine.getWorkDir()` returns `String`.

- [ ] **Step 2: Compile-check** (no test; verified by IT later):

Run: `mvn -o -pl wikantik-main test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java
git commit -m "feat(ontology): OntologyWiringHelper (construct TDB2 manager + coordinator, setManager)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Register `OntologyRebuildService` in `WikiEngine` + call the helper

**Files:** Modify `wikantik-main/.../WikiEngine.java`.

> **Read the current file first** at the cited regions (the line numbers below are from research and may have drifted): the `TYPED_FIELD_WRITERS` writer block (~263), `TYPED_FIELD_READERS` reader block (~347), the `mgr_*` field declarations, the `SNAPSHOT_REBUILDERS` map, and `initKnowledgeGraph` (~1519–1555). Mirror the `ContentIndexRebuildService` entries exactly.

- [ ] **Step 1: Add the backing field** next to `mgr_ContentIndexRebuildService`:

```java
    private com.wikantik.ontology.runtime.OntologyRebuildService mgr_OntologyRebuildService;
```

- [ ] **Step 2: Add the writer entry** in the `w.put(...)` block (next to the `ContentIndexRebuildService` writer):

```java
        w.put( com.wikantik.ontology.runtime.OntologyRebuildService.class,
               ( e, m ) -> e.mgr_OntologyRebuildService = (com.wikantik.ontology.runtime.OntologyRebuildService) m );
```

- [ ] **Step 3: Add the reader entry** in the `r.put(...)` block (next to the `ContentIndexRebuildService` reader):

```java
        r.put( com.wikantik.ontology.runtime.OntologyRebuildService.class,
               e -> e.mgr_OntologyRebuildService );
```

- [ ] **Step 4: SNAPSHOT_REBUILDERS** — read the `SNAPSHOT_REBUILDERS` map and find the entry keyed by the **PageGraph** subsystem bridge (the same one that rebuilds `ContentIndexRebuildService`, since Task 6 puts our service in `PageGraphSubsystem.Services`). The `OntologyRebuildService` will be repopulated by that bridge's `rebuildFromManagers` (Task 6) — so **no new SNAPSHOT_REBUILDERS entry is needed** as long as the PageGraph bridge already has one. Confirm the PageGraph bridge is present in `SNAPSHOT_REBUILDERS`; if our service ends up in a different subsystem, add the corresponding entry. (Document what you find here before proceeding.)

- [ ] **Step 5: Call the wiring helper from `initKnowledgeGraph`** — immediately after the existing `KnowledgeWiringHelper.wireKgPolicyAndContent(...)` call (so `props`, `ds`, and `pageManager` are in scope; `pageManager` is already fetched at the documented line ~1540):

```java
        com.wikantik.ontology.runtime.OntologyWiringHelper.wireOntology( this, props, ds, pageManager );
```

> This is a plain method call (not `getManager`), so it adds no ArchUnit violation. `wireOntology` itself only calls `setManager`.

- [ ] **Step 6: Compile-check.**

Run: `mvn -o -pl wikantik-main test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat(ontology): register OntologyRebuildService + wire it in initKnowledgeGraph

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Expose via `PageGraphSubsystem.Services`

**Files:** Modify `PageGraphSubsystem.java`, `PageGraphSubsystemFactory.java`, `PageGraphSubsystemBridge.java`.

> **Read each file first.** Mirror `contentIndexRebuildService` exactly in all three.

- [ ] **Step 1: Add the component to the `Services` record** (`PageGraphSubsystem.java`):

```java
    public record Services(
        StructuralIndexService       structuralIndexService,
        PageGraphService             pageGraphService,
        ReferenceManager             referenceManager,
        ContentIndexRebuildService   contentIndexRebuildService,
        com.wikantik.ontology.runtime.OntologyRebuildService ontologyRebuildService   // <-- added
    ) {}
```

- [ ] **Step 2: Pack it in `PageGraphSubsystemFactory.create(...)`** — read the existing `engine.getManager(ContentIndexRebuildService.class)` line and add directly beneath it:

```java
        final com.wikantik.ontology.runtime.OntologyRebuildService ontologyRebuildService =
            engine.getManager( com.wikantik.ontology.runtime.OntologyRebuildService.class );
```
and append `ontologyRebuildService` as the final argument to the `new PageGraphSubsystem.Services( ... )` constructor call. **This adds a new `getManager` call site in a `*SubsystemFactory` (R-4-exempt by suffix, but a new R-2 frozen violation → Task 7).**

- [ ] **Step 3: Repopulate it in `PageGraphSubsystemBridge`** — find `rebuildFromManagers` (or equivalent) where it reads `engine.getManager(ContentIndexRebuildService.class)` to rebuild the `Services` snapshot, and add the analogous `getManager(OntologyRebuildService.class)` so the rebuilt record includes our service. **Another new `getManager` in a `*SubsystemBridge` (R-4-exempt; R-2 → Task 7).** If the bridge instead delegates to the factory, no change is needed — confirm by reading it.

- [ ] **Step 4: Compile-check.**

Run: `mvn -o -pl wikantik-main test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystem.java \
        wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystemFactory.java \
        wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystemBridge.java
git commit -m "feat(ontology): expose OntologyRebuildService via PageGraphSubsystem.Services

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Re-freeze the ArchUnit `no_get_manager` rules

**Files:** `wikantik-main/src/test/resources/archunit_store/*` (the R-2 store, and R-4 if it flags the new factory/bridge callers).

> **Critical handling** (per `reference_archunit_store_prunes_on_failure`): the store mutates even on red runs. Before each attempt, `git checkout -- wikantik-main/src/test/resources/archunit_store/` to restore the committed baseline.

- [ ] **Step 1: Run the decomposition test to see the new violations:**

Run: `mvn -o -pl wikantik-main test -Dtest=DecompositionArchTest`
Expected: It FAILS on R-2 (`no_new_get_manager_callers`) reporting the new `getManager` call(s) in `PageGraphSubsystemFactory` (and `PageGraphSubsystemBridge` if edited). R-4 should PASS (both classes match the `*SubsystemFactory`/`*SubsystemBridge` suffix whitelist). **If R-4 fails**, a class is mis-named — rename it to end with the whitelisted suffix rather than touching the R-4 store.

- [ ] **Step 2: Restore the store, then re-freeze deliberately:**

```bash
git checkout -- wikantik-main/src/test/resources/archunit_store/
mvn -o -pl wikantik-main test -Dtest=DecompositionArchTest -Darchunit.freeze.refreeze=true
```
This run records the *current* violation set as the new baseline (adds the intended new `getManager` callers). Expected: BUILD SUCCESS.

- [ ] **Step 3: Re-run WITHOUT refreeze to confirm the baseline is stable:**

```bash
mvn -o -pl wikantik-main test -Dtest=DecompositionArchTest
```
Expected: BUILD SUCCESS (no new violations vs the just-frozen store).

- [ ] **Step 4: Review the store diff** — `git diff wikantik-main/src/test/resources/archunit_store/` should show ONLY the intended new `PageGraphSubsystemFactory`/`Bridge` `getManager` entries (no unrelated prunes). If unrelated baselines were pruned, `git checkout` the store and redo Step 2 cleanly.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/test/resources/archunit_store/
git commit -m "test(ontology): re-freeze ArchUnit baseline for ontology getManager wiring

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: `AdminOntologyResource` + web.xml

**Files:**
- Create `wikantik-rest/src/main/java/com/wikantik/rest/AdminOntologyResource.java`
- Modify `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Implement `AdminOntologyResource.java`** (mirror `AdminContentResource`; read it for the exact `RestServletBase` helper names + `extractPathParam` behavior):

```java
package com.wikantik.rest;

import java.io.IOException;
import java.util.Map;

import com.wikantik.ontology.runtime.OntologyRebuildService;
import com.wikantik.ontology.runtime.OntologyRebuildStatus;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Admin endpoint: POST /admin/ontology/rebuild (trigger), GET /admin/ontology/status. */
public class AdminOntologyResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminOntologyResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String action = extractPathParam( request );
        if ( "status".equals( action ) ) {
            handleStatus( response );
        } else {
            sendNotFound( response, "Unknown ontology endpoint: " + action );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String action = extractPathParam( request );
        if ( "rebuild".equals( action ) ) {
            handleRebuild( response );
        } else {
            sendNotFound( response, "Unknown ontology endpoint: " + action );
        }
    }

    private OntologyRebuildService service() {
        return getSubsystems().pageGraph().ontologyRebuildService();
    }

    private void handleStatus( final HttpServletResponse response ) throws IOException {
        final OntologyRebuildService svc = service();
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        sendJsonWithStatus( response, 200, toMap( svc.status() ) );
    }

    private void handleRebuild( final HttpServletResponse response ) throws IOException {
        final OntologyRebuildService svc = service();
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        try {
            final OntologyRebuildStatus snap = svc.triggerRebuild();
            LOG.info( "Ontology rebuild triggered" );
            sendJsonWithStatus( response, 202, toMap( snap ) );
        } catch ( final OntologyRebuildService.ConflictException e ) {
            LOG.warn( "Ontology rebuild rejected — already running: {}", e.getMessage() );
            sendJsonWithStatus( response, HttpServletResponse.SC_CONFLICT, toMap( svc.status() ) );
        } catch ( final OntologyRebuildService.DisabledException e ) {
            LOG.warn( "Ontology rebuild rejected — disabled" );
            sendJsonWithStatus( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    Map.of( "error", "ontology disabled", "flag", "wikantik.ontology.enabled" ) );
        }
    }

    private Map< String, Object > toMap( final OntologyRebuildStatus s ) {
        return Map.of( "state", s.state(), "enabled", s.enabled(),
                "graphCount", s.graphCount(), "lastError", s.lastError() == null ? "" : s.lastError() );
    }

    private void sendJsonWithStatus( final HttpServletResponse response, final int status, final Object payload )
            throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( new com.google.gson.GsonBuilder().serializeNulls().create().toJson( payload ) );
    }
}
```

- [ ] **Step 2: Register in `web.xml`** — add a `<servlet>` (near `AdminContentResource`'s) and a `<servlet-mapping>` (near its mapping). `AdminAuthFilter` already covers `/admin/*`.

```xml
    <servlet>
        <servlet-name>AdminOntologyResource</servlet-name>
        <servlet-class>com.wikantik.rest.AdminOntologyResource</servlet-class>
    </servlet>
```
```xml
    <servlet-mapping>
        <servlet-name>AdminOntologyResource</servlet-name>
        <url-pattern>/admin/ontology/*</url-pattern>
    </servlet-mapping>
```

- [ ] **Step 3: Compile-check both modules.**

Run: `mvn -o -pl wikantik-rest -am test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminOntologyResource.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(ontology): AdminOntologyResource (POST /admin/ontology/rebuild, GET status)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 9: Wire-level IT + full reactor gate

**Files:** Create `wikantik-it-tests/.../AdminOntologyRebuildIT.java` (place it in the REST IT module alongside the existing admin ITs — read a sibling like an `AdminContent*IT` for the exact base class, login helper, and base-URL pattern).

- [ ] **Step 1: Write the IT** — authenticate as admin (use `RestSeedHelper.awaitAdminReady` per `reference_admin_it_login_race`), POST `/admin/ontology/rebuild`, assert 202 + a JSON `state`, then GET `/admin/ontology/status` and assert it reports `enabled:true` and eventually `state:"IDLE"`. Model the HTTP plumbing on the nearest existing admin IT. (Full code deferred to implementation: it must match the sibling IT's harness exactly — copy that sibling's setup verbatim and swap the path/assertions.)

- [ ] **Step 2: Run the REST IT module:**

Run: `mvn clean install -pl wikantik-it-tests/wikantik-it-test-rest -am -Pintegration-tests -fae` (sequential — NO `-T`)
Expected: the new IT passes (admin rebuild returns 202; status reachable).

- [ ] **Step 3: Full unit reactor + ArchUnit:**

Run: `mvn -o clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS across all modules, including `DecompositionArchTest` green against the re-frozen store.

- [ ] **Step 4: Full integration-test reactor gate** (per `feedback_full_it_after_targeted_fix`, sequential, `-fae`):

Run: `mvn clean install -Pintegration-tests -fae`
Expected: FULLY GREEN.

- [ ] **Step 5: Commit the IT**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/.../AdminOntologyRebuildIT.java
git commit -m "test(ontology): wire-level IT for /admin/ontology/rebuild

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 6: Update docs/memory** — add `/admin/ontology/*` to the CLAUDE.md agent-facing surface table; update the project memory status to Phase 1b complete; note the `wikantik.ontology.*` properties.

---

## Self-Review (against spec §3/§5 Phase 1 wiring scope)

**Spec coverage:**
- "OntologyModelManager (TDB2 …)" constructed at startup with a configurable on-disk dir → Task 4 (`OntologyWiringHelper`, `wikantik.ontology.tdb2.dir`). ✓
- "OntologyRebuildService (full rebuild, startup-if-empty, /admin/ontology/rebuild)" → Task 2 (coordinator + `rebuildIfEmpty`), Task 4 (startup-if-empty call), Task 8 (endpoint). ✓
- A-Box materialized from live repositories → Tasks 3–4 (`PageRecordBuilder` + repo suppliers). ✓
- "No public endpoint" (Phase 3) → only the **admin** endpoint here, behind `AdminAuthFilter`. ✓

**Risk handling:** ArchUnit freeze store handled as its own task (Task 7) with the git-restore safeguard; `OntologyWiringHelper` deliberately avoids `getManager` to add zero violations itself; the only new `getManager` callers are R-4-whitelisted factory/bridge.

**Placeholder scan:** New standalone components (coordinator, status, builder, wiring helper, admin resource) are complete code. The shared-file edits (WikiEngine maps, PageGraph subsystem trio) are specified as exact additions mirroring the named `ContentIndexRebuildService` entries, each prefaced with "read the current file first" because those are large, evolving files where the anchor must be confirmed at edit time. The IT (Task 9) is specified to copy the nearest sibling admin IT's harness verbatim (its exact base class/login plumbing must be read at implementation time) — flagged, not hand-waved.

**Type consistency:** `OntologyRebuildService(manager, nodeSupplier, edgeSupplier, pageSupplier, enabled)`, `OntologyRebuildStatus(state, enabled, graphCount, lastError)`, `PageRecordBuilder(pageManager, rowSupplier)`, `OntologyWiringHelper.wireOntology(engine, props, ds, pageManager)`, `OntologyModelManager.namedGraphCount()`, and `getSubsystems().pageGraph().ontologyRebuildService()` are used consistently. `PageRecord` (9 components), `KgNode`/`KgEdge` (10 components), `PageCanonicalIdsDao.Row` (7 components) match the verified records.

**Open items to confirm at implementation time (flagged, not guessed):** `PageProvider.LATEST_VERSION` constant value; `Tier.HUMAN` as the "include all tiers" argument for `getAllNodes/Edges`; the exact `SNAPSHOT_REBUILDERS` PageGraph entry (Task 5 Step 4); whether `PageGraphSubsystemBridge` needs the getManager add or delegates to the factory (Task 6 Step 3); the sibling admin IT harness (Task 9).
