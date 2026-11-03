/**
 * The editor's comment ranges against the checked-in differential corpus (`__fixtures__/comments-differential.json`),
 * whose expected ranges are the server's `ReaderComments.ranges`, itself checked against the rendered page by
 * `CommentsDifferentialTest`. An input in `allow` is a known editor defect (a TODO list, not a waiver); the list may only shrink.
 */
import { describe, expect, it } from 'vitest';
import fixture from './__fixtures__/comments-differential.json';
import { commentRanges } from './obsidianComments';

const ALLOW_AT_COMMIT = 18;
const allowed = new Set(fixture.allow.map((a) => a.input));

describe('comment ranges: editor against the server corpus', () => {
  it('has the documented size, and every allow entry names its side and reason', () => {
    expect(fixture.cases).toHaveLength(fixture.count);
    for (const a of fixture.allow) {
      expect(a.side).toBe('editor');
      expect(a.reason).toBeTruthy();
    }
  });

  it('agrees on every case that is not allow-listed', () => {
    const mismatches = [];
    for (const c of fixture.cases) {
      if (allowed.has(c.input)) continue;
      const actual = commentRanges(c.input).map((r) => [r.from, r.to]);
      if (JSON.stringify(actual) !== JSON.stringify(c.ranges)) {
        mismatches.push(`${JSON.stringify(c.input)}: expected ${JSON.stringify(c.ranges)} got ${JSON.stringify(actual)}`);
      }
    }
    expect(mismatches.length, mismatches.slice(0, 20).join('\n')).toBe(0);
  });

  it('only ever allow-lists fewer inputs', () => {
    expect(fixture.allow.length).toBeLessThanOrEqual(ALLOW_AT_COMMIT);
  });
});
