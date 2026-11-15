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
