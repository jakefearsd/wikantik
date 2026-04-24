# Structural Spine — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an observe-only structural index for the wiki — every page gets a stable `canonical_id`, a JDBC-backed `StructuralIndexService` builds a queryable projection of clusters / tags / types / canonical-IDs, and five new MCP tools plus a `/api/structure/*` REST surface let agents navigate the wiki by shape rather than by full-text search.

**Architecture:** A new `StructuralIndexService` interface in `wikantik-api`, implemented in `wikantik-knowledge` as `DefaultStructuralIndexService`. The service owns an in-memory projection rebuilt at startup and maintained via `WikiPageEvent.POST_SAVE` / `PAGE_DELETED` listeners, with a Postgres table (`page_canonical_ids`) as the durable backstop. A CLI command in `wikantik-extract-cli` backfills `canonical_id` frontmatter across the existing page corpus. REST endpoints live in a new `StructureResource` servlet in `wikantik-rest`; MCP tools live alongside existing retrieval tools in `wikantik-knowledge/.../mcp/`. Phase 1 is observe-only — pages without `canonical_id` still save successfully; enforcement is deferred to Phase 4.

**Tech Stack:** Java 21, Jakarta EE 10, Jakarta Servlet 6, JUnit 5, Mockito 5, Testcontainers (already wired for postgres-pgvector in `wikantik-it-tests`), Flexmark, SnakeYAML (already transitive via `wikantik-api`), Gson, Log4j2, Prometheus via Micrometer, ULID via `com.github.f4b6a3:ulid-creator:5.2.3` (new dependency).

**Scope — what ships at end of Phase 1:**

- ✅ Every page in `docs/wikantik-pages/` has `canonical_id` frontmatter
- ✅ Migration `V013` adds `page_canonical_ids`, `page_slug_history`, `page_relations` tables
- ✅ `StructuralIndexService` live as an Engine manager, maintained by events
- ✅ REST: `GET /api/structure/{clusters,clusters/{name},tags,pages,sitemap}` and `GET /api/pages/by-id/{canonical_id}`
- ✅ MCP tools on `/knowledge-mcp`: `list_clusters`, `list_tags`, `list_pages_by_filter`, `get_page_by_id`
- ✅ Prometheus metrics for the index
- ✅ Health endpoint at `/api/health/structural-index`

**Out of scope — deferred to later phases** (separate plans when Phase 1 ships):

- Phase 2 — Typed `relations:` vocabulary, `traverse_relations` tool, relation validator, `ProposeRelationsTool`
- Phase 3 — Generated `Main.md`, pins sidecar, pre-commit guard
- Phase 4 — Enforcement: reject saves without `canonical_id`; reject relations with missing targets

---

## File Structure

### New files

```
bin/db/migrations/V013__canonical_ids_and_relations.sql                         — DDL

wikantik-bom/pom.xml                                                             — add ulid-creator version pin (modify)

wikantik-api/src/main/java/com/wikantik/api/structure/
    PageDescriptor.java                                                          — record
    PageType.java                                                                — enum: HUB, ARTICLE, REFERENCE, RUNBOOK, UNKNOWN
    ClusterSummary.java                                                          — record
    ClusterDetails.java                                                          — record
    TagSummary.java                                                              — record
    StructuralFilter.java                                                        — record
    Sitemap.java                                                                 — record
    IndexHealth.java                                                             — record, includes status enum
    StructuralIndexService.java                                                  — interface

wikantik-knowledge/pom.xml                                                       — add ulid-creator dep (modify)
wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/
    DefaultStructuralIndexService.java                                           — implementation
    PageCanonicalIdsDao.java                                                     — JDBC DAO
    StructuralProjection.java                                                    — in-memory immutable snapshot
    StructuralProjectionBuilder.java                                             — mutable builder used during rebuild
    StructuralIndexEventListener.java                                            — WikiEventListener wiring
    StructuralIndexMetrics.java                                                  — Micrometer gauges + histograms

wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/
    ListClustersTool.java                                                        — MCP tool
    ListTagsTool.java                                                            — MCP tool
    ListPagesByFilterTool.java                                                   — MCP tool
    GetPageByIdTool.java                                                         — MCP tool

wikantik-knowledge/src/test/java/com/wikantik/knowledge/structure/
    DefaultStructuralIndexServiceTest.java                                       — unit tests with mocked PageManager
    PageCanonicalIdsDaoTest.java                                                 — JDBC test using H2
    StructuralIndexEventListenerTest.java                                        — unit tests
    StructuralProjectionTest.java                                                — unit tests

wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/
    ListClustersToolTest.java
    ListTagsToolTest.java
    ListPagesByFilterToolTest.java
    GetPageByIdToolTest.java

wikantik-extract-cli/pom.xml                                                     — add ulid-creator dep (modify)
wikantik-extract-cli/src/main/java/com/wikantik/extractcli/
    AssignCanonicalIdsCli.java                                                   — main class
    FrontmatterRewriter.java                                                     — in-place YAML rewrite utility
wikantik-extract-cli/src/test/java/com/wikantik/extractcli/
    AssignCanonicalIdsCliTest.java
    FrontmatterRewriterTest.java

wikantik-rest/src/main/java/com/wikantik/rest/
    StructureResource.java                                                       — /api/structure/* servlet
    PageByIdResource.java                                                        — /api/pages/by-id/{canonical_id}
    StructuralIndexHealthResource.java                                           — /api/health/structural-index

wikantik-rest/src/test/java/com/wikantik/rest/
    StructureResourceTest.java
    PageByIdResourceTest.java
    StructuralIndexHealthResourceTest.java

wikantik-it-tests/src/test/java/com/wikantik/it/structure/
    StructuralSpineIT.java                                                       — end-to-end Cargo+Postgres
```

### Modified files

```
wikantik-main/src/main/java/com/wikantik/WikiEngine.java                         — register StructuralIndexService in initKnowledgeGraph()
wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java    — register new MCP tools + event listener
wikantik-war/src/main/webapp/WEB-INF/web.xml                                     — register new REST servlets
docs/wikantik-pages/*.md                                                         — backfill canonical_id frontmatter (~1000 files, single commit)
```

---

## Conventions for every task

- **TDD cycle per task:** failing test first → verify it fails → implement minimum to pass → verify it passes → commit. Never squash these into one step.
- **Build command for tight feedback:** `mvn test -pl <module> -Dtest=<ClassName> -am -Dsurefire.failIfNoSpecifiedTests=false -q`
- **Compile-only check:** `mvn compile -pl <module> -am -q`
- **Final verification command** (after all tasks): `mvn clean install -T 1C -DskipITs` then `mvn clean install -Pintegration-tests -fae`
- **Commits are specific-file `git add`** — never `git add -A`
- **One commit per task** unless the task explicitly notes otherwise (the backfill task in particular rolls its test + impl + data migration into one task but still uses multiple commits).
- **Working directly on `main`** per `CLAUDE.md` — no branches, no PRs.

---

## Task 1: Add ULID dependency

**Files:**
- Modify: `wikantik-bom/pom.xml`
- Modify: `wikantik-knowledge/pom.xml`
- Modify: `wikantik-extract-cli/pom.xml`

- [ ] **Step 1: Read current BOM dependency-management block**

Run: `grep -n '</dependencyManagement>' wikantik-bom/pom.xml`

Note the line number where `</dependencyManagement>` closes. Plan to insert above it.

- [ ] **Step 2: Add ULID version pin to the BOM**

In `wikantik-bom/pom.xml`, inside `<dependencyManagement><dependencies>`, add:

```xml
<dependency>
    <groupId>com.github.f4b6a3</groupId>
    <artifactId>ulid-creator</artifactId>
    <version>5.2.3</version>
</dependency>
```

- [ ] **Step 3: Declare the dep in wikantik-knowledge**

In `wikantik-knowledge/pom.xml`, inside `<dependencies>`, add:

```xml
<dependency>
    <groupId>com.github.f4b6a3</groupId>
    <artifactId>ulid-creator</artifactId>
</dependency>
```

- [ ] **Step 4: Declare the dep in wikantik-extract-cli**

In `wikantik-extract-cli/pom.xml`, inside `<dependencies>`, add the identical block (no `<version>` — comes from BOM).

- [ ] **Step 5: Verify resolution**

Run: `mvn dependency:resolve -pl wikantik-knowledge,wikantik-extract-cli -am -q`
Expected: no "could not resolve" errors.

- [ ] **Step 6: Commit**

```bash
git add wikantik-bom/pom.xml wikantik-knowledge/pom.xml wikantik-extract-cli/pom.xml
git commit -m "build: add ulid-creator 5.2.3 for canonical page IDs"
```

---

## Task 2: V013 migration for canonical-id tables

**Files:**
- Create: `bin/db/migrations/V013__canonical_ids_and_relations.sql`

- [ ] **Step 1: Write the migration**

Create `bin/db/migrations/V013__canonical_ids_and_relations.sql` with the full Apache license header (copy from `V012__retire_graph_projector_links_to_edges.sql` lines 1-16), followed by:

```sql
-- V013: Canonical page IDs, slug history, and typed relations.
--
-- Phase 1 of the Structural Spine (see
-- docs/wikantik-pages/StructuralSpineDesign.md). Adds:
--   * page_canonical_ids   — stable identity surviving renames
--   * page_slug_history    — audit trail of slug changes per canonical_id
--   * page_relations       — typed authored relationships between pages
--
-- Phase 1 only populates page_canonical_ids and page_slug_history.
-- page_relations is created here so Phase 2 can land without another
-- DDL migration. Idempotent.

CREATE TABLE IF NOT EXISTS page_canonical_ids (
    canonical_id   CHAR(26)     PRIMARY KEY,
    current_slug   VARCHAR(512) NOT NULL UNIQUE,
    title          VARCHAR(512) NOT NULL,
    type           VARCHAR(32)  NOT NULL,
    cluster        VARCHAR(128),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS page_slug_history (
    canonical_id   CHAR(26)     NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    previous_slug  VARCHAR(512) NOT NULL,
    renamed_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (canonical_id, previous_slug)
);

CREATE TABLE IF NOT EXISTS page_relations (
    source_id      CHAR(26)     NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    target_id      CHAR(26)     NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    relation_type  VARCHAR(32)  NOT NULL
        CHECK (relation_type IN ('part-of','example-of','prerequisite-for','supersedes','contradicts','implements','derived-from')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (source_id, target_id, relation_type)
);

CREATE INDEX IF NOT EXISTS ix_page_relations_target      ON page_relations(target_id, relation_type);
CREATE INDEX IF NOT EXISTS ix_page_relations_source_type ON page_relations(source_id, relation_type);
CREATE INDEX IF NOT EXISTS ix_canonical_ids_type         ON page_canonical_ids(type);
CREATE INDEX IF NOT EXISTS ix_canonical_ids_cluster      ON page_canonical_ids(cluster);

GRANT SELECT, INSERT, UPDATE, DELETE ON page_canonical_ids, page_slug_history, page_relations TO :app_user;
```

- [ ] **Step 2: Apply locally to verify**

Run: `DB_NAME=wikantik DB_APP_USER=jspwiki bin/db/migrate.sh`
Expected: "V013__canonical_ids_and_relations.sql  APPLIED" in output.

- [ ] **Step 3: Re-run to verify idempotence**

Run: `DB_NAME=wikantik DB_APP_USER=jspwiki bin/db/migrate.sh`
Expected: no error; migration already applied is skipped.

- [ ] **Step 4: Spot-check the schema**

Run: `psql -h localhost -U jspwiki -d wikantik -c '\d page_canonical_ids'`
Expected: table with five columns (canonical_id, current_slug, title, type, cluster, created_at, updated_at — six because of `NOT NULL DEFAULT NOW()`).

- [ ] **Step 5: Commit**

```bash
git add bin/db/migrations/V013__canonical_ids_and_relations.sql
git commit -m "db: V013 migration adds page_canonical_ids, page_slug_history, page_relations"
```

---

## Task 3: Value types (records and enum)

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/structure/PageType.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/structure/PageDescriptor.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/structure/ClusterSummary.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/structure/ClusterDetails.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/structure/TagSummary.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/structure/StructuralFilter.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/structure/Sitemap.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/structure/IndexHealth.java`

These are pure data types — no tests needed; their correctness is exercised by `DefaultStructuralIndexServiceTest` in later tasks.

- [ ] **Step 1: Create PageType enum**

Put the Apache license header at the top of every file in this task (copy from `wikantik-api/src/main/java/com/wikantik/api/frontmatter/ParsedPage.java` lines 1-18). Then:

```java
package com.wikantik.api.structure;

/**
 * Canonical page type as declared in frontmatter {@code type:}. {@link #UNKNOWN}
 * captures pages that either omit the field or declare a value not yet in the
 * supported vocabulary.
 */
public enum PageType {
    HUB, ARTICLE, REFERENCE, RUNBOOK, DESIGN, UNKNOWN;

    public static PageType fromFrontmatter( final Object raw ) {
        if ( raw == null ) {
            return UNKNOWN;
        }
        final String value = raw.toString().trim().toLowerCase( java.util.Locale.ROOT );
        return switch ( value ) {
            case "hub"       -> HUB;
            case "article"   -> ARTICLE;
            case "reference" -> REFERENCE;
            case "runbook"   -> RUNBOOK;
            case "design"    -> DESIGN;
            default          -> UNKNOWN;
        };
    }

    public String asFrontmatterValue() {
        return name().toLowerCase( java.util.Locale.ROOT );
    }
}
```

- [ ] **Step 2: Create PageDescriptor record**

```java
package com.wikantik.api.structure;

import java.time.Instant;
import java.util.List;

/**
 * Lightweight descriptor of a wiki page for structural queries — enough to render
 * a compact listing without the full page body. All fields are non-null except
 * {@code cluster}, {@code summary}, and {@code updated}; prefer empty collections
 * over null for list fields.
 */
public record PageDescriptor(
        String canonicalId,
        String slug,
        String title,
        PageType type,
        String cluster,
        List< String > tags,
        String summary,
        Instant updated
) {
    public PageDescriptor {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId required" );
        }
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "slug required" );
        }
        if ( title == null ) {
            title = slug;
        }
        if ( type == null ) {
            type = PageType.UNKNOWN;
        }
        tags = tags == null ? List.of() : List.copyOf( tags );
    }
}
```

- [ ] **Step 3: Create ClusterSummary and ClusterDetails**

`ClusterSummary`:

```java
package com.wikantik.api.structure;

