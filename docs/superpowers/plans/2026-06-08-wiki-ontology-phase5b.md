# Wiki Ontology — Phase 5b Implementation Plan (Write-Time SHACL Gate + Extractor Harmonization)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. (This session runs it **directly/inline**.) Steps use checkbox (`- [ ]`).

**Goal:** Keep the KG ontology-conformant going forward — (A) reject/skip ontology-non-conformant edges at the two acceptance chokepoints (SHACL domain/range), and (B) harmonize both entity extractors onto a single canonical 9-class node_type vocabulary.

**Architecture:** `OntologyShaclValidator` gains `validateEdge(sourceType, relationshipType, targetType)` (builds a typed mini-graph + validates against `shapes.ttl`). It's injected (via `KnowledgeSubsystemFactory`) into `DefaultKgCurationOps.tryUpsertEdge` (human/MCP path → **reject with cited reason**) and `KgMaterializationService.materialize` (machine path → **skip + log + count**). A new `EntityTypeVocabulary.ENTITY_CLASSES` in `wikantik-api` (mirroring `RelationshipTypeVocabulary`) becomes the single source of the 9 entity types; both extractors' prompts + parsers align to it.

**Tech Stack:** Java 21, JUnit 5 + Mockito, jena-shacl, Cargo IT (the `curate_edges` refusal IT, per the MCP write-surface pairing rule).

**Spec:** `docs/superpowers/specs/2026-06-08-wiki-ontology-design.md` (§5 Phase 5 — the write-gate + extraction-harmonization portions).

---

## Decisions & constraints

- **The gate is narrow + safe by construction:** `shapes.ttl` has 2 explicit shapes (`wk:implements`: Technology→Concept; `wk:locatedIn`: range Place). `validateEdge` only flags edges violating an *explicit* shape; predicates with no shape (the other 19) always pass — so **no false positives** beyond the 2 declared constraints. As shapes are added, the gate tightens automatically.
- **Two chokepoints, two behaviors:** the **human/MCP** path (`curate_edges` → `DefaultKgCurationOps.tryUpsertEdge`) **rejects** with a cited reason (per `feedback_mcp_write_surface_pairing` — refusal cites the reason, ships with Mockito unit + Cargo IT). The **machine** path (`KgMaterializationService.materialize`) **skips + logs + counts** the bad edge rather than failing the whole batch.
- **9-class vocabulary** = {person, organization, place, event, product, technology, concept, project, version} (lowercase), in `wikantik-api` `EntityTypeVocabulary`. Chunk extractor today lists 8 (missing **Technology**) + no parser validation; page extractor lists 7 (missing **Project**, **Version**) + a 7-type `ALLOWED_TYPES`. Both align to the 9.
- **Brittle test flagged by research:** `PageExtractionPromptBuilderTest:27-28` asserts the exact 7-type string and that `|Project|` is absent — both must be updated when the prompt gains Project/Version.
- **No module cycle:** wikantik-main → wikantik-ontology (compile) is established; the canonical set lives in wikantik-api (both sides depend on it). No new `getManager` → ArchUnit untouched.
- **Apache header** on every new `.java`.

---

## PART A — Write-time SHACL gate

### Task A1: `OntologyShaclValidator.validateEdge(...)`

**Files:** edit `OntologyShaclValidator.java`; edit `OntologyShaclValidatorTest.java` (wikantik-ontology).

- [ ] **Step 1: Add failing tests:**

```java
    @Test
    void validateEdge_conformantImplementsPasses() {
        assertTrue( new OntologyShaclValidator()
                .validateEdge( "technology", "implements", "concept" ).isEmpty() );
    }

    @Test
    void validateEdge_personImplementsIsRejected() {
        assertFalse( new OntologyShaclValidator()
                .validateEdge( "person", "implements", "concept" ).isEmpty(),
                "a non-Technology subject of wk:implements must violate the domain shape" );
    }

    @Test
    void validateEdge_predicateWithNoShapePasses() {
        // 'uses' has no SHACL shape -> never a false positive.
        assertTrue( new OntologyShaclValidator()
                .validateEdge( "person", "uses", "concept" ).isEmpty() );
    }
```

- [ ] **Step 2: Run RED.**

- [ ] **Step 3: Implement `validateEdge`** — build a 3-triple typed mini-graph and reuse `validate(Model)`:

