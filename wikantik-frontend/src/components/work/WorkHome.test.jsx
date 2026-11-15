import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({ getMe: vi.fn(), createProject: vi.fn() }));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../../hooks/useCapabilities', () => ({ useCapabilities: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { getMe, createProject } from '../../api/work';
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
        <Route path="/work/:project" element={<div>BOARD</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => vi.clearAllMocks());

describe('WorkHome', () => {
  it('listsVisibleProjectsWithRole', async () => {
    renderHome();
    const link = await screen.findByRole('link', { name: 'PLAT Platform' });
    expect(link).toHaveAttribute('href', '/work/PLAT');
    expect(link.closest('li')).toHaveTextContent('member');
    expect(screen.queryByRole('link', { name: 'Products' })).not.toBeInTheDocument();
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
});
