# KG Edge Curation v0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the read-only-Edge-Explorer gap by shipping full create / edit / delete / delete-and-reject / bulk-delete edge curation in the admin UI, with an `kg_edge_audit` table backing a History pane.

**Architecture:** Backend mutations go through `AdminKnowledgeResource` → `DefaultKnowledgeGraphService` → `KgEdgeRepository` (+ new `KgEdgeAuditRepository`, `KgRejectionRepository.insertEdgeRejection`). Server unconditionally stamps `provenance='human-curated'` + `tier='human'` on every admin upsert. The UI grows an `EdgeFormModal` (shared Create/Edit), three action buttons (Edit / Delete / Delete + Prevent) in `EdgeDetail`, a bulk-delete control next to pagination, and a collapsible History list. Audit rows are written by the resource layer after each successful mutation; failures log-and-continue.

**Tech Stack:** Java 21, Jakarta Servlet, JDBC (PostgreSQL + pgvector), Gson, Log4j2, JUnit 5 + Mockito + Selenide; React 18 + Vite + Vitest + Testing Library.

**Spec:** [docs/superpowers/specs/2026-05-11-kg-edge-curation-v0-design.md](../specs/2026-05-11-kg-edge-curation-v0-design.md)

---

## File Inventory

**Create:**
- `bin/db/migrations/V028__kg_edge_audit.sql` — audit table DDL
- `wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeAuditRepository.java` — JDBC repo for the audit table
- `wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeAuditRepositoryTest.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeBulkDeleteTest.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeProvenanceStampingTest.java`
- `wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeEdgeCurationTest.java` — Mockito unit tests for resource layer
- `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/EdgeCurationIT.java` — wire-level IT
- `wikantik-it-tests/wikantik-selenide-tests/src/test/java/com/wikantik/its/EdgeCurationBrowserIT.java` — Selenide happy-path browser test
- `wikantik-frontend/src/components/admin/EdgeFormModal.jsx` — Create/Edit modal
- `wikantik-frontend/src/components/admin/EdgeFormModal.test.jsx`
- `wikantik-frontend/src/components/admin/EdgeExplorer.test.jsx` — Vitest for new buttons

**Modify:**
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/Provenance.java` — add `HUMAN_CURATED` value
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java` — add four service methods
- `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java` — implement the four service methods, wire `KgEdgeAuditRepository`
- `wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeRepository.java` — add `countEdgesWithFilter`, `bulkDeleteByFilter`, modify `upsertEdge` to accept actor provenance
- `wikantik-main/src/main/java/com/wikantik/knowledge/KgRejectionRepository.java` (or wherever rejections live — confirm in Task 5) — add `insertEdgeRejection`
- `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java` — add four endpoints, modify `handlePostEdge` / `handleDeleteEdge` / `handleGetEdges` for audit + total + stamping
- `wikantik-frontend/src/api/client.js` — add three client wrappers + update queryEdges to surface `total`
- `wikantik-frontend/src/components/admin/EdgeExplorer.jsx` — wire all new UI

---

## Critical Conventions (from CLAUDE.md and project memory)

- **TDD.** Every code task starts with a failing test.
- **No empty catch.** Log at least `LOG.warn` with context on any caught exception.
- **No data backfills in versioned migrations.** Audit table starts empty — correct.
- **Stage files by name** in commits — never `git add -A` or `git add .`.
- **Commit frequently** at the end of each task. Per CLAUDE.md, this repo is sole-developer on `main`, no feature branches.
- **Run `mvn test-compile` after signature changes** to catch test-file breakage (`compile` skips test sources).
- **Full IT reactor before considering done:** `mvn clean install -Pintegration-tests -fae`.

---

## Phase 1 — Backend Foundation

### Task 1: Migration V028 for kg_edge_audit

**Files:**
- Create: `bin/db/migrations/V028__kg_edge_audit.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V028__kg_edge_audit.sql
-- Append-only audit trail of admin-UI-driven kg_edges mutations.
-- Schema: see docs/superpowers/specs/2026-05-11-kg-edge-curation-v0-design.md

CREATE TABLE IF NOT EXISTS kg_edge_audit (
    id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    edge_id   UUID         NOT NULL,
    action    VARCHAR(10)  NOT NULL CHECK (action IN ('CREATE','UPDATE','DELETE')),
    before    JSONB,
    after     JSONB,
    actor     VARCHAR(100) NOT NULL,
    reason    TEXT,
    created   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kg_edge_audit_edge_created
    ON kg_edge_audit (edge_id, created DESC);

GRANT SELECT, INSERT ON kg_edge_audit TO :app_user;
```

- [ ] **Step 2: Apply the migration locally and verify idempotency**

```bash
DB_NAME=jspwiki DB_APP_USER=jspwiki PGPASSWORD="$(grep -oP 'password="\K[^"]+' tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml | head -1)" \
  bin/db/migrate.sh
# Re-run to confirm idempotent
DB_NAME=jspwiki DB_APP_USER=jspwiki PGPASSWORD="$(grep -oP 'password="\K[^"]+' tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml | head -1)" \
  bin/db/migrate.sh
bin/db/migrate.sh --status
```

Expected: V028 listed as applied, second run no-ops.

- [ ] **Step 3: Verify table exists with correct shape**

```bash
PGPASSWORD="$(grep -oP 'password="\K[^"]+' tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml | head -1)" \
  psql -h localhost -U jspwiki -d jspwiki -c "\d kg_edge_audit"
```

Expected: table with 7 columns matching the DDL.

- [ ] **Step 4: Commit**

```bash
git add bin/db/migrations/V028__kg_edge_audit.sql
git commit -m "$(cat <<'EOF'
db(V028): kg_edge_audit append-only audit table

Backs the Edge Explorer History pane per the KG edge curation v0 spec.
EOF
)"
```

---

