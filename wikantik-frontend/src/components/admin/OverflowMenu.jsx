import { useState, useEffect, useRef } from 'react';
import Icon from '../ui/Icon';

/**
 * OverflowMenu — a "⋯" trigger that opens a small popover menu.
 *
 * Props:
 *   actions: Array<{ label: string, onClick: () => void, disabled?: boolean }>
 *
 * Closes on: Esc, outside-click, after an action fires.
 */
export default function OverflowMenu({ actions = [] }) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef(null);

  // Close on outside-click (mousedown so it fires before focus events)
  useEffect(() => {
    if (!open) return;
    const onMouseDown = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
  }, [open]);

  // Close on Escape
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open]);

  const handleAction = (action) => {
    if (action.disabled) return;
    action.onClick();
    setOpen(false);
  };

  return (
    <span
      ref={containerRef}
      style={{ position: 'relative', display: 'inline-block' }}
    >
      <button
        type="button"
        aria-label="More actions"
        aria-haspopup="menu"
        aria-expanded={open ? 'true' : 'false'}
        onClick={() => setOpen((v) => !v)}
        className="btn btn-ghost btn-sm"
        style={{ padding: '2px 4px', lineHeight: 1 }}
      >
        <Icon name="more" size={16} />
      </button>

      {open && (
        <div
          role="menu"
          style={{
            position: 'absolute',
            right: 0,
            top: '100%',
            marginTop: 4,
            minWidth: 140,
            background: 'var(--bg-elevated)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-sm)',
            boxShadow: '0 4px 16px var(--shadow-strong)',
            zIndex: 50,
            padding: '4px 0',
          }}
        >
          {actions.map((action) => (
            <button
              key={action.label}
              role="menuitem"
              type="button"
              disabled={action.disabled}
              onClick={() => handleAction(action)}
              style={{
                display: 'block',
                width: '100%',
                textAlign: 'left',
                padding: '6px 14px',
                background: 'none',
                border: 'none',
                fontFamily: 'var(--font-ui)',
                fontSize: '0.875rem',
                color: action.disabled ? 'var(--text-muted)' : 'var(--text)',
                cursor: action.disabled ? 'not-allowed' : 'pointer',
              }}
            >
              {action.label}
            </button>
          ))}
        </div>
      )}
    </span>
  );
}
