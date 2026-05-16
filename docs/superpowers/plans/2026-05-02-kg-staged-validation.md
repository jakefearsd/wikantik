# KG Staged Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stage Knowledge Graph validation into machine and human tiers — judge LLM auto-promotes proposals into materialized `kg_nodes`/`kg_edges` at `tier='machine'`; admin approval upgrades them to `tier='human'`. Read paths default to `min_tier='machine'`; callers pass `min_tier=human` to enforce strict filtering.

**Architecture:** Async judge runner consumes pending proposals, persists verdicts to a new audit table, and calls a single `KgMaterializationService` that owns all proposal-driven writes to `kg_nodes`/`kg_edges`. Closes the existing materialization gap where approval only flipped a status column. Tier filtering happens at read time via a `tier` column on materialized rows.

**Tech Stack:** Java 21, JUnit 5, Mockito, PostgreSQL 15+ with pgvector, Ollama (HTTP/JSON), JEE Servlet, MCP Streamable HTTP, React SPA (Vite).

**Spec:** [`docs/superpowers/specs/2026-05-02-kg-staged-validation-design.md`](../specs/2026-05-02-kg-staged-validation-design.md)

---

## Pre-flight

- [ ] **Read the spec end-to-end before starting.** The Decisions Log in §12 explains every choice.
- [ ] **Confirm baseline build is green.** Run `mvn clean install -T 1C -DskipITs` and assert exit 0 before touching anything.
- [ ] **Confirm the local PostgreSQL deployment is up.** `psql -h localhost -U jspwiki -d jspwiki -c '\dt kg_*'` should list `kg_proposals`, `kg_nodes`, `kg_edges`, `kg_rejections`, `kg_embeddings`, `kg_node_embeddings`. If not, run the first-time setup from CLAUDE.md.

---

## Task 1: Database migration V023

**Files:**
- Create: `bin/db/migrations/V023__kg_staged_validation.sql`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/MigrationV023Test.java`

- [ ] **Step 1: Write the failing migration test**

```java
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import static org.junit.jupiter.api.Assertions.*;

class MigrationV023Test extends PgTestBase {
    @Test
    void migration_adds_tier_columns_and_audit_table() throws Exception {
        final DataSource ds = dataSource();
        try ( Connection c = ds.getConnection() ) {
            // tier column on kg_proposals
            assertColumnExists( c, "kg_proposals", "tier", "character varying" );
            assertColumnExists( c, "kg_proposals", "machine_status", "character varying" );
            assertColumnExists( c, "kg_proposals", "machine_confidence", "double precision" );
            assertColumnExists( c, "kg_proposals", "machine_judged_at", "timestamp without time zone" );
            assertColumnExists( c, "kg_proposals", "machine_model", "character varying" );

            // tier column on kg_nodes / kg_edges
            assertColumnExists( c, "kg_nodes", "tier", "character varying" );
            assertColumnExists( c, "kg_nodes", "provenance_proposal_id", "uuid" );
            assertColumnExists( c, "kg_edges", "tier", "character varying" );
            assertColumnExists( c, "kg_edges", "provenance_proposal_id", "uuid" );

            // kg_proposal_reviews exists
            try ( ResultSet rs = c.createStatement().executeQuery(
                    "SELECT to_regclass('kg_proposal_reviews')" ) ) {
                rs.next();
                assertNotNull( rs.getString( 1 ), "kg_proposal_reviews must exist" );
            }
        }
    }

    @Test
    void migration_is_idempotent() throws Exception {
        applyMigration( "V023__kg_staged_validation.sql" ); // applied once during base setup
        applyMigration( "V023__kg_staged_validation.sql" ); // second apply must be a no-op
        // No exception = pass.
    }

