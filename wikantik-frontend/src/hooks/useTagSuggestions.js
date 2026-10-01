import { useEffect, useState } from 'react';
import { api } from '../api/client';

// One fetch per browser session, shared by every editor that mounts.
let cached = null;

function load() {
  if (!cached) {
    cached = api.listTags()
      .then((data) => (data?.tags || [])
        .slice()
        .sort((a, b) => (b.count - a.count) || a.tag.localeCompare(b.tag))
        .map((t) => t.tag))
      .catch((err) => {
        console.warn('[tag-suggestions] could not load tags; free entry still works', err?.message || err);
        cached = null; // allow a later editor mount to retry
        return [];
      });
  }
  return cached;
}

export function useTagSuggestions() {
  const [tags, setTags] = useState([]);
  useEffect(() => {
    let cancelled = false;
    load().then((t) => { if (!cancelled) setTags(t); });
    return () => { cancelled = true; };
  }, []);
  return tags;
}

export function __resetTagSuggestionsForTest() { cached = null; }
