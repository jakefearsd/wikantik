# KG-Change Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit `KgChangeEvent` from the two Knowledge Graph write funnels and incrementally re-project affected entity named graphs into the ontology TDB2 store via a coalesced async drain, demoting the nightly full rebuild to a reconciliation backstop.

**Architecture:** New `KgChangeEvent` (wikantik-event, sealed `WikiEvent` permits opened) carries only `Set<UUID>` touched/removed payloads, fired via `WikiEventManager.fireEvent(serviceInstance, event)` after durable writes in `DefaultKnowledgeGraphService` (direct curation writes) and `KgMaterializationService` (proposal-driven writes, incl. the async judge). A new `OntologyEntitySync` (analog of `OntologyPageSync`) owns a single-thread daemon scheduler with pending dirty-ID sets: marks are O(1), a drain runs `coalesce.ms` after the first mark, fetches **current DB state** per entity, applies the shared `isPublic` ACL gate, and writes through `OntologyModelManager.replaceNamedGraph`/`removeNamedGraph` (preserving its snapshot-invalidation contract). Spec: `docs/superpowers/specs/2026-07-19-kg-change-events-design.md` — read it before starting any task.

**Tech Stack:** Java 25, Maven, JUnit 5, Mockito, Testcontainers-Postgres (existing KG test fixtures), Apache Jena (existing), no new dependencies.

## Global Constraints

