import { useState, useMemo, useCallback, useEffect, useRef } from 'react';
import { useTableSelection } from './useTableSelection';
import SelectionBar from './SelectionBar';
import ConfirmBulkModal from './ConfirmBulkModal';
import Pagination from './Pagination';
import '../../../styles/admin.css';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const DEFAULT_FILTER_FN = (row, query, columns) => {
  const q = query.toLowerCase();
  return columns.some((col) => {
    if (col.render) {
      // Can't introspect JSX — skip rendered columns in default filter
      return false;
    }
    const val = row[col.id];
    if (val == null) return false;
    return String(val).toLowerCase().includes(q);
  });
};

function SortIcon({ direction }) {
  if (!direction) return <span style={{ opacity: 0.3, marginLeft: 4 }}>⇅</span>;
  return <span style={{ marginLeft: 4 }}>{direction === 'asc' ? '↑' : '↓'}</span>;
}

// ---------------------------------------------------------------------------
// Toast — lightweight inline banner (no new dependency)
// ---------------------------------------------------------------------------

function ToastBanner({ message, variant, onRetry, onDismiss }) {
  const isError = variant === 'error';
  const isSuccess = variant === 'success';
  const style = {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '10px 14px',
    borderRadius: 'var(--radius-sm)',
    fontFamily: 'var(--font-ui)',
    fontSize: '0.875rem',
    marginBottom: '12px',
    background: isError ? '#FEE' : isSuccess ? 'var(--sage-light)' : 'var(--bg-elevated)',
    color: isError ? '#C44' : isSuccess ? 'var(--sage)' : 'var(--text)',
    border: `1px solid ${isError ? '#C44' : isSuccess ? 'var(--sage)' : 'var(--border)'}`,
  };

  return (
    <div style={style} role="status" aria-live="polite">
      <span style={{ flex: 1 }}>{message}</span>
      {onRetry && (
        <button
          className="btn btn-ghost btn-sm"
          onClick={onRetry}
          style={{ color: '#C44', fontSize: '0.8rem' }}
        >
          Retry failed
        </button>
      )}
      <button
        className="btn btn-ghost btn-sm"
        onClick={onDismiss}
        aria-label="Dismiss"
        style={{ padding: '2px 6px', fontSize: '0.8rem' }}
      >
        ✕
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// AdminTable
// ---------------------------------------------------------------------------

/**
 * @param {{
 *   rows: any[],
 *   getRowKey: (row: any) => string,
 *   columns: Array<{id: string, label: string, render?: Function, sortable?: boolean, width?: any, align?: string}>,
 *   selectable?: boolean,
 *   bulkActions?: import('./index').BulkAction[],
 *   onBulkAction?: (action: import('./index').BulkAction, selectedRows: any[], reason?: string) => Promise<import('./index').BulkResult>,
 *   searchable?: boolean | { placeholder?: string, filterFn?: Function },
 *   initialSort?: { columnId: string, direction: 'asc' | 'desc' },
 *   loading?: boolean,
 *   loadingLabel?: string,
 *   emptyState?: any,
 *   emptyMessage?: string,
 *   density?: 'compact' | 'comfortable',
 *   rowAction?: (row: any) => Array<{id: string, label: string, variant?: string, onClick: Function}>,
 *   onRowClick?: (row: any) => void,
 *   kindLabel?: string,
 * }} props
 */
export default function AdminTable({
  rows,
  getRowKey,
  columns,
  selectable = false,
  bulkActions = [],
  onBulkAction,
  searchable = false,
  initialSort,
  loading = false,
  loadingLabel = 'Loading…',
  emptyState,
  emptyMessage,
  density = 'comfortable',
  rowAction,
  onRowClick,
  kindLabel = 'items',
  // ── Pagination (server-driven, opt-in) ────────────────────────────────────
  // Provide this prop only when the consumer is fetching one page at a time
  // from the server. When set:
  //   - A pagination footer renders below the table.
  //   - Client-side search is suppressed (it would only filter the current
  //     page, which is misleading). The consumer can still pass
  //     `searchable: true` for the small-table case where it makes sense.
  //   - Client-side header sort still works on the current page only;
  //     consumers with stable server ordering should pass `sortable: false`
  //     on each column to avoid that confusion.
  // Shape: { pageSize, totalCount, currentPage (0-indexed), onPageChange }.
  pagination,
}) {
  // ── Search ────────────────────────────────────────────────────────────────
  // Client-side search is incompatible with server-driven pagination — it
  // would only filter the visible page and silently miss matches on other
  // pages. When pagination is active we hide the search input.
  const isPaginated = !!pagination;
  const searchCfg = !isPaginated && searchable === true ? {} : (!isPaginated && searchable) || null;
  const [query, setQuery] = useState('');

  const filteredRows = useMemo(() => {
    if (!searchCfg || !query.trim()) return rows;
    const fn = searchCfg.filterFn ?? ((r, q) => DEFAULT_FILTER_FN(r, q, columns));
    return rows.filter((r) => fn(r, query));
  }, [rows, query, searchCfg, columns]);

  // ── Sort ──────────────────────────────────────────────────────────────────
  const [sortState, setSortState] = useState(initialSort ?? null);

  const sortedRows = useMemo(() => {
    if (!sortState) return filteredRows;
    const col = columns.find((c) => c.id === sortState.columnId);
    if (!col || !col.sortable) return filteredRows;
    return [...filteredRows].sort((a, b) => {
      const av = a[sortState.columnId];
      const bv = b[sortState.columnId];
      const cmp = av < bv ? -1 : av > bv ? 1 : 0;
      return sortState.direction === 'asc' ? cmp : -cmp;
    });
  }, [filteredRows, sortState, columns]);

  const handleHeaderClick = (col) => {
    if (!col.sortable) return;
    setSortState((prev) => {
      if (!prev || prev.columnId !== col.id) {
        return { columnId: col.id, direction: 'asc' };
      }
      if (prev.direction === 'asc') return { columnId: col.id, direction: 'desc' };
      return null; // toggle off
    });
  };

  // ── Selection ─────────────────────────────────────────────────────────────
  const { selected, toggle, toggleAll, isSelected, isAllSelected, isIndeterminate, clear } =
    useTableSelection();

  const visibleKeys = useMemo(() => sortedRows.map(getRowKey), [sortedRows, getRowKey]);

  // ── Keyboard shortcuts ────────────────────────────────────────────────────
  const tableRef = useRef(null);

  const handleKeyDown = useCallback(
    (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'a') {
        if (selectable) {
          e.preventDefault();
          toggleAll(visibleKeys);
        }
      }
      if (e.key === 'Escape') {
        clear();
      }
    },
    [selectable, toggleAll, visibleKeys, clear]
  );

  // ── Pending bulk action ───────────────────────────────────────────────────
  const [pendingAction, setPendingAction] = useState(null); // BulkAction requiring confirm
  const [dispatching, setDispatching] = useState(false);
  const [toast, setToast] = useState(null); // { message, variant, onRetry }

  const dismissToast = useCallback(() => setToast(null), []);

  const dispatchAction = useCallback(
    async (action, overrideKeys = null, reason = undefined) => {
      const targetKeys = overrideKeys ?? [...selected];
      const targetRows = rows.filter((r) => targetKeys.includes(getRowKey(r)));

      if (!onBulkAction) return;

      setDispatching(true);
      try {
        const result = await onBulkAction(action, targetRows, reason);

        const nOk = result.succeeded?.length ?? 0;
        const nFail = result.failed?.length ?? 0;

        if (nFail === 0 && nOk > 0) {
          // Full success — clear selection
          clear();
          setToast({
            message: result.message ?? `${nOk} ${nOk === 1 ? 'item' : 'items'} ${action.label.toLowerCase()}d successfully.`,
            variant: 'success',
          });
        } else if (nFail > 0) {
          // Partial or full failure — keep failed rows selected
          const failedIds = new Set((result.failed ?? []).map((f) => f.id));
          const succeededIds = new Set(result.succeeded ?? []);

          // Remove succeeded rows from selection, keep failed
          const nextSelected = targetKeys.filter(
            (k) => !succeededIds.has(k) || failedIds.has(k)
          );

          // Reset selection to only failed rows
          clear();
          nextSelected.forEach((k) => {
            if (failedIds.has(k)) toggle(k);
          });

          const failMessages = (result.failed ?? [])
            .map((f) => f.error)
            .filter(Boolean)
            .slice(0, 2)
            .join('; ');

          const retryKeys = (result.failed ?? []).map((f) => f.id);

          setToast({
            message: `${nOk} succeeded, ${nFail} failed.${failMessages ? ' ' + failMessages : ''}`,
            variant: 'error',
            onRetry: () => {
              dismissToast();
              dispatchAction(action, retryKeys);
            },
          });
        } else {
          // nOk === 0 && nFail === 0 — treat as success with the server message
          clear();
          setToast({
            message: result.message ?? `${action.label} completed.`,
            variant: 'success',
          });
        }
      } catch (err) {
        // Surface error — do NOT swallow
        setToast({
          message: err?.message ?? String(err),
          variant: 'error',
        });
      } finally {
        setDispatching(false);
      }
    },
    [selected, rows, getRowKey, onBulkAction, clear, toggle, dismissToast]
  );

  const handleActionClick = useCallback(
    (action) => {
      if (action.confirm) {
        setPendingAction(action);
      } else if (action.reason) {
        // reason without confirm — open the modal for reason collection only
        setPendingAction(action);
      } else {
        dispatchAction(action);
      }
    },
    [dispatchAction]
  );

  const handleConfirm = useCallback(
    (reason) => {
      const action = pendingAction;
      setPendingAction(null);
      dispatchAction(action, null, reason);
    },
    [pendingAction, dispatchAction]
  );

  // ── Render ────────────────────────────────────────────────────────────────
  const colSpan = columns.length + (selectable ? 1 : 0) + (rowAction ? 1 : 0);
  const hasActions = selectable && bulkActions.length > 0;
  const selectedRows = useMemo(
    () => rows.filter((r) => selected.has(getRowKey(r))),
    [rows, selected, getRowKey]
  );

  return (
    <div
      className={`admin-table-container admin-table--${density}`}
      ref={tableRef}
      tabIndex={0}
      onKeyDown={handleKeyDown}
      style={{ outline: 'none' }}
    >
      {/* Toast banner */}
      {toast && (
        <ToastBanner
          message={toast.message}
          variant={toast.variant}
          onRetry={toast.onRetry}
          onDismiss={dismissToast}
        />
      )}

      {/* Selection bar */}
      {hasActions && (
        <SelectionBar
          selectedCount={selected.size}
          selectedRows={selectedRows}
          actions={bulkActions}
          onAction={handleActionClick}
          onClear={clear}
        />
      )}

      {/* Search bar */}
      {searchCfg && (
        <div className="admin-toolbar">
          <input
            type="search"
            className="admin-search"
            placeholder={searchCfg.placeholder ?? 'Search…'}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label="Search"
          />
        </div>
      )}

      <div className="admin-table-wrapper">
        <table className="admin-table">
          <thead>
            <tr>
              {selectable && (
                <th style={{ width: 40 }}>
                  <input
                    type="checkbox"
                    aria-label="Select all"
                    checked={isAllSelected(visibleKeys)}
                    ref={(el) => {
                      if (el) el.indeterminate = isIndeterminate(visibleKeys);
                    }}
                    onChange={() => toggleAll(visibleKeys)}
                  />
                </th>
              )}
              {columns.map((col) => {
                const dir =
                  sortState?.columnId === col.id ? sortState.direction : null;
                return (
                  <th
                    key={col.id}
                    title={col.titleHint || undefined}
                    style={{
                      width: col.width,
                      textAlign: col.align ?? 'left',
                      cursor: col.sortable ? 'pointer' : 'default',
                    }}
                    onClick={() => handleHeaderClick(col)}
                    aria-sort={
                      dir === 'asc'
                        ? 'ascending'
                        : dir === 'desc'
                        ? 'descending'
                        : col.sortable
                        ? 'none'
                        : undefined
                    }
                  >
                    {col.label}
                    {col.sortable && <SortIcon direction={dir} />}
                  </th>
                );
              })}
              {rowAction && <th style={{ width: 120 }}>Actions</th>}
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={colSpan} className="admin-empty">
                  {loadingLabel}
                </td>
              </tr>
            ) : sortedRows.length === 0 ? (
              <tr>
                <td colSpan={colSpan} className="admin-empty">
                  {emptyState ?? emptyMessage ?? 'No items found.'}
                </td>
              </tr>
            ) : (
              sortedRows.map((row) => {
                const key = getRowKey(row);
                const checked = isSelected(key);
                const rowActions = rowAction ? rowAction(row) : [];

                return (
                  <tr
                    key={key}
                    style={{ cursor: onRowClick ? 'pointer' : undefined }}
                    onClick={
                      onRowClick && !selectable
                        ? () => onRowClick(row)
                        : undefined
                    }
                  >
                    {selectable && (
                      <td
                        onClick={(e) => e.stopPropagation()}
                        style={{ width: 40 }}
                      >
                        <input
                          type="checkbox"
                          aria-label={`Select ${key}`}
                          checked={checked}
                          onChange={(e) =>
                            toggle(key, {
                              shift: e.nativeEvent.shiftKey,
                              allKeys: visibleKeys,
                            })
                          }
                        />
                      </td>
                    )}
                    {columns.map((col) => (
                      <td
                        key={col.id}
                        style={{ textAlign: col.align ?? 'left' }}
                      >
                        {col.render ? col.render(row) : row[col.id]}
                      </td>
                    ))}
                    {rowAction && (
                      <td className="admin-cell-actions">
                        {rowActions.map((a) => (
                          <button
                            key={a.id}
                            className={`btn btn-ghost btn-sm${a.variant === 'danger' ? ' btn-danger' : ''}`}
                            onClick={(e) => {
                              e.stopPropagation();
                              a.onClick(row);
                            }}
                          >
                            {a.label}
                          </button>
                        ))}
                      </td>
                    )}
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination footer (server-driven, opt-in) */}
      {isPaginated && (
        <Pagination
          currentPage={pagination.currentPage}
          pageSize={pagination.pageSize}
          totalCount={pagination.totalCount}
          onPageChange={pagination.onPageChange}
        />
      )}

      {/* Confirm modal */}
      {pendingAction && (
        <ConfirmBulkModal
          action={pendingAction}
          selectedRows={selectedRows}
          getRowKey={getRowKey}
          kindLabel={kindLabel}
          onConfirm={handleConfirm}
          onCancel={() => setPendingAction(null)}
        />
      )}

      {/* Dispatch overlay */}
      {dispatching && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            background: 'rgba(255,255,255,0.4)',
            zIndex: 10,
            cursor: 'wait',
          }}
          aria-hidden="true"
        />
      )}
    </div>
  );
}
