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
      <GraphLegend hubDegreeThreshold={hubDegreeThreshold} timestamp={timestamp} />
    </div>
  );
}
