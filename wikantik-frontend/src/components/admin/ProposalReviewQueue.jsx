import { useState, useEffect } from 'react';
import { api } from '../../api/client';
import PageLink from './PageLink';

export default function ProposalReviewQueue() {
  const [proposals, setProposals] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const loadProposals = async () => {
    try {
      const data = await api.knowledge.listProposals('pending', 50);
      setProposals(data.proposals || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadProposals(); }, []);

  const handleApprove = async (id) => {
    await api.knowledge.approveProposal(id);
    await loadProposals();
  };

  const handleReject = async (id) => {
    const reason = prompt('Rejection reason (optional):');
    await api.knowledge.rejectProposal(id, reason || '');
    await loadProposals();
  };

  if (loading) return <div className="admin-loading">Loading proposals...</div>;
  if (error) return <div className="admin-error">{error}</div>;

  return (
    <div className="admin-proposals">
      <h3>Pending Proposals ({proposals.length})</h3>
      {proposals.length === 0 ? (
        <p className="admin-empty">No pending proposals.</p>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              <th>Type</th>
              <th>Source Page</th>
              <th>Details</th>
              <th>Confidence</th>
              <th>Reasoning</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {proposals.map(p => (
              <tr key={p.id}>
                <td>{p.proposal_type}</td>
                <td><PageLink name={p.source_page} /></td>
                <td><pre style={{ fontSize: '0.8em', maxWidth: '300px', overflow: 'auto' }}>
                  {JSON.stringify(p.proposed_data, null, 2)}
                </pre></td>
                <td>{(p.confidence * 100).toFixed(0)}%</td>
                <td className="admin-reasoning" style={{ maxWidth: '300px', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                  {p.reasoning}
                </td>
                <td style={{ whiteSpace: 'nowrap' }}>
                  <button className="btn btn-sm btn-success" onClick={() => handleApprove(p.id)} style={{ marginRight: '4px' }}>
                    Approve
                  </button>
                  <button className="btn btn-sm btn-danger" onClick={() => handleReject(p.id)}>
                    Reject
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
