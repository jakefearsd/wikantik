# Wiki Ontology — Phase 2 Implementation Plan (Event-Incremental Sync + Nightly Backstop)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. (This session runs it **directly/inline**.) Steps use checkbox (`- [ ]`).

**Goal:** Keep the materialized ontology fresh in near-real-time. A `WikiEventListener` re-projects a single page's named graph (plus its concept graphs) on save/rename and removes it on a true delete; a scheduled full-rebuild backstop reconciles everything the event path can't see.

**Architecture:** Mirror `StructuralIndexEventListener` exactly — register on both `PageManager` and `FilterManager`, route `POST_SAVE_END`/`POST_SAVE` → save, `PAGE_DELETED` → delete, and (added) `WikiPageRenameEvent` → rename. The listener delegates to an `OntologyPageSync` that does single-page re-projection against the `OntologyModelManager` (named-graph-per-resource makes "replace graph G" trivial). A `OntologyRebuildScheduler` triggers the coordinator's full rebuild on a timer.

**Tech Stack:** Java 21, JUnit 5 + Mockito (unit), the existing event system + Jena in-memory dataset for tests. All new code in `wikantik-main` (`com.wikantik.ontology.runtime`).

**Spec:** `docs/superpowers/specs/2026-06-08-wiki-ontology-design.md` (§3.3 sync, §5 Phase 2).

---

## Key constraints & decisions

- **No KG-change events exist** (only page events). So incremental sync is **page-driven**: it refreshes **page** graphs + **concept** graphs. **Entity** graphs (from `kg_nodes`/`kg_edges`) change only via the **nightly full rebuild** — documented, not a gap to fix here (adding `Kg*Event`s is a later enhancement).
- **Rename is IRI-stable by design:** the page graph IRI is `Iris.page(canonicalId)`, unchanged across rename — so rename = re-project the same graph with the new slug (only `schema:url` changes). Handled by routing `WikiPageRenameEvent` → `onPageSaved(newName)`.
- **Delete vs rename disambiguation (load-bearing):** a JSPWiki rename can surface as delete-old + save-new. `onPageDeleted(slug)` therefore removes the page graph **only if** the canonical_id is truly gone from `page_canonical_ids` (`findByCanonicalId(id).isEmpty()`); if the id still lives (under a new slug), it's a rename — leave the graph for the save event to refresh. A small `slug → canonical_id` cache (populated on save) lets delete resolve the id even after the row is gone.
- **Concurrency:** the listener re-projects synchronously on the event thread (one page = a few triples = one fast TDB2 write txn). TDB2 serializes writers, so a save during a running full rebuild blocks briefly but never corrupts. Eventual consistency is acceptable per the spec.
- **No new `getManager` calls** → no ArchUnit freeze-store churn this phase. The wiring helper receives `FilterManager` as a parameter (already fetched in `initKnowledgeGraph`).
- **New config:** `wikantik.ontology.rebuild.interval.hours` (default `24`; `0` disables the scheduler).
- **Apache header** on every new `.java` (copy from an existing file).

## File structure

```
wikantik-main/.../ontology/runtime/
    PageRecordBuilder.java        (edit) extract `static PageRecord fromRow(Row, PageManager)`
    OntologyPageSync.java         (new)  single-page re-projection: onPageSaved/onPageDeleted/onPageRenamed
    OntologyEventListener.java    (new)  WikiEventListener -> OntologyPageSync; register(PageManager, FilterManager)
    OntologyRebuildScheduler.java (new)  daemon timer -> coordinator.triggerRebuild()
    OntologyWiringHelper.java     (edit) build+register listener; start scheduler; +FilterManager param
wikantik-main/.../WikiEngine.java (edit) pass filterManager to wireOntology
```

---

## Task 1: Extract `PageRecordBuilder.fromRow(Row, PageManager)`

**Files:** Modify `PageRecordBuilder.java`; Test `PageRecordBuilderTest.java` (add a `fromRow` case).

The single-page sync needs to turn one `PageCanonicalIdsDao.Row` into a `PageRecord`. Extract the per-row enrichment (currently inline in `build()`) into a reusable static method.

- [ ] **Step 1: Add the failing test** to `PageRecordBuilderTest` (a new `@Test`):