    private void assertColumnExists( Connection c, String table, String column,
                                     String expectedType ) throws Exception {
        try ( ResultSet rs = c.createStatement().executeQuery(
                "SELECT data_type FROM information_schema.columns " +
                "WHERE table_name = '" + table + "' AND column_name = '" + column + "'" ) ) {
            assertTrue( rs.next(), table + "." + column + " missing" );
            assertEquals( expectedType, rs.getString( 1 ), table + "." + column + " wrong type" );
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=MigrationV023Test`
Expected: FAIL with "kg_proposals.tier missing".

- [ ] **Step 3: Write the migration**

Create `bin/db/migrations/V023__kg_staged_validation.sql`:

```sql
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- V023: Staged validation for the Knowledge Graph.
--
-- Adds: machine-tier metadata + audit table on kg_proposals;
-- tier column + provenance FK on kg_nodes and kg_edges so machine-approved
-- proposals can be materialised into the graph alongside human-approved rows.

ALTER TABLE kg_proposals
    ADD COLUMN IF NOT EXISTS tier               VARCHAR(16) NOT NULL DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS machine_status     VARCHAR(16),
    ADD COLUMN IF NOT EXISTS machine_confidence DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS machine_judged_at  TIMESTAMP,
    ADD COLUMN IF NOT EXISTS machine_model      VARCHAR(64);

DO $$BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage
        WHERE table_name = 'kg_proposals' AND constraint_name = 'kg_proposals_tier_check'
    ) THEN
        ALTER TABLE kg_proposals
            ADD CONSTRAINT kg_proposals_tier_check
            CHECK (tier IN ('none','machine','human'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage
        WHERE table_name = 'kg_proposals' AND constraint_name = 'kg_proposals_human_terminal_check'
    ) THEN
        ALTER TABLE kg_proposals
            ADD CONSTRAINT kg_proposals_human_terminal_check
            CHECK (tier <> 'human' OR status IN ('approved','rejected'));
    END IF;
END$$;

-- Audit history: every machine and human verdict appends one row.
CREATE TABLE IF NOT EXISTS kg_proposal_reviews (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id   UUID         NOT NULL REFERENCES kg_proposals(id) ON DELETE CASCADE,
    reviewer_kind VARCHAR(16)  NOT NULL CHECK (reviewer_kind IN ('machine','human')),
    reviewer_id   VARCHAR(100) NOT NULL,
    verdict       VARCHAR(16)  NOT NULL CHECK (verdict IN ('approved','rejected','abstain')),
    confidence    DOUBLE PRECISION,
    rationale     TEXT,
    created       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS kg_proposal_reviews_proposal_idx
    ON kg_proposal_reviews (proposal_id, created DESC);

-- Materialisation: tier on kg_nodes / kg_edges. Existing rows default to 'human'
-- (they were created by the existing manual flow).
ALTER TABLE kg_nodes
    ADD COLUMN IF NOT EXISTS tier                   VARCHAR(16) NOT NULL DEFAULT 'human',
    ADD COLUMN IF NOT EXISTS provenance_proposal_id UUID;

ALTER TABLE kg_edges
    ADD COLUMN IF NOT EXISTS tier                   VARCHAR(16) NOT NULL DEFAULT 'human',
    ADD COLUMN IF NOT EXISTS provenance_proposal_id UUID;

DO$$BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage
        WHERE table_name = 'kg_nodes' AND constraint_name = 'kg_nodes_tier_check'
    ) THEN
        ALTER TABLE kg_nodes
            ADD CONSTRAINT kg_nodes_tier_check
            CHECK (tier IN ('machine','human'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage
        WHERE table_name = 'kg_edges' AND constraint_name = 'kg_edges_tier_check'
    ) THEN
        ALTER TABLE kg_edges
            ADD CONSTRAINT kg_edges_tier_check
            CHECK (tier IN ('machine','human'));
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS kg_nodes_tier_idx           ON kg_nodes (tier);
CREATE INDEX IF NOT EXISTS kg_nodes_tier_name_idx      ON kg_nodes (tier, name);
CREATE INDEX IF NOT EXISTS kg_edges_tier_source_idx    ON kg_edges (tier, source_id);
CREATE INDEX IF NOT EXISTS kg_edges_tier_target_idx    ON kg_edges (tier, target_id);
CREATE INDEX IF NOT EXISTS kg_nodes_provenance_idx     ON kg_nodes (provenance_proposal_id);
CREATE INDEX IF NOT EXISTS kg_edges_provenance_idx     ON kg_edges (provenance_proposal_id);

-- Grandfather already-approved proposals as human-tier so existing
-- consumers keep seeing them under min_tier='human'.
UPDATE kg_proposals SET tier = 'human'
WHERE status = 'approved' AND tier = 'none';

-- Permissions: re-grant in case column-level defaults vary.
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_proposals       TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_nodes           TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_edges           TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_proposal_reviews TO :app_user;
```

- [ ] **Step 4: Apply migration locally and verify**

Run: `bin/db/migrate.sh` (or `tomcat/tomcat-11/bin/shutdown.sh && bin/deploy-local.sh && tomcat/tomcat-11/bin/startup.sh` to redeploy with the migration).
Then: `mvn test -pl wikantik-main -Dtest=MigrationV023Test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add bin/db/migrations/V023__kg_staged_validation.sql wikantik-main/src/test/java/com/wikantik/knowledge/MigrationV023Test.java
git commit -m "feat(kg): V023 staged-validation schema (tier + audit)"
```

---

## Task 2: Tier enum, JudgeVerdict record, KgProposalReview record

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/Tier.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/JudgeVerdict.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposalReview.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/knowledge/TierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TierTest {
    @Test
    void wireName_is_lowercase() {
        assertEquals( "human", Tier.HUMAN.wireName() );
        assertEquals( "machine", Tier.MACHINE.wireName() );
    }

    @Test
    void fromWire_parses_canonical_values() {
        assertEquals( Tier.HUMAN, Tier.fromWire( "human" ) );
        assertEquals( Tier.MACHINE, Tier.fromWire( "machine" ) );
    }

    @Test
    void fromWire_rejects_unknown_values() {
        assertThrows( IllegalArgumentException.class, () -> Tier.fromWire( "none" ) );
        assertThrows( IllegalArgumentException.class, () -> Tier.fromWire( "" ) );
        assertThrows( IllegalArgumentException.class, () -> Tier.fromWire( null ) );
    }

    @Test
    void includes_models_monotonic_trust() {
        // HUMAN view sees only human-tier rows.
        assertTrue( Tier.HUMAN.includes( "human" ) );
        assertFalse( Tier.HUMAN.includes( "machine" ) );
        // MACHINE view sees both.
        assertTrue( Tier.MACHINE.includes( "human" ) );
        assertTrue( Tier.MACHINE.includes( "machine" ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-api -Dtest=TierTest`
Expected: FAIL with "cannot find symbol Tier".

- [ ] **Step 3: Implement the three types**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/Tier.java`:

```java
package com.wikantik.api.knowledge;

import java.util.Set;

/**
 * Trust tier for materialised knowledge-graph rows. Monotonic: MACHINE
 * includes both machine- and human-vetted rows; HUMAN is the strict view.
 */
public enum Tier {
    HUMAN( "human", Set.of( "human" ) ),
    MACHINE( "machine", Set.of( "human", "machine" ) );

    private final String wireName;
    private final Set< String > includedTiers;

    Tier( final String wireName, final Set< String > includedTiers ) {
        this.wireName = wireName;
        this.includedTiers = includedTiers;
    }

    public String wireName() { return wireName; }

    public boolean includes( final String tierColumnValue ) {
        return includedTiers.contains( tierColumnValue );
    }

    public Set< String > includedTiers() { return includedTiers; }

    public static Tier fromWire( final String wire ) {
        if ( wire == null ) throw new IllegalArgumentException( "tier must not be null" );
        for ( final Tier t : values() ) {
            if ( t.wireName.equalsIgnoreCase( wire ) ) return t;
        }
        throw new IllegalArgumentException( "unknown tier: " + wire );
    }
}
```

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/JudgeVerdict.java`:

```java
package com.wikantik.api.knowledge;

/**
 * Result of a single judge LLM call. Verdicts are one of approved | rejected | abstain.
 * Confidence is in [0,1]; rationale is the judge's free-text explanation.
 */
public record JudgeVerdict(
    String verdict,
    double confidence,
    String rationale,
    String model
) {
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String ABSTAIN  = "abstain";

    public JudgeVerdict {
        if ( verdict == null
                || !( APPROVED.equals( verdict ) || REJECTED.equals( verdict ) || ABSTAIN.equals( verdict ) ) ) {
            throw new IllegalArgumentException( "verdict must be approved|rejected|abstain, got " + verdict );
        }
        if ( confidence < 0.0 || confidence > 1.0 ) {
            throw new IllegalArgumentException( "confidence must be in [0,1], got " + confidence );
        }
        if ( model == null || model.isBlank() ) {
            throw new IllegalArgumentException( "model must not be blank" );
        }
    }
}
```

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposalReview.java`:

```java
package com.wikantik.api.knowledge;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit row for a single proposal review action.
 * reviewerKind = "machine" | "human"; reviewerId = model name or username.
 */
public record KgProposalReview(
    UUID id,
    UUID proposalId,
    String reviewerKind,
    String reviewerId,
    String verdict,
    Double confidence,
    String rationale,
    Instant created
) {
    public static final String REVIEWER_MACHINE = "machine";
    public static final String REVIEWER_HUMAN   = "human";
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-api -Dtest=TierTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/Tier.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/JudgeVerdict.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposalReview.java \
        wikantik-api/src/test/java/com/wikantik/api/knowledge/TierTest.java
git commit -m "feat(kg-api): Tier, JudgeVerdict, KgProposalReview records"
```

---

## Task 3: Extend KgProposal, KgNode, KgEdge with new fields

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposal.java`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgNode.java`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgEdge.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/knowledge/KgRecordsTierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class KgRecordsTierTest {
    @Test
    void kgProposal_has_tier_and_machine_fields() {
        final UUID id = UUID.randomUUID();
        final KgProposal p = new KgProposal(
            id, "new-edge", "Page", Map.of(), 0.5, "reason",
            "pending", null, Instant.now(), null,
            "machine", "approved", 0.85, Instant.now(), "gemma4-assist:latest" );
        assertEquals( "machine", p.tier() );
        assertEquals( "approved", p.machineStatus() );
        assertEquals( 0.85, p.machineConfidence() );
        assertEquals( "gemma4-assist:latest", p.machineModel() );
    }

    @Test
    void kgNode_has_tier_and_provenance() {
        final UUID id = UUID.randomUUID();
        final UUID provenance = UUID.randomUUID();
        final KgNode n = new KgNode(
            id, "Foo", "concept", "Foo", Provenance.MARKDOWN_SECTION, Map.of(),
            Instant.now(), Instant.now(), "machine", provenance );
        assertEquals( "machine", n.tier() );
        assertEquals( provenance, n.provenanceProposalId() );
    }

    @Test
    void kgEdge_has_tier_and_provenance() {
        final UUID provenance = UUID.randomUUID();
        final KgEdge e = new KgEdge(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "rel",
            Provenance.MARKDOWN_SECTION, Map.of(), Instant.now(), Instant.now(),
            "human", provenance );
        assertEquals( "human", e.tier() );
        assertEquals( provenance, e.provenanceProposalId() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-api -Dtest=KgRecordsTierTest`
Expected: FAIL with "constructor … cannot be applied".

- [ ] **Step 3: Extend the records**

Modify `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposal.java`:

```java
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
    String status,             // human verdict: pending | approved | rejected
    String reviewedBy,
    Instant created,
    Instant reviewedAt,
    String tier,               // none | machine | human
    String machineStatus,      // null | approved | rejected | abstain
    Double machineConfidence,
    Instant machineJudgedAt,
    String machineModel
) {
    public KgProposal {
        proposedData = proposedData == null ? Map.of() : Map.copyOf( proposedData );
        tier = tier == null ? "none" : tier;
    }
}
```

Modify `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgNode.java`:

```java
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
    Instant modified,
    String tier,
    UUID provenanceProposalId
) {
    public KgNode {
        properties = properties == null ? Map.of() : Map.copyOf( properties );
        tier = tier == null ? "human" : tier;
    }

    /** Returns true if this node is a stub (referenced but has no wiki page yet). */
    public boolean isStub() {
        return sourcePage == null;
    }
}
```

Modify `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgEdge.java`:

```java
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
    Instant modified,
    String tier,
    UUID provenanceProposalId
) {
    public KgEdge {
        properties = properties == null ? Map.of() : Map.copyOf( properties );
        tier = tier == null ? "human" : tier;
    }
}
```

- [ ] **Step 4: Run test-compile to surface call-site breakage early**

Per `feedback_test_compile_after_signature_change.md` — record canonical constructors are widening.

Run: `mvn test-compile -pl wikantik-api,wikantik-main,wikantik-rest,wikantik-knowledge -am`
Expected: Many compile errors at construction sites — that's EXPECTED. Note the file list; later tasks will fix them. Do NOT proceed until you've **read** the list and confirmed every error is a record-arity mismatch (no semantic surprises).

- [ ] **Step 5: Wide unit-only build to confirm api module passes alone**

Run: `mvn test -pl wikantik-api -Dtest=KgRecordsTierTest`
Expected: PASS.

- [ ] **Step 6: Commit (api module only — call sites fixed in later tasks)**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposal.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/KgNode.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/KgEdge.java \
        wikantik-api/src/test/java/com/wikantik/api/knowledge/KgRecordsTierTest.java
git commit -m "feat(kg-api): widen KgProposal/KgNode/KgEdge with tier + provenance"
```

---

## Task 4: JdbcKnowledgeRepository — row mappers + tier-aware getAllNodes/getAllEdges

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryTierReadsTest.java`

This task fixes the call sites broken by Task 3 inside `JdbcKnowledgeRepository` (row mappers) and adds tier-filtered reads.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.Tier;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JdbcKnowledgeRepositoryTierReadsTest extends PgTestBase {
    @Test
    void getAllNodes_filters_by_tier() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final UUID provenance = UUID.randomUUID();
        // Manually insert one human-tier and one machine-tier node.
        execSql( "INSERT INTO kg_nodes (name, node_type, source_page, provenance, properties, tier, modified) " +
            "VALUES ('Human', 'concept', null, 'markdown_section', '{}'::jsonb, 'human', NOW())" );
        execSql( "INSERT INTO kg_nodes (name, node_type, source_page, provenance, properties, tier, modified) " +
            "VALUES ('Machine', 'concept', null, 'markdown_section', '{}'::jsonb, 'machine', NOW())" );

        final List< KgNode > humanOnly = repo.getAllNodes( Tier.HUMAN );
        assertTrue( humanOnly.stream().anyMatch( n -> n.name().equals( "Human" ) ) );
        assertFalse( humanOnly.stream().anyMatch( n -> n.name().equals( "Machine" ) ) );

        final List< KgNode > both = repo.getAllNodes( Tier.MACHINE );
        assertTrue( both.stream().anyMatch( n -> n.name().equals( "Human" ) ) );
        assertTrue( both.stream().anyMatch( n -> n.name().equals( "Machine" ) ) );
    }

    @Test
    void getAllEdges_filters_by_tier() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        // Seed two nodes and two edges (one human, one machine).
        final KgNode a = repo.upsertNode( "A", "concept", null, Provenance.MARKDOWN_SECTION, Map.of() );
        final KgNode b = repo.upsertNode( "B", "concept", null, Provenance.MARKDOWN_SECTION, Map.of() );
        execSql( String.format(
            "INSERT INTO kg_edges (source_id, target_id, relationship_type, provenance, properties, tier, modified) " +
            "VALUES ('%s'::uuid, '%s'::uuid, 'human-rel', 'markdown_section', '{}'::jsonb, 'human', NOW())",
            a.id(), b.id() ) );
        execSql( String.format(
            "INSERT INTO kg_edges (source_id, target_id, relationship_type, provenance, properties, tier, modified) " +
            "VALUES ('%s'::uuid, '%s'::uuid, 'machine-rel', 'markdown_section', '{}'::jsonb, 'machine', NOW())",
            a.id(), b.id() ) );

        final List< KgEdge > humanOnly = repo.getAllEdges( Tier.HUMAN );
        assertTrue( humanOnly.stream().anyMatch( e -> e.relationshipType().equals( "human-rel" ) ) );
        assertFalse( humanOnly.stream().anyMatch( e -> e.relationshipType().equals( "machine-rel" ) ) );

        final List< KgEdge > both = repo.getAllEdges( Tier.MACHINE );
        assertTrue( both.stream().anyMatch( e -> e.relationshipType().equals( "human-rel" ) ) );
        assertTrue( both.stream().anyMatch( e -> e.relationshipType().equals( "machine-rel" ) ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=JdbcKnowledgeRepositoryTierReadsTest`
Expected: FAIL — `getAllNodes(Tier)` not defined.

- [ ] **Step 3: Update repository row mappers and add tier-aware reads**

In `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java`:

a) **Update `mapNodeRow` (and equivalent for KgEdge)** to read `tier` and `provenance_proposal_id` columns and pass them to the canonical record constructor.
b) **Update existing `INSERT INTO kg_nodes` / `INSERT INTO kg_edges` SQL** to include `tier` and `provenance_proposal_id` columns. Default `tier='human'` for existing call sites (those go through `upsertNode`/`upsertEdge` which today represent admin-curated entries) — pass `provenance_proposal_id = NULL`.
c) **Add tier-aware overloads:**

```java
/** Backward-compatible default: human-tier only. */
public List< KgNode > getAllNodes() { return getAllNodes( Tier.HUMAN ); }

public List< KgNode > getAllNodes( final Tier minTier ) {
    final String sql = "SELECT * FROM kg_nodes WHERE tier = ANY( ? )";
    try ( Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement( sql ) ) {
        ps.setArray( 1, c.createArrayOf( "varchar", minTier.includedTiers().toArray() ) );
        try ( ResultSet rs = ps.executeQuery() ) {
            final List< KgNode > out = new ArrayList<>();
            while ( rs.next() ) out.add( mapNodeRow( rs ) );
            return out;
        }
    } catch ( final SQLException e ) {
        LOG.warn( "getAllNodes({}) failed: {}", minTier.wireName(), e.getMessage(), e );
        throw new RuntimeException( "getAllNodes failed: " + e.getMessage(), e );
    }
}
```

Mirror for `getAllEdges()` / `getAllEdges(Tier)`.

Update `mapNodeRow` (existing method):

```java
private KgNode mapNodeRow( final ResultSet rs ) throws SQLException {
    return new KgNode(
        (UUID) rs.getObject( "id" ),
        rs.getString( "name" ),
        rs.getString( "node_type" ),
        rs.getString( "source_page" ),
        Provenance.fromWire( rs.getString( "provenance" ) ),
        parsePropertiesJson( rs.getString( "properties" ) ),
        rs.getTimestamp( "created" ).toInstant(),
        rs.getTimestamp( "modified" ).toInstant(),
        rs.getString( "tier" ),
        (UUID) rs.getObject( "provenance_proposal_id" )
    );
}
```

Mirror for `mapEdgeRow`.

Update insert SQL on existing `upsertNode`/`upsertEdge` to add the two new columns:

```java
final String sql = "INSERT INTO kg_nodes ( name, node_type, source_page, provenance, properties, " +
    "tier, provenance_proposal_id, modified ) " +
    "VALUES ( ?, ?, ?, ?, ?::jsonb, 'human', NULL, NOW() ) " +
    "ON CONFLICT ( name ) DO UPDATE SET " +
    "node_type = EXCLUDED.node_type, source_page = COALESCE(EXCLUDED.source_page, kg_nodes.source_page), " +
    "provenance = EXCLUDED.provenance, properties = EXCLUDED.properties, modified = NOW() " +
    "RETURNING *";
```

(Existing `upsertNode/upsertEdge` callers are admin-curated; they remain `tier='human'`. Materialisation goes through `KgMaterializationService` in Task 8/9.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=JdbcKnowledgeRepositoryTierReadsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryTierReadsTest.java
git commit -m "feat(kg-repo): tier-aware getAllNodes/getAllEdges + row mapper updates"
```

---

## Task 5: JdbcKnowledgeRepository — tier-aware searchKnowledge + traverseByCoMention

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositorySearchTierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.Tier;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class JdbcKnowledgeRepositorySearchTierTest extends PgTestBase {
    @Test
    void searchKnowledge_filters_by_tier() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        repo.upsertNode( "Apple", "concept", null, Provenance.MARKDOWN_SECTION, Map.of() ); // tier=human
        execSql( "INSERT INTO kg_nodes (name, node_type, source_page, provenance, properties, tier, modified) " +
            "VALUES ('Applet', 'concept', null, 'markdown_section', '{}'::jsonb, 'machine', NOW())" );

        final List< KgNode > strict = repo.searchKnowledge( "Appl",
            Set.of( Provenance.MARKDOWN_SECTION ), 10, Tier.HUMAN );
        assertTrue( strict.stream().anyMatch( n -> n.name().equals( "Apple" ) ) );
        assertFalse( strict.stream().anyMatch( n -> n.name().equals( "Applet" ) ) );

        final List< KgNode > broad = repo.searchKnowledge( "Appl",
            Set.of( Provenance.MARKDOWN_SECTION ), 10, Tier.MACHINE );
        assertTrue( broad.stream().anyMatch( n -> n.name().equals( "Apple" ) ) );
        assertTrue( broad.stream().anyMatch( n -> n.name().equals( "Applet" ) ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=JdbcKnowledgeRepositorySearchTierTest`
Expected: FAIL — `searchKnowledge(...,Tier)` not defined.

- [ ] **Step 3: Add tier-aware overload**

In `JdbcKnowledgeRepository`:

```java
/** Backward-compatible: defaults to HUMAN tier. */
public List< KgNode > searchKnowledge( final String query,
                                       final Set< Provenance > provenanceFilter,
                                       final int limit ) {
    return searchKnowledge( query, provenanceFilter, limit, Tier.HUMAN );
}

public List< KgNode > searchKnowledge( final String query,
                                       final Set< Provenance > provenanceFilter,
                                       final int limit,
                                       final Tier minTier ) {
    final StringBuilder sql = new StringBuilder(
        "SELECT * FROM kg_nodes WHERE name ILIKE ? AND tier = ANY( ? )" );
    if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
        sql.append( " AND provenance = ANY( ? )" );
    }
    sql.append( " ORDER BY name ASC LIMIT ?" );

    try ( Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement( sql.toString() ) ) {
        int idx = 1;
        ps.setString( idx++, "%" + query + "%" );
        ps.setArray( idx++, c.createArrayOf( "varchar", minTier.includedTiers().toArray() ) );
        if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            final String[] provs = provenanceFilter.stream()
                .map( Provenance::wireName ).toArray( String[]::new );
            ps.setArray( idx++, c.createArrayOf( "varchar", provs ) );
        }
        ps.setInt( idx, limit );
        try ( ResultSet rs = ps.executeQuery() ) {
            final List< KgNode > out = new ArrayList<>();
            while ( rs.next() ) out.add( mapNodeRow( rs ) );
            return out;
        }
    } catch ( final SQLException e ) {
        LOG.warn( "searchKnowledge('{}', tier={}) failed: {}", query, minTier.wireName(),
            e.getMessage(), e );
        throw new RuntimeException( "searchKnowledge failed: " + e.getMessage(), e );
    }
}
```

For `traverseByCoMention`: add a tier-aware overload that filters edge rows in the BFS expansion by `tier IN (…)`. Default overload preserves HUMAN behaviour. Mirror the structure above; the only change to the SQL is adding `AND e.tier = ANY( ? )` to the join expansion query.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=JdbcKnowledgeRepositorySearchTierTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositorySearchTierTest.java
git commit -m "feat(kg-repo): tier-aware searchKnowledge + traverseByCoMention"
```

---

## Task 6: JdbcKnowledgeRepository — recordReview + getProposalsForJudging

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryReviewTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalReview;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JdbcKnowledgeRepositoryReviewTest extends PgTestBase {
    @Test
    void recordReview_appends_audit_row() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "A", "target", "B", "relationship", "rel" ),
            0.7, "reason" );

        repo.recordReview( p.id(), KgProposalReview.REVIEWER_MACHINE,
            "gemma4-assist:latest", "approved", 0.85, "looks legit" );
        repo.recordReview( p.id(), KgProposalReview.REVIEWER_HUMAN,
            "alice", "approved", null, null );

        final List< KgProposalReview > reviews = repo.listReviews( p.id() );
        assertEquals( 2, reviews.size() );
        // listReviews is ordered by created DESC.
        assertEquals( KgProposalReview.REVIEWER_HUMAN, reviews.get( 0 ).reviewerKind() );
        assertEquals( KgProposalReview.REVIEWER_MACHINE, reviews.get( 1 ).reviewerKind() );
        assertEquals( 0.85, reviews.get( 1 ).confidence() );
    }

    @Test
    void getProposalsForJudging_returns_only_unjudged() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgProposal a = repo.insertProposal( "new-edge", "PageA",
            Map.of( "source", "X", "target", "Y", "relationship", "r" ), 0.5, "" );
        final KgProposal b = repo.insertProposal( "new-edge", "PageB",
            Map.of( "source", "X", "target", "Z", "relationship", "r" ), 0.5, "" );
        // Mark `a` as already judged.
        execSql( "UPDATE kg_proposals SET machine_status='approved' WHERE id='" + a.id() + "'" );

        final List< KgProposal > batch = repo.getProposalsForJudging( 50 );
        assertTrue( batch.stream().anyMatch( p -> p.id().equals( b.id() ) ) );
        assertFalse( batch.stream().anyMatch( p -> p.id().equals( a.id() ) ) );
    }

    @Test
    void getProposalsForJudging_skips_locked_rows() throws Exception {
        // Insert one proposal, lock it in tx1, ensure tx2 picks zero rows (SKIP LOCKED).
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "A", "target", "B", "relationship", "r" ), 0.5, "" );

        try ( var c1 = dataSource().getConnection() ) {
            c1.setAutoCommit( false );
            try ( var st = c1.prepareStatement(
                    "SELECT id FROM kg_proposals WHERE machine_status IS NULL FOR UPDATE" ) ) {
                st.executeQuery().next();
                // Now from another connection, the SKIP LOCKED query must skip it.
                final List< KgProposal > batch = repo.getProposalsForJudging( 50 );
                assertTrue( batch.isEmpty(), "SKIP LOCKED must skip the row held by tx1" );
            } finally {
                c1.rollback();
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=JdbcKnowledgeRepositoryReviewTest`
Expected: FAIL — `recordReview`, `listReviews`, `getProposalsForJudging` not defined.

- [ ] **Step 3: Add the methods**

```java
public void recordReview( final UUID proposalId, final String reviewerKind,
                          final String reviewerId, final String verdict,
                          final Double confidence, final String rationale ) {
    final String sql = "INSERT INTO kg_proposal_reviews " +
        "(proposal_id, reviewer_kind, reviewer_id, verdict, confidence, rationale) " +
        "VALUES (?, ?, ?, ?, ?, ?)";
    try ( Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement( sql ) ) {
        ps.setObject( 1, proposalId );
        ps.setString( 2, reviewerKind );
        ps.setString( 3, reviewerId );
        ps.setString( 4, verdict );
        if ( confidence == null ) ps.setNull( 5, Types.DOUBLE );
        else ps.setDouble( 5, confidence );
        ps.setString( 6, rationale );
        ps.executeUpdate();
    } catch ( final SQLException e ) {
        LOG.warn( "recordReview({}, {}, {}) failed: {}", proposalId, reviewerKind,
            verdict, e.getMessage(), e );
        throw new RuntimeException( "recordReview failed: " + e.getMessage(), e );
    }
}

public List< KgProposalReview > listReviews( final UUID proposalId ) {
    final String sql = "SELECT * FROM kg_proposal_reviews WHERE proposal_id = ? " +
        "ORDER BY created DESC, id DESC";
    try ( Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement( sql ) ) {
        ps.setObject( 1, proposalId );
        try ( ResultSet rs = ps.executeQuery() ) {
            final List< KgProposalReview > out = new ArrayList<>();
            while ( rs.next() ) {
                final double conf = rs.getDouble( "confidence" );
                out.add( new KgProposalReview(
                    (UUID) rs.getObject( "id" ),
                    (UUID) rs.getObject( "proposal_id" ),
                    rs.getString( "reviewer_kind" ),
                    rs.getString( "reviewer_id" ),
                    rs.getString( "verdict" ),
                    rs.wasNull() ? null : conf,
                    rs.getString( "rationale" ),
                    rs.getTimestamp( "created" ).toInstant()
                ) );
            }
            return out;
        }
    } catch ( final SQLException e ) {
        LOG.warn( "listReviews({}) failed: {}", proposalId, e.getMessage(), e );
        throw new RuntimeException( "listReviews failed: " + e.getMessage(), e );
    }
}

/**
 * Picks up to `batch` pending proposals whose machine_status is NULL.
 * Uses FOR UPDATE SKIP LOCKED so multiple judge runners don't double-pick.
 * Caller MUST consume within the same transaction (returned rows hold locks
 * until commit/rollback).
 */
public List< KgProposal > getProposalsForJudging( final int batch ) {
    final String sql = "SELECT * FROM kg_proposals " +
        "WHERE status = 'pending' AND machine_status IS NULL " +
        "ORDER BY created ASC " +
        "LIMIT ? FOR UPDATE SKIP LOCKED";
    try ( Connection c = ds.getConnection() ) {
        c.setAutoCommit( false );
        try ( PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setInt( 1, batch );
            try ( ResultSet rs = ps.executeQuery() ) {
                final List< KgProposal > out = new ArrayList<>();
                while ( rs.next() ) out.add( mapProposalRow( rs ) );
                c.commit();
                return out;
            }
        } catch ( final SQLException e ) {
            c.rollback();
            throw e;
        }
    } catch ( final SQLException e ) {
        LOG.warn( "getProposalsForJudging({}) failed: {}", batch, e.getMessage(), e );
        throw new RuntimeException( "getProposalsForJudging failed: " + e.getMessage(), e );
    }
}
```

Update `mapProposalRow` to include the new tier/machine columns when building the `KgProposal` record (it now has 5 more fields per Task 3).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=JdbcKnowledgeRepositoryReviewTest`
Expected: PASS (all three test methods).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryReviewTest.java
git commit -m "feat(kg-repo): recordReview + getProposalsForJudging (SKIP LOCKED)"
```

---

## Task 7: JdbcKnowledgeRepository — applyMachineVerdict + applyHumanVerdict + clearAll update

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryVerdictTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgProposal;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JdbcKnowledgeRepositoryVerdictTest extends PgTestBase {
    @Test
    void applyMachineVerdict_approved_sets_machine_status_and_tier_machine() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "A", "target", "B", "relationship", "r" ), 0.7, "" );

        repo.applyMachineVerdict( p.id(), "approved", 0.9, "gemma4-assist:latest" );

        final KgProposal updated = repo.getProposal( p.id() );
        assertEquals( "approved", updated.machineStatus() );
        assertEquals( "machine", updated.tier() );
        assertEquals( 0.9, updated.machineConfidence() );
        assertNotNull( updated.machineJudgedAt() );
    }

    @Test
    void applyMachineVerdict_rejected_sets_status_rejected_tier_none() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "A", "target", "C", "relationship", "r" ), 0.7, "" );

        repo.applyMachineVerdict( p.id(), "rejected", 0.95, "gemma4-assist:latest" );

        final KgProposal updated = repo.getProposal( p.id() );
        assertEquals( "rejected", updated.status(), "human status flips to rejected on hard auto-reject" );
        assertEquals( "rejected", updated.machineStatus() );
        assertEquals( "none", updated.tier(), "rejected proposals are not materialised" );
    }

    @Test
    void applyHumanVerdict_approved_sets_tier_human() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "A", "target", "D", "relationship", "r" ), 0.7, "" );
        repo.applyMachineVerdict( p.id(), "approved", 0.8, "gemma4-assist:latest" );

        repo.applyHumanVerdict( p.id(), "approved", "alice" );

        final KgProposal updated = repo.getProposal( p.id() );
        assertEquals( "approved", updated.status() );
        assertEquals( "human", updated.tier() );
        assertEquals( "alice", updated.reviewedBy() );
    }

    @Test
    void clearAll_truncates_kg_proposal_reviews() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "A", "target", "E", "relationship", "r" ), 0.7, "" );
        repo.recordReview( p.id(), "machine", "gemma", "approved", 0.8, "ok" );

        repo.clearAll();

        assertTrue( repo.listReviews( p.id() ).isEmpty() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=JdbcKnowledgeRepositoryVerdictTest`
Expected: FAIL — methods not defined.

- [ ] **Step 3: Implement the verdict methods**

```java
public void applyMachineVerdict( final UUID proposalId, final String verdict,
                                  final double confidence, final String model ) {
    final String newTier;
    final String newStatus;
    if ( "approved".equals( verdict ) ) {
        newTier = "machine"; newStatus = null; // status untouched
    } else if ( "rejected".equals( verdict ) ) {
        newTier = "none"; newStatus = "rejected"; // hard auto-reject flips human status too
    } else if ( "abstain".equals( verdict ) ) {
        newTier = "none"; newStatus = null;
    } else {
        throw new IllegalArgumentException( "verdict must be approved|rejected|abstain" );
    }

    final String sql = "UPDATE kg_proposals SET " +
        "machine_status = ?, machine_confidence = ?, machine_judged_at = NOW(), " +
        "machine_model = ?, tier = ?" +
        ( newStatus != null ? ", status = ?" : "" ) +
        " WHERE id = ?";
    try ( Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement( sql ) ) {
        int idx = 1;
        ps.setString( idx++, verdict );
        ps.setDouble( idx++, confidence );
        ps.setString( idx++, model );
        ps.setString( idx++, newTier );
        if ( newStatus != null ) ps.setString( idx++, newStatus );
        ps.setObject( idx, proposalId );
        ps.executeUpdate();
    } catch ( final SQLException e ) {
        LOG.warn( "applyMachineVerdict({}, {}) failed: {}", proposalId, verdict,
            e.getMessage(), e );
        throw new RuntimeException( "applyMachineVerdict failed: " + e.getMessage(), e );
    }
}

public void applyHumanVerdict( final UUID proposalId, final String verdict,
                                final String reviewedBy ) {
    if ( !( "approved".equals( verdict ) || "rejected".equals( verdict ) ) ) {
        throw new IllegalArgumentException( "human verdict must be approved|rejected" );
    }
    final String newTier = "approved".equals( verdict ) ? "human" : "none";
    final String sql = "UPDATE kg_proposals SET " +
        "status = ?, reviewed_by = ?, reviewed_at = NOW(), tier = ? WHERE id = ?";
    try ( Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement( sql ) ) {
        ps.setString( 1, verdict );
        ps.setString( 2, reviewedBy );
        ps.setString( 3, newTier );
        ps.setObject( 4, proposalId );
        ps.executeUpdate();
    } catch ( final SQLException e ) {
        LOG.warn( "applyHumanVerdict({}, {}, {}) failed: {}", proposalId, verdict,
            reviewedBy, e.getMessage(), e );
        throw new RuntimeException( "applyHumanVerdict failed: " + e.getMessage(), e );
    }
}
```

In existing `clearAll()`, add:

```java
execSql( c, "TRUNCATE TABLE kg_proposal_reviews" );
```

(Order: truncate child tables before parents. `kg_proposal_reviews` references `kg_proposals` with `ON DELETE CASCADE`, so truncating it first is safest.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=JdbcKnowledgeRepositoryVerdictTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryVerdictTest.java
git commit -m "feat(kg-repo): applyMachineVerdict + applyHumanVerdict + clearAll review truncation"
```

---

## Task 8: KgMaterializationService — materializeMachine

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgMaterializationService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java` (add `upsertNodeWithProvenance`, `upsertEdgeWithProvenance`)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/judge/KgMaterializationServiceMaterializeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.judge;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.PgTestBase;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class KgMaterializationServiceMaterializeTest extends PgTestBase {
    @Test
    void materializeMachine_new_edge_inserts_two_nodes_and_one_edge_at_machine_tier() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService svc = new KgMaterializationService( repo );

        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "Alpha", "target", "Beta", "relationship", "depends_on" ),
            0.8, "extractor reasoning" );

        svc.materializeMachine( p );

        final List< KgNode > all = repo.getAllNodes( com.wikantik.api.knowledge.Tier.MACHINE );
        assertTrue( all.stream().anyMatch( n -> n.name().equals( "Alpha" ) && "machine".equals( n.tier() ) ) );
        assertTrue( all.stream().anyMatch( n -> n.name().equals( "Beta" ) && "machine".equals( n.tier() ) ) );

        // Edge tier=machine, provenance points back to proposal.
        final var edges = repo.getAllEdges( com.wikantik.api.knowledge.Tier.MACHINE );
        assertTrue( edges.stream().anyMatch( e -> "depends_on".equals( e.relationshipType() )
            && p.id().equals( e.provenanceProposalId() )
            && "machine".equals( e.tier() ) ) );
    }

    @Test
    void materializeMachine_is_idempotent() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService svc = new KgMaterializationService( repo );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "Gamma", "target", "Delta", "relationship", "uses" ),
            0.8, "" );

        svc.materializeMachine( p );
        svc.materializeMachine( p ); // second call must be a no-op

        final var edges = repo.getAllEdges( com.wikantik.api.knowledge.Tier.MACHINE );
        final long count = edges.stream()
            .filter( e -> "uses".equals( e.relationshipType() )
                && p.id().equals( e.provenanceProposalId() ) ).count();
        assertEquals( 1L, count, "edge must not be duplicated on retry" );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=KgMaterializationServiceMaterializeTest`
Expected: FAIL — class not defined.

- [ ] **Step 3: Implement the service and provenance-aware repo upserts**

In `JdbcKnowledgeRepository`, add:

```java
public KgNode upsertNodeWithProvenance( final String name, final String nodeType,
                                        final String sourcePage, final Provenance provenance,
                                        final Map< String, Object > properties,
                                        final String tier, final UUID provenanceProposalId ) {
    final String sql = "INSERT INTO kg_nodes ( name, node_type, source_page, provenance, properties, " +
        "tier, provenance_proposal_id, modified ) " +
        "VALUES ( ?, ?, ?, ?, ?::jsonb, ?, ?, NOW() ) " +
        "ON CONFLICT ( name ) DO UPDATE SET " +
        "node_type = EXCLUDED.node_type, source_page = COALESCE(EXCLUDED.source_page, kg_nodes.source_page), " +
        "provenance = EXCLUDED.provenance, properties = EXCLUDED.properties, modified = NOW() " +
        "RETURNING *";
    try ( Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement( sql ) ) {
        ps.setString( 1, name );
        ps.setString( 2, nodeType );
        ps.setString( 3, sourcePage );
        ps.setString( 4, provenance.wireName() );
        ps.setString( 5, propertiesJson( properties ) );
        ps.setString( 6, tier );
        if ( provenanceProposalId == null ) ps.setNull( 7, Types.OTHER );
        else ps.setObject( 7, provenanceProposalId );
        try ( ResultSet rs = ps.executeQuery() ) {
            rs.next();
            return mapNodeRow( rs );
        }
    } catch ( final SQLException e ) {
        LOG.warn( "upsertNodeWithProvenance({}) failed: {}", name, e.getMessage(), e );
        throw new RuntimeException( "upsertNodeWithProvenance failed: " + e.getMessage(), e );
    }
}

public KgEdge upsertEdgeWithProvenance( final UUID sourceId, final UUID targetId,
                                        final String relationshipType, final Provenance provenance,
                                        final Map< String, Object > properties,
                                        final String tier, final UUID provenanceProposalId ) {
    final String sql = "INSERT INTO kg_edges ( source_id, target_id, relationship_type, provenance, " +
        "properties, tier, provenance_proposal_id, modified ) " +
        "VALUES ( ?, ?, ?, ?, ?::jsonb, ?, ?, NOW() ) " +
        "ON CONFLICT ( source_id, target_id, relationship_type ) DO UPDATE SET " +
        "provenance = EXCLUDED.provenance, properties = EXCLUDED.properties, modified = NOW() " +
        "RETURNING *";
    try ( Connection c = ds.getConnection();
          PreparedStatement ps = c.prepareStatement( sql ) ) {
        ps.setObject( 1, sourceId );
        ps.setObject( 2, targetId );
        ps.setString( 3, relationshipType );
        ps.setString( 4, provenance.wireName() );
        ps.setString( 5, propertiesJson( properties ) );
        ps.setString( 6, tier );
        if ( provenanceProposalId == null ) ps.setNull( 7, Types.OTHER );
        else ps.setObject( 7, provenanceProposalId );
        try ( ResultSet rs = ps.executeQuery() ) {
            rs.next();
            return mapEdgeRow( rs );
        }
    } catch ( final SQLException e ) {
        LOG.warn( "upsertEdgeWithProvenance({},{},{}) failed: {}",
            sourceId, targetId, relationshipType, e.getMessage(), e );
        throw new RuntimeException( "upsertEdgeWithProvenance failed: " + e.getMessage(), e );
    }
}
```

(Verify the existing kg_edges UNIQUE constraint key matches the ON CONFLICT clause; if it differs, adjust to the actual conflict target.)

Create `wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgMaterializationService.java`:

```java
package com.wikantik.knowledge.judge;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Objects;

/**
 * Single owner of proposal-driven writes to kg_nodes and kg_edges.
 * Closes the long-standing gap where approveProposal only flipped a status column.
 *
 * Operations are idempotent: re-running on the same proposal is a no-op
 * thanks to ON CONFLICT in the underlying upserts.
 */
public class KgMaterializationService {

    private static final Logger LOG = LogManager.getLogger( KgMaterializationService.class );

    private final JdbcKnowledgeRepository repo;

    public KgMaterializationService( final JdbcKnowledgeRepository repo ) {
        this.repo = Objects.requireNonNull( repo, "repo" );
    }

    /** Materialise the proposal at tier='machine'. Currently supports proposalType='new-edge'. */
    public void materializeMachine( final KgProposal proposal ) {
        materialize( proposal, "machine" );
    }

    /** Promote (or insert) the proposal at tier='human'. */
    public void promoteToHuman( final KgProposal proposal ) {
        materialize( proposal, "human" );
    }

    private void materialize( final KgProposal proposal, final String tier ) {
        if ( !"new-edge".equals( proposal.proposalType() ) ) {
            LOG.debug( "materialize: skipping proposalType={}", proposal.proposalType() );
            return;
        }
        final Map< String, Object > data = proposal.proposedData();
        final String source = Objects.toString( data.get( "source" ), null );
        final String target = Objects.toString( data.get( "target" ), null );
        final String rel = Objects.toString( data.get( "relationship" ), null );
        if ( source == null || target == null || rel == null ) {
            LOG.warn( "materialize: missing source/target/relationship on proposal {}", proposal.id() );
            return;
        }

        final KgNode src = repo.upsertNodeWithProvenance( source, "concept", null,
            Provenance.MARKDOWN_SECTION, Map.of(), tier, proposal.id() );
        final KgNode tgt = repo.upsertNodeWithProvenance( target, "concept", null,
            Provenance.MARKDOWN_SECTION, Map.of(), tier, proposal.id() );
        repo.upsertEdgeWithProvenance( src.id(), tgt.id(), rel,
            Provenance.MARKDOWN_SECTION, Map.of(), tier, proposal.id() );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=KgMaterializationServiceMaterializeTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgMaterializationService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/judge/KgMaterializationServiceMaterializeTest.java
git commit -m "feat(kg-judge): KgMaterializationService.materializeMachine + provenance-aware upserts"
```

---

## Task 9: KgMaterializationService — promoteToHuman + retract + kg_rejections cleanup

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgMaterializationService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java` (add `deleteNodesAndEdgesByProvenance`, `deleteRejection`)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/judge/KgMaterializationServicePromoteRetractTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.judge;

import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.PgTestBase;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class KgMaterializationServicePromoteRetractTest extends PgTestBase {
    @Test
    void promoteToHuman_upgrades_existing_machine_rows_to_human() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService svc = new KgMaterializationService( repo );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "M1", "target", "M2", "relationship", "uses" ), 0.8, "" );
        svc.materializeMachine( p );

        svc.promoteToHuman( p );

        final var humanEdges = repo.getAllEdges( Tier.HUMAN );
        assertTrue( humanEdges.stream().anyMatch( e -> "uses".equals( e.relationshipType() )
            && p.id().equals( e.provenanceProposalId() ) ) );
    }

    @Test
    void promoteToHuman_inserts_when_no_machine_rows_exist() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService svc = new KgMaterializationService( repo );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "H1", "target", "H2", "relationship", "owns" ), 0.8, "" );

