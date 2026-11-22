import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useNavigate } from 'react-router-dom';

vi.mock('../../api/work', () => ({ getProject: vi.fn(), listSprints: vi.fn() }));
import { getProject, listSprints } from '../../api/work';
import WorkLayout from './WorkLayout';
import { useWorkProject } from './useWorkProject';

function Probe() {
  const { project, role } = useWorkProject();
  return <div data-testid="probe">{project.key}:{role}</div>;
}
const renderAt = (path) => render(
  <MemoryRouter initialEntries={[path]}>
    <Routes>
      <Route path="/work/:project" element={<WorkLayout />}>
        <Route index element={<Probe />} />
        <Route path="dates" element={<div>dates view</div>} />
        <Route path="log" element={<div>log view</div>} />
      </Route>
    </Routes>
  </MemoryRouter>);

beforeEach(() => { vi.clearAllMocks(); listSprints.mockResolvedValue([]); });

describe('WorkLayout', () => {
  it('a project with no sprint shows no Sprints tab to a member', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' });
    renderAt('/work/PLAT');
    await screen.findByTestId('probe');
    expect(listSprints).toHaveBeenCalledWith('PLAT');
    expect(screen.queryByRole('tab', { name: 'Sprints' })).toBeNull();
  });

  it('a lead sees the Sprints tab before the first sprint', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'lead' });
    renderAt('/work/PLAT');
    await screen.findByTestId('probe');
    expect(screen.getByRole('tab', { name: 'Sprints' })).toBeInTheDocument();
  });

  it('a member sees the Sprints tab once the project has a sprint', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' });
    listSprints.mockResolvedValue([{ id: 5, name: 'Sprint 1', state: 'planned' }]);
    renderAt('/work/PLAT');
    await screen.findByTestId('probe');
    expect(screen.getByRole('tab', { name: 'Sprints' })).toBeInTheDocument();
  });

  it('does not wait for the sprint list', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' });
    listSprints.mockReturnValue(new Promise(() => {}));
    renderAt('/work/PLAT');
    expect(await screen.findByTestId('probe')).toBeInTheDocument();
  });

  it('shows a failed sprint load with a retry that recovers', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'lead' });
    listSprints.mockRejectedValueOnce(new Error('down'));
    renderAt('/work/PLAT');
    expect(await screen.findByText(/Could not load the sprints: down/)).toBeInTheDocument();
    listSprints.mockResolvedValue([{ id: 5, name: 'Sprint 1', state: 'planned' }]);
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.queryByText(/Could not load the sprints/)).toBeNull());
  });

  it('a failed reload keeps the last good sprint list', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' });
    listSprints.mockResolvedValue([{ id: 5, name: 'Sprint 1', state: 'planned' }]);
    function Reloader() {
      const { sprints, reloadSprints } = useWorkProject();
      return <button type="button" onClick={reloadSprints}>reload {sprints.length}</button>;
    }
    render(
      <MemoryRouter initialEntries={['/work/PLAT']}>
        <Routes><Route path="/work/:project" element={<WorkLayout />}><Route index element={<Reloader />} /></Route></Routes>
      </MemoryRouter>);
    const button = await screen.findByRole('button', { name: 'reload 1' });
    listSprints.mockRejectedValue(new Error('blip'));
    fireEvent.click(button);
    expect(await screen.findByText(/Could not load the sprints: blip/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'reload 1' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Sprints' })).toBeInTheDocument();
  });

  it('still loads when the sprint list fails', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' });
    listSprints.mockRejectedValue(new Error('down'));
    renderAt('/work/PLAT');
    expect(await screen.findByTestId('probe')).toBeInTheDocument();
  });

  it('loads the project and exposes project and role to children', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'lead' });
    renderAt('/work/PLAT');
    expect(await screen.findByTestId('probe')).toHaveTextContent('PLAT:lead');
    expect(screen.getByRole('heading', { name: /PLAT Platform/ })).toBeInTheDocument();
    expect(getProject).toHaveBeenCalledWith('PLAT');
    for (const name of ['Board', 'Dates', 'Activity', 'Assignments and claims', 'Settings']) {
      expect(screen.getByRole('tab', { name })).toBeInTheDocument();
    }
    expect(screen.getByRole('tab', { name: 'Board' })).toHaveAttribute('aria-selected', 'true');
  });

  it('navigates between tabs', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' });
    renderAt('/work/PLAT');
    await screen.findByTestId('probe');
    fireEvent.click(screen.getByRole('tab', { name: 'Dates' }));
    expect(await screen.findByText('dates view')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Dates' })).toHaveAttribute('aria-selected', 'true');
  });

  it('keeps the project shell around the assignment log and selects its tab', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' });
    renderAt('/work/PLAT');
    await screen.findByTestId('probe');
    fireEvent.click(screen.getByRole('tab', { name: 'Assignments and claims' }));
    expect(await screen.findByText('log view')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Assignments and claims' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Board' })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByRole('tab', { name: 'Settings' })).toBeInTheDocument();
  });

  it('shows one neutral state on 404', async () => {
    getProject.mockRejectedValue(Object.assign(new Error('Not found.'), { status: 404 }));
    renderAt('/work/NOPE');
    expect(await screen.findByText('Not found')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('tab')).not.toBeInTheDocument());
  });
});

