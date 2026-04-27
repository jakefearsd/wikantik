import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import AdminKgPolicyPage from './AdminKgPolicyPage';
import { api } from '../../api/client';

// ---- fixtures ----

const clusterInclude = {
  cluster: 'personal-finance',
  page_count: 25,
  action: 'include',
  reason: 'Core topic cluster',
  set_by: 'admin',
  set_at: '2026-04-01T10:00:00Z',
  reviewed_at: '2026-04-20T10:00:00Z',
};

const clusterExclude = {
  cluster: 'lifestyle',
  page_count: 10,
  action: 'exclude',
  reason: 'Off-topic',
  set_by: 'admin',
  set_at: '2026-04-02T10:00:00Z',
  reviewed_at: '2026-04-15T10:00:00Z',
};

const clusterUnset = {
  cluster: 'travel',
  page_count: 5,
  action: null,
  reason: null,
  set_by: null,
  set_at: null,
  reviewed_at: null,
};

const defaultClusters = [clusterInclude, clusterExclude, clusterUnset];

const noReconciliation = { reconciliation: [] };

// ---- helpers ----

function mockApi(clusters = defaultClusters, reconciliation = []) {
  vi.spyOn(api.admin.kgPolicy, 'listClusters').mockResolvedValue({
    clusters,
  });
  vi.spyOn(api.admin.kgPolicy, 'reconciliation').mockResolvedValue({
    reconciliation,
  });
}

