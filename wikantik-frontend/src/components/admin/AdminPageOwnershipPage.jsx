// AdminPageOwnershipPage.jsx
//
// Admin surface for the page-ownership API (T17). Two filter tabs:
//   - Orphaned (default) → api.admin.pageOwnership.listOrphaned()
//   - By Owner           → api.admin.pageOwnership.listByOwner(login)
// Per-row Reassign opens an inline modal that calls api.admin.pageOwnership.reassign().
// Above the table, a "Reassign all of a user's pages" form calls reassignByUser().
//
// The literal `<orphaned>` sentinel (used by the backend to mean "no owner") is
// passed through verbatim in both the search input and the bulk form — operators
// occasionally need to mass-reassign orphaned pages onto a real user.
import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import PageHeader from './PageHeader';
import '../../styles/admin.css';

const FILTER_ORPHANED = 'orphaned';
const FILTER_BY_OWNER = 'by-owner';

export default function AdminPageOwnershipPage() {
  const [filter, setFilter] = useState(FILTER_ORPHANED);
  const [ownerQuery, setOwnerQuery] = useState('');
  const [submittedOwner, setSubmittedOwner] = useState('');

  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [reassignTarget, setReassignTarget] = useState(null);
  const [newOwner, setNewOwner] = useState('');

  const [bulkFrom, setBulkFrom] = useState('');
  const [bulkTo, setBulkTo] = useState('');
  const [bulkBusy, setBulkBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res =
        filter === FILTER_ORPHANED
          ? await api.admin.pageOwnership.listOrphaned()
          : await api.admin.pageOwnership.listByOwner(submittedOwner);
      const pages = res.pages || [];
      setRows(pages);
      setTotal(typeof res.total === 'number' ? res.total : pages.length);
    } catch (err) {
      setError(err?.message || String(err));
      setRows([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [filter, submittedOwner]);

  // Reload whenever the active filter or the submitted owner query changes.
  // We never auto-call listByOwner with an empty string — the operator must
  // explicitly hit Search first, so a blank submittedOwner suppresses the load
  // on the By-Owner tab (handled inside load() via the empty result fallthrough
  // — see the by-owner branch below).
  useEffect(() => {
    if (filter === FILTER_BY_OWNER && !submittedOwner) {
      setRows([]);
      setTotal(0);
      setLoading(false);
      return;
    }
    load();
  }, [filter, submittedOwner, load]);

  const switchFilter = (next) => {
    if (next === filter) return;
    setFilter(next);
    setRows([]);
    setTotal(0);
    if (next === FILTER_ORPHANED) {
      setOwnerQuery('');
      setSubmittedOwner('');
    }
  };

  const onSearch = (e) => {
    e?.preventDefault?.();
    setSubmittedOwner(ownerQuery.trim());
  };

  const openReassign = (row) => {
    setReassignTarget(row);
    setNewOwner('');
  };

  const closeReassign = () => {
    setReassignTarget(null);
    setNewOwner('');
  };

  const onReassignSubmit = async () => {
    if (!reassignTarget || !newOwner.trim()) return;
    try {
      await api.admin.pageOwnership.reassign(
        [reassignTarget.canonicalId],
        newOwner.trim(),
      );
    } catch (err) {
      setError(err?.message || String(err));
    }
    closeReassign();
    await load();
  };

  const onBulkSubmit = async (e) => {
    e?.preventDefault?.();
    if (!bulkFrom.trim() || !bulkTo.trim()) return;
    setBulkBusy(true);
    try {
      await api.admin.pageOwnership.reassignByUser(
        bulkFrom.trim(),
        bulkTo.trim(),
      );
    } catch (err) {
      setError(err?.message || String(err));
    } finally {
      setBulkBusy(false);
    }
    setBulkFrom('');
    setBulkTo('');
    await load();
  };

  return (
    <AdminPage
      loading={loading && rows.length === 0 && !error}
      error={error}
      loadingLabel="Loading page ownership…"
      className="admin-page-ownership page-enter"
    >
      <PageHeader
        title="Page Ownership"
        description="Reassign ownership of pages, including those orphaned by deleted users."
      />

      {/* Bulk reassign-by-user form */}
      <section className="admin-callout">
        <h3 style={{ marginTop: 0 }}>Reassign all of a user's pages</h3>
        <form
          onSubmit={onBulkSubmit}
          className="admin-bulk-reassign-form"
          data-testid="admin-bulk-reassign-form"
        >
          <label>
            From owner
            <input
              type="text"
              value={bulkFrom}
              onChange={(e) => setBulkFrom(e.target.value)}
              placeholder="login or <orphaned>"
            />
          </label>
          <label>
            To owner
            <input
              type="text"
              value={bulkTo}
              onChange={(e) => setBulkTo(e.target.value)}
              placeholder="login or <orphaned>"
            />
          </label>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={bulkBusy || !bulkFrom.trim() || !bulkTo.trim()}
          >
            Reassign
          </button>
        </form>
      </section>

      {/* Filter tabs */}
      <div className="admin-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={filter === FILTER_ORPHANED}
          className={`admin-tab ${filter === FILTER_ORPHANED ? 'active' : ''}`}
          onClick={() => switchFilter(FILTER_ORPHANED)}
        >
          Orphaned
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={filter === FILTER_BY_OWNER}
          className={`admin-tab ${filter === FILTER_BY_OWNER ? 'active' : ''}`}
          onClick={() => switchFilter(FILTER_BY_OWNER)}
        >
          By Owner
        </button>
      </div>

      {filter === FILTER_BY_OWNER && (
        <form className="admin-toolbar" onSubmit={onSearch}>
          <input
            type="text"
            className="admin-search"
            value={ownerQuery}
            onChange={(e) => setOwnerQuery(e.target.value)}
            placeholder="Owner login (or <orphaned>)"
            aria-label="Owner login"
          />
          <button type="submit" className="btn btn-primary">Search</button>
        </form>
      )}

      <div className="admin-table-wrapper">
        <table className="admin-table">
          <thead>
            <tr>
              <th>Page name</th>
              <th>Canonical id</th>
              <th>Owner</th>
              <th>Assigned by</th>
              <th>Assigned at</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan="6" className="admin-empty">
                  {filter === FILTER_BY_OWNER && !submittedOwner
                    ? 'Enter a login above and hit Search.'
                    : 'No pages found.'}
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr key={r.canonicalId}>
                  <td>{r.pageName || '—'}</td>
                  <td><strong>{r.canonicalId}</strong></td>
                  <td>{r.ownerLogin || '—'}</td>
                  <td>{r.assignedBy ?? ''}</td>
                  <td>{r.assignedAt ?? ''}</td>
                  <td className="admin-cell-actions">
                    <button
                      type="button"
                      className="btn btn-sm"
                      onClick={() => openReassign(r)}
                    >
                      Reassign
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <p className="admin-row-count" style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
        {total} page{total === 1 ? '' : 's'}
      </p>

      {reassignTarget && (
        <div className="modal-overlay" onClick={closeReassign}>
          <div
            className="modal-content admin-modal"
            role="dialog"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-lg)' }}>
              Reassign {reassignTarget.canonicalId}
            </h2>
            <div className="form-field">
              <label>New owner</label>
              <input
                type="text"
                value={newOwner}
                onChange={(e) => setNewOwner(e.target.value)}
                placeholder="new owner login (or <orphaned>)"
                autoFocus
              />
            </div>
            <div className="modal-actions">
              <button className="btn btn-ghost" onClick={closeReassign}>Cancel</button>
              <button
                className="btn btn-primary"
                disabled={!newOwner.trim()}
                onClick={onReassignSubmit}
              >
                Reassign
              </button>
            </div>
          </div>
        </div>
      )}
    </AdminPage>
  );
}
