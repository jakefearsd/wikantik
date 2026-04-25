# Structural Spine — Phase 2 Implementation Plan

> **Status:** Implemented (commits `39c83df02..40fb65c6b` on `main`).

**Goal:** Promote cross-references from untyped Markdown links to typed,
machine-queryable relations. Phase 1 stood up the canonical_id spine and the
empty `page_relations` table; Phase 2 fills the table from frontmatter,
exposes a typed-relation graph through REST and MCP, and lays the validator
groundwork that Phase 4 will switch from warn-only to mandatory.

**Design source:** [docs/wikantik-pages/StructuralSpineDesign.md](../../wikantik-pages/StructuralSpineDesign.md)
(see "Migration path > Phase 2 — Typed relations").

**Architecture:** Closed seven-type relation vocabulary (`RelationType`),
authored in YAML frontmatter as a list of `{type, target}` records. The
existing `DefaultStructuralIndexService.rebuild()` becomes a two-pass scan:
pass 1 collects pages and authored canonical_ids, pass 2 validates each
page's relations against the just-built canonical_id set, persists the
surviving edges via `PageRelationsDao` (per-source DELETE-then-INSERT),
and adds them to an in-memory adjacency graph the projection serves through
`outgoingRelations()`, `incomingRelations()`, and a depth-bounded BFS
`traverse()`. Phase 2 is warn-only — the validator drops bad entries with
a WARN log and continues.

**Tech stack delta vs Phase 1:** No new external dependencies. Reuses the
existing `wikantik-bom` ULID pin, JDBC + H2 testing harness, Mockito,
`MeterRegistryHolder`, and the `wikantik-knowledge` MCP scaffolding.

---

## What ships at end of Phase 2

| Layer | Class / endpoint | Behaviour |
|-------|------------------|-----------|
| Value types | `RelationType`, `Relation`, `RelationEdge`, `RelationDirection`, `TraversalSpec` | Closed vocabulary; directional source/target/type triples; depth-bounded BFS spec |
| DB | `PageRelationsDao` | Per-source replace-set (DELETE-then-INSERT in one tx) over `page_relations` |
| Validator | `FrontmatterRelationValidator` | Parses `relations:` field, classifies issues (UNKNOWN_TYPE, MISSING_TARGET, TARGET_MISSING, SELF_REFERENCE, MALFORMED_ENTRY), dedupes |
| Service | `StructuralIndexService.{outgoingRelations,incomingRelations,traverse}` | Three new interface methods, delegated to the in-memory projection |
| Service impl | `DefaultStructuralIndexService.rebuild()` two-pass | Parses + persists + indexes relations; synthesised canonical_ids do not write to DB |
| Projection | `StructuralProjection` adjacency lists + BFS | Each emitted `RelationEdge` carries resolved target slug+title (or null when dangling) |
| REST | `GET /api/relations/{canonical_id}` | `direction=out\|in\|both`, `type=part-of\|...`, `depth=1..5` |
| MCP | `traverse_relations` on `/knowledge-mcp` | Inputs `{from, direction?, type_filter?, depth_cap?}` |

---

## Task ledger (executed)

| # | Task | Commit |
|---|------|--------|
| P2-T1 | Relation value types | `39c83df02` |
| P2-T2 | `PageRelationsDao` + H2 tests | `f8471dfca` |
| P2-T3 | `StructuralProjection` relation graph + BFS | `2389d5765` |
| P2-T4 | `rebuild()` parses + persists relations | `cde8e1eee` |
| P2-T5 | `FrontmatterRelationValidator` (warn-only) | `de5c0cfec` |
| P2-T6 | `PageRelationsResource` at `/api/relations/*` | `c0ab293a7` |
| P2-T7 | `TraverseRelationsTool` (MCP) | `e88d9e39f` |
| P2-T8 | Register MCP tool in initializer | `02844180a` |
| P2-T9 | IT coverage in `RestApiIT` | `40fb65c6b` |
| P2-T10 | Full verification + this doc | (this commit) |

