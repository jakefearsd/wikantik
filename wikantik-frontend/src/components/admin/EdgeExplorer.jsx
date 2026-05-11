import { useState, useEffect, useRef, useCallback } from 'react';
import { api } from '../../api/client';
import ProvenanceBadge from './ProvenanceBadge';
import PageLink from './PageLink';
import EdgeFormModal from './EdgeFormModal';

const LIMIT = 50;

function ConfirmModal({ title, body, requireText, onConfirm, onCancel, extraField }) {
  const [typed, setTyped] = useState('');
  const [extraValue, setExtraValue] = useState('');
  const canConfirm = requireText ? typed === requireText : true;
  return (
    <div
      className="modal-overlay"
      role="dialog"
      style={{ alignItems: 'center', paddingTop: 0, zIndex: 1000 }}
    >
      <div
        className="modal-content"
        style={{ minWidth: '380px', maxWidth: '520px' }}
      >
        <h3>{title}</h3>
        <p>{body}</p>
        {requireText && (
          <div style={{ marginBottom: 'var(--space-sm)' }}>
            <span style={{ display: 'block', marginBottom: '4px' }}>
              Type the count <code>{requireText}</code> to confirm:
            </span>
            <input
              className="form-input"
              aria-label="type the count"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              style={{ width: '100%' }}
            />
          </div>
        )}
        {extraField && (
          <div style={{ marginBottom: 'var(--space-sm)' }}>
            <span style={{ display: 'block', marginBottom: '4px' }}>{extraField.label}</span>
            <input
              className="form-input"
              aria-label={extraField.label.toLowerCase()}
              value={extraValue}
              onChange={(e) => setExtraValue(e.target.value)}
              style={{ width: '100%' }}
            />
          </div>
        )}
        <div style={{ display: 'flex', gap: 'var(--space-sm)', justifyContent: 'flex-end' }}>
          <button type="button" className="btn" onClick={onCancel}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!canConfirm}
            onClick={() => onConfirm(extraValue)}
          >
            Confirm
          </button>
        </div>
      </div>
    </div>
  );
}

