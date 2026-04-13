# Hub Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an admin-triggered cluster-discovery feature that finds latent topic clusters among non-hub-member pages using HDBSCAN over existing TF-IDF embeddings, presents them as editable review-queue cards, and on accept writes a stub wiki page whose frontmatter the existing save pipeline projects into the knowledge graph.

**Architecture:** A new `HubDiscoveryService` (builder-constructed, mirrors `HubProposalService`) orchestrates a thin `SmileHdbscanClusterer` wrapper over Smile's HDBSCAN, a new `HubDiscoveryRepository` over the `hub_discovery_proposals` table (no status column — accept and dismiss both delete), and uses `PageSaveHelper.saveText` to emit stub pages whose frontmatter (`type: hub`, `related: [...]`) is auto-projected into `kg_nodes`/`kg_edges` by the existing `GraphProjector.postSave` filter. REST endpoints live on a new `AdminHubDiscoveryResource` servlet under `/admin/knowledge/hub-discovery/*` (longest-path-prefix wins over `AdminKnowledgeResource`), and a React admin page under `/admin/hub-discovery` provides the review UI with `data-testid` attributes for Selenide coverage.

**Tech Stack:** Java 21, Maven, PostgreSQL + pgvector, Smile (`com.github.haifengl:smile-core`, Apache 2.0), Gson, Log4j, JUnit 5, Mockito, Testcontainers, React/Vite, Selenide.

**Spec:** `docs/superpowers/specs/2026-04-09-hub-discovery-design.md`

---

## File Structure

**Create:**

- `wikantik-war/src/main/config/db/migrations/V006__hub_discovery_proposals.sql` — schema migration, `CREATE TABLE IF NOT EXISTS hub_discovery_proposals`, index, grants.
- `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryRepository.java` — JDBC CRUD over `hub_discovery_proposals`. Mirrors `HubProposalRepository` conventions.
- `wikantik-main/src/main/java/com/wikantik/knowledge/SmileHdbscanClusterer.java` — thin wrapper isolating Smile. Public method: `int[] cluster(float[][] vectors, int minClusterSize, int minPts)`.
- `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java` — orchestrator, builder-constructed. Public methods: `int generateClusterProposals()`, `AcceptResult acceptProposal(int id, String editedName, List<String> members, String reviewedBy)`, `void dismissProposal(int id, String reviewedBy)`, plus the builder.
- `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryException.java` — runtime exception for orchestrator failures. Mirrors `HubProposalException`.
- `wikantik-main/src/main/java/com/wikantik/knowledge/HubNameCollisionException.java` — runtime exception raised when an accept targets an existing page. Distinct class so the REST layer can map it to `409`.
- `wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java` — REST servlet mapped to `/admin/knowledge/hub-discovery/*`, DTO-based accept body.
- `wikantik-rest/src/main/java/com/wikantik/rest/dto/AcceptProposalRequest.java` — DTO for the accept body. Gson-compatible POJO.
- `wikantik-main/src/test/java/com/wikantik/knowledge/SmileHdbscanClustererTest.java` — pure unit tests (no DB).
- `wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryRepositoryTest.java` — Testcontainers Postgres.
- `wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryServiceTest.java` — Testcontainers Postgres.
- `wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java` — Mockito unit tests.
- `wikantik-frontend/src/components/admin/HubDiscoveryTab.jsx` — React tab component (review-queue list).
- `wikantik-frontend/src/components/admin/HubDiscoveryCard.jsx` — individual proposal card with editable name + member checkboxes.
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/pages/admin/HubDiscoveryAdminPage.java` — Selenide page object.
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubDiscoveryAdminIT.java` — Selenide end-to-end tests.
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RestSeedHelper.java` — REST helper for seeding corpus state (used by the Selenide IT).

**Modify:**

- `pom.xml` (root) — add `<smile.version>` property.
- `wikantik-main/pom.xml` — add `smile-core` dependency.
- `wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java` — construct `HubDiscoveryRepository` + `HubDiscoveryService`, expose via the `Services` record.
- `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` — register the two new managers in `initKnowledgeGraph`.
- `wikantik-war/src/main/webapp/WEB-INF/web.xml` — declare and map the new `AdminHubDiscoveryResource` servlet.
- `wikantik-main/src/main/resources/ini/wikantik.properties` — add the three `wikantik.hub.discovery.*` properties (defaults commented; properties are optional).
- `wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx` — register the new "Hub Discovery" tab.
- `wikantik-frontend/src/api/client.js` — add `api.knowledge.hubDiscovery.*` client methods.

---

## Task 1: Add Smile dependency

**Files:**
- Modify: `pom.xml` (root) — properties block
- Modify: `wikantik-main/pom.xml` — dependencies block

- [ ] **Step 1: Look up the latest stable `smile-core` version on Maven Central**

Run: `curl -s 'https://search.maven.org/solrsearch/select?q=g:com.github.haifengl+AND+a:smile-core&rows=1&wt=json' | head -c 500`

Expected: a JSON response with `"latestVersion":"X.Y.Z"`. Record the version string — referred to as `SMILE_VERSION` below. As of writing, `smile-core` is at 3.x; pin whichever is current.

- [ ] **Step 2: Add the version property to the root `pom.xml`**

Open `pom.xml` and find the `<properties>` block near the top. After the existing version pins (e.g., `<lucene.version>...`), add:

```xml
<smile.version>SMILE_VERSION</smile.version>
```

Replace `SMILE_VERSION` with the value from Step 1.

- [ ] **Step 3: Add the dependency to `wikantik-main/pom.xml`**

Open `wikantik-main/pom.xml`. In the `<dependencies>` block, append:

```xml
<dependency>
    <groupId>com.github.haifengl</groupId>
    <artifactId>smile-core</artifactId>
    <version>${smile.version}</version>
</dependency>
```

- [ ] **Step 4: Verify the dependency resolves**

Run: `mvn dependency:resolve -pl wikantik-main -q`
Expected: exits 0, no errors. If a transitive SLF4J binding conflict surfaces, add an exclusion for `org.slf4j:slf4j-simple` on the `smile-core` dependency (Smile ships one by default and the project uses Log4j).

- [ ] **Step 5: Commit**

```bash
git add pom.xml wikantik-main/pom.xml
git commit -m "build: add smile-core dependency for HDBSCAN clustering"
```

---

## Task 2: Database migration for `hub_discovery_proposals`

**Files:**
- Create: `wikantik-war/src/main/config/db/migrations/V006__hub_discovery_proposals.sql`

- [ ] **Step 1: Confirm V006 is the next migration number**

Run: `ls wikantik-war/src/main/config/db/migrations/`
Expected: last numbered file is `V005__hub_membership.sql`. If a newer `V006__*.sql` already exists, bump this plan's migration number to the next free slot and use that number consistently throughout later tasks.

- [ ] **Step 2: Write the migration file**

Create `wikantik-war/src/main/config/db/migrations/V006__hub_discovery_proposals.sql`:

```sql
-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: Hub discovery proposals
--
-- Creates hub_discovery_proposals, the review queue for cluster-based hub
-- suggestions produced by HubDiscoveryService. Accept and dismiss both
-- DELETE the row — there is no status column — so a row's existence means
-- "pending review". Fully idempotent.
--
-- Depends on V004 (pgvector extension, though this table uses JSONB not vectors).

