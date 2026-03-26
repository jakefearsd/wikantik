import { useParams, Link } from 'react-router-dom';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import { useAuth } from '../hooks/useAuth';
import PageMeta from './PageMeta';
import MetadataPanel from './MetadataPanel';
import ChangeNotesPanel from './ChangeNotesPanel';
import '../styles/article.css';

export default function PageView() {
  const { name = 'Main' } = useParams();
  const { user } = useAuth();
  const { data: page, loading, error } = useApi(() => api.getPage(name, { render: true }), [name]);

  if (loading) return <div className="loading">Loading…</div>;
  if (error?.status === 404) return <NotFound name={name} />;
  if (error) return <div className="error-banner">Failed to load page: {error.message}</div>;
  if (!page) return null;

  return (
    <div className="page-enter">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-md)' }}>
        <PageMeta page={page} />
        {user?.authenticated && (
          <Link to={`/edit/${name}`} className="btn btn-ghost" style={{ flexShrink: 0 }}>
            ✎ Edit
          </Link>
        )}
      </div>
      <MetadataPanel metadata={page.metadata} />
      <ChangeNotesPanel pageName={name} />

      <article
        className="article-prose"
        dangerouslySetInnerHTML={{ __html: page.contentHtml || page.content || '' }}
      />

      {page.metadata?.tags && (
        <div style={{ marginTop: 'var(--space-2xl)', display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
          {(Array.isArray(page.metadata.tags) ? page.metadata.tags : [page.metadata.tags]).map(tag => (
            <span key={tag} className="tag">{tag}</span>
          ))}
        </div>
      )}
    </div>
  );
}

function NotFound({ name }) {
  const { user } = useAuth();
  return (
    <div className="page-enter" style={{ textAlign: 'center', padding: 'var(--space-2xl) 0' }}>
      <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '2rem', marginBottom: 'var(--space-md)' }}>
        Page not found
      </h1>
      <p style={{ color: 'var(--text-muted)', marginBottom: 'var(--space-lg)' }}>
        "{name}" doesn't exist yet.
      </p>
      {user?.authenticated && (
        <Link to={`/edit/${name}`} className="btn btn-primary">
          Create "{name}"
        </Link>
      )}
    </div>
  );
}
