import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import { toCytoscapeElements } from './graph-data.js';
import GraphCanvas from './GraphCanvas.jsx';
import GraphToolbar from './GraphToolbar.jsx';
import GraphLegend from './GraphLegend.jsx';
import GraphDetailsDrawer from './GraphDetailsDrawer.jsx';
import GraphErrorState from './GraphErrorState.jsx';
import GraphErrorBoundary from './GraphErrorBoundary.jsx';
import GraphLoadingFallback from './GraphLoadingFallback.jsx';
import './graph.css';

export default function GraphView() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const focusParam = useRef(searchParams.get('focus'));

  const [fetchState, setFetchState] = useState('loading');
  const [errorVariant, setErrorVariant] = useState(null);
  const [snapshot, setSnapshot] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [hiddenEdgeTypes, setHiddenEdgeTypes] = useState(new Set());
  const [onlyAnomalies, setOnlyAnomalies] = useState(false);
  const [layoutDone, setLayoutDone] = useState(false);

  const fetchSnapshot = useCallback(async () => {
    setFetchState('loading');
    setErrorVariant(null);
    try {
      const data = await api.knowledge.getGraphSnapshot();
      setSnapshot(data);
      if (data.nodeCount === 0) {
        setFetchState('error');
        setErrorVariant('empty');
      } else if (data.nodes.every(n => n.restricted)) {
        setFetchState('error');
        setErrorVariant('empty-for-you');
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

  const elements = useMemo(() => {
    if (!snapshot || fetchState !== 'ready') return { nodes: [], edges: [] };
    return toCytoscapeElements(snapshot);
  }, [snapshot, fetchState]);

  const edgeTypes = useMemo(() => {
    if (!snapshot) return [];
    return [...new Set(snapshot.edges.map(e => e.relationshipType))].sort();
  }, [snapshot]);

  const timestamp = useMemo(() => {
    if (!snapshot?.generatedAt) return '';
    try {
      return new Date(snapshot.generatedAt).toLocaleTimeString();
    } catch {
      return snapshot.generatedAt;
    }
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

  const focusNodeId = useMemo(() => {
    if (!focusParam.current || !snapshot) return null;
    const match = snapshot.nodes.find(
      n => n.name === focusParam.current && !n.restricted
    );
    return match?.id || null;
  }, [snapshot]);

  const handleNodeClick = useCallback((nodeId) => setSelectedId(nodeId), []);
  const handleBackgroundClick = useCallback(() => setSelectedId(null), []);

  const handleReady = useCallback(() => {
    setLayoutDone(true);
  }, []);

  const handleOpenPage = useCallback((pageName) => {
    navigate(`/wiki/${encodeURIComponent(pageName)}`);
  }, [navigate]);

  const handleToggleEdgeType = useCallback((type) => {
    setHiddenEdgeTypes(prev => {
      const next = new Set(prev);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return next;
    });
  }, []);

  const handleRefresh = useCallback(async () => {
    const prevSelectedName = selectedNode?.name;
    setFetchState('loading');
    setErrorVariant(null);
    try {
      const data = await api.knowledge.getGraphSnapshot();
      setSnapshot(data);
      if (data.nodeCount === 0) {
        setFetchState('error');
        setErrorVariant('empty');
        return;
      }
      if (data.nodes.every(n => n.restricted)) {
        setFetchState('error');
        setErrorVariant('empty-for-you');
        return;
      }
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
    const handler = (e) => {
      if (e.key === 'Escape') setSelectedId(null);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  if (fetchState === 'loading') return <GraphLoadingFallback />;
  if (fetchState === 'error') return <GraphErrorState variant={errorVariant} onRetry={fetchSnapshot} />;

  return (
    <GraphErrorBoundary>
      <div className="graph-view">
        <GraphToolbar
          onFitToView={() => window.cy?.fit()}
          onRefresh={handleRefresh}
          onToggleAnomalies={() => setOnlyAnomalies(v => !v)}
          onToggleEdgeType={handleToggleEdgeType}
          edgeTypes={edgeTypes}
          hiddenEdgeTypes={hiddenEdgeTypes}
          onlyAnomalies={onlyAnomalies}
          timestamp={timestamp}
        />
        <GraphCanvas
          elements={elements}
          selectedId={selectedId}
          focusNodeId={focusNodeId}
          hiddenEdgeTypes={hiddenEdgeTypes}
          onlyAnomalies={onlyAnomalies}
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
        <GraphLegend
          hubDegreeThreshold={snapshot?.hubDegreeThreshold || 10}
          edgeTypes={edgeTypes}
          timestamp={timestamp}
        />
        {!layoutDone && (
          <div className="graph-layout-overlay">Laying out graph...</div>
        )}
      </div>
    </GraphErrorBoundary>
  );
}
