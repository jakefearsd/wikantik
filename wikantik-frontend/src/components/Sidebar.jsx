import { useState, useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useCapabilities } from '../hooks/useCapabilities';
import { useDarkMode } from '../hooks/useDarkMode';
import PersonalZone from './PersonalZone';
import UserBadge from './UserBadge';
import NewArticleModal from './NewArticleModal';
import CollapsibleSection from './CollapsibleSection';
import Icon from './ui/Icon';

// Pages that already have a dedicated link in the Navigation / Wiki Tools /
// Admin sections. They are excluded from the "Uncategorized" bucket so a
// clusterless system page isn't listed twice.
const KNOWN_NAV_PAGES = new Set([
  'Main', 'About', 'News', 'RecentChanges', 'PageIndex', 'SystemInfo',
  'UnusedPages', 'UndefinedPages',
]);

export default function Sidebar({ collapsed, onToggle, mobileOpen = false, onMobileClose = () => {}, onMobileOpen = () => {}, onOpenSearch = () => {} }) {
  const { name: activePage } = useParams();
  const [pages, setPages] = useState([]);
  const [recentChanges, setRecentChanges] = useState([]);
  const [newArticleOpen, setNewArticleOpen] = useState(false);
  const [showAllRecent, setShowAllRecent] = useState(false);
  const [dark, toggleDark] = useDarkMode();
  const { user } = useAuth();
  const { capabilities } = useCapabilities();
  useEffect(() => {
    api.listPages({ limit: 500 }).then(d => setPages(d.pages || [])).catch(() => {});
    api.getRecentChanges(20).then(d => setRecentChanges(d.changes || [])).catch(() => {});
  }, []);

  // Group pages by cluster; clusterless pages (minus system nav pages) fall
  // into an "Uncategorized" bucket.
  const clusters = {};
  const uncategorized = [];
  pages.forEach(p => {
    const cluster = p.cluster || p.metadata?.cluster;
    if (cluster) {
      (clusters[cluster] = clusters[cluster] || []).push(p);
    } else if (!KNOWN_NAV_PAGES.has(p.name)) {
      uncategorized.push(p);
    }
  });

  const existingPageNames = new Set(pages.map(p => p.name));
  const existingClusters = Object.keys(clusters).sort();

  // The cluster (or the Uncategorized bucket) holding the active page opens by
  // default so a reader always sees where the current page sits in the tree.
  const isActiveCluster = (clusterPages) => clusterPages.some(p => p.name === activePage);

  const clusterLinks = (clusterPages) =>
    clusterPages
      .slice()
      .sort((a, b) => a.name.localeCompare(b.name))
      .map(p => (
        <Link
          key={p.name}
          to={`/wiki/${p.name}`}
          className={`sidebar-link ${activePage === p.name ? 'active' : ''}`}
          onClick={onMobileClose}
          {...(activePage === p.name ? { 'aria-current': 'page' } : {})}
        >
          {p.name}
          {p.derived && (
            <span className="derived-badge" title="Synced from an external source" aria-label="Synced from an external source">↯</span>
          )}
        </Link>
      ));

  const navLink = (to, label) => {
    const isActive = activePage === to.replace('/wiki/', '');
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
        <div className="sidebar-tab-handle" onClick={onMobileOpen} aria-label="Open navigation">
          <Icon name="chevron" size={18} />
        </div>
        <div className="sidebar-brand">
          <Link to="/wiki/Main" style={{ color: 'inherit', textDecoration: 'none' }} onClick={onMobileClose}>
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
          onNewArticle={() => setNewArticleOpen(true)}
        />

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
          {navLink('/wiki/SystemInfo', 'System Info')}
          <Link
            to={activePage ? `/page-graph?focus=${encodeURIComponent(activePage)}` : '/page-graph'}
            className="sidebar-link"
            onClick={onMobileClose}
          >
            Page Graph
          </Link>
          {capabilities.knowledgeGraph && (
            <Link
              to="/knowledge-graph"
              className="sidebar-link"
              onClick={onMobileClose}
            >
              Knowledge Graph
            </Link>
          )}
        </div>

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
                  {c.name}
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
            {capabilities.knowledgeGraph && (
              <Link
                to="/admin/knowledge-graph"
                className="sidebar-link"
                onClick={onMobileClose}
              >
                Knowledge Graph
              </Link>
            )}
            {navLink('/wiki/UnusedPages', 'Unused pages')}
            {navLink('/wiki/UndefinedPages', 'Undefined pages')}
          </div>
        )}

        {/* Clusters — one top-level collapsible wrapper (collapsed by default so it
            stays a single line) holding the per-cluster collapsible tree. The active
            page's cluster still auto-expands inside, so opening this lands on it. */}
        {(existingClusters.length > 0 || uncategorized.length > 0) && (
          <CollapsibleSection
            id="clusters-root"
            title="Browse Clusters"
            headerClassName="personal-section-header--title"
            count={existingClusters.length + (uncategorized.length > 0 ? 1 : 0)}
            defaultOpen={false}
          >
            {existingClusters.map(cluster => (
              <CollapsibleSection
                key={cluster}
                id={`cluster-${cluster}`}
                title={cluster}
                count={clusters[cluster].length}
                defaultOpen={isActiveCluster(clusters[cluster])}
              >
                {clusterLinks(clusters[cluster])}
              </CollapsibleSection>
            ))}

            {/* Clusterless pages */}
            {uncategorized.length > 0 && (
              <CollapsibleSection
                id="cluster-__uncategorized__"
                title="Uncategorized"
                count={uncategorized.length}
                defaultOpen={isActiveCluster(uncategorized)}
              >
                {clusterLinks(uncategorized)}
              </CollapsibleSection>
            )}
          </CollapsibleSection>
        )}

      </aside>

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
