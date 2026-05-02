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
