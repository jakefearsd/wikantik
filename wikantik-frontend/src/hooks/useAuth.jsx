import { useState, useEffect, createContext, useContext } from 'react';
import { api } from '../api/client';

const AuthContext = createContext(null);

// SSO availability is static server configuration, not per-session state, so we
// cache it. This keeps the "Continue with <provider>" button stable: it renders
// immediately on load (seeded from cache, no first-probe race) and never
// disappears when the /api/auth/user probe is in flight or fails transiently.
const SSO_CACHE_KEY = 'wikantik.sso';

function readCachedSso() {
  try {
    const raw = window.localStorage.getItem(SSO_CACHE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function writeCachedSso(sso) {
  try {
    window.localStorage.setItem(SSO_CACHE_KEY, JSON.stringify(sso));
  } catch {
    /* localStorage unavailable or over quota — non-fatal, button just won't pre-seed */
  }
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [sso, setSso] = useState(readCachedSso);

  const refresh = async () => {
    try {
      const data = await api.getUser();
      setUser(data);
      // Update the sticky SSO config from the probe when present; never clear it
      // on a response that happens to omit it.
      if (data && data.sso) {
        setSso(data.sso);
        writeCachedSso(data.sso);
      }
    } catch {
      // A failed probe means an unknown session, not "SSO is gone" — leave the
      // cached `sso` in place so the login button stays put.
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
    const result = await api.login(username, password);
    await refresh();
    return result;
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
    <AuthContext.Provider value={{ user, loading, sso, login, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
