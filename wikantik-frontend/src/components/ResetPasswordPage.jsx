import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';

export default function ResetPasswordPage() {
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);

    if (!email.trim()) {
      setError('Please enter your email address');
      return;
    }

    setLoading(true);
    try {
      await api.resetPassword(email);
      setSubmitted(true);
    } catch (err) {
      setError(err.body?.message || err.message || 'An error occurred. Please try again.');
    } finally {
      setLoading(false);
    }
  };

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

  return (
    <div style={{ maxWidth: '420px', margin: '0 auto', padding: 'var(--space-2xl) var(--space-xl)' }}>
      <h1 style={{
        fontFamily: 'var(--font-display)',
        fontSize: '1.8rem',
        fontWeight: 700,
        marginBottom: 'var(--space-xs)',
        textAlign: 'center',
        color: 'var(--text)',
      }}>
        Reset Password
      </h1>
      <p style={{
        color: 'var(--text-muted)',
        fontSize: '0.9rem',
        textAlign: 'center',
        marginBottom: 'var(--space-xl)',
        fontFamily: 'var(--font-ui)',
      }}>
        Enter the email address associated with your account and we will send you a new password.
      </p>

      {submitted ? (
        <div style={{
          background: 'var(--sage-light)',
          border: '1px solid var(--sage)',
          color: 'var(--sage)',
          padding: 'var(--space-lg)',
          borderRadius: 'var(--radius-lg)',
          fontSize: '0.9rem',
          textAlign: 'center',
          lineHeight: 1.6,
        }}>
          <p style={{ fontWeight: 600, marginBottom: 'var(--space-sm)' }}>
            Check your email
          </p>
          <p>
            If an account exists with that email, a new password has been sent.
            Please check your inbox and use the new password to sign in.
          </p>
          <Link
            to="/wiki/Main"
            style={{
              display: 'inline-block',
              marginTop: 'var(--space-md)',
              color: 'var(--accent)',
              textDecoration: 'none',
              fontWeight: 500,
              fontSize: '0.85rem',
            }}
          >
            Return to wiki
          </Link>
        </div>
      ) : (
        <form onSubmit={handleSubmit}>
          {error && (
            <div className="error-banner" style={{ marginBottom: 'var(--space-md)' }}>
              {error}
            </div>
          )}

          <div style={{ marginBottom: 'var(--space-lg)' }}>
            <label style={labelStyle}>Email Address</label>
            <input
              type="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="you@example.com"
              autoFocus
              style={inputStyle}
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            disabled={loading}
            style={{
              width: '100%',
              justifyContent: 'center',
              padding: 'var(--space-sm) var(--space-lg)',
              marginBottom: 'var(--space-md)',
            }}
          >
            {loading ? 'Sending...' : 'Send New Password'}
          </button>

          <div style={{ textAlign: 'center' }}>
            <Link
              to="/wiki/Main"
              style={{
                color: 'var(--text-muted)',
                textDecoration: 'none',
                fontSize: '0.85rem',
              }}
            >
              Back to wiki
            </Link>
          </div>
        </form>
      )}
    </div>
  );
}
