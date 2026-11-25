import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ReaderEngagementPanel from './ReaderEngagementPanel';

vi.mock('../../api/client', () => ({
  api: { admin: { getInsightsReaders: vi.fn() } },
}));

import { api } from '../../api/client';

const PAGE = {
  canonical_id: 'ID1', slug: 'Alpha', views: 120, visitors: 90,
  typical_active_s: 38, scroll75_rate: 0.42, actions_per_view: 0.3,
  landings: { 'organic-search': 60, direct: 20 }, continuation_rate: 0.35,
  returning_this_month_rate: 0.1, lcp_p75_ms: 1800, inp_p75_ms: null, cls_p75: 0.02,
};

beforeEach(() => vi.clearAllMocks());

describe('ReaderEngagementPanel', () => {
  it('renders the returning-this-month and typical-active-time columns with values', async () => {
    api.admin.getInsightsReaders.mockResolvedValue({
      pages: [PAGE], coverage: { site: { google: 0.3 }, pages: [] }, refreshedAt: '2026-11-24T05:00:00Z',
    });
    render(<ReaderEngagementPanel />);
    expect(await screen.findByText('Returning this month')).toBeInTheDocument();
    expect(screen.getByText('Typical active time')).toBeInTheDocument();
    const row = screen.getByTestId('reader-row-ID1');
    expect(row).toHaveTextContent('Alpha');
    expect(row).toHaveTextContent('38 s');
    expect(row).toHaveTextContent('10%');
    expect(row).toHaveTextContent('organic-search 60');
    expect(screen.getByTestId('reader-coverage')).toHaveTextContent('google 30%');
    expect(api.admin.getInsightsReaders).toHaveBeenCalledWith(28);
  });

  it('shows a dash for null measures, never zero', async () => {
    api.admin.getInsightsReaders.mockResolvedValue({
      pages: [{ ...PAGE, typical_active_s: null, returning_this_month_rate: null }],
      coverage: { site: {}, pages: [] }, refreshedAt: null,
    });
    render(<ReaderEngagementPanel />);
    const row = await screen.findByTestId('reader-row-ID1');
    expect(row).not.toHaveTextContent('0 s');
    expect(row).toHaveTextContent('—');
  });

  it('shows the empty state when there are no pages', async () => {
    api.admin.getInsightsReaders.mockResolvedValue({ pages: [], coverage: { site: {}, pages: [] }, refreshedAt: null });
    render(<ReaderEngagementPanel />);
    expect(await screen.findByText('No reader data yet — analytics off or not yet read back')).toBeInTheDocument();
  });

  it('treats a 503 as the empty state, not an error', async () => {
    api.admin.getInsightsReaders.mockRejectedValue(Object.assign(new Error('off'), { status: 503 }));
    render(<ReaderEngagementPanel />);
    expect(await screen.findByText('No reader data yet — analytics off or not yet read back')).toBeInTheDocument();
  });
});
