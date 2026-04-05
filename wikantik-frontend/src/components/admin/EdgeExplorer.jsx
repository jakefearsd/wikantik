import { useState, useEffect, useRef, useCallback } from 'react';
import { api } from '../../api/client';
import ProvenanceBadge from './ProvenanceBadge';

const LIMIT = 50;

export default function EdgeExplorer() {
  const [edges, setEdges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [search, setSearch] = useState('');
  const [relTypeFilter, setRelTypeFilter] = useState('');
  const [relTypes, setRelTypes] = useState([]);
  const [offset, setOffset] = useState(0);
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

  const handlePrev = () => setOffset(Math.max(0, offset - LIMIT));
  const handleNext = () => setOffset(offset + LIMIT);

  if (loading) return <div className="admin-loading">Loading edges...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div>
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
            <tr key={e.id}>
              <td>{e.source_name || e.source_id}</td>
              <td>{e.relationship_type}</td>
              <td>{e.target_name || e.target_id}</td>
              <td><ProvenanceBadge value={e.provenance} /></td>
            </tr>
          ))}
          {edges.length === 0 && (
            <tr><td colSpan={4} style={{ textAlign: 'center' }}>No edges found.</td></tr>
          )}
        </tbody>
      </table>

      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 'var(--space-sm)', fontSize: '0.85em' }}>
        <button className="btn btn-sm" onClick={handlePrev} disabled={offset === 0}>
          Previous
        </button>
        <span>Showing {offset + 1}–{offset + edges.length}</span>
        <button className="btn btn-sm" onClick={handleNext} disabled={edges.length < LIMIT}>
          Next
        </button>
      </div>
    </div>
  );
}
