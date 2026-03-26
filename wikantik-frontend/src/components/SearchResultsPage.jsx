import { useSearchParams, Link } from 'react-router-dom';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';

export default function SearchResultsPage() {
  const [params] = useSearchParams();
  const query = params.get('q') || '';
  const { data, loading, error } = useApi(() => api.search(query, 50), [query]);

  if (!query) {
    return (
      <div className="page-enter" style={{ textAlign: 'center', padding: 'var(--space-2xl) 0' }}>
        <h1 style={{ fontFamily: 'var(--font-display)', fontSize: '2rem', marginBottom: 'var(--space-md)' }}>
          Search
        </h1>
        <p style={{ color: 'var(--text-muted)' }}>Enter a search term to find articles.</p>
      </div>
    );
  }

  if (loading) return <div className="loading">Searching…</div>;
  if (error) return <div className="error-banner">Search failed: {error.message}</div>;

  const results = data?.results || [];

  return (
    <div className="page-enter">
      <div style={{ marginBottom: 'var(--space-xl)' }}>
        <h1 style={{
          fontFamily: 'var(--font-display)',
          fontSize: '1.75rem',
          fontWeight: 700,
          marginBottom: 'var(--space-xs)',
        }}>
          {results.length > 0
            ? `${results.length} result${results.length !== 1 ? 's' : ''} for "${query}"`
            : `No results for "${query}"`
          }
        </h1>
        {results.length === 0 && (
          <p style={{ color: 'var(--text-muted)', fontSize: '1rem', marginTop: 'var(--space-md)' }}>
            Try different keywords or check the spelling.
          </p>
        )}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-lg)' }}>
        {results.map(result => (
          <SearchResultCard key={result.name} result={result} />
        ))}
      </div>
    </div>
  );
}

function SearchResultCard({ result }) {
  const date = result.lastModified
    ? new Date(result.lastModified).toLocaleDateString('en-US', {
        year: 'numeric', month: 'short', day: 'numeric'
      })
    : null;

  const tags = Array.isArray(result.tags) ? result.tags : result.tags ? [result.tags] : [];

  return (
    <article style={{
      padding: 'var(--space-lg)',
      border: '1px solid var(--border)',
      borderRadius: 'var(--radius-md)',
      transition: 'border-color var(--duration) var(--ease), box-shadow var(--duration) var(--ease)',
      background: 'var(--bg-elevated)',
    }}
    onMouseEnter={e => {
      e.currentTarget.style.borderColor = 'var(--border-strong)';
      e.currentTarget.style.boxShadow = '0 2px 12px var(--shadow)';
    }}
    onMouseLeave={e => {
      e.currentTarget.style.borderColor = 'var(--border)';
      e.currentTarget.style.boxShadow = 'none';
    }}
    >
      {/* Title */}
      <Link
        to={`/wiki/${result.name}`}
        style={{
          fontFamily: 'var(--font-display)',
          fontSize: '1.3rem',
          fontWeight: 600,
          color: 'var(--text)',
          textDecoration: 'none',
          lineHeight: 1.3,
        }}
        onMouseEnter={e => e.currentTarget.style.color = 'var(--accent)'}
        onMouseLeave={e => e.currentTarget.style.color = 'var(--text)'}
      >
        {result.name}
      </Link>

      {/* Summary */}
      {result.summary && (
        <p style={{
          fontFamily: 'var(--font-body)',
          fontSize: '0.95rem',
          color: 'var(--text-secondary)',
          lineHeight: 1.6,
          marginTop: 'var(--space-sm)',
          marginBottom: 0,
        }}>
          {result.summary}
        </p>
      )}

      {/* Context snippets — highlighted match fragments from Lucene */}
      {result.contexts && result.contexts.length > 0 && (
        <div style={{
          marginTop: 'var(--space-sm)',
          padding: 'var(--space-sm) var(--space-md)',
          background: 'var(--bg-sidebar)',
          borderRadius: 'var(--radius-sm)',
          fontSize: '0.85rem',
          fontFamily: 'var(--font-body)',
          color: 'var(--text-secondary)',
          lineHeight: 1.6,
        }}>
          {result.contexts.slice(0, 2).map((ctx, i) => (
            <span key={i} dangerouslySetInnerHTML={{ __html: '…' + ctx + '…' }} />
          ))}
        </div>
      )}

      {/* Metadata row */}
      <div style={{
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 'var(--space-xs) var(--space-md)',
        marginTop: 'var(--space-sm)',
        fontSize: '0.8rem',
        color: 'var(--text-muted)',
      }}>
        {result.author && (
          <Link to={`/search?q=${encodeURIComponent(result.author)}`}
            style={{ fontWeight: 500, color: 'var(--text-secondary)', textDecoration: 'none' }}
            onMouseEnter={e => e.currentTarget.style.color = 'var(--accent)'}
            onMouseLeave={e => e.currentTarget.style.color = 'var(--text-secondary)'}
          >{result.author}</Link>
        )}
        {date && (
          <>
            <span style={{ color: 'var(--border-strong)' }}>·</span>
            <span>{date}</span>
          </>
        )}
        {result.cluster && (
          <>
            <span style={{ color: 'var(--border-strong)' }}>·</span>
            <Link to={`/search?q=${encodeURIComponent(result.cluster)}`} className="tag"
              style={{ textDecoration: 'none', cursor: 'pointer' }}
              onMouseEnter={e => e.currentTarget.style.opacity = '0.8'}
              onMouseLeave={e => e.currentTarget.style.opacity = '1'}
            >{result.cluster}</Link>
          </>
        )}
        <span style={{ color: 'var(--border-strong)' }}>·</span>
        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '0.75rem' }}>
          {Math.round(result.score)}% match
        </span>
      </div>

      {/* Tags */}
      {tags.length > 0 && (
        <div style={{ display: 'flex', gap: 'var(--space-xs)', flexWrap: 'wrap', marginTop: 'var(--space-sm)' }}>
          {tags.map(tag => (
            <Link key={tag} to={`/search?q=${encodeURIComponent(tag)}`} className="tag"
              style={{ textDecoration: 'none', cursor: 'pointer' }}
              onMouseEnter={e => e.currentTarget.style.opacity = '0.8'}
              onMouseLeave={e => e.currentTarget.style.opacity = '1'}
            >{tag}</Link>
          ))}
        </div>
      )}
    </article>
  );
}
