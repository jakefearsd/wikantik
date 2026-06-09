import { useState, useEffect, useRef, useCallback } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import PageHeader from './PageHeader';
import Sparkline from './Sparkline';
import PageEditLink from './PageEditLink';
import '../../styles/admin.css';

const POLL_INTERVAL_MS = 2000;
const MAX_POLLS = 60;
const COLUMNS = 7; // family | code | severity | count | delta | trend | expand

const rowKey = (family, code) => `${family}|${code}`;

export default function AdminDriftPage() {
  const [summary, setSummary] = useState(null);
  const [trend, setTrend] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [sweeping, setSweeping] = useState(false);
  const [actionError, setActionError] = useState(null);
  const [expandedKey, setExpandedKey] = useState(null);
  const [pagesByKey, setPagesByKey] = useState({});
  const [pagesLoadingKey, setPagesLoadingKey] = useState(null);

  const mounted = useRef(true);
  const pollTimer = useRef(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      if (pollTimer.current) clearTimeout(pollTimer.current);
    };
  }, []);

  const loadAll = useCallback(
    () => Promise.all([api.admin.getDriftSummary(), api.admin.getDriftTrend(30)]),
    [],
  );

  useEffect(() => {
    let cancelled = false;
    loadAll()
      .then(([s, t]) => {
        if (!cancelled) {
          setSummary(s);
          setTrend(t);
        }
      })
      .catch(err => { if (!cancelled) setError(err.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [loadAll]);

  // Run a sweep, then poll the summary until sweptAt moves past the pre-trigger
  // value (first poll immediate, then every POLL_INTERVAL_MS, capped at MAX_POLLS).
  const runNow = async () => {
    setSweeping(true);
    setActionError(null);
    const before = summary?.sweptAt ?? null;
    try {
      await api.admin.runDriftSweep();
    } catch (err) {
      if (mounted.current) {
        setActionError(err.status === 409 ? 'A sweep is already running.' : err.message);
        setSweeping(false);
      }
      return;
    }
    let tries = 0;
    const poll = async () => {
      tries += 1;
      try {
        const s = await api.admin.getDriftSummary();
        if (!mounted.current) return;
        if (s?.sweptAt && s.sweptAt !== before) {
          const t = await api.admin.getDriftTrend(30);
          if (!mounted.current) return;
          setSummary(s);
          setTrend(t);
          setPagesByKey({}); // live drill-downs may be stale after a fresh sweep
          setExpandedKey(null);
          setSweeping(false);
          return;
        }
      } catch (err) {
        if (!mounted.current) return;
        setActionError(err.message);
        setSweeping(false);
        return;
      }
      if (tries >= MAX_POLLS) {
        setActionError('Sweep did not complete within 2 minutes — reload to check its status.');
        setSweeping(false);
        return;
      }
      pollTimer.current = setTimeout(poll, POLL_INTERVAL_MS);
    };
    poll();
  };

  const toggleExpand = async (family, code) => {
    const key = rowKey(family, code);
    if (expandedKey === key) {
      setExpandedKey(null);
      return;
    }
    setExpandedKey(key);
    if (pagesByKey[key]) return; // cached
    setPagesLoadingKey(key);
    try {
      const resp = await api.admin.getDriftPages(family, code);
      if (!mounted.current) return;
      setPagesByKey(prev => ({ ...prev, [key]: resp?.pages || [] }));
    } catch (err) {
      if (mounted.current) setActionError(err.message);
    } finally {
      if (mounted.current) setPagesLoadingKey(k => (k === key ? null : k));
    }
  };

  // Per-(family,code) series across trend sweeps, oldest→newest (API order).
  const sparkValues = (family, code) =>
    (trend?.sweeps || []).map(sw =>
      (sw.counts || [])
        .filter(c => c.family === family && c.code === code)
        .reduce((sum, c) => sum + (c.count || 0), 0),
    );

  const hasSweep = !!summary?.sweptAt;
  const counts = summary?.counts || [];

  return (
    <AdminPage loading={loading} error={error} loadingLabel="Loading drift sweeps…">
      <PageHeader
        title="Metadata Drift"
        description="Frontmatter and SHACL drift burn-down across sweeps."
        actions={
          <button data-testid="drift-run-now" disabled={sweeping} onClick={runNow}>
            {sweeping ? 'Sweeping…' : 'Run sweep now'}
          </button>
        }
      />

      {actionError && <div className="error-banner">{actionError}</div>}

      {!hasSweep ? (
        <div className="admin-empty-state" data-testid="drift-empty-state">
          <p>No drift sweeps have run yet.</p>
          <p>Run the first sweep to populate the burn-down table.</p>
        </div>
      ) : (
        <>
          <div className="admin-toolbar">
            <span>Last sweep: {new Date(summary.sweptAt).toLocaleString()}</span>
            <span>Triggered by: {summary.triggeredBy}</span>
            <span>
              Pages scanned: <span data-testid="drift-pages-scanned">{summary.pagesScanned}</span>
            </span>
            {typeof summary.durationMs === 'number' && (
              <span>Duration: {(summary.durationMs / 1000).toFixed(1)}s</span>
            )}
            {summary.shaclChecked === false && (
              <span className="admin-badge badge-warning" data-testid="drift-shacl-unchecked">
                SHACL not checked
              </span>
            )}
          </div>

          <table className="admin-table">
            <thead>
              <tr>
                <th>Family</th>
                <th>Code</th>
                <th>Severity</th>
                <th>Count</th>
                <th>Δ</th>
                <th>Trend</th>
                <th>Pages</th>
              </tr>
            </thead>
            <tbody>
              {counts.length === 0 && (
                <tr>
                  <td colSpan={COLUMNS}><em>No drift detected in the latest sweep.</em></td>
                </tr>
              )}
              {counts.map(c => {
                const key = rowKey(c.family, c.code);
                const expanded = expandedKey === key;
                const pages = pagesByKey[key];
                return (
                  <DriftRowGroup
                    key={key}
                    count={c}
                    expanded={expanded}
                    pages={pages}
                    pagesLoading={pagesLoadingKey === key}
                    sparkValues={sparkValues(c.family, c.code)}
                    onToggle={() => toggleExpand(c.family, c.code)}
                  />
                );
              })}
            </tbody>
          </table>
        </>
      )}
    </AdminPage>
  );
}

function DriftRowGroup({ count: c, expanded, pages, pagesLoading, sparkValues, onToggle }) {
  return (
    <>
      <tr>
        <td>{c.family}</td>
        <td>{c.code}</td>
        <td>{c.severity}</td>
        <td>{c.count}</td>
        <td data-testid={`delta-${c.code}`}>
          {c.delta == null ? '—' : c.delta > 0 ? `+${c.delta}` : `${c.delta}`}
        </td>
        <td><Sparkline values={sparkValues} /></td>
        <td>
          <button data-testid={`expand-${c.code}`} onClick={onToggle}>
            {expanded ? 'Hide' : 'Show'}
          </button>
        </td>
      </tr>
      {expanded && (
        <tr className="row-detail">
          <td colSpan={COLUMNS}>
            {pagesLoading && <em>Loading…</em>}
            {!pagesLoading && pages && pages.length === 0 && (
              <em>No pages currently affected — drift resolved since the sweep.</em>
            )}
            {!pagesLoading && pages && pages.length > 0 && (
              <ul>
                {pages.map((p, i) => (
                  <li key={`${p.pageName}|${p.field}|${i}`}>
                    <PageEditLink name={p.pageName} />
                    {' — '}
                    <code>{p.field}</code>: {p.message}
                    {p.suggestion != null && <> (suggested: {p.suggestion})</>}
                  </li>
                ))}
              </ul>
            )}
          </td>
        </tr>
      )}
    </>
  );
}
