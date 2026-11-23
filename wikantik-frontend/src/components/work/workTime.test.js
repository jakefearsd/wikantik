import { describe, it, expect } from 'vitest';
import { clockText, fullTime, isPast, whenText } from './workTime';

const at = (d, h, m) => new Date(2026, 10, d, h, m).toISOString();

describe('workTime', () => {
  it('clockText omits the date on the reference day and adds it otherwise', () => {
    expect(clockText(at(16, 9, 5), at(16, 1, 0))).toBe('09:05');
    expect(clockText(at(17, 15, 40), at(16, 14, 0))).toBe('Nov 17 15:40');
    expect(clockText(at(16, 0, 0), at(16, 23, 59))).toBe('00:00');
  });
  it('is empty for a missing or unparseable instant', () => {
    expect(clockText(null, new Date())).toBe('');
    expect(clockText('nonsense', new Date())).toBe('');
    expect(fullTime(null)).toBe('');
  });
  it('isPast compares against the injected clock', () => {
    expect(isPast(at(16, 13, 0), new Date(2026, 10, 16, 14, 0))).toBe(true);
    expect(isPast(at(16, 15, 0), new Date(2026, 10, 16, 14, 0))).toBe(false);
    expect(isPast(null)).toBe(false);
  });

  it('clockText always shows the date when there is no usable reference day', () => {
    expect(clockText(at(16, 9, 5))).toBe('Nov 16 09:05');
    expect(clockText(at(16, 9, 5), 'nonsense')).toBe('Nov 16 09:05');
  });
  it('whenText adds the year only outside the current year and is empty for bad input', () => {
    expect(whenText(at(16, 13, 0), new Date(2026, 10, 20))).toBe('Nov 16 13:00');
    expect(whenText(new Date(2025, 11, 31, 8, 5).toISOString(), new Date(2026, 10, 20))).toBe('Dec 31 2025 08:05');
    expect(whenText(null)).toBe('');
    expect(whenText('nonsense')).toBe('');
  });

  it('writes a sprint range once, with the year on the first day only when the years differ', async () => {
    const { dayRangeText } = await import('./workTime');
    expect(dayRangeText('2026-11-23', '2026-12-06')).toBe('Nov 23 – Dec 6 2026');
    expect(dayRangeText('2026-12-28', '2027-01-10')).toBe('Dec 28 2026 – Jan 10 2027');
    expect(dayRangeText('soon', '2026-12-06')).toBe('soon – Dec 6 2026');
    expect(dayRangeText(null, null)).toBe(' – ');
  });
});
