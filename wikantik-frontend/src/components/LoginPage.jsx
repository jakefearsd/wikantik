import { useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import SsoLoginButton from './SsoLoginButton';

const SSO_ERROR_MESSAGES = {
  sso_callback_failed: 'Single sign-on could not be completed. Please try signing in again.',
  no_sso_client: 'Single sign-on is not configured. Contact your administrator.',
  sso_redirect_failed: 'Could not start single sign-on. Please try again.',
};

function ssoErrorMessage(code) {
  if (!code) return null;
  return SSO_ERROR_MESSAGES[code] ?? 'Sign-in failed. Please try again.';
}

export default function LoginPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login } = useAuth();

  const errorCode = searchParams.get('error');
  const ssoMessage = ssoErrorMessage(errorCode);

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [credError, setCredError] = useState(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setCredError(null);
    try {
      const result = await login(username, password);
      navigate(result?.mustChangePassword ? '/change-password' : '/wiki/Main');
    } catch {
      setCredError('Invalid credentials');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div data-testid="login-page" style={{ display: 'flex', justifyContent: 'center', padding: 'var(--space-xl)' }}>
      <div style={{ maxWidth: '380px', width: '100%' }}>
        {ssoMessage && (
          <div className="error-banner" data-testid="sso-error" style={{ marginBottom: 'var(--space-md)' }}>
            {ssoMessage}
          </div>
        )}

        <form onSubmit={handleSubmit} data-testid="login-form" style={{ padding: 'var(--space-xl)', border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)' }}>
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
              Sign in to Wikantik
            </h2>
            <div style={{
              fontFamily: 'var(--font-ui)',
              fontSize: '0.8rem',
              color: 'var(--text-muted)',
              marginTop: 'var(--space-xs)',
            }}>
              wiki.wikantik.com
            </div>
          </div>

          <SsoLoginButton />

          {credError && (
            <div className="error-banner" data-testid="login-error" style={{ marginBottom: 'var(--space-md)' }}>
              {credError}
            </div>
          )}

          <div style={{ marginBottom: 'var(--space-md)' }}>
            <label style={{
              display: 'block',
              fontSize: '0.8rem',
              fontWeight: 500,
              color: 'var(--text-muted)',
              marginBottom: 'var(--space-xs)',
            }}>Username</label>
            <input
              type="text"
              name="username"
              data-testid="login-username"
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
              marginBottom: 'var(--space-xs)',
            }}>Password</label>
            <input
              type="password"
              name="password"
              data-testid="login-password"
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

          <button
            type="submit"
            className="btn btn-primary"
            data-testid="login-submit"
            disabled={loading}
            style={{ width: '100%', justifyContent: 'center', padding: 'var(--space-sm) var(--space-lg)' }}
          >
            {loading ? 'Signing in...' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  );
}
