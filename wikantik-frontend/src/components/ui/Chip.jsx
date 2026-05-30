// Chip.jsx
// Pill-shaped label with optional remove button.
export default function Chip({ label, children, onRemove }) {
  const content = children ?? label;

  return (
    <div className="chip">
      <span>{content}</span>
      {onRemove && (
        <button
          type="button"
          aria-label="Remove"
          className="chip-remove"
          onClick={onRemove}
        >
          ×
        </button>
      )}
    </div>
  );
}
