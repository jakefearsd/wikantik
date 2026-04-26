import { useState, useEffect, useMemo, useCallback } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import Sparkline from './Sparkline';
import '../../styles/admin.css';

// Mirrors com.wikantik.api.eval.RetrievalMode (wikantik-api).
const MODES = ['bm25', 'hybrid', 'hybrid_graph'];
const METRICS = ['ndcg_at_5', 'ndcg_at_10', 'recall_at_20', 'mrr'];
const DEFAULT_LIMIT = 30;

const cellKey = (querySetId, mode) => `${querySetId}|${mode}`;

export default function AdminRetrievalQualityPage() {
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [filterSet, setFilterSet] = useState('');
  const [filterMode, setFilterMode] = useState('');
  const [runningKey, setRunningKey] = useState(null);

  const loadRuns = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const resp = await api.admin.listRetrievalRuns({
        querySetId: filterSet || undefined,
        mode: filterMode || undefined,
        limit: DEFAULT_LIMIT,
      });
      return resp?.recent_runs || [];
    } finally {
      setLoading(false);
    }
  }, [filterSet, filterMode]);

  useEffect(() => {
    let cancelled = false;
    loadRuns()
      .then(rows => { if (!cancelled) setRuns(rows); })
      .catch(err => { if (!cancelled) setError(err.message); });
    return () => { cancelled = true; };
  }, [loadRuns]);

  const runNow = async (querySetId, mode) => {
    const key = cellKey(querySetId, mode);
    setRunningKey(key);
    try {
      await api.admin.runRetrievalNow(querySetId, mode);
      const rows = await loadRuns();
      setRuns(rows);
    } catch (err) {
      setError(err.message);
    } finally {
      setRunningKey(null);
    }
  };

  // Bucket runs by (querySetId, mode). Backend returns recent-first; the latest
  // entry per bucket becomes the headline value, the rest feed the sparklines.
  const cells = useMemo(() => {
    const buckets = {};
    for (const r of runs) {
      const k = cellKey(r.query_set_id, r.mode);
      if (!buckets[k]) {
        buckets[k] = { querySetId: r.query_set_id, mode: r.mode, latest: r, history: [] };
      }
      buckets[k].history.push(r);
    }
    // Precompute the chronological series per metric so render is allocation-free.
    return Object.values(buckets).map(b => ({
      ...b,
      seriesByMetric: Object.fromEntries(
        METRICS.map(m => [
          m,
          b.history
            .map(r => r[m])
            .filter(v => typeof v === 'number')
            .reverse(),
        ]),
      ),
    }));
  }, [runs]);

  return (
    <AdminPage loading={loading} error={error} loadingLabel="Loading retrieval-quality runs…">
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
          {cells.map(cell => {
            const key = cellKey(cell.querySetId, cell.mode);
            const isRunning = runningKey === key;
            return (
              <tr key={key}>
                <td>{cell.querySetId}</td>
                <td>{cell.mode}</td>
                {METRICS.map(metric => {
                  const latest = cell.latest[metric];
                  return (
                    <td key={metric}>
                      <div>{typeof latest === 'number' ? latest.toFixed(3) : '—'}</div>
                      <Sparkline values={cell.seriesByMetric[metric]} />
                    </td>
                  );
                })}
                <td>
                  <button
                    disabled={isRunning}
                    onClick={() => runNow(cell.querySetId, cell.mode)}
                  >
                    {isRunning ? '…' : 'Run now'}
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </AdminPage>
  );
}
