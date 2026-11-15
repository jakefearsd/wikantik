import { describe, it, expect } from 'vitest';
import { lineDiff } from './lineDiff';

describe('lineDiff', () => {
  it('marks added and removed lines and keeps unchanged ones', () => {
    expect(lineDiff('a\nb\nc', 'a\nx\nc')).toEqual([
      { type: 'same', text: 'a' }, { type: 'del', text: 'b' }, { type: 'add', text: 'x' }, { type: 'same', text: 'c' },
    ]);
  });
  it('handles empty sides', () => {
    expect(lineDiff('', 'a')).toEqual([{ type: 'add', text: 'a' }]);
    expect(lineDiff('a', '')).toEqual([{ type: 'del', text: 'a' }]);
    expect(lineDiff('', '')).toEqual([]);
  });
  it('treats a huge input as a whole replacement rather than hanging', () => {
    const big = Array.from({ length: 3000 }, (_, i) => `l${i}`).join('\n');
    const out = lineDiff(big, `${big}\nmore`);
    expect(out.filter((l) => l.type === 'del')).toHaveLength(3000);
  });
});
