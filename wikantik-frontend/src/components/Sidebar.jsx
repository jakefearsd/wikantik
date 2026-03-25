import { useState, useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDarkMode } from '../hooks/useDarkMode';
import SearchOverlay from './SearchOverlay';
import UserBadge from './UserBadge';

export default function Sidebar({ collapsed, onToggle, mobileOpen = false, onMobileClose = () => {}, onMobileOpen = () => {} }) {
  const { name: activePage } = useParams();
  const [pages, setPages] = useState([]);
  const [recentChanges, setRecentChanges] = useState([]);
  const [searchOpen, setSearchOpen] = useState(false);
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
  const unclustered = [];
  pages.forEach(p => {
    const cluster = p.cluster || p.metadata?.cluster;
    if (cluster) {
      (clusters[cluster] = clusters[cluster] || []).push(p);
    } else {
      unclustered.push(p);
    }
  });

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

        <button className="search-trigger" onClick={() => setSearchOpen(true)}>
          <span>🔍</span> Search…
          <kbd>⌘K</kbd>
        </button>

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

        {/* Unclustered pages */}
        {unclustered.length > 0 && (
          <div className="sidebar-section">
            <div className="sidebar-section-title">Pages</div>
            {unclustered.sort((a, b) => a.name.localeCompare(b.name)).slice(0, 30).map(p => (
              <Link
                key={p.name}
                to={`/wiki/${p.name}`}
                className={`sidebar-link ${activePage === p.name ? 'active' : ''}`}
                onClick={onMobileClose}
              >
                {p.name}
              </Link>
            ))}
            {unclustered.length > 30 && (
              <span className="sidebar-link" style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>
                +{unclustered.length - 30} more…
              </span>
            )}
          </div>
        )}

        {/* Footer controls */}
        <div className="sidebar-controls">
          <UserBadge />
          <div style={{ marginLeft: 'auto' }}>
            <button className="theme-toggle" onClick={toggleDark} title={dark ? 'Light mode' : 'Dark mode'}>
              {dark ? '☀️' : '🌙'}
            </button>
          </div>
        </div>
      </aside>

      {searchOpen && <SearchOverlay onClose={() => setSearchOpen(false)} />}
    </>
  );
}
