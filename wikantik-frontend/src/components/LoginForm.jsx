import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import SsoLoginButton from './SsoLoginButton';
import Modal from './ui/Modal';

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
    <Modal isOpen onClose={onClose} labelledBy="login-modal-title" className="search-dialog" testId="login-modal">
        <form onSubmit={handleSubmit} data-testid="login-form">
          <h2 id="login-modal-title" style={{
            fontFamily: 'var(--font-display)',
            fontSize: '1.5rem',
            marginBottom: 'var(--space-lg)',
            textAlign: 'center'
          }}>
            Sign in
          </h2>

          <SsoLoginButton />

          {error && <div className="error-banner" data-testid="login-error" style={{ marginBottom: 'var(--space-md)' }}>{error}</div>}

          <div style={{ marginBottom: 'var(--space-md)' }}>
            <label className="field-label" htmlFor="login-username-input">Username</label>
            <input
              id="login-username-input"
              type="text"
              name="username"
              className="form-input"
              data-testid="login-username"
              autoComplete="username"
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoFocus
            />
          </div>

          <div style={{ marginBottom: 'var(--space-lg)' }}>
            <label className="field-label" htmlFor="login-password-input">Password</label>
            <input
              id="login-password-input"
              type="password"
              name="password"
              className="form-input"
              data-testid="login-password"
              autoComplete="current-password"
              value={password}
              onChange={e => setPassword(e.target.value)}
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            data-testid="login-submit"
            disabled={loading}
            style={{ width: '100%', justifyContent: 'center', padding: 'var(--space-sm) var(--space-lg)' }}
          >
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
    </Modal>
  );
}
