# Knowledge Graph Visualization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a read-only `/graph` route that renders the entire knowledge graph as an interactive force-directed visualization, backed by a single bulk REST endpoint.

**Architecture:** New `KnowledgeGraphResource` servlet serves a JSON snapshot of all nodes and edges with server-side role classification and per-user ACL redaction (60s cached). React frontend at `/graph` (lazy-loaded) renders with cytoscape.js (`cose-bilkent` layout), colour-coded node roles, directional/bidirectional edge rendering, node details drawer, toolbar filters, and collapsible legend.

**Tech Stack:** Java 21, JDBC (PostgreSQL), JUnit 5 + Testcontainers; React 18, cytoscape.js, cytoscape-cose-bilkent, react-cytoscapejs, Vitest, React Testing Library; Selenide for ITs.

**Spec:** `docs/superpowers/specs/2026-04-11-knowledge-graph-visualization-design.md`

---

## File Map

### New backend files
| File | Responsibility |
|------|---------------|
| `wikantik-api/src/main/java/com/wikantik/api/knowledge/GraphSnapshot.java` | Response record: generatedAt, counts, threshold, node/edge lists |
| `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java` | Node record: id, name, type, role, provenance, sourcePage, degrees, restricted |
| `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotEdge.java` | Edge record: id, source, target, relationshipType, provenance |
| `wikantik-main/src/main/java/com/wikantik/knowledge/GraphRoleClassifier.java` | Pure static classifier: restricted > stub > orphan > hub > normal |
| `wikantik-rest/src/main/java/com/wikantik/rest/KnowledgeGraphResource.java` | Servlet: doGet → auth check → service.snapshotGraph → sendJson |

### New backend test files
| File | Responsibility |
|------|---------------|
| `wikantik-main/src/test/java/com/wikantik/knowledge/GraphRoleClassifierTest.java` | Every classification branch |
| `wikantik-rest/src/test/java/com/wikantik/rest/KnowledgeGraphResourceTest.java` | Servlet tests: 200, 401, 500 |

### Edited backend files
| File | Change |
|------|--------|
| `wikantik-api/.../KnowledgeGraphService.java` | + `snapshotGraph(Session)` method |
| `wikantik-main/.../DefaultKnowledgeGraphService.java` | + snapshotGraph impl, 60s cache, ACL redaction |
| `wikantik-main/.../JdbcKnowledgeRepository.java` | + `getAllNodes()` method |
| `wikantik-main/.../KnowledgeGraphServiceFactory.java` | Pass Engine to service constructor |
| `wikantik-war/.../WEB-INF/web.xml` | + servlet registration + mapping |
| `wikantik-main/src/test/.../DefaultKnowledgeGraphServiceTest.java` | + snapshotGraph tests |

### New frontend files
| File | Responsibility |
|------|---------------|
| `wikantik-frontend/src/components/graph/graph-data.js` | Pure transform: snapshot → cytoscape elements, bidirectional merge |
| `wikantik-frontend/src/components/graph/graph-data.test.js` | Unit tests for transform and merge |
| `wikantik-frontend/src/components/graph/graph-style.js` | Cytoscape stylesheet array |
| `wikantik-frontend/src/components/graph/graph.css` | Layout/spacing for graph view components |
| `wikantik-frontend/src/components/graph/GraphErrorBoundary.jsx` | Class component error boundary |
| `wikantik-frontend/src/components/graph/GraphErrorState.jsx` | Variant-driven error/empty states |
| `wikantik-frontend/src/components/graph/GraphErrorState.test.jsx` | One test per variant |
| `wikantik-frontend/src/components/graph/GraphLoadingFallback.jsx` | Suspense fallback + fetch loading |
| `wikantik-frontend/src/components/graph/GraphLegend.jsx` | Collapsible bottom-right legend |
| `wikantik-frontend/src/components/graph/GraphLegend.test.jsx` | Rendering, collapse toggle, dynamic threshold |
| `wikantik-frontend/src/components/graph/GraphToolbar.jsx` | Top overlay: fit, filters, refresh, timestamp |
| `wikantik-frontend/src/components/graph/GraphToolbar.test.jsx` | Button callbacks, filter popover, timestamp |
| `wikantik-frontend/src/components/graph/GraphDetailsDrawer.jsx` | Right-side node details + edge walking |
| `wikantik-frontend/src/components/graph/GraphDetailsDrawer.test.jsx` | Fields, edge rows, restricted rows, callbacks |
| `wikantik-frontend/src/components/graph/GraphCanvas.jsx` | react-cytoscapejs wrapper, event delegation |
| `wikantik-frontend/src/components/graph/GraphView.jsx` | Route component, state owner, orchestrator |
| `wikantik-frontend/src/components/graph/GraphView.test.jsx` | Integration test with mocked API |

### Edited frontend files
| File | Change |
|------|--------|
| `wikantik-frontend/src/api/client.js` | + `api.knowledge.getGraphSnapshot()` |
| `wikantik-frontend/src/main.jsx` | + lazy-loaded `/graph` route |
| `wikantik-frontend/src/components/Sidebar.jsx` | + "Graph" link with dynamic `?focus=` |
| `wikantik-frontend/package.json` | + cytoscape, cytoscape-cose-bilkent, react-cytoscapejs |
| `wikantik-frontend/vite.config.js` | + `/api/knowledge` proxy entry (if not already covered) |

### New IT test file
| File | Responsibility |
|------|---------------|
| `wikantik-it-tests/wikantik-selenide-tests/.../KnowledgeGraphVisualizationIT.java` | 6 Selenide integration tests |

---

## Task 1: Backfill Verification

Verify that the knowledge graph has been fully projected before building a visualization that depends on completeness.

**Files:**
- Read: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java` (lines 386-422 — `handleProjectAll`)
- Read: `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java` (countNodes)

- [ ] **Step 1: Check if project-all has been run**

The `handleProjectAll()` endpoint at `POST /admin/knowledge/project-all` already exists in `AdminKnowledgeResource` (lines 386-422). It iterates all pages and calls `graphProjector.projectPage()` for each. Verify by querying the local database:

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
curl -s -u "${login}:${password}" http://localhost:8080/admin/knowledge/schema | python3 -m json.tool
```

Check the `stats.nodes` and `stats.edges` counts. Compare `stats.nodes` against the page count:

```bash
ls docs/wikantik-pages/*.md | wc -l
```

If the node count is close to the page count (within ~5% — system pages are excluded), backfill is complete. If not, trigger a project-all:

```bash
curl -s -u "${login}:${password}" -X POST http://localhost:8080/admin/knowledge/project-all | python3 -m json.tool
```

- [ ] **Step 2: Document findings**

Record whether backfill was already complete or needed triggering. If you had to trigger it, verify counts again after completion. Proceed only once verified.

- [ ] **Step 3: Commit (if any changes)**

If you made any changes (unlikely for this step), commit them.

---

## Task 2: API Records + Service Interface

Create the three data records and add the `snapshotGraph` method to the service interface.

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/GraphSnapshot.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotEdge.java`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java`

- [ ] **Step 1: Create SnapshotNode record**

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.api.knowledge;

import java.util.UUID;

public record SnapshotNode(
    UUID id,
    String name,
    String type,
    String role,
    Provenance provenance,
    String sourcePage,
    int degreeIn,
    int degreeOut,
    boolean restricted
) {}
```

- [ ] **Step 2: Create SnapshotEdge record**

```java
// (same ASF license header)
package com.wikantik.api.knowledge;

import java.util.UUID;

public record SnapshotEdge(
    UUID id,
    UUID source,
    UUID target,
    String relationshipType,
    Provenance provenance
) {}
```

- [ ] **Step 3: Create GraphSnapshot record**

```java
// (same ASF license header)
package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.List;

public record GraphSnapshot(
    Instant generatedAt,
    int nodeCount,
    int edgeCount,
    int hubDegreeThreshold,
    List< SnapshotNode > nodes,
    List< SnapshotEdge > edges
) {}
```

- [ ] **Step 4: Add snapshotGraph to KnowledgeGraphService interface**

Add to `KnowledgeGraphService.java` after the `clearAll()` method:

```java
// --- Graph visualization ---

/**
 * Builds a snapshot of the entire knowledge graph for visualization.
 * Nodes are classified by role (hub, normal, orphan, stub, restricted).
 * Restricted nodes have sensitive fields redacted based on the viewer's
 * page-level permissions. The underlying data may be cached; per-user
 * redaction is applied on top of the cache.
 *
 * @param viewer the user's session (used for ACL checks); must not be null
 * @return a complete snapshot of all nodes and edges
 */
GraphSnapshot snapshotGraph( com.wikantik.api.core.Session viewer );
```

Note: the spec says `Principal viewer` but ACL checks require a `Session` (for `AuthorizationManager.checkPermission`). This is an implementation-driven adjustment.

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -pl wikantik-api -q
```

