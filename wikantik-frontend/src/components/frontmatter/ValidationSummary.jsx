// Compact validation status strip atop the editor. States: checking / valid / counts.
// Counts are buttons that jump to the first field of that severity.
import Badge from '../ui/Badge';
import Spinner from '../ui/Spinner';

export default function ValidationSummary({ violations = [], validating = false, onJump }) {
  const errors = violations.filter((v) => v.severity === 'ERROR');
  const warnings = violations.filter((v) => v.severity === 'WARNING');

  if (validating && violations.length === 0) {
    return (
      <div className="fm-summary fm-summary-checking">
        <Spinner size="sm" label="Checking" /> Checking…
      </div>
    );
  }
  if (errors.length === 0 && warnings.length === 0) {
    return <div className="fm-summary fm-summary-valid">✓ Frontmatter valid</div>;
  }
  const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
  return (
    <div className="fm-summary">
      {errors.length > 0 && (
        <button type="button" className="fm-summary-count" onClick={() => onJump?.(errors[0].field)}>
          <Badge variant="danger">{plural(errors.length, 'error')}</Badge>
        </button>
      )}
      {warnings.length > 0 && (
        <button type="button" className="fm-summary-count" onClick={() => onJump?.(warnings[0].field)}>
          <Badge variant="warning">{plural(warnings.length, 'warning')}</Badge>
        </button>
      )}
    </div>
  );
}
