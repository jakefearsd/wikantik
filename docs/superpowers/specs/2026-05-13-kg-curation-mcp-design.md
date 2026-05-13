# Knowledge Graph Curation on MCP — Design

**Date:** 2026-05-13
**Status:** Approved, awaiting implementation plan
**Surface:** `/wikantik-admin-mcp`
**Related:** [KgInclusionPolicy](../../wikantik-pages/KgInclusionPolicy.md), [AgentGradeContentDesign](../../wikantik-pages/AgentGradeContentDesign.md), `feedback_mcp_write_surface_pairing.md`

## Motivation

External curator agents (Gemini) currently fall through to the REST surface at
`/admin/knowledge-graph/*` because the MCP server exposes only two KG tools:
`list_proposals` (basic projection, no conflict flags) and `propose_knowledge`
(write). There is no MCP path to approve a proposal, reject a duplicate, confirm
an edge to human-curated, or merge two nodes — every curation action requires
the agent to leave the MCP protocol and call REST directly.

This design closes that gap so a curator agent can complete the full Knowledge
Graph triage loop entirely through `/wikantik-admin-mcp`.

## Goals

1. Curator agents can review, accept, reject, or judge proposals via MCP.
2. Curator agents can directly upsert, confirm, delete, and merge nodes/edges
   via MCP.
3. Conflict flags (`node_exists`, `edge_previously_rejected`) are surfaced on
   every read path so duplicate proposals are visible without a second roundtrip.
4. REST and MCP share a single service-level implementation — they cannot drift.

## Non-goals

- New auth tier or capability scope today. The 9b unified-API-key admin will
  layer `kg_curate` scope onto the same tool surface later.
- Operator-tier tooling on MCP (judge-timeout management, hub proposals,
  embedding rebuilds, sync-hub-memberships). These remain REST-only.
- Changes to the REST behavior or to the admin UI. The REST helpers move into
  `KnowledgeGraphService`, but the wire surface is unchanged.
- Per-op `McpAudit` rows. One audit row per bulk call matches the REST pattern.

## Tool surface

Four new tools plus one enriched tool. Registry count moves from 18 to 22.

### Cap on bulk operations

All curation verbs accept `1..N` items. **Cap = 50** per call (configurable via
property `wikantik.mcp.kg_curation.bulk_limit`, default `50`). Exceeding the cap
is a top-level error — the call is rejected, not silently truncated:

```json
{"error": "bulk limit exceeded: 73 > 50"}
```

### `list_proposals` (evolved)

Input schema unchanged. Output now includes two conflict flags per proposal,
computed from the existing REST helper extracted into
`ProposalConflictFlags`:

```json
{
  "proposals": [
    {
      "id": "...",
      "proposal_type": "new-edge",
      "source_page": "HybridRetrieval",
      "proposed_data": {"source": "HybridRetrieval", "target": "BM25", "relationship": "falls_back_to"},
      "confidence": 0.86,
      "status": "pending",
      "node_exists": false,
      "edge_previously_rejected": true,
      "created": "2026-04-24T11:22:33Z",
      "reviewed_by": null,
      "reviewed_at": null
    }
  ]
}
```

`node_exists` populates only for `new-node` proposals; `edge_previously_rejected`
only for `new-edge` proposals. Unused flags are omitted (not `null`) to keep
payloads tight. `list_proposals` surfaces only the boolean flags; deeper
context (the existing-entity id when a flag is `true`) is returned by
`inspect_proposals`.

### `inspect_proposals` (new)

Bulk deep-dive reader. Input: `{ids: [uuid, ...]}` (1..50). Output per id:
the full proposal record, conflict flags, prior reviews (the list currently
served at `GET /admin/knowledge-graph/proposals/{id}/reviews`), and a snapshot of
the currently-linked node/edge state if relevant.

```json
{
  "proposals": [
    {
      "id": "...",
      "proposal": { /* full KgProposal projection */ },
      "conflicts": {"node_exists": true, "existing_node_id": "..."},
      "prior_reviews": [{"reviewer": "...", "verdict": "rejected", "at": "...", "reason": "..."}],
      "linked_entity": {"kind": "node", "id": "...", "name": "...", "type": "..."}
    }
  ],
  "missing": ["<uuid that did not resolve>"]
}
```

IDs that don't resolve land in `missing[]` rather than triggering a top-level
error — consistent with the per-op failure semantics of the write verbs.

