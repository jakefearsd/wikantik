/**
 * The editor's comment ranges against the checked-in differential corpus (`__fixtures__/comments-differential.json`),
 * whose expected ranges are the server's `ReaderComments.ranges`, itself checked against the rendered page by
 * `CommentsDifferentialTest`. An input in `allow` is a known editor defect (a TODO list, not a waiver); the list may only shrink.
 */
import { describe, expect, it } from 'vitest';
import fixture from './__fixtures__/comments-differential.json';
import { commentRanges } from './obsidianComments';

const ALLOW_AT_COMMIT = 0;
const key = (input, crlf) => `${crlf ? 'crlf' : 'lf'}:${input}`;
const allowed = new Map(fixture.allow.map((a) => [key(a.input, a.crlf), a]));
const editorRanges = (input) => commentRanges(input).map((r) => [r.from, r.to]);

describe('comment ranges: editor against the server corpus', () => {
  it('has the documented size, and every allow entry names its side, reason and recorded editor output', () => {
    expect(fixture.cases).toHaveLength(fixture.count);
    expect(allowed.size).toBe(fixture.allow.length);
    for (const a of fixture.allow) {
      expect(a.side).toBe('editor');
      expect(a.reason).toBeTruthy();
      expect(Array.isArray(a.editorRanges)).toBe(true);
      expect(fixture.cases.some((c) => c.input === a.input)).toBe(true);
    }
  });

  for (const crlf of [false, true]) {
    it(`agrees on every ${crlf ? 'CRLF' : 'LF'} case, or changes exactly as its allow entry records`, () => {
      const problems = [];
      for (const c of fixture.cases) {
        const input = crlf ? c.input.replace(/\n/g, '\r\n') : c.input;
        const expected = crlf ? c.rangesCrlf : c.ranges;
        const actual = editorRanges(input);
        const entry = allowed.get(key(c.input, crlf));
        const agrees = JSON.stringify(actual) === JSON.stringify(expected);
        if (!entry && !agrees) {
          problems.push(`${JSON.stringify(input)}: expected ${JSON.stringify(expected)} got ${JSON.stringify(actual)}`);
        } else if (entry && agrees) {
          problems.push(`${JSON.stringify(input)}: the editor now agrees, remove it from allow`);
        } else if (entry && JSON.stringify(actual) !== JSON.stringify(entry.editorRanges)) {
          problems.push(`${JSON.stringify(input)}: the editor's output changed from ${JSON.stringify(entry.editorRanges)} to ${JSON.stringify(actual)}; update the allow entry`);
        }
      }
      expect(problems.length, problems.slice(0, 20).join('\n')).toBe(0);
    });
  }

  it('only ever allow-lists fewer inputs', () => {
    expect(fixture.allow.length).toBeLessThanOrEqual(ALLOW_AT_COMMIT);
  });
});
