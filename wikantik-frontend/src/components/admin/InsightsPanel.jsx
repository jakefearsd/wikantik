// InsightsPanel.jsx
// Search-engine acquisition dashboard over search_visibility_snapshot (Google/Bing/Yandex
// snapshots shipped by jakemon's collector, see V050__search_visibility_snapshot.sql).
// Reads GET /admin/insights/acquisition, which reports per-engine totals for the single most
// recent snapshot_date plus a clicks trend series — both sourced from page-rollup rows
// (query_text = '') only, since engines omit low-volume queries and summing them undercounts.
import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import PageHeader from './PageHeader';
import Sparkline from './Sparkline';
import BacklogPanel from './BacklogPanel';
import '../../styles/admin.css';

const DEFAULT_SITE = 'wiki.wikantik.com';
const DEFAULT_DAYS = 90;
const COLUMNS = 6; // engine | clicks | impressions | ctr | position | trend

// Yandex omits position entirely for some/all snapshots — render the gap as an em dash.
// Coercing a missing position to "0" would be a lie: 0 means rank 1.
function formatPosition(position) {
  return position == null ? '—' : position.toFixed(1);
}

function formatCtr(ctr) {
  return typeof ctr === 'number' && Number.isFinite(ctr) ? `${(ctr * 100).toFixed(2)}%` : '—';
}

export default function InsightsPanel() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    api.admin.getInsightsAcquisition(DEFAULT_SITE, DEFAULT_DAYS)
      .then((resp) => { if (!cancelled) setData(resp); })
      .catch((err) => { if (!cancelled) setError(err.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  // One clicks series per engine, oldest→newest (API order), for the per-row sparkline.
  const trendByEngine = useMemo(() => {
    const map = {};
    for (const p of data?.trend || []) {
      if (!map[p.engine]) map[p.engine] = [];
      map[p.engine].push(p.clicks);
    }
    return map;
  }, [data]);

  const engines = data?.engines || [];
  const hasSnapshot = !!data?.snapshotDate;

  return (
    <>
      <AdminPage loading={loading} error={error} loadingLabel="Loading search visibility…">
        <PageHeader
          title="Search Visibility"
          description="Search-engine acquisition — clicks, impressions, CTR, and average position from search-visibility snapshots."
        />

        {!hasSnapshot ? (
          <div className="admin-empty-state" data-testid="insights-empty-state">
            <p>No search visibility snapshots yet for {data?.site || DEFAULT_SITE}.</p>
          </div>
        ) : (
          <>
            <div className="admin-toolbar">
              <span>Site: {data.site}</span>
              <span>
                Latest snapshot: <span data-testid="insights-snapshot-date">{data.snapshotDate}</span>
              </span>
            </div>

            <table className="admin-table">
              <thead>
                <tr>
                  <th>Engine</th>
                  <th>Clicks</th>
                  <th>Impressions</th>
                  <th>CTR</th>
                  <th>Avg position</th>
                  <th>Trend</th>
                </tr>
              </thead>
              <tbody>
                {engines.length === 0 && (
                  <tr>
                    <td colSpan={COLUMNS}><em>No page-level rows for {data.snapshotDate}.</em></td>
                  </tr>
                )}
                {engines.map((e) => (
                  <tr key={e.engine}>
                    <td>{e.engine}</td>
                    <td data-testid={`clicks-${e.engine}`}>{e.clicks}</td>
                    <td data-testid={`impressions-${e.engine}`}>{e.impressions}</td>
                    <td data-testid={`ctr-${e.engine}`}>{formatCtr(e.ctr)}</td>
                    <td data-testid={`position-${e.engine}`}>{formatPosition(e.position)}</td>
                    <td><Sparkline values={trendByEngine[e.engine] || []} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </AdminPage>

      {/* Independent subsystem: rendered even if the acquisition fetch above fails, since a
          stale search-visibility snapshot has no bearing on whether the backlog can load. */}
      <div className="page-enter">
        <BacklogPanel />
      </div>
    </>
  );
}
