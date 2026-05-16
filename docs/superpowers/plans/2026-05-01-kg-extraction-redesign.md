# KG Extraction Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-chunk entity-extraction pipeline (43,856 mostly-noise pending proposals, day+ runtime) with a per-page extraction pipeline that produces ~200–500 high-quality, deduplicated, evidence-grounded proposals in ~3.6 hours.

**Architecture:** Six independently-shippable phases. Phase 0 lands DB schema (signature column + partial unique index, node-embedding cache). Phases 1–2 add the new `PageExtractor` / `ProposalConsolidator` / `ProposalJudge` interfaces and components, all unit-tested in isolation. Phase 3 refactors `BootstrapEntityExtractionIndexer` internals to drive the new pipeline; the save-time `AsyncEntityExtractionListener` stays in place (it's a separate, out-of-scope path). Phase 4 reshapes the CLI. Phase 5 wipes legacy proposals and runs the new pipeline. Phase 6 adds optional Ollama / Claude judges plus the experiment harness.

**Tech Stack:** Java 21, Maven, PostgreSQL 15 + pgvector, Ollama (`gemma4-assist:latest`, `bge-m3:latest`), JUnit 5, Mockito, log4j2, Micrometer, Cargo + WireMock for IT.

**Spec:** `docs/superpowers/specs/2026-05-01-kg-extraction-redesign-design.md`

---

## File Structure (locked in here, referenced by tasks)

**New files (`wikantik-api`):**
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/Page.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/PageExtractor.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/PageExtractionResult.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/ExtractedEntity.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/ExtractedRelation.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/NodeSignature.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/EdgeSignature.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/ConsolidatedProposal.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/SupportEvidence.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/ProposalJudge.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/JudgeContext.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/Verdict.java`

**New files (`wikantik-main`):**
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/EvidenceGroundingVerifier.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/PageExtractionPromptBuilder.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/PageExtractionResponseParser.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/OllamaPageExtractor.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ProposalConsolidator.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ProposalUpserter.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/MentionAttributor.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/NoOpProposalJudge.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/OllamaProposalJudge.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ClaudeProposalJudge.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/embedding/KgNodeEmbeddingRepository.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/embedding/KgNodeEmbeddingService.java`

**Modified files (`wikantik-main`):**
- `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java` — add `upsertConsolidatedProposal`
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexer.java` — replace chunk-driven internals; **keep** `AsyncEntityExtractionListener` (used by save-time)

**New files (`wikantik-extract-cli`):**
- `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/JudgeExperimentCli.java`

**Modified files (`wikantik-extract-cli`):**
- `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java` — drop chunk-tuning flags, add new flags

**Migrations:**
- `bin/db/migrations/V020__kg_proposals_signature.sql`
- `bin/db/migrations/V021__kg_node_embeddings.sql`

**Scripts:**
- `bin/kg-judge-experiment.sh` (new)
- `bin/kg-chunker-stats.sh` (new — receives the moved `--chunker-stats-only` mode)
- `bin/kg-extract.sh` (modified — new flags, retire old)

**New tests follow the same package paths under `src/test/java/`. IT additions land in `wikantik-it-tests/wikantik-it-test-rest/` (which already runs Cargo + Postgres).**

---

## Conventions

- All steps are TDD: write the failing test first, run it red, implement minimum, run it green, commit. **Never skip the red step** — a test that goes straight to green proves nothing.
- Commits are small. Each task ends with one commit. **Never use `git add -A`**; stage by exact paths.
- Run from repo root unless a step says otherwise. Use `mvn -pl <module> -am test -Dtest=ClassName` for fast unit-test iteration; full builds wait until task end.
- Per CLAUDE.md: no empty catch blocks, log with context (`LOG.warn("...", e.getMessage())`), prefer single-line `LOG.warn` calls.
- Per CLAUDE.md: integration tests run sequentially — never use `-T` with `-Pintegration-tests`. Always pair with `-fae`.

---

# Phase 0 — Schema migrations

### Task 0.1: V020 migration — `kg_proposals` signature column + partial unique index

**Files:**
- Create: `bin/db/migrations/V020__kg_proposals_signature.sql`

- [ ] **Step 1: Write the migration SQL**

```sql
-- V020: Idempotency for kg_proposals. Same logical proposal arriving from a
-- re-run (or from multiple chunks of the same page) becomes an UPSERT that
-- merges support evidence, not a second row. Pending-only — reviewed history
-- (approved/rejected) is allowed to repeat.

ALTER TABLE kg_proposals
  ADD COLUMN IF NOT EXISTS signature       VARCHAR(64),
  ADD COLUMN IF NOT EXISTS support         JSONB DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS support_count   INT  DEFAULT 0,
  ADD COLUMN IF NOT EXISTS first_seen_at   TIMESTAMP DEFAULT NOW(),
  ADD COLUMN IF NOT EXISTS last_seen_at    TIMESTAMP DEFAULT NOW();

CREATE UNIQUE INDEX IF NOT EXISTS kg_proposals_pending_signature_uq
  ON kg_proposals (signature)
  WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS kg_proposals_signature_idx ON kg_proposals (signature);

-- Permissions: re-grant in case the column-level defaults vary.
GRANT SELECT, INSERT, UPDATE, DELETE ON kg_proposals TO :app_user;
```

- [ ] **Step 2: Apply migration locally**

Run: `bin/db/migrate.sh`
Expected: `Applied V020__kg_proposals_signature.sql` line in output, exit 0.

- [ ] **Step 3: Verify idempotency by re-applying**

Run: `bin/db/migrate.sh`
Expected: V020 reported as already-applied (no-op), exit 0.

- [ ] **Step 4: Smoke-test the unique index**

```bash
ROOT_XML=tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml
pw=$(grep -oE 'password="[^"]+"' "$ROOT_XML" | head -1 | sed -E 's/password="([^"]+)"/\1/')
PGPASSWORD="$pw" psql -h localhost -U jspwiki -d jspwiki <<'SQL'
INSERT INTO kg_proposals (proposal_type, source_page, proposed_data, signature)
VALUES ('new-node', 'TestPage', '{"name":"X"}'::jsonb, 'sig-test-001');

INSERT INTO kg_proposals (proposal_type, source_page, proposed_data, signature)
VALUES ('new-node', 'TestPage', '{"name":"X"}'::jsonb, 'sig-test-001');
SQL
```

Expected: second INSERT fails with `duplicate key value violates unique constraint "kg_proposals_pending_signature_uq"`. Cleanup: `DELETE FROM kg_proposals WHERE signature = 'sig-test-001';`.

- [ ] **Step 5: Commit**

```bash
git add bin/db/migrations/V020__kg_proposals_signature.sql
git commit -m "db(V020): add kg_proposals signature column + partial unique index

Pending-only unique index on signature lets the upcoming consolidator
upsert support evidence into a single row instead of N rows.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 0.2: V021 migration — `kg_node_embeddings` cache table

**Files:**
- Create: `bin/db/migrations/V021__kg_node_embeddings.sql`

- [ ] **Step 1: Write the migration SQL**

```sql
-- V021: Cache for KG-node-level bge-m3 embeddings. Used by the page-extractor
-- to build a per-page top-K dictionary instead of the alphabetical-200 we
-- have today. Re-runs are a no-op when content_hash is unchanged.

CREATE TABLE IF NOT EXISTS kg_node_embeddings (
    node_id      UUID         PRIMARY KEY REFERENCES kg_nodes(id) ON DELETE CASCADE,
    content_hash VARCHAR(64)  NOT NULL,
    embedding    vector(1024) NOT NULL,
    embedded_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS kg_node_embeddings_ivfflat_idx
  ON kg_node_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 50);

GRANT SELECT, INSERT, UPDATE, DELETE ON kg_node_embeddings TO :app_user;
```

- [ ] **Step 2: Apply locally**

Run: `bin/db/migrate.sh`
Expected: `Applied V021__kg_node_embeddings.sql`, exit 0.

- [ ] **Step 3: Verify table exists and ivfflat index is built**

```bash
PGPASSWORD="$pw" psql -h localhost -U jspwiki -d jspwiki -c "\d kg_node_embeddings"
```

Expected: shows columns `node_id, content_hash, embedding, embedded_at` and the `ivfflat` index.

- [ ] **Step 4: Re-apply for idempotency**

Run: `bin/db/migrate.sh`
Expected: V021 reported as already-applied, exit 0.

- [ ] **Step 5: Commit**

```bash
git add bin/db/migrations/V021__kg_node_embeddings.sql
git commit -m "db(V021): add kg_node_embeddings cache table

Stores bge-m3 embeddings keyed by (node_id, content_hash) so re-runs of
the extractor only re-embed nodes whose name/type/source changed.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Phase 1 — API types

These four tasks add records and interfaces to `wikantik-api`. They compile but aren't used by production code yet. Tests assert equality / hashing / signature-stability invariants so consolidation works correctly when components arrive in Phase 2.

### Task 1.1: NodeSignature + EdgeSignature

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/NodeSignature.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/EdgeSignature.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/knowledge/NodeSignatureTest.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/knowledge/EdgeSignatureTest.java`

- [ ] **Step 1: Write failing tests**

`NodeSignatureTest.java`:

```java
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeSignatureTest {

    @Test
    void normalizesNameAndType() {
        // Whitespace, case, surrounding punctuation all collapse.
        NodeSignature a = NodeSignature.of(" GitHub ", "Organization");
        NodeSignature b = NodeSignature.of("github", "organization");
        NodeSignature c = NodeSignature.of("GitHub.", "ORGANIZATION");
        assertEquals(a.asHash(), b.asHash());
        assertEquals(a.asHash(), c.asHash());
    }

    @Test
    void distinguishesDifferentNames() {
        // No fuzzy-matching: 'Spark' and 'Apache Spark' must NOT collapse.
        NodeSignature a = NodeSignature.of("Spark", "Technology");
        NodeSignature b = NodeSignature.of("Apache Spark", "Technology");
        assertNotEquals(a.asHash(), b.asHash());
    }

    @Test
    void distinguishesDifferentTypes() {
        NodeSignature a = NodeSignature.of("Java", "Technology");
        NodeSignature b = NodeSignature.of("Java", "Place");      // The island
        assertNotEquals(a.asHash(), b.asHash());
    }

    @Test
    void hashIsStableAcrossJvms() {
        // SHA-256 hex digest, deterministic.
        assertEquals(NodeSignature.of("Kafka", "Technology").asHash(),
                     NodeSignature.of("Kafka", "Technology").asHash());
        assertEquals(64, NodeSignature.of("Kafka", "Technology").asHash().length());
    }

    @Test
    void nfcUnicodeEquivalence() {
        // "café" composed (1 char é) vs decomposed (e + combining acute) must match.
        String composed = "Café";
        String decomposed = "Café";
        assertEquals(NodeSignature.of(composed, "Place").asHash(),
                     NodeSignature.of(decomposed, "Place").asHash());
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
            () -> NodeSignature.of("", "Concept"));
        assertThrows(IllegalArgumentException.class,
            () -> NodeSignature.of("   ", "Concept"));
    }
}
```

`EdgeSignatureTest.java`:

```java
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EdgeSignatureTest {

    @Test
    void normalizesEndpointsAndPredicate() {
        EdgeSignature a = EdgeSignature.of("Python", "Programming Language", "is_a");
        EdgeSignature b = EdgeSignature.of("python", "programming language", "IS-A");
        assertEquals(a.asHash(), b.asHash());
    }

    @Test
    void predicateSynonymsCollapse() {
        EdgeSignature a = EdgeSignature.of("Python", "Guido", "created_by");
        EdgeSignature b = EdgeSignature.of("Python", "Guido", "created-by");
        assertEquals(a.asHash(), b.asHash());
    }

    @Test
    void direction_matters() {
        // (A -> B) is NOT the same as (B -> A)
        EdgeSignature a = EdgeSignature.of("Python", "Guido", "created_by");
        EdgeSignature b = EdgeSignature.of("Guido", "Python", "created_by");
        assertNotEquals(a.asHash(), b.asHash());
    }

    @Test
    void distinguishesPredicates() {
        EdgeSignature a = EdgeSignature.of("Kafka", "Confluent", "owned_by");
        EdgeSignature b = EdgeSignature.of("Kafka", "Confluent", "competes_with");
        assertNotEquals(a.asHash(), b.asHash());
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run: `mvn -pl wikantik-api test -Dtest=NodeSignatureTest,EdgeSignatureTest -q`
Expected: compilation errors (`NodeSignature`/`EdgeSignature` don't exist) or test failures.

- [ ] **Step 3: Implement NodeSignature**

`NodeSignature.java`:

```java
package com.wikantik.api.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Canonical signature for a node proposal. Two proposals with the same
 * (normalized name, normalized type) collapse into the same row in
 * {@code kg_proposals} via the partial unique index on {@code signature}.
 *
 * <p>Normalization rules: NFC-normalize, trim, collapse internal whitespace,
 * strip surrounding punctuation, lower-case for comparison. The original
 * casing is preserved by the consolidator's display-name vote — this class
 * cares only about identity.
 */
public record NodeSignature(String normalizedName, String normalizedType) {

    public NodeSignature {
        if (normalizedName == null || normalizedName.isBlank()) {
            throw new IllegalArgumentException("normalizedName must not be blank");
        }
        if (normalizedType == null || normalizedType.isBlank()) {
            throw new IllegalArgumentException("normalizedType must not be blank");
        }
    }

    public static NodeSignature of(String name, String type) {
        return new NodeSignature(normalize(name), normalize(type));
    }

    public String asHash() {
        return sha256Hex("node:" + normalizedName + "|" + normalizedType);
    }

    static String normalize(String s) {
        if (s == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        String nfc = Normalizer.normalize(s, Normalizer.Form.NFC);
        String trimmed = nfc.trim();
        // Strip surrounding punctuation.
        trimmed = trimmed.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
        // Collapse internal whitespace.
        String collapsed = trimmed.replaceAll("\\s+", " ");
        return collapsed.toLowerCase(Locale.ROOT);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

`EdgeSignature.java`:

```java
package com.wikantik.api.knowledge;

import java.util.Locale;
import java.util.Map;

/**
 * Canonical signature for an edge proposal. Combines normalized source,
 * target, and a normalized predicate (with a small synonym map collapsing
 * underscore vs hyphen variants of common predicates).
 */
public record EdgeSignature(String normalizedSource,
                            String normalizedTarget,
                            String normalizedPredicate) {

    private static final Map<String, String> PREDICATE_SYNONYMS = Map.of(
        "is_a",        "is-a",
        "created_by",  "created-by",
        "part_of",     "part-of",
        "depends_on",  "depends-on",
        "located_in",  "located-in",
        "used_by",     "used-by",
        "owned_by",    "owned-by",
        "competes_with","competes-with"
    );

    public EdgeSignature {
        if (normalizedSource == null || normalizedSource.isBlank()) {
            throw new IllegalArgumentException("normalizedSource must not be blank");
        }
        if (normalizedTarget == null || normalizedTarget.isBlank()) {
            throw new IllegalArgumentException("normalizedTarget must not be blank");
        }
        if (normalizedPredicate == null || normalizedPredicate.isBlank()) {
            throw new IllegalArgumentException("normalizedPredicate must not be blank");
        }
    }

    public static EdgeSignature of(String source, String target, String predicate) {
        return new EdgeSignature(NodeSignature.normalize(source),
                                 NodeSignature.normalize(target),
                                 normalizePredicate(predicate));
    }

    public String asHash() {
        return NodeSignature.sha256Hex("edge:" + normalizedSource + "|" + normalizedTarget
                                       + "|" + normalizedPredicate);
    }

    static String normalizePredicate(String predicate) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must not be null");
        }
        String lower = predicate.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        return PREDICATE_SYNONYMS.getOrDefault(lower, lower);
    }
}
```

- [ ] **Step 4: Run tests, verify they pass**

Run: `mvn -pl wikantik-api test -Dtest=NodeSignatureTest,EdgeSignatureTest -q`
Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/NodeSignature.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/EdgeSignature.java \
        wikantik-api/src/test/java/com/wikantik/api/knowledge/NodeSignatureTest.java \
        wikantik-api/src/test/java/com/wikantik/api/knowledge/EdgeSignatureTest.java
git commit -m "api: add NodeSignature + EdgeSignature with NFC + predicate-synonym normalization

Pure data records keyed by SHA-256 of canonical (name|type) for nodes and
(source|target|predicate) for edges. Used by the upcoming consolidator to
collapse duplicate proposals.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 1.2: SupportEvidence + ConsolidatedProposal + Verdict

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/SupportEvidence.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/ConsolidatedProposal.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/Verdict.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/knowledge/ConsolidatedProposalTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConsolidatedProposalTest {

    @Test
    void newNode_holdsTypeAndName() {
        SupportEvidence e = new SupportEvidence("Pg1", "Python is a language.", 0.9, "ollama:gemma4");
        ConsolidatedProposal p = ConsolidatedProposal.newNode(
            "sig123", "Python", "Technology", List.of(e), 0.9);
        assertEquals(ConsolidatedProposal.Kind.NEW_NODE, p.kind());
        assertEquals("Python", p.displayName());
        assertEquals("Technology", p.type());
        assertNull(p.source());
        assertEquals(1, p.support().size());
    }

    @Test
    void newEdge_holdsTriple() {
        SupportEvidence e = new SupportEvidence("Pg1", "Python created by Guido.", 0.95, "ollama:gemma4");
        ConsolidatedProposal p = ConsolidatedProposal.newEdge(
            "sig456", "Python", "Guido van Rossum", "created-by", List.of(e), 0.95);
        assertEquals(ConsolidatedProposal.Kind.NEW_EDGE, p.kind());
        assertEquals("Python", p.source());
        assertEquals("Guido van Rossum", p.target());
        assertEquals("created-by", p.predicate());
        assertNull(p.type());
    }

    @Test
    void verdict_acceptCarriesConfidenceAndRationale() {
        Verdict.Accept a = new Verdict.Accept(0.8, "evidence is solid");
        assertEquals(0.8, a.finalConfidence());
        assertEquals("evidence is solid", a.rationale());
    }

    @Test
    void verdict_rejectCarriesReasonCode() {
        Verdict.Reject r = new Verdict.Reject("ungrounded", "quote not in page");
        assertEquals("ungrounded", r.reasonCode());
    }
}
```

- [ ] **Step 2: Run, verify red**

Run: `mvn -pl wikantik-api test -Dtest=ConsolidatedProposalTest -q`
Expected: compilation errors.

- [ ] **Step 3: Implement records**

`SupportEvidence.java`:

```java
package com.wikantik.api.knowledge;

/**
 * One piece of evidence backing a consolidated proposal: which page produced
 * it, the verbatim quote (already grounded by EvidenceGroundingVerifier), the
 * model's confidence, and the extractor that emitted it.
 */
public record SupportEvidence(String sourcePage,
                              String evidenceSpan,
                              double confidence,
                              String extractorCode) {
    public SupportEvidence {
        if (sourcePage == null || sourcePage.isBlank()) {
            throw new IllegalArgumentException("sourcePage must not be blank");
        }
        if (evidenceSpan == null) {
            throw new IllegalArgumentException("evidenceSpan must not be null");
        }
        if (extractorCode == null || extractorCode.isBlank()) {
            throw new IllegalArgumentException("extractorCode must not be blank");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
    }
}
```

`ConsolidatedProposal.java`:

```java
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * The output of {@code ProposalConsolidator}. One row per logical claim.
 * Same shape regardless of {@link Kind}; node-only fields are null on edges
 * and vice versa.
 */
public record ConsolidatedProposal(
        Kind kind,
        String signature,
        String displayName,            // nodes: name; edges: null
        String type,                   // nodes only
        String source, String target,  // edges only
        String predicate,              // edges only
        List<SupportEvidence> support,
        double aggregateConfidence) {

    public enum Kind { NEW_NODE, NEW_EDGE }

    public static ConsolidatedProposal newNode(String signature, String displayName, String type,
                                                List<SupportEvidence> support, double aggregateConfidence) {
        return new ConsolidatedProposal(Kind.NEW_NODE, signature, displayName, type,
                                         null, null, null,
                                         List.copyOf(support), aggregateConfidence);
    }

    public static ConsolidatedProposal newEdge(String signature, String source, String target,
                                                String predicate, List<SupportEvidence> support,
                                                double aggregateConfidence) {
        return new ConsolidatedProposal(Kind.NEW_EDGE, signature, null, null,
                                         source, target, predicate,
                                         List.copyOf(support), aggregateConfidence);
    }
}
```

`Verdict.java`:

```java
package com.wikantik.api.knowledge;

/**
 * Output of a {@link ProposalJudge}. Sealed: every judge returns one of
 * three verdicts. {@code Reject.reasonCode} is a small closed enum
 * (see ProposalJudge contract test) used for metrics keying.
 */
public sealed interface Verdict {

    record Accept(double finalConfidence, String rationale) implements Verdict {}

    record Reject(String reasonCode, String rationale) implements Verdict {
        public Reject {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode must not be blank");
            }
        }
    }

    record Rewrite(ConsolidatedProposal rewritten, String rationale) implements Verdict {
        public Rewrite {
            if (rewritten == null) {
                throw new IllegalArgumentException("rewritten must not be null");
            }
        }
    }
}
```

- [ ] **Step 4: Run, verify green**

Run: `mvn -pl wikantik-api test -Dtest=ConsolidatedProposalTest -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/SupportEvidence.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/ConsolidatedProposal.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/Verdict.java \
        wikantik-api/src/test/java/com/wikantik/api/knowledge/ConsolidatedProposalTest.java
git commit -m "api: add ConsolidatedProposal + SupportEvidence + sealed Verdict

Output records for the upcoming consolidator and judge stages. Sealed
Verdict has Accept/Reject/Rewrite variants; Reject.reasonCode keys judge
rejection metrics.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 1.3: Page + ExtractedEntity/Relation + PageExtractionResult

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/Page.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/ExtractedEntity.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/ExtractedRelation.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/PageExtractionResult.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/knowledge/PageExtractionResultTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PageExtractionResultTest {

    @Test
    void emptyResultIsNonNullCollections() {
        PageExtractionResult empty = PageExtractionResult.empty("ollama:gemma4", "Page1", Duration.ofMillis(10));
        assertNotNull(empty.entities());
        assertNotNull(empty.relations());
        assertEquals(0, empty.entities().size());
        assertEquals(0, empty.relations().size());
        assertEquals("Page1", empty.pageName());
    }

    @Test
    void resultExposesStats() {
        PageExtractionResult.Stats stats = new PageExtractionResult.Stats(10, 5, 2, 1, Duration.ofMillis(100));
        PageExtractionResult r = new PageExtractionResult("ollama:gemma4", "Page1",
            List.of(new ExtractedEntity("Python", "Technology", "Python is...", 0.9)),
            List.of(),
            stats);
        assertEquals(10, r.stats().rawEntities());
        assertEquals(2, r.stats().rejectedUngrounded());
    }

    @Test
    void extractedEntityValidates() {
        assertThrows(IllegalArgumentException.class,
            () -> new ExtractedEntity("", "Technology", "x", 0.5));
        assertThrows(IllegalArgumentException.class,
            () -> new ExtractedEntity("X", "Technology", "x", -0.1));
    }
}
```

- [ ] **Step 2: Verify red**

Run: `mvn -pl wikantik-api test -Dtest=PageExtractionResultTest -q`
Expected: compilation failure.

- [ ] **Step 3: Implement records**

`Page.java`:

```java
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * A page presented to {@link PageExtractor#extract}. {@code pageId} is the
 * canonical_id where available; null for pages without one (the extractor
 * uses {@code name} as a fallback identifier in metrics + logs).
 */
public record Page(String name,
                   String pageId,
                   String body,
                   String summary,
                   List<String> headings) {
    public Page {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        headings = headings == null ? List.of() : List.copyOf(headings);
    }
}
```

`ExtractedEntity.java`:

```java
package com.wikantik.api.knowledge;

/**
 * One entity emitted by a {@link PageExtractor}, post-grounding. The
 * {@code evidenceSpan} is guaranteed to be a substring of the source page
 * body — extractors run the grounding verifier before returning.
 */
public record ExtractedEntity(String name, String type, String evidenceSpan, double confidence) {
    public ExtractedEntity {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (evidenceSpan == null) {
            throw new IllegalArgumentException("evidenceSpan must not be null");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
    }
}
```

`ExtractedRelation.java`:

```java
package com.wikantik.api.knowledge;

public record ExtractedRelation(String source, String target, String predicate,
                                String evidenceSpan, double confidence) {
    public ExtractedRelation {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        if (predicate == null || predicate.isBlank()) {
            throw new IllegalArgumentException("predicate must not be blank");
        }
        if (evidenceSpan == null) {
            throw new IllegalArgumentException("evidenceSpan must not be null");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
    }
}
```

`PageExtractionResult.java`:

```java
package com.wikantik.api.knowledge;

import java.time.Duration;
import java.util.List;

public record PageExtractionResult(String extractorCode,
                                    String pageName,
                                    List<ExtractedEntity> entities,
                                    List<ExtractedRelation> relations,
                                    Stats stats) {

    public PageExtractionResult {
        if (extractorCode == null || extractorCode.isBlank()) {
            throw new IllegalArgumentException("extractorCode must not be blank");
        }
        if (pageName == null || pageName.isBlank()) {
            throw new IllegalArgumentException("pageName must not be blank");
        }
        entities  = entities  == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        if (stats == null) {
            throw new IllegalArgumentException("stats must not be null");
        }
    }

    public static PageExtractionResult empty(String extractorCode, String pageName, Duration latency) {
        return new PageExtractionResult(extractorCode, pageName, List.of(), List.of(),
            new Stats(0, 0, 0, 0, latency));
    }

    public record Stats(int rawEntities,
                        int rawRelations,
                        int rejectedUngrounded,
                        int rejectedBannedName,
                        Duration latency) {
        public Stats {
            if (latency == null) {
                throw new IllegalArgumentException("latency must not be null");
            }
        }
    }
}
```

- [ ] **Step 4: Verify green**

Run: `mvn -pl wikantik-api test -Dtest=PageExtractionResultTest -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/Page.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/ExtractedEntity.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/ExtractedRelation.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/PageExtractionResult.java \
        wikantik-api/src/test/java/com/wikantik/api/knowledge/PageExtractionResultTest.java
git commit -m "api: add Page + ExtractedEntity/Relation + PageExtractionResult

Input/output records for the upcoming PageExtractor interface. Records
validate at construction; extracted spans are non-null guarantees so
downstream code doesn't have to defensively check.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 1.4: PageExtractor + ProposalJudge + JudgeContext + ExtractionContext

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/PageExtractor.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/ProposalJudge.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/JudgeContext.java`
- **Modify:** `wikantik-api/src/main/java/com/wikantik/api/knowledge/ExtractionContext.java` (existing record; add a constructor variant taking `dictionaryNodes`).

- [ ] **Step 1: Read the existing ExtractionContext**

Run: `cat wikantik-api/src/main/java/com/wikantik/api/knowledge/ExtractionContext.java`
Expected: existing record with at least `pageName, existingNodes, hints` fields.

- [ ] **Step 2: Add a smoke compile-test (no behavior under test yet)**

`wikantik-api/src/test/java/com/wikantik/api/knowledge/PageExtractorTest.java`:

```java
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class PageExtractorTest {

    @Test
    void canImplementInterfaceWithFake() {
        PageExtractor fake = new PageExtractor() {
            public String code() { return "fake"; }
            public PageExtractionResult extract(Page page, ExtractionContext ctx) {
                return PageExtractionResult.empty("fake", page.name(), Duration.ZERO);
            }
        };
        Page p = new Page("X", null, "body", "", List.of());
        ExtractionContext ctx = new ExtractionContext(p.name(), List.of(), Map.of());
        assertEquals("fake", fake.code());
        assertEquals("X", fake.extract(p, ctx).pageName());
    }

    @Test
    void canImplementJudgeWithFake() {
        ProposalJudge fake = new ProposalJudge() {
            public String code() { return "fake-judge"; }
            public Verdict judge(ConsolidatedProposal p, JudgeContext c) {
                return new Verdict.Accept(p.aggregateConfidence(), "ok");
            }
        };
        SupportEvidence e = new SupportEvidence("Pg1", "x", 0.9, "fake");
        ConsolidatedProposal p = ConsolidatedProposal.newNode("sig", "X", "Concept", List.of(e), 0.9);
        JudgeContext jc = new JudgeContext(Map.of(), List.of());
        Verdict v = fake.judge(p, jc);
        assertInstanceOf(Verdict.Accept.class, v);
    }
}
```

- [ ] **Step 3: Verify red**

Run: `mvn -pl wikantik-api test -Dtest=PageExtractorTest -q`
Expected: compilation errors.

- [ ] **Step 4: Implement interfaces**

`PageExtractor.java`:

```java
package com.wikantik.api.knowledge;

/**
 * Per-page entity / relation extractor. Implementations talk to an LLM (Ollama,
 * Claude) and return grounded, capped, schema-conforming results. Extractors
 * never throw on extraction failure — they return an empty result with stats
 * populated. Constructor errors (bad config) are still propagated.
 */
public interface PageExtractor {
    /** Stable identifier carried into chunk_entity_mentions.extractor and metrics. */
    String code();

    PageExtractionResult extract(Page page, ExtractionContext context);
}
```

`ProposalJudge.java`:

```java
package com.wikantik.api.knowledge;

/**
 * Optional verdict step that runs on consolidated proposals. Default
 * implementation in production is {@code NoOpProposalJudge} (accepts all);
 * Ollama and Claude judges are opt-in.
 *
 * <p>Closed enum of {@code Reject.reasonCode} values: ungrounded,
 * redundant_with_existing_node, wrong_type, too_generic, weak_support.
 * Implementations that need to fail-open due to internal error should
 * return {@code Accept(p.aggregateConfidence(), "judge_failed: ...")}.
 */
public interface ProposalJudge {
    String code();

    Verdict judge(ConsolidatedProposal proposal, JudgeContext context);
}
```

`JudgeContext.java`:

```java
package com.wikantik.api.knowledge;

import java.util.List;
import java.util.Map;

/**
 * Read-only context handed to a {@link ProposalJudge}: the bodies of pages
 * referenced in the proposal's support array (so the judge can verify
 * grounding), and a small neighborhood of existing nodes for canonicalization
 * decisions ("does GitHub Inc. already exist as GitHub?").
 */
public record JudgeContext(Map<String, String> sourcePageBodies,
                           List<KgNode> neighborhoodNodes) {
    public JudgeContext {
        sourcePageBodies   = sourcePageBodies   == null ? Map.of()  : Map.copyOf(sourcePageBodies);
        neighborhoodNodes  = neighborhoodNodes  == null ? List.of() : List.copyOf(neighborhoodNodes);
    }
}
```

For `ExtractionContext`: read its current shape with `cat`. If it already has `existingNodes` (a `List<KgNode>`), the test above will compile against it. **Do not break existing callers** — this record is shared with the soon-to-be-orphan `EntityExtractor` which is still wired into save-time. If the existing constructor signature is `(String pageName, List<KgNode> existingNodes, Map<String,Object> hints)`, the test compiles unchanged.

- [ ] **Step 5: Verify green**

Run: `mvn -pl wikantik-api test -Dtest=PageExtractorTest -q && mvn -pl wikantik-api test -q`
Expected: BUILD SUCCESS for both. The second command catches any record-validation tests that broke.

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/PageExtractor.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/ProposalJudge.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/JudgeContext.java \
        wikantik-api/src/test/java/com/wikantik/api/knowledge/PageExtractorTest.java
git commit -m "api: add PageExtractor + ProposalJudge interfaces with JudgeContext

Both interfaces are pure abstractions (no production impl yet). Smoke
test exercises construction with anonymous fakes to lock in the shapes
before Phase 2 implementations land.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Phase 2 — Components

Each component lands fully unit-tested. Order chosen to minimize cross-task dependencies: pure functions first, then DB-touching code, then HTTP-touching code last.

### Task 2.1: EvidenceGroundingVerifier

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/EvidenceGroundingVerifier.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/EvidenceGroundingVerifierTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.knowledge.extraction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceGroundingVerifierTest {

    private final EvidenceGroundingVerifier v = new EvidenceGroundingVerifier();

    @Test
    void verbatimMatchIsGrounded() {
        EvidenceGroundingVerifier.Decision d = v.evaluate(
            "Python created by Guido", "Programming languages: Python created by Guido in 1991.");
        assertTrue(d.grounded());
        assertEquals("ok", d.reason());
    }

    @Test
    void whitespaceVariantIsGrounded() {
        EvidenceGroundingVerifier.Decision d = v.evaluate(
            "Python  created   by Guido", "Python created by Guido in 1991.");
        assertTrue(d.grounded());
    }

    @Test
    void paraphraseIsRejected() {
        EvidenceGroundingVerifier.Decision d = v.evaluate(
            "Python invented by Guido", "Python created by Guido.");
        assertFalse(d.grounded());
        assertEquals("not_in_page", d.reason());
    }

    @Test
    void emptySpanRejected() {
        EvidenceGroundingVerifier.Decision d = v.evaluate("", "anything");
        assertFalse(d.grounded());
        assertEquals("empty_span", d.reason());
    }

    @Test
    void overlongSpanRejected() {
        String span = "x".repeat(201);
        String body = "x".repeat(500);
        EvidenceGroundingVerifier.Decision d = v.evaluate(span, body);
        assertFalse(d.grounded());
        assertEquals("span_too_long", d.reason());
    }

    @Test
    void nfcEquivalenceMatches() {
        // composed é vs decomposed e + combining acute
        String composed = "Café terrace";
        String decomposed = "Café terrace";
        EvidenceGroundingVerifier.Decision d = v.evaluate(composed,
            "We met at a Café terrace in Paris.");
        assertTrue(d.grounded());
        EvidenceGroundingVerifier.Decision d2 = v.evaluate(decomposed,
            "We met at a Café terrace in Paris.");
        assertTrue(d2.grounded());
    }
}
```

- [ ] **Step 2: Verify red**

Run: `mvn -pl wikantik-main test -Dtest=EvidenceGroundingVerifierTest -q`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.wikantik.knowledge.extraction;

import java.text.Normalizer;

/**
 * Bright-line filter against hallucinated entities/relations: an
 * evidence_span must be a verbatim substring of the source page (after NFC
 * + whitespace normalization) and shorter than 200 characters.
 *
 * <p>Pure function; no LLM, no I/O. Decisions are paired with a stable
 * reason code so {@code wikantik_kg_extractor_rejected_total{reason=...}}
 * gauges work.
 */
public final class EvidenceGroundingVerifier {

    private static final int MAX_SPAN_LEN = 200;

    public Decision evaluate(String evidenceSpan, String pageBody) {
        if (evidenceSpan == null || evidenceSpan.isBlank()) {
            return new Decision(false, "empty_span");
        }
        if (evidenceSpan.length() > MAX_SPAN_LEN) {
            return new Decision(false, "span_too_long");
        }
        String spanNorm = normalize(evidenceSpan);
        String bodyNorm = normalize(pageBody);
        return bodyNorm.contains(spanNorm)
            ? new Decision(true,  "ok")
            : new Decision(false, "not_in_page");
    }

    private static String normalize(String s) {
        if (s == null) return "";
        // NFC normalize, collapse all whitespace to single spaces.
        return Normalizer.normalize(s, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim();
    }

    public record Decision(boolean grounded, String reason) {}
}
```

- [ ] **Step 4: Verify green**

Run: `mvn -pl wikantik-main test -Dtest=EvidenceGroundingVerifierTest -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/EvidenceGroundingVerifier.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/EvidenceGroundingVerifierTest.java
git commit -m "extraction: add EvidenceGroundingVerifier (substring + len + NFC check)

Pure-function gate that rejects ungrounded evidence_spans before they
reach the consolidator. Reason codes match the metric label set.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.2: PageExtractionPromptBuilder

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/PageExtractionPromptBuilder.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/PageExtractionPromptBuilderTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PageExtractionPromptBuilderTest {

    @Test
    void systemPromptIsByteStable() {
        // Two calls return the exact same string (cache invariant).
        assertEquals(PageExtractionPromptBuilder.SYSTEM_PROMPT,
                     PageExtractionPromptBuilder.SYSTEM_PROMPT);
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("evidence_span"));
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("max 12"));
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("max 8"));
        // Banned-name list is in the prompt.
        for (String banned : List.of("Concept", "Agent", "Process", "System", "User", "Software", "Data")) {
            assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("\"" + banned + "\""),
                       "system prompt missing banned name: " + banned);
        }
        // Closed type enum is in the prompt and excludes Project.
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("Person|Organization|Place|Event|Product|Technology|Concept"));
        assertFalse(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("|Project|"));
    }

    @Test
    void userPromptIncludesPageDictionaryAndBody() {
        Page page = new Page("Kafka", null, "Kafka is a streaming platform.", "summary",
                             List.of("Overview"));
        KgNode existing = new KgNode(UUID.randomUUID(), "PostgreSQL", "Technology",
                                     "PostgreSQL", Provenance.HUMAN_AUTHORED,
                                     Map.of(), Instant.now(), Instant.now());
        ExtractionContext ctx = new ExtractionContext("Kafka", List.of(existing), Map.of());
        String prompt = PageExtractionPromptBuilder.buildUserPrompt(page, ctx);
        assertTrue(prompt.contains("Page: Kafka"));
        assertTrue(prompt.contains("Kafka is a streaming platform."));
        assertTrue(prompt.contains("PostgreSQL"));
    }

    @Test
    void emptyDictionaryOmitsKnownEntitiesSection() {
        Page page = new Page("X", null, "body", "", List.of());
        ExtractionContext ctx = new ExtractionContext("X", List.of(), Map.of());
        String prompt = PageExtractionPromptBuilder.buildUserPrompt(page, ctx);
        assertFalse(prompt.contains("Known Entities"));
    }
}
```

- [ ] **Step 2: Verify red**

Run: `mvn -pl wikantik-main test -Dtest=PageExtractionPromptBuilderTest -q`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Page;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Builds the system + user prompts for the per-page extractor. The system
 * prompt is a constant {@code String} so prompt caching has a chance of
 * hitting (Claude judge later; gemma's effect is smaller but harmless).
 */
public final class PageExtractionPromptBuilder {

    private PageExtractionPromptBuilder() {}

    public static final String SYSTEM_PROMPT =
          "You extract a small, high-quality set of named entities and relations from a single "
        + "wiki page. Output STRICT JSON only, no prose, no markdown fence:\n\n"
        + "{\n"
        + "  \"entities\": [\n"
        + "    { \"name\": str, \"type\": Person|Organization|Place|Event|Product|Technology|Concept,\n"
        + "      \"evidence_span\": str, \"confidence\": 0..1 }   // max 12\n"
        + "  ],\n"
        + "  \"relations\": [\n"
        + "    { \"source\": str, \"target\": str, \"predicate\": str,\n"
        + "      \"evidence_span\": str, \"confidence\": 0..1 }   // max 8\n"
        + "  ]\n"
        + "}\n\n"
        + "Hard rules:\n"
        + "- evidence_span MUST be a verbatim <=200-char quote from the page below. No paraphrase.\n"
        + "- Both source and target of every relation MUST appear in entities[].\n"
        + "- name MUST be a proper-noun, Title-Case canonical form. NEVER emit type-labels\n"
        + "  (\"Concept\", \"Agent\", \"Process\", \"System\", \"User\", \"Software\", \"Data\") as a name.\n"
        + "- Prefer Known Entities (below) verbatim. Only propose a brand-new entity if it is\n"
        + "  clearly named, distinct, and not in the Known list.\n"
        + "- If the page genuinely has no proper-noun entities, return empty arrays. That is correct.\n"
        + "- Reasoning is implicit in evidence_span. No \"reasoning\" field.";

    public static String buildUserPrompt(Page page, ExtractionContext ctx) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("Page: ").append(page.name()).append('\n');
        if (!page.headings().isEmpty()) {
            sb.append("Section path: ").append(String.join(" › ", page.headings())).append('\n');
        }
        String dict = formatDictionary(ctx);
        if (!dict.isEmpty()) {
            sb.append("\nKnown Entities (name :: type) — reuse these names when the page refers to them:\n")
              .append(dict).append('\n');
        }
        sb.append("\nPage body:\n---\n").append(page.body()).append("\n---\n\nReturn ONLY the JSON object.");
        return sb.toString();
    }

    private static String formatDictionary(ExtractionContext ctx) {
        if (ctx == null || ctx.existingNodes() == null || ctx.existingNodes().isEmpty()) {
            return "";
        }
        return ctx.existingNodes().stream()
            .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
            .map(PageExtractionPromptBuilder::formatNode)
            .collect(Collectors.joining("\n"));
    }

    private static String formatNode(KgNode n) {
        String type = n.nodeType() == null ? "Concept" : n.nodeType();
        return "- " + n.name() + " :: " + type.toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: Verify green**

Run: `mvn -pl wikantik-main test -Dtest=PageExtractionPromptBuilderTest -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/PageExtractionPromptBuilder.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/PageExtractionPromptBuilderTest.java
git commit -m "extraction: add PageExtractionPromptBuilder with frozen system prompt

System prompt is byte-stable (cache invariant) and embeds the closed
7-type enum + banned-name list + max 12/8 caps. User prompt formats the
retrieval-augmented dictionary plus page body.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.3: PageExtractionResponseParser

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/PageExtractionResponseParser.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/PageExtractionResponseParserTest.java`

The parser takes the raw JSON string the LLM produces, validates it against the schema, drops banned-name entities, drops relations whose endpoints aren't in `entities[]`, applies entity/relation caps (sorted by descending confidence on overflow), runs each `evidence_span` through `EvidenceGroundingVerifier`, and returns a `PageExtractionResult` with stats counters populated.

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.PageExtractionResult;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class PageExtractionResponseParserTest {

    private final PageExtractionResponseParser parser = new PageExtractionResponseParser(
        new EvidenceGroundingVerifier(), 12, 8);

    @Test
    void parsesValidJson() {
        String body = "Python created by Guido in 1991.";
        String json = "{\"entities\":["
                + "{\"name\":\"Python\",\"type\":\"Technology\",\"evidence_span\":\"Python created by Guido\",\"confidence\":0.9},"
                + "{\"name\":\"Guido\",\"type\":\"Person\",\"evidence_span\":\"created by Guido\",\"confidence\":0.85}"
                + "],\"relations\":["
                + "{\"source\":\"Python\",\"target\":\"Guido\",\"predicate\":\"created_by\",\"evidence_span\":\"Python created by Guido\",\"confidence\":0.9}"
                + "]}";
        PageExtractionResult r = parser.parse(json, "ollama:gemma4", "PythonPage", body, Duration.ofMillis(100));
        assertEquals(2, r.entities().size());
        assertEquals(1, r.relations().size());
        assertEquals(0, r.stats().rejectedUngrounded());
        assertEquals(0, r.stats().rejectedBannedName());
    }

    @Test
    void dropsBannedNameEntities() {
        String body = "Concept is everywhere. The system is humming.";
        String json = "{\"entities\":["
                + "{\"name\":\"Concept\",\"type\":\"Concept\",\"evidence_span\":\"Concept is everywhere.\",\"confidence\":1.0},"
                + "{\"name\":\"Python\",\"type\":\"Technology\",\"evidence_span\":\"system is humming\",\"confidence\":0.5}"
                + "],\"relations\":[]}";
        PageExtractionResult r = parser.parse(json, "x", "P", body, Duration.ZERO);
        assertEquals(1, r.entities().size());
        assertEquals("Python", r.entities().get(0).name());
        assertEquals(1, r.stats().rejectedBannedName());
    }

    @Test
    void dropsUngroundedEntities() {
        String body = "Real text only.";
        String json = "{\"entities\":["
                + "{\"name\":\"Python\",\"type\":\"Technology\",\"evidence_span\":\"NOT IN BODY\",\"confidence\":1.0}"
                + "],\"relations\":[]}";
        PageExtractionResult r = parser.parse(json, "x", "P", body, Duration.ZERO);
        assertEquals(0, r.entities().size());
        assertEquals(1, r.stats().rejectedUngrounded());
    }

    @Test
    void dropsRelationsWithUnknownEndpoints() {
        String body = "Python and Guido.";
        String json = "{\"entities\":["
                + "{\"name\":\"Python\",\"type\":\"Technology\",\"evidence_span\":\"Python\",\"confidence\":0.9}"
                + "],\"relations\":["
                + "{\"source\":\"Python\",\"target\":\"Ruby\",\"predicate\":\"alt\",\"evidence_span\":\"Python\",\"confidence\":0.9}"
                + "]}";
        PageExtractionResult r = parser.parse(json, "x", "P", body, Duration.ZERO);
        assertEquals(0, r.relations().size());
    }

    @Test
    void enforcesEntityCap() {
        // 14 entities, cap 12, by confidence descending.
        StringBuilder sb = new StringBuilder("{\"entities\":[");
        for (int i = 0; i < 14; i++) {
            if (i > 0) sb.append(',');
            double conf = (i + 1) / 100.0;  // 0.01..0.14
            sb.append("{\"name\":\"E").append(i)
              .append("\",\"type\":\"Concept\",\"evidence_span\":\"x\",\"confidence\":").append(conf).append("}");
        }
        sb.append("],\"relations\":[]}");
        PageExtractionResult r = parser.parse(sb.toString(), "x", "P", "x", Duration.ZERO);
        assertEquals(12, r.entities().size());
        // Top 12 by confidence — E2..E13 survive (0.03..0.14).
        assertTrue(r.entities().stream().noneMatch(e -> e.name().equals("E0")));
    }

    @Test
    void malformedJsonReturnsEmptyResult() {
        PageExtractionResult r = parser.parse("not json", "x", "P", "body", Duration.ZERO);
        assertEquals(0, r.entities().size());
        assertEquals(0, r.relations().size());
    }

    @Test
    void emptyArraysAreValid() {
        PageExtractionResult r = parser.parse("{\"entities\":[],\"relations\":[]}",
            "x", "P", "body", Duration.ZERO);
        assertEquals(0, r.entities().size());
        assertEquals(0, r.relations().size());
    }
}
```

- [ ] **Step 2: Verify red**

Run: `mvn -pl wikantik-main test -Dtest=PageExtractionResponseParserTest -q`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.wikantik.knowledge.extraction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.ExtractedEntity;
import com.wikantik.api.knowledge.ExtractedRelation;
import com.wikantik.api.knowledge.PageExtractionResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the raw JSON returned by an LLM page extractor into a
 * grounded, capped {@link PageExtractionResult}. Schema-violators are
 * silently dropped (parser is fail-open: we'd rather ship N-1 entities
 * than 0 because of one malformed item).
 */
public final class PageExtractionResponseParser {

    private static final Logger LOG = LogManager.getLogger(PageExtractionResponseParser.class);

    /** Names the model is forbidden to emit. Lower-cased on comparison. */
    private static final Set<String> BANNED_NAMES = Set.of(
        "concept", "agent", "process", "system", "user", "software", "data"
    );

    /** Closed enum of allowed types. Lower-cased on comparison. */
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "person", "organization", "place", "event", "product", "technology", "concept"
    );

    private final EvidenceGroundingVerifier verifier;
    private final int maxEntities;
    private final int maxRelations;

    public PageExtractionResponseParser(EvidenceGroundingVerifier verifier,
                                        int maxEntities, int maxRelations) {
        this.verifier = verifier;
        this.maxEntities = maxEntities;
        this.maxRelations = maxRelations;
    }

    public PageExtractionResult parse(String json, String extractorCode, String pageName,
                                       String pageBody, Duration latency) {
        int rawE = 0, rawR = 0, rejectedUngrounded = 0, rejectedBannedName = 0;
        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                return PageExtractionResult.empty(extractorCode, pageName, latency);
            }
            root = el.getAsJsonObject();
        } catch (RuntimeException e) {
            LOG.warn("PageExtractionResponseParser: malformed JSON for page '{}': {}",
                     pageName, e.getMessage());
            return PageExtractionResult.empty(extractorCode, pageName, latency);
        }

        JsonArray entityArr = arrayOrEmpty(root, "entities");
        rawE = entityArr.size();
        for (JsonElement e : entityArr) {
            if (!e.isJsonObject()) continue;
            JsonObject obj = e.getAsJsonObject();
            String name = stringOrNull(obj, "name");
            String type = stringOrNull(obj, "type");
            String span = stringOrNull(obj, "evidence_span");
            Double conf = doubleOrDefault(obj, "confidence", 0.5);
            if (name == null || type == null || span == null) continue;

            if (BANNED_NAMES.contains(name.trim().toLowerCase(Locale.ROOT))) {
                rejectedBannedName++;
                continue;
            }
            if (!ALLOWED_TYPES.contains(type.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            EvidenceGroundingVerifier.Decision d = verifier.evaluate(span, pageBody);
            if (!d.grounded()) {
                rejectedUngrounded++;
                continue;
            }
            entities.add(new ExtractedEntity(name.trim(), titleCaseType(type), span, clampConf(conf)));
        }

        // Cap by descending confidence. Stable across equal confidence by JSON insertion order.
        if (entities.size() > maxEntities) {
            entities.sort(Comparator.comparingDouble(ExtractedEntity::confidence).reversed());
            entities = new ArrayList<>(entities.subList(0, maxEntities));
        }

        // Build a name set for relation-endpoint validation.
        Set<String> entityNames = new HashSet<>();
        for (ExtractedEntity e : entities) entityNames.add(e.name().toLowerCase(Locale.ROOT));

        JsonArray relArr = arrayOrEmpty(root, "relations");
        rawR = relArr.size();
        for (JsonElement r : relArr) {
            if (!r.isJsonObject()) continue;
            JsonObject obj = r.getAsJsonObject();
            String src  = stringOrNull(obj, "source");
            String tgt  = stringOrNull(obj, "target");
            String pred = stringOrNull(obj, "predicate");
            String span = stringOrNull(obj, "evidence_span");
            Double conf = doubleOrDefault(obj, "confidence", 0.5);
            if (src == null || tgt == null || pred == null || span == null) continue;

            if (!entityNames.contains(src.trim().toLowerCase(Locale.ROOT))
             || !entityNames.contains(tgt.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            EvidenceGroundingVerifier.Decision d = verifier.evaluate(span, pageBody);
            if (!d.grounded()) {
                rejectedUngrounded++;
                continue;
            }
            relations.add(new ExtractedRelation(src.trim(), tgt.trim(), pred.trim(), span, clampConf(conf)));
        }

        if (relations.size() > maxRelations) {
            relations.sort(Comparator.comparingDouble(ExtractedRelation::confidence).reversed());
            relations = new ArrayList<>(relations.subList(0, maxRelations));
        }

        return new PageExtractionResult(extractorCode, pageName, entities, relations,
            new PageExtractionResult.Stats(rawE, rawR, rejectedUngrounded, rejectedBannedName, latency));
    }

    private static JsonArray arrayOrEmpty(JsonObject root, String key) {
        JsonElement el = root.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }

    private static String stringOrNull(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() || !el.isJsonPrimitive() ? null : el.getAsString();
    }

    private static double doubleOrDefault(JsonObject o, String key, double def) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return def;
        try {
            return el.getAsDouble();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static double clampConf(double v) {
        if (Double.isNaN(v) || v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static String titleCaseType(String t) {
        String trimmed = t.trim();
        if (trimmed.isEmpty()) return trimmed;
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1).toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: Verify green**

Run: `mvn -pl wikantik-main test -Dtest=PageExtractionResponseParserTest -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/PageExtractionResponseParser.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/PageExtractionResponseParserTest.java
git commit -m "extraction: add PageExtractionResponseParser with banned-name + cap + grounding

Drops banned names ('Concept', 'Agent', etc.), enforces the closed type
enum, validates relation endpoints exist in entities[], caps results by
descending confidence, and applies the grounding verifier per item.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.4: ProposalConsolidator

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ProposalConsolidator.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/ProposalConsolidatorTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProposalConsolidatorTest {

    private final ProposalConsolidator consolidator = new ProposalConsolidator();

    @Test
    void duplicateEntitiesCollapseToOneProposalWithMultipleSupport() {
        PageExtractionResult r1 = pageResult("Page1", "Python is a language.",
            new ExtractedEntity("Python", "Technology", "Python is a language.", 0.9));
        PageExtractionResult r2 = pageResult("Page2", "I learned Python.",
            new ExtractedEntity("python", "technology", "I learned Python.", 0.8));
        List<ConsolidatedProposal> out = consolidator.consolidate(List.of(r1, r2).stream());
        assertEquals(1, out.size());
        ConsolidatedProposal p = out.get(0);
        assertEquals(ConsolidatedProposal.Kind.NEW_NODE, p.kind());
        assertEquals(2, p.support().size());
        // Display name picks the most-frequent original casing — tie here, takes first encountered "Python"
        assertEquals("Python", p.displayName());
    }

    @Test
    void displayNameVoteWinsByCount() {
        PageExtractionResult r1 = pageResult("P1", "GitHub", new ExtractedEntity("GitHub", "Organization", "GitHub", 0.9));
        PageExtractionResult r2 = pageResult("P2", "GitHub", new ExtractedEntity("GitHub", "Organization", "GitHub", 0.9));
        PageExtractionResult r3 = pageResult("P3", "github", new ExtractedEntity("github", "Organization", "github", 0.9));
        List<ConsolidatedProposal> out = consolidator.consolidate(List.of(r1, r2, r3).stream());
        assertEquals(1, out.size());
        assertEquals("GitHub", out.get(0).displayName());  // 2 votes vs 1
    }

    @Test
    void differentTypesProduceSeparateProposals() {
        PageExtractionResult r1 = pageResult("P1", "Java island", new ExtractedEntity("Java", "Place", "Java island", 0.9));
        PageExtractionResult r2 = pageResult("P2", "Java code", new ExtractedEntity("Java", "Technology", "Java code", 0.9));
        List<ConsolidatedProposal> out = consolidator.consolidate(List.of(r1, r2).stream());
        assertEquals(2, out.size());
    }

    @Test
    void edgesConsolidateOnSourceTargetPredicate() {
        PageExtractionResult r1 = pageResult("P1", "Python created by Guido.",
            new ExtractedEntity("Python", "Technology", "Python", 0.9),
            new ExtractedEntity("Guido", "Person", "Guido", 0.9));
        r1 = withRelation(r1, new ExtractedRelation("Python", "Guido", "created_by", "Python created by Guido.", 0.95));
        PageExtractionResult r2 = pageResult("P2", "Python was created by Guido in 1991.",
            new ExtractedEntity("Python", "Technology", "Python", 0.9),
            new ExtractedEntity("Guido", "Person", "Guido", 0.9));
        r2 = withRelation(r2, new ExtractedRelation("python", "guido", "created-by", "Python was created by Guido", 0.85));
        List<ConsolidatedProposal> out = consolidator.consolidate(List.of(r1, r2).stream());
        // 2 nodes (Python, Guido) + 1 edge (created-by, deduped via predicate-synonym) = 3
        assertEquals(3, out.size());
        ConsolidatedProposal edge = out.stream().filter(p -> p.kind() == ConsolidatedProposal.Kind.NEW_EDGE).findFirst().orElseThrow();
        assertEquals(2, edge.support().size());
    }

    @Test
    void aggregateConfidenceIsMeanOfSupports() {
        PageExtractionResult r1 = pageResult("P1", "x", new ExtractedEntity("X", "Concept", "x", 1.0));
        PageExtractionResult r2 = pageResult("P2", "x", new ExtractedEntity("X", "Concept", "x", 0.5));
        List<ConsolidatedProposal> out = consolidator.consolidate(List.of(r1, r2).stream());
        assertEquals(0.75, out.get(0).aggregateConfidence(), 0.001);
    }

    private PageExtractionResult pageResult(String page, String body, ExtractedEntity... ents) {
        return new PageExtractionResult("ollama:fake", page, List.of(ents), List.of(),
            new PageExtractionResult.Stats(ents.length, 0, 0, 0, Duration.ZERO));
    }

    private PageExtractionResult withRelation(PageExtractionResult r, ExtractedRelation rel) {
        return new PageExtractionResult(r.extractorCode(), r.pageName(), r.entities(), List.of(rel), r.stats());
    }
}
```

- [ ] **Step 2: Verify red**

Run: `mvn -pl wikantik-main test -Dtest=ProposalConsolidatorTest -q`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pure function: groups extraction results by canonical signature, picks
 * the most-frequent original-cased display name by vote, aggregates support
 * evidence, and emits one ConsolidatedProposal per logical claim.
 */
public final class ProposalConsolidator {

    public List<ConsolidatedProposal> consolidate(Stream<PageExtractionResult> pageResults) {
        Map<String, NodeBuilder> nodes = new LinkedHashMap<>();
        Map<String, EdgeBuilder> edges = new LinkedHashMap<>();

        pageResults.forEach(pr -> {
            for (ExtractedEntity e : pr.entities()) {
                NodeSignature sig = NodeSignature.of(e.name(), e.type());
                nodes.computeIfAbsent(sig.asHash(),
                    k -> new NodeBuilder(sig.asHash(), e.name(), titleCase(e.type())))
                     .addSupport(pr.pageName(), e.evidenceSpan(), e.confidence(),
                                 pr.extractorCode(), e.name());
            }
            for (ExtractedRelation r : pr.relations()) {
                EdgeSignature sig = EdgeSignature.of(r.source(), r.target(), r.predicate());
                edges.computeIfAbsent(sig.asHash(),
                    k -> new EdgeBuilder(sig.asHash(), r.source(), r.target(),
                                         EdgeSignature.normalizePredicate(r.predicate())))
                     .addSupport(pr.pageName(), r.evidenceSpan(), r.confidence(), pr.extractorCode());
            }
        });

        List<ConsolidatedProposal> out = new ArrayList<>(nodes.size() + edges.size());
        for (NodeBuilder b : nodes.values()) out.add(b.build());
        for (EdgeBuilder b : edges.values()) out.add(b.build());
        return out;
    }

    private static String titleCase(String t) {
        String s = t.trim();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Package-private mutable accumulator for nodes. */
    static final class NodeBuilder {
        private final String signature;
        private final String firstSeenName;
        private final String type;
        private final List<SupportEvidence> support = new ArrayList<>();
        private final Map<String, Integer> nameVotes = new HashMap<>();
        private double confidenceSum = 0.0;

        NodeBuilder(String signature, String firstSeenName, String type) {
            this.signature = signature;
            this.firstSeenName = firstSeenName;
            this.type = type;
        }

        void addSupport(String page, String span, double conf, String extractorCode, String emittedName) {
            support.add(new SupportEvidence(page, span, conf, extractorCode));
            confidenceSum += conf;
            nameVotes.merge(emittedName, 1, Integer::sum);
        }

        ConsolidatedProposal build() {
            // Vote for display name; tie-break by first-seen.
            String winner = firstSeenName;
            int best = nameVotes.getOrDefault(firstSeenName, 0);
            for (var e : nameVotes.entrySet()) {
                if (e.getValue() > best) {
                    winner = e.getKey();
                    best = e.getValue();
                }
            }
            double agg = support.isEmpty() ? 0.0 : confidenceSum / support.size();
            return ConsolidatedProposal.newNode(signature, winner, type, support, agg);
        }
    }

    /** Package-private mutable accumulator for edges. */
    static final class EdgeBuilder {
        private final String signature;
        private final String source;
        private final String target;
        private final String predicate;
        private final List<SupportEvidence> support = new ArrayList<>();
        private double confidenceSum = 0.0;

        EdgeBuilder(String signature, String source, String target, String predicate) {
            this.signature = signature;
            this.source = source;
            this.target = target;
            this.predicate = predicate;
        }

        void addSupport(String page, String span, double conf, String extractorCode) {
            support.add(new SupportEvidence(page, span, conf, extractorCode));
            confidenceSum += conf;
        }

        ConsolidatedProposal build() {
            double agg = support.isEmpty() ? 0.0 : confidenceSum / support.size();
            return ConsolidatedProposal.newEdge(signature, source, target, predicate, support, agg);
        }
    }
}
```

- [ ] **Step 4: Verify green**

Run: `mvn -pl wikantik-main test -Dtest=ProposalConsolidatorTest -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ProposalConsolidator.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/ProposalConsolidatorTest.java
git commit -m "extraction: add ProposalConsolidator with display-name vote + signature dedup

Pure function that groups page results by NodeSignature/EdgeSignature,
votes on canonical display-name, and aggregates support evidence into
single ConsolidatedProposals.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.5: ProposalUpserter

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ProposalUpserter.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/ProposalUpserterTest.java` (uses H2-with-pgvector substitute or PostgreSQL test container)
- **Modify:** `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java` — add a low-level `upsertConsolidatedProposal(ConsolidatedProposal)` method that the upserter delegates to.

**Important:** the upsert SQL uses `jsonb_array_elements` + `DISTINCT ON` for idempotency (same source page on re-run doesn't double up support entries). H2 has no JSONB. So the `ProposalUpserterTest` is an **integration-style** unit test that requires a real PostgreSQL — use the same `Testcontainers` pattern other DB-touching tests in `wikantik-main` use, or annotate with `@EnabledIfSystemProperty(named="postgres.url")` and skip when not available.

- [ ] **Step 1: Read the existing JdbcKnowledgeRepository to find the connection-acquisition pattern**

Run: `grep -nA 5 'final Connection conn' wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java | head -20`
Note the pattern: `try (Connection conn = dataSource.getConnection(); ...)`.

- [ ] **Step 2: Write failing test (assumes PostgreSQL available; gracefully skip otherwise)**

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.SupportEvidence;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "wikantik.test.pg.url", matches = ".+",
    disabledReason = "Requires Postgres + V020 schema. Set -Dwikantik.test.pg.url=jdbc:... -Dwikantik.test.pg.user=... -Dwikantik.test.pg.password=...")
class ProposalUpserterTest {

    private DataSource ds;
    private ProposalUpserter upserter;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(System.getProperty("wikantik.test.pg.url"));
        pg.setUser(System.getProperty("wikantik.test.pg.user", "jspwiki"));
        pg.setPassword(System.getProperty("wikantik.test.pg.password", ""));
        ds = pg;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM kg_proposals WHERE signature LIKE 'sig-test-%'");
        }
        upserter = new ProposalUpserter(new JdbcKnowledgeRepository(ds));
    }

    @Test
    void firstInsertProducesRowWithSupportCountOne() {
        ConsolidatedProposal p = ConsolidatedProposal.newNode(
            "sig-test-001", "Kafka", "Technology",
            List.of(new SupportEvidence("Page1", "Kafka is a streaming platform.", 0.9, "ollama:gemma4")),
            0.9);
        ProposalUpserter.Result r = upserter.upsert(p);
        assertTrue(r.inserted());
        assertEquals(1, r.supportCount());
    }

    @Test
    void secondUpsertDifferentPageMergesSupport() {
        SupportEvidence p1 = new SupportEvidence("Page1", "Kafka is X.", 0.9, "ollama:gemma4");
        SupportEvidence p2 = new SupportEvidence("Page2", "Kafka is Y.", 0.85, "ollama:gemma4");
        upserter.upsert(ConsolidatedProposal.newNode("sig-test-002", "Kafka", "Technology", List.of(p1), 0.9));
        ProposalUpserter.Result r = upserter.upsert(
            ConsolidatedProposal.newNode("sig-test-002", "Kafka", "Technology", List.of(p2), 0.85));
        assertFalse(r.inserted());
        assertEquals(2, r.supportCount());
    }

    @Test
    void reUpsertSamePageDoesNotDoubleSupport() {
        SupportEvidence p1 = new SupportEvidence("Page1", "Kafka is X.", 0.9, "ollama:gemma4");
        upserter.upsert(ConsolidatedProposal.newNode("sig-test-003", "Kafka", "Technology", List.of(p1), 0.9));
        ProposalUpserter.Result r = upserter.upsert(
            ConsolidatedProposal.newNode("sig-test-003", "Kafka", "Technology", List.of(p1), 0.95));
        assertFalse(r.inserted());
        assertEquals(1, r.supportCount());  // idempotency: same page deduped
    }
}
```

- [ ] **Step 3: Verify red**

Run: `mvn -pl wikantik-main test -Dtest=ProposalUpserterTest -q -Dwikantik.test.pg.url=jdbc:postgresql://localhost:5432/jspwiki -Dwikantik.test.pg.user=jspwiki -Dwikantik.test.pg.password="$pw"`

(If PG isn't available, the test is skipped — but it shouldn't compile yet either way. Compilation error confirms red.)

- [ ] **Step 4: Implement upserter and JdbcKnowledgeRepository extension**

`ProposalUpserter.java`:

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.knowledge.JdbcKnowledgeRepository;

/**
 * Wraps the DB-side upsert. Computes the JSON support payload, delegates to
 * the repository's prepared-statement upsert, returns inserted/merged + the
 * resulting support_count for status logging.
 */
public final class ProposalUpserter {

    private final JdbcKnowledgeRepository repo;

    public ProposalUpserter(JdbcKnowledgeRepository repo) {
        this.repo = repo;
    }

    public Result upsert(ConsolidatedProposal cp) {
        return repo.upsertConsolidatedProposal(cp);
    }

    public record Result(boolean inserted, int supportCount) {}
}
```

In `JdbcKnowledgeRepository.java` add this method. The SQL uses a CTE that computes the merged support array (deduped by `sourcePage`) and either inserts a new row or updates the existing pending one:

```java
public ProposalUpserter.Result upsertConsolidatedProposal(ConsolidatedProposal cp) {
    final String proposedJson = GSON.toJson(buildProposedData(cp));
    final String supportJson  = GSON.toJson(cp.support());
    final String sql = """
        WITH merged AS (
            SELECT (
                SELECT jsonb_agg(s ORDER BY (s->>'sourcePage'))
                FROM (
                    SELECT DISTINCT ON (s->>'sourcePage') s
                    FROM jsonb_array_elements(
                        COALESCE(kp.support, '[]'::jsonb) || ?::jsonb
                    ) s
                    ORDER BY (s->>'sourcePage'), (s->>'confidence')::numeric DESC
                ) deduped
            ) AS support_merged
            FROM kg_proposals kp
            WHERE kp.signature = ? AND kp.status = 'pending'
        )
        INSERT INTO kg_proposals
            (proposal_type, source_page, proposed_data, confidence, reasoning,
             signature, support, support_count, first_seen_at, last_seen_at)
        VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, 1, NOW(), NOW())
        ON CONFLICT (signature) WHERE status = 'pending' DO UPDATE
        SET support       = COALESCE((SELECT support_merged FROM merged), kg_proposals.support),
            support_count = jsonb_array_length(COALESCE((SELECT support_merged FROM merged), kg_proposals.support)),
            confidence    = GREATEST(kg_proposals.confidence, EXCLUDED.confidence),
            last_seen_at  = NOW()
        RETURNING (xmax = 0) AS inserted, support_count
        """;
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, supportJson);
        ps.setString(2, cp.signature());
        ps.setString(3, cp.kind() == ConsolidatedProposal.Kind.NEW_NODE ? "new-node" : "new-edge");
        ps.setString(4, cp.support().isEmpty() ? null : cp.support().get(0).sourcePage());
        ps.setString(5, proposedJson);
        ps.setDouble(6, cp.aggregateConfidence());
        ps.setString(7, "consolidated by " + cp.support().size() + " support(s)");
        ps.setString(8, cp.signature());
        ps.setString(9, supportJson);
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ProposalUpserter.Result(rs.getBoolean(1), rs.getInt(2));
            }
            throw new IllegalStateException("upsert returned no rows for signature " + cp.signature());
        }
    } catch (SQLException e) {
        LOG.warn("upsertConsolidatedProposal failed for signature {}: {}", cp.signature(), e.getMessage());
        throw new RuntimeException("upsert failed: " + e.getMessage(), e);
    }
}

private static java.util.Map<String, Object> buildProposedData(ConsolidatedProposal cp) {
    if (cp.kind() == ConsolidatedProposal.Kind.NEW_NODE) {
        return java.util.Map.of("name", cp.displayName(), "nodeType", cp.type());
    }
    return java.util.Map.of("source", cp.source(), "target", cp.target(),
                            "relationship", cp.predicate());
}
```

- [ ] **Step 5: Verify green**

Run: `mvn -pl wikantik-main test -Dtest=ProposalUpserterTest -q -Dwikantik.test.pg.url=jdbc:postgresql://localhost:5432/jspwiki -Dwikantik.test.pg.user=jspwiki -Dwikantik.test.pg.password="$pw"`
Expected: BUILD SUCCESS, 3 tests passed.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ProposalUpserter.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/ProposalUpserterTest.java
git commit -m "extraction: add ProposalUpserter with JSONB support-merge upsert

Idempotent upsert: same logical proposal arriving from a re-run merges
its support entry by source_page (DISTINCT ON keeps the highest-conf
quote per page). Counts inserted vs merged via the (xmax=0) trick.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.6: KgNodeEmbeddingRepository

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/embedding/KgNodeEmbeddingRepository.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/embedding/KgNodeEmbeddingRepositoryTest.java`

Repository thin layer over `kg_node_embeddings`: `findById`, `upsert(node_id, content_hash, vector)`, `findTopKByPageEmbedding(pageEmbedding, k)`. The third method joins to `kg_nodes` to return `KgNode` records.

- [ ] **Step 1: Skeleton test (PG-required, same skip pattern as 2.5)**

```java
package com.wikantik.knowledge.embedding;

import com.wikantik.api.knowledge.KgNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "wikantik.test.pg.url", matches = ".+",
    disabledReason = "Requires Postgres + V021 schema")
class KgNodeEmbeddingRepositoryTest {

    private KgNodeEmbeddingRepository repo;
    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(System.getProperty("wikantik.test.pg.url"));
        pg.setUser(System.getProperty("wikantik.test.pg.user", "jspwiki"));
        pg.setPassword(System.getProperty("wikantik.test.pg.password", ""));
        ds = pg;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM kg_node_embeddings WHERE content_hash LIKE 'test-%'");
        }
        repo = new KgNodeEmbeddingRepository(ds);
    }

    @Test
    void upsertThenFindReturnsSameVector() throws Exception {
        UUID nodeId = anyExistingNodeId();
        float[] vec = new float[1024];
        for (int i = 0; i < 1024; i++) vec[i] = (float)(Math.sin(i) * 0.1);
        repo.upsert(nodeId, "test-hash-1", vec);
        Optional<KgNodeEmbeddingRepository.Cached> got = repo.findById(nodeId);
        assertTrue(got.isPresent());
        assertEquals("test-hash-1", got.get().contentHash());
        assertArrayEquals(vec, got.get().embedding(), 1e-6f);
    }

    @Test
    void findTopKReturnsClosestNodes() throws Exception {
        UUID a = anyExistingNodeId();
        float[] vec = new float[1024];
        vec[0] = 1.0f;
        repo.upsert(a, "test-hash-2", vec);
        List<KgNode> top = repo.findTopKByPageEmbedding(vec, 10);
        assertFalse(top.isEmpty());
        assertEquals(a, top.get(0).id());
    }

    private UUID anyExistingNodeId() throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT id FROM kg_nodes LIMIT 1")) {
            assertTrue(rs.next(), "test requires at least one row in kg_nodes");
            return UUID.fromString(rs.getString(1));
        }
    }
}
```

- [ ] **Step 2: Verify red, then implement**

```java
package com.wikantik.knowledge.embedding;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DAO over {@code kg_node_embeddings}. Vectors are stored as pgvector
 * literal strings ({@code "[v1,v2,...]"}) — pgvector's JDBC type isn't
 * registered in this codebase so we round-trip via {@code ::vector} casts.
 */
