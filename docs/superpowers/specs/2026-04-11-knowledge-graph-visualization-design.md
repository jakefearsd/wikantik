# Knowledge Graph Visualization — Design

**Date:** 2026-04-11
**Author:** Jake Fear (brainstormed with Claude)
**Status:** Approved — ready for plan
**Scope:** Add a read-only, exploratory visualization of the knowledge graph as its own
route in the React SPA. Surfaces overall structure and state (hubs, orphans, stubs,
restricted pages) in a single snapshot-in-time view backed by one bulk REST call.

## Goal

Answer the question *"what is the state of the knowledge graph?"* in a single glance,
and let any logged-in user explore from there. The view blends two purposes:

- **Understand structure** — which pages form clusters, which are hubs, how densely the
  content is connected.
- **Find anomalies** — orphans (zero-degree pages), stubs (referenced names with no
  backing page), and pages that are weakly connected.

Clicking any node opens a details drawer showing data already in the graph payload —
name, type, role, provenance, degrees, and its incident edges — and lets the user walk to
a neighbor by clicking an edge row or jump to the underlying wiki page.

## Non-Goals

- **No editing.** The view is read-only; it does not respond to page saves.
- **No live updates.** No websocket, no polling, no "graph changed" banner. The snapshot
  is fixed for the session; a manual refresh button re-fetches on demand.
- **No pagination or virtualization for v1.** The view loads the entire graph in a single
  call. If the graph gets too large for modest client hardware we will constrain later.
- **No scale cap enforcement.** Designed for the current ~1k–3k node corpus. If the graph
  exceeds ~10k nodes we will revisit the library choice (see "Risks").
- **No "share this view" or URL state sync.** Clicking nodes does not update the URL;
  only `?focus=<name>` on entry is honoured.
- **No saved node positions across refreshes.** Force-directed layout runs fresh each
  load.
- **No admin entry point.** Accessible via the primary sidebar only. The existing admin
  Knowledge page does not gain a "View as graph" link.
- **No cluster detection, path-finding, or graph history.** Out of scope for v1.
- **No new backfill mechanism for v1.** See "Backfill verification" — v1 assumes the
  existing `GraphProjector` has been run over all pages and verifies this before shipping.
- **No anonymous access.** The endpoint requires an authenticated principal and returns
  401 otherwise.

## Background — why the knowledge graph is the right data source

Wikantik has two graph-shaped data sources. A prior investigation confirmed that the
knowledge graph is a **superset of the wiki link graph** and is therefore the correct
target for this visualization:

- `wikantik-main/src/main/java/com/wikantik/knowledge/GraphProjector.java` is registered
  as a `PageFilter` and runs on every `postSave`. For each saved page it:
  1. Upserts a knowledge-graph node for the page (line 73).
  2. Projects frontmatter relationships into typed edges (`related_to`, `part_of`, etc.)
     via `FrontmatterRelationshipDetector`.
  3. Extracts every wiki body link via `MarkdownLinkScanner.findLocalLinks()` and
     creates a `links_to` edge per link (lines 93–100).
  4. Diffs out stale human-authored edges.
  5. Creates stub nodes (line 112–116) for unresolved link targets.
- System pages (CSS themes, navigation fragments) are excluded from projection.

So every wiki link is represented as a `links_to` edge in the knowledge graph, every
page is a node, every unresolved link target is a stub node, and typed frontmatter
relationships are additional layered edges.

## User-Facing Behaviour

### Entry

- New route: `/graph`, lazy-loaded in `App.jsx` via `React.lazy` + `Suspense`, so
  cytoscape's bundle only downloads for users who visit.
- Single entry point: a new "Graph" link in the primary sidebar (`Sidebar.jsx`). The
  link's `href` is computed dynamically:
  - When the current route is a page view (e.g. `/wiki/SomePage`), the link is
    `/graph?focus=SomePage` (URL-encoded).
  - Otherwise the link is plain `/graph`.
- Browser-native "open in new tab" on the sidebar link gives users the "pop out to a
  tab" behaviour without a custom popout button.

### Initial focus

- On mount, `GraphView` reads `?focus=<name>` once from `useSearchParams()` and stores
  it in a ref. Later URL changes during the session are ignored.
- After the snapshot arrives:
  - **Match found and node is visible:** set selection, animate the camera to the node
    (`cy.animate({ center: { eles: node }, zoom: 1.2 })`), open the details drawer
    pre-populated.
  - **No match (either the name does not exist in the KG or it was redacted to a
    restricted node with a null name):** log a debug-level console warning, fall through
    to fit-to-view, no selection. This deliberately avoids "centering on a lock icon"
    which would hint at the page's existence.

### Default view