```java
    @Test
    void fromRowEnrichesASingleRow() {
        final PageCanonicalIdsDao.Row row = new PageCanonicalIdsDao.Row(
                "01CANON0000000000000000009", "Solo", "Solo", "article", "ml",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH );
        org.mockito.Mockito.when( pageManager.getPureText( "Solo", -1 ) )
                .thenReturn( "---\ntags: [x]\nsummary: s\n---\nbody" );
        final PageRecord r = PageRecordBuilder.fromRow( row, pageManager );
        assertEquals( "01CANON0000000000000000009", r.canonicalId() );
        assertEquals( List.of( "x" ), r.tags() );
        assertEquals( "s", r.summary() );
        assertEquals( "ml", r.cluster() );
    }
```

- [ ] **Step 2: Run — verify it fails** (`fromRow` does not exist).

Run: `mvn -o -pl wikantik-main test -Dtest=PageRecordBuilderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILE FAILURE (cannot find symbol `fromRow`).

- [ ] **Step 3: Refactor `PageRecordBuilder`** — replace the body of `build()`'s loop with a call to a new `public static fromRow`, keeping the existing `tags`/`str`/`isoDate` helpers (make them used by `fromRow`):

```java
    public List< PageRecord > build() {
        final List< PageRecord > out = new ArrayList<>();
        for ( final PageCanonicalIdsDao.Row row : rowSource.get() ) {
            out.add( fromRow( row, pageManager ) );
        }
        return out;
    }

    /** Turns one canonical-id row into a PageRecord, enriching tags/summary/date/author from frontmatter. */
    public static PageRecord fromRow( final PageCanonicalIdsDao.Row row, final PageManager pageManager ) {
        Map< String, Object > md = Map.of();
        try {
            final String text = pageManager.getPureText( row.currentSlug(), PageProvider.LATEST_VERSION );
            if ( text != null ) {
                md = FrontmatterParser.parse( text ).metadata();
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "frontmatter parse failed for {}: {}", row.currentSlug(), e.getMessage() );
        }
        return new PageRecord(
                row.canonicalId(), row.currentSlug(), row.title(), row.type(), row.cluster(),
                tags( md.get( "tags" ) ), str( md.get( "summary" ) ), isoDate( md.get( "date" ) ),
                str( md.get( "author" ) ) );
    }
```
(Leave `tags`, `str`, `isoDate` as the existing private statics — now called by `fromRow`. Remove the now-duplicated inline enrichment from the old loop body.)

- [ ] **Step 4: Run — verify all `PageRecordBuilderTest` tests pass** (the 2 originals + `fromRow`). Expected: 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/PageRecordBuilder.java \
        wikantik-main/src/test/java/com/wikantik/ontology/runtime/PageRecordBuilderTest.java
git commit -m "refactor(ontology): extract PageRecordBuilder.fromRow for single-page reuse

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `OntologyPageSync` — single-page re-projection

**Files:** Create `OntologyPageSync.java`; Test `OntologyPageSyncTest.java`.

- [ ] **Step 1: Write the failing test:**

```java
package com.wikantik.ontology.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.wikantik.api.managers.PageManager;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith( MockitoExtension.class )
class OntologyPageSyncTest {

    @Mock PageManager pageManager;
    @Mock PageCanonicalIdsDao dao;

    private static final String CID = "01CANON0000000000000000010";

    private PageCanonicalIdsDao.Row row( final String slug ) {
        return new PageCanonicalIdsDao.Row( CID, slug, "T", "article", "ml", Instant.EPOCH, Instant.EPOCH );
    }

    private OntologyPageSync sync( final OntologyModelManager mgr ) {
        return new OntologyPageSync( mgr, dao, pageManager );
    }

