import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import WorkCard from './WorkCard';

const ITEM = {
  id: 7, key: 'PLAT-7', level: 'task', title: 'Fix the flux capacitor', status: 'ready', priority: 2,
  dueOn: '2099-11-22', assignee: 'bob-login', assigneeName: 'Bob B', version: 3,
};
const renderCard = (props = {}) => render(
  <MemoryRouter><WorkCard item={ITEM} productKey="wikantik" {...props} /></MemoryRouter>);

describe('WorkCard', () => {
  it('shows key, title, product key, assignee display name, priority and due date', () => {
    renderCard();
    expect(screen.getByRole('link', { name: 'PLAT-7' })).toHaveAttribute('href', '/work/item/PLAT-7');
    expect(screen.getByText('Fix the flux capacitor')).toBeInTheDocument();
    expect(screen.getByText('wikantik')).toBeInTheDocument();
    expect(screen.getByText('Bob B')).toBeInTheDocument();
    expect(screen.queryByText('bob-login')).not.toBeInTheDocument();
    expect(screen.getByText('P2')).toBeInTheDocument();
    expect(screen.getByText(/due Nov 22/)).toBeInTheDocument();
  });

  it('has the overdue class when the due date is past and the item is open', () => {
    const { container } = renderCard({ item: { ...ITEM, dueOn: '2020-01-01' } });
    expect(container.querySelector('.work-card')).toHaveClass('overdue');
  });

  it('does not flag a closed item or a future date as overdue', () => {
    const closed = renderCard({ item: { ...ITEM, dueOn: '2020-01-01', status: 'done' } });
    expect(closed.container.querySelector('.work-card')).not.toHaveClass('overdue');
    const future = renderCard();
    expect(future.container.querySelector('.work-card')).not.toHaveClass('overdue');
  });

  it('shows a blocked marker and the comment count when present', () => {
    renderCard({ item: { ...ITEM, blocked: true, commentCount: 3 } });
    expect(screen.getByText('Blocked')).toBeInTheDocument();
    expect(screen.getByLabelText('3 comments')).toBeInTheDocument();
  });

  it('omits the blocked marker and comment count otherwise', () => {
    renderCard();
    expect(screen.queryByText('Blocked')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/comments/)).not.toBeInTheDocument();
  });

  it('is draggable only when allowed and reports the drag', () => {
    const onDragStart = vi.fn();
    const { container, rerender } = renderCard({ draggable: true, onDragStart });
    const card = container.querySelector('.work-card');
    expect(card).toHaveAttribute('draggable', 'true');
    fireEvent.dragStart(card);
    expect(onDragStart).toHaveBeenCalledWith(ITEM);
    rerender(<MemoryRouter><WorkCard item={ITEM} draggable={false} /></MemoryRouter>);
    expect(container.querySelector('.work-card')).toHaveAttribute('draggable', 'false');
  });

  it('offers a Move to menu that reports the chosen status', () => {
    const onMove = vi.fn();
    renderCard({ onMove });
    fireEvent.change(screen.getByLabelText('Move PLAT-7 to…'), { target: { value: 'review' } });
    expect(onMove).toHaveBeenCalledWith(ITEM, 'review');
  });

  it('offers no Move to menu without a handler', () => {
    renderCard();
    expect(screen.queryByLabelText('Move PLAT-7 to…')).not.toBeInTheDocument();
  });

  describe('queues and claims', () => {
    const NOW = new Date(2026, 10, 16, 14, 0);
    const until = (h, m, day = 16) => new Date(2026, 10, day, h, m).toISOString();
    const claimed = { ...ITEM, assignee: null, assigneeName: null, status: 'in_progress', queue: 'implementer', queueName: 'Implementer', claimedByAgent: 3,
      claimedByName: 'codex@docker2', claimedAt: until(13, 0), claimExpiresAt: until(15, 40) };

    it('showsQueueChip', () => {
      renderCard({ item: { ...ITEM, queue: 'implementer', queueName: 'Implementer', status: 'draft' }, now: NOW });
      expect(screen.getByText('Queue · Implementer')).toBeInTheDocument();
    });

    it('showsClaimedByAndUntil, distinct from a person, with the full timestamp as its name', () => {
      const { container } = renderCard({ item: claimed, now: NOW });
      const chip = screen.getByText('codex@docker2 · until 15:40');
      expect(chip.closest('.work-card-claim')).toBeTruthy();
      expect(chip.closest('.work-card-claim')).toHaveAttribute('aria-label',
        expect.stringContaining('Claimed by codex@docker2'));
      expect(chip.closest('.work-card-claim')).toHaveAttribute('title', expect.stringContaining(new Date(claimed.claimExpiresAt).toLocaleString()));
      expect(container.querySelector('.work-card-assignee')).toBeNull();
      expect(screen.queryByText('Available to agents')).toBeNull();
    });

    it('expiredClaimMarked: a claim in the past is marked lapsed in words and class', () => {
      const { container } = renderCard({ item: { ...claimed, claimExpiresAt: until(13, 30) }, now: NOW });
      expect(container.querySelector('.claim-expired')).toBeTruthy();
      expect(screen.getByText(/claim lapsed 13:30/)).toBeInTheDocument();
    });

    it('a claim holder with no resolved name reads as an agent, not an id', () => {
      renderCard({ item: { ...claimed, claimedByName: null }, now: NOW });
      expect(screen.getByText('an agent · until 15:40')).toBeInTheDocument();
    });

    it('availableToAgents: queued, ready or review, and unclaimed', () => {
      renderCard({ item: { ...ITEM, queue: 'implementer', queueName: 'Implementer', claimedByAgent: null }, now: NOW });
      expect(screen.getByText('Available to agents')).toBeInTheDocument();
    });

    it('is not available to agents when in progress, unqueued or claimed', () => {
      const a = renderCard({ item: { ...ITEM, status: 'in_progress', queue: 'q', queueName: 'Q' }, now: NOW });
      expect(a.container.textContent).not.toContain('Available to agents');
      const b = renderCard({ item: { ...ITEM, queue: null }, now: NOW });
      expect(b.container.textContent).not.toContain('Available to agents');
    });

    describe('expiry while the board is open', () => {
      afterEach(() => vi.useRealTimers());
      it('the chip turns to lapsed when the expiry passes, and its timer goes on unmount', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date(2026, 10, 16, 15, 39, 0));
        const { container, unmount } = renderCard({ item: claimed });
        expect(screen.getByText('codex@docker2 · until 15:40')).toBeInTheDocument();
        act(() => { vi.advanceTimersByTime(61 * 1000); });
        expect(container.querySelector('.claim-expired')).toBeTruthy();
        expect(screen.getByText(/claim lapsed 15:40/)).toBeInTheDocument();
        unmount();
        expect(vi.getTimerCount()).toBe(0);
      });
    });
  });
});
