import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, createEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getBoard: vi.fn(), getMe: vi.fn(), listProducts: vi.fn(), listMembers: vi.fn(), patchItem: vi.fn(), listItems: vi.fn(), createItem: vi.fn(), listQueues: vi.fn(),
  getChangeCounts: vi.fn(() => Promise.resolve({})), acknowledgeItem: vi.fn(() => Promise.resolve({})),
}));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));

import {
  getBoard, getMe, listProducts, listMembers, patchItem, listItems, createItem, listQueues, getChangeCounts,
  acknowledgeItem,
} from '../../api/work';
import { WorkProjectContext } from './useWorkProject';
import WorkBoard from './WorkBoard';

const PROJECT = { id: 1, key: 'PLAT', name: 'Platform' };
const item = (id, status, extra = {}) => ({
  id, key: `PLAT-${id}`, projectId: 1, level: 'task', title: `Item ${id}`, status, priority: 3,
  rank: id * 1024, version: id, productId: 3, productKey: 'wikantik', parentKey: null, commentCount: 0,
  blocked: false, assigneeName: null, dueOn: null, parentId: null, ...extra,
});
const boardOf = (byStatus, totals = {}) => ['draft', 'refining', 'ready', 'in_progress', 'review', 'done', 'cancelled']
  .map((s) => ({ status: s, total: totals[s] ?? (byStatus[s] || []).length, items: byStatus[s] || [] }));

let board;
function renderBoard({ role = 'member', me = { waitingOn: [], projects: [] }, url = '/work/PLAT' } = {}) {
  getBoard.mockImplementation(() => Promise.resolve(board));
  getMe.mockResolvedValue(me);
  const reload = vi.fn();
  render(
    <MemoryRouter initialEntries={[url]}>
      <WorkProjectContext.Provider value={{ project: PROJECT, role, reload }}>
        <WorkBoard />
      </WorkProjectContext.Provider>
    </MemoryRouter>);
  return { reload };
}
const column = (status) => screen.getByTestId(`column-${status}`);
const dt = () => ({ setData: vi.fn(), getData: vi.fn(), effectAllowed: '', dropEffect: '' });
async function dragTo(key, status, extra = {}) {
  const card = (await screen.findByText(key)).closest('.work-card');
  const dataTransfer = dt();
  fireEvent.dragStart(card, { dataTransfer });
  fireEvent.dragOver(column(status), { dataTransfer });
  // jsdom has no DragEvent, so clientY is not carried by init; define it on the event itself.
  const drop = createEvent.drop(column(status), { dataTransfer });
  if (extra.clientY !== undefined) Object.defineProperty(drop, 'clientY', { value: extra.clientY });
  fireEvent(column(status), drop);
}

beforeEach(() => {
  vi.clearAllMocks();
  listProducts.mockResolvedValue([{ id: 3, key: 'wikantik', name: 'Wikantik' }]);
  listItems.mockResolvedValue([]);
  listQueues.mockResolvedValue([
    { key: 'implementer', name: 'Implementer', retiredAt: null }, { key: 'old', name: 'Old', retiredAt: '2026-10-01T00:00:00Z' },
  ]);
  listMembers.mockResolvedValue([
    { principal: 'carol-login', principalKind: 'user', role: 'member', displayName: 'Carol C' },
    { principal: 'dan-login', principalKind: 'user', role: 'lead', displayName: 'Dan D' },
  ]);
  board = boardOf({ ready: [item(1, 'ready'), item(2, 'ready')], review: [item(3, 'review')] });
  getChangeCounts.mockResolvedValue({});
  acknowledgeItem.mockResolvedValue({});
});

