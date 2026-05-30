import { useState, useEffect, useCallback } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import Sidebar from './components/Sidebar';
import AdminSidebar from './components/admin/AdminSidebar';
import { useAuth } from './hooks/useAuth';
import { ToastProvider } from './components/ui/ToastProvider';
import SearchOverlay from './components/SearchOverlay';
import { useGlobalHotkeys } from './hooks/useGlobalHotkeys';

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
  const isEditorRoute = location.pathname.startsWith('/edit/');
  const isAdminRoute = location.pathname.startsWith('/admin');
  const isGraphRoute = location.pathname === '/page-graph' || location.pathname === '/knowledge-graph';

  // Close mobile sidebar when user successfully authenticates
  useEffect(() => {
    if (user?.authenticated) setMobileOpen(false);
  }, [user?.authenticated]);

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
          </footer>
        )}
      </main>
    </div>
      {searchOpen && <SearchOverlay onClose={() => setSearchOpen(false)} />}
    </ToastProvider>
  );
}
