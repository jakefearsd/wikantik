// Pure board logic: column ordering, rank arithmetic, optimistic moves and epic grouping.

export const STATUSES = ['draft', 'refining', 'ready', 'in_progress', 'review', 'done', 'cancelled'];
export const DEFAULT_STATUSES = ['refining', 'ready', 'in_progress', 'review', 'done'];
export const STATUS_LABELS = {
  draft: 'Draft', refining: 'Refining', ready: 'Ready', in_progress: 'In progress',
  review: 'Review', done: 'Done', cancelled: 'Cancelled',
};
const RANK_STEP = 1024;

/** The seven columns in status order (missing ones empty); draft and cancelled only when showAll. */
export function orderColumns(columns, showAll) {
  const byStatus = new Map((columns || []).map((c) => [c.status, c]));
  return (showAll ? STATUSES : DEFAULT_STATUSES)
    .map((s) => byStatus.get(s) || { status: s, total: 0, items: [] });
}

/**
 * The rank for a card dropped at `index` in a column whose other cards are `others` (the moved card
 * excluded). Midpoint between neighbours; first rank minus 1024 at the top; undefined at the bottom
 * (the server appends) unless `append` asks for an explicit rank 1024 past the last card, which a
 * same-column move to the bottom needs because its status does not change.
 */
export function rankForInsert(others, index, { append = false } = {}) {
  if (!others.length) return undefined;
  if (index >= others.length) return append ? others[others.length - 1].rank + RANK_STEP : undefined;
  if (index <= 0) return others[0].rank - RANK_STEP;
  return (others[index - 1].rank + others[index].rank) / 2;
}

/**
 * A copy of columns with just this card put back where `original` (the card as it was before a move) had it:
 * its old status, at the position its old rank gives. Every other card is left as it is now.
 */
export function revertCard(columns, original) {
  const target = columns.find((c) => c.status === original.status);
  const others = (target?.items || []).filter((i) => i.key !== original.key);
  const hit = others.findIndex((i) => i.rank > original.rank);
  return moveInColumns(columns, original.key, original.status, hit < 0 ? others.length : hit, original.rank);
}

/** A copy of columns with a newly created item appended to the column of its status. */
export function addToColumns(columns, item) {
  return columns.map((c) => (c.status === item.status
    ? { ...c, items: [...c.items, item], total: c.total + 1 } : c));
}

/** A copy of columns with the card moved to `status` at `index` (optionally re-ranked). */
export function moveInColumns(columns, key, status, index, rank) {
  let moved = null;
  let from = null;
  const stripped = columns.map((c) => {
    const found = c.items.find((i) => i.key === key);
    if (!found) return c;
    moved = found;
    from = c.status;
    return { ...c, items: c.items.filter((i) => i.key !== key) };
  });
  if (!moved) return columns;
  const updated = { ...moved, status, ...(rank !== undefined ? { rank } : {}) };
  return stripped.map((c) => {
    let col = c;
    if (c.status === from && from !== status) col = { ...col, total: Math.max(0, c.total - 1) };
    if (c.status === status) {
      const items = col.items.slice();
      items.splice(Math.min(Math.max(index, 0), items.length), 0, updated);
      col = { ...col, items, total: from !== status ? c.total + 1 : c.total };
    }
    return col;
  });
}

/**
 * Groups cards by their epic (an epic's own card, its stories and their tasks). `all` resolves parents.
 * Groups appear in order of first card; items with no epic are last under "No epic".
 */
export function groupByEpic(items, all) {
  const byId = new Map(all.map((i) => [i.id, i]));
  const epicOf = (i) => {
    if (i.level === 'epic') return i;
    const p = i.parentId != null ? byId.get(i.parentId) : null;
    if (!p) return null;
    if (p.level === 'epic') return p;
    const g = p.parentId != null ? byId.get(p.parentId) : null;
    return g && g.level === 'epic' ? g : null;
  };
  const groups = new Map();
  const loose = [];
  for (const i of items) {
    const epic = epicOf(i);
    if (!epic && i.parentId != null && byId.get(i.parentId) === undefined) {
      // Parent not on this board page: the epic cannot be named.
      let g = groups.get('other');
      if (!g) { g = { id: 'other', title: 'Other epic', items: [] }; groups.set('other', g); }
      g.items.push(i);
    } else if (!epic) {
      loose.push(i);
    } else {
      let g = groups.get(epic.id);
      if (!g) { g = { id: epic.id, title: epic.title, items: [] }; groups.set(epic.id, g); }
      g.items.push(i);
    }
  }
  const out = [...groups.values()];
  if (loose.length) out.push({ id: 'none', title: 'No epic', items: loose });
  return out;
}

const CLOSED = new Set(['done', 'cancelled']);

/** True when the due date (YYYY-MM-DD) is before today and the item is still open. */
export function isOverdue(item, now = new Date()) {
  if (!item.dueOn || CLOSED.has(item.status)) return false;
  const pad = (n) => String(n).padStart(2, '0');
  const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  return item.dueOn < today;
}
