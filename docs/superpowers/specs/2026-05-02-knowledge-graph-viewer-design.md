# Knowledge Graph Viewer — Design Spec
**Date:** 2026-05-02
**Status:** Draft

---

## 1. Goal and Scope

Add a full-page interactive graph visualisation at the SPA route `/knowledge-graph` that renders the Knowledge Graph (KG) snapshot — LLM-extracted entity nodes and their co-mention / typed edges — using the same Cytoscape.js canvas infrastructure already powering the Page Graph viewer at `/page-graph`. The new viewer must feel visually and behaviourally identical to the Page Graph viewer except where the underlying data model differs, and must expose one KG-specific capability not present in the Page Graph viewer: a **tier filter** that controls whether the snapshot includes machine-tier-only nodes or only human-validated nodes.

**Non-goals (v1):**

- No editing of KG nodes or edges from this viewer. The admin GraphExplorer at `/admin/knowledge-graph` handles writes.
- No drilldown into `kg_proposal_reviews` records. That is a separate admin surface.
- No export of the snapshot (CSV, GraphML, etc.).
- No direct link between a KG entity node and the wiki page that mentioned it (that is a separate mention-drilldown surface).
- No embedding-similarity layout mode. Spatial layout is purely force-directed (cose-bilkent) as in the Page Graph viewer.
- No real-time/streaming updates. The snapshot is fetched once on mount and on explicit Refresh.
- No merge or deduplicate operations. Those belong to the admin panel.

---

## 2. Background

The two graph subsystems are documented in `docs/wikantik-pages/PageGraphVsKnowledgeGraph.md`. The key data-model difference between them is:

| Dimension | Page Graph | Knowledge Graph |
|-----------|-----------|-----------------|
| Node identity | Page slug (`name`) | Entity name + `node_type` |
| Edge semantics | Real wikilink (page A links to page B) | LLM-extracted relation or co-mention |
| Hub classification | Degree threshold stored in `GraphSnapshot.hubDegreeThreshold` | Same field; same degree-based rule applies |
| Cluster membership | `cluster:` frontmatter on the source page | Same field; KG nodes carry it from the source page's frontmatter |
| Provenance | Per-node and per-edge `Provenance` enum (`HUMAN_AUTHORED` / `AI_INFERRED` / `AI_REVIEWED`) | Same enum, but semantically richer: nearly all KG nodes start as `AI_INFERRED` |
| Tier | Not present | Per-proposal tier (`human` vs `machine`); affects which nodes appear in the snapshot via `?min_tier=` |
| Status | Optional string | Optional string (e.g. `active`, `stub`, `deprecated`) |

`SnapshotNode` and `SnapshotEdge` in `wikantik-api` are **shared** between both subsystems. The KG snapshot endpoint is `GET /api/knowledge/graph?min_tier=human|machine` (default `machine`). The Page Graph snapshot endpoint is `GET /api/page-graph/snapshot` (no tier parameter).

**Critical gap:** `SnapshotNode` currently has no `tier` field. The `min_tier` query parameter tells the server which tier-floor to apply before returning the snapshot, but the response contains no per-node indication of which tier that node came from. This means the viewer cannot render a "this node is human-validated" badge unless the field is added to the wire type. This gap is flagged explicitly in section 7 and section 11.

---

## 3. Reference: Existing Page Graph Viewer

Every file read during research, with its single-line responsibility:

| File (repo-relative) | Responsibility |
|---|---|
| `wikantik-frontend/src/main.jsx` | Router root: declares the `/page-graph` `React.lazy` route inside `<App />` wrapper |
| `wikantik-frontend/src/App.jsx` | Shell layout; `isGraphRoute` flag (`pathname === '/page-graph'`) that applies `app-content-full` CSS class for edge-to-edge canvas |
| `wikantik-frontend/src/components/Sidebar.jsx` | Nav sidebar; contains the `Page Graph` link under "Wiki Tools" section |
| `wikantik-frontend/src/api/client.js` | Central API client; `api.pageGraph.getSnapshot()` and `api.knowledge.getGraphSnapshot()` |
| `wikantik-frontend/src/components/pagegraph/PageGraphView.jsx` | Top-level orchestrator: fetch state machine, filter state, Cytoscape element derivation, drawer open/close, keyboard Escape handler |
| `wikantik-frontend/src/components/pagegraph/graph-data.js` | Pure functions: `mergeBidirectionalEdges`, `mergeParallelEdges`, `toCytoscapeElements` — converts a `GraphSnapshot` to Cytoscape element arrays |
| `wikantik-frontend/src/components/pagegraph/filter-state.js` | Immutable filter state shape; preset constants; pure reducer functions (`applyPreset`, `toggleCluster`, etc.) |
| `wikantik-frontend/src/components/pagegraph/filter-url.js` | Serialises/deserialises filter state to/from URL query params (`filterStateToParams`, `paramsToFilterState`) |
| `wikantik-frontend/src/components/pagegraph/filter-engine.js` | Pure: applies filter state to a snapshot and returns `{ visibleNodeIds, fadedNodeIds, visibleEdgeIds, fadedEdgeIds, nodeColor }` |
| `wikantik-frontend/src/components/pagegraph/FilterPanel.jsx` | Sidebar panel of preset pills, cluster legend, tag picker, type/status selects, search input, active-filter chips |
| `wikantik-frontend/src/components/pagegraph/GraphCanvas.jsx` | Wraps `react-cytoscapejs`; attaches Cytoscape event handlers; semantic zoom; selection dimming; `window.cy` global for toolbar fit-to-view |
| `wikantik-frontend/src/components/pagegraph/GraphToolbar.jsx` | Top toolbar: Fit to View, Edge Filter popover, Only Orphans/Stubs toggle, Refresh, snapshot timestamp |
| `wikantik-frontend/src/components/pagegraph/GraphDetailsDrawer.jsx` | Right-side slide-in panel showing selected node metadata and incident edge list |
| `wikantik-frontend/src/components/pagegraph/GraphLegend.jsx` | Bottom-right collapsible legend: node role swatches, edge direction legend, snapshot time |
| `wikantik-frontend/src/components/pagegraph/GraphZoomSlider.jsx` | Bottom-right zoom slider that drives `window.cy.zoom()` |
| `wikantik-frontend/src/components/pagegraph/GraphErrorState.jsx` | Empty/error/unauthorized/forbidden states |
| `wikantik-frontend/src/components/pagegraph/GraphErrorBoundary.jsx` | React error boundary wrapping the whole canvas |
| `wikantik-frontend/src/components/pagegraph/GraphLoadingFallback.jsx` | Spinner shown while fetching |
| `wikantik-frontend/src/components/pagegraph/graph-style.js` | Cytoscape stylesheet array: node colours per role, edge styles, dimmed/hidden/faded/selected selectors |
| `wikantik-frontend/src/components/pagegraph/graph.css` | CSS for `.graph-view`, `.graph-canvas-container`, `.graph-toolbar`, `.graph-details-drawer`, `.graph-legend`, `.filter-panel`, etc. |
| `wikantik-frontend/src/components/pagegraph/zoom-scale.js` | Utility: zoom domain → CSS scale factor |
| `wikantik-frontend/src/components/pagegraph/PageGraphView.test.jsx` | Vitest tests for PageGraphView fetch states and filter preset URL round-trip |
| `wikantik-frontend/src/components/admin/GraphExplorer.jsx` | Admin tabular KG explorer (paginated node list + NodeDetail side pane); already exists; does NOT render a visual graph |
| `wikantik-war/src/main/webapp/WEB-INF/web.xml` (lines 132–151) | `SpaRoutingFilter` mapping; `/page-graph` is listed; `/knowledge-graph` is not yet listed |

---

## 4. Architecture

### Files to create

