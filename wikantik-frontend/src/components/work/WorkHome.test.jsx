import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({ getMe: vi.fn(), createProject: vi.fn(), acknowledgeItem: vi.fn(() => Promise.resolve({})) }));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../../hooks/useCapabilities', () => ({ useCapabilities: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { getMe, createProject, acknowledgeItem } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import { useCapabilities } from '../../hooks/useCapabilities';
import WorkHome from './WorkHome';

const ME = {
  projects: [{ id: 1, key: 'PLAT', name: 'Platform', role: 'member' }],
  waitingOn: [{ id: 5, key: 'PLAT-5', projectId: 1, title: 'Review me' }],
};

function renderHome({ admin = false, me = ME, work = true } = {}) {
  useAuth.mockReturnValue({ user: { authenticated: true, roles: admin ? ['Admin'] : [] } });
  useCapabilities.mockReturnValue({ capabilities: { work }, loading: false });
  getMe.mockResolvedValue(me);
  render(
    <MemoryRouter initialEntries={['/work']}>
      <Routes>
        <Route path="/work" element={<WorkHome />} />
        <Route path="/work/log" element={<div>LOG PAGE</div>} />
        <Route path="/work/:project" element={<div>BOARD</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => vi.clearAllMocks());

describe('WorkHome', () => {
  it('linksToTheAssignmentLog', async () => {
    renderHome();
    const link = await screen.findByRole('link', { name: 'Assignments and claims' });
    expect(link).toHaveAttribute('href', '/work/log');
    fireEvent.click(link);
    expect(await screen.findByText('LOG PAGE')).toBeInTheDocument();
  });

  it('hasNoLogLinkWhenTheViewerSeesNoProject', async () => {
    renderHome({ me: { projects: [], waitingOn: [] } });
    await screen.findByText('You are not a member of any project yet.');
    expect(screen.queryByRole('link', { name: 'Assignments and claims' })).not.toBeInTheDocument();
  });

  it('listsVisibleProjectsWithRole', async () => {
    renderHome();
    const link = await screen.findByRole('link', { name: 'PLAT Platform' });
    expect(link).toHaveAttribute('href', '/work/PLAT');
    expect(link.closest('li')).toHaveTextContent('member');
    expect(screen.queryByRole('link', { name: 'Products' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Agents & queues' })).toHaveAttribute('href', '/work/agents');
  });

  it('showsWaitingOnYou', async () => {
    renderHome();
    expect(await screen.findByText(/Waiting on you \(1\)/)).toBeInTheDocument();
    const row = screen.getByRole('link', { name: 'PLAT-5' });
    expect(row).toHaveAttribute('href', '/work/item/PLAT-5');
    expect(row.closest('li')).toHaveTextContent('Review me');
    expect(row.closest('li')).toHaveTextContent('PLAT');
  });

  it('adminSeesCreateProject', async () => {
    renderHome({ admin: true });
    expect(await screen.findByRole('link', { name: 'Products' })).toHaveAttribute('href', '/work/products');
    expect(screen.getByRole('link', { name: 'Agents & queues' })).toHaveAttribute('href', '/work/agents');
    fireEvent.click(screen.getByRole('button', { name: 'New project' }));
    for (const bad of ['p', 'plat', '1PLAT', 'TOOLONGKEY1X', 'PL-AT']) {
      fireEvent.change(screen.getByLabelText('Key'), { target: { value: bad } });
      fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Name' } });
      fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
      expect(await screen.findByText(/Key must be 2 to 10 characters/)).toBeInTheDocument();
    }
    expect(createProject).not.toHaveBeenCalled();
    createProject.mockResolvedValue({ key: 'NEW' });
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'NEW' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
    await waitFor(() => expect(createProject).toHaveBeenCalledWith({ key: 'NEW', name: 'Name', description: '' }));
    expect(await screen.findByText('BOARD')).toBeInTheDocument();
  });

  it('does not offer New project to a non-admin', async () => {
    renderHome();
    await screen.findByText('PLAT Platform');
    expect(screen.queryByRole('button', { name: 'New project' })).not.toBeInTheDocument();
  });

  it('emptyStateForUserWithNoProjects', async () => {
    renderHome({ me: { projects: [], waitingOn: [] } });
    const empty = await screen.findByText(/not a member of any project/);
    expect(empty).toBeInTheDocument();
    expect(screen.queryByTestId('waiting-lane')).not.toBeInTheDocument();
    expect(screen.queryByText(/PLAT/)).not.toBeInTheDocument();
  });

  it('disabledFeatureShowsNotEnabled', () => {
    renderHome({ work: false });
    expect(screen.getByText('Work management is not enabled')).toBeInTheDocument();
    expect(getMe).not.toHaveBeenCalled();
  });

  it('shows an error with Retry', async () => {
    useAuth.mockReturnValue({ user: { authenticated: true, roles: [] } });
    useCapabilities.mockReturnValue({ capabilities: { work: true }, loading: false });
    getMe.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(ME);
    render(<MemoryRouter><WorkHome /></MemoryRouter>);
    expect(await screen.findByText('boom')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('PLAT Platform')).toBeInTheDocument();
  });

  it('waits for the signed-in user before showing admin controls', async () => {
    useAuth.mockReturnValue({ user: null, loading: true });
    useCapabilities.mockReturnValue({ capabilities: { work: true }, loading: false });
    getMe.mockResolvedValue(ME);
    render(<MemoryRouter><WorkHome /></MemoryRouter>);
    await waitFor(() => expect(getMe).toHaveBeenCalled());
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText('PLAT Platform')).not.toBeInTheDocument();
  });

  it('lists archived projects apart, under Archived, still reachable', async () => {
    renderHome({ me: { waitingOn: [], projects: [
      { id: 1, key: 'PLAT', name: 'Platform', role: 'member', archived: false },
      { id: 2, key: 'OLD', name: 'Old one', role: 'lead', archived: true }] } });
    await screen.findByRole('link', { name: 'PLAT Platform' });
    const archived = screen.getByRole('region', { name: 'Archived projects' });
    expect(within(archived).getByRole('link', { name: 'OLD Old one' })).toHaveAttribute('href', '/work/OLD');
    expect(within(archived).queryByRole('link', { name: 'PLAT Platform' })).toBeNull();
  });

  it('acknowledges work done without the viewer from the home page and reloads', async () => {
    renderHome({ me: { projects: ME.projects, waitingOn: [
      { id: 9, key: 'PLAT-9', projectId: 1, title: 'Restarted the indexer', waiting: { reason: 'acknowledge' } }] } });
    fireEvent.click(await screen.findByRole('button', { name: 'Acknowledge PLAT-9' }));
    await waitFor(() => expect(acknowledgeItem).toHaveBeenCalledWith('PLAT-9'));
    await waitFor(() => expect(getMe).toHaveBeenCalledTimes(2));
  });

  it('lists the deployments waiting for the viewer\'s approval and links to Deployments', async () => {
    renderHome({ me: { projects: ME.projects, waitingOn: [], deploymentsAwaiting: [
      { id: 4, product: 'wikantik', version: '2.5.26', environment: 'production', state: 'requested', itemCount: 3 }] } });
    const lane = await screen.findByTestId('deployments-awaiting');
    expect(within(lane).getByText('Deployments waiting for your approval (1)')).toBeInTheDocument();
    expect(within(lane).getByRole('link', { name: 'wikantik 2.5.26' })).toHaveAttribute('href', '/work/deployments/4');
    expect(lane).toHaveTextContent('production · 3 items');
    expect(screen.getByRole('link', { name: 'Deployments' })).toHaveAttribute('href', '/work/deployments');
  });

  it('shows no approvals lane when nothing waits', async () => {
    renderHome();
    await screen.findByText(/Review me/);
    expect(screen.queryByTestId('deployments-awaiting')).toBeNull();
  });

  it('waitingShowsWhoHandedOff: the lane says who handed the item over and their note', async () => {
    renderHome({ me: { projects: ME.projects, waitingOn: [
      { id: 5, key: 'PLAT-5', projectId: 1, title: 'Review me',
        waiting: { reason: 'assigned', byName: 'codex@docker2', byKind: 'agent', note: 'tests pass', at: '2026-11-16T10:00:00Z' } },
      { id: 6, key: 'PLAT-6', projectId: 1, title: 'Look here', waiting: { reason: 'mentioned', byName: 'Jane Doe', byKind: 'human', note: null, at: null } },
    ] } });
    const lane = await screen.findByTestId('waiting-lane');
    expect(lane).toHaveTextContent('Assigned by codex@docker2 (agent)');
    expect(lane).toHaveTextContent('tests pass');
    expect(lane).toHaveTextContent('Mentioned by Jane Doe');
  });

  const openForm = async () => {
    renderHome({ admin: true });
    fireEvent.click(await screen.findByRole('button', { name: 'New project' }));
  };

  it('asks for a name before creating a project', async () => {
    await openForm();
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'NEW' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a name.');
    expect(createProject).not.toHaveBeenCalled();
  });

  it('Cancel closes the new-project form and offers New project again', async () => {
    await openForm();
    expect(screen.queryByRole('button', { name: 'New project' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByLabelText('Key')).toBeNull();
    expect(screen.getByRole('button', { name: 'New project' })).toBeInTheDocument();
  });

  it('goes to the typed key when the server reply names no project, and toasts success', async () => {
    await openForm();
    createProject.mockResolvedValue(undefined);
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'NEW' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: ' Fresh ' } });
    fireEvent.change(screen.getByLabelText('Description'), { target: { value: ' about it ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
    await waitFor(() => expect(createProject).toHaveBeenCalledWith({ key: 'NEW', name: 'Fresh', description: 'about it' }));
    expect(await screen.findByText('BOARD')).toBeInTheDocument();
    expect(toast.success).toHaveBeenCalledWith('Created NEW');
  });

  it('toasts a permission refusal and shows other failures in the form, re-enabling the button', async () => {
    await openForm();
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'NEW' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Fresh' } });
    createProject.mockRejectedValueOnce(Object.assign(new Error('Only admins may do that.'), { status: 403 }));
    fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Only admins may do that.'));
    expect(screen.queryByRole('alert')).toBeNull();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create project' })).not.toBeDisabled());
    createProject.mockRejectedValueOnce(Object.assign(new Error('Key PLAT is taken.'), { status: 409 }));
    fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Key PLAT is taken.');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create project' })).not.toBeDisabled());
    createProject.mockRejectedValueOnce({});
    fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Could not create the project.'));
  });

  it('says "item" for a single item in a deployment awaiting approval', async () => {
    renderHome({ me: { projects: ME.projects, deploymentsAwaiting: [
      { id: 4, product: 'wikantik', version: '2.5.26', environment: 'staging', itemCount: 1 }] } });
    const lane = await screen.findByTestId('deployments-awaiting');
    expect(lane).toHaveTextContent('staging · 1 item');
    expect(lane).not.toHaveTextContent('1 items');
  });

  it('copes with a reply that lists nothing at all', async () => {
    renderHome({ me: {} });
    expect(await screen.findByText('You are not a member of any project yet.')).toBeInTheDocument();
  });

  it('says there are no active projects when only archived ones remain', async () => {
    renderHome({ me: { projects: [{ id: 2, key: 'OLD', name: 'Old one', role: 'lead', archived: true }] } });
    expect(await screen.findByText('You have no active projects.')).toBeInTheDocument();
  });

  it('omits the project suffix for a waiting item from a project the viewer cannot list, and logs a failed acknowledge', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    acknowledgeItem.mockRejectedValueOnce(new Error('nope'));
    renderHome({ me: { projects: ME.projects, waitingOn: [
      { id: 9, key: 'ZZ-9', projectId: 99, title: 'Elsewhere', waiting: { reason: 'acknowledge' } }] } });
    const row = (await screen.findByRole('link', { name: 'ZZ-9' })).closest('li');
    expect(row).not.toHaveTextContent('PLAT');
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge ZZ-9' }));
    await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkHome: could not acknowledge', 'ZZ-9', expect.any(Error)));
    expect(getMe).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });
});
