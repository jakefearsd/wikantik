import { describe, it, expect } from 'vitest';
import { groupByDue, todayIso, comingSunday } from './dateGroups';

const it_ = (key, dueOn, extra = {}) => ({ key, dueOn, status: 'ready', ...extra });
// 2026-11-15 is a Sunday; 2026-11-12 is a Thursday.
const ids = (g, id) => g.find((x) => x.id === id).items.map((i) => i.key);

describe('comingSunday', () => {
  it('is the same day when today is a Sunday', () => expect(comingSunday('2026-11-15')).toBe('2026-11-15'));
  it('is the next Sunday from a Thursday', () => expect(comingSunday('2026-11-12')).toBe('2026-11-15'));
  it('crosses a month boundary', () => expect(comingSunday('2026-12-30')).toBe('2027-01-03'));
});

describe('todayIso', () => {
  it('uses the local calendar date', () => expect(todayIso(new Date(2026, 10, 5, 23, 59))).toBe('2026-11-05'));
});

describe('groupByDue', () => {
  const today = '2026-11-12';
  it('always returns the four groups in order', () => {
    expect(groupByDue([], today).map((g) => g.id)).toEqual(['overdue', 'week', 'later', 'none']);
  });
  it('due yesterday is overdue, oldest first', () => {
    const g = groupByDue([it_('B', '2026-11-11'), it_('A', '2026-10-01')], today);
    expect(ids(g, 'overdue')).toEqual(['A', 'B']);
  });
  it('due today and due this Sunday are this week', () => {
    const g = groupByDue([it_('T', '2026-11-12'), it_('S', '2026-11-15')], today);
    expect(ids(g, 'week')).toEqual(['T', 'S']);
    expect(ids(g, 'overdue')).toEqual([]);
  });
  it('due next Monday is later, soonest first', () => {
    const g = groupByDue([it_('Z', '2027-01-01'), it_('M', '2026-11-16')], today);
    expect(ids(g, 'later')).toEqual(['M', 'Z']);
  });
  it('no date goes last', () => {
    const g = groupByDue([it_('N', null), it_('U', undefined)], today);
    expect(ids(g, 'none')).toEqual(['N', 'U']);
  });
  it('when today is a Sunday, this week is only today and next Monday is later', () => {
    const g = groupByDue([it_('T', '2026-11-15'), it_('M', '2026-11-16')], '2026-11-15');
    expect(ids(g, 'week')).toEqual(['T']);
    expect(ids(g, 'later')).toEqual(['M']);
  });
});
