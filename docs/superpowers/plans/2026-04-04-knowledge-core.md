# Knowledge Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-describing knowledge graph backed by PostgreSQL, a dedicated read-only consumption MCP endpoint, a proposal/review system for AI-assisted enrichment, and a knowledge administration UI — enabling coding agents to query authoritative domain knowledge.

**Architecture:** The knowledge graph stores entities and relationships extracted from wiki page frontmatter in PostgreSQL tables with JSONB properties for schema-free metadata. A graph projector (WikiEvent listener) synchronizes frontmatter to the graph on page saves. A new `wikantik-knowledge` module provides a separate MCP endpoint with 5 read-only tools for agent consumption. External agents propose enrichments through the authoring MCP; humans review and approve via an admin UI, with approved knowledge written back to page frontmatter.

**Tech Stack:** Java 21, PostgreSQL 15+ (JSONB), H2 (unit tests), MCP SDK (io.modelcontextprotocol), JUnit 5, React, Gson

**Spec:** `docs/superpowers/specs/2026-04-04-knowledge-core-design.md`

---

## File Structure

### New Files

**Database:**
- `wikantik-war/src/main/config/db/postgresql-knowledge.ddl` — PostgreSQL schema for knowledge tables
- `wikantik-main/src/test/resources/knowledge-h2.sql` — H2-compatible schema for unit tests

**API interfaces (wikantik-api):**
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgNode.java` — Node model
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgEdge.java` — Edge model
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposal.java` — Proposal model
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgRejection.java` — Rejection model
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java` — Service interface

**Implementation (wikantik-main):**
- `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java` — Service implementation
- `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java` — JDBC data access
- `wikantik-main/src/main/java/com/wikantik/knowledge/GraphProjector.java` — Frontmatter-to-graph event listener
- `wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterRelationshipDetector.java` — Convention-based relationship detection

**Tests (wikantik-main):**
- `wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryTest.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/GraphProjectorTest.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterRelationshipDetectorTest.java`

**Consumption MCP (wikantik-knowledge — new module):**
- `wikantik-knowledge/pom.xml`
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/DiscoverSchemaTool.java`
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/QueryNodesTool.java`
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/TraverseTool.java`
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetNodeTool.java`
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java`
- `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/DiscoverSchemaToolTest.java`
- `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/QueryNodesToolTest.java`
- `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/TraverseToolTest.java`
- `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetNodeToolTest.java`
- `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/SearchKnowledgeToolTest.java`

**Proposal tools (wikantik-mcp — existing module, 3 new tools):**
- `wikantik-mcp/src/main/java/com/wikantik/mcp/tools/ProposeKnowledgeTool.java`
- `wikantik-mcp/src/main/java/com/wikantik/mcp/tools/ListRejectionsTool.java`
- `wikantik-mcp/src/main/java/com/wikantik/mcp/tools/ListProposalsTool.java`

**Admin REST (wikantik-rest):**
- `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`

**Admin UI (wikantik-frontend):**
- `wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx`
- `wikantik-frontend/src/components/admin/ProposalReviewQueue.jsx`
- `wikantik-frontend/src/components/admin/GraphExplorer.jsx`
- `wikantik-frontend/src/components/admin/NodeDetail.jsx`
- `wikantik-frontend/src/components/admin/ManualCurationModal.jsx`

### Modified Files

- `pom.xml` — add `wikantik-knowledge` module
- `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` — register KnowledgeGraphService in Phase 9
- `wikantik-war/src/main/config/tomcat/Wikantik-context.xml.template` — add `jdbc/KnowledgeDatabase` JNDI resource
- `wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template` — add knowledge datasource property
- `wikantik-war/src/main/webapp/WEB-INF/web.xml` — add knowledge MCP servlet, admin knowledge servlet mapping
- `wikantik-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java` — register 3 proposal tools
- `wikantik-frontend/src/main.jsx` — add knowledge admin route
- `wikantik-frontend/src/components/admin/AdminLayout.jsx` — add Knowledge nav link
- `wikantik-frontend/src/components/Sidebar.jsx` — add Knowledge admin link
- `wikantik-frontend/src/api/client.js` — add knowledge API methods

---

## Task 1: Database Schema

**Files:**
- Create: `wikantik-war/src/main/config/db/postgresql-knowledge.ddl`
- Create: `wikantik-main/src/test/resources/knowledge-h2.sql`

- [ ] **Step 1: Create the PostgreSQL DDL**

```sql
-- wikantik-war/src/main/config/db/postgresql-knowledge.ddl
--
-- Knowledge Graph tables for Wikantik
-- Run after postgresql.ddl: sudo -u postgres psql -d wikantik -f postgresql-knowledge.ddl

-- Nodes: entities in the knowledge graph
CREATE TABLE IF NOT EXISTS kg_nodes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL UNIQUE,
    node_type       VARCHAR(100),
    source_page     VARCHAR(255),
    provenance      VARCHAR(50) NOT NULL DEFAULT 'human-authored',
    properties      JSONB DEFAULT '{}'::jsonb,
    created         TIMESTAMP NOT NULL DEFAULT NOW(),
    modified        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kg_nodes_type ON kg_nodes(node_type);
CREATE INDEX IF NOT EXISTS idx_kg_nodes_source_page ON kg_nodes(source_page);
CREATE INDEX IF NOT EXISTS idx_kg_nodes_provenance ON kg_nodes(provenance);
CREATE INDEX IF NOT EXISTS idx_kg_nodes_properties ON kg_nodes USING GIN (properties);

-- Edges: typed relationships between nodes
CREATE TABLE IF NOT EXISTS kg_edges (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id         UUID NOT NULL REFERENCES kg_nodes(id) ON DELETE CASCADE,
    target_id         UUID NOT NULL REFERENCES kg_nodes(id) ON DELETE CASCADE,
    relationship_type VARCHAR(100) NOT NULL,
    provenance        VARCHAR(50) NOT NULL DEFAULT 'human-authored',
    properties        JSONB DEFAULT '{}'::jsonb,
    created           TIMESTAMP NOT NULL DEFAULT NOW(),
    modified          TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(source_id, target_id, relationship_type)
);

CREATE INDEX IF NOT EXISTS idx_kg_edges_source ON kg_edges(source_id);
CREATE INDEX IF NOT EXISTS idx_kg_edges_target ON kg_edges(target_id);
CREATE INDEX IF NOT EXISTS idx_kg_edges_type ON kg_edges(relationship_type);
CREATE INDEX IF NOT EXISTS idx_kg_edges_provenance ON kg_edges(provenance);

-- Proposals: staged knowledge additions from external agents
CREATE TABLE IF NOT EXISTS kg_proposals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_type   VARCHAR(50) NOT NULL,
    source_page     VARCHAR(255),
    proposed_data   JSONB NOT NULL,
    confidence      DOUBLE PRECISION DEFAULT 0.0,
    reasoning       TEXT,
    status          VARCHAR(50) NOT NULL DEFAULT 'pending',
    reviewed_by     VARCHAR(100),
    created         TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_at     TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kg_proposals_status ON kg_proposals(status);
CREATE INDEX IF NOT EXISTS idx_kg_proposals_source_page ON kg_proposals(source_page);

-- Rejections: negative knowledge to prevent re-proposals
CREATE TABLE IF NOT EXISTS kg_rejections (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposed_source       VARCHAR(255) NOT NULL,
    proposed_target       VARCHAR(255) NOT NULL,
    proposed_relationship VARCHAR(100) NOT NULL,
    rejected_by           VARCHAR(100),
    reason                TEXT,
    created               TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(proposed_source, proposed_target, proposed_relationship)
);

-- Grant permissions to wikantik application user
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_nodes TO wikantik;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_edges TO wikantik;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_proposals TO wikantik;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_rejections TO wikantik;
```

- [ ] **Step 2: Create the H2-compatible schema for unit tests**

H2 does not support JSONB or `gen_random_uuid()`. Use TEXT for JSON columns and UUID auto-generation.

```sql
-- wikantik-main/src/test/resources/knowledge-h2.sql

CREATE TABLE IF NOT EXISTS kg_nodes (
    id              UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    node_type       VARCHAR(100),
    source_page     VARCHAR(255),
    provenance      VARCHAR(50) NOT NULL DEFAULT 'human-authored',
    properties      TEXT DEFAULT '{}',
    created         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS kg_edges (
    id                UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    source_id         UUID NOT NULL,
    target_id         UUID NOT NULL,
    relationship_type VARCHAR(100) NOT NULL,
    provenance        VARCHAR(50) NOT NULL DEFAULT 'human-authored',
    properties        TEXT DEFAULT '{}',
    created           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source_id, target_id, relationship_type),
    FOREIGN KEY (source_id) REFERENCES kg_nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (target_id) REFERENCES kg_nodes(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS kg_proposals (
    id              UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    proposal_type   VARCHAR(50) NOT NULL,
    source_page     VARCHAR(255),
    proposed_data   TEXT NOT NULL,
    confidence      DOUBLE PRECISION DEFAULT 0.0,
    reasoning       TEXT,
    status          VARCHAR(50) NOT NULL DEFAULT 'pending',
    reviewed_by     VARCHAR(100),
    created         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at     TIMESTAMP
);

CREATE TABLE IF NOT EXISTS kg_rejections (
    id                    UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    proposed_source       VARCHAR(255) NOT NULL,
    proposed_target       VARCHAR(255) NOT NULL,
    proposed_relationship VARCHAR(100) NOT NULL,
    rejected_by           VARCHAR(100),
    reason                TEXT,
    created               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(proposed_source, proposed_target, proposed_relationship)
);
```

- [ ] **Step 3: Commit**

```bash
git add wikantik-war/src/main/config/db/postgresql-knowledge.ddl wikantik-main/src/test/resources/knowledge-h2.sql
git commit -m "feat(knowledge): add PostgreSQL and H2 DDL for knowledge graph tables"
```

---

## Task 2: Data Model Classes

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgNode.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgEdge.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposal.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgRejection.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/Provenance.java`

- [ ] **Step 1: Create Provenance enum**

```java
// wikantik-api/src/main/java/com/wikantik/api/knowledge/Provenance.java
package com.wikantik.api.knowledge;

public enum Provenance {
    HUMAN_AUTHORED( "human-authored" ),
    AI_INFERRED( "ai-inferred" ),
    AI_REVIEWED( "ai-reviewed" );

    private final String value;

    Provenance( final String value ) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Provenance fromValue( final String value ) {
        for ( final Provenance p : values() ) {
            if ( p.value.equals( value ) ) {
                return p;
            }
        }
        throw new IllegalArgumentException( "Unknown provenance: " + value );
    }
}
```

- [ ] **Step 2: Create KgNode record**

```java
// wikantik-api/src/main/java/com/wikantik/api/knowledge/KgNode.java
package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KgNode(
    UUID id,
    String name,
    String nodeType,
    String sourcePage,
    Provenance provenance,
    Map< String, Object > properties,
    Instant created,
    Instant modified
) {
    /** Returns true if this node is a stub (referenced but has no wiki page yet). */
    public boolean isStub() {
        return sourcePage == null;
    }
}
```

- [ ] **Step 3: Create KgEdge record**

```java
// wikantik-api/src/main/java/com/wikantik/api/knowledge/KgEdge.java
package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KgEdge(
    UUID id,
    UUID sourceId,
    UUID targetId,
    String relationshipType,
    Provenance provenance,
    Map< String, Object > properties,
    Instant created,
    Instant modified
) {}
```

- [ ] **Step 4: Create KgProposal record**

```java
// wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposal.java
package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KgProposal(
    UUID id,
    String proposalType,
    String sourcePage,
    Map< String, Object > proposedData,
    double confidence,
    String reasoning,
    String status,
    String reviewedBy,
    Instant created,
    Instant reviewedAt
) {}
```

- [ ] **Step 5: Create KgRejection record**

```java
// wikantik-api/src/main/java/com/wikantik/api/knowledge/KgRejection.java
package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.UUID;