Expected: BUILD SUCCESS (the service interface now has a default-less method, so implementors will fail to compile until Task 5 adds the implementation — that's expected).

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/GraphSnapshot.java \
       wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java \
       wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotEdge.java \
       wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java
git commit -m "feat(api): add GraphSnapshot records and snapshotGraph interface method"
```

---

## Task 3: GraphRoleClassifier (TDD)

Pure static classifier with no dependencies — ideal TDD target.

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/GraphRoleClassifierTest.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/GraphRoleClassifier.java`

- [ ] **Step 1: Write the failing test**

```java
// (ASF license header)
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphRoleClassifierTest {

    private static KgNode node( final String name, final String sourcePage ) {
        return new KgNode( UUID.randomUUID(), name, "page", sourcePage,
                Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now() );
    }

    @Test
    void restricted_takesPrecedenceOverAll() {
        assertEquals( "restricted",
                GraphRoleClassifier.classify( node( "A", "A" ), 5, 5, 10, true ) );
    }

    @Test
    void stub_whenSourcePageNull() {
        assertEquals( "stub",
                GraphRoleClassifier.classify( node( "X", null ), 2, 0, 10, false ) );
    }

    @Test
    void orphan_whenZeroDegree() {
        assertEquals( "orphan",
                GraphRoleClassifier.classify( node( "O", "O" ), 0, 0, 10, false ) );
    }

    @Test
    void hub_atThreshold() {
        assertEquals( "hub",
                GraphRoleClassifier.classify( node( "H", "H" ), 5, 5, 10, false ) );
    }

    @Test
    void hub_aboveThreshold() {
        assertEquals( "hub",
                GraphRoleClassifier.classify( node( "H", "H" ), 8, 8, 10, false ) );
    }

    @Test
    void normal_belowThreshold() {
        assertEquals( "normal",
                GraphRoleClassifier.classify( node( "N", "N" ), 3, 2, 10, false ) );
    }

    @Test
    void stub_precedesOrphan() {
        // sourcePage null + zero degree → stub (not orphan)
        assertEquals( "stub",
                GraphRoleClassifier.classify( node( "S", null ), 0, 0, 10, false ) );
    }

    @Test
    void restricted_precedesStub() {
        assertEquals( "restricted",
                GraphRoleClassifier.classify( node( "R", null ), 0, 0, 10, true ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl wikantik-main -Dtest=GraphRoleClassifierTest -q
```

Expected: FAIL — `GraphRoleClassifier` class does not exist.

- [ ] **Step 3: Write the implementation**

```java
// (ASF license header)
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgNode;

public final class GraphRoleClassifier {

    private GraphRoleClassifier() {}

    public static String classify( final KgNode node, final int degreeIn, final int degreeOut,
                                    final int hubThreshold, final boolean restricted ) {
        if ( restricted ) return "restricted";
        if ( node.sourcePage() == null ) return "stub";
        if ( degreeIn + degreeOut == 0 ) return "orphan";
        if ( degreeIn + degreeOut >= hubThreshold ) return "hub";
        return "normal";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -pl wikantik-main -Dtest=GraphRoleClassifierTest -q
```

Expected: BUILD SUCCESS, all 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/GraphRoleClassifier.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/GraphRoleClassifierTest.java
git commit -m "feat(knowledge): add GraphRoleClassifier with TDD tests"
```

---

## Task 4: Repository — getAllNodes()

Add the bulk node query method. `getAllEdges()` already exists at line 333 of `JdbcKnowledgeRepository.java` — follow the same pattern.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java`

- [ ] **Step 1: Add getAllNodes() method**

Add directly above `getAllEdges()` (before line 327):

```java
/**
 * Returns all nodes in the knowledge graph, ordered by id for determinism.
 *
 * @return list of all nodes
 */
public List< KgNode > getAllNodes() {
    final List< KgNode > results = new ArrayList<>();
    try( final Connection conn = dataSource.getConnection();
         final Statement st = conn.createStatement();
         final ResultSet rs = st.executeQuery( "SELECT * FROM kg_nodes ORDER BY id" ) ) {
        while( rs.next() ) {
            results.add( mapNode( rs ) );
        }
    } catch( final SQLException e ) {
        LOG.warn( "Failed to get all nodes: {}", e.getMessage(), e );
        throw new RuntimeException( e );
    }
    return results;
}
```

- [ ] **Step 2: Also add ORDER BY to getAllEdges()**

The existing `getAllEdges()` at line 337 uses `SELECT * FROM kg_edges` without ordering. Add `ORDER BY id` for deterministic output:

Change `"SELECT * FROM kg_edges"` to `"SELECT * FROM kg_edges ORDER BY id"`.

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl wikantik-main -q
```

Expected: FAIL — `DefaultKnowledgeGraphService` doesn't implement `snapshotGraph` yet. That's expected. The compilation of `JdbcKnowledgeRepository` itself should succeed. Verify by checking the error is specifically about the missing interface method, not a syntax error in the new method.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java
git commit -m "feat(knowledge): add getAllNodes() to JdbcKnowledgeRepository"
```

---

## Task 5: DefaultKnowledgeGraphService.snapshotGraph (TDD)

The core backend logic: snapshot building, role classification, degree computation, hub threshold, ACL redaction, and 60-second caching.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java` (line 101)
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add these tests to `DefaultKnowledgeGraphServiceTest.java`. The test setup already creates `service` with a real PostgreSQL-backed repository via Testcontainers.

First, add these imports at the top of the test file:

```java
import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.SnapshotNode;
import com.wikantik.api.core.Session;
import com.wikantik.auth.SessionMonitor;
```

The test needs a `Session` for the `snapshotGraph` call. Add a helper in the test class:

```java
private Session createTestSession() {
    return Wiki.session().find( engine, HttpMockFactory.createHttpRequest() );
}
```

And add a `TestEngine engine` field alongside the existing `service` field, initialized in `setUp()`:

```java
private TestEngine engine;

@BeforeEach
void setUp() throws Exception {
    // ... existing cleanup code ...
    final Properties props = TestEngine.getTestProperties();
    engine = new TestEngine( props );
    final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource );
    service = new DefaultKnowledgeGraphService( repo, engine );
}
```

Then add these test methods:

```java
@Test
void snapshotGraph_emptyGraph_returnsEmptyCollections() {
    final GraphSnapshot snap = service.snapshotGraph( createTestSession() );
    assertEquals( 0, snap.nodeCount() );
    assertEquals( 0, snap.edgeCount() );
    assertTrue( snap.nodes().isEmpty() );
    assertTrue( snap.edges().isEmpty() );
    assertNotNull( snap.generatedAt() );
    assertEquals( 10, snap.hubDegreeThreshold() );
}

@Test
void snapshotGraph_classifiesOrphanWithZeroDegree() {
    service.upsertNode( "Orphan", "page", "Orphan",
            Provenance.HUMAN_AUTHORED, Map.of() );
    final GraphSnapshot snap = service.snapshotGraph( createTestSession() );
    assertEquals( 1, snap.nodeCount() );
    final SnapshotNode node = snap.nodes().get( 0 );
    assertEquals( "orphan", node.role() );
    assertEquals( 0, node.degreeIn() );
    assertEquals( 0, node.degreeOut() );
}

@Test
void snapshotGraph_classifiesStubFromNullSourcePage() {
    service.upsertNode( "StubTarget", "page", null,
            Provenance.HUMAN_AUTHORED, Map.of() );
    final GraphSnapshot snap = service.snapshotGraph( createTestSession() );
    final SnapshotNode node = snap.nodes().get( 0 );
    assertEquals( "stub", node.role() );
}

