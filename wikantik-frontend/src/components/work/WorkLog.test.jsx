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

  it('choosing People needs no note about expired claims: the server lists them under every Who', async () => {
    getAssignmentLog.mockResolvedValue({ events: [ev()] });
    renderLog();
    await screen.findAllByRole('listitem');
    fireEvent.change(screen.getByLabelText('Who'), { target: { value: 'people' } });
    await waitFor(() => expect(getAssignmentLog).toHaveBeenLastCalledWith({ actorKind: 'human', limit: 50 }));
    expect(screen.queryByText(/Expired claims are listed under/)).not.toBeInTheDocument();
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

describe('WorkLog edges', () => {
  const deferred = () => { let resolve; let reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
  const quiet = () => vi.spyOn(console, 'warn').mockImplementation(() => {});
  const full = (prefix, base = 3000) => Array.from({ length: 50 }, (_, i) => ev({ id: base + i, itemKey: `${prefix}-${i + 1}` }));

  it('says a claim was renewed without an expiry when the event carries none, and counts one folded renewal in the singular', async () => {
    getAssignmentLog.mockResolvedValue({ events: [
      agentEv({ type: 'claim_renewed', toValue: null, at: local('15:30') }),
      agentEv({ type: 'claim_renewed', toValue: null, at: local('15:00') }),
    ] });
    renderLog();
    const rows = await screen.findAllByRole('listitem');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('renewed its claim 2 times');
    expect(rows[0]).not.toHaveTextContent('last until');
    expect(screen.getByRole('status')).toHaveTextContent('1 earlier renewal folded into the lines above.');
  });

  it('shows Project membership for an event with no item, and no title when an item has none', async () => {
    getAssignmentLog.mockResolvedValue({ events: [
      ev({ type: 'member', itemId: null, itemKey: null, itemTitle: null, fromValue: null, toValue: 'user:x:member', toValueName: 'Bo B', at: local('11:00') }),
      ev({ itemKey: 'PLAT-8', itemTitle: null, at: local('10:00') }),
    ] });
    renderLog();
    const rows = await screen.findAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('Project membership');
    expect(within(rows[0]).queryByRole('link')).not.toBeInTheDocument();
    expect(within(rows[1]).getByRole('link', { name: 'PLAT-8' })).toBeInTheDocument();
    expect(rows[1].querySelector('.work-log-title')).toBeNull();
  });

  it('shows the note of a released claim under its row', async () => {
    getAssignmentLog.mockResolvedValue({ events: [
      agentEv({ type: 'released', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'ready', note: 'blocked on review' }),
    ] });
    renderLog();
    expect(await screen.findByText('blocked on review')).toHaveClass('work-event-note');
  });

  it('offers the agents and projects the server lists, whether it sends a bare list or a wrapped one', async () => {
    listAgents.mockResolvedValue({ agents: [{ id: 3, name: 'codex@docker2' }] });
    listProjects.mockResolvedValue({ projects: [{ key: 'OPS', name: 'Ops' }] });
    getAssignmentLog.mockResolvedValue({ events: [ev()] });
    renderLog();
    await screen.findAllByRole('listitem');
    await waitFor(() => expect(within(screen.getByLabelText('Agent')).getByRole('option', { name: 'codex@docker2' })).toBeInTheDocument());
    expect(within(screen.getByLabelText('Project')).getByRole('option', { name: 'OPS Ops' })).toBeInTheDocument();
  });

  it('offers no agents or projects when the lists are missing or cannot be read, and still shows the log', async () => {
    quiet();
    listAgents.mockRejectedValue(new Error('no agents for you'));
    listProjects.mockResolvedValue(undefined);
    getAssignmentLog.mockResolvedValue({ events: [ev()] });
    renderLog();
    expect(await screen.findByText('PLAT-15')).toBeInTheDocument();
    await waitFor(() => expect(listProjects).toHaveBeenCalled());
    expect(within(screen.getByLabelText('Agent')).getAllByRole('option')).toHaveLength(1);
    expect(within(screen.getByLabelText('Project')).getAllByRole('option')).toHaveLength(1);
  });

  it('survives the page closing before the option lists arrive', async () => {
    const agents = deferred();
    listAgents.mockReturnValue(agents.promise);
    getAssignmentLog.mockResolvedValue({ events: [ev()] });
    const { unmount } = render(<MemoryRouter><WorkLog /></MemoryRouter>);
    await screen.findByText('PLAT-15');
    unmount();
    agents.resolve([{ id: 3, name: 'codex@docker2' }]);
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('codex@docker2')).not.toBeInTheDocument();
  });

  it('treats an empty answer as an empty log', async () => {
    getAssignmentLog.mockResolvedValue(null);
    renderLog();
    expect(await screen.findByText('No assignments or claims yet.')).toBeInTheDocument();
  });

  it('says nothing matches when a filter leaves the log empty, but not for a project fixed by the path', async () => {
    getAssignmentLog.mockResolvedValue({ events: [] });
    renderLog('/work/log?who=agents');
    expect(await screen.findByText('No assignments or claims match these filters.')).toBeInTheDocument();
  });

  it('counts the project filter as filtering when it comes from the query string', async () => {
    getAssignmentLog.mockResolvedValue({ events: [] });
    renderLog('/work/log?project=OPS');
    expect(await screen.findByText('No assignments or claims match these filters.')).toBeInTheDocument();
  });

  it('does not count the project of the path as a filter', async () => {
    getAssignmentLog.mockResolvedValue({ events: [] });
    renderLog('/work/PLAT/log');
    expect(await screen.findByText('No assignments or claims yet.')).toBeInTheDocument();
  });

  it('shows a neutral Not found when the log is refused with 404', async () => {
    quiet();
    getAssignmentLog.mockRejectedValue(Object.assign(new Error('Project secret'), { status: 404 }));
    renderLog();
    expect(await screen.findByText('Not found')).toBeInTheDocument();
    expect(screen.queryByText(/secret/)).not.toBeInTheDocument();
  });

  it('says it could not load the log when the failure carries no message', async () => {
    quiet();
    getAssignmentLog.mockRejectedValue({ status: 500 });
    renderLog();
    expect(await screen.findByText('Could not load the log.')).toBeInTheDocument();
  });

  it('chooses a project in the filter and asks the server for that project only', async () => {
    getAssignmentLog.mockResolvedValue({ events: [ev()] });
    renderLog();
    await screen.findAllByRole('listitem');
    await waitFor(() => expect(within(screen.getByLabelText('Project')).getByRole('option', { name: 'OPS Ops' })).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('Project'), { target: { value: 'OPS' } });
    await waitFor(() => expect(getAssignmentLog).toHaveBeenLastCalledWith({ project: 'OPS', limit: 50 }));
    expect(screen.getByTestId('where')).toHaveTextContent('/work/log?project=OPS');
    fireEvent.change(screen.getByLabelText('Project'), { target: { value: '' } });
    await waitFor(() => expect(getAssignmentLog).toHaveBeenLastCalledWith({ limit: 50 }));
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/work\/log$/);
  });

  it('folds renewals again after Show every renewal is turned off', async () => {
    getAssignmentLog.mockResolvedValue({ events: [
      agentEv({ type: 'claim_renewed', toValue: local('17:40'), at: local('15:30') }),
      agentEv({ type: 'claim_renewed', toValue: local('17:10'), at: local('15:00') }),
    ] });
    renderLog('/work/log?renewals=all');
    expect(await screen.findAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Fold renewals' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Fold renewals' }));
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(1));
    expect(screen.getByTestId('where')).not.toHaveTextContent('renewals');
  });

  it('shows why Load more failed and lets the viewer try again', async () => {
    quiet();
    getAssignmentLog.mockResolvedValueOnce({ events: full('A') })
      .mockRejectedValueOnce(new Error('Server hiccup'))
      .mockResolvedValueOnce({ events: [ev({ id: 7, itemKey: 'PLAT-777' })] });
    renderLog();
    await screen.findByText('A-1');
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Server hiccup');
    expect(screen.getByRole('button', { name: 'Load more' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('PLAT-777')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('uses plain words when Load more fails without a message', async () => {
    quiet();
    getAssignmentLog.mockResolvedValueOnce({ events: full('A') }).mockRejectedValueOnce({});
    renderLog();
    await screen.findByText('A-1');
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load more.');
  });

  it('takes an empty Load more answer as the end of the log', async () => {
    getAssignmentLog.mockResolvedValueOnce({ events: full('A') }).mockResolvedValueOnce(null);
    renderLog();
    await screen.findByText('A-1');
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: /Load more|Loading/ })).not.toBeInTheDocument());
    expect(screen.getByText('A-1')).toBeInTheDocument();
  });

  it('never shows an event twice when the next page repeats one', async () => {
    const first = full('A');
    getAssignmentLog.mockResolvedValueOnce({ events: first })
      .mockResolvedValueOnce({ events: [first[49], ev({ id: 1, itemKey: 'PLAT-NEW' })] });
    renderLog();
    await screen.findByText('A-50');
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    await screen.findByText('PLAT-NEW');
    expect(screen.getAllByText('A-50')).toHaveLength(1);
  });

  it('ignores a Load more failure that arrives after the filter changed', async () => {
    quiet();
    const stale = deferred();
    getAssignmentLog.mockResolvedValueOnce({ events: full('A') }).mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce({ events: [ev({ id: 5, itemKey: 'B-1' })] });
    renderLog();
    await screen.findByText('A-1');
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    fireEvent.change(screen.getByLabelText('Who'), { target: { value: 'agents' } });
    await screen.findByText('B-1');
    stale.reject(new Error('late failure'));
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('drops the first answer and its failure when the filter changed while it was still loading', async () => {
    quiet();
    const first = deferred();
    const second = deferred();
    getAssignmentLog.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    renderLog();
    await waitFor(() => expect(getAssignmentLog).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText('Who'), { target: { value: 'agents' } });
    await waitFor(() => expect(getAssignmentLog).toHaveBeenCalledTimes(2));
    second.resolve({ events: [ev({ itemKey: 'PLAT-FRESH' })] });
    await screen.findByText('PLAT-FRESH');
    first.resolve({ events: [ev({ id: 1, itemKey: 'PLAT-STALE' })] });
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('PLAT-STALE')).not.toBeInTheDocument();
    first.reject(new Error('too late'));
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('too late')).not.toBeInTheDocument();
    expect(screen.getByText('PLAT-FRESH')).toBeInTheDocument();
  });

  it('ignores a first-load failure that arrives after the filter changed', async () => {
    quiet();
    const first = deferred();
    getAssignmentLog.mockReturnValueOnce(first.promise).mockResolvedValueOnce({ events: [ev({ itemKey: 'PLAT-FRESH' })] });
    renderLog();
    await waitFor(() => expect(getAssignmentLog).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText('Who'), { target: { value: 'agents' } });
    await screen.findByText('PLAT-FRESH');
    first.reject(new Error('too late'));
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('too late')).not.toBeInTheDocument();
    expect(screen.getByText('PLAT-FRESH')).toBeInTheDocument();
  });

  it('drops a Load more answer when the page was left', async () => {
    const more = deferred();
    getAssignmentLog.mockResolvedValueOnce({ events: full('A') }).mockReturnValueOnce(more.promise);
    const { unmount } = render(<MemoryRouter><WorkLog /></MemoryRouter>);
    await screen.findByText('A-1');
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    unmount();
    more.resolve({ events: [ev({ id: 1, itemKey: 'PLAT-GONE' })] });
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('PLAT-GONE')).not.toBeInTheDocument();
  });
});
