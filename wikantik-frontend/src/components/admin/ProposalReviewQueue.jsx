import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { api } from '../../api/client';
import PageLink from './PageLink';
import { AdminTable } from './table';

/**
 * Page size for the proposal queue. 25 keeps each page comfortably scrollable
 * even with the typed Details renderer expanding to multiple lines per row.
 * Server caps at 500 (see AdminKnowledgeResource.MAX_PROPOSAL_PAGE_SIZE).
 */
const PAGE_SIZE = 25;

const FILTERS = [
  { value: 'all',       label: 'All' },
  { value: 'awaiting',  label: 'Awaiting machine review' },
  { value: 'approved',  label: 'Machine approved' },
  { value: 'rejected',  label: 'Machine rejected' },
  { value: 'abstained', label: 'Machine abstained' },
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
    // Speed path: skip the reason field for obvious junk. The audit trail
    // still records "(no reason given)" so triagers can grep for these
    // later. The dispatcher rewrites action.id 'reject-quick' → 'reject'
    // before talking to the server.
    id: 'reject-quick',
    label: 'Reject (no reason)',
    variant: 'danger',
    confirm: {
      title: 'Reject Without Reason',
      body: (selected) => (
        <p>
          Reject <strong>{selected.length}</strong> proposal{selected.length !== 1 ? 's' : ''} without
          recording a reason? The audit trail will show <code>(no reason given)</code>.
        </p>
      ),
      confirmLabel: 'Reject',
    },
  },
  {
    id: 'judge',
    label: 'Judge',
    variant: 'default',
    // No confirm — runs the LLM judge on selected proposals immediately.
  },
];

/** Placeholder recorded in the audit trail for quick-reject actions. */
const QUICK_REJECT_REASON = '(no reason given)';

/**
 * Compact relative-time formatter for the judge-rationale disclosure. Returns
 * one of "just now", "Xm ago", "Xh ago", "Xd ago", or a localized date for
 * anything older than a month. Pair with a {@code title} attribute carrying
 * the full {@code toLocaleString()} so hover yields the precise timestamp.
 */
export function formatRelativeTime(isoString) {
  if (!isoString) return '';
  const ts = new Date(isoString);
  if (Number.isNaN(ts.getTime())) return '';
  const diffSec = Math.max(0, Math.floor((Date.now() - ts.getTime()) / 1000));
  if (diffSec < 60)         return 'just now';
  if (diffSec < 3600)       return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400)      return `${Math.floor(diffSec / 3600)}h ago`;
  if (diffSec < 86400 * 30) return `${Math.floor(diffSec / 86400)}d ago`;
  return ts.toLocaleDateString();
}

function VerdictBadge({ status, expanded, onToggle }) {
  const map = {
    approved: { glyph: '✓', color: '#2a8d2a', label: 'approved' },
    rejected: { glyph: '✗', color: '#b13a3a', label: 'rejected' },
    abstain:  { glyph: '◯', color: '#888',    label: 'abstain'  },
  };
  const info = map[status];
  if (!info) {
    return <span style={{ color: '#aaa' }} title="LLM judge has not evaluated this proposal yet">–</span>;
  }
  return (
    <button
      type="button"
      onClick={onToggle}
      title="Click to see judge reasoning"
      aria-expanded={!!expanded}
      style={{
        color: info.color, fontWeight: 600, cursor: 'pointer',
        background: 'none', border: 'none', padding: 0, font: 'inherit',
      }}
    >
      {info.glyph} {info.label} {expanded ? '▾' : '▸'}
    </button>
  );
}

function ConflictBadge({ kind, title }) {
  const colors = {
    conflict:  { bg: '#fff4e6', border: '#e8a04c', fg: '#a35d12', text: 'Conflict' },
    rejected:  { bg: '#fff0f0', border: '#d88',    fg: '#a33',    text: 'Already rejected' },
  }[kind];
  return (
    <span
      title={title}
      style={{
        display: 'inline-block',
        marginLeft: 8,
        padding: '1px 8px',
        background: colors.bg,
        border: `1px solid ${colors.border}`,
        borderRadius: 12,
        fontSize: '0.75em',
        color: colors.fg,
        fontWeight: 600,
        whiteSpace: 'nowrap',
      }}
    >
      {colors.text}
    </span>
  );
}

function PropertyChips({ properties }) {
  if (!properties || typeof properties !== 'object') return null;
  const entries = Object.entries(properties);
  if (entries.length === 0) return null;
  return (
    <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
      {entries.map(([k, v]) => (
        <span
          key={k}
          style={{
            fontSize: '0.75em',
            background: 'var(--bg-sidebar, #f4f1ec)',
            padding: '1px 6px',
            borderRadius: 4,
            color: 'var(--text-secondary, #666)',
          }}
        >
          {k}={typeof v === 'object' ? JSON.stringify(v) : String(v)}
        </span>
      ))}
    </div>
  );
}

