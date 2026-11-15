import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  listMembers: vi.fn(), putMember: vi.fn(), removeMember: vi.fn(), updateProject: vi.fn(), archiveProject: vi.fn(),
}));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { listMembers, putMember, removeMember, updateProject, archiveProject } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { WorkProjectContext } from './useWorkProject';
import WorkSettings from './WorkSettings';

const PROJECT = { id: 1, key: 'PLAT', name: 'Platform', description: 'The platform', defaultAutonomy: 'supervised' };
const MEMBERS = [
  { principal: 'me-login', principalKind: 'user', role: 'lead', displayName: 'Me Myself' },
  { principal: 'bob', principalKind: 'user', role: 'member', displayName: 'Bob B' },
  { principal: 'Devs', principalKind: 'group', role: 'viewer', displayName: 'Devs' },
];
const reload = vi.fn();

function renderSettings({ role = 'lead', admin = false } = {}) {
  useAuth.mockReturnValue({ user: { authenticated: true, loginPrincipal: 'me-login', roles: admin ? ['Admin'] : [] } });
  render(
    <MemoryRouter initialEntries={['/work/PLAT/settings']}>
      <WorkProjectContext.Provider value={{ project: PROJECT, role, reload }}>
        <Routes>
          <Route path="/work/PLAT/settings" element={<WorkSettings />} />
          <Route path="/work/PLAT" element={<div>BOARD</div>} />
          <Route path="/work" element={<div>HOME</div>} />
        </Routes>
      </WorkProjectContext.Provider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  listMembers.mockResolvedValue(MEMBERS);
  putMember.mockResolvedValue(undefined);
  removeMember.mockResolvedValue(undefined);
  updateProject.mockResolvedValue({});
  archiveProject.mockResolvedValue(undefined);
  window.confirm = vi.fn();
});

describe('WorkSettings', () => {
  it.each(['member', 'viewer'])('hiddenFromNonLeads (%s is sent to the board)', async (role) => {
    renderSettings({ role });
    expect(await screen.findByText('BOARD')).toBeInTheDocument();
    expect(listMembers).not.toHaveBeenCalled();
  });

  it('lists members by display name with kind and role', async () => {
    renderSettings();
    const row = (await screen.findByText('Bob B')).closest('li');
    expect(within(row).getByText('user')).toBeInTheDocument();
    expect(screen.getByText('group')).toBeInTheDocument();
    expect(screen.queryByText('bob')).not.toBeInTheDocument();
    expect(screen.getByText('Takes effect in a later release')).toBeInTheDocument();
  });

  it('saves the project name and description', async () => {
    renderSettings();
    fireEvent.change(await screen.findByLabelText('Project name'), { target: { value: 'Platform 2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save details' }));
    await waitFor(() => expect(updateProject).toHaveBeenCalledWith('PLAT', { name: 'Platform 2', description: 'The platform' }));
    expect(reload).toHaveBeenCalled();
  });

  it('addMemberByUserOrGroup', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Login or group name'), { target: { value: 'carol' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    await waitFor(() => expect(putMember).toHaveBeenCalledWith('PLAT', { principal: 'carol', principalKind: 'user', role: 'member' }));
    fireEvent.change(screen.getByLabelText('Login or group name'), { target: { value: 'Ops' } });
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'group' } });
    fireEvent.change(screen.getByLabelText('Role for new member'), { target: { value: 'viewer' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    await waitFor(() => expect(putMember).toHaveBeenLastCalledWith('PLAT', { principal: 'Ops', principalKind: 'group', role: 'viewer' }));
  });

  it('shows the server message beside the field when a member is rejected', async () => {
    putMember.mockRejectedValue(Object.assign(new Error('No such user.'), { status: 400 }));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Login or group name'), { target: { value: 'ghost' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    expect(await screen.findByText('No such user.')).toBeInTheDocument();
  });

  it('changes another member role and removes a member', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Role of Bob B'), { target: { value: 'viewer' } });
    await waitFor(() => expect(putMember).toHaveBeenCalledWith('PLAT', { principal: 'bob', principalKind: 'user', role: 'viewer' }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove Bob B' }));
    await waitFor(() => expect(removeMember).toHaveBeenCalledWith('PLAT', { principal: 'bob', principalKind: 'user' }));
  });

  it('changingOwnLeadRoleAsksForConfirmation', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Role of Me Myself'), { target: { value: 'member' } });
    expect(putMember).not.toHaveBeenCalled();
    expect(screen.getByText(/You will lose access to these settings/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(putMember).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Role of Me Myself'), { target: { value: 'member' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, change my role' }));
    await waitFor(() => expect(putMember).toHaveBeenCalledWith('PLAT', { principal: 'me-login', principalKind: 'user', role: 'member' }));
  });

  it('asks before removing yourself', async () => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Remove Me Myself' }));
    expect(removeMember).not.toHaveBeenCalled();
    expect(screen.getByText(/You will lose access to these settings/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove me' }));
    await waitFor(() => expect(removeMember).toHaveBeenCalledWith('PLAT', { principal: 'me-login', principalKind: 'user' }));
  });

  it('hides archive from a lead who is not an admin', async () => {
    renderSettings({ admin: false });
    await screen.findByText('Bob B');
    expect(screen.queryByRole('button', { name: 'Archive project' })).not.toBeInTheDocument();
  });

  it('archiveAsksForConfirmationInline', async () => {
    renderSettings({ admin: true });
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Archive project' }));
    expect(window.confirm).not.toHaveBeenCalled();
    expect(archiveProject).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, archive PLAT' }));
    await waitFor(() => expect(archiveProject).toHaveBeenCalledWith('PLAT'));
    expect(await screen.findByText('HOME')).toBeInTheDocument();
  });

  it('shows the server reason in a toast when archive is refused', async () => {
    archiveProject.mockRejectedValue(Object.assign(new Error('Admins only.'), { status: 403 }));
    renderSettings({ admin: true });
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Archive project' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, archive PLAT' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Admins only.'));
  });

  it('shows an error with Retry when members fail to load', async () => {
    listMembers.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(MEMBERS);
    renderSettings();
    expect(await screen.findByText('boom')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Bob B')).toBeInTheDocument();
  });
});
