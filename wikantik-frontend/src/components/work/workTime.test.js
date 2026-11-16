import { describe, it, expect } from 'vitest';
import { clockText, fullTime, isPast } from './workTime';

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
});
