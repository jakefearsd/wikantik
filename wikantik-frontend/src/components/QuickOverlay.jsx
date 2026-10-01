import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useRecentlyViewed } from '../hooks/useRecentlyViewed';
import { useCommands, useRunCommand } from '../commands/useCommands';
import { useGuardedNavigate } from '../navigation/NavigationGuardProvider';
import { useNewPage } from '../newpage/NewPageProvider';
import { fuzzyRank } from '../utils/fuzzy';

const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';

export default function QuickOverlay({ mode = 'pages', onClose }) {
  const seedFor = (m) => (m === 'commands' ? '>' : '');
  const [query, setQuery] = useState(seedFor(mode));
  const [pages, setPages] = useState([]);
  const [pageError, setPageError] = useState(false);
  const [fullText, setFullText] = useState([]);
  const [recentChanges, setRecentChanges] = useState([]);
  const [focused, setFocused] = useState(0);
  // Re-seed when the mode changes while open (Ctrl-P over Ctrl-K): adjust during render, not in an effect.
  const [seededMode, setSeededMode] = useState(mode);
  if (mode !== seededMode) {
    setSeededMode(mode);
    setQuery(seedFor(mode));
    setFocused(0);
  }
  const inputRef = useRef(null);
  const go = useGuardedNavigate();
  const { openNewPage } = useNewPage();
  const runCommand = useRunCommand();
  const commands = useCommands();
  const { user } = useAuth();
  const login = user?.loginPrincipal || null;
  const { items: recentlyViewed } = useRecentlyViewed({ login, enabled: !!login });

  const isCommand = query.startsWith('>');
  const term = (isCommand ? query.slice(1) : query).trim();

  useEffect(() => { inputRef.current?.focus(); }, []);

  useEffect(() => {
    if (login || isCommand) return undefined;
    let cancelled = false;
    api.getRecentChanges(8)
      .then((d) => { if (!cancelled) setRecentChanges((d?.changes || []).map((c) => c.name)); })
      .catch((err) => console.warn('[quick-overlay] recent changes unavailable', err?.message || err));
    return () => { cancelled = true; };
  }, [login, isCommand]);

  useEffect(() => {
    if (isCommand || !term) return undefined; // rows ignore pages when there is no term — no state reset needed
    let cancelled = false;
    const id = setTimeout(() => {
      api.listPages({ q: term, limit: 8 })
        .then((d) => { if (!cancelled) { setPages((d?.pages || []).map((p) => p.name)); setPageError(false); } })
        .catch((err) => {
          console.warn('[quick-overlay] page search failed', err?.message || err);
          if (!cancelled) { setPages([]); setPageError(true); }
        });
    }, 80);
    return () => { cancelled = true; clearTimeout(id); };
  }, [term, isCommand]);

  useEffect(() => {
    if (isCommand || !term) return undefined;
    const ctl = new AbortController();
    const id = setTimeout(() => {
      api.search(term, 8, { typeahead: true, signal: ctl.signal })
        .then((d) => setFullText((d?.results || []).map((r) => r.name)))
        .catch((err) => {
          if (err?.name === 'AbortError') return;
          console.warn('[quick-overlay] full-text search failed', err?.message || err);
          setFullText([]);
        });
    }, 200);
    return () => { ctl.abort(); clearTimeout(id); };
  }, [term, isCommand]);

  const rows = useMemo(() => {
    if (isCommand) {
      return commands
        .map((c) => ({ c, r: fuzzyRank(c.title, term) }))
        .filter((x) => x.r >= 0)
        .sort((a, b) => a.r - b.r || a.c.title.localeCompare(b.c.title))
        .map(({ c }) => ({ kind: 'command', key: `c:${c.id}`, command: c }));
    }
    if (!term) {
      const recent = login
        ? recentlyViewed.map((i) => ({ kind: 'page', key: `r:${i.slug}`, name: i.slug, label: i.title || i.slug }))
        : recentChanges.map((n) => ({ kind: 'page', key: `r:${n}`, name: n, label: n }));
      return recent;
    }
    const out = [];
    if (pageError) out.push({ kind: 'error', key: 'err', label: 'Page search failed' });
    pages.forEach((n) => out.push({ kind: 'page', key: `p:${n}`, name: n, label: n }));
    fullText.filter((n) => !pages.includes(n))
      .forEach((n) => out.push({ kind: 'fulltext', key: `f:${n}`, name: n, label: n }));
    out.push({ kind: 'search', key: 'search', label: `Search full text for “${term}”` });
    const exact = pages.some((n) => n.toLowerCase() === term.replace(/\s+/g, '').toLowerCase());
    if (!exact) out.push({ kind: 'create', key: 'create', label: `Create page “${term}”` });
    return out;
  }, [isCommand, commands, term, login, recentlyViewed, recentChanges, pages, fullText, pageError]);

  const selectable = rows.filter((r) => r.kind !== 'error');
  const current = selectable[Math.min(focused, selectable.length - 1)];

  const activate = (row, { newTab = false } = {}) => {
    if (!row) return;
    if (row.kind === 'page' || row.kind === 'fulltext') {
      if (newTab) { window.open(`${BASE}/wiki/${row.name}`, '_blank', 'noopener'); return; }
      onClose();
      go(`/wiki/${row.name}`);
    } else if (row.kind === 'search') {
      onClose();
      go(`/search?q=${encodeURIComponent(term)}`);
    } else if (row.kind === 'create') {
      onClose();
      openNewPage(term);
    } else if (row.kind === 'command') {
      onClose();
      runCommand(row.command.id);
    }
  };

  const onKeyDown = (e) => {
    if (e.key === 'Escape') { e.preventDefault(); onClose(); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); setFocused((f) => Math.min(f + 1, selectable.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setFocused((f) => Math.max(f - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); activate(current, { newTab: e.ctrlKey || e.metaKey }); }
  };

  return (
    <div className="search-overlay" data-testid="search-overlay"
         onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="search-dialog quick-overlay" role="dialog" aria-label="Go to page or run a command">
        <input ref={inputRef} className="search-input" data-testid="search-overlay-input" type="text"
               placeholder="Go to page…  (type > for commands)" value={query}
               onChange={(e) => { setQuery(e.target.value); setFocused(0); }} onKeyDown={onKeyDown}
               aria-controls="quick-overlay-rows" />
        <div className="search-results" id="quick-overlay-rows" role="listbox">
          {rows.map((row) => (
            row.kind === 'error'
              ? <div key={row.key} className="search-empty quick-row-error" data-testid="quick-row" data-kind="error">{row.label}</div>
              : (
                <button key={row.key} type="button" role="option"
                        aria-selected={row === current}
                        className={`search-result-item quick-row-${row.kind}${row === current ? ' focused' : ''}`}
                        data-testid="quick-row" data-kind={row.kind}
                        data-page-name={row.name} data-command-id={row.command?.id}
                        onMouseEnter={() => setFocused(selectable.indexOf(row))}
                        onClick={() => activate(row)}>
                  <span>{row.kind === 'command' ? row.command.title : row.label}</span>
                  {row.command?.keys && <kbd className="search-view-all-kbd">{row.command.keys}</kbd>}
                </button>
              )
          ))}
        </div>
      </div>
    </div>
  );
}