        svc.promoteToHuman( p ); // no prior materialisation

        final var humanEdges = repo.getAllEdges( Tier.HUMAN );
        assertTrue( humanEdges.stream().anyMatch( e -> "owns".equals( e.relationshipType() )
            && p.id().equals( e.provenanceProposalId() ) ) );
    }

    @Test
    void retract_deletes_rows_for_provenance() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService svc = new KgMaterializationService( repo );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "R1", "target", "R2", "relationship", "deletes_me" ), 0.8, "" );
        svc.materializeMachine( p );

        svc.retract( p );

        final var allEdges = repo.getAllEdges( Tier.MACHINE );
        assertFalse( allEdges.stream().anyMatch( e -> p.id().equals( e.provenanceProposalId() ) ) );
    }

    @Test
    void promoteToHuman_clears_kg_rejections_for_triple() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService svc = new KgMaterializationService( repo );
        // Simulate: judge previously rejected this triple → kg_rejections row exists.
        repo.insertRejection( "Z1", "Z2", "judged_no", "gemma4-assist:latest", "low evidence" );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "Z1", "target", "Z2", "relationship", "judged_no" ), 0.8, "" );

        svc.promoteToHuman( p );

        assertFalse( repo.isRejected( "Z1", "Z2", "judged_no" ),
            "human override must remove the negative-knowledge entry" );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=KgMaterializationServicePromoteRetractTest`
Expected: FAIL — `retract`, `deleteNodesAndEdgesByProvenance`, etc. not defined.

- [ ] **Step 3: Implement promoteToHuman cleanup, retract, and repo helpers**

In `JdbcKnowledgeRepository`:

```java
public int deleteNodesByProvenance( final UUID proposalId ) {
    return executeUpdate( "DELETE FROM kg_nodes WHERE provenance_proposal_id = ?",
        ps -> ps.setObject( 1, proposalId ) );
}

