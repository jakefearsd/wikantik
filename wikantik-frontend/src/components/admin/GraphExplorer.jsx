import { useState, useEffect, useCallback, useMemo } from 'react';
import { api } from '../../api/client';
import { usePaginatedQuery } from '../../hooks/useApi';
import NodeDetail from './NodeDetail';
import ProvenanceBadge from './ProvenanceBadge';
import { AdminTable } from './table';

const PAGE_SIZE = 50;

export default function GraphExplorer() {
  const [schema, setSchema] = useState(null);
  const [schemaError, setSchemaError] = useState(null);
  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [selectedNode, setSelectedNode] = useState(null);

  const fetchNodes = useCallback(
    async ({ page: currentPage, pageSize, search }) => {
      const data = await api.knowledge.queryNodes({
        name: search || undefined,
        node_type: typeFilter || undefined,
        status: statusFilter || undefined,
        limit: pageSize,
        offset: currentPage * pageSize,
      });
      return {
        rows: data.nodes || [],
        total: typeof data.total === 'number' ? data.total : data.nodes?.length || 0,
      };
    },
    [typeFilter, statusFilter],
  );

  const {
    rows: nodes,
    total,
    page,
    setPage,
    search: searchInput,
    setSearch: setSearchInput,
    error,
    setError,
    firstLoad: loading,
    reload: reloadNodes,
  } = usePaginatedQuery(fetchNodes, [typeFilter, statusFilter], { pageSize: PAGE_SIZE });

  const loadSchema = useCallback(async () => {
    try {
      const schemaData = await api.knowledge.getSchema();
      setSchema(schemaData);
    } catch (err) {
      setSchemaError(err.message);
    }
  }, []);

  useEffect(() => {
    loadSchema();
  }, [loadSchema]);

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
    await reloadNodes();
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
        titleHint:
          "Editorial state of the underlying wiki page. Sourced from the page frontmatter's `status:` field (active, designed, deployed, published, archived, …).",
        render: (n) => n.properties?.status || '—',
      },
      {
        id: 'provenance',
        label: 'Provenance',
        titleHint:
          'Where this node came from on the human-authored → ai-inferred → ai-reviewed → human-curated lifecycle. Hover a badge for the full definition.',
        render: (n) => <ProvenanceBadge value={n.provenance} />,
      },
      {
        id: 'is_stub',
        label: 'No wiki page',
        titleHint:
          'The entity is referenced in the Knowledge Graph but has no backing wiki page. Common for LLM-extracted concepts whose canonical page has not been authored yet.',
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
      await Promise.all([reloadNodes(), loadSchema()]);
      return { succeeded, failed, status: 'completed' };
    },
    [reloadNodes, loadSchema, selectedNode],
  );

  if (loading) return <div className="admin-loading">Loading knowledge graph…</div>;
  if (error || schemaError) return <div className="admin-error">{error || schemaError}</div>;

  return (
    <div style={{ display: 'flex', gap: 'var(--space-lg)', minHeight: '400px' }}>
      <div style={{ flex: '1 1 50%' }}>
        {schema && (
          <div
            data-testid="kg-schema-header"
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
              <strong>Knowledge Graph</strong>
              {' · '}
              <span title="Entities — wiki pages and LLM-extracted concepts.">
                {(schema.stats?.nodes || 0).toLocaleString()} nodes (entities)
              </span>
              {' · '}
              <span title="Typed relationships between entities (uses, contains, requires, …).">
                {(schema.stats?.edges || 0).toLocaleString()} edges (relationships)
              </span>
            </div>
            {schema.stats?.pendingBreakdown?.total > 0 && (
              <div
                data-testid="kg-pending-queue"
                style={{ marginTop: '4px', color: 'var(--text-secondary)' }}
              >
                <strong style={{ color: 'var(--accent)' }}>
                  {schema.stats.pendingBreakdown.total.toLocaleString()} pending proposals
                </strong>
                {' — '}
                <span title="Candidate additions to the Knowledge Graph generated by the LLM entity extractor, awaiting human curation in the Proposals tab.">
                  {schema.stats.pendingBreakdown.newNodes.toLocaleString()} new nodes
                  {' + '}
                  {schema.stats.pendingBreakdown.newEdges.toLocaleString()} new edges
                </span>
                {' · '}
                <span title="The LLM judge pre-screens proposals. Approved = the judge thinks they're worth keeping (still needs human OK). Abstained = the judge couldn't decide and flagged for human triage. Unjudged = the judge hasn't run on them yet.">
                  {schema.stats.pendingBreakdown.judgeApproved.toLocaleString()} judge-approved
                  {' · '}
                  {schema.stats.pendingBreakdown.judgeAbstained.toLocaleString()} abstained
                  {' · '}
                  {schema.stats.pendingBreakdown.unjudged.toLocaleString()} unjudged
                </span>
              </div>
            )}
            <div style={{ marginTop: '4px' }}>
              <strong>Node types:</strong>{' '}
              {(schema.nodeTypes || schema.node_types || []).join(', ') || 'none'}
            </div>
            <div>
              <strong>Relationship types:</strong>{' '}
              {(schema.relationshipTypes || schema.relationship_types || []).join(', ') || 'none'}
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
            Select a node from the list to inspect its properties, mention chunks, and connecting edges.
          </div>
        )}
      </div>
    </div>
  );
}
