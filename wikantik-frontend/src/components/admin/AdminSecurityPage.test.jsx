import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AdminSecurityPage from './AdminSecurityPage';

// ---------------------------------------------------------------------------
// Mock api/client. The page has a Groups section and a Grants section; both
// load on mount once their section is shown. Default section is 'groups'.
// ---------------------------------------------------------------------------

vi.mock('../../api/client', () => ({
  api: {
    admin: {
      listGroups: vi.fn(),
      updateGroup: vi.fn(),
      deleteGroup: vi.fn(),
      listPolicyGrants: vi.fn(),
      createPolicyGrant: vi.fn(),
      updatePolicyGrant: vi.fn(),
      deletePolicyGrant: vi.fn(),
    },
  },
}));

import { api } from '../../api/client';

const GROUPS = [
  { name: 'editors', members: ['alice', 'bob'] },
  { name: 'Admin', members: ['root'] },
];

const GRANTS = [
  { id: 1, principalType: 'role', principalName: 'Authenticated', permissionType: 'page', target: '*', actions: 'view,edit' },
  { id: 2, principalType: 'role', principalName: 'Admin', permissionType: 'all', target: '*', actions: '*' },
];

beforeEach(() => {
  vi.clearAllMocks();
  api.admin.listGroups.mockResolvedValue({ groups: GROUPS });
  api.admin.updateGroup.mockResolvedValue({});
  api.admin.deleteGroup.mockResolvedValue({});
  api.admin.listPolicyGrants.mockResolvedValue({ grants: GRANTS });
  api.admin.createPolicyGrant.mockResolvedValue({});
  api.admin.updatePolicyGrant.mockResolvedValue({});
  api.admin.deletePolicyGrant.mockResolvedValue({});
});

// The form modals use <label>Text</label><input/> sibling pairs without a
// `for`/`id` association, so getByLabelText can't find the control. This finds
// the input inside the .form-field whose <label> matches the given text.
const fieldInput = (labelText) => {
  const label = screen.getByText(labelText);
  return label.closest('.form-field').querySelector('input, select, textarea');
};

const switchToGrants = async () => {
  fireEvent.click(screen.getByRole('button', { name: 'Policy Grants' }));
  await waitFor(() => expect(api.admin.listPolicyGrants).toHaveBeenCalled());
};

// ---------------------------------------------------------------------------
// Section switching
// ---------------------------------------------------------------------------

describe('AdminSecurityPage — sections', () => {
  it('defaults to the Groups section and loads groups', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    expect(api.admin.listGroups).toHaveBeenCalledTimes(1);
    expect(api.admin.listPolicyGrants).not.toHaveBeenCalled();
  });

  it('switches to the Grants section and loads grants', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    await switchToGrants();
    await screen.findByText('Authenticated');
  });
});

// ---------------------------------------------------------------------------
// Groups section
// ---------------------------------------------------------------------------

describe('AdminSecurityPage — Groups list', () => {
  it('renders groups with member lists and counts', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    expect(screen.getByText('alice, bob')).toBeTruthy();
    // Admin group present
    expect(screen.getByText('Admin')).toBeTruthy();
  });

  it('shows an empty state when no groups exist', async () => {
    api.admin.listGroups.mockResolvedValueOnce({ groups: [] });
    render(<AdminSecurityPage />);
    await screen.findByText('No groups found');
  });

  it('shows the error banner when group load fails', async () => {
    api.admin.listGroups.mockRejectedValueOnce(new Error('groups boom'));
    render(<AdminSecurityPage />);
    await screen.findByText('groups boom');
  });

  it('disables Delete for the built-in Admin group', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    const adminRow = screen.getByText('Admin').closest('tr');
    const delBtn = within(adminRow).getByRole('button', { name: 'Delete' });
    expect(delBtn.disabled).toBe(true);
  });
});

describe('AdminSecurityPage — create group', () => {
  it('opens the modal, submits, and calls updateGroup', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');

    fireEvent.click(screen.getByRole('button', { name: '+ Create Group' }));
    await screen.findByRole('heading', { name: 'Create Group' });

    fireEvent.change(fieldInput('Group Name'), { target: { value: 'newgroup' } });
    const memberInput = screen.getByPlaceholderText('Enter member name…');
    fireEvent.change(memberInput, { target: { value: 'carol' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));

    fireEvent.click(screen.getByRole('button', { name: 'Create Group' }));

    await waitFor(() => {
      expect(api.admin.updateGroup).toHaveBeenCalledWith('newgroup', { members: ['carol'] });
    });
    // list reloads after save
    await waitFor(() => expect(api.admin.listGroups).toHaveBeenCalledTimes(2));
  });
});

describe('AdminSecurityPage — edit group', () => {
  it('opens the modal preloaded and submits the existing members', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');

    const editorsRow = screen.getByText('editors').closest('tr');
    fireEvent.click(within(editorsRow).getByRole('button', { name: 'Edit' }));

    await screen.findByRole('heading', { name: 'Edit Group' });
    // name field disabled in edit mode, members preloaded
    expect(screen.getByDisplayValue('editors')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));

    await waitFor(() => {
      expect(api.admin.updateGroup).toHaveBeenCalledWith('editors', { members: ['alice', 'bob'] });
    });
  });
});