describe('WorkLayout edges', () => {
  const renderWithNav = (path) => render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/work/:project" element={<WorkLayout />}>
          <Route index element={<div>board view</div>} />
          <Route path="dates" element={<div>dates view</div>} />
          <Route path="whatever" element={<div>mystery view</div>} />
        </Route>
      </Routes>
    </MemoryRouter>);

  it('shows the server message when the load fails for another reason than 404', async () => {
    getProject.mockRejectedValue(Object.assign(new Error('Database is down'), { status: 500 }));
    renderAt('/work/PLAT');
    expect(await screen.findByText('Database is down')).toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
  });

  it('says it could not load the project when the error has no message', async () => {
    getProject.mockRejectedValue({ status: 500 });
    renderAt('/work/PLAT');
    expect(await screen.findByText('Could not load the project.')).toBeInTheDocument();
  });

  it('shows a read-only banner for an archived project and none otherwise', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'lead', archived: true });
    renderAt('/work/PLAT');
    await screen.findByTestId('probe');
    expect(screen.getByRole('status')).toHaveTextContent('This project is archived. It is read-only.');
  });

  it('shows no banner for a live project', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'lead' });
    renderAt('/work/PLAT');
    await screen.findByTestId('probe');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('goes back to the bare project path when the Board tab is chosen', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' });
    renderWithNav('/work/PLAT/dates');
    expect(await screen.findByText('dates view')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Dates' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(screen.getByRole('tab', { name: 'Board' }));
    expect(await screen.findByText('board view')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Board' })).toHaveAttribute('aria-selected', 'true');
  });

  it('selects the Board tab for a path that is not one of the tabs', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' });
    renderWithNav('/work/PLAT/whatever');
    expect(await screen.findByText('mystery view')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Board' })).toHaveAttribute('aria-selected', 'true');
  });

  it('shows the spinner, not the old project, while a different project loads', async () => {
    getProject.mockImplementation((k) => (k === 'PLAT'
      ? Promise.resolve({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' })
      : new Promise(() => {})));
    function Jump() {
      const nav = useNavigate();
      return <button type="button" onClick={() => nav('/work/OPS')}>jump</button>;
    }
    render(
      <MemoryRouter initialEntries={['/work/PLAT']}>
        <Jump />
        <Routes>
          <Route path="/work/:project" element={<WorkLayout />}><Route index element={<Probe />} /></Route>
        </Routes>
      </MemoryRouter>);
    await screen.findByTestId('probe');
    fireEvent.click(screen.getByRole('button', { name: 'jump' }));
    expect(await screen.findByText('Loading project…')).toBeInTheDocument();
    expect(screen.queryByTestId('probe')).not.toBeInTheDocument();
    expect(getProject).toHaveBeenLastCalledWith('OPS');
  });

  it('shows the Flow tab to a plain member', async () => {
    getProject.mockResolvedValue({ id: 1, key: 'PLAT', name: 'Platform', role: 'member' });
    renderAt('/work/PLAT');
    await screen.findByTestId('probe');
    expect(screen.getByRole('tab', { name: 'Flow' })).toBeInTheDocument();
  });
});
