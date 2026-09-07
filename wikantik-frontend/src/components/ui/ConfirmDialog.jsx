// ConfirmDialog.jsx
// Shared modal confirmation dialog for destructive/impactful admin actions.
// Extracted from IndexStatusTab's private ConfirmDialog (markup/classes kept
// identical so styling doesn't shift) so every "are you sure?" prompt in the
// app can share one component instead of re-rolling the same modal-overlay
// markup — and so bare confirm()/window.confirm() calls have somewhere to go.
export default function ConfirmDialog({
  title = 'Confirm',
  message,
  confirmLabel = 'Continue',
  cancelLabel = 'Cancel',
  danger = true,
  onConfirm,
  onCancel,
}) {
  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div className="modal-content admin-modal" role="dialog" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
          {title}
        </h2>
        <p style={{ marginBottom: 'var(--space-lg)' }}>{message}</p>
        <div className="admin-actions-row">
          <button
            className={`btn btn-primary${danger ? ' btn-danger' : ''}`}
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
          <button className="btn btn-ghost" onClick={onCancel}>{cancelLabel}</button>
        </div>
      </div>
    </div>
  );
}
