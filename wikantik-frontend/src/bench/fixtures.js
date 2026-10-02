/**
 * Realistic markdown fixtures for the performance benchmarks (`npm run bench`; never part of `vitest run`).
 * Every section mixes the constructs the editor's live preview and preview pane render: headings, lists,
 * tasks, wikilinks (plain, aliased, heading), inline + display math, fenced code, a table, a callout, an
 * image, a horizontal rule and — every fourth section — a whole-line page embed.
 */
const FENCE = '```';

function section(i) {
  const lines = [
    `## Section ${i}`,
    '',
    `Paragraph ${i} with **bold**, *emphasis*, \`code\`, a [[Page${i}]] link, [[Other${i % 7}#Intro|an alias]] and $x_${i}^2$ math.`,
    `It continues with ~~struck~~ text, [an external link](https://example.com/${i}) and [[Topic ${i % 11}#Details]].`,
    '',
    `- first item linking [[ListTarget${i % 13}]]`,
    '- [ ] an open task',
    '- [x] a finished task',
    '  - a nested item',
    '',
    '> [!note] Callout title',
    `> callout body ${i} with **bold** and [[Callout${i % 5}]]`,
    '',
    '$$',
    `\\int_0^${i} x\\,dx = \\frac{${i}^2}{2}`,
    '$$',
    '',
  ];
  if (i % 4 === 0) lines.push(`![[Embedded${i % 9}]]`, '');
  lines.push(
    `${FENCE}js`,
    `const value${i} = ${i};`,
    'console.log(value);',
    FENCE,
    '',
    '| a | b |',
    '|---|---|',
    `| ${i} | ${i * 2} |`,
    '',
    `![diagram ${i}](https://example.com/img/${i}.png)`,
    '',
    '---',
    '',
  );
  return lines;
}

/** A markdown document of at least `lineCount` lines (cut at a section boundary past it). */
export function makeDoc(lineCount) {
  const out = ['# Benchmark page', ''];
  for (let i = 1; out.length < lineCount; i += 1) out.push(...section(i));
  return out.join('\n');
}

/** A page body holding `linkCount` distinct wikilinks, about ten per paragraph. */
export function makeLinkDoc(linkCount) {
  const paras = [];
  for (let i = 0; i < linkCount; i += 10) {
    const links = [];
    for (let j = i; j < Math.min(i + 10, linkCount); j += 1) links.push(`[[LinkedPage${j}]]`);
    paras.push(`Paragraph ${i / 10} mentions ${links.join(', ')}.`);
  }
  return paras.join('\n\n');
}

/** 0-based character offset of the start of 1-based line `n`. */
export function lineStart(doc, n) {
  let pos = 0;
  for (let k = 1; k < n; k += 1) pos = doc.indexOf('\n', pos) + 1;
  return pos;
}

/** Print one machine-greppable measurement line (the benchmark report collects these). */
export function report(label, value) {
  console.log(`[perf] ${label}: ${value}`);
}
