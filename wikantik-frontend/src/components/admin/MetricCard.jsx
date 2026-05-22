// MetricCard.jsx
import { Link } from 'react-router-dom';

// One dashboard tile. `degraded` renders a muted unavailable state (the card's
// collector failed server-side). `to` makes the whole card a link into a section.
export default function MetricCard({ label, value, meta, accent, dim, degraded, to, children }) {
  const body = degraded ? (
    <div className="metric-card-unavailable">Unavailable</div>
  ) : (
    <>
      {value != null && <div className={`metric-card-value${accent ? ' accent' : ''}`}>{value}</div>}
      {children}
      {meta && <div className="metric-card-meta">{meta}</div>}
    </>
  );
  const inner = (
    <>
      <div className="metric-card-label">{label}</div>
      {body}
    </>
  );
  const cls = `metric-card${dim ? ' dim' : ''}`;
  return to && !degraded
    ? <Link to={to} className={cls}>{inner}</Link>
    : <div className={cls}>{inner}</div>;
}