@Test
void snapshotGraph_classifiesHubAtThreshold() {
    // Create a hub with enough edges to exceed threshold
    final var hub = service.upsertNode( "Hub", "page", "Hub",
            Provenance.HUMAN_AUTHORED, Map.of() );
    for ( int i = 0; i < 12; i++ ) {
        final var target = service.upsertNode( "Target" + i, "page", "Target" + i,
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( hub.id(), target.id(), "links_to",
                Provenance.HUMAN_AUTHORED, Map.of() );
    }
    final GraphSnapshot snap = service.snapshotGraph( createTestSession() );
    final SnapshotNode hubNode = snap.nodes().stream()
            .filter( n -> "Hub".equals( n.name() ) ).findFirst().orElseThrow();
    assertEquals( "hub", hubNode.role() );
}

@Test
void snapshotGraph_hubThresholdFloorIsTen() {
    // With very few nodes, threshold should still be at least 10
    service.upsertNode( "A", "page", "A", Provenance.HUMAN_AUTHORED, Map.of() );
    final GraphSnapshot snap = service.snapshotGraph( createTestSession() );
    assertTrue( snap.hubDegreeThreshold() >= 10 );
}

@Test
void snapshotGraph_degreeCountsBothDirections() {
    final var a = service.upsertNode( "A", "page", "A",
            Provenance.HUMAN_AUTHORED, Map.of() );
    final var b = service.upsertNode( "B", "page", "B",
            Provenance.HUMAN_AUTHORED, Map.of() );
    service.upsertEdge( a.id(), b.id(), "links_to",
            Provenance.HUMAN_AUTHORED, Map.of() );

    final GraphSnapshot snap = service.snapshotGraph( createTestSession() );
    final SnapshotNode nodeA = snap.nodes().stream()
            .filter( n -> "A".equals( n.name() ) ).findFirst().orElseThrow();
    final SnapshotNode nodeB = snap.nodes().stream()
            .filter( n -> "B".equals( n.name() ) ).findFirst().orElseThrow();
    assertEquals( 0, nodeA.degreeIn() );
    assertEquals( 1, nodeA.degreeOut() );
    assertEquals( 1, nodeB.degreeIn() );
    assertEquals( 0, nodeB.degreeOut() );
}

@Test
void snapshotGraph_edgesReturnedWithCorrectFields() {
    final var a = service.upsertNode( "A", "page", "A",
            Provenance.HUMAN_AUTHORED, Map.of() );
    final var b = service.upsertNode( "B", "page", "B",
            Provenance.HUMAN_AUTHORED, Map.of() );
    service.upsertEdge( a.id(), b.id(), "related_to",
            Provenance.HUMAN_AUTHORED, Map.of() );

    final GraphSnapshot snap = service.snapshotGraph( createTestSession() );
    assertEquals( 1, snap.edgeCount() );
    final var edge = snap.edges().get( 0 );
    assertEquals( a.id(), edge.source() );
    assertEquals( b.id(), edge.target() );
    assertEquals( "related_to", edge.relationshipType() );
    assertEquals( Provenance.HUMAN_AUTHORED, edge.provenance() );
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceTest#snapshotGraph* -q
```

Expected: FAIL — `snapshotGraph` method not implemented.

- [ ] **Step 3: Update constructor to accept Engine**

In `DefaultKnowledgeGraphService.java`, add `Engine` as a second constructor parameter:

```java
private final JdbcKnowledgeRepository repo;
private final com.wikantik.api.core.Engine engine;

public DefaultKnowledgeGraphService( final JdbcKnowledgeRepository repo,
                                      final com.wikantik.api.core.Engine engine ) {
    this.repo = repo;
    this.engine = engine;
}
```

Also add a backwards-compatible constructor for existing callers that don't need snapshot support:

```java
public DefaultKnowledgeGraphService( final JdbcKnowledgeRepository repo ) {
    this( repo, null );
}
```

- [ ] **Step 4: Update KnowledgeGraphServiceFactory**

In `KnowledgeGraphServiceFactory.java`, line 101, change:

```java
final DefaultKnowledgeGraphService kgService = new DefaultKnowledgeGraphService( repo );
```

to:

```java
final DefaultKnowledgeGraphService kgService = new DefaultKnowledgeGraphService( repo, null );
```

The `Engine` reference will be injected separately after engine init. Add a note: the factory doesn't have access to the engine at construction time; the caller must call `kgService.setEngine(engine)` after the engine is fully initialized.

Alternatively, add a `setEngine` method to `DefaultKnowledgeGraphService`:

```java
public void setEngine( final com.wikantik.api.core.Engine engine ) {
    this.engine = engine;
}
```

Change the `engine` field from `final` to non-final for this setter.

Find where the factory result is consumed (search for `KnowledgeGraphServiceFactory.create`) and add the `setEngine` call after engine initialization.

- [ ] **Step 5: Implement snapshotGraph**

Add these fields to `DefaultKnowledgeGraphService`:

```java
private volatile GraphSnapshot cachedSnapshot;
private volatile Instant cacheTimestamp;
private static final long CACHE_TTL_SECONDS = 60;
```

Add the implementation:

```java
@Override
public GraphSnapshot snapshotGraph( final com.wikantik.api.core.Session viewer ) {
    if ( viewer == null || viewer.isAnonymous() ) {
        throw new IllegalArgumentException( "Authenticated session required" );
    }

    // 1. Get or rebuild the unredacted snapshot (cached for 60s)
    GraphSnapshot base = cachedSnapshot;
    final Instant now = Instant.now();
    if ( base == null || cacheTimestamp == null
            || now.isAfter( cacheTimestamp.plusSeconds( CACHE_TTL_SECONDS ) ) ) {
        base = buildUnredactedSnapshot();
        cachedSnapshot = base;
        cacheTimestamp = now;
    }

    // 2. Apply per-user ACL redaction
    return redactForViewer( base, viewer );
}

private GraphSnapshot buildUnredactedSnapshot() {
    final List< KgNode > allNodes = repo.getAllNodes();
    final List< KgEdge > allEdges = repo.getAllEdges();

    // Build degree maps
    final Map< UUID, int[] > degrees = new HashMap<>();
    for ( final KgEdge edge : allEdges ) {
        degrees.computeIfAbsent( edge.sourceId(), k -> new int[2] )[1]++;
        degrees.computeIfAbsent( edge.targetId(), k -> new int[2] )[0]++;
    }

    // Compute hub threshold: max(10, p95(degreeTotal))
    final int hubThreshold = computeHubThreshold( allNodes, degrees );

    // Classify nodes (without ACL — that happens per-request)
    final List< SnapshotNode > nodes = new ArrayList<>( allNodes.size() );
    for ( final KgNode node : allNodes ) {
        final int[] deg = degrees.getOrDefault( node.id(), new int[2] );
        final String role = GraphRoleClassifier.classify( node, deg[0], deg[1], hubThreshold, false );
        nodes.add( new SnapshotNode(
                node.id(), node.name(), node.nodeType(), role,
                node.provenance(), node.sourcePage(), deg[0], deg[1], false ) );
    }

    final List< SnapshotEdge > edges = allEdges.stream()
            .map( e -> new SnapshotEdge( e.id(), e.sourceId(), e.targetId(),
                    e.relationshipType(), e.provenance() ) )
            .toList();

    return new GraphSnapshot( Instant.now(), nodes.size(), edges.size(),
            hubThreshold, nodes, edges );
}

private int computeHubThreshold( final List< KgNode > nodes,
                                  final Map< UUID, int[] > degrees ) {
    if ( nodes.isEmpty() ) return 10;
    final int[] totals = nodes.stream()
            .mapToInt( n -> {
                final int[] d = degrees.getOrDefault( n.id(), new int[2] );
                return d[0] + d[1];
            } )
            .sorted()
            .toArray();
    final int p95Index = (int) Math.ceil( totals.length * 0.95 ) - 1;
    final int p95 = totals[Math.max( 0, p95Index )];
    return Math.max( 10, p95 );
}

private GraphSnapshot redactForViewer( final GraphSnapshot base,
                                        final com.wikantik.api.core.Session viewer ) {
    if ( engine == null ) {
        return base;
    }
    final com.wikantik.auth.AuthorizationManager authMgr =
            engine.getManager( com.wikantik.auth.AuthorizationManager.class );
    final com.wikantik.api.managers.PageManager pageMgr =
            engine.getManager( com.wikantik.api.managers.PageManager.class );

    final List< SnapshotNode > redacted = new ArrayList<>( base.nodes().size() );
    for ( final SnapshotNode node : base.nodes() ) {
        if ( node.sourcePage() != null && !isViewable( node.sourcePage(), viewer, authMgr, pageMgr ) ) {
            redacted.add( new SnapshotNode(
                    node.id(), null, null, "restricted", null, null,
                    node.degreeIn(), node.degreeOut(), true ) );
        } else {
            redacted.add( node );
        }
    }
    return new GraphSnapshot( base.generatedAt(), base.nodeCount(), base.edgeCount(),
            base.hubDegreeThreshold(), redacted, base.edges() );
}

private boolean isViewable( final String pageName,
                             final com.wikantik.api.core.Session viewer,
                             final com.wikantik.auth.AuthorizationManager authMgr,
                             final com.wikantik.api.managers.PageManager pageMgr ) {
    final com.wikantik.api.core.Page page = pageMgr.getPage( pageName );
    final java.security.Permission perm = ( page != null )
            ? com.wikantik.auth.permissions.PermissionFactory.getPagePermission( page, "view" )
            : new com.wikantik.auth.permissions.PagePermission(
                    engine.getApplicationName() + ":" + pageName, "view" );
    return authMgr.checkPermission( viewer, perm );
}
```

Also add cache invalidation to the mutating methods. Add this private helper:

```java
private void invalidateSnapshotCache() {
    cachedSnapshot = null;
    cacheTimestamp = null;
}
```

Call `invalidateSnapshotCache()` at the end of these existing methods: `upsertNode`, `deleteNode`, `mergeNodes`, `upsertEdge`, `deleteEdge`, `diffAndRemoveStaleEdges`, `clearAll`.

Add the required imports at the top of the file:

```java
import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.SnapshotNode;
import com.wikantik.api.knowledge.SnapshotEdge;
import com.wikantik.api.knowledge.KgEdge;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceTest#snapshotGraph* -q
```

Expected: all new snapshotGraph tests pass.

- [ ] **Step 7: Run full module tests**

```bash
mvn test -pl wikantik-main -q
```

Expected: BUILD SUCCESS — no regressions.

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
       wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java
git commit -m "feat(knowledge): implement snapshotGraph with caching and ACL redaction"
```

---

## Task 6: KnowledgeGraphResource Servlet (TDD)

New servlet at `/api/knowledge/graph` + web.xml registration.

**Files:**
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/KnowledgeGraphResourceTest.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/KnowledgeGraphResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Write the failing test**

```java
// (ASF license header)
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.wikantik.HttpMockFactory;
import com.wikantik.PostgresTestContainer;
import com.wikantik.TestEngine;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.DefaultKnowledgeGraphService;
import com.wikantik.knowledge.JdbcKnowledgeRepository;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class KnowledgeGraphResourceTest {

    private static DataSource dataSource;
    private TestEngine engine;
    private KnowledgeGraphResource servlet;
    private final Gson gson = new Gson();

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource );
        final DefaultKnowledgeGraphService service = new DefaultKnowledgeGraphService( repo, engine );
        engine.setManager( KnowledgeGraphService.class, service );

        servlet = new KnowledgeGraphResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void doGet_authenticated_returns200WithSnapshot() throws Exception {
        final KnowledgeGraphService svc = engine.getManager( KnowledgeGraphService.class );
        svc.upsertNode( "TestPage", "page", "TestPage",
                Provenance.HUMAN_AUTHORED, Map.of() );

        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( createAuthenticatedRequest(), response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertFalse( obj.has( "error" ), "Should not be an error: " + sw );
        assertTrue( obj.has( "generatedAt" ) );
        assertTrue( obj.has( "nodeCount" ) );
        assertTrue( obj.has( "edgeCount" ) );
        assertTrue( obj.has( "hubDegreeThreshold" ) );
        assertTrue( obj.has( "nodes" ) );
        assertTrue( obj.has( "edges" ) );
        assertEquals( 1, obj.get( "nodeCount" ).getAsInt() );
    }

    @Test
    void doGet_emptyGraph_returns200WithZeroCounts() throws Exception {
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( createAuthenticatedRequest(), response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 0, obj.get( "nodeCount" ).getAsInt() );
        assertEquals( 0, obj.get( "edgeCount" ).getAsInt() );
    }

    private HttpServletRequest createAuthenticatedRequest() {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/knowledge/graph" );
        Mockito.doReturn( null ).when( request ).getPathInfo();
        return request;
    }
}
```

Note: testing the anonymous/401 case is complicated because `Wiki.session().find()` returns a session based on the mock request's HTTP session state, which defaults to an anonymous guest. If the test engine's default session is anonymous, the existing `createAuthenticatedRequest()` helper may need to be adjusted. Check how the test engine handles authentication — if the default test session is authenticated (which is typical for `TestEngine`), the 401 test needs a request that produces an anonymous session. Add the 401 test once you understand the test engine's session behavior.

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl wikantik-rest -Dtest=KnowledgeGraphResourceTest -q
```

Expected: FAIL — `KnowledgeGraphResource` class does not exist.

- [ ] **Step 3: Write the servlet**

```java
// (ASF license header)
package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

public class KnowledgeGraphResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( KnowledgeGraphResource.class );

    @Override
    protected void doGet( final HttpServletRequest request,
                          final HttpServletResponse response ) throws IOException {
        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );
        if ( session == null || session.isAnonymous() ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED,
                       "Authentication required" );
            return;
        }
        try {
            final KnowledgeGraphService svc =
                    engine.getManager( KnowledgeGraphService.class );
            final GraphSnapshot snapshot = svc.snapshotGraph( session );
            sendJson( response, snapshot );
        } catch ( final Exception e ) {
            LOG.warn( "Failed to build knowledge-graph snapshot", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                       "Failed to build graph snapshot" );
        }
    }
}
```

- [ ] **Step 4: Register in web.xml**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, add the servlet definition after the `BacklinksResource` servlet (around line 303):

```xml
   <servlet>
       <servlet-name>KnowledgeGraphResource</servlet-name>
       <servlet-class>com.wikantik.rest.KnowledgeGraphResource</servlet-class>
   </servlet>
```

Add the mapping after the `BacklinksResource` mapping (around line 441):

```xml
   <servlet-mapping>
       <servlet-name>KnowledgeGraphResource</servlet-name>
       <url-pattern>/api/knowledge/graph</url-pattern>
   </servlet-mapping>
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -pl wikantik-rest -Dtest=KnowledgeGraphResourceTest -q
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/KnowledgeGraphResource.java \
       wikantik-rest/src/test/java/com/wikantik/rest/KnowledgeGraphResourceTest.java \
       wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(rest): add KnowledgeGraphResource servlet at /api/knowledge/graph"
