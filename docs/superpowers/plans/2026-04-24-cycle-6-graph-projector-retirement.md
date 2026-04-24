# Cycle 6: GraphProjector Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retire `GraphProjector` — the legacy PageFilter that derived KG nodes/edges from page frontmatter + body links. Cycle 3 added a read-filter that hides GraphProjector's output from MCP; cycle 6 completes Option B: stop writing, delete the class, delete its helper (`FrontmatterRelationshipDetector`), delete the `/admin/knowledge/project-all` endpoint, and purge the unambiguous `links_to` edges.

**Architecture:** Five-step retirement: (1) unregister from the save-time PageFilter chain; (2) remove the admin-UI full-reprojection endpoint; (3) delete the class + its sole helper; (4) update stale doc comments; (5) SQL migration V012 purges `links_to` edges (the only unambiguously GraphProjector-written type — frontmatter-derived edges use arbitrary type names that may overlap with manually-authored ones, so those are left alone).

**Tech Stack:** Java 21, JUnit 5, existing migration framework (`bin/db/migrate.sh`), SQL.

**Reference spec:** `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`
**Reference cycle 3 plan:** `docs/superpowers/plans/2026-04-24-cycle-3-kg-tools-mention-rebacking.md` (cycle 3 is the "read-filter" complement — cycle 6 is the "data purge" complement)

---

## What keeps running after retirement

- **`ChunkProjector`** — DIFFERENT class from GraphProjector, at `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ChunkProjector.java`. This is the active save-time chunker that populates `kg_content_chunks`. Keep it. The spec's phrase "ChunkProjector path [GraphProjector] fed" was imprecise — the two filters are independent, both registered in `WikiEngine.initKnowledgeGraph` with different priorities.
- **Manual KG authoring** via admin UI continues to work — users can still create HUMAN_AUTHORED nodes/edges. Those rows are not purged.
- **Frontmatter-derived edges with arbitrary types** (from fields like `related:`, `implements:`, etc.) stay in `kg_edges`. They are hidden from MCP by cycle 3's read-filter; they are indistinguishable from hand-authored edges, so the purge can't safely remove them. They become orphans in place.

## Scope boundaries

In scope:
- Unregister GraphProjector from save-time hooks.
- Remove `/admin/knowledge/project-all` endpoint (+ its test coverage).
- Delete `GraphProjector.java`, `GraphProjectorTest.java`, `FrontmatterRelationshipDetector.java`, `FrontmatterRelationshipDetectorTest.java`.
- Update test fixtures / Javadoc references.
- V012 migration — purge `links_to` edges.
- Full-build verify.

Out of scope (flagged for user follow-up if desired):
- Admin React UI button/page for "Project all" — if it exists, it will 404 or similar after this cycle. Cosmetic.
- Purging non-`links_to` legacy edges — requires case-by-case review; left in place per the above.

---

## File Structure

**Modifying:**
- `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` — remove GraphProjector wiring (imports, `managers.put`, filter registration, helper-method signatures if they passed it through).
- `wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java` — remove GraphProjector construction + field from the services record.
- `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java` — remove `handleProjectAll` method + its route wiring + import.
- `wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java` — drop the "GraphProjector" reference in Javadoc (stale after retirement).
- `wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalService.java` — drop the "GraphProjector routes" Javadoc comment.
- `wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalServiceTest.java` — trivial comment update.
- `wikantik-main/src/test/java/com/wikantik/knowledge/test/InMemoryPageSaveHelper.java` — trivial Javadoc update.
- `wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeResourceTest.java` — remove `handleProjectAll` test coverage.

**Deleting:**
- `wikantik-main/src/main/java/com/wikantik/knowledge/GraphProjector.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/GraphProjectorTest.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterRelationshipDetector.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterRelationshipDetectorTest.java`

**Creating:**
- `bin/db/migrations/V012__retire_graph_projector_links_to_edges.sql` — purge `links_to` edges.

