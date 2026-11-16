// Time text shared by the work components so a claim reads the same on a card, the item page and the timeline.
// All local-time and pure: `ref` / `now` are passed in, so nothing here reads the clock.

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
