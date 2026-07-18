import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import HubDiscoveryTab from './HubDiscoveryTab';

// ---------------------------------------------------------------------------
// Mock api/client. HubDiscoveryTab renders the real HubDiscoveryCard and
// ExistingHubsPanel children, so the mock must cover their knowledge calls too.
// ---------------------------------------------------------------------------

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      listHubDiscoveryProposals: vi.fn(),
      listDismissedHubDiscoveryProposals: vi.fn(),
      runHubDiscovery: vi.fn(),
      acceptHubDiscoveryProposal: vi.fn(),
      dismissHubDiscoveryProposal: vi.fn(),
      deleteDismissedHubDiscoveryProposal: vi.fn(),
      bulkDeleteDismissedHubDiscoveryProposals: vi.fn(),
      listExistingHubs: vi.fn(),
      getHubDrilldown: vi.fn(),
      removeHubMember: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const PROPOSALS = [
  { id: 1, suggestedName: 'Networking', exemplarPage: 'TCP', memberPages: ['TCP', 'UDP', 'IP'], coherenceScore: 0.82 },
  { id: 2, suggestedName: 'Storage', exemplarPage: 'Disk', memberPages: ['Disk', 'SSD'], coherenceScore: 0.75 },
];

const DISMISSED = [
  { id: 9, suggestedName: 'OldHub', exemplarPage: 'X', memberPages: ['X', 'Y'], reviewedBy: 'admin', reviewedAt: '2024-01-01T00:00:00Z' },
];

function renderTab() {
  return render(<MemoryRouter><HubDiscoveryTab /></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  api.knowledge.listHubDiscoveryProposals.mockResolvedValue({ proposals: PROPOSALS, total: 2 });
  api.knowledge.listDismissedHubDiscoveryProposals.mockResolvedValue({ proposals: DISMISSED, total: 1 });
  api.knowledge.runHubDiscovery.mockResolvedValue({
    proposalsCreated: 3, candidatePoolSize: 40, noisePages: 5, skippedDismissed: 1, durationMs: 120,
  });
  api.knowledge.acceptHubDiscoveryProposal.mockResolvedValue({});
  api.knowledge.dismissHubDiscoveryProposal.mockResolvedValue({});
  api.knowledge.deleteDismissedHubDiscoveryProposal.mockResolvedValue({});
  api.knowledge.bulkDeleteDismissedHubDiscoveryProposals.mockResolvedValue({ deleted: 2 });
  api.knowledge.listExistingHubs.mockResolvedValue({ hubs: [] });
  api.knowledge.getHubDrilldown.mockResolvedValue({ members: [], nearMisses: [] });
  api.knowledge.removeHubMember.mockResolvedValue({});
});

// ---------------------------------------------------------------------------
// Initial load
// ---------------------------------------------------------------------------

describe('HubDiscoveryTab — initial load', () => {
  it('loads and renders pending proposals with the pending count', async () => {
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');
    expect(screen.getByTestId('hub-discovery-card-2')).toBeTruthy();
    expect(screen.getByTestId('hub-discovery-count').textContent).toContain('2 pending');
    expect(api.knowledge.listHubDiscoveryProposals).toHaveBeenCalledWith(50, 0);
  });

  it('shows the empty state when there are no pending proposals', async () => {
    api.knowledge.listHubDiscoveryProposals.mockResolvedValueOnce({ proposals: [], total: 0 });
    renderTab();
    await screen.findByTestId('hub-discovery-empty');
    expect(screen.getByTestId('hub-discovery-count').textContent).toContain('0 pending');
  });

  it('shows an error toast when the load fails', async () => {
    api.knowledge.listHubDiscoveryProposals.mockRejectedValueOnce(new Error('load boom'));
    renderTab();
    const toast = await screen.findByTestId('hub-discovery-toast-error');
    expect(toast.textContent).toContain('load boom');
  });
});

// ---------------------------------------------------------------------------
// Run discovery
// ---------------------------------------------------------------------------

describe('HubDiscoveryTab — run discovery', () => {
  it('runs discovery, posts, shows the success toast, and reloads proposals', async () => {
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');

    fireEvent.click(screen.getByTestId('hub-discovery-run'));

    await waitFor(() => expect(api.knowledge.runHubDiscovery).toHaveBeenCalledTimes(1));
    const toast = await screen.findByTestId('hub-discovery-toast-success');
    expect(toast.textContent).toContain('3 proposals from 40 candidates');
    expect(toast.textContent).toContain('1 skipped as previously dismissed');
    // reload of pending proposals
    await waitFor(() => expect(api.knowledge.listHubDiscoveryProposals).toHaveBeenCalledTimes(2));
  });

  it('shows an error toast when run fails', async () => {
    api.knowledge.runHubDiscovery.mockRejectedValueOnce(new Error('run boom'));
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');
    fireEvent.click(screen.getByTestId('hub-discovery-run'));
    const toast = await screen.findByTestId('hub-discovery-toast-error');
    expect(toast.textContent).toContain('run boom');
  });
});

// ---------------------------------------------------------------------------
// Accept / dismiss a proposal (via the real card)
// ---------------------------------------------------------------------------

describe('HubDiscoveryTab — accept proposal', () => {
  it('accepts a proposal and removes its card, decrementing the count', async () => {
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');

    fireEvent.click(screen.getByTestId('hub-discovery-accept-1'));

    await waitFor(() => {
      expect(api.knowledge.acceptHubDiscoveryProposal).toHaveBeenCalledWith(1, 'Networking', ['TCP', 'UDP', 'IP']);
    });
    await waitFor(() => expect(screen.queryByTestId('hub-discovery-card-1')).toBeNull());
    expect(screen.getByTestId('hub-discovery-count').textContent).toContain('1 pending');
  });

  it('surfaces an accept error from the card via the tab toast', async () => {
    api.knowledge.acceptHubDiscoveryProposal.mockRejectedValueOnce(new Error('accept boom'));
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');
    fireEvent.click(screen.getByTestId('hub-discovery-accept-1'));
    const toast = await screen.findByTestId('hub-discovery-toast-error');
    expect(toast.textContent).toContain('accept boom');
  });
});

