import { useEffect, useRef } from 'react';

/**
 * Sets document.title to `"${title} - Wikantik"` on mount and whenever
 * `title` changes. On unmount, restores the previous title so nested
 * routes don't clobber each other permanently.
 *
 * Pass the bare page/section name; the " - Wikantik" suffix is applied
 * here, matching the title the server renders. If `title` is falsy the effect
 * is skipped (prevents a flash of " - Wikantik" while data is still loading).
 */
export function useDocumentTitle(title) {
  const prevTitleRef = useRef(document.title);

  useEffect(() => {
    if (!title) return;
    prevTitleRef.current = document.title;
    document.title = `${title} - Wikantik`;
    return () => {
      document.title = prevTitleRef.current;
    };
  }, [title]);
}
