# Knowledge Graph Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a full-page interactive Knowledge Graph viewer at the SPA route `/knowledge-graph` that mirrors the Page Graph viewer at `/page-graph`, plus a KG-specific tier filter (`machine` ↔ `human`), node-type colour coding, and provenance/status styling.

**Architecture:** Reuse all `wikantik-frontend/src/components/pagegraph/*` sub-components without modification. Add a sibling directory `wikantik-frontend/src/components/kgraph/` containing one orchestrator (`KnowledgeGraphView.jsx`) plus thin KG-specific overlays (`KgGraphToolbar`, `KgGraphLegend`, `KgGraphDetailsDrawer`, `KgErrorState`) and pure helpers (`kg-graph-data.js`, `kg-graph-style.js`). The orchestrator keeps two extra pieces of state beyond the Page Graph version: `minTier` (URL-synced via `?tier=`) and a `nodeTypeColor` map. Backend change: add `String tier` to the shared `SnapshotNode` record so the wire carries per-node tier and the viewer can render a tier badge without a second API call.

**Tech Stack:** React 18 + Vite, react-router-dom v6, react-cytoscapejs, Vitest + @testing-library/react. Backend: Java 21 record types in `wikantik-api`, JUnit 5 unit tests in `wikantik-main`. SPA routing via `wikantik-rest`'s `SpaRoutingFilter` declared in `wikantik-war/src/main/webapp/WEB-INF/web.xml`. Selenide for end-to-end browser ITs in `wikantik-it-tests/wikantik-it-test-selenide-spa`.

---

## Pre-flight

Before starting Task 1 confirm the working tree is clean and the build is green.

```bash
git status                              # expect: clean
mvn -pl wikantik-frontend -am test -q   # expect: BUILD SUCCESS, vitest passes
```

The reference design lives at `docs/superpowers/specs/2026-05-02-knowledge-graph-viewer-design.md`. Re-read sections 4 (Architecture), 7 (KG-specific extensions), and 12 (Implementation Phases) before each task — the task numbers below correspond directly to the phase numbering there.

Two pieces of repo context this plan assumes:

- **`SnapshotNode` is shared between Page Graph and Knowledge Graph.** Adding the `tier` field to the record affects both. The Page Graph builder has no tier concept — it must populate `tier=null` (or `"human"`) so existing Page Graph behaviour is unchanged. Cross-check `wikantik-pagegraph/.../*.java` and `wikantik-main/.../PageGraphService.java` for any `new SnapshotNode(...)` call sites and pass the new positional argument explicitly.
- **The api client is at `wikantik-frontend/src/api/client.js`.** Method `api.knowledge.getGraphSnapshot({ signal } = {})` already exists at line 506; we extend its signature, not replace it.

---

## Task 1: Phase 1 — minimal wiring at `/knowledge-graph`

**Goal:** A new SPA route at `/knowledge-graph` renders the KG snapshot using all existing Page Graph sub-components without modification. No tier dropdown, no node-type colours, no KG-specific drawer fields. The dedicated test confirms the route loads, fetches `/api/knowledge/graph` (no query params), and renders the canvas on success.

**Files:**
- Create: `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx`
- Create: `wikantik-frontend/src/components/kgraph/KgErrorState.jsx`
- Create: `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.test.jsx`
- Modify: `wikantik-frontend/src/main.jsx` (add route)
- Modify: `wikantik-frontend/src/App.jsx` (extend `isGraphRoute`)
- Modify: `wikantik-frontend/src/components/Sidebar.jsx` (add nav link)
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (add SPA url-pattern)

- [ ] **Step 1: Write the failing test for `KnowledgeGraphView`**

Create `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.test.jsx` with these vitest cases. Mirror the structure of `wikantik-frontend/src/components/pagegraph/PageGraphView.test.jsx` exactly — same import pattern, same `vi.mock('../../api/client')`, same `vi.mock('./GraphCanvas.jsx')` (note the relative path is shorter because we reuse the canvas via `../pagegraph/GraphCanvas.jsx` — see step 3).

```jsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const MOCK_SNAPSHOT = {
  generatedAt: '2026-05-02T20:00:00Z',
  nodeCount: 2,
  edgeCount: 1,
  hubDegreeThreshold: 10,
  nodes: [
    { id: 'aaa', name: 'Alan Turing', type: 'person',  role: 'normal', provenance: 'AI_INFERRED', sourcePage: 'AlanTuring',  degreeIn: 1, degreeOut: 0, restricted: false, cluster: null, tags: [], status: 'active' },
    { id: 'bbb', name: 'Computability', type: 'concept', role: 'normal', provenance: 'AI_INFERRED', sourcePage: 'Computability', degreeIn: 0, degreeOut: 1, restricted: false, cluster: null, tags: [], status: 'active' },
  ],
  edges: [
    { id: 'e1', source: 'bbb', target: 'aaa', relationshipType: 'mentioned_with', provenance: 'AI_INFERRED' },
  ],
};

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      getGraphSnapshot: vi.fn(),
    },
  },
}));

vi.mock('../pagegraph/GraphCanvas.jsx', () => ({
  default: ({ elements }) => <div data-testid="graph-canvas">Canvas: {elements.nodes.length} nodes</div>,
}));

import { api } from '../../api/client';
import KnowledgeGraphView from './KnowledgeGraphView.jsx';

describe('KnowledgeGraphView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading then canvas on success', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue(MOCK_SNAPSHOT);
    render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
    expect(screen.getByText(/loading/i)).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('graph-canvas')).toBeTruthy());
  });

  it('calls getGraphSnapshot with no minTier on first mount', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue(MOCK_SNAPSHOT);
    render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
    await waitFor(() => expect(api.knowledge.getGraphSnapshot).toHaveBeenCalled());
    const args = api.knowledge.getGraphSnapshot.mock.calls[0][0] || {};
    expect(args.minTier).toBeUndefined();
  });

  it('shows 401 error variant for unauthorized', async () => {
    api.knowledge.getGraphSnapshot.mockRejectedValue(Object.assign(new Error('Unauthorized'), { status: 401 }));
    render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText('Sign in to view the knowledge graph.')).toBeTruthy());
  });

  it('shows server error for 5xx', async () => {
    api.knowledge.getGraphSnapshot.mockRejectedValue(Object.assign(new Error('Server error'), { status: 500 }));
    render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/unavailable/i)).toBeTruthy());
  });

  it('shows empty state when nodeCount is 0', async () => {
    api.knowledge.getGraphSnapshot.mockResolvedValue({ ...MOCK_SNAPSHOT, nodeCount: 0, nodes: [], edges: [] });
    render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/empty/i)).toBeTruthy());
  });
});
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
cd wikantik-frontend && npx vitest run src/components/kgraph/KnowledgeGraphView.test.jsx 2>&1 | tail -20
```

Expected: failure with `Cannot find module './KnowledgeGraphView.jsx'` — the module does not exist yet.

- [ ] **Step 3: Create `KgErrorState.jsx` (KG-specific error variants)**

The Page Graph `GraphErrorState` hard-codes "page graph" in messages and a `/login?return=/page-graph` link. Mirror it for KG so the test in step 1 (`'Sign in to view the knowledge graph.'`) passes. Create `wikantik-frontend/src/components/kgraph/KgErrorState.jsx`:

