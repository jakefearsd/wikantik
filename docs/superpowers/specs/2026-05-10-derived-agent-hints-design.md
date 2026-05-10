# Derived Agent Hints + Agent Batch Reads — Design

**Status:** Approved (brainstorm 2026-05-10)
**Author:** Jake Fear / Claude (Opus 4.7)
**Targets:** pre-public-release tuning of the agent-facing surface
**Sibling docs:** [AgentGradeContentDesign.md](../wikantik-pages/AgentGradeContentDesign.md), [StructuralSpineDesign.md](../wikantik-pages/StructuralSpineDesign.md), [PageGraphVsKnowledgeGraph.md](../wikantik-pages/PageGraphVsKnowledgeGraph.md)

## Motivation

External agent (Gemini) consuming `/knowledge-mcp` reported two structural complaints with the agent surface:

1. **Discovery → action gap.** The `/for-agent` projection tells an agent *what a page is about* but not *which tools or sibling pages are authoritative for the topic*. Agents either re-read the body to harvest signal or guess from `related_pages` summaries. Both waste tokens and degrade first-call success rate.
2. **Per-page read tax.** Cluster-spanning research forces one `read_page` call per page. A 4-page exploration is 4 round-trips when it could be 1.

A naïve fix is an authored `agent_hints:` frontmatter block (mirroring `runbook:`). We rejected this in brainstorming: the runbook block has the excuse of being a page-type-specific affordance with a clear "I am writing a runbook" trigger; an `agent_hints:` block would impose schema burden across the entire methodology/conceptual corpus and would not realistically be populated. The right move is to **derive hints in code** from signals we already maintain (Page Graph, structural index, verification metadata, tool-hint extractor) and surface them on the existing projection — zero author burden.

This design also closes Gemini's secondary complaint about generic hub summaries by overlaying a synthesized Top-3 highlight at projection time when a hub's authored summary matches the "Index of pages on…" pattern. It does *not* write back to page bodies.

## Non-Goals

