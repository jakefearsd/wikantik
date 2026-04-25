# Agent-Grade Content — Phase 3 Implementation Plan

> **Status:** Implemented (commits `a7822484c..c8a1c9d77` on `main`).

**Goal:** Make `type: runbook` a first-class, schema-validated page type. Authors write a structured `runbook:` block in frontmatter; saves with an invalid runbook are rejected; the `/for-agent` projection's previously-empty `runbook` field is populated with the parsed block.

**Design source:** [docs/wikantik-pages/AgentGradeContentDesign.md](../../wikantik-pages/AgentGradeContentDesign.md)
("The `runbook` page type" section).

**Architecture:**

- A new value type `RunbookBlock` (record) lives in `wikantik-api` alongside `ForAgentProjection`. Field names use the snake_case wire form (`when_to_use`, `related_tools`, …) so default Gson serialisation produces snake_case JSON without naming-policy plumbing.
- A pure-function validator `FrontmatterRunbookValidator` in `wikantik-main` walks the raw frontmatter map and returns either a populated `RunbookBlock` or a list of `Issue` entries. Mirrors `FrontmatterRelationValidator` exactly — same `Result` / `Issue` / `IssueKind` shape, different schema rules.
- A new `RunbookValidationPageFilter` in `wikantik-main` calls the validator from `preSave` and rejects invalid runbooks with `FilterException`. Registered at filter priority -1003 (same band as `StructuralSpinePageFilter`), gated by `wikantik.runbook.enforcement.enabled` (default `true`).
- `DefaultForAgentProjectionService` runs the validator at read time too. When `type: runbook` and the block validates, the parsed `RunbookBlock` is placed on `ForAgentProjection.runbook`; when invalid, `runbook` stays null and `"runbook"` lands on `missing_fields`.
- The REST resource and MCP tool need no changes: the existing `Object runbook` field round-trips through their respective `Gson` serialisers. Phase 2's `serializeNulls()` keeps `"runbook": null` in the contract; Phase 3 just stops producing null for runbook pages.

**Tech stack delta:** None. Reuses Phase 2's `ForAgentProjection`, the existing `FilterManager` / `PageFilter` / `FrontmatterParser` plumbing, the structural index for canonical_id checks, and `PageManager.pageExists` for title checks.

---

## What ships

| Layer | Class / endpoint | Behaviour |
|-------|------------------|-----------|
| Value type | `RunbookBlock` (record) | Six fields with snake_case wire names: `when_to_use`, `inputs`, `steps`, `pitfalls`, `related_tools`, `references` |
| Validator | `FrontmatterRunbookValidator` | Pure function → `Result(valid: Optional<RunbookBlock>, issues: List<Issue>)`. Schema rules per design doc |
| Save filter | `RunbookValidationPageFilter` | preSave at -1003; throws `FilterException` on invalid runbook saves; gated by `wikantik.runbook.enforcement.enabled` (default `true`) |
| Engine wiring | `WikiEngine.initKnowledgeGraph()` | Registers the new filter alongside `StructuralSpinePageFilter` |
| Projection | `DefaultForAgentProjectionService` | When `type==runbook` and block validates → populate `ForAgentProjection.runbook`; when invalid → leave null, add `"runbook"` to `missing_fields` |
| Wire | REST + MCP | No code change — existing serialisers carry the new payload by construction |

---

## Task ledger (executed)

| # | Task | Commit |
|---|------|--------|
| P3-T1 | `RunbookBlock` record in `wikantik-api/.../agent` (snake_case fields) | `a7822484c` |
| P3-T2 | `FrontmatterRunbookValidator` + 12 unit tests | `7332aad16` |
| P3-T3 | `RunbookValidationPageFilter` + 7 unit tests | `31e492ba4` |
| P3-T4 | `WikiEngine.initKnowledgeGraph()` registers the filter at -1003 | `ef4e8134b` |
| P3-T5 | `DefaultForAgentProjectionService` populates `runbook` field; +2 unit tests | `c8a1c9d77` |
| P3-T6 | Full build + manual smoke (no commit — verification) | (no commit) |
| P3-T7 | This doc + CLAUDE.md note + memory updates | (this commit) |

Total commits: 5 functional + 1 doc.
Total new tests: **21** (12 `FrontmatterRunbookValidatorTest`, 7 `RunbookValidationPageFilterTest`, 2 added to `DefaultForAgentProjectionServiceTest`).

---

## Material deviations from the design doc

1. **Validator returns `Optional<RunbookBlock>` rather than throwing on
   invalid.** The design doc said "FrontmatterParser validates that
   when type: runbook…" — the validator surface in the design implied
   throwing. The implementation returns a `Result` with optional
   valid output + list of issues, mirroring `FrontmatterRelationValidator`.
   This lets callers decide whether to reject (the save filter does)
   or warn (the projection service surfaces a `missing_fields` entry).
   Single contract, two consumers, zero throwing.

2. **Snake_case fields on `RunbookBlock`.** The record uses
   `when_to_use` / `related_tools` / etc. as Java field names so
   default Gson serialisation produces snake_case JSON without a
   per-instance `FieldNamingPolicy` and without adding a Gson
   dependency to `wikantik-api`. The Java naming convention is bent;
   the wire shape is clean.

