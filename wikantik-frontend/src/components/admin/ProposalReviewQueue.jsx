import { useState, useEffect, useCallback } from 'react';
import { api } from '../../api/client';
import PageLink from './PageLink';

const FILTERS = [
  { value: 'all',       label: 'All' },
  { value: 'awaiting',  label: 'Awaiting machine review' },
  { value: 'approved',  label: 'Machine approved' },
  { value: 'rejected',  label: 'Machine rejected' },
  { value: 'abstained', label: 'Machine abstained' },
];

function VerdictBadge({ status, onHover }) {
  const map = {
    approved: { glyph: '✓', color: '#2a8d2a', label: 'approved' },
    rejected: { glyph: '✗', color: '#b13a3a', label: 'rejected' },
    abstain:  { glyph: '◯', color: '#888',    label: 'abstain'  },
  };
  const info = map[status];
  if (!info) return <span style={{ color: '#aaa' }} title="not yet judged">–</span>;
  return (
    <span
      style={{ color: info.color, fontWeight: 600, cursor: 'help' }}
      title={info.label}
      onMouseEnter={onHover}
    >
      {info.glyph} {info.label}
    </span>
  );
}

export default function ProposalReviewQueue() {
  const [proposals, setProposals] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [filter, setFilter] = useState('all');
  const [reviewsCache, setReviewsCache] = useState({});
  const [judgeRunning, setJudgeRunning] = useState(false);

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

  useEffect(() => { loadProposals(); }, [loadProposals]);

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
    try { await api.knowledge.runJudge(); }
    finally { setTimeout(() => { setJudgeRunning(false); loadProposals(); }, 1500); }
  };

  const fetchReviews = async (id) => {
    if (reviewsCache[id]) return;
    try {
      const data = await api.knowledge.listProposalReviews(id);
      setReviewsCache(prev => ({ ...prev, [id]: data.reviews || [] }));
    } catch (_) { /* keep silent — tooltip is informational */ }
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

  if (loading) return <div className="admin-loading">Loading proposals...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div className="admin-proposals">
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
      {visible.length === 0 ? (
        <p className="admin-empty">No proposals match the current filter.</p>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              <th>Type</th>
              <th>Source Page</th>
              <th>Details</th>
              <th>Confidence</th>
              <th>Machine</th>
              <th>Reasoning</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {visible.map(p => (
              <tr key={p.id}>
                <td>{p.proposal_type}</td>
                <td><PageLink name={p.source_page} /></td>
                <td><pre style={{ fontSize: '0.8em', maxWidth: '300px', overflow: 'auto' }}>
                  {JSON.stringify(p.proposed_data, null, 2)}
                </pre></td>
                <td>{(p.confidence * 100).toFixed(0)}%</td>
                <td
                  onMouseEnter={() => fetchReviews(p.id)}
                  title={reviewsCache[p.id]?.find(r => r.reviewer_kind === 'machine')?.rationale || ''}
                >
                  <VerdictBadge status={p.machine_status} onHover={() => fetchReviews(p.id)} />
                </td>
                <td className="admin-reasoning" style={{ maxWidth: '300px', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                  {p.reasoning}
                </td>
                <td style={{ whiteSpace: 'nowrap' }}>
                  <button className="btn btn-sm btn-success" onClick={() => handleApprove(p.id)} style={{ marginRight: '4px' }}>
                    Approve
                  </button>
                  <button className="btn btn-sm btn-danger" onClick={() => handleReject(p.id)} style={{ marginRight: '4px' }}>
                    Reject
                  </button>
                  <button className="btn btn-sm" onClick={() => handleJudgeNow(p.id)} title="Run the judge LLM on this proposal now">
                    Judge now
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
