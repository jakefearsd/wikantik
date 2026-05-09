import { useState, useEffect, useCallback } from 'react';
import { api } from '../../api/client';
import PageLink from './PageLink';
import { AdminTable } from './table';

const FILTERS = [
  { value: 'all',       label: 'All' },
  { value: 'awaiting',  label: 'Awaiting machine review' },
  { value: 'approved',  label: 'Machine approved' },
  { value: 'rejected',  label: 'Machine rejected' },
  { value: 'abstained', label: 'Machine abstained' },
];

const COLUMNS = [
  { id: 'proposal_type', label: 'Type' },
  {
    id: 'source_page',
    label: 'Source Page',
    render: (p) => <PageLink name={p.source_page} />,
  },
  {
    id: 'proposed_data',
    label: 'Details',
    render: (p) => (
      <pre style={{ fontSize: '0.8em', maxWidth: '300px', overflow: 'auto' }}>
        {JSON.stringify(p.proposed_data, null, 2)}
      </pre>
    ),
  },
  {
    id: 'confidence',
    label: 'Confidence',
    render: (p) => `${(p.confidence * 100).toFixed(0)}%`,
  },
  {
    id: 'machine_status',
    label: 'Machine',
    render: (p) => <VerdictBadge status={p.machine_status} />,
  },
  {
    id: 'reasoning',
    label: 'Reasoning',
    render: (p) => (
      <span
        className="admin-reasoning"
        style={{ maxWidth: '300px', overflow: 'hidden', textOverflow: 'ellipsis', display: 'block' }}
      >
        {p.reasoning}
      </span>
    ),
  },
];

const BULK_ACTIONS = [
  {
    id: 'approve',
    label: 'Approve',
    variant: 'primary',
    confirm: {
      title: 'Approve Proposals',
      body: (selected) => (
        <p>Approve <strong>{selected.length}</strong> proposal{selected.length !== 1 ? 's' : ''}?</p>
      ),
      confirmLabel: 'Approve',
    },
  },
  {
    id: 'reject',
    label: 'Reject',
    variant: 'danger',
    confirm: {
      title: 'Reject Proposals',
      body: (selected) => (
        <p>Reject <strong>{selected.length}</strong> proposal{selected.length !== 1 ? 's' : ''}?</p>
      ),
      confirmLabel: 'Reject',
    },
    reason: {
      label: 'Reason for rejection',
      placeholder: 'e.g. duplicate, low confidence…',
      required: true,
    },
  },
  {
    id: 'judge',
    label: 'Judge',
    variant: 'default',
    // No confirm — runs the LLM judge on selected proposals immediately.
  },
];

function VerdictBadge({ status }) {
  const map = {
    approved: { glyph: '✓', color: '#2a8d2a', label: 'approved' },
    rejected: { glyph: '✗', color: '#b13a3a', label: 'rejected' },
    abstain:  { glyph: '◯', color: '#888',    label: 'abstain'  },
  };
  const info = map[status];
  if (!info) return <span style={{ color: '#aaa' }} title="not yet judged">–</span>;
  return (
    <span style={{ color: info.color, fontWeight: 600, cursor: 'help' }} title={info.label}>
      {info.glyph} {info.label}
    </span>
  );
}