- Sole developer, direct on `main`. **No per-task commits** — the single commit happens in Task 6 after the full IT reactor passes (repo policy: gate prod-code commits on `mvn clean install -Pintegration-tests -fae`). Tasks end with green module builds, not commits.
- Every **new** `.java` file MUST start with the 18-line ASF license header (copy verbatim from `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystem.java` lines 1–18) or `apache-rat:check` fails.
- Never swallow exceptions with empty catch blocks — minimum `LOG.warn` with context and message.
- Style: `final` on params/locals, spaces inside parens `( like this )`, match each file's existing import ordering.
- Integration tests sequential only — never `-T` with `-Pintegration-tests`; don't use `-T 1C` for wikantik-main unit runs (parallel-flaky).
- After signature changes run `mvn test-compile` (plain `compile` skips test sources).
- Config keys (exact): `wikantik.ontology.incremental.enabled` default `"true"`; `wikantik.ontology.incremental.coalesce.ms` default `"500"`.
- Event type constant (exact): `KgChangeEvent.KG_CHANGED = 60` (page events use 10–28, security 30–54).
- Commit message 1–3 lines ending `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Testcontainers-based tests are `@Testcontainers( disabledWithoutDocker = true )`; Docker is available in this environment — report actual run/skip status, never assume.

---

### Task 1: `KgChangeEvent` in wikantik-event (TDD)

**Files:**
- Create: `wikantik-event/src/main/java/com/wikantik/event/KgChangeEvent.java`
- Modify: `wikantik-event/src/main/java/com/wikantik/event/WikiEvent.java:32-33` (permits clause only)
- Test (create): `wikantik-event/src/test/java/com/wikantik/event/KgChangeEventTest.java`

**Interfaces:**
- Consumes: `WikiEvent( Object src, int type )` constructor; sealed permits list currently `permits WikiEngineEvent, WikiPageEvent, WikiSecurityEvent`.
- Produces (Tasks 2–4 rely on exactly): `public final class KgChangeEvent extends WikiEvent`; ctor `KgChangeEvent( Object src, Set<UUID> touchedEntityIds, Set<UUID> removedEntityIds )` (null sets → empty, defensive `Set.copyOf`); accessors `Set<UUID> touchedEntityIds()`, `Set<UUID> removedEntityIds()`; constant `public static final int KG_CHANGED = 60`.

- [ ] **Step 1: Write the failing test** — create `KgChangeEventTest.java` (ASF header first, then):

```java
package com.wikantik.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KgChangeEventTest {

    @Test
    void carriesDefensiveCopiesOfBothIdSets() {
        final UUID touched = UUID.randomUUID();
        final UUID removed = UUID.randomUUID();
        final Set< UUID > touchedIn = new HashSet<>( Set.of( touched ) );
        final Set< UUID > removedIn = new HashSet<>( Set.of( removed ) );
        final Object src = new Object();
        final KgChangeEvent event = new KgChangeEvent( src, touchedIn, removedIn );

        touchedIn.clear();
        removedIn.clear();

        assertEquals( Set.of( touched ), event.touchedEntityIds(), "must copy, not alias, the touched set" );
        assertEquals( Set.of( removed ), event.removedEntityIds(), "must copy, not alias, the removed set" );
        assertEquals( KgChangeEvent.KG_CHANGED, event.getType() );
        assertSame( src, event.getSrc() );
    }

    @Test
    void nullSetsNormalizeToEmpty() {
        final KgChangeEvent event = new KgChangeEvent( new Object(), null, null );
        assertTrue( event.touchedEntityIds().isEmpty() );
        assertTrue( event.removedEntityIds().isEmpty() );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test-compile -pl wikantik-event -q 2>&1 | tail -15`
Expected: COMPILE ERROR — `cannot find symbol: class KgChangeEvent`.

- [ ] **Step 3: Implement.** Edit `WikiEvent.java` — the sealed declaration

```java
public abstract sealed class WikiEvent extends EventObject
        permits WikiEngineEvent, WikiPageEvent, WikiSecurityEvent {
```

becomes

```java
public abstract sealed class WikiEvent extends EventObject
        permits WikiEngineEvent, WikiPageEvent, WikiSecurityEvent, KgChangeEvent {
```

Create `KgChangeEvent.java` (ASF header first, then):

```java
package com.wikantik.event;

import java.util.Set;
import java.util.UUID;

/**
 * Fired after a durable write to the Knowledge Graph ({@code kg_nodes}/{@code kg_edges}).
 *
 * <p>The payload is deliberately dumb — entity ids only, no KG domain types — so this
 * module stays dependency-free and consumers always re-read current database state
 * rather than trusting an event-time snapshot. {@code touchedEntityIds} are entities
 * whose ontology named graphs should be re-projected; {@code removedEntityIds} are
 * entities whose graphs should be dropped. Emitted by the two KG write funnels
 * ({@code DefaultKnowledgeGraphService}, {@code KgMaterializationService}) with the
 * emitting service instance as the {@code WikiEventManager} client object. See
 * {@code docs/superpowers/specs/2026-07-19-kg-change-events-design.md}.</p>
 */
public final class KgChangeEvent extends WikiEvent {

    /** KG content changed. Page events use 10&ndash;28, security events 30&ndash;54. */
    public static final int KG_CHANGED = 60;

    private static final long serialVersionUID = 1L;

    private final Set< UUID > touchedEntityIds;
    private final Set< UUID > removedEntityIds;

    public KgChangeEvent( final Object src, final Set< UUID > touchedEntityIds,
                          final Set< UUID > removedEntityIds ) {
        super( src, KG_CHANGED );
        this.touchedEntityIds = touchedEntityIds == null ? Set.of() : Set.copyOf( touchedEntityIds );
        this.removedEntityIds = removedEntityIds == null ? Set.of() : Set.copyOf( removedEntityIds );
    }

    /** Entity ids whose named graphs should be re-projected from current DB state. */
    public Set< UUID > touchedEntityIds() {
        return touchedEntityIds;
    }

    /** Entity ids whose named graphs should be removed from the ontology dataset. */
    public Set< UUID > removedEntityIds() {
        return removedEntityIds;
    }
}
```

- [ ] **Step 4: Run to verify green**

Run: `mvn test -pl wikantik-event -q 2>&1 | tail -15`
Expected: BUILD SUCCESS, `KgChangeEventTest` 2/2 plus all existing wikantik-event tests pass (the sealed-permits change is compile-checked by the whole module).

---

### Task 2: Emission from `DefaultKnowledgeGraphService` + provenance read methods (TDD, Testcontainers)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java` (7 mutation methods + 1 private helper; method bodies quoted below are current as of `45fab65340` — verify before splicing)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeRepository.java` (add `findEdgesByProvenance`)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KgNodeRepository.java` (add `findNodeIdsByProvenance`)
- Test (create): `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceEventTest.java`

**Interfaces:**
- Consumes: `KgChangeEvent` from Task 1; `WikiEventManager.fireEvent( Object client, WikiEvent event )` and `addWikiEventListener( Object client, WikiEventListener listener )` (both public static); existing repo methods `edges.findById( UUID )`, `edges.getEdgesForNode( UUID, "inbound"|"outbound" )`, `nodes.getNode( UUID )`.
- Produces (Task 3 relies on): `List<KgEdge> KgEdgeRepository.findEdgesByProvenance( UUID proposalId )` and `List<UUID> KgNodeRepository.findNodeIdsByProvenance( UUID proposalId )`.

- [ ] **Step 1: Write the failing test** — create `DefaultKnowledgeGraphServiceEventTest.java` (ASF header; mirror the fixture of the existing `DefaultKnowledgeGraphServiceTest` in the same package — same `PostgresTestContainer.createDataSource()` + `TestEngine` `@BeforeAll`, same `@BeforeEach` `DELETE FROM kg_edges/kg_proposals/kg_rejections/kg_nodes` cleanup and 6-arg service construction; read that class first and copy its setup verbatim):

```java
package com.wikantik.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.event.KgChangeEvent;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// + the @Testcontainers/@BeforeAll fixture copied from DefaultKnowledgeGraphServiceTest

class DefaultKnowledgeGraphServiceEventTest {

    /** Records every KgChangeEvent fired with the service under test as client. */
    private static final class RecordingListener implements WikiEventListener {
        final List< KgChangeEvent > events = new CopyOnWriteArrayList<>();
        @Override
        public void actionPerformed( final WikiEvent event ) {
            if ( event instanceof KgChangeEvent kce ) {
                events.add( kce );
            }
        }
    }

    private RecordingListener listener;
    // service field + construction in the copied @BeforeEach

    @BeforeEach
    void attachListener() {
        listener = new RecordingListener();
        WikiEventManager.addWikiEventListener( service, listener );
    }

    @AfterEach
    void detachListener() {
        WikiEventManager.removeWikiEventListener( listener );
    }

    private KgChangeEvent only() {
        assertEquals( 1, listener.events.size(), "expected exactly one KgChangeEvent" );
        return listener.events.get( 0 );
    }

    @Test
    void upsertNodeFiresTouchedNodeId() {
        final KgNode node = service.upsertNode( "EventNode", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        assertEquals( Set.of( node.id() ), only().touchedEntityIds() );
        assertTrue( only().removedEntityIds().isEmpty() );
    }

    @Test
    void upsertEdgeFiresTouchedSourceId() {
        final KgNode a = service.upsertNode( "EvA", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode b = service.upsertNode( "EvB", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        service.upsertEdge( a.id(), b.id(), "related_to", Provenance.HUMAN_CURATED, Map.of() );
        assertEquals( Set.of( a.id() ), only().touchedEntityIds() );
    }

    @Test
    void deleteEdgeFiresTouchedSourceIdViaPreDeleteLookup() {
        final KgNode a = service.upsertNode( "EvC", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode b = service.upsertNode( "EvD", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgEdge edge = service.upsertEdge( a.id(), b.id(), "related_to", Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        service.deleteEdge( edge.id() );
        assertEquals( Set.of( a.id() ), only().touchedEntityIds() );
    }

    @Test
    void deleteNodeFiresRemovedIdAndTouchedInEdgeSources() {
        final KgNode victim = service.upsertNode( "EvVictim", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode pointer = service.upsertNode( "EvPointer", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        service.upsertEdge( pointer.id(), victim.id(), "related_to", Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        service.deleteNode( victim.id() );
        assertEquals( Set.of( victim.id() ), only().removedEntityIds() );
        assertEquals( Set.of( pointer.id() ), only().touchedEntityIds() );
    }

    @Test
    void mergeNodesFiresRemovedSourceAndTouchedTargetPlusInboundSources() {
        final KgNode src = service.upsertNode( "EvMergeSrc", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode tgt = service.upsertNode( "EvMergeTgt", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        final KgNode inboundSrc = service.upsertNode( "EvInbound", "concept", null, Provenance.HUMAN_CURATED, Map.of() );
        service.upsertEdge( inboundSrc.id(), src.id(), "related_to", Provenance.HUMAN_CURATED, Map.of() );
        listener.events.clear();
        service.mergeNodes( src.id(), tgt.id() );
        assertEquals( Set.of( src.id() ), only().removedEntityIds() );
        assertEquals( Set.of( tgt.id(), inboundSrc.id() ), only().touchedEntityIds() );
    }

    @Test
    void confirmEdgeFiresTouchedSourceId() {
        final KgNode a = service.upsertNode( "EvConfA", "concept", null, Provenance.AI_INFERRED, Map.of() );
        final KgNode b = service.upsertNode( "EvConfB", "concept", null, Provenance.AI_INFERRED, Map.of() );
        final KgEdge edge = service.upsertEdge( a.id(), b.id(), "related_to", Provenance.AI_INFERRED, Map.of() );
        listener.events.clear();
        service.confirmEdge( edge.id(), "tester" );
        assertEquals( Set.of( a.id() ), only().touchedEntityIds() );
    }

    @Test
    void findByProvenanceReadsReturnPendingRows() {
        // Exercises the two NEW repository read methods Task 3 depends on.
        final UUID proposalId = UUID.randomUUID();
        final KgNodeRepository nodeRepo = new KgNodeRepository( dataSource );
        final KgEdgeRepository edgeRepo = new KgEdgeRepository( dataSource );
        final KgNode s = nodeRepo.upsertNodeWithProvenance( "EvProvS", "concept", null,
            Provenance.AI_INFERRED, Map.of(), "machine", proposalId );
        final KgNode t = nodeRepo.upsertNodeWithProvenance( "EvProvT", "concept", null,
            Provenance.AI_INFERRED, Map.of(), "machine", proposalId );
        edgeRepo.upsertEdgeWithProvenance( s.id(), t.id(), "related_to",
            Provenance.AI_INFERRED, Map.of(), "machine", proposalId );
        assertEquals( Set.of( s.id(), t.id() ),
            Set.copyOf( nodeRepo.findNodeIdsByProvenance( proposalId ) ) );
        final List< KgEdge > provEdges = edgeRepo.findEdgesByProvenance( proposalId );
        assertEquals( 1, provEdges.size() );
        assertEquals( s.id(), provEdges.get( 0 ).sourceId() );
    }
}
```

Note for the implementer: if `upsertNodeWithProvenance`/`upsertEdgeWithProvenance` signatures differ from the above, copy the exact call shapes used in `KgMaterializationService.materialize` (lines ~125–151) — those are the authoritative usages. Deletion order in the shared `@BeforeEach` cleanup must delete `kg_edges` before `kg_nodes` (FK).

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test-compile -pl wikantik-main -q 2>&1 | tail -15`
Expected: COMPILE ERROR on `findNodeIdsByProvenance` / `findEdgesByProvenance` (and the behavior tests would fail with 0 events if only the reads existed).

- [ ] **Step 3: Implement the two repository reads.** In `KgEdgeRepository.java`, next to `deleteEdgesByProvenance` (~line 323), following the file's exact connection/mapper idiom (`getAllEdges` ~327–342 is the loop template; `mapEdge( rs )` is the existing row mapper):

```java
    /** All edges materialized from the given proposal — read side of {@link #deleteEdgesByProvenance}. */
    public List< KgEdge > findEdgesByProvenance( final UUID proposalId ) {
        final List< KgEdge > result = new ArrayList<>();
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement(
                  "SELECT * FROM kg_edges WHERE provenance_proposal_id = ?" ) ) {
            ps.setObject( 1, proposalId );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    result.add( mapEdge( rs ) );
                }
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( "findEdgesByProvenance failed for " + proposalId, e );
        }
        return result;
    }
```

In `KgNodeRepository.java`, next to `deleteNodesByProvenance` (~line 406):

```java
    /** Ids of all nodes materialized from the given proposal — read side of {@link #deleteNodesByProvenance}. */
    public List< UUID > findNodeIdsByProvenance( final UUID proposalId ) {
        final List< UUID > result = new ArrayList<>();
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement(
                  "SELECT id FROM kg_nodes WHERE provenance_proposal_id = ?" ) ) {
            ps.setObject( 1, proposalId );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    result.add( rs.getObject( "id", UUID.class ) );
                }
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( "findNodeIdsByProvenance failed for " + proposalId, e );
        }
        return result;
    }
