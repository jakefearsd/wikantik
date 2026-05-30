import { useState, useRef, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useRecentSearches } from '../hooks/useRecentSearches';
import { useRecentlyViewed } from '../hooks/useRecentlyViewed';

export default function SearchOverlay({ onClose }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [focused, setFocused] = useState(0);
  const [searching, setSearching] = useState(false);
  const inputRef = useRef(null);
  const navigate = useNavigate();
  // Keep refs for use in event handlers to avoid stale closures
  const focusedRef = useRef(0);
  const resultsRef = useRef([]);

  const { user } = useAuth();
  const login = user?.loginPrincipal || null;
  const { searches: recentSearches, record: recordSearch } = useRecentSearches();
  const { items: recentlyViewed } = useRecentlyViewed({
    login,
    enabled: !!login,
  });

  const setFocusedSync = (val) => {
    const next = typeof val === 'function' ? val(focusedRef.current) : val;
    focusedRef.current = next;
    setFocused(next);
  };

  const setResultsSync = (val) => {
    resultsRef.current = val;
    setResults(val);
  };

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Close on Escape
  useEffect(() => {
    const handler = (e) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  // Debounced search
  useEffect(() => {
    if (!query.trim()) { setResultsSync([]); return; }
    const timer = setTimeout(async () => {
      setSearching(true);
      try {
        const data = await api.search(query);
        setResultsSync(data.results || []);
        setFocusedSync(0);
      } catch {
        setResultsSync([]);
      } finally {
        setSearching(false);
      }
    }, 200);
    return () => clearTimeout(timer);
  }, [query]);

  const select = useCallback((name) => {
    navigate(`/wiki/${name}`);
    onClose();
  }, [navigate, onClose]);

  const goToSearchResults = useCallback(() => {
    if (query.trim()) {
      recordSearch(query.trim());
      navigate(`/search?q=${encodeURIComponent(query.trim())}`);
      onClose();
    }
  }, [query, navigate, onClose, recordSearch]);

  const handleKeyDown = (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (resultsRef.current.length === 0) return;
      setFocusedSync(f => (f + 1) % resultsRef.current.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (resultsRef.current.length === 0) return;
      setFocusedSync(f => (f - 1 + resultsRef.current.length) % resultsRef.current.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (resultsRef.current.length > 0) {
        recordSearch(query.trim());
        select(resultsRef.current[focusedRef.current].name);
      } else {
        goToSearchResults();
      }
    }
  };

  const applyRecentSearch = (term) => {
    setQuery(term);
  };

  const hasRecentSearches = recentSearches.length > 0;
  const hasRecentlyViewed = recentlyViewed.length > 0;
  const showEmptyState = !query.trim();

  return (
    <div className="search-overlay" data-testid="search-overlay" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="search-dialog">
        <input
          ref={inputRef}
          className="search-input"
          data-testid="search-overlay-input"
          type="text"
          placeholder="Search pages…"
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={handleKeyDown}
        />
        <div className="search-results" data-testid="search-overlay-results">
          {results.length > 0 ? results.map((r, i) => (
            <button
              key={r.name}
              className={`search-result-item ${i === focused ? 'focused' : ''}`}
              data-testid="search-overlay-result"
              data-page-name={r.name}
              onClick={() => { recordSearch(query.trim()); select(r.name); }}
              onMouseEnter={() => setFocusedSync(i)}
            >
              {r.name}
              {r.score != null && <span className="search-result-score">{Math.round(r.score * 100)}%</span>}
            </button>
          )) : query.trim() && !searching ? (
            <div className="search-empty">No results for "{query}"</div>
          ) : showEmptyState ? (
            <>
              {hasRecentSearches && (
                <div className="search-section" data-testid="recent-searches-section">
                  <div className="search-section-title">Recent searches</div>
                  {recentSearches.map((term) => (
                    <button
                      key={term}
                      className="search-result-item"
                      data-testid="recent-search-item"
                      onClick={() => applyRecentSearch(term)}
                    >
                      🔍 {term}
                    </button>
                  ))}
                </div>
              )}
              {hasRecentlyViewed && (
                <div className="search-section" data-testid="recently-viewed-section">
                  <div className="search-section-title">Recently viewed</div>
                  {recentlyViewed.map((item) => (
                    <button
                      key={item.slug}
                      className="search-result-item"
                      data-testid="recently-viewed-item"
                      onClick={() => select(item.slug)}
                    >
                      📄 {item.title || item.slug}
                    </button>
                  ))}
                </div>
              )}
              {!hasRecentSearches && !hasRecentlyViewed && (
                <div className="search-empty">Type to search…</div>
              )}
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}