export default function ProposalReviewQueue() {
  const [proposals, setProposals] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [filter, setFilter] = useState('all');
  const [judgeRunning, setJudgeRunning] = useState(false);
  const [judgeStatus, setJudgeStatus] = useState(null);
  const [polling, setPolling] = useState(false);

  const loadProposals = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.knowledge.listProposalsFiltered({
        status: 'pending', limit: 100, includeMachineRejected: true,
      });
      setProposals(data.proposals || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchJudgeStatus = useCallback(async () => {
    try {
      const s = await api.knowledge.judgeStatus();
      setJudgeStatus(s);
      return s;
    } catch (_) { return null; }
  }, []);

  useEffect(() => { loadProposals(); fetchJudgeStatus(); }, [loadProposals, fetchJudgeStatus]);

  useEffect(() => {
    if (!polling) return;
    const id = setInterval(async () => {
      const s = await fetchJudgeStatus();
      if (s && !s.in_flight && (s.last_run_completed > 0 || s.last_run_error)) {
        setPolling(false);
        await loadProposals();
      }
    }, 2000);
    return () => clearInterval(id);
  }, [polling, fetchJudgeStatus, loadProposals]);

  const handleApprove = async (id) => {
    await api.knowledge.approveProposal(id);
    await loadProposals();
  };

  const handleReject = async (id) => {
    const reason = prompt('Rejection reason (optional):');
    await api.knowledge.rejectProposal(id, reason || '');
    await loadProposals();
  };

  const handleJudgeNow = async (id) => {
    await api.knowledge.judgeProposal(id);
    await loadProposals();
  };

  const handleRunRunner = async () => {
    setJudgeRunning(true);
    try {
      await api.knowledge.runJudge();
      setPolling(true);
    } finally {
      setJudgeRunning(false);
    }
  };

  const handleBulkAction = async (action, selectedRows, reason) => {
    const ids = selectedRows.map(p => p.id);
    const opts = reason ? { reason } : {};
    const result = await api.knowledge.bulkProposalAction(action.id, ids, opts);
    await loadProposals();
    return result;
  };

  const filterMatches = (p) => {
    if (filter === 'all') return true;
    if (filter === 'awaiting') return !p.machine_status;
    if (filter === 'approved') return p.machine_status === 'approved';
    if (filter === 'rejected') return p.machine_status === 'rejected';
    if (filter === 'abstained') return p.machine_status === 'abstain';
    return true;
  };

  const visible = proposals.filter(filterMatches);

  const rowAction = (p) => [
    {
      id: 'approve',
      label: 'Approve',
      variant: 'primary',
      onClick: () => handleApprove(p.id),
    },
    {
      id: 'reject',
      label: 'Reject',
      variant: 'danger',
      onClick: () => handleReject(p.id),
    },
    {
      id: 'judge',
      label: 'Judge now',
      variant: 'default',
      onClick: () => handleJudgeNow(p.id),
    },
  ];

  if (loading) return <div className="admin-loading">Loading proposals...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div className="admin-proposals">
      {judgeStatus && (
        <div style={{ marginBottom: '8px', fontSize: '0.9em', color: '#444' }}>
          Queue: <strong>{judgeStatus.queue_depth}</strong> pending unjudged
          {judgeStatus.in_flight && (
            <> · Running ({judgeStatus.last_run_completed}/{judgeStatus.last_run_submitted})</>
          )}
          {!judgeStatus.in_flight && judgeStatus.last_run_finished_at && (
            <> · Last run: {judgeStatus.last_run_completed}/{judgeStatus.last_run_submitted} at {new Date(judgeStatus.last_run_finished_at).toLocaleTimeString()}</>
          )}
          {judgeStatus.last_run_error && (
            <> · <span style={{ color: '#b13a3a' }}>Error: {judgeStatus.last_run_error}</span></>
          )}
        </div>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '12px' }}>
        <h3 style={{ margin: 0 }}>Pending Proposals ({visible.length}/{proposals.length})</h3>
        <label>
          Filter:
          <select value={filter} onChange={e => setFilter(e.target.value)} style={{ marginLeft: '6px' }}>
            {FILTERS.map(f => <option key={f.value} value={f.value}>{f.label}</option>)}
          </select>
        </label>
        <button className="btn btn-sm" onClick={handleRunRunner} disabled={judgeRunning}>
          {judgeRunning ? 'Running…' : 'Run judge runner'}
        </button>
      </div>

      <AdminTable
        rows={visible}
        getRowKey={(p) => p.id}
        columns={COLUMNS}
        selectable
        bulkActions={BULK_ACTIONS}
        onBulkAction={handleBulkAction}
        emptyMessage="No proposals match the current filter."
        rowAction={rowAction}
        density="comfortable"
      />
    </div>
  );
}