```

**IMPORTANT:** these two bodies are written from the described pattern, not copied from the file — before splicing, read the surrounding methods and match their exact exception-handling and helper style (if the file uses a `KgJdbcSupport` query helper or wraps SQLException differently, follow the file, keeping the method names/signatures from the Interfaces block).

- [ ] **Step 4: Implement emission in `DefaultKnowledgeGraphService`.** Add imports `com.wikantik.event.KgChangeEvent`, `com.wikantik.event.WikiEventManager`, `java.util.Set`, `java.util.HashSet` (match file import style). Add one private helper at the bottom of the class:

```java
    /** Fires a KgChangeEvent with this service as the event-bus client; no-ops on empty payloads. */
    private void fireKgChange( final Set< UUID > touched, final Set< UUID > removed ) {
        if ( ( touched == null || touched.isEmpty() ) && ( removed == null || removed.isEmpty() ) ) {
            return;
        }
        WikiEventManager.fireEvent( this, new KgChangeEvent( this, touched, removed ) );
    }
```

Then edit the seven methods — the fire always goes AFTER the repo write and `snapshotBuilder.invalidateCache()` (write-then-fire, page-save precedent), and delete paths do their lookups BEFORE the delete:

`upsertNode` — after `snapshotBuilder.invalidateCache();`, before `return result;`:
```java
        fireKgChange( Set.of( result.id() ), Set.of() );
```

`upsertEdge` — same position:
```java
        fireKgChange( Set.of( result.sourceId() ), Set.of() );
```

`confirmEdge` — after `snapshotBuilder.invalidateCache();`, before `return after;`:
```java
        fireKgChange( Set.of( after.sourceId() ), Set.of() );
```

`deleteEdge` — full new body:
```java
    @Override
    public void deleteEdge( final UUID id ) {
        final KgEdge edge = edges.findById( id );
        edges.deleteEdge( id );
        snapshotBuilder.invalidateCache();
        if ( edge != null ) {
            fireKgChange( Set.of( edge.sourceId() ), Set.of() );
        }
    }
```

`deleteEdgeAndRecordRejection` — full new body:
```java
    @Override
    public void deleteEdgeAndRecordRejection( final UUID edgeId, final String actor, final String reason ) {
        final KgEdge edge = edges.findById( edgeId );
        edges.deleteEdgeAndRecordRejection( edgeId, actor, reason );
        snapshotBuilder.invalidateCache();
        if ( edge != null ) {
            fireKgChange( Set.of( edge.sourceId() ), Set.of() );
        }
    }
```

`deleteNode` — full new body (in-edge sources collected pre-delete; their graphs hold the dangling statements):
```java
    @Override
    public void deleteNode( final UUID id ) {
        final Set< UUID > inboundSources = new HashSet<>();
        for ( final KgEdge edge : edges.getEdgesForNode( id, "inbound" ) ) {
            inboundSources.add( edge.sourceId() );
        }
        nodes.deleteNode( id );
        snapshotBuilder.invalidateCache();
        fireKgChange( inboundSources, Set.of( id ) );
    }
```

`mergeNodes` — keep the existing guards and re-pointing loops exactly as they are; collect ids during the inbound loop and fire at the end. The tail of the method (from the first `getEdgesForNode` call) becomes:
```java
        final List< KgEdge > outbound = edges.getEdgesForNode( sourceId, "outbound" );
        for ( final KgEdge edge : outbound ) {
            edges.upsertEdge( targetId, edge.targetId(), edge.relationshipType(),
                    edge.provenance(), edge.properties() );
        }
        final List< KgEdge > inbound = edges.getEdgesForNode( sourceId, "inbound" );
        final Set< UUID > touched = new HashSet<>();
        touched.add( targetId );
        for ( final KgEdge edge : inbound ) {
            edges.upsertEdge( edge.sourceId(), targetId, edge.relationshipType(),
                    edge.provenance(), edge.properties() );
            touched.add( edge.sourceId() );
        }
        nodes.deleteNode( sourceId );
        snapshotBuilder.invalidateCache();
        fireKgChange( touched, Set.of( sourceId ) );
