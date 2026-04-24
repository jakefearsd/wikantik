# Cycle 3: KG Tools — Mention-Based Graph Rebacking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reback the `search_knowledge`, `query_nodes`, `traverse`, and `discover_schema` MCP tools so they present the mention-covered subset of the knowledge graph — i.e., nodes the LLM extractor has actually found in chunk content — instead of the full union of `kg_nodes` / `kg_edges` which includes link/metadata-derived nodes from `GraphProjector`.

**Architecture:** Add a `MentionIndex` read-only utility over `chunk_entity_mentions`. Do mention-filtering at the **MCP tool layer**, not at the `KnowledgeGraphService` interface — the service is shared with admin REST endpoints and the admin UI, which must continue to see the full graph (this is Option A from the spec's risks section: read-filter, not data purge). `traverse` gets a new service method `traverseByCoMention` so the BFS logic stays in one place. The 6 KG tools split 4/2: `search_knowledge`, `query_nodes`, `traverse`, `discover_schema` are rebacked; `get_node` and `find_similar` unchanged (`find_similar` already uses mentions via `NodeMentionSimilarity`).

**Tech Stack:** Java 21, JUnit 5 + Mockito + Testcontainers (Postgres for the existing KG tests), existing `JdbcKnowledgeRepository` / `ChunkEntityMentionRepository` data access.

**Reference spec:** `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`
**Reference cycle 1/2 plans:** `docs/superpowers/plans/2026-04-24-cycle-1-context-retrieval-service.md`, `docs/superpowers/plans/2026-04-24-cycle-2-knowledge-mcp-tools.md`

---

## Scope decision (Option A per spec)

The spec lists two options for the rebacking:
- Option A: read-filter — tools ignore unmentioned nodes; legacy writes continue into `kg_nodes`/`kg_edges` but are invisible to MCP readers.
- Option B: stop legacy writes + purge — cleaner end state but irreversible.

This plan implements **Option A**. Rationale:
- Reversible: if the mention filter turns out to drop legitimate hand-curated nodes, one config flag flips back.
- Admin UI unaffected: it still sees the full graph via the unchanged `KnowledgeGraphService` methods.
- Option B moves to Cycle 6 alongside `GraphProjector` retirement.

`find_similar` is already mention-based (uses `NodeMentionSimilarity.similarTo`) so it's excluded from this cycle. `get_node` is a targeted single-node lookup — mention-filtering it would make the tool fail to resolve specific node IDs, so it's also excluded.

---

## File Structure

**Creating (in `wikantik-main/src/main/java/com/wikantik/knowledge/`):**
- `MentionIndex.java` — read-only utility class; one SQL-backed method per query we need

**Creating (in `wikantik-main/src/test/java/com/wikantik/knowledge/`):**
- `MentionIndexTest.java` — unit tests for the utility (testcontainer-backed)

**Creating (in `wikantik-api/src/main/java/com/wikantik/api/knowledge/`):**
- Additional method on `KnowledgeGraphService`: `TraversalResult traverseByCoMention(String startNodeName, int maxDepth, int minSharedChunks)` — walks co-mention edges via BFS.

**Modifying (in `wikantik-main/src/main/java/com/wikantik/knowledge/`):**
- `DefaultKnowledgeGraphService.java` — implement `traverseByCoMention`.

**Modifying (in `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/`):**
- `SearchKnowledgeTool.java` — post-filter results to mention-covered names.
- `QueryNodesTool.java` — post-filter results to mention-covered names.
- `TraverseTool.java` — call `traverseByCoMention` instead of `traverse`.
- `DiscoverSchemaTool.java` — report mention-covered node/edge counts instead of full counts.
- `KnowledgeMcpInitializer.java` — pass a `MentionIndex` into the tools that need it.

**Modifying (in `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/`):**
- `KnowledgeMcpToolsTest.java` — extend tests to cover the new mention-filter behavior.

---

## Task 1: `MentionIndex` utility + test

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/MentionIndexTest.java`

- [ ] **Step 1: Write failing test**

Create `wikantik-main/src/test/java/com/wikantik/knowledge/MentionIndexTest.java`:

```java
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class MentionIndexTest {

    private static DataSource dataSource;

    @BeforeAll
    static void init() { dataSource = PostgresTestContainer.createDataSource(); }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void isMentioned_returnsTrueForMentionedNode() throws Exception {
        final UUID nodeId = UUID.randomUUID();
        final UUID chunkId = UUID.randomUUID();
        seedChunk( chunkId, "P", 0 );
        seedNode( nodeId, "Alpha" );
        seedMention( chunkId, nodeId, 0.9 );

        final MentionIndex idx = new MentionIndex( dataSource );
        assertTrue( idx.isMentioned( nodeId ) );
    }

    @Test
    void isMentioned_returnsFalseForUnmentionedNode() {
        final UUID nodeId = UUID.randomUUID();
        final MentionIndex idx = new MentionIndex( dataSource );
        assertFalse( idx.isMentioned( nodeId ) );
    }

    @Test
    void getMentionedIds_returnsAllDistinctIds() throws Exception {
        final UUID n1 = UUID.randomUUID();
        final UUID n2 = UUID.randomUUID();
        final UUID n3 = UUID.randomUUID();
        final UUID c1 = UUID.randomUUID();
        final UUID c2 = UUID.randomUUID();
        seedChunk( c1, "P1", 0 );
        seedChunk( c2, "P2", 0 );
        seedNode( n1, "A" ); seedNode( n2, "B" ); seedNode( n3, "C" );
        seedMention( c1, n1, 0.9 );
        seedMention( c1, n2, 0.8 );
        seedMention( c2, n2, 0.7 );
        // n3 is never mentioned

        final MentionIndex idx = new MentionIndex( dataSource );
        final Set< UUID > ids = idx.getMentionedIds();
        assertEquals( 2, ids.size() );
        assertTrue( ids.contains( n1 ) );
        assertTrue( ids.contains( n2 ) );
        assertFalse( ids.contains( n3 ) );
    }

    @Test
    void getCoMentionCounts_returnsSharedChunkCounts() throws Exception {
        final UUID alpha = UUID.randomUUID();
        final UUID beta  = UUID.randomUUID();
        final UUID gamma = UUID.randomUUID();
        final UUID c1 = UUID.randomUUID();
        final UUID c2 = UUID.randomUUID();
        final UUID c3 = UUID.randomUUID();
        seedChunk( c1, "P1", 0 );
        seedChunk( c2, "P1", 1 );
        seedChunk( c3, "P2", 0 );
        seedNode( alpha, "Alpha" ); seedNode( beta, "Beta" ); seedNode( gamma, "Gamma" );

        // alpha+beta share c1 and c2 (count=2); alpha+gamma share c3 (count=1)
        seedMention( c1, alpha, 0.9 );
        seedMention( c1, beta, 0.8 );
        seedMention( c2, alpha, 0.9 );
        seedMention( c2, beta, 0.8 );
        seedMention( c3, alpha, 0.9 );
        seedMention( c3, gamma, 0.8 );

        final MentionIndex idx = new MentionIndex( dataSource );
        final Map< UUID, Integer > counts = idx.getCoMentionCounts( alpha );
        assertEquals( 2, (int) counts.get( beta ) );
        assertEquals( 1, (int) counts.get( gamma ) );
        assertFalse( counts.containsKey( alpha ),
            "self should not appear in co-mention counts" );
    }

    @Test
    void getCoMentionCounts_returnsEmptyForUnmentionedNode() {
        final UUID nodeId = UUID.randomUUID();
        final MentionIndex idx = new MentionIndex( dataSource );
        assertTrue( idx.getCoMentionCounts( nodeId ).isEmpty() );
    }

    // --- seed helpers ---

    private static void seedChunk( UUID id, String page, int idx ) throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + id + "', '" + page + "', " + idx + ", ARRAY['H'], 'body', 4, 1, '" + id + "h')" );
        }
    }

    private static void seedNode( UUID id, String name ) throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_nodes (id, name, node_type, provenance, created_at, updated_at) VALUES "
              + "('" + id + "', '" + name + "', 'type', 'human-authored', NOW(), NOW())" );
        }
    }

    private static void seedMention( UUID chunkId, UUID nodeId, double confidence ) throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunkId + "', '" + nodeId + "', " + confidence + ", 'test', NOW())" );
        }
    }
}
```

- [ ] **Step 2: Run test to verify compile failure**

```bash
cd /home/jakefear/source/jspwiki && mvn -pl wikantik-main -q test -Dtest=MentionIndexTest
```

Expected: compile error — `MentionIndex` unresolved.

- [ ] **Step 3: Create `MentionIndex.java`**

Create `wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java` (Apache 2 header matching `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java` lines 1-18, then):

```java
package com.wikantik.knowledge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only view over {@code chunk_entity_mentions} — answers "is this node
 * mentioned by the extractor?" and "what nodes share chunks with this one?"
 * Provides the mention-covered subset that agent-facing MCP tools surface,
 * hiding nodes that exist in {@code kg_nodes} only because the legacy
 * {@code GraphProjector} put them there from frontmatter/links but no
 * extractor has confirmed them in real content.
 */
