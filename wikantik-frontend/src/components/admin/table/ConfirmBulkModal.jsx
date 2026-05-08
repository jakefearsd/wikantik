import { useState } from 'react';
import '../../../styles/admin.css';

const SAMPLE_LIMIT = 5;

/**
 * Generic confirmation dialog for destructive bulk operations.
 *
 * @param {{
 *   action: import('./index').BulkAction,
 *   selectedRows: any[],
 *   getRowKey: (row: any) => string,
 *   kindLabel?: string,
 *   onConfirm: (reason?: string) => void,
 *   onCancel: () => void,
 * }} props
 */
export default function ConfirmBulkModal({
  action,
  selectedRows,
  getRowKey,
  kindLabel = 'items',
  onConfirm,
  onCancel,
}) {
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState(null);

  const N = selectedRows.length;
  const confirmCfg = typeof action.confirm === 'object' ? action.confirm : null;

  // Derive title
  const title = confirmCfg?.title ?? `${action.label}?`;

  // Derive body
  let body;
  if (confirmCfg?.body) {
    body = confirmCfg.body(selectedRows);
  } else {
    // Sample-row preview
    const keys = selectedRows.map(getRowKey);
    const preview = keys.slice(0, SAMPLE_LIMIT).join(', ');
    const overflow = keys.length > SAMPLE_LIMIT ? ` +${keys.length - SAMPLE_LIMIT} more` : '';
    body = (
      <>
        <p>
          Are you sure you want to <strong>{action.label}</strong>{' '}
          <strong>{N}</strong> {kindLabel}?
        </p>
        {N > 0 && (
          <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '8px' }}>
            {preview}{overflow}
          </p>
        )}
      </>
    );
  }

  const confirmLabel = confirmCfg?.confirmLabel ?? action.label;
  const isDanger = action.variant === 'danger';
  const isPrimary = action.variant === 'primary';
  const confirmBtnClass = isDanger
    ? 'btn btn-primary btn-danger'
    : isPrimary
    ? 'btn btn-primary'
    : 'btn btn-ghost';

  const handleConfirm = () => {
    if (action.reason?.required && !reason.trim()) {
      setReasonError(`${action.reason.label ?? 'Reason'} is required`);
      return;
    }
    onConfirm(action.reason ? reason : undefined);
  };

  return (
    <div className="modal-overlay" role="dialog" aria-modal="true" aria-label={title}>
      <div className="modal-content admin-modal">
        <h3 style={{ marginBottom: '16px', fontFamily: 'var(--font-display)' }}>{title}</h3>

        <div style={{ fontFamily: 'var(--font-ui)', fontSize: '0.9rem', lineHeight: 1.6 }}>
          {body}
        </div>

        {action.reason && (
          <div className="form-field" style={{ marginTop: '16px' }}>
            <label htmlFor="bulk-modal-reason">
              {action.reason.label ?? 'Reason'}
              {action.reason.required && (
                <span style={{ color: '#C44', marginLeft: 4 }}>*</span>
              )}
            </label>
            <textarea
              id="bulk-modal-reason"
              rows={3}
              placeholder={action.reason.placeholder ?? ''}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value);
                if (reasonError) setReasonError(null);
              }}
            />
            {reasonError && (
              <p style={{ color: '#C44', fontSize: '0.8rem', marginTop: 4 }} role="alert">
                {reasonError}
              </p>
            )}
          </div>
        )}

        <div className="modal-actions">
          <button className="btn btn-ghost" onClick={onCancel}>
            Cancel
          </button>
          <button className={confirmBtnClass} onClick={handleConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