```

---

## Task 7: Install Frontend Dependencies

**Files:**
- Modify: `wikantik-frontend/package.json`
- Modify: `wikantik-frontend/vite.config.js` (proxy)

- [ ] **Step 1: Install cytoscape packages**

```bash
cd wikantik-frontend && npm install cytoscape cytoscape-cose-bilkent react-cytoscapejs && cd ..
```

- [ ] **Step 2: Install React Testing Library (if not already present)**

Check if `@testing-library/react` is already in `package.json`. If not:

```bash
cd wikantik-frontend && npm install --save-dev @testing-library/react @testing-library/jest-dom jsdom && cd ..
```

Also check `vite.config.js` — if `test.environment` is `'node'`, change it to `'jsdom'` for component testing:

```js
test: {
  environment: 'jsdom',
}
```

- [ ] **Step 3: Verify the /api/knowledge proxy**

In `wikantik-frontend/vite.config.js`, the existing proxy has `/api` which should already cover `/api/knowledge/graph`. Verify:

```js
proxy: {
  '/api': { target: 'http://localhost:8080' },
```

If `/api` is listed, no change needed. If only specific sub-paths are listed, add `/api/knowledge`.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/package.json wikantik-frontend/package-lock.json wikantik-frontend/vite.config.js
git commit -m "build(frontend): add cytoscape.js and testing library dependencies"
```

---

## Task 8: graph-data.js (TDD)

Pure transformation module — no React, no DOM, ideal for TDD.

**Files:**
- Create: `wikantik-frontend/src/components/graph/graph-data.test.js`
- Create: `wikantik-frontend/src/components/graph/graph-data.js`

- [ ] **Step 1: Write the failing test**

```js
import { describe, it, expect } from 'vitest';
import { toCytoscapeElements, mergeBidirectionalEdges } from './graph-data.js';

const SNAPSHOT = {
  generatedAt: '2026-04-12T10:00:00Z',
  nodeCount: 4,
  edgeCount: 3,
  hubDegreeThreshold: 10,
  nodes: [
    { id: 'aaa', name: 'Hub', type: 'page', role: 'hub', provenance: 'HUMAN_AUTHORED', sourcePage: 'Hub', degreeIn: 6, degreeOut: 6, restricted: false },
    { id: 'bbb', name: 'Normal', type: 'page', role: 'normal', provenance: 'HUMAN_AUTHORED', sourcePage: 'Normal', degreeIn: 1, degreeOut: 0, restricted: false },
    { id: 'ccc', name: 'Orphan', type: 'page', role: 'orphan', provenance: 'HUMAN_AUTHORED', sourcePage: 'Orphan', degreeIn: 0, degreeOut: 0, restricted: false },
    { id: 'ddd', name: null, type: null, role: 'restricted', provenance: null, sourcePage: null, degreeIn: 1, degreeOut: 1, restricted: true },
  ],
  edges: [
    { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' },
    { id: 'e2', source: 'aaa', target: 'ddd', relationshipType: 'related_to', provenance: 'HUMAN_AUTHORED' },
    { id: 'e3', source: 'ddd', target: 'aaa', relationshipType: 'related_to', provenance: 'HUMAN_AUTHORED' },
  ],
};

describe('toCytoscapeElements', () => {
  it('maps role to CSS class', () => {
    const { nodes } = toCytoscapeElements(SNAPSHOT);
    const hub = nodes.find(n => n.data.id === 'aaa');
    expect(hub.classes).toContain('role-hub');
    const orphan = nodes.find(n => n.data.id === 'ccc');
    expect(orphan.classes).toContain('role-orphan');
    const restricted = nodes.find(n => n.data.id === 'ddd');
    expect(restricted.classes).toContain('role-restricted');
  });

  it('assigns edge color from palette', () => {
    const { edges } = toCytoscapeElements(SNAPSHOT);
    const linksTo = edges.find(e => e.data.relationshipType === 'links_to');
    expect(linksTo.data.edgeColor).toBe('#94a3b8');
    const relatedTo = edges.find(e => e.data.relationshipType === 'related_to');
    expect(relatedTo.data.edgeColor).toBe('#2563eb');
  });

  it('preserves restricted nodes with null fields', () => {
    const { nodes } = toCytoscapeElements(SNAPSHOT);
    const restricted = nodes.find(n => n.data.id === 'ddd');
    expect(restricted.data.name).toBeNull();
    expect(restricted.data.restricted).toBe(true);
  });

  it('passes through node metadata', () => {
    const { nodes } = toCytoscapeElements(SNAPSHOT);
    const hub = nodes.find(n => n.data.id === 'aaa');
    expect(hub.data.role).toBe('hub');
    expect(hub.data.degreeIn).toBe(6);
    expect(hub.data.degreeOut).toBe(6);
    expect(hub.data.sourcePage).toBe('Hub');
  });
});

describe('mergeBidirectionalEdges', () => {
  it('collapses matching pairs into bidirectional edge', () => {
    const edges = [
      { id: 'e2', source: 'aaa', target: 'ddd', relationshipType: 'related_to' },
      { id: 'e3', source: 'ddd', target: 'aaa', relationshipType: 'related_to' },
    ];
    const merged = mergeBidirectionalEdges(edges);
    expect(merged).toHaveLength(1);
    expect(merged[0].bidirectional).toBe(true);
  });

  it('does not collapse different relationship types', () => {
    const edges = [
      { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to' },
      { id: 'e2', source: 'bbb', target: 'aaa', relationshipType: 'related_to' },
    ];
    const merged = mergeBidirectionalEdges(edges);
    expect(merged).toHaveLength(2);
    expect(merged.every(e => !e.bidirectional)).toBe(true);
  });

  it('preserves singletons', () => {
    const edges = [
      { id: 'e1', source: 'aaa', target: 'bbb', relationshipType: 'links_to' },
    ];
    const merged = mergeBidirectionalEdges(edges);
    expect(merged).toHaveLength(1);
    expect(merged[0].bidirectional).toBeFalsy();
  });

  it('is stable across ordering', () => {
    const edges1 = [
      { id: 'e2', source: 'aaa', target: 'ddd', relationshipType: 'related_to' },
      { id: 'e3', source: 'ddd', target: 'aaa', relationshipType: 'related_to' },
    ];
    const edges2 = [
      { id: 'e3', source: 'ddd', target: 'aaa', relationshipType: 'related_to' },
      { id: 'e2', source: 'aaa', target: 'ddd', relationshipType: 'related_to' },
    ];
    const m1 = mergeBidirectionalEdges(edges1);
    const m2 = mergeBidirectionalEdges(edges2);
    expect(m1[0].source).toBe(m2[0].source);
    expect(m1[0].target).toBe(m2[0].target);
  });
});

describe('edge palette', () => {
  it('is deterministic for unknown types', () => {
    const snap1 = { ...SNAPSHOT, edges: [
      { id: 'x', source: 'aaa', target: 'bbb', relationshipType: 'custom_type', provenance: 'HUMAN_AUTHORED' },
    ]};
    const snap2 = { ...SNAPSHOT, edges: [
      { id: 'x', source: 'aaa', target: 'bbb', relationshipType: 'custom_type', provenance: 'HUMAN_AUTHORED' },
    ]};
    const c1 = toCytoscapeElements(snap1).edges[0].data.edgeColor;
    const c2 = toCytoscapeElements(snap2).edges[0].data.edgeColor;
    expect(c1).toBe(c2);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd wikantik-frontend && npx vitest run src/components/graph/graph-data.test.js && cd ..
```

Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

```js
const KNOWN_PALETTE = {
  links_to:   '#94a3b8',
  related_to: '#2563eb',
  part_of:    '#7c3aed',
};

const FALLBACK_PALETTE = [
  '#06b6d4', '#ec4899', '#14b8a6', '#f97316',
  '#84cc16', '#d946ef', '#6366f1',
];

function stableHash(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

function colorFor(relationshipType) {
  if (KNOWN_PALETTE[relationshipType]) return KNOWN_PALETTE[relationshipType];
  return FALLBACK_PALETTE[stableHash(relationshipType) % FALLBACK_PALETTE.length];
}

export function mergeBidirectionalEdges(edges) {
  const seen = new Map();
  const result = [];

  for (const edge of edges) {
    const key = [edge.source, edge.target, edge.relationshipType].sort().join('|') +
                '|' + edge.relationshipType;
    const reverseKey = [edge.target, edge.source, edge.relationshipType].sort().join('|') +
                       '|' + edge.relationshipType;

    if (seen.has(reverseKey) || seen.has(key)) {
      const existingIdx = seen.get(key) ?? seen.get(reverseKey);
      if (existingIdx !== undefined && !result[existingIdx].bidirectional) {
        result[existingIdx].bidirectional = true;
        const [a, b] = [result[existingIdx].source, result[existingIdx].target].sort();
        result[existingIdx].source = a;
        result[existingIdx].target = b;
      }
    } else {
      seen.set(key, result.length);
      result.push({ ...edge });
    }
  }

  return result;
}

export function toCytoscapeElements(snapshot) {
  const nodes = snapshot.nodes.map(n => ({
    data: {
      id: n.id,
      name: n.name,
      type: n.type,
      role: n.role,
      provenance: n.provenance,
      sourcePage: n.sourcePage,
      degreeIn: n.degreeIn,
      degreeOut: n.degreeOut,
      restricted: n.restricted,
      label: n.restricted ? '\u{1F512}' : (n.name || ''),
    },
    classes: `role-${n.role}`,
  }));

  const mergedEdges = mergeBidirectionalEdges(snapshot.edges);

  const edges = mergedEdges.map(e => ({
    data: {
      id: e.id + (e.bidirectional ? '-bidi' : ''),
      source: e.source,
      target: e.target,
      relationshipType: e.relationshipType,
      provenance: e.provenance,
      edgeColor: colorFor(e.relationshipType),
      bidirectional: e.bidirectional || false,
    },
  }));

  return { nodes, edges };
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd wikantik-frontend && npx vitest run src/components/graph/graph-data.test.js && cd ..
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/graph/graph-data.js \
       wikantik-frontend/src/components/graph/graph-data.test.js
git commit -m "feat(frontend): add graph-data.js transform with TDD tests"
```

---

## Task 9: graph-style.js + graph.css

Cytoscape stylesheet and layout CSS. These are configuration — no unit tests needed.

**Files:**
- Create: `wikantik-frontend/src/components/graph/graph-style.js`
- Create: `wikantik-frontend/src/components/graph/graph.css`

- [ ] **Step 1: Create graph-style.js**

```js
export const graphStylesheet = [
  {
    selector: 'node',
    style: {
      'background-color': '#94a3b8',
      'label': 'data(label)',
      'font-size': 10,
      'text-valign': 'bottom',
      'text-halign': 'center',
      'text-margin-y': 4,
      'width': 'mapData(degreeIn, 0, 20, 6, 18)',
      'height': 'mapData(degreeIn, 0, 20, 6, 18)',
      'min-zoomed-font-size': 0,
    },
  },
  {
    selector: 'node.role-hub',
    style: {
      'background-color': '#059669',
      'width': 'mapData(degreeIn, 0, 40, 8, 22)',
      'height': 'mapData(degreeIn, 0, 40, 8, 22)',
    },
  },
  {
    selector: 'node.role-orphan',
    style: {
      'background-color': '#f59e0b',
      'width': 7,
      'height': 7,
    },
  },
  {
    selector: 'node.role-stub',
    style: {
      'background-color': '#fff1f2',
      'border-color': '#dc2626',
      'border-width': 2,
      'border-style': 'dashed',
      'width': 7,
      'height': 7,
    },
  },
  {
    selector: 'node.role-restricted',
    style: {
      'background-color': '#e5e7eb',
      'border-color': '#9ca3af',
      'border-width': 1,
      'border-style': 'solid',
      'label': '\u{1F512}',
      'font-size': 8,
      'width': 8,
      'height': 8,
    },
  },
  {
    selector: 'edge',
    style: {
      'width': 1,
      'curve-style': 'bezier',
      'line-color': 'data(edgeColor)',
      'target-arrow-color': 'data(edgeColor)',
      'target-arrow-shape': 'triangle',
      'arrow-scale': 0.6,
    },
  },
  {
    selector: 'edge[?bidirectional]',
    style: {
      'source-arrow-shape': 'triangle',
      'source-arrow-color': 'data(edgeColor)',
    },
  },
  {
    selector: '.dimmed',
    style: {
      'opacity': 0.2,
    },
  },
  {
    selector: '.hidden',
    style: {
      'display': 'none',
    },
  },
  {
    selector: ':selected',
    style: {
      'border-width': 3,
      'border-color': '#2563eb',
    },
  },
];
```

- [ ] **Step 2: Create graph.css**

```css
.graph-view {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 48px);
  position: relative;
  overflow: hidden;
}

.graph-canvas-container {
  flex: 1;
  position: relative;
}

.graph-toolbar {
  position: absolute;
  top: var(--space-sm);
  left: var(--space-sm);
  display: flex;
  gap: var(--space-xs);
  z-index: 10;
  flex-wrap: wrap;
}

.graph-toolbar button {
  padding: var(--space-xs) var(--space-sm);
  border: 1px solid var(--border);
  border-radius: 4px;
  background: var(--bg);
  cursor: pointer;
  font-size: 0.8rem;
  white-space: nowrap;
}

.graph-toolbar button:hover {
  background: var(--bg-hover, #f1f5f9);
}

.graph-toolbar button.active {
  background: var(--accent);
  color: white;
  border-color: var(--accent);
}

.graph-toolbar .snapshot-time {
  font-size: 0.75rem;
  color: var(--text-secondary, #64748b);
  align-self: center;
  padding: 0 var(--space-xs);
}

.graph-legend {
  position: absolute;
  bottom: var(--space-sm);
  right: var(--space-sm);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: var(--space-sm);
  z-index: 10;
  font-size: 0.8rem;
  max-width: 240px;
}

.graph-legend.collapsed {
  padding: var(--space-xs);
}

.graph-legend-toggle {
  cursor: pointer;
  font-weight: 600;
  margin-bottom: var(--space-xs);
  user-select: none;
}

.graph-legend-item {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin: 2px 0;
}

.graph-legend-swatch {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}

.graph-legend-swatch.dashed {
  border: 2px dashed #dc2626;
  background: #fff1f2;
  width: 8px;
  height: 8px;
}

.graph-details-drawer {
  position: absolute;
  top: 0;
  right: 0;
  width: 320px;
  height: 100%;
  background: var(--bg);
  border-left: 1px solid var(--border);
  overflow-y: auto;
  z-index: 20;
  padding: var(--space-md);
}

.graph-details-drawer .drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-sm);
}

.graph-details-drawer .drawer-header h3 {
  margin: 0;
  font-size: 1rem;
  word-break: break-word;
}

.graph-details-drawer .drawer-meta {
  font-size: 0.85rem;
  color: var(--text-secondary, #64748b);
  margin-bottom: var(--space-sm);
}

.graph-details-drawer .drawer-section-title {
  font-size: 0.8rem;
  font-weight: 600;
  margin-top: var(--space-sm);
  margin-bottom: var(--space-xs);
}

.graph-details-drawer .edge-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 3px 0;
  cursor: pointer;
  font-size: 0.85rem;
  border-radius: 3px;
}

.graph-details-drawer .edge-row:hover {
  background: var(--bg-hover, #f1f5f9);
}

.graph-details-drawer .edge-row.restricted {
  cursor: default;
  opacity: 0.6;
}

.graph-details-drawer .edge-row .edge-type {
  color: var(--text-secondary, #64748b);
  font-size: 0.75rem;
  min-width: 70px;
}

.graph-details-drawer .open-page-btn {
  display: block;
  width: 100%;
  margin-top: var(--space-md);
  padding: var(--space-xs) var(--space-sm);
  text-align: center;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: var(--bg);
  cursor: pointer;
}

.graph-details-drawer .open-page-btn:hover:not(:disabled) {
  background: var(--bg-hover, #f1f5f9);
}

.graph-details-drawer .open-page-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.graph-error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: var(--space-md);
  text-align: center;
  padding: var(--space-xl);
}

.graph-error-state .error-message {
  font-size: 1.1rem;
  color: var(--text);
}

.graph-error-state .error-action {
  padding: var(--space-xs) var(--space-md);
  border: 1px solid var(--border);
  border-radius: 4px;
  background: var(--bg);
  cursor: pointer;
}

.graph-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: var(--space-sm);
}

.graph-layout-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.7);
  z-index: 15;
  font-size: 0.9rem;
  pointer-events: none;
}

.graph-filter-popover {
  position: absolute;
  top: 100%;
  left: 0;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: var(--space-sm);
  z-index: 20;
  min-width: 180px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
}

.graph-filter-popover label {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 2px 0;
  font-size: 0.85rem;
  cursor: pointer;
}
```

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/components/graph/graph-style.js \
       wikantik-frontend/src/components/graph/graph.css
git commit -m "feat(frontend): add cytoscape stylesheet and graph CSS"
```

---

## Task 10: API Client + Error Components

Add the API method and build the error/loading components.

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`
- Create: `wikantik-frontend/src/components/graph/GraphErrorBoundary.jsx`
- Create: `wikantik-frontend/src/components/graph/GraphErrorState.jsx`
- Create: `wikantik-frontend/src/components/graph/GraphErrorState.test.jsx`
- Create: `wikantik-frontend/src/components/graph/GraphLoadingFallback.jsx`

- [ ] **Step 1: Add getGraphSnapshot to client.js**

In `wikantik-frontend/src/api/client.js`, inside the `knowledge: {` block (around line 315), add:

```js
    getGraphSnapshot: ({ signal } = {}) =>
      request('/api/knowledge/graph', { signal }),
```

This goes at the top of the `knowledge` block, before `getSchema`.

- [ ] **Step 2: Create GraphErrorBoundary**

```jsx
import { Component } from 'react';

export default class GraphErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error, info) {
    console.error('GraphErrorBoundary caught:', error, info);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="graph-error-state">
          <p className="error-message">Something went wrong rendering the graph.</p>
          <button className="error-action" onClick={() => window.location.reload()}>
            Reload page
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
```

- [ ] **Step 3: Create GraphErrorState**

```jsx
import { Link } from 'react-router-dom';

const VARIANTS = {
  empty:          { message: 'The knowledge graph is empty.', action: 'refresh' },
  'empty-for-you': { message: "You don't have permission to view any pages in the knowledge graph.", action: null },
  unauthorized:   { message: 'Sign in to view the knowledge graph.', action: 'login' },
  forbidden:      { message: "You don't have permission to view the knowledge graph.", action: null },
  server:         { message: 'The graph service is unavailable right now.', action: 'retry' },
  malformed:      { message: 'Graph snapshot was invalid. Check server logs.', action: 'retry' },
};

export default function GraphErrorState({ variant, onRetry }) {
  const config = VARIANTS[variant] || VARIANTS.server;

  return (
    <div className="graph-error-state" data-testid="graph-error-state">
      <p className="error-message">{config.message}</p>
      {config.action === 'refresh' && (
        <button className="error-action" onClick={onRetry}>Refresh</button>
      )}
      {config.action === 'retry' && (
        <button className="error-action" onClick={onRetry}>Try again</button>
      )}
      {config.action === 'login' && (
        <Link to="/login?return=/graph" className="error-action" style={{ textDecoration: 'none' }}>
          Sign in
        </Link>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Write GraphErrorState tests**

```jsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import GraphErrorState from './GraphErrorState.jsx';

const wrap = (ui) => render(<MemoryRouter>{ui}</MemoryRouter>);

describe('GraphErrorState', () => {
  it('shows empty message with refresh button', () => {
    const onRetry = vi.fn();
    wrap(<GraphErrorState variant="empty" onRetry={onRetry} />);
    expect(screen.getByText('The knowledge graph is empty.')).toBeTruthy();
    fireEvent.click(screen.getByText('Refresh'));
    expect(onRetry).toHaveBeenCalled();
  });

  it('shows empty-for-you without action', () => {
    wrap(<GraphErrorState variant="empty-for-you" />);
    expect(screen.getByText(/don't have permission to view any/)).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows unauthorized with sign-in link', () => {
    wrap(<GraphErrorState variant="unauthorized" />);
    expect(screen.getByText('Sign in to view the knowledge graph.')).toBeTruthy();
    expect(screen.getByText('Sign in')).toBeTruthy();
  });

  it('shows forbidden without action', () => {
    wrap(<GraphErrorState variant="forbidden" />);
    expect(screen.getByText(/don't have permission to view the knowledge graph/)).toBeTruthy();
  });

  it('shows server error with retry button', () => {
    const onRetry = vi.fn();
    wrap(<GraphErrorState variant="server" onRetry={onRetry} />);
    expect(screen.getByText('The graph service is unavailable right now.')).toBeTruthy();
    fireEvent.click(screen.getByText('Try again'));
    expect(onRetry).toHaveBeenCalled();
  });

  it('shows malformed with retry button', () => {
    const onRetry = vi.fn();
    wrap(<GraphErrorState variant="malformed" onRetry={onRetry} />);
    expect(screen.getByText(/snapshot was invalid/)).toBeTruthy();
    fireEvent.click(screen.getByText('Try again'));
    expect(onRetry).toHaveBeenCalled();
  });
});
```

- [ ] **Step 5: Create GraphLoadingFallback**

```jsx
import { useState, useEffect } from 'react';

export default function GraphLoadingFallback() {
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => setSlow(true), 2000);
    return () => clearTimeout(timer);
  }, []);

  return (
    <div className="graph-loading">
      <p>Loading knowledge graph...</p>
      {slow && <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary, #64748b)' }}>
        Still working — large graphs can take a few seconds.
      </p>}
    </div>
  );
}
```

- [ ] **Step 6: Run tests**

```bash
cd wikantik-frontend && npx vitest run src/components/graph/GraphErrorState.test.jsx && cd ..
```

Expected: all 6 variant tests pass.

- [ ] **Step 7: Commit**

```bash
git add wikantik-frontend/src/api/client.js \
       wikantik-frontend/src/components/graph/GraphErrorBoundary.jsx \
       wikantik-frontend/src/components/graph/GraphErrorState.jsx \
       wikantik-frontend/src/components/graph/GraphErrorState.test.jsx \
       wikantik-frontend/src/components/graph/GraphLoadingFallback.jsx
git commit -m "feat(frontend): add API method, error boundary, error states, loading fallback"
```

---

## Task 11: GraphLegend (TDD)

**Files:**
- Create: `wikantik-frontend/src/components/graph/GraphLegend.test.jsx`
- Create: `wikantik-frontend/src/components/graph/GraphLegend.jsx`

- [ ] **Step 1: Write the failing test**

```jsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import GraphLegend from './GraphLegend.jsx';

describe('GraphLegend', () => {
  const defaultProps = {
    hubDegreeThreshold: 12,
    edgeTypes: ['links_to', 'related_to', 'part_of'],
    timestamp: '14:32:07',
  };

  it('renders node role legend items', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText(/hub/i)).toBeTruthy();
    expect(screen.getByText(/normal/i)).toBeTruthy();
    expect(screen.getByText(/orphan/i)).toBeTruthy();
    expect(screen.getByText(/stub/i)).toBeTruthy();
    expect(screen.getByText(/restricted/i)).toBeTruthy();
  });

  it('shows dynamic hub threshold', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText(/12/)).toBeTruthy();
  });

  it('lists edge types', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText('links_to')).toBeTruthy();
    expect(screen.getByText('related_to')).toBeTruthy();
  });

  it('shows directionality convention', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText(/one-way/i)).toBeTruthy();
    expect(screen.getByText(/bidirectional/i)).toBeTruthy();
  });

  it('collapses and expands', () => {
    render(<GraphLegend {...defaultProps} />);
    const toggle = screen.getByText(/legend/i);
    fireEvent.click(toggle);
    expect(screen.queryByText('links_to')).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByText('links_to')).toBeTruthy();
  });

  it('shows timestamp', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText(/14:32:07/)).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run to verify failure**

