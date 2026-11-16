/** Inline confirmation strip (no browser dialogs); shared by the agents page. */
export default function WorkConfirm({ children, yes, onYes, onNo, disabled = false }) {
  return (
    <div className="work-confirm" role="alertdialog" aria-label="Confirm">
      <p>{children}</p>
      <div className="work-actions">
        <button type="button" className="btn btn-sm btn-danger" disabled={disabled} onClick={onYes}>{yes}</button>
        <button type="button" className="btn btn-sm btn-ghost" onClick={onNo}>Cancel</button>
      </div>
    </div>
  );
}