### `review_proposals` (new)

Mirrors `POST /admin/knowledge-graph/proposals/bulk-action`.

Input:

```json
{
  "verdict": "approve" | "reject" | "judge",
  "ids": ["uuid", ...],
  "reason": "<required iff verdict == reject>"
}
```

Output (matches existing REST bulk-action envelope verbatim):

```json
{
  "status": "completed",
  "succeeded": ["uuid", ...],
  "failed": [{"id": "uuid", "error": "..."}],
  "message": "12 of 15 proposals approved"
}
```

### `curate_edges` (new)

Heterogeneous bulk edge ops. Input: `{operations: [op, ...]}` (1..50). Each op
has a discriminator `action` plus action-specific fields and an optional
client-supplied `tag` (string) echoed in the response for correlation:

```json
{
  "operations": [
    {"action": "upsert", "tag": "edge-1", "source": "...", "target": "...", "relationship": "...", "weight": 0.9},
    {"action": "confirm", "tag": "edge-2", "id": "..."},
    {"action": "delete", "tag": "edge-3", "id": "..."},
    {"action": "delete_and_reject", "tag": "edge-4", "id": "...", "reason": "spurious co-mention"}
  ]
}
```

Output:

```json
{
  "status": "completed",
  "succeeded": [{"tag": "edge-1", "action": "upsert", "id": "<new-or-existing-edge-id>"}],
  "failed":    [{"tag": "edge-2", "action": "confirm", "id": "...", "error": "edge not found"}],
  "message": "3 of 4 edge operations applied"
}
```

`upsert` stamps `HUMAN_CURATED` (existing REST semantics). `delete_and_reject`
writes a rejection record so the edge cannot be re-proposed without explicit
agent review.

### `curate_nodes` (new)

Heterogeneous bulk node ops. Same envelope as `curate_edges`. Actions:
`upsert | delete | merge`.

```json
{
  "operations": [
    {"action": "upsert", "tag": "node-1", "name": "PaxosAndRaft", "node_type": "concept", "aliases": ["Paxos", "Raft"]},
    {"action": "delete", "tag": "node-2", "id": "..."},
    {"action": "merge",  "tag": "node-3", "source_id": "...", "target_id": "..."}
  ]
}
```

Merge with `source_id == target_id` returns a per-op error and does not abort
the call.

## Architecture and reuse

### Service-level extraction

The private helpers in `AdminKnowledgeResource`
(`tryApproveProposal`, `tryRejectProposal`, `tryJudgeProposal`) move onto
`KnowledgeGraphService` as public methods returning `Optional<String>` (empty on
success, error message on failure). Both REST and the new MCP tools route
through these methods — there is no duplicated curation logic.

Similarly, `tryUpsertEdge`, `tryConfirmEdge`, `tryDeleteEdge`,
`tryDeleteAndRejectEdge`, `tryUpsertNode`, `tryDeleteNode`, `tryMergeNodes` are
extracted to the service. The REST handlers shrink to argument parsing +
service call + envelope assembly.

### Conflict-flag helper

`AdminKnowledgeResource.java:1228-1235` computes the two conflict flags
inline. Extract into `ProposalConflictFlags` in `wikantik-knowledge`:

```java
public final class ProposalConflictFlags {
    public static Map<String, Object> forProposal(KnowledgeGraphService svc, KgProposal p);
}
```

Used by REST `handleGetProposals`, the evolved `ListProposalsTool`, and the new
`InspectProposalsTool`. Lock-step: a future flag added in one place is
automatically visible on the other.

### MCP tool wiring

Each new tool gets its own `McpTool` impl in
`wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/`:

- `InspectProposalsTool`
- `ReviewProposalsTool`
- `CurateEdgesTool`
- `CurateNodesTool`

`ListProposalsTool` is updated in place (no rename) since the input schema is
unchanged. All five are registered in `McpToolRegistry` inside the existing
`if (kgService != null)` guard that already protects `ListProposalsTool` /
`ProposeKnowledgeTool` — KG curation tools are not registered when the
knowledge service is disabled.

Per the `AgentGradeContentDesign` worked-example convention, each tool ships
with at least one input example per property and at least one top-level
`outputSchema.examples` entry. Discriminator-shape tools (`curate_edges`,
`curate_nodes`, `review_proposals`) ship one top-level example per
`action`/`verdict` value.

