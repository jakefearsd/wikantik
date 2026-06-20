import { useState, useEffect, useCallback } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import '../../styles/admin.css';

// Defaults must match wikantik.kg_policy.bootstrap.{include,exclude} in
// wikantik.properties so the wizard renders the same set of pre-checked
// clusters that the spec / docs describe.
const DEFAULT_INCLUDE = [
  'wikantik-development','agentic-ai','generative-ai','machine-learning',
  'devops-sre','databases','software-engineering-practices','mathematics',
  'security','distributed-systems','software-architecture','cloud-platforms',
  'frontend-development','java','warehouse-automation','data-engineering',
  'design-patterns','agent-cookbook','operations-research',
  'web-services-and-apis','data-structures','mechanical-engineering',
  'networking','computer-science-foundations','retirement-planning',
  'index-fund-investing','personal-finance',
];
const DEFAULT_EXCLUDE = [
  'engineering-leadership','linux-for-windows-users','geopolitics-and-finance',
  'van-life','hobby-woodworking','philosophy','cooking-and-food',
  'emergency-prep','berlin-history','immigration','spousal-green-card',
  'remote-host-management','russia-ukraine-war','hobbies','american-coinage',
];

export default function AdminKgPolicyBootstrap() {
  const [clustersInCorpus, setClustersInCorpus] = useState([]);
  const [picked, setPicked] = useState(() => {
    const m = new Map();
    DEFAULT_INCLUDE.forEach(c => m.set(c, 'include'));
    DEFAULT_EXCLUDE.forEach(c => m.set(c, 'exclude'));
    return m;
  });
  const [reason, setReason] = useState('bootstrap initial config');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [submitted, setSubmitted] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const data = await api.admin.kgPolicy.listClusters();
        setClustersInCorpus(data.clusters || []);
      } catch (err) { setError(err.message); }
      finally { setLoading(false); }
    })();
  }, []);

  const setAction = useCallback((cluster, action) => {
    setPicked(prev => {
      const next = new Map(prev);
      if (action == null) next.delete(cluster);
      else next.set(cluster, action);
      return next;
    });
  }, []);

  const onSubmit = useCallback(async () => {
    const include = []; const exclude = [];
    for (const [c, a] of picked.entries()) {
      if (a === 'include') include.push(c);
      else if (a === 'exclude') exclude.push(c);
    }
    try {
      await api.admin.kgPolicy.bootstrap({ include, exclude, reason });
      setSubmitted(true);
    } catch (err) { setError(err.message); }
  }, [picked, reason]);

  if (submitted) {
    return (
      <AdminPage>
        <div className="admin-callout">
          Bootstrap applied. Reconciliation is running in the background.
          {' '}<a href="/admin/kg-policy">Open dashboard</a>.
        </div>
      </AdminPage>
    );
  }

  return (
    <AdminPage loading={loading} error={error} loadingLabel="Loading clusters…">
      <div className="admin-card">
        <h2>KG inclusion bootstrap</h2>
        <p>
          The KG defaults to <em>exclude all</em>. This wizard pre-selects tech and
          finance clusters as <strong>include</strong> and lifestyle clusters as <strong>exclude</strong>.
          Adjust as desired, then commit. Each row's choice is recorded with the shared reason below.
        </p>
        <div className="form-group">
          <label>Reason (recorded on every audit row)</label>
          <input type="text" value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        <table className="admin-table">
          <thead>
            <tr><th>Cluster</th><th>Pages</th><th>Action</th></tr>
          </thead>
          <tbody>
            {clustersInCorpus.map(c => (
              <tr key={c.cluster}>
                <td><strong>{c.cluster}</strong></td>
                <td>{c.page_count}</td>
                <td>
                  <label style={{ marginRight: '12px' }}>
                    <input type="radio"
                           checked={picked.get(c.cluster) === 'include'}
                           onChange={() => setAction(c.cluster, 'include')} />
                    {' '}Include
                  </label>
                  <label style={{ marginRight: '12px' }}>
                    <input type="radio"
                           checked={picked.get(c.cluster) === 'exclude'}
                           onChange={() => setAction(c.cluster, 'exclude')} />
                    {' '}Exclude
                  </label>
                  <label>
                    <input type="radio"
                           checked={!picked.has(c.cluster)}
                           onChange={() => setAction(c.cluster, null)} />
                    {' '}Skip (defaults to exclude)
                  </label>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="modal-actions" style={{ marginTop: '1rem' }}>
          <button className="btn btn-primary" onClick={onSubmit}>Commit bootstrap</button>
        </div>
      </div>
    </AdminPage>
  );
}
