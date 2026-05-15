import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/client';

const FAST_POLL_MS = 2000;
const SLOW_POLL_MS = 10000;

const SUBSYSTEM_LABELS = {
  ENTITY_EXTRACTION: 'extraction',
  PROPOSAL_JUDGE: 'judge',
  EMBEDDING: 'embedding',
};

function fmtTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString();
}

function fmtDuration(ms, status) {
  if (status === 'IN_FLIGHT') return '…';
  if (ms == null || ms < 0) return '—';
  return `${ms.toLocaleString()}ms`;
}

export default function LlmActivityTab() {
  const [snapshot, setSnapshot] = useState(null);
  const [error, setError] = useState(null);
  const [expanded, setExpanded] = useState(null);
  const pollRef = useRef(null);
  const activeRef = useRef(false);

  const fetchActivity = async () => {
    try {
      const res = await api.knowledge.getLlmActivity({ limit: 200 });
      const data = res || {};
      setSnapshot(data);
      setError(null);
      activeRef.current = (data.inFlight || 0) > 0;
    } catch (e) {
      setError(e.message || 'Failed to load LLM activity');
    }
  };

  useEffect(() => {
    let cancelled = false;
    fetchActivity();
    const tick = async () => {
      if (cancelled) return;
      await fetchActivity();
      if (cancelled) return;
      clearInterval(pollRef.current);
      pollRef.current = setInterval(tick, activeRef.current ? FAST_POLL_MS : SLOW_POLL_MS);
    };
    pollRef.current = setInterval(tick, FAST_POLL_MS);
    return () => {
      cancelled = true;
      if (pollRef.current) clearInterval(pollRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (error) return <div className="admin-error">{error}</div>;
  if (!snapshot) return <div className="admin-loading">Loading LLM activity…</div>;

  if (!snapshot.enabled) {
    return (
      <div className="admin-notice">
        LLM activity recording is disabled. Set{' '}
        <code>wikantik.llm_activity.enabled = true</code> to enable it.
      </div>
    );
  }

  const calls = snapshot.calls || [];
  const errorCount = calls.filter((c) => c.status === 'ERROR').length;

  return (
    <div>
      <div className="admin-subhead" style={{ marginBottom: 'var(--space-md)' }}>
        <strong>{snapshot.inFlight || 0} in-flight</strong>
        {' · '}
        {calls.length} calls in the last {snapshot.windowMinutes} min
        {' · '}
        {errorCount} error{errorCount === 1 ? '' : 's'}
      </div>

      {calls.length === 0 ? (
        <div className="admin-empty">No LLM calls recorded in the current window.</div>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Subsystem</th>
              <th>Model</th>
              <th>Op</th>
              <th>Duration</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {calls.map((c) => (
              <ActivityRow
                key={c.seq}
                call={c}
                expanded={expanded === c.seq}
                onToggle={() => setExpanded(expanded === c.seq ? null : c.seq)}
              />
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function ActivityRow({ call, expanded, onToggle }) {
  const rowClass =
    call.status === 'ERROR'
      ? 'row-error'
      : call.status === 'IN_FLIGHT'
        ? 'row-inflight'
        : '';
  return (
    <>
      <tr className={rowClass} onClick={onToggle} style={{ cursor: 'pointer' }}>
        <td>{fmtTime(call.startedAt)}</td>
        <td>{SUBSYSTEM_LABELS[call.subsystem] || call.subsystem}</td>
        <td>{call.model}</td>
        <td>{call.operation}</td>
        <td>{fmtDuration(call.durationMs, call.status)}</td>
        <td>
          {call.status}
          {call.status === 'ERROR' && call.errorMessage && (
            <span className="row-error-message"> — {call.errorMessage}</span>
          )}
        </td>
      </tr>
      {expanded && (
        <tr className="row-detail">
          <td colSpan={6}>
            <div>
              <strong>Prompt:</strong> {call.promptPreview || '—'}
            </div>
            <div>
              <strong>Response:</strong> {call.responsePreview || '—'}
            </div>
            {call.errorMessage && (
              <div>
                <strong>Error:</strong> {call.errorMessage}
              </div>
            )}
          </td>
        </tr>
      )}
    </>
  );
}
