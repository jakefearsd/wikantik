import { useState, useEffect, useMemo, useCallback } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import '../../styles/admin.css';

const FILTER_OPTIONS = [
  { value: 'all',     label: 'All' },
  { value: 'include', label: 'Include' },
  { value: 'exclude', label: 'Exclude' },
  { value: 'unset',   label: 'Unset' },
];

export default function AdminKgPolicyPage() {
  const [clusters, setClusters] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [filter, setFilter] = useState('all');
  const [search, setSearch] = useState('');
  const [editTarget, setEditTarget] = useState(null);   // { cluster, currentAction }
  const [estimate, setEstimate] = useState(null);        // dry-run preview
  const [reconciliation, setReconciliation] = useState([]);

  const reload = useCallback(async () => {
    try {
      const data = await api.admin.kgPolicy.listClusters();
      setClusters(data.clusters || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const reloadReconciliation = useCallback(async () => {
    try {
      const data = await api.admin.kgPolicy.reconciliation();
      setReconciliation(data.reconciliation || []);
    } catch (_) {
      // reconciliation refresh is best-effort; swallow silently
    }
  }, []);

  useEffect(() => {
    reload();
    reloadReconciliation();
  }, [reload, reloadReconciliation]);

  // Poll reconciliation while any job is RUNNING or QUEUED
  useEffect(() => {
    const active = reconciliation.some(
      (r) => r.state === 'RUNNING' || r.state === 'QUEUED',
    );
    if (!active) return;
    const id = setInterval(reloadReconciliation, 5000);
    return () => clearInterval(id);
  }, [reconciliation, reloadReconciliation]);

  const visible = useMemo(() => {
    return clusters.filter((c) => {
      if (filter === 'unset' && c.action != null) return false;
      if (filter !== 'all' && filter !== 'unset' && c.action !== filter) return false;
      if (search && !c.cluster.toLowerCase().includes(search.toLowerCase())) return false;
      return true;
    });
  }, [clusters, filter, search]);

  const onApplyEdit = async (action, reason) => {
    try {
      const est = await api.admin.kgPolicy.estimate(editTarget.cluster, action);
      setEstimate({ ...est, pendingAction: action, pendingReason: reason });
    } catch (err) {
      setError(err.message);
    }
  };

  const onConfirmEstimate = async () => {
    try {
      await api.admin.kgPolicy.setCluster(editTarget.cluster, {
        action: estimate.pendingAction,
        reason: estimate.pendingReason || '',
      });
      setEditTarget(null);
      setEstimate(null);
      await reload();
      await reloadReconciliation();
    } catch (err) {
      setError(err.message);
    }
  };

  const onClear = async (cluster) => {
    if (!window.confirm(`Clear policy for "${cluster}"? It will revert to default-exclude.`)) return;
    try {
      await api.admin.kgPolicy.clearCluster(cluster);
      await reload();
    } catch (err) {
      setError(err.message);
    }
  };

  // Bootstrap call-to-action: all clusters are present but none have a policy
  const allUnset = clusters.length > 0 && clusters.every((c) => c.action == null);

  return (
    <AdminPage loading={loading} error={error} loadingLabel="Loading KG policy…">
      {allUnset && (
        <div className="admin-callout">
          <strong>No clusters configured yet.</strong>
          {' '}The KG defaults to <em>exclude all</em>. Run the bootstrap wizard to seed
          tech / finance clusters as include and lifestyle clusters as exclude.
          {' '}<a href="/admin/kg-policy/bootstrap">Open wizard</a>
          <div>Bootstrap wizard — Task 25</div>
        </div>
      )}

      {reconciliation.some((r) => r.state === 'RUNNING' || r.state === 'QUEUED') && (
        <ReconciliationPanel
          statuses={reconciliation.filter(
            (r) => r.state === 'RUNNING' || r.state === 'QUEUED',
          )}
        />
      )}

      <div className="admin-toolbar">
        <select value={filter} onChange={(e) => setFilter(e.target.value)}>
          {FILTER_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
        <input
          type="search"
          className="admin-search"
          placeholder="Search cluster name…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      <div className="admin-table-wrapper">
        <table className="admin-table">
          <thead>
            <tr>
              <th>Cluster</th>
              <th>Pages</th>
              <th>Action</th>
              <th>Reason</th>
              <th>Set by</th>
              <th>Last reviewed</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {visible.length === 0 ? (
              <tr>
                <td colSpan="7" className="admin-empty">No clusters match the current filter</td>
              </tr>
            ) : visible.map((c) => (
              <ClusterRow
                key={c.cluster}
                row={c}
                onEdit={() => setEditTarget({ cluster: c.cluster, currentAction: c.action })}
                onClear={() => onClear(c.cluster)}
              />
            ))}
          </tbody>
        </table>
      </div>

      {editTarget && !estimate && (
        <EditModal
          target={editTarget}
          onCancel={() => setEditTarget(null)}
          onApply={onApplyEdit}
        />
      )}

      {estimate && (
        <EstimateConfirmModal
          target={editTarget}
          estimate={estimate}
          onCancel={() => setEstimate(null)}
          onConfirm={onConfirmEstimate}
        />
      )}
    </AdminPage>
  );
}

// ---- subcomponents ----

function ClusterRow({ row, onEdit, onClear }) {
  return (
    <tr>
      <td><strong>{row.cluster}</strong></td>
      <td>{row.page_count}</td>
      <td><ActionBadge action={row.action} /></td>
      <td className="admin-reason">{row.reason ?? ''}</td>
      <td>{row.set_by ?? ''}</td>
      <td><LastReviewed at={row.reviewed_at} /></td>
      <td className="admin-cell-actions">
        <button className="btn btn-sm" onClick={onEdit}>Edit</button>
        {row.action != null && (
          <button className="btn btn-sm btn-secondary" onClick={onClear}>Clear</button>
        )}
      </td>
    </tr>
  );
}

function ActionBadge({ action }) {
  if (action == null) return <span className="admin-badge badge-warning">Unset</span>;
  if (action === 'include') return <span className="admin-badge badge-success">Include</span>;
  return <span className="admin-badge badge-default">Exclude</span>;
}

function LastReviewed({ at }) {
  if (!at) return <span style={{ color: '#a00' }}>never</span>;
  const days = Math.floor((Date.now() - new Date(at).getTime()) / 86_400_000);
  const label = days < 1 ? 'today' : days === 1 ? '1 day ago' : `${days} days ago`;
  const stale = days > 90;
  return (
    <span title={at} style={{ color: stale ? '#a00' : 'inherit' }}>
      {label}{stale && <span title="Policy is stale (>90 days)" style={{ marginLeft: '4px' }}>⚠</span>}
    </span>
  );
}

function EditModal({ target, onCancel, onApply }) {
  const [action, setAction] = useState(target.currentAction ?? 'include');
  const [reason, setReason] = useState('');

  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-lg)' }}>
          Edit policy: {target.cluster}
        </h2>
        <div className="form-field">
          <label>
            <input
              type="radio"
              name="kg-action"
              checked={action === 'include'}
              onChange={() => setAction('include')}
            />{' '}
            Include in KG
          </label>
          <label style={{ marginTop: 'var(--space-xs)', display: 'block' }}>
            <input
              type="radio"
              name="kg-action"
              checked={action === 'exclude'}
              onChange={() => setAction('exclude')}
            />{' '}
            Exclude from KG
          </label>
        </div>
        <div className="form-field">
          <label>Reason</label>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            placeholder="Optional reason for this policy decision…"
          />
        </div>
        <div className="modal-actions">
          <button className="btn btn-ghost" onClick={onCancel}>Cancel</button>
          <button className="btn btn-primary" onClick={() => onApply(action, reason)}>
            Preview
          </button>
        </div>
      </div>
    </div>
  );
}

function EstimateConfirmModal({ target, estimate, onCancel, onConfirm }) {
  const currentVerb = target.currentAction ? target.currentAction.toUpperCase() : 'UNSET';
  const nextVerb = estimate.pendingAction === 'include' ? 'INCLUDE' : 'EXCLUDE';

  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
          Confirm: {target.cluster} → {nextVerb}
        </h2>
        <p>
          Toggling <strong>{target.cluster}</strong> from {currentVerb} → {nextVerb} will affect{' '}
          <strong>{estimate.page_count}</strong> pages.
        </p>
        {estimate.note && (
          <p style={{ color: 'var(--text-muted, #888)' }}>
            <em>{estimate.note}</em>
          </p>
        )}
        <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
          <button className="btn btn-ghost" onClick={onCancel}>Cancel</button>
          <button className="btn btn-primary" onClick={onConfirm}>Confirm</button>
        </div>
      </div>
    </div>
  );
}

function ReconciliationPanel({ statuses }) {
  return (
    <div className="admin-callout">
      <strong>Reconciliation in progress:</strong>
      <ul style={{ margin: 'var(--space-xs) 0 0', paddingLeft: 'var(--space-lg)' }}>
        {statuses.map((s) => (
          <li key={s.cluster}>
            {s.cluster}: <strong>{s.state}</strong>{' '}
            ({s.processed ?? 0}/{s.total_pages ?? '?'} pages)
            {s.errors > 0 && (
              <span style={{ color: '#a00', marginLeft: '4px' }}>
                ({s.errors} errors)
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
