import { useEffect, useRef } from 'react';

/**
 * Sets document.title to `"Wikantik: ${title}"` on mount and whenever
 * `title` changes. On unmount, restores the previous title so nested
 * routes don't clobber each other permanently.
 *
 * Pass the bare page/section name; the "Wikantik: " prefix is applied
 * here. If `title` is falsy the effect is skipped (prevents a flash of
 * "Wikantik: " while data is still loading).
 */
export function useDocumentTitle(title) {
  const prevTitleRef = useRef(document.title);

  useEffect(() => {
    if (!title) return;
    prevTitleRef.current = document.title;
    document.title = `Wikantik: ${title}`;
    return () => {
      document.title = prevTitleRef.current;
    };
  }, [title]);
}
