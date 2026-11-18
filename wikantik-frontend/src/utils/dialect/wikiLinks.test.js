import { describe, it, expect } from 'vitest';
import { linearCost, LINEAR_COST_TIMEOUT_MS } from '../../test/linearCost';
import { dialectParser } from './index';
import { wikiLinkRanges, wikiLinkNodeAt } from './wikiLinks';
import { inlineTagRanges } from './inlineTags';
import fixture from '../__fixtures__/wikilinks.json';

const parse = (t) => dialectParser.parse(t);
const links = (t) => wikiLinkRanges(parse(t)).map((r) => t.slice(r.from, r.to));
const shape = (t) => {
  const out = [];
  parse(t).iterate({ enter: (n) => { if (/^Wiki/.test(n.name)) out.push([n.name, t.slice(n.from, n.to)]); } });
  return out;
};

describe('WikiLink node (shared fixture wikilinks.json)', () => {
  // The reader renders one anchor per `expected` entry; the editor must find exactly those wikilinks (none in code,
  // none with a leading space like `[[ -f x ]]`).
  it.each(fixture.map((c) => [c.name, c]))('%s', (_name, c) => {
    expect(links(c.markdown)).toHaveLength(c.expected.length);
  });
  it('finds the two links of a line', () => {
    expect(links('[[ParityTarget]] and [[NoSuchParityPage|n]]\n')).toEqual(['[[ParityTarget]]', '[[NoSuchParityPage|n]]']);
  });
  it('a table cell holds its link with an escaped pipe', () => {
    expect(links('| a |\n|---|\n| [[ParityTarget\\|cell]] |\n')).toEqual(['[[ParityTarget\\|cell]]']);
  });
});

describe('WikiLink shape', () => {
  it('page, heading and alias children', () => {
    expect(shape('[[P#H|a b]]')).toEqual([
      ['WikiLink', '[[P#H|a b]]'], ['WikiLinkMark', '[['], ['WikiLinkTarget', 'P#H'], ['WikiLinkMark', '|'],
      ['WikiLinkAlias', 'a b'], ['WikiLinkMark', ']]'],
    ]);
  });
  it('a same-page heading has a target and no alias', () => {
    expect(shape('[[#H]]').map((x) => x[0])).toEqual(['WikiLink', 'WikiLinkMark', 'WikiLinkTarget', 'WikiLinkMark']);
  });
  it('an escaped pipe ends the target before its backslash', () => {
    expect(shape('[[A\\|b]]')).toContainEqual(['WikiLinkTarget', 'A']);
  });
  it('a second pipe stays in the alias', () => {
    expect(shape('[[a|b|c]]')).toContainEqual(['WikiLinkAlias', 'b|c']);
  });
  it('an embed is a WikiEmbed starting at the bang', () => {
    expect(shape('![[f.png|300]]')[0]).toEqual(['WikiEmbed', '![[f.png|300]]']);
    expect(wikiLinkRanges(parse('x ![[f.png]]'))).toEqual([{ from: 2, to: 12, embed: true }]);
  });
  it('is never a Link', () => {
    let link = false;
    parse('[[x]] ![[y.png]]').iterate({ enter: (n) => { if (n.name === 'Link' || n.name === 'Image') link = true; } });
    expect(link).toBe(false);
  });
});

describe('WikiLink edges', () => {
  it('is not found in code or math', () => {
    expect(links('`[[a]]` $[[b]]$ ```[[c]]``` x')).toEqual([]);
  });
  it('an escaped opener is text; an escaped bang leaves the link', () => {
    expect(links('\\[[x]]')).toEqual([]);
    expect(links('\\![[x]]')).toEqual(['[[x]]']);
    expect(links('\\\\[[x]]')).toEqual(['[[x]]']);
  });
  it('unclosed, empty, spaced and multi-line are text', () => {
    expect(links('[[x')).toEqual([]);
    expect(links('[[]] [[ x]] [[x\ny]]')).toEqual([]);
    expect(links('[[a[b]]')).toEqual([]);
  });
  it('is found inside a markdown link text and a heading', () => {
    expect(links('[see [[A]]](u)')).toEqual(['[[A]]']);
    expect(links('# H [[A]]')).toEqual(['[[A]]']);
  });
  it('a # inside is a heading fragment, not a tag', () => {
    const t = '[[Page#Heading]] #real';
    expect(inlineTagRanges(parse(t), { slice: (a, b) => t.slice(a, b) }).map((r) => t.slice(r.from, r.to))).toEqual(['#real']);
  });
  it('wikiLinkNodeAt resolves through children', () => {
    const t = 'x [[Page|a]] y';
    const tree = parse(t);
    expect(wikiLinkNodeAt(tree, 6).from).toBe(2);
    expect(wikiLinkNodeAt(tree, 0)).toBeNull();
  });
});