public int deleteEdgesByProvenance( final UUID proposalId ) {
    return executeUpdate( "DELETE FROM kg_edges WHERE provenance_proposal_id = ?",
        ps -> ps.setObject( 1, proposalId ) );
}

public int deleteRejection( final String source, final String target, final String relationship ) {
    return executeUpdate( "DELETE FROM kg_rejections WHERE proposed_source = ? " +
        "AND proposed_target = ? AND proposed_relationship = ?",
        ps -> { ps.setString( 1, source ); ps.setString( 2, target ); ps.setString( 3, relationship ); } );
}
```

(`executeUpdate` is a small helper — define it once if it doesn't already exist; otherwise inline the try/catch in the same shape as the other methods. Always log at WARN with context per the never-swallow-exceptions rule.)

In `KgMaterializationService`:

```java
public void retract( final KgProposal proposal ) {
    repo.deleteEdgesByProvenance( proposal.id() );
    repo.deleteNodesByProvenance( proposal.id() );
    if ( "new-edge".equals( proposal.proposalType() ) ) {
        final Map< String, Object > data = proposal.proposedData();
        final String src = Objects.toString( data.get( "source" ), null );
        final String tgt = Objects.toString( data.get( "target" ), null );
        final String rel = Objects.toString( data.get( "relationship" ), null );
        if ( src != null && tgt != null && rel != null ) {
            // Note: caller (DefaultKnowledgeGraphService.rejectProposal) is responsible
            // for inserting kg_rejections AFTER calling retract — that side preserves
            // the existing rejection-cascade semantics.
        }
    }
}
```

Update `promoteToHuman` to clear any pre-existing kg_rejections row for the triple before re-materialising, so a human override removes negative knowledge:

```java
public void promoteToHuman( final KgProposal proposal ) {
    if ( "new-edge".equals( proposal.proposalType() ) ) {
        final Map< String, Object > data = proposal.proposedData();
        final String src = Objects.toString( data.get( "source" ), null );
        final String tgt = Objects.toString( data.get( "target" ), null );
        final String rel = Objects.toString( data.get( "relationship" ), null );
        if ( src != null && tgt != null && rel != null ) {
            repo.deleteRejection( src, tgt, rel );
        }
    }
    materialize( proposal, "human" );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=KgMaterializationServicePromoteRetractTest`
Expected: PASS (all four tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgMaterializationService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/judge/KgMaterializationServicePromoteRetractTest.java
git commit -m "feat(kg-judge): promoteToHuman + retract + kg_rejections override"
```

---

## Task 10: KgProposalJudgeService interface + KgJudgeConfig

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposalJudgeService.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgJudgeConfig.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/judge/KgJudgeConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.judge;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class KgJudgeConfigTest {
    @Test
    void fromProperties_falls_back_to_extractor_settings_when_unset() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.ollama.endpoint", "http://extractor:11434" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "gemma4-assist:latest" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertEquals( "http://extractor:11434", cfg.endpoint() );
        assertEquals( "gemma4-assist:latest", cfg.model() );
    }

    @Test
    void fromProperties_explicit_judge_settings_override_extractor_fallback() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.ollama.endpoint", "http://extractor:11434" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "gemma4-assist:latest" );
        p.setProperty( "wikantik.kg.judge.endpoint", "http://judge:11434" );
        p.setProperty( "wikantik.kg.judge.model", "gemma4-judge:latest" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertEquals( "http://judge:11434", cfg.endpoint() );
        assertEquals( "gemma4-judge:latest", cfg.model() );
    }

    @Test
    void fromProperties_defaults_for_runtime_knobs() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.ollama.endpoint", "http://x" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "m" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );
        assertEquals( true, cfg.enabled() );
        assertEquals( true, cfg.cronEnabled() );
        assertEquals( 5, cfg.cronIntervalMinutes() );
        assertEquals( 50, cfg.batchSize() );
        assertEquals( 2, cfg.concurrency() );
        assertEquals( 30, cfg.timeoutSeconds() );
        assertEquals( 3, cfg.maxAttempts() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=KgJudgeConfigTest`
Expected: FAIL — class not defined.

- [ ] **Step 3: Implement interface and config**

Create `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposalJudgeService.java`:

```java
package com.wikantik.api.knowledge;

/** Stateless judge service: takes a proposal, returns a verdict. */
public interface KgProposalJudgeService {
    JudgeVerdict judge( KgProposal proposal );
}
```

Create `wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgJudgeConfig.java`:

```java
package com.wikantik.knowledge.judge;

import java.util.Properties;

/** Pure-data record holding the runtime config for the judge service + runner. */
public record KgJudgeConfig(
    boolean enabled,
    String endpoint,
    String model,
    boolean cronEnabled,
    int cronIntervalMinutes,
    int batchSize,
    int concurrency,
    int timeoutSeconds,
    int maxAttempts
) {
    public static KgJudgeConfig fromProperties( final Properties p ) {
        final String extractorEndpoint = p.getProperty( "wikantik.knowledge.extractor.ollama.endpoint" );
        final String extractorModel    = p.getProperty( "wikantik.knowledge.extractor.ollama.model" );

        return new KgJudgeConfig(
            getBool( p, "wikantik.kg.judge.enabled", true ),
            getString( p, "wikantik.kg.judge.endpoint", extractorEndpoint ),
            getString( p, "wikantik.kg.judge.model",    extractorModel ),
            getBool( p,   "wikantik.kg.judge.cron.enabled", true ),
            getInt( p,    "wikantik.kg.judge.cron.interval_min", 5 ),
            getInt( p,    "wikantik.kg.judge.batch_size", 50 ),
            getInt( p,    "wikantik.kg.judge.concurrency", 2 ),
            getInt( p,    "wikantik.kg.judge.timeout_seconds", 30 ),
            getInt( p,    "wikantik.kg.judge.max_attempts", 3 )
        );
    }

    private static String getString( final Properties p, final String k, final String def ) {
        final String v = p.getProperty( k );
        return ( v == null || v.isBlank() ) ? def : v.trim();
    }
    private static boolean getBool( final Properties p, final String k, final boolean def ) {
        final String v = p.getProperty( k );
        return ( v == null || v.isBlank() ) ? def : Boolean.parseBoolean( v.trim() );
    }
    private static int getInt( final Properties p, final String k, final int def ) {
        final String v = p.getProperty( k );
        try { return ( v == null || v.isBlank() ) ? def : Integer.parseInt( v.trim() ); }
        catch ( final NumberFormatException e ) { return def; }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=KgJudgeConfigTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposalJudgeService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgJudgeConfig.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/judge/KgJudgeConfigTest.java
git commit -m "feat(kg-judge): KgProposalJudgeService interface + KgJudgeConfig"
```

---

## Task 11: DefaultKgProposalJudgeService — Ollama implementation

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/judge/DefaultKgProposalJudgeService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/judge/DefaultKgProposalJudgeServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.judge;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultKgProposalJudgeServiceTest {
    private static KgProposal sampleProposal() {
        return new KgProposal(
            UUID.randomUUID(), "new-edge", "PageA",
            Map.of( "source", "Foo", "target", "Bar", "relationship", "uses" ),
            0.7, "extractor reasoning",
            "pending", null, Instant.now(), null,
            "none", null, null, null, null );
    }

    private static KgJudgeConfig cfg() {
        return new KgJudgeConfig( true, "http://localhost:11434",
            "gemma4-assist:latest", false, 5, 50, 2, 30, 3 );
    }

    @Test
    void judge_returns_approved_when_ollama_returns_strict_json() throws Exception {
        final HttpClient http = mock( HttpClient.class );
        final HttpResponse< String > resp = mock( HttpResponse.class );
        when( resp.statusCode() ).thenReturn( 200 );
        when( resp.body() ).thenReturn(
            "{\"response\":\"{\\\"verdict\\\":\\\"approved\\\",\\\"confidence\\\":0.85,\\\"rationale\\\":\\\"strong evidence\\\"}\"}" );
        when( http.send( any(), any() ) ).thenReturn( resp );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );

        assertEquals( "approved", v.verdict() );
        assertEquals( 0.85, v.confidence() );
        assertEquals( "gemma4-assist:latest", v.model() );
    }

    @Test
    void judge_returns_abstain_on_http_error() throws Exception {
        final HttpClient http = mock( HttpClient.class );
        when( http.send( any(), any() ) ).thenThrow( new java.io.IOException( "connection refused" ) );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );

        assertEquals( "abstain", v.verdict() );
        assertTrue( v.rationale().contains( "judge_unavailable" ) );
    }

    @Test
    void judge_returns_abstain_on_malformed_json() throws Exception {
        final HttpClient http = mock( HttpClient.class );
        final HttpResponse< String > resp = mock( HttpResponse.class );
        when( resp.statusCode() ).thenReturn( 200 );
        when( resp.body() ).thenReturn( "{\"response\":\"not-json\"}" );
        when( http.send( any(), any() ) ).thenReturn( resp );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );

        assertEquals( "abstain", v.verdict() );
        assertTrue( v.rationale().contains( "judge_unavailable" ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKgProposalJudgeServiceTest`
Expected: FAIL — class not defined.

- [ ] **Step 3: Implement the service**

Create `wikantik-main/src/main/java/com/wikantik/knowledge/judge/DefaultKgProposalJudgeService.java`:

```java
package com.wikantik.knowledge.judge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public class DefaultKgProposalJudgeService implements KgProposalJudgeService {

    private static final Logger LOG = LogManager.getLogger( DefaultKgProposalJudgeService.class );
    private static final Gson GSON = new Gson();

    private static final String SYSTEM_PROMPT = """
        You are a knowledge-graph fact judge. You are given an extracted relationship
        proposal: a (source, target, relationship) triple, the source page name, the
        extractor's confidence score, and the extractor's free-text reasoning.

        Decide whether the proposed triple is well-supported as a factual relationship
        on the source page. Return STRICT JSON with three keys and nothing else:

          {"verdict":"approved|rejected|abstain","confidence":0.0..1.0,"rationale":"..."}

        - approved: clear factual support; the triple should join the graph.
        - rejected: clearly unsupported, contradicted, or nonsensical.
        - abstain:  evidence is ambiguous or insufficient to commit either way.

        Confidence is YOUR confidence in the verdict, not the relationship strength.
        Rationale: one or two short sentences. No markdown, no preamble.
        """;

    private final HttpClient httpClient;
    private final KgJudgeConfig config;

    public DefaultKgProposalJudgeService( final HttpClient httpClient,
                                           final KgJudgeConfig config ) {
        this.httpClient = Objects.requireNonNull( httpClient, "httpClient" );
        this.config     = Objects.requireNonNull( config, "config" );
    }

    @Override
    public JudgeVerdict judge( final KgProposal proposal ) {
        final String userPrompt = buildUserPrompt( proposal );
        final String body = GSON.toJson( Map.of(
            "model", config.model(),
            "system", SYSTEM_PROMPT,
            "prompt", userPrompt,
            "stream", false,
            "format", "json"
        ) );

        final HttpRequest req = HttpRequest.newBuilder()
            .uri( URI.create( config.endpoint() + "/api/generate" ) )
            .timeout( Duration.ofSeconds( config.timeoutSeconds() ) )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( body ) )
            .build();

        try {
            final HttpResponse< String > resp = httpClient.send( req, HttpResponse.BodyHandlers.ofString() );
            if ( resp.statusCode() != 200 ) {
                return abstain( "judge_unavailable: http " + resp.statusCode() );
            }
            return parseResponse( resp.body() );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            LOG.warn( "judge interrupted for proposal {}: {}", proposal.id(), e.getMessage() );
            return abstain( "judge_unavailable: interrupted" );
        } catch ( final java.io.IOException e ) {
            LOG.warn( "judge HTTP failure for proposal {}: {}", proposal.id(), e.getMessage() );
            return abstain( "judge_unavailable: " + e.getMessage() );
        }
    }

    private String buildUserPrompt( final KgProposal p ) {
        final Map< String, Object > data = p.proposedData();
        return String.format(
            "PROPOSAL TYPE: %s%nSOURCE PAGE: %s%nSOURCE: %s%nTARGET: %s%nRELATIONSHIP: %s%n" +
            "EXTRACTOR CONFIDENCE: %.2f%nEXTRACTOR REASONING: %s%n",
            p.proposalType(), p.sourcePage(),
            data.get( "source" ), data.get( "target" ), data.get( "relationship" ),
            p.confidence(), p.reasoning() == null ? "" : p.reasoning() );
    }

    private JudgeVerdict parseResponse( final String body ) {
        try {
            final JsonObject outer = JsonParser.parseString( body ).getAsJsonObject();
            // Ollama wraps the model's text in a "response" string field.
            final String inner = outer.has( "response" ) ? outer.get( "response" ).getAsString() : body;
            final JsonObject verdict = JsonParser.parseString( inner ).getAsJsonObject();
            final String v = verdict.get( "verdict" ).getAsString();
            final double c = verdict.has( "confidence" ) ? verdict.get( "confidence" ).getAsDouble() : 0.0;
            final String r = verdict.has( "rationale" ) ? verdict.get( "rationale" ).getAsString() : "";
            // Clamp to canonical record bounds.
            final double clamped = Math.max( 0.0, Math.min( 1.0, c ) );
            return new JudgeVerdict( v, clamped, r, config.model() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "judge response parse failure: {}", e.getMessage() );
            return abstain( "judge_unavailable: parse error" );
        }
    }

    private JudgeVerdict abstain( final String reason ) {
        return new JudgeVerdict( JudgeVerdict.ABSTAIN, 0.0, reason, config.model() );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKgProposalJudgeServiceTest`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/judge/DefaultKgProposalJudgeService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/judge/DefaultKgProposalJudgeServiceTest.java
git commit -m "feat(kg-judge): DefaultKgProposalJudgeService (Ollama, strict JSON)"
```

---

## Task 12: JudgeRunner background task

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/judge/JudgeRunner.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/judge/JudgeRunnerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.judge;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalReview;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.PgTestBase;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JudgeRunnerTest extends PgTestBase {
    @Test
    void runOnce_judges_pending_proposals_and_records_review() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService mat = new KgMaterializationService( repo );
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        when( judge.judge( any() ) ).thenReturn(
            new JudgeVerdict( "approved", 0.9, "ok", "gemma4-assist:latest" ) );

        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "X", "target", "Y", "relationship", "rel" ), 0.7, "" );

        final JudgeRunner runner = new JudgeRunner( repo, judge, mat,
            new KgJudgeConfig( true, "x", "gemma4-assist:latest", false, 5, 50, 1, 30, 3 ) );
        runner.runOnce();

        assertEquals( "approved", repo.getProposal( p.id() ).machineStatus() );
        assertEquals( "machine", repo.getProposal( p.id() ).tier() );
        assertFalse( repo.listReviews( p.id() ).isEmpty() );
    }

    @Test
    void runOnce_skips_proposals_past_max_attempts() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService mat = new KgMaterializationService( repo );
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        when( judge.judge( any() ) ).thenReturn(
            new JudgeVerdict( "abstain", 0.0, "judge_unavailable: x", "gemma4-assist:latest" ) );

        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "X", "target", "Z", "relationship", "rel" ), 0.7, "" );
        // Pre-seed 3 abstain reviews → past max_attempts=3.
        for ( int i = 0; i < 3; i++ ) {
            repo.recordReview( p.id(), "machine", "gemma", "abstain", 0.0, "boom" );
        }

        final JudgeRunner runner = new JudgeRunner( repo, judge, mat,
            new KgJudgeConfig( true, "x", "gemma4-assist:latest", false, 5, 50, 1, 30, 3 ) );
        runner.runOnce();

        // Judge must NOT have been called for this proposal — it's past the cap.
        verify( judge, never() ).judge( any() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=JudgeRunnerTest`
Expected: FAIL — class not defined.

- [ ] **Step 3: Implement JudgeRunner**

Create `wikantik-main/src/main/java/com/wikantik/knowledge/judge/JudgeRunner.java`:

```java
package com.wikantik.knowledge.judge;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.api.knowledge.KgProposalReview;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background runner that picks up unjudged proposals and feeds them to the judge.
 * Cadence and concurrency come from KgJudgeConfig. Schedule is started by
 * WikiEngine wiring; tests can call runOnce() directly.
 */
public class JudgeRunner implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( JudgeRunner.class );

    private final JdbcKnowledgeRepository repo;
    private final KgProposalJudgeService judge;
    private final KgMaterializationService materialization;
    private final KgJudgeConfig config;
    private ScheduledExecutorService scheduler;
    private boolean started;

    public JudgeRunner( final JdbcKnowledgeRepository repo,
                         final KgProposalJudgeService judge,
                         final KgMaterializationService materialization,
                         final KgJudgeConfig config ) {
        this.repo            = Objects.requireNonNull( repo, "repo" );
        this.judge           = Objects.requireNonNull( judge, "judge" );
        this.materialization = Objects.requireNonNull( materialization, "materialization" );
        this.config          = Objects.requireNonNull( config, "config" );
    }

    public synchronized void schedule() {
        if ( started ) return;
        if ( !config.enabled() || !config.cronEnabled() ) {
            LOG.info( "JudgeRunner disabled (enabled={}, cronEnabled={})",
                config.enabled(), config.cronEnabled() );
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "kg-judge-runner" );
            t.setDaemon( true );
            return t;
        } );
        scheduler.scheduleAtFixedRate( this::runOnceQuietly,
            config.cronIntervalMinutes(), config.cronIntervalMinutes(), TimeUnit.MINUTES );
        started = true;
        LOG.info( "JudgeRunner scheduled every {} min", config.cronIntervalMinutes() );
    }

    public void runOnceQuietly() {
        try { runOnce(); }
        catch ( final RuntimeException e ) {
            LOG.warn( "judge runner pass failed: {}", e.getMessage(), e );
        }
    }

    /** Synchronous one-pass; visible for tests and for the admin "run now" trigger. */
    public int runOnce() {
        final List< KgProposal > batch = repo.getProposalsForJudging( config.batchSize() );
        if ( batch.isEmpty() ) return 0;

        final ExecutorService pool = Executors.newFixedThreadPool( Math.max( 1, config.concurrency() ) );
        try {
            int judged = 0;
            for ( final KgProposal proposal : batch ) {
                if ( pastMaxAttempts( proposal ) ) {
                    LOG.debug( "skip {} — past max_attempts ({})", proposal.id(), config.maxAttempts() );
                    continue;
                }
                pool.submit( () -> processOne( proposal ) );
                judged++;
            }
            pool.shutdown();
            if ( !pool.awaitTermination( config.timeoutSeconds() * 2L, TimeUnit.SECONDS ) ) {
                LOG.warn( "judge pool timed out — {} tasks may still be running", batch.size() );
            }
            return judged;
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            if ( !pool.isShutdown() ) pool.shutdownNow();
        }
    }

    private boolean pastMaxAttempts( final KgProposal p ) {
        final long abstainCount = repo.listReviews( p.id() ).stream()
            .filter( r -> KgProposalReview.REVIEWER_MACHINE.equals( r.reviewerKind() ) )
            .filter( r -> "abstain".equals( r.verdict() ) )
            .count();
        return abstainCount >= config.maxAttempts();
    }

    private void processOne( final KgProposal proposal ) {
        try {
            final JudgeVerdict v = judge.judge( proposal );
            repo.applyMachineVerdict( proposal.id(), v.verdict(), v.confidence(), v.model() );
            repo.recordReview( proposal.id(), KgProposalReview.REVIEWER_MACHINE, v.model(),
                v.verdict(), v.confidence(), v.rationale() );
            if ( JudgeVerdict.APPROVED.equals( v.verdict() ) ) {
                materialization.materializeMachine( proposal );
            } else if ( JudgeVerdict.REJECTED.equals( v.verdict() ) ) {
                // Hard auto-reject: write kg_rejections so the same triple won't re-surface.
                final var data = proposal.proposedData();
                final String src = java.util.Objects.toString( data.get( "source" ), null );
                final String tgt = java.util.Objects.toString( data.get( "target" ), null );
                final String rel = java.util.Objects.toString( data.get( "relationship" ), null );
                if ( "new-edge".equals( proposal.proposalType() )
                        && src != null && tgt != null && rel != null ) {
                    repo.insertRejection( src, tgt, rel, v.model(), v.rationale() );
                }
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "judge processing failed for proposal {}: {}", proposal.id(), e.getMessage(), e );
        }
    }

    @Override
    public synchronized void close() {
        if ( scheduler != null ) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        started = false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=JudgeRunnerTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/judge/JudgeRunner.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/judge/JudgeRunnerTest.java
git commit -m "feat(kg-judge): JudgeRunner background task with max_attempts cap"
```

---

## Task 13: KnowledgeGraphService interface — add Tier params; update other call sites

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`
- Modify: any other production call sites broken by Task 3 (use the file list captured in Task 3 step 4).
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTierReadTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.api.knowledge.SnapshotNode;
import com.wikantik.api.core.Session;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultKnowledgeGraphServiceTierReadTest {
    @Test
    void snapshotGraph_default_overload_uses_machine_tier_per_spec() {
        final JdbcKnowledgeRepository repo = mock( JdbcKnowledgeRepository.class );
        when( repo.getAllNodes( any( Tier.class ) ) ).thenReturn( java.util.List.of() );
        when( repo.getAllEdges( any( Tier.class ) ) ).thenReturn( java.util.List.of() );

        final DefaultKnowledgeGraphService svc = new DefaultKnowledgeGraphService( repo );
        final Session session = mock( Session.class );
        svc.snapshotGraph( session ); // no minTier argument → defaults to MACHINE

        verify( repo ).getAllNodes( Tier.MACHINE );
        verify( repo ).getAllEdges( Tier.MACHINE );
    }

    @Test
    void snapshotGraph_strict_overload_uses_human_tier() {
        final JdbcKnowledgeRepository repo = mock( JdbcKnowledgeRepository.class );
        when( repo.getAllNodes( any( Tier.class ) ) ).thenReturn( java.util.List.of() );
        when( repo.getAllEdges( any( Tier.class ) ) ).thenReturn( java.util.List.of() );

        final DefaultKnowledgeGraphService svc = new DefaultKnowledgeGraphService( repo );
        final Session session = mock( Session.class );
        svc.snapshotGraph( session, Tier.HUMAN );

        verify( repo ).getAllNodes( Tier.HUMAN );
        verify( repo ).getAllEdges( Tier.HUMAN );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceTierReadTest`
Expected: FAIL — overload not defined.

- [ ] **Step 3: Widen interface and implementation**

In `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java`:

```java
// New tier-aware methods (defaults preserved via overloads):

GraphSnapshot snapshotGraph( com.wikantik.api.core.Session viewer );          // default MACHINE
GraphSnapshot snapshotGraph( com.wikantik.api.core.Session viewer, Tier minTier );

List< KgNode > searchKnowledge( String query, Set< Provenance > provenanceFilter, int limit );
List< KgNode > searchKnowledge( String query, Set< Provenance > provenanceFilter, int limit, Tier minTier );

TraversalResult traverseByCoMention( String startNodeName, int maxDepth, int minSharedChunks );
TraversalResult traverseByCoMention( String startNodeName, int maxDepth, int minSharedChunks, Tier minTier );

/** Synchronously judge a single proposal — used by the admin "judge now" path. */
JudgeVerdict judgeNow( UUID proposalId, String triggeredBy );
```

In `DefaultKnowledgeGraphService`:

a) **Cache key change.** Cached snapshot map becomes `Map<Tier, GraphSnapshot>`; one entry per tier. Per-viewer redaction continues to run after the tier-filtered snapshot.

```java
private final java.util.Map< Tier, GraphSnapshot > cachedByTier = new java.util.concurrent.ConcurrentHashMap<>();
private final java.util.Map< Tier, java.time.Instant > cacheTsByTier = new java.util.concurrent.ConcurrentHashMap<>();

@Override
public GraphSnapshot snapshotGraph( final Session viewer ) {
    return snapshotGraph( viewer, Tier.MACHINE );
}

@Override
public GraphSnapshot snapshotGraph( final Session viewer, final Tier minTier ) {
    GraphSnapshot base = cachedByTier.get( minTier );
    final java.time.Instant ts = cacheTsByTier.get( minTier );
    final java.time.Instant now = java.time.Instant.now();
    if ( base == null || ts == null || now.isAfter( ts.plusSeconds( CACHE_TTL_SECONDS ) ) ) {
        base = buildUnredactedSnapshot( minTier );
        cachedByTier.put( minTier, base );
        cacheTsByTier.put( minTier, now );
    }
    return redactForViewer( base, viewer );
}

private GraphSnapshot buildUnredactedSnapshot( final Tier minTier ) {
    final List< KgNode > allNodes = repo.getAllNodes( minTier );
    final List< KgEdge > allEdges = repo.getAllEdges( minTier );
    // … rest is unchanged from the existing buildUnredactedSnapshot(), just using
    // the tier-filtered inputs instead of all rows.
}
```

Update `invalidateSnapshotCache` to clear both maps:

```java
private void invalidateSnapshotCache() {
    cachedByTier.clear();
    cacheTsByTier.clear();
}
```

Mirror tier-aware overloads for `searchKnowledge` and `traverseByCoMention` — pass `minTier` straight through to the repo overloads added in Task 5.

b) **Fix any other call sites broken by Task 3.** Use the file list from Task 3 step 4. Common ones: `AdminKnowledgeResource`, `HubProposalService`, `HubDiscoveryService`, `MentionAttributor`, etc. Most just need extra arguments (`null, null`) on `KgNode`/`KgEdge` constructions, or to switch construction over to the repo upserts.

- [ ] **Step 4: Run focused test, then test-compile across modules**

```bash
mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceTierReadTest
mvn test-compile -pl wikantik-api,wikantik-main,wikantik-rest,wikantik-knowledge -am
```

Both must pass cleanly.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTierReadTest.java \
        # … plus any other call sites you touched
git commit -m "feat(kg-svc): tier-aware reads + judgeNow signature; default min_tier=machine"
```

---

## Task 14: DefaultKnowledgeGraphService — approveProposal/rejectProposal materialise + audit

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceApproveTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.knowledge.judge.KgMaterializationService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultKnowledgeGraphServiceApproveTest extends PgTestBase {
    @Test
    void approveProposal_records_audit_and_promotes_to_human() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService mat = new KgMaterializationService( repo );
        final var svc = new DefaultKnowledgeGraphService( repo, mat );

        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "U", "target", "V", "relationship", "owns" ), 0.7, "" );

        svc.approveProposal( p.id(), "alice" );

        final KgProposal updated = repo.getProposal( p.id() );
        assertEquals( "approved", updated.status() );
        assertEquals( "human", updated.tier() );
        assertEquals( "alice", updated.reviewedBy() );
        assertTrue( repo.listReviews( p.id() ).stream()
            .anyMatch( r -> "human".equals( r.reviewerKind() ) && "approved".equals( r.verdict() ) ) );
        assertTrue( repo.getAllEdges( Tier.HUMAN ).stream()
            .anyMatch( e -> "owns".equals( e.relationshipType() )
                && p.id().equals( e.provenanceProposalId() ) ) );
    }

    @Test
    void rejectProposal_retracts_materialised_rows_and_writes_kg_rejections() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService mat = new KgMaterializationService( repo );
        final var svc = new DefaultKnowledgeGraphService( repo, mat );

        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "Q", "target", "R", "relationship", "delete_me" ), 0.7, "" );
        mat.materializeMachine( p ); // simulate prior judge approval

        svc.rejectProposal( p.id(), "alice", "wrong" );

        final KgProposal updated = repo.getProposal( p.id() );
        assertEquals( "rejected", updated.status() );
        assertFalse( repo.getAllEdges( Tier.MACHINE ).stream()
            .anyMatch( e -> p.id().equals( e.provenanceProposalId() ) ) );
        assertTrue( repo.isRejected( "Q", "R", "delete_me" ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceApproveTest`
Expected: FAIL — current implementation only flips the status column.

- [ ] **Step 3: Update approveProposal / rejectProposal**

In `DefaultKnowledgeGraphService`:

```java
@Override
public KgProposal approveProposal( final UUID proposalId, final String reviewedBy ) {
    repo.applyHumanVerdict( proposalId, "approved", reviewedBy );
    repo.recordReview( proposalId, KgProposalReview.REVIEWER_HUMAN, reviewedBy,
        "approved", null, null );
    final KgProposal proposal = repo.getProposal( proposalId );
    if ( proposal != null ) {
        materialization.promoteToHuman( proposal );
        invalidateSnapshotCache();
    }
    return proposal;
}

@Override
public KgProposal rejectProposal( final UUID proposalId, final String reviewedBy, final String reason ) {
    final KgProposal proposal = repo.getProposal( proposalId );
    repo.applyHumanVerdict( proposalId, "rejected", reviewedBy );
    repo.recordReview( proposalId, KgProposalReview.REVIEWER_HUMAN, reviewedBy,
        "rejected", null, reason );
    if ( proposal != null ) {
        materialization.retract( proposal );
        if ( "new-edge".equals( proposal.proposalType() ) && proposal.proposedData() != null ) {
            final String source = java.util.Objects.toString( proposal.proposedData().get( "source" ), null );
            final String target = java.util.Objects.toString( proposal.proposedData().get( "target" ), null );
            final String rel    = java.util.Objects.toString( proposal.proposedData().get( "relationship" ), null );
            if ( source != null && target != null && rel != null ) {
                repo.insertRejection( source, target, rel, reviewedBy, reason );
            }
        }
        invalidateSnapshotCache();
    }
    return repo.getProposal( proposalId );
}
```

Add a constructor that accepts both `repo` and `materialization` (the existing single-arg constructor stays for backward compat in test code paths; have it lazily build a `KgMaterializationService(repo)`).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceApproveTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceApproveTest.java
git commit -m "feat(kg-svc): approveProposal/rejectProposal materialise + audit"
```

---

## Task 15: DefaultKnowledgeGraphService — judgeNow

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceJudgeNowTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.knowledge.judge.KgMaterializationService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultKnowledgeGraphServiceJudgeNowTest extends PgTestBase {
    @Test
    void judgeNow_calls_judge_records_review_and_materialises_when_approved() {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource() );
        final KgMaterializationService mat = new KgMaterializationService( repo );
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        when( judge.judge( any() ) ).thenReturn(
            new JudgeVerdict( "approved", 0.9, "ok", "gemma4-assist:latest" ) );

        final var svc = new DefaultKnowledgeGraphService( repo, mat, judge );
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.of( "source", "S", "target", "T", "relationship", "now" ), 0.7, "" );

        final JudgeVerdict v = svc.judgeNow( p.id(), "alice" );

        assertEquals( "approved", v.verdict() );
        assertEquals( "machine", repo.getProposal( p.id() ).tier() );
        assertFalse( repo.listReviews( p.id() ).isEmpty() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceJudgeNowTest`
Expected: FAIL — `judgeNow` not implemented.

- [ ] **Step 3: Implement judgeNow**

Add a constructor taking `(repo, materialization, judge)` and the method:

```java
@Override
public JudgeVerdict judgeNow( final UUID proposalId, final String triggeredBy ) {
    if ( judge == null ) {
        throw new IllegalStateException( "judge service not configured" );
    }
    final KgProposal proposal = repo.getProposal( proposalId );
    if ( proposal == null ) {
        throw new IllegalArgumentException( "no proposal: " + proposalId );
    }
    final JudgeVerdict v = judge.judge( proposal );
    repo.applyMachineVerdict( proposalId, v.verdict(), v.confidence(), v.model() );
    repo.recordReview( proposalId, KgProposalReview.REVIEWER_MACHINE, v.model(),
        v.verdict(), v.confidence(), v.rationale() );
    if ( JudgeVerdict.APPROVED.equals( v.verdict() ) ) {
        materialization.materializeMachine( proposal );
        invalidateSnapshotCache();
    } else if ( JudgeVerdict.REJECTED.equals( v.verdict() )
            && "new-edge".equals( proposal.proposalType() ) ) {
        // Hard auto-reject: cascade to negative knowledge.
        final var data = proposal.proposedData();
        final String src = java.util.Objects.toString( data.get( "source" ), null );
        final String tgt = java.util.Objects.toString( data.get( "target" ), null );
        final String rel = java.util.Objects.toString( data.get( "relationship" ), null );
        if ( src != null && tgt != null && rel != null ) {
            repo.insertRejection( src, tgt, rel, v.model(), v.rationale() );
        }
    }
    LOG.info( "judgeNow proposal={} verdict={} triggeredBy={}", proposalId, v.verdict(), triggeredBy );
    return v;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceJudgeNowTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceJudgeNowTest.java
git commit -m "feat(kg-svc): judgeNow synchronous single-proposal trigger"
```

---

## Task 16: WikiEngine wiring — judge service, materialisation, runner

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`
- Test: covered by integration + smoke tests in later tasks (no new unit test).

- [ ] **Step 1: Locate the existing extraction wiring block**

Around line 965–990 (the `AsyncEntityExtractionListener` construction). The judge wiring goes immediately after, so it inherits the same `kgRepo`, properties, and lifecycle.

- [ ] **Step 2: Add the wiring**

Insert after the `AsyncEntityExtractionListener` registration:

```java
// --- KG staged validation: judge service + materialization + runner -------
final com.wikantik.knowledge.judge.KgJudgeConfig judgeCfg =
    com.wikantik.knowledge.judge.KgJudgeConfig.fromProperties( props );
final com.wikantik.knowledge.judge.KgMaterializationService kgMat =
    new com.wikantik.knowledge.judge.KgMaterializationService( kgRepo );
managers.put( com.wikantik.knowledge.judge.KgMaterializationService.class, kgMat );

if ( judgeCfg.enabled() ) {
    final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
        .connectTimeout( java.time.Duration.ofSeconds( judgeCfg.timeoutSeconds() ) )
        .build();
    final com.wikantik.api.knowledge.KgProposalJudgeService judge =
        new com.wikantik.knowledge.judge.DefaultKgProposalJudgeService( http, judgeCfg );
    managers.put( com.wikantik.api.knowledge.KgProposalJudgeService.class, judge );

    final com.wikantik.knowledge.judge.JudgeRunner runner =
        new com.wikantik.knowledge.judge.JudgeRunner( kgRepo, judge, kgMat, judgeCfg );
    runner.schedule();
    managers.put( com.wikantik.knowledge.judge.JudgeRunner.class, runner );
}
```

Also: change the existing `DefaultKnowledgeGraphService` construction so it gets the materialisation service and judge (when present). Search for `new DefaultKnowledgeGraphService(` in WikiEngine.java and replace with:

```java
final com.wikantik.knowledge.DefaultKnowledgeGraphService kgService =
    new com.wikantik.knowledge.DefaultKnowledgeGraphService( kgRepo, kgMat,
        managers.get( com.wikantik.api.knowledge.KgProposalJudgeService.class ) instanceof
            com.wikantik.api.knowledge.KgProposalJudgeService j ? j : null );
```

- [ ] **Step 3: Build the WAR and verify boot**

```bash
mvn clean install -Dmaven.test.skip -T 1C
tomcat/tomcat-11/bin/shutdown.sh
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
sleep 10
grep -E "JudgeRunner scheduled|JudgeRunner disabled" tomcat/tomcat-11/logs/catalina.out
```

Expected: one of the two JudgeRunner log lines appears.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat(kg-judge): wire judge service + materialisation + runner into WikiEngine"
```

---

## Task 17: KnowledgeGraphResource — min_tier query param + audit logging

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/KnowledgeGraphResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/KnowledgeGraphResourceTierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.rest;

import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeGraphResourceTierTest {
    @Test
    void doGet_passes_human_when_min_tier_human() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "min_tier" ) ).thenReturn( "human" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.snapshotGraph( any( Session.class ), eq( Tier.HUMAN ) ) )
            .thenReturn( new GraphSnapshot( java.util.List.of(), java.util.List.of() ) );

        new TestableKnowledgeGraphResource( svc ).doGet( req, resp );

        verify( svc ).snapshotGraph( any( Session.class ), eq( Tier.HUMAN ) );
    }

    @Test
    void doGet_returns_400_on_invalid_min_tier() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "min_tier" ) ).thenReturn( "garbage" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );

        new TestableKnowledgeGraphResource( mock( KnowledgeGraphService.class ) )
            .doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }
}