describe('AdminKgPolicyPage', () => {
  beforeEach(() => {
    mockApi();
    // Default window.confirm to false so Clear calls don't accidentally proceed
    vi.spyOn(window, 'confirm').mockReturnValue(false);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ---- 1. Renders rows from listClusters() ----

  it('renders a row for each cluster returned by listClusters', async () => {
    render(<AdminKgPolicyPage />);

    await waitFor(() => expect(screen.getByText('personal-finance')).toBeInTheDocument());
    expect(screen.getByText('lifestyle')).toBeInTheDocument();
    expect(screen.getByText('travel')).toBeInTheDocument();

    // Badge variants
    expect(screen.getByText('Include')).toBeInTheDocument();
    expect(screen.getByText('Exclude')).toBeInTheDocument();
    expect(screen.getByText('Unset')).toBeInTheDocument();
  });

  // ---- 2. Filter by action narrows visible rows ----

  it('filter by action narrows visible rows', async () => {
    render(<AdminKgPolicyPage />);
    await waitFor(() => expect(screen.getByText('personal-finance')).toBeInTheDocument());

    const select = screen.getByRole('combobox');

    // Filter: include
    fireEvent.change(select, { target: { value: 'include' } });
    expect(screen.getByText('personal-finance')).toBeInTheDocument();
    expect(screen.queryByText('lifestyle')).not.toBeInTheDocument();
    expect(screen.queryByText('travel')).not.toBeInTheDocument();

    // Filter: exclude
    fireEvent.change(select, { target: { value: 'exclude' } });
    expect(screen.queryByText('personal-finance')).not.toBeInTheDocument();
    expect(screen.getByText('lifestyle')).toBeInTheDocument();
    expect(screen.queryByText('travel')).not.toBeInTheDocument();

    // Filter: unset
    fireEvent.change(select, { target: { value: 'unset' } });
    expect(screen.queryByText('personal-finance')).not.toBeInTheDocument();
    expect(screen.queryByText('lifestyle')).not.toBeInTheDocument();
    expect(screen.getByText('travel')).toBeInTheDocument();

    // Filter: all restores everything
    fireEvent.change(select, { target: { value: 'all' } });
    expect(screen.getByText('personal-finance')).toBeInTheDocument();
    expect(screen.getByText('lifestyle')).toBeInTheDocument();
    expect(screen.getByText('travel')).toBeInTheDocument();
  });

  // ---- 3. Search by name narrows visible rows ----

  it('search by name narrows visible rows (client-side substring match)', async () => {
    render(<AdminKgPolicyPage />);
    await waitFor(() => expect(screen.getByText('personal-finance')).toBeInTheDocument());

    const searchInput = screen.getByPlaceholderText(/Search cluster name/i);
    fireEvent.change(searchInput, { target: { value: 'finance' } });

    expect(screen.getByText('personal-finance')).toBeInTheDocument();
    expect(screen.queryByText('lifestyle')).not.toBeInTheDocument();
    expect(screen.queryByText('travel')).not.toBeInTheDocument();

    // Case-insensitive
    fireEvent.change(searchInput, { target: { value: 'LIFE' } });
    expect(screen.queryByText('personal-finance')).not.toBeInTheDocument();
    expect(screen.getByText('lifestyle')).toBeInTheDocument();

    // Clear search restores all rows
    fireEvent.change(searchInput, { target: { value: '' } });
    expect(screen.getByText('personal-finance')).toBeInTheDocument();
    expect(screen.getByText('lifestyle')).toBeInTheDocument();
    expect(screen.getByText('travel')).toBeInTheDocument();
  });

  // ---- 4. Edit → Preview → Confirm flow ----

  it('Edit button opens modal; Preview fetches estimate; Confirm calls setCluster', async () => {
    vi.spyOn(api.admin.kgPolicy, 'estimate').mockResolvedValue({
      cluster: 'personal-finance',
      page_count: 25,
      action: 'exclude',
      note: 'All pages will be de-indexed',
    });
    const setCluster = vi.spyOn(api.admin.kgPolicy, 'setCluster').mockResolvedValue({ ok: true });

    render(<AdminKgPolicyPage />);
    await waitFor(() => expect(screen.getByText('personal-finance')).toBeInTheDocument());

    // Click Edit on personal-finance row
    const row = screen.getByText('personal-finance').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: /Edit/i }));

    // Edit modal should appear with cluster name in heading
    await waitFor(() => expect(screen.getByText(/Edit policy: personal-finance/i)).toBeInTheDocument());

    // Switch action to exclude
    fireEvent.click(screen.getByLabelText(/Exclude from KG/i));

    // Fill in reason
    fireEvent.change(screen.getByPlaceholderText(/Optional reason/i), {
      target: { value: 'Rebalancing clusters' },
    });

    // Click Preview — should call estimate
    fireEvent.click(screen.getByRole('button', { name: /Preview/i }));

    await waitFor(() => expect(api.admin.kgPolicy.estimate).toHaveBeenCalledWith(
      'personal-finance',
      'exclude',
    ));

    // Estimate confirm modal should show page count and cluster name
    await waitFor(() =>
      expect(screen.getByText(/will affect/i)).toBeInTheDocument(),
    );
    expect(screen.getByText('25')).toBeInTheDocument();
    expect(screen.getByText(/All pages will be de-indexed/i)).toBeInTheDocument();

    // Confirm
    fireEvent.click(screen.getByRole('button', { name: /Confirm/i }));

    await waitFor(() =>
      expect(setCluster).toHaveBeenCalledWith('personal-finance', {
        action: 'exclude',
        reason: 'Rebalancing clusters',
      }),
    );

    // List reloads after confirm
    expect(api.admin.kgPolicy.listClusters).toHaveBeenCalledTimes(2);
  });

  it('Cancel on the estimate modal does not call setCluster', async () => {
    vi.spyOn(api.admin.kgPolicy, 'estimate').mockResolvedValue({
      cluster: 'personal-finance',
      page_count: 25,
      action: 'exclude',
    });
    const setCluster = vi.spyOn(api.admin.kgPolicy, 'setCluster').mockResolvedValue({ ok: true });

    render(<AdminKgPolicyPage />);
    await waitFor(() => expect(screen.getByText('personal-finance')).toBeInTheDocument());

    const row = screen.getByText('personal-finance').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: /Edit/i }));

    await waitFor(() => expect(screen.getByText(/Edit policy: personal-finance/i)).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /Preview/i }));

    await waitFor(() => expect(screen.getByText(/will affect/i)).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Cancel/i }));

    expect(setCluster).not.toHaveBeenCalled();
    expect(screen.queryByText(/will affect/i)).not.toBeInTheDocument();
  });

  // ---- 5. Clear button calls clearCluster after window.confirm ----

  it('Clear button calls clearCluster when window.confirm returns true', async () => {
    window.confirm.mockReturnValue(true);
    const clearCluster = vi.spyOn(api.admin.kgPolicy, 'clearCluster').mockResolvedValue({ ok: true });

    render(<AdminKgPolicyPage />);
    await waitFor(() => expect(screen.getByText('personal-finance')).toBeInTheDocument());

    const row = screen.getByText('personal-finance').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: /Clear/i }));

    await waitFor(() =>
      expect(clearCluster).toHaveBeenCalledWith('personal-finance'),
    );
    expect(api.admin.kgPolicy.listClusters).toHaveBeenCalledTimes(2);
  });

  it('Clear button does NOT call clearCluster when window.confirm returns false', async () => {
    window.confirm.mockReturnValue(false);
    const clearCluster = vi.spyOn(api.admin.kgPolicy, 'clearCluster').mockResolvedValue({ ok: true });

    render(<AdminKgPolicyPage />);
    await waitFor(() => expect(screen.getByText('personal-finance')).toBeInTheDocument());

    const row = screen.getByText('personal-finance').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: /Clear/i }));

    expect(clearCluster).not.toHaveBeenCalled();
  });

  it('Clear button is absent for unset clusters', async () => {
    render(<AdminKgPolicyPage />);
    await waitFor(() => expect(screen.getByText('travel')).toBeInTheDocument());

    const row = screen.getByText('travel').closest('tr');
    expect(within(row).queryByRole('button', { name: /Clear/i })).not.toBeInTheDocument();
  });

  // ---- Bootstrap call-to-action ----

  it('shows bootstrap callout when all clusters have no action set', async () => {
    vi.spyOn(api.admin.kgPolicy, 'listClusters').mockResolvedValue({
      clusters: [clusterUnset, { ...clusterUnset, cluster: 'sports' }],
    });

    render(<AdminKgPolicyPage />);
    await waitFor(() =>
      expect(screen.getByText(/No clusters configured yet/i)).toBeInTheDocument(),
    );
    expect(screen.getByText(/Bootstrap wizard — Task 25/i)).toBeInTheDocument();
  });

  it('does not show bootstrap callout when any cluster has a policy', async () => {
    render(<AdminKgPolicyPage />);
    await waitFor(() => expect(screen.getByText('personal-finance')).toBeInTheDocument());
    expect(screen.queryByText(/No clusters configured yet/i)).not.toBeInTheDocument();
  });

  // ---- Reconciliation panel ----

  it('shows reconciliation panel when reconciliation data is returned', async () => {
    vi.spyOn(api.admin.kgPolicy, 'reconciliation').mockResolvedValue({
      reconciliation: [
        { cluster: 'personal-finance', state: 'RUNNING', processed: 10, total_pages: 25, errors: 0 },
      ],
    });

    render(<AdminKgPolicyPage />);
    await waitFor(() =>
      expect(screen.getByText(/Reconciliation in progress/i)).toBeInTheDocument(),
    );
    expect(screen.getByText(/RUNNING/)).toBeInTheDocument();
  });

  // ---- Error state ----

  it('surfaces a list error via AdminPage error prop', async () => {
    vi.spyOn(api.admin.kgPolicy, 'listClusters').mockRejectedValueOnce(new Error('network timeout'));
    render(<AdminKgPolicyPage />);
    await waitFor(() => expect(screen.getByText('network timeout')).toBeInTheDocument());
  });
});
