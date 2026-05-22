// AdminLayout.jsx
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';

// Content shell for the admin area. Navigation now lives in AdminSidebar (rendered
// by App.jsx in the rail slot); this component only gates on the Admin role and
// renders the routed child.
export default function AdminLayout() {
  const { user, loading } = useAuth();
  if (loading) return null;

  const isAdmin = user?.authenticated && user?.roles?.includes('Admin');
  if (!isAdmin) return <Navigate to="/wiki/Main" replace />;

  return (
    <div className="admin-content">
      <Outlet />
    </div>
  );
}