- Force-directed layout (`cose-bilkent`) with animation disabled for the initial
  positioning run (too slow for ~2k nodes).
- Node labels are hidden at zoom levels below ~0.6; the selected node and its immediate
  neighbors always show labels regardless of zoom.
- Bottom-right collapsible legend panel; top-left toolbar.

### Node encoding (loud, colour-coded)

| role | fill | border | label | size |
|---|---|---|---|---|
| `hub` (degree ≥ `hubDegreeThreshold`) | `#059669` emerald-600 | none | name | `mapData(degreeTotal, 0, hubThreshold×2, 8, 22)` |
| `normal` | `#94a3b8` slate-400 | none | name | by degree, min 6px |
| `orphan` (degree 0) | `#f59e0b` amber-500 | none | name | fixed 7px |
| `stub` (unresolved link target, `sourcePage == null`) | `#fff1f2` rose-50 | `#dc2626` red-600, 2px dashed | name | fixed 7px |
| `restricted` (ACL denies current viewer) | `#e5e7eb` gray-200 | `#9ca3af` gray-400, 1px solid | lock glyph `🔒` only | fixed 8px |

Role classification is computed **on the backend** and shipped in the response; the
client does not re-walk the graph for aggregates.

`hubDegreeThreshold = max(10, p95(degree across all nodes))`. The threshold is returned
in the payload and displayed in the legend (e.g. *"hub (≥12 connections)"*).

### Edge encoding

| `relationshipType` | stroke | width | notes |
|---|---|---|---|
| `links_to` | `#94a3b8` slate-400 | 1px | wiki-link skeleton, intentionally muted |
| `related_to` | `#2563eb` blue-600 | 2px | |
| `part_of` | `#7c3aed` violet-600 | 2px | |
| other known types | deterministic hash → [cyan, pink, teal, orange, lime, fuchsia, indigo] | 2px | same type always gets the same colour across refreshes |
| `.dimmed` class | inherited colour @ 20% opacity | unchanged | applied during ego-highlight |

**Directionality:** default `target-arrow-shape: triangle` (one-way, reads as `→`).
When `graph-data.js` detects that both A→B and B→A exist with the **same**
`relationshipType`, the pair is collapsed into one client-side edge with
`data.bidirectional = true`, which the stylesheet renders with both
`source-arrow-shape` and `target-arrow-shape` set — reads as `↔`. An A→B `links_to`
paired with a B→A `related_to` does **not** merge (different types, different edges);
they render as two curved parallel edges with different colours.

### Interactions

- **Hover:** lightweight tooltip with the node's name. No extra fetch; name is from the
  already-loaded payload.
- **Click node:** select + open right-side details drawer + add the `.dimmed` class to
  every element not in the closed neighborhood, causing everything outside the ego
  network to drop to 20% opacity.
- **Click background / press Escape:** clear selection, close drawer, remove `.dimmed`.
- **Click an edge row in the drawer:** re-select the neighbour at the other end. The
  drawer becomes a walking mechanism, not just a display.
- **Restricted neighbour edge rows** in the drawer render as `🔒 (restricted)` and are
  not clickable.
- **"Open page →" button** in the drawer: routes to the existing wiki page view for the
  selected node. Disabled for stub and restricted nodes.
- **URL stays stable.** Clicking nodes does not update the address bar.

### Toolbar (top overlay on the canvas)

- **Fit to view** — resets zoom/pan to show the whole graph.
- **Edge-type filter** — popover listing every distinct `relationshipType` in the
  snapshot with checkboxes. Unchecking hides edges of that type. All types start
  checked.
- **"Only orphans & stubs"** — quick-filter button for task B. Hides all other nodes
  and all edges; re-click to restore.
- **Re-fetch snapshot** — re-calls `/api/knowledge/graph`. Preserves the `?focus=`
  param and, where possible, preserves the current selection.
- **Snapshot timestamp** — tiny text, always visible: *"snapshot: 14:32:07"*.

### Legend (bottom-right, collapsible)

Static, rendered as HTML (not SVG), contains:

- Node role list with colours and labels, including the live `hubDegreeThreshold`.
- Edge type list generated from the snapshot's edge palette.
- Directionality convention (`→` one-way, `↔` bidirectional).
- Snapshot timestamp (duplicated from toolbar for visibility at the bottom of the
  canvas).

### Empty, error, and stale states

