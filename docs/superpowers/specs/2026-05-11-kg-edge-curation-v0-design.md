# Knowledge Graph Edge Curation v0 — Design

**Status:** Approved (brainstorm 2026-05-11)
**Author:** Jake Fear / Claude (Opus 4.7)
**Targets:** first visible public release — closes the read-only Edge Explorer gap
**Sibling docs:** [PageGraphVsKnowledgeGraph.md](../wikantik-pages/PageGraphVsKnowledgeGraph.md), [2026-04-27-kg-inclusion-policy-design.md](2026-04-27-kg-inclusion-policy-design.md), [2026-05-10-derived-agent-hints-design.md](2026-05-10-derived-agent-hints-design.md)

## Motivation

The admin Edge Explorer (`wikantik-frontend/src/components/admin/EdgeExplorer.jsx`) is read-only. Admins can browse and filter the `kg_edges` table but cannot create, edit, or delete edges. The mutation surface already exists in the backend (`AdminKnowledgeResource` exposes `POST /admin/knowledge-graph/edges` upsert and `DELETE /admin/knowledge-graph/edges/{id}`) and in the JS client (`api.knowledge.upsertEdge`, `api.knowledge.deleteEdge`) — neither is wired into a UI.

That gap is unacceptable for a release that puts the Knowledge Graph in front of external users. A bad edge today requires either a `psql` session or a re-extraction run with a tweaked policy — both off-limits to anyone who is not the operator.

Curation needs to be safe against re-extraction clobber: a manual delete that the LLM re-proposes on the next run is no curation at all. The `kg_rejections` table (already populated by the proposal review queue) provides the right primitive — this design extends manual deletes to optionally write a rejection row in the same transaction, closing the loop.

## Non-Goals

- **Endpoint swap on an existing edge** (changing `source_id` or `target_id` in place). Use delete + create. Avoids a UNIQUE-constraint-aware swap path and the audit semantics of "is this the same edge or a different one."
- **Bulk relationship-type rewrite.** Bulk delete only in v0.
- **Audit revert / undo UI.** v0 logs every mutation but the History pane is read-only. Iteration 2.
- **Tier override from the UI.** All UI writes stamp `tier='human'`. No machine ↔ human flip control.
- **Node-side audit parity.** Node delete already exists without an audit trail; that is a separate gap not closed here.
- **Permissioning beyond the existing `AllPermission` admin gate.** Curation reuses `AdminAuthFilter`.
- **Edge merge.** No analog to node merge; edges are cheap to delete + recreate.

## Architecture Overview

Five operations, all surfaced in the existing Edge Explorer admin tab:

1. **Create edge** — modal with source/target name autocomplete + relationship-type dropdown (closed 20-type vocabulary from `V027`) + optional properties JSON.
2. **Edit edge** — `relationship_type` and `properties` only. Source/target are not editable.
3. **Delete edge** — confirm modal in the detail panel.
4. **Delete + prevent re-proposal** — second delete option that also writes a `kg_rejections` row in the same transaction. Reuses the existing rejection table that gates proposal acceptance, so a re-extracted triple will not re-materialise.
5. **Bulk delete by current filter** — single button next to pagination ("Delete N filtered edges"), gated by an optimistic-concurrency check on the expected count.

Plus one cross-cutting surface:

6. **`kg_edge_audit` table + `GET …/edges/{id}/audit` endpoint** — every UI mutation writes an audit row; the detail pane shows a collapsible History list.

### Components