```
(Outbound edge targets are NOT touched — their own named graphs hold their own outgoing statements and are unchanged by the merge. Do NOT add fire calls to `approveProposal`/`rejectProposal`/`judgeNow` — those delegate to `KgMaterializationService`, which fires in Task 3; firing here too would double-fire.)

- [ ] **Step 5: Run to verify green**

Run: `mvn test -pl wikantik-main -Dtest='DefaultKnowledgeGraphServiceEventTest,DefaultKnowledgeGraphServiceTest' -q 2>&1 | tail -15`
Expected: PASS — all new event tests plus the existing service tests (which must be unaffected; they attach no listener, and `fireKgChange` with no listeners registered is a no-op broadcast).

---

### Task 3: Emission from `KgMaterializationService` (TDD, Testcontainers)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgMaterializationService.java` (`materialize` + `retract` + fire helper)
- Test (create): `wikantik-main/src/test/java/com/wikantik/knowledge/judge/KgMaterializationServiceEventTest.java`

**Interfaces:**
- Consumes: `KgChangeEvent`, `WikiEventManager` (Task 1); `findEdgesByProvenance`/`findNodeIdsByProvenance` (Task 2).
- Produces: nothing new — behavior only.

- [ ] **Step 1: Write the failing test** — create `KgMaterializationServiceEventTest.java` (ASF header; copy the Testcontainers fixture and 4-arg `new KgMaterializationService( kgNodes, kgEdges, kgProposals, kgRejections )` construction plus proposal-building helpers from the existing `KgMaterializationServicePromoteRetractTest` in the same package — read it first; reuse its way of creating a persisted `KgProposal` with `proposalType "new-edge"` and `proposedData` containing `source`/`target`/`relationship`). Include the same `RecordingListener` inner class as Task 2's test (repeat it — tasks may run out of order), attached with `WikiEventManager.addWikiEventListener( materialization, listener )` in `@BeforeEach` and removed in `@AfterEach`. Tests:

```java
    @Test
    void materializeMachineFiresBothNodeIdsOnce() {
        final KgProposal proposal = persistNewEdgeProposal( "MatSrc", "MatTgt", "related_to" );
        materialization.materializeMachine( proposal );
        assertEquals( 1, listener.events.size(), "exactly one event per materialize call (no double-fire)" );
        final KgChangeEvent event = listener.events.get( 0 );
        assertEquals( 2, event.touchedEntityIds().size(), "both endpoint node ids touched" );
        assertTrue( event.removedEntityIds().isEmpty() );
    }

    @Test
    void retractFiresRemovedNodesAndNoLongerExistingRowsAreGone() {
        final KgProposal proposal = persistNewEdgeProposal( "RetSrc", "RetTgt", "related_to" );
        materialization.materializeMachine( proposal );
        listener.events.clear();
        materialization.retract( proposal );
        assertEquals( 1, listener.events.size() );
        final KgChangeEvent event = listener.events.get( 0 );
        assertEquals( 2, event.removedEntityIds().size(),
            "both provenance-created nodes removed" );
        assertTrue( event.touchedEntityIds().isEmpty(),
            "edge sources that are themselves removed must not also be touched" );
    }

    @Test
    void unsupportedProposalTypeFiresNothing() {
        final KgProposal proposal = persistProposalOfType( "new-node" );
        materialization.materializeMachine( proposal );
        assertTrue( listener.events.isEmpty() );
    }
```

(`persistNewEdgeProposal`/`persistProposalOfType` are whatever helper shapes `KgMaterializationServicePromoteRetractTest` already uses — mirror them exactly; if it inlines proposal construction, inline the same way.)

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=KgMaterializationServiceEventTest -q 2>&1 | tail -15`
Expected: FAIL — `expected exactly one event... but was 0` (compiles fine — Task 1's event type exists — but no emission yet).

- [ ] **Step 3: Implement.** In `KgMaterializationService.java`, add imports (`com.wikantik.event.KgChangeEvent`, `com.wikantik.event.WikiEventManager`, `java.util.Set`, `java.util.HashSet`, `java.util.List`, `java.util.UUID` as needed) and the same private helper as Task 2 (repeat it — small, and the two services are independent emitters):

```java
    /** Fires a KgChangeEvent with this service as the event-bus client; no-ops on empty payloads. */
    private void fireKgChange( final Set< java.util.UUID > touched, final Set< java.util.UUID > removed ) {
        if ( ( touched == null || touched.isEmpty() ) && ( removed == null || removed.isEmpty() ) ) {
            return;
        }
        WikiEventManager.fireEvent( this, new KgChangeEvent( this, touched, removed ) );
    }
```

`retract` — full new body (pre-delete reads from Task 2):

```java
    public void retract( final KgProposal proposal ) {
        final List< KgEdge > doomedEdges = edges.findEdgesByProvenance( proposal.id() );
        final List< UUID > doomedNodeIds = nodes.findNodeIdsByProvenance( proposal.id() );
        edges.deleteEdgesByProvenance( proposal.id() );
        nodes.deleteNodesByProvenance( proposal.id() );
        final Set< UUID > removed = new HashSet<>( doomedNodeIds );
        final Set< UUID > touched = new HashSet<>();
        for ( final KgEdge edge : doomedEdges ) {
            if ( !removed.contains( edge.sourceId() ) ) {
                touched.add( edge.sourceId() );
            }
        }
        fireKgChange( touched, removed );
    }
```

(Import `KgEdge` if not already imported; check the file's existing imports.)

`materialize` — keep everything up to and including the two `upsertNodeWithProvenance` calls unchanged, then thread the fires through the three post-upsert exits:

```java
        final KgNode src = nodes.upsertNodeWithProvenance( source, "concept", null,
            Provenance.AI_INFERRED, Map.of(), tier, proposal.id() );
        final KgNode tgt = nodes.upsertNodeWithProvenance( target, "concept", null,
            Provenance.AI_INFERRED, Map.of(), tier, proposal.id() );
        if ( src == null || tgt == null ) {
            LOG.warn( "materialize: skipping edge for proposal {} — node excluded by KG inclusion policy "
                + "(source='{}' present={}; target='{}' present={})",
                proposal.id(), source, src != null, target, tgt != null );
            // Whichever node WAS written is a durable change — fire for it.
            final Set< UUID > written = new HashSet<>();
            if ( src != null ) { written.add( src.id() ); }
            if ( tgt != null ) { written.add( tgt.id() ); }
            fireKgChange( written, Set.of() );
            return;
        }
        if ( ontologyValidator != null && src.nodeType() != null && tgt.nodeType() != null ) {
            final var violations = ontologyValidator.validateEdge( src.nodeType(), rel, tgt.nodeType() );
            if ( !violations.isEmpty() ) {
                skippedNonConformant.incrementAndGet();
                LOG.info( "materialize: rejected ontology-non-conformant edge for proposal {} "
                    + "({} --{}--> {}): {} [skipped by SHACL gate, count={}]",
                    proposal.id(), src.nodeType(), rel, tgt.nodeType(),
                    violations.get( 0 ).message(), skippedNonConformant.get() );
                // Both node upserts already happened — the SHACL gate only skipped the edge.
                fireKgChange( Set.of( src.id(), tgt.id() ), Set.of() );
                return;
            }
        }
        edges.upsertEdgeWithProvenance( src.id(), tgt.id(), rel,
            Provenance.AI_INFERRED, Map.of(), tier, proposal.id() );
        fireKgChange( Set.of( src.id(), tgt.id() ), Set.of() );
