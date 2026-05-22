import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ExistingHubsPanel from './ExistingHubsPanel';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      listExistingHubs: vi.fn(),
      getHubDrilldown: vi.fn(),
      removeHubMember: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const HUBS = [
  { name: 'AlphaHub', memberCount: 4, inboundLinkCount: 7, nearMissCount: 1, hasBackingPage: true },
  { name: 'BetaHub', memberCount: 2, inboundLinkCount: 0, nearMissCount: 0, hasBackingPage: false },
];

const DRILLDOWN_ALPHA = {
  name: 'AlphaHub',
  coherence: 0.83,
  hasBackingPage: true,
  members: [
    { name: 'M1', cosineToCentroid: 0.9 },
    { name: 'M2', cosineToCentroid: 0.8 },
    { name: 'M3', cosineToCentroid: 0.7 },
  ],
};

const DRILLDOWN_BETA = {
  name: 'BetaHub',
  coherence: 0.5,
  hasBackingPage: false,
  members: [
    { name: 'B1', cosineToCentroid: 0.6 },
    { name: 'B2', cosineToCentroid: 0.55 },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
  api.knowledge.listExistingHubs.mockResolvedValue({ hubs: HUBS });
  api.knowledge.getHubDrilldown.mockResolvedValue(DRILLDOWN_ALPHA);
  api.knowledge.removeHubMember.mockResolvedValue({});
});

const expand = async () => {
  fireEvent.click(screen.getByTestId('existing-hubs-toggle'));
  await screen.findByTestId('existing-hubs-panel');
};

describe('ExistingHubsPanel — list and lazy load', () => {
  it('does not fetch until expanded, then lists hubs', async () => {
    render(<ExistingHubsPanel onError={vi.fn()} />);
    expect(api.knowledge.listExistingHubs).not.toHaveBeenCalled();

    await expand();
    await screen.findByText('AlphaHub');
    expect(api.knowledge.listExistingHubs).toHaveBeenCalledTimes(1);
    expect(screen.getByText('BetaHub')).toBeTruthy();
    // Orphan badge for the hub with no backing page
    expect(screen.getByText('orphan')).toBeTruthy();
  });

  it('shows empty message when there are no hubs', async () => {
    api.knowledge.listExistingHubs.mockResolvedValue({ hubs: [] });
    render(<ExistingHubsPanel onError={vi.fn()} />);
    await expand();
    await screen.findByText(/No hubs exist yet/);
  });

  it('invokes onError when the list load fails', async () => {
    const onError = vi.fn();
    api.knowledge.listExistingHubs.mockRejectedValue(new Error('list down'));
    render(<ExistingHubsPanel onError={onError} />);
    fireEvent.click(screen.getByTestId('existing-hubs-toggle'));
    await waitFor(() => expect(onError).toHaveBeenCalledWith('list down'));
  });
});

describe('ExistingHubsPanel — drilldown', () => {
  it('expanding a hub row fetches and renders its drilldown', async () => {
    render(<ExistingHubsPanel onError={vi.fn()} />);
    await expand();
    await screen.findByText('AlphaHub');

    fireEvent.click(screen.getByTestId('existing-hub-row-AlphaHub'));

    await screen.findByTestId('existing-hub-drilldown-AlphaHub');
    expect(api.knowledge.getHubDrilldown).toHaveBeenCalledWith('AlphaHub');
    expect(screen.getByText('M1')).toBeTruthy();
  });

  it('surfaces drilldown load failure via onError', async () => {
    const onError = vi.fn();
    api.knowledge.getHubDrilldown.mockRejectedValue(new Error('drill fail'));
    render(<ExistingHubsPanel onError={onError} />);
    await expand();
    await screen.findByText('AlphaHub');
    fireEvent.click(screen.getByTestId('existing-hub-row-AlphaHub'));
    await waitFor(() => expect(onError).toHaveBeenCalledWith('drill fail'));
  });
});

