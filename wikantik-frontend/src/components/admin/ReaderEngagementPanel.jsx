// ReaderEngagementPanel.jsx
// What readers did on each page (views, typical active time, scroll depth, where they came from,
// whether they stayed), read back from Umami into the reader rollups. Reads
// GET /admin/insights/readers. A null measure means "no data" and is shown as a dash, never zero.
import { useAdminResource } from '../../hooks/useAdminResource';
import { api } from '../../api/client';
import EmptyState from '../ui/EmptyState';
import '../../styles/admin.css';

const DAYS = 28;
const NOT_CONFIGURED = Object.freeze({ notConfigured: true });
export const EMPTY_MESSAGE = 'No reader data yet — analytics off or not yet read back';

const isNum = (n) => typeof n === 'number' && Number.isFinite(n);
const percent = (r) => (isNum(r) ? `${Math.round(r * 100)}%` : '—');
const seconds = (s) => (isNum(s) ? `${Math.round(s)} s` : '—');
const millis = (ms) => (isNum(ms) ? `${Math.round(ms).toLocaleString()} ms` : '—');
const fixed = (n, digits) => (isNum(n) ? n.toFixed(digits) : '—');

function landingsText(landings) {
  const entries = Object.entries(landings || {}).sort((a, b) => b[1] - a[1]);
  return entries.length === 0 ? '—' : entries.map(([ch, n]) => `${ch} ${n}`).join(', ');
}

export default function ReaderEngagementPanel() {
  // A 503 means reader analytics is off here; that is data, not an error.
  const { data, loading, error } = useAdminResource(
    () => api.admin.getInsightsReaders(DAYS).catch((err) => {
      if (err.status === 503) return NOT_CONFIGURED;
      throw err;
    }),
    [],
  );

  if (loading && !data) {
    return <div className="admin-loading">Loading reader engagement…</div>;
  }
  if (error) {
    return <div className="error-banner">{error}</div>;
  }
  const pages = data === NOT_CONFIGURED ? [] : data?.pages || [];
  const siteCoverage = Object.entries(data?.coverage?.site || {});

  return (
    <section data-testid="reader-engagement-panel">
      <div className="admin-section-header">
        <h3>Reader Engagement</h3>
      </div>
      <p className="admin-section-help">
        Last {DAYS} days, most-viewed pages first. A dash means no data, not zero.
        {data?.refreshedAt && <> Read back {new Date(data.refreshedAt).toLocaleString()}.</>}
      </p>

      {pages.length === 0 ? (
        <EmptyState message={EMPTY_MESSAGE} />
      ) : (
        <>
          {siteCoverage.length > 0 && (
            <p className="admin-section-help" data-testid="reader-coverage">
              Search clicks that arrived as organic landings:{' '}
              {siteCoverage.map(([engine, ratio]) => `${engine} ${percent(ratio)}`).join(', ')}
            </p>
          )}
          <table className="admin-table">
            <thead>
              <tr>
                <th>Page</th>
                <th>Views</th>
                <th title="Visitor-days: sum of daily visitors; not unique">Visitor-days</th>
                <th title="Exit-weighted median of the daily median active times">Typical active time</th>
                <th>Scrolled 75%</th>
                <th>Actions / view</th>
                <th>Landings</th>
                <th>Continued</th>
                <th title="Share of landings by returning readers, counted on days 8+ of each month">
                  Returning this month
                </th>
                <th title="Views-weighted mean of daily p75">LCP</th>
                <th title="Views-weighted mean of daily p75">INP</th>
                <th title="Views-weighted mean of daily p75">CLS</th>
              </tr>
            </thead>
            <tbody>
              {pages.map((p) => (
                <tr key={p.canonical_id} data-testid={`reader-row-${p.canonical_id}`}>
                  <td>{p.slug || p.canonical_id}</td>
                  <td>{p.views}</td>
                  <td>{p.visitors}</td>
                  <td>{seconds(p.typical_active_s)}</td>
                  <td>{percent(p.scroll75_rate)}</td>
                  <td>{fixed(p.actions_per_view, 2)}</td>
                  <td>{landingsText(p.landings)}</td>
                  <td>{percent(p.continuation_rate)}</td>
                  <td>{percent(p.returning_this_month_rate)}</td>
                  <td>{millis(p.lcp_p75_ms)}</td>
                  <td>{millis(p.inp_p75_ms)}</td>
                  <td>{fixed(p.cls_p75, 3)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
