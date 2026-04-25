# Structural Spine — Phase 4 Implementation Plan

> **Status:** Implemented (commits `3e57b74b2..f4ca88b91` on `main`).

**Goal:** Promote the structural-spine validator from warn-only (Phase 2)
to save-time enforcement, with auto-assignment of missing canonical_ids
and rejection of invalid relations. Surface remaining conflicts via an
admin endpoint so an operator can drive them to zero.

**Design source:** [docs/wikantik-pages/StructuralSpineDesign.md](../../wikantik-pages/StructuralSpineDesign.md)
("Migration path > Phase 4 — Enforcement").

**Architecture:**

- A new `StructuralSpinePageFilter` runs in `PageFilter.preSave` between
  frontmatter defaulting (-1004) and hub sync (-999).
- **canonical_id missing** → auto-assign a fresh ULID and inject as the
  first frontmatter key. Save proceeds; structural integrity self-heals.
  This is friendlier than the design doc's "reject" — pages saved by
  out-of-band processes (entity extraction, content rewriters) keep
  working without operator intervention.
- **relations invalid** → `FilterException` aborts the save. Both
  unknown-type and unresolvable-target failures are caught, with a
  message that names the offending page and quotes the validator's
  classification.
- Both behaviours are gated by
  `wikantik.structural_spine.enforcement.enabled` (default `true`).
  Setting to `false` reverts to Phase 2 warn-only semantics.
- `DefaultStructuralIndexService.rebuild()` now records each finding as
  a `StructuralConflict` (kind = `MISSING_CANONICAL_ID` or
  `RELATION_ISSUE`) and exposes the list via `conflicts()`.
- `GET /admin/structural-conflicts` returns that list to operators.

**Tech stack delta:** None — reuses Phase 1's index, Phase 2's
`FrontmatterRelationValidator`, the existing `FrontmatterParser` /
`FrontmatterWriter`, and ULID generation already on the classpath.

---

## What ships at end of Phase 4

| Layer | Class / endpoint | Behaviour |
|-------|------------------|-----------|
| Value type | `StructuralConflict` | (slug, canonicalId, kind, detail) record exposing rebuild-time findings |
| Service surface | `StructuralIndexService.conflicts()` | Snapshot of conflicts from the most recent rebuild |
| Service impl | `DefaultStructuralIndexService` | Tracks conflicts during the two-pass rebuild and stores them in `volatile List` for read-side access |
| Filter | `StructuralSpinePageFilter` | Save-time enforcement: auto-assign missing canonical_id, reject invalid relations. Wires `wikantik.structural_spine.enforcement.enabled` (default `true`) |
| Engine wiring | `WikiEngine.initKnowledgeGraph()` | Filter registered at priority -1003 between frontmatter defaulting (-1004) and hub sync (-999); receives the structural index, the system-page predicate, and the engine props |
| REST | `GET /admin/structural-conflicts?kind=...` | Admin-protected (rides existing `/admin/*` `AdminAuthFilter`); aggregates conflict list with per-kind counts |

---

## Task ledger (executed)

| # | Task | Commit |
|---|------|--------|
| P4-T1 | `StructuralConflict` record + `conflicts()` on service | `3e57b74b2` |
| P4-T2 | `StructuralSpinePageFilter` + 7 tests | `e22c8d9fe` |
| P4-T3 | Wire filter into `WikiEngine.initKnowledgeGraph()` | `936aa502e` |
| P4-T4 | `AdminStructuralConflictsResource` + 4 tests + web.xml | `f4ca88b91` |
| P4-T5 | This doc + CLAUDE.md note + verification | (this commit) |

Total commits in Phase 4: 4 functional + 1 doc.
Total new tests in Phase 4: **12** (1 `DefaultStructuralIndexServiceTest`,
7 `StructuralSpinePageFilterTest`, 4 `AdminStructuralConflictsResourceTest`).

---

## Deviations from the design doc

