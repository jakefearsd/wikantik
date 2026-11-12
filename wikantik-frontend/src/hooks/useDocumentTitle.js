import { useEffect, useRef } from 'react';

const APP_NAME = 'Wikantik';

/**
 * The browser title for a page title, by the server's rule (PageSeoModel.titleWithApp):
 * `"${title} - Wikantik"`, unless the title already names the app (in any case), which
 * is then used as is, so "WikantikOperations" stays "WikantikOperations".
 */
export function documentTitleFor(title) {
  const t = String(title).trim();
  return t.toLowerCase().includes(APP_NAME.toLowerCase()) ? t : `${t} - ${APP_NAME}`;
}

/**
 * Sets document.title on mount and whenever `title` changes, to exactly the title
 * the server renders for the same page (see documentTitleFor). On unmount, restores
 * the previous title so nested routes don't clobber each other permanently.
 *
 * Pass the bare page/section name; the " - Wikantik" suffix is applied here.
 * If `title` is falsy the effect is skipped (prevents a flash of " - Wikantik"
 * while data is still loading).
 */
export function useDocumentTitle(title) {
  const prevTitleRef = useRef(document.title);

  useEffect(() => {
    if (!title) return;
    prevTitleRef.current = document.title;
    document.title = documentTitleFor(title);
    return () => {
      document.title = prevTitleRef.current;
    };
  }, [title]);
}
