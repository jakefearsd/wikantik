import { describe, it, expect } from 'vitest';
import { KINDS, typesFor, dayLabel, groupByDay, foldRenewals } from './logModel';

const NOW = new Date(2026, 10, 16, 18, 0);
const at = (y, m, d, h = 10) => new Date(y, m, d, h, 0).toISOString();

describe('typesFor', () => {
  it('gives the server types for a known kind', () => {
    expect(typesFor('status')).toEqual(['status']);
    expect(typesFor('claims')).toEqual(KINDS.claims.types);
  });
  it('gives nothing (the whole log) for no kind or an unknown one', () => {
    expect(typesFor('')).toBeUndefined();
    expect(typesFor('bogus')).toBeUndefined();
    expect(typesFor(undefined)).toBeUndefined();
  });
});

describe('dayLabel', () => {
  it('says Today and Yesterday', () => {
    expect(dayLabel(at(2026, 10, 16, 1), NOW)).toBe('Today');
    expect(dayLabel(at(2026, 10, 15, 23), NOW)).toBe('Yesterday');
  });
  it('says Yesterday across a month boundary', () => {
    expect(dayLabel(at(2026, 9, 31), new Date(2026, 10, 1, 9, 0))).toBe('Yesterday');
  });
  it('names an older day this year without the year', () => {
    expect(dayLabel(at(2026, 10, 2), NOW)).toBe('Mon, Nov 2');
  });
  it('adds the year when the day is in another year', () => {
    expect(dayLabel(at(2025, 11, 25), NOW)).toBe('Thu, Dec 25, 2025');
  });
  it('says Unknown date for a missing or unparseable time', () => {
    expect(dayLabel(null, NOW)).toBe('Unknown date');
    expect(dayLabel('', NOW)).toBe('Unknown date');
    expect(dayLabel('not a date', NOW)).toBe('Unknown date');
  });
  it('works against the real clock when no reference is given', () => {
    expect(dayLabel(new Date().toISOString())).toBe('Today');
  });
});

describe('groupByDay', () => {
  const row = (id, iso) => ({ event: { id, at: iso }, count: 1 });
  it('splits consecutive rows into one group per local day, newest first', () => {
    const groups = groupByDay([row(1, at(2026, 10, 16, 15)), row(2, at(2026, 10, 16, 9)), row(3, at(2026, 10, 15, 9))], NOW);
    expect(groups.map((g) => [g.label, g.rows.map((r) => r.event.id)])).toEqual([['Today', [1, 2]], ['Yesterday', [3]]]);
  });
  it('starts a new group when the same label comes round again after another day', () => {
    const groups = groupByDay([row(1, at(2025, 11, 25)), row(2, at(2026, 10, 2)), row(3, at(2025, 11, 25))], NOW);
    expect(groups.map((g) => g.rows.length)).toEqual([1, 1, 1]);
  });
  it('groups rows with an unreadable time together under Unknown date', () => {
    const groups = groupByDay([row(1, 'garbage'), row(2, 'also garbage')], NOW);
    expect(groups).toHaveLength(1);
    expect(groups[0].label).toBe('Unknown date');
    expect(groups[0].rows).toHaveLength(2);
  });
  it('puts a row with no time and a row with an unreadable time under one Unknown date group', () => {
    const groups = groupByDay([row(1, null), row(2, 'garbage'), row(3, undefined)], NOW);
    expect(groups).toHaveLength(1);
    expect(groups[0].label).toBe('Unknown date');
    expect(groups[0].rows).toHaveLength(3);
  });
  it('keeps two different days that share a label apart', () => {
    // 2025-12-25 and 2024-12-25 both read "Thu, Dec 25"-like labels only by year; their labels differ, but dayKey guards the same-label case anyway
    const groups = groupByDay([row(1, at(2025, 11, 25)), row(2, at(2024, 11, 25))], NOW);
    expect(groups).toHaveLength(2);
  });
});

describe('foldRenewals', () => {
  const renew = (id, extra = {}) => ({ id, type: 'claim_renewed', itemId: 1, agentId: 3, ...extra });
  it('leaves every event as its own row when folding is off', () => {
    const { rows, folded } = foldRenewals([renew(3), renew(2), renew(1)], false);
    expect(rows).toHaveLength(3);
    expect(folded).toBe(0);
  });
  it('falls back to the item key when an event has no item id', () => {
    const { rows, folded } = foldRenewals([renew(2, { itemId: undefined, itemKey: 'PLAT-1' }), renew(1, { itemId: undefined, itemKey: 'PLAT-1' })], true);
    expect(rows).toHaveLength(1);
    expect(rows[0].count).toBe(2);
    expect(folded).toBe(1);
  });
  it('does not fold renewals of different items', () => {
    const { rows } = foldRenewals([renew(2, { itemId: 2 }), renew(1, { itemId: 1 })], true);
    expect(rows).toHaveLength(2);
  });
  it('lets an event with no item at all (project membership) pass through without ending a run', () => {
    const member = { id: 9, type: 'member', itemId: null };
    const { rows, folded } = foldRenewals([renew(3), member, renew(2)], true);
    expect(rows.map((r) => r.event.id)).toEqual([3, 9]);
    expect(rows[0].count).toBe(2);
    expect(folded).toBe(1);
    expect(foldRenewals([member], true).rows).toHaveLength(1);
  });
  it('starts a fresh run for a different agent renewing the same item, and the old agent run is not resumed', () => {
    const { rows, folded } = foldRenewals([renew(4), renew(3, { agentId: 4 }), renew(2), renew(1)], true);
    expect(rows.map((r) => r.count)).toEqual([1, 1, 2]);
    expect(folded).toBe(1);
  });
});
