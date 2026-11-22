// Pure helpers for the sprint views. A sprint is { id, name, state: planned|active|closed, startsOn, endsOn, … }.

export const activeSprint = (sprints) => (sprints || []).find((s) => s.state === 'active') || null;

/** Planned and active sprints: the ones an item may be planned into. */
export const openSprints = (sprints) => (sprints || []).filter((s) => s.state === 'planned' || s.state === 'active');

/**
 * What the board sends for its `sprint` URL parameter: `all` sends nothing; no value means the active sprint when
 * there is one (else nothing); anything else (`active`, `none`, an id) is sent as it is.
 */
export function boardSprintParam(urlValue, sprints) {
  if (urlValue === 'all') return undefined;
  if (!urlValue) return activeSprint(sprints) ? 'active' : undefined;
  return urlValue;
}

/** 3 -> "3", 2.5 -> "2.5", nothing -> "". */
export function formatPoints(n) {
  if (n == null || n === '' || !Number.isFinite(Number(n))) return '';
  return String(Number(n));
}

/** Whether `value` is an estimate the server takes: a whole or half point from 0 to 999.5. */
export function isHalfPoint(value) {
  if (value === null || value === undefined || String(value).trim() === '') return false;
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 && n <= 999.5 && Number.isInteger(n * 2);
}

const dayMs = 86400000;
const utc = (iso) => {
  const [y, m, d] = iso.split('-').map(Number);
  return Date.UTC(y, m - 1, d);
};

/** One { date, points } per day from startsOn to endsOn, falling in a straight line from the commitment to 0 on endsOn. */
export function idealLine(committedPoints, startsOn, endsOn) {
  if (!startsOn || !endsOn) return [];
  const from = utc(startsOn);
  const steps = Math.round((utc(endsOn) - from) / dayMs);
  if (steps < 0) return [];
  const total = Number(committedPoints) || 0;
  const line = [];
  for (let i = 0; i <= steps; i += 1) {
    line.push({
      date: new Date(from + i * dayMs).toISOString().slice(0, 10),
      points: steps === 0 ? 0 : total * (1 - i / steps),
    });
  }
  return line;
}
