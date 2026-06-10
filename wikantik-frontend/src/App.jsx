import { useState, useEffect, useCallback } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import Sidebar from './components/Sidebar';
import AdminSidebar from './components/admin/AdminSidebar';
import { useAuth } from './hooks/useAuth';
import { ToastProvider } from './components/ui/ToastProvider';
import SearchOverlay from './components/SearchOverlay';
import { useGlobalHotkeys } from './hooks/useGlobalHotkeys';

/* global __APP_VERSION__ */
// Semantic version baked in at build time (Maven project.version → vite define).
// Defensive typeof guard for any context where the define isn't applied.
const APP_VERSION = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : 'dev';
// Prefix "v" only for real release/snapshot versions (e.g. 2.0.11), not for "dev".
const APP_VERSION_LABEL = /^\d/.test(APP_VERSION) ? `v${APP_VERSION}` : APP_VERSION;

export default function App() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const { user } = useAuth();

  // Cmd/Ctrl+K opens search from any route (admin or wiki)
  const openSearch = useCallback(() => setSearchOpen(true), []);
  useGlobalHotkeys({ onSearch: openSearch });
  const location = useLocation();
  const navigate = useNavigate();
  const isEditorRoute = location.pathname.startsWith('/edit/');
  const isAdminRoute = location.pathname.startsWith('/admin');
  const isGraphRoute = location.pathname === '/page-graph' || location.pathname === '/knowledge-graph';

  // Close mobile sidebar when user successfully authenticates
  useEffect(() => {
    if (user?.authenticated) setMobileOpen(false);
  }, [user?.authenticated]);

  // Mid-session flag: if the server marks the account must-change-password
  // (e.g. after a 403 PASSWORD_CHANGE_REQUIRED fires wikantik:auth-required →
  // refresh()), redirect to the change-password form from any route.
  useEffect(() => {
    if (user?.authenticated && user.mustChangePassword
        && location.pathname !== '/change-password') {
      navigate('/change-password', { replace: true });
    }
  }, [user, location.pathname, navigate]);

  // Listen for server version mismatch
  useEffect(() => {
    function onVersionMismatch(e) {
      const dismissed = sessionStorage.getItem('__wikantik_dismissed_version');
      if (dismissed !== e.detail.serverVersion) {
        setUpdateAvailable(e.detail.serverVersion);
      }
    }
    window.addEventListener('wikantik:version-mismatch', onVersionMismatch);
    return () => window.removeEventListener('wikantik:version-mismatch', onVersionMismatch);
  }, []);

  const dismissUpdate = useCallback(() => {
    sessionStorage.setItem('__wikantik_dismissed_version', updateAvailable);
    setUpdateAvailable(false);
  }, [updateAvailable]);

  return (
    <ToastProvider>
    <div className="app-layout">
      <a href="#main-content" className="skip-link">Skip to content</a>
      {updateAvailable && (
        <div className="update-toast" role="status">
          <span>A new version is available.</span>
          <button onClick={() => window.location.reload()}>Reload</button>
          <button onClick={dismissUpdate} aria-label="Dismiss">&times;</button>
        </div>
      )}
      {mobileOpen && (
        <div className="sidebar-backdrop" onClick={() => setMobileOpen(false)} />
      )}
      {isAdminRoute ? (
        <AdminSidebar />
      ) : (
        <Sidebar
          collapsed={sidebarCollapsed}
          onToggle={() => setSidebarCollapsed(c => !c)}
          mobileOpen={mobileOpen}
          onMobileClose={() => setMobileOpen(false)}
          onMobileOpen={() => setMobileOpen(true)}
          onOpenSearch={openSearch}
        />
      )}
      <main className={`app-main ${sidebarCollapsed ? 'expanded' : ''}`}>
        <div id="main-content" className={`app-content${(isEditorRoute || isAdminRoute) ? ' app-content-wide' : ''}${isGraphRoute ? ' app-content-full' : ''}`}>
          <Outlet />
        </div>
        {!isAdminRoute && !isEditorRoute && (
          <footer className="site-footer">
            <a href="/privacy-policy.html">Privacy Policy</a>
            <span className="site-footer-sep" aria-hidden="true">·</span>
            <a href="/terms-of-service.html">Terms of Service</a>
            <span className="site-footer-sep" aria-hidden="true">·</span>
            <span className="site-footer-version" data-testid="app-version" title="Wikantik version">{APP_VERSION_LABEL}</span>
          </footer>
        )}
      </main>
    </div>
      {searchOpen && <SearchOverlay onClose={() => setSearchOpen(false)} />}
    </ToastProvider>
  );
}