public final class KgNodeEmbeddingRepository {

    private static final Logger LOG = LogManager.getLogger(KgNodeEmbeddingRepository.class);

    private final DataSource dataSource;

    public KgNodeEmbeddingRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<Cached> findById(UUID nodeId) {
        final String sql = "SELECT content_hash, embedding::text FROM kg_node_embeddings WHERE node_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Cached(rs.getString(1), parseVector(rs.getString(2))));
            }
        } catch (SQLException e) {
            LOG.warn("findById({}) failed: {}", nodeId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void upsert(UUID nodeId, String contentHash, float[] embedding) {
        final String sql = """
            INSERT INTO kg_node_embeddings (node_id, content_hash, embedding, embedded_at)
            VALUES (?, ?, ?::vector, NOW())
            ON CONFLICT (node_id) DO UPDATE
            SET content_hash = EXCLUDED.content_hash,
                embedding    = EXCLUDED.embedding,
                embedded_at  = NOW()
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, nodeId);
            ps.setString(2, contentHash);
            ps.setString(3, formatVector(embedding));
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("upsert({}) failed: {}", nodeId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public List<KgNode> findTopKByPageEmbedding(float[] pageEmbedding, int k) {
        final String sql = """
            SELECT n.id, n.name, n.node_type, n.source_page, n.provenance,
                   n.properties::text, n.created, n.modified
            FROM kg_node_embeddings ne
            JOIN kg_nodes n ON n.id = ne.node_id
            ORDER BY ne.embedding <=> ?::vector
            LIMIT ?
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, formatVector(pageEmbedding));
            ps.setInt(2, k);
            try (ResultSet rs = ps.executeQuery()) {
                List<KgNode> out = new ArrayList<>(k);
                while (rs.next()) {
                    out.add(new KgNode(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        Provenance.fromValue(rs.getString(5)),
                        Map.of(),  // skip properties parse for the dictionary path; not used by the prompt
                        rs.getTimestamp(7) == null ? Instant.now() : rs.getTimestamp(7).toInstant(),
                        rs.getTimestamp(8) == null ? Instant.now() : rs.getTimestamp(8).toInstant()
                    ));
                }
                return out;
            }
        } catch (SQLException e) {
            LOG.warn("findTopKByPageEmbedding failed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    static String formatVector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    static float[] parseVector(String text) {
        String trimmed = text.substring(1, text.length() - 1);   // strip [ ]
        String[] parts = trimmed.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i]);
        return out;
    }

    public record Cached(String contentHash, float[] embedding) {}
}
```

- [ ] **Step 3: Verify green, commit**

```bash
mvn -pl wikantik-main test -Dtest=KgNodeEmbeddingRepositoryTest -q \
    -Dwikantik.test.pg.url=jdbc:postgresql://localhost:5432/jspwiki \
    -Dwikantik.test.pg.user=jspwiki -Dwikantik.test.pg.password="$pw"
