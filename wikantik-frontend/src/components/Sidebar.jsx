import { useState, useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDarkMode } from '../hooks/useDarkMode';
import SearchOverlay from './SearchOverlay';
import UserBadge from './UserBadge';
import NewArticleModal from './NewArticleModal';

export default function Sidebar({ collapsed, onToggle, mobileOpen = false, onMobileClose = () => {}, onMobileOpen = () => {} }) {
  const { name: activePage } = useParams();
  const [pages, setPages] = useState([]);
  const [recentChanges, setRecentChanges] = useState([]);
  const [searchOpen, setSearchOpen] = useState(false);
  const [newArticleOpen, setNewArticleOpen] = useState(false);
  const [dark, toggleDark] = useDarkMode();
  const { user } = useAuth();

  useEffect(() => {
    api.listPages({ limit: 500 }).then(d => setPages(d.pages || [])).catch(() => {});
    api.getRecentChanges(10).then(d => setRecentChanges(d.changes || [])).catch(() => {});
  }, []);

  // Cmd+K / Ctrl+K to open search
  useEffect(() => {
    const handler = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setSearchOpen(true);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  // Group pages by cluster
  const clusters = {};
  pages.forEach(p => {
    const cluster = p.cluster || p.metadata?.cluster;
    if (cluster) {
      (clusters[cluster] = clusters[cluster] || []).push(p);
    }
  });

  const existingPageNames = new Set(pages.map(p => p.name));
  const existingClusters = Object.keys(clusters).sort();

  const navLink = (to, label) => (
    <Link
      to={to}
      className={`sidebar-link ${activePage === to.replace('/wiki/', '') ? 'active' : ''}`}
      onClick={onMobileClose}
    >
      {label}
    </Link>
  );

  return (
    <>
      <aside className={`app-sidebar ${collapsed ? 'collapsed' : ''} ${mobileOpen ? 'open' : ''}`}>
        <div className="sidebar-tab-handle" onClick={onMobileOpen} aria-label="Open navigation">
          ☰
        </div>
        <div className="sidebar-brand">
          <Link to="/wiki/Main" style={{ color: 'inherit', textDecoration: 'none' }} onClick={onMobileClose}>
            Wik<span>antik</span>
          </Link>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', margin: 'var(--space-md) 0' }}>
          <UserBadge />
          <button className="theme-toggle" onClick={toggleDark} title={dark ? 'Light mode' : 'Dark mode'}>
            {dark ? '☀️' : '🌙'}
          </button>
        </div>

        <button className="search-trigger" onClick={() => setSearchOpen(true)}>
          <span>🔍</span> Search…
          <kbd>⌘K</kbd>
        </button>

        {user?.authenticated && (
          <button
            className="btn btn-primary"
            onClick={() => setNewArticleOpen(true)}
            style={{ width: '100%', margin: 'var(--space-sm) 0', justifyContent: 'center' }}
          >
            + New Article
          </button>
        )}

        {/* Primary Navigation — matches JSP LeftMenu */}
        <div className="sidebar-section">
          <div className="sidebar-section-title">Navigation</div>
          {navLink('/wiki/Main', 'Main page')}
          {navLink('/wiki/About', 'About')}
          {navLink('/wiki/News', 'News')}
          {navLink('/wiki/RecentChanges', 'Recent Changes')}
        </div>

        <div className="sidebar-section">
          <div className="sidebar-section-title">Wiki Tools</div>
          {navLink('/wiki/PageIndex', 'Page Index')}
          {navLink('/wiki/UnusedPages', 'Unused pages')}
          {navLink('/wiki/UndefinedPages', 'Undefined pages')}
          {navLink('/wiki/SystemInfo', 'System Info')}
        </div>

        {/* Recent Changes — live feed */}
        {recentChanges.length > 0 && (
          <div className="sidebar-section">
            <div className="sidebar-section-title">Recently Modified</div>
            {recentChanges.slice(0, 8).map(c => (
              <Link
                key={c.name}
                to={`/wiki/${c.name}`}
                className={`sidebar-link ${activePage === c.name ? 'active' : ''}`}
                onClick={onMobileClose}
              >
                {c.name}
              </Link>
            ))}
          </div>
        )}

        {/* Admin — only for admin users */}
        {user?.authenticated && user?.roles?.includes('Admin') && (
          <div className="sidebar-section">
            <div className="sidebar-section-title">Admin</div>
            <Link
              to="/admin/users"
              className="sidebar-link"
              onClick={onMobileClose}
            >
              User Management
            </Link>
            <Link
              to="/admin/content"
              className="sidebar-link"
              onClick={onMobileClose}
            >
              Content Management
            </Link>
            <Link
              to="/admin/security"
              className="sidebar-link"
              onClick={onMobileClose}
            >
              Security
            </Link>
          </div>
        )}

        {/* Clusters */}
        {Object.keys(clusters).sort().map(cluster => (
          <div key={cluster} className="sidebar-section">
            <div className="sidebar-section-title">{cluster}</div>
            {clusters[cluster].sort((a, b) => a.name.localeCompare(b.name)).map(p => (
              <Link
                key={p.name}
                to={`/wiki/${p.name}`}
                className={`sidebar-link ${activePage === p.name ? 'active' : ''}`}
                onClick={onMobileClose}
              >
                {p.name}
              </Link>
            ))}
          </div>
        ))}

      </aside>

      {searchOpen && <SearchOverlay onClose={() => setSearchOpen(false)} />}
      {newArticleOpen && (
        <NewArticleModal
          isOpen={newArticleOpen}
          onClose={() => setNewArticleOpen(false)}
          existingPageNames={existingPageNames}
          existingClusters={existingClusters}
        />
      )}
    </>
  );
}
