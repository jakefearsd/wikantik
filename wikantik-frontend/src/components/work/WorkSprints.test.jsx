import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, createEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  listItems: vi.fn(), patchItem: vi.fn(), startSprint: vi.fn(), closeSprint: vi.fn(), createSprint: vi.fn(),
}));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { listItems, patchItem, startSprint, closeSprint } from '../../api/work';
import { WorkProjectContext } from './useWorkProject';
import WorkSprints from './WorkSprints';

const PROJECT = { id: 1, key: 'PLAT', name: 'Platform' };
const CUR = { items: 1, points: 3, doneItems: 0, donePoints: 0 };
const SPRINTS = [
  { id: 11, name: 'Sprint 12', state: 'active', startsOn: '2026-11-23', endsOn: '2026-12-06', version: 4, current: { items: 2, points: 8, doneItems: 1, donePoints: 3 } },
  { id: 12, name: 'Sprint 13', state: 'planned', startsOn: '2026-12-07', endsOn: '2026-12-20', version: 1, current: CUR },
  { id: 10, name: 'Sprint 11', state: 'closed', startsOn: '2026-11-09', endsOn: '2026-11-22', version: 6, committed: { items: 4, points: 10 }, completed: { items: 3, points: 8 } },
];
const item = (id, status, extra = {}) => ({
  id, key: `PLAT-${id}`, level: 'task', title: `Item ${id}`, status, version: id, sprintId: null, estimate: null, ...extra,
});
let lists;
let reloadSprints;

function renderSprints({ role = 'member', sprints = SPRINTS } = {}) {
  reloadSprints = vi.fn();
  return render(
    <MemoryRouter>
      <WorkProjectContext.Provider value={{ project: PROJECT, role, reload: vi.fn(), sprints, reloadSprints }}>
        <WorkSprints />
      </WorkProjectContext.Provider>
    </MemoryRouter>);
}
const dt = () => ({ setData: vi.fn(), getData: vi.fn(), effectAllowed: '', dropEffect: '' });
async function dragTo(key, zoneId) {
  const row = (await screen.findByText(key)).closest('li');
  const dataTransfer = dt();
  fireEvent.dragStart(row, { dataTransfer });
  const zone = screen.getByTestId(zoneId);
  fireEvent.dragOver(zone, { dataTransfer });
  fireEvent(zone, createEvent.drop(zone, { dataTransfer }));
}

beforeEach(() => {
  vi.clearAllMocks();
  lists = {
    none: [item(21, 'ready', { title: 'Billing export' }), item(22, 'draft', { estimate: 2.5 }), item(30, 'ready', { level: 'epic', title: 'An epic' })],
    11: [item(14, 'in_progress', { title: 'Retry uploads', sprintId: 11, estimate: 3 }), item(15, 'done', { sprintId: 11, estimate: 5 })],
    12: [],
  };
  listItems.mockImplementation(({ sprint }) => Promise.resolve(lists[sprint] || []));
  patchItem.mockResolvedValue({ item: item(21, 'ready') });
  startSprint.mockResolvedValue({});
  closeSprint.mockResolvedValue({});
});