git add wikantik-main/src/main/java/com/wikantik/knowledge/embedding/KgNodeEmbeddingRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/embedding/KgNodeEmbeddingRepositoryTest.java
git commit -m "embedding: add KgNodeEmbeddingRepository for kg_node_embeddings cache

Thin pgvector DAO. upsert/findById/findTopKByPageEmbedding using
::vector casts. Used by KgNodeEmbeddingService + the per-page extractor
to build a retrieval-augmented dictionary.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.7: KgNodeEmbeddingService

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/embedding/KgNodeEmbeddingService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/embedding/KgNodeEmbeddingServiceTest.java`

Coordinates: for each existing KG node, compute `content_hash = sha256(name|nodeType|sourcePage)`; if cache row's hash matches, skip. Otherwise call `OllamaEmbeddingClient.embed(text)` for `name :: nodeType :: sourcePage` and upsert. Returns `(cached, reEmbedded, errors)` counts.

- [ ] **Step 1: Failing test using Mockito to fake the embedding client**

```java
package com.wikantik.knowledge.embedding;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.search.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class KgNodeEmbeddingServiceTest {

    @Test
    void cachedHashSkipsEmbedding() {
        KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        EmbeddingClient client = mock(EmbeddingClient.class);
        UUID id = UUID.randomUUID();
        KgNode node = new KgNode(id, "Kafka", "Technology", "Kafka",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());

        // Simulate cache hit with matching hash.
        String expectedText = "Kafka :: Technology :: Kafka";
        String expectedHash = KgNodeEmbeddingService.contentHashOf(node);
        when(repo.findById(id)).thenReturn(
            Optional.of(new KgNodeEmbeddingRepository.Cached(expectedHash, new float[1024])));

        KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "bge-m3:latest");
        KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(1, r.cached());
        assertEquals(0, r.reEmbedded());
        verify(client, never()).embed(any());
    }

    @Test
    void missingCacheTriggersEmbedAndUpsert() {
        KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        EmbeddingClient client = mock(EmbeddingClient.class);
        UUID id = UUID.randomUUID();
        KgNode node = new KgNode(id, "Kafka", "Technology", "Kafka",
            Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());
        when(repo.findById(id)).thenReturn(Optional.empty());
        float[] vec = new float[1024];
        vec[0] = 0.5f;
        when(client.embed("Kafka :: Technology :: Kafka")).thenReturn(vec);

        KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "bge-m3:latest");
        KgNodeEmbeddingService.Result r = svc.warmUp(List.of(node));
        assertEquals(0, r.cached());
        assertEquals(1, r.reEmbedded());
        verify(repo).upsert(eq(id), eq(KgNodeEmbeddingService.contentHashOf(node)), eq(vec));
    }

    @Test
    void embedFailureContinuesProcessing() {
        KgNodeEmbeddingRepository repo = mock(KgNodeEmbeddingRepository.class);
        EmbeddingClient client = mock(EmbeddingClient.class);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        KgNode bad  = new KgNode(id1, "X", "Concept", "X", Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());
        KgNode good = new KgNode(id2, "Y", "Concept", "Y", Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now());
        when(repo.findById(any())).thenReturn(Optional.empty());
        when(client.embed("X :: Concept :: X")).thenThrow(new RuntimeException("boom"));
        when(client.embed("Y :: Concept :: Y")).thenReturn(new float[1024]);

        KgNodeEmbeddingService svc = new KgNodeEmbeddingService(repo, client, "bge-m3:latest");
        KgNodeEmbeddingService.Result r = svc.warmUp(List.of(bad, good));
        assertEquals(1, r.errors());
        assertEquals(1, r.reEmbedded());
    }
}
```

NOTE: `EmbeddingClient` may not exist as an interface in the codebase. Check `com.wikantik.search.embedding.OllamaEmbeddingClient` — if it has a public `embed(String)` method, define a narrow `EmbeddingClient` functional interface here OR have the service take `OllamaEmbeddingClient` directly. Update the test accordingly.

- [ ] **Step 2: Implement**

```java
package com.wikantik.knowledge.embedding;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.search.embedding.EmbeddingClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Warms the {@code kg_node_embeddings} cache. For each KG node, compares the
 * cached content_hash against the live one; only re-embeds on mismatch (or
 * absence). Result counts feed the indexer's start-up log line.
 */
