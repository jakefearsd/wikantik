import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import SelectionBar from './SelectionBar';

const makeActions = (n) =>
  Array.from({ length: n }, (_, i) => ({
    id: `action-${i}`,
    label: `Action ${i}`,
    variant: i === n - 1 ? 'danger' : 'default',
  }));

describe('SelectionBar', () => {
  it('renders the selected count', () => {
    render(
      <SelectionBar
        selectedCount={3}
        selectedRows={[]}
        actions={[]}
        onAction={vi.fn()}
        onClear={vi.fn()}
      />
    );
    expect(screen.getByText('3 selected')).toBeInTheDocument();
  });

  it('renders nothing when selectedCount is 0', () => {
    const { container } = render(
      <SelectionBar
        selectedCount={0}
        selectedRows={[]}
        actions={[{ id: 'a', label: 'Do it' }]}
        onAction={vi.fn()}
        onClear={vi.fn()}
      />
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders up to 4 inline action buttons', () => {
    render(
      <SelectionBar
        selectedCount={1}
        selectedRows={[]}
        actions={makeActions(4)}
        onAction={vi.fn()}
        onClear={vi.fn()}
      />
    );
    expect(screen.getByText('Action 0')).toBeInTheDocument();
    expect(screen.getByText('Action 1')).toBeInTheDocument();
    expect(screen.getByText('Action 2')).toBeInTheDocument();
    expect(screen.getByText('Action 3')).toBeInTheDocument();
    expect(screen.queryByText('More ▾')).not.toBeInTheDocument();
  });

  it('overflows into More menu beyond 4 actions', () => {
    render(
      <SelectionBar
        selectedCount={1}
        selectedRows={[]}
        actions={makeActions(6)}
        onAction={vi.fn()}
        onClear={vi.fn()}
      />
    );
    // First 4 inline
    expect(screen.getByText('Action 0')).toBeInTheDocument();
    expect(screen.getByText('Action 3')).toBeInTheDocument();
    // 5th and 6th in overflow
    expect(screen.queryByText('Action 4')).not.toBeInTheDocument();
    expect(screen.getByText('More ▾')).toBeInTheDocument();
  });

  it('clicking More opens the overflow menu with remaining actions', () => {
    render(
      <SelectionBar
        selectedCount={1}
        selectedRows={[]}
        actions={makeActions(6)}
        onAction={vi.fn()}
        onClear={vi.fn()}
      />
    );
    fireEvent.click(screen.getByText('More ▾'));
    expect(screen.getByText('Action 4')).toBeInTheDocument();
    expect(screen.getByText('Action 5')).toBeInTheDocument();
  });

  it('Clear button fires onClear', () => {
    const onClear = vi.fn();
    render(
      <SelectionBar
        selectedCount={2}
        selectedRows={[]}
        actions={[]}
        onAction={vi.fn()}
        onClear={onClear}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /Clear selection/i }));
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  it('danger action button has btn-danger class', () => {
    render(
      <SelectionBar
        selectedCount={1}
        selectedRows={[]}
        actions={[{ id: 'del', label: 'Delete', variant: 'danger' }]}
        onAction={vi.fn()}
        onClear={vi.fn()}
      />
    );
    const btn = screen.getByText('Delete');
    expect(btn.className).toMatch(/btn-danger/);
  });

  it('primary action button has btn-primary class', () => {
    render(
      <SelectionBar
        selectedCount={1}
        selectedRows={[]}
        actions={[{ id: 'approve', label: 'Approve', variant: 'primary' }]}
        onAction={vi.fn()}
        onClear={vi.fn()}
      />
    );
    const btn = screen.getByText('Approve');
    expect(btn.className).toMatch(/btn-primary/);
  });

  it('clicking an inline action calls onAction with the action', () => {
    const onAction = vi.fn();
    const action = { id: 'foo', label: 'Foo' };
    render(
      <SelectionBar
        selectedCount={1}
        selectedRows={[]}
        actions={[action]}
        onAction={onAction}
        onClear={vi.fn()}
      />
    );
    fireEvent.click(screen.getByText('Foo'));
    expect(onAction).toHaveBeenCalledWith(action);
  });

  it('disabled action is button-disabled and shows tooltip text', () => {
    const action = {
      id: 'foo',
      label: 'Foo',
      disabled: () => 'Not allowed',
    };
    render(
      <SelectionBar
        selectedCount={1}
        selectedRows={[]}
        actions={[action]}
        onAction={vi.fn()}
        onClear={vi.fn()}
      />
    );
    const btn = screen.getByText('Foo');
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('title', 'Not allowed');
  });
});
