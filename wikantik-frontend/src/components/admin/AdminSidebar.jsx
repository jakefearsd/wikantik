// AdminSidebar.jsx
import { NavLink, Link } from 'react-router-dom';
import '../../styles/admin.css';

// Grouped admin navigation. Rendered by App.jsx in the rail slot in place of the
// reader Sidebar while on /admin/* (the "context swap"). The reader sidebar is
// untouched on wiki routes; "← Back to wiki" is the door out.
const GROUPS = [
  {
    title: 'People & Access',
    links: [
      { to: '/admin/users', label: 'Users' },
      { to: '/admin/security', label: 'Security' },
      { to: '/admin/apikeys', label: 'API Keys' },
    ],
  },
  {
    title: 'Content',
    links: [{ to: '/admin/content', label: 'Content & Index' }],
  },
  {
    title: 'Knowledge & Search',
    links: [
      { to: '/admin/knowledge-graph', label: 'Knowledge Graph' },
      { to: '/admin/kg-policy', label: 'KG Policy' },
      { to: '/admin/retrieval-quality', label: 'Retrieval Quality' },
    ],
  },
];

const linkClass = ({ isActive }) => `admin-sidebar-link${isActive ? ' active' : ''}`;

export default function AdminSidebar() {
  return (
    <aside className="app-sidebar admin-sidebar">
      <Link to="/wiki/Main" className="admin-sidebar-back">← Back to wiki</Link>
      <h1 className="admin-sidebar-title">Administration</h1>
      <nav className="admin-sidebar-nav">
        {/* `end` so /admin matches Overview exactly, not every /admin/* child */}
        <NavLink to="/admin" end className={linkClass}>Overview</NavLink>
        {GROUPS.map((group) => (
          <div className="admin-sidebar-group" key={group.title}>
            <div className="admin-sidebar-group-title">{group.title}</div>
            {group.links.map((l) => (
              <NavLink key={l.to} to={l.to} className={linkClass}>{l.label}</NavLink>
            ))}
          </div>
        ))}
      </nav>
    </aside>
  );
}
