import { useState, useEffect, useCallback } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import '../../styles/admin.css';

export default function AdminKgPolicyPending() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const reload = useCallback(async () => {
    try { setData(await api.admin.kgPolicy.pending()); }
    catch (err) { setError(err.message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const dismiss = async (cluster) => {
    try {
      await api.admin.kgPolicy.markReviewed(cluster);
      await reload();
    } catch (err) { setError(err.message); }
  };

  return (
    <AdminPage loading={loading} error={error} loadingLabel="Loading pending review…">
      {data && (
        <>
          <Section
            title="Unset clusters (default exclude is in effect — make a decision)"
            rows={data.unset_clusters || []}
            cols={[ { label: 'Cluster', get: r => r.cluster },
                    { label: 'Pages',   get: r => r.page_count } ]}
            actionLabel={null}
          />
          <Section
            title="Stale reviews (>90 days)"
            rows={data.stale_reviews || []}
            cols={[ { label: 'Cluster',     get: r => r.cluster },
                    { label: 'Action',      get: r => r.action ?? '' },
                    { label: 'Last reviewed', get: r => r.reviewed_at ?? 'never' } ]}
            actionLabel="Mark reviewed"
            onAction={(r) => dismiss(r.cluster)}
          />
          <Section
            title="Recent page-count changes"
            rows={data.recent_count_changes || []}
            cols={[ { label: 'Cluster', get: r => r.cluster } ]}
            actionLabel={null}
            emptyText="No drift detected (this section's threshold logic is deferred — see spec)."
          />
        </>
      )}
    </AdminPage>
  );
}

function Section({ title, rows, cols, actionLabel, onAction, emptyText }) {
  return (
    <div className="admin-card">
      <h3>{title}</h3>
      {rows.length === 0
        ? <p style={{color:'#888'}}><em>{emptyText ?? 'Nothing to review.'}</em></p>
        : (
          <table className="admin-table">
            <thead>
              <tr>{cols.map(c => <th key={c.label}>{c.label}</th>)}{actionLabel && <th>Action</th>}</tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i}>
                  {cols.map(c => <td key={c.label}>{c.get(r)}</td>)}
                  {actionLabel && <td><button className="btn btn-sm" onClick={() => onAction(r)}>{actionLabel}</button></td>}
                </tr>
              ))}
            </tbody>
          </table>
        )}
    </div>
  );
}
