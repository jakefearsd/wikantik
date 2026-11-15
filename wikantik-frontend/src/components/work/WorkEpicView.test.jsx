import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({ listItems: vi.fn() }));
import { listItems } from '../../api/work';
import WorkEpicView from './WorkEpicView';

const child = (id, status, extra = {}) => ({ id, key: `PLAT-${id}`, level: 'story', title: `Story ${id}`, status, ...extra });
const VIEW = { item: { id: 1, key: 'PLAT-1', level: 'epic' }, projectKey: 'PLAT', derivedProductKeys: ['wikantik', 'docs'] };

let children;
let tasksByStory;
beforeEach(() => {
  vi.clearAllMocks();
  children = [child(2, 'done'), child(3, 'in_progress'), child(4, 'cancelled'), child(5, 'done')];
  tasksByStory = { 'PLAT-2': [{ id: 20 }, { id: 21 }], 'PLAT-3': [{ id: 30 }], 'PLAT-4': [], 'PLAT-5': [{ id: 50 }, { id: 51 }, { id: 52 }] };
  listItems.mockImplementation(({ parent }) => Promise.resolve(parent === 'PLAT-1' ? children : (tasksByStory[parent] || [])));
});
const renderEpic = (view = VIEW) => render(<MemoryRouter><WorkEpicView view={view} /></MemoryRouter>);

describe('WorkEpicView', () => {
  it('listsStoriesWithTaskCounts', async () => {
    renderEpic();
    expect(await screen.findByRole('link', { name: 'PLAT-2' })).toHaveAttribute('href', '/work/item/PLAT-2');
    expect(screen.getByText('Story 3')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('story-PLAT-2')).toHaveTextContent('2 tasks'));
    expect(screen.getByTestId('story-PLAT-3')).toHaveTextContent('1 task');
    expect(screen.getByTestId('story-PLAT-4')).toHaveTextContent('0 tasks');
    expect(screen.getByTestId('story-PLAT-5')).toHaveTextContent('3 tasks');
    expect(listItems).toHaveBeenCalledWith(expect.objectContaining({ project: 'PLAT', parent: 'PLAT-1' }));
  });

  it('progressIsDoneOverNonCancelled', async () => {
    renderEpic();
    const bar = await screen.findByRole('progressbar');
    // 2 done of 4 stories, one cancelled: 2 / 3.
    expect(bar).toHaveAttribute('value', '2');
    expect(bar).toHaveAttribute('max', '3');
    expect(screen.getByText('2 of 3 done (67%)')).toBeInTheDocument();
  });

  it('has no progress to show when every story is cancelled', async () => {
    children = [child(2, 'cancelled')];
    renderEpic();
    expect(await screen.findByText('No active stories.')).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).toBeNull();
  });

  it('says so when the epic has no stories', async () => {
    children = [];
    renderEpic();
    expect(await screen.findByText('No stories yet.')).toBeInTheDocument();
  });

  it('showsDerivedProducts', async () => {
    renderEpic();
    expect(await screen.findByText('wikantik')).toBeInTheDocument();
    expect(screen.getByText('docs')).toBeInTheDocument();
  });

  it('shows no products when none are derived', async () => {
    renderEpic({ ...VIEW, derivedProductKeys: [] });
    expect(await screen.findByText('No products yet.')).toBeInTheDocument();
  });

  it('caps the eager task-count requests and counts the rest on demand', async () => {
    children = Array.from({ length: 25 }, (_, i) => child(100 + i, 'ready'));
    renderEpic();
    await screen.findByText('Story 100');
    await waitFor(() => expect(screen.getByTestId('story-PLAT-100')).toHaveTextContent('0 tasks'));
    // one call for the epic's children + at most 20 story counts
    expect(listItems.mock.calls.length).toBe(21);
    const buttons = screen.getAllByRole('button', { name: /Count tasks/ });
    expect(buttons).toHaveLength(5);
    fireEvent.click(buttons[0]);
    await waitFor(() => expect(listItems.mock.calls.length).toBe(22));
  });

  it('shows an error when the stories cannot be loaded', async () => {
    listItems.mockRejectedValue(new Error('nope'));
    renderEpic();
    expect(await screen.findByRole('alert')).toHaveTextContent('nope');
  });

  it('leaves a failed count unknown instead of showing zero', async () => {
    listItems.mockImplementation(({ parent }) => (parent === 'PLAT-1' ? Promise.resolve([child(2, 'ready')]) : Promise.reject(new Error('x'))));
    renderEpic();
    await screen.findByText('Story 2');
    await waitFor(() => expect(screen.getByTestId('story-PLAT-2')).toHaveTextContent('tasks unavailable'));
  });
});