public final class KgNodeEmbeddingService {

    private static final Logger LOG = LogManager.getLogger(KgNodeEmbeddingService.class);

    private final KgNodeEmbeddingRepository repo;
    private final EmbeddingClient client;
    private final String modelTag;

    public KgNodeEmbeddingService(KgNodeEmbeddingRepository repo,
                                  EmbeddingClient client,
                                  String modelTag) {
        this.repo = repo;
        this.client = client;
        this.modelTag = modelTag;
    }

    public Result warmUp(List<KgNode> nodes) {
        int cached = 0, reEmbedded = 0, errors = 0;
        for (KgNode n : nodes) {
            String hash = contentHashOf(n);
            Optional<KgNodeEmbeddingRepository.Cached> existing;
            try {
                existing = repo.findById(n.id());
            } catch (RuntimeException e) {
                LOG.warn("findById failed for node {}: {}", n.id(), e.getMessage());
                errors++;
                continue;
            }
            if (existing.isPresent() && hash.equals(existing.get().contentHash())) {
                cached++;
                continue;
            }
            String text = embeddingTextOf(n);
            float[] vec;
            try {
                vec = client.embed(text);
            } catch (RuntimeException e) {
                LOG.warn("embed failed for node '{}' ({}): {}", n.name(), modelTag, e.getMessage());
                errors++;
                continue;
            }
            try {
                repo.upsert(n.id(), hash, vec);
                reEmbedded++;
            } catch (RuntimeException e) {
                LOG.warn("upsert embedding failed for node '{}': {}", n.name(), e.getMessage());
                errors++;
            }
        }
        return new Result(cached, reEmbedded, errors);
    }