| state | trigger | content |
|---|---|---|
| Loading | route enter → snapshot arrives | full-viewport spinner with message; after ~2s adds "Still working — large graphs can take a few seconds." |
| Empty | `nodeCount === 0` | "The knowledge graph is empty." + [Refresh] button |
| Empty-for-you | all returned nodes have `restricted: true` | "You don't have permission to view any pages in the knowledge graph." No refresh button. |
| 401 Unauthorized | anonymous caller | "Sign in to view the knowledge graph." + [Sign in] button → `/login?return=/graph` |
| 403 Forbidden | logged-in but denied | "You don't have permission to view the knowledge graph." No retry. |
| 5xx / network error | server or transport error | "The graph service is unavailable right now." + [Try again] button |
| Malformed JSON | unparseable payload | "Graph snapshot was invalid. Check server logs." + [Try again] button |
| Layout running | `cose-bilkent` stabilizing | canvas visible, overlay "Laying out graph…", controls disabled |
| Layout timeout | layout doesn't stabilize within 15s | force-stop, show warning toast "Layout took too long, display may be suboptimal", enable controls anyway |
| Stale | snapshot is older than "now" | timestamp in toolbar + manual refresh button; no automatic action |
| Uncaught render error | any runtime exception in the view tree | `GraphErrorBoundary` renders a clean fallback with reload link; logs via existing telemetry |

After manual refresh:
- If the previously selected node's name still exists in the new snapshot, selection
  and drawer are preserved.
- Otherwise selection clears and a toast fires: *"The previously selected node is no
  longer in the graph."*
- Positions are re-computed; they do not survive a refresh.

## Architecture

```
┌────────────────────────────┐        ┌─────────────────────────────┐
│  React SPA                 │        │  Wikantik backend           │
│                            │        │                             │
│  Sidebar.jsx               │        │                             │
│    └─ link to /graph       │        │                             │
│       ?focus=<currentPage> │        │                             │
│                            │        │                             │
│  Routes (App.jsx)          │        │                             │
│    /graph → GraphView.jsx  │        │                             │
│    (React.lazy)            │        │                             │
│                            │        │                             │
│  GraphView.jsx             │ ─GET─► │ KnowledgeGraphResource      │
│    • reads ?focus once     │        │   (new HttpServlet, mapped  │
│    • fetches snapshot      │        │    at /api/knowledge/graph, │
│    • owns: data, selection,│        │    outside AdminAuthFilter) │
│      drawer state          │        │                             │
│      │                     │        │ GET /api/knowledge/graph    │
│      ▼                     │        │   • svc.snapshotGraph()     │
│                            │        │   • ACL redaction           │
│  GraphCanvas.jsx            │        │                             │
│    (react-cytoscapejs)     │        │ DefaultKnowledgeGraphService│
│      │                     │        │   + snapshotGraph(viewer)   │
│      ▼                     │        │   + 60s in-memory cache     │
│  GraphDetailsDrawer.jsx    │        │                             │
│  GraphLegend.jsx           │        │ GraphRoleClassifier          │
│  GraphToolbar.jsx          │        │   (new pure helper)         │
│  GraphErrorState.jsx       │        │                             │
│  GraphErrorBoundary.jsx    │        │ JdbcKnowledgeRepository      │
│                            │        │   + listAllNodes()          │
│  graph-style.js            │        │   + listAllEdges()          │
│  graph-data.js             │        │                             │
│  graph.css                 │        │                             │
└────────────────────────────┘        └─────────────────────────────┘
```

### Design invariants

1. **Server-side classification.** `role`, `degreeIn`, `degreeOut`, and
   `hubDegreeThreshold` are computed once on the backend. The client never re-walks the
   graph to compute aggregates; it only transforms the response into cytoscape
   elements.
2. **`GraphView` owns state; `GraphCanvas` is presentational.** The canvas receives
   data + selection as props and emits events. Cytoscape is quarantined behind this
   single file so it can be replaced later without touching the rest.
3. **Route-level code splitting.** Cytoscape is only loaded when `/graph` is visited.
4. **Same topology for everyone.** ACL redaction replaces sensitive fields on restricted
   nodes but keeps node IDs and edges intact, so the graph shape is consistent across
   users and the public-view cache is valid for all callers.

## Backend — endpoint and service

### New REST endpoint

```
GET /api/knowledge/graph
Accept: application/json
```

- No query parameters.
- Served by a **new** `KnowledgeGraphResource` servlet (a subclass of `RestServletBase`,
  matching the pattern used by `BacklinksResource`, `ListPagesResource`, etc.) mapped at
  `/api/knowledge/graph`. `AdminAuthFilter` is scoped to `/admin/*`, so the new endpoint
  is automatically outside its scope — no exemption needed. Anonymous callers get `401
  Unauthorized` from an explicit session check in `doGet`.

### Response — 200 OK

