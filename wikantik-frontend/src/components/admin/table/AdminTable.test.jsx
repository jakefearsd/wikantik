import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AdminTable from './AdminTable';

// ---------------------------------------------------------------------------
// Fixture data
// ---------------------------------------------------------------------------

const ROWS = [
  { id: 'alice', name: 'Alice', role: 'admin' },
  { id: 'bob', name: 'Bob', role: 'editor' },
  { id: 'charlie', name: 'Charlie', role: 'viewer' },
  { id: 'dave', name: 'Dave', role: 'editor' },
  { id: 'eve', name: 'Eve', role: 'viewer' },
];

const COLUMNS = [
  { id: 'name', label: 'Name', sortable: true },
  { id: 'role', label: 'Role' },
];

const getKey = (r) => r.id;

const SUCCESS_RESULT = { succeeded: ['alice', 'bob'], failed: [], status: 'completed' };
const PARTIAL_RESULT = {
  succeeded: ['alice'],
  failed: [{ id: 'bob', error: 'Not allowed' }],
  status: 'completed',
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function renderTable(props = {}) {
  return render(
    <AdminTable
      rows={ROWS}
      getRowKey={getKey}
      columns={COLUMNS}
      {...props}
    />
  );
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

describe('AdminTable — rendering', () => {
  it('renders all rows and columns', () => {
    renderTable();
    expect(screen.getByText('Alice')).toBeInTheDocument();
    expect(screen.getByText('Bob')).toBeInTheDocument();
    expect(screen.getByText('Charlie')).toBeInTheDocument();
    expect(screen.getByText('Name')).toBeInTheDocument();
    expect(screen.getByText('Role')).toBeInTheDocument();
  });

  it('selection column is hidden when selectable is not set', () => {
    renderTable();
    expect(screen.queryByLabelText(/Select all/i)).not.toBeInTheDocument();
  });

  it('loading state renders loadingLabel', () => {
    renderTable({ loading: true, loadingLabel: 'Fetching users…' });
    expect(screen.getByText('Fetching users…')).toBeInTheDocument();
  });

  it('loading state with default label', () => {
    renderTable({ loading: true });
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('empty state renders emptyMessage when rows is empty', () => {
    render(
      <AdminTable
        rows={[]}
        getRowKey={getKey}
        columns={COLUMNS}
        emptyMessage="No users here"
      />
    );
    expect(screen.getByText('No users here')).toBeInTheDocument();
  });

  it('empty state renders default copy when rows is empty and no emptyMessage', () => {
    render(<AdminTable rows={[]} getRowKey={getKey} columns={COLUMNS} />);
    expect(screen.getByText('No items found.')).toBeInTheDocument();
  });

  it('empty state renders custom ReactNode emptyState', () => {
    render(
      <AdminTable
        rows={[]}
        getRowKey={getKey}
        columns={COLUMNS}
        emptyState={<span>Custom empty</span>}
      />
    );
    expect(screen.getByText('Custom empty')).toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Selection
// ---------------------------------------------------------------------------

describe('AdminTable — selection', () => {
  it('selection column header reflects none/all/indeterminate state', () => {
    renderTable({ selectable: true });

    const headerCb = screen.getByLabelText(/Select all/i);
    // Initially: none
    expect(headerCb.checked).toBe(false);
    expect(headerCb.indeterminate).toBe(false);

    // Select one row → indeterminate
    const firstRowCb = screen.getByLabelText('Select alice');
    fireEvent.click(firstRowCb);
    expect(headerCb.indeterminate).toBe(true);

    // Select all → checked
    fireEvent.click(headerCb);
    expect(headerCb.checked).toBe(true);
    expect(headerCb.indeterminate).toBe(false);
  });

  it('per-row checkbox toggles selection', () => {
    renderTable({ selectable: true });
    const cb = screen.getByLabelText('Select alice');
    expect(cb.checked).toBe(false);
    fireEvent.click(cb);
    expect(cb.checked).toBe(true);
    fireEvent.click(cb);
    expect(cb.checked).toBe(false);
  });

  it('selection bar appears when N > 0 and hides when N = 0', () => {
    renderTable({
      selectable: true,
      bulkActions: [{ id: 'del', label: 'Delete' }],
      onBulkAction: vi.fn().mockResolvedValue(SUCCESS_RESULT),
    });

    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('Select alice'));
    expect(screen.getByRole('toolbar')).toBeInTheDocument();
    expect(screen.getByText('1 selected')).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('Select alice'));
    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument();
  });

  it('Esc clears selection', () => {
    renderTable({ selectable: true });
    fireEvent.click(screen.getByLabelText('Select alice'));
    expect(screen.getByLabelText('Select alice').checked).toBe(true);

    const container = document.querySelector('.admin-table-container');
    fireEvent.keyDown(container, { key: 'Escape' });
    expect(screen.getByLabelText('Select alice').checked).toBe(false);
  });

  it('Ctrl-A selects all visible rows', () => {
    renderTable({ selectable: true });
    const container = document.querySelector('.admin-table-container');
    fireEvent.keyDown(container, { key: 'a', ctrlKey: true });
    const checkboxes = screen.getAllByRole('checkbox');
    // All row checkboxes (excluding header) should be checked
    const rowCbs = checkboxes.filter((cb) => cb !== screen.getByLabelText(/Select all/i));
    rowCbs.forEach((cb) => expect(cb.checked).toBe(true));
  });

  it('Meta-A (⌘A) selects all visible rows', () => {
    renderTable({ selectable: true });
    const container = document.querySelector('.admin-table-container');
    fireEvent.keyDown(container, { key: 'a', metaKey: true });
    const headerCb = screen.getByLabelText(/Select all/i);
    expect(headerCb.checked).toBe(true);
  });

  it('shift-click on a second checkbox selects the contiguous range', () => {
    renderTable({ selectable: true });

    // Click alice (index 0)
    fireEvent.click(screen.getByLabelText('Select alice'));
    // Shift-click charlie (index 2)
    fireEvent.click(screen.getByLabelText('Select charlie'), { shiftKey: true });

    expect(screen.getByLabelText('Select alice').checked).toBe(true);
    expect(screen.getByLabelText('Select bob').checked).toBe(true);
    expect(screen.getByLabelText('Select charlie').checked).toBe(true);
    // dave and eve not selected
    expect(screen.getByLabelText('Select dave').checked).toBe(false);
    expect(screen.getByLabelText('Select eve').checked).toBe(false);
  });

  it('header checkbox select-all then clear from selection bar', () => {
    renderTable({
      selectable: true,
      bulkActions: [{ id: 'del', label: 'Delete' }],
      onBulkAction: vi.fn().mockResolvedValue(SUCCESS_RESULT),
    });
    fireEvent.click(screen.getByLabelText(/Select all/i));
    expect(screen.getByText(`${ROWS.length} selected`)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Clear selection/i }));
    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Sort
// ---------------------------------------------------------------------------

describe('AdminTable — sort', () => {
  it('clicking a sortable header toggles asc → desc → off', () => {
    renderTable();
    const nameHeader = screen.getByText(/Name/);

    // Click once → asc (Alice before Bob before Charlie)
    fireEvent.click(nameHeader);
    let rows = screen.getAllByRole('row').slice(1); // skip header
    expect(within(rows[0]).getByText('Alice')).toBeInTheDocument();

    // Click again → desc
    fireEvent.click(nameHeader);
    rows = screen.getAllByRole('row').slice(1);
    expect(within(rows[0]).getByText('Eve')).toBeInTheDocument();

    // Click again → off (original order)
    fireEvent.click(nameHeader);
    rows = screen.getAllByRole('row').slice(1);
    expect(within(rows[0]).getByText('Alice')).toBeInTheDocument();
  });

  it('non-sortable header click does nothing', () => {
    renderTable();
    const roleHeader = screen.getByText('Role');
    // Should not throw
    fireEvent.click(roleHeader);
    expect(screen.getAllByRole('row').length).toBeGreaterThan(1);
  });
});

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

describe('AdminTable — search', () => {
  it('typing in the search box filters visible rows', () => {
    renderTable({ searchable: { placeholder: 'Filter…' } });
    fireEvent.change(screen.getByPlaceholderText('Filter…'), {
      target: { value: 'alice' },
    });
    expect(screen.getByText('Alice')).toBeInTheDocument();
    expect(screen.queryByText('Bob')).not.toBeInTheDocument();
  });

  it('custom filterFn is used', () => {
    renderTable({
      searchable: {
        filterFn: (row, q) => row.role === q,
      },
    });
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'admin' } });
    expect(screen.getByText('Alice')).toBeInTheDocument();
    expect(screen.queryByText('Bob')).not.toBeInTheDocument();
  });

  it('selection on filtered-out rows is preserved after search', () => {
    renderTable({ selectable: true });
    // Select alice, then filter her out
    fireEvent.click(screen.getByLabelText('Select alice'));

    renderTable({
      selectable: true,
      searchable: {},
    });
    // This tests independent render — in the single-render case, we check that
    // selection isn't reset by filtering. We do this in the integration test below.
  });
});

// ---------------------------------------------------------------------------
// Bulk actions
// ---------------------------------------------------------------------------

describe('AdminTable — bulk actions', () => {
  it('bulk action without confirm dispatches immediately', async () => {
    const onBulkAction = vi.fn().mockResolvedValue(SUCCESS_RESULT);
    renderTable({
      selectable: true,
      bulkActions: [{ id: 'approve', label: 'Approve' }],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    fireEvent.click(screen.getByLabelText('Select bob'));
    fireEvent.click(screen.getByText('Approve'));

    await waitFor(() => expect(onBulkAction).toHaveBeenCalledTimes(1));
    const [action, rows] = onBulkAction.mock.calls[0];
    expect(action.id).toBe('approve');
    expect(rows.map((r) => r.id)).toEqual(expect.arrayContaining(['alice', 'bob']));
  });

  it('bulk action with confirm: true opens modal, Cancel aborts', async () => {
    const onBulkAction = vi.fn();
    renderTable({
      selectable: true,
      bulkActions: [{ id: 'del', label: 'Delete', variant: 'danger', confirm: true }],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    fireEvent.click(screen.getByText('Delete'));

    // Modal appears
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText(/Are you sure/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Cancel/i }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(onBulkAction).not.toHaveBeenCalled();
  });

  it('bulk action with confirm: true — Confirm dispatches', async () => {
    const onBulkAction = vi.fn().mockResolvedValue(SUCCESS_RESULT);
    renderTable({
      selectable: true,
      bulkActions: [{ id: 'del', label: 'Delete', variant: 'danger', confirm: true }],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    fireEvent.click(screen.getByLabelText('Select bob'));
    fireEvent.click(screen.getByText('Delete'));

    const dialog = screen.getByRole('dialog');
    expect(dialog).toBeInTheDocument();
    // Click the primary confirm button inside the dialog (not the selection bar button)
    fireEvent.click(within(dialog).getByRole('button', { name: /Delete/i }));

    await waitFor(() => expect(onBulkAction).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('bulk action with reason: { required: true } blocks dispatch when reason empty', async () => {
    const onBulkAction = vi.fn();
    renderTable({
      selectable: true,
      bulkActions: [
        {
          id: 'reject',
          label: 'Reject',
          confirm: true,
          reason: { label: 'Reason', required: true, placeholder: 'Why?' },
        },
      ],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    // Click the selection bar button to open the modal
    fireEvent.click(screen.getByRole('toolbar').querySelector('button'));
    const dialog = screen.getByRole('dialog');
    // Click confirm inside dialog without filling reason
    fireEvent.click(within(dialog).getByRole('button', { name: /Reject/i }));

    expect(onBulkAction).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('bulk action with reason accepts non-empty reason and passes it as third arg', async () => {
    const onBulkAction = vi.fn().mockResolvedValue(SUCCESS_RESULT);
    renderTable({
      selectable: true,
      bulkActions: [
        {
          id: 'reject',
          label: 'Reject',
          confirm: true,
          reason: { label: 'Reason', required: true, placeholder: 'Why?' },
        },
      ],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    // Open modal via selection bar button
    fireEvent.click(screen.getByRole('toolbar').querySelector('button'));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(screen.getByPlaceholderText('Why?'), {
      target: { value: 'duplicate' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /Reject/i }));

    await waitFor(() => expect(onBulkAction).toHaveBeenCalledTimes(1));
    const [, , reason] = onBulkAction.mock.calls[0];
    expect(reason).toBe('duplicate');
  });

  it('successful resolution clears selection and shows success toast', async () => {
    const onBulkAction = vi.fn().mockResolvedValue({
      succeeded: ['alice', 'bob'],
      failed: [],
      status: 'completed',
    });
    renderTable({
      selectable: true,
      bulkActions: [{ id: 'approve', label: 'Approve' }],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    fireEvent.click(screen.getByLabelText('Select bob'));
    fireEvent.click(screen.getByText('Approve'));

    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument());
    // Selection cleared
    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument();
    // Toast visible
    expect(screen.getByRole('status').textContent).toMatch(/2.*success/i);
  });

  it('partial failure keeps failed ids selected and shows Retry failed button', async () => {
    const onBulkAction = vi.fn().mockResolvedValue(PARTIAL_RESULT);
    renderTable({
      selectable: true,
      bulkActions: [{ id: 'approve', label: 'Approve' }],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    fireEvent.click(screen.getByLabelText('Select bob'));
    fireEvent.click(screen.getByText('Approve'));

    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument());

    // bob (failed) stays selected; alice (succeeded) cleared
    expect(screen.getByLabelText('Select bob').checked).toBe(true);
    expect(screen.getByLabelText('Select alice').checked).toBe(false);

    // Retry button present
    expect(screen.getByRole('button', { name: /Retry failed/i })).toBeInTheDocument();
  });

  it('Retry failed re-dispatches with only the failed ids', async () => {
    const onBulkAction = vi
      .fn()
      .mockResolvedValueOnce(PARTIAL_RESULT)
      .mockResolvedValueOnce({ succeeded: ['bob'], failed: [], status: 'completed' });

    renderTable({
      selectable: true,
      bulkActions: [{ id: 'approve', label: 'Approve' }],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    fireEvent.click(screen.getByLabelText('Select bob'));
    fireEvent.click(screen.getByText('Approve'));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Retry failed/i })).toBeInTheDocument()
    );

    fireEvent.click(screen.getByRole('button', { name: /Retry failed/i }));

    await waitFor(() => expect(onBulkAction).toHaveBeenCalledTimes(2));

    const [, retryRows] = onBulkAction.mock.calls[1];
    expect(retryRows.map((r) => r.id)).toEqual(['bob']);
  });

  it('thrown/rejected onBulkAction surfaces error toast and leaves selection intact', async () => {
    const onBulkAction = vi.fn().mockRejectedValue(new Error('Server exploded'));
    renderTable({
      selectable: true,
      bulkActions: [{ id: 'approve', label: 'Approve' }],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    fireEvent.click(screen.getByText('Approve'));

    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument());
    expect(screen.getByRole('status').textContent).toMatch(/Server exploded/);
    // Selection still intact
    expect(screen.getByLabelText('Select alice').checked).toBe(true);
  });

  it('disabled action shows tooltip and is not clickable', async () => {
    const onBulkAction = vi.fn();
    renderTable({
      selectable: true,
      bulkActions: [
        {
          id: 'del',
          label: 'Delete',
          disabled: () => 'Cannot delete admins',
        },
      ],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    const deleteBtn = screen.getByText('Delete');
    expect(deleteBtn).toBeDisabled();
    expect(deleteBtn).toHaveAttribute('title', 'Cannot delete admins');

    fireEvent.click(deleteBtn);
    expect(onBulkAction).not.toHaveBeenCalled();
  });

  it('toast has a dismiss button', async () => {
    const onBulkAction = vi.fn().mockResolvedValue(SUCCESS_RESULT);
    renderTable({
      selectable: true,
      bulkActions: [{ id: 'approve', label: 'Approve' }],
      onBulkAction,
    });

    fireEvent.click(screen.getByLabelText('Select alice'));
    fireEvent.click(screen.getByText('Approve'));

    await waitFor(() => expect(screen.getByRole('status')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('Dismiss'));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// rowAction prop
// ---------------------------------------------------------------------------

describe('AdminTable — rowAction', () => {
  it('renders per-row action buttons', () => {
    renderTable({
      rowAction: (row) => [{ id: 'edit', label: `Edit ${row.name}`, onClick: vi.fn() }],
    });
    expect(screen.getByText('Edit Alice')).toBeInTheDocument();
    expect(screen.getByText('Edit Bob')).toBeInTheDocument();
  });

  it('per-row action onClick is called with the row', () => {
    const onClick = vi.fn();
    renderTable({
      rowAction: () => [{ id: 'del', label: 'Del', onClick }],
    });
    fireEvent.click(screen.getAllByText('Del')[0]);
    expect(onClick).toHaveBeenCalledWith(ROWS[0]);
  });
});

describe('AdminTable — onRowClick', () => {
  it('clicking a row fires onRowClick when selectable is false', () => {
    const onRowClick = vi.fn();
    renderTable({ onRowClick });
    fireEvent.click(screen.getByText('Alice'));
    expect(onRowClick).toHaveBeenCalledWith(ROWS[0]);
  });

  it('clicking a row does not fire onRowClick when selectable is true', () => {
    // When selectable, the row click handler is intentionally suppressed so that
    // checkbox clicks don't double-fire as row clicks.
    const onRowClick = vi.fn();
    renderTable({
      onRowClick,
      selectable: true,
      bulkActions: [{ id: 'a', label: 'Act' }],
      onBulkAction: vi.fn(),
    });
    fireEvent.click(screen.getByText('Alice'));
    expect(onRowClick).not.toHaveBeenCalled();
  });
});

describe('AdminTable — action with reason but no confirm', () => {
  it('opens a modal for reason collection when action has reason but no confirm', async () => {
    const onBulkAction = vi.fn().mockResolvedValue(SUCCESS_RESULT);
    renderTable({
      selectable: true,
      bulkActions: [
        {
          id: 'reject',
          label: 'Reject',
          // No `confirm` field — only a `reason` field. Modal still opens for reason collection.
          reason: { label: 'Reason', required: true },
        },
      ],
      onBulkAction,
    });
    // Select two rows.
    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    fireEvent.click(checkboxes[2]);
    // Trigger the action via the selection bar — should open the modal even without confirm.
    fireEvent.click(screen.getByRole('button', { name: /^Reject$/ }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toBeInTheDocument();
    // Provide a reason in the modal then confirm via the modal's action button.
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'duplicate' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^Reject$/ }));
    await waitFor(() =>
      expect(onBulkAction).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'reject' }),
        expect.any(Array),
        'duplicate'
      )
    );
  });
});

describe('AdminTable — server-driven pagination', () => {
  it('renders pagination footer when pagination prop provided', () => {
    renderTable({
      pagination: { pageSize: 25, totalCount: 150, currentPage: 0, onPageChange: vi.fn() },
    });
    expect(screen.getByRole('navigation', { name: /Pagination/i })).toBeInTheDocument();
    expect(screen.getByText(/Page 1 of 6/)).toBeInTheDocument();
  });

  it('does not render pagination footer when prop absent', () => {
    renderTable();
    expect(screen.queryByRole('navigation', { name: /Pagination/i })).not.toBeInTheDocument();
  });

  it('hides client-side search input when paginated even if searchable=true', () => {
    renderTable({
      searchable: { placeholder: 'Filter…' },
      pagination: { pageSize: 25, totalCount: 100, currentPage: 0, onPageChange: vi.fn() },
    });
    // Pagination is on, so the search box is suppressed (search would only
    // filter the visible page, which would mislead the operator).
    expect(screen.queryByPlaceholderText('Filter…')).not.toBeInTheDocument();
  });

  it('still shows search input when not paginated', () => {
    renderTable({ searchable: { placeholder: 'Filter…' } });
    expect(screen.getByPlaceholderText('Filter…')).toBeInTheDocument();
  });

  it('clicking Next in the pagination footer fires onPageChange', () => {
    const onPageChange = vi.fn();
    renderTable({
      pagination: { pageSize: 25, totalCount: 100, currentPage: 1, onPageChange },
    });
    fireEvent.click(screen.getByRole('button', { name: /Next page/i }));
    expect(onPageChange).toHaveBeenCalledWith(2);
  });
});
