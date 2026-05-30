import { useState, useEffect } from 'react';

/**
 * useScrollSpy(ids) → currently-visible heading id.
 *
 * Uses IntersectionObserver to track which heading is at the top of the
 * viewport. Returns the id of the topmost intersecting element, or '' when
 * nothing is intersecting (observer not yet fired / all above fold).
 *
 * Guards for missing IntersectionObserver (SSR / old browsers / tests
 * that opt out of the mock).
 */
export default function useScrollSpy(ids) {
  const [activeId, setActiveId] = useState('');

  useEffect(() => {
    if (!ids || ids.length === 0) return;
    if (typeof IntersectionObserver === 'undefined') return;

    const elements = ids
      .map((id) => document.getElementById(id))
      .filter(Boolean);

    if (elements.length === 0) return;

    const observer = new IntersectionObserver(
      (entries) => {
        // Find the topmost intersecting entry
        const intersecting = entries
          .filter((e) => e.isIntersecting)
          .map((e) => e.target.id);

        if (intersecting.length > 0) {
          // Prefer the first intersecting id in the original order
          const ordered = ids.find((id) => intersecting.includes(id));
          if (ordered) setActiveId(ordered);
        }
      },
      {
        rootMargin: '0px 0px -60% 0px',
        threshold: 0,
      }
    );

    elements.forEach((el) => observer.observe(el));

    return () => {
      observer.disconnect();
    };
  }, [ids]);

  return activeId;
}
