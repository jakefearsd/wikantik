import { describe, it, expect } from 'vitest';
import { activeSprint, openSprints, boardSprintParam, formatPoints, idealLine, isHalfPoint } from './sprintModel';

const planned = { id: 1, name: 'A', state: 'planned' };
const active = { id: 2, name: 'B', state: 'active' };
const closed = { id: 3, name: 'C', state: 'closed' };

describe('sprintModel', () => {
  it('activeSprint finds the active sprint or null', () => {
    expect(activeSprint([planned, active, closed])).toBe(active);
    expect(activeSprint([planned, closed])).toBeNull();
    expect(activeSprint(undefined)).toBeNull();
  });

  it('openSprints keeps planned and active', () => {
    expect(openSprints([planned, active, closed])).toEqual([planned, active]);
    expect(openSprints(undefined)).toEqual([]);
  });

  it('boardSprintParam: all sends none, absent defaults to active when one exists, else the value', () => {
    expect(boardSprintParam('all', [active])).toBeUndefined();
    expect(boardSprintParam('', [active])).toBe('active');
    expect(boardSprintParam(null, [planned])).toBeUndefined();
    expect(boardSprintParam('', [])).toBeUndefined();
    expect(boardSprintParam('none', [active])).toBe('none');
    expect(boardSprintParam('7', [active])).toBe('7');
    expect(boardSprintParam('active', [])).toBe('active');
  });

  it('formatPoints drops a trailing .0', () => {
    expect(formatPoints(3.0)).toBe('3');
    expect(formatPoints(2.5)).toBe('2.5');
    expect(formatPoints(0)).toBe('0');
    expect(formatPoints(null)).toBe('');
    expect(formatPoints(undefined)).toBe('');
    expect(formatPoints('abc')).toBe('');
  });

  it('isHalfPoint accepts whole and half points from 0 to 999.5', () => {
    for (const ok of [0, 1, 2.5, '3', '0.5', 999.5]) expect(isHalfPoint(ok), String(ok)).toBe(true);
    for (const bad of [1.25, -1, 1000, 'x', '', null, undefined, NaN, Infinity]) expect(isHalfPoint(bad), String(bad)).toBe(false);
  });

  it('idealLine runs one point per day, linear to 0 on the last day', () => {
    const line = idealLine(8, '2026-11-23', '2026-11-27');
    expect(line.map((p) => p.date)).toEqual(['2026-11-23', '2026-11-24', '2026-11-25', '2026-11-26', '2026-11-27']);
    expect(line.map((p) => p.points)).toEqual([8, 6, 4, 2, 0]);
  });

  it('idealLine crosses a month and a DST change without skipping a day', () => {
    const line = idealLine(6, '2026-10-29', '2026-11-03');
    expect(line.map((p) => p.date)).toEqual(['2026-10-29', '2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02', '2026-11-03']);
  });

  it('idealLine handles a one-day sprint, a bad range and missing dates', () => {
    expect(idealLine(5, '2026-11-23', '2026-11-23')).toEqual([{ date: '2026-11-23', points: 0 }]);
    expect(idealLine(5, '2026-11-24', '2026-11-23')).toEqual([]);
    expect(idealLine(5, null, '2026-11-23')).toEqual([]);
    expect(idealLine(null, '2026-11-23', '2026-11-24').map((p) => p.points)).toEqual([0, 0]);
  });
});