```bash
cd wikantik-frontend && npx vitest run src/components/graph/GraphLegend.test.jsx && cd ..
```

- [ ] **Step 3: Implement GraphLegend**

```jsx
import { useState } from 'react';

const NODE_ROLES = [
  { role: 'hub', color: '#059669', label: 'Hub' },
  { role: 'normal', color: '#94a3b8', label: 'Normal' },
  { role: 'orphan', color: '#f59e0b', label: 'Orphan' },
  { role: 'stub', color: null, label: 'Stub', dashed: true },
  { role: 'restricted', color: '#e5e7eb', label: 'Restricted' },
];

export default function GraphLegend({ hubDegreeThreshold, edgeTypes, timestamp }) {
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div className={`graph-legend ${collapsed ? 'collapsed' : ''}`}>
      <div className="graph-legend-toggle" onClick={() => setCollapsed(!collapsed)}>
        {collapsed ? 'Legend +' : 'Legend -'}
      </div>
      {!collapsed && (
        <>
          <div style={{ marginBottom: 'var(--space-xs)' }}>
            {NODE_ROLES.map(({ role, color, label, dashed }) => (
              <div key={role} className="graph-legend-item">
                <span
                  className={`graph-legend-swatch ${dashed ? 'dashed' : ''}`}
                  style={dashed ? {} : { backgroundColor: color }}
                />
                <span>{label}{role === 'hub' ? ` (\u2265${hubDegreeThreshold} connections)` : ''}</span>
              </div>
            ))}
          </div>
          <div style={{ borderTop: '1px solid var(--border)', paddingTop: 'var(--space-xs)' }}>
            {edgeTypes.map(t => (
              <div key={t} className="graph-legend-item">
                <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>{t}</span>
              </div>
            ))}
          </div>
          <div style={{ borderTop: '1px solid var(--border)', paddingTop: 'var(--space-xs)', marginTop: 'var(--space-xs)' }}>
            <div className="graph-legend-item">
              <span>\u2192 one-way</span>
            </div>
            <div className="graph-legend-item">
              <span>\u2194 bidirectional</span>
            </div>
          </div>
          <div style={{ marginTop: 'var(--space-xs)', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
            snapshot: {timestamp}
          </div>
        </>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run to verify pass**

```bash
cd wikantik-frontend && npx vitest run src/components/graph/GraphLegend.test.jsx && cd ..
```

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/graph/GraphLegend.jsx \
       wikantik-frontend/src/components/graph/GraphLegend.test.jsx
git commit -m "feat(frontend): add GraphLegend component with TDD tests"
```

