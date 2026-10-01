import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useRecentlyViewed } from '../hooks/useRecentlyViewed';
import { useCommands, useRunCommand } from '../commands/useCommands';
import { useGuardedNavigate } from '../navigation/NavigationGuardProvider';
import { useNewPage } from '../newpage/NewPageProvider';
import { fuzzyRank } from '../utils/fuzzy';
import { beautify } from '../utils/beautify';
import { formatKeys } from '../utils/keyHints';
import Icon from './ui/Icon';

const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';
const ROW_ICONS = { page: 'page', fulltext: 'search', search: 'search', create: 'plus' };
const NOT_SELECTABLE = new Set(['error', 'section', 'divider']);

const section = (label) => ({ kind: 'section', key: `s:${label}`, label });
const pageRow = (kind, prefix, { name, title }) => ({ kind, key: `${prefix}:${name}`, name, label: title || beautify(name) });
const toEntry = (p) => ({ name: p.name, title: p.title });

function commandRows(commands, term) {
  const rows = commands
    .map((c) => ({ c, r: fuzzyRank(c.title, term) }))
    .filter((x) => x.r >= 0)
    .sort((a, b) => a.r - b.r || a.c.title.localeCompare(b.c.title))
    .map(({ c }) => ({ kind: 'command', key: `c:${c.id}`, command: c, label: c.title }));
  return rows.length ? [section('Commands'), ...rows] : rows;
}

function recentRows(entries) {
  const rows = entries.map((e) => pageRow('page', 'r', e));
  return rows.length ? [section('Recent'), ...rows] : rows;
}

/** Page matches, then full-text matches not already listed, then a divider and the search / create actions. */
function queryRows({ term, pages, fullText, pageError }) {
  const out = [];
  if (pages.length || pageError) out.push(section('Pages'));
  if (pageError) out.push({ kind: 'error', key: 'err', label: 'Page search failed' });
  pages.forEach((p) => out.push(pageRow('page', 'p', p)));
  const extra = fullText.filter((f) => !pages.some((p) => p.name === f.name));
  if (extra.length) out.push(section('Full-text matches'));
  extra.forEach((f) => out.push(pageRow('fulltext', 'f', f)));
  if (out.length) out.push({ kind: 'divider', key: 'divider' });
  out.push({ kind: 'search', key: 'search', label: `Search full text for “${term}”` });
  const exact = pages.some((p) => p.name.toLowerCase() === term.replace(/\s+/g, '').toLowerCase());
  if (!exact) out.push({ kind: 'create', key: 'create', label: `Create page “${term}”` });
  return out;
}

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

  const listRef = useRef(null);
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
        .then((d) => { if (!cancelled) { setPages((d?.pages || []).map(toEntry)); setPageError(false); } })
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
        .then((d) => setFullText((d?.results || []).map(toEntry)))
        .catch((err) => {
          if (err?.name === 'AbortError') return;
          console.warn('[quick-overlay] full-text search failed', err?.message || err);
          setFullText([]);
        });
    }, 200);
    return () => { ctl.abort(); clearTimeout(id); };
  }, [term, isCommand]);

  const rows = useMemo(() => {
    if (isCommand) return commandRows(commands, term);
    if (!term) {
      return recentRows(login
        ? recentlyViewed.map((i) => ({ name: i.slug, title: i.title }))
        : recentChanges.map((n) => ({ name: n })));
    }
    return queryRows({ term, pages, fullText, pageError });
  }, [isCommand, commands, term, login, recentlyViewed, recentChanges, pages, fullText, pageError]);

  const selectable = rows.filter((r) => !NOT_SELECTABLE.has(r.kind));
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

  useEffect(() => {
    // Keep the keyboard-selected row visible when arrowing through a list taller than the panel.
    listRef.current?.querySelector('.focused')?.scrollIntoView?.({ block: 'nearest' });
  }, [current]);

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
        <div className="search-results" id="quick-overlay-rows" role="listbox" ref={listRef}>
          {rows.map((row) => <QuickRow key={row.key} row={row} focused={row === current}
                                       onHover={() => setFocused(selectable.indexOf(row))}
                                       onActivate={() => activate(row)} />)}
        </div>
      </div>
    </div>
  );
}

/** One overlay entry: a section header, the divider, the error notice, or a selectable row. */
function QuickRow({ row, focused, onHover, onActivate }) {
  if (row.kind === 'section') {
    return <div className="quick-section" data-testid="quick-section" role="presentation">{row.label}</div>;
  }
  if (row.kind === 'divider') {
    return <div className="quick-divider" data-testid="quick-divider" role="separator" />;
  }
  if (row.kind === 'error') {
    return <div className="search-empty quick-row-error" data-testid="quick-row" data-kind="error">{row.label}</div>;
  }
  const keys = row.command?.keys;
  return (
    <button type="button" role="option" aria-selected={focused}
            className={`search-result-item quick-row quick-row-${row.kind}${focused ? ' focused' : ''}`}
            data-testid="quick-row" data-kind={row.kind}
            data-page-name={row.name} data-command-id={row.command?.id}
            onMouseEnter={onHover} onClick={onActivate}>
      {ROW_ICONS[row.kind] && <Icon name={ROW_ICONS[row.kind]} size={15} className="quick-row-icon" />}
      <span className="quick-row-text">
        <span className="quick-row-title">{row.label}</span>
        {row.name && row.name !== row.label && <span className="quick-row-name">{row.name}</span>}
      </span>
      {keys && <kbd className="search-view-all-kbd quick-row-kbd">{formatKeys(keys)}</kbd>}
    </button>
  );
}