public class MentionIndex {

    private static final Logger LOG = LogManager.getLogger( MentionIndex.class );

    private static final String EXISTS_SQL =
        "SELECT 1 FROM chunk_entity_mentions WHERE node_id = ? LIMIT 1";

    private static final String ALL_MENTIONED_IDS_SQL =
        "SELECT DISTINCT node_id FROM chunk_entity_mentions";

    /**
     * Counts shared chunks between {@code ?} and every other mentioned node.
     * Self is excluded.
     */
    private static final String COMENTION_COUNTS_SQL =
        "SELECT m2.node_id, COUNT( DISTINCT m1.chunk_id ) AS shared "
      + "  FROM chunk_entity_mentions m1 "
      + "  JOIN chunk_entity_mentions m2 ON m1.chunk_id = m2.chunk_id "
      + " WHERE m1.node_id = ? AND m2.node_id <> m1.node_id "
      + " GROUP BY m2.node_id";

    private final DataSource dataSource;

    public MentionIndex( final DataSource dataSource ) {
        if ( dataSource == null ) throw new IllegalArgumentException( "dataSource required" );
        this.dataSource = dataSource;
    }

    /** True iff the node appears in at least one chunk_entity_mentions row. */
    public boolean isMentioned( final UUID nodeId ) {
        if ( nodeId == null ) return false;
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( EXISTS_SQL ) ) {
            ps.setObject( 1, nodeId );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "MentionIndex.isMentioned failed for {}: {}", nodeId, e.getMessage(), e );
            return false;
        }
    }

    /** Every distinct node id that appears in chunk_entity_mentions. */
    public Set< UUID > getMentionedIds() {
        final Set< UUID > out = new HashSet<>();
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( ALL_MENTIONED_IDS_SQL );
              final ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) {
                out.add( rs.getObject( 1, UUID.class ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "MentionIndex.getMentionedIds failed: {}", e.getMessage(), e );
            return Set.of();
        }
        return out;
    }

    /**
     * For the given node id, return the map of other-node-id → shared-chunk-count.
     * Result is ordered by descending count then by node id for stability.
     */
    public Map< UUID, Integer > getCoMentionCounts( final UUID nodeId ) {
        if ( nodeId == null ) return Map.of();
        final Map< UUID, Integer > counts = new HashMap<>();
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( COMENTION_COUNTS_SQL ) ) {
            ps.setObject( 1, nodeId );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    counts.put( rs.getObject( 1, UUID.class ), rs.getInt( 2 ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "MentionIndex.getCoMentionCounts failed for {}: {}", nodeId, e.getMessage(), e );
            return Map.of();
        }
        // Sort by descending count then id for deterministic output
        final Map< UUID, Integer > sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
            .sorted( ( a, b ) -> {
                final int byCount = Integer.compare( b.getValue(), a.getValue() );
                return byCount != 0 ? byCount : a.getKey().compareTo( b.getKey() );
            } )
            .forEach( e -> sorted.put( e.getKey(), e.getValue() ) );
        return sorted;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn -pl wikantik-main -q test -Dtest=MentionIndexTest
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/MentionIndexTest.java
git commit -m "feat(knowledge): add MentionIndex read-only utility"
```

---

## Task 2: Add `traverseByCoMention` to `KnowledgeGraphService`

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`
- Create/Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java` (add a test method)

- [ ] **Step 1: Write failing test**

Either open the existing `DefaultKnowledgeGraphServiceTest.java` or create one. Add:

```java
    @Test
    void traverseByCoMention_bfsFromStartNode() throws Exception {
        // Seed 3 nodes, 2 chunks, cross-mentions: alpha+beta share c1 (count=2), alpha+gamma share c2 (count=1)
        final UUID alpha = UUID.randomUUID();
        final UUID beta  = UUID.randomUUID();
        final UUID gamma = UUID.randomUUID();
        final UUID c1    = UUID.randomUUID();
        final UUID c2    = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + c1 + "', 'P', 0, ARRAY['H'], 'x', 1, 1, 'h1'), "
              + "('" + c2 + "', 'P', 1, ARRAY['H'], 'x', 1, 1, 'h2')" );
            c.createStatement().execute(
                "INSERT INTO kg_nodes (id, name, node_type, provenance, created_at, updated_at) VALUES "
              + "('" + alpha + "', 'Alpha', 't', 'human-authored', NOW(), NOW()), "
              + "('" + beta  + "', 'Beta',  't', 'human-authored', NOW(), NOW()), "
              + "('" + gamma + "', 'Gamma', 't', 'human-authored', NOW(), NOW())" );
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + c1 + "', '" + alpha + "', 0.9, 't', NOW()), "
              + "('" + c1 + "', '" + beta  + "', 0.9, 't', NOW()), "
              + "('" + c2 + "', '" + alpha + "', 0.9, 't', NOW()), "
              + "('" + c2 + "', '" + gamma + "', 0.9, 't', NOW())" );
        }

        final TraversalResult res = service.traverseByCoMention( "Alpha", 1, 1 );
        assertEquals( 3, res.nodes().size() );
        assertTrue( res.edges().size() >= 2 );
    }