| File | Responsibility | Key exports / components | Dependencies |
|------|---------------|--------------------------|--------------|
| `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx` | Top-level orchestrator; mirrors `PageGraphView.jsx` exactly except (a) calls `api.knowledge.getGraphSnapshot({minTier, signal})`, (b) holds a `minTier` state value (`'machine'` default), (c) passes `minTier` to the toolbar tier dropdown | `default KnowledgeGraphView` | `api.knowledge.getGraphSnapshot`, all shared graph sub-components via `../pagegraph/*`, new `KgGraphLegend`, new `KgGraphToolbar` |
| `wikantik-frontend/src/components/kgraph/KgGraphToolbar.jsx` | Extends GraphToolbar with a Tier dropdown (`machine` / `human`) rendered to the left of the Refresh button; fires `onTierChange(tier)` | `default KgGraphToolbar` | React, no external deps |
| `wikantik-frontend/src/components/kgraph/KgGraphLegend.jsx` | Extends GraphLegend with a KG-specific section: node-type colour swatches, provenance icon key, tier count display (`N machine + M human`). Accepts `machineCount` and `humanCount` props derived from the fetched snapshot. | `default KgGraphLegend` | React |
| `wikantik-frontend/src/components/kgraph/KgGraphDetailsDrawer.jsx` | Extends `GraphDetailsDrawer` with KG-specific fields: `node_type`, `provenance` badge (colour-coded), `status` icon (if present), and a tier badge (disabled until `SnapshotNode.tier` is added — see section 7). Removes the "Open page" button (no direct page identity for a KG node) and replaces it with a link to the admin node detail: `/admin/knowledge-graph` filtered to the node name. | `default KgGraphDetailsDrawer` | React, `react-router-dom Link` |
| `wikantik-frontend/src/components/kgraph/kg-graph-data.js` | Re-exports `mergeBidirectionalEdges` and `mergeParallelEdges` from `../pagegraph/graph-data.js` as-is; provides a KG-specific `toKgCytoscapeElements(snapshot, filter)` that colour-codes nodes by `node_type` (using a deterministic palette, same algorithm as `colorForCluster`) instead of cluster, and adds a `provenanceBadge` data property for tooltip use | `toKgCytoscapeElements` | `../pagegraph/graph-data.js`, `../pagegraph/filter-engine.js` |
| `wikantik-frontend/src/components/kgraph/kg-graph-style.js` | Cytoscape stylesheet for the KG viewer. Starts from a copy of `graph-style.js` and adds: per-`node_type` CSS classes (`node-type-person`, `node-type-concept`, etc.) with distinct fill colours; `node.provenance-ai-inferred` with a subtle dashed border; `node.provenance-human-authored` with a solid bright border. Hub and orphan/stub rules carry over unchanged. | `kgGraphStylesheet` | None |
| `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.test.jsx` | Vitest tests mirroring `PageGraphView.test.jsx`: loading state, success, 401, 500, zero-node empty state; plus KG-specific: tier dropdown changes URL param; snapshot fetched with correct `min_tier` query string | — | `vitest`, `@testing-library/react`, `api` mock |

### Files to modify

| File | Change |
|------|--------|
| `wikantik-frontend/src/main.jsx` | Add `React.lazy(() => import('./components/kgraph/KnowledgeGraphView.jsx'))` and a `/knowledge-graph` Route inside `<App />`, following the exact same pattern as `/page-graph` |
| `wikantik-frontend/src/App.jsx` | Extend `isGraphRoute` from `pathname === '/page-graph'` to `pathname === '/page-graph' \|\| pathname === '/knowledge-graph'` so the `app-content-full` CSS class is applied on both routes |
| `wikantik-frontend/src/components/Sidebar.jsx` | Add a `Knowledge Graph` link under the "Wiki Tools" section, directly below the existing `Page Graph` link |
| `wikantik-frontend/src/api/client.js` | Update `api.knowledge.getGraphSnapshot` to accept a `minTier` option and append `?min_tier=<value>` when provided. Change the signature from `({ signal } = {})` to `({ minTier, signal } = {})`. |
| `wikantik-war/src/main/webapp/WEB-INF/web.xml` | Add `<url-pattern>/knowledge-graph</url-pattern>` to the `SpaRoutingFilter` mapping block, immediately after `/page-graph` |
| `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java` | **Optional / Phase 2:** Add `String tier` field to the record so the viewer can display per-node tier badges. This is a wire-breaking addition requiring `KnowledgeGraphService.snapshotGraph` and its callers to populate the field. See section 7. |