```jsx
import { Link } from 'react-router-dom';

const VARIANTS = {
  empty:          { message: 'The knowledge graph is empty.', action: 'refresh' },
  'empty-for-you': { message: "You don't have permission to view any nodes in the knowledge graph.", action: null },
  unauthorized:   { message: 'Sign in to view the knowledge graph.', action: 'login' },
  forbidden:      { message: "You don't have permission to view the knowledge graph.", action: null },
  server:         { message: 'The knowledge graph service is unavailable right now.', action: 'retry' },
  malformed:      { message: 'Knowledge graph snapshot was invalid. Check server logs.', action: 'retry' },
};

export default function KgErrorState({ variant, onRetry }) {
  const config = VARIANTS[variant] || VARIANTS.server;

  return (
    <div className="graph-error-state" data-testid="graph-error-state">
      <p className="error-message">{config.message}</p>
      {config.action === 'refresh' && (
        <button className="error-action" onClick={onRetry}>Refresh</button>
      )}
      {config.action === 'retry' && (
        <button className="error-action" onClick={onRetry}>Try again</button>
      )}
      {config.action === 'login' && (
        <Link to="/login?return=/knowledge-graph" className="error-action" style={{ textDecoration: 'none' }}>
          Sign in
        </Link>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Create `KnowledgeGraphView.jsx` (Phase 1 minimal version)**

Create `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx`. This is a near-clone of `PageGraphView.jsx`, with three substitutions:

1. `api.pageGraph.getSnapshot()` → `api.knowledge.getGraphSnapshot()`
2. `GraphErrorState` → `KgErrorState`
3. The "Page Graph — edges are real wikilinks" banner → "Knowledge Graph — edges are LLM-extracted relations"

All the rest (filter state, fetch state machine, refresh handler, drawer, error boundary, layout overlay, keyboard escape) is reused unchanged.

```jsx
import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import { toCytoscapeElements } from '../pagegraph/graph-data.js';
import { applyFilters } from '../pagegraph/filter-engine.js';
import { paramsToFilterState, filterStateToParams } from '../pagegraph/filter-url.js';
import FilterPanel from '../pagegraph/FilterPanel.jsx';
import GraphCanvas from '../pagegraph/GraphCanvas.jsx';
import GraphToolbar from '../pagegraph/GraphToolbar.jsx';
import GraphLegend from '../pagegraph/GraphLegend.jsx';
import GraphZoomSlider from '../pagegraph/GraphZoomSlider.jsx';
import GraphDetailsDrawer from '../pagegraph/GraphDetailsDrawer.jsx';
import GraphErrorBoundary from '../pagegraph/GraphErrorBoundary.jsx';
import GraphLoadingFallback from '../pagegraph/GraphLoadingFallback.jsx';
import { setEdgeTypeHidden, setShowOrphansStubs } from '../pagegraph/filter-state.js';
import KgErrorState from './KgErrorState.jsx';
import '../pagegraph/graph.css';

export default function KnowledgeGraphView() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const focusParam = useRef(searchParams.get('focus'));

  const [fetchState, setFetchState] = useState('loading');
  const [errorVariant, setErrorVariant] = useState(null);
  const [snapshot, setSnapshot] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [filterState, setFilterState] = useState(() => paramsToFilterState(searchParams));
  const [layoutDone, setLayoutDone] = useState(false);

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
  if (fetchState === 'error') return <KgErrorState variant={errorVariant} onRetry={fetchSnapshot} />;

  const noVisibleNodes = filterResult && filterResult.visibleNodeIds.size === 0;

  return (
    <GraphErrorBoundary>
      <div className="graph-view">
        <div style={{ padding: '4px 12px', fontSize: '0.75rem', opacity: 0.7 }}>
          Knowledge Graph — edges are LLM-extracted relations.{' '}
          <a href="/wiki/PageGraphVsKnowledgeGraph">What is the Knowledge Graph?</a>
        </div>
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

- [ ] **Step 5: Run the test and verify it passes**

```bash
cd wikantik-frontend && npx vitest run src/components/kgraph/KnowledgeGraphView.test.jsx 2>&1 | tail -15
```

Expected: 5 tests pass.

- [ ] **Step 6: Wire the route in `main.jsx`**

In `wikantik-frontend/src/main.jsx`, immediately after the existing `PageGraphView` lazy import (around line 31), add:

```jsx
const KnowledgeGraphView = React.lazy(() => import('./components/kgraph/KnowledgeGraphView.jsx'));
```

Inside the `<Route element={<App />}>` block, immediately after the `/page-graph` Route (around line 50), add:

```jsx
<Route path="/knowledge-graph" element={
  <Suspense fallback={<div className="graph-loading"><p>Loading knowledge graph...</p></div>}>
    <KnowledgeGraphView />
  </Suspense>
} />
```

- [ ] **Step 7: Extend `isGraphRoute` in `App.jsx`**

In `wikantik-frontend/src/App.jsx`, change line 13 from:

```jsx
const isGraphRoute = location.pathname === '/page-graph';
```

to:

```jsx
const isGraphRoute = location.pathname === '/page-graph' || location.pathname === '/knowledge-graph';
```

This makes the `app-content-full` CSS class apply on both routes, giving the canvas edge-to-edge width.

- [ ] **Step 8: Add the Sidebar nav link**

In `wikantik-frontend/src/components/Sidebar.jsx`, immediately after the existing `Page Graph` `<Link>` (around line 117), add a sibling `<Link>`:

```jsx
<Link
  to="/knowledge-graph"
  className="sidebar-link"
  onClick={onMobileClose}
>
  Knowledge Graph
</Link>
```

Place it between the `Page Graph` `<Link>` and the existing `Page Graph vs Knowledge Graph` `<a>` so the three links group naturally.

- [ ] **Step 9: Add the SPA url-pattern in `web.xml`**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, find the `SpaRoutingFilter` `<filter-mapping>` block (around line 138). Insert `<url-pattern>/knowledge-graph</url-pattern>` immediately after `<url-pattern>/page-graph</url-pattern>`:

```xml
<url-pattern>/page-graph</url-pattern>
<url-pattern>/knowledge-graph</url-pattern>
```

Without this, a direct browser load of `http://host/knowledge-graph` (or page refresh) returns the Tomcat default 404 page instead of forwarding to `/index.html`.

- [ ] **Step 10: Build the war and verify the route loads**

```bash
mvn clean install -Dmaven.test.skip -T 1C -q
tomcat/tomcat-11/bin/shutdown.sh; sleep 3
rm -rf tomcat/tomcat-11/webapps/ROOT tomcat/tomcat-11/webapps/ROOT.war
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

Wait for the app context, then:

```bash
curl -fsS -o /dev/null -w "%{http_code}\n" http://localhost:8080/knowledge-graph
```

Expected: `200`. The response body is `index.html` (because `SpaRoutingFilter` forwarded). If it is `404`, recheck the `web.xml` edit.

In a browser, navigate to `http://localhost:8080/knowledge-graph` and confirm:
- The KG nodes render as a force-directed layout.
- The "Knowledge Graph — edges are LLM-extracted relations" banner is at the top.
- The sidebar contains the new `Knowledge Graph` link.
- Clicking the link from any other route navigates here.

- [ ] **Step 11: Commit**

```bash
git add wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx \
        wikantik-frontend/src/components/kgraph/KnowledgeGraphView.test.jsx \
        wikantik-frontend/src/components/kgraph/KgErrorState.jsx \
        wikantik-frontend/src/main.jsx \
        wikantik-frontend/src/App.jsx \
        wikantik-frontend/src/components/Sidebar.jsx \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(kg-view): /knowledge-graph SPA route mirroring /page-graph (Phase 1)"
```

---

## Task 2: Phase 2a Backend — add `tier` to `SnapshotNode`

**Goal:** The `SnapshotNode` record carries a per-node `tier` value (`"human"` or `"machine"`) populated from `KgNode.tier()`. The Page Graph builder also populates the field (with `null`) so `SnapshotNode` construction stays consistent across the codebase. No frontend behaviour changes yet — the field is unused by the UI in this task.

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java` (two `new SnapshotNode(...)` call sites at lines 525 and 567)
- Find and modify: every other `new SnapshotNode(...)` call site (Page Graph builder, tests, fixtures) — see step 3
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java` (or matching existing test)

- [ ] **Step 1: Locate every `new SnapshotNode(...)` call site**

```bash
grep -rn "new SnapshotNode" --include="*.java" . | grep -v target/ | grep -v build/
```

Record the list. There are at least:
- `wikantik-main/.../DefaultKnowledgeGraphService.java` (× 2)

Plus any Page Graph builders, snapshot adapters, mock test fixtures, and the API module's own tests. Every one of these must be updated in step 3.

- [ ] **Step 2: Write a failing test for the new `tier` field**

In `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceSnapshotTierTest.java` add:

```java
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.SnapshotNode;
import com.wikantik.api.knowledge.Tier;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DefaultKnowledgeGraphServiceSnapshotTierTest {

    @Test
    void snapshotPopulatesTierOnEachNode() {
        final UUID id = UUID.randomUUID();
        final KgNode kgNode = new KgNode(
            id, "AlanTuring", "person",
            Provenance.AI_INFERRED,
            "AlanTuring",
            Instant.now(), Instant.now(),
            "human",  /* tier */
            null      /* properties */
        );

        final KnowledgeRepository repo = Mockito.mock( KnowledgeRepository.class );
        Mockito.when( repo.getAllNodes( Mockito.any() ) ).thenReturn( List.of( kgNode ) );
        Mockito.when( repo.getAllEdges( Mockito.any() ) ).thenReturn( List.of() );

        final DefaultKnowledgeGraphService svc = new DefaultKnowledgeGraphService( repo, null );
        final GraphSnapshot snap = svc.snapshotGraph( null, Tier.HUMAN );

        assertEquals( 1, snap.nodes().size() );
        final SnapshotNode out = snap.nodes().get( 0 );
        assertNotNull( out.tier() );
        assertEquals( "human", out.tier() );
    }
}
```

(Adjust the `KgNode` constructor positional arguments if its actual record signature differs — confirm with `grep -n "public record KgNode" wikantik-api/src/main/java/com/wikantik/api/knowledge/KgNode.java`. Adjust `DefaultKnowledgeGraphService` constructor arity to match the real signature.)

- [ ] **Step 3: Run the test and verify it fails**

```bash
mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceSnapshotTierTest -q 2>&1 | tail -25
```

Expected: compilation error — `SnapshotNode#tier()` does not exist.

- [ ] **Step 4: Add `tier` to `SnapshotNode`**

In `wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java`, append `String tier` to the record components. The new declaration:

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
    String status,
    String tier
) {
    public SnapshotNode {
        tags = tags == null ? List.of() : List.copyOf( tags );
    }
}
```

- [ ] **Step 5: Update `DefaultKnowledgeGraphService` call sites**

At line 525 (the main-path builder), pass `node.tier()` as the trailing argument:

```java
nodes.add( new SnapshotNode(
        node.id(), node.name(), node.nodeType(), role,
        node.provenance(), node.sourcePage(), deg[0], deg[1], false,
        propString( node, "cluster" ),
        propStringList( node, "tags" ),
        propString( node, "status" ),
        node.tier() ) );