```

Add this import at the top of the test file if missing:
```java
import com.wikantik.api.knowledge.TraversalResult;
import java.sql.Connection;
import java.util.UUID;
```

- [ ] **Step 2: Run test to verify failure**

```bash
mvn -pl wikantik-main -q test -Dtest=DefaultKnowledgeGraphServiceTest#traverseByCoMention_bfsFromStartNode
```

Expected: compile failure — `traverseByCoMention` not defined.

- [ ] **Step 3: Add method to `KnowledgeGraphService` interface**

Open `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java`. Find the `// --- Traversal ---` section (around line 56). Add a second method right after the existing `traverse`:

```java
    /**
     * Traverse the co-mention graph: starting from the named node, walk
     * through nodes that share at least {@code minSharedChunks} chunks with
     * the current frontier, up to {@code maxDepth} hops. Returns every
     * visited node plus synthetic "co-mention" edges with the shared-chunk
     * count as a property on each edge.
     *
     * <p>Replaces {@link #traverse} for agent-facing MCP use when the
     * co-mention graph (from {@code chunk_entity_mentions}) is the
     * authoritative relationship source. {@code traverse} remains for
     * admin UI / REST paths that need the full {@code kg_edges} view.</p>
     *
     * @param startNodeName name of the seed node
     * @param maxDepth      BFS depth limit (1 = direct co-mentions only)
     * @param minSharedChunks minimum count of shared chunks required to follow
     *                        an edge (default 1)
     */
    TraversalResult traverseByCoMention( String startNodeName, int maxDepth, int minSharedChunks );
```

