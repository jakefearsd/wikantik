import { useEffect, useState } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api } from '../api/client';
import { useApi } from '../hooks/useApi';
import { highlightTerms } from '../utils/highlight';
import { formatDate } from '../utils/datetime';
import Card from './ui/Card';
import EmptyState from './ui/EmptyState';
import Icon from './ui/Icon';

const PAGE_SIZE = 20;

export default function SearchResultsPage() {
  const [params] = useSearchParams();
  const query = params.get('q') || '';
  const { data, loading, error } = useApi((signal) => api.search(query, 50, { signal }), [query]);
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);

  // Reset pagination when query changes
  useEffect(() => {
    setVisibleCount(PAGE_SIZE);
  }, [query]);

  useEffect(() => {
    document.title = query ? `Wikantik: Search results for ${query}` : 'Wikantik: Search';
  }, [query]);

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
  const visibleResults = results.slice(0, visibleCount);
  const hasMore = results.length > visibleCount;

  return (
    <div className="page-enter" data-testid="search-results-page" data-query={query}>
      {results.length === 0 ? (
        <EmptyState
          icon={<Icon name="search" />}
          message={`No results for "${query}"`}
          action={<span>Try different keywords or check spelling.</span>}
        />
      ) : (
      <div style={{ marginBottom: 'var(--space-xl)' }}>
        <h1
          data-testid="search-results-heading"
          style={{
            fontFamily: 'var(--font-display)',
            fontSize: '1.75rem',
            fontWeight: 700,
            marginBottom: 'var(--space-xs)',
          }}
        >
          {`${results.length} result${results.length !== 1 ? 's' : ''} for "${query}"`}
        </h1>
      </div>
      )}

      {results.length > 0 && (
        <>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-lg)' }} data-testid="search-results-list">
            {visibleResults.map(result => (
              <SearchResultCard key={result.name} result={result} query={query} />
            ))}
          </div>

          {hasMore && (
            <div style={{ marginTop: 'var(--space-xl)', textAlign: 'center' }}>
              <p
                data-testid="results-count"
                style={{ color: 'var(--text-muted)', fontSize: '0.9rem', marginBottom: 'var(--space-sm)' }}
              >
                Showing {visibleCount} of {results.length}
              </p>
              <button
                data-testid="load-more-button"
                onClick={() => setVisibleCount(c => c + PAGE_SIZE)}
                style={{
                  padding: 'var(--space-sm) var(--space-lg)',
                  borderRadius: 'var(--radius-md)',
                  border: '1px solid var(--border)',
                  background: 'var(--bg-elevated)',
                  color: 'var(--text)',
                  cursor: 'pointer',
                  fontSize: '0.95rem',
                  fontFamily: 'var(--font-body)',
                }}
              >
                Load more
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

// Tight, inline-friendly overrides for ReactMarkdown when rendering preview
// snippets. The snippet container is small; default block margins/font sizes
// would overflow the card. Empty-href wikilinks (Lucene emits these for bare
// `[Page]()` fragments) render as plain text to avoid dead anchor styling.
const SNIPPET_COMPONENTS = {
  p:  ({ children }) => <p  style={{ margin: 0, display: 'inline' }}>{children}</p>,
  pre: ({ children }) => (
    <pre style={{
      margin: '4px 0',
      padding: '4px 8px',
      background: 'var(--code-bg)',
      borderRadius: 'var(--radius-sm)',
      fontSize: '0.78rem',
      overflowX: 'auto',
      whiteSpace: 'pre-wrap',
    }}>{children}</pre>
  ),
  code: ({ inline, children }) => inline
    ? <code style={{
        background: 'var(--code-bg)',
        padding: '0 4px',
        borderRadius: '3px',
        fontFamily: 'var(--font-mono)',
        fontSize: '0.85em',
      }}>{children}</code>
    : <code style={{ fontFamily: 'var(--font-mono)', fontSize: '0.85em' }}>{children}</code>,
  ul: ({ children }) => <ul style={{ margin: '4px 0', paddingLeft: '1.4em' }}>{children}</ul>,
  ol: ({ children }) => <ol style={{ margin: '4px 0', paddingLeft: '1.4em' }}>{children}</ol>,
  li: ({ children }) => <li style={{ margin: 0 }}>{children}</li>,
  h1: ({ children }) => <strong>{children}</strong>,
  h2: ({ children }) => <strong>{children}</strong>,
  h3: ({ children }) => <strong>{children}</strong>,
  h4: ({ children }) => <strong>{children}</strong>,
  h5: ({ children }) => <strong>{children}</strong>,
  h6: ({ children }) => <strong>{children}</strong>,
  a:  ({ href, children }) => (href ? <a href={href}>{children}</a> : <span>{children}</span>),
};

function SearchResultCard({ result, query }) {
  const date = result.lastModified ? formatDate(result.lastModified) : null;

  const tags = Array.isArray(result.tags) ? result.tags : result.tags ? [result.tags] : [];

  return (
    <Card
      as="article"
      data-testid="search-result-card"
      data-page-name={result.name}
    >
      {/* Title */}
      <Link
        to={`/wiki/${result.name}`}
        data-testid="search-result-link"
        className="search-result-title"
      >
        {highlightTerms(result.name, query)}
      </Link>

      {/* Summary */}
      {result.summary && (
        <p className="search-result-summary">
          {highlightTerms(result.summary, query)}
        </p>
      )}

      {/* Context snippets — Markdown match fragments from the search engine.
          Rendered via react-markdown (same toolchain as the article view) so list
          items, code fences, links, etc. render as readable preview rather than
          raw markup. Fragments may start mid-element; react-markdown is forgiving
          enough to make sense of partial trees. */}
      {result.contexts && result.contexts.length > 0 && (
        <div className="search-result-snippet">
          {result.contexts.slice(0, 2).map((ctx, i) => (
            <div key={i} style={{ marginTop: i === 0 ? 0 : 'var(--space-xs)' }}>
              <span className="search-result-snippet-sep">…</span>
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                components={SNIPPET_COMPONENTS}
              >
                {ctx}
              </ReactMarkdown>
              <span className="search-result-snippet-sep">…</span>
            </div>
          ))}
        </div>
      )}

      {/* Metadata row */}
      <div className="search-result-meta">
        {result.author && (
          <Link to={`/search?q=${encodeURIComponent(result.author)}`}
            className="search-result-meta-author"
          >{result.author}</Link>
        )}
        {date && (
          <>
            <span className="search-result-meta-sep">·</span>
            <span>{date}</span>
          </>
        )}
        {result.cluster && (
          <>
            <span className="search-result-meta-sep">·</span>
            <Link to={`/search?q=${encodeURIComponent(result.cluster)}`} className="tag">{result.cluster}</Link>
          </>
        )}
        <span className="search-result-meta-sep">·</span>
        <span className="search-result-meta-score">
          {Math.round(result.score)}% match
        </span>
      </div>

      {/* Tags */}
      {tags.length > 0 && (
        <div className="search-result-tags">
          {tags.map(tag => (
            <Link key={tag} to={`/search?q=${encodeURIComponent(tag)}`} className="tag">{tag}</Link>
          ))}
        </div>
      )}
    </Card>
  );
}