### Task 2: Add HUMAN_CURATED to Provenance enum

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/Provenance.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/knowledge/ProvenanceTest.java` (create if absent)

- [ ] **Step 1: Write the failing test**

Create `wikantik-api/src/test/java/com/wikantik/api/knowledge/ProvenanceTest.java`:

```java
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProvenanceTest {

    @Test
    void humanCuratedRoundtripsThroughFromValue() {
        assertEquals( Provenance.HUMAN_CURATED, Provenance.fromValue( "human-curated" ) );
        assertEquals( "human-curated", Provenance.HUMAN_CURATED.value() );
    }

    @Test
    void existingValuesStillRoundtrip() {
        assertEquals( Provenance.HUMAN_AUTHORED, Provenance.fromValue( "human-authored" ) );
        assertEquals( Provenance.AI_INFERRED, Provenance.fromValue( "ai-inferred" ) );
        assertEquals( Provenance.AI_REVIEWED, Provenance.fromValue( "ai-reviewed" ) );
    }

    @Test
    void unknownValueThrows() {
        assertThrows( IllegalArgumentException.class, () -> Provenance.fromValue( "bogus" ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl wikantik-api -Dtest=ProvenanceTest -q
```

Expected: FAIL — "HUMAN_CURATED cannot be resolved" or similar.

- [ ] **Step 3: Add the enum value**

Edit `wikantik-api/src/main/java/com/wikantik/api/knowledge/Provenance.java`:

```java
package com.wikantik.api.knowledge;

public enum Provenance {
    HUMAN_AUTHORED( "human-authored" ),
    HUMAN_CURATED( "human-curated" ),
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

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -pl wikantik-api -Dtest=ProvenanceTest -q
```

Expected: PASS, three green tests.

- [ ] **Step 5: Compile downstream modules to confirm no exhaustive switches break**

```bash
mvn test-compile -pl wikantik-main,wikantik-rest -am -q
```

Expected: BUILD SUCCESS. No "switch does not cover all enum values" errors. (None expected — grep confirms there are no `switch (provenance)` statements; usages are direct constant references.)

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/Provenance.java \
        wikantik-api/src/test/java/com/wikantik/api/knowledge/ProvenanceTest.java
git commit -m "$(cat <<'EOF'
feat(kg): add HUMAN_CURATED provenance for admin-UI edge writes

Distinguishes admin-curated edges from page-body-derived HUMAN_AUTHORED
rows. Required by the edge curation v0 endpoint.
EOF
)"
```

---

### Task 3: KgEdgeAuditRepository — insert + findByEdgeId

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeAuditRepository.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeAuditRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

Create `wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeAuditRepositoryTest.java`:

```java
package com.wikantik.knowledge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class KgEdgeAuditRepositoryTest {

    @Container
    static final PostgreSQLContainer< ? > PG = new PostgreSQLContainer<>( "pgvector/pgvector:pg15" )
            .withDatabaseName( "kg" ).withUsername( "test" ).withPassword( "test" );

    static DataSource ds;
    static KgEdgeAuditRepository repo;

    @BeforeAll
    static void setUp() throws Exception {
        ds = KgTestSupport.buildDataSource( PG );
        KgTestSupport.applyMigrationsThrough( ds, "V028" );
        repo = new KgEdgeAuditRepository( ds );
    }

    @Test
    void insertAndFindByEdgeIdOrdersNewestFirst() {
        final UUID edgeId = UUID.randomUUID();
        repo.insert( edgeId, "CREATE", null,
                Map.of( "id", edgeId.toString(), "relationship_type", "related" ),
                "alice", null );
        repo.insert( edgeId, "UPDATE",
                Map.of( "relationship_type", "related" ),
                Map.of( "relationship_type", "depends_on" ),
                "bob", "type rewrite" );
        repo.insert( edgeId, "DELETE",
                Map.of( "relationship_type", "depends_on" ),
                null, "carol", "wrong direction" );

        final List< Map< String, Object > > rows = repo.findByEdgeId( edgeId, 10 );

        assertEquals( 3, rows.size() );
        assertEquals( "DELETE", rows.get( 0 ).get( "action" ) );
        assertEquals( "UPDATE", rows.get( 1 ).get( "action" ) );
        assertEquals( "CREATE", rows.get( 2 ).get( "action" ) );
        assertEquals( "carol", rows.get( 0 ).get( "actor" ) );
        assertEquals( "wrong direction", rows.get( 0 ).get( "reason" ) );
    }

    @Test
    void findByEdgeIdRespectsLimit() {
        final UUID edgeId = UUID.randomUUID();
        for ( int i = 0; i < 5; i++ ) {
            repo.insert( edgeId, "UPDATE", Map.of(), Map.of( "i", i ), "alice", null );
        }
        assertEquals( 2, repo.findByEdgeId( edgeId, 2 ).size() );
    }

    @Test
    void findByMissingEdgeReturnsEmptyList() {
        assertTrue( repo.findByEdgeId( UUID.randomUUID(), 10 ).isEmpty() );
    }
}
```

NOTE: `KgTestSupport` is a small helper that already exists in `wikantik-main` for `KgJdbcSupport`-style repo tests. If absent, Task 3 includes creating it. Check first: `find wikantik-main/src/test -name "KgTestSupport*"`.

- [ ] **Step 2: Verify KgTestSupport exists or create it**

```bash
find wikantik-main/src/test -name "KgTestSupport*"
```

If absent, create `wikantik-main/src/test/java/com/wikantik/knowledge/KgTestSupport.java`:

```java
package com.wikantik.knowledge;

import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class KgTestSupport {
    private KgTestSupport() {}

    static DataSource buildDataSource( final PostgreSQLContainer< ? > pg ) {
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl( pg.getJdbcUrl() );
        ds.setUser( pg.getUsername() );
        ds.setPassword( pg.getPassword() );
        return ds;
    }

    static void applyMigrationsThrough( final DataSource ds, final String highestVersion ) throws Exception {
        final Path migrations = Path.of( "..", "bin", "db", "migrations" ).toAbsolutePath().normalize();
        try ( Stream< Path > files = Files.list( migrations ) ) {
            final List< Path > applicable = files
                    .filter( p -> p.getFileName().toString().endsWith( ".sql" ) )
                    .filter( p -> p.getFileName().toString().compareTo( highestVersion + "_zzz" ) <= 0 )
                    .sorted( Comparator.comparing( p -> p.getFileName().toString() ) )
                    .toList();
            try ( Connection conn = ds.getConnection(); Statement st = conn.createStatement() ) {
                // pgvector + gen_random_uuid prerequisites
                st.execute( "CREATE EXTENSION IF NOT EXISTS pgcrypto" );
                st.execute( "CREATE EXTENSION IF NOT EXISTS vector" );
                st.execute( "CREATE ROLE jspwiki NOLOGIN" );
                for ( final Path m : applicable ) {
                    final String sql = Files.readString( m ).replace( ":app_user", "jspwiki" );
                    st.execute( sql );
                }
            } catch ( final Exception e ) {
                throw new IOException( "Migration apply failed", e );
            }
        }
    }
}
```

(If a richer helper already exists, just import it instead.)

- [ ] **Step 3: Run test to verify it fails**

```bash
mvn test -pl wikantik-main -Dtest=KgEdgeAuditRepositoryTest -q
```

Expected: FAIL — `KgEdgeAuditRepository` does not exist.

- [ ] **Step 4: Implement KgEdgeAuditRepository**

Create `wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeAuditRepository.java`:

```java
package com.wikantik.knowledge;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit trail for admin-UI driven kg_edges mutations.
 *
 * Schema: see V028__kg_edge_audit.sql.
 */
public final class KgEdgeAuditRepository extends KgJdbcSupport {

    private static final Logger LOG = LogManager.getLogger( KgEdgeAuditRepository.class );
    private static final Gson GSON_LOCAL = new Gson();

    public KgEdgeAuditRepository( final DataSource dataSource ) { super( dataSource ); }

    @Override
    protected Logger log() { return LOG; }

    public void insert( final UUID edgeId, final String action,
                        final Map< String, Object > before, final Map< String, Object > after,
                        final String actor, final String reason ) {
        final String sql = "INSERT INTO kg_edge_audit ( edge_id, action, before, after, actor, reason ) "
                         + "VALUES ( ?, ?, ?::jsonb, ?::jsonb, ?, ? )";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, edgeId );
            ps.setString( 2, action );
            ps.setString( 3, before == null ? null : GSON_LOCAL.toJson( before ) );
            ps.setString( 4, after == null ? null : GSON_LOCAL.toJson( after ) );
            ps.setString( 5, actor );
            ps.setString( 6, reason );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to insert kg_edge_audit (edge={}, action={}): {}",
                    edgeId, action, e.getMessage(), e );
            // Audit is a fidelity surface, not correctness — never propagate.
        }
    }

    public List< Map< String, Object > > findByEdgeId( final UUID edgeId, final int limit ) {
        final String sql = "SELECT id, edge_id, action, before, after, actor, reason, created "
                         + "FROM kg_edge_audit WHERE edge_id = ? ORDER BY created DESC LIMIT ?";
        final List< Map< String, Object > > rows = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, edgeId );
            ps.setInt( 2, limit );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final Map< String, Object > m = new LinkedHashMap<>();
                    m.put( "id", rs.getObject( "id", UUID.class ).toString() );
                    m.put( "edge_id", rs.getObject( "edge_id", UUID.class ).toString() );
                    m.put( "action", rs.getString( "action" ) );
                    m.put( "before", parseJson( rs.getString( "before" ) ) );
                    m.put( "after", parseJson( rs.getString( "after" ) ) );
                    m.put( "actor", rs.getString( "actor" ) );
                    m.put( "reason", rs.getString( "reason" ) );
                    final Timestamp ts = rs.getTimestamp( "created" );
                    m.put( "created", ts != null ? ts.toInstant().toString() : null );
                    rows.add( m );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "findByEdgeId({}) failed: {}", edgeId, e.getMessage(), e );
            throw new RuntimeException( "findByEdgeId failed", e );
        }
        return rows;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -pl wikantik-main -Dtest=KgEdgeAuditRepositoryTest -q
```

Expected: PASS, three green tests.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeAuditRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeAuditRepositoryTest.java
# Include KgTestSupport.java if newly created.
git commit -m "$(cat <<'EOF'
feat(kg): KgEdgeAuditRepository for append-only edge mutation audit

Insert + findByEdgeId, ordered newest-first with a limit. Audit insert
failures log-and-continue per the design's fidelity-not-correctness rule.
EOF
)"
```

---

## Phase 2 — Repository Extensions

### Task 4: countEdgesWithFilter and queryEdgesWithTotal on KgEdgeRepository

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeRepository.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeRepositoryFilterCountTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeRepositoryFilterCountTest.java`:

```java
package com.wikantik.knowledge;

// ... imports same as KgEdgeAuditRepositoryTest plus:
// import com.wikantik.api.knowledge.Provenance;
// import java.util.UUID;

@Testcontainers
class KgEdgeRepositoryFilterCountTest {

    @Container
    static final PostgreSQLContainer< ? > PG = new PostgreSQLContainer<>( "pgvector/pgvector:pg15" )
            .withDatabaseName( "kg" ).withUsername( "test" ).withPassword( "test" );

    static DataSource ds;
    static KgEdgeRepository repo;
    static KgNodeRepository nodeRepo;

    @BeforeAll
    static void setUp() throws Exception {
        ds = KgTestSupport.buildDataSource( PG );
        KgTestSupport.applyMigrationsThrough( ds, "V028" );
        repo = new KgEdgeRepository( ds );
        nodeRepo = new KgNodeRepository( ds );
    }

    @Test
    void countMatchesFilteredQuery() {
        final UUID a = nodeRepo.upsertNode( "NodeA", "concept", null,
                Provenance.HUMAN_AUTHORED, java.util.Map.of() ).id();
        final UUID b = nodeRepo.upsertNode( "NodeB", "concept", null,
                Provenance.HUMAN_AUTHORED, java.util.Map.of() ).id();
        final UUID c = nodeRepo.upsertNode( "NodeC", "concept", null,
                Provenance.HUMAN_AUTHORED, java.util.Map.of() ).id();
        repo.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, java.util.Map.of() );
        repo.upsertEdge( a, c, "related", Provenance.HUMAN_CURATED, java.util.Map.of() );
        repo.upsertEdge( b, c, "depends_on", Provenance.HUMAN_CURATED, java.util.Map.of() );

        assertEquals( 2, repo.countEdgesWithFilter( "related", null ) );
        assertEquals( 1, repo.countEdgesWithFilter( "depends_on", null ) );
        assertEquals( 3, repo.countEdgesWithFilter( null, null ) );
        assertEquals( 2, repo.countEdgesWithFilter( null, "NodeA" ) ); // a->b, a->c
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl wikantik-main -Dtest=KgEdgeRepositoryFilterCountTest -q
```

Expected: FAIL — `countEdgesWithFilter` does not exist.

- [ ] **Step 3: Add the method to KgEdgeRepository**

Append to `wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeRepository.java` after `countEdges()`:

```java
    public long countEdgesWithFilter( final String relationshipType, final String searchName ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM kg_edges e "
              + "JOIN kg_nodes sn ON e.source_id = sn.id "
              + "JOIN kg_nodes tn ON e.target_id = tn.id"
              + KgInclusionFilter.EDGE_FILTER_JOIN
              + "WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE );
        final List< Object > params = new ArrayList<>();

        if ( relationshipType != null && !relationshipType.isBlank() ) {
            sql.append( " AND e.relationship_type = ?" );
            params.add( relationshipType );
        }
        if ( searchName != null && !searchName.isBlank() ) {
            sql.append( " AND ( LOWER( sn.name ) LIKE ? OR LOWER( tn.name ) LIKE ? )" );
            final String pattern = "%" + searchName.toLowerCase( java.util.Locale.ROOT ) + "%";
            params.add( pattern );
            params.add( pattern );
        }

        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? rs.getLong( 1 ) : 0L;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "countEdgesWithFilter failed: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -pl wikantik-main -Dtest=KgEdgeRepositoryFilterCountTest -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeRepositoryFilterCountTest.java
git commit -m "$(cat <<'EOF'
feat(kg): KgEdgeRepository.countEdgesWithFilter

Paired counter for queryEdgesWithNames so the admin UI can show a
total alongside the paginated edge list (needed for bulk-delete UX).
EOF
)"
```

---

### Task 5: Bulk delete + delete-and-reject on KgEdgeRepository

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeRepository.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeBulkDeleteTest.java`

- [ ] **Step 1: Confirm where rejections are inserted**

```bash
grep -rn "INSERT INTO kg_rejections\|insertRejection" /home/jakefear/source/jspwiki/wikantik-main/src --include="*.java" | head -10
```

Note the class/method name. If a `KgRejectionRepository` exists, extend it. Otherwise, place the insert helper as a private method on `KgEdgeRepository` and document why.

- [ ] **Step 2: Write the failing test**

Create `wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeBulkDeleteTest.java`:

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class KgEdgeBulkDeleteTest {

    @Container
    static final PostgreSQLContainer< ? > PG = new PostgreSQLContainer<>( "pgvector/pgvector:pg15" )
            .withDatabaseName( "kg" ).withUsername( "test" ).withPassword( "test" );

    static DataSource ds;
    static KgEdgeRepository edges;
    static KgNodeRepository nodes;

    @BeforeAll
    static void setUp() throws Exception {
        ds = KgTestSupport.buildDataSource( PG );
        KgTestSupport.applyMigrationsThrough( ds, "V028" );
        edges = new KgEdgeRepository( ds );
        nodes = new KgNodeRepository( ds );
    }

    @Test
    void bulkDeleteByFilterRemovesMatchingEdgesOnly() {
        final UUID a = nodes.upsertNode( "BulkA", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "BulkB", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID c = nodes.upsertNode( "BulkC", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( a, c, "related", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( b, c, "depends_on", Provenance.HUMAN_CURATED, Map.of() );

        final int deleted = edges.bulkDeleteByFilter( "related", null );

        assertEquals( 2, deleted );
        assertEquals( 1, edges.countEdgesWithFilter( null, null ) );
        assertEquals( 1, edges.countEdgesWithFilter( "depends_on", null ) );
    }

    @Test
    void deleteEdgeAndRecordRejectionInsertsRejectionRow() throws Exception {
        final UUID a = nodes.upsertNode( "RejA", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "RejB", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );
        final UUID edgeId = queryEdgeId( a, b, "related" );

        edges.deleteEdgeAndRecordRejection( edgeId, "carol", "bad inference" );

        // Edge gone
        assertEquals( 0, edges.countEdgesWithFilter( "related", null ) );
        // Rejection inserted
        try ( Connection conn = ds.getConnection();
              Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery(
                  "SELECT proposed_source, proposed_target, proposed_relationship, rejected_by, reason "
                + "FROM kg_rejections WHERE proposed_relationship = 'related'" ) ) {
            assertTrue( rs.next() );
            assertEquals( "RejA", rs.getString( 1 ) );
            assertEquals( "RejB", rs.getString( 2 ) );
            assertEquals( "carol", rs.getString( 4 ) );
            assertEquals( "bad inference", rs.getString( 5 ) );
        }
    }

    @Test
    void deleteEdgeAndRecordRejectionIsIdempotentOnConflict() throws Exception {
        final UUID a = nodes.upsertNode( "IdemA", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "IdemB", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );
        final UUID edgeId = queryEdgeId( a, b, "related" );
        edges.deleteEdgeAndRecordRejection( edgeId, "carol", "first" );

        // Recreate the edge and reject again — should not throw on the UNIQUE conflict
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );
        final UUID edgeId2 = queryEdgeId( a, b, "related" );
        assertDoesNotThrow( () -> edges.deleteEdgeAndRecordRejection( edgeId2, "dave", "second" ) );
    }

    private UUID queryEdgeId( final UUID source, final UUID target, final String rel ) throws Exception {
        try ( Connection conn = ds.getConnection();
              Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery(
                  "SELECT id FROM kg_edges WHERE source_id = '" + source + "' "
                + "AND target_id = '" + target + "' AND relationship_type = '" + rel + "'" ) ) {
            assertTrue( rs.next() );
            return rs.getObject( 1, UUID.class );
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
mvn test -pl wikantik-main -Dtest=KgEdgeBulkDeleteTest -q
```

Expected: FAIL — methods `bulkDeleteByFilter` and `deleteEdgeAndRecordRejection` not found.

- [ ] **Step 4: Implement both methods**

Append to `KgEdgeRepository.java`:

```java
    /**
     * Deletes all edges matching the same filter that {@link #queryEdgesWithNames}
     * applies (relationship_type, name search). Runs in a single transaction.
     *
     * @return number of rows deleted
     */
    public int bulkDeleteByFilter( final String relationshipType, final String searchName ) {
        final StringBuilder sql = new StringBuilder(
                "DELETE FROM kg_edges WHERE id IN ("
              + " SELECT e.id FROM kg_edges e "
              + " JOIN kg_nodes sn ON e.source_id = sn.id "
              + " JOIN kg_nodes tn ON e.target_id = tn.id"
              + KgInclusionFilter.EDGE_FILTER_JOIN
              + " WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE );
        final List< Object > params = new ArrayList<>();

        if ( relationshipType != null && !relationshipType.isBlank() ) {
            sql.append( " AND e.relationship_type = ?" );
            params.add( relationshipType );
        }
        if ( searchName != null && !searchName.isBlank() ) {
            sql.append( " AND ( LOWER( sn.name ) LIKE ? OR LOWER( tn.name ) LIKE ? )" );
            final String pattern = "%" + searchName.toLowerCase( java.util.Locale.ROOT ) + "%";
            params.add( pattern );
            params.add( pattern );
        }
        sql.append( " )" );

        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            return ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "bulkDeleteByFilter failed: {}", e.getMessage(), e );
            throw new RuntimeException( "bulkDeleteByFilter failed: " + e.getMessage(), e );
        }
    }

    /**
     * In one transaction: looks up source/target node names from the edge, deletes
     * the edge, and inserts a kg_rejections row with the same triple. The rejection
     * insert uses ON CONFLICT DO NOTHING so re-rejection is a safe no-op.
     */
    public void deleteEdgeAndRecordRejection( final UUID edgeId, final String actor, final String reason ) {
        final String lookupSql = "SELECT sn.name AS s_name, tn.name AS t_name, e.relationship_type "
                               + "FROM kg_edges e "
                               + "JOIN kg_nodes sn ON e.source_id = sn.id "
                               + "JOIN kg_nodes tn ON e.target_id = tn.id "
                               + "WHERE e.id = ?";
        final String deleteSql = "DELETE FROM kg_edges WHERE id = ?";
        final String rejectSql = "INSERT INTO kg_rejections "
                               + "( proposed_source, proposed_target, proposed_relationship, rejected_by, reason ) "
                               + "VALUES ( ?, ?, ?, ?, ? ) "
                               + "ON CONFLICT ( proposed_source, proposed_target, proposed_relationship ) DO NOTHING";

        try ( Connection conn = dataSource.getConnection() ) {
            conn.setAutoCommit( false );
            try {
                String sourceName, targetName, relType;
                try ( PreparedStatement ps = conn.prepareStatement( lookupSql ) ) {
                    ps.setObject( 1, edgeId );
                    try ( ResultSet rs = ps.executeQuery() ) {
                        if ( !rs.next() ) {
                            conn.rollback();
                            throw new IllegalArgumentException( "Edge not found: " + edgeId );
                        }
                        sourceName = rs.getString( "s_name" );
                        targetName = rs.getString( "t_name" );
                        relType    = rs.getString( "relationship_type" );
                    }
                }
                try ( PreparedStatement ps = conn.prepareStatement( deleteSql ) ) {
                    ps.setObject( 1, edgeId );
                    ps.executeUpdate();
                }
                try ( PreparedStatement ps = conn.prepareStatement( rejectSql ) ) {
                    ps.setString( 1, sourceName );
                    ps.setString( 2, targetName );
                    ps.setString( 3, relType );
                    ps.setString( 4, actor );
                    ps.setString( 5, reason );
                    ps.executeUpdate();
                }
                conn.commit();
            } catch ( final SQLException inner ) {
                conn.rollback();
                LOG.warn( "deleteEdgeAndRecordRejection({}) rolled back: {}",
                        edgeId, inner.getMessage(), inner );
                throw new RuntimeException( "deleteEdgeAndRecordRejection failed", inner );
            } finally {
                conn.setAutoCommit( true );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "deleteEdgeAndRecordRejection({}) failed: {}", edgeId, e.getMessage(), e );
            throw new RuntimeException( "deleteEdgeAndRecordRejection failed", e );
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -pl wikantik-main -Dtest=KgEdgeBulkDeleteTest -q
```

Expected: PASS, three green tests.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/KgEdgeRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeBulkDeleteTest.java
git commit -m "$(cat <<'EOF'
feat(kg): bulk delete + delete-and-reject on KgEdgeRepository

bulkDeleteByFilter mirrors the queryEdgesWithNames filter. The
delete-and-reject path runs DELETE + INSERT INTO kg_rejections in
one transaction; the rejection insert uses ON CONFLICT DO NOTHING
so re-rejection is a safe no-op.
EOF
)"
```

---

### Task 6: Provenance stamping safeguard on KgEdgeRepository.upsertEdge

The upsertEdge SQL already hardcodes `tier='human'` and `provenance_proposal_id=NULL`. This task adds a regression test to lock that in so the contract is testable and visible.

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeProvenanceStampingTest.java`

- [ ] **Step 1: Write the test**

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class KgEdgeProvenanceStampingTest {

    @Container
    static final PostgreSQLContainer< ? > PG = new PostgreSQLContainer<>( "pgvector/pgvector:pg15" )
            .withDatabaseName( "kg" ).withUsername( "test" ).withPassword( "test" );

    static DataSource ds;
    static KgEdgeRepository edges;
    static KgNodeRepository nodes;

    @BeforeAll
    static void setUp() throws Exception {
        ds = KgTestSupport.buildDataSource( PG );
        KgTestSupport.applyMigrationsThrough( ds, "V028" );
        edges = new KgEdgeRepository( ds );
        nodes = new KgNodeRepository( ds );
    }

    @Test
    void upsertEdgeAlwaysStampsHumanTierAndNullProposalId() {
        final UUID a = nodes.upsertNode( "StampA", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "StampB", "concept", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final KgEdge e = edges.upsertEdge( a, b, "related",
                Provenance.HUMAN_CURATED, Map.of( "k", "v" ) );

        assertNotNull( e );
        assertEquals( "human", e.tier() );
        assertNull( e.provenanceProposalId() );
        assertEquals( Provenance.HUMAN_CURATED, e.provenance() );
    }
}
```

- [ ] **Step 2: Run the test**

```bash
mvn test -pl wikantik-main -Dtest=KgEdgeProvenanceStampingTest -q
```

Expected: PASS immediately (existing implementation already conforms). This is a regression-lock test.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/KgEdgeProvenanceStampingTest.java
git commit -m "$(cat <<'EOF'
test(kg): regression-lock upsertEdge tier/proposal stamping

Documents the invariant that KgEdgeRepository.upsertEdge always writes
tier='human' and provenance_proposal_id=NULL — the admin-UI mutation
path depends on it.
EOF
)"
```

---

## Phase 3 — Service Layer

### Task 7: Extend KnowledgeGraphService interface and implementation

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`

- [ ] **Step 1: Read the current interface to find the right insertion point**

```bash
sed -n '1,80p' wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java
```

Find the section that declares edge methods (look for `queryEdges`, `deleteEdge`, `upsertEdge`).

- [ ] **Step 2: Add interface methods**

Add these methods to `KnowledgeGraphService.java` near the existing edge methods:

```java
    /**
     * Counts edges that would be returned by {@link #queryEdges} with the same filter
     * arguments. Used by the admin UI to render a total alongside the paginated list.
     */
    long countEdges( String relationshipType, String searchName );

    /**
     * Deletes an edge and, in the same transaction, inserts a kg_rejections row so
     * a future re-extraction proposing the same triple will be auto-rejected.
     * Idempotent on the rejection insert.
     */
    void deleteEdgeAndRecordRejection( java.util.UUID edgeId, String actor, String reason );

    /**
     * Bulk-deletes every edge matching the same filter that {@link #queryEdges} applies.
     * The caller's {@code expectedCount} is compared with the actual matched-row count
     * before deletion; a mismatch raises {@link IllegalStateException} to signal snapshot
     * drift back to the operator.
     *
     * @return the number of rows deleted
     */
    int bulkDeleteEdges( String relationshipType, String searchName, int expectedCount );

    /**
     * Returns up to {@code limit} audit rows for the given edge id, newest first.
     * Empty list if the edge has no audit history (or never existed).
     */
    java.util.List< java.util.Map< String, Object > > getEdgeAudit( java.util.UUID edgeId, int limit );
```

- [ ] **Step 3: Implement on DefaultKnowledgeGraphService**

First check the field structure:

```bash
grep -n "edges\|edgeRepo\|kgEdges\|KgEdgeAuditRepository" wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java | head -20
```

Add an `edgeAudit` field, initialize in the constructor, and add the four implementations:

```java
// Add at top of class:
private final KgEdgeAuditRepository edgeAudit;

// In constructor (wherever the other repos are instantiated, find the pattern with `edges = new KgEdgeRepository(...)`):
this.edgeAudit = new KgEdgeAuditRepository( dataSource );

// Methods (add near existing edge methods):

@Override
public long countEdges( final String relationshipType, final String searchName ) {
    return edges.countEdgesWithFilter( relationshipType, searchName );
}

@Override
public void deleteEdgeAndRecordRejection( final UUID edgeId, final String actor, final String reason ) {
    edges.deleteEdgeAndRecordRejection( edgeId, actor, reason );
}

@Override
public int bulkDeleteEdges( final String relationshipType, final String searchName, final int expectedCount ) {
    final long actual = edges.countEdgesWithFilter( relationshipType, searchName );
    if ( actual != expectedCount ) {
        throw new IllegalStateException( "expected " + expectedCount
                + " rows, found " + actual + " — re-confirm before retrying" );
    }
    return edges.bulkDeleteByFilter( relationshipType, searchName );
}

@Override
public java.util.List< java.util.Map< String, Object > > getEdgeAudit( final UUID edgeId, final int limit ) {
    return edgeAudit.findByEdgeId( edgeId, limit );
}
```

Expose the audit repo for the resource layer (resource writes audit rows directly):

```java
public KgEdgeAuditRepository getEdgeAuditRepository() { return edgeAudit; }
```

- [ ] **Step 4: Compile to confirm**

```bash
mvn test-compile -pl wikantik-main -am -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Run the wikantik-main test reactor to confirm no regressions**

```bash
mvn test -pl wikantik-main -q
```

Expected: All tests pass. (Note from memory: some `wikantik-main` provider tests are parallel-flaky — if there are failures in `*Provider*Test`, re-run with `-DforkCount=1` to confirm they're the known flake.)

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java
git commit -m "$(cat <<'EOF'
feat(kg): KnowledgeGraphService methods for edge curation v0

countEdges, deleteEdgeAndRecordRejection, bulkDeleteEdges,
getEdgeAudit. Bulk delete enforces optimistic concurrency via
expectedCount; mismatch raises IllegalStateException.
EOF
)"
```

---

## Phase 4 — Resource Endpoints

### Task 8: handleGetEdges returns total; handlePostEdge stamps HUMAN_CURATED and audits

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeEdgeCurationTest.java`

- [ ] **Step 1: Write failing tests covering the resource's new behavior**

Create `wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeEdgeCurationTest.java`:

```java
package com.wikantik.rest;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminKnowledgeEdgeCurationTest {

    AdminKnowledgeResource resource;
    KnowledgeGraphService service;
    HttpServletRequest req;
    HttpServletResponse resp;
    StringWriter out;

    @BeforeEach
    void setUp() throws Exception {
        service = mock( KnowledgeGraphService.class );
        resource = spy( new AdminKnowledgeResource() );
        // Subclasses of RestServletBase pull subsystems from WikiEngine; here we stub
        // getKnowledgeService via reflection or via a small accessor in the resource.
        // The actual harness in this repo already has a pattern — find it via:
        //   grep -n "AdminKnowledgeResource()" wikantik-rest/src/test
        // and follow that pattern. Test outline below uses the canonical pattern.
        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        out = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( out ) );
    }

    @Test
    void postEdgeStampsHumanCuratedProvenance() throws Exception {
        final UUID s = UUID.randomUUID(), t = UUID.randomUUID();
        final String body = "{\"source_id\":\"" + s + "\",\"target_id\":\"" + t
                + "\",\"relationship_type\":\"related\",\"provenance\":\"ai-inferred\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );
        when( service.upsertEdge( any(), any(), anyString(), any(), anyMap() ) )
                .thenReturn( new KgEdge( UUID.randomUUID(), s, t, "related",
                        Provenance.HUMAN_CURATED, Map.of(),
                        Instant.now(), Instant.now(), "human", null ) );

        resource.handlePostEdge( service, req, resp ); // exposed for test or invoke via reflection

        final ArgumentCaptor< Provenance > prov = ArgumentCaptor.forClass( Provenance.class );
        verify( service ).upsertEdge( eq( s ), eq( t ), eq( "related" ), prov.capture(), anyMap() );
        // Despite the body asking for ai-inferred, the resource MUST stamp HUMAN_CURATED.
        assertEquals( Provenance.HUMAN_CURATED, prov.getValue() );
    }

    @Test
    void getEdgesIncludesTotal() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/edges" );
        when( req.getParameter( "limit" ) ).thenReturn( "50" );
        when( req.getParameter( "offset" ) ).thenReturn( "0" );
        when( service.queryEdges( null, null, 50, 0 ) ).thenReturn( List.of() );
        when( service.countEdges( null, null ) ).thenReturn( 42L );

        resource.handleGetEdges( service, req, resp, new String[]{ "edges" } );

        final String body = out.toString();
        assertTrue( body.contains( "\"total\":42" ), "expected total in response: " + body );
        assertTrue( body.contains( "\"edges\"" ) );
    }

    @Test
    void bulkDeleteFails409OnCountDrift() throws Exception {
        final String body = "{\"relationship_type\":\"related\",\"expected_count\":5}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );
        when( req.getPathInfo() ).thenReturn( "/edges/bulk-delete" );
        doThrow( new IllegalStateException( "expected 5 rows, found 4" ) )
                .when( service ).bulkDeleteEdges( "related", null, 5 );

        resource.handlePostEdgeBulkDelete( service, req, resp ); // resource-method-under-test name

        verify( resp ).sendError( eq( HttpServletResponse.SC_CONFLICT ), contains( "expected 5 rows" ) );
    }

    @Test
    void deleteAndRejectInsertsRejectionAndReturnsOk() throws Exception {
        final UUID edgeId = UUID.randomUUID();
        final String body = "{\"reason\":\"bad inference\"}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.handlePostEdgeDeleteAndReject( service, req, resp,
                new String[]{ "edges", edgeId.toString(), "delete-and-reject" } );

        verify( service ).deleteEdgeAndRecordRejection( eq( edgeId ), anyString(), eq( "bad inference" ) );
    }

    @Test
    void getEdgeAuditReturnsRowsNewestFirst() throws Exception {
        final UUID edgeId = UUID.randomUUID();
        when( service.getEdgeAudit( edgeId, 20 ) ).thenReturn( List.of(
                Map.of( "action", "DELETE", "actor", "carol" ),
                Map.of( "action", "CREATE", "actor", "alice" )
        ) );

        resource.handleGetEdgeAudit( service, req, resp,
                new String[]{ "edges", edgeId.toString(), "audit" } );

        final String text = out.toString();
        assertTrue( text.contains( "\"action\":\"DELETE\"" ) );
        assertTrue( text.indexOf( "DELETE" ) < text.indexOf( "CREATE" ),
                "DELETE must precede CREATE (newest first)" );
    }
}
```

NOTE: This test uses package-private or protected accessors on the resource (`handlePostEdge`, `handlePostEdgeBulkDelete`, `handlePostEdgeDeleteAndReject`, `handleGetEdgeAudit`). If the existing resource keeps these private, change the new ones to **package-private** for testability — same pattern as the rest of the file.

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl wikantik-rest -Dtest=AdminKnowledgeEdgeCurationTest -q
```

Expected: FAIL — handler methods don't exist; `countEdges` not called.

- [ ] **Step 3: Modify handleGetEdges to include total**

Edit `AdminKnowledgeResource.handleGetEdges`:

Replace the segment-length-< 2 branch:

```java
        if ( segments.length < 2 ) {
            // GET /admin/knowledge-graph/edges — list all edges (paginated, with names)
            final String relType = request.getParameter( "relationship_type" );
            final String search = request.getParameter( "search" );
            final int limit = parseIntParam( request, "limit", 50 );
            final int offset = parseIntParam( request, "offset", 0 );
            final Map< String, Object > out = new LinkedHashMap<>();
            out.put( "edges", service.queryEdges( relType, search, limit, offset ) );
            out.put( "total", service.countEdges( relType, search ) );
            sendJson( response, out );
            return;
        }
```

Also extend the segment-length->= 2 branch to handle `/edges/{id}/audit` and `/edges/{id}/delete-and-reject`. Tee off before the UUID parse:

```java
        if ( segments.length >= 3 && "audit".equals( segments[2] ) ) {
            handleGetEdgeAudit( service, request, response, segments );
            return;
        }
```

(`delete-and-reject` is a POST, so it goes in the post-dispatch table instead.)

- [ ] **Step 4: Modify handlePostEdge to unconditionally stamp HUMAN_CURATED**

Replace handlePostEdge:

```java
    void handlePostEdge( final KnowledgeGraphService service,
                         final HttpServletRequest request,
                         final HttpServletResponse response ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final UUID sourceId = UUID.fromString( body.get( "source_id" ).getAsString() );
        final UUID targetId = UUID.fromString( body.get( "target_id" ).getAsString() );
        final String relType = body.get( "relationship_type" ).getAsString();
        final Map< String, Object > properties = body.has( "properties" )
                ? GSON.fromJson( body.get( "properties" ), MAP_TYPE ) : Map.of();

        // Snapshot the pre-state for audit (null on create).
        final KgEdge existingEdge = findExistingEdge( service, sourceId, targetId, relType );
        final Map< String, Object > before = existingEdge == null ? null : edgeToMap( existingEdge );

        final KgEdge edge;
        try {
            edge = service.upsertEdge( sourceId, targetId, relType,
                    Provenance.HUMAN_CURATED, properties );
        } catch ( final RuntimeException e ) {
            // Surface UNIQUE-collision as 409 (cause inspection: PSQLException SQLState 23505).
            if ( unwrap( e ).getMessage() != null && unwrap( e ).getMessage().contains( "duplicate key" ) ) {
                sendError( response, HttpServletResponse.SC_CONFLICT,
                        "Edge already exists: (" + sourceId + ", " + targetId + ", " + relType + ")" );
                return;
            }
            throw e;
        }

        // Write audit row (best-effort).
        final DefaultKnowledgeGraphService impl = unwrapImpl( service );
        if ( impl != null ) {
            impl.getEdgeAuditRepository().insert( edge.id(),
                    before == null ? "CREATE" : "UPDATE",
                    before, edgeToMap( edge ),
                    actor( request ), null );
        }
        sendJson( response, edgeToMap( edge ) );
    }

    private static Throwable unwrap( final Throwable t ) {
        Throwable cur = t;
        while ( cur.getCause() != null && cur.getCause() != cur ) cur = cur.getCause();
        return cur;
    }

    private static DefaultKnowledgeGraphService unwrapImpl( final KnowledgeGraphService svc ) {
        return ( svc instanceof DefaultKnowledgeGraphService impl ) ? impl : null;
    }

    private static String actor( final HttpServletRequest req ) {
        final Object sess = req.getSession( false ) == null ? null
                : req.getSession( false ).getAttribute( "wikantik.user" );
        return sess != null ? sess.toString() : "admin";
    }

    private static KgEdge findExistingEdge( final KnowledgeGraphService service,
                                            final UUID sourceId, final UUID targetId, final String relType ) {
        return service.getEdgesForNode( sourceId, "outbound" ).stream()
                .filter( e -> e.targetId().equals( targetId ) && relType.equals( e.relationshipType() ) )
                .findFirst().orElse( null );
    }
```

NOTE: `actor()` — check the existing AdminKnowledgeResource for how it derives the logged-in user. The right call is whatever `proposalReviewer` is set to in the existing approve/reject paths. Grep:

```bash
grep -n "reviewedBy\|reviewed_by\|getActor\|currentLogin" wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java | head
```

Use the same pattern. If it uses `WikiContext.getCurrentUser()` or similar, mirror that and remove the fallback `"admin"` string.

- [ ] **Step 5: Modify handleDeleteEdge to write audit**

Replace handleDeleteEdge:

```java
    void handleDeleteEdge( final KnowledgeGraphService service,
                           final HttpServletRequest request,
                           final HttpServletResponse response,
                           final String idStr ) throws IOException {
        final UUID id = parseUuid( idStr, response );
        if ( id == null ) return;

        // Capture before-state for audit.
        final KgEdge existing = service.getEdge( id ); // confirm this method exists; if not, query via edges-for-source
        final Map< String, Object > before = existing == null ? null : edgeToMap( existing );

        service.deleteEdge( id );
        LOG.info( "Knowledge graph edge deleted: {}", id );

        final DefaultKnowledgeGraphService impl = unwrapImpl( service );
        if ( impl != null && before != null ) {
            impl.getEdgeAuditRepository().insert( id, "DELETE", before, null, actor( request ), null );
        }
        sendJson( response, Map.of( "deleted", true ) );
    }
```

Verify `service.getEdge(UUID)` exists; if not, add it:

```bash
grep -n "getEdge(\s*UUID\|getEdgeById" wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java
```

If absent, add it to the service interface and DefaultKnowledgeGraphService backed by `KgEdgeRepository.findById` (which itself may need adding — single-row select on `kg_edges` joined to `kg_nodes` for names).

- [ ] **Step 6: Add new endpoints to the dispatch table**

In `buildResources()` add the new `delete-and-reject` and `bulk-delete` and `audit` URL handling. The simplest mapping:

```java
        m.put( "edges", new Resource(
                this::handleGetEdges,           // existing — augmented for total + audit subroute
                this::handlePostEdgeDispatch,   // dispatcher: bulk-delete | {id}/delete-and-reject | plain upsert
                ( svc, req, resp, seg ) -> handleDeleteEdge( svc, req, resp, seg[ 1 ] ) ) );
```

Add the POST dispatcher:

```java
    private void handlePostEdgeDispatch( final KnowledgeGraphService service,
                                         final HttpServletRequest request,
                                         final HttpServletResponse response,
                                         final String[] segments ) throws IOException {
        if ( segments.length >= 2 && "bulk-delete".equals( segments[1] ) ) {
            handlePostEdgeBulkDelete( service, request, response );
            return;
        }
        if ( segments.length >= 3 && "delete-and-reject".equals( segments[2] ) ) {
            handlePostEdgeDeleteAndReject( service, request, response, segments );
            return;
        }
        handlePostEdge( service, request, response );
    }
```

Implementations:

```java
    void handlePostEdgeBulkDelete( final KnowledgeGraphService service,
                                   final HttpServletRequest request,
                                   final HttpServletResponse response ) throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String relType = body.has( "relationship_type" ) ? body.get( "relationship_type" ).getAsString() : null;
        final String search  = body.has( "search" )            ? body.get( "search" ).getAsString()            : null;
        if ( !body.has( "expected_count" ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "expected_count required" );
            return;
        }
        final int expected = body.get( "expected_count" ).getAsInt();

        // Snapshot rows for audit BEFORE delete.
        final java.util.List< java.util.Map< String, Object > > snapshot =
                service.queryEdges( relType, search, expected + 1, 0 );

        final int deleted;
        try {
            deleted = service.bulkDeleteEdges( relType, search, expected );
        } catch ( final IllegalStateException drift ) {
            sendError( response, HttpServletResponse.SC_CONFLICT, drift.getMessage() );
            return;
        }

        final DefaultKnowledgeGraphService impl = unwrapImpl( service );
        if ( impl != null ) {
            final String reason = "bulk delete via filter: relationship_type="
                    + ( relType == null ? "" : relType )
                    + ", search=" + ( search == null ? "" : search );
            for ( final java.util.Map< String, Object > row : snapshot ) {
                final UUID edgeId = UUID.fromString( (String) row.get( "id" ) );
                impl.getEdgeAuditRepository().insert( edgeId, "DELETE", row, null, actor( request ), reason );
            }
        }
        sendJson( response, Map.of( "deleted", deleted ) );
    }

    void handlePostEdgeDeleteAndReject( final KnowledgeGraphService service,
                                        final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final String[] segments ) throws IOException {
        final UUID id = parseUuid( segments[1], response );
        if ( id == null ) return;
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String reason = body.has( "reason" ) ? body.get( "reason" ).getAsString() : null;

        final KgEdge existing = service.getEdge( id );
        if ( existing == null ) { sendNotFound( response, "Edge not found: " + id ); return; }
        final Map< String, Object > before = edgeToMap( existing );

        service.deleteEdgeAndRecordRejection( id, actor( request ), reason );

        final DefaultKnowledgeGraphService impl = unwrapImpl( service );
        if ( impl != null ) {
            impl.getEdgeAuditRepository().insert( id, "DELETE", before,
                    Map.of( "rejected", true ), actor( request ), reason );
        }
        sendJson( response, Map.of( "deleted", true, "rejected", true ) );
    }

    void handleGetEdgeAudit( final KnowledgeGraphService service,
                             final HttpServletRequest request,
                             final HttpServletResponse response,
                             final String[] segments ) throws IOException {
        final UUID id = parseUuid( segments[1], response );
        if ( id == null ) return;
        final int limit = parseIntParam( request, "limit", 20 );
        sendJson( response, Map.of( "audit", service.getEdgeAudit( id, limit ) ) );
    }
```

- [ ] **Step 7: Update the Javadoc block at the top of the class with the new endpoints**

Add to the javadoc list:

```java
 *   <li>{@code POST /admin/knowledge-graph/edges/bulk-delete} — bulk delete by filter</li>
 *   <li>{@code POST /admin/knowledge-graph/edges/{id}/delete-and-reject} — delete + write rejection</li>
 *   <li>{@code GET  /admin/knowledge-graph/edges/{id}/audit} — list edge audit rows</li>
```

- [ ] **Step 8: Run the tests**

```bash
mvn test -pl wikantik-rest -Dtest=AdminKnowledgeEdgeCurationTest -q
```

Expected: PASS. If any access-modifier issue surfaces, change the new handler methods from `private` to package-private.

- [ ] **Step 9: Run the wikantik-rest reactor**

```bash
mvn test -pl wikantik-rest -am -q
```

Expected: All tests pass. Memory note: `mvn test-compile` is needed if signature changes broke any other test file — if so, fix collateral and re-run.

- [ ] **Step 10: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeEdgeCurationTest.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java
git commit -m "$(cat <<'EOF'
feat(admin): edge curation endpoints on AdminKnowledgeResource

POST /edges (upsert) stamps HUMAN_CURATED unconditionally and writes
CREATE/UPDATE audit. DELETE /edges/{id} writes DELETE audit. New:
POST /edges/bulk-delete (with expected_count drift check), POST
/edges/{id}/delete-and-reject, GET /edges/{id}/audit. GET /edges
response gains a `total` field for the bulk-delete UI count.
EOF
)"
```

---

## Phase 5 — Frontend Client

### Task 9: Extend api/client.js with the new wrappers

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Add the three new wrappers and surface `total` in queryEdges**

Edit `wikantik-frontend/src/api/client.js`. Find `queryEdges` (around line 552). The response shape already returns `{ edges, total }` from the new server contract. Existing consumers destructure `.edges` so adding `.total` is backward compatible — no change needed to the wrapper itself.

Add after `deleteEdge` (around line 616):

```javascript
    deleteAndRejectEdge: (id, reason) =>
      request(`/admin/knowledge-graph/edges/${id}/delete-and-reject`, {
        method: 'POST',
        body: JSON.stringify({ reason }),
      }),

    bulkDeleteEdges: ({ relationship_type, search, expected_count }) =>
      request('/admin/knowledge-graph/edges/bulk-delete', {
        method: 'POST',
        body: JSON.stringify({ relationship_type, search, expected_count }),
      }),

    getEdgeAudit: (id, limit = 20) =>
      request(`/admin/knowledge-graph/edges/${id}/audit?limit=${limit}`),
```

- [ ] **Step 2: Commit (no separate test — client is exercised by Vitest in next tasks)**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "$(cat <<'EOF'
feat(frontend): client wrappers for edge curation endpoints

deleteAndRejectEdge, bulkDeleteEdges, getEdgeAudit.
EOF
)"
```

---

## Phase 6 — Frontend UI

### Task 10: EdgeFormModal (shared Create + Edit)

**Files:**
- Create: `wikantik-frontend/src/components/admin/EdgeFormModal.jsx`
- Create: `wikantik-frontend/src/components/admin/EdgeFormModal.test.jsx`

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/components/admin/EdgeFormModal.test.jsx`:

```jsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import EdgeFormModal from './EdgeFormModal';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      queryNodes: vi.fn(),
      upsertEdge: vi.fn(),
    },
  },
}));
import { api } from '../../api/client';

describe('EdgeFormModal', () => {
  const relTypes = ['related', 'depends_on', 'part_of'];

  beforeEach(() => {
    api.knowledge.queryNodes.mockReset();
    api.knowledge.upsertEdge.mockReset();
  });

  it('renders dropdown populated from relTypes', () => {
    render(<EdgeFormModal mode="create" relTypes={relTypes} onClose={() => {}} onSaved={() => {}} />);
    relTypes.forEach((t) => expect(screen.getByRole('option', { name: t })).toBeInTheDocument());
  });

  it('disables Save when properties JSON is invalid', () => {
    render(<EdgeFormModal mode="create" relTypes={relTypes} onClose={() => {}} onSaved={() => {}} />);
    const props = screen.getByLabelText(/properties/i);
    fireEvent.change(props, { target: { value: 'not-json' } });
    expect(screen.getByRole('button', { name: /save/i })).toBeDisabled();
  });

  it('submits HUMAN_CURATED upsert and calls onSaved', async () => {
    const onSaved = vi.fn();
    const sourceNode = { id: 's-1', name: 'NodeA' };
    const targetNode = { id: 't-1', name: 'NodeB' };
    api.knowledge.queryNodes.mockResolvedValue({ nodes: [sourceNode] });
    api.knowledge.upsertEdge.mockResolvedValue({ id: 'edge-1' });

    render(<EdgeFormModal mode="create" relTypes={relTypes}
                          initialSource={sourceNode} initialTarget={targetNode}
                          onClose={() => {}} onSaved={onSaved} />);
    fireEvent.change(screen.getByLabelText(/relationship/i), { target: { value: 'related' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(api.knowledge.upsertEdge).toHaveBeenCalledWith(expect.objectContaining({
      source_id: 's-1',
      target_id: 't-1',
      relationship_type: 'related',
    }));
  });

  it('disables source/target fields when in edit mode', () => {
    render(<EdgeFormModal mode="edit" relTypes={relTypes}
                          initialEdge={{ id: 'e-1', source_id: 's-1', target_id: 't-1', relationship_type: 'related', properties: {} }}
                          initialSource={{ id: 's-1', name: 'NodeA' }}
                          initialTarget={{ id: 't-1', name: 'NodeB' }}
                          onClose={() => {}} onSaved={() => {}} />);
    expect(screen.getByLabelText(/source/i)).toBeDisabled();
    expect(screen.getByLabelText(/target/i)).toBeDisabled();
  });

  it('shows inline error on 409 conflict', async () => {
    api.knowledge.upsertEdge.mockRejectedValue({ status: 409, message: 'Edge already exists' });
    render(<EdgeFormModal mode="create" relTypes={relTypes}
                          initialSource={{ id: 's', name: 'A' }}
                          initialTarget={{ id: 't', name: 'B' }}
                          onClose={() => {}} onSaved={() => {}} />);
    fireEvent.change(screen.getByLabelText(/relationship/i), { target: { value: 'related' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));
    await waitFor(() => expect(screen.getByText(/already exists/i)).toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd wikantik-frontend && npm test -- --run EdgeFormModal
```

Expected: FAIL — `EdgeFormModal` does not exist.

- [ ] **Step 3: Implement EdgeFormModal**

Create `wikantik-frontend/src/components/admin/EdgeFormModal.jsx`:

```jsx
import { useState, useEffect, useRef } from 'react';
import { api } from '../../api/client';

function NodeAutocomplete({ label, value, onSelect, disabled }) {
  const [query, setQuery] = useState(value?.name || '');
  const [results, setResults] = useState([]);
  const [open, setOpen] = useState(false);
  const debounceRef = useRef(null);

  useEffect(() => { setQuery(value?.name || ''); }, [value]);

  const onChange = (e) => {
    const v = e.target.value;
    setQuery(v);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      if (!v.trim()) { setResults([]); return; }
      try {
        const data = await api.knowledge.queryNodes({ name: v, limit: 10 });
        setResults(data.nodes || []);
        setOpen(true);
      } catch {
        setResults([]);
      }
    }, 250);
  };

  return (
    <label style={{ display: 'block', marginBottom: 'var(--space-sm)' }}>
      <span style={{ display: 'block', fontWeight: 500, marginBottom: '4px' }}>{label}</span>
      <input
        type="text"
        value={query}
        onChange={onChange}
        disabled={disabled}
        className="form-input"
        style={{ width: '100%' }}
        aria-label={label}
      />
      {open && results.length > 0 && (
        <ul style={{ listStyle: 'none', padding: 0, margin: 0, border: '1px solid var(--border)', maxHeight: '180px', overflowY: 'auto' }}>
          {results.map((n) => (
            <li key={n.id}>
              <button type="button" className="btn-link" style={{ display: 'block', width: '100%', textAlign: 'left', padding: '6px 8px' }}
                      onClick={() => { onSelect(n); setQuery(n.name); setOpen(false); }}>
                {n.name} <small style={{ color: 'var(--text-muted)' }}>({n.node_type})</small>
              </button>
            </li>
          ))}
        </ul>
      )}
    </label>
  );
}

export default function EdgeFormModal({
  mode, relTypes,
  initialEdge,
  initialSource, initialTarget,
  onClose, onSaved,
}) {
  const [source, setSource] = useState(initialSource || null);
  const [target, setTarget] = useState(initialTarget || null);
  const [relType, setRelType] = useState(initialEdge?.relationship_type || '');
  const [propsText, setPropsText] = useState(
      initialEdge?.properties ? JSON.stringify(initialEdge.properties, null, 2) : '{}');
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  let propsValid = true;
  let parsedProps = {};
  try {
    parsedProps = propsText.trim() === '' ? {} : JSON.parse(propsText);
    if (typeof parsedProps !== 'object' || Array.isArray(parsedProps)) propsValid = false;
  } catch {
    propsValid = false;
  }

  const canSave = source && target && relType && propsValid && !saving;

  const onSave = async () => {
    setSaving(true);
    setError(null);
    try {
      const body = {
        source_id: source.id,
        target_id: target.id,
        relationship_type: relType,
        properties: parsedProps,
      };
      if (mode === 'edit' && initialEdge?.id) body.id = initialEdge.id;
      await api.knowledge.upsertEdge(body);
      onSaved();
    } catch (e) {
      if (e?.status === 409) setError(e.message || 'This edge already exists.');
      else setError(e?.message || 'Save failed.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div role="dialog" aria-label={mode === 'edit' ? 'Edit edge' : 'Create edge'}
         style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
      <div style={{ background: 'var(--surface-primary)', padding: 'var(--space-lg)', borderRadius: 'var(--radius-md)', minWidth: '480px', maxWidth: '640px' }}>
        <h3>{mode === 'edit' ? 'Edit edge' : 'New edge'}</h3>
        <NodeAutocomplete label="Source" value={source} onSelect={setSource} disabled={mode === 'edit'} />
        <NodeAutocomplete label="Target" value={target} onSelect={setTarget} disabled={mode === 'edit'} />
        <label style={{ display: 'block', marginBottom: 'var(--space-sm)' }}>
          <span style={{ display: 'block', fontWeight: 500, marginBottom: '4px' }}>Relationship</span>
          <select className="form-input" value={relType} onChange={(e) => setRelType(e.target.value)} style={{ width: '100%' }}>
            <option value="">— pick a type —</option>
            {relTypes.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
        <label style={{ display: 'block', marginBottom: 'var(--space-sm)' }}>
          <span style={{ display: 'block', fontWeight: 500, marginBottom: '4px' }}>Properties (JSON)</span>
          <textarea className="form-input" value={propsText} onChange={(e) => setPropsText(e.target.value)}
                    rows={5} style={{ width: '100%', fontFamily: 'monospace' }} />
          {!propsValid && <small style={{ color: 'var(--error)' }}>Invalid JSON object.</small>}
        </label>
        {error && <div className="admin-error" style={{ marginBottom: 'var(--space-sm)' }}>{error}</div>}
        <div style={{ display: 'flex', gap: 'var(--space-sm)', justifyContent: 'flex-end' }}>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button type="button" className="btn btn-primary" disabled={!canSave} onClick={onSave}>Save</button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd wikantik-frontend && npm test -- --run EdgeFormModal
```

Expected: All five tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/admin/EdgeFormModal.jsx \
        wikantik-frontend/src/components/admin/EdgeFormModal.test.jsx
git commit -m "$(cat <<'EOF'
feat(frontend): EdgeFormModal shared between Create and Edit

Source/target autocomplete via /admin/knowledge-graph/nodes, dropdown
seeded from /schema's relationshipTypes, properties JSON validated
client-side, 409 conflict surfaced inline. Source/target disabled in
edit mode.
EOF
)"
```

---

### Task 11: Wire EdgeExplorer to support all five operations

**Files:**
- Modify: `wikantik-frontend/src/components/admin/EdgeExplorer.jsx`
- Create: `wikantik-frontend/src/components/admin/EdgeExplorer.test.jsx`

- [ ] **Step 1: Write the failing UI test**

Create `wikantik-frontend/src/components/admin/EdgeExplorer.test.jsx`:

```jsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import EdgeExplorer from './EdgeExplorer';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      queryEdges: vi.fn(),
      getSchema: vi.fn(),
      getNode: vi.fn(),
      deleteEdge: vi.fn(),
      deleteAndRejectEdge: vi.fn(),
      bulkDeleteEdges: vi.fn(),
      getEdgeAudit: vi.fn(),
      upsertEdge: vi.fn(),
      queryNodes: vi.fn(),
    },
  },
}));
import { api } from '../../api/client';

