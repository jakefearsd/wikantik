import { describe, it, expect } from 'vitest';
import {
  durationText, partialWeek, paramsFrom, percent, sampleText, toSearch, todayIso, validDate, weekLabel, windowError,
} from './flowModel';
import { dayText } from './workTime';

describe('flowModel', () => {
  it('formats durations through formatDuration and shows an en dash for none', () => {
    expect(durationText(null)).toBe('–');
    expect(durationText(undefined)).toBe('–');
    expect(durationText(2700)).toBe('45 min');
    expect(durationText(691200)).toBe('8 d');
    expect(durationText(Number.NaN)).toBe('–');
  });
  it('never turns a rate over nothing into 0%', () => {
    expect(percent({ numerator: 0, denominator: 0 })).toBe('–');
    expect(percent({ numerator: 4, denominator: 7 })).toBe('57%');
    expect(percent({ numerator: 0, denominator: 3 })).toBe('0%');
    expect(percent(null)).toBe('–');
  });
  it('labels a week', () => {
    expect(weekLabel('2026-11-16')).toBe('16 Nov');
    expect(weekLabel('bad')).toBe('');
  });
  it('marks a week the window cuts or that has not finished', () => {
    expect(partialWeek('2026-11-02', '2026-11-04', '2026-11-30')).toBe(true);
    expect(partialWeek('2026-11-09', '2026-11-04', '2026-11-30')).toBe(false);
    expect(partialWeek('2026-11-23', '2026-11-04', '2026-11-25')).toBe(true);
    expect(partialWeek('2026-11-16', '2026-11-04', '2026-11-30', '2026-11-18')).toBe(true);
    expect(partialWeek('2026-11-09', '2026-11-04', '2026-11-30', '2026-11-18')).toBe(false);
  });
  it('validates dates and windows like the route', () => {
    expect(validDate('2026-02-29')).toBe(false);
    expect(validDate('1969-12-31')).toBe(false);
    expect(validDate('2026-11-21')).toBe(true);
    expect(windowError({})).toBeNull();
    expect(windowError({ from: '2026-11-01' })).toBeNull();
    expect(windowError({ from: 'x' })).toMatch(/Start/);
    expect(windowError({ to: '10000-01-01' })).toMatch(/End/);
    expect(windowError({ from: '2026-11-10', to: '2026-11-01' })).toBe('The start date is after the end date.');
    expect(windowError({ from: '2025-01-01', to: '2026-12-31' })).toMatch(/at most 366 days/);
    expect(windowError({ from: '2025-11-21', to: '2026-11-21' })).toBeNull(); // 366 days inclusive
    expect(windowError({ from: '2025-11-20', to: '2026-11-21' })).toMatch(/at most 366 days/);
  });
  it('round-trips the window through URL params', () => {
    const sp = toSearch({ from: '2026-11-02', to: '2026-11-15', label: '', sprint: ' 3 ' });
    expect(sp.toString()).toBe('from=2026-11-02&to=2026-11-15&sprint=3');
    expect(paramsFrom(new URLSearchParams(sp.toString()), ['from', 'to', 'sprint', 'label']))
      .toEqual({ from: '2026-11-02', to: '2026-11-15', sprint: '3' });
  });
  it('states sample sizes and today', () => {
    expect(sampleText(1, 'item')).toBe('from 1 item');
    expect(sampleText(7, 'deployment')).toBe('from 7 deployments');
    expect(todayIso(new Date(2026, 10, 5))).toBe('2026-11-05');
    expect(dayText('2026-11-16')).toBe('Nov 16 2026');
    expect(dayText('nope')).toBe('');
  });
});