```json
{
  "generatedAt": "2026-04-11T14:32:00Z",
  "nodeCount": 1847,
  "edgeCount": 6913,
  "hubDegreeThreshold": 12,
  "nodes": [
    {
      "id": "b2f1...",
      "name": "KnowledgeGraphsAndManagement",
      "type": "page",
      "role": "hub",
      "provenance": "HUMAN_AUTHORED",
      "sourcePage": "KnowledgeGraphsAndManagement",
      "degreeIn": 23,
      "degreeOut": 19,
      "restricted": false
    },
    {
      "id": "ccaa...",
      "name": "UnresolvedTargetName",
      "type": "stub",
      "role": "stub",
      "provenance": "HUMAN_AUTHORED",
      "sourcePage": null,
      "degreeIn": 2,
      "degreeOut": 0,
      "restricted": false
    },
    {
      "id": "deff...",
      "name": null,
      "type": null,
      "role": "restricted",
      "provenance": null,
      "sourcePage": null,
      "degreeIn": 1,
      "degreeOut": 3,
      "restricted": true
    }
  ],
  "edges": [
    {
      "id": "e001",
      "source": "b2f1...",
      "target": "ccaa...",
      "relationshipType": "links_to",
      "provenance": "HUMAN_AUTHORED"
    }
  ]
}
```

Field notes:

- `role` ∈ `{hub, normal, orphan, stub, restricted}`. Computed server-side by
  `GraphRoleClassifier`.
- `restricted` always true implies `role == "restricted"` and `name == null`,
  `type == null`, `provenance == null`, `sourcePage == null`.
- Edges involving restricted nodes are returned with the restricted node's UUID intact.
- Edge IDs are stable across calls (they come from the underlying `KgEdge` rows).
- Node IDs are stable UUIDs.

### Response size (current scale)

- ~1800 nodes × ~180 bytes + ~7000 edges × ~140 bytes ≈ 1.3 MB uncompressed, ~250 KB
  gzipped.
- Acceptable for a single snapshot call.

### Service interface addition

`wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java`:

```java
GraphSnapshot snapshotGraph( Principal viewer );
```

New records (also in `wikantik-api/src/main/java/com/wikantik/api/knowledge/`):

```java
public record GraphSnapshot(
    Instant generatedAt,
    int nodeCount,
    int edgeCount,
    int hubDegreeThreshold,
    List<SnapshotNode> nodes,
    List<SnapshotEdge> edges
) {}

public record SnapshotNode(
    UUID id,
    String name,
    String type,
    String role,
    Provenance provenance,
    String sourcePage,
    int degreeIn,
    int degreeOut,
    boolean restricted
) {}

public record SnapshotEdge(
    UUID id,
    UUID source,
    UUID target,
    String relationshipType,
    Provenance provenance
) {}
```

### `DefaultKnowledgeGraphService.snapshotGraph` — implementation plan

1. **Reject anonymous callers.** `if (viewer == null) throw new AnonymousAccessDeniedException()` — the resource converts this to 401.
2. **Read all nodes and edges** via two new bulk repository methods
   `JdbcKnowledgeRepository.listAllNodes()` and `listAllEdges()`. Single SELECT each,
   no joins, ordered by id for determinism.
3. **Build in-memory degree maps** in one pass over the edge list:
   `Map<UUID, int[]> degrees` where `int[0]` is `degreeIn` and `int[1]` is `degreeOut`.
4. **Compute `hubDegreeThreshold`** as `max(10, p95(degreeTotal))` using a simple
   sort-and-index on `degreeIn + degreeOut` for all nodes.
5. **For each node, classify role** via `GraphRoleClassifier.classify(node, degrees,
   hubThreshold, sourcePage)`. Restricted takes precedence, then stub, then orphan,
   then hub, then normal.
6. **ACL check each node** against the viewer via whatever `PageManager` uses for
   `/api/page/{name}` read permission. If the viewer cannot view the backing page (or
   the node has no backing page and is not a stub — edge case — default to visible),
   produce a redacted `SnapshotNode` with `name=null, type=null, provenance=null,
   sourcePage=null, role="restricted", restricted=true` but with degrees preserved.
   Stub nodes (`sourcePage==null`) are always visible.
7. **Emit the edge list unchanged** — edges are not themselves ACL-checked; they inherit
   visibility from their endpoints via the redaction pattern. This is why the topology
   remains stable across users.
8. **Cache the unredacted snapshot** (step 5 output, before step 6) in an in-memory
   holder with a 60-second TTL. Invalidate on any `upsertNode`/`upsertEdge`/`deleteNode`
   /`deleteEdge`/`mergeNodes`/`clearAll` call. Per-request redaction runs on the cached
   snapshot for each caller.

### `GraphRoleClassifier` (new pure helper)