```java
    /** Validates a single candidate edge against the SHACL shapes (typed endpoints + the predicate). */
    public List< Violation > validateEdge( final String sourceNodeType, final String relationshipType,
                                           final String targetNodeType ) {
        final org.apache.jena.rdf.model.Model m = ModelFactory.createDefaultModel();
        final org.apache.jena.rdf.model.Resource src = m.createResource( "urn:edge:src" );
        final org.apache.jena.rdf.model.Resource tgt = m.createResource( "urn:edge:tgt" );
        src.addProperty( org.apache.jena.vocabulary.RDF.type,
                m.createResource( Iris.term( NodeTypeMapping.classLocalName( sourceNodeType ) ) ) );
        tgt.addProperty( org.apache.jena.vocabulary.RDF.type,
                m.createResource( Iris.term( NodeTypeMapping.classLocalName( targetNodeType ) ) ) );
        src.addProperty( m.createProperty( Iris.term( propertyLocalName( relationshipType ) ) ), tgt );
        return validate( m );
    }

    /** snake_case relationship_type -> wk: lowerCamel property local name. */
    private static String propertyLocalName( final String relationshipType ) {
        final String[] parts = relationshipType.split( "_" );
        final StringBuilder sb = new StringBuilder( parts[ 0 ] );
        for ( int i = 1; i < parts.length; i++ ) {
            sb.append( Character.toUpperCase( parts[ i ].charAt( 0 ) ) ).append( parts[ i ].substring( 1 ) );
        }
        return sb.toString();
    }
```

- [ ] **Step 4: Run GREEN. Commit + install wikantik-ontology.**

### Task A2: Gate `DefaultKgCurationOps.tryUpsertEdge` (reject + cite)

**Files:** read `DefaultKgCurationOps.java` (chokepoint ~line 128, refusal template ~136-142) + its constructor; edit + add a Mockito test in `DefaultKgCurationOpsTest`.

