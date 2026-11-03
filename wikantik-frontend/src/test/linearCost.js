/**
 * The one linear-time guard for parser and scanner tests: whether `run(n)` costs about `factor`x more at `factor`x the
 * input (linear) rather than about `factor`²x (quadratic). It compares ratios, never absolute milliseconds, and is built
 * to hold on a loaded machine (the full suite runs ~300 files in parallel):
 * - a warm-up run first, so JIT compilation is not billed to the small input;
 * - the small and large inputs are timed alternately, so a slow stretch of the machine slows both;
 * - each size keeps its best (minimum) of `rounds`, which discards transient stalls;
 * - the bound is `factor ** 1.5`, the geometric midpoint between linear (`factor`) and quadratic (`factor ** 2`).
 */

/**
 * The per-test timeout every `linearCost` test passes (`it(name, fn, LINEAR_COST_TIMEOUT_MS)`): the helper runs the work
 * eleven times, which outlasts vitest's 5 s default on a slow CI runner while the ratio itself is fine.
 */
export const LINEAR_COST_TIMEOUT_MS = 60_000;
function msOf(fn) {
  const t = process.hrtime.bigint();
  fn();
  return Number(process.hrtime.bigint() - t) / 1e6;
}

/**
 * Times `make(n)`'s cost through `run` at `small` and `factor * small`; returns `{ ratio, bound }`.
 * `make(n)` builds the input once per size (not timed); `run(input)` is the timed work.
 */
export function linearCost(make, run, { small = 5000, factor = 4, rounds = 5, floorMs = 0.5 } = {}) {
  const a = make(small);
  const b = make(small * factor);
  run(a); // warm up
  let bestA = Infinity;
  let bestB = Infinity;
  for (let i = 0; i < rounds; i += 1) {
    bestA = Math.min(bestA, msOf(() => run(a)));
    bestB = Math.min(bestB, msOf(() => run(b)));
  }
  return { ratio: bestB / Math.max(bestA, floorMs), bound: factor ** 1.5 };
}
