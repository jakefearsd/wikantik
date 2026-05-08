import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import AdminPage from './AdminPage';
import { AdminTable } from './table';
import '../../styles/admin.css';

const SCOPE_OPTIONS = [
  { value: 'tools', label: 'Tools (OpenAPI)' },
  { value: 'mcp', label: 'MCP' },
  { value: 'all', label: 'All (MCP + Tools)' },
];

const BULK_ACTIONS = [
  {
    id: 'revoke',
    label: 'Revoke',
    variant: 'danger',
    confirm: {
      title: 'Revoke API Keys',
      body: (selected) => (
        <p>
          Revoke {selected.length} key{selected.length !== 1 ? 's' : ''}?
          Any client using {selected.length === 1 ? 'this token' : 'these tokens'} will
          start receiving HTTP 403.
        </p>
      ),
      confirmLabel: 'Revoke Keys',
    },
  },
];

const COLUMNS = [
  { id: 'principalLogin', label: 'Principal', sortable: true },
  { id: 'label', label: 'Label', render: (k) => k.label || '—' },
  { id: 'scope', label: 'Scope', render: (k) => <code>{k.scope}</code> },
  {
    id: 'fingerprint',
    label: 'Fingerprint',
    render: (k) => <code title="SHA-256 prefix">{k.fingerprint}…</code>,
  },
  {
    id: 'createdAt',
    label: 'Created',
    render: (k) => <span className="admin-cell-date">{formatDate(k.createdAt)}</span>,
    sortable: true,
  },
  {
    id: 'lastUsedAt',
    label: 'Last Used',
    render: (k) => <span className="admin-cell-date">{formatDate(k.lastUsedAt)}</span>,
    sortable: true,
  },
  {
    id: 'active',
    label: 'Status',
    render: (k) => (
      <span className={`admin-badge ${k.active ? 'active' : 'locked'}`}>
        {k.active ? 'Active' : 'Revoked'}
      </span>
    ),
  },
];

export default function AdminApiKeysPage() {
  const [keys, setKeys] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showRevoked, setShowRevoked] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [revealed, setRevealed] = useState(null); // { token, record }
  const [confirmRevoke, setConfirmRevoke] = useState(null);

  const loadKeys = async () => {
    try {
      const data = await api.admin.listApiKeys();
      setKeys(data.keys || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadKeys(); }, []);

  const visible = useMemo(() => {
    return showRevoked ? keys : keys.filter(k => k.active);
  }, [keys, showRevoked]);

  const handleGenerate = async (form) => {
    const result = await api.admin.createApiKey(form);
    setRevealed({ token: result.token, record: result });
    await loadKeys();
  };

  const handleRevoke = async (id) => {
    await api.admin.revokeApiKey(id);
    setConfirmRevoke(null);
    await loadKeys();
  };

  const handleBulkAction = async (action, selectedRows) => {
    const ids = selectedRows.map(k => String(k.id));
    const result = await api.admin.bulkApiKeyAction(action.id, ids);
    // Remove succeeded keys from local state for immediate UI feedback
    if (result.succeeded && result.succeeded.length > 0) {
      const succeededSet = new Set(result.succeeded);
      setKeys(prev => prev.map(k =>
        succeededSet.has(String(k.id)) ? { ...k, active: false } : k
      ));
    }
    return result;
  };

  const rowAction = (k) => {
    if (!k.active) return [];
    return [
      {
        id: 'revoke',
        label: 'Revoke',
        variant: 'danger',
        onClick: () => setConfirmRevoke(k),
      },
    ];
  };

  return (
    <AdminPage loading={loading} error={error} loadingLabel="Loading API keys…">
      <div className="admin-toolbar">
        <label style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
          <input
            type="checkbox"
            checked={showRevoked}
            onChange={(e) => setShowRevoked(e.target.checked)}
            aria-label="Show revoked"
          />
          Show revoked
        </label>
        <button
          className="btn btn-primary"
          onClick={() => setModalOpen(true)}
        >
          + Generate Key
        </button>
      </div>

      <AdminTable
        rows={visible}
        getRowKey={(k) => String(k.id)}
        columns={COLUMNS}
        selectable
        bulkActions={BULK_ACTIONS}
        onBulkAction={handleBulkAction}
        emptyMessage="No API keys found"
        rowAction={rowAction}
        kind="key"
        searchable={{ placeholder: 'Filter keys…' }}
        initialSort={{ columnId: 'createdAt', direction: 'desc' }}
      />

      <ApiKeyFormModal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        onSave={handleGenerate}
      />

      {revealed && (
        <RevealedTokenModal
          token={revealed.token}
          record={revealed.record}
          onClose={() => setRevealed(null)}
        />
      )}

      {confirmRevoke && (
        <div className="modal-overlay" onClick={() => setConfirmRevoke(null)}>
          <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
              Revoke API Key
            </h3>
            <p>
              Revoke key <strong>{confirmRevoke.label || `#${confirmRevoke.id}`}</strong> belonging to{' '}
              <strong>{confirmRevoke.principalLogin}</strong>? Any client using this token will start receiving HTTP 403.
            </p>
            <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
              <button className="btn btn-ghost" onClick={() => setConfirmRevoke(null)}>Cancel</button>
              <button className="btn btn-primary btn-danger" onClick={() => handleRevoke(confirmRevoke.id)}>
                Revoke Key
              </button>
            </div>
          </div>
        </div>
      )}
    </AdminPage>
  );
}

