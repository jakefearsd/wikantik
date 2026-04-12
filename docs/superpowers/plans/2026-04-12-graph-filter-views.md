# Graph Filter Views Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three view modes to the `/graph` visualization — Backbone, Communities, and Topic/Tag — built on a single client-side filter state with preset starting points, stackable refinements, URL-synced state, and mode-dependent hide/fade visuals.

**Architecture:** A small backend change extends `SnapshotNode` with `cluster`, `tags`, and `status` from `KgNode.properties`. The frontend introduces a pure `applyFilters(snapshot, state)` engine, a FilterPanel UI with preset pills and contextual controls, and URL query param sync. Filter output drives Cytoscape CSS classes (`hidden`, `faded`) without re-laying out the graph.

**Tech Stack:** Java 21 records, JUnit 5 + Testcontainers (Postgres), React 18 + Vitest + happy-dom, Cytoscape.js, react-router-dom, existing Selenide IT infrastructure.

**Spec:** `docs/superpowers/specs/2026-04-12-graph-filter-views-design.md`

---

## Task 1: Extend SnapshotNode DTO with cluster, tags, status

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java` (buildUnredactedSnapshot, redactForViewer)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java`

- [ ] **Step 1: Write the failing test** — append a new test to the existing file (after the last `snapshotGraph_*` test method):

```java
    @Test
    void snapshotGraph_exposesClusterTagsStatusFromProperties() throws Exception {
        service.upsertNode( "LinearAlgebra", "article", "LinearAlgebra.md",
                Provenance.HUMAN_AUTHORED, Map.of(
                        "cluster", "mathematics",
                        "tags", java.util.List.of( "linear-algebra", "vectors" ),
                        "status", "active" ) );
        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        final SnapshotNode node = snap.nodes().get( 0 );
        assertEquals( "mathematics", node.cluster() );
        assertEquals( java.util.List.of( "linear-algebra", "vectors" ), node.tags() );
        assertEquals( "active", node.status() );
    }

    @Test
    void snapshotGraph_nullPropertiesYieldNullClusterAndEmptyTags() throws Exception {
        service.upsertNode( "Orphan", "article", "Orphan.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        final SnapshotNode node = snap.nodes().get( 0 );
        assertNull( node.cluster() );
        assertTrue( node.tags().isEmpty() );
        assertNull( node.status() );
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceTest#snapshotGraph_exposesClusterTagsStatusFromProperties+snapshotGraph_nullPropertiesYieldNullClusterAndEmptyTags`
Expected: COMPILE FAIL — `cluster()`, `tags()`, `status()` not methods on `SnapshotNode`.

- [ ] **Step 3: Extend the `SnapshotNode` record**

Replace the contents of `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java` with:

```java
package com.wikantik.api.knowledge;

import java.util.List;
import java.util.UUID;

public record SnapshotNode(
    UUID id,
    String name,
    String type,
    String role,
    Provenance provenance,
    String sourcePage,
    int degreeIn,
    int degreeOut,
    boolean restricted,
    String cluster,
    List< String > tags,
    String status
) {}
```

- [ ] **Step 4: Update `buildUnredactedSnapshot` and `redactForViewer` in `DefaultKnowledgeGraphService`**

In `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`:

Replace the body of `buildUnredactedSnapshot` (the loop that constructs `SnapshotNode`). The lines that currently read:

```java
        final List< SnapshotNode > nodes = new ArrayList<>( allNodes.size() );
        for ( final KgNode node : allNodes ) {
            final int[] deg = degrees.getOrDefault( node.id(), new int[2] );
            final String role = GraphRoleClassifier.classify( node, deg[0], deg[1], hubThreshold, false );
            nodes.add( new SnapshotNode(
                    node.id(), node.name(), node.nodeType(), role,
                    node.provenance(), node.sourcePage(), deg[0], deg[1], false ) );
        }
```

become:

```java
        final List< SnapshotNode > nodes = new ArrayList<>( allNodes.size() );
        for ( final KgNode node : allNodes ) {
            final int[] deg = degrees.getOrDefault( node.id(), new int[2] );
            final String role = GraphRoleClassifier.classify( node, deg[0], deg[1], hubThreshold, false );
            nodes.add( new SnapshotNode(
                    node.id(), node.name(), node.nodeType(), role,
                    node.provenance(), node.sourcePage(), deg[0], deg[1], false,
                    propString( node, "cluster" ),
                    propStringList( node, "tags" ),
                    propString( node, "status" ) ) );
        }
```

Replace the redaction branch inside `redactForViewer` — the line that currently reads:

```java
                redacted.add( new SnapshotNode(
                        node.id(), null, null, "restricted", null, null,
                        node.degreeIn(), node.degreeOut(), true ) );
```

becomes:

```java
                redacted.add( new SnapshotNode(
                        node.id(), null, null, "restricted", null, null,
                        node.degreeIn(), node.degreeOut(), true,
                        null, List.of(), null ) );
```

Add two private helper methods near the end of the class (just above `invalidateSnapshotCache`):

```java
    private static String propString( final KgNode node, final String key ) {
        if ( node.properties() == null ) return null;
        final Object v = node.properties().get( key );
        return ( v == null ) ? null : v.toString();
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > propStringList( final KgNode node, final String key ) {
        if ( node.properties() == null ) return List.of();
        final Object v = node.properties().get( key );
        if ( v instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object item : list ) {
                if ( item instanceof String s ) out.add( s );
            }
            return List.copyOf( out );
        }
        return List.of();
    }
```

Add the import at the top if not already present:

```java
import java.util.List;
```

- [ ] **Step 5: Run the two new tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceTest`
Expected: all tests pass (existing + 2 new).

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java
git commit -m "feat(knowledge): expose cluster/tags/status in SnapshotNode DTO"
```

---

## Task 2: Frontend filter state model

**Files:**
- Create: `wikantik-frontend/src/components/graph/filter-state.js`
- Test: `wikantik-frontend/src/components/graph/filter-state.test.js`

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/components/graph/filter-state.test.js`:

```js
import { describe, it, expect } from 'vitest';
import {
  INITIAL_FILTER_STATE, PRESETS,
  applyPreset, toggleCluster, setTags, setEdgeTypeHidden,
} from './filter-state.js';

