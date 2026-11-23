// Time text shared by the work components so a claim reads the same on a card, the item page and the timeline.
// All local-time and pure: `ref` / `now` are passed in, so nothing here reads the clock.

import { useEffect, useState } from 'react';

const pad = (n) => String(n).padStart(2, '0');
const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

/** "15:40" when `iso` falls on the same local day as `ref`, else "Nov 17 15:40". Empty for a missing or bad instant. */
export function clockText(iso, ref) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const r = ref ? new Date(ref) : null;
  if (r && !Number.isNaN(r.getTime()) && sameDay(d, r)) return hm;
  return `${d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} ${hm}`;
}

/** The complete local timestamp, for a title or aria-label. */
export const fullTime = (iso) => (iso ? new Date(iso).toLocaleString() : '');

/** True when `iso` is at or before `now`. */
export const isPast = (iso, now = new Date()) => !!iso && new Date(iso).getTime() <= new Date(now).getTime();

/** "Nov 16 13:00", with the year ("Dec 31 2025 08:05") when `iso` is not in `now`'s year. Empty for a bad instant. */
export function whenText(iso, now = new Date()) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const date = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  const year = d.getFullYear() === new Date(now).getFullYear() ? '' : ` ${d.getFullYear()}`;
  return `${date}${year} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const MAX_TIMEOUT = 2 ** 31 - 1;

/**
 * The clock for deciding "until" versus "lapsed". An injected `now` (tests) is used as is; otherwise the current time
 * is re-read once the `expiry` instant passes, by a single timeout (no polling) that is cleared on unmount.
 */
export function useExpiryClock(expiry, injected) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    if (injected || !expiry) return undefined;
    const ms = new Date(expiry).getTime() - Date.now();
    if (!(ms > 0)) return undefined;
    const id = setTimeout(() => setNow(new Date()), Math.min(ms + 5, MAX_TIMEOUT));
    return () => clearTimeout(id);
  }, [expiry, injected, now]);
  return injected || now;
}

/** "Nov 16 2026" for a plain date ("2026-11-16"), read as the calendar day it names (no zone shift). Empty for a bad date. */
export function dayText(isoDate) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate || '');
  if (!m) return '';
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  if (Number.isNaN(d.getTime())) return '';
  return `${d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })} ${m[1]}`;
}

/**
 * "Nov 23 – Dec 6 2026" for two plain dates; the first keeps its year only when the years differ
 * ("Dec 28 2026 – Jan 10 2027"). A side that is not a date is shown as it came.
 */
export function dayRangeText(fromIso, toIso) {
  const from = dayText(fromIso);
  const to = dayText(toIso);
  if (!from || !to) return `${from || fromIso || ''} – ${to || toIso || ''}`;
  const sameYear = fromIso.slice(0, 4) === toIso.slice(0, 4);
  return `${sameYear ? from.slice(0, -5) : from} – ${to}`;
}