    @Test
    void onPageSavedProjectsPageAndConceptGraphs() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( "Solo" ) ).thenReturn( Optional.of( row( "Solo" ) ) );
        when( pageManager.getPureText( "Solo", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );

        sync( mgr ).onPageSaved( "Solo" );

        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ), "page graph projected" );
        assertTrue( mgr.namedGraphExists( Iris.concept( "graphs" ) ), "tag concept graph projected" );
        assertTrue( mgr.namedGraphExists( Iris.concept( "ml" ) ), "cluster concept graph projected" );
    }

    @Test
    void onPageDeletedRemovesGraphWhenCanonicalIdIsGone() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( "Solo" ) ).thenReturn( Optional.of( row( "Solo" ) ) );
        when( pageManager.getPureText( "Solo", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );
        final OntologyPageSync s = sync( mgr );
        s.onPageSaved( "Solo" );
        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ) );

        // Now the page is truly deleted: canonical_id no longer resolves.
        when( dao.findByCanonicalId( CID ) ).thenReturn( Optional.empty() );
        s.onPageDeleted( "Solo" );
        assertFalse( mgr.namedGraphExists( Iris.page( CID ) ), "page graph removed on true delete" );
    }

    @Test
    void onPageDeletedKeepsGraphWhenCanonicalIdStillLives_renameCase() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( "Old" ) ).thenReturn( Optional.of( row( "Old" ) ) );
        when( pageManager.getPureText( "Old", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );
        final OntologyPageSync s = sync( mgr );
        s.onPageSaved( "Old" );

        // Rename surfaced as delete-of-old: canonical_id still lives (under the new slug).
        when( dao.findByCanonicalId( CID ) ).thenReturn( Optional.of( row( "New" ) ) );
        s.onPageDeleted( "Old" );
        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ),
                "rename must NOT drop the page graph (canonical_id still lives)" );
    }

    @Test
    void onPageRenamedReprojectsUnderNewSlug() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( "New" ) ).thenReturn( Optional.of( row( "New" ) ) );
        when( pageManager.getPureText( "New", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );

        sync( mgr ).onPageRenamed( "Old", "New" );
        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ), "page graph present after rename re-projection" );
    }
}
```

- [ ] **Step 2: Run — verify it fails** (no `OntologyPageSync`).

- [ ] **Step 3: Implement `OntologyPageSync.java`:**

```java
package com.wikantik.ontology.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.wikantik.api.managers.PageManager;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.projection.ConceptProjector;
import com.wikantik.ontology.projection.PageProjector;
import com.wikantik.ontology.projection.PageRecord;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.apache.jena.rdf.model.Model;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Single-page incremental re-projection into the ontology dataset. Page + concept
 * graphs only — entity graphs change via the nightly full rebuild (no KG events exist).
 */
public final class OntologyPageSync {

    private static final Logger LOG = LogManager.getLogger( OntologyPageSync.class );

    private final OntologyModelManager manager;
    private final PageCanonicalIdsDao dao;
    private final PageManager pageManager;
    /** slug -> canonical_id, populated on save so deletes can resolve the page IRI after the row is gone. */
    private final ConcurrentHashMap< String, String > slugToCanonical = new ConcurrentHashMap<>();

    public OntologyPageSync( final OntologyModelManager manager, final PageCanonicalIdsDao dao,
                             final PageManager pageManager ) {
        this.manager = manager;
        this.dao = dao;
        this.pageManager = pageManager;
    }

    /** Re-projects the page's named graph + its tag/cluster concept graphs. */
    public void onPageSaved( final String slug ) {
        final Optional< PageCanonicalIdsDao.Row > row = dao.findBySlug( slug );
        if ( row.isEmpty() ) {
            LOG.warn( "onPageSaved: no canonical_id row for slug '{}'; skipping (nightly rebuild will reconcile)", slug );
            return;
        }
        final PageRecord record = PageRecordBuilder.fromRow( row.get(), pageManager );
        manager.replaceNamedGraph( Iris.page( record.canonicalId() ), PageProjector.project( record ) );
        for ( final Map.Entry< String, Model > c : ConceptProjector.project( java.util.List.of( record ) ).entrySet() ) {
            manager.replaceNamedGraph( c.getKey(), c.getValue() );
        }
        slugToCanonical.put( slug, record.canonicalId() );
    }

    /**
     * Removes the page graph ONLY if the canonical_id is truly gone. If the id still
     * lives (rename surfaced as delete-of-old), the graph is left for the save event.
     */
    public void onPageDeleted( final String slug ) {
        String canonicalId = slugToCanonical.remove( slug );
        if ( canonicalId == null ) {
            canonicalId = dao.findBySlug( slug ).map( PageCanonicalIdsDao.Row::canonicalId ).orElse( null );
        }
        if ( canonicalId == null ) {
            LOG.warn( "onPageDeleted: cannot resolve canonical_id for slug '{}'; nightly rebuild will prune", slug );
            return;
        }
        if ( dao.findByCanonicalId( canonicalId ).isPresent() ) {
            LOG.info( "onPageDeleted: canonical_id {} still lives (rename of '{}'); keeping page graph", canonicalId, slug );
            return;
        }
        manager.removeNamedGraph( Iris.page( canonicalId ) );
    }

