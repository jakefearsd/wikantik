import { useEffect, useState } from 'react';
import { useAuth } from './useAuth';
import { useCapabilities } from './useCapabilities';
import { getMe } from '../api/work';

/**
 * Whether the "Work" navigation entry shows: the feature is on AND the viewer is signed in AND is either
 * an admin or a member of at least one project. Costs no request while the feature is off.
 */
export function useWorkNavVisible() {
  const { user } = useAuth();
  const { capabilities } = useCapabilities();
  const authed = !!user?.authenticated;
  const isAdmin = authed && !!user?.roles?.includes('Admin');
  const enabled = !!capabilities?.work && authed;
  const [hasProject, setHasProject] = useState(false);

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;
    getMe()
      .then((me) => { if (!cancelled) setHasProject((me?.projects || []).length > 0); })
      .catch((e) => console.warn('useWorkNavVisible: failed to load /api/work/me', e));
    return () => { cancelled = true; };
  }, [enabled]);

  return enabled && (isAdmin || hasProject);
}
