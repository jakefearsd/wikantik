import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import PageHeader from './PageHeader';
import Sparkline from './Sparkline';
import PageEditLink from './PageEditLink';
import '../../styles/admin.css';

const STATUS_POLL_INTERVAL_MS = 1000;
const MAX_POLLS = 120; // 1s × 120 ≈ 2 minutes
const COLUMNS = 7; // family | code | severity | count | delta | trend | expand

const rowKey = (family, code) => `${family}|${code}`;

export default function AdminDriftPage() {
  const [summary, setSummary] = useState(null);
  const [trend, setTrend] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [sweeping, setSweeping] = useState(false);
  const [progress, setProgress] = useState(null);
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

  // Poll /status every second while a sweep runs; when it stops, confirm a NEW
  // sweep landed (sweptAt advanced) before declaring done — triggerAsync returns
  // 202 before the worker flips `running`, and a fast sweep can finish before the
  // first poll, so `running=false` alone is not a completion signal.
  const startStatusPolling = useCallback((before) => {
    let tries = 0;
    const poll = async () => {
      tries += 1;
      try {
        const st = await api.admin.getDriftStatus();
        if (!mounted.current) return;
        if (st?.running) {
          setProgress(st);
        } else {
          const s = await api.admin.getDriftSummary();
          if (!mounted.current) return;
          if (s?.sweptAt && s.sweptAt !== before) {
            const t = await api.admin.getDriftTrend(30);
            if (!mounted.current) return;
            setSummary(s);
            setTrend(t);
            setPagesByKey({});
            setExpandedKey(null);
            setProgress(null);
            setSweeping(false);
            return;
          }
          // running=false but no new sweep yet → startup window; keep polling.
        }
      } catch {
        if (!mounted.current) return;
        // transient poll error — ignore and retry next tick
      }
      if (tries >= MAX_POLLS) {
        setActionError('Sweep did not complete within 2 minutes — reload to check its status.');
        setProgress(null);
        setSweeping(false);
        return;
      }
      pollTimer.current = setTimeout(poll, STATUS_POLL_INTERVAL_MS);
    };
    poll();
  }, []);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.admin.getDriftSummary(), api.admin.getDriftTrend(30), api.admin.getDriftStatus()])
      .then(([s, t, st]) => {
        if (cancelled) return;
        setSummary(s);
        setTrend(t);
        if (st?.running) {
          setSweeping(true);
          setProgress(st);
          startStatusPolling(s?.sweptAt ?? null);
        }
      })
      .catch(err => { if (!cancelled) setError(err.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [startStatusPolling]);

  const runNow = async () => {
    setSweeping(true);
    setActionError(null);
    setProgress({ running: true, phase: null, pagesScanned: 0, totalPages: 0 });
    const before = summary?.sweptAt ?? null;
    try {
      await api.admin.runDriftSweep();
    } catch (err) {
      if (mounted.current) {
        setActionError(err.status === 409 ? 'A sweep is already running.' : err.message);
        setProgress(null);
        setSweeping(false);
      }
      return;
    }
    startStatusPolling(before);
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
  // Precomputed once per trend payload so render is allocation-free
  // (mirrors AdminRetrievalQualityPage's seriesByMetric pattern).
  const sparkMap = useMemo(() => {
    const sweeps = trend?.sweeps || [];
    const map = {};
    sweeps.forEach((sw, i) => {
      for (const c of sw.counts || []) {
        const key = rowKey(c.family, c.code);
        if (!map[key]) map[key] = new Array(sweeps.length).fill(0);
        map[key][i] += c.count || 0;
      }
    });
    return map;
  }, [trend]);

  const hasSweep = !!summary?.sweptAt;
  const counts = summary?.counts || [];

  return (
    <AdminPage loading={loading} error={error} loadingLabel="Loading drift sweeps…">
      <PageHeader
        title="Metadata Drift"
        description="Frontmatter and SHACL drift burn-down across sweeps."
        actions={
          <button type="button" data-testid="drift-run-now" disabled={sweeping} onClick={runNow}>
            {sweeping ? 'Sweeping…' : 'Run sweep now'}
          </button>
        }
      />

      {actionError && <div className="error-banner">{actionError}</div>}

      {sweeping && progress && <DriftProgressBar progress={progress} />}

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
                    sparkValues={sparkMap[key] ?? []}
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

const PHASE_LABELS = {
  frontmatter: 'validating frontmatter',
  shacl: 'checking SHACL conformance',
  persisting: 'saving snapshot',
};

function DriftProgressBar({ progress }) {
  const phase = progress?.phase;
  const label = PHASE_LABELS[phase] || 'starting…';
  const total = progress?.totalPages || 0;
  const scanned = progress?.pagesScanned || 0;
  const perPage = phase === 'frontmatter' && total > 0;
  const pct = perPage ? Math.min(100, Math.round((scanned / total) * 100)) : phase ? 100 : 8;
  const text = perPage ? `${scanned} / ${total} pages — ${label}` : label;
  return (
    <div className="drift-progress" data-testid="drift-progress">
      <div
        className="drift-progress-track"
        role="progressbar"
        aria-label={text}
        aria-valuemin={perPage ? 0 : undefined}
        aria-valuemax={perPage ? total : undefined}
        aria-valuenow={perPage ? scanned : undefined}
      >
        <div className="drift-progress-fill" style={{ width: `${pct}%` }} />
      </div>
      <div className="drift-progress-label" data-testid="drift-progress-label">{text}</div>
    </div>
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
          <button
            type="button"
            data-testid={`expand-${rowKey(c.family, c.code)}`}
            aria-expanded={expanded}
            onClick={onToggle}
          >
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
                {pages.map(p => (
                  <li key={`${p.pageName}|${p.field}`}>
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
