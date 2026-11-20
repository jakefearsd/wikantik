import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, createEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  getBoard: vi.fn(), getMe: vi.fn(), listProducts: vi.fn(), listMembers: vi.fn(), patchItem: vi.fn(), listItems: vi.fn(), createItem: vi.fn(), listQueues: vi.fn(),
  getChangeCounts: vi.fn(), acknowledgeItem: vi.fn(),
}));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));

import {
  getBoard, getMe, listProducts, listMembers, patchItem, createItem, listQueues, getChangeCounts, acknowledgeItem, listItems,
} from '../../api/work';
import { WorkProjectContext } from './useWorkProject';
import WorkBoard from './WorkBoard';

const PROJECT = { id: 1, key: 'PLAT', name: 'Platform' };
const item = (id, status, extra = {}) => ({
  id, key: `PLAT-${id}`, projectId: 1, level: 'task', title: `Item ${id}`, status, priority: 3,
  rank: id * 1024, version: id, productId: 3, productKey: 'wikantik', parentKey: null, commentCount: 0,
  blocked: false, assigneeName: null, dueOn: null, parentId: null, ...extra,
});
const STATUSES = ['draft', 'refining', 'ready', 'in_progress', 'review', 'done', 'cancelled'];
const boardOf = (byStatus) => STATUSES.map((s) => ({ status: s, total: (byStatus[s] || []).length, items: byStatus[s] || [] }));
const deferred = () => { let resolve; let reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };

let board;
function tree({ project = PROJECT, role = 'member', reload = vi.fn(), url = '/work/PLAT' } = {}) {
  return (
    <MemoryRouter initialEntries={[url]}>
      <WorkProjectContext.Provider value={{ project, role, reload }}>
        <WorkBoard />
      </WorkProjectContext.Provider>
    </MemoryRouter>);
}
function renderBoard(opts = {}) {
  getBoard.mockImplementation(() => Promise.resolve(board));
  getMe.mockResolvedValue({ waitingOn: [], projects: [] });
  const reload = opts.reload || vi.fn();
  const result = render(tree({ ...opts, reload }));
  return { reload, ...result };
}
const column = (status) => screen.getByTestId(`column-${status}`);
const dt = () => ({ setData: vi.fn(), getData: vi.fn(), effectAllowed: '', dropEffect: '' });
async function dragTo(key, status, extra = {}) {
  const card = (await screen.findByText(key)).closest('.work-card');
  const dataTransfer = dt();
  fireEvent.dragStart(card, { dataTransfer });
  fireEvent.dragOver(column(status), { dataTransfer });
  const drop = createEvent.drop(column(status), { dataTransfer });
  if (extra.clientY !== undefined) Object.defineProperty(drop, 'clientY', { value: extra.clientY });
  fireEvent(column(status), drop);
}
const quiet = () => vi.spyOn(console, 'warn').mockImplementation(() => {});

beforeEach(() => {
  vi.clearAllMocks();
  listProducts.mockResolvedValue([{ id: 3, key: 'wikantik', name: 'Wikantik' }]);
  listQueues.mockResolvedValue([]);
  listItems.mockResolvedValue([]);
  listMembers.mockResolvedValue([]);
  getChangeCounts.mockResolvedValue({});
  acknowledgeItem.mockResolvedValue({});
  board = boardOf({ ready: [item(1, 'ready'), item(2, 'ready')], review: [item(3, 'review')] });
});