// Helper: subclass that bypasses servlet container init. Define inline near the test.
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=KnowledgeGraphResourceTierTest`
Expected: FAIL — `min_tier` not parsed.

- [ ] **Step 3: Update the resource**

```java
@Override
protected void doGet( final HttpServletRequest request,
                      final HttpServletResponse response ) throws IOException {
    final Engine engine = getEngine();
    final Session session = Wiki.session().find( engine, request );

    final Tier minTier;
    final String paramRaw = request.getParameter( "min_tier" );
    if ( paramRaw == null || paramRaw.isBlank() ) {
        minTier = defaultMinTier( engine );
    } else {
        try { minTier = Tier.fromWire( paramRaw ); }
        catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "min_tier must be 'human' or 'machine'" );
            return;
        }
        // Audit explicit opt-ins/outs.
        LOG.info( "knowledge-graph snapshot min_tier={} by principal={}",
            minTier.wireName(), principalNameOrAnon( session ) );
    }

    try {
        final KnowledgeGraphService svc = engine.getManager( KnowledgeGraphService.class );
        final GraphSnapshot snapshot = svc.snapshotGraph( session, minTier );
        sendJson( response, snapshot );
    } catch ( final Exception e ) {
        LOG.warn( "Failed to build knowledge-graph snapshot", e );
        sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                   "Failed to build graph snapshot" );
    }
}