---

## 5. Data Flow

```
User navigates to /knowledge-graph
  └─> SpaRoutingFilter forwards to /index.html
        └─> React Router renders KnowledgeGraphView
              └─> on mount: fetchSnapshot(minTier='machine')
                    └─> api.knowledge.getGraphSnapshot({ minTier: 'machine' })
                          └─> GET /api/knowledge/graph?min_tier=machine
                                └─> KnowledgeGraphResource.doGet()
                                      └─> KnowledgeGraphService.snapshotGraph(session, Tier.MACHINE)
                                            └─> GraphSnapshot { nodes, edges, ... }
                    └─> on success: setSnapshot(data), setFetchState('ready')
                    └─> on empty: setFetchState('error'), setErrorVariant('empty')
                    └─> on 401: setErrorVariant('unauthorized')
                    └─> on 5xx: setErrorVariant('server')

User changes Tier dropdown from 'machine' to 'human'
  └─> handleTierChange('human')
        └─> setMinTier('human')
        └─> URL bar updated: /knowledge-graph?tier=human  (replaceState, not pushState)
        └─> fetchSnapshot('human') triggered via useEffect([minTier])
              └─> GET /api/knowledge/graph?min_tier=human
              └─> re-render with new (smaller) snapshot

User clicks a node
  └─> handleNodeClick(nodeId)
        └─> setSelectedId(nodeId)
        └─> KgGraphDetailsDrawer renders with selectedNode from snapshot.nodes

User presses Escape
  └─> window keydown handler → setSelectedId(null)
```

### Tier filter URL contract

The tier selection is stored in the URL query string as `?tier=machine` or `?tier=human`. Omitting the parameter is equivalent to `?tier=machine` (matching the API default). On mount, `KnowledgeGraphView` reads `searchParams.get('tier')` and initialises `minTier` from it, falling back to `'machine'`. On change, `window.history.replaceState` updates the URL without a new history entry.

**Recommendation:** Use `?tier=` (not `?min_tier=`) in the frontend URL because `min_tier` is a backend implementation term; the user-facing concept is just "tier". The client translates `tier=human` to `min_tier=human` when calling the API.

**Alternative considered:** Put the tier in the path (`/knowledge-graph/human`). Rejected: the rest of the filter state (cluster, search, etc.) lives in query params, and mixing path segments with query params for the same conceptual "view" is inconsistent.

---

## 6. UI Parity Matrix

