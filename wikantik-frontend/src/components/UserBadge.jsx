import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import LoginForm from './LoginForm';

export default function UserBadge() {
  const { user, logout } = useAuth();
  const [showLogin, setShowLogin] = useState(false);

  if (!user) return null;

  if (!user.authenticated) {
    return (
      <>
        <button className="btn btn-ghost" onClick={() => setShowLogin(true)} style={{ fontSize: '0.8rem' }}>
          Sign in
        </button>
        {showLogin && <LoginForm onClose={() => setShowLogin(false)} />}
      </>
    );
  }

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', fontSize: '0.8rem' }}>
      <Link
        to="/preferences"
        style={{ color: 'var(--text-secondary)', fontWeight: 500, textDecoration: 'none' }}
        title="Preferences"
      >
        {user.username}
      </Link>
      <button className="btn btn-ghost" onClick={logout} style={{ fontSize: '0.75rem', padding: '2px 6px' }}>
        Logout
      </button>
    </div>
  );
}