| Component | Module | Purpose |
| --- | --- | --- |
| `kg_edge_audit` table | DB migration `V0xx__kg_edge_audit.sql` | Append-only audit of every UI-driven edge mutation. Columns: `id UUID PK`, `edge_id UUID` (no FK — edge may be gone), `action VARCHAR(10) CHECK (action IN ('CREATE','UPDATE','DELETE'))`, `before JSONB NULL`, `after JSONB NULL`, `actor VARCHAR(100)`, `reason TEXT NULL`, `created TIMESTAMP DEFAULT NOW()`. Indexed on `(edge_id, created DESC)`. |
| `KgEdgeAuditRepository` | `wikantik-main` (`com.wikantik.knowledge`) | INSERT one row; `findByEdgeId(UUID, int limit)` for the history pane. No DELETE/UPDATE — append-only. |
| `AdminKnowledgeResource` — new endpoint `POST /admin/knowledge-graph/edges/{id}/delete-and-reject` | `wikantik-rest` | DELETE + INSERT INTO `kg_rejections` in one transaction. Body: `{"reason": "..."}`. Writes a `DELETE` audit row with `reason` populated and a marker `{"rejected": true}` in `after`. |
| `AdminKnowledgeResource` — new endpoint `POST /admin/knowledge-graph/edges/bulk-delete` | `wikantik-rest` | Body: `{"relationship_type": "...", "search": "...", "expected_count": N}`. Re-runs the same filter query, fails 409 if the row count differs from `expected_count` (snapshot drift), otherwise deletes all matched rows and writes one audit row per edge. |
| `AdminKnowledgeResource` — new endpoint `GET /admin/knowledge-graph/edges/{id}/audit` | `wikantik-rest` | Returns the most recent N (default 20) audit rows for an edge id, newest first. |
| `AdminKnowledgeResource` — existing `POST /edges` (upsert) | `wikantik-rest` | Behavior change: server unconditionally stamps `provenance='human-curated'`, `tier='human'`, `provenance_proposal_id=NULL` on this endpoint, ignoring any conflicting values in the request body. (The endpoint is admin-only via `AdminAuthFilter`; the proposal pipeline writes through `KgMaterializationService`, not here.) Writes a `CREATE` or `UPDATE` audit row depending on whether the (source, target, type) triple existed. |
| `AdminKnowledgeResource` — existing `GET /edges` (queryEdges) | `wikantik-rest` | Response shape extended with a `total` integer carrying the unpaginated row count for the current filter, so the UI can render "Delete filtered (N)" without a separate count round-trip. Existing `edges` array unchanged. |
| `AdminKnowledgeResource` — existing `DELETE /edges/{id}` | `wikantik-rest` | Behavior change: writes a `DELETE` audit row. No rejection insert (that is the explicit delete-and-reject path). |
| `EdgeFormModal.jsx` | `wikantik-frontend` | Shared between Create and Edit. Validates `relationship_type` against the schema (already fetched on Edge Explorer mount), parses `properties` JSON, surfaces server-side 409 on UNIQUE collision as inline "this triple already exists". |
| `EdgeExplorer.jsx` | `wikantik-frontend` | Grows: "New edge" button above list, "Delete filtered (N)" button next to pagination, Edit / Delete / Delete + Prevent buttons in `EdgeDetail`, collapsible "History" section in `EdgeDetail`. |
| `api.knowledge.deleteAndRejectEdge`, `bulkDeleteEdges`, `getEdgeAudit` | `wikantik-frontend/src/api/client.js` | New client wrappers for the three new endpoints. `upsertEdge` and `deleteEdge` already exist. |

### Data flow

#### Create edge

