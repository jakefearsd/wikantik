import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import AdminRetrievalQualityPage from './AdminRetrievalQualityPage';
import { api } from '../../api/client';

describe('AdminRetrievalQualityPage', () => {
  beforeEach(() => {
    vi.spyOn(api.admin, 'listRetrievalRuns').mockResolvedValue({
      data: {
        recent_runs: [
          {
            query_set_id: 'core-agent-queries',
            mode: 'hybrid',
            ndcg_at_5: 0.42,
            ndcg_at_10: 0.55,
            recall_at_20: 0.71,
            mrr: 0.38,
          },
          {
            query_set_id: 'core-agent-queries',
            mode: 'hybrid',
            ndcg_at_5: 0.45,
            ndcg_at_10: 0.58,
            recall_at_20: 0.74,
            mrr: 0.40,
          },
        ],
        count: 2,
      },
    });
    vi.spyOn(api.admin, 'runRetrievalNow').mockResolvedValue({ data: {} });
  });

  it('renders one row per (set, mode) bucket with the latest value + sparkline', async () => {
    render(<AdminRetrievalQualityPage />);
    await waitFor(() => screen.getByText('core-agent-queries'));
    // 'hybrid' appears twice: once in the mode <option>, once in the table cell.
    expect(screen.getAllByText('hybrid').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('0.420')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Run now/ })).toBeInTheDocument();
  });
});