    static String embeddingTextOf(KgNode n) {
        String type = n.nodeType() == null ? "Concept" : n.nodeType();
        String sp   = n.sourcePage() == null ? n.name() : n.sourcePage();
        return n.name() + " :: " + type + " :: " + sp;
    }

    public static String contentHashOf(KgNode n) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(embeddingTextOf(n).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Result(int cached, int reEmbedded, int errors) {}
}
```

- [ ] **Step 3: Verify green, commit**

```bash
mvn -pl wikantik-main test -Dtest=KgNodeEmbeddingServiceTest -q
git add wikantik-main/src/main/java/com/wikantik/knowledge/embedding/KgNodeEmbeddingService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/embedding/KgNodeEmbeddingServiceTest.java
git commit -m "embedding: add KgNodeEmbeddingService with content-hash skip

Warms the kg_node_embeddings cache. Per-node SHA-256 of name|type|source
keeps re-runs cheap (~1020 nodes * skipped on identity is sub-second);
embed errors continue processing rather than aborting the whole warmup.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.8: OllamaPageExtractor

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/OllamaPageExtractor.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/OllamaPageExtractorTest.java`

Production implementation of `PageExtractor`. Builds prompts via `PageExtractionPromptBuilder`, calls Ollama `/api/chat` with `format: "json"`, parses via `PageExtractionResponseParser`. Mirror the existing `OllamaEntityExtractor` style: blocking `HttpClient`, no retries, fail-open (return empty on error).

- [ ] **Step 1: Write failing test (uses Mockito to fake the HttpClient)**

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.PageExtractionResult;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class OllamaPageExtractorTest {

    @Test
    @SuppressWarnings("unchecked")
    void successfulExtraction() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
            {"message":{"content":"{\\"entities\\":[{\\"name\\":\\"Python\\",\\"type\\":\\"Technology\\",\\"evidence_span\\":\\"Python is a language\\",\\"confidence\\":0.9}],\\"relations\\":[]}"}}
            """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        OllamaPageExtractor extractor = new OllamaPageExtractor(
            client, "http://localhost:11434", "gemma4-assist:latest", 60_000L,
            new PageExtractionResponseParser(new EvidenceGroundingVerifier(), 12, 8));

        Page page = new Page("PythonPage", null, "Python is a language. Used widely.", "", List.of());
        ExtractionContext ctx = new ExtractionContext("PythonPage", List.of(), Map.of());
        PageExtractionResult result = extractor.extract(page, ctx);
        assertEquals(1, result.entities().size());
        assertEquals("Python", result.entities().get(0).name());
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpErrorReturnsEmpty() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("Internal Server Error");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        OllamaPageExtractor extractor = new OllamaPageExtractor(
            client, "http://localhost:11434", "gemma4-assist:latest", 60_000L,
            new PageExtractionResponseParser(new EvidenceGroundingVerifier(), 12, 8));

        Page page = new Page("X", null, "body", "", List.of());
        PageExtractionResult result = extractor.extract(page, new ExtractionContext("X", List.of(), Map.of()));
        assertEquals(0, result.entities().size());
    }

    @Test
    void codeIncludesModelTag() {
        OllamaPageExtractor extractor = new OllamaPageExtractor(
            mock(HttpClient.class), "u", "gemma4-assist:latest", 60_000L,
            new PageExtractionResponseParser(new EvidenceGroundingVerifier(), 12, 8));
        assertEquals("ollama:gemma4-assist", extractor.code());
    }
}
```

- [ ] **Step 2: Implement (mirror OllamaEntityExtractor patterns)**

```java
package com.wikantik.knowledge.extraction;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.PageExtractionResult;
import com.wikantik.api.knowledge.PageExtractor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Per-page extractor backed by an Ollama /api/chat endpoint with
 * {@code format: "json"}. Mirrors the existing OllamaEntityExtractor style
 * (no retries, fail-open). Stripping ":latest" from the model tag matches
 * how the chunk extractor reports lineage.
 */
public final class OllamaPageExtractor implements PageExtractor {

    private static final Logger LOG = LogManager.getLogger(OllamaPageExtractor.class);
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final long timeoutMs;
    private final PageExtractionResponseParser parser;

    public OllamaPageExtractor(HttpClient httpClient, String baseUrl, String model,
                                long timeoutMs, PageExtractionResponseParser parser) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.parser = parser;
    }

    @Override
    public String code() {
        String trimmed = model.trim();
        String stripped = trimmed.endsWith(":latest")
            ? trimmed.substring(0, trimmed.length() - ":latest".length())
            : trimmed;
        return "ollama:" + stripped;
    }

    @Override
    public PageExtractionResult extract(Page page, ExtractionContext context) {
        long started = System.nanoTime();
        try {
            String raw = callOllama(page, context);
            Duration latency = Duration.ofNanos(System.nanoTime() - started);
            if (raw == null) {
                return PageExtractionResult.empty(code(), page.name(), latency);
            }
            return parser.parse(raw, code(), page.name(), page.body(), latency);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn("Ollama extraction interrupted for page '{}'", page.name());
            return PageExtractionResult.empty(code(), page.name(), Duration.ofNanos(System.nanoTime() - started));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Ollama extraction failed for page '{}': {}", page.name(), e.getMessage());
            return PageExtractionResult.empty(code(), page.name(), Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private String callOllama(Page page, ExtractionContext ctx) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
            "model", model,
            "stream", false,
            "format", "json",
            "messages", List.of(
                Map.of("role", "system", "content", PageExtractionPromptBuilder.SYSTEM_PROMPT),
                Map.of("role", "user", "content", PageExtractionPromptBuilder.buildUserPrompt(page, ctx))
            )
        );
        String url = stripTrailingSlash(baseUrl) + "/api/chat";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            LOG.warn("Ollama page extract HTTP {} for page '{}'", res.statusCode(), page.name());
            return null;
        }
        JsonElement root = JsonParser.parseString(res.body());
        if (!root.isJsonObject()) return null;
        JsonElement message = root.getAsJsonObject().get("message");
        if (message == null || !message.isJsonObject()) return null;
        JsonElement content = message.getAsJsonObject().get("content");
        return content == null || content.isJsonNull() ? null : content.getAsString();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
```

- [ ] **Step 3: Verify green, commit**

```bash
mvn -pl wikantik-main test -Dtest=OllamaPageExtractorTest -q
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/OllamaPageExtractor.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/OllamaPageExtractorTest.java
git commit -m "extraction: add OllamaPageExtractor (per-page, format=json)

Production page extractor. Mirrors OllamaEntityExtractor wire patterns
(blocking HttpClient, no retries, fail-open). Code is 'ollama:<model>'
with ':latest' stripped to match existing lineage reporting.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.9: MentionAttributor

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/MentionAttributor.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/MentionAttributorTest.java`

Deterministic exact-string-match per chunk. Inputs: a list of resolved entity names (after consolidation + judge), the page's chunks (from `ContentChunkRepository`). Outputs: rows ready for `chunk_entity_mentions`.

- [ ] **Step 1: Failing test**

```java
package com.wikantik.knowledge.extraction;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MentionAttributorTest {

    private final MentionAttributor attr = new MentionAttributor();

    @Test
    void wholeWordMatchOnly() {
        UUID chunkId = UUID.randomUUID();
        UUID javaNodeId = UUID.randomUUID();
        UUID jsNodeId   = UUID.randomUUID();
        List<MentionAttributor.NameMapping> names = List.of(
            new MentionAttributor.NameMapping(javaNodeId, "Java"),
            new MentionAttributor.NameMapping(jsNodeId, "JavaScript")
        );
        // Chunk text contains both 'Java' (whole word) and 'JavaScript' (whole word).
        // Verify Java does NOT match the substring inside JavaScript.
        List<MentionAttributor.ChunkMention> matches = attr.attribute(
            chunkId, "I write Java and JavaScript code.", names);
        // Expected: one mention each, NOT two for Java.
        long javaMatches = matches.stream().filter(m -> m.nodeId().equals(javaNodeId)).count();
        long jsMatches   = matches.stream().filter(m -> m.nodeId().equals(jsNodeId)).count();
        assertEquals(1, javaMatches);
        assertEquals(1, jsMatches);
    }

    @Test
    void caseInsensitivePresenceCaseSensitiveSurface() {
        UUID chunkId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        List<MentionAttributor.ChunkMention> matches = attr.attribute(
            chunkId, "kafka is great. Kafka really is.",
            List.of(new MentionAttributor.NameMapping(nodeId, "Kafka")));
        // Two mentions; surface forms preserved per occurrence.
        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(m -> "kafka".equals(m.surfaceForm())));
        assertTrue(matches.stream().anyMatch(m -> "Kafka".equals(m.surfaceForm())));
    }

    @Test
    void noMatchesReturnsEmpty() {
        UUID chunkId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        List<MentionAttributor.ChunkMention> matches = attr.attribute(
            chunkId, "Nothing relevant here.",
            List.of(new MentionAttributor.NameMapping(nodeId, "Kafka")));
        assertTrue(matches.isEmpty());
    }
}
```

- [ ] **Step 2: Implement**

```java
package com.wikantik.knowledge.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic chunk → entity mention attribution. For each resolved entity
 * name, scan each chunk's text for whole-word matches (case-insensitive
 * presence, case-preserving surface form). No LLM, no I/O.
 */
public final class MentionAttributor {

    public List<ChunkMention> attribute(UUID chunkId, String chunkText, List<NameMapping> names) {
        List<ChunkMention> out = new ArrayList<>();
        for (NameMapping nm : names) {
            // \b is the word boundary; Pattern.quote handles names with regex metachars.
            Pattern p = Pattern.compile("\\b" + Pattern.quote(nm.name()) + "\\b",
                                         Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(chunkText);
            while (m.find()) {
                out.add(new ChunkMention(chunkId, nm.nodeId(), m.group(), m.start(), m.end()));
            }
        }
        return out;
    }

    public record NameMapping(UUID nodeId, String name) {}

    public record ChunkMention(UUID chunkId, UUID nodeId, String surfaceForm, int startOffset, int endOffset) {}
}
```

- [ ] **Step 3: Verify green, commit**

```bash
mvn -pl wikantik-main test -Dtest=MentionAttributorTest -q
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/MentionAttributor.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/MentionAttributorTest.java
git commit -m "extraction: add MentionAttributor (deterministic whole-word match)

Replaces today's silently-broken LLM-driven mention attribution (0 rows
in chunk_entity_mentions today) with a pure regex pass. Whole-word means
'Java' does not match inside 'JavaScript'; case-insensitive presence,
case-preserving surface form.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2.10: NoOpProposalJudge

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/NoOpProposalJudge.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/NoOpProposalJudgeTest.java`

- [ ] **Step 1: Failing test**

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NoOpProposalJudgeTest {

    @Test
    void allInputsAccepted() {
        NoOpProposalJudge judge = new NoOpProposalJudge();
        SupportEvidence e = new SupportEvidence("P", "x", 0.9, "x");
        ConsolidatedProposal p = ConsolidatedProposal.newNode("sig", "X", "Concept", List.of(e), 0.9);
        Verdict v = judge.judge(p, new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
        assertEquals(0.9, ((Verdict.Accept)v).finalConfidence());
    }

    @Test
    void codeIsNoop() {
        assertEquals("noop", new NoOpProposalJudge().code());
    }
}
```

- [ ] **Step 2: Implement**

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.JudgeContext;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.api.knowledge.Verdict;

/** Default judge: accepts every consolidated proposal as-is. */
public final class NoOpProposalJudge implements ProposalJudge {
    @Override public String code() { return "noop"; }
    @Override public Verdict judge(ConsolidatedProposal proposal, JudgeContext context) {
        return new Verdict.Accept(proposal.aggregateConfidence(), "no-op judge");
    }
}
```

- [ ] **Step 3: Verify green, commit**

```bash
mvn -pl wikantik-main test -Dtest=NoOpProposalJudgeTest -q
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/NoOpProposalJudge.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/NoOpProposalJudgeTest.java
git commit -m "extraction: add NoOpProposalJudge (production default)

Identity transform. Production runs with this judge installed; Ollama
and Claude judges are opt-in.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Phase 3 — Refactor BootstrapEntityExtractionIndexer

This phase replaces `BootstrapEntityExtractionIndexer` internals to drive the new pipeline (page extraction → consolidation → judge → upsert → mention attribution). The save-time `AsyncEntityExtractionListener` stays in place — it's wired via `WikiEngine.java:963` and is **out of scope** for this redesign (see spec section 12).

### Task 3.1: Refactor `BootstrapEntityExtractionIndexer`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexer.java`

The new internals do this on `start()`:
1. Load all KG nodes; warm `KgNodeEmbeddingService`.
2. List distinct page names via `chunkRepo.listDistinctPageNames()` (chunk repo still tracks them).
3. For each page (with concurrency from a worker pool):
   a. Read page body (from disk via `pages-dir` OR by stitching chunks back together — choose stitching to avoid reaching for the filesystem in the indexer; see implementation note below).
   b. Compute page-mean embedding (mean of the page's `content_chunk_embeddings` rows).
   c. Retrieve top-K dictionary nodes via `KgNodeEmbeddingRepository.findTopKByPageEmbedding`.
   d. Call `pageExtractor.extract(page, ctx)`. Capture stats.
4. Collect all `PageExtractionResult`s; pass to `ProposalConsolidator`.
5. Run each consolidated proposal through `proposalJudge`.
6. For each `Accept` or `Rewrite` verdict, call `proposalUpserter.upsert(...)`. Track inserted vs merged.
7. Build a `nodeId -> name` map from the surviving consolidated proposals + DB lookups for canonicalized rewrites; run `MentionAttributor.attribute(...)` over each chunk; bulk-insert into `chunk_entity_mentions`.
8. Update `Status` counters at each stage so polled progress lines are meaningful.

**Implementation note on page-body source:** the simplest path is to stitch chunks back together by `chunk_index`. `ContentChunkRepository.findByIds` already exposes `text()`. Verify the stitched text includes whatever the chunker dropped (frontmatter is intentionally excluded; that's fine, we don't extract from frontmatter). If stitching loses fidelity, fall back to reading from `--pages-dir` (the CLI already accepts that path).

- [ ] **Step 1: Update `Status` record to expose new fields the operator log needs**

Add these fields to the existing `Status` record (keep existing names; add new at the end):
- `int consolidatedCandidates` — entities + edges after `ProposalConsolidator`
- `int judgeAccepted, judgeRejected, judgeRewritten` — counts from judge stage
- `int proposalsInserted, proposalsMerged` — for upsert metric
- `int mentionsWritten` (already exists — keep semantics, now populated by `MentionAttributor`)
- `Map<String,Integer> rejectionReasons` — from judge `Reject.reasonCode`

- [ ] **Step 2: Write failing test**

Add to `BootstrapEntityExtractionIndexerTest.java` (existing file):

```java
@Test
void newPipelineDrivesPageExtractorAndConsolidator() {
    // Mock: 2 pages; PageExtractor returns 1 entity each, both naming "Python";
    // consolidator collapses to 1 ConsolidatedProposal; NoOpJudge accepts;
    // Upserter inserts 1 row.
    PageExtractor extractor = mock(PageExtractor.class);
    when(extractor.code()).thenReturn("ollama:test");
    when(extractor.extract(any(), any())).thenReturn(
        new PageExtractionResult("ollama:test", "P1",
            List.of(new ExtractedEntity("Python", "Technology", "Python", 0.9)), List.of(),
            new PageExtractionResult.Stats(1, 0, 0, 0, Duration.ZERO)));
    // ...
    indexer.start(false);
    awaitCompletion(indexer);
    Status s = indexer.status();
    assertEquals(1, s.consolidatedCandidates());
    assertEquals(1, s.judgeAccepted());
    assertEquals(1, s.proposalsInserted());
}
```

(The exact mocks depend on the existing test scaffolding — read `BootstrapEntityExtractionIndexerTest.java` first to understand the constructor wiring.)

- [ ] **Step 3: Refactor**

The constructor signature changes from `(AsyncEntityExtractionListener listener, ContentChunkRepository chunkRepo, ChunkEntityMentionRepository mentionRepo, ...)` to take the new collaborators:

```java
public BootstrapEntityExtractionIndexer(
        PageExtractor pageExtractor,
        ProposalJudge judge,
        ProposalConsolidator consolidator,
        ProposalUpserter upserter,
        KgNodeEmbeddingService embeddingService,
        KgNodeEmbeddingRepository embeddingRepo,
        ContentChunkRepository chunkRepo,
        ChunkEntityMentionRepository mentionRepo,
        JdbcKnowledgeRepository kgRepo,
        MentionAttributor mentionAttributor,
        int concurrency,
        int dictionaryTopK,
        int maxEntitiesPerPage,    // forwarded to parser config — wire through if pageExtractor needs it
        int maxRelationsPerPage)
```

Keep the legacy constructors deprecated but functional during the transition, OR delete them — your call. Per CLAUDE.md the project tolerates breaking API changes (sole dev on main).

The `runBatch()` method is rewritten to follow the 8-step lifecycle above. Each step logs progress so polled `status()` calls show real-time counters.

For mention attribution, after upsert, query the `kg_nodes` table to look up the `node_id` for each accepted `displayName`; if a node doesn't exist yet (most won't on first run, since these are proposals not committed nodes), skip mention attribution for that name. Mention attribution lights up after admins approve proposals into real nodes — that's correct behaviour.

- [ ] **Step 4: Compile + run all tests in module**

```bash
mvn -pl wikantik-main test -q
```

Expected: BUILD SUCCESS. **Watch for compile failures in `WikiEngine.java`** if you changed the constructor signature — `WikiEngine` constructs the `BootstrapEntityExtractionIndexer` only via DI keyed on its class object (`managers.put(...)`). It doesn't directly call the constructor; the CLI does. So the only direct callers to update are `BootstrapExtractionCli.java` (Phase 4) and the indexer's tests.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexer.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexerTest.java
git commit -m "extraction: refactor BootstrapEntityExtractionIndexer to drive page pipeline

Replaces chunk-driven internals with: KgNodeEmbeddingService warmup,
per-page extraction, ProposalConsolidator, ProposalJudge (NoOp default),
ProposalUpserter, MentionAttributor. Status record carries new
consolidated/judge/upsert counters.

Save-time AsyncEntityExtractionListener stays untouched (out of scope).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 3.2: PageExtractionEndToEndIT

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/it/knowledge/PageExtractionEndToEndIT.java`
- Modify: `wikantik-it-tests/wikantik-it-test-rest/pom.xml` — add `wiremock-jre8-standalone` test dep if not present.

End-to-end IT: real PostgreSQL via Cargo, fake Ollama via WireMock. Seeds 5 deterministic pages, fires `BootstrapEntityExtractionIndexer.start()`, asserts `kg_proposals` rows.

- [ ] **Step 1: Add WireMock to the IT module pom**

In `wikantik-it-tests/wikantik-it-test-rest/pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
    <groupId>com.github.tomakehurst</groupId>
    <artifactId>wiremock-jre8-standalone</artifactId>
    <version>2.35.2</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the IT**

```java
package com.wikantik.it.knowledge;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingService;
import com.wikantik.knowledge.extraction.*;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.api.knowledge.*;
import com.wikantik.search.embedding.EmbeddingClient;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class PageExtractionEndToEndIT {

    private WireMockServer wireMock;
    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        wireMock = new WireMockServer(0);  // random port
        wireMock.start();
        // Stub the per-page extraction. All 5 pages return the same JSON
        // (varying text would matter for grounding; keep evidence_spans simple).
        wireMock.stubFor(post(urlEqualTo("/api/chat"))
            .willReturn(okJson("""
                {"message":{"content":"{\\"entities\\":[{\\"name\\":\\"Kafka\\",\\"type\\":\\"Technology\\",\\"evidence_span\\":\\"Kafka\\",\\"confidence\\":0.9}],\\"relations\\":[]}"}}
                """)));

        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(System.getProperty("wikantik.test.pg.url"));
        pg.setUser(System.getProperty("wikantik.test.pg.user", "jspwiki"));
        pg.setPassword(System.getProperty("wikantik.test.pg.password", ""));
        ds = pg;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM kg_proposals WHERE source_page LIKE 'IT_%'");
            st.execute("DELETE FROM kg_content_chunks WHERE page_name LIKE 'IT_%'");
            // Seed 5 pages with one chunk each containing the word "Kafka".
            for (int i = 1; i <= 5; i++) {
                st.executeUpdate(String.format(
                    "INSERT INTO kg_content_chunks (page_name, chunk_index, text, token_count_estimate) " +
                    "VALUES ('IT_Page%d', 0, 'Kafka is a streaming platform.', 8)", i));
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (wireMock != null) wireMock.stop();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM kg_proposals WHERE source_page LIKE 'IT_%'");
            st.execute("DELETE FROM kg_content_chunks WHERE page_name LIKE 'IT_%'");
        }
    }

    @Test
    void fivePagesProduceOneConsolidatedKafkaProposal() throws Exception {
        OllamaPageExtractor extractor = new OllamaPageExtractor(
            HttpClient.newHttpClient(), wireMock.baseUrl(), "gemma4-assist:latest", 60_000L,
            new PageExtractionResponseParser(new EvidenceGroundingVerifier(), 12, 8));

        JdbcKnowledgeRepository kgRepo = new JdbcKnowledgeRepository(ds);
        ContentChunkRepository chunkRepo = new ContentChunkRepository(ds);
        ChunkEntityMentionRepository mentionRepo = new ChunkEntityMentionRepository(ds);
        KgNodeEmbeddingRepository embRepo = new KgNodeEmbeddingRepository(ds);
        EmbeddingClient embClient = text -> new float[1024];   // zero vec; dictionary will be empty
        KgNodeEmbeddingService embService = new KgNodeEmbeddingService(embRepo, embClient, "bge-m3:latest");

        BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
            extractor, new NoOpProposalJudge(), new ProposalConsolidator(),
            new ProposalUpserter(kgRepo), embService, embRepo,
            chunkRepo, mentionRepo, kgRepo, new MentionAttributor(),
            1, 50, 12, 8);

        indexer.start(false);
        long deadline = System.currentTimeMillis() + 60_000;
        while (indexer.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(200);
        assertFalse(indexer.isRunning(), "indexer did not finish in 60s");

        BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals(5, s.processedPages());
        assertEquals(1, s.consolidatedCandidates(), "5 pages all naming Kafka should consolidate to 1 proposal");
        assertEquals(1, s.proposalsInserted());
    }
}
```

- [ ] **Step 3: Run and verify**

Run: `mvn -pl wikantik-it-tests/wikantik-it-test-rest test -Pintegration-tests -fae -Dtest=PageExtractionEndToEndIT`
Expected: BUILD SUCCESS. (Cargo will spin up Tomcat; PG must be running locally with V020 + V021 applied.)

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/it/knowledge/PageExtractionEndToEndIT.java \
        wikantik-it-tests/wikantik-it-test-rest/pom.xml
git commit -m "it: PageExtractionEndToEndIT covers 5-page → 1-consolidated-proposal path

WireMock fakes Ollama; real PG validates the consolidator collapses
5 identical extractions into a single kg_proposals row with support_count=5.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 3.3: PageExtractionIdempotencyIT

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/it/knowledge/PageExtractionIdempotencyIT.java`

Runs the same extraction twice; second run produces 0 new pending rows and only `last_seen_at` updates.

- [ ] **Step 1: Write the IT**

```java
package com.wikantik.it.knowledge;

// (same imports + setUp as Task 3.2, factor out into a base class if you prefer)

class PageExtractionIdempotencyIT extends PageExtractionEndToEndITBase {

    @Test
    void reRunIsIdempotent() throws Exception {
        // Same setup as 3.2: 5 pages, all extract Kafka.
        runOneExtraction();   // first run inserts 1 proposal
        Instant lastSeen1 = readLastSeenAt("Kafka");
        assertNotNull(lastSeen1);

        Thread.sleep(1100);   // ensure last_seen_at can move

        runOneExtraction();   // second run merges
        long pending = countPending("Kafka");
        Instant lastSeen2 = readLastSeenAt("Kafka");

        assertEquals(1, pending);
        assertTrue(lastSeen2.isAfter(lastSeen1));
        assertEquals(5, supportCountOf("Kafka"));   // DISTINCT ON dedup: NOT 10
    }
}
```

(`runOneExtraction`, `readLastSeenAt`, `countPending`, `supportCountOf` are helpers you factor out of the 3.2 IT into a `PageExtractionEndToEndITBase` class.)

- [ ] **Step 2: Verify, commit**

```bash
mvn -pl wikantik-it-tests/wikantik-it-test-rest test -Pintegration-tests -fae -Dtest=PageExtractionIdempotencyIT
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/it/knowledge/PageExtractionIdempotencyIT.java \
        wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/it/knowledge/PageExtractionEndToEndITBase.java
git commit -m "it: PageExtractionIdempotencyIT proves re-run merges support without duplicates

Same 5 pages run twice: support_count stays 5 (DISTINCT ON dedup by
sourcePage), last_seen_at moves forward, no second pending row.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Phase 4 — CLI surface

### Task 4.1: Refactor `BootstrapExtractionCli` (Args + run wiring)

**Files:**
- Modify: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java`

The existing `Args` class drops these fields/cases: `force`, `prefilterEnabled`, `prefilterDryRun`, `prefilterSkipCode`, `prefilterSkipNoProper`, `prefilterSkipShort`, `prefilterMinTokens`, `statsOnly`, `chunkerStatsOnly`, `chunkerMaxTokens`, `chunkerMergeForwardTokens`, `pagesDir`, `backend`, `confThreshold` is renamed/repurposed (keep but document new semantics), `maxNodes` → `dictionaryTopK`, `claudeModel` → `judgeModel`.

It adds: `judge` (`none|ollama|claude`), `judgeModel`, `anthropicKeyEnv`, `maxEntitiesPerPage`, `maxRelationsPerPage`, `dictionaryTopK`, `nodeEmbeddingModel`, `pagePattern`, `rebuildNodeEmbeddings`, `dryRun`, `report`.

- [ ] **Step 1: Add a smoke unit test for the new Args parsing**

Create `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/BootstrapExtractionCliArgsTest.java`:

```java
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapExtractionCliArgsTest {

    @Test
    void newFlagsParse() throws Exception {
        var a = invokeArgsParse(new String[]{
            "--judge", "none",
            "--max-entities-per-page", "10",
            "--max-relations-per-page", "5",
            "--dictionary-top-k", "30",
            "--node-embedding-model", "bge-m3:latest",
            "--max-pages", "20",
            "--page-pattern", "Knowledge*",
            "--rebuild-node-embeddings",
            "--dry-run",
            "--report", "/tmp/x.json"
        });
        assertEquals("none", a.getString("judge"));
        assertEquals(10, a.getInt("maxEntitiesPerPage"));
        assertEquals(5, a.getInt("maxRelationsPerPage"));
        assertEquals(30, a.getInt("dictionaryTopK"));
        assertTrue(a.getBoolean("rebuildNodeEmbeddings"));
        assertTrue(a.getBoolean("dryRun"));
        assertEquals("Knowledge*", a.getString("pagePattern"));
    }

    @Test
    void retiredFlagsRejected() {
        for (String flag : new String[]{"--prefilter", "--force", "--stats-only",
                                         "--max-existing-nodes", "--backend"}) {
            assertThrows(IllegalArgumentException.class,
                () -> invokeArgsParse(new String[]{flag, "x"}),
                "expected " + flag + " to be rejected");
        }
    }

    private TestArgs invokeArgsParse(String[] argv) throws Exception {
        // Use reflection to call the package-private Args class — or expose Args as public.
        // Implementation detail: simplest is to make Args public for testability.
        return TestArgs.from(BootstrapExtractionCli.Args.parse(argv));
    }
}
```

(The `TestArgs` adapter is a small helper that wraps the `Args` record/class for cleaner assertion access. Or, simpler: assert on `BootstrapExtractionCli.Args` fields directly if it's package-public.)

- [ ] **Step 2: Implement Args changes**

Refactor `Args.parse()`:
- Delete cases for retired flags (`--prefilter`, `--prefilter-dry-run`, `--no-prefilter-skip-code/nopn/short`, `--prefilter-min-tokens`, `--stats-only`, `--chunker-stats-only`, `--chunker-max-tokens`, `--chunker-merge-forward-tokens`, `--pages-dir`, `--backend`, `--max-existing-nodes`, `--force`).
- Add cases for new flags listed above.
- Default `concurrency` clamp to 1..6 (was 1..10).
- Default `confidenceThreshold` to 0.55.

Then refactor `run()` to construct the new pipeline:

```java
PageExtractionResponseParser parser = new PageExtractionResponseParser(
    new EvidenceGroundingVerifier(), a.maxEntitiesPerPage, a.maxRelationsPerPage);
OllamaPageExtractor extractor = new OllamaPageExtractor(
    HttpClient.newHttpClient(), a.ollamaUrl, a.ollamaModel, a.timeoutMs, parser);

ProposalJudge judge = switch (a.judge) {
    case "ollama" -> new OllamaProposalJudge(/* ... */);
    case "claude" -> {
        if (!Boolean.parseBoolean(System.getProperty("wikantik.kg.judge.allow_claude", "false"))) {
            System.err.println("error: --judge claude requires wikantik.kg.judge.allow_claude=true");
            System.exit(1);
        }
        yield new ClaudeProposalJudge(/* ... */);
    }
    default -> new NoOpProposalJudge();
};

ProposalConsolidator consolidator = new ProposalConsolidator();
JdbcKnowledgeRepository kgRepo = new JdbcKnowledgeRepository(ds);
ProposalUpserter upserter = new ProposalUpserter(kgRepo);
KgNodeEmbeddingRepository embRepo = new KgNodeEmbeddingRepository(ds);
EmbeddingClient embClient = new OllamaEmbeddingClient(/* a.ollamaUrl, a.nodeEmbeddingModel */);
KgNodeEmbeddingService embService = new KgNodeEmbeddingService(embRepo, embClient, a.nodeEmbeddingModel);

ContentChunkRepository chunkRepo = new ContentChunkRepository(ds);
ChunkEntityMentionRepository mentionRepo = new ChunkEntityMentionRepository(ds);

BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
    extractor, judge, consolidator, upserter, embService, embRepo,
    chunkRepo, mentionRepo, kgRepo, new MentionAttributor(),
    a.concurrency, a.dictionaryTopK, a.maxEntitiesPerPage, a.maxRelationsPerPage);

if (a.dryRun) {
    indexer.setDryRun(true);   // skip upsert step
}

indexer.start(false /* no force */, a.maxPages);
// Same wait+report loop as today.

// On completion, if a.report != null, dump indexer.status() as JSON.
```

For `--page-pattern`, add an overload of `start()` that takes a glob; convert to a regex inside the indexer and filter `chunkRepo.listDistinctPageNames()`.

The `--chunker-stats-only` mode is moving — see Task 4.3.

- [ ] **Step 3: Verify all unit tests + smoke compile of the CLI**

```bash
mvn -pl wikantik-extract-cli -am test -Dtest=BootstrapExtractionCliArgsTest -q
mvn -pl wikantik-extract-cli install -Dmaven.test.skip -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java \
        wikantik-extract-cli/src/test/java/com/wikantik/extractcli/BootstrapExtractionCliArgsTest.java
git commit -m "cli: rewire BootstrapExtractionCli for the page-extraction pipeline

Adds --judge / --max-entities-per-page / --max-relations-per-page /
--dictionary-top-k / --node-embedding-model / --page-pattern /
--rebuild-node-embeddings / --dry-run / --report. Drops --prefilter*,
--force, --backend, --max-existing-nodes, --stats-only,
--chunker-stats-only (moved to bin/kg-chunker-stats.sh per Task 4.3).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 4.2: Update `bin/kg-extract.sh`

**Files:**
- Modify: `bin/kg-extract.sh`

Update the help banner, the `extract_flag`/`has_flag` helpers, and the run banner to reflect new flags. Drop references to `--prefilter`, `--force`, `--backend` from the banner.

- [ ] **Step 1: Edit the script**

Find the `# Usage:` block at the top and replace flag examples:

```bash
# Usage:
#   bin/kg-extract.sh                          # full corpus, no judge
#   bin/kg-extract.sh --max-pages 50 --dry-run # smoke run
#   bin/kg-extract.sh --judge ollama --judge-model qwen3.5:9b
#   bin/kg-extract.sh --report reports/extract-$(date +%Y%m%d).json
#   bin/kg-extract.sh --help
```

In the banner section (around line 122), replace the `backend`/`prefilter`/`force` extraction with:

```bash
judge=$(extract_flag --judge "$@");           judge="${judge:-none}"
model=$(extract_flag --ollama-model "$@");    model="${model:-gemma4-assist:latest}"
concurrency=$(extract_flag --concurrency "$@"); concurrency="${concurrency:-2}"
dryrun="no"; has_flag --dry-run "$@" && dryrun="yes"

echo
echo -e "${BOLD}== Wikantik per-page entity extraction =="
info "DB:          ${jdbc_url} as ${jdbc_user}"
info "Model:       ${model}    judge: ${judge}"
info "Concurrency: ${concurrency}    dry-run: ${dryrun}"
info "Forwarded:   $*"
info "Progress lines arrive every --poll-seconds (default 30s)."
echo
```

- [ ] **Step 2: Smoke test**

```bash
bin/kg-extract.sh --help
bin/kg-extract.sh --max-pages 1 --dry-run --report /tmp/smoke.json
cat /tmp/smoke.json   # confirm a status JSON was written
```

- [ ] **Step 3: Commit**

```bash
git add bin/kg-extract.sh
git commit -m "bin: update kg-extract.sh banner and flags for page-extraction pipeline

New banner shows Model/judge/concurrency/dry-run; help links the new
flag set.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 4.3: Add `bin/kg-chunker-stats.sh`

**Files:**
- Create: `bin/kg-chunker-stats.sh`
- Modify: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java` — preserve the `runChunkerStatsOnly` method as a separate `ChunkerStatsCli.main` entrypoint OR move it to a new class.

Cleanest path: extract `runChunkerStatsOnly` and its `Args` subset into a new `ChunkerStatsCli` class; the `bin/kg-chunker-stats.sh` script invokes it.

- [ ] **Step 1: Create `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/ChunkerStatsCli.java`**

(Copy the existing `runChunkerStatsOnly` body from `BootstrapExtractionCli.java`, extract the few `Args` fields it depends on — `pagesDir`, `chunkerMaxTokens`, `chunkerMergeForwardTokens` — into a tiny local Args class. Add a `public static void main` entry.)

- [ ] **Step 2: Create `bin/kg-chunker-stats.sh`**

```bash
#!/bin/bash
# Inspect chunk-size distribution for the page corpus without touching the DB.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${ROOT_DIR}/wikantik-extract-cli/target/wikantik-extract-cli.jar"

if [ ! -f "${JAR}" ]( ! -f "${JAR}" ) || \
   find "${ROOT_DIR}/wikantik-extract-cli/src" -name '*.java' -newer "${JAR}" -print -quit | grep -q .; then
    (cd "${ROOT_DIR}" && mvn install -pl wikantik-extract-cli -am -Dmaven.test.skip -q)
fi

java -cp "${JAR}" com.wikantik.extractcli.ChunkerStatsCli "$@"
```

`chmod +x bin/kg-chunker-stats.sh`.

- [ ] **Step 3: Smoke test**

```bash
bin/kg-chunker-stats.sh --pages-dir docs/wikantik-pages
```

Expected: chunk distribution stats line, exit 0.

- [ ] **Step 4: Commit**

```bash
git add wikantik-extract-cli/src/main/java/com/wikantik/extractcli/ChunkerStatsCli.java \
        wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java \
        bin/kg-chunker-stats.sh
git commit -m "cli: split chunker-stats into ChunkerStatsCli + bin/kg-chunker-stats.sh

Keeps the chunker-tuning workflow alive but moves it out of the main
extractor CLI so kg-extract.sh stays focused on the new pipeline.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Phase 5 — Wipe + first real run (operator phase)

This phase is operational: human runs the commands, monitors output, decides when the run looks healthy enough to commit to. No code changes.

### Task 5.1: Document the wipe-then-fill workflow in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Append a short section near the existing extractor sections**

Add (look for the "Active Design Documents" or "Local Deployment" section as a sensible neighbor):

```markdown
## Running the entity extractor

`bin/kg-extract.sh` runs the per-page entity-extraction pipeline against the
local PostgreSQL via the deployed ROOT.xml. Defaults — `gemma4-assist:latest`
at concurrency 2, no judge — produce ~200–500 deduplicated, evidence-grounded
proposals in ~3.6 hours over a 1000-page corpus.

Routine usage:
```bash
bin/kg-extract.sh --max-pages 50 --dry-run --report reports/smoke.json   # smoke
bin/kg-extract.sh --report reports/extract-$(date +%Y%m%d).json          # full run
```

If the pending-proposal queue gets unwieldy and a clean restart is the right
call, snapshot pending proposals first, then wipe:

```bash
PGPASSWORD=… pg_dump -h localhost -U jspwiki -d jspwiki \
    --data-only --table=kg_proposals --column-inserts \
    --where="status = 'pending'" \
    > backups/kg_proposals_pending_$(date +%Y%m%d).sql

PGPASSWORD=… psql -h localhost -U jspwiki -d jspwiki -c \
    "DELETE FROM kg_proposals WHERE status = 'pending';"
```

Per the no-data-in-migrations rule, wipes are never landed in `Vxxx`
migrations — they are documented one-shots run by the operator.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude.md): document the new extractor + wipe-then-fill workflow

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 5.2: Snapshot, wipe, and run a smoke pass