- Authored `agent_hints:` frontmatter, validator, save filter (rejected — author burden).
- Embedded JSON-Schema literals on pages (Gemini's "Standard Requirement JSON Schema" ask). Deferred until we see authors actually want it.
- `avoid_tools:` anti-recommendations. Wait for a real instance.
- Writing the synthesized hub summary back to the page body. Read-only overlay only.
- Surfacing `agent_hints` in `search_knowledge` results (v2 if usage shows agents always-fetch projections to read hints).
- Schema/typing for cross-page typed semantics — the Page Graph is wikilinks-only by deliberate design (see [Page Graph vs Knowledge Graph](../wikantik-pages/PageGraphVsKnowledgeGraph.md)). Derivation uses centrality, not relation labels.

## Architecture Overview

Three deliverables in one spec, sharing graph/metadata queries and one cache:

1. **Derived `agent_hints` on `/for-agent`** — new projection field populated by `AgentHintsDeriver`. Agent UX win.
2. **Hub summary synthesis overlay** — `HubSummarySynthesizer` swaps in a Top-3 highlight when the hub's authored summary matches a generic regex. Read-only, projection-time only.
3. **`read_pages(slugs[])` MCP tool** on `/knowledge-mcp` — batched raw markdown reads, capped at 20, partial-success result shape.

Plus one operator surface:

4. **`GET /admin/agent-grade-audit`** — paginated weak-signal report so authors can find pages worth manual improvement without imposing a frontmatter schema.

### Components

| Component                                                                                     | Module                                           | Purpose                                                                                                                                                                      |
| --------------------------------------------------------------------------------------------- | ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `AgentHintsBlock` (record: `prefer_tools: List<String>`, `prefer_pages: List<PreferredPage>`) | `wikantik-api` | New typed slot on `ForAgentProjection`. Snake_case Java field names so default Gson serialisation matches the wire form (mirrors `RunbookBlock` convention). |
| `PreferredPage` (record: `canonical_id`, `title`, `role`) | `wikantik-api` | Each `prefer_pages` entry. `title` included so agents do not need a second projection call to decide whether to fetch. `role` ∈ {`cluster_hub`, `authoritative_reference`, `cluster_member`}. |
| `AgentHintsDeriver` | `wikantik-main` (`com.wikantik.knowledge.agent`) | Composes the block from existing signals. Stateless; takes service dependencies via constructor. |
| `HubSummarySynthesizer` | `wikantik-main` (same package) | Detects generic hub summary; synthesises overlay from `prefer_pages[0..2]`. |
| New field `AgentHintsBlock agentHints` on `ForAgentProjection` | `wikantik-api` | Typed concretely (unlike `runbook: Object`, which was deferred-typed for forward compat we no longer need). |
| New field `boolean summarySynthesized` on `ForAgentProjection` | `wikantik-api` | `true` iff the hub overlay fired. Operators and downstream tools can detect synthesis without diffing text. |
| Wire-up in `DefaultForAgentProjectionService` | `wikantik-main` | Adds the `agent_hints` extractor + summary overlay step. Both wrapped in per-step try/catch, populating `degraded` + `missing_fields` on failure (matches existing pattern). |
| `AgentGradeAuditResource` | `wikantik-rest` | `GET /admin/agent-grade-audit?limit=N&offset=M`, behind `AdminAuthFilter`. |
| `ReadPagesTool` | `wikantik-knowledge` (`com.wikantik.knowledge.mcp`) | New MCP tool registered by `KnowledgeMcpInitializer`. |

**Naming convention reminder:** top-level `ForAgentProjection` record fields are camelCase Java (`agentHints`, `summarySynthesized`) and rely on the projection's existing Gson naming policy to emit snake_case on the wire. Nested records (`AgentHintsBlock`, `PreferredPage`) use snake_case Java fields directly — same trick `RunbookBlock` uses, so default Gson serialisation matches the wire form without a per-instance naming policy. Do not mix the two within a single record.

## Detailed Design

### `AgentHintsDeriver`

Stateless service. Single public method:

```java
public AgentHintsBlock derive( String canonicalId );
```

Dependencies (constructor-injected, narrow functional seams for testability — same pattern as the retrieval-quality runner):

- `StructuralIndex` — for cluster membership and hub lookup.
- `PageGraphService` — for inbound wikilink counts within a cluster.
- `McpToolHintsResolver` — existing extractor; reused for body-derived tool hints.
- `PageVerificationService` — for `confidence` lookup.
- `PageManager` — for title resolution on `PreferredPage` entries.

Throws nothing — returns `AgentHintsBlock(List.of(), List.of())` on any internal failure (caller handles whole-block degradation via try/catch around the `derive(...)` call).

#### `prefer_tools` derivation

1. Run `McpToolHintsResolver` on the current page → tool name list.
2. If page has a cluster hub (and hub ≠ self), run resolver on the hub → tool name list.
3. Concatenate both lists, count occurrences, sort by count descending then alphabetical (deterministic tie-break), take top 5, dedupe.
4. Output: `List<String>` of bare tool names (matching the snake_case convention used by `runbook.related_tools`).

#### `prefer_pages` derivation

1. Find current page's cluster (frontmatter `cluster:`). If absent → return empty list.
2. Look up cluster hub via `StructuralIndex`. If hub exists and is not the current page → first entry, `role: cluster_hub`.
3. Query `PageGraphService` for all pages in the same cluster, with their inbound wikilink counts *from other pages in that cluster* (intra-cluster centrality). Exclude self and the hub.
4. For each candidate, compute `score = inbound_count * (verified_authoritative ? 1.5 : 1.0)`.
5. Sort by score descending, then by title ascending (deterministic tie-break). Take top 4 (so hub + 4 = 5 total).
6. Each entry's `role` is `authoritative_reference` if `confidence == authoritative`, else `cluster_member`.
7. Output: `List<PreferredPage>` capped at 5.

If the current page IS the cluster hub: skip step 2, take top 5 from step 3 with no hub entry.

### `HubSummarySynthesizer`

Stateless service. Single public method:

```java
public Optional< String > maybeOverlay( String authoredSummary, AgentHintsBlock derivedHints, boolean isHub );
```

Logic:

1. If `isHub == false` → `Optional.empty()`.
2. If `authoredSummary` does not match the generic pattern `(?i)^\s*(an?\s+)?index of (pages?|articles?|content)\s+(on|about|covering|for)\b` → `Optional.empty()`.
3. If `derivedHints.prefer_pages()` is empty → `Optional.empty()` (no signal to synthesize from).
4. Else build: `"Cluster hub. Highest-signal pages: " + prefer_pages[0..min(3,size)].map(p -> p.title()).join(", ") + "."` and return as `Optional`.

The caller (`DefaultForAgentProjectionService`) replaces the `summary` field with the overlay value when present and sets `summarySynthesized = true`.

### `DefaultForAgentProjectionService` wiring

Existing flow (unchanged): summary, key facts, headings, recent changes, MCP tool hints, verification — each in its own try/catch with `missing_fields` accumulation.

New steps appended:

```java
// 1) Derive agent_hints
AgentHintsBlock hints = AgentHintsBlock.empty();
try {
    hints = agentHintsDeriver.derive( canonicalId );
} catch ( Exception ex ) {
    LOG.warn( "agent_hints derivation failed for {}: {}", canonicalId, ex.getMessage() );
    missingFields.add( "agent_hints" );
    degraded = true;
    hints = null;  // surfaces as JSON null
}

// 2) Maybe overlay hub summary
boolean summarySynthesized = false;
if ( hints != null ) {
    Optional< String > overlay = hubSummarySynthesizer.maybeOverlay( summary, hints, isHub );
    if ( overlay.isPresent() ) {
        summary = overlay.get();
        summarySynthesized = true;
    }
}
```

`isHub` is read from `StructuralIndex` (already a dependency).

### `read_pages` MCP tool

**Tool name:** `read_pages`
**Server:** `/knowledge-mcp`
**Authz:** existing per-page ACL helper used by `read_page` (delegated, no new code path).

#### Input schema

```json
{
  "type": "object",
  "properties": {
    "slugs": {
      "type": "array",
      "items": { "type": "string" },
      "minItems": 1,
      "maxItems": 20
    }
  },
  "required": ["slugs"]
}
```

A request with `slugs.length > 20` returns an MCP error (`invalid_params`) with a clear message — do NOT silently truncate. Worked example annotation per the Phase 6 examples convention (matches the per-property example pattern; outputSchema gets a top-level `examples` array).

#### Output schema

```json
{
  "type": "object",
  "properties": {
    "pages": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "slug":    { "type": "string" },
          "content": { "type": ["string", "null"] },
          "error":   { "type": ["string", "null"] }
        },
        "required": ["slug"]
      }
    }
  },
  "required": ["pages"]
}
```

Per-page semantics:

- Valid + authorised: `{slug, content: "<markdown>", error: null}`.
- Missing page: `{slug, content: null, error: "not_found"}`.
- ACL denied: `{slug, content: null, error: "forbidden"}`. (Do not leak existence — but `read_page` already chose its existence-vs-403 stance; this tool mirrors whatever `read_page` does today, do not diverge.)
- Internal error on one page: `{slug, content: null, error: "internal_error"}` and the failure is logged at `WARN`. Other slugs in the batch still process.

The whole call only fails (non-200) on input validation (cap exceeded, missing `slugs`). Per-page failures are *data*, not errors, so a partial-success batch returns 200.

**Slug vs canonical_id:** match the existing `read_page` parameter convention exactly. Verify in implementation; do not introduce a divergent identifier scheme.

### `AgentGradeAuditResource`

`GET /admin/agent-grade-audit?limit=50&offset=0`

Response:

```json
{
  "total": 117,
  "limit": 50,
  "offset": 0,
  "pages": [
    {
      "canonical_id": "warehouse_automation_hub",
      "title": "Warehouse Automation Hub",
      "cluster": "warehouse-automation",
      "weaknesses": ["generic_hub_summary", "no_verified_at"]
    }
  ]
}
```

#### Weakness flags

| Flag                       | Detection                                                                                                                            |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `no_cluster`               | Frontmatter `cluster:` absent or empty.                                                                                              |
| `no_inbound_cluster_links` | Page graph reports zero inbound wikilinks from same-cluster pages. (Excludes hubs — hubs are *expected* to have outbound > inbound.) |
| `generic_hub_summary`      | `is_hub == true` AND `summary` matches the generic regex used by `HubSummarySynthesizer`.                                            |
| `no_verified_at`           | `page_verification.verified_at` is null.                                                                                             |
| `stale_verification`       | `ConfidenceComputer.compute(...)` returns `STALE`.                                                                                   |

Pages with zero flags are not returned. Sort: descending by flag count, then by `canonical_id` for determinism.

Pagination: `limit` clamped to `[1, 200]`, default 50; `offset` clamped to `[0, ∞)`, default 0. `total` is the pre-pagination count.

Auth: behind `AdminAuthFilter` (`AllPermission` required) — same as every other `/admin/*` endpoint.

## Caching

Reuses the existing `wikantik.forAgentCache` (1h TTL, 5K entries) keyed by `(canonical_id, updated_at_millis)`.

**Acknowledged staleness window:** when a *cluster-mate* of page P is created, renamed, deleted, or reverified, P's cached projection still reflects the pre-change state until either P itself is touched (cache key changes) or the 1h TTL elapses. This is acceptable for v1: agents fetching `prefer_pages` get eventually-consistent recommendations, never broken ones (the deriver re-resolves canonical_ids → titles fresh on cache miss).

If staleness becomes a real complaint post-launch, the upgrade is to invalidate all P's cluster's cache entries on any cluster-member change — a follow-up, not in this spec.

The audit endpoint does NOT use the projection cache (it queries the structural index + verification table directly so operators see live state). Limit/offset bounds keep response size bounded; no additional cache needed.

## Edge Cases

| Case                                                | Behaviour                                                                                           |
| --------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| Page has no cluster                                 | `prefer_pages: []`. `prefer_tools` derived from page body alone. No degradation flag.               |
| Cluster has only one page (the current page)        | `prefer_pages: []`. No degradation flag.                                                            |
| Cluster hub IS the current page                     | Skip hub entry; `prefer_pages` = top 5 cluster-mates.                                               |
| `McpToolHintsResolver` throws on current page       | `prefer_tools` derived from hub only (or empty). Whole block still returns. No degradation.         |
| `McpToolHintsResolver` throws on hub                | `prefer_tools` derived from current page only.                                                      |
| `PageGraphService` throws                           | `prefer_pages` empty; `prefer_tools` still derived. No degradation.                                 |
| `PageVerificationService` throws on a candidate     | Treat as `confidence = null`, score with 1.0 multiplier. Continue.                                  |
| `PageManager.getTitle` returns null for a candidate | Skip that candidate (do not emit a `PreferredPage` with null title).                                |
| Whole `AgentHintsDeriver.derive(...)` throws        | Caller catches → `agent_hints: null`, `missing_fields += ["agent_hints"]`, `degraded = true`.       |
| Hub has no authored summary                         | `HubSummarySynthesizer` does not fire (regex doesn't match empty/null).                             |
| Hub summary is rich (non-generic)                   | Synthesizer does not fire. `summarySynthesized: false`.                                             |
| Hub matches regex but `prefer_pages` empty          | Synthesizer does not fire — no signal to synthesise from, leave authored summary intact.            |
| `read_pages` called with empty slug list            | MCP `invalid_params` (covered by `minItems: 1`).                                                    |
| `read_pages` called with 21+ slugs                  | MCP `invalid_params` with explicit cap message.                                                     |
| `read_pages` slug duplicated within batch           | Process each occurrence (or dedupe — implementation may pick; deduping is harmless and saves work). |
| Audit endpoint with no weak pages in corpus         | `{total: 0, pages: []}`, 200 OK.                                                                    |

## Observability

- Existing `wikantik_for_agent_response_bytes` Prometheus histogram automatically captures any size delta from the new field.
- New counter: `wikantik_agent_hints_derivation_failures_total` (incremented on the whole-block try/catch path).
- New counter: `wikantik_hub_summary_synthesis_total` (incremented every time the overlay fires — gives operators visibility into how many hub pages have generic summaries).
- New counter: `wikantik_read_pages_partial_failures_total` labelled `{reason="not_found"|"forbidden"|"internal_error"}`.
- Audit endpoint and `read_pages` tool emit existing access-log lines; no new logging configuration needed.

## Testing Strategy

### Unit (JUnit 5, in `wikantik-main` and `wikantik-rest` and `wikantik-knowledge`)

- `AgentHintsDeriverTest` — table-driven over fixture clusters covering: empty cluster, cluster with hub only, cluster with mixed verified/unverified, current page = hub, current page with no cluster, exception in each dependency.
- `HubSummarySynthesizerTest` — table-driven over: regex matches/non-matches (case variants, leading whitespace), empty `prefer_pages`, hub flag false.
- `ReadPagesToolTest` — Mockito; covers happy path, cap-exceeded, partial failure (not_found / forbidden / internal_error mix), empty list rejection, dedupe behaviour.
- `AgentGradeAuditResourceTest` — covers each weakness flag in isolation, pages with multiple flags, sort determinism, pagination bounds.

### Wire-level (existing test classes extended)

- `PageForAgentResourceTest` — assert new `agent_hints` field shape and `summarySynthesized` flag presence; assert graceful degradation when deriver fails (mocked).
- `GetPageForAgentToolTest` — same assertions on the MCP tool's wire response.

### Integration (Cargo-launched Tomcat against PostgreSQL + pgvector, in `wikantik-it-tests`)

Single new IT class: `DerivedAgentHintsAndBatchReadIT` covering:

1. Seed fixture pages forming a small cluster; project current page; assert `agent_hints` populated with expected `prefer_pages` order and `prefer_tools` ranked correctly.
2. Project a hub page with a generic authored summary; assert synthesised overlay fires and `summarySynthesized = true`.
3. Call `read_pages` with three slugs (one valid, one missing, one ACL-denied); assert per-page error fields and 200 status.
4. Call `read_pages` with 21 slugs; assert MCP `invalid_params`.
5. Hit `/admin/agent-grade-audit` as admin; assert at least one fixture page surfaces the expected weakness flags.

Runs inside the standard `mvn clean install -Pintegration-tests -fae` reactor; no parallelism (per CLAUDE.md's explicit ban on `-T` for ITs).

### Pre-commit verification

Per project memory: full IT reactor before committing prod-code changes. Targeted `-Dtest=` runs miss cross-module breakage.

## Documentation Updates

Pre-public-release tuning — the design is exhaustive, and every public-facing reference to tool counts, projection fields, or the agent-grade content design must be reconciled. Below is the complete list with the specific change.

### `CLAUDE.md`

1. **Line 292** (`wikantik-knowledge` module description): bump tool count `15 read-only tools` → new count (verify actual count via `KnowledgeMcpInitializer` registry; should be N+1 where N is the current count). Append `read_pages` to the parenthetical capability list.
2. **Line 306** (Agent-facing surface summary table, `/knowledge-mcp` row): bump tool count to match.
3. **Line 384** (`/for-agent projection is in.` paragraph): add a sentence describing the new `agent_hints` field — what it carries, that it is *derived*, that the hub-summary overlay can replace `summary` and is signalled by `summarySynthesized`. Keep the URL deviation note intact.
4. **Line 390** (`Tool-description examples are in.` paragraph): RECONCILE existing discrepancy. This line says `/knowledge-mcp (16)` while lines 292 and 306 say `15`. Pick the actual current count (verify via registry), reconcile all three lines, then bump by +1 for `read_pages`.
5. **Active Design Documents bullet list**: add a new bullet pointing at `docs/superpowers/specs/2026-05-10-derived-agent-hints-design.md` summarising the three deliverables. Mark this design's status accurately ("Implemented YYYY-MM-DD" once landed).

### `README.md`

1. **Line 37** (top-level `/knowledge-mcp` blurb): bump count `15 read-only tools` → new count. Append `read_pages` to the capability list.
2. **Line 102** (architecture diagram label): bump `15 read-only retrieval tools` → new count.
3. **Line 344** (module table row for `wikantik-knowledge`): bump count.
4. **Line 417** (`/knowledge-mcp` deep-dive paragraph): bump `Exposes **15 tools**` → new count. Add `read_pages` to the capability list. Add a sentence on the `agent_hints` projection field.

### `docs/wikantik-pages/AgentGradeContentDesign.md`

1. Add a new "Phase 7 — Derived agent hints and batch reads" section near the end (or before "Out of scope" if such a section exists), summarising:
   - The decision to derive vs. require authored hints, with the author-burden reasoning recorded so future contributors don't relitigate.
   - The three deliverables (derived `agent_hints`, hub summary overlay, `read_pages`) with one-line descriptions.
   - The audit endpoint as the operator surface that compensates for not requiring author input.
   - A pointer to this spec.
2. If the doc has a "Status" header, update it to indicate Phase 7 added. The doc's overall "design complete 2026-04-25" framing should change to "design complete 2026-04-25; Phase 7 tuning added 2026-05-10".

### `docs/wikantik-pages/PageGraphVsKnowledgeGraph.md`

1. Add a new consumer to the Page Graph consumers list: `AgentHintsDeriver` (centrality query for `prefer_pages` ranking). One sentence; link to this spec. This makes the page graph dependency discoverable when someone refactors the page graph schema.

### `docs/wikantik-pages/StructuralSpineDesign.md`

1. Add a new consumer to the structural-index consumers list: `AgentHintsDeriver` (cluster lookup, hub designation) and `AgentGradeAuditResource` (weakness scan over the cluster index).

### `MEMORY.md` and project memory file

1. Update `project_agent_grade_phase_2_next.md` (or rename to drop "phase_2") to add the Phase 7 deliverables. Reflect that the agent-grade content design is no longer "complete" but "extended with derived-hints tuning 2026-05-10".
2. If a memory file tracks live tool counts (`project_admin_mcp_tool_surface.md` per existing memory index), bump the `/knowledge-mcp` count there too.

### MCP tool description (in code, but documentation-shaped)

1. `read_pages` ships with a worked input/output example per the Phase 6 examples convention (`inputSchema.properties.<name>` carries per-property examples; `outputSchema` carries a top-level `examples` array — recall `JsonSchema` record can't carry top-level extras, so `outputSchema` is a free Map per the established pattern).
2. `get_page_for_agent` tool description gets a one-sentence addendum: "Response includes derived `agent_hints` (recommended tools and pages for this topic) and a `summarySynthesized` flag for hub overlays."

### Cross-cutting reconciliation note for the implementer

The current CLAUDE.md has a tool-count discrepancy (`15` vs `16` for `/knowledge-mcp`). Before bumping anything, verify the actual current count by inspecting `KnowledgeMcpInitializer` or running a one-shot diagnostic — do not propagate the discrepancy. Record the verified pre-change count in the implementation plan so reviewers can sanity-check the +1 arithmetic.

## Migration / Backwards Compatibility

Purely additive on every surface:

- `ForAgentProjection` gains two new fields. JSON consumers using lenient parsers (most) ignore unknown fields. Strict consumers (none currently external) would need an update — this is pre-public-release, so no real consumers exist.
- `read_pages` is a brand-new tool name; no collision risk.
- `/admin/agent-grade-audit` is a brand-new endpoint behind admin auth; no collision risk.
- No DB migrations.
- No frontmatter schema changes (the whole point of this design).

## Open Questions

None as of 2026-05-10. Brainstorm closed all decisions:

- Authored block vs derived → derived.
- `agent_hints` field set → `prefer_tools` + `prefer_pages` (not `key_data`).
- `prefer_pages` shape → `{canonical_id, title, role}` not bare canonical_id.
- Read flavour → A (raw markdown batch only).
- Cap → 20.
- Scope → option 3 (derived hints + audit + hub summary overlay, plus `read_pages`).

## Deferred (revisit post-launch)

- Embedded JSON Schema literals on methodology pages.
- `avoid_tools:` anti-recommendations.
- Surfacing `agent_hints` in `search_knowledge` results.
- Cluster-wide cache invalidation on any cluster-member change (instead of TTL-only staleness).
- A `note:` or `when:` string on `prefer_tools`/`prefer_pages` entries for richer agent context — only if usage shows agents struggle to choose between recommendations.