---

## Conventions

- Work on main (no branches — per project CLAUDE.md).
- Apache 2 header on new SQL migration (SQL comment form, copy from an existing migration like `bin/db/migrations/V011__chunk_entity_mentions.sql`).
- Migration must be idempotent per `bin/db/migrations/README.md`.
- Tests stay green at every commit.
- `git rm` for deletions (preserves history).

---

## Task 1: Unwire `GraphProjector` from the engine

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java`

This task removes the production wiring so GraphProjector is no longer constructed or invoked. The class file remains (Task 3 deletes it), so tests that reference it still compile — those get cleaned up in Task 4.

- [ ] **Step 1: Review where GraphProjector gets wired**

```bash
grep -n "GraphProjector\|graphProjector" wikantik-main/src/main/java/com/wikantik/WikiEngine.java
grep -n "GraphProjector\|graphProjector" wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java
```

- [ ] **Step 2: Remove GraphProjector from `KnowledgeGraphServiceFactory`**

Open `wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java`.

Find the `record KnowledgeGraphServices(...)` (or similarly named record/class) that exposes `.graphProjector()`. Remove the `GraphProjector graphProjector` field from the record signature and drop the constructor argument. If the record has a generated accessor `.graphProjector()`, all callers need to stop calling it (WikiEngine does — Task 1 Step 3 handles that).

Find the line `final GraphProjector projector = new GraphProjector( kgService, spr );` (roughly line 127). Delete it. Wherever `projector` was passed into the services record instance, remove that argument too.

Remove the import:
```java
import com.wikantik.knowledge.GraphProjector;
```

(Keep `import com.wikantik.knowledge.chunking.ChunkProjector;` — that's the other, non-retired class.)

- [ ] **Step 3: Remove GraphProjector from `WikiEngine`**

Open `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`. Make four changes:

1. Remove import:
```java
import com.wikantik.knowledge.GraphProjector;
```

2. Delete the manager registration (around line 596):
```java
            managers.put( GraphProjector.class, svcs.graphProjector() );
```

3. Delete the filter registration (around line 654):
```java
            filterManager.addPageFilter( svcs.graphProjector(), -1003 );
```

4. Update the preceding comment (around line 650-652) that explains the filter ordering — it references `GraphProjector` at priority -1003. Replace the comment with:
```java
            // Register filters (priority order preserved; higher priority runs first).
            // ChunkProjector at -1005 is the active save-time chunker for the embedding
            // / entity-extraction pipelines.
```

- [ ] **Step 4: Compile + test**

```bash
cd /home/jakefear/source/jspwiki && mvn -pl wikantik-main -am -q compile
```

Expected: BUILD SUCCESS.

Test compile will fail because test files still reference GraphProjector directly (`GraphProjectorTest.java` etc.). That's expected — Task 3/4 resolves it. Production compile is the gate here.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java
git commit -m "refactor(knowledge): unwire GraphProjector from WikiEngine + factory"
```

---