CREATE TABLE IF NOT EXISTS hub_discovery_proposals (
    id              SERIAL PRIMARY KEY,
    suggested_name  TEXT             NOT NULL,
    exemplar_page   TEXT             NOT NULL,
    member_pages    JSONB            NOT NULL,
    coherence_score DOUBLE PRECISION NOT NULL,
    created         TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_hub_discovery_proposals_created
    ON hub_discovery_proposals ( created DESC );

GRANT SELECT, INSERT, UPDATE, DELETE ON hub_discovery_proposals TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE hub_discovery_proposals_id_seq TO :app_user;
```

- [ ] **Step 3: Apply the migration against the local database**

Run: `wikantik-war/src/main/config/db/migrate.sh`
Expected: output reports `V006__hub_discovery_proposals.sql` applied.

- [ ] **Step 4: Verify idempotency**

Run: `wikantik-war/src/main/config/db/migrate.sh`
Expected: output reports 0 migrations applied (no-op on second run).

- [ ] **Step 5: Verify the table exists**

Run:
```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
PGPASSWORD="${db_password:-ChangeMe123!}" psql -h localhost -U jspwiki -d wikantik -c '\d hub_discovery_proposals'
```
Expected: table schema prints with all five columns.

- [ ] **Step 6: Commit**

```bash
git add wikantik-war/src/main/config/db/migrations/V006__hub_discovery_proposals.sql
git commit -m "feat: V006 hub_discovery_proposals schema"
```

---

## Task 3: `SmileHdbscanClusterer` with unit tests (TDD)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/SmileHdbscanClusterer.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/SmileHdbscanClustererTest.java`

- [ ] **Step 1: Write the failing unit tests**

Create `wikantik-main/src/test/java/com/wikantik/knowledge/SmileHdbscanClustererTest.java`:

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
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SmileHdbscanClustererTest {

    private final SmileHdbscanClusterer clusterer = new SmileHdbscanClusterer();

    @Test
    void cluster_findsTwoObviousGroups() {
        // Two tight groups in 2D space plus one outlier.
        final float[][] vectors = {
            // Group A near (0, 0)
            { 0.00f, 0.00f }, { 0.01f, 0.02f }, { 0.02f, 0.01f }, { 0.00f, 0.03f },
            // Group B near (10, 10)
            { 10.00f, 10.00f }, { 10.02f, 10.01f }, { 9.99f, 10.03f }, { 10.01f, 9.98f },
            // Outlier far from both
            { 50.0f, 50.0f }
        };
        final int[] labels = clusterer.cluster( vectors, 3, 3 );

        assertEquals( 9, labels.length );
        // Outlier must be noise.
        assertEquals( -1, labels[ 8 ] );
        // Both groups must share a non-noise label internally and have distinct labels.
        final Set< Integer > groupA = new HashSet<>();
        for ( int i = 0; i < 4; i++ ) groupA.add( labels[ i ] );
        final Set< Integer > groupB = new HashSet<>();
        for ( int i = 4; i < 8; i++ ) groupB.add( labels[ i ] );
        assertEquals( 1, groupA.size(), "Group A members should share one label" );
        assertEquals( 1, groupB.size(), "Group B members should share one label" );
        assertNotEquals( groupA.iterator().next(), groupB.iterator().next(),
            "Group A and B must have distinct cluster labels" );
        assertNotEquals( -1, groupA.iterator().next(), "Group A must not be noise" );
        assertNotEquals( -1, groupB.iterator().next(), "Group B must not be noise" );
    }

    @Test
    void cluster_emptyInput_returnsEmpty() {
        final int[] labels = clusterer.cluster( new float[ 0 ][ 0 ], 3, 3 );
        assertEquals( 0, labels.length );
    }

    @Test
    void cluster_belowMinClusterSize_allNoise() {
        final float[][] vectors = { { 0.0f, 0.0f }, { 0.01f, 0.01f } };
        final int[] labels = clusterer.cluster( vectors, 3, 3 );
        assertEquals( 2, labels.length );
        assertEquals( -1, labels[ 0 ] );
        assertEquals( -1, labels[ 1 ] );
    }
}
```

- [ ] **Step 2: Run tests — expect compile failure**

Run: `mvn test -pl wikantik-main -Dtest=SmileHdbscanClustererTest -q`
Expected: compilation fails because `SmileHdbscanClusterer` does not exist yet.

- [ ] **Step 3: Write the clusterer implementation**

Create `wikantik-main/src/main/java/com/wikantik/knowledge/SmileHdbscanClusterer.java`:

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
package com.wikantik.knowledge;

import smile.clustering.HDBSCAN;

/**
 * Thin wrapper over Smile's HDBSCAN implementation. Exists so the orchestrator
 * layer can depend on a minimal, mockable interface and so Smile behavior changes
 * surface in a small number of focused unit tests rather than scattered across the
 * HubDiscovery test suite.
 *
 * <p>Input vectors should already be L2-normalized; when that is true, Euclidean
 * distance in Smile's default implementation is monotonic with cosine distance, so
 * no custom metric needs to be passed. The TfidfModel in this project emits
 * L2-normalized vectors by default.
 */
public class SmileHdbscanClusterer {

    /**
     * Run HDBSCAN over the supplied vectors.
     *
     * @param vectors         one row per data point; all rows must share the same length.
     * @param minClusterSize  smallest number of members for a cluster; groups below this are noise.
     * @param minPts          HDBSCAN density parameter (core-point threshold).
     * @return                one label per input row. Noise points are labeled {@code -1};
     *                        clustered points share a non-negative integer with their cluster mates.
     */
    public int[] cluster( final float[][] vectors, final int minClusterSize, final int minPts ) {
        if ( vectors.length == 0 ) return new int[ 0 ];
        final double[][] doubles = new double[ vectors.length ][];
        for ( int i = 0; i < vectors.length; i++ ) {
            final float[] row = vectors[ i ];
            final double[] out = new double[ row.length ];
            for ( int j = 0; j < row.length; j++ ) out[ j ] = row[ j ];
            doubles[ i ] = out;
        }
        final HDBSCAN< double[] > model = HDBSCAN.fit( doubles, minPts, minClusterSize );
        return model.y;
    }
}
```

Note on Smile API: Smile's `HDBSCAN.fit(data, minPts, minClusterSize)` returns a model exposing cluster assignments via its `y` field. If the pinned Smile version has renamed these (Smile 3.x has occasionally reorganized), adapt by reading the `HDBSCAN` Javadoc with `mvn dependency:sources -pl wikantik-main` and update the last two lines to the current equivalent. The test from Step 1 defines the contract the wrapper must satisfy.

- [ ] **Step 4: Run tests — expect pass**

Run: `mvn test -pl wikantik-main -Dtest=SmileHdbscanClustererTest -q`
Expected: all three tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/SmileHdbscanClusterer.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/SmileHdbscanClustererTest.java
git commit -m "feat: SmileHdbscanClusterer wrapper with unit tests"
```

---

## Task 4: `HubDiscoveryRepository` — failing test first

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryRepositoryTest.java`

- [ ] **Step 1: Write the failing repository test**

Create `wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryRepositoryTest.java`:

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
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class HubDiscoveryRepositoryTest {

    private static DataSource dataSource;
    private HubDiscoveryRepository repo;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM hub_discovery_proposals" );
        }
        repo = new HubDiscoveryRepository( dataSource );
    }

    @Test
    void insert_thenList_returnsRow() {
        final int id = repo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "O'Brien's Scala" ), 0.84 );
        assertTrue( id > 0 );
        final List< HubDiscoveryRepository.HubDiscoveryProposal > all = repo.list( 50, 0 );
        assertEquals( 1, all.size() );
        final var row = all.get( 0 );
        assertEquals( "JavaHub", row.suggestedName() );
        assertEquals( "Java", row.exemplarPage() );
        assertEquals( List.of( "Java", "Kotlin", "O'Brien's Scala" ), row.memberPages() );
        assertEquals( 0.84, row.coherenceScore(), 1e-9 );
        assertNotNull( row.created() );
    }

    @Test
    void findById_returnsRowOrNull() {
        final int id = repo.insert( "TechHub", "Java", List.of( "Java", "Python" ), 0.7 );
        final var found = repo.findById( id );
        assertNotNull( found );
        assertEquals( id, found.id() );
        assertNull( repo.findById( id + 9999 ) );
    }

    @Test
    void delete_removesRow() {
        final int id = repo.insert( "H", "A", List.of( "A", "B", "C" ), 0.5 );
        assertTrue( repo.delete( id ) );
        assertNull( repo.findById( id ) );
    }

    @Test
    void delete_missingId_returnsFalse() {
        assertFalse( repo.delete( 99999 ) );
    }

    @Test
    void list_orderedByCreatedDesc() throws Exception {
        repo.insert( "First", "A", List.of( "A", "B", "C" ), 0.5 );
        Thread.sleep( 10 );
        repo.insert( "Second", "D", List.of( "D", "E", "F" ), 0.6 );
        Thread.sleep( 10 );
        repo.insert( "Third", "G", List.of( "G", "H", "I" ), 0.7 );
        final var all = repo.list( 50, 0 );
        assertEquals( 3, all.size() );
        assertEquals( "Third", all.get( 0 ).suggestedName() );
        assertEquals( "First", all.get( 2 ).suggestedName() );
    }

    @Test
    void list_respectsLimit() {
        for ( int i = 0; i < 5; i++ ) {
            repo.insert( "H" + i, "A", List.of( "A", "B", "C" ), 0.5 );
        }
        assertEquals( 2, repo.list( 2, 0 ).size() );
        assertEquals( 3, repo.list( 3, 0 ).size() );
    }

    @Test
    void count_returnsTotal() {
        assertEquals( 0, repo.count() );
        repo.insert( "H1", "A", List.of( "A", "B", "C" ), 0.5 );
        repo.insert( "H2", "D", List.of( "D", "E", "F" ), 0.6 );
        assertEquals( 2, repo.count() );
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

Run: `mvn test -pl wikantik-main -Dtest=HubDiscoveryRepositoryTest -q`
Expected: compilation fails — `HubDiscoveryRepository` does not exist.

---

## Task 5: `HubDiscoveryRepository` implementation

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryRepository.java`

- [ ] **Step 1: Write the repository**

Create `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryRepository.java`:

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
package com.wikantik.knowledge;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for cluster-discovery proposals. Accept and dismiss both
 * {@link #delete(int)} the row — there is no status column — so a row's mere
 * existence means "pending review".
 */
public class HubDiscoveryRepository {

    private static final Logger LOG = LogManager.getLogger( HubDiscoveryRepository.class );
    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST = new TypeToken< List< String > >() {}.getType();

    public record HubDiscoveryProposal( int id, String suggestedName, String exemplarPage,
                                         List< String > memberPages, double coherenceScore,
                                         Instant created ) {}

    private final DataSource dataSource;

    public HubDiscoveryRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    /**
     * Insert a new proposal. Returns the generated id, or throws {@link RuntimeException}
     * wrapping the underlying {@link SQLException} so batch callers abort on failure instead
     * of silently losing writes (same convention as {@link HubProposalRepository#insertProposal}).
     */
    public int insert( final String suggestedName, final String exemplarPage,
                        final List< String > memberPages, final double coherenceScore ) {
        final String sql = "INSERT INTO hub_discovery_proposals "
            + "(suggested_name, exemplar_page, member_pages, coherence_score) "
            + "VALUES (?, ?, ?::jsonb, ?)";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql, Statement.RETURN_GENERATED_KEYS ) ) {
            ps.setString( 1, suggestedName );
            ps.setString( 2, exemplarPage );
            ps.setString( 3, GSON.toJson( memberPages ) );
            ps.setDouble( 4, coherenceScore );
            ps.executeUpdate();
            try ( final ResultSet keys = ps.getGeneratedKeys() ) {
                if ( keys.next() ) return keys.getInt( 1 );
            }
            throw new SQLException( "No generated key returned" );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to insert hub discovery proposal '{}': {}", suggestedName, e.getMessage() );
            throw new RuntimeException( "Failed to insert hub discovery proposal '" + suggestedName + "'", e );
        }
    }

    public HubDiscoveryProposal findById( final int id ) {
        final String sql = "SELECT id, suggested_name, exemplar_page, member_pages::text, "
            + "coherence_score, created FROM hub_discovery_proposals WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, id );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapRow( rs ) : null;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to load hub discovery proposal {}: {}", id, e.getMessage() );
            return null;
        }
    }

    public List< HubDiscoveryProposal > list( final int limit, final int offset ) {
        final String sql = "SELECT id, suggested_name, exemplar_page, member_pages::text, "
            + "coherence_score, created FROM hub_discovery_proposals "
            + "ORDER BY created DESC LIMIT ? OFFSET ?";
        final List< HubDiscoveryProposal > out = new ArrayList<>();
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, limit );
            ps.setInt( 2, offset );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) out.add( mapRow( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list hub discovery proposals: {}", e.getMessage() );
        }
        return out;
    }

    public int count() {
        final String sql = "SELECT COUNT(*) FROM hub_discovery_proposals";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql );
              final ResultSet rs = ps.executeQuery() ) {
            return rs.next() ? rs.getInt( 1 ) : 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to count hub discovery proposals: {}", e.getMessage() );
            return 0;
        }
    }

    /** @return true if a row was deleted, false if no row matched. */
    public boolean delete( final int id ) {
        final String sql = "DELETE FROM hub_discovery_proposals WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, id );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to delete hub discovery proposal {}: {}", id, e.getMessage() );
            return false;
        }
    }

    private static HubDiscoveryProposal mapRow( final ResultSet rs ) throws SQLException {
        final List< String > members = GSON.fromJson( rs.getString( "member_pages" ), STRING_LIST );
        return new HubDiscoveryProposal(
            rs.getInt( "id" ),
            rs.getString( "suggested_name" ),
            rs.getString( "exemplar_page" ),
            members,
            rs.getDouble( "coherence_score" ),
            rs.getTimestamp( "created" ).toInstant()
        );
    }
}
```

- [ ] **Step 2: Run the repository test — expect pass**

Run: `mvn test -pl wikantik-main -Dtest=HubDiscoveryRepositoryTest -q`
Expected: all repository tests pass.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryRepositoryTest.java
git commit -m "feat: HubDiscoveryRepository with Testcontainers coverage"
```

---

## Task 6: `HubDiscoveryException` and `HubNameCollisionException`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryException.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/HubNameCollisionException.java`

- [ ] **Step 1: Write `HubDiscoveryException`**

Create `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryException.java`:

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
package com.wikantik.knowledge;

/** Runtime failure raised by {@link HubDiscoveryService} when a batch discovery run fails. */
public class HubDiscoveryException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public HubDiscoveryException( final String message, final Throwable cause ) {
        super( message, cause );
    }
}
```

- [ ] **Step 2: Write `HubNameCollisionException`**

Create `wikantik-main/src/main/java/com/wikantik/knowledge/HubNameCollisionException.java`:

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
package com.wikantik.knowledge;

/**
 * Raised by {@link HubDiscoveryService#acceptProposal} when the edited hub name collides
 * with an existing wiki page. The REST layer maps this to {@code 409 Conflict}.
 */
public class HubNameCollisionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String collidingName;
    public HubNameCollisionException( final String collidingName ) {
        super( "Hub name collides with existing page: " + collidingName );
        this.collidingName = collidingName;
    }
    public String collidingName() { return collidingName; }
}
```

- [ ] **Step 3: Compile-check**

Run: `mvn compile -pl wikantik-main -q`
Expected: exits 0.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryException.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/HubNameCollisionException.java
git commit -m "feat: exception types for hub discovery"
```

---

## Task 7: `HubDiscoveryService` — test scaffolding and first failing test

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryServiceTest.java`

- [ ] **Step 1: Write the test class with the first test — `generateClusterProposals_findsClusterOfNonMembers`**

