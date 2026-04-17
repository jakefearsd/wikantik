import { useEffect, useState, useRef } from 'react';
import { api } from '../../api/client';

const FAST_POLL_MS = 2000;
const SLOW_POLL_MS = 10000;

export default function IndexStatusTab() {
  const [status, setStatus] = useState(null);
  const [error, setError] = useState(null);
  const [confirming, setConfirming] = useState(false);
  const pollRef = useRef(null);
  const stateRef = useRef('IDLE');
  const hasErrorsRef = useRef(false);

  const fetchStatus = async () => {
    try {
      const s = await api.admin.getIndexStatus();
      setStatus(s);
      stateRef.current = s.rebuild?.state || 'IDLE';
      hasErrorsRef.current = (s.rebuild?.errors?.length || 0) > 0;
    } catch (e) {
      setError(e.message || 'Failed to fetch status');
    }
  };

  useEffect(() => {
    let cancelled = false;
    fetchStatus();
    const tick = async () => {
      if (cancelled) return;
      await fetchStatus();
      if (cancelled) return;
      const active = stateRef.current !== 'IDLE' || hasErrorsRef.current;
      clearInterval(pollRef.current);
      pollRef.current = setInterval(tick, active ? FAST_POLL_MS : SLOW_POLL_MS);
    };
    pollRef.current = setInterval(tick, FAST_POLL_MS);
    return () => {
      cancelled = true;
      if (pollRef.current) clearInterval(pollRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const doRebuild = async () => {
    setConfirming(false);
    setError(null);
    try {
      const next = await api.admin.rebuildIndexes();
      if (next) {
        setStatus(next);
        stateRef.current = next.rebuild?.state || 'STARTING';
      }
    } catch (e) {
      if (e.code === 'rebuild_in_flight' || e.status === 409) {
        setError('A rebuild is already in flight');
      } else if (e.code === 'rebuild_disabled' || e.status === 503) {
        setError('Rebuild disabled via wikantik.rebuild.enabled flag');
      } else {
        setError(e.message || 'Rebuild failed');
      }
    }
  };

  if (!status) return <div className="admin-loading">Loading index status…</div>;

  const rebuild = status.rebuild || {};
  const isIdle = rebuild.state === 'IDLE';
  const errors = rebuild.errors || [];

  return (
    <div className="index-status-tab">
      <div className="stats-grid">
        <StatCard
          label="Pages Indexable"
          value={status.pages?.indexable ?? 0}
          subtitle={`of ${status.pages?.total ?? 0} total`}
        />
        <StatCard
          label="Lucene Documents"
          value={`${status.lucene?.documents_indexed ?? 0} docs`}
          subtitle={`queue: ${status.lucene?.queue_depth ?? 0}`}
        />
        <StatCard
          label="Total Chunks"
          value={status.chunks?.total_chunks ?? 0}
          subtitle={`avg ${status.chunks?.avg_tokens ?? 0} tokens/chunk`}
        />
        <StatCard
          label="Lucene Queue Depth"
          value={status.lucene?.queue_depth ?? 0}
        />
      </div>

      <div className="admin-section-header">
        <h3>Rebuild</h3>
      </div>
      <div className="admin-actions-row">
        <button
          className="btn btn-primary btn-danger"
          onClick={() => setConfirming(true)}
          disabled={!isIdle}
        >
          {isIdle ? 'Rebuild Indexes' : `Rebuild (${rebuild.state})`}
        </button>
        {!isIdle && (
          <RebuildProgress
            rebuild={rebuild}
            luceneQueueDepth={status.lucene?.queue_depth ?? 0}
          />
        )}
      </div>

      {error && (
        <div className="admin-message error" role="alert" style={{ marginTop: 'var(--space-md)' }}>
          {error}
        </div>
      )}

      {errors.length > 0 && (
        <details className="errors-panel" style={{ marginTop: 'var(--space-md)' }}>
          <summary>{errors.length} rebuild error{errors.length !== 1 ? 's' : ''}</summary>
          <ul>
            {errors.slice(-20).map((e, i) => (
              <li key={i}>
                <strong>{e.page}</strong>: {e.error} <em>({e.at})</em>
              </li>
            ))}
          </ul>
        </details>
      )}

      {confirming && (
        <ConfirmDialog
          message={`This will clear the Lucene index and the chunk table, then rebuild from all ${status.pages?.indexable ?? 0} indexable pages. Search will be degraded until the rebuild completes. Continue?`}
          onConfirm={doRebuild}
          onCancel={() => setConfirming(false)}
        />
      )}
    </div>
  );
}

function StatCard({ label, value, subtitle }) {
  return (
    <div className="stat-card">
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
      {subtitle && <div className="stat-subtitle" style={{ fontSize: '0.8em', color: 'var(--text-muted)' }}>{subtitle}</div>}
    </div>
  );
}

function RebuildProgress({ rebuild, luceneQueueDepth }) {
  const iterated = rebuild.pages_iterated ?? 0;
  const total = rebuild.pages_total || 1;
  const luceneDrained = Math.max(0, (rebuild.lucene_queued ?? 0) - luceneQueueDepth);
  return (
    <div className="rebuild-progress" style={{ flex: 1 }}>
      <div><strong>State:</strong> {rebuild.state}</div>
      <progress value={iterated} max={total} style={{ width: '100%' }} />
      <div className="stat-subtitle" style={{ fontSize: '0.85em', color: 'var(--text-muted)' }}>
        {iterated}/{rebuild.pages_total ?? 0} iterated — chunked {rebuild.pages_chunked ?? 0},
        system skipped {rebuild.system_pages_skipped ?? 0}
      </div>
      {rebuild.state === 'DRAINING_LUCENE' && (
        <div style={{ marginTop: 'var(--space-sm)' }}>
          <div>Lucene queue draining…</div>
          <progress value={luceneDrained} max={rebuild.lucene_queued || 1} style={{ width: '100%' }} />
        </div>
      )}
    </div>
  );
}

function ConfirmDialog({ message, onConfirm, onCancel }) {
  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div className="modal-content admin-modal" role="dialog" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
          Confirm Rebuild
        </h2>
        <p style={{ marginBottom: 'var(--space-lg)' }}>{message}</p>
        <div className="admin-actions-row">
          <button className="btn btn-primary btn-danger" onClick={onConfirm}>Continue</button>
          <button className="btn btn-ghost" onClick={onCancel}>Cancel</button>
        </div>
      </div>
    </div>
  );
}
