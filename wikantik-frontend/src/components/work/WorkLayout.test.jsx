import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({ getProject: vi.fn() }));
import { getProject } from '../../api/work';
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

beforeEach(() => { vi.clearAllMocks(); });

describe('WorkLayout', () => {
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
