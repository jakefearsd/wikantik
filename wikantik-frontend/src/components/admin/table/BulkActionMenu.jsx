import { useState, useRef, useEffect } from 'react';
import '../../../styles/admin.css';

/**
 * Overflow popover used by SelectionBar when there are more than 4 bulk actions.
 *
 * @param {{
 *   actions: import('./index').BulkAction[],
 *   selectedRows: any[],
 *   onAction: (action: import('./index').BulkAction) => void,
 * }} props
 */
export default function BulkActionMenu({ actions, selectedRows, onAction }) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef(null);

  // Close when clicking outside
  useEffect(() => {
    if (!open) return;
    const handler = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  return (
    <div
      className="bulk-action-menu"
      ref={containerRef}
      style={{ position: 'relative', display: 'inline-block' }}
    >
      <button
        className="btn btn-ghost btn-sm"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label="More bulk actions"
      >
        More ▾
      </button>

      {open && (
        <div
          role="menu"
          className="bulk-action-menu__popover"
          style={{
            position: 'absolute',
            top: 'calc(100% + 4px)',
            right: 0,
            background: 'var(--bg-elevated)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-md)',
            boxShadow: '0 8px 24px var(--shadow-strong)',
            zIndex: 200,
            minWidth: '160px',
            padding: '4px 0',
          }}
        >
          {actions.map((action) => {
            const disabledReason =
              action.disabled ? action.disabled(selectedRows) : false;
            const isDisabled = !!disabledReason;
            const isDanger = action.variant === 'danger';

            return (
              <button
                key={action.id}
                role="menuitem"
                disabled={isDisabled}
                title={isDisabled ? disabledReason : undefined}
                onClick={() => {
                  setOpen(false);
                  onAction(action);
                }}
                style={{
                  display: 'block',
                  width: '100%',
                  textAlign: 'left',
                  padding: '8px 16px',
                  background: 'none',
                  border: 'none',
                  cursor: isDisabled ? 'not-allowed' : 'pointer',
                  fontFamily: 'var(--font-ui)',
                  fontSize: '0.875rem',
                  color: isDisabled
                    ? 'var(--text-muted)'
                    : isDanger
                    ? '#C44'
                    : 'var(--text)',
                  opacity: isDisabled ? 0.5 : 1,
                }}
              >
                {action.label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
