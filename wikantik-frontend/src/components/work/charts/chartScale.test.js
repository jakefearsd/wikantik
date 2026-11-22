import { describe, it, expect } from 'vitest';
import { linearScale, niceTicks, formatDuration, formatCount, finiteOr } from './chartScale';

describe('niceTicks', () => {
  it('gives [0,1] for zero, negative and non-finite maxima', () => {
    [0, -3, NaN, Infinity, null, undefined, 'x'].forEach((m) => expect(niceTicks(m)).toEqual([0, 1]));
  });
  it('uses steps of 1, 2 or 5 times a power of ten', () => {
    expect(niceTicks(7)).toEqual([0, 2, 4, 6, 8]);
    expect(niceTicks(100)).toEqual([0, 50, 100]);
    expect(niceTicks(1)).toEqual([0, 0.5, 1]);
  });
  it('ends at or above a fractional maximum without float noise', () => {
    const t = niceTicks(0.5, 4);
    expect(t[0]).toBe(0);
    expect(t[t.length - 1]).toBeGreaterThanOrEqual(0.5);
    expect(t.every((v) => String(v).length < 8)).toBe(true);
  });
  it('never repeats a tick and stays finite for huge values', () => {
    [0.3, 3, 99, 12345, 1e12, 1e300, Number.MAX_VALUE].forEach((m) => {
      const t = niceTicks(m);
      expect(new Set(t).size).toBe(t.length);
      expect(t.every(Number.isFinite)).toBe(true);
      expect(t[t.length - 1]).toBeGreaterThanOrEqual(m);
    });
  });
});

describe('linearScale', () => {
  it('maps the domain onto the range', () => {
    expect(linearScale([0, 10], [100, 0])(5)).toBe(50);
  });
  it('is finite for a degenerate domain and for bad values', () => {
    expect(linearScale([3, 3], [0, 10])(3)).toBe(5);
    expect(linearScale([0, 10], [0, 100])(NaN)).toBe(0);
    expect(linearScale([0, 10], [0, 100])(null)).toBe(0);
    expect(Number.isFinite(linearScale([0, 1e-320], [0, 1e308])(1e308))).toBe(true);
  });
});

describe('formatDuration', () => {
  it('speaks in minutes, hours and days', () => {
    expect(formatDuration(0.5)).toBe('30 min');
    expect(formatDuration(0)).toBe('0 h');
    expect(formatDuration(2)).toBe('2 h');
    expect(formatDuration(26)).toBe('1 d 2 h');
    expect(formatDuration(48)).toBe('2 d');
  });
  it('shows an en dash for a missing or invalid duration', () => {
    [null, undefined, '', NaN, -1, Infinity].forEach((v) => expect(formatDuration(v)).toBe('–'));
  });
});

describe('formatCount and finiteOr', () => {
  it('formats counts and tolerates junk', () => {
    expect(formatCount(1234)).toBe('1,234');
    expect(formatCount(12.34)).toBe('12.3');
    expect(formatCount(2500000)).toBe('2.5M');
    expect(formatCount(null)).toBe('–');
    expect(finiteOr('7')).toBe(7);
    expect(finiteOr(null, 5)).toBe(5);
    expect(finiteOr(true, 5)).toBe(5);
  });
});
