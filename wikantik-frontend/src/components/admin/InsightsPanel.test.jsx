import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import InsightsPanel from './InsightsPanel';

vi.mock('../../api/client', () => ({
  api: {
    admin: {
      getInsightsAcquisition: vi.fn(),
      getInsightsBacklog: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const ACQUISITION = {
  site: 'wiki.wikantik.com',
  snapshotDate: '2026-08-14',
  engines: [
    { engine: 'google', clicks: 8, impressions: 2518, ctr: 0.00318, position: 36.0 },
    { engine: 'bing', clicks: 3, impressions: 640, ctr: 0.00469, position: 4.7 },
    { engine: 'yandex', clicks: 1, impressions: 50, ctr: 0.02, position: null },
  ],
  trend: [
    { snapshotDate: '2026-06-04', engine: 'google', clicks: 7, impressions: 2401 },
    { snapshotDate: '2026-08-14', engine: 'google', clicks: 8, impressions: 2518 },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
  // BacklogPanel is now rendered inside InsightsPanel and fetches independently — give it
  // a benign default so the pre-existing acquisition tests below aren't affected.
  api.admin.getInsightsBacklog.mockResolvedValue({
    site: 'wiki.wikantik.com',
    generatedAt: '2026-08-14',
    count: 0,
    opportunities: [],
    suppressed: [],
    uncalibratedTypes: [],
  });
});

describe('InsightsPanel', () => {
  it('renders per-engine clicks, impressions, CTR, and position VALUES from the response', async () => {
    api.admin.getInsightsAcquisition.mockResolvedValue(ACQUISITION);
    render(<InsightsPanel />);

    // Assert actual rendered VALUES, not just that the cell exists — the endpoint's numbers
    // must reach the DOM unmodified (this is what the two prior sessions' bugs both missed).
    expect(await screen.findByTestId('clicks-google')).toHaveTextContent('8');
    expect(screen.getByTestId('impressions-google')).toHaveTextContent('2518');
    expect(screen.getByTestId('clicks-bing')).toHaveTextContent('3');
    expect(screen.getByTestId('position-bing')).toHaveTextContent('4.7');
    expect(screen.getByTestId('insights-snapshot-date')).toHaveTextContent('2026-08-14');
  });

  it('renders a NULL position as an em dash, never as 0', async () => {
    api.admin.getInsightsAcquisition.mockResolvedValue(ACQUISITION);
    render(<InsightsPanel />);

    const yandexPosition = await screen.findByTestId('position-yandex');
    expect(yandexPosition).toHaveTextContent('—');
    expect(yandexPosition).not.toHaveTextContent('0');
  });

  it('omits an engine entirely when the API omits it (e.g. Yandex with no page rows)', async () => {
    api.admin.getInsightsAcquisition.mockResolvedValue({
      ...ACQUISITION,
      engines: ACQUISITION.engines.filter((e) => e.engine !== 'yandex'),
    });
    render(<InsightsPanel />);

    expect(await screen.findByTestId('clicks-google')).toBeInTheDocument();
    expect(screen.queryByTestId('clicks-yandex')).not.toBeInTheDocument();
    expect(screen.queryByTestId('position-yandex')).not.toBeInTheDocument();
  });

  it('shows the empty state when there is no snapshot yet', async () => {
    api.admin.getInsightsAcquisition.mockResolvedValue({
      site: 'wiki.wikantik.com', snapshotDate: null, engines: [], trend: [],
    });
    render(<InsightsPanel />);

    expect(await screen.findByTestId('insights-empty-state')).toBeInTheDocument();
  });

  it('surfaces a fetch error via the shared AdminPage error banner', async () => {
    api.admin.getInsightsAcquisition.mockRejectedValue(new Error('boom'));
    render(<InsightsPanel />);

    expect(await screen.findByText('boom')).toBeInTheDocument();
  });
});
