import { describe, it, expect } from 'vitest';
import { placeCard } from './placeCard';

const vp = { width: 1000, height: 800 };
const size = { width: 300, height: 120 };

describe('placeCard', () => {
  it('sits just below the link, aligned to its left edge, when there is room', () => {
    expect(placeCard({ left: 100, top: 50, bottom: 70 }, size, vp)).toEqual({ left: 100, top: 76 });
  });
  it('is pulled back inside the right edge', () => {
    expect(placeCard({ left: 900, top: 50, bottom: 70 }, size, vp).left).toBe(1000 - 300 - 8);
  });
  it('never starts left of the gutter', () => {
    expect(placeCard({ left: -40, top: 50, bottom: 70 }, size, vp).left).toBe(8);
  });
  it('flips above the link when there is no room below', () => {
    expect(placeCard({ left: 100, top: 740, bottom: 760 }, size, vp).top).toBe(740 - 6 - 120);
  });
  it('stays inside the top edge when neither side fits fully', () => {
    expect(placeCard({ left: 100, top: 60, bottom: 780 }, size, vp).top).toBe(8);
  });
  it('a viewport narrower than the card pins it to the gutter', () => {
    expect(placeCard({ left: 100, top: 50, bottom: 70 }, size, { width: 200, height: 800 }).left).toBe(8);
  });
});
