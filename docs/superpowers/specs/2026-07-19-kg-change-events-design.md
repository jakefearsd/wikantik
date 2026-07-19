# KG-Change Events: Incremental Ontology Entity Sync — Design

**Date:** 2026-07-19
**Status:** Approved (brainstormed + user-approved same day)
**Problem:** KG entity/edge writes only reach the ontology TDB2 store via the nightly
full rebuild (`OntologyRebuildScheduler`, 24h default) because no KG-change events
exist. Curated entities are up to 24h stale on every public ontology surface
(`/sparql`, `/id/{type}/{id}`, `/export/*`), the SHACL conformance views, and the
SEO JSON-LD `sameAs` targets. Page graphs are already event-incremental
(`OntologyEventListener` → `OntologyPageSync`); entity graphs are the anomaly.

## Decisions (user-approved forks)

1. **Delivery: async + coalesced.** Single-thread daemon executor with pending
   dirty-ID sets; a burst of N writes to one entity re-projects it once. No TDB2
   latency on KG-write request threads. (Rejected: synchronous like
   `OntologyPageSync` — would block REST/MCP callers and thrash during judge
   batches.)
2. **Transport: `WikiEvent` + event bus.** New `KgChangeEvent` in wikantik-event
   (sealed `permits` clause opened), fired via `WikiEventManager` like page events.
   Keeps the signal public for future consumers (cache invalidation, admin
   freshness). (Rejected: private listener interface — forfeits reusability.)
3. **ACL flips: deferred to nightly.** A page save that changes ACLs re-projects
   the page graph today but its sourced *entities* wait for the nightly rebuild;
   that staleness is unchanged by this feature and stays ≤24h. Follow-up is easy
   once `OntologyEntitySync` exists (mark entities of the saved page dirty).

## Architecture

```
DefaultKnowledgeGraphService ──┐                       ┌─> OntologyEntitySync
  (curation-facing writes)     ├─ KgChangeEvent ──> KgChangeEventListener
KgMaterializationService ──────┘   (WikiEventManager)  │   (thin WikiEventListener)
  (proposal-driven writes,                             │
   incl. async JudgeRunner)                            └─ pending sets ─ coalesce.ms
                                                          └─ drain (1 daemon thread):
                                                             fetch node+edges from DB
                                                             → isPublic gate
                                                             → EntityProjector.project
                                                             → replace/removeNamedGraph
Nightly OntologyRebuildScheduler: unchanged — demoted to reconciliation backstop
(also still covers ACL flips and any drain failures).
```

### 1. `KgChangeEvent` (wikantik-event)

- `public final class KgChangeEvent extends WikiEvent` — add to `WikiEvent`'s
  sealed `permits` list (`wikantik-event/.../WikiEvent.java:32`); `final` like
  `WikiEngineEvent`/`WikiSecurityEvent` (leaf event, no subtypes).
- Payload: `Set<UUID> touchedEntityIds` (re-project) and `Set<UUID> removedEntityIds`
  (drop named graph). One type constant `KG_CHANGED = 60` (page events use 10–28,
  security 30–54). **UUIDs only — no KG domain types cross into wikantik-event.**
- Fired via `WikiEventManager.fireEvent( client, event )` where `client` is the
  emitting service instance. Listener registration (in `OntologyWiringHelper`):
  `addWikiEventListener( kgService, listener )` and
  `addWikiEventListener( kgMaterialization, listener )` — both instances reachable
  via `KnowledgeSubsystem.Services` accessors.

### 2. Emission points — after the durable write (page-save precedent: write, then fire)

The two true write funnels for `kg_nodes`/`kg_edges` (verified by exhaustive sweep;
extraction pipeline writes only proposals/mentions; `diffAndRemoveStaleEdges` is
wired-but-never-called; `clearAll()` is an admin nuke out of scope):

**`DefaultKnowledgeGraphService`** (`wikantik-main/.../knowledge/DefaultKnowledgeGraphService.java`)
— fires ONLY for writes it performs directly against the repositories. Approve /
reject / judgeNow delegate to materialization, which fires instead (no double-fire).

| Method | touched | removed | Note |
|---|---|---|---|
| `upsertNode` | {nodeId} | — | |
| `upsertEdge` | {sourceId} | — | edge statements live in the source entity's graph |
| `confirmEdge` | {edge.sourceId} | — | tier change surfaces in `prov:wasAttributedTo` |
| `deleteEdge` | {edge.sourceId} | — | look the edge up **before** deleting |
| `deleteEdgeAndRecordRejection` | {edge.sourceId} | — | same pre-delete lookup |
| `deleteNode` | {source ids of in-edges, collected **pre-delete**} | {nodeId} | in-edge source graphs hold dangling statements otherwise |
| `mergeNodes` | {targetId} ∪ {re-pointed edge source ids} | {sourceNodeId} | method already iterates the edge list — collect ids there |

**`KgMaterializationService`** (`wikantik-main/.../knowledge/judge/KgMaterializationService.java`)

