import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({ listItems: vi.fn() }));
import { listItems } from '../../api/work';
import { WorkProjectContext } from './useWorkProject';
import WorkDates from './WorkDates';

const item = (n, dueOn, extra = {}) => ({
  id: n, key: `PLAT-${n}`, level: 'task', title: `Item ${n}`, status: 'ready', priority: 2, dueOn,
  assigneeName: 'Ada Lovelace', ...extra,
});

function renderDates() {
  render(
    <MemoryRouter>
      <WorkProjectContext.Provider value={{ project: { id: 1, key: 'PLAT' }, role: 'member', reload: vi.fn() }}>
        <WorkDates />
      </WorkProjectContext.Provider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 10, 12, 10, 0)); // Thursday 2026-11-12, local
});
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });

describe('WorkDates', () => {
  it('groupsIntoOverdueThisWeekLaterNoDate', async () => {
    listItems.mockResolvedValue([
      item(1, '2026-11-01'), item(2, '2026-11-14'), item(3, '2026-12-01'), item(4, null),
    ]);
    renderDates();
    const group = async (name) => within(await screen.findByRole('region', { name }));
    expect((await group('Overdue')).getByText('Item 1')).toBeInTheDocument();
    expect((await group('This week')).getByText('Item 2')).toBeInTheDocument();
    expect((await group('Later')).getByText('Item 3')).toBeInTheDocument();
    const none = await group('No date');
    expect(none.getByText('Item 4')).toBeInTheDocument();
    expect(none.getByRole('link', { name: 'PLAT-4' })).toHaveAttribute('href', '/work/item/PLAT-4');
    expect(listItems).toHaveBeenCalledWith(expect.objectContaining({ project: 'PLAT', order: 'due', limit: 200 }));
    expect(screen.getAllByText('Ada Lovelace').length).toBe(4);
  });

  it('excludesDoneAndCancelled', async () => {
    listItems.mockResolvedValue([
      item(1, '2026-11-01', { status: 'done' }), item(2, '2026-11-01', { status: 'cancelled' }),
      item(3, '2026-11-01', { level: 'epic', status: 'in_progress' }),
    ]);
    renderDates();
    expect(await screen.findByText('Item 3')).toBeInTheDocument();
    expect(screen.queryByText('Item 1')).not.toBeInTheDocument();
    expect(screen.queryByText('Item 2')).not.toBeInTheDocument();
  });

  it('overdueSortedOldestFirst', async () => {
    listItems.mockResolvedValue([item(1, '2026-11-10'), item(2, '2026-10-01'), item(3, '2026-11-05')]);
    renderDates();
    const region = within(await screen.findByRole('region', { name: 'Overdue' }));
    expect(region.getAllByRole('link').map((a) => a.textContent)).toEqual(['PLAT-2', 'PLAT-3', 'PLAT-1']);
  });

  it('says the list is partial when it is exactly at the limit', async () => {
    listItems.mockResolvedValue(Array.from({ length: 200 }, (_, i) => item(i + 1, '2026-12-01')));
    renderDates();
    expect(await screen.findByText('Showing the first 200 items')).toBeInTheDocument();
  });

  it('shows an empty state', async () => {
    listItems.mockResolvedValue([]);
    renderDates();
    expect(await screen.findByText(/Nothing open/)).toBeInTheDocument();
  });

  it('shows an error with Retry', async () => {
    listItems.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce([item(1, null)]);
    renderDates();
    expect(await screen.findByText('boom')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Item 1')).toBeInTheDocument();
  });
});
