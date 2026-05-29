import { useCallback, useRef } from 'react';
import { draftKey } from '../utils/draftKeys';

/**
 * Editor autosave for a single page, persisted to localStorage namespaced by login.
 * @param {{login:string|null, pageId:string|null, enabled:boolean}} opts
 */
export function useDraft({ login, pageId, enabled }) {
  const active = enabled && !!login && !!pageId;
  const key = active ? draftKey(login, pageId) : null;

  // Read once on first render so an open editor can offer restore.
  const initial = useRef(undefined);
  if (initial.current === undefined) {
    if (key) {
      try {
        const raw = localStorage.getItem(key);
        initial.current = raw ? JSON.parse(raw) : null;
      } catch {
        initial.current = null;
      }
    } else {
      initial.current = null;
    }
  }

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

  return { draft: initial.current, saveDraft, clearDraft };
}
