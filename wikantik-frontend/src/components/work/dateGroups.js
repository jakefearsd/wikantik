// Pure date grouping for the Dates view. Due dates are ISO calendar dates (YYYY-MM-DD) and are compared as
// strings against the viewer's local "today", never as timestamps, so a due date cannot shift across time zones.

const pad = (n) => String(n).padStart(2, '0');

/** The viewer's local calendar date as YYYY-MM-DD. */
export function todayIso(now = new Date()) {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** The Sunday on or after `iso` (today itself when it is a Sunday). Calendar arithmetic only, via UTC. */
export function comingSunday(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  date.setUTCDate(date.getUTCDate() + ((7 - date.getUTCDay()) % 7));
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
}

const byDue = (a, b) => (a.dueOn < b.dueOn ? -1 : a.dueOn > b.dueOn ? 1 : 0);

/** @returns {{id: string, label: string, items: object[]}[]} Overdue, This week, Later, No date. */
export function groupByDue(items, today) {
  const sunday = comingSunday(today);
  const groups = { overdue: [], week: [], later: [], none: [] };
  for (const item of items) {
    if (!item.dueOn) groups.none.push(item);
    else if (item.dueOn < today) groups.overdue.push(item);
    else if (item.dueOn <= sunday) groups.week.push(item);
    else groups.later.push(item);
  }
  groups.overdue.sort(byDue);
  groups.week.sort(byDue);
  groups.later.sort(byDue);
  return [
    { id: 'overdue', label: 'Overdue', items: groups.overdue },
    { id: 'week', label: 'This week', items: groups.week },
    { id: 'later', label: 'Later', items: groups.later },
    { id: 'none', label: 'No date', items: groups.none },
  ];
}
