import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import WaitingLane from './WaitingLane';

const ITEMS = [
  { id: 1, key: 'PLAT-1', title: 'Review me', waiting: { reason: 'assigned', byName: 'codex', byKind: 'agent', note: 'please look' } },
  { id: 2, key: 'PLAT-2', title: 'Mentioned you', waiting: { reason: 'mentioned', byName: 'Jane Doe', byKind: 'human' } },
  { id: 3, key: 'OPS-9', title: 'Restarted the indexer', waiting: { reason: 'acknowledge' } },
];
const lane = (props = {}) => render(<MemoryRouter><WaitingLane items={ITEMS} {...props} /></MemoryRouter>);

describe('WaitingLane', () => {
  it('keeps what waits on you apart from what was done without you', () => {
    lane({ onAcknowledge: vi.fn() });
    expect(screen.getByText('Waiting on you (2)')).toBeInTheDocument();
    expect(screen.getByText(/Assigned by codex \(agent\)/)).toBeInTheDocument();
    expect(screen.getByText('please look')).toBeInTheDocument();
    const done = screen.getByTestId('done-without-you');
    expect(within(done).getByText('Done without you (1)')).toBeInTheDocument();
    expect(within(done).getByRole('link', { name: 'OPS-9' })).toHaveAttribute('href', '/work/item/OPS-9');
    expect(within(done).queryByText(/Assigned by/)).toBeNull();
  });

  it('acknowledges the item that was clicked', () => {
    const onAcknowledge = vi.fn();
    lane({ onAcknowledge });
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge OPS-9' }));
    expect(onAcknowledge).toHaveBeenCalledWith(ITEMS[2]);
  });

  it('shows no button when the viewer may not acknowledge', () => {
    lane();
    expect(screen.getByTestId('done-without-you')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows only the group that has items', () => {
    render(<MemoryRouter><WaitingLane items={[ITEMS[2]]} /></MemoryRouter>);
    expect(screen.queryByText(/Waiting on you/)).toBeNull();
    render(<MemoryRouter><WaitingLane items={[ITEMS[0]]} /></MemoryRouter>);
    expect(screen.getAllByTestId('done-without-you')).toHaveLength(1);
  });

  it('groups merges outside policy apart, not as assigned, and tolerates an item that is also assigned', () => {
    const warn = vi.spyOn(console, 'error').mockImplementation(() => {});
    const items = [
      { id: 1, key: 'PLAT-1', title: 'Assigned too', waiting: { reason: 'assigned', byName: 'codex', byKind: 'agent' } },
      { id: 1, key: 'PLAT-1', title: 'Assigned too', waiting: { reason: 'change_outside_policy' } },
      { id: 4, key: 'PLAT-4', title: 'Pushed to main', waiting: { reason: 'change_outside_policy' } },
    ];
    render(<MemoryRouter><WaitingLane items={items} /></MemoryRouter>);
    const g = screen.getByTestId('outside-policy');
    expect(within(g).getByText('Merged outside policy (2)')).toBeInTheDocument();
    expect(g).toHaveTextContent('merged on the code host without review, or committed directly where that is not allowed');
    expect(within(g).getByRole('link', { name: 'PLAT-4' })).toHaveAttribute('href', '/work/item/PLAT-4');
    expect(within(g).queryByText(/Assigned by/)).toBeNull();
    expect(within(g).queryByRole('button')).toBeNull();
    expect(screen.getByText('Waiting on you (1)')).toBeInTheDocument();
    expect(warn.mock.calls.flat().join(' ')).not.toMatch(/same key/);
    warn.mockRestore();
  });

  it('never says "by someone": with no name it says only what happened', () => {
    render(<MemoryRouter><WaitingLane items={[
      { id: 1, key: 'PLAT-1', title: 'Old assignment', waiting: { reason: 'assigned', byName: null } },
      { id: 2, key: 'PLAT-2', title: 'A mention', waiting: { reason: 'mentioned', byName: null } },
    ]} /></MemoryRouter>);
    expect(screen.getByText(/Assigned to you/)).toBeInTheDocument();
    expect(screen.getByText(/Mentioned in a comment/)).toBeInTheDocument();
    expect(screen.queryByText(/someone/)).toBeNull();
  });
});
