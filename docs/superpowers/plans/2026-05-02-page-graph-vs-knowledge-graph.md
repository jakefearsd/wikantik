# Page Graph vs Knowledge Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define Page Graph as wikilinks-only, delete the frontmatter `relations:` mechanism (DAOs, REST resource, MCP tool, API types, the typed-edge palette in the frontend), then perform the top-to-bottom rename that gives the Page Graph and Knowledge Graph parallel names across Java packages, REST routes, frontend folders, MCP descriptions, and docs.

**Architecture:** Single-monorepo refactor in two ordered phases. Phase A removes `relations:` first because the affected files (`PageRelationsDao`, `FrontmatterRelationValidator`, `RelationType`, etc.) live inside packages being moved in Phase B — deleting them first means Phase B only moves what survives. Phase B is a series of mechanical renames with two HTTP 301 redirects for back-compat. No new Maven module; package-only refactor.

**Tech Stack:** Java 21 / Maven; React (Vite); PostgreSQL 15+; Servlet API filters for SPA routing; JAX-RS-shape REST resources; MCP Streamable HTTP. Build via `mvn`, frontend via `npm` (invoked by Maven).

**Spec:** `docs/superpowers/specs/2026-05-02-page-graph-vs-knowledge-graph-design.md`

---

## Phase A — Remove the `relations:` mechanism

### Task 1: Strip `relations:` examples from the 2 doc pages that show them

**Discovery correction:** No content pages have `relations:` in their actual
frontmatter. The only `relations:` references in the corpus are illustrative
YAML examples inside code blocks in three documentation pages
(`McpIntegration.md`, `TextFormattingRules.md`, `StructuralSpineDesign.md`).
`StructuralSpineDesign.md` is rewritten in Task 27, so this task handles
only the other two.

**Files:**
- Modify: `docs/wikantik-pages/McpIntegration.md`
- Modify: `docs/wikantik-pages/TextFormattingRules.md`

- [ ] **Step 1: Strip the `relations:` lines from McpIntegration.md.**

Open `docs/wikantik-pages/McpIntegration.md`. Around line 337 there is an
example frontmatter block in a code fence. Delete the two lines:

```
relations:
  - { type: depends_on, target: KnowledgeGraphCore }
```

Leave every other line in the example frontmatter intact.

- [ ] **Step 2: Remove the "Typed relations" section from TextFormattingRules.md.**

Open `docs/wikantik-pages/TextFormattingRules.md`. Around line 425 there is a
section beginning with the heading `### Typed relations` (or similar level),
immediately followed by an explanatory paragraph and a code-block example
(lines ~429–435 show the `relations:` YAML), and a vocabulary table. Delete
the entire section: from the heading through the end of the table that
documents the relation-type vocabulary, stopping just before the next
section header. Adjust surrounding blank lines so the document remains
tidy.

If the section's removal leaves an awkward seam (e.g. the previous section
referenced "Typed relations" inline), edit the prior paragraph to remove
or rewrite that forward-reference. Do not invent new content.

- [ ] **Step 3: Verify no `relations:` remains outside StructuralSpineDesign.md.**

Run: `grep -rnE "^relations:" docs/wikantik-pages/ | grep -v StructuralSpineDesign.md`
Expected: no output.

(`StructuralSpineDesign.md` still contains an example `relations:` block
which Task 27 will remove during its full rewrite.)

- [ ] **Step 4: Commit.**

```bash
git add docs/wikantik-pages/McpIntegration.md docs/wikantik-pages/TextFormattingRules.md
git commit -m "$(cat <<'EOF'
docs: strip relations: examples from McpIntegration and TextFormattingRules

Both pages contained illustrative YAML showing the typed-relation
grammar inside code-block examples. The grammar is being removed
in this work (see 2026-05-02 spec); the examples are stripped to
match. StructuralSpineDesign.md retains its example for now and is
rewritten in Task 27.

Original spec asserted three content pages used relations: in their
frontmatter; on inspection, no content pages did — only doc-page
examples. Spec/plan updated to match reality.
EOF
)"
```

---

### Task 2: Delete `TraverseRelationsTool` (Knowledge MCP)

**Files:**
- Delete: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/TraverseRelationsTool.java`
- Delete: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/TraverseRelationsToolTest.java`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java` (remove the tool registration)

- [ ] **Step 1: Find the tool's registration call in the initializer.**

Run: `grep -n "TraverseRelationsTool" wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`
Expected: a `new TraverseRelationsTool(...)` or `register(... TraverseRelationsTool ...)` line — note the line number.

- [ ] **Step 2: Delete the tool source and test files.**

```bash
git rm wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/TraverseRelationsTool.java
git rm wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/TraverseRelationsToolTest.java
```

- [ ] **Step 3: Remove the registration line from the initializer.**

Open `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java`, delete the line(s) that construct or register `TraverseRelationsTool`, and remove its `import` line at the top.

- [ ] **Step 4: Verify the module compiles and the registry is one tool lighter.**

Run: `mvn compile -pl wikantik-knowledge -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "knowledge-mcp: remove TraverseRelationsTool (frontmatter relations: deprecated)"
```

---

### Task 3: Delete `PageRelationsResource` (REST)

**Files:**
- Delete: `wikantik-rest/src/main/java/com/wikantik/rest/PageRelationsResource.java`
- Delete: `wikantik-rest/src/test/java/com/wikantik/rest/PageRelationsResourceTest.java`
- Modify: any servlet-mounting code that wires `/api/pages/*/relations` (locate via grep)

- [ ] **Step 1: Find the resource's mount point.**

Run: `grep -rn "PageRelationsResource\|/api/pages/.*/relations" --include="*.java" wikantik-rest/src/main/java/`
Expected: the resource class itself plus a mount in a servlet wiring class (e.g. `RestApplication`, `WikiServletInitializer`, or a `@Path` annotation on the resource).

- [ ] **Step 2: Delete the source and test files.**

```bash
git rm wikantik-rest/src/main/java/com/wikantik/rest/PageRelationsResource.java
git rm wikantik-rest/src/test/java/com/wikantik/rest/PageRelationsResourceTest.java
```

- [ ] **Step 3: Remove the mount.**

In whichever wiring file the previous grep surfaced, delete the line(s) that register `PageRelationsResource`. Remove its `import` line.

- [ ] **Step 4: Verify the module compiles.**

Run: `mvn compile -pl wikantik-rest -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "rest: remove PageRelationsResource (frontmatter relations: deprecated)"
```

---

### Task 4: Strip the `relations:` validation branch from `StructuralSpinePageFilter`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/structure/StructuralSpinePageFilter.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/structure/StructuralSpinePageFilterTest.java`

- [ ] **Step 1: Run the filter test to capture current state.**

Run: `mvn test -pl wikantik-main -Dtest=StructuralSpinePageFilterTest -q`
Expected: PASS (baseline before changes).

- [ ] **Step 2: Remove the `relations:` validation branch.**

Open `StructuralSpinePageFilter.java`. Find the block starting at the `// -- relations validation --` comment (around line 125) and continuing through the `relationsField`/`FrontmatterRelationValidator.validate(...)` invocation and any conditional that throws `FilterException` on invalid relations. Delete the entire block. Remove the `import com.wikantik.knowledge.structure.FrontmatterRelationValidator` line if present.

The canonical-id auto-assignment logic stays. No other behaviour changes.

- [ ] **Step 3: Update the filter test.**

Open `StructuralSpinePageFilterTest.java`. Delete every test method whose name references `relations`, `Relation`, `invalidRelation`, etc. (e.g. `rejectsInvalidRelationType`, `rejectsUnresolvableRelationTarget`). Keep all canonical-id and cluster tests intact. Remove now-unused imports.

- [ ] **Step 4: Run the test suite for the filter.**

Run: `mvn test -pl wikantik-main -Dtest=StructuralSpinePageFilterTest -q`
Expected: PASS, with the canonical-id and cluster test methods executing and the relations test methods gone.

