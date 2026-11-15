import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
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
});