```

At line 567 (the redacted-for-viewer copy), pass `null` because a restricted node has no business advertising its tier:

```java
redacted.add( new SnapshotNode(
        node.id(), null, null, "restricted", null, null,
        node.degreeIn(), node.degreeOut(), true,
        null, List.of(), null, null ) );
```

- [ ] **Step 6: Update the remaining `new SnapshotNode(...)` call sites**

For every match in step 1 outside `DefaultKnowledgeGraphService`, append a trailing argument. For the Page Graph builder (which has no concept of tier), pass `null`. For test fixtures, pass `null` unless the test specifically asserts on `tier`. Run the search again to confirm no call site is left at the old arity:

```bash
grep -rn "new SnapshotNode" --include="*.java" . | grep -v target/
```

- [ ] **Step 7: Run `mvn test-compile` and fix any breakage**

```bash
mvn test-compile -pl wikantik-main,wikantik-api,wikantik-pagegraph -am -q 2>&1 | tail -25
```

Expected: zero errors. (`mvn compile` skips test sources — see `feedback_test_compile_after_signature_change.md`. The test files are guaranteed callers of `new SnapshotNode(...)`.)

- [ ] **Step 8: Run the new test and verify it passes**

```bash
mvn test -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceSnapshotTierTest -q 2>&1 | tail -10
```

Expected: 1 test passes.

- [ ] **Step 9: Run the full unit suite for `wikantik-main` and `wikantik-api`**

```bash
mvn test -pl wikantik-main,wikantik-api -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS. If a Page Graph test fails because a fixture was missed, fix it and rerun.

- [ ] **Step 10: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/SnapshotNode.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceSnapshotTierTest.java \
        $(other-call-sites-modified)
git commit -m "feat(kg-api): add tier to SnapshotNode + populate from KgNode"
```

---

## Task 3: Phase 2b Frontend — tier dropdown, URL sync, legend tier counts

**Goal:** A `KgGraphToolbar` wraps the existing `GraphToolbar` and adds a tier `<select>` dropdown. `KnowledgeGraphView` holds `minTier` state, syncs it to the URL query string `?tier=<value>`, and re-fetches the snapshot on change. `KgGraphLegend` renders machine + human counts derived from `snapshot.nodes.filter(n => n.tier === 'human').length`.

**Files:**
- Create: `wikantik-frontend/src/components/kgraph/KgGraphToolbar.jsx`
- Create: `wikantik-frontend/src/components/kgraph/KgGraphLegend.jsx`
- Modify: `wikantik-frontend/src/api/client.js:506` (extend `getGraphSnapshot` signature)
- Modify: `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx`
- Modify: `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.test.jsx` (add tier tests)

- [ ] **Step 1: Extend `api.knowledge.getGraphSnapshot` signature**

In `wikantik-frontend/src/api/client.js` change line 506-507 from:

```js
getGraphSnapshot: ({ signal } = {}) =>
  request('/api/knowledge/graph', { signal }),
```

to:

```js
getGraphSnapshot: ({ minTier, signal } = {}) => {
  const qs = minTier ? `?min_tier=${encodeURIComponent(minTier)}` : '';
  return request(`/api/knowledge/graph${qs}`, { signal });
},
```

The frontend uses the user-facing term `tier` in the URL bar and the backend canonical `min_tier` in the API call — the conversion lives entirely in this method.

- [ ] **Step 2: Write failing tests for tier behaviour**

Append these cases to `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.test.jsx` inside the existing `describe('KnowledgeGraphView')` block:

```jsx
it('reads tier from URL on mount and fetches with that minTier', async () => {
  api.knowledge.getGraphSnapshot.mockResolvedValue(MOCK_SNAPSHOT);
  render(<MemoryRouter initialEntries={['/knowledge-graph?tier=human']}><KnowledgeGraphView /></MemoryRouter>);
  await waitFor(() => expect(api.knowledge.getGraphSnapshot).toHaveBeenCalled());
  const args = api.knowledge.getGraphSnapshot.mock.calls[0][0] || {};
  expect(args.minTier).toBe('human');
});

it('changing tier dropdown re-fetches with new minTier and updates URL', async () => {
  const { fireEvent } = await import('@testing-library/react');
  api.knowledge.getGraphSnapshot.mockResolvedValue(MOCK_SNAPSHOT);
  render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
  await waitFor(() => expect(screen.getByTestId('graph-canvas')).toBeTruthy());

  const select = screen.getByLabelText(/tier/i);
  fireEvent.change(select, { target: { value: 'human' } });

  await waitFor(() => expect(api.knowledge.getGraphSnapshot).toHaveBeenCalledTimes(2));
  const secondArgs = api.knowledge.getGraphSnapshot.mock.calls[1][0] || {};
  expect(secondArgs.minTier).toBe('human');
  expect(window.location.search).toContain('tier=human');
});

it('legend shows machine + human tier counts', async () => {
  const snap = {
    ...MOCK_SNAPSHOT,
    nodes: [
      { ...MOCK_SNAPSHOT.nodes[0], tier: 'machine' },
      { ...MOCK_SNAPSHOT.nodes[1], tier: 'human' },
    ],
  };
  api.knowledge.getGraphSnapshot.mockResolvedValue(snap);
  render(<MemoryRouter initialEntries={['/knowledge-graph']}><KnowledgeGraphView /></MemoryRouter>);
  await waitFor(() => expect(screen.getByText(/1 machine/i)).toBeTruthy());
  expect(screen.getByText(/1 human/i)).toBeTruthy();
});
```

(Mock `KgGraphLegend` is **not** added — we want the real legend rendering to assert the count strings.)

- [ ] **Step 3: Run the test and verify it fails**

```bash
cd wikantik-frontend && npx vitest run src/components/kgraph/KnowledgeGraphView.test.jsx 2>&1 | tail -20
```

Expected: 3 new failures. Existing 5 still pass.

- [ ] **Step 4: Create `KgGraphToolbar.jsx`**

```jsx
import GraphToolbar from '../pagegraph/GraphToolbar.jsx';

export default function KgGraphToolbar({
  onFitToView, onRefresh, onToggleAnomalies, onToggleEdgeType,
  edgeTypes, hiddenEdgeTypes, onlyAnomalies, timestamp,
  minTier, onTierChange,
}) {
  return (
    <div className="kg-graph-toolbar-wrapper" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
      <label htmlFor="kg-tier-select" style={{ fontSize: '0.75rem', opacity: 0.85 }}>
        Tier:
      </label>
      <select
        id="kg-tier-select"
        aria-label="Tier"
        value={minTier}
        onChange={(e) => onTierChange(e.target.value)}
        style={{ fontSize: '0.75rem', padding: '2px 6px' }}
      >
        <option value="machine">machine (broader)</option>
        <option value="human">human (strict)</option>
      </select>
      <GraphToolbar
        onFitToView={onFitToView}
        onRefresh={onRefresh}
        onToggleAnomalies={onToggleAnomalies}
        onToggleEdgeType={onToggleEdgeType}
        edgeTypes={edgeTypes}
        hiddenEdgeTypes={hiddenEdgeTypes}
        onlyAnomalies={onlyAnomalies}
        timestamp={timestamp}
      />
    </div>
  );
}
```

- [ ] **Step 5: Create `KgGraphLegend.jsx`**

```jsx
import GraphLegend from '../pagegraph/GraphLegend.jsx';