- [ ] **Step 4: Implement in `DefaultKnowledgeGraphService`**

Open `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`. Find the `// --- Traversal ---` section. The class needs access to a `MentionIndex`; add it as a field and constructor param.

Add a field and constructor overload at the top of the class (around line 50-65):

```java
    private final MentionIndex mentionIndex;
```

Update the two existing constructors to take the index (or null for test fixtures where mention-based traverse isn't exercised). Add a new constructor overload or add the field default to a zero-arg instance that works:

```java
    public DefaultKnowledgeGraphService( final JdbcKnowledgeRepository repo ) {
        this( repo, null, null );
    }

    public DefaultKnowledgeGraphService( final JdbcKnowledgeRepository repo, final Engine engine ) {
        this( repo, engine, null );
    }

    public DefaultKnowledgeGraphService( final JdbcKnowledgeRepository repo,
                                          final Engine engine,
                                          final MentionIndex mentionIndex ) {
        this.repo = repo;
        this.engine = engine;
        this.mentionIndex = mentionIndex;
    }
```

Add the new method next to the existing `traverse`. Place after the `return new TraversalResult( ... );` of the old `traverse`:

```java
    @Override
    public TraversalResult traverseByCoMention( final String startNodeName,
                                                final int maxDepth,
                                                final int minSharedChunks ) {
        if ( mentionIndex == null ) {
            LOG.warn( "traverseByCoMention called but MentionIndex is not configured" );
            return new TraversalResult( List.of(), List.of() );
        }
        final KgNode startNode = repo.getNodeByName( startNodeName );
        if ( startNode == null ) {
            return new TraversalResult( List.of(), List.of() );
        }
        final int effectiveMin = Math.max( 1, minSharedChunks );

        final Map< UUID, KgNode > visited = new LinkedHashMap<>();
        final List< KgEdge > collectedEdges = new ArrayList<>();
        final Queue< UUID > queue = new ArrayDeque<>();
        final Map< UUID, Integer > depthMap = new HashMap<>();

        visited.put( startNode.id(), startNode );
        queue.add( startNode.id() );
        depthMap.put( startNode.id(), 0 );

        while ( !queue.isEmpty() ) {
            final UUID currentId = queue.poll();
            final int currentDepth = depthMap.get( currentId );
            if ( currentDepth >= maxDepth ) continue;

            final Map< UUID, Integer > neighbors = mentionIndex.getCoMentionCounts( currentId );
            for ( final Map.Entry< UUID, Integer > e : neighbors.entrySet() ) {
                if ( e.getValue() < effectiveMin ) continue;
                final UUID neighborId = e.getKey();
                final int shared = e.getValue();

                // Synthesize a co-mention edge. KgEdge requires a provenance and
                // relationshipType; use AI_INFERRED + "co-mentions" with the
                // shared count exposed as a property so clients can see weight.
                collectedEdges.add( new KgEdge(
                    UUID.randomUUID(),
                    currentId, neighborId,
                    "co-mentions",
                    Provenance.AI_INFERRED,
                    Map.of( "sharedChunks", shared ),
                    Instant.now(),
                    Instant.now() ) );

                if ( !visited.containsKey( neighborId ) ) {
                    final KgNode neighbor = repo.getNode( neighborId );
                    if ( neighbor != null ) {
                        visited.put( neighborId, neighbor );
                        queue.add( neighborId );
                        depthMap.put( neighborId, currentDepth + 1 );
                    }
                }
            }
        }

        return new TraversalResult( new ArrayList<>( visited.values() ), collectedEdges );
    }
```

Make sure these imports are present (some are already there):
```java
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
```

And `Provenance`, `KgNode`, `KgEdge`, `TraversalResult` — already imported via `com.wikantik.api.knowledge.*`.

Test file must instantiate `DefaultKnowledgeGraphService` with a `MentionIndex`. Update the test's `@BeforeEach` setUp or constructor call to:

```java
    service = new DefaultKnowledgeGraphService(
        new JdbcKnowledgeRepository( dataSource ),
        null,
        new MentionIndex( dataSource ) );
```

(In the existing `KnowledgeMcpToolsTest` or `DefaultKnowledgeGraphServiceTest`.)

- [ ] **Step 5: Run the test**

```bash
mvn -pl wikantik-main -q test -Dtest=DefaultKnowledgeGraphServiceTest
```

Expected: `traverseByCoMention_bfsFromStartNode` passes plus every existing test in the class still passes.

- [ ] **Step 6: Verify `DefaultKnowledgeGraphServiceFactory` still builds**

`DefaultKnowledgeGraphServiceFactory` is how production wires the service. It must supply the new `MentionIndex`. Edit:

```bash
find . -name "KnowledgeGraphServiceFactory.java" -not -path "*/target/*"
```

Inspect the file. If it constructs the service via `new DefaultKnowledgeGraphService( repo )` or `new DefaultKnowledgeGraphService( repo, engine )`, the existing two-arg constructor still works (falls back to null MentionIndex). For the production path, we want a real `MentionIndex` — change the factory to:

```java
return new DefaultKnowledgeGraphService(
    repo,
    engine,
    new MentionIndex( dataSource ) );
```

Confirm the factory has access to the `DataSource` (it does for `repo` construction — just reuse it).

Also update any `KnowledgeMcpInitializer` test wiring if it instantiates the service directly.

- [ ] **Step 7: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java
git commit -m "feat(knowledge): add traverseByCoMention to KnowledgeGraphService"
```

(Adjust staged file list to match what you actually touched.)

---

## Task 3: Reback `SearchKnowledgeTool`

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java` (or create a new test class)

- [ ] **Step 1: Write failing test**

Find the test for `searchKnowledge_returnsMatches` (or the test that exercises `search_knowledge` in `KnowledgeMcpToolsTest`). Add a new test that creates TWO nodes — one mentioned, one unmentioned — and asserts `search_knowledge` returns ONLY the mentioned one.

In `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java`, append a test method:

```java
    @Test
    void searchKnowledge_filtersToMentionedNodesOnly() throws Exception {
        final UUID mentioned = service.upsertNode( "MentionedNode", "t", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID unmentioned = service.upsertNode( "UnmentionedNode", "t", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();

        // Seed a chunk + a mention for "mentioned" only
        final UUID chunkId = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + chunkId + "', 'P', 0, ARRAY['H'], 'x', 1, 1, 'h1')" );
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunkId + "', '" + mentioned + "', 0.9, 't', NOW())" );
        }

        final MentionIndex idx = new MentionIndex( dataSource );
        final SearchKnowledgeTool tool = new SearchKnowledgeTool( service, idx );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "query", "Node" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();

        assertTrue( text.contains( "MentionedNode" ) );
        assertFalse( text.contains( "UnmentionedNode" ) );
    }
```

Add this import:
```java
import com.wikantik.knowledge.MentionIndex;
```

- [ ] **Step 2: Run the test to see the compile failure**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=KnowledgeMcpToolsTest#searchKnowledge_filtersToMentionedNodesOnly
```

Expected: compile error on the 2-arg `SearchKnowledgeTool( service, idx )` constructor.

- [ ] **Step 3: Modify `SearchKnowledgeTool`**

Open `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java`. Change the constructor to accept an optional `MentionIndex`. Post-filter results in `execute`:

```java
    private final KnowledgeGraphService service;
    private final MentionIndex mentionIndex;

    public SearchKnowledgeTool( final KnowledgeGraphService service ) {
        this( service, null );
    }

    public SearchKnowledgeTool( final KnowledgeGraphService service,
                                 final MentionIndex mentionIndex ) {
        this.service = service;
        this.mentionIndex = mentionIndex;
    }
```

Update `execute` — after `service.searchKnowledge(...)` returns `nodes`, filter:

```java
            final List< KgNode > nodes = service.searchKnowledge( query, provenanceFilter, limit );
            final List< KgNode > filtered = filterToMentioned( nodes );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, Map.of( "results", filtered ) );