## Auth and trust posture

No new tier today. A valid `/wikantik-admin-mcp` API key is already trusted
with `write_pages`, `delete_pages`, `rename_page`, `mark_page_verified`.
Curation writes sit on the same trust line.

Each new tool registration carries a `// future: gated by kg_curate scope`
comment so the 9b unified API-key admin (see `project_api_key_admin_9b.md`)
knows where to attach the scope check when scoped keys land. No runtime
gating code is added today.

## Auditing

One `McpAudit` row per bulk call:

```
tool=<tool_name> actor=<actor> attempted=<n> succeeded=<k> failed=<n-k>
```

Per-op failures are visible in the response payload, not in audit rows
(matches REST behavior at `AdminKnowledgeResource.java:621-622`). Audit rows
never include per-op bodies — payload size and PII risk both argue for
counts-only.

## Edge cases

| Case | Behavior |
|------|----------|
| Source page on `kg_excluded_pages` | Approval succeeds. Response includes `warnings: ["source_page is in kg_excluded_pages list"]`. Exclusion list governs extraction, not retroactive curation. |
| Already-reviewed proposal | Per-op `error: "proposal already reviewed: status=approved"`. No idempotent re-approve. |
| `verdict=judge` failure | Per-op error surfaces; proposal status unchanged. |
| Bulk cap exceeded | Top-level error, call rejected. Caller must split. |
| `curate_edges.upsert` on existing edge | Stamps `HUMAN_CURATED`; succeeded entry echoes existing edge id. |
| `curate_nodes.merge` with `source_id == target_id` | Per-op error. Other ops continue. |
| Invalid UUID in any id field | Per-op `error: "Invalid UUID: <value>"`. |
| Cap value of 0 or negative via config | Treated as "use default 50"; warning logged at startup. |

## Testing

Per `feedback_mcp_write_surface_pairing.md`, every new write tool ships with
both layers:

**Mockito unit tests** covering, per tool:
- Happy path (single op and N ops).
- Cap exceeded → top-level error.
- Per-op failure mix (some succeed, some fail).
- Missing required field (e.g. `verdict`, `reason` when verdict=reject, `ids`).
- Invalid UUID in id field.
- Mid-batch service exception caught and surfaced as a per-op failure without
  aborting subsequent ops.

**Wire-level Cargo IT** in `wikantik-it-tests`:
- Drives the JSON-RPC contract end-to-end against a Cargo-launched Tomcat.
- Asserts response envelope shape matches the spec exactly.
- Asserts an `McpAudit` row was written with the expected `attempted/succeeded/failed` counts.
- Asserts per-op error messages cite the reason explicitly
  (`"proposal already reviewed: status=..."`, `"node_exists=true: would create duplicate"`,
  `"Invalid UUID: ..."`).

**Enriched `list_proposals`** gets a unit test asserting both conflict flags
appear correctly on a `new-node` and a `new-edge` fixture.

ITs run sequentially per CLAUDE.md (`mvn clean install -Pintegration-tests -fae`,
never `-T`).

## Configuration

| Property | Default | Notes |
|----------|---------|-------|
| `wikantik.mcp.kg_curation.bulk_limit` | `50` | Max ops per bulk call. Values ≤ 0 fall back to default with a startup warning. |

## Documentation updates

- `CLAUDE.md`: bump `/wikantik-admin-mcp` tool count from 18 → 22 in the
  agent-facing surface table.
- `KgInclusionPolicy.md`: cross-link the curation tools as the preferred
  agent path for triaging proposals.
- `project_admin_mcp_tool_surface.md` memory: refresh counts after merge.

## Rollout

Single PR. No schema change. No data migration. Backwards-compatible: existing
clients of `list_proposals` continue to work (new fields are additive). REST
behavior unchanged.

## Open questions deferred to follow-ups

1. **Node/edge audit-trail readers on MCP.** Useful for curator agents
   reconstructing why an edge was rejected. Deferred — Gemini did not need
   them to complete the curation loop.
2. **Pagination on `inspect_proposals`.** Capped at 50 today; if curators
   regularly need to deep-inspect more, switch to a cursor model.
3. **`kg_curate` scope gating.** Wires in when 9b ships.
4. **Per-op `McpAudit` rows.** If forensic analysis of large bulk calls
   becomes a need, revisit. Today the response payload is the audit-of-record
   for per-op outcomes.