describe('WorkBoard loading problems', () => {
  it('shows the neutral Not found when the board load is refused with 404', async () => {
    getBoard.mockRejectedValue(Object.assign(new Error('secret project'), { status: 404 }));
    getMe.mockResolvedValue({ waitingOn: [] });
    render(tree());
    expect(await screen.findByText('Not found')).toBeInTheDocument();
    expect(screen.queryByText(/secret/)).not.toBeInTheDocument();
  });

  it('shows the server message when the board cannot be loaded', async () => {
    getBoard.mockRejectedValue(Object.assign(new Error('Database is down'), { status: 500 }));
    getMe.mockResolvedValue({ waitingOn: [] });
    render(tree());
    expect(await screen.findByText('Database is down')).toBeInTheDocument();
    expect(screen.queryByTestId('column-ready')).not.toBeInTheDocument();
  });

  it('says it could not load the board when the failure has no message', async () => {
    getBoard.mockRejectedValue({ status: 500 });
    getMe.mockResolvedValue({ waitingOn: [] });
    render(tree());
    expect(await screen.findByText('Could not load the board.')).toBeInTheDocument();
  });

  it('says it could not load the board when the failure is not even an object', async () => {
    getBoard.mockRejectedValue(undefined);
    getMe.mockResolvedValue({ waitingOn: [] });
    render(tree());
    expect(await screen.findByText('Could not load the board.')).toBeInTheDocument();
  });

  it('shows five empty default columns when the server sends no columns', async () => {
    getBoard.mockResolvedValue(undefined);
    getMe.mockResolvedValue({ waitingOn: [] });
    render(tree());
    await screen.findByTestId('column-ready');
    expect(screen.getAllByTestId(/^column-/)).toHaveLength(5);
    expect(within(column('ready')).queryByText(/Item/)).not.toBeInTheDocument();
  });

  it('shows the board without badges when the change counts come back empty', async () => {
    getChangeCounts.mockResolvedValue(null);
    renderBoard();
    expect(await screen.findByText('Item 1')).toBeInTheDocument();
    await waitFor(() => expect(getChangeCounts).toHaveBeenCalledWith('PLAT'));
    expect(screen.queryByText(/open change/)).not.toBeInTheDocument();
  });

  it('drops a failure of an older board load that arrives after a newer one succeeded', async () => {
    const stale = deferred();
    getMe.mockResolvedValue({ waitingOn: [] });
    getBoard.mockResolvedValueOnce(board).mockReturnValueOnce(stale.promise).mockResolvedValueOnce(board);
    render(tree());
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Filter by product'), { target: { value: 'wikantik' } });
    await waitFor(() => expect(getBoard).toHaveBeenCalledTimes(2));
    fireEvent.change(screen.getByLabelText('Filter by product'), { target: { value: '' } });
    await waitFor(() => expect(getBoard).toHaveBeenCalledTimes(3));
    await screen.findByText('Item 1');
    stale.reject(Object.assign(new Error('late failure'), { status: 500 }));
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('late failure')).not.toBeInTheDocument();
    expect(screen.getByText('Item 1')).toBeInTheDocument();
  });

  it('does not let a late move from the old project blank the new project board', async () => {
    const late = deferred();
    patchItem.mockReturnValueOnce(late.promise);
    getMe.mockResolvedValue({ waitingOn: [] });
    getBoard.mockImplementation((key) => Promise.resolve(key === 'PLAT' ? board
      : boardOf({ ready: [item(50, 'ready', { title: 'Ops card', key: 'OPS-50' })] })));
    const { rerender } = render(tree());
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    rerender(tree({ project: { id: 2, key: 'OPS', name: 'Ops' } }));
    expect(await screen.findByText('Ops card')).toBeInTheDocument();
    late.resolve({ item: item(1, 'review', { version: 2 }) });
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.getByText('Ops card')).toBeInTheDocument();
  });
  it('ignores a late failure of a move made in the old project', async () => {
    const late = deferred();
    patchItem.mockReturnValueOnce(late.promise);
    getMe.mockResolvedValue({ waitingOn: [] });
    getBoard.mockImplementation((key) => Promise.resolve(key === 'PLAT' ? board
      : boardOf({ ready: [item(50, 'ready', { title: 'Ops card', key: 'OPS-50' })] })));
    const reload = vi.fn();
    const { rerender } = render(tree({ reload }));
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    rerender(tree({ project: { id: 2, key: 'OPS', name: 'Ops' }, reload }));
    expect(await screen.findByText('Ops card')).toBeInTheDocument();
    late.reject(Object.assign(new Error('Old project failure'), { status: 500 }));
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.getByText('Ops card')).toBeInTheDocument();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it.each([['an empty answer', undefined], ['an answer with no item', {}]])(
    'reloads the board instead of crashing when a move is saved with %s', async (_n, answer) => {
      patchItem.mockResolvedValue(answer);
      renderBoard();
      await screen.findByText('Item 1');
      fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
      await waitFor(() => expect(getBoard).toHaveBeenCalledTimes(2));
      expect(await screen.findByText('Item 1')).toBeInTheDocument();
    });

  it('keeps a moved card visible when the server sent no column for its target status', async () => {
    board = board.filter((c) => c.status !== 'review');
    patchItem.mockReturnValue(new Promise(() => {}));
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    expect(within(column('review')).getByText('Item 1')).toBeInTheDocument();
  });

  it('keeps a dropped card visible when the server sent no column for its target status', async () => {
    board = board.filter((c) => c.status !== 'done');
    patchItem.mockReturnValue(new Promise(() => {}));
    renderBoard();
    await dragTo('Item 1', 'done');
    expect(within(column('done')).getByText('Item 1')).toBeInTheDocument();
  });

  it('marks the column a card is dragged over, and unmarks it on leave and on drop', async () => {
    patchItem.mockReturnValue(new Promise(() => {}));
    renderBoard();
    const card = (await screen.findByText('Item 1')).closest('.work-card');
    const dataTransfer = dt();
    fireEvent.dragStart(card, { dataTransfer });
    fireEvent.dragOver(column('review'), { dataTransfer });
    expect(column('review')).toHaveClass('work-column-over');
    expect(column('ready')).not.toHaveClass('work-column-over');
    fireEvent.dragLeave(column('review'), { dataTransfer });
    expect(column('review')).not.toHaveClass('work-column-over');
    fireEvent.dragOver(column('review'), { dataTransfer });
    fireEvent(column('review'), createEvent.drop(column('review'), { dataTransfer }));
    expect(column('review')).not.toHaveClass('work-column-over');
  });

  it('unmarks the column when the drag ends without a drop', async () => {
    renderBoard();
    const card = (await screen.findByText('Item 1')).closest('.work-card');
    const dataTransfer = dt();
    fireEvent.dragStart(card, { dataTransfer });
    fireEvent.dragOver(column('review'), { dataTransfer });
    fireEvent.dragEnd(card, { dataTransfer });
    expect(column('review')).not.toHaveClass('work-column-over');
  });

  it('announces a successful move to assistive technology in a polite live region', async () => {
    patchItem.mockResolvedValue({ item: item(1, 'review', { version: 2 }) });
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    expect((await screen.findByText('Moved PLAT-1 to Review.')).closest('[aria-live="polite"]')).not.toBeNull();
  });
});

