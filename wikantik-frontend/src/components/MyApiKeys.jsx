import { useState, useEffect } from 'react';
import { api } from '../api/client';
import RevealedTokenModal from './apikeys/RevealedTokenModal';

const SCOPES = ['tools', 'mcp', 'all'];

export default function MyApiKeys() {
  const [keys, setKeys] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState({ label: '', scope: 'tools' });
  const [busy, setBusy] = useState(false);
  const [revealed, setRevealed] = useState(null);          // { token, record }
  const [confirmRevoke, setConfirmRevoke] = useState(null); // key row

  const load = async () => {
    setLoading(true);
    try {
      const data = await api.self.listApiKeys();
      setKeys(data.keys || []);
      setError(null);
    } catch (err) {
      setError(err.message || 'Failed to load API keys');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const generate = async (e) => {
    e?.preventDefault();
    setBusy(true);
    try {
      const result = await api.self.createApiKey({ label: form.label || null, scope: form.scope });
      // result carries the metadata row + plaintext token; principalLogin shown in the
      // reveal modal — set it to the current key's account label for display.
      setRevealed({ token: result.token, record: { ...result, principalLogin: 'you' } });
      setFormOpen(false);
      setForm({ label: '', scope: 'tools' });
      await load();
    } catch (err) {
      setError(err.message || 'Failed to generate key');
    } finally {
      setBusy(false);
    }
  };

  const rotate = async (id) => {
    setBusy(true);
    try {
      const result = await api.self.rotateApiKey(id);
      setRevealed({ token: result.token, record: { ...result, principalLogin: 'you' } });
      await load();
    } catch (err) {
      setError(err.message || 'Failed to rotate key');
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (id) => {
    setBusy(true);
    try {
      await api.self.revokeApiKey(id);
      setConfirmRevoke(null);
      await load();
    } catch (err) {
      setError(err.message || 'Failed to revoke key');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section style={{ marginTop: 'var(--space-xl)' }}>
      <h2 style={{ fontFamily: 'var(--font-display)' }}>API Keys</h2>
      <p>
        Keys are bound to your account and act with <strong>your</strong> permissions.
        Secrets are shown once — store them somewhere safe.
      </p>
      {error && <div className="error-banner" role="alert">{error}</div>}

      <button className="btn btn-primary" onClick={() => setFormOpen(true)} disabled={busy}>
        + New key
      </button>

      {loading ? (
        <p>Loading…</p>
      ) : keys.length === 0 ? (
        <p>You have no active API keys.</p>
      ) : (
        <table className="data-table" style={{ marginTop: 'var(--space-md)', width: '100%' }}>
          <thead>
            <tr><th>Label</th><th>Scope</th><th>Created</th><th>Last used</th><th></th></tr>
          </thead>
          <tbody>
            {keys.map((k) => (
              <tr key={k.id}>
                <td>{k.label || <em>—</em>}</td>
                <td><code>{k.scope}</code></td>
                <td>{fmt(k.createdAt)}</td>
                <td>{fmt(k.lastUsedAt)}</td>
                <td>
                  <button className="btn btn-ghost" onClick={() => rotate(k.id)} disabled={busy}>Rotate</button>
                  <button className="btn btn-ghost" onClick={() => setConfirmRevoke(k)} disabled={busy}>Revoke</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {formOpen && (
        <div className="modal-overlay" onClick={() => setFormOpen(false)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <h3>Generate API key</h3>
            <form onSubmit={generate}>
              <label>Label
                <input value={form.label} onChange={(e) => setForm({ ...form, label: e.target.value })}
                       placeholder="e.g. laptop, ci-bot" />
              </label>
              <label>Scope
                <select value={form.scope} onChange={(e) => setForm({ ...form, scope: e.target.value })}>
                  {SCOPES.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </label>
              <div className="modal-actions">
                <button type="button" className="btn btn-ghost" onClick={() => setFormOpen(false)}>Cancel</button>
                <button type="submit" className="btn btn-primary" disabled={busy}>Generate key</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {revealed && (
        <RevealedTokenModal token={revealed.token} record={revealed.record} onClose={() => setRevealed(null)} />
      )}

      {confirmRevoke && (
        <div className="modal-overlay" onClick={() => setConfirmRevoke(null)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <h3>Revoke this key?</h3>
            <p>Any client using this key will immediately start receiving HTTP 403.</p>
            <div className="modal-actions">
              <button className="btn btn-ghost" onClick={() => setConfirmRevoke(null)}>Cancel</button>
              <button className="btn btn-primary" onClick={() => revoke(confirmRevoke.id)} disabled={busy}>Revoke key</button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

function fmt(iso) {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleDateString(); } catch { return iso; }
}
