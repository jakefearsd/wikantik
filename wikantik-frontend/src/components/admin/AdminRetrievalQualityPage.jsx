import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import Sparkline from './Sparkline';
import '../../styles/admin.css';

const MODES = ['bm25', 'hybrid', 'hybrid_graph'];
const METRICS = ['ndcg_at_5', 'ndcg_at_10', 'recall_at_20', 'mrr'];

export default function AdminRetrievalQualityPage() {
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [filterSet, setFilterSet] = useState('');
  const [filterMode, setFilterMode] = useState('');
  const [running, setRunning] = useState(false);

  const loadRuns = async () => {
    setLoading(true);
    setError(null);
    try {
      const resp = await api.admin.listRetrievalRuns({
        querySetId: filterSet || undefined,
        mode: filterMode || undefined,
        limit: 30,
      });
      setRuns(resp?.data?.recent_runs || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRuns();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterSet, filterMode]);

  const runNow = async (querySetId, mode) => {
    setRunning(true);
    try {
      await api.admin.runRetrievalNow(querySetId, mode);
      await loadRuns();
    } catch (err) {
      setError(err.message);
    } finally {
      setRunning(false);
    }
  };

  // Bucket runs by (querySetId, mode). Backend returns recent-first; the latest
  // entry per bucket becomes the headline value, the rest feed the sparkline.
  const cells = useMemo(() => {
    const out = {};
    for (const r of runs) {
      const k = `${r.query_set_id}|${r.mode}`;
      if (!out[k]) out[k] = { querySetId: r.query_set_id, mode: r.mode, latest: r, history: [] };
      out[k].history.push(r);
    }
    return Object.values(out);
  }, [runs]);

  if (loading) return <div className="admin-loading">Loading retrieval-quality runs…</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <div className="admin-users page-enter">
      <h2>Retrieval Quality</h2>
      <div className="admin-toolbar">
        <label>
          Query set:{' '}
          <input
            type="text"
            value={filterSet}
            onChange={e => setFilterSet(e.target.value)}
            placeholder="(any)"
          />
        </label>
        <label style={{ marginLeft: '12px' }}>
          Mode:{' '}
          <select value={filterMode} onChange={e => setFilterMode(e.target.value)}>
            <option value="">(any)</option>
            {MODES.map(m => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        </label>
      </div>

      <table className="admin-table">
        <thead>
          <tr>
            <th>Query set</th>
            <th>Mode</th>
            {METRICS.map(m => <th key={m}>{m}</th>)}
            <th>Run</th>
          </tr>
        </thead>
        <tbody>
          {cells.length === 0 && (
            <tr>
              <td colSpan={2 + METRICS.length + 1}><em>No runs yet.</em></td>
            </tr>
          )}
          {cells.map(cell => (
            <tr key={`${cell.querySetId}-${cell.mode}`}>
              <td>{cell.querySetId}</td>
              <td>{cell.mode}</td>
              {METRICS.map(metric => {
                // history is recent-first; reverse so the sparkline runs left-to-right in time order.
                const series = cell.history
                  .map(r => r[metric])
                  .filter(v => typeof v === 'number')
                  .slice()
                  .reverse();
                const latest = cell.latest[metric];
                return (
                  <td key={metric}>
                    <div>{typeof latest === 'number' ? latest.toFixed(3) : '—'}</div>
                    <Sparkline values={series} />
                  </td>
                );
              })}
              <td>
                <button
                  disabled={running}
                  onClick={() => runNow(cell.querySetId, cell.mode)}
                >
                  {running ? '…' : 'Run now'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
