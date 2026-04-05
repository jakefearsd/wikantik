import { api } from '../../api/client';
import ProvenanceBadge from './ProvenanceBadge';

export default function NodeDetail({ node, onNavigate }) {
  if (!node) return null;

  const outbound = (node.edges || []).filter(e => e.source_id === node.id);
  const inbound = (node.edges || []).filter(e => e.target_id === node.id);

  const handleDelete = async () => {
    if (!confirm(`Delete node "${node.name}"? This will also remove all its edges.`)) return;
    await api.knowledge.deleteNode(node.id);
    window.location.reload();
  };

  return (
    <div style={{ padding: 'var(--space-md)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-sm)' }}>
        <h3 style={{ margin: 0 }}>{node.name}</h3>
        <button className="btn btn-sm btn-danger" onClick={handleDelete}>Delete</button>
      </div>

      <div style={{ fontSize: '0.9em', marginBottom: 'var(--space-md)' }}>
        <div><strong>Type:</strong> {node.node_type || '-'}</div>
        <div><strong>Provenance:</strong> <ProvenanceBadge value={node.provenance} /></div>
        {node.source_page && (
          <div><strong>Source page:</strong>{' '}
            <a href={`/wiki/${node.source_page.replace('.md', '')}`}>{node.source_page}</a>
          </div>
        )}
        {node.is_stub && <div style={{ color: 'var(--warning)', fontStyle: 'italic' }}>Stub node (no wiki page yet)</div>}
      </div>

      {node.properties && Object.keys(node.properties).length > 0 && (
        <div style={{ marginBottom: 'var(--space-md)' }}>
          <h4 style={{ fontSize: '0.9em', marginBottom: '4px' }}>Properties</h4>
          <table className="admin-table" style={{ fontSize: '0.85em' }}>
            <tbody>
              {Object.entries(node.properties).map(([k, v]) => (
                <tr key={k}>
                  <td style={{ fontWeight: 500 }}>{k}</td>
                  <td>{typeof v === 'object' ? JSON.stringify(v) : String(v)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {outbound.length > 0 && (
        <div style={{ marginBottom: 'var(--space-md)' }}>
          <h4 style={{ fontSize: '0.9em', marginBottom: '4px' }}>Outbound Edges ({outbound.length})</h4>
          <table className="admin-table" style={{ fontSize: '0.85em' }}>
            <thead>
              <tr><th>Relationship</th><th>Target</th><th>Provenance</th></tr>
            </thead>
            <tbody>
              {outbound.map(e => (
                <tr key={e.id}>
                  <td>{e.relationship_type}</td>
                  <td>
                    <button className="btn-link" onClick={() => onNavigate && onNavigate(e.target_name)}>
                      {e.target_name || e.target_id}
                    </button>
                  </td>
                  <td><ProvenanceBadge value={e.provenance} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {inbound.length > 0 && (
        <div>
          <h4 style={{ fontSize: '0.9em', marginBottom: '4px' }}>Inbound Edges ({inbound.length})</h4>
          <table className="admin-table" style={{ fontSize: '0.85em' }}>
            <thead>
              <tr><th>Source</th><th>Relationship</th><th>Provenance</th></tr>
            </thead>
            <tbody>
              {inbound.map(e => (
                <tr key={e.id}>
                  <td>
                    <button className="btn-link" onClick={() => onNavigate && onNavigate(e.source_name)}>
                      {e.source_name || e.source_id}
                    </button>
                  </td>
                  <td>{e.relationship_type}</td>
                  <td><ProvenanceBadge value={e.provenance} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {outbound.length === 0 && inbound.length === 0 && (
        <p className="admin-empty">No edges.</p>
      )}
    </div>
  );
}