## Task 2: Remove `/admin/knowledge/project-all` endpoint

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeResourceTest.java`

- [ ] **Step 1: Inspect the route wiring + handler**

```bash
grep -n "project-all\|projectAll\|handleProjectAll\|GraphProjector" wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java
```

Expected: the import (`import com.wikantik.knowledge.GraphProjector;`), the route dispatch (a switch case or equivalent routing `"project-all" → handleProjectAll(...)`), the `handleProjectAll(HttpServletResponse)` method.

- [ ] **Step 2: Remove the import, the route, and the handler**

Edit the file:

1. Delete the import `import com.wikantik.knowledge.GraphProjector;`.
2. Delete the route dispatch case for `"project-all"` (the switch/if-chain entry that invokes `handleProjectAll`).
3. Delete the entire `handleProjectAll(HttpServletResponse)` method (the block that obtains the projector manager, iterates pages, calls `projector.projectPage(...)` and sends the JSON response).

If removing the handler leaves an unused import (e.g., `ParsedPage`, `FrontmatterParser`, `Collection`, `ArrayList`, `Page`), delete those imports too.

- [ ] **Step 3: Update the test**

```bash
grep -n "project-all\|projectAll\|handleProjectAll\|GraphProjector" wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeResourceTest.java
```

Delete any test methods that exercise the deleted endpoint (likely one test method named something like `projectAll_scansAndProjectsAllPages`). Remove any helper mocks for `GraphProjector`. Remove the `import com.wikantik.knowledge.GraphProjector;` from the test file if present.

- [ ] **Step 4: Compile + test**

```bash
mvn -pl wikantik-rest -am -q test -Dtest=AdminKnowledgeResourceTest
```

Expected: surviving tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeResourceTest.java
git commit -m "chore(admin): remove /admin/knowledge/project-all endpoint (GraphProjector retired)"
```

---

## Task 3: Delete `GraphProjector.java` + its test

**Files:**
- Delete: `wikantik-main/src/main/java/com/wikantik/knowledge/GraphProjector.java`
- Delete: `wikantik-main/src/test/java/com/wikantik/knowledge/GraphProjectorTest.java`

- [ ] **Step 1: Confirm no production callers remain**

```bash
cd /home/jakefear/source/jspwiki
grep -rln "\bGraphProjector\b" --include="*.java" . | grep -v "/target/" | grep -v "GraphProjectorTest" | grep -v "GraphProjector.java"
```

Expected hits after Tasks 1 and 2: NONE in `src/main/`. If production code still references the class, stop and investigate — something was missed.

Test-side hits may still exist in:
- `wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalServiceTest.java` — comment reference only (cleaned up in Task 5)
- `wikantik-main/src/test/java/com/wikantik/plugin/RelationshipsPluginTest.java` — uses "GraphProjector" as a test fixture node NAME (string, not the class) — no code change needed
- `wikantik-main/src/test/java/com/wikantik/knowledge/test/InMemoryPageSaveHelper.java` — Javadoc comment (cleaned up in Task 5)

Those are acceptable — they don't break the compile.

- [ ] **Step 2: Delete the files**

```bash
git rm wikantik-main/src/main/java/com/wikantik/knowledge/GraphProjector.java
git rm wikantik-main/src/test/java/com/wikantik/knowledge/GraphProjectorTest.java
```

- [ ] **Step 3: Full compile + test**

```bash
mvn -pl wikantik-main -am -q test -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -10
```

Expected: BUILD SUCCESS. Any lingering compile error means something references `GraphProjector` that Tasks 1/2 missed — fix in the same commit.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(knowledge): delete GraphProjector + its test"
```

---

## Task 4: Delete `FrontmatterRelationshipDetector` + its test

**Files:**
- Delete: `wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterRelationshipDetector.java`
- Delete: `wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterRelationshipDetectorTest.java`

`FrontmatterRelationshipDetector` was used only by `GraphProjector` (verified via `grep -rln FrontmatterRelationshipDetector`). After Task 3, it's dead code.

- [ ] **Step 1: Confirm no callers**

```bash
grep -rln "FrontmatterRelationshipDetector" --include="*.java" . | grep -v /target/
```

Expected output after Task 3: only `FrontmatterRelationshipDetector.java` + `FrontmatterRelationshipDetectorTest.java` themselves. If anything else surfaces, stop and investigate.

- [ ] **Step 2: Delete**

```bash
git rm wikantik-main/src/main/java/com/wikantik/knowledge/FrontmatterRelationshipDetector.java
git rm wikantik-main/src/test/java/com/wikantik/knowledge/FrontmatterRelationshipDetectorTest.java
```

- [ ] **Step 3: Compile + test**

```bash
mvn -pl wikantik-main -am -q test -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(knowledge): delete FrontmatterRelationshipDetector (unused after GraphProjector retirement)"
```

---

## Task 5: Update stale doc references

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalService.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalServiceTest.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/test/InMemoryPageSaveHelper.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (only if the comment mentioning GraphProjector is no longer accurate — verify first)

- [ ] **Step 1: Update `MentionIndex` class Javadoc**

Open `wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java`. Find the class Javadoc (around lines 37-43). The text references `GraphProjector` as a historical source of unmentioned nodes. Replace that paragraph with:

```java
/**
 * Read-only view over {@code chunk_entity_mentions} — answers "is this node
 * mentioned by the extractor?" and "what nodes share chunks with this one?"
 * Provides the mention-covered subset that agent-facing MCP tools surface.
 * Nodes that exist in {@code kg_nodes} but have no extractor mention are
 * hidden — typically legacy frontmatter/link-derived nodes left over from
 * the retired projection pipeline.
 */
