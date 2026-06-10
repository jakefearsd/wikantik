// ViolationList.jsx
// Renders a list of {severity, message, suggestion} violations inline, with an optional
// "apply suggestion" affordance. Shared by FieldWidget and RunbookBlockEditor.
export default function ViolationList({ violations, onApplySuggestion }) {
  if (!violations || violations.length === 0) return null;
  return (
    <ul className="fm-violations">
      {violations.map((v, i) => (
        <li key={i} className={`fm-violation fm-violation-${(v.severity || '').toLowerCase()}`}>
          <span className="fm-violation-msg">{v.message}</span>
          {v.suggestion && onApplySuggestion && (
            <button
              type="button"
              className="fm-apply-suggestion"
              onClick={() => onApplySuggestion(v.suggestion)}
            >
              Use “{v.suggestion}”
            </button>
          )}
        </li>
      ))}
    </ul>
  );
}
