import { useState, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';

export default function SearchOverlay({ onClose }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [focused, setFocused] = useState(0);
  const [searching, setSearching] = useState(false);
  const inputRef = useRef(null);
  const navigate = useNavigate();

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
    if (!query.trim()) { setResults([]); return; }
    const timer = setTimeout(async () => {
      setSearching(true);
      try {
        const data = await api.search(query);
        setResults(data.results || []);
        setFocused(0);
      } catch {
        setResults([]);
      } finally {
        setSearching(false);
      }
    }, 200);
    return () => clearTimeout(timer);
  }, [query]);

  const select = (name) => {
    navigate(`/wiki/${name}`);
    onClose();
  };

  const goToSearchResults = () => {
    if (query.trim()) {
      navigate(`/search?q=${encodeURIComponent(query.trim())}`);
      onClose();
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setFocused(f => Math.min(f + 1, results.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setFocused(f => Math.max(f - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      goToSearchResults();
    }
  };

  return (
    <div className="search-overlay" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="search-dialog">
        <input
          ref={inputRef}
          className="search-input"
          type="text"
          placeholder="Search pages…"
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={handleKeyDown}
        />
        <div className="search-results">
          {results.length > 0 ? results.map((r, i) => (
            <button
              key={r.name}
              className={`search-result-item ${i === focused ? 'focused' : ''}`}
              onClick={() => select(r.name)}
              onMouseEnter={() => setFocused(i)}
            >
              {r.name}
              {r.score != null && <span className="search-result-score">{Math.round(r.score * 100)}%</span>}
            </button>
          )) : query.trim() && !searching ? (
            <div className="search-empty">No results for "{query}"</div>
          ) : !query.trim() ? (
            <div className="search-empty">Type to search…</div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
