import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import AdminKgPolicyPending from './AdminKgPolicyPending';
import { api } from '../../api/client';

const pendingData = {
  unset_clusters: [
    { cluster: 'travel', page_count: 5 },
    { cluster: 'gaming', page_count: 12 },
  ],
  stale_reviews: [
    { cluster: 'personal-finance', action: 'include', reviewed_at: '2025-12-01T00:00:00Z' },
  ],
  recent_count_changes: [],
};

describe('AdminKgPolicyPending', () => {
  beforeEach(() => {
    vi.spyOn(api.admin.kgPolicy, 'pending').mockResolvedValue(pendingData);
    vi.spyOn(api.admin.kgPolicy, 'markReviewed').mockResolvedValue({ ok: true });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ---- 1. Renders three sections with their rows ----

  it('renders unset clusters, stale reviews, and recent count changes sections', async () => {
    render(<AdminKgPolicyPending />);

    await waitFor(() => expect(screen.getByText('travel')).toBeInTheDocument());

    // Unset clusters section
    expect(screen.getByText('gaming')).toBeInTheDocument();

    // Stale reviews section
    expect(screen.getByText('personal-finance')).toBeInTheDocument();

    // Recent count changes empty text
    expect(screen.getByText(/No drift detected/i)).toBeInTheDocument();

    // Action buttons only on stale reviews
    const markButtons = screen.getAllByRole('button', { name: /Mark reviewed/i });
    expect(markButtons).toHaveLength(1);
  });

  // ---- 2. "Mark reviewed" calls markReviewed then reloads ----

  it('"Mark reviewed" calls markReviewed with cluster name and reloads the list', async () => {
    render(<AdminKgPolicyPending />);

    await waitFor(() => expect(screen.getByText('personal-finance')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Mark reviewed/i }));

    await waitFor(() =>
      expect(api.admin.kgPolicy.markReviewed).toHaveBeenCalledWith('personal-finance'),
    );

    // reload triggered — pending called twice total
    await waitFor(() =>
      expect(api.admin.kgPolicy.pending).toHaveBeenCalledTimes(2),
    );
  });
});