```

(Remove the specific `GraphProjector` reference — the comment stays accurate even without naming the retired class.)

- [ ] **Step 2: Update `HubProposalService` Javadoc**

Open `wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalService.java`. Find the comment around line 165 mentioning "GraphProjector routes". Update it to reflect that `kg_edges` membership is now maintained via admin UI / API (not a save-time filter):

```java
     * <p>Membership is the single source of truth stored in kg_edges — admin
     * tooling and the proposal-approval flow route canonical "member-of"
     * relationships into it; hub discovery reads from there.</p>
```

- [ ] **Step 3: Update test comments**

`wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalServiceTest.java` line 77: replace the existing `// Hub + 3 member articles, wired by related edges (mirrors GraphProjector).` with `// Hub + 3 member articles, wired via upsertEdge (manual admin/API path).`

`wikantik-main/src/test/java/com/wikantik/knowledge/test/InMemoryPageSaveHelper.java` line 31 (or wherever the Javadoc mentions GraphProjector): replace `GraphProjector.postSave does in production` with something accurate — e.g. `Mirrors production's save-time behavior.` Keep it concise.

- [ ] **Step 4: Check the properties file reference**

```bash
grep -n "GraphProjector\|ChunkProjector" wikantik-main/src/main/resources/ini/wikantik.properties
```

The only hit should be around line 1219 — `# page saves (ChunkProjector is failure-isolated, but this lets ops short` — which is about ChunkProjector, NOT GraphProjector. No change needed.

- [ ] **Step 5: Compile + test**

```bash
mvn -pl wikantik-main -am -q test -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/HubProposalServiceTest.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/test/InMemoryPageSaveHelper.java
git commit -m "docs(knowledge): drop stale GraphProjector references from javadoc/tests"
```

---

## Task 6: V012 migration — purge `links_to` edges

**Files:**
- Create: `bin/db/migrations/V012__retire_graph_projector_links_to_edges.sql`

Only `links_to` edges are unambiguously written by GraphProjector (from body-link extraction). Frontmatter-derived edges use arbitrary relationship types that may also be hand-authored, so those stay. This is the conservative purge: small, reversible by re-deriving (if anyone ever wants to), and safe for cycle 6.

- [ ] **Step 1: Create the migration**

Create `bin/db/migrations/V012__retire_graph_projector_links_to_edges.sql` — follow the header + psql-var pattern of an existing migration (e.g., `V011__chunk_entity_mentions.sql`):

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

-- V012: Purge links_to edges written by the retired GraphProjector.
--
-- The retired GraphProjector (deleted in cycle 6 of the agent-MCP redesign)
-- used to write every markdown body link as an edge with
-- relationship_type='links_to'. These edges are unambiguously projector-
-- derived — no other write path produces 'links_to' — so this purge is
-- safe and idempotent: re-running finds no rows to delete.
--
-- Frontmatter-derived edges (arbitrary relationship types) are NOT
-- purged: they may include manually-authored relationships that happen
-- to share a type name. Cycle 3's MCP read-filter hides them from
-- agent consumers; they remain for admin-UI inspection.

