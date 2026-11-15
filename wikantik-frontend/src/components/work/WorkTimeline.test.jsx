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
});
