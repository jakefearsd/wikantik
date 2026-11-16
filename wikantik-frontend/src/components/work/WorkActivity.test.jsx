import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
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
  // The server's cursor is exclusive and compound: strictly older in (at, id) order, newest first.
  const serve = (all) => (params) => {
    const sorted = [...all].sort((a, b) => (a.at === b.at ? b.id - a.id : (a.at < b.at ? 1 : -1)));
    const rest = params.before === undefined ? sorted
      : sorted.filter((e) => (params.beforeId === undefined ? e.at < params.before
        : e.at < params.before || (e.at === params.before && e.id < params.beforeId)));
    return Promise.resolve(rest.slice(0, params.limit));
  };

  it('loadsFiftyThenMoreWithBeforeCursor', async () => {
    const all = page(1, 53);
    getActivity.mockImplementation(serve(all));
    renderActivity();
    expect(await screen.findByText('PLAT-1')).toBeInTheDocument();
    expect(getActivity).toHaveBeenCalledWith({ project: 'PLAT', limit: 50 });
    expect(screen.queryByText('PLAT-51')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('PLAT-53')).toBeInTheDocument();
    expect(getActivity).toHaveBeenLastCalledWith({ project: 'PLAT', before: all[49].at, beforeId: all[49].id, limit: 50 });
    expect(screen.getAllByText('PLAT-50')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('sameTimestampEventsAcrossAPageBoundaryAreAllShown', async () => {
    // 120 events, all with the same timestamp (one update writes several events at once)
    const all = Array.from({ length: 120 }, (_, i) => ev(i + 1, { at: '2026-11-15T10:00:00Z' }));
    getActivity.mockImplementation(serve(all));
    renderActivity();
    await screen.findByText('PLAT-120');
    for (let i = 0; i < 2; i++) {
      fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));
      await screen.findByText(`PLAT-${i === 0 ? 70 : 1}`);
    }
    for (let n = 1; n <= 120; n++) expect(screen.getAllByText(`PLAT-${n}`)).toHaveLength(1);
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

  it('a project event (no item) is shown without an item link', async () => {
    getActivity.mockResolvedValue([ev(1, { itemKey: null, type: 'member', fromValue: null, toValue: 'user:x:member',
      toValueName: 'Bo B' })]);
    renderActivity();
    expect(await screen.findByText(/added Bo B as member/)).toBeInTheDocument();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('the item filter asks the server for that item only', async () => {
    getActivity.mockResolvedValue(page(1, 3));
    renderActivity();
    await screen.findByText('PLAT-1');
    fireEvent.change(screen.getByLabelText('Item'), { target: { value: 'plat-2' } });
    getActivity.mockResolvedValue([ev(2)]);
    fireEvent.click(screen.getByRole('button', { name: 'Show' }));
    await waitFor(() => expect(getActivity).toHaveBeenLastCalledWith({ project: 'PLAT', item: 'PLAT-2', limit: 50 }));
    await waitFor(() => expect(screen.queryByText('PLAT-1')).toBeNull());
  });

  it('an item that is not in the project says so and keeps the filter', async () => {
    getActivity.mockResolvedValueOnce(page(1, 2));
    renderActivity();
    await screen.findByText('PLAT-1');
    getActivity.mockRejectedValue(Object.assign(new Error('Not found.'), { status: 404 }));
    fireEvent.change(screen.getByLabelText('Item'), { target: { value: 'OPS-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Show' }));
    expect(await screen.findByText('No such item in this project.')).toBeInTheDocument();
    expect(screen.getByLabelText('Item')).toHaveValue('OPS-1');
  });

  it('reads agent and claim events as plain sentences, each on its own line, with an agent badge', async () => {
    const t = (h, m) => new Date(2026, 10, 16, h, m).toISOString();
    getActivity.mockResolvedValue([
      ev(1, { type: 'claimed', actorName: 'codex@docker2', actorKind: 'agent', agentId: 3, fromValue: 'ready', toValue: 'ready', note: t(15, 40), at: t(13, 0) }),
      ev(2, { type: 'released', actorName: 'Jane Doe', actorKind: 'human', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'review', note: 'moved' }),
      ev(3, { type: 'claim_expired', actorName: 'codex@docker2', actorKind: 'agent', fromValue: 'in_progress', toValue: 'ready' }),
      ev(4, { type: 'handoff', actorName: 'codex@docker2', actorKind: 'agent', fromValue: 'in_progress|', toValue: 'review|queue:reviewer', toValueName: 'Reviewer', note: 'ready for review' }),
    ]);
    renderActivity();
    const rows = await screen.findAllByRole('listitem');
    expect(rows).toHaveLength(4);
    expect(rows[0]).toHaveTextContent('PLAT-1 codex@docker2 agent claimed this · until 15:40');
    expect(rows[1]).toHaveTextContent("Jane Doe moved this item, ending codex@docker2's claim (now review)");
    expect(rows[2]).toHaveTextContent('claim by codex@docker2 expired — back to ready');
    expect(rows[3]).toHaveTextContent('codex@docker2 agent handed this to Queue · Reviewer: in progress → review');
    expect(rows[3]).toHaveTextContent('ready for review');
  });
});
