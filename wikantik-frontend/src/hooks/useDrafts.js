import { useCallback, useState } from 'react';
import { draftPrefix, parseDraftKey } from '../utils/draftKeys';

function readDrafts(login) {
  if (!login) return [];
  const prefix = draftPrefix(login);
  const out = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key || !key.startsWith(prefix)) continue;
    const pageId = parseDraftKey(login, key);
    try {
      const val = JSON.parse(localStorage.getItem(key));
      out.push({ pageId, title: val.title || pageId, savedAt: val.savedAt || 0 });
    } catch {
      // Skip a corrupt entry rather than failing the whole list.
    }
  }
  out.sort((a, b) => b.savedAt - a.savedAt);
  return out;
}

export function useDrafts({ login, enabled }) {
  const active = enabled && !!login;
  const [drafts, setDrafts] = useState(() => (active ? readDrafts(login) : []));

  const removeDraft = useCallback((pageId) => {
    if (!login) return;
    localStorage.removeItem(`${draftPrefix(login)}${pageId}`);
    setDrafts(readDrafts(login));
  }, [login]);

  return { drafts, removeDraft };
}
