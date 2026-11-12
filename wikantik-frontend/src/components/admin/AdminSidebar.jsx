// AdminSidebar.jsx
import { NavLink, Link } from 'react-router-dom';
import '../../styles/admin.css';
import { HOME_PATH } from '../../utils/homePage';

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
      { to: '/admin/connectors', label: 'Connectors' },
    ],
  },
  {
    title: 'Knowledge & Search',
    links: [
      { to: '/admin/embeddings', label: 'Embeddings' },
      { to: '/admin/retrieval-quality', label: 'Retrieval Quality' },
      { to: '/admin/drift', label: 'Drift' },
      { to: '/admin/insights', label: 'Search Visibility' },
    ],
  },
  {
    title: 'Observability',
    links: [
      { to: '/admin/audit', label: 'Audit' },
    ],
  },
];

const linkClass = ({ isActive }) => `admin-sidebar-link${isActive ? ' active' : ''}`;

// Derive a stable testid slug from a nav `to` path:
//   "/admin"           → "overview"
//   "/admin/users"     → "users"
//   "/admin/page-ownership" → "page-ownership"
function navSlug(to) {
  return to === '/admin' ? 'overview' : to.replace(/^\/admin\//, '');
}

export default function AdminSidebar() {
  return (
    <aside className="app-sidebar admin-sidebar" data-testid="admin-sidebar">
      <Link to={HOME_PATH} className="admin-sidebar-back" data-testid="admin-back-to-wiki">← Back to wiki</Link>
      <h2 className="admin-sidebar-title">Administration</h2>
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
