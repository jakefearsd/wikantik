// EmptyState.jsx
// Uniform empty-list state for admin tables/sections with optional icon.
export default function EmptyState({ message, action, icon }) {
  return (
    <div className="admin-empty-state">
      {icon && <div className="empty-state-icon">{icon}</div>}
      <p className="admin-empty-message">{message}</p>
      {action && <div className="admin-empty-action">{action}</div>}
    </div>
  );
}
