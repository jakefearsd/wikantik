import { useState, useEffect, useRef, useCallback } from 'react';
import { api } from '../../api/client';
import ProvenanceBadge from './ProvenanceBadge';

const LIMIT = 50;

function EdgeDetail({ edge, sourceNode, targetNode, loading, onNavigateNode }) {
  if (!edge) return null;

  const renderNodeCard = (label, node) => {
    if (!node) return <div style={{ fontStyle: 'italic', color: 'var(--text-muted)' }}>Not found</div>;
    return (
      <div style={{ padding: 'var(--space-sm)', background: 'var(--bg-elevated)', borderRadius: 'var(--radius-md)', marginBottom: 'var(--space-sm)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
          <strong style={{ fontSize: '0.95em' }}>
            <button className="btn-link" onClick={() => onNavigateNode(node.name)} style={{ fontWeight: 600 }}>
              {node.name}
            </button>
          </strong>
          <span style={{ fontSize: '0.8em', color: 'var(--text-muted)' }}>{label}</span>
        </div>
        <div style={{ fontSize: '0.85em', display: 'flex', flexWrap: 'wrap', gap: 'var(--space-xs) var(--space-md)' }}>
          <span><strong>Type:</strong> {node.node_type || '-'}</span>
          <span><strong>Provenance:</strong> <ProvenanceBadge value={node.provenance} /></span>
          {node.properties?.status && <span><strong>Status:</strong> {node.properties.status}</span>}
          {node.is_stub && <span style={{ color: 'var(--warning)', fontStyle: 'italic' }}>Stub</span>}
        </div>
        {node.source_page && (
          <div style={{ fontSize: '0.85em', marginTop: '2px' }}>
            <strong>Source page:</strong>{' '}
            <a href={`/wiki/${node.source_page.replace('.md', '')}`}>{node.source_page}</a>
          </div>
        )}
        {node.properties && Object.keys(node.properties).filter(k => k !== 'status').length > 0 && (
          <details style={{ fontSize: '0.85em', marginTop: '4px' }}>
            <summary style={{ cursor: 'pointer', color: 'var(--text-muted)' }}>Properties</summary>
            <table className="admin-table" style={{ fontSize: '0.85em', marginTop: '4px' }}>
              <tbody>
                {Object.entries(node.properties).filter(([k]) => k !== 'status').map(([k, v]) => (
                  <tr key={k}>
                    <td style={{ fontWeight: 500 }}>{k}</td>
                    <td>{typeof v === 'object' ? JSON.stringify(v) : String(v)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        )}
      </div>
    );
  };

  return (
    <div style={{ padding: 'var(--space-md)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)' }}>
      <div style={{ textAlign: 'center', marginBottom: 'var(--space-md)' }}>
        <div style={{ fontSize: '0.8em', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.5px', marginBottom: '4px' }}>
          Relationship
        </div>
        <div style={{ fontSize: '1.1em', fontWeight: 600 }}>
          {edge.source_name || edge.source_id}
          <span style={{ margin: '0 8px', color: 'var(--text-muted)' }}>&rarr;</span>
          <span style={{ whiteSpace: 'nowrap' }}>{edge.relationship_type}</span>
          <span style={{ margin: '0 8px', color: 'var(--text-muted)' }}>&rarr;</span>
          {edge.target_name || edge.target_id}
        </div>
        <div style={{ marginTop: '4px' }}>
          <ProvenanceBadge value={edge.provenance} />
        </div>
      </div>

      {loading ? (
        <div className="admin-loading" style={{ padding: 'var(--space-md)' }}>Loading node details...</div>
      ) : (
        <>
          {renderNodeCard('Source', sourceNode)}
          {renderNodeCard('Target', targetNode)}
        </>
      )}
    </div>
  );
}

export default function EdgeExplorer() {
  const [edges, setEdges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [search, setSearch] = useState('');
  const [relTypeFilter, setRelTypeFilter] = useState('');
  const [relTypes, setRelTypes] = useState([]);
  const [offset, setOffset] = useState(0);
  const [selectedEdge, setSelectedEdge] = useState(null);
  const [sourceNode, setSourceNode] = useState(null);
  const [targetNode, setTargetNode] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const debounceRef = useRef(null);

  const loadEdges = useCallback(async (currentOffset) => {
    try {
      const data = await api.knowledge.queryEdges({
        relationship_type: relTypeFilter || undefined,
        search: search || undefined,
        limit: LIMIT,
        offset: currentOffset,
      });
      setEdges(data.edges || []);
      setError(null);
    } catch (err) {
      setError(err.message);
    }
  }, [relTypeFilter, search]);

  useEffect(() => {
    (async () => {
      try {
        const schema = await api.knowledge.getSchema();
        setRelTypes(schema.relationshipTypes || schema.relationship_types || []);
      } catch (err) {
        setError(err.message);
      }
    })();
  }, []);

  useEffect(() => {
    setOffset(0);
    loadEdges(0).finally(() => setLoading(false));
  }, [loadEdges]);

  useEffect(() => {
    if (offset > 0) {
      loadEdges(offset);
    }
  }, [offset, loadEdges]);

  const handleSearchChange = (e) => {
    const value = e.target.value;
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => setSearch(value), 300);
  };

  const handleEdgeClick = async (edge) => {
    setSelectedEdge(edge);
    setDetailLoading(true);
    setSourceNode(null);
    setTargetNode(null);
    try {
      const [src, tgt] = await Promise.all([
        api.knowledge.getNode(edge.source_name).catch(() => null),
        api.knowledge.getNode(edge.target_name).catch(() => null),
      ]);
      setSourceNode(src);
      setTargetNode(tgt);
    } finally {
      setDetailLoading(false);
    }
  };

  const handleNavigateNode = async (name) => {
    // Find an edge that involves this node and select it, or just update the detail panel
    // For simplicity, navigate to the Node Explorer tab by dispatching a custom event
    // But since we don't control the parent tabs, we'll just show that node's info
    // by fetching it as a "source" with no target
    setDetailLoading(true);
    try {
      const node = await api.knowledge.getNode(name);
      if (node) {
        // Show this node as both source context
        setSourceNode(node);
        setTargetNode(null);
        setSelectedEdge({
          source_name: node.name,
          target_name: '',
          relationship_type: '(viewing node)',
          provenance: node.provenance,
        });
      }
    } finally {
      setDetailLoading(false);
    }
  };

  const handlePrev = () => setOffset(Math.max(0, offset - LIMIT));
  const handleNext = () => setOffset(offset + LIMIT);

  if (loading) return <div className="admin-loading">Loading edges...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div style={{ display: 'flex', gap: 'var(--space-lg)', minHeight: '400px' }}>
      <div style={{ flex: '1 1 50%' }}>
        <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)' }}>
          <input
            type="text"
            placeholder="Search by node name..."
            defaultValue={search}
            onChange={handleSearchChange}
            className="form-input"
            style={{ flex: 1 }}
          />
          <select
            value={relTypeFilter}
            onChange={e => setRelTypeFilter(e.target.value)}
            className="form-input"
            style={{ width: '200px' }}
          >
            <option value="">All relationship types</option>
            {relTypes.map(t => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </div>

        <table className="admin-table">
          <thead>
            <tr>
              <th>Source</th>
              <th>Relationship</th>
              <th>Target</th>
              <th>Provenance</th>
            </tr>
          </thead>
          <tbody>
            {edges.map(e => (
              <tr
                key={e.id}
                onClick={() => handleEdgeClick(e)}
                style={{ cursor: 'pointer' }}
                className={selectedEdge?.id === e.id ? 'admin-row-selected' : ''}
              >
                <td>{e.source_name || e.source_id}</td>
                <td style={{ whiteSpace: 'nowrap' }}>{e.relationship_type}</td>
                <td>{e.target_name || e.target_id}</td>
                <td style={{ whiteSpace: 'nowrap' }}><ProvenanceBadge value={e.provenance} /></td>
              </tr>
            ))}
            {edges.length === 0 && (
              <tr><td colSpan={4} style={{ textAlign: 'center' }}>No edges found.</td></tr>
            )}
          </tbody>
        </table>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 'var(--space-sm)', fontSize: '0.85em' }}>
          <button className="btn btn-sm" onClick={handlePrev} disabled={offset === 0}>Prev</button>
          <span>Showing {offset + 1}–{offset + edges.length}</span>
          <button className="btn btn-sm" onClick={handleNext} disabled={edges.length < LIMIT}>Next</button>
        </div>
      </div>

      <div style={{ flex: '1 1 50%' }}>
        {selectedEdge ? (
          <EdgeDetail
            edge={selectedEdge}
            sourceNode={sourceNode}
            targetNode={targetNode}
            loading={detailLoading}
            onNavigateNode={handleNavigateNode}
          />
        ) : (
          <div className="admin-empty" style={{ padding: 'var(--space-lg)', textAlign: 'center' }}>
            Select an edge to view details.
          </div>
        )}
      </div>
    </div>
  );
}
