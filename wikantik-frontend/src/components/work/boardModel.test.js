import { describe, it, expect } from 'vitest';
import { STATUSES, DEFAULT_STATUSES, STATUS_LABELS, orderColumns, rankForInsert, addToColumns, moveInColumns, revertCard, groupByEpic, isOverdue } from './boardModel';

const item = (id, rank, extra = {}) => ({ id, key: `P-${id}`, rank, status: 'ready', level: 'task', ...extra });

describe('rankForInsert', () => {
  it('uses the midpoint between the neighbours', () => {
    expect(rankForInsert([item(1, 1024), item(2, 2048)], 1)).toBe(1536);
  });
  it('goes 1024 above the first card at the top', () => {
    expect(rankForInsert([item(1, 1024), item(2, 2048)], 0)).toBe(0);
  });
  it('omits the rank at the bottom and in an empty column', () => {
    expect(rankForInsert([item(1, 1024)], 1)).toBeUndefined();
    expect(rankForInsert([], 0)).toBeUndefined();
  });
});

describe('rankForInsert append', () => {
  it('gives an explicit rank past the last card when asked', () => {
    expect(rankForInsert([item(1, 1024), item(2, 2048)], 2, { append: true })).toBe(3072);
    expect(rankForInsert([], 0, { append: true })).toBeUndefined();
  });
});

describe('addToColumns', () => {
  it('appends to the matching column and bumps its total', () => {
    const cols = [{ status: 'draft', total: 1, items: [item(1, 1)] }, { status: 'done', total: 0, items: [] }];
    const next = addToColumns(cols, item(2, 2, { status: 'draft' }));
    expect(next[0].items.map((i) => i.id)).toEqual([1, 2]);
    expect(next[0].total).toBe(2);
    expect(next[1]).toBe(cols[1]);
  });
});

describe('orderColumns', () => {
  const cols = [{ status: 'done', total: 1, items: [] }, { status: 'draft', total: 0, items: [] },
    { status: 'ready', total: 0, items: [] }];
  it('orders by the fixed status order and fills missing statuses', () => {
    expect(orderColumns(cols, true).map((c) => c.status)).toEqual(STATUSES);
  });
  it('hides draft and cancelled unless asked', () => {
    expect(orderColumns(cols, false).map((c) => c.status)).toEqual(DEFAULT_STATUSES);
    expect(DEFAULT_STATUSES).toEqual(['refining', 'ready', 'in_progress', 'review', 'done']);
    expect(STATUS_LABELS.in_progress).toBe('In progress');
  });
});

describe('moveInColumns', () => {
  const cols = [
    { status: 'ready', total: 2, items: [item(1, 1), item(2, 2)] },
    { status: 'review', total: 0, items: [] },
  ];
  it('moves a card across columns, retags status and adjusts totals', () => {
    const next = moveInColumns(cols, 'P-1', 'review', 0, 5);
    expect(next[0].items.map((i) => i.id)).toEqual([2]);
    expect(next[0].total).toBe(1);
    expect(next[1].items[0]).toMatchObject({ id: 1, status: 'review', rank: 5 });
    expect(next[1].total).toBe(1);
    expect(cols[0].items).toHaveLength(2);
  });
  it('keeps totals when reordering inside one column', () => {
    const next = moveInColumns(cols, 'P-1', 'ready', 1);
    expect(next[0].items.map((i) => i.id)).toEqual([2, 1]);
    expect(next[0].total).toBe(2);
  });
});

describe('revertCard', () => {
  it('puts only that card back, by rank, leaving the rest as they are', () => {
    const a = item(1, 1); const b = item(2, 2); const c = item(3, 3);
    let cols = [{ status: 'ready', total: 3, items: [a, b, c] }, { status: 'review', total: 0, items: [] }];
    cols = moveInColumns(cols, 'P-2', 'review', 0, 9);
    cols = moveInColumns(cols, 'P-3', 'review', 1);
    const reverted = revertCard(cols, b);
    expect(reverted[0].items.map((i) => i.id)).toEqual([1, 2]);
    expect(reverted[0].items[1]).toMatchObject({ rank: 2, status: 'ready' });
    expect(reverted[1].items.map((i) => i.id)).toEqual([3]);
    expect(reverted[0].total + reverted[1].total).toBe(3);
  });
});