```java
public final class GraphRoleClassifier {
    public static String classify( KgNode node, int degreeIn, int degreeOut,
                                    int hubThreshold, boolean restricted ) {
        if ( restricted ) return "restricted";
        if ( node.sourcePage() == null ) return "stub";
        if ( degreeIn + degreeOut == 0 ) return "orphan";
        if ( degreeIn + degreeOut >= hubThreshold ) return "hub";
        return "normal";
    }
}
```

Pure function, trivially testable without the service or repository.

### `KnowledgeGraphResource` wiring

Wikantik's REST layer is servlet-based, not JAX-RS. The new resource extends
`RestServletBase` and uses its helpers (`getEngine`, `sendJson`, `sendError`) to stay
consistent with every other `/api/*` servlet in `wikantik-rest`:

```java
public class KnowledgeGraphResource extends RestServletBase {

    private static final Logger LOG = LogManager.getLogger( KnowledgeGraphResource.class );

    @Override
    protected void doGet( final HttpServletRequest request,
                          final HttpServletResponse response ) throws IOException {
        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );
        if ( session == null || session.isAnonymous() ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED,
                       "Authentication required" );
            return;
        }
        try {
            final KnowledgeGraphService svc =
                    engine.getManager( KnowledgeGraphService.class );
            final GraphSnapshot snapshot =
                    svc.snapshotGraph( session.getUserPrincipal() );
            sendJson( response, snapshot );
        } catch ( final Exception e ) {
            LOG.warn( "Failed to build knowledge-graph snapshot", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                       "Failed to build graph snapshot" );
        }
    }
}
```

Registered in `wikantik-war/src/main/webapp/WEB-INF/web.xml` with a `<servlet>` entry
pointing at `com.wikantik.rest.KnowledgeGraphResource` and a `<servlet-mapping>` of
`/api/knowledge/graph`. Because `AdminAuthFilter` is mapped to `/admin/*`, the new
endpoint is automatically outside its scope and no filter change is needed — the only
auth check that matters is the explicit `session.isAnonymous()` guard above.

## Frontend — components and data flow

### New files

```
wikantik-frontend/src/components/graph/
  GraphView.jsx                  route component, state owner
  GraphCanvas.jsx                react-cytoscapejs wrapper; presentation only
  GraphDetailsDrawer.jsx         right-side drawer; presentation
  GraphLegend.jsx                bottom-right legend; presentation
  GraphToolbar.jsx               top overlay controls; presentation
  GraphErrorState.jsx            variant-driven error / empty states
  GraphErrorBoundary.jsx         React class component, catches render errors
  GraphLoadingFallback.jsx       Suspense fallback + fetch-loading reuse
  graph-style.js                 cytoscape stylesheet
  graph-data.js                  pure transform + bidirectional merge
  graph.css                      layout/spacing only
```

### Edits to existing files

- `wikantik-frontend/src/api/client.js` — add `api.knowledge.getGraphSnapshot()` that
  calls `GET /api/knowledge/graph` and returns parsed JSON, routing error shapes through
  the existing client error plumbing.
- `wikantik-frontend/src/main.jsx` (or `App.jsx`, whichever holds the routes) — add the
  lazy-loaded `/graph` route wrapped in a `Suspense` boundary with
  `<GraphLoadingFallback />`.
- `wikantik-frontend/src/components/Sidebar.jsx` — add the "Graph" link with a dynamic
  `href` computed from `useLocation()`. Extract a helper like
  `extractPageNameFromPath(location.pathname)` if one does not already exist.

### `GraphView.jsx` state ownership

```
GraphView state:
  ├─ fetchState: 'idle' | 'loading' | 'ready' | 'error'
  ├─ snapshot:   GraphSnapshot | null
  ├─ elements:   memoized cytoscape elements array, derived from snapshot
  ├─ selectedId: node UUID | null
  ├─ edgeTypeFilter: Set<string> | null   (null = all)
  ├─ onlyAnomalies: boolean                (the "only orphans & stubs" quick-filter)
  └─ focusParam: string | null             (read once on mount via ref)
```

Effects:

1. **Mount:** call `api.knowledge.getGraphSnapshot()`. On success, store snapshot and
   transform into elements via `graph-data.js`. Transition `fetchState`. On error, set
   `fetchState = 'error'` with the variant.
2. **Snapshot ready + focusParam present:** look up the node by name, set `selectedId`,
   emit a camera-center instruction to the canvas on `onReady`.
3. **Snapshot ready + no focusParam:** emit fit-to-view on `onReady`.

### `GraphCanvas.jsx` — cytoscape quarantine

```jsx
<CytoscapeComponent
  elements={elements}
  stylesheet={graphStyle}
  layout={{ name: 'cose-bilkent', randomize: true, animate: false, quality: 'default' }}
  cy={cy => { cyRef.current = cy; attachHandlers(cy); }}
/>
```

