import { useEffect, useState, useRef } from 'react';
import { api } from '../../api/client';

const FAST_POLL_MS = 2000;
const SLOW_POLL_MS = 10000;

export default function IndexStatusTab() {
  const [status, setStatus] = useState(null);
  const [error, setError] = useState(null);
  const [confirming, setConfirming] = useState(false);
  const [reindexMessage, setReindexMessage] = useState(null);
  const pollRef = useRef(null);
  const stateRef = useRef('IDLE');
  const hasErrorsRef = useRef(false);
  const bootstrapStateRef = useRef('IDLE');

  const fetchStatus = async () => {
    try {
      const s = await api.admin.getIndexStatus();
      setStatus(s);
      stateRef.current = s.rebuild?.state || 'IDLE';
      hasErrorsRef.current = (s.rebuild?.errors?.length || 0) > 0;
      bootstrapStateRef.current = s.embeddings?.bootstrap?.state || 'IDLE';
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
      const active = stateRef.current !== 'IDLE'
        || hasErrorsRef.current
        || bootstrapStateRef.current === 'RUNNING';
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

  const doReindexEmbeddings = async () => {
    setError(null);
    setReindexMessage(null);
    try {
      const r = await api.admin.reindexEmbeddings();
      setReindexMessage(`Embedding reindex dispatched (state: ${r?.state || 'RUNNING'})`);
      bootstrapStateRef.current = 'RUNNING';
      fetchStatus();
    } catch (e) {
      if (e.code === 'embedding_bootstrap_running' || e.status === 409) {
        setError('An embedding bootstrap is already running');
      } else if (e.code === 'hybrid_disabled' || e.status === 503) {
        setError('Hybrid search disabled via wikantik.search.hybrid.enabled flag');
      } else {
        setError(e.message || 'Reindex embeddings failed');
      }
    }
  };

  if (!status) return <div className="admin-loading">Loading index status…</div>;

  const rebuild = status.rebuild || {};
  const isIdle = rebuild.state === 'IDLE';
  const errors = rebuild.errors || [];
  const embeddings = status.embeddings || {};
  const bootstrap = embeddings.bootstrap || {};
  const embedder = embeddings.embedder || {};
  const embeddingsEnabled = !!embeddings.model_code;
  const bootstrapRunning = bootstrap.state === 'RUNNING';

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
        {embeddingsEnabled && (
          <StatCard
            label="Embeddings"
            value={`${embeddings.row_count ?? 0}`}
            subtitle={`${embeddings.model_code} · dim ${embeddings.dim ?? 0}`}
          />
        )}
      </div>

      {embeddingsEnabled && bootstrap.state && bootstrap.state !== 'DISABLED' && (
        <BootstrapProgress bootstrap={bootstrap} />
      )}

      {embeddingsEnabled && (
        <>
          <div className="admin-section-header">
            <h3>Embeddings</h3>
          </div>
          <div className="admin-actions-row">
            <button
              className="btn btn-primary btn-danger"
              onClick={doReindexEmbeddings}
              disabled={bootstrapRunning}
            >
              {bootstrapRunning ? 'Embedding Bootstrap Running…' : 'Reindex Embeddings'}
            </button>
          </div>
          {reindexMessage && (
            <div className="admin-message info" style={{ marginTop: 'var(--space-sm)' }}>
              {reindexMessage}
            </div>
          )}
          <EmbedderMetrics embedder={embedder} />
        </>
      )}

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

function BootstrapProgress({ bootstrap }) {
  const state = bootstrap.state;
  const total = bootstrap.chunks_total || 0;
  const processed = bootstrap.chunks_processed || 0;
  const pct = total > 0 ? Math.min(100, Math.round((processed / total) * 100)) : 0;
  const stateLabel = {
    IDLE: 'Idle',
    SKIPPED_ALREADY_POPULATED: 'Already populated — skipped',
    SKIPPED_NO_CHUNKS: 'No chunks to embed — skipped',
    RUNNING: 'Running',
    COMPLETED: 'Completed',
    FAILED: 'Failed',
  }[state] || state;
  return (
    <div className="admin-section" style={{ marginTop: 'var(--space-md)' }}>
      <div className="admin-section-header"><h3>Embedding Bootstrap</h3></div>
      <div><strong>State:</strong> {stateLabel}</div>
      {state === 'RUNNING' && total > 0 && (
        <>
          <progress value={processed} max={total} style={{ width: '100%' }} />
          <div className="stat-subtitle" style={{ fontSize: '0.85em', color: 'var(--text-muted)' }}>
            {processed}/{total} chunks embedded ({pct}%)
          </div>
        </>
      )}
      {(state === 'COMPLETED' || state === 'SKIPPED_ALREADY_POPULATED' || state === 'SKIPPED_NO_CHUNKS') && bootstrap.completed_at && (
        <div className="stat-subtitle" style={{ fontSize: '0.85em', color: 'var(--text-muted)' }}>
          Finished at {bootstrap.completed_at}
        </div>
      )}
      {state === 'FAILED' && bootstrap.error_message && (
        <div className="admin-message error" role="alert" style={{ marginTop: 'var(--space-sm)' }}>
          {bootstrap.error_message}
        </div>
      )}
    </div>
  );
}

function EmbedderMetrics({ embedder }) {
  const state = embedder.circuit_state || 'DISABLED';
  const hit = Number(embedder.cache_hit || 0);
  const miss = Number(embedder.cache_miss || 0);
  const total = hit + miss;
  const hitRate = total > 0 ? ((hit / total) * 100).toFixed(1) + '%' : '—';
  const stateColor = {
    CLOSED: 'var(--color-success, #2e7d32)',
    HALF_OPEN: 'var(--color-warning, #ed6c02)',
    OPEN: 'var(--color-error, #c62828)',
    DISABLED: 'var(--text-muted)',
  }[state] || 'var(--text-muted)';
  return (
    <div className="admin-section" style={{ marginTop: 'var(--space-md)' }}>
      <div style={{ marginBottom: 'var(--space-sm)' }}>
        <strong>Circuit:</strong>{' '}
        <span style={{ color: stateColor, fontWeight: 600 }}>{state}</span>
      </div>
      <div className="stats-grid">
        <StatCard label="Cache Hit Rate" value={hitRate} subtitle={`${hit} hits / ${miss} miss`} />
        <StatCard label="Call Success" value={embedder.call_success ?? 0} />
        <StatCard label="Call Failure" value={embedder.call_failure ?? 0} />
        <StatCard label="Call Timeout" value={embedder.call_timeout ?? 0} />
        <StatCard label="Breaker Open" value={embedder.breaker_open ?? 0} />
        <StatCard label="Breaker Close" value={embedder.breaker_close ?? 0} />
        <StatCard label="Half-Open Probe" value={embedder.breaker_half_open_probe ?? 0} />
        <StatCard label="Calls Rejected" value={embedder.breaker_call_rejected ?? 0} />
      </div>
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