function ApiKeyFormModal({ isOpen, onClose, onSave }) {
  const [form, setForm] = useState({ principalLogin: '', label: '', scope: 'tools' });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (isOpen) {
      setForm({ principalLogin: '', label: '', scope: 'tools' });
      setError(null);
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const set = (key) => (e) => setForm(f => ({ ...f, [key]: e.target.value }));

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await onSave(form);
      onClose();
    } catch (err) {
      setError(err.message || 'Failed to generate API key');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-lg)' }}>
          Generate API Key
        </h2>

        {error && <div className="error-banner">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label>Principal (login)</label>
            <input
              type="text"
              required
              value={form.principalLogin}
              onChange={set('principalLogin')}
              placeholder="testbot"
              autoFocus
            />
            <p className="form-hint">
              Tool calls run as this user. Page ACLs and JAAS permissions apply accordingly.
            </p>
          </div>

          <div className="form-field">
            <label>Label</label>
            <input
              type="text"
              value={form.label}
              onChange={set('label')}
              placeholder="OpenWebUI production"
            />
            <p className="form-hint">Free-form note to identify where the key is used.</p>
          </div>

          <div className="form-field">
            <label>Scope</label>
            <select value={form.scope} onChange={set('scope')}>
              {SCOPE_OPTIONS.map(opt => (
                <option key={opt.value} value={opt.value}>{opt.label}</option>
              ))}
            </select>
          </div>

          <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
            <button type="button" className="btn btn-ghost" onClick={onClose} disabled={saving}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Generating…' : 'Generate'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function RevealedTokenModal({ token, record, onClose }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(token);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
          Copy this token now
        </h2>
        <p>
          This is the only time you will see the plaintext key for{' '}
          <strong>{record.principalLogin}</strong>
          {record.label ? <> (<em>{record.label}</em>)</> : null}. Store it somewhere safe —
          after closing this dialog only the hash remains.
        </p>
        <div style={{
          marginTop: 'var(--space-md)',
          padding: 'var(--space-md)',
          background: 'var(--color-surface-alt, #f5f5f5)',
          border: '1px solid var(--color-border, #ccc)',
          borderRadius: '4px',
          fontFamily: 'monospace',
          fontSize: '0.9em',
          wordBreak: 'break-all',
        }}>
          {token}
        </div>
        <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
          <button className="btn btn-ghost" onClick={copy}>
            {copied ? 'Copied ✓' : 'Copy to clipboard'}
          </button>
          <button className="btn btn-primary" onClick={onClose}>
            I've saved it — close
          </button>
        </div>
      </div>
    </div>
  );
}

function formatDate(dateStr) {
  if (!dateStr) return '—';
  try {
    return new Date(dateStr).toLocaleString();
  } catch {
    return dateStr;
  }
}
