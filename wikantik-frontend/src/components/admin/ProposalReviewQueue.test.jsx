import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ProposalReviewQueue from './ProposalReviewQueue';

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