import java.time.Instant;

public record ClusterSummary(
        String name,
        PageDescriptor hubPage,          // nullable — some clusters have no declared hub
        int articleCount,
        Instant updatedAt
) {}
```

`ClusterDetails`:

```java
package com.wikantik.api.structure;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ClusterDetails(
        String name,
        PageDescriptor hubPage,
        List< PageDescriptor > articles,
        Map< String, Integer > tagDistribution,
        Instant updatedAt
) {
    public ClusterDetails {
        articles        = articles        == null ? List.of() : List.copyOf( articles );
        tagDistribution = tagDistribution == null ? Map.of()  : Map.copyOf( tagDistribution );
    }
}
```

- [ ] **Step 4: Create TagSummary**

```java
package com.wikantik.api.structure;

import java.util.List;

public record TagSummary(
        String tag,
        int count,
        List< String > topPageIds        // canonical_ids of the highest-value representatives
) {
    public TagSummary {
        topPageIds = topPageIds == null ? List.of() : List.copyOf( topPageIds );
    }
}
```

- [ ] **Step 5: Create StructuralFilter**

```java
package com.wikantik.api.structure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Filter criteria for {@link StructuralIndexService#listPagesByFilter}. All fields
 * optional; empty optionals mean "no constraint". A null value in the static
 * factories means "not specified".
 */
public record StructuralFilter(
        Optional< PageType > type,
        Optional< String > cluster,
        List< String > tags,              // ALL must match (AND), empty list = no tag filter
        Optional< Instant > updatedSince,
        int limit,
        Optional< String > cursor
) {
    public StructuralFilter {
        type         = type         == null ? Optional.empty() : type;
        cluster      = cluster      == null ? Optional.empty() : cluster;
        tags         = tags         == null ? List.of()        : List.copyOf( tags );
        updatedSince = updatedSince == null ? Optional.empty() : updatedSince;
        cursor       = cursor       == null ? Optional.empty() : cursor;
        if ( limit <= 0 ) {
            limit = 100;
        }
        if ( limit > 1000 ) {
            limit = 1000;
        }
    }

    public static StructuralFilter none() {
        return new StructuralFilter( null, null, null, null, 100, null );
    }
}
```

- [ ] **Step 6: Create Sitemap**

```java
package com.wikantik.api.structure;

import java.time.Instant;
import java.util.List;

public record Sitemap(
        List< PageDescriptor > pages,
        int count,
        Instant generatedAt
) {
    public Sitemap {
        pages = pages == null ? List.of() : List.copyOf( pages );
    }
}
```

- [ ] **Step 7: Create IndexHealth**

```java
package com.wikantik.api.structure;

import java.time.Instant;

public record IndexHealth(
        Status status,
        int pages,
        int unclaimedCanonicalIds,
        Instant lastRebuildStartedAt,
        Instant lastRebuildFinishedAt,
        long lastRebuildDurationMillis,
        long lagSeconds
) {
    public enum Status { UP, REBUILDING, DEGRADED, DOWN }
}
```

- [ ] **Step 8: Compile-check**

Run: `mvn compile -pl wikantik-api -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/structure/
git commit -m "feat(api): structural-index value types (PageDescriptor, ClusterSummary, TagSummary, StructuralFilter, Sitemap, IndexHealth)"
```

---

## Task 4: StructuralIndexService interface

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/structure/StructuralIndexService.java`

No test — interface only. Behaviour is tested against the default implementation in later tasks.

- [ ] **Step 1: Write the interface**

```java
package com.wikantik.api.structure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent-facing, machine-queryable projection of wiki structure. Maintained
 * incrementally via {@code WikiPageEvent} subscriptions and rebuilt from
 * frontmatter on startup. Callers should treat every result as potentially
 * stale by {@link IndexHealth#lagSeconds} seconds — the service will surface
 * lag rather than return wrong data.
 *
 * <p>Phase 1: observe-only. Pages without {@code canonical_id} frontmatter are
 * still indexed (their ID is synthesised and flagged in {@code unclaimedCanonicalIds}).
 * Phase 4 tightens this into a hard save-time requirement — see
 * {@code docs/wikantik-pages/StructuralSpineDesign.md}.</p>
 */
public interface StructuralIndexService {

    List< ClusterSummary > listClusters();

    Optional< ClusterDetails > getCluster( String name );

    List< TagSummary > listTags( int minPages );

    List< PageDescriptor > listPagesByType( PageType type );

    List< PageDescriptor > listPagesByFilter( StructuralFilter filter );

    Sitemap sitemap();

    Optional< PageDescriptor > getByCanonicalId( String canonicalId );

    Optional< String > resolveSlugFromCanonicalId( String canonicalId );

    Optional< String > resolveCanonicalIdFromSlug( String slug );

    /** Rebuilds the projection from the authoritative frontmatter source. Blocks until complete. */
    void rebuild();

    IndexHealth health();

    /** Snapshot of the current projection used by metrics and admin UIs. Stable for the duration of the call. */
    StructuralProjectionSnapshot snapshot();

    /** Immutable snapshot — exposed for observability but not for mutation. */
    interface StructuralProjectionSnapshot {
        int pageCount();
        int clusterCount();
        int tagCount();
        Instant generatedAt();
    }
}
```

- [ ] **Step 2: Compile-check**

Run: `mvn compile -pl wikantik-api -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/structure/StructuralIndexService.java
git commit -m "feat(api): StructuralIndexService interface for machine-queryable wiki structure"
```

---

## Task 5: PageCanonicalIdsDao — failing JDBC test

**Files:**
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/structure/PageCanonicalIdsDaoTest.java`

- [ ] **Step 1: Write failing test with H2 fixture**

```java
package com.wikantik.knowledge.structure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PageCanonicalIdsDaoTest {

    private DataSource ds;
    private PageCanonicalIdsDao dao;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:pci;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE page_canonical_ids (
                    canonical_id CHAR(26) PRIMARY KEY,
                    current_slug VARCHAR(512) NOT NULL UNIQUE,
                    title VARCHAR(512) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    cluster VARCHAR(128),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""" );
            s.executeUpdate( """
                CREATE TABLE page_slug_history (
                    canonical_id CHAR(26) NOT NULL,
                    previous_slug VARCHAR(512) NOT NULL,
                    renamed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (canonical_id, previous_slug)
                )""" );
        }
        this.dao = new PageCanonicalIdsDao( ds );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE page_slug_history" );
            s.executeUpdate( "DROP TABLE page_canonical_ids" );
        }
    }

    @Test
    void upsert_inserts_new_row() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridRetrieval", "Hybrid Retrieval",
                    "article", "wikantik-development" );

        final Optional< PageCanonicalIdsDao.Row > row =
                dao.findByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" );
        assertTrue( row.isPresent() );
        assertEquals( "HybridRetrieval",   row.get().currentSlug() );
        assertEquals( "article",           row.get().type() );
        assertEquals( "wikantik-development", row.get().cluster() );
    }

    @Test
    void upsert_updates_slug_and_records_history() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridRetrieval", "Hybrid Retrieval",
                    "article", "wikantik-development" );

        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridSearch", "Hybrid Search",
                    "article", "wikantik-development" );

        final var row = dao.findByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).orElseThrow();
        assertEquals( "HybridSearch", row.currentSlug() );

        final List< String > history = dao.slugHistory( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" );
        assertEquals( List.of( "HybridRetrieval" ), history );
    }

    @Test
    void findBySlug_returns_row_for_current_slug() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridRetrieval", "Hybrid Retrieval",
                    "article", "wikantik-development" );
        assertTrue( dao.findBySlug( "HybridRetrieval" ).isPresent() );
        assertTrue( dao.findBySlug( "ThisDoesNotExist" ).isEmpty() );
    }

    @Test
    void findAll_returns_rows_in_stable_order() {
        dao.upsert( "01ABCDEFGHJKMNPQRSTVWXYZ12", "AA", "AA", "article", null );
        dao.upsert( "01ABCDEFGHJKMNPQRSTVWXYZ13", "BB", "BB", "hub",     null );
        final List< PageCanonicalIdsDao.Row > all = dao.findAll();
        assertEquals( 2, all.size() );
    }

    @Test
    void delete_removes_canonical_and_cascades_history() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "A", "A", "article", null );
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "B", "B", "article", null );
        assertEquals( 1, dao.slugHistory( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).size() );

        dao.delete( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" );
        assertTrue( dao.findByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).isEmpty() );
    }
}
```

- [ ] **Step 2: Add H2 test dep if not already present**

Run: `grep -l 'h2database' wikantik-knowledge/pom.xml`. If no match, add to `wikantik-knowledge/pom.xml` under `<dependencies>`:

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

(Version comes from the BOM.)

- [ ] **Step 3: Run test and see it fail**

Run: `mvn test -pl wikantik-knowledge -Dtest=PageCanonicalIdsDaoTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: compile error because `PageCanonicalIdsDao` does not exist yet.

- [ ] **Step 4: Do not commit the failing test alone** — continue to Task 6.

---

## Task 6: PageCanonicalIdsDao implementation

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/PageCanonicalIdsDao.java`

- [ ] **Step 1: Write the DAO**

```java
package com.wikantik.knowledge.structure;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC gateway for {@code page_canonical_ids} and {@code page_slug_history}.
 * All methods are idempotent: {@link #upsert} inserts on miss and updates
 * on hit, emitting a history row whenever the slug actually changes.
 */
public class PageCanonicalIdsDao {

    private static final Logger LOG = LogManager.getLogger( PageCanonicalIdsDao.class );

    private final DataSource ds;

    public PageCanonicalIdsDao( final DataSource ds ) {
        this.ds = ds;
    }

    public void upsert( final String canonicalId,
                        final String currentSlug,
                        final String title,
                        final String type,
                        final String cluster ) {
        try ( Connection c = ds.getConnection() ) {
            c.setAutoCommit( false );
            try {
                final Optional< Row > existing = findByCanonicalId( c, canonicalId );
                if ( existing.isEmpty() ) {
                    try ( PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO page_canonical_ids " +
                            "(canonical_id, current_slug, title, type, cluster) " +
                            "VALUES (?, ?, ?, ?, ?)" ) ) {
                        ps.setString( 1, canonicalId );
                        ps.setString( 2, currentSlug );
                        ps.setString( 3, title );
                        ps.setString( 4, type );
                        ps.setString( 5, cluster );
                        ps.executeUpdate();
                    }
                } else {
                    final Row prev = existing.get();
                    if ( !prev.currentSlug().equals( currentSlug ) ) {
                        try ( PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO page_slug_history (canonical_id, previous_slug) " +
                                "VALUES (?, ?) " +
                                "ON CONFLICT (canonical_id, previous_slug) DO NOTHING" ) ) {
                            ps.setString( 1, canonicalId );
                            ps.setString( 2, prev.currentSlug() );
                            ps.executeUpdate();
                        }
                    }
                    try ( PreparedStatement ps = c.prepareStatement(
                            "UPDATE page_canonical_ids SET " +
                            "current_slug = ?, title = ?, type = ?, cluster = ?, updated_at = CURRENT_TIMESTAMP " +
                            "WHERE canonical_id = ?" ) ) {
                        ps.setString( 1, currentSlug );
                        ps.setString( 2, title );
                        ps.setString( 3, type );
                        ps.setString( 4, cluster );
                        ps.setString( 5, canonicalId );
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch ( final SQLException e ) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit( true );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "PageCanonicalIdsDao.upsert({}) failed: {}", canonicalId, e.getMessage(), e );
            throw new RuntimeException( "upsert failed", e );
        }
    }

    public Optional< Row > findByCanonicalId( final String canonicalId ) {
        try ( Connection c = ds.getConnection() ) {
            return findByCanonicalId( c, canonicalId );
        } catch ( final SQLException e ) {
            LOG.warn( "findByCanonicalId({}) failed: {}", canonicalId, e.getMessage() );
            return Optional.empty();
        }
    }

    private Optional< Row > findByCanonicalId( final Connection c, final String id ) throws SQLException {
        try ( PreparedStatement ps = c.prepareStatement(
                "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                "FROM page_canonical_ids WHERE canonical_id = ?" ) ) {
            ps.setString( 1, id );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? Optional.of( readRow( rs ) ) : Optional.empty();
            }
        }
    }

    public Optional< Row > findBySlug( final String slug ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                      "FROM page_canonical_ids WHERE current_slug = ?" ) ) {
            ps.setString( 1, slug );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? Optional.of( readRow( rs ) ) : Optional.empty();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "findBySlug({}) failed: {}", slug, e.getMessage() );
            return Optional.empty();
        }
    }

    public List< Row > findAll() {
        final List< Row > rows = new ArrayList<>();
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT canonical_id, current_slug, title, type, cluster, created_at, updated_at " +
                      "FROM page_canonical_ids ORDER BY canonical_id" );
              ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) {
                rows.add( readRow( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "findAll() failed: {}", e.getMessage() );
        }
        return rows;
    }

    public List< String > slugHistory( final String canonicalId ) {
        final List< String > history = new ArrayList<>();
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT previous_slug FROM page_slug_history " +
                      "WHERE canonical_id = ? ORDER BY renamed_at DESC" ) ) {
            ps.setString( 1, canonicalId );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    history.add( rs.getString( 1 ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "slugHistory({}) failed: {}", canonicalId, e.getMessage() );
        }
        return history;
    }

    public void delete( final String canonicalId ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "DELETE FROM page_canonical_ids WHERE canonical_id = ?" ) ) {
            ps.setString( 1, canonicalId );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "delete({}) failed: {}", canonicalId, e.getMessage() );
            throw new RuntimeException( "delete failed", e );
        }
    }

    private static Row readRow( final ResultSet rs ) throws SQLException {
        return new Row(
                rs.getString( "canonical_id" ),
                rs.getString( "current_slug" ),
                rs.getString( "title" ),
                rs.getString( "type" ),
                rs.getString( "cluster" ),
                rs.getTimestamp( "created_at" ).toInstant(),
                rs.getTimestamp( "updated_at" ).toInstant() );
    }

    public record Row(
            String canonicalId,
            String currentSlug,
            String title,
            String type,
            String cluster,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
```

- [ ] **Step 2: Run DAO test and verify it passes**

Run: `mvn test -pl wikantik-knowledge -Dtest=PageCanonicalIdsDaoTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: BUILD SUCCESS, 5 tests passed.

- [ ] **Step 3: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/PageCanonicalIdsDao.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/structure/PageCanonicalIdsDaoTest.java \
        wikantik-knowledge/pom.xml
git commit -m "feat(knowledge): PageCanonicalIdsDao for canonical_id + slug history"
```

---

## Task 7: StructuralProjection immutable snapshot + builder

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/StructuralProjection.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/StructuralProjectionBuilder.java`
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/structure/StructuralProjectionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.structure;

import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralFilter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StructuralProjectionTest {

    private static PageDescriptor page( final String id, final String slug, final PageType type,
                                         final String cluster, final List< String > tags ) {
        return new PageDescriptor( id, slug, slug, type, cluster, tags,
                                    slug + " summary", Instant.parse( "2026-04-01T00:00:00Z" ) );
    }

    @Test
    void build_returns_cluster_summaries() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "HybridRetrieval", PageType.ARTICLE, "wikantik-development",
                                List.of( "retrieval" ) ) )
                .addPage( page( "B", "WikantikDevelopment", PageType.HUB, "wikantik-development",
                                List.of() ) )
                .addPage( page( "C", "IndexFunds",         PageType.ARTICLE, "investing",
                                List.of( "investing" ) ) )
                .build();

        final var clusters = proj.listClusters();
        assertEquals( 2, clusters.size() );
        final var dev = clusters.stream().filter( c -> "wikantik-development".equals( c.name() ) ).findFirst().orElseThrow();
        assertEquals( 2, dev.articleCount() );
        assertEquals( "WikantikDevelopment", dev.hubPage().slug() );
    }

    @Test
    void listTags_excludes_tags_under_min_pages() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, null, List.of( "alpha", "beta" ) ) )
                .addPage( page( "B", "Y", PageType.ARTICLE, null, List.of( "alpha" ) ) )
                .addPage( page( "C", "Z", PageType.ARTICLE, null, List.of( "beta" ) ) )
                .build();
        final var tags2 = proj.listTags( 2 );
        assertEquals( 2, tags2.size() );
        final var tags1 = proj.listTags( 1 );
        assertEquals( 2, tags1.size() );  // alpha, beta both appear
    }

    @Test
    void listPagesByFilter_by_type_and_cluster() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, "c1", List.of() ) )
                .addPage( page( "B", "Y", PageType.HUB,     "c1", List.of() ) )
                .addPage( page( "C", "Z", PageType.ARTICLE, "c2", List.of() ) )
                .build();
        final var result = proj.listPagesByFilter( new StructuralFilter(
                Optional.of( PageType.ARTICLE ), Optional.of( "c1" ), null, null, 100, null ) );
        assertEquals( 1, result.size() );
        assertEquals( "X", result.get( 0 ).slug() );
    }

    @Test
    void listPagesByFilter_by_all_tags_AND() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, null, List.of( "alpha", "beta" ) ) )
                .addPage( page( "B", "Y", PageType.ARTICLE, null, List.of( "alpha" ) ) )
                .build();
        final var result = proj.listPagesByFilter( new StructuralFilter(
                null, null, List.of( "alpha", "beta" ), null, 100, null ) );
        assertEquals( 1, result.size() );
        assertEquals( "X", result.get( 0 ).slug() );
    }

    @Test
    void getByCanonicalId_and_resolveSlug_round_trip() {
        final var proj = new StructuralProjectionBuilder()
                .addPage( page( "A", "X", PageType.ARTICLE, null, List.of() ) )
                .build();
        assertEquals( Optional.of( "X" ),  proj.resolveSlugFromCanonicalId( "A" ) );
        assertEquals( Optional.of( "A" ),  proj.resolveCanonicalIdFromSlug( "X" ) );
        assertTrue( proj.getByCanonicalId( "A" ).isPresent() );
        assertTrue( proj.getByCanonicalId( "Z" ).isEmpty() );
    }
}
```

- [ ] **Step 2: Run test and see it fail**

Run: `mvn test -pl wikantik-knowledge -Dtest=StructuralProjectionTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: compile error — `StructuralProjection` and `StructuralProjectionBuilder` do not exist.

- [ ] **Step 3: Write StructuralProjection (immutable snapshot)**

```java
package com.wikantik.knowledge.structure;

import com.wikantik.api.structure.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of the structural projection. Built by {@link StructuralProjectionBuilder}
 * during a full rebuild; exposed through {@link DefaultStructuralIndexService} as the query
 * substrate. All list results are defensively copied at construction time.
 */
public final class StructuralProjection {

    private final Map< String, PageDescriptor > byCanonicalId;
    private final Map< String, String >         slugToCanonicalId;
    private final Map< String, List< PageDescriptor > > byCluster;
    private final Map< String, PageDescriptor > hubByCluster;
    private final Map< String, List< PageDescriptor > > byTag;
    private final Map< PageType, List< PageDescriptor > > byType;
    private final Instant generatedAt;

    StructuralProjection( final Map< String, PageDescriptor > byCanonicalId,
                          final Map< String, String > slugToCanonicalId,
                          final Map< String, List< PageDescriptor > > byCluster,
                          final Map< String, PageDescriptor > hubByCluster,
                          final Map< String, List< PageDescriptor > > byTag,
                          final Map< PageType, List< PageDescriptor > > byType,
                          final Instant generatedAt ) {
        this.byCanonicalId     = Map.copyOf( byCanonicalId );
        this.slugToCanonicalId = Map.copyOf( slugToCanonicalId );
        this.byCluster         = deepCopy( byCluster );
        this.hubByCluster      = Map.copyOf( hubByCluster );
        this.byTag             = deepCopy( byTag );
        this.byType            = deepCopyEnum( byType );
        this.generatedAt       = generatedAt;
    }

    public Instant generatedAt() { return generatedAt; }

    public int pageCount() { return byCanonicalId.size(); }
    public int clusterCount() { return byCluster.size(); }
    public int tagCount() { return byTag.size(); }

    public List< ClusterSummary > listClusters() {
        final List< ClusterSummary > out = new ArrayList<>( byCluster.size() );
        byCluster.forEach( ( name, pages ) -> out.add( new ClusterSummary(
                name,
                hubByCluster.get( name ),
                pages.size(),
                pages.stream().map( PageDescriptor::updated ).filter( Objects::nonNull )
                     .max( Instant::compareTo ).orElse( null ) ) ) );
        out.sort( Comparator.comparing( ClusterSummary::name ) );
        return out;
    }

    public Optional< ClusterDetails > getCluster( final String name ) {
        final List< PageDescriptor > pages = byCluster.get( name );
        if ( pages == null ) {
            return Optional.empty();
        }
        final Map< String, Integer > tagDist = new TreeMap<>();
        pages.forEach( p -> p.tags().forEach( t -> tagDist.merge( t, 1, Integer::sum ) ) );
        return Optional.of( new ClusterDetails(
                name,
                hubByCluster.get( name ),
                pages,
                tagDist,
                pages.stream().map( PageDescriptor::updated ).filter( Objects::nonNull )
                     .max( Instant::compareTo ).orElse( null ) ) );
    }

    public List< TagSummary > listTags( final int minPages ) {
        final int threshold = Math.max( 1, minPages );
        return byTag.entrySet().stream()
                .filter( e -> e.getValue().size() >= threshold )
                .map( e -> new TagSummary(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream().limit( 10 ).map( PageDescriptor::canonicalId ).toList() ) )
                .sorted( Comparator.comparingInt( TagSummary::count ).reversed()
                        .thenComparing( TagSummary::tag ) )
                .collect( Collectors.toList() );
    }

    public List< PageDescriptor > listPagesByType( final PageType type ) {
        return byType.getOrDefault( type, List.of() );
    }

    public List< PageDescriptor > listPagesByFilter( final StructuralFilter filter ) {
        return byCanonicalId.values().stream()
                .filter( p -> filter.type().map( t -> t == p.type() ).orElse( true ) )
                .filter( p -> filter.cluster().map( c -> c.equals( p.cluster() ) ).orElse( true ) )
                .filter( p -> filter.tags().isEmpty() || p.tags().containsAll( filter.tags() ) )
                .filter( p -> filter.updatedSince()
                        .map( since -> p.updated() != null && !p.updated().isBefore( since ) )
                        .orElse( true ) )
                .sorted( Comparator.comparing( PageDescriptor::canonicalId ) )
                .limit( filter.limit() )
                .toList();
    }

    public Sitemap sitemap() {
        final List< PageDescriptor > all = byCanonicalId.values().stream()
                .sorted( Comparator.comparing( PageDescriptor::slug ) ).toList();
        return new Sitemap( all, all.size(), Instant.now() );
    }

    public Optional< PageDescriptor > getByCanonicalId( final String canonicalId ) {
        return Optional.ofNullable( byCanonicalId.get( canonicalId ) );
    }

    public Optional< String > resolveSlugFromCanonicalId( final String canonicalId ) {
        return Optional.ofNullable( byCanonicalId.get( canonicalId ) ).map( PageDescriptor::slug );
    }

    public Optional< String > resolveCanonicalIdFromSlug( final String slug ) {
        return Optional.ofNullable( slugToCanonicalId.get( slug ) );
    }

    private static < K > Map< K, List< PageDescriptor > > deepCopy( final Map< K, List< PageDescriptor > > m ) {
        final Map< K, List< PageDescriptor > > out = new HashMap<>( m.size() );
        m.forEach( ( k, v ) -> out.put( k, List.copyOf( v ) ) );
        return Collections.unmodifiableMap( out );
    }

    private static Map< PageType, List< PageDescriptor > > deepCopyEnum( final Map< PageType, List< PageDescriptor > > m ) {
        final EnumMap< PageType, List< PageDescriptor > > out = new EnumMap<>( PageType.class );
        m.forEach( ( k, v ) -> out.put( k, List.copyOf( v ) ) );
        return Collections.unmodifiableMap( out );
    }
}
```

- [ ] **Step 4: Write StructuralProjectionBuilder**

```java
package com.wikantik.knowledge.structure;

import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;

import java.time.Instant;
import java.util.*;

public final class StructuralProjectionBuilder {

    private final Map< String, PageDescriptor > byCanonicalId = new LinkedHashMap<>();
    private final Map< String, String >         slugToCanonicalId = new LinkedHashMap<>();
    private final Map< String, List< PageDescriptor > > byCluster = new LinkedHashMap<>();
    private final Map< String, PageDescriptor > hubByCluster = new HashMap<>();
    private final Map< String, List< PageDescriptor > > byTag = new LinkedHashMap<>();
    private final Map< PageType, List< PageDescriptor > > byType = new EnumMap<>( PageType.class );

    public StructuralProjectionBuilder addPage( final PageDescriptor page ) {
        byCanonicalId.put( page.canonicalId(), page );
        slugToCanonicalId.put( page.slug(), page.canonicalId() );

        if ( page.cluster() != null ) {
            byCluster.computeIfAbsent( page.cluster(), k -> new ArrayList<>() ).add( page );
            if ( page.type() == PageType.HUB ) {
                hubByCluster.put( page.cluster(), page );
            }
        }

        for ( final String tag : page.tags() ) {
            byTag.computeIfAbsent( tag, k -> new ArrayList<>() ).add( page );
        }

        byType.computeIfAbsent( page.type(), k -> new ArrayList<>() ).add( page );
        return this;
    }

    public StructuralProjection build() {
        return new StructuralProjection(
                byCanonicalId,
                slugToCanonicalId,
                byCluster,
                hubByCluster,
                byTag,
                byType,
                Instant.now() );
    }
}
```

- [ ] **Step 5: Run test and verify pass**

Run: `mvn test -pl wikantik-knowledge -Dtest=StructuralProjectionTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: BUILD SUCCESS, 5 tests passed.

- [ ] **Step 6: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/StructuralProjection.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/StructuralProjectionBuilder.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/structure/StructuralProjectionTest.java
git commit -m "feat(knowledge): StructuralProjection immutable snapshot + builder"
```

---

## Task 8: DefaultStructuralIndexService — rebuild + unit test

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/DefaultStructuralIndexService.java`
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/structure/DefaultStructuralIndexServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.structure;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.structure.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultStructuralIndexServiceTest {

    private PageManager pageManager;
    private PageCanonicalIdsDao dao;
    private DefaultStructuralIndexService svc;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        dao = mock( PageCanonicalIdsDao.class );
        svc = new DefaultStructuralIndexService( pageManager, dao );
    }

    private Page fakePage( final String name, final String frontmatter, final String body ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getLastModified() ).thenReturn( new java.util.Date( 1700000000000L ) );
        // Body returned by PageManager.getPureText is expected to include frontmatter
        try {
            when( pageManager.getPureText( p ) ).thenReturn( "---\n" + frontmatter + "\n---\n" + body );
        } catch ( final Exception ignored ) { /* mock, no-op */ }
        return p;
    }

    @Test
    void rebuild_indexes_every_page_returned_by_pageManager() throws Exception {
        final Page a = fakePage( "HybridRetrieval",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8MN\n" +
                "title: Hybrid Retrieval\n" +
                "type: article\n" +
                "cluster: wikantik-development\n" +
                "tags: [retrieval, bm25]\n" +
                "summary: Hybrid retrieval reference.", "body" );
        final Page b = fakePage( "WikantikDevelopment",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8A0\n" +
                "title: Wikantik Development\n" +
                "type: hub\n" +
                "cluster: wikantik-development\n" +
                "tags: [wikantik]\n" +
                "summary: Dev hub.", "body" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b ) );

        svc.rebuild();

        final var clusters = svc.listClusters();
        assertEquals( 1, clusters.size() );
        assertEquals( "wikantik-development", clusters.get( 0 ).name() );
        assertEquals( 2, clusters.get( 0 ).articleCount() );

        assertTrue( svc.getByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).isPresent() );
        verify( dao, times( 2 ) ).upsert( any(), any(), any(), any(), any() );
    }

    @Test
    void rebuild_synthesises_canonical_id_for_pages_missing_frontmatter_field() throws Exception {
        final Page a = fakePage( "RawPage", "title: Raw Page\ntype: article", "body" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );

        svc.rebuild();

        final var health = svc.health();
        assertEquals( 1, health.unclaimedCanonicalIds() );
        assertEquals( 1, svc.snapshot().pageCount() );
        // Synthesised IDs live in memory only — they MUST NOT be written to the DB,
        // otherwise every restart would churn new rows into page_canonical_ids.
        verifyNoInteractions( dao );
    }

    @Test
    void listPagesByFilter_round_trip_after_rebuild() throws Exception {
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: A\ntype: article\ncluster: x\ntags: [t1]", "" );
        final Page b = fakePage( "B",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\n" +
                "title: B\ntype: hub\ncluster: x\ntags: [t1]", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b ) );

        svc.rebuild();

        final var articles = svc.listPagesByFilter( new StructuralFilter(
                Optional.of( PageType.ARTICLE ), null, null, null, 10, null ) );
        assertEquals( 1, articles.size() );
        assertEquals( "A", articles.get( 0 ).slug() );
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run: `mvn test -pl wikantik-knowledge -Dtest=DefaultStructuralIndexServiceTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: compile error — `DefaultStructuralIndexService` not yet defined.

- [ ] **Step 3: Implement DefaultStructuralIndexService**

```java
package com.wikantik.knowledge.structure;

import com.github.f4b6a3.ulid.UlidCreator;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.structure.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of {@link StructuralIndexService}. Maintains an in-memory
 * {@link StructuralProjection} rebuilt on startup and updated incrementally via
 * {@link StructuralIndexEventListener}. Writes canonical-id state through
 * {@link PageCanonicalIdsDao} for durability and rename-stability.
 *
 * <p>Phase 1 is observe-only. Pages missing {@code canonical_id} frontmatter get a
 * synthesised ULID assigned in memory only (not written to disk); they surface in
 * {@link IndexHealth#unclaimedCanonicalIds} so authors can backfill.</p>
 */
public class DefaultStructuralIndexService implements StructuralIndexService {

    private static final Logger LOG = LogManager.getLogger( DefaultStructuralIndexService.class );

    private final PageManager pageManager;
    private final PageCanonicalIdsDao dao;

    private final AtomicReference< StructuralProjection > current =
            new AtomicReference<>( new StructuralProjectionBuilder().build() );
    private volatile IndexHealth health = new IndexHealth(
            IndexHealth.Status.DOWN, 0, 0, null, null, 0L, 0L );
    private volatile int unclaimed = 0;

    public DefaultStructuralIndexService( final PageManager pageManager,
                                          final PageCanonicalIdsDao dao ) {
        this.pageManager = pageManager;
        this.dao = dao;
    }

    @Override
    public synchronized void rebuild() {
        final Instant start = Instant.now();
        this.health = new IndexHealth( IndexHealth.Status.REBUILDING,
                current.get().pageCount(), unclaimed, start, null, 0L, 0L );

        final StructuralProjectionBuilder builder = new StructuralProjectionBuilder();
        int missing = 0;
        int indexed = 0;
        Collection< Page > pages;
        try {
            pages = pageManager.getAllPages();
        } catch ( final Exception e ) {
            LOG.warn( "rebuild() could not enumerate pages: {}", e.getMessage(), e );
            this.health = new IndexHealth( IndexHealth.Status.DEGRADED, 0, 0, start, Instant.now(), 0L, 0L );
            return;
        }

        for ( final Page p : pages ) {
            try {
                final String raw = pageManager.getPureText( p );
                final ParsedPage parsed = FrontmatterParser.parse( raw );
                final Map< String, Object > fm = parsed.metadata();

                String canonicalId = asString( fm.get( "canonical_id" ) );
                final boolean authored = canonicalId != null && !canonicalId.isBlank();
                if ( !authored ) {
                    canonicalId = UlidCreator.getUlid().toString();
                    missing++;
                }

                final PageType type = PageType.fromFrontmatter( fm.get( "type" ) );
                final String cluster = asString( fm.get( "cluster" ) );
                final String title = firstNonBlank( asString( fm.get( "title" ) ), p.getName() );
                final String summary = asString( fm.get( "summary" ) );
                final List< String > tags = stringList( fm.get( "tags" ) );
                final Instant updated = p.getLastModified() == null ? null : p.getLastModified().toInstant();

                builder.addPage( new PageDescriptor(
                        canonicalId, p.getName(), title, type, cluster, tags, summary, updated ) );

                // Only persist canonical_ids authored in frontmatter. Synthesised IDs live
                // in memory until an author (or Phase 4's mandatory validator) writes them
                // to disk — otherwise every restart would churn fresh rows into the DB.
                if ( authored ) {
                    try {
                        dao.upsert( canonicalId, p.getName(), title, type.asFrontmatterValue(), cluster );
                    } catch ( final RuntimeException dbx ) {
                        LOG.warn( "DAO upsert failed for {} — in-memory projection will continue: {}",
                                  p.getName(), dbx.getMessage() );
                    }
                }

                indexed++;
            } catch ( final Exception e ) {
                LOG.warn( "rebuild(): failed to index page {}: {}", p.getName(), e.getMessage() );
            }
        }

        current.set( builder.build() );
        this.unclaimed = missing;

        final Instant finish = Instant.now();
        this.health = new IndexHealth( IndexHealth.Status.UP, indexed, missing,
                start, finish, finish.toEpochMilli() - start.toEpochMilli(), 0L );
        LOG.info( "Structural index rebuilt: {} pages indexed ({} without canonical_id) in {} ms",
                  indexed, missing, finish.toEpochMilli() - start.toEpochMilli() );
    }

    @Override
    public List< ClusterSummary > listClusters()          { return current.get().listClusters(); }

    @Override
    public Optional< ClusterDetails > getCluster( final String name ) {
        return current.get().getCluster( name );
    }

    @Override
    public List< TagSummary > listTags( final int minPages ) { return current.get().listTags( minPages ); }

    @Override
    public List< PageDescriptor > listPagesByType( final PageType type ) {
        return current.get().listPagesByType( type );
    }

    @Override
    public List< PageDescriptor > listPagesByFilter( final StructuralFilter filter ) {
        return current.get().listPagesByFilter( filter );
    }

    @Override
    public Sitemap sitemap()                             { return current.get().sitemap(); }

    @Override
    public Optional< PageDescriptor > getByCanonicalId( final String canonicalId ) {
        return current.get().getByCanonicalId( canonicalId );
    }

    @Override
    public Optional< String > resolveSlugFromCanonicalId( final String canonicalId ) {
        return current.get().resolveSlugFromCanonicalId( canonicalId );
    }

    @Override
    public Optional< String > resolveCanonicalIdFromSlug( final String slug ) {
        return current.get().resolveCanonicalIdFromSlug( slug );
    }

    @Override
    public IndexHealth health() { return health; }

    @Override
    public StructuralProjectionSnapshot snapshot() {
        final StructuralProjection p = current.get();
        return new StructuralProjectionSnapshot() {
            @Override public int pageCount()       { return p.pageCount(); }
            @Override public int clusterCount()    { return p.clusterCount(); }
            @Override public int tagCount()        { return p.tagCount(); }
            @Override public Instant generatedAt() { return p.generatedAt(); }
        };
    }

    /** Called by {@link StructuralIndexEventListener} on save/delete events. */
    synchronized void onPageSaved( final String pageName ) {
        try {
            final Page page = pageManager.getPage( pageName );
            if ( page == null ) {
                return;
            }
            // For Phase 1 we defer to a full rebuild on incremental events — the
            // projection of ~2000 pages rebuilds in well under a second. Phase 2
            // will make this properly incremental.
            rebuild();
        } catch ( final Exception e ) {
            LOG.warn( "onPageSaved({}) failed: {}", pageName, e.getMessage() );
        }
    }

    synchronized void onPageDeleted( final String pageName ) {
        rebuild();
    }

    private static String asString( final Object o ) {
        if ( o == null ) return null;
        final String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank( final String a, final String b ) {
        return ( a == null || a.isBlank() ) ? b : a;
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > stringList( final Object o ) {
        if ( o == null ) return List.of();
        if ( o instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object x : list ) {
                if ( x != null ) out.add( x.toString() );
            }
            return List.copyOf( out );
        }
        return List.of( o.toString() );
    }
}
```

- [ ] **Step 4: Run test and verify it passes**

Run: `mvn test -pl wikantik-knowledge -Dtest=DefaultStructuralIndexServiceTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: BUILD SUCCESS, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/DefaultStructuralIndexService.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/structure/DefaultStructuralIndexServiceTest.java
git commit -m "feat(knowledge): DefaultStructuralIndexService observe-only rebuild"
```

---

## Task 9: StructuralIndexEventListener

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/StructuralIndexEventListener.java`
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/structure/StructuralIndexEventListenerTest.java`

- [ ] **Step 1: Failing test**

```java
package com.wikantik.knowledge.structure;

import com.wikantik.event.WikiPageEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class StructuralIndexEventListenerTest {

    @Test
    void post_save_triggers_onPageSaved() {
        final DefaultStructuralIndexService svc = mock( DefaultStructuralIndexService.class );
        final StructuralIndexEventListener listener = new StructuralIndexEventListener( svc );

        final WikiPageEvent evt = new WikiPageEvent( this, WikiPageEvent.POST_SAVE, "HybridRetrieval" );
        listener.actionPerformed( evt );

        verify( svc, times( 1 ) ).onPageSaved( "HybridRetrieval" );
    }

    @Test
    void page_deleted_triggers_onPageDeleted() {
        final DefaultStructuralIndexService svc = mock( DefaultStructuralIndexService.class );
        final StructuralIndexEventListener listener = new StructuralIndexEventListener( svc );

        final WikiPageEvent evt = new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "GoneBaby" );
        listener.actionPerformed( evt );

        verify( svc, times( 1 ) ).onPageDeleted( "GoneBaby" );
    }

    @Test
    void other_events_are_ignored() {
        final DefaultStructuralIndexService svc = mock( DefaultStructuralIndexService.class );
        final StructuralIndexEventListener listener = new StructuralIndexEventListener( svc );

        final WikiPageEvent evt = new WikiPageEvent( this, WikiPageEvent.PAGE_LOCK, "X" );
        listener.actionPerformed( evt );

        verifyNoInteractions( svc );
    }
}
```

- [ ] **Step 2: Run test and see it fail**

Run: `mvn test -pl wikantik-knowledge -Dtest=StructuralIndexEventListenerTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: compile error — listener class not defined.

- [ ] **Step 3: Implement listener**

```java
package com.wikantik.knowledge.structure;

import com.wikantik.api.managers.PageManager;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forwards wiki {@link WikiPageEvent}s to the {@link DefaultStructuralIndexService}.
 * Wired from {@link com.wikantik.knowledge.mcp.KnowledgeMcpInitializer}, following
 * the same pattern as {@code com.wikantik.mcp.resources.WikiEventSubscriptionBridge}.
 */
public class StructuralIndexEventListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( StructuralIndexEventListener.class );

    private final DefaultStructuralIndexService service;

    public StructuralIndexEventListener( final DefaultStructuralIndexService service ) {
        this.service = service;
    }

    public void register( final PageManager pageManager ) {
        WikiEventManager.addWikiEventListener( pageManager, this );
        LOG.info( "Structural index event listener registered for PageManager events" );
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( !( event instanceof WikiPageEvent pageEvent ) ) {
            return;
        }
        switch ( pageEvent.getType() ) {
            case WikiPageEvent.POST_SAVE    -> service.onPageSaved( pageEvent.getPageName() );
            case WikiPageEvent.PAGE_DELETED -> service.onPageDeleted( pageEvent.getPageName() );
            default                         -> { /* ignore other event types */ }
        }
    }
}
```

- [ ] **Step 4: Run test and verify**

Run: `mvn test -pl wikantik-knowledge -Dtest=StructuralIndexEventListenerTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: BUILD SUCCESS, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/StructuralIndexEventListener.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/structure/StructuralIndexEventListenerTest.java
git commit -m "feat(knowledge): wire WikiPageEvents into StructuralIndexService"
```

---

## Task 10: Register StructuralIndexService in WikiEngine

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`

- [ ] **Step 1: Locate the manager-registration block**

Open `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` and find the line `managers.put( HubOverviewService.class, svcs.hubOverviewService() );` (around line 601 today). We will insert the new manager immediately after this block, before the `ContentIndexRebuildService` wiring.

- [ ] **Step 2: Add imports**

At the top of `WikiEngine.java` imports, add:

```java
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.knowledge.structure.DefaultStructuralIndexService;
import com.wikantik.knowledge.structure.PageCanonicalIdsDao;
import com.wikantik.knowledge.structure.StructuralIndexEventListener;
```

- [ ] **Step 3: Instantiate and register the service**

Immediately after `managers.put( com.wikantik.knowledge.chunking.ContentChunkRepository.class, svcs.contentChunkRepo() );`, add:

```java
final PageCanonicalIdsDao canonicalIdsDao = new PageCanonicalIdsDao( ds );
final DefaultStructuralIndexService structuralIndex =
        new DefaultStructuralIndexService( getManager( PageManager.class ), canonicalIdsDao );
managers.put( StructuralIndexService.class, structuralIndex );
new StructuralIndexEventListener( structuralIndex ).register( getManager( PageManager.class ) );
// Kick off initial rebuild in a background thread so Engine.start() does not block on ~2000-page scan.
new Thread( structuralIndex::rebuild, "structural-index-bootstrap" ).start();
LOG.info( "StructuralIndexService registered; initial rebuild dispatched" );
```

- [ ] **Step 4: Ensure wikantik-main depends on wikantik-knowledge**

Run: `grep -A1 'wikantik-knowledge' wikantik-main/pom.xml | head -5`
If no match, add to `wikantik-main/pom.xml` `<dependencies>`:

```xml
<dependency>
    <groupId>com.wikantik</groupId>
    <artifactId>wikantik-knowledge</artifactId>
</dependency>
```

(If a circular dependency surfaces — wikantik-knowledge already depending on wikantik-main — move `DefaultStructuralIndexService` instantiation into a factory class in wikantik-knowledge, analogous to `KnowledgeGraphServiceFactory`. Default assumption: no cycle, since existing `managers.put( KnowledgeGraphService.class, svcs.kgService() );` already imports from wikantik-knowledge.)

- [ ] **Step 5: Compile-check**

Run: `mvn compile -pl wikantik-main -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java wikantik-main/pom.xml
git commit -m "feat(engine): register StructuralIndexService as a manager in initKnowledgeGraph"
```

---

## Task 11: AssignCanonicalIdsCli — failing test

**Files:**
- Create: `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/FrontmatterRewriterTest.java`
- Create: `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/AssignCanonicalIdsCliTest.java`

- [ ] **Step 1: Write FrontmatterRewriterTest**

```java
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterRewriterTest {

    @Test
    void adds_canonical_id_to_existing_frontmatter() {
        final String input = "---\ntitle: X\ntype: article\n---\nbody text";
        final String out = FrontmatterRewriter.assignCanonicalId( input, "01ABCDEFGHJKMNPQRSTVWXYZ12" );
        assertTrue( out.contains( "canonical_id: 01ABCDEFGHJKMNPQRSTVWXYZ12" ) );
        assertTrue( out.contains( "title: X" ) );
        assertTrue( out.contains( "body text" ) );
    }

    @Test
    void creates_frontmatter_block_when_absent() {
        final String input = "Just a body.\n";
        final String out = FrontmatterRewriter.assignCanonicalId( input, "01AAAAAAAAAAAAAAAAAAAAAAAA" );
        assertTrue( out.startsWith( "---\ncanonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n---\n" ) );
        assertTrue( out.contains( "Just a body." ) );
    }

    @Test
    void noop_when_canonical_id_already_present() {
        final String input = "---\ncanonical_id: 01XXXXXXXXXXXXXXXXXXXXXXXX\ntitle: X\n---\nbody";
        final String out = FrontmatterRewriter.assignCanonicalId( input, "01YYYYYYYYYYYYYYYYYYYYYYYY" );
        assertEquals( input, out );
    }

    @Test
    void preserves_crlf_endings() {
        final String input = "---\r\ntitle: X\r\n---\r\nbody\r\n";
        final String out = FrontmatterRewriter.assignCanonicalId( input, "01ZZZZZZZZZZZZZZZZZZZZZZZZ" );
        assertTrue( out.contains( "\r\n" ) );
        assertTrue( out.contains( "canonical_id: 01ZZZZZZZZZZZZZZZZZZZZZZZZ" ) );
    }
}
```

- [ ] **Step 2: Write AssignCanonicalIdsCliTest**

```java
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssignCanonicalIdsCliTest {

    @Test
    void dry_run_reports_missing_without_writing( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "A.md" ), "---\ntitle: A\n---\nbody" );
        Files.writeString( tmp.resolve( "B.md" ), "---\ncanonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n---\nbody" );

        final var result = new AssignCanonicalIdsCli().run( tmp, /* write */ false );

        assertEquals( 2, result.scanned() );
        assertEquals( 1, result.missing() );
        assertEquals( 0, result.updated() );
        assertEquals( "---\ntitle: A\n---\nbody",
                Files.readString( tmp.resolve( "A.md" ) ) );
    }

    @Test
    void write_mode_assigns_unique_canonical_ids( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "A.md" ), "---\ntitle: A\n---\nbody" );
        Files.writeString( tmp.resolve( "B.md" ), "---\ntitle: B\n---\nbody" );

        final var result = new AssignCanonicalIdsCli().run( tmp, /* write */ true );

        assertEquals( 2, result.updated() );
        final String a = Files.readString( tmp.resolve( "A.md" ) );
        final String b = Files.readString( tmp.resolve( "B.md" ) );
        assertTrue( a.contains( "canonical_id:" ) );
        assertTrue( b.contains( "canonical_id:" ) );
        assertNotEquals(
                a.lines().filter( l -> l.startsWith( "canonical_id:" ) ).findFirst().orElseThrow(),
                b.lines().filter( l -> l.startsWith( "canonical_id:" ) ).findFirst().orElseThrow() );
    }

    @Test
    void write_mode_is_idempotent( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "A.md" ), "---\ntitle: A\n---\nbody" );

        new AssignCanonicalIdsCli().run( tmp, true );
        final String afterFirst = Files.readString( tmp.resolve( "A.md" ) );

        final var secondResult = new AssignCanonicalIdsCli().run( tmp, true );
        final String afterSecond = Files.readString( tmp.resolve( "A.md" ) );

        assertEquals( 0, secondResult.updated() );
        assertEquals( afterFirst, afterSecond );
    }

    @Test
    void skips_non_md_files( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "A.md" ), "---\ntitle: A\n---\nbody" );
        Files.writeString( tmp.resolve( "ignored.properties" ), "key=value" );

        final var result = new AssignCanonicalIdsCli().run( tmp, true );
        assertEquals( 1, result.scanned() );
        assertEquals( "key=value", Files.readString( tmp.resolve( "ignored.properties" ) ) );
    }
}
```

- [ ] **Step 3: Run tests and see them fail**

Run: `mvn test -pl wikantik-extract-cli -Dtest='FrontmatterRewriterTest,AssignCanonicalIdsCliTest' -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: compile error — classes don't exist yet. Continue to Task 12.

---

## Task 12: AssignCanonicalIdsCli implementation

**Files:**
- Create: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/FrontmatterRewriter.java`
- Create: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/AssignCanonicalIdsCli.java`

- [ ] **Step 1: Write FrontmatterRewriter**

```java
package com.wikantik.extractcli;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-place canonical-id assignment for wiki Markdown files. Idempotent — a file
 * that already declares {@code canonical_id:} is returned unchanged.
 */
public final class FrontmatterRewriter {

    private static final Pattern OPEN          = Pattern.compile( "\\A---(\\r?\\n)" );
    private static final Pattern HAS_CANONICAL = Pattern.compile( "(?m)^canonical_id:\\s*\\S" );

    private FrontmatterRewriter() {}

    public static String assignCanonicalId( final String input, final String canonicalId ) {
        if ( input == null ) return "---\ncanonical_id: " + canonicalId + "\n---\n";
        if ( HAS_CANONICAL.matcher( input ).find() ) {
            return input;
        }
        final Matcher open = OPEN.matcher( input );
        if ( !open.find() ) {
            // No frontmatter at all — wrap a fresh block around the body.
            final String nl = input.contains( "\r\n" ) ? "\r\n" : "\n";
            return "---" + nl + "canonical_id: " + canonicalId + nl + "---" + nl + input;
        }
        // Insert `canonical_id: X\n` directly after the opening `---\n`.
        final String nl = open.group( 1 );
        return input.substring( 0, open.end() )
                + "canonical_id: " + canonicalId + nl
                + input.substring( open.end() );
    }
}
```

- [ ] **Step 2: Write AssignCanonicalIdsCli**

```java
package com.wikantik.extractcli;

import com.github.f4b6a3.ulid.UlidCreator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks a wiki pages directory, assigns a ULID {@code canonical_id} to every
 * Markdown file that lacks one, and (optionally) rewrites the file in place.
 *
 * <p>Run modes:</p>
 * <ul>
 *   <li>Dry-run (default): scans and reports, does not write.</li>
 *   <li>--write: writes assignments back to disk, idempotent across reruns.</li>
 * </ul>
 *
 * <p>CLI entry:
 * {@code java -cp wikantik-extract-cli.jar com.wikantik.extractcli.AssignCanonicalIdsCli <pagesDir> [--write]}
 * </p>
 */
public class AssignCanonicalIdsCli {

    public record Result( int scanned, int missing, int updated ) {}

    public Result run( final Path pagesDir, final boolean write ) throws IOException {
        int scanned = 0;
        int missing = 0;
        int updated = 0;

        try ( Stream< Path > stream = Files.list( pagesDir ) ) {
            final List< Path > mdFiles = stream
                    .filter( Files::isRegularFile )
                    .filter( p -> p.getFileName().toString().endsWith( ".md" ) )
                    .sorted()
                    .toList();

            for ( final Path file : mdFiles ) {
                scanned++;
                final String content = Files.readString( file );
                if ( content.contains( "canonical_id:" ) ) {
                    continue;
                }
                missing++;
                if ( write ) {
                    final String newId = UlidCreator.getUlid().toString();
                    final String rewritten = FrontmatterRewriter.assignCanonicalId( content, newId );
                    if ( !rewritten.equals( content ) ) {
                        Files.writeString( file, rewritten );
                        updated++;
                    }
                }
            }
        }

        return new Result( scanned, missing, updated );
    }

    public static void main( final String[] args ) throws Exception {
        if ( args.length < 1 ) {
            System.err.println( "Usage: assign-canonical-ids <pagesDir> [--write]" );
            System.exit( 2 );
        }
        final Path pagesDir = Path.of( args[ 0 ] );
        final boolean write = args.length > 1 && "--write".equals( args[ 1 ] );

        final Result r = new AssignCanonicalIdsCli().run( pagesDir, write );
        System.out.printf( "scanned=%d  missing=%d  updated=%d  mode=%s%n",
                r.scanned(), r.missing(), r.updated(), write ? "WRITE" : "DRY-RUN" );
    }
}
```

- [ ] **Step 3: Run tests and verify pass**

Run: `mvn test -pl wikantik-extract-cli -Dtest='FrontmatterRewriterTest,AssignCanonicalIdsCliTest' -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: BUILD SUCCESS, 4 + 4 = 8 tests pass.

- [ ] **Step 4: Commit**

```bash
git add wikantik-extract-cli/src/main/java/com/wikantik/extractcli/FrontmatterRewriter.java \
        wikantik-extract-cli/src/main/java/com/wikantik/extractcli/AssignCanonicalIdsCli.java \
        wikantik-extract-cli/src/test/java/com/wikantik/extractcli/FrontmatterRewriterTest.java \
        wikantik-extract-cli/src/test/java/com/wikantik/extractcli/AssignCanonicalIdsCliTest.java
git commit -m "feat(extract-cli): AssignCanonicalIdsCli — ULID backfill tool for wiki pages"
```

---

## Task 13: Run canonical-id backfill against the real wiki

**Files:**
- Modify (mass update): `docs/wikantik-pages/*.md`

- [ ] **Step 1: Build the CLI jar**

Run: `mvn package -pl wikantik-extract-cli -am -DskipTests -q`
Expected: JAR at `wikantik-extract-cli/target/wikantik-extract-cli-*.jar`.

- [ ] **Step 2: Dry-run against docs/wikantik-pages/**

Run:
```bash
java -cp "wikantik-extract-cli/target/*:wikantik-extract-cli/target/dependency/*" \
     com.wikantik.extractcli.AssignCanonicalIdsCli docs/wikantik-pages
```
(If the JAR is not self-contained, prefer `mvn exec:java` or `java -jar <jar>-with-dependencies.jar` — check `wikantik-extract-cli/pom.xml` for an assembly plugin. If neither is configured, add the `shade` plugin in a follow-up micro-commit so the CLI is self-contained.)

Expected output: `scanned=~1055  missing=~1050  updated=0  mode=DRY-RUN` (exact numbers depend on current page count and which pages already carry canonical_id from prior experimentation).

- [ ] **Step 3: Run in --write mode**

Run:
```bash
java -cp "wikantik-extract-cli/target/*:wikantik-extract-cli/target/dependency/*" \
     com.wikantik.extractcli.AssignCanonicalIdsCli docs/wikantik-pages --write
```
Expected: `updated` equals `missing` from the dry-run.

- [ ] **Step 4: Spot-check three random files**

Run:
```bash
for f in docs/wikantik-pages/About.md docs/wikantik-pages/HybridRetrieval.md docs/wikantik-pages/AgentMemory.md; do
  echo "--- $f ---"; head -3 "$f"
done
```
Expected: each file's frontmatter starts with `---` then `canonical_id: 01...` then the rest.

- [ ] **Step 5: Re-run --write to verify idempotence**

Run: same command as Step 3.
Expected: `updated=0`.

- [ ] **Step 6: Commit the backfill as a single data migration**

```bash
git add docs/wikantik-pages/
git commit -m "data: backfill canonical_id ULID into all wiki pages (Phase 1 of structural spine)"
```

- [ ] **Step 7: Verify no spurious non-frontmatter edits slipped in**

Run: `git show --stat HEAD | tail -5` to confirm the changed-file count matches the expected missing count from Step 2.

---

## Task 14: StructureResource REST endpoints

**Files:**
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/StructureResourceTest.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/StructureResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Study the existing REST servlet pattern**

Run: `cat wikantik-rest/src/main/java/com/wikantik/rest/BacklinksResource.java` (or any short existing resource) to see the idiomatic pattern: `extends RestServletBase`, `doGet(req, resp)`, path parsing via `req.getPathInfo()`, JSON writes via `GSON`. Mirror this.

- [ ] **Step 2: Write the failing test**

```java
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.structure.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StructureResourceTest {

    private StructuralIndexService svc;
    private StructureResource resource;
    private Engine engine;

    @BeforeEach
    void setUp() {
        svc = mock( StructuralIndexService.class );
        engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( svc );
        resource = new StructureResource();
        resource.setEngineForTesting( engine );
    }

    @Test
    void clusters_returns_cluster_list() throws Exception {
        when( svc.listClusters() ).thenReturn( List.of( new ClusterSummary(
                "wikantik-development",
                new PageDescriptor( "01A", "WikantikDevelopment", "Wikantik Development",
                        PageType.HUB, "wikantik-development", List.of(), "hub", Instant.EPOCH ),
                12,
                Instant.parse( "2026-04-01T00:00:00Z" ) ) ) );

        final JsonObject body = callGet( "/clusters" );
        assertTrue( body.has( "data" ) );
        final var clusters = body.getAsJsonObject( "data" ).getAsJsonArray( "clusters" );
        assertEquals( 1, clusters.size() );
        assertEquals( "wikantik-development", clusters.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void tags_returns_tag_dictionary() throws Exception {
        when( svc.listTags( 1 ) ).thenReturn( List.of(
                new TagSummary( "retrieval", 5, List.of( "01X", "01Y" ) ) ) );
        final JsonObject body = callGet( "/tags?min_pages=1" );
        assertEquals( 1, body.getAsJsonObject( "data" ).getAsJsonArray( "tags" ).size() );
    }

    @Test
    void sitemap_returns_all_pages() throws Exception {
        when( svc.sitemap() ).thenReturn( new Sitemap(
                List.of( new PageDescriptor( "01A", "Slug", "T", PageType.ARTICLE, null, List.of(),
                        "summary", Instant.EPOCH ) ),
                1, Instant.EPOCH ) );
        final JsonObject body = callGet( "/sitemap" );
        assertEquals( 1, body.getAsJsonObject( "data" ).get( "count" ).getAsInt() );
    }

    @Test
    void unknown_path_returns_404() throws Exception {
        final HttpServletResponse resp = callRaw( "/does-not-exist" );
        verify( resp ).setStatus( 404 );
    }

    private JsonObject callGet( final String pathInfo ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( pathInfo.split( "\\?" )[ 0 ] );
        if ( pathInfo.contains( "?" ) ) {
            final String qs = pathInfo.substring( pathInfo.indexOf( '?' ) + 1 );
            for ( final String pair : qs.split( "&" ) ) {
                final String[] kv = pair.split( "=" );
                when( req.getParameter( kv[ 0 ] ) ).thenReturn( kv.length > 1 ? kv[ 1 ] : null );
            }
        }
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        resource.doGet( req, resp );
        return JsonParser.parseString( sw.toString() ).getAsJsonObject();
    }

    private HttpServletResponse callRaw( final String pathInfo ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( pathInfo );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        resource.doGet( req, resp );
        return resp;
    }
}
```

- [ ] **Step 3: Run test, see it fail**

Run: `mvn test -pl wikantik-rest -Dtest=StructureResourceTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: compile error — `StructureResource` undefined.

- [ ] **Step 4: Implement StructureResource**

```java
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.api.core.Engine;
import com.wikantik.api.structure.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * {@code /api/structure/*} — machine-queryable wiki structure for agents.
 * Mirrors the {@link StructuralIndexService} surface over REST.
 */
public class StructureResource extends RestServletBase {

    private static final Logger LOG = LogManager.getLogger( StructureResource.class );

    // Test seam — production code uses the Engine set up by RestServletBase.init().
    private Engine engineOverride;

    void setEngineForTesting( final Engine engine ) {
        this.engineOverride = engine;
    }

    private Engine engine() {
        return engineOverride != null ? engineOverride : getEngine();
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String pathInfo = Optional.ofNullable( req.getPathInfo() ).orElse( "" );
        final StructuralIndexService svc = engine().getManager( StructuralIndexService.class );
        if ( svc == null ) {
            writeError( resp, 503, "structural index unavailable" );
            return;
        }

        try {
            if ( pathInfo.equals( "/clusters" ) ) {
                writeClusters( resp, svc );
            } else if ( pathInfo.startsWith( "/clusters/" ) ) {
                writeCluster( resp, svc, pathInfo.substring( "/clusters/".length() ) );
            } else if ( pathInfo.equals( "/tags" ) ) {
                final int min = parseIntOr( req.getParameter( "min_pages" ), 1 );
                writeTags( resp, svc, min );
            } else if ( pathInfo.equals( "/pages" ) ) {
                writePages( resp, svc, req );
            } else if ( pathInfo.equals( "/sitemap" ) ) {
                writeSitemap( resp, svc );
            } else {
                writeError( resp, 404, "unknown structure path: " + pathInfo );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "/api/structure{} failed: {}", pathInfo, e.getMessage(), e );
            writeError( resp, 500, e.getMessage() );
        }
    }

    private void writeClusters( final HttpServletResponse resp, final StructuralIndexService svc )
            throws IOException {
        final JsonArray arr = new JsonArray();
        for ( final ClusterSummary c : svc.listClusters() ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "name", c.name() );
            if ( c.hubPage() != null ) o.add( "hub_page", describe( c.hubPage() ) );
            o.addProperty( "article_count", c.articleCount() );
            if ( c.updatedAt() != null ) o.addProperty( "updated_at", c.updatedAt().toString() );
            arr.add( o );
        }
        final JsonObject data = new JsonObject();
        data.add( "clusters", arr );
        data.addProperty( "generated_at", Instant.now().toString() );
        writeEnvelope( resp, data );
    }

    private void writeCluster( final HttpServletResponse resp, final StructuralIndexService svc,
                                final String name ) throws IOException {
        final Optional< ClusterDetails > d = svc.getCluster( name );
        if ( d.isEmpty() ) {
            writeError( resp, 404, "cluster not found: " + name );
            return;
        }
        final ClusterDetails details = d.get();
        final JsonObject data = new JsonObject();
        data.addProperty( "name", details.name() );
        if ( details.hubPage() != null ) data.add( "hub_page", describe( details.hubPage() ) );
        final JsonArray articles = new JsonArray();
        details.articles().forEach( p -> articles.add( describe( p ) ) );
        data.add( "articles", articles );
        final JsonObject tags = new JsonObject();
        details.tagDistribution().forEach( tags::addProperty );
        data.add( "tag_distribution", tags );
        writeEnvelope( resp, data );
    }

    private void writeTags( final HttpServletResponse resp, final StructuralIndexService svc, final int min )
            throws IOException {
        final JsonArray arr = new JsonArray();
        for ( final TagSummary t : svc.listTags( min ) ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "tag", t.tag() );
            o.addProperty( "count", t.count() );
            final JsonArray pages = new JsonArray();
            t.topPageIds().forEach( pages::add );
            o.add( "top_pages", pages );
            arr.add( o );
        }
        final JsonObject data = new JsonObject();
        data.add( "tags", arr );
        writeEnvelope( resp, data );
    }

    private void writePages( final HttpServletResponse resp, final StructuralIndexService svc,
                              final HttpServletRequest req ) throws IOException {
        final StructuralFilter filter = new StructuralFilter(
                Optional.ofNullable( req.getParameter( "type" ) ).map( PageType::fromFrontmatter ),
                Optional.ofNullable( req.getParameter( "cluster" ) ),
                Arrays.asList( Optional.ofNullable( req.getParameter( "tag" ) )
                        .map( t -> t.split( "," ) ).orElse( new String[ 0 ] ) ),
                Optional.ofNullable( req.getParameter( "updated_since" ) ).map( Instant::parse ),
                parseIntOr( req.getParameter( "limit" ), 100 ),
                Optional.ofNullable( req.getParameter( "cursor" ) )
        );
        final List< PageDescriptor > pages = svc.listPagesByFilter( filter );
        final JsonArray arr = new JsonArray();
        pages.forEach( p -> arr.add( describe( p ) ) );
        final JsonObject data = new JsonObject();
        data.add( "pages", arr );
        data.addProperty( "count", pages.size() );
        writeEnvelope( resp, data );
    }

    private void writeSitemap( final HttpServletResponse resp, final StructuralIndexService svc )
            throws IOException {
        final Sitemap s = svc.sitemap();
        final JsonArray arr = new JsonArray();
        s.pages().forEach( p -> arr.add( describe( p ) ) );
        final JsonObject data = new JsonObject();
        data.add( "pages", arr );
        data.addProperty( "count", s.count() );
        data.addProperty( "generated_at", s.generatedAt().toString() );
        writeEnvelope( resp, data );
    }

    private static JsonObject describe( final PageDescriptor p ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "id",      p.canonicalId() );
        o.addProperty( "slug",    p.slug() );
        o.addProperty( "title",   p.title() );
        o.addProperty( "type",    p.type().asFrontmatterValue() );
        if ( p.cluster() != null ) o.addProperty( "cluster", p.cluster() );
        if ( p.summary() != null ) o.addProperty( "summary", p.summary() );
        if ( p.updated() != null ) o.addProperty( "updated", p.updated().toString() );
        final JsonArray tags = new JsonArray();
        p.tags().forEach( tags::add );
        o.add( "tags", tags );
        return o;
    }

    private void writeEnvelope( final HttpServletResponse resp, final JsonObject data ) throws IOException {
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }

    private void writeError( final HttpServletResponse resp, final int status, final String message )
            throws IOException {
        resp.setStatus( status );
        resp.setContentType( "application/json; charset=UTF-8" );
        final JsonObject err = new JsonObject();
        err.addProperty( "error", message );
        resp.getWriter().write( GSON.toJson( err ) );
    }

    private static int parseIntOr( final String raw, final int fallback ) {
        if ( raw == null || raw.isBlank() ) return fallback;
        try { return Integer.parseInt( raw ); } catch ( final NumberFormatException e ) { return fallback; }
    }
}
```

- [ ] **Step 5: Run test and verify**

Run: `mvn test -pl wikantik-rest -Dtest=StructureResourceTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: BUILD SUCCESS, 4 tests passed.

- [ ] **Step 6: Wire servlet in web.xml**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, below the existing REST servlet declarations (search for `BacklinksResource` as a reference), add:

```xml
<servlet>
    <servlet-name>StructureResource</servlet-name>
    <servlet-class>com.wikantik.rest.StructureResource</servlet-class>
    <load-on-startup>5</load-on-startup>
</servlet>
<servlet-mapping>
    <servlet-name>StructureResource</servlet-name>
    <url-pattern>/api/structure/*</url-pattern>
</servlet-mapping>
```

- [ ] **Step 7: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/StructureResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/StructureResourceTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(rest): StructureResource at /api/structure/* for machine-queryable wiki shape"
```

---

## Task 15: PageByIdResource — `/api/pages/by-id/{canonical_id}`

**Files:**
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/PageByIdResourceTest.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/PageByIdResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.core.Engine;
import com.wikantik.api.structure.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageByIdResourceTest {

    private StructuralIndexService svc;
    private PageByIdResource resource;

    @BeforeEach
    void setUp() {
        svc = mock( StructuralIndexService.class );
        final Engine engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( svc );
        resource = new PageByIdResource();
        resource.setEngineForTesting( engine );
    }

    @Test
    void resolves_id_to_descriptor() throws Exception {
        when( svc.getByCanonicalId( "01A" ) ).thenReturn( Optional.of( new PageDescriptor(
                "01A", "HybridRetrieval", "Hybrid Retrieval", PageType.ARTICLE,
                "wikantik-development", List.of( "retrieval" ), "summary", Instant.EPOCH ) ) );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( "/01A" );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        assertEquals( "HybridRetrieval",
                body.getAsJsonObject( "data" ).get( "slug" ).getAsString() );
    }

    @Test
    void unknown_id_returns_404() throws Exception {
        when( svc.getByCanonicalId( "missing" ) ).thenReturn( Optional.empty() );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getPathInfo() ).thenReturn( "/missing" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 404 );
    }
}
```

- [ ] **Step 2: Run and see it fail.** Same pattern as Task 14. Expected: compile error.

- [ ] **Step 3: Implement PageByIdResource**

```java
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.api.core.Engine;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.StructuralIndexService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * {@code GET /api/pages/by-id/{canonical_id}} — resolves a canonical_id to the
 * page descriptor. Callers that want the full body should follow up with
 * {@code GET /api/pages/{slug}} using the returned {@code slug}.
 */
public class PageByIdResource extends RestServletBase {

    private static final Logger LOG = LogManager.getLogger( PageByIdResource.class );

    private Engine engineOverride;
    void setEngineForTesting( final Engine engine ) { this.engineOverride = engine; }
    private Engine engine() { return engineOverride != null ? engineOverride : getEngine(); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String pathInfo = Optional.ofNullable( req.getPathInfo() ).orElse( "" );
        if ( pathInfo.length() < 2 ) {
            resp.setStatus( 400 );
            resp.getWriter().write( "{\"error\":\"canonical_id required in path\"}" );
            return;
        }
        final String canonicalId = pathInfo.substring( 1 );

        final StructuralIndexService svc = engine().getManager( StructuralIndexService.class );
        if ( svc == null ) {
            resp.setStatus( 503 );
            resp.getWriter().write( "{\"error\":\"structural index unavailable\"}" );
            return;
        }

        final Optional< PageDescriptor > found = svc.getByCanonicalId( canonicalId );
        if ( found.isEmpty() ) {
            resp.setStatus( 404 );
            resp.getWriter().write( "{\"error\":\"no page for canonical_id " + canonicalId + "\"}" );
            return;
        }

        final PageDescriptor p = found.get();
        final JsonObject data = new JsonObject();
        data.addProperty( "id",      p.canonicalId() );
        data.addProperty( "slug",    p.slug() );
        data.addProperty( "title",   p.title() );
        data.addProperty( "type",    p.type().asFrontmatterValue() );
        if ( p.cluster() != null ) data.addProperty( "cluster", p.cluster() );
        if ( p.summary() != null ) data.addProperty( "summary", p.summary() );
        if ( p.updated() != null ) data.addProperty( "updated", p.updated().toString() );
        final JsonArray tags = new JsonArray();
        p.tags().forEach( tags::add );
        data.add( "tags", tags );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }
}
```

- [ ] **Step 4: Run test and verify pass**

- [ ] **Step 5: Wire in web.xml**

Add to `web.xml`:

```xml
<servlet>
    <servlet-name>PageByIdResource</servlet-name>
    <servlet-class>com.wikantik.rest.PageByIdResource</servlet-class>
    <load-on-startup>5</load-on-startup>
</servlet>
<servlet-mapping>
    <servlet-name>PageByIdResource</servlet-name>
    <url-pattern>/api/pages/by-id/*</url-pattern>
</servlet-mapping>
```

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/PageByIdResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/PageByIdResourceTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(rest): PageByIdResource for canonical_id lookups"
```

---

## Task 16: ListClustersTool MCP

**Files:**
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListClustersToolTest.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListClustersTool.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.structure.ClusterSummary;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralIndexService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListClustersToolTest {

    @Test
    void returns_tool_definition_with_expected_name() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        final ListClustersTool tool = new ListClustersTool( svc );
        assertEquals( "list_clusters", tool.name() );
        final McpSchema.Tool def = tool.definition();
        assertNotNull( def );
        assertEquals( "list_clusters", def.name() );
    }

    @Test
    void execute_returns_clusters_as_json_payload() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listClusters() ).thenReturn( List.of( new ClusterSummary(
                "wikantik-development",
                new PageDescriptor( "01A", "WikantikDevelopment", "Wikantik Development",
                        PageType.HUB, "wikantik-development", List.of(), "hub", Instant.EPOCH ),
                12, Instant.EPOCH ) ) );

        final var result = new ListClustersTool( svc ).execute( Map.of() );
        assertFalse( result.isError() );
        final var content = ( McpSchema.TextContent ) result.content().get( 0 );
        assertTrue( content.text().contains( "\"name\":\"wikantik-development\"" )
                 || content.text().contains( "\"name\": \"wikantik-development\"" ) );
    }
}
```

- [ ] **Step 2: Run, see it fail.** Expected: compile error.

- [ ] **Step 3: Implement ListClustersTool**

```java
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.wikantik.api.structure.ClusterSummary;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

/** MCP tool — returns every cluster with its hub page, article count, and freshness. */
public class ListClustersTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListClustersTool.class );
    public static final String TOOL_NAME = "list_clusters";
    private static final Gson GSON = new Gson();

    private final StructuralIndexService service;

    public ListClustersTool( final StructuralIndexService service ) {
        this.service = service;
    }

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List every cluster in the wiki with its hub page, article count, " +
                        "and most-recent update time. Call this first when an agent needs a map of " +
                        "topic areas before drilling into a specific cluster." )
                .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final List< ClusterSummary > clusters = service.listClusters();
            return McpToolUtils.jsonResult( GSON, Map.of( "clusters", clusters, "count", clusters.size() ) );
        } catch ( final Exception e ) {
            LOG.error( "list_clusters failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( GSON, e.getMessage() );
        }
    }
}
```

- [ ] **Step 4: Run test and verify pass**

Run: `mvn test -pl wikantik-knowledge -Dtest=ListClustersToolTest -am -Dsurefire.failIfNoSpecifiedTests=false -q`

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListClustersTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListClustersToolTest.java
git commit -m "feat(knowledge-mcp): list_clusters tool"
```

---

## Task 17: ListTagsTool MCP

**Files:**
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListTagsToolTest.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListTagsTool.java`

Same TDD shape as Task 16.

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TagSummary;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListTagsToolTest {

    @Test
    void min_pages_defaults_to_1() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listTags( 1 ) ).thenReturn( List.of( new TagSummary( "retrieval", 5, List.of() ) ) );
        final ListTagsTool tool = new ListTagsTool( svc );
        final var result = tool.execute( Map.of() );
        assertFalse( result.isError() );
        final var content = ( McpSchema.TextContent ) result.content().get( 0 );
        assertTrue( content.text().contains( "retrieval" ) );
        verify( svc ).listTags( 1 );
    }

    @Test
    void respects_min_pages_argument() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listTags( 5 ) ).thenReturn( List.of() );
        new ListTagsTool( svc ).execute( Map.of( "min_pages", 5 ) );
        verify( svc ).listTags( 5 );
    }
}
```

- [ ] **Step 2: Run, see fail.**

- [ ] **Step 3: Implement ListTagsTool**

```java
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TagSummary;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ListTagsTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListTagsTool.class );
    public static final String TOOL_NAME = "list_tags";
    private static final Gson GSON = new Gson();

    private final StructuralIndexService service;
    public ListTagsTool( final StructuralIndexService service ) { this.service = service; }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "min_pages", Map.of(
                "type", "integer",
                "description", "Only return tags used by at least this many pages (default 1)."
        ) );
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Return the tag dictionary for the wiki — one entry per tag, with the " +
                        "page count and a sample of canonical IDs using that tag. Useful when an agent " +
                        "wants to discover what topics the wiki covers." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final int minPages = arguments.get( "min_pages" ) instanceof Number n
                    ? Math.max( 1, n.intValue() )
                    : 1;
            final List< TagSummary > tags = service.listTags( minPages );
            return McpToolUtils.jsonResult( GSON, Map.of( "tags", tags, "count", tags.size() ) );
        } catch ( final Exception e ) {
            LOG.error( "list_tags failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( GSON, e.getMessage() );
        }
    }
}
```

- [ ] **Step 4: Run test and verify**

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListTagsTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListTagsToolTest.java
git commit -m "feat(knowledge-mcp): list_tags tool"
```

---

## Task 18: ListPagesByFilterTool MCP

**Files:**
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListPagesByFilterToolTest.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListPagesByFilterTool.java`

- [ ] **Step 1: Failing test**

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.structure.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListPagesByFilterToolTest {

    @Test
    void forwards_filter_arguments_to_service() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of() );
        final ArgumentCaptor< StructuralFilter > cap = ArgumentCaptor.forClass( StructuralFilter.class );

        new ListPagesByFilterTool( svc ).execute( Map.of(
                "type", "article",
                "cluster", "wikantik-development",
                "tag", List.of( "retrieval" ),
                "limit", 5 ) );

        verify( svc ).listPagesByFilter( cap.capture() );
        final StructuralFilter f = cap.getValue();
        assertEquals( PageType.ARTICLE,       f.type().orElseThrow() );
        assertEquals( "wikantik-development", f.cluster().orElseThrow() );
        assertEquals( List.of( "retrieval" ),  f.tags() );
        assertEquals( 5,                       f.limit() );
    }

    @Test
    void returns_pages_as_json_payload() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of(
                new PageDescriptor( "01A", "X", "X", PageType.ARTICLE, null, List.of(),
                        "summary", Instant.EPOCH ) ) );

        final var result = new ListPagesByFilterTool( svc ).execute( Map.of() );
        assertFalse( result.isError() );
        final var content = ( McpSchema.TextContent ) result.content().get( 0 );
        assertTrue( content.text().contains( "\"slug\":\"X\"" )
                 || content.text().contains( "\"slug\": \"X\"" ) );
    }
}
```

- [ ] **Step 2: Run, see fail.**

- [ ] **Step 3: Implement ListPagesByFilterTool**

```java
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.wikantik.api.structure.*;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.*;

public class ListPagesByFilterTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListPagesByFilterTool.class );
    public static final String TOOL_NAME = "list_pages_by_filter";
    private static final Gson GSON = new Gson();

    private final StructuralIndexService service;
    public ListPagesByFilterTool( final StructuralIndexService service ) { this.service = service; }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "type",           Map.of( "type", "string",
                "description", "One of hub, article, reference, runbook, design." ) );
        props.put( "cluster",        Map.of( "type", "string",
                "description", "Cluster name; limits results to pages with that cluster frontmatter." ) );
        props.put( "tag",            Map.of( "type", "array", "items", Map.of( "type", "string" ),
                "description", "All listed tags must appear on the page (AND semantics)." ) );
        props.put( "updated_since",  Map.of( "type", "string",
                "description", "ISO-8601 timestamp; include pages modified on or after this instant." ) );
        props.put( "limit",          Map.of( "type", "integer",
                "description", "Maximum number of pages (default 100, max 1000)." ) );
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List wiki pages matching a structural filter (type, cluster, tags, freshness). " +
                        "Returns {pages: [PageDescriptor], count}. Prefer this over search_knowledge for " +
                        "structural queries — it reads the canonical page index directly and does not depend " +
                        "on the retrieval pipeline." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final StructuralFilter filter = new StructuralFilter(
                    Optional.ofNullable( (String) arguments.get( "type" ) ).map( PageType::fromFrontmatter ),
                    Optional.ofNullable( (String) arguments.get( "cluster" ) ),
                    coerceStringList( arguments.get( "tag" ) ),
                    Optional.ofNullable( (String) arguments.get( "updated_since" ) ).map( Instant::parse ),
                    arguments.get( "limit" ) instanceof Number n ? n.intValue() : 100,
                    Optional.ofNullable( (String) arguments.get( "cursor" ) ) );
            final List< PageDescriptor > pages = service.listPagesByFilter( filter );
            return McpToolUtils.jsonResult( GSON, Map.of( "pages", pages, "count", pages.size() ) );
        } catch ( final Exception e ) {
            LOG.error( "list_pages_by_filter failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( GSON, e.getMessage() );
        }
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > coerceStringList( final Object o ) {
        if ( o == null ) return List.of();
        if ( o instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object x : list ) if ( x != null ) out.add( x.toString() );
            return out;
        }
        return List.of( o.toString() );
    }
}
```

- [ ] **Step 4: Run test and verify**

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListPagesByFilterTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListPagesByFilterToolTest.java
git commit -m "feat(knowledge-mcp): list_pages_by_filter tool"
```

---

## Task 19: GetPageByIdTool MCP

**Files:**
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetPageByIdToolTest.java`
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetPageByIdTool.java`

- [ ] **Step 1: Failing test**

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.structure.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetPageByIdToolTest {

    @Test
    void returns_descriptor_for_known_id() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "01A" ) ).thenReturn( Optional.of(
                new PageDescriptor( "01A", "X", "X", PageType.ARTICLE, null, List.of(), "s", Instant.EPOCH ) ) );
        final var result = new GetPageByIdTool( svc ).execute( Map.of( "canonical_id", "01A" ) );
        assertFalse( result.isError() );
    }

    @Test
    void returns_error_for_missing_id() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "missing" ) ).thenReturn( Optional.empty() );
        final var result = new GetPageByIdTool( svc ).execute( Map.of( "canonical_id", "missing" ) );
        assertTrue( result.isError() );
    }

    @Test
    void requires_canonical_id_argument() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        final var result = new GetPageByIdTool( svc ).execute( Map.of() );
        assertTrue( result.isError() );
    }
}
```

- [ ] **Step 2: Run, fail.**

- [ ] **Step 3: Implement GetPageByIdTool**

```java
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class GetPageByIdTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( GetPageByIdTool.class );
    public static final String TOOL_NAME = "get_page_by_id";
    private static final Gson GSON = new Gson();

    private final StructuralIndexService service;
    public GetPageByIdTool( final StructuralIndexService service ) { this.service = service; }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "canonical_id", Map.of(
                "type", "string",
                "description", "26-character ULID canonical identifier for the page."
        ) );
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Resolve a canonical_id to the current page descriptor. Returns " +
                        "{id, slug, title, type, cluster, tags, summary, updated}. Prefer this over " +
                        "get_page when citing sources — the canonical_id is stable across renames." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of( "canonical_id" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String canonicalId = (String) arguments.get( "canonical_id" );
            if ( canonicalId == null || canonicalId.isBlank() ) {
                return McpToolUtils.errorResult( GSON, "canonical_id argument is required" );
            }
            final Optional< PageDescriptor > found = service.getByCanonicalId( canonicalId );
            if ( found.isEmpty() ) {
                return McpToolUtils.errorResult( GSON, "no page for canonical_id " + canonicalId );
            }
            return McpToolUtils.jsonResult( GSON, found.get() );
        } catch ( final Exception e ) {
            LOG.error( "get_page_by_id failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( GSON, e.getMessage() );
        }
    }
}
```

- [ ] **Step 4: Run test and verify**

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetPageByIdTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetPageByIdToolTest.java
git commit -m "feat(knowledge-mcp): get_page_by_id tool"
```

---

## Task 20: Register new MCP tools in KnowledgeMcpInitializer

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`

- [ ] **Step 1: Add imports for new tools and StructuralIndexService**

At the top of `KnowledgeMcpInitializer.java`, add:

```java
import com.wikantik.api.structure.StructuralIndexService;
```

- [ ] **Step 2: Resolve StructuralIndexService in contextInitialized**

In `contextInitialized`, immediately after the lines that fetch `kgService` and `ctxService`:

```java
final StructuralIndexService structuralIndex = engine.getManager( StructuralIndexService.class );
```

- [ ] **Step 3: Register four new tools in the "Phase 3: assemble the tool list" block**

Inside the `try { ... }` that builds `tools`, after the `ctxService` block, add:

```java
if ( structuralIndex != null ) {
    tools.add( new ListClustersTool( structuralIndex ) );
    tools.add( new ListTagsTool( structuralIndex ) );
    tools.add( new ListPagesByFilterTool( structuralIndex ) );
    tools.add( new GetPageByIdTool( structuralIndex ) );
}
```

- [ ] **Step 4: Update the "NOT started" guard**

At the top of `contextInitialized`, the existing guard reads `if ( kgService == null && ctxService == null )`. Expand to include the structural service:

```java
if ( kgService == null && ctxService == null && structuralIndex == null ) {
    LOG.info( "Neither KnowledgeGraphService, ContextRetrievalService, nor StructuralIndexService " +
              "configured — Knowledge MCP server not started" );
    return;
}
```

- [ ] **Step 5: Update the instructions text**

Change the `.instructions(...)` argument to:

```java
.instructions( "Agent-facing MCP endpoint. For wiki structure use " +
        "list_clusters, list_tags, list_pages_by_filter, or get_page_by_id. " +
        "For wiki content use retrieve_context (primary RAG), get_page (pinned fetch), " +
        "list_pages (browse), or list_metadata_values (discovery). " +
        "For knowledge graph structure use discover_schema, query_nodes, " +
        "get_node, traverse, search_knowledge, or find_similar." )
```

- [ ] **Step 6: Update the completion log line**

Replace:
```java
LOG.info( "Knowledge MCP server started successfully with {} tools at /knowledge-mcp", tools.size() );
```
with the same text — no change needed, `tools.size()` will be 14 once all services are configured.

- [ ] **Step 7: Compile-check**

Run: `mvn compile -pl wikantik-knowledge -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java
git commit -m "feat(knowledge-mcp): register list_clusters, list_tags, list_pages_by_filter, get_page_by_id"
```

---

## Task 21: Prometheus metrics for the structural index

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/StructuralIndexMetrics.java`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/DefaultStructuralIndexService.java`

- [ ] **Step 1: Write StructuralIndexMetrics**

```java
package com.wikantik.knowledge.structure;

import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.api.structure.IndexHealth;
import com.wikantik.api.structure.StructuralIndexService.StructuralProjectionSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes Prometheus metrics describing the structural index:
 * <ul>
 *   <li>{@code wikantik_structural_index_pages_total}</li>
 *   <li>{@code wikantik_structural_index_clusters_total}</li>
 *   <li>{@code wikantik_structural_index_tags_total}</li>
 *   <li>{@code wikantik_structural_index_unclaimed_total}</li>
 *   <li>{@code wikantik_structural_index_lag_seconds}</li>
 *   <li>{@code wikantik_structural_index_rebuild_duration_seconds} (timer)</li>
 * </ul>
 */
public class StructuralIndexMetrics {

    private static final Logger LOG = LogManager.getLogger( StructuralIndexMetrics.class );

    private final AtomicLong pages     = new AtomicLong( 0 );
    private final AtomicLong clusters  = new AtomicLong( 0 );
    private final AtomicLong tags      = new AtomicLong( 0 );
    private final AtomicLong unclaimed = new AtomicLong( 0 );
    private final AtomicLong lagSeconds = new AtomicLong( 0 );

    private Timer rebuildTimer;

    public void bind( final MeterRegistry registry ) {
        if ( registry == null ) return;
        Gauge.builder( "wikantik_structural_index_pages_total",     pages,     AtomicLong::get ).register( registry );
        Gauge.builder( "wikantik_structural_index_clusters_total",  clusters,  AtomicLong::get ).register( registry );
        Gauge.builder( "wikantik_structural_index_tags_total",      tags,      AtomicLong::get ).register( registry );
        Gauge.builder( "wikantik_structural_index_unclaimed_total", unclaimed, AtomicLong::get ).register( registry );
        Gauge.builder( "wikantik_structural_index_lag_seconds",     lagSeconds, AtomicLong::get ).register( registry );
        this.rebuildTimer = Timer.builder( "wikantik_structural_index_rebuild_duration_seconds" )
                .register( registry );
    }

    public void update( final StructuralProjectionSnapshot snapshot, final IndexHealth health ) {
        pages.set( snapshot.pageCount() );
        clusters.set( snapshot.clusterCount() );
        tags.set( snapshot.tagCount() );
        unclaimed.set( health.unclaimedCanonicalIds() );
        lagSeconds.set( health.lagSeconds() );
    }

    public void recordRebuildMillis( final long ms ) {
        if ( rebuildTimer != null ) {
            rebuildTimer.record( java.time.Duration.ofMillis( ms ) );
        }
    }

    public static StructuralIndexMetrics resolveAndBind() {
        final StructuralIndexMetrics m = new StructuralIndexMetrics();
        final MeterRegistry registry = MeterRegistryHolder.get();
        if ( registry == null ) {
            LOG.warn( "No shared MeterRegistry — structural-index metrics will NOT be scraped." );
        } else {
            m.bind( registry );
        }
        return m;
    }
}
```

- [ ] **Step 2: Wire metrics into DefaultStructuralIndexService**

Add a field and constructor parameter:

```java
private final StructuralIndexMetrics metrics;

public DefaultStructuralIndexService( final PageManager pageManager,
                                      final PageCanonicalIdsDao dao,
                                      final StructuralIndexMetrics metrics ) {
    this.pageManager = pageManager;
    this.dao = dao;
    this.metrics = metrics;
}

// Keep the two-arg constructor as a convenience for tests:
public DefaultStructuralIndexService( final PageManager pageManager, final PageCanonicalIdsDao dao ) {
    this( pageManager, dao, new StructuralIndexMetrics() );   // no-op metrics when registry is absent
}
```

At the end of `rebuild()` (after `this.health = ...`), add:

```java
metrics.update( snapshot(), health );
metrics.recordRebuildMillis( finish.toEpochMilli() - start.toEpochMilli() );
```

- [ ] **Step 3: Update WikiEngine to pass bound metrics**

In `WikiEngine.initKnowledgeGraph()`, replace the earlier instantiation with:

```java
final StructuralIndexMetrics structuralMetrics = StructuralIndexMetrics.resolveAndBind();
final DefaultStructuralIndexService structuralIndex =
        new DefaultStructuralIndexService( getManager( PageManager.class ), canonicalIdsDao, structuralMetrics );
```

- [ ] **Step 4: Run all knowledge tests**

Run: `mvn test -pl wikantik-knowledge -am -q`
Expected: BUILD SUCCESS, all previously-green tests still pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/StructuralIndexMetrics.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/structure/DefaultStructuralIndexService.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat(observability): Prometheus metrics for the structural index"
```

---

## Task 22: Health endpoint `/api/health/structural-index`

**Files:**
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/StructuralIndexHealthResourceTest.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/StructuralIndexHealthResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Failing test**

```java
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.core.Engine;
import com.wikantik.api.structure.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StructuralIndexHealthResourceTest {

    @Test
    void reports_up_status_when_service_healthy() throws Exception {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.health() ).thenReturn( new IndexHealth(
                IndexHealth.Status.UP, 1024, 0, Instant.EPOCH, Instant.EPOCH, 500L, 3L ) );
        when( svc.snapshot() ).thenReturn( new StructuralIndexService.StructuralProjectionSnapshot() {
            @Override public int pageCount()       { return 1024; }
            @Override public int clusterCount()    { return 24; }
            @Override public int tagCount()        { return 185; }
            @Override public Instant generatedAt() { return Instant.EPOCH; }
        } );
        final Engine engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( svc );

        final StructuralIndexHealthResource r = new StructuralIndexHealthResource();
        r.setEngineForTesting( engine );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        r.doGet( req, resp );

        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        assertEquals( "UP", body.get( "status" ).getAsString() );
        assertEquals( 1024, body.get( "pages" ).getAsInt() );
        assertEquals( 0,    body.get( "unclaimed_canonical_ids" ).getAsInt() );
    }

    @Test
    void reports_503_when_service_absent() throws Exception {
        final Engine engine = mock( Engine.class );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( null );
        final StructuralIndexHealthResource r = new StructuralIndexHealthResource();
        r.setEngineForTesting( engine );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        r.doGet( req, resp );

        verify( resp ).setStatus( 503 );
    }
}
```

- [ ] **Step 2: Run, fail.**

- [ ] **Step 3: Implement StructuralIndexHealthResource**

```java
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.wikantik.api.core.Engine;
import com.wikantik.api.structure.IndexHealth;
import com.wikantik.api.structure.StructuralIndexService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/** {@code GET /api/health/structural-index} — pointable at Prometheus/k8s liveness. */
public class StructuralIndexHealthResource extends RestServletBase {

    private static final Logger LOG = LogManager.getLogger( StructuralIndexHealthResource.class );

    private Engine engineOverride;
    void setEngineForTesting( final Engine engine ) { this.engineOverride = engine; }
    private Engine engine() { return engineOverride != null ? engineOverride : getEngine(); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final StructuralIndexService svc = engine().getManager( StructuralIndexService.class );
        if ( svc == null ) {
            resp.setStatus( 503 );
            resp.getWriter().write( "{\"status\":\"DOWN\",\"error\":\"service not registered\"}" );
            return;
        }
        final IndexHealth h = svc.health();
        final var snap = svc.snapshot();
        final JsonObject body = new JsonObject();
        body.addProperty( "status", h.status().name() );
        body.addProperty( "pages", h.pages() );
        body.addProperty( "clusters", snap.clusterCount() );
        body.addProperty( "tags", snap.tagCount() );
        body.addProperty( "unclaimed_canonical_ids", h.unclaimedCanonicalIds() );
        body.addProperty( "lag_seconds", h.lagSeconds() );
        body.addProperty( "last_rebuild_duration_ms", h.lastRebuildDurationMillis() );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( h.status() == IndexHealth.Status.UP ? 200 : 503 );
        resp.getWriter().write( GSON.toJson( body ) );
    }
}
```

- [ ] **Step 4: Run test and verify**

- [ ] **Step 5: Wire in web.xml**

```xml
<servlet>
    <servlet-name>StructuralIndexHealthResource</servlet-name>
    <servlet-class>com.wikantik.rest.StructuralIndexHealthResource</servlet-class>
    <load-on-startup>5</load-on-startup>
</servlet>
<servlet-mapping>
    <servlet-name>StructuralIndexHealthResource</servlet-name>
    <url-pattern>/api/health/structural-index</url-pattern>
</servlet-mapping>
```

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/StructuralIndexHealthResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/StructuralIndexHealthResourceTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(rest): /api/health/structural-index endpoint"
```

---

## Task 23: End-to-end integration test

**Files:**
- Create: `wikantik-it-tests/src/test/java/com/wikantik/it/structure/StructuralSpineIT.java`

This test exercises the full path: Cargo starts Tomcat, the WAR deploys, the structural index rebuilds, REST endpoints serve, MCP tools respond.

- [ ] **Step 1: Write the IT**

```java
package com.wikantik.it.structure;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: Cargo-deployed Tomcat exposes /api/structure/* and serves a
 * non-empty cluster list after the initial rebuild completes.
 *
 * <p>Depends on the Cargo-managed Tomcat + PostgreSQL + pgvector fixture
 * already used by other wikantik-it-tests. Uses the testbot admin account
 * credentials from test.properties for any auth-gated endpoints.</p>
 */
class StructuralSpineIT {

    private static final String BASE_URL = System.getProperty( "wikantik.baseUrl", "http://localhost:8080" );

    @Test
    void clusters_endpoint_returns_at_least_one_cluster() throws Exception {
        final HttpClient http = HttpClient.newHttpClient();
        // Allow up to 30s for the background bootstrap rebuild to finish on first hit.
        for ( int attempt = 0; attempt < 30; attempt++ ) {
            final HttpResponse< String > resp = http.send(
                    HttpRequest.newBuilder( URI.create( BASE_URL + "/api/structure/clusters" ) ).GET().build(),
                    HttpResponse.BodyHandlers.ofString() );
            if ( resp.statusCode() == 200 && resp.body().contains( "\"clusters\"" )
                    && resp.body().contains( "\"name\"" ) ) {
                assertTrue( resp.body().length() > 50 );
                return;
            }
            Thread.sleep( 1000 );
        }
        fail( "Structural index did not come up with a non-empty cluster list within 30s" );
    }

    @Test
    void sitemap_endpoint_returns_many_pages() throws Exception {
        final HttpClient http = HttpClient.newHttpClient();
        final HttpResponse< String > resp = http.send(
                HttpRequest.newBuilder( URI.create( BASE_URL + "/api/structure/sitemap" ) ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, resp.statusCode() );
        assertTrue( resp.body().contains( "\"count\":" ) );
    }

    @Test
    void health_endpoint_reports_up() throws Exception {
        final HttpClient http = HttpClient.newHttpClient();
        for ( int attempt = 0; attempt < 30; attempt++ ) {
            final HttpResponse< String > resp = http.send(
                    HttpRequest.newBuilder( URI.create( BASE_URL + "/api/health/structural-index" ) )
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString() );
            if ( resp.statusCode() == 200 && resp.body().contains( "\"status\":\"UP\"" ) ) {
                return;
            }
            Thread.sleep( 1000 );
        }
        fail( "Structural index never reached UP status" );
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn test-compile -pl wikantik-it-tests -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run IT suite**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: all IT tests pass, including `StructuralSpineIT` (three methods green).

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/src/test/java/com/wikantik/it/structure/StructuralSpineIT.java
git commit -m "test(it): end-to-end StructuralSpineIT — REST endpoints + health"
```

---

## Task 24: Full verification build

- [ ] **Step 1: Unit tests**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS, zero test failures.

- [ ] **Step 2: Integration tests**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS, zero IT failures.

- [ ] **Step 3: Deploy locally and smoke-test by hand**

```bash
tomcat/tomcat-11/bin/shutdown.sh || true
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
sleep 15

# Source credentials
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')

# REST smoke
curl -s http://localhost:8080/api/structure/clusters | head -c 500
curl -s http://localhost:8080/api/structure/tags?min_pages=5 | head -c 500
curl -s http://localhost:8080/api/structure/sitemap | head -c 500
curl -s http://localhost:8080/api/health/structural-index

# Pick a canonical_id from the cluster response and verify by-id lookup:
ID=$(curl -s http://localhost:8080/api/structure/sitemap | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["data"]["pages"][0]["id"])')
curl -s "http://localhost:8080/api/pages/by-id/$ID"

# MCP smoke (manual — use your preferred MCP client against /knowledge-mcp)
# Example (requires an MCP client configured with the endpoint + access key):
#   list_clusters           — expect non-empty
#   list_tags               — expect non-empty
#   list_pages_by_filter    — with type=hub expect hub pages only
#   get_page_by_id          — with the ID above expect 200
```
Expected: all REST endpoints return HTTP 200 with plausible payloads; `UP` status on the health check.

- [ ] **Step 4: Final commit (if any smoke-test fixes needed)**

If all five smoke calls pass, nothing more to commit. If any failed, fix and commit per Task 14/22 pattern.

---

## Self-review checklist

Before declaring Phase 1 complete, confirm:

- [ ] `git log --oneline` from the start of this plan shows ~24 focused commits (one per task), not a handful of large dumps.
- [ ] Every file under `docs/wikantik-pages/*.md` has `canonical_id:` in frontmatter (`grep -L 'canonical_id:' docs/wikantik-pages/*.md` returns empty).
- [ ] `wikantik_structural_index_pages_total` Prometheus gauge reports the wiki page count on the running Tomcat.
- [ ] A cold client invoking `list_clusters` against `/knowledge-mcp` receives the cluster list on the first call with no retry logic.
- [ ] No existing retrieval MCP tools (`search_knowledge`, `retrieve_context`, …) regressed — they still appear in the startup log alongside the four new tools.
- [ ] `mvn clean install -Pintegration-tests -fae` green.

---

## After Phase 1 — follow-on plans

These phases each warrant their own plan document in `docs/superpowers/plans/`, written after Phase 1 lands so the plan can be informed by what we learn:

### Phase 2 — Typed relations (~1 sprint)

Adds the `relations:` frontmatter vocabulary (seven types: part-of, example-of, prerequisite-for, supersedes, contradicts, implements, derived-from). Populates `page_relations` table from frontmatter on save. New MCP tool `traverse_relations` with depth-bounded BFS. New REST endpoint `/api/pages/{id}/relations`. Author-facing validator that rejects unknown relation types and unresolvable targets. CLI tool to propose `part-of` / `example-of` relations from existing Markdown links. `ProposeRelationsTool` in `/wikantik-admin-mcp` for agent-driven proposals.

### Phase 3 — Generated `Main.md` (~0.5 sprint)

Mustache template (or equivalent) + generator invoked from `wikantik-wikipages-builder`'s `package` phase. Pre-commit guard that rejects manual edits to `Main.md`. Pins sidecar file (`docs/wikantik-pages/Main.pins.yaml`) for curator-chosen featured pages. Regression IT that regenerates and diffs to catch drift.

### Phase 4 — Enforcement (~0.25 sprint)

Frontmatter validator promoted from observe-only to mandatory:

- Reject `PAGE_SAVE` without `canonical_id`.
- Reject `PAGE_SAVE` whose `relations.target` values don't resolve to a live `canonical_id`.
- Admin UI page at `/admin/structural-conflicts` listing any rows left over from Phase 1's observe-only regime.
- Flip `DefaultStructuralIndexService` to require canonical_ids (previous Phase-1 code path that synthesised them in memory removed).

When ready to plan Phase 2, invoke `superpowers:writing-plans` with the Phase 2 scope from `docs/wikantik-pages/StructuralSpineDesign.md` § Migration path.
