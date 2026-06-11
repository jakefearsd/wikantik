// MathValidationSummary.jsx
// Panel listing ContentViolation entries from math validation (POST /api/pages 422).
// Renders nothing when empty. Shows a severity badge, message, pinpoint excerpt+caret,
// and a Jump button that positions the editor at the offending offset.
import Badge from './ui/Badge';

export default function MathValidationSummary({ violations = [], onJump }) {
  if (!violations || violations.length === 0) return null;

  return (
    <div className="math-violations" data-testid="math-validation-summary">
      <div className="math-violations-header">
        <strong>Math validation</strong>
      </div>
      <ul className="math-violations-list">
        {violations.map((v, i) => (
          <li key={i} className={`math-violation math-violation-${(v.severity || '').toLowerCase()}`}>
            <div className="math-violation-row">
              <Badge variant={v.severity === 'ERROR' ? 'danger' : 'warning'}>
                {(v.severity || 'ERROR').toLowerCase()}
              </Badge>
              <span className="math-violation-msg">{v.message}</span>
              {v.location && onJump && (
                <button
                  type="button"
                  className="btn btn-ghost btn-sm math-violation-jump"
                  onClick={() => onJump(v.location)}
                >
                  Jump
                </button>
              )}
            </div>
            {v.location && (v.location.excerpt || v.location.caret) && (
              <pre className="math-violation-pinpoint">
                {v.location.excerpt}
                {'\n'}
                {v.location.caret}
              </pre>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