**No code changes — operator workflow.**

- [ ] **Step 1: Snapshot pending proposals**

```bash
ROOT_XML=tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml
pw=$(grep -oE 'password="[^"]+"' "$ROOT_XML" | head -1 | sed -E 's/password="([^"]+)"/\1/')
mkdir -p backups
PGPASSWORD="$pw" pg_dump -h localhost -U jspwiki -d jspwiki \
    --data-only --table=kg_proposals --column-inserts \
    --where="status = 'pending'" \
    > "backups/kg_proposals_pending_pre_redesign_$(date +%Y%m%d).sql"
ls -lh backups/
```

Expected: a `.sql` file ~tens of MB containing 43,856 INSERT rows.

- [ ] **Step 2: Wipe pending**

```bash
PGPASSWORD="$pw" psql -h localhost -U jspwiki -d jspwiki -c \
    "DELETE FROM kg_proposals WHERE status = 'pending';"
PGPASSWORD="$pw" psql -h localhost -U jspwiki -d jspwiki -tAc \
    "SELECT COUNT(*) FROM kg_proposals WHERE status='pending';"
```

Expected: `DELETE 43856`, then `0`.

- [ ] **Step 3: Smoke run (50 pages, dry-run)**

```bash
mkdir -p reports
bin/kg-extract.sh --max-pages 50 --dry-run --report "reports/smoke-$(date +%Y%m%d).json"
```

