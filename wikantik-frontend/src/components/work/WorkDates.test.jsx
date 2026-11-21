import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({ listItems: vi.fn(), listLabels: vi.fn(() => Promise.resolve([])) }));
import { listItems } from '../../api/work';
import { WorkProjectContext } from './useWorkProject';
import WorkDates from './WorkDates';

const item = (n, dueOn, extra = {}) => ({
  id: n, key: `PLAT-${n}`, level: 'task', title: `Item ${n}`, status: 'ready', priority: 2, dueOn,
  assigneeName: 'Ada Lovelace', ...extra,
});

function renderDates(url = '/') {
  render(
    <MemoryRouter initialEntries={[url]}>
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

describe('WorkDates edges', () => {
  const deferred = () => { let resolve; let reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
  const mount = (projectKey) => (
    <MemoryRouter>
      <WorkProjectContext.Provider value={{ project: { id: 1, key: projectKey }, role: 'member', reload: vi.fn() }}>
        <WorkDates />
      </WorkProjectContext.Provider>
    </MemoryRouter>);

  it('shows the due date, the priority label, and Unassigned for an item nobody holds', async () => {
    listItems.mockResolvedValue([item(1, '2026-11-14', { assigneeName: null, priority: 1 })]);
    renderDates();
    const row = (await screen.findByText('Item 1')).closest('li');
    expect(row).toHaveTextContent('Nov 14, 2026');
    expect(row).toHaveTextContent('P1 – highest');
    expect(row).toHaveTextContent('Unassigned');
    expect(row).not.toHaveClass('overdue');
  });

  it('marks only overdue rows, and says No date for an item without one', async () => {
    listItems.mockResolvedValue([item(1, '2026-11-01'), item(2, null)]);
    renderDates();
    expect((await screen.findByText('Item 1')).closest('li')).toHaveClass('overdue');
    expect(screen.getByText('Item 1').closest('li')).toHaveTextContent('Nov 1, 2026');
    const none = screen.getByText('Item 2').closest('li');
    expect(none).not.toHaveClass('overdue');
    expect(within(none).getByText('No date', { selector: '.work-date-due' })).toBeInTheDocument();
  });

  it('falls back to P-number for a priority outside 1 to 4', async () => {
    listItems.mockResolvedValue([item(1, null, { priority: 9 })]);
    renderDates();
    expect((await screen.findByText('Item 1')).closest('li')).toHaveTextContent('P9');
  });

  it('treats an empty answer from the server as nothing open', async () => {
    listItems.mockResolvedValue(null);
    renderDates();
    expect(await screen.findByText('Nothing open in this project.')).toBeInTheDocument();
  });

  it('shows nothing open when everything the server returned is closed', async () => {
    listItems.mockResolvedValue([item(1, null, { status: 'done' })]);
    renderDates();
    expect(await screen.findByText('Nothing open in this project.')).toBeInTheDocument();
  });

  it('does not show the partial-list note below the limit', async () => {
    listItems.mockResolvedValue([item(1, null)]);
    renderDates();
    await screen.findByText('Item 1');
    expect(screen.queryByText(/Showing the first/)).not.toBeInTheDocument();
  });

  it('asks the server only for open statuses', async () => {
    listItems.mockResolvedValue([]);
    renderDates();
    await screen.findByText(/Nothing open/);
    expect(listItems).toHaveBeenCalledWith({ project: 'PLAT', status: 'draft,refining,ready,in_progress,review', order: 'due', limit: 200 });
  });

  it('shows the new project, not the old one, when an answer for the old project arrives late', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const oldAnswer = deferred();
    listItems.mockReturnValueOnce(oldAnswer.promise).mockResolvedValueOnce([item(2, null, { title: 'Ops item', key: 'OPS-2' })]);
    const { rerender } = render(mount('PLAT'));
    rerender(mount('OPS'));
    expect(await screen.findByText('Ops item')).toBeInTheDocument();
    oldAnswer.resolve([item(1, null, { title: 'Plat item' })]);
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('Plat item')).not.toBeInTheDocument();
    expect(screen.getByText('Ops item')).toBeInTheDocument();
  });

  it('ignores a failure for the old project that arrives after the new project loaded', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const oldAnswer = deferred();
    listItems.mockReturnValueOnce(oldAnswer.promise).mockResolvedValueOnce([item(2, null, { title: 'Ops item' })]);
    const { rerender } = render(mount('PLAT'));
    rerender(mount('OPS'));
    await screen.findByText('Ops item');
    oldAnswer.reject(new Error('stale boom'));
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('stale boom')).not.toBeInTheDocument();
    expect(screen.getByText('Ops item')).toBeInTheDocument();
  });

  it('shows a spinner rather than the old project while the next one loads', async () => {
    listItems.mockResolvedValueOnce([item(1, null, { title: 'Plat item' })]).mockReturnValueOnce(new Promise(() => {}));
    const { rerender } = render(mount('PLAT'));
    await screen.findByText('Plat item');
    rerender(mount('OPS'));
    expect(await screen.findByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText('Plat item')).not.toBeInTheDocument();
  });

  it('filters by type and label', async () => {
    listItems.mockResolvedValue([item(1, '2026-11-14')]);
    renderDates('/?type=bug&label=ui');
    await screen.findByText('Item 1');
    expect(listItems).toHaveBeenCalledWith(expect.objectContaining({ project: 'PLAT', type: 'bug', label: 'ui' }));
    expect(screen.getByLabelText('Filter by type').value).toBe('bug');
  });

  it('says so, and keeps the filters, when nothing matches', async () => {
    listItems.mockResolvedValue([]);
    renderDates('/?label=ui');
    expect(await screen.findByText('No open items match these filters.')).toBeInTheDocument();
    expect(screen.getByLabelText('Filter by label')).toBeInTheDocument();
  });

  it('a bug row shows the marker', async () => {
    listItems.mockResolvedValue([item(1, '2026-11-14', { type: 'bug' }), item(2, '2026-11-14', { type: 'feature' })]);
    renderDates();
    await screen.findByText('Item 2');
    const rows = screen.getAllByRole('listitem');
    expect(within(rows.find((r) => r.textContent.includes('Item 1'))).getByText('Bug')).toBeInTheDocument();
    expect(within(rows.find((r) => r.textContent.includes('Item 2'))).queryByText('Bug')).toBeNull();
  });
});
