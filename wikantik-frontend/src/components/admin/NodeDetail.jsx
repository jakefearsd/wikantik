import { useState, useEffect } from 'react';
import { api } from '../../api/client';
import ProvenanceBadge from './ProvenanceBadge';
import PageLink from './PageLink';
import { MentionsPanel } from './MentionChunks';
import ConfirmDialog from '../ui/ConfirmDialog';

export default function NodeDetail({ node, onNavigate, onDeleted }) {
  const [similar, setSimilar] = useState([]);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState(null);

  useEffect(() => {
    if (!node?.name) { setSimilar([]); return; }
    api.knowledge.getSimilarNodes(node.name, 5)
      .then(data => setSimilar(data.similar || []))
      .catch(() => setSimilar([]));
  }, [node?.name]);

  // Reset delete state when the selected node changes so a stale error/dialog
  // from a previously inspected node can't bleed into this one.
  useEffect(() => {
    setConfirmingDelete(false);
    setDeleting(false);
    setDeleteError(null);
  }, [node?.id]);

  if (!node) return null;

  const outbound = (node.edges || []).filter(e => e.source_id === node.id);
  const inbound = (node.edges || []).filter(e => e.target_id === node.id);
  const totalEdges = outbound.length + inbound.length;

  const impact = totalEdges > 0
    ? ` This will also remove ${totalEdges} edge${totalEdges === 1 ? '' : 's'}` +
      ` (${outbound.length} outbound, ${inbound.length} inbound).`
    : ' It has no edges to remove.';

  const handleDelete = async () => {
    setConfirmingDelete(false);
    setDeleting(true);
    setDeleteError(null);
    try {
      await api.knowledge.deleteNode(node.id);
      // Refresh the parent list in place instead of a full window reload, which
      // would lose the admin tab state and bounce the user back to the default
      // (Proposals) tab.
      if (onDeleted) onDeleted();
    } catch (err) {
      setDeleteError(err.message || 'Failed to delete node');
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div style={{
      padding: 'var(--space-md)',
      background: 'var(--bg-elevated)',
      border: '1px solid var(--border)',
      borderRadius: 'var(--radius-md)',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-sm)', gap: 'var(--space-sm)' }}>
        <h3 style={{ margin: 0 }}>{node.name}</h3>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '2px' }}>
          <button
            className="btn btn-sm btn-danger"
            onClick={() => setConfirmingDelete(true)}
            disabled={deleting}
          >
            {deleting ? 'Deleting…' : 'Delete'}
          </button>
          {deleteError && (
            <span
              role="alert"
              style={{ fontSize: '0.75em', color: 'var(--danger, #c0392b)' }}
            >
              {deleteError}
            </span>
          )}
          {totalEdges > 0 && (
            <span
              data-testid="node-delete-impact"
              style={{ fontSize: '0.75em', color: 'var(--text-muted)' }}
              title="Deleting this node cascades to every edge that touches it. Inbound edges (other nodes pointing at this one) are removed as well."
            >
              Removes {totalEdges} edge{totalEdges === 1 ? '' : 's'} ({outbound.length} out / {inbound.length} in)
            </span>
          )}
        </div>
      </div>

      <div style={{ fontSize: '0.9em', marginBottom: 'var(--space-md)' }}>
        <div><strong>Type:</strong> {node.node_type || '-'}</div>
        <div><strong>Provenance:</strong> <ProvenanceBadge value={node.provenance} /></div>
        {node.source_page && (
          <div><strong>Source page:</strong>{' '}
            <PageLink name={node.source_page} />
          </div>
        )}
        {node.is_stub && <div style={{ color: 'var(--accent)', fontStyle: 'italic' }}>Stub node (no wiki page yet)</div>}
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

      <MentionsPanel label="Context" node={node} />

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
                    <button
                      className="btn-link"
                      onClick={() =>
                        onNavigate && onNavigate({ id: e.target_id, name: e.target_name })
                      }
                    >
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
                    <button
                      className="btn-link"
                      onClick={() =>
                        onNavigate && onNavigate({ id: e.source_id, name: e.source_name })
                      }
                    >
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

      {similar.length > 0 && (
        <div style={{ marginTop: 'var(--space-md)' }}>
          <h4 style={{ fontSize: '0.9em', marginBottom: '2px' }}>Similar Nodes ({similar.length})</h4>
          <div style={{ fontSize: '0.75em', color: 'var(--text-secondary)', marginBottom: '4px' }}>
            Cosine similarity over the shared mention-centroid embedding space
          </div>
          <table className="admin-table" style={{ fontSize: '0.85em' }}>
            <thead>
              <tr><th>Name</th><th>Similarity</th></tr>
            </thead>
            <tbody>
              {similar.map(s => (
                <tr key={s.id || s.name}>
                  <td>
                    <button
                      className="btn-link"
                      onClick={() => onNavigate && onNavigate(s.id ? { id: s.id, name: s.name } : s.name)}
                    >
                      {s.name}
                    </button>
                  </td>
                  <td>{(s.similarity * 100).toFixed(1)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {confirmingDelete && (
        <ConfirmDialog
          title="Delete Node"
          message={`Delete node "${node.name}"?${impact}`}
          confirmLabel="Delete"
          onConfirm={handleDelete}
          onCancel={() => setConfirmingDelete(false)}
        />
      )}
    </div>
  );
}
