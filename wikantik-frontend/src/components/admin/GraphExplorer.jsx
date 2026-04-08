import { useState, useEffect, useRef, useCallback } from 'react';
import { api } from '../../api/client';
import NodeDetail from './NodeDetail';

const LIMIT = 50;

export default function GraphExplorer() {
  const [schema, setSchema] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [selectedNode, setSelectedNode] = useState(null);
  const [projecting, setProjecting] = useState(false);
  const [projectResult, setProjectResult] = useState(null);
  const [offset, setOffset] = useState(0);
  const debounceRef = useRef(null);

  const loadNodes = useCallback(async (currentOffset) => {
    try {
      const data = await api.knowledge.queryNodes({
        name: search || undefined,
        node_type: typeFilter || undefined,
        status: statusFilter || undefined,
        limit: LIMIT,
        offset: currentOffset,
      });
      setNodes(data.nodes || []);
      setError(null);
    } catch (err) {
      setError(err.message);
    }
  }, [search, typeFilter, statusFilter]);

  const loadSchema = async () => {
    try {
      const schemaData = await api.knowledge.getSchema();
      setSchema(schemaData);
    } catch (err) {
      setError(err.message);
    }
  };

  useEffect(() => {
    loadSchema();
  }, []);

  useEffect(() => {
    setOffset(0);
    loadNodes(0).finally(() => setLoading(false));
  }, [loadNodes]);

  useEffect(() => {
    if (offset > 0) {
      loadNodes(offset);
    }
  }, [offset, loadNodes]);

  const handleProjectAll = async () => {
    setProjecting(true);
    setProjectResult(null);
    try {
      const result = await api.knowledge.projectAll();
      setProjectResult(result);
      await Promise.all([loadSchema(), loadNodes(offset)]);
    } catch (err) {
      setProjectResult({ error: err.message });
    } finally {
      setProjecting(false);
    }
  };

  const handleSearchChange = (e) => {
    const value = e.target.value;
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => setSearch(value), 300);
  };

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
            <div style={{ marginTop: '8px' }}>
              <button
                className="btn btn-primary btn-sm"
                onClick={handleProjectAll}
                disabled={projecting}
              >
                {projecting ? 'Projecting...' : 'Project All Pages'}
              </button>
              {projectResult && !projectResult.error && (
                <span style={{ marginLeft: '8px', fontSize: '0.85em' }}>
                  Scanned {projectResult.scanned}, projected {projectResult.projected}
                  {projectResult.errors?.length > 0 && (
                    <span style={{ color: 'var(--warning)' }}>
                      {' '}({projectResult.errors.length} errors)
                    </span>
                  )}
                </span>
              )}
              {projectResult?.error && (
                <span style={{ marginLeft: '8px', color: 'var(--danger)', fontSize: '0.85em' }}>
                  Error: {projectResult.error}
                </span>
              )}
            </div>
          </div>
        )}

        <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)' }}>
          <input
            type="text"
            placeholder="Search nodes..."
            defaultValue={search}
            onChange={handleSearchChange}
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
          <select
            value={statusFilter}
            onChange={e => setStatusFilter(e.target.value)}
            className="form-input"
            style={{ width: '180px' }}
          >
            <option value="">All statuses</option>
            {(schema?.statusValues || schema?.status_values || []).map(s => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>

        <table className="admin-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Status</th>
              <th>Provenance</th>
              <th>Stub</th>
            </tr>
          </thead>
          <tbody>
            {nodes.map(n => (
              <tr
                key={n.id}
                onClick={() => handleNodeClick(n.name)}
                style={{ cursor: 'pointer' }}
                className={selectedNode?.name === n.name ? 'admin-row-selected' : ''}
              >
                <td>{n.name}</td>
                <td style={{ whiteSpace: 'nowrap' }}>{n.node_type || '-'}</td>
                <td>{n.properties?.status || '-'}</td>
                <td style={{ whiteSpace: 'nowrap' }}>{n.provenance}</td>
                <td>{n.is_stub ? 'Yes' : ''}</td>
              </tr>
            ))}
            {nodes.length === 0 && (
              <tr><td colSpan={5} style={{ textAlign: 'center' }}>No nodes found.</td></tr>
            )}
          </tbody>
        </table>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 'var(--space-sm)', fontSize: '0.85em' }}>
          <button className="btn btn-sm" onClick={() => setOffset(Math.max(0, offset - LIMIT))} disabled={offset === 0}>Prev</button>
          <span>Showing {offset + 1}–{offset + nodes.length}</span>
          <button className="btn btn-sm" onClick={() => setOffset(offset + LIMIT)} disabled={nodes.length < LIMIT}>Next</button>
        </div>
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
