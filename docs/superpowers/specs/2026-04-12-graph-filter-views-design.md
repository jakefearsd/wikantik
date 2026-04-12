# Graph Filter Views — Design

**Date:** 2026-04-12
**Status:** Approved — ready for implementation planning

## Problem

The `/graph` route renders the full knowledge-graph snapshot in Cytoscape. Load time is acceptable, but the graph is difficult to navigate: everything is on screen at once. Users need **alternative views** that slice the graph for exploration and understanding.

The three highest-value exploration tasks:

1. **Backbone view** — hubs plus connections between hubs, hide leaf detail
2. **Communities view** — group/isolate pages by their author-assigned cluster
3. **Topic/Tag view** — filter by frontmatter tag, type, or status

Other exploration modes (N-hop neighborhood, shortest paths, date windows) are interesting but out of scope for v1.

## Constraints

- **Client-side filtering only** for v1. The current snapshot payload is acceptable; server-side query params are deferred until load time becomes an issue.
- **Preserve existing behavior:** edge-type checkbox filter, orphan/stub toggle, `?focus=<pageName>` query param, 60-second snapshot cache, ACL redaction, Cytoscape COSE-Bilkent layout, semantic zoom, parallel edge merging.
- **Author-assigned clusters are the community signal.** The wiki already uses `cluster:` frontmatter; no graph algorithm (Louvain, Leiden, etc.) is needed.
- **Hybrid UI model:** presets serve as one-click "starting points" that configure a shared underlying filter state; stackable controls refine that state. Presets and controls are not two separate systems.
- **Mode-dependent visual treatment:** Backbone and Communities presets **hide** non-matching nodes (need a clean slate); Topic/Tag refinement **fades** non-matching nodes (highlight within context).

## Architecture

Four layers, built in order:

### Layer 1 — Filter state (React)

Single state object held in `GraphView`:

```ts
type FilterState = {
  preset: 'full' | 'backbone' | 'communities' | 'tags',
  // Backbone
  hubsOnly: boolean,
  includeHubNeighbors: boolean,
  // Communities
  clusters: Set<string>,           // empty = show all (or all when preset=communities and nothing isolated)
  showUnclustered: boolean,
  // Topic/Tag
  tags: Set<string>,
  types: Set<string>,
  statuses: Set<string>,
  searchText: string,
  // Orthogonal, preserved across preset changes
  edgeTypes: Set<string>,
  showOrphansStubs: boolean,
  // Derived / bookkeeping
  visualMode: 'hide' | 'fade',
}
```

Preset buttons write the state. Stackable controls edit the same state directly. Switching preset resets **preset-specific** slots but preserves the orthogonal controls (edge types, orphan/stub toggle, focus node).

### Layer 2 — Filter engine

A pure function:

```ts
applyFilters(snapshot, state) => {
  visibleNodeIds: Set<string>,
  fadedNodeIds: Set<string>,
  visibleEdgeIds: Set<string>,
  fadedEdgeIds: Set<string>,
  nodeColor: Map<string, string>,   // populated when communities preset is active
}
```

Rules:

- **Backbone preset:** node is visible iff `type === 'hub'` OR `role === 'hub'` (union). If `includeHubNeighbors`, also include any node with an edge to a visible hub.
- **Communities preset:** if `clusters` set is non-empty, only nodes with `cluster ∈ clusters` are visible (plus unclustered if `showUnclustered`). If set is empty, all nodes visible and colored by cluster.
- **Topic/Tag preset:** all nodes visible; faded if they don't match the facets. Facet semantics: **OR within a facet** (any selected tag matches), **AND across facets** (tags ∩ types ∩ statuses). Name search also fades non-matches.
- **Edges:** visible iff both endpoints visible. Faded iff either endpoint faded (and not itself removed by edge-type filter).
- **Orthogonal filters apply on top:** edge-type and orphan/stub toggle further remove from visible set.
- **Focus pin:** if `?focus=X` is set and X would be filtered out, keep X visible as a pinned exception.
- **Missing frontmatter:** nodes without `cluster`/`tags`/`status` simply don't match filters on those fields; they never throw.

### Layer 3 — URL sync

Filter state serializes to query params on every change (`history.replaceState` — no navigation, no history entries per click):

```
?preset=backbone
?preset=communities&cluster=operations-research,warehouse-automation&unclustered=0
?preset=tags&tags=optimization,simplex&type=article&status=active&search=warehouse
?hop=1                       # backbone +1 hop
?focus=LinearAlgebra         # existing
```

