import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import BulkActionMenu from './BulkActionMenu';

const baseAction = (id, overrides = {}) => ({
  id,
  label: `Action ${id}`,
  ...overrides,
});

describe('BulkActionMenu', () => {
  it('does not render the popover until More is clicked', () => {
    render(
      <BulkActionMenu
        actions={[baseAction('a'), baseAction('b')]}
        selectedRows={[]}
        onAction={vi.fn()}
      />
    );
    expect(screen.getByRole('button', { name: /More bulk actions/i })).toBeInTheDocument();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('clicking More opens the popover with menu items', () => {
    render(
      <BulkActionMenu
        actions={[baseAction('a'), baseAction('b')]}
        selectedRows={[]}
        onAction={vi.fn()}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /More bulk actions/i }));
    expect(screen.getByRole('menu')).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Action a/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Action b/i })).toBeInTheDocument();
  });

  it('clicking a menu item fires onAction and closes the popover', () => {
    const onAction = vi.fn();
    const action = baseAction('approve');
    render(
      <BulkActionMenu
        actions={[action, baseAction('reject')]}
        selectedRows={[]}
        onAction={onAction}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /More bulk actions/i }));
    fireEvent.click(screen.getByRole('menuitem', { name: /Action approve/i }));
    expect(onAction).toHaveBeenCalledWith(action);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('clicking outside closes the popover', () => {
    render(
      <div>
        <BulkActionMenu
          actions={[baseAction('a')]}
          selectedRows={[]}
          onAction={vi.fn()}
        />
        <button data-testid="outside">outside</button>
      </div>
    );
    fireEvent.click(screen.getByRole('button', { name: /More bulk actions/i }));
    expect(screen.getByRole('menu')).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByTestId('outside'));
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('clicking inside the popover does not close it', () => {
    render(
      <BulkActionMenu
        actions={[baseAction('a'), baseAction('b')]}
        selectedRows={[]}
        onAction={vi.fn()}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /More bulk actions/i }));
    const menu = screen.getByRole('menu');
    fireEvent.mouseDown(menu);
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  it('disabled menu item is disabled and surfaces tooltip text', () => {
    const action = baseAction('blocked', { disabled: () => 'Cannot do this' });
    const onAction = vi.fn();
    render(
      <BulkActionMenu
        actions={[action]}
        selectedRows={[]}
        onAction={onAction}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /More bulk actions/i }));
    const item = screen.getByRole('menuitem', { name: /Action blocked/i });
    expect(item).toBeDisabled();
    expect(item).toHaveAttribute('title', 'Cannot do this');
    fireEvent.click(item);
    expect(onAction).not.toHaveBeenCalled();
  });

  it('aria-expanded reflects open state', () => {
    render(
      <BulkActionMenu
        actions={[baseAction('a')]}
        selectedRows={[]}
        onAction={vi.fn()}
      />
    );
    const trigger = screen.getByRole('button', { name: /More bulk actions/i });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('danger-variant menu item gets the danger color', () => {
    render(
      <BulkActionMenu
        actions={[baseAction('del', { variant: 'danger' })]}
        selectedRows={[]}
        onAction={vi.fn()}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /More bulk actions/i }));
    const item = screen.getByRole('menuitem', { name: /Action del/i });
    // jsdom preserves the raw inline color value (#C44), so match the source string.
    expect(item.style.color.toUpperCase()).toBe('#C44');
  });

  it('disabled predicate receives selectedRows', () => {
    const disabled = vi.fn(() => false);
    const rows = [{ id: 1 }, { id: 2 }];
    render(
      <BulkActionMenu
        actions={[baseAction('a', { disabled })]}
        selectedRows={rows}
        onAction={vi.fn()}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /More bulk actions/i }));
    expect(disabled).toHaveBeenCalledWith(rows);
  });
});