Expected: ~22 minutes wall-clock (50 pages × 25.8 s ÷ 2 = 645 s + warmup), `reports/smoke-…json` exists. Open the report; confirm: ≥30 entities, no `"name":"Concept"`, all `evidence_span` strings appear in their source pages.

If quality looks wrong, STOP. Investigate (re-read prompt, sample a few extractor calls manually) before doing the full run.

- [ ] **Step 4: Full real run**

```bash
bin/kg-extract.sh --report "reports/extract-$(date +%Y%m%d).json" 2>&1 | tee "reports/extract-$(date +%Y%m%d).log"
```

Expected: ~3.6 hours wall-clock at concurrency 2, ~200–500 pending proposals at completion.

- [ ] **Step 5: Verify**

```bash
PGPASSWORD="$pw" psql -h localhost -U jspwiki -d jspwiki -c "
  SELECT COUNT(*) AS pending, MAX(support_count) AS max_support, AVG(support_count) AS avg_support
  FROM kg_proposals WHERE status='pending';
"
PGPASSWORD="$pw" psql -h localhost -U jspwiki -d jspwiki -c "
  SELECT proposed_data->>'name' AS name, support_count
  FROM kg_proposals WHERE status='pending' AND proposal_type='new-node'
  ORDER BY support_count DESC LIMIT 20;
"
```

Expected: hundreds (not thousands) of pending rows; the highest-support entries are real KG-worthy entities (not `Concept`/`Agent`/`LLM`).

- [ ] **Step 6: Tell the user** that the wipe + first run is done so they can start triaging.