describe('HubDiscoveryTab — dismiss proposal', () => {
  it('dismisses a proposal, removes the card, and bumps the dismissed count', async () => {
    // The silent background reconcile re-reads the dismissed list, so reflect
    // the newly-dismissed proposal in that response (total now 2).
    api.knowledge.listDismissedHubDiscoveryProposals.mockResolvedValue({
      proposals: [
        ...DISMISSED,
        { id: 2, suggestedName: 'Storage', exemplarPage: 'Disk', memberPages: ['Disk', 'SSD'], reviewedBy: 'admin', reviewedAt: '2024-02-01T00:00:00Z' },
      ],
      total: 2,
    });
    renderTab();
    await screen.findByTestId('hub-discovery-card-2');

    fireEvent.click(screen.getByTestId('hub-discovery-dismiss-2'));

    await waitFor(() => expect(api.knowledge.dismissHubDiscoveryProposal).toHaveBeenCalledWith(2));
    await waitFor(() => expect(screen.queryByTestId('hub-discovery-card-2')).toBeNull());
    // optimistic dismissed-count bump (initial 1 -> 2), confirmed by the reconcile
    await waitFor(() => {
      expect(screen.getByTestId('hub-discovery-dismissed-toggle').textContent).toContain('(2)');
    });
  });
});

// ---------------------------------------------------------------------------
// Dismissed panel
// ---------------------------------------------------------------------------

describe('HubDiscoveryTab — dismissed panel', () => {
  it('expands and lists dismissed proposals on first open', async () => {
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');

    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-toggle'));

    await waitFor(() => expect(api.knowledge.listDismissedHubDiscoveryProposals).toHaveBeenCalledWith(50, 0));
    await screen.findByTestId('hub-discovery-dismissed-row-9');
    expect(screen.getByText('OldHub')).toBeTruthy();
  });

  it('shows the empty state in the dismissed panel', async () => {
    api.knowledge.listDismissedHubDiscoveryProposals.mockResolvedValueOnce({ proposals: [], total: 0 });
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-toggle'));
    await screen.findByTestId('hub-discovery-dismissed-empty');
  });

  it('deletes a single dismissed proposal after confirm', async () => {
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-toggle'));
    await screen.findByTestId('hub-discovery-dismissed-row-9');

    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-delete-9'));
    await screen.findByTestId('hub-discovery-dismissed-confirm-modal');
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-confirm-delete'));

    await waitFor(() => expect(api.knowledge.deleteDismissedHubDiscoveryProposal).toHaveBeenCalledWith(9));
    const toast = await screen.findByTestId('hub-discovery-toast-success');
    expect(toast.textContent).toContain('Dismissed proposal deleted');
  });

  it('cancels a delete without calling the API', async () => {
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-toggle'));
    await screen.findByTestId('hub-discovery-dismissed-row-9');
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-delete-9'));
    await screen.findByTestId('hub-discovery-dismissed-confirm-modal');
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-confirm-cancel'));
    await waitFor(() => expect(screen.queryByTestId('hub-discovery-dismissed-confirm-modal')).toBeNull());
    expect(api.knowledge.deleteDismissedHubDiscoveryProposal).not.toHaveBeenCalled();
  });

  it('bulk-deletes selected dismissed proposals after confirm', async () => {
    api.knowledge.listDismissedHubDiscoveryProposals.mockResolvedValue({
      proposals: [
        { id: 9, suggestedName: 'OldHub', exemplarPage: 'X', memberPages: ['X'], reviewedBy: 'admin', reviewedAt: '2024-01-01T00:00:00Z' },
        { id: 10, suggestedName: 'OlderHub', exemplarPage: 'Z', memberPages: ['Z'], reviewedBy: 'admin', reviewedAt: '2024-01-02T00:00:00Z' },
      ],
      total: 2,
    });
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-toggle'));
    await screen.findByTestId('hub-discovery-dismissed-row-9');

    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-select-9'));
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-select-10'));
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-bulk-delete'));

    await screen.findByTestId('hub-discovery-dismissed-confirm-modal');
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-confirm-delete'));

    await waitFor(() => {
      expect(api.knowledge.bulkDeleteDismissedHubDiscoveryProposals).toHaveBeenCalledWith([9, 10]);
    });
    const toast = await screen.findByTestId('hub-discovery-toast-success');
    expect(toast.textContent).toContain('Deleted 2 dismissed proposal(s)');
  });

  it('select-all toggles every dismissed row', async () => {
    api.knowledge.listDismissedHubDiscoveryProposals.mockResolvedValue({
      proposals: [
        { id: 9, suggestedName: 'A', exemplarPage: 'X', memberPages: ['X'], reviewedBy: 'admin', reviewedAt: '2024-01-01T00:00:00Z' },
        { id: 10, suggestedName: 'B', exemplarPage: 'Z', memberPages: ['Z'], reviewedBy: 'admin', reviewedAt: '2024-01-02T00:00:00Z' },
      ],
      total: 2,
    });
    renderTab();
    await screen.findByTestId('hub-discovery-card-1');
    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-toggle'));
    await screen.findByTestId('hub-discovery-dismissed-row-9');

    fireEvent.click(screen.getByTestId('hub-discovery-dismissed-select-all'));
    expect(screen.getByTestId('hub-discovery-dismissed-bulk-delete').textContent).toContain('(2)');
  });
});
