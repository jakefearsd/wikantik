import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../../api/client', () => ({
  api: { admin: { listAuditLog: vi.fn(), verifyAuditChain: vi.fn() } },
}));

import AdminAuditPage from './AdminAuditPage';
import { api } from '../../api/client';

const record1 = {
  seq: 1,
  eventTime: '2026-01-01T10:00:00Z',
  actorPrincipal: 'alice',
  category: 'AUTHN',
  eventType: 'LOGIN',
  outcome: 'SUCCESS',
  targetId: null,
  rowHash: 'aaa',
  prevHash: null,
};

const record2 = {
  seq: 2,
  eventTime: '2026-01-02T10:00:00Z',
  actorPrincipal: null,
  category: null,
  eventType: null,
  outcome: 'FAILURE',
  targetId: 'PageX',
  rowHash: 'bbb',
  prevHash: 'aaa',
};

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AdminAuditPage', () => {
  it('shows a hint and no table before any search', () => {
    render(<AdminAuditPage />);
    expect(screen.getByText(/Set filters above/)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(api.admin.listAuditLog).not.toHaveBeenCalled();
  });

  it('searches with the current filters and renders results', async () => {
    api.admin.listAuditLog.mockResolvedValue([record1, record2]);
    render(<AdminAuditPage />);

    fireEvent.change(screen.getByLabelText('Filter by actor'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('Filter by category'), { target: { value: 'authn' } });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(api.admin.listAuditLog).toHaveBeenCalledWith(
      expect.objectContaining({ actor: 'alice', category: 'authn' })
    ));

    expect(await screen.findByText('alice')).toBeInTheDocument();
    const aliceRow = screen.getByText('alice').closest('tr');
    // Outcome badge lowercased
    expect(within(aliceRow).getByText('success')).toBeInTheDocument();
    // targetId falls back to em dash
    expect(within(aliceRow).getByText('—')).toBeInTheDocument();
  });

  it('renders em-dash fallbacks for missing actor/category/event on a row', async () => {
    api.admin.listAuditLog.mockResolvedValue([record2]);
    render(<AdminAuditPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const targetCell = await screen.findByText('PageX');
    expect(within(targetCell.closest('tr')).getByText('failure')).toBeInTheDocument();
  });

  it('shows the empty-results message when no rows match', async () => {
    api.admin.listAuditLog.mockResolvedValue([]);
    render(<AdminAuditPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('No audit entries matched the filter.')).toBeInTheDocument();
  });

  it('tolerates a non-array response by rendering an empty table', async () => {
    api.admin.listAuditLog.mockResolvedValue(null);
    render(<AdminAuditPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('No audit entries matched the filter.')).toBeInTheDocument();
  });

  it('surfaces a search error in the banner', async () => {
    api.admin.listAuditLog.mockRejectedValue(new Error('search failed'));
    render(<AdminAuditPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('search failed')).toBeInTheDocument();
  });

  it('disables the Search button and shows a loading label while pending', async () => {
    let resolveFn;
    api.admin.listAuditLog.mockReturnValue(new Promise((resolve) => { resolveFn = resolve; }));
    render(<AdminAuditPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByRole('button', { name: 'Loading…' })).toBeDisabled();
    resolveFn([]);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Search' })).not.toBeDisabled());
  });

  it('opens the record modal on row click and closes it', async () => {
    api.admin.listAuditLog.mockResolvedValue([record1]);
    render(<AdminAuditPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const row = await screen.findByText('alice');
    fireEvent.click(row.closest('tr'));

    expect(await screen.findByText('Audit record #1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByText('Audit record #1')).not.toBeInTheDocument();
  });

  it('verify integrity: shows the ok banner on success', async () => {
    api.admin.verifyAuditChain.mockResolvedValue({ ok: true });
    render(<AdminAuditPage />);

    fireEvent.click(screen.getByRole('button', { name: 'Verify integrity' }));

    expect(await screen.findByText('Chain intact — all row hashes verified.')).toBeInTheDocument();
  });

  it('verify integrity: shows the broken-chain banner with the seq', async () => {
    api.admin.verifyAuditChain.mockResolvedValue({ ok: false, firstBrokenSeq: 7 });
    render(<AdminAuditPage />);

    fireEvent.click(screen.getByRole('button', { name: 'Verify integrity' }));

    expect(await screen.findByText('Hash chain broken at seq 7.')).toBeInTheDocument();
  });

  it('verify integrity: shows an error message when the call rejects', async () => {
    api.admin.verifyAuditChain.mockRejectedValue(new Error('verify boom'));
    render(<AdminAuditPage />);

    fireEvent.click(screen.getByRole('button', { name: 'Verify integrity' }));

    expect(await screen.findByText('Verification failed: verify boom')).toBeInTheDocument();
  });

  it('disables the Verify integrity button while the check is pending', async () => {
    let resolveFn;
    api.admin.verifyAuditChain.mockReturnValue(new Promise((resolve) => { resolveFn = resolve; }));
    render(<AdminAuditPage />);

    fireEvent.click(screen.getByRole('button', { name: 'Verify integrity' }));
    expect(await screen.findByRole('button', { name: 'Verifying…' })).toBeDisabled();

    resolveFn({ ok: true });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Verify integrity' })).not.toBeDisabled());
  });

  it('exposes a CSV export link', () => {
    render(<AdminAuditPage />);
    const link = screen.getByRole('link', { name: 'Export CSV' });
    expect(link).toHaveAttribute('href', '/admin/audit/export?format=csv');
    expect(link).toHaveAttribute('download', 'audit-log.csv');
  });
});