public record KgRejection(
    UUID id,
    String proposedSource,
    String proposedTarget,
    String proposedRelationship,
    String rejectedBy,
    String reason,
    Instant created
) {}
```

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/
git commit -m "feat(knowledge): add data model records for knowledge graph"
```

---

## Task 3: KnowledgeGraphService Interface

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/SchemaDescription.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/TraversalResult.java`

- [ ] **Step 1: Create SchemaDescription record**

```java
// wikantik-api/src/main/java/com/wikantik/api/knowledge/SchemaDescription.java
package com.wikantik.api.knowledge;

import java.util.List;
import java.util.Map;

public record SchemaDescription(
    List< String > nodeTypes,
    List< String > relationshipTypes,
    Map< String, PropertyInfo > propertyKeys,
    Stats stats
) {
    public record PropertyInfo( long count, List< String > sampleValues ) {}
    public record Stats( long nodes, long edges, long unreviewedProposals ) {}
}
```

- [ ] **Step 2: Create TraversalResult record**

```java
// wikantik-api/src/main/java/com/wikantik/api/knowledge/TraversalResult.java
package com.wikantik.api.knowledge;

import java.util.List;

public record TraversalResult(
    List< KgNode > nodes,
    List< KgEdge > edges
) {}
```

- [ ] **Step 3: Create KnowledgeGraphService interface**

```java
// wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java
package com.wikantik.api.knowledge;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service interface for the knowledge graph. Provides schema discovery,
 * node/edge CRUD, graph traversal, and proposal management.
 * Shared by the consumption MCP, admin REST endpoints, and admin UI.
 */
public interface KnowledgeGraphService {

    // --- Schema discovery ---

    SchemaDescription discoverSchema();

    // --- Node operations ---

    KgNode getNode( UUID id );

    KgNode getNodeByName( String name );

    KgNode upsertNode( String name, String nodeType, String sourcePage,
                       Provenance provenance, Map< String, Object > properties );

    void deleteNode( UUID id );

    /** Merge duplicate nodes: moves all edges from sourceId to targetId, then deletes sourceId. */
    void mergeNodes( UUID sourceId, UUID targetId );

    List< KgNode > queryNodes( Map< String, Object > filters, Set< Provenance > provenanceFilter,
                               int limit, int offset );

    // --- Edge operations ---

    KgEdge upsertEdge( UUID sourceId, UUID targetId, String relationshipType,
                       Provenance provenance, Map< String, Object > properties );

    void deleteEdge( UUID id );

    /** Remove all human-authored edges from sourceId that are NOT in the given set of (targetName, relationshipType). */
    void diffAndRemoveStaleEdges( UUID sourceId, Set< Map.Entry< String, String > > currentEdges );

    List< KgEdge > getEdgesForNode( UUID nodeId, String direction );

    // --- Traversal ---

    TraversalResult traverse( String startNodeName, String direction,
                              Set< String > relationshipTypes, int maxDepth,
                              Set< Provenance > provenanceFilter );

    // --- Search ---

    List< KgNode > searchKnowledge( String query, Set< Provenance > provenanceFilter, int limit );

    // --- Proposal management ---

    KgProposal submitProposal( String proposalType, String sourcePage,
                               Map< String, Object > proposedData,
                               double confidence, String reasoning );

    List< KgProposal > listProposals( String status, String sourcePage, int limit, int offset );

    KgProposal approveProposal( UUID proposalId, String reviewedBy );

    KgProposal rejectProposal( UUID proposalId, String reviewedBy, String reason );

    // --- Rejection queries ---

    List< KgRejection > listRejections( String sourceName, String targetName,
                                        String relationshipType );

    boolean isRejected( String sourceName, String targetName, String relationshipType );
}
```

- [ ] **Step 4: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/
git commit -m "feat(knowledge): add KnowledgeGraphService interface and supporting types"
```

---

## Task 4: JDBC Knowledge Repository

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryTest.java`

This is the data access layer. It extends `AbstractJDBCDatabase` to reuse the JNDI DataSource pattern and provides all SQL operations for the knowledge graph tables.

- [ ] **Step 1: Write failing tests for node CRUD**

```java
// wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryTest.java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JdbcKnowledgeRepository using an in-memory H2 database.
 */
class JdbcKnowledgeRepositoryTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository repo;

    @BeforeAll
    static void initDataSource() throws Exception {
        final org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL( "jdbc:h2:mem:knowledge_test;DB_CLOSE_DELAY=-1" );
        dataSource = ds;

        // Create schema
        try ( final Connection conn = ds.getConnection() ) {
            final String ddl = new String(
                JdbcKnowledgeRepositoryTest.class.getResourceAsStream( "/knowledge-h2.sql" ).readAllBytes() );
            conn.createStatement().execute( ddl );
        }
    }

    @BeforeEach
    void setUp() {
        repo = new JdbcKnowledgeRepository( dataSource );
    }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void upsertNode_createsNewNode() {
        final KgNode node = repo.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );

        assertNotNull( node.id() );
        assertEquals( "Order", node.name() );
        assertEquals( "domain-model", node.nodeType() );
        assertEquals( "Order.md", node.sourcePage() );
        assertEquals( Provenance.HUMAN_AUTHORED, node.provenance() );
        assertEquals( "billing", node.properties().get( "domain" ) );
    }

    @Test
    void upsertNode_updatesExistingNode() {
        repo.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        final KgNode updated = repo.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "shipping" ) );

        assertEquals( "shipping", updated.properties().get( "domain" ) );
    }

    @Test
    void getNodeByName_returnsNode() {
        repo.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of() );

        final KgNode found = repo.getNodeByName( "Order" );
        assertNotNull( found );
        assertEquals( "Order", found.name() );
    }

    @Test
    void getNodeByName_returnsNullForMissing() {
        assertNull( repo.getNodeByName( "NonExistent" ) );
    }

    @Test
    void deleteNode_removesNode() {
        final KgNode node = repo.upsertNode( "ToDelete", "test", null,
                Provenance.HUMAN_AUTHORED, Map.of() );
        repo.deleteNode( node.id() );
        assertNull( repo.getNodeByName( "ToDelete" ) );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd wikantik-main && mvn test -Dtest=JdbcKnowledgeRepositoryTest -pl . 2>&1 | tail -5`
Expected: Compilation error — `JdbcKnowledgeRepository` does not exist yet.

- [ ] **Step 3: Implement JdbcKnowledgeRepository — node operations**

```java
// wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java
package com.wikantik.knowledge;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.wikantik.api.knowledge.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * JDBC-backed data access for the knowledge graph tables.
 * Works with both PostgreSQL (production, JSONB columns) and H2 (tests, TEXT columns).
 */
public class JdbcKnowledgeRepository {

    private static final Logger LOG = LogManager.getLogger( JdbcKnowledgeRepository.class );
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() {}.getType();

    private final DataSource dataSource;

    public JdbcKnowledgeRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    // --- Node operations ---

    public KgNode upsertNode( final String name, final String nodeType, final String sourcePage,
                              final Provenance provenance, final Map< String, Object > properties ) {
        final String propsJson = GSON.toJson( properties != null ? properties : Map.of() );
        final String sql = """
            MERGE INTO kg_nodes (name, node_type, source_page, provenance, properties, modified)
            KEY (name)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, name );
            ps.setString( 2, nodeType );
            ps.setString( 3, sourcePage );
            ps.setString( 4, provenance.value() );
            ps.setString( 5, propsJson );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.error( "Failed to upsert node '{}': {}", name, e.getMessage() );
            throw new RuntimeException( e );
        }
        return getNodeByName( name );
    }

    public KgNode getNode( final UUID id ) {
        final String sql = "SELECT * FROM kg_nodes WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapNode( rs ) : null;
            }
        } catch ( final SQLException e ) {
            LOG.error( "Failed to get node {}: {}", id, e.getMessage() );
            throw new RuntimeException( e );
        }
    }

    public KgNode getNodeByName( final String name ) {
        final String sql = "SELECT * FROM kg_nodes WHERE name = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, name );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapNode( rs ) : null;
            }
        } catch ( final SQLException e ) {
            LOG.error( "Failed to get node '{}': {}", name, e.getMessage() );
            throw new RuntimeException( e );
        }
    }

    public void deleteNode( final UUID id ) {
        final String sql = "DELETE FROM kg_nodes WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.error( "Failed to delete node {}: {}", id, e.getMessage() );
            throw new RuntimeException( e );
        }
    }

    public List< KgNode > queryNodes( final Map< String, Object > filters,
                                       final Set< Provenance > provenanceFilter,
                                       final int limit, final int offset ) {
        final StringBuilder sql = new StringBuilder( "SELECT * FROM kg_nodes WHERE 1=1" );
        final List< Object > params = new ArrayList<>();

        if ( filters != null ) {
            if ( filters.containsKey( "node_type" ) ) {
                sql.append( " AND node_type = ?" );
                params.add( filters.get( "node_type" ) );
            }
            if ( filters.containsKey( "source_page" ) ) {
                sql.append( " AND source_page = ?" );
                params.add( filters.get( "source_page" ) );
            }
            if ( filters.containsKey( "name" ) ) {
                sql.append( " AND name LIKE ?" );
                params.add( "%" + filters.get( "name" ) + "%" );
            }
        }

        if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            final String placeholders = String.join( ", ",
                    provenanceFilter.stream().map( p -> "?" ).toArray( String[]::new ) );
            sql.append( " AND provenance IN (" ).append( placeholders ).append( ")" );
            provenanceFilter.forEach( p -> params.add( p.value() ) );
        }

        sql.append( " ORDER BY name LIMIT ? OFFSET ?" );
        params.add( limit );
        params.add( offset );

        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            final List< KgNode > results = new ArrayList<>();
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    results.add( mapNode( rs ) );
                }
            }
            return results;
        } catch ( final SQLException e ) {
            LOG.error( "Failed to query nodes: {}", e.getMessage() );
            throw new RuntimeException( e );
        }
    }

    public List< KgNode > searchNodes( final String query, final Set< Provenance > provenanceFilter,
                                        final int limit ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT * FROM kg_nodes WHERE (LOWER(name) LIKE ? OR LOWER(CAST(properties AS VARCHAR)) LIKE ?)" );
        final List< Object > params = new ArrayList<>();
        final String pattern = "%" + query.toLowerCase() + "%";
        params.add( pattern );
        params.add( pattern );

        if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            final String placeholders = String.join( ", ",
                    provenanceFilter.stream().map( p -> "?" ).toArray( String[]::new ) );
            sql.append( " AND provenance IN (" ).append( placeholders ).append( ")" );
            provenanceFilter.forEach( p -> params.add( p.value() ) );
        }

        sql.append( " ORDER BY name LIMIT ?" );
        params.add( limit );

        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            final List< KgNode > results = new ArrayList<>();
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    results.add( mapNode( rs ) );
                }
            }
            return results;
        } catch ( final SQLException e ) {
            LOG.error( "Failed to search nodes: {}", e.getMessage() );
            throw new RuntimeException( e );
        }
    }

    // --- Edge operations ---

    public KgEdge upsertEdge( final UUID sourceId, final UUID targetId,
                              final String relationshipType, final Provenance provenance,
                              final Map< String, Object > properties ) {
        final String propsJson = GSON.toJson( properties != null ? properties : Map.of() );
        final String sql = """
            MERGE INTO kg_edges (source_id, target_id, relationship_type, provenance, properties, modified)
            KEY (source_id, target_id, relationship_type)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, sourceId );
            ps.setObject( 2, targetId );
            ps.setString( 3, relationshipType );
            ps.setString( 4, provenance.value() );
            ps.setString( 5, propsJson );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.error( "Failed to upsert edge {}->{} ({}): {}", sourceId, targetId,
                    relationshipType, e.getMessage() );
            throw new RuntimeException( e );
        }

        // Retrieve the upserted edge
        final String selectSql = "SELECT * FROM kg_edges WHERE source_id = ? AND target_id = ? AND relationship_type = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( selectSql ) ) {
            ps.setObject( 1, sourceId );
            ps.setObject( 2, targetId );
            ps.setString( 3, relationshipType );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapEdge( rs ) : null;
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( e );
        }
    }

    public void deleteEdge( final UUID id ) {
        final String sql = "DELETE FROM kg_edges WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.error( "Failed to delete edge {}: {}", id, e.getMessage() );
            throw new RuntimeException( e );
        }
    }

    public List< KgEdge > getEdgesForNode( final UUID nodeId, final String direction ) {
        final String sql = switch ( direction ) {
            case "outbound" -> "SELECT * FROM kg_edges WHERE source_id = ?";
            case "inbound" -> "SELECT * FROM kg_edges WHERE target_id = ?";
            default -> "SELECT * FROM kg_edges WHERE source_id = ? OR target_id = ?";
        };
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, nodeId );
            if ( "both".equals( direction ) ) {
                ps.setObject( 2, nodeId );
            }
            final List< KgEdge > results = new ArrayList<>();
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    results.add( mapEdge( rs ) );
                }
            }
            return results;
        } catch ( final SQLException e ) {
            LOG.error( "Failed to get edges for node {}: {}", nodeId, e.getMessage() );
            throw new RuntimeException( e );
        }
    }

    /** Removes human-authored outbound edges from sourceId that are not in currentEdges. */
    public void diffAndRemoveStaleEdges( final UUID sourceId,
                                         final Set< Map.Entry< String, String > > currentEdges ) {
        final List< KgEdge > existing = getEdgesForNode( sourceId, "outbound" );
        for ( final KgEdge edge : existing ) {
            if ( edge.provenance() != Provenance.HUMAN_AUTHORED ) {
                continue; // Only diff human-authored edges
            }
            final KgNode target = getNode( edge.targetId() );
            if ( target == null ) {
                continue;
            }
            final var key = Map.entry( target.name(), edge.relationshipType() );
            if ( !currentEdges.contains( key ) ) {
                deleteEdge( edge.id() );
            }
        }
    }

    // --- Schema introspection ---

    public List< String > getDistinctNodeTypes() {
        return queryDistinct( "SELECT DISTINCT node_type FROM kg_nodes WHERE node_type IS NOT NULL ORDER BY node_type" );
    }

    public List< String > getDistinctRelationshipTypes() {
        return queryDistinct( "SELECT DISTINCT relationship_type FROM kg_edges ORDER BY relationship_type" );
    }

    public long countNodes() {
        return queryCount( "SELECT COUNT(*) FROM kg_nodes" );
    }

    public long countEdges() {
        return queryCount( "SELECT COUNT(*) FROM kg_edges" );
    }

    public long countPendingProposals() {
        return queryCount( "SELECT COUNT(*) FROM kg_proposals WHERE status = 'pending'" );
    }

    // --- Proposal operations ---

    public KgProposal insertProposal( final String proposalType, final String sourcePage,
                                      final Map< String, Object > proposedData,
                                      final double confidence, final String reasoning ) {
        final String dataJson = GSON.toJson( proposedData );
        final String sql = """
            INSERT INTO kg_proposals (proposal_type, source_page, proposed_data, confidence, reasoning, status)
            VALUES (?, ?, ?, ?, ?, 'pending')
            """;
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql, Statement.RETURN_GENERATED_KEYS ) ) {
            ps.setString( 1, proposalType );
            ps.setString( 2, sourcePage );
            ps.setString( 3, dataJson );
            ps.setDouble( 4, confidence );
            ps.setString( 5, reasoning );
            ps.executeUpdate();
            try ( final ResultSet keys = ps.getGeneratedKeys() ) {
                if ( keys.next() ) {
                    return getProposal( keys.getObject( 1, UUID.class ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.error( "Failed to insert proposal: {}", e.getMessage() );
            throw new RuntimeException( e );
        }
        return null;
    }

    public KgProposal getProposal( final UUID id ) {
        final String sql = "SELECT * FROM kg_proposals WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapProposal( rs ) : null;
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( e );
        }
    }

    public List< KgProposal > listProposals( final String status, final String sourcePage,
                                              final int limit, final int offset ) {
        final StringBuilder sql = new StringBuilder( "SELECT * FROM kg_proposals WHERE 1=1" );
        final List< Object > params = new ArrayList<>();
        if ( status != null ) {
            sql.append( " AND status = ?" );
            params.add( status );
        }
        if ( sourcePage != null ) {
            sql.append( " AND source_page = ?" );
            params.add( sourcePage );
        }
        sql.append( " ORDER BY created DESC LIMIT ? OFFSET ?" );
        params.add( limit );
        params.add( offset );

        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            final List< KgProposal > results = new ArrayList<>();
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    results.add( mapProposal( rs ) );
                }
            }
            return results;
        } catch ( final SQLException e ) {
            throw new RuntimeException( e );
        }
    }

    public void updateProposalStatus( final UUID id, final String status, final String reviewedBy ) {
        final String sql = "UPDATE kg_proposals SET status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, status );
            ps.setString( 2, reviewedBy );
            ps.setObject( 3, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            throw new RuntimeException( e );
        }
    }

    // --- Rejection operations ---

    public void insertRejection( final String source, final String target,
                                 final String relationship, final String rejectedBy,
                                 final String reason ) {
        final String sql = """
            MERGE INTO kg_rejections (proposed_source, proposed_target, proposed_relationship, rejected_by, reason)
            KEY (proposed_source, proposed_target, proposed_relationship)
            VALUES (?, ?, ?, ?, ?)
            """;
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, source );
            ps.setString( 2, target );
            ps.setString( 3, relationship );
            ps.setString( 4, rejectedBy );
            ps.setString( 5, reason );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            throw new RuntimeException( e );
        }
    }

    public boolean isRejected( final String source, final String target, final String relationship ) {
        final String sql = "SELECT COUNT(*) FROM kg_rejections WHERE proposed_source = ? AND proposed_target = ? AND proposed_relationship = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, source );
            ps.setString( 2, target );
            ps.setString( 3, relationship );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() && rs.getLong( 1 ) > 0;
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( e );
        }
    }

    public List< KgRejection > listRejections( final String sourceName, final String targetName,
                                                final String relationshipType ) {
        final StringBuilder sql = new StringBuilder( "SELECT * FROM kg_rejections WHERE 1=1" );
        final List< Object > params = new ArrayList<>();
        if ( sourceName != null ) {
            sql.append( " AND proposed_source = ?" );
            params.add( sourceName );
        }
        if ( targetName != null ) {
            sql.append( " AND proposed_target = ?" );
            params.add( targetName );
        }
        if ( relationshipType != null ) {
            sql.append( " AND proposed_relationship = ?" );
            params.add( relationshipType );
        }
        sql.append( " ORDER BY created DESC" );

        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            final List< KgRejection > results = new ArrayList<>();
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    results.add( mapRejection( rs ) );
                }
            }
            return results;
        } catch ( final SQLException e ) {
            throw new RuntimeException( e );
        }
    }

    // --- Helpers ---

    private KgNode mapNode( final ResultSet rs ) throws SQLException {
        return new KgNode(
            rs.getObject( "id", UUID.class ),
            rs.getString( "name" ),
            rs.getString( "node_type" ),
            rs.getString( "source_page" ),
            Provenance.fromValue( rs.getString( "provenance" ) ),
            parseJson( rs.getString( "properties" ) ),
            toInstant( rs.getTimestamp( "created" ) ),
            toInstant( rs.getTimestamp( "modified" ) )
        );
    }

    private KgEdge mapEdge( final ResultSet rs ) throws SQLException {
        return new KgEdge(
            rs.getObject( "id", UUID.class ),
            rs.getObject( "source_id", UUID.class ),
            rs.getObject( "target_id", UUID.class ),
            rs.getString( "relationship_type" ),
            Provenance.fromValue( rs.getString( "provenance" ) ),
            parseJson( rs.getString( "properties" ) ),
            toInstant( rs.getTimestamp( "created" ) ),
            toInstant( rs.getTimestamp( "modified" ) )
        );
    }

    private KgProposal mapProposal( final ResultSet rs ) throws SQLException {
        return new KgProposal(
            rs.getObject( "id", UUID.class ),
            rs.getString( "proposal_type" ),
            rs.getString( "source_page" ),
            parseJson( rs.getString( "proposed_data" ) ),
            rs.getDouble( "confidence" ),
            rs.getString( "reasoning" ),
            rs.getString( "status" ),
            rs.getString( "reviewed_by" ),
            toInstant( rs.getTimestamp( "created" ) ),
            toInstant( rs.getTimestamp( "reviewed_at" ) )
        );
    }

    private KgRejection mapRejection( final ResultSet rs ) throws SQLException {
        return new KgRejection(
            rs.getObject( "id", UUID.class ),
            rs.getString( "proposed_source" ),
            rs.getString( "proposed_target" ),
            rs.getString( "proposed_relationship" ),
            rs.getString( "rejected_by" ),
            rs.getString( "reason" ),
            toInstant( rs.getTimestamp( "created" ) )
        );
    }

    private Map< String, Object > parseJson( final String json ) {
        if ( json == null || json.isBlank() ) {
            return Map.of();
        }
        return GSON.fromJson( json, MAP_TYPE );
    }

    private Instant toInstant( final Timestamp ts ) {
        return ts != null ? ts.toInstant() : null;
    }

    private List< String > queryDistinct( final String sql ) {
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql );
              final ResultSet rs = ps.executeQuery() ) {
            final List< String > results = new ArrayList<>();
            while ( rs.next() ) {
                results.add( rs.getString( 1 ) );
            }
            return results;
        } catch ( final SQLException e ) {
            throw new RuntimeException( e );
        }
    }

    private long queryCount( final String sql ) {
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql );
              final ResultSet rs = ps.executeQuery() ) {
            return rs.next() ? rs.getLong( 1 ) : 0;
        } catch ( final SQLException e ) {
            throw new RuntimeException( e );
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd wikantik-main && mvn test -Dtest=JdbcKnowledgeRepositoryTest -pl .`
Expected: All 5 tests PASS.

- [ ] **Step 5: Write edge tests and add to test file**

Add to `JdbcKnowledgeRepositoryTest.java`:

```java
@Test
void upsertEdge_createsEdge() {
    final KgNode a = repo.upsertNode( "A", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
    final KgNode b = repo.upsertNode( "B", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );

    final KgEdge edge = repo.upsertEdge( a.id(), b.id(), "depends-on",
            Provenance.HUMAN_AUTHORED, Map.of() );

    assertNotNull( edge );
    assertEquals( "depends-on", edge.relationshipType() );
    assertEquals( a.id(), edge.sourceId() );
    assertEquals( b.id(), edge.targetId() );
}

@Test
void getEdgesForNode_outbound() {
    final KgNode a = repo.upsertNode( "A", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
    final KgNode b = repo.upsertNode( "B", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
    final KgNode c = repo.upsertNode( "C", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
    repo.upsertEdge( a.id(), b.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
    repo.upsertEdge( a.id(), c.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

    final List< KgEdge > edges = repo.getEdgesForNode( a.id(), "outbound" );
    assertEquals( 2, edges.size() );
}

@Test
void diffAndRemoveStaleEdges_removesOldEdges() {
    final KgNode a = repo.upsertNode( "A", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
    final KgNode b = repo.upsertNode( "B", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
    final KgNode c = repo.upsertNode( "C", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
    repo.upsertEdge( a.id(), b.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
    repo.upsertEdge( a.id(), c.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

    // Current frontmatter only has A->B depends-on; A->C related should be removed
    repo.diffAndRemoveStaleEdges( a.id(), Set.of( Map.entry( "B", "depends-on" ) ) );

    final List< KgEdge > remaining = repo.getEdgesForNode( a.id(), "outbound" );
    assertEquals( 1, remaining.size() );
    assertEquals( "depends-on", remaining.get( 0 ).relationshipType() );
}

@Test
void diffAndRemoveStaleEdges_preservesAiInferredEdges() {
    final KgNode a = repo.upsertNode( "A", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
    final KgNode b = repo.upsertNode( "B", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
    repo.upsertEdge( a.id(), b.id(), "inferred-rel", Provenance.AI_INFERRED, Map.of() );

    // Empty current edges — but AI-inferred edges should NOT be removed
    repo.diffAndRemoveStaleEdges( a.id(), Set.of() );

    final List< KgEdge > remaining = repo.getEdgesForNode( a.id(), "outbound" );
    assertEquals( 1, remaining.size() );
}
```

- [ ] **Step 6: Run all repository tests**

Run: `cd wikantik-main && mvn test -Dtest=JdbcKnowledgeRepositoryTest -pl .`
Expected: All 9 tests PASS.

- [ ] **Step 7: Write proposal/rejection tests and add to test file**

Add to `JdbcKnowledgeRepositoryTest.java`:

```java
@Test
void insertProposal_createsPendingProposal() {
    final KgProposal proposal = repo.insertProposal( "new-edge", "Order.md",
            Map.of( "source", "Order", "target", "Customer", "relationship", "depends-on" ),
            0.85, "Line 47 mentions 'the order references the customer'" );

    assertNotNull( proposal );
    assertEquals( "pending", proposal.status() );
    assertEquals( 0.85, proposal.confidence(), 0.001 );
}

@Test
void updateProposalStatus_approvesProposal() {
    final KgProposal proposal = repo.insertProposal( "new-edge", "Order.md",
            Map.of(), 0.9, "test" );
    repo.updateProposalStatus( proposal.id(), "approved", "admin" );
    final KgProposal updated = repo.getProposal( proposal.id() );
    assertEquals( "approved", updated.status() );
    assertEquals( "admin", updated.reviewedBy() );
    assertNotNull( updated.reviewedAt() );
}

@Test
void isRejected_returnsTrueForRejectedRelationship() {
    repo.insertRejection( "Order", "Inventory", "depends-on", "admin", "Not a real dependency" );
    assertTrue( repo.isRejected( "Order", "Inventory", "depends-on" ) );
    assertFalse( repo.isRejected( "Order", "Customer", "depends-on" ) );
}
```

- [ ] **Step 8: Run all repository tests**

Run: `cd wikantik-main && mvn test -Dtest=JdbcKnowledgeRepositoryTest -pl .`
Expected: All 12 tests PASS.

- [ ] **Step 9: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryTest.java
git commit -m "feat(knowledge): add JDBC repository for knowledge graph with full test coverage"
```

---

## Task 5: FrontmatterRelationshipDetector

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterRelationshipDetector.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterRelationshipDetectorTest.java`

This class determines which frontmatter keys represent relationships (producing edges) vs properties (stored in JSONB). Per the design spec, a key is a relationship when its value is a list of strings. Certain keys (`tags`, `keywords`, `status`, `type`, `summary`, `date`, `author`, `cluster`) are always treated as properties.

- [ ] **Step 1: Write failing tests**

```java
// wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterRelationshipDetectorTest.java
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterRelationshipDetectorTest {

    private final FrontmatterRelationshipDetector detector = new FrontmatterRelationshipDetector();

    @Test
    void listOfStrings_isRelationship() {
        final Map< String, Object > fm = Map.of( "depends-on", List.of( "Customer", "Product" ) );
        final var result = detector.detect( fm );
        assertTrue( result.relationships().containsKey( "depends-on" ) );
        assertEquals( List.of( "Customer", "Product" ), result.relationships().get( "depends-on" ) );
        assertTrue( result.properties().isEmpty() );
    }

    @Test
    void scalarString_isProperty() {
        final Map< String, Object > fm = Map.of( "domain", "billing" );
        final var result = detector.detect( fm );
        assertTrue( result.relationships().isEmpty() );
        assertEquals( "billing", result.properties().get( "domain" ) );
    }

    @Test
    void excludedKeys_alwaysProperties() {
        final Map< String, Object > fm = Map.of(
            "tags", List.of( "billing", "auth" ),
            "type", "domain-model",
            "summary", "An order entity"
        );
        final var result = detector.detect( fm );
        assertTrue( result.relationships().isEmpty() );
        assertEquals( 3, result.properties().size() );
    }

    @Test
    void mixedFrontmatter_separatesCorrectly() {
        final Map< String, Object > fm = Map.of(
            "type", "domain-model",
            "domain", "billing",
            "depends-on", List.of( "Customer" ),
            "tags", List.of( "core" ),
            "related", List.of( "PaymentGateway" )
        );
        final var result = detector.detect( fm );
        assertEquals( 2, result.relationships().size() );
        assertTrue( result.relationships().containsKey( "depends-on" ) );
        assertTrue( result.relationships().containsKey( "related" ) );
        assertEquals( 3, result.properties().size() ); // type, domain, tags
    }

    @Test
    void singleStringInRelationshipKey_treatedAsRelationship() {
        // A single string (not a list) for a non-excluded key should be a property
        final Map< String, Object > fm = Map.of( "owner", "TeamAlpha" );
        final var result = detector.detect( fm );
        assertEquals( "TeamAlpha", result.properties().get( "owner" ) );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd wikantik-main && mvn test -Dtest=FrontmatterRelationshipDetectorTest -pl . 2>&1 | tail -5`
Expected: Compilation error — class does not exist.

- [ ] **Step 3: Implement FrontmatterRelationshipDetector**

```java
// wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterRelationshipDetector.java
package com.wikantik.knowledge;

import java.util.*;

/**
 * Determines which frontmatter keys represent relationships (edges in the knowledge graph)
 * versus properties (JSONB node attributes).
 *
 * Convention: A key produces edges when its value is a {@code List<String>} and the key
 * is not in the excluded set. Keys in the excluded set (tags, keywords, type, etc.) are
 * always treated as node properties regardless of value type.
 */
public class FrontmatterRelationshipDetector {

    /** Keys that are always treated as properties, never as relationships. */
    private static final Set< String > PROPERTY_ONLY_KEYS = Set.of(
        "tags", "keywords", "type", "summary", "date", "author", "cluster",
        "status", "title", "description", "category", "language"
    );

    public record DetectionResult(
        /** relationship-key -> list of target node names */
        Map< String, List< String > > relationships,
        /** property-key -> value (scalar or list, stored as-is in JSONB) */
        Map< String, Object > properties
    ) {}

    @SuppressWarnings( "unchecked" )
    public DetectionResult detect( final Map< String, Object > frontmatter ) {
        final Map< String, List< String > > relationships = new LinkedHashMap<>();
        final Map< String, Object > properties = new LinkedHashMap<>();

        for ( final Map.Entry< String, Object > entry : frontmatter.entrySet() ) {
            final String key = entry.getKey();
            final Object value = entry.getValue();

            if ( PROPERTY_ONLY_KEYS.contains( key ) ) {
                properties.put( key, value );
                continue;
            }

            if ( value instanceof List< ? > list && !list.isEmpty()
                    && list.stream().allMatch( String.class::isInstance ) ) {
                relationships.put( key, (List< String >) list );
            } else {
                properties.put( key, value );
            }
        }

        return new DetectionResult(
            Collections.unmodifiableMap( relationships ),
            Collections.unmodifiableMap( properties )
        );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd wikantik-main && mvn test -Dtest=FrontmatterRelationshipDetectorTest -pl .`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterRelationshipDetector.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterRelationshipDetectorTest.java
git commit -m "feat(knowledge): add frontmatter relationship detector with convention-based rules"
```

---

## Task 6: DefaultKnowledgeGraphService

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java`

The service delegates to `JdbcKnowledgeRepository` and adds graph traversal (BFS), schema discovery, and proposal approval logic.

- [ ] **Step 1: Write failing tests for schema discovery and traversal**

```java
// wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultKnowledgeGraphServiceTest {

    private static DataSource dataSource;
    private DefaultKnowledgeGraphService service;

    @BeforeAll
    static void initDataSource() throws Exception {
        final org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL( "jdbc:h2:mem:kg_service_test;DB_CLOSE_DELAY=-1" );
        dataSource = ds;
        try ( final Connection conn = ds.getConnection() ) {
            final String ddl = new String(
                DefaultKnowledgeGraphServiceTest.class.getResourceAsStream( "/knowledge-h2.sql" ).readAllBytes() );
            conn.createStatement().execute( ddl );
        }
    }

    @BeforeEach
    void setUp() {
        service = new DefaultKnowledgeGraphService( new JdbcKnowledgeRepository( dataSource ) );
    }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void discoverSchema_returnsCorrectCounts() {
        service.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        service.upsertNode( "Customer", "domain-model", "Customer.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );

        final SchemaDescription schema = service.discoverSchema();
        assertTrue( schema.nodeTypes().contains( "domain-model" ) );
        assertEquals( 2, schema.stats().nodes() );
    }

    @Test
    void traverse_findsConnectedNodes() {
        final KgNode order = service.upsertNode( "Order", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode customer = service.upsertNode( "Customer", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode address = service.upsertNode( "Address", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( order.id(), customer.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( customer.id(), address.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );

        final TraversalResult result = service.traverse( "Order", "outbound",
                Set.of(), 3, Set.of( Provenance.HUMAN_AUTHORED ) );

        // Should find Order, Customer, Address
        assertEquals( 3, result.nodes().size() );
        assertEquals( 2, result.edges().size() );
    }

    @Test
    void traverse_respectsMaxDepth() {
        final KgNode a = service.upsertNode( "A", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = service.upsertNode( "B", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = service.upsertNode( "C", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "r", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( b.id(), c.id(), "r", Provenance.HUMAN_AUTHORED, Map.of() );

        final TraversalResult result = service.traverse( "A", "outbound", Set.of(), 1, null );
        // Depth 1: A and B only
        assertEquals( 2, result.nodes().size() );
        assertEquals( 1, result.edges().size() );
    }

    @Test
    void traverse_filtersRelationshipTypes() {
        final KgNode a = service.upsertNode( "A", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = service.upsertNode( "B", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = service.upsertNode( "C", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), c.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

        final TraversalResult result = service.traverse( "A", "outbound",
                Set.of( "depends-on" ), 5, null );
        assertEquals( 2, result.nodes().size() ); // A and B only
    }

    @Test
    void submitProposal_rejectedIfPreviouslyRejected() {
        service.upsertNode( "Order", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertNode( "Inventory", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );

        // Reject the relationship
        service.rejectProposal(
            service.submitProposal( "new-edge", "Order.md",
                Map.of( "source", "Order", "target", "Inventory", "relationship", "depends-on" ),
                0.7, "test" ).id(),
            "admin", "Not real" );

        // Second proposal for same relationship should be rejected
        assertNull( service.submitProposal( "new-edge", "Order.md",
            Map.of( "source", "Order", "target", "Inventory", "relationship", "depends-on" ),
            0.8, "test again" ) );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd wikantik-main && mvn test -Dtest=DefaultKnowledgeGraphServiceTest -pl . 2>&1 | tail -5`
Expected: Compilation error — class does not exist.

- [ ] **Step 3: Implement DefaultKnowledgeGraphService**

```java
// wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class DefaultKnowledgeGraphService implements KnowledgeGraphService {

    private static final Logger LOG = LogManager.getLogger( DefaultKnowledgeGraphService.class );
    private static final Set< Provenance > DEFAULT_PROVENANCE =
            Set.of( Provenance.HUMAN_AUTHORED, Provenance.AI_REVIEWED );

    private final JdbcKnowledgeRepository repo;

    public DefaultKnowledgeGraphService( final JdbcKnowledgeRepository repo ) {
        this.repo = repo;
    }

    @Override
    public SchemaDescription discoverSchema() {
        final List< String > nodeTypes = repo.getDistinctNodeTypes();
        final List< String > relTypes = repo.getDistinctRelationshipTypes();
        final long nodeCount = repo.countNodes();
        final long edgeCount = repo.countEdges();
        final long pendingProposals = repo.countPendingProposals();

        // Gather property key stats by scanning all nodes
        final List< KgNode > allNodes = repo.queryNodes( null, null, 10000, 0 );
        final Map< String, SchemaDescription.PropertyInfo > propKeys = new LinkedHashMap<>();
        final Map< String, List< String > > propValues = new LinkedHashMap<>();

        for ( final KgNode node : allNodes ) {
            for ( final Map.Entry< String, Object > entry : node.properties().entrySet() ) {
                propValues.computeIfAbsent( entry.getKey(), k -> new ArrayList<>() )
                        .add( String.valueOf( entry.getValue() ) );
            }
        }
        for ( final Map.Entry< String, List< String > > entry : propValues.entrySet() ) {
            final List< String > values = entry.getValue();
            final List< String > samples = values.stream().distinct().limit( 5 )
                    .collect( Collectors.toList() );
            propKeys.put( entry.getKey(), new SchemaDescription.PropertyInfo( values.size(), samples ) );
        }

        return new SchemaDescription( nodeTypes, relTypes, propKeys,
                new SchemaDescription.Stats( nodeCount, edgeCount, pendingProposals ) );
    }

    @Override
    public KgNode getNode( final UUID id ) {
        return repo.getNode( id );
    }

    @Override
    public KgNode getNodeByName( final String name ) {
        return repo.getNodeByName( name );
    }

    @Override
    public KgNode upsertNode( final String name, final String nodeType, final String sourcePage,
                              final Provenance provenance, final Map< String, Object > properties ) {
        return repo.upsertNode( name, nodeType, sourcePage, provenance, properties );
    }

    @Override
    public void deleteNode( final UUID id ) {
        repo.deleteNode( id );
    }

    @Override
    public void mergeNodes( final UUID sourceId, final UUID targetId ) {
        // Move all edges from sourceId to targetId
        final List< KgEdge > outbound = repo.getEdgesForNode( sourceId, "outbound" );
        for ( final KgEdge edge : outbound ) {
            repo.upsertEdge( targetId, edge.targetId(), edge.relationshipType(),
                    edge.provenance(), edge.properties() );
        }
        final List< KgEdge > inbound = repo.getEdgesForNode( sourceId, "inbound" );
        for ( final KgEdge edge : inbound ) {
            repo.upsertEdge( edge.sourceId(), targetId, edge.relationshipType(),
                    edge.provenance(), edge.properties() );
        }
        repo.deleteNode( sourceId );
    }

    @Override
    public List< KgNode > queryNodes( final Map< String, Object > filters,
                                       final Set< Provenance > provenanceFilter,
                                       final int limit, final int offset ) {
        return repo.queryNodes( filters, provenanceFilter, limit, offset );
    }

    @Override
    public KgEdge upsertEdge( final UUID sourceId, final UUID targetId,
                              final String relationshipType, final Provenance provenance,
                              final Map< String, Object > properties ) {
        return repo.upsertEdge( sourceId, targetId, relationshipType, provenance, properties );
    }

    @Override
    public void deleteEdge( final UUID id ) {
        repo.deleteEdge( id );
    }

    @Override
    public void diffAndRemoveStaleEdges( final UUID sourceId,
                                         final Set< Map.Entry< String, String > > currentEdges ) {
        repo.diffAndRemoveStaleEdges( sourceId, currentEdges );
    }

    @Override
    public List< KgEdge > getEdgesForNode( final UUID nodeId, final String direction ) {
        return repo.getEdgesForNode( nodeId, direction );
    }

    @Override
    public TraversalResult traverse( final String startNodeName, final String direction,
                                     final Set< String > relationshipTypes, final int maxDepth,
                                     final Set< Provenance > provenanceFilter ) {
        final KgNode start = repo.getNodeByName( startNodeName );
        if ( start == null ) {
            return new TraversalResult( List.of(), List.of() );
        }

        final Set< Provenance > filter = provenanceFilter != null && !provenanceFilter.isEmpty()
                ? provenanceFilter : DEFAULT_PROVENANCE;

        // BFS traversal
        final Map< UUID, KgNode > visitedNodes = new LinkedHashMap<>();
        final List< KgEdge > collectedEdges = new ArrayList<>();
        final Queue< UUID > queue = new LinkedList<>();
        final Map< UUID, Integer > depthMap = new HashMap<>();

        visitedNodes.put( start.id(), start );
        queue.add( start.id() );
        depthMap.put( start.id(), 0 );

        while ( !queue.isEmpty() ) {
            final UUID currentId = queue.poll();
            final int currentDepth = depthMap.get( currentId );
            if ( currentDepth >= maxDepth ) {
                continue;
            }

            final List< KgEdge > edges = repo.getEdgesForNode( currentId, direction );
            for ( final KgEdge edge : edges ) {
                if ( !filter.contains( edge.provenance() ) ) {
                    continue;
                }
                if ( !relationshipTypes.isEmpty()
                        && !relationshipTypes.contains( edge.relationshipType() ) ) {
                    continue;
                }

                collectedEdges.add( edge );

                final UUID neighborId = edge.sourceId().equals( currentId )
                        ? edge.targetId() : edge.sourceId();
                if ( !visitedNodes.containsKey( neighborId ) ) {
                    final KgNode neighbor = repo.getNode( neighborId );
                    if ( neighbor != null ) {
                        visitedNodes.put( neighborId, neighbor );
                        depthMap.put( neighborId, currentDepth + 1 );
                        queue.add( neighborId );
                    }
                }
            }
        }

        return new TraversalResult( new ArrayList<>( visitedNodes.values() ), collectedEdges );
    }

    @Override
    public List< KgNode > searchKnowledge( final String query,
                                            final Set< Provenance > provenanceFilter,
                                            final int limit ) {
        final Set< Provenance > filter = provenanceFilter != null && !provenanceFilter.isEmpty()
                ? provenanceFilter : DEFAULT_PROVENANCE;
        return repo.searchNodes( query, filter, limit );
    }

    @Override
    public KgProposal submitProposal( final String proposalType, final String sourcePage,
                                      final Map< String, Object > proposedData,
                                      final double confidence, final String reasoning ) {
        // Check rejection list for edge proposals
        if ( "new-edge".equals( proposalType ) ) {
            final String source = (String) proposedData.get( "source" );
            final String target = (String) proposedData.get( "target" );
            final String relationship = (String) proposedData.get( "relationship" );
            if ( source != null && target != null && relationship != null
                    && repo.isRejected( source, target, relationship ) ) {
                LOG.info( "Proposal rejected: {}->{} ({}) was previously rejected",
                        source, target, relationship );
                return null;
            }
        }
        return repo.insertProposal( proposalType, sourcePage, proposedData, confidence, reasoning );
    }

    @Override
    public List< KgProposal > listProposals( final String status, final String sourcePage,
                                              final int limit, final int offset ) {
        return repo.listProposals( status, sourcePage, limit, offset );
    }

    @Override
    public KgProposal approveProposal( final UUID proposalId, final String reviewedBy ) {
        final KgProposal proposal = repo.getProposal( proposalId );
        if ( proposal == null || !"pending".equals( proposal.status() ) ) {
            return null;
        }
        repo.updateProposalStatus( proposalId, "approved", reviewedBy );
        return repo.getProposal( proposalId );
    }

    @Override
    public KgProposal rejectProposal( final UUID proposalId, final String reviewedBy,
                                      final String reason ) {
        final KgProposal proposal = repo.getProposal( proposalId );
        if ( proposal == null || !"pending".equals( proposal.status() ) ) {
            return null;
        }
        repo.updateProposalStatus( proposalId, "rejected", reviewedBy );

        // Record rejection for edge proposals to prevent re-proposal
        if ( "new-edge".equals( proposal.proposalType() ) ) {
            final String source = (String) proposal.proposedData().get( "source" );
            final String target = (String) proposal.proposedData().get( "target" );
            final String relationship = (String) proposal.proposedData().get( "relationship" );
            if ( source != null && target != null && relationship != null ) {
                repo.insertRejection( source, target, relationship, reviewedBy, reason );
            }
        }

        return repo.getProposal( proposalId );
    }

    @Override
    public List< KgRejection > listRejections( final String sourceName, final String targetName,
                                                final String relationshipType ) {
        return repo.listRejections( sourceName, targetName, relationshipType );
    }

    @Override
    public boolean isRejected( final String sourceName, final String targetName,
                               final String relationshipType ) {
        return repo.isRejected( sourceName, targetName, relationshipType );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd wikantik-main && mvn test -Dtest=DefaultKnowledgeGraphServiceTest -pl .`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java
git commit -m "feat(knowledge): add DefaultKnowledgeGraphService with BFS traversal and proposal logic"
```

---

## Task 7: Graph Projector

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/GraphProjector.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/GraphProjectorTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:307-331`

The graph projector listens for `POST_SAVE_BEGIN` events, parses the saved page's frontmatter, uses `FrontmatterRelationshipDetector` to separate relationships from properties, and upserts nodes/edges into the knowledge graph via `KnowledgeGraphService`.

- [ ] **Step 1: Write failing test for graph projection on page save**

```java
// wikantik-main/src/test/java/com/wikantik/knowledge/GraphProjectorTest.java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphProjectorTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository repo;
    private DefaultKnowledgeGraphService service;
    private GraphProjector projector;

    @BeforeAll
    static void initDataSource() throws Exception {
        final org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL( "jdbc:h2:mem:kg_projector_test;DB_CLOSE_DELAY=-1" );
        dataSource = ds;
        try ( final Connection conn = ds.getConnection() ) {
            final String ddl = new String(
                GraphProjectorTest.class.getResourceAsStream( "/knowledge-h2.sql" ).readAllBytes() );
            conn.createStatement().execute( ddl );
        }
    }

    @BeforeEach
    void setUp() {
        repo = new JdbcKnowledgeRepository( dataSource );
        service = new DefaultKnowledgeGraphService( repo );
        projector = new GraphProjector( service );
    }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void projectPage_createsNodeAndEdges() {
        final Map< String, Object > frontmatter = Map.of(
            "type", "domain-model",
            "domain", "billing",
            "depends-on", List.of( "Customer", "Product" ),
            "tags", List.of( "core" )
        );

        projector.projectPage( "Order", frontmatter );

        final KgNode order = service.getNodeByName( "Order" );
        assertNotNull( order );
        assertEquals( "domain-model", order.nodeType() );
        assertEquals( "Order", order.sourcePage() );
        assertEquals( "billing", order.properties().get( "domain" ) );

        // Customer and Product should exist as stubs
        assertNotNull( service.getNodeByName( "Customer" ) );
        assertTrue( service.getNodeByName( "Customer" ).isStub() );

        // Two outbound edges from Order
        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        assertEquals( 2, edges.size() );
    }

    @Test
    void projectPage_removesStaleEdges() {
        // First save: Order depends on Customer and Product
        projector.projectPage( "Order", Map.of(
            "type", "domain-model",
            "depends-on", List.of( "Customer", "Product" )
        ) );

        // Second save: Order now only depends on Customer (Product removed)
        projector.projectPage( "Order", Map.of(
            "type", "domain-model",
            "depends-on", List.of( "Customer" )
        ) );

        final KgNode order = service.getNodeByName( "Order" );
        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        assertEquals( 1, edges.size() );
        assertEquals( service.getNodeByName( "Customer" ).id(), edges.get( 0 ).targetId() );
    }

    @Test
    void projectPage_preservesAiEdges() {
        // Human saves page with one edge
        projector.projectPage( "Order", Map.of( "depends-on", List.of( "Customer" ) ) );

        // AI adds an edge
        final KgNode order = service.getNodeByName( "Order" );
        final KgNode inv = service.upsertNode( "Inventory", "service", null,
                Provenance.AI_INFERRED, Map.of() );
        service.upsertEdge( order.id(), inv.id(), "calls", Provenance.AI_INFERRED, Map.of() );

        // Human re-saves with same content — AI edge should survive
        projector.projectPage( "Order", Map.of( "depends-on", List.of( "Customer" ) ) );

        final List< KgEdge > edges = service.getEdgesForNode( order.id(), "outbound" );
        assertEquals( 2, edges.size() ); // human edge + AI edge
    }

    @Test
    void projectPage_hydratesStubNode() {
        // Customer exists as stub from Order's save
        projector.projectPage( "Order", Map.of( "depends-on", List.of( "Customer" ) ) );
        assertTrue( service.getNodeByName( "Customer" ).isStub() );

        // Now Customer gets its own page
        projector.projectPage( "Customer", Map.of(
            "type", "domain-model",
            "domain", "crm"
        ) );

        final KgNode customer = service.getNodeByName( "Customer" );
        assertFalse( customer.isStub() );
        assertEquals( "domain-model", customer.nodeType() );
        assertEquals( "Customer", customer.sourcePage() );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd wikantik-main && mvn test -Dtest=GraphProjectorTest -pl . 2>&1 | tail -5`
Expected: Compilation error — `GraphProjector` does not exist.

- [ ] **Step 3: Implement GraphProjector**

```java
// wikantik-main/src/main/java/com/wikantik/knowledge/GraphProjector.java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.*;
import com.wikantik.knowledge.FrontmatterRelationshipDetector.DetectionResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Projects wiki page frontmatter into the knowledge graph. Called on every page save
 * that includes frontmatter. Upserts the page's node, resolves relationships to edges,
 * creates stub nodes for unresolved references, and diffs to remove stale edges.
 */
public class GraphProjector {

    private static final Logger LOG = LogManager.getLogger( GraphProjector.class );

    private final KnowledgeGraphService service;
    private final FrontmatterRelationshipDetector detector;

    public GraphProjector( final KnowledgeGraphService service ) {
        this.service = service;
        this.detector = new FrontmatterRelationshipDetector();
    }

    /**
     * Projects a page's frontmatter into the knowledge graph.
     *
     * @param pageName the wiki page name (used as node name and source_page)
     * @param frontmatter the parsed frontmatter metadata map
     */
    public void projectPage( final String pageName, final Map< String, Object > frontmatter ) {
        if ( frontmatter == null || frontmatter.isEmpty() ) {
            return;
        }

        final DetectionResult detection = detector.detect( frontmatter );

        // 1. Upsert the page's node
        final String nodeType = detection.properties().containsKey( "type" )
                ? String.valueOf( detection.properties().get( "type" ) ) : null;
        final KgNode pageNode = service.upsertNode( pageName, nodeType, pageName,
                Provenance.HUMAN_AUTHORED, detection.properties() );

        LOG.debug( "Projected node for page '{}': type={}, properties={}",
                pageName, nodeType, detection.properties().size() );

        // 2. Resolve relationships to edges
        final Set< Map.Entry< String, String > > currentEdges = new HashSet<>();
        for ( final Map.Entry< String, List< String > > rel : detection.relationships().entrySet() ) {
            final String relationshipType = rel.getKey();
            for ( final String targetName : rel.getValue() ) {
                // Ensure target node exists (create stub if needed)
                KgNode target = service.getNodeByName( targetName );
                if ( target == null ) {
                    target = service.upsertNode( targetName, null, null,
                            Provenance.HUMAN_AUTHORED, Map.of() );
                    LOG.debug( "Created stub node for '{}'", targetName );
                }

                // Check if this edge already exists with ai-reviewed provenance
                // (promotion write-back scenario — don't downgrade)
                final List< KgEdge > existingEdges = service.getEdgesForNode( pageNode.id(), "outbound" );
                final boolean alreadyReviewed = existingEdges.stream().anyMatch( e ->
                        e.targetId().equals( target.id() )
                        && e.relationshipType().equals( relationshipType )
                        && e.provenance() == Provenance.AI_REVIEWED );

                if ( !alreadyReviewed ) {
                    service.upsertEdge( pageNode.id(), target.id(), relationshipType,
                            Provenance.HUMAN_AUTHORED, Map.of() );
                }

                currentEdges.add( Map.entry( targetName, relationshipType ) );
            }
        }

        // 3. Diff: remove human-authored edges no longer in frontmatter
        service.diffAndRemoveStaleEdges( pageNode.id(), currentEdges );

        LOG.debug( "Projection complete for '{}': {} relationships", pageName, currentEdges.size() );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd wikantik-main && mvn test -Dtest=GraphProjectorTest -pl .`
Expected: All 4 tests PASS.

- [ ] **Step 5: Register GraphProjector in WikiEngine**

Modify `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`. After Phase 8 (ReferenceManager), add Phase 9 for the knowledge graph. The projector hooks into the FilterManager as a page filter that triggers on post-save events.

Note: The actual integration approach depends on whether KnowledgeGraphService uses a JNDI DataSource (requires servlet context) or can be initialized standalone. For the initial implementation, the service will be registered as a manager and the projector will be added as a page filter. The JNDI DataSource lookup will be deferred — the service is initialized lazily when the DataSource becomes available (e.g., via a property pointing to an existing JNDI name like `jdbc/GroupDatabase`).

Add after line 331 in WikiEngine.java:

```java
// Phase 9: Knowledge graph (optional — requires datasource configuration)
initKnowledgeGraph( props );
```

Add the initialization method:

```java
private void initKnowledgeGraph( final Properties props ) {
    final String datasource = props.getProperty( "wikantik.knowledge.datasource" );
    if ( datasource == null || datasource.isBlank() ) {
        LOG.info( "Knowledge graph disabled (no wikantik.knowledge.datasource configured)" );
        return;
    }
    try {
        final javax.naming.Context initCtx = new javax.naming.InitialContext();
        final javax.naming.Context ctx = (javax.naming.Context) initCtx.lookup( "java:comp/env" );
        final javax.sql.DataSource ds = (javax.sql.DataSource) ctx.lookup( datasource );

        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( ds );
        final DefaultKnowledgeGraphService service = new DefaultKnowledgeGraphService( repo );
        managers.put( KnowledgeGraphService.class, service );

        final GraphProjector projector = new GraphProjector( service );
        managers.put( GraphProjector.class, projector );

        // Register projector as a page filter so it fires on post-save
        getManager( FilterManager.class ).addPageFilter( projector, -1003 );

        LOG.info( "Knowledge graph initialized with datasource '{}'", datasource );
    } catch ( final Exception e ) {
        LOG.warn( "Knowledge graph initialization failed: {}", e.getMessage() );
    }
}
```

Note: `GraphProjector` will need to implement `PageFilter` for this integration. Update its class to implement `PageFilter` with a `postSave` method that calls `projectPage` using the saved page's frontmatter. The `preSave` method is a no-op.

- [ ] **Step 6: Run full test suite to verify no regressions**

Run: `mvn test -pl wikantik-main`
Expected: All existing tests pass, plus the 4 new GraphProjector tests.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/GraphProjector.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/GraphProjectorTest.java \
       wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat(knowledge): add GraphProjector for frontmatter-to-graph synchronization"
```

---

## Task 8: Consumption MCP Module Setup

**Files:**
- Create: `wikantik-knowledge/pom.xml`
- Modify: `pom.xml` (parent) — add module
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` — add servlet mapping

- [ ] **Step 1: Create module pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.wikantik</groupId>
        <artifactId>wikantik-builder</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>wikantik-knowledge</artifactId>
    <name>Wikantik Knowledge MCP</name>
    <description>Read-only MCP endpoint for knowledge graph consumption by coding agents</description>

    <dependencies>
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>wikantik-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>wikantik-main</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp</artifactId>
        </dependency>
        <dependency>
            <groupId>jakarta.servlet</groupId>
            <artifactId>jakarta.servlet-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.logging.log4j</groupId>
            <artifactId>log4j-api</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Add module to parent pom.xml**

Add `<module>wikantik-knowledge</module>` after `wikantik-mcp` in the parent pom.xml modules list.

- [ ] **Step 3: Create package directory structure**

```bash
mkdir -p wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp
mkdir -p wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp
```

- [ ] **Step 4: Verify module compiles**

Run: `mvn compile -pl wikantik-knowledge -am`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/pom.xml pom.xml
git commit -m "feat(knowledge): add wikantik-knowledge module skeleton"
```

---

## Task 9: Consumption MCP Tools

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpTool.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/DiscoverSchemaTool.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/QueryNodesTool.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/TraverseTool.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetNodeTool.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java`
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java`

Each tool implements the `McpTool` interface from `wikantik-mcp`. Since the consumption MCP is a separate module, define a local tool interface matching the same pattern.

- [ ] **Step 1: Write failing tests for all 5 tools**

```java
// wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolsTest.java
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.wikantik.api.knowledge.*;
import com.wikantik.knowledge.DefaultKnowledgeGraphService;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeMcpToolsTest {

    private static DataSource dataSource;
    private static final Gson GSON = new Gson();
    private KnowledgeGraphService service;

    @BeforeAll
    static void initDataSource() throws Exception {
        final org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL( "jdbc:h2:mem:kg_mcp_test;DB_CLOSE_DELAY=-1" );
        dataSource = ds;
        try ( final Connection conn = ds.getConnection() ) {
            final String ddl = new String(
                KnowledgeMcpToolsTest.class.getResourceAsStream( "/knowledge-h2.sql" ).readAllBytes() );
            conn.createStatement().execute( ddl );
        }
    }

    @BeforeEach
    void setUp() {
        service = new DefaultKnowledgeGraphService( new JdbcKnowledgeRepository( dataSource ) );
    }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void discoverSchema_returnsSchemaDescription() {
        service.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );

        final DiscoverSchemaTool tool = new DiscoverSchemaTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String text = ((McpSchema.TextContent) result.content().get( 0 )).text();

        assertTrue( text.contains( "domain-model" ) );
        assertTrue( text.contains( "billing" ) );
    }

    @Test
    void queryNodes_filtersCorrectly() {
        service.upsertNode( "Order", "domain-model", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertNode( "PaymentGW", "service", null, Provenance.HUMAN_AUTHORED, Map.of() );

        final QueryNodesTool tool = new QueryNodesTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "filters", Map.of( "node_type", "domain-model" ),
            "limit", 50
        ) );
        final String text = ((McpSchema.TextContent) result.content().get( 0 )).text();

        assertTrue( text.contains( "Order" ) );
        assertFalse( text.contains( "PaymentGW" ) );
    }

    @Test
    void traverse_returnsSubgraph() {
        final KgNode order = service.upsertNode( "Order", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode customer = service.upsertNode( "Customer", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( order.id(), customer.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );

        final TraverseTool tool = new TraverseTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "start_node", "Order",
            "direction", "outbound",
            "max_depth", 3
        ) );
        final String text = ((McpSchema.TextContent) result.content().get( 0 )).text();

        assertTrue( text.contains( "Order" ) );
        assertTrue( text.contains( "Customer" ) );
        assertTrue( text.contains( "depends-on" ) );
    }

    @Test
    void getNode_returnsFullDetail() {
        final KgNode order = service.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        final KgNode customer = service.upsertNode( "Customer", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( order.id(), customer.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );

        final GetNodeTool tool = new GetNodeTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "node", "Order" ) );
        final String text = ((McpSchema.TextContent) result.content().get( 0 )).text();

        assertTrue( text.contains( "Order" ) );
        assertTrue( text.contains( "domain-model" ) );
        assertTrue( text.contains( "depends-on" ) );
    }

    @Test
    void searchKnowledge_findsByName() {
        service.upsertNode( "OrderProcessing", "service", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertNode( "Customer", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );

        final SearchKnowledgeTool tool = new SearchKnowledgeTool( service );
        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "query", "order",
            "limit", 10
        ) );
        final String text = ((McpSchema.TextContent) result.content().get( 0 )).text();

        assertTrue( text.contains( "OrderProcessing" ) );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=KnowledgeMcpToolsTest -pl wikantik-knowledge 2>&1 | tail -5`
Expected: Compilation error — tool classes don't exist.

- [ ] **Step 3: Implement all 5 tools**

Each tool follows the same pattern from `wikantik-mcp/tools/McpTool.java`. Since `wikantik-knowledge` depends on `wikantik-mcp` being a separate concern, reuse the `McpTool` interface by depending on the MCP SDK directly and creating tools with the same `McpSchema.Tool` definition pattern.

Create each tool class. Example for `DiscoverSchemaTool`:

```java
// wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/DiscoverSchemaTool.java
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.SchemaDescription;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

public class DiscoverSchemaTool {

    private static final Gson GSON = new Gson();
    private final KnowledgeGraphService service;

    public DiscoverSchemaTool( final KnowledgeGraphService service ) {
        this.service = service;
    }

    public String name() {
        return "discover_schema";
    }

    public McpSchema.Tool definition() {
        return McpSchema.Tool.builder()
                .name( name() )
                .description( "Returns the current shape of the knowledge base: node types, relationship types, "
                        + "property keys with cardinalities and sample values, and aggregate statistics. "
                        + "Use this first to understand what's in this knowledge base before querying." )
                .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final SchemaDescription schema = service.discoverSchema();
        return McpSchema.CallToolResult.builder()
                .content( List.of( new McpSchema.TextContent( GSON.toJson( schema ) ) ) )
                .build();
    }
}
```

Create `QueryNodesTool`, `TraverseTool`, `GetNodeTool`, and `SearchKnowledgeTool` following the same pattern, each extracting parameters from the arguments map and calling the corresponding `KnowledgeGraphService` method. Each tool should parse `provenance_filter` from arguments (if present) as a list of provenance strings, converting to `Set<Provenance>`.

The implementations for the remaining 4 tools follow the exact same structure: constructor takes `KnowledgeGraphService`, `definition()` returns `McpSchema.Tool` with appropriate parameter schemas, `execute()` delegates to the service and returns JSON results.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=KnowledgeMcpToolsTest -pl wikantik-knowledge`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/
git commit -m "feat(knowledge): add 5 consumption MCP tools — discover, query, traverse, get, search"
```

---

## Task 10: Knowledge MCP Server Initializer

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

This registers a second MCP server at `/knowledge-mcp`, separate from the authoring MCP at `/mcp`.

- [ ] **Step 1: Implement KnowledgeMcpInitializer**

Follow the pattern from `McpServerInitializer.java` but register only the 5 read-only knowledge tools. No author resolution, no write tools, no prompts.

```java
// wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java
package com.wikantik.knowledge.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.spi.Wiki;

import java.util.EnumSet;

public class KnowledgeMcpInitializer implements ServletContextListener {

    private static final Logger LOG = LogManager.getLogger( KnowledgeMcpInitializer.class );
    private McpSyncServer mcpServer;

    @Override
    public void contextInitialized( final ServletContextEvent sce ) {
        final ServletContext servletContext = sce.getServletContext();

        final Engine engine;
        try {
            engine = Wiki.engine().find( servletContext, null );
        } catch ( final Exception e ) {
            LOG.warn( "WikiEngine not available — Knowledge MCP server not started: {}", e.getMessage() );
            return;
        }

        final KnowledgeGraphService kgService = engine.getManager( KnowledgeGraphService.class );
        if ( kgService == null ) {
            LOG.info( "Knowledge graph not configured — Knowledge MCP server not started" );
            return;
        }

        try {
            final HttpServletStreamableServerTransportProvider transportProvider =
                    HttpServletStreamableServerTransportProvider.builder()
                            .mcpEndpoint( "/knowledge-mcp" )
                            .build();

            final ServletRegistration.Dynamic registration =
                    servletContext.addServlet( "KnowledgeMcpTransportServlet", transportProvider );
            registration.addMapping( "/knowledge-mcp" );
            registration.setAsyncSupported( true );
            registration.setLoadOnStartup( 3 );

            final var serverImpl = new McpSchema.Implementation(
                    "wikantik-knowledge", "Wikantik Knowledge Graph", "1.0.0" );

            final DiscoverSchemaTool discoverSchema = new DiscoverSchemaTool( kgService );
            final QueryNodesTool queryNodes = new QueryNodesTool( kgService );
            final TraverseTool traverse = new TraverseTool( kgService );
            final GetNodeTool getNode = new GetNodeTool( kgService );
            final SearchKnowledgeTool searchKnowledge = new SearchKnowledgeTool( kgService );

            mcpServer = McpServer.sync( transportProvider )
                    .serverInfo( serverImpl )
                    .instructions( "This is a read-only knowledge graph endpoint. Use discover_schema first "
                            + "to understand what's in this knowledge base, then query, traverse, or search." )
                    .capabilities( ServerCapabilities.builder()
                            .tools( true )
                            .build() )
                    .toolCall( discoverSchema.definition(), ( exchange, request ) ->
                            discoverSchema.execute( request.arguments() ) )
                    .toolCall( queryNodes.definition(), ( exchange, request ) ->
                            queryNodes.execute( request.arguments() ) )
                    .toolCall( traverse.definition(), ( exchange, request ) ->
                            traverse.execute( request.arguments() ) )
                    .toolCall( getNode.definition(), ( exchange, request ) ->
                            getNode.execute( request.arguments() ) )
                    .toolCall( searchKnowledge.definition(), ( exchange, request ) ->
                            searchKnowledge.execute( request.arguments() ) )
                    .build();

            LOG.info( "Knowledge MCP server started at /knowledge-mcp with 5 read-only tools" );

        } catch ( final Exception e ) {
            LOG.error( "Failed to start Knowledge MCP server: {}", e.getMessage(), e );
        }
    }

    @Override
    public void contextDestroyed( final ServletContextEvent sce ) {
        if ( mcpServer != null ) {
            try {
                mcpServer.close();
                LOG.info( "Knowledge MCP server shut down" );
            } catch ( final Exception e ) {
                LOG.warn( "Error shutting down Knowledge MCP server: {}", e.getMessage() );
            }
        }
    }
}
```

- [ ] **Step 2: Register listener in web.xml**

Add to `wikantik-war/src/main/webapp/WEB-INF/web.xml` in the listeners section:

```xml
<listener>
    <listener-class>com.wikantik.knowledge.mcp.KnowledgeMcpInitializer</listener-class>
</listener>
```

- [ ] **Step 3: Add JNDI DataSource configuration**

Add to `Wikantik-context.xml.template`:

```xml
<!-- JNDI DataSource for Knowledge Graph (same database) -->
<Resource name="jdbc/KnowledgeDatabase"
          auth="Container"
          type="javax.sql.DataSource"
          factory="org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory"
          driverClassName="org.postgresql.Driver"
          url="jdbc:postgresql://localhost:5432/wikantik"
          username="wikantik"
          password="YOUR_SECURE_PASSWORD_HERE"
          maxTotal="20"
          maxIdle="10"
          maxWaitMillis="10000"
          validationQuery="SELECT 1"
          testOnBorrow="true"/>
```

Add to `wikantik-custom-postgresql.properties.template`:

```properties
wikantik.knowledge.datasource = jdbc/KnowledgeDatabase
```

- [ ] **Step 4: Build and verify module compiles**

Run: `mvn compile -pl wikantik-knowledge -am`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java \
       wikantik-war/src/main/webapp/WEB-INF/web.xml \
       wikantik-war/src/main/config/tomcat/Wikantik-context.xml.template \
       wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template
git commit -m "feat(knowledge): add KnowledgeMcpInitializer — separate MCP endpoint at /knowledge-mcp"
```

---

## Task 11: Proposal Tools on Authoring MCP

**Files:**
- Create: `wikantik-mcp/src/main/java/com/wikantik/mcp/tools/ProposeKnowledgeTool.java`
- Create: `wikantik-mcp/src/main/java/com/wikantik/mcp/tools/ListRejectionsTool.java`
- Create: `wikantik-mcp/src/main/java/com/wikantik/mcp/tools/ListProposalsTool.java`
- Modify: `wikantik-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java`

These 3 tools are added to the existing authoring MCP, allowing external agents to submit knowledge proposals and check rejection/proposal history.

- [ ] **Step 1: Implement ProposeKnowledgeTool**

Follows the `McpTool` interface pattern. Parameters: `proposal_type`, `proposed_data` (JSON object), `source_page`, `confidence`, `reasoning`. Returns the created proposal or an error if the relationship was previously rejected.

- [ ] **Step 2: Implement ListRejectionsTool**

Parameters: `source` (optional), `target` (optional), `relationship` (optional). Returns matching rejections.

- [ ] **Step 3: Implement ListProposalsTool**

Parameters: `status` (optional), `source_page` (optional), `limit`, `offset`. Returns matching proposals.

- [ ] **Step 4: Register tools in McpToolRegistry**

Add to the `McpToolRegistry` constructor, after existing tool creation:

```java
// Knowledge proposal tools (only if KnowledgeGraphService is available)
final KnowledgeGraphService kgService = engine.getManager( KnowledgeGraphService.class );
if ( kgService != null ) {
    readOnly.add( new ListProposalsTool( kgService ) );
    readOnly.add( new ListRejectionsTool( kgService ) );
    authorConfigurable.add( new ProposeKnowledgeTool( kgService ) );
}
```

- [ ] **Step 5: Build and verify**

Run: `mvn compile -pl wikantik-mcp -am`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-mcp/src/main/java/com/wikantik/mcp/tools/ProposeKnowledgeTool.java \
       wikantik-mcp/src/main/java/com/wikantik/mcp/tools/ListRejectionsTool.java \
       wikantik-mcp/src/main/java/com/wikantik/mcp/tools/ListProposalsTool.java \
       wikantik-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java
git commit -m "feat(knowledge): add proposal tools to authoring MCP — propose, list proposals, list rejections"
```

---

## Task 12: Admin REST Endpoint for Knowledge

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

Provides REST API for the admin UI: proposal review (list, approve, reject), graph browsing (schema, nodes, edges), and manual curation (create/edit/delete/merge nodes and edges).

- [ ] **Step 1: Implement AdminKnowledgeResource**

Extends `RestServletBase`. Endpoints:

```
GET  /admin/knowledge/schema          → discoverSchema
GET  /admin/knowledge/nodes           → queryNodes (with ?filters=...&limit=...&offset=...)
GET  /admin/knowledge/nodes/{name}    → getNode
GET  /admin/knowledge/edges/{nodeId}  → getEdgesForNode
GET  /admin/knowledge/proposals       → listProposals (?status=pending&limit=50)
POST /admin/knowledge/proposals/{id}/approve  → approveProposal
POST /admin/knowledge/proposals/{id}/reject   → rejectProposal (body: {reason})
POST /admin/knowledge/nodes           → upsertNode (manual curation)
PUT  /admin/knowledge/nodes/{id}      → updateNode
DELETE /admin/knowledge/nodes/{id}    → deleteNode
POST /admin/knowledge/nodes/merge     → mergeNodes (body: {sourceId, targetId})
POST /admin/knowledge/edges           → upsertEdge (manual curation)
DELETE /admin/knowledge/edges/{id}    → deleteEdge
```

All endpoints are protected by the existing `AdminAuthFilter` on `/admin/*`.

- [ ] **Step 2: Register servlet in web.xml**

Add servlet definition and mappings:

```xml
<servlet>
    <servlet-name>AdminKnowledgeResource</servlet-name>
    <servlet-class>com.wikantik.rest.AdminKnowledgeResource</servlet-class>
</servlet>
<servlet-mapping>
    <servlet-name>AdminKnowledgeResource</servlet-name>
    <url-pattern>/admin/knowledge/*</url-pattern>
</servlet-mapping>
<servlet-mapping>
    <servlet-name>AdminKnowledgeResource</servlet-name>
    <url-pattern>/admin/knowledge</url-pattern>
</servlet-mapping>
```

- [ ] **Step 3: Build and verify**

Run: `mvn compile -pl wikantik-rest -am`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java \
       wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(knowledge): add admin REST endpoints for knowledge graph management"
```

---

## Task 13: Admin Knowledge UI — API Client and Routing

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`
- Modify: `wikantik-frontend/src/main.jsx`
- Modify: `wikantik-frontend/src/components/admin/AdminLayout.jsx`
- Modify: `wikantik-frontend/src/components/Sidebar.jsx`

- [ ] **Step 1: Add knowledge API methods to client.js**

Add a `knowledge` namespace to the `api` object:

```javascript
knowledge: {
    getSchema: () => request('/admin/knowledge/schema'),
    queryNodes: (filters = {}, limit = 50, offset = 0) =>
        request(`/admin/knowledge/nodes?filters=${encodeURIComponent(JSON.stringify(filters))}&limit=${limit}&offset=${offset}`),
    getNode: (name) =>
        request(`/admin/knowledge/nodes/${encodeURIComponent(name)}`),
    getEdges: (nodeId, direction = 'both') =>
        request(`/admin/knowledge/edges/${nodeId}?direction=${direction}`),
    listProposals: (status = 'pending', limit = 50) =>
        request(`/admin/knowledge/proposals?status=${status}&limit=${limit}`),
    approveProposal: (id) =>
        request(`/admin/knowledge/proposals/${id}/approve`, { method: 'POST' }),
    rejectProposal: (id, reason) =>
        request(`/admin/knowledge/proposals/${id}/reject`, {
            method: 'POST',
            body: JSON.stringify({ reason }),
        }),
    upsertNode: (data) =>
        request('/admin/knowledge/nodes', { method: 'POST', body: JSON.stringify(data) }),
    deleteNode: (id) =>
        request(`/admin/knowledge/nodes/${id}`, { method: 'DELETE' }),
    mergeNodes: (sourceId, targetId) =>
        request('/admin/knowledge/nodes/merge', {
            method: 'POST',
            body: JSON.stringify({ sourceId, targetId }),
        }),
    upsertEdge: (data) =>
        request('/admin/knowledge/edges', { method: 'POST', body: JSON.stringify(data) }),
    deleteEdge: (id) =>
        request(`/admin/knowledge/edges/${id}`, { method: 'DELETE' }),
},
```

- [ ] **Step 2: Add route and nav links**

In `main.jsx`, add import and route:

```jsx
import AdminKnowledgePage from './components/admin/AdminKnowledgePage';
// Inside the admin Route children:
<Route path="knowledge" element={<AdminKnowledgePage />} />
```

In `AdminLayout.jsx`, add nav link:

```jsx
<NavLink to="/admin/knowledge" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
  Knowledge
</NavLink>
```

In `Sidebar.jsx`, add admin link for knowledge (following existing pattern for admin links).

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/api/client.js \
       wikantik-frontend/src/main.jsx \
       wikantik-frontend/src/components/admin/AdminLayout.jsx \
       wikantik-frontend/src/components/Sidebar.jsx
git commit -m "feat(knowledge): add admin knowledge API client and routing"
```

---

## Task 14: Admin Knowledge UI — Proposal Review Queue

**Files:**
- Create: `wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx`
- Create: `wikantik-frontend/src/components/admin/ProposalReviewQueue.jsx`

- [ ] **Step 1: Implement AdminKnowledgePage as tab container**

Three tabs: Proposals, Explorer, Curation. Each tab renders a child component.

```jsx
// wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx
import { useState } from 'react';
import ProposalReviewQueue from './ProposalReviewQueue';
import GraphExplorer from './GraphExplorer';
import '../../styles/admin.css';

export default function AdminKnowledgePage() {
    const [activeTab, setActiveTab] = useState('proposals');

    return (
        <div className="admin-knowledge page-enter">
            <div className="admin-toolbar">
                <div className="admin-tabs">
                    {['proposals', 'explorer', 'curation'].map(tab => (
                        <button
                            key={tab}
                            className={`admin-tab ${activeTab === tab ? 'active' : ''}`}
                            onClick={() => setActiveTab(tab)}
                        >
                            {tab.charAt(0).toUpperCase() + tab.slice(1)}
                        </button>
                    ))}
                </div>
            </div>
            {activeTab === 'proposals' && <ProposalReviewQueue />}
            {activeTab === 'explorer' && <GraphExplorer />}
        </div>
    );
}
```

- [ ] **Step 2: Implement ProposalReviewQueue**

Lists pending proposals with approve/reject actions. Each row shows: proposal type, source page, confidence, reasoning excerpt, and action buttons.

```jsx
// wikantik-frontend/src/components/admin/ProposalReviewQueue.jsx
import { useState, useEffect } from 'react';
import { api } from '../../api/client';

export default function ProposalReviewQueue() {
    const [proposals, setProposals] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);

    const loadProposals = async () => {
        try {
            const data = await api.knowledge.listProposals('pending', 50);
            setProposals(data.proposals || []);
        } catch (err) {
            setError(err.message);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { loadProposals(); }, []);

    const handleApprove = async (id) => {
        await api.knowledge.approveProposal(id);
        await loadProposals();
    };

    const handleReject = async (id) => {
        const reason = prompt('Rejection reason (optional):');
        await api.knowledge.rejectProposal(id, reason || '');
        await loadProposals();
    };

    if (loading) return <div className="admin-loading">Loading proposals...</div>;
    if (error) return <div className="admin-error">{error}</div>;

    return (
        <div className="admin-proposals">
            <h3>Pending Proposals ({proposals.length})</h3>
            {proposals.length === 0 ? (
                <p className="admin-empty">No pending proposals.</p>
            ) : (
                <table className="admin-table">
                    <thead>
                        <tr>
                            <th>Type</th>
                            <th>Source Page</th>
                            <th>Details</th>
                            <th>Confidence</th>
                            <th>Reasoning</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {proposals.map(p => (
                            <tr key={p.id}>
                                <td>{p.proposalType}</td>
                                <td>{p.sourcePage}</td>
                                <td><pre>{JSON.stringify(p.proposedData, null, 2)}</pre></td>
                                <td>{(p.confidence * 100).toFixed(0)}%</td>
                                <td className="admin-reasoning">{p.reasoning}</td>
                                <td>
                                    <button className="btn btn-sm btn-success" onClick={() => handleApprove(p.id)}>Approve</button>
                                    <button className="btn btn-sm btn-danger" onClick={() => handleReject(p.id)}>Reject</button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </div>
    );
}
```

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx \
       wikantik-frontend/src/components/admin/ProposalReviewQueue.jsx
git commit -m "feat(knowledge): add admin knowledge page with proposal review queue"
```

---

## Task 15: Admin Knowledge UI — Graph Explorer

**Files:**
- Create: `wikantik-frontend/src/components/admin/GraphExplorer.jsx`
- Create: `wikantik-frontend/src/components/admin/NodeDetail.jsx`

- [ ] **Step 1: Implement GraphExplorer**

Shows the knowledge schema summary at top, a searchable/filterable node list, and a node detail panel. Uses `discover_schema` equivalent (getSchema) to show available types and relationships, and `queryNodes` to browse.

- [ ] **Step 2: Implement NodeDetail**

Shows full detail for a selected node: all properties, all inbound/outbound edges (with links to navigate to connected nodes), source page link, and provenance badge. Includes edit/delete buttons for manual curation.

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/components/admin/GraphExplorer.jsx \
       wikantik-frontend/src/components/admin/NodeDetail.jsx
git commit -m "feat(knowledge): add graph explorer and node detail UI components"
```

---

## Task 16: Knowledge Admin Role

**Files:**
- Modify: `wikantik-war/src/main/config/db/postgresql-permissions.ddl` (or inline in knowledge DDL)

The existing `AdminAuthFilter` on `/admin/*` requires `AllPermission`. For the knowledge admin, we have two options:

1. Reuse the existing `Admin` role (simplest — knowledge admin IS a wiki admin)
2. Create a new `knowledge-admin` role with a separate filter

For the initial implementation, option 1 is pragmatic. The `/admin/knowledge/*` endpoints are already protected by `AdminAuthFilter`. A dedicated `knowledge-admin` role can be added later when there's a concrete need for non-admin domain experts to curate knowledge.

- [ ] **Step 1: Document the role decision**

Add a comment in `AdminKnowledgeResource.java`:

```java
/**
 * REST endpoints for knowledge graph administration.
 * Currently protected by AdminAuthFilter (requires Admin role).
 * TODO: When knowledge-admin role is needed, add a KnowledgeAdminFilter
 * that checks for either Admin or knowledge-admin role.
 */
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java
git commit -m "docs(knowledge): document knowledge-admin role plan in AdminKnowledgeResource"
```

---

## Task 17: Deployment Configuration Updates

**Files:**
- Modify: `wikantik-war/pom.xml` — add wikantik-knowledge dependency
- Modify: `deploy-local.sh` — include knowledge DDL in bootstrap

- [ ] **Step 1: Add wikantik-knowledge dependency to WAR**

In `wikantik-war/pom.xml`, add:

```xml
<dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>wikantik-knowledge</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: Update deploy-local.sh for knowledge DDL**

Add to the bootstrap section of `deploy-local.sh` (after the permissions DDL):

```bash
# Apply knowledge graph DDL if not already present
if ! PGPASSWORD="$PG_PASSWORD" psql -h localhost -U wikantik -d wikantik -c "SELECT 1 FROM kg_nodes LIMIT 1" 2>/dev/null; then
    echo "Creating knowledge graph tables..."
    PGPASSWORD="$PG_PASSWORD" psql -h localhost -U wikantik -d wikantik -f wikantik-war/src/main/config/db/postgresql-knowledge.ddl
fi
```

- [ ] **Step 3: Build full project**

Run: `mvn clean install -Dmaven.test.skip -T 1C`
Expected: BUILD SUCCESS with all modules including wikantik-knowledge.

- [ ] **Step 4: Commit**

```bash
git add wikantik-war/pom.xml deploy-local.sh
git commit -m "feat(knowledge): add wikantik-knowledge to WAR packaging and deployment bootstrap"
```

---

## Task 18: Frontmatter Write-Back on Proposal Approval

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`

When a proposal is approved, the approved relationship must be written back into the source page's frontmatter. This closes the loop: AI proposes → human approves → frontmatter updated → graph projector confirms.

- [ ] **Step 1: Implement write-back in the approve endpoint**

In the `approveProposal` handler:

1. Call `service.approveProposal()` to update the graph
2. For `new-edge` proposals: read the source page, parse frontmatter, add the target to the appropriate relationship key (create the key if needed), write the page back
3. The page save triggers the graph projector, which recognizes the edge already exists at `ai-reviewed` and skips duplication

The write-back uses `PageManager.getPureText()` to read, `FrontmatterParser.parse()` to extract, `FrontmatterWriter` to serialize, and `PageSaveHelper.saveText()` to save.

- [ ] **Step 2: Test manually after deployment**

After deploying, create a test page with frontmatter, submit a proposal via the authoring MCP, approve it via the admin UI, and verify the frontmatter was updated.

- [ ] **Step 3: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java
git commit -m "feat(knowledge): implement frontmatter write-back on proposal approval"
```

---

## Task 19: SPA Routing and Final Integration

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java` — ensure `/admin/knowledge` is routed to SPA
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` — ensure `/knowledge-mcp` is NOT caught by SPA filter

- [ ] **Step 1: Verify SPA routing handles /admin/knowledge**

The existing SPA filter already catches `/admin/*`. Verify that `/admin/knowledge` correctly routes to the React SPA by checking the filter's URL pattern list.

- [ ] **Step 2: Ensure /knowledge-mcp is excluded from SPA routing**

The MCP endpoint must NOT be caught by the SPA filter. Verify that the SPA filter's URL patterns do not include `/knowledge-mcp`. If the filter uses a wildcard, add an exclusion.

- [ ] **Step 3: Full build and manual smoke test**

Run: `mvn clean install -Dmaven.test.skip -T 1C && ./deploy-local.sh`

Verify:
- `/knowledge-mcp` responds to MCP protocol
- `/admin/knowledge` shows the React admin UI
- The graph projector creates nodes when saving a page with frontmatter

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(knowledge): final integration — SPA routing, MCP endpoint exclusion, smoke test"
```