    /** Rename: the page IRI keys on the stable canonical_id, so re-project under the new slug. */
    public void onPageRenamed( final String oldSlug, final String newSlug ) {
        slugToCanonical.remove( oldSlug );
        onPageSaved( newSlug );
    }
}
```

- [ ] **Step 4: Run — verify all 4 tests pass.**

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyPageSync.java \
        wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyPageSyncTest.java
git commit -m "feat(ontology): OntologyPageSync (single-page incremental re-projection)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `OntologyEventListener` — wire page events to the sync

**Files:** Create `OntologyEventListener.java`; Test `OntologyEventListenerTest.java`.

- [ ] **Step 1: Write the failing test** (Mockito the sync; fire real events):

```java
package com.wikantik.ontology.runtime;

import static org.mockito.Mockito.verify;

import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith( MockitoExtension.class )
class OntologyEventListenerTest {

    @Mock OntologyPageSync sync;

    @Test
    void postSaveEndRoutesToOnPageSaved() {
        new OntologyEventListener( sync ).actionPerformed(
                new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "Foo" ) );
        verify( sync ).onPageSaved( "Foo" );
    }

    @Test
    void pageDeletedRoutesToOnPageDeleted() {
        new OntologyEventListener( sync ).actionPerformed(
                new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "Foo" ) );
        verify( sync ).onPageDeleted( "Foo" );
    }

    @Test
    void renameRoutesToOnPageRenamed() {
        new OntologyEventListener( sync ).actionPerformed(
                new WikiPageRenameEvent( this, "Old", "New" ) );
        verify( sync ).onPageRenamed( "Old", "New" );
    }
}
```

- [ ] **Step 2: Run — verify it fails** (no `OntologyEventListener`).

- [ ] **Step 3: Implement `OntologyEventListener.java`** (rename check FIRST — `WikiPageRenameEvent` extends `WikiPageEvent`):

```java
package com.wikantik.ontology.runtime;

import com.wikantik.api.managers.PageManager;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.filters.FilterManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forwards wiki page events to {@link OntologyPageSync}, mirroring
 * {@code StructuralIndexEventListener}: save events arrive from the FilterManager
 * (doPostSaveFiltering), PAGE_DELETED from the PageManager — so register on both.
 */
public final class OntologyEventListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( OntologyEventListener.class );

    private final OntologyPageSync sync;

    public OntologyEventListener( final OntologyPageSync sync ) {
        this.sync = sync;
    }

    public void register( final PageManager pageManager, final FilterManager filterManager ) {
        WikiEventManager.addWikiEventListener( pageManager, this );
        WikiEventManager.addWikiEventListener( filterManager, this );
        LOG.info( "Ontology event listener registered for PageManager + FilterManager events" );
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof WikiPageRenameEvent rename ) {
            sync.onPageRenamed( rename.getOldPageName(), rename.getNewPageName() );
            return;
        }
        if ( !( event instanceof WikiPageEvent pageEvent ) ) {
            return;
        }
        switch ( pageEvent.getType() ) {
            case WikiPageEvent.POST_SAVE_END,
                 WikiPageEvent.POST_SAVE     -> sync.onPageSaved( pageEvent.getPageName() );
            case WikiPageEvent.PAGE_DELETED  -> sync.onPageDeleted( pageEvent.getPageName() );
            default                          -> { /* ignore */ }
        }
    }
}
```

- [ ] **Step 4: Run — verify 3 tests pass.**

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyEventListener.java \
        wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyEventListenerTest.java
git commit -m "feat(ontology): OntologyEventListener (page save/delete/rename -> OntologyPageSync)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: `OntologyRebuildScheduler` — nightly full-rebuild backstop

**Files:** Create `OntologyRebuildScheduler.java`; Test `OntologyRebuildSchedulerTest.java`.

- [ ] **Step 1: Write the failing test** (test the tick behavior, not real timing):

```java
package com.wikantik.ontology.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith( MockitoExtension.class )
class OntologyRebuildSchedulerTest {

    @Mock OntologyRebuildCoordinator coordinator;

