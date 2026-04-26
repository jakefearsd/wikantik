// Shared shell for admin pages: handles the loading + error short-circuit and
// the outer page-enter wrapper. Each admin page was repeating the same three
// lines verbatim — `if (loading) return …; if (error) return …;` plus an outer
// `<div className="… page-enter">`. Centralising that means new admin pages
// can't accidentally drift the spinner or error banner away from the rest.
//
// Pass `className={null}` (or empty string) to skip the outer wrapper — useful
// for sub-section components that already live inside a page-level wrapper.
export default function AdminPage({
  loading,
  error,
  loadingLabel = 'Loading…',
  className = 'admin-users page-enter',
  children,
}) {
  if (loading) return <div className="admin-loading">{loadingLabel}</div>;
  if (error) return <div className="error-banner">{error}</div>;
  if (!className) return <>{children}</>;
  return <div className={className}>{children}</div>;
}
