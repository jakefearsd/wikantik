import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../../api/work', () => ({ listEvents: vi.fn(), listRevisions: vi.fn() }));
import { listEvents, listRevisions } from '../../api/work';
import WorkTimeline from './WorkTimeline';

const ev = (id, type, extra = {}) => ({
  id, type, at: '2026-11-01T10:00:00Z', actor: `login-${id}`, actorName: `Person ${id}`, fromValue: null, toValue: null, note: null, ...extra,
});
const rev = (version, title, body) => ({ itemId: 7, version, title, body, editedBy: 'x', editedByName: 'X', editedAt: '2026-11-01T10:00:00Z' });

beforeEach(() => {
  vi.clearAllMocks();
});

describe('WorkTimeline', () => {
  it('renders one row per event with the display name, type text and the handoff note', async () => {
    listEvents.mockResolvedValue([
      ev(1, 'created'),
      ev(2, 'status', { fromValue: 'draft', toValue: 'ready' }),
      ev(3, 'handoff', { fromValue: 'ready|b', toValue: 'review|c', fromValueName: 'Bob B', toValueName: 'Carol C', note: 'Please check the edge cases' }),
      ev(4, 'sprint_planned', { toValue: 'S1' }),
    ]);
    render(<WorkTimeline itemKey="PLAT-7" />);
    const rows = await screen.findAllByRole('listitem');
    expect(rows).toHaveLength(4);
    expect(within(rows[0]).getByText('Person 1')).toBeInTheDocument();
    expect(within(rows[1]).getByText('status draft → ready')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Please check the edge cases')).toBeInTheDocument();
    expect(within(rows[3]).getByText(/sprint planned/)).toBeInTheDocument();
    expect(screen.queryByText(/login-/)).toBeNull();
  });

  it('shows an empty message when there is no history', async () => {
    listEvents.mockResolvedValue([]);
    render(<WorkTimeline itemKey="PLAT-7" />);
    expect(await screen.findByText('No history yet.')).toBeInTheDocument();
  });

  it('shows an error when the history cannot be loaded', async () => {
    listEvents.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    render(<WorkTimeline itemKey="PLAT-7" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
  });

  it('revisionRowsOpenDiff: an edited event links to a diff of that version against the previous one', async () => {
    listEvents.mockResolvedValue([ev(1, 'created'), ev(2, 'edited'), ev(3, 'edited')]);
    listRevisions.mockResolvedValue([rev(1, 'T', 'one\ntwo'), rev(2, 'T', 'one\nTWO'), rev(3, 'T', 'one\nTWO\nthree')]);
    render(<WorkTimeline itemKey="PLAT-7" />);
    const buttons = await screen.findAllByRole('button', { name: 'Show changes' });
    expect(buttons).toHaveLength(2);
    fireEvent.click(buttons[0]);
    const diff = await screen.findByTestId('revision-diff');
    expect(diff).toHaveTextContent('two');
    expect(diff.querySelector('.work-diff-del')).toHaveTextContent('two');
    expect(diff.querySelector('.work-diff-add')).toHaveTextContent('TWO');
    expect(listRevisions).toHaveBeenCalledTimes(1);
    // The second edit compares version 3 with version 2.
    fireEvent.click(screen.getAllByRole('button', { name: /changes/ })[1]);
    await waitFor(() => expect(screen.getAllByTestId('revision-diff')).toHaveLength(2));
    expect(screen.getAllByTestId('revision-diff')[1].querySelector('.work-diff-add')).toHaveTextContent('three');
    expect(listRevisions).toHaveBeenCalledTimes(1);
  });

  it('hides the diff again', async () => {
    listEvents.mockResolvedValue([ev(1, 'created'), ev(2, 'edited')]);
    listRevisions.mockResolvedValue([rev(1, 'T', 'a'), rev(2, 'T', 'b')]);
    render(<WorkTimeline itemKey="PLAT-7" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Show changes' }));
    await screen.findByTestId('revision-diff');
    fireEvent.click(screen.getByRole('button', { name: 'Hide changes' }));
    expect(screen.queryByTestId('revision-diff')).toBeNull();
  });

  it('maps edits to revisions by order, not by arithmetic: revision versions have gaps', async () => {
    listEvents.mockResolvedValue([
      ev(1, 'created'), ev(2, 'edited'), ev(3, 'status', { fromValue: 'draft', toValue: 'ready' }), ev(4, 'edited'), ev(5, 'edited'),
    ]);
    listRevisions.mockResolvedValue([rev(1, 'T', 'a'), rev(2, 'T', 'b'), rev(5, 'T', 'c'), rev(9, 'T', 'd')]);
    render(<WorkTimeline itemKey="PLAT-7" />);
    const buttons = await screen.findAllByRole('button', { name: 'Show changes' });
    expect(buttons).toHaveLength(3);
    fireEvent.click(buttons[1]);
    const diff = await screen.findByTestId('revision-diff');
    expect(diff.querySelector('.work-diff-del')).toHaveTextContent('b');
    expect(diff.querySelector('.work-diff-add')).toHaveTextContent('c');
    fireEvent.click(screen.getAllByRole('button', { name: /changes/ })[2]);
    const last = screen.getAllByTestId('revision-diff')[1];
    expect(last.querySelector('.work-diff-del')).toHaveTextContent('c');
    expect(last.querySelector('.work-diff-add')).toHaveTextContent('d');
  });

  it('shows an edit without a diff link when there are fewer revisions than edits', async () => {
    listEvents.mockResolvedValue([ev(1, 'created'), ev(2, 'edited'), ev(3, 'edited')]);
    listRevisions.mockResolvedValue([rev(1, 'T', 'a'), rev(4, 'T', 'b')]);
    render(<WorkTimeline itemKey="PLAT-7" />);
    expect(await screen.findAllByRole('button', { name: 'Show changes' })).toHaveLength(1);
  });

  it('reports a revisions failure inline', async () => {
    listEvents.mockResolvedValue([ev(2, 'edited')]);
    listRevisions.mockRejectedValue(new Error('no history'));
    render(<WorkTimeline itemKey="PLAT-7" />);
    expect(await screen.findByText(/no history/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show changes' })).toBeNull();
  });

  it('reloads when refreshKey changes and ignores a stale response', async () => {
    let resolveFirst;
    listEvents.mockImplementationOnce(() => new Promise((r) => { resolveFirst = r; }));
    listEvents.mockResolvedValueOnce([ev(9, 'created')]);
    const { rerender } = render(<WorkTimeline itemKey="PLAT-7" refreshKey={0} />);
    rerender(<WorkTimeline itemKey="PLAT-7" refreshKey={1} />);
    expect(await screen.findByText('Person 9')).toBeInTheDocument();
    resolveFirst([ev(1, 'created'), ev(2, 'created')]);
    await Promise.resolve();
    expect(screen.queryByText('Person 1')).toBeNull();
  });

  describe('agents and claims', () => {
    const agent = (id, type, extra = {}) => ev(id, type, { actorName: 'codex@docker2', actorKind: 'agent', agentId: 3, ...extra });

    it('agentActorShowsAgentName: an agent actor is named and badged, not an unknown contributor', async () => {
      listEvents.mockResolvedValue([agent(1, 'status', { fromValue: 'ready', toValue: 'review' })]);
      render(<WorkTimeline itemKey="PLAT-7" />);
      const row = (await screen.findAllByRole('listitem'))[0];
      expect(within(row).getByText('codex@docker2')).toBeInTheDocument();
      expect(within(row).getByText('agent')).toBeInTheDocument();
      expect(row).not.toHaveTextContent('Someone');
    });

    it('a person actor carries no agent badge', async () => {
      listEvents.mockResolvedValue([ev(1, 'created', { actorKind: 'human' })]);
      render(<WorkTimeline itemKey="PLAT-7" />);
      await screen.findByText('created this item');
      expect(screen.queryByText('agent')).toBeNull();
    });

    it('every claim event is its own plain-text line and none is dropped', async () => {
      const t = (h, m) => new Date(2026, 10, 16, h, m).toISOString();
      listEvents.mockResolvedValue([
        agent(1, 'claimed', { at: t(13, 0), fromValue: 'ready', toValue: 'ready', note: t(15, 40) }),
        agent(2, 'claim_renewed', { at: t(14, 0), toValue: t(17, 40) }),
        ev(3, 'status', { actorName: 'Jane Doe', fromValue: 'ready', toValue: 'review' }),
        ev(4, 'released', { actorName: 'Jane Doe', actorKind: 'human', fromValue: 'agent:3', fromValueName: 'codex@docker2', toValue: 'review', note: 'moved' }),
        agent(5, 'claim_expired', { fromValue: 'in_progress', toValue: 'ready' }),
        ev(6, 'future_event', { toValue: 'x' }),
      ]);
      render(<WorkTimeline itemKey="PLAT-7" />);
      const rows = await screen.findAllByRole('listitem');
      expect(rows).toHaveLength(6);
      expect(rows[0]).toHaveTextContent('codex@docker2 agent claimed this · until 15:40');
      expect(rows[1]).toHaveTextContent('codex@docker2 agent renewed its claim · until 17:40');
      expect(rows[3]).toHaveTextContent("Jane Doe moved this item, ending codex@docker2's claim (now review)");
      expect(rows[4]).toHaveTextContent('claim by codex@docker2 expired — back to ready');
      expect(rows[4].textContent.match(/codex@docker2/g)).toHaveLength(1);
      expect(rows[5]).toHaveTextContent('future event: none → x');
    });

    it('a handoff to a queue shows the note and the queue name', async () => {
      listEvents.mockResolvedValue([agent(1, 'handoff', { fromValue: 'in_progress|', toValue: 'review|queue:reviewer', toValueName: 'Reviewer', note: 'ready for review' })]);
      render(<WorkTimeline itemKey="PLAT-7" />);
      const row = (await screen.findAllByRole('listitem'))[0];
      expect(row).toHaveTextContent('handed this to Queue · Reviewer: in progress → review');
      expect(within(row).getByText('ready for review')).toBeInTheDocument();
    });

    it('an unresolved agent or queue never shows a raw id', async () => {
      listEvents.mockResolvedValue([
        ev(1, 'released', { fromValue: 'agent:17', toValue: 'ready', note: 'agent retired' }),
        ev(2, 'assigned', { toValue: 'queue:q9' }),
      ]);
      render(<WorkTimeline itemKey="PLAT-7" />);
      await screen.findAllByRole('listitem');
      expect(document.body.textContent).not.toMatch(/agent:17|queue:q9/);
    });

    it('times use the shared formatter: no year this year, the year for another', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date(2026, 10, 20, 9, 0));
      try {
        listEvents.mockResolvedValue([
          ev(1, 'created', { at: new Date(2026, 10, 16, 13, 0).toISOString() }),
          ev(2, 'created', { at: new Date(2025, 11, 31, 8, 5).toISOString() }),
        ]);
        render(<WorkTimeline itemKey="PLAT-7" />);
        const rows = await screen.findAllByRole('listitem');
        expect(rows[0].querySelector('time')).toHaveTextContent('Nov 16 13:00');
        expect(rows[1].querySelector('time')).toHaveTextContent('Dec 31 2025 08:05');
      } finally { vi.useRealTimers(); }
    });
  });

  it('treats a missing event list as no history, and a failure with no message gets a plain default', async () => {
    listEvents.mockResolvedValue(undefined);
    const first = render(<WorkTimeline itemKey="PLAT-7" />);
    expect(await screen.findByText('No history yet.')).toBeInTheDocument();
    first.unmount();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    listEvents.mockRejectedValue({});
    render(<WorkTimeline itemKey="PLAT-7" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the history.');
    warn.mockRestore();
  });

  it('shows an edit with no diff link when the server sends no revisions', async () => {
    listEvents.mockResolvedValue([ev(2, 'edited')]);
    listRevisions.mockResolvedValue(undefined);
    render(<WorkTimeline itemKey="PLAT-7" />);
    await screen.findByText('edited the title or text');
    await waitFor(() => expect(listRevisions).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: 'Show changes' })).toBeNull();
    expect(screen.queryByText(/Changes could not be shown/)).toBeNull();
  });

  it('gives a plain default when the revisions fail with no message', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    listEvents.mockResolvedValue([ev(2, 'edited')]);
    listRevisions.mockRejectedValue({});
    render(<WorkTimeline itemKey="PLAT-7" />);
    expect(await screen.findByText('Changes could not be shown: Could not load the versions.')).toBeInTheDocument();
    warn.mockRestore();
  });

  it('diffs a revision whose text body is missing as an empty body', async () => {
    listEvents.mockResolvedValue([ev(1, 'created'), ev(2, 'edited')]);
    listRevisions.mockResolvedValue([rev(1, 'T', null), rev(2, 'T', 'added line')]);
    render(<WorkTimeline itemKey="PLAT-7" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Show changes' }));
    const diff = await screen.findByTestId('revision-diff');
    expect(diff.querySelector('.work-diff-add')).toHaveTextContent('added line');
    expect(diff.querySelector('.work-diff-del').textContent.trim()).toBe('-');
  });

  it('sets a hand-off note apart from an ordinary note', async () => {
    listEvents.mockResolvedValue([
      ev(1, 'handoff', { fromValue: 'ready|', toValue: 'review|c', toValueName: 'Carol C', note: 'over to you' }),
      ev(2, 'status', { fromValue: 'a', toValue: 'b', note: 'because' }),
    ]);
    render(<WorkTimeline itemKey="PLAT-7" />);
    expect((await screen.findByText('over to you')).className).toBe('work-handoff-note');
    expect(screen.getByText('because').className).toBe('work-event-note');
  });
});