```

Add the helper method:

```java
    private List< KgNode > filterToMentioned( final List< KgNode > nodes ) {
        if ( mentionIndex == null ) return nodes;
        final List< KgNode > out = new ArrayList<>( nodes.size() );
        for ( final KgNode n : nodes ) {
            if ( mentionIndex.isMentioned( n.id() ) ) {
                out.add( n );
            }
        }
        return out;
    }
```

Add imports:
```java
import com.wikantik.knowledge.MentionIndex;
import java.util.ArrayList;
import java.util.List;
```

Update the tool description in `definition()` — append to the description string: `" Results are filtered to nodes the entity extractor has actually found in wiki content; nodes present only from legacy frontmatter/link projection are hidden."`

- [ ] **Step 4: Run tests**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=KnowledgeMcpToolsTest
```

Expected: all tests pass, including the new one.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java
git commit -m "feat(knowledge-mcp): filter search_knowledge to mention-covered nodes"
```

---

## Task 4: Reback `QueryNodesTool`

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/QueryNodesTool.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java`

- [ ] **Step 1: Write failing test**

Append to `KnowledgeMcpToolsTest.java`:

```java
    @Test
    void queryNodes_filtersToMentionedNodesOnly() throws Exception {
        final UUID mentioned = service.upsertNode( "QNMentioned", "kind", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();
        service.upsertNode( "QNUnmentioned", "kind", null,
            Provenance.HUMAN_AUTHORED, Map.of() );

        final UUID chunkId = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + chunkId + "', 'P', 0, ARRAY['H'], 'x', 1, 1, 'h2')" );
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunkId + "', '" + mentioned + "', 0.9, 't', NOW())" );
        }

        final MentionIndex idx = new MentionIndex( dataSource );
        final QueryNodesTool tool = new QueryNodesTool( service, idx );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "filters", Map.of( "node_type", "kind" ),
            "limit", 50 ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();

        assertTrue( text.contains( "QNMentioned" ) );
        assertFalse( text.contains( "QNUnmentioned" ) );
    }
```

