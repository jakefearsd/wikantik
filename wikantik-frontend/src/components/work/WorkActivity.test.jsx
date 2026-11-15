import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({ getActivity: vi.fn() }));
import { getActivity } from '../../api/work';
import { WorkProjectContext } from './useWorkProject';
import WorkActivity from './WorkActivity';

const ev = (id, extra = {}) => ({
  id, itemKey: `PLAT-${id}`, at: `2026-11-15T10:${String(60 - id).padStart(2, '0')}:00Z`,
  actor: 'ada', actorName: 'Ada Lovelace', type: 'status', fromValue: 'draft', toValue: 'ready', ...extra,
});
const page = (from, n) => Array.from({ length: n }, (_, i) => ev(from + i));

function renderActivity() {
  render(
    <MemoryRouter>
      <WorkProjectContext.Provider value={{ project: { id: 1, key: 'PLAT' }, role: 'viewer', reload: vi.fn() }}>
        <WorkActivity />
      </WorkProjectContext.Provider>
    </MemoryRouter>,
  );
}

beforeEach(() => vi.clearAllMocks());

describe('WorkActivity', () => {
  it('loadsFiftyThenMoreWithBeforeCursor', async () => {
    const first = page(1, 50);
    getActivity.mockResolvedValueOnce(first).mockResolvedValueOnce([first[49], ...page(51, 3)]);
    renderActivity();
    expect(await screen.findByText('PLAT-1')).toBeInTheDocument();
    expect(getActivity).toHaveBeenCalledWith({ project: 'PLAT', limit: 50 });
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('PLAT-53')).toBeInTheDocument();
    expect(getActivity).toHaveBeenLastCalledWith({ project: 'PLAT', before: first[49].at, limit: 50 });
    // the repeated event id 50 is shown once; a short page ends the offer
    expect(screen.getAllByText('PLAT-50')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('filtersByTypeAndActorClientSide', async () => {
    getActivity.mockResolvedValue([
      ev(1), ev(2, { type: 'commented', actorName: 'Grace Hopper' }), ev(3, { actorName: 'Grace Hopper' }),
    ]);
    renderActivity();
    await screen.findByText('PLAT-1');
    expect(screen.queryByText('Filtering loaded activity')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Event type'), { target: { value: 'commented' } });
    expect(screen.getByText('PLAT-2')).toBeInTheDocument();
    expect(screen.queryByText('PLAT-1')).not.toBeInTheDocument();
    expect(screen.getByText('Filtering loaded activity')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Event type'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Person'), { target: { value: 'Ada Lovelace' } });
    expect(screen.getByText('PLAT-1')).toBeInTheDocument();
    expect(screen.queryByText('PLAT-3')).not.toBeInTheDocument();
    expect(getActivity).toHaveBeenCalledTimes(1);
  });

  it('itemKeysLinkToItemView', async () => {
    getActivity.mockResolvedValue([ev(1)]);
    renderActivity();
    expect(await screen.findByRole('link', { name: 'PLAT-1' })).toHaveAttribute('href', '/work/item/PLAT-1');
    expect(screen.getByText(/status draft → ready/)).toBeInTheDocument();
  });

  it('never shows a login when the display name is missing', async () => {
    getActivity.mockResolvedValue([ev(1, { actor: 'secret-login', actorName: null })]);
    renderActivity();
    await screen.findByText('PLAT-1');
    expect(screen.queryByText(/secret-login/)).not.toBeInTheDocument();
    expect(screen.getByRole('list')).toHaveTextContent('Someone');
  });

  it('shows an empty state, and an error with Retry', async () => {
    getActivity.mockResolvedValueOnce([]);
    renderActivity();
    expect(await screen.findByText(/No activity yet/)).toBeInTheDocument();
  });

  it('retries a failed load', async () => {
    getActivity.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce([ev(1)]);
    renderActivity();
    expect(await screen.findByText('boom')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('PLAT-1')).toBeInTheDocument();
  });
});
