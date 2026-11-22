import { useEffect, useState } from 'react';
import { useAuth } from './useAuth';
import { useCapabilities } from './useCapabilities';
import { getMe } from '../api/work';

/**
 * Whether the "Work" navigation entry shows: the feature is on AND the viewer is signed in AND is either
 * an admin or a member of at least one project that is not archived, or may file into some project (a reporter has no project
 * of their own but still has requests to file and follow). Costs no request while the feature is off.
 */
export function useWorkNavVisible() {
  const { user } = useAuth();
  const { capabilities } = useCapabilities();
  const authed = !!user?.authenticated;
  const isAdmin = authed && !!user?.roles?.includes('Admin');
  const enabled = !!capabilities?.work && authed;
  // The answer is remembered with the user it was asked for; another user's answer is never reused.
  const who = authed ? `auth:${user?.username ?? ''}` : null;
  const [answer, setAnswer] = useState({ who: null, hasProject: false });
  const hasProject = answer.who === who && answer.hasProject;

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;
    getMe()
      // Archived projects are read-only and listed apart: they alone do not earn a navigation entry. A project one may file into does.
      .then((me) => { if (!cancelled) setAnswer({ who, hasProject: (me?.projects || []).some((p) => !p.archived) || (me?.reportable || []).length > 0 }); })
      .catch((e) => console.warn('useWorkNavVisible: failed to load /api/work/me', e));
    return () => { cancelled = true; };
  }, [enabled, who]);

  return enabled && (isAdmin || hasProject);
}
