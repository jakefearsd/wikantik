export default function GraphDetailsDrawer({ selectedNode, incidentEdges, onClose, onSelectNeighbor, onOpenPage }) {
  if (!selectedNode) return null;

  const { name, type, role, provenance, degreeIn, degreeOut, sourcePage, restricted } = selectedNode;
  const incoming = incidentEdges.filter(e => e.direction === 'in');
  const outgoing = incidentEdges.filter(e => e.direction === 'out');
  const canOpenPage = !restricted && role !== 'stub' && role !== 'restricted' && sourcePage;

  return (
    <div className="graph-details-drawer">
      <div className="drawer-header">
        <h3>{restricted ? '\u{1F512} (restricted)' : name}</h3>
        <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: '1.2rem' }}>
          {'\u00D7'}
        </button>
      </div>
      <div className="drawer-meta">
        <div>role: {role} · type: {type || '\u2014'}</div>
        <div>provenance: {provenance || '\u2014'}</div>
      </div>
      <div className="drawer-meta">
        Connections: {degreeIn} in · {degreeOut} out
      </div>

      {incoming.length > 0 && (
        <>
          <div className="drawer-section-title">Incoming ({incoming.length})</div>
          {incoming.map((e, i) => (
            <EdgeRow key={i} edge={e} prefix={'\u2190'} onSelect={onSelectNeighbor} />
          ))}
        </>
      )}

      {outgoing.length > 0 && (
        <>
          <div className="drawer-section-title">Outgoing ({outgoing.length})</div>
          {outgoing.map((e, i) => (
            <EdgeRow key={i} edge={e} prefix={'\u2192'} onSelect={onSelectNeighbor} />
          ))}
        </>
      )}

      <button
        className="open-page-btn"
        disabled={!canOpenPage}
        onClick={() => canOpenPage && onOpenPage(sourcePage)}
      >
        Open page {'\u2192'}
      </button>
    </div>
  );
}

function EdgeRow({ edge, prefix, onSelect }) {
  const { neighborId, neighborName, neighborRestricted, relationshipType } = edge;
  if (neighborRestricted) {
    return (
      <div className="edge-row restricted">
        <span>{prefix}</span>
        <span className="edge-type">{relationshipType}</span>
        <span>{'\u{1F512}'} (restricted)</span>
      </div>
    );
  }
  return (
    <div className="edge-row" onClick={() => onSelect(neighborId)}>
      <span>{prefix}</span>
      <span className="edge-type">{relationshipType}</span>
      <span>{neighborName}</span>
    </div>
  );
}