```

The two EARLY exits (unsupported `proposalType`, missing source/target/relationship) stay fire-free — nothing was written. `promoteToHuman` needs no edit: its `materialize(...)` call fires, and the rejection-row delete is `kg_rejections` (not an entity table).

- [ ] **Step 4: Run to verify green**

Run: `mvn test -pl wikantik-main -Dtest='KgMaterializationServiceEventTest,KgMaterializationServicePromoteRetractTest,KgMaterializationServiceMaterializeTest,KgMaterializationServiceNullGuardTest' -q 2>&1 | tail -15`
Expected: PASS — new event tests plus all three existing materialization test classes unaffected.

---

### Task 4: `OntologyEntitySync` + `KgChangeEventListener` (TDD, in-memory manager)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyEntitySync.java`
- Create: `wikantik-main/src/main/java/com/wikantik/ontology/runtime/KgChangeEventListener.java`
- Test (create): `wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyEntitySyncTest.java`

**Interfaces:**
- Consumes: `KgChangeEvent` (Task 1); `OntologyModelManager` (`inMemory()`, `loadTBox()`, `replaceNamedGraph( String, Model )`, `removeNamedGraph( String )`, `namedGraphExists( String )`); `EntityProjector.project( KgNode, List<KgEdge>, Function<String,String> )`; `Iris.entity( UUID )`; `KgNodeRepository.getNode( UUID )`; `KgEdgeRepository.getEdgesForNode( UUID, String )`; `PageCanonicalIdsDao.findBySlug( String )` → `Optional<Row>` with `canonicalId()`.
- Produces (Task 5 relies on): `OntologyEntitySync( OntologyModelManager, KgNodeRepository, KgEdgeRepository, PageCanonicalIdsDao, Predicate<String> isPublic, long coalesceMs )` public ctor (package-private overload additionally takes `ScheduledExecutorService`); `void mark( Set<UUID> touched, Set<UUID> removed )`; package-private `void drainNow()`; `void close()`. `KgChangeEventListener( OntologyEntitySync )` with `void register( Object kgService, Object kgMaterialization )`.

- [ ] **Step 1: Write the failing test** — create `OntologyEntitySyncTest.java` (ASF header; template is the existing `OntologyPageSyncTest` in the same package — `@ExtendWith(MockitoExtension.class)`, `OntologyModelManager.inMemory()` + `loadTBox()`; read it first and mirror its setup style):

```java
package com.wikantik.ontology.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith( MockitoExtension.class )
@MockitoSettings( strictness = Strictness.LENIENT )
class OntologyEntitySyncTest {

    @Mock private KgNodeRepository nodes;
    @Mock private KgEdgeRepository edges;
    @Mock private PageCanonicalIdsDao pageDao;
    @Mock private ScheduledExecutorService scheduler; // never executes — tests call drainNow() directly

    private OntologyModelManager manager;
    private OntologyEntitySync sync;

    private static KgNode node( final UUID id, final String name, final String sourcePage ) {
        return new KgNode( id, name, "concept", sourcePage, Provenance.HUMAN_CURATED,
            Map.of(), Instant.now(), Instant.now(), "human", null );
    }

    private static KgEdge edge( final UUID source, final UUID target ) {
        return new KgEdge( UUID.randomUUID(), source, target, "related_to", Provenance.HUMAN_CURATED,
            Map.of(), Instant.now(), Instant.now(), "human", null );
    }

    @BeforeEach
    void setUp() {
        manager = OntologyModelManager.inMemory();
        manager.loadTBox();
        when( pageDao.findBySlug( any() ) ).thenReturn( Optional.empty() );
        sync = new OntologyEntitySync( manager, nodes, edges, pageDao, slug -> true, 500L, scheduler );
    }

    @Test
    void touchedEntityGetsProjectedIntoItsNamedGraph() {
        final UUID id = UUID.randomUUID();
        when( nodes.getNode( id ) ).thenReturn( node( id, "Alpha", null ) );
        when( edges.getEdgesForNode( id, "outbound" ) ).thenReturn( List.of() );
        sync.mark( Set.of( id ), Set.of() );
        sync.drainNow();
        assertTrue( manager.namedGraphExists( Iris.entity( id ) ) );
    }

    @Test
    void coalescingProjectsOncePerDrainAndSchedulesOnce() {
        final UUID id = UUID.randomUUID();
        when( nodes.getNode( id ) ).thenReturn( node( id, "Beta", null ) );
        when( edges.getEdgesForNode( id, "outbound" ) ).thenReturn( List.of() );
        sync.mark( Set.of( id ), Set.of() );
        sync.mark( Set.of( id ), Set.of() );
        sync.mark( Set.of( id ), Set.of() );
        verify( scheduler, times( 1 ) ).schedule( any( Runnable.class ), anyLong(), any( TimeUnit.class ) );
        sync.drainNow();
        verify( nodes, times( 1 ) ).getNode( id );
    }

    @Test
    void removedEntityGraphIsDropped() {
        final UUID id = UUID.randomUUID();
        when( nodes.getNode( id ) ).thenReturn( node( id, "Gamma", null ) );
        when( edges.getEdgesForNode( id, "outbound" ) ).thenReturn( List.of() );
        sync.mark( Set.of( id ), Set.of() );
        sync.drainNow();
        assertTrue( manager.namedGraphExists( Iris.entity( id ) ) );
        sync.mark( Set.of(), Set.of( id ) );
        sync.drainNow();
        assertFalse( manager.namedGraphExists( Iris.entity( id ) ) );
    }

    @Test
    void nodeGoneAtDrainTimeRemovesTheGraph() {
        final UUID id = UUID.randomUUID();
        when( nodes.getNode( id ) ).thenReturn( null );
        sync.mark( Set.of( id ), Set.of() );
        sync.drainNow();
        assertFalse( manager.namedGraphExists( Iris.entity( id ) ) );
    }

    @Test
    void restrictedSourcePageRemovesTheGraph() {
        final UUID id = UUID.randomUUID();
        sync = new OntologyEntitySync( manager, nodes, edges, pageDao,
            slug -> false, 500L, scheduler ); // nothing is public
        when( nodes.getNode( id ) ).thenReturn( node( id, "Delta", "SecretPage" ) );
        sync.mark( Set.of( id ), Set.of() );
        sync.drainNow();
        assertFalse( manager.namedGraphExists( Iris.entity( id ) ) );
    }

    @Test
    void edgeToRestrictedTargetIsFilteredOut() {
        final UUID src = UUID.randomUUID();
        final UUID pubTgt = UUID.randomUUID();
        final UUID privTgt = UUID.randomUUID();
        sync = new OntologyEntitySync( manager, nodes, edges, pageDao,
            "PublicPage"::equals, 500L, scheduler );
        when( nodes.getNode( src ) ).thenReturn( node( src, "Src", null ) );
        when( nodes.getNode( pubTgt ) ).thenReturn( node( pubTgt, "PubTgt", "PublicPage" ) );
        when( nodes.getNode( privTgt ) ).thenReturn( node( privTgt, "PrivTgt", "SecretPage" ) );
        when( edges.getEdgesForNode( src, "outbound" ) )
            .thenReturn( List.of( edge( src, pubTgt ), edge( src, privTgt ) ) );
        sync.mark( Set.of( src ), Set.of() );
        sync.drainNow();
        final String graph = manager.unionSnapshot().toString(); // any read that exposes triples
        assertTrue( manager.namedGraphExists( Iris.entity( src ) ) );
        // The projected statements must reference the public target IRI but never the private one.
        assertTrue( graph.contains( privTgt.toString() ) == false,
            "restricted-target edge must not be projected" );
    }

    @Test
    void drainContinuesPastAPerEntityFailure() {
        final UUID bad = UUID.randomUUID();
        final UUID good = UUID.randomUUID();
        when( nodes.getNode( bad ) ).thenThrow( new RuntimeException( "boom" ) );
        when( nodes.getNode( good ) ).thenReturn( node( good, "Good", null ) );
        when( edges.getEdgesForNode( good, "outbound" ) ).thenReturn( List.of() );
        sync.mark( Set.of( bad, good ), Set.of() );
        sync.drainNow(); // must not throw
        assertTrue( manager.namedGraphExists( Iris.entity( good ) ),
            "the good entity must still be projected after the bad one failed" );
    }
}
```

