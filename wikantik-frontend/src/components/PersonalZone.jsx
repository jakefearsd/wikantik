import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { useUnreadMentions } from '../hooks/useUnreadMentions';
import { useMyPages } from '../hooks/useMyPages';
import { useMyBlog } from '../hooks/useMyBlog';
import { useRecentlyViewed } from '../hooks/useRecentlyViewed';
import { useDrafts } from '../hooks/useDrafts';
import CollapsibleSection from './CollapsibleSection';

const INLINE = 3;
const EXPANDED = 15;

function PreviewList({ items, render, emptyLabel }) {
  const [showAll, setShowAll] = useState(false);
  if (!items.length) return <div className="personal-empty">{emptyLabel}</div>;
  const visible = showAll ? items.slice(0, EXPANDED) : items.slice(0, INLINE);
  return (
    <>
      {visible.map(render)}
      {!showAll && items.length > INLINE && (
        <button type="button" className="personal-viewall" onClick={() => setShowAll(true)}>
          View all {items.length}
        </button>
      )}
    </>
  );
}

export default function PersonalZone({ onMobileClose = () => {}, onNewArticle = () => {} }) {
  const { user, logout } = useAuth();
  const authed = !!user?.authenticated;
  const login = authed ? user.loginPrincipal : null;

  const { count: mentions } = useUnreadMentions({ enabled: authed });
  const { pages } = useMyPages({ enabled: authed });
  const { entries } = useMyBlog({ login, enabled: authed });
  const { items: recent } = useRecentlyViewed({ login, enabled: authed });
  const { drafts, removeDraft } = useDrafts({ login, enabled: authed });

  if (!authed) return null;

  const initials = (user.username || '?').slice(0, 2).toUpperCase();

  return (
    <div className="personal-zone">
      <div className="personal-identity">
        <div className="personal-avatar" aria-hidden="true">{initials}</div>
        <div className="personal-identity-text">
          <Link to="/preferences" className="personal-name" onClick={onMobileClose}>{user.username}</Link>
          <div className="personal-sub">
            {user.roles?.includes('Admin') ? 'Admin · ' : ''}
            <Link to="/preferences" onClick={onMobileClose}>Profile</Link>
            {' · '}
            <button type="button" className="btn-link" onClick={logout}>Sign out</button>
          </div>
        </div>
      </div>

      <button
        className="btn btn-primary personal-new-article btn-block"
        onClick={onNewArticle}
      >
        + New Article
      </button>

      {/* Everything below the New Article control collapses under one toggle,
          default collapsed, so the personal zone stays out of the way until
          wanted. The unread-mentions badge is surfaced on the header so the
          notification signal survives while collapsed. */}
      <CollapsibleSection
        id="me-zone"
        icon="👤"
        title="Me"
        defaultOpen={false}
        badge={mentions > 0 ? <span className="sidebar-mentions-badge">{mentions}</span> : null}
      >
        <Link to="/me/mentions" className="sidebar-link personal-mentions" onClick={onMobileClose}>
          <span aria-hidden="true">🔔</span> My mentions
          {mentions > 0 && <span className="sidebar-mentions-badge">{mentions}</span>}
        </Link>

        <CollapsibleSection id="my-pages" icon="📄" title="My pages" count={pages.length}>
          <PreviewList
            items={pages}
            emptyLabel="No pages yet."
            render={(p) => (
              <Link key={p.slug} to={`/wiki/${p.slug}`} className="sidebar-link" onClick={onMobileClose}>
                {p.title}
              </Link>
            )}
          />
        </CollapsibleSection>

        <CollapsibleSection id="recent" icon="🕘" title="Recently viewed">
          <PreviewList
            items={recent}
            emptyLabel="Pages you view will appear here."
            render={(r) => (
              <Link key={r.slug} to={`/wiki/${r.slug}`} className="sidebar-link" onClick={onMobileClose}>
                {r.title}
              </Link>
            )}
          />
        </CollapsibleSection>

        <CollapsibleSection id="my-blog" icon="✍️" title="My blog" count={entries.length}>
          <Link to={`/blog/${login}/Blog`} className="sidebar-link" onClick={onMobileClose}>Blog home</Link>
          <PreviewList
            items={entries}
            emptyLabel="No blog entries yet."
            render={(e) => (
              <Link
                key={e.name}
                to={`/blog/${login}/${e.name}`}
                className="sidebar-link"
                onClick={onMobileClose}
              >
                {e.title || e.name}
              </Link>
            )}
          />
        </CollapsibleSection>

        {drafts.length > 0 && (
          <CollapsibleSection id="drafts" icon="📝" title="Resume editing" count={drafts.length}>
            {drafts.map((d) => (
              <div key={d.pageId} className="personal-draft-row">
                <Link to={`/edit/${d.pageId}`} className="sidebar-link" onClick={onMobileClose}>
                  {d.title}
                </Link>
                <button
                  type="button"
                  className="btn-link personal-draft-discard"
                  aria-label={`Discard draft for ${d.title}`}
                  onClick={() => removeDraft(d.pageId)}
                >
                  ✕
                </button>
              </div>
            ))}
          </CollapsibleSection>
        )}
      </CollapsibleSection>
    </div>
  );
}
