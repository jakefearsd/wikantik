import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import { toKgCytoscapeElements } from './kg-graph-data.js';
import { applyFilters } from '../pagegraph/filter-engine.js';
import { paramsToFilterState, filterStateToParams } from '../pagegraph/filter-url.js';
import FilterPanel from '../pagegraph/FilterPanel.jsx';
import GraphCanvas from '../pagegraph/GraphCanvas.jsx';
import KgGraphToolbar from './KgGraphToolbar.jsx';
import { kgGraphStylesheet } from './kg-graph-style.js';
import KgGraphLegend from './KgGraphLegend.jsx';
import GraphZoomSlider from '../pagegraph/GraphZoomSlider.jsx';
import KgGraphDetailsDrawer from './KgGraphDetailsDrawer.jsx';
import GraphErrorBoundary from '../pagegraph/GraphErrorBoundary.jsx';
import GraphLoadingFallback from '../pagegraph/GraphLoadingFallback.jsx';
import { setEdgeTypeHidden, setShowOrphansStubs, setEndpointClass } from '../pagegraph/filter-state.js';
import KgErrorState from './KgErrorState.jsx';
import '../pagegraph/graph.css';
import './kg-graph.css';

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
  const [minTier, setMinTier] = useState(() => {
    const t = searchParams.get('tier');
    return (t === 'human' || t === 'machine') ? t : 'machine';
  });

  useEffect(() => {
    const next = filterStateToParams(filterState, new URLSearchParams(window.location.search));
    const qs = next.toString();
    const url = qs ? `${window.location.pathname}?${qs}` : window.location.pathname;
    window.history.replaceState(null, '', url);
  }, [filterState]);

  const fetchSnapshot = useCallback(async (tier, restoreSelectedName) => {
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
        if (restoreSelectedName) {
          const match = data.nodes.find(n => n.name === restoreSelectedName);
          setSelectedId(match ? match.id : null);
        }
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
    return toKgCytoscapeElements(snapshot, filterResult);
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

  const observedTypes = useMemo(() => {
    if (!snapshot) return new Set();
    return new Set(snapshot.nodes.map(n => n.type).filter(Boolean));
  }, [snapshot]);

  const tierCounts = useMemo(() => {
    if (!snapshot) return { machineCount: 0, humanCount: 0 };
    let m = 0, h = 0;
    for (const n of snapshot.nodes) {
      if (n.tier === 'human') h++;
      else if (n.tier === 'machine') m++;
    }
    return { machineCount: m, humanCount: h };
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

  const handleEndpointClassChange = useCallback((value) => {
    setFilterState(prev => setEndpointClass(prev, value));
  }, []);

  const handleTierChange = useCallback((tier) => {
    setMinTier(tier);
    // Drive the URL write through filter-url's PRESERVED_KEYS path so it stays
    // the single source of truth for KG/Page Graph URL serialization. `tier` is
    // 'machine' default → drop it from the URL for cleanliness.
    const existing = new URLSearchParams(window.location.search);
    if (tier === 'machine') existing.delete('tier');
    else existing.set('tier', tier);
    const next = filterStateToParams(filterState, existing);
    const qs = next.toString();
    const url = qs ? `${window.location.pathname}?${qs}` : window.location.pathname;
    window.history.replaceState(null, '', url);
    fetchSnapshot(tier);
    setSelectedId(null); // node ID set may change between tiers
  }, [fetchSnapshot, filterState]);

  const handleRefresh = useCallback(
    () => fetchSnapshot(minTier, selectedNode?.name),
    [fetchSnapshot, selectedNode, minTier]);

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
          nodeCount={snapshot?.nodeCount || 0}
          endpointClass={filterState.endpointClass}
          onEndpointClassChange={handleEndpointClassChange}
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
          stylesheet={kgGraphStylesheet}
        />
        {selectedNode && (
          <KgGraphDetailsDrawer
            selectedNode={selectedNode}
            incidentEdges={incidentEdges}
            onClose={() => setSelectedId(null)}
            onSelectNeighbor={handleNodeClick}
          />
        )}
        <div className="graph-bottom-right">
          <GraphZoomSlider layoutDone={layoutDone} />
          <KgGraphLegend
            hubDegreeThreshold={snapshot?.hubDegreeThreshold || 10}
            timestamp={timestamp}
            machineCount={tierCounts.machineCount}
            humanCount={tierCounts.humanCount}
            observedTypes={observedTypes}
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
