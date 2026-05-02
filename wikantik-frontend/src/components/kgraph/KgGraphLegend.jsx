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