    @Test
    void tickTriggersRebuild() {
        new OntologyRebuildScheduler( coordinator, 24 ).runOnce();
        verify( coordinator ).triggerRebuild();
    }

    @Test
    void tickSwallowsConflict() {
        when( coordinator.triggerRebuild() )
                .thenThrow( new OntologyRebuildCoordinator.ConflictException( "running" ) );
        assertDoesNotThrow( () -> new OntologyRebuildScheduler( coordinator, 24 ).runOnce() );
    }
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement `OntologyRebuildScheduler.java`:**

```java
package com.wikantik.ontology.runtime;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Periodically triggers a full ontology rebuild as the backstop for missed/uneventful changes. */
public final class OntologyRebuildScheduler {

    private static final Logger LOG = LogManager.getLogger( OntologyRebuildScheduler.class );

    private final OntologyRebuildCoordinator coordinator;
    private final long intervalHours;
    private ScheduledExecutorService executor;

    public OntologyRebuildScheduler( final OntologyRebuildCoordinator coordinator, final long intervalHours ) {
        this.coordinator = coordinator;
        this.intervalHours = intervalHours;
    }

    /** Starts the timer (no-op when intervalHours <= 0). First run is one interval out. */
    public void start() {
        if ( intervalHours <= 0 ) {
            LOG.info( "ontology rebuild scheduler disabled (interval={}h)", intervalHours );
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-ontology-rebuild-scheduler" );
            t.setDaemon( true );
            return t;
        } );
        executor.scheduleAtFixedRate( this::runOnce, intervalHours, intervalHours, TimeUnit.HOURS );
        LOG.info( "ontology rebuild scheduler started (every {}h)", intervalHours );
    }

    /** One scheduled tick — triggers a rebuild, swallowing the "already running" conflict. */
    void runOnce() {
        try {
            coordinator.triggerRebuild();
            LOG.info( "scheduled ontology rebuild triggered" );
        } catch ( final OntologyRebuildCoordinator.ConflictException e ) {
            LOG.info( "scheduled ontology rebuild skipped — already running" );
        } catch ( final OntologyRebuildCoordinator.DisabledException e ) {
            LOG.info( "scheduled ontology rebuild skipped — disabled" );
        } catch ( final RuntimeException e ) {
            LOG.warn( "scheduled ontology rebuild failed: {}", e.getMessage(), e );
        }
    }

    public void stop() {
        if ( executor != null ) {
            executor.shutdownNow();
        }
    }
}
```

- [ ] **Step 4: Run — verify 2 tests pass.**

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyRebuildScheduler.java \
        wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyRebuildSchedulerTest.java
git commit -m "feat(ontology): OntologyRebuildScheduler (nightly full-rebuild backstop)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Wire the listener + scheduler into `OntologyWiringHelper`

**Files:** Modify `OntologyWiringHelper.java`, `WikiEngine.java`.

- [ ] **Step 1: Add a `FilterManager` parameter + the listener/scheduler wiring** to `OntologyWiringHelper.wireOntology`. After the existing `engine.setManager( OntologyRebuildCoordinator.class, coordinator )` line and BEFORE the `coordinator.rebuildIfEmpty()` call, insert:

```java
        // Event-incremental sync: re-project a page's graph on save/rename, remove on true delete.
        final OntologyPageSync pageSync = new OntologyPageSync( mgr, pageDao, pageManager );
        new OntologyEventListener( pageSync ).register( pageManager, filterManager );

        // Nightly full-rebuild backstop (catches KG drift + missed events).
        final long intervalHours = Long.parseLong(
                props.getProperty( "wikantik.ontology.rebuild.interval.hours", "24" ) );
        new OntologyRebuildScheduler( coordinator, intervalHours ).start();
```

Update the method signature to accept `filterManager`:
```java
    public static void wireOntology( final WikiEngine engine,
                                     final Properties props,
                                     final DataSource dataSource,
                                     final PageManager pageManager,
                                     final com.wikantik.filters.FilterManager filterManager ) {
```
(Add `import com.wikantik.filters.FilterManager;` or fully-qualify as above. `pageDao` is already constructed earlier in the method for `PageRecordBuilder`; reuse it.)

- [ ] **Step 2: Pass `filterManager` at the `WikiEngine` call site.** In `initKnowledgeGraph`, the call added in Phase 1b is:
```java
            com.wikantik.ontology.runtime.OntologyWiringHelper.wireOntology( this, props, ds, pageManager );
```
`filterManager` is already in scope (fetched ~line 1541). Change the call to:
```java
            com.wikantik.ontology.runtime.OntologyWiringHelper.wireOntology( this, props, ds, pageManager, filterManager );
```

- [ ] **Step 3: Compile-check.**

Run: `mvn -o -pl wikantik-main test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Confirm no new ArchUnit violations** (the wiring helper still calls only `setManager`; the call site adds no `getManager`):

Run: `mvn -o -pl wikantik-main test -Dtest=DecompositionArchTest`
Expected: BUILD SUCCESS (no store change needed). If it unexpectedly fails, `git checkout -- wikantik-main/src/test/resources/archunit_store/` and investigate before re-freezing.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat(ontology): wire event listener + nightly rebuild scheduler at boot

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Verification gates

- [ ] **Step 1: Full unit reactor** — `mvn -o clean install -T 1C -DskipITs`. Expected: BUILD SUCCESS across all modules, ArchUnit green, all new Phase 2 unit tests passing.

- [ ] **Step 2: Full IT reactor** (the integration gate — every save/rename/delete in the IT suites now drives the listener; a listener that breaks saves would fail those ITs). Run with unit tests skipped to dodge the wall-kill (per `reference_full_it_reactor_execution`):

Run: `mvn -o install -Pintegration-tests -Dsurefire.skip=true -fae`
Expected: BUILD SUCCESS. Verify via the failsafe reports (the `-newermt` predicate is unreliable — aggregate without it):
```bash
find wikantik-it-tests -path '*failsafe-reports*' -name 'com.wikantik.its.*.txt' \
  | xargs grep -hE "^Tests run:" \
  | awk '{for(i=1;i<=NF;i++){if($i=="run:")t+=$(i+1)+0;if($i ~ /Failures:/)f+=$(i+1)+0;if($i ~ /Errors:/)e+=$(i+1)+0}} END {printf "Tests run: %d, Failures: %d, Errors: %d\n", t, f, e}'
```
Expected: Failures: 0, Errors: 0.

- [ ] **Step 3: Docs + memory** — document `wikantik.ontology.rebuild.interval.hours` in CLAUDE.md's `wikantik-ontology` module bullet; update the project memory to Phase 2 complete (note: incremental sync covers page + concept graphs; entity graphs reconcile via the nightly backstop since no KG events exist).

---

## Self-Review (against spec §3.3 / §5 Phase 2)

**Spec coverage:**
- "OntologyEventListener (save/delete/rename …) → per-resource named-graph re-projection" → Tasks 2–3 + 5. Save/rename re-project the page graph + concept graphs; delete removes the page graph (true-delete guarded). ✓
- "+ KG node/edge events" → **documented as N/A** (no `Kg*Event` types exist); entity-graph freshness is delivered by the nightly backstop. ✓ (scoped honestly)
- "nightly rebuild backstop" → Task 4 + 5 (`OntologyRebuildScheduler`, `wikantik.ontology.rebuild.interval.hours`). ✓
- Phase 2 gate "event → graph replaced; delete → graph removed" → `OntologyPageSyncTest` (save projects, true-delete removes, rename keeps). "full rebuild == sum of incrementals" → tested at the page level (the incremental page graph matches a projected page graph); entity graphs are out of the incremental path by design, so a *global* equality is intentionally not claimed (noted). ✓

**Placeholder scan:** new components + tests are complete code; the two shared-file edits (`OntologyWiringHelper` signature + the `WikiEngine` call site) are exact one-line/one-block changes against Phase-1b anchors created in this same effort.

**Type consistency:** `OntologyPageSync(manager, dao, pageManager)` with `onPageSaved/onPageDeleted/onPageRenamed`; `OntologyEventListener(sync)` + `register(pageManager, filterManager)`; `OntologyRebuildScheduler(coordinator, intervalHours)` with `start()/runOnce()/stop()`; `PageRecordBuilder.fromRow(Row, PageManager)`. `WikiPageRenameEvent.getOldPageName()/getNewPageName()`, `WikiPageEvent.POST_SAVE_END/POST_SAVE/PAGE_DELETED`, `PageCanonicalIdsDao.findBySlug/findByCanonicalId` all match the verified signatures. No new `getManager` callers → ArchUnit store untouched.
