import { useState, useCallback } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import '../../styles/admin.css';

export default function AdminKgPolicyExplain() {
  const [query, setQuery] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const onSubmit = useCallback(async (e) => {
    e.preventDefault();
    if (!query.trim()) return;
    setLoading(true); setError(null); setResult(null);
    try {
      setResult(await api.admin.kgPolicy.explain(query.trim()));
    } catch (err) {
      setError(err.message || 'lookup failed');
    } finally { setLoading(false); }
  }, [query]);

  return (
    <AdminPage>
      <div className="admin-toolbar">
        <form onSubmit={onSubmit} style={{ display: 'flex', gap: '8px', flex: 1 }}>
          <input type="text"
                 placeholder="Page name or canonical_id"
                 value={query}
                 onChange={(e) => setQuery(e.target.value)}
                 style={{ flex: 1 }} />
          <button className="btn btn-primary" type="submit" disabled={loading}>
            {loading ? 'Looking up…' : 'Explain'}
          </button>
        </form>
      </div>

      {error && <div className="admin-callout error">{error}</div>}

      {result && (
        <div className="admin-card">
          <h3>{result.page_name} <small style={{color:'#888'}}>({result.canonical_id})</small></h3>
          <table className="admin-table">
            <tbody>
              <tr><td>Cluster</td><td>{result.cluster ?? <em>(unclustered)</em>}</td></tr>
              <tr><td>System page</td><td>{result.system_page ? 'yes' : 'no'}</td></tr>
              <tr>
                <td>Frontmatter override</td>
                <td>{result.frontmatter_override == null ? <em>(none)</em>
                     : (result.frontmatter_override ? 'true (force-include)' : 'false (force-exclude)')}</td>
              </tr>
              <tr>
                <td>Cluster policy</td>
                <td>{result.cluster_policy == null ? <em>(unset → default exclude)</em> : result.cluster_policy}</td>
              </tr>
              <tr>
                <td><strong>Effective action</strong></td>
                <td><strong>{result.effective_action.toUpperCase()}</strong>
                    {result.exclusion_reason && <span style={{color:'#888'}}> ({result.exclusion_reason})</span>}</td>
              </tr>
            </tbody>
          </table>
          <p style={{marginTop:'1rem'}}>
            <a className="btn btn-secondary" href={`/wiki/${encodeURIComponent(result.page_name)}`} target="_blank" rel="noreferrer">Open page</a>
          </p>
        </div>
      )}
    </AdminPage>
  );
}