describe('WorkBoard side lists', () => {
  it('still shows the board when the products, members and queues cannot be listed or come back empty', async () => {
    const warn = quiet();
    listProducts.mockRejectedValue(new Error('no products'));
    listMembers.mockRejectedValue(new Error('no members'));
    listQueues.mockRejectedValue(new Error('no queues'));
    renderBoard();
    expect(await screen.findByText('Item 1')).toBeInTheDocument();
    await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkBoard: failed to load products', expect.any(Error)));
    expect(warn).toHaveBeenCalledWith('WorkBoard: failed to load members', expect.any(Error));
    expect(warn).toHaveBeenCalledWith('WorkBoard: failed to load queues', expect.any(Error));
    expect(within(screen.getByLabelText('Filter by product')).getAllByRole('option')).toHaveLength(1);
    expect(within(screen.getByLabelText('Filter by assignee')).getAllByRole('option')).toHaveLength(1);
  });

  it('treats empty answers for products, members and queues as no options', async () => {
    listProducts.mockResolvedValue(null);
    listMembers.mockResolvedValue(null);
    listQueues.mockResolvedValue(null);
    renderBoard();
    await screen.findByText('Item 1');
    await waitFor(() => expect(listQueues).toHaveBeenCalled());
    expect(within(screen.getByLabelText('Filter by product')).getAllByRole('option')).toHaveLength(1);
    expect(within(screen.getByLabelText('Filter by assignee')).getAllByRole('option')).toHaveLength(1);
  });

  it('shows a waiting lane when getMe answers with no waiting list as an empty lane', async () => {
    getBoard.mockImplementation(() => Promise.resolve(board));
    getMe.mockResolvedValue(undefined);
    render(tree());
    await screen.findByText('Item 1');
    await waitFor(() => expect(getMe).toHaveBeenCalled());
    expect(screen.queryByTestId('waiting-lane')).not.toBeInTheDocument();
  });

  it('keeps the board when the waiting-on list cannot be read', async () => {
    const warn = quiet();
    getBoard.mockImplementation(() => Promise.resolve(board));
    getMe.mockRejectedValue(new Error('me failed'));
    render(tree());
    expect(await screen.findByText('Item 1')).toBeInTheDocument();
    await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkBoard: failed to load the waiting-on list', expect.any(Error)));
  });

  it('labels a product by its key when it has no name, and a queue by its key when it has no name', async () => {
    listProducts.mockResolvedValue([{ id: 4, key: 'nameless' }]);
    listQueues.mockResolvedValue([{ key: 'plain', retiredAt: null }]);
    renderBoard();
    await screen.findByText('Item 1');
    await waitFor(() => expect(within(screen.getByLabelText('Filter by product')).getByRole('option', { name: 'nameless' })).toBeInTheDocument());
    expect(within(screen.getByLabelText('Filter by assignee')).getByRole('option', { name: 'Queue · plain' })).toBeInTheDocument();
  });

  it('keeps a retired queue in the filter while it is the one being filtered on', async () => {
    listQueues.mockResolvedValue([{ key: 'old', name: 'Old', retiredAt: '2026-10-01T00:00:00Z' }]);
    renderBoard({ url: '/work/PLAT?queue=old' });
    await screen.findByText('Item 1');
    await waitFor(() => expect(within(screen.getByLabelText('Filter by assignee')).getByRole('option', { name: 'Queue · Old' })).toBeInTheDocument());
    expect(screen.getByLabelText('Filter by assignee')).toHaveValue('queue:old');
  });

  it('reports when an acknowledgement is refused and keeps the item in the lane', async () => {
    const done = item(9, 'done', { projectId: 1, title: 'Restarted the indexer', waiting: { reason: 'acknowledge' } });
    getBoard.mockImplementation(() => Promise.resolve(board));
    getMe.mockResolvedValue({ waitingOn: [done] });
    acknowledgeItem.mockRejectedValueOnce(Object.assign(new Error('Already acknowledged'), { status: 409 }));
    render(tree({ role: 'lead' }));
    const lane = await screen.findByTestId('done-without-you');
    fireEvent.click(within(lane).getByRole('button', { name: 'Acknowledge PLAT-9' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Already acknowledged'));
    expect(screen.getByTestId('done-without-you')).toBeInTheDocument();
  });

  it('names the item when an acknowledgement fails without a message', async () => {
    const done = item(9, 'done', { projectId: 1, waiting: { reason: 'acknowledge' } });
    getBoard.mockImplementation(() => Promise.resolve(board));
    getMe.mockResolvedValue({ waitingOn: [done] });
    acknowledgeItem.mockRejectedValueOnce({});
    render(tree({ role: 'lead' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Acknowledge PLAT-9' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Could not acknowledge PLAT-9.'));
  });
});

describe('WorkBoard moves', () => {
  it('moves a card to the bottom of its own column with an explicit rank past the last card', async () => {
    patchItem.mockResolvedValue({ item: item(1, 'ready', { rank: 3072, version: 2 }) });
    renderBoard();
    await dragTo('Item 1', 'ready');
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-1', { expectedVersion: 1, changes: { rank: 3072 } }));
    await waitFor(() => expect(within(column('ready')).getAllByText(/^Item [12]$/).map((n) => n.textContent)).toEqual(['Item 2', 'Item 1']));
  });

  it('does nothing when a card is dropped back onto the position it already holds', async () => {
    renderBoard();
    await dragTo('Item 2', 'ready');
    await new Promise((r) => setTimeout(r, 20));
    expect(patchItem).not.toHaveBeenCalled();
    expect(within(column('ready')).getAllByText(/^Item [12]$/).map((n) => n.textContent)).toEqual(['Item 1', 'Item 2']);
  });

  it('puts a card above the first card of a column with a rank 1024 lower', async () => {
    board = boardOf({ ready: [item(1, 'ready')], review: [item(3, 'review', { rank: 2000 }), item(4, 'review', { rank: 3000 })] });
    patchItem.mockResolvedValue({ item: item(1, 'review', { rank: 976 }) });
    renderBoard();
    const cards = await screen.findAllByText(/Item [34]/);
    cards[0].closest('.work-card').getBoundingClientRect = () => ({ top: 100, height: 20 });
    cards[1].closest('.work-card').getBoundingClientRect = () => ({ top: 200, height: 20 });
    await dragTo('Item 1', 'review', { clientY: 10 });
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-1', { expectedVersion: 1, changes: { status: 'review', rank: 976, before: 'PLAT-3' } }));
    await waitFor(() => expect(within(column('review')).getAllByText(/^Item [134]$/).map((n) => n.textContent)).toEqual(['Item 1', 'Item 3', 'Item 4']));
  });

  it('does not ask for a rank when a card lands at the bottom of another column', async () => {
    patchItem.mockResolvedValue({ item: item(1, 'review') });
    renderBoard();
    await dragTo('Item 1', 'review', { clientY: 9999 });
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-1', { expectedVersion: 1, changes: { status: 'review' } }));
  });

  it('ignores a drop when nothing is being dragged', async () => {
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.drop(column('review'), { dataTransfer: dt() });
    expect(patchItem).not.toHaveBeenCalled();
  });

  it('drops in grouped view at the end of the column', async () => {
    patchItem.mockResolvedValue({ item: item(1, 'review') });
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.click(screen.getByLabelText('Group by epic'));
    await dragTo('Item 1', 'review');
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-1', { expectedVersion: 1, changes: { status: 'review' } }));
  });

  it('names the card when a refused move carries no message, and puts it back', async () => {
    patchItem.mockRejectedValue({ status: 500 });
    renderBoard();
    await dragTo('Item 1', 'review');
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Could not move PLAT-1.'));
    expect(within(column('ready')).getByText('Item 1')).toBeInTheDocument();
    expect(within(column('review')).queryByText('Item 1')).not.toBeInTheDocument();
  });

  it('says the claim was released by an agent when the claimant has no name', async () => {
    board = boardOf({ ready: [item(1, 'ready', { claimedByAgent: 3, claimedByName: null })] });
    patchItem.mockResolvedValue({ item: item(1, 'review', { version: 2, claimedByAgent: null }) });
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    await waitFor(() => expect(toast.info).toHaveBeenCalledWith("An agent's claim on PLAT-1 was released by the move."));
  });

  it('says nothing about a claim that the server kept', async () => {
    board = boardOf({ ready: [item(1, 'ready', { claimedByAgent: 3, claimedByName: 'codex@docker2' })] });
    patchItem.mockResolvedValue({ item: item(1, 'review', { version: 2, claimedByAgent: 3 }) });
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    await waitFor(() => expect(within(column('review')).getByText('Item 1')).toBeInTheDocument());
    await waitFor(() => expect(patchItem).toHaveBeenCalled());
    expect(toast.info).not.toHaveBeenCalled();
  });

  it('puts the card back and tells the viewer when the project was archived under them, also without a message', async () => {
    patchItem.mockRejectedValue(Object.assign(new Error(''), { status: 409, body: { reason: 'archived' } }));
    const { reload } = renderBoard();
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    await waitFor(() => expect(toast.info).toHaveBeenCalledWith('This project is archived; nothing was moved.'));
    expect(reload).toHaveBeenCalledTimes(1);
    expect(within(column('ready')).getByText('Item 1')).toBeInTheDocument();
  });

  it('cannot move a card to a column the server did not send, but still asks the server', async () => {
    board = [{ status: 'ready', total: 1, items: [item(1, 'ready')] }];
    patchItem.mockResolvedValue({ item: item(1, 'review', { version: 2 }) });
    renderBoard();
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Move PLAT-1 to…'), { target: { value: 'review' } });
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-1', { expectedVersion: 1, changes: { status: 'review' } }));
  });

  it('dropping onto a column the server did not send still asks the server', async () => {
    board = [{ status: 'ready', total: 1, items: [item(1, 'ready')] }];
    patchItem.mockResolvedValue({ item: item(1, 'review', { version: 2 }) });
    renderBoard();
    await dragTo('Item 1', 'review');
    await waitFor(() => expect(patchItem).toHaveBeenCalledWith('PLAT-1', { expectedVersion: 1, changes: { status: 'review' } }));
  });
});

describe('WorkBoard creating items', () => {
  const create = async (view) => {
    createItem.mockResolvedValue(view);
    fireEvent.click(screen.getByRole('button', { name: 'New item' }));
    fireEvent.change(await screen.findByLabelText('Title'), { target: { value: 'Brand new' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create item' }));
    await waitFor(() => expect(toast.success).toHaveBeenCalled());
    fireEvent.click(screen.getByLabelText('Show draft and cancelled'));
  };

  it('adds the created item when it matches the assignee filter', async () => {
    renderBoard({ url: '/work/PLAT?assignee=Carol%20C' });
    await screen.findByText('Item 1');
    await create({ item: item(77, 'draft', { title: 'Brand new', assigneeName: 'Carol C' }) });
    expect(within(column('draft')).getByText('Brand new')).toBeInTheDocument();
  });

  it('leaves out a created item that belongs to someone else than the assignee filter', async () => {
    renderBoard({ url: '/work/PLAT?assignee=Carol%20C' });
    await screen.findByText('Item 1');
    await create({ item: item(77, 'draft', { title: 'Brand new', assigneeName: 'Dan D' }) });
    expect(screen.queryByText('Brand new')).not.toBeInTheDocument();
  });

  it('adds the created item when it is in the queue being filtered on, and leaves out one in another queue', async () => {
    renderBoard({ url: '/work/PLAT?queue=implementer' });
    await screen.findByText('Item 1');
    await create({ item: item(77, 'draft', { title: 'Brand new', queue: 'implementer' }) });
    expect(within(column('draft')).getByText('Brand new')).toBeInTheDocument();
  });

  it('leaves out a created item in another queue', async () => {
    renderBoard({ url: '/work/PLAT?queue=implementer' });
    await screen.findByText('Item 1');
    await create({ item: item(77, 'draft', { title: 'Brand new', queue: 'reviewer' }) });
    expect(screen.queryByText('Brand new')).not.toBeInTheDocument();
  });

  it('adds a created item that the server answered without extras, as unblocked with no comments', async () => {
    renderBoard();
    await screen.findByText('Item 1');
    await create({ item: item(77, 'draft', { title: 'Brand new', productKey: undefined }) });
    const card = within(column('draft')).getByText('Brand new').closest('.work-card');
    expect(within(card).queryByText('Blocked')).not.toBeInTheDocument();
    expect(within(card).queryByText('wikantik')).not.toBeInTheDocument();
  });

  it('closes the form after creating', async () => {
    renderBoard();
    await screen.findByText('Item 1');
    await create({ item: item(77, 'draft', { title: 'Brand new' }) });
    await waitFor(() => expect(screen.queryByLabelText('Title')).not.toBeInTheDocument());
  });
});

describe('WorkBoard filters', () => {
  it('clears the assignee and queue from the URL when "All assignees" is chosen again', async () => {
    renderBoard({ url: '/work/PLAT?assignee=Carol%20C' });
    await screen.findByText('Item 1');
    fireEvent.change(screen.getByLabelText('Filter by assignee'), { target: { value: '' } });
    await waitFor(() => expect(getBoard).toHaveBeenLastCalledWith('PLAT', {}));
  });

  it('says how many drafts and cancelled items are hidden, and shows them on a click', async () => {
    board = boardOf({ draft: [item(7, 'draft'), item(8, 'draft')], cancelled: [item(9, 'cancelled')], ready: [item(1, 'ready')] });
    renderBoard();
    const hint = await screen.findByRole('button', { name: '3 draft or cancelled items are hidden. Show' });
    expect(screen.queryByText('PLAT-7')).toBeNull();
    fireEvent.click(hint);
    expect(await screen.findByText('PLAT-7')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /hidden\. Show/ })).toBeNull();
  });

  it('gives no hint when nothing is hidden', async () => {
    board = boardOf({ ready: [item(1, 'ready')] });
    renderBoard();
    await screen.findByText('PLAT-1');
    expect(screen.queryByRole('button', { name: /hidden\. Show/ })).toBeNull();
  });
});
