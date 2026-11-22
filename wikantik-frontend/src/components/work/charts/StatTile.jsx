/**
 * A headline figure: the value large, the label above it, an optional small note beneath. HTML, not SVG. The
 * value is shown as given (callers format it); a missing value reads as an en dash, never "null" or "NaN".
 */
export default function StatTile({ label, value, note }) {
  const shown = value === null || value === undefined || value === '' || (typeof value === 'number' && !Number.isFinite(value))
    ? '–' : value;
  return (
    <div className="work-stat">
      <div className="work-stat-label">{label}</div>
      <div className="work-stat-value">{shown}</div>
      {note ? <div className="work-stat-note">{note}</div> : null}
    </div>
  );
}