| Page Graph feature | Knowledge Graph viewer treatment |
|--------------------|----------------------------------|
| Full-page canvas (`app-content-full` CSS class) | Identical — `isGraphRoute` extended to include `/knowledge-graph` |
| `GraphLoadingFallback` spinner | Reuse directly; no wrapper needed |
| `GraphErrorBoundary` wrapping whole canvas | Reuse directly |
| `GraphErrorState` (empty / unauthorized / forbidden / server) | Reuse directly; message strings substituted ("Knowledge graph is empty" etc.) — pass the component a `graphName` prop or use a thin wrapper with custom `VARIANTS` |
| `FilterPanel` with preset pills (Full / Backbone / Communities / Tags) | Reuse directly — the snapshot wire shape is identical, `cluster`, `tags`, `type`, `status` fields all exist on `SnapshotNode` |
| Zoom/pan (Cytoscape wheel/drag) | Identical — `GraphCanvas` is reused as-is |
| `GraphZoomSlider` | Reuse directly |
| `window.cy.fit()` via GraphToolbar "Fit to view" button | Reuse via `KgGraphToolbar` which embeds this button |
| Node click → `GraphDetailsDrawer` | Replaced by `KgGraphDetailsDrawer` (same slide-in pattern, KG-specific fields; see section 4) |
| Background click → close drawer | Identical event handler |
| Edge filter popover (hide specific `relationshipType` values) | `KgGraphToolbar` includes the same popover; edge types derived from `snapshot.edges.map(e => e.relationshipType)` |
| Semantic zoom (label hiding at extremes) | Reuse `GraphCanvas` semantic zoom logic; no KG-specific changes needed |
| Hub highlighting (green, larger node, `role-hub` CSS class) | Identical; `SnapshotNode.role` carries `hub` for high-degree KG entity nodes |
| Orphan/stub anomaly mode | Reuse — same `role` values appear on KG stubs |
| Selection dimming (neighbourhood) | Identical Cytoscape logic in `GraphCanvas` |
| Filter chips (active-filter row) | `FilterPanel` handles chips already; reused |
| URL-serialised filter state | `filter-url.js` reused without change; `?tier=` added as a separate managed param in `KnowledgeGraphView` |
| Snapshot timestamp in toolbar and legend | Identical pattern |
| Refresh button | Identical; `KgGraphToolbar.onRefresh` calls `fetchSnapshot(minTier)` |
| Keyboard Escape → close drawer | Identical `useEffect` in `KnowledgeGraphView` |
| Banner "X edges are real wikilinks" | Replaced with: "Knowledge Graph — edges are LLM-extracted relations. [What is the Knowledge Graph?](/wiki/PageGraphVsKnowledgeGraph)" |
| Node size scaled by `degreeIn` | Identical — `mapData(degreeIn, 0, 20, 6, 18)` in stylesheet |
| Cluster border ring colour (`clusterColor`) | Retained; KG nodes also carry `cluster` in `SnapshotNode` |
| `graph.css` shared stylesheet | Reused without change — all `.graph-*` class names are topology-agnostic |

---

## 7. KG-Specific Extensions

### 7a. Tier filter

**Location:** Left side of `KgGraphToolbar`, before the "Edge Filter" button.

**Control:** A `<select>` dropdown with exactly two options:

```
<select value={minTier} onChange={e => onTierChange(e.target.value)}>
  <option value="machine">Tier: machine (broader)</option>
  <option value="human">Tier: human (strict)</option>
</select>
```

**Behaviour:** Changing the value fires `onTierChange(tier)` in `KnowledgeGraphView`, which (1) sets `minTier` state, (2) updates URL via `replaceState` to `?tier=<value>`, (3) calls `fetchSnapshot(tier)`. The canvas shows `GraphLoadingFallback` during the re-fetch. On completion, previously selected node ID is discarded (the UUID set may change between tiers).

**Legend count:** `KgGraphLegend` receives two props: `machineCount` and `humanCount`. These are computed by `KnowledgeGraphView` after each fetch. Because the current `SnapshotNode` has no `tier` field (see gap below), counts are computed from the two fetches directly:

- `machineCount = snapshot.nodeCount` (from `?min_tier=machine` fetch)
- `humanCount` is unknown without a second fetch or a new field

**Gap flag — `SnapshotNode.tier` is missing:** `SnapshotNode` does not currently carry a `tier` field. Without it, the viewer cannot (a) display a per-node tier badge, or (b) show a split count like "420 machine + 280 human-only" without issuing a second API call. The recommended fix is to add `String tier` (`"human"` / `"machine"`) to `SnapshotNode` and populate it in `KnowledgeGraphService.snapshotGraph()`. This is a **Phase 2 backend change** (see section 12). In Phase 1, the tier dropdown controls which nodes appear but no per-node tier badge is rendered.

### 7b. Provenance badge on hover tooltip

The existing `GraphCanvas` tooltip shows only the node label. `KgGraphDetailsDrawer` displays provenance prominently; in the hover tooltip the provenance is also shown.