- [ ] **Step 1:** Inject `OntologyShaclValidator` + `KgNodeRepository` (if not already present) into `DefaultKgCurationOps`. Read the constructor + its construction site (`KnowledgeSubsystemFactory`/wiring) first.
- [ ] **Step 2: Write the failing Mockito test** — `tryUpsertEdge` for a `person --implements--> concept` edge returns a refusal whose error message cites the SHACL/ontology reason; a conformant `technology --implements--> concept` upserts normally (mirror `DefaultKgCurationOpsTest:134-151`).
- [ ] **Step 3: Implement the gate** before `kg.upsertEdge(...)` (line ~132): look up `nodes.getNode(sourceId)`/`getNode(targetId)` for their `nodeType()`, call `validator.validateEdge(srcType, rel, tgtType)`; if non-empty, return a refusal Result citing the first violation message (mirror the mixed-edge refusal at 136-142). Skip the check gracefully if the validator is null (degrade, don't fail).
- [ ] **Step 4: Run GREEN. Commit.**

### Task A3: Gate `KgMaterializationService.materialize` (skip + log + count)

**Files:** read `KgMaterializationService.java` (chokepoint ~line 121, `src`/`tgt` KgNode in scope ~106-119); edit + add a unit test.

- [ ] **Step 1: Write the failing test** — a materialization run including one non-conformant edge persists the conformant edges and **skips** the bad one (assert the skip via a returned count or the edge's absence). Use the existing materialization test harness.
- [ ] **Step 2: Implement** — before `edges.upsertEdgeWithProvenance(...)`, call `validator.validateEdge(src.nodeType(), rel, tgt.nodeType())`; if non-empty, `LOG.warn(...)`, increment a `skippedNonConformant` counter, and `continue` (don't persist). Null-validator → no-op.
- [ ] **Step 3: Run GREEN. Commit.**

### Task A4: Wire the validator + `curate_edges` refusal IT

**Files:** `KnowledgeSubsystemFactory` (construct one `OntologyShaclValidator`, thread into both); `KgCurationIT` (add a refusal assertion).

- [ ] **Step 1:** Construct a singleton `OntologyShaclValidator` in `KnowledgeSubsystemFactory` and pass it to `DefaultKgCurationOps` + `KgMaterializationService` (read the factory; mirror how other collaborators are threaded). Compile + ArchUnit (no new getManager; store clean).
- [ ] **Step 2: Add a `curate_edges` IT** (`KgCurationIT`, mirror lines 326-362) — via the admin-mcp `curate_edges` tool, attempt to create a `person --implements--> concept` edge; assert the tool returns an error result whose message cites the ontology/SHACL reason, and the edge is NOT created. (Confirm a conformant edge still succeeds.)
- [ ] **Step 3: Commit** (IT runs in the final full-IT-reactor gate).

---

## PART B — Extractor harmonization (9-class vocabulary)

### Task B1: `EntityTypeVocabulary` (wikantik-api) + drift guard

**Files:** create `wikantik-api/.../api/knowledge/EntityTypeVocabulary.java`; test in `wikantik-ontology` (drift-guard against `NodeTypeMapping`).

- [ ] **Step 1: Create the constant:**

```java
package com.wikantik.api.knowledge;

import java.util.List;

/** Single source of truth for the canonical entity-type vocabulary (lowercase node_type values). */
public final class EntityTypeVocabulary {
    private EntityTypeVocabulary() {}
    public static final List< String > ENTITY_CLASSES = List.of(
            "person", "organization", "place", "event", "product",
            "technology", "concept", "project", "version" );
}
```

- [ ] **Step 2: Drift-guard test** (wikantik-ontology) — assert `NodeTypeMapping.classLocalName(t)` maps each `EntityTypeVocabulary.ENTITY_CLASSES` entry to a non-default-by-accident class (i.e. each of the 9 has an explicit mapping, not the fallback). Run + commit.

### Task B2: Chunk extractor → 9 classes

**Files:** edit `ExtractionPromptBuilder.java` (add Technology → 9-type list, ideally generated from `EntityTypeVocabulary`); edit `ExtractionResponseParser.java` (normalize/allowlist to the 9, default `concept`); test in `ExtractionResponseParserTest`.

- [ ] **Step 1: Failing test** — parser maps an out-of-vocab type (e.g. `"Thing"`/`"Gadget"`) to `concept`, and keeps an in-vocab type (e.g. `"Technology"`).
- [ ] **Step 2: Implement** — prompt lists all 9 (incl. Technology); parser lowercases + checks `EntityTypeVocabulary.ENTITY_CLASSES.contains(...)`, else `concept`. Run GREEN. Commit.

### Task B3: Page extractor → 9 classes (+ fix brittle test)

**Files:** edit `PageExtractionPromptBuilder.java` (add Project, Version); edit `PageExtractionResponseParser.java` (`ALLOWED_TYPES` → the 9, from `EntityTypeVocabulary`); **fix `PageExtractionPromptBuilderTest:27-28`** (the brittle 7-type + `|Project|`-absent assertions).

- [ ] **Step 1: Update `PageExtractionPromptBuilderTest`** to assert all 9 types appear (loop over `EntityTypeVocabulary.ENTITY_CLASSES`, or assert the new full list) and remove the `assertFalse(...contains("|Project|"))`. Run — should FAIL against the current 7-type prompt.
- [ ] **Step 2: Implement** — prompt lists 9; `ALLOWED_TYPES = Set.copyOf(EntityTypeVocabulary.ENTITY_CLASSES)`. Run GREEN. Commit.

---

## Task C1: Gates + docs/memory

- [ ] **Step 1: Full unit reactor** `mvn -o clean install -T 1C -DskipITs` (all modules + ArchUnit + the new unit tests; confirms the brittle page-prompt test is fixed and nothing else broke).
- [ ] **Step 2: Full IT reactor** `mvn -o install -Pintegration-tests -Dsurefire.skip=true -fae`; verify failsafe aggregate (Failures: 0) — includes the `curate_edges` refusal IT.
- [ ] **Step 3: Docs + memory** — CLAUDE.md: note the write-time SHACL gate on the curation/materialization paths + the harmonized 9-class vocabulary; project memory → Phase 5b complete (gate is narrow = only the 2 explicit shapes today; extractors now emit the 9). Note the `EntityTypeVocabulary` as the canonical set.

---

## Self-Review (against spec §5 Phase 5, deferred portions)

- "SHACL gate on proposed KG edges at acceptance" → Tasks A1–A4 (both chokepoints; reject on human path, skip on machine path; narrow + false-positive-free). ✓
- "admin-mcp `curate_edges` refusal cites reason + unit + IT" (`feedback_mcp_write_surface_pairing`) → A2 (Mockito unit) + A4 (Cargo IT). ✓
- "Both extractors unified onto the 9-class vocabulary" → B1–B3 (`EntityTypeVocabulary` + both prompts + both parsers + brittle test fixed). ✓
- "Extractor tests emit only ontology classes" → B2/B3 parser tests. ✓

**Placeholders:** A1 + B1 are complete code; A2/A3/A4/B2/B3 specify the exact chokepoint + behavior + test shape but defer constructor/wiring details to read-at-edit-time (existing structures — `DefaultKgCurationOps`, `KgMaterializationService`, `KnowledgeSubsystemFactory`, the two parsers — mirrored, not redesigned), each flagged.

**Type consistency:** `OntologyShaclValidator.validateEdge(srcType, rel, tgtType) -> List<Violation>` used by both gates; `NodeTypeMapping.classLocalName` + `Iris.term` (existing) inside it; `EntityTypeVocabulary.ENTITY_CLASSES` shared by both parsers + the drift guard.
