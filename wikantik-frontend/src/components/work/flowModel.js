// Pure helpers for the flow and delivery figures. Nothing here reads the clock unless `today` is left out.
import { formatDuration } from './charts/chartScale';

const DAY_MS = 86400000;
const MIN_YEAR = 1970;
const MAX_YEAR = 9999;
/** The most days a report may cover (inclusive); the server refuses more. */
export const MAX_WINDOW_DAYS = 366;
/** Below this many values a percentile is shown with a "few data points" note. */
export const FEW = 5;

const utc = (iso) => {
  const [y, m, d] = iso.split('-').map(Number);
  return Date.UTC(y, m - 1, d);
};
const isoOf = (ms) => new Date(ms).toISOString().slice(0, 10);

/** A duration in whole seconds as text through formatDuration; an en dash for a missing value. */
export const durationText = (seconds) => (seconds === null || seconds === undefined || !Number.isFinite(Number(seconds))
  ? '–' : formatDuration(Number(seconds) / 3600));

/** "57%" for { numerator, denominator }; an en dash when the denominator is 0 (a rate over nothing is not 0%). */
export function percent(rate) {
  const n = Number(rate?.numerator);
  const d = Number(rate?.denominator);
  if (!Number.isFinite(n) || !Number.isFinite(d) || d <= 0) return '–';
  return `${Math.round((n / d) * 100)}%`;
}

/** "16 Nov" for a plain date. */
export function weekLabel(iso) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso || '')) return '';
  const day = Number(iso.slice(8, 10));
  const month = new Date(utc(iso)).toLocaleDateString('en-US', { month: 'short', timeZone: 'UTC' });
  return `${day} ${month}`;
}

/** Today as yyyy-MM-dd in the viewer's own zone. */
export function todayIso(now = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`;
}

/** True for a week (Monday `weekStart` to Sunday) the window cuts, or one that has not finished yet. */
export function partialWeek(weekStart, from, to, today) {
  if (!weekStart) return false;
  const end = isoOf(utc(weekStart) + 6 * DAY_MS);
  if (from && weekStart < from) return true;
  if (to && end > to) return true;
  return !!today && end >= today;
}

/** Whether `text` is a real calendar date (yyyy-MM-dd) from 1970 to 9999. */
export function validDate(text) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text || '');
  if (!m) return false;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  if (y < MIN_YEAR || y > MAX_YEAR) return false;
  const dt = new Date(Date.UTC(y, mo - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === mo - 1 && dt.getUTCDate() === d;
}

/**
 * The message for a window the server would refuse, else null. Blank bounds are fine (the server defaults them to
 * the last 90 days). Mirrors the route: real dates in 1970-9999, from not after to, at most 366 days inclusive.
 */
export function windowError({ from, to } = {}) {
  if (from && !validDate(from)) return 'Start: use a real date between 1970 and 9999, like 2026-11-21.';
  if (to && !validDate(to)) return 'End: use a real date between 1970 and 9999, like 2026-11-21.';
  if (from && to) {
    if (from > to) return 'The start date is after the end date.';
    if ((utc(to) - utc(from)) / DAY_MS + 1 > MAX_WINDOW_DAYS) return `A report covers at most ${MAX_WINDOW_DAYS} days.`;
  }
  return null;
}

/** The parameters the project flow page keeps in its URL. */
export const FLOW_PARAMS = ['from', 'to', 'sprint', 'label', 'type', 'assignee', 'product'];

/** The request parameters held in the URL: only the named, non-blank ones. */
export function paramsFrom(searchParams, names) {
  const out = {};
  names.forEach((n) => {
    const v = (searchParams.get(n) || '').trim();
    if (v) out[n] = v;
  });
  return out;
}

/** The URL query for `params`, dropping blanks (inverse of paramsFrom). */
export function toSearch(params) {
  const next = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && String(v).trim() !== '') next.set(k, String(v).trim()); });
  return next;
}

/** "from 7 items" / "from 1 item". */
export const sampleText = (n, noun) => `from ${n} ${noun}${n === 1 ? '' : 's'}`;
