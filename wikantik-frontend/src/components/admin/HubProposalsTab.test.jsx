import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import HubProposalsTab from './HubProposalsTab';

// ---------------------------------------------------------------------------
// Mock api/client. HubProposalsTab uses the knowledge surface for hub
// membership proposals (the percentile-driven, per-page-membership variety).
// ---------------------------------------------------------------------------

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      listHubProposals: vi.fn(),
      generateHubProposals: vi.fn(),
      syncHubMemberships: vi.fn(),
      approveHubProposal: vi.fn(),
      rejectHubProposal: vi.fn(),
      bulkApproveHubProposals: vi.fn(),
      bulkRejectHubProposals: vi.fn(),
      thresholdApproveHubProposals: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const PROPOSALS = [
  { id: 1, hub_name: 'Networking', page_name: 'TCP', percentile_score: 98.4, raw_similarity: 0.91, created: '2024-01-01T00:00:00Z' },
  { id: 2, hub_name: 'Networking', page_name: 'UDP', percentile_score: 60.0, raw_similarity: 0.55, created: '2024-01-02T00:00:00Z' },
];

beforeEach(() => {
  vi.clearAllMocks();
  api.knowledge.listHubProposals.mockResolvedValue({ proposals: PROPOSALS, total: 2 });
  api.knowledge.generateHubProposals.mockResolvedValue({});
  api.knowledge.syncHubMemberships.mockResolvedValue({});
  api.knowledge.approveHubProposal.mockResolvedValue({});
  api.knowledge.rejectHubProposal.mockResolvedValue({});
  api.knowledge.bulkApproveHubProposals.mockResolvedValue({});
  api.knowledge.bulkRejectHubProposals.mockResolvedValue({});
  api.knowledge.thresholdApproveHubProposals.mockResolvedValue({});
});

afterEach(() => {
  vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// Initial load
// ---------------------------------------------------------------------------

describe('HubProposalsTab — load', () => {
  it('shows a loading state then renders the proposals table', async () => {
    let resolve;
    api.knowledge.listHubProposals.mockReturnValueOnce(new Promise(r => { resolve = r; }));
    render(<HubProposalsTab />);
    expect(screen.getByText('Loading hub proposals...')).toBeTruthy();
    resolve({ proposals: PROPOSALS, total: 2 });
    await screen.findByText('TCP');
  });

  it('renders proposal rows with formatted percentile and similarity', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    expect(screen.getByText('UDP')).toBeTruthy();
    expect(screen.getByText('98.4%')).toBeTruthy();   // percentile
    expect(screen.getByText('91.0%')).toBeTruthy();   // raw_similarity * 100
    expect(screen.getByText((_, el) => el?.textContent === 'Pending: 2')).toBeTruthy();
    expect(api.knowledge.listHubProposals).toHaveBeenCalledWith('pending', null, 50, 0);
  });

  it('shows an empty message when there are no proposals', async () => {
    api.knowledge.listHubProposals.mockResolvedValueOnce({ proposals: [], total: 0 });
    render(<HubProposalsTab />);
    await screen.findByText('No pending proposals.');
  });

  it('shows the error banner on load failure', async () => {
    api.knowledge.listHubProposals.mockRejectedValueOnce(new Error('load boom'));
    render(<HubProposalsTab />);
    await screen.findByText('load boom');
  });
});

// ---------------------------------------------------------------------------
// Generate / sync
// ---------------------------------------------------------------------------

describe('HubProposalsTab — generate & sync', () => {
  it('generates proposals then reloads', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    fireEvent.click(screen.getByRole('button', { name: 'Generate Hub Proposals' }));
    await waitFor(() => expect(api.knowledge.generateHubProposals).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(api.knowledge.listHubProposals).toHaveBeenCalledTimes(2));
  });

  it('shows an error when generate fails', async () => {
    api.knowledge.generateHubProposals.mockRejectedValueOnce(new Error('gen boom'));
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    fireEvent.click(screen.getByRole('button', { name: 'Generate Hub Proposals' }));
    await screen.findByText('gen boom');
  });

  it('runs sync hub memberships', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    fireEvent.click(screen.getByRole('button', { name: 'Sync Hub Memberships' }));
    await waitFor(() => expect(api.knowledge.syncHubMemberships).toHaveBeenCalledTimes(1));
  });

  it('shows an error when sync fails', async () => {
    api.knowledge.syncHubMemberships.mockRejectedValueOnce(new Error('sync boom'));
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    fireEvent.click(screen.getByRole('button', { name: 'Sync Hub Memberships' }));
    await screen.findByText('sync boom');
  });
});

// ---------------------------------------------------------------------------
// Per-row approve / reject
// ---------------------------------------------------------------------------

