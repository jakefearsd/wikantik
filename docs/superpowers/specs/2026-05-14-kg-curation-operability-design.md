# KG Curation Operability — Design

**Date:** 2026-05-14
**Status:** Approved, awaiting implementation plan
**Surface:** REST `/admin/knowledge-graph/*`, MCP `/wikantik-admin-mcp` curation tools, MCP `/knowledge-mcp` query tools, and the underlying `KnowledgeGraphService` + `KgNodeRepository`
**Related:** [KG Curation on MCP Design](2026-05-13-kg-curation-mcp-design.md), [KG Inclusion Policy](2026-04-27-kg-inclusion-policy-design.md), `feedback_no_data_backfill_in_migrations`

## Motivation

External evaluation of the KG curation surface (Gemini, 2026-05-14) confirmed
four operability defects that make admin work feel hostile:

1. **Visibility black hole** — `KgInclusionFilter` runs on `query_nodes`,
   `search_knowledge`, and `/admin/knowledge-graph/nodes` reads. New pages get
   added to `kg_excluded_pages` by `DefaultKgInclusionPolicy`'s default-exclude
   rule, so admin-created nodes disappear immediately from the standard query
   tools that curators use to verify their work.
2. **Ghost merges** — `mergeNodes(sourceId, targetId)` returns success even
   when `sourceId` references a non-existent node, because the underlying SQL
   is `DELETE + UPDATE` and 0 affected rows looks like success.
3. **Schema rot** — `upsertNode` accepts any `node_type` string, so a single
   typo (`"concept,"`) or empty-string default pollutes `discover_schema`
   results permanently.
4. **Parameter fragility** — `curate_nodes` upsert requires a flat operation
   shape (`{action, name, node_type, source_page}`); agents that nest data
   under `{node: {...}}` get an unhelpful "name is required" error rather than
   guidance to the correct shape.

Live corpus inspection on 2026-05-14 found existing pollution from issue #3:

| node_type | count | classification |
|-----------|------:|---------------|
| `concept` | 1190 | legit |
| `article` | 868 | legit |
| (empty string) | 71 | pollution |
| `hub` | 21 | legit |
| `reference` | 2 | legit |
| `concept,` | 2 | pollution (comma typo) |
| `implementation-plan` | 1 | legit (hyphen) |
| `intelligence-summary` | 1 | legit (hyphen) |
| `Product` | 1 | pollution (uppercase first) |
| `not_a_valid_type_hopefully` | 1 | pollution (test injection) |

The pollution is real and small enough to clean by hand in one operator pass.

## Goals

1. **An admin who creates a node can immediately read it back via the same
   tools they use for triage** — no waiting for the inclusion-policy reconciler
   to allow the page.
2. **`curate_nodes.merge` refuses to silently no-op on a missing source or
   target UUID** — the response surfaces the per-op error so the operator
   knows the merge didn't happen.
3. **`upsertNode` rejects node_type strings that don't match a clean vocabulary
   pattern** — typos and empty strings stop at the service boundary; the
   global schema stays clean going forward.
4. **`curate_nodes.upsert` returns a guiding error when the op is shaped with
   nested `{node: {...}}` instead of flat fields** — saves agents a debugging
   round-trip.
5. **The existing pollution from #3 is cleaned up via a one-shot operator
   script** — never as a versioned migration (per `feedback_no_data_backfill_in_migrations`).

## Non-goals

- No new auth tier. Admin status is already gated by `AdminAuthFilter`
  (REST `AllPermission`) and the MCP access filter (valid API key on
  `/wikantik-admin-mcp`).
- No schema migration. Regex enforcement at the service boundary makes future
  writes clean; existing pollution is handled by the operator script.
- No change to the extraction-time inclusion policy. The default-exclude rule
  for new pages stays — it is correct for non-admin retrieval consumers.
- No `ReadContext` value object. Overloads keep the interface tight; YAGNI for
  one flag.
- No "scoped key" gating of curation operations. That belongs to the 9b
  unified-API-key admin work.

## Fix 1: Admin-bypass on read paths

The `KgInclusionFilter` provides SQL fragments that filter `kg_nodes`,
`kg_edges`, and `chunk_entity_mentions` against `kg_excluded_pages`. The
fragments are spliced into `KgNodeRepository.queryNodes / searchNodes` and
into the hybrid retrieval SQL.

**Change:** add overloaded `*WithBypass(...)` methods that skip the filter
fragments, and wire them into admin call sites.

