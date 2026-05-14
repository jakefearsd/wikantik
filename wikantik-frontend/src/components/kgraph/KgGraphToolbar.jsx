import GraphToolbar from '../pagegraph/GraphToolbar.jsx';
import { ENDPOINT_CLASSES } from '../pagegraph/filter-state.js';

export default function KgGraphToolbar({
  onFitToView, onRefresh, onToggleAnomalies, onToggleEdgeType,
  edgeTypes, hiddenEdgeTypes, onlyAnomalies, timestamp,
  minTier, onTierChange, nodeCount,
  endpointClass, onEndpointClassChange,
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
      <label htmlFor="kg-endpoints-select" style={{ fontSize: '0.75rem', opacity: 0.85 }}>Edges:</label>
      <select id="kg-endpoints-select" aria-label="Edge endpoints"
              value={endpointClass ?? ENDPOINT_CLASSES.ALL}
              onChange={(e) => onEndpointClassChange?.(e.target.value)}
              title="Filter edges by the node-type class of their endpoints"
              style={{ fontSize: '0.75rem', padding: '2px 6px' }}>
        <option value={ENDPOINT_CLASSES.ALL}>all endpoints</option>
        <option value={ENDPOINT_CLASSES.HIDE_ARTICLE_ARTICLE}>hide article↔article</option>
        <option value={ENDPOINT_CLASSES.CONCEPT_ONLY}>concepts only</option>
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
