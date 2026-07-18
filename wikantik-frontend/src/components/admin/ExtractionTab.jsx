import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/client';

const FAST_POLL_MS = 2000;
const SLOW_POLL_MS = 10000;

export default function ExtractionTab() {
  const [status, setStatus] = useState(null);
  const [disabled, setDisabled] = useState(false);
  const [error, setError] = useState(null);
  const [confirmKind, setConfirmKind] = useState(null);
  const [force, setForce] = useState(false);
  const [cancelRequested, setCancelRequested] = useState(false);
  const pollRef = useRef(null);
  const stateRef = useRef('IDLE');

  const fetchStatus = async () => {
    try {
      const s = await api.knowledge.getExtractionStatus();
      setStatus(s);
      setDisabled(false);
      stateRef.current = s?.state || 'IDLE';
      if ((s?.state || 'IDLE') !== 'RUNNING') setCancelRequested(false);
    } catch (e) {
      if (e.status === 503) {
        setDisabled(true);
        stateRef.current = 'IDLE';
      } else {
        setError(e.message || 'Failed to fetch status');
      }
    }
  };

  useEffect(() => {
    let cancelled = false;
    fetchStatus();
    const tick = async () => {
      if (cancelled) return;
      await fetchStatus();
      if (cancelled) return;
      const active = stateRef.current === 'RUNNING';
      clearInterval(pollRef.current);
      pollRef.current = setInterval(tick, active ? FAST_POLL_MS : SLOW_POLL_MS);
    };
    pollRef.current = setInterval(tick, FAST_POLL_MS);
    return () => {
      cancelled = true;
      if (pollRef.current) clearInterval(pollRef.current);
    };
     
  }, []);

  const doStart = async () => {
    setConfirmKind(null);
    setError(null);
    try {
      const next = await api.knowledge.startExtraction(force);
      if (next) {
        setStatus(next);
        stateRef.current = next.state || 'RUNNING';
      }
    } catch (e) {
      if (e.status === 409) {
        setError('An extraction run is already in progress.');
        if (e.body) setStatus(e.body);
      } else if (e.status === 503) {
        setDisabled(true);
      } else {
        setError(e.message || 'Failed to start extraction.');
      }
    }
  };

  const doCancel = async () => {
    setConfirmKind(null);
    setError(null);
    try {
      const next = await api.knowledge.cancelExtraction();
      if (next) setStatus(next);
      setCancelRequested(true);
    } catch (e) {
      if (e.status === 409) {
        setError('No extraction run is currently in progress.');
      } else {
        setError(e.message || 'Failed to cancel extraction.');
      }
    }
  };

  if (disabled) {
    return (
      <div className="admin-message warning" role="status">
        Entity extraction is not configured (check{' '}
        <code>wikantik.knowledge.extractor.backend</code>).
      </div>
    );
  }

  if (!status) return <div className="admin-loading">Loading extraction status…</div>;

  const state = status.state || 'IDLE';
  const isRunning = state === 'RUNNING';
  const elapsedSec = status.elapsedMs ? Math.round(status.elapsedMs / 1000) : 0;
  const pagesPct = status.totalPages > 0
    ? Math.round((status.processedPages / status.totalPages) * 100) : 0;
  const chunksPct = status.totalChunks > 0
    ? Math.round((status.processedChunks / status.totalChunks) * 100) : 0;

  return (
    <div className="extraction-tab">
      <div className="admin-section-header">
        <h3>Entity Extraction</h3>
      </div>
      <p className="admin-section-help">
        Runs the LLM-based entity extractor across every chunk in the corpus,
        writing entity mentions and filing knowledge-graph proposals. This is
        the upstream of the <em>Proposals</em> tab. Long-running — typical
        runs take minutes per hundred pages depending on backend.
      </p>

      <div className="stats-grid" data-testid="extraction-header">
        <StatCard
          label="State"
          value={state}
          subtitle={`backend: ${status.extractorBackend || 'unknown'}`}
        />
        <StatCard label="Concurrency" value={status.concurrency ?? 0} />
        <StatCard
          label="Elapsed"
          value={`${elapsedSec}s`}
          subtitle={status.startedAt ? `started ${status.startedAt}` : '—'}
        />
        {status.finishedAt && <StatCard label="Finished" value={status.finishedAt} />}
      </div>

      {state !== 'IDLE' && (
        <div className="extraction-progress" data-testid="extraction-progress">
          <ProgressBar
            label={`Pages — ${status.processedPages}/${status.totalPages} (${pagesPct}%)`}
            value={pagesPct}
            failed={status.failedPages}
          />
          <ProgressBar
            label={`Chunks — ${status.processedChunks}/${status.totalChunks} (${chunksPct}%)`}
            value={chunksPct}
            failed={status.failedChunks}
          />
          <div className="extraction-counters">
            <span>mentions written: <strong>{status.mentionsWritten ?? 0}</strong></span>
            <span>proposals filed: <strong>{status.proposalsFiled ?? 0}</strong></span>
            <span>excluded skipped: <strong>{status.excludedSkipped ?? 0}</strong></span>
          </div>
          {cancelRequested && isRunning && (
            <div className="admin-message info" role="status">
              Cancellation requested — the in-flight page will finish, then the run stops.
            </div>
          )}
        </div>
      )}

      <div className="admin-actions-row" style={{ marginTop: 'var(--space-md)' }}>
        <button
          className="btn btn-primary btn-danger"
          onClick={() => setConfirmKind(force ? 'force' : 'start')}
          disabled={isRunning}
        >
          {isRunning ? `Running (${state})` : 'Extract Mentions'}
        </button>
        <label style={{ marginLeft: 'var(--space-md)' }}>
          <input
            type="checkbox"
            checked={force}
            onChange={(e) => setForce(e.target.checked)}
            disabled={isRunning}
          />
          {' '}Force re-extract
        </label>
        {isRunning && (
          <button
            className="btn btn-secondary"
            style={{ marginLeft: 'var(--space-md)' }}
            onClick={() => setConfirmKind('cancel')}
          >
            Cancel
          </button>
        )}
      </div>

      {status.lastError && (
        <details className="errors-panel" style={{ marginTop: 'var(--space-md)' }}>
          <summary>Last error</summary>
          <pre>{status.lastError}</pre>
        </details>
      )}

      {error && (
        <div className="admin-message error" role="alert"
             style={{ marginTop: 'var(--space-md)' }}>
          {error}
        </div>
      )}

      {confirmKind === 'start' && (
        <ConfirmDialog
          title="Confirm Extraction"
          message="Run entity extraction over every page? This calls the LLM extractor and may take a long time."
          onConfirm={doStart}
          onCancel={() => setConfirmKind(null)}
        />
      )}
      {confirmKind === 'force' && (
        <ConfirmDialog
          title="Confirm Force Re-Extract"
          message="Force re-extract every page, including ones already processed? This will re-run the LLM extractor over the entire corpus."
          onConfirm={doStart}
          onCancel={() => setConfirmKind(null)}
        />
      )}
      {confirmKind === 'cancel' && (
        <ConfirmDialog
          title="Cancel Extraction"
          message="Cancel the in-flight extraction run? The current page will finish; remaining pages will be skipped."
          onConfirm={doCancel}
          onCancel={() => setConfirmKind(null)}
        />
      )}
    </div>
  );
}

function StatCard({ label, value, subtitle }) {
  return (
    <div className="stat-card">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {subtitle && <div className="stat-subtitle">{subtitle}</div>}
    </div>
  );
}

function ProgressBar({ label, value, failed }) {
  return (
    <div className="progress-row">
      <div className="progress-label">{label}</div>
      <div className="progress-track">
        <div
          className="progress-fill"
          style={{ width: `${Math.min(100, Math.max(0, value))}%` }}
        />
      </div>
      {failed > 0 && (
        <div className="progress-failed" style={{ color: 'var(--color-danger, #c00)' }}>
          {failed} failed
        </div>
      )}
    </div>
  );
}

function ConfirmDialog({ title, message, onConfirm, onCancel }) {
  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div
        className="modal-content admin-modal"
        role="dialog"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
          {title}
        </h2>
        <p style={{ marginBottom: 'var(--space-lg)' }}>{message}</p>
        <div className="admin-actions-row">
          <button className="btn btn-primary btn-danger" onClick={onConfirm}>Continue</button>
          <button className="btn btn-ghost" onClick={onCancel}>Dismiss</button>
        </div>
      </div>
    </div>
  );
}