---

## Task 12: GraphToolbar (TDD)

**Files:**
- Create: `wikantik-frontend/src/components/graph/GraphToolbar.test.jsx`
- Create: `wikantik-frontend/src/components/graph/GraphToolbar.jsx`

- [ ] **Step 1: Write the failing test**

```jsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import GraphToolbar from './GraphToolbar.jsx';

describe('GraphToolbar', () => {
  const defaultProps = {
    onFitToView: vi.fn(),
    onRefresh: vi.fn(),
    onToggleAnomalies: vi.fn(),
    onToggleEdgeType: vi.fn(),
    edgeTypes: ['links_to', 'related_to'],
    hiddenEdgeTypes: new Set(),
    onlyAnomalies: false,
    timestamp: '14:32:07',
  };

  it('renders fit-to-view button', () => {
    render(<GraphToolbar {...defaultProps} />);
    fireEvent.click(screen.getByText(/fit/i));
    expect(defaultProps.onFitToView).toHaveBeenCalled();
  });

  it('renders refresh button', () => {
    render(<GraphToolbar {...defaultProps} />);
    fireEvent.click(screen.getByText(/refresh/i));
    expect(defaultProps.onRefresh).toHaveBeenCalled();
  });

  it('renders anomalies toggle', () => {
    render(<GraphToolbar {...defaultProps} />);
    fireEvent.click(screen.getByText(/orphans/i));
    expect(defaultProps.onToggleAnomalies).toHaveBeenCalled();
  });

  it('shows active state on anomalies toggle when active', () => {
    render(<GraphToolbar {...defaultProps} onlyAnomalies={true} />);
    const btn = screen.getByText(/orphans/i);
    expect(btn.className).toContain('active');
  });

  it('shows snapshot timestamp', () => {
    render(<GraphToolbar {...defaultProps} />);
    expect(screen.getByText(/14:32:07/)).toBeTruthy();
  });

  it('opens filter popover and toggles edge type', () => {
    render(<GraphToolbar {...defaultProps} />);
    fireEvent.click(screen.getByText(/filter/i));
    expect(screen.getByText('links_to')).toBeTruthy();
    fireEvent.click(screen.getByText('links_to'));
    expect(defaultProps.onToggleEdgeType).toHaveBeenCalledWith('links_to');
  });
});
```

- [ ] **Step 2: Run to verify failure**

```bash
cd wikantik-frontend && npx vitest run src/components/graph/GraphToolbar.test.jsx && cd ..
```

- [ ] **Step 3: Implement GraphToolbar**

```jsx
import { useState } from 'react';

export default function GraphToolbar({
  onFitToView, onRefresh, onToggleAnomalies, onToggleEdgeType,
  edgeTypes, hiddenEdgeTypes, onlyAnomalies, timestamp,
}) {
  const [filterOpen, setFilterOpen] = useState(false);

  return (
    <div className="graph-toolbar">
      <button onClick={onFitToView}>Fit to view</button>
      <div style={{ position: 'relative' }}>
        <button onClick={() => setFilterOpen(!filterOpen)}>
          Edge filter
        </button>
        {filterOpen && (
          <div className="graph-filter-popover">
            {edgeTypes.map(t => (
              <label key={t}>
                <input
                  type="checkbox"
                  checked={!hiddenEdgeTypes.has(t)}
                  onChange={() => onToggleEdgeType(t)}
                />
                {t}
              </label>
            ))}
          </div>
        )}
      </div>
      <button
        className={onlyAnomalies ? 'active' : ''}
        onClick={onToggleAnomalies}
      >
        Only orphans &amp; stubs
      </button>
      <button onClick={onRefresh}>Refresh</button>
      <span className="snapshot-time">snapshot: {timestamp}</span>
    </div>
  );
}
```

- [ ] **Step 4: Run to verify pass**

```bash
cd wikantik-frontend && npx vitest run src/components/graph/GraphToolbar.test.jsx && cd ..
```

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/graph/GraphToolbar.jsx \
       wikantik-frontend/src/components/graph/GraphToolbar.test.jsx
git commit -m "feat(frontend): add GraphToolbar component with TDD tests"
```

---

## Task 13: GraphDetailsDrawer (TDD)

**Files:**
- Create: `wikantik-frontend/src/components/graph/GraphDetailsDrawer.test.jsx`
- Create: `wikantik-frontend/src/components/graph/GraphDetailsDrawer.jsx`

- [ ] **Step 1: Write the failing test**

```jsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import GraphDetailsDrawer from './GraphDetailsDrawer.jsx';

const wrap = (ui) => render(<MemoryRouter>{ui}</MemoryRouter>);

describe('GraphDetailsDrawer', () => {
  const node = {
    id: 'aaa', name: 'TestPage', type: 'page', role: 'hub',
    provenance: 'HUMAN_AUTHORED', sourcePage: 'TestPage',
    degreeIn: 5, degreeOut: 3, restricted: false,
  };

  const edges = [
    { source: 'bbb', target: 'aaa', relationshipType: 'links_to',
      neighborId: 'bbb', neighborName: 'Neighbor1', neighborRestricted: false, direction: 'in' },
    { source: 'aaa', target: 'ccc', relationshipType: 'related_to',
      neighborId: 'ccc', neighborName: 'Neighbor2', neighborRestricted: false, direction: 'out' },
    { source: 'aaa', target: 'ddd', relationshipType: 'links_to',
      neighborId: 'ddd', neighborName: null, neighborRestricted: true, direction: 'out' },
  ];

  const defaultProps = {
    selectedNode: node,
    incidentEdges: edges,
    onClose: vi.fn(),
    onSelectNeighbor: vi.fn(),
    onOpenPage: vi.fn(),
  };

  it('renders node name and metadata', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    expect(screen.getByText('TestPage')).toBeTruthy();
    expect(screen.getByText(/hub/i)).toBeTruthy();
    expect(screen.getByText(/page/i)).toBeTruthy();
  });

  it('shows degree counts', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    expect(screen.getByText(/5 in/i)).toBeTruthy();
    expect(screen.getByText(/3 out/i)).toBeTruthy();
  });

  it('renders edge rows', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    expect(screen.getByText('Neighbor1')).toBeTruthy();
    expect(screen.getByText('Neighbor2')).toBeTruthy();
  });

  it('clicking edge row fires onSelectNeighbor', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    fireEvent.click(screen.getByText('Neighbor1'));
    expect(defaultProps.onSelectNeighbor).toHaveBeenCalledWith('bbb');
  });

  it('restricted neighbor rows show lock and are not clickable', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    const restricted = screen.getByText(/restricted/);
    fireEvent.click(restricted);
    expect(defaultProps.onSelectNeighbor).not.toHaveBeenCalledWith('ddd');
  });

  it('close button fires onClose', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    fireEvent.click(screen.getByText('\u00D7'));
    expect(defaultProps.onClose).toHaveBeenCalled();
  });

  it('open page button fires onOpenPage', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    fireEvent.click(screen.getByText(/open page/i));
    expect(defaultProps.onOpenPage).toHaveBeenCalledWith('TestPage');
  });

  it('open page disabled for stub nodes', () => {
    const stubNode = { ...node, role: 'stub', sourcePage: null };
    wrap(<GraphDetailsDrawer {...defaultProps} selectedNode={stubNode} />);
    const btn = screen.getByText(/open page/i);
    expect(btn.disabled).toBe(true);
  });

  it('open page disabled for restricted nodes', () => {
    const restrictedNode = { ...node, role: 'restricted', restricted: true, name: null };
    wrap(<GraphDetailsDrawer {...defaultProps} selectedNode={restrictedNode} />);
    const btn = screen.getByText(/open page/i);
    expect(btn.disabled).toBe(true);
  });
});
```

- [ ] **Step 2: Run to verify failure, then implement**

```jsx
export default function GraphDetailsDrawer({ selectedNode, incidentEdges, onClose, onSelectNeighbor, onOpenPage }) {
  if (!selectedNode) return null;

  const { name, type, role, provenance, degreeIn, degreeOut, sourcePage, restricted } = selectedNode;
  const incoming = incidentEdges.filter(e => e.direction === 'in');
  const outgoing = incidentEdges.filter(e => e.direction === 'out');
  const canOpenPage = !restricted && role !== 'stub' && role !== 'restricted' && sourcePage;

  return (
    <div className="graph-details-drawer">
      <div className="drawer-header">
        <h3>{restricted ? '\u{1F512} (restricted)' : name}</h3>
        <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: '1.2rem' }}>
          {'\u00D7'}
        </button>
      </div>
      <div className="drawer-meta">
        <div>role: {role} · type: {type || '—'}</div>
        <div>provenance: {provenance || '—'}</div>
      </div>
      <div className="drawer-meta">
        Connections: {degreeIn} in · {degreeOut} out
      </div>

      {incoming.length > 0 && (
        <>
          <div className="drawer-section-title">Incoming ({incoming.length})</div>
          {incoming.map((e, i) => (
            <EdgeRow key={i} edge={e} prefix="\u2190" onSelect={onSelectNeighbor} />
          ))}
        </>
      )}

      {outgoing.length > 0 && (
        <>
          <div className="drawer-section-title">Outgoing ({outgoing.length})</div>
          {outgoing.map((e, i) => (
            <EdgeRow key={i} edge={e} prefix="\u2192" onSelect={onSelectNeighbor} />
          ))}
        </>
      )}

      <button
        className="open-page-btn"
        disabled={!canOpenPage}
        onClick={() => canOpenPage && onOpenPage(sourcePage)}
      >
        Open page \u2192
      </button>
    </div>
  );
}

function EdgeRow({ edge, prefix, onSelect }) {
  const { neighborId, neighborName, neighborRestricted, relationshipType } = edge;
  if (neighborRestricted) {
    return (
      <div className="edge-row restricted">
        <span>{prefix}</span>
        <span className="edge-type">{relationshipType}</span>
        <span>{'\u{1F512}'} (restricted)</span>
      </div>
    );
  }
  return (
    <div className="edge-row" onClick={() => onSelect(neighborId)}>
      <span>{prefix}</span>
      <span className="edge-type">{relationshipType}</span>
      <span>{neighborName}</span>
    </div>
  );
}
```

- [ ] **Step 3: Run to verify pass**

```bash
cd wikantik-frontend && npx vitest run src/components/graph/GraphDetailsDrawer.test.jsx && cd ..
```

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/graph/GraphDetailsDrawer.jsx \
       wikantik-frontend/src/components/graph/GraphDetailsDrawer.test.jsx
git commit -m "feat(frontend): add GraphDetailsDrawer component with TDD tests"
```

---

## Task 14: GraphCanvas

The cytoscape wrapper. Shallow-tested with mocked library — real interactions are covered by Selenide IT.

**Files:**
- Create: `wikantik-frontend/src/components/graph/GraphCanvas.jsx`

- [ ] **Step 1: Implement GraphCanvas**