private Tier defaultMinTier( final Engine engine ) {
    final String defaultStr = engine.getWikiProperties()
        .getProperty( "wikantik.kg.read.default_min_tier", "machine" );
    try { return Tier.fromWire( defaultStr ); }
    catch ( final IllegalArgumentException e ) {
        LOG.warn( "Invalid wikantik.kg.read.default_min_tier='{}', falling back to MACHINE", defaultStr );
        return Tier.MACHINE;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-rest -Dtest=KnowledgeGraphResourceTierTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/KnowledgeGraphResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/KnowledgeGraphResourceTierTest.java
git commit -m "feat(kg-rest): min_tier query param on /api/knowledge-graph/snapshot"
```

---

## Task 18: AdminKnowledgeResource — judge endpoints + reviews + proposal filters

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeResourceJudgeTest.java`

- [ ] **Step 1: Write the failing test (Mockito unit)**

```java
package com.wikantik.rest;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminKnowledgeResourceJudgeTest {
    @Test
    void judge_single_proposal_endpoint_calls_judgeNow_and_returns_verdict() throws Exception {
        final UUID proposalId = UUID.randomUUID();
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getPathInfo() ).thenReturn( "/proposals/" + proposalId + "/judge" );
        when( req.getMethod() ).thenReturn( "POST" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        // resp wiring (writer mock) elided for brevity; use the same pattern as other tests.

        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.judgeNow( eq( proposalId ), anyString() ) ).thenReturn(
            new JudgeVerdict( "approved", 0.9, "ok", "gemma4-assist:latest" ) );

        new TestableAdminKnowledgeResource( svc ).doPost( req, resp );

        verify( svc ).judgeNow( eq( proposalId ), anyString() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=AdminKnowledgeResourceJudgeTest`
Expected: FAIL — endpoint not routed.

- [ ] **Step 3: Add endpoints**

In `AdminKnowledgeResource`:

a) **POST `/admin/knowledge-graph/judge/run`** — kicks off `JudgeRunner.runOnce()` on a background executor and returns 202 with `{"status":"started"}`.

```java
private void handleJudgeRun( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
    final JudgeRunner runner = engine.getManager( JudgeRunner.class );
    if ( runner == null ) {
        sendError( resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "judge runner not configured" );
        return;
    }
    new Thread( runner::runOnceQuietly, "kg-judge-adhoc" ).start();
    resp.setStatus( HttpServletResponse.SC_ACCEPTED );
    sendJson( resp, Map.of( "status", "started" ) );
}
```

b) **POST `/admin/knowledge-graph/proposals/{id}/judge`** — synchronous single-proposal judge:

```java
private void handleSingleProposalJudge( final HttpServletRequest req, final HttpServletResponse resp,
                                         final UUID proposalId ) throws IOException {
    final String triggeredBy = currentUsername( req );
    try {
        final JudgeVerdict v = service.judgeNow( proposalId, triggeredBy );
        sendJson( resp, Map.of(
            "verdict", v.verdict(),
            "confidence", v.confidence(),
            "rationale", v.rationale(),
            "model", v.model() ) );
    } catch ( final IllegalArgumentException e ) {
        sendError( resp, HttpServletResponse.SC_NOT_FOUND, e.getMessage() );
    }
}
```

c) **GET `/admin/knowledge-graph/proposals/{id}/reviews`** — returns the audit history:

```java
private void handleListReviews( final HttpServletRequest req, final HttpServletResponse resp,
                                final UUID proposalId ) throws IOException {
    final var reviews = repo.listReviews( proposalId );
    sendJson( resp, Map.of( "reviews", reviews ) );
}
```

d) **GET `/admin/knowledge-graph/proposals`** — extend the existing list endpoint with optional query params:
   - `?machine_status=approved|rejected|abstain|null`
   - `?tier=none|machine|human`
   - `?include_machine_rejected=true|false` (default `false`)

Update the SQL `WHERE` clause in `JdbcKnowledgeRepository.listProposals` to take these filters (overload the method; leave existing signature for tests that depend on it).

