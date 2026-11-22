import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  findItems: vi.fn(), getMe: vi.fn(), listProducts: vi.fn(), listQueues: vi.fn(), saveFilter: vi.fn(),
}));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
import { findItems, getMe, listProducts, listQueues, saveFilter } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import WorkFind from './WorkFind';

const row = (key, extra) => ({
  key, title: `Title ${key}`, status: 'ready', type: 'bug', labels: ['ui'], assigneeName: 'Ada Lovelace', priority: 2,
  dueOn: '2026-12-01', updatedAt: '2026-11-20T10:00:00Z', ...extra,
});
const ME = { projects: [{ id: 1, key: 'PLAT', name: 'Platform', role: 'member' }, { id: 2, key: 'OPS', name: 'Ops', role: 'viewer' }] };

function Where() {
  const l = useLocation();
  return <div data-testid="where">{l.pathname}{l.search}</div>;
}

function renderFind(url = '/work/find', { admin = false } = {}) {
  useAuth.mockReturnValue({ user: { authenticated: true, roles: admin ? ['Admin'] : [] } });
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/work/find" element={<><WorkFind /><Where /></>} />
        <Route path="/work" element={<div>HOME</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

const deferred = () => {
  let resolve; let reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  return { promise, resolve, reject };
};

beforeEach(() => {
  vi.clearAllMocks();
  getMe.mockResolvedValue(ME);
  listProducts.mockResolvedValue([]);
  listQueues.mockResolvedValue([]);
  findItems.mockResolvedValue({ items: [row('PLAT-7')], next: null });
});

describe('WorkFind', () => {
  it('loading /work/find?label=ui&assignee=me issues that request', async () => {
    renderFind('/work/find?label=ui&assignee=me');
    await screen.findByText('Title PLAT-7');
    expect(findItems).toHaveBeenCalledWith(expect.objectContaining({ label: ['ui'], assignee: 'me', limit: 50 }));
    const link = screen.getByRole('link', { name: 'PLAT-7' });
    expect(link).toHaveAttribute('href', '/work/item/PLAT-7');
  });

  it('shows the project from the item key and the assignee by name', async () => {
    renderFind();
    const tr = (await screen.findByText('Title PLAT-7')).closest('tr');
    expect(within(tr).getByText('PLAT')).toBeInTheDocument();
    expect(within(tr).getByText('Ada Lovelace')).toBeInTheDocument();
  });

  it('applying the bar writes the URL', async () => {
    renderFind();
    await screen.findByText('Title PLAT-7');
    fireEvent.click(screen.getByLabelText('Review'));
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'Ada Lovelace' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/work/find?status=review&assignee=Ada+Lovelace'));
    await waitFor(() => expect(findItems).toHaveBeenLastCalledWith(expect.objectContaining({ status: ['review'], assignee: 'Ada Lovelace' })));
  });

  it('load more passes the cursor and appends', async () => {
    findItems.mockResolvedValueOnce({ items: [row('PLAT-7')], next: 'c1' });
    findItems.mockResolvedValueOnce({ items: [row('OPS-2')], next: null });
    renderFind();
    await screen.findByText('Title PLAT-7');
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    await screen.findByText('Title OPS-2');
    expect(findItems).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'c1' }));
    expect(screen.getByText('Title PLAT-7')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('a failed load more keeps what is loaded and can be retried', async () => {
    findItems.mockResolvedValueOnce({ items: [row('PLAT-7')], next: 'c1' });
    findItems.mockRejectedValueOnce(new Error('boom'));
    renderFind();
    await screen.findByText('Title PLAT-7');
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
    expect(screen.getByText('Title PLAT-7')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Load more' })).toBeEnabled();
  });

  it('a stale response does not replace a newer one', async () => {
    const slow = deferred();
    findItems.mockReturnValueOnce(slow.promise);
    findItems.mockResolvedValueOnce({ items: [row('PLAT-9', { title: 'Newer' })], next: null });
    renderFind();
    await waitFor(() => expect(findItems).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByLabelText('Review'));
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await screen.findByText('Newer');
    slow.resolve({ items: [row('PLAT-1', { title: 'Older' })], next: null });
    await Promise.resolve();
    expect(screen.queryByText('Older')).not.toBeInTheDocument();
    expect(screen.getByText('Newer')).toBeInTheDocument();
  });

  it('an empty result says nothing matches', async () => {
    findItems.mockResolvedValue({ items: [], next: null });
    renderFind();
    expect(await screen.findByText('Nothing matches this filter.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('an error offers a retry and is not the empty message', async () => {
    findItems.mockRejectedValueOnce(Object.assign(new Error('Server down'), { status: 500 }));
    renderFind();
    expect(await screen.findByText('Server down')).toBeInTheDocument();
    expect(screen.queryByText('Nothing matches this filter.')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByText('Title PLAT-7');
  });

  it('a failed re-search keeps the last good rows beside the error', async () => {
    findItems.mockResolvedValueOnce({ items: [row('PLAT-7')], next: null });
    findItems.mockRejectedValueOnce(new Error('later failure'));
    renderFind();
    await screen.findByText('Title PLAT-7');
    fireEvent.click(screen.getByLabelText('Review'));
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('later failure');
    expect(screen.getByText('Title PLAT-7')).toBeInTheDocument();
  });

  it('sharing is offered only for one project the viewer is a member of', async () => {
    renderFind('/work/find?projects=PLAT');
    await screen.findByText('Title PLAT-7');
    fireEvent.click(screen.getByRole('button', { name: 'Save filter' }));
    await screen.findByLabelText('Share with PLAT members');
  });

  it.each(['/work/find?projects=OPS', '/work/find?projects=PLAT,OPS', '/work/find'])(
    'offers no sharing for a viewer project, two projects, or none: %s', async (url) => {
      renderFind(url);
      await screen.findByText('Title PLAT-7');
      await waitFor(() => expect(getMe).toHaveBeenCalled());
      fireEvent.click(screen.getByRole('button', { name: 'Save filter' }));
      expect(screen.getByLabelText('Filter name')).toBeInTheDocument();
      expect(screen.queryByLabelText(/^Share with/)).not.toBeInTheDocument();
    });

  it('an admin may share in the one project the filter names', async () => {
    renderFind('/work/find?projects=OPS', { admin: true });
    await screen.findByText('Title PLAT-7');
    fireEvent.click(screen.getByRole('button', { name: 'Save filter' }));
    await screen.findByLabelText('Share with OPS members');
  });

  it('saving sends the canonical filter and shows a duplicate name inline', async () => {
    saveFilter.mockRejectedValueOnce(Object.assign(new Error('exists'), { status: 409 }));
    saveFilter.mockResolvedValueOnce({ id: 1 });
    renderFind('/work/find?projects=PLAT&label=ui,api&status=ready');
    await screen.findByText('Title PLAT-7');
    fireEvent.click(screen.getByRole('button', { name: 'Save filter' }));
    fireEvent.change(screen.getByLabelText('Filter name'), { target: { value: 'My bugs' } });
    fireEvent.click(screen.getByLabelText('Share with PLAT members'));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('You already have a filter with that name.')).toBeInTheDocument();
    expect(saveFilter).toHaveBeenCalledWith({
      name: 'My bugs', project: 'PLAT', shared: true, filter: { projects: 'PLAT', status: 'ready', label: 'ui,api' },
    });
    fireEvent.change(screen.getByLabelText('Filter name'), { target: { value: 'My bugs 2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/Saved\./)).toBeInTheDocument();
  });
});