function EdgeHistory({ edgeId }) {
  const [rows, setRows] = useState(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState(null);

  const onToggle = async () => {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    if (rows == null) {
      try {
        const data = await api.knowledge.getEdgeAudit(edgeId);
        setRows(data.audit || []);
      } catch (e) {
        setError(e.message || 'Failed to load history');
      }
    }
  };

  return (
    <div style={{ marginTop: 'var(--space-md)' }}>
      <button type="button" className="btn-link" onClick={onToggle}>
        {open ? '▾' : '▸'} History
      </button>
      {open && (
        <div style={{ marginTop: 'var(--space-sm)' }}>
          {error && <div className="admin-error">{error}</div>}
          {rows && rows.length === 0 && (
            <div style={{ color: 'var(--text-muted)' }}>No audit entries.</div>
          )}
          {rows && rows.length > 0 && (
            <table className="admin-table" style={{ fontSize: '0.85em' }}>
              <thead>
                <tr>
                  <th>When</th>
                  <th>Action</th>
                  <th>Actor</th>
                  <th>Reason</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id || `${r.created}-${r.action}`}>
                    <td style={{ whiteSpace: 'nowrap' }}>{r.created}</td>
                    <td>{r.action}</td>
                    <td>{r.actor}</td>
                    <td>{r.reason || ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}

function EdgeDetail({
  edge,
  sourceNode,
  targetNode,
  loading,
  onNavigateNode,
  onEdit,
  onDelete,
  onDeleteAndReject,
}) {
  if (!edge) return null;
  const renderNodeCard = (label, node) => {
    if (!node)
      return (
        <div style={{ fontStyle: 'italic', color: 'var(--text-muted)' }}>Not found</div>
      );
    return (
      <div
        style={{
          padding: 'var(--space-sm)',
          background: 'var(--bg-elevated)',
          borderRadius: 'var(--radius-md)',
          marginBottom: 'var(--space-sm)',
        }}
      >
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: '4px',
          }}
        >
          <strong style={{ fontSize: '0.95em' }}>
            <button
              className="btn-link"
              onClick={() => onNavigateNode(node.name)}
              style={{ fontWeight: 600 }}
            >
              {node.name}
            </button>
          </strong>
          <span style={{ fontSize: '0.8em', color: 'var(--text-muted)' }}>{label}</span>
        </div>
        <div style={{ fontSize: '0.85em' }}>
          <span>
            <strong>Type:</strong> {node.node_type || '-'}
          </span>
          {' · '}
          <strong>Provenance:</strong> <ProvenanceBadge value={node.provenance} />
        </div>
        {node.source_page && (
          <div style={{ fontSize: '0.85em' }}>
            <strong>Source page:</strong> <PageLink name={node.source_page} />
          </div>
        )}
      </div>
    );
  };

  return (
    <div
      style={{
        padding: 'var(--space-md)',
        background: 'var(--bg-elevated)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
      }}
    >
      <div style={{ textAlign: 'center', marginBottom: 'var(--space-md)' }}>
        <div
          style={{
            fontSize: '0.8em',
            color: 'var(--text-muted)',
            textTransform: 'uppercase',
            letterSpacing: '0.5px',
            marginBottom: '4px',
          }}
        >
          Relationship
        </div>
        <div style={{ fontSize: '1.1em', fontWeight: 600 }}>
          {edge.source_name || edge.source_id}
          <span style={{ margin: '0 8px', color: 'var(--text-muted)' }}>→</span>
          <span style={{ whiteSpace: 'nowrap' }}>{edge.relationship_type}</span>
          <span style={{ margin: '0 8px', color: 'var(--text-muted)' }}>→</span>
          {edge.target_name || edge.target_id}
        </div>
        <div style={{ marginTop: '4px' }}>
          <ProvenanceBadge value={edge.provenance} />
        </div>
      </div>

      {!loading && (
        <div
          style={{
            display: 'flex',
            gap: 'var(--space-sm)',
            marginBottom: 'var(--space-md)',
            justifyContent: 'center',
          }}
        >
          <button type="button" className="btn btn-sm" onClick={onEdit}>
            Edit
          </button>
          <button type="button" className="btn btn-sm" onClick={onDelete}>
            Delete
          </button>
          <button type="button" className="btn btn-sm btn-danger" onClick={onDeleteAndReject}>
            Delete + Prevent
          </button>
        </div>
      )}

      {loading ? (
        <div className="admin-loading">Loading node details...</div>
      ) : (
        <>
          {renderNodeCard('Source', sourceNode)}
          {renderNodeCard('Target', targetNode)}
        </>
      )}

      {edge.id && <EdgeHistory edgeId={edge.id} />}
    </div>
  );
}

export default function EdgeExplorer() {
  const [edges, setEdges] = useState([]);
  const [total, setTotal] = useState(0);
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
  const [formMode, setFormMode] = useState(null); // null | 'create' | 'edit'
  const [confirmMode, setConfirmMode] = useState(null); // null | 'plain' | 'reject' | 'bulk'
  const debounceRef = useRef(null);

  const loadEdges = useCallback(
    async (currentOffset) => {
      try {
        const data = await api.knowledge.queryEdges({
          relationship_type: relTypeFilter || undefined,
          search: search || undefined,
          limit: LIMIT,
          offset: currentOffset,
        });
        setEdges(data.edges || []);
        setTotal(typeof data.total === 'number' ? data.total : data.edges?.length || 0);
        setError(null);
      } catch (err) {
        setError(err.message);
      }
    },
    [relTypeFilter, search],
  );

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
    if (offset > 0) loadEdges(offset);
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
    setDetailLoading(true);
    try {
      const node = await api.knowledge.getNode(name);
      if (node) {
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

  const refreshAndClose = async () => {
    setFormMode(null);
    setConfirmMode(null);
    await loadEdges(offset);
  };

  const onConfirmPlainDelete = async () => {
    if (!selectedEdge?.id) return;
    try {
      await api.knowledge.deleteEdge(selectedEdge.id);
      setSelectedEdge(null);
      await refreshAndClose();
    } catch (e) {
      setError(e.message);
      setConfirmMode(null);
    }
  };

  const onConfirmDeleteAndReject = async (reason) => {
    if (!selectedEdge?.id) return;
    try {
      await api.knowledge.deleteAndRejectEdge(selectedEdge.id, reason);
      setSelectedEdge(null);
      await refreshAndClose();
    } catch (e) {
      setError(e.message);
      setConfirmMode(null);
    }
  };

  const onConfirmBulkDelete = async () => {
    try {
      await api.knowledge.bulkDeleteEdges({
        relationship_type: relTypeFilter || undefined,
        search: search || undefined,
        expected_count: total,
      });
      setSelectedEdge(null);
      await refreshAndClose();
    } catch (e) {
      setError(e.message);
      setConfirmMode(null);
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
            onChange={(e) => setRelTypeFilter(e.target.value)}
            className="form-input"
            style={{ width: '200px' }}
          >
            <option value="">All relationship types</option>
            {relTypes.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          <button type="button" className="btn btn-primary" onClick={() => setFormMode('create')}>
            New edge
          </button>
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
            {edges.map((e) => (
              <tr
                key={e.id}
                onClick={() => handleEdgeClick(e)}
                style={{ cursor: 'pointer' }}
                className={selectedEdge?.id === e.id ? 'admin-row-selected' : ''}
              >
                <td>{e.source_name || e.source_id}</td>
                <td style={{ whiteSpace: 'nowrap' }}>{e.relationship_type}</td>
                <td>{e.target_name || e.target_id}</td>
                <td>
                  <ProvenanceBadge value={e.provenance} />
                </td>
              </tr>
            ))}
            {edges.length === 0 && (
              <tr>
                <td colSpan={4} style={{ textAlign: 'center' }}>
                  No edges found.
                </td>
              </tr>
            )}
          </tbody>
        </table>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginTop: 'var(--space-sm)',
            fontSize: '0.85em',
          }}
        >
          <button className="btn btn-sm" onClick={handlePrev} disabled={offset === 0}>
            Prev
          </button>
          <span>
            Showing {offset + 1}–{offset + edges.length} of {total}
          </span>
          <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
            <button
              className="btn btn-sm btn-danger"
              disabled={total === 0}
              onClick={() => setConfirmMode('bulk')}
            >
              Delete filtered ({total})
            </button>
            <button className="btn btn-sm" onClick={handleNext} disabled={edges.length < LIMIT}>
              Next
            </button>
          </div>
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
            onEdit={() => setFormMode('edit')}
            onDelete={() => setConfirmMode('plain')}
            onDeleteAndReject={() => setConfirmMode('reject')}
          />
        ) : (
          <div
            className="admin-empty"
            style={{ padding: 'var(--space-lg)', textAlign: 'center' }}
          >
            Select an edge to view details.
          </div>
        )}
      </div>

      {formMode && (
        <EdgeFormModal
          mode={formMode}
          relTypes={relTypes}
          initialEdge={formMode === 'edit' ? selectedEdge : null}
          initialSource={formMode === 'edit' ? sourceNode : null}
          initialTarget={formMode === 'edit' ? targetNode : null}
          onClose={() => setFormMode(null)}
          onSaved={refreshAndClose}
        />
      )}

      {confirmMode === 'plain' && (
        <ConfirmModal
          title="Delete this edge?"
          body="Re-extraction may re-propose it. Use Delete + Prevent if you want to keep it out."
          onConfirm={onConfirmPlainDelete}
          onCancel={() => setConfirmMode(null)}
        />
      )}
      {confirmMode === 'reject' && (
        <ConfirmModal
          title="Delete and prevent re-proposal"
          body="This will delete the edge AND insert a rejection so the next extraction run cannot re-add it."
          extraField={{ label: 'Reason' }}
          onConfirm={onConfirmDeleteAndReject}
          onCancel={() => setConfirmMode(null)}
        />
      )}
      {confirmMode === 'bulk' && (
        <ConfirmModal
          title={`Delete ${total} filtered edges?`}
          body="This deletes every edge matching the current filter, including pages beyond the current view."
          requireText={String(total)}
          onConfirm={onConfirmBulkDelete}
          onCancel={() => setConfirmMode(null)}
        />
      )}
    </div>
  );
}
