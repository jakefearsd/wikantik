// AdminAuditPage.jsx
// Tamper-evident audit log viewer: filter bar, results table, integrity check, CSV export.
// Mirrors AdminApiKeysPage structure: AdminPage shell, PageHeader, AdminTable, api.admin.* fetch helpers.
import { useState } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import PageHeader from './PageHeader';
import { AdminTable } from './table';
import '../../styles/admin.css';

const CATEGORY_OPTIONS = [
  { value: '', label: 'All categories' },
  { value: 'authn', label: 'authn' },
  { value: 'authz', label: 'authz' },
  { value: 'content', label: 'content' },
  { value: 'admin', label: 'admin' },
  { value: 'read', label: 'read' },
];

const OUTCOME_OPTIONS = [
  { value: '', label: 'All outcomes' },
  { value: 'SUCCESS', label: 'success' },
  { value: 'FAILURE', label: 'failure' },
  { value: 'DENIED', label: 'denied' },
];

const COLUMNS = [
  { id: 'seq', label: 'Seq', sortable: true },
  {
    id: 'eventTime',
    label: 'Time',
    sortable: true,
    render: (r) => (
      <span className="admin-cell-date">{formatDate(r.eventTime)}</span>
    ),
  },
  {
    id: 'actorPrincipal',
    label: 'Actor',
    render: (r) => r.actorPrincipal || '—',
  },
  {
    id: 'category',
    label: 'Category',
    render: (r) => r.category ? <code>{r.category.toLowerCase()}</code> : '—',
  },
  {
    id: 'eventType',
    label: 'Event',
    render: (r) => r.eventType ? <code>{r.eventType}</code> : '—',
  },
  {
    id: 'outcome',
    label: 'Outcome',
    render: (r) => {
      const out = r.outcome ? r.outcome.toLowerCase() : null;
      if (!out) return '—';
      const badge = out === 'success' ? 'active' : 'locked';
      return <span className={`admin-badge ${badge}`}>{out}</span>;
    },
  },
  {
    id: 'targetId',
    label: 'Target',
    render: (r) => r.targetId || '—',
  },
];

function formatDate(str) {
  if (!str) return '—';
  try {
    return new Date(str).toLocaleString();
  } catch {
    return str;
  }
}

export default function AdminAuditPage() {
  // Filter state
  const [filters, setFilters] = useState({
    actor: '',
    category: '',
    eventType: '',
    target: '',
    outcome: '',
    from: '',
    to: '',
  });

  // Results state
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [fetched, setFetched] = useState(false);

  // Integrity check state
  const [verifyState, setVerifyState] = useState(null); // null | { ok, firstBrokenSeq }
  const [verifying, setVerifying] = useState(false);

  const setFilter = (key) => (e) =>
    setFilters((f) => ({ ...f, [key]: e.target.value }));

  const handleSearch = async (e) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const data = await api.admin.listAuditLog(filters);
      setRows(Array.isArray(data) ? data : []);
      setFetched(true);
    } catch (err) {
      setError(err.message || 'Failed to load audit log');
    } finally {
      setLoading(false);
    }
  };

  const handleVerify = async () => {
    setVerifying(true);
    setVerifyState(null);
    try {
      const result = await api.admin.verifyAuditChain();
      setVerifyState(result);
    } catch (err) {
      setVerifyState({ ok: false, firstBrokenSeq: null, error: err.message });
    } finally {
      setVerifying(false);
    }
  };

  const exportUrl = buildExportUrl();

  return (
    <div className="admin-users page-enter">
      <PageHeader
        title="Audit Log"
        description="Tamper-evident record of authentication, authorization, content, and admin events."
        actions={
          <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'center', flexWrap: 'wrap' }}>
            <button
              className="btn btn-ghost"
              onClick={handleVerify}
              disabled={verifying}
            >
              {verifying ? 'Verifying…' : 'Verify integrity'}
            </button>
            <a
              href={exportUrl}
              className="btn btn-ghost"
              download="audit-log.csv"
            >
              Export CSV
            </a>
          </div>
        }
      />

      {verifyState !== null && (
        <div
          className={`admin-toolbar ${verifyState.ok ? 'admin-banner-ok' : 'admin-banner-error'}`}
          role="status"
          style={{
            padding: 'var(--space-sm) var(--space-md)',
            borderRadius: '4px',
            marginBottom: 'var(--space-md)',
            background: verifyState.ok
              ? 'var(--color-success-bg, #ecfdf5)'
              : 'var(--color-error-bg, #fef2f2)',
            border: `1px solid ${verifyState.ok ? 'var(--color-success, #22c55e)' : 'var(--color-danger, #ef4444)'}`,
            color: verifyState.ok ? 'var(--color-success-text, #166534)' : 'var(--color-danger-text, #991b1b)',
          }}
        >
          {verifyState.ok
            ? 'Chain intact — all row hashes verified.'
            : verifyState.error
              ? `Verification failed: ${verifyState.error}`
              : `Hash chain broken at seq ${verifyState.firstBrokenSeq}.`}
        </div>
      )}

      <form className="admin-toolbar" onSubmit={handleSearch} style={{ flexWrap: 'wrap', gap: 'var(--space-sm)' }}>
        <input
          type="text"
          placeholder="Actor"
          value={filters.actor}
          onChange={setFilter('actor')}
          className="admin-search-input"
          style={{ width: '130px' }}
          aria-label="Filter by actor"
        />
        <select
          value={filters.category}
          onChange={setFilter('category')}
          className="admin-search-input"
          aria-label="Filter by category"
        >
          {CATEGORY_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
        <input
          type="text"
          placeholder="Event type"
          value={filters.eventType}
          onChange={setFilter('eventType')}
          className="admin-search-input"
          style={{ width: '130px' }}
          aria-label="Filter by event type"
        />
        <input
          type="text"
          placeholder="Target"
          value={filters.target}
          onChange={setFilter('target')}
          className="admin-search-input"
          style={{ width: '130px' }}
          aria-label="Filter by target"
        />
        <select
          value={filters.outcome}
          onChange={setFilter('outcome')}
          className="admin-search-input"
          aria-label="Filter by outcome"
        >
          {OUTCOME_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
        <input
          type="datetime-local"
          value={filters.from}
          onChange={setFilter('from')}
          className="admin-search-input"
          aria-label="From date"
          title="From"
        />
        <input
          type="datetime-local"
          value={filters.to}
          onChange={setFilter('to')}
          className="admin-search-input"
          aria-label="To date"
          title="To"
        />
        <button type="submit" className="btn btn-primary" disabled={loading}>
          {loading ? 'Loading…' : 'Search'}
        </button>
      </form>

      {error && <div className="error-banner">{error}</div>}

      {fetched && (
        <AdminTable
          rows={rows}
          getRowKey={(r) => String(r.seq)}
          columns={COLUMNS}
          emptyMessage="No audit entries matched the filter."
          initialSort={{ columnId: 'seq', direction: 'desc' }}
        />
      )}

      {!fetched && !loading && (
        <div className="admin-empty-hint" style={{ color: 'var(--color-text-muted)', padding: 'var(--space-lg) 0' }}>
          Set filters above and click <strong>Search</strong> to load audit entries.
        </div>
      )}
    </div>
  );
}

// Build the export URL using the same BASE prefix that client.js uses.
function buildExportUrl() {
  const base = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';
  return `${base}/admin/audit/export?format=csv`;
}