1. User clicks "New edge". `EdgeFormModal` opens with empty fields.
2. User types source name → autocomplete hits `GET /admin/knowledge-graph/nodes?name=...&limit=10`. Same for target. Selection captures the node id.
3. User picks `relationship_type` from a dropdown populated by the schema fetched on mount.
4. Optional properties: JSON textarea accepting any JSON object (matches the schema's unconstrained `properties JSONB`); parsed client-side, invalid JSON disables Save.
5. Submit → `POST /admin/knowledge-graph/edges` with `{source_id, target_id, relationship_type, properties}`. Server stamps `provenance='human-curated'`, `tier='human'`. Writes `CREATE` audit row.
6. 409 on UNIQUE collision → inline error "this triple already exists"; modal stays open.
7. Success → close modal, refresh current page of edges, select the new edge.

#### Edit edge

1. User clicks Edit in `EdgeDetail`. `EdgeFormModal` opens prefilled. Source/target fields are disabled.
2. Same validation as Create. Submit → `POST /admin/knowledge-graph/edges` with the existing `id` plus new `relationship_type` / `properties`.
3. Server detects existing id, runs UPDATE, writes `UPDATE` audit row with before/after JSONB.
4. Success → close modal, refresh detail pane, keep selection on the updated edge.

#### Delete edge

1. User clicks Delete in `EdgeDetail` → confirm modal "Delete this edge? Re-extraction may re-propose it."
2. Confirm → `DELETE /admin/knowledge-graph/edges/{id}`. Server writes `DELETE` audit row.
3. Success → refresh page of edges, clear selection.

#### Delete + prevent re-proposal

1. User clicks "Delete + Prevent" → confirm modal asks for a `reason` (optional, free text).
2. Confirm → `POST /admin/knowledge-graph/edges/{id}/delete-and-reject` with `{reason}`.
3. Server, in one transaction: looks up the edge's source/target node names, DELETEs the edge, INSERTs into `kg_rejections` with `(proposed_source, proposed_target, proposed_relationship, rejected_by=actor, reason)`. The existing UNIQUE on `kg_rejections` makes this idempotent — if the same triple is already rejected, the INSERT does `ON CONFLICT DO NOTHING`.
4. Audit row records `before=edge_json`, `after={"rejected": true}`, `reason=...`.
5. Success → same UX as plain Delete.

#### Bulk delete

1. With a filter set, user clicks "Delete filtered (N)" next to pagination. N comes from the new `total` field on the `GET /edges` response, so the count reflects every matching row, not just the current page.
2. Confirm modal requires typing the count to enable Delete.
3. Submit → `POST /admin/knowledge-graph/edges/bulk-delete` with `{relationship_type, search, expected_count: N}`.
4. Server re-runs the filter, fails 409 if the actual count != `expected_count`, otherwise streams the matched ids, DELETEs each, writes one `DELETE` audit row per edge with `reason="bulk delete via filter: relationship_type=X, search=Y"`. Wrapped in one transaction.
5. Success → refresh, clear selection. Returned body `{deleted: N}` shown as a brief banner.

### Provenance and tier semantics

All UI-driven writes stamp:

- `provenance='human-curated'` (distinct from the schema default `'human-authored'` used by hub-page-save derived edges — preserves analytical ability to tell admin-curated rows from page-body-derived rows)
- `tier='human'`
- `provenance_proposal_id=NULL`

The `human` tier (from `V024`) is the existing protection mechanism. `KgMaterializationService` must respect it on re-extraction runs — pre-merge work item: verify and add an explicit unit test if not already covered. (Implementation-plan-level concern; called out here to keep it visible.)

### Error handling

- **Invalid relationship type** (not in the V027 vocabulary): server returns 400 with `{"error": "relationship_type 'X' is not in the allowed vocabulary"}`. Client surfaces inline on the dropdown.
- **Triple already exists** (UNIQUE violation on `(source_id, target_id, relationship_type)`): server returns 409. Client surfaces inline on submit.
- **Invalid properties JSON**: server returns 400 with the parser error message. Client also pre-validates and disables Save until parseable.
- **Node not found** (source_id or target_id refers to nonexistent node): server returns 404 with which endpoint is missing. Client surfaces inline on the autocomplete field.
- **Bulk count drift**: 409 with `{"error": "expected N rows, found M — re-confirm before retrying"}`. Client refreshes the count and re-prompts.
- **Audit write failure**: must not block the primary mutation. Wrap audit insert in try/catch, `LOG.warn` with edge id and exception, continue. The audit table is a fidelity surface, not a correctness surface.

### Audit row contents

| Action | `before` | `after` |
| --- | --- | --- |
| CREATE | `null` | full edge JSON (id, source_id, target_id, relationship_type, properties, provenance, tier) |
| UPDATE | full edge JSON pre-change | full edge JSON post-change |
| DELETE | full edge JSON pre-delete | `null` (or `{"rejected": true}` if delete-and-reject path) |

`actor` is the logged-in admin's login name from `WikiContext`. `reason` is user-supplied free text where applicable (delete-and-reject mandates it; bulk-delete server-generates; create/edit leave it null in v0).

## Testing

| Layer | Coverage |
| --- | --- |
| `KgEdgeAuditRepository` unit tests | Insert round-trip, findByEdgeId ordering (newest first), limit enforcement. |
| `AdminKnowledgeResource` unit tests | Provenance/tier stamping on UI upsert path. 400 on bad relationship type. 409 on UNIQUE collision. 409 on bulk count drift. delete-and-reject writes both `kg_edges` DELETE and `kg_rejections` INSERT in one transaction (rollback on either failure). Audit failure does not block the mutation. |
| `AdminKnowledgeResource` integration test (wikantik-it-tests/wikantik-it-test-rest) | Wire-level: each new endpoint with a real PostgreSQL backend, asserting both the kg_* table state and the kg_edge_audit row. |
| `EdgeFormModal` Vitest | Relationship-type dropdown population, properties JSON parse, autocomplete error surfaces. |
| `EdgeExplorer` Vitest | Buttons appear and call the right client method with the right args. Bulk-delete confirm requires typed count. |
| Selenide IT (wikantik-selenide-tests) | One happy-path browser test: create → edit → delete-and-reject → verify rejection blocks re-creation. Gives us the "release-blocker" end-to-end signal. |
| KG materialization protection test | New unit test confirming that re-running materialization against a proposal whose triple already exists at `tier='human'` does not overwrite. (Pre-existing behavior assumed but not always covered; gap closed here.) |

All wikantik-main tests run as part of the normal `mvn clean install` reactor. The Selenide IT runs under `-Pintegration-tests`.

## Migration

Single new migration `V0xx__kg_edge_audit.sql` (next available number at implementation time):

```sql
CREATE TABLE IF NOT EXISTS kg_edge_audit (
    id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    edge_id   UUID         NOT NULL,
    action    VARCHAR(10)  NOT NULL CHECK (action IN ('CREATE','UPDATE','DELETE')),
    before    JSONB,
    after     JSONB,
    actor     VARCHAR(100) NOT NULL,
    reason    TEXT,
    created   TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_kg_edge_audit_edge_created ON kg_edge_audit (edge_id, created DESC);
GRANT SELECT, INSERT ON kg_edge_audit TO :app_user;
```

Idempotent; safe to re-run. No data backfill (per the project rule against backfills in versioned migrations — audit starts empty, which is correct).

## Observability

- New Prometheus counter `wikantik_kg_edge_curation_total{action=create|update|delete|delete_and_reject|bulk_delete}` incremented on each successful mutation.
- Existing request-log correlation IDs already cover the new endpoints (inherited from `RestServletBase`).
- No new dashboards required for v0. Iteration 2 may add a "curation activity" admin tile.

## Rollout

Single PR / single deploy:

1. Migration applies (`bin/db/migrate.sh` on `deploy-local.sh`).
2. New endpoints become live; client picks them up the next page load.
3. No feature flag — release-blocker, intended to be on from day one.

If we discover post-merge that we need to disable the new mutations urgently, the rollback path is `AdminAuthFilter` already gates the entire `/admin/*` surface — we can short-circuit the new endpoints in a one-line config without reverting code.

## Open Implementation-Plan-Level Questions

These get answered in the implementation plan, not here:

- Exact form of source/target autocomplete on `EdgeFormModal` (debounce ms, max suggestions). Mirror the existing pattern in `NodeDetail` if there is one.
- Whether to add a "match count" indicator to Edge Explorer's filter bar so the bulk-delete button's count is visible without clicking. Strongly preferred but not strictly required for v0.
- Exact migration number — depends on what has landed by implementation time.

## Out of v0, Tracked for Follow-Up

- **Iteration 2:** Revert button on audit history rows. Endpoint `POST /admin/knowledge-graph/edges/audit/{audit_id}/revert` that applies the inverse of the recorded action.
- **Iteration 2:** Node-side audit parity. Same pattern, separate spec because node merge complicates the audit shape.
- **Iteration 2:** Bulk relationship-type rewrite. Likely a script-style "Find and replace" panel rather than inline UI.
- **Iteration 2:** Tier override toggle. Only valuable if operators want to deliberately demote a curated edge back to `machine` to allow re-extraction. No demand for this yet.