describe('AdminSecurityPage — delete group', () => {
  it('confirms then calls deleteGroup and reloads', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');

    const editorsRow = screen.getByText('editors').closest('tr');
    fireEvent.click(within(editorsRow).getByRole('button', { name: 'Delete' }));

    await screen.findByRole('heading', { name: 'Delete Group' });
    fireEvent.click(screen.getByRole('button', { name: 'Delete Group' }));

    await waitFor(() => expect(api.admin.deleteGroup).toHaveBeenCalledWith('editors'));
    await waitFor(() => expect(api.admin.listGroups).toHaveBeenCalledTimes(2));
  });

  it('cancels delete without calling the API', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    const editorsRow = screen.getByText('editors').closest('tr');
    fireEvent.click(within(editorsRow).getByRole('button', { name: 'Delete' }));
    await screen.findByRole('heading', { name: 'Delete Group' });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Delete Group' })).toBeNull());
    expect(api.admin.deleteGroup).not.toHaveBeenCalled();
  });

  it('surfaces a delete error', async () => {
    api.admin.deleteGroup.mockRejectedValueOnce(new Error('cannot delete'));
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    const editorsRow = screen.getByText('editors').closest('tr');
    fireEvent.click(within(editorsRow).getByRole('button', { name: 'Delete' }));
    await screen.findByRole('heading', { name: 'Delete Group' });
    fireEvent.click(screen.getByRole('button', { name: 'Delete Group' }));
    await screen.findByText('cannot delete');
  });
});

// ---------------------------------------------------------------------------
// Grants section
// ---------------------------------------------------------------------------

describe('AdminSecurityPage — Grants list', () => {
  it('renders grants including action badges and AllPermission row', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    await switchToGrants();
    await screen.findByText('Authenticated');
    // action badges for the page grant
    expect(screen.getByText('view')).toBeTruthy();
    expect(screen.getByText('edit')).toBeTruthy();
    // AllPermission rendering for grant 2
    expect(screen.getByText('AllPermission')).toBeTruthy();
    expect(screen.getByText('* (all)')).toBeTruthy();
  });

  it('shows an empty state when no grants exist', async () => {
    api.admin.listPolicyGrants.mockResolvedValueOnce({ grants: [] });
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    await switchToGrants();
    await screen.findByText('No policy grants found');
  });

  it('shows the error banner when grant load fails', async () => {
    api.admin.listPolicyGrants.mockRejectedValueOnce(new Error('grants boom'));
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    await switchToGrants();
    await screen.findByText('grants boom');
  });
});

describe('AdminSecurityPage — create grant', () => {
  it('opens the modal, fills it, and calls createPolicyGrant with composed data', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    await switchToGrants();
    await screen.findByText('Authenticated');

    fireEvent.click(screen.getByRole('button', { name: '+ Create Grant' }));
    await screen.findByRole('heading', { name: 'Create Policy Grant' });

    fireEvent.change(fieldInput('Principal Name'), { target: { value: 'Anonymous' } });
    // default permissionType=page; toggle the 'view' action checkbox
    const viewCheckbox = screen.getByLabelText('view');
    fireEvent.click(viewCheckbox);

    fireEvent.click(screen.getByRole('button', { name: 'Create Grant' }));

    await waitFor(() => {
      expect(api.admin.createPolicyGrant).toHaveBeenCalledWith({
        principalType: 'role',
        principalName: 'Anonymous',
        permissionType: 'page',
        target: '*',
        actions: 'view',
      });
    });
    expect(api.admin.updatePolicyGrant).not.toHaveBeenCalled();
    await waitFor(() => expect(api.admin.listPolicyGrants).toHaveBeenCalledTimes(2));
  });
});

describe('AdminSecurityPage — edit grant', () => {
  it('opens preloaded modal and calls updatePolicyGrant with the id', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    await switchToGrants();
    await screen.findByText('Authenticated');

    const row = screen.getByText('Authenticated').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: 'Edit' }));
    await screen.findByRole('heading', { name: 'Edit Policy Grant' });
    expect(screen.getByDisplayValue('Authenticated')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));

    await waitFor(() => {
      expect(api.admin.updatePolicyGrant).toHaveBeenCalledWith(1, expect.objectContaining({
        principalName: 'Authenticated',
        actions: 'view,edit',
      }));
    });
  });
});

describe('AdminSecurityPage — delete grant', () => {
  it('confirms then calls deletePolicyGrant by id', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    await switchToGrants();
    await screen.findByText('Authenticated');

    const row = screen.getByText('Authenticated').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: 'Delete' }));
    await screen.findByRole('heading', { name: 'Delete Policy Grant' });
    fireEvent.click(screen.getByRole('button', { name: 'Delete Grant' }));

    await waitFor(() => expect(api.admin.deletePolicyGrant).toHaveBeenCalledWith(1));
    await waitFor(() => expect(api.admin.listPolicyGrants).toHaveBeenCalledTimes(2));
  });

  it('cancels delete without calling the API', async () => {
    render(<AdminSecurityPage />);
    await screen.findByText('editors');
    await switchToGrants();
    await screen.findByText('Authenticated');
    const row = screen.getByText('Authenticated').closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: 'Delete' }));
    await screen.findByRole('heading', { name: 'Delete Policy Grant' });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Delete Policy Grant' })).toBeNull());
    expect(api.admin.deletePolicyGrant).not.toHaveBeenCalled();
  });
});
