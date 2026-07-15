// ConnectorDetailPage.jsx
//
// Connector detail page (T20 of the connector-admin-ui plan): four tabs
// (Overview / Settings / Authorization / Pages) over a single connector,
// plus a gated delete flow. Loads api.connectors.get(id) and re-fetches on
// any mutation so the page always reflects the server's current state.
import { useCallback, useEffect, useState } from 'react';
import { useParams, useNavigate, useSearchParams, Link } from 'react-router-dom';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import PageHeader from './PageHeader';
import ConnectorSettingsForm from './ConnectorSettingsForm';
import { CONNECTOR_TYPES } from './connectorGuides';
import { formatDateTime, formatRelative } from '../../utils/datetime';
import '../../styles/admin.css';

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'settings', label: 'Settings' },
  { id: 'authorization', label: 'Authorization' },
  { id: 'pages', label: 'Pages' },
];

const RUN_STALE_MS = 60 * 60 * 1000; // 1h — a "running" row older than this is treated as interrupted

function errorMessage(err, fallback) {
  return err?.body?.message || err?.message || fallback;
}

function deriveRunStatus(run) {
  if (run.status === 'running' && run.started) {
    const startedMs = new Date(run.started).getTime();
    if (!Number.isNaN(startedMs) && Date.now() - startedMs > RUN_STALE_MS) {
      return 'interrupted';
    }
  }
  return run.status;
}

