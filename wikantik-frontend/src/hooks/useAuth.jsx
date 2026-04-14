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
