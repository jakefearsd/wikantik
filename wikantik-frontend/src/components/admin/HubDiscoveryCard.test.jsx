import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import HubDiscoveryCard from './HubDiscoveryCard';

vi.mock('../../api/client', () => ({
  api: {
    knowledge: {
      acceptHubDiscoveryProposal: vi.fn(),
      dismissHubDiscoveryProposal: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const PROPOSAL = {
  id: 'prop-1',
  suggestedName: 'Crypto Concepts',
  exemplarPage: 'Bitcoin',
  coherenceScore: 0.77,
  memberPages: ['Bitcoin', 'Ethereum', 'Blockchain'],
};

beforeEach(() => {
  vi.clearAllMocks();
  api.knowledge.acceptHubDiscoveryProposal.mockResolvedValue({});
  api.knowledge.dismissHubDiscoveryProposal.mockResolvedValue({});
});

const renderCard = (props = {}) =>
  render(
    <HubDiscoveryCard
      proposal={PROPOSAL}
      onAccepted={props.onAccepted ?? vi.fn()}
      onDismissed={props.onDismissed ?? vi.fn()}
      onError={props.onError ?? vi.fn()}
    />
  );

describe('HubDiscoveryCard — render', () => {
  it('shows the editable name prefilled with the suggested name and the coherence', () => {
    renderCard();
    const input = screen.getByTestId('hub-discovery-name-prop-1');
    expect(input.value).toBe('Crypto Concepts');
    expect(screen.getByText('coherence: 0.77')).toBeTruthy();
  });

  it('lists all member pages with checked checkboxes by default', () => {
    renderCard();
    PROPOSAL.memberPages.forEach((m) => {
      const cb = screen.getByTestId(`hub-discovery-member-prop-1-${m}`);
      expect(cb.checked).toBe(true);
    });
  });
});

describe('HubDiscoveryCard — accept flow', () => {
  it('accepts with the edited name and currently-checked members', async () => {
    const onAccepted = vi.fn();
    renderCard({ onAccepted });

    // Edit the name
    const input = screen.getByTestId('hub-discovery-name-prop-1');
    fireEvent.change(input, { target: { value: 'Renamed Hub' } });

    // Uncheck one member
    fireEvent.click(screen.getByTestId('hub-discovery-member-prop-1-Blockchain'));

    fireEvent.click(screen.getByTestId('hub-discovery-accept-prop-1'));

    await waitFor(() =>
      expect(api.knowledge.acceptHubDiscoveryProposal).toHaveBeenCalledWith(
        'prop-1',
        'Renamed Hub',
        ['Bitcoin', 'Ethereum']
      )
    );
    await waitFor(() => expect(onAccepted).toHaveBeenCalledWith('prop-1'));
  });

  it('blocks accept with empty name and reports via onError without calling the API', async () => {
    const onError = vi.fn();
    renderCard({ onError });
    fireEvent.change(screen.getByTestId('hub-discovery-name-prop-1'), { target: { value: '   ' } });
    fireEvent.click(screen.getByTestId('hub-discovery-accept-prop-1'));
    expect(onError).toHaveBeenCalledWith('Hub name must not be empty');
    expect(api.knowledge.acceptHubDiscoveryProposal).not.toHaveBeenCalled();
  });

  it('blocks accept when fewer than 2 members are selected', async () => {
    const onError = vi.fn();
    renderCard({ onError });
    // Uncheck two of three → only one left
    fireEvent.click(screen.getByTestId('hub-discovery-member-prop-1-Ethereum'));
    fireEvent.click(screen.getByTestId('hub-discovery-member-prop-1-Blockchain'));
    fireEvent.click(screen.getByTestId('hub-discovery-accept-prop-1'));
    expect(onError).toHaveBeenCalledWith('Select at least 2 members');
    expect(api.knowledge.acceptHubDiscoveryProposal).not.toHaveBeenCalled();
  });

  it('surfaces a name-collision/accept error inline via onError (prefers err.body.message)', async () => {
    const onError = vi.fn();
    const onAccepted = vi.fn();
    api.knowledge.acceptHubDiscoveryProposal.mockRejectedValue({
      body: { message: 'A hub named Crypto Concepts already exists' },
      message: 'HTTP 409',
    });
    renderCard({ onError, onAccepted });
    fireEvent.click(screen.getByTestId('hub-discovery-accept-prop-1'));
    await waitFor(() =>
      expect(onError).toHaveBeenCalledWith('A hub named Crypto Concepts already exists')
    );
    expect(onAccepted).not.toHaveBeenCalled();
    // Accept button is re-enabled after failure (busy reset).
    expect(screen.getByTestId('hub-discovery-accept-prop-1').disabled).toBe(false);
  });
});

describe('HubDiscoveryCard — dismiss flow', () => {
  it('dismisses the proposal and notifies the parent', async () => {
    const onDismissed = vi.fn();
    renderCard({ onDismissed });
    fireEvent.click(screen.getByTestId('hub-discovery-dismiss-prop-1'));
    await waitFor(() =>
      expect(api.knowledge.dismissHubDiscoveryProposal).toHaveBeenCalledWith('prop-1')
    );
    await waitFor(() => expect(onDismissed).toHaveBeenCalledWith(PROPOSAL));
  });

  it('reports a dismiss failure via onError and re-enables the button', async () => {
    const onError = vi.fn();
    api.knowledge.dismissHubDiscoveryProposal.mockRejectedValue(new Error('dismiss failed'));
    renderCard({ onError });
    fireEvent.click(screen.getByTestId('hub-discovery-dismiss-prop-1'));
    await waitFor(() => expect(onError).toHaveBeenCalledWith('dismiss failed'));
    expect(screen.getByTestId('hub-discovery-dismiss-prop-1').disabled).toBe(false);
  });
});