3. **Filter priority -1003 (same band as `StructuralSpinePageFilter`)
   rather than a fresh slot.** Both filters validate frontmatter and
   reject invalid saves; pairing them at the same priority surfaces
   the conceptual sibling-ness in the source.

4. **Validator runs at read time too.** The design doc only specified
   save-time validation. The projection service re-runs the same
   validator so corpus drift (or saves made while enforcement was
   disabled, or pages saved through paths that bypass the filter)
   surfaces as a graceful `degraded: true` + `missing_fields: ["runbook"]`
   rather than poisoning the response. Save-time prevents new bad
   runbooks; read-time protects against legacy bad runbooks.

5. **Smoke-test caveat — incremental indexing.** During smoke testing
   it became evident that the structural index does not pick up
   newly-saved pages until a fresh bootstrap rebuild fires (a
   pre-existing limitation flagged on the structural index, not a
   Phase 3 issue). Workaround for live deploys: restart Tomcat to
   force a bootstrap rebuild. Out of scope for Phase 3.

---

## Out of scope, explicitly deferred

| Item | Why deferred |
|------|--------------|
| Seed runbooks (15 cookbook pages) | Phase 4 of the design — authoring effort, not engineering |
| Custom SPA template for runbook body | Phase 3 is a content-shape contract; the default page template renders the body Markdown fine |
| Admin endpoint listing runbooks with validation state | Validation runs at save time (rejects) and at projection time (degrades). A list view is convenience; ops can already see invalid runbooks via `degraded:true` + `"runbook"` on `missing_fields` per page |
| Per-tool-name validation against the live MCP registry | The regex covers the structure; cross-checking against actual registered tool names would create a runtime coupling between authoring and registry state, and the registry can change between save and read |
| Auto-fix wizard for common runbook validation errors | YAGNI for a single-author wiki — the FilterException message names the issue kind |
| Fix structural-index incremental indexing for REST saves | Pre-existing behaviour; surfaced during this phase's smoke test but not introduced by Phase 3. Belongs in a structural-spine follow-up |

---

## Verification

`mvn install -DskipITs` is green across all 26 modules (parallel `-T 1C`
hit the known maven-dependency-plugin race during this run; the
sequential resume completed without further issue).

Targeted tests:
- `mvn test -pl wikantik-main -Dtest='FrontmatterRunbookValidatorTest,RunbookValidationPageFilterTest,DefaultForAgentProjectionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false` — 26 tests, all green.

Manual smoke against deployed WAR (after Tomcat restart):
- `PUT /api/pages/SmokeRunbook` with a valid runbook block → HTTP 200.
- `GET /api/pages/for-agent/{canonical_id}` → returns the populated runbook structure with snake_case keys, no degradation.
- `PUT /api/pages/InvalidSmokeRunbook` with one step → HTTP 500 with the message `Runbook page '…' has invalid frontmatter: [STEPS_TOO_FEW runbook.steps must have at least 2 entries (got 1)]`.

Net effect: agents reading a runbook page through `/for-agent` now
see a structured procedure (when_to_use / steps / pitfalls /
related_tools / references) instead of a `null`. Authors get
immediate feedback when they save a malformed runbook.

---

## Authoring workflow (post-Phase 3)

1. **Author a runbook page**:
   ```yaml
   ---
   title: Choosing a Retrieval Mode
   type: runbook
   runbook:
     when_to_use:
       - Agent needs to pick between BM25, hybrid, and graph traversal
     steps:
       - Try /knowledge-mcp/search_knowledge first (hybrid default)
       - If top-5 share a cluster, list_pages_by_filter to broaden
     pitfalls:
       - Do not chain more than 3 retrieval calls
     related_tools:
       - /knowledge-mcp/search_knowledge
       - /knowledge-mcp/list_pages_by_filter
     references:
       - HybridRetrieval
   ---

   # Choosing a Retrieval Mode
   …prose body…
   ```

2. **Save fails with named issue kind** if the schema is violated —
   the React editor surfaces the `FilterException`'s message.

3. **Read structured payload over `/knowledge-mcp`**:
   ```json
   { "tool": "get_page_for_agent",
     "arguments": { "canonical_id": "01KQ…" } }
   ```
   Response carries `runbook.steps`, `runbook.pitfalls`, etc., as
   first-class structured fields — no need to extract them from
   prose.

4. **Bypass enforcement temporarily** (operator escape hatch):
   set `wikantik.runbook.enforcement.enabled=false` in
   `wikantik-custom.properties` and restart. Existing invalid
   runbooks still degrade gracefully on `/for-agent`.

---

## Next phases (Agent-Grade Content)

- **Phase 4 — Agent cookbook authoring:** ~15 seed runbooks for the
  scenarios coding agents actually hit. Authoring work, not
  engineering — the schema and projection are now ready to host them.
- **Phase 5 — Retrieval-quality CI:** scheduled `RetrievalQualityRunner`
  with Prometheus dashboards.
- **Phase 6 — Tool-description examples:** worked input/output examples
  on every MCP tool's JSON schema.

When ready for Phase 4, ask and I'll outline a kickoff plan for the
first three runbooks (the design doc names `ChoosingARetrievalMode`,
`FindingTheRightMcpTool`, `WritingANewMcpTool`).