describe('HubProposalsTab — per-row actions', () => {
  it('approves a single proposal via the checkmark button', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    const row = screen.getByText('TCP').closest('tr');
    fireEvent.click(within(row).getByTitle('Approve'));
    await waitFor(() => expect(api.knowledge.approveHubProposal).toHaveBeenCalledWith(1));
    await waitFor(() => expect(api.knowledge.listHubProposals).toHaveBeenCalledTimes(2));
  });

  it('rejects a single proposal using the prompt reason', async () => {
    vi.spyOn(window, 'prompt').mockReturnValue('not relevant');
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    const row = screen.getByText('TCP').closest('tr');
    fireEvent.click(within(row).getByTitle('Reject'));
    await waitFor(() => expect(api.knowledge.rejectHubProposal).toHaveBeenCalledWith(1, 'not relevant'));
  });

  it('does not reject when the prompt is cancelled', async () => {
    vi.spyOn(window, 'prompt').mockReturnValue(null);
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    const row = screen.getByText('TCP').closest('tr');
    fireEvent.click(within(row).getByTitle('Reject'));
    await waitFor(() => {});
    expect(api.knowledge.rejectHubProposal).not.toHaveBeenCalled();
  });

  it('surfaces an approve error', async () => {
    api.knowledge.approveHubProposal.mockRejectedValueOnce(new Error('approve boom'));
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    const row = screen.getByText('TCP').closest('tr');
    fireEvent.click(within(row).getByTitle('Approve'));
    await screen.findByText('approve boom');
  });
});

// ---------------------------------------------------------------------------
// Bulk approve / reject
// ---------------------------------------------------------------------------

describe('HubProposalsTab — bulk actions', () => {
  it('bulk-approves the selected proposals', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');

    const boxes = screen.getAllByRole('checkbox');
    // index 0 is the header select-all
    fireEvent.click(boxes[1]);
    fireEvent.click(boxes[2]);

    fireEvent.click(screen.getByRole('button', { name: /Approve Selected/ }));
    await waitFor(() => expect(api.knowledge.bulkApproveHubProposals).toHaveBeenCalledWith([1, 2]));
    await waitFor(() => expect(api.knowledge.listHubProposals).toHaveBeenCalledTimes(2));
  });

  it('bulk-approve is disabled with no selection', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    expect(screen.getByRole('button', { name: /Approve Selected \(0\)/ }).disabled).toBe(true);
  });

  it('opens the reject modal and bulk-rejects with a reason', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');

    const boxes = screen.getAllByRole('checkbox');
    fireEvent.click(boxes[1]);

    fireEvent.click(screen.getByRole('button', { name: /Reject Selected/ }));
    const reasonLabel = await screen.findByText('Rejection reason (optional):');
    const reasonInput = reasonLabel.parentElement.querySelector('input[type="text"]');
    fireEvent.change(reasonInput, { target: { value: 'spam' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Reject' }));

    await waitFor(() => expect(api.knowledge.bulkRejectHubProposals).toHaveBeenCalledWith([1], 'spam'));
    // modal closes
    await waitFor(() => expect(screen.queryByText('Confirm Reject')).toBeNull());
  });

  it('select-all selects every proposal', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    const boxes = screen.getAllByRole('checkbox');
    fireEvent.click(boxes[0]);
    expect(screen.getByRole('button', { name: /Approve Selected \(2\)/ })).toBeTruthy();
  });
});

// ---------------------------------------------------------------------------
// Threshold approve
// ---------------------------------------------------------------------------

describe('HubProposalsTab — threshold approve', () => {
  it('counts matches above the threshold and applies threshold-approve', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');

    // Default threshold 95 -> only the 98.4 proposal matches => 1 match
    expect(screen.getByText((_, el) => el?.textContent === '% (1 match)')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(api.knowledge.thresholdApproveHubProposals).toHaveBeenCalledWith(95));
    await waitFor(() => expect(api.knowledge.listHubProposals).toHaveBeenCalledTimes(2));
  });

  it('disables Apply when no proposals meet the threshold', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    const thresholdInput = screen.getByDisplayValue('95');
    fireEvent.change(thresholdInput, { target: { value: '99' } });
    await waitFor(() => {
      expect(screen.getByText((_, el) => el?.textContent === '% (0 match)')).toBeTruthy();
    });
    expect(screen.getByRole('button', { name: 'Apply' }).disabled).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Hub filter
// ---------------------------------------------------------------------------

describe('HubProposalsTab — filter', () => {
  it('reloads with the hub filter applied', async () => {
    render(<HubProposalsTab />);
    await screen.findByText('TCP');
    fireEvent.change(screen.getByPlaceholderText('Filter by Hub name...'), { target: { value: 'Networking' } });
    await waitFor(() => {
      expect(api.knowledge.listHubProposals).toHaveBeenCalledWith('pending', 'Networking', 50, 0);
    });
  });
});