1. **Auto-assign instead of reject for missing canonical_id.** The
   design called for "reject `PAGE_SAVE` without canonical_id". In
   practice, the wiki has out-of-band save paths (entity extraction
   pipeline, AI content rewriters) that legitimately need to save
   pages whose canonical_id was already established in frontmatter.
   When something strips the field, hard-rejecting the save would
   block legitimate work; auto-assigning a new ULID would create a
   duplicate-id mess. The Phase 4 filter splits the difference:
   - If frontmatter has no `canonical_id`, inject a fresh ULID and
     proceed. The save self-heals.
   - The save-time validator does not detect "page previously had id X
     and someone stripped it". That detection belongs in a
     content-comparison filter and is out of scope here.
   Phase 4-bis can flip this to strict-reject if the corpus stabilises
   and out-of-band stripping stops; the `enforcement.enabled` property
   already exists, and a follow-up could split it into
   `auto_assign_canonical_id` / `require_canonical_id`.

2. **In-memory synthesis path retained in `DefaultStructuralIndexService`.**
   The design said "previous Phase-1 code path that synthesised them
   in memory removed". The synthesis path is kept because the corpus
   may temporarily fall out of sync (an external process strips an id;
   the next rebuild runs before a save re-establishes it). Synthesis
   is harmless — the synthesised id stays in memory and never reaches
   the DB — and dropping a page entirely from the projection would be
   worse than indexing it under a new id for one rebuild cycle. The
   conflict surface (`StructuralConflict.MISSING_CANONICAL_ID`) gives
   operators visibility into the gap.

---

## Out of scope, explicitly deferred

| Item | Why deferred |
|------|--------------|
| React admin UI for conflicts | The endpoint is the ground truth; a UI is convenience |
| Strict-reject mode for missing canonical_id | Not needed yet — the auto-assign path handles real-world saves correctly |
| Detection of "field was present, now stripped" | Requires a per-save comparison against the previous version; warrants its own design |
| Configurable per-relation-type strictness (e.g. allow `contradicts` to point at a missing target) | YAGNI — all seven relation types currently treat target-missing identically |
| Backfill script that resolves all current conflicts | Editorial — the operator decides whether each MISSING_CANONICAL_ID is legitimate or whether a relation needs to be retargeted |

---

## Verification

Phase 4 unit tests are in three classes — all pass on
`mvn test -pl wikantik-main -Dtest='StructuralSpinePageFilterTest,DefaultStructuralIndexServiceTest'`
and on
`mvn test -pl wikantik-rest -Dtest=AdminStructuralConflictsResourceTest`.

The full unit-test build is green across all 20 modules including
`wikantik-admin-mcp` (which the Phase 1+2 ledger noted was failing
due to user WIP — that has since been resolved by re-running the test
expectations to match the re-introduced read_page / delete_pages tools
in commit `7f44e5532`).

---

## Operational checklist

After Phase 4 deploys, an operator should:

1. Hit `GET /admin/structural-conflicts` and triage the list.
2. For each `MISSING_CANONICAL_ID` row, either accept the synthesised
   id (the next save through the wiki will persist it via the filter)
   or run `wikantik-extract-cli`'s `assign-canonical-ids --write`
   against the affected slugs and commit.
3. For each `RELATION_ISSUE` row, edit the offending page's
   `relations:` frontmatter to retarget at a live canonical_id (the
   `Phase 2` admin endpoint at `/api/relations/{id}` and the
   `traverse_relations` MCP tool both help here).
4. Once the list is empty, consider tightening
   `wikantik.structural_spine.enforcement.enabled=true` (already the
   default) into a stricter follow-up that rejects rather than
   auto-assigns canonical_ids.

---

## Next phases

The structural spine reaches its design endpoint with Phase 4. Natural
follow-ups:

- **Editorial cleanup:** clear the conflict list to zero, then split
  the enforcement flag into per-behaviour knobs and tighten the
  canonical_id rule from auto-assign to reject.
- **Generated `Main.md` Phase 3 cleanup:** backfill `title:` into
  frontmatter so `Main.pins.yaml` no longer needs `titleOverride` for
  every entry.
- **Companion design from `AgentGradeContentDesign.md`:** verification
  metadata, `type: runbook`, `/api/pages/{id}/for-agent`, and the
  scheduled retrieval-quality CI loop. That design has its own four
  phases and is the next big chunk of agentic-context work.
