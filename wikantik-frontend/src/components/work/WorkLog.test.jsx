import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';

vi.mock('../../api/work', () => ({ getAssignmentLog: vi.fn(), listAgents: vi.fn(), listProjects: vi.fn() }));
import { getAssignmentLog, listAgents, listProjects } from '../../api/work';
import WorkLog from './WorkLog';

const NOW = new Date('2026-11-16T18:00:00');
const local = (hhmm, day = '2026-11-16') => new Date(`${day}T${hhmm}:00`).toISOString();

let nextId = 100;
const ev = (extra) => ({
  id: nextId--, projectId: 1, projectKey: 'PLAT', projectName: 'Platform', projectArchived: false, itemId: 15,
  itemKey: 'PLAT-15', itemTitle: 'Fix the cache', at: local('10:00'), actor: 'lena', actorName: 'Jane Doe',
  actorKind: 'human', agentId: null, type: 'status', fromValue: 'draft', toValue: 'ready', note: null, ...extra,
});
const agentEv = (extra) => ev({ actor: 'codex@docker2', actorName: 'codex@docker2', actorKind: 'agent', agentId: 3, ...extra });

function Where() {
  const l = useLocation();
  return <div data-testid="where">{l.pathname}{l.search}</div>;
}

function renderLog(url = '/work/log') {
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/work/log" element={<><WorkLog /><Where /></>} />
        <Route path="/work/:project/log" element={<><WorkLog /><Where /></>} />
        <Route path="/work/item/:key" element={<div>ITEM PAGE</div>} />
        <Route path="/work" element={<div>HOME</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  nextId = 100;
  listAgents.mockResolvedValue([{ id: 3, name: 'codex@docker2' }, { id: 4, name: 'claude@docker2' }]);
  listProjects.mockResolvedValue([{ id: 1, key: 'PLAT', name: 'Platform' }, { id: 2, key: 'OPS', name: 'Ops' }]);
});
afterEach(() => vi.useRealTimers());

