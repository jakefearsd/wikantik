import { useState, useEffect } from 'react';
import { useAuth } from '../hooks/useAuth';
import { api } from '../api/client';
import { useNavigate } from 'react-router-dom';

export default function UserPreferencesPage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [profile, setProfile] = useState(null);
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (user && !user.authenticated) {
      navigate('/wiki/Main');
      return;
    }
    loadProfile();
  }, [user]);

  const loadProfile = async () => {
    try {
      const data = await api.getProfile();
      setProfile(data);
      setFullName(data.fullName || '');
      setEmail(data.email || '');
    } catch (err) {
      setError(err.message || 'Failed to load profile');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setMessage(null);

    if (newPassword && newPassword !== confirmPassword) {
      setError('New passwords do not match');
      return;
    }

    setSaving(true);
    try {
      const data = { fullName, email };
      if (newPassword) {
        data.currentPassword = currentPassword;
        data.newPassword = newPassword;
      }
      const updated = await api.updateProfile(data);
      setProfile(updated);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      setMessage('Profile updated successfully');
    } catch (err) {
      setError(err.body?.message || err.message || 'Failed to update profile');
    } finally {
      setSaving(false);
    }
  };

  if (!user || !user.authenticated) return null;

  if (loading) {
    return (
      <div style={{ padding: 'var(--space-xl)', textAlign: 'center', color: 'var(--text-muted)' }}>
        Loading profile...
      </div>
    );
  }

  const labelStyle = {
    display: 'block',
    fontSize: '0.8rem',
    fontWeight: 500,
    color: 'var(--text-muted)',
    marginBottom: 'var(--space-xs)',
    fontFamily: 'var(--font-ui)',
  };

  const inputStyle = {
    width: '100%',
    padding: 'var(--space-sm) var(--space-md)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-md)',
    fontSize: '1rem',
    background: 'var(--bg)',
    color: 'var(--text)',
    fontFamily: 'var(--font-ui)',
  };

  const readOnlyStyle = {
    ...inputStyle,
    background: 'var(--bg-sidebar)',
    color: 'var(--text-muted)',
    cursor: 'not-allowed',
  };

  const fieldStyle = {
    marginBottom: 'var(--space-md)',
  };

  return (
    <div style={{ maxWidth: '560px', margin: '0 auto', padding: 'var(--space-xl)' }}>
      <h1 style={{
        fontFamily: 'var(--font-display)',
        fontSize: '2rem',
        fontWeight: 700,
        marginBottom: 'var(--space-xs)',
        color: 'var(--text)',
      }}>
        Preferences
      </h1>
      <p style={{
        color: 'var(--text-muted)',
        fontSize: '0.9rem',
        marginBottom: 'var(--space-xl)',
        fontFamily: 'var(--font-ui)',
      }}>
        Manage your account settings and password.
      </p>

      {message && (
        <div style={{
          background: 'var(--sage-light)',
          border: '1px solid var(--sage)',
          color: 'var(--sage)',
          padding: 'var(--space-sm) var(--space-md)',
          borderRadius: 'var(--radius-md)',
          fontSize: '0.875rem',
          marginBottom: 'var(--space-lg)',
        }}>
          {message}
        </div>
      )}

      {error && (
        <div className="error-banner" style={{ marginBottom: 'var(--space-lg)' }}>
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit}>
        <fieldset style={{
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius-lg)',
          padding: 'var(--space-lg)',
          marginBottom: 'var(--space-lg)',
        }}>
          <legend style={{
            fontFamily: 'var(--font-display)',
            fontSize: '1.1rem',
            fontWeight: 600,
            padding: '0 var(--space-sm)',
            color: 'var(--text)',
          }}>
            Profile Information
          </legend>

          <div style={fieldStyle}>
            <label style={labelStyle}>Login Name</label>
            <input
              type="text"
              value={profile?.loginName || ''}
              readOnly
              style={readOnlyStyle}
            />
          </div>

          <div style={fieldStyle}>
            <label style={labelStyle}>Wiki Name</label>
            <input
              type="text"
              value={profile?.wikiName || ''}
              readOnly
              style={readOnlyStyle}
            />
          </div>

          <div style={fieldStyle}>
            <label style={labelStyle}>Full Name</label>
            <input
              type="text"
              value={fullName}
              onChange={e => setFullName(e.target.value)}
              style={inputStyle}
            />
          </div>

          <div style={fieldStyle}>
            <label style={labelStyle}>Email</label>
            <input
              type="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              style={inputStyle}
            />
          </div>
        </fieldset>

        <fieldset style={{
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius-lg)',
          padding: 'var(--space-lg)',
          marginBottom: 'var(--space-lg)',
        }}>
          <legend style={{
            fontFamily: 'var(--font-display)',
            fontSize: '1.1rem',
            fontWeight: 600,
            padding: '0 var(--space-sm)',
            color: 'var(--text)',
          }}>
            Change Password
          </legend>

          <p style={{
            fontSize: '0.8rem',
            color: 'var(--text-muted)',
            marginBottom: 'var(--space-md)',
          }}>
            Leave blank to keep your current password.
          </p>

          <div style={fieldStyle}>
            <label style={labelStyle}>Current Password</label>
            <input
              type="password"
              value={currentPassword}
              onChange={e => setCurrentPassword(e.target.value)}
              style={inputStyle}
              autoComplete="current-password"
            />
          </div>

          <div style={fieldStyle}>
            <label style={labelStyle}>New Password</label>
            <input
              type="password"
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              style={inputStyle}
              autoComplete="new-password"
            />
          </div>

          <div style={fieldStyle}>
            <label style={labelStyle}>Confirm New Password</label>
            <input
              type="password"
              value={confirmPassword}
              onChange={e => setConfirmPassword(e.target.value)}
              style={inputStyle}
              autoComplete="new-password"
            />
          </div>
        </fieldset>

        <button
          type="submit"
          className="btn btn-primary"
          disabled={saving}
          style={{
            width: '100%',
            justifyContent: 'center',
            padding: 'var(--space-sm) var(--space-lg)',
          }}
        >
          {saving ? 'Saving...' : 'Save Changes'}
        </button>
      </form>
    </div>
  );
}