- [ ] **Step 5: Commit.**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/structure/StructuralSpinePageFilter.java wikantik-main/src/test/java/com/wikantik/knowledge/structure/StructuralSpinePageFilterTest.java
git commit -m "spine: drop relations: validation branch from StructuralSpinePageFilter"
```

---

### Task 5: Trim `outgoingRelations` / `incomingRelations` from `ForAgentProjection`

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/agent/ForAgentProjection.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionServiceTest.java`

This is a backward-incompatible change to the `/api/pages/for-agent/{id}` response shape. Per spec section 1, the deleted relations grammar has no replacement; agents needing page-link traversal call `get_outbound_links` / `get_backlinks` separately.

- [ ] **Step 1: Open `ForAgentProjection` and locate the relation fields.**

Run: `grep -n "outgoingRelations\|incomingRelations\|RelationEdge" wikantik-api/src/main/java/com/wikantik/api/agent/ForAgentProjection.java`
Expected: the `outgoingRelations` and `incomingRelations` record components (or fields), the import of `RelationEdge`, and any defensive-copy lines in the constructor.

- [ ] **Step 2: Remove the relation fields from `ForAgentProjection`.**

Delete:
- The `outgoingRelations` and `incomingRelations` record components.
- The corresponding constructor parameters.
- The defensive-copy lines for both.
- The `import com.wikantik.api.structure.RelationEdge;` line.

- [ ] **Step 3: Remove the relation lookups from `DefaultForAgentProjectionService`.**

Open `DefaultForAgentProjectionService.java`. Delete the entire `// Relations.` block (around lines 152–160) including the `outgoing` / `incoming` local variables, the try/catch wrapping `index.outgoingRelations(...)` and `index.incomingRelations(...)`, and the `missing.add("typed_relations")` call. Remove the `outgoing` and `incoming` arguments from the `ForAgentProjection` construction (around line 229). Remove the `import com.wikantik.api.structure.RelationEdge;` line.

- [ ] **Step 4: Update the projection service test.**

Open `DefaultForAgentProjectionServiceTest.java`. Delete every assertion or helper that references `outgoingRelations`, `incomingRelations`, or `typed_relations`. Update construction of expected `ForAgentProjection` instances to use the new (smaller) constructor signature. Delete tests dedicated solely to relation behavior.

- [ ] **Step 5: Compile-check and run the test.**

Run: `mvn test -pl wikantik-main -am -Dtest=DefaultForAgentProjectionServiceTest -q`
Expected: BUILD SUCCESS, test PASSes.

- [ ] **Step 6: Commit.**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/agent/ForAgentProjection.java wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java wikantik-main/src/test/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionServiceTest.java
git commit -m "for-agent: drop outgoing/incomingRelations fields (relations: deprecated)"
```

---

### Task 6: Trim relation methods from `StructuralIndexService` interface and impl

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/structure/StructuralIndexService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/structure/DefaultStructuralIndexService.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/structure/DefaultStructuralIndexServiceTest.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/structure/DefaultStructuralIndexServiceSavedPageRoundTripTest.java`

- [ ] **Step 1: Remove relation methods from the interface.**

Open `StructuralIndexService.java`. Delete:
- The `outgoingRelations(...)` declaration.
- The `incomingRelations(...)` declaration.
- The `traverse(...)` declaration.
- The `// Relation graph (Phase 2)` comment header.
- Imports: `RelationEdge`, `RelationType`, `TraversalSpec`.

Keep all canonical-id, cluster, tag, sitemap, and lifecycle methods.

- [ ] **Step 2: Remove the implementations from `DefaultStructuralIndexService`.**

Open `DefaultStructuralIndexService.java`. Delete the three method implementations (`outgoingRelations`, `incomingRelations`, `traverse`) plus any private helpers used only by them. Delete the `PageRelationsDao` field and any wiring to it (constructor parameter, factory injection). Remove `RelationEdge`, `RelationType`, `TraversalSpec`, `PageRelationsDao`, `Relation` imports.

- [ ] **Step 3: Update the service tests.**

Open both `DefaultStructuralIndexServiceTest.java` and `DefaultStructuralIndexServiceSavedPageRoundTripTest.java`. Delete every test method whose name or body references `outgoingRelations`, `incomingRelations`, `traverse`, or `RelationEdge`. Remove now-unused imports. Update test setup that constructs `DefaultStructuralIndexService` to drop the `PageRelationsDao` argument.

- [ ] **Step 4: Compile-check and run remaining tests.**

Run: `mvn test -pl wikantik-main -am -Dtest='DefaultStructuralIndexServiceTest,DefaultStructuralIndexServiceSavedPageRoundTripTest' -q`
Expected: BUILD SUCCESS, both tests PASS.

- [ ] **Step 5: Commit.**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/structure/StructuralIndexService.java wikantik-main/src/main/java/com/wikantik/knowledge/structure/DefaultStructuralIndexService.java wikantik-main/src/test/java/com/wikantik/knowledge/structure/DefaultStructuralIndexServiceTest.java wikantik-main/src/test/java/com/wikantik/knowledge/structure/DefaultStructuralIndexServiceSavedPageRoundTripTest.java
git commit -m "spine: drop relation methods from StructuralIndexService"
```

---

### Task 7: Trim `relations` from `StructuralProjection` and `StructuralProjectionBuilder`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/structure/StructuralProjection.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/structure/StructuralProjectionBuilder.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/structure/StructuralProjectionTest.java`

- [ ] **Step 1: Remove `relations` data from `StructuralProjection`.**

Open `StructuralProjection.java`. Delete:
- The `outgoingBySource` / `incomingByTarget` (or equivalently named) maps holding `Relation` objects.
- Public methods `outgoingRelations(canonicalId)` / `incomingRelations(canonicalId)` / `allRelations()` (snapshot accessor at ~line 172).
- The constructor parameters / builder hooks that populate these.
- Imports: `Relation`, `RelationType`, `RelationEdge`.

Keep all cluster, tag, page-descriptor, canonical-id state.

- [ ] **Step 2: Remove `addRelation` from `StructuralProjectionBuilder`.**

Open `StructuralProjectionBuilder.java`. Delete the `addRelation(Relation relation)` method (around line 74) and its supporting fields (`outgoingBySource`, `incomingByTarget`). Remove any constructor params handed to `StructuralProjection` for these. Remove `Relation` import.

- [ ] **Step 3: Update `StructuralProjectionTest`.**

Open `StructuralProjectionTest.java`. Delete every test method that touches `addRelation`, `outgoingRelations`, `incomingRelations`, or relation snapshots. Remove unused imports.

- [ ] **Step 4: Compile-check and run the test.**

Run: `mvn test -pl wikantik-main -am -Dtest=StructuralProjectionTest -q`
Expected: BUILD SUCCESS, test PASSes.

