// Badge.jsx
// Small semantic badge for status indicators.
export default function Badge({ variant = 'default', className = '', title, children }) {
  return (
    <span className={`badge badge-${variant} ${className}`.trim()} title={title}>
      {children}
    </span>
  );
}
