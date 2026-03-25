import { useState, useEffect } from 'react';
import { Outlet } from 'react-router-dom';
import Sidebar from './components/Sidebar';
import { useAuth } from './hooks/useAuth';

export default function App() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const { user } = useAuth();

  // Close mobile sidebar when user successfully authenticates
  useEffect(() => {
    if (user?.authenticated) setMobileOpen(false);
  }, [user?.authenticated]);

  return (
    <div className="app-layout">
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
        <div className="app-content">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