(No commit for this task — it's operational. The reports stay outside git unless the user wants them.)

---

# Phase 6 — Optional judges + experiment harness

### Task 6.1: OllamaProposalJudge

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/OllamaProposalJudge.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/OllamaProposalJudgeTest.java`

Same wire shape as `OllamaPageExtractor` but with the judge prompt from spec §6.2.

- [ ] **Step 1: Failing test (Mockito-faked HttpClient)**

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class OllamaProposalJudgeTest {

    @Test @SuppressWarnings("unchecked")
    void parsesAcceptVerdict() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("""
            {"message":{"content":"{\\"verdict\\":\\"accept\\",\\"reason_code\\":\\"ok\\",\\"rationale\\":\\"strong evidence\\"}"}}
            """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        OllamaProposalJudge j = new OllamaProposalJudge(client, "http://x", "qwen3.5:9b", 60_000L);
        ConsolidatedProposal p = ConsolidatedProposal.newNode("sig", "Kafka", "Technology",
            List.of(new SupportEvidence("P", "Kafka is fast", 0.9, "x")), 0.9);
        Verdict v = j.judge(p, new JudgeContext(Map.of("P", "Kafka is fast"), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
    }

    @Test @SuppressWarnings("unchecked")
    void parsesRejectVerdict() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("""
            {"message":{"content":"{\\"verdict\\":\\"reject\\",\\"reason_code\\":\\"too_generic\\",\\"rationale\\":\\"too vague\\"}"}}
            """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        OllamaProposalJudge j = new OllamaProposalJudge(client, "u", "m", 60_000L);
        ConsolidatedProposal p = ConsolidatedProposal.newNode("sig", "Concept", "Concept",
            List.of(new SupportEvidence("P", "x", 0.5, "x")), 0.5);
        Verdict v = j.judge(p, new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Reject.class, v);
        assertEquals("too_generic", ((Verdict.Reject)v).reasonCode());
    }

    @Test @SuppressWarnings("unchecked")
    void malformedJudgeFailsOpen() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("not json");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        OllamaProposalJudge j = new OllamaProposalJudge(client, "u", "m", 60_000L);
        ConsolidatedProposal p = ConsolidatedProposal.newNode("sig", "X", "Concept",
            List.of(new SupportEvidence("P", "x", 0.5, "x")), 0.5);
        Verdict v = j.judge(p, new JudgeContext(Map.of(), List.of()));
        // Fail-open: malformed verdict accepts.
        assertInstanceOf(Verdict.Accept.class, v);
        assertTrue(((Verdict.Accept)v).rationale().startsWith("judge_failed"));
    }
}
```

- [ ] **Step 2: Implement**

The judge prompt is in spec §6.2. Build it once as a constant. Format the user payload as:

```
Candidate: <ConsolidatedProposal as JSON>
Supporting page excerpts:
  Page1: <≤500-char excerpt around the evidence_span>
  Page2: <...>
Nearby existing nodes: <neighborhoodNodes as a flat list "Name :: type">
```

Wire the same `format: "json"` Ollama call. On parse failure, return `Accept(p.aggregateConfidence(), "judge_failed: <reason>")`.

Full class:

```java
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.*;
import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OllamaProposalJudge implements ProposalJudge {

    private static final Logger LOG = LogManager.getLogger(OllamaProposalJudge.class);
    private static final Gson GSON = new Gson();
    private static final Set<String> ALLOWED_REASONS = Set.of(
        "ungrounded", "redundant_with_existing_node", "wrong_type", "too_generic", "weak_support");

    private static final String SYSTEM_PROMPT = """
        You are a strict reviewer for a small, curated knowledge graph. Reject anything
        that fails ANY of these tests:

        1. Ungrounded: the evidence_span doesn't actually support the claim.
        2. Too generic: the entity/predicate is so general it adds no graph value
           (Concept, Agent, System, Software, "is_related_to").
        3. Redundant: a near-identical node already exists in the dictionary.
        4. Wrong type: the type doesn't match the entity (e.g. "Kafka" typed as Person).
        5. Weak support: only one weak quote and aggregate_confidence < 0.55.

        When the entity is right but the form is wrong (e.g. "GitHub Inc." but "GitHub"
        exists), rewrite to the canonical form rather than reject.

        Output strict JSON: { "verdict": "accept"|"reject"|"rewrite",
                              "reason_code": str, "rationale": <=30 words,
                              "rewritten": { ...same shape as input... } | null }
        """;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final long timeoutMs;

    public OllamaProposalJudge(HttpClient httpClient, String baseUrl, String model, long timeoutMs) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeoutMs = timeoutMs;
    }

    @Override public String code() {
        String trimmed = model.trim();
        return "ollama:" + (trimmed.endsWith(":latest")
            ? trimmed.substring(0, trimmed.length() - ":latest".length()) : trimmed);
    }

    @Override
    public Verdict judge(ConsolidatedProposal proposal, JudgeContext context) {
        try {
            String raw = callOllama(proposal, context);
            return parseVerdict(raw, proposal);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Verdict.Accept(proposal.aggregateConfidence(), "judge_failed: interrupted");
        } catch (IOException | RuntimeException e) {
            LOG.warn("OllamaProposalJudge failed for {}: {}", proposal.signature(), e.getMessage());
            return new Verdict.Accept(proposal.aggregateConfidence(), "judge_failed: " + e.getMessage());
        }
    }

    private String callOllama(ConsolidatedProposal p, JudgeContext c) throws IOException, InterruptedException {
        StringBuilder user = new StringBuilder(2048);
        user.append("Candidate: ").append(GSON.toJson(p)).append('\n');
        if (!c.sourcePageBodies().isEmpty()) {
            user.append("Supporting page excerpts:\n");
            for (var entry : c.sourcePageBodies().entrySet()) {
                String body = entry.getValue();
                String excerpt = body.length() > 500 ? body.substring(0, 500) : body;
                user.append("  ").append(entry.getKey()).append(": ").append(excerpt).append('\n');
            }
        }
        if (!c.neighborhoodNodes().isEmpty()) {
            user.append("Nearby existing nodes:\n");
            for (KgNode n : c.neighborhoodNodes()) {
                user.append("  - ").append(n.name()).append(" :: ")
                    .append(n.nodeType() == null ? "concept" : n.nodeType().toLowerCase()).append('\n');
            }
        }
        user.append("\nReturn ONLY the JSON object.");

        Map<String, Object> body = Map.of(
            "model", model,
            "stream", false,
            "format", "json",
            "messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", user.toString())
            )
        );
        String url = stripTrailingSlash(baseUrl) + "/api/chat";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            LOG.warn("OllamaProposalJudge HTTP {} for sig {}", res.statusCode(), p.signature());
            return null;
        }
        JsonElement root = JsonParser.parseString(res.body());
        if (!root.isJsonObject()) return null;
        JsonElement message = root.getAsJsonObject().get("message");
        if (message == null || !message.isJsonObject()) return null;
        JsonElement content = message.getAsJsonObject().get("content");
        return content == null || content.isJsonNull() ? null : content.getAsString();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private Verdict parseVerdict(String raw, ConsolidatedProposal proposal) {
        if (raw == null) return new Verdict.Accept(proposal.aggregateConfidence(), "judge_failed: empty response");
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            String verdict = obj.get("verdict").getAsString().toLowerCase();
            String reason  = obj.has("reason_code") ? obj.get("reason_code").getAsString() : "ok";
            String rationale = obj.has("rationale") ? obj.get("rationale").getAsString() : "";
            return switch (verdict) {
                case "accept"  -> new Verdict.Accept(proposal.aggregateConfidence(), rationale);
                case "reject"  -> new Verdict.Reject(
                    ALLOWED_REASONS.contains(reason) ? reason : "weak_support", rationale);
                case "rewrite" -> {
                    // Rewrite path: parse 'rewritten' into a new ConsolidatedProposal with re-keyed signature.
                    // For initial implementation, fail-open if rewrite is malformed.
                    yield new Verdict.Accept(proposal.aggregateConfidence(),
                        "judge_failed: rewrite parsing not implemented yet");
                }
                default -> new Verdict.Accept(proposal.aggregateConfidence(), "judge_failed: unknown verdict " + verdict);
            };
        } catch (RuntimeException e) {
            return new Verdict.Accept(proposal.aggregateConfidence(), "judge_failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Verify green, commit**

```bash
mvn -pl wikantik-main test -Dtest=OllamaProposalJudgeTest -q
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/OllamaProposalJudge.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/OllamaProposalJudgeTest.java
git commit -m "extraction: add OllamaProposalJudge (opt-in; fail-open)

Same wire shape as the page extractor. Reject reason codes constrained
to the closed enum; malformed verdicts fall back to Accept with
'judge_failed' rationale so a misbehaving judge never silently kills
proposals.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 6.2: ClaudeProposalJudge (gated)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ClaudeProposalJudge.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/ClaudeProposalJudgeTest.java`

Mirror of `OllamaProposalJudge` but talks to the Anthropic Messages API. Constructor reads the API key from an env var; emits `Accept(judge_failed)` if the key is missing.

The key gate is at the **CLI level** (Task 4.1 already added `--judge claude` failing unless `wikantik.kg.judge.allow_claude=true`). The judge class itself is callable in tests without the gate, but won't run unless an `ANTHROPIC_API_KEY` env var is set.

- [ ] **Step 1: Write failing test**

```java
package com.wikantik.knowledge.extraction;

// Mocked HttpClient like Task 6.1, just calling the Anthropic /v1/messages shape.
// Same Accept/Reject/Rewrite/fail-open coverage.

class ClaudeProposalJudgeTest {

    @Test
    void noApiKeyFailsOpen() {
        ClaudeProposalJudge j = new ClaudeProposalJudge(null, "claude-haiku-4-5", 60_000L);
        ConsolidatedProposal p = /* ... */;
        Verdict v = j.judge(p, new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
    }

    // ...other tests mirror Task 6.1...
}
```

- [ ] **Step 2: Implement**

Make `OllamaProposalJudge.SYSTEM_PROMPT` package-public (drop the `private` modifier on that one constant) so this class can reuse it byte-for-byte. Then write the full class:

```java
package com.wikantik.knowledge.extraction;

import com.google.gson.*;
import com.wikantik.api.knowledge.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClaudeProposalJudge implements ProposalJudge {

    private static final Logger LOG = LogManager.getLogger(ClaudeProposalJudge.class);
    private static final Gson GSON = new Gson();
    private static final String ANTHROPIC_BASE = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Set<String> ALLOWED_REASONS = Set.of(
        "ungrounded", "redundant_with_existing_node", "wrong_type", "too_generic", "weak_support");

    private final String apiKey;
    private final String model;
    private final long timeoutMs;
    private final HttpClient httpClient;

    public ClaudeProposalJudge(String apiKey, String model, long timeoutMs) {
        this(apiKey, model, timeoutMs, HttpClient.newHttpClient());
    }

    /** Test-visible: inject a mock HttpClient. */
    ClaudeProposalJudge(String apiKey, String model, long timeoutMs, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.httpClient = httpClient;
    }

    @Override public String code() { return "claude:" + model; }

    @Override
    public Verdict judge(ConsolidatedProposal proposal, JudgeContext context) {
        if (apiKey == null || apiKey.isBlank()) {
            return new Verdict.Accept(proposal.aggregateConfidence(),
                "judge_failed: ANTHROPIC_API_KEY missing");
        }
        try {
            String raw = callAnthropic(proposal, context);
            return parseVerdict(raw, proposal);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Verdict.Accept(proposal.aggregateConfidence(), "judge_failed: interrupted");
        } catch (IOException | RuntimeException e) {
            LOG.warn("ClaudeProposalJudge failed for {}: {}", proposal.signature(), e.getMessage());
            return new Verdict.Accept(proposal.aggregateConfidence(), "judge_failed: " + e.getMessage());
        }
    }

    private String callAnthropic(ConsolidatedProposal p, JudgeContext c) throws IOException, InterruptedException {
        StringBuilder user = new StringBuilder(2048);
        user.append("Candidate: ").append(GSON.toJson(p)).append('\n');
        if (!c.sourcePageBodies().isEmpty()) {
            user.append("Supporting page excerpts:\n");
            for (var entry : c.sourcePageBodies().entrySet()) {
                String body = entry.getValue();
                String excerpt = body.length() > 500 ? body.substring(0, 500) : body;
                user.append("  ").append(entry.getKey()).append(": ").append(excerpt).append('\n');
            }
        }
        if (!c.neighborhoodNodes().isEmpty()) {
            user.append("Nearby existing nodes:\n");
            for (KgNode n : c.neighborhoodNodes()) {
                user.append("  - ").append(n.name()).append(" :: ")
                    .append(n.nodeType() == null ? "concept" : n.nodeType().toLowerCase()).append('\n');
            }
        }
        user.append("\nReturn ONLY the JSON object.");

        Map<String, Object> body = Map.of(
            "model", model,
            "max_tokens", 1024,
            "system", OllamaProposalJudge.SYSTEM_PROMPT,
            "messages", List.of(Map.of("role", "user", "content", user.toString()))
        );
        HttpRequest req = HttpRequest.newBuilder(URI.create(ANTHROPIC_BASE))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            LOG.warn("ClaudeProposalJudge HTTP {} for sig {}: {}", res.statusCode(), p.signature(), res.body());
            return null;
        }
        // Anthropic shape: { "content": [ { "type":"text", "text":"..." } ], ... }
        JsonElement root = JsonParser.parseString(res.body());
        if (!root.isJsonObject()) return null;
        JsonElement contentArr = root.getAsJsonObject().get("content");
        if (contentArr == null || !contentArr.isJsonArray() || contentArr.getAsJsonArray().size() == 0) {
            return null;
        }
        JsonElement first = contentArr.getAsJsonArray().get(0);
        if (!first.isJsonObject()) return null;
        JsonElement text = first.getAsJsonObject().get("text");
        return text == null || text.isJsonNull() ? null : text.getAsString();
    }

    private Verdict parseVerdict(String raw, ConsolidatedProposal proposal) {
        if (raw == null) return new Verdict.Accept(proposal.aggregateConfidence(), "judge_failed: empty response");
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            String verdict = obj.get("verdict").getAsString().toLowerCase();
            String reason  = obj.has("reason_code") ? obj.get("reason_code").getAsString() : "ok";
            String rationale = obj.has("rationale") ? obj.get("rationale").getAsString() : "";
            return switch (verdict) {
                case "accept"  -> new Verdict.Accept(proposal.aggregateConfidence(), rationale);
                case "reject"  -> new Verdict.Reject(
                    ALLOWED_REASONS.contains(reason) ? reason : "weak_support", rationale);
                case "rewrite" -> new Verdict.Accept(proposal.aggregateConfidence(),
                    "judge_failed: rewrite parsing not implemented yet");
                default -> new Verdict.Accept(proposal.aggregateConfidence(),
                    "judge_failed: unknown verdict " + verdict);
            };
        } catch (RuntimeException e) {
            return new Verdict.Accept(proposal.aggregateConfidence(), "judge_failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Verify green, commit**

```bash
mvn -pl wikantik-main test -Dtest=ClaudeProposalJudgeTest -q
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ClaudeProposalJudge.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/ClaudeProposalJudgeTest.java
git commit -m "extraction: add ClaudeProposalJudge (gated by allow_claude property)

Same prompt as OllamaProposalJudge for A/B comparability. Missing API
key fails open. Production gate is at the CLI; class is callable from
tests without the gate.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 6.3: JudgeExperimentCli + bin/kg-judge-experiment.sh

**Files:**
- Create: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/JudgeExperimentCli.java`
- Create: `bin/kg-judge-experiment.sh`

The experiment script reads pending proposals from `kg_proposals`, samples N at random, runs each through both `NoOpProposalJudge` and a chosen comparator judge (`ollama` or `claude`), writes the side-by-side report to `--output`.

- [ ] **Step 1: Implement `JudgeExperimentCli.java`**

Args:
- `--jdbc-url`, `--jdbc-user`, `--jdbc-password-env` (same as `BootstrapExtractionCli`)
- `--judge ollama|claude` (no `none` — experiment requires a real judge)
- `--judge-model`
- `--anthropic-key-env` (Claude only)
- `--sample N` (default 100)
- `--output <path.json>` (required)

Logic:
1. Connect to DB; load all pending proposals via `kgRepo.listProposals("pending", null, sample, 0)` (or a randomized variant).
2. For each, build a `ConsolidatedProposal` from the row's `proposed_data` + `support` JSONB.
3. Build a `JudgeContext` by reading the bodies of the support pages (simplest: pull from `kg_content_chunks` and stitch).
4. Run NoOp + comparator. Record the verdicts.
5. Aggregate counts, total tokens (Claude only — Ollama doesn't bill), example proposals.
6. Write JSON report.

- [ ] **Step 2: Wrap in `bin/kg-judge-experiment.sh`** (same pattern as `bin/kg-extract.sh`: rebuild jar if stale, extract DB creds from ROOT.xml, exec the cli).

- [ ] **Step 3: Verify the script runs end-to-end against the local DB**

```bash
bin/kg-judge-experiment.sh --judge ollama --judge-model qwen3.5:9b \
    --sample 10 --output /tmp/judge-experiment.json
cat /tmp/judge-experiment.json | jq '.claude_verdicts // .ollama_verdicts'
```

Expected: a JSON file with verdict counts, a few example proposals.

- [ ] **Step 4: Commit**

```bash
git add wikantik-extract-cli/src/main/java/com/wikantik/extractcli/JudgeExperimentCli.java \
        bin/kg-judge-experiment.sh
git commit -m "cli: add JudgeExperimentCli + bin/kg-judge-experiment.sh

Reads pending proposals, runs each through NoOp + (ollama|claude)
judges, writes a side-by-side JSON report. Lets the operator decide
whether to enable an opt-in judge in production runs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Done

After Phase 6, the new pipeline is fully wired and the optional judge stage has both production-ready (`OllamaProposalJudge`) and experimental (`ClaudeProposalJudge`) backends. Recommended follow-ups (out of scope for this plan):

- Migrate the save-time `AsyncEntityExtractionListener` (still wired in `WikiEngine.java:963`) to the new pipeline. Currently per-chunk extraction continues to run on every page save.
- Migrate the MCP `propose_knowledge` write path to also compute signatures so it benefits from the partial unique index.
- Article-typed-node cleanup (917 of 1020 nodes are page-titles, not entities) — separate cleanup pass.
- Confidence calibration for the Ollama extractor (small models return 1.0 too readily).
