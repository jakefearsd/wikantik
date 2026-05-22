// EmptyState.jsx
// Uniform empty-list state for admin tables/sections.
export default function EmptyState({ message, action }) {
  return (
    <div className="admin-empty-state">
      <p className="admin-empty-message">{message}</p>
      {action && <div className="admin-empty-action">{action}</div>}
    </div>
  );
}
