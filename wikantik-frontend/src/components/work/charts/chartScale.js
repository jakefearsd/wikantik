// Pure scale and formatting helpers for the work charts. Nothing here reads the clock or the DOM, and every
// function returns a finite number (or a finite-number array) whatever it is given, so no SVG attribute built
// from them can become NaN or Infinity.

/** A finite number from `v`, else `fallback`. Strings of digits count; null, '' and booleans do not. */
export function finiteOr(v, fallback = 0) {
  if (typeof v === 'string' && v.trim() === '') return fallback;
  if (v === null || v === undefined || typeof v === 'boolean') return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

/**
 * `linearScale([d0, d1], [r0, r1])` maps a value in the domain onto the range. A degenerate domain maps to the
 * middle of the range; a value that is not a finite number maps to `r0`.
 */
export function linearScale([d0, d1], [r0, r1]) {
  const a = finiteOr(d0);
  const b = finiteOr(d1);
  const lo = finiteOr(r0);
  const hi = finiteOr(r1);
  return (v) => {
    const n = Number(v);
    if (v === null || v === undefined || !Number.isFinite(n)) return lo;
    if (a === b) return (lo + hi) / 2;
    const out = lo + ((n - a) / (b - a)) * (hi - lo);
    return Number.isFinite(out) ? out : lo;
  };
}

const roundTo = (n, decimals) => {
  const f = 10 ** decimals;
  return Math.round(n * f) / f;
};

/**
 * "Nice" axis ticks: 0 first, equal steps of 1, 2 or 5 times a power of ten, the last at or above `max`. A `max`
 * that is zero, negative or not finite gives `[0, 1]`. Ticks are distinct numbers, so labels never repeat.
 */
export function niceTicks(max, count = 4) {
  const m = finiteOr(max);
  if (m <= 0) return [0, 1];
  const n = Math.max(1, Math.floor(finiteOr(count, 4)));
  const raw = m / n;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const f = raw / pow;
  let nice = 10;
  if (f <= 1) nice = 1;
  else if (f <= 2) nice = 2;
  else if (f <= 5) nice = 5;
  const step = nice * pow;
  const decimals = Math.max(0, -Math.floor(Math.log10(step)) + 1);
  const steps = Math.max(1, Math.ceil(roundTo(m / step, 9)));
  const ticks = [];
  for (let i = 0; i <= steps; i += 1) {
    const t = roundTo(i * step, Math.min(decimals, 20));
    if (!Number.isFinite(t)) return [0, m];
    ticks.push(t);
  }
  return ticks;
}

/** "1,234", "12.5", "1.2M": a plain count for tiles and bar tooltips. Empty value gives an en dash. */
export function formatCount(v) {
  if (v === null || v === undefined || v === '' || !Number.isFinite(Number(v))) return '–';
  const n = Number(v);
  if (Math.abs(n) >= 1e6) {
    return new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(n);
  }
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 }).format(n);
}

/**
 * A duration given in hours, as people say it: "45 min", "2 h", "1 d 2 h", "12 d". Whole hours under a day,
 * days and remaining hours otherwise (hours dropped when zero). Missing or negative gives an en dash.
 */
export function formatDuration(hours) {
  if (hours === null || hours === undefined || hours === '' || !Number.isFinite(Number(hours)) || Number(hours) < 0) {
    return '–';
  }
  const h = Number(hours);
  if (h === 0) return '0 h';
  if (h < 1) return `${Math.max(1, Math.round(h * 60))} min`;
  const total = Math.round(h);
  const days = Math.floor(total / 24);
  const rest = total % 24;
  if (days === 0) return `${rest} h`;
  const d = formatCount(days);
  return rest === 0 ? `${d} d` : `${d} d ${rest} h`;
}
