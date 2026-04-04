import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import NodeDetail from './NodeDetail';

export default function GraphExplorer() {
  const [schema, setSchema] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [selectedNode, setSelectedNode] = useState(null);

  useEffect(() => {
    (async () => {
      try {
        const [schemaData, nodesData] = await Promise.all([
          api.knowledge.getSchema(),
          api.knowledge.queryNodes({ limit: 200 }),
        ]);
        setSchema(schemaData);
        setNodes(nodesData.nodes || []);
      } catch (err) {
        setError(err.message);
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const filtered = useMemo(() => {
    const q = search.toLowerCase();
    return nodes.filter(n => {
      if (typeFilter && n.node_type !== typeFilter) return false;
      if (q && !n.name.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [nodes, search, typeFilter]);

  const handleNodeClick = async (name) => {
    try {
      const data = await api.knowledge.getNode(name);
      setSelectedNode(data);
    } catch (err) {
      setError(err.message);
    }
  };

  if (loading) return <div className="admin-loading">Loading knowledge graph...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div style={{ display: 'flex', gap: 'var(--space-lg)', minHeight: '400px' }}>
      <div style={{ flex: '1 1 50%' }}>
        {schema && (
          <div style={{ marginBottom: 'var(--space-md)', padding: 'var(--space-sm)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)', fontSize: '0.85em' }}>
            <strong>Schema:</strong> {schema.stats?.nodes || 0} nodes, {schema.stats?.edges || 0} edges
            {schema.stats?.unreviewedProposals > 0 && (
              <span style={{ marginLeft: '8px', color: 'var(--warning)' }}>
                ({schema.stats.unreviewedProposals} pending proposals)
              </span>
            )}
            <div style={{ marginTop: '4px' }}>
              <strong>Types:</strong> {(schema.nodeTypes || schema.node_types || []).join(', ') || 'none'}
            </div>
            <div>
              <strong>Relationships:</strong> {(schema.relationshipTypes || schema.relationship_types || []).join(', ') || 'none'}
            </div>
          </div>
        )}

        <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)' }}>
          <input
            type="text"
            placeholder="Search nodes..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="form-input"
            style={{ flex: 1 }}
          />
          <select
            value={typeFilter}
            onChange={e => setTypeFilter(e.target.value)}
            className="form-input"
            style={{ width: '180px' }}
          >
            <option value="">All types</option>
            {(schema?.nodeTypes || schema?.node_types || []).map(t => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </div>

        <table className="admin-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Provenance</th>
              <th>Stub</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map(n => (
              <tr
                key={n.id}
                onClick={() => handleNodeClick(n.name)}
                style={{ cursor: 'pointer' }}
                className={selectedNode?.name === n.name ? 'admin-row-selected' : ''}
              >
                <td>{n.name}</td>
                <td>{n.node_type || '-'}</td>
                <td>{n.provenance}</td>
                <td>{n.is_stub ? 'Yes' : ''}</td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <tr><td colSpan={4} style={{ textAlign: 'center' }}>No nodes found.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <div style={{ flex: '1 1 50%' }}>
        {selectedNode ? (
          <NodeDetail node={selectedNode} onNavigate={handleNodeClick} />
        ) : (
          <div className="admin-empty" style={{ padding: 'var(--space-lg)', textAlign: 'center' }}>
            Select a node to view details.
          </div>
        )}
      </div>
    </div>
  );
}
