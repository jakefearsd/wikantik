import { describe, it, expect } from 'vitest';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from './linearCost';

const linear = (n) => { let s = 0; for (let i = 0; i < n * 200; i += 1) s += i % 7; return s; };
const quadratic = (n) => { let s = 0; for (let i = 0; i < n; i += 1) for (let j = 0; j < n; j += 1) s += (i ^ j) & 1; return s; };

describe('linearCost', () => {
  it('passes linear work', () => {
    const { ratio, bound } = linearCost((n) => n, linear, { small: 2000 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
  it('fails quadratic work, so a guard built on it is not vacuous', () => {
    const { ratio, bound } = linearCost((n) => n, quadratic, { small: 1500 });
    expect(ratio).toBeGreaterThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
  it('bounds at the geometric midpoint between linear and quadratic', () => {
    expect(linearCost((n) => n, () => 0, { factor: 5, rounds: 1 }).bound).toBeCloseTo(5 ** 1.5);
  }, LINEAR_COST_TIMEOUT_MS);
});