describe('WikiLink and code spans', () => {
  const kinds = (text, name) => { const out = []; parse(text).iterate({ enter: (n) => { if (n.name === name) out.push(text.slice(n.from, n.to)); } }); return out; };
  it('a code span opened inside the link text and closed after it wins: no link, the span holds `]]`', () => {
    // Rendered by the reader: `[[Note <code>x]] %%secret%% </code>y&#96; z` (comments.json).
    const text = '[[Note `x]] %%secret%% `y` z';
    expect(kinds(text, 'WikiLink')).toEqual([]);
    expect(kinds(text, 'InlineCode')).toEqual(['`x]] %%secret%% `']);
  });
  it('the run lengths must agree: a double backtick opens nothing against a single', () => {
    expect(kinds('[[Note ``x]] a `y` z', 'WikiLink')).toEqual(['[[Note ``x]]']);
  });
  it('an unclosed backtick inside the link text leaves the link', () => {
    expect(kinds('[[Note `x]] after', 'WikiLink')).toEqual(['[[Note `x]]']);
  });
  it('a code span that opens and closes inside the link text leaves the link', () => {
    expect(kinds('[[a `b` c]] `d`', 'WikiLink')).toEqual(['[[a `b` c]]']);
  });
  it('an escaped backtick opens no span', () => {
    expect(kinds('[[a \\`b]] and `c`', 'WikiLink')).toEqual(['[[a \\`b]]']);
  });
  it('a CRLF paragraph reads the same', () => {
    expect(kinds('[[Note `x]] %%secret%% `y` z\r\n', 'WikiLink')).toEqual([]);
  });
  it.each([
    ['links holding a span of their own', (n) => '[[a `b` c]] '.repeat(n)],
    ['one link whose span never closes, before a long tail', (n) => `[[a\`b]] ${'word '.repeat(n)}`],
  ])('%s: stays linear', (_name, make) => {
    const { ratio, bound } = linearCost(make, parse, { small: 4000, factor: 5 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
});

describe('WikiLink and inline math', () => {
  // The reader lets an inline-math span win over a wikilink whose text opens it, exactly as a code span does.
  const crlf = (t) => t.replace(/\n/g, '\r\n');
  it.each([
    ['[[Note $x]] %%secret%% y$ z', []],
    ['[[Note $x]] y$ z', []],
    ['[[a $x$ b]] and [[c]]', ['[[a $x$ b]]', '[[c]]']],
    ['[[Cost 5$]] and 3', ['[[Cost 5$]]']],
    ['[[a \\$x]] y$', ['[[a \\$x]]']],
    ['[[a $ x]] y$ z', ['[[a $ x]]']],
  ])('%j', (text, expected) => {
    expect(links(text)).toEqual(expected);
    expect(links(crlf(text))).toEqual(expected);
  });
  it('a line break before the closing dollar keeps the paragraph, so the math still wins', () => {
    expect(links('[[Note $x]] a\nb$ z')).toEqual([]);
    expect(links('[[Note $x]] a\r\nb$ z')).toEqual([]);
  });
  it('stays linear with many dollars in link text', () => {
    const make = (n) => '[[a $b]] '.repeat(n);
    const { ratio, bound } = linearCost(make, parse, { small: 4000, factor: 5 });
    expect(ratio).toBeLessThan(bound);
    // one link whose math opener closes far past it, and one whose text is nothing but dollars
    for (const shape of [(n) => `[[a$b]] ${'word '.repeat(n)}c$ d`, (n) => `[[${'$ '.repeat(n)}]] x`]) {
      const r = linearCost(shape, parse, { small: 4000, factor: 5 });
      expect(r.ratio).toBeLessThan(r.bound);
    }
  }, LINEAR_COST_TIMEOUT_MS);
});

describe('WikiLink bracket matching (the reader pairs the brackets, #130)', () => {
  const kinds = (text, name) => { const out = []; parse(text).iterate({ enter: (n) => { if (n.name === name) out.push(text.slice(n.from, n.to)); } }); return out; };
  it('a pair that spans lines is WikiLinkText, not a link, and nothing inside it is drawn', () => {
    const text = 'x [[a\n*b* #tag `c`]] y';
    expect(links(text)).toEqual([]);
    expect(kinds(text, 'WikiLinkText')).toEqual(['[[a\n*b* #tag `c`]]']);
    expect(kinds(text, 'Emphasis')).toEqual([]);
    expect(kinds(text, 'InlineCode')).toEqual([]);
    expect(inlineTagRanges(parse(text))).toEqual([]);
  });
  it('a pair that is no wikilink on one line is WikiLinkText too', () => {
    expect(shape('[[ *x* ]] [[]]')).toEqual([['WikiLinkText', '[[ *x* ]]'], ['WikiLinkText', '[[]]']]);
    expect(kinds('[[ *x* ]]', 'Emphasis')).toEqual([]);
  });
  it('nothing inside a pair is a node: the reader draws it from its source text', () => {
    for (const text of ['[[P|a `x` $m$ *e* ==h== #t]]', '[[a `x`#h|b]]', '![[a `x`]]', '[[a\n`x`|\n`y`]]']) {
      for (const name of ['InlineCode', 'InlineMath', 'Emphasis', 'Highlight']) expect(kinds(text, name)).toEqual([]);
      expect(inlineTagRanges(parse(text))).toEqual([]);
    }
  });
  it('the link keeps its children', () => {
    expect(shape('[[P|a `x`]]')).toEqual([['WikiLink', '[[P|a `x`]]'], ['WikiLinkMark', '[['], ['WikiLinkTarget', 'P'],
      ['WikiLinkMark', '|'], ['WikiLinkAlias', 'a `x`'], ['WikiLinkMark', ']]']]);
  });
  it('a pair may span the lines of a blockquote or a list item', () => {
    expect(kinds('> x [[a\n> b]] y', 'WikiLinkText')).toEqual(['[[a\n> b]]']);
    expect(kinds('- x [[a\n  b]] y', 'WikiLinkText')).toEqual(['[[a\n  b]]']);
    expect(links('> x [[a]]\n> [[b|c]] y')).toEqual(['[[a]]', '[[b|c]]']);
  });
  it('the everyday shapes are links wherever they stand', () => {
    expect(links('# H [[A]]\n\n*em [[B|b]]* **[[C#h]]** ![[i.png|300]]\n\n| [[D\\|d]] | [[E]] |\n|---|---|\n\n[t [[F]] u](https://example.com) [[G]]] [[H]]'))
      .toEqual(['[[A]]', '[[B|b]]', '[[C#h]]', '![[i.png|300]]', '[[D\\|d]]', '[[E]]', '[[F]]', '[[G]]', '[[H]]']);
  });
  it('a lone bracket inside ends the pair as text; a second [[ starts anew', () => {
    expect(shape('[[a [b] c]]').filter(([n]) => n !== 'WikiLinkMark')).toEqual([]);
    expect(shape('[[a] b]]')).toEqual([]);
    expect(links('[[a [[b]] c]]')).toEqual(['[[b]]']);
    expect(links('[[[a]]')).toEqual(['[[a]]']);
    expect(links('[[a]]]')).toEqual(['[[a]]']);
  });
  it('a bracket held by a code span, math or an escape does not end the pair', () => {
    expect(kinds('[[a `]` b]]', 'WikiLinkText')).toEqual(['[[a `]` b]]']);
    expect(kinds('[[a $]$ b]]', 'WikiLinkText')).toEqual(['[[a $]$ b]]']);
    expect(kinds('[[a \\] b]]', 'WikiLinkText')).toEqual(['[[a \\] b]]']);
  });
  it('an image inside is the one bracket pair it survives; an unclosed image bracket is not', () => {
    expect(kinds('[[a ![i](u) b]]', 'WikiLinkText')).toEqual(['[[a ![i](u) b]]']);
    expect(shape('[[a ![b c]]')).toEqual([]);
  });
  it('a web address or an HTML tag in the text leaves the link', () => {
    expect(links('[[https://example.com/a]] [[P|see www.example.com]] [[P|a <b>c</b>]]'))
      .toEqual(['[[https://example.com/a]]', '[[P|see www.example.com]]', '[[P|a <b>c</b>]]']);
  });
  it.each(['[[a\n', '[[a ![i](u) ', '[[a ![i] ', '[[a] ', '[[a `b` ', '![[a\n]] '])('%j repeated stays linear', (unit) => {
    const { ratio, bound } = linearCost((n) => unit.repeat(n), parse, { small: 4000, factor: 5 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
  it('one pair holding many images stays linear', () => {
    const { ratio, bound } = linearCost((n) => `[[a ${'![i](u) '.repeat(n)}]]`, parse, { small: 2000, factor: 5 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
});

describe('WikiLink code-span lookup cost', () => {
  it('links with unclosed backtick runs of ever-longer lengths stay linear in the text', () => {
    // One link per run length k = 1..K, so no run has an equal-length closer: each lookup used to scan to the end of the
    // paragraph (K links x K^2/2 characters). The text is about n characters; 16x the text must cost well under 16^1.25x.
    const make = (n) => {
      let out = '';
      for (let k = 1; out.length < n; k += 1) out += `[[a${'`'.repeat(k)}b]] `;
      return out;
    };
    const { ratio } = linearCost(make, parse, { small: 4000, factor: 16 });
    expect(ratio).toBeLessThan(16 ** 1.25);
  }, LINEAR_COST_TIMEOUT_MS);
});

describe('WikiLink parser cost', () => {
  it.each(['[[', '[[a', ']]', '[[a|', '![[', '[[a]]', '![[a|b '])('%j repeated stays linear', (unit) => {
    const { ratio, bound } = linearCost((n) => unit.repeat(n), parse, { small: 4000, factor: 5 });
    expect(ratio).toBeLessThan(bound);
  }, LINEAR_COST_TIMEOUT_MS);
});

describe('wikiLinkNodeAt', () => {
  it('finds the link at its last position too, so a click on the right half of the closing ] opens it', () => {
    const tree = parse('a [[x]] b');
    expect(wikiLinkNodeAt(tree, 7)).not.toBeNull();
    expect(wikiLinkNodeAt(tree, 2)).not.toBeNull();
    expect(wikiLinkNodeAt(tree, 8)).toBeNull();
    expect(wikiLinkNodeAt(tree, 1)).toBeNull();
  });
});
