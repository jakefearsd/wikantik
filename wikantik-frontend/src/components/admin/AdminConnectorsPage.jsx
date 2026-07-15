// AdminConnectorsPage.jsx
//
// Connectors admin list page (T19 of the connector-admin-ui plan). Loads
// api.connectors.list() — { syncingEnabled, credentialStoreEnabled, connectors[] }
// — and renders a table with a per-row Sync Now action. Detail/create flows
// (connectors/:id, connectors/new) are owned by later tasks; the Add Connector
// button here just navigates, it's fine for the target route to 404 until
// those ship.
import { useCallback, useEffect, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import PageHeader from './PageHeader';
import { AdminTable } from './table';
import { formatRelative } from '../../utils/datetime';
import '../../styles/admin.css';

// Plain-text glyphs — no icon font/SVG dependency for the admin table.
const TYPE_ICONS = {
  webcrawler: '🕸',
  sitemap: '🗺',
  feed: '📰',
  gdrive: '📁',
  github: '🐙',
  confluence: '🌀',
  filesystem: '💾',
};

export default function AdminConnectorsPage() {
  const navigate = useNavigate();

  const [connectors, setConnectors] = useState([]);
  const [syncingEnabled, setSyncingEnabled] = useState(true);
  const [credentialStoreEnabled, setCredentialStoreEnabled] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Per-row sync-in-flight tracking + inline error surfacing (e.g. a 409
  // "sync already running" from ConnectorRuntime).
  const [syncingIds, setSyncingIds] = useState(() => new Set());
  const [rowMessages, setRowMessages] = useState({});

  const load = useCallback(async () => {
    try {
      const data = await api.connectors.list();
      setConnectors(data.connectors || []);
      setSyncingEnabled(data.syncingEnabled !== false);
      setCredentialStoreEnabled(data.credentialStoreEnabled !== false);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const goToWizard = () => navigate('/admin/connectors/new');

  const handleSync = async (id) => {
    setRowMessages((prev) => {
      if (!(id in prev)) return prev;
      const next = { ...prev };
      delete next[id];
      return next;
    });
    setSyncingIds((prev) => new Set(prev).add(id));
    try {
      await api.connectors.sync(id);
      await load();
    } catch (err) {
      setRowMessages((prev) => ({ ...prev, [id]: err.message || String(err) }));
    } finally {
      setSyncingIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  };

  const columns = [
    {
      id: 'type',
      label: 'Type',
      render: (c) => <span>{TYPE_ICONS[c.type] || '❔'} {c.type}</span>,
    },
    {
      id: 'id',
      label: 'ID',
      render: (c) => (
        <Link to={`/admin/connectors/${encodeURIComponent(c.id)}`} data-testid={`connector-link-${c.id}`}>
          {c.id}
        </Link>
      ),
    },
    {
      id: 'origin',
      label: 'Origin',
      render: (c) => (
        <span className="admin-badge badge-default">
          {c.origin === 'properties' ? 'config file' : 'database'}
        </span>
      ),
    },
    {
      id: 'enabled',
      label: 'Enabled',
      render: (c) => (
        <span className={`admin-badge ${c.enabled ? 'active' : 'locked'}`}>
          {c.enabled ? 'Enabled' : 'Disabled'}
        </span>
      ),
    },
    {
      id: 'syncIntervalHours',
      label: 'Interval',
      render: (c) => (c.syncIntervalHours ? `${c.syncIntervalHours}h` : 'manual'),
    },
    {
      id: 'lastRun',
      label: 'Last Run',
      render: (c) => (c.lastRun ? formatRelative(c.lastRun) : '—'),
    },
    {
      id: 'lastStatus',
      label: 'Last Status',
      render: (c) => c.lastStatus || '—',
    },
    {
      id: 'pageCount',
      label: 'Pages',
      render: (c) => c.pageCount ?? 0,
    },
    {
      id: 'actions',
      label: 'Actions',
      render: (c) => {
        const busy = syncingIds.has(c.id);
        return (
          <div className="admin-cell-actions">
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              data-testid={`sync-${c.id}`}
              disabled={busy}
              onClick={() => handleSync(c.id)}
            >
              {busy ? 'Syncing…' : 'Sync Now'}
            </button>
            {rowMessages[c.id] && (
              <div className="admin-row-message" data-testid={`sync-message-${c.id}`} role="alert">
                {rowMessages[c.id]}
              </div>
            )}
          </div>
        );
      },
    },
  ];

  const hasConnectors = connectors.length > 0;

  return (
    <AdminPage loading={loading} error={error} loadingLabel="Loading connectors…">
      <PageHeader
        title="Connectors"
        description="Sync external sources — websites, feeds, Google Drive folders, GitHub repos, Confluence spaces — into wiki pages."
        actions={
          <button
            type="button"
            className="btn btn-primary"
            data-testid="add-connector-button"
            onClick={goToWizard}
          >
            + Add Connector
          </button>
        }
      />

      {!syncingEnabled && (
        <div className="warning-banner" data-testid="connectors-disabled-banner">
          Connector syncing is disabled by the operator (<code>wikantik.connectors.enabled=false</code> in
          wikantik-custom.properties). Configuration remains editable; syncs will not run.
        </div>
      )}

      {!credentialStoreEnabled && (
        <div className="warning-banner" data-testid="credstore-disabled-banner">
          Credential storage is not configured, so GitHub / Confluence / Google Drive connectors cannot store
          secrets. Web crawler, sitemap and feed connectors still work. To enable: generate a key with{' '}
          <code>openssl rand -base64 32</code> and set <code>wikantik.connectors.crypto.key=&lt;key&gt;</code> in
          wikantik-custom.properties, then restart.
        </div>
      )}

      {hasConnectors ? (
        <AdminTable
          rows={connectors}
          getRowKey={(c) => String(c.id)}
          columns={columns}
          emptyMessage="No connectors found."
          kindLabel="connectors"
        />
      ) : (
        <div className="admin-empty-state" data-testid="connectors-empty-state">
          <p>
            Connectors sync external sources — websites, feeds, Google Drive folders, GitHub repos, Confluence
            spaces — into wiki pages marked with their origin.
          </p>
          <button
            type="button"
            className="btn btn-primary"
            data-testid="add-connector-button-empty"
            onClick={goToWizard}
          >
            + Add Connector
          </button>
        </div>
      )}
    </AdminPage>
  );
}
