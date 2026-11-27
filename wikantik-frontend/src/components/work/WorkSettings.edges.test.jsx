import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getMe: vi.fn(() => Promise.resolve({ projects: [], notify: { webhooksAvailable: false } })),
  listMembers: vi.fn(), listAgents: vi.fn(), putMember: vi.fn(), removeMember: vi.fn(), updateProject: vi.fn(), archiveProject: vi.fn(), listProducts: vi.fn(),
}));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { listMembers, listAgents, putMember, removeMember, updateProject, archiveProject, listProducts } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { WorkProjectContext } from './useWorkProject';
import WorkSettings from './WorkSettings';

const PROJECT = { id: 1, key: 'PLAT', name: 'Platform', description: 'The platform', defaultAutonomy: 'supervised' };
const ME = { principal: 'Me Myself', principalKind: 'user', role: 'lead', displayName: 'Me Myself', self: true };
const BOB = { principal: 'bob', principalKind: 'user', role: 'member', displayName: 'Bob B' };
const reload = vi.fn();
const refusal = (message, status) => Object.assign(new Error(message), { status });

function renderSettings({ project = PROJECT, admin = false } = {}) {
  useAuth.mockReturnValue({ user: { authenticated: true, roles: admin ? ['Admin'] : [] }, loading: false });
  return render(
    <MemoryRouter initialEntries={['/work/PLAT/settings']}>
      <WorkProjectContext.Provider value={{ project, role: 'lead', reload }}>
        <Routes>
          <Route path="/work/PLAT/settings" element={<WorkSettings />} />
          <Route path="/work" element={<div>HOME</div>} />
        </Routes>
      </WorkProjectContext.Provider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  listMembers.mockResolvedValue([ME, BOB]);
  listAgents.mockResolvedValue([{ id: 1, name: 'triage-bot', retiredAt: null }]);
  putMember.mockResolvedValue(undefined);
  removeMember.mockResolvedValue(undefined);
  updateProject.mockResolvedValue({});
  listProducts.mockResolvedValue([]);
  archiveProject.mockResolvedValue(undefined);
});