function ProposedDataDetails({ proposal }) {
  const data = proposal.proposed_data || {};
  const extractor = data.extractor;
  if (proposal.proposal_type === 'new-node') {
    const nodeType = data.nodeType || 'Node';
    return (
      <div>
        <div>
          <span style={{ color: 'var(--text-muted, #888)' }}>+ {nodeType}</span>{' '}
          <strong>«{data.name || '?'}»</strong>
          {proposal.node_exists && (
            <ConflictBadge
              kind="conflict"
              title={`A node named «${data.name}» already exists in the Knowledge Graph.`}
            />
          )}
        </div>
        {extractor && (
          <div style={{ fontSize: '0.75em', color: 'var(--text-muted, #888)', marginTop: 2 }}>
            extractor: {extractor}
          </div>
        )}
        <PropertyChips properties={data.properties} />
      </div>
    );
  }
  if (proposal.proposal_type === 'new-edge') {
    return (
      <div>
        <div>
          <strong>«{data.source || '?'}»</strong>{' '}
          <span style={{ color: 'var(--text-muted, #888)' }}>—[{data.relationship || '?'}]→</span>{' '}
          <strong>«{data.target || '?'}»</strong>
          {proposal.edge_previously_rejected && (
            <ConflictBadge
              kind="rejected"
              title={`This (source, target, relationship) tuple was rejected previously.`}
            />
          )}
        </div>
        {extractor && (
          <div style={{ fontSize: '0.75em', color: 'var(--text-muted, #888)', marginTop: 2 }}>
            extractor: {extractor}
          </div>
        )}
        <PropertyChips properties={data.properties} />
      </div>
    );
  }
  // Unknown shape — fall back to compact JSON.
  return (
    <pre style={{ fontSize: '0.8em', maxWidth: '300px', overflow: 'auto', margin: 0 }}>
      {JSON.stringify(data, null, 2)}
    </pre>
  );
}

