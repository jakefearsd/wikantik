import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../../api/work', () => ({ getSprintReport: vi.fn() }));
import { getSprintReport } from '../../api/work';
import WorkSprintReport from './WorkSprintReport';

const DAY = (date, remaining) => ({ date, remainingPoints: remaining, remainingItems: 1, scopePoints: 8, scopeItems: 2 });
const SPRINT = {
  id: 7, name: 'Sprint 12', state: 'closed', startsOn: '2026-11-23', endsOn: '2026-11-25', goal: 'Uploads',
  committed: { items: 2, points: 8 }, completed: { items: 1, points: 3 }, current: { items: 2, points: 8, doneItems: 1, donePoints: 3 },
};
const renderReport = () => render(
  <MemoryRouter initialEntries={['/work/PLAT/sprints/7']}>
    <Routes><Route path="/work/:project/sprints/:id" element={<WorkSprintReport />} /></Routes>
  </MemoryRouter>);

beforeEach(() => vi.clearAllMocks());

describe('WorkSprintReport', () => {
  it('shows completed against committed', async () => {
    getSprintReport.mockResolvedValue({ sprint: SPRINT, days: [DAY('2026-11-23', 8), DAY('2026-11-24', 5)], added: [], removed: [] });
    const { container } = renderReport();
    expect(await screen.findByText('Completed 1 of 2 items, 3 of 8 points')).toBeInTheDocument();
    expect(getSprintReport).toHaveBeenCalledWith('7');
    expect(container.querySelectorAll('circle[data-series="remaining"]')).toHaveLength(2);
    expect(screen.getByRole('link', { name: 'Sprints' })).toHaveAttribute('href', '/work/PLAT/sprints');
    expect(screen.getByText('Nothing was added.')).toBeInTheDocument();
    expect(screen.getByText(/· closed · Nov 23 – Nov 25 2026/)).toBeInTheDocument();
  });

  it('lists added and removed items', async () => {
    getSprintReport.mockResolvedValue({
      sprint: SPRINT, days: [],
      added: [{ key: 'PLAT-21', title: 'Billing export' }], removed: [{ key: 'PLAT-22', title: 'Old idea' }],
    });
    renderReport();
    expect(await screen.findByRole('link', { name: 'PLAT-21' })).toHaveAttribute('href', '/work/item/PLAT-21');
    expect(screen.getByText('Billing export')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'PLAT-22' })).toBeInTheDocument();
    expect(screen.getByText('No burndown yet: the sprint has not started.')).toBeInTheDocument();
  });

  it('shows the current totals of an active sprint', async () => {
    getSprintReport.mockResolvedValue({
      sprint: { ...SPRINT, state: 'active', completed: null, current: { items: 9, points: 21, doneItems: 5, donePoints: 13 } },
      days: [], added: [], removed: [],
    });
    renderReport();
    expect(await screen.findByText('Now: 5 of 9 items done, 13 of 21 points.')).toBeInTheDocument();
    expect(screen.queryByText(/^Completed/)).toBeNull();
  });

  it('says Not found on a 404', async () => {
    getSprintReport.mockRejectedValue(Object.assign(new Error('nope'), { status: 404 }));
    renderReport();
    expect(await screen.findByText('Not found')).toBeInTheDocument();
  });
});