**Implementation:** The tooltip `div` in `GraphCanvas` is currently built inline. The KG viewer uses `GraphCanvas` as-is (reuse decision) but passes richer node label text via the `data.label` Cytoscape property. In `toKgCytoscapeElements`, the label is set to:

```js
label: n.restricted ? '\u{1F512}' : `${n.name || ''}\n[${n.provenance || ''}]`
```

Cytoscape's `line-height` style property allows multi-line labels since Cytoscape 3.x; the second line is styled in a smaller font via `font-size` on the `text-wrap: wrap` selector.

**Alternative considered:** A separate floating DOM tooltip that shows provenance (avoiding Cytoscape multi-line labels). Rejected for Phase 1 because it requires DOM position tracking code; the multi-line label achieves the same result without new infrastructure.

### 7c. Status icon on nodes

`SnapshotNode.status` is already present. In `kg-graph-style.js`, add CSS class selectors for known status values:

- `node[status = "stub"]` — dashed border, same as Page Graph stub
- `node[status = "deprecated"]` — grey fill, half opacity
- `node[status = "active"]` — no special style (default)

The `status` value is written to `node.data.status` by `toKgCytoscapeElements` (it already is in the Page Graph `toCytoscapeElements`; `kg-graph-data.js` copies this).

### 7d. Tier badge on nodes (Phase 2 only)

Once `SnapshotNode.tier` is added, the stylesheet adds:

- `node[tier = "human"]` — gold/amber solid border (2px), distinguishing human-validated from machine-only
- `node[tier = "machine"]` — no extra style

The legend gains a row: "Gold border = human-validated".

### 7e. Node-type colour coding

KG nodes are coloured by `node_type` rather than by cluster (cluster border rings are additive, not the fill colour).

`kg-graph-data.js` builds a `nodeTypeColor` map using the same `colorForCluster` deterministic palette already in `filter-engine.js`, keyed by `node_type`. This map is passed into `toKgCytoscapeElements` the same way `filter.nodeColor` is passed in the Page Graph version. The stylesheet assigns `background-color` from `data(nodeTypeColor)` for the base `node` selector, overridden by `node.role-hub`, `node.role-orphan`, `node.role-stub` as before.

---

## 8. Routing and SPA Shell

### `wikantik-frontend/src/main.jsx`

Add a lazy-loaded route for `/knowledge-graph` immediately after the existing `/page-graph` route:

```jsx
const KnowledgeGraphView = React.lazy(() => import('./components/kgraph/KnowledgeGraphView.jsx'));

// Inside <Route element={<App />}>:
<Route path="/knowledge-graph" element={
  <Suspense fallback={<div className="graph-loading"><p>Loading knowledge graph...</p></div>}>
    <KnowledgeGraphView />
  </Suspense>
} />
```

### `wikantik-frontend/src/App.jsx`

Change line 13 from:

```js
const isGraphRoute = location.pathname === '/page-graph';
```

to:

```js
const isGraphRoute = location.pathname === '/page-graph' || location.pathname === '/knowledge-graph';
```

This applies the `app-content-full` CSS class that removes all padding and gives the canvas edge-to-edge width. Without this change, the canvas renders inside the standard content-width column and looks wrong.

### `wikantik-frontend/src/components/Sidebar.jsx`

Add a new `<Link>` entry in the "Wiki Tools" section, directly below the existing `Page Graph` link:

```jsx
<Link to="/knowledge-graph" className="sidebar-link" onClick={onMobileClose}>
  Knowledge Graph
</Link>
```

### `wikantik-war/src/main/webapp/WEB-INF/web.xml`

Add `/knowledge-graph` to the `SpaRoutingFilter` mapping. Insert it on the line immediately after `/page-graph`:

```xml
<url-pattern>/page-graph</url-pattern>
<url-pattern>/knowledge-graph</url-pattern>
```

Without this entry, a direct URL load of `http://host/knowledge-graph` (or browser refresh on the route) is not forwarded to `/index.html` and results in a 404 or the Tomcat default error page.

---

## 9. Out-of-Scope Notes