- [ ] **Step 5: Commit.**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/structure/StructuralProjection.java wikantik-main/src/main/java/com/wikantik/knowledge/structure/StructuralProjectionBuilder.java wikantik-main/src/test/java/com/wikantik/knowledge/structure/StructuralProjectionTest.java
git commit -m "spine: drop relation collections from StructuralProjection"
```

---

### Task 8: Delete `PageRelationsDao`

**Files:**
- Delete: `wikantik-main/src/main/java/com/wikantik/knowledge/structure/PageRelationsDao.java`
- Delete: `wikantik-main/src/test/java/com/wikantik/knowledge/structure/PageRelationsDaoTest.java`

- [ ] **Step 1: Confirm zero callers remain.**

Run: `grep -rn "PageRelationsDao" --include="*.java"`
Expected: only the two files about to be deleted.

- [ ] **Step 2: Delete the files.**

```bash
git rm wikantik-main/src/main/java/com/wikantik/knowledge/structure/PageRelationsDao.java
git rm wikantik-main/src/test/java/com/wikantik/knowledge/structure/PageRelationsDaoTest.java
```

- [ ] **Step 3: Compile-check the module.**

Run: `mvn compile -pl wikantik-main -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add -A
git commit -m "spine: delete PageRelationsDao (no remaining callers)"
```

---

### Task 9: Delete `FrontmatterRelationValidator`

**Files:**
- Delete: `wikantik-main/src/main/java/com/wikantik/knowledge/structure/FrontmatterRelationValidator.java`
- Delete: `wikantik-main/src/test/java/com/wikantik/knowledge/structure/FrontmatterRelationValidatorTest.java`

- [ ] **Step 1: Confirm zero callers.**

Run: `grep -rn "FrontmatterRelationValidator" --include="*.java"`
Expected: only the two files about to be deleted.

- [ ] **Step 2: Delete the files.**

```bash
git rm wikantik-main/src/main/java/com/wikantik/knowledge/structure/FrontmatterRelationValidator.java
git rm wikantik-main/src/test/java/com/wikantik/knowledge/structure/FrontmatterRelationValidatorTest.java
```

- [ ] **Step 3: Compile-check the module.**

Run: `mvn compile -pl wikantik-main -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add -A
git commit -m "spine: delete FrontmatterRelationValidator (no remaining callers)"
```

---

### Task 10: Delete relation API types

**Files:**
- Delete: `wikantik-api/src/main/java/com/wikantik/api/structure/Relation.java`
- Delete: `wikantik-api/src/main/java/com/wikantik/api/structure/RelationEdge.java`
- Delete: `wikantik-api/src/main/java/com/wikantik/api/structure/RelationType.java`
- Delete: `wikantik-api/src/main/java/com/wikantik/api/structure/RelationDirection.java`
- Delete: `wikantik-api/src/main/java/com/wikantik/api/structure/TraversalSpec.java`

- [ ] **Step 1: Confirm zero callers across all modules.**

Run: `grep -rn "Relation\b\|RelationEdge\|RelationType\|RelationDirection\|TraversalSpec" --include="*.java"`
Expected: no hits outside the five files about to be deleted. If anything survives, fix it before continuing.

- [ ] **Step 2: Delete the files.**

```bash
git rm wikantik-api/src/main/java/com/wikantik/api/structure/Relation.java
git rm wikantik-api/src/main/java/com/wikantik/api/structure/RelationEdge.java
git rm wikantik-api/src/main/java/com/wikantik/api/structure/RelationType.java
git rm wikantik-api/src/main/java/com/wikantik/api/structure/RelationDirection.java
git rm wikantik-api/src/main/java/com/wikantik/api/structure/TraversalSpec.java
```

- [ ] **Step 3: Full compile.**

Run: `mvn compile -T 1C -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add -A
git commit -m "api: delete relation grammar types (Relation, RelationEdge, RelationType, RelationDirection, TraversalSpec)"
```

---

### Task 11: Collapse the typed-edge palette in the frontend

**Files:**
- Modify: `wikantik-frontend/src/components/graph/graph-data.js`
- Modify: `wikantik-frontend/src/components/graph/graph-data.test.js`
- Modify: `wikantik-frontend/src/components/graph/FilterPanel.jsx`
- Modify: `wikantik-frontend/src/components/graph/GraphLegend.jsx`
- Modify: `wikantik-frontend/src/components/graph/filter-state.js`
- Modify: `wikantik-frontend/src/components/graph/filter-url.js` and `filter-url.test.js` (if they reference typed relation kinds)

- [ ] **Step 1: Find all typed-relation references in the frontend.**

Run: `grep -rn "links_to\|related_to\|part_of\|relationshipType" wikantik-frontend/src/components/graph/`
Expected: a handful of hits across `graph-data.js`, `FilterPanel.jsx`, `GraphLegend.jsx`, and `filter-state.js`.

- [ ] **Step 2: Collapse `KNOWN_PALETTE` in `graph-data.js`.**

Open `wikantik-frontend/src/components/graph/graph-data.js`. Replace the top of the file:

```javascript
const KNOWN_PALETTE = {
  links_to:   '#94a3b8',
  related_to: '#2563eb',
  part_of:    '#7c3aed',
};
```

with:

```javascript
const PAGE_LINK_COLOR = '#94a3b8';
```

Update `colorFor()` to always return `PAGE_LINK_COLOR`:

```javascript
function colorFor() {
  return PAGE_LINK_COLOR;
}
```

Remove `FALLBACK_PALETTE` and `stableHash()` (no longer used). All call sites of `colorFor(...)` already work because they ignore the now-unused argument.

`mergeBidirectionalEdges` and `mergeParallelEdges` keep working: edges still carry a `relationshipType` field on the wire, but it will always be `links_to`.

- [ ] **Step 3: Strip relation-kind UI from `FilterPanel.jsx`.**

Open `FilterPanel.jsx`. Find any UI section that lets users filter by relation kind (typically a checkbox group keyed by `links_to`/`related_to`/`part_of`). Delete that section. If the panel's only purpose is the relation-kind filter, simplify the panel to just whatever else it offers (cluster filter, search) or remove it entirely.

- [ ] **Step 4: Strip relation-kind UI from `GraphLegend.jsx`.**

Open `GraphLegend.jsx`. Delete the legend rows for `links_to`/`related_to`/`part_of` and replace with a single row showing the `PAGE_LINK_COLOR` swatch labelled "Page link".

- [ ] **Step 5: Trim `filter-state.js` and `filter-url.js`.**

Open `filter-state.js` and `filter-url.js`. Delete any state field, default value, or URL-param parsing/serialization for "active relation types" filter. Update the corresponding `filter-url.test.js` and `graph-data.test.js` to drop tests for typed relation behavior.

- [ ] **Step 6: Run frontend tests.**

```bash
cd wikantik-frontend && npm test -- --run && cd ..
```

Expected: all tests pass.

- [ ] **Step 7: Commit.**

```bash
git add wikantik-frontend/src/components/graph/
git commit -m "frontend(graph): collapse typed-edge palette to single page-link color"
```

---

### Task 12: Drop the `page_relations` DB table

**Files:**
- Create: `bin/db/migrations/V023__drop_page_relations.sql` (use the next free number; verify with `ls bin/db/migrations/` first)

- [ ] **Step 1: Confirm V023 is the next free number.**

Run: `ls bin/db/migrations/ | grep -E '^V[0-9]+' | sort -V | tail -3`
Expected: highest current is `V022__kg_node_embeddings_model_code.sql`. If a higher number exists, use the next free instead.

- [ ] **Step 2: Write the migration.**

Create `bin/db/migrations/V023__drop_page_relations.sql`:

```sql
-- Drop the page_relations table.
--
-- The frontmatter `relations:` mechanism is removed in this work
-- (see docs/superpowers/specs/2026-05-02-page-graph-vs-knowledge-graph-design.md).
-- Page Graph edges are now strictly real wikilinks. The page_relations
-- table contained only data derived from frontmatter, with frontmatter
-- as the source of truth — safe to drop, no backup needed.
--
-- Idempotent: re-applying is a no-op once the table is gone.

DROP TABLE IF EXISTS page_relations;
DROP INDEX IF EXISTS ix_page_relations_target;
DROP INDEX IF EXISTS ix_page_relations_source_type;
```

- [ ] **Step 3: Apply the migration locally.**

```bash
DB_NAME=jspwiki DB_APP_USER=jspwiki PGHOST=localhost PGUSER=postgres \
    PGPASSWORD="$(grep '^PGPASSWORD' bin/db/local.env 2>/dev/null | cut -d= -f2)" \
    bin/db/migrate.sh
