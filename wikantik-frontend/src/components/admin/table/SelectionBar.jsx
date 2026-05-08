import BulkActionMenu from './BulkActionMenu';
import '../../../styles/admin.css';

const MAX_INLINE = 4;

/**
 * Sticky toolbar shown when N rows are selected.
 *
 * @param {{
 *   selectedCount: number,
 *   selectedRows: any[],
 *   actions: import('./index').BulkAction[],
 *   onAction: (action: import('./index').BulkAction) => void,
 *   onClear: () => void,
 * }} props
 */
export default function SelectionBar({
  selectedCount,
  selectedRows,
  actions,
  onAction,
  onClear,
}) {
  if (selectedCount === 0) return null;

  const inline = actions.slice(0, MAX_INLINE);
  const overflow = actions.slice(MAX_INLINE);

  return (
    <div className="admin-selection-bar" role="toolbar" aria-label="Bulk actions">
      <span className="admin-selection-bar__count">
        {selectedCount} selected
      </span>

      <div className="admin-selection-bar__actions">
        {inline.map((action) => {
          const disabledReason =
            action.disabled ? action.disabled(selectedRows) : false;
          const isDisabled = !!disabledReason;
          const isDanger = action.variant === 'danger';
          const isPrimary = action.variant === 'primary';

          const btnClass = isDanger
            ? 'btn btn-ghost btn-sm btn-danger'
            : isPrimary
            ? 'btn btn-primary btn-sm'
            : 'btn btn-ghost btn-sm';

          return (
            <button
              key={action.id}
              className={btnClass}
              disabled={isDisabled}
              title={isDisabled ? disabledReason : undefined}
              onClick={() => onAction(action)}
            >
              {action.label}
            </button>
          );
        })}

        {overflow.length > 0 && (
          <BulkActionMenu
            actions={overflow}
            selectedRows={selectedRows}
            onAction={onAction}
          />
        )}
      </div>

      <button
        className="btn btn-ghost btn-sm admin-selection-bar__clear"
        onClick={onClear}
        aria-label="Clear selection"
      >
        Clear
      </button>
    </div>
  );
}