describe('WorkSprints', () => {
  it('shows the active sprint, the planned ones, the backlog without epics, and closed sprints with reports', async () => {
    renderSprints();
    expect(await screen.findByText('Retry uploads')).toBeInTheDocument();
    expect(screen.getByText('1 of 2 items · 3 of 8 pts')).toBeInTheDocument();
    expect(screen.getByText('3 pts')).toBeInTheDocument();
    expect(screen.getByText('Billing export')).toBeInTheDocument();
    expect(screen.getByText('2.5 pts')).toBeInTheDocument();
    expect(screen.queryByText('An epic')).toBeNull();
    expect(listItems).toHaveBeenCalledWith(expect.objectContaining({
      project: 'PLAT', sprint: 'none', status: 'draft,refining,ready,in_progress,review',
    }));
    expect(screen.getByRole('link', { name: 'Sprint 11' })).toHaveAttribute('href', '/work/PLAT/sprints/10');
    expect(screen.getByText(/Nov 23 – Dec 6 2026/)).toBeInTheDocument();
    expect(screen.getByText('Nov 9 – Nov 22 2026')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Report' })).toHaveAttribute('href', '/work/PLAT/sprints/11');
  });

  it('planning an item into a sprint patches sprint', async () => {
    renderSprints();
    await screen.findByText('Billing export');
    fireEvent.change(screen.getByLabelText('Plan PLAT-21 into'), { target: { value: '12' } });
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-21', { expectedVersion: 21, changes: { sprint: 12 } }));
    await waitFor(() => expect(reloadSprints).toHaveBeenCalled());
  });

  it('a sprint row can go back to the backlog', async () => {
    renderSprints();
    await screen.findByText('Retry uploads');
    fireEvent.change(screen.getByLabelText('Plan PLAT-14 into'), { target: { value: 'none' } });
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-14', { expectedVersion: 14, changes: { sprint: null } }));
  });

  it('dropping a backlog row on a sprint patches sprint', async () => {
    renderSprints();
    await dragTo('Billing export', 'sprint-12');
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-21', { expectedVersion: 21, changes: { sprint: 12 } }));
  });

  it('dropping a sprint row on the backlog sends null', async () => {
    renderSprints();
    await dragTo('Retry uploads', 'backlog');
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-14', { expectedVersion: 14, changes: { sprint: null } }));
  });

  it('dropping a row on the sprint it is already in changes nothing', async () => {
    renderSprints();
    await dragTo('Retry uploads', 'sprint-11');
    expect(patchItem).not.toHaveBeenCalled();
  });

  it('a stale reply reloads', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, reason: 'stale', body: { reason: 'stale' } }));
    renderSprints();
    await screen.findByText('Billing export');
    const before = listItems.mock.calls.length;
    fireEvent.change(screen.getByLabelText('Plan PLAT-21 into'), { target: { value: '12' } });
    await waitFor(() => expect(toast.info).toHaveBeenCalled());
    await waitFor(() => expect(listItems.mock.calls.length).toBeGreaterThan(before));
    expect(reloadSprints).toHaveBeenCalled();
  });

  it('a refusal is shown and the page reloads', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('A person plans a sprint.'), { status: 403 }));
    renderSprints();
    await screen.findByText('Billing export');
    fireEvent.change(screen.getByLabelText('Plan PLAT-21 into'), { target: { value: '12' } });
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('A person plans a sprint.'));
  });

  it('a lead starts a planned sprint', async () => {
    renderSprints({ role: 'lead' });
    await screen.findByText('Billing export');
    fireEvent.click(screen.getByRole('button', { name: 'Start' }));
    await waitFor(() => expect(startSprint).toHaveBeenCalledWith(12, 1));
  });

  it('closing with open items asks where they go', async () => {
    renderSprints({ role: 'lead' });
    await screen.findByText('Retry uploads');
    fireEvent.click(screen.getByRole('button', { name: 'Close sprint' }));
    const dialog = await screen.findByRole('dialog');
    const select = within(dialog).getByLabelText('Move unfinished work to');
    expect([...select.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['Backlog', 'Sprint 13']);
    fireEvent.change(select, { target: { value: '12' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close sprint' }));
    await waitFor(() => expect(closeSprint).toHaveBeenCalledWith(11, { version: 4, moveOpenTo: 12 }));
  });

  it('closing with the default target sends the backlog', async () => {
    renderSprints({ role: 'lead' });
    await screen.findByText('Retry uploads');
    fireEvent.click(screen.getByRole('button', { name: 'Close sprint' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Close sprint' }));
    await waitFor(() => expect(closeSprint).toHaveBeenCalledWith(11, { version: 4, moveOpenTo: null }));
  });

  it('closing with nothing unfinished asks nothing', async () => {
    lists[11] = [item(15, 'done', { sprintId: 11 })];
    const done = [{ ...SPRINTS[0], current: { items: 1, points: 5, doneItems: 1, donePoints: 5 } }, ...SPRINTS.slice(1)];
    renderSprints({ role: 'lead', sprints: done });
    await screen.findByText('Item 15');
    fireEvent.click(screen.getByRole('button', { name: 'Close sprint' }));
    await waitFor(() => expect(closeSprint).toHaveBeenCalledWith(11, { version: 4, moveOpenTo: null }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('a sprint with unfinished work asks where it goes even when the item lists are capped', async () => {
    lists[11] = [];
    renderSprints({ role: 'lead' });
    await screen.findByText('Billing export');
    fireEvent.click(screen.getByRole('button', { name: 'Close sprint' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(closeSprint).not.toHaveBeenCalled();
  });

  it('a conflict other than stale shows the server message on start and on close', async () => {
    const clash = (message) => Object.assign(new Error(message), { status: 409, body: { reason: 'sprint_active' } });
    startSprint.mockRejectedValue(clash('Another sprint is already active.'));
    renderSprints({ role: 'lead' });
    await screen.findByText('Billing export');
    fireEvent.click(screen.getByRole('button', { name: 'Start' }));
    await waitFor(() => expect(toast.info).toHaveBeenCalledWith('Another sprint is already active.'));
    closeSprint.mockRejectedValue(Object.assign(new Error('Closed already.'), { status: 409, body: { reason: 'bad_transition' } }));
    fireEvent.click(screen.getByRole('button', { name: 'Close sprint' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Close sprint' }));
    await waitFor(() => expect(toast.info).toHaveBeenCalledWith('Closed already.'));
  });

  it('a member sees no Start or Close', async () => {
    renderSprints({ role: 'member' });
    await screen.findByText('Retry uploads');
    expect(screen.queryByRole('button', { name: 'Start' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Close sprint' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'New sprint' })).toBeNull();
  });

  it('a viewer cannot plan', async () => {
    renderSprints({ role: 'viewer' });
    await screen.findByText('Retry uploads');
    expect(screen.queryByLabelText('Plan PLAT-21 into')).toBeNull();
    await dragTo('Billing export', 'sprint-12');
    expect(patchItem).not.toHaveBeenCalled();
  });

  it('a lead can open the New sprint form', async () => {
    renderSprints({ role: 'lead', sprints: [] });
    await screen.findByText('No sprint is planned or running.');
    fireEvent.click(screen.getByRole('button', { name: 'New sprint' }));
    expect(await screen.findByRole('heading', { name: 'New sprint' })).toBeInTheDocument();
  });
});
