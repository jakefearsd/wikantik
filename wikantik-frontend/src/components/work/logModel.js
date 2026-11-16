// Pure helpers for the assignment log page: which event types a filter means, how renewals fold, day headings.

/** The kinds of event the "Kind" filter offers; each is a set of server event types. */
export const KINDS = {
  claims: { label: 'Claims', types: ['claimed', 'claim_renewed', 'released', 'claim_expired'] },
  assignments: { label: 'Assignments and hand-offs', types: ['assigned', 'handoff', 'member'] },
  status: { label: 'Status changes', types: ['status'] },
};

/** The `types` the server is asked for: the kind's, or undefined (the whole log) for no kind. */
export const typesFor = (kind) => KINDS[kind]?.types;

const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

/** "Today", "Yesterday" or "Mon, Nov 16" (with the year when it is not this year), in the viewer's local time. */
export function dayLabel(iso, now = new Date()) {
  const d = new Date(iso);
  if (!iso || Number.isNaN(d.getTime())) return 'Unknown date';
  const ref = new Date(now);
  if (sameDay(d, ref)) return 'Today';
  const yesterday = new Date(ref);
  yesterday.setDate(yesterday.getDate() - 1);
  if (sameDay(d, yesterday)) return 'Yesterday';
  const opts = { weekday: 'short', month: 'short', day: 'numeric' };
  if (d.getFullYear() !== ref.getFullYear()) opts.year = 'numeric';
  return d.toLocaleDateString('en-US', opts);
}

/** Events (newest first) split into consecutive local days: [{ label, rows }]. */
export function groupByDay(rows, now = new Date()) {
  const groups = [];
  for (const row of rows) {
    const at = row.event.at;
    const label = dayLabel(at, now);
    const last = groups[groups.length - 1];
    if (last && last.label === label && last.dayKey === dayKey(at)) last.rows.push(row);
    else groups.push({ label, dayKey: dayKey(at), rows: [row] });
  }
  return groups;
}

function dayKey(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? 'x' : `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

/**
 * Rows for the page. With `fold` false every event is its own row. With `fold` true, renewals of one claim (the
 * same item and agent, with no other event on that item between them) become one row, positioned where the newest
 * renewal is; that row carries the newest renewal as `event` and the run's size as `count`. Nothing but renewals is
 * ever folded. `folded` is how many renewal events no longer have a row of their own.
 * @returns {{ rows: {event: object, count: number}[], folded: number }}
 */
export function foldRenewals(events, fold) {
  const rows = [];
  let folded = 0;
  const open = new Map();   // itemKey -> the renewal row currently being extended, for the agent that renewed
  for (const event of events) {
    const item = event.itemId ?? event.itemKey;
    if (event.type === 'claim_renewed' && fold) {
      const run = open.get(item);
      if (run && run.event.agentId === event.agentId) {
        run.count += 1;
        folded += 1;
        continue;
      }
      const row = { event, count: 1 };
      open.set(item, row);
      rows.push(row);
      continue;
    }
    if (item != null) open.delete(item);
    rows.push({ event, count: 1 });
  }
  return { rows, folded };
}
