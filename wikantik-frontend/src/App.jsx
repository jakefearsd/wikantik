import { useState, useEffect, useCallback } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import Sidebar from './components/Sidebar';
import { useAuth } from './hooks/useAuth';

export default function App() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const { user } = useAuth();
  const location = useLocation();
  const isEditorRoute = location.pathname.startsWith('/edit/');
  const isGraphRoute = location.pathname === '/graph';

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
    <div className="app-layout">
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
      <Sidebar
        collapsed={sidebarCollapsed}
        onToggle={() => setSidebarCollapsed(c => !c)}
        mobileOpen={mobileOpen}
        onMobileClose={() => setMobileOpen(false)}
        onMobileOpen={() => setMobileOpen(true)}
      />
      <main className={`app-main ${sidebarCollapsed ? 'expanded' : ''}`}>
        <div className={`app-content${isEditorRoute ? ' app-content-wide' : ''}${isGraphRoute ? ' app-content-full' : ''}`}>
          <Outlet />
        </div>
      </main>
    </div>
  );
}