DELETE FROM kg_edges WHERE relationship_type = 'links_to';

-- No grants needed — existing grants on kg_edges cover this.
```

- [ ] **Step 2: Syntax-check against the test schema**

```bash
cd /home/jakefear/source/jspwiki
psql "postgresql://test-harness-connection-if-any" -c "DELETE FROM kg_edges WHERE relationship_type = 'links_to'" 2>&1 | head -5
```

Skip this step if no local pg is running. The migration is trivial SQL; running it once in a dev DB on the next `deploy-local.sh` will validate it in place.

- [ ] **Step 3: Register in the migration runner if needed**

Most migration systems auto-discover files via the `V<NNN>__` filename prefix. Confirm by reading `bin/db/migrate.sh`:

```bash
grep -n "V[0-9]\|migrations" bin/db/migrate.sh | head -10
```

If auto-discovered (expected), no config change.

- [ ] **Step 4: Commit**

```bash
git add bin/db/migrations/V012__retire_graph_projector_links_to_edges.sql
git commit -m "feat(db): V012 — purge links_to edges written by retired GraphProjector"
```

---

## Task 7: Full build + close cycle + close the redesign

**Files:** none created

- [ ] **Step 1: Full multi-module build**

```bash
cd /home/jakefear/source/jspwiki && mvn clean install -T 1C -DskipITs 2>&1 | grep -E "BUILD|ERROR" | tail -10
```

Expected: BUILD SUCCESS. All tests pass across all modules.

- [ ] **Step 2: Mark cycle 6 complete in the spec**

Edit `docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md`. Change cycle 6's heading to:

```
6. **Cycle 6 — `GraphProjector` retirement. ✓**
```

While editing, also update the Risks/Open-Questions section's "Option A vs B" discussion to reflect that both options are now implemented (A in cycle 3, B in cycle 6).

- [ ] **Step 3: Commit + recap**

```bash
git add docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md
git commit -m "chore(spec): mark cycle 6 complete — GraphProjector retired; redesign done"
```

- [ ] **Step 4: Final sanity check**

Read the spec one more time and confirm all 6 cycle checkboxes are marked `✓`:

```bash
grep -n "Cycle [1-6] —" docs/superpowers/specs/2026-04-24-agent-mcp-surface-redesign.md | head -10
```

Every cycle line should end with `✓`. If any don't, that cycle's completion marker was missed — trace back and mark it.

---

## Summary

At cycle 6 complete:

- `GraphProjector` no longer runs at save time — legacy frontmatter + link-graph projection is fully retired.
- Admin endpoint `/admin/knowledge/project-all` removed (UI button, if any, will 404 — cosmetic follow-up).
- `GraphProjector.java`, `GraphProjectorTest.java`, `FrontmatterRelationshipDetector.java`, `FrontmatterRelationshipDetectorTest.java` deleted.
- V012 migration purges `links_to` edges (the only unambiguous legacy row type).
- Frontmatter-derived edges with arbitrary types remain in `kg_edges` but stay hidden from MCP via cycle 3's read-filter.
- All stale doc / javadoc references updated.

With cycle 6 complete, the **agent MCP surface redesign is done**. The 6-cycle rollout delivered:

- Cycle 1: `ContextRetrievalService` shared layer (eliminated the `search_pages` lexical-only gap).
- Cycle 2: 4 new agent-facing tools on `/knowledge-mcp` (retrieve_context, get_page, list_pages, list_metadata_values).
- Cycle 3: KG tools rebacked onto the mention graph (read-filter).
- Cycle 4: admin-mcp rename + write_pages/update_page; 9 obsolete tools deleted.
- Cycle 5: Tool-server shape parity (contributingChunks/relatedPages on /tools/search_wiki).
- Cycle 6: GraphProjector retired; legacy row purge; redesign complete.