These items are deliberately deferred and should not creep into the phases below:

- **Editing nodes in the viewer.** Node merge, rename, and delete belong to the admin GraphExplorer.
- **Drilling into `kg_proposal_reviews`.** The viewer shows the node as it currently exists in the KG; the proposal audit trail is a separate admin surface.
- **Exporting the snapshot.** No CSV/GraphML export button in v1.
- **`?focus=<name>` deep-link for KG nodes.** The Page Graph viewer supports `?focus=<slug>` to centre on a specific page. KG entity names are not guaranteed to match page slugs; adding focus support requires a name-lookup step. Defer to a follow-up.
- **Mention drilldown.** Clicking a KG entity node could eventually open a panel listing the wiki pages that mention it. That is a distinct surface requiring a new API endpoint.
- **Auto-refresh / polling.** The KG snapshot can be large and is not real-time; no polling.

---

## 10. Testing Strategy

### Vitest unit tests (`wikantik-frontend/src/components/kgraph/KnowledgeGraphView.test.jsx`)

Mirror the structure of `PageGraphView.test.jsx`. Required test cases:

1. `shows loading then canvas on success` — mock `api.knowledge.getGraphSnapshot` to resolve with a 2-node snapshot; assert canvas renders.
2. `shows 401 error for unauthorized` — mock rejected with `{ status: 401 }`; assert "Sign in" text.
3. `shows server error for 5xx` — mock rejected with `{ status: 500 }`; assert "unavailable" text.
4. `shows empty state for zero nodes` — mock snapshot with `nodeCount: 0`.
5. `tier dropdown defaults to machine and fetches with min_tier=machine` — assert `getGraphSnapshot` called with `{ minTier: 'machine' }` on mount.
6. `changing tier to human re-fetches and updates URL` — simulate dropdown change; assert `getGraphSnapshot` called with `{ minTier: 'human' }` and `window.history.replaceState` called with URL containing `tier=human`.
7. `loads tier from URL param on mount` — render with `MemoryRouter initialEntries={['/knowledge-graph?tier=human']}`; assert initial fetch uses `minTier: 'human'`.

### Vitest unit tests for `kg-graph-data.js`

1. `toKgCytoscapeElements assigns nodeTypeColor from node_type` — assert nodes with different `node_type` values get different `nodeTypeColor` data properties.
2. `toKgCytoscapeElements includes provenance in label for non-restricted nodes` — assert label contains provenance string.
3. Bidirectional and parallel edge merge tests: delegate to existing `graph-data.test.js` (those functions are re-exported).

### Selenide integration tests

Add a new IT class `KnowledgeGraphViewerIT` in `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/`, modelled on `KnowledgeGraphVisualizationIT.java`:

1. `route_loads_without_error` — navigate to `/knowledge-graph`; assert the React root renders (no crash, no 404 page).
2. `tier_dropdown_visible` — assert `<select>` with options "machine" and "human" is present in the DOM.
3. `tier_dropdown_changes_url` — select "human"; assert `window.location.search` contains `tier=human`.
4. `canvas_renders_after_load` — assert `.graph-canvas-container` is visible (or `GraphErrorState` if no DB — same pattern as existing graph ITs).
5. `sidebar_link_present` — assert the nav sidebar contains a link with text "Knowledge Graph".

These tests must be written to pass whether or not PostgreSQL is available (same convention as `KnowledgeGraphVisualizationIT`): they assert React routing and DOM structure, not data presence.

---

## 11. Risks and Open Questions

1. **`SnapshotNode.tier` gap (high priority).** The wire type does not carry a per-node tier value. Without this field, the "tier badge" feature (section 7d) and the legend count split ("N machine + M human") require either (a) a second API call, or (b) the field added to `SnapshotNode`. Adding the field is the clean path but is a two-module change (API + service). **Operator input needed:** is the tier badge a must-have for launch, or can Phase 1 ship without it? If without, the dropdown still controls which nodes appear; nodes just don't carry a visible tier indicator.

