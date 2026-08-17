// BacklogPanel.jsx
// Content-opportunity backlog — ranked retrieval-gap rules (agent_gap, engine_divergence,
// vocabulary_gap, stale_high_traffic) surfaced alongside the acquisition insights.
// Reads GET /admin/insights/backlog.
//
// `suppressed` is load-bearing: three of the four rules gate off below a per-site traffic
// threshold, so an empty `opportunities` list does NOT mean "nothing to do" — it can just as
// easily mean "not enough traffic to evaluate yet". Those are different operator-facing
// statements and this panel must never collapse them into one empty-state message.
import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import Badge from '../ui/Badge';
import Select from '../ui/Select';
import '../../styles/admin.css';

const DEFAULT_SITE = 'wiki.wikantik.com';

// The full rule set (server-side com.wikantik.insights.runtime.InsightsSettings weight keys).
// Kept as a fixed list rather than derived from the response — deriving it from a
// type-filtered response would lose every option except the one currently selected.
const TYPE_OPTIONS = [
  { value: 'agent_gap', label: 'Agent gap' },
  { value: 'engine_divergence', label: 'Engine divergence' },
  { value: 'vocabulary_gap', label: 'Vocabulary gap' },
  { value: 'stale_high_traffic', label: 'Stale, high-traffic' },
];

function formatNumber(n) {
  return typeof n === 'number' && Number.isFinite(n) ? n.toLocaleString() : String(n);
}

function formatPriority(p) {
  return typeof p === 'number' && Number.isFinite(p) ? p.toFixed(2) : String(p ?? '—');
}

function formatEvidenceValue(v) {
  if (v == null) return '—';
  if (typeof v === 'number' && Number.isFinite(v)) return v.toLocaleString();
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

export default function BacklogPanel() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [notConfigured, setNotConfigured] = useState(false);
  const [typeFilter, setTypeFilter] = useState('');
  const [includeSnoozed, setIncludeSnoozed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setNotConfigured(false);
    api.admin.getInsightsBacklog({
      site: DEFAULT_SITE,
      type: typeFilter || undefined,
      includeSnoozed,
    })
      .then((resp) => { if (!cancelled) setData(resp); })
      .catch((err) => {
        if (cancelled) return;
        if (err.status === 503) {
          setNotConfigured(true);
        } else {
          setError(err.message);
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [typeFilter, includeSnoozed]);

  // Not-configured and hard-error states replace the whole panel, same as the acquisition
  // section above. A loading/filter-refetch failure would also land here, which is an
  // acceptable trade-off — see the file header for why filters otherwise stay mounted.
  if (notConfigured) {
    return (
      <div className="admin-message warning" role="status" data-testid="backlog-not-configured">
        The content-opportunity backlog subsystem is not configured on this deployment.
      </div>
    );
  }
  if (error) {
    return <div className="error-banner">{error}</div>;
  }

  // Full-panel loading only on the very first fetch. A filter-triggered refetch keeps the
  // toolbar mounted (see the `loading` check further down) so the controls that caused the
  // refetch don't vanish out from under the operator mid-interaction.
  if (loading && !data) {
    return <div className="admin-loading">Loading backlog…</div>;
  }

  const opportunities = data?.opportunities || [];
  const suppressed = data?.suppressed || [];

  return (
    <section data-testid="backlog-panel">
      <div className="admin-section-header">
        <h3>Content Backlog</h3>
      </div>
      <p className="admin-section-help">
        Retrieval-gap opportunities ranked by priority. Rules gated by low site traffic are
        listed separately below, so an empty table here is never mistaken for "nothing to do".
      </p>

      <div className="admin-toolbar">
        <label htmlFor="backlog-type-filter">Type:</label>
        <Select
          id="backlog-type-filter"
          value={typeFilter}
          options={TYPE_OPTIONS}
          placeholder="All types"
          onChange={setTypeFilter}
          ariaLabel="Filter by opportunity type"
          data-testid="backlog-type-filter"
        />
        <label>
          <input
            type="checkbox"
            checked={includeSnoozed}
            onChange={(e) => setIncludeSnoozed(e.target.checked)}
            data-testid="backlog-include-snoozed"
          />
          {' '}Include snoozed
        </label>
      </div>

      {loading ? (
        <div className="admin-loading" data-testid="backlog-refreshing">Refreshing…</div>
      ) : (
        <>
          {suppressed.length > 0 && (
            <div className="admin-callout" data-testid="backlog-suppressed-callout">
              <strong>
                {suppressed.length} rule{suppressed.length === 1 ? '' : 's'} gated off for lack
                of traffic:
              </strong>
              <ul>
                {suppressed.map((s, i) => (
                  <li key={`${s.type}-${i}`} data-testid={`suppressed-${s.type}`}>
                    <code>{s.type}</code> — gated ({s.reason}): {formatNumber(s.measured)} of{' '}
                    {formatNumber(s.required)} required
                  </li>
                ))}
              </ul>
            </div>
          )}

          {opportunities.length === 0 ? (
            <div className="admin-empty-state" data-testid="backlog-empty-state">
              {suppressed.length > 0 ? (
                <p>
                  No opportunities to show — every rule that could report one is currently gated
                  off for lack of traffic (see above). This is not the same as "no opportunities
                  found".
                </p>
              ) : (
                <p>No opportunities found.</p>
              )}
            </div>
          ) : (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Type</th>
                  <th>Target</th>
                  <th>Priority</th>
                  <th>First seen</th>
                  <th>Suggested action</th>
                  <th>Evidence</th>
                </tr>
              </thead>
              <tbody>
                {opportunities.map((o, i) => (
                  <tr key={`${o.type}-${o.target}-${i}`}>
                    <td>
                      {o.type}
                      {o.calibrated === false && (
                        <span data-testid={`uncalibrated-${i}`}>
                          {' '}
                          <Badge
                            variant="warning"
                            title="This rule has not yet been calibrated against measured outcomes — its priority weight is a starting guess, not a validated value."
                          >
                            uncalibrated
                          </Badge>
                        </span>
                      )}
                    </td>
                    <td>{o.target}</td>
                    <td>{formatPriority(o.priority)}</td>
                    <td>{o.firstSeen || '—'}</td>
                    <td>{o.suggestedAction}</td>
                    <td>
                      {o.evidence && Object.keys(o.evidence).length > 0 ? (
                        <details data-testid={`evidence-${i}`}>
                          <summary>Evidence</summary>
                          <dl className="admin-evidence-list">
                            {Object.entries(o.evidence).map(([k, v]) => (
                              <div key={k}>
                                <dt style={{ display: 'inline', fontWeight: 600 }}>{k}:</dt>{' '}
                                <dd style={{ display: 'inline', margin: 0 }}>
                                  {formatEvidenceValue(v)}
                                </dd>
                              </div>
                            ))}
                          </dl>
                        </details>
                      ) : (
                        '—'
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}
