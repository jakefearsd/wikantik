import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import AdminRetrievalQualityPage from './AdminRetrievalQualityPage';
import { api } from '../../api/client';

// `request()` auto-unwraps single-key `{data: ...}` envelopes — mock at the
// unwrapped shape since that's what the api method returns in production.
const twoRuns = {
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
};

describe('AdminRetrievalQualityPage', () => {
  let listSpy;
  let runSpy;

  beforeEach(() => {
    listSpy = vi.spyOn(api.admin, 'listRetrievalRuns').mockResolvedValue(twoRuns);
    runSpy = vi.spyOn(api.admin, 'runRetrievalNow').mockResolvedValue({});
  });

  it('renders one row per (set, mode) bucket with the latest value + sparkline', async () => {
    render(<AdminRetrievalQualityPage />);
    await waitFor(() => screen.getByText('core-agent-queries'));
    // 'hybrid' appears twice: once in the mode <option>, once in the table cell.
    expect(screen.getAllByText('hybrid').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('0.420')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Run now/ })).toBeInTheDocument();
  });

  it('renders the empty state when no runs exist', async () => {
    listSpy.mockResolvedValueOnce({ recent_runs: [], count: 0 });
    render(<AdminRetrievalQualityPage />);
    await waitFor(() => screen.getByText(/No runs yet\./));
  });

  it('renders the error banner when the list call rejects', async () => {
    listSpy.mockRejectedValueOnce(new Error('upstream is down'));
    render(<AdminRetrievalQualityPage />);
    await waitFor(() => screen.getByText('upstream is down'));
  });

  it('clicking Run now POSTs with the row coordinates and re-fetches', async () => {
    render(<AdminRetrievalQualityPage />);
    await waitFor(() => screen.getByText('core-agent-queries'));
    expect(listSpy).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: /Run now/ }));

    await waitFor(() => expect(runSpy).toHaveBeenCalledWith('core-agent-queries', 'hybrid'));
    await waitFor(() => expect(listSpy).toHaveBeenCalledTimes(2));
  });

  it('changing the mode filter re-issues the list call with the mode parameter', async () => {
    render(<AdminRetrievalQualityPage />);
    await waitFor(() => screen.getByText('core-agent-queries'));
    expect(listSpy).toHaveBeenLastCalledWith({
      querySetId: undefined, mode: undefined, limit: 30,
    });

    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'bm25' } });

    await waitFor(() => expect(listSpy).toHaveBeenLastCalledWith({
      querySetId: undefined, mode: 'bm25', limit: 30,
    }));
  });
});