- [ ] **Step 2: Run the test**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=KnowledgeMcpToolsTest#queryNodes_filtersToMentionedNodesOnly
```

Expected: compile error on 2-arg `QueryNodesTool` constructor.

- [ ] **Step 3: Modify `QueryNodesTool`**

Open `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/QueryNodesTool.java`. Apply the same pattern as `SearchKnowledgeTool`:

```java
    private final KnowledgeGraphService service;
    private final MentionIndex mentionIndex;

    public QueryNodesTool( final KnowledgeGraphService service ) {
        this( service, null );
    }

    public QueryNodesTool( final KnowledgeGraphService service,
                            final MentionIndex mentionIndex ) {
        this.service = service;
        this.mentionIndex = mentionIndex;
    }
```

After obtaining `final List< KgNode > nodes = service.queryNodes( ... );` in `execute()`, post-filter:

```java
            final List< KgNode > nodes = service.queryNodes( filters, provenanceFilter, limit, offset );
            final List< KgNode > filtered;
            if ( mentionIndex == null ) {
                filtered = nodes;
            } else {
                filtered = new ArrayList<>( nodes.size() );
                for ( final KgNode n : nodes ) {
                    if ( mentionIndex.isMentioned( n.id() ) ) filtered.add( n );
                }
            }
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, Map.of( "results", filtered ) );
```

Add imports:
```java
import com.wikantik.knowledge.MentionIndex;
import java.util.ArrayList;
```

Update the tool description to match `SearchKnowledgeTool` — note the mention-filter.

- [ ] **Step 4: Run tests**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=KnowledgeMcpToolsTest
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/QueryNodesTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java
git commit -m "feat(knowledge-mcp): filter query_nodes to mention-covered nodes"
```

