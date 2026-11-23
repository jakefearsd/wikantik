import { useState, useEffect, useMemo } from 'react';
import ClusterTree from './ClusterTree';
import { BLOG_URL } from '../utils/siteLinks';
import { Link, useLocation, useParams } from 'react-router-dom';
import { HOME_PAGE, HOME_PATH } from '../utils/homePage';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useDarkMode } from '../hooks/useDarkMode';
import { useWorkUnread } from '../hooks/useWorkUnread';
import PersonalZone from './PersonalZone';
import UserBadge from './UserBadge';
import { useNewPage } from '../newpage/NewPageProvider';
import ExportDialog from './ExportDialog';
import ImportDialog from './ImportDialog';
import { useRegisterCommands } from '../commands/useCommands';
import CollapsibleSection from './CollapsibleSection';
import Icon from './ui/Icon';

export default function Sidebar({ collapsed, mobileOpen = false, onMobileClose = () => {}, onMobileOpen = () => {}, onOpenSearch = () => {} }) {
  const { name: routeName } = useParams();
  // The front page is served at "/", which carries no :name param.
  const { pathname } = useLocation();
  const activePage = routeName ?? (pathname === HOME_PATH ? HOME_PAGE : undefined);
  const [titles, setTitles] = useState({});
  const [recentChanges, setRecentChanges] = useState([]);
  const [recentChangesError, setRecentChangesError] = useState(false);
  const { openNewPage } = useNewPage();
  const [exportOpen, setExportOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [showAllRecent, setShowAllRecent] = useState(false);
  const [dark, toggleDark] = useDarkMode();
  const { user } = useAuth();
  const { unread, visible: showWork } = useWorkUnread();
  const importCommands = useMemo(() => (user?.canCreatePages
    ? [{ id: 'import-vault', title: 'Import Obsidian vault…', section: 'Page', run: () => setImportOpen(true) }]
    : []), [user?.canCreatePages]);
  useRegisterCommands(importCommands, [importCommands]);
  useEffect(() => {
    api.getRecentChanges(20).then(d => {
      const changes = d.changes || [];
      setRecentChanges(changes);
      if (changes.length === 0) return undefined;
      // The change feed carries page names only; readers are shown titles.
      return api.listPages({ names: changes.map(c => c.name), limit: changes.length })
        .then(t => setTitles(Object.fromEntries((t.pages || []).map(p => [p.name, p.title]))))
        .catch((e) => console.warn('Sidebar: failed to load recent page titles', e));
    }).catch((e) => {
      console.warn('Sidebar: failed to load recent changes', e);
      setRecentChangesError(true);
    });
  }, []);

  const navLink = (to, label) => {
    const isActive = activePage === (to === HOME_PATH ? HOME_PAGE : to.replace('/wiki/', ''));
    return (
      <Link
        to={to}
        className={`sidebar-link ${isActive ? 'active' : ''}`}
        onClick={onMobileClose}
        {...(isActive ? { 'aria-current': 'page' } : {})}
      >
        {label}
      </Link>
    );
  };

  return (
    <>
      <aside className={`app-sidebar ${collapsed ? 'collapsed' : ''} ${mobileOpen ? 'open' : ''}`}>
        <button type="button" className="sidebar-tab-handle" onClick={onMobileOpen} aria-label="Open navigation">
          <Icon name="chevron" size={18} />
        </button>
        <div className="sidebar-brand">
          <Link to={HOME_PATH} style={{ color: 'inherit', textDecoration: 'none' }} onClick={onMobileClose}>
            Wik<span>antik</span>
          </Link>
        </div>

        <div className="sidebar-user-row">
          <UserBadge />
          <button
            className="theme-toggle"
            onClick={toggleDark}
            title={dark ? 'Light mode' : 'Dark mode'}
            aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
          >
            <Icon name={dark ? 'sun' : 'moon'} className="theme-toggle-icon" size={18} />
          </button>
        </div>

        <button
          className="search-trigger"
          data-testid="sidebar-search-trigger"
          onClick={onOpenSearch}
        >
          <Icon name="search" /> Search…
          <kbd>⌘K</kbd>
        </button>

        <PersonalZone
          onMobileClose={onMobileClose}
          onNewArticle={() => openNewPage()}
          onExport={() => setExportOpen(true)}
          onImport={() => setImportOpen(true)}
        />

        {/* Primary Navigation — matches JSP LeftMenu */}
        <div className="sidebar-section">
          <div className="sidebar-section-title">Navigation</div>
          {navLink(HOME_PATH, 'Main page')}
          {navLink('/wiki/About', 'About')}
          {showWork && (
            <Link to="/work" className={`sidebar-link ${pathname === '/work' || pathname.startsWith('/work/') ? 'active' : ''}`}
              onClick={onMobileClose}>
              Work
              {unread > 0 && (
                <span className="work-unread-badge" aria-label={`${unread} unread notifications`}>{unread}</span>
              )}
            </Link>
          )}
          {navLink('/wiki/RecentChanges', 'Recent Changes')}
          <a href={BLOG_URL} className="sidebar-link" onClick={onMobileClose}>Blog</a>
        </div>

        <div className="sidebar-section">
          <div className="sidebar-section-title">Wiki Tools</div>
          {navLink('/wiki/PageIndex', 'Page Index')}
          {navLink('/wiki/SystemInfo', 'System Info')}
          <Link
            to={activePage ? `/page-graph?focus=${encodeURIComponent(activePage)}` : '/page-graph'}
            className="sidebar-link"
            onClick={onMobileClose}
          >
            Page Graph
          </Link>
        </div>

        {recentChangesError && recentChanges.length === 0 && (
          <div className="sidebar-section">
            <div className="personal-empty">Couldn't load recent changes.</div>
          </div>
        )}

        {/* Recent Changes — live feed, capped at 5 until expanded */}
        {recentChanges.length > 0 && (
          <div className="sidebar-section">
            <div className="sidebar-section-title">Recently Modified</div>
            {(showAllRecent ? recentChanges : recentChanges.slice(0, 5)).map(c => {
              const isActive = activePage === c.name;
              return (
                <Link
                  key={c.name}
                  to={`/wiki/${c.name}`}
                  className={`sidebar-link ${isActive ? 'active' : ''}`}
                  onClick={onMobileClose}
                  {...(isActive ? { 'aria-current': 'page' } : {})}
                >
                  {titles[c.name] || c.name}
                </Link>
              );
            })}
            {!showAllRecent && recentChanges.length > 5 && (
              <button
                type="button"
                className="personal-viewall"
                onClick={() => setShowAllRecent(true)}
              >
                Show all {recentChanges.length}
              </button>
            )}
          </div>
        )}

        {/* Admin — only for admin users */}
        {user?.authenticated && user?.roles?.includes('Admin') && (
          <div className="sidebar-section">
            <div className="sidebar-section-title">Admin</div>
            <Link
              to="/admin"
              className="sidebar-link"
              onClick={onMobileClose}
            >
              Overview
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
            {navLink('/wiki/UnusedPages', 'Unused pages')}
            {navLink('/wiki/UndefinedPages', 'Undefined pages')}
          </div>
        )}

        {/* Clusters — one collapsed section. Its tree mounts, and so fetches, only once it is opened. */}
        <CollapsibleSection
          id="clusters-root"
          title="Browse Clusters"
          headerClassName="personal-section-header--title"
          defaultOpen={false}
        >
          <ClusterTree activePage={activePage} onNavigate={onMobileClose} />
        </CollapsibleSection>

      </aside>

      <ExportDialog isOpen={exportOpen} onClose={() => setExportOpen(false)} />
      <ImportDialog isOpen={importOpen} onClose={() => setImportOpen(false)} />
    </>
  );
}