```

If the local credentials env doesn't exist, run `bin/db/migrate.sh --status` first to confirm the migrate script can reach the DB; ask the operator for credentials if not. Expected: `V023` reported as applied; subsequent `--status` shows it in the applied list.

- [ ] **Step 4: Verify the table is gone.**

Run: `PGPASSWORD=… psql -h localhost -U jspwiki -d jspwiki -c "\dt page_relations"`
Expected: `Did not find any relation named "page_relations".`

- [ ] **Step 5: Re-run migrate to confirm idempotency.**

Run: `bin/db/migrate.sh` (with same env)
Expected: no-op; V023 already applied.

- [ ] **Step 6: Commit.**

```bash
git add bin/db/migrations/V023__drop_page_relations.sql
git commit -m "db: V023 drop page_relations table (relations: mechanism removed)"
```

---

### Task 13: Phase A verification — full unit-test build

- [ ] **Step 1: Run the full unit-test build.**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS, all unit tests pass.

- [ ] **Step 2: Verify acceptance criteria for Phase A.**

```bash
grep -rE "^relations:" docs/wikantik-pages/ ; echo "---"
grep -rE "PageRelations|FrontmatterRelationValidator|TraverseRelationsTool|RelationEdge|RelationType|TraversalSpec|RelationDirection" --include="*.java" . ; echo "---"
PGPASSWORD=… psql -h localhost -U jspwiki -d jspwiki -c "\dt page_relations"
```

Expected:
- First grep: no output.
- Second grep: no output.
- `\dt page_relations`: "Did not find any relation named ...".

- [ ] **Step 3: No commit (verification-only); proceed to Phase B.**

---

## Phase B — Rename Page Graph / Knowledge Graph

### Task 14: Move `com.wikantik.references` → `com.wikantik.pagegraph.references`

**Files:**
- Move: every `.java` file under `wikantik-main/src/main/java/com/wikantik/references/` to `wikantik-main/src/main/java/com/wikantik/pagegraph/references/`
- Move: every `.java` file under `wikantik-main/src/test/java/com/wikantik/references/` to `wikantik-main/src/test/java/com/wikantik/pagegraph/references/`
- Modify: every Java file that imports from `com.wikantik.references`

- [ ] **Step 1: Make the new directory and move the files.**

```bash
mkdir -p wikantik-main/src/main/java/com/wikantik/pagegraph/references
mkdir -p wikantik-main/src/test/java/com/wikantik/pagegraph/references
git mv wikantik-main/src/main/java/com/wikantik/references/*.java wikantik-main/src/main/java/com/wikantik/pagegraph/references/
git mv wikantik-main/src/test/java/com/wikantik/references/*.java wikantik-main/src/test/java/com/wikantik/pagegraph/references/
rmdir wikantik-main/src/main/java/com/wikantik/references wikantik-main/src/test/java/com/wikantik/references
```

- [ ] **Step 2: Rewrite the package declaration in every moved file.**

Run: `find wikantik-main/src/main/java/com/wikantik/pagegraph/references wikantik-main/src/test/java/com/wikantik/pagegraph/references -name "*.java" -exec sed -i 's|^package com\.wikantik\.references;|package com.wikantik.pagegraph.references;|' {} +`

Verify: `grep -rh "^package " wikantik-main/src/main/java/com/wikantik/pagegraph/references/` should print only `package com.wikantik.pagegraph.references;` (and likewise for the test tree).

- [ ] **Step 3: Rewrite imports across the codebase.**

Run: `grep -rl "com\.wikantik\.references" --include="*.java" . | xargs sed -i 's|com\.wikantik\.references|com.wikantik.pagegraph.references|g'`

Verify: `grep -rn "com\.wikantik\.references" --include="*.java" .` returns no hits.

- [ ] **Step 4: Compile-check.**

Run: `mvn compile -T 1C -DskipITs -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run unit tests.**

Run: `mvn test -T 1C -DskipITs -q`
Expected: BUILD SUCCESS, all unit tests pass.

- [ ] **Step 6: Commit.**

```bash
git add -A
git commit -m "refactor: move com.wikantik.references → com.wikantik.pagegraph.references"
```

---

### Task 15: Move surviving `com.wikantik.knowledge.structure` → `com.wikantik.pagegraph.spine`

**Files:**
- Move: every remaining `.java` file under `wikantik-main/src/main/java/com/wikantik/knowledge/structure/` to `wikantik-main/src/main/java/com/wikantik/pagegraph/spine/`
- Same for the test tree
- Modify: every Java file that imports from `com.wikantik.knowledge.structure`

After Phase A, the surviving files are: `ConfidenceComputer`, `DefaultStructuralIndexService`, `PageCanonicalIdsDao`, `PageVerificationDao`, `StructuralIndexEventListener`, `StructuralIndexMetrics`, `StructuralProjectionBuilder`, `StructuralProjection`, `StructuralSpinePageFilter`, `TrustedAuthorsDao`.

- [ ] **Step 1: Make the new directory and move the files.**

```bash
mkdir -p wikantik-main/src/main/java/com/wikantik/pagegraph/spine
mkdir -p wikantik-main/src/test/java/com/wikantik/pagegraph/spine
git mv wikantik-main/src/main/java/com/wikantik/knowledge/structure/*.java wikantik-main/src/main/java/com/wikantik/pagegraph/spine/
git mv wikantik-main/src/test/java/com/wikantik/knowledge/structure/*.java wikantik-main/src/test/java/com/wikantik/pagegraph/spine/
rmdir wikantik-main/src/main/java/com/wikantik/knowledge/structure wikantik-main/src/test/java/com/wikantik/knowledge/structure
```

- [ ] **Step 2: Rewrite package declarations in every moved file.**

Run: `find wikantik-main/src/main/java/com/wikantik/pagegraph/spine wikantik-main/src/test/java/com/wikantik/pagegraph/spine -name "*.java" -exec sed -i 's|^package com\.wikantik\.knowledge\.structure;|package com.wikantik.pagegraph.spine;|' {} +`

Verify: `grep -rh "^package " wikantik-main/src/main/java/com/wikantik/pagegraph/spine/` prints only `package com.wikantik.pagegraph.spine;`.

- [ ] **Step 3: Rewrite imports across the codebase.**

Run: `grep -rl "com\.wikantik\.knowledge\.structure" --include="*.java" . | xargs sed -i 's|com\.wikantik\.knowledge\.structure|com.wikantik.pagegraph.spine|g'`

Verify: `grep -rn "com\.wikantik\.knowledge\.structure" --include="*.java" .` returns no hits.

- [ ] **Step 4: Compile-check.**

Run: `mvn compile -T 1C -DskipITs -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run unit tests.**

Run: `mvn test -T 1C -DskipITs -q`
Expected: BUILD SUCCESS, all unit tests pass.

- [ ] **Step 6: Commit.**

```bash
git add -A
git commit -m "refactor: move com.wikantik.knowledge.structure → com.wikantik.pagegraph.spine"
```

---

### Task 16: Rename `com.wikantik.api.structure` → `com.wikantik.api.pagegraph`

**Files:**
- Move: every remaining `.java` file under `wikantik-api/src/main/java/com/wikantik/api/structure/` to `wikantik-api/src/main/java/com/wikantik/api/pagegraph/`
- Same for the test tree
- Modify: every Java file that imports from `com.wikantik.api.structure`

After Phase A, the surviving files in this directory are: `Audience`, `ClusterDetails`, `ClusterSummary`, `Confidence`, `IndexHealth`, `PageDescriptor`, `PageType`, `Sitemap`, `StructuralConflict`, `StructuralFilter`, `StructuralIndexService`, `TagSummary`, `Verification`.

- [ ] **Step 1: Make the new directory and move the files.**

```bash
mkdir -p wikantik-api/src/main/java/com/wikantik/api/pagegraph
mkdir -p wikantik-api/src/test/java/com/wikantik/api/pagegraph
git mv wikantik-api/src/main/java/com/wikantik/api/structure/*.java wikantik-api/src/main/java/com/wikantik/api/pagegraph/
[ -d wikantik-api/src/test/java/com/wikantik/api/structure ] && git mv wikantik-api/src/test/java/com/wikantik/api/structure/*.java wikantik-api/src/test/java/com/wikantik/api/pagegraph/
rmdir wikantik-api/src/main/java/com/wikantik/api/structure 2>/dev/null
[ -d wikantik-api/src/test/java/com/wikantik/api/structure ] && rmdir wikantik-api/src/test/java/com/wikantik/api/structure
```

- [ ] **Step 2: Rewrite package declarations.**

Run: `find wikantik-api/src/main/java/com/wikantik/api/pagegraph wikantik-api/src/test/java/com/wikantik/api/pagegraph -name "*.java" 2>/dev/null -exec sed -i 's|^package com\.wikantik\.api\.structure;|package com.wikantik.api.pagegraph;|' {} +`

Verify: `grep -rh "^package " wikantik-api/src/main/java/com/wikantik/api/pagegraph/` prints only `package com.wikantik.api.pagegraph;`.

- [ ] **Step 3: Rewrite imports across the codebase.**

Run: `grep -rl "com\.wikantik\.api\.structure" --include="*.java" . | xargs sed -i 's|com\.wikantik\.api\.structure|com.wikantik.api.pagegraph|g'`

Verify: `grep -rn "com\.wikantik\.api\.structure" --include="*.java" .` returns no hits.

- [ ] **Step 4: Full unit-test build.**

Run: `mvn clean install -T 1C -DskipITs -q`
Expected: BUILD SUCCESS, all unit tests pass.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "refactor: rename com.wikantik.api.structure → com.wikantik.api.pagegraph"
```

---

### Task 17: Add `/page-graph` SPA route + `/graph` → `/page-graph` 301 redirect

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java` (if exists)

- [ ] **Step 1: Locate the SPA route registration.**

Run: `grep -n 'SPA_EXACT' wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java`
Expected: a line like `private static final String[] SPA_EXACT = { "/search", "/graph", "/preferences", "/reset-password", "/blog" };` (around line 87).

- [ ] **Step 2: Replace `/graph` with `/page-graph` and add a redirect rule.**

Edit `SpaRoutingFilter.java`:

1. In `SPA_EXACT`, replace `"/graph"` with `"/page-graph"`.
2. Add a redirect map (or extend the existing one) so that exact path `/graph` returns `301 Moved Permanently` with `Location: /page-graph` (preserving any query string, e.g. `?focus=…`). The existing class doc (line 49) says it already handles `/`, `/wiki`, `/wiki/` redirects — follow the same code path. If there's no general redirect mechanism, add a small block at the top of the filter's `doFilter`:

   ```java
   final String requestUri = request.getRequestURI();
   if ( "/graph".equals( requestUri ) ) {
       final String qs = request.getQueryString();
       final String target = "/page-graph" + ( qs != null ? "?" + qs : "" );
       response.setStatus( HttpServletResponse.SC_MOVED_PERMANENTLY );
       response.setHeader( "Location", target );
       return;
   }
   ```

- [ ] **Step 3: Add a unit test for the redirect.**

If `SpaRoutingFilterTest.java` exists, add (or modify) a test:

```java
@Test
void redirectsLegacyGraphPathToPageGraph() throws Exception {
    final HttpServletRequest req = mock( HttpServletRequest.class );
    final HttpServletResponse resp = mock( HttpServletResponse.class );
    when( req.getRequestURI() ).thenReturn( "/graph" );
    when( req.getQueryString() ).thenReturn( "focus=Foo" );

    new SpaRoutingFilter().doFilter( req, resp, mock( FilterChain.class ) );

    verify( resp ).setStatus( HttpServletResponse.SC_MOVED_PERMANENTLY );
    verify( resp ).setHeader( "Location", "/page-graph?focus=Foo" );
}
```

If no such test class exists, create `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java` with this test plus any necessary scaffolding (Mockito setup), modeled on existing filter tests in the same module.

- [ ] **Step 4: Run the test.**

Run: `mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterTest -q`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java
git commit -m "rest: rename /graph → /page-graph SPA route + 301 redirect"
```

---

### Task 18: Add `/admin/knowledge-graph` route + `/admin/knowledge` → `/admin/knowledge-graph` 301 redirect

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java` (or wherever admin routes are mounted)
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java`

- [ ] **Step 1: Find how admin SPA routes are registered.**

Run: `grep -n 'admin\|knowledge' wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java`
Expected: a separate prefix list for `/admin/...` SPA routes, or none (in which case admin routes are caught by a generic SPA fallback).

- [ ] **Step 2: Add the redirect.**

Extend the redirect block from Task 17 (or add a new if-branch) so that any request whose path equals `/admin/knowledge` (or starts with `/admin/knowledge/` and is **not** `/admin/knowledge-graph...`) returns a `301` to the same path with `knowledge` replaced by `knowledge-graph`. Preserve query strings.

If admin SPA routes are explicitly listed (e.g. `ADMIN_SPA_PREFIXES`), add `/admin/knowledge-graph` and remove `/admin/knowledge`.

```java
if ( "/admin/knowledge".equals( requestUri ) || requestUri.startsWith( "/admin/knowledge/" ) ) {
    final String suffix = requestUri.substring( "/admin/knowledge".length() );
    final String qs = request.getQueryString();
    final String target = "/admin/knowledge-graph" + suffix + ( qs != null ? "?" + qs : "" );
    response.setStatus( HttpServletResponse.SC_MOVED_PERMANENTLY );
    response.setHeader( "Location", target );
    return;
}
```

- [ ] **Step 3: Add a unit test.**

In `SpaRoutingFilterTest.java`, add:

```java
@Test
void redirectsLegacyAdminKnowledgePathToKnowledgeGraph() throws Exception {
    final HttpServletRequest req = mock( HttpServletRequest.class );
    final HttpServletResponse resp = mock( HttpServletResponse.class );
    when( req.getRequestURI() ).thenReturn( "/admin/knowledge/extraction" );
    when( req.getQueryString() ).thenReturn( null );

    new SpaRoutingFilter().doFilter( req, resp, mock( FilterChain.class ) );

    verify( resp ).setStatus( HttpServletResponse.SC_MOVED_PERMANENTLY );
    verify( resp ).setHeader( "Location", "/admin/knowledge-graph/extraction" );
}
```

- [ ] **Step 4: Run.**

Run: `mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterTest -q`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java
git commit -m "rest: rename /admin/knowledge → /admin/knowledge-graph + 301 redirect"
```

---

### Task 19: Move `AdminStructuralConflictsResource` mount under `/admin/page-graph/conflicts`

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminStructuralConflictsResource.java`
- Modify: any wiring file that mounts admin REST resources (locate by grep)

- [ ] **Step 1: Find the current mount path.**

Run: `grep -rn 'AdminStructuralConflictsResource\|/admin/structural-conflicts\|structural-conflicts' --include="*.java" wikantik-rest/`
Expected: the resource class plus its `@Path` or wiring location.

- [ ] **Step 2: Update the path.**

If the resource uses an annotation like `@Path("/admin/structural-conflicts")`, change to `@Path("/admin/page-graph/conflicts")`. If the path is set in a wiring class, update it there. The class name does not change.

- [ ] **Step 3: Update any tests, integration tests, and admin frontend pages that hit the old URL.**

Run: `grep -rn "/admin/structural-conflicts" --include="*.java" --include="*.jsx" --include="*.tsx" --include="*.ts" --include="*.md"`
For every hit, update to `/admin/page-graph/conflicts`.

- [ ] **Step 4: Compile and run affected tests.**

Run: `mvn test -pl wikantik-rest -Dtest=*AdminStructuralConflicts* -q`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "rest: move /admin/structural-conflicts → /admin/page-graph/conflicts"
```

---

### Task 20: Rename frontend folder `components/graph/` → `components/pagegraph/`

**Files:**
- Move: every file under `wikantik-frontend/src/components/graph/` to `wikantik-frontend/src/components/pagegraph/`
- Modify: every frontend file that imports from `../graph/...` or `./graph/...`
- Rename: `GraphView.jsx` → `PageGraphView.jsx`

- [ ] **Step 1: Move the folder.**

```bash
git mv wikantik-frontend/src/components/graph wikantik-frontend/src/components/pagegraph
```

- [ ] **Step 2: Rename the route component.**

```bash
git mv wikantik-frontend/src/components/pagegraph/GraphView.jsx wikantik-frontend/src/components/pagegraph/PageGraphView.jsx
[ -f wikantik-frontend/src/components/pagegraph/GraphView.test.jsx ] && git mv wikantik-frontend/src/components/pagegraph/GraphView.test.jsx wikantik-frontend/src/components/pagegraph/PageGraphView.test.jsx
```

Inside each renamed file, replace any internal `function GraphView`, `default export GraphView`, or `GraphView` symbol with `PageGraphView`. Run:

```bash
sed -i 's|GraphView|PageGraphView|g' wikantik-frontend/src/components/pagegraph/PageGraphView.jsx
[ -f wikantik-frontend/src/components/pagegraph/PageGraphView.test.jsx ] && sed -i 's|GraphView|PageGraphView|g' wikantik-frontend/src/components/pagegraph/PageGraphView.test.jsx
```

- [ ] **Step 3: Rewrite import paths across the frontend.**

Run:

```bash
grep -rl "components/graph" wikantik-frontend/src/ | xargs sed -i 's|components/graph|components/pagegraph|g'
grep -rl "/GraphView" wikantik-frontend/src/ | xargs sed -i 's|/GraphView|/PageGraphView|g'
grep -rl 'import GraphView\|GraphView from' wikantik-frontend/src/ | xargs sed -i 's|GraphView|PageGraphView|g'
```

Verify:
- `grep -rn "components/graph" wikantik-frontend/src/` returns no hits.
- `grep -rn "GraphView" wikantik-frontend/src/` returns only `PageGraphView` matches.

- [ ] **Step 4: Run frontend tests.**

```bash
cd wikantik-frontend && npm test -- --run && cd ..
```

Expected: all tests pass.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "frontend: rename components/graph → components/pagegraph; GraphView → PageGraphView"
```

---

### Task 21: Update Sidebar labels and routes

**Files:**
- Modify: `wikantik-frontend/src/components/Sidebar.jsx`
- Modify: `wikantik-frontend/src/main.jsx` (the `/graph` route registration)

- [ ] **Step 1: Update `Sidebar.jsx`.**

Replace every occurrence of the user-visible label "Knowledge Graph" that links to `/graph` with "Page Graph" linking to `/page-graph`. Update the surrounding `to=...` attribute. From the prior grep we know the relevant lines are around 112, 116, and 167 — adjust as needed for the current shape of the file.

```bash
sed -i 's|/graph?focus=|/page-graph?focus=|g' wikantik-frontend/src/components/Sidebar.jsx
sed -i "s|to='/graph'|to='/page-graph'|g; s|to=\"/graph\"|to=\"/page-graph\"|g" wikantik-frontend/src/components/Sidebar.jsx
sed -i 's|Knowledge Graph|Page Graph|g' wikantik-frontend/src/components/Sidebar.jsx
```

(If the Sidebar contains an entry for the *admin* "Knowledge" nav, that one becomes "Knowledge Graph" — see Task 22.)

- [ ] **Step 2: Update the route registration in `main.jsx`.**

Replace:

```jsx
<Route path="/graph" element={
  <Suspense fallback={<div className="graph-loading"><p>Loading knowledge graph...</p></div>}>
```

with:

```jsx
<Route path="/page-graph" element={
  <Suspense fallback={<div className="graph-loading"><p>Loading page graph...</p></div>}>
```

And update the import line at the top:

```jsx
const PageGraphView = React.lazy(() => import('./components/pagegraph/PageGraphView.jsx'));
```

(was: `const GraphView = React.lazy(() => import('./components/graph/GraphView.jsx'));`)

Update the element reference inside the route to `<PageGraphView />`.

- [ ] **Step 3: Run frontend tests.**

```bash
cd wikantik-frontend && npm test -- --run && cd ..
```

Expected: all tests pass.

- [ ] **Step 4: Commit.**

```bash
git add wikantik-frontend/src/components/Sidebar.jsx wikantik-frontend/src/main.jsx
git commit -m "frontend: sidebar 'Knowledge Graph' (page-link view) → 'Page Graph' at /page-graph"
```

---

### Task 22: Update admin nav label "Knowledge" → "Knowledge Graph"

**Files:**
- Modify: `wikantik-frontend/src/components/admin/AdminLayout.jsx` (or wherever admin nav lives)
- Modify: `wikantik-frontend/src/components/admin/AdminPage.jsx` if it carries the nav
- Modify: any other file that references the admin "Knowledge" link

- [ ] **Step 1: Find the admin nav source.**

Run: `grep -rln '"Knowledge"\|>Knowledge<\|to=.*admin/knowledge' wikantik-frontend/src/components/admin/`
Expected: a tab/link entry in the admin layout component.

- [ ] **Step 2: Update labels and routes.**

In every match: change the visible string `"Knowledge"` → `"Knowledge Graph"` and the route `/admin/knowledge` → `/admin/knowledge-graph`. Be careful not to touch unrelated symbols (the `AdminKnowledgePage` component name stays — only labels and route paths change).

```bash
grep -rl 'admin/knowledge[^-]' wikantik-frontend/src/components/admin/ | xargs sed -i 's|/admin/knowledge\([^-]\)|/admin/knowledge-graph\1|g'
# Hand-edit visible labels — sed risks over-matching the substring "Knowledge"
```

For visible labels, hand-edit each match found in step 1 to change the rendered text from "Knowledge" to "Knowledge Graph" (avoid touching component class names like `AdminKnowledgePage` or service names like `KnowledgeGraphService`).

- [ ] **Step 3: Update route registrations in `main.jsx` (or admin route file).**

Find the route `<Route path="knowledge" element={<AdminKnowledgePage />} />` and change to `<Route path="knowledge-graph" element={<AdminKnowledgePage />} />`. The component class name does not change.

- [ ] **Step 4: Run frontend tests.**

```bash
cd wikantik-frontend && npm test -- --run && cd ..
```

Expected: all tests pass.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "frontend(admin): nav 'Knowledge' → 'Knowledge Graph'; route /admin/knowledge → /admin/knowledge-graph"
```

---

### Task 23: Audit MCP tool descriptions on `/wikantik-admin-mcp`

**Files:**
- Modify: each tool source under `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/*.java` whose description text references "graph" without qualification

- [ ] **Step 1: Find ambiguous "graph" references in admin MCP tool descriptions.**

Run: `grep -rln 'description = "[^"]*\bgraph\b\|description("[^"]*\bgraph\b' wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/`
Expected: a small set of files (link/backlink/orphan tools).

- [ ] **Step 2: Edit each match.**

For every tool in the list, open the file and update the description string so it qualifies "graph" as "Page Graph" wherever it refers to page-link traversal. Examples:

- `"Get pages that link to this page (the graph's incoming edges)."` → `"Get pages that link to this page (Page Graph incoming edges)."`
- `"Find orphan pages (no incoming edges in the graph)."` → `"Find orphan pages (no incoming edges in the Page Graph)."`

Keep tool *names* unchanged. Only descriptions move.

- [ ] **Step 3: Run module tests.**

Run: `mvn test -pl wikantik-admin-mcp -q`
Expected: PASS. (Tests don't typically assert on description text, but compile + sanity check is required.)

- [ ] **Step 4: Commit.**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/
git commit -m "admin-mcp: qualify tool descriptions to say 'Page Graph'"
```

---

### Task 24: Audit MCP tool descriptions on `/knowledge-mcp`

**Files:**
- Modify: each tool source under `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/*.java` whose description references "graph" without qualification

- [ ] **Step 1: Find ambiguous "graph" references.**

Run: `grep -rln 'description = "[^"]*\bgraph\b\|description("[^"]*\bgraph\b' wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/`
Expected: a handful of files.

- [ ] **Step 2: Edit each match.**

Update descriptions so every "graph" is qualified as "Knowledge Graph" (or, where it specifically refers to page links inside a KG-side tool — unlikely but possible — "Page Graph"). Keep tool names unchanged.

- [ ] **Step 3: Run module tests.**

Run: `mvn test -pl wikantik-knowledge -q`
Expected: PASS.

- [ ] **Step 4: Commit.**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/
git commit -m "knowledge-mcp: qualify tool descriptions to say 'Knowledge Graph'"
```

---

### Task 25: Update `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Insert the canonical-definitions section.**

Open `CLAUDE.md`. After the "Architecture Overview" header (around the section that lists module structure), insert a new subsection `### Page Graph vs Knowledge Graph` with this exact body:

```markdown
### Page Graph vs Knowledge Graph

Two distinct subsystems. Do not conflate them.

- **Page Graph.** A graph whose edges are real page-to-page wikilinks.
  Sources: wikilinks parsed from page bodies, period. Companion structure
  (not edges of the Page Graph itself, but co-resident in the same
  subsystem): `canonical_id` (rename-stable identifier in frontmatter)
  and `cluster:` (hub membership). Code: `com.wikantik.pagegraph.*`,
  `com.wikantik.api.pagegraph`. UI: `/page-graph` reader route,
  `/admin/page-graph/*` operator surfaces.
- **Knowledge Graph.** Nodes are LLM-extracted entities; edges are
  co-mention or typed-relation predicates between them. Code:
  `com.wikantik.knowledge.*`, `wikantik-knowledge` module, `kg_*`
  tables. UI: `/admin/knowledge-graph/*`, `/knowledge-mcp` tool surface.

Naming convention: the bare word "graph" is a code smell. Always say
"Page Graph", "Knowledge Graph", or `kg_*`/`pagegraph` in identifiers.
```

- [ ] **Step 2: Sweep the rest of `CLAUDE.md` for ambiguous references.**

Run: `grep -n -i "knowledge graph\|page graph\|\bgraph\b" CLAUDE.md`
For each match: confirm the meaning is unambiguous in context. Replace bare "graph" with "Page Graph" or "Knowledge Graph" as appropriate. Update the Structural Spine summary (search for "structural_spine.enforcement.enabled") to clarify that the spine validates `canonical_id` and clusters; mention that the typed `relations:` mechanism was removed 2026-05-02.

- [ ] **Step 3: Update the `wikantik-knowledge` module description in the module table.**

Find the row in the modules table for `wikantik-knowledge`. Confirm it reads as a Knowledge-Graph-only description (no leftover "structural" language).

- [ ] **Step 4: Update the active design documents list.**

Find the line referencing `StructuralSpineDesign.md`. Add a short note that the typed `relations:` grammar was removed 2026-05-02. Add a new bullet linking to `docs/wikantik-pages/PageGraphVsKnowledgeGraph.md` (the explainer page created in Task 28).

- [ ] **Step 5: Commit.**

```bash
git add CLAUDE.md
git commit -m "docs(CLAUDE.md): add Page Graph vs Knowledge Graph section; sweep ambiguous 'graph' refs"
```

---

### Task 26: Update `README.md`

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Insert the canonical-definitions paragraph.**

Open `README.md`. Find an early section that introduces the project (likely under "Overview" or "Features"). Insert a one-paragraph note:

```markdown
**Page Graph vs Knowledge Graph.** Wikantik distinguishes two graph
subsystems: the *Page Graph* (edges are real wikilinks; reader-facing
at `/page-graph`) and the *Knowledge Graph* (LLM-extracted entities and
relations; admin and agent surfaces). See [PageGraphVsKnowledgeGraph](docs/wikantik-pages/PageGraphVsKnowledgeGraph.md)
for the long form.
```

- [ ] **Step 2: Sweep for ambiguous references.**

Run: `grep -n -i "knowledge graph\|page graph\|\bgraph\b" README.md`
For each match, qualify or fix. Pay special attention to text describing the visualization: it now says "Page Graph", not "Knowledge Graph".

- [ ] **Step 3: Commit.**

```bash
git add README.md
git commit -m "docs(README): add Page Graph vs Knowledge Graph note; sweep ambiguous refs"
```

---

### Task 27: Rewrite `docs/wikantik-pages/StructuralSpineDesign.md`

**Files:**
- Modify: `docs/wikantik-pages/StructuralSpineDesign.md`

- [ ] **Step 1: Add a "Changes 2026-05-02" header note at the top.**

Just below the page's title/frontmatter (after the `---` block), insert:

```markdown
> **Note (2026-05-02).** The Structural Spine is now a sub-area of the
> **Page Graph** subsystem. The typed-relation grammar (`relations:`
> frontmatter, `related_to`, `part_of`, etc.) was removed in this
> update — see `docs/superpowers/specs/2026-05-02-page-graph-vs-knowledge-graph-design.md`.
> The spine retains its `canonical_id` assignment and validation, the
> `cluster:` hub-membership mechanism, save-time enforcement, and the
> `Main.md` projection. Page Graph edges are now strictly real wiki
> links.
```

- [ ] **Step 2: Strike the `relations:` grammar sections.**

Sweep the doc for sections describing typed relations, the `relations:` frontmatter grammar, the relation graph traversal, and `outgoingRelations`/`incomingRelations` API surface. Either delete those sections outright or wrap each in a struck-through note pointing to the 2026-05-02 spec. Prefer deletion for clarity.

- [ ] **Step 3: Update remaining references to the package name.**

Anywhere the doc mentions `com.wikantik.knowledge.structure`, change to `com.wikantik.pagegraph.spine`. Anywhere it mentions `com.wikantik.api.structure`, change to `com.wikantik.api.pagegraph`.

- [ ] **Step 4: Verify no lingering relations references.**

Run: `grep -nE 'relations:|outgoingRelations|incomingRelations|TraversalSpec|RelationType' docs/wikantik-pages/StructuralSpineDesign.md`
Expected: only references in the "Changes 2026-05-02" note (which describes their removal).

- [ ] **Step 5: Commit.**

```bash
git add docs/wikantik-pages/StructuralSpineDesign.md
git commit -m "docs(StructuralSpineDesign): rewrite for narrower spine; relations: grammar removed"
```

---

### Task 28: Audit `HybridRetrieval.md` and `AgentGradeContentDesign.md`

**Files:**
- Modify: `docs/wikantik-pages/HybridRetrieval.md`
- Modify: `docs/wikantik-pages/AgentGradeContentDesign.md`

- [ ] **Step 1: Find ambiguous "graph" references in both docs.**

Run: `grep -nE '\bgraph\b' docs/wikantik-pages/HybridRetrieval.md docs/wikantik-pages/AgentGradeContentDesign.md`

- [ ] **Step 2: For each match, qualify.**

Replace bare "graph" with "Page Graph", "Knowledge Graph", or `kg_*` as appropriate to context. In `HybridRetrieval.md`, references to the graph-aware reranker should explicitly say "Knowledge Graph". In `AgentGradeContentDesign.md`:

1. Find the `/for-agent` projection section. Delete every reference to `outgoingRelations`, `incomingRelations`, and `typed_relations` (these fields no longer exist on `ForAgentProjection`).
2. Add a single dated note immediately under the section header:

   ```markdown
   > **Update 2026-05-02.** The `outgoingRelations` and
   > `incomingRelations` fields were removed from `ForAgentProjection`
   > when the frontmatter `relations:` mechanism was deleted. Agents
   > needing page-link traversal should call the `get_outbound_links`
   > or `get_backlinks` MCP tools on `/wikantik-admin-mcp` instead.
   > See `docs/superpowers/specs/2026-05-02-page-graph-vs-knowledge-graph-design.md`.
   ```

- [ ] **Step 3: Verify.**

Run: `grep -nE '\bgraph\b' docs/wikantik-pages/HybridRetrieval.md docs/wikantik-pages/AgentGradeContentDesign.md`
Expected: every remaining match is preceded by "Page" or "Knowledge".

- [ ] **Step 4: Commit.**

```bash
git add docs/wikantik-pages/HybridRetrieval.md docs/wikantik-pages/AgentGradeContentDesign.md
git commit -m "docs: qualify 'graph' refs in HybridRetrieval and AgentGradeContentDesign"
```

---

### Task 29: Create the `PageGraphVsKnowledgeGraph` explainer page

**Files:**
- Create: `docs/wikantik-pages/PageGraphVsKnowledgeGraph.md`

- [ ] **Step 1: Write the page.**

Create the file with this content:

```markdown
---
title: Page Graph vs Knowledge Graph
cluster: wikantik-development
audience: [humans, agents]
type: explainer
---

<!-- canonical_id is omitted intentionally — StructuralSpinePageFilter
     auto-assigns a ULID on first save and writes it back into the
     frontmatter. -->


# Page Graph vs Knowledge Graph

Wikantik distinguishes two graph subsystems. Confusing them is the
single most common source of bugs and miscommunication when working
on retrieval, navigation, or admin tooling. Use the right name.

## Page Graph

**A graph whose edges are real page-to-page wikilinks.**

- **Sources.** Wikilinks parsed from page bodies (`[[OtherPage]]`,
  `[OtherPage]`, etc.). Period.
- **Companion structure** (not edges of the Page Graph itself, but
  co-resident in the same subsystem): the `canonical_id` field in
  frontmatter (the rename-stable identifier) and the `cluster:` field
  (hub membership).
- **Purpose.** Navigation, authoring aids (broken-link triage, orphan
  pages), the visual `/page-graph` view.
- **Audience.** Human readers and authors.
- **Code.** `com.wikantik.pagegraph.*` (in `wikantik-main`),
  `com.wikantik.api.pagegraph` (in `wikantik-api`).
- **UI.** `/page-graph` (reader); `/admin/page-graph/*` (operator).

## Knowledge Graph

**A graph whose nodes are LLM-extracted entities and whose edges are
co-mention or typed-relation predicates between them.**

- **Sources.** The entity-extraction pipeline (`bin/kg-extract.sh`)
  reads page text and proposes nodes and edges; admins approve them.
- **Purpose.** Semantic retrieval, hub discovery, agent-facing
  question answering.
- **Audience.** Agents and admins.
- **Code.** `com.wikantik.knowledge.*` (in `wikantik-main`),
  `wikantik-knowledge` module, `kg_*` tables.
- **UI.** `/admin/knowledge-graph/*` (operator); `/knowledge-mcp` MCP
  tool surface (agents).

## How to tell them apart

| Question | Page Graph | Knowledge Graph |
|---|---|---|
| What is an edge? | A wikilink one author wrote in one page | An LLM-extracted predicate between two entities |
| What is a node? | A page | An entity (concept, person, organisation, etc.) |
| Who curates it? | Authors (by writing links) | Extraction pipeline (with admin review) |
| Where does it live in code? | `pagegraph.*` | `knowledge.*`, `kg_*` |

## What was removed (2026-05-02)

The frontmatter `relations:` mechanism — a third concept that let
authors hand-curate typed edges between pages without writing real
wikilinks — was removed. Three of 951 pages used it; nothing
load-bearing depended on it. After removal, the Page Graph is
strictly the wikilinks graph. If curated typed edges between concepts
need to come back later, they belong on the Knowledge Graph as
admin-approved edges, not in page frontmatter.

## See also

- [StructuralSpineDesign](StructuralSpineDesign) — the canonical-id
  and cluster machinery that lives inside the Page Graph subsystem.
- [HybridRetrieval](HybridRetrieval) — the graph-aware reranker uses
  the Knowledge Graph.
- `docs/superpowers/specs/2026-05-02-page-graph-vs-knowledge-graph-design.md`
  — the spec that drove this separation.
```

- [ ] **Step 2: Wire the page into the sidebar help and the new admin/reader views.**

Add a link to `PageGraphVsKnowledgeGraph` from:
- The reader help/about menu (find the file that renders sidebar help — likely `Sidebar.jsx`).
- The `/page-graph` view (a small "What is the Page Graph?" link in `PageGraphView.jsx`).
- The `/admin/knowledge-graph` admin page (a small "Page Graph vs Knowledge Graph" link in `AdminKnowledgePage.jsx`).

For each, add a single anchor element:

```jsx
<a href="/wiki/PageGraphVsKnowledgeGraph">Page Graph vs Knowledge Graph</a>
```

- [ ] **Step 3: Verify the page renders.**

Build the WAR (`mvn install -pl wikantik-war -am -DskipTests -q`) and start Tomcat, then `curl -s http://localhost:8080/wiki/PageGraphVsKnowledgeGraph?format=md` should return the markdown body. (Skip the curl if local Tomcat isn't already running; the verification will be re-run as part of the final acceptance task.)

- [ ] **Step 4: Commit.**

```bash
git add docs/wikantik-pages/PageGraphVsKnowledgeGraph.md wikantik-frontend/src/components/Sidebar.jsx wikantik-frontend/src/components/pagegraph/PageGraphView.jsx wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx
git commit -m "docs: add PageGraphVsKnowledgeGraph explainer; link from sidebar and views"
```

---

### Task 30: Final acceptance — full unit + integration build

**Files:** none (verification only).

- [ ] **Step 1: Full unit-test build.**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS, all unit tests pass.

- [ ] **Step 2: Full integration-test build (sequential — no `-T` flag).**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS. If any IT module fails, inspect the report; integration tests covering the reader graph view, admin KG pages, and the MCP surface need their URLs updated to match the new routes (do this in the same task and re-run).

- [ ] **Step 3: Spec acceptance check — grep + curl.**

Start the local Tomcat (per `CLAUDE.md`):

```bash
bin/deploy-local.sh
tomcat/tomcat-11/bin/startup.sh
sleep 5  # allow startup
```

Then run:

```bash
echo "--- relations: in pages (expect empty) ---"
grep -rE "^relations:" docs/wikantik-pages/

echo "--- deleted Java types (expect empty) ---"
grep -rE "PageRelations|FrontmatterRelationValidator|TraverseRelationsTool|RelationEdge|RelationType|TraversalSpec|RelationDirection" --include="*.java" .

echo "--- old Java packages (expect empty) ---"
grep -rE "knowledge\.structure|knowledge\.references" --include="*.java" .

echo "--- /graph redirect (expect 301 → /page-graph) ---"
curl -sI http://localhost:8080/graph

echo "--- /admin/knowledge redirect (expect 301 → /admin/knowledge-graph) ---"
curl -sI http://localhost:8080/admin/knowledge

echo "--- /api/pages/Foo/relations (expect 404) ---"
curl -sI http://localhost:8080/api/pages/Foo/relations

echo "--- explainer page renders ---"
curl -s 'http://localhost:8080/wiki/PageGraphVsKnowledgeGraph?format=md' | head -5
```

Expected output:
- First three greps: empty.
- `/graph`: HTTP 301, Location: /page-graph.
- `/admin/knowledge`: HTTP 301, Location: /admin/knowledge-graph.
- `/api/pages/Foo/relations`: HTTP 404.
- Explainer page: returns its markdown body.

- [ ] **Step 4: Sidebar / nav manual check.**

Open `http://localhost:8080/` in a browser, log in as `testbot` (credentials in `test.properties`), and confirm:
- Sidebar shows "Page Graph" linking to `/page-graph` (no longer "Knowledge Graph" linking to `/graph`).
- Admin nav shows "Knowledge Graph" linking to `/admin/knowledge-graph`.
- Visiting `/page-graph` renders the page-link visualization with single edge color.
- Visiting `/admin/knowledge-graph` shows the existing extraction / hub / KG-policy tabs.

- [ ] **Step 5: Final commit (if any small fixes were made during acceptance).**

```bash
git add -A
git diff --cached --stat
git commit -m "chore: final cleanups from acceptance run"
```

(If nothing to commit, skip this step.)

---

## Summary

After all tasks, the codebase has:
- One canonical name per concept (`Page Graph`, `Knowledge Graph`).
- No frontmatter `relations:` mechanism, no `page_relations` table, no relation grammar API types.
- Java packages cleanly partitioned: `com.wikantik.pagegraph.*` vs `com.wikantik.knowledge.*` (and `com.wikantik.api.pagegraph` vs `com.wikantik.api.knowledge`).
- Reader and admin routes named symmetrically (`/page-graph`, `/admin/knowledge-graph`), with 301 redirects from the old paths.
- Frontend folder, route component, sidebar, and admin nav all aligned.
- MCP tool descriptions disambiguated.
- Documentation (CLAUDE.md, README, design docs, new explainer) reflects the new vocabulary.
- One new database migration (`V023__drop_page_relations.sql`).