---

## Task 5: Reback `TraverseTool` to use co-mention traversal

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/TraverseTool.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java`

- [ ] **Step 1: Write failing test**

Append to `KnowledgeMcpToolsTest.java`:

```java
    @Test
    void traverse_walksCoMentionGraph() throws Exception {
        final UUID alpha = service.upsertNode( "TrvAlpha", "t", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID beta = service.upsertNode( "TrvBeta", "t", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID chunk = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + chunk + "', 'P', 0, ARRAY['H'], 'x', 1, 1, 'ht')" );
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunk + "', '" + alpha + "', 0.9, 't', NOW()), "
              + "('" + chunk + "', '" + beta  + "', 0.9, 't', NOW())" );
        }

        final TraverseTool tool = new TraverseTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "start_node", "TrvAlpha",
            "max_depth", 1 ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "TrvBeta" ) );
        assertTrue( text.contains( "co-mentions" ) );
    }
```

Note: this test relies on the `service` fixture already having `MentionIndex` wired (from Task 2's factory update and the test's `@BeforeEach`).

- [ ] **Step 2: Run the test**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=KnowledgeMcpToolsTest#traverse_walksCoMentionGraph
```

Expected: fails because `TraverseTool` still calls the old `service.traverse(...)` which uses `kg_edges` and won't find a co-mention path.

- [ ] **Step 3: Modify `TraverseTool.execute`**

Open `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/TraverseTool.java`. Replace the call to `service.traverse(...)` with `service.traverseByCoMention(...)`. Arguments become simpler — no direction, no relationshipTypes, no provenance filter (since co-mention is the only relationship type and the extractor provenance is implicit):

```java
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String startNode = McpToolUtils.getString( arguments, "start_node" );
            final int maxDepth = McpToolUtils.getInt( arguments, "max_depth", 2 );
            final int minSharedChunks = McpToolUtils.getInt( arguments, "min_shared_chunks", 1 );

            final TraversalResult result = service.traverseByCoMention(
                startNode, maxDepth, minSharedChunks );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, result );
        } catch ( final Exception e ) {
            LOG.error( "Traverse failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
```

Update the `definition()` input schema to match — drop `direction`, `relationship_types`, `provenance_filter`; add `min_shared_chunks` (optional, default 1). Update the tool description to say "walks the co-mention graph."

Required args remain just `start_node`.

- [ ] **Step 4: Run tests**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=KnowledgeMcpToolsTest
```

Expected: all pass including the new co-mention traversal test.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/TraverseTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java
git commit -m "feat(knowledge-mcp): traverse tool walks co-mention graph"
```

---

## Task 6: Reback `DiscoverSchemaTool` to report mention-covered counts

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/DiscoverSchemaTool.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java`

- [ ] **Step 1: Write failing test**

Append to `KnowledgeMcpToolsTest.java`:

```java
    @Test
    void discoverSchema_reportsMentionCoverageStats() throws Exception {
        final UUID mentioned = service.upsertNode( "SchAlpha", "kind", null,
            Provenance.HUMAN_AUTHORED, Map.of() ).id();
        service.upsertNode( "SchBeta", "kind", null,
            Provenance.HUMAN_AUTHORED, Map.of() );

        final UUID chunk = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + chunk + "', 'P', 0, ARRAY['H'], 'x', 1, 1, 'hs')" );
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunk + "', '" + mentioned + "', 0.9, 't', NOW())" );
        }

        final MentionIndex idx = new MentionIndex( dataSource );
        final DiscoverSchemaTool tool = new DiscoverSchemaTool( service, idx );
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "mentionedNodeCount" ) );
        assertTrue( text.contains( "\"mentionedNodeCount\":1" ) );
    }
```

- [ ] **Step 2: Run the test**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=KnowledgeMcpToolsTest#discoverSchema_reportsMentionCoverageStats
```

Expected: compile error on 2-arg `DiscoverSchemaTool` constructor.

- [ ] **Step 3: Modify `DiscoverSchemaTool`**

Open `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/DiscoverSchemaTool.java`. Add optional `MentionIndex` constructor param. In `execute`, if the index is present, enrich the JSON result with the mention-coverage count:

```java
    private final KnowledgeGraphService service;
    private final MentionIndex mentionIndex;

    public DiscoverSchemaTool( final KnowledgeGraphService service ) {
        this( service, null );
    }

    public DiscoverSchemaTool( final KnowledgeGraphService service,
                                final MentionIndex mentionIndex ) {
        this.service = service;
        this.mentionIndex = mentionIndex;
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final SchemaDescription schema = service.discoverSchema();
            if ( mentionIndex == null ) {
                return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, schema );
            }
            // Wrap the schema with the mention-coverage stat.
            final Map< String, Object > payload = new LinkedHashMap<>();
            payload.put( "schema", schema );
            payload.put( "mentionedNodeCount", mentionIndex.getMentionedIds().size() );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, payload );
        } catch ( final Exception e ) {
            LOG.error( "Discover schema failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
```