### `KgInclusionFilter`

Add four `static String` constants alongside the existing four:

```java
/** Empty fragment — replaces NODE_FILTER_JOIN when admin bypass is on. */
public static final String NODE_FILTER_JOIN_BYPASS = "";
/** "TRUE" fragment — replaces NODE_FILTER_WHERE when admin bypass is on. */
public static final String NODE_FILTER_WHERE_BYPASS = " TRUE ";
```

Plus an accessor that returns the appropriate pair:

```java
public static String nodeFilterJoin( boolean adminBypass ) {
    return adminBypass ? NODE_FILTER_JOIN_BYPASS : NODE_FILTER_JOIN;
}
public static String nodeFilterWhere( boolean adminBypass ) {
    return adminBypass ? NODE_FILTER_WHERE_BYPASS : NODE_FILTER_WHERE;
}
```

(Repeat for `EDGE_*` and `MENTION_*`.)

Existing static-constant call sites are unchanged. New call sites use the
accessor.

### `KgNodeRepository`

Existing public methods (`queryNodes`, `searchNodes`, `getNode`,
`getNodeByName`) keep their signatures and current filter-on behaviour. Add
new overloads taking a trailing `boolean adminBypass`:

```java
public List< KgNode > queryNodes( Map<String,Object> filters, Set<Provenance> prov,
                                   int limit, int offset, boolean adminBypass );
public List< KgNode > searchNodes( String query, Set<Provenance> prov,
                                    int limit, boolean adminBypass );
public KgNode getNode( UUID id, boolean adminBypass );
public KgNode getNodeByName( String name, boolean adminBypass );
```

Each new overload swaps the static constant for the accessor result. The
existing methods are kept (not removed) and now delegate:
`queryNodes(filters, prov, limit, offset)` calls
`queryNodes(filters, prov, limit, offset, false)`.

### `KnowledgeGraphService`

Symmetric: existing methods unchanged, new `*WithBypass(...)` overloads
threading the flag through.

### Call sites that flip the flag ON (admin context)

- `AdminKnowledgeResource` — all `/admin/knowledge-graph/*` reads
  (`handleGetNodes`, `handleGetEdges`, etc.). REST is already gated by
  `AdminAuthFilter` so the bypass is always safe here.
- `KgCurationOps` facade — any read it performs on the curation path
  (currently mostly write paths; verify).
- `InspectProposalsTool` — calls `getNodeByName` for `linked_entity` lookup.
  This is curator-facing; use bypass so a freshly-created node is visible.
- `ListProposalsTool` — calls `ProposalConflictFlags.forProposal(service, p)`
  which in turn calls `getNodeByName`. Should this use bypass? The
  `node_exists` flag is most useful to a curator deciding whether to approve;
  use bypass.
- **New tool registrations on `/wikantik-admin-mcp`.** `query_nodes` and
  `search_knowledge` currently live only on `/knowledge-mcp`. Register
  admin-bypass copies of both on `/wikantik-admin-mcp` so curators have
  a "verify what I just wrote" path without changing the existing
  agent-facing semantics. Two new tool registrations in
  `McpToolRegistry.java`:
  - `QueryNodesTool(kgService, /*adminBypass=*/ true)` → read-only list
  - `SearchKnowledgeTool(kgService, /*adminBypass=*/ true)` → read-only list
  
  The existing `/knowledge-mcp` registrations of the same two tools stay
  exactly as they are, with bypass=false. Both wrap the same service entry
  point via the new bypass-aware overload.
  
  Tool count on `/wikantik-admin-mcp` goes from 22 → 24. Update
  `McpProtocolIT.EXPECTED_TOOLS`, `CLAUDE.md` agent-facing-surface row,
  and `wikantik-mcp-instructions.txt` accordingly.

### Call sites that leave the flag OFF (agent retrieval)

- `QueryEntityResolver` (hybrid retrieval entity-pin)
- `InMemoryGraphNeighborIndex` (hybrid retrieval graph neighbour expansion)
- `NodeMentionSimilarity` (mention-based similarity)
- All `/knowledge-mcp` read tools NOT also exposed on `/wikantik-admin-mcp`

### Wire-level acceptance

After this fix, a curator workflow that does
`mcp call /wikantik-admin-mcp curate_nodes upsert name=Foo source_page=Bar`
followed by `mcp call /wikantik-admin-mcp query_nodes filter:Foo` MUST return
the freshly created node, regardless of whether `Bar` is on
`kg_excluded_pages`. The same `query_nodes` call against `/knowledge-mcp` MUST
honour the inclusion list and return nothing if `Bar` is excluded.