describe('WorkLog', () => {
  it('rendersClaimAssignmentAndStatusRowsAsSentences', async () => {
    getAssignmentLog.mockResolvedValue({ events: [
      agentEv({ type: 'claimed', fromValue: 'ready', toValue: 'in_progress', note: local('17:40'), at: local('15:40') }),
      ev({ type: 'assigned', fromValue: null, toValue: 'queue:reviewer', toValueName: 'Reviewer', itemKey: 'PLAT-9',
        itemTitle: 'Review docs', at: local('15:00') }),
      ev({ type: 'status', fromValue: 'ready', toValue: 'in_progress', at: local('14:00') }),
    ] });
    renderLog();
    const rows = await screen.findAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('PLAT-15 Fix the cache PLAT codex@docker2 agent claimed this (ready → in progress) · until 17:40');
    expect(rows[1]).toHaveTextContent('PLAT-9 Review docs PLAT Jane Doe assigned this to Queue · Reviewer');
    expect(rows[2]).toHaveTextContent('Jane Doe status ready → in progress');
    expect(screen.getByRole('link', { name: 'PLAT-9' })).toHaveAttribute('href', '/work/item/PLAT-9');
    expect(screen.getByRole('heading', { name: 'Today' })).toBeInTheDocument();
    expect(getAssignmentLog).toHaveBeenCalledWith({ limit: 50 });
  });

  it('agentRowsCarryBadge', async () => {
    getAssignmentLog.mockResolvedValue({ events: [agentEv({ type: 'claimed', fromValue: 'ready', toValue: 'in_progress' }), ev()] });
    renderLog();
    const rows = await screen.findAllByRole('listitem');
    expect(within(rows[0]).getByText('agent')).toHaveClass('work-agent-badge');
    expect(within(rows[1]).queryByText('agent')).not.toBeInTheDocument();
  });

  it('groupsRowsByDayAndMarksArchivedProjects', async () => {
    getAssignmentLog.mockResolvedValue({ events: [
      ev({ at: local('09:00') }), ev({ at: local('09:00', '2026-11-15'), projectArchived: true, projectKey: 'OLD' }),
    ] });
    renderLog();
    await screen.findAllByRole('listitem');
    expect(screen.getByRole('heading', { name: 'Today' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Yesterday' })).toBeInTheDocument();
    expect(screen.getByText('(archived)')).toBeInTheDocument();
  });

  it('filterByAgentsAsksTheServerForAgentsOnly', async () => {
    getAssignmentLog.mockResolvedValue({ events: [ev()] });
    renderLog();
    await screen.findAllByRole('listitem');
    fireEvent.change(screen.getByLabelText('Who'), { target: { value: 'agents' } });
    await waitFor(() => expect(getAssignmentLog).toHaveBeenLastCalledWith({ actorKind: 'agent', limit: 50 }));
    expect(screen.getByTestId('where')).toHaveTextContent('/work/log?who=agents');
  });

  it('kindAgentAndProjectFiltersAreServerSideAndLiveInTheUrl', async () => {
    getAssignmentLog.mockResolvedValue({ events: [ev()] });
    renderLog('/work/log?kind=claims&agent=3&project=PLAT&who=agents');
    await screen.findAllByRole('listitem');
    expect(getAssignmentLog).toHaveBeenCalledWith({
      types: ['claimed', 'claim_renewed', 'released', 'claim_expired'], project: 'PLAT', actorKind: 'agent', agent: '3', limit: 50,
    });
    await waitFor(() => expect(screen.getByLabelText('Agent')).toHaveValue('3'));
    expect(screen.getByLabelText('Project')).toHaveValue('PLAT');
    fireEvent.change(screen.getByLabelText('Kind of event'), { target: { value: 'assignments' } });
    await waitFor(() => expect(getAssignmentLog).toHaveBeenLastCalledWith(expect.objectContaining({ types: ['assigned', 'handoff', 'member'] })));
  });

  it('ignoresUnknownFilterValuesInTheUrl', async () => {
    getAssignmentLog.mockResolvedValue({ events: [ev()] });
    renderLog('/work/log?kind=bogus&who=robots&agent=x');
    await screen.findAllByRole('listitem');
    expect(getAssignmentLog).toHaveBeenCalledWith({ limit: 50 });
  });

  it('loadMoreUsesCompoundCursor', async () => {
    const first = Array.from({ length: 50 }, (_, i) => ev({ id: 1000 - i, at: local('12:00'), itemKey: `PLAT-${i + 1}` }));
    getAssignmentLog.mockResolvedValueOnce({ events: first }).mockResolvedValueOnce({ events: [ev({ id: 5, itemKey: 'PLAT-99', at: local('08:00') })] });
    renderLog();
    await screen.findByText('PLAT-1');
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('PLAT-99')).toBeInTheDocument();
    expect(getAssignmentLog).toHaveBeenLastCalledWith({ before: first[49].at, beforeId: 951, limit: 50 });
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('emptyStateSaysNoAssignmentsYet', async () => {
    getAssignmentLog.mockResolvedValue({ events: [] });
    renderLog();
    expect(await screen.findByText('No assignments or claims yet.')).toBeInTheDocument();
  });

  it('showsAnErrorWithRetry', async () => {
    getAssignmentLog.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 })).mockResolvedValueOnce({ events: [ev()] });
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderLog();
    expect(await screen.findByText('boom')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('PLAT-15')).toBeInTheDocument();
  });

  it('insideAProjectTheRequestCarriesTheProjectAndOffersNoProjectFilter', async () => {
    getAssignmentLog.mockResolvedValue({ events: [ev({ projectArchived: true })] });
    renderLog('/work/PLAT/log?kind=claims');
    await screen.findAllByRole('listitem');
    expect(getAssignmentLog).toHaveBeenCalledWith({
      types: ['claimed', 'claim_renewed', 'released', 'claim_expired'], project: 'PLAT', limit: 50,
    });
    expect(screen.queryByLabelText('Project')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Kind of event')).toHaveValue('claims');
    expect(screen.getByText('(archived)')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Who'), { target: { value: 'agents' } });
    await waitFor(() => expect(getAssignmentLog).toHaveBeenLastCalledWith(expect.objectContaining({ project: 'PLAT', actorKind: 'agent' })));
    expect(screen.getByTestId('where')).toHaveTextContent('/work/PLAT/log?kind=claims&who=agents');
  });

  it('aLoadMoreInFlightWhenAFilterChangesCannotDisableOrPolluteTheNextQuery', async () => {
    const full = (prefix) => Array.from({ length: 50 }, (_, i) => ev({ id: 2000 + i + (prefix === 'B' ? 100 : 0), itemKey: `${prefix}-${i + 1}` }));
    let resolveStale;
    getAssignmentLog
      .mockResolvedValueOnce({ events: full('A') })
      .mockReturnValueOnce(new Promise((r) => { resolveStale = r; }))
      .mockResolvedValueOnce({ events: full('B') });
    renderLog();
    await screen.findByText('A-1');
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByRole('button', { name: 'Loading…' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Who'), { target: { value: 'agents' } });
    await screen.findByText('B-1');
    resolveStale({ events: [ev({ id: 1, itemKey: 'STALE-1' })] });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Load more' })).toBeEnabled());
    expect(screen.queryByText('STALE-1')).not.toBeInTheDocument();
  });

  describe('renewals', () => {
    it('foldsConsecutiveRenewalsOfOneClaimIntoOneStatedLine', async () => {
      const renewals = [
        agentEv({ type: 'claim_renewed', toValue: local('17:40'), at: local('15:30') }),
        agentEv({ type: 'claim_renewed', toValue: local('17:10'), at: local('15:00') }),
        agentEv({ type: 'claim_renewed', toValue: local('16:40'), at: local('14:30') }),
      ];
      const claimed = agentEv({ type: 'claimed', fromValue: 'ready', toValue: 'in_progress', at: local('14:00') });
      const otherItem = agentEv({ type: 'claim_renewed', itemId: 16, itemKey: 'PLAT-16', toValue: local('17:00'), at: local('14:45') });
      getAssignmentLog.mockResolvedValue({ events: [renewals[0], renewals[1], otherItem, renewals[2], claimed] });
      renderLog();
      const rows = await screen.findAllByRole('listitem');
      expect(rows).toHaveLength(3);
      expect(rows[0]).toHaveTextContent('codex@docker2 agent renewed its claim 3 times, last until 17:40');
      expect(rows[1]).toHaveTextContent('PLAT-16');
      expect(rows[1]).toHaveTextContent('renewed its claim · until 17:00');
      expect(rows[2]).toHaveTextContent('claimed this');
      expect(screen.getByRole('status')).toHaveTextContent('2 earlier renewals folded into the lines above.');
      fireEvent.click(screen.getByRole('button', { name: 'Show every renewal' }));
      expect(await screen.findAllByRole('listitem')).toHaveLength(5);
      expect(screen.queryByText(/folded into/)).not.toBeInTheDocument();
      expect(screen.getByTestId('where')).toHaveTextContent('renewals=all');
      expect(getAssignmentLog).toHaveBeenCalledTimes(1);
    });

    it('neverFoldsAnythingButRenewalsAndNeverAcrossAnotherEventOnTheItem', async () => {
      getAssignmentLog.mockResolvedValue({ events: [
        agentEv({ type: 'claim_renewed', toValue: local('17:40'), at: local('15:30') }),
        ev({ type: 'released', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'moved', at: local('15:20') }),
        agentEv({ type: 'claim_renewed', toValue: local('17:10'), at: local('15:00') }),
        agentEv({ type: 'claim_renewed', agentId: 4, actorName: 'claude@docker2', toValue: local('17:10'), at: local('14:55') }),
        ev({ type: 'status', at: local('14:50') }), ev({ type: 'status', at: local('14:40') }),
      ] });
      renderLog();
      const rows = await screen.findAllByRole('listitem');
      expect(rows).toHaveLength(6);
      expect(screen.queryByText(/folded into/)).not.toBeInTheDocument();
      expect(screen.queryByText(/times/)).not.toBeInTheDocument();
    });
  });
});