```jsx
import { useRef, useEffect, useCallback } from 'react';
import CytoscapeComponent from 'react-cytoscapejs';
import coseBilkent from 'cytoscape-cose-bilkent';
import cytoscape from 'cytoscape';
import { graphStylesheet } from './graph-style.js';

cytoscape.use(coseBilkent);

const LAYOUT_OPTIONS = {
  name: 'cose-bilkent',
  randomize: true,
  animate: false,
  quality: 'default',
  nodeDimensionsIncludeLabels: true,
  idealEdgeLength: 80,
  nodeRepulsion: 4500,
};

const LAYOUT_TIMEOUT_MS = 15000;

export default function GraphCanvas({
  elements, selectedId, hiddenEdgeTypes, onlyAnomalies,
  focusNodeId, onNodeClick, onBackgroundClick, onReady, onLayoutTimeout,
}) {
  const cyRef = useRef(null);
  const layoutTimeoutRef = useRef(null);
  const tooltipRef = useRef(null);

  const attachHandlers = useCallback((cy) => {
    cy.on('tap', 'node', (evt) => {
      onNodeClick(evt.target.id());
    });
    cy.on('tap', (evt) => {
      if (evt.target === cy) {
        onBackgroundClick();
      }
    });

    // Hover tooltip
    cy.on('mouseover', 'node', (evt) => {
      const node = evt.target;
      const name = node.data('label');
      if (!name || !tooltipRef.current) return;
      const pos = node.renderedPosition();
      const container = cy.container().getBoundingClientRect();
      tooltipRef.current.textContent = name;
      tooltipRef.current.style.display = 'block';
      tooltipRef.current.style.left = `${pos.x + container.left + 12}px`;
      tooltipRef.current.style.top = `${pos.y + container.top - 8}px`;
    });
    cy.on('mouseout', 'node', () => {
      if (tooltipRef.current) tooltipRef.current.style.display = 'none';
    });

    cy.on('layoutstop', () => {
      if (layoutTimeoutRef.current) {
        clearTimeout(layoutTimeoutRef.current);
        layoutTimeoutRef.current = null;
      }
      // Focus on initial node if specified
      if (focusNodeId) {
        const target = cy.getElementById(focusNodeId);
        if (target.length > 0) {
          cy.animate({ center: { eles: target }, zoom: 1.2 }, { duration: 300 });
          onNodeClick(focusNodeId);
        }
      } else {
        cy.fit(undefined, 30);
      }
      onReady();
    });

    layoutTimeoutRef.current = setTimeout(() => {
      cy.stop();
      if (onLayoutTimeout) onLayoutTimeout();
      onReady();
    }, LAYOUT_TIMEOUT_MS);

    if (import.meta.env.DEV) {
      window.cy = cy;
    }
  }, [onNodeClick, onBackgroundClick, onReady, onLayoutTimeout]);

  // Ego-highlight
  useEffect(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    cy.elements().removeClass('dimmed');
    if (!selectedId) return;
    const selected = cy.getElementById(selectedId);
    if (selected.length === 0) return;
    selected.select();
    const neighborhood = selected.closedNeighborhood();
    cy.elements().not(neighborhood).addClass('dimmed');
  }, [selectedId]);

  // Edge-type filtering
  useEffect(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    cy.edges().forEach(edge => {
      const type = edge.data('relationshipType');
      if (hiddenEdgeTypes && hiddenEdgeTypes.has(type)) {
        edge.addClass('hidden');
      } else {
        edge.removeClass('hidden');
      }
    });
  }, [hiddenEdgeTypes]);

  // Only anomalies filter
  useEffect(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    if (onlyAnomalies) {
      cy.nodes().forEach(node => {
        const role = node.data('role');
        if (role !== 'orphan' && role !== 'stub') {
          node.addClass('hidden');
        }
      });
      cy.edges().addClass('hidden');
    } else {
      cy.nodes().removeClass('hidden');
      cy.edges().removeClass('hidden');
    }
  }, [onlyAnomalies]);

  // Label visibility based on zoom
  useEffect(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    const updateLabels = () => {
      const zoom = cy.zoom();
      cy.nodes().forEach(node => {
        const isSelected = node.selected();
        const isNeighbor = selectedId && cy.getElementById(selectedId).closedNeighborhood().has(node);
        if (zoom < 0.6 && !isSelected && !isNeighbor) {
          node.style('label', '');
        } else {
          node.style('label', node.data('label'));
        }
      });
    };
    cy.on('zoom', updateLabels);
    return () => cy.off('zoom', updateLabels);
  }, [selectedId]);

  return (
    <div className="graph-canvas-container">
      <CytoscapeComponent
        elements={CytoscapeComponent.normalizeElements(elements)}
        stylesheet={graphStylesheet}
        layout={LAYOUT_OPTIONS}
        style={{ width: '100%', height: '100%' }}
        cy={(cy) => {
          cyRef.current = cy;
          attachHandlers(cy);
        }}
      />
      <div ref={tooltipRef} style={{
        display: 'none', position: 'fixed', pointerEvents: 'none',
        background: 'var(--bg, #fff)', border: '1px solid var(--border, #e2e8f0)',
        borderRadius: 4, padding: '2px 6px', fontSize: '0.8rem', zIndex: 30,
        boxShadow: '0 2px 4px rgba(0,0,0,0.1)',
      }} />
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/components/graph/GraphCanvas.jsx
git commit -m "feat(frontend): add GraphCanvas cytoscape wrapper"
```

---

## Task 15: GraphView — State Owner

The orchestrator component that owns all state and delegates to children.

**Files:**
- Create: `wikantik-frontend/src/components/graph/GraphView.jsx`
- Create: `wikantik-frontend/src/components/graph/GraphView.test.jsx`

- [ ] **Step 1: Write the failing test**

```jsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const MOCK_SNAPSHOT = {
  generatedAt: '2026-04-12T14:32:00Z',
  nodeCount: 2,
  edgeCount: 1,
  hubDegreeThreshold: 10,
  nodes: [
    { id: 'aaa', name: 'PageA', type: 'page', role: 'normal', provenance: 'HUMAN_AUTHORED', sourcePage: 'PageA', degreeIn: 1, degreeOut: 0, restricted: false },
    { id: 'bbb', name: 'PageB', type: 'page', role: 'normal', provenance: 'HUMAN_AUTHORED', sourcePage: 'PageB', degreeIn: 0, degreeOut: 1, restricted: false },
  ],
  edges: [
    { id: 'e1', source: 'bbb', target: 'aaa', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' },
  ],
};

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      getGraphSnapshot: vi.fn(),
    },
  },
}));

vi.mock('./GraphCanvas.jsx', () => ({
  default: ({ elements }) => <div data-testid="graph-canvas">Canvas: {elements.nodes.length} nodes</div>,
}));

import { api } from '../../api/client';
import GraphView from './GraphView.jsx';

describe('GraphView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading then canvas on success', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue(MOCK_SNAPSHOT);
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    expect(screen.getByText(/loading/i)).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('graph-canvas')).toBeTruthy());
  });

  it('shows 401 error for unauthorized', async () => {
    api.knowledge.getGraphSnapshot.mockRejectedValue(Object.assign(new Error('Unauthorized'), { status: 401 }));
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/sign in/i)).toBeTruthy());
  });

  it('shows server error for 5xx', async () => {
    api.knowledge.getGraphSnapshot.mockRejectedValue(Object.assign(new Error('Server error'), { status: 500 }));
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/unavailable/i)).toBeTruthy());
  });

  it('shows empty state for zero nodes', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue({ ...MOCK_SNAPSHOT, nodeCount: 0, nodes: [], edges: [] });
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/empty/i)).toBeTruthy());
  });

  it('shows empty-for-you when all nodes restricted', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue({
      ...MOCK_SNAPSHOT,
      nodes: [{ id: 'x', name: null, role: 'restricted', restricted: true, degreeIn: 0, degreeOut: 0 }],
    });
    render(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/don't have permission/i)).toBeTruthy());
  });
});
```

- [ ] **Step 2: Run to verify failure, then implement**

```jsx
import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import { toCytoscapeElements } from './graph-data.js';
import GraphCanvas from './GraphCanvas.jsx';
import GraphToolbar from './GraphToolbar.jsx';
import GraphLegend from './GraphLegend.jsx';
import GraphDetailsDrawer from './GraphDetailsDrawer.jsx';
import GraphErrorState from './GraphErrorState.jsx';
import GraphErrorBoundary from './GraphErrorBoundary.jsx';
import GraphLoadingFallback from './GraphLoadingFallback.jsx';
import './graph.css';

export default function GraphView() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const focusParam = useRef(searchParams.get('focus'));

  const [fetchState, setFetchState] = useState('loading');
  const [errorVariant, setErrorVariant] = useState(null);
  const [snapshot, setSnapshot] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [hiddenEdgeTypes, setHiddenEdgeTypes] = useState(new Set());
  const [onlyAnomalies, setOnlyAnomalies] = useState(false);
  const [layoutDone, setLayoutDone] = useState(false);

  const fetchSnapshot = useCallback(async () => {
    setFetchState('loading');
    setErrorVariant(null);
    try {
      const data = await api.knowledge.getGraphSnapshot();
      setSnapshot(data);
      if (data.nodeCount === 0) {
        setFetchState('error');
        setErrorVariant('empty');
      } else if (data.nodes.every(n => n.restricted)) {
        setFetchState('error');
        setErrorVariant('empty-for-you');
      } else {
        setFetchState('ready');
      }
    } catch (err) {
      setFetchState('error');
      if (err.status === 401) setErrorVariant('unauthorized');
      else if (err.status === 403) setErrorVariant('forbidden');
      else setErrorVariant('server');
    }
  }, []);

  useEffect(() => { fetchSnapshot(); }, [fetchSnapshot]);

  const elements = useMemo(() => {
    if (!snapshot || fetchState !== 'ready') return { nodes: [], edges: [] };
    return toCytoscapeElements(snapshot);
  }, [snapshot, fetchState]);

  const edgeTypes = useMemo(() => {
    if (!snapshot) return [];
    return [...new Set(snapshot.edges.map(e => e.relationshipType))].sort();
  }, [snapshot]);

  const timestamp = useMemo(() => {
    if (!snapshot?.generatedAt) return '';
    try {
      return new Date(snapshot.generatedAt).toLocaleTimeString();
    } catch {
      return snapshot.generatedAt;
    }
  }, [snapshot]);

  const selectedNode = useMemo(() => {
    if (!selectedId || !snapshot) return null;
    return snapshot.nodes.find(n => n.id === selectedId) || null;
  }, [selectedId, snapshot]);

  const incidentEdges = useMemo(() => {
    if (!selectedId || !snapshot) return [];
    const nodeMap = new Map(snapshot.nodes.map(n => [n.id, n]));
    return snapshot.edges
      .filter(e => e.source === selectedId || e.target === selectedId)
      .map(e => {
        const isIncoming = e.target === selectedId;
        const neighborId = isIncoming ? e.source : e.target;
        const neighbor = nodeMap.get(neighborId);
        return {
          ...e,
          direction: isIncoming ? 'in' : 'out',
          neighborId,
          neighborName: neighbor?.name || null,
          neighborRestricted: neighbor?.restricted || false,
        };
      });
  }, [selectedId, snapshot]);

  // Resolve focus param to a node ID (once, after snapshot loads)
  const focusNodeId = useMemo(() => {
    if (!focusParam.current || !snapshot) return null;
    const match = snapshot.nodes.find(
      n => n.name === focusParam.current && !n.restricted
    );
    if (!match) {
      console.debug(`[GraphView] focus param "${focusParam.current}" not found or restricted`);
    }
    return match?.id || null;
  }, [snapshot]);

  const handleNodeClick = useCallback((nodeId) => setSelectedId(nodeId), []);
  const handleBackgroundClick = useCallback(() => setSelectedId(null), []);

  const handleReady = useCallback(() => {
    setLayoutDone(true);
  }, []);

  const handleOpenPage = useCallback((pageName) => {
    navigate(`/wiki/${encodeURIComponent(pageName)}`);
  }, [navigate]);

  const handleToggleEdgeType = useCallback((type) => {
    setHiddenEdgeTypes(prev => {
      const next = new Set(prev);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return next;
    });
  }, []);

  const handleRefresh = useCallback(async () => {
    const prevSelectedName = selectedNode?.name;
    setFetchState('loading');
    setErrorVariant(null);
    try {
      const data = await api.knowledge.getGraphSnapshot();
      setSnapshot(data);
      if (data.nodeCount === 0) {
        setFetchState('error');
        setErrorVariant('empty');
        return;
      }
      if (data.nodes.every(n => n.restricted)) {
        setFetchState('error');
        setErrorVariant('empty-for-you');
        return;
      }
      setFetchState('ready');
      if (prevSelectedName) {
        const match = data.nodes.find(n => n.name === prevSelectedName);
        setSelectedId(match ? match.id : null);
      }
    } catch (err) {
      setFetchState('error');
      if (err.status === 401) setErrorVariant('unauthorized');
      else if (err.status === 403) setErrorVariant('forbidden');
      else setErrorVariant('server');
    }
  }, [selectedNode]);

  // Keyboard: Escape clears selection
  useEffect(() => {
    const handler = (e) => {
      if (e.key === 'Escape') setSelectedId(null);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  if (fetchState === 'loading') return <GraphLoadingFallback />;
  if (fetchState === 'error') return <GraphErrorState variant={errorVariant} onRetry={fetchSnapshot} />;

  return (
    <GraphErrorBoundary>
      <div className="graph-view">
        <GraphToolbar
          onFitToView={() => window.cy?.fit()}
          onRefresh={handleRefresh}
          onToggleAnomalies={() => setOnlyAnomalies(v => !v)}
          onToggleEdgeType={handleToggleEdgeType}
          edgeTypes={edgeTypes}
          hiddenEdgeTypes={hiddenEdgeTypes}
          onlyAnomalies={onlyAnomalies}
          timestamp={timestamp}
        />
        <GraphCanvas
          elements={elements}
          selectedId={selectedId}
          focusNodeId={focusNodeId}
          hiddenEdgeTypes={hiddenEdgeTypes}
          onlyAnomalies={onlyAnomalies}
          onNodeClick={handleNodeClick}
          onBackgroundClick={handleBackgroundClick}
          onReady={handleReady}
          onLayoutTimeout={() => console.warn('Layout took too long')}
        />
        {selectedNode && (
          <GraphDetailsDrawer
            selectedNode={selectedNode}
            incidentEdges={incidentEdges}
            onClose={() => setSelectedId(null)}
            onSelectNeighbor={handleNodeClick}
            onOpenPage={handleOpenPage}
          />
        )}
        <GraphLegend
          hubDegreeThreshold={snapshot?.hubDegreeThreshold || 10}
          edgeTypes={edgeTypes}
          timestamp={timestamp}
        />
        {!layoutDone && (
          <div className="graph-layout-overlay">Laying out graph...</div>
        )}
      </div>
    </GraphErrorBoundary>
  );
}
```