## Fix 2: Refuse ghost merges

In `DefaultKnowledgeGraphService.mergeNodes(UUID sourceId, UUID targetId)`,
prepend two existence checks:

```java
@Override
public void mergeNodes( final UUID sourceId, final UUID targetId ) {
    if ( nodes.getNode( sourceId ) == null ) {
        throw new IllegalStateException( "merge source not found: " + sourceId );
    }
    if ( nodes.getNode( targetId ) == null ) {
        throw new IllegalStateException( "merge target not found: " + targetId );
    }
    // existing body unchanged
}
```

`KgCurationOps.tryMergeNodes` already catches `Exception` and surfaces the
message as a per-op error in the bulk envelope. So `curate_nodes` merge of a
ghost UUID produces:

```json
{
  "failed": [{
    "tag": "n1",
    "action": "merge",
    "source_id": "00000000-...",
    "target_id": "fefb24bb-...",
    "error": "merge source not found: 00000000-..."
  }]
}
```

REST `/admin/knowledge-graph/nodes/merge` already routes through the same
facade after the T7 refactor.

## Fix 3: Node-type vocabulary gate + one-shot cleanup

### Service-tier validation

In `DefaultKnowledgeGraphService.upsertNode`, validate `nodeType` against
the regex **`^[a-z][a-z0-9_-]{0,30}$`** (lowercase identifier, max 31 chars,
allows hyphens to honour the existing `implementation-plan` /
`intelligence-summary` types in the corpus). Reject with
`IllegalArgumentException("invalid node_type: must match /^[a-z][a-z0-9_-]{0,30}$/")`.

```java
private static final java.util.regex.Pattern NODE_TYPE_REGEX =
        java.util.regex.Pattern.compile( "^[a-z][a-z0-9_-]{0,30}$" );

@Override
public KgNode upsertNode( final String name, final String nodeType, final String sourcePage,
                          final Provenance provenance, final Map< String, Object > properties ) {
    if ( nodeType != null && !NODE_TYPE_REGEX.matcher( nodeType ).matches() ) {
        throw new IllegalArgumentException(
                "invalid node_type: must match /^[a-z][a-z0-9_-]{0,30}$/ (got: '" + nodeType + "')" );
    }
    final KgNode result = nodes.upsertNode( name, nodeType, sourcePage, provenance, properties );
    snapshotBuilder.invalidateCache();
    return result;
}
```

Null `nodeType` is allowed (some legitimate extraction paths produce nodes
without a type — verify against the 71 empty-string rows; if they were
inserted as `""` rather than NULL, the cleanup script normalizes them).

### Symmetric validation in `propose_knowledge`

The MCP tool that *suggests* node types should also validate so a typo never
gets into a proposal in the first place. Add the same regex check to
`ProposeKnowledgeTool` before forwarding to the service.

### One-shot operator cleanup script

Create `bin/kg-cleanup-node-types.sh` with no `--unattended` mode — purely
interactive. Header documents:

```
ONE-SHOT operator script. Cleans up legacy node_type pollution before the
service-tier vocabulary gate (V_xxx commit) starts rejecting writes. Run once
per environment. Do NOT add to bin/db/migrations/ — this is data, not
schema.
```

Behaviour (operator runs manually against the local DB credentials in
`tomcat/.../ROOT.xml`):

1. Print the current distribution:
   `SELECT node_type, COUNT(*) FROM kg_nodes GROUP BY node_type ORDER BY 2 DESC`
2. For each non-matching type:
   - Show the type, its count, and the regex
   - Prompt: `[r]ename to <input>, [d]elete rows, [s]kip`
   - Apply with `UPDATE kg_nodes SET node_type = ? WHERE node_type = ?` or
     `DELETE FROM kg_nodes WHERE node_type = ?`
3. Re-print the distribution as a verification.

Use `psql` directly with `-h $PGHOST -U $PGUSER -d $PGDB`. Read credentials
from `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` via a small awk
parse, falling back to env vars `PGHOST` / `PGUSER` / `PGPASSWORD` if the
ROOT.xml isn't present (so the script also works against remote DBs).

## Fix 4: Better error on `curate_nodes.upsert` misuse

In `CurateNodesTool.doUpsert`, before the existing "name is required" check,
detect the most common shape misuse:

