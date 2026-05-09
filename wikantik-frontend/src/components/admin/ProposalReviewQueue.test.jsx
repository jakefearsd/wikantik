import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ProposalReviewQueue, { formatRelativeTime } from './ProposalReviewQueue';

// ---------------------------------------------------------------------------
// Mock api/client
// ---------------------------------------------------------------------------

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      listProposalsFiltered: vi.fn(),
      judgeStatus: vi.fn(),
      approveProposal: vi.fn(),
      rejectProposal: vi.fn(),
      judgeProposal: vi.fn(),
      runJudge: vi.fn(),
      bulkProposalAction: vi.fn(),
      listProposalReviews: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const PROPOSALS = [
  {
    id: 'aaaaaaaa-0000-0000-0000-000000000001',
    proposal_type: 'new-edge',
    source_page: 'PageA.md',
    proposed_data: { source: 'A', target: 'B', relationship: 'related' },
    confidence: 0.9,
    reasoning: 'A mentions B',
    machine_status: 'approved',
    status: 'pending',
  },
  {
    id: 'bbbbbbbb-0000-0000-0000-000000000002',
    proposal_type: 'new-node',
    source_page: 'PageB.md',
    proposed_data: { name: 'Concept X' },
    confidence: 0.6,
    reasoning: 'Found in text',
    machine_status: null,
    status: 'pending',
  },
];

const JUDGE_STATUS_IDLE = {
  configured: true,
  in_flight: false,
  last_run_submitted: 5,
  last_run_completed: 5,
  last_run_error: '',
  last_run_finished_at: '2026-05-09T10:00:00Z',
  queue_depth: 2,
};

