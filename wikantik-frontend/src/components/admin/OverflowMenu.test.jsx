import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import OverflowMenu from './OverflowMenu';

const ACTIONS = [
  { label: 'Edit', onClick: vi.fn() },
  { label: 'Delete', onClick: vi.fn() },
  { label: 'Archive', onClick: vi.fn(), disabled: true },
];

function renderMenu(props = {}) {
  return render(<OverflowMenu actions={ACTIONS} {...props} />);
}

describe('OverflowMenu', () => {
  it('renders a trigger button but no menu initially', () => {
    renderMenu();
    expect(screen.getByRole('button', { name: /more actions/i })).toBeInTheDocument();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('trigger has aria-haspopup="menu" and aria-expanded="false" when closed', () => {
    renderMenu();
    const trigger = screen.getByRole('button', { name: /more actions/i });
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('clicking the trigger opens the menu', () => {
    renderMenu();
    const trigger = screen.getByRole('button', { name: /more actions/i });
    fireEvent.click(trigger);
    expect(screen.getByRole('menu')).toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
  });

  it('clicking the trigger again closes the menu', () => {
    renderMenu();
    const trigger = screen.getByRole('button', { name: /more actions/i });
    fireEvent.click(trigger);
    fireEvent.click(trigger);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('menu renders all action labels as menuitems', () => {
    renderMenu();
    fireEvent.click(screen.getByRole('button', { name: /more actions/i }));
    expect(screen.getByRole('menuitem', { name: 'Edit' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Archive' })).toBeInTheDocument();
  });

  it('clicking an action calls its onClick and closes the menu', () => {
    const onClick = vi.fn();
    render(<OverflowMenu actions={[{ label: 'Edit', onClick }]} />);
    fireEvent.click(screen.getByRole('button', { name: /more actions/i }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('clicking a disabled action does not call its onClick', () => {
    const onClick = vi.fn();
    render(<OverflowMenu actions={[{ label: 'Archive', onClick, disabled: true }]} />);
    fireEvent.click(screen.getByRole('button', { name: /more actions/i }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Archive' }));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('pressing Escape closes the menu', () => {
    renderMenu();
    fireEvent.click(screen.getByRole('button', { name: /more actions/i }));
    expect(screen.getByRole('menu')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('clicking outside closes the menu', () => {
    renderMenu();
    fireEvent.click(screen.getByRole('button', { name: /more actions/i }));
    expect(screen.getByRole('menu')).toBeInTheDocument();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});