| Method | touched | removed | Note |
|---|---|---|---|
| `materialize` (machine + human tiers) | {srcNodeId, tgtNodeId} | — | fires even on the SHACL edge-skip path — the two node upserts still happened |
| `retract` | {source ids of provenance-deleted edges, pre-delete} | {provenance-deleted node ids, pre-delete} | |

Fire once per mutation method invocation with the full set (not per row). A failed
write (SHACL refusal in `tryUpsertEdge`, missing rows) fires nothing (curation-side
refusal happens pre-write) except the materialization SHACL skip noted above.

### 3. `OntologyEntitySync` + `KgChangeEventListener` (wikantik-main, `com.wikantik.ontology.runtime`)

The entity analog of `OntologyPageSync`. `KgChangeEventListener` is a thin
`WikiEventListener` that forwards the two ID sets to the sync.

- **Coalescing:** two thread-safe pending sets (touched / removed) + a
  single-thread daemon `ScheduledExecutorService`. `mark(touched, removed)` is
  O(1); if no drain is scheduled, schedule one after
  `wikantik.ontology.incremental.coalesce.ms` (default 500). Removal wins over
  touch for the same ID within a drain window (the drain checks DB state anyway).
- **Drain (per distinct entity):**
  - removed → `manager.removeNamedGraph( Iris.entity( id ) )`.
  - touched → fetch current node + outgoing edges **from the DB at drain time**
    (never from event payloads — this is what makes coalescing safe: we always
    project the latest truth). If the node is gone → remove graph. ACL gate: node
    public iff `sourcePage == null || isPublic.test( sourcePage )` (the SAME
    `isPublic` predicate instance `OntologyWiringHelper` already builds for
    rebuilds and `OntologyPageSync` — guest session + `canAccessQuietly`); if
    restricted → remove graph. Otherwise filter outgoing edges to those whose
    target node is also public (fetch + test per target), then
    `EntityProjector.project( node, publicEdges, slugToCanonicalId )` →
    `manager.replaceNamedGraph( Iris.entity( id ), model )`. Reuse the same
    slug→canonical resolver the rebuild wiring uses.
  - Per-entity failures: catch, `LOG.warn` with entity id + message, continue the
    drain. The drain never throws; the nightly rebuild reconciles misses.
- All TDB2 access goes through `OntologyModelManager.replaceNamedGraph` /
  `removeNamedGraph` — preserving its write-generation snapshot-invalidation
  contract.

### 4. Config + lifecycle

| Property | Default | Behavior |
|---|---|---|
| `wikantik.ontology.incremental.enabled` | `true` | `false` → listener never registered, sync never built; behavior identical to today (nightly-only) |
| `wikantik.ontology.incremental.coalesce.ms` | `500` | drain delay after first dirty mark |

- Wired in `OntologyWiringHelper.wireOntology` behind the existing
  `wikantik.ontology.enabled` master switch (disabled → nothing changes).
- Executor is daemon; add shutdown drain-and-stop mirroring
  `AsyncEmbeddingIndexListener.close()` (5s await, then `shutdownNow`).
- `OntologyRebuildScheduler` and its 24h default are **unchanged**.

### 5. Accepted race (documented, not engineered away)

An incremental drain interleaving with a running full rebuild can leave one entity
one write behind until its next change or the next nightly tick: both writers read
current DB state, so nothing corrupts, and `OntologyModelManager`'s write-generation
guard keeps snapshot caches coherent. No pause/lock coordination is added (YAGNI).

Also inherited (pre-existing, unchanged): `KgMaterializationService.materialize` is
three autocommitted statements, not one transaction; the event fires after the last
statement the method reached.

## Testing (TDD — failing test first per unit)

- **wikantik-event:** `KgChangeEvent` payload semantics (sets defensive-copied /
  non-null; sealed hierarchy compiles).
- **Emission (Mockito, listener attached to the service instance under test):** one
  test per mutation method asserting the exact touched/removed sets — with emphasis
  on the pre-delete collection paths (`deleteNode` in-edge sources, `mergeNodes`
  re-pointed sources, `retract` provenance rows) and the no-double-fire property of
  approve/judge (delegating paths fire only from materialization).
- **`OntologyEntitySync` (temp-dir TDB2, like existing wikantik-ontology tests):**
  coalescing (N marks → exactly 1 projection), removal, node-gone-at-drain,
  ACL gate (restricted source page → graph absent; both-endpoints rule for edges),
  drain continues past a per-entity failure.
- **Integration (existing IT suite):** curate an edge via the admin surface, poll
  `/sparql` until the triple appears **without** any rebuild trigger — the
  end-to-end proof. Full IT reactor gates the commit (repo policy).

## Out of scope

- Page-ACL-flip → entity re-projection (deferred; nightly covers, follow-up noted).
- Any change to extraction/proposal pipelines (they don't write kg_nodes/kg_edges).
- KG rerank / retrieval behavior (unrelated; that program is shelved).
- Pausing incremental sync during full rebuilds (accepted race above).