export default function ConnectorDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const oauthParam = searchParams.get('oauth');
  const nextParam = searchParams.get('next');

  // ?next=authorize (wizard "authorize now" redirect) and ?oauth=… (Task 20's
  // consent-callback result) can both be present at once — e.g. a return trip
  // from Google's consent screen carries ?oauth_return=1&oauth=ok while the
  // wizard's own deep link only ever carries ?next=authorize. Either one
  // alone, or both together, must land on the Authorization tab.
  const [activeTab, setActiveTab] = useState(
    oauthParam || nextParam === 'authorize' ? 'authorization' : 'overview'
  );
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const load = useCallback(async () => {
    try {
      const data = await api.connectors.get(id);
      setDetail(data);
      setError(null);
    } catch (err) {
      setError(errorMessage(err, 'Failed to load connector'));
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  return (
    <AdminPage loading={loading} error={error} loadingLabel="Loading connector…">
      {detail && (
        <>
          <Link
            to="/admin/connectors"
            data-testid="back-to-connectors"
            style={{ color: 'var(--text-muted)', fontSize: '0.875rem', textDecoration: 'none', display: 'inline-block', marginBottom: 'var(--space-md)' }}
          >
            ← Connectors
          </Link>
          <PageHeader
            title={`${CONNECTOR_TYPES[detail.type]?.icon || ''} ${detail.id}`.trim()}
            description={CONNECTOR_TYPES[detail.type]?.label || detail.type}
            actions={
              <button
                type="button"
                className="btn btn-primary btn-danger"
                data-testid="delete-connector-button"
                onClick={() => setDeleteOpen(true)}
              >
                Delete
              </button>
            }
          />

          {oauthParam === 'ok' && (
            <div className="admin-row-message" data-testid="oauth-result-ok" role="status">
              Google Drive authorization succeeded.
            </div>
          )}
          {oauthParam && oauthParam !== 'ok' && (
            <div className="error-banner" data-testid="oauth-result-error" role="alert">
              Google Drive authorization failed: {oauthParam}
            </div>
          )}

          <div className="admin-tabs">
            {TABS.map((tab) => (
              <button
                key={tab.id}
                type="button"
                className={`admin-tab ${activeTab === tab.id ? 'active' : ''}`}
                data-testid={`tab-${tab.id}`}
                onClick={() => setActiveTab(tab.id)}
              >
                {tab.label}
              </button>
            ))}
          </div>

          <div data-testid={`connector-tab-panel-${activeTab}`}>
            {activeTab === 'overview' && <OverviewTab id={id} detail={detail} onReload={load} />}
            {activeTab === 'settings' && <SettingsTab id={id} detail={detail} onReload={load} />}
            {activeTab === 'authorization' && <AuthorizationTab id={id} detail={detail} onReload={load} />}
            {activeTab === 'pages' && <PagesTab id={id} />}
          </div>

          {deleteOpen && (
            <DeleteConnectorModal
              id={id}
              onClose={() => setDeleteOpen(false)}
              onDeleted={() => navigate('/admin/connectors')}
            />
          )}
        </>
      )}
    </AdminPage>
  );
}

// ---------------------------------------------------------------------------
// Overview tab
// ---------------------------------------------------------------------------

function OverviewTab({ id, detail, onReload }) {
  const [runs, setRuns] = useState([]);
  const [runsError, setRunsError] = useState(null);
  const [syncing, setSyncing] = useState(false);
  const [syncMessage, setSyncMessage] = useState(null);
  const [testing, setTesting] = useState(false);
  const [testMessage, setTestMessage] = useState(null);

  const loadRuns = useCallback(async () => {
    try {
      const data = await api.connectors.runs(id);
      setRuns(data.runs || []);
      setRunsError(null);
    } catch (err) {
      setRunsError(errorMessage(err, 'Failed to load run history'));
    }
  }, [id]);

  useEffect(() => { loadRuns(); }, [loadRuns]);

  const handleSync = async () => {
    setSyncing(true);
    setSyncMessage(null);
    try {
      await api.connectors.sync(id);
      await Promise.all([onReload(), loadRuns()]);
    } catch (err) {
      setSyncMessage(errorMessage(err, 'Sync failed'));
    } finally {
      setSyncing(false);
    }
  };

  const handleTest = async () => {
    setTesting(true);
    setTestMessage(null);
    try {
      const result = await api.connectors.testSaved(id);
      setTestMessage(result?.message || 'Connection OK.');
    } catch (err) {
      setTestMessage(errorMessage(err, 'Test failed'));
    } finally {
      setTesting(false);
    }
  };

  return (
    <div>
      <div className="connector-status-strip" data-testid="connector-status-strip">
        <span className={`admin-badge ${detail.enabled ? 'active' : 'locked'}`}>
          {detail.enabled ? 'Enabled' : 'Disabled'}
        </span>
        <span>{detail.syncIntervalHours ? `${detail.syncIntervalHours}h interval` : 'Manual sync only'}</span>
        <span>Last run: {detail.lastRun ? formatRelative(detail.lastRun) : 'never'}</span>
        <span>{detail.pageCount ?? 0} pages</span>
      </div>

      <div className="admin-toolbar">
        <button type="button" className="btn btn-primary" data-testid="sync-now-button" onClick={handleSync} disabled={syncing}>
          {syncing ? 'Syncing…' : 'Sync Now'}
        </button>
        <button type="button" className="btn btn-ghost" data-testid="test-connection-button" onClick={handleTest} disabled={testing}>
          {testing ? 'Testing…' : 'Test Connection'}
        </button>
      </div>
      {syncMessage && (
        <div className="admin-row-message" data-testid="sync-now-message" role="alert">{syncMessage}</div>
      )}
      {testMessage && (
        <div className="admin-row-message" data-testid="test-connection-message" role="status">{testMessage}</div>
      )}

      {runsError && <div className="error-banner" role="alert">{runsError}</div>}

      <div className="admin-table-wrapper">
        <table className="admin-table" data-testid="runs-table">
          <thead>
            <tr>
              <th>Trigger</th><th>Started</th><th>Finished</th><th>Status</th>
              <th>Created</th><th>Updated</th><th>Unchanged</th><th>Deleted</th><th>Failed</th><th>Error</th>
            </tr>
          </thead>
          <tbody>
            {runs.map((run) => {
              const status = deriveRunStatus(run);
              return (
                <tr key={run.runId} data-testid={`run-row-${run.runId}`}>
                  <td>{run.trigger}</td>
                  <td>{formatDateTime(run.started)}</td>
                  <td>{run.finished ? formatDateTime(run.finished) : '—'}</td>
                  <td data-testid={`run-status-${run.runId}`}>{status}</td>
                  <td>{run.created ?? 0}</td>
                  <td>{run.updated ?? 0}</td>
                  <td>{run.unchanged ?? 0}</td>
                  <td>{run.deleted ?? 0}</td>
                  <td>{run.failed ?? 0}</td>
                  <td>
                    {run.status === 'failed' && run.error && (
                      <details data-testid={`run-error-${run.runId}`}>
                        <summary>Error</summary>
                        <pre>{run.error}</pre>
                      </details>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {runs.length === 0 && <p data-testid="runs-empty">No sync runs yet.</p>}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Settings tab
// ---------------------------------------------------------------------------

function SettingsTab({ id, detail, onReload }) {
  const [errors, setErrors] = useState({});
  const [saveMessage, setSaveMessage] = useState(null);
  const [importing, setImporting] = useState(false);
  const [importError, setImportError] = useState(null);

  const readOnly = detail.origin === 'properties';

  const handleSubmit = async (body) => {
    setErrors({});
    setSaveMessage(null);
    try {
      await api.connectors.update(id, body);
      setSaveMessage('Saved.');
      await onReload();
    } catch (err) {
      if (err.status === 422) {
        setErrors(err.body?.errors || {});
      } else {
        setSaveMessage(errorMessage(err, 'Save failed'));
      }
    }
  };

  const handleImport = async () => {
    setImporting(true);
    setImportError(null);
    try {
      await api.connectors.importFromProperties(id);
      await onReload();
    } catch (err) {
      setImportError(errorMessage(err, 'Import failed'));
    } finally {
      setImporting(false);
    }
  };

  return (
    <div>
      {readOnly && (
        <div className="warning-banner" data-testid="properties-origin-note">
          <p>
            Defined in wikantik-custom.properties — import to edit here. For Google Drive, re-enter the
            client secret under Authorization after importing.
          </p>
          <button
            type="button"
            className="btn btn-primary"
            data-testid="import-to-database-button"
            onClick={handleImport}
            disabled={importing}
          >
            {importing ? 'Importing…' : 'Import to database'}
          </button>
          {importError && <div className="error-banner" role="alert">{importError}</div>}
        </div>
      )}

      <ConnectorSettingsForm
        type={detail.type}
        initialValues={{
          config: detail.config,
          enabled: detail.enabled,
          syncIntervalHours: detail.syncIntervalHours,
          cluster: detail.cluster,
          defaultTags: detail.defaultTags,
          pagePrefix: detail.pagePrefix,
        }}
        onSubmit={handleSubmit}
        submitLabel="Save Settings"
        errors={errors}
        readOnly={readOnly}
      />
      {saveMessage && <div data-testid="settings-save-message">{saveMessage}</div>}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Authorization tab
// ---------------------------------------------------------------------------

function AuthorizationTab({ id, detail }) {
  const secrets = CONNECTOR_TYPES[detail.type]?.secrets || [];
  const [secretsSet, setSecretsSet] = useState(() => new Set(detail.secretsSet || []));
  const [values, setValues] = useState({});
  const [busy, setBusy] = useState({});
  const [messages, setMessages] = useState({});

  const setBusyFor = (name, val) => setBusy((prev) => ({ ...prev, [name]: val }));
  const setMessageFor = (name, val) => setMessages((prev) => ({ ...prev, [name]: val }));

  const handleSave = async (name) => {
    setBusyFor(name, true);
    setMessageFor(name, null);
    try {
      await api.connectors.setCredential(id, name, values[name] || '');
      setSecretsSet((prev) => new Set(prev).add(name));
      setValues((prev) => ({ ...prev, [name]: '' }));
    } catch (err) {
      setMessageFor(name, errorMessage(err, 'Save failed'));
    } finally {
      setBusyFor(name, false);
    }
  };

  const handleDelete = async (name) => {
    setBusyFor(name, true);
    setMessageFor(name, null);
    try {
      await api.connectors.deleteCredential(id, name);
      setSecretsSet((prev) => {
        const next = new Set(prev);
        next.delete(name);
        return next;
      });
    } catch (err) {
      setMessageFor(name, errorMessage(err, 'Delete failed'));
    } finally {
      setBusyFor(name, false);
    }
  };

  if (secrets.length === 0) {
    return <p data-testid="no-credentials-note">This connector type needs no credentials.</p>;
  }

  const base = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';
  const returnTo = `/admin/connectors/${id}?oauth_return=1`;
  const authorizeHref = `${base}/admin/connector-oauth/gdrive/${id}/authorize?return_to=${encodeURIComponent(returnTo)}`;

  return (
    <div>
      {detail.type === 'gdrive' && (
        <div className="connector-consent-state" data-testid="gdrive-consent-state">
          {secretsSet.has('refresh_token') ? 'Authorized ✓' : 'Not authorized'}{' '}
          {/* Plain <a> — top-level navigation, never a router Link. A #fragment
              here would swallow the ?oauth= param the callback appends. */}
          <a href={authorizeHref} data-testid="gdrive-authorize-link" className="btn btn-primary">
            Authorize with Google
          </a>
        </div>
      )}

      {secrets.map((name) => (
        <div key={name} className="form-field" data-testid={`secret-row-${name}`}>
          <label htmlFor={`secret-input-${name}`}>
            {name} — <span data-testid={`secret-state-${name}`}>{secretsSet.has(name) ? 'Set' : 'Not set'}</span>
          </label>
          <input
            id={`secret-input-${name}`}
            type="password"
            data-testid={`secret-input-${name}`}
            value={values[name] || ''}
            onChange={(e) => setValues((prev) => ({ ...prev, [name]: e.target.value }))}
            autoComplete="off"
          />
          <div className="admin-cell-actions">
            <button
              type="button"
              className="btn btn-primary"
              data-testid={`secret-save-${name}`}
              onClick={() => handleSave(name)}
              disabled={busy[name] || !values[name]}
            >
              Save
            </button>
            <button
              type="button"
              className="btn btn-ghost"
              data-testid={`secret-delete-${name}`}
              onClick={() => handleDelete(name)}
              disabled={busy[name] || !secretsSet.has(name)}
            >
              Delete
            </button>
          </div>
          {messages[name] && (
            <div className="admin-row-message" role="alert" data-testid={`secret-message-${name}`}>{messages[name]}</div>
          )}
        </div>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Pages tab
// ---------------------------------------------------------------------------

function PagesTab({ id }) {
  const [pages, setPages] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.connectors.pages(id);
        if (!cancelled) setPages(data.pages || []);
      } catch (err) {
        if (!cancelled) setError(errorMessage(err, 'Failed to load pages'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  if (loading) return <div className="admin-loading">Loading pages…</div>;
  if (error) return <div className="error-banner" role="alert">{error}</div>;

  return (
    <div className="admin-table-wrapper">
      <table className="admin-table" data-testid="pages-table">
        <thead>
          <tr><th>Page</th><th>Source URI</th><th>Last synced</th></tr>
        </thead>
        <tbody>
          {pages.map((p) => (
            <tr key={p.pageName}>
              <td><Link to={`/wiki/${p.pageName}`}>{p.pageName}</Link></td>
              <td>{p.sourceUri}</td>
              <td>{p.lastSynced ? formatDateTime(p.lastSynced) : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {pages.length === 0 && <p data-testid="pages-empty">No pages yet.</p>}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Delete modal
// ---------------------------------------------------------------------------

function DeleteConnectorModal({ id, onClose, onDeleted }) {
  const [pageCount, setPageCount] = useState(null);
  const [loading, setLoading] = useState(true);
  const [deletePages, setDeletePages] = useState(false);
  const [confirmText, setConfirmText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.connectors.pages(id);
        if (!cancelled) setPageCount((data.pages || []).length);
      } catch (err) {
        if (!cancelled) {
          setPageCount(0);
          setError(errorMessage(err, 'Failed to load page count'));
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [id]);

  // Deleting the connector alone (keeping its pages) needs no extra
  // confirmation; opting to also delete the derived pages requires typing
  // the connector id to prove intent.
  const canDelete = !deletePages || confirmText === id;

  const handleDelete = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.connectors.remove(id, deletePages);
      onDeleted();
    } catch (err) {
      setError(errorMessage(err, 'Delete failed'));
      setBusy(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()} data-testid="delete-connector-modal">
        <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>Delete connector</h3>
        {loading ? (
          <p>Loading…</p>
        ) : (
          <>
            <p>
              This connector created {pageCount} pages. They will be kept and marked &quot;no longer syncing&quot;.
            </p>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
              <input
                type="checkbox"
                data-testid="delete-pages-checkbox"
                checked={deletePages}
                onChange={(e) => {
                  setDeletePages(e.target.checked);
                  setConfirmText('');
                }}
              />
              Also delete all {pageCount} derived pages
            </label>

            {deletePages && (
              <div className="form-field" style={{ marginTop: 'var(--space-md)' }}>
                <label htmlFor="delete-confirm-input">Type the connector id ({id}) to confirm</label>
                <input
                  id="delete-confirm-input"
                  type="text"
                  data-testid="delete-confirm-input"
                  value={confirmText}
                  onChange={(e) => setConfirmText(e.target.value)}
                />
              </div>
            )}

            {error && <div className="error-banner" role="alert">{error}</div>}

            <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
              <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
              <button
                type="button"
                className="btn btn-primary btn-danger"
                data-testid="delete-confirm-button"
                disabled={!canDelete || busy}
                onClick={handleDelete}
              >
                {busy ? 'Deleting…' : 'Delete connector'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