describe('filter-state', () => {
  it('initial state is preset=full with hide mode', () => {
    expect(INITIAL_FILTER_STATE.preset).toBe(PRESETS.FULL);
    expect(INITIAL_FILTER_STATE.visualMode).toBe('hide');
    expect(INITIAL_FILTER_STATE.clusters.size).toBe(0);
    expect(INITIAL_FILTER_STATE.tags.size).toBe(0);
  });

  it('applyPreset(BACKBONE) sets hubsOnly and hide mode', () => {
    const s = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    expect(s.preset).toBe(PRESETS.BACKBONE);
    expect(s.hubsOnly).toBe(true);
    expect(s.includeHubNeighbors).toBe(false);
    expect(s.visualMode).toBe('hide');
  });

  it('applyPreset(TAGS) sets fade mode and clears cluster/hubs', () => {
    const afterBackbone = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    const s = applyPreset(afterBackbone, PRESETS.TAGS);
    expect(s.preset).toBe(PRESETS.TAGS);
    expect(s.hubsOnly).toBe(false);
    expect(s.visualMode).toBe('fade');
  });

  it('applyPreset preserves orthogonal controls (edge types, orphan toggle)', () => {
    const base = setEdgeTypeHidden(INITIAL_FILTER_STATE, 'links_to', true);
    const s = applyPreset(base, PRESETS.BACKBONE);
    expect(s.hiddenEdgeTypes.has('links_to')).toBe(true);
  });

  it('toggleCluster adds then removes a cluster', () => {
    let s = toggleCluster(INITIAL_FILTER_STATE, 'mathematics');
    expect(s.clusters.has('mathematics')).toBe(true);
    s = toggleCluster(s, 'mathematics');
    expect(s.clusters.has('mathematics')).toBe(false);
  });

  it('setTags replaces the tags set', () => {
    const s = setTags(INITIAL_FILTER_STATE, ['optimization', 'simplex']);
    expect(s.tags.size).toBe(2);
    expect(s.tags.has('optimization')).toBe(true);
  });

  it('applyPreset(COMMUNITIES) hides when clusters selected, else hide stays but all visible', () => {
    const s = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    expect(s.preset).toBe(PRESETS.COMMUNITIES);
    expect(s.visualMode).toBe('hide');
    expect(s.clusters.size).toBe(0);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/filter-state.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `filter-state.js`**

Create `wikantik-frontend/src/components/graph/filter-state.js`:

```js
export const PRESETS = Object.freeze({
  FULL: 'full',
  BACKBONE: 'backbone',
  COMMUNITIES: 'communities',
  TAGS: 'tags',
});

export const INITIAL_FILTER_STATE = Object.freeze({
  preset: PRESETS.FULL,
  hubsOnly: false,
  includeHubNeighbors: false,
  clusters: new Set(),
  showUnclustered: true,
  tags: new Set(),
  types: new Set(),
  statuses: new Set(),
  searchText: '',
  hiddenEdgeTypes: new Set(),
  showOrphansStubs: true,
  visualMode: 'hide',
});

function clonePresetSlots(state) {
  return {
    hubsOnly: false,
    includeHubNeighbors: false,
    clusters: new Set(),
    showUnclustered: true,
    tags: new Set(),
    types: new Set(),
    statuses: new Set(),
    searchText: '',
    hiddenEdgeTypes: new Set(state.hiddenEdgeTypes),
    showOrphansStubs: state.showOrphansStubs,
  };
}

export function applyPreset(state, preset) {
  const base = { ...state, ...clonePresetSlots(state), preset };
  switch (preset) {
    case PRESETS.BACKBONE:
      return { ...base, hubsOnly: true, visualMode: 'hide' };
    case PRESETS.COMMUNITIES:
      return { ...base, visualMode: 'hide' };
    case PRESETS.TAGS:
      return { ...base, visualMode: 'fade' };
    case PRESETS.FULL:
    default:
      return { ...base, visualMode: 'hide' };
  }
}

export function toggleCluster(state, cluster) {
  const next = new Set(state.clusters);
  if (next.has(cluster)) next.delete(cluster);
  else next.add(cluster);
  return { ...state, clusters: next };
}

export function setClusters(state, clusters) {
  return { ...state, clusters: new Set(clusters) };
}

export function setTags(state, tags) {
  return { ...state, tags: new Set(tags) };
}

export function setTypes(state, types) {
  return { ...state, types: new Set(types) };
}

export function setStatuses(state, statuses) {
  return { ...state, statuses: new Set(statuses) };
}

export function setSearchText(state, text) {
  return { ...state, searchText: text };
}

export function setIncludeHubNeighbors(state, include) {
  return { ...state, includeHubNeighbors: include };
}

export function setShowUnclustered(state, show) {
  return { ...state, showUnclustered: show };
}

export function setShowOrphansStubs(state, show) {
  return { ...state, showOrphansStubs: show };
}

export function setEdgeTypeHidden(state, type, hidden) {
  const next = new Set(state.hiddenEdgeTypes);
  if (hidden) next.add(type);
  else next.delete(type);
  return { ...state, hiddenEdgeTypes: next };
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/filter-state.test.js`
Expected: all 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/graph/filter-state.js \
        wikantik-frontend/src/components/graph/filter-state.test.js
git commit -m "feat(graph): filter state model with presets and orthogonal controls"
```

---

## Task 3: Frontend filter engine (applyFilters)

**Files:**
- Create: `wikantik-frontend/src/components/graph/filter-engine.js`
- Test: `wikantik-frontend/src/components/graph/filter-engine.test.js`

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/components/graph/filter-engine.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { applyFilters } from './filter-engine.js';
import { INITIAL_FILTER_STATE, applyPreset, PRESETS, toggleCluster, setTags } from './filter-state.js';

function node(id, extra = {}) {
  return {
    id, name: `n${id}`, type: 'article', role: 'normal',
    provenance: 'HUMAN_AUTHORED', sourcePage: `n${id}.md`,
    degreeIn: 0, degreeOut: 0, restricted: false,
    cluster: null, tags: [], status: null, ...extra,
  };
}
function edge(id, source, target, rel = 'links_to') {
  return { id, source, target, relationshipType: rel, provenance: 'HUMAN_AUTHORED' };
}
function snap(nodes, edges = []) {
  return { nodes, edges, hubDegreeThreshold: 10 };
}

describe('applyFilters', () => {
  it('preset=full returns every node as visible, no fades', () => {
    const s = snap([node('a'), node('b')], [edge('e1', 'a', 'b')]);
    const r = applyFilters(s, INITIAL_FILTER_STATE);
    expect(r.visibleNodeIds).toEqual(new Set(['a', 'b']));
    expect(r.fadedNodeIds.size).toBe(0);
    expect(r.visibleEdgeIds).toEqual(new Set(['e1']));
  });

  it('preset=backbone keeps type:hub and role:hub, drops normals', () => {
    const s = snap([
      node('h1', { type: 'hub' }),
      node('h2', { role: 'hub' }),
      node('n1'),
    ]);
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.has('h1')).toBe(true);
    expect(r.visibleNodeIds.has('h2')).toBe(true);
    expect(r.visibleNodeIds.has('n1')).toBe(false);
  });

  it('backbone +1 hop includes direct neighbors of hubs', () => {
    const s = snap(
      [node('h', { role: 'hub' }), node('n1'), node('n2')],
      [edge('e1', 'h', 'n1')]
    );
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    state = { ...state, includeHubNeighbors: true };
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.has('h')).toBe(true);
    expect(r.visibleNodeIds.has('n1')).toBe(true);
    expect(r.visibleNodeIds.has('n2')).toBe(false);
  });

  it('edge hidden when either endpoint is not visible', () => {
    const s = snap(
      [node('h', { role: 'hub' }), node('n1')],
      [edge('e1', 'h', 'n1')]
    );
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    const r = applyFilters(s, state);
    expect(r.visibleEdgeIds.has('e1')).toBe(false);
  });

  it('communities: no selection = all visible, color map populated by cluster', () => {
    const s = snap([
      node('a', { cluster: 'math' }),
      node('b', { cluster: 'ops' }),
      node('c'),
    ]);
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.size).toBe(3);
    expect(r.nodeColor.get('a')).toBeTruthy();
    expect(r.nodeColor.get('b')).toBeTruthy();
    expect(r.nodeColor.get('a')).not.toBe(r.nodeColor.get('b'));
  });

  it('communities: cluster isolation hides other clusters', () => {
    const s = snap([
      node('a', { cluster: 'math' }),
      node('b', { cluster: 'ops' }),
      node('c'),
    ]);
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    state = toggleCluster(state, 'math');
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.has('a')).toBe(true);
    expect(r.visibleNodeIds.has('b')).toBe(false);
    expect(r.visibleNodeIds.has('c')).toBe(false);
  });

  it('communities: showUnclustered toggle includes null-cluster nodes when isolating', () => {
    const s = snap([
      node('a', { cluster: 'math' }),
      node('c'),
    ]);
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    state = toggleCluster(state, 'math');
    state = { ...state, showUnclustered: true };
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.has('c')).toBe(true);

    const state2 = { ...state, showUnclustered: false };
    const r2 = applyFilters(s, state2);
    expect(r2.visibleNodeIds.has('c')).toBe(false);
  });

  it('tags: OR within tag facet, fade non-matching', () => {
    const s = snap([
      node('a', { tags: ['optimization'] }),
      node('b', { tags: ['vectors'] }),
      node('c', { tags: ['optimization', 'simplex'] }),
    ]);
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    state = setTags(state, ['optimization']);
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.size).toBe(3); // fade mode — all still visible
    expect(r.fadedNodeIds.has('b')).toBe(true);
    expect(r.fadedNodeIds.has('a')).toBe(false);
  });

  it('tags: AND across facets (tags AND type)', () => {
    const s = snap([
      node('a', { tags: ['opt'], type: 'hub' }),
      node('b', { tags: ['opt'], type: 'article' }),
    ]);
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    state = setTags(state, ['opt']);
    state = { ...state, types: new Set(['hub']) };
    const r = applyFilters(s, state);
    expect(r.fadedNodeIds.has('a')).toBe(false);
    expect(r.fadedNodeIds.has('b')).toBe(true);
  });

  it('tags: search text fades non-matching names (case-insensitive substring)', () => {
    const s = snap([node('a', { name: 'Warehouse' }), node('b', { name: 'Logistics' })]);
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    state = { ...state, searchText: 'ware' };
    const r = applyFilters(s, state);
    expect(r.fadedNodeIds.has('a')).toBe(false);
    expect(r.fadedNodeIds.has('b')).toBe(true);
  });

  it('edges fade when either endpoint is faded (tags preset)', () => {
    const s = snap(
      [node('a', { tags: ['x'] }), node('b', { tags: ['y'] })],
      [edge('e1', 'a', 'b')]
    );
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    state = setTags(state, ['x']);
    const r = applyFilters(s, state);
    expect(r.fadedEdgeIds.has('e1')).toBe(true);
  });

  it('hidden edge types remove edges even when endpoints visible', () => {
    const s = snap(
      [node('a'), node('b')],
      [edge('e1', 'a', 'b', 'links_to')]
    );
    const state = { ...INITIAL_FILTER_STATE, hiddenEdgeTypes: new Set(['links_to']) };
    const r = applyFilters(s, state);
    expect(r.visibleEdgeIds.has('e1')).toBe(false);
  });

  it('orphan/stub toggle hides role=orphan and role=stub when off', () => {
    const s = snap([
      node('n'),
      node('o', { role: 'orphan' }),
      node('st', { role: 'stub' }),
    ]);
    const state = { ...INITIAL_FILTER_STATE, showOrphansStubs: false };
    const r = applyFilters(s, state);
    expect(r.visibleNodeIds.has('n')).toBe(true);
    expect(r.visibleNodeIds.has('o')).toBe(false);
    expect(r.visibleNodeIds.has('st')).toBe(false);
  });

  it('focusNodeId overrides filter exclusion (pin)', () => {
    const s = snap([node('n1'), node('n2')]);
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    const r = applyFilters(s, state, 'n1');
    expect(r.visibleNodeIds.has('n1')).toBe(true);
  });

  it('handles missing frontmatter fields without crashing', () => {
    const s = snap([{ id: 'x', name: 'X', restricted: false }]);
    const r = applyFilters(s, INITIAL_FILTER_STATE);
    expect(r.visibleNodeIds.has('x')).toBe(true);
  });

  it('returns empty sets on empty input', () => {
    const r = applyFilters({ nodes: [], edges: [] }, INITIAL_FILTER_STATE);
    expect(r.visibleNodeIds.size).toBe(0);
    expect(r.visibleEdgeIds.size).toBe(0);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/filter-engine.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `filter-engine.js`**

Create `wikantik-frontend/src/components/graph/filter-engine.js`:

```js
import { PRESETS } from './filter-state.js';

const CLUSTER_PALETTE = [
  '#2563eb', '#dc2626', '#059669', '#d97706', '#7c3aed',
  '#db2777', '#0891b2', '#65a30d', '#ea580c', '#4f46e5',
];

function stableHash(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

function colorForCluster(cluster) {
  if (!cluster) return '#94a3b8';
  return CLUSTER_PALETTE[stableHash(cluster) % CLUSTER_PALETTE.length];
}

function isHub(node) {
  return node.type === 'hub' || node.role === 'hub';
}

function nodeTags(node) {
  return Array.isArray(node.tags) ? node.tags : [];
}

function matchesTagsFacet(node, state) {
  if (state.tags.size === 0) return true;
  const t = nodeTags(node);
  for (const tag of t) if (state.tags.has(tag)) return true;
  return false;
}

function matchesTypeFacet(node, state) {
  if (state.types.size === 0) return true;
  return state.types.has(node.type);
}

function matchesStatusFacet(node, state) {
  if (state.statuses.size === 0) return true;
  return node.status && state.statuses.has(node.status);
}

function matchesSearch(node, state) {
  if (!state.searchText) return true;
  const name = (node.name || '').toLowerCase();
  return name.includes(state.searchText.toLowerCase());
}

function matchesAllTagFacets(node, state) {
  return matchesTagsFacet(node, state)
      && matchesTypeFacet(node, state)
      && matchesStatusFacet(node, state)
      && matchesSearch(node, state);
}

function computeBackboneVisible(snapshot, state) {
  const visible = new Set();
  for (const n of snapshot.nodes) if (isHub(n)) visible.add(n.id);
  if (state.includeHubNeighbors) {
    for (const e of snapshot.edges) {
      if (visible.has(e.source)) visible.add(e.target);
      if (visible.has(e.target)) visible.add(e.source);
    }
  }
  return visible;
}

function computeCommunitiesVisible(snapshot, state) {
  if (state.clusters.size === 0) {
    return new Set(snapshot.nodes.map(n => n.id));
  }
  const visible = new Set();
  for (const n of snapshot.nodes) {
    if (n.cluster && state.clusters.has(n.cluster)) visible.add(n.id);
    else if (!n.cluster && state.showUnclustered) visible.add(n.id);
  }
  return visible;
}

export function applyFilters(snapshot, state, focusNodeId = null) {
  const allIds = new Set(snapshot.nodes.map(n => n.id));
  let visibleNodeIds;
  const fadedNodeIds = new Set();
  const nodeColor = new Map();

  // 1) Preset-level visibility
  switch (state.preset) {
    case PRESETS.BACKBONE:
      visibleNodeIds = computeBackboneVisible(snapshot, state);
      break;
    case PRESETS.COMMUNITIES:
      visibleNodeIds = computeCommunitiesVisible(snapshot, state);
      break;
    case PRESETS.TAGS:
    case PRESETS.FULL:
    default:
      visibleNodeIds = new Set(allIds);
      break;
  }

  // 2) Orthogonal filter: orphan/stub toggle
  if (!state.showOrphansStubs) {
    for (const n of snapshot.nodes) {
      if (n.role === 'orphan' || n.role === 'stub') visibleNodeIds.delete(n.id);
    }
  }

  // 3) Focus pin — always include focus node
  if (focusNodeId && allIds.has(focusNodeId)) {
    visibleNodeIds.add(focusNodeId);
  }

  // 4) Fade pass — only in fade mode (Tags preset)
  if (state.visualMode === 'fade') {
    for (const n of snapshot.nodes) {
      if (!visibleNodeIds.has(n.id)) continue;
      if (!matchesAllTagFacets(n, state)) fadedNodeIds.add(n.id);
    }
  }

  // 5) Cluster coloring — always populate when communities preset active or clusters selected
  if (state.preset === PRESETS.COMMUNITIES || state.clusters.size > 0) {
    for (const n of snapshot.nodes) {
      if (visibleNodeIds.has(n.id)) nodeColor.set(n.id, colorForCluster(n.cluster));
    }
  }

  // 6) Edges
  const hiddenEdgeTypes = state.hiddenEdgeTypes;
  const visibleEdgeIds = new Set();
  const fadedEdgeIds = new Set();
  for (const e of snapshot.edges) {
    if (hiddenEdgeTypes.has(e.relationshipType)) continue;
    const srcVis = visibleNodeIds.has(e.source);
    const tgtVis = visibleNodeIds.has(e.target);
    if (!srcVis || !tgtVis) continue;
    visibleEdgeIds.add(e.id);
    if (fadedNodeIds.has(e.source) || fadedNodeIds.has(e.target)) fadedEdgeIds.add(e.id);
  }

  return { visibleNodeIds, fadedNodeIds, visibleEdgeIds, fadedEdgeIds, nodeColor };
}

export { colorForCluster };
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/filter-engine.test.js`
Expected: all 16 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/graph/filter-engine.js \
        wikantik-frontend/src/components/graph/filter-engine.test.js
git commit -m "feat(graph): pure applyFilters engine for client-side view modes"
```

---

## Task 4: Wire filter output into Cytoscape element classes

**Files:**
- Modify: `wikantik-frontend/src/components/graph/graph-data.js`
- Modify: `wikantik-frontend/src/components/graph/graph-data.test.js`
- Modify: `wikantik-frontend/src/components/graph/graph-style.js`

- [ ] **Step 1: Add a failing test to `graph-data.test.js`**

Append to `wikantik-frontend/src/components/graph/graph-data.test.js`:

```js
import { describe as describe2, it as it2, expect as expect2 } from 'vitest';
import { toCytoscapeElements as toEls } from './graph-data.js';

describe2('toCytoscapeElements with filter output', () => {
  const snapshot = {
    nodes: [
      { id: 'a', name: 'A', type: 'article', role: 'normal', restricted: false, cluster: 'math', tags: [], status: 'active' },
      { id: 'b', name: 'B', type: 'hub', role: 'hub', restricted: false, cluster: 'math', tags: [], status: 'active' },
    ],
    edges: [
      { id: 'e1', source: 'a', target: 'b', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' },
    ],
  };

  it2('applies hidden class to nodes not in visibleNodeIds', () => {
    const filter = {
      visibleNodeIds: new Set(['b']),
      fadedNodeIds: new Set(),
      visibleEdgeIds: new Set(),
      fadedEdgeIds: new Set(),
      nodeColor: new Map(),
    };
    const { nodes } = toEls(snapshot, filter);
    const nodeA = nodes.find(n => n.data.id === 'a');
    expect2(nodeA.classes).toContain('hidden');
  });

  it2('applies faded class to nodes in fadedNodeIds', () => {
    const filter = {
      visibleNodeIds: new Set(['a', 'b']),
      fadedNodeIds: new Set(['a']),
      visibleEdgeIds: new Set(['e1']),
      fadedEdgeIds: new Set(),
      nodeColor: new Map(),
    };
    const { nodes } = toEls(snapshot, filter);
    const nodeA = nodes.find(n => n.data.id === 'a');
    expect2(nodeA.classes).toContain('faded');
  });

  it2('attaches clusterColor data from nodeColor map', () => {
    const filter = {
      visibleNodeIds: new Set(['a', 'b']),
      fadedNodeIds: new Set(),
      visibleEdgeIds: new Set(['e1']),
      fadedEdgeIds: new Set(),
      nodeColor: new Map([['a', '#123456']]),
    };
    const { nodes } = toEls(snapshot, filter);
    const nodeA = nodes.find(n => n.data.id === 'a');
    expect2(nodeA.data.clusterColor).toBe('#123456');
  });

  it2('with no filter argument, behaves as before (all visible, no new classes)', () => {
    const { nodes } = toEls(snapshot);
    const nodeA = nodes.find(n => n.data.id === 'a');
    expect2(nodeA.classes).not.toContain('hidden');
    expect2(nodeA.classes).not.toContain('faded');
  });
});
```

- [ ] **Step 2: Run tests — new cases fail**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/graph-data.test.js`
Expected: new test cases FAIL.

- [ ] **Step 3: Update `toCytoscapeElements`**

In `wikantik-frontend/src/components/graph/graph-data.js`, replace the `toCytoscapeElements` function (lines 77–116) with:

```js
export function toCytoscapeElements(snapshot, filter) {
  const visibleNodeIds = filter?.visibleNodeIds ?? null;
  const fadedNodeIds = filter?.fadedNodeIds ?? new Set();
  const visibleEdgeIds = filter?.visibleEdgeIds ?? null;
  const fadedEdgeIds = filter?.fadedEdgeIds ?? new Set();
  const nodeColor = filter?.nodeColor ?? new Map();

  const nodes = snapshot.nodes.map(n => {
    const hidden = visibleNodeIds !== null && !visibleNodeIds.has(n.id);
    const faded = fadedNodeIds.has(n.id);
    const classList = [`role-${n.role}`];
    if (hidden) classList.push('hidden');
    if (faded) classList.push('faded');
    const clusterColor = nodeColor.get(n.id);
    return {
      data: {
        id: n.id,
        name: n.name,
        type: n.type,
        role: n.role,
        provenance: n.provenance,
        sourcePage: n.sourcePage,
        degreeIn: n.degreeIn,
        degreeOut: n.degreeOut,
        restricted: n.restricted,
        label: n.restricted ? '\u{1F512}' : (n.name || ''),
        cluster: n.cluster ?? null,
        tags: n.tags ?? [],
        status: n.status ?? null,
        ...(clusterColor ? { clusterColor } : {}),
      },
      classes: classList.join(' '),
    };
  });

  const bidiMerged = mergeBidirectionalEdges(snapshot.edges);
  const parallelMerged = mergeParallelEdges(bidiMerged);

  const edges = parallelMerged.map(e => {
    const baseClass = e.relationshipTypes.length > 1 ? 'composite' : '';
    const hidden = visibleEdgeIds !== null && !visibleEdgeIds.has(e.id);
    const faded = fadedEdgeIds.has(e.id);
    const classList = baseClass ? [baseClass] : [];
    if (hidden) classList.push('hidden');
    if (faded) classList.push('faded');
    return {
      classes: classList.join(' '),
      data: {
        id: e.relationshipTypes.length > 1
          ? e.relationshipTypes.join('-') + '-' + e.source + '-' + e.target
          : e.id + (e.bidirectional ? '-bidi' : ''),
        source: e.source,
        target: e.target,
        relationshipType: e.relationshipTypes[0],
        relationshipTypes: e.relationshipTypes,
        edgeLabel: e.relationshipTypes.join(' \u00B7 '),
        provenance: e.provenance,
        edgeColor: colorFor(e.relationshipTypes[0]),
        bidirectional: e.bidirectional || false,
        compositeWidth: e.relationshipTypes.length > 1 ? 2 : 1,
      },
    };
  });

  return { nodes, edges };
}
```

- [ ] **Step 4: Add Cytoscape style rules for `hidden`, `faded`, and cluster color**

Read the current `wikantik-frontend/src/components/graph/graph-style.js` to find the style-rules array, then append these three rules to it (if the file exports an array named `GRAPH_STYLE`, push onto it; otherwise add an entry consistent with its existing pattern):

```js
  {
    selector: 'node.hidden, edge.hidden',
    style: { 'display': 'none' },
  },
  {
    selector: 'node.faded',
    style: { 'opacity': 0.15 },
  },
  {
    selector: 'edge.faded',
    style: { 'opacity': 0.08 },
  },
  {
    selector: 'node[clusterColor]',
    style: { 'border-color': 'data(clusterColor)', 'border-width': 3 },
  },
```

(If the file structure differs, keep the selectors identical and adapt to the local idiom. Do not change unrelated styles.)

- [ ] **Step 5: Run tests**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/graph-data.test.js`
Expected: all tests PASS (original + 4 new).

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/graph/graph-data.js \
        wikantik-frontend/src/components/graph/graph-data.test.js \
        wikantik-frontend/src/components/graph/graph-style.js
git commit -m "feat(graph): Cytoscape classes for hidden/faded filter states"
```

---

## Task 5: FilterPanel component (presets + contextual controls + chips)

**Files:**
- Create: `wikantik-frontend/src/components/graph/FilterPanel.jsx`
- Create: `wikantik-frontend/src/components/graph/FilterPanel.test.jsx`
- Modify: `wikantik-frontend/src/components/graph/graph.css` (minor — panel layout)

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/components/graph/FilterPanel.test.jsx`:

```jsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import FilterPanel from './FilterPanel.jsx';
import { INITIAL_FILTER_STATE, PRESETS, applyPreset } from './filter-state.js';

const snapshot = {
  nodes: [
    { id: 'a', cluster: 'math', tags: ['opt'], type: 'article', status: 'active' },
    { id: 'b', cluster: 'ops', tags: ['simplex'], type: 'hub', status: 'active' },
    { id: 'c', cluster: null, tags: [], type: 'article', status: 'draft' },
  ],
  edges: [],
};

describe('FilterPanel', () => {
  it('renders four preset buttons', () => {
    render(<FilterPanel state={INITIAL_FILTER_STATE} snapshot={snapshot} onChange={() => {}} />);
    expect(screen.getByRole('button', { name: /full/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /backbone/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /communities/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /tags/i })).toBeInTheDocument();
  });

  it('clicking Backbone calls onChange with backbone preset state', () => {
    const onChange = vi.fn();
    render(<FilterPanel state={INITIAL_FILTER_STATE} snapshot={snapshot} onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: /backbone/i }));
    expect(onChange).toHaveBeenCalled();
    const next = onChange.mock.calls[0][0];
    expect(next.preset).toBe(PRESETS.BACKBONE);
    expect(next.hubsOnly).toBe(true);
  });

  it('in Backbone preset, shows +1 hop toggle', () => {
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    render(<FilterPanel state={state} snapshot={snapshot} onChange={() => {}} />);
    expect(screen.getByLabelText(/\+1 hop neighbors/i)).toBeInTheDocument();
  });

  it('in Communities preset, lists each cluster with node count', () => {
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    render(<FilterPanel state={state} snapshot={snapshot} onChange={() => {}} />);
    expect(screen.getByText(/math/i)).toBeInTheDocument();
    expect(screen.getByText(/ops/i)).toBeInTheDocument();
    expect(screen.getByText(/unclustered/i)).toBeInTheDocument();
  });

  it('clicking a cluster in Communities preset toggles it in state', () => {
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    const onChange = vi.fn();
    render(<FilterPanel state={state} snapshot={snapshot} onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: /math/i }));
    const next = onChange.mock.calls[0][0];
    expect(next.clusters.has('math')).toBe(true);
  });

  it('in Tags preset, shows tag checkboxes derived from snapshot with counts', () => {
    const state = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    render(<FilterPanel state={state} snapshot={snapshot} onChange={() => {}} />);
    expect(screen.getByLabelText(/opt/)).toBeInTheDocument();
    expect(screen.getByLabelText(/simplex/)).toBeInTheDocument();
  });

  it('shows active chip for each selected cluster and removes on click', () => {
    let state = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    state = { ...state, clusters: new Set(['math']) };
    const onChange = vi.fn();
    render(<FilterPanel state={state} snapshot={snapshot} onChange={onChange} />);
    const chip = screen.getByRole('button', { name: /cluster: math ×/i });
    fireEvent.click(chip);
    const next = onChange.mock.calls[0][0];
    expect(next.clusters.has('math')).toBe(false);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/FilterPanel.test.jsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `FilterPanel.jsx`**

Create `wikantik-frontend/src/components/graph/FilterPanel.jsx`:

```jsx
import { useMemo } from 'react';
import {
  PRESETS, applyPreset, toggleCluster, setTags, setTypes, setStatuses,
  setSearchText, setIncludeHubNeighbors, setShowUnclustered,
} from './filter-state.js';
import { colorForCluster } from './filter-engine.js';

function deriveClusters(snapshot) {
  const counts = new Map();
  let unclustered = 0;
  for (const n of snapshot.nodes) {
    if (n.cluster) counts.set(n.cluster, (counts.get(n.cluster) ?? 0) + 1);
    else unclustered += 1;
  }
  const entries = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  return { entries, unclustered };
}

function deriveTags(snapshot) {
  const counts = new Map();
  for (const n of snapshot.nodes) {
    for (const t of (n.tags || [])) counts.set(t, (counts.get(t) ?? 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

function deriveTypes(snapshot) {
  const counts = new Map();
  for (const n of snapshot.nodes) if (n.type) counts.set(n.type, (counts.get(n.type) ?? 0) + 1);
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

function deriveStatuses(snapshot) {
  const counts = new Map();
  for (const n of snapshot.nodes) if (n.status) counts.set(n.status, (counts.get(n.status) ?? 0) + 1);
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

function ChipRow({ state, onChange }) {
  const chips = [];
  for (const c of state.clusters) {
    chips.push({ key: `cluster:${c}`, label: `Cluster: ${c}`, onRemove: () => onChange(toggleCluster(state, c)) });
  }
  for (const t of state.tags) {
    chips.push({ key: `tag:${t}`, label: `Tag: ${t}`, onRemove: () => {
      const next = new Set(state.tags); next.delete(t); onChange(setTags(state, [...next]));
    }});
  }
  for (const ty of state.types) {
    chips.push({ key: `type:${ty}`, label: `Type: ${ty}`, onRemove: () => {
      const next = new Set(state.types); next.delete(ty); onChange(setTypes(state, [...next]));
    }});
  }
  for (const st of state.statuses) {
    chips.push({ key: `status:${st}`, label: `Status: ${st}`, onRemove: () => {
      const next = new Set(state.statuses); next.delete(st); onChange(setStatuses(state, [...next]));
    }});
  }
  if (state.searchText) {
    chips.push({ key: 'search', label: `Search: ${state.searchText}`, onRemove: () => onChange(setSearchText(state, '')) });
  }
  if (chips.length === 0) return null;
  return (
    <div className="filter-chips">
      {chips.map(c => (
        <button key={c.key} type="button" className="filter-chip" onClick={c.onRemove}>
          {c.label} ×
        </button>
      ))}
    </div>
  );
}

export default function FilterPanel({ state, snapshot, onChange }) {
  const clusters = useMemo(() => deriveClusters(snapshot), [snapshot]);
  const tags = useMemo(() => deriveTags(snapshot), [snapshot]);
  const types = useMemo(() => deriveTypes(snapshot), [snapshot]);
  const statuses = useMemo(() => deriveStatuses(snapshot), [snapshot]);

  const presetButton = (preset, label) => (
    <button
      type="button"
      className={`filter-preset-pill ${state.preset === preset ? 'active' : ''}`}
      onClick={() => onChange(applyPreset(state, preset))}
    >
      {label}
    </button>
  );

  return (
    <div className="filter-panel">
      <div className="filter-preset-row">
        {presetButton(PRESETS.FULL, 'Full')}
        {presetButton(PRESETS.BACKBONE, 'Backbone')}
        {presetButton(PRESETS.COMMUNITIES, 'Communities')}
        {presetButton(PRESETS.TAGS, 'Tags')}
      </div>

      {state.preset === PRESETS.BACKBONE && (
        <div className="filter-section">
          <label>
            <input
              type="checkbox"
              checked={state.includeHubNeighbors}
              onChange={e => onChange(setIncludeHubNeighbors(state, e.target.checked))}
            />
            +1 hop neighbors
          </label>
          <div className="filter-caption">
            Hubs = author-marked (<code>type: hub</code>) OR top-5% degree
          </div>
        </div>
      )}

      {state.preset === PRESETS.COMMUNITIES && (
        <div className="filter-section">
          <div className="cluster-legend">
            {clusters.entries.map(([name, count]) => (
              <button
                key={name}
                type="button"
                className={`cluster-legend-item ${state.clusters.has(name) ? 'active' : ''}`}
                onClick={() => onChange(toggleCluster(state, name))}
              >
                <span className="cluster-swatch" style={{ background: colorForCluster(name) }} />
                <span className="cluster-name">{name}</span>
                <span className="cluster-count">{count}</span>
              </button>
            ))}
            <label className="cluster-unclustered">
              <input
                type="checkbox"
                checked={state.showUnclustered}
                onChange={e => onChange(setShowUnclustered(state, e.target.checked))}
              />
              Show unclustered ({clusters.unclustered})
            </label>
          </div>
        </div>
      )}

      {state.preset === PRESETS.TAGS && (
        <div className="filter-section">
          <div className="tag-picker">
            {tags.map(([tag, count]) => (
              <label key={tag} className="tag-option">
                <input
                  type="checkbox"
                  checked={state.tags.has(tag)}
                  onChange={e => {
                    const next = new Set(state.tags);
                    if (e.target.checked) next.add(tag); else next.delete(tag);
                    onChange(setTags(state, [...next]));
                  }}
                />
                {tag} ({count})
              </label>
            ))}
          </div>
          <div className="facet-row">
            <select
              value=""
              onChange={e => {
                if (!e.target.value) return;
                const next = new Set(state.types); next.add(e.target.value);
                onChange(setTypes(state, [...next]));
              }}
            >
              <option value="">+ type…</option>
              {types.map(([t, c]) => (<option key={t} value={t}>{t} ({c})</option>))}
            </select>
            <select
              value=""
              onChange={e => {
                if (!e.target.value) return;
                const next = new Set(state.statuses); next.add(e.target.value);
                onChange(setStatuses(state, [...next]));
              }}
            >
              <option value="">+ status…</option>
              {statuses.map(([s, c]) => (<option key={s} value={s}>{s} ({c})</option>))}
            </select>
            <input
              type="text"
              placeholder="Search names…"
              value={state.searchText}
              onChange={e => onChange(setSearchText(state, e.target.value))}
            />
          </div>
        </div>
      )}

      <ChipRow state={state} onChange={onChange} />
    </div>
  );
}
```

- [ ] **Step 4: Add minimal CSS**

Append to `wikantik-frontend/src/components/graph/graph.css`:

```css
.filter-panel { padding: 8px; border-bottom: 1px solid #e5e7eb; }
.filter-preset-row { display: flex; gap: 4px; margin-bottom: 8px; }
.filter-preset-pill {
  padding: 4px 10px; border: 1px solid #d1d5db; border-radius: 999px;
  background: #fff; cursor: pointer; font-size: 12px;
}
.filter-preset-pill.active { background: #2563eb; color: #fff; border-color: #2563eb; }
.filter-section { margin-top: 6px; font-size: 12px; }
.filter-caption { color: #6b7280; font-size: 11px; margin-top: 4px; }
.cluster-legend { display: flex; flex-direction: column; gap: 2px; }
.cluster-legend-item {
  display: flex; align-items: center; gap: 6px; padding: 2px 4px;
  background: none; border: none; cursor: pointer; text-align: left;
}
.cluster-legend-item.active { background: #eff6ff; }
.cluster-swatch { width: 12px; height: 12px; border-radius: 2px; display: inline-block; }
.cluster-name { flex: 1; }
.cluster-count { color: #6b7280; }
.tag-picker { display: flex; flex-wrap: wrap; gap: 4px 10px; max-height: 160px; overflow-y: auto; }
.tag-option { font-size: 12px; display: inline-flex; gap: 4px; align-items: center; }
.facet-row { display: flex; gap: 4px; margin-top: 6px; }
.facet-row select, .facet-row input { font-size: 12px; padding: 2px 4px; }
.filter-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }
.filter-chip {
  font-size: 11px; padding: 2px 6px; border: 1px solid #d1d5db; border-radius: 999px;
  background: #f3f4f6; cursor: pointer;
}
```

- [ ] **Step 5: Run tests**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/FilterPanel.test.jsx`
Expected: all 7 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/graph/FilterPanel.jsx \
        wikantik-frontend/src/components/graph/FilterPanel.test.jsx \
        wikantik-frontend/src/components/graph/graph.css
git commit -m "feat(graph): FilterPanel UI with presets, contextual controls, chips"
```

---

## Task 6: URL sync for filter state

**Files:**
- Create: `wikantik-frontend/src/components/graph/filter-url.js`
- Create: `wikantik-frontend/src/components/graph/filter-url.test.js`

- [ ] **Step 1: Write the failing test**

Create `wikantik-frontend/src/components/graph/filter-url.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { filterStateToParams, paramsToFilterState } from './filter-url.js';
import { INITIAL_FILTER_STATE, applyPreset, setTags, toggleCluster, PRESETS } from './filter-state.js';

describe('filter-url', () => {
  it('round-trips backbone preset with +1 hop', () => {
    let s = applyPreset(INITIAL_FILTER_STATE, PRESETS.BACKBONE);
    s = { ...s, includeHubNeighbors: true };
    const params = filterStateToParams(s);
    expect(params.get('preset')).toBe('backbone');
    expect(params.get('hop')).toBe('1');
    const restored = paramsToFilterState(params);
    expect(restored.preset).toBe(PRESETS.BACKBONE);
    expect(restored.includeHubNeighbors).toBe(true);
  });

  it('round-trips communities with multiple clusters', () => {
    let s = applyPreset(INITIAL_FILTER_STATE, PRESETS.COMMUNITIES);
    s = toggleCluster(s, 'math');
    s = toggleCluster(s, 'ops');
    const params = filterStateToParams(s);
    expect(params.get('cluster')).toBe('math,ops');
    const restored = paramsToFilterState(params);
    expect(restored.clusters.has('math')).toBe(true);
    expect(restored.clusters.has('ops')).toBe(true);
  });

  it('round-trips tags and search', () => {
    let s = applyPreset(INITIAL_FILTER_STATE, PRESETS.TAGS);
    s = setTags(s, ['optimization', 'simplex']);
    s = { ...s, searchText: 'warehouse' };
    const params = filterStateToParams(s);
    expect(params.get('tags')).toBe('optimization,simplex');
    expect(params.get('search')).toBe('warehouse');
    const restored = paramsToFilterState(params);
    expect(restored.tags.has('optimization')).toBe(true);
    expect(restored.searchText).toBe('warehouse');
  });

  it('preserves unrelated params (focus)', () => {
    const input = new URLSearchParams();
    input.set('focus', 'LinearAlgebra');
    input.set('preset', 'backbone');
    const restored = paramsToFilterState(input);
    const params = filterStateToParams(restored, input);
    expect(params.get('focus')).toBe('LinearAlgebra');
    expect(params.get('preset')).toBe('backbone');
  });

  it('returns initial-equivalent state for empty params', () => {
    const restored = paramsToFilterState(new URLSearchParams());
    expect(restored.preset).toBe(PRESETS.FULL);
    expect(restored.clusters.size).toBe(0);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/filter-url.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `filter-url.js`**

Create `wikantik-frontend/src/components/graph/filter-url.js`:

```js
import { INITIAL_FILTER_STATE, applyPreset, PRESETS } from './filter-state.js';

const PRESERVED_KEYS = ['focus'];
const MANAGED_KEYS = ['preset', 'hop', 'cluster', 'unclustered', 'tags', 'type', 'status', 'search'];

function asCsv(set) {
  return [...set].join(',');
}
function fromCsv(str) {
  return str ? str.split(',').filter(Boolean) : [];
}

export function filterStateToParams(state, existing = new URLSearchParams()) {
  const params = new URLSearchParams();
  for (const key of PRESERVED_KEYS) {
    const v = existing.get(key);
    if (v != null) params.set(key, v);
  }
  if (state.preset !== PRESETS.FULL) params.set('preset', state.preset);
  if (state.includeHubNeighbors) params.set('hop', '1');
  if (state.clusters.size > 0) params.set('cluster', asCsv(state.clusters));
  if (!state.showUnclustered) params.set('unclustered', '0');
  if (state.tags.size > 0) params.set('tags', asCsv(state.tags));
  if (state.types.size > 0) params.set('type', asCsv(state.types));
  if (state.statuses.size > 0) params.set('status', asCsv(state.statuses));
  if (state.searchText) params.set('search', state.searchText);
  return params;
}

export function paramsToFilterState(params) {
  const presetParam = params.get('preset');
  const preset = Object.values(PRESETS).includes(presetParam) ? presetParam : PRESETS.FULL;
  let s = applyPreset(INITIAL_FILTER_STATE, preset);
  if (params.get('hop') === '1') s = { ...s, includeHubNeighbors: true };
  const clusters = fromCsv(params.get('cluster'));
  if (clusters.length) s = { ...s, clusters: new Set(clusters) };
  if (params.get('unclustered') === '0') s = { ...s, showUnclustered: false };
  const tags = fromCsv(params.get('tags'));
  if (tags.length) s = { ...s, tags: new Set(tags) };
  const types = fromCsv(params.get('type'));
  if (types.length) s = { ...s, types: new Set(types) };
  const statuses = fromCsv(params.get('status'));
  if (statuses.length) s = { ...s, statuses: new Set(statuses) };
  const search = params.get('search');
  if (search) s = { ...s, searchText: search };
  return s;
}

export { MANAGED_KEYS, PRESERVED_KEYS };
```

- [ ] **Step 4: Run tests**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/filter-url.test.js`
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/graph/filter-url.js \
        wikantik-frontend/src/components/graph/filter-url.test.js
git commit -m "feat(graph): URL sync for filter state (parse/serialize)"
```

---

## Task 7: Wire FilterPanel + URL sync into GraphView

**Files:**
- Modify: `wikantik-frontend/src/components/graph/GraphView.jsx`
- Modify: `wikantik-frontend/src/components/graph/GraphView.test.jsx`

- [ ] **Step 1: Add a failing test**

Append to `wikantik-frontend/src/components/graph/GraphView.test.jsx` (read it first to match existing imports/render helpers — if the file doesn't exist, skip this step's test and rely on Task 8's IT):

```jsx
import { describe as d2, it as i2, expect as e2, vi as v2 } from 'vitest';
import { render as r2, screen as s2, fireEvent as f2, waitFor as w2 } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import GraphView from './GraphView.jsx';
import { api } from '../../api/client';

d2('GraphView with FilterPanel', () => {
  const snap = {
    generatedAt: new Date().toISOString(), nodeCount: 2, edgeCount: 1,
    hubDegreeThreshold: 10,
    nodes: [
      { id: 'a', name: 'A', type: 'hub', role: 'hub', restricted: false, cluster: 'math', tags: [], status: 'active', degreeIn: 0, degreeOut: 1 },
      { id: 'b', name: 'B', type: 'article', role: 'normal', restricted: false, cluster: 'math', tags: [], status: 'active', degreeIn: 1, degreeOut: 0 },
    ],
    edges: [{ id: 'e1', source: 'a', target: 'b', relationshipType: 'links_to', provenance: 'HUMAN_AUTHORED' }],
  };

  i2('renders FilterPanel and writes preset param to URL on preset click', async () => {
    v2.spyOn(api.knowledge, 'getGraphSnapshot').mockResolvedValue(snap);
    const replace = v2.spyOn(window.history, 'replaceState');
    r2(<MemoryRouter initialEntries={['/graph']}><GraphView /></MemoryRouter>);
    await w2(() => s2.getByRole('button', { name: /backbone/i }));
    f2.click(s2.getByRole('button', { name: /backbone/i }));
    expect(replace).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run the test (it will fail because FilterPanel isn't wired yet)**

Run: `cd wikantik-frontend && npx vitest run src/components/graph/GraphView.test.jsx`
Expected: FAIL.

- [ ] **Step 3: Update `GraphView.jsx` to hold filter state and render FilterPanel**

Read the current `GraphView.jsx`. Replace its body with the updated version:

```jsx
import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import { toCytoscapeElements } from './graph-data.js';
import { applyFilters } from './filter-engine.js';
import { paramsToFilterState, filterStateToParams } from './filter-url.js';
import FilterPanel from './FilterPanel.jsx';
import GraphCanvas from './GraphCanvas.jsx';
import GraphToolbar from './GraphToolbar.jsx';
import GraphLegend from './GraphLegend.jsx';
import GraphZoomSlider from './GraphZoomSlider.jsx';
import GraphDetailsDrawer from './GraphDetailsDrawer.jsx';
import GraphErrorState from './GraphErrorState.jsx';
import GraphErrorBoundary from './GraphErrorBoundary.jsx';
import GraphLoadingFallback from './GraphLoadingFallback.jsx';
import { setEdgeTypeHidden, setShowOrphansStubs } from './filter-state.js';
import './graph.css';

export default function GraphView() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const focusParam = useRef(searchParams.get('focus'));

  const [fetchState, setFetchState] = useState('loading');
  const [errorVariant, setErrorVariant] = useState(null);
  const [snapshot, setSnapshot] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [filterState, setFilterState] = useState(() => paramsToFilterState(searchParams));
  const [layoutDone, setLayoutDone] = useState(false);

  // Serialize filter state → URL on every change (replaceState, no nav)
  useEffect(() => {
    const next = filterStateToParams(filterState, new URLSearchParams(window.location.search));
    const qs = next.toString();
    const url = qs ? `${window.location.pathname}?${qs}` : window.location.pathname;
    window.history.replaceState(null, '', url);
  }, [filterState]);

  const fetchSnapshot = useCallback(async () => {
    setFetchState('loading');
    setErrorVariant(null);
    try {
      const data = await api.knowledge.getGraphSnapshot();
      setSnapshot(data);
      if (data.nodeCount === 0) {
        setFetchState('error'); setErrorVariant('empty');
      } else if (data.nodes.every(n => n.restricted)) {
        setFetchState('error'); setErrorVariant('empty-for-you');
      } else {
        setFetchState('ready');
      }
    } catch (err) {
      setFetchState('error');
      if (err.status === 401) setErrorVariant('unauthorized');
      else if (err.status === 403) setErrorVariant('forbidden');
      else setErrorVariant('server');
    }
  }, []);

  useEffect(() => { fetchSnapshot(); }, [fetchSnapshot]);

  const focusNodeId = useMemo(() => {
    if (!focusParam.current || !snapshot) return null;
    const match = snapshot.nodes.find(n => n.name === focusParam.current && !n.restricted);
    return match?.id || null;
  }, [snapshot]);

  const filterResult = useMemo(() => {
    if (!snapshot || fetchState !== 'ready') return null;
    return applyFilters(snapshot, filterState, focusNodeId);
  }, [snapshot, fetchState, filterState, focusNodeId]);

  const elements = useMemo(() => {
    if (!snapshot || fetchState !== 'ready') return { nodes: [], edges: [] };
    return toCytoscapeElements(snapshot, filterResult);
  }, [snapshot, fetchState, filterResult]);

  const edgeTypes = useMemo(() => {
    if (!snapshot) return [];
    return [...new Set(snapshot.edges.map(e => e.relationshipType))].sort();
  }, [snapshot]);

  const timestamp = useMemo(() => {
    if (!snapshot?.generatedAt) return '';
    try { return new Date(snapshot.generatedAt).toLocaleTimeString(); }
    catch { return snapshot.generatedAt; }
  }, [snapshot]);

  const selectedNode = useMemo(() => {
    if (!selectedId || !snapshot) return null;
    return snapshot.nodes.find(n => n.id === selectedId) || null;
  }, [selectedId, snapshot]);

  const incidentEdges = useMemo(() => {
    if (!selectedId || !snapshot) return [];
    const nodeMap = new Map(snapshot.nodes.map(n => [n.id, n]));
    return snapshot.edges
      .filter(e => e.source === selectedId || e.target === selectedId)
      .map(e => {
        const isIncoming = e.target === selectedId;
        const neighborId = isIncoming ? e.source : e.target;
        const neighbor = nodeMap.get(neighborId);
        return {
          ...e,
          direction: isIncoming ? 'in' : 'out',
          neighborId,
          neighborName: neighbor?.name || null,
          neighborRestricted: neighbor?.restricted || false,
        };
      });
  }, [selectedId, snapshot]);

  const handleNodeClick = useCallback((nodeId) => setSelectedId(nodeId), []);
  const handleBackgroundClick = useCallback(() => setSelectedId(null), []);
  const handleReady = useCallback(() => setLayoutDone(true), []);
  const handleOpenPage = useCallback((pageName) => navigate(`/wiki/${encodeURIComponent(pageName)}`), [navigate]);

  const handleToggleEdgeType = useCallback((type) => {
    setFilterState(prev => setEdgeTypeHidden(prev, type, !prev.hiddenEdgeTypes.has(type)));
  }, []);

  const handleToggleOrphans = useCallback(() => {
    setFilterState(prev => setShowOrphansStubs(prev, !prev.showOrphansStubs));
  }, []);

  const handleRefresh = useCallback(async () => {
    const prevSelectedName = selectedNode?.name;
    setFetchState('loading'); setErrorVariant(null);
    try {
      const data = await api.knowledge.getGraphSnapshot();
      setSnapshot(data);
      if (data.nodeCount === 0) { setFetchState('error'); setErrorVariant('empty'); return; }
      if (data.nodes.every(n => n.restricted)) { setFetchState('error'); setErrorVariant('empty-for-you'); return; }
      setFetchState('ready');
      if (prevSelectedName) {
        const match = data.nodes.find(n => n.name === prevSelectedName);
        setSelectedId(match ? match.id : null);
      }
    } catch (err) {
      setFetchState('error');
      if (err.status === 401) setErrorVariant('unauthorized');
      else if (err.status === 403) setErrorVariant('forbidden');
      else setErrorVariant('server');
    }
  }, [selectedNode]);

  useEffect(() => {
    const handler = (e) => { if (e.key === 'Escape') setSelectedId(null); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  if (fetchState === 'loading') return <GraphLoadingFallback />;
  if (fetchState === 'error') return <GraphErrorState variant={errorVariant} onRetry={fetchSnapshot} />;

  const noVisibleNodes = filterResult && filterResult.visibleNodeIds.size === 0;

  return (
    <GraphErrorBoundary>
      <div className="graph-view">
        <FilterPanel state={filterState} snapshot={snapshot} onChange={setFilterState} />
        <GraphToolbar
          onFitToView={() => window.cy?.fit()}
          onRefresh={handleRefresh}
          onToggleAnomalies={handleToggleOrphans}
          onToggleEdgeType={handleToggleEdgeType}
          edgeTypes={edgeTypes}
          hiddenEdgeTypes={filterState.hiddenEdgeTypes}
          onlyAnomalies={!filterState.showOrphansStubs}
          timestamp={timestamp}
        />
        <GraphCanvas
          elements={elements}
          selectedId={selectedId}
          focusNodeId={focusNodeId}
          hiddenEdgeTypes={filterState.hiddenEdgeTypes}
          onlyAnomalies={!filterState.showOrphansStubs}
          onNodeClick={handleNodeClick}
          onBackgroundClick={handleBackgroundClick}
          onReady={handleReady}
          onLayoutTimeout={() => console.warn('Layout took too long')}
        />
        {selectedNode && (
          <GraphDetailsDrawer
            selectedNode={selectedNode}
            incidentEdges={incidentEdges}
            onClose={() => setSelectedId(null)}
            onSelectNeighbor={handleNodeClick}
            onOpenPage={handleOpenPage}
          />
        )}
        <div className="graph-bottom-right">
          <GraphZoomSlider layoutDone={layoutDone} />
          <GraphLegend
            hubDegreeThreshold={snapshot?.hubDegreeThreshold || 10}
            edgeTypes={edgeTypes}
            timestamp={timestamp}
          />
        </div>
        {noVisibleNodes && (
          <div className="graph-empty-overlay">
            No matches —{' '}
            <button type="button" onClick={() => setFilterState(paramsToFilterState(new URLSearchParams()))}>
              clear filters
            </button>
          </div>
        )}
        {!layoutDone && (
          <div className="graph-layout-overlay">Laying out graph...</div>
        )}
      </div>
    </GraphErrorBoundary>
  );
}
```

- [ ] **Step 4: Add empty-overlay CSS**

Append to `wikantik-frontend/src/components/graph/graph.css`:

```css
.graph-empty-overlay {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
  background: rgba(255,255,255,0.95); padding: 12px 16px;
  border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px;
}
.graph-empty-overlay button {
  background: none; border: none; color: #2563eb; cursor: pointer; text-decoration: underline;
}
```

- [ ] **Step 5: Run the Vitest suite**

Run: `cd wikantik-frontend && npx vitest run`
Expected: all tests PASS. If existing GraphView.test.jsx tests break because of new FilterPanel markup or onlyAnomalies semantics, update them minimally — the change is: `onlyAnomalies` now means "show only anomalies" via `!showOrphansStubs`, which is the same meaning as before. If existing tests inspect state in ways that no longer apply, update them to check filterState instead.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/graph/GraphView.jsx \
        wikantik-frontend/src/components/graph/GraphView.test.jsx \
        wikantik-frontend/src/components/graph/graph.css
git commit -m "feat(graph): wire FilterPanel + URL sync into GraphView"
```

---

## Task 8: Selenide integration test

**Files:**
- Create: `wikantik-it-test-custom/src/test/java/com/wikantik/it/GraphFilterViewsIT.java`

- [ ] **Step 1: Find a similar existing IT for template/structure**

Run: `Glob pattern="wikantik-it-test-custom/src/test/java/**/KnowledgeGraph*IT.java"` or similar. Read the closest match (e.g. `KnowledgeGraphVisualizationIT.java` if present) to copy its setup (login, navigate to /graph, wait for canvas).

- [ ] **Step 2: Write the IT**

Using the pattern from Step 1, create `wikantik-it-test-custom/src/test/java/com/wikantik/it/GraphFilterViewsIT.java`. It must: login as admin, navigate to `/graph`, wait for Cytoscape to load, click the `Backbone` pill, assert the URL now contains `preset=backbone`, click `Communities`, assert URL contains `preset=communities`, reload with an explicit `?preset=backbone&hop=1` and assert the +1 hop checkbox is checked.

Minimum skeleton (fill in Selenide page-object idioms from the template):

```java
package com.wikantik.it;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;
import static org.assertj.core.api.Assertions.assertThat;

public class GraphFilterViewsIT extends AbstractAuthenticatedIT {

    @Test
    void backbonePresetAddsUrlParam() {
        loginAsAdmin();
        open("/graph");
        $(".filter-preset-row").shouldBe(Condition.visible);
        $(".filter-preset-pill:nth-child(2)").click();                 // Backbone
        Selenide.Wait().until(d -> WebDriverRunner.url().contains("preset=backbone"));
    }

    @Test
    void communitiesPresetIsolatesCluster() {
        loginAsAdmin();
        open("/graph");
        $(".filter-preset-pill:nth-child(3)").click();                 // Communities
        $(".cluster-legend").shouldBe(Condition.visible);
        $(".cluster-legend-item").click();                             // isolate first cluster
        Selenide.Wait().until(d -> WebDriverRunner.url().contains("cluster="));
    }

    @Test
    void backboneHopStateRestoredFromUrl() {
        loginAsAdmin();
        open("/graph?preset=backbone&hop=1");
        $("input[type='checkbox'][checked]").shouldBe(Condition.visible);
    }
}
```

(Replace `AbstractAuthenticatedIT` / `loginAsAdmin()` with whatever the nearest existing IT in this module uses. If the base class is named differently, use the actual name from the template IT you copied.)

- [ ] **Step 3: Run the IT module (sequential, no parallel!)**

Run: `mvn clean install -pl wikantik-it-test-custom -Pintegration-tests -fae`
Expected: all three new tests pass along with existing ITs.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-test-custom/src/test/java/com/wikantik/it/GraphFilterViewsIT.java
git commit -m "test(it): Selenide IT covering filter presets, URL sync"
```

---

## Task 9: Final build + manual browser verification

- [ ] **Step 1: Full unit-test build**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Frontend test pass**

Run: `cd wikantik-frontend && npm test`
Expected: all tests pass.

- [ ] **Step 3: Redeploy locally**

Run:
```bash
tomcat/tomcat-11/bin/shutdown.sh || true
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

- [ ] **Step 4: Manual browser checks**

Open `http://localhost:8080/graph` and verify:
- Filter panel renders at top of graph view, four preset pills visible
- Clicking Backbone hides non-hub nodes; URL gains `?preset=backbone`
- Clicking Communities shows cluster legend with counts; clicking a cluster isolates; URL gains `?cluster=<name>`
- Clicking Tags fades non-matching when a tag is selected; URL gains `?tags=<tag>&preset=tags`
- Chip row appears; clicking × removes one refinement without leaving the preset
- Reload the page with the current URL — state is restored
- Edge-type checkbox filters still work
- `?focus=<PageName>` still selects and zooms the node; if filter would hide it, it stays visible
- Empty-result overlay appears when all nodes are filtered out

If any step fails: investigate, fix, re-run from the first failing step.

- [ ] **Step 5: Full integration-test suite (confidence check)**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Final commit if any trailing fix-ups needed**

If steps 4 or 5 surfaced bugs, fix them and commit:
```bash
git add <files>
git commit -m "fix(graph): <specific issue surfaced during verification>"
```

---

## Notes for the implementer

- **TDD discipline**: every task starts with a failing test. If you can't write a test first (e.g. Task 8 IT structure depends on existing helpers), read the nearest sibling test first to match idioms, then write.
- **Positions preserved on filter change**: do NOT call `cy.layout().run()` when filters change — only when refreshing the snapshot. `display:none` via the `hidden` class keeps layout coordinates intact for remaining nodes.
- **Don't re-layout on every preset click**: this is the #1 most common mistake. The whole design depends on smooth, stable visibility toggling.
- **ACL redaction already handles restricted nodes**: Task 1 explicitly nulls `cluster`/`tags`/`status` on restricted nodes so they never leak data. Verify with the existing redaction test pattern.
- **`npm test` runs via `vitest run`** per `wikantik-frontend/package.json`. Use `npx vitest run <file>` for single-file runs during development.
- **The IT module name** may be `wikantik-it-test-custom` or a sibling — pick the one that already has Selenide tests for `/graph`. Do NOT run ITs with `-T` (see CLAUDE.md).
