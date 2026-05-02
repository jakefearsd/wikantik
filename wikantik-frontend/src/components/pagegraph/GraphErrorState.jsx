import { Link } from 'react-router-dom';

const VARIANTS = {
  empty:          { message: 'The page graph is empty.', action: 'refresh' },
  'empty-for-you': { message: "You don't have permission to view any pages in the page graph.", action: null },
  unauthorized:   { message: 'Sign in to view the page graph.', action: 'login' },
  forbidden:      { message: "You don't have permission to view the page graph.", action: null },
  server:         { message: 'The page graph service is unavailable right now.', action: 'retry' },
  malformed:      { message: 'Page graph snapshot was invalid. Check server logs.', action: 'retry' },
};

export default function GraphErrorState({ variant, onRetry }) {
  const config = VARIANTS[variant] || VARIANTS.server;

  return (
    <div className="graph-error-state" data-testid="graph-error-state">
      <p className="error-message">{config.message}</p>
      {config.action === 'refresh' && (
        <button className="error-action" onClick={onRetry}>Refresh</button>
      )}
      {config.action === 'retry' && (
        <button className="error-action" onClick={onRetry}>Try again</button>
      )}
      {config.action === 'login' && (
        <Link to="/login?return=/page-graph" className="error-action" style={{ textDecoration: 'none' }}>
          Sign in
        </Link>
      )}
    </div>
  );
}
