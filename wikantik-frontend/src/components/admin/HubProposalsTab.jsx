import { useState, useEffect } from 'react';
import { api } from '../../api/client';

const PAGE_SIZE = 50;

export default function HubProposalsTab() {
  const [proposals, setProposals] = useState([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [hubFilter, setHubFilter] = useState('');
  const [selected, setSelected] = useState(new Set());
  const [thresholdValue, setThresholdValue] = useState(95);
  const [thresholdCount, setThresholdCount] = useState(0);
  const [generating, setGenerating] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [rejectReason, setRejectReason] = useState('');
  const [showRejectModal, setShowRejectModal] = useState(false);

  const loadData = async () => {
    try {
      const result = await api.knowledge.listHubProposals('pending', hubFilter || null, PAGE_SIZE, offset);
      setProposals(result.proposals || []);
      setTotal(result.total || 0);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadData(); }, [offset, hubFilter]);

  useEffect(() => {
    const count = proposals.filter(p => p.percentile_score >= thresholdValue).length;
    setThresholdCount(count);
  }, [thresholdValue, proposals]);

  const handleGenerate = async () => {
    setGenerating(true);
    setError(null);
    try {
      await api.knowledge.generateHubProposals();
      await loadData();
    } catch (err) {
      setError(err.message);
    } finally {
      setGenerating(false);
    }
  };

  const handleSync = async () => {
    setSyncing(true);
    setError(null);
    try {
      await api.knowledge.syncHubMemberships();
    } catch (err) {
      setError(err.message);
    } finally {
      setSyncing(false);
    }
  };

  const handleApprove = async (id) => {
    try {
      await api.knowledge.approveHubProposal(id);
      await loadData();
    } catch (err) {
      setError(err.message);
    }
  };

  const handleReject = async (id, reason) => {
    try {
      await api.knowledge.rejectHubProposal(id, reason);
      await loadData();
    } catch (err) {
      setError(err.message);
    }
  };

  const handleBulkApprove = async () => {
    if (selected.size === 0) return;
    try {
      await api.knowledge.bulkApproveHubProposals([...selected]);
      setSelected(new Set());
      await loadData();
    } catch (err) {
      setError(err.message);
    }
  };

  const handleBulkReject = async () => {
    if (selected.size === 0) return;
    try {
      await api.knowledge.bulkRejectHubProposals([...selected], rejectReason);
      setSelected(new Set());
      setShowRejectModal(false);
      setRejectReason('');
      await loadData();
    } catch (err) {
      setError(err.message);
    }
  };

  const handleThresholdApprove = async () => {
    try {
      await api.knowledge.thresholdApproveHubProposals(thresholdValue);
      await loadData();
    } catch (err) {
      setError(err.message);
    }
  };

  const toggleSelect = (id) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selected.size === proposals.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(proposals.map(p => p.id)));
    }
  };

  if (loading) return <div className="admin-loading">Loading hub proposals...</div>;

  return (
    <div>
      {error && <div className="admin-error" style={{ marginBottom: 'var(--space-sm)' }}>{error}</div>}

      {/* Top bar */}
      <div style={{ padding: 'var(--space-sm)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)', marginBottom: 'var(--space-md)', fontSize: '0.85em' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-md)', flexWrap: 'wrap' }}>
          <button className="btn btn-primary btn-sm" onClick={handleGenerate} disabled={generating}>
            {generating ? 'Generating...' : 'Generate Hub Proposals'}
          </button>
          <button className="btn btn-sm" onClick={handleSync} disabled={syncing}>
            {syncing ? 'Syncing...' : 'Sync Hub Memberships'}
          </button>
          <span><strong>Pending:</strong> {total}</span>
        </div>
      </div>

      {/* Bulk operations */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-sm)', flexWrap: 'wrap', fontSize: '0.85em' }}>
        <button className="btn btn-sm" onClick={handleBulkApprove} disabled={selected.size === 0}>
          Approve Selected ({selected.size})
        </button>
        <button className="btn btn-sm" onClick={() => selected.size > 0 && setShowRejectModal(true)} disabled={selected.size === 0}>
          Reject Selected ({selected.size})
        </button>
        <span style={{ margin: '0 var(--space-sm)' }}>|</span>
        <label>Approve all above</label>
        <input type="number" min="0" max="100" value={thresholdValue} onChange={e => setThresholdValue(Number(e.target.value))}
          style={{ width: '60px', padding: '2px 4px' }} />
        <span>% ({thresholdCount} match)</span>
        <button className="btn btn-sm" onClick={handleThresholdApprove} disabled={thresholdCount === 0}>Apply</button>
      </div>

      {/* Hub filter */}
      <div style={{ marginBottom: 'var(--space-sm)' }}>
        <input type="text" placeholder="Filter by Hub name..." value={hubFilter}
          onChange={e => { setHubFilter(e.target.value); setOffset(0); }}
          style={{ padding: '4px 8px', width: '250px' }} />
      </div>

      {/* Reject modal */}
      {showRejectModal && (
        <div style={{ padding: 'var(--space-sm)', background: 'var(--surface-secondary)', borderRadius: 'var(--radius-md)', marginBottom: 'var(--space-sm)' }}>
          <label>Rejection reason (optional):</label>
          <input type="text" value={rejectReason} onChange={e => setRejectReason(e.target.value)}
            style={{ width: '100%', padding: '4px 8px', marginTop: '4px' }} />
          <div style={{ marginTop: 'var(--space-sm)', display: 'flex', gap: 'var(--space-sm)' }}>
            <button className="btn btn-sm" onClick={handleBulkReject}>Confirm Reject</button>
            <button className="btn btn-sm" onClick={() => setShowRejectModal(false)}>Cancel</button>
          </div>
        </div>
      )}

      {/* Proposals table */}
      <table className="admin-table">
        <thead>
          <tr>
            <th style={{ width: '30px' }}>
              <input type="checkbox" checked={selected.size === proposals.length && proposals.length > 0}
                onChange={toggleSelectAll} />
            </th>
            <th>Hub</th>
            <th>Page</th>
            <th>Percentile</th>
            <th>Similarity</th>
            <th>Created</th>
            <th style={{ width: '100px' }}>Actions</th>
          </tr>
        </thead>
        <tbody>
          {proposals.map(p => (
            <tr key={p.id}>
              <td><input type="checkbox" checked={selected.has(p.id)} onChange={() => toggleSelect(p.id)} /></td>
              <td><a href={`/${encodeURIComponent(p.hub_name)}`}>{p.hub_name}</a></td>
              <td><a href={`/${encodeURIComponent(p.page_name)}`}>{p.page_name}</a></td>
              <td>{p.percentile_score.toFixed(1)}%</td>
              <td>{(p.raw_similarity * 100).toFixed(1)}%</td>
              <td style={{ fontSize: '0.85em', color: 'var(--text-muted)' }}>
                {new Date(p.created).toLocaleString()}
              </td>
              <td>
                <button className="btn btn-sm" onClick={() => handleApprove(p.id)} title="Approve">&#10003;</button>
                {' '}
                <button className="btn btn-sm" onClick={() => {
                  const reason = prompt('Rejection reason (optional):');
                  if (reason !== null) handleReject(p.id, reason);
                }} title="Reject">&#10007;</button>
              </td>
            </tr>
          ))}
          {proposals.length === 0 && (
            <tr><td colSpan={7} style={{ textAlign: 'center' }}>No pending proposals.</td></tr>
          )}
        </tbody>
      </table>

      {/* Pagination */}
      {total > PAGE_SIZE && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 'var(--space-sm)', fontSize: '0.85em' }}>
          <button className="btn btn-sm" onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))} disabled={offset === 0}>Prev</button>
          <span>Showing {offset + 1}–{Math.min(offset + proposals.length, total)} of {total}</span>
          <button className="btn btn-sm" onClick={() => setOffset(offset + PAGE_SIZE)} disabled={offset + proposals.length >= total}>Next</button>
        </div>
      )}
    </div>
  );
}