describe('groupByEpic', () => {
  const epic = item(10, 1, { level: 'epic', title: 'Big', status: 'in_progress' });
  const story = item(11, 2, { level: 'story', parentId: 10 });
  const task = item(12, 3, { level: 'task', parentId: 11 });
  const loose = item(13, 4);
  const all = [epic, story, task, loose];
  it('groups epics, their stories and tasks together, loose items last', () => {
    const groups = groupByEpic([task, loose, story, epic], all);
    expect(groups.map((g) => g.title)).toEqual(['Big', 'No epic']);
    expect(groups[0].items.map((i) => i.id)).toEqual([12, 11, 10]);
    expect(groups[1].items.map((i) => i.id)).toEqual([13]);
  });
});

describe('isOverdue', () => {
  it('is true only for a past due date on an open item', () => {
    const now = new Date('2026-11-15T12:00:00Z');
    expect(isOverdue({ dueOn: '2026-11-14', status: 'ready' }, now)).toBe(true);
    expect(isOverdue({ dueOn: '2026-11-15', status: 'ready' }, now)).toBe(false);
    expect(isOverdue({ dueOn: '2026-11-01', status: 'done' }, now)).toBe(false);
    expect(isOverdue({ dueOn: '2026-11-01', status: 'cancelled' }, now)).toBe(false);
    expect(isOverdue({ dueOn: null, status: 'ready' }, now)).toBe(false);
  });
});

describe('orderColumns with no data', () => {
  it('still gives every default column, empty, when the server sent nothing', () => {
    const cols = orderColumns(undefined, false);
    expect(cols.map((c) => c.status)).toEqual(DEFAULT_STATUSES);
    expect(cols.every((c) => c.total === 0 && c.items.length === 0)).toBe(true);
  });
});

describe('rankForInsert edges', () => {
  it('gives a card dropped at the top, above a first card of rank 0, a negative rank (-1024)', () => {
    expect(rankForInsert([item(1, 0), item(2, 5)], 0)).toBe(-1024);
  });
  it('treats a negative index as the top', () => {
    expect(rankForInsert([item(1, 1024)], -3)).toBe(0);
  });
  it('treats an index past the end as the bottom', () => {
    expect(rankForInsert([item(1, 1024)], 9)).toBeUndefined();
    expect(rankForInsert([item(1, 1024)], 9, { append: true })).toBe(2048);
  });
  it('gives a card dropped between two cards of equal rank that same rank; the server then orders the tie by rank then id, not by where it was dropped', () => {
    const rank = rankForInsert([item(1, 1000), item(2, 1000)], 1);
    expect(rank).toBe(1000);
  });
});

describe('a card moved to a status the board has no column for', () => {
  const cols = [{ status: 'ready', total: 1, items: [item(1, 1)] }, { status: 'done', total: 0, items: [] }];
  it('gets a new column, placed in the fixed status order', () => {
    const next = moveInColumns(cols, 'P-1', 'review', 0);
    expect(next.map((c) => c.status)).toEqual(['ready', 'review', 'done']);
    expect(next[1]).toMatchObject({ status: 'review', total: 1 });
    expect(next[1].items[0]).toMatchObject({ id: 1, status: 'review' });
    expect(next[0].total).toBe(0);
  });
  it('is also what a newly created item gets when its status has no column', () => {
    const next = addToColumns(cols, item(9, 9, { status: 'refining' }));
    expect(next.map((c) => c.status)).toEqual(['refining', 'ready', 'done']);
    expect(next[0].items.map((i) => i.id)).toEqual([9]);
  });
});

