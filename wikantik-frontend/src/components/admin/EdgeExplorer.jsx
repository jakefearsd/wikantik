import { useState, useEffect, useCallback, useMemo } from 'react';
import { api } from '../../api/client';
import ProvenanceBadge from './ProvenanceBadge';
import PageLink from './PageLink';
import EdgeFormModal from './EdgeFormModal';
import { AdminTable } from './table';

const PAGE_SIZE = 50;

function ConfirmModal({ title, body, onConfirm, onCancel, extraField }) {
  const [extraValue, setExtraValue] = useState('');
  return (
    <div
      className="modal-overlay"
      role="dialog"
      style={{ alignItems: 'center', paddingTop: 0, zIndex: 1000 }}
    >
      <div
        className="modal-content admin-modal"
        style={{ maxWidth: '520px' }}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-sm)' }}>
          {title}
        </h2>
        <p style={{ color: 'var(--text-secondary)', marginBottom: 'var(--space-md)' }}>{body}</p>
        {extraField && (
          <div className="form-field">
            <label>{extraField.label}</label>
            <input
              type="text"
              aria-label={extraField.label.toLowerCase()}
              value={extraValue}
              onChange={(e) => setExtraValue(e.target.value)}
            />
          </div>
        )}
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onCancel}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-primary btn-danger"
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
          border: '1px solid var(--border)',
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
              onClick={() => onNavigateNode(node)}
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
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [relTypeFilter, setRelTypeFilter] = useState('');
  const [endpointKindFilter, setEndpointKindFilter] = useState(''); // '' | 'page' | 'entity'
  const [relTypes, setRelTypes] = useState([]);
  const [page, setPage] = useState(0);
  const [selectedEdge, setSelectedEdge] = useState(null);
  const [sourceNode, setSourceNode] = useState(null);
  const [targetNode, setTargetNode] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [formMode, setFormMode] = useState(null); // null | 'create' | 'edit'
  const [confirmMode, setConfirmMode] = useState(null); // null | 'plain' | 'reject'

  const loadEdges = useCallback(
    async (currentPage) => {
      try {
        const data = await api.knowledge.queryEdges({
          relationship_type: relTypeFilter || undefined,
          search: search || undefined,
          endpoint_kind: endpointKindFilter || undefined,
          limit: PAGE_SIZE,
          offset: currentPage * PAGE_SIZE,
        });
        setEdges(data.edges || []);
        setTotal(typeof data.total === 'number' ? data.total : data.edges?.length || 0);
        setError(null);
      } catch (err) {
        setError(err.message);
      }
    },
    [relTypeFilter, search, endpointKindFilter],
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

  // Reset to page 0 whenever a filter changes, then reload.
  useEffect(() => {
    setPage(0);
    loadEdges(0).finally(() => setLoading(false));
  }, [loadEdges]);

  // Reload when paginating.
  useEffect(() => {
    if (page > 0) loadEdges(page);
  }, [page, loadEdges]);

  // Debounce the search input → search state.
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput.trim()), 300);
    return () => clearTimeout(t);
  }, [searchInput]);

  const handleEdgeClick = async (edge) => {
    setSelectedEdge(edge);
    setDetailLoading(true);
    setSourceNode(null);
    setTargetNode(null);
    // Look up the endpoint nodes by ID, not by name. Tomcat rejects encoded
    // slashes in path segments by default, so getNode(name) 400s for any node
    // whose name contains "/" — the edge list itself carries source_id /
    // target_id, so this path is robust to special characters in names.
    try {
      const [src, tgt] = await Promise.all([
        edge.source_id ? api.knowledge.getNodeById(edge.source_id).catch(() => null) : null,
        edge.target_id ? api.knowledge.getNodeById(edge.target_id).catch(() => null) : null,
      ]);
      setSourceNode(src);
      setTargetNode(tgt);
    } finally {
      setDetailLoading(false);
    }
  };

  const handleNavigateNode = async (node) => {
    if (!node?.id) return;
    setDetailLoading(true);
    try {
      const full = await api.knowledge.getNodeById(node.id).catch(() => null);
      const resolved = full || node;
      setSourceNode(resolved);
      setTargetNode(null);
      setSelectedEdge({
        source_name: resolved.name,
        target_name: '',
        relationship_type: '(viewing node)',
        provenance: resolved.provenance,
      });
    } finally {
      setDetailLoading(false);
    }
  };

  const refreshAndClose = async () => {
    setFormMode(null);
    setConfirmMode(null);
    await loadEdges(page);
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

  // ---- AdminTable wiring ---------------------------------------------------

  const columns = useMemo(
    () => [
      {
        id: 'source_name',
        label: 'Source',
        render: (e) => (
          <button
            type="button"
            className="btn-link"
            onClick={(ev) => {
              ev.stopPropagation();
              handleEdgeClick(e);
            }}
            style={{ fontWeight: 500 }}
          >
            {e.source_name || e.source_id}
          </button>
        ),
      },
      {
        id: 'relationship_type',
        label: 'Relationship',
        render: (e) => <span style={{ whiteSpace: 'nowrap' }}>{e.relationship_type}</span>,
      },
      {
        id: 'target_name',
        label: 'Target',
        render: (e) => (
          <button
            type="button"
            className="btn-link"
            onClick={(ev) => {
              ev.stopPropagation();
              handleEdgeClick(e);
            }}
            style={{ fontWeight: 500 }}
          >
            {e.target_name || e.target_id}
          </button>
        ),
      },
      {
        id: 'provenance',
        label: 'Provenance',
        render: (e) => <ProvenanceBadge value={e.provenance} />,
      },
    ],
    [],
  );

  const bulkActions = useMemo(
    () => [
      {
        id: 'delete',
        label: 'Delete',
        variant: 'danger',
        confirm: {
          title: 'Delete selected edges',
          body: (rows) => (
            <p>
              Delete {rows.length} edge{rows.length !== 1 ? 's' : ''}? Re-extraction may
              re-propose them. Use <strong>Delete + Prevent</strong> if you want to keep them
              out for good.
            </p>
          ),
          confirmLabel: 'Delete',
        },
      },
      {
        id: 'reject',
        label: 'Delete + Prevent',
        variant: 'danger',
        confirm: {
          title: 'Delete selected and prevent re-proposal',
          body: (rows) => (
            <p>
              Delete {rows.length} edge{rows.length !== 1 ? 's' : ''} AND insert a rejection
              row for each so the next extraction run cannot re-add them.
            </p>
          ),
          confirmLabel: 'Delete + Prevent',
        },
        reason: { label: 'Reason', placeholder: 'Why are these wrong?', required: true },
      },
    ],
    [],
  );

  const handleBulkAction = useCallback(
    async (action, selectedRows, reason) => {
      const ids = selectedRows.map((e) => e.id).filter(Boolean);
      const succeeded = [];
      const failed = [];

      for (const id of ids) {
        try {
          if (action.id === 'delete') {
            await api.knowledge.deleteEdge(id);
          } else if (action.id === 'reject') {
            await api.knowledge.deleteAndRejectEdge(id, reason);
          }
          succeeded.push(id);
        } catch (e) {
          failed.push({ id, error: e?.message || String(e) });
        }
      }

      // Clear the detail pane if its edge was part of the deleted set.
      if (selectedEdge?.id && ids.includes(selectedEdge.id) && failed.every((f) => f.id !== selectedEdge.id)) {
        setSelectedEdge(null);
      }
      await loadEdges(page);

      return { succeeded, failed, status: 'completed' };
    },
    [loadEdges, page, selectedEdge],
  );

  if (loading) return <div className="admin-loading">Loading edges...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div style={{ display: 'flex', gap: 'var(--space-lg)', minHeight: '400px' }}>
      <div style={{ flex: '1 1 50%' }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginBottom: 'var(--space-sm)',
            gap: 'var(--space-md)',
          }}
        >
          <h3 style={{ margin: 0, fontFamily: 'var(--font-display)', fontSize: '1.1rem' }}>
            Edges <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}>
              · {total.toLocaleString()} total
            </span>
          </h3>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setFormMode('create')}>
            New edge
          </button>
        </div>

        <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)' }}>
          <input
            type="text"
            placeholder="Search by node name…"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            className="form-input"
            style={{ flex: 1 }}
            aria-label="Search by node name"
          />
          <select
            value={endpointKindFilter}
            onChange={(e) => setEndpointKindFilter(e.target.value)}
            className="form-input"
            style={{ width: '160px' }}
            aria-label="Endpoint kind filter"
          >
            <option value="">Pages + Entities</option>
            <option value="page">Pages only</option>
            <option value="entity">Entities only</option>
          </select>
          <select
            value={relTypeFilter}
            onChange={(e) => setRelTypeFilter(e.target.value)}
            className="form-input"
            style={{ width: '200px' }}
            aria-label="Relationship type filter"
          >
            <option value="">All relationship types</option>
            {relTypes.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </div>

        <AdminTable
          rows={edges}
          getRowKey={(e) => e.id}
          columns={columns}
          selectable
          bulkActions={bulkActions}
          onBulkAction={handleBulkAction}
          emptyMessage="No edges found."
          kindLabel="edge"
          pagination={{
            pageSize: PAGE_SIZE,
            totalCount: total,
            currentPage: page,
            onPageChange: setPage,
          }}
        />
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
    </div>
  );
}
