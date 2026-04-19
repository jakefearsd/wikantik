import { Navigate, Outlet, NavLink } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';

export default function AdminLayout() {
  const { user, loading } = useAuth();

  if (loading) return null;

  const isAdmin = user?.authenticated && user?.roles?.includes('Admin');
  if (!isAdmin) return <Navigate to="/wiki/Main" replace />;

  return (
    <div className="admin-layout">
      <div className="admin-header">
        <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '1.5rem', fontWeight: 600 }}>
          Administration
        </h1>
        <nav className="admin-nav">
          <NavLink to="/admin/users" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
            Users
          </NavLink>
          <NavLink to="/admin/content" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
            Content
          </NavLink>
          <NavLink to="/admin/security" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
            Security
          </NavLink>
          <NavLink to="/admin/knowledge" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
            Knowledge
          </NavLink>
          <NavLink to="/admin/apikeys" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
            API Keys
          </NavLink>
        </nav>
      </div>
      <Outlet />
    </div>
  );
}
