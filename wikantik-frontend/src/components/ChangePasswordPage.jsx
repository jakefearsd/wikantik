import { useState } from 'react';
import { useNavigate, Navigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';

export default function ChangePasswordPage() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  const { refresh, user, loading } = useAuth();
  const navigate = useNavigate();

  if (!loading && user && !user.authenticated) {
    return <Navigate to="/login" replace />;
  }

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    if (newPassword !== confirmPassword) {
      setError('New passwords do not match');
      return;
    }
    setSaving(true);
    try {
      await api.updateProfile({ currentPassword, newPassword });
      await refresh();
      navigate('/wiki/Main', { replace: true });
    } catch (err) {
      setError(err.body?.message || err.message || 'Failed to change password');
    } finally {
      setSaving(false);
    }
  };

  const labelStyle = {
    display: 'block',
    fontSize: '0.8rem',
    fontWeight: 500,
    color: 'var(--text-muted)',
    marginBottom: 'var(--space-xs)',
  };

  const inputStyle = {
    width: '100%',
    padding: 'var(--space-sm) var(--space-md)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-md)',
    fontSize: '1rem',
    background: 'var(--bg)',
  };

  return (
    <div data-testid="change-password-page" style={{ display: 'flex', justifyContent: 'center', padding: 'var(--space-xl)' }}>
      <div style={{ maxWidth: '380px', width: '100%' }}>
        <form onSubmit={handleSubmit} style={{ padding: 'var(--space-xl)', border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)' }}>
          <div style={{ textAlign: 'center', marginBottom: 'var(--space-lg)' }}>
            <img
              src="/favicon.svg"
              alt="Wikantik"
              width="48"
              height="48"
              style={{ display: 'block', margin: '0 auto var(--space-sm)' }}
            />
            <h2 style={{
              fontFamily: 'var(--font-display)',
              fontSize: '1.5rem',
              margin: 0,
              lineHeight: 1.2,
            }}>
              Change Your Password
            </h2>
            <div style={{
              fontFamily: 'var(--font-ui)',
              fontSize: '0.85rem',
              color: 'var(--text-muted)',
              marginTop: 'var(--space-sm)',
              lineHeight: 1.5,
            }}>
              Your account requires a new password before you can continue — the
              current one was assigned, not chosen.
            </div>
          </div>

          {error && (
            <div className="error-banner" data-testid="change-error" style={{ marginBottom: 'var(--space-md)' }}>
              {error}
            </div>
          )}

          <div style={{ marginBottom: 'var(--space-md)' }}>
            <label style={labelStyle}>Current Password</label>
            <input
              type="password"
              name="currentPassword"
              data-testid="change-current"
              autoComplete="current-password"
              value={currentPassword}
              onChange={e => setCurrentPassword(e.target.value)}
              autoFocus
              style={inputStyle}
            />
          </div>

          <div style={{ marginBottom: 'var(--space-md)' }}>
            <label style={labelStyle}>New Password</label>
            <input
              type="password"
              name="newPassword"
              data-testid="change-new"
              autoComplete="new-password"
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              style={inputStyle}
            />
          </div>

          <div style={{ marginBottom: 'var(--space-lg)' }}>
            <label style={labelStyle}>Confirm New Password</label>
            <input
              type="password"
              name="confirmPassword"
              data-testid="change-confirm"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={e => setConfirmPassword(e.target.value)}
              style={inputStyle}
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            data-testid="change-submit"
            disabled={saving}
            style={{ width: '100%', justifyContent: 'center', padding: 'var(--space-sm) var(--space-lg)' }}
          >
            {saving ? 'Saving...' : 'Set New Password'}
          </button>
        </form>
      </div>
    </div>
  );
}