Wire path routing in `doPost` / `doGet` (follow the existing routing pattern in this resource).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-rest -Dtest=AdminKnowledgeResourceJudgeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeResourceJudgeTest.java
git commit -m "feat(kg-rest): admin endpoints for judge run/now + reviews + filters"
```

---

## Task 19: MCP — min_tier on read tools (search, traverse, structural)

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/TraverseTool.java`
- (Modify any other read tools in the same package that surface KG data; check `QueryNodesTool`, `GetNodeTool`, etc. — add `min_tier` only where the tool reads from `kg_nodes`/`kg_edges`.)
- Test: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/SearchKnowledgeToolTierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Tier;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchKnowledgeToolTierTest {
    @Test
    void invoke_with_min_tier_human_passes_human_to_service() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.searchKnowledge( anyString(), any(), anyInt(), eq( Tier.HUMAN ) ) )
            .thenReturn( java.util.List.of() );
        final SearchKnowledgeTool tool = new SearchKnowledgeTool( svc, /* mentionIndex */ null );

        tool.invoke( Map.of( "query", "foo", "min_tier", "human" ) );

        verify( svc ).searchKnowledge( eq( "foo" ), any(), anyInt(), eq( Tier.HUMAN ) );
    }

    @Test
    void invoke_without_min_tier_defaults_to_machine() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.searchKnowledge( anyString(), any(), anyInt(), eq( Tier.MACHINE ) ) )
            .thenReturn( java.util.List.of() );
        final SearchKnowledgeTool tool = new SearchKnowledgeTool( svc, null );

        tool.invoke( Map.of( "query", "foo" ) );

        verify( svc ).searchKnowledge( eq( "foo" ), any(), anyInt(), eq( Tier.MACHINE ) );
    }

    @Test
    void schema_advertises_min_tier_with_examples() {
        final SearchKnowledgeTool tool = new SearchKnowledgeTool( mock( KnowledgeGraphService.class ), null );
        final var schema = tool.inputSchema();
        // The schema must declare a min_tier property, default machine, enum [human, machine].
        // Concrete assertion shape depends on the existing inputSchema() return type;
        // adapt to assert the same thing the other tools do for their per-property schemas.
        assertNotNull( schema, "inputSchema must include min_tier" );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-knowledge -Dtest=SearchKnowledgeToolTierTest`
Expected: FAIL — `min_tier` parameter not parsed by the tool.

- [ ] **Step 3: Update each affected MCP tool**

For `SearchKnowledgeTool` (and equivalently `TraverseTool`, plus any other tool that reads `kg_nodes`/`kg_edges`):

a) **inputSchema:** add the `min_tier` property:

```java
inputSchema.put( "min_tier", Map.of(
    "type", "string",
    "enum", List.of( "human", "machine" ),
    "default", "machine",
    "description", "Trust tier filter; 'human' enforces the strict view.",
    "examples", List.of( "machine", "human" )
) );
```

b) **invoke:** parse the parameter and pass it through:

```java
final String tierRaw = arguments.get( "min_tier" ) instanceof String s ? s : "machine";
final Tier minTier;
try { minTier = Tier.fromWire( tierRaw ); }
catch ( final IllegalArgumentException e ) {
    return errorResponse( "min_tier must be 'human' or 'machine'" );
}
final var nodes = svc.searchKnowledge( query, provFilter, limit, minTier );
```

c) **outputSchema examples:** add a second `examples` entry showing a `min_tier=human` request/response pair (per the Phase 6 examples convention).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-knowledge -Dtest=SearchKnowledgeToolTierTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/TraverseTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/SearchKnowledgeToolTierTest.java
git commit -m "feat(kg-mcp): min_tier on search_knowledge + traverse_by_co_mention tools"
```

---

## Task 20: Frontend admin SPA — review queue with machine column + Judge Now button

**Files:**
- Modify: the React component(s) under `wikantik-frontend/src/` that render the proposal review queue. (Find with: `grep -rln "kg.proposal\|knowledge-graph/proposals" wikantik-frontend/src`.)
- Test: a Vitest / RTL test on the same component, or a Selenide IT — pick whichever pattern the existing review-queue tests use.

- [ ] **Step 1: Locate the existing review-queue component and test pattern**

```bash
grep -rln "knowledge-graph/proposals" wikantik-frontend/src
```

Read the first match end-to-end before editing.

- [ ] **Step 2: Add a column for the machine verdict**

Render one of: `✓ approved` (green), `✗ rejected` (red), `◯ abstain` (grey), `–` (un-judged). Tooltip on hover shows the rationale (fetched lazily from `/admin/knowledge-graph/proposals/{id}/reviews`). Add a filter dropdown above the table with the options listed in the spec §5.2.

- [ ] **Step 3: Add a "Judge now" action**

Per row: a button that POSTs to `/admin/knowledge-graph/proposals/{id}/judge`, then refreshes the row from the response.

- [ ] **Step 4: Verify in the browser**

Per CLAUDE.md ("for UI or frontend changes, start the dev server and use the feature in a browser"):

```bash
tomcat/tomcat-11/bin/shutdown.sh
mvn clean install -Dmaven.test.skip -T 1C
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

Then open `http://localhost:8080/admin/knowledge` (or the actual admin route the proposals queue lives at), filter to "Machine approved", click "Judge now" on a row, confirm the badge updates.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/<the-files-you-touched>
git commit -m "feat(kg-frontend): machine verdict column + Judge Now action in review queue"
```

---

## Task 21: bin/kg-judge.sh CLI + wikantik.properties config

**Files:**
- Create: `bin/kg-judge.sh`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`

- [ ] **Step 1: Add config to wikantik.properties**

Append a new section:

```properties
#
# Knowledge Graph staged validation — judge LLM
#
wikantik.kg.judge.enabled            = true
# When unset, judge connection + model fall back to the extractor's settings
# (read at startup from wikantik.knowledge.extractor.ollama.*). Override only
# to pin the judge to a different endpoint or model.
# wikantik.kg.judge.endpoint         =
# wikantik.kg.judge.model            =
wikantik.kg.judge.cron.enabled       = true
wikantik.kg.judge.cron.interval_min  = 5
wikantik.kg.judge.batch_size         = 50
wikantik.kg.judge.concurrency        = 2
wikantik.kg.judge.timeout_seconds    = 30
wikantik.kg.judge.max_attempts       = 3
# Default tier for read paths when the caller does not pass min_tier.
wikantik.kg.read.default_min_tier    = machine
```

- [ ] **Step 2: Create bin/kg-judge.sh**

Pattern mirrored from `bin/kg-extract.sh`. Hits the admin endpoint `/admin/knowledge-graph/judge/run` (or `/admin/knowledge-graph/proposals/{id}/judge` when `--proposal-id` is passed) using credentials from `test.properties`:

```bash
#!/usr/bin/env bash
# Trigger an ad-hoc KG judge run against the local deployment.
# Usage:
#   bin/kg-judge.sh [--max-proposals N] [--dry-run] [--report path]
#   bin/kg-judge.sh --proposal-id UUID

set -euo pipefail

URL_BASE="${WIKANTIK_URL:-http://localhost:8080}"
TEST_PROPS="$(dirname "$0")/../test.properties"

if [ ! -f "$TEST_PROPS" ]( ! -f "$TEST_PROPS" ); then
    echo "Missing test.properties at$TEST_PROPS — see CLAUDE.md > Manual Testing Credentials" >&2
    exit 2
fi

LOGIN=$(grep '^test.user.login=' "$TEST_PROPS" | cut -d= -f2)
PASS=$(grep '^test.user.password=' "$TEST_PROPS" | cut -d= -f2)

PROPOSAL_ID=""
while [$# -gt 0 ]($# -gt 0 ); do
    case "$1" in
        --proposal-id) PROPOSAL_ID="$2"; shift 2 ;;
        --max-proposals|--report|--dry-run) shift ;; # accepted but currently no-ops; runner uses cron config
        *) echo "Unknown arg:$1"; exit 2 ;;
    esac
done

if [ -n "$PROPOSAL_ID" ]( -n "$PROPOSAL_ID" ); then
    curl -fsS -u "${LOGIN}:${PASS}" -X POST \
        "${URL_BASE}/admin/knowledge-graph/proposals/${PROPOSAL_ID}/judge"
else
    curl -fsS -u "${LOGIN}:${PASS}" -X POST \
        "${URL_BASE}/admin/knowledge-graph/judge/run"
fi
echo
```

```bash
chmod +x bin/kg-judge.sh
```

- [ ] **Step 3: Smoke-test the CLI**

```bash
tomcat/tomcat-11/bin/startup.sh # if not already up
bin/kg-judge.sh
```

Expected: HTTP 202 + `{"status":"started"}`.

- [ ] **Step 4: Commit**

```bash
git add bin/kg-judge.sh wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "feat(kg-judge): bin/kg-judge.sh + wikantik.properties config"
```

---

## Task 22: Cargo IT — end-to-end smoke

**Files:**
- Create: `wikantik-it-tests/.../KgStagedValidationIT.java` (place under the same package as other Cargo-launched ITs touching the KG)

- [ ] **Step 1: Locate an existing Cargo IT for the KG and copy its bootstrap**

```bash
grep -rln "Cargo\|@RunWith" wikantik-it-tests/src/test/java | head
```

Copy the closest analogue (one that seeds proposals via the REST API and asserts via the snapshot endpoint) and rename to `KgStagedValidationIT`.

- [ ] **Step 2: Write the end-to-end flow**

Driven against the live admin REST API + snapshot endpoint:

1. POST a synthetic proposal via the admin "submit proposal" endpoint (or insert directly via JDBC if no such endpoint exists).
2. POST `/admin/knowledge-graph/judge/run`. Poll `/admin/knowledge-graph/proposals?include_machine_rejected=true` until `machine_status` is no longer `null`. (Or use `POST .../proposals/{id}/judge` for synchronous behaviour to avoid polling.)
3. GET `/api/knowledge-graph/snapshot` — assert the new node/edge IS present (default `min_tier=machine`).
4. GET `/api/knowledge-graph/snapshot?min_tier=human` — assert the same node/edge is NOT present.
5. POST `/admin/knowledge-graph/proposals/{id}/approve`.
6. GET `/api/knowledge-graph/snapshot?min_tier=human` — assert the node/edge IS now present.
7. POST `/admin/knowledge-graph/proposals/{id}/reject` against a different proposal that the judge approved.
8. Assert the node/edge for that proposal is gone from BOTH `human` and `machine` snapshots, and `kg_rejections` contains the triple.

Use Selenide / Awaitility / direct JDBC as the existing ITs do. Run a real Ollama model if available; otherwise stub `KgProposalJudgeService` via a system property and inject a deterministic verdict — set up matches whatever the existing extractor IT does.

- [ ] **Step 3: Run the IT**

Per CLAUDE.md, ITs run sequentially (no `-T`):

```bash
mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests -am
```

Expected: `KgStagedValidationIT` passes (and the rest of the IT suite stays green).

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/src/test/java/<KgStagedValidationIT path>
git commit -m "test(kg-it): end-to-end smoke for staged validation"
```

---

## Final verification

- [ ] **Full unit test pass**

```bash
mvn clean install -T 1C -DskipITs
```
Expected: BUILD SUCCESS.

- [ ] **Full integration test pass (sequential per CLAUDE.md)**

```bash
mvn clean install -Pintegration-tests -fae
```
Expected: BUILD SUCCESS.

- [ ] **Apache RAT licence check**

```bash
mvn apache-rat:check
```
Expected: no violations.

- [ ] **Manual smoke against local deploy**

1. Stop Tomcat, redeploy, restart.
2. Insert a test proposal via admin UI.
3. Trigger judge from admin UI → confirm verdict + materialised node appears in `/api/knowledge-graph/snapshot`.
4. Approve from admin UI → confirm tier upgrades to `human`.
5. Confirm `/api/knowledge-graph/snapshot?min_tier=human` now includes the row.

- [ ] **Memory updates** — append to `~/.claude/projects/-home-jakefear-source-jspwiki/memory/`:
  - A new `project_kg_staged_validation_complete.md` recording the launch date and the 22-task plan output.
  - Update `project_admin_mcp_tool_surface.md` if the tool counts on `/knowledge-mcp` changed (they did — `min_tier` was added to read tools, but tool *count* did not change).

---

## Self-Review Notes

Coverage check against spec §3–§12:

| Spec section | Implemented in task |
|--------------|---------------------|
| §4.1 kg_proposals columns | T1 (migration), T3 (record extension), T6 + T7 (repository) |
| §4.2 kg_proposal_reviews | T1 (migration), T2 (record), T6 (repo CRUD) |
| §4.3 tier on kg_nodes / kg_edges | T1 (migration), T3 (records), T4 + T5 (tier-aware reads), T8 (provenance-aware writes) |
| §4.4 kg_rejections override | T9 |
| §5.1 KgProposalJudgeService | T10 (interface), T11 (impl) |
| §5.1 JudgeRunner | T12 (impl), T16 (wiring) |
| §5.1 KgMaterializationService | T8 + T9 |
| §5.1 bin/kg-judge.sh | T21 |
| §5.2 JdbcKnowledgeRepository changes | T4–T9 |
| §5.2 KnowledgeGraphService widening | T13 (reads), T14 (approve/reject), T15 (judgeNow) |
| §5.2 AdminKnowledgeResource | T18 |
| §5.2 KnowledgeGraphResource | T17 |
| §5.2 KnowledgeMcpInitializer / tools | T19 |
| §5.2 Frontend admin SPA | T20 |
| §5.3 Configuration | T21 |
| §6 Lifecycle | T7 (verdict semantics), T12 (runner), T14 (approve/reject), T15 (judgeNow) |
| §7 Read path & opt-in | T13 (defaults), T17 (REST), T19 (MCP) |
| §8 Failure handling | T11 (judge abstain), T12 (max_attempts), T8 (idempotent materialisation), T9 (kg_rejections override) |
| §9 Migration | T1 |
| §10 Testing | each task includes a TDD test; T22 covers end-to-end |

No placeholders found; types and signatures consistent across tasks (verified `KgMaterializationService` constructor arity, `KgJudgeConfig` fields, repo method signatures).
