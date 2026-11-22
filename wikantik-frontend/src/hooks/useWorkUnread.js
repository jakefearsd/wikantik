import { useEffect, useState } from 'react';
import { getUnread } from '../api/work';
import { useWorkNavVisible } from './useWorkNavVisible';

export const POLL_MS = 60000;
export const MAX_POLL_MS = 600000;

// One poller for the whole page: however many components show the count, one request goes out per interval.
const poller = {
  count: 0, consumers: new Set(), timer: null, delay: POLL_MS, streakWarned: false, stopped: false, inFlight: false,
};

const publish = (n) => { poller.count = n; poller.consumers.forEach((fn) => fn(n)); };
const clearTimer = () => { if (poller.timer != null) { clearTimeout(poller.timer); poller.timer = null; } };

function schedule() {
  clearTimer();
  if (poller.consumers.size === 0 || poller.stopped || document.visibilityState !== 'visible') return;
  poller.timer = setTimeout(tick, poller.delay);
}

function tick() {
  poller.timer = null;
  if (poller.inFlight) { schedule(); return; }
  poller.inFlight = true;
  getUnread()
    .then((r) => {
      poller.delay = POLL_MS; poller.streakWarned = false;
      if (typeof r?.unread === 'number') publish(r.unread);
    })
    .catch((e) => {
      if (e?.status === 401 || e?.status === 403) {
        // The session is gone: stop quietly. A user-driven work request that succeeds starts it again.
        poller.stopped = true;
        return;
      }
      poller.delay = Math.min(poller.delay * 2, MAX_POLL_MS);
      if (!poller.streakWarned) {
        poller.streakWarned = true;
        console.warn('useWorkUnread: could not load the unread count', e);
      }
    })
    .finally(() => { poller.inFlight = false; schedule(); });
}

function onVisibility() {
  if (document.visibilityState === 'visible' && !poller.stopped) { clearTimer(); tick(); } else clearTimer();
}

function resume() {
  poller.stopped = false; poller.delay = POLL_MS;
  clearTimer();
  if (poller.consumers.size > 0 && document.visibilityState === 'visible') tick();
}

function acquire(fn) {
  const first = poller.consumers.size === 0;
  poller.consumers.add(fn);
  fn(poller.count);
  if (first) {
    poller.delay = POLL_MS; poller.streakWarned = false; poller.stopped = false;
    document.addEventListener('visibilitychange', onVisibility);
    if (document.visibilityState === 'visible') tick();
  } else if (poller.stopped) {
    resume(); // another work page opened: a user-driven navigation
  }
}

function release(fn) {
  poller.consumers.delete(fn);
  if (poller.consumers.size === 0) {
    clearTimer();
    document.removeEventListener('visibilitychange', onVisibility);
  }
}

/**
 * Tells the navigation badge the count changed: with a number, it shows that; without, it asks the server again.
 * Called after a user-driven work request succeeded, so it also restarts a poll that stopped on a lapsed session.
 */
export function announceUnread(unread) {
  poller.stopped = false;
  if (typeof unread === 'number') { poller.delay = POLL_MS; publish(unread); } else resume();
}

/** Test seam: forget the shared poller's state. */
export function resetUnreadPoller() {
  clearTimer();
  document.removeEventListener('visibilitychange', onVisibility);
  Object.assign(poller, { count: 0, consumers: new Set(), delay: POLL_MS, streakWarned: false, stopped: false, inFlight: false });
}

/**
 * The unread notification count for the Work navigation entry, and whether that entry shows. The poll is silent (it
 * never raises the auth-required event), stops on 401/403, backs off after failures and never delays a page.
 */
export function useWorkUnread() {
  const visible = useWorkNavVisible();
  return { unread: useUnreadCount(visible), visible };
}

/** The same shared count for a page that already knows work is on. */
export function useUnreadCount(active) {
  const [unread, setUnread] = useState(poller.count);
  useEffect(() => {
    if (!active) return undefined;
    acquire(setUnread);
    return () => release(setUnread);
  }, [active]);
  return active ? unread : 0;
}