Add imports:
```java
import com.wikantik.knowledge.MentionIndex;
import java.util.LinkedHashMap;
```

- [ ] **Step 4: Run tests**

```bash
mvn -pl wikantik-knowledge -am -q test -Dtest=KnowledgeMcpToolsTest
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/DiscoverSchemaTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java
git commit -m "feat(knowledge-mcp): discover_schema reports mention-coverage stats"
```

---

## Task 7: Wire `MentionIndex` into `KnowledgeMcpInitializer`

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`

- [ ] **Step 1: Inspect current initializer**

```bash
sed -n '90,120p' wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java
```

Note where the 6 KG tools are instantiated (around the `if ( kgService != null )` block from cycle 2).

- [ ] **Step 2: Construct `MentionIndex` and pass it in**

The `MentionIndex` needs a `DataSource`. The engine exposes it via `engine.getManager(DataSource.class)` — confirm by grepping:

```bash
grep -n "getManager( DataSource\|Wiki\.datasource\|DataSource.class" wikantik-main/src/main/java/com/wikantik --include="*.java" -r | head -5
```

(Most likely it's accessible via `com.wikantik.api.spi.Wiki.datasource()...` — check existing sources like `NodeMentionSimilarity`'s wiring for the pattern.)

Then in `KnowledgeMcpInitializer.contextInitialized`:

```java
            final MentionIndex mentionIndex = ( kgService != null )
                ? new MentionIndex( /* DataSource — wire from engine */ )
                : null;

            if ( kgService != null ) {
                tools.add( new DiscoverSchemaTool( kgService, mentionIndex ) );
                tools.add( new QueryNodesTool( kgService, mentionIndex ) );
                tools.add( new GetNodeTool( kgService ) );  // unchanged, single-2-arg construct
                tools.add( new TraverseTool( kgService ) );  // uses service.traverseByCoMention internally
                tools.add( new SearchKnowledgeTool( kgService, mentionIndex ) );
                // ... find_similar unchanged
            }
```

Add the import:
```java
import com.wikantik.knowledge.MentionIndex;
```

If the DataSource is not trivially accessible from the initializer, add a `DataSource` manager lookup. Consult `ContextRetrievalServiceInitializer.java` (cycle 1) or `NodeMentionSimilarity` wiring for how the existing code obtains a `DataSource`.

- [ ] **Step 3: Verify compile + full module tests**

```bash
mvn -pl wikantik-knowledge -am -q test
```

Expected: all tests pass (old KG tool tests + new mention-filter tests).

- [ ] **Step 4: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java
git commit -m "feat(knowledge-mcp): wire MentionIndex into rebacked KG tools"
```

---

## Task 8: Full build + close cycle

**Files:** none created

- [ ] **Step 1: Full multi-module build**

```bash
cd /home/jakefear/source/jspwiki && mvn clean install -T 1C -DskipITs 2>&1 | grep -E "BUILD|ERROR" | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Mark cycle 3 complete in the spec**

Edit `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`, change the cycle 3 heading to `**Cycle 3 — KG tools rebacked onto mention graph. ✓**`.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md
git commit -m "chore(spec): mark cycle 3 complete — KG tools rebacked onto mention graph"
```

---

## Summary

At cycle 3 complete:

- New `MentionIndex` utility (read-only) over `chunk_entity_mentions`.
- `KnowledgeGraphService.traverseByCoMention` implemented in `DefaultKnowledgeGraphService`.
- 4 rebacked KG MCP tools (`search_knowledge`, `query_nodes`, `traverse`, `discover_schema`) — hide unmentioned nodes, walk co-mention graph, report mention-coverage stats.
- 2 unchanged (`get_node`, `find_similar`) — `find_similar` is already mention-based.
- Admin UI + REST continue to see the full graph via the unchanged `KnowledgeGraphService.traverse` / `queryNodes` / etc.
- Tests: ~4 new tests in `MentionIndexTest`, ~4 new tests in `KnowledgeMcpToolsTest` / `DefaultKnowledgeGraphServiceTest`.

Deferred to cycle 6:
- Stop `GraphProjector` from writing further legacy rows.
- Purge existing legacy-only rows from `kg_nodes` / `kg_edges`.
- Delete `GraphProjector.java`.