Implementer notes: if `OntologyModelManager` lacks a public `unionSnapshot()` (check `OntologyPageSyncTest` for how it inspects triples), assert the restricted-edge case by querying the entity's named graph model directly instead — the assertion that matters is "no statement references the private target's IRI". If `KgNode`/`KgEdge` record component orders differ from the `node(...)`/`edge(...)` helpers above, fix the helpers to the actual record order (they are `wikantik-api` records: `KgNode( id, name, nodeType, sourcePage, provenance, properties, created, modified, tier, provenanceProposalId )`, `KgEdge( id, sourceId, targetId, relationshipType, provenance, properties, created, modified, tier, provenanceProposalId )`).

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test-compile -pl wikantik-main -q 2>&1 | tail -15`
Expected: COMPILE ERROR — `OntologyEntitySync` does not exist.

- [ ] **Step 3: Implement `OntologyEntitySync.java`** (ASF header first):

```java
package com.wikantik.ontology.runtime;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.projection.EntityProjector;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event-incremental ENTITY sync — the Knowledge Graph analog of {@link OntologyPageSync}.
 *
 * <p>KG writes mark entity ids dirty via {@link #mark}; a single daemon thread drains the
 * pending sets {@code coalesceMs} after the first mark, so a judge batch touching one
 * entity N times re-projects it once. The drain reads CURRENT database state (never
 * event-time snapshots — that is what makes coalescing safe), applies the same
 * public/restricted ACL gate as full rebuilds (node public iff its source page is
 * anonymously viewable or absent; an edge additionally needs a public target), and writes
 * through {@link OntologyModelManager#replaceNamedGraph}/{@code removeNamedGraph} to keep
 * the snapshot-invalidation contract. Per-entity failures are logged and skipped — the
 * nightly full rebuild reconciles misses. See
 * {@code docs/superpowers/specs/2026-07-19-kg-change-events-design.md}.</p>
 */
public final class OntologyEntitySync implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger( OntologyEntitySync.class );

    private final OntologyModelManager manager;
    private final KgNodeRepository nodes;
    private final KgEdgeRepository edges;
    private final PageCanonicalIdsDao pageDao;
    private final Predicate< String > isPublic;
    private final long coalesceMs;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private final Set< UUID > pendingTouched = new HashSet<>();
    private final Set< UUID > pendingRemoved = new HashSet<>();
    private boolean drainScheduled;

    public OntologyEntitySync( final OntologyModelManager manager, final KgNodeRepository nodes,
                               final KgEdgeRepository edges, final PageCanonicalIdsDao pageDao,
                               final Predicate< String > isPublic, final long coalesceMs ) {
        this( manager, nodes, edges, pageDao, isPublic, coalesceMs, defaultScheduler() );
    }

    OntologyEntitySync( final OntologyModelManager manager, final KgNodeRepository nodes,
                        final KgEdgeRepository edges, final PageCanonicalIdsDao pageDao,
                        final Predicate< String > isPublic, final long coalesceMs,
                        final ScheduledExecutorService scheduler ) {
        this.manager = manager;
        this.nodes = nodes;
        this.edges = edges;
        this.pageDao = pageDao;
        this.isPublic = isPublic;
        this.coalesceMs = coalesceMs;
        this.scheduler = scheduler;
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-ontology-entity-sync" );
            t.setDaemon( true );
            return t;
        } );
    }

    /** Marks entities dirty; O(1) on the caller's thread. Null sets are treated as empty. */
    public void mark( final Set< UUID > touched, final Set< UUID > removed ) {
        synchronized ( lock ) {
            if ( touched != null ) {
                pendingTouched.addAll( touched );
            }
            if ( removed != null ) {
                pendingRemoved.addAll( removed );
            }
            if ( !drainScheduled && !( pendingTouched.isEmpty() && pendingRemoved.isEmpty() ) ) {
                try {
                    scheduler.schedule( this::drainNow, coalesceMs, TimeUnit.MILLISECONDS );
                    drainScheduled = true;
                } catch ( final RejectedExecutionException e ) {
                    LOG.warn( "entity-sync: drain rejected (executor shutting down?): {}", e.getMessage() );
                }
            }
        }
    }

    /** One drain, synchronously. Scheduled-task body; package-private for deterministic tests. */
    void drainNow() {
        final Set< UUID > touched;
        final Set< UUID > removed;
        synchronized ( lock ) {
            touched = new HashSet<>( pendingTouched );
            removed = new HashSet<>( pendingRemoved );
            pendingTouched.clear();
            pendingRemoved.clear();
            drainScheduled = false;
        }
        for ( final UUID id : removed ) {
            try {
                manager.removeNamedGraph( Iris.entity( id ) );
            } catch ( final RuntimeException e ) {
                LOG.warn( "entity-sync: failed to remove graph for {} (nightly rebuild reconciles): {}",
                    id, e.getMessage() );
            }
        }
        touched.removeAll( removed );
        for ( final UUID id : touched ) {
            try {
                projectOne( id );
            } catch ( final RuntimeException e ) {
                LOG.warn( "entity-sync: failed to re-project {} (nightly rebuild reconciles): {}",
                    id, e.getMessage() );
            }
        }
    }

    /** Projects one entity from current DB state, or removes its graph if gone/restricted. */
    private void projectOne( final UUID id ) {
        final KgNode node = nodes.getNode( id );
        if ( node == null || ( node.sourcePage() != null && !isPublic.test( node.sourcePage() ) ) ) {
            manager.removeNamedGraph( Iris.entity( id ) );
            return;
        }
        final List< KgEdge > outgoing = edges.getEdgesForNode( id, "outbound" );
        final List< KgEdge > publicEdges = new ArrayList<>( outgoing.size() );
        for ( final KgEdge edge : outgoing ) {
            final KgNode target = nodes.getNode( edge.targetId() );
            if ( target != null
                    && ( target.sourcePage() == null || isPublic.test( target.sourcePage() ) ) ) {
                publicEdges.add( edge );
            }
        }
        final Model graph = EntityProjector.project( node, publicEdges,
            slug -> pageDao.findBySlug( slug )
                .map( PageCanonicalIdsDao.Row::canonicalId )
                .orElse( null ) );
        manager.replaceNamedGraph( Iris.entity( id ), graph );
    }

    /** Drain-and-stop, mirroring AsyncEmbeddingIndexListener.close(): 5s await then force. */
    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if ( !scheduler.awaitTermination( 5, TimeUnit.SECONDS ) ) {
                LOG.warn( "entity-sync scheduler did not drain within 5s; forcing shutdown" );
                scheduler.shutdownNow();
            }
        } catch ( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}
