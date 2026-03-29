import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

export default function LoginForm({ onClose }) {
  const { login } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await login(username, password);
      onClose();
    } catch (err) {
      setError('Invalid credentials');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="search-overlay" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="search-dialog" style={{ maxWidth: '380px' }}>
        <form onSubmit={handleSubmit} style={{ padding: 'var(--space-xl)' }}>
          <h2 style={{
            fontFamily: 'var(--font-display)',
            fontSize: '1.5rem',
            marginBottom: 'var(--space-lg)',
            textAlign: 'center'
          }}>
            Sign in
          </h2>

          {error && <div className="error-banner" style={{ marginBottom: 'var(--space-md)' }}>{error}</div>}

          <div style={{ marginBottom: 'var(--space-md)' }}>
            <label style={{
              display: 'block',
              fontSize: '0.8rem',
              fontWeight: 500,
              color: 'var(--text-muted)',
              marginBottom: 'var(--space-xs)'
            }}>Username</label>
            <input
              type="text"
              name="username"
              autoComplete="username"
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoFocus
              style={{
                width: '100%',
                padding: 'var(--space-sm) var(--space-md)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)',
                fontSize: '1rem',
                background: 'var(--bg)',
              }}
            />
          </div>

          <div style={{ marginBottom: 'var(--space-lg)' }}>
            <label style={{
              display: 'block',
              fontSize: '0.8rem',
              fontWeight: 500,
              color: 'var(--text-muted)',
              marginBottom: 'var(--space-xs)'
            }}>Password</label>
            <input
              type="password"
              name="password"
              autoComplete="current-password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              style={{
                width: '100%',
                padding: 'var(--space-sm) var(--space-md)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)',
                fontSize: '1rem',
                background: 'var(--bg)',
              }}
            />
          </div>

          <button type="submit" className="btn btn-primary" disabled={loading}
            style={{ width: '100%', justifyContent: 'center', padding: 'var(--space-sm) var(--space-lg)' }}>
            {loading ? 'Signing in...' : 'Sign in'}
          </button>

          <div style={{ textAlign: 'center', marginTop: 'var(--space-md)' }}>
            <Link
              to="/reset-password"
              onClick={onClose}
              style={{
                color: 'var(--text-muted)',
                textDecoration: 'none',
                fontSize: '0.8rem',
              }}
            >
              Forgot your password?
            </Link>
          </div>
        </form>
      </div>
    </div>
  );
}
