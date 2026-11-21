import { describe, it, expect } from 'vitest';
import { labelColor, LABEL_PALETTE } from './labelColor';

describe('labelColor', () => {
  it('the same name always gets the same colour', () => {
    expect(labelColor('ui')).toBe(labelColor('ui'));
    expect(labelColor('api')).toBe(labelColor('api'));
  });
  it('every colour is from the palette', () => {
    for (const n of ['ui', 'api', 'a', 'release-1.2', 'x_y', 'zzzzzzzz', '0']) {
      expect(LABEL_PALETTE).toContain(labelColor(n));
    }
  });
  it('the palette uses no red token', () => {
    for (const c of LABEL_PALETTE) {
      expect(c).not.toMatch(/failure|danger|bug/);
    }
    expect(LABEL_PALETTE).toHaveLength(8);
  });
});