Total commits: 10 functional + this doc = 11.
Total new tests in Phase 2: **30** (6 `PageRelationsDaoTest`, 4 new
`StructuralProjectionTest`, 2 new `DefaultStructuralIndexServiceTest`,
8 `FrontmatterRelationValidatorTest`, 6 `PageRelationsResourceTest`,
3 `TraverseRelationsToolTest`, 2 IT methods on `RestApiIT`).

---

## Deviations from the design doc

1. **REST path renamed.** Design specified
   `GET /api/pages/{canonical_id}/relations`. Servlet URL patterns don't
   support embedded path segments and `/api/pages/*` is already split
   between `PageResource` and `PageByIdResource`. Phase 2 mounts the
   resource at `/api/relations/{canonical_id}` instead. Tests, web.xml,
   and ITs all use the new path.

2. **Synthesised canonical_ids skip relation persistence.** The plan's
   intent — "synthesised IDs live in memory" — extends to relations:
   when a page lacks an authored `canonical_id`, neither its
   `page_canonical_ids` row nor its outgoing relations land in the DB,
   only in the in-memory projection. Phase 4 will flip this when
   canonical_ids become mandatory.

3. **`traverse()` always emits edges into dangling targets.** When a
   relation's target no longer resolves to a known canonical_id, the
   projection still emits a `RelationEdge` with `targetSlug == null`
   and `targetTitle == null`. Callers can surface that as a broken-link
   warning. The DAO refuses to insert such rows because the FK
   constraint rejects them — the dangling edges only exist when the
   target was known at save time and later disappeared.

4. **Two-pass rebuild instead of incremental relation update.** Page
   saves still trigger a full rebuild (deferred from Phase 1). Phase 3
   or a follow-on plan can implement incremental relation updates if
   profiling shows it.

---

## Out of scope, explicitly deferred

| Item | Why deferred | Where it'll land |
|------|--------------|------------------|
| `ProposeRelationsTool` in `/wikantik-admin-mcp` | Needs alignment with the existing knowledge-graph proposal workflow (`ProposeKnowledgeTool`). Worth its own plan. | Phase 2.1 or Phase 3 |
| `infer-relations` CLI heuristic | Simpler to bootstrap relations by hand on the `wikantik-development` cluster first; heuristic without a labelled set risks polluting authored data. | Optional follow-up |
| Backfill sweep across clusters | Author-driven editorial work, not code | Done by user as content updates roll forward |
| Phase 4 enforcement (mandatory canonical_id, reject unresolvable targets) | Validator already exists — Phase 4 is a one-line policy flip plus admin UI | Separate plan |

---

## Verification

`mvn install -T 1C -DskipITs -fae -pl '!wikantik-admin-mcp' -am` is **green**
across all 19 buildable modules (the `wikantik-admin-mcp` exclusion is
unrelated user WIP — the maintainer has re-added `ReadPageTool` /
`DeletePagesTool` intentionally and `McpToolRegistryTest` will be updated
separately).

Integration test suite (`mvn install -Pintegration-tests -fae`) needs Docker
+ pgvector and is yours to run before merging. The two new IT methods
(`testRelationsEndpointResponds`, `testRelationsRejectsMalformedPath`) ride
the existing Cargo-Tomcat fixture.

---

## Smoke commands (post-deploy)

```bash
# Sanity: pick any page with declared relations and walk one hop.
curl -s "http://localhost:8080/api/structure/sitemap" | jq -r '.data.pages[0].id' | \
  xargs -I{} curl -s "http://localhost:8080/api/relations/{}?depth=2"

# MCP: dispatch traverse_relations against /knowledge-mcp using your usual client.
# Tool definition advertises inputs {from (required), direction, type_filter, depth_cap}.
```

---

## Next phases

- **Phase 3 — Generated `Main.md`:** template + `wikantik-wikipages-builder`
  generator + pre-commit guard + pins sidecar.
- **Phase 4 — Enforcement:** flip `FrontmatterRelationValidator` from
  warn-only to reject; require `canonical_id` on save; admin UI for
  structural conflicts.

When ready, ask for a Phase 3 plan and I'll write it from the design doc's
"Migration path > Phase 3" section.
