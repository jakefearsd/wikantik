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
});
