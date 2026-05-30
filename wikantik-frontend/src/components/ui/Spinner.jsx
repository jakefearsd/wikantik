// Spinner.jsx
// Animated spinner for loading states.
export default function Spinner({ size = 'md', label = 'Loading…', className = '' }) {
  return (
    <span
      role="status"
      aria-label={label}
      className={`spinner spinner-${size} ${className}`}
    >
      <span className="sr-only">{label}</span>
    </span>
  );
}