Props in:
- `elements` — transformed node/edge list
- `selectedId` — currently selected node (or null)
- `edgeTypeFilter` — set of hidden relationship types
- `onlyAnomalies` — quick-filter boolean

Events out:
- `onNodeClick(nodeId)`
- `onBackgroundClick()`
- `onReady()`
- `onLayoutTimeout()`

Ego-highlight is a `useEffect` that watches `selectedId`:

```js
useEffect(() => {
  if (!cyRef.current) return;
  const cy = cyRef.current;
  cy.elements().removeClass('dimmed');
  if (!selectedId) return;
  const selected = cy.getElementById(selectedId);
  const neighborhood = selected.closedNeighborhood();
  cy.elements().not(neighborhood).addClass('dimmed');
}, [selectedId]);
```

Edge-type filtering and anomalies-only filtering are similar `useEffect`s that toggle a
`hidden` class on filtered elements, which the stylesheet maps to `display: none`.

### `GraphDetailsDrawer.jsx` — pure presentation

Props: `{ selectedNode, incidentEdges, onClose, onSelectNeighbor, onOpenPage }`.

Contents (only fields already in the payload):

```
┌─────────────────────────────────────┐
│  SomePage                    [ X ]  │
│  role: hub · type: page             │
│  provenance: HUMAN_AUTHORED         │
├─────────────────────────────────────┤
│  Connections: 23 in · 19 out        │
├─────────────────────────────────────┤
│  Incoming (23)                      │
│    ← related_to   AnotherHub        │
│    ← links_to     SomePage2         │
│    ← links_to     SomePage3         │
│    ...                              │
│  Outgoing (19)                      │
│    → links_to     SomePage4         │
│    → part_of      WikantikHub       │
│    ...                              │
├─────────────────────────────────────┤
│  [ Open page → ]                    │
└─────────────────────────────────────┘
```

Restricted neighbour rows render as `🔒 (restricted)` and are not clickable. The
"Open page →" button is disabled for stubs and restricted nodes. Clicking any
non-restricted neighbour row invokes `onSelectNeighbor(neighborId)`, which the view
treats identically to a canvas click — walking the graph via the drawer.

### `graph-data.js` — transformation

Two exports, both pure:

```js
export function toCytoscapeElements(snapshot) { ... }
export function mergeBidirectionalEdges(edges) { ... }
```

`toCytoscapeElements` produces `{ nodes: [...], edges: [...] }` in cytoscape's shape,
assigns each node a `class` matching its `role-*`, assigns each edge a `data.edgeColor`
from the palette, and calls `mergeBidirectionalEdges` to collapse matching pairs.

Palette:

```js
const KNOWN_PALETTE = {
  links_to:   '#94a3b8',
  related_to: '#2563eb',
  part_of:    '#7c3aed',
};
const FALLBACK_PALETTE = [
  '#06b6d4', '#ec4899', '#14b8a6', '#f97316',
  '#84cc16', '#d946ef', '#6366f1',
];
function colorFor(relationshipType) {
  if (KNOWN_PALETTE[relationshipType]) return KNOWN_PALETTE[relationshipType];
  const hash = stableHash(relationshipType);
  return FALLBACK_PALETTE[hash % FALLBACK_PALETTE.length];
}
```

`mergeBidirectionalEdges` walks the edge list once. For every `(source, target,
relationshipType)`, it checks if the reverse `(target, source, relationshipType)` is
also present. If so, both are collapsed into a single edge with `data.bidirectional =
true` (orientation uses the lexicographically smaller UUID as source, for stability).

### `graph-style.js` — cytoscape stylesheet

One exported array; selectors map to role classes, edge colours, and state classes.
Key selectors:

```js
{ selector: 'node', style: { ... base ... }}
{ selector: 'node.role-hub',        style: { 'background-color': '#059669' }}
{ selector: 'node.role-orphan',     style: { 'background-color': '#f59e0b' }}
{ selector: 'node.role-stub',       style: { 'background-color': '#fff1f2',
                                              'border-color': '#dc2626',
                                              'border-width': 2,
                                              'border-style': 'dashed' }}
{ selector: 'node.role-restricted', style: { 'background-color': '#e5e7eb',
                                              'border-color': '#9ca3af',
                                              'label': '🔒' }}
{ selector: 'edge', style: { 'width': 1, 'curve-style': 'bezier',
                              'line-color': 'data(edgeColor)',
                              'target-arrow-color': 'data(edgeColor)',
                              'target-arrow-shape': 'triangle' }}
{ selector: 'edge[?bidirectional]', style: { 'source-arrow-shape': 'triangle',
                                              'source-arrow-color': 'data(edgeColor)' }}
{ selector: '.dimmed', style: { 'opacity': 0.2 }}
{ selector: '.hidden', style: { 'display': 'none' }}
```