export default function KgGraphLegend({ hubDegreeThreshold, timestamp, machineCount, humanCount }) {
  return (
    <div>
      <div style={{ fontSize: '0.7rem', opacity: 0.8, padding: '2px 8px' }}>
        Tier: {machineCount} machine, {humanCount} human
      </div>
      <GraphLegend hubDegreeThreshold={hubDegreeThreshold} timestamp={timestamp} />
    </div>
  );
}
```

- [ ] **Step 6: Wire `minTier` into `KnowledgeGraphView`**

Edit `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx`. Make four changes:

(a) Replace the `import` for the toolbar and legend from pagegraph with the kg variants:

```jsx
import KgGraphToolbar from './KgGraphToolbar.jsx';
import KgGraphLegend from './KgGraphLegend.jsx';
```

…and remove the now-unused `import GraphToolbar from '../pagegraph/GraphToolbar.jsx';` and `import GraphLegend from '../pagegraph/GraphLegend.jsx';` lines.

(b) Add `minTier` state. Place it directly below the existing `useState` hooks:

```jsx
const [minTier, setMinTier] = useState(() => {
  const t = searchParams.get('tier');
  return (t === 'human' || t === 'machine') ? t : 'machine';
});
```

(c) Replace the body of `fetchSnapshot` so it accepts a tier argument and forwards it to the API call. Add `minTier` to its dependency list. Also re-trigger the fetch whenever `minTier` changes:

```jsx
const fetchSnapshot = useCallback(async (tier) => {
  setFetchState('loading');
  setErrorVariant(null);
  try {
    const data = await api.knowledge.getGraphSnapshot(tier ? { minTier: tier } : undefined);
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

useEffect(() => {
  // On first mount, omit the tier so the existing test ("calls getGraphSnapshot
  // with no minTier on first mount") keeps passing when no tier is in the URL.
  // Subsequent updates always pass it explicitly.
  fetchSnapshot(searchParams.get('tier') || undefined);
}, [fetchSnapshot, searchParams]);
```

Replace the existing `useEffect(() => { fetchSnapshot(); }, [fetchSnapshot]);` with the version above. (The Phase 1 test "calls getGraphSnapshot with no minTier on first mount" must still pass — when no tier query param is present, `searchParams.get('tier')` is `null`, the conditional sends `undefined`, and `getGraphSnapshot` gets called with `undefined`.)

(d) Add the `handleTierChange` handler:

```jsx
const handleTierChange = useCallback((tier) => {
  setMinTier(tier);
  const params = new URLSearchParams(window.location.search);
  if (tier === 'machine') {
    params.delete('tier'); // keep URL clean for the default
  } else {
    params.set('tier', tier);
  }
  const qs = params.toString();
  const url = qs ? `${window.location.pathname}?${qs}` : window.location.pathname;
  window.history.replaceState(null, '', url);
  fetchSnapshot(tier);
  setSelectedId(null); // node ID set may change between tiers
}, [fetchSnapshot]);
```

(e) Update `handleRefresh` so it forwards the current `minTier`:

```jsx
const handleRefresh = useCallback(async () => {
  const prevSelectedName = selectedNode?.name;
  setFetchState('loading'); setErrorVariant(null);
  try {
    const data = await api.knowledge.getGraphSnapshot({ minTier });
    /* …rest unchanged… */
  }
  /* …catch unchanged… */
}, [selectedNode, minTier]);
```

(f) Compute `machineCount` and `humanCount`:

```jsx
const tierCounts = useMemo(() => {
  if (!snapshot) return { machineCount: 0, humanCount: 0 };
  let m = 0, h = 0;
  for (const n of snapshot.nodes) {
    if (n.tier === 'human') h++;
    else if (n.tier === 'machine') m++;
  }
  return { machineCount: m, humanCount: h };
}, [snapshot]);
```

(g) Replace the JSX `<GraphToolbar …/>` with `<KgGraphToolbar …/>` (extra `minTier` and `onTierChange` props), and the JSX `<GraphLegend …/>` with `<KgGraphLegend …/>` (extra `machineCount` and `humanCount` props):

```jsx
<KgGraphToolbar
  onFitToView={() => window.cy?.fit()}
  onRefresh={handleRefresh}
  onToggleAnomalies={handleToggleOrphans}
  onToggleEdgeType={handleToggleEdgeType}
  edgeTypes={edgeTypes}
  hiddenEdgeTypes={filterState.hiddenEdgeTypes}
  onlyAnomalies={!filterState.showOrphansStubs}
  timestamp={timestamp}
  minTier={minTier}
  onTierChange={handleTierChange}
/>
```

```jsx
<KgGraphLegend
  hubDegreeThreshold={snapshot?.hubDegreeThreshold || 10}
  timestamp={timestamp}
  machineCount={tierCounts.machineCount}
  humanCount={tierCounts.humanCount}
/>
```

- [ ] **Step 7: Run all kgraph tests and verify pass**

```bash
cd wikantik-frontend && npx vitest run src/components/kgraph/ 2>&1 | tail -20
```

Expected: 8 tests pass (5 from Phase 1 + 3 new).

- [ ] **Step 8: Browser smoke test**

```bash
mvn -pl wikantik-frontend,wikantik-war -am package -DskipTests -q
tomcat/tomcat-11/bin/shutdown.sh; sleep 3
rm -rf tomcat/tomcat-11/webapps/ROOT tomcat/tomcat-11/webapps/ROOT.war
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

Once up:
- Open `http://localhost:8080/knowledge-graph` — confirm the Tier dropdown is visible to the left of the Refresh button.
- Switch to "human (strict)" — URL becomes `?tier=human`, the canvas reloads, the legend's "machine / human" counts update.
- Switch back to "machine (broader)" — URL clears to `/knowledge-graph` (no `?tier=` for the default).
- Open `http://localhost:8080/knowledge-graph?tier=human` directly — the dropdown initialises to "human" on mount.

- [ ] **Step 9: Commit**

```bash
git add wikantik-frontend/src/components/kgraph/KgGraphToolbar.jsx \
        wikantik-frontend/src/components/kgraph/KgGraphLegend.jsx \
        wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx \
        wikantik-frontend/src/components/kgraph/KnowledgeGraphView.test.jsx \
        wikantik-frontend/src/api/client.js
git commit -m "feat(kg-view): tier dropdown + URL sync + legend tier counts (Phase 2)"
```

---

## Task 4: Phase 3 — node-type colour coding

**Goal:** KG nodes are colour-coded by `node_type` (the `SnapshotNode.type` field) using a deterministic palette. The legend displays a swatch for each observed `node_type` in the snapshot. The drawer shows `node_type` and `provenance` as labelled fields.

**Files:**
- Create: `wikantik-frontend/src/components/kgraph/kg-graph-data.js`
- Create: `wikantik-frontend/src/components/kgraph/kg-graph-style.js`
- Create: `wikantik-frontend/src/components/kgraph/KgGraphDetailsDrawer.jsx`
- Create: `wikantik-frontend/src/components/kgraph/kg-graph-data.test.js`
- Modify: `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx`
- Modify: `wikantik-frontend/src/components/kgraph/KgGraphLegend.jsx` (add type swatches)

- [ ] **Step 1: Write a failing test for `toKgCytoscapeElements`**

Create `wikantik-frontend/src/components/kgraph/kg-graph-data.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { toKgCytoscapeElements, colourForNodeType } from './kg-graph-data.js';

const SNAP = {
  generatedAt: '2026-05-02T20:00:00Z',
  nodeCount: 3,
  edgeCount: 1,
  hubDegreeThreshold: 10,
  nodes: [
    { id: 'a', name: 'Alan',    type: 'person',  role: 'normal', degreeIn: 1, degreeOut: 0, restricted: false, cluster: null, tags: [], status: 'active', provenance: 'AI_INFERRED', tier: 'machine' },
    { id: 'b', name: 'Compute', type: 'concept', role: 'normal', degreeIn: 0, degreeOut: 1, restricted: false, cluster: null, tags: [], status: 'active', provenance: 'AI_INFERRED', tier: 'machine' },
    { id: 'c', name: 'Other',   type: 'person',  role: 'normal', degreeIn: 0, degreeOut: 0, restricted: false, cluster: null, tags: [], status: 'active', provenance: 'AI_INFERRED', tier: 'machine' },
  ],
  edges: [
    { id: 'e1', source: 'b', target: 'a', relationshipType: 'mentioned_with', provenance: 'AI_INFERRED' },
  ],
};

const FILTER = {
  visibleNodeIds:  new Set(['a', 'b', 'c']),
  fadedNodeIds:    new Set(),
  visibleEdgeIds:  new Set(['e1']),
  fadedEdgeIds:    new Set(),
  nodeColor:       new Map(),
};

describe('toKgCytoscapeElements', () => {
  it('assigns the same nodeTypeColor to nodes of the same node_type', () => {
    const out = toKgCytoscapeElements(SNAP, FILTER);
    const a = out.nodes.find(n => n.data.id === 'a');
    const b = out.nodes.find(n => n.data.id === 'b');
    const c = out.nodes.find(n => n.data.id === 'c');
    expect(a.data.nodeTypeColor).toBe(c.data.nodeTypeColor);
    expect(a.data.nodeTypeColor).not.toBe(b.data.nodeTypeColor);
  });

  it('writes type, provenance, status, tier into node.data', () => {
    const out = toKgCytoscapeElements(SNAP, FILTER);
    const a = out.nodes.find(n => n.data.id === 'a');
    expect(a.data.type).toBe('person');
    expect(a.data.provenance).toBe('AI_INFERRED');
    expect(a.data.status).toBe('active');
    expect(a.data.tier).toBe('machine');
  });
});

describe('colourForNodeType', () => {
  it('is deterministic for the same input', () => {
    expect(colourForNodeType('person')).toBe(colourForNodeType('person'));
  });

  it('returns a CSS hex colour', () => {
    expect(colourForNodeType('concept')).toMatch(/^#[0-9a-f]{6}$/i);
  });
});
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
cd wikantik-frontend && npx vitest run src/components/kgraph/kg-graph-data.test.js 2>&1 | tail -10
```

Expected: failure with `Cannot find module './kg-graph-data.js'`.

- [ ] **Step 3: Create `kg-graph-data.js`**

```js
import { toCytoscapeElements as baseToCy } from '../pagegraph/graph-data.js';

const TYPE_PALETTE = [
  '#6e8efb', '#e35d6a', '#5cb85c', '#f0ad4e',
  '#9b59b6', '#1abc9c', '#34495e', '#e67e22',
  '#16a085', '#c0392b',
];

function hash(str) {
  let h = 0;
  for (let i = 0; i < str.length; i++) {
    h = ((h << 5) - h) + str.charCodeAt(i);
    h |= 0;
  }
  return Math.abs(h);
}

export function colourForNodeType(nodeType) {
  if (!nodeType) return '#888888';
  return TYPE_PALETTE[hash(nodeType) % TYPE_PALETTE.length];
}

export function toKgCytoscapeElements(snapshot, filter) {
  const base = baseToCy(snapshot, filter);
  const nodeIndex = new Map(snapshot.nodes.map(n => [n.id, n]));

  const nodes = base.nodes.map(el => {
    const src = nodeIndex.get(el.data.id);
    if (!src) return el;
    return {
      ...el,
      data: {
        ...el.data,
        type:           src.type,
        provenance:     src.provenance,
        status:         src.status,
        tier:           src.tier,
        nodeTypeColor:  colourForNodeType(src.type),
      },
    };
  });

  return { nodes, edges: base.edges };
}
```

- [ ] **Step 4: Run the test and verify it passes**

```bash
cd wikantik-frontend && npx vitest run src/components/kgraph/kg-graph-data.test.js 2>&1 | tail -10
```

Expected: 4 tests pass.

- [ ] **Step 5: Create `kg-graph-style.js`**

The KG stylesheet copies the Page Graph stylesheet and overrides the base node `background-color` selector to use `data(nodeTypeColor)`. Read the existing Page Graph stylesheet first to see exactly what's there:

```bash
cat wikantik-frontend/src/components/pagegraph/graph-style.js
```

Then create `wikantik-frontend/src/components/kgraph/kg-graph-style.js`. Start by re-exporting the base stylesheet and append KG overrides. The pattern:

```js
import { stylesheet as baseStylesheet } from '../pagegraph/graph-style.js';

const kgOverrides = [
  {
    selector: 'node',
    style: { 'background-color': 'data(nodeTypeColor)' },
  },
];

export const kgGraphStylesheet = [...baseStylesheet, ...kgOverrides];
```

(If `pagegraph/graph-style.js` exports under a different name — for example `default` — adjust accordingly. Confirm with `grep "export" wikantik-frontend/src/components/pagegraph/graph-style.js`.)

`KgGraphCanvas` is **not** created in this task — `GraphCanvas` is reused unchanged. The stylesheet override happens via the `stylesheet` prop on the existing component. Look at how `GraphCanvas` consumes its stylesheet (it likely accepts a `stylesheet` prop or imports the array directly). If the existing `GraphCanvas` does **not** accept a `stylesheet` prop, instead of patching `GraphCanvas`, copy it to `kgraph/KgGraphCanvas.jsx` and switch the import in this single file. Defer that decision to step 7.

- [ ] **Step 6: Create `KgGraphDetailsDrawer.jsx`**

```jsx
import GraphDetailsDrawer from '../pagegraph/GraphDetailsDrawer.jsx';
import { Link } from 'react-router-dom';

export default function KgGraphDetailsDrawer({ selectedNode, incidentEdges, onClose, onSelectNeighbor }) {
  if (!selectedNode) return null;

  return (
    <div className="graph-details-drawer kg-details-drawer">
      <button type="button" className="drawer-close" onClick={onClose} aria-label="Close">×</button>
      <h3>{selectedNode.name}</h3>
      <dl className="kg-node-attrs">
        <dt>Type</dt>
        <dd>{selectedNode.type || '—'}</dd>
        <dt>Provenance</dt>
        <dd>{selectedNode.provenance || '—'}</dd>
        <dt>Status</dt>
        <dd>{selectedNode.status || '—'}</dd>
        <dt>Tier</dt>
        <dd>{selectedNode.tier || '—'}</dd>
        {selectedNode.cluster && (<><dt>Cluster</dt><dd>{selectedNode.cluster}</dd></>)}
      </dl>
      <h4>Incident edges ({incidentEdges.length})</h4>
      <ul className="kg-incident-edges">
        {incidentEdges.map((e) => (
          <li key={e.id}>
            <span className={`edge-direction edge-direction-${e.direction}`}>{e.direction === 'in' ? '←' : '→'}</span>{' '}
            <button type="button" className="neighbor-link" onClick={() => onSelectNeighbor(e.neighborId)}>
              {e.neighborName || '(restricted)'}
            </button>{' '}
            <span className="edge-type">{e.relationshipType}</span>
          </li>
        ))}
      </ul>
      <Link to={`/admin/knowledge-graph?focus=${encodeURIComponent(selectedNode.name || '')}`} className="kg-admin-link">
        Open in admin →
      </Link>
    </div>
  );
}
```

(The exact layout strings — `edge-direction-${e.direction}`, `kg-incident-edges` etc. — should match the existing Page Graph CSS class conventions. If `GraphDetailsDrawer.jsx` uses different class names, mirror them.)

- [ ] **Step 7: Wire kg overrides into `KnowledgeGraphView`**

Edit `KnowledgeGraphView.jsx`:

(a) Replace the `toCytoscapeElements` import:

```js
import { toKgCytoscapeElements } from './kg-graph-data.js';
```

…and remove the now-unused `import { toCytoscapeElements } from '../pagegraph/graph-data.js';`.

(b) In the `elements` `useMemo`, swap `toCytoscapeElements` → `toKgCytoscapeElements`.

(c) Replace the `<GraphDetailsDrawer …/>` JSX with `<KgGraphDetailsDrawer …/>`. Add the import at the top:

```js
import KgGraphDetailsDrawer from './KgGraphDetailsDrawer.jsx';
```

(d) Pass the kg stylesheet to `GraphCanvas` via a new prop. **First check whether `GraphCanvas` already accepts a `stylesheet` prop** by reading `wikantik-frontend/src/components/pagegraph/GraphCanvas.jsx`. If yes, add `stylesheet={kgGraphStylesheet}`. If no, two options:
1. Add a `stylesheet` prop to `GraphCanvas` that defaults to the existing constant — purely additive change to a shared component.
2. Copy `GraphCanvas.jsx` to `kgraph/KgGraphCanvas.jsx` with the kg stylesheet hard-coded, and import that here.

Pick (1) if `GraphCanvas` is small and the change is local; pick (2) if `GraphCanvas` is large or its stylesheet is buried in non-trivial logic. Document the choice with a one-line comment in the diff.

- [ ] **Step 8: Run all kgraph tests**

```bash
cd wikantik-frontend && npx vitest run src/components/kgraph/ 2>&1 | tail -15
```

Expected: 8 prior tests + 4 new (`kg-graph-data.test.js`) all pass.

- [ ] **Step 9: Update `KgGraphLegend` with node-type swatches**

Replace `wikantik-frontend/src/components/kgraph/KgGraphLegend.jsx`:

```jsx
import { useMemo } from 'react';
import GraphLegend from '../pagegraph/GraphLegend.jsx';
import { colourForNodeType } from './kg-graph-data.js';

export default function KgGraphLegend({ hubDegreeThreshold, timestamp, machineCount, humanCount, observedTypes }) {
  const swatches = useMemo(() => {
    if (!observedTypes) return [];
    return [...observedTypes].sort().map(t => ({ type: t, colour: colourForNodeType(t) }));
  }, [observedTypes]);

  return (
    <div>
      <div style={{ fontSize: '0.7rem', opacity: 0.8, padding: '2px 8px' }}>
        Tier: {machineCount} machine, {humanCount} human
      </div>
      {swatches.length > 0 && (
        <div style={{ fontSize: '0.7rem', padding: '2px 8px' }}>
          <strong>Types:</strong>{' '}
          {swatches.map(s => (
            <span key={s.type} style={{ marginRight: '8px', whiteSpace: 'nowrap' }}>
              <span style={{
                display: 'inline-block', width: '10px', height: '10px',
                background: s.colour, marginRight: '3px', verticalAlign: 'middle',
                borderRadius: '50%',
              }} />
              {s.type}
            </span>
          ))}
        </div>
      )}
      <GraphLegend hubDegreeThreshold={hubDegreeThreshold} timestamp={timestamp} />
    </div>
  );
}
```

In `KnowledgeGraphView.jsx` compute `observedTypes`:

```jsx
const observedTypes = useMemo(() => {
  if (!snapshot) return new Set();
  return new Set(snapshot.nodes.map(n => n.type).filter(Boolean));
}, [snapshot]);
```

…and pass it: `<KgGraphLegend … observedTypes={observedTypes} />`.

- [ ] **Step 10: Browser smoke**

Rebuild and redeploy (same commands as Task 3 step 8). Confirm:
- Nodes are now coloured by `type` rather than uniformly.
- The legend displays node-type swatches.
- Clicking a node opens the kg drawer with type/provenance/status/tier.
- The "Open in admin →" link routes to `/admin/knowledge-graph?focus=…`.

- [ ] **Step 11: Commit**

```bash
git add wikantik-frontend/src/components/kgraph/kg-graph-data.js \
        wikantik-frontend/src/components/kgraph/kg-graph-data.test.js \
        wikantik-frontend/src/components/kgraph/kg-graph-style.js \
        wikantik-frontend/src/components/kgraph/KgGraphDetailsDrawer.jsx \
        wikantik-frontend/src/components/kgraph/KgGraphLegend.jsx \
        wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx \
        wikantik-frontend/src/components/pagegraph/GraphCanvas.jsx \
        # ...if GraphCanvas was modified to accept stylesheet prop
git commit -m "feat(kg-view): node-type colour coding + KG drawer (Phase 3)"
```

---

## Task 5: Phase 4 — provenance and status styling

**Goal:** Cytoscape stylesheet carries per-`provenance` and per-`status` selectors so visual cues (border style, opacity, dashed outline) communicate node state at a glance. The drawer's `Provenance` field renders as a coloured pill rather than plain text.

**Files:**
- Modify: `wikantik-frontend/src/components/kgraph/kg-graph-style.js`
- Modify: `wikantik-frontend/src/components/kgraph/KgGraphDetailsDrawer.jsx`
- Modify: `wikantik-frontend/src/components/kgraph/KgGraphLegend.jsx`

- [ ] **Step 1: Add stylesheet rules**

Append these entries to `kgOverrides` in `kg-graph-style.js`:

```js
{
  selector: 'node[provenance = "AI_INFERRED"]',
  style: { 'border-width': 1, 'border-style': 'dashed', 'border-color': '#888' },
},
{
  selector: 'node[provenance = "AI_REVIEWED"]',
  style: { 'border-width': 2, 'border-style': 'solid',  'border-color': '#3b82f6' },
},
{
  selector: 'node[provenance = "HUMAN_AUTHORED"]',
  style: { 'border-width': 2, 'border-style': 'solid',  'border-color': '#16a085' },
},
{
  selector: 'node[status = "stub"]',
  style: { 'border-style': 'dashed' },
},
{
  selector: 'node[status = "deprecated"]',
  style: { 'opacity': 0.5, 'background-color': '#888' },
},
```

- [ ] **Step 2: Render provenance as a coloured pill in the drawer**

In `KgGraphDetailsDrawer.jsx`, replace the plain `dd` for provenance with:

```jsx
<dt>Provenance</dt>
<dd>
  <span className={`kg-prov-pill kg-prov-${(selectedNode.provenance || 'unknown').toLowerCase()}`}>
    {selectedNode.provenance || '—'}
  </span>
</dd>
```

Add the matching CSS to `wikantik-frontend/src/components/pagegraph/graph.css` (or a sibling kg-specific CSS file) — a short rule per provenance value:

```css
.kg-prov-pill { display: inline-block; padding: 1px 8px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
.kg-prov-human_authored { background: #16a085; color: white; }
.kg-prov-ai_reviewed    { background: #3b82f6; color: white; }
.kg-prov-ai_inferred    { background: #ddd;    color: #333;  }
.kg-prov-unknown        { background: #eee;    color: #888;  }
```

(A dedicated kg CSS file is fine — create `wikantik-frontend/src/components/kgraph/kg-graph.css` and import it from `KnowledgeGraphView.jsx`.)

- [ ] **Step 3: Add a provenance key to the legend**

In `KgGraphLegend.jsx`, append a small section below the type swatches:

```jsx
<div style={{ fontSize: '0.7rem', padding: '2px 8px' }}>
  <strong>Provenance:</strong>{' '}
  <span style={{ marginRight: '8px' }}>
    <span style={{ display: 'inline-block', width: '10px', height: '10px', background: '#16a085', marginRight: '3px', verticalAlign: 'middle' }} /> human
  </span>
  <span style={{ marginRight: '8px' }}>
    <span style={{ display: 'inline-block', width: '10px', height: '10px', background: '#3b82f6', marginRight: '3px', verticalAlign: 'middle' }} /> AI-reviewed
  </span>
  <span>
    <span style={{ display: 'inline-block', width: '10px', height: '10px', border: '1px dashed #888', marginRight: '3px', verticalAlign: 'middle' }} /> AI-inferred
  </span>
</div>
```

- [ ] **Step 4: Run the unit suite**

```bash
cd wikantik-frontend && npx vitest run src/components/kgraph/ 2>&1 | tail -10
```

Expected: all tests still pass.

- [ ] **Step 5: Browser smoke test**

Rebuild + redeploy. Confirm visually:
- AI-inferred nodes have a dashed grey border.
- HUMAN_AUTHORED nodes (if any) have a solid green border.
- Stub nodes show a dashed border.
- The drawer's Provenance field is now a coloured pill.
- The legend shows the provenance colour key.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/kgraph/kg-graph-style.js \
        wikantik-frontend/src/components/kgraph/KgGraphDetailsDrawer.jsx \
        wikantik-frontend/src/components/kgraph/KgGraphLegend.jsx \
        wikantik-frontend/src/components/kgraph/kg-graph.css \
        wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx
git commit -m "feat(kg-view): provenance and status styling (Phase 4)"
```

---

## Task 6: Phase 5 — tier badge on nodes

**Goal:** Once `SnapshotNode.tier` is on the wire (Task 2), nodes with `tier === "human"` show a gold/amber 2px solid border so human-validated entities stand out at a glance. Legend gains a "Gold border = human-validated" entry.

**Files:**
- Modify: `wikantik-frontend/src/components/kgraph/kg-graph-style.js`
- Modify: `wikantik-frontend/src/components/kgraph/KgGraphLegend.jsx`

- [ ] **Step 1: Append the tier rule to `kg-graph-style.js`**

```js
{
  selector: 'node[tier = "human"]',
  style: { 'border-width': 2, 'border-style': 'solid', 'border-color': '#f0b400' },
},
```

This rule lands **after** the provenance rules so it overrides `border-color` for `HUMAN_AUTHORED` nodes that are also `tier = "human"` (most are). Tier wins because it is the more user-relevant signal in the KG context (was this entity reviewed by a human?), whereas provenance answers a different question (was the entity *authored* by hand?). If you want both signals visible simultaneously, encode tier as a glow/halo instead — but ship Phase 5 with the simple border rule first; reconsider only if reviewers ask.

- [ ] **Step 2: Add the legend entry**

In `KgGraphLegend.jsx`, append below the provenance section:

```jsx
<div style={{ fontSize: '0.7rem', padding: '2px 8px' }}>
  <strong>Tier:</strong>{' '}
  <span>
    <span style={{ display: 'inline-block', width: '10px', height: '10px', border: '2px solid #f0b400', marginRight: '3px', verticalAlign: 'middle' }} />
    human-validated
  </span>
</div>
```

- [ ] **Step 3: Browser smoke test**

Rebuild + redeploy. Confirm the gold border appears on whatever proportion of nodes carries `tier === "human"`. If no nodes have tier `human` in your test corpus, manually flip one in the database:

```sql
-- in psql against the local jspwiki database
UPDATE kg_nodes SET tier = 'human' WHERE name ILIKE 'Alan%' LIMIT 1;
```

Refresh the page and confirm exactly one node shows the gold border.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/kgraph/kg-graph-style.js \
        wikantik-frontend/src/components/kgraph/KgGraphLegend.jsx
git commit -m "feat(kg-view): tier badge gold border for human-validated nodes (Phase 5)"
```

---

## Task 7: Phase 6 — large-graph node-count warning

**Goal:** When the snapshot has more than 500 nodes, the toolbar shows a warning chip ("Large graph: 1234 nodes — layout may be approximate"). This sets user expectations before the cose-bilkent layout potentially times out at 15 seconds.

**Files:**
- Modify: `wikantik-frontend/src/components/kgraph/KgGraphToolbar.jsx`
- Modify: `wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx`

- [ ] **Step 1: Add a `nodeCount` prop to `KgGraphToolbar` and render the warning**

```jsx
import GraphToolbar from '../pagegraph/GraphToolbar.jsx';

export default function KgGraphToolbar({
  onFitToView, onRefresh, onToggleAnomalies, onToggleEdgeType,
  edgeTypes, hiddenEdgeTypes, onlyAnomalies, timestamp,
  minTier, onTierChange, nodeCount,
}) {
  const showLargeWarning = typeof nodeCount === 'number' && nodeCount > 500;

  return (
    <div className="kg-graph-toolbar-wrapper" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
      <label htmlFor="kg-tier-select" style={{ fontSize: '0.75rem', opacity: 0.85 }}>Tier:</label>
      <select id="kg-tier-select" aria-label="Tier" value={minTier} onChange={(e) => onTierChange(e.target.value)}
              style={{ fontSize: '0.75rem', padding: '2px 6px' }}>
        <option value="machine">machine (broader)</option>
        <option value="human">human (strict)</option>
      </select>
      {showLargeWarning && (
        <span className="kg-large-graph-warning" title="Cytoscape layout may be approximate at this size."
              style={{ fontSize: '0.7rem', background: '#fff3cd', color: '#856404',
                       padding: '2px 6px', borderRadius: '4px', whiteSpace: 'nowrap' }}>
          Large graph: {nodeCount} nodes — layout may be approximate
        </span>
      )}
      <GraphToolbar
        onFitToView={onFitToView}
        onRefresh={onRefresh}
        onToggleAnomalies={onToggleAnomalies}
        onToggleEdgeType={onToggleEdgeType}
        edgeTypes={edgeTypes}
        hiddenEdgeTypes={hiddenEdgeTypes}
        onlyAnomalies={onlyAnomalies}
        timestamp={timestamp}
      />
    </div>
  );
}
```

- [ ] **Step 2: Pass `nodeCount` from `KnowledgeGraphView`**

In the `<KgGraphToolbar …/>` invocation in `KnowledgeGraphView.jsx`, add `nodeCount={snapshot?.nodeCount || 0}`.

- [ ] **Step 3: Browser smoke**

The local KG corpus has 1020 nodes (verified during the Task 2/3 smoke), so the warning should appear immediately on page load. Rebuild + redeploy and confirm.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/kgraph/KgGraphToolbar.jsx \
        wikantik-frontend/src/components/kgraph/KnowledgeGraphView.jsx
git commit -m "feat(kg-view): large-graph node-count warning (Phase 6)"
```

---

## Task 8: Selenide IT — `KnowledgeGraphViewerIT`

**Goal:** A Selenide IT loads `/knowledge-graph`, asserts the React route renders, the tier dropdown is present, dropdown-driven URL updates work, and the Sidebar contains the new link. Assertions are robust to the database being empty (matching the pattern in `KnowledgeGraphVisualizationIT`).

**Files:**
- Find: existing pattern at `wikantik-it-tests/wikantik-it-test-selenide-spa/src/main/java/com/wikantik/its/KnowledgeGraphVisualizationIT.java` (the spec quotes a slightly different path; verify with the find command below)
- Create: `wikantik-it-tests/wikantik-it-test-selenide-spa/src/main/java/com/wikantik/its/KnowledgeGraphViewerIT.java`

- [ ] **Step 1: Locate the existing graph IT and the right module to add to**

```bash
find wikantik-it-tests -name "KnowledgeGraphVisualizationIT.java" -not -path "*/target/*"
find wikantik-it-tests -name "PageGraphViewerIT.java"             -not -path "*/target/*"
```

Pick whichever module the visualization IT lives in — that's where the new IT goes. The path is most likely `wikantik-it-tests/wikantik-it-test-selenide-spa/src/main/java/com/wikantik/its/`. Adjust paths in step 2 if the actual location differs.

- [ ] **Step 2: Write the IT**

```java
package com.wikantik.its;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KnowledgeGraphViewerIT extends BaseSelenideIT {

    @Test
    public void route_loads_without_error() {
        open( "/knowledge-graph" );
        // Either the canvas mounts or the empty/error state mounts — both are valid endings.
        $( "[data-testid='graph-canvas'], [data-testid='graph-error-state']" )
            .shouldBe( Condition.visible );
    }

    @Test
    public void tier_dropdown_visible() {
        open( "/knowledge-graph" );
        $( "select#kg-tier-select" ).shouldBe( Condition.visible );
        $( "select#kg-tier-select option[value='machine']" ).shouldBe( Condition.exist );
        $( "select#kg-tier-select option[value='human']" ).shouldBe( Condition.exist );
    }

    @Test
    public void tier_dropdown_changes_url() {
        open( "/knowledge-graph" );
        $( "select#kg-tier-select" ).selectOptionByValue( "human" );
        // Allow a beat for the URL replaceState + re-fetch.
        Selenide.Wait().until( wd -> WebDriverRunner.url().contains( "tier=human" ) );
        assertTrue( WebDriverRunner.url().contains( "tier=human" ) );
    }

    @Test
    public void sidebar_link_present() {
        open( "/knowledge-graph" );
        $( "a.sidebar-link[href='/knowledge-graph']" ).shouldBe( Condition.visible );
    }
}
```

(`BaseSelenideIT` is the existing parent class used by other Selenide ITs — confirm the package and superclass name with `grep -n "extends" wikantik-it-tests/.../KnowledgeGraphVisualizationIT.java`. If the visualization IT does not have a base class, follow whatever pattern it uses for `Configuration.baseUrl` and Cargo lifecycle.)

- [ ] **Step 3: Run the IT (sequential — never `-T`)**

Per CLAUDE.md, ITs run sequentially with `-fae`:

```bash
mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-selenide-spa -am -Dit.test=KnowledgeGraphViewerIT 2>&1 | tail -50
```

Expected: 4 tests pass. If they fail because the embedded Tomcat hasn't loaded the new web.xml mapping or the new `kgraph/` files, recheck Tasks 1, 3, 6 are committed — Cargo deploys whatever is in the workspace at IT launch time.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-selenide-spa/src/main/java/com/wikantik/its/KnowledgeGraphViewerIT.java
git commit -m "test(kg-view): Selenide IT for /knowledge-graph route + tier dropdown"
```

---

## Task 9: Final verification + memory update

**Goal:** All tests green, manual smoke against a live deploy passes, memory file records the design + plan as complete.

- [ ] **Step 1: Full unit pass**

```bash
mvn clean install -T 1C -DskipITs 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Full IT pass (sequential, fail-at-end)**

```bash
mvn clean install -Pintegration-tests -fae 2>&1 | tail -20
```

Expected: BUILD SUCCESS. The new `KnowledgeGraphViewerIT` is included; the rest of the IT suite stays green.

- [ ] **Step 3: Apache RAT licence check**

```bash
mvn apache-rat:check 2>&1 | tail -10
```

Expected: zero violations.

- [ ] **Step 4: Manual end-to-end smoke against the local deploy**

```bash
tomcat/tomcat-11/bin/shutdown.sh; sleep 3
rm -rf tomcat/tomcat-11/webapps/ROOT tomcat/tomcat-11/webapps/ROOT.war
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

Open the browser and check:

1. `http://localhost:8080/` — Sidebar contains `Knowledge Graph` link directly below `Page Graph`. Click it.
2. The page loads at `/knowledge-graph`. The "Knowledge Graph — edges are LLM-extracted relations" banner is visible. The canvas renders.
3. The Tier dropdown is visible to the left of the toolbar buttons. Default is `machine (broader)`.
4. Switch to `human (strict)`. URL becomes `/knowledge-graph?tier=human`. The canvas reloads with a smaller graph (or the same if all nodes are already human-tier in your DB).
5. Switch back. URL clears to `/knowledge-graph`. Canvas reloads.
6. Click a node. The KG-specific drawer opens, showing Type / Provenance (as a coloured pill) / Status / Tier / Cluster.
7. The "Open in admin →" link in the drawer routes to `/admin/knowledge-graph?focus=<NodeName>`.
8. The legend in the bottom-right shows: tier counts, type swatches, provenance key, tier badge entry.
9. If the snapshot has > 500 nodes (it does — local has 1020), the toolbar shows the "Large graph: N nodes" warning chip.
10. Refresh the browser at `/knowledge-graph?tier=human`. The dropdown initialises to `human` on mount (URL → state).
11. Open `http://localhost:8080/knowledge-graph` directly via address bar (not from the sidebar) — confirm `SpaRoutingFilter` forwards correctly to `index.html` (no Tomcat 404 page).
12. Tail the application log during interaction to confirm no new exceptions:
    ```bash
    tail -f tomcat/tomcat-11/logs/jspwiki/jspwiki.log | grep -E "ERROR|Exception|WARN.*graph"
    ```
    Expected: only the pre-existing `structural-index-bootstrap` PSQL warnings (canonical_id duplicate key, page_verification fk) — those are unrelated. No new warnings tied to `KnowledgeGraphResource` or `kgraph/` should appear.

- [ ] **Step 5: Memory update**

Append to `/home/jakefear/.claude/projects/-home-jakefear-source-jspwiki/memory/MEMORY.md`:

```markdown
- [Knowledge Graph Viewer shipped](project_kg_viewer_shipped.md) — `/knowledge-graph` SPA route mirrors `/page-graph` + tier filter + node-type colours; design + plan dated 2026-05-02
```

…and create `/home/jakefear/.claude/projects/-home-jakefear-source-jspwiki/memory/project_kg_viewer_shipped.md`:

```markdown
---
name: Knowledge Graph Viewer shipped
description: /knowledge-graph SPA route mirroring /page-graph + tier dropdown + node-type colours + provenance/status styling — Phase 1-6 complete
type: project
---

`/knowledge-graph` reader route landed 2026-05-02 across 8 commits. Reuses every `wikantik-frontend/src/components/pagegraph/*` sub-component; KG-specific overlays live in `wikantik-frontend/src/components/kgraph/`. Phase 2 added `String tier` to the shared `SnapshotNode` record (positional argument; every existing call site updated). The backend already populated `KgNode.tier`; nothing else moved. Selenide coverage in `KnowledgeGraphViewerIT`. Design at `docs/superpowers/specs/2026-05-02-knowledge-graph-viewer-design.md`. Plan at `docs/superpowers/plans/2026-05-02-knowledge-graph-viewer.md`.

**Why:** Dual-graph operator UX — Page Graph for "how do my pages link?" and Knowledge Graph for "what entities did the LLM extract?" Tier dropdown lets you flip between the broad machine-extracted graph and the curated human-reviewed subset.

**How to apply:** When somebody asks for "the graph viewer", clarify which one. When touching `SnapshotNode`, remember it now carries `tier` and is shared between both subsystems.
```

- [ ] **Step 6: Final commit + push**

```bash
git add /home/jakefear/.claude/projects/-home-jakefear-source-jspwiki/memory/MEMORY.md \
        /home/jakefear/.claude/projects/-home-jakefear-source-jspwiki/memory/project_kg_viewer_shipped.md
git commit -m "chore(memory): record kg-viewer shipping (Phase 1-6 + IT)"
git push origin main
```

---

## Final verification checklist

- [ ] `mvn clean install -T 1C -DskipITs` passes.
- [ ] `mvn clean install -Pintegration-tests -fae` passes.
- [ ] `mvn apache-rat:check` reports no violations.
- [ ] Manual smoke (Task 9 step 4 items 1-12) all pass.
- [ ] Memory updated.
- [ ] Branch pushed.

---

## Self-Review Notes

Coverage check against `docs/superpowers/specs/2026-05-02-knowledge-graph-viewer-design.md`:

| Spec section | Implemented in task |
|--------------|---------------------|
| §4 Files to create — `KnowledgeGraphView.jsx` | Task 1 step 4 |
| §4 Files to create — `KgGraphToolbar.jsx` | Task 3 step 4 |
| §4 Files to create — `KgGraphLegend.jsx` | Task 3 step 5; Task 4 step 9; Task 5 step 3; Task 6 step 2 |
| §4 Files to create — `KgGraphDetailsDrawer.jsx` | Task 4 step 6; Task 5 step 2 |
| §4 Files to create — `kg-graph-data.js` | Task 4 step 3 |
| §4 Files to create — `kg-graph-style.js` | Task 4 step 5; Task 5 step 1; Task 6 step 1 |
| §4 Files to create — `KnowledgeGraphView.test.jsx` | Task 1 step 1; Task 3 step 2 |
| §4 Files to modify — `main.jsx` | Task 1 step 6 |
| §4 Files to modify — `App.jsx` | Task 1 step 7 |
| §4 Files to modify — `Sidebar.jsx` | Task 1 step 8 |
| §4 Files to modify — `client.js` | Task 3 step 1 |
| §4 Files to modify — `web.xml` | Task 1 step 9 |
| §4 Files to modify — `SnapshotNode.java` | Task 2 step 4 |
| §5 Data Flow — fetch state machine | Task 1 step 4 |
| §5 Data Flow — tier change re-fetch | Task 3 step 6 |
| §5 Data Flow — Escape closes drawer | Task 1 step 4 (carried from PageGraphView) |
| §6 UI Parity Matrix | Task 1 step 4 reuses every named sub-component; Task 4 swaps in kg variants for drawer/toolbar/legend |
| §7a Tier filter dropdown + URL contract | Task 3 |
| §7b Provenance in tooltip | Task 4 step 3 (label includes provenance via baseToCy then enriched) — multi-line tooltip not implemented; the drawer carries provenance prominently. If reviewers insist on the multi-line tooltip, see spec §7b for the alternative. |
| §7c Status icon | Task 5 step 1 (status=stub / status=deprecated CSS) |
| §7d Tier badge | Task 6 |
| §7e Node-type colour coding | Task 4 |
| §8 Routing and SPA Shell | Task 1 steps 6-9 |
| §10 Vitest unit tests for `KnowledgeGraphView` | Task 1 step 1; Task 3 step 2 |
| §10 Vitest unit tests for `kg-graph-data` | Task 4 step 1 |
| §10 Selenide IT | Task 8 |
| §11 Risk 1 — `SnapshotNode.tier` gap | Task 2 (closes the gap by adding the field) |
| §11 Risk 2 — `getGraphSnapshot` minTier param | Task 3 step 1 |
| §11 Risk 3 — Large-graph warning | Task 7 |
| §12 Phase 1 — wiring + parity | Task 1 |
| §12 Phase 2 — tier filter + backend `SnapshotNode.tier` | Tasks 2 + 3 |
| §12 Phase 3 — node-type colour | Task 4 |
| §12 Phase 4 — provenance + status | Task 5 |
| §12 Phase 5 — tier badge | Task 6 |
| §12 Phase 6 — large-graph UX | Task 7 |

No placeholders found. Type / signature consistency: the new `String tier` argument to `SnapshotNode` has consistent argument position (last) across Tasks 2/4 references; `getGraphSnapshot({ minTier, signal })` signature is the same in client.js (Task 3 step 1) and in every `api.knowledge.getGraphSnapshot(...)` call site (Tasks 1, 3, 4, 7). `KgGraphToolbar`'s prop names (`minTier`, `onTierChange`, `nodeCount`) match between the components that pass them and the receiver. The kg stylesheet entry order — base → node-type override → provenance rules → status rules → tier rule — is documented in Task 6 step 1 (tier wins over provenance border-color by stylesheet position).
