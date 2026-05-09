import { useState, useEffect, createContext, useContext } from 'react';
import { api } from '../api/client';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  const refresh = async () => {
    try {
      const data = await api.getUser();
      setUser(data);
    } catch {
      setUser({ authenticated: false, username: 'anonymous' });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  // When a request returns 401/403, /api/auth/user gets re-queried so
  // RequireAuth can route to login. Debounced via `pending` so a burst of
  // failing /admin/* fetches doesn't spawn N parallel auth probes.
  useEffect(() => {
    let pending = false;
    const onAuthRequired = () => {
      if (pending) return;
      pending = true;
      Promise.resolve(refresh()).finally(() => { pending = false; });
    };
    window.addEventListener('wikantik:auth-required', onAuthRequired);
    return () => window.removeEventListener('wikantik:auth-required', onAuthRequired);
  }, []);

  const login = async (username, password) => {
    await api.login(username, password);
    await refresh();
  };

  const logout = async () => {
    // Flip local state to anonymous unconditionally. The POST invalidates
    // the server session; we don't depend on its response completing, and
    // we don't rely on refresh() running, because either can be dropped
    // (promise rejection, browser tab throttling, connection reset) and
    // leave the UI stuck on an authenticated badge pointing at a dead
    // session.
    try {
      await api.logout();
    } finally {
      setUser({ authenticated: false, username: 'anonymous', roles: [] });
    }
  };

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
