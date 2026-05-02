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
