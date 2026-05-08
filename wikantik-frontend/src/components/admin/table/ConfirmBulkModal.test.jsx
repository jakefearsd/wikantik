import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ConfirmBulkModal from './ConfirmBulkModal';

const mkRows = (n) =>
  Array.from({ length: n }, (_, i) => ({ id: `row-${i}`, name: `Item ${i}` }));
const getKey = (r) => r.id;

describe('ConfirmBulkModal', () => {
  it('renders default body with N and label', () => {
    const action = { id: 'del', label: 'Delete', variant: 'danger' };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(3)}
        getRowKey={getKey}
        kindLabel="users"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    );
    // Title present
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    // Body contains the action label, N, and kindLabel
    expect(screen.getByText(/Are you sure you want to/i)).toBeInTheDocument();
    expect(screen.getByText(/3/)).toBeInTheDocument();
    expect(screen.getByText(/users/)).toBeInTheDocument();
  });

  it('sample-row preview shows up to 5 keys', () => {
    const action = { id: 'del', label: 'Delete' };
    const rows = mkRows(7);
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={rows}
        getRowKey={getKey}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    );
    // First 5 keys visible
    expect(screen.getByText(/row-0/)).toBeInTheDocument();
    expect(screen.getByText(/row-4/)).toBeInTheDocument();
    // Overflow shown
    expect(screen.getByText(/\+2 more/)).toBeInTheDocument();
  });

  it('sample-row preview shows exactly 5 keys with no overflow when N=5', () => {
    const action = { id: 'del', label: 'Delete' };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(5)}
        getRowKey={getKey}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    );
    expect(screen.queryByText(/more/)).not.toBeInTheDocument();
  });

  it('Cancel calls onCancel and does not call onConfirm', () => {
    const onCancel = vi.fn();
    const onConfirm = vi.fn();
    const action = { id: 'del', label: 'Delete' };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(1)}
        getRowKey={getKey}
        onConfirm={onConfirm}
        onCancel={onCancel}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /Cancel/i }));
    expect(onCancel).toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('Confirm calls onConfirm without reason when no reason config', () => {
    const onConfirm = vi.fn();
    const action = { id: 'approve', label: 'Approve', variant: 'primary' };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(2)}
        getRowKey={getKey}
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /Approve/i }));
    expect(onConfirm).toHaveBeenCalledWith(undefined);
  });

  it('required reason blocks confirm when empty', () => {
    const onConfirm = vi.fn();
    const action = {
      id: 'reject',
      label: 'Reject',
      variant: 'danger',
      reason: { label: 'Reason', required: true },
    };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(1)}
        getRowKey={getKey}
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /Reject/i }));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('alert').textContent).toMatch(/required/i);
  });

  it('required reason allows confirm when non-empty', () => {
    const onConfirm = vi.fn();
    const action = {
      id: 'reject',
      label: 'Reject',
      variant: 'danger',
      reason: { label: 'Reason', required: true, placeholder: 'Enter reason' },
    };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(1)}
        getRowKey={getKey}
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />
    );
    fireEvent.change(screen.getByPlaceholderText('Enter reason'), {
      target: { value: 'duplicate entry' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Reject/i }));
    expect(onConfirm).toHaveBeenCalledWith('duplicate entry');
  });

  it('optional reason allows confirm when empty', () => {
    const onConfirm = vi.fn();
    const action = {
      id: 'reject',
      label: 'Reject',
      reason: { label: 'Reason', required: false },
    };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(1)}
        getRowKey={getKey}
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /Reject/i }));
    expect(onConfirm).toHaveBeenCalledWith('');
  });

  it('custom body(rows) renders correctly', () => {
    const action = {
      id: 'nuke',
      label: 'Nuke',
      confirm: {
        title: 'Nuke all?',
        body: (rows) => <span>Custom body for {rows.length} rows</span>,
        confirmLabel: 'Yes, nuke',
      },
    };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(4)}
        getRowKey={getKey}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    );
    expect(screen.getByText(/Nuke all\?/)).toBeInTheDocument();
    expect(screen.getByText('Custom body for 4 rows')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Yes, nuke/i })).toBeInTheDocument();
  });

  it('confirm button has danger class for danger variant', () => {
    const action = { id: 'del', label: 'Delete', variant: 'danger' };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(1)}
        getRowKey={getKey}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    );
    const confirmBtn = screen.getByRole('button', { name: /Delete/i });
    expect(confirmBtn.className).toMatch(/btn-danger/);
  });

  it('confirm button has primary class for primary variant', () => {
    const action = { id: 'go', label: 'Approve', variant: 'primary' };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(1)}
        getRowKey={getKey}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    );
    const confirmBtn = screen.getByRole('button', { name: /Approve/i });
    expect(confirmBtn.className).toMatch(/btn-primary/);
  });

  it('typing in the reason field clears the error message', () => {
    const action = {
      id: 'reject',
      label: 'Reject',
      reason: { label: 'Reason', required: true },
    };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(1)}
        getRowKey={getKey}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    );
    // Trigger the required-empty error.
    fireEvent.click(screen.getByRole('button', { name: /Reject/i }));
    expect(screen.getByRole('alert')).toBeInTheDocument();
    // Typing into the reason should clear it.
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'because' } });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('reason without an explicit label falls back to the default "Reason"', () => {
    const action = {
      id: 'reject',
      label: 'Reject',
      // No `label` field → component should fall back to 'Reason'.
      reason: { required: true },
    };
    render(
      <ConfirmBulkModal
        action={action}
        selectedRows={mkRows(1)}
        getRowKey={getKey}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    );
    // The label text appears next to a required asterisk.
    expect(screen.getByText('Reason')).toBeInTheDocument();
    // And the validation error uses the same fallback label.
    fireEvent.click(screen.getByRole('button', { name: /Reject/i }));
    expect(screen.getByRole('alert').textContent).toMatch(/^Reason is required/);
  });
});