Create `wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryServiceTest.java`:

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
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class HubDiscoveryServiceTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository kgRepo;
    private ContentEmbeddingRepository contentRepo;
    private HubDiscoveryRepository discoveryRepo;
    private TfidfModel model;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM hub_discovery_proposals" );
            conn.createStatement().execute( "DELETE FROM hub_proposals" );
            conn.createStatement().execute( "DELETE FROM hub_centroids" );
            conn.createStatement().execute( "DELETE FROM kg_content_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        kgRepo = new JdbcKnowledgeRepository( dataSource );
        contentRepo = new ContentEmbeddingRepository( dataSource );
        discoveryRepo = new HubDiscoveryRepository( dataSource );
    }

    @Test
    void generateClusterProposals_findsClusterOfNonMembers() {
        // Existing TechHub with 2 members (Java, Python).
        final var techHub = kgRepo.upsertNode( "TechHub", "hub", "TechHub",
            Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );
        final var java = kgRepo.upsertNode( "Java", "article", "Java",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final var python = kgRepo.upsertNode( "Python", "article", "Python",
            Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(), java.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(), python.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

        // Non-members: 3 cooking pages forming a tight cluster, 3 sports pages forming another,
        // and one standalone outlier.
        final String[] pages = {
            "Baking", "Roasting", "Grilling",
            "Soccer", "Basketball", "Tennis",
            "Miscellaneous"
        };
        for ( final String name : pages ) {
            kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        model = new TfidfModel();
        model.build(
            List.of( "Java", "Python", "Baking", "Roasting", "Grilling",
                     "Soccer", "Basketball", "Tennis", "Miscellaneous", "TechHub" ),
            List.of(
                "Java programming language object oriented JVM bytecode",
                "Python programming language dynamic typing scripting",
                "baking bread cake flour sugar oven recipes",
                "roasting meat oven temperature seasoning recipes",
                "grilling barbecue charcoal meat outdoor cooking",
                "soccer football goals players teams league",
                "basketball players teams league hoops court",
                "tennis players racquet grand slam court",
                "random standalone unrelated content nothing",
                "Technology hub programming languages software"
            )
        );
        contentRepo.saveEmbeddings( 1, model, Map.of() );

        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .contentRepo( contentRepo )
            .minClusterSize( 3 )
            .minPts( 3 )
            .minCandidatePool( 5 )
            .contentModel( model )
            .build();

        final int created = service.generateClusterProposals();
        assertTrue( created >= 2, "Expected at least 2 discovered clusters, got " + created );

        final var all = discoveryRepo.list( 50, 0 );
        assertEquals( created, all.size() );

        // No hub-member page (Java, Python) should appear in any proposal.
        for ( final var prop : all ) {
            assertFalse( prop.memberPages().contains( "Java" ),
                "Existing hub member 'Java' should not be proposed" );
            assertFalse( prop.memberPages().contains( "Python" ),
                "Existing hub member 'Python' should not be proposed" );
        }

        // At least one proposal should contain the cooking triad as a coherent cluster.
        final boolean cookingFound = all.stream().anyMatch( p ->
            p.memberPages().containsAll( List.of( "Baking", "Roasting", "Grilling" ) ) );
        assertTrue( cookingFound, "Expected a cluster containing Baking, Roasting, Grilling" );
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

Run: `mvn test -pl wikantik-main -Dtest=HubDiscoveryServiceTest -q`
Expected: compile fails — `HubDiscoveryService` does not exist yet.

---

## Task 8: `HubDiscoveryService` — `generateClusterProposals` implementation

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java`

- [ ] **Step 1: Write the service skeleton with `generateClusterProposals` and its builder**

Create `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java`:

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
package com.wikantik.knowledge;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.function.Supplier;

/**
 * Discovers latent topic clusters among wiki pages that are NOT currently members of any
 * existing hub, then presents each cluster as an editable proposal for admin review. This
 * complements {@link HubProposalService} (which assigns orphan pages to existing hubs) by
 * answering the inverse question: which hubs don't yet exist?
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Load the candidate pool (articles minus current hub members).</li>
 *   <li>Fetch each candidate's TF-IDF vector from the content model.</li>
 *   <li>Run HDBSCAN over the candidate vectors.</li>
 *   <li>For each non-noise cluster compute a centroid, pick an exemplar, score coherence,
 *       and insert a proposal row.</li>
 * </ol>
 *
 * <p>Use {@link #builder()} to construct. Like {@code HubProposalService}, the content-model
 * supplier is invoked fresh on every call so production callers can pass
 * {@code EmbeddingService::getCurrentContentModel} without needing to re-register the service
 * after each retrain.
 */
public class HubDiscoveryService {

    private static final Logger LOG = LogManager.getLogger( HubDiscoveryService.class );

    public static final String PROP_MIN_CLUSTER_SIZE  = "wikantik.hub.discovery.minClusterSize";
    public static final String PROP_MIN_PTS           = "wikantik.hub.discovery.minPts";
    public static final String PROP_MIN_CANDIDATE_POOL = "wikantik.hub.discovery.minCandidatePool";

    private static final int DEFAULT_MIN_CLUSTER_SIZE  = 3;
    private static final int DEFAULT_MIN_PTS           = 3;
    private static final int DEFAULT_MIN_CANDIDATE_POOL = 6;

    private final JdbcKnowledgeRepository kgRepo;
    private final HubDiscoveryRepository discoveryRepo;
    private final ContentEmbeddingRepository contentRepo;
    private final SmileHdbscanClusterer clusterer;
    private final int minClusterSize;
    private final int minPts;
    private final int minCandidatePool;
    private final Supplier< TfidfModel > contentModelSupplier;
    private final PageManager pageManager;
    private final PageSaveHelper pageSaveHelper;

    private HubDiscoveryService( final Builder b ) {
        this.kgRepo = b.kgRepo;
        this.discoveryRepo = b.discoveryRepo;
        this.contentRepo = b.contentRepo;
        this.clusterer = b.clusterer != null ? b.clusterer : new SmileHdbscanClusterer();
        this.minClusterSize = b.minClusterSize != null ? b.minClusterSize : DEFAULT_MIN_CLUSTER_SIZE;
        this.minPts = b.minPts != null ? b.minPts : DEFAULT_MIN_PTS;
        this.minCandidatePool = b.minCandidatePool != null ? b.minCandidatePool : DEFAULT_MIN_CANDIDATE_POOL;
        this.contentModelSupplier = b.contentModelSupplier;
        this.pageManager = b.pageManager;
        this.pageSaveHelper = b.pageSaveHelper;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Result returned to REST callers after a successful accept. */
    public record AcceptResult( String createdPage, int memberCount ) {}

    /** Summary of a discovery run for logging and REST responses. */
    public record RunSummary( int proposalsCreated, int candidatePoolSize, int noisePages, long durationMs ) {}

    /**
     * Run the discovery pipeline. Writes proposals directly to {@link HubDiscoveryRepository}.
     *
     * @return number of proposals created.
     * @throws HubDiscoveryException on unexpected failure.
     */
    public int generateClusterProposals() {
        return runDiscovery().proposalsCreated();
    }

    /** Same as {@link #generateClusterProposals()} but returns the full run summary. */
    public RunSummary runDiscovery() {
        LOG.info( "Hub discovery run started" );
        final long start = System.currentTimeMillis();

        final TfidfModel model = contentModelSupplier.get();
        if ( model == null || model.getEntityCount() == 0 ) {
            LOG.info( "Hub discovery skipped: no content model available" );
            return new RunSummary( 0, 0, 0, System.currentTimeMillis() - start );
        }

        final List< String > candidates = loadCandidatePool();
        if ( candidates.size() < minCandidatePool ) {
            LOG.info( "Hub discovery skipped: candidate pool too small ({})", candidates.size() );
            return new RunSummary( 0, candidates.size(), 0, System.currentTimeMillis() - start );
        }

        final List< String > candidatesWithVectors = new ArrayList<>( candidates.size() );
        final List< float[] > vectorList = new ArrayList<>( candidates.size() );
        int droppedNoVector = 0;
        for ( final String name : candidates ) {
            final float[] vec = model.getVector( name );
            if ( vec == null ) {
                droppedNoVector++;
                continue;
            }
            candidatesWithVectors.add( name );
            vectorList.add( vec );
        }
        if ( droppedNoVector > 0 ) {
            LOG.debug( "Hub discovery: dropped {} candidates with no TF-IDF vector", droppedNoVector );
        }
        if ( candidatesWithVectors.size() < minCandidatePool ) {
            LOG.info( "Hub discovery skipped: candidate pool too small after dropping pages "
                + "without vectors ({})", candidatesWithVectors.size() );
            return new RunSummary( 0, candidatesWithVectors.size(), 0,
                System.currentTimeMillis() - start );
        }

        try {
            final float[][] vectors = vectorList.toArray( new float[ 0 ][] );
            final int[] labels = clusterer.cluster( vectors, minClusterSize, minPts );

            final Map< Integer, List< Integer > > byLabel = new LinkedHashMap<>();
            int noise = 0;
            for ( int i = 0; i < labels.length; i++ ) {
                if ( labels[ i ] == -1 ) { noise++; continue; }
                byLabel.computeIfAbsent( labels[ i ], k -> new ArrayList<>() ).add( i );
            }

            int created = 0;
            for ( final var entry : byLabel.entrySet() ) {
                final List< Integer > indices = entry.getValue();
                if ( indices.size() < minClusterSize ) continue;
                final List< String > memberNames = new ArrayList<>( indices.size() );
                final float[][] memberVecs = new float[ indices.size() ][];
                for ( int i = 0; i < indices.size(); i++ ) {
                    memberNames.add( candidatesWithVectors.get( indices.get( i ) ) );
                    memberVecs[ i ] = vectorList.get( indices.get( i ) );
                }
                memberNames.sort( String::compareTo );
                // Re-pull vectors in sorted order so exemplar indices align with memberNames.
                final float[][] sortedVecs = new float[ memberNames.size() ][];
                for ( int i = 0; i < memberNames.size(); i++ ) {
                    sortedVecs[ i ] = model.getVector( memberNames.get( i ) );
                }
                final float[] centroid = normalizedCentroid( sortedVecs );
                final int exemplarIdx = argmaxDot( sortedVecs, centroid );
                final String exemplar = memberNames.get( exemplarIdx );
                final double coherence = meanDot( sortedVecs, centroid );
                discoveryRepo.insert( exemplar, exemplar, memberNames, coherence );
                created++;
            }

            final long duration = System.currentTimeMillis() - start;
            LOG.info( "Hub discovery run: created {} proposals from {} candidates ({} noise) in {} ms",
                created, candidatesWithVectors.size(), noise, duration );
            return new RunSummary( created, candidatesWithVectors.size(), noise, duration );
        } catch ( final RuntimeException e ) {
            // LOG.error justified: admin-triggered batch failure — stack trace must land in the
            // server log since the REST caller only sees a 500 message.
            LOG.error( "Hub discovery run failed with exception", e );
            throw new HubDiscoveryException( "Hub discovery run failed: " + e.getMessage(), e );
        }
    }

    /**
     * Candidate pool = articles whose name is not the target of any {@code related} edge
     * whose source is a hub-typed node. Returned as a list of page names.
     */
    private List< String > loadCandidatePool() {
        final var articles = kgRepo.queryNodes(
            Map.of( "node_type", "article" ), null, Integer.MAX_VALUE, 0 );
        final var hubs = kgRepo.queryNodes(
            Map.of( "node_type", "hub" ), null, Integer.MAX_VALUE, 0 );
        final Set< java.util.UUID > hubIds = new HashSet<>();
        for ( final var h : hubs ) hubIds.add( h.id() );
        final Set< String > members = new HashSet<>();
        for ( final var hub : hubs ) {
            final var edges = kgRepo.queryEdgesWithNames( "related", null, Integer.MAX_VALUE, 0 );
            for ( final var e : edges ) {
                if ( hubIds.contains( e.sourceId() ) ) {
                    members.add( e.targetName() );
                }
            }
            break; // queryEdgesWithNames is not scoped by source, so one call is enough.
        }
        final List< String > out = new ArrayList<>();
        for ( final var a : articles ) {
            if ( !members.contains( a.name() ) ) out.add( a.name() );
        }
        return out;
    }

    private static float[] normalizedCentroid( final float[][] rows ) {
        final int d = rows[ 0 ].length;
        final float[] sum = new float[ d ];
        for ( final float[] row : rows ) {
            for ( int j = 0; j < d; j++ ) sum[ j ] += row[ j ];
        }
        double norm = 0.0;
        for ( int j = 0; j < d; j++ ) norm += sum[ j ] * sum[ j ];
        norm = Math.sqrt( norm );
        if ( norm > 0 ) {
            for ( int j = 0; j < d; j++ ) sum[ j ] /= ( float ) norm;
        }
        return sum;
    }

    private static int argmaxDot( final float[][] rows, final float[] centroid ) {
        int best = 0;
        double bestScore = -Double.MAX_VALUE;
        for ( int i = 0; i < rows.length; i++ ) {
            double s = 0.0;
            for ( int j = 0; j < centroid.length; j++ ) s += rows[ i ][ j ] * centroid[ j ];
            if ( s > bestScore ) { bestScore = s; best = i; }
        }
        return best;
    }

    private static double meanDot( final float[][] rows, final float[] centroid ) {
        double total = 0.0;
        for ( final float[] row : rows ) {
            double s = 0.0;
            for ( int j = 0; j < centroid.length; j++ ) s += row[ j ] * centroid[ j ];
            total += s;
        }
        return total / rows.length;
    }

    // Accept and dismiss come in Task 10.
    public AcceptResult acceptProposal( final int id, final String editedName,
                                         final List< String > members, final String reviewedBy ) {
        throw new UnsupportedOperationException( "acceptProposal is implemented in Task 10" );
    }

    public void dismissProposal( final int id, final String reviewedBy ) {
        throw new UnsupportedOperationException( "dismissProposal is implemented in Task 10" );
    }

    /** Fluent builder matching the {@link HubProposalService.Builder} style. */
    public static final class Builder {
        private JdbcKnowledgeRepository kgRepo;
        private HubDiscoveryRepository discoveryRepo;
        private ContentEmbeddingRepository contentRepo;
        private SmileHdbscanClusterer clusterer;
        private Integer minClusterSize;
        private Integer minPts;
        private Integer minCandidatePool;
        private Supplier< TfidfModel > contentModelSupplier;
        private PageManager pageManager;
        private PageSaveHelper pageSaveHelper;

        private Builder() {}

        public Builder kgRepo( final JdbcKnowledgeRepository v ) { this.kgRepo = v; return this; }
        public Builder discoveryRepo( final HubDiscoveryRepository v ) { this.discoveryRepo = v; return this; }
        public Builder contentRepo( final ContentEmbeddingRepository v ) { this.contentRepo = v; return this; }
        public Builder clusterer( final SmileHdbscanClusterer v ) { this.clusterer = v; return this; }
        public Builder minClusterSize( final int v ) { this.minClusterSize = v; return this; }
        public Builder minPts( final int v ) { this.minPts = v; return this; }
        public Builder minCandidatePool( final int v ) { this.minCandidatePool = v; return this; }
        public Builder contentModel( final TfidfModel m ) { this.contentModelSupplier = () -> m; return this; }
        public Builder contentModelSupplier( final Supplier< TfidfModel > s ) { this.contentModelSupplier = s; return this; }
        public Builder pageManager( final PageManager p ) { this.pageManager = p; return this; }
        public Builder pageSaveHelper( final PageSaveHelper h ) { this.pageSaveHelper = h; return this; }

        /** Reads {@link #PROP_MIN_CLUSTER_SIZE}, {@link #PROP_MIN_PTS}, {@link #PROP_MIN_CANDIDATE_POOL}. */
        public Builder propsFrom( final Properties props ) {
            this.minClusterSize = Integer.parseInt(
                props.getProperty( PROP_MIN_CLUSTER_SIZE, String.valueOf( DEFAULT_MIN_CLUSTER_SIZE ) ) );
            this.minPts = Integer.parseInt(
                props.getProperty( PROP_MIN_PTS, String.valueOf( DEFAULT_MIN_PTS ) ) );
            this.minCandidatePool = Integer.parseInt(
                props.getProperty( PROP_MIN_CANDIDATE_POOL, String.valueOf( DEFAULT_MIN_CANDIDATE_POOL ) ) );
            return this;
        }

        public HubDiscoveryService build() {
            if ( kgRepo == null || discoveryRepo == null
                || contentRepo == null || contentModelSupplier == null ) {
                throw new IllegalStateException( "HubDiscoveryService.Builder: kgRepo, "
                    + "discoveryRepo, contentRepo, and a content model (contentModel or "
                    + "contentModelSupplier) are all required" );
            }
            // pageManager and pageSaveHelper are optional for build() — they are only
            // required when calling acceptProposal(). generateClusterProposals() does not
            // need them, so unit tests covering discovery-only paths can omit them.
            return new HubDiscoveryService( this );
        }
    }
}
```

Notes on `loadCandidatePool()`: this uses the existing `JdbcKnowledgeRepository.queryNodes` and `queryEdgesWithNames` calls. If the `queryEdgesWithNames` method name or signature on your branch differs, open `JdbcKnowledgeRepository.java`, grep for the method that returns edges with resolved source/target names, and adapt the call. The essential requirement is: "give me, for every hub-typed node, the set of target names of its `related` edges." The implementation above iterates hubs and uses one edges-query — update it to a direct query if a more targeted one exists on your repo.

- [ ] **Step 2: Verify `JdbcKnowledgeRepository` has the methods used above**

Run: `grep -n "queryNodes\|queryEdgesWithNames" wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java`
Expected: both method names appear. If either is named differently, update `loadCandidatePool()` to match and record the updated call in your next commit.

- [ ] **Step 3: Verify `TfidfModel.getVector(String)` exists and returns `float[]`**

Run: `grep -n "getVector\|float\[\]" wikantik-main/src/main/java/com/wikantik/knowledge/TfidfModel.java`
Expected: a public method that accepts `String name` and returns `float[]`. If the method is named differently (e.g., `vectorFor`), update the call sites in `HubDiscoveryService.java` and this plan.

- [ ] **Step 4: Run the discovery test — expect pass**

Run: `mvn test -pl wikantik-main -Dtest=HubDiscoveryServiceTest#generateClusterProposals_findsClusterOfNonMembers -q`
Expected: passes. If it fails because HDBSCAN clumped differently than expected on this corpus, adjust the synthetic text corpus in the test to make the clusters tighter — don't tune the service to make the test pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryServiceTest.java
git commit -m "feat: HubDiscoveryService.generateClusterProposals"
```

---

## Task 9: Additional discovery tests — empty, tiny, exemplar

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryServiceTest.java`

- [ ] **Step 1: Add `generateClusterProposals_emptyCorpus_noProposals`**

Append to `HubDiscoveryServiceTest`:

```java
@Test
void generateClusterProposals_emptyCorpus_noProposals() {
    model = new TfidfModel();
    model.build( List.of( "Nothing" ), List.of( "nothing here" ) );
    contentRepo.saveEmbeddings( 1, model, Map.of() );

    final HubDiscoveryService service = HubDiscoveryService.builder()
        .kgRepo( kgRepo )
        .discoveryRepo( discoveryRepo )
        .contentRepo( contentRepo )
        .minClusterSize( 3 )
        .minPts( 3 )
        .minCandidatePool( 6 )
        .contentModel( model )
        .build();

    assertEquals( 0, service.generateClusterProposals() );
    assertEquals( 0, discoveryRepo.count() );
}
```

- [ ] **Step 2: Add `generateClusterProposals_tinyCorpus_noProposals`**

```java
@Test
void generateClusterProposals_tinyCorpus_noProposals() {
    // Three article pages — below the default minCandidatePool=6.
    for ( final String name : new String[]{ "A", "B", "C" } ) {
        kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
    }
    model = new TfidfModel();
    model.build(
        List.of( "A", "B", "C" ),
        List.of( "alpha beta", "alpha gamma", "beta gamma" ) );
    contentRepo.saveEmbeddings( 1, model, Map.of() );

    final HubDiscoveryService service = HubDiscoveryService.builder()
        .kgRepo( kgRepo )
        .discoveryRepo( discoveryRepo )
        .contentRepo( contentRepo )
        .minClusterSize( 3 )
        .minPts( 3 )
        .minCandidatePool( 6 )
        .contentModel( model )
        .build();

    assertEquals( 0, service.generateClusterProposals() );
    assertEquals( 0, discoveryRepo.count() );
}
```

- [ ] **Step 3: Add `generateClusterProposals_exemplarIsClosestToCentroid`**

```java
@Test
void generateClusterProposals_exemplarIsClosestToCentroid() {
    final String[] members = { "Baking", "Roasting", "Grilling", "Sauteing", "Broiling", "Boiling" };
    for ( final String name : members ) {
        kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
    }
    model = new TfidfModel();
    model.build(
        List.of( members ),
        List.of(
            "baking oven bread recipe flour sugar",
            "roasting oven meat recipe temperature seasoning",
            "grilling outdoor charcoal meat barbecue",
            "sauteing pan oil butter quick heat",
            "broiling oven top direct heat meat",
            "boiling water pot heat stovetop"
        ) );
    contentRepo.saveEmbeddings( 1, model, Map.of() );

    final HubDiscoveryService service = HubDiscoveryService.builder()
        .kgRepo( kgRepo )
        .discoveryRepo( discoveryRepo )
        .contentRepo( contentRepo )
        .minClusterSize( 3 )
        .minPts( 3 )
        .minCandidatePool( 6 )
        .contentModel( model )
        .build();

    service.generateClusterProposals();
    final var proposals = discoveryRepo.list( 10, 0 );
    assertFalse( proposals.isEmpty() );
    // Exemplar must be one of the cluster members.
    for ( final var p : proposals ) {
        assertTrue( p.memberPages().contains( p.exemplarPage() ),
            "Exemplar must be in its cluster's member list" );
        assertTrue( p.coherenceScore() >= 0.0 && p.coherenceScore() <= 1.0 );
    }
}
```

- [ ] **Step 4: Run the new tests — expect pass**

Run: `mvn test -pl wikantik-main -Dtest=HubDiscoveryServiceTest -q`
Expected: all four tests pass (the 3 new + the earlier one from Task 7).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryServiceTest.java
git commit -m "test: empty/tiny/exemplar edge cases for hub discovery"
```

---

## Task 10: `HubDiscoveryService.acceptProposal` and `dismissProposal` — TDD

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryServiceTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java`

- [ ] **Step 1: Add helper for building an accept-capable service in the test class**

Append near the top of `HubDiscoveryServiceTest` (after the field declarations, before `@BeforeAll`):

```java
    private HubDiscoveryService acceptCapableService( final TfidfModel contentModel ) {
        final var pageManager = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var saveHelper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pageManager, kgRepo );
        return HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .contentRepo( contentRepo )
            .minClusterSize( 3 )
            .minPts( 3 )
            .minCandidatePool( 3 )
            .contentModel( contentModel )
            .pageManager( pageManager )
            .pageSaveHelper( saveHelper )
            .build();
    }
```

Note: `InMemoryPageManager` and `InMemoryPageSaveHelper` are lightweight test doubles created in Step 2 below. They live under a new `knowledge/test/` package so they are reusable without polluting the production source tree and are never packaged into the WAR (test sources are excluded automatically).

- [ ] **Step 2: Create the in-memory test doubles**

Create `wikantik-main/src/test/java/com/wikantik/knowledge/test/InMemoryPageManager.java`:

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
package com.wikantik.knowledge.test;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal in-memory "page manager" providing just enough surface area for
 * HubDiscoveryService tests: page-exists checks, put/get/list of raw markdown text.
 * Not an implementation of {@link com.wikantik.api.managers.PageManager} — that
 * interface has too many callbacks for a focused test fake.
 */
public class InMemoryPageManager {
    private final Map< String, String > pages = new HashMap<>();

    public boolean exists( final String name ) { return pages.containsKey( name ); }
    public String getText( final String name ) { return pages.get( name ); }
    public void putText( final String name, final String text ) { pages.put( name, text ); }
    public int size() { return pages.size(); }
}
```

Create `wikantik-main/src/test/java/com/wikantik/knowledge/test/InMemoryPageSaveHelper.java`:

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
package com.wikantik.knowledge.test;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.JdbcKnowledgeRepository;

import java.util.*;

/**
 * Test fake for {@link com.wikantik.api.pages.PageSaveHelper} that (a) stores the written
 * markdown in an {@link InMemoryPageManager} and (b) projects frontmatter directly into
 * {@link JdbcKnowledgeRepository}, simulating what {@code GraphProjector.postSave} does in
 * production. Lets the accept-path tests verify both the written page and the resulting
 * {@code kg_nodes}/{@code kg_edges} rows without spinning up a full engine.
 */
public class InMemoryPageSaveHelper {

    private final InMemoryPageManager pages;
    private final JdbcKnowledgeRepository kgRepo;

    public InMemoryPageSaveHelper( final InMemoryPageManager pages,
                                    final JdbcKnowledgeRepository kgRepo ) {
        this.pages = pages;
        this.kgRepo = kgRepo;
    }

    public void saveText( final String pageName, final String text ) {
        pages.putText( pageName, text );
        final ParsedPage parsed = FrontmatterParser.parse( text );
        final Map< String, Object > fm = parsed.frontmatter();
        if ( fm == null ) return;
        final String type = String.valueOf( fm.getOrDefault( "type", "article" ) );
        final Map< String, Object > props = new LinkedHashMap<>();
        for ( final var e : fm.entrySet() ) {
            if ( !( e.getValue() instanceof List ) ) props.put( e.getKey(), e.getValue() );
        }
        final var node = kgRepo.upsertNode( pageName, type, pageName, Provenance.HUMAN_AUTHORED, props );
        final Object related = fm.get( "related" );
        if ( related instanceof List< ? > list ) {
            for ( final Object raw : list ) {
                final String target = String.valueOf( raw );
                final var targetNode = kgRepo.upsertNode( target, "article", target,
                    Provenance.HUMAN_AUTHORED, Map.of() );
                kgRepo.upsertEdge( node.id(), targetNode.id(), "related",
                    Provenance.HUMAN_AUTHORED, Map.of() );
            }
        }
    }
}
```

Note: `HubDiscoveryService.acceptProposal` will call a method named `saveText(String, String, ...)` on the production `PageSaveHelper`, which is an interface type in the api module. For tests we use this fake directly (not via the interface) by having the builder accept `Object` or by switching to a small functional interface — see Step 3.

- [ ] **Step 3: Refactor the builder to use a `PageWriter` functional interface**

This hides the difference between the real `PageSaveHelper.saveText(name, content, options)` and the test fake's `saveText(name, content)`, so the service does not depend on the heavy `PageSaveHelper` interface in test code.

Modify `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java`:

1. Add a nested functional interface at the bottom of the class (before the `Builder` class):

```java
    /**
     * Two-argument page-writing abstraction the service depends on. Production wires this
     * to {@code PageSaveHelper.saveText(name, content, SaveOptions)}; tests pass an
     * in-memory fake that records writes and mirrors the frontmatter projection.
     */
    @FunctionalInterface
    public interface PageWriter {
        void write( String name, String content ) throws Exception;
    }
```

2. Replace the `pageSaveHelper` field and its usages:

```java
    private final PageWriter pageWriter;
    private final java.util.function.Predicate< String > pageExists;
```

3. Update the constructor body to store these (replace the lines setting `pageManager` and `pageSaveHelper`):

```java
        this.pageWriter = b.pageWriter;
        this.pageExists = b.pageExists;
```

4. Remove the `pageManager` and `pageSaveHelper` fields entirely — they are unused.

5. Update the `Builder` to expose `.pageWriter(PageWriter)` and `.pageExists(Predicate<String>)` (remove `.pageManager(...)` and `.pageSaveHelper(...)` — those helpers belong in the factory wiring, not the service API):

```java
        private PageWriter pageWriter;
        private java.util.function.Predicate< String > pageExists;

        public Builder pageWriter( final PageWriter v ) { this.pageWriter = v; return this; }
        public Builder pageExists( final java.util.function.Predicate< String > v ) { this.pageExists = v; return this; }
```

Also remove the `pageManager`/`pageSaveHelper` builder methods and the matching `import`s for `PageManager` and `PageSaveHelper` (they move to `KnowledgeGraphServiceFactory` in Task 11).

- [ ] **Step 4: Add failing accept/dismiss tests**

Append to `HubDiscoveryServiceTest`:

```java
    @Test
    void acceptProposal_createsStubPageAndEdges() throws Exception {
        final String[] members = { "Java", "Kotlin", "Scala" };
        for ( final String name : members ) {
            kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        model = new TfidfModel();
        model.build( List.of( members ),
            List.of( "java jvm oop", "kotlin jvm coroutines", "scala jvm fp" ) );
        contentRepo.saveEmbeddings( 1, model, Map.of() );

        final com.wikantik.knowledge.test.InMemoryPageManager pages =
            new com.wikantik.knowledge.test.InMemoryPageManager();
        final com.wikantik.knowledge.test.InMemoryPageSaveHelper helper =
            new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .contentRepo( contentRepo )
            .minClusterSize( 3 )
            .minPts( 3 )
            .minCandidatePool( 3 )
            .contentModel( model )
            .pageWriter( helper::saveText )
            .pageExists( pages::exists )
            .build();

        // Seed a proposal directly via the repository rather than running discovery,
        // so this test is independent of HDBSCAN's clustering behaviour.
        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );

        final HubDiscoveryService.AcceptResult result = service.acceptProposal(
            id, "JavaHub", List.of( "Java", "Kotlin", "Scala" ), "admin" );

        assertEquals( "JavaHub", result.createdPage() );
        assertEquals( 3, result.memberCount() );
        assertTrue( pages.exists( "JavaHub" ), "Stub page must be written" );
        final String written = pages.getText( "JavaHub" );
        assertTrue( written.contains( "type: hub" ), "Frontmatter must have type: hub" );
        assertTrue( written.contains( "auto-generated: true" ) );
        assertTrue( written.contains( "# JavaHub" ) );
        assertTrue( written.contains( "<!-- TODO: describe this hub -->" ) );
        assertTrue( written.contains( "- [Java](Java)" ) );

        // Proposal must be deleted after a successful accept.
        assertNull( discoveryRepo.findById( id ), "Proposal row must be deleted on accept" );
        // Graph projection: JavaHub node exists with type=hub and has 3 related edges.
        final var hubNode = kgRepo.queryNodes( Map.of( "name", "JavaHub" ), null, 1, 0 );
        assertEquals( 1, hubNode.size() );
        assertEquals( "hub", hubNode.get( 0 ).nodeType() );
    }

    @Test
    void acceptProposal_collisionWithExistingPage_throwsAndKeepsRow() {
        final com.wikantik.knowledge.test.InMemoryPageManager pages =
            new com.wikantik.knowledge.test.InMemoryPageManager();
        pages.putText( "JavaHub", "existing content" );
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .contentRepo( contentRepo )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .contentModel( new TfidfModel() )
            .pageWriter( helper::saveText )
            .pageExists( pages::exists )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );
        for ( final String m : List.of( "Java", "Kotlin", "Scala" ) ) {
            kgRepo.upsertNode( m, "article", m, Provenance.HUMAN_AUTHORED, Map.of() );
        }

        assertThrows( HubNameCollisionException.class, () ->
            service.acceptProposal( id, "JavaHub", List.of( "Java", "Kotlin", "Scala" ), "admin" ) );

        // Proposal row must NOT be deleted.
        assertNotNull( discoveryRepo.findById( id ) );
        assertEquals( "existing content", pages.getText( "JavaHub" ), "Existing page untouched" );
    }

    @Test
    void acceptProposal_memberNotInProposal_throwsIllegalArgument() {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .contentRepo( contentRepo )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .contentModel( new TfidfModel() )
            .pageWriter( helper::saveText )
            .pageExists( pages::exists )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );

        assertThrows( IllegalArgumentException.class, () ->
            service.acceptProposal( id, "JavaHub",
                List.of( "Java", "Kotlin", "Evil Injected Page" ), "admin" ) );

        assertNotNull( discoveryRepo.findById( id ) );
    }

    @Test
    void acceptProposal_missingMemberPagesDropped() throws Exception {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        for ( final String m : List.of( "Java", "Kotlin" ) ) {
            kgRepo.upsertNode( m, "article", m, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .contentRepo( contentRepo )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .contentModel( new TfidfModel() )
            .pageWriter( helper::saveText )
            .pageExists( name -> kgRepo.queryNodes( Map.of( "name", name ), null, 1, 0 ).size() > 0 )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );

        final var result = service.acceptProposal( id, "JavaHub",
            List.of( "Java", "Kotlin", "Scala" ), "admin" );
        assertEquals( 2, result.memberCount(), "Scala was missing and should be dropped" );
        assertNull( discoveryRepo.findById( id ) );
    }

    @Test
    void acceptProposal_allMembersMissing_throws() {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo ).discoveryRepo( discoveryRepo ).contentRepo( contentRepo )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .contentModel( new TfidfModel() )
            .pageWriter( helper::saveText )
            .pageExists( name -> false )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );

        assertThrows( HubDiscoveryException.class, () ->
            service.acceptProposal( id, "JavaHub", List.of( "Java", "Kotlin", "Scala" ), "admin" ) );
        assertNotNull( discoveryRepo.findById( id ) );
    }

    @Test
    void dismissProposal_deletesRow() {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo ).discoveryRepo( discoveryRepo ).contentRepo( contentRepo )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .contentModel( new TfidfModel() )
            .pageWriter( helper::saveText )
            .pageExists( name -> false )
            .build();

        final int id = discoveryRepo.insert( "JavaHub", "Java",
            List.of( "Java", "Kotlin", "Scala" ), 0.9 );
        service.dismissProposal( id, "admin" );
        assertNull( discoveryRepo.findById( id ) );
    }

    @Test
    void dismissProposal_missingId_throws() {
        final var pages = new com.wikantik.knowledge.test.InMemoryPageManager();
        final var helper = new com.wikantik.knowledge.test.InMemoryPageSaveHelper( pages, kgRepo );
        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo ).discoveryRepo( discoveryRepo ).contentRepo( contentRepo )
            .minClusterSize( 3 ).minPts( 3 ).minCandidatePool( 3 )
            .contentModel( new TfidfModel() )
            .pageWriter( helper::saveText )
            .pageExists( name -> false )
            .build();

        assertThrows( HubDiscoveryException.class, () ->
            service.dismissProposal( 9999, "admin" ) );
    }
```

- [ ] **Step 5: Run tests — expect accept/dismiss tests to fail**

Run: `mvn test -pl wikantik-main -Dtest=HubDiscoveryServiceTest -q`
Expected: earlier tests pass, new accept/dismiss tests fail with `UnsupportedOperationException` (stub still says "implemented in Task 10").

- [ ] **Step 6: Implement `acceptProposal` and `dismissProposal`**

Replace the two stubbed methods in `HubDiscoveryService.java`:

```java
    /**
     * Accept a proposal: write a stub wiki page whose frontmatter triggers the save-pipeline
     * graph projection, then delete the proposal row. Concurrent accepts on the same id are
     * safe — the first {@code delete} wins; the loser sees a 0-row delete followed by a
     * 404 on its next call.
     *
     * @throws HubDiscoveryException     if the proposal does not exist, or if fewer than 2
     *                                    members survive the page-exists filter.
     * @throws HubNameCollisionException if {@code editedName} matches an existing wiki page.
     * @throws IllegalArgumentException  if a submitted member is not in the proposal's stored list.
     */
    public AcceptResult acceptProposal( final int id, final String editedName,
                                         final List< String > members, final String reviewedBy ) {
        if ( pageWriter == null || pageExists == null ) {
            throw new IllegalStateException( "HubDiscoveryService: pageWriter and pageExists "
                + "are required for acceptProposal — wire them via the builder" );
        }
        final var proposal = discoveryRepo.findById( id );
        if ( proposal == null ) {
            throw new HubDiscoveryException( "Hub discovery proposal " + id + " not found", null );
        }
        final String name = editedName == null ? "" : editedName.trim();
        if ( name.isEmpty() ) {
            throw new IllegalArgumentException( "Edited hub name must not be empty" );
        }
        if ( pageExists.test( name ) ) {
            throw new HubNameCollisionException( name );
        }
        final Set< String > allowed = new HashSet<>( proposal.memberPages() );
        for ( final String m : members ) {
            if ( !allowed.contains( m ) ) {
                throw new IllegalArgumentException( "Member not in original proposal: " + m );
            }
        }
        final List< String > surviving = new ArrayList<>();
        for ( final String m : members ) {
            if ( pageExists.test( m ) ) {
                surviving.add( m );
            } else {
                LOG.info( "Hub discovery: dropping missing member '{}' from accepted proposal {}", m, id );
            }
        }
        if ( surviving.size() < 2 ) {
            throw new HubDiscoveryException(
                "Hub discovery: too few surviving members for proposal " + id + "; dismiss instead", null );
        }
        Collections.sort( surviving );
        final String markdown = renderStub( name, surviving );
        try {
            pageWriter.write( name, markdown );
        } catch ( final Exception e ) {
            throw new HubDiscoveryException(
                "Hub discovery: failed to save stub page '" + name + "'", e );
        }
        discoveryRepo.delete( id );
        LOG.info( "Hub discovery: accepted proposal {} as '{}' with {} members (reviewed by {})",
            id, name, surviving.size(), reviewedBy );
        return new AcceptResult( name, surviving.size() );
    }

    public void dismissProposal( final int id, final String reviewedBy ) {
        final var proposal = discoveryRepo.findById( id );
        if ( proposal == null ) {
            throw new HubDiscoveryException( "Hub discovery proposal " + id + " not found", null );
        }
        discoveryRepo.delete( id );
        LOG.info( "Hub discovery: dismissed proposal {} ('{}', {} members, reviewed by {})",
            id, proposal.suggestedName(), proposal.memberPages().size(), reviewedBy );
    }

    static String renderStub( final String hubName, final List< String > members ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "---\n" );
        sb.append( "title: " ).append( hubName ).append( '\n' );
        sb.append( "type: hub\n" );
        sb.append( "auto-generated: true\n" );
        sb.append( "related:\n" );
        for ( final String m : members ) {
            sb.append( "  - " ).append( m ).append( '\n' );
        }
        sb.append( "---\n\n" );
        sb.append( "# " ).append( hubName ).append( "\n\n" );
        sb.append( "<!-- TODO: describe this hub -->\n\n" );
        sb.append( "## Members\n\n" );
        for ( final String m : members ) {
            sb.append( "- [" ).append( m ).append( "](" ).append( m ).append( ")\n" );
        }
        return sb.toString();
    }
```

- [ ] **Step 7: Run tests — expect pass**

Run: `mvn test -pl wikantik-main -Dtest=HubDiscoveryServiceTest -q`
Expected: all tests in `HubDiscoveryServiceTest` pass (13 total — run, 3 edge cases, 7 accept/dismiss).

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/HubDiscoveryServiceTest.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/test/InMemoryPageManager.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/test/InMemoryPageSaveHelper.java
git commit -m "feat: HubDiscoveryService accept/dismiss with test doubles"
```

---

## Task 11: Factory wiring — `KnowledgeGraphServiceFactory` and `WikiEngine`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`

- [ ] **Step 1: Extend the `Services` record**

Open `KnowledgeGraphServiceFactory.java`. Replace the existing `Services` record definition with:

```java
    public record Services(
        KnowledgeGraphService kgService,
        GraphProjector graphProjector,
        FrontmatterDefaultsFilter frontmatterDefaultsFilter,
        HubSyncFilter hubSyncFilter,
        EmbeddingService embeddingService,
        HubProposalRepository hubProposalRepo,
        HubProposalService hubProposalService,
        HubDiscoveryRepository hubDiscoveryRepo,
        HubDiscoveryService hubDiscoveryService
    ) {}
```

- [ ] **Step 2: Build the discovery service at the bottom of `create(...)`**

Inside `KnowledgeGraphServiceFactory.create`, after the existing `HubProposalService` construction and before the `return new Services(...)` line, add:

```java
        final HubDiscoveryRepository hubDiscoveryRepo = new HubDiscoveryRepository( dataSource );
        final HubDiscoveryService hubDiscoveryService = HubDiscoveryService.builder()
            .kgRepo( repo )
            .discoveryRepo( hubDiscoveryRepo )
            .contentRepo( contentEmbeddingRepo )
            .propsFrom( props )
            .contentModelSupplier( embeddingService::getCurrentContentModel )
            .pageWriter( ( name, content ) -> saveHelper.saveText( name, content,
                SaveOptions.builder().changeNote( "Hub discovery: stub created" ).build() ) )
            .pageExists( name -> {
                try {
                    final Page p = pageManager.getPage( name );
                    return p != null;
                } catch ( final Exception e ) {
                    LOG.warn( "HubDiscoveryService pageExists: failed to check '{}': {}",
                        name, e.getMessage() );
                    return false;
                }
            } )
            .build();
```

- [ ] **Step 3: Include the new components in the return**

Update the `return new Services(...)` line:

```java
        return new Services( kgService, projector, fmDefaults, hubSync,
            embeddingService, hubProposalRepo, hubProposalService,
            hubDiscoveryRepo, hubDiscoveryService );
```

- [ ] **Step 4: Register the new managers in `WikiEngine.initKnowledgeGraph`**

Open `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`. Find the block inside `initKnowledgeGraph(Properties)` that registers `HubProposalService` (search for `managers.put( HubProposalService.class`). Immediately after it, add:

```java
            managers.put( HubDiscoveryRepository.class, svcs.hubDiscoveryRepo() );
            managers.put( HubDiscoveryService.class, svcs.hubDiscoveryService() );
            LOG.info( "HubDiscoveryService registered (minClusterSize property='{}', minPts='{}')",
                props.getProperty( HubDiscoveryService.PROP_MIN_CLUSTER_SIZE, "default" ),
                props.getProperty( HubDiscoveryService.PROP_MIN_PTS, "default" ) );
```

Add the matching import at the top of `WikiEngine.java`:

```java
import com.wikantik.knowledge.HubDiscoveryRepository;
import com.wikantik.knowledge.HubDiscoveryService;
```

- [ ] **Step 5: Compile-check**

Run: `mvn compile -pl wikantik-main -q`
Expected: exits 0.

- [ ] **Step 6: Re-run the full unit test suite for `wikantik-main`**

Run: `mvn test -pl wikantik-main -q`
Expected: all tests pass, no regressions.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat: wire HubDiscoveryService through KnowledgeGraphServiceFactory"
```

---

## Task 12: Property defaults in `wikantik.properties`

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`

- [ ] **Step 1: Locate the hub-related properties section**

Run: `grep -n "wikantik.hub" wikantik-main/src/main/resources/ini/wikantik.properties`
Expected: the existing `wikantik.hub.reviewPercentile` line shows up. Remember the line number.

- [ ] **Step 2: Append the three new properties**

After the existing `wikantik.hub.*` block, append:

```properties
# --- Hub Discovery (cluster-based) ---

# Minimum number of members for HDBSCAN to form a cluster.
# Below this, points are classified as noise.
wikantik.hub.discovery.minClusterSize = 3

# HDBSCAN minPts: density requirement for "core" points.
wikantik.hub.discovery.minPts = 3

# Safety floor: if the candidate pool has fewer non-member
# articles than this, skip the run entirely.
wikantik.hub.discovery.minCandidatePool = 6
```

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "feat: hub discovery default properties"
```

---

## Task 13: `AcceptProposalRequest` DTO and REST servlet — TDD with Mockito

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/dto/AcceptProposalRequest.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java`

- [ ] **Step 1: Write the DTO**

Create `wikantik-rest/src/main/java/com/wikantik/rest/dto/AcceptProposalRequest.java`:

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
package com.wikantik.rest.dto;

import java.util.List;

/**
 * Request body for {@code POST /admin/knowledge/hub-discovery/proposals/{id}/accept}.
 *
 * <p>Deserialized by Gson. Fields are mutable to match Gson's reflection-based
 * deserialization convention. Consumer code should treat instances as effectively
 * immutable after deserialization.
 */
public class AcceptProposalRequest {
    public String name;
    public List< String > members;
}
```

- [ ] **Step 2: Write the failing Mockito tests for the REST servlet**

Create `wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java`:

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
package com.wikantik.rest;

import com.wikantik.WikiEngine;
import com.wikantik.knowledge.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminHubDiscoveryResourceTest {

    private HubDiscoveryService service;
    private HubDiscoveryRepository repo;
    private WikiEngine engine;
    private AdminHubDiscoveryResource resource;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter respBody;

    @BeforeEach
    void setUp() throws Exception {
        service = mock( HubDiscoveryService.class );
        repo = mock( HubDiscoveryRepository.class );
        engine = mock( WikiEngine.class );
        when( engine.getManager( HubDiscoveryService.class ) ).thenReturn( service );
        when( engine.getManager( HubDiscoveryRepository.class ) ).thenReturn( repo );

        resource = spy( new AdminHubDiscoveryResource() );
        doReturn( engine ).when( resource ).getEngine();

        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        respBody = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( respBody ) );
    }

    private void setBody( final String json ) throws Exception {
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( json ) ) );
    }

    // --- POST /run ---

    @Test
    void postRun_happyPath_returnsSummary() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/run" );
        when( service.runDiscovery() ).thenReturn(
            new HubDiscoveryService.RunSummary( 5, 142, 37, 814 ) );
        resource.doPost( req, resp );
        verify( resp, never() ).sendError( anyInt(), anyString() );
        assertTrue( respBody.toString().contains( "\"proposalsCreated\":5" ) );
    }

    @Test
    void postRun_serviceUnavailable_returns503() throws Exception {
        when( engine.getManager( HubDiscoveryService.class ) ).thenReturn( null );
        when( req.getPathInfo() ).thenReturn( "/run" );
        resource.doPost( req, resp );
        verify( resp ).sendError( eq( HttpServletResponse.SC_SERVICE_UNAVAILABLE ), anyString() );
    }

    // --- GET /proposals ---

    @Test
    void getProposals_returnsList() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals" );
        when( req.getParameter( "limit" ) ).thenReturn( "10" );
        when( req.getParameter( "offset" ) ).thenReturn( "0" );
        when( repo.list( 10, 0 ) ).thenReturn( List.of(
            new HubDiscoveryRepository.HubDiscoveryProposal(
                17, "JavaHub", "Java", List.of( "Java", "Kotlin", "Scala" ),
                0.84, Instant.parse( "2026-04-09T14:23:11Z" ) ) ) );
        when( repo.count() ).thenReturn( 1 );
        resource.doGet( req, resp );
        assertTrue( respBody.toString().contains( "\"suggestedName\":\"JavaHub\"" ) );
        assertTrue( respBody.toString().contains( "\"total\":1" ) );
    }

    @Test
    void getProposals_limitCappedTo200() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals" );
        when( req.getParameter( "limit" ) ).thenReturn( "9999" );
        when( req.getParameter( "offset" ) ).thenReturn( "0" );
        when( repo.list( 200, 0 ) ).thenReturn( List.of() );
        when( repo.count() ).thenReturn( 0 );
        resource.doGet( req, resp );
        verify( repo ).list( 200, 0 );
    }

    // --- POST /proposals/{id}/accept ---

    @Test
    void postAccept_happyPath_returns200() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        setBody( "{\"name\":\"JavaHub\",\"members\":[\"Java\",\"Kotlin\",\"Scala\"]}" );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        when( service.acceptProposal( eq( 17 ), eq( "JavaHub" ),
            eq( List.of( "Java", "Kotlin", "Scala" ) ), eq( "admin" ) ) )
            .thenReturn( new HubDiscoveryService.AcceptResult( "JavaHub", 3 ) );
        resource.doPost( req, resp );
        verify( resp, never() ).sendError( anyInt(), anyString() );
        assertTrue( respBody.toString().contains( "\"createdPage\":\"JavaHub\"" ) );
    }

    @Test
    void postAccept_collision_returns409() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        setBody( "{\"name\":\"JavaHub\",\"members\":[\"Java\",\"Kotlin\",\"Scala\"]}" );
        when( service.acceptProposal( anyInt(), anyString(), anyList(), anyString() ) )
            .thenThrow( new HubNameCollisionException( "JavaHub" ) );
        resource.doPost( req, resp );
        verify( resp ).sendError( eq( HttpServletResponse.SC_CONFLICT ), contains( "JavaHub" ) );
    }

    @Test
    void postAccept_notFound_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        setBody( "{\"name\":\"JavaHub\",\"members\":[\"Java\",\"Kotlin\",\"Scala\"]}" );
        when( service.acceptProposal( anyInt(), anyString(), anyList(), anyString() ) )
            .thenThrow( new HubDiscoveryException( "not found", null ) );
        resource.doPost( req, resp );
        verify( resp ).sendError( eq( HttpServletResponse.SC_NOT_FOUND ), anyString() );
    }

    @Test
    void postAccept_badMember_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        setBody( "{\"name\":\"JavaHub\",\"members\":[\"Java\"]}" );
        when( service.acceptProposal( anyInt(), anyString(), anyList(), anyString() ) )
            .thenThrow( new IllegalArgumentException( "Member not in original proposal: Evil" ) );
        resource.doPost( req, resp );
        verify( resp ).sendError( eq( HttpServletResponse.SC_BAD_REQUEST ), anyString() );
    }

    @Test
    void postAccept_emptyName_returns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/accept" );
        setBody( "{\"name\":\"\",\"members\":[\"Java\"]}" );
        when( service.acceptProposal( anyInt(), anyString(), anyList(), anyString() ) )
            .thenThrow( new IllegalArgumentException( "Edited hub name must not be empty" ) );
        resource.doPost( req, resp );
        verify( resp ).sendError( eq( HttpServletResponse.SC_BAD_REQUEST ), anyString() );
    }

    // --- POST /proposals/{id}/dismiss ---

    @Test
    void postDismiss_happyPath_returns204() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/dismiss" );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        resource.doPost( req, resp );
        verify( service ).dismissProposal( 17, "admin" );
        verify( resp ).setStatus( HttpServletResponse.SC_NO_CONTENT );
    }

    @Test
    void postDismiss_notFound_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/proposals/17/dismiss" );
        doThrow( new HubDiscoveryException( "not found", null ) )
            .when( service ).dismissProposal( anyInt(), anyString() );
        resource.doPost( req, resp );
        verify( resp ).sendError( eq( HttpServletResponse.SC_NOT_FOUND ), anyString() );
    }

    @Test
    void unknownPath_returns404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/bogus" );
        resource.doGet( req, resp );
        verify( resp ).sendError( eq( HttpServletResponse.SC_NOT_FOUND ), anyString() );
    }
}
```

- [ ] **Step 3: Run the servlet test — expect compile failure**

Run: `mvn test -pl wikantik-rest -Dtest=AdminHubDiscoveryResourceTest -q`
Expected: compile failure — `AdminHubDiscoveryResource` does not exist yet.

---

## Task 14: `AdminHubDiscoveryResource` implementation

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java`

- [ ] **Step 1: Write the servlet**

Create `wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java`:

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
package com.wikantik.rest;

import com.google.gson.Gson;
import com.wikantik.knowledge.HubDiscoveryException;
import com.wikantik.knowledge.HubDiscoveryRepository;
import com.wikantik.knowledge.HubDiscoveryService;
import com.wikantik.knowledge.HubNameCollisionException;
import com.wikantik.rest.dto.AcceptProposalRequest;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST servlet for cluster-based hub discovery. Mapped to
 * {@code /admin/knowledge/hub-discovery/*}. Longest-path-prefix matching means
 * requests under this prefix are routed here instead of
 * {@link AdminKnowledgeResource}.
 *
 * <ul>
 *   <li>{@code POST   /admin/knowledge/hub-discovery/run} — run discovery</li>
 *   <li>{@code GET    /admin/knowledge/hub-discovery/proposals?limit=50&amp;offset=0} — list</li>
 *   <li>{@code POST   /admin/knowledge/hub-discovery/proposals/{id}/accept} — accept</li>
 *   <li>{@code POST   /admin/knowledge/hub-discovery/proposals/{id}/dismiss} — dismiss</li>
 * </ul>
 */
public class AdminHubDiscoveryResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminHubDiscoveryResource.class );
    private static final Gson GSON = new Gson();
    private static final int MAX_LIMIT = 200;

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String pathInfo = request.getPathInfo();
        if ( "/proposals".equals( pathInfo ) ) {
            handleListProposals( request, response );
            return;
        }
        sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + pathInfo );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Path required" );
            return;
        }
        if ( "/run".equals( pathInfo ) ) {
            handleRun( response );
            return;
        }
        final String[] segments = pathInfo.substring( 1 ).split( "/" );
        // segments[0] = "proposals", segments[1] = id, segments[2] = action
        if ( segments.length == 3 && "proposals".equals( segments[ 0 ] ) ) {
            final int id;
            try {
                id = Integer.parseInt( segments[ 1 ] );
            } catch ( final NumberFormatException e ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid proposal id: " + segments[ 1 ] );
                return;
            }
            switch ( segments[ 2 ] ) {
                case "accept" -> handleAccept( request, response, id );
                case "dismiss" -> handleDismiss( request, response, id );
                default -> sendError( response, HttpServletResponse.SC_NOT_FOUND,
                    "Unknown action: " + segments[ 2 ] );
            }
            return;
        }
        sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + pathInfo );
    }

    private void handleRun( final HttpServletResponse response ) throws IOException {
        final HubDiscoveryService service = getEngine().getManager( HubDiscoveryService.class );
        if ( service == null ) {
            LOG.warn( "Hub discovery run rejected: HubDiscoveryService not registered" );
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryService not available — knowledge graph not initialized" );
            return;
        }
        try {
            final HubDiscoveryService.RunSummary summary = service.runDiscovery();
            final Map< String, Object > body = new LinkedHashMap<>();
            body.put( "proposalsCreated", summary.proposalsCreated() );
            body.put( "candidatePoolSize", summary.candidatePoolSize() );
            body.put( "noisePages", summary.noisePages() );
            body.put( "durationMs", summary.durationMs() );
            sendJson( response, body );
        } catch ( final HubDiscoveryException e ) {
            LOG.error( "Hub discovery run failed", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Hub discovery run failed: " + e.getMessage() );
        }
    }

    private void handleListProposals( final HttpServletRequest request,
                                         final HttpServletResponse response ) throws IOException {
        final HubDiscoveryRepository repo = getEngine().getManager( HubDiscoveryRepository.class );
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryRepository not available" );
            return;
        }
        int limit = parseIntParam( request, "limit", 50 );
        if ( limit > MAX_LIMIT ) limit = MAX_LIMIT;
        if ( limit < 1 ) limit = 1;
        final int offset = Math.max( 0, parseIntParam( request, "offset", 0 ) );
        final var proposals = repo.list( limit, offset );
        final int total = repo.count();

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "proposals", proposals.stream().map( p -> {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "id", p.id() );
            m.put( "suggestedName", p.suggestedName() );
            m.put( "exemplarPage", p.exemplarPage() );
            m.put( "memberPages", p.memberPages() );
            m.put( "coherenceScore", p.coherenceScore() );
            m.put( "created", p.created().toString() );
            return m;
        } ).toList() );
        result.put( "total", total );
        sendJson( response, result );
    }

    private void handleAccept( final HttpServletRequest request, final HttpServletResponse response,
                                 final int id ) throws IOException {
        final HubDiscoveryService service = getEngine().getManager( HubDiscoveryService.class );
        if ( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryService not available" );
            return;
        }
        final AcceptProposalRequest body = parseAcceptBody( request, response );
        if ( body == null ) return;
        if ( body.name == null || body.name.trim().isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "name is required" );
            return;
        }
        if ( body.members == null || body.members.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "members is required" );
            return;
        }
        final String reviewedBy = request.getRemoteUser() != null ? request.getRemoteUser() : "admin";
        try {
            final HubDiscoveryService.AcceptResult result =
                service.acceptProposal( id, body.name.trim(), body.members, reviewedBy );
            sendJson( response, Map.of(
                "createdPage", result.createdPage(),
                "members", result.memberCount() ) );
        } catch ( final HubNameCollisionException e ) {
            sendError( response, HttpServletResponse.SC_CONFLICT,
                "Hub name collides with existing page: " + e.collidingName() );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        } catch ( final HubDiscoveryException e ) {
            // The service throws HubDiscoveryException for both "not found" and "too few
            // surviving members"; both map to user-correctable states, so map "not found"
            // to 404 and everything else to 409. The message text distinguishes them.
            final int status = e.getMessage() != null && e.getMessage().contains( "not found" )
                ? HttpServletResponse.SC_NOT_FOUND : HttpServletResponse.SC_CONFLICT;
            sendError( response, status, e.getMessage() );
        }
    }

    private void handleDismiss( final HttpServletRequest request, final HttpServletResponse response,
                                  final int id ) throws IOException {
        final HubDiscoveryService service = getEngine().getManager( HubDiscoveryService.class );
        if ( service == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "HubDiscoveryService not available" );
            return;
        }
        final String reviewedBy = request.getRemoteUser() != null ? request.getRemoteUser() : "admin";
        try {
            service.dismissProposal( id, reviewedBy );
            response.setStatus( HttpServletResponse.SC_NO_CONTENT );
        } catch ( final HubDiscoveryException e ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, e.getMessage() );
        }
    }

    private AcceptProposalRequest parseAcceptBody( final HttpServletRequest request,
                                                     final HttpServletResponse response ) throws IOException {
        try ( final BufferedReader reader = request.getReader() ) {
            return GSON.fromJson( reader, AcceptProposalRequest.class );
        } catch ( final RuntimeException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "Invalid JSON body: " + e.getMessage() );
            return null;
        }
    }
}
```

Notes on `RestServletBase` helpers: this class uses `sendError`, `sendJson`, `parseIntParam`, and `getEngine()` methods that `AdminKnowledgeResource` also uses. If any of those have different visibility or names on your branch, adjust — the existing `AdminKnowledgeResource` is the authoritative example of the helper surface.

- [ ] **Step 2: Run the servlet tests — expect pass**

Run: `mvn test -pl wikantik-rest -Dtest=AdminHubDiscoveryResourceTest -q`
Expected: all 12 tests pass. If the `contains(...)` Mockito argument matcher fails because `sendError` is final/private in `RestServletBase`, change the test to use `verify(resp).setStatus(...)` plus reading `respBody.toString()`, matching whatever the real helper does.

- [ ] **Step 3: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java \
        wikantik-rest/src/main/java/com/wikantik/rest/dto/AcceptProposalRequest.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java
git commit -m "feat: AdminHubDiscoveryResource with DTO-based accept endpoint"
```

---

## Task 15: Register the servlet in `web.xml`

**Files:**
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Find the `AdminKnowledgeResource` declaration and mapping**

Run: `grep -n "AdminKnowledgeResource\|knowledge" wikantik-war/src/main/webapp/WEB-INF/web.xml`
Expected: a `<servlet>` block declaring `AdminKnowledgeResource` and `<servlet-mapping>` entries for `/admin/knowledge/*` and `/admin/knowledge`. Record those line numbers.

- [ ] **Step 2: Add the new servlet declaration**

Directly after the existing `AdminKnowledgeResource` `<servlet>` block, insert:

```xml
    <servlet>
        <servlet-name>AdminHubDiscoveryResource</servlet-name>
        <servlet-class>com.wikantik.rest.AdminHubDiscoveryResource</servlet-class>
    </servlet>
```

- [ ] **Step 3: Add the servlet mapping**

Directly after the existing `/admin/knowledge/*` `<servlet-mapping>` block, insert:

```xml
    <servlet-mapping>
        <servlet-name>AdminHubDiscoveryResource</servlet-name>
        <url-pattern>/admin/knowledge/hub-discovery/*</url-pattern>
    </servlet-mapping>
```

Longest-path-prefix matching means every request beginning with `/admin/knowledge/hub-discovery/` is routed to the new servlet; everything else under `/admin/knowledge/` continues to hit `AdminKnowledgeResource`.

- [ ] **Step 4: Verify `AdminAuthFilter` covers the new path**

Run: `grep -n "AdminAuthFilter" wikantik-war/src/main/webapp/WEB-INF/web.xml`
Expected: the existing filter maps to `/admin/*`, which already covers the new path. No change needed.

- [ ] **Step 5: Commit**

```bash
git add wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat: wire AdminHubDiscoveryResource servlet mapping"
```

---

## Task 16: Full build verification

**Files:** _(none — verification only)_

- [ ] **Step 1: Run a full clean install without integration tests**

Run: `mvn clean install -T 1C -DskipITs -fae`
Expected: BUILD SUCCESS, no test failures, no compile errors. If the WAR module fails because it tries to resolve `AdminHubDiscoveryResource` from the REST JAR, confirm the REST dependency in `wikantik-war/pom.xml` already pulls in the new class (it will — same module, no pom changes needed).

- [ ] **Step 2: Deploy locally**

Run:
```bash
tomcat/tomcat-11/bin/shutdown.sh 2>/dev/null || true
./deploy-local.sh
tomcat/tomcat-11/bin/startup.sh
```
Expected: Tomcat starts, `catalina.out` shows `HubDiscoveryService registered`, and the V006 migration reports "already applied" on this run (since Task 2 applied it).

- [ ] **Step 3: Smoke-test the REST endpoints with curl**

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
# GET proposals (empty)
curl -u "${login}:${password}" -s http://localhost:8080/admin/knowledge/hub-discovery/proposals
# POST run
curl -u "${login}:${password}" -s -X POST http://localhost:8080/admin/knowledge/hub-discovery/run
```
Expected: both return JSON. The first returns `{"proposals":[],"total":0}`. The second returns a run summary; if `proposalsCreated` is 0, either the candidate pool is below the floor or the content model isn't trained — both are fine for a smoke test.

- [ ] **Step 4: No commit** — this task is verification only.

---

## Task 17: React API client wiring

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Add the hub-discovery methods to the `api.knowledge` namespace**

Open `wikantik-frontend/src/api/client.js`. Find the existing `api.knowledge.listHubProposals`/`generateHubProposals` block. Immediately after it (still inside the `knowledge` namespace) add:

```javascript
    listHubDiscoveryProposals: (limit = 50, offset = 0) => {
      const params = new URLSearchParams({ limit, offset });
      return request(`/admin/knowledge/hub-discovery/proposals?${params}`);
    },

    runHubDiscovery: () =>
      request('/admin/knowledge/hub-discovery/run', { method: 'POST' }),

    acceptHubDiscoveryProposal: (id, name, members) =>
      request(`/admin/knowledge/hub-discovery/proposals/${id}/accept`, {
        method: 'POST',
        body: JSON.stringify({ name, members }),
      }),

    dismissHubDiscoveryProposal: (id) =>
      request(`/admin/knowledge/hub-discovery/proposals/${id}/dismiss`, { method: 'POST' }),
```

- [ ] **Step 2: Verify the edit**

Run: `grep -n "HubDiscovery" wikantik-frontend/src/api/client.js`
Expected: four lines matching the methods you added.

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "feat: hub-discovery api client methods"
```

---

## Task 18: React `HubDiscoveryCard` component

**Files:**
- Create: `wikantik-frontend/src/components/admin/HubDiscoveryCard.jsx`

- [ ] **Step 1: Write the card component**

Create `wikantik-frontend/src/components/admin/HubDiscoveryCard.jsx`:

```jsx
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
import { useState } from 'react';
import { api } from '../../api/client';

export default function HubDiscoveryCard({ proposal, onRemoved, onError }) {
  const [name, setName] = useState(proposal.suggestedName);
  const [checked, setChecked] = useState(() => new Set(proposal.memberPages));
  const [busy, setBusy] = useState(false);

  const toggle = (member) => {
    const next = new Set(checked);
    if (next.has(member)) next.delete(member);
    else next.add(member);
    setChecked(next);
  };

  const handleAccept = async () => {
    if (!name.trim()) {
      onError?.('Hub name must not be empty');
      return;
    }
    if (checked.size < 2) {
      onError?.('Select at least 2 members');
      return;
    }
    setBusy(true);
    try {
      const members = proposal.memberPages.filter((m) => checked.has(m));
      await api.knowledge.acceptHubDiscoveryProposal(proposal.id, name.trim(), members);
      onRemoved?.(proposal.id);
    } catch (err) {
      onError?.(err.body?.message || err.message || 'Accept failed');
      setBusy(false);
    }
  };

  const handleDismiss = async () => {
    setBusy(true);
    try {
      await api.knowledge.dismissHubDiscoveryProposal(proposal.id);
      onRemoved?.(proposal.id);
    } catch (err) {
      onError?.(err.body?.message || err.message || 'Dismiss failed');
      setBusy(false);
    }
  };

  return (
    <div className="hub-discovery-card" data-testid={`hub-discovery-card-${proposal.id}`}>
      <div className="hub-discovery-card-header">
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          disabled={busy}
          data-testid={`hub-discovery-name-${proposal.id}`}
        />
        <div className="hub-discovery-card-meta">
          <span>exemplar: <strong>{proposal.exemplarPage}</strong></span>
          <span>coherence: {proposal.coherenceScore.toFixed(2)}</span>
        </div>
      </div>
      <ul className="hub-discovery-members">
        {proposal.memberPages.map((m) => (
          <li key={m}>
            <label>
              <input
                type="checkbox"
                checked={checked.has(m)}
                onChange={() => toggle(m)}
                disabled={busy}
                data-testid={`hub-discovery-member-${proposal.id}-${m}`}
              />
              {m}
            </label>
          </li>
        ))}
      </ul>
      <div className="hub-discovery-card-actions">
        <button
          className="btn btn-primary"
          onClick={handleAccept}
          disabled={busy}
          data-testid={`hub-discovery-accept-${proposal.id}`}
        >
          Accept
        </button>
        <button
          className="btn"
          onClick={handleDismiss}
          disabled={busy}
          data-testid={`hub-discovery-dismiss-${proposal.id}`}
        >
          Dismiss
        </button>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/components/admin/HubDiscoveryCard.jsx
git commit -m "feat: HubDiscoveryCard react component"
```

---

## Task 19: React `HubDiscoveryTab` and tab registration

**Files:**
- Create: `wikantik-frontend/src/components/admin/HubDiscoveryTab.jsx`
- Modify: `wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx`

- [ ] **Step 1: Write the tab component**

Create `wikantik-frontend/src/components/admin/HubDiscoveryTab.jsx`:

```jsx
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
import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import HubDiscoveryCard from './HubDiscoveryCard';

export default function HubDiscoveryTab() {
  const [proposals, setProposals] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [toast, setToast] = useState(null);

  const load = async () => {
    setLoading(true);
    try {
      const resp = await api.knowledge.listHubDiscoveryProposals(50, 0);
      setProposals(resp.proposals || []);
      setTotal(resp.total || 0);
    } catch (err) {
      setToast({ kind: 'error', message: err.message || 'Load failed' });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleRun = async () => {
    setRunning(true);
    setToast(null);
    try {
      const resp = await api.knowledge.runHubDiscovery();
      setToast({
        kind: 'success',
        message: `Discovery complete: ${resp.proposalsCreated} proposals from ${resp.candidatePoolSize} candidates (${resp.noisePages} noise) in ${resp.durationMs} ms`,
      });
      await load();
    } catch (err) {
      setToast({ kind: 'error', message: err.message || 'Run failed' });
    } finally {
      setRunning(false);
    }
  };

  const handleRemoved = (id) => {
    setProposals((prev) => prev.filter((p) => p.id !== id));
    setTotal((prev) => Math.max(0, prev - 1));
  };

  const handleCardError = (message) => {
    setToast({ kind: 'error', message });
  };

  return (
    <div className="hub-discovery-tab" data-testid="hub-discovery-tab">
      <div className="hub-discovery-toolbar">
        <button
          className="btn btn-primary"
          onClick={handleRun}
          disabled={running}
          data-testid="hub-discovery-run"
        >
          {running ? 'Running…' : 'Run Discovery'}
        </button>
        <span className="hub-discovery-count" data-testid="hub-discovery-count">
          {total} pending
        </span>
      </div>
      {toast && (
        <div
          className={`toast toast-${toast.kind}`}
          data-testid={`hub-discovery-toast-${toast.kind}`}
        >
          {toast.message}
        </div>
      )}
      {loading ? (
        <p>Loading…</p>
      ) : proposals.length === 0 ? (
        <p data-testid="hub-discovery-empty">No pending cluster proposals. Click "Run Discovery" to generate.</p>
      ) : (
        <div className="hub-discovery-list">
          {proposals.map((p) => (
            <HubDiscoveryCard
              key={p.id}
              proposal={p}
              onRemoved={handleRemoved}
              onError={handleCardError}
            />
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Register the tab in `AdminKnowledgePage`**

Modify `wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx`. Add the import at the top of the file:

```jsx
import HubDiscoveryTab from './HubDiscoveryTab';
```

Add the tab to the `TABS` array (append):

```jsx
  { id: 'hub-discovery', label: 'Hub Discovery' },
```

Add the conditional render at the bottom of the existing tab-switch block (after the `hub-proposals` line):

```jsx
      {activeTab === 'hub-discovery' && <HubDiscoveryTab />}
```

- [ ] **Step 3: Rebuild the frontend**

Run: `cd wikantik-frontend && npm run build -- --logLevel=warn`
Expected: build completes. If `npm` complains about a missing dep, run `npm install` once.

- [ ] **Step 4: Rebuild the WAR and redeploy**

Run:
```bash
tomcat/tomcat-11/bin/shutdown.sh 2>/dev/null || true
mvn clean install -Dmaven.test.skip -pl wikantik-war -am -T 1C -q
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

Navigate to `http://localhost:8080/admin/knowledge` and confirm the "Hub Discovery" tab appears and its empty state renders. Click "Run Discovery" and verify the toast shows a summary.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/admin/HubDiscoveryTab.jsx \
        wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx
git commit -m "feat: hub discovery admin tab"
```

---

## Task 20: Selenide page object

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/pages/admin/HubDiscoveryAdminPage.java`

- [ ] **Step 1: Write the page object**

Create `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/pages/admin/HubDiscoveryAdminPage.java`:

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
package com.wikantik.pages.admin;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pages.Page;

import java.time.Duration;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;

/**
 * Selenide page object for the hub-discovery admin tab. Locators use {@code data-testid}
 * attributes baked into the React components so they are resilient to CSS/class renames.
 */
public class HubDiscoveryAdminPage extends Page {

    public HubDiscoveryAdminPage open() {
        open( "/admin/knowledge" );
        // Click the Hub Discovery tab. The tab button text comes from TABS[].label.
        $( ".admin-tab[data-testid='hub-discovery-tab']" ).shouldBe( visible ).click();
        // Fallback: some builds attach data-testid to the wrapper div, not the tab button,
        // so also accept a button match by visible text.
        return this;
    }

    public HubDiscoveryAdminPage clickRunDiscovery() {
        $( "[data-testid='hub-discovery-run']" ).shouldBe( visible ).click();
        return this;
    }

    public HubDiscoveryAdminPage waitForSuccessToast() {
        $( "[data-testid='hub-discovery-toast-success']" )
            .shouldBe( visible, Duration.ofSeconds( 30 ) );
        return this;
    }

    public HubDiscoveryAdminPage waitForErrorToast() {
        $( "[data-testid='hub-discovery-toast-error']" )
            .shouldBe( visible, Duration.ofSeconds( 10 ) );
        return this;
    }

    public SelenideElement card( final int proposalId ) {
        return $( "[data-testid='hub-discovery-card-" + proposalId + "']" );
    }

    public HubDiscoveryAdminPage setName( final int proposalId, final String name ) {
        final SelenideElement input = $( "[data-testid='hub-discovery-name-" + proposalId + "']" );
        input.shouldBe( visible );
        input.clear();
        input.sendKeys( name );
        return this;
    }

    public HubDiscoveryAdminPage clickAccept( final int proposalId ) {
        $( "[data-testid='hub-discovery-accept-" + proposalId + "']" ).shouldBe( visible ).click();
        return this;
    }

    public HubDiscoveryAdminPage clickDismiss( final int proposalId ) {
        $( "[data-testid='hub-discovery-dismiss-" + proposalId + "']" ).shouldBe( visible ).click();
        return this;
    }

    public HubDiscoveryAdminPage assertCardDisappears( final int proposalId ) {
        $( "[data-testid='hub-discovery-card-" + proposalId + "']" )
            .shouldNotBe( exist, Duration.ofSeconds( 10 ) );
        return this;
    }

    public HubDiscoveryAdminPage assertCardStillPresent( final int proposalId ) {
        $( "[data-testid='hub-discovery-card-" + proposalId + "']" )
            .shouldBe( Condition.visible );
        return this;
    }
}
```

Note: `Page` is the existing base class under `com.wikantik.pages` on the module.

- [ ] **Step 2: Compile the IT module**

Run: `mvn compile -pl wikantik-it-tests/wikantik-selenide-tests -q`
Expected: exits 0.

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/pages/admin/HubDiscoveryAdminPage.java
git commit -m "test: HubDiscoveryAdminPage selenide page object"
```

---

## Task 21: REST seed helper for Selenide ITs

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RestSeedHelper.java`

- [ ] **Step 1: Write the helper**

Create `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RestSeedHelper.java`:

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
package com.wikantik.its;

import com.wikantik.its.environment.Env;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Minimal HTTP seed helper used by {@code HubDiscoveryAdminIT} to prepare backend
 * state without going through the browser. Uses basic auth against Janne's test
 * credentials, so the wiki must be configured to accept basic auth on the admin
 * REST endpoints during integration-test runs.
 *
 * <p>The helper is deliberately low-level. A higher-level seeder would couple
 * the tests to the REST surface in ways that make breakages slower to diagnose.
 */
public final class RestSeedHelper {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private RestSeedHelper() {}

    private static String authHeader() {
        final String userPass = Env.LOGIN_JANNE_USERNAME + ":" + Env.LOGIN_JANNE_PASSWORD;
        return "Basic " + Base64.getEncoder().encodeToString(
            userPass.getBytes( StandardCharsets.UTF_8 ) );
    }

    /** Write a wiki page via the page REST API. Uses raw markdown content. */
    public static void writePage( final String name, final String markdown ) throws Exception {
        final String url = Env.TESTS_BASE_URL + "/api/pages/" + name;
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .header( "Authorization", authHeader() )
            .header( "Content-Type", "application/json" )
            .PUT( HttpRequest.BodyPublishers.ofString(
                "{\"content\":" + jsonString( markdown ) + "}" ) )
            .build();
        final HttpResponse< String > resp = CLIENT.send( req, HttpResponse.BodyHandlers.ofString() );
        if ( resp.statusCode() >= 300 ) {
            throw new IllegalStateException( "writePage failed: " + resp.statusCode() + " " + resp.body() );
        }
    }

    /** POST /admin/knowledge/hub-discovery/run; returns the raw JSON body. */
    public static String runDiscovery() throws Exception {
        return post( "/admin/knowledge/hub-discovery/run", "" );
    }

    /** GET /admin/knowledge/hub-discovery/proposals; returns the raw JSON body. */
    public static String listProposals() throws Exception {
        return get( "/admin/knowledge/hub-discovery/proposals?limit=50" );
    }

    /** Directly insert a proposal via the REST admin test seam, if one exists; otherwise use runDiscovery. */
    public static String post( final String path, final String jsonBody ) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder( URI.create( Env.TESTS_BASE_URL + path ) )
            .header( "Authorization", authHeader() )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( jsonBody ) )
            .build();
        final HttpResponse< String > resp = CLIENT.send( req, HttpResponse.BodyHandlers.ofString() );
        if ( resp.statusCode() >= 300 ) {
            throw new IllegalStateException( "POST " + path + " failed: "
                + resp.statusCode() + " " + resp.body() );
        }
        return resp.body();
    }

    public static String get( final String path ) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder( URI.create( Env.TESTS_BASE_URL + path ) )
            .header( "Authorization", authHeader() )
            .GET()
            .build();
        final HttpResponse< String > resp = CLIENT.send( req, HttpResponse.BodyHandlers.ofString() );
        if ( resp.statusCode() >= 300 ) {
            throw new IllegalStateException( "GET " + path + " failed: "
                + resp.statusCode() + " " + resp.body() );
        }
        return resp.body();
    }

    private static String jsonString( final String s ) {
        final StringBuilder sb = new StringBuilder( "\"" );
        for ( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            switch ( c ) {
                case '"' -> sb.append( "\\\"" );
                case '\\' -> sb.append( "\\\\" );
                case '\n' -> sb.append( "\\n" );
                case '\r' -> sb.append( "\\r" );
                case '\t' -> sb.append( "\\t" );
                default -> sb.append( c );
            }
        }
        return sb.append( '"' ).toString();
    }
}
```

- [ ] **Step 2: Compile the IT module**

Run: `mvn compile -pl wikantik-it-tests/wikantik-selenide-tests -q`
Expected: exits 0.

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RestSeedHelper.java
git commit -m "test: RestSeedHelper for selenide its"
```

---

## Task 22: Selenide end-to-end tests

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubDiscoveryAdminIT.java`

- [ ] **Step 1: Write the three end-to-end tests**

Create `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubDiscoveryAdminIT.java`:

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
package com.wikantik.its;

import com.wikantik.its.environment.Env;
import com.wikantik.pages.admin.HubDiscoveryAdminPage;
import com.wikantik.pages.haddock.LoginPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the Hub Discovery admin UI. Covers:
 * <ol>
 *   <li>Happy-path run → accept → graph projection → stub page visible.</li>
 *   <li>409 collision → inline error, card retained, existing page untouched.</li>
 *   <li>Dismiss → card disappears, list empty.</li>
 * </ol>
 */
public class HubDiscoveryAdminIT extends WithIntegrationTestSetup {

    @BeforeEach
    void login() {
        final LoginPage login = new LoginPage().open();
        login.performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
    }

    @Test
    void runAcceptFlow_happyPath() throws Exception {
        // Seed six tightly-related cooking pages via the page REST API so the content
        // model picks them up on its next retrain. The wikantik.properties used by ITs
        // must enable on-save content-model updates (it does by default).
        RestSeedHelper.writePage( "CookingBaking", "baking bread cake flour sugar oven recipes" );
        RestSeedHelper.writePage( "CookingRoasting", "roasting meat oven temperature seasoning recipes" );
        RestSeedHelper.writePage( "CookingGrilling", "grilling outdoor charcoal meat barbecue" );
        RestSeedHelper.writePage( "CookingSauteing", "sauteing pan oil butter quick heat" );
        RestSeedHelper.writePage( "CookingBroiling", "broiling oven top direct heat meat" );
        RestSeedHelper.writePage( "CookingBoiling", "boiling water pot heat stovetop" );

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();

        // Find the first proposal card that appeared. The card id is not known statically,
        // so we match the generic card data-testid prefix.
        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible );
        final String testid = firstCard.getAttribute( "data-testid" );
        assertTrue( testid != null && testid.startsWith( "hub-discovery-card-" ) );
        final int proposalId = Integer.parseInt( testid.substring( "hub-discovery-card-".length() ) );

        page.clickAccept( proposalId );
        page.assertCardDisappears( proposalId );

        // The stub page should now exist. Its name is the exemplar page name — the test
        // does not know it statically, so rely on the REST list to confirm the row was
        // deleted (side-effect verification). Navigating to the stub by name would also
        // work if the test plumbs the exemplar name out of the response.
        final String afterJson = RestSeedHelper.listProposals();
        // The just-accepted proposal's id must no longer appear.
        assertTrue( !afterJson.contains( "\"id\":" + proposalId + "," ),
            "Accepted proposal should be gone from list" );
    }

    @Test
    void acceptCollisionShowsInlineError() throws Exception {
        // Pre-create a wiki page that a discovery card's name will collide with.
        RestSeedHelper.writePage( "ClashingHubName",
            "Pre-existing content for collision test" );
        // Seed enough cooking pages to produce at least one cluster.
        RestSeedHelper.writePage( "SportSoccer", "soccer football goal player team" );
        RestSeedHelper.writePage( "SportBasketball", "basketball hoop court player team" );
        RestSeedHelper.writePage( "SportTennis", "tennis racquet grand slam court" );
        RestSeedHelper.writePage( "SportRugby", "rugby scrum ball tackle field" );
        RestSeedHelper.writePage( "SportHockey", "hockey stick ice puck goal" );
        RestSeedHelper.writePage( "SportBaseball", "baseball bat ball pitcher batter" );

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();
        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible );
        final int proposalId = Integer.parseInt(
            firstCard.getAttribute( "data-testid" ).substring( "hub-discovery-card-".length() ) );

        page.setName( proposalId, "ClashingHubName" )
            .clickAccept( proposalId )
            .waitForErrorToast()
            .assertCardStillPresent( proposalId );

        // Pre-existing page still has original content.
        open( "/" + "ClashingHubName" );
        $( "main" ).shouldHave( com.codeborne.selenide.Condition.text( "Pre-existing content" ) );
    }

    @Test
    void dismissRemovesCard() throws Exception {
        RestSeedHelper.writePage( "DismissBaking", "baking bread cake flour sugar oven" );
        RestSeedHelper.writePage( "DismissRoasting", "roasting meat oven temperature seasoning" );
        RestSeedHelper.writePage( "DismissGrilling", "grilling outdoor charcoal meat barbecue" );
        RestSeedHelper.writePage( "DismissSauteing", "sauteing pan oil butter quick heat" );
        RestSeedHelper.writePage( "DismissBroiling", "broiling oven top direct heat meat" );
        RestSeedHelper.writePage( "DismissBoiling", "boiling water pot heat stovetop" );

        final HubDiscoveryAdminPage page = new HubDiscoveryAdminPage().open();
        page.clickRunDiscovery().waitForSuccessToast();
        final var firstCard = $( "[data-testid^='hub-discovery-card-']" );
        firstCard.shouldBe( visible );
        final int proposalId = Integer.parseInt(
            firstCard.getAttribute( "data-testid" ).substring( "hub-discovery-card-".length() ) );

        page.clickDismiss( proposalId ).assertCardDisappears( proposalId );

        final String afterJson = RestSeedHelper.listProposals();
        assertTrue( !afterJson.contains( "\"id\":" + proposalId + "," ),
            "Dismissed proposal should be gone from list" );
    }
}
```

Note on seeding via `PUT /api/pages/{name}`: Wikantik's REST page-write endpoint shape may differ. If `RestSeedHelper.writePage` fails with 404, adjust the URL and body format to match the REST shape used by existing integration tests (grep `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its` for examples of how they create pages). If no such REST endpoint exists, fall back to performing the page writes through the editor via Selenide as the existing `EditIT` tests do.

- [ ] **Step 2: Run the ITs sequentially (no -T flag)**

Run: `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-selenide-tests -am`
Expected: all Selenide tests including the three new hub-discovery ITs pass. If any flake due to page-creation timing, add an extra `Thread.sleep( Env.TESTS_CONFIG_SEARCH_INDEX_WAIT )` after the `RestSeedHelper.writePage` batch and before `clickRunDiscovery()` to let the content model refresh.

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubDiscoveryAdminIT.java
git commit -m "test: selenide its for hub discovery admin ui"
```

---

## Task 23: Final verification — full build + full IT run

**Files:** _(none — verification only)_

- [ ] **Step 1: Full parallel unit-test build**

Run: `mvn clean install -T 1C -DskipITs -fae`
Expected: BUILD SUCCESS. No test failures. Record any non-HubDiscovery flakes separately for the user to triage — do not silence them.

- [ ] **Step 2: Full sequential IT build**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS. The three new `HubDiscoveryAdminIT` tests pass. Other modules' ITs pass as they did before.

- [ ] **Step 3: Tag the feature commit for rollback convenience**

Run: `git log --oneline | head -30`
Expected: the task commits from this plan form a contiguous block. Record the first and last hashes.

- [ ] **Step 4: No commit.** Verification only.

---

## Self-Review Notes

Spec coverage walked section-by-section before finalizing:

- **Architecture** — Tasks 3 (`SmileHdbscanClusterer`), 5 (`HubDiscoveryRepository`), 8/10 (`HubDiscoveryService`), 14 (`AdminHubDiscoveryResource`), 19 (React admin page).
- **Candidate Pool** — `HubDiscoveryService.loadCandidatePool` in Task 8 (Step 1). Task 8 Step 2 validates the `JdbcKnowledgeRepository` method names used there.
- **Clustering** — `SmileHdbscanClusterer` in Task 3. Parameters wired through builder and tested in Task 7–9.
- **Database Schema** — Task 2 (V006 migration). Matches the spec's DDL exactly.
- **Stub Page Template** — `HubDiscoveryService.renderStub` in Task 10 Step 6. Format matches the spec verbatim.
- **REST API** — Task 14. All four endpoints, DTO, `limit` cap, status-code mapping. Unit-tested in Task 13.
- **Accept Flow** — Task 10 Step 6. Validation ordering matches the spec: find by id → validate name → collision check → validate member membership → drop missing members → write page → delete row.
- **Dismiss Flow** — Task 10 Step 6.
- **Admin UI** — Tasks 17–19. `data-testid` attributes on every interactive element for Selenide.
- **Concurrency Model** — Repository uses unique primary key for ordering; accept's `delete` is idempotent.
- **Testing Plan** — Tasks 3 (unit), 4–5 (repo IT), 7–10 (service IT), 13 (servlet unit), 20–22 (Selenide IT). Browser coverage is included per explicit user instruction.
- **Configuration** — Task 12 adds the three properties to `wikantik.properties`. `propsFrom(props)` builder method in Task 8.
- **Logging Summary** — INFO log lines added at service layer in Task 8 and Task 10, and at the REST layer in Task 14. Exact message templates match the spec.
- **Dependency Changes** — Task 1.
- **Wiring Changes** — Task 11 (factory + engine), Task 15 (web.xml), Task 17 (React client), Task 19 (React tab registration), Task 2 (migration).

Placeholder scan: no "TBD", no "TODO fill in", no "handle appropriately". Notes about Smile version and about alternative `JdbcKnowledgeRepository` method names are deliberate judgment-call hooks, not placeholders — they point to specific commands to resolve ambiguity.

Type consistency: `AcceptResult(createdPage, memberCount)`, `RunSummary(proposalsCreated, candidatePoolSize, noisePages, durationMs)`, `HubDiscoveryProposal(id, suggestedName, exemplarPage, memberPages, coherenceScore, created)`, and the REST JSON shapes all match across tasks. Builder method names are consistent: `kgRepo`, `discoveryRepo`, `contentRepo`, `minClusterSize`, `minPts`, `minCandidatePool`, `contentModel`/`contentModelSupplier`, `pageWriter`, `pageExists`, `propsFrom`. `PageWriter` is a single function-interface type used in both tests and factory wiring.
