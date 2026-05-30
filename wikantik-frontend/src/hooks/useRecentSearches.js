import { useState } from 'react';

const LS_KEY = 'wikantik:recent-searches';
const CAP = 8;

function readFromStorage() {
  try {
    return JSON.parse(localStorage.getItem(LS_KEY)) || [];
  } catch {
    return [];
  }
}

/**
 * useRecentSearches — localStorage-backed list of recent search terms.
 *
 * Returns:
 *   searches  string[]  — most-recent-first, deduped case-insensitively, max 8
 *   record(q) void      — add a trimmed, non-empty query to the list
 *   clear()   void      — empty the list and localStorage
 */
export function useRecentSearches() {
  const [searches, setSearches] = useState(() => readFromStorage());

  const record = (q) => {
    const trimmed = (q || '').trim();
    if (!trimmed) return;
    const lower = trimmed.toLowerCase();
    const prev = readFromStorage();
    // Remove any existing entry that matches case-insensitively, then prepend
    const deduped = [trimmed, ...prev.filter((s) => s.toLowerCase() !== lower)].slice(0, CAP);
    try {
      localStorage.setItem(LS_KEY, JSON.stringify(deduped));
    } catch (e) {
      console.warn('useRecentSearches: failed to persist', e);
    }
    setSearches(deduped);
  };

  const clear = () => {
    localStorage.removeItem(LS_KEY);
    setSearches([]);
  };

  return { searches, record, clear };
}