## Testing

Following CLAUDE.md's TDD preference: tests are written against the failing behaviour
first, then the implementation lands. Target >90% line coverage on all new code.

### Backend unit tests

`DefaultKnowledgeGraphServiceTest` — new `snapshotGraph()` tests:

- `snapshotGraph_emptyGraph_returnsEmptyCollections`
- `snapshotGraph_classifiesOrphanWithZeroDegree`
- `snapshotGraph_classifiesStubFromNullSourcePage`
- `snapshotGraph_classifiesHubAtOrAboveThreshold`
- `snapshotGraph_hubThresholdFloorIsTen`
- `snapshotGraph_degreeCountsBothDirections`
- `snapshotGraph_restrictedNodeBlanksName`
- `snapshotGraph_restrictedNodeEdgesStillReturned`
- `snapshotGraph_anonymousViewerRejected`
- `snapshotGraph_cacheHitWithinTtl`
- `snapshotGraph_cacheInvalidatedOnUpsert`

`GraphRoleClassifierTest` — pure function tests for every branch, no repository
dependency.

### Backend REST tests

`KnowledgeGraphResourceTest` — new servlet tests:

- `doGet_authenticated_returns200AndBody`
- `doGet_anonymous_returns401`
- `doGet_afterMutation_reflectsChange`
- `doGet_cacheHitWithinTtl_returnsSameGeneratedAt`
- `doGet_cacheInvalidatedOnUpsert_returnsFreshGeneratedAt`
- `doGet_serviceThrows_returns500AndLogsWarning`

### Frontend unit tests

`graph-data.test.js`:

- `toCytoscapeElements_mapsRoleToClass`
- `toCytoscapeElements_assignsEdgeColorFromPalette`
- `toCytoscapeElements_preservesRestrictedNodes`
- `mergeBidirectionalEdges_collapsesMatchingPairs`
- `mergeBidirectionalEdges_doesNotCollapseDifferentTypes`
- `mergeBidirectionalEdges_preservesSingletons`
- `mergeBidirectionalEdges_isStableAcrossOrdering`
- `edgePaletteRotation_isDeterministic`

Component tests (React Testing Library):

- `GraphDetailsDrawer.test.jsx` — fields render, edge lists render, edge-row click fires
  `onSelectNeighbor`, "Open page" disabled for stubs/restricted, close button, restricted
  neighbour rows are locked.
- `GraphLegend.test.jsx` — rendering, collapse toggle, dynamic `hubDegreeThreshold`,
  edge palette list.
- `GraphToolbar.test.jsx` — every button fires its callback, filter popover,
  quick-filter toggle, snapshot timestamp display.
- `GraphErrorState.test.jsx` — one test per variant.

Integration test:

- `GraphView.test.jsx` — mocked `api.knowledge.getGraphSnapshot()`:
  - Loads → loading → canvas on success
  - With `?focus=` that resolves → selects and centers
  - With `?focus=` that does not resolve → fit-to-view, no selection, debug log
  - With `?focus=` that points at a restricted node → treated as no-match
  - 401 → sign-in CTA
  - 5xx → retry CTA; retry re-calls endpoint
  - Empty snapshot → empty state
  - Everything-restricted → empty-for-you state

`GraphCanvas` itself is shallow-tested with a mocked `react-cytoscapejs` to verify
event wiring and class toggling. Real cytoscape interactions are covered by IT.

### Integration tests (Selenide)

New IT class `KnowledgeGraphVisualizationIT` in
`wikantik-it-tests/wikantik-selenide-tests/`:

- `graphView_loadsFullSnapshot`
- `graphView_focusParamSelectsNode`
- `graphView_clickNodeOpensDrawer`
- `graphView_restrictedNodeAppearsLocked`
- `graphView_sidebarLinkIncludesFocus`
- `graphView_anonymousRedirectedToLogin`

Test affordance: `window.cy` is exposed in dev/test builds only via
`import.meta.env.DEV`, letting Selenide drive cytoscape interactions directly.
Production builds do not expose this.

### Coverage expectations

- `DefaultKnowledgeGraphService.snapshotGraph` and `GraphRoleClassifier`: 100% line,
  100% branch.
- `graph-data.js`: 100% line, 100% branch.
- Frontend components: every rendered state path covered.
- `GraphCanvas` cytoscape wiring: exempt from unit coverage, covered at IT level.

## Backfill verification