beforeEach(() => {
  vi.clearAllMocks();
  api.knowledge.listProposalsFiltered.mockResolvedValue({ proposals: PROPOSALS });
  api.knowledge.judgeStatus.mockResolvedValue(JUDGE_STATUS_IDLE);
  api.knowledge.bulkProposalAction.mockResolvedValue({
    succeeded: [PROPOSALS[0].id, PROPOSALS[1].id],
    failed: [],
    status: 'completed',
    message: '2 of 2 proposals approved',
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// Basic rendering
// ---------------------------------------------------------------------------

describe('ProposalReviewQueue — rendering', () => {
  it('renders proposals after load', async () => {
    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');
    expect(screen.getByText('new-node')).toBeTruthy();
  });

  it('shows per-row Approve/Reject/Judge now buttons', async () => {
    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');
    const approveButtons = screen.getAllByRole('button', { name: /^Approve$/i });
    expect(approveButtons.length).toBeGreaterThanOrEqual(1);
    const rejectButtons = screen.getAllByRole('button', { name: /^Reject$/i });
    expect(rejectButtons.length).toBeGreaterThanOrEqual(1);
    const judgeButtons = screen.getAllByRole('button', { name: /Judge now/i });
    expect(judgeButtons.length).toBeGreaterThanOrEqual(1);
  });
});

// ---------------------------------------------------------------------------
// Bulk approve confirm flow
// ---------------------------------------------------------------------------

describe('ProposalReviewQueue — bulk approve', () => {
  it('shows selection bar after selecting a row', async () => {
    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);

    await screen.findByText(/1 selected/);
  });

  it('dispatches bulk approve and refreshes on confirm', async () => {
    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');

    // Select first row
    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = screen.getByRole('toolbar', { name: /Bulk actions/i });
    fireEvent.click(within(toolbar).getByRole('button', { name: /^Approve$/i }));

    // Confirm dialog
    const dialog = await screen.findByRole('dialog', { name: 'Approve Proposals' });
    fireEvent.click(within(dialog).getByRole('button', { name: /^Approve$/i }));

    await waitFor(() => {
      expect(api.knowledge.bulkProposalAction).toHaveBeenCalledWith(
        'approve',
        [PROPOSALS[0].id],
        {}
      );
    });

    // loadProposals is called again after success
    expect(api.knowledge.listProposalsFiltered).toHaveBeenCalledTimes(2);
  });

  it('shows server message in success toast', async () => {
    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = screen.getByRole('toolbar', { name: /Bulk actions/i });
    fireEvent.click(within(toolbar).getByRole('button', { name: /^Approve$/i }));

    const dialog = await screen.findByRole('dialog', { name: 'Approve Proposals' });
    fireEvent.click(within(dialog).getByRole('button', { name: /^Approve$/i }));

    await waitFor(() =>
      expect(screen.getByText(/2 of 2 proposals approved/i)).toBeInTheDocument()
    );
  });
});

// ---------------------------------------------------------------------------
// Bulk reject with required reason
// ---------------------------------------------------------------------------

describe('ProposalReviewQueue — bulk reject', () => {
  it('dispatches bulk reject with reason passed as opts', async () => {
    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = screen.getByRole('toolbar', { name: /Bulk actions/i });
    fireEvent.click(within(toolbar).getByRole('button', { name: /^Reject$/i }));

    // Confirm dialog with reason input
    const dialog = await screen.findByRole('dialog', { name: 'Reject Proposals' });

    // Fill in the reason
    const reasonInput = within(dialog).getByRole('textbox');
    fireEvent.change(reasonInput, { target: { value: 'duplicate' } });

    fireEvent.click(within(dialog).getByRole('button', { name: /^Reject$/i }));

    await waitFor(() => {
      expect(api.knowledge.bulkProposalAction).toHaveBeenCalledWith(
        'reject',
        [PROPOSALS[0].id],
        { reason: 'duplicate' }
      );
    });

    expect(api.knowledge.listProposalsFiltered).toHaveBeenCalledTimes(2);
  });

  it('shows required-reason error when confirm is clicked with empty reason', async () => {
    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = screen.getByRole('toolbar', { name: /Bulk actions/i });
    fireEvent.click(within(toolbar).getByRole('button', { name: /^Reject$/i }));

    const dialog = await screen.findByRole('dialog', { name: 'Reject Proposals' });
    // Confirm button exists; click without filling in reason
    const confirmBtn = within(dialog).getByRole('button', { name: /^Reject$/i });
    fireEvent.click(confirmBtn);

    // Should show a required-reason error without dispatching
    await waitFor(() =>
      expect(screen.getByRole('alert')).toBeInTheDocument()
    );
    expect(api.knowledge.bulkProposalAction).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// Bulk judge — no confirm, dispatches immediately
// ---------------------------------------------------------------------------

describe('ProposalReviewQueue — bulk judge', () => {
  it('dispatches bulk judge without confirm dialog', async () => {
    api.knowledge.bulkProposalAction.mockResolvedValue({
      succeeded: [PROPOSALS[0].id],
      failed: [],
      status: 'completed',
      message: '1 of 1 proposals judged',
    });

    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = screen.getByRole('toolbar', { name: /Bulk actions/i });
    fireEvent.click(within(toolbar).getByRole('button', { name: /^Judge$/i }));

    // No confirm dialog — dispatches immediately
    await waitFor(() => {
      expect(api.knowledge.bulkProposalAction).toHaveBeenCalledWith(
        'judge',
        [PROPOSALS[0].id],
        {}
      );
    });
  });
});

// ---------------------------------------------------------------------------
// Partial failure
// ---------------------------------------------------------------------------

describe('ProposalReviewQueue — partial failure', () => {
  it('shows failed count when some proposals fail', async () => {
    api.knowledge.bulkProposalAction.mockResolvedValue({
      succeeded: [PROPOSALS[0].id],
      failed: [{ id: PROPOSALS[1].id, error: 'Already accepted' }],
      status: 'completed',
      message: '1 of 2 proposals approved',
    });

    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');

    // Select both rows
    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    fireEvent.click(checkboxes[2]);
    await screen.findByText(/2 selected/);

    const toolbar = screen.getByRole('toolbar', { name: /Bulk actions/i });
    fireEvent.click(within(toolbar).getByRole('button', { name: /^Approve$/i }));

    const dialog = await screen.findByRole('dialog', { name: 'Approve Proposals' });
    fireEvent.click(within(dialog).getByRole('button', { name: /^Approve$/i }));

    await waitFor(() =>
      expect(screen.getByText(/1 failed/i)).toBeInTheDocument()
    );
  });
});

// ---------------------------------------------------------------------------
// List refresh on success
// ---------------------------------------------------------------------------

describe('ProposalReviewQueue — list refresh', () => {
  it('refetches proposals after successful bulk action', async () => {
    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');

    // Initial load = 1 call
    expect(api.knowledge.listProposalsFiltered).toHaveBeenCalledTimes(1);

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = screen.getByRole('toolbar', { name: /Bulk actions/i });
    fireEvent.click(within(toolbar).getByRole('button', { name: /^Approve$/i }));

    const dialog = await screen.findByRole('dialog', { name: 'Approve Proposals' });
    fireEvent.click(within(dialog).getByRole('button', { name: /^Approve$/i }));

    await waitFor(() =>
      expect(api.knowledge.listProposalsFiltered).toHaveBeenCalledTimes(2)
    );
  });
});

// ---------------------------------------------------------------------------
// Typed Details renderer — replaces the raw JSON dump
// ---------------------------------------------------------------------------

describe('ProposalReviewQueue — Details column typed renderer', () => {
  it('new-edge proposal renders source —[rel]→ target with extractor line', async () => {
    api.knowledge.listProposalsFiltered.mockResolvedValue({
      proposals: [{
        id: 'edge-1',
        proposal_type: 'new-edge',
        source_page: 'PageA.md',
        proposed_data: { source: 'Apple', target: 'Fruit', relationship: 'is-a', extractor: 'gemma4-assist' },
        confidence: 0.9,
        reasoning: 'mentioned',
        status: 'pending',
      }],
    });
    render(<ProposalReviewQueue />);
    await screen.findByText('«Apple»');
    expect(screen.getByText('«Fruit»')).toBeInTheDocument();
    expect(screen.getByText('—[is-a]→')).toBeInTheDocument();
    expect(screen.getByText(/extractor: gemma4-assist/)).toBeInTheDocument();
  });

  it('new-node proposal renders + Type «name»', async () => {
    api.knowledge.listProposalsFiltered.mockResolvedValue({
      proposals: [{
        id: 'node-1',
        proposal_type: 'new-node',
        source_page: 'PageB.md',
        proposed_data: { name: 'Bonds', nodeType: 'Concept', extractor: 'gemma4-assist' },
        confidence: 0.8,
        reasoning: 'noun',
        status: 'pending',
      }],
    });
    render(<ProposalReviewQueue />);
    await screen.findByText('«Bonds»');
    expect(screen.getByText('+ Concept')).toBeInTheDocument();
  });

  it('shows Conflict badge when node_exists is true', async () => {
    api.knowledge.listProposalsFiltered.mockResolvedValue({
      proposals: [{
        id: 'node-1',
        proposal_type: 'new-node',
        source_page: 'PageB.md',
        proposed_data: { name: 'Bonds', nodeType: 'Concept' },
        confidence: 0.8,
        reasoning: '',
        status: 'pending',
        node_exists: true,
      }],
    });
    render(<ProposalReviewQueue />);
    await screen.findByText('Conflict');
  });

  it('shows Already rejected badge when edge_previously_rejected is true', async () => {
    api.knowledge.listProposalsFiltered.mockResolvedValue({
      proposals: [{
        id: 'edge-1',
        proposal_type: 'new-edge',
        source_page: 'PageA.md',
        proposed_data: { source: 'A', target: 'B', relationship: 'related' },
        confidence: 0.5,
        reasoning: '',
        status: 'pending',
        edge_previously_rejected: true,
      }],
    });
    render(<ProposalReviewQueue />);
    await screen.findByText('Already rejected');
  });

  it('renders properties as chips when proposed_data.properties has entries', async () => {
    api.knowledge.listProposalsFiltered.mockResolvedValue({
      proposals: [{
        id: 'node-1',
        proposal_type: 'new-node',
        source_page: 'PageB.md',
        proposed_data: { name: 'Bonds', nodeType: 'Concept', properties: { domain: 'finance', risk: 'low' } },
        confidence: 0.8,
        reasoning: '',
        status: 'pending',
      }],
    });
    render(<ProposalReviewQueue />);
    await screen.findByText('domain=finance');
    expect(screen.getByText('risk=low')).toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// "Machine rejected" filter — switches the loadProposals API call so the
// auto-promoted (status='rejected') set actually shows up.
// ---------------------------------------------------------------------------

describe('ProposalReviewQueue — Machine rejected filter', () => {
  it('switching to "Machine rejected" loads the rejected set with the right opts', async () => {
    api.knowledge.listProposalsFiltered.mockResolvedValue({ proposals: PROPOSALS });
    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');

    // First call (initial load) was for pending.
    expect(api.knowledge.listProposalsFiltered).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'pending', includeMachineRejected: true })
    );

    // Switch to "Machine rejected" filter.
    api.knowledge.listProposalsFiltered.mockResolvedValueOnce({
      proposals: [{
        id: 'rej-1',
        proposal_type: 'new-node',
        source_page: 'P.md',
        proposed_data: { name: 'Junk' },
        confidence: 0.6,
        reasoning: '',
        status: 'rejected',
        machine_status: 'rejected',
      }],
    });
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'rejected' } });

    await waitFor(() =>
      expect(api.knowledge.listProposalsFiltered).toHaveBeenCalledWith(
        expect.objectContaining({
          status: 'rejected',
          machineStatus: 'rejected',
          includeMachineRejected: true,
        })
      )
    );
  });

  it('header label says "Machine-Rejected Proposals" when filter is rejected', async () => {
    api.knowledge.listProposalsFiltered.mockResolvedValue({ proposals: PROPOSALS });
    render(<ProposalReviewQueue />);
    await screen.findByText('new-edge');

    expect(screen.getByRole('heading', { name: /Pending Proposals/ })).toBeInTheDocument();

    api.knowledge.listProposalsFiltered.mockResolvedValueOnce({ proposals: [] });
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'rejected' } });

    await screen.findByRole('heading', { name: /Machine-Rejected Proposals/ });
  });
});

// ---------------------------------------------------------------------------
// formatRelativeTime — pure helper
// ---------------------------------------------------------------------------

describe('formatRelativeTime', () => {
  it('returns empty string for null/undefined/invalid input', () => {
    expect(formatRelativeTime(null)).toBe('');
    expect(formatRelativeTime(undefined)).toBe('');
    expect(formatRelativeTime('not-a-date')).toBe('');
  });

  it('returns "just now" within 60 seconds', () => {
    const now = new Date(Date.now() - 30 * 1000).toISOString();
    expect(formatRelativeTime(now)).toBe('just now');
  });

  it('returns Xm ago between 1 and 59 minutes', () => {
    const ts = new Date(Date.now() - 12 * 60 * 1000).toISOString();
    expect(formatRelativeTime(ts)).toBe('12m ago');
  });

  it('returns Xh ago between 1 and 23 hours', () => {
    const ts = new Date(Date.now() - 5 * 3600 * 1000).toISOString();
    expect(formatRelativeTime(ts)).toBe('5h ago');
  });

  it('returns Xd ago between 1 and 29 days', () => {
    const ts = new Date(Date.now() - 3 * 86400 * 1000).toISOString();
    expect(formatRelativeTime(ts)).toBe('3d ago');
  });

  it('falls back to localeDateString for older than 30 days', () => {
    const ts = new Date(Date.now() - 60 * 86400 * 1000).toISOString();
    const formatted = formatRelativeTime(ts);
    expect(formatted).not.toMatch(/ago/);
    expect(formatted.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// MACHINE column — clickable badge + reasoning disclosure
// ---------------------------------------------------------------------------

describe('ProposalReviewQueue — Machine reasoning disclosure', () => {
  it('shows dash with title for not-yet-judged proposals', async () => {
    api.knowledge.listProposalsFiltered.mockResolvedValue({
      proposals: [{
        id: 'p1',
        proposal_type: 'new-node',
        source_page: 'P.md',
        proposed_data: { name: 'X' },
        confidence: 0.5,
        reasoning: '',
        status: 'pending',
        machine_status: null,
      }],
    });
    render(<ProposalReviewQueue />);
    await screen.findByText('«X»');
    const dash = screen.getAllByText('–').find(el => el.tagName === 'SPAN');
    expect(dash).toBeTruthy();
    expect(dash).toHaveAttribute('title', expect.stringMatching(/judge has not evaluated/i));
  });

  it('clicking the verdict badge fetches reviews and shows rationale', async () => {
    api.knowledge.listProposalsFiltered.mockResolvedValue({
      proposals: [{
        id: 'p2',
        proposal_type: 'new-node',
        source_page: 'P.md',
        proposed_data: { name: 'Y' },
        confidence: 0.7,
        reasoning: '',
        status: 'pending',
        machine_status: 'rejected',
      }],
    });
    api.knowledge.listProposalReviews.mockResolvedValue({
      reviews: [{
        id: 'r1', reviewer_kind: 'machine', reviewer_id: 'judge', verdict: 'rejected',
        confidence: 0.85, rationale: 'duplicate of existing node', created: '2026-05-09T10:00:00Z',
      }],
    });

    render(<ProposalReviewQueue />);
    await screen.findByText('«Y»');

    const badge = screen.getByRole('button', { name: /rejected/ });
    expect(badge).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(badge);

    await waitFor(() =>
      expect(api.knowledge.listProposalReviews).toHaveBeenCalledWith('p2')
    );
    await screen.findByText(/duplicate of existing node/);
    expect(badge).toHaveAttribute('aria-expanded', 'true');
  });

  it('shows relative timestamp on the rationale line with full timestamp in tooltip', async () => {
    const now = Date.now();
    const fiveMinAgoIso = new Date(now - 5 * 60 * 1000).toISOString();

    api.knowledge.listProposalsFiltered.mockResolvedValue({
      proposals: [{
        id: 'p-time',
        proposal_type: 'new-node',
        source_page: 'P.md',
        proposed_data: { name: 'Z' },
        confidence: 0.7,
        reasoning: '',
        status: 'pending',
        machine_status: 'rejected',
      }],
    });
    api.knowledge.listProposalReviews.mockResolvedValue({
      reviews: [{
        id: 'r-time', reviewer_kind: 'machine', reviewer_id: 'judge',
        verdict: 'rejected', confidence: 0.9, rationale: 'reason',
        created: fiveMinAgoIso,
      }],
    });

    render(<ProposalReviewQueue />);
    await screen.findByText('«Z»');
    fireEvent.click(screen.getByRole('button', { name: /rejected/ }));

    const ago = await screen.findByText(/5m ago/);
    // Tooltip carries the precise timestamp via toLocaleString().
    expect(ago).toHaveAttribute('title', new Date(fiveMinAgoIso).toLocaleString());
  });

  it('cached reviews — clicking twice does not refetch', async () => {
    api.knowledge.listProposalsFiltered.mockResolvedValue({
      proposals: [{
        id: 'p3',
        proposal_type: 'new-edge',
        source_page: 'P.md',
        proposed_data: { source: 'A', target: 'B', relationship: 'r' },
        confidence: 0.7,
        reasoning: '',
        status: 'pending',
        machine_status: 'approved',
      }],
    });
    api.knowledge.listProposalReviews.mockResolvedValue({
      reviews: [{ id: 'r1', reviewer_kind: 'machine', verdict: 'approved', confidence: 0.9, rationale: 'ok', created: '' }],
    });

    render(<ProposalReviewQueue />);
    await screen.findByText('«A»');

    const badge = screen.getByRole('button', { name: /approved/ });
    fireEvent.click(badge); // expand
    await screen.findByText(/ok/);
    fireEvent.click(badge); // collapse
    fireEvent.click(badge); // expand again

    await waitFor(() => expect(api.knowledge.listProposalReviews).toHaveBeenCalledTimes(1));
  });
});
