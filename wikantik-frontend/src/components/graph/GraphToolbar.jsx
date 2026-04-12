import { useState } from 'react';

export default function GraphToolbar({
  onFitToView, onRefresh, onToggleAnomalies, onToggleEdgeType,
  edgeTypes, hiddenEdgeTypes, onlyAnomalies, timestamp,
}) {
  const [filterOpen, setFilterOpen] = useState(false);

  return (
    <div className="graph-toolbar">
      <button onClick={onFitToView}>Fit to view</button>
      <div style={{ position: 'relative' }}>
        <button onClick={() => setFilterOpen(!filterOpen)}>
          Edge filter
        </button>
        {filterOpen && (
          <div className="graph-filter-popover">
            {edgeTypes.map(t => (
              <label key={t}>
                <input
                  type="checkbox"
                  checked={!hiddenEdgeTypes.has(t)}
                  onChange={() => onToggleEdgeType(t)}
                />
                {t}
              </label>
            ))}
          </div>
        )}
      </div>
      <button
        className={onlyAnomalies ? 'active' : ''}
        onClick={onToggleAnomalies}
      >
        Only orphans &amp; stubs
      </button>
      <button onClick={onRefresh}>Refresh</button>
      <span className="snapshot-time">snapshot: {timestamp}</span>
    </div>
  );
}