`GraphProjector.postSave` runs only on saved pages. Any page that has not been saved
since the projector was installed will be missing its node and `links_to` edges from
the knowledge graph, which would cause the visualization to silently understate
reality — directly undermining task B (finding anomalies).

**Step 1 of the implementation plan must be to verify whether the knowledge graph has
been backfilled over the existing pages**:

- Search the repository for any existing backfill command, admin endpoint, migration,
  or one-shot tool that touches `graphProjector.projectPage()` across a page set.
- Query the KG directly against the ~992 pages in `docs/wikantik-pages/` and confirm
  node count matches (or is close enough, given system-page exclusions).
- If a backfill has been done and the KG is complete, document that in the plan and
  proceed.
- If the KG is missing pages, **the plan must add a separate deliverable** (a one-shot
  admin-only rebuild endpoint or offline tool) and ship it before the visualization.

This spec deliberately defers the backfill mechanism to the plan phase: we do not yet
know whether one is needed. The visualization itself cannot ship honestly without this
check.

## Risks

- **Scale ceiling.** cytoscape.js with `cose-bilkent` comfortably handles 3k-5k nodes
  on modest hardware, but performance degrades beyond that. If the graph crosses ~10k
  nodes in the future, migration to sigma.js / graphology on WebGL will become
  necessary. The cytoscape quarantine (`GraphCanvas` as the only file importing the
  library) makes this migration a localized change.
- **Bundle size.** cytoscape + `cose-bilkent` + `react-cytoscapejs` adds ~900 KB to the
  app bundle. Lazy-loading the route contains this to users who actually visit.
- **Layout non-determinism.** Force-directed layouts produce slightly different
  positions each run. This is intentional — position memory is out of scope — but
  users may experience mild "shifted" feelings on refresh. Acceptable for v1.
- **Restricted-node redaction correctness.** If the ACL check is wrong, the view
  either leaks page names (bad) or hides visible pages (annoying). Tests must cover
  both directions.
- **Single bulk endpoint latency.** At ~1.3 MB uncompressed the payload is substantial.
  If backend response times creep past ~2s, we will need to add gzip compression
  (should already be on at the servlet layer) or switch to a more compact binary
  encoding. Flagged for monitoring, not for v1 implementation.

## Out of scope for this spec but worth capturing for later

- Saving node positions across refreshes (requires persistent layout).
- "Copy link to this node" button in the drawer (produces a shareable
  `/graph?focus=<name>` URL).
- Path-finding between two selected nodes.
- Time-travel / graph history view.
- Cluster detection and community colouring as an alternate node-colour mode.
- Admin-page entry point with "View as graph" button.

## Files summary

**New (frontend):**
- `wikantik-frontend/src/components/graph/GraphView.jsx`
- `wikantik-frontend/src/components/graph/GraphCanvas.jsx`
- `wikantik-frontend/src/components/graph/GraphDetailsDrawer.jsx`
- `wikantik-frontend/src/components/graph/GraphLegend.jsx`
- `wikantik-frontend/src/components/graph/GraphToolbar.jsx`
- `wikantik-frontend/src/components/graph/GraphErrorState.jsx`
- `wikantik-frontend/src/components/graph/GraphErrorBoundary.jsx`
- `wikantik-frontend/src/components/graph/GraphLoadingFallback.jsx`
- `wikantik-frontend/src/components/graph/graph-style.js`
- `wikantik-frontend/src/components/graph/graph-data.js`
- `wikantik-frontend/src/components/graph/graph.css`

**New (backend):**
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/GraphSnapshot.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java`
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotEdge.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/GraphRoleClassifier.java`
- `wikantik-rest/src/main/java/com/wikantik/rest/KnowledgeGraphResource.java`

**New (tests):**
- `wikantik-main/src/test/java/com/wikantik/knowledge/GraphRoleClassifierTest.java`
- `wikantik-rest/src/test/java/com/wikantik/rest/KnowledgeGraphResourceTest.java`
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/KnowledgeGraphVisualizationIT.java`
- Frontend test files mirroring each new component.

**Edited (frontend):**
- `wikantik-frontend/src/api/client.js` (+ `api.knowledge.getGraphSnapshot`)
- `wikantik-frontend/src/main.jsx` or `App.jsx` (+ lazy `/graph` route)
- `wikantik-frontend/src/components/Sidebar.jsx` (+ Graph link with dynamic `?focus=`)

**Edited (backend):**
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java` (+ `snapshotGraph`)
- `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java` (+ implementation + cache)
- `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java` (+ `listAllNodes`, `listAllEdges`)
- `wikantik-war/src/main/webapp/WEB-INF/web.xml` (+ servlet registration and `/api/knowledge/graph` mapping)
- `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java` (+ new tests)