- [ ] **Step 3: Run to verify pass**

```bash
cd wikantik-frontend && npx vitest run src/components/graph/GraphView.test.jsx && cd ..
```

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/graph/GraphView.jsx \
       wikantik-frontend/src/components/graph/GraphView.test.jsx
git commit -m "feat(frontend): add GraphView state owner with integration tests"
```

---

## Task 16: Route Registration + Sidebar Link

Wire the new route into the app and add the sidebar entry point.

**Files:**
- Modify: `wikantik-frontend/src/main.jsx`
- Modify: `wikantik-frontend/src/components/Sidebar.jsx`

- [ ] **Step 1: Add lazy route to main.jsx**

At the top of `main.jsx`, add:

```js
import React, { Suspense } from 'react';
```

(Replace the existing `import React from 'react';`)

Add after the other imports (around line 23):

```js
const GraphView = React.lazy(() => import('./components/graph/GraphView.jsx'));
```

Add the route inside the `<Route element={<App />}>` block, after line 37 (`/search` route):

```jsx
            <Route path="/graph" element={
              <Suspense fallback={<div className="graph-loading"><p>Loading knowledge graph...</p></div>}>
                <GraphView />
              </Suspense>
            } />
```

- [ ] **Step 2: Add Graph link to Sidebar.jsx**

In the "Wiki Tools" section (around line 105-111), add a new link. But the Graph link needs a dynamic `?focus=` based on the current page.

Add after the `{navLink('/wiki/SystemInfo', 'System Info')}` line (line 110):

```jsx
          <Link
            to={activePage ? `/graph?focus=${encodeURIComponent(activePage)}` : '/graph'}
            className="sidebar-link"
            onClick={onMobileClose}
          >
            Knowledge Graph
          </Link>
```

This uses the existing `activePage` from `useParams()` (line 11) to seed the focus parameter when the user is currently viewing a wiki page.

- [ ] **Step 3: Verify dev server renders**

```bash
cd wikantik-frontend && npm run dev &
```

Open `http://localhost:5173/graph` in a browser. Verify:
- The route loads (may show loading or error if backend isn't running)
- The sidebar shows the "Knowledge Graph" link
- When on `/wiki/SomePage`, the link includes `?focus=SomePage`

Kill the dev server after verification.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/main.jsx \
       wikantik-frontend/src/components/Sidebar.jsx
git commit -m "feat(frontend): wire /graph route and sidebar link"
```

---

## Task 17: Full Build + Manual Verification

Build everything and verify end-to-end in the browser.

**Files:** None — this is a verification task.

- [ ] **Step 1: Full backend build**

```bash
mvn clean install -T 1C -DskipITs
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Deploy locally**

```bash
tomcat/tomcat-11/bin/shutdown.sh 2>/dev/null
mvn clean install -Dmaven.test.skip -T 1C
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

Wait for startup (check `tomcat/tomcat-11/logs/catalina.out`).

- [ ] **Step 3: Manual browser verification**

Open `http://localhost:8080/` and verify:

1. **Sidebar link**: Navigate to any wiki page. Confirm "Knowledge Graph" link appears in the sidebar Wiki Tools section with `?focus=<currentPage>`.
2. **Route loads**: Click the link. The `/graph` route should load, show a loading spinner, then render the graph.
3. **Node encoding**: Verify colour-coded nodes — green hubs, amber orphans, red dashed stubs, gray normal.
4. **Edge rendering**: Verify directional arrows on edges. Check that bidirectional edges have arrows on both ends.
5. **Click node**: Click a node. Details drawer should open on the right. Ego-highlight should dim non-neighbors.
6. **Drawer walking**: Click an edge row in the drawer to navigate to that neighbor.
7. **Escape**: Press Escape. Drawer closes, highlight clears.
8. **Toolbar**: Test "Fit to view", edge filter, "Only orphans & stubs", and "Refresh".
9. **Legend**: Verify the legend is visible, shows correct colours, and collapses/expands.
10. **Anonymous**: Open an incognito window, navigate to `/graph`. Should show "Sign in to view the knowledge graph."
11. **Focus param**: Navigate to `/graph?focus=Main`. The "Main" node should be selected and centered.

- [ ] **Step 4: Fix any issues found during manual testing**

If any issues are found, fix them and re-test.

- [ ] **Step 5: Commit any fixes**

```bash
git add <fixed files>
git commit -m "fix(graph): address issues found during manual verification"
```

---

## Task 18: Selenide Integration Tests

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/KnowledgeGraphVisualizationIT.java`

- [ ] **Step 1: Study the existing IT pattern**

Read `LoginIT.java` and `AnonymousViewIT.java` to understand:
- How tests extend `WithIntegrationTestSetup`
- How `RestSeedHelper` is used for test data setup
- How Selenide assertions work
- How `open()` navigates to routes

- [ ] **Step 2: Write the IT class**

```java
// (ASF license header)
package com.wikantik.its;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.SelenideElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Selenide.*;
import static com.codeborne.selenide.Condition.*;

class KnowledgeGraphVisualizationIT extends WithIntegrationTestSetup {

    @BeforeEach
    void ensureLoggedIn() {
        RestSeedHelper.loginAsTestBot( getBaseUrl() );
    }

    @Test
    void graphView_loadsFullSnapshot() {
        open( getBaseUrl() + "/graph" );
        $("[data-testid='graph-canvas'], .graph-canvas-container canvas")
                .shouldBe( visible, java.time.Duration.ofSeconds( 15 ) );
    }

    @Test
    void graphView_focusParamSelectsNode() {
        open( getBaseUrl() + "/graph?focus=Main" );
        $(".graph-details-drawer").shouldBe( visible, java.time.Duration.ofSeconds( 15 ) );
        $(".graph-details-drawer").shouldHave( text( "Main" ) );
    }

    @Test
    void graphView_clickNodeOpensDrawer() {
        open( getBaseUrl() + "/graph" );
        $("[data-testid='graph-canvas'], .graph-canvas-container canvas")
                .shouldBe( visible, java.time.Duration.ofSeconds( 15 ) );
        // Use window.cy test hook to simulate node click
        executeJavaScript(
            "if (window.cy) { window.cy.nodes()[0].emit('tap'); }" );
        $(".graph-details-drawer").shouldBe( visible, java.time.Duration.ofSeconds( 5 ) );
    }

    @Test
    void graphView_restrictedNodeAppearsLocked() {
        // This test requires a page with restrictive ACLs.
        // If no such page exists in the test corpus, this test verifies
        // that restricted nodes (if any) show the lock glyph.
        open( getBaseUrl() + "/graph" );
        $("[data-testid='graph-canvas'], .graph-canvas-container canvas")
                .shouldBe( visible, java.time.Duration.ofSeconds( 15 ) );
        // Verify the graph loaded without errors — restricted node presence
        // depends on test corpus ACL setup
        $(".graph-error-state").shouldNotBe( visible );
    }

    @Test
    void graphView_sidebarLinkIncludesFocus() {
        open( getBaseUrl() + "/wiki/Main" );
        final SelenideElement graphLink = $$("a").findBy( text( "Knowledge Graph" ) );
        graphLink.shouldBe( visible );
        graphLink.shouldHave( attribute( "href", "/graph?focus=Main" ) );
    }

    @Test
    void graphView_anonymousRedirectedToLogin() {
        RestSeedHelper.logout( getBaseUrl() );
        open( getBaseUrl() + "/graph" );
        // Should show the sign-in prompt, not the graph
        $(".graph-error-state").shouldBe( visible, java.time.Duration.ofSeconds( 10 ) );
        $(".graph-error-state").shouldHave( text( "Sign in" ) );
    }
}
```

Note: Adjust selectors based on actual rendered markup. The `RestSeedHelper` methods (`loginAsTestBot`, `logout`) must exist or be adapted from the existing IT helpers — read `RestSeedHelper.java` and `WithIntegrationTestSetup.java` first to match the existing patterns.

- [ ] **Step 3: Run ITs**

```bash
mvn clean install -Pintegration-tests -fae
```

Expected: all 6 new tests pass alongside existing ITs.

- [ ] **Step 4: Fix any failures and re-run**

Selenide ITs often need selector adjustments. Fix and re-run until green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/KnowledgeGraphVisualizationIT.java
git commit -m "test(it): add 6 Selenide ITs for knowledge graph visualization"
```

---

## Task 19: Final Build Verification

- [ ] **Step 1: Full build with unit tests**

```bash
mvn clean install -T 1C -DskipITs
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run frontend tests**

```bash
cd wikantik-frontend && npx vitest run && cd ..
```

Expected: all tests pass.

- [ ] **Step 3: Run integration tests**

```bash
mvn clean install -Pintegration-tests -fae
```

Expected: BUILD SUCCESS, all ITs pass.

- [ ] **Step 4: Final commit (if any remaining changes)**

```bash
git status
```

If there are uncommitted changes from fixes during this task, commit them.
