// Badge.jsx
// Small semantic badge for status indicators.
export default function Badge({ variant = 'default', className = '', children }) {
  return (
    <span className={`badge badge-${variant} ${className}`.trim()}>
      {children}
    </span>
  );
}