describe('WorkBoard', () => {
  it('rendersSevenColumnsInOrder', async () => {
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.click(screen.getByLabelText('Show draft and cancelled'));
    const headings = screen.getAllByTestId(/^column-/).map((c) => c.getAttribute('data-testid'));
    expect(headings).toEqual(['draft', 'refining', 'ready', 'in_progress', 'review', 'done', 'cancelled']
      .map((s) => `column-${s}`));
  });

  it('hidesDraftAndCancelledByDefault', async () => {
    renderBoard();
    await screen.findByText('Item 1');
    expect(screen.queryByTestId('column-draft')).not.toBeInTheDocument();
    expect(screen.queryByTestId('column-cancelled')).not.toBeInTheDocument();
    expect(screen.getAllByTestId(/^column-/)).toHaveLength(5);
  });

  it('showsWaitingOnYouLaneOnlyWhenNonEmpty', async () => {
    const waiting = [item(2, 'review', { projectId: 1 }), item(50, 'review', { projectId: 9, title: 'Elsewhere' })];
    renderBoard({ me: { waitingOn: waiting, projects: [] } });
    const lane = await screen.findByTestId('waiting-lane');
    expect(within(lane).getByText(/Waiting on you \(1\)/)).toBeInTheDocument();
    expect(within(lane).queryByText(/Elsewhere/)).not.toBeInTheDocument();
    expect(within(lane).getByRole('link', { name: /PLAT-2/ })).toHaveAttribute('href', '/work/item/PLAT-2');
  });

  it('shows what was done without the viewer and acknowledges it, then reloads the lane', async () => {
    const done = item(9, 'done', { projectId: 1, title: 'Restarted the indexer', waiting: { reason: 'acknowledge' } });
    getMe.mockResolvedValueOnce({ waitingOn: [done], projects: [] }).mockResolvedValue({ waitingOn: [], projects: [] });
    getBoard.mockImplementation(() => Promise.resolve(board));
    render(
      <MemoryRouter initialEntries={['/work/PLAT']}>
        <WorkProjectContext.Provider value={{ project: PROJECT, role: 'lead', reload: vi.fn() }}>
          <WorkBoard />
        </WorkProjectContext.Provider>
      </MemoryRouter>);
    const lane = await screen.findByTestId('done-without-you');
    expect(within(lane).getByText('Done without you (1)')).toBeInTheDocument();
    fireEvent.click(within(lane).getByRole('button', { name: 'Acknowledge PLAT-9' }));
    await waitFor(() => expect(acknowledgeItem).toHaveBeenCalledWith('PLAT-9'));
    await waitFor(() => expect(screen.queryByTestId('done-without-you')).toBeNull());
  });

  it('a viewer sees the lane without the button', async () => {
    const done = item(9, 'done', { projectId: 1, waiting: { reason: 'acknowledge' } });
    renderBoard({ role: 'viewer', me: { waitingOn: [done], projects: [] } });
    await screen.findByTestId('done-without-you');
    expect(screen.queryByRole('button', { name: /Acknowledge/ })).toBeNull();
  });

  it('badges the cards that have a live change', async () => {
    getChangeCounts.mockResolvedValue({ 'PLAT-1': { openChanges: 1, inReview: 0 }, 'PLAT-3': { openChanges: 1, inReview: 1 } });
    renderBoard();
    await screen.findByText('Item 1');
    expect(await screen.findByText('1 open change')).toBeInTheDocument();
    expect(screen.getByText('in review')).toBeInTheDocument();
    expect(getChangeCounts).toHaveBeenCalledWith('PLAT');
  });

  it('the board still shows when the change counts cannot be read', async () => {
    getChangeCounts.mockRejectedValue(new Error('boom'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderBoard();
    expect(await screen.findByText('Item 1')).toBeInTheDocument();
    warn.mockRestore();
  });

  it('hides the waiting lane when nothing waits on the viewer in this project', async () => {
    renderBoard({ me: { waitingOn: [item(50, 'review', { projectId: 9 })], projects: [] } });
    await screen.findByText('Item 1');
    await waitFor(() => expect(getMe).toHaveBeenCalled());
    expect(screen.queryByTestId('waiting-lane')).not.toBeInTheDocument();
  });

  it('dropCallsPatchItemWithStatusAndExpectedVersion', async () => {
    patchItem.mockResolvedValue({ item: item(1, 'review', { version: 2 }) });
    renderBoard();
    await dragTo('Item 1', 'review');
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-1',
      { expectedVersion: 1, changes: { status: 'review' } }));
    await waitFor(() => expect(within(column('review')).getByText('Item 1')).toBeInTheDocument());
    expect(within(column('ready')).queryByText('Item 1')).not.toBeInTheDocument();
  });

  it('sends the midpoint rank when dropped between two cards', async () => {
    board = boardOf({ ready: [item(1, 'ready')], review: [item(3, 'review', { rank: 1000 }), item(4, 'review', { rank: 2000 })] });
    patchItem.mockResolvedValue({ item: item(1, 'review') });
    renderBoard();
    const cards = await screen.findAllByText(/Item [34]/);
    // Card 3 sits above the drop point, card 4 below it.
    cards[0].closest('.work-card').getBoundingClientRect = () => ({ top: 0, height: 20 });
    cards[1].closest('.work-card').getBoundingClientRect = () => ({ top: 100, height: 20 });
    await dragTo('Item 1', 'review', { clientY: 50 });
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-1',
      { expectedVersion: 1, changes: { status: 'review', rank: 1500, before: 'PLAT-4' } }));
  });

  describe('board order', () => {
    async function dropBetween3and4(reply) {
      board = boardOf({ ready: [item(1, 'ready')], review: [item(3, 'review', { rank: 1000 }), item(4, 'review', { rank: 2000 })] });
      patchItem.mockResolvedValue({ item: reply });
      renderBoard();
      const cards = await screen.findAllByText(/Item [34]/);
      cards[0].closest('.work-card').getBoundingClientRect = () => ({ top: 0, height: 20 });
      cards[1].closest('.work-card').getBoundingClientRect = () => ({ top: 100, height: 20 });
      await dragTo('Item 1', 'review', { clientY: 50 });
      await waitFor(() => expect(patchItem).toHaveBeenCalled());
    }

    it('sends the key of the card it was dropped before', async () => {
      await dropBetween3and4(item(1, 'review', { rank: 1500 }));
      expect(patchItem.mock.calls[0][1].changes.before).toBe('PLAT-4');
    });

    it('sends no before when dropped at the end of a column', async () => {
      board = boardOf({ ready: [item(1, 'ready')], review: [item(3, 'review')] });
      patchItem.mockResolvedValue({ item: item(1, 'review') });
      renderBoard();
      await dragTo('Item 1', 'review');
      await waitFor(() => expect(patchItem).toHaveBeenCalled());
      expect(patchItem.mock.calls[0][1].changes).not.toHaveProperty('before');
    });

    it('reloads the board when the server renumbered the column', async () => {
      await dropBetween3and4(item(1, 'review', { rank: 4096 }));
      await waitFor(() => expect(getBoard).toHaveBeenCalledTimes(2));
    });

    it('does not reload when the server kept the rank', async () => {
      await dropBetween3and4(item(1, 'review', { rank: 1500 }));
      await waitFor(() => expect(within(column('review')).getByText('Item 1')).toBeInTheDocument());
      expect(getBoard).toHaveBeenCalledTimes(1);
    });
  });

  it('dropping a queued card on In progress shows the server rule and puts the card back', async () => {
    const rule = 'Work in a queue is ready or in review until an agent claims it.';
    patchItem.mockRejectedValue(Object.assign(new Error(rule), { status: 400, body: { field: 'status', message: rule } }));
    renderBoard();
    await dragTo('Item 1', 'in_progress');
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith(rule));
    expect(within(column('ready')).getByText('Item 1')).toBeInTheDocument();
    expect(within(column('in_progress')).queryByText('Item 1')).not.toBeInTheDocument();
  });

  it('refusedMoveShowsReasonAndRestoresCard', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('Done needs a linked change'), { status: 403 }));
    renderBoard();
    await dragTo('Item 1', 'review');
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Done needs a linked change'));
    expect(within(column('ready')).getByText('Item 1')).toBeInTheDocument();
    expect(within(column('review')).queryByText('Item 1')).not.toBeInTheDocument();
  });

  it('conflictReloadsBoard', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, current: {} }));
    renderBoard();
    await dragTo('Item 1', 'review');
    await waitFor(() => expect(getBoard).toHaveBeenCalledTimes(2));
    expect(toast.info).toHaveBeenCalledWith(expect.stringMatching(/changed/i));
  });

  it('shows the neutral not-found state when the user lost access (404)', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error('Not found.'), { status: 404 }));
    renderBoard();
    await dragTo('Item 1', 'review');
    expect(await screen.findByText('Not found')).toBeInTheDocument();
    expect(screen.queryByText('Item 1')).not.toBeInTheDocument();
  });

  it('viewerCannotDrag', async () => {
    renderBoard({ role: 'viewer' });
    const card = (await screen.findByText('Item 1')).closest('.work-card');
    expect(card).toHaveAttribute('draggable', 'false');
    expect(screen.queryByLabelText('Move PLAT-1 to…')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New item' })).not.toBeInTheDocument();
    fireEvent.dragStart(card, { dataTransfer: dt() });
    fireEvent.drop(column('review'), { dataTransfer: dt() });
    expect(patchItem).not.toHaveBeenCalled();
  });

  it('doneColumnShowsTotalWhenCapped', async () => {
    const done = Array.from({ length: 50 }, (_, i) => item(100 + i, 'done'));
    board = boardOf({ done }, { done: 312 });
    renderBoard();
    await screen.findByText('Item 100');
    expect(within(column('done')).getByText('50 of 312')).toBeInTheDocument();
  });

  it('groupsByEpicWhenToggled', async () => {
    const epic = item(10, 'in_progress', { level: 'epic', title: 'Big epic' });
    const story = item(11, 'ready', { level: 'story', parentId: 10, title: 'A story' });
    board = boardOf({ in_progress: [epic], ready: [story, item(1, 'ready')] });
    renderBoard();
    await screen.findByText('A story');
    expect(screen.queryByTestId('epic-group-10')).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Group by epic'));
    const group = screen.getAllByTestId('epic-group-10');
    expect(group.length).toBeGreaterThan(0);
    expect(within(column('ready')).getByTestId('epic-group-10')).toHaveTextContent('Big epic');
    expect(within(column('ready')).getByTestId('epic-group-none')).toHaveTextContent('No epic');
  });

  it('keyboardMoveMenuMovesCard', async () => {
    patchItem.mockResolvedValue({ item: item(1, 'in_progress') });
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'in_progress' } });
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-1',
      { expectedVersion: 1, changes: { status: 'in_progress' } }));
    await waitFor(() => expect(within(column('in_progress')).getByText('Item 1')).toBeInTheDocument());
  });

  it('adds a newly created item to the board without reloading', async () => {
    createItem.mockResolvedValue({ item: item(77, 'draft', { title: 'Brand new' }) });
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.click(screen.getByRole('button', { name: 'New item' }));
    fireEvent.change(await screen.findByLabelText('Title'), { target: { value: 'Brand new' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(createItem).toHaveBeenCalled());
    fireEvent.click(await screen.findByLabelText('Show draft and cancelled'));
    expect(within(column('draft')).getByText('Brand new')).toBeInTheDocument();
    expect(getBoard).toHaveBeenCalledTimes(1);
  });

  it('showsProductKeyCommentCountAndBlockedFromTheResponse', async () => {
    board = boardOf({ ready: [item(1, 'ready', { commentCount: 5, blocked: true })] });
    renderBoard();
    const card = (await screen.findByText('Item 1')).closest('.work-card');
    expect(within(card).getByText('wikantik')).toBeInTheDocument();
    expect(within(card).getByLabelText('5 comments')).toBeInTheDocument();
    expect(within(card).getByText('Blocked')).toBeInTheDocument();
  });

  it('filtersBoardByProduct', async () => {
    renderBoard();
    await screen.findByText('Item 1');
    await waitFor(() => expect(within(screen.getByLabelText('Filter by product')).getAllByRole('option')).toHaveLength(2));
    expect(within(screen.getByLabelText('Filter by product')).getByRole('option', { name: 'All products' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Filter by product'), { target: { value: 'wikantik' } });
    await waitFor(() => expect(getBoard).toHaveBeenLastCalledWith('PLAT', { product: 'wikantik' }));
    fireEvent.change(screen.getByLabelText('Filter by product'), { target: { value: '' } });
    await waitFor(() => expect(getBoard).toHaveBeenLastCalledWith('PLAT', {}));
  });

  it('filtersBoardByAssignee by display name, offering people only and never a login', async () => {
    listMembers.mockResolvedValue([
      { principal: 'carol-login', principalKind: 'user', role: 'member', displayName: 'Carol C' },
      { principal: 'Devs', principalKind: 'group', role: 'member', displayName: 'Devs' },
    ]);
    renderBoard();
    await screen.findByText('Item 1');
    const select = screen.getByLabelText('Filter by assignee');
    await waitFor(() => expect(within(select).getByRole('option', { name: 'Carol C' })).toBeInTheDocument());
    expect(within(select).queryByRole('option', { name: 'carol-login' })).not.toBeInTheDocument();
    expect(within(select).queryByRole('option', { name: 'Devs' })).not.toBeInTheDocument();
    expect(within(select).getByRole('option', { name: 'All assignees' })).toBeInTheDocument();
    fireEvent.change(select, { target: { value: 'Carol C' } });
    await waitFor(() => expect(getBoard).toHaveBeenLastCalledWith('PLAT', { assignee: 'Carol C' }));
  });

  it('filterSelectionSurvivesReloadViaUrl', async () => {
    renderBoard({ url: '/work/PLAT?product=wikantik&assignee=Dan%20D' });
    await screen.findByText('Item 1');
    expect(getBoard).toHaveBeenCalledWith('PLAT', { product: 'wikantik', assignee: 'Dan D' });
    await waitFor(() => expect(screen.getByLabelText('Filter by product')).toHaveValue('wikantik'));
    await waitFor(() => expect(screen.getByLabelText('Filter by assignee')).toHaveValue('Dan D'));
  });

  it('an archived project shows its board read-only', async () => {
    getBoard.mockImplementation(() => Promise.resolve(board));
    getMe.mockResolvedValue({ waitingOn: [], projects: [] });
    render(
      <MemoryRouter initialEntries={['/work/PLAT']}>
        <WorkProjectContext.Provider value={{ project: { ...PROJECT, archived: true }, role: 'lead', reload: vi.fn() }}>
          <WorkBoard />
        </WorkProjectContext.Provider>
      </MemoryRouter>);
    await screen.findByText('Item 1');
    expect(screen.queryByRole('button', { name: 'New item' })).toBeNull();
    expect(screen.queryByLabelText('Move PLAT-1 to…')).toBeNull();
  });

  it('a move refused because the project was archived says so, puts the card back and re-reads the project', async () => {
    board = boardOf({ ready: [item(1, 'ready')] });
    patchItem.mockRejectedValue(Object.assign(new Error('This project is archived.'), { status: 409, reason: 'archived' }));
    const { reload } = renderBoard();
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    await waitFor(() => expect(toast.info).toHaveBeenCalledWith('This project is archived; nothing was moved.'));
    expect(toast.info).not.toHaveBeenCalledWith(expect.stringContaining('changed while you were moving'));
    expect(reload).toHaveBeenCalled();
    await waitFor(() => expect(within(column('ready')).getByText('Item 1')).toBeInTheDocument());
  });

  const deferred = () => { let resolve; let reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };

  it('failedMoveRevertsOnlyThatCard', async () => {
    board = boardOf({ ready: [item(1, 'ready'), item(2, 'ready')], review: [item(3, 'review')] });
    const first = deferred();
    const second = deferred();
    patchItem.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    fireEvent.change(screen.getByLabelText('Move PLAT-2 to…'), { target: { value: 'in_progress' } });
    await waitFor(() => expect(within(column('in_progress')).getByText('Item 2')).toBeInTheDocument());
    first.reject(Object.assign(new Error('Refused'), { status: 403 }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Refused'));
    expect(within(column('ready')).getByText('Item 1')).toBeInTheDocument();
    expect(within(column('review')).queryByText('Item 1')).not.toBeInTheDocument();
    expect(within(column('in_progress')).getByText('Item 2')).toBeInTheDocument();
    expect(within(column('ready')).queryByText('Item 2')).not.toBeInTheDocument();
  });

  it('staleBoardResponseIsIgnored', async () => {
    renderBoard();
    await screen.findByText('Item 1');
    const stale = deferred();
    const fresh = deferred();
    getBoard.mockReset();
    getBoard.mockReturnValueOnce(stale.promise).mockReturnValueOnce(fresh.promise);
    patchItem.mockRejectedValue(Object.assign(new Error('changed'), { status: 409, current: {} }));
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    await waitFor(() => expect(getBoard).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText('Filter by product'), { target: { value: 'wikantik' } });
    await waitFor(() => expect(getBoard).toHaveBeenCalledTimes(2));
    fresh.resolve(boardOf({ ready: [item(8, 'ready', { title: 'Fresh card' })] }));
    expect(await screen.findByText('Fresh card')).toBeInTheDocument();
    stale.resolve(boardOf({ ready: [item(9, 'ready', { title: 'Stale card' })] }));
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('Stale card')).not.toBeInTheDocument();
    expect(screen.getByText('Fresh card')).toBeInTheDocument();
  });

  it('cancelledDragDoesNotLeaveAStaleCardToDrop', async () => {
    renderBoard();
    const card = (await screen.findByText('Item 1')).closest('.work-card');
    fireEvent.dragStart(card, { dataTransfer: dt() });
    fireEvent.dragEnd(card, { dataTransfer: dt() });
    fireEvent.drop(column('review'), { dataTransfer: dt() });
    expect(patchItem).not.toHaveBeenCalled();
  });

  it('createdItemCarriesExtrasFromTheItemView', async () => {
    createItem.mockResolvedValue({ item: item(77, 'draft', { title: 'Brand new', productKey: undefined }),
      productKey: 'wikantik', parentKey: 'PLAT-2', blocked: true, comments: [] });
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.click(screen.getByRole('button', { name: 'New item' }));
    fireEvent.change(await screen.findByLabelText('Title'), { target: { value: 'Brand new' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    fireEvent.click(await screen.findByLabelText('Show draft and cancelled'));
    const card = (await screen.findByText('Brand new')).closest('.work-card');
    expect(within(card).getByText('wikantik')).toBeInTheDocument();
    expect(within(card).getByText('Blocked')).toBeInTheDocument();
  });

  it('createdItemOutsideTheActiveFilterIsNotAdded', async () => {
    createItem.mockResolvedValue({ item: item(77, 'draft', { title: 'Other product' }), productKey: 'other', comments: [] });
    renderBoard({ url: '/work/PLAT?product=wikantik' });
    await screen.findByText('Item 1');
    fireEvent.click(screen.getByRole('button', { name: 'New item' }));
    fireEvent.change(await screen.findByLabelText('Title'), { target: { value: 'Other product' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(createItem).toHaveBeenCalled());
    await waitFor(() => expect(toast.success).toHaveBeenCalled());
    fireEvent.click(screen.getByLabelText('Show draft and cancelled'));
    expect(screen.queryByText('Other product')).not.toBeInTheDocument();
  });

  it('assigneeFilterIncludesQueues: choosing a queue sends queue= and no assignee, and the URL keeps it', async () => {
    renderBoard();
    await screen.findByText('Item 1');
    const select = screen.getByLabelText('Filter by assignee');
    await waitFor(() => expect(within(select).getByRole('option', { name: 'Queue · Implementer' })).toBeInTheDocument());
    expect(within(select).queryByRole('option', { name: /Old/ })).toBeNull();
    fireEvent.change(select, { target: { value: 'queue:implementer' } });
    await waitFor(() => expect(getBoard).toHaveBeenLastCalledWith('PLAT', { queue: 'implementer' }));
    expect(screen.getByLabelText('Filter by assignee')).toHaveValue('queue:implementer');
    fireEvent.change(screen.getByLabelText('Filter by assignee'), { target: { value: 'Carol C' } });
    await waitFor(() => expect(getBoard).toHaveBeenLastCalledWith('PLAT', { assignee: 'Carol C' }));
  });

  it('a queue filter in the URL is sent on load', async () => {
    renderBoard({ url: '/work/PLAT?queue=implementer' });
    await screen.findByText('Item 1');
    expect(getBoard).toHaveBeenCalledWith('PLAT', { queue: 'implementer' });
  });

  it('waitingLaneShowsHandoffFromAndNote, never a login', async () => {
    const waiting = [
      item(2, 'review', { projectId: 1, waiting: { reason: 'assigned', byName: 'codex@docker2', byKind: 'agent', note: 'ready for review', at: '2026-11-16T10:00:00Z' } }),
      item(3, 'review', { projectId: 1, waiting: { reason: 'mentioned', byName: 'Jane Doe', byKind: 'human', note: null, at: '2026-11-16T10:00:00Z' } }),
      item(4, 'review', { projectId: 1, waiting: { reason: 'assigned', byName: null, byKind: null, note: null, at: null } }),
    ];
    renderBoard({ me: { waitingOn: waiting, projects: [] } });
    const lane = await screen.findByTestId('waiting-lane');
    expect(lane).toHaveTextContent('Assigned by codex@docker2 (agent)');
    expect(within(lane).getByText('ready for review')).toBeInTheDocument();
    expect(lane).toHaveTextContent('Mentioned by Jane Doe');
    expect(lane).not.toHaveTextContent('Jane Doe (agent)');
  });

  it('the create form offers the active queues', async () => {
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.click(screen.getByRole('button', { name: 'New item' }));
    expect(await screen.findByLabelText('Assignee')).toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByLabelText('Assignee')).getByRole('option', { name: 'Queue · Implementer' })).toBeInTheDocument());
  });

  describe('claimed items', () => {
    const claimedItem = (id, status) => item(id, status, {
      queue: 'implementer', queueName: 'Implementer', claimedByAgent: 3, claimedByName: 'codex@docker2',
      claimedAt: '2026-11-16T09:00:00Z', claimExpiresAt: '2099-11-16T15:40:00Z',
    });

    it('a claimed card shows its holder', async () => {
      board = boardOf({ ready: [claimedItem(1, 'ready')] });
      renderBoard();
      expect(await screen.findByText(/^codex@docker2 · until /)).toBeInTheDocument();
    });

    it('movingAClaimedItemProceedsAndThenSaysTheClaimWasReleased', async () => {
      board = boardOf({ ready: [claimedItem(1, 'ready')] });
      patchItem.mockResolvedValue({ item: item(1, 'review', { version: 2, queue: 'implementer', queueName: 'Implementer', claimedByAgent: null }) });
      renderBoard();
      await dragTo('Item 1', 'review');
      await waitFor(() => expect(patchItem).toHaveBeenCalled());
      await waitFor(() => expect(toast.info).toHaveBeenCalledWith("codex@docker2's claim on PLAT-1 was released by the move."));
    });

    it('moving an unclaimed item says nothing about claims', async () => {
      patchItem.mockResolvedValue({ item: item(1, 'review', { version: 2 }) });
      renderBoard();
      await dragTo('Item 1', 'review');
      await waitFor(() => expect(patchItem).toHaveBeenCalled());
      expect(toast.info).not.toHaveBeenCalled();
    });
  });

  describe('empty board', () => {
    const NONE = 'No items yet. Choose New item to add one; new items start as drafts, shown with Show draft and cancelled.';

    it('says how to add the first item, and where drafts went, when drafts are hidden', async () => {
      board = boardOf({});
      renderBoard();
      expect(await screen.findByText(NONE)).toBeInTheDocument();
      fireEvent.click(screen.getByLabelText('Show draft and cancelled'));
      expect(screen.getByText('No items yet. Choose New item to add one.')).toBeInTheDocument();
      expect(screen.queryByText(NONE)).toBeNull();
    });

    it('does not tell a viewer to choose New item, which they do not have', async () => {
      board = boardOf({});
      renderBoard({ role: 'viewer' });
      expect(await screen.findByText('No items yet.')).toBeInTheDocument();
      expect(screen.queryByText(/New item/)).toBeNull();
    });

    it('says the filters match nothing, not that the board is empty, when a filter is on', async () => {
      board = boardOf({});
      renderBoard({ url: '/work/PLAT?product=wikantik' });
      expect(await screen.findByText('No items match these filters.')).toBeInTheDocument();
      expect(screen.queryByText(/No items yet/)).toBeNull();
    });

    it('is not shown when the board has cards', async () => {
      renderBoard();
      await screen.findByText('Item 1');
      expect(screen.queryByText(/No items yet/)).toBeNull();
    });
  });

  it('a created draft says it is hidden and how to show it', async () => {
    createItem.mockResolvedValue({ item: item(4, 'draft', { title: 'Brand new' }) });
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.click(screen.getByRole('button', { name: 'New item' }));
    fireEvent.change(await screen.findByLabelText('Title'), { target: { value: 'Brand new' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith(
      'Created PLAT-4 as a draft. Drafts are hidden: tick Show draft and cancelled to see it.'));
  });

  it('a created draft keeps the short toast when drafts are shown', async () => {
    createItem.mockResolvedValue({ item: item(4, 'draft', { title: 'Brand new' }) });
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.click(screen.getByLabelText('Show draft and cancelled'));
    fireEvent.click(screen.getByRole('button', { name: 'New item' }));
    fireEvent.change(await screen.findByLabelText('Title'), { target: { value: 'Brand new' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Created PLAT-4'));
  });
});
