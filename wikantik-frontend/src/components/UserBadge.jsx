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
        <div data-testid="user-badge" data-authenticated="false">
          <button
            className="btn btn-ghost"
            data-testid="user-badge-signin"
            onClick={() => setShowLogin(true)}
            style={{ fontSize: '0.8rem' }}
          >
            Sign in
          </button>
        </div>
        {showLogin && <LoginForm onClose={() => setShowLogin(false)} />}
      </>
    );
  }

  return (
    <div
      data-testid="user-badge"
      data-authenticated="true"
      data-username={user.username}
      data-login-name={user.loginPrincipal || user.username}
      className="user-badge-identity"
    >
      <Link
        to="/preferences"
        data-testid="user-badge-name"
        className="user-badge-name"
        title="Preferences"
      >
        {user.username}
      </Link>
      <button
        className="btn btn-ghost user-badge-logout"
        data-testid="user-badge-logout"
        onClick={logout}
      >
        Logout
      </button>
    </div>
  );
}
