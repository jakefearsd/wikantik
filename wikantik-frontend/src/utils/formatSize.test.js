import { describe, it, expect } from 'vitest';
import { formatSize } from './formatSize';

describe('formatSize', () => {
  it('writes bytes, kilobytes and megabytes', () => {
    expect(formatSize(512)).toBe('512 B');
    expect(formatSize(2048)).toBe('2.0 KB');
    expect(formatSize(3 * 1024 * 1024)).toBe('3.0 MB');
  });
});