```

- [ ] **Step 4: Implement `KgChangeEventListener.java`** (ASF header first):

```java
package com.wikantik.ontology.runtime;

import com.wikantik.event.KgChangeEvent;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;

/**
 * Bridges {@link KgChangeEvent}s from the two KG write funnels into
 * {@link OntologyEntitySync}. Registered per emitting service instance
 * (the WikiEventManager client object), mirroring how {@link OntologyEventListener}
 * registers on the page/filter managers.
 */
public final class KgChangeEventListener implements WikiEventListener {

    private final OntologyEntitySync sync;

    public KgChangeEventListener( final OntologyEntitySync sync ) {
        this.sync = sync;
    }

    /** Attaches to both emitting service instances; null clients are skipped (KG disabled). */
    public void register( final Object kgService, final Object kgMaterialization ) {
        if ( kgService != null ) {
            WikiEventManager.addWikiEventListener( kgService, this );
        }
        if ( kgMaterialization != null ) {
            WikiEventManager.addWikiEventListener( kgMaterialization, this );
        }
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof KgChangeEvent kce ) {
            sync.mark( kce.touchedEntityIds(), kce.removedEntityIds() );
        }
    }
}
```

- [ ] **Step 5: Run to verify green**

Run: `mvn test -pl wikantik-main -Dtest='OntologyEntitySyncTest,OntologyPageSyncTest' -q 2>&1 | tail -15`
Expected: PASS — all new sync tests plus the existing page-sync tests unaffected.

---

### Task 5: Wiring, config, docs

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java` (signature + wiring block)
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:~1129` (the single `wireOntology` call site)
- Modify: `docs/OntologyManagement.md:~233-236` (config block)
- Modify: `CLAUDE.md` (wikantik-ontology module bullet)
- Test: existing suites only (wiring is exercised by Task 6's IT; a unit test of `wireOntology` would need a full engine — out of scope, matching how the existing page-sync wiring is untested at unit level)

**Interfaces:**
- Consumes: `OntologyEntitySync` + `KgChangeEventListener` (Task 4); `KnowledgeSubsystem.Services` accessors `kgService()`, `kgMaterialization()`.
- Produces: `wireOntology( WikiEngine, Properties, DataSource, PageManager, FilterManager, KnowledgeSubsystem.Services )` — note the NEW sixth parameter.

- [ ] **Step 1: Extend `wireOntology`.** Add the sixth parameter to the signature:

```java
    public static OntologyRebuildCoordinator wireOntology( final WikiEngine engine,
                                     final Properties props,
                                     final DataSource dataSource,
                                     final PageManager pageManager,
                                     final com.wikantik.filters.FilterManager filterManager,
                                     final com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services knowledgeServices ) {
```

Then insert this block immediately AFTER the existing `new OntologyEventListener( pageSync ).register( pageManager, filterManager );` line and BEFORE the nightly-scheduler block (`nodeRepo`, `edgeRepo`, `pageDao`, `isPublic`, `mgr` are all already in scope at that point):

```java
        // Event-incremental ENTITY sync: KgChangeEvent from the two KG write funnels →
        // coalesced re-projection of affected entity graphs. Same ACL gate as rebuilds
        // and page sync; the nightly rebuild below reconciles anything missed.
        final boolean incrementalEnabled = Boolean.parseBoolean(
                props.getProperty( "wikantik.ontology.incremental.enabled", "true" ) );
        if ( incrementalEnabled && knowledgeServices != null
                && knowledgeServices.kgService() != null
                && knowledgeServices.kgMaterialization() != null ) {
            final long coalesceMs = Long.parseLong(
                    props.getProperty( "wikantik.ontology.incremental.coalesce.ms", "500" ) );
            final OntologyEntitySync entitySync = new OntologyEntitySync(
                    mgr, nodeRepo, edgeRepo, pageDao, isPublic, coalesceMs );
            new KgChangeEventListener( entitySync ).register(
                    knowledgeServices.kgService(), knowledgeServices.kgMaterialization() );
            LOG.info( "ontology incremental entity sync wired (coalesce={}ms)", coalesceMs );
        } else if ( !incrementalEnabled ) {
            LOG.info( "ontology incremental entity sync disabled (wikantik.ontology.incremental.enabled=false)" );
        }
```

(When `knowledgeServices.kgService()` is null — KG disabled — the block silently skips, matching how a disabled subsystem behaves elsewhere; the `else if` keeps the explicit-disable log honest. The `kgService()` accessor is a plain record component here, NOT the retrieval-holder accessor — no post-boot timing concern.)

- [ ] **Step 2: Update the call site.** In `WikiEngine.java` (~line 1129), the call

```java
        final com.wikantik.ontology.runtime.OntologyRebuildCoordinator ontologyCoordinator =
                com.wikantik.ontology.runtime.OntologyWiringHelper.wireOntology( this, props, ds, pageManager, filterManager );
```

gains the already-in-scope `svcs` (built at ~line 1081, BEFORE this call — verified ordering):

```java
        final com.wikantik.ontology.runtime.OntologyRebuildCoordinator ontologyCoordinator =
                com.wikantik.ontology.runtime.OntologyWiringHelper.wireOntology( this, props, ds, pageManager, filterManager, svcs );
```

(If the local variable is named differently at the call site, use the actual name of the `KnowledgeSubsystemFactory.create(...)` result; do NOT use `engine.getKnowledgeSubsystem()` — the snapshot field is assigned only at the end of `initialize()` and is null here.)

- [ ] **Step 3: Compile + targeted tests**

Run: `mvn test-compile -pl wikantik-main -q 2>&1 | tail -10` then `mvn test -pl wikantik-main -Dtest='OntologyEntitySyncTest,OntologyPageSyncTest' -q 2>&1 | tail -10`
Expected: BUILD SUCCESS both. (Any other `wireOntology` caller surfaces here as a compile error — fix it with the same new argument; a test-fixture caller may pass `null` for `knowledgeServices`, which cleanly disables the block.)

- [ ] **Step 4: Docs.** In `docs/OntologyManagement.md` (config lines ~233–236), extend the config sentence to include: `wikantik.ontology.incremental.enabled` (default `true` — event-incremental entity sync; `false` restores nightly-only behavior) and `wikantik.ontology.incremental.coalesce.ms` (default `500`). In `CLAUDE.md`'s **wikantik-ontology** module bullet: replace the parenthetical `(no KG-change events exist)` in the event-incremental sentence with a clause noting `KgChangeEvent` now fires from both KG write funnels and `OntologyEntitySync` re-projects entity graphs incrementally (coalesced, async), with the nightly `OntologyRebuildScheduler` demoted to reconciliation backstop; append the two new config keys to the module bullet's config list.

---

### Task 6: Integration test, full verification, single commit

**Files:**
- Test (create): `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/KgIncrementalOntologySyncIT.java`
- No production files.

**Interfaces:**
- Consumes: everything from Tasks 1–5; existing IT plumbing in the same package — `EdgeCurationIT` (admin login `loginAsAdmin()` with `janne`/`myP@5sw0rd` via `POST /api/auth/login`, node/edge creation against `POST /admin/knowledge-graph/edges` and its node-setup calls, `secureCookieOverHttp()` wrapper) and `OntologyPublicEndpointsIT` (anonymous `/sparql` querying via `anon( "/sparql?query=" + q, "application/sparql-results+json" )`, deadline-poll loop shape from `rebuildAndAwaitIdle`).
- Produces: the final commit.

- [ ] **Step 1: Write the IT.** Create `KgIncrementalOntologySyncIT.java` (ASF header; copy the class-level scaffolding — base URL helpers, GSON, cookie handling, `loginAsAdmin` — from `EdgeCurationIT`, and the anonymous-SPARQL helper from `OntologyPublicEndpointsIT`; read both first). One test method:

```java
    /**
     * The end-to-end proof of incremental sync: a freshly curated node becomes visible
     * to anonymous /sparql WITHOUT any ontology rebuild being triggered. Startup's
     * rebuildIfEmpty ran long before this test creates the node, so only the
     * KgChangeEvent -> OntologyEntitySync path can make it appear.
     */
    @Test
    void curatedNodeAppearsInSparqlWithoutRebuild() throws Exception {
        loginAsAdmin();
        final String nodeName = "IncrementalSyncProbe" + System.currentTimeMillis();
        // Create the node via the same admin surface EdgeCurationIT uses (mirror its
        // node-creation call exactly — endpoint, body shape, and status assertion).
        createNode( nodeName, "concept" );

        final String q = java.net.URLEncoder.encode(
            "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#label> \"" + nodeName + "\" }",
            java.nio.charset.StandardCharsets.UTF_8 );
        final long deadline = System.currentTimeMillis() + 30_000;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > sel = anon( "/sparql?query=" + q, "application/sparql-results+json" );
            if ( sel.statusCode() == 200 && sel.body().contains( nodeName ) ) {
                return; // incremental sync delivered the entity — pass
            }
            Thread.sleep( 500 );
        }
        fail( "curated node '" + nodeName + "' never appeared in /sparql within 30s "
            + "(incremental entity sync did not fire; check KgChangeEvent wiring)" );
    }