describe('WorkSettings edges', () => {
  it('shows a spinner while the project is not loaded yet', () => {
    renderSettings({ project: null });
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(listMembers).not.toHaveBeenCalled();
  });

  it('a project with no name, description or default autonomy shows empty fields and the delegated default', async () => {
    renderSettings({ project: { id: 1, key: 'PLAT' } });
    expect(await screen.findByLabelText('Project name')).toHaveValue('');
    expect(screen.getByLabelText('Description')).toHaveValue('');
    expect(screen.getByLabelText('Default autonomy')).toHaveValue('delegated');
    expect(screen.getByRole('button', { name: 'Save details' })).toBeDisabled();
  });

  it('shows a refusal to save the details in a toast, in the server words, and keeps the typed name', async () => {
    updateProject.mockRejectedValue(refusal('Only leads edit details.', 403));
    renderSettings();
    fireEvent.change(await screen.findByLabelText('Project name'), { target: { value: 'Renamed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save details' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Only leads edit details.'));
    expect(screen.getByLabelText('Project name')).toHaveValue('Renamed');
    expect(reload).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Save details' })).toBeEnabled();
  });

  it('shows another save failure on the form, and a plain default when the server gave no words', async () => {
    updateProject.mockRejectedValueOnce(refusal('Name is taken.', 409)).mockRejectedValueOnce(new Error(''));
    renderSettings();
    await screen.findByLabelText('Project name');
    fireEvent.click(screen.getByRole('button', { name: 'Save details' }));
    expect(await screen.findByText('Name is taken.')).toBeInTheDocument();
    expect(toast.error).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Save details' }));
    expect(await screen.findByText('Could not save.')).toBeInTheDocument();
  });

  it('saves an edited description along with the unchanged name', async () => {
    renderSettings();
    fireEvent.change(await screen.findByLabelText('Description'), { target: { value: 'New words' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save details' }));
    await waitFor(() => expect(updateProject).toHaveBeenCalledWith('PLAT', { name: 'Platform', description: 'New words' }));
  });

  it('says the project has no members when the server returns none or nothing', async () => {
    listMembers.mockResolvedValue(null);
    renderSettings();
    expect(await screen.findByText('This project has no members.')).toBeInTheDocument();
  });

  it('names a member with no display name "Unnamed member" and labels their controls "member"', async () => {
    listMembers.mockResolvedValue([ME, { principal: 'x', principalKind: 'group', role: 'viewer' }]);
    renderSettings();
    const row = (await screen.findByText('Unnamed member')).closest('li');
    expect(within(row).getByLabelText('Role of member')).toHaveValue('viewer');
    expect(within(row).getByRole('button', { name: 'Remove member' })).toBeInTheDocument();
  });

  it.each([
    ['user', 'Enter a display name.'],
    ['group', 'Enter a group name.'],
    ['agent', 'Choose an agent.'],
  ])('refuses to add a blank %s and says what to enter', async (kind, text) => {
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: kind } });
    if (kind === 'agent') await screen.findByLabelText('Agent to add');
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    expect(screen.getByRole('alert')).toHaveTextContent(text);
    expect(putMember).not.toHaveBeenCalled();
  });

  it('a second submit of the add form while the first is in flight is ignored', async () => {
    let release;
    putMember.mockReturnValue(new Promise((r) => { release = r; }));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Display name or group name'), { target: { value: 'carol' } });
    const form = screen.getByRole('button', { name: 'Add member' }).closest('form');
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(putMember).toHaveBeenCalledTimes(1);
    release();
    await waitFor(() => expect(screen.getByLabelText('Display name or group name')).toHaveValue(''));
  });

  it('a refused add by a user without the right shows the server words by the form, not in a toast', async () => {
    putMember.mockRejectedValue(refusal('Leads only.', 403));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Display name or group name'), { target: { value: 'carol' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Leads only.');
    expect(toast.error).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Display name or group name')).toHaveValue('carol');
  });

  it('a failed add with no server words says so plainly and keeps the typed name', async () => {
    putMember.mockRejectedValue(new Error(''));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Display name or group name'), { target: { value: 'carol' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }));
    expect(await screen.findByText('Could not add the member.')).toBeInTheDocument();
    expect(screen.getByLabelText('Display name or group name')).toHaveValue('carol');
  });

  it('a failed agent list load is reported beside the add form', async () => {
    listAgents.mockRejectedValue(new Error('registry down'));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } });
    expect(await screen.findByText('Could not load the agents.')).toBeInTheDocument();
    expect(screen.getByLabelText('Agent to add')).toBeDisabled();
  });

  it('offers no agents when the registry returns nothing', async () => {
    listAgents.mockResolvedValue(null);
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'agent' } });
    await waitFor(() => expect(screen.getByLabelText('Agent to add')).toBeEnabled());
    expect(within(screen.getByLabelText('Agent to add')).getAllByRole('option').map((o) => o.textContent)).toEqual(['Choose an agent']);
  });

  it('a failed role change shows the server words on that member\'s row and re-reads the list', async () => {
    putMember.mockRejectedValue(refusal('Last lead cannot be demoted.', 409));
    renderSettings();
    await screen.findByText('Bob B');
    listMembers.mockClear();
    fireEvent.change(screen.getByLabelText('Role of Bob B'), { target: { value: 'lead' } });
    const row = screen.getByText('Bob B').closest('li');
    expect(await within(row).findByRole('alert')).toHaveTextContent('Last lead cannot be demoted.');
    expect(toast.error).not.toHaveBeenCalled();
    await waitFor(() => expect(listMembers).toHaveBeenCalledTimes(1));
    // The message stays after the list is re-read, and goes when the next change works.
    expect(within(screen.getByText('Bob B').closest('li')).getByRole('alert')).toBeInTheDocument();
    putMember.mockResolvedValue({});
    fireEvent.change(screen.getByLabelText('Role of Bob B'), { target: { value: 'viewer' } });
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });

  it('a failed role change with no server words uses the plain default', async () => {
    putMember.mockRejectedValue(new Error(''));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Role of Bob B'), { target: { value: 'lead' } });
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not change the member.');
  });

  it('a failed removal with no server words uses the plain default', async () => {
    removeMember.mockRejectedValue(new Error(''));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Remove Bob B' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove Bob B' }));
    expect(await within(screen.getByRole('alertdialog')).findByRole('alert')).toHaveTextContent('Could not change the member.');
  });

  it('a failed change of your own role keeps the member list on screen', async () => {
    putMember.mockRejectedValue(refusal('Not allowed.', 403));
    listMembers.mockResolvedValueOnce([ME, BOB]).mockRejectedValue(refusal('Forbidden', 403));
    renderSettings();
    await screen.findByText('Bob B');
    fireEvent.change(screen.getByLabelText('Role of Me Myself'), { target: { value: 'member' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, change my role' }));
    expect(await within(screen.getByText('Me Myself').closest('li')).findByRole('alert')).toHaveTextContent('Not allowed.');
    await waitFor(() => expect(listMembers).toHaveBeenCalledTimes(2));
    expect(screen.getByText('Bob B')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('removing an agent with no display name asks about "member" and sends the agent identity', async () => {
    const agent = { principal: 'build-bot', principalKind: 'agent', role: 'member' };
    listMembers.mockResolvedValue([ME, agent]);
    renderSettings();
    fireEvent.click(await screen.findByRole('button', { name: 'Remove member' }));
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Remove member from Platform?');
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove member' }));
    await waitFor(() => expect(removeMember).toHaveBeenCalledWith('PLAT', { principal: 'build-bot', principalKind: 'agent' }));
  });

  it('a refused archive without server words says so plainly and closes the confirmation', async () => {
    archiveProject.mockRejectedValue(new Error(''));
    renderSettings({ admin: true });
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Archive project' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, archive PLAT' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Could not archive the project.'));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(screen.getByRole('button', { name: 'Archive project' })).toBeInTheDocument();
  });

  it('cancelling the archive confirmation archives nothing', async () => {
    renderSettings({ admin: true });
    await screen.findByText('Bob B');
    fireEvent.click(screen.getByRole('button', { name: 'Archive project' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Cancel' }));
    expect(archiveProject).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Archive project' })).toBeInTheDocument();
  });
});
