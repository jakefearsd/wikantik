/**
 * Inline confirmation strip (no browser dialogs); shared by the agents page and the project archive. `fields` is extra
 * controls shown under the question; `error` a server refusal, shown verbatim in the strip (which stays open);
 * `describedBy` the id of text that explains why Yes is disabled.
 */
export default function WorkConfirm({ children, yes, onYes, onNo, disabled = false, fields = null, error = null, describedBy }) {
  return (
    <div className="work-confirm" role="alertdialog" aria-label="Confirm">
      <p>{children}</p>
      {fields}
      {error && <div className="work-form-error" role="alert">{error}</div>}
      <div className="work-actions">
        <button type="button" className="btn btn-sm btn-danger" disabled={disabled} aria-describedby={disabled ? describedBy : undefined}
          onClick={onYes}>{yes}</button>
        <button type="button" className="btn btn-sm btn-ghost" onClick={onNo}>Cancel</button>
      </div>
    </div>
  );
}
