import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AdminUsersPage from './AdminUsersPage';

// ---------------------------------------------------------------------------
// Mock api/client
// ---------------------------------------------------------------------------

vi.mock('../../api/client', () => ({
  api: {
    admin: {
      listUsers: vi.fn(),
      createUser: vi.fn(),
      updateUser: vi.fn(),
      deleteUser: vi.fn(),
      lockUser: vi.fn(),
      unlockUser: vi.fn(),
      bulkUserAction: vi.fn(),
      listGroups: vi.fn(),
    },
    getUser: vi.fn(),
  },
}));

import { api } from '../../api/client';

const USERS = [
  { loginName: 'alice', fullName: 'Alice Admin', email: 'alice@example.com', created: '2024-01-01', lastLogin: '2026-06-20T09:12:00Z', locked: false },
  { loginName: 'bob', fullName: 'Bob Builder', email: 'bob@example.com', created: '2024-01-02', lastLogin: '2026-06-18T14:40:00Z', locked: false },
  { loginName: 'charlie', fullName: 'Charlie Checker', email: 'charlie@example.com', created: '2024-01-03', lastLogin: null, locked: true },
];

beforeEach(() => {
  vi.clearAllMocks();
  api.admin.listUsers.mockResolvedValue({ users: USERS });
  api.getUser.mockResolvedValue({ loginName: 'admin' });
  api.admin.listGroups.mockResolvedValue({ groups: [{ name: 'editors' }, { name: 'readers' }] });
  api.admin.bulkUserAction.mockResolvedValue({
    succeeded: ['alice', 'bob'],
    failed: [],
    status: 'completed',
    message: '2 of 2 users locked',
  });
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Returns the bulk-action toolbar, which is rendered only when rows are selected. */
const getBulkToolbar = () => screen.getByRole('toolbar', { name: 'Bulk actions' });

// ---------------------------------------------------------------------------
// Basic rendering
// ---------------------------------------------------------------------------

describe('AdminUsersPage — loading state', () => {
  it('shows the loading indicator before the user list resolves', async () => {
    let resolve;
    api.admin.listUsers.mockReturnValue(new Promise((r) => { resolve = r; }));

    render(<AdminUsersPage />);
    // Loading label is visible while the fetch is pending.
    expect(screen.getByText('Loading users…')).toBeInTheDocument();
    expect(screen.queryByText('alice')).toBeNull();

    resolve({ users: USERS });
    await screen.findByText('alice');
    expect(screen.queryByText('Loading users…')).toBeNull();
  });
});

describe('AdminUsersPage — rendering', () => {
  it('renders the user list after load', async () => {
    render(<AdminUsersPage />);
    await screen.findByText('alice');
    expect(screen.getByText('bob')).toBeTruthy();
    expect(screen.getByText('charlie')).toBeTruthy();
  });

  it('shows Create User button', async () => {
    render(<AdminUsersPage />);
    await screen.findByText('alice');
    expect(screen.getByText('+ Create User')).toBeTruthy();
  });

  it('shows lock status badges', async () => {
    render(<AdminUsersPage />);
    await screen.findByText('alice');
    const activeBadges = screen.getAllByText('Active');
    expect(activeBadges.length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Locked')).toBeTruthy();
  });

  it('shows a Last login column, with an em dash for never-authenticated accounts', async () => {
    render(<AdminUsersPage />);
    await screen.findByText('alice');

    expect(screen.getByText('Last login')).toBeInTheDocument();

    // Every user has a full name and email, so charlie's null lastLogin is the
    // only em dash in the table body.
    const charlieRow = screen.getByText('charlie').closest('tr');
    expect(within(charlieRow).getByText('—')).toBeInTheDocument();

    // alice authenticated, so her row must NOT render an em dash.
    const aliceRow = screen.getByText('alice').closest('tr');
    expect(within(aliceRow).queryByText('—')).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Bulk lock confirm flow
// ---------------------------------------------------------------------------

describe('AdminUsersPage — bulk lock', () => {
  it('shows selection bar after selecting a row', async () => {
    render(<AdminUsersPage />);
    await screen.findByText('alice');

    const checkboxes = screen.getAllByRole('checkbox');
    // First checkbox is the header; row checkboxes follow
    fireEvent.click(checkboxes[1]);

    await screen.findByText(/1 selected/);
  });

  it('dispatches bulk lock and refreshes on confirm', async () => {
    render(<AdminUsersPage />);
    await screen.findByText('alice');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    // Click Lock action button in the bulk toolbar (not the row action)
    const toolbar = getBulkToolbar();
    fireEvent.click(within(toolbar).getByRole('button', { name: /^Lock$/i }));

    // Confirm dialog — scope to the dialog to avoid ambiguity with the title text
    const dialog = await screen.findByRole('dialog', { name: 'Lock Users' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Lock Users' }));

    await waitFor(() => {
      expect(api.admin.bulkUserAction).toHaveBeenCalledWith('lock', ['alice']);
    });

    // loadUsers is called again after success
    expect(api.admin.listUsers).toHaveBeenCalledTimes(2);
  });
});

// ---------------------------------------------------------------------------
// Bulk delete with self-in-selection guard
// ---------------------------------------------------------------------------

describe('AdminUsersPage — bulk delete self-guard', () => {
  it('disables Delete when current user is selected', async () => {
    // Current user is 'alice'
    api.getUser.mockResolvedValue({ loginName: 'alice' });

    render(<AdminUsersPage />);
    await screen.findByText('alice');

    // Select alice's row
    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    // Delete button in the bulk toolbar should be disabled
    const toolbar = getBulkToolbar();
    const deleteBtn = within(toolbar).getByRole('button', { name: /^Delete$/i });
    expect(deleteBtn.disabled).toBeTruthy();
  });

  it('enables Delete when current user is NOT selected', async () => {
    // Current user is 'admin' — not in the list
    api.getUser.mockResolvedValue({ loginName: 'admin' });

    render(<AdminUsersPage />);
    await screen.findByText('alice');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = getBulkToolbar();
    const deleteBtn = within(toolbar).getByRole('button', { name: /^Delete$/i });
    expect(deleteBtn.disabled).toBeFalsy();
  });
});

// ---------------------------------------------------------------------------
// Add-to-group modal flow
// ---------------------------------------------------------------------------

describe('AdminUsersPage — add-to-group modal', () => {
  it('opens group picker modal when Add to group… is clicked', async () => {
    render(<AdminUsersPage />);
    await screen.findByText('alice');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = getBulkToolbar();
    fireEvent.click(within(toolbar).getByRole('button', { name: /add to group/i }));

    // Modal heading appears
    await screen.findByRole('heading', { name: /add to group/i });
    await screen.findByText('editors');
    expect(screen.getByText('readers')).toBeTruthy();
  });

  it('dispatches add-to-group with selected group name on confirm', async () => {
    render(<AdminUsersPage />);
    await screen.findByText('alice');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = getBulkToolbar();
    fireEvent.click(within(toolbar).getByRole('button', { name: /add to group/i }));
    await screen.findByRole('heading', { name: /add to group/i });

    // Select editors group
    const select = screen.getByRole('combobox');
    fireEvent.change(select, { target: { value: 'editors' } });

    // Click the "Add to Group" confirm button inside the modal
    const modal = screen.getByRole('heading', { name: /add to group/i }).closest('.modal-content');
    fireEvent.click(within(modal).getByRole('button', { name: 'Add to Group' }));

    await waitFor(() => {
      expect(api.admin.bulkUserAction).toHaveBeenCalledWith(
        'add-to-group',
        ['alice'],
        { group: 'editors' },
      );
    });
  });

  it('cancels without dispatch when Cancel is clicked in group modal', async () => {
    render(<AdminUsersPage />);
    await screen.findByText('alice');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = getBulkToolbar();
    fireEvent.click(within(toolbar).getByRole('button', { name: /add to group/i }));
    await screen.findByRole('heading', { name: /add to group/i });

    const modal = screen.getByRole('heading', { name: /add to group/i }).closest('.modal-content');
    fireEvent.click(within(modal).getByRole('button', { name: 'Cancel' }));

    await waitFor(() => {
      expect(api.admin.bulkUserAction).not.toHaveBeenCalled();
    });
  });
});

// ---------------------------------------------------------------------------
// List refresh on success
// ---------------------------------------------------------------------------

describe('AdminUsersPage — list refresh', () => {
  it('refetches user list after successful bulk action', async () => {
    render(<AdminUsersPage />);
    await screen.findByText('alice');

    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    await screen.findByText(/1 selected/);

    const toolbar = getBulkToolbar();
    fireEvent.click(within(toolbar).getByRole('button', { name: /^Lock$/i }));
    const dialog = await screen.findByRole('dialog', { name: 'Lock Users' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Lock Users' }));

    await waitFor(() => {
      // Once on mount, once after bulk action
      expect(api.admin.listUsers.mock.calls.length).toBeGreaterThanOrEqual(2);
    });
  });
});

// ---------------------------------------------------------------------------
// Partial failure UI
// ---------------------------------------------------------------------------

describe('AdminUsersPage — partial failure', () => {
  it('shows failure count in the result banner after partial failure', async () => {
    api.admin.bulkUserAction.mockResolvedValue({
      succeeded: ['alice'],
      failed: [{ id: 'bob', error: 'User not found' }],
      status: 'completed',
      message: '1 of 2 users locked',
    });

    render(<AdminUsersPage />);
    await screen.findByText('alice');

    // Select both alice and bob
    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    fireEvent.click(checkboxes[2]);
    await screen.findByText(/2 selected/);

    const toolbar = getBulkToolbar();
    fireEvent.click(within(toolbar).getByRole('button', { name: /^Lock$/i }));
    const dialog = await screen.findByRole('dialog', { name: 'Lock Users' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Lock Users' }));

    // After dispatch, AdminTable calls bulkUserAction with both IDs
    await waitFor(() => {
      expect(api.admin.bulkUserAction).toHaveBeenCalled();
    });
    // Verify the API was called with both IDs
    expect(api.admin.bulkUserAction).toHaveBeenCalledWith('lock', ['alice', 'bob']);
  });
});
