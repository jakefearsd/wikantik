import { render, screen, within, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import BacklogPanel from './BacklogPanel';

vi.mock('../../api/client', () => ({
  api: {
    admin: {
      getInsightsBacklog: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const OPPORTUNITY = {
  type: 'agent_gap',
  target: 'how do I deploy locally',
  priority: 6.0,
  calibrated: false,
  suggestedAction: 'Curate the Knowledge Graph relations for this topic.',
  firstSeen: '2026-08-02',
  evidence: { occurrences: 3, distinctSessions: 2 },
};

const SUPPRESSED = {
  type: 'engine_divergence',
  reason: 'traffic_gate',
  measured: 2626.0,
  required: 5000.0,
};

const BASE_RESPONSE = {
  site: 'wiki.wikantik.com',
  generatedAt: '2026-08-17',
  count: 1,
  opportunities: [OPPORTUNITY],
  suppressed: [],
  uncalibratedTypes: ['agent_gap', 'engine_divergence'],
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('BacklogPanel', () => {
  it('renders opportunities from a mocked fetch', async () => {
    api.admin.getInsightsBacklog.mockResolvedValue(BASE_RESPONSE);
    render(<BacklogPanel />);

    expect(await screen.findByText('how do I deploy locally')).toBeInTheDocument();
    expect(screen.getByText('agent_gap')).toBeInTheDocument();
    expect(screen.getByText('6.00')).toBeInTheDocument();
    expect(screen.getByText('2026-08-02')).toBeInTheDocument();
    expect(
      screen.getByText('Curate the Knowledge Graph relations for this topic.'),
    ).toBeInTheDocument();
  });

  // The highest-value test in this file: an empty opportunities table can mean two
  // completely different things — "nothing to do" or "not enough traffic to tell" — and
  // the panel must never collapse them into the same message.
  it('renders the gated-off explanation (not a bare "no opportunities" message) when opportunities is empty but suppressed is not', async () => {
    api.admin.getInsightsBacklog.mockResolvedValue({
      ...BASE_RESPONSE,
      opportunities: [],
      suppressed: [SUPPRESSED],
    });
    render(<BacklogPanel />);

    const callout = await screen.findByTestId('backlog-suppressed-callout');
    expect(within(callout).getByText(/engine_divergence/)).toBeInTheDocument();
    expect(within(callout).getByText(/2,626/)).toBeInTheDocument();
    expect(within(callout).getByText(/5,000/)).toBeInTheDocument();

    const emptyState = screen.getByTestId('backlog-empty-state');
    expect(emptyState).toHaveTextContent(/gated off for lack of traffic/i);
    expect(emptyState).not.toHaveTextContent('No opportunities found.');
  });

  it('renders the genuine empty state when both opportunities and suppressed are empty', async () => {
    api.admin.getInsightsBacklog.mockResolvedValue({
      ...BASE_RESPONSE,
      opportunities: [],
      suppressed: [],
    });
    render(<BacklogPanel />);

    const emptyState = await screen.findByTestId('backlog-empty-state');
    expect(emptyState).toHaveTextContent('No opportunities found.');
    expect(screen.queryByTestId('backlog-suppressed-callout')).not.toBeInTheDocument();
  });

  it('shows the uncalibrated marker for calibrated: false and not for calibrated: true', async () => {
    api.admin.getInsightsBacklog.mockResolvedValue({
      ...BASE_RESPONSE,
      opportunities: [
        { ...OPPORTUNITY, calibrated: false },
        { ...OPPORTUNITY, type: 'stale_high_traffic', target: 'other page', calibrated: true },
      ],
    });
    render(<BacklogPanel />);

    expect(await screen.findByTestId('uncalibrated-0')).toBeInTheDocument();
    expect(screen.queryByTestId('uncalibrated-1')).not.toBeInTheDocument();
  });

  it('renders arbitrary evidence keys generically, without hardcoding field names', async () => {
    api.admin.getInsightsBacklog.mockResolvedValue({
      ...BASE_RESPONSE,
      opportunities: [
        {
          ...OPPORTUNITY,
          evidence: { totallyCustomKey: 42, anotherOddball: 'yep' },
        },
      ],
    });
    render(<BacklogPanel />);

    const details = await screen.findByTestId('evidence-0');
    fireEvent.click(within(details).getByText('Evidence'));

    expect(within(details).getByText(/totallyCustomKey/)).toBeInTheDocument();
    expect(within(details).getByText(/42/)).toBeInTheDocument();
    expect(within(details).getByText(/anotherOddball/)).toBeInTheDocument();
    expect(within(details).getByText(/yep/)).toBeInTheDocument();
  });

  it('renders a clear "not configured" message on a 503, not a generic failure', async () => {
    api.admin.getInsightsBacklog.mockRejectedValue(
      Object.assign(new Error('Service Unavailable'), { status: 503 }),
    );
    render(<BacklogPanel />);

    expect(await screen.findByTestId('backlog-not-configured')).toBeInTheDocument();
    expect(screen.queryByText('Service Unavailable')).not.toBeInTheDocument();
  });

  it('surfaces a non-503 fetch error via the shared AdminPage error banner', async () => {
    api.admin.getInsightsBacklog.mockRejectedValue(new Error('boom'));
    render(<BacklogPanel />);

    expect(await screen.findByText('boom')).toBeInTheDocument();
  });

  it('wires the type filter and include-snoozed toggle to query params on refetch', async () => {
    api.admin.getInsightsBacklog.mockResolvedValue(BASE_RESPONSE);
    render(<BacklogPanel />);
    await screen.findByText('how do I deploy locally');

    expect(api.admin.getInsightsBacklog).toHaveBeenCalledWith(
      expect.objectContaining({ type: undefined, includeSnoozed: false }),
    );

    fireEvent.change(screen.getByTestId('backlog-type-filter'), {
      target: { value: 'engine_divergence' },
    });
    fireEvent.click(screen.getByTestId('backlog-include-snoozed'));

    expect(api.admin.getInsightsBacklog).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: 'engine_divergence', includeSnoozed: true }),
    );
  });
});