function MachineCell({ proposal, expandedReviews, onToggleExpand, reviewsCache }) {
  const expanded = !!expandedReviews[proposal.id];
  const reviews = reviewsCache[proposal.id];
  return (
    <div>
      <VerdictBadge
        status={proposal.machine_status}
        expanded={expanded}
        onToggle={proposal.machine_status ? () => onToggleExpand(proposal.id) : null}
      />
      {expanded && (
        <div
          style={{
            marginTop: 6,
            padding: 8,
            background: 'var(--bg-sidebar, #f4f1ec)',
            borderRadius: 4,
            fontSize: '0.8em',
            maxWidth: 320,
          }}
        >
          {reviews === undefined && <em>Loading judge reasoning…</em>}
          {reviews && reviews.length === 0 && <em>No machine review recorded.</em>}
          {reviews && reviews.length > 0 && (
            <ul style={{ margin: 0, paddingLeft: 16 }}>
              {reviews.filter(r => r.reviewer_kind === 'machine').map(r => (
                <li key={r.id} style={{ marginBottom: 4 }}>
                  <strong>{r.verdict}</strong>
                  {r.confidence != null && ` (${(r.confidence * 100).toFixed(0)}%)`}
                  {r.rationale && <>: {r.rationale}</>}
                  {r.created && (
                    <span
                      style={{ color: 'var(--text-muted, #888)', marginLeft: 6, whiteSpace: 'nowrap' }}
                      title={new Date(r.created).toLocaleString()}
                    >
                      · {formatRelativeTime(r.created)}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
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
  // Per-row "judge reasoning expanded?" toggle + memoised review fetches.
  const [expandedReviews, setExpandedReviews] = useState({});
  const [reviewsCache, setReviewsCache] = useState({});
  // Server-driven pagination state. currentPage is 0-indexed.
  const [currentPage, setCurrentPage] = useState(0);
  const [totalCount, setTotalCount] = useState(0);
  // Generation counter so out-of-order list-fetch responses (e.g. an in-flight
  // page-2 fetch resolving after a page-3 fetch the operator just kicked off)
  // can't overwrite newer state. Each call increments; only the latest gen
  // commits its result.
  const requestGen = useRef(0);

  const handleToggleExpand = useCallback(async (proposalId) => {
    setExpandedReviews(prev => ({ ...prev, [proposalId]: !prev[proposalId] }));
    if (!reviewsCache[proposalId]) {
      try {
        const data = await api.knowledge.listProposalReviews(proposalId);
        setReviewsCache(prev => ({ ...prev, [proposalId]: data.reviews || [] }));
      } catch (err) {
        // Surface failure inline so the user knows the disclosure didn't load.
        setReviewsCache(prev => ({ ...prev, [proposalId]: [] }));
        // eslint-disable-next-line no-console
        console.warn(`Failed to load reviews for ${proposalId}:`, err.message);
      }
    }
  }, [reviewsCache]);

  const loadProposals = useCallback(async () => {
    const gen = ++requestGen.current;
    setLoading(true);
    setError(null);
    try {
      // Push every filter to the server so pagination counts are accurate.
      // Mixing client-side filtering with server pagination would let the
      // operator land on a "Page 5 of 60" header where every page shows
      // wildly variable row counts after client-side culling.
      //
      // - 'all'       → all pending (incl. machine-rejected stragglers)
      // - 'awaiting'  → pending AND machine_status IS NULL (sentinel "(null)")
      // - 'approved'  → pending AND machine_status='approved'
      // - 'rejected'  → status='rejected', machine_status='rejected'
      //                 (auto-promoted; needs includeMachineRejected to bypass
      //                  the backend's default-off rejected-exclusion clause)
      // - 'abstained' → pending AND machine_status='abstain'
      const baseOpts = (() => {
        switch (filter) {
          case 'rejected':  return { status: 'rejected', machineStatus: 'rejected', includeMachineRejected: true };
          case 'awaiting':  return { status: 'pending', machineStatus: '(null)' };
          case 'approved':  return { status: 'pending', machineStatus: 'approved' };
          case 'abstained': return { status: 'pending', machineStatus: 'abstain' };
          case 'all':
          default:          return { status: 'pending', includeMachineRejected: true };
        }
      })();
      const opts = {
        ...baseOpts,
        limit: PAGE_SIZE,
        offset: currentPage * PAGE_SIZE,
      };
      const data = await api.knowledge.listProposalsFiltered(opts);
      // Newer request superseded us — drop this result on the floor.
      if (gen !== requestGen.current) return;
      setProposals(data.proposals || []);
      const newTotal = typeof data.total_count === 'number' ? data.total_count : 0;
      setTotalCount(newTotal);
      // Defensive: if the operator was on page N but the server now says total
      // is too small for that page (e.g. a bulk-reject just emptied the tail),
      // step back to a valid page. setCurrentPage re-triggers loadProposals.
      const lastValidPage = Math.max(0, Math.ceil(newTotal / PAGE_SIZE) - 1);
      if (currentPage > lastValidPage) {
        setCurrentPage(lastValidPage);
      }
    } catch (err) {
      if (gen !== requestGen.current) return;
      setError(err.message);
    } finally {
      if (gen === requestGen.current) setLoading(false);
    }
  }, [filter, currentPage]);

  // Filter changes always snap back to page 0 — the row counts of the next
  // filter aren't related to the current page boundary.
  useEffect(() => {
    setCurrentPage(0);
  }, [filter]);

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

  const handleQuickReject = async (id) => {
    // Speed path: skip the prompt for obvious junk. Audit trail records
    // QUICK_REJECT_REASON so triagers can identify these later.
    await api.knowledge.rejectProposal(id, QUICK_REJECT_REASON);
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
    // The "Reject (no reason)" speed-path button has its own action.id
    // ('reject-quick') so it can render a different confirm modal, but the
    // server only knows 'approve' / 'reject' / 'judge'. Rewrite to the
    // canonical 'reject' action and substitute the audit-trail placeholder.
    const isQuickReject = action.id === 'reject-quick';
    const serverAction = isQuickReject ? 'reject' : action.id;
    const opts = isQuickReject
      ? { reason: QUICK_REJECT_REASON }
      : (reason ? { reason } : {});
    const result = await api.knowledge.bulkProposalAction(serverAction, ids, opts);
    await loadProposals();
    return result;
  };

  const columns = useMemo(() => [
    { id: 'proposal_type', label: 'Type' },
    {
      id: 'source_page',
      label: 'Source Page',
      render: (p) => <PageLink name={p.source_page} />,
    },
    {
      id: 'proposed_data',
      label: 'Details',
      render: (p) => <ProposedDataDetails proposal={p} />,
    },
    {
      id: 'confidence',
      label: 'Confidence',
      render: (p) => `${(p.confidence * 100).toFixed(0)}%`,
    },
    {
      id: 'machine_status',
      label: 'Machine',
      render: (p) => (
        <MachineCell
          proposal={p}
          expandedReviews={expandedReviews}
          onToggleExpand={handleToggleExpand}
          reviewsCache={reviewsCache}
        />
      ),
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
  ], [expandedReviews, reviewsCache, handleToggleExpand]);

  // Server-side filtering means `proposals` IS the matching set for the
  // active filter — no client-side post-filter needed.
  const visible = proposals;

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
      id: 'reject-quick',
      label: 'Reject (skip)',
      variant: 'danger',
      onClick: () => handleQuickReject(p.id),
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
        <h3 style={{ margin: 0 }}>
          {filter === 'rejected' ? 'Machine-Rejected Proposals' : 'Pending Proposals'}
          {' '}({totalCount.toLocaleString()})
        </h3>
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
        columns={columns}
        selectable
        bulkActions={BULK_ACTIONS}
        onBulkAction={handleBulkAction}
        emptyMessage="No proposals match the current filter."
        rowAction={rowAction}
        density="comfortable"
        pagination={{
          pageSize: PAGE_SIZE,
          totalCount,
          currentPage,
          onPageChange: setCurrentPage,
        }}
      />
    </div>
  );
}
