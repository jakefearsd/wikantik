import { describe, it, expect } from 'vitest';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from '../../test/linearCost';
import { dialectParser } from './index';

/** The text of each TableCell, row by row (header first), read from the dialect tree. */
function cells(text) {
  const rows = [];
  dialectParser.parse(text).iterate({
    enter: (n) => {
      if (n.name === 'TableHeader' || n.name === 'TableRow') rows.push([]);
      if (n.name === 'TableCell') rows[rows.length - 1].push(text.slice(n.from, n.to));
      return undefined;
    },
  });
  return rows;
}
const SEP = '|---|---|\n';

describe('table cells follow the reader: code spans and inline math stay whole across a pipe', () => {
  it('a code span holds its pipe (rendered by the reader: <td><code>x | %%secret%% | y</code></td>)', () => {
    expect(cells(`| h | h |\n${SEP}| \`x | %%secret%% | y\` |`)).toEqual([['h', 'h'], ['`x | %%secret%% | y`']]);
  });
  it('a double-backtick span holds its pipe, in a body row and in the header', () => {
    expect(cells(`| h | \`a|b\` |\n${SEP}| \`\`a|b\`\` | z |`)).toEqual([['h', '`a|b`'], ['``a|b``', 'z']]);
  });
  it('inline math holds its pipe (rendered: one math span across the cell)', () => {
    expect(cells(`| A | B |\n${SEP}| $a %%x%% | b$ |`)).toEqual([['A', 'B'], ['$a %%x%% | b$']]);
  });
  it('an unclosed backtick or a lone dollar splits at the pipe as before', () => {
    expect(cells(`| h | h |\n${SEP}| \`x | y |`)).toEqual([['h', 'h'], ['`x', 'y']]);
    expect(cells(`| h | h |\n${SEP}| $5 | x |`)).toEqual([['h', 'h'], ['$5', 'x']]);
  });
  it('a span never reaches into the next row', () => {
    expect(cells(`| h | h |\n${SEP}| \`x | y |\n| z\` | w |`)).toEqual([['h', 'h'], ['`x', 'y'], ['z`', 'w']]);
  });
  it('an escaped pipe and an escaped backtick behave as in GFM', () => {
    expect(cells(`| h | h |\n${SEP}| a\\|b | c |`)).toEqual([['h', 'h'], ['a\\|b', 'c']]);
    expect(cells(`| h | h |\n${SEP}| \\\`a | b\` |`)).toEqual([['h', 'h'], ['\\`a', 'b`']]);
  });
  it('a plain table is unchanged', () => {
    expect(cells(`| h | h |\n${SEP}| 1 | 2 |\n| 3 | 4 |\n`)).toEqual([['h', 'h'], ['1', '2'], ['3', '4']]);
  });
  it('holds with CRLF line ends', () => {
    expect(cells(`| h | h |\r\n${SEP.replace('\n', '\r\n')}| \`x | y\` | z |\r\n`)).toEqual([['h', 'h'], ['`x | y`', 'z']]);
  });
  it('a table in a list item reads the same', () => {
    expect(cells(`- | h | h |\n  ${SEP}  | \`x | y\` | z |`)).toEqual([['h', 'h'], ['`x | y`', 'z']]);
  });
  it('is parsed as a Table node like GFM\'s', () => {
    const names = [];
    dialectParser.parse(`| h | h |\n${SEP}| 1 | 2 |`).iterate({ enter: (n) => { names.push(n.name); } });
    expect(names).toContain('Table');
    expect(names).toContain('TableDelimiter');
  });
  it('a long row of code spans stays linear', () => {
    const { ratio, bound } = linearCost((n) => `| h | h |\n${SEP}| ${'`a|b` '.repeat(n)}| x |\n`, (s) => dialectParser.parse(s), { small: 2000, factor: 4 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
  it('a long row of dollars stays linear', () => {
    const { ratio, bound } = linearCost((n) => `| h | h |\n${SEP}| ${'$a '.repeat(n)}| x |\n`, (s) => dialectParser.parse(s), { small: 2000, factor: 4 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
});
