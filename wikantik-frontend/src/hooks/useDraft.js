import { useCallback, useState } from 'react';
import { draftKey } from '../utils/draftKeys';

/**
 * Editor autosave for a single page, persisted to localStorage namespaced by login.
 * @param {{login:string|null, pageId:string|null, enabled:boolean}} opts
 */
export function useDraft({ login, pageId, enabled }) {
  const active = enabled && !!login && !!pageId;
  const key = active ? draftKey(login, pageId) : null;

  // Read once on first render (lazy useState initializer) so an open editor
  // can offer restore; later `key` changes intentionally don't re-read.
  const [draft] = useState(() => {
    if (!key) return null;
    try {
      const raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  });

  const saveDraft = useCallback((fields) => {
    if (!key) return;
    try {
      localStorage.setItem(key, JSON.stringify({ ...fields, savedAt: Date.now() }));
    } catch (e) {
      // Quota or serialization failure — drafts are best-effort; don't break editing.
      console.warn('useDraft: failed to persist draft', e);
    }
  }, [key]);

  const clearDraft = useCallback(() => {
    if (key) localStorage.removeItem(key);
  }, [key]);

  return { draft, saveDraft, clearDraft };
}