describe('moveInColumns edges', () => {
  const cols = [
    { status: 'ready', total: 2, items: [item(1, 1), item(2, 2)] },
    { status: 'review', total: 1, items: [item(3, 3, { status: 'review' })] },
  ];
  it('returns the very same columns when the card is not on the board', () => {
    expect(moveInColumns(cols, 'P-99', 'review', 0)).toBe(cols);
  });
  it('keeps the card rank when none is given', () => {
    const next = moveInColumns(cols, 'P-1', 'review', 0);
    expect(next[1].items[0]).toMatchObject({ id: 1, status: 'review', rank: 1 });
  });
  it('clamps a negative index to the top and a large one to the bottom', () => {
    expect(moveInColumns(cols, 'P-1', 'review', -4)[1].items.map((i) => i.id)).toEqual([1, 3]);
    expect(moveInColumns(cols, 'P-1', 'review', 40)[1].items.map((i) => i.id)).toEqual([3, 1]);
  });
  it('never takes a total below zero when the source column total is stale', () => {
    const stale = [{ status: 'ready', total: 0, items: [item(1, 1)] }, { status: 'review', total: 0, items: [] }];
    expect(moveInColumns(stale, 'P-1', 'review', 0)[0].total).toBe(0);
  });
});

describe('revertCard edges', () => {
  it('puts the card back at the top when its old rank is below every card now in the column', () => {
    const a = item(1, 10); const b = item(2, 20); const c = item(3, 30);
    const cols = [{ status: 'ready', total: 2, items: [b, c] }, { status: 'review', total: 1, items: [{ ...a, status: 'review' }] }];
    const reverted = revertCard(cols, a);
    expect(reverted[0].items.map((i) => i.id)).toEqual([1, 2, 3]);
    expect(reverted[1].items).toEqual([]);
  });
  it('puts the card back at the bottom when its old rank is above every other card', () => {
    const z = item(9, 99);
    const cols = [{ status: 'ready', total: 1, items: [item(1, 1)] }, { status: 'review', total: 1, items: [{ ...z, status: 'review' }] }];
    expect(revertCard(cols, z)[0].items.map((i) => i.id)).toEqual([1, 9]);
  });
});

describe('groupByEpic edges', () => {
  const epic = item(10, 1, { level: 'epic', title: 'Big' });
  it('puts items whose parent is not on the board under "Other epic", before "No epic"', () => {
    const a = item(20, 2, { level: 'story', parentId: 777 });
    const b = item(21, 3, { level: 'task', parentId: 777 });
    const loose = item(22, 4);
    const groups = groupByEpic([loose, a, b], [loose, a, b]);
    expect(groups.map((g) => [g.id, g.title, g.items.map((i) => i.id)])).toEqual([
      ['other', 'Other epic', [20, 21]], ['none', 'No epic', [22]],
    ]);
  });
  it('treats a task under a story that has no epic as having no epic', () => {
    const story = item(11, 2, { level: 'story', parentId: null });
    const task = item(12, 3, { level: 'task', parentId: 11 });
    const groups = groupByEpic([task, story], [task, story]);
    expect(groups).toHaveLength(1);
    expect(groups[0]).toMatchObject({ id: 'none', title: 'No epic' });
    expect(groups[0].items.map((i) => i.id)).toEqual([12, 11]);
  });
  it('treats a task whose grandparent is not an epic as having no epic', () => {
    const top = item(30, 1, { level: 'story' });
    const mid = item(31, 2, { level: 'story', parentId: 30 });
    const task = item(32, 3, { level: 'task', parentId: 31 });
    expect(groupByEpic([task], [top, mid, task])[0].id).toBe('none');
  });
  it('keeps several stories of one epic together in the order given', () => {
    const s1 = item(41, 2, { level: 'story', parentId: 10 });
    const s2 = item(42, 3, { level: 'story', parentId: 10 });
    const groups = groupByEpic([s1, s2], [epic, s1, s2]);
    expect(groups).toHaveLength(1);
    expect(groups[0]).toMatchObject({ id: 10, title: 'Big' });
    expect(groups[0].items.map((i) => i.id)).toEqual([41, 42]);
  });
});

describe('isOverdue with the real clock', () => {
  it('is true for a date long past and false for a date far ahead', () => {
    expect(isOverdue({ dueOn: '2000-01-01', status: 'ready' })).toBe(true);
    expect(isOverdue({ dueOn: '2999-01-01', status: 'ready' })).toBe(false);
  });
});
