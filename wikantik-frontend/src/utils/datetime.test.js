import { describe, it, expect } from 'vitest';
import { formatRelative, formatDate, formatDateTime, formatTime } from './datetime.js';

// Fixed reference point for deterministic relative-time tests.
// 2026-05-30T12:00:00Z  →  epoch ms = 1748606400000
const NOW = new Date('2026-05-30T12:00:00Z');
const NOW_MS = NOW.getTime();

// Helper: produce an ISO string that is `deltaMs` milliseconds before NOW.
const before = (deltaMs) => new Date(NOW_MS - deltaMs).toISOString();
// Helper: produce an ISO string `deltaMs` milliseconds AFTER NOW.
const after = (deltaMs) => new Date(NOW_MS + deltaMs).toISOString();

describe('formatRelative', () => {
  it('returns "just now" for a date 30 seconds ago', () => {
    expect(formatRelative(before(30_000), NOW)).toBe('just now');
  });

  it('returns "just now" for a date 0 seconds ago (exactly now)', () => {
    expect(formatRelative(before(0), NOW)).toBe('just now');
  });

  it('returns "1m ago" for a date 90 seconds ago', () => {
    expect(formatRelative(before(90_000), NOW)).toBe('1m ago');
  });

  it('returns "59m ago" for a date 59 minutes ago', () => {
    expect(formatRelative(before(59 * 60_000), NOW)).toBe('59m ago');
  });

  it('returns "2h ago" for a date 2 hours ago', () => {
    expect(formatRelative(before(2 * 3_600_000), NOW)).toBe('2h ago');
  });

  it('returns "23h ago" for a date 23 hours ago', () => {
    expect(formatRelative(before(23 * 3_600_000), NOW)).toBe('23h ago');
  });

  it('returns "3d ago" for a date 3 days ago', () => {
    expect(formatRelative(before(3 * 86_400_000), NOW)).toBe('3d ago');
  });

  it('returns "29d ago" for a date 29 days ago', () => {
    expect(formatRelative(before(29 * 86_400_000), NOW)).toBe('29d ago');
  });

  it('falls back to formatDate for a date 40 days ago', () => {
    const iso = before(40 * 86_400_000);
    expect(formatRelative(iso, NOW)).toBe(formatDate(iso));
  });

  it('returns "just now" for a future date (no negative values)', () => {
    expect(formatRelative(after(5 * 60_000), NOW)).toBe('just now');
  });

  it('returns "" for null', () => {
    expect(formatRelative(null, NOW)).toBe('');
  });

  it('returns "" for undefined', () => {
    expect(formatRelative(undefined, NOW)).toBe('');
  });

  it('returns "" for empty string', () => {
    expect(formatRelative('', NOW)).toBe('');
  });

  it('returns the original input unchanged for an invalid date string', () => {
    expect(formatRelative('not-a-date', NOW)).toBe('not-a-date');
  });

  it('accepts epoch-ms number as the `now` argument', () => {
    expect(formatRelative(before(30_000), NOW_MS)).toBe('just now');
  });
});

describe('formatDate', () => {
  it('returns "" for null', () => {
    expect(formatDate(null)).toBe('');
  });

  it('returns "" for undefined', () => {
    expect(formatDate(undefined)).toBe('');
  });

  it('returns "" for empty string', () => {
    expect(formatDate('')).toBe('');
  });

  it('returns the original string unchanged for an invalid date', () => {
    expect(formatDate('not-a-date')).toBe('not-a-date');
  });

  it('formats a valid ISO date string using toLocaleDateString en-US', () => {
    const iso = '2026-05-30T12:00:00Z';
    // Build expected via the same API so the assertion is TZ-agnostic.
    const expected = new Date(iso).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
    expect(formatDate(iso)).toBe(expected);
  });
});

describe('formatDateTime', () => {
  it('returns "" for null', () => {
    expect(formatDateTime(null)).toBe('');
  });

  it('returns "" for empty string', () => {
    expect(formatDateTime('')).toBe('');
  });

  it('returns the original string unchanged for an invalid date', () => {
    expect(formatDateTime('bad-input')).toBe('bad-input');
  });

  it('formats a valid ISO string using toLocaleString', () => {
    const iso = '2026-05-30T12:00:00Z';
    const expected = new Date(iso).toLocaleString();
    expect(formatDateTime(iso)).toBe(expected);
  });
});

describe('formatTime', () => {
  it('returns "" for null', () => {
    expect(formatTime(null)).toBe('');
  });

  it('returns "" for empty string', () => {
    expect(formatTime('')).toBe('');
  });

  it('returns the original string unchanged for an invalid date', () => {
    expect(formatTime('bad-input')).toBe('bad-input');
  });

  it('formats a valid ISO string using toLocaleTimeString', () => {
    const iso = '2026-05-30T12:00:00Z';
    const expected = new Date(iso).toLocaleTimeString();
    expect(formatTime(iso)).toBe(expected);
  });
});