```

Implementer notes: `createNode` means "whatever `EdgeCurationIT` does to create its fixture nodes" — if it creates nodes via `POST /admin/knowledge-graph/nodes`, mirror that; if it creates them implicitly through edge upserts, create two nodes by upserting an edge between two fresh names and probe for the SOURCE node's label. The assertion must NOT call `/admin/ontology/rebuild` anywhere. A stub node created via curation has `sourcePage = null`, which the ACL gate treats as public — no page fixtures needed. Register the new IT class the same way its siblings are registered if the module uses a suite/includes list (check the module's pom failsafe includes — `*IT.java` is usually auto-included).

- [ ] **Step 2: Run the ONE new IT module** (sanity before the full reactor)

Run: `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-rest -am -Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=KgIncrementalOntologySyncIT 2>&1 | tail -25`
(Long-running: use the background nohup>log + poll pattern; a foreground call gets wall-killed. If `-Dit.test` filtering doesn't take in this module, run the module without the filter and check the new IT's line in the failsafe summary.)
Expected: the new IT passes.

- [ ] **Step 3: Full unit build**

Run (background + poll, `WIKANTIK_*` env unset): `mvn clean install -DskipITs -q`
Expected: BUILD SUCCESS (includes apache-rat header check over the five new files).

- [ ] **Step 4: Full IT reactor** (sequential, no `-T`)

Run (background + poll): `mvn clean install -Pintegration-tests -fae -Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS all modules. Known acceptable flake: `EditIT#createPageAndTestEditPermissions` — if it is the ONLY red, re-run that module alone to confirm, then proceed. Any other failure: stop, do not commit, report the failing tail.

- [ ] **Step 5: Single commit** (verify `git status --porcelain` shows ONLY these files first; never `git add -A`):

```bash
git add \
  wikantik-event/src/main/java/com/wikantik/event/KgChangeEvent.java \
  wikantik-event/src/main/java/com/wikantik/event/WikiEvent.java \
  wikantik-event/src/test/java/com/wikantik/event/KgChangeEventTest.java \
  wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
  wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeRepository.java \
  wikantik-main/src/main/java/com/wikantik/knowledge/KgNodeRepository.java \
  wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgMaterializationService.java \
  wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyEntitySync.java \
  wikantik-main/src/main/java/com/wikantik/ontology/runtime/KgChangeEventListener.java \
  wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java \
  wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
  wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceEventTest.java \
  wikantik-main/src/test/java/com/wikantik/knowledge/judge/KgMaterializationServiceEventTest.java \
  wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyEntitySyncTest.java \
  wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/KgIncrementalOntologySyncIT.java \
  docs/OntologyManagement.md \
  CLAUDE.md \
  docs/superpowers/plans/2026-07-19-kg-change-events.md
git commit -m "feat: KgChangeEvent + OntologyEntitySync — event-incremental ontology entity sync (coalesced async re-projection from both KG write funnels; nightly rebuild demoted to reconciliation backstop)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Risks / explicitly out of scope (from the spec)

- Page-ACL-flip → entity re-projection: deferred, nightly covers (user decision).
- Rebuild-vs-incremental interleave: accepted race, documented in the spec §5; no locking added.
- `materialize`'s three-autocommit non-atomicity: pre-existing, unchanged.
- The quoted current-code excerpts (repo method bodies, wiring body) reflect `45fab65340`; implementers verify against the working tree before splicing and reconcile drift in favor of the tree, keeping the plan's *semantic* requirements (touched/removed sets per the spec's emission tables) fixed.