describe('ExistingHubsPanel — remove member', () => {
  it('confirm flow removes a member: API call + optimistic UI drop', async () => {
    // Initial drilldown has 3 members; the background reconcile fetch after
    // removal returns the reduced (M1 dropped) set so it doesn't restore M1.
    const reduced = { ...DRILLDOWN_ALPHA, members: DRILLDOWN_ALPHA.members.filter((m) => m.name !== 'M1') };
    api.knowledge.getHubDrilldown
      .mockResolvedValueOnce(DRILLDOWN_ALPHA)
      .mockResolvedValue(reduced);

    render(<ExistingHubsPanel onError={vi.fn()} />);
    await expand();
    await screen.findByText('AlphaHub');
    fireEvent.click(screen.getByTestId('existing-hub-row-AlphaHub'));
    await screen.findByTestId('existing-hub-drilldown-AlphaHub');

    // Click remove on member M1 (3 members → removal allowed)
    fireEvent.click(screen.getByTestId('existing-hub-member-remove-AlphaHub-M1'));

    // Confirmation modal appears
    const modal = await screen.findByTestId('existing-hub-member-remove-confirm-modal');
    expect(within(modal).getByText('M1')).toBeTruthy();

    fireEvent.click(screen.getByTestId('existing-hub-member-remove-confirm-ok'));

    await waitFor(() =>
      expect(api.knowledge.removeHubMember).toHaveBeenCalledWith('AlphaHub', 'M1')
    );
    // Optimistic removal drops the row from the drilldown
    await waitFor(() =>
      expect(screen.queryByTestId('existing-hub-member-AlphaHub-M1')).toBeNull()
    );
    // Background reconcile re-fetches list + drilldown
    await waitFor(() => expect(api.knowledge.listExistingHubs).toHaveBeenCalledTimes(2));
  });

  it('cancelling the confirm modal does not call the API', async () => {
    render(<ExistingHubsPanel onError={vi.fn()} />);
    await expand();
    await screen.findByText('AlphaHub');
    fireEvent.click(screen.getByTestId('existing-hub-row-AlphaHub'));
    await screen.findByTestId('existing-hub-drilldown-AlphaHub');

    fireEvent.click(screen.getByTestId('existing-hub-member-remove-AlphaHub-M1'));
    await screen.findByTestId('existing-hub-member-remove-confirm-modal');
    fireEvent.click(screen.getByTestId('existing-hub-member-remove-confirm-cancel'));

    await waitFor(() =>
      expect(screen.queryByTestId('existing-hub-member-remove-confirm-modal')).toBeNull()
    );
    expect(api.knowledge.removeHubMember).not.toHaveBeenCalled();
  });

  it('reports an error via onError when remove fails', async () => {
    const onError = vi.fn();
    api.knowledge.removeHubMember.mockRejectedValue(new Error('remove fail'));
    render(<ExistingHubsPanel onError={onError} />);
    await expand();
    await screen.findByText('AlphaHub');
    fireEvent.click(screen.getByTestId('existing-hub-row-AlphaHub'));
    await screen.findByTestId('existing-hub-drilldown-AlphaHub');
    fireEvent.click(screen.getByTestId('existing-hub-member-remove-AlphaHub-M1'));
    await screen.findByTestId('existing-hub-member-remove-confirm-modal');
    fireEvent.click(screen.getByTestId('existing-hub-member-remove-confirm-ok'));
    await waitFor(() => expect(onError).toHaveBeenCalledWith('remove fail'));
  });

  it('two-member guard: remove buttons are disabled at the minimum', async () => {
    api.knowledge.getHubDrilldown.mockResolvedValue(DRILLDOWN_BETA);
    render(<ExistingHubsPanel onError={vi.fn()} />);
    await expand();
    await screen.findByText('BetaHub');
    fireEvent.click(screen.getByTestId('existing-hub-row-BetaHub'));
    await screen.findByTestId('existing-hub-drilldown-BetaHub');

    const removeBtn = screen.getByTestId('existing-hub-member-remove-BetaHub-B1');
    expect(removeBtn.disabled).toBe(true);
    // Clicking a disabled button must not request removal.
    fireEvent.click(removeBtn);
    expect(screen.queryByTestId('existing-hub-member-remove-confirm-modal')).toBeNull();
  });
});
