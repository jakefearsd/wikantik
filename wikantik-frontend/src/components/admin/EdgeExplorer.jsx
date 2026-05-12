import { useState, useEffect, useCallback, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
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

// Rehype plugin: walks the rendered HAST and wraps every case-insensitive
// occurrence of `needle` in a <mark> element. Skips text inside <code>, <pre>,
// and <a> so we don't mangle code identifiers or URLs. Returns an identity
// transformer when needle is empty so the markdown still renders cleanly.
function makeHighlightRehype(needle) {
  return () => (tree) => {
    if (!needle || !needle.trim()) return;
    const target = needle.trim().toLowerCase();
    const tlen = target.length;
    const visit = (node, skipMarking) => {
      if (!node) return;
      if (node.type === 'element') {
        const skip = skipMarking
          || node.tagName === 'code'
          || node.tagName === 'pre'
          || node.tagName === 'a'
          || node.tagName === 'mark';
        if (node.children) {
          const replaced = [];
          for (const child of node.children) {
            if (!skip && child.type === 'text' && child.value) {
              const text = child.value;
              const lower = text.toLowerCase();
              let cursor = 0;
              let idx = lower.indexOf(target, cursor);
              if (idx === -1) {
                replaced.push(child);
                continue;
              }
              while (idx !== -1) {
                if (idx > cursor) {
                  replaced.push({ type: 'text', value: text.slice(cursor, idx) });
                }
                replaced.push({
                  type: 'element',
                  tagName: 'mark',
                  properties: {},
                  children: [{ type: 'text', value: text.slice(idx, idx + tlen) }],
                });
                cursor = idx + tlen;
                idx = lower.indexOf(target, cursor);
              }
              if (cursor < text.length) {
                replaced.push({ type: 'text', value: text.slice(cursor) });
              }
            } else {
              visit(child, skip);
              replaced.push(child);
            }
          }
          node.children = replaced;
        }
        return;
      }
      if (node.type === 'root' && node.children) {
        for (const child of node.children) visit(child, skipMarking);
      }
    };
    visit(tree, false);
  };
}

// Block-friendly ReactMarkdown overrides for chunk bodies. We have full
// right-pane width, so paragraphs, lists, and code blocks render at normal
// scale — but we still tighten margins and downgrade headings (chunks are
// passages plucked from the middle of pages; their original <h1>/<h2>
// outranks our admin layout's heading hierarchy).
const CHUNK_COMPONENTS = {
  p:    ({ children }) => <p style={{ margin: '0 0 8px 0' }}>{children}</p>,
  h1:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  h2:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  h3:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  h4:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  h5:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  h6:   ({ children }) => <strong style={{ display: 'block', margin: '4px 0' }}>{children}</strong>,
  ul:   ({ children }) => <ul style={{ margin: '4px 0 8px 0', paddingLeft: '1.4em' }}>{children}</ul>,
  ol:   ({ children }) => <ol style={{ margin: '4px 0 8px 0', paddingLeft: '1.4em' }}>{children}</ol>,
  li:   ({ children }) => <li style={{ margin: 0 }}>{children}</li>,
  blockquote: ({ children }) => (
    <blockquote
      style={{
        margin: '4px 0',
        padding: '2px 10px',
        borderLeft: '3px solid var(--border)',
        color: 'var(--text-secondary)',
      }}
    >
      {children}
    </blockquote>
  ),
  code: ({ inline, children }) =>
    inline ? (
      <code
        style={{
          background: 'var(--code-bg)',
          padding: '0 4px',
          borderRadius: '3px',
          fontFamily: 'var(--font-mono)',
          fontSize: '0.85em',
        }}
      >
        {children}
      </code>
    ) : (
      <code style={{ fontFamily: 'var(--font-mono)', fontSize: '0.85em' }}>{children}</code>
    ),
  pre:  ({ children }) => (
    <pre
      style={{
        margin: '6px 0',
        padding: '6px 10px',
        background: 'var(--code-bg)',
        borderRadius: 'var(--radius-sm)',
        fontSize: '0.82rem',
        overflowX: 'auto',
        whiteSpace: 'pre-wrap',
      }}
    >
      {children}
    </pre>
  ),
  a:    ({ href, children }) =>
    href ? (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    ) : (
      <span>{children}</span>
    ),
  mark: ({ children }) => (
    <mark
      style={{
        background: 'var(--accent-soft, rgba(255, 200, 0, 0.35))',
        color: 'inherit',
        padding: '0 2px',
        borderRadius: '2px',
      }}
    >
      {children}
    </mark>
  ),
};

function MentionsPanel({ label, node }) {
  const [mentions, setMentions] = useState(null); // null = unloaded; [] = none
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!node?.id) {
      setMentions(null);
      return;
    }
    let cancelled = false;
    setMentions(null);
    setError(null);
    api.knowledge
      .getNodeMentions(node.id, 3)
      .then((data) => {
        if (!cancelled) setMentions(data.mentions || []);
      })
      .catch((e) => {
        if (!cancelled) setError(e?.message || 'Failed to load mentions');
      });
    return () => {
      cancelled = true;
    };
  }, [node?.id]);

  if (!node?.id) return null;

  return (
    <div style={{ marginTop: 'var(--space-md)' }}>
      <h4
        style={{
          fontSize: '0.85em',
          marginBottom: 'var(--space-xs, 4px)',
          textTransform: 'uppercase',
          letterSpacing: '0.5px',
          color: 'var(--text-muted)',
        }}
      >
        {label} mentions{mentions && mentions.length > 0 ? ` (${mentions.length})` : ''}
      </h4>
      {error && <div className="admin-error">{error}</div>}
      {mentions == null && !error && (
        <div style={{ color: 'var(--text-muted)', fontSize: '0.85em' }}>Loading mentions…</div>
      )}
      {mentions && mentions.length === 0 && (
        <div style={{ color: 'var(--text-muted)', fontSize: '0.85em', fontStyle: 'italic' }}>
          No mention chunks recorded for this node.
        </div>
      )}
      {mentions &&
        mentions.length > 0 &&
        mentions.map((m) => {
          const isFallback = m.extractor === 'edge-proposal-fallback';
          return (
          <div
            key={m.chunk_id}
            data-testid={isFallback ? 'mention-fallback' : 'mention-attributed'}
            style={{
              padding: 'var(--space-sm)',
              background: 'var(--bg-base, var(--bg-elevated))',
              border: `1px ${isFallback ? 'dashed' : 'solid'} var(--border)`,
              borderRadius: 'var(--radius-sm)',
              marginBottom: 'var(--space-sm)',
              fontSize: '0.88em',
              lineHeight: 1.5,
            }}
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                gap: 'var(--space-sm)',
                marginBottom: '4px',
                fontSize: '0.85em',
                flexWrap: 'wrap',
              }}
            >
              <span>
                <PageLink name={m.page_name} />
                {m.heading_path && m.heading_path.length > 0 && (
                  <span style={{ color: 'var(--text-muted)' }}>
                    {' · '}
                    {m.heading_path.join(' › ')}
                  </span>
                )}
                {isFallback && (
                  <span
                    title={
                      'This node has no per-chunk attribution. Showing chunks on the originating '
                      + "proposal's source page that contain the entity name."
                    }
                    style={{
                      marginLeft: 'var(--space-sm)',
                      padding: '0 6px',
                      borderRadius: '3px',
                      border: '1px dashed var(--border)',
                      color: 'var(--text-muted)',
                      fontSize: '0.78em',
                      textTransform: 'uppercase',
                      letterSpacing: '0.4px',
                    }}
                  >
                    Inferred context
                  </span>
                )}
              </span>
              <span style={{ color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                conf {m.confidence != null ? m.confidence.toFixed(2) : '—'}
              </span>
            </div>
            <div className="mention-chunk-body">
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                rehypePlugins={[makeHighlightRehype(node.name)]}
                components={CHUNK_COMPONENTS}
              >
                {m.text}
              </ReactMarkdown>
            </div>
          </div>
        );
        })}
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
          <MentionsPanel label="Source" node={sourceNode} />
          <MentionsPanel label="Target" node={targetNode} />
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