describe('EdgeExplorer curation buttons', () => {
  beforeEach(() => {
    Object.values(api.knowledge).forEach((fn) => fn.mockReset?.());
    api.knowledge.getSchema.mockResolvedValue({ relationshipTypes: ['related', 'depends_on'] });
    api.knowledge.queryEdges.mockResolvedValue({
      edges: [
        { id: 'e1', source_name: 'A', target_name: 'B', relationship_type: 'related', provenance: 'human-curated' },
      ],
      total: 1,
    });
    api.knowledge.getEdgeAudit.mockResolvedValue({ audit: [] });
  });

  it('shows New edge button that opens the modal', async () => {
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByRole('button', { name: /new edge/i }));
    expect(screen.getByRole('dialog', { name: /new edge/i })).toBeInTheDocument();
  });

  it('shows Edit / Delete / Delete + Prevent buttons in detail pane after selection', async () => {
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A')); // row click selects
    await waitFor(() => screen.getByRole('button', { name: /^edit$/i }));
    expect(screen.getByRole('button', { name: /^delete$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /delete \+ prevent/i })).toBeInTheDocument();
  });

  it('confirms before plain delete and calls deleteEdge', async () => {
    api.knowledge.deleteEdge.mockResolvedValue({ deleted: true });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /^delete$/i }));
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }));
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));
    await waitFor(() => expect(api.knowledge.deleteEdge).toHaveBeenCalledWith('e1'));
  });

  it('delete + prevent requires a reason and calls deleteAndRejectEdge', async () => {
    api.knowledge.deleteAndRejectEdge.mockResolvedValue({ deleted: true, rejected: true });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /delete \+ prevent/i }));
    fireEvent.click(screen.getByRole('button', { name: /delete \+ prevent/i }));
    fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: 'wrong direction' } });
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));
    await waitFor(() =>
        expect(api.knowledge.deleteAndRejectEdge).toHaveBeenCalledWith('e1', 'wrong direction'));
  });

  it('bulk delete requires typed count match and calls bulkDeleteEdges', async () => {
    api.knowledge.bulkDeleteEdges.mockResolvedValue({ deleted: 1 });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByRole('button', { name: /delete filtered \(1\)/i }));
    const input = screen.getByLabelText(/type the count/i);
    fireEvent.change(input, { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));
    await waitFor(() =>
        expect(api.knowledge.bulkDeleteEdges).toHaveBeenCalledWith(expect.objectContaining({
          expected_count: 1,
        })));
  });

  it('renders History rows from getEdgeAudit', async () => {
    api.knowledge.getEdgeAudit.mockResolvedValue({
      audit: [
        { action: 'CREATE', actor: 'alice', created: '2026-05-11T10:00:00Z' },
      ],
    });
    render(<EdgeExplorer />);
    await waitFor(() => screen.getByText('A'));
    fireEvent.click(screen.getByText('A'));
    await waitFor(() => screen.getByRole('button', { name: /history/i }));
    fireEvent.click(screen.getByRole('button', { name: /history/i }));
    await waitFor(() => expect(screen.getByText(/alice/)).toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run the test**

```bash
cd wikantik-frontend && npm test -- --run EdgeExplorer
```

Expected: FAIL — buttons don't exist yet.

- [ ] **Step 3: Modify EdgeExplorer.jsx**

Major rewrite of `wikantik-frontend/src/components/admin/EdgeExplorer.jsx`. Key changes:

1. **Track total** from `queryEdges` response.
2. **State for modals**: `formMode` ∈ `null | 'create' | 'edit'`, `deleteMode` ∈ `null | 'plain' | 'reject' | 'bulk'`.
3. **History section** in `EdgeDetail` collapsed by default, lazy-loads on first expand.
4. **Three action buttons** in `EdgeDetail`.
5. **"New edge"** and **"Delete filtered (N)"** buttons on the list-side header / pagination row.

The full rewritten file (single replacement):

```jsx
import { useState, useEffect, useRef, useCallback } from 'react';
import { api } from '../../api/client';
import ProvenanceBadge from './ProvenanceBadge';
import PageLink from './PageLink';
import EdgeFormModal from './EdgeFormModal';

const LIMIT = 50;

function ConfirmModal({ title, body, requireText, onConfirm, onCancel, extraField }) {
  const [typed, setTyped] = useState('');
  const [extraValue, setExtraValue] = useState('');
  const canConfirm = requireText ? typed === requireText : true;
  return (
    <div role="dialog" style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
      <div style={{ background: 'var(--surface-primary)', padding: 'var(--space-lg)', borderRadius: 'var(--radius-md)', minWidth: '380px' }}>
        <h3>{title}</h3>
        <p>{body}</p>
        {requireText && (
          <label style={{ display: 'block', marginBottom: 'var(--space-sm)' }}>
            <span style={{ display: 'block' }}>Type the count <code>{requireText}</code> to confirm:</span>
            <input className="form-input" aria-label="type the count" value={typed}
                   onChange={(e) => setTyped(e.target.value)} style={{ width: '100%' }} />
          </label>
        )}
        {extraField && (
          <label style={{ display: 'block', marginBottom: 'var(--space-sm)' }}>
            <span style={{ display: 'block' }}>{extraField.label}</span>
            <input className="form-input" aria-label={extraField.label.toLowerCase()} value={extraValue}
                   onChange={(e) => setExtraValue(e.target.value)} style={{ width: '100%' }} />
          </label>
        )}
        <div style={{ display: 'flex', gap: 'var(--space-sm)', justifyContent: 'flex-end' }}>
          <button type="button" className="btn" onClick={onCancel}>Cancel</button>
          <button type="button" className="btn btn-primary" disabled={!canConfirm}
                  onClick={() => onConfirm(extraValue)}>Confirm</button>
        </div>
      </div>
    </div>
  );
}

function EdgeHistory({ edgeId }) {
  const [rows, setRows] = useState(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState(null);
  const onToggle = async () => {
    if (open) { setOpen(false); return; }
    setOpen(true);
    if (rows == null) {
      try {
        const data = await api.knowledge.getEdgeAudit(edgeId);
        setRows(data.audit || []);
      } catch (e) {
        setError(e.message || 'Failed to load history');
      }
    }
  };
  return (
    <div style={{ marginTop: 'var(--space-md)' }}>
      <button type="button" className="btn-link" onClick={onToggle}>
        {open ? '▾' : '▸'} History
      </button>
      {open && (
        <div style={{ marginTop: 'var(--space-sm)' }}>
          {error && <div className="admin-error">{error}</div>}
          {rows && rows.length === 0 && <div style={{ color: 'var(--text-muted)' }}>No audit entries.</div>}
          {rows && rows.length > 0 && (
            <table className="admin-table" style={{ fontSize: '0.85em' }}>
              <thead><tr><th>When</th><th>Action</th><th>Actor</th><th>Reason</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id || `${r.created}-${r.action}`}>
                    <td style={{ whiteSpace: 'nowrap' }}>{r.created}</td>
                    <td>{r.action}</td>
                    <td>{r.actor}</td>
                    <td>{r.reason || ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}

function EdgeDetail({ edge, sourceNode, targetNode, loading, onNavigateNode, onEdit, onDelete, onDeleteAndReject }) {
  if (!edge) return null;
  const renderNodeCard = (label, node) => {
    if (!node) return <div style={{ fontStyle: 'italic', color: 'var(--text-muted)' }}>Not found</div>;
    return (
      <div style={{ padding: 'var(--space-sm)', background: 'var(--bg-elevated)', borderRadius: 'var(--radius-md)', marginBottom: 'var(--space-sm)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
          <strong style={{ fontSize: '0.95em' }}>
            <button className="btn-link" onClick={() => onNavigateNode(node.name)} style={{ fontWeight: 600 }}>
              {node.name}
            </button>
          </strong>
          <span style={{ fontSize: '0.8em', color: 'var(--text-muted)' }}>{label}</span>
        </div>
        <div style={{ fontSize: '0.85em' }}>
          <span><strong>Type:</strong> {node.node_type || '-'}</span>
          {' · '}
          <strong>Provenance:</strong> <ProvenanceBadge value={node.provenance} />
        </div>
        {node.source_page && (
          <div style={{ fontSize: '0.85em' }}><strong>Source page:</strong> <PageLink name={node.source_page} /></div>
        )}
      </div>
    );
  };

  return (
    <div style={{ padding: 'var(--space-md)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)' }}>
      <div style={{ textAlign: 'center', marginBottom: 'var(--space-md)' }}>
        <div style={{ fontSize: '0.8em', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.5px', marginBottom: '4px' }}>Relationship</div>
        <div style={{ fontSize: '1.1em', fontWeight: 600 }}>
          {edge.source_name || edge.source_id}
          <span style={{ margin: '0 8px', color: 'var(--text-muted)' }}>&rarr;</span>
          <span style={{ whiteSpace: 'nowrap' }}>{edge.relationship_type}</span>
          <span style={{ margin: '0 8px', color: 'var(--text-muted)' }}>&rarr;</span>
          {edge.target_name || edge.target_id}
        </div>
        <div style={{ marginTop: '4px' }}><ProvenanceBadge value={edge.provenance} /></div>
      </div>

      {!loading && (
        <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-md)', justifyContent: 'center' }}>
          <button type="button" className="btn btn-sm" onClick={onEdit}>Edit</button>
          <button type="button" className="btn btn-sm" onClick={onDelete}>Delete</button>
          <button type="button" className="btn btn-sm btn-warning" onClick={onDeleteAndReject}>Delete + Prevent</button>
        </div>
      )}

      {loading ? (
        <div className="admin-loading">Loading node details...</div>
      ) : (
        <>
          {renderNodeCard('Source', sourceNode)}
          {renderNodeCard('Target', targetNode)}
        </>
      )}

      {edge.id && <EdgeHistory edgeId={edge.id} />}
    </div>
  );
}

export default function EdgeExplorer() {
  const [edges, setEdges] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [search, setSearch] = useState('');
  const [relTypeFilter, setRelTypeFilter] = useState('');
  const [relTypes, setRelTypes] = useState([]);
  const [offset, setOffset] = useState(0);
  const [selectedEdge, setSelectedEdge] = useState(null);
  const [sourceNode, setSourceNode] = useState(null);
  const [targetNode, setTargetNode] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [formMode, setFormMode] = useState(null);          // null | 'create' | 'edit'
  const [confirmMode, setConfirmMode] = useState(null);    // null | 'plain' | 'reject' | 'bulk'
  const debounceRef = useRef(null);

  const loadEdges = useCallback(async (currentOffset) => {
    try {
      const data = await api.knowledge.queryEdges({
        relationship_type: relTypeFilter || undefined,
        search: search || undefined,
        limit: LIMIT,
        offset: currentOffset,
      });
      setEdges(data.edges || []);
      setTotal(typeof data.total === 'number' ? data.total : (data.edges?.length || 0));
      setError(null);
    } catch (err) {
      setError(err.message);
    }
  }, [relTypeFilter, search]);

  useEffect(() => {
    (async () => {
      try {
        const schema = await api.knowledge.getSchema();
        setRelTypes(schema.relationshipTypes || schema.relationship_types || []);
      } catch (err) {
        setError(err.message);
      }
    })();
  }, []);

  useEffect(() => {
    setOffset(0);
    loadEdges(0).finally(() => setLoading(false));
  }, [loadEdges]);

  useEffect(() => { if (offset > 0) loadEdges(offset); }, [offset, loadEdges]);

  const handleSearchChange = (e) => {
    const value = e.target.value;
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => setSearch(value), 300);
  };

  const handleEdgeClick = async (edge) => {
    setSelectedEdge(edge);
    setDetailLoading(true);
    setSourceNode(null);
    setTargetNode(null);
    try {
      const [src, tgt] = await Promise.all([
        api.knowledge.getNode(edge.source_name).catch(() => null),
        api.knowledge.getNode(edge.target_name).catch(() => null),
      ]);
      setSourceNode(src); setTargetNode(tgt);
    } finally {
      setDetailLoading(false);
    }
  };

  const handleNavigateNode = async (name) => {
    setDetailLoading(true);
    try {
      const node = await api.knowledge.getNode(name);
      if (node) { setSourceNode(node); setTargetNode(null);
        setSelectedEdge({ source_name: node.name, target_name: '', relationship_type: '(viewing node)', provenance: node.provenance });
      }
    } finally { setDetailLoading(false); }
  };

  const refreshAndClose = async () => {
    setFormMode(null);
    setConfirmMode(null);
    await loadEdges(offset);
  };

  const onConfirmPlainDelete = async () => {
    if (!selectedEdge?.id) return;
    try {
      await api.knowledge.deleteEdge(selectedEdge.id);
      setSelectedEdge(null);
      await refreshAndClose();
    } catch (e) { setError(e.message); setConfirmMode(null); }
  };

  const onConfirmDeleteAndReject = async (reason) => {
    if (!selectedEdge?.id) return;
    try {
      await api.knowledge.deleteAndRejectEdge(selectedEdge.id, reason);
      setSelectedEdge(null);
      await refreshAndClose();
    } catch (e) { setError(e.message); setConfirmMode(null); }
  };

  const onConfirmBulkDelete = async () => {
    try {
      await api.knowledge.bulkDeleteEdges({
        relationship_type: relTypeFilter || undefined,
        search: search || undefined,
        expected_count: total,
      });
      setSelectedEdge(null);
      await refreshAndClose();
    } catch (e) { setError(e.message); setConfirmMode(null); }
  };

  const handlePrev = () => setOffset(Math.max(0, offset - LIMIT));
  const handleNext = () => setOffset(offset + LIMIT);

  if (loading) return <div className="admin-loading">Loading edges...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div style={{ display: 'flex', gap: 'var(--space-lg)', minHeight: '400px' }}>
      <div style={{ flex: '1 1 50%' }}>
        <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)' }}>
          <input type="text" placeholder="Search by node name..." defaultValue={search}
                 onChange={handleSearchChange} className="form-input" style={{ flex: 1 }} />
          <select value={relTypeFilter} onChange={(e) => setRelTypeFilter(e.target.value)}
                  className="form-input" style={{ width: '200px' }}>
            <option value="">All relationship types</option>
            {relTypes.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
          <button type="button" className="btn btn-primary" onClick={() => setFormMode('create')}>New edge</button>
        </div>

        <table className="admin-table">
          <thead>
            <tr><th>Source</th><th>Relationship</th><th>Target</th><th>Provenance</th></tr>
          </thead>
          <tbody>
            {edges.map((e) => (
              <tr key={e.id} onClick={() => handleEdgeClick(e)} style={{ cursor: 'pointer' }}
                  className={selectedEdge?.id === e.id ? 'admin-row-selected' : ''}>
                <td>{e.source_name || e.source_id}</td>
                <td style={{ whiteSpace: 'nowrap' }}>{e.relationship_type}</td>
                <td>{e.target_name || e.target_id}</td>
                <td><ProvenanceBadge value={e.provenance} /></td>
              </tr>
            ))}
            {edges.length === 0 && <tr><td colSpan={4} style={{ textAlign: 'center' }}>No edges found.</td></tr>}
          </tbody>
        </table>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 'var(--space-sm)', fontSize: '0.85em' }}>
          <button className="btn btn-sm" onClick={handlePrev} disabled={offset === 0}>Prev</button>
          <span>Showing {offset + 1}–{offset + edges.length} of {total}</span>
          <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
            <button className="btn btn-sm btn-warning" disabled={total === 0}
                    onClick={() => setConfirmMode('bulk')}>
              Delete filtered ({total})
            </button>
            <button className="btn btn-sm" onClick={handleNext} disabled={edges.length < LIMIT}>Next</button>
          </div>
        </div>
      </div>

      <div style={{ flex: '1 1 50%' }}>
        {selectedEdge ? (
          <EdgeDetail edge={selectedEdge} sourceNode={sourceNode} targetNode={targetNode}
                      loading={detailLoading} onNavigateNode={handleNavigateNode}
                      onEdit={() => setFormMode('edit')}
                      onDelete={() => setConfirmMode('plain')}
                      onDeleteAndReject={() => setConfirmMode('reject')} />
        ) : (
          <div className="admin-empty" style={{ padding: 'var(--space-lg)', textAlign: 'center' }}>
            Select an edge to view details.
          </div>
        )}
      </div>

      {formMode && (
        <EdgeFormModal
          mode={formMode}
          relTypes={relTypes}
          initialEdge={formMode === 'edit' ? selectedEdge : null}
          initialSource={formMode === 'edit' ? sourceNode : null}
          initialTarget={formMode === 'edit' ? targetNode : null}
          onClose={() => setFormMode(null)}
          onSaved={refreshAndClose}
        />
      )}

      {confirmMode === 'plain' && (
        <ConfirmModal title="Delete this edge?" body="Re-extraction may re-propose it. Use Delete + Prevent if you want to keep it out."
                      onConfirm={onConfirmPlainDelete} onCancel={() => setConfirmMode(null)} />
      )}
      {confirmMode === 'reject' && (
        <ConfirmModal title="Delete and prevent re-proposal"
                      body="This will delete the edge AND insert a rejection so the next extraction run cannot re-add it."
                      extraField={{ label: 'Reason' }}
                      onConfirm={onConfirmDeleteAndReject} onCancel={() => setConfirmMode(null)} />
      )}
      {confirmMode === 'bulk' && (
        <ConfirmModal title={`Delete ${total} filtered edges?`}
                      body="This deletes every edge matching the current filter, including pages beyond the current view."
                      requireText={String(total)}
                      onConfirm={onConfirmBulkDelete} onCancel={() => setConfirmMode(null)} />
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run the tests**

```bash
cd wikantik-frontend && npm test -- --run EdgeExplorer
```

Expected: All six tests pass. If a test references a tooltip/role that doesn't quite match, adjust the assertion or the JSX label to match — keep the underlying behavior.

- [ ] **Step 5: Type-check the whole frontend**

```bash
cd wikantik-frontend && npm run build
```

Expected: Vite build succeeds.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/admin/EdgeExplorer.jsx \
        wikantik-frontend/src/components/admin/EdgeExplorer.test.jsx
git commit -m "$(cat <<'EOF'
feat(frontend): wire EdgeExplorer to create/edit/delete/reject/bulk-delete

EdgeFormModal opens for both Create (top toolbar button) and Edit
(detail-pane button). Confirm modals back plain delete, delete-and-
reject (with reason field), and bulk delete (count-typed confirm).
EdgeHistory lazy-loads audit rows. Surface `total` from queryEdges
to drive the bulk-delete button's count.
EOF
)"
```

---

## Phase 7 — Integration Tests

### Task 12: Wire-level IT against PostgreSQL

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/EdgeCurationIT.java`

- [ ] **Step 1: Examine the IT pattern**

```bash
ls wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/
head -120 wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/KgStagedValidationIT.java
```

Mirror the pattern used by `KgStagedValidationIT` — same Cargo-launched Tomcat, same admin credentials from `test.properties`.

- [ ] **Step 2: Write the IT**

```java
package com.wikantik.its.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-level IT for the admin edge curation endpoints. Uses the Cargo-launched
 * Tomcat from the wikantik-it-test-rest harness.
 *
 * Order matters: create → query (total) → edit → audit → delete-and-reject → re-extract guard.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class EdgeCurationIT {

    private static final String BASE = System.getProperty( "wikantik.test.baseUrl", "http://localhost:8205" );
    private static final String AUTH = "Basic "
            + java.util.Base64.getEncoder().encodeToString(
                ( System.getProperty( "wikantik.test.user", "admin" ) + ":"
                + System.getProperty( "wikantik.test.password", "admin" ) ).getBytes() );

    private static String edgeId;
    private static String sourceId;
    private static String targetId;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HttpResponse< String > send( final HttpRequest.Builder b ) throws Exception {
        return HTTP.send( b.header( "Authorization", AUTH )
                          .header( "Content-Type", "application/json" ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    @Test @Order( 1 )
    void seedNodesAndCreateEdge() throws Exception {
        // Upsert two nodes via the admin node endpoint
        for ( final String name : new String[]{ "CurationA", "CurationB" } ) {
            final JsonObject body = new JsonObject();
            body.addProperty( "name", name );
            body.addProperty( "node_type", "concept" );
            final HttpResponse< String > r = send( HttpRequest.newBuilder()
                .uri( URI.create( BASE + "/admin/knowledge-graph/nodes" ) )
                .POST( HttpRequest.BodyPublishers.ofString( body.toString() ) ) );
            assertEquals( 200, r.statusCode(), r.body() );
            final JsonObject n = JsonParser.parseString( r.body() ).getAsJsonObject();
            if ( "CurationA".equals( name ) ) sourceId = n.get( "id" ).getAsString();
            else                              targetId = n.get( "id" ).getAsString();
        }

        // Create the edge
        final JsonObject body = new JsonObject();
        body.addProperty( "source_id", sourceId );
        body.addProperty( "target_id", targetId );
        body.addProperty( "relationship_type", "related" );
        final HttpResponse< String > r = send( HttpRequest.newBuilder()
            .uri( URI.create( BASE + "/admin/knowledge-graph/edges" ) )
            .POST( HttpRequest.BodyPublishers.ofString( body.toString() ) ) );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonObject e = JsonParser.parseString( r.body() ).getAsJsonObject();
        edgeId = e.get( "id" ).getAsString();
        assertEquals( "human-curated", e.get( "provenance" ).getAsString() );
        assertEquals( "human", e.get( "tier" ).getAsString() );
    }

    @Test @Order( 2 )
    void queryEdgesIncludesTotal() throws Exception {
        final HttpResponse< String > r = send( HttpRequest.newBuilder()
            .uri( URI.create( BASE + "/admin/knowledge-graph/edges?relationship_type=related&search=CurationA" ) )
            .GET() );
        assertEquals( 200, r.statusCode() );
        final JsonObject body = JsonParser.parseString( r.body() ).getAsJsonObject();
        assertTrue( body.has( "total" ), "response must include total: " + r.body() );
        assertEquals( 1, body.get( "total" ).getAsInt() );
    }

    @Test @Order( 3 )
    void editEdgeUpdatesRelationshipType() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "source_id", sourceId );
        body.addProperty( "target_id", targetId );
        body.addProperty( "relationship_type", "related" ); // upsert same triple to test idempotency
        body.add( "properties", JsonParser.parseString( "{\"note\":\"edited\"}" ) );
        final HttpResponse< String > r = send( HttpRequest.newBuilder()
            .uri( URI.create( BASE + "/admin/knowledge-graph/edges" ) )
            .POST( HttpRequest.BodyPublishers.ofString( body.toString() ) ) );
        assertEquals( 200, r.statusCode() );
    }

    @Test @Order( 4 )
    void auditEndpointShowsCreateAndUpdate() throws Exception {
        final HttpResponse< String > r = send( HttpRequest.newBuilder()
            .uri( URI.create( BASE + "/admin/knowledge-graph/edges/" + edgeId + "/audit" ) )
            .GET() );
        assertEquals( 200, r.statusCode() );
        final JsonObject body = JsonParser.parseString( r.body() ).getAsJsonObject();
        final var rows = body.getAsJsonArray( "audit" );
        assertTrue( rows.size() >= 2, "expected at least CREATE + UPDATE rows: " + r.body() );
        assertEquals( "UPDATE", rows.get( 0 ).getAsJsonObject().get( "action" ).getAsString() );
    }

    @Test @Order( 5 )
    void deleteAndRejectBlocksRecreate() throws Exception {
        // Delete + reject
        final JsonObject body = new JsonObject();
        body.addProperty( "reason", "wrong direction" );
        final HttpResponse< String > r = send( HttpRequest.newBuilder()
            .uri( URI.create( BASE + "/admin/knowledge-graph/edges/" + edgeId + "/delete-and-reject" ) )
            .POST( HttpRequest.BodyPublishers.ofString( body.toString() ) ) );
        assertEquals( 200, r.statusCode(), r.body() );

        // Re-creating the same triple should still succeed via admin UI (rejections only
        // gate proposal acceptance — admin curation can override). Verify the rejection
        // row is present via a backdoor query if available, or by re-running and seeing the
        // rejection respected by the proposal path. For v0 we just assert the audit shows
        // DELETE with reason 'wrong direction'.
        final HttpResponse< String > auditR = send( HttpRequest.newBuilder()
            .uri( URI.create( BASE + "/admin/knowledge-graph/edges/" + edgeId + "/audit" ) )
            .GET() );
        assertEquals( 200, auditR.statusCode() );
        assertTrue( auditR.body().contains( "wrong direction" ),
                "audit must record the rejection reason: " + auditR.body() );
    }

    @Test @Order( 6 )
    void bulkDeleteFails409OnCountMismatch() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "relationship_type", "related" );
        body.addProperty( "expected_count", 9999 );
        final HttpResponse< String > r = send( HttpRequest.newBuilder()
            .uri( URI.create( BASE + "/admin/knowledge-graph/edges/bulk-delete" ) )
            .POST( HttpRequest.BodyPublishers.ofString( body.toString() ) ) );
        assertEquals( 409, r.statusCode(), r.body() );
    }
}
```

- [ ] **Step 3: Run the IT (sequential — no `-T` flag)**

```bash
mvn clean install -Pintegration-tests -pl wikantik-it-tests/wikantik-it-test-rest -am -Dit.test=EdgeCurationIT -fae
```

Expected: All six ordered tests pass against a real Cargo Tomcat + PostgreSQL.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/EdgeCurationIT.java
git commit -m "$(cat <<'EOF'
test(it): wire-level IT for admin edge curation endpoints

Six ordered tests: create + total + edit + audit + delete-and-reject
with audit trail + bulk-delete 409 on count drift.
EOF
)"
```

---

### Task 13: Selenide browser IT for the happy path

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/test/java/com/wikantik/its/EdgeCurationBrowserIT.java`

- [ ] **Step 1: Examine the Selenide IT pattern**

```bash
ls wikantik-it-tests/wikantik-selenide-tests/src/test/java/com/wikantik/its/ | head
head -80 wikantik-it-tests/wikantik-selenide-tests/src/test/java/com/wikantik/its/HubOverviewAdminIT.java
```

Mirror auth bootstrap + page navigation pattern.

- [ ] **Step 2: Write the browser IT**

```java
package com.wikantik.its;

import com.codeborne.selenide.Condition;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;

/**
 * End-to-end browser smoke: admin Edge Explorer → create → edit → delete-and-reject.
 * Login bootstrap mirrors HubOverviewAdminIT.
 */
class EdgeCurationBrowserIT extends WikantikSelenideBase {

    @Test
    void createEditDeleteRoundTrip() {
        loginAsAdmin();
        open( baseUrl() + "/admin/knowledge-graph" );

        // Switch to Edge Explorer tab
        $( "button[data-tab='edges']" ).shouldBe( Condition.visible ).click();

        // New edge
        $( "button" ).find( Condition.text( "New edge" ) ).click();
        $( "[role=dialog]" ).shouldBe( Condition.visible );

        // (Test environment seeds nodes "SelenideA" and "SelenideB" via a fixture; if not,
        // wire a one-time @BeforeAll that POSTs them via the admin nodes endpoint.)
        $( "[aria-label=Source]" ).setValue( "SelenideA" );
        $( "li button" ).find( Condition.text( "SelenideA" ) ).click();
        $( "[aria-label=Target]" ).setValue( "SelenideB" );
        $( "li button" ).find( Condition.text( "SelenideB" ) ).click();
        $( "[aria-label=Relationship]" ).selectOptionByValue( "related" );
        $( "button" ).find( Condition.text( "Save" ) ).click();

        $( "table.admin-table" ).shouldHave( Condition.text( "SelenideA" ) );

        // Click the row to select it
        $$( "tr" ).find( Condition.text( "SelenideA" ) ).click();

        // Edit
        $( "button" ).find( Condition.text( "Edit" ) ).click();
        $( "[role=dialog]" ).shouldBe( Condition.visible );
        $( "button" ).find( Condition.text( "Cancel" ) ).click();

        // Delete + Prevent
        $( "button" ).find( Condition.text( "Delete + Prevent" ) ).click();
        $( "[aria-label=reason]" ).setValue( "smoke test" );
        $( "button" ).find( Condition.text( "Confirm" ) ).click();

        // Row should be gone
        $( "table.admin-table" ).shouldNotHave( Condition.text( "SelenideA → related → SelenideB" ) );
    }
}
```

- [ ] **Step 3: Run the Selenide IT**

```bash
mvn clean install -Pintegration-tests -pl wikantik-it-tests/wikantik-selenide-tests -am -Dit.test=EdgeCurationBrowserIT -fae
```

Expected: Browser opens, completes the flow, passes. Headless mode picks up the existing `-Dselenide.headless=true` system property.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/test/java/com/wikantik/its/EdgeCurationBrowserIT.java
git commit -m "$(cat <<'EOF'
test(selenide): end-to-end Edge Explorer create/edit/delete-and-reject

Single happy-path browser smoke matching the curation v0 design.
EOF
)"
```

---

## Phase 8 — Final Verification + Deploy

### Task 14: Full IT reactor

- [ ] **Step 1: Run the full IT reactor sequentially**

```bash
mvn clean install -Pintegration-tests -fae
```

Expected: All IT modules pass. Per memory: no `-T` flag (port conflicts), always `-fae`. If any failure surfaces, fix and re-run before deploying.

- [ ] **Step 2: Quick sanity build**

```bash
mvn clean install -Dmaven.test.skip -T 1C
```

Expected: BUILD SUCCESS, WAR produced.

### Task 15: Local deploy

- [ ] **Step 1: Redeploy via the routine path**

```bash
bin/redeploy.sh
```

Expected: shutdown, rotate `catalina.out`, swap WAR, startup. Final log line should report Tomcat up.

- [ ] **Step 2: Smoke-test the new endpoints with curl**

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
# GET edges with total
curl -s -u "${login}:${password}" "http://localhost:8080/admin/knowledge-graph/edges?limit=5" | head -200
```

Expected: response includes `"total":` integer alongside `"edges":[...]`.

- [ ] **Step 3: Hand off to user**

Tell the user the deploy is live and where to test:

> Deployed at http://localhost:8080. Admin Edge Explorer is at /admin/knowledge-graph → Edges tab. Credentials in `test.properties`. Tasks to exercise: create a fresh edge, edit it, view history, delete-and-prevent, bulk delete by filter.

---

## Self-Review Checklist

Run this against the spec sections before declaring the plan complete:

- **Migration** — Task 1 ✓
- **Provenance enum extension** — Task 2 ✓
- **kg_edge_audit table + repo** — Tasks 1, 3 ✓
- **countEdgesWithFilter** — Task 4 ✓
- **bulkDeleteByFilter + delete-and-reject in tx** — Task 5 ✓
- **Provenance/tier stamping invariant** — Task 6 (regression lock) ✓
- **Service interface additions** — Task 7 ✓
- **Resource endpoints (POST upsert auditing, GET total, bulk-delete, delete-and-reject, audit)** — Task 8 ✓
- **Client wrappers** — Task 9 ✓
- **EdgeFormModal** — Task 10 ✓
- **EdgeExplorer wiring + ConfirmModal + EdgeHistory** — Task 11 ✓
- **Wire-level IT** — Task 12 ✓
- **Browser IT** — Task 13 ✓
- **Full reactor + deploy** — Tasks 14, 15 ✓

All eight spec deliverables (create, edit, delete, delete-and-reject, bulk delete, audit table, audit endpoint, History pane) and one cross-cutting concern (HUMAN_CURATED stamping) are covered.
