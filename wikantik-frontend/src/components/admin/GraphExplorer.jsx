import { useState, useEffect, useCallback, useMemo } from 'react';
import { api } from '../../api/client';
import NodeDetail from './NodeDetail';
import ProvenanceBadge from './ProvenanceBadge';
import { AdminTable } from './table';

const PAGE_SIZE = 50;

export default function GraphExplorer() {
  const [schema, setSchema] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [selectedNode, setSelectedNode] = useState(null);
  const [projecting, setProjecting] = useState(false);
  const [projectResult, setProjectResult] = useState(null);
  const [page, setPage] = useState(0);

  const loadNodes = useCallback(
    async (currentPage) => {
      try {
        const data = await api.knowledge.queryNodes({
          name: search || undefined,
          node_type: typeFilter || undefined,
          status: statusFilter || undefined,
          limit: PAGE_SIZE,
          offset: currentPage * PAGE_SIZE,
        });
        setNodes(data.nodes || []);
        setTotal(typeof data.total === 'number' ? data.total : data.nodes?.length || 0);
        setError(null);
      } catch (err) {
        setError(err.message);
      }
    },
    [search, typeFilter, statusFilter],
  );

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

  // Reset to page 0 when filters change.
  useEffect(() => {
    setPage(0);
    loadNodes(0).finally(() => setLoading(false));
  }, [loadNodes]);

  useEffect(() => {
    if (page > 0) loadNodes(page);
  }, [page, loadNodes]);

  // Debounce search input.
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput.trim()), 300);
    return () => clearTimeout(t);
  }, [searchInput]);

  const handleProjectAll = async () => {
    setProjecting(true);
    setProjectResult(null);
    try {
      const result = await api.knowledge.projectAll();
      setProjectResult(result);
      await Promise.all([loadSchema(), loadNodes(page)]);
    } catch (err) {
      setProjectResult({ error: err.message });
    } finally {
      setProjecting(false);
    }
  };

  // Accepts either a node object (preferred — uses the slash-safe by-id
  // lookup) or a bare name (legacy callers like NodeDetail's navigate). Tomcat
  // rejects encoded slashes in path segments, so for names with "/" the
  // by-name lookup 400s and the detail pane shows empty.
  const handleNodeClick = async (nodeOrName) => {
    try {
      const data =
        typeof nodeOrName === 'object' && nodeOrName?.id
          ? await api.knowledge.getNodeById(nodeOrName.id)
          : await api.knowledge.getNode(nodeOrName);
      setSelectedNode(data);
    } catch (err) {
      setError(err.message);
    }
  };

  // Refresh after a single-node delete from the detail pane. The previous
  // window.location.reload() lost the tab state and kicked the user back to
  // the Proposals tab; we now refresh in place.
  const handleNodeDeleted = async () => {
    setSelectedNode(null);
    await loadNodes(page);
    await loadSchema();
  };

  // ---- AdminTable columns + bulk actions ----------------------------------

  const columns = useMemo(
    () => [
      {
        id: 'name',
        label: 'Name',
        sortable: true,
        render: (n) => (
          <button
            type="button"
            className="btn-link"
            onClick={(ev) => {
              ev.stopPropagation();
              handleNodeClick(n);
            }}
            style={{ fontWeight: 500 }}
          >
            {n.name}
          </button>
        ),
      },
      {
        id: 'node_type',
        label: 'Type',
        sortable: true,
        render: (n) => <span style={{ whiteSpace: 'nowrap' }}>{n.node_type || '—'}</span>,
      },
      {
        id: 'status',
        label: 'Status',
        render: (n) => n.properties?.status || '—',
      },
      {
        id: 'provenance',
        label: 'Provenance',
        render: (n) => <ProvenanceBadge value={n.provenance} />,
      },
      {
        id: 'is_stub',
        label: 'Stub',
        render: (n) => (n.is_stub ? 'Yes' : ''),
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
          title: 'Delete selected nodes',
          body: (rows) => (
            <div>
              <p>
                Delete {rows.length} node{rows.length !== 1 ? 's' : ''}? This cascades to
                remove every edge connecting to these nodes.
              </p>
              <p style={{ color: 'var(--text-muted)', fontSize: '0.9em' }}>
                Heads up — node deletion is <em>soft</em>: <code>article</code> nodes
                are re-created by the next graph projection, and <code>concept</code>{' '}
                nodes are re-created by the next entity-extraction run unless you also
                add a rejection / exclusion policy.
              </p>
            </div>
          ),
          confirmLabel: 'Delete',
        },
      },
    ],
    [],
  );

  const handleBulkAction = useCallback(
    async (action, selectedRows) => {
      const ids = selectedRows.map((n) => n.id).filter(Boolean);
      const succeeded = [];
      const failed = [];
      for (const id of ids) {
        try {
          await api.knowledge.deleteNode(id);
          succeeded.push(id);
        } catch (e) {
          failed.push({ id, error: e?.message || String(e) });
        }
      }
      // If the selected detail-pane node was deleted, clear it.
      if (selectedNode?.id && ids.includes(selectedNode.id) && failed.every((f) => f.id !== selectedNode.id)) {
        setSelectedNode(null);
      }
      await Promise.all([loadNodes(page), loadSchema()]);
      return { succeeded, failed, status: 'completed' };
    },
    [loadNodes, page, selectedNode],
  );

  if (loading) return <div className="admin-loading">Loading knowledge graph…</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div style={{ display: 'flex', gap: 'var(--space-lg)', minHeight: '400px' }}>
      <div style={{ flex: '1 1 50%' }}>
        {schema && (
          <div
            style={{
              marginBottom: 'var(--space-md)',
              padding: 'var(--space-sm) var(--space-md)',
              background: 'var(--bg-elevated)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius-md)',
              fontSize: '0.85em',
            }}
          >
            <div>
              <strong>Schema:</strong> {schema.stats?.nodes || 0} nodes, {schema.stats?.edges || 0} edges
              {schema.stats?.unreviewedProposals > 0 && (
                <span style={{ marginLeft: '8px', color: 'var(--accent)' }}>
                  ({schema.stats.unreviewedProposals} pending proposals)
                </span>
              )}
            </div>
            <div style={{ marginTop: '4px' }}>
              <strong>Types:</strong>{' '}
              {(schema.nodeTypes || schema.node_types || []).join(', ') || 'none'}
            </div>
            <div>
              <strong>Relationships:</strong>{' '}
              {(schema.relationshipTypes || schema.relationship_types || []).join(', ') || 'none'}
            </div>
            <div style={{ marginTop: '8px' }}>
              <button
                className="btn btn-primary btn-sm"
                onClick={handleProjectAll}
                disabled={projecting}
              >
                {projecting ? 'Projecting…' : 'Project All Pages'}
              </button>
              {projectResult && !projectResult.error && (
                <span style={{ marginLeft: '8px', fontSize: '0.85em' }}>
                  Scanned {projectResult.scanned}, projected {projectResult.projected}
                  {projectResult.errors?.length > 0 && (
                    <span style={{ color: 'var(--accent)' }}>
                      {' '}({projectResult.errors.length} errors)
                    </span>
                  )}
                </span>
              )}
              {projectResult?.error && (
                <span style={{ marginLeft: '8px', color: 'var(--error, #c0392b)', fontSize: '0.85em' }}>
                  Error: {projectResult.error}
                </span>
              )}
            </div>
          </div>
        )}

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
            Nodes{' '}
            <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}>
              · {total.toLocaleString()} total
            </span>
          </h3>
        </div>

        <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)' }}>
          <input
            type="text"
            placeholder="Search nodes…"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            className="form-input"
            style={{ flex: 1 }}
            aria-label="Search nodes"
          />
          <select
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
            className="form-input"
            style={{ width: '180px' }}
            aria-label="Type filter"
          >
            <option value="">All types</option>
            {(schema?.nodeTypes || schema?.node_types || []).map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="form-input"
            style={{ width: '180px' }}
            aria-label="Status filter"
          >
            <option value="">All statuses</option>
            {(schema?.statusValues || schema?.status_values || []).map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>

        <AdminTable
          rows={nodes}
          getRowKey={(n) => n.id}
          columns={columns}
          selectable
          bulkActions={bulkActions}
          onBulkAction={handleBulkAction}
          emptyMessage="No nodes found."
          kindLabel="node"
          pagination={{
            pageSize: PAGE_SIZE,
            totalCount: total,
            currentPage: page,
            onPageChange: setPage,
          }}
        />
      </div>

      <div style={{ flex: '1 1 50%' }}>
        {selectedNode ? (
          <NodeDetail
            node={selectedNode}
            onNavigate={handleNodeClick}
            onDeleted={handleNodeDeleted}
          />
        ) : (
          <div
            className="admin-empty"
            style={{ padding: 'var(--space-lg)', textAlign: 'center' }}
          >
            Select a node to view details.
          </div>
        )}
      </div>
    </div>
  );
}