On load, parse → hydrate state. URL *is* the persistence layer; no localStorage for v1.

### Layer 4 — Backend DTO extension

`SnapshotNode` currently exposes `id, name, type, role, provenance, sourcePage, degreeIn, degreeOut, restricted`. Add three fields pulled from `KgNode.properties`:

```java
record SnapshotNode(
    ...,
    String cluster,           // may be null
    List<String> tags,        // may be empty
    String status             // may be null
) { }
```

ACL redaction rule: if the node is restricted, these fields are also nulled/emptied (same as `name`/`type`). This is a small additive change — no existing consumer breaks.

## UI

Extend the existing graph sidebar. Add a new top section above the existing controls.

Top to bottom:

1. **Preset row** — four pill buttons: `Full · Backbone · Communities · Tags`. Active preset highlighted. Clicking swaps the contextual control section (2).
2. **Contextual controls:**
   - *Full*: collapsed — no extra controls.
   - *Backbone*: "+1 hop neighbors" checkbox. Small caption: "Hubs = author-marked (`type: hub`) OR top-5% degree."
   - *Communities*: cluster legend — colored swatch + name + node count. Click to isolate (replace selection); shift-click to add/remove from selection. "Show unclustered" toggle.
   - *Tags*: searchable multi-select (tags derived from loaded snapshot, sorted by count; top-N with "show all" expander); `type` and `status` select controls beside it; free-text name search box.
3. **Always-visible controls (existing):** edge-type checkboxes, orphan/stub toggle, refresh, fit-to-view.
4. **Active filters chip row:** small chips showing e.g. `Cluster: operations-research ×`, `Tag: optimization ×`. Click the × to remove one refinement without clearing the preset.

### Visual behavior

- **Hide mode** (`visualMode === 'hide'`): filtered elements get a `display: none` class in Cytoscape. Remaining elements keep their layout positions — no re-layout on filter change (jarring). The `fit-to-view` button re-frames.
- **Fade mode** (`visualMode === 'fade'`): filtered elements get a `faded` class: `opacity: 0.1`, non-interactive. Edges fade when either endpoint is faded.
- **Cluster coloring**: stable color per cluster name (hash → HSL with fixed saturation/lightness). Active when Communities preset is active OR a cluster chip is present in the filter state. Stubs/orphans keep their current styling; they merely layer cluster color on top.

### Empty-result safeguard

If filters produce zero visible nodes, overlay a "No matches — clear filters" button on the canvas.

## Non-goals (v1)

- Server-side query params / payload reduction
- Algorithmic community detection (Louvain etc.)
- `part-of` secondary hub membership (single-cluster grouping only)
- localStorage persistence
- Re-layout on filter change (positions preserved)
- N-hop neighborhood mode, shortest-path mode, date-window mode

## Testing

- **Vitest (pure) — `applyFilters`:**
  - Backbone: hub-only, +1 hop, both explicit (`type:hub`) and implicit (`role:hub`) counted, edge endpoint visibility.
  - Communities: single/multi-cluster selection, unclustered bucket, color map populated correctly.
  - Tags: OR-within / AND-across semantics, search text fades non-matches.
  - Combined facets, orphan/stub toggle interaction, edge-type filter interaction.
  - Missing frontmatter resilience (no crashes when `cluster`/`tags`/`status` are null/empty).
  - Focus-pin: `?focus=X` overrides filter exclusion.
  - Empty-result path returns empty sets cleanly.
- **JUnit — `SnapshotNode` serialization:** new fields present when set; absent/defaulted when null; restricted nodes null them out.
- **Selenide IT:** load `/graph`, click Backbone, assert hub-only count, click Communities, isolate a cluster, assert URL has `?preset=communities&cluster=...`, reload, assert state restored.
- **Manual verification:** exercise each preset in the browser, check transitions preserve orthogonal filters, test a shared URL end-to-end.

## Build order

1. Backend: extend `SnapshotNode` DTO + serializer.
2. Frontend: filter state model + `applyFilters` engine + unit tests (pure, no UI yet).
3. Frontend: Cytoscape integration — add `hidden` / `faded` / cluster-color classes, wire to filter engine output.
4. Frontend: FilterPanel UI — preset pills, contextual control sections, chip row.
5. Frontend: URL sync (parse on mount, serialize on state change).
6. Integration test + manual browser verification.

Each step is independently verifiable. Stopping mid-way leaves a working graph (the engine still returns the full snapshot; missing UI just means fewer controls).