```java
private Map< String, Object > doUpsert( final Map< String, Object > op ) {
    if ( op.containsKey( "node" ) ) {
        return Map.of( "error",
            "upsert fields belong at the top level of the operation, not nested under 'node'. "
            + "Expected shape: {action: 'upsert', name: '...', node_type: '...', source_page: '...'}" );
    }
    final String name = stringOrNull( op.get( "name" ) );
    if ( name == null || name.isBlank() ) return Map.of( "error", "upsert requires name" );
    // ...rest unchanged
}
```

Mirror in `CurateEdgesTool.doUpsert` for the `edge` key (defensive — same
misuse may hit edges):

```java
if ( op.containsKey( "edge" ) ) {
    return Map.of( "error",
        "upsert fields belong at the top level of the operation, not nested under 'edge'. "
        + "Expected shape: {action: 'upsert', source_id: '...', target_id: '...', relationship_type: '...'}" );
}
```

No other behavioural change.

## Testing

| Fix | Unit tests | IT |
|-----|-----------|----|
| 1 | `KgNodeRepositoryBypassTest` covering: bypass=false hides excluded-page node; bypass=true exposes it; `getNode` bypass overload; `getNodeByName` bypass overload; mixed seed (allowed + excluded) returns only allowed when bypass=false, both when bypass=true. | `KgCurationVisibilityIT` (new file in selenide-tests/.../mcp): seeds one node on `KgVisibilityExcludedPage` (added to `kg_excluded_pages`) and one on `KgVisibilityAllowedPage`. Calls `query_nodes` via `/wikantik-admin-mcp` → both visible. Calls `query_nodes` via `/knowledge-mcp` → only the allowed node. Equivalent REST check via `/admin/knowledge-graph/nodes`. |
| 2 | `DefaultKnowledgeGraphServiceTest.mergeNodes_throwsWhenSourceMissing` + `…targetMissing` + happy path still works. | `KgCurationIT.curateNodesMergeWithGhostSourceIsPerOpError` — bulk-merge containing one valid + one ghost source; valid succeeds, ghost lands in `failed[]` with explicit message. |
| 3 | `DefaultKnowledgeGraphServiceTest.upsertNode_rejectsTrailingCommaTypo`, `…rejectsEmptyString`, `…rejectsUppercaseFirstLetter`, `…allowsHyphen`, `…allowsHappyPath`. Plus `ProposeKnowledgeToolTest` for the symmetric proposal-time validation. | `KgCurationIT.curateNodesUpsertWithPollutedTypeIsPerOpError` — bulk op with `node_type: "concept,"` lands in `failed[]` with the regex message. |
| 4 | `CurateNodesToolTest.upsert_rejectsNestedNodeShape`, `CurateEdgesToolTest.upsert_rejectsNestedEdgeShape`. | (covered by unit; no new IT.) |

All ITs run sequentially per CLAUDE.md (`mvn clean install -Pintegration-tests -fae`).

## Documentation

- Update `docs/wikantik-pages/KgInclusionPolicy.md` to document the
  admin-bypass behaviour: "Admin-context reads (REST `/admin/knowledge-graph/*`,
  MCP tools registered on `/wikantik-admin-mcp`) bypass the exclusion filter
  so curators can verify newly-created entities."
- Add a `bin/kg-cleanup-node-types.sh` entry to the script catalogue in
  `bin/README.md` if one exists; otherwise add a 4-line section to
  `CLAUDE.md` under "bin/ script conventions" pointing to the one-shot.

## Rollout

Single PR per fix (4 commits total, plus 1 for the cleanup script + docs):

1. `feat(kg): KgInclusionFilter admin-bypass accessor + repository overloads`
2. `feat(kg): wire admin-bypass on REST + curator MCP read tools`
3. `fix(kg): refuse merge against ghost source or target UUIDs`
4. `feat(kg): node_type vocabulary regex at upsertNode + propose_knowledge`
5. `chore(ops): one-shot script to clean legacy node_type pollution + DX docs`
6. `fix(mcp): curate_{nodes,edges}.upsert returns helpful error on nested shape`

No DB migration. Existing pollution is cleaned by the operator before
running the regex-gated build, OR after (in which case the regex catches
future writes but historical pollution stays until the script runs).

## Open questions

None. All four fixes have clear scope. The IPv6-style branching of "test first,
then maybe fix" doesn't apply here — every fix has known production code
changes.
