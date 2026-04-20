import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import '../../styles/admin.css';

const SCOPE_OPTIONS = [
  { value: 'tools', label: 'Tools (OpenAPI)' },
  { value: 'mcp', label: 'MCP' },
  { value: 'all', label: 'All (MCP + Tools)' },
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

  if (loading) return <div className="admin-loading">Loading API keys…</div>;
  if (error) return <div className="error-banner">{error}</div>;

  return (
    <div className="admin-users page-enter">
      <div className="admin-toolbar">
        <label style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
          <input
            type="checkbox"
            checked={showRevoked}
            onChange={(e) => setShowRevoked(e.target.checked)}
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

      <div className="admin-table-wrapper">
        <table className="admin-table">
          <thead>
            <tr>
              <th>Principal</th>
              <th>Label</th>
              <th>Scope</th>
              <th>Fingerprint</th>
              <th>Created</th>
              <th>Last Used</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {visible.length === 0 ? (
              <tr><td colSpan="8" className="admin-empty">No API keys found</td></tr>
            ) : visible.map(k => (
              <tr key={k.id}>
                <td className="admin-cell-primary">{k.principalLogin}</td>
                <td>{k.label || '—'}</td>
                <td><code>{k.scope}</code></td>
                <td><code title="SHA-256 prefix">{k.fingerprint}…</code></td>
                <td className="admin-cell-date">{formatDate(k.createdAt)}</td>
                <td className="admin-cell-date">{formatDate(k.lastUsedAt)}</td>
                <td>
                  <span className={`admin-badge ${k.active ? 'active' : 'locked'}`}>
                    {k.active ? 'Active' : 'Revoked'}
                  </span>
                </td>
                <td className="admin-cell-actions">
                  {k.active && (
                    <button
                      className="btn btn-ghost btn-sm btn-danger"
                      onClick={() => setConfirmRevoke(k)}
                    >
                      Revoke
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

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
    </div>
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
