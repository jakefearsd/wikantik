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
    links: [
      { to: '/admin/content', label: 'Content & Index' },
      { to: '/admin/page-ownership', label: 'Page Ownership' },
    ],
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

// Derive a stable testid slug from a nav `to` path:
//   "/admin"           → "overview"
//   "/admin/users"     → "users"
//   "/admin/kg-policy" → "kg-policy"
function navSlug(to) {
  return to === '/admin' ? 'overview' : to.replace(/^\/admin\//, '');
}

export default function AdminSidebar() {
  return (
    <aside className="app-sidebar admin-sidebar" data-testid="admin-sidebar">
      <Link to="/wiki/Main" className="admin-sidebar-back" data-testid="admin-back-to-wiki">← Back to wiki</Link>
      <h1 className="admin-sidebar-title">Administration</h1>
      <nav className="admin-sidebar-nav">
        {/* `end` so /admin matches Overview exactly, not every /admin/* child */}
        <NavLink to="/admin" end className={linkClass} data-testid={`admin-nav-${navSlug('/admin')}`}>Overview</NavLink>
        {GROUPS.map((group) => (
          <div className="admin-sidebar-group" key={group.title}>
            <div className="admin-sidebar-group-title">{group.title}</div>
            {group.links.map((l) => (
              <NavLink key={l.to} to={l.to} className={linkClass} data-testid={`admin-nav-${navSlug(l.to)}`}>{l.label}</NavLink>
            ))}
          </div>
        ))}
      </nav>
    </aside>
  );
}