2. **`getGraphSnapshot` in `client.js` does not accept `minTier` yet.** The method signature is `({ signal } = {})` and always calls `/api/knowledge/graph` without any query string. The modification in section 4 (Files to modify) adds `minTier` to the signature, but this is a shared client method also called by `GraphExplorer.jsx` indirectly (it calls `api.knowledge.queryNodes` via the admin surface, not `getGraphSnapshot`). Verify no other caller breaks. Based on the code read, `getGraphSnapshot` is currently unused by any component — the admin panel calls `queryNodes` + `getSchema`. Safe to add the param without coordination risk.

3. **KG snapshot size vs. Page Graph snapshot size.** The Page Graph snapshot covers all linked pages in the wiki. The KG snapshot at `min_tier=machine` may include thousands of entity nodes, making Cytoscape layout slow. The 15-second `LAYOUT_TIMEOUT_MS` in `GraphCanvas` is the existing backstop. If the KG corpus is large, the first render will hit this timeout and `onLayoutTimeout` will fire, stopping the layout at an intermediate state. Consider exposing a node-count warning (e.g. "Large graph: {N} nodes — layout may be approximate") in the toolbar when `snapshot.nodeCount > 500`. This is a UX nicety but should be flagged before implementation so the engineer can include it.

---

## 12. Implementation Phases

**Phase 1 — Wiring and parity (no KG-specific extras)**

Create `KnowledgeGraphView.jsx` calling `api.knowledge.getGraphSnapshot()` (no tier param yet), reusing all existing pagegraph sub-components without modification. Wire the `/knowledge-graph` route in `main.jsx`, `App.jsx` `isGraphRoute`, Sidebar link, and `web.xml` SPA filter mapping. The viewer renders the KG snapshot with the Page Graph visual style (uniform node colour, no node-type colouring, no tier dropdown). Vitest tests for load states and routing. Validates: the plumbing works end-to-end before any KG-specific UI is built.

**Phase 2 — Tier filter**

Add `minTier` state to `KnowledgeGraphView`; add `KgGraphToolbar` with the tier `<select>` dropdown; update `api.knowledge.getGraphSnapshot` in `client.js` to pass `?min_tier=`. URL serialisation (`?tier=`), mount-time reading, and re-fetch on change. Vitest tests for tier param. Selenide IT for dropdown presence and URL update. Also: add `String tier` to `SnapshotNode` record and populate it in `KnowledgeGraphService.snapshotGraph()` (backend change in `wikantik-api` + `wikantik-main`); update the legend to show tier counts.

**Phase 3 — Node-type visual differentiation**

Create `kg-graph-data.js` with `toKgCytoscapeElements` and `kg-graph-style.js` with per-`node_type` colours. Wire into `KnowledgeGraphView`. Update `KgGraphLegend` with node-type swatches. Add `KgGraphDetailsDrawer` with `node_type` and `provenance` badge. Vitest tests for `toKgCytoscapeElements` colour assignment.

**Phase 4 — Provenance and status styling**

Add `node.provenance-*` and `node[status=*]` Cytoscape stylesheet rules in `kg-graph-style.js`. Update `KgGraphDetailsDrawer` with status icon (small coloured dot) and provenance badge (coloured pill: "human-authored" = green, "ai-reviewed" = blue, "ai-inferred" = grey). Update legend with provenance key.

**Phase 5 — Tier badge on nodes (depends on Phase 2 backend change shipping first)**

Once `SnapshotNode.tier` is confirmed in the wire, add the gold/amber border rule for `node[tier="human"]` in `kg-graph-style.js` and the legend entry.

**Phase 6 — Large-graph UX and polish**

Node-count warning in toolbar when `snapshot.nodeCount > 500`. Performance: consider adding a `?limit=N` or cluster-scoped fetch option to the KG snapshot endpoint if the full graph is impractical to render in the browser. Keyboard shortcut (`Cmd+K` → filter panel focus). Accessible colour palette audit for the node-type colours.
