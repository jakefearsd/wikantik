// AdminPageOwnershipPage.test.jsx
//
// Mirrors the AdminKgPolicyPage test style — `vi.spyOn` the api.admin.pageOwnership
// surface and assert against the rendered DOM. The page has four wire interactions
// to cover:
//   1. listOrphaned()       — default load
//   2. listByOwner(owner)   — after switching tab + submitting search
//   3. reassign([id], to)   — per-row reassign modal
//   4. reassignByUser(a,b)  — bulk form
// plus the `<orphaned>` sentinel passthrough.
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import AdminPageOwnershipPage from './AdminPageOwnershipPage';
import { api } from '../../api/client';

const orphanedRow = {
  canonicalId: 'page-001',
  ownerLogin: null,
  assignedBy: 'system',
  assignedAt: '2026-05-01T10:00:00Z',
};

const ownedRow = {
  canonicalId: 'page-042',
  ownerLogin: 'alice',
  assignedBy: 'admin',
  assignedAt: '2026-05-10T10:00:00Z',
};

function mockApi() {
  vi.spyOn(api.admin.pageOwnership, 'listOrphaned').mockResolvedValue({
    pages: [orphanedRow],
    total: 1,
  });
  vi.spyOn(api.admin.pageOwnership, 'listByOwner').mockResolvedValue({
    pages: [ownedRow],
    total: 1,
  });
  vi.spyOn(api.admin.pageOwnership, 'reassign').mockResolvedValue({ ok: true });
  vi.spyOn(api.admin.pageOwnership, 'reassignByUser').mockResolvedValue({ ok: true });
}

describe('AdminPageOwnershipPage', () => {
  beforeEach(() => {
    mockApi();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ---- 1. Default orphaned filter loads + renders ----
  it('loads orphaned pages by default and renders them in the table', async () => {
    render(<AdminPageOwnershipPage />);
    await waitFor(() =>
      expect(api.admin.pageOwnership.listOrphaned).toHaveBeenCalled(),
    );
    expect(await screen.findByText('page-001')).toBeInTheDocument();
    expect(api.admin.pageOwnership.listByOwner).not.toHaveBeenCalled();
  });

  // ---- 2. Switching to By Owner + submitting search calls listByOwner ----
  it('switching to "By Owner" + submitting search calls listByOwner with the typed login', async () => {
    render(<AdminPageOwnershipPage />);
    await waitFor(() =>
      expect(api.admin.pageOwnership.listOrphaned).toHaveBeenCalled(),
    );

    fireEvent.click(screen.getByRole('tab', { name: /By Owner/i }));

    const input = screen.getByLabelText(/Owner login/i);
    fireEvent.change(input, { target: { value: 'alice' } });
    fireEvent.click(screen.getByRole('button', { name: /^Search$/i }));

    await waitFor(() =>
      expect(api.admin.pageOwnership.listByOwner).toHaveBeenCalledWith('alice'),
    );
    expect(await screen.findByText('page-042')).toBeInTheDocument();
  });

  // ---- 3. Per-row Reassign opens modal, submit calls reassign + reloads ----
  it('per-row Reassign opens a modal whose submit calls reassign() and refetches', async () => {
    render(<AdminPageOwnershipPage />);
    await screen.findByText('page-001');

    const row = screen.getByText('page-001').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: /Reassign/i }));

    // Modal heading should cite the canonical id
    await screen.findByText(/Reassign page-001/i);

    const newOwnerInput = screen.getByPlaceholderText(/new owner login/i);
    fireEvent.change(newOwnerInput, { target: { value: 'bob' } });

    // Submit button inside the modal — scope to the dialog
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /^Reassign$/i }));

    await waitFor(() =>
      expect(api.admin.pageOwnership.reassign).toHaveBeenCalledWith(
        ['page-001'],
        'bob',
      ),
    );

    // Modal closes + list reloads
    await waitFor(() =>
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
    );
    expect(api.admin.pageOwnership.listOrphaned).toHaveBeenCalledTimes(2);
  });

  // ---- 4. Bulk reassign-by-user calls reassignByUser + reloads ----
  it('bulk reassign-by-user form calls reassignByUser and refetches', async () => {
    render(<AdminPageOwnershipPage />);
    await screen.findByText('page-001');

    const form = screen.getByTestId('admin-bulk-reassign-form');
    const [fromInput, toInput] = within(form).getAllByRole('textbox');
    fireEvent.change(fromInput, { target: { value: 'alice' } });
    fireEvent.change(toInput, { target: { value: 'bob' } });

    fireEvent.click(within(form).getByRole('button', { name: /^Reassign$/i }));

    await waitFor(() =>
      expect(api.admin.pageOwnership.reassignByUser).toHaveBeenCalledWith(
        'alice',
        'bob',
      ),
    );
    expect(api.admin.pageOwnership.listOrphaned).toHaveBeenCalledTimes(2);
  });

  // ---- 5. <orphaned> sentinel passthrough in bulk reassign form ----
  it('passes the <orphaned> sentinel through verbatim to reassignByUser', async () => {
    render(<AdminPageOwnershipPage />);
    await screen.findByText('page-001');

    const form = screen.getByTestId('admin-bulk-reassign-form');
    const [fromInput, toInput] = within(form).getAllByRole('textbox');
    fireEvent.change(fromInput, { target: { value: 'alice' } });
    fireEvent.change(toInput, { target: { value: '<orphaned>' } });
    fireEvent.click(within(form).getByRole('button', { name: /^Reassign$/i }));

    await waitFor(() =>
      expect(api.admin.pageOwnership.reassignByUser).toHaveBeenCalledWith(
        'alice',
        '<orphaned>',
      ),
    );
  });

  // ---- 6. <orphaned> sentinel in the By-Owner search ----
  it('typed <orphaned> sentinel is passed verbatim to listByOwner', async () => {
    render(<AdminPageOwnershipPage />);
    await waitFor(() =>
      expect(api.admin.pageOwnership.listOrphaned).toHaveBeenCalled(),
    );

    fireEvent.click(screen.getByRole('tab', { name: /By Owner/i }));
    const input = screen.getByLabelText(/Owner login/i);
    fireEvent.change(input, { target: { value: '<orphaned>' } });
    fireEvent.click(screen.getByRole('button', { name: /^Search$/i }));

    await waitFor(() =>
      expect(api.admin.pageOwnership.listByOwner).toHaveBeenCalledWith(
        '<orphaned>',
      ),
    );
  });

  // ---- Error path: list error surfaces ----
  it('surfaces a list error via AdminPage error banner', async () => {
    api.admin.pageOwnership.listOrphaned.mockRejectedValueOnce(
      new Error('boom'),
    );
    render(<AdminPageOwnershipPage />);
    await waitFor(() => expect(screen.getByText('boom')).toBeInTheDocument());
  });
});
